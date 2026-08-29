(ns horizon-blackline.proofray-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [horizon-blackline.adapters.proofray :as proofray]))

(deftest ask-sends-bearer-token-and-parses-response
  (let [requests (atom [])
        send! (fn [request]
                (swap! requests conj request)
                {:status 201
                 :body "{\"state\":\"resolved\",\"answer\":\"a\",\"sources\":[{\"text\":\"t\",\"source\":\"doc:1\",\"relevance_score\":0.9}]}"})
        response (proofray/ask! send! {:base-url "http://127.0.0.1:8420" :token "tok"}
                                "is AAPL bullish?" ["doc one" "doc two"])]
    (is (= "http://127.0.0.1:8420/v1/answers" (:url (first @requests))))
    (is (= "Bearer tok" (get-in (first @requests) [:headers "Authorization"])))
    (is (= "resolved" (:state response)))
    (is (= 1 (count (:sources response))))))

(deftest missing-token-fails-closed-without-a-network-call
  (let [called? (atom false)
        send! (fn [_] (reset! called? true) {:status 200 :body "{}"})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (proofray/ask! send! {:base-url "http://127.0.0.1:8420" :token nil}
                                "q" ["doc"])))
    (is (false? @called?))))

(deftest non-2xx-status-throws
  (let [send! (fn [_] {:status 500 :body "server error"})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (proofray/ask! send! {:base-url "http://127.0.0.1:8420" :token "tok"}
                                "q" ["doc"])))))

(deftest read-token-reads-the-persisted-credentials-file
  (let [file (java.io.File/createTempFile "proofray-credentials" ".json")]
    (try
      (spit file "{\"token\":\"abc123\",\"machine_fingerprint\":\"x\"}")
      (is (= "abc123" (proofray/read-token! (.getPath file))))
      (finally (io/delete-file file true)))))

(deftest read-token-returns-nil-when-file-is-absent
  (is (nil? (proofray/read-token! "/nonexistent/path/credentials.json"))))
