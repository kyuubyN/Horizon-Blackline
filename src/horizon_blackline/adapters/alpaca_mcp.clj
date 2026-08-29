(ns horizon-blackline.adapters.alpaca-mcp
  "Minimal MCP client used exclusively by the Alpaca Gateway. It never accepts
   free-text agent commands: callers must select a reviewed tool and typed args."
  (:require [clojure.string :as str]
            [jsonista.core :as json])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.time Duration)))

(def mapper (json/object-mapper {:decode-key-fn keyword}))
(def protocol-version "2025-03-26")
(def execution-tools #{"place_stock_order" "place_crypto_order" "place_option_order"})
(def cancel-tools #{"cancel_order_by_id"})
(def read-tools #{"get_account_info" "get_order_by_client_id" "get_stock_latest_quote"
                   "get_all_positions" "get_stock_bars" "get_news" "get_clock"})

(def ^:private shared-client
  (delay (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 10)) (.build))))

(defn- send-http! [{:keys [url headers body]}]
  (let [builder (-> (HttpRequest/newBuilder (URI/create url))
                    (.timeout (Duration/ofSeconds 10))
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

(defn- unwrap-sse [body]
  (if (sse-body? body)
    (->> (str/split-lines body)
         (filter #(str/starts-with? % "data:"))
         (map #(subs % 5))
         (str/join "\n")
         str/trim)
    body))

(defn- rpc! [send! {:keys [base-url session-id]} id method params]
  (let [response (-> (send! {:url base-url
                             :headers (cond-> {"mcp-protocol-version" protocol-version}
                                        session-id (assoc "mcp-session-id" session-id))
                             :body (json/write-value-as-string
                                    (cond-> {:jsonrpc "2.0" :method method :params params}
                                      (some? id) (assoc :id id)) mapper)})
                     assert-success!)]
    (if (seq (:body response))
      (json/read-value (unwrap-sse (:body response)) mapper)
      {})))

(defn initialize!
  ([base-url] (initialize! send-http! base-url))
  ([send! base-url]
   (let [response (-> (send! {:url base-url
                              :headers {}
                              :body (json/write-value-as-string
                                     {:jsonrpc "2.0" :id 1 :method "initialize"
                                      :params {:protocolVersion protocol-version
                                               :capabilities {}
                                               :clientInfo {:name "horizon-blackline-gateway" :version "0.1.0"}}} mapper)})
                      assert-success!)
         payload (json/read-value (unwrap-sse (:body response)) mapper)
         session-id (get-in response [:headers "mcp-session-id"])]
     (when-not session-id
       (throw (ex-info "MCP did not return a session id" {:response payload})))
     (rpc! send! {:base-url base-url :session-id session-id} nil "notifications/initialized" {})
     {:base-url base-url :session-id session-id :server-info (get-in payload [:result :serverInfo])})))

(defn list-tools!
  ([session] (list-tools! send-http! session))
  ([send! session]
   (let [payload (rpc! send! session 3 "tools/list" {})]
     (or (get-in payload [:result :tools]) []))))

(defn call-tool!
  ([session tool arguments] (call-tool! send-http! session tool arguments))
  ([send! {:keys [base-url session-id]} tool arguments]
   (when-not (contains? (into (into execution-tools cancel-tools) read-tools) tool)
     (throw (ex-info "Gateway rejected MCP tool" {:tool tool})))
   (let [payload (rpc! send! {:base-url base-url :session-id session-id}
                       2 "tools/call" {:name tool :arguments arguments})]
     (let [result (:result payload)
           text (some :text (:content result))]
       (when (or (:error payload)
                 (:isError result)
                 (and text (clojure.string/starts-with? text "Error calling tool")))
         (throw (ex-info "MCP tool returned an error"
                         {:error (or (:error payload) text)
                          :tool tool})))
       result))))
