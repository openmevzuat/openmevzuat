;; Copyright (c) 2026 OpenMevzuat contributors.
;; SPDX-License-Identifier: AGPL-3.0-only

(ns openmevzuat.render
  (:require [clojure.string :as str]))

(defn article-heading [article]
  (let [prefix (case (:article/type article)
                 :temporary "GEÇİCİ MADDE"
                 :additional "EK MADDE"
                 "MADDE")
        base (str prefix " " (:article/no article))]
    (if-let [title (some-> (:article/title article) str/trim not-empty)]
      (str base " — " title)
      base)))

(defn render-article [article]
  (str "# " (article-heading article) "\n\n"
       (str/trim (or (:article/body article) ""))
       "\n"))

(defn document-type-label [document]
  (case (:document/type document)
    :constitution "Anayasa"
    :law "Kanun"
    :decree (case (:decree/subtype document)
              :khk "Kanun Hükmünde Kararname"
              :cbk "Cumhurbaşkanlığı Kararnamesi"
              "Kararname")
    (name (:document/type document))))

(defn document-number-label [document]
  (case (:document/type document)
    :decree "Kararname No"
    "Kanun No"))

(defn render-readme [document]
  (let [articles (:articles document)]
    (str "# " (:document/title document) "\n\n"
         "**" (document-number-label document) ":** " (:document/number document) "  \n"
         "**Tür:** " (document-type-label document) "  \n"
         "**Kaynak:** " (or (:source/name document) "mevzuat.gov.tr") "  \n"
         "**OpenMevzuat ID:** " (:document/id document) "  \n\n"
         "> This project is not an official source and does not provide legal advice. Verify against official sources.\n\n"
         "## Maddeler\n\n"
         (if (seq articles)
           (str (str/join "\n"
                          (map (fn [article]
                                 (str "- [" (article-heading article) "]("
                                      (:article/path article) ")"))
                               articles))
                "\n")
           "_No articles parsed._\n"))))

(defn render-full-text [document]
  (str "# " (:document/title document) "\n\n"
       "**" (document-number-label document) ":** " (:document/number document) "  \n"
       "**Kaynak:** " (or (:source/name document) "mevzuat.gov.tr") "  \n"
       "**OpenMevzuat ID:** " (:document/id document) "  \n\n"
       (str/join "\n\n"
                 (map #(str (article-heading %) "\n\n" (str/trim (or (:article/body %) "")))
                      (:articles document)))
       "\n"))
