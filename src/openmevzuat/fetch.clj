(ns openmevzuat.fetch
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hato.client :as http])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.channels ClosedChannelException UnresolvedAddressException]
           [java.net ConnectException NoRouteToHostException SocketTimeoutException URI UnknownHostException]
           [java.net.http HttpConnectTimeoutException HttpTimeoutException]
           [java.time Instant ZonedDateTime]
           [java.time.format DateTimeFormatter]
           [java.util Date]
           [org.apache.pdfbox Loader]
           [org.apache.pdfbox.text PDFTextStripper]))

(def default-fetch-config
  {:attempts 6
   :request-delay-ms 2500
   :backoff-ms 5000
   :max-backoff-ms 120000
   :connect-timeout-ms 60000
   :timeout-ms 300000
   :preflight? true
   :preflight-attempts 2
   :circuit-breaker-failures 3})

(def retriable-statuses #{408 425 429 500 502 503 504})

(defonce last-request-at-ms (atom 0))
(defonce source-circuit-state (atom {}))

(defn now-date []
  (Date/from (Instant/now)))

(defn- parse-long-value [value fallback]
  (try
    (Long/parseLong (str/trim (str value)))
    (catch Exception _
      fallback)))

(defn- env-long [name fallback minimum]
  (max minimum
       (parse-long-value (System/getenv name) fallback)))

(defn- env-bool [name fallback]
  (case (some-> (System/getenv name) str/lower-case str/trim)
    "true" true
    "1" true
    "yes" true
    "false" false
    "0" false
    "no" false
    fallback))

(defn fetch-config []
  {:attempts (env-long "OPENMEVZUAT_FETCH_ATTEMPTS"
                       (:attempts default-fetch-config)
                       1)
   :request-delay-ms (env-long "OPENMEVZUAT_FETCH_DELAY_MS"
                               (:request-delay-ms default-fetch-config)
                               0)
   :backoff-ms (env-long "OPENMEVZUAT_FETCH_BACKOFF_MS"
                         (:backoff-ms default-fetch-config)
                         0)
   :max-backoff-ms (env-long "OPENMEVZUAT_FETCH_MAX_BACKOFF_MS"
                             (:max-backoff-ms default-fetch-config)
                             0)
   :connect-timeout-ms (env-long "OPENMEVZUAT_FETCH_CONNECT_TIMEOUT_MS"
                                 (:connect-timeout-ms default-fetch-config)
                                 1)
   :timeout-ms (env-long "OPENMEVZUAT_FETCH_TIMEOUT_MS"
                         (:timeout-ms default-fetch-config)
                         1)
   :preflight? (env-bool "OPENMEVZUAT_PREFLIGHT_ENABLED"
                         (:preflight? default-fetch-config))
   :preflight-attempts (env-long "OPENMEVZUAT_PREFLIGHT_ATTEMPTS"
                                 (:preflight-attempts default-fetch-config)
                                 1)
   :circuit-breaker-failures (env-long "OPENMEVZUAT_CIRCUIT_BREAKER_FAILURES"
                                       (:circuit-breaker-failures default-fetch-config)
                                       1)})

(defn- sleep-ms! [ms]
  (when (pos? ms)
    (Thread/sleep ms)))

(defn- throttle! [request-delay-ms]
  (when (pos? request-delay-ms)
    (locking last-request-at-ms
      (let [now (System/currentTimeMillis)
            wait-ms (- request-delay-ms (- now @last-request-at-ms))]
        (sleep-ms! wait-ms)
        (reset! last-request-at-ms (System/currentTimeMillis))))))

(defn fixture-mode? []
  (= "true" (some-> (System/getenv "OPENMEVZUAT_FIXTURE_MODE") str/lower-case)))

(defn fixture-resource [document]
  (str "fixtures/" (:document/number document) ".txt"))

(defn read-fixture [document]
  (when-let [resource (io/resource (fixture-resource document))]
    (slurp resource)))

(defn pdf-bytes->text [bytes]
  (with-open [document (Loader/loadPDF bytes)]
    (let [stripper (PDFTextStripper.)]
      (.setSortByPosition stripper true)
      (.getText stripper document))))

(declare fetch-url)

(defn- pdf-url? [url]
  (let [url (str/lower-case url)]
    (or (str/ends-with? url ".pdf")
        (str/includes? url "/file/generatepdf"))))

(defn generated-pdf-url [url]
  (when-let [[_ mevzuat-tur mevzuat-tertip mevzuat-no]
             (re-find #"(?i)/MevzuatMetin/([0-9]+)\.([0-9]+)\.([0-9]+)\.pdf$"
                      (str url))]
    (when (= "1" mevzuat-tur)
      (format "https://www.mevzuat.gov.tr/File/GeneratePdf?mevzuatNo=%s&mevzuatTur=Kanun&mevzuatTertip=%s"
              mevzuat-no
              mevzuat-tertip))))

(defn- generated-pdf-fallback-error? [e]
  (and (instance? clojure.lang.ExceptionInfo e)
       (= "Expected PDF source but response was not a PDF" (.getMessage e))))

(defn- fetch-url-with-generated-pdf-fallback [url]
  (try
    {:text (fetch-url url)
     :url url}
    (catch Exception e
      (if-let [fallback-url (and (generated-pdf-fallback-error? e)
                                 (generated-pdf-url url))]
        (do
          (binding [*out* *err*]
            (println "PDF URL returned HTML; retrying generated PDF endpoint:" fallback-url))
          {:text (fetch-url fallback-url)
           :url fallback-url})
        (throw e)))))

(defn- pdf-bytes? [body]
  (and (bytes? body)
       (<= 5 (alength ^bytes body))
       (= "%PDF-" (String. ^bytes body 0 5 StandardCharsets/US_ASCII))))

(defn- body-preview [body]
  (when (bytes? body)
    (let [length (min 256 (alength ^bytes body))]
      (-> (String. ^bytes body 0 length StandardCharsets/UTF_8)
          (str/replace #"\s+" " ")
          str/trim))))

(defn- throttled-body? [body]
  (let [preview (some-> body body-preview str/lower-case)]
    (boolean
     (and preview
          (some #(str/includes? preview %)
                ["too many requests"
                 "rate limit"
                 "throttl"
                 "captcha"
                 "temporarily unavailable"
                 "service unavailable"])))))

(defn- header-value [headers name]
  (some (fn [[k v]]
          (when (= (str/lower-case (str k)) name)
            v))
        headers))

(defn- retry-after-ms [headers]
  (when-let [raw (some-> (header-value headers "retry-after") str str/trim not-empty)]
    (if (re-matches #"\d+" raw)
      (* 1000 (parse-long-value raw 0))
      (try
        (let [retry-at (.toInstant (ZonedDateTime/parse raw DateTimeFormatter/RFC_1123_DATE_TIME))]
          (max 0 (- (.toEpochMilli retry-at) (System/currentTimeMillis))))
        (catch Exception _
          nil)))))

(defn- request-options [{:keys [connect-timeout-ms timeout-ms]}]
  {:as :byte-array
   :headers {"User-Agent" "OpenMevzuat/0.1"
             "Accept" "application/pdf,text/plain,text/html;q=0.8,*/*;q=0.1"}
   :throw-exceptions false
   :timeout timeout-ms
   :version :http-1.1
   :http-client {:connect-timeout connect-timeout-ms
                 :redirect-policy :normal
                 :version :http-1.1}})

(defn- origin-key [url]
  (let [uri (URI/create url)]
    (str (.getScheme uri) "://" (.getHost uri)
         (let [port (.getPort uri)]
           (when (pos? port)
             (str ":" port))))))

(defn- source-unreachable-data [data]
  (assoc data
         :openmevzuat/error :source-unreachable
         :source/unreachable? true))

(defn source-unreachable? [e]
  (= :source-unreachable (:openmevzuat/error (ex-data e))))

(defn- maybe-source-unreachable-data [data connection?]
  (cond-> data
    connection? source-unreachable-data))

(defn- cause-chain [e]
  (take-while some? (iterate #(.getCause ^Throwable %) e)))

(defn- connection-exception? [e]
  (boolean
   (some #(or (instance? HttpConnectTimeoutException %)
              (instance? HttpTimeoutException %)
              (instance? ConnectException %)
              (instance? SocketTimeoutException %)
              (instance? NoRouteToHostException %)
              (instance? UnknownHostException %)
              (instance? UnresolvedAddressException %)
              (instance? ClosedChannelException %))
         (cause-chain e))))

(defn- circuit-open-error [url config]
  (let [key (origin-key url)
        state (get @source-circuit-state key)
        threshold (:circuit-breaker-failures config)]
    (when (and (pos? threshold)
               (>= (:connection-failures state 0) threshold))
      (ex-info "Source connection circuit breaker is open"
               (source-unreachable-data
                {:url url
                 :source/origin key
                 :connection-failures (:connection-failures state)
                 :last-error (:last-error state)
                 :circuit-breaker/threshold threshold
                 :circuit-breaker/reason "connection could not be established"})))))

(defn- record-circuit-success! [url]
  (swap! source-circuit-state dissoc (origin-key url)))

(defn- record-circuit-failure! [url reason]
  (let [key (origin-key url)]
    (get
     (swap! source-circuit-state
            (fn [state]
              (update state key
                      (fn [entry]
                        {:connection-failures (inc (:connection-failures entry 0))
                         :last-error reason
                         :last-failed-at (Date/from (Instant/now))}))))
     key)))

(defn- response-action [url {:keys [status body headers]}]
  (cond
    (contains? retriable-statuses status)
    {:action :retry
     :reason (if (= 429 status)
               "server returned 429, possible throttling"
               (str "server returned " status))
     :retry-after-ms (retry-after-ms headers)}

    (not (<= 200 status 299))
    {:action :fail
     :message "Failed to fetch source URL"
     :data {:url url
            :status status
            :body-preview (body-preview body)}}

    (and (pdf-url? url) (not (pdf-bytes? body)))
    (if (throttled-body? body)
      {:action :retry
       :reason "PDF URL returned a throttling-like non-PDF response"
       :retry-after-ms (retry-after-ms headers)}
      {:action :fail
       :message "Expected PDF source but response was not a PDF"
       :data {:url url
              :status status
              :content-type (header-value headers "content-type")
              :body-preview (body-preview body)}})

    :else
    {:action :success}))

(defn- response-body->text [url body]
  (if (pdf-url? url)
    (pdf-bytes->text body)
    (String. ^bytes body StandardCharsets/UTF_8)))

(defn- retriable-exception? [e]
  (or (not (instance? clojure.lang.ExceptionInfo e))
      (:retry? (ex-data e))))

(defn- exception-summary [e]
  (str (.getSimpleName (class e))
       (when-let [message (not-empty (.getMessage e))]
         (str ": " message))))

(defn- retry-delay-ms [{:keys [backoff-ms max-backoff-ms]} attempt action]
  (let [delay (or (:retry-after-ms action)
                  (* backoff-ms (bit-shift-left 1 (max 0 (dec attempt)))))]
    (min max-backoff-ms (max 0 delay))))

(defn- log-retry! [url attempt attempts reason delay-ms]
  (binding [*out* *err*]
    (println
     (format "Fetch failed for %s (attempt %d/%d): %s. Retrying in %d ms."
             url attempt attempts (or reason "unknown error") delay-ms))))

(defn- final-fetch-error [url config attempt result]
  (if-let [exception (:exception result)]
    (if (instance? clojure.lang.ExceptionInfo exception)
      exception
      (ex-info "Failed to fetch source URL after retries"
               (maybe-source-unreachable-data
                {:url url
                 :attempt attempt
                 :attempts (:attempts config)
                 :cause (exception-summary exception)
                 :connection? (:connection? result)}
                (:connection? result))
               exception))
    (ex-info (:message result "Failed to fetch source URL after retries")
             (assoc (:data result)
                    :attempt attempt
                    :attempts (:attempts config)
                    :reason (:reason result)))))

(defn fetch-url
  ([url]
   (fetch-url url (fetch-config)))
  ([url config]
   (loop [attempt 1]
     (when-let [e (circuit-open-error url config)]
       (throw e))
     (let [result (try
                    (throttle! (:request-delay-ms config))
                    (let [response (http/get url (request-options config))
                          action (response-action url response)]
                      (if (= :success (:action action))
                        {:ok? true
                         :text (response-body->text url (:body response))}
                        (assoc action :ok? false)))
                    (catch Exception e
                      (if (retriable-exception? e)
                        {:ok? false
                         :action :retry
                         :reason (exception-summary e)
                         :connection? (connection-exception? e)
                         :exception e}
                        {:ok? false
                         :action :fail
                         :exception e})))]
       (if (:ok? result)
         (do
           (record-circuit-success! url)
           (:text result))
         (do
           (when (:connection? result)
             (record-circuit-failure! url (:reason result)))
           (if-let [e (circuit-open-error url config)]
             (throw e)
             (if (and (= :retry (:action result))
                      (< attempt (:attempts config)))
               (let [delay-ms (retry-delay-ms config attempt result)]
                 (log-retry! url attempt (:attempts config) (:reason result) delay-ms)
                 (sleep-ms! delay-ms)
                 (recur (inc attempt)))
               (throw (final-fetch-error url config attempt result))))))))))

(defn preflight-source!
  ([source]
   (preflight-source! source (fetch-config)))
  ([source config]
   (when (and (:preflight? config)
              (not (fixture-mode?)))
     (let [url (:source/base-url source)
           attempts (:preflight-attempts config)]
       (when (not-empty url)
         (loop [attempt 1]
           (when-let [e (circuit-open-error url config)]
             (throw e))
           (let [result (try
                          (throttle! (:request-delay-ms config))
                          (let [response (http/get url (request-options config))]
                            {:ok? true :status (:status response)})
                          (catch Exception e
                            {:ok? false
                             :reason (exception-summary e)
                             :connection? (connection-exception? e)
                             :exception e}))]
             (if (:ok? result)
               (do
                 (record-circuit-success! url)
                 result)
               (do
                 (when (:connection? result)
                   (record-circuit-failure! url (:reason result)))
                 (if-let [e (circuit-open-error url config)]
                   (throw e)
                   (if (< attempt attempts)
                     (let [delay-ms (retry-delay-ms config attempt result)]
                       (log-retry! url attempt attempts
                                   (str "source preflight failed: " (:reason result))
                                   delay-ms)
                       (sleep-ms! delay-ms)
                       (recur (inc attempt)))
                     (throw
                     (ex-info "Source preflight failed"
                               (maybe-source-unreachable-data
                                {:source/id (:source/id source)
                                 :source/name (:source/name source)
                                 :source/base-url url
                                 :attempt attempt
                                 :attempts attempts
                                 :reason (:reason result)
                                 :connection? (:connection? result)}
                                (:connection? result))
                               (:exception result))))))))))))))

(defn preflight-sources! [sources]
  (let [config (fetch-config)]
    (when (and (:preflight? config)
               (not (fixture-mode?)))
      (doseq [source (->> sources
                          (filter :source/enabled?)
                          (distinct))]
        (preflight-source! source config)))))

(defn fetch-document [document source]
  (let [fetched-at (now-date)
        url (:source/url document)
        fixture? (fixture-mode?)
        fetched (if fixture?
                  {:text (or (read-fixture document)
                             (throw (ex-info "Fixture not found" {:document/id (:document/id document)})))
                   :url url}
                  (fetch-url-with-generated-pdf-fallback url))]
    (assoc document
           :source/name (:source/name source)
           :source/base-url (:source/base-url source)
           :source/url (:url fetched)
           :source/original-url url
           :source/fetched-at fetched-at
           :source/fixture? fixture?
           :text/full (:text fetched))))
