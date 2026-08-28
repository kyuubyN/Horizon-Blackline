(ns horizon-blackline.datomic-test
  (:require [clojure.test :refer [deftest is]]
            [horizon-blackline.execution.dispatcher :as dispatcher]
            [horizon-blackline.bdr.core :as bdr]
            [horizon-blackline.persistence.datomic :as store]
            [horizon-blackline.workflow.core :as workflow])
  (:import (java.util UUID)))

(deftest bdr-events-are-persisted-as-immutable-facts
  (let [system {:store (store/new-store {:storage-dir :mem
                                         :system (str "test-" (UUID/randomUUID))
                                         :db-name "blackline"})}
        record (workflow/create-bdr! system {:run-id "run-1" :correlation-id "corr-1"})
        updated (workflow/append! system (:bdr-id record)
                                  {:event-type :INTENT_CREATED
                                   :actor "orchestrator"
                                   :payload-schema "trade_intent@1"
                                   :payload {:symbol "AAPL" :quantity "1"}})
        reloaded (store/get-record (:store system) (:bdr-id record))]
    (is (= 1 (count (:events updated))))
    (is (= (:events updated) (:events reloaded)))
    (is (= "AAPL" (get-in reloaded [:events 0 :payload :symbol])))
    (is (bdr/verify reloaded))
    (is (not (bdr/verify (assoc-in reloaded [:events 0 :payload :quantity] "999"))))
    (is (= [(:bdr-id record)] (mapv :bdr-id (store/list-records (:store system)))))))

(deftest authorization-requires-challenge-and-persists-its-decision
  (let [system {:store (store/new-store {:storage-dir :mem
                                         :system (str "auth-" (UUID/randomUUID))
                                         :db-name "blackline"})}
        record (workflow/create-bdr! system {:run-id "run-2" :correlation-id "corr-2"})
        bdr-id (:bdr-id record)
        _ (workflow/challenge! system bdr-id {:critics [{:id "contrarian" :complete true}
                                                        {:id "evidence" :complete true}
                                                        {:id "risk" :complete true}]})
        intent {:intent-id "intent-1" :bdr-id bdr-id :symbol "AAPL" :asset-class :stock}
        authorization (workflow/authorization! system
                                                {:bdr-id bdr-id
                                                 :intent intent
                                                 :policy-bundle-id "demo@1"
                                                 :evaluation {:result :ALLOW :reason-codes []}})
        stored (store/get-authorization (:store system) (:authorization-id authorization))
        state-after-authorization (:state (store/get-record (:store system) bdr-id))
        execution (workflow/prepare-execution! system {:authorization-id (:authorization-id authorization)
                                                        :intent intent
                                                        :idempotency-key "execution-1"
                                                        :paper? true})
        duplicate (workflow/prepare-execution! system {:authorization-id (:authorization-id authorization)
                                                        :intent intent
                                                        :idempotency-key "execution-1"
                                                        :paper? true})
        broker-called (atom false)
        dispatch-blocked (try
                           (dispatcher/dispatch! system (:execution-id execution)
                                                 {:mcp-url "http://example.invalid/mcp"
                                                  :paper-account-id nil
                                                  :initialize! (fn [_] {:session-id "test"})
                                                  :list-tools! (fn [_] [{:name "get_account_info"}
                                                                         {:name "place_stock_order"}])
                                                  :call-tool! (fn [& _] (reset! broker-called true))})
                           false
                           (catch clojure.lang.ExceptionInfo _ true))
        submitted (workflow/submitted! system (:execution-id execution) {:broker-order-id "paper-1"})
        observed (workflow/observe! system (:execution-id execution)
                                    {:status :FILLED :receipt {:broker-order-id "paper-1" :filled-qty "10"}})
        monitoring (workflow/start-monitoring! system bdr-id)
        held (workflow/reevaluate! system bdr-id {:decision :HOLD :trigger "time"})
        closed (workflow/reevaluate! system bdr-id {:decision :EXIT :trigger "thesis-invalidated"})
        post-mortem (workflow/post-mortem! system bdr-id {:outcome "fixture-filled"
                                                           :limitations ["synthetic broker receipt"]})]
    (is (= :ALLOW (:result authorization)))
    (is (= :AUTHORIZED state-after-authorization))
    (is (= (:input-hash authorization) (:input-hash stored)))
    (is (= [] (:reason-codes stored)))
    (is (= :SUBMISSION_PENDING (:status execution)))
    (is (= (:execution-id execution) (:execution-id duplicate)))
    (is dispatch-blocked)
    (is (not @broker-called))
    (is (= :SUBMITTED (:status submitted)))
    (is (= :FILLED (:status observed)))
    (is (= :MONITORING (:state monitoring)))
    (is (= :MONITORING (:state held)))
    (is (= :CLOSED (:state closed)))
    (is (= :POST_MORTEM_COMPLETE (:state post-mortem)))
    (is (= :POST_MORTEM_COMPLETE (:state (store/get-record (:store system) bdr-id))))))

(deftest dispatcher-submits-a-persisted-outbox-through-the-approved-tool-only
  (let [system {:store (store/new-store {:storage-dir :mem
                                         :system (str "dispatch-" (UUID/randomUUID))
                                         :db-name "blackline"})}
        record (workflow/create-bdr! system {:run-id "run-3" :correlation-id "corr-3"})
        bdr-id (:bdr-id record)
        _ (workflow/challenge! system bdr-id {:critics []})
        intent {:intent-id "intent-dispatch" :bdr-id bdr-id :asset-class :stock
                :symbol "AAPL" :side :buy :order-type :limit :quantity "2" :entry-price "100"}
        authorization (workflow/authorization! system {:bdr-id bdr-id :intent intent
                                                        :policy-bundle-id "demo@1"
                                                        :evaluation {:result :ALLOW :reason-codes []}})
        execution (workflow/prepare-execution! system {:authorization-id (:authorization-id authorization)
                                                        :intent intent :idempotency-key "dispatch-1" :paper? true})
        calls (atom [])
        submitted (dispatcher/dispatch! system (:execution-id execution)
                                        {:mcp-url "http://mcp.test/mcp"
                                         :paper-account-id "paper-account"
                                         :initialize! (fn [_] {:session-id "test"})
                                         :list-tools! (fn [_] [{:name "get_account_info"}
                                                                {:name "place_stock_order"}])
                                         :call-tool! (fn [_ tool args]
                                                       (swap! calls conj [tool args])
                                                       (if (= tool "get_account_info")
                                                         {:content [{:text "{\"id\":\"paper-account\"}"}]}
                                                         {:content [{:text "{\"id\":\"paper-order\"}"}]}))})]
    (is (= :SUBMITTED (:status submitted)))
    (is (= :SUBMITTED (:state (store/get-record (:store system) bdr-id))))
    (is (= ["get_account_info" "place_stock_order"] (mapv first @calls)))
    (is (= "dispatch-1" (get-in @calls [1 1 :client_order_id])))))

(deftest dispatcher-marks-timeout-unknown-instead-of-retrying
  (let [system {:store (store/new-store {:storage-dir :mem
                                         :system (str "unknown-" (UUID/randomUUID))
                                         :db-name "blackline"})}
        record (workflow/create-bdr! system {:run-id "run-4" :correlation-id "corr-4"})
        bdr-id (:bdr-id record)
        _ (workflow/challenge! system bdr-id {:critics []})
        intent {:intent-id "intent-unknown" :bdr-id bdr-id :asset-class :stock
                :symbol "AAPL" :side :buy :order-type :limit :quantity "2" :entry-price "100"}
        authorization (workflow/authorization! system {:bdr-id bdr-id :intent intent
                                                        :policy-bundle-id "demo@1"
                                                        :evaluation {:result :ALLOW :reason-codes []}})
        execution (workflow/prepare-execution! system {:authorization-id (:authorization-id authorization)
                                                        :intent intent :idempotency-key "unknown-1" :paper? true})
        result (dispatcher/dispatch! system (:execution-id execution)
                                    {:mcp-url "http://mcp.test/mcp"
                                     :paper-account-id "paper-account"
                                     :initialize! (fn [_] {:session-id "test"})
                                     :list-tools! (fn [_] [{:name "get_account_info"} {:name "place_stock_order"}])
                                     :call-tool! (fn [_ tool _]
                                                   (if (= tool "get_account_info")
                                                     {:content [{:text "{\"id\":\"paper-account\"}"}]}
                                                     (throw (ex-info "simulated timeout" {}))))})
        reconciled (workflow/reconcile! system (:execution-id execution) {:broker-order-id "recovered-paper-order"})]
    (is (= :UNKNOWN (:status result)))
    (is (= :SUBMITTED (:status reconciled)))
    (is (= :SUBMITTED (:state (store/get-record (:store system) bdr-id))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (dispatcher/dispatch! system (:execution-id execution)
                                       {:mcp-url "http://mcp.test/mcp" :paper-account-id "paper-account"
                                        :initialize! (fn [_] {:session-id "test"})
                                        :list-tools! (fn [_] []) :call-tool! (fn [& _] {})})))))

(deftest canceled-decisions-can-close-and-receive-post-mortem
  (let [system {:store (store/new-store {:storage-dir :mem
                                         :system (str "canceled-" (UUID/randomUUID))
                                         :db-name "blackline"})}
        record (workflow/create-bdr! system {:run-id "run-5" :correlation-id "corr-5"})
        bdr-id (:bdr-id record)
        _ (workflow/challenge! system bdr-id {:critics []})
        intent {:intent-id "intent-cancel" :bdr-id bdr-id :asset-class :stock
                :symbol "AAPL" :side :buy :order-type :limit :quantity "1" :entry-price "100"}
        authorization (workflow/authorization! system {:bdr-id bdr-id :intent intent
                                                        :policy-bundle-id "demo@1"
                                                        :evaluation {:result :ALLOW :reason-codes []}})
        execution (workflow/prepare-execution! system {:authorization-id (:authorization-id authorization)
                                                        :intent intent :idempotency-key "cancel-1" :paper? true})
        _ (workflow/submitted! system (:execution-id execution) {:broker-order-id "paper-cancel"})
        _ (workflow/observe! system (:execution-id execution) {:status :CANCELED :receipt {:broker-order-id "paper-cancel"}})
        closed (workflow/close! system bdr-id "canceled")
        post-mortem (workflow/post-mortem! system bdr-id {:outcome "canceled"})]
    (is (= :CLOSED (:state closed)))
    (is (= :POST_MORTEM_COMPLETE (:state post-mortem)))))
