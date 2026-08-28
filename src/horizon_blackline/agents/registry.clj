(ns horizon-blackline.agents.registry
  "Versioned workload manifests. Agents can create bounded artifacts but have no
   capital authority and no direct broker capability."
  (:require [clojure.set :as set]))

(def forbidden-scopes #{"authorize" "alpaca:submit" "policy:write"})

(def manifests
  [{:agent-id "discovery" :version "1.0.0" :scopes #{"market:read" "candidate:write"} :max-runtime-s 20 :input-schema "market_snapshot@1" :output-schema "candidate_set@1"}
   {:agent-id "research" :version "1.0.0" :scopes #{"evidence:read" "evidence:write" "thesis:write"} :max-runtime-s 30 :input-schema "candidate@1" :output-schema "thesis@1"}
   {:agent-id "contrarian-critic" :version "1.0.0" :scopes #{"evidence:read" "critique:write"} :max-runtime-s 20 :input-schema "thesis@1" :output-schema "critique@1"}
   {:agent-id "evidence-critic" :version "1.0.0" :scopes #{"evidence:read" "critique:write"} :max-runtime-s 20 :input-schema "claims@1" :output-schema "critique@1"}
   {:agent-id "risk-critic" :version "1.0.0" :scopes #{"portfolio:read" "critique:write"} :max-runtime-s 20 :input-schema "trade_intent@1" :output-schema "critique@1"}
   {:agent-id "observer" :version "1.0.0" :scopes #{"alpaca:read" "observation:write"} :max-runtime-s 20 :input-schema "broker_observation@1" :output-schema "observation@1"}])

(defn valid-manifest? [manifest]
  (and (string? (:agent-id manifest)) (string? (:version manifest))
       (set? (:scopes manifest))
       (empty? (set/intersection forbidden-scopes (:scopes manifest)))
       (pos-int? (:max-runtime-s manifest))
       (string? (:input-schema manifest)) (string? (:output-schema manifest))))

(defn registry-valid? [] (every? valid-manifest? manifests))

(defn authorize-action! [agent-id scope]
  (let [manifest (some #(when (= agent-id (:agent-id %)) %) manifests)]
    (when-not manifest (throw (ex-info "Unknown agent" {:agent-id agent-id})))
    (when (or (contains? forbidden-scopes scope) (not (contains? (:scopes manifest) scope)))
      (throw (ex-info "Agent scope denied" {:agent-id agent-id :scope scope})))
    true))
