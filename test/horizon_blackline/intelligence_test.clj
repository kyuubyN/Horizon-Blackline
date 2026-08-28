(ns horizon-blackline.intelligence-test
  (:require [clojure.test :refer [deftest is]]
            [horizon-blackline.intelligence :as intelligence]))

(deftest discovery-and-research-preserve-provenance-without-prediction
  (let [discovery (intelligence/discover {:symbol "AAPL"
                                          :evidence {:source-uri "alpaca://stock/latest-quote/AAPL"
                                                     :content-hash "sha256:quote"
                                                     :observed-at "2026-08-28T12:00:00Z"}})
        thesis (intelligence/research (:candidate discovery))]
    (is (= "AAPL" (get-in discovery [:candidate :symbol])))
    (is (= "sha256:quote" (get-in thesis [:claims 0 :evidence-hash])))
    (is (= 2 (count (:limitations thesis))))))
