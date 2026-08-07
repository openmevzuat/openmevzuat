(ns openmevzuat.test-runner
  (:require [clojure.test :as test]
            [openmevzuat.core-test]
            [openmevzuat.normalize-test]
            [openmevzuat.tls-test]))

(defn -main [& _args]
  (let [{:keys [fail error]} (test/run-tests 'openmevzuat.core-test
                                             'openmevzuat.normalize-test
                                             'openmevzuat.tls-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))

