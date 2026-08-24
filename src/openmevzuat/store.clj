;; Copyright (c) 2026 OpenMevzuat contributors.
;; SPDX-License-Identifier: AGPL-3.0-only

(ns openmevzuat.store
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [openmevzuat.hash :as hash]
            [openmevzuat.slug :as slug])
  (:import [java.nio.file CopyOption Files StandardCopyOption]))

(def max-document-slug-length 180)

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

(defn- equal-byte-ranges? [left right length]
  (loop [i 0]
    (or (= i length)
        (and (= (aget left i) (aget right i))
             (recur (inc i))))))

(defn files-equal? [left right]
  (let [left (fs/path left)
        right (fs/path right)]
    (and (fs/exists? left)
         (fs/exists? right)
         (= (fs/size left) (fs/size right))
         (with-open [left-in (io/input-stream (io/file (str left)))
                     right-in (io/input-stream (io/file (str right)))]
           (let [left-buffer (byte-array 8192)
                 right-buffer (byte-array 8192)]
             (loop []
               ;; readNBytes, not read: a short read on one stream would
               ;; otherwise report two identical files as different.
               (let [left-read (.readNBytes left-in left-buffer 0 8192)
                     right-read (.readNBytes right-in right-buffer 0 8192)]
                 (cond
                   (not= left-read right-read) false
                   (zero? left-read) true
                   (equal-byte-ranges? left-buffer right-buffer left-read) (recur)
                   :else false))))))))

(defn replace-file-if-changed! [path temp-path]
  (let [path (fs/path path)
        temp-path (fs/path temp-path)]
    (if (files-equal? path temp-path)
      (do
        (fs/delete-if-exists temp-path)
        {:path (str path) :changed? false})
      (do
        (fs/create-dirs (fs/parent path))
        (Files/move (.toPath (io/file (str temp-path)))
                    (.toPath (io/file (str path)))
                    (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
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
  (let [raw (if (= :decree (:document/type document))
              (str (or (decree-subtype-prefix document) "decree")
                   "-" (:document/number document)
                   "-" (slug/slugify (:document/title document)))
              (str (:document/number document)
                   "-"
                   (slug/slugify (:document/title document))))]
    (if (<= (count raw) max-document-slug-length)
      raw
      (let [suffix (subs (hash/sha256-str raw) 0 12)
            prefix-length (- max-document-slug-length (count suffix) 1)
            prefix (str/replace (subs raw 0 prefix-length) #"-+$" "")]
        (str prefix "-" suffix)))))

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
