# Horizon Blackline — one-page write-up

**Models propose. Engines calculate. Blackline authorizes. Alpaca executes.**

Horizon Blackline is a governed autonomous options-trading agent built on
Alpaca's Paper Trading API and MCP server. No LLM ever has direct authority to
place an order — every trade passes through a deterministic risk engine and an
immutable, hash-chained audit record before it reaches Alpaca.

## AI logic

Each watchlist symbol is ticked on a fixed interval. An LLM (Featherless
primary, Gemini as automatic fallback on rate-limit or outage) reads a
retrieved-evidence bundle and returns a directional thesis — `buy`, `sell`, or
`hold` — with a confidence score and stated key risks. The evidence itself is
retrieved and verified by ProofRay/HorizonMemory, a separate, deterministic
(zero-LLM) retrieval engine: it grounds the thesis in real, timestamped news
sources rather than letting the model recall or invent facts.

The LLM's output is a proposal only: a thesis, never a trade. A `hold` or
below-threshold-confidence thesis produces nothing. A `buy` thesis selects a
call; a `sell` thesis selects a put — options only, always bought long (never
written naked), so every position's maximum loss is bounded at the premium
paid the moment it is opened. Contract selection (near-the-money strike,
14–45 days to expiration, live two-sided quote within an acceptable bid-ask
spread) is deterministic code, not the model.

## Risk gates

Every candidate trade becomes a Blackline Decision Record (BDR): an
append-only, SHA-256 hash-chained sequence of events (evidence captured,
thesis researched, critics challenged, authorization issued, execution
observed, position monitored, post-mortem recorded). BDRs are never edited,
only appended to, and are independently replay-verifiable.

Before authorization, a purely deterministic policy engine evaluates the
proposed trade against real, live account state — not cached or assumed data:

- **Risk budget** — max dollar loss at stop, scaled for the option contract's
  100-share multiplier.
- **Concentration / gross exposure** — post-trade position weight and
  portfolio-wide exposure as a fraction of live equity.
- **Liquidity** — the selected contract's own bid-ask spread as a fraction of
  mid; wide-spread contracts are rejected before an order is ever built.
- **Drawdown** — a hard daily-drawdown circuit breaker.

The system is fail-closed throughout: any missing, stale, or unobtainable
input (a quote that never arrives, an account snapshot that fails to load) is
treated as a denial, never a default. A single operator-controlled kill switch
freezes all new authorizations instantly and survives process restarts,
verified by regression test. Autonomous dispatch itself is gated a second
time, independently of the policy evaluation above, by an official-campaign
check that requires an exact account-ID match and an active scoring window —
so the agent cannot dispatch against the wrong account even if every other
gate passes.

Positions are monitored on their own faster loop, independent of discovery
latency: a stop breach places a real, separately-authorized closing order
(never just an internal state flip), and — deliberately — that closing order
is never held on the liquidity gate, since blocking an exit on a wide spread
would trap capital in a losing position instead of de-risking it.

## Alpaca infrastructure

All broker access goes through Alpaca's official MCP server, run as a local
sidecar with a hard tool allowlist enforced in code: only a handful of
read/order tools are reachable at all, regardless of what an agent might
otherwise attempt. The dispatcher itself is the single choke point for every
broker call — it re-verifies the authorization's hash, TTL, and account
allowlist immediately before submission, and claims the execution via a
Datomic compare-and-swap so two concurrent dispatch attempts can never both
reach the broker for the same decision.

The account used for the official scoring window is a fresh paper account
created specifically for this hackathon, starting at exactly $100,000 equity,
untouched before the scoring window began. An official-campaign monitor
captures that starting-equity baseline automatically at market open and
records an equity snapshot every 60 seconds through market close, independent
of whether the trading agent itself finds anything worth trading that day.

## Limitations

Paper trading only — no code path exists for live capital. This validates
governed autonomous decision-making and execution, not investment performance
or advice.
