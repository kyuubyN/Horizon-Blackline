(ns horizon-blackline.schema-test
  (:require [clojure.test :refer [deftest is]]
            [horizon-blackline.schema :as schema]))

(deftest zero-daily-drawdown-is-valid
  (is (schema/valid? schema/risk-snapshot
                     {:account-id "ACC" :equity "10000" :buying-power "5000"
                      :post-trade-symbol-weight "0.05" :post-trade-gross-exposure "0.15"
                      :estimated-participation "0.01" :daily-drawdown "0"
                      :as-of "2026-01-01T00:00:00Z" :source-digest "fixture:1"})))

(deftest negative-daily-drawdown-is-still-rejected
  (is (not (schema/valid? schema/risk-snapshot
                          {:account-id "ACC" :equity "10000" :buying-power "5000"
                           :post-trade-symbol-weight "0.05" :post-trade-gross-exposure "0.15"
                           :estimated-participation "0.01" :daily-drawdown "-0.01"
                           :as-of "2026-01-01T00:00:00Z" :source-digest "fixture:1"}))))

(deftest zero-quantity-is-still-rejected-by-strict-positive-schema
  (is (not (schema/valid? schema/trade-intent
                          {:intent-id (str (java.util.UUID/randomUUID))
                           :bdr-id (str (java.util.UUID/randomUUID))
                           :asset-class :stock :symbol "AAPL" :side :buy :order-type :market
                           :quantity "0" :entry-price "100" :stop-price "98"
                           :requested-risk-budget "100" :as-of "2026-01-01T00:00:00Z"
                           :evidence-refs []}))))

(deftest assert-valid-failure-produces-json-safe-details
  (try
    (schema/assert-valid! schema/trade-intent {:symbol "AAPL"})
    (is false "expected assert-valid! to throw")
    (catch clojure.lang.ExceptionInfo e
      (let [details (:errors (ex-data e))]
        (is (map? details))
        (is (every? (fn [v] (or (string? v) (sequential? v) (map? v))) (vals details)))))))
