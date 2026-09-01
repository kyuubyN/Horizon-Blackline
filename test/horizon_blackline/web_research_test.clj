(ns horizon-blackline.web-research-test
  (:require [clojure.test :refer [deftest is]]
            [horizon-blackline.web-research :as wr]
            [jsonista.core :as json]))

(defn- fake-session [{:keys [search-text fetch-text]}]
  {:base-url "http://ddg.test/mcp" :session-id "s"
   :send (fn [{:keys [body]}]
           (let [method (get (json/read-value body) "method")]
             {:status 200 :headers {"mcp-session-id" "s"}
              :body (case method
                      "tools/call"
                      (let [tool (get-in (json/read-value body) ["params" "name"])
                            text (if (= tool "search") search-text fetch-text)]
                        (str "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[{\"type\":\"text\",\"text\":"
                             (json/write-value-as-string text) "}]}}"))
                      "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"serverInfo\":{}}}")}))})

(def ^:private mixed-results
  (str "Found 3 search results:\n\n"
       "1. Nubank Q3 result beats - Reuters\n   URL: https://www.reuters.com/markets/nu-q3\n   Summary: Nu Holdings posted a record profit.\n\n"
       "2. Random forum thread\n   URL: https://www.reddit.com/r/stocks/abc\n   Summary: chatter\n\n"
       "3. NU upgraded to Buy - MarketWatch\n   URL: https://www.marketwatch.com/story/nu-upgrade\n   Summary: Analyst lifts target.\n"))

(deftest fresh-evidence-keeps-only-allowlisted-domains-and-labels-recency
  (let [now (java.time.Instant/parse "2026-09-01T13:00:00Z")
        docs (wr/fresh-evidence!
              {:ddg-connect! (fn [] (fake-session {:search-text mixed-results
                                                   :fetch-text "Full article: Nu Holdings beat estimates and guided higher."}))}
              "NU" now {:max-docs 5})]
    (is (= 2 (count docs)) "reddit.com is filtered out, reuters + marketwatch kept")
    (is (every? #(re-find #"reuters\.com|marketwatch\.com" (:url %)) docs))
    (is (every? #(clojure.string/includes? (:text %) "publication date not verified") docs))
    (is (every? #(<= (count (:text %)) 1800) docs))))

(deftest fresh-evidence-returns-empty-when-sidecar-is-unavailable
  (is (= [] (wr/fresh-evidence! {:ddg-connect! (fn [] (throw (ex-info "down" {})))}
                                "AAPL" (java.time.Instant/now))))
  (is (= [] (wr/fresh-evidence! {:ddg-connect! (fn [] (fake-session {:search-text "Found 0 search results:\n"
                                                                     :fetch-text ""}))}
                                "AAPL" (java.time.Instant/now)))))
