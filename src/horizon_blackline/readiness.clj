(ns horizon-blackline.readiness
  "Readiness intentionally reports configuration presence, never secret values.")

(defn check-config
  ([registry-valid?] (check-config registry-valid? #(System/getenv %)))
  ([registry-valid? getenv]
   (let [paper? (= "true" (getenv "ALPACA_PAPER_TRADE"))
         account? (boolean (seq (getenv "ALPACA_PAPER_ACCOUNT_ID")))
         mcp? (boolean (seq (getenv "ALPACA_MCP_URL")))
         ready? (and registry-valid? paper? account? mcp?)]
     {:ready? ready?
      :paper-only? paper?
      :agent-registry-valid? registry-valid?
      :paper-account-configured? account?
      :mcp-url-configured? mcp?
      :missing (vec (concat (when-not paper? ["ALPACA_PAPER_TRADE=true"])
                            (when-not account? ["ALPACA_PAPER_ACCOUNT_ID"])
                            (when-not mcp? ["ALPACA_MCP_URL"])
                            (when-not registry-valid? ["agent registry validity"]))) })))
