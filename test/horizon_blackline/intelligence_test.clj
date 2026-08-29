(ns horizon-blackline.intelligence-test
  (:require [clojure.test :refer [deftest is]]
            [horizon-blackline.intelligence :as intelligence]))

(def sample-quote
  {:symbol "AAPL"
   :data {:quotes {:AAPL {:ap "100.00" :bp "99.90"}}}
   :evidence {:source-uri "alpaca://stock/latest-quote/AAPL"
              :content-hash "sha256:quote"
              :observed-at "2026-08-28T12:00:00Z"}})

(defn- candidate [] (:candidate (intelligence/discover sample-quote)))

(deftest discovery-and-research-preserve-provenance-without-prediction
  (let [discovery (intelligence/discover sample-quote)
        thesis (intelligence/research (:candidate discovery))]
    (is (= "AAPL" (get-in discovery [:candidate :symbol])))
    (is (= "sha256:quote" (get-in thesis [:claims 0 :evidence-hash])))
    (is (= "hold" (:direction thesis)))
    (is (= 0.0 (:confidence thesis)))
    (is (= 2 (count (:limitations thesis))))))

(defn- news-deps [& {:keys [news proofray-state sources llm-response]
                     :or {news [{:headline "Apple beats estimates" :summary "Strong quarter."
                                :source "test-wire" :created_at "2026-08-28T12:00:00Z"}]
                          proofray-state "resolved"
                          sources [{:text "Apple beats estimates" :source "doc:1" :relevance_score 0.9}]
                          llm-response "{\"direction\":\"buy\",\"confidence\":0.85,\"reasoning\":\"strong earnings\",\"key_risks\":[\"guidance risk\"]}"}}]
  {:fetch-news! (fn [_symbol] {:items news})
   :ask-proofray! (fn [_question _documents] {:state proofray-state :sources sources})
   :complete-llm! (fn [_request] llm-response)})

(deftest research-bang-produces-a-directional-thesis-from-verified-evidence
  (let [thesis (intelligence/research! (news-deps) (candidate) sample-quote)]
    (is (= "buy" (:direction thesis)))
    (is (= 0.85 (:confidence thesis)))
    (is (= ["guidance risk"] (:key-risks thesis)))
    (is (= 1 (count (:sources thesis))))))

(deftest research-bang-holds-when-no-news-is-available
  (let [thesis (intelligence/research! (news-deps :news []) (candidate) sample-quote)]
    (is (= "hold" (:direction thesis)))
    (is (= 0.0 (:confidence thesis)))))

(deftest research-bang-holds-when-proofray-does-not-resolve
  (let [thesis (intelligence/research! (news-deps :proofray-state "abstained") (candidate) sample-quote)]
    (is (= "hold" (:direction thesis)))))

(deftest research-bang-holds-when-llm-json-is-malformed
  (let [thesis (intelligence/research! (news-deps :llm-response "not json") (candidate) sample-quote)]
    (is (= "hold" (:direction thesis)))))

(deftest research-bang-parses-json-wrapped-in-a-think-block
  (let [response (str "\n<think>\nlots of reasoning here, not json at all, ignore me\n</think>\n\n"
                       "{\"direction\":\"sell\",\"confidence\":0.7,\"reasoning\":\"weak guidance\",\"key_risks\":[]}")
        thesis (intelligence/research! (news-deps :llm-response response) (candidate) sample-quote)]
    (is (= "sell" (:direction thesis)))
    (is (= 0.7 (:confidence thesis)))))

(deftest research-bang-parses-json-wrapped-in-markdown-fences
  (let [response "```json\n{\"direction\":\"buy\",\"confidence\":0.6,\"reasoning\":\"ok\",\"key_risks\":[]}\n```"
        thesis (intelligence/research! (news-deps :llm-response response) (candidate) sample-quote)]
    (is (= "buy" (:direction thesis)))
    (is (= 0.6 (:confidence thesis)))))

(deftest research-bang-holds-when-llm-returns-nil
  (let [deps (assoc (news-deps) :complete-llm! (fn [_] nil))
        thesis (intelligence/research! deps (candidate) sample-quote)]
    (is (= "hold" (:direction thesis)))))

(deftest research-bang-never-throws-when-a-dependency-throws
  (let [deps (assoc (news-deps) :fetch-news! (fn [_] (throw (ex-info "mcp down" {}))))
        thesis (intelligence/research! deps (candidate) sample-quote)]
    (is (= "hold" (:direction thesis)))))
