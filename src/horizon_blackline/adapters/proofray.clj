(ns horizon-blackline.adapters.proofray
  "Minimal HTTP client for the local ProofRay sidecar (HorizonMemory's deterministic,
   zero-LLM answer engine). Localhost-only; every call sends fresh caller-supplied documents
   -- ProofRay indexes nothing on its own. This is Q&A over caller-supplied text, not a tool
   that can be handed free-text agent instructions, so it carries no injection surface of its
   own; callers are still responsible for not treating ProofRay's returned text as instructions."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jsonista.core :as json])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.time Duration)))

(def mapper (json/object-mapper {:decode-key-fn keyword}))

(def ^:private shared-client
  (delay (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 10)) (.build))))

(defn- send-http! [{:keys [url headers body]}]
  (let [builder (-> (HttpRequest/newBuilder (URI/create url))
                    (.timeout (Duration/ofSeconds 30))
                    (.header "Content-Type" "application/json"))
        builder (reduce (fn [request [header value]] (.header request header value)) builder headers)
        request (-> builder (.POST (HttpRequest$BodyPublishers/ofString body)) (.build))
        response (.send @shared-client request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response) :body (.body response)}))

(defn credentials-path []
  (or (System/getenv "PROOFRAY_API_CREDENTIALS_PATH")
      (System/getenv "HORIZON_API_CREDENTIALS_PATH")
      (str (System/getProperty "user.home") "/.config/proofray/api_credentials.json")))

(defn read-token!
  "Reads the bearer token ProofRay's server.py generates on first run (see
   HorizonMemory/api/machine_auth.py). Returns nil, never throws, when the file is absent."
  ([] (read-token! (credentials-path)))
  ([path]
   (try
     (let [file (io/file path)]
       (when (.exists file)
         (:token (json/read-value (slurp file) mapper))))
     (catch Exception _ nil))))

(defn ask!
  "Asks ProofRay a question over caller-supplied documents. documents is a vector of plain
   strings (pre-chunked by the caller to stay under ProofRay's per-document/body size caps).
   polish stays false: Horizon Blackline's own LLM does the reasoning/writing, so ProofRay's
   optional polish step would be a redundant second LLM hop. Throws on any failure (missing
   token, HTTP error, malformed body) -- callers in this codebase catch and fail closed to
   'no trade', matching every other MCP-adjacent adapter here."
  ([config question documents] (ask! send-http! config question documents))
  ([send! {:keys [base-url token]} question documents]
   (when (str/blank? token)
     (throw (ex-info "ProofRay bearer token is not configured" {:reason-code :PAPER_ENV_REQUIRED})))
   (when (str/blank? base-url)
     (throw (ex-info "ProofRay base URL is not configured" {:reason-code :PAPER_ENV_REQUIRED})))
   (let [response (send! {:url (str base-url "/v1/answers")
                          :headers {"Authorization" (str "Bearer " token)}
                          :body (json/write-value-as-string
                                 {:question question :documents documents
                                  :include_sources true :polish false} mapper)})]
     (when-not (<= 200 (:status response) 299)
       (throw (ex-info "ProofRay request failed" {:status (:status response) :body (:body response)})))
     (json/read-value (:body response) mapper))))
