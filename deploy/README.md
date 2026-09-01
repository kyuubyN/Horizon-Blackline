# Deploy to a free Linux VM

Five systemd units (plus an optional sixth, the DuckDuckGo search sidecar),
each with `Restart=on-failure` — simpler to get right under a deadline than a
single bash supervisor. Tested against Ubuntu
22.04+, ARM64 (Oracle Cloud "Always Free" Ampere A1) or x86_64 (e.g. GCP
`e2-micro`); the Clojure CLI installer script is architecture-agnostic, but
the JDK tarball needs the right variant.

## 1. System dependencies

```bash
sudo apt-get update
sudo apt-get install -y curl git
```

## 2. Clone the repository

```bash
sudo mkdir -p /opt/horizon-blackline
sudo chown "$USER:$USER" /opt/horizon-blackline
git clone git@github.com:kyuubyN/Horizon-Blackline.git /opt/horizon-blackline
cd /opt/horizon-blackline/horizon-blackline   # adjust if the clone is already the project root
```

## 3. JDK 21 (architecture-specific variant)

```bash
ARCH=$(uname -m)
case "$ARCH" in
  aarch64) JDK_ARCH=aarch64 ;;
  x86_64)  JDK_ARCH=x64 ;;
  *) echo "unsupported architecture: $ARCH"; exit 1 ;;
esac
curl -L -o /tmp/jdk21.tar.gz \
  "https://api.adoptium.net/v3/binary/latest/21/ga/linux/${JDK_ARCH}/jdk/hotspot/normal/eclipse?project=jdk"
mkdir -p .tools/jdk
tar -xzf /tmp/jdk21.tar.gz -C .tools/jdk --strip-components=1
.tools/jdk/bin/java -version
```

`.../latest/21/...` resolves to whatever the newest JDK 21 point release is at
download time, so it is not a fixed artifact and pinning a SHA-256 for it
here would silently go stale on the next Adoptium release. For a
reproducible/audited deploy, verify manually against Adoptium's published
`SHA256SUMS` for the exact build resolved (`curl -sI ... | grep
x-amz-meta-...` or the redirect target's filename, then compare against
https://github.com/adoptium/temurin21-binaries/releases).

## 4. Clojure CLI (architecture-agnostic)

```bash
curl -O https://download.clojure.org/install/linux-install-1.12.1.1550.sh
echo "aea202cd0573d79fd8b7db1b608762645a8f93006a86bc817ec130bed1d9707d  linux-install-1.12.1.1550.sh" | sha256sum -c -
chmod +x linux-install-1.12.1.1550.sh
./linux-install-1.12.1.1550.sh --prefix "$PWD/.tools" --install-dir "$PWD/.tools/clojure"
mkdir -p .tools/clojure-config .tools/clojure-cache
rm linux-install-1.12.1.1550.sh
```

The checksum above was computed directly from the versioned artifact (this
URL is pinned to `1.12.1.1550`, unlike the JDK's `latest` endpoint, so it
should stay stable); Clojure does not publish an official sidecar checksum
for it, so recompute and compare independently if in doubt.

## 5. MCP sidecar (Python via uv)

```bash
curl -LsSf https://astral.sh/uv/install.sh | sh
export PATH="$HOME/.local/bin:$PATH"
uv venv .tools/alpaca-mcp-venv --python 3.11
uv pip install --python .tools/alpaca-mcp-venv/bin/python \
  "git+https://github.com/alpacahq/alpaca-mcp-server.git@872abbf28dab6cdde7d341fc13ac139b8002d1d9"
```

`uv` downloads its own Python 3.11 interpreter if none is present on the
system; manually installing `python3` is not required, but `sudo apt-get
install -y python3.11-venv` is the fallback if the managed interpreter
download fails due to network policy.

## 6. ProofRay sidecar (HorizonMemory)

ProofRay is the deterministic (zero-LLM) engine that verifies news evidence
before it reaches the strategy LLM. It is a separate checkout, git-ignored by
this repository — clone it on the side as
`horizon-blackline/HorizonMemory/`. `bin/run-proofray` creates the virtualenv
under `.tools/proofray-venv` on its own, on first run:

```bash
./bin/run-proofray
```

The bearer token is generated on first start and persisted at
`~/.config/proofray/api_credentials.json` (0600, tied to this machine); the
Clojure client (`horizon-blackline.adapters.proofray`) reads that file
directly, nothing needs to be copied into `.env`.

## 6b. DuckDuckGo search sidecar (optional — current open-web evidence)

Only needed when `HORIZON_WEB_RESEARCH_ENABLED=true`. It fetches current
open-web coverage (analyst ratings, price targets, recent headlines) to
complement the broker news feed, whose Benzinga coverage of thinly-traded or
non-US tickers can lag the quote by months. Results flow through the same
ProofRay → strategy-LLM chain; if the sidecar is down the tick silently falls
back to broker news only. No API key. `bin/run-ddg-mcp` creates its own
virtualenv under `.tools/ddg-mcp-venv` on first run (installs the `[browser]`
extra for Chrome-TLS impersonation, which clears most finance-site bot walls):

```bash
./bin/run-ddg-mcp   # binds 127.0.0.1:8765 by default
```

## 7. Configuration

```bash
cp .env.example .env
```

Fill in `.env` with the **Paper** account's keys (never the committed
`.env.example`). Also set `HORIZON_WATCHLIST` (e.g. `AAPL,MSFT`) and, if
measuring the official campaign, `HORIZON_OFFICIAL_ACCOUNT_ID`,
`HORIZON_OFFICIAL_WINDOW_START/END`. Leave `HORIZON_OFFICIAL_CAMPAIGN_ENABLED`
and `HORIZON_AUTONOMY_ENABLED` at `false` until ready for real autonomous
dispatch. Fill in `FEATHER_API_KEY` and/or `GEMINI_API_KEY` for the strategy
LLM (Featherless is primary, Gemini is the fallback); with neither set,
`decide-intent` never proposes an order — the loop keeps running, it just
doesn't trade, which is the expected fail-closed behavior.

**Important**: on a VM, leave `HORIZON_ORCHESTRATOR_EMBEDDED=true`. Datomic
Local holds a file lock per `DATOMIC_STORAGE_DIR` — only one process can have
the storage open at a time. With `true`, the orchestrator loop runs on a
thread inside the `horizon-blackline-api.service` process itself, instead of
a separate `orchestrator` process fighting over the same lock (that fails
with `IOException: ... .lock is in use by another process`). The
`bin/run-orchestrator` binary / `horizon-blackline-orchestrator.service` unit
still exist only for local dev/test against a disposable
`DATOMIC_STORAGE_DIR` — never run both at once pointed at the same storage.

Validate locally before installing the units:

```bash
./bin/check
```

## 8. systemd units

```bash
sudo cp deploy/horizon-blackline-*.service /etc/systemd/system/
```

Edit each copied file and adjust `User=`, `WorkingDirectory=`, and
`ExecStart=` for the real user and clone path (the files here use `horizon`
and `/opt/horizon-blackline` as an example). In the ProofRay unit, also
adjust the hardcoded `ReadWritePaths=` path (`/home/horizon/.config`) to that
user's real home — systemd's `%h` specifier did not resolve reliably on
every systemd tested (it fell back to `/root` instead of the configured
`User=`'s home), so we prefer an absolute path.

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now horizon-blackline-mcp.service
sudo systemctl enable --now horizon-blackline-proofray.service
# Optional, only with HORIZON_WEB_RESEARCH_ENABLED=true:
sudo systemctl enable --now horizon-blackline-ddg-mcp.service
sudo systemctl enable --now horizon-blackline-api.service
```

Confirm the API (with the embedded orchestrator loop) is ready:

```bash
curl -s 127.0.0.1:8080/ready
```

`horizon-blackline-campaign-monitor.service` requires
`HORIZON_OFFICIAL_CAMPAIGN_ENABLED=true` and the official-campaign env vars
(`HORIZON_OFFICIAL_ACCOUNT_ID`, `HORIZON_OFFICIAL_WINDOW_START/END`) to
already be set in `.env` — it exits with error 64 in a restart loop if any of
those are missing. It is safe to start it *before* the scoring window opens,
though: once the required vars are present, the script itself waits (logging
"Waiting for official window start: ...") until the window starts, then
captures the baseline and starts snapshotting — no crash-loop, just an idle
wait. Confirmed in practice by starting it about 18 hours ahead of the 2026
window.

```bash
sudo systemctl enable --now horizon-blackline-campaign-monitor.service
```

## 9. Logs

```bash
journalctl -u horizon-blackline-mcp.service -f
journalctl -u horizon-blackline-proofray.service -f
journalctl -u horizon-blackline-api.service -f      # the orchestrator loop lives here too
journalctl -u horizon-blackline-campaign-monitor.service -f
```

## 10. Verification

```bash
curl -s 127.0.0.1:8080/ready
./bin/doctor
```

## 11. Stopping everything safely

Trigger the kill switch through the API before touching the VM — it
preserves in-flight reconciliation and blocks new entries without killing the
process:

```bash
curl -s -X POST 127.0.0.1:8080/v1/system/freeze \
  -H 'content-type: application/json' \
  -d '{"actor":"operator","reason":"planned maintenance"}'
```

Only then stop the services, in the reverse order of startup:

```bash
sudo systemctl stop horizon-blackline-campaign-monitor.service
sudo systemctl stop horizon-blackline-api.service   # also stops the orchestrator loop
sudo systemctl stop horizon-blackline-proofray.service
sudo systemctl stop horizon-blackline-mcp.service
```
