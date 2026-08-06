(ns openmevzuat.tls-test
  (:require [clojure.test :refer [deftest is testing]]
            [openmevzuat.catalog :as catalog]
            [openmevzuat.fetch :as fetch]
            [openmevzuat.resmigazete :as rg]
            [openmevzuat.tls :as tls])
  (:import [java.security KeyStore]
           [java.security.cert CertPathValidator CertificateFactory PKIXParameters
            TrustAnchor X509Certificate]
           [javax.net.ssl TrustManagerFactory X509ExtendedTrustManager]
           [javax.security.auth.x500 X500Principal]))

(defn- stub-certificate [subject issuer]
  (proxy [X509Certificate] []
    (getSubjectX500Principal [] (X500Principal. ^String subject))
    (getIssuerX500Principal [] (X500Principal. ^String issuer))))

(defn- principals [chain]
  (mapv #(.getName (.getSubjectX500Principal ^X509Certificate %)) chain))

(defn- trust-anchors []
  (let [factory (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm))
        ^KeyStore default-trust-store nil]
    (.init factory default-trust-store)
    (->> (.getTrustManagers factory)
         (filter #(instance? X509ExtendedTrustManager %))
         (mapcat #(.getAcceptedIssuers ^X509ExtendedTrustManager %))
         (map #(TrustAnchor. ^X509Certificate % nil))
         set)))

(deftest incomplete-chains-are-completed-with-bundled-intermediates
  (let [leaf (stub-certificate "CN=leaf" "CN=intermediate")
        intermediate (stub-certificate "CN=intermediate" "CN=root")
        root (stub-certificate "CN=root" "CN=root")]
    (testing "a leaf-only chain gains the issuing intermediate, leaf first"
      (is (= ["CN=leaf" "CN=intermediate"]
             (principals (tls/complete-chain [leaf] [intermediate])))))

    (testing "chains are followed until no bundled issuer matches"
      (is (= ["CN=leaf" "CN=intermediate" "CN=root"]
             (principals (tls/complete-chain [leaf] [root intermediate])))))

    (testing "a complete chain is left untouched"
      (is (= ["CN=leaf" "CN=intermediate"]
             (principals (tls/complete-chain [leaf intermediate] [intermediate]))))
      (is (= ["CN=root"]
             (principals (tls/complete-chain [root] [intermediate])))))

    (testing "unrelated intermediates are ignored"
      (is (= ["CN=leaf"]
             (principals (tls/complete-chain [leaf] [(stub-certificate "CN=other" "CN=root")])))))

    (testing "an empty chain stays empty"
      (is (= [] (principals (tls/complete-chain [] [intermediate])))))))

(deftest bundled-intermediates-are-loaded
  (let [intermediates (tls/bundled-intermediates)]
    (is (= (count tls/bundled-certificate-resources) (count intermediates)))
    (is (every? #(instance? X509Certificate %) intermediates))
    (testing "the bundled intermediate issues the certificate the sources present"
      (is (contains? (set (map #(.getName (.getSubjectX500Principal ^X509Certificate %))
                               intermediates))
                     "CN=GeoTrust TLS RSA CA G1,OU=www.digicert.com,O=DigiCert Inc,C=US")))))

(deftest bundled-intermediates-chain-to-a-trusted-root
  (testing "every bundled certificate is signed by a JDK trust anchor and is still valid"
    (let [validator (CertPathValidator/getInstance "PKIX")
          factory (CertificateFactory/getInstance "X.509")
          parameters (doto (PKIXParameters. (trust-anchors))
                       ;; Offline check: signature and validity only.
                       (.setRevocationEnabled false))]
      (doseq [^X509Certificate intermediate (tls/bundled-intermediates)]
        (.checkValidity intermediate)
        (is (some? (.validate validator
                              (.generateCertPath factory [intermediate])
                              parameters))
            (str "not signed by a trusted root: "
                 (.getName (.getSubjectX500Principal intermediate))))))))

(deftest ssl-context-is-shared
  (is (identical? (tls/ssl-context) (tls/ssl-context))))

(deftest every-source-request-uses-the-trust-context
  (testing "each namespace that builds its own client still gets the completed chain"
    (let [config (fetch/fetch-config)
          expected (tls/ssl-context)]
      (doseq [[label options] [["fetch" (#'fetch/request-options config)]
                               ["catalog" (#'catalog/request-options {} config)]
                               ["resmigazete" (#'rg/request-options nil "*/*" config)]
                               ["resmigazete post" (#'rg/request-options {:a 1} "*/*" config)]]]
        (is (identical? expected (get-in options [:http-client :ssl-context]))
            (str label " builds an HttpClient without the bundled trust context"))
        (is (= (:connect-timeout-ms config) (get-in options [:http-client :connect-timeout]))
            (str label " drops the configured connect timeout"))))))
