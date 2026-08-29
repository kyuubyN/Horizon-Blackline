(ns horizon-blackline.campaign
  "Official Paper campaign controls. Disabled unless explicitly configured and
   bounded by a UTC scoring window."
  (:require [clojure.string :as str]
            [horizon-blackline.adapters.alpaca-mcp :as mcp]
            [horizon-blackline.bdr.core :as bdr]
            [horizon-blackline.canonical-json :as canonical]
            [horizon-blackline.persistence.datomic :as store])
  (:import (java.time Instant)))

(def campaign-id "alpaca-hackathon-official@1")

(defn- truthy? [value]
  (= "true" (some-> value str/lower-case)))

(defn- parse-instant [value]
  (when (seq value) (Instant/parse value)))

(defn config
  ([] (config #(System/getenv %)))
  ([getenv]
   (let [start (parse-instant (getenv "HORIZON_OFFICIAL_WINDOW_START"))
         end (parse-instant (getenv "HORIZON_OFFICIAL_WINDOW_END"))]
     {:enabled? (truthy? (getenv "HORIZON_OFFICIAL_CAMPAIGN_ENABLED"))
      :autonomy-enabled? (truthy? (getenv "HORIZON_AUTONOMY_ENABLED"))
      :paper? (= "true" (getenv "ALPACA_PAPER_TRADE"))
      :account-id (getenv "HORIZON_OFFICIAL_ACCOUNT_ID")
      :paper-account-id (getenv "ALPACA_PAPER_ACCOUNT_ID")
      :expected-starting-equity (or (getenv "HORIZON_OFFICIAL_STARTING_EQUITY") "100000")
      :starts-at start
      :ends-at end})))

(defn status
  ([campaign-config now]
   (let [{:keys [enabled? autonomy-enabled? account-id starts-at ends-at]} campaign-config
         window-valid? (and starts-at ends-at (.isBefore starts-at ends-at))
         active? (and enabled? window-valid? (not (.isBefore now starts-at)) (.isBefore now ends-at))]
     {:campaign-id campaign-id
      :enabled? enabled?
      :autonomy-enabled? autonomy-enabled?
      :account-configured? (boolean (seq account-id))
      :starts-at (some-> starts-at str)
      :ends-at (some-> ends-at str)
      :window-valid? window-valid?
      :window-active? active?}))
  ([] (status (config) (Instant/now))))

(defn- nested-value [value field]
  (cond
    (map? value) (or (get value field) (some #(nested-value % field) (vals value)))
    (sequential? value) (some #(nested-value % field) value)
    :else nil))

(defn account-snapshot [mcp-result now]
  (let [data (or (get-in mcp-result [:structuredContent :data]) mcp-result)
        account-id (some-> (nested-value data :id) str)
        equity (some-> (nested-value data :equity) str)]
    (when (or (str/blank? account-id) (str/blank? equity))
      (throw (ex-info "Account response lacks id or equity" {:reason-code :PAPER_ENV_REQUIRED})))
    {:account-id account-id
     :equity equity
     :captured-at (str now)
     :source-digest (str "sha256:" (bdr/sha256 (canonical/encode data)))}))

(defn read-account-snapshot!
  ([] (read-account-snapshot! {:mcp-url (System/getenv "ALPACA_MCP_URL")
                                :initialize! mcp/initialize!
                                :call-tool! mcp/call-tool!
                                :now #(Instant/now)}))
  ([{:keys [mcp-url initialize! call-tool! now]}]
   (when (str/blank? mcp-url)
     (throw (ex-info "MCP URL is not configured" {:reason-code :PAPER_ENV_REQUIRED})))
   (account-snapshot (call-tool! (initialize! mcp-url) "get_account_info" {}) (now))))

(defn- assert-campaign! [campaign-config now snapshot]
  (let [state (status campaign-config now)]
    (when-not (:enabled? state)
      (throw (ex-info "Official campaign is disabled" {:campaign state})))
    (when-not (:paper? campaign-config)
      (throw (ex-info "Official campaign requires paper trading" {:reason-code :PAPER_ENV_REQUIRED})))
    (when-not (= (:account-id campaign-config) (:paper-account-id campaign-config))
      (throw (ex-info "Official campaign account must match the Paper gateway allowlist"
                      {:reason-code :PAPER_ENV_REQUIRED})))
    (when-not (:window-active? state)
      (throw (ex-info "Official campaign window is not active" {:campaign state})))
    (when-not (= (:account-id campaign-config) (:account-id snapshot))
      (throw (ex-info "Account is not the configured official account" {:reason-code :PAPER_ENV_REQUIRED})))
    state))

(defn capture-baseline! [system campaign-config snapshot now]
  (assert-campaign! campaign-config now snapshot)
  ;; Idempotent on purpose: a baseline already captured is returned as-is, without re-asserting
  ;; starting equity, so a caller (e.g. a monitor script under Restart=on-failure) that always
  ;; re-asserts baseline on start never crash-loops on ordinary intraday equity drift. Callers can
  ;; check GET /v1/campaign/official first to see whether a baseline already exists.
  (or (store/get-campaign (:store system) campaign-id)
      (do
        (when-not (= (bigdec (:expected-starting-equity campaign-config)) (bigdec (:equity snapshot)))
          (throw (ex-info "Official campaign must begin at the configured starting equity"
                          {:expected (:expected-starting-equity campaign-config)
                           :actual (:equity snapshot)})))
        (store/create-campaign! (:store system)
                                {:campaign-id campaign-id
                                 :account-id (:account-id snapshot)
                                 :starts-at (str (:starts-at campaign-config))
                                 :ends-at (str (:ends-at campaign-config))
                                 :baseline-equity (:equity snapshot)
                                 :baseline-at (:captured-at snapshot)
                                 :autonomy-enabled? (:autonomy-enabled? campaign-config)}))))

(defn capture-snapshot! [system campaign-config snapshot now]
  (assert-campaign! campaign-config now snapshot)
  (when-not (store/get-campaign (:store system) campaign-id)
    (throw (ex-info "Official baseline has not been captured" {:campaign-id campaign-id})))
  (store/add-equity-snapshot! (:store system) campaign-id snapshot))

(defn autonomy-allowed? [system campaign-config now]
  (let [state (status campaign-config now)
        campaign (store/get-campaign (:store system) campaign-id)]
    (and (:enabled? state)
         (:autonomy-enabled? state)
         (:window-active? state)
         (not (store/frozen? (:store system)))
         campaign
         (= (:account-id campaign) (:account-id campaign-config)))))

(defn pnl-summary
  "Cheaper than (pnl (store/get-campaign ...)) for read-only callers that don't need every
   historical snapshot -- e.g. the /v1/campaign/official polling route."
  [system]
  (when-let [summary (store/campaign-summary (:store system) campaign-id)]
    {:baseline-equity (:baseline-equity summary)
     :latest-equity (:latest-equity summary)
     :pnl (str (- (bigdec (:latest-equity summary)) (bigdec (:baseline-equity summary))))
     :snapshot-count (:snapshot-count summary)}))

(defn pnl [campaign]
  (let [last-equity (or (:equity (last (:snapshots campaign))) (:baseline-equity campaign))]
    {:baseline-equity (:baseline-equity campaign)
     :latest-equity last-equity
     :pnl (str (- (bigdec last-equity) (bigdec (:baseline-equity campaign))))
     :snapshot-count (count (:snapshots campaign))}))
