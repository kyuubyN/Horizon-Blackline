(ns horizon-blackline.bdr-test
  (:require [clojure.test :refer [deftest is]]
            [horizon-blackline.bdr.core :as bdr]))

(deftest sealed-record-detects-tampering
  (let [record (-> (bdr/new-record {:run-id "run-1" :correlation-id "corr-1"})
                   (bdr/append-event {:event-type :INTENT_CREATED
                                      :actor "orchestrator"
                                      :payload-schema "trade_intent@1"
                                      :payload {:symbol "AAPL" :quantity "1"}})
                   bdr/seal)]
    (is (bdr/verify record))
    (is (not (bdr/verify (assoc-in record [:events 0 :payload :quantity] "2"))))))
