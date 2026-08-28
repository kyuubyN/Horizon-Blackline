(ns horizon-blackline.intelligence
  "Deterministic acquisition artifacts. They preserve observed provenance and
   limitations. They do not predict returns or grant capital authority."
  (:require [horizon-blackline.bdr.core :as bdr]
            [horizon-blackline.canonical-json :as canonical]))

(defn discover [quote]
  (let [evidence (:evidence quote)
        candidate-id (str "candidate-" (subs (bdr/sha256 (canonical/encode evidence)) 0 16))]
    {:candidate {:candidate-id candidate-id
                 :symbol (:symbol quote)
                 :source-hash (:content-hash evidence)
                 :observed-at (:observed-at evidence)
                 :discovery-method "alpaca-latest-quote@1"
                 :environment :PAPER_READ_ONLY}
     :evidence evidence}))

(defn research [candidate]
  (let [digest (subs (bdr/sha256 (canonical/encode candidate)) 0 16)]
    {:thesis-id (str "thesis-" digest)
     :candidate-id (:candidate-id candidate)
     :symbol (:symbol candidate)
     :claims [{:claim-id (str "claim-" digest)
               :kind :market-data-captured
               :evidence-hash (:source-hash candidate)
               :observed-at (:observed-at candidate)}]
     :limitations ["Deterministic acquisition only; no forecast or investment recommendation."
                   "A quote alone is insufficient to authorize capital without challenge and policy evaluation."]}))
