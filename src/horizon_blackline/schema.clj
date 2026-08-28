(ns horizon-blackline.schema
  (:require [malli.core :as m]))

(def decimal-string
  [:and string? [:fn {:error/message "must be a positive decimal string"}
                  #(try (pos? (bigdec %)) (catch Exception _ false))]])

(def uuid-string
  [:and string? [:fn {:error/message "must be a UUID string"}
                  #(try (java.util.UUID/fromString %) true (catch Exception _ false))]])

(def rfc3339-utc-string
  [:and string? [:fn {:error/message "must be an RFC 3339 timestamp"}
                  #(try (java.time.Instant/parse %) true (catch Exception _ false))]])

(def trade-intent
  [:map {:closed true}
   [:intent-id uuid-string]
   [:bdr-id uuid-string]
   [:asset-class [:enum :stock :etf :crypto :option]]
   [:symbol string?]
   [:side [:enum :buy :sell]]
   [:order-type [:enum :market :limit :stop :stop-limit]]
   [:quantity decimal-string]
   [:entry-price decimal-string]
   [:stop-price decimal-string]
   [:requested-risk-budget decimal-string]
   [:as-of rfc3339-utc-string]
   [:evidence-refs [:vector uuid-string]]])

(def risk-snapshot
  [:map {:closed true}
   [:account-id string?]
   [:equity decimal-string]
   [:buying-power decimal-string]
   [:post-trade-symbol-weight decimal-string]
   [:post-trade-gross-exposure decimal-string]
   [:estimated-participation decimal-string]
   [:daily-drawdown decimal-string]
   [:as-of rfc3339-utc-string]
   [:source-digest string?]])

(def evidence-envelope
  [:map {:closed true}
   [:source-uri string?]
   [:source-type [:enum :alpaca :news :filing :fixture]]
   [:content-hash [:and string? [:fn #(re-matches #"sha256:.+" %)]]]
   [:observed-at rfc3339-utc-string]
   [:ingested-at rfc3339-utc-string]
   [:valid-to rfc3339-utc-string]
   [:confidence [:and number? [:fn #(<= 0 % 1)]]]])

(defn valid? [schema value] (m/validate schema value))

(defn assert-valid! [schema value]
  (when-not (valid? schema value)
    (throw (ex-info "Schema validation failed" {:schema schema :value value})))
  value)
