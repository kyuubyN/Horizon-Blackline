(ns horizon-blackline.main
  (:require [clojure.java.io :as io]
            [jsonista.core :as json]
            [reitit.ring :as ring]
            [ring.adapter.jetty :as jetty]
            [horizon-blackline.capital.policy :as policy]
            [horizon-blackline.execution.dispatcher :as dispatcher]
            [horizon-blackline.demo.core :as demo]
            [horizon-blackline.market :as market]
            [horizon-blackline.intelligence :as intelligence]
            [horizon-blackline.campaign :as campaign]
            [horizon-blackline.bdr.core :as bdr]
            [horizon-blackline.agents.registry :as agents]
            [horizon-blackline.readiness :as readiness]
            [horizon-blackline.schema :as schema]
            [horizon-blackline.workflow.core :as workflow]
            [horizon-blackline.persistence.datomic :as store]
            [horizon-blackline.orchestrator :as orchestrator])
  (:gen-class))

(def mapper (json/object-mapper {:decode-key-fn keyword}))
(def system (delay (workflow/new-system)))

(defn- response [status body]
  {:status status :headers {"content-type" "application/json"}
   :body (json/write-value-as-string body mapper)})

(defn- json-body [request]
  (json/read-value (slurp (:body request)) mapper))

(defn normalize-intent [intent]
  (reduce (fn [value key]
            (if (string? (get value key))
              (update value key keyword)
              value))
          intent
          [:asset-class :side :order-type]))

(defn normalize-evidence [evidence]
  (if (string? (:source-type evidence))
    (update evidence :source-type keyword)
    evidence))

(defn- static-file [path content-type]
  (let [file (io/file path)]
    (if (.isFile file)
      {:status 200 :headers {"content-type" content-type} :body (slurp file)}
      {:status 503 :headers {"content-type" "text/plain"} :body "UI build unavailable"})))

(defn- exception-response [handler]
  (fn [request]
    (try (handler request)
         (catch clojure.lang.ExceptionInfo e
           (response 422 {:error (.getMessage e) :details (ex-data e)}))
         (catch IllegalArgumentException e
           (response 400 {:error "invalid_request" :message (.getMessage e)}))
         (catch Exception _ (response 500 {:error "internal_error"})))))

(def ^:private public-uris #{"/health" "/ready"})

(defn- constant-time-eq? [a b]
  (java.security.MessageDigest/isEqual
   (.getBytes (str a) java.nio.charset.StandardCharsets/UTF_8)
   (.getBytes (str b) java.nio.charset.StandardCharsets/UTF_8)))

(defn wrap-auth
  "No-op when HORIZON_API_AUTH_TOKEN is unset/blank (default local/dev posture, matching the
   README's stated localhost-only threat model). Set it to require a matching Bearer token on
   every route except /health and /ready -- worth turning on for any deployment where the
   loopback boundary alone isn't a strong enough guarantee (containers, multi-user hosts,
   anything reachable via a reverse proxy)."
  [handler expected-token]
  (if (clojure.string/blank? expected-token)
    handler
    (fn [request]
      (if (contains? public-uris (:uri request))
        (handler request)
        (let [header (get-in request [:headers "authorization"])
              token (when (and header (.startsWith header "Bearer ")) (subs header 7))]
          (if (and token (constant-time-eq? token expected-token))
            (handler request)
            (response 401 {:error "unauthorized"})))))))

;; A sealed record is immutable, so its verify result can never change once computed -- cache it
;; keyed by bdr-id+seal to avoid replaying the full SHA-256 hash chain on every /v1/metrics poll.
;; Unsealed records are re-verified every time since their event log can still grow.
(def ^:private verify-cache (atom {}))

(defn- cached-verify [record]
  (if (:sealed? record)
    (let [cache-key [(:bdr-id record) (:seal record)]]
      (if-some [cached (get @verify-cache cache-key)]
        cached
        (let [result (bdr/verify record)]
          (swap! verify-cache assoc cache-key result)
          result)))
    (bdr/verify record)))

(defn system-metrics [system]
  (let [records (store/list-records (:store system))]
    {:bdr-total (count records)
     :events-total (reduce + 0 (map #(count (:events %)) records))
     :sealed-total (count (filter :sealed? records))
     :replay-valid-total (count (filter cached-verify records))
     :replay-invalid-total (count (remove cached-verify records))
     :states (frequencies (map :state records))}))

(defn audit-export [record]
  {:format "horizon-blackline/audit-export@1"
   :exported-at (str (java.time.Instant/now))
   :bdr record
   :replay {:valid? (cached-verify record)
            :event-count (count (:events record))
            :seal (:seal record)}})

(defn official-campaign-view [system]
  (let [config (campaign/config)
        status (campaign/status config (java.time.Instant/now))
        summary (campaign/pnl-summary system)]
    (cond-> status
      summary (assoc :baseline-captured? true :pnl summary))))

(defn make-app [system]
  (wrap-auth
   (exception-response
   (ring/ring-handler
    (ring/router
     [["/health" {:get (fn [_] (response 200 {:status "ok" :paper-only true}))}]
      ["/ready" {:get (fn [_]
                         (let [result (readiness/check-config (agents/registry-valid?))]
                           (response (if (:ready? result) 200 503) result)))}]
      ["/v1/agents" {:get (fn [_] (response 200 {:registry-valid? (agents/registry-valid?)
                                                    :agents agents/manifests}))}]
      ["/v1/system" {:get (fn [_] (response 200 (store/system-status (:store system))))}]
      ["/v1/metrics" {:get (fn [_] (response 200 (system-metrics system)))}]
      ["/v1/campaign/official" {:get (fn [_] (response 200 (official-campaign-view system)))}]
      ["/v1/campaign/official/baseline" {:post (fn [_]
                                                   (let [snapshot (campaign/read-account-snapshot!)
                                                         result (campaign/capture-baseline! system (campaign/config) snapshot (java.time.Instant/now))]
                                                     (response 201 {:campaign (campaign/pnl result)})))}]
      ["/v1/campaign/official/snapshot" {:post (fn [_]
                                                   (let [snapshot (campaign/read-account-snapshot!)
                                                         result (campaign/capture-snapshot! system (campaign/config) snapshot (java.time.Instant/now))]
                                                     (response 201 {:campaign (campaign/pnl result)})))}]
      ["/v1/market/quote/:symbol" {:get (fn [request]
                                            (response 200 (market/latest-stock-quote!
                                                           (get-in request [:path-params :symbol]))))}]
      ["/v1/discovery/:symbol" {:get (fn [request]
                                        (response 200 (intelligence/discover
                                                       (market/latest-stock-quote!
                                                        (get-in request [:path-params :symbol])))))}]
      ["/v1/intelligence/discover" {:post (fn [request]
                                             (response 200 (intelligence/discover (json-body request))))}]
      ["/v1/intelligence/research" {:post (fn [request]
                                             (response 200 (intelligence/research (json-body request))))}]
      ["/" {:get (fn [_] (static-file "resources/public/index.html" "text/html; charset=utf-8"))}]
      ["/app.js" {:get (fn [_] (static-file "target/ui/app.js" "application/javascript; charset=utf-8"))}]
      ["/v1/bdr" {:get (fn [_] (response 200 (store/list-records (:store system))))
                    :post (fn [request]
                            (response 201 (workflow/create-bdr! system (json-body request))))}]
      ["/v1/bdr/:id" {:get (fn [request]
                              (if-let [record (store/get-record (:store system) (get-in request [:path-params :id]))]
                                (response 200 record)
                                (response 404 {:error "bdr_not_found"})))}]
      ["/v1/bdr/:id/replay" {:get (fn [request]
                                     (if-let [record (store/get-record (:store system) (get-in request [:path-params :id]))]
                                       (response 200 {:bdr-id (:bdr-id record)
                                                      :valid? (cached-verify record)
                                                      :events (:events record)})
                                       (response 404 {:error "bdr_not_found"})))}]
      ["/v1/bdr/:id/export" {:get (fn [request]
                                     (if-let [record (store/get-record (:store system) (get-in request [:path-params :id]))]
                                       (response 200 (audit-export record))
                                       (response 404 {:error "bdr_not_found"})))}]
      ["/v1/bdr/:id/challenge" {:post (fn [request]
                                         (response 200 (workflow/challenge! system
                                                                           (get-in request [:path-params :id])
                                                                           (json-body request))))}]
      ["/v1/bdr/:id/evidence" {:post (fn [request]
                                        (let [evidence (normalize-evidence (json-body request))]
                                          (schema/assert-valid! schema/evidence-envelope evidence)
                                          (response 201 (workflow/append! system
                                                                          (get-in request [:path-params :id])
                                                                          {:event-type :EVIDENCE_CAPTURED
                                                                           :actor "evidence-service"
                                                                           :payload-schema "evidence_envelope@1"
                                                                           :payload evidence}))))}]
      ["/v1/bdr/:id/discovery" {:post (fn [request]
                                         (response 201 (workflow/append! system
                                                                        (get-in request [:path-params :id])
                                                                        {:event-type :CANDIDATE_DISCOVERED
                                                                         :actor "discovery"
                                                                         :payload-schema "candidate_set@1"
                                                                         :payload (json-body request)})))}]
      ["/v1/bdr/:id/research" {:post (fn [request]
                                        (response 201 (workflow/append! system
                                                                       (get-in request [:path-params :id])
                                                                       {:event-type :THESIS_RESEARCHED
                                                                        :actor "research"
                                                                        :payload-schema "thesis@1"
                                                                        :payload (json-body request)})))}]
      ["/v1/bdr/:id/post-mortem" {:post (fn [request]
                                           (response 200 (workflow/post-mortem! system
                                                                                (get-in request [:path-params :id])
                                                                                (json-body request))))}]
      ["/v1/bdr/:id/close" {:post (fn [request]
                                     (response 200 (workflow/close! system
                                                                     (get-in request [:path-params :id])
                                                                     (:reason (json-body request)))))}]
      ["/v1/capital/evaluate" {:post (fn [request]
                                        (response 200 (policy/evaluate
                                                       (update (json-body request) :intent normalize-intent))))}]
      ["/v1/demo/run" {:post (fn [_] (response 201 (demo/run-demo! system)))}]
      ["/v1/authorizations"
       {:post (fn [request]
                (let [command (update (json-body request) :intent normalize-intent)]
                  (schema/assert-valid! schema/trade-intent (:intent command))
                  ;; evidence-valid?/critics-complete? are derived from the BDR's own real event
                  ;; history, never trusted from the request body -- a client that never called
                  ;; POST .../evidence or .../challenge cannot simply assert "true" and forge an
                  ;; authorization. (:snapshot/:policy remain caller-supplied for now: the
                  ;; desktop app has no live account-snapshot capture wired into this dialog yet.)
                  (let [record (store/get-record (:store system) (:bdr-id command))
                        events (:events record)
                        evidence-valid? (boolean (some #(= :EVIDENCE_CAPTURED (:event-type %)) events))
                        critique-event (some #(when (= :CRITIQUE_BUNDLE_COMPLETED (:event-type %)) %) events)
                        critics-complete? (boolean (and critique-event
                                                        (every? :complete (get-in critique-event [:payload :critics]))))
                        command (-> command
                                    (assoc :evidence-valid? evidence-valid?)
                                    (assoc :critics-complete? critics-complete?))]
                    (response 201 (workflow/authorization!
                                   system
                                   (assoc command :evaluation (policy/evaluate command)))))))}]
      ["/v1/authorizations/:id" {:get (fn [request]
                                         (if-let [authorization (store/get-authorization (:store system)
                                                                                          (get-in request [:path-params :id]))]
                                           (response 200 authorization)
                                           (response 404 {:error "authorization_not_found"})))}]
      ["/v1/executions"
       {:post (fn [request]
                (let [body (json-body request)
                      command (assoc (update body :intent normalize-intent)
                                     :paper? (= "true" (System/getenv "ALPACA_PAPER_TRADE")))]
                  (schema/assert-valid! schema/trade-intent (:intent command))
                  (response 202 (workflow/prepare-execution! system command))))}]
      ["/v1/executions/:id/dispatch"
       {:post (fn [request]
                (let [confirmation (:operator-confirmation (json-body request))]
                  (when-not (= "DISPATCH-PAPER" confirmation)
                    (throw (ex-info "Explicit paper dispatch confirmation is required" {:reason-code :PAPER_ENV_REQUIRED})))
                  (response 200 (dispatcher/dispatch! system (get-in request [:path-params :id])))))}]
      ["/v1/executions/:id/autonomous-dispatch"
       {:post (fn [request]
                (when-not (campaign/autonomy-allowed? system (campaign/config) (java.time.Instant/now))
                  (throw (ex-info "Autonomous dispatch is not allowed for the official campaign"
                                  {:reason-code :PAPER_ENV_REQUIRED
                                   :campaign (official-campaign-view system)})))
                (response 200 (dispatcher/dispatch! system (get-in request [:path-params :id]))))}]
      ["/v1/executions/:id/cancel"
       {:post (fn [request]
                (let [{:keys [operator-confirmation broker-order-id]} (json-body request)]
                  (when-not (= "CANCEL-PAPER" operator-confirmation)
                    (throw (ex-info "Explicit paper cancellation confirmation is required" {:reason-code :PAPER_ENV_REQUIRED})))
                  (response 200 (dispatcher/cancel! system
                                                     (get-in request [:path-params :id])
                                                     broker-order-id))))}]
      ["/v1/executions/:id/observe"
       {:post (fn [request]
                (response 200 (workflow/observe! system
                                                  (get-in request [:path-params :id])
                                                  (update (json-body request) :status keyword))))}]
      ["/v1/executions/:id/reconcile"
       {:post (fn [request]
                (response 200 (workflow/reconcile! system
                                                    (get-in request [:path-params :id])
                                                    (json-body request))))}]
      ["/v1/bdr/:id/monitor" {:post (fn [request]
                                       (response 200 (workflow/start-monitoring! system
                                                                                (get-in request [:path-params :id]))))}]
      ["/v1/bdr/:id/reevaluate" {:post (fn [request]
                                          (response 200 (workflow/reevaluate! system
                                                                              (get-in request [:path-params :id])
                                                                              (update (json-body request) :decision keyword))))}]
      ["/v1/system/freeze" {:post (fn [request]
                                     (let [{:keys [actor reason]} (json-body request)]
                                       (response 200 (workflow/freeze! system actor reason))))}]
      ["/v1/system/unfreeze"
       {:post (fn [request]
                (let [{:keys [actor reason operator-confirmation]} (json-body request)]
                  (when-not (= "UNFREEZE" operator-confirmation)
                    (throw (ex-info "Explicit unfreeze confirmation is required" {:reason-code :SYSTEM_FROZEN})))
                  (response 200 (workflow/unfreeze! system actor reason))))}]])))
   (System/getenv "HORIZON_API_AUTH_TOKEN")))

(def app (delay (make-app @system)))

(defn- start-orchestrator-loop!
  "Datomic Local holds one file lock per storage-dir, so the trading loop must run inside this
   same JVM rather than as a second OS process racing the API for that lock. Opt-in via
   HORIZON_ORCHESTRATOR_EMBEDDED so plain `bin/run-api` (dev/testing) stays API-only."
  []
  (if-not (= "true" (System/getenv "HORIZON_ORCHESTRATOR_EMBEDDED"))
    (println "[main] HORIZON_ORCHESTRATOR_EMBEDDED not true; orchestrator loop not started")
    (let [cfg (orchestrator/config)]
      (if (empty? (:watchlist cfg))
        (println "[main] HORIZON_WATCHLIST empty; orchestrator loop not started")
        (let [deps (orchestrator/default-deps)
              ;; Stop-loss monitoring dispatches real exit orders, so it runs on its own faster
              ;; daemon thread rather than being starved by per-symbol LLM/ProofRay latency in tick!.
              monitoring-thread (Thread.
                                 (fn []
                                   (while true
                                     (try
                                       (orchestrator/tick-monitoring! @system deps cfg (campaign/config) (java.time.Instant/now))
                                       (catch Exception e
                                         (println "[main] orchestrator monitoring tick failed:" (.getMessage e))))
                                     (Thread/sleep (* 1000 (:monitoring-poll-seconds cfg))))))
              thread (Thread.
                      (fn []
                        (println "[main] orchestrator loop starting, watchlist:" (:watchlist cfg))
                        (while true
                          (try
                            (orchestrator/tick! @system deps cfg (campaign/config) (java.time.Instant/now))
                            (catch Exception e
                              (println "[main] orchestrator tick cycle failed:" (.getMessage e))))
                          (Thread/sleep (* 1000 (:poll-seconds cfg))))))]
          (.setDaemon monitoring-thread true)
          (.setName monitoring-thread "orchestrator-monitoring-loop")
          (.start monitoring-thread)
          (.setDaemon thread true)
          (.setName thread "orchestrator-loop")
          (.start thread))))))

(defn -main [& _]
  (start-orchestrator-loop!)
  (jetty/run-jetty @app {:host "127.0.0.1"
                         :port (Integer/parseInt (or (System/getenv "PORT") "8080"))
                         :join? true}))
