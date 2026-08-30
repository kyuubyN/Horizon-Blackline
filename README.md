# Horizon Blackline

Governed autonomous options trading for the Alpaca AI Trading Agents
Hackathon. See [PLANEJAMENTO.md](PLANEJAMENTO.md) for design decisions,
contracts, and acceptance criteria. See [docs/WRITEUP.md](docs/WRITEUP.md)
for the one-page summary of the AI logic, risk gates, and Alpaca
infrastructure.

## Desktop Flutter (primary UI)

The desktop console lives in `desktop_flutter/`; it talks only to the local
Clojure API and never stores or displays secrets. With the local SDK
installed, start the full experience with:

```bash
bin/run-desktop
```

The console stays permanently `PAPER ONLY`, distinguishes `MOCK` journeys
from Paper events, and lets you review BDRs, their hash chain, and the
freeze/kill-switch controls. The backend remains the authority: Datomic holds
the facts, Clojure enforces policy, and the gateway is the only boundary
allowed to talk to the Alpaca MCP.

To build a Linux distribution with this core as a local sidecar, run
`bin/package-desktop-linux`. The package ships without `.env` or credentials;
see the [desktop guide](desktop_flutter/README.md) to configure the Paper
environment.

Before a demo, run `bin/rehearse-demo`. It validates the local `paper-only`
guard and the deterministic MOCK journey (DENY, ALLOW, replay, and a sealed
BDR) without touching the account or sending an order to Alpaca.

Every BDR can also be exported from the desktop as
`horizon-blackline/audit-export@1`, containing the full record and its replay
result for independent local review.

The desktop can also create a new locally governed decision using fixture
evidence and an operator-declared snapshot. That flow produces a BDR,
challenge, and authorization, but has no ability to reach the gateway or send
an order. It can also capture a read-only quote through the MCP and turn it
into a BDR's temporal evidence. When that quote is used, deterministic
discovery and research are recorded before the critics, preserving the
candidate, thesis, claims, and limits without producing its own forecast or
authorization.

## Autonomous loop (orchestrator)

`bin/run-orchestrator` runs `horizon-blackline.orchestrator` in the
foreground, reading `HORIZON_WATCHLIST` (comma-separated symbols) every
`HORIZON_ORCHESTRATOR_POLL_SECONDS`. On every tick, for each symbol: it
captures a stock quote, builds evidence, runs `intelligence/research!` (real
news via the MCP `get_news` tool -> deterministic verification via ProofRay
-> a direction/confidence judgment from the LLM), and feeds the result to the
`decide-intent` swap point. A `buy` thesis with sufficient confidence selects
a near-the-money **call**; a `sell` thesis selects a near-the-money **put** —
every trade here is a single-leg long option (`HORIZON_MIN_CONFIDENCE`
defaults to `0.6`), never a naked/short position, so the maximum loss is
always bounded at the premium paid. A `hold` direction, low confidence, no
liquid contract within the configured strike/expiration band, or any research
pipeline failure resolves to "no trade" for that tick.

The LLM never has capital authority: it only fills in the fields of a
`thesis`. `decide-intent` remains the sole point that produces a candidate
TradeIntent, which still has to clear its own deterministic critics (evidence
freshness, concentration, risk budget), a real risk snapshot built from live
account/position/option-quote data via the MCP, `policy/evaluate`, and — if
`ALLOW` — authorization and execution preparation. Only then does it check
`campaign/autonomy-allowed?`: if true, it dispatches; if false, the decision
sits recorded at `SUBMISSION_PENDING` for manual dispatch via
`DISPATCH-PAPER`. A second, faster tick observes/reconciles/reevaluates open
positions against real broker state; a stop breach places a real, separately
authorized closing order rather than just flipping internal state. The kill
switch (`frozen?`) is checked at the top of every tick.

The strategy LLM (`horizon-blackline.adapters.llm`) tries Featherless AI
first (`FEATHER_API_KEY`) and falls back to Google Gemini (`GEMINI_API_KEY`)
when Featherless fails or is unconfigured; if neither key is set, the loop
keeps running but never proposes a trade — that is the expected fail-closed
behavior, not an error. Evidence verification
(`horizon-blackline.adapters.proofray`) depends on the local ProofRay sidecar
(`bin/run-proofray`, see [deploy/README.md](deploy/README.md)); the same
fail-closed behavior applies if it is down.

To deploy to a free Linux VM with systemd units for the MCP, API,
orchestrator, and campaign monitor, see [deploy/README.md](deploy/README.md).

## Official Paper campaign

The hackathon requires a brand-new $100,000 Paper account dedicated to this
event; the scoring window never reuses the development/test account. Set
`HORIZON_OFFICIAL_ACCOUNT_ID`, `HORIZON_OFFICIAL_WINDOW_START`, and
`HORIZON_OFFICIAL_WINDOW_END`. At the start of the window, after enabling
`HORIZON_OFFICIAL_CAMPAIGN_ENABLED=true`, run
`bin/run-official-campaign-monitor`: it records the starting-equity baseline
and periodic equity snapshots through read-only account calls only — it never
creates or sends an order itself.

`HORIZON_OFFICIAL_ACCOUNT_ID` must be identical to `ALPACA_PAPER_ACCOUNT_ID`.
The system refuses to capture a baseline, record snapshots, or allow autonomy
if the two accounts diverge.

`HORIZON_AUTONOMY_ENABLED` defaults to `false`. Even when enabled, autonomous
dispatch still requires an active campaign, a captured baseline, a valid
window, the official account, and an unfrozen system; the BDR, policy, and
gateway gates all still apply on top of that.

To verify an export without starting the API, MCP, or desktop app, run:

```bash
bin/verify-audit-export /path/to/bdr-<id>.audit.json
```

The command fails closed if the format, hash chain, declared replay, event
count, or seal are inconsistent.

## Local start, no Docker

1. Run `./bin/check` for tests and dashboard compilation.
2. Run `./bin/run-api`.
3. Open `http://localhost:8080` for the dashboard;
   `http://localhost:8080/health` remains a JSON health check.

`GET http://localhost:8080/ready` reports, without revealing secrets, whether
Paper dispatch has `ALPACA_PAPER_TRADE=true`, an allowlisted Paper account ID,
and an MCP URL configured. The server is also restricted to `127.0.0.1` in
local mode.

With the API and MCP running, `./bin/doctor` validates those gates and the
presence of the required MCP tools, without touching the account or sending
an order.

The result of an end-to-end validation against Alpaca Paper is in
[docs/PAPER_TEST_RESULT.md](docs/PAPER_TEST_RESULT.md).

The Java and Clojure runtimes live under `.tools/`, git-ignored; there is no
Docker dependency and nothing is installed globally. The BDR and
authorization facts live in Datomic Local, under `.datomic/`, also
git-ignored. The `run-api` command starts only the Clojure core; the legacy
web UI can be built on demand with `HORIZON_BUILD_WEB_UI=true`.

To start the local MCP, fill in both keys for a **Paper** account and
`ALPACA_PAPER_ACCOUNT_ID` in `.env`, then, in another terminal, run
`./bin/run-alpaca-mcp`. The server is restricted to `127.0.0.1:8001`; never
expose it to the network. An order still requires a BDR, critics, evaluation,
authorization, outbox, an allowlisted account, and an explicit
`DISPATCH-PAPER` confirmation.

For the orchestrator to use real research (news + ProofRay + LLM), also run
`./bin/run-proofray` in another terminal (it creates its own virtualenv under
`.tools/proofray-venv` on first run; restricted to `127.0.0.1:8420`) and fill
in `FEATHER_API_KEY`/`GEMINI_API_KEY` in `.env`. Without that,
`decide-intent` always resolves to "no trade" — fail-closed, and not a
requirement for the other commands (`bin/run-api`, `bin/rehearse-demo`, the
`MOCK` journey).


> The project fails closed and has no live-trading option. Paper trading is a
> simulation, not financial advice.
