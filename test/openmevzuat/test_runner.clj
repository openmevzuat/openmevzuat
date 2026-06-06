(ns openmevzuat.test-runner
  (:require [clojure.test :as test]
            [openmevzuat.core-test]))

(defn -main [& _args]
  (let [{:keys [fail error]} (test/run-tests 'openmevzuat.core-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))

