(ns openmevzuat.core
  (:gen-class)
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [openmevzuat.catalog :as catalog]
            [openmevzuat.fetch :as fetch]
            [openmevzuat.hash :as hash]
            [openmevzuat.normalize :as normalize]
            [openmevzuat.parse :as parse]
            [openmevzuat.render :as render]
            [openmevzuat.resmigazete :as rg]
            [openmevzuat.slug :as slug]
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

(defn env-true? [name]
  (#{"true" "1" "yes"} (some-> (System/getenv name) str/lower-case str/trim)))

(defn env-long [name fallback minimum]
  (max minimum
       (try
         (Long/parseLong (str/trim (str (System/getenv name))))
         (catch Exception _
           fallback))))

(defn update-window-days []
  (env-long "OPENMEVZUAT_UPDATE_WINDOW_DAYS" 30 1))

(defn edn-str [value]
  (binding [*print-namespace-maps* false]
    (with-out-str (pprint/pprint value))))

(defn read-edn-file [path]
  (when (fs/exists? path)
    (edn/read-string (slurp (io/file (str path))))))

(def search-index-path "derived/search/documents.jsonl")

(defn log! [& values]
  (apply println values)
  (flush))

(defn configured-documents []
  (sources/documents))

(defn catalog-law-documents []
  (or (catalog/law-documents) []))

(defn law-document? [document]
  (= :law (:document/type document)))

(defn numeric-document-number [document]
  (try
    (Long/parseLong (str/trim (str (:document/number document))))
    (catch Exception _
      Long/MAX_VALUE)))

(defn document-type-rank [document]
  (case (:document/type document)
    :constitution 0
    :law 1
    :decree 2
    3))

(defn document-sort-key [document]
  [(document-type-rank document)
   (numeric-document-number document)
   (:document/id document)])

(defn document-map [documents]
  (into {} (map (juxt :document/id identity) documents)))

(defn source-url-set [documents]
  (set (keep :source/url documents)))

(defn merged-documents [& document-collections]
  (->> document-collections
       (apply concat)
       document-map
       vals
       (sort-by document-sort-key)
       vec))

(defn all-law-catalog-documents []
  (let [catalog-documents (catalog-law-documents)]
    (when (empty? catalog-documents)
      (throw (ex-info "Law catalog not found. Run `clojure -M:openmevzuat sync-catalog` first."
                      {:catalog/path catalog/law-catalog-path})))
    (let [configured-laws (filter law-document? (configured-documents))
          configured-source-urls (source-url-set configured-laws)
          catalog-documents (remove #(contains? configured-source-urls (:source/url %))
                                    catalog-documents)]
      (merged-documents catalog-documents configured-laws))))

(defn all-catalog-documents []
  (merged-documents (remove law-document? (configured-documents))
                    (all-law-catalog-documents)))

(defn selector->document-id [selector]
  (let [selector (str/trim (str selector))]
    (cond
      (str/includes? selector "/") selector
      (re-matches #"\d+" selector) (str "law/" selector)
      (re-matches #"t\d+-\d+" selector) (str "law/" selector)
      :else selector)))

(defn selected-documents [selectors]
  (let [ids (mapv selector->document-id selectors)
        by-id (document-map (merged-documents (catalog-law-documents)
                                             (configured-documents)))
        missing (remove #(contains? by-id %) ids)]
    (when (seq missing)
      (throw (ex-info "Requested documents were not found in configured documents or the synced catalog."
                      {:missing-document/ids (vec missing)
                       :hint "Run `clojure -M:openmevzuat sync-catalog` if the catalog is missing or stale."})))
    (mapv by-id ids)))

(defn title-key [title]
  (some-> title slug/slugify not-empty))

(defn title-match? [expected actual]
  (let [expected (title-key expected)
        actual (title-key actual)]
    (and expected actual
         (or (= expected actual)
             (str/includes? expected actual)
             (str/includes? actual expected)))))

(defn resolve-affected-law [documents affected-law]
  (let [number (:law/number affected-law)
        candidates (filter #(= number (:document/number %)) documents)
        title-matches (filter #(title-match? (:law/title affected-law)
                                             (:document/title %))
                              candidates)
        direct-id (str "law/" number)
        direct-matches (filter #(= direct-id (:document/id %)) candidates)]
    (cond
      (empty? candidates)
      {:affected-law affected-law
       :reason :not-in-catalog}

      (= 1 (count candidates))
      {:affected-law affected-law
       :document (first candidates)}

      (= 1 (count title-matches))
      {:affected-law affected-law
       :document (first title-matches)}

      (= 1 (count direct-matches))
      {:affected-law affected-law
       :document (first direct-matches)}

      :else
      {:affected-law affected-law
       :reason :ambiguous
       :candidates (mapv #(select-keys % [:document/id :document/title :source/url])
                         candidates)})))

(defn resolve-affected-laws [affected-laws]
  (let [documents (all-law-catalog-documents)
        resolutions (mapv #(resolve-affected-law documents %) affected-laws)
        resolved-documents (->> resolutions
                                (keep :document)
                                document-map
                                vals
                                (sort-by document-sort-key)
                                vec)
        unresolved (->> resolutions
                        (remove :document)
                        vec)]
    {:documents resolved-documents
     :unresolved unresolved
     :resolutions resolutions}))

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

(defn prepared->manifest-document [{:keys [document metadata]}]
  (array-map
   :document/id (:document/id document)
   :document/title (:document/title document)
   :document/path (store/canonical-document-path document)
   :metadata/path (store/metadata-path document)
   :document/sha256 (:document/sha256 metadata)))

(defn manifest-map
  ([manifest-documents run-at date path]
   (manifest-map manifest-documents run-at date path nil))
  ([manifest-documents run-at date path scope]
   (let [existing (read-edn-file path)
         docs (vec manifest-documents)
         generated-at (if (= docs (:documents existing))
                        (or (:snapshot/generated-at existing) run-at)
                        run-at)]
     (cond->
      (array-map
       :snapshot/date date
       :snapshot/generated-at generated-at
       :generator/name "openmevzuat"
       :generator/version generator-version
       :documents docs)
       scope (assoc :snapshot/scope scope)))))

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

(defn search-lines [prepared-documents]
  (mapcat #(document->search-lines (:document %)) prepared-documents))

(defn- search-line-document-id [line]
  (try
    (:document/id (json/parse-string line true))
    (catch Exception _
      nil)))

(defn- merge-search-lines [existing-lines new-lines]
  (let [new-lines-by-document-id (group-by search-line-document-id new-lines)
        updated-document-ids (set (keys new-lines-by-document-id))
        emitted-document-ids (atom #{})
        merged-lines (mapcat
                      (fn [line]
                        (let [document-id (search-line-document-id line)]
                          (if (contains? updated-document-ids document-id)
                            (when-not (contains? @emitted-document-ids document-id)
                              (swap! emitted-document-ids conj document-id)
                              (get new-lines-by-document-id document-id))
                            [line])))
                      existing-lines)
        appended-lines (mapcat new-lines-by-document-id
                               (remove @emitted-document-ids updated-document-ids))]
    (concat merged-lines appended-lines)))

(defn write-search!
  ([prepared-documents]
   (write-search! prepared-documents {:merge? false}))
  ([prepared-documents {:keys [merge?]}]
   (let [new-lines (vec (search-lines prepared-documents))
         lines (if merge?
                 (merge-search-lines (some-> (store/read-file search-index-path)
                                             str/split-lines)
                                     new-lines)
                 new-lines)]
    (store/write-if-changed! search-index-path
                             (if (seq lines)
                               (str (str/join "\n" lines) "\n")
                               "")))))

(defn search-temp-path [date suffix]
  (str "derived/search/.documents-" date "-" suffix ".jsonl.tmp"))

(defn write-search-line! [writer line]
  (.write writer (str line))
  (.write writer "\n"))

(defn write-search-lines-to-writer! [writer lines]
  (doseq [line lines]
    (write-search-line! writer line)))

(defn write-merged-search! [new-lines-by-document-id date]
  (let [temp-path (search-temp-path date "incremental")
        updated-document-ids (set (keys new-lines-by-document-id))
        emitted-document-ids (atom #{})]
    (fs/create-dirs (fs/parent (fs/path temp-path)))
    (fs/delete-if-exists temp-path)
    (with-open [writer (io/writer (io/file temp-path))]
      (when (fs/exists? search-index-path)
        (with-open [reader (io/reader (io/file search-index-path))]
          (doseq [line (line-seq reader)]
            (let [document-id (search-line-document-id line)]
              (if (contains? updated-document-ids document-id)
                (when-not (contains? @emitted-document-ids document-id)
                  (swap! emitted-document-ids conj document-id)
                  (write-search-lines-to-writer!
                   writer
                   (get new-lines-by-document-id document-id)))
                (write-search-line! writer line))))))
      (doseq [document-id (remove @emitted-document-ids updated-document-ids)]
        (write-search-lines-to-writer!
         writer
         (get new-lines-by-document-id document-id))))
    (store/replace-file-if-changed! search-index-path temp-path)))

(defn manifest-path [date]
  (str "data/manifests/" date ".edn"))

(defn incremental-manifest-path [date]
  (str "data/manifests/incremental/" date ".edn"))

(defn selected-manifest-path [date]
  (str "data/manifests/selected/" date ".edn"))

(defn scoped-manifest-path [date scope]
  (case scope
    :incremental (incremental-manifest-path date)
    :selected (selected-manifest-path date)
    (manifest-path date)))

(defn write-manifest!
  ([manifest-documents run-at date]
   (write-manifest! manifest-documents run-at date (manifest-path date) nil))
  ([manifest-documents run-at date path scope]
   (let [manifest (manifest-map manifest-documents run-at date path scope)]
    {:path path
     :write (store/write-if-changed! path (edn-str manifest))})))

(defn skip-manifest
  ([date]
   (skip-manifest date (manifest-path date)))
  ([date path]
   {:path path
    :write {:path path
           :changed? false
           :skipped? true
            :reason :no-content-changes}}))

(defn changed-article-count [writes]
  (count (filter :changed? (:article-writes writes))))

(defn log-document-start! [index total document]
  (log! (format "Document %d/%d: %s - %s"
                index
                total
                (:document/id document)
                (:document/title document))))

(defn log-document-finish! [article-count changed-files]
  (log! (format "  articles: %d, changed files: %d"
                article-count
                changed-files)))

(defn process-document! [document run-at date index total]
  (log-document-start! index total document)
  (let [prepared (prepare-document document run-at date)
        writes (write-document! prepared)
        article-count (count (get-in prepared [:document :articles]))
        changed-files (changed-count [writes])]
    (log-document-finish! article-count changed-files)
    {:document (:document prepared)
     :manifest-document (prepared->manifest-document prepared)
     :writes writes
     :article-count article-count
     :articles-written (changed-article-count writes)
     :changed-files changed-files}))

(defn process-documents! [documents run-at date {:keys [merge-search? search-writer]}]
  (let [total (count documents)]
    (loop [index 1
           remaining (seq documents)
           manifest-documents []
           document-changed-files 0
           articles-written 0
           new-search-lines-by-document-id {}]
      (if-not remaining
        {:manifest-documents manifest-documents
         :document-changed-files document-changed-files
         :articles-written articles-written
         :new-search-lines-by-document-id new-search-lines-by-document-id}
        (let [document (first remaining)
              result (process-document! document run-at date index total)
              search-lines (vec (document->search-lines (:document result)))]
          (when search-writer
            (write-search-lines-to-writer! search-writer search-lines))
          (recur (inc index)
                 (next remaining)
                 (conj manifest-documents (:manifest-document result))
                 (+ document-changed-files (:changed-files result))
                 (+ articles-written (:articles-written result))
                 (if merge-search?
                   (assoc new-search-lines-by-document-id
                          (:document/id document)
                          search-lines)
                   new-search-lines-by-document-id)))))))

(defn update-documents!
  ([documents]
   (update-documents! documents {}))
  ([documents {:keys [label merge-search? manifest-scope]}]
   (let [documents (vec documents)
         run-at (now)
         date (snapshot-date)
         used-source-ids (set (map :source/id documents))
         used-sources (filter #(contains? used-source-ids (:source/id %)) (sources/sources))
         manifest-path (scoped-manifest-path date manifest-scope)
         full-search-temp-path (when-not merge-search?
                                 (search-temp-path date "full"))]
     (log! (or label "OpenMevzuat update"))
     (log! "Mode:" (if merge-search? "incremental" "full"))
     (log! "Documents:" (count documents))
     (log! "Processing: one document at a time")
     (fetch/preflight-sources! used-sources)
     (when full-search-temp-path
       (fs/create-dirs (fs/parent (fs/path full-search-temp-path)))
       (fs/delete-if-exists full-search-temp-path))
     (let [result (if full-search-temp-path
                    (with-open [writer (io/writer (io/file full-search-temp-path))]
                      (process-documents! documents run-at date {:search-writer writer}))
                    (process-documents! documents run-at date {:merge-search? true}))
           search-write (if merge-search?
                          (write-merged-search! (:new-search-lines-by-document-id result) date)
                          (store/replace-file-if-changed! search-index-path full-search-temp-path))
           content-changed-files (+ (:document-changed-files result)
                                    (changed-count [search-write]))
           manifest-write (if (pos? content-changed-files)
                            (write-manifest! (:manifest-documents result)
                                             run-at
                                             date
                                             manifest-path
                                             manifest-scope)
                            (skip-manifest date manifest-path))
           changed-files (+ content-changed-files
                            (changed-count [(:write manifest-write)]))]
       (log! (str (or label "OpenMevzuat update") " complete"))
       (log! "Mode:" (if merge-search? "incremental" "full"))
       (log! "Documents:" (count documents))
       (log! "Articles written:" (:articles-written result))
       (log! "Changed files:" changed-files)
       (log! "Manifest:" (if (get-in manifest-write [:write :skipped?])
                            (str (:path manifest-write) " (skipped; no content changes)")
                            (:path manifest-write)))
       {:documents (:manifest-documents result)
        :search search-write
        :manifest manifest-write
        :summary {:documents (count documents)
                  :articles-written (:articles-written result)
                  :changed-files changed-files}}))))

(defn configured-update! []
  (update-documents! (configured-documents)
                     {:label "OpenMevzuat update-configured"}))

(defn update-laws! [selectors]
  (update-documents! (selected-documents selectors)
                     {:label "OpenMevzuat update-laws"
                      :merge-search? true
                      :manifest-scope :selected}))

(defn update-all-laws! []
  (update-documents! (all-catalog-documents)
                     {:label "OpenMevzuat update-all-laws"}))

(defn update-date-range []
  (rg/date-range-ending (LocalDate/parse (snapshot-date))
                        (update-window-days)))

(defn print-unresolved-affected-laws! [unresolved]
  (when (seq unresolved)
    (println "Unresolved affected laws:" (count unresolved))
    (doseq [{:keys [affected-law reason candidates]} unresolved]
      (println " -" (:law/number affected-law)
               (or (:law/title affected-law) "")
               (str "(" (name reason) ")"))
      (when (seq candidates)
        (doseq [candidate candidates]
          (println "   candidate:" (:document/id candidate) "-" (:document/title candidate)))))))

(defn update! []
  (let [{:keys [from to]} (update-date-range)
        catalog-result (catalog/sync-laws!)
        changes (rg/changed-laws! from to)
        {:keys [documents unresolved]} (resolve-affected-laws (:affected-laws changes))]
    (println "OpenMevzuat update")
    (println "Mode: resmigazete-incremental")
    (println "Window:" (str from) "to" (str to))
    (println "Catalog documents:" (:documents catalog-result))
    (println "Resmi Gazete amendment laws:" (count (:amendment-laws changes)))
    (println "Affected laws:" (count (:affected-laws changes)))
    (println "Resolved documents:" (count documents))
    (print-unresolved-affected-laws! unresolved)
    (if (seq documents)
      (assoc (update-documents! documents
                                {:label "OpenMevzuat update"
                                 :merge-search? true
                                 :manifest-scope :incremental})
             :catalog catalog-result
             :changes changes
             :unresolved unresolved)
      (do
        (println "No affected laws to update.")
        {:catalog catalog-result
         :changes changes
         :unresolved unresolved
         :skipped? true}))))

(defn update-or-skip-unreachable!
  ([] (update-or-skip-unreachable! update!))
  ([f]
   (try
     (f)
     (catch Exception e
       (if (and (env-true? "OPENMEVZUAT_SKIP_UNREACHABLE_SOURCES")
                (fetch/source-unreachable? e))
         (let [data (ex-data e)]
           (println "OpenMevzuat update skipped")
           (println "Reason: official source is unreachable from this runner.")
           (println "Source:" (or (:source/base-url data) (:url data) (:source/origin data)))
           (println "Last error:" (or (:last-error data) (:reason data) (:cause data) (.getMessage e)))
           {:skipped? true
            :reason :source-unreachable
            :source (or (:source/base-url data) (:url data) (:source/origin data))})
         (throw e))))))

(defn usage []
  (println "Usage: clojure -M:openmevzuat <sync-catalog|update|update-configured|update-laws|update-all-laws|build|clean-derived>")
  (println)
  (println "Commands:")
  (println "  sync-catalog             Fetch the official active Kanunlar catalog only.")
  (println "  update                   Sync catalog, detect recent Resmi Gazete law amendments, update affected laws.")
  (println "  update-configured        Update configured documents from resources/documents.edn.")
  (println "  update-laws 193 2918     Incrementally update selected laws and merge search index rows.")
  (println "  update-all-laws          Explicit full rebuild of synced catalog laws plus configured non-laws.")
  (println "  build                    Alias of update-configured.")
  (println "  clean-derived            Remove derived outputs."))

(defn -main [& args]
  (case (first args)
    "sync-catalog" (catalog/sync-laws!)
    "update" (update-or-skip-unreachable!)
    "update-configured" (update-or-skip-unreachable! configured-update!)
    "build" (update-or-skip-unreachable! configured-update!)
    "update-laws" (if (seq (rest args))
                    (update-or-skip-unreachable!
                     #(update-laws! (rest args)))
                    (do (usage)
                        (System/exit 1)))
    "update-all-laws" (update-or-skip-unreachable! update-all-laws!)
    "clean-derived" (do (store/clean-derived!)
                        (println "Derived files cleaned."))
    (do (usage)
        (System/exit 1))))
