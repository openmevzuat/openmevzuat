;; Copyright (c) 2026 OpenMevzuat contributors.
;; SPDX-License-Identifier: AGPL-3.0-only

(ns openmevzuat.hash
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(defn sha256-str [s]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (str s) StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) digest))))

