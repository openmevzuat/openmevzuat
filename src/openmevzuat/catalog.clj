;; Copyright (c) 2026 OpenMevzuat contributors.
;; SPDX-License-Identifier: AGPL-3.0-only

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
(def decree-catalog-path "data/catalog/decrees.edn")
(def default-page-size 100)

(def law-mevzuat-tur
  "MevzuatTur parameter for the active Kanunlar list."
  "Kanun")

(def decree-catalog-specs
  "The two decree lists mevzuat.gov.tr publishes, in document-id order.

  `:mevzuat/tur-parameter` is the MevzuatTur value the datatable endpoint
  expects. The endpoint rejects an unknown value with a bare `FormValidate`
  body rather than an error status, so these strings must match the site's own
  form values exactly: Cumhurbaşkanlığı Kararnameleri are requested as
  `CumhurbaskaniKararnameleri`, not the more obvious spelling."
  [{:decree/subtype :khk
    :mevzuat/tur-parameter "KHK"
    :catalog/source-url "https://www.mevzuat.gov.tr/#kanunHukmundeKararnameler"}
   {:decree/subtype :cbk
    :mevzuat/tur-parameter "CumhurbaskaniKararnameleri"
    :catalog/source-url "https://www.mevzuat.gov.tr/#cumhurbaskanligiKararnameleri"}])

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

(defn- datatable-payload [draw start length mevzuat-tur]
  {:draw draw
   :columns (datatable-columns)
   :order []
   :start start
   :length length
   :search {:value ""
            :regex false}
   :parameters {:MevzuatTur mevzuat-tur
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

(defn fetch-catalog-page! [mevzuat-tur start length draw]
  (let [payload (datatable-payload draw start length mevzuat-tur)
        response (fetch/request-with-retries!
                  law-datatable-url
                  #(http/post law-datatable-url (request-options payload %))
                  {:message "Failed to fetch catalog page"
                   :context {:mevzuat-tur mevzuat-tur
                             :start start
                             :length length
                             :draw draw}})
        status (:status response)
        text (bytes->text (:body response))]
    (when-not (<= 200 status 299)
      (throw (ex-info "Failed to fetch catalog page"
                      {:url law-datatable-url
                       :mevzuat-tur mevzuat-tur
                       :status status
                       :start start
                       :length length
                       :body-preview (subs text 0 (min 300 (count text)))})))
    (try
      (json/parse-string text true)
      (catch Exception _
        ;; An unknown MevzuatTur is answered with a bare `FormValidate` body and
        ;; a 200 status, so a parse failure here means the parameter was
        ;; rejected rather than that the response was malformed.
        (throw (ex-info "Catalog endpoint rejected the MevzuatTur parameter"
                        {:url law-datatable-url
                         :mevzuat-tur mevzuat-tur
                         :body-preview (subs text 0 (min 300 (count text)))}))))))

(defn fetch-law-page! [start length draw]
  (fetch-catalog-page! law-mevzuat-tur start length draw))

(defn fetch-catalog-rows! [mevzuat-tur]
  (let [length (page-size)]
    (loop [draw 1
           start 0
           rows []
           total nil]
      (let [page (fetch-catalog-page! mevzuat-tur start length draw)
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

(defn fetch-law-rows! []
  (fetch-catalog-rows! law-mevzuat-tur))

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

(defn decree-catalog-document-id [row subtype duplicate-numbers]
  (let [number (str (:mevzuatNo row))
        prefix (str "decree/" (name subtype) "-")]
    (if (contains? duplicate-numbers number)
      (str prefix "t" (:mevzuatTertip row) "-" number)
      (str prefix number))))

(defn decree-row->document
  ([row subtype]
   (decree-row->document row subtype #{}))
  ([row subtype duplicate-numbers]
   (array-map
    :document/id (decree-catalog-document-id row subtype duplicate-numbers)
    :document/type :decree
    :decree/subtype subtype
    :document/number (str (:mevzuatNo row))
    :document/title (:mevAdi row)
    :source/id :mevzuat-gov-tr
    :source/url (source-pdf-url row)
    :source/catalog-url (absolute-url (:url row))
    :mevzuat/tur (:mevzuatTur row)
    :mevzuat/tertip (str (:mevzuatTertip row))
    :resmi-gazete/date (:resmiGazeteTarihi row)
    :resmi-gazete/issue (:resmiGazeteSayisi row)
    :law/adoption-date (:kabulTarih row))))

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

(defn decree-catalog-documents [rows-by-subtype]
  (->> decree-catalog-specs
       (mapcat (fn [{:keys [decree/subtype]}]
                 (let [rows (get rows-by-subtype subtype [])
                       duplicate-numbers (duplicate-law-numbers rows)]
                   (map #(decree-row->document % subtype duplicate-numbers) rows))))
       distinct-documents
       (sort-by (juxt #(name (:decree/subtype %))
                      numeric-document-number
                      :document/id))
       vec))

(defn decree-catalog-map [rows-by-subtype fetched-at]
  (let [documents (decree-catalog-documents rows-by-subtype)
        subtype-counts (into (array-map)
                             (for [{:keys [decree/subtype]} decree-catalog-specs]
                               [subtype (count (get rows-by-subtype subtype []))]))]
    (array-map
     :catalog/id :mevzuat-gov-tr/decrees
     :catalog/type :decree
     :catalog/source-urls (mapv :catalog/source-url decree-catalog-specs)
     :catalog/api-url law-datatable-url
     :catalog/fetched-at fetched-at
     :catalog/records-total (reduce + 0 (vals subtype-counts))
     :catalog/documents-total (count documents)
     :catalog/subtype-totals subtype-counts
     :documents documents)))

(defn write-decree-catalog! [catalog]
  (let [path (fs/path decree-catalog-path)
        existing (read-catalog decree-catalog-path)
        catalog (if (and existing (stable-catalog-content? existing catalog))
                  (assoc catalog :catalog/fetched-at (:catalog/fetched-at existing))
                  catalog)]
    (fs/create-dirs (fs/parent path))
    (spit (io/file (str path)) (edn-str catalog))
    {:path (str path)
     :documents (count (:documents catalog))}))

(defn decree-documents
  ([] (decree-documents decree-catalog-path))
  ([path]
   (:documents (read-catalog path))))

(defn sync-decrees! []
  (let [rows-by-subtype (into (array-map)
                              (for [{:keys [decree/subtype mevzuat/tur-parameter]} decree-catalog-specs]
                                [subtype (:rows (fetch-catalog-rows! tur-parameter))]))
        catalog (decree-catalog-map rows-by-subtype (fetch/now-date))
        write (write-decree-catalog! catalog)]
    (println "OpenMevzuat decree catalog sync")
    (doseq [{:keys [decree/subtype]} decree-catalog-specs]
      (println (str "  " (str/upper-case (name subtype)) ":")
               (count (get rows-by-subtype subtype []))))
    (println "Documents:" (:documents write))
    (println "Catalog:" (:path write))
    (assoc write :catalog catalog)))
