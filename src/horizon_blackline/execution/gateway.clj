(ns horizon-blackline.execution.gateway
  (:require [horizon-blackline.bdr.core :as bdr]
            [horizon-blackline.canonical-json :as canonical])
  (:import (java.time Instant)))

(defn preflight!
  "Pure gateway gate. The MCP transport adapter may run only after this returns.
   It intentionally accepts no agent identity or free-text instruction."
  [{:keys [paper? frozen? authorization intent]}]
  (let [intent-hash (bdr/sha256 (canonical/encode intent))]
    (cond
      (not paper?) (throw (ex-info "Paper environment is required" {:reason-code :PAPER_ENV_REQUIRED}))
      frozen? (throw (ex-info "System is frozen" {:reason-code :SYSTEM_FROZEN}))
      (not= :ALLOW (:result authorization)) (throw (ex-info "Authorization is not ALLOW" {:reason-code :AUTH_EXPIRED}))
      (not= (:bdr-id intent) (:bdr-id authorization)) (throw (ex-info "Intent belongs to another BDR" {:reason-code :INTENT_HASH_MISMATCH}))
      (not= intent-hash (:input-hash authorization)) (throw (ex-info "Intent differs from authorization" {:reason-code :INTENT_HASH_MISMATCH}))
      (not (.isAfter (Instant/parse (:expires-at authorization)) (Instant/now)))
      (throw (ex-info "Authorization expired" {:reason-code :AUTH_EXPIRED}))
      :else {:mcp-tool (case (:asset-class intent)
                         (:stock :etf) "place_stock_order"
                         :crypto "place_crypto_order"
                         :option "place_option_order")
             :client-order-id (:intent-id intent)
             :intent intent})))

(defn order-arguments
  "Translates a reviewed TradeIntent to the schema currently advertised by the
   Alpaca MCP. No agent-provided tool name or arbitrary argument survives this
   boundary."
  [{:keys [mcp-tool client-order-id intent]}]
  (let [{:keys [symbol quantity entry-price]} intent
        side (if (keyword? (:side intent)) (:side intent) (keyword (:side intent)))
        order-type (if (keyword? (:order-type intent)) (:order-type intent) (keyword (:order-type intent)))
        base {:symbol symbol
              :side (name side)
              :qty quantity
              :type (name order-type)
              :client_order_id client-order-id}
        with-limit (if (#{:limit :stop-limit} order-type)
                     (assoc base :limit_price entry-price)
                     base)]
    (case mcp-tool
      "place_stock_order" (assoc with-limit :time_in_force "day")
      "place_crypto_order" (assoc with-limit :time_in_force "gtc")
      "place_option_order" (assoc with-limit :time_in_force "day")
      (throw (ex-info "No approved MCP mapper for tool" {:tool mcp-tool})))))
