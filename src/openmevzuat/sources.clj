;; Copyright (c) 2026 OpenMevzuat contributors.
;; SPDX-License-Identifier: AGPL-3.0-only

(ns openmevzuat.sources
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.io PushbackReader]))

(defn read-resource-edn [resource-name]
  (with-open [reader (io/reader (io/resource resource-name))]
    (edn/read {:readers *data-readers*} (PushbackReader. reader))))

(defn documents []
  (:documents (read-resource-edn "documents.edn")))

(defn sources []
  (:sources (read-resource-edn "sources.edn")))

(defn source-by-id [source-id]
  (first (filter #(= source-id (:source/id %)) (sources))))
