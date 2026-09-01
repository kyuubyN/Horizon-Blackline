(ns horizon-blackline.web-research
  "Pulls current, openly-published web coverage for a symbol to complement the Alpaca/Benzinga
   news feed, whose coverage of non-mega-cap and non-US tickers can be weeks to months stale.

   This module does NOT judge anything. It fetches candidate documents and hands them, unchanged
   and clearly labelled as untrusted external text, to the same ProofRay -> strategy-LLM chain
   the broker news already flows through. ProofRay still does the claim verification and relevance
   ranking; the LLM still does the reasoning; every deterministic risk gate downstream is
   unchanged. If anything here fails or is disabled, callers get an empty vector and the pipeline
   behaves exactly as it did before web research existed."
  (:require [clojure.string :as str]
            [horizon-blackline.adapters.ddg :as ddg]))

;; Only read from recognised financial-news / market-data domains. Keeps SEO spam, forums and
;; content farms out of the evidence set, and bounds how much text the LLM has to sift.
(def ^:private source-allowlist
  #{"reuters.com" "apnews.com" "bloomberg.com" "cnbc.com" "wsj.com" "barrons.com"
    "marketwatch.com" "finance.yahoo.com" "yahoo.com" "investing.com" "seekingalpha.com"
    "fool.com" "benzinga.com" "stockanalysis.com" "morningstar.com" "forbes.com"
    "businesswire.com" "prnewswire.com" "globenewswire.com" "ft.com" "nasdaq.com"
    "investors.com" "tipranks.com" "marketbeat.com" "zacks.com" "marketscreener.com"
    "simplywall.st" "tradingview.com" "kiplinger.com" "thestreet.com" "247wallst.com"
    "finbold.com" "stocktitan.net" "streetinsider.com"})

;; Watchlist ticker -> the phrasing that gives DuckDuckGo the best shot at current coverage.
(def ^:private company-names
  {"AAPL" "Apple"
   "MSFT" "Microsoft"
   "NVDA" "Nvidia"
   "NU" "Nu Holdings Nubank"
   "BABA" "Alibaba"
   "VALE" "Vale"
   "PBR" "Petrobras"
   "ITUB" "Itau Unibanco"
   "BBD" "Banco Bradesco"
   "GOOGL" "Alphabet Google"
   "AMZN" "Amazon"
   "META" "Meta Platforms"
   "AMD" "AMD"})

(defn- host-of [url]
  (some-> (re-find #"^https?://([^/]+)/?" (str url)) second str/lower-case
          (str/replace #"^www\." "")))

(defn- allowed? [url]
  (let [h (host-of url)]
    (boolean (and h (some #(or (= h %) (str/ends-with? h (str "." %))) source-allowlist)))))

(defn- clip [s n]
  (let [s (str s)] (if (<= (count s) n) s (subs s 0 n))))

;; fetch_content sometimes returns a bot-wall / paywall interstitial instead of the article.
;; Those add no signal and can crowd out real evidence, so drop the body and keep the search
;; snippet alone when the text smells like one.
(def ^:private junk-body-markers
  ["pardon our interruption" "made us think you were a bot" "enable javascript"
   "verify you are human" "access to this page has been denied" "subscribe to continue"
   "subscribe to read" "create a free account to" "you have reached your article limit"
   "please enable cookies" "checking your browser before"])

(defn- usable-body? [body]
  (let [low (str/lower-case (str body))]
    (and (>= (count low) 200)
         (not-any? #(str/includes? low %) junk-body-markers))))

(defn fresh-evidence!
  "Returns a vector of {:text :url :retrieved-at} for `symbol`, newest-relevance first, capped at
   :max-docs (default 4). `now` is an Instant (or anything str-able) used only to stamp
   :retrieved-at. Never throws; returns [] when the sidecar is down, disabled, or dry."
  ([deps symbol now] (fresh-evidence! deps symbol now {}))
  ([deps symbol now {:keys [max-docs max-search] :or {max-docs 4 max-search 10}}]
   (try
     (let [connect! (or (:ddg-connect! deps) ddg/connect!)
           session (connect!)
           name (get company-names (str/upper-case (str symbol)) (str symbol))
           query (str name " stock news analyst rating price target")
           hits (->> (ddg/search! session query {:max-results max-search})
                     (filter (comp allowed? :url))
                     (distinct)
                     (take max-docs)
                     vec)]
       ;; The search snippet alone is already a current, decision-relevant sentence (the query
       ;; asks for news / ratings / price targets). fetch_content adds the article lead when the
       ;; page isn't paywalled or bot-walled -- best-effort, never required. Each doc is kept in
       ;; the same size class as a broker-news item, well under ProofRay's per-document cap.
       (->> hits
            (map (fn [{:keys [title url snippet]}]
                   (let [raw (ddg/fetch-content! session url {:max-length 4000})
                         body (when (usable-body? raw) raw)]
                     {:url url
                      :retrieved-at (str now)
                      :text (clip
                             (str/trim
                              (str "[external web page, retrieved " now
                                   "; publication date not verified -- weigh against the dated "
                                   "broker-feed items] " title ". " (clip snippet 400)
                                   (when body (str " -- " body))))
                             1800)})))
            (filter #(>= (count (:text %)) 60))
            vec))
     (catch Exception _ []))))
