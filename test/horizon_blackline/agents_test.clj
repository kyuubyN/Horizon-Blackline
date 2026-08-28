(ns horizon-blackline.agents-test
  (:require [clojure.test :refer [deftest is]]
            [horizon-blackline.agents.registry :as registry]))

(deftest agents-cannot-acquire-capital-or-broker-authority
  (is (registry/registry-valid?))
  (is (registry/authorize-action! "research" "thesis:write"))
  (is (thrown? clojure.lang.ExceptionInfo (registry/authorize-action! "research" "alpaca:submit")))
  (is (thrown? clojure.lang.ExceptionInfo (registry/authorize-action! "risk-critic" "authorize"))))
