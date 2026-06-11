(ns openmevzuat.fetch
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hato.client :as http])
  (:import [java.nio.charset StandardCharsets]
           [java.time Instant ZonedDateTime]
           [java.time.format DateTimeFormatter]
           [java.util Date]
           [org.apache.pdfbox Loader]
           [org.apache.pdfbox.text PDFTextStripper]))

(def default-fetch-config
  {:attempts 4
   :request-delay-ms 1000
   :backoff-ms 1500
   :max-backoff-ms 20000
   :connect-timeout-ms 15000
   :timeout-ms 180000})

(def retriable-statuses #{408 425 429 500 502 503 504})

(defonce last-request-at-ms (atom 0))

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

(defn- pdf-url? [url]
  (str/ends-with? (str/lower-case url) ".pdf"))

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
               {:url url
                :attempt attempt
                :attempts (:attempts config)
                :cause (exception-summary exception)}
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
                         :exception e}
                        {:ok? false
                         :action :fail
                         :exception e})))]
       (if (:ok? result)
         (:text result)
         (if (and (= :retry (:action result))
                  (< attempt (:attempts config)))
           (let [delay-ms (retry-delay-ms config attempt result)]
             (log-retry! url attempt (:attempts config) (:reason result) delay-ms)
             (sleep-ms! delay-ms)
             (recur (inc attempt)))
           (throw (final-fetch-error url config attempt result))))))))

(defn fetch-document [document source]
  (let [fetched-at (now-date)
        url (:source/url document)
        fixture? (fixture-mode?)
        text (if fixture?
               (or (read-fixture document)
                   (throw (ex-info "Fixture not found" {:document/id (:document/id document)})))
               (fetch-url url))]
    (assoc document
           :source/name (:source/name source)
           :source/base-url (:source/base-url source)
           :source/fetched-at fetched-at
           :source/fixture? fixture?
           :text/full text)))
