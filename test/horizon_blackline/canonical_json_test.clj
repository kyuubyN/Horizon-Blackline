(ns horizon-blackline.canonical-json-test
  (:require [clojure.test :refer [deftest is]]
            [horizon-blackline.canonical-json :as canonical]))

(deftest confidence-numbers-are-canonical-and-nonfinite-values-reject
  (is (= "{\"confidence\":1.0}" (canonical/encode {:confidence 1.0})))
  (is (thrown? clojure.lang.ExceptionInfo (canonical/encode {:confidence Double/NaN}))))
