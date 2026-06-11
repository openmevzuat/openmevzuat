(ns openmevzuat.core-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [openmevzuat.fetch :as fetch]
            [openmevzuat.hash :as hash]
            [openmevzuat.parse :as parse]
            [openmevzuat.render :as render]
            [openmevzuat.slug :as slug]
            [openmevzuat.store :as store])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftest slug-normalization
  (is (= "turk-ceza-kanunu" (slug/slugify "Türk Ceza Kanunu")))
  (is (= "turkiye-cumhuriyeti-anayasasi"
         (slug/slugify " Türkiye Cumhuriyeti Anayasası ")))
  (is (= "olaganustu-hal-kanunu"
         (slug/slugify "Olağanüstü Hal Kanunu!"))))

(deftest article-heading-recognition
  (testing "normal, temporary, and additional article headings"
    (let [articles (parse/parse-articles
                    "MADDE 81 — Kasten öldürme\n\nBody\n\nGEÇİCİ MADDE 1\n\nTemporary body\n\nEK MADDE 1 — Additional title\n\nAdditional body")]
      (is (= [{:article/type :normal
               :article/no "81"
               :article/title "Kasten öldürme"
               :article/body "Body"}
              {:article/type :temporary
               :article/no "1"
               :article/title nil
               :article/body "Temporary body"}
              {:article/type :additional
               :article/no "1"
               :article/title "Additional title"
               :article/body "Additional body"}]
             articles))))
  (testing "appendix stop markers are not parsed as canonical articles"
    (let [articles (parse/parse-articles
                    "MADDE 1 — Main\n\nBody\n\n5237 SAYILI KANUNA İŞLENEMEYEN HÜKÜMLER\n\nGEÇİCİ MADDE 1- Appendix\n\nAppendix body")]
      (is (= [{:article/type :normal
               :article/no "1"
               :article/title "Main"
               :article/body "Body"}]
             articles))))
  (testing "split appendix stop markers are not parsed as canonical articles"
    (let [articles (parse/parse-articles
                    "MADDE 138\n\nBody\n\n18/10/1983 TARİHLİ VE 2918 SAYILI ANA KANUNA\nİŞLENEMEYEN HÜKÜMLER\n\nGEÇİCİ MADDE 1\n\nAppendix body")]
      (is (= [{:article/type :normal
               :article/no "138"
               :article/title nil
               :article/body "Body"}]
             articles))))
  (testing "hyphenated inline legal text remains in the body"
    (let [articles (parse/parse-articles
                    "MADDE 1 ‒ (1) Body starts here.\n\nBody continues.")]
      (is (= [{:article/type :normal
               :article/no "1"
               :article/title nil
               :article/body "(1) Body starts here.\n\nBody continues."}]
             articles))))
  (testing "preceding short title lines attach to the following article"
    (let [articles (parse/parse-articles
                    "Amaç\nMADDE 1- (1) First body.\n\nKapsam\nMADDE 2- (1) Second body.")]
      (is (= [{:article/type :normal
               :article/no "1"
               :article/title "Amaç"
               :article/body "(1) First body."}
              {:article/type :normal
               :article/no "2"
               :article/title "Kapsam"
               :article/body "(1) Second body."}]
             articles)))))

(deftest article-filename-generation
  (is (= "madde-081.md"
         (store/article-filename {:article/type :normal :article/no "81"})))
  (is (= "gecici-madde-001.md"
         (store/article-filename {:article/type :temporary :article/no "1"})))
  (is (= "ek-madde-001.md"
         (store/article-filename {:article/type :additional :article/no "1"})))
  (is (= "madde-123-a.md"
         (store/article-filename {:article/type :normal :article/no "123/A"}))))

(deftest decree-path-generation
  (let [khk {:document/id "decree/khk-700"
             :document/type :decree
             :decree/subtype :khk
             :document/number "700"
             :document/title "Örnek Kanun Hükmünde Kararname"}
        cbk {:document/id "decree/cbk-1"
             :document/type :decree
             :decree/subtype :cbk
             :document/number "1"
             :document/title "Örnek Cumhurbaşkanlığı Kararnamesi"}]
    (is (= "khk-700-ornek-kanun-hukmunde-kararname"
           (store/document-slug khk)))
    (is (= "cbk-1-ornek-cumhurbaskanligi-kararnamesi"
           (store/document-slug cbk)))
    (is (= "data/canonical/decrees/khk-700-ornek-kanun-hukmunde-kararname"
           (store/canonical-document-path khk)))
    (is (= "data/metadata/decrees/cbk-1-ornek-cumhurbaskanligi-kararnamesi.edn"
           (store/metadata-path cbk)))))

(deftest markdown-rendering
  (is (= "# MADDE 81 — Kasten öldürme\n\nBody\n"
         (render/render-article {:article/type :normal
                                 :article/no "81"
                                 :article/title "Kasten öldürme"
                                 :article/body "Body"})))
  (is (= "# GEÇİCİ MADDE 1\n\nBody\n"
         (render/render-article {:article/type :temporary
                                 :article/no "1"
                                 :article/body "Body"}))))

(deftest sha256-hashing
  (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
         (hash/sha256-str "abc"))))

(deftest fetch-resilience-helpers
  (is (#'fetch/pdf-bytes? (.getBytes "%PDF-1.7\nbody" "US-ASCII")))
  (is (false? (#'fetch/pdf-bytes? (.getBytes "<html></html>" "UTF-8"))))
  (is (= 2000 (#'fetch/retry-after-ms {"retry-after" "2"})))
  (is (= :retry
         (:action (#'fetch/response-action
                   "https://example.test/source.pdf"
                   {:status 429
                    :headers {"retry-after" "2"}
                    :body (.getBytes "Too Many Requests" "UTF-8")}))))
  (is (= :fail
         (:action (#'fetch/response-action
                   "https://example.test/source.pdf"
                   {:status 200
                    :headers {"content-type" "text/html"}
                    :body (.getBytes "<html>not a pdf</html>" "UTF-8")}))))
  (testing "connection failures can open the source circuit"
    (let [state @#'fetch/source-circuit-state
          url "https://example.test/source.pdf"
          config {:circuit-breaker-failures 2}]
      (try
        (reset! state {})
        (is (#'fetch/connection-exception? (java.net.ConnectException. "blocked")))
        (is (nil? (#'fetch/circuit-open-error url config)))
        (#'fetch/record-circuit-failure! url "first failure")
        (is (nil? (#'fetch/circuit-open-error url config)))
        (#'fetch/record-circuit-failure! url "second failure")
        (is (some? (#'fetch/circuit-open-error url config)))
        (finally
          (reset! state {}))))))

(deftest write-if-changed-behavior
  (let [dir (Files/createTempDirectory "openmevzuat-test"
                                       (make-array FileAttribute 0))
        path (fs/path dir "example.txt")]
    (try
      (is (:changed? (store/write-if-changed! path "one")))
      (is (false? (:changed? (store/write-if-changed! path "one"))))
      (is (:changed? (store/write-if-changed! path "two")))
      (is (= "two" (slurp (str path))))
      (finally
        (fs/delete-tree dir)))))
