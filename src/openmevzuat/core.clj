(ns openmevzuat.core
  (:gen-class)
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [openmevzuat.fetch :as fetch]
            [openmevzuat.hash :as hash]
            [openmevzuat.normalize :as normalize]
            [openmevzuat.parse :as parse]
            [openmevzuat.render :as render]
            [openmevzuat.sources :as sources]
            [openmevzuat.store :as store])
  (:import [java.time Instant LocalDate ZoneOffset]
           [java.util Date]))

(def generator-version "0.1.0")

(defn now []
  (Date/from (Instant/now)))

(defn snapshot-date []
  (or (not-empty (System/getenv "OPENMEVZUAT_SNAPSHOT_DATE"))
      (str (LocalDate/now ZoneOffset/UTC))))

(defn edn-str [value]
  (binding [*print-namespace-maps* false]
    (with-out-str (pprint/pprint value))))

(defn read-edn-file [path]
  (when (fs/exists? path)
    (edn/read-string (slurp (io/file (str path))))))

(defn- write-result? [value]
  (and (map? value) (contains? value :changed?)))

(defn- write-results [value]
  (cond
    (write-result? value) [value]
    (map? value) (mapcat write-results (vals value))
    (sequential? value) (mapcat write-results value)
    :else []))

(defn changed-count [writes]
  (count (filter :changed? (write-results writes))))

(defn article-id [document article]
  (str (:document/id document) "/article/" (:article/no article)))

(defn with-rendered-article-data [document]
  (update document :articles
          (fn [articles]
            (vec
             (map (fn [article]
                    (let [content (render/render-article article)]
                      (assoc article
                             :article/rendered content
                             :article/sha256 (hash/sha256-str content)
                             :article/id (article-id document article))))
                  articles)))))

(defn article-metadata [article]
  (array-map
   :article/id (:article/id article)
   :article/type (:article/type article)
   :article/no (:article/no article)
   :article/title (:article/title article)
   :article/path (:article/path article)
   :article/sha256 (:article/sha256 article)))

(defn content-signature [document readme-content]
  (hash/sha256-str
   (str readme-content "\n"
        (str/join "\n" (map :article/rendered (:articles document))))))

(defn metadata-map [document readme-content run-at baseline-date]
  (let [metadata-path (store/metadata-path document)
        existing (read-edn-file metadata-path)
        old-articles (:articles existing)
        new-articles (mapv article-metadata (:articles document))
        document-sha (content-signature document readme-content)
        stable-content? (and (= old-articles new-articles)
                             (= document-sha (:document/sha256 existing)))
        fetched-at (if stable-content?
                     (or (:source/fetched-at existing) (:source/fetched-at document) run-at)
                     (or (:source/fetched-at document) run-at))
        stable-baseline-date (if stable-content?
                               (or (:openmevzuat/baseline-date existing) baseline-date)
                               baseline-date)
        generated-at (if stable-content?
                       (or (:openmevzuat/generated-at existing) run-at)
                       run-at)]
    (array-map
     :document/id (:document/id document)
     :document/type (:document/type document)
     :document/number (:document/number document)
     :document/title (:document/title document)
     :document/slug (store/document-slug document)
     :document/path (store/canonical-document-path document)
     :document/sha256 document-sha
     :source/name (or (:source/name document) "mevzuat.gov.tr")
     :source/url (:source/url document)
     :source/fetched-at fetched-at
     :openmevzuat/baseline-date stable-baseline-date
     :openmevzuat/generated-at generated-at
     :content/language "tr"
     :content/format :markdown
     :content/granularity :article
     :articles new-articles)))

(defn document->search-lines [document]
  (map (fn [article]
         (json/generate-string
          (array-map
           :document/id (:document/id document)
           :document/type (:document/type document)
           :document/number (:document/number document)
           :document/title (:document/title document)
           :document/path (store/canonical-document-path document)
           :article/id (:article/id article)
           :article/type (:article/type article)
           :article/no (:article/no article)
           :article/title (:article/title article)
           :article/path (str (store/canonical-document-path document) "/" (:article/path article))
           :text (:article/body article))))
       (:articles document)))

(defn manifest-map [documents run-at date]
  (let [manifest-path (str "data/manifests/" date ".edn")
        existing (read-edn-file manifest-path)
        docs (mapv (fn [{:keys [document metadata]}]
                     (array-map
                      :document/id (:document/id document)
                      :document/title (:document/title document)
                      :document/path (store/canonical-document-path document)
                      :metadata/path (store/metadata-path document)
                      :document/sha256 (:document/sha256 metadata)))
                   documents)
        generated-at (if (= docs (:documents existing))
                       (or (:snapshot/generated-at existing) run-at)
                       run-at)]
    (array-map
     :snapshot/date date
     :snapshot/generated-at generated-at
     :generator/name "openmevzuat"
     :generator/version generator-version
     :documents docs)))

(defn prepare-document [document run-at baseline-date]
  (let [source (sources/source-by-id (:source/id document))
        fetched (fetch/fetch-document document source)
        normalized (update fetched :text/full normalize/normalize-text)
        parsed (parse/parse-document normalized)
        with-paths (store/attach-article-paths parsed)
        with-rendered (with-rendered-article-data with-paths)
        readme-content (render/render-readme with-rendered)
        metadata (metadata-map with-rendered readme-content run-at baseline-date)
        full-text-content (render/render-full-text with-rendered)]
    {:document with-rendered
     :readme-content readme-content
     :metadata metadata
     :metadata-content (edn-str metadata)
     :full-text-content full-text-content}))

(defn write-document! [{:keys [document readme-content metadata-content full-text-content]}]
  (let [doc-path (store/canonical-document-path document)
        article-writes (mapv (fn [article]
                               (store/write-if-changed!
                                (fs/path doc-path (:article/path article))
                                (:article/rendered article)))
                             (:articles document))
        stale-writes (store/remove-stale-article-files! document)
        readme-write (store/write-if-changed! (fs/path doc-path "README.md") readme-content)
        metadata-write (store/write-if-changed! (store/metadata-path document) metadata-content)
        full-text-write (store/write-if-changed! (store/full-text-path document) full-text-content)]
    {:article-writes article-writes
     :stale-writes stale-writes
     :readme-write readme-write
     :metadata-write metadata-write
     :full-text-write full-text-write}))

(defn write-search! [prepared-documents]
  (let [lines (mapcat #(document->search-lines (:document %)) prepared-documents)]
    (store/write-if-changed! "derived/search/documents.jsonl"
                             (str (str/join "\n" lines) "\n"))))

(defn write-manifest! [prepared-documents run-at date]
  (let [manifest (manifest-map prepared-documents run-at date)
        path (str "data/manifests/" date ".edn")]
    {:path path
     :write (store/write-if-changed! path (edn-str manifest))}))

(defn update! []
  (let [run-at (now)
        date (snapshot-date)
        documents (sources/documents)
        used-source-ids (set (map :source/id documents))
        used-sources (filter #(contains? used-source-ids (:source/id %)) (sources/sources))
        _ (fetch/preflight-sources! used-sources)
        prepared (mapv #(prepare-document % run-at date) documents)
        document-writes (mapv write-document! prepared)
        search-write (write-search! prepared)
        manifest-write (write-manifest! prepared run-at date)
        articles-written (count (filter :changed? (mapcat :article-writes document-writes)))
        changed-files (changed-count [document-writes search-write (:write manifest-write)])]
    (println "OpenMevzuat update")
    (println "Documents:" (count prepared))
    (println "Articles written:" articles-written)
    (println "Changed files:" changed-files)
    (println "Manifest:" (:path manifest-write))
    {:documents prepared
     :writes document-writes
     :search search-write
     :manifest manifest-write}))

(defn usage []
  (println "Usage: clojure -M:openmevzuat <update|build|clean-derived>"))

(defn -main [& args]
  (case (first args)
    "update" (update!)
    "build" (update!)
    "clean-derived" (do (store/clean-derived!)
                        (println "Derived files cleaned."))
    (do (usage)
        (System/exit 1))))
