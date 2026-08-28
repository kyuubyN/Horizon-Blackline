(ns horizon-blackline.api-test
  (:require [clojure.test :refer [deftest is]]
            [horizon-blackline.main :as main]
            [horizon-blackline.market :as market]
            [horizon-blackline.persistence.datomic :as store]
            [jsonista.core :as json])
  (:import (java.io ByteArrayInputStream)
           (java.nio.charset StandardCharsets)
           (java.util UUID)))

(def mapper (json/object-mapper {:decode-key-fn keyword}))

(defn request [method uri body]
  {:request-method method :uri uri :headers {}
   :body (ByteArrayInputStream. (.getBytes (or body "") StandardCharsets/UTF_8))})

(deftest api-exposes-health-demo-and-dispatch-confirmation-gates
  (let [system {:store (store/new-store {:storage-dir :mem
                                         :system (str "api-" (UUID/randomUUID))
                                         :db-name "blackline"})}
        app (main/make-app system)
        health (app (request :get "/health" nil))
        readiness (app (request :get "/ready" nil))
        demo (app (request :post "/v1/demo/run" nil))
        records (app (request :get "/v1/bdr" nil))
        metrics (app (request :get "/v1/metrics" nil))
        system-status (app (request :get "/v1/system" nil))
        malformed-intent (app (request :post "/v1/authorizations" "{\"intent\":{\"symbol\":\"AAPL\"}}"))
        malformed-evidence (app (request :post "/v1/bdr/does-not-matter/evidence" "{\"source-uri\":\"x\"}"))
        frozen (app (request :post "/v1/system:freeze" "{\"actor\":\"test-operator\",\"reason\":\"test\"}"))
        frozen-status (app (request :get "/v1/system" nil))
        dispatch (app (request :post "/v1/executions/does-not-matter/dispatch" "{}"))
        first-id (:bdr-id (first (store/list-records (:store system))))
        audit-export (app (request :get (str "/v1/bdr/" first-id "/export") nil))]
    (is (= 200 (:status health)))
    (is (contains? #{200 503} (:status readiness)))
    (is (= 201 (:status demo)))
    (is (= 2 (count (json/read-value (:body records) mapper))))
    (is (= 2 (:bdr-total (json/read-value (:body metrics) mapper))))
    (is (= "horizon-blackline/audit-export@1"
           (:format (json/read-value (:body audit-export) mapper))))
    (is (= false (:frozen? (json/read-value (:body system-status) mapper))))
    (is (= 200 (:status frozen)))
    (is (= true (:frozen? (json/read-value (:body frozen-status) mapper))))
    (is (= 422 (:status malformed-intent)))
    (is (= 422 (:status malformed-evidence)))
    (is (= 422 (:status dispatch)))))

(deftest json-intent-enums-are-normalized-at-the-api-boundary
  (is (= {:asset-class :stock :side :buy :order-type :limit}
         (main/normalize-intent {:asset-class "stock" :side "buy" :order-type "limit"}))))

(deftest json-evidence-enums-are-normalized-at-the-api-boundary
  (is (= {:source-type :fixture}
         (main/normalize-evidence {:source-type "fixture"}))))

(deftest observation-and-reevaluation-enums-normalize-at-the-api-boundary
  (is (= :REJECTED (:status (update {:status "REJECTED"} :status keyword))))
  (is (= :HOLD (:decision (update {:decision "HOLD"} :decision keyword)))))

(deftest quote-route-is-read-only-and-returns-temporal-evidence
  (let [system {:store (store/new-store {:storage-dir :mem
                                         :system (str "quote-" (UUID/randomUUID))
                                         :db-name "blackline"})}
        app (main/make-app system)
        response (with-redefs [market/latest-stock-quote!
                               (fn [symbol]
                                 {:symbol symbol
                                  :data {:AAPL {:bid_price "100"}}
                                  :evidence {:source-type :alpaca
                                             :content-hash "sha256:quote"
                                             :observed-at "2026-08-28T12:00:00Z"}})]
                   (app (request :get "/v1/market/quote/AAPL" nil)))]
    (is (= 200 (:status response)))
    (is (= "AAPL" (:symbol (json/read-value (:body response) mapper))))
    (is (= "alpaca" (get-in (json/read-value (:body response) mapper) [:evidence :source-type])))))

(deftest discovery-and-research-artifacts-append-to-a-bdr-before-challenge
  (let [system {:store (store/new-store {:storage-dir :mem
                                         :system (str "intel-api-" (UUID/randomUUID))
                                         :db-name "blackline"})}
        app (main/make-app system)
        run-id (str "intel-" (UUID/randomUUID))
        created (app (request :post "/v1/bdr" (json/write-value-as-string {:run-id run-id :correlation-id run-id :actor "research"})))
        bdr-id (:bdr-id (json/read-value (:body created) mapper))
        discovered (app (request :post (str "/v1/bdr/" bdr-id "/discovery")
                                 (json/write-value-as-string {:candidate-id "candidate-1" :symbol "AAPL" :source-hash "sha256:q" :observed-at "2026-08-28T12:00:00Z"})))
        researched (app (request :post (str "/v1/bdr/" bdr-id "/research")
                                (json/write-value-as-string {:thesis-id "thesis-1" :symbol "AAPL" :claims [] :limitations ["no forecast"]})))
        record (app (request :get (str "/v1/bdr/" bdr-id) nil))]
    (is (= 201 (:status discovered)))
    (is (= 201 (:status researched)))
    (is (= ["CANDIDATE_DISCOVERED" "THESIS_RESEARCHED"]
           (mapv :event-type (:events (json/read-value (:body record) mapper)))))))

(deftest desktop-decision-journey-creates-a-governed-allow-without-dispatch
  (let [system {:store (store/new-store {:storage-dir :mem
                                         :system (str "desktop-" (UUID/randomUUID))
                                         :db-name "blackline"})}
        app (main/make-app system)
        run-id (str "desktop-" (UUID/randomUUID))
        created (app (request :post "/v1/bdr" (json/write-value-as-string {:run-id run-id :correlation-id run-id :actor "desktop-operator"})))
        bdr-id (:bdr-id (json/read-value (:body created) mapper))
        now "2026-08-28T12:00:00Z"
        _evidence (app (request :post (str "/v1/bdr/" bdr-id "/evidence")
                                (json/write-value-as-string {:source-uri "fixture://desktop/AAPL" :source-type "fixture" :content-hash "sha256:desktop" :observed-at now :ingested-at now :valid-to "2026-08-28T12:05:00Z" :confidence 1.0})))
        _challenge (app (request :post (str "/v1/bdr/" bdr-id "/challenge")
                                 (json/write-value-as-string {:critics [{:critic-id "contrarian" :severity "low" :complete true} {:critic-id "evidence" :severity "none" :complete true} {:critic-id "risk" :severity "none" :complete true}]})))
        intent {:intent-id (str (UUID/randomUUID)) :bdr-id bdr-id :asset-class "stock" :symbol "AAPL" :side "buy" :order-type "limit" :quantity "1" :entry-price "100" :stop-price "95" :requested-risk-budget "100" :as-of now :evidence-refs []}
        authorization (app (request :post "/v1/authorizations"
                                    (json/write-value-as-string {:bdr-id bdr-id :intent intent :policy-bundle-id "desktop-policy@1" :ttl-seconds 60 :snapshot {:account-id "DESKTOP" :equity "10000" :buying-power "5000" :post-trade-symbol-weight "0.05" :post-trade-gross-exposure "0.15" :estimated-participation "0.01" :daily-drawdown "0.01" :as-of now :source-digest "fixture:desktop@1"} :policy {:limits {:remaining-risk-budget "100" :max-symbol-weight "0.10" :max-gross-exposure "0.20" :max-adv-participation "0.05" :hard-drawdown-limit "0.05"}} :evidence-valid? true :critics-complete? true :snapshot-valid? true :policy-active? true})))
        record (app (request :get (str "/v1/bdr/" bdr-id) nil))]
    (is (= 201 (:status created)))
    (is (= 201 (:status authorization)))
    (is (= "ALLOW" (:result (json/read-value (:body authorization) mapper))))
    (is (= "AUTHORIZED" (:state (json/read-value (:body record) mapper))))
    (is (= 3 (count (:events (json/read-value (:body record) mapper)))))))
