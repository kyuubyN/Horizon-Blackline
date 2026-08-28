(ns horizon-blackline.audit-test
  (:require [clojure.test :refer [deftest is]]
            [horizon-blackline.audit :as audit]
            [horizon-blackline.bdr.core :as bdr]))

(defn- exported-record []
  (let [record (-> (bdr/new-record {:run-id "audit" :correlation-id "audit" :actor "test"})
                   (bdr/append-event {:event-type :EVIDENCE_CAPTURED
                                      :actor "test"
                                      :payload-schema "evidence@1"
                                      :payload {:source-uri "fixture://audit"}})
                   bdr/seal)]
    {:format audit/export-format
     :bdr record
     :replay {:valid? true :event-count (count (:events record)) :seal (:seal record)}}))

(deftest offline-audit-export-verification-fails-closed
  (is (:valid? (audit/verify-export (exported-record))))
  (is (false? (:valid? (audit/verify-export (assoc-in (exported-record) [:replay :event-count] 99)))))
  (is (false? (:valid? (audit/verify-export (assoc-in (exported-record) [:bdr :events 0 :payload :source-uri] "tampered"))))))
