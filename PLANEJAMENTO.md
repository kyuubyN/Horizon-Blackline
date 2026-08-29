# Horizon Blackline — plano de implementação

## Objetivo

Construir uma plataforma de paper trading governado para a Alpaca AI Trading
Agents Hackathon. O produto demonstra autoridade verificável, e não promessa
de rentabilidade: modelos e agentes propõem; funções determinísticas calculam;
Blackline autoriza; o gateway executa somente em uma conta Alpaca Paper.

## Decisões confirmadas

- Stack: Clojure/JVM no backend e Flutter Desktop no console operacional.
- Superfície: API interna e painel com timeline, replay, decisão e kill switch.
- Integração: o Alpaca MCP v2 é um sidecar interno; nenhum agente possui acesso
  direto a ferramentas do broker.
- Cobertura: ações/ETFs, cripto e opções desde o início, com contratos e
  políticas específicos por classe de ativo.
- Inteligência: fontes e críticos determinísticos garantem a demo; um adaptador
  de LLM é opcional e não possui autoridade de capital.
- Persistência: Datomic é a fonte de fatos transacional; a cadeia de eventos
  BDR e a outbox são gravadas antes de efeitos externos.
- Operação local: Java, Clojure e MCP são processos locais; Docker não é
  requisito para desenvolver, testar ou demonstrar o núcleo.

## Estrutura e fronteiras

```text
UI (Flutter Desktop) -> API/Workflow -> Capital Authority -> Blackline Authorizer
                                          |                    |
                                      Datomic BDR          Alpaca Gateway
                                                               |
                                                    MCP sidecar (interno)
                                                               |
                                                       Alpaca PAPER
```

O gateway é o único cliente MCP. Ele exige uma autorização não expirada, com
`input_hash` igual ao `intent_hash`, e valida a conta/ambiente paper antes de
enviar qualquer ordem. O MCP não publica portas para o host. Credenciais ficam
somente no seu processo/container.

## Marcos de implementação

1. Fundação: projeto, configuração, Compose, schemas, reason codes e API base.
2. Núcleo verificável: BDR append-only, hash chain, replay, state machine,
   políticas, engines de risco e testes de propriedades.
3. Integração: cliente MCP, paper guard, outbox, idempotência, reconciliação e
   adapters para ações/ETFs, cripto e opções.
4. Inteligência: discovery, evidência temporal, tese, críticos e adaptador LLM
   opcional que produz somente artefatos tipados.
5. Experiência: painel desktop Flutter de decisão, replay, tamper detection, kill switch e
   jornadas happy/denial/re-evaluation.
6. Endurecimento: matriz adversarial, fixtures/mock declarado, observabilidade,
   scanner de segredos e ensaio de demo.

## Contratos públicos internos

- `POST /v1/bdr`: abre um Decision Record em estado `DRAFT`.
- `POST /v1/capital/evaluate`: avalia intent e snapshot, sem poder autorizar.
- `POST /v1/authorizations`: emite `ALLOW`, `DENY` ou `REVIEW` com TTL.
- `POST /v1/executions`: somente gateway; exige autorização válida.
- `GET /v1/bdr/{id}`: devolve eventos, hashes e artefatos para auditoria.
- `POST /v1/system/freeze`: impede novas entradas e preserva reconciliação.
- `GET /ready`: mostra gates de configuração Paper sem revelar segredos.

Todos os limites usam JSON versionado, `additionalProperties=false`, decimais
como strings e timestamps RFC 3339 UTC. Mutação requer `correlation-id`,
identidade de ator e `idempotency-key`.

## Dados e segurança

- `.env` é local e ignorado; copiar `.env.example` e preencher as duas chaves.
- Datomic Local persiste em `.datomic/`; uma futura porta para Datomic Cloud
  preservará os contratos de repositório, sem reintroduzir CRUD relacional.
- `ALPACA_PAPER_TRADE=true` e uma conta Paper allowlisted são obrigatórios no
  guard de dispatch.
- O MCP oficial é fixado no commit `872abbf28dab6cdde7d341fc13ac139b8002d1d9`.
- O BDR nunca contém segredos, prompts brutos não aprovados ou dados sensíveis.
- Eventos não são atualizados: correções geram novo evento que referencia o
  anterior; hashes encadeados detectam adulteração.

## Critérios de aceite

- Uma ordem paper só é possível após BDR, engines, policy e autorização.
- Uma intenção que viola concentração recebe `DENY` sem chamada MCP.
- Repetir a mesma execução não duplica a ordem.
- Endpoint/conta live, dados vencidos, policy expirada, hash divergente ou engine
  indisponível falham fechados.
- O painel rastreia claim -> evidence -> critique -> calculation -> decision ->
  order/fill e reproduz a decisão a partir do BDR selado.
- A suíte cobre ações/ETFs, cripto e opções, além de timeout pós-submit, fills
  parciais, kill switch e tamper detection.
- A validação Paper de AAPL em 2026-08-28 percorreu o gateway real, confirmou
  cancelamento sem fills e preservou a falha inicial de mapeamento; consulte
  `docs/PAPER_TEST_RESULT.md`.
- A jornada `MOCK` é determinística, persiste BDRs e percorre autorização,
  execução sintética, observação, monitoramento, reavaliação e post-mortem.
  Ela nunca chama a Alpaca e é rotulada como sintética na API e no desktop.

## Loop autônomo

`horizon-blackline.orchestrator` implementa o loop exigido pela janela oficial
(trading autônomo sem confirmação humana). `tick!` percorre `HORIZON_WATCHLIST`
e para cada símbolo cria BDR, evidência, descoberta/pesquisa determinísticas,
críticos próprios (frescor, concentração, risco), risk-snapshot real via MCP
(`get_account_info`, `get_all_positions`, `get_stock_bars`) e `policy/evaluate`.
Em `ALLOW`, autoriza e prepara a execução; só então consulta
`campaign/autonomy-allowed?` para decidir se despacha ou deixa em
`SUBMISSION_PENDING`. `tick-monitoring!` observa/reconcilia/reavalia posições
abertas via `get_order_by_client_id` e cotação real, com saída determinística
por rompimento de stop. Ambos checam `frozen?` primeiro e isolam exceções por
símbolo/registro — um símbolo com falha não derruba o loop. `decide-intent` é
o único ponto que produz um TradeIntent candidato: hoje ele consome o
`:direction`/`:confidence` de `intelligence/research!` (notícias reais via MCP
`get_news` -> verificação determinística via ProofRay -> julgamento do LLM
Featherless/Gemini) e só propõe `buy` ou `sell` quando a confiança atinge
`HORIZON_MIN_CONFIDENCE`; tamanho por notional fixo e stop percentual fixo
continuam sendo cálculo determinístico, não julgamento do modelo. O LLM não
tem autoridade de capital: ele só preenche campos de um `thesis` map que ainda
precisa passar por `decide-intent`, críticos, `policy/evaluate` e
`authorization!` sem exceção — o mesmo caminho que qualquer outro TradeIntent.

- `get_all_positions`, `get_stock_bars` e `get_news` foram adicionados ao
  allowlist somente-leitura do MCP após verificação contra o servidor Alpaca
  MCP local em execução; os nomes foram confirmados via `tools/list` real, não
  supostos.
- O risk-snapshot é fail-closed: `daily-drawdown` vem de `last_equity`/`equity`
  da conta (não de `get_portfolio_history`, cujo array fica vazio em contas
  novas) e `estimated-participation` vem de `get_stock_bars` (média de volume
  de 5 dias, feed `iex`); se qualquer uma dessas leituras falhar, o snapshot
  é marcado inválido e a política nega.
- `decide-intent` agora propõe `buy` ou `sell` conforme a direção do thesis;
  não há lógica de venda a descoberto além do `side :sell` padrão de mercado
  (o gateway/MCP tratam isso como qualquer ordem de venda). Direção `hold`,
  confiança abaixo do limiar, ou qualquer falha no pipeline de pesquisa
  (sem notícia, ProofRay fora do ar, JSON do LLM malformado, chaves ausentes)
  resolvem para nenhum TradeIntent neste tick — nunca um default arriscado.
  `tick-monitoring!` só decide `HOLD`/`EXIT` (saída total ao romper o stop);
  não há `REDUCE` parcial.
- Os campos de posição (`symbol`, `market_value`, `qty`) foram assumidos pelo
  formato público conhecido da Alpaca; a conta Paper usada para validação
  estava sem posições abertas, então o formato de item não-vazio não foi
  confirmado empiricamente.
- Uma decisão que fica em `SUBMISSION_PENDING` por falta de autonomia/janela
  não é reprocessada automaticamente quando a janela abre; só novos ticks
  a partir daquele momento despacham. Evite iniciar o orquestrador com
  autonomia esperada antes do início da janela oficial.

## Limites explícitos

- Cotações de ações são lidas somente pela fronteira Clojure/MCP e normalizadas
  em evidência temporal `alpaca`, com hash e validade curta. O Flutter não
  chama o MCP nem interpreta saída bruta como instrução.

Paper trading não comprova comportamento com capital real. Os modelos de risco
do protótipo são limites demonstrativos, não aconselhamento financeiro nem
framework institucional validado. Não haverá suporte a live trading.
