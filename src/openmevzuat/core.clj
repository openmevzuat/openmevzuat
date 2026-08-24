;; Copyright (c) 2026 OpenMevzuat contributors.
;; SPDX-License-Identifier: AGPL-3.0-only

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
(def processed-amendments-path "data/state/resmigazete-amendments.edn")

(defn log! [& values]
  (apply println values)
  (flush))

(defn configured-documents []
  (sources/documents))

(defn catalog-law-documents []
  (or (catalog/law-documents) []))

(defn law-document? [document]
  (= :law (:document/type document)))

(defn decree-document? [document]
  (= :decree (:document/type document)))

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

(defn resolvable-documents
  "Documents an amendment law can name as affected. Amendment laws routinely
  change Kanun Hükmünde Kararnameler alongside kanuns, so configured decrees
  belong here too. The constitution is deliberately left out: it is already in
  the Kanunlar catalog as law/2709, and adding it back would make every
  reference to 2709 ambiguous."
  []
  (merged-documents (filter decree-document? (configured-documents))
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
  (let [documents (resolvable-documents)
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

(defn metadata->manifest-document [metadata]
  (array-map
   :document/id (:document/id metadata)
   :document/title (:document/title metadata)
   :document/path (:document/path metadata)
   :metadata/path (store/metadata-path metadata)
   :document/sha256 (:document/sha256 metadata)))

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
        ;; Realized eagerly: the appended lines below read the atom, so leaving
        ;; this lazy would re-append every document whose existing lines sit
        ;; past the first chunk.
        merged-lines (vec
                      (mapcat
                       (fn [line]
                         (let [document-id (search-line-document-id line)]
                           (if (contains? updated-document-ids document-id)
                             (when-not (contains? @emitted-document-ids document-id)
                               (swap! emitted-document-ids conj document-id)
                               (get new-lines-by-document-id document-id))
                             [line])))
                       existing-lines))
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

(defn rendered-article-body [content]
  (-> (or content "")
      (str/replace-first #"(?s)^# [^\n]*\r?\n\r?\n?" "")
      (str/replace #"\s+\z" "")))

(defn local-document-search-lines [metadata]
  (map (fn [article]
         (let [article-path (fs/path (:document/path metadata) (:article/path article))
               content (or (store/read-file article-path)
                           (throw (ex-info "Cannot seed search index from local document; article file is missing."
                                           {:document/id (:document/id metadata)
                                            :article/id (:article/id article)
                                            :article/path (str article-path)})))]
           (json/generate-string
            (array-map
             :document/id (:document/id metadata)
             :document/type (:document/type metadata)
             :document/number (:document/number metadata)
             :document/title (:document/title metadata)
             :document/path (:document/path metadata)
             :article/id (:article/id article)
             :article/type (:article/type article)
             :article/no (:article/no article)
             :article/title (:article/title article)
             :article/path (str (:document/path metadata) "/" (:article/path article))
             :text (rendered-article-body content)))))
       (:articles metadata)))

(defn read-resume-metadata [document index]
  (let [path (store/metadata-path document)]
    (or (read-edn-file path)
        (throw (ex-info "Cannot resume full update; local metadata is missing for a skipped document."
                        {:document/index index
                         :document/id (:document/id document)
                         :metadata/path path
                         :hint (str "Resume from index " index " or earlier, or run without --resume-from.")})))))

(defn log-resume-seed-progress! [index total document]
  (when (or (= index 1)
            (= index total)
            (zero? (mod index 100)))
    (log! (format "Resume seed %d/%d: %s" index total (:document/id document)))))

(defn seed-resume-documents! [writer documents]
  (let [total (count documents)]
    (when (pos? total)
      (log! (format "Resume: seeding %d already-written documents from local metadata" total)))
    (loop [index 1
           remaining (seq documents)
           manifest-documents []]
      (if-not remaining
        {:manifest-documents manifest-documents
         :document-changed-files 0
         :articles-written 0}
        (let [document (first remaining)
              metadata (read-resume-metadata document index)]
          (log-resume-seed-progress! index total document)
          (write-search-lines-to-writer! writer (local-document-search-lines metadata))
          (recur (inc index)
                 (next remaining)
                 (conj manifest-documents
                       (metadata->manifest-document metadata))))))))

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

(defn process-documents! [documents run-at date {:keys [merge-search? search-writer start-index total-documents]
                                                 :or {start-index 1}}]
  (let [total (or total-documents (count documents))]
    (loop [index start-index
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
  ([documents {:keys [label merge-search? manifest-scope resume-from-index]}]
   (let [documents (vec documents)
         total-documents (count documents)
         resume-from-index (or resume-from-index 1)
         _ (when (and merge-search? (< 1 resume-from-index))
             (throw (ex-info "Resume is only supported for full update mode."
                             {:resume/from-index resume-from-index})))
         _ (when (or (< resume-from-index 1)
                     (> resume-from-index (max 1 total-documents)))
             (throw (ex-info "Resume index is outside the document range."
                             {:resume/from-index resume-from-index
                              :documents total-documents})))
         skipped-documents (subvec documents 0 (dec resume-from-index))
         documents-to-process (subvec documents (dec resume-from-index))
         run-at (now)
         date (snapshot-date)
         used-source-ids (set (map :source/id documents))
         used-sources (filter #(contains? used-source-ids (:source/id %)) (sources/sources))
         manifest-path (scoped-manifest-path date manifest-scope)
         full-search-temp-path (when-not merge-search?
                                 (search-temp-path date "full"))]
     (log! (or label "OpenMevzuat update"))
     (log! "Mode:" (if merge-search? "incremental" "full"))
     (log! "Documents:" total-documents)
     (log! "Processing: one document at a time")
     (when (< 1 resume-from-index)
       (log! "Resume from index:" resume-from-index)
       (log! "Documents to process:" (count documents-to-process)))
     (fetch/preflight-sources! used-sources)
     (when full-search-temp-path
       (fs/create-dirs (fs/parent (fs/path full-search-temp-path)))
       (fs/delete-if-exists full-search-temp-path))
     (let [result (if full-search-temp-path
                    (with-open [writer (io/writer (io/file full-search-temp-path))]
                      (let [seeded (seed-resume-documents! writer skipped-documents)
                            processed (process-documents! documents-to-process
                                                          run-at
                                                          date
                                                          {:search-writer writer
                                                           :start-index resume-from-index
                                                           :total-documents total-documents})]
                        {:manifest-documents (into (:manifest-documents seeded)
                                                   (:manifest-documents processed))
                         :document-changed-files (:document-changed-files processed)
                         :articles-written (:articles-written processed)}))
                    (process-documents! documents
                                        run-at
                                        date
                                        {:merge-search? true}))
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
       (log! "Documents:" total-documents)
       (log! "Articles written:" (:articles-written result))
       (log! "Changed files:" changed-files)
       (log! "Manifest:" (if (get-in manifest-write [:write :skipped?])
                            (str (:path manifest-write) " (skipped; no content changes)")
                            (:path manifest-write)))
       {:documents (:manifest-documents result)
        :search search-write
        :manifest manifest-write
        :summary {:documents total-documents
                  :resume/from-index resume-from-index
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

(defn parse-positive-index [raw option]
  (try
    (let [value (Long/parseLong (str/trim (str raw)))]
      (when-not (pos? value)
        (throw (ex-info "Index must be positive."
                        {:option option
                         :value raw})))
      value)
    (catch NumberFormatException _
      (throw (ex-info "Index must be a positive integer."
                      {:option option
                       :value raw})))))

(defn update-all-laws-options [args]
  (loop [args (seq args)
         options {}]
    (if-not args
      options
      (let [[option value & rest-args] args]
        (case option
          "--resume-from" (recur rest-args
                                 (assoc options
                                        :resume-from-index
                                        (parse-positive-index value option)))
          "--resume-after" (recur rest-args
                                  (assoc options
                                         :resume-from-index
                                         (inc (parse-positive-index value option))))
          "--resume-from-index" (recur rest-args
                                       (assoc options
                                              :resume-from-index
                                              (parse-positive-index value option)))
          (throw (ex-info "Unknown update-all-laws option."
                          {:option option
                           :allowed ["--resume-from" "--resume-after" "--resume-from-index"]})))))))

(defn update-all-laws!
  ([] (update-all-laws! {}))
  ([options]
  (update-documents! (all-catalog-documents)
                     (merge {:label "OpenMevzuat update-all-laws"}
                            options))))

(defn update-date-range []
  (rg/date-range-ending (LocalDate/parse (snapshot-date))
                        (update-window-days)))

(defn- amendment-value [law & keys]
  (some (fn [k]
          (some-> (get law k) str not-empty))
        keys))

(defn amendment-law-no [law]
  (amendment-value law :kanunKararNo :amendment/law-no))

(defn amendment-law-title [law]
  (amendment-value law :konu :amendment/title))

(defn amendment-law-url [law]
  (amendment-value law :resmi-gazete/amendment-url :amendment/url))

(defn amendment-law-date [law]
  (amendment-value law :resmiGazeteTarihiFormatted :resmi-gazete/date))

(defn amendment-law-issue [law]
  (amendment-value law :resmiGazeteSayisi :resmi-gazete/issue))

(defn amendment-law-key [law]
  [(or (amendment-law-no law) "")
   (or (amendment-law-date law) "")
   (or (amendment-law-issue law) "")])

(defn processed-amendments-state []
  (or (read-edn-file processed-amendments-path)
      {:resmi-gazete/processed-amendments []}))

(defn processed-amendment-entries [state]
  (vec (:resmi-gazete/processed-amendments state)))

(defn processed-amendment-key-set [state]
  (set (map amendment-law-key (processed-amendment-entries state))))

(defn new-amendment-laws [state amendment-laws]
  (let [seen (processed-amendment-key-set state)]
    (->> amendment-laws
         (remove #(contains? seen (amendment-law-key %)))
         vec)))

(defn skipped-amendment-laws [state amendment-laws]
  (let [seen (processed-amendment-key-set state)]
    (->> amendment-laws
         (filter #(contains? seen (amendment-law-key %)))
         vec)))

(defn- resmi-gazete-date-sort-key [date]
  (if-let [[_ day month year] (re-matches #"([0-9]{2})\.([0-9]{2})\.([0-9]{4})"
                                          (str date))]
    [year month day]
    [(str date) "" ""]))

(defn processed-amendment-entry [law processed-at snapshot-date]
  (cond->
   (array-map
    :amendment/law-no (amendment-law-no law)
    :amendment/title (amendment-law-title law)
    :resmi-gazete/date (amendment-law-date law)
    :resmi-gazete/issue (amendment-law-issue law)
    :processed/snapshot-date snapshot-date)
    (amendment-law-url law) (assoc :amendment/url (amendment-law-url law))
    processed-at (assoc :processed/at processed-at)))

(defn processed-amendment-state-map [existing-state new-laws processed-at snapshot-date]
  (let [entries (concat (processed-amendment-entries existing-state)
                        (map #(processed-amendment-entry % processed-at snapshot-date)
                             new-laws))
        entries-by-key (reduce (fn [by-key entry]
                                 (assoc by-key (amendment-law-key entry) entry))
                               {}
                               entries)
        entries (->> (vals entries-by-key)
                     (sort-by (juxt #(resmi-gazete-date-sort-key
                                      (:resmi-gazete/date %))
                                    #(or (:amendment/law-no %) "")))
                     vec)]
    (array-map
     :state/updated-at processed-at
     :resmi-gazete/processed-amendments entries)))

(defn write-processed-amendments! [new-laws snapshot-date]
  (let [processed-at (now)
        state (processed-amendment-state-map (processed-amendments-state)
                                             new-laws
                                             processed-at
                                             snapshot-date)]
    {:path processed-amendments-path
     :write (store/write-if-changed! processed-amendments-path
                                     (edn-str state))}))

(defn advance-processed-amendments! [new-laws unresolved snapshot-date processed?]
  (cond
    (empty? new-laws)
    nil

    (seq unresolved)
    (do
      (println "Processed amendment state: not advanced because unresolved affected laws remain.")
      nil)

    (not processed?)
    (do
      (println "Processed amendment state: not advanced because resolved documents did not change; will retry on the next overlapping run.")
      nil)

    :else
    (let [write (write-processed-amendments! new-laws snapshot-date)]
      (println "Processed amendment state:"
               (:path write)
               (if (get-in write [:write :changed?]) "(updated)" "(unchanged)"))
      write)))

(defn recent-unprocessed-changes! [from to]
  (let [{:keys [records-total records-filtered amendment-laws]} (rg/amendment-law-candidates! from to)
        state (processed-amendments-state)
        skipped (skipped-amendment-laws state amendment-laws)
        new-candidates (new-amendment-laws state amendment-laws)
        new-laws (rg/add-amendment-urls! new-candidates)
        changes (rg/changed-laws-from-amendments! from
                                                  to
                                                  records-total
                                                  records-filtered
                                                  new-laws)]
    (assoc changes
           :amendment-laws/detected (count amendment-laws)
           :amendment-laws/skipped (count skipped)
           :skipped-amendment-laws skipped
           :processed-amendments/path processed-amendments-path)))

(defn pr-body-output-path []
  (not-empty (System/getenv "OPENMEVZUAT_PR_BODY_PATH")))

(defn markdown-cell [value]
  (-> (or value "")
      str
      (str/replace #"\r?\n" " ")
      (str/replace "|" "\\|")
      (str/trim)))

(defn markdown-link [label url]
  (let [label (markdown-cell label)
        url (not-empty (str url))]
    (if url
      (str "[" label "](" url ")")
      label)))

(defn amendment-label [amendment]
  (str (:amendment/law-no amendment)
       " - "
       (:amendment/title amendment)))

(defn amendment-rg-label [amendment]
  (str (:resmi-gazete/date amendment)
       " / "
       (:resmi-gazete/issue amendment)))

(defn amendment-source-cell [amendments]
  (->> amendments
       (map #(markdown-link (amendment-label %) (:amendment/url %)))
       distinct
       (str/join "<br>")))

(defn amendment-rg-cell [amendments]
  (->> amendments
       (map amendment-rg-label)
       distinct
       (str/join "<br>")
       markdown-cell))

(defn markdown-table [headers rows]
  (str "| " (str/join " | " (map markdown-cell headers)) " |\n"
       "| " (str/join " | " (repeat (count headers) "---")) " |\n"
       (str/join
        "\n"
        (for [row rows]
          (str "| " (str/join " | " (map markdown-cell row)) " |")))
       "\n"))

(defn update-pr-body [changes documents unresolved update-result]
  (let [summary (:summary update-result)
        amendment-laws (:amendment-laws changes)
        affected-laws (:affected-laws changes)
        detected-amendment-laws (or (:amendment-laws/detected changes)
                                    (count amendment-laws))
        skipped-amendment-laws (or (:amendment-laws/skipped changes) 0)
        amendment-rows (for [law amendment-laws]
                         [(markdown-link (str (:kanunKararNo law)
                                              " - "
                                              (:konu law))
                                         (:resmi-gazete/amendment-url law))
                          (str (:resmiGazeteTarihiFormatted law)
                               " / "
                               (:resmiGazeteSayisi law))])
        affected-rows (for [law affected-laws]
                        [(str (:law/number law)
                              " - "
                              (or (:law/title law) "Title unavailable"))
                         (amendment-source-cell (:amendments law))
                         (amendment-rg-cell (:amendments law))])]
    (str "Automated OpenMevzuat update from official public sources.\n\n"
         "## Summary\n\n"
         "- Window: `" (:range/from changes) "` to `" (:range/to changes) "`\n"
         "- Resmi Gazete amendment laws detected: " detected-amendment-laws "\n"
         "- New Resmi Gazete amendment laws: " (count amendment-laws) "\n"
         "- Previously processed amendment laws skipped: " skipped-amendment-laws "\n"
         "- Affected kanuns: " (count affected-laws) "\n"
         "- Resolved documents: " (count documents) "\n"
         "- Unresolved affected laws: " (count unresolved) "\n"
         (when summary
           (str "- Changed files: " (:changed-files summary) "\n"))
         "\n"
         "## Resmi Gazete Amendment Laws\n\n"
         (if (seq amendment-rows)
           (markdown-table ["Amendment law" "Resmi Gazete"] amendment-rows)
           "No new amendment laws were detected.\n")
         "\n"
         "## Changed Kanuns\n\n"
         (if (seq affected-rows)
           (markdown-table ["Kanun" "Changed by" "Resmi Gazete"] affected-rows)
           "No affected kanuns were detected.\n")
         (when (seq unresolved)
           (str "\n"
                "## Unresolved\n\n"
                (markdown-table
                 ["Kanun" "Reason"]
                 (for [{:keys [affected-law reason]} unresolved]
                   [(str (:law/number affected-law)
                         " - "
                         (or (:law/title affected-law) "Title unavailable"))
                    (name reason)]))
                "\n"
                "> Processed amendment state was not advanced because unresolved affected\n"
                "> laws remain. Every later run re-detects and re-renders these amendment\n"
                "> laws until they resolve or leave the lookback window.\n")))))

(defn write-pr-body-when-configured! [body]
  (when-let [path (pr-body-output-path)]
    (let [path (fs/path path)]
      (when-let [parent (fs/parent path)]
        (fs/create-dirs parent))
      (spit (io/file (str path)) body)
      (println "PR body:" (str path)))))

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
  (let [date (snapshot-date)
        {:keys [from to]} (rg/date-range-ending (LocalDate/parse date)
                                                (update-window-days))
        catalog-result (catalog/sync-laws!)
        changes (recent-unprocessed-changes! from to)
        {:keys [documents unresolved]} (resolve-affected-laws (:affected-laws changes))]
    (println "OpenMevzuat update")
    (println "Mode: resmigazete-incremental")
    (println "Window:" (str from) "to" (str to))
    (println "Catalog documents:" (:documents catalog-result))
    (println "Resmi Gazete amendment laws detected:" (:amendment-laws/detected changes))
    (println "Previously processed amendment laws skipped:" (:amendment-laws/skipped changes))
    (println "New Resmi Gazete amendment laws:" (count (:amendment-laws changes)))
    (println "Affected laws:" (count (:affected-laws changes)))
    (println "Resolved documents:" (count documents))
    (print-unresolved-affected-laws! unresolved)
    (if (seq documents)
      (let [update-result (update-documents! documents
                                             {:label "OpenMevzuat update"
                                              :merge-search? true
                                              :manifest-scope :incremental})
            processed-write (advance-processed-amendments! (:amendment-laws changes)
                                                           unresolved
                                                           date
                                                           (pos? (get-in update-result
                                                                         [:summary :changed-files]
                                                                         0)))
            result (assoc update-result
                          :catalog catalog-result
                          :changes changes
                          :unresolved unresolved
                          :processed-amendments processed-write)]
        (write-pr-body-when-configured!
         (update-pr-body changes documents unresolved update-result))
        result)
      (do
        (println "No affected laws to update.")
        (let [processed-write (advance-processed-amendments! (:amendment-laws changes)
                                                            unresolved
                                                            date
                                                            true)
              result {:catalog catalog-result
                      :changes changes
                      :unresolved unresolved
                      :processed-amendments processed-write
                      :skipped? true}]
          (write-pr-body-when-configured!
           (update-pr-body changes documents unresolved nil))
          result)))))

(defn print-ex-data! [e]
  (when-let [data (ex-data e)]
    (println "Error data:")
    (binding [*print-namespace-maps* false]
      (pprint/pprint data))))

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
         (do
           (print-ex-data! e)
           (throw e)))))))

(defn usage []
  (println "Usage: clojure -M:openmevzuat <sync-catalog|update|update-configured|update-laws|update-all-laws|build|clean-derived>")
  (println)
  (println "Commands:")
  (println "  sync-catalog             Fetch the official active Kanunlar catalog only.")
  (println "  update                   Sync catalog, detect recent Resmi Gazete law amendments, update affected laws.")
  (println "  update-configured        Update configured documents from resources/documents.edn.")
  (println "  update-laws 193 2918     Incrementally update selected laws and merge search index rows.")
  (println "  update-all-laws          Explicit full rebuild of synced catalog laws plus configured non-laws.")
  (println "  update-all-laws --resume-from 702")
  (println "                           Seed prior documents locally, then continue full rebuild from progress index 702.")
  (println "  update-all-laws --resume-after 704")
  (println "                           Seed through progress index 704, then continue from 705.")
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
    "update-all-laws" (update-or-skip-unreachable!
                       #(update-all-laws! (update-all-laws-options (rest args))))
    "clean-derived" (do (store/clean-derived!)
                        (println "Derived files cleaned."))
    (do (usage)
        (System/exit 1))))
