(ns horizon-blackline.audit
  "Offline verification for portable BDR audit exports. No persistence, network,
   MCP client, or runtime configuration is needed to verify a file."
  (:require [horizon-blackline.bdr.core :as bdr]))

(def export-format "horizon-blackline/audit-export@1")

(defn verify-export [export]
  (let [record (:bdr export)
        replay (:replay export)
        format-valid? (= export-format (:format export))
        record-present? (map? record)
        events-present? (vector? (:events record))
        chain-valid? (and record-present? events-present? (bdr/verify record))
        declared-valid? (= chain-valid? (:valid? replay))
        count-valid? (= (count (:events record)) (:event-count replay))
        seal-valid? (= (:seal record) (:seal replay))
        valid? (and format-valid? record-present? events-present? chain-valid?
                    declared-valid? count-valid? seal-valid?)]
    {:valid? valid?
     :format (:format export)
     :bdr-id (:bdr-id record)
     :event-count (count (:events record))
     :checks {:format format-valid?
              :record-present record-present?
              :events-present events-present?
              :hash-chain chain-valid?
              :declared-replay declared-valid?
              :event-count count-valid?
              :seal seal-valid?}}))
