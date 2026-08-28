# Horizon Blackline Desktop

Cliente Flutter Desktop do console local. Ele nunca recebe chaves da Alpaca e nunca fala com a corretora: comunica somente com a API Clojure em `127.0.0.1:8080`.

## Desenvolvimento

Na raiz do projeto, execute `bin/run-desktop`. O launcher sobe o nucleo local e abre o cliente Linux. Para apontar a outro nucleo local, defina `HORIZON_API_URL` antes de iniciar o Flutter.

## Bundle Linux

Execute `bin/package-desktop-linux` na raiz. O bundle em
`build/linux/x64/release/bundle/` inclui o executável Flutter e o sidecar
Clojure/JVM. Ao abrir o executável, ele usa uma API já disponível em
`127.0.0.1:8080` ou inicia apenas o `backend/bin/run-api` que veio no mesmo
bundle.

O sidecar persiste o Datomic em `XDG_DATA_HOME/horizon-blackline` (ou em
`HORIZON_DATA_DIR`) e não inclui `.env`. Configure as credenciais Paper no
ambiente ou copie `backend/.env.example` para `backend/.env` antes de usar o
MCP da Alpaca. Sem essa configuração o app continua seguro: health e a demo
MOCK funcionam, mas o gate de dispatch permanece indisponível.

## Operação governada

No detalhe de um BDR, o desktop expõe somente as transições locais permitidas
pelo estado atual: iniciar monitoramento, registrar `HOLD`, encerrar e anexar
post-mortem. A submissão, o cancelamento e a reconciliação de uma ordem Paper
continuam no gateway Clojure/MCP, com confirmação explícita e todas as guardas
de autorização; não existe atalho de envio ao broker pela interface.

Além da timeline bruta, cada BDR apresenta uma leitura de decisão com os
estágios de evidência, críticos, autorização, observação e post-mortem. Essa
leitura é derivada dos eventos append-only; o payload original continua
disponível para auditoria.

O botão **Rodar jornada MOCK** abre uma narrativa guiada em três atos: a recusa
por concentração, a autorização governada e o ciclo sintético selado. Cada ato
leva ao BDR correspondente. A jornada é explicitamente sintética e nunca chama
a Alpaca.

Na seção **BDRs**, use a busca para localizar símbolo, estratégia ou ID e o
filtro de estado para separar recusas, registros em monitoramento e decisões
concluídas. Esses filtros são locais à interface e não alteram o histórico.

O botão **Nova decisão** cria um BDR local, registra evidência `fixture`,
challenge com os três críticos e uma autorização determinística. Ele identifica
que os dados são locais/sintéticos e não aciona gateway, MCP ou corretora.
Opcionalmente, o operador pode consultar uma cotação de ação pelo MCP local;
ela é capturada como evidência temporal `alpaca` antes de criar o BDR. Essa
consulta é somente leitura e ainda não autoriza ou envia ordens.
Com essa cotação, o núcleo também registra descoberta de candidato e pesquisa
determinística (tese, claim e limitações) antes do challenge. Essas etapas não
fazem previsão e não têm autoridade de capital.

A seção **Agentes** lê o registro local de workloads e mostra seus escopos.
Ela é uma evidência operacional de menor privilégio: os manifests não incluem
`authorize`, `alpaca:submit` nem `policy:write`.

A visão geral exibe métricas locais derivadas dos BDRs: quantidade de selos,
replays válidos e eventos auditáveis. Elas não enviam telemetria nem conteúdo
de decisão para fora do dispositivo.

No detalhe de um BDR, o ícone de download gera uma prova local
`horizon-blackline/audit-export@1`: o BDR completo e seu replay. Por padrão ela
fica em `~/Documents/Horizon Blackline`; defina `HORIZON_EXPORT_DIR` para usar
um diretório gerenciado. A exportação não inclui `.env` nem segredos.
Ela pode ser verificada em outra máquina com
`bin/verify-audit-export <arquivo.audit.json>`; o verificador não inicia API,
MCP ou conexão de rede.

## Campanha oficial

A seção **Campanha** exibe apenas o status seguro da janela, baseline e ledger
de equity. A ativação não existe na UI: configure as variáveis `HORIZON_OFFICIAL_*`
no ambiente ou `.env`, mantenha a conta oficial separada da conta de teste e
use `bin/run-official-campaign-monitor` no início da janela. O monitor só lê
equity; ele não cria decisões nem envia ordens.

A conta oficial configurada precisa ser a mesma conta Paper allowlisted pelo
gateway; divergência bloqueia o monitor e qualquer dispatch autônomo.

O empacotamento de distribuicao deve incluir o nucleo JVM como sidecar local. A interface continua sem capacidade de liberar modo live; a guarda paper-only e a autorizacao permanecem no Clojure.
