(ns horizon-blackline.market-test
  (:require [clojure.test :refer [deftest is]]
            [horizon-blackline.market :as market]))

(deftest latest-quote-is-normalized-as-temporal-evidence
  (let [quote (market/latest-stock-quote!
               {:mcp-url "http://mcp.local/mcp"
                :initialize! (fn [url] {:url url})
                :call-tool! (fn [_ tool arguments]
                              (is (= "get_stock_latest_quote" tool))
                              (is (= "AAPL" (:symbols arguments)))
                              {:structuredContent {:data {:AAPL {:bid_price "100.00" :ask_price "100.10"}}}})
                :now #(java.time.Instant/parse "2026-08-28T12:00:00Z")}
               "aapl")]
    (is (= "AAPL" (:symbol quote)))
    (is (= :alpaca (get-in quote [:evidence :source-type])))
    (is (re-matches #"sha256:.+" (get-in quote [:evidence :content-hash])))
    (is (= "2026-08-28T12:01:00Z" (get-in quote [:evidence :valid-to])))))

(deftest invalid-symbol-fails-before-mcp-call
  (is (thrown? clojure.lang.ExceptionInfo
               (market/latest-stock-quote! {:mcp-url "http://mcp" :initialize! identity :call-tool! (fn [& _] (throw (ex-info "must not call" {}))) :now #(java.time.Instant/now)} "bad symbol!"))))
