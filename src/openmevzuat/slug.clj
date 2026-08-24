;; Copyright (c) 2026 OpenMevzuat contributors.
;; SPDX-License-Identifier: AGPL-3.0-only

(ns openmevzuat.slug
  (:require [clojure.string :as str])
  (:import [java.util Locale]))

(def turkish-chars
  {\ç "c" \ğ "g" \ı "i" \ö "o" \ş "s" \ü "u"
   \Ç "c" \Ğ "g" \İ "i" \I "i" \Ö "o" \Ş "s" \Ü "u"})

(defn transliterate-turkish [s]
  (apply str (map #(get turkish-chars % %) (str s))))

(defn slugify [s]
  (-> s
      transliterate-turkish
      (.toLowerCase Locale/ROOT)
      str/trim
      (str/replace #"\s+" "-")
      (str/replace #"[^a-z0-9-]" "")
      (str/replace #"-+" "-")
      (str/replace #"(^-|-$)" "")))

