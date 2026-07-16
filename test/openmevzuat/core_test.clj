(ns openmevzuat.core-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [openmevzuat.catalog :as catalog]
            [openmevzuat.core :as core]
            [openmevzuat.fetch :as fetch]
            [openmevzuat.hash :as hash]
            [openmevzuat.parse :as parse]
            [openmevzuat.render :as render]
            [openmevzuat.resmigazete :as rg]
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

(deftest long-document-slugs-are-bounded
  (let [document {:document/id "law/400"
                  :document/type :law
                  :document/number "400"
                  :document/title (str/join " " (repeat 40 "Uzun Kanun Basligi"))}
        document-slug (store/document-slug document)]
    (is (<= (count document-slug) store/max-document-slug-length))
    (is (re-find #"-[0-9a-f]{12}$" document-slug))
    (is (str/starts-with? document-slug "400-"))))

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
        (let [error (#'fetch/circuit-open-error url config)]
          (is (some? error))
          (is (fetch/source-unreachable? error))
          (is (= :source-unreachable (:openmevzuat/error (ex-data error)))))
        (finally
          (reset! state {}))))))

(deftest generated-pdf-fallback-url
  (is (= "https://www.mevzuat.gov.tr/File/GeneratePdf?mevzuatNo=6111&mevzuatTur=Kanun&mevzuatTertip=5"
         (fetch/generated-pdf-url "https://www.mevzuat.gov.tr/MevzuatMetin/1.5.6111.pdf")))
  (is (nil? (fetch/generated-pdf-url "https://www.mevzuat.gov.tr/MevzuatMetin/0.1.2.pdf")))
  (is (#'fetch/pdf-url? "https://www.mevzuat.gov.tr/File/GeneratePdf?mevzuatNo=6111&mevzuatTur=Kanun&mevzuatTertip=5")))

(deftest full-update-resume-options
  (is (= {:resume-from-index 702}
         (core/update-all-laws-options ["--resume-from" "702"])))
  (is (= {:resume-from-index 705}
         (core/update-all-laws-options ["--resume-after" "704"]))))

(deftest rendered-article-body-for-resume-search
  (is (= "Body\n\ncontinues"
         (core/rendered-article-body "# MADDE 1 — Title\n\nBody\n\ncontinues\n"))))

(deftest manifest-skip-behavior
  (let [manifest (core/skip-manifest "2026-06-12")]
    (is (= "data/manifests/2026-06-12.edn" (:path manifest)))
    (is (= {:path "data/manifests/2026-06-12.edn"
            :changed? false
            :skipped? true
            :reason :no-content-changes}
           (:write manifest)))
    (is (zero? (core/changed-count [(:write manifest)])))))

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

(deftest catalog-row-conversion
  (let [document (catalog/law-row->document
                  {:mevzuatNo "2918"
                   :mevAdi "Karayolları Trafik Kanunu"
                   :mevzuatTur 1
                   :mevzuatTertip "5"
                   :url "mevzuat?MevzuatNo=2918&MevzuatTur=1&MevzuatTertip=5"
                   :resmiGazeteTarihi "18.10.1983"
                   :resmiGazeteSayisi "18195"
                   :kabulTarih "13.10.1983"})]
    (is (= "law/2918" (:document/id document)))
    (is (= "2918" (:document/number document)))
    (is (= :law (:document/type document)))
    (is (= "https://www.mevzuat.gov.tr/MevzuatMetin/1.5.2918.pdf"
           (:source/url document)))
    (is (= "https://www.mevzuat.gov.tr/mevzuat?MevzuatNo=2918&MevzuatTur=1&MevzuatTertip=5"
           (:source/catalog-url document)))))

(deftest catalog-keeps-tertip-collisions
  (let [catalog (catalog/catalog-map
                 [{:mevzuatNo "3201"
                   :mevAdi "Tertip 5 Law"
                   :mevzuatTur 1
                   :mevzuatTertip "5"}
                  {:mevzuatNo "3201"
                   :mevAdi "Tertip 3 Law"
                   :mevzuatTur 1
                   :mevzuatTertip "3"}]
                 (java.util.Date. 0))]
    (is (= ["3201"] (:catalog/duplicate-numbers catalog)))
    (is (= ["law/t3-3201" "law/t5-3201"]
           (mapv :document/id (:documents catalog))))))

(deftest selected-documents-use-catalog-and-configured-overrides
  (with-redefs [core/catalog-law-documents
                (fn []
                  [{:document/id "law/1"
                    :document/type :law
                    :document/number "1"
                    :document/title "Catalog Title"}
                   {:document/id "law/2"
                    :document/type :law
                    :document/number "2"
                    :document/title "Catalog Only"}])
                core/configured-documents
                (fn []
                  [{:document/id "law/1"
                    :document/type :law
                    :document/number "1"
                    :document/title "Configured Title"}])]
    (is (= ["law/2" "law/1"]
           (mapv :document/id (core/selected-documents ["2" "law/1"]))))
    (is (= "Configured Title"
           (:document/title (first (core/selected-documents ["1"])))))))

(deftest incremental-search-merge-keeps-other-documents
  (let [written (atom nil)
        old-lines ["{\"document/id\":\"law/1\",\"article/id\":\"law/1/article/1\"}"
                   "{\"document/id\":\"law/2\",\"article/id\":\"law/2/article/old\"}"]
        prepared [{:document {:document/id "law/2"
                              :document/type :law
                              :document/number "2"
                              :document/title "Law Two"
                              :articles [{:article/id "law/2/article/1"
                                          :article/type :normal
                                          :article/no "1"
                                          :article/title nil
                                          :article/path "articles/madde-001.md"
                                          :article/body "New text"}]}}]]
    (with-redefs [store/read-file (fn [_] (str (clojure.string/join "\n" old-lines) "\n"))
                  store/write-if-changed! (fn [path content]
                                            (reset! written {:path path :content content})
                                            {:path path :changed? true})]
      (core/write-search! prepared {:merge? true})
      (is (= "derived/search/documents.jsonl" (:path @written)))
      (is (clojure.string/includes? (:content @written) "law/1/article/1"))
      (is (not (clojure.string/includes? (:content @written) "law/2/article/old")))
      (is (clojure.string/includes? (:content @written) "New text")))))

(deftest resmi-gazete-affected-law-extraction
  (let [html "<p>MADDE 16- 13/10/1983 tarihli ve 2918 sayılı Karayolları Trafik Kanununun 20 nci maddesinde yer alan “üç iş günü” ibaresi “on beş iş günü” şeklinde değiştirilmiştir.</p>
              <p>MADDE 17- 25/10/1984 tarihli ve 3065 sayılı Katma Değer Vergisi Kanununun 17 nci maddesi değiştirilmiştir.</p>
              <p>MADDE 30- Bu Kanun yayımı tarihinde yürürlüğe girer.</p>"
        affected (rg/affected-laws-from-html html)]
    (is (= ["2918" "3065"] (mapv :law/number affected)))
    (is (= ["Karayolları Trafik Kanun" "Katma Değer Vergisi Kanun"]
           (mapv :law/title affected)))))

(deftest affected-laws-resolve-duplicate-numbers-by-title
  (with-redefs [core/catalog-law-documents
                (fn []
                  [{:document/id "law/t5-3201"
                    :document/type :law
                    :document/number "3201"
                    :document/title "Yurt Dışında Bulunan Türk Vatandaşlarının Yurt Dışında Geçen Sürelerinin Sosyal Güvenlikleri Bakımından Değerlendirilmesi Hakkında Kanun"
                    :source/url "https://www.mevzuat.gov.tr/MevzuatMetin/1.5.3201.pdf"}
                   {:document/id "law/t3-3201"
                    :document/type :law
                    :document/number "3201"
                    :document/title "Emniyet Teşkilat Kanunu"
                    :source/url "https://www.mevzuat.gov.tr/MevzuatMetin/1.3.3201.pdf"}])
                core/configured-documents
                (fn [] [])]
    (let [{:keys [documents unresolved]}
          (core/resolve-affected-laws [{:law/number "3201"
                                        :law/title "Emniyet Teşkilat Kanun"}])]
      (is (empty? unresolved))
      (is (= ["law/t3-3201"] (mapv :document/id documents))))))
