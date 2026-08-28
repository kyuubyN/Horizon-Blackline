(ns horizon-blackline.persistence.datomic
  "Datomic is the system of record for decision facts. The application derives
   BDR views from immutable entities; it does not update historical events."
  (:require [datomic.client.api :as d]
            [horizon-blackline.bdr.core :as bdr]
            [jsonista.core :as json])
  (:import (java.util UUID)))

(def mapper (json/object-mapper {:decode-key-fn keyword}))

(def schema
  [{:db/ident :bdr/id :db/valueType :db.type/uuid :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :bdr/run-id :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :bdr/correlation-id :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :bdr/created-at :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :bdr/created-by :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :bdr/state :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :bdr/sealed? :db/valueType :db.type/boolean :db/cardinality :db.cardinality/one}
   {:db/ident :bdr/seal :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :bdr/sealed-at :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :bdr/head-hash :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :bdr/events :db/valueType :db.type/ref :db/cardinality :db.cardinality/many :db/isComponent true}
   {:db/ident :event/id :db/valueType :db.type/uuid :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :event/sequence :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :event/type :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :event/occurred-at :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :event/actor :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :event/payload-schema :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :event/payload-json :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :event/prev-hash :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :event/hash :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :authorization/id :db/valueType :db.type/uuid :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :authorization/bdr :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :authorization/result :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :authorization/reason-codes :db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}
   {:db/ident :authorization/policy-bundle-id :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :authorization/input-hash :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :authorization/issued-at :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :authorization/expires-at :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :execution/id :db/valueType :db.type/uuid :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :execution/bdr :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :execution/authorization :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   {:db/ident :execution/idempotency-key :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :execution/intent-json :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :execution/mcp-tool :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :execution/client-order-id :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :execution/status :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :execution/broker-receipt-json :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :execution/created-at :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :system/id :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :system/frozen? :db/valueType :db.type/boolean :db/cardinality :db.cardinality/one}
   {:db/ident :system/freeze-actor :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :system/freeze-reason :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :system/frozen-at :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :campaign/id :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :campaign/account-id :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :campaign/starts-at :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :campaign/ends-at :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :campaign/baseline-equity :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :campaign/baseline-at :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :campaign/autonomy-enabled? :db/valueType :db.type/boolean :db/cardinality :db.cardinality/one}
   {:db/ident :campaign/snapshots :db/valueType :db.type/ref :db/cardinality :db.cardinality/many :db/isComponent true}
   {:db/ident :equity-snapshot/id :db/valueType :db.type/uuid :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :equity-snapshot/captured-at :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :equity-snapshot/equity :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :equity-snapshot/source-digest :db/valueType :db.type/string :db/cardinality :db.cardinality/one}])

(defn- uuid [value] (if (instance? UUID value) value (UUID/fromString (str value))))
(defn- default-storage-dir [] (or (System/getenv "DATOMIC_STORAGE_DIR")
                                   (str (System/getProperty "user.dir") "/.datomic")))

(defn new-store
  ([] (new-store {}))
  ([{:keys [storage-dir system db-name]
     :or {system "horizon-blackline" db-name "horizon-blackline"}}]
   (let [root (or storage-dir (default-storage-dir))
         client (d/client {:server-type :datomic-local
                           :system system
                           :storage-dir root})]
     (d/create-database client {:db-name db-name})
     (let [conn (d/connect client {:db-name db-name})]
       (d/transact conn {:tx-data schema})
       (d/transact conn {:tx-data [{:system/id "horizon-blackline"
                                    :system/frozen? false}]})
       {:client client :conn conn :db-name db-name}))))

(defn- pull-record [store bdr-id]
  (d/pull (d/db (:conn store))
          [:bdr/id :bdr/run-id :bdr/correlation-id :bdr/created-at :bdr/created-by
           :bdr/state :bdr/sealed? :bdr/seal :bdr/sealed-at
           {:bdr/events [:event/id :event/sequence :event/type :event/occurred-at
                         :event/actor :event/payload-schema :event/payload-json
                         :event/prev-hash :event/hash]}]
          [:bdr/id (uuid bdr-id)]))

(defn- event->map [event bdr-id]
  {:event-id (str (:event/id event))
   :bdr-id (str bdr-id)
   :sequence (:event/sequence event)
   :event-type (:event/type event)
   :occurred-at (:event/occurred-at event)
   :actor (:event/actor event)
   :payload-schema (:event/payload-schema event)
   :payload (json/read-value (:event/payload-json event) mapper)
   :prev-event-hash (:event/prev-hash event)
   :event-hash (:event/hash event)})

(defn get-record [store bdr-id]
  (when-let [entity (pull-record store bdr-id)]
    {:bdr-id (str (:bdr/id entity))
     :run-id (:bdr/run-id entity)
     :correlation-id (:bdr/correlation-id entity)
     :created-at (:bdr/created-at entity)
     :created-by (:bdr/created-by entity)
     :state (:bdr/state entity)
     :sealed? (:bdr/sealed? entity)
     :seal (:bdr/seal entity)
     :sealed-at (:bdr/sealed-at entity)
     :events (->> (:bdr/events entity)
                  (map #(event->map % (:bdr/id entity)))
                  (sort-by :sequence)
                  vec)}))

(defn list-records [store]
  (->> (d/q '[:find ?id
              :in $
              :where [_ :bdr/id ?id]]
            (d/db (:conn store)))
       (map first)
       (map #(get-record store %))
       (sort-by :created-at #(compare %2 %1))
       vec))

(defn create-record! [store record]
  (let [id (uuid (:bdr-id record))]
    (d/transact (:conn store)
                {:tx-data [{:bdr/id id
                            :bdr/run-id (:run-id record)
                            :bdr/correlation-id (:correlation-id record)
                            :bdr/created-at (:created-at record)
                            :bdr/created-by (:created-by record)
                            :bdr/state (:state record)
                            :bdr/sealed? false
                            :bdr/head-hash bdr/genesis-hash}]})
    (get-record store id)))

(defn append-event! [store record event]
  (let [id (uuid (:bdr-id record))
        tx-event {:event/id (uuid (:event-id event))
                  :event/sequence (long (:sequence event))
                  :event/type (:event-type event)
                  :event/occurred-at (:occurred-at event)
                  :event/actor (:actor event)
                  :event/payload-schema (:payload-schema event)
                  :event/payload-json (json/write-value-as-string (:payload event) mapper)
                  :event/prev-hash (:prev-event-hash event)
                  :event/hash (:event-hash event)}]
    (d/transact (:conn store)
                {:tx-data [[:db/cas [:bdr/id id] :bdr/head-hash
                            (:prev-event-hash event) (:event-hash event)]
                           {:db/id [:bdr/id id] :bdr/events [tx-event]}]})
    (get-record store id)))

(defn seal-record! [store record]
  (let [id (uuid (:bdr-id record))]
    (d/transact (:conn store)
                {:tx-data [[:db/cas [:bdr/id id] :bdr/sealed? false true]
                           {:db/id [:bdr/id id]
                            :bdr/seal (:seal record)
                            :bdr/sealed-at (:sealed-at record)}]})
    (get-record store id)))

(defn transition-state! [store bdr-id current-state next-state]
  (let [id (uuid bdr-id)]
    (d/transact (:conn store)
                {:tx-data [[:db/cas [:bdr/id id] :bdr/state current-state next-state]]})
    (get-record store id)))

(defn put-authorization! [store authorization]
  (d/transact (:conn store)
              {:tx-data [{:authorization/id (uuid (:authorization-id authorization))
                          :authorization/bdr [:bdr/id (uuid (:bdr-id authorization))]
                          :authorization/result (:result authorization)
                          :authorization/reason-codes (:reason-codes authorization)
                          :authorization/policy-bundle-id (:policy-bundle-id authorization)
                          :authorization/input-hash (:input-hash authorization)
                          :authorization/issued-at (:issued-at authorization)
                          :authorization/expires-at (:expires-at authorization)}]})
  authorization)

(defn get-authorization [store authorization-id]
  (when-let [entity (d/pull (d/db (:conn store))
                            [:authorization/id :authorization/result
                             :authorization/reason-codes
                             :authorization/policy-bundle-id :authorization/input-hash
                             :authorization/issued-at :authorization/expires-at
                             {:authorization/bdr [:bdr/id]}]
                            [:authorization/id (uuid authorization-id)])]
    {:authorization-id (str (:authorization/id entity))
     :bdr-id (str (get-in entity [:authorization/bdr :bdr/id]))
     :result (:authorization/result entity)
     :reason-codes (vec (:authorization/reason-codes entity))
     :policy-bundle-id (:authorization/policy-bundle-id entity)
     :input-hash (:authorization/input-hash entity)
     :issued-at (:authorization/issued-at entity)
     :expires-at (:authorization/expires-at entity)}))

(defn- execution->map [entity]
  {:execution-id (str (:execution/id entity))
   :bdr-id (str (get-in entity [:execution/bdr :bdr/id]))
   :authorization-id (str (get-in entity [:execution/authorization :authorization/id]))
   :idempotency-key (:execution/idempotency-key entity)
   :intent (json/read-value (:execution/intent-json entity) mapper)
   :mcp-tool (:execution/mcp-tool entity)
   :client-order-id (:execution/client-order-id entity)
   :status (:execution/status entity)
   :created-at (:execution/created-at entity)})

(defn get-execution-by-key [store idempotency-key]
  (when-let [entity (d/pull (d/db (:conn store))
                            [:execution/id :execution/idempotency-key :execution/intent-json
                             :execution/mcp-tool :execution/client-order-id :execution/status
                             :execution/created-at {:execution/bdr [:bdr/id]
                                                    :execution/authorization [:authorization/id]}]
                            [:execution/idempotency-key idempotency-key])]
    (execution->map entity)))

(defn get-execution [store execution-id]
  (when-let [entity (d/pull (d/db (:conn store))
                            [:execution/id :execution/idempotency-key :execution/intent-json
                             :execution/mcp-tool :execution/client-order-id :execution/status
                             :execution/created-at {:execution/bdr [:bdr/id]
                                                    :execution/authorization [:authorization/id]}]
                            [:execution/id (uuid execution-id)])]
    (execution->map entity)))

(defn create-execution! [store execution]
  (or (get-execution-by-key store (:idempotency-key execution))
      (do
        (d/transact (:conn store)
                    {:tx-data [{:execution/id (uuid (:execution-id execution))
                                :execution/bdr [:bdr/id (uuid (:bdr-id execution))]
                                :execution/authorization [:authorization/id (uuid (:authorization-id execution))]
                                :execution/idempotency-key (:idempotency-key execution)
                                :execution/intent-json (json/write-value-as-string (:intent execution) mapper)
                                :execution/mcp-tool (:mcp-tool execution)
                                :execution/client-order-id (:client-order-id execution)
                                :execution/status (:status execution)
                                :execution/created-at (:created-at execution)}]})
        (get-execution-by-key store (:idempotency-key execution)))))

(defn mark-execution! [store execution-id current-status next-status receipt]
  (let [id (uuid execution-id)]
    (d/transact (:conn store)
                {:tx-data [[:db/cas [:execution/id id] :execution/status current-status next-status]
                           {:db/id [:execution/id id]
                            :execution/broker-receipt-json (json/write-value-as-string receipt mapper)}]})
    (get-execution store id)))

(defn frozen? [store]
  (boolean (:system/frozen?
            (d/pull (d/db (:conn store)) [:system/frozen?] [:system/id "horizon-blackline"]))))

(defn system-status [store]
  (let [entity (d/pull (d/db (:conn store))
                       [:system/frozen? :system/freeze-actor :system/freeze-reason :system/frozen-at]
                       [:system/id "horizon-blackline"])]
    {:frozen? (boolean (:system/frozen? entity))
     :actor (:system/freeze-actor entity)
     :reason (:system/freeze-reason entity)
     :at (:system/frozen-at entity)}))

(defn freeze! [store freeze]
  (d/transact (:conn store)
              {:tx-data [{:db/id [:system/id "horizon-blackline"]
                          :system/frozen? true
                          :system/freeze-actor (:actor freeze)
                          :system/freeze-reason (:reason freeze)
                          :system/frozen-at (:at freeze)}]})
  freeze)

(defn get-campaign [store campaign-id]
  (when-let [entity (d/pull (d/db (:conn store))
                            [:campaign/id :campaign/account-id :campaign/starts-at :campaign/ends-at
                             :campaign/baseline-equity :campaign/baseline-at :campaign/autonomy-enabled?
                             {:campaign/snapshots [:equity-snapshot/id :equity-snapshot/captured-at
                                                   :equity-snapshot/equity :equity-snapshot/source-digest]}]
                            [:campaign/id campaign-id])]
    {:campaign-id (:campaign/id entity)
     :account-id (:campaign/account-id entity)
     :starts-at (:campaign/starts-at entity)
     :ends-at (:campaign/ends-at entity)
     :baseline-equity (:campaign/baseline-equity entity)
     :baseline-at (:campaign/baseline-at entity)
     :autonomy-enabled? (:campaign/autonomy-enabled? entity)
     :snapshots (->> (:campaign/snapshots entity)
                     (map (fn [snapshot] {:snapshot-id (str (:equity-snapshot/id snapshot))
                                          :captured-at (:equity-snapshot/captured-at snapshot)
                                          :equity (:equity-snapshot/equity snapshot)
                                          :source-digest (:equity-snapshot/source-digest snapshot)}))
                     (sort-by :captured-at)
                     vec)}))

(defn create-campaign! [store campaign]
  (d/transact (:conn store)
              {:tx-data [{:campaign/id (:campaign-id campaign)
                          :campaign/account-id (:account-id campaign)
                          :campaign/starts-at (:starts-at campaign)
                          :campaign/ends-at (:ends-at campaign)
                          :campaign/baseline-equity (:baseline-equity campaign)
                          :campaign/baseline-at (:baseline-at campaign)
                          :campaign/autonomy-enabled? (:autonomy-enabled? campaign)}]})
  (get-campaign store (:campaign-id campaign)))

(defn add-equity-snapshot! [store campaign-id snapshot]
  (let [snapshot-id (or (:snapshot-id snapshot) (str (UUID/randomUUID)))]
    (d/transact (:conn store)
                {:tx-data [{:db/id [:campaign/id campaign-id]
                            :campaign/snapshots [{:equity-snapshot/id (uuid snapshot-id)
                                                  :equity-snapshot/captured-at (:captured-at snapshot)
                                                  :equity-snapshot/equity (:equity snapshot)
                                                  :equity-snapshot/source-digest (:source-digest snapshot)}]}]})
    (get-campaign store campaign-id)))
