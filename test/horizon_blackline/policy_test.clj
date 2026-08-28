(ns horizon-blackline.policy-test
  (:require [clojure.test :refer [deftest is]]
            [horizon-blackline.capital.policy :as policy]))

(def base-request
  {:intent {:symbol "AAPL" :side :buy :quantity "10" :entry-price "100" :stop-price "95"}
   :snapshot {:post-trade-symbol-weight "0.05"
              :post-trade-gross-exposure "0.10"
              :estimated-participation "0.01"
              :daily-drawdown "0.01"}
   :policy {:limits {:remaining-risk-budget "100"
                     :max-symbol-weight "0.10"
                     :max-gross-exposure "0.20"
                     :max-adv-participation "0.05"
                     :hard-drawdown-limit "0.05"}}
   :snapshot-valid? true
   :policy-active? true
   :evidence-valid? true
   :critics-complete? true})

(deftest concentration-is-denied-before-execution
  (let [result (policy/evaluate (assoc-in base-request [:snapshot :post-trade-symbol-weight] "0.11"))]
    (is (= :DENY (:result result)))
    (is (some #{:CONCENTRATION_LIMIT} (:reason-codes result)))))

(deftest stale-snapshot-and-inactive-policy-fail-closed
  (let [result (policy/evaluate (assoc base-request :snapshot-valid? false :policy-active? false))]
    (is (= :DENY (:result result)))
    (is (some #{:EVIDENCE_INVALID} (:reason-codes result)))
    (is (some #{:AUTH_EXPIRED} (:reason-codes result)))))

(deftest absent-or-excessive-liquidity-fails-closed
  (let [missing (policy/evaluate (update base-request :snapshot dissoc :estimated-participation))
        excessive (policy/evaluate (assoc-in base-request [:snapshot :estimated-participation] "0.06"))]
    (is (some #{:LIQUIDITY_LIMIT} (:reason-codes missing)))
    (is (some #{:LIQUIDITY_LIMIT} (:reason-codes excessive)))))
