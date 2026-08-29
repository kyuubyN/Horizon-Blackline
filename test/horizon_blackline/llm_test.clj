(ns horizon-blackline.llm-test
  (:require [clojure.test :refer [deftest is]]
            [horizon-blackline.adapters.llm :as llm]))

(def request {:system-prompt "system" :user-prompt "user"})

(defn- ok-response [content]
  {:status 200
   :body (str "{\"choices\":[{\"message\":{\"content\":" (pr-str content) "}}]}")})

(defn- truncated-response [content]
  {:status 200
   :body (str "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":"
              (pr-str content) "}}]}")})

(deftest featherless-success-never-touches-gemini
  (let [calls (atom [])
        send! (fn [request]
                (swap! calls conj (:url request))
                (ok-response "{\"direction\":\"buy\"}"))
        providers [{:name "featherless" :base-url "https://feather.test" :api-key "k" :model "m"}
                   {:name "gemini" :base-url "https://gemini.test" :api-key "k" :model "m"}]]
    (is (= "{\"direction\":\"buy\"}" (llm/complete! send! providers request)))
    (is (= 1 (count @calls)))))

(deftest featherless-failure-falls-back-to-gemini
  (let [calls (atom [])
        send! (fn [request]
                (swap! calls conj (:url request))
                (if (= (:url request) "https://feather.test")
                  {:status 500 :body "boom"}
                  (ok-response "gemini-answer")))
        providers [{:name "featherless" :base-url "https://feather.test" :api-key "k" :model "m"}
                   {:name "gemini" :base-url "https://gemini.test" :api-key "k" :model "m"}]]
    (is (= "gemini-answer" (llm/complete! send! providers request)))
    (is (= ["https://feather.test" "https://gemini.test"] @calls))))

(deftest blank-api-key-skips-provider-without-a-network-call
  (let [calls (atom [])
        send! (fn [request] (swap! calls conj (:url request)) (ok-response "gemini-answer"))
        providers [{:name "featherless" :base-url "https://feather.test" :api-key "" :model "m"}
                   {:name "gemini" :base-url "https://gemini.test" :api-key "k" :model "m"}]]
    (is (= "gemini-answer" (llm/complete! send! providers request)))
    (is (= ["https://gemini.test"] @calls))))

(deftest both-providers-failing-returns-nil-never-throws
  (let [send! (fn [_] {:status 401 :body "unauthorized"})
        providers [{:name "featherless" :base-url "https://feather.test" :api-key "k" :model "m"}
                   {:name "gemini" :base-url "https://gemini.test" :api-key "k" :model "m"}]]
    (is (nil? (llm/complete! send! providers request)))))

(deftest truncated-response-falls-back-to-the-next-provider
  (let [calls (atom [])
        send! (fn [request]
                (swap! calls conj (:url request))
                (if (= (:url request) "https://feather.test")
                  (truncated-response "<think>never finished")
                  (ok-response "gemini-answer")))
        providers [{:name "featherless" :base-url "https://feather.test" :api-key "k" :model "m"}
                   {:name "gemini" :base-url "https://gemini.test" :api-key "k" :model "m"}]]
    (is (= "gemini-answer" (llm/complete! send! providers request)))
    (is (= ["https://feather.test" "https://gemini.test"] @calls))))

(deftest malformed-response-body-is-treated-as-failure-not-an-exception
  (let [send! (fn [_] {:status 200 :body "not json"})
        providers [{:name "featherless" :base-url "https://feather.test" :api-key "k" :model "m"}]]
    (is (nil? (llm/complete! send! providers request)))))

(deftest gemini-chain-skips-forward-past-a-rate-limited-model-to-the-next
  (let [calls (atom [])
        send! (fn [request]
                (swap! calls conj (:url request))
                (case (:url request)
                  "https://gemini-1.test" {:status 429 :body "rate limited"}
                  "https://gemini-2.test" {:status 429 :body "rate limited"}
                  "https://gemini-3.test" (ok-response "third-model-answer")))
        providers [{:name "gemini-1" :base-url "https://gemini-1.test" :api-key "k" :model "m1"}
                   {:name "gemini-2" :base-url "https://gemini-2.test" :api-key "k" :model "m2"}
                   {:name "gemini-3" :base-url "https://gemini-3.test" :api-key "k" :model "m3"}]]
    (is (= "third-model-answer" (llm/complete! send! providers request)))
    (is (= 3 (count @calls)))))

(deftest parse-model-list-splits-trims-and-drops-blanks
  (is (= ["a" "b" "c"] (llm/parse-model-list "a, b ,,c" ["default"])))
  (is (= ["default"] (llm/parse-model-list "" ["default"])))
  (is (= ["default"] (llm/parse-model-list nil ["default"]))))

(deftest build-providers-puts-groq-first-only-when-its-key-is-set
  (let [env {"GROQ_API_KEY" "g" "FEATHER_API_KEY" "f" "GEMINI_API_KEY" "gm"}
        names (fn [env] (map :name (llm/build-providers (fn [k] (get env k)))))]
    (is (= "groq" (first (names env))))
    (is (= "featherless" (first (names (dissoc env "GROQ_API_KEY")))))
    (is (= "featherless" (first (names (assoc env "GROQ_API_KEY" "")))))))

(deftest build-providers-gemini-tail-follows-configured-model-list
  (let [env {"HORIZON_GEMINI_MODELS" "gemini-a, gemini-b"}
        names (map :name (llm/build-providers (fn [k] (get env k))))]
    (is (= ["gemini:gemini-a" "gemini:gemini-b"] (rest names)))))
