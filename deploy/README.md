# Deploy em VM Linux gratuita

Cinco unidades systemd separadas, cada uma com `Restart=on-failure` — mais
simples de acertar sob prazo do que um supervisor bash único. Testado como
alvo Ubuntu 22.04+, ARM64 (Oracle Cloud "Always Free" Ampere A1) ou x86_64
(ex.: GCP `e2-micro`); o script de instalação do Clojure CLI é agnóstico de
arquitetura, mas o tarball do JDK precisa da variante correta.

## 1. Dependências do sistema

```bash
sudo apt-get update
sudo apt-get install -y curl git
```

## 2. Clonar o repositório

```bash
sudo mkdir -p /opt/horizon-blackline
sudo chown "$USER:$USER" /opt/horizon-blackline
git clone git@github.com:kyuubyN/Horizon-Blackline.git /opt/horizon-blackline
cd /opt/horizon-blackline/horizon-blackline   # ajuste se o clone já for a raiz do projeto
```

## 3. JDK 21 (variante por arquitetura)

```bash
ARCH=$(uname -m)
case "$ARCH" in
  aarch64) JDK_ARCH=aarch64 ;;
  x86_64)  JDK_ARCH=x64 ;;
  *) echo "arquitetura não suportada: $ARCH"; exit 1 ;;
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

## 4. Clojure CLI (agnóstico de arquitetura)

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

## 5. Sidecar MCP (Python via uv)

```bash
curl -LsSf https://astral.sh/uv/install.sh | sh
export PATH="$HOME/.local/bin:$PATH"
uv venv .tools/alpaca-mcp-venv --python 3.11
uv pip install --python .tools/alpaca-mcp-venv/bin/python \
  "git+https://github.com/alpacahq/alpaca-mcp-server.git@872abbf28dab6cdde7d341fc13ac139b8002d1d9"
```

`uv` baixa seu próprio interpretador Python 3.11 se não houver um no sistema;
não é necessário instalar `python3` manualmente, mas `sudo apt-get install -y
python3.11-venv` é o fallback caso o download do interpretador gerenciado
falhe por política de rede.

## 6. Sidecar ProofRay (HorizonMemory)

ProofRay é o motor determinístico (zero LLM) que verifica a evidência de
notícias antes de ela chegar ao LLM de estratégia. É um checkout separado,
ignorado pelo Git deste repositório — clone-o à parte como
`horizon-blackline/HorizonMemory/`. `bin/run-proofray` cria o virtualenv em
`.tools/proofray-venv` sozinho, na primeira execução:

```bash
./bin/run-proofray
```

O token bearer é gerado no primeiro start e persistido em
`~/.config/proofray/api_credentials.json` (0600, ligado a esta máquina); o
cliente Clojure (`horizon-blackline.adapters.proofray`) lê esse arquivo
diretamente, nada precisa ser copiado para o `.env`.

## 7. Configuração

```bash
cp .env.example .env
```

Preencha `.env` com as chaves da conta **Paper** (nunca a `.env` do commit).
Configure também `HORIZON_WATCHLIST` (ex.: `AAPL,MSFT`) e, se for medir a
campanha oficial, `HORIZON_OFFICIAL_ACCOUNT_ID`,
`HORIZON_OFFICIAL_WINDOW_START/END`. Deixe `HORIZON_OFFICIAL_CAMPAIGN_ENABLED`
e `HORIZON_AUTONOMY_ENABLED` em `false` até estar pronto para o dispatch
autônomo real. Preencha `FEATHER_API_KEY` e/ou `GEMINI_API_KEY` para o LLM de
estratégia (Featherless é primário, Gemini é fallback); sem nenhuma das duas,
`decide-intent` nunca propõe uma ordem — o loop continua rodando, só não
opera, o que é o comportamento fail-closed esperado.

**Importante**: em VM, deixe `HORIZON_ORCHESTRATOR_EMBEDDED=true`. O Datomic
Local segura um lock de arquivo por `DATOMIC_STORAGE_DIR` — só um processo
pode ter o storage aberto por vez. Com `true`, o loop do orquestrador roda
numa thread dentro do próprio processo `horizon-blackline-api.service`, em
vez de um processo `orchestrator` separado brigando pelo mesmo lock (isso
falha com `IOException: ... .lock is in use by another process`). O binário
`bin/run-orchestrator`/unidade `horizon-blackline-orchestrator.service`
continuam existindo só para dev/teste local contra um `DATOMIC_STORAGE_DIR`
descartável — nunca ligue os dois ao mesmo tempo apontando pro mesmo storage.

Valide localmente antes de instalar as unidades:

```bash
./bin/check
```

## 8. Unidades systemd

```bash
sudo cp deploy/horizon-blackline-*.service /etc/systemd/system/
```

Edite cada arquivo copiado e ajuste `User=`, `WorkingDirectory=` e
`ExecStart=` para o usuário e caminho reais do clone (os arquivos aqui usam
`horizon` e `/opt/horizon-blackline` como exemplo).

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now horizon-blackline-mcp.service
sudo systemctl enable --now horizon-blackline-proofray.service
sudo systemctl enable --now horizon-blackline-api.service
```

Confirme que a API (com o loop do orquestrador embutido) está pronta:

```bash
curl -s 127.0.0.1:8080/ready
```

`horizon-blackline-campaign-monitor.service` só deve ser ligada quando
`HORIZON_OFFICIAL_CAMPAIGN_ENABLED=true` estiver configurado no `.env` — o
script sai com erro 64 em loop de restart até isso ser verdade, então ligue-a
apenas perto do início da janela oficial:

```bash
sudo systemctl enable --now horizon-blackline-campaign-monitor.service
```

## 9. Logs

```bash
journalctl -u horizon-blackline-mcp.service -f
journalctl -u horizon-blackline-proofray.service -f
journalctl -u horizon-blackline-api.service -f      # loop do orquestrador está aqui também
journalctl -u horizon-blackline-campaign-monitor.service -f
```

## 10. Verificação

```bash
curl -s 127.0.0.1:8080/ready
./bin/doctor
```

## 11. Parar tudo com segurança

Acione o kill switch pela API antes de tocar na VM — ele preserva a
reconciliação em andamento e impede novas entradas sem matar o processo:

```bash
curl -s -X POST 127.0.0.1:8080/v1/system/freeze \
  -H 'content-type: application/json' \
  -d '{"actor":"operator","reason":"manutenção planejada"}'
```

Só então pare os serviços, na ordem inversa da inicialização:

```bash
sudo systemctl stop horizon-blackline-campaign-monitor.service
sudo systemctl stop horizon-blackline-api.service   # também para o loop do orquestrador
sudo systemctl stop horizon-blackline-proofray.service
sudo systemctl stop horizon-blackline-mcp.service
```
