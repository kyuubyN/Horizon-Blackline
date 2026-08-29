;; KNOWN LIMITATION (audit HIGH-09): every state-transition function below (authorization!,
;; submitted!, broker-unknown!, reconcile!, observe!, start-monitoring!, reevaluate!, post-mortem!,
;; close!) does its entity mutation (store/put-authorization!, store/mark-execution!, ...), then
;; append! (its own d/transact for the BDR event), then advance! (a THIRD, separate d/transact for
;; the state CAS) -- a crash between any two of these leaves inconsistent state. A real fix means
;; every one of these ~9 functions building all three pieces of tx-data up front and submitting
;; them in a single d/transact, which means append!/advance! can no longer own their own transact
;; calls -- a structural change to every call site, not a local patch, and it risks the hash-chain/
;; CAS invariants that are this system's core correctness guarantee. Left as-is rather than rushed
;; under the deadline; Datomic transactions are otherwise atomic, so this only bites on a process
;; crash landing in one of these narrow windows, and this is paper trading.
(ns horizon-blackline.workflow.core
  (:require [horizon-blackline.agents.registry :as registry]
            [horizon-blackline.bdr.core :as bdr]
            [clojure.string :as str]
            [horizon-blackline.canonical-json :as canonical]
            [horizon-blackline.execution.gateway :as gateway]
            [horizon-blackline.persistence.datomic :as store])
  (:import (java.time Instant Duration)
           (java.util UUID)))

(def transitions
  {:DRAFT #{:CHALLENGED :DENIED :REVIEW}
   :CHALLENGED #{:AUTHORIZED :DENIED :REVIEW}
   :AUTHORIZED #{:SUBMISSION_PENDING}
   :SUBMISSION_PENDING #{:SUBMITTED :REJECTED :UNKNOWN}
   :UNKNOWN #{:SUBMITTED :REJECTED}
   :SUBMITTED #{:PARTIALLY_FILLED :FILLED :CANCELED :REJECTED}
   :CANCELED #{:CLOSED}
   :REJECTED #{:CLOSED}
   :PARTIALLY_FILLED #{:FILLED :CANCELED :MONITORING}
   :FILLED #{:MONITORING :CLOSED}
   :MONITORING #{:CLOSED}
   :CLOSED #{:POST_MORTEM_COMPLETE}})

(defn transition! [state next-state]
  (when-not (contains? (get transitions state #{}) next-state)
    (throw (ex-info "Illegal workflow transition" {:from state :to next-state})))
  next-state)

(defn new-system [] {:store (store/new-store)})

(defn create-bdr! [system request]
  (let [record (assoc (bdr/new-record request) :state :DRAFT)]
    (store/create-record! (:store system) record)))

(def event-type->required-scope
  "Only enforced for actors claiming a registered sub-agent id (see agents.registry) --
   internal/orchestrator/service actors are not in the registry and pass through unchecked."
  {:CANDIDATE_DISCOVERED "candidate:write"
   :THESIS_RESEARCHED "thesis:write"
   :EVIDENCE_CAPTURED "evidence:write"
   :CRITIQUE_BUNDLE_COMPLETED "critique:write"
   :BROKER_OBSERVED "observation:write"
   :POSITION_REEVALUATED "observation:write"})

(defn- authorize-actor! [event]
  (let [actor (:actor event)
        scope (get event-type->required-scope (:event-type event))]
    (when (and scope (some #(= actor (:agent-id %)) registry/manifests))
      (registry/authorize-action! actor scope))))

(defn- thin-record
  "bdr/append-event only ever reads :sealed?, :bdr-id, (count (:events record)), and
   (:event-hash (last (:events record))) -- never event payloads -- so a record built from
   store/head-info's cheap query is exactly as good as a full get-record here, without paying to
   pull and JSON-parse every prior event just to append one more."
  [head bdr-id]
  (let [n (:event-count head)]
    {:bdr-id bdr-id
     :sealed? (:sealed? head)
     :events (if (pos? n)
               (conj (vec (repeat (dec n) nil)) {:event-hash (:head-hash head)})
               [])}))

(defn append! [system bdr-id event]
  (authorize-actor! event)
  (let [head (store/head-info (:store system) bdr-id)]
    (when-not head
      (throw (ex-info "BDR not found" {:bdr-id bdr-id})))
    (let [record (thin-record head bdr-id)]
      (store/append-event! (:store system) record (last (:events (bdr/append-event record event)))))))

(defn- advance! [system bdr-id next-state]
  (let [record (store/get-record (:store system) bdr-id)]
    (store/transition-state! (:store system) bdr-id (:state record)
                             (transition! (:state record) next-state))))

(defn challenge! [system bdr-id critique-bundle]
  (append! system bdr-id {:event-type :CRITIQUE_BUNDLE_COMPLETED
                          :actor "critic-council"
                          :payload-schema "critique_bundle@1"
                          :payload critique-bundle})
  (advance! system bdr-id :CHALLENGED))

(defn authorization! [system {:keys [bdr-id intent policy-bundle-id ttl-seconds evaluation] :as request}]
  (let [record (store/get-record (:store system) bdr-id)
        issued-at (Instant/now)
        expires-at (.plus issued-at (Duration/ofSeconds (long (or ttl-seconds 30))))
        authorization {:authorization-id (str (UUID/randomUUID))
                       :bdr-id bdr-id
                       :result (:result evaluation)
                       :reason-codes (:reason-codes evaluation)
                       :policy-bundle-id policy-bundle-id
                       :input-hash (bdr/sha256 (canonical/encode intent))
                       :issued-at (str issued-at)
                       :expires-at (str expires-at)}
        ;; :intent rides along in the event payload only (put-authorization! below picks its own
        ;; fields, so this doesn't touch the Datomic authorization entity/schema). Storing the
        ;; actual data next to its hash is what makes :input-hash independently checkable instead
        ;; of an audit dead-end -- every other event here already carries its full payload.
        event-payload (assoc authorization :intent intent)]
    (when (or (nil? record) (:sealed? record) (not= :CHALLENGED (:state record)))
      (throw (ex-info "Authorization requires a challenged, unsealed BDR" {:bdr-id bdr-id :state (:state record)})))
    (when-not (#{:ALLOW :DENY :REVIEW} (:result authorization))
      (throw (ex-info "Capital evaluation is required" {:bdr-id bdr-id})))
    (store/put-authorization! (:store system) authorization)
    (append! system bdr-id {:event-type :AUTHORIZATION_ISSUED
                            :actor "blackline-authorizer"
                            :payload-schema "authorization_decision@1"
                            :payload event-payload})
    (advance! system bdr-id (case (:result authorization)
                              :ALLOW :AUTHORIZED
                              :DENY :DENIED
                              :REVIEW :REVIEW))
    authorization))

(defn freeze! [system actor reason]
  (store/freeze! (:store system) {:actor actor :reason reason :at (str (Instant/now))}))

(defn unfreeze! [system actor reason]
  (store/unfreeze! (:store system) {:actor actor :reason reason :at (str (Instant/now))}))

(defn prepare-execution!
  "Creates a durable execution outbox record after all local gates pass. It does
   not call Alpaca; a dispatcher may only consume this record after the BDR
   transition has been persisted."
  [system {:keys [authorization-id intent idempotency-key paper?]}]
  (when (or (not (string? idempotency-key)) (str/blank? idempotency-key))
    (throw (ex-info "Execution requires idempotency-key" {:reason-code :BROKER_STATE_UNKNOWN})))
  (if-let [existing (store/get-execution-by-key (:store system) idempotency-key)]
    existing
    (let [authorization (store/get-authorization (:store system) authorization-id)
          _ (when-not authorization
              (throw (ex-info "Authorization not found" {:authorization-id authorization-id})))
          prepared (gateway/preflight! {:paper? paper?
                                        :frozen? (store/frozen? (:store system))
                                        :authorization authorization
                                        :intent intent})
          execution (store/create-execution! (:store system)
                                             {:execution-id (str (UUID/randomUUID))
                                              :bdr-id (:bdr-id authorization)
                                              :authorization-id authorization-id
                                              :idempotency-key idempotency-key
                                              :intent intent
                                              :mcp-tool (:mcp-tool prepared)
                                              :client-order-id idempotency-key
                                              :status :SUBMISSION_PENDING
                                              :created-at (str (Instant/now))})]
      (append! system (:bdr-id authorization)
               {:event-type :EXECUTION_SUBMISSION_PENDING
                :actor "alpaca-gateway"
                :payload-schema "execution_outbox@1"
                :payload (dissoc execution :intent)})
      (advance! system (:bdr-id authorization) :SUBMISSION_PENDING)
      execution)))

(defn claim-execution!
  "Must precede submitted!/broker-unknown! -- see dispatcher/dispatch! for why this is a
   separate atomic step rather than folded into submitted!."
  [system execution-id]
  (store/claim-execution! (:store system) execution-id :SUBMISSION_PENDING :DISPATCHING))

(defn submitted! [system execution-id receipt]
  (let [execution (store/get-execution (:store system) execution-id)]
    (when-not (= :DISPATCHING (:status execution))
      (throw (ex-info "Only claimed (dispatching) executions can be submitted" {:execution-id execution-id :status (:status execution)})))
    (let [updated (store/mark-execution! (:store system) execution-id
                                         :DISPATCHING :SUBMITTED receipt)]
      (append! system (:bdr-id updated)
               {:event-type :EXECUTION_SUBMITTED
                :actor "alpaca-gateway"
                :payload-schema "broker_receipt@1"
                :payload (merge (dissoc updated :intent) {:broker-receipt receipt})})
      (advance! system (:bdr-id updated) :SUBMITTED)
      updated)))

(defn broker-unknown! [system execution-id failure]
  (let [execution (store/get-execution (:store system) execution-id)]
    (when-not (= :DISPATCHING (:status execution))
      (throw (ex-info "Only claimed (dispatching) executions can become unknown" {:execution-id execution-id :status (:status execution)})))
    (let [updated (store/mark-execution! (:store system) execution-id
                                         :DISPATCHING :UNKNOWN failure)]
      (append! system (:bdr-id updated)
               {:event-type :BROKER_OUTCOME_UNKNOWN
                :actor "alpaca-gateway"
                :payload-schema "broker_failure@1"
                :payload {:execution-id execution-id
                          :client-order-id (:client-order-id updated)
                          :failure failure}})
      (advance! system (:bdr-id updated) :UNKNOWN)
      updated)))

(defn reconcile! [system execution-id receipt]
  (let [execution (store/get-execution (:store system) execution-id)]
    (when-not (= :UNKNOWN (:status execution))
      (throw (ex-info "Only unknown executions require reconciliation" {:execution-id execution-id :status (:status execution)})))
    (let [updated (store/mark-execution! (:store system) execution-id :UNKNOWN :SUBMITTED receipt)]
      (append! system (:bdr-id updated)
               {:event-type :BROKER_RECONCILED
                :actor "observer-reconciler"
                :payload-schema "broker_reconciliation@1"
                :payload {:execution-id execution-id
                          :client-order-id (:client-order-id updated)
                          :receipt receipt}})
      (advance! system (:bdr-id updated) :SUBMITTED)
      updated)))

(def broker-status->workflow-state
  {:FILLED :FILLED
   :PARTIALLY_FILLED :PARTIALLY_FILLED
   :CANCELED :CANCELED
   :REJECTED :REJECTED})

(declare close!)

(defn observe! [system execution-id {:keys [status receipt]}]
  (let [execution (store/get-execution (:store system) execution-id)
        next-state (get broker-status->workflow-state status)]
    (when-not (= :SUBMITTED (:status execution))
      (throw (ex-info "Only submitted executions can be observed" {:execution-id execution-id :status (:status execution)})))
    (when-not next-state
      (throw (ex-info "Unknown broker status" {:status status})))
    (let [updated (store/mark-execution! (:store system) execution-id :SUBMITTED status receipt)]
      (append! system (:bdr-id updated)
               {:event-type :BROKER_OBSERVED
                :actor "observer-reconciler"
                :payload-schema "broker_observation@1"
                :payload {:execution-id execution-id :status status :receipt receipt}})
      (advance! system (:bdr-id updated) next-state)
      updated)))

(defn start-monitoring! [system bdr-id]
  (let [record (store/get-record (:store system) bdr-id)]
    (when-not (= :FILLED (:state record))
      (throw (ex-info "Monitoring requires a filled BDR" {:bdr-id bdr-id :state (:state record)})))
    (append! system bdr-id {:event-type :MONITORING_STARTED
                            :actor "observer-reconciler"
                            :payload-schema "monitoring@1"
                            :payload {}})
    (advance! system bdr-id :MONITORING)))

(defn reevaluate! [system bdr-id {:keys [decision trigger] :as observation}]
  (let [record (store/get-record (:store system) bdr-id)]
    (when-not (= :MONITORING (:state record))
      (throw (ex-info "Re-evaluation requires monitoring" {:bdr-id bdr-id :state (:state record)})))
    (when-not (#{:HOLD :REDUCE :EXIT} decision)
      (throw (ex-info "Re-evaluation decision is invalid" {:decision decision})))
    (append! system bdr-id {:event-type :POSITION_REEVALUATED
                            :actor "observer-reconciler"
                            :payload-schema "re_evaluation@1"
                            :payload observation})
    (if (= decision :HOLD)
      (store/get-record (:store system) bdr-id)
      (close! system bdr-id (str "re-evaluation:" (name decision))))))

(defn post-mortem! [system bdr-id report]
  (let [record (store/get-record (:store system) bdr-id)]
    (when-not (= :CLOSED (:state record))
      (throw (ex-info "Post-mortem requires a closed BDR" {:bdr-id bdr-id :state (:state record)})))
    (append! system bdr-id {:event-type :POST_MORTEM_RECORDED
                            :actor "post-mortem-service"
                            :payload-schema "post_mortem@1"
                            :payload report})
    (let [completed (advance! system bdr-id :POST_MORTEM_COMPLETE)]
      (store/seal-record! (:store system) (bdr/seal completed)))))

(defn close! [system bdr-id reason]
  (let [record (store/get-record (:store system) bdr-id)]
    (when-not (#{:FILLED :MONITORING :CANCELED :REJECTED} (:state record))
      (throw (ex-info "Only terminal or monitored BDRs can close" {:bdr-id bdr-id :state (:state record)})))
    (append! system bdr-id {:event-type :DECISION_CLOSED
                            :actor "observer-reconciler"
                            :payload-schema "closure@1"
                            :payload {:reason reason}})
    (advance! system bdr-id :CLOSED)))
