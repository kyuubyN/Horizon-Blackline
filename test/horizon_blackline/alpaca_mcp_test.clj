(ns horizon-blackline.alpaca-mcp-test
  (:require [clojure.test :refer [deftest is]]
            [horizon-blackline.adapters.alpaca-mcp :as mcp]
            [horizon-blackline.execution.gateway :as gateway]
            [horizon-blackline.bdr.core :as bdr]
            [horizon-blackline.canonical-json :as canonical]))

(deftest gateway-only-allows-reviewed-execution-tools
  (let [requests (atom [])
        send! (fn [request]
                (swap! requests conj request)
                {:status 200
                 :headers {"mcp-session-id" "session-1"}
                 :body "{\"jsonrpc\":\"2.0\",\"result\":{\"serverInfo\":{\"name\":\"alpaca\"}}}"})
        session (mcp/initialize! send! "http://127.0.0.1:8001/mcp")]
    (is (= "session-1" (:session-id session)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (mcp/call-tool! send! session "cancel_all_orders" {})))
    (is (= 2 (count @requests)))))

(deftest embedded-mcp-tool-errors-do-not-become-broker-receipts
  (let [send! (fn [_] {:status 200
                        :headers {"mcp-session-id" "session-1"}
                        :body "{\"jsonrpc\":\"2.0\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"Error calling tool 'place_stock_order': rejected\"}]}}"})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (mcp/call-tool! send! {:base-url "http://mcp.test" :session-id "session-1"}
                                 "place_stock_order" {})))))

(deftest cancellation-is-an-explicitly-allowed-gateway-tool
  (let [send! (fn [_] {:status 200
                        :headers {"mcp-session-id" "session-1"}
                        :body "{\"jsonrpc\":\"2.0\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"status\\\":\\\"canceled\\\"}\"}]}}"})]
    (is (= [{:type "text" :text "{\"status\":\"canceled\"}"}]
           (:content (mcp/call-tool! send! {:base-url "http://mcp.test" :session-id "session-1"}
                                         "cancel_order_by_id" {:order_id "broker-1"}))))))

(deftest gateway-constructs-only-approved-alpaca-order-shapes
  (is (= {:symbol "AAPL" :side "buy" :qty "2" :type "limit"
          :client_order_id "intent-1" :limit_price "100" :time_in_force "day"}
         (gateway/order-arguments {:mcp-tool "place_stock_order"
                                   :client-order-id "intent-1"
                                   :intent {:symbol "AAPL" :side :buy :quantity "2"
                                            :order-type :limit :entry-price "100"}})))
  (is (= {:symbol "BTC/USD" :side "buy" :qty "0.01" :type "market"
          :client_order_id "crypto-1" :time_in_force "gtc"}
         (gateway/order-arguments {:mcp-tool "place_crypto_order"
                                   :client-order-id "crypto-1"
                                   :intent {:symbol "BTC/USD" :side :buy :quantity "0.01"
                                            :order-type :market}})))
  (is (= {:symbol "AAPL260116C00200000" :side "buy" :qty "1" :type "limit"
          :client_order_id "option-1" :limit_price "10" :time_in_force "day"}
         (gateway/order-arguments {:mcp-tool "place_option_order"
                                   :client-order-id "option-1"
                                   :intent {:symbol "AAPL260116C00200000" :side :buy :quantity "1"
                                            :order-type :limit :entry-price "10"}})))
  (is (= "100" (:limit_price
                  (gateway/order-arguments {:mcp-tool "place_stock_order"
                                            :client-order-id "persisted-1"
                                            :intent {:symbol "AAPL" :side "buy" :quantity "1"
                                                     :order-type "limit" :entry-price "100"}})))))

(deftest execution-gate-rejects-live-mismatch-and-expired-authorizations
  (let [intent {:intent-id "intent-1" :bdr-id "bdr-1" :asset-class :stock
                :symbol "AAPL" :side :buy :quantity "1" :order-type :market}
        valid-auth {:bdr-id "bdr-1" :result :ALLOW
                    :input-hash (bdr/sha256 (canonical/encode intent))
                    :expires-at "2099-01-01T00:00:00Z"}]
    (is (thrown? clojure.lang.ExceptionInfo
                 (gateway/preflight! {:paper? false :frozen? false :authorization valid-auth :intent intent})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (gateway/preflight! {:paper? true :frozen? false
                                      :authorization (assoc valid-auth :input-hash "bad") :intent intent})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (gateway/preflight! {:paper? true :frozen? false
                                      :authorization (assoc valid-auth :expires-at "2000-01-01T00:00:00Z") :intent intent})))))
