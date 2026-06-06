(ns openmevzuat.normalize
  (:require [clojure.string :as str]))

(def html-entities
  {"&nbsp;" " "
   "&amp;" "&"
   "&lt;" "<"
   "&gt;" ">"
   "&quot;" "\""
   "&apos;" "'"
   "&#39;" "'"})

(defn- decode-numeric-entity [entity]
  (try
    (let [[_ hex? value] (re-matches #"&#(x?)([0-9A-Fa-f]+);" entity)
          radix (if (= "x" hex?) 16 10)
          codepoint (Integer/parseInt value radix)]
      (String. (Character/toChars codepoint)))
    (catch Exception _
      entity)))

(defn decode-html-entities [s]
  (-> (reduce-kv str/replace (str s) html-entities)
      (str/replace #"&#x?[0-9A-Fa-f]+;" decode-numeric-entity)))

(defn strip-obvious-html [s]
  (if (re-find #"<[A-Za-z!/][^>]*>" s)
    (-> s
        (str/replace #"(?i)<\s*br\s*/?\s*>" "\n")
        (str/replace #"(?i)</\s*(p|div|li|tr|h[1-6])\s*>" "\n")
        (str/replace #"<[^>]+>" " "))
    s))

(defn normalize-whitespace [s]
  (-> s
      (str/replace "\r\n" "\n")
      (str/replace "\r" "\n")
      (str/replace #"\u00a0" " ")
      (str/replace #"[ \t\f\u000B\u1680\u2000-\u200A\u202F\u205F\u3000]+" " ")))

(defn trim-line-trailing-spaces [s]
  (->> (str/split s #"\n" -1)
       (map #(str/replace % #"[ \t]+$" ""))
       (str/join "\n")))

(defn normalize-text [s]
  (-> s
      (or "")
      decode-html-entities
      strip-obvious-html
      normalize-whitespace
      trim-line-trailing-spaces
      str/trim
      (str/replace #"\n{3,}" "\n\n")))

