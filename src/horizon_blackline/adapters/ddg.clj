(ns horizon-blackline.adapters.ddg
  "Client for a local DuckDuckGo MCP sidecar (github.com/nickclyde/duckduckgo-mcp-server)
   run with `--transport streamable-http`. Two tools are used, both strictly read-only:

     search(query, max_results)  -> newline-formatted list of {title, url, snippet}
     fetch_content(url, max_length) -> cleaned main-text of one page

   The sidecar itself has no market or capital authority; it only returns external web text.
   Every string that comes back is UNTRUSTED third-party content -- callers must treat it as
   data to summarise/verify (via ProofRay + the strategy LLM), never as instructions."
  (:require [clojure.string :as str]
            [horizon-blackline.adapters.mcp-http :as mcp]))

(def ^:private default-url "http://127.0.0.1:8765/mcp")
(def ^:private default-timeout-seconds 20)

(defn connect!
  "Opens an MCP session to the DDG sidecar. Throws if it is unreachable."
  ([] (connect! (or (System/getenv "HORIZON_DDG_MCP_URL") default-url)))
  ([base-url] (connect! mcp/send-http! base-url))
  ([send! base-url]
   (mcp/initialize! send! base-url {:timeout-seconds default-timeout-seconds})))

(defn- parse-search-block
  "The server returns results as a numbered block:

     1. Title
        URL: https://...
        Summary: ...

   Parse it back into maps. Lenient: anything it cannot line up is skipped."
  [text]
  (->> (str/split (str text) #"\n(?=\d+\.\s)")
       (keep (fn [chunk]
               (let [title (some-> (re-find #"^\s*\d+\.\s+(.+)" chunk) second str/trim)
                     url (some-> (re-find #"(?im)^\s*URL:\s*(\S+)" chunk) second str/trim)
                     summary (some-> (re-find #"(?is)Summary:\s*(.+?)\s*$" chunk) second str/trim)]
                 (when (and title url (str/starts-with? url "http"))
                   {:title title :url url :snippet (or summary "")}))))
       vec))

(defn search!
  "Runs one web search. Returns a vector of {:title :url :snippet}. Never throws -- any
   transport/tool failure yields []."
  [session query {:keys [max-results] :or {max-results 8}}]
  (try
    (-> (mcp/call-tool! session "search"
                        {:query query :max_results (max 1 (min 20 max-results))})
        mcp/result-text
        parse-search-block)
    (catch Exception _ [])))

(defn fetch-content!
  "Fetches the cleaned main text of one page (default cap 6000 chars). Returns the string,
   or nil on failure / on the server's own 'Error: ...' sentinel."
  [session url {:keys [max-length] :or {max-length 6000}}]
  (try
    (let [text (-> (mcp/call-tool! session "fetch_content"
                                   {:url url :max_length max-length})
                   mcp/result-text)]
      (when (and (seq text) (not (str/starts-with? (str/triml text) "Error:")))
        text))
    (catch Exception _ nil)))
