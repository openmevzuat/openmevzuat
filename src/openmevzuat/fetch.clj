(ns openmevzuat.fetch
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hato.client :as http])
  (:import [java.time Instant]
           [java.util Date]
           [org.apache.pdfbox Loader]
           [org.apache.pdfbox.text PDFTextStripper]))

(defn now-date []
  (Date/from (Instant/now)))

(defn fixture-mode? []
  (= "true" (some-> (System/getenv "OPENMEVZUAT_FIXTURE_MODE") str/lower-case)))

(defn fixture-resource [document]
  (str "fixtures/" (:document/number document) ".txt"))

(defn read-fixture [document]
  (when-let [resource (io/resource (fixture-resource document))]
    (slurp resource)))

(defn pdf-bytes->text [bytes]
  (with-open [document (Loader/loadPDF bytes)]
    (let [stripper (PDFTextStripper.)]
      (.setSortByPosition stripper true)
      (.getText stripper document))))

(defn fetch-url [url]
  (let [response (http/get url {:as :byte-array
                                :headers {"User-Agent" "OpenMevzuat/0.1"}
                                :throw-exceptions false})
        status (:status response)
        body (:body response)]
    (when-not (<= 200 status 299)
      (throw (ex-info "Failed to fetch source URL"
                      {:url url :status status})))
    (if (str/ends-with? (str/lower-case url) ".pdf")
      (pdf-bytes->text body)
      (String. body "UTF-8"))))

(defn fetch-document [document source]
  (let [fetched-at (now-date)
        url (:source/url document)
        fixture? (fixture-mode?)
        text (if fixture?
               (or (read-fixture document)
                   (throw (ex-info "Fixture not found" {:document/id (:document/id document)})))
               (fetch-url url))]
    (assoc document
           :source/name (:source/name source)
           :source/base-url (:source/base-url source)
           :source/fetched-at fetched-at
           :source/fixture? fixture?
           :text/full text)))
