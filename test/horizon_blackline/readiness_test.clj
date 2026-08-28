(ns horizon-blackline.readiness-test
  (:require [clojure.test :refer [deftest is]]
            [horizon-blackline.readiness :as readiness]))

(deftest readiness-reveals-missing-gates-without-secrets
  (let [missing (readiness/check-config true (constantly nil))
        ready (readiness/check-config true {"ALPACA_PAPER_TRADE" "true"
                                            "ALPACA_PAPER_ACCOUNT_ID" "account-id"
                                            "ALPACA_MCP_URL" "http://127.0.0.1:8001/mcp"})]
    (is (false? (:ready? missing)))
    (is (= ["ALPACA_PAPER_TRADE=true" "ALPACA_PAPER_ACCOUNT_ID" "ALPACA_MCP_URL"] (:missing missing)))
    (is (:ready? ready))
    (is (empty? (:missing ready)))))
