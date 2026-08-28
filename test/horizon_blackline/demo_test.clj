(ns horizon-blackline.demo-test
  (:require [clojure.test :refer [deftest is]]
            [horizon-blackline.demo.core :as demo]
            [horizon-blackline.persistence.datomic :as store]
            [horizon-blackline.bdr.core :as bdr])
  (:import (java.util UUID)))

(deftest demo-preserves-an-explicit-denial-and-allow
  (let [system {:store (store/new-store {:storage-dir :mem
                                         :system (str "demo-" (UUID/randomUUID))
                                         :db-name "blackline"})}
        result (demo/run-demo! system)]
    (is (= :MOCK (:environment result)))
    (is (= :DENY (get-in result [:denied :authorization :result])))
    (is (= :ALLOW (get-in result [:authorized :authorization :result])))
    (is (= 2 (count (store/list-records (:store system)))))
    (let [record (store/get-record (:store system) (get-in result [:lifecycle :bdr-id]))]
      (is (= :POST_MORTEM_COMPLETE (:state record)))
      (is (:sealed? record))
      (is (bdr/verify record))
      (is (every? (set (map :event-type (:events record)))
                  #{:EXECUTION_SUBMISSION_PENDING :EXECUTION_SUBMITTED :BROKER_OBSERVED
                    :MONITORING_STARTED :POSITION_REEVALUATED :POST_MORTEM_RECORDED})))))
