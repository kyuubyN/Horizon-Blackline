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
        (str "Integridade: " (if (aget replay "valid?") "VALIDA" "INVALIDA")
             " | eventos: " (count (array-seq (aget replay "events"))))))

(defn freeze-system []
  (when (js/confirm "Congelar novas entradas em Paper trading?")
    (let [reason (or (js/prompt "Motivo do kill switch:") "operator-request")]
      (-> (js/fetch "/v1/system/freeze"
                    #js {:method "POST"
                         :headers #js {"Content-Type" "application/json"}
                         :body (js/JSON.stringify #js {"actor" "local-operator" "reason" reason})})
          (.then (fn [_] (start)))))))

(defn unfreeze-system []
  (when (js/confirm "Descongelar o sistema e voltar a permitir novas entradas?")
    (let [reason (or (js/prompt "Motivo do unfreeze:") "operator-request")]
      (-> (js/fetch "/v1/system/unfreeze"
                    #js {:method "POST"
                         :headers #js {"Content-Type" "application/json"}
                         :body (js/JSON.stringify #js {"actor" "local-operator" "reason" reason
                                                        "operator-confirmation" "UNFREEZE"})})
          (.then (fn [_] (start)))))))

(defn render [health system records]
  (set! (.-innerHTML (by-id "app"))
        (str "<main><header><p class='eyebrow'>HORIZON BLACKLINE</p>"
             "<h1>Autoridade de capital verificável</h1>"
             "<p>Modelos propõem. Engines calculam. Blackline autoriza. Alpaca executa.</p></header>"
             "<section class='status'><span class='dot'></span><strong>PAPER ONLY</strong>"
             "<span>API: " (if (= "ok" (aget health "status")) "online" "indisponível") "</span></section>"
             "<section class='freeze'><strong>Kill switch: " (if (aget system "frozen?") "ATIVO" "pronto") "</strong>"
             (when (aget system "frozen?") (str " <span>" (escape-html (aget system "reason")) "</span>"))
             (if (aget system "frozen?")
               "<button id='unfreeze-button'>Descongelar</button>"
               "<button id='freeze-button'>Congelar entradas</button>")
             "</section>"
             "<section class='grid'><article><h2>Decisão</h2><p>" (count (array-seq records)) " BDR(s) persistidos no Datomic.</p></article>"
             "<article><h2>Capital Authority</h2><p>Risco, sizing e concentração são determinísticos.</p></article>"
             "<article><h2>Execução</h2><p>O gateway usa outbox idempotente e MCP somente em paper.</p></article></section>"
             "<section class='timeline'><h2>Jornada governada</h2><ol><li>DISCOVER</li><li>RESEARCH</li><li>CHALLENGE</li><li>AUTHORIZE</li><li>EXECUTE</li><li>OBSERVE</li></ol>"
             "<h3>BDRs recentes</h3><ul class='bdrs'>" (apply str (map bdr-row (array-seq records))) "</ul>"
             "<p id='replay-result' class='hint'>Selecione um BDR para verificar a cadeia de hashes.</p>"
             "<button id='demo-button'>Gerar jornada demo (MOCK)</button>"
             "<p class='hint'>Esta tela não envia ordens. Ela exibirá BDRs e recibos do gateway.</p></section></main>"))
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
