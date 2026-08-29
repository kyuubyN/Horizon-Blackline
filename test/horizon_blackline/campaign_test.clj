(ns horizon-blackline.campaign-test
  (:require [clojure.test :refer [deftest is]]
            [horizon-blackline.campaign :as campaign]
            [horizon-blackline.persistence.datomic :as store])
  (:import (java.time Instant)
           (java.util UUID)))

(def now (Instant/parse "2026-08-31T13:30:00Z"))
(def config {:enabled? true :autonomy-enabled? true :paper? true :account-id "official-paper" :paper-account-id "official-paper"
             :expected-starting-equity "100000"
             :starts-at (Instant/parse "2026-08-31T13:30:00Z")
             :ends-at (Instant/parse "2026-09-04T13:30:00Z")})

(defn system [] {:store (store/new-store {:storage-dir :mem :system (str "campaign-" (UUID/randomUUID)) :db-name "blackline"})})

(deftest campaign-baseline-snapshots-and-autonomy-are-time-bounded
  (let [system (system)
        baseline {:account-id "official-paper" :equity "100000" :captured-at (str now) :source-digest "sha256:baseline"}
        started (campaign/capture-baseline! system config baseline now)
        updated (campaign/capture-snapshot! system config (assoc baseline :equity "100250" :captured-at "2026-09-01T13:30:00Z") (Instant/parse "2026-09-01T13:30:00Z"))]
    (is (= "100000" (:baseline-equity started)))
    (is (= "250" (:pnl (campaign/pnl updated))))
    (is (= "250" (:pnl (campaign/pnl-summary system)))
        "pnl-summary must agree with pnl despite using cheaper aggregate queries")
    (is (= 1 (:snapshot-count (campaign/pnl-summary system))))
    (is (campaign/autonomy-allowed? system config now))))

(deftest pnl-summary-is-nil-before-any-baseline-is-captured
  (is (nil? (campaign/pnl-summary (system)))))

(deftest campaign-fails-closed-for-wrong-equity-or-outside-window
  (let [system (system)
        snapshot {:account-id "official-paper" :equity "99999" :captured-at (str now) :source-digest "sha256:x"}]
    (is (thrown? clojure.lang.ExceptionInfo (campaign/capture-baseline! system config snapshot now)))
    (is (thrown? clojure.lang.ExceptionInfo (campaign/capture-baseline! system (assoc config :paper-account-id "other-paper") (assoc snapshot :equity "100000") now)))
    (is (false? (:window-active? (campaign/status config (Instant/parse "2026-09-05T13:30:00Z")))))))

(deftest account-snapshot-is-derived-from-read-only-mcp-data
  (let [snapshot (campaign/read-account-snapshot!
                  {:mcp-url "http://mcp"
                   :initialize! (fn [_] :session)
                   :call-tool! (fn [_ tool arguments]
                                 (is (= "get_account_info" tool))
                                 (is (= {} arguments))
                                 {:structuredContent {:data {:id "official-paper" :equity "100000"}}})
                   :now #(Instant/parse "2026-08-31T13:30:00Z")})]
    (is (= "official-paper" (:account-id snapshot)))
    (is (= "100000" (:equity snapshot)))
    (is (re-matches #"sha256:.+" (:source-digest snapshot)))))
