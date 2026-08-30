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

(deftest option-loss-at-stop-is-scaled-by-the-100-share-contract-multiplier
  (let [stock-loss (policy/calculate-loss-at-stop
                     {:asset-class :stock :side :buy :quantity "1" :entry-price "6.00" :stop-price "3.00"})
        option-loss (policy/calculate-loss-at-stop
                     {:asset-class :option :side :buy :quantity "1" :entry-price "6.00" :stop-price "3.00"})]
    (is (= 3.00M stock-loss))
    (is (= 300.00M option-loss))))

(deftest option-risk-budget-uses-the-scaled-loss
  (let [result (policy/evaluate
                (-> base-request
                    (assoc-in [:intent :asset-class] :option)
                    (assoc-in [:intent :entry-price] "6.00")
                    (assoc-in [:intent :stop-price] "3.00")
                    (assoc-in [:intent :quantity] "1")))]
    ;; 1 contract * (6.00-3.00) * 100 = $300 loss at stop, well over the $100 budget above.
    (is (= :DENY (:result result)))
    (is (some #{:RISK_BUDGET_EXCEEDED} (:reason-codes result)))))
