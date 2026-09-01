(ns horizon-blackline.adapters.mcp-http
  "Generic MCP-over-streamable-HTTP transport (initialize handshake + JSON-RPC calls,
   SSE-framed responses unwrapped). Extracted from the Alpaca adapter's private plumbing
   so a second, unrelated sidecar (the DuckDuckGo search server) can reuse the exact same
   transport without touching the audited Alpaca gateway code path.

   This namespace only moves bytes: it selects no tools, enforces no allowlist, and treats
   every tool result as opaque data. Callers are responsible for choosing a reviewed tool,
   passing typed arguments, and never treating a returned payload as instructions."
  (:require [clojure.string :as str]
            [jsonista.core :as json])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.time Duration)))

(def mapper (json/object-mapper {:decode-key-fn keyword}))
(def protocol-version "2025-03-26")

(def ^:private shared-client
  (delay (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 10)) (.build))))

(defn send-http!
  "Default transport. request is {:url :headers :body}; returns {:status :headers :body}
   with the mcp-session-id response header surfaced under :headers."
  [{:keys [url headers body timeout-seconds]}]
  (let [builder (-> (HttpRequest/newBuilder (URI/create url))
                    (.timeout (Duration/ofSeconds (long (or timeout-seconds 30))))
                    (.header "Content-Type" "application/json")
                    (.header "Accept" "application/json, text/event-stream"))
        builder (reduce (fn [request [header value]] (.header request header value)) builder headers)
        request (-> builder (.POST (HttpRequest$BodyPublishers/ofString body)) (.build))
        response (.send @shared-client request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :headers {"mcp-session-id" (.orElse (.firstValue (.headers response) "mcp-session-id") nil)}
     :body (.body response)}))

(defn- assert-success! [response]
  (when-not (<= 200 (:status response) 299)
    (throw (ex-info "MCP request failed" {:status (:status response) :body (:body response)})))
  response)

(defn- sse-body? [body]
  (let [trimmed (str/triml body)]
    (or (str/starts-with? trimmed "event:")
        (str/starts-with? trimmed "data:")
        (str/starts-with? trimmed ":"))))

(defn- parse-rpc-body
  "A streamable-HTTP MCP reply is either a bare JSON object or an SSE stream that interleaves
   progress `notifications/message` frames with the one real JSON-RPC response. Return the
   response frame: the last data frame carrying :result or :error, else the last parseable one."
  [body]
  (when (seq body)
    (if-not (sse-body? body)
      (json/read-value body mapper)
      (let [frames (->> (str/split-lines body)
                        (filter #(str/starts-with? % "data:"))
                        (map #(str/trim (subs % 5)))
                        (remove str/blank?)
                        (keep (fn [line] (try (json/read-value line mapper) (catch Exception _ nil))))
                        vec)]
        (or (last (filter #(or (contains? % :result) (contains? % :error)) frames))
            (last frames)
            {})))))

(defn- rpc! [send! {:keys [base-url session-id timeout-seconds]} id method params]
  (let [response (-> (send! {:url base-url
                             :timeout-seconds timeout-seconds
                             :headers (cond-> {"mcp-protocol-version" protocol-version}
                                        session-id (assoc "mcp-session-id" session-id))
                             :body (json/write-value-as-string
                                    (cond-> {:jsonrpc "2.0" :method method :params params}
                                      (some? id) (assoc :id id)) mapper)})
                     assert-success!)]
    (or (parse-rpc-body (:body response)) {})))

(defn initialize!
  "Runs the MCP initialize handshake against base-url and returns a session map
   {:base-url :session-id :server-info :timeout-seconds} usable with call-tool!."
  ([base-url] (initialize! send-http! base-url {}))
  ([send! base-url] (initialize! send! base-url {}))
  ([send! base-url {:keys [timeout-seconds]}]
   (let [response (-> (send! {:url base-url
                              :timeout-seconds timeout-seconds
                              :headers {}
                              :body (json/write-value-as-string
                                     {:jsonrpc "2.0" :id 1 :method "initialize"
                                      :params {:protocolVersion protocol-version
                                               :capabilities {}
                                               :clientInfo {:name "horizon-blackline" :version "0.1.0"}}} mapper)})
                      assert-success!)
         payload (or (parse-rpc-body (:body response)) {})
         session-id (get-in response [:headers "mcp-session-id"])]
     (when-not session-id
       (throw (ex-info "MCP did not return a session id" {:response payload})))
     (rpc! send! {:base-url base-url :session-id session-id :timeout-seconds timeout-seconds}
           nil "notifications/initialized" {})
     ;; :send is carried in the session so single-sidecar callers (e.g. the DDG adapter) don't
     ;; have to thread the transport fn through every call; tests inject a fake here.
     {:base-url base-url :session-id session-id :timeout-seconds timeout-seconds :send send!
      :server-info (get-in payload [:result :serverInfo])})))

(defn list-tools!
  ([session] (list-tools! (or (:send session) send-http!) session))
  ([send! session]
   (or (get-in (rpc! send! session 3 "tools/list" {}) [:result :tools]) [])))

(defn call-tool!
  "Calls one MCP tool. tool is a string, arguments a map. Returns the JSON-RPC result map.
   Throws ex-info on an RPC error or an isError result."
  ([session tool arguments] (call-tool! (or (:send session) send-http!) session tool arguments))
  ([send! session tool arguments]
   (let [payload (rpc! send! session 2 "tools/call" {:name tool :arguments arguments})
         result (:result payload)
         text (some :text (:content result))]
     (when (or (:error payload)
               (:isError result)
               (and text (str/starts-with? text "Error calling tool")))
       (throw (ex-info "MCP tool returned an error"
                       {:error (or (:error payload) text) :tool tool})))
     result)))

(defn result-text
  "Concatenates the text parts of a tools/call result (FastMCP returns a single text part)."
  [result]
  (->> (:content result) (keep :text) (str/join "\n")))
