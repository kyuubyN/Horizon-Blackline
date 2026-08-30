(ns horizon-blackline.orchestrator-test
  (:require [clojure.test :refer [deftest is]]
            [horizon-blackline.campaign :as campaign]
            [horizon-blackline.orchestrator :as orchestrator]
            [horizon-blackline.persistence.datomic :as store]
            [horizon-blackline.workflow.core :as workflow])
  (:import (java.time Instant)
           (java.util UUID)))

(def now (Instant/parse "2026-08-28T15:00:00Z"))

(defn- system [] {:store (store/new-store {:storage-dir :mem
                                           :system (str "orchestrator-" (UUID/randomUUID))
                                           :db-name "blackline"})})

(def cfg (assoc (orchestrator/config (constantly nil)) :watchlist ["AAPL"] :paper? true))

(def campaign-config
  {:enabled? true :autonomy-enabled? true :paper? true
   :account-id "official-paper" :paper-account-id "official-paper"
   :expected-starting-equity "100000"
   :starts-at (Instant/parse "2026-08-28T00:00:00Z")
   :ends-at (Instant/parse "2026-08-29T00:00:00Z")})

(defn- seed-campaign! [system]
  (store/create-campaign! (:store system)
                          {:campaign-id campaign/campaign-id
                           :account-id "official-paper"
                           :starts-at (str (:starts-at campaign-config))
                           :ends-at (str (:ends-at campaign-config))
                           :baseline-equity "100000"
                           :baseline-at (str now)
                           :autonomy-enabled? true}))

(defn- val-of [x] (if (instance? clojure.lang.IDeref x) @x x))

;; A bullish ("buy") thesis selects a call, a bearish ("sell") thesis a put -- see
;; orchestrator/select-option-contract!. The mock chain always returns exactly one contract of
;; whichever type was requested, at a fixed near-the-money strike, so tests can reason about the
;; resulting quantity/stop without needing real option pricing.
(defn- occ-symbol [underlying option-type]
  (str underlying "260918" (if (= option-type "call") "C" "P") "00100000"))

(defn- make-deps [{:keys [ap bp positions equity last-equity calls quote-error?
                          news direction confidence order-status option-ap option-bp]
                   :or {ap "100.00" bp "99.90" equity 100000 last-equity 100000
                        positions []
                        news [{:headline "AAPL rallies on strong iPhone demand"
                               :summary "Sales beat estimates." :source "test-wire"
                               :created_at "2026-08-28T12:00:00Z"}]
                        direction "buy" confidence 0.9 order-status "filled"
                        option-ap "6.00" option-bp "5.90"}}]
  {:mcp-url "http://mcp.test"
   :paper-account-id "official-paper"
   :market-open! (fn [_] true)
   :initialize! (fn [_] :session)
   :list-tools! (fn [_] [{:name "place_option_order"} {:name "get_account_info"}])
   :ask-proofray! (fn [_question _documents]
                    {:state "resolved" :sources [{:text "evidence" :source "doc:1" :relevance_score 0.9}]})
   :complete-llm! (fn [_request]
                    (str "{\"direction\":\"" direction "\",\"confidence\":" confidence
                         ",\"reasoning\":\"test thesis\",\"key_risks\":[]}"))
   :call-tool!
   (fn [_ tool arguments]
     (case tool
       "get_account_info"
       {:structuredContent {:data {:id "official-paper" :equity equity
                                   :buying_power equity :last_equity last-equity}}
        :content [{:type "text" :text "{\"id\":\"official-paper\"}"}]}
       "get_all_positions"
       {:structuredContent {:data {:result positions}}}
       "get_news"
       {:structuredContent {:data {:news news}}}
       "get_stock_latest_quote"
       (if (and quote-error? (= (:symbols arguments) "BAD"))
         (throw (ex-info "quote unavailable" {:symbol (:symbols arguments)}))
         {:structuredContent {:data {:quotes {(keyword (:symbols arguments)) {:ap (val-of ap) :bp (val-of bp)}}}}})
       "get_option_chain"
       (let [occ (occ-symbol (:underlying_symbol arguments) (:type arguments))]
         {:structuredContent {:data {:snapshots {(keyword occ) {:latestQuote {:ap (val-of option-ap) :bp (val-of option-bp)}}}}}})
       "get_option_latest_quote"
       {:structuredContent {:data {:quotes {(keyword (:symbols arguments)) {:ap (val-of option-ap) :bp (val-of option-bp)}}}}}
       "get_order_by_client_id"
       {:structuredContent {:data {:status (val-of order-status)}}}
       "place_option_order"
       (do (when calls (swap! calls conj arguments))
           {:content [{:type "text" :text "{\"status\":\"accepted\"}"}]})
       (throw (ex-info "unexpected tool" {:tool tool}))))})

(deftest frozen-system-short-circuits-both-ticks
  (let [system (system)
        deps (make-deps {:calls (atom [])})]
    (workflow/freeze! system "operator" "kill switch")
    (is (= {:frozen? true} (orchestrator/tick! system deps cfg campaign-config now)))
    (is (= {:frozen? true} (orchestrator/tick-monitoring! system deps now)))
    (is (empty? (store/list-records (:store system))))))

(deftest deny-path-never-reaches-dispatch
  (let [system (system)
        calls (atom [])
        deps (make-deps {:calls calls})
        tiny-budget-cfg (assoc cfg :risk-budget "1")
        result (orchestrator/tick! system deps tiny-budget-cfg campaign-config now)
        record (first (store/list-records (:store system)))]
    (is (= :DENY (:result (first (:results result)))))
    (is (= :DENIED (:state record)))
    (is (empty? @calls))))

(deftest autonomy-not-allowed-leaves-execution-pending-without-dispatch
  (let [system (system)
        calls (atom [])
        deps (make-deps {:calls calls})
        result (orchestrator/tick! system deps cfg campaign-config now)
        record (first (store/list-records (:store system)))]
    (is (= :ALLOW (:result (first (:results result)))))
    (is (= :SUBMISSION_PENDING (:state record)))
    (is (empty? @calls))))

(deftest autonomy-allowed-calls-dispatch-exactly-once
  (let [system (system)
        calls (atom [])
        deps (make-deps {:calls calls})]
    (seed-campaign! system)
    (let [result (orchestrator/tick! system deps cfg campaign-config now)
          record (first (store/list-records (:store system)))]
      (is (= :ALLOW (:result (first (:results result)))))
      (is (= :SUBMITTED (:state record)))
      (is (= 1 (count @calls))))))

(deftest market-closed-skips-the-tick-without-any-network-calls-or-bdrs
  (let [system (system)
        calls (atom [])
        deps (assoc (make-deps {:calls calls}) :market-open! (fn [_] false))
        result (orchestrator/tick! system deps cfg campaign-config now)]
    (is (= {:frozen? false :market-open? false} result))
    (is (empty? (store/list-records (:store system))))
    (is (empty? @calls))))

(deftest stop-breach-dispatches-a-real-governed-closing-order
  (let [system (system)
        calls (atom [])
        option-bp (atom "5.90")
        deps (make-deps {:calls calls :option-bp option-bp})]
    (seed-campaign! system)
    ;; Entry: authorized and dispatched (autonomy on via seed-campaign!).
    (orchestrator/tick! system deps cfg campaign-config now)
    (is (= 1 (count @calls)) "entry order must have been placed")
    ;; :SUBMITTED -> :FILLED
    (orchestrator/tick-monitoring! system deps cfg campaign-config now)
    (is (= :FILLED (:state (first (store/list-records (:store system))))))
    ;; :FILLED -> :MONITORING
    (orchestrator/tick-monitoring! system deps cfg campaign-config now)
    (is (= :MONITORING (:state (first (store/list-records (:store system))))))
    ;; Entry premium ~$6.00 with the default 50% option-stop-loss-pct -> stop ~$3.00. Bid now
    ;; well below that must trigger a real, separate, governed closing order, not just an
    ;; internal state flip.
    (reset! option-bp "2.50")
    (orchestrator/tick-monitoring! system deps cfg campaign-config now)
    (let [records (store/list-records (:store system))
          original (first (filter #(= :POST_MORTEM_COMPLETE (:state %)) records))
          closing-order (second @calls)]
      (is (= 2 (count @calls)) "the stop breach must place a real second broker order")
      (is (= "sell" (:side closing-order)) "closing a long option means selling to close, not buying again")
      (is (= 2 (count records)) "the exit is tracked as its own BDR, not folded into the original")
      (is (some? original)))))

(deftest one-symbol-exception-does-not-stop-others
  (let [system (system)
        calls (atom [])
        deps (make-deps {:calls calls :quote-error? true})
        cfg (assoc cfg :watchlist ["BAD" "AAPL"])
        result (orchestrator/tick! system deps cfg campaign-config now)
        by-symbol (into {} (map (juxt :symbol identity)) (:results result))]
    (is (some? (:error (get by-symbol "BAD"))))
    (is (= :ALLOW (:result (get by-symbol "AAPL"))))
    (is (= 1 (count (store/list-records (:store system)))))))

(deftest hold-direction-produces-no-bdr-and-no-dispatch-call
  (let [system (system)
        calls (atom [])
        deps (make-deps {:calls calls :direction "hold" :confidence 0.9})
        result (orchestrator/tick! system deps cfg campaign-config now)]
    (is (true? (:skipped? (first (:results result)))))
    (is (empty? (store/list-records (:store system))))
    (is (empty? @calls))))

(deftest low-confidence-produces-no-bdr-and-no-dispatch-call
  (let [system (system)
        calls (atom [])
        deps (make-deps {:calls calls :direction "buy" :confidence 0.1})
        result (orchestrator/tick! system deps cfg campaign-config now)]
    (is (true? (:skipped? (first (:results result)))))
    (is (empty? (store/list-records (:store system))))
    (is (empty? @calls))))

(deftest sell-direction-with-sufficient-confidence-buys-a-put-contract
  (let [system (system)
        calls (atom [])
        deps (make-deps {:calls calls :direction "sell" :confidence 0.9})]
    (seed-campaign! system)
    (let [result (orchestrator/tick! system deps cfg campaign-config now)
          record (first (store/list-records (:store system)))]
      (is (= :ALLOW (:result (first (:results result)))))
      (is (= "buy" (:side (first @calls)))
          "options are always bought long -- a bearish thesis is expressed via a put, not a sell")
      (is (re-find #"P00100000$" (:symbol (first @calls))) "a bearish thesis buys a put contract")
      (is (some? record)))))

(def base-config (assoc (orchestrator/config (constantly nil))
                        :order-notional-usd "1000" :option-stop-loss-pct "0.5"
                        :risk-budget "500" :min-confidence 0.6))

(def base-contract {:symbol "AAPL260918C00100000" :strike 100M
                    :ask 6.00M :bid 5.90M :spread-pct 0.0168M :distance 0M})

(deftest decide-intent-holds-produce-no-trade-intent
  (is (nil? (orchestrator/decide-intent {:direction "hold" :confidence 0.9} base-contract base-config now))))

(deftest decide-intent-low-confidence-produces-no-trade-intent
  (is (nil? (orchestrator/decide-intent {:direction "buy" :confidence 0.59} base-contract base-config now))))

(deftest decide-intent-nil-contract-produces-no-trade-intent
  (is (nil? (orchestrator/decide-intent {:direction "buy" :confidence 0.9} nil base-config now))))

(deftest decide-intent-buys-the-contract-with-a-stop-below-premium
  (let [intent (orchestrator/decide-intent {:direction "buy" :confidence 0.9} base-contract base-config now)]
    (is (= :option (:asset-class intent)))
    (is (= :buy (:side intent)))
    (is (= "AAPL260918C00100000" (:symbol intent)))
    (is (< (bigdec (:stop-price intent)) (bigdec (:entry-price intent))))))

(deftest decide-intent-zero-quantity-produces-no-trade-intent
  (let [tiny-budget (assoc base-config :order-notional-usd "1")]
    (is (nil? (orchestrator/decide-intent {:direction "buy" :confidence 0.9} base-contract tiny-budget now)))))
