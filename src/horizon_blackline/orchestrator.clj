(ns horizon-blackline.orchestrator
  "Autonomous trading loop. Ticks the watchlist into governed BDR decisions and
   drives open positions through observe/reconcile/reevaluate using real
   broker state. dispatcher/dispatch! is only ever reached after
   campaign/autonomy-allowed? returns true for an already-authorized decision."
  (:require [clojure.string :as str]
            [horizon-blackline.adapters.alpaca-mcp :as mcp]
            [horizon-blackline.adapters.llm :as llm]
            [horizon-blackline.adapters.proofray :as proofray]
            [horizon-blackline.bdr.core :as bdr]
            [horizon-blackline.campaign :as campaign]
            [horizon-blackline.canonical-json :as canonical]
            [horizon-blackline.capital.policy :as policy]
            [horizon-blackline.execution.dispatcher :as dispatcher]
            [horizon-blackline.intelligence :as intelligence]
            [horizon-blackline.market :as market]
            [horizon-blackline.persistence.datomic :as store]
            [horizon-blackline.schema :as schema]
            [horizon-blackline.workflow.core :as workflow])
  (:import (java.math RoundingMode)
           (java.time Instant)
           (java.util UUID))
  (:gen-class))

(defn- truthy? [value] (= "true" (some-> value str/lower-case)))

(defn- positive-long [value default]
  (if (and value (re-matches #"[1-9][0-9]*" value)) (Long/parseLong value) default))

(defn- probability-double [value default]
  (try
    (if (str/blank? value)
      default
      (let [parsed (Double/parseDouble value)]
        (if (<= 0.0 parsed 1.0) parsed default)))
    (catch Exception _ default)))

(defn config
  ([] (config #(System/getenv %)))
  ([getenv]
   {:watchlist (->> (str/split (or (getenv "HORIZON_WATCHLIST") "") #",")
                    (map str/trim)
                    (remove str/blank?)
                    (map str/upper-case)
                    vec)
    :poll-seconds (positive-long (getenv "HORIZON_ORCHESTRATOR_POLL_SECONDS") 300)
    :risk-budget (or (getenv "HORIZON_RISK_BUDGET_USD") "500")
    :max-symbol-weight (or (getenv "HORIZON_MAX_SYMBOL_WEIGHT") "0.05")
    :max-gross-exposure (or (getenv "HORIZON_MAX_GROSS_EXPOSURE") "0.20")
    :max-adv-participation (or (getenv "HORIZON_MAX_ADV_PARTICIPATION") "0.01")
    :hard-drawdown-limit (or (getenv "HORIZON_MAX_DRAWDOWN") "0.03")
    :stop-distance-pct (or (getenv "HORIZON_STOP_DISTANCE_PCT") "0.02")
    :order-notional-usd (or (getenv "HORIZON_ORDER_NOTIONAL_USD") "1000")
    :min-confidence (probability-double (getenv "HORIZON_MIN_CONFIDENCE") 0.6)
    :paper? (truthy? (getenv "ALPACA_PAPER_TRADE"))}))

(defn policy-bundle [config]
  {:limits {:remaining-risk-budget (:risk-budget config)
            :max-symbol-weight (:max-symbol-weight config)
            :max-gross-exposure (:max-gross-exposure config)
            :max-adv-participation (:max-adv-participation config)
            :hard-drawdown-limit (:hard-drawdown-limit config)}})

(defn default-deps []
  {:mcp-url (or (System/getenv "ALPACA_MCP_URL") "http://127.0.0.1:8001/mcp")
   :paper-account-id (System/getenv "ALPACA_PAPER_ACCOUNT_ID")
   :initialize! mcp/initialize!
   :call-tool! mcp/call-tool!
   :list-tools! mcp/list-tools!
   :ask-proofray! (fn [question documents]
                    (proofray/ask! {:base-url (or (System/getenv "PROOFRAY_URL") "http://127.0.0.1:8420")
                                    :token (proofray/read-token!)}
                                   question documents))
   :complete-llm! llm/complete!
   :market-open! market/market-open?})

(defn- log! [& args] (apply println "[orchestrator]" args))

(defn- describe-exception [e]
  (str (.getSimpleName (class e)) (when-let [m (.getMessage e)] (str ": " m))))

(defn- mcp-deps [deps now] (assoc deps :now (constantly now)))

(defn- floor-positive [d] (if (pos? d) d 0.000001M))

(defn- safe-div
  ([a b] (safe-div a b 10 RoundingMode/HALF_UP))
  ([a b scale rounding] (.divide (bigdec a) (bigdec b) (int scale) rounding)))

(defn account-snapshot!
  [deps now]
  (let [session ((:initialize! deps) (:mcp-url deps))
        account (get-in ((:call-tool! deps) session "get_account_info" {}) [:structuredContent :data])
        positions (get-in ((:call-tool! deps) session "get_all_positions" {}) [:structuredContent :data :result])]
    {:account-id (str (:id account))
     :equity (policy/decimal (:equity account))
     :buying-power (policy/decimal (:buying_power account))
     :last-equity (some-> (:last_equity account) policy/decimal)
     :positions (reduce (fn [acc {:keys [symbol market_value]}]
                          (assoc acc symbol (policy/decimal (or market_value 0))))
                        {} positions)
     :captured-at (str now)}))

(defn- average-daily-volume! [deps symbol]
  (try
    (let [session ((:initialize! deps) (:mcp-url deps))
          result ((:call-tool! deps) session "get_stock_bars" {:symbols symbol :timeframe "1Day" :days 5 :feed "iex"})
          bars (get-in result [:structuredContent :data :bars (keyword symbol)])
          volumes (keep :v bars)]
      (when (seq volumes)
        (safe-div (reduce + 0M (map policy/decimal volumes)) (count volumes) 4 RoundingMode/HALF_UP)))
    (catch Exception _ nil)))

(defn build-risk-snapshot
  "Real fail-closed risk snapshot from live account/position/volume data.
   :valid? is false whenever any real input could not be obtained; the caller
   must feed that into policy/evaluate as :snapshot-valid? so a missing input
   denies instead of silently defaulting."
  [deps account symbol side quantity entry-price now]
  (let [equity (:equity account)
        existing (get (:positions account) symbol 0M)
        notional (* (policy/decimal quantity) (policy/decimal entry-price))
        signed-notional (if (= side :buy) notional (- notional))
        projected-symbol-value (+ existing signed-notional)
        other-gross (reduce + 0M (map (fn [[sym value]] (if (= sym symbol) 0M (abs value)))
                                       (:positions account)))
        gross (+ other-gross (abs projected-symbol-value))
        adv (average-daily-volume! deps symbol)
        participation (when (and adv (pos? adv)) (safe-div (policy/decimal quantity) adv))
        last-equity (:last-equity account)
        drawdown (when (and last-equity (pos? last-equity))
                   (max 0M (safe-div (- last-equity equity) last-equity)))
        valid? (boolean (and equity (pos? equity) (:buying-power account) adv participation drawdown))]
    {:snapshot {:account-id (:account-id account)
                :equity (str equity)
                :buying-power (str (:buying-power account))
                :post-trade-symbol-weight (str (floor-positive (if (and equity (pos? equity)) (safe-div (abs projected-symbol-value) equity) 0M)))
                :post-trade-gross-exposure (str (floor-positive (if (and equity (pos? equity)) (safe-div gross equity) 0M)))
                :estimated-participation (str (floor-positive (or participation 0M)))
                :daily-drawdown (str (floor-positive (or drawdown 0M)))
                :as-of (str now)
                :source-digest (str "sha256:" (bdr/sha256 (canonical/encode account)))}
     :valid? valid?}))

;; SWAP POINT (now wired to horizon-blackline.intelligence/research!): thesis :direction/
;; :confidence come from the LLM path; a nil/hold/low-confidence thesis still yields nil here,
;; same as the placeholder's "no viable intent" branch -- decide-intent itself stays the only
;; place a candidate TradeIntent map is produced, and it still has to clear every existing gate.
(defn decide-intent [candidate thesis account-snapshot config now]
  (let [direction (:direction thesis)
        confidence (:confidence thesis)
        side (cond (= direction "buy") :buy (= direction "sell") :sell :else nil)
        entry (some-> (:ask-price candidate) str policy/decimal)
        notional (policy/decimal (:order-notional-usd config))
        quantity (when (and entry (pos? entry))
                   (safe-div notional entry 0 RoundingMode/DOWN))
        stop-distance-pct (policy/decimal (:stop-distance-pct config))
        stop (when (and entry (pos? entry) side)
               (.setScale (if (= side :buy)
                            (- entry (* entry stop-distance-pct))
                            (+ entry (* entry stop-distance-pct)))
                          2 RoundingMode/HALF_UP))]
    (when (and side
               (number? confidence) (>= confidence (:min-confidence config))
               quantity (pos? quantity) stop (pos? stop))
      {:intent-id (str (UUID/randomUUID))
       :asset-class :stock
       :symbol (:symbol candidate)
       :side side
       :order-type :limit
       :quantity (str quantity)
       :entry-price (str entry)
       :stop-price (str stop)
       :requested-risk-budget (:risk-budget config)
       :as-of (str now)
       :evidence-refs []})))

(defn- evidence-freshness-critic [evidence now]
  (let [valid-to (Instant/parse (:valid-to evidence))
        stale? (not (.isBefore now valid-to))]
    {:critic-id "evidence-freshness" :severity (if stale? :high :none) :complete true}))

(defn- concentration-awareness-critic [snapshot policy-bundle]
  (let [weight (bigdec (:post-trade-symbol-weight snapshot))
        limit (bigdec (get-in policy-bundle [:limits :max-symbol-weight]))
        ratio (if (pos? limit) (safe-div weight limit) 1M)]
    {:critic-id "concentration-awareness"
     :severity (cond (>= ratio 1M) :high (>= ratio 0.8M) :medium :else :none)
     :complete true}))

(defn- risk-budget-critic [intent policy-bundle]
  (let [loss (policy/calculate-loss-at-stop intent)
        budget (bigdec (get-in policy-bundle [:limits :remaining-risk-budget]))
        ratio (if (pos? budget) (safe-div loss budget) 1M)]
    {:critic-id "risk-budget"
     :severity (cond (>= ratio 1M) :high (>= ratio 0.8M) :medium :else :none)
     :complete true}))

(defn- research-deps [deps now]
  {:fetch-news! (fn [symbol] (market/latest-news! (mcp-deps deps now) symbol))
   :ask-proofray! (:ask-proofray! deps)
   :complete-llm! (:complete-llm! deps)})

(defn tick-symbol! [system deps config campaign-config symbol now]
  (let [quote (market/latest-stock-quote! (mcp-deps deps now) symbol)
        candidate (:candidate (intelligence/discover quote))
        augmented-candidate (assoc candidate :ask-price
                                    (get-in quote [:data :quotes (keyword symbol) :ap]))
        thesis (intelligence/research! (research-deps deps now) augmented-candidate quote)
        account (account-snapshot! deps now)
        draft-intent (decide-intent augmented-candidate thesis account config now)]
    (if-not draft-intent
      (do (log! "no viable intent this tick" symbol) {:symbol symbol :skipped? true})
      (let [record (workflow/create-bdr! system {:run-id (str "orchestrator-" symbol "-" (UUID/randomUUID))
                                                  :correlation-id (str "orchestrator-" symbol)
                                                  :actor "orchestrator"})
            bdr-id (:bdr-id record)
            intent (schema/assert-valid! schema/trade-intent (assoc draft-intent :bdr-id bdr-id))
            evidence (schema/assert-valid! schema/evidence-envelope (:evidence quote))
            bundle (policy-bundle config)
            {:keys [snapshot valid?]} (build-risk-snapshot deps account symbol (:side intent)
                                                            (:quantity intent) (:entry-price intent) now)
            evidence-critic (evidence-freshness-critic evidence now)
            concentration-critic (concentration-awareness-critic snapshot bundle)
            risk-critic (risk-budget-critic intent bundle)
            critics [evidence-critic concentration-critic risk-critic]
            evaluation (policy/evaluate {:intent intent :snapshot snapshot :policy bundle
                                         :frozen? (store/frozen? (:store system))
                                         :evidence-valid? (= :none (:severity evidence-critic))
                                         :critics-complete? (every? :complete critics)
                                         :snapshot-valid? valid?
                                         :policy-active? true})]
        (workflow/append! system bdr-id {:event-type :EVIDENCE_CAPTURED :actor "evidence-service"
                                         :payload-schema "evidence_envelope@1" :payload evidence})
        (workflow/append! system bdr-id {:event-type :CANDIDATE_DISCOVERED :actor "discovery"
                                         :payload-schema "candidate_set@1" :payload augmented-candidate})
        (workflow/append! system bdr-id {:event-type :THESIS_RESEARCHED :actor "research"
                                         :payload-schema "thesis@1" :payload thesis})
        (workflow/challenge! system bdr-id {:critics critics})
        (let [authorization (workflow/authorization! system {:bdr-id bdr-id :intent intent
                                                              :policy-bundle-id "orchestrator-policy@1"
                                                              :ttl-seconds 120 :evaluation evaluation})]
          (if (= :ALLOW (:result evaluation))
            (let [execution (workflow/prepare-execution!
                             system {:authorization-id (:authorization-id authorization)
                                     :intent intent
                                     :idempotency-key (str "orchestrator-" bdr-id)
                                     :paper? (:paper? config)})]
              (if (campaign/autonomy-allowed? system campaign-config now)
                (do (log! "dispatching" symbol bdr-id)
                    (dispatcher/dispatch! system (:execution-id execution)
                                          {:mcp-url (:mcp-url deps)
                                           :paper-account-id (:paper-account-id deps)
                                           :initialize! (:initialize! deps)
                                           :list-tools! (:list-tools! deps)
                                           :call-tool! (:call-tool! deps)}))
                (log! "authorized but held (autonomy/campaign gate inactive):" symbol bdr-id)))
            (log! "not authorized:" symbol bdr-id (:result evaluation) (:reason-codes evaluation)))
          {:symbol symbol :bdr-id bdr-id :result (:result evaluation)})))))

(defn tick!
  ([system config now] (tick! system (default-deps) config (campaign/config) now))
  ([system deps config campaign-config now]
   (cond
     (store/frozen? (:store system))
     (do (log! "system frozen; skipping trading tick") {:frozen? true})

     (not ((:market-open! deps) deps))
     (do (log! "market closed; skipping trading tick") {:frozen? false :market-open? false})

     :else
     {:frozen? false
      :market-open? true
      :results (doall
                (map (fn [symbol]
                       (try (tick-symbol! system deps config campaign-config symbol now)
                            (catch Exception e
                              (log! "symbol tick failed:" symbol (describe-exception e))
                              {:symbol symbol :error (describe-exception e)})))
                     (:watchlist config)))})))

(def broker-status->workflow-state
  {"filled" :FILLED "partially_filled" :PARTIALLY_FILLED
   "canceled" :CANCELED "rejected" :REJECTED})

(defn- broker-order! [deps client-order-id]
  (let [session ((:initialize! deps) (:mcp-url deps))
        result ((:call-tool! deps) session "get_order_by_client_id" {:client_order_id client-order-id})]
    (or (get-in result [:structuredContent :data]) result)))

(defn- orchestrator-execution [system bdr-id]
  (store/get-execution-by-key (:store system) (str "orchestrator-" bdr-id)))

(defn- observe-submitted! [system deps record]
  (if-let [execution (orchestrator-execution system (:bdr-id record))]
    (let [data (broker-order! deps (:client-order-id execution))
          status (some-> (:status data) name str/lower-case)
          mapped (get broker-status->workflow-state status)]
      (if mapped
        (workflow/observe! system (:execution-id execution) {:status mapped :receipt data})
        (log! "order still open for" (:bdr-id record) "status" status)))
    (log! "no orchestrator execution found for" (:bdr-id record))))

(defn- reconcile-unknown! [system deps record]
  (when-let [execution (orchestrator-execution system (:bdr-id record))]
    (workflow/reconcile! system (:execution-id execution) (broker-order! deps (:client-order-id execution)))))

(defn- close-terminal! [system record reason]
  (workflow/close! system (:bdr-id record) reason)
  (workflow/post-mortem! system (:bdr-id record)
                         {:environment :PAPER :outcome reason
                          :limitations ["Terminal broker outcome observed by the orchestrator."]}))

(defn- reevaluate-position! [system deps record now]
  (if-let [execution (orchestrator-execution system (:bdr-id record))]
    (let [intent (:intent execution)
          quote (market/latest-stock-quote! (mcp-deps deps now) (:symbol intent))
          price (some-> (get-in quote [:data :quotes (keyword (:symbol intent)) :bp]) str bigdec)
          stop (bigdec (:stop-price intent))
          decision (if (and price (<= price stop)) :EXIT :HOLD)]
      (workflow/reevaluate! system (:bdr-id record)
                            {:decision decision
                             :trigger (str "orchestrator:price=" price ":stop=" stop)
                             :environment :PAPER})
      (when (= decision :EXIT)
        (workflow/post-mortem! system (:bdr-id record)
                               {:environment :PAPER
                                :outcome (str "auto-exit: price " price " breached stop " stop)
                                :limitations ["Deterministic stop-based exit; no discretionary judgment."]})))
    (log! "no intent found to reevaluate" (:bdr-id record))))

(defn- monitor-record! [system deps record now]
  (case (:state record)
    :SUBMITTED (observe-submitted! system deps record)
    :UNKNOWN (reconcile-unknown! system deps record)
    :FILLED (workflow/start-monitoring! system (:bdr-id record))
    :MONITORING (reevaluate-position! system deps record now)
    :CANCELED (close-terminal! system record "broker:canceled")
    :REJECTED (close-terminal! system record "broker:rejected")
    nil))

(defn tick-monitoring!
  ([system now] (tick-monitoring! system (default-deps) now))
  ([system deps now]
   (if (store/frozen? (:store system))
     (do (log! "system frozen; skipping monitoring tick") {:frozen? true})
     (let [records (filter (comp #{:SUBMITTED :UNKNOWN :FILLED :MONITORING :CANCELED :REJECTED} :state)
                            (store/list-records (:store system)))]
       {:frozen? false
        :results (doall
                  (map (fn [record]
                         (try (monitor-record! system deps record now)
                              {:bdr-id (:bdr-id record) :state (:state record)}
                              (catch Exception e
                                (log! "monitor tick failed:" (:bdr-id record) (describe-exception e))
                                {:bdr-id (:bdr-id record) :error (describe-exception e)})))
                       records))}))))

(defn -main [& _]
  (let [system (workflow/new-system)
        cfg (config)
        deps (default-deps)]
    (log! "watchlist:" (:watchlist cfg) "poll-seconds:" (:poll-seconds cfg))
    (while true
      (try
        (tick! system deps cfg (campaign/config) (Instant/now))
        (tick-monitoring! system deps (Instant/now))
        (catch Exception e
          (log! "tick cycle failed:" (describe-exception e))))
      (Thread/sleep (* 1000 (:poll-seconds cfg))))))
