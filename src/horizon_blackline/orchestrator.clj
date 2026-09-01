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
            [horizon-blackline.web-research :as web-research]
            [horizon-blackline.workflow.core :as workflow])
  (:import (java.math RoundingMode)
           (java.time Instant Duration LocalDate ZoneOffset)
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
    :monitoring-poll-seconds (positive-long (getenv "HORIZON_MONITORING_POLL_SECONDS") 30)
    :risk-budget (or (getenv "HORIZON_RISK_BUDGET_USD") "500")
    :max-symbol-weight (or (getenv "HORIZON_MAX_SYMBOL_WEIGHT") "0.05")
    :max-gross-exposure (or (getenv "HORIZON_MAX_GROSS_EXPOSURE") "0.20")
    ;; Repurposed for the options-only strategy: this is now the max acceptable bid-ask
    ;; spread (as a fraction of mid) on the selected option contract, not ADV participation --
    ;; see select-option-contract!/build-risk-snapshot. 0.15 is permissive on purpose since
    ;; even liquid single-name option spreads commonly run wider than an equivalent stock's.
    :max-adv-participation (or (getenv "HORIZON_MAX_ADV_PARTICIPATION") "0.15")
    :hard-drawdown-limit (or (getenv "HORIZON_MAX_DRAWDOWN") "0.03")
    :order-notional-usd (or (getenv "HORIZON_ORDER_NOTIONAL_USD") "1000")
    :min-confidence (probability-double (getenv "HORIZON_MIN_CONFIDENCE") 0.6)
    ;; A tradeable thesis (buy/sell at or above :min-confidence) must recur for the SAME symbol
    ;; and direction this many consecutive discovery ticks before an order is proposed. The
    ;; per-tick LLM/ProofRay judgment is noisy -- the same symbol can swing a full 0.2 in
    ;; confidence between 5-minute ticks -- and a single spike should not open a position.
    ;; 1 = act on the first tradeable tick (old behaviour). Streaks reset on restart.
    :thesis-confirm-ticks (positive-long (getenv "HORIZON_THESIS_CONFIRM_TICKS") 2)
    ;; Every strategy here trades single-leg long options (buy calls for a bullish thesis, buy
    ;; puts for bearish) -- never naked/short options, so max loss is always bounded at the
    ;; premium paid. See select-option-contract!/decide-intent below.
    :option-stop-loss-pct (or (getenv "HORIZON_OPTION_STOP_LOSS_PCT") "0.5")
    ;; Close a monitored long option once its premium is up this fraction over entry (1.0 =
    ;; +100%, i.e. sell when the contract has doubled). The strategy never sells a winner
    ;; otherwise -- it would ride a gain straight back into the stop or into expiry.
    :option-take-profit-pct (or (getenv "HORIZON_OPTION_TAKE_PROFIT_PCT") "1.0")
    ;; Close any monitored option this many calendar days before its expiration, regardless of
    ;; P&L -- an option held into expiry decays to intrinsic value and then auto-exercises or
    ;; expires worthless, neither of which this system models.
    :option-expiry-exit-days (positive-long (getenv "HORIZON_OPTION_EXPIRY_EXIT_DAYS") 1)
    :option-expiration-min-days (positive-long (getenv "HORIZON_OPTION_EXPIRATION_MIN_DAYS") 14)
    :option-expiration-max-days (positive-long (getenv "HORIZON_OPTION_EXPIRATION_MAX_DAYS") 45)
    :option-strike-band-pct (or (getenv "HORIZON_OPTION_STRIKE_BAND_PCT") "0.05")
    ;; Broker-feed news older than this (days) is dropped before the LLM sees it rather than
    ;; presented as current -- Benzinga coverage of thinly-traded / non-US tickers can lag the
    ;; quote by months. 0 or unset disables the filter (previous behaviour).
    :news-max-age-days (positive-long (getenv "HORIZON_NEWS_MAX_AGE_DAYS") 21)
    ;; When true, each tick also pulls current open-web coverage (DuckDuckGo MCP sidecar) and
    ;; feeds it through the same ProofRay -> LLM chain as broker news. Off by default.
    :web-research-enabled? (truthy? (getenv "HORIZON_WEB_RESEARCH_ENABLED"))
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
   ;; Optional: only consulted when HORIZON_WEB_RESEARCH_ENABLED is true. Fails closed to [].
   :fetch-web! (fn [symbol now] (web-research/fresh-evidence! {} symbol now))
   :market-open! market/market-open?})

(defn- log! [& args] (apply println "[orchestrator]" args))

(defn- describe-exception [e]
  (str (.getSimpleName (class e)) (when-let [m (.getMessage e)] (str ": " m))))

;; Per-symbol consecutive-tradeable-tick counter for the confirmation gate. Process-local: a
;; restart clears it, so the system re-confirms before trading -- the conservative direction.
(defonce ^:private thesis-streaks (atom {}))

(defn advance-streak
  "Pure. Given the streak so far for one symbol and this tick's thesis, return the new streak
   {:direction dir :count n}. A tradeable thesis (buy/sell at/above min-confidence) that matches
   the running direction increments the count; a different direction restarts it at 1; a hold or
   sub-threshold thesis resets to {:direction nil :count 0}."
  [streak direction confidence min-confidence]
  (if (and (#{"buy" "sell"} direction) (number? confidence) (>= confidence min-confidence))
    (if (= direction (:direction streak))
      {:direction direction :count (inc (:count streak 0))}
      {:direction direction :count 1})
    {:direction nil :count 0}))

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

(defn build-risk-snapshot
  "Real fail-closed risk snapshot from live account/position data.
   :valid? is false whenever any real input could not be obtained; the caller
   must feed that into policy/evaluate as :snapshot-valid? so a missing input
   denies instead of silently defaulting.

   Every order here is a single-leg long option contract (100-share multiplier), so
   :quantity is a contract count and dollar exposure is quantity*entry-price*100 -- and the
   liquidity gate reuses the same :estimated-participation/:max-adv-participation fields as
   the (retired) stock path, but the number in them is now the contract's own bid-ask spread
   as a fraction of mid, not ADV participation. :spread-pct comes from the contract the caller
   already selected (select-option-contract! below) so this never re-fetches a quote."
  [account symbol side quantity entry-price spread-pct now]
  (let [multiplier 100M
        equity (:equity account)
        existing (get (:positions account) symbol 0M)
        notional (* (policy/decimal quantity) (policy/decimal entry-price) multiplier)
        signed-notional (if (= side :buy) notional (- notional))
        projected-symbol-value (+ existing signed-notional)
        other-gross (reduce + 0M (map (fn [[sym value]] (if (= sym symbol) 0M (abs value)))
                                       (:positions account)))
        gross (+ other-gross (abs projected-symbol-value))
        participation spread-pct
        last-equity (:last-equity account)
        drawdown (when (and last-equity (pos? last-equity))
                   (max 0M (safe-div (- last-equity equity) last-equity)))
        valid? (boolean (and equity (pos? equity) (:buying-power account) participation drawdown))]
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

(defn- parse-occ-symbol
  "Parses an OCC option symbol (e.g. \"AAPL260909C00220000\") into its root/expiration/type/
   strike. Format: root (1-6 letters) + YYMMDD + C|P + strike*1000 zero-padded to 8 digits."
  [occ]
  (when-let [[_ root date cp strike] (re-matches #"^([A-Z]{1,6})(\d{6})([CP])(\d{8})$" occ)]
    {:root root
     :expiration (str "20" (subs date 0 2) "-" (subs date 2 4) "-" (subs date 4 6))
     :type (if (= cp "C") :call :put)
     :strike (/ (bigdec (Long/parseLong strike)) 1000M)}))

(defn select-option-contract!
  "Picks the near-the-money contract (within config's strike band, 14-45 DTE by default) with
   a live two-sided quote and an acceptable bid-ask spread. Returns nil (no viable intent) when
   nothing in the chain clears the spread gate -- fail-closed, same as a missing stock quote
   used to short-circuit decide-intent."
  [deps now underlying option-type underlying-price config]
  (let [today (.toLocalDate (.atZone ^Instant now java.time.ZoneOffset/UTC))
        expiration-gte (str (.plusDays today (:option-expiration-min-days config)))
        expiration-lte (str (.plusDays today (:option-expiration-max-days config)))
        band (policy/decimal (:option-strike-band-pct config))
        strike-gte (double (* underlying-price (- 1M band)))
        strike-lte (double (* underlying-price (+ 1M band)))
        max-spread (policy/decimal (:max-adv-participation config))
        chain (market/option-chain! (mcp-deps deps now) underlying (name option-type)
                                     strike-gte strike-lte expiration-gte expiration-lte)
        snapshots (get-in chain [:data :snapshots])
        candidates (keep (fn [[occ-kw snap]]
                            (let [occ (name occ-kw)
                                  parsed (parse-occ-symbol occ)
                                  ask (some-> (get-in snap [:latestQuote :ap]) policy/decimal)
                                  bid (some-> (get-in snap [:latestQuote :bp]) policy/decimal)]
                              (when (and parsed ask bid (pos? ask) (pos? bid))
                                (let [mid (safe-div (+ ask bid) 2M 6 RoundingMode/HALF_UP)
                                      spread-pct (safe-div (- ask bid) mid 6 RoundingMode/HALF_UP)]
                                  {:symbol occ :strike (:strike parsed) :ask ask :bid bid
                                   :spread-pct spread-pct
                                   :distance (abs (- (:strike parsed) (policy/decimal underlying-price)))}))))
                          snapshots)
        within-spread (filter #(<= (:spread-pct %) max-spread) candidates)
        pick (->> within-spread (sort-by :distance) first)]
    (log! "option-diag" underlying (name option-type)
          "strike-window=" (str strike-gte ".." strike-lte)
          "exp-window=" (str expiration-gte ".." expiration-lte)
          "raw-snapshots=" (count snapshots)
          "priced-candidates=" (count candidates)
          "within-spread=" (count within-spread)
          "max-spread=" (str max-spread)
          "sample-spreads=" (pr-str (vec (take 5 (map (comp str :spread-pct) candidates)))))
    pick))

;; SWAP POINT (now wired to horizon-blackline.intelligence/research!): thesis :direction/
;; :confidence come from the LLM path; a nil/hold/low-confidence thesis still yields nil here,
;; same as the placeholder's "no viable intent" branch -- decide-intent itself stays the only
;; place a candidate TradeIntent map is produced, and it still has to clear every existing gate.
;;
;; Options-only by design (hackathon core requirement: "all strategies must incorporate options
;; trading"): a bullish thesis buys a call, a bearish thesis buys a put -- always :side :buy,
;; never a naked/short option, so max loss is always bounded at the premium paid. Contract
;; selection (select-option-contract!, an MCP call) happens in the caller, before this function,
;; so decide-intent itself stays a pure function of its arguments -- same contract this function
;; had for the stock path.
(defn decide-intent [thesis contract config now]
  (let [direction (:direction thesis)
        confidence (:confidence thesis)]
    (when (and contract (#{"buy" "sell"} direction)
               (number? confidence) (>= confidence (:min-confidence config)))
      (let [premium (:ask contract)
            notional (policy/decimal (:order-notional-usd config))
            quantity (long (safe-div notional (* premium 100M) 0 RoundingMode/DOWN))]
        (if (zero? quantity)
          (log! "no viable intent:" (:symbol contract)
                "premium" premium "x100 exceeds order notional budget" notional "-> 0 contracts")
          (let [stop-loss-pct (policy/decimal (:option-stop-loss-pct config))
                stop (.setScale (* premium (- 1M stop-loss-pct)) 2 RoundingMode/HALF_UP)]
            {:intent-id (str (UUID/randomUUID))
             :asset-class :option
             :symbol (:symbol contract)
             :side :buy
             :order-type :limit
             :quantity (str quantity)
             :entry-price (str premium)
             :stop-price (str (max stop 0.01M))
             :requested-risk-budget (:risk-budget config)
             :as-of (str now)
             :evidence-refs []}))))))

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

(defn- research-deps [deps config now]
  (cond-> {:fetch-news! (fn [symbol] (market/latest-news! (mcp-deps deps now) symbol))
           :ask-proofray! (:ask-proofray! deps)
           :complete-llm! (:complete-llm! deps)
           :now now
           :max-news-age-days (:news-max-age-days config)}
    (and (:web-research-enabled? config) (:fetch-web! deps))
    (assoc :fetch-web! (:fetch-web! deps))))

(def ^:private open-position-states
  "A BDR in one of these states is an in-flight or open position -- the orchestrator must not
   open a second one for the same symbol on the next tick. Terminal states (DENIED, CANCELED,
   REJECTED, CLOSED) and incomplete DRAFTs do not block re-entry."
  #{:SUBMISSION_PENDING :SUBMITTED :FILLED :MONITORING :UNKNOWN})

(defn- open-position-for-symbol? [system symbol]
  (let [target (str "orchestrator-" symbol)]
    (boolean (some #(= target (:correlation-id %))
                   (store/list-records-by-state (:store system) (vec open-position-states))))))

(defn tick-symbol! [system deps config campaign-config symbol now]
  (if (open-position-for-symbol? system symbol)
    (do (log! "position already open this symbol; skipping entry" symbol)
        {:symbol symbol :skipped? true :position-open? true})
   (let [quote (market/latest-stock-quote! (mcp-deps deps now) symbol)
        candidate (:candidate (intelligence/discover quote))
        underlying-price (get-in quote [:data :quotes (keyword symbol) :ap])
        augmented-candidate (assoc candidate :ask-price underlying-price)
        thesis (intelligence/research! (research-deps deps config now) augmented-candidate quote)
        account (account-snapshot! deps now)
        option-type (cond (= (:direction thesis) "buy") :call
                           (= (:direction thesis) "sell") :put
                           :else nil)
        contract (when (and option-type underlying-price (pos? (policy/decimal underlying-price)))
                   (select-option-contract! deps now symbol option-type
                                             (double (policy/decimal underlying-price)) config))
        draft-intent (decide-intent thesis contract config now)
        confirm-ticks (:thesis-confirm-ticks config 1)
        streak (get (swap! thesis-streaks update symbol advance-streak
                           (:direction thesis) (:confidence thesis) (:min-confidence config))
                    symbol)
        confirmed? (>= (:count streak 0) confirm-ticks)]
    (log! "tick-diag" symbol
          "direction=" (pr-str (:direction thesis))
          "confidence=" (pr-str (:confidence thesis))
          "min-conf=" (pr-str (:min-confidence config))
          "streak=" (str (:direction streak) "x" (:count streak 0) "/" confirm-ticks)
          "underlying-price=" (pr-str underlying-price)
          "contract=" (pr-str (some-> contract (select-keys [:symbol :ask :bid :spread-pct])))
          "reason=" (pr-str (:reasoning thesis)))
    (cond
      (not draft-intent)
      (do (log! "no viable intent this tick" symbol) {:symbol symbol :skipped? true})

      (not confirmed?)
      (do (log! "thesis awaiting confirmation" symbol (:direction streak)
                (str (:count streak 0) "/" confirm-ticks))
          {:symbol symbol :skipped? true :awaiting-confirmation? true})

      :else
      (let [record (workflow/create-bdr! system {:run-id (str "orchestrator-" symbol "-" (UUID/randomUUID))
                                                  :correlation-id (str "orchestrator-" symbol)
                                                  :actor "orchestrator"})
            bdr-id (:bdr-id record)
            intent (schema/assert-valid! schema/trade-intent (assoc draft-intent :bdr-id bdr-id))
            evidence (schema/assert-valid! schema/evidence-envelope (:evidence quote))
            bundle (policy-bundle config)
            {:keys [snapshot valid?]} (build-risk-snapshot account (:symbol intent) (:side intent)
                                                            (:quantity intent) (:entry-price intent)
                                                            (:spread-pct contract) now)
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
          {:symbol symbol :bdr-id bdr-id :result (:result evaluation)}))))))

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

(defn- exit-side [entry-side] (if (= entry-side :buy) :sell :buy))

(defn dispatch-exit!
  "Places a real, governed closing order when a monitored position's stop is breached. Without
   this, reevaluate! only updated the BDR's own bookkeeping to :EXIT/:CLOSED -- the real broker
   position stayed open indefinitely with nothing left watching it, since tick-monitoring! never
   revisits a CLOSED/POST_MORTEM_COMPLETE record. Runs through the same governed pipeline as an
   entry (evaluate -> authorize -> prepare -> dispatch-if-autonomy-allowed) rather than bypassing
   it -- an exit is still capital movement and still needs an audit trail."
  [system deps config campaign-config original-bdr-id intent exit-price now]
  ;; :intent read back from storage round-trips through JSON (decode-key-fn keyword decodes
  ;; keys, not values), so :side/:asset-class arrive as strings here, not keywords -- coerce
  ;; before comparing/validating, same class of bug as the HTTP-boundary one in policy.clj.
  (let [entry-side (let [v (:side intent)] (if (keyword? v) v (keyword (str v))))
        asset-class (let [v (:asset-class intent)] (if (keyword? v) v (keyword (str v))))
        side (exit-side entry-side)
        symbol (:symbol intent)
        record (workflow/create-bdr! system {:run-id (str "orchestrator-exit-" symbol "-" (UUID/randomUUID))
                                              :correlation-id (str "orchestrator-exit-" original-bdr-id)
                                              :actor "orchestrator"})
        bdr-id (:bdr-id record)
        exit-intent (schema/assert-valid!
                     schema/trade-intent
                     {:intent-id (str (UUID/randomUUID)) :bdr-id bdr-id
                      :asset-class asset-class :symbol symbol
                      :side side :order-type :market
                      :quantity (:quantity intent)
                      :entry-price (str exit-price) :stop-price (str exit-price)
                      :requested-risk-budget (:risk-budget config)
                      :as-of (str now) :evidence-refs []})
        account (account-snapshot! deps now)
        ;; Exits are never held for a wide spread: a breached stop is precisely when the
        ;; contract's relative spread tends to widen (premium has shrunk toward zero), and
        ;; blocking the closing order on liquidity would trap capital in the losing position
        ;; instead of de-risking it. The other gates (risk budget, concentration, gross
        ;; exposure, drawdown, frozen) still apply normally -- only the spread check is
        ;; bypassed here, with 0 (best case) standing in for "not applicable to a de-risking
        ;; trade".
        {:keys [snapshot valid?]} (build-risk-snapshot account symbol side
                                                        (:quantity exit-intent) (str exit-price)
                                                        0M now)
        bundle (policy-bundle config)
        evaluation (policy/evaluate {:intent exit-intent :snapshot snapshot :policy bundle
                                     :frozen? (store/frozen? (:store system))
                                     :evidence-valid? true :critics-complete? true
                                     :snapshot-valid? valid? :policy-active? true})]
    (workflow/append! system bdr-id
                      {:event-type :EVIDENCE_CAPTURED :actor "evidence-service"
                       :payload-schema "evidence_envelope@1"
                       :payload {:source-uri (str "alpaca://option/latest-quote/" symbol)
                                 :source-type :alpaca
                                 :content-hash (str "sha256:" (bdr/sha256 (canonical/encode {:price (str exit-price)})))
                                 :observed-at (str now) :ingested-at (str now)
                                 :valid-to (str (.plus now (Duration/ofMinutes 1)))
                                 :confidence 1.0}})
    (workflow/challenge! system bdr-id
                         {:critics [{:critic-id "risk-management-exit" :severity :none :complete true
                                     :note (str "deterministic stop-breach auto-exit for " original-bdr-id)}]})
    (let [authorization (workflow/authorization! system {:bdr-id bdr-id :intent exit-intent
                                                          :policy-bundle-id "orchestrator-exit-policy@1"
                                                          :ttl-seconds 120 :evaluation evaluation})]
      (if (= :ALLOW (:result evaluation))
        (let [execution (workflow/prepare-execution!
                         system {:authorization-id (:authorization-id authorization)
                                 :intent exit-intent
                                 :idempotency-key (str "orchestrator-exit-" bdr-id)
                                 :paper? (:paper? config)})]
          (if (campaign/autonomy-allowed? system campaign-config now)
            (do (log! "dispatching EXIT" symbol bdr-id)
                (dispatcher/dispatch! system (:execution-id execution)
                                      {:mcp-url (:mcp-url deps) :paper-account-id (:paper-account-id deps)
                                       :initialize! (:initialize! deps) :list-tools! (:list-tools! deps)
                                       :call-tool! (:call-tool! deps)})
                {:bdr-id bdr-id :dispatched? true})
            (do (log! "EXIT authorized but held (autonomy/campaign gate inactive):" symbol bdr-id)
                {:bdr-id bdr-id :dispatched? false})))
        (do (log! "EXIT not authorized:" symbol bdr-id (:result evaluation) (:reason-codes evaluation))
            {:bdr-id bdr-id :dispatched? false :denied? true})))))

(defn- days-to-expiry
  "Calendar days from `now` to the OCC symbol's expiration date, or nil for a non-option
   symbol (an older stock-era position that might still be monitored)."
  [symbol ^Instant now]
  (when-let [exp (:expiration (parse-occ-symbol (str symbol)))]
    (let [today (.toLocalDate (.atZone now ZoneOffset/UTC))]
      (- (.toEpochDay (LocalDate/parse exp)) (.toEpochDay today)))))

(defn position-exit-decision
  "Pure. Decides whether a monitored long-option position should be closed this tick. Returns
   [:EXIT reason-string] or [:HOLD nil]. Priority: expiry (fires regardless of price) > stop
   (cut losses) > take-profit (lock a gain). take-profit-pct / expiry-exit-days nil disables
   that rule; stop always applies."
  [{:keys [side entry-price stop price days-left take-profit-pct expiry-exit-days]}]
  (let [long? (not= side :sell)
        stopped? (when (and price stop) (if long? (<= price stop) (>= price stop)))
        took-profit? (when (and price entry-price take-profit-pct (pos? entry-price))
                       (if long?
                         (>= price (* entry-price (+ 1M take-profit-pct)))
                         (<= price (* entry-price (max 0M (- 1M take-profit-pct))))))
        expiring? (boolean (and (number? days-left) (number? expiry-exit-days)
                                (<= days-left expiry-exit-days)))]
    (cond
      expiring?     [:EXIT (str "expiry-in-" days-left "d")]
      stopped?      [:EXIT "stop-breach"]
      took-profit?  [:EXIT "take-profit"]
      :else         [:HOLD nil])))

(defn- reevaluate-position! [system deps config campaign-config record now]
  (if-let [execution (orchestrator-execution system (:bdr-id record))]
    (let [intent (:intent execution)
          side (if (keyword? (:side intent)) (:side intent) (keyword (str (:side intent))))
          quote (market/latest-option-quote! (mcp-deps deps now) (:symbol intent))
          quotes (get-in quote [:data :quotes (keyword (:symbol intent))])
          ask (some-> (:ap quotes) str bigdec)
          bid (some-> (:bp quotes) str bigdec)
          ;; Exiting a long means selling at the bid; exiting a short means buying back at the
          ;; ask -- checking bid for both (the original code) made the short-side stop never
          ;; trigger correctly. Every position here is a long option (see decide-intent), so
          ;; this always reduces to checking the bid, but keeps the short-side branch for
          ;; safety in case an older stock-era position is still being monitored.
          price (if (= side :buy) bid ask)
          stop (bigdec (:stop-price intent))
          [decision reason] (position-exit-decision
                             {:side side
                              :entry-price (some-> (:entry-price intent) str bigdec)
                              :stop stop
                              :price price
                              :days-left (days-to-expiry (:symbol intent) now)
                              :take-profit-pct (some-> (:option-take-profit-pct config) str bigdec)
                              :expiry-exit-days (:option-expiry-exit-days config)})]
      (workflow/reevaluate! system (:bdr-id record)
                            {:decision decision
                             :trigger (str "orchestrator:price=" price ":stop=" stop
                                           (when reason (str ":reason=" reason)))
                             :environment :PAPER})
      (when (= decision :EXIT)
        (let [exit-result (try
                            (dispatch-exit! system deps config campaign-config
                                            (:bdr-id record) intent price now)
                            (catch Exception e
                              (log! "EXIT dispatch failed:" (:bdr-id record) (describe-exception e))
                              {:error (describe-exception e)}))]
          (workflow/post-mortem! system (:bdr-id record)
                                 {:environment :PAPER
                                  :outcome (str "auto-exit (" reason "): price " price " stop " stop
                                                "; closing-bdr=" (:bdr-id exit-result)
                                                "; dispatched?=" (boolean (:dispatched? exit-result)))
                                  :limitations ["Deterministic rule-based exit; no discretionary judgment."
                                                "The closing order is tracked as its own BDR, not this one."]}))))
    (log! "no intent found to reevaluate" (:bdr-id record))))

(defn- monitor-record! [system deps config campaign-config record now]
  (case (:state record)
    :SUBMITTED (observe-submitted! system deps record)
    :UNKNOWN (reconcile-unknown! system deps record)
    :FILLED (workflow/start-monitoring! system (:bdr-id record))
    :MONITORING (reevaluate-position! system deps config campaign-config record now)
    :CANCELED (close-terminal! system record "broker:canceled")
    :REJECTED (close-terminal! system record "broker:rejected")
    nil))

(defn tick-monitoring!
  ([system now] (tick-monitoring! system (default-deps) (config) (campaign/config) now))
  ([system deps now] (tick-monitoring! system deps (config) (campaign/config) now))
  ([system deps config campaign-config now]
   (if (store/frozen? (:store system))
     (do (log! "system frozen; skipping monitoring tick") {:frozen? true})
     (let [records (store/list-records-by-state (:store system)
                                                 [:SUBMITTED :UNKNOWN :FILLED :MONITORING :CANCELED :REJECTED])]
       {:frozen? false
        :results (doall
                  (map (fn [record]
                         (try (monitor-record! system deps config campaign-config record now)
                              {:bdr-id (:bdr-id record) :state (:state record)}
                              (catch Exception e
                                (log! "monitor tick failed:" (:bdr-id record) (describe-exception e))
                                {:bdr-id (:bdr-id record) :error (describe-exception e)})))
                       records))}))))

(defn -main [& _]
  (let [system (workflow/new-system)
        cfg (config)
        deps (default-deps)]
    (log! "watchlist:" (:watchlist cfg) "poll-seconds:" (:poll-seconds cfg)
          "monitoring-poll-seconds:" (:monitoring-poll-seconds cfg))
    ;; Stop-loss monitoring dispatches real exit orders, so it must not be starved by
    ;; per-symbol LLM/ProofRay latency in the discovery tick -- runs on its own faster thread.
    (let [monitoring-thread (Thread.
                              (fn []
                                (while true
                                  (try
                                    (tick-monitoring! system deps cfg (campaign/config) (Instant/now))
                                    (catch Exception e
                                      (log! "monitoring tick cycle failed:" (describe-exception e))))
                                  (Thread/sleep (* 1000 (:monitoring-poll-seconds cfg))))))]
      (.setDaemon monitoring-thread true)
      (.setName monitoring-thread "orchestrator-monitoring-loop")
      (.start monitoring-thread))
    (while true
      (try
        (tick! system deps cfg (campaign/config) (Instant/now))
        (catch Exception e
          (log! "tick cycle failed:" (describe-exception e))))
      (Thread/sleep (* 1000 (:poll-seconds cfg))))))
