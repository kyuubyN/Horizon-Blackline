(ns horizon-blackline.ddg-test
  (:require [clojure.test :refer [deftest is]]
            [horizon-blackline.adapters.ddg :as ddg]))

(def ^:private search-payload
  (str "Found 3 search results:\n\n"
       "1. NVIDIA Corporation (NVDA) Latest Stock News - Yahoo Finance\n"
       "   URL: https://finance.yahoo.com/quote/NVDA/news/\n"
       "   Summary: Get the latest NVIDIA stock news and headlines.\n\n"
       "2. NVDA News Today - MarketBeat\n"
       "   URL: https://www.marketbeat.com/stocks/NASDAQ/NVDA/news/\n"
       "   Summary: What's going on at NVIDIA?\n\n"
       "3. Junk result with a relative link\n"
       "   URL: /not/a/real/url\n"
       "   Summary: should be dropped\n"))

(defn- fake-send [{:keys [body]}]
  (let [req (when (seq body) (jsonista.core/read-value body))
        method (get req "method")]
    {:status 200
     :headers {"mcp-session-id" "sess-1"}
     :body (case method
             "initialize"
             "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"serverInfo\":{\"name\":\"ddg-search\"}}}"
             "tools/call"
             (let [tool (get-in req ["params" "name"])
                   text (if (= tool "search") search-payload "clean article body about a rating upgrade")]
               (str "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[{\"type\":\"text\",\"text\":"
                    (jsonista.core/write-value-as-string text) "}]}}"))
             "{}")}))

(deftest search-parses-the-numbered-block-and-drops-non-http-links
  (let [session (ddg/connect! fake-send "http://ddg.test/mcp")
        hits (ddg/search! session "nvda" {:max-results 10})]
    (is (= 2 (count hits)))
    (is (= "https://finance.yahoo.com/quote/NVDA/news/" (:url (first hits))))
    (is (= "NVDA News Today - MarketBeat" (:title (second hits))))
    (is (= "Get the latest NVIDIA stock news and headlines." (:snippet (first hits))))))

(deftest search-and-fetch-never-throw-on-transport-failure
  (let [session {:base-url "x" :session-id "y" :send (fn [_] (throw (ex-info "down" {})))}]
    (is (= [] (ddg/search! session "q" {})))
    (is (nil? (ddg/fetch-content! session "https://x.test" {})))))

(deftest fetch-content-rejects-the-servers-own-error-sentinel
  (let [err-send (fn [{:keys [body]}]
                   (let [method (get (jsonista.core/read-value body) "method")]
                     {:status 200 :headers {"mcp-session-id" "s"}
                      :body (if (= method "tools/call")
                              "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"Error: Could not access the webpage\"}]}}"
                              "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"serverInfo\":{}}}")}))
        session (ddg/connect! err-send "http://ddg.test/mcp")]
    (is (nil? (ddg/fetch-content! session "https://x.test" {})))))
