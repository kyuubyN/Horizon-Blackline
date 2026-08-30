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

(defn latest-option-quote!
  ([contract-symbol]
   (latest-option-quote! {:mcp-url (System/getenv "ALPACA_MCP_URL")
                          :initialize! mcp/initialize!
                          :call-tool! mcp/call-tool!
                          :now #(Instant/now)}
                         contract-symbol))
  ([{:keys [mcp-url initialize! call-tool! now]} contract-symbol]
   (let [contract-symbol (str/upper-case (str/trim contract-symbol))]
     (when (str/blank? contract-symbol)
       (throw (ex-info "Invalid option contract symbol" {:symbol contract-symbol})))
     (when (str/blank? mcp-url)
       (throw (ex-info "MCP URL is not configured" {:reason-code :PAPER_ENV_REQUIRED})))
     (let [observed-at (now)
           result (call-tool! (initialize! mcp-url)
                              "get_option_latest_quote"
                              {:symbols contract-symbol})
           data (or (get-in result [:structuredContent :data]) result)
           content-hash (str "sha256:" (bdr/sha256 (canonical/encode data)))]
       {:symbol contract-symbol
        :data data
        :evidence {:source-uri (str "alpaca://option/latest-quote/" contract-symbol)
                   :source-type :alpaca
                   :content-hash content-hash
                   :observed-at (str observed-at)
                   :ingested-at (str observed-at)
                   :valid-to (str (.plus observed-at (Duration/ofMinutes 1)))
                   :confidence 1.0}}))))

(defn option-chain!
  "Chain snapshot (quote per contract) for an underlying, narrowed by strike band and
   expiration window so decide-intent never has to page through the full chain."
  ([underlying option-type strike-gte strike-lte expiration-gte expiration-lte]
   (option-chain! {:mcp-url (System/getenv "ALPACA_MCP_URL")
                   :initialize! mcp/initialize!
                   :call-tool! mcp/call-tool!
                   :now #(Instant/now)}
                  underlying option-type strike-gte strike-lte expiration-gte expiration-lte))
  ([{:keys [mcp-url initialize! call-tool! now]} underlying option-type
    strike-gte strike-lte expiration-gte expiration-lte]
   (let [underlying (str/upper-case (str/trim underlying))]
     (when-not (valid-symbol? underlying)
       (throw (ex-info "Invalid underlying symbol" {:symbol underlying})))
     (when (str/blank? mcp-url)
       (throw (ex-info "MCP URL is not configured" {:reason-code :PAPER_ENV_REQUIRED})))
     (let [observed-at (now)
           result (call-tool! (initialize! mcp-url)
                              "get_option_chain"
                              {:underlying_symbol underlying :type option-type
                               :strike_price_gte strike-gte :strike_price_lte strike-lte
                               :expiration_date_gte expiration-gte :expiration_date_lte expiration-lte
                               :limit 100})
           data (or (get-in result [:structuredContent :data]) result)
           content-hash (str "sha256:" (bdr/sha256 (canonical/encode data)))]
       {:underlying underlying
        :data data
        :evidence {:source-uri (str "alpaca://option/chain/" underlying)
                   :source-type :alpaca
                   :content-hash content-hash
                   :observed-at (str observed-at)
                   :ingested-at (str observed-at)
                   :valid-to (str (.plus observed-at (Duration/ofMinutes 1)))
                   :confidence 1.0}}))))

(defn latest-news!
  "Recent news items for a symbol via the MCP get_news tool. The item text is external,
   untrusted content (the MCP sidecar itself tags it as such) -- callers must only ever treat it
   as data to analyze/summarize, never as instructions."
  ([symbol]
   (latest-news! {:mcp-url (System/getenv "ALPACA_MCP_URL")
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
                              "get_news"
                              {:symbols symbol :limit 10})
           data (or (get-in result [:structuredContent :data]) result)
           items (->> (:news data)
                      (map #(select-keys % [:headline :summary :source :author
                                            :created_at :updated_at :url :symbols])))
           content-hash (str "sha256:" (bdr/sha256 (canonical/encode items)))]
       {:symbol symbol
        :items items
        :evidence {:source-uri (str "alpaca://news/" symbol)
                   :source-type :news
                   :content-hash content-hash
                   :observed-at (str observed-at)
                   :ingested-at (str observed-at)
                   :valid-to (str (.plus observed-at (Duration/ofMinutes 15)))
                   :confidence 1.0}}))))

(defn market-open?
  "Alpaca's own market clock (holiday-aware) -- fail-closed: any error/unreachable MCP means
   'not open' rather than risking a tick against a stale/assumed schedule."
  ([] (market-open? {:mcp-url (System/getenv "ALPACA_MCP_URL")
                     :initialize! mcp/initialize!
                     :call-tool! mcp/call-tool!}))
  ([{:keys [mcp-url initialize! call-tool!]}]
   (try
     (let [result (call-tool! (initialize! mcp-url) "get_clock" {})
           data (or (get-in result [:structuredContent :data]) result)]
       (boolean (:is_open data)))
     (catch Exception _ false))))
