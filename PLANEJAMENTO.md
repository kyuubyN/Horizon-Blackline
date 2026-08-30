# Horizon Blackline — implementation plan

## Goal

Build a governed paper-trading platform for the Alpaca AI Trading Agents
Hackathon. The product demonstrates verifiable authority, not a promise of
profitability: models and agents propose; deterministic functions calculate;
Blackline authorizes; the gateway executes only against an Alpaca Paper
account.

## Confirmed decisions

- Stack: Clojure/JVM on the backend, Flutter Desktop for the operator
  console.
- Surface: an internal API and a dashboard with timeline, replay, decision,
  and kill switch.
- Integration: the Alpaca MCP v2 is an internal sidecar; no agent has direct
  access to broker tools.
- Coverage: options are the only tradeable instrument in the autonomous
  strategy (single-leg long calls/puts — see "Autonomous loop" below), with
  a schema that also still models stock/ETF/crypto for the manually
  operator-driven desktop flow.
- Intelligence: deterministic sources and critics guarantee the demo; an LLM
  adapter proposes a thesis only and never has capital authority.
- Persistence: Datomic is the transactional source of truth; the BDR event
  chain and the outbox are written before any external effect.
- Local operation: Java, Clojure, and the MCP are local processes; Docker is
  not required to develop, test, or demo the core.

## Structure and boundaries

```text
UI (Flutter Desktop) -> API/Workflow -> Capital Authority -> Blackline Authorizer
                                          |                    |
                                      Datomic BDR          Alpaca Gateway
                                                               |
                                                    MCP sidecar (internal)
                                                               |
                                                       Alpaca PAPER
```

The gateway is the only MCP client. It requires a non-expired authorization,
with `input_hash` equal to `intent_hash`, and validates the account/paper
environment before sending any order. The MCP does not publish ports to the
host. Credentials live only in its own process/container.

## Implementation milestones

1. Foundation: project setup, configuration, schemas, reason codes, and the
   base API.
2. Verifiable core: append-only BDR, hash chain, replay, state machine,
   policies, risk engines, and property tests.
3. Integration: MCP client, paper guard, outbox, idempotency, reconciliation,
   and adapters for stocks/ETFs, crypto, and options.
4. Intelligence: discovery, temporal evidence, thesis, critics, and an
   optional LLM adapter that only ever produces typed artifacts.
5. Experience: the Flutter desktop decision/replay dashboard, tamper
   detection, kill switch, and happy/denial/re-evaluation journeys.
6. Hardening: adversarial matrix, declared fixtures/mocks, observability, a
   secrets scanner, and a demo rehearsal.

## Internal public contracts

- `POST /v1/bdr`: opens a Decision Record in `DRAFT` state.
- `POST /v1/capital/evaluate`: evaluates an intent and snapshot, with no
  authority to authorize.
- `POST /v1/authorizations`: issues `ALLOW`, `DENY`, or `REVIEW` with a TTL.
- `POST /v1/executions`: gateway-only; requires a valid authorization.
- `GET /v1/bdr/{id}`: returns events, hashes, and artifacts for audit.
- `POST /v1/system/freeze`: blocks new entries and preserves reconciliation.
- `GET /ready`: shows Paper configuration gates without revealing secrets.

Every boundary uses versioned JSON, `additionalProperties=false`, decimals as
strings, and RFC 3339 UTC timestamps. Mutation requires a `correlation-id`,
actor identity, and an `idempotency-key`.

## Data and security

- `.env` is local and git-ignored; copy `.env.example` and fill in both keys.
- Datomic Local persists to `.datomic/`; a future port to Datomic Cloud will
  preserve the repository contracts without reintroducing relational CRUD.
- `ALPACA_PAPER_TRADE=true` and an allowlisted Paper account are required by
  the dispatch guard.
- The official MCP is pinned to commit
  `872abbf28dab6cdde7d341fc13ac139b8002d1d9`.
- The BDR never contains secrets, unapproved raw prompts, or sensitive data.
- Events are never updated: corrections produce a new event that references
  the previous one; chained hashes detect tampering.

## Acceptance criteria

- A paper order is only possible after a BDR, the engines, policy, and
  authorization.
- An intent that violates concentration receives `DENY` without any MCP
  call.
- Repeating the same execution never duplicates the order.
- A live endpoint/account, stale data, an expired policy, a mismatched hash,
  or an unavailable engine all fail closed.
- The dashboard tracks claim -> evidence -> critique -> calculation ->
  decision -> order/fill and can reproduce the decision from the sealed BDR.
- The suite covers stocks/ETFs, crypto, and options, plus post-submit
  timeout, partial fills, the kill switch, and tamper detection.
- The AAPL Paper validation on 2026-08-28 went through the real gateway,
  confirmed a cancellation with no fills, and preserved the initial mapping
  failure rather than rewriting it; see `docs/PAPER_TEST_RESULT.md`.
- The `MOCK` journey is deterministic, persists BDRs, and walks through
  authorization, synthetic execution, observation, monitoring,
  re-evaluation, and post-mortem. It never calls Alpaca and is labeled
  synthetic in both the API and the desktop app.

## Autonomous loop

`horizon-blackline.orchestrator` implements the loop required by the
official window (autonomous trading with no human confirmation). `tick!`
walks `HORIZON_WATCHLIST` and, for each symbol, builds a BDR, evidence,
deterministic discovery/research, its own critics (freshness, concentration,
risk), a real risk snapshot from live account/option-quote data via the MCP,
and `policy/evaluate`. On `ALLOW`, it authorizes and prepares the execution;
only then does it consult `campaign/autonomy-allowed?` to decide whether to
dispatch or leave the decision at `SUBMISSION_PENDING`. `tick-monitoring!`
observes/reconciles/re-evaluates open positions via `get_order_by_client_id`
and a real option quote, with a deterministic exit on a stop breach. Both
check `frozen?` first and isolate exceptions per symbol/record — a failure
on one symbol never brings the loop down. `decide-intent` is the sole point
that produces a candidate TradeIntent: it consumes the `:direction`/
`:confidence` from `intelligence/research!` (real news via the MCP `get_news`
tool -> deterministic verification via ProofRay -> a judgment from the
Featherless/Gemini LLM) and only proposes a trade once confidence clears
`HORIZON_MIN_CONFIDENCE`. A `buy` thesis selects a near-the-money **call**;
a `sell` thesis selects a near-the-money **put** (contract selection,
sizing, and the stop are all deterministic calculation, never model
judgment) — every position is a single-leg long option, never naked/short,
so the maximum loss is always bounded at the premium paid. The LLM has no
capital authority at all: it only fills in the fields of a `thesis` map,
which still has to pass through `decide-intent`, the critics,
`policy/evaluate`, and `authorization!` without exception — the same path as
any other TradeIntent.

- `get_all_positions`, `get_news`, `get_option_chain`, `get_option_bars`, and
  `get_option_latest_quote` were added to the MCP's read-only allowlist after
  verification against the actual running local Alpaca MCP server; the tool
  names and their input schemas were confirmed via a live `tools/list` call
  and live sample responses, not assumed.
- The risk snapshot is fail-closed: `daily-drawdown` comes from the account's
  `last_equity`/`equity` (not from `get_portfolio_history`, whose array is
  empty on new accounts), and the liquidity gate (`estimated-participation`
  in the snapshot, historically ADV participation) is now the selected
  option contract's own bid-ask spread as a fraction of mid — if that
  quote, or any other required input, is unavailable, the snapshot is marked
  invalid and policy denies.
- `decide-intent` always buys long — a bullish thesis buys a call, a bearish
  thesis buys a put; there is no short-selling or naked-writing path at all,
  by construction. A `hold` direction, confidence below the threshold, no
  contract clearing the spread/strike/expiration band, or any research
  pipeline failure (no news, ProofRay down, malformed LLM JSON, missing
  keys) all resolve to no TradeIntent for that tick — never a risky default.
  `tick-monitoring!` only ever decides `HOLD`/`EXIT` (a full exit on stop
  breach); there is no partial `REDUCE`.
- Position fields (`symbol`, `market_value`, `qty`) were assumed from
  Alpaca's known public format; the Paper account used for validation had no
  open positions, so the non-empty item format was not empirically
  confirmed.
- A decision left at `SUBMISSION_PENDING` for lack of autonomy/an inactive
  window is not automatically reprocessed once the window opens; only new
  ticks from that point on will dispatch. Avoid starting the orchestrator
  with autonomy expected before the official window actually begins.

## Explicit limits

- Stock quotes are read only through the Clojure/MCP boundary and normalized
  into `alpaca` temporal evidence, with a hash and a short validity window.
  Flutter never calls the MCP nor interprets raw output as an instruction.

Paper trading does not prove behavior with real capital. The prototype's risk
models are demonstrative limits, not financial advice or a validated
institutional framework. Live trading will not be supported.
