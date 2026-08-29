(ns horizon-blackline.capital.policy
  (:import (java.math RoundingMode)))

(def reason-codes
  #{:PAPER_ENV_REQUIRED :EVIDENCE_INVALID :CRITIC_INCOMPLETE
    :RISK_BUDGET_EXCEEDED :EXPOSURE_LIMIT :LIQUIDITY_LIMIT
    :DRAWDOWN_FREEZE :CONCENTRATION_LIMIT :AUTH_EXPIRED
    :INTENT_HASH_MISMATCH :BROKER_STATE_UNKNOWN :SYSTEM_FROZEN})

(defn decimal [value]
  (bigdec (str value)))

(defn positive-decimal? [value]
  (and (string? value) (pos? (decimal value))))

(defn calculate-loss-at-stop [{:keys [side quantity entry-price stop-price]}]
  (let [side-kw (if (keyword? side) side (keyword (str side)))
        q (decimal quantity)
        entry (decimal entry-price)
        stop (decimal stop-price)
        distance (if (= side-kw :buy) (- entry stop) (- stop entry))]
    (.multiply q (max 0M distance))))

(defn evaluate
  "Avalia somente dados tipados. O resultado é fail-closed: qualquer entrada
   ausente, congelamento, excedente ou evidência inválida é uma negação."
  [{:keys [intent snapshot policy frozen? evidence-valid? critics-complete?
           snapshot-valid? policy-active?]}]
  (let [{:keys [quantity entry-price stop-price symbol]} intent
        limits (:limits policy)
        loss (when (every? positive-decimal? [quantity entry-price stop-price])
               (calculate-loss-at-stop intent))
        risk-budget (some-> limits :remaining-risk-budget decimal)
        next-symbol-weight (some-> snapshot :post-trade-symbol-weight decimal)
        max-symbol-weight (some-> limits :max-symbol-weight decimal)
        next-gross (some-> snapshot :post-trade-gross-exposure decimal)
        max-gross (some-> limits :max-gross-exposure decimal)
        participation (some-> snapshot :estimated-participation decimal)
        max-participation (some-> limits :max-adv-participation decimal)
        drawdown (some-> snapshot :daily-drawdown decimal)
        hard-drawdown (some-> limits :hard-drawdown-limit decimal)
        codes (cond-> []
                frozen? (conj :SYSTEM_FROZEN)
                (not snapshot-valid?) (conj :EVIDENCE_INVALID)
                (not policy-active?) (conj :AUTH_EXPIRED)
                (not evidence-valid?) (conj :EVIDENCE_INVALID)
                (not critics-complete?) (conj :CRITIC_INCOMPLETE)
                (nil? loss) (conj :RISK_BUDGET_EXCEEDED)
                (and loss risk-budget (> loss risk-budget)) (conj :RISK_BUDGET_EXCEEDED)
                (and next-symbol-weight max-symbol-weight (> next-symbol-weight max-symbol-weight)) (conj :CONCENTRATION_LIMIT)
                (and next-gross max-gross (> next-gross max-gross)) (conj :EXPOSURE_LIMIT)
                (or (nil? participation) (nil? max-participation)
                    (> participation max-participation)) (conj :LIQUIDITY_LIMIT)
                (and drawdown hard-drawdown (>= drawdown hard-drawdown)) (conj :DRAWDOWN_FREEZE))]
    {:result (if (seq codes) :DENY :ALLOW)
     :reason-codes codes
     :symbol symbol
     :calculations {:loss-at-stop (some-> loss str)
                    :remaining-risk-budget (some-> risk-budget str)}}))
