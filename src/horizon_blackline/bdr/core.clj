(ns horizon-blackline.bdr.core
  (:require [horizon-blackline.canonical-json :as canonical])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.time Instant)
           (java.util HexFormat UUID)))

(def genesis-hash (apply str (repeat 64 "0")))

(defn sha256 [value]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.formatHex (HexFormat/of) (.digest digest (.getBytes (str value) StandardCharsets/UTF_8)))))

(defn new-record [{:keys [run-id correlation-id actor now]
                   :or {actor "system" now (Instant/now)}}]
  {:bdr-id (str (UUID/randomUUID))
   :run-id run-id
   :correlation-id correlation-id
   :created-at (str now)
   :events []
   :sealed? false
   :created-by actor})

(defn- event-with-hash [record {:keys [event-type actor payload payload-schema now]
                                 :or {now (Instant/now)}}]
  (let [sequence (inc (count (:events record)))
        prev-event-hash (or (:event-hash (last (:events record))) genesis-hash)
        event (cond-> {:event-id (str (UUID/randomUUID))
                       :bdr-id (:bdr-id record)
                       :sequence sequence
                       :event-type event-type
                       :occurred-at (str now)
                       :actor actor
                       :payload-schema payload-schema
                       :payload payload
                       :prev-event-hash prev-event-hash}
                (nil? actor) (assoc :actor "system"))]
    (assoc event :event-hash (sha256 (str (canonical/encode event) prev-event-hash)))))

(defn append-event [record event]
  (when (:sealed? record)
    (throw (ex-info "A sealed BDR cannot be changed" {:bdr-id (:bdr-id record)})))
  (let [next-event (event-with-hash record event)]
    (update record :events conj next-event)))

(defn seal [record]
  (when (:sealed? record)
    (throw (ex-info "BDR is already sealed" {:bdr-id (:bdr-id record)})))
  (let [head (or (:event-hash (last (:events record))) genesis-hash)]
    (assoc record :sealed? true :seal head :sealed-at (str (Instant/now)))))

(defn verify [record]
  (let [events (:events record)
        valid-events?
        (every? true?
                (map-indexed
                 (fn [index event]
                   (let [prior (if (zero? index) genesis-hash
                                   (:event-hash (nth events (dec index))))
                         unsigned (dissoc event :event-hash)]
                     (and (= (inc index) (:sequence event))
                          (= prior (:prev-event-hash event))
                          (= (:event-hash event)
                             (sha256 (str (canonical/encode unsigned) prior))))))
                 events))]
    (and valid-events?
         (or (not (:sealed? record))
             (= (:seal record) (or (:event-hash (last events)) genesis-hash))))))
