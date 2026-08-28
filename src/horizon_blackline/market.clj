(ns horizon-blackline.market
  "Read-only market data boundary. It converts reviewed MCP output into a
   temporal evidence envelope; it has no order or capital authority."
  (:require [clojure.string :as str]
            [horizon-blackline.adapters.alpaca-mcp :as mcp]
            [horizon-blackline.bdr.core :as bdr]
            [horizon-blackline.canonical-json :as canonical])
  (:import (java.time Instant Duration)))

(defn- valid-symbol? [symbol]
  (boolean (re-matches #"[A-Z0-9.\-/]{1,32}" symbol)))

(defn latest-stock-quote!
  ([symbol]
   (latest-stock-quote! {:mcp-url (System/getenv "ALPACA_MCP_URL")
                         :initialize! mcp/initialize!
                         :call-tool! mcp/call-tool!
                         :now #(Instant/now)}
                        symbol))
  ([{:keys [mcp-url initialize! call-tool! now]} symbol]
   (let [symbol (str/upper-case (str/trim symbol))]
     (when-not (valid-symbol? symbol)
       (throw (ex-info "Invalid stock symbol" {:symbol symbol})))
     (when (str/blank? mcp-url)
       (throw (ex-info "MCP URL is not configured" {:reason-code :PAPER_ENV_REQUIRED})))
     (let [observed-at (now)
           result (call-tool! (initialize! mcp-url)
                              "get_stock_latest_quote"
                              {:symbols symbol})
           data (or (get-in result [:structuredContent :data]) result)
           content-hash (str "sha256:" (bdr/sha256 (canonical/encode data)))]
       {:symbol symbol
        :data data
        :evidence {:source-uri (str "alpaca://stock/latest-quote/" symbol)
                   :source-type :alpaca
                   :content-hash content-hash
                   :observed-at (str observed-at)
                   :ingested-at (str observed-at)
                   :valid-to (str (.plus observed-at (Duration/ofMinutes 1)))
                   :confidence 1.0}}))))
