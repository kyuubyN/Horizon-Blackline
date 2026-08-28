(ns horizon-blackline.demo.core
  "Deterministic demo fixtures. They are explicitly synthetic and never claimed
   to be live Alpaca data or investment advice."
  (:require [horizon-blackline.capital.policy :as policy]
            [horizon-blackline.workflow.core :as workflow])
  (:import (java.util UUID)))

(def base-policy
  {:limits {:remaining-risk-budget "100"
            :max-symbol-weight "0.10"
            :max-gross-exposure "0.20"
            :max-adv-participation "0.05"
            :hard-drawdown-limit "0.05"}})

(defn- run-decision! [system scenario symbol-weight]
  (let [run-id (str "demo-" scenario "-" (UUID/randomUUID))
        record (workflow/create-bdr! system {:run-id run-id :correlation-id run-id :actor "demo-operator"})
        bdr-id (:bdr-id record)
        intent {:intent-id (str (UUID/randomUUID))
                :bdr-id bdr-id
                :asset-class :stock
                :symbol "AAPL"
                :side :buy
                :order-type :limit
                :quantity "10"
                :entry-price "100"
                :stop-price "95"
                :requested-risk-budget "100"
                :as-of "2026-08-28T12:00:00Z"
                :evidence-refs []}
        snapshot {:account-id "DEMO-PAPER"
                  :equity "10000"
                  :buying-power "5000"
                  :post-trade-symbol-weight symbol-weight
                  :post-trade-gross-exposure "0.15"
                  :estimated-participation "0.01"
                  :daily-drawdown "0.01"
                  :as-of "2026-08-28T12:00:00Z"
                  :source-digest "fixture:demo@1"}
        evaluation (policy/evaluate {:intent intent :snapshot snapshot :policy base-policy
                                     :evidence-valid? true :critics-complete? true
                                     :snapshot-valid? true :policy-active? true})]
    (workflow/append! system bdr-id {:event-type :EVIDENCE_CAPTURED
                                     :actor "evidence-service"
                                     :payload-schema "evidence_envelope@1"
                                     :payload {:source-uri "fixture://demo/aapl"
                                               :source-type "fixture"
                                               :content-hash "sha256:demo"
                                               :observed-at "2026-08-28T12:00:00Z"
                                               :valid-to "2026-08-28T12:05:00Z"}})
    (workflow/challenge! system bdr-id {:critics [{:critic-id "contrarian" :severity :low :complete true}
                                                  {:critic-id "evidence" :severity :none :complete true}
                                                  {:critic-id "risk" :severity :none :complete true}]})
    (let [authorization (workflow/authorization! system {:bdr-id bdr-id
                                                          :intent intent
                                                          :policy-bundle-id "demo-policy@1"
                                                          :ttl-seconds 60
                                                          :evaluation evaluation})]
      {:scenario scenario :bdr-id bdr-id :intent intent
       :evaluation evaluation :authorization authorization})))

(defn- complete-mock-lifecycle! [system authorized]
  (let [execution (workflow/prepare-execution!
                   system
                   {:authorization-id (get-in authorized [:authorization :authorization-id])
                    :intent (:intent authorized)
                    :idempotency-key (str "mock-" (:bdr-id authorized))
                    :paper? true})
        receipt {:environment :MOCK
                 :broker-order-id (str "mock-order-" (:execution-id execution))
                 :client-order-id (:client-order-id execution)
                 :status :NEW}
        submitted (workflow/submitted! system (:execution-id execution) receipt)
        filled (workflow/observe! system (:execution-id submitted)
                                  {:status :FILLED
                                   :receipt (assoc receipt :status :FILLED :filled-qty "10")})]
    (workflow/start-monitoring! system (:bdr-id filled))
    (workflow/reevaluate! system (:bdr-id filled)
                          {:decision :HOLD
                           :trigger "fixture:time-horizon"
                           :environment :MOCK})
    (workflow/close! system (:bdr-id filled) "fixture:demo-close")
    (workflow/post-mortem! system (:bdr-id filled)
                            {:environment :MOCK
                             :outcome "synthetic lifecycle completed"
                             :limitations ["No broker call was made" "Fixture is not market data"]})
    {:execution execution :bdr-id (:bdr-id filled)}))

(defn run-demo! [system]
  (let [denied (run-decision! system "concentration-denial" "0.11")
        authorized (run-decision! system "authorized-limit-order" "0.05")]
    {:environment :MOCK
     :warning "Synthetic deterministic demo; no broker call is made."
     :denied denied
     :authorized authorized
     :lifecycle (complete-mock-lifecycle! system authorized)}))
