(ns openmevzuat.tls
  "TLS trust configuration for the official sources.

  mevzuat.gov.tr and resmigazete.gov.tr serve only their leaf certificate and
  omit the issuing intermediate, so the JDK cannot build a certification path to
  a trusted root and every request fails with SunCertPathBuilderException.
  Browsers hide this by fetching the intermediate over AuthorityInfoAccess,
  which the JDK does not do by default.

  The intermediates in resources/certs are appended to the chain the server
  presents before the platform trust manager validates it. Nothing is trusted
  blindly: the completed chain still has to validate against the JDK trust
  anchors, and the hostname check is left to the platform trust manager."
  (:require [clojure.java.io :as io])
  (:import [java.net Socket]
           [java.security KeyStore]
           [java.security.cert CertificateFactory X509Certificate]
           [javax.net.ssl SSLContext SSLEngine TrustManager TrustManagerFactory
            X509ExtendedTrustManager]))

(def bundled-certificate-resources
  ["certs/geotrust-tls-rsa-ca-g1.pem"])

(defn- read-certificates [resource-name]
  (when-let [resource (io/resource resource-name)]
    (with-open [stream (io/input-stream resource)]
      (vec (.generateCertificates (CertificateFactory/getInstance "X.509") stream)))))

(defn bundled-intermediates
  "X509 intermediates shipped with the repository, used to complete chains that
  the official sources serve incomplete."
  []
  (into []
        (comp (mapcat read-certificates)
              (filter #(instance? X509Certificate %)))
        bundled-certificate-resources))

(defn- self-signed? [^X509Certificate certificate]
  (= (.getSubjectX500Principal certificate)
     (.getIssuerX500Principal certificate)))

(defn complete-chain
  "Returns the presented chain extended with any bundled intermediate that
  issued its last certificate. The chain stays ordered leaf-first, and is
  returned untouched when the server already sent a complete chain."
  [chain intermediates]
  (loop [chain (vec chain)
         remaining (vec intermediates)]
    (let [^X509Certificate tail (peek chain)]
      (if (or (nil? tail)
              (empty? remaining)
              (self-signed? tail))
        chain
        (let [issuer (.getIssuerX500Principal tail)]
          (if-let [issuer-certificate (first (filter #(= issuer (.getSubjectX500Principal ^X509Certificate %))
                                                     remaining))]
            (recur (conj chain issuer-certificate)
                   (vec (remove #(identical? issuer-certificate %) remaining)))
            chain))))))

(defn- ->chain-array [chain]
  (into-array X509Certificate chain))

(defn chain-completing-trust-manager
  "Wraps `delegate` so incomplete server chains are completed with
  `intermediates` before the delegate validates them."
  ^X509ExtendedTrustManager [^X509ExtendedTrustManager delegate intermediates]
  (let [complete (fn [chain] (->chain-array (complete-chain chain intermediates)))]
    (proxy [X509ExtendedTrustManager] []
      (getAcceptedIssuers []
        (.getAcceptedIssuers delegate))

      (checkClientTrusted
        ([chain auth-type]
         (.checkClientTrusted delegate chain auth-type))
        ([chain auth-type socket-or-engine]
         (if (instance? SSLEngine socket-or-engine)
           (.checkClientTrusted delegate chain auth-type ^SSLEngine socket-or-engine)
           (.checkClientTrusted delegate chain auth-type ^Socket socket-or-engine))))

      (checkServerTrusted
        ([chain auth-type]
         (.checkServerTrusted delegate (complete chain) auth-type))
        ([chain auth-type socket-or-engine]
         (if (instance? SSLEngine socket-or-engine)
           (.checkServerTrusted delegate (complete chain) auth-type ^SSLEngine socket-or-engine)
           (.checkServerTrusted delegate (complete chain) auth-type ^Socket socket-or-engine)))))))

(defn- platform-trust-managers []
  (let [factory (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm))
        ;; A nil KeyStore selects the JDK trust store.
        ^KeyStore default-trust-store nil]
    (.init factory default-trust-store)
    (.getTrustManagers factory)))

(defn- build-ssl-context []
  (let [intermediates (bundled-intermediates)]
    (if (empty? intermediates)
      (SSLContext/getDefault)
      (let [managers (into-array TrustManager
                                 (map (fn [manager]
                                        (if (instance? X509ExtendedTrustManager manager)
                                          (chain-completing-trust-manager manager intermediates)
                                          manager))
                                      (platform-trust-managers)))
            context (SSLContext/getInstance "TLS")]
        (.init context nil managers nil)
        context))))

(def ^:private ssl-context-delay
  (delay
    (try
      (build-ssl-context)
      (catch Exception e
        (binding [*out* *err*]
          (println "Falling back to the default SSL context:" (.getMessage e)))
        (SSLContext/getDefault)))))

(defn ssl-context
  "Shared SSLContext used for every source request."
  ^SSLContext []
  @ssl-context-delay)
