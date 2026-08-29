(ns horizon-blackline.test-runner
  (:require [clojure.test :as test]
            [horizon-blackline.alpaca-mcp-test]
            [horizon-blackline.agents-test]
            [horizon-blackline.api-test]
            [horizon-blackline.audit-test]
            [horizon-blackline.bdr-test]
            [horizon-blackline.campaign-test]
            [horizon-blackline.canonical-json-test]
            [horizon-blackline.datomic-test]
            [horizon-blackline.demo-test]
            [horizon-blackline.intelligence-test]
            [horizon-blackline.llm-test]
            [horizon-blackline.market-test]
            [horizon-blackline.orchestrator-test]
            [horizon-blackline.policy-test]
            [horizon-blackline.proofray-test]
            [horizon-blackline.readiness-test]
            [horizon-blackline.schema-test])
  (:gen-class))

(defn -main [& _]
  (let [result (test/run-tests 'horizon-blackline.alpaca-mcp-test
                               'horizon-blackline.agents-test
                               'horizon-blackline.api-test
                               'horizon-blackline.audit-test
                               'horizon-blackline.bdr-test
                               'horizon-blackline.campaign-test
                               'horizon-blackline.canonical-json-test
                               'horizon-blackline.datomic-test
                               'horizon-blackline.demo-test
                               'horizon-blackline.intelligence-test
                               'horizon-blackline.llm-test
                               'horizon-blackline.market-test
                               'horizon-blackline.orchestrator-test
                               'horizon-blackline.policy-test
                               'horizon-blackline.proofray-test
                               'horizon-blackline.readiness-test
                               'horizon-blackline.schema-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
