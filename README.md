# Horizon Blackline

Paper trading governado para a Alpaca AI Trading Agents Hackathon. Consulte
[PLANEJAMENTO.md](PLANEJAMENTO.md) para as decisões, contratos e critérios de
aceite.

## Desktop Flutter (principal)

O console desktop fica em `desktop_flutter/`, comunica somente com a API
Clojure local e não armazena nem exibe segredos. Com o SDK local instalado,
inicie a experiência completa com:

```bash
bin/run-desktop
```

O console deixa `PAPER ONLY` permanente, diferencia jornadas `MOCK` de eventos
Paper e permite revisar BDRs, sua cadeia de hashes e os controles de freeze.
O backend permanece a autoridade: Datomic guarda os fatos, Clojure aplica a
política e o gateway é a única fronteira que pode falar ao MCP da Alpaca.

Para gerar uma distribuição Linux com esse núcleo como sidecar local, execute
`bin/package-desktop-linux`. O pacote não leva `.env` nem credenciais; consulte
o [guia do desktop](desktop_flutter/README.md) para configurar o ambiente Paper.

Antes de uma apresentação, execute `bin/rehearse-demo`. Ele valida o guard
local `paper-only` e a jornada determinística MOCK (DENY, ALLOW, replay e BDR
selado), sem consultar a conta nem enviar uma ordem à Alpaca.

Cada BDR também pode ser exportado pelo desktop como
`horizon-blackline/audit-export@1`, contendo o registro completo e o resultado
do replay para revisão local independente.

O desktop também pode criar uma nova decisão local governada usando evidência
fixture e snapshot declarado pelo operador. Esse fluxo cria BDR, challenge e
autorização, mas não tem capacidade de acionar o gateway ou enviar uma ordem.
Ele também pode capturar uma cotação somente-leitura pelo MCP e convertê-la em
evidência temporal do BDR.
Quando essa cotação é usada, descoberta e pesquisa determinísticas são
registradas antes dos críticos, preservando candidato, tese, claims e limites
sem produzir previsão ou autorização própria.

## Campanha oficial Paper

O FAQ exige uma conta Paper nova de US$100.000; não reutilize a conta de teste
na medição. Configure `HORIZON_OFFICIAL_ACCOUNT_ID`, `HORIZON_OFFICIAL_WINDOW_START`
e `HORIZON_OFFICIAL_WINDOW_END`. No início da janela, após ativar
`HORIZON_OFFICIAL_CAMPAIGN_ENABLED=true`, execute
`bin/run-official-campaign-monitor`: ele registra baseline e equity periódica
somente por leitura de conta, sem criar ou enviar ordens.

`HORIZON_OFFICIAL_ACCOUNT_ID` deve ser idêntico a `ALPACA_PAPER_ACCOUNT_ID`.
O sistema recusa baseline, snapshots e autonomia se as duas contas divergirem.

`HORIZON_AUTONOMY_ENABLED` permanece `false` por padrão. Mesmo ativado, o
dispatch autônomo exige campanha ativa, baseline, janela válida, conta oficial
e sistema não congelado; BDR, política e gateway continuam obrigatórios.

Para verificar uma exportação sem iniciar a API, MCP ou o desktop, execute:

```bash
bin/verify-audit-export /caminho/para/bdr-<id>.audit.json
```

O comando falha fechado se o formato, cadeia de hashes, replay declarado,
contagem de eventos ou selo não forem consistentes.

## Início local, sem Docker

1. Execute `./bin/check` para testes e compilação do painel.
2. Execute `./bin/run-api`.
3. Abra `http://localhost:8080` para o painel; `http://localhost:8080/health`
   permanece como health check JSON.

`GET http://localhost:8080/ready` informa, sem revelar segredos, se o dispatch
Paper possui `ALPACA_PAPER_TRADE=true`, ID allowlisted da conta Paper e URL MCP.
O servidor também fica restrito a `127.0.0.1` no modo local.

Com a API e o MCP em execução, `./bin/doctor` valida esses gates e a presença
das ferramentas MCP necessárias, sem consultar conta ou enviar ordens.

O resultado da validação end-to-end em Alpaca Paper está em
[docs/PAPER_TEST_RESULT.md](docs/PAPER_TEST_RESULT.md).

Os runtimes Java e Clojure ficam em `.tools/`, ignorado pelo Git; não há
dependência de Docker nem instalação global. O BDR e os fatos de autorização
ficam em Datomic Local, no diretório `.datomic/`, também ignorado pelo Git. O
comando `run-api` inicia apenas o núcleo Clojure; o legado web pode ser
compilado sob demanda com `HORIZON_BUILD_WEB_UI=true`.

Para iniciar o MCP local, preencha as duas chaves de uma conta **Paper** e
`ALPACA_PAPER_ACCOUNT_ID` em `.env` e, em outro terminal, execute
`./bin/run-alpaca-mcp`. O servidor fica restrito a `127.0.0.1:8001`; não o
exponha à rede. Uma ordem ainda exige BDR, críticos, avaliação, autorização,
outbox, conta allowlisted e confirmação explícita `DISPATCH-PAPER`.


> O projeto falha fechado e não possui opção de live trading. Paper trading é
> uma simulação e não uma recomendação financeira.
