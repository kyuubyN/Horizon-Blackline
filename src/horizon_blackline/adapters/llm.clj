(ns horizon-blackline.adapters.llm
  "Chat-completion client: Featherless AI primary, Google Gemini fallback (its OpenAI-compatible
   endpoint, same request/response shape as Featherless so one code path serves both). This
   client has zero capital authority of its own -- complete! only ever returns raw completion
   text or nil; every caller here treats nil as 'no thesis this tick', same as any other missing
   input. complete! never throws: a bad key, a timeout, an HTTP error or a malformed response are
   all just reasons to fall back, and if both providers fail the caller gets nil. Logging here is
   one-line-per-attempt only -- never an API key, never a full prompt or response body."
  (:require [clojure.string :as str]
            [jsonista.core :as json])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.time Duration)))

(def mapper (json/object-mapper {:decode-key-fn keyword}))

(defn- log! [& args] (apply println "[llm]" args))

(defn- send-http! [{:keys [url headers body]}]
  (let [client (HttpClient/newBuilder)
        builder (-> (HttpRequest/newBuilder (URI/create url))
                    (.timeout (Duration/ofSeconds 30))
                    (.header "Content-Type" "application/json"))
        builder (reduce (fn [request [header value]] (.header request header value)) builder headers)
        request (-> builder (.POST (HttpRequest$BodyPublishers/ofString body)) (.build))
        response (.send (.build client) request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response) :body (.body response)}))

(defn- chat-completion! [send! {:keys [base-url api-key model]} {:keys [system-prompt user-prompt]}]
  (when (str/blank? api-key)
    (throw (ex-info "missing api key" {})))
  (when (str/blank? model)
    (throw (ex-info "missing model id" {})))
  (let [response (send! {:url base-url
                         :headers {"Authorization" (str "Bearer " api-key)}
                         :body (json/write-value-as-string
                                {:model model
                                 :messages [{:role "system" :content system-prompt}
                                            {:role "user" :content user-prompt}]
                                 :temperature 0.2
                                 ;; Generous: reasoning models (e.g. Qwen thinking mode) can spend
                                 ;; thousands of tokens on a <think> block before the final JSON --
                                 ;; too low a cap truncates mid-thought and the answer never arrives.
                                 :max_tokens 8192}
                                mapper)})]
    (when-not (<= 200 (:status response) 299)
      (throw (ex-info "http error" {:status (:status response)})))
    (let [payload (json/read-value (:body response) mapper)
          content (get-in payload [:choices 0 :message :content])
          finish-reason (get-in payload [:choices 0 :finish_reason])]
      (when (str/blank? content)
        (throw (ex-info "empty completion" {})))
      ;; "length" means max_tokens cut the response off mid-thought -- the JSON (if any) is
      ;; unreliable, so fail this provider now and let the caller try the next one in the chain.
      (when (= "length" finish-reason)
        (throw (ex-info "completion truncated by max_tokens" {:finish-reason finish-reason})))
      content)))

(defn- try-provider [send! {:keys [name] :as config} request]
  (try
    (let [content (chat-completion! send! config request)]
      (log! name "ok")
      content)
    (catch Exception e
      (log! name "failed:" (.getSimpleName (class e)) (some-> (.getMessage e)))
      nil)))

(defn- some-of [getenv name]
  (let [value (getenv name)]
    (when-not (str/blank? value) value)))

(def default-gemini-models
  "Newest/most-capable first, Flash-Lite variants last -- each is a distinct model with its own
   rate-limit bucket, so a 429 on one just moves to the next instead of failing the tick."
  ["gemini-3.7-flash" "gemini-3.6-flash" "gemini-3.5-flash" "gemini-3.5-flash-lite" "gemini-3.1-flash-lite"])

(defn parse-model-list [value default]
  (if (str/blank? value)
    default
    (->> (str/split value #",") (map str/trim) (remove str/blank?) vec)))

(defn- gemini-provider [getenv model]
  {:name (str "gemini:" model)
   :base-url "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
   :api-key (getenv "GEMINI_API_KEY")
   :model model})

(defn- groq-provider [getenv]
  (when (some-of getenv "GROQ_API_KEY")
    {:name "groq"
     :base-url "https://api.groq.com/openai/v1/chat/completions"
     :api-key (getenv "GROQ_API_KEY")
     :model (or (some-of getenv "HORIZON_GROQ_MODEL") "openai/gpt-oss-120b")}))

(defn build-providers
  "getenv is injectable for testing; defaults to the real environment. GROQ_API_KEY presence
   puts Groq first -- a free/cheap key for testing the pipeline without spending Featherless
   credit. Unset it to go back to Featherless-first."
  ([] (build-providers #(System/getenv %)))
  ([getenv]
   (into (vec (keep identity [(groq-provider getenv)
                               {:name "featherless"
                                :base-url "https://api.featherless.ai/v1/chat/completions"
                                :api-key (getenv "FEATHER_API_KEY")
                                :model (or (some-of getenv "HORIZON_FEATHER_MODEL") "Qwen/Qwen3.8-27B")}]))
         (map (partial gemini-provider getenv)
              (parse-model-list (some-of getenv "HORIZON_GEMINI_MODELS") default-gemini-models)))))

(defn default-providers [] (build-providers))

(defn complete!
  "request is {:system-prompt string :user-prompt string}. Tries providers in order, returns the
   first non-blank completion string, or nil if every provider fails/is unconfigured."
  ([request] (complete! send-http! (default-providers) request))
  ([send! providers request]
   (some #(try-provider send! % request) providers)))
