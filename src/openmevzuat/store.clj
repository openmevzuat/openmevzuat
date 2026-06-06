(ns openmevzuat.store
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [openmevzuat.slug :as slug]))

(defn read-file [path]
  (when (fs/exists? path)
    (slurp (io/file (str path)))))

(defn write-if-changed! [path content]
  (let [path (fs/path path)
        content (str content)
        existing (read-file path)]
    (if (= existing content)
      {:path (str path) :changed? false}
      (do
        (fs/create-dirs (fs/parent path))
        (spit (io/file (str path)) content)
        {:path (str path) :changed? true}))))

(defn article-number-token [article-no]
  (let [raw (-> article-no str slug/transliterate-turkish str/lower-case str/trim)
        compact (str/replace raw #"\s+" "")
        token (slug/slugify article-no)]
    (if-let [[_ digits suffix] (re-matches #"([0-9]+)(?:/([a-z0-9]+))?" compact)]
      (str (format "%03d" (Long/parseLong digits))
           (when suffix (str "-" suffix)))
      token)))

(defn article-filename [article]
  (let [prefix (case (:article/type article)
                 :temporary "gecici-madde"
                 :additional "ek-madde"
                 "madde")]
    (str prefix "-" (article-number-token (:article/no article)) ".md")))

(defn decree-subtype-prefix [document]
  (case (:decree/subtype document)
    :khk "khk"
    :cbk "cbk"
    (when-let [[_ prefix] (re-find #"^decree/(khk|cbk)-" (or (:document/id document) ""))]
      prefix)))

(defn document-slug [document]
  (if (= :decree (:document/type document))
    (str (or (decree-subtype-prefix document) "decree")
         "-" (:document/number document)
         "-" (slug/slugify (:document/title document)))
    (str (:document/number document) "-" (slug/slugify (:document/title document)))))

(defn canonical-kind-dir [document]
  (case (:document/type document)
    :constitution "constitution"
    :law "laws"
    :decree "decrees"
    "laws"))

(defn canonical-document-path [document]
  (str "data/canonical/" (canonical-kind-dir document) "/" (document-slug document)))

(defn metadata-path [document]
  (str "data/metadata/" (canonical-kind-dir document) "/" (document-slug document) ".edn"))

(defn full-text-path [document]
  (str "derived/full-text/" (canonical-kind-dir document) "/" (document-slug document) ".md"))

(defn attach-article-paths [document]
  (update document :articles
          (fn [articles]
            (vec (map #(assoc % :article/path (str "articles/" (article-filename %)))
                      articles)))))

(defn remove-stale-article-files! [document]
  (let [article-dir (fs/path (canonical-document-path document) "articles")
        expected (set (map :article/path (:articles document)))]
    (when (fs/exists? article-dir)
      (doall
       (for [path (fs/glob article-dir "*.md")
             :let [relative (str "articles/" (fs/file-name path))]
             :when (not (contains? expected relative))]
         (do
           (fs/delete-if-exists path)
           {:path (str path) :changed? true}))))))

(defn clean-derived! []
  (doseq [path ["derived/full-text" "derived/search" "derived/diffs"]]
    (when (fs/exists? path)
      (fs/delete-tree path)))
  (fs/create-dirs "derived/full-text/constitution")
  (fs/create-dirs "derived/full-text/laws")
  (fs/create-dirs "derived/full-text/decrees")
  (fs/create-dirs "derived/search")
  (fs/create-dirs "derived/diffs"))
