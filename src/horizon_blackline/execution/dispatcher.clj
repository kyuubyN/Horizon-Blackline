(ns horizon-blackline.execution.dispatcher
  "The only code path that can call an Alpaca order tool. This namespace is not
   reachable by agents; it consumes an already-persisted execution outbox item."
  (:require [clojure.string :as str]
            [horizon-blackline.adapters.alpaca-mcp :as mcp]
            [horizon-blackline.execution.gateway :as gateway]
            [horizon-blackline.persistence.datomic :as store]
            [horizon-blackline.workflow.core :as workflow]
            [jsonista.core :as json]))

(def mapper (json/object-mapper {:decode-key-fn keyword}))

(defn- first-account-id [value]
  (cond
    (map? value) (or (:id value) (some first-account-id (vals value)))
    (sequential? value) (some first-account-id value)
    :else nil))

(defn- account-id [result]
  (let [text (some :text (:content result))]
    (when text
      (try (some-> (json/read-value text mapper) first-account-id str)
           (catch Exception _ nil)))))

(defn- assert-account! [mcp-call! session expected-account-id]
  (when (or (nil? expected-account-id) (str/blank? expected-account-id))
    (throw (ex-info "Paper account allowlist is required" {:reason-code :PAPER_ENV_REQUIRED})))
  (let [actual-account-id (account-id (mcp-call! session "get_account_info" {}))]
    (when-not (= expected-account-id actual-account-id)
      (throw (ex-info "Broker account is not allowlisted for paper execution"
                      {:reason-code :PAPER_ENV_REQUIRED})))
    actual-account-id))

(defn dispatch!
  "Dispatches exactly one pending outbox item after verifying the MCP toolset and
   account allowlist. The injected fns make this safety boundary testable."
  ([system execution-id]
   (dispatch! system execution-id {:mcp-url (or (System/getenv "ALPACA_MCP_URL")
                                                  "http://127.0.0.1:8001/mcp")
                                  :paper-account-id (System/getenv "ALPACA_PAPER_ACCOUNT_ID")
                                  :initialize! mcp/initialize!
                                  :list-tools! mcp/list-tools!
                                  :call-tool! mcp/call-tool!}))
  ([system execution-id {:keys [mcp-url paper-account-id initialize! list-tools! call-tool!]}]
   (let [execution (store/get-execution (:store system) execution-id)]
     (when-not (= :SUBMISSION_PENDING (:status execution))
       (throw (ex-info "Execution is not pending dispatch" {:execution-id execution-id :status (:status execution)})))
     (let [session (initialize! mcp-url)
           tools (set (map :name (list-tools! session)))]
       (when-not (contains? tools (:mcp-tool execution))
         (throw (ex-info "MCP does not expose approved order tool" {:tool (:mcp-tool execution)})))
       (when-not (contains? tools "get_account_info")
         (throw (ex-info "MCP does not expose account validation" {:reason-code :PAPER_ENV_REQUIRED})))
       (assert-account! call-tool! session paper-account-id)
       (try
         (let [receipt (call-tool! session (:mcp-tool execution)
                                   (gateway/order-arguments {:mcp-tool (:mcp-tool execution)
                                                             :client-order-id (:client-order-id execution)
                                                             :intent (:intent execution)}))]
           (workflow/submitted! system execution-id receipt))
         (catch Exception exception
           (workflow/broker-unknown! system execution-id
                                     {:message (.getMessage exception)
                                      :type (str (class exception))})))))))

(defn cancel!
  ([system execution-id broker-order-id]
   (cancel! system execution-id broker-order-id {:mcp-url (or (System/getenv "ALPACA_MCP_URL")
                                                               "http://127.0.0.1:8001/mcp")
                                                 :initialize! mcp/initialize!
                                                 :call-tool! mcp/call-tool!}))
  ([system execution-id broker-order-id {:keys [mcp-url initialize! call-tool!]}]
   (let [execution (store/get-execution (:store system) execution-id)]
     (when-not (= :SUBMITTED (:status execution))
       (throw (ex-info "Only submitted executions can be canceled" {:execution-id execution-id :status (:status execution)})))
     (when-not (string? broker-order-id)
       (throw (ex-info "Broker order id is required for cancellation" {:execution-id execution-id})))
     (let [session (initialize! mcp-url)
           receipt (call-tool! session "cancel_order_by_id" {:order_id broker-order-id})]
       (workflow/observe! system execution-id {:status :CANCELED
                                               :receipt {:broker-order-id broker-order-id
                                                         :cancel-receipt receipt}})))))
