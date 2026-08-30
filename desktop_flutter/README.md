# Horizon Blackline Desktop

Flutter Desktop client for the local console. It never receives Alpaca keys and never talks to the broker: it only communicates with the Clojure API at `127.0.0.1:8080`.

## Development

From the project root, run `bin/run-desktop`. The launcher starts the local core and opens the Linux client. To point at a different local core, set `HORIZON_API_URL` before starting Flutter.

## Linux bundle

Run `bin/package-desktop-linux` from the root. The bundle at
`build/linux/x64/release/bundle/` includes the Flutter executable and the
Clojure/JVM sidecar. When the executable opens, it uses an already-available
API at `127.0.0.1:8080` or starts only the `backend/bin/run-api` shipped in
the same bundle.

The sidecar persists Datomic at `XDG_DATA_HOME/horizon-blackline` (or at
`HORIZON_DATA_DIR`) and does not ship `.env`. Configure the Paper credentials
in the environment or copy `backend/.env.example` to `backend/.env` before
using the Alpaca MCP. Without that configuration the app stays safe: health
checks and the MOCK demo still work, but the dispatch gate stays unavailable.

## Governed operation

On a BDR's detail page, the desktop only exposes the local transitions
allowed by its current state: start monitoring, record `HOLD`, close, and
attach a post-mortem. Submitting, canceling, and reconciling a Paper order
all remain in the Clojure/MCP gateway, with an explicit confirmation and
every authorization guard — there is no shortcut to send an order to the
broker from the UI.

Beyond the raw timeline, every BDR presents a decision narrative through the
evidence, critics, authorization, observation, and post-mortem stages. That
narrative is derived from the append-only events; the original payload
remains available for audit.

The **Run MOCK journey** button opens a guided three-act narrative: the
concentration denial, the governed authorization, and the sealed synthetic
cycle. Each act links to its corresponding BDR. The journey is explicitly
synthetic and never calls Alpaca.

In the **BDRs** section, use the search box to find a symbol, strategy, or ID,
and the state filter to separate denials, records under monitoring, and
completed decisions. These filters are local to the UI and never alter the
history.

The **New decision** button creates a local BDR, records `fixture` evidence,
challenges it with the three critics, and issues a deterministic
authorization. It clearly flags that the data is local/synthetic and never
touches the gateway, MCP, or broker. Optionally, the operator can fetch a
stock quote through the local MCP; it is captured as `alpaca` temporal
evidence before the BDR is created. That lookup is read-only and still does
not authorize or send an order. With that quote, the core also records
deterministic candidate discovery and research (thesis, claims, and
limitations) before the challenge. Neither step forecasts anything or has
capital authority.

The **Agents** section reads the local workload registry and shows their
scopes. It is operational evidence of least privilege: the manifests never
include `authorize`, `alpaca:submit`, or `policy:write`.

The overview shows local metrics derived from the BDRs: number of seals,
valid replays, and auditable events. None of it sends telemetry or decision
content off the device.

On a BDR's detail page, the download icon generates a local
`horizon-blackline/audit-export@1` proof: the full BDR plus its replay. By
default it lands in `~/Documents/Horizon Blackline`; set
`HORIZON_EXPORT_DIR` to use a managed directory instead. The export never
includes `.env` or secrets. It can be verified on another machine with
`bin/verify-audit-export <file.audit.json>`; the verifier never starts the
API, the MCP, or any network connection.

## Official campaign

The **Campaign** section only shows the safe status of the window, the
baseline, and the equity ledger. There is no activation control in the UI:
configure the `HORIZON_OFFICIAL_*` variables in the environment or `.env`,
keep the official account separate from the test account, and run
`bin/run-official-campaign-monitor` at the start of the window. The monitor
only ever reads equity; it never creates a decision or sends an order.

The configured official account must be the same Paper account allowlisted
by the gateway; a mismatch blocks the monitor and any autonomous dispatch.

The distribution package must include the JVM core as a local sidecar. The UI
still has no way to unlock live mode; the paper-only guard and authorization
remain in the Clojure core.
