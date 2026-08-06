(ns openmevzuat.resmigazete
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [hato.client :as http]
            [openmevzuat.fetch :as fetch])
  (:import [java.nio.charset Charset StandardCharsets]
           [java.time LocalDate]
           [java.time.format DateTimeFormatter]
           [java.util Locale]))

(def base-url "https://www.resmigazete.gov.tr")
(def filter-url (str base-url "/Home/Filter"))
(def default-page-size 100)
(def tr-locale (Locale/forLanguageTag "tr"))
(def windows-1254 (Charset/forName "windows-1254"))

(defn- parse-long-value [value fallback]
  (try
    (Long/parseLong (str/trim (str value)))
    (catch Exception _
      fallback)))

(defn page-size []
  (parse-long-value (System/getenv "OPENMEVZUAT_RESMIGAZETE_PAGE_SIZE")
                    default-page-size))

(defn- request-options [payload accept config]
  (cond->
   {:as :byte-array
    :headers {"User-Agent" "OpenMevzuat/0.1"
              "Accept" accept}
    :throw-exceptions false
    :timeout (:timeout-ms config)
    :version :http-1.1
    :http-client (fetch/http-client-options config)}
    payload
    (assoc :body (json/generate-string payload)
           :headers {"User-Agent" "OpenMevzuat/0.1"
                     "Accept" accept
                     "Content-Type" "application/json; charset=utf-8"})))

(defn- bytes->text
  ([body]
   (bytes->text body StandardCharsets/UTF_8))
  ([body charset]
   (if (bytes? body)
     (String. ^bytes body charset)
     (str body))))

(defn- datatable-columns []
  (vec
   (repeat 1
           {:data nil
            :name ""
            :searchable true
            :orderable false
            :search {:value ""
                     :regex false}})))

(defn- filter-payload [from-date to-date start length draw]
  {:draw draw
   :columns (datatable-columns)
   :order []
   :start start
   :length length
   :search {:value ""
            :regex false}
   :parameters {:genelBaslangicTarihi (str from-date)
                :genelBitisTarihi (str to-date)
                :searchtype 1
                :mevzuatTuru "1"}})

(defn fetch-yasama-page! [from-date to-date start length draw]
  (let [payload (filter-payload from-date to-date start length draw)
        response (fetch/request-with-retries!
                  filter-url
                  #(http/post filter-url
                              (request-options payload
                                               "application/json,text/plain,*/*;q=0.1"
                                               %))
                  {:message "Failed to fetch Resmi Gazete filter page"
                   :context {:from-date (str from-date)
                             :to-date (str to-date)
                             :start start
                             :length length
                             :draw draw}})
        status (:status response)
        text (bytes->text (:body response))]
    (when-not (<= 200 status 299)
      (throw (ex-info "Failed to fetch Resmi Gazete filter page"
                      {:url filter-url
                       :status status
                       :from-date (str from-date)
                       :to-date (str to-date)
                       :start start
                       :length length
                       :body-preview (subs text 0 (min 300 (count text)))})))
    (json/parse-string text true)))

(defn yasama-rows! [from-date to-date]
  (let [length (page-size)]
    (loop [draw 1
           start 0
           rows []
           total nil]
      (let [page (fetch-yasama-page! from-date to-date start length draw)
            page-rows (vec (:data page))
            total (or total (:recordsFiltered page) (:recordsTotal page) 0)
            rows (into rows page-rows)
            next-start (+ start (count page-rows))]
        (if (or (empty? page-rows)
                (>= next-start total))
          {:records-total (:recordsTotal page)
           :records-filtered (:recordsFiltered page)
           :rows rows}
          (recur (inc draw) next-start rows total))))))

(defn upper-tr [value]
  (.toUpperCase (str value) tr-locale))

(defn amendment-law-row? [row]
  (and (= "KANUNLAR" (:mevzuatAdi row))
       (str/includes? (upper-tr (:konu row)) "DEĞİŞİKLİK")))

(defn absolute-url [url]
  (when (not-empty (str url))
    (if (str/starts-with? url "http")
      url
      (str base-url "/" (str/replace-first (str url) #"^/+" "")))))

(defn- codepoint->string
  "Decodes one numeric entity. Codepoints outside the basic plane need a
  surrogate pair, and an out-of-range entity is left as written rather than
  thrown."
  [code radix]
  (try
    (String. (Character/toChars (Integer/parseInt code radix)))
    (catch Exception _
      (str "&#" (when (= 16 radix) "x") code ";"))))

(defn- decode-html-entities [text]
  (-> text
      (str/replace #"&#x([0-9A-Fa-f]+);"
                   (fn [[_ code]]
                     (codepoint->string code 16)))
      (str/replace #"&#([0-9]+);"
                   (fn [[_ code]]
                     (codepoint->string code 10)))
      (str/replace "&nbsp;" " ")
      (str/replace "&amp;" "&")
      (str/replace "&quot;" "\"")
      (str/replace "&#39;" "'")
      (str/replace "&lt;" "<")
      (str/replace "&gt;" ">")))

(defn normalize-space [text]
  (-> (or text "")
      decode-html-entities
      (str/replace #"\u00a0" " ")
      (str/replace #"\s+" " ")
      str/trim))

(defn strip-tags [html]
  (normalize-space (str/replace (or html "") #"(?is)<[^>]+>" " ")))

(defn title-key [text]
  (-> text
      normalize-space
      upper-tr
      (str/replace #"^[\s–—-]+" "")
      (str/replace #"\s+" " ")
      str/trim))

(defn html-links [html]
  (for [[_ href body] (re-seq #"(?is)<a\b[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>" html)]
    {:href (absolute-url (decode-html-entities href))
     :text (strip-tags body)}))

(defn fetch-url-text!
  ([url]
   (fetch-url-text! url StandardCharsets/UTF_8))
  ([url charset]
   (let [response (fetch/request-with-retries!
                   url
                   #(http/get url
                              (request-options nil
                                               "text/html,application/xhtml+xml,*/*;q=0.1"
                                               %))
                   {:message "Failed to fetch Resmi Gazete page"})
         status (:status response)
         text (bytes->text (:body response) charset)]
     (when-not (<= 200 status 299)
       (throw (ex-info "Failed to fetch Resmi Gazete page"
                       {:url url
                        :status status
                        :body-preview (subs text 0 (min 300 (count text)))})))
     text)))

(defn find-amendment-url! [row]
  (let [row-url (or (absolute-url (:url row))
                    (throw (ex-info "Resmi Gazete row has no document URL"
                                    {:law/no (str (:kanunKararNo row))
                                     :law/title (:konu row)
                                     :resmi-gazete/date (:resmiGazeteTarihiFormatted row)})))]
    (if (re-find #"\.(?:htm|html|pdf)$" row-url)
      row-url
      (let [fihrist-html (fetch-url-text! row-url StandardCharsets/UTF_8)
            expected-title (title-key (:konu row))
            expected-no (str (:kanunKararNo row))
            links (html-links fihrist-html)]
        (or (:href (first (filter #(= expected-title (title-key (:text %))) links)))
            (:href (first (filter #(and (str/includes? (title-key (:text %)) expected-title)
                                        (not-empty expected-title))
                                  links)))
            (:href (first (filter #(and (not-empty expected-no)
                                        (str/includes? (:text %) expected-no))
                                  links)))
            (throw (ex-info "Could not find amendment law link in Resmi Gazete fihrist"
                            {:fihrist/url row-url
                             :law/no expected-no
                             :law/title (:konu row)})))))))

(defn paragraph-texts [html]
  (map (fn [[_ body]] (strip-tags body))
       (re-seq #"(?is)<p\b[^>]*>(.*?)</p>" html)))

(defn article-intro [paragraph]
  (when-let [[_ article-no intro] (re-find #"(?iu)^MADDE\s+([0-9]+)\s*[-–—]\s*(.*)$"
                                           paragraph)]
    {:article/no article-no
     :article/intro intro}))

(defn affected-law-from-intro [article]
  (let [article-no (:article/no article)
        intro (:article/intro article)]
    (when-let [[_ number] (re-find #"(?iu)([0-9]{1,5})\s+sayılı(?:\s|$)" intro)]
      (let [title (some-> (re-find #"(?iu)([0-9]{1,5})\s+sayılı\s+(.{1,260}?\s+Kanun)(?:un(?:un)?|una|unun|u|ı|a|da|dan)?\b"
                                    intro)
                          (nth 2)
                          normalize-space
                          not-empty)]
        {:law/number number
         :law/title title
         :article/no article-no
         :article/intro intro}))))

(defn affected-laws-from-html [html]
  (->> (paragraph-texts html)
       (keep article-intro)
       (keep affected-law-from-intro)
       vec))

(defn- amendment-summary [entry]
  (select-keys entry
               [:amendment/law-no
                :amendment/title
                :amendment/url
                :resmi-gazete/date
                :resmi-gazete/issue]))

(defn- merge-affected-entries [entries]
  (->> entries
       (group-by :law/number)
       (mapcat
        (fn [[number rows]]
          (let [titles (->> rows
                            (keep :law/title)
                            distinct
                            vec)]
            (if (seq titles)
              (for [title titles
                    :let [matching (filter #(or (= title (:law/title %))
                                                (nil? (:law/title %)))
                                           rows)]]
                {:law/number number
                 :law/title title
                 :articles (mapv #(select-keys % [:article/no :article/intro]) matching)
                 :amendments (->> matching
                                  (map amendment-summary)
                                  distinct
                                  vec)})
              [{:law/number number
                :law/title nil
                :articles (mapv #(select-keys % [:article/no :article/intro]) rows)
                :amendments (->> rows
                                 (map amendment-summary)
                                 distinct
                                 vec)}]))))
       (sort-by (juxt #(parse-long-value (:law/number %) Long/MAX_VALUE)
                      #(or (:law/title %) "")))
       vec))

(defn amendment-law-candidates! [from-date to-date]
  (let [{:keys [records-total records-filtered rows]} (yasama-rows! from-date to-date)
        amendment-laws (vec (filter amendment-law-row? rows))]
    {:records-total records-total
     :records-filtered records-filtered
     :amendment-laws amendment-laws}))

(defn add-amendment-urls! [amendment-laws]
  (mapv (fn [row]
          (assoc row :resmi-gazete/amendment-url
                 (find-amendment-url! row)))
        amendment-laws))

(defn amendment-laws! [from-date to-date]
  (let [{:keys [records-total records-filtered amendment-laws]} (amendment-law-candidates! from-date to-date)]
    {:records-total records-total
     :records-filtered records-filtered
     :amendment-laws (add-amendment-urls! amendment-laws)}))

(defn changed-laws-from-amendments! [from-date to-date records-total records-filtered amendment-laws]
  (let [amendment-laws (vec amendment-laws)
        entries (mapcat
                 (fn [law]
                   (let [html (fetch-url-text! (:resmi-gazete/amendment-url law) windows-1254)]
                     (map #(assoc %
                                  :amendment/law-no (:kanunKararNo law)
                                  :amendment/title (:konu law)
                                  :amendment/url (:resmi-gazete/amendment-url law)
                                  :resmi-gazete/date (:resmiGazeteTarihiFormatted law)
                                  :resmi-gazete/issue (:resmiGazeteSayisi law))
                          (affected-laws-from-html html))))
                 amendment-laws)
        affected-laws (merge-affected-entries entries)]
    {:range/from (str from-date)
     :range/to (str to-date)
     :yasama/records-total records-total
     :yasama/records-filtered records-filtered
     :amendment-laws amendment-laws
     :affected-laws affected-laws}))

(defn changed-laws! [from-date to-date]
  (let [{:keys [records-total records-filtered amendment-laws]} (amendment-laws! from-date to-date)]
    (changed-laws-from-amendments! from-date
                                   to-date
                                   records-total
                                   records-filtered
                                   amendment-laws)))

(defn date-range-ending [to-date days]
  (let [to-date (if (instance? LocalDate to-date)
                  to-date
                  (LocalDate/parse (str to-date) DateTimeFormatter/ISO_LOCAL_DATE))]
    {:from (.minusDays to-date days)
     :to to-date}))
