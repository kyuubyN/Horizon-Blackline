(ns horizon-blackline.canonical-json
  "Small, deterministic JSON representation for content entering the BDR.
   Monetary values must arrive as decimal strings; floats are rejected by
   the schemas before this boundary."
  (:require [clojure.string :as str]))

(defn- escape-string [s]
  (let [out (StringBuilder.)]
    (.append out \" )
    (doseq [c (str s)]
      (.append out
               (case c
                 \" "\\\""
                 \\ "\\\\"
                 \backspace "\\b"
                 \formfeed "\\f"
                 \newline "\\n"
                 \return "\\r"
                 \tab "\\t"
                 (if (< (int c) 32)
                   (format "\\u%04x" (int c))
                   c))))
    (.append out \" )
    (str out)))

(declare encode)

(defn- key-name [k]
  (cond (keyword? k) (name k)
        (string? k) k
        :else (str k)))

(defn- encode-map [m]
  (str "{"
       (->> m
            (sort-by (comp str key))
            (map (fn [[k v]] (str (escape-string (key-name k)) ":" (encode v))))
            (str/join ","))
       "}"))

(defn encode [value]
  (cond
    (nil? value) "null"
    (string? value) (escape-string value)
    (keyword? value) (escape-string (name value))
    (boolean? value) (if value "true" "false")
    (integer? value) (str value)
    (instance? java.math.BigDecimal value) (.toPlainString ^java.math.BigDecimal value)
    (number? value) (let [number (double value)]
                      (when (or (Double/isNaN number) (Double/isInfinite number))
                        (throw (ex-info "Non-finite canonical JSON number" {:value value})))
                      (.toPlainString (java.math.BigDecimal/valueOf number)))
    (map? value) (encode-map value)
    ;; Sets have no inherent order -- encode each element first, then sort the resulting JSON
    ;; strings so the overall encoding stays deterministic regardless of element type.
    (set? value) (str "[" (str/join "," (sort (map encode value))) "]")
    (sequential? value) (str "[" (str/join "," (map encode value)) "]")
    :else (throw (ex-info "Unsupported canonical JSON value"
                          {:value value :type (type value)}))))
