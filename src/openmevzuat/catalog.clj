(ns openmevzuat.catalog
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [hato.client :as http]
            [openmevzuat.fetch :as fetch])
  (:import [java.io PushbackReader]
           [java.nio.charset StandardCharsets]))

(def law-catalog-url "https://www.mevzuat.gov.tr/#kanunlar")
(def law-datatable-url "https://www.mevzuat.gov.tr/Anasayfa/MevzuatDatatable")
(def law-catalog-path "data/catalog/laws.edn")
(def default-page-size 100)

(defn- edn-str [value]
  (binding [*print-namespace-maps* false]
    (with-out-str (pprint/pprint value))))

(defn- parse-long-value [value fallback]
  (try
    (Long/parseLong (str/trim (str value)))
    (catch Exception _
      fallback)))

(defn- page-size []
  (parse-long-value (System/getenv "OPENMEVZUAT_CATALOG_PAGE_SIZE")
                    default-page-size))

(defn- request-options [payload config]
  {:as :byte-array
   :body (json/generate-string payload)
   :headers {"User-Agent" "OpenMevzuat/0.1"
             "Accept" "application/json,text/plain,*/*;q=0.1"
             "Content-Type" "application/json; charset=utf-8"}
   :throw-exceptions false
   :timeout (:timeout-ms config)
   :version :http-1.1
   :http-client (fetch/http-client-options config)})

(defn- datatable-columns []
  (vec
   (repeat 3
           {:data nil
            :name ""
            :searchable true
            :orderable false
            :search {:value ""
                     :regex false}})))

(defn- datatable-payload [draw start length]
  {:draw draw
   :columns (datatable-columns)
   :order []
   :start start
   :length length
   :search {:value ""
            :regex false}
   :parameters {:MevzuatTur "Kanun"
                :YonetmelikMevzuatTur "OsmanliKanunu"
                :AranacakIfade ""
                :AranacakYer "2"
                :MevzuatNo ""
                :BaslangicTarihi ""
                :BitisTarihi ""}})

(defn- bytes->text [body]
  (if (bytes? body)
    (String. ^bytes body StandardCharsets/UTF_8)
    (str body)))

(defn fetch-law-page! [start length draw]
  (let [payload (datatable-payload draw start length)
        response (fetch/request-with-retries!
                  law-datatable-url
                  #(http/post law-datatable-url (request-options payload %))
                  {:message "Failed to fetch law catalog page"
                   :context {:start start
                             :length length
                             :draw draw}})
        status (:status response)
        text (bytes->text (:body response))]
    (when-not (<= 200 status 299)
      (throw (ex-info "Failed to fetch law catalog page"
                      {:url law-datatable-url
                       :status status
                       :start start
                       :length length
                       :body-preview (subs text 0 (min 300 (count text)))})))
    (json/parse-string text true)))

(defn fetch-law-rows! []
  (let [length (page-size)]
    (loop [draw 1
           start 0
           rows []
           total nil]
      (let [page (fetch-law-page! start length draw)
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

(defn absolute-url [url]
  (when (not-empty (str url))
    (if (str/starts-with? url "http")
      url
      (str "https://www.mevzuat.gov.tr/"
           (str/replace-first (str url) #"^/+" "")))))

(defn source-pdf-url [row]
  (format "https://www.mevzuat.gov.tr/MevzuatMetin/%s.%s.%s.pdf"
          (:mevzuatTur row)
          (:mevzuatTertip row)
          (:mevzuatNo row)))

(defn duplicate-law-numbers [rows]
  (->> rows
       (group-by #(str (:mevzuatNo %)))
       (keep (fn [[number rows]]
               (when (< 1 (count rows))
                 number)))
       set))

(defn catalog-document-id [row duplicate-numbers]
  (let [number (str (:mevzuatNo row))]
    (if (contains? duplicate-numbers number)
      (str "law/t" (:mevzuatTertip row) "-" number)
      (str "law/" number))))

(defn law-row->document
  ([row]
   (law-row->document row #{}))
  ([row duplicate-numbers]
   (let [number (str (:mevzuatNo row))]
    (array-map
     :document/id (catalog-document-id row duplicate-numbers)
     :document/type :law
     :document/number number
     :document/title (:mevAdi row)
     :source/id :mevzuat-gov-tr
     :source/url (source-pdf-url row)
     :source/catalog-url (absolute-url (:url row))
     :mevzuat/tur (:mevzuatTur row)
     :mevzuat/tertip (str (:mevzuatTertip row))
     :resmi-gazete/date (:resmiGazeteTarihi row)
     :resmi-gazete/issue (:resmiGazeteSayisi row)
     :law/adoption-date (:kabulTarih row)))))

(defn- numeric-document-number [document]
  (parse-long-value (:document/number document) Long/MAX_VALUE))

(defn- distinct-documents [documents]
  (->> documents
       (reduce (fn [by-id document]
                 (if (contains? by-id (:document/id document))
                   by-id
                   (assoc by-id (:document/id document) document)))
               (array-map))
       vals))

(defn catalog-map [rows fetched-at]
  (let [duplicate-numbers (duplicate-law-numbers rows)
        documents (->> rows
                       (map #(law-row->document % duplicate-numbers))
                       distinct-documents
                       (sort-by (juxt numeric-document-number :document/id))
                       vec)]
    (array-map
     :catalog/id :mevzuat-gov-tr/laws
     :catalog/type :law
     :catalog/source-url law-catalog-url
     :catalog/api-url law-datatable-url
     :catalog/fetched-at fetched-at
     :catalog/records-total (count rows)
     :catalog/documents-total (count documents)
     :catalog/duplicate-numbers (vec (sort-by #(parse-long-value % Long/MAX_VALUE)
                                              duplicate-numbers))
     :documents documents)))

(defn stable-catalog-content? [old-catalog new-catalog]
  (= (dissoc old-catalog :catalog/fetched-at)
     (dissoc new-catalog :catalog/fetched-at)))

(defn read-catalog
  ([] (read-catalog law-catalog-path))
  ([path]
   (when (fs/exists? path)
     (with-open [reader (io/reader (io/file path))]
       (edn/read {:readers *data-readers*} (PushbackReader. reader))))))

(defn law-documents
  ([] (law-documents law-catalog-path))
  ([path]
   (:documents (read-catalog path))))

(defn write-catalog! [catalog]
  (let [path (fs/path law-catalog-path)
        existing (read-catalog)
        catalog (if (and existing (stable-catalog-content? existing catalog))
                  (assoc catalog :catalog/fetched-at (:catalog/fetched-at existing))
                  catalog)]
    (fs/create-dirs (fs/parent path))
    (spit (io/file (str path)) (edn-str catalog))
    {:path (str path)
     :documents (count (:documents catalog))}))

(defn sync-laws! []
  (let [{:keys [records-total records-filtered rows]} (fetch-law-rows!)
        catalog (catalog-map rows (fetch/now-date))
        write (write-catalog! catalog)]
    (println "OpenMevzuat catalog sync")
    (println "Source:" law-catalog-url)
    (println "Records total:" records-total)
    (println "Records filtered:" records-filtered)
    (println "Documents:" (:documents write))
    (println "Catalog:" (:path write))
    (assoc write
           :records-total records-total
           :records-filtered records-filtered
           :catalog catalog)))
