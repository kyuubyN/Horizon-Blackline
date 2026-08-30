(ns horizon-blackline.ui.app)

(defn by-id [id] (.getElementById js/document id))
(declare start)

(defn escape-html [value]
  (-> (str value)
      (.replaceAll "&" "&amp;") (.replaceAll "<" "&lt;") (.replaceAll ">" "&gt;")
      (.replaceAll "\"" "&quot;") (.replaceAll "'" "&#39;")))

(defn bdr-row [record]
  (str "<li><button class='bdr-button' data-bdr-id='" (escape-html (aget record "bdr-id")) "'><code>" (escape-html (aget record "bdr-id")) "</code></button><span>"
       (escape-html (aget record "state")) "</span></li>"))

(defn show-replay [replay]
  (set! (.-textContent (by-id "replay-result"))
        (str "Integrity: " (if (aget replay "valid?") "VALID" "INVALID")
             " | events: " (count (array-seq (aget replay "events"))))))

(defn freeze-system []
  (when (js/confirm "Freeze new entries on Paper trading?")
    (let [reason (or (js/prompt "Kill switch reason:") "operator-request")]
      (-> (js/fetch "/v1/system/freeze"
                    #js {:method "POST"
                         :headers #js {"Content-Type" "application/json"}
                         :body (js/JSON.stringify #js {"actor" "local-operator" "reason" reason})})
          (.then (fn [_] (start)))))))

(defn unfreeze-system []
  (when (js/confirm "Unfreeze the system and allow new entries again?")
    (let [reason (or (js/prompt "Unfreeze reason:") "operator-request")]
      (-> (js/fetch "/v1/system/unfreeze"
                    #js {:method "POST"
                         :headers #js {"Content-Type" "application/json"}
                         :body (js/JSON.stringify #js {"actor" "local-operator" "reason" reason
                                                        "operator-confirmation" "UNFREEZE"})})
          (.then (fn [_] (start)))))))

(defn render [health system records]
  (set! (.-innerHTML (by-id "app"))
        (str "<main><header><p class='eyebrow'>HORIZON BLACKLINE</p>"
             "<h1>Verifiable capital authority</h1>"
             "<p>Models propose. Engines calculate. Blackline authorizes. Alpaca executes.</p></header>"
             "<section class='status'><span class='dot'></span><strong>PAPER ONLY</strong>"
             "<span>API: " (if (= "ok" (aget health "status")) "online" "unavailable") "</span></section>"
             "<section class='freeze'><strong>Kill switch: " (if (aget system "frozen?") "ACTIVE" "ready") "</strong>"
             (when (aget system "frozen?") (str " <span>" (escape-html (aget system "reason")) "</span>"))
             (if (aget system "frozen?")
               "<button id='unfreeze-button'>Unfreeze</button>"
               "<button id='freeze-button'>Freeze entries</button>")
             "</section>"
             "<section class='grid'><article><h2>Decision</h2><p>" (count (array-seq records)) " BDR(s) persisted in Datomic.</p></article>"
             "<article><h2>Capital Authority</h2><p>Risk, sizing, and concentration are deterministic.</p></article>"
             "<article><h2>Execution</h2><p>The gateway uses an idempotent outbox and the MCP paper-only.</p></article></section>"
             "<section class='timeline'><h2>Governed journey</h2><ol><li>DISCOVER</li><li>RESEARCH</li><li>CHALLENGE</li><li>AUTHORIZE</li><li>EXECUTE</li><li>OBSERVE</li></ol>"
             "<h3>Recent BDRs</h3><ul class='bdrs'>" (apply str (map bdr-row (array-seq records))) "</ul>"
             "<p id='replay-result' class='hint'>Select a BDR to verify its hash chain.</p>"
             "<button id='demo-button'>Run demo journey (MOCK)</button>"
             "<p class='hint'>This screen never sends orders. It only displays BDRs and gateway receipts.</p></section></main>"))
  (.addEventListener (by-id "demo-button") "click"
                     (fn [_]
                       (.then (js/fetch "/v1/demo/run" #js {:method "POST"})
                              (fn [_] (start)))))
  (when-let [button (by-id "freeze-button")]
    (.addEventListener button "click" (fn [_] (freeze-system))))
  (when-let [button (by-id "unfreeze-button")]
    (.addEventListener button "click" (fn [_] (unfreeze-system))))
  (doseq [element (array-seq (.querySelectorAll js/document "[data-bdr-id]"))]
    (.addEventListener element "click"
                       (fn [event]
                         (let [bdr-id (.getAttribute (.-currentTarget event) "data-bdr-id")]
                           (.then (js/fetch (str "/v1/bdr/" bdr-id "/replay"))
                                  (fn [response]
                                    (.then (.json response) show-replay))))))))

(defn start []
  (-> (js/Promise.all #js [(-> (js/fetch "/health") (.then #(.json %)))
                            (-> (js/fetch "/v1/system") (.then #(.json %)))
                            (-> (js/fetch "/v1/bdr") (.then #(.json %)))])
      (.then (fn [result] (render (aget result 0) (aget result 1) (aget result 2))))
      (.catch (fn [_] (render #js {"status" "offline"} #js {"frozen?" true} #js [])))))

(set! (.-onload js/window) start)
