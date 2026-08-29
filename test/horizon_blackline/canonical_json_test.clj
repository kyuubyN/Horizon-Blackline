(ns horizon-blackline.canonical-json-test
  (:require [clojure.test :refer [deftest is]]
            [horizon-blackline.canonical-json :as canonical]))

(deftest confidence-numbers-are-canonical-and-nonfinite-values-reject
  (is (= "{\"confidence\":1.0}" (canonical/encode {:confidence 1.0})))
  (is (thrown? clojure.lang.ExceptionInfo (canonical/encode {:confidence Double/NaN}))))

(deftest sets-encode-deterministically-regardless-of-iteration-order
  (is (= (canonical/encode #{"b" "a" "c"}) (canonical/encode (set (reverse ["b" "a" "c"])))))
  (is (= "[\"a\",\"b\",\"c\"]" (canonical/encode #{"a" "b" "c"}))))

(deftest non-named-map-keys-encode-via-str-instead-of-throwing
  (is (= "{\"1\":\"x\"}" (canonical/encode {1 "x"}))))
