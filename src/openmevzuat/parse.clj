(ns openmevzuat.parse
  (:require [clojure.string :as str]
            [openmevzuat.slug :as slug]))

(def heading-pattern
  #"(?iu)^\s*(GEÇİCİ\s+MADDE|GECICI\s+MADDE|EK\s+MADDE|MADDE)\s+([0-9]+(?:/[A-Z])?)\s*(?:([-‐‑‒–—.])\s*(.*?))?\s*$")

(def stop-marker-patterns
  [#"(?imu)^.*SAYILI.*KANUNA\s*\R\s*İŞLENEME[YZ]EN\s+HÜKÜMLER.*$"
   #"(?imu)^.*SAYILI\s+KANUNA\s+İŞLENEME[YZ]EN\s+HÜKÜMLER.*$"
   #"(?imu)^.*İŞLENEME[YZ]EN\s+HÜKÜMLER.*$"
   #"(?imu)^.*SAYILI\s+KANUNA\s+EK\s+VE\s+DEĞİŞİKLİK\s+GETİREN\s+MEVZUAT.*$"])

(defn- marker-index [text pattern]
  (let [matcher (re-matcher pattern text)]
    (when (.find matcher)
      (.start matcher))))

(defn truncate-after-document-body [text]
  (let [indexes (keep #(marker-index text %) stop-marker-patterns)]
    (if (seq indexes)
      (subs text 0 (apply min indexes))
      text)))

(defn heading->type [heading]
  (let [token (slug/slugify heading)]
    (cond
      (str/starts-with? token "gecici") :temporary
      (str/starts-with? token "ek") :additional
      :else :normal)))

(defn- inline-body? [separator rest]
  (and (not-empty rest)
       (or (not= "—" separator)
           (re-find #"^\s*(?:[-‐‑‒–—]|\(|\d)" rest))))

(defn- last-nonblank-index [lines]
  (first
   (filter #(not (str/blank? (nth lines %)))
           (range (dec (count lines)) -1 -1))))

(defn- previous-nonblank-index [lines idx]
  (first
   (filter #(not (str/blank? (nth lines %)))
           (range (dec idx) -1 -1))))

(defn- completed-body-line? [line]
  (boolean (re-find #"[.!?…)]$" (str/trim line))))

(defn- title-candidate? [line]
  (let [title (str/trim line)]
    (and (not-empty title)
         (<= (count title) 120)
         (not (re-find #"^\s*(?:[-–—]|\(|\d)" title))
         (not (re-find #"[.;:,]$" title))
         (not (re-find #"(?iu)(SAYILI\s+KANUN|RESM[İI]\s+GAZETE|DEĞİŞTİREN|YÜRÜRLÜĞE\s+GİRİŞ\s+TARİHİ)" title)))))

(defn- move-preceding-title [body-lines heading current]
  (if (:article/title heading)
    [body-lines heading]
    (let [body-lines (vec body-lines)]
      (if-let [idx (last-nonblank-index body-lines)]
        (let [candidate (str/trim (nth body-lines idx))]
          (if (and (title-candidate? candidate)
                   (or (nil? current)
                       (some->> (previous-nonblank-index body-lines idx)
                                (nth body-lines)
                                completed-body-line?)))
            [(subvec body-lines 0 idx) (assoc heading :article/title candidate)]
            [body-lines heading]))
        [body-lines heading]))))

(defn parse-heading [line]
  (when-let [[_ heading no separator rest] (re-matches heading-pattern (str line))]
    (let [rest (some-> rest str/trim not-empty)
          inline? (inline-body? separator rest)]
      (cond-> {:article/type (heading->type heading)
               :article/no no
               :article/title (when-not inline? rest)}
        inline? (assoc :article/inline-body rest)))))

(defn- finish-article [article body-lines]
  (when article
    (-> article
        (dissoc :article/inline-body)
        (assoc :article/body (str/trim (str/join "\n" body-lines))))))

(defn parse-articles [text]
  (let [lines (str/split (truncate-after-document-body (or text "")) #"\n" -1)]
    (loop [[line & more] lines
           current nil
           body []
           articles []]
      (if (nil? line)
        (cond-> articles
          current (conj (finish-article current body)))
        (if-let [heading (parse-heading line)]
          (let [[previous-body heading] (move-preceding-title body heading current)]
            (recur more
                   heading
                   (cond-> []
                     (:article/inline-body heading) (conj (:article/inline-body heading)))
                   (cond-> articles
                     current (conj (finish-article current previous-body)))))
          (recur more current (conj body line) articles))))))

(defn parse-document [document]
  (assoc document :articles (parse-articles (:text/full document))))
