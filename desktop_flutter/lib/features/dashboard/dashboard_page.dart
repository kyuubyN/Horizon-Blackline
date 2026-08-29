import 'package:flutter/material.dart';

import '../../models/bdr.dart';
import '../../services/backend_lifecycle.dart';
import '../../services/horizon_api.dart';
import '../bdr/bdr_detail_page.dart';
import '../decision/new_decision_dialog.dart';
import 'brand_mark.dart';

class DashboardPage extends StatefulWidget {
  const DashboardPage({super.key, required this.api});
  final HorizonApi api;

  @override
  State<DashboardPage> createState() => _DashboardPageState();
}

class _DashboardPageState extends State<DashboardPage> {
  late Future<_DashboardData> _data;
  late BackendLifecycle _backend;
  late TextEditingController _searchController;
  bool _working = false;
  int _section = 0;
  String _stateFilter = 'TODOS';

  @override
  void initState() {
    super.initState();
    _backend = BackendLifecycle(api: widget.api);
    _searchController = TextEditingController();
    _data = _load();
  }

  @override
  void dispose() {
    _searchController.dispose();
    _backend.dispose();
    widget.api.close();
    super.dispose();
  }

  Future<_DashboardData> _load() async {
    await _backend.ensureAvailable();
    final results = await Future.wait([
      widget.api.health().catchError((_) => <String, dynamic>{}),
      widget.api.ready().catchError((_) => <String, dynamic>{}),
      widget.api.system().catchError((_) => <String, dynamic>{}),
      widget.api.bdrs().catchError((_) => <BdrSummary>[]),
      widget.api.agents().catchError((_) => <String, dynamic>{}),
      widget.api.metrics().catchError((_) => <String, dynamic>{}),
      widget.api.officialCampaign().catchError((_) => <String, dynamic>{}),
    ]);
    return _DashboardData(
      health: results[0] as Map<String, dynamic>,
      ready: results[1] as Map<String, dynamic>,
      system: results[2] as Map<String, dynamic>,
      bdrs: results[3] as List<BdrSummary>,
      agents: results[4] as Map<String, dynamic>,
      metrics: results[5] as Map<String, dynamic>,
      campaign: results[6] as Map<String, dynamic>,
    );
  }

  void _refresh() => setState(() => _data = _load());

  Future<void> _runDemo() async {
    setState(() => _working = true);
    try {
      final journey = await widget.api.runDemo();
      setState(() {
        _section = 1;
        _data = _load();
      });
      if (mounted) _showJourney(journey);
    } catch (error) {
      if (mounted) _error(error);
    } finally {
      if (mounted) setState(() => _working = false);
    }
  }

  void _showJourney(Map<String, dynamic> journey) => showDialog<void>(
    context: context,
    builder: (context) => _DemoJourneyDialog(
      journey: journey,
      api: widget.api,
      onOpen: (bdrId) {
        Navigator.pop(context);
        Navigator.push(
          this.context,
          MaterialPageRoute(
            builder: (_) => BdrDetailPage(api: widget.api, bdrId: bdrId),
          ),
        );
      },
    ),
  );

  Future<void> _freeze() async {
    final approved = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Congelar operacao'),
        content: const Text(
          'Isso bloqueia novas autorizacoes locais. A acao e registrada e exige revisao para retomar.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Voltar'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Congelar'),
          ),
        ],
      ),
    );
    if (approved != true) return;
    setState(() => _working = true);
    try {
      await widget.api.freeze(
        actor: 'desktop-operator',
        reason: 'operator-requested-freeze',
      );
      _refresh();
    } catch (error) {
      if (mounted) _error(error);
    } finally {
      if (mounted) setState(() => _working = false);
    }
  }

  Future<void> _newDecision() async {
    final result = await showDialog<DecisionResult>(
      context: context,
      builder: (context) => NewDecisionDialog(api: widget.api),
    );
    if (result == null || !mounted) return;
    setState(() {
      _section = 1;
      _data = _load();
    });
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('Decisao ${result.authorization['result']} registrada.'),
      ),
    );
    await Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => BdrDetailPage(api: widget.api, bdrId: result.bdrId),
      ),
    );
    if (mounted) _refresh();
  }

  Future<void> _captureCampaignBaseline() async {
    setState(() => _working = true);
    try {
      await widget.api.captureOfficialBaseline();
      _refresh();
    } catch (error) {
      if (mounted) _error(error);
    } finally {
      if (mounted) setState(() => _working = false);
    }
  }

  Future<void> _captureCampaignSnapshot() async {
    setState(() => _working = true);
    try {
      await widget.api.captureOfficialSnapshot();
      _refresh();
    } catch (error) {
      if (mounted) _error(error);
    } finally {
      if (mounted) setState(() => _working = false);
    }
  }

  void _error(Object error) => ScaffoldMessenger.of(
    context,
  ).showSnackBar(SnackBar(content: Text('Nao foi possivel concluir: $error')));

  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(
      child: FutureBuilder<_DashboardData>(
        future: _data,
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError) {
            return _Offline(
              apiUrl: widget.api.baseUrl,
              onRetry: _refresh,
              error: snapshot.error,
            );
          }
          return _Console(
            data: snapshot.data!,
            working: _working,
            onRefresh: _refresh,
            onDemo: _runDemo,
            onNewDecision: _newDecision,
            onCaptureCampaignBaseline: _captureCampaignBaseline,
            onCaptureCampaignSnapshot: _captureCampaignSnapshot,
            onFreeze: _freeze,
            api: widget.api,
            section: _section,
            onSectionSelected: (index) => setState(() => _section = index),
            searchController: _searchController,
            stateFilter: _stateFilter,
            onSearchChanged: (_) => setState(() {}),
            onStateFilterChanged: (state) =>
                setState(() => _stateFilter = state),
          );
        },
      ),
    ),
  );
}

class _Console extends StatelessWidget {
  const _Console({
    required this.data,
    required this.working,
    required this.onRefresh,
    required this.onDemo,
    required this.onNewDecision,
    required this.onCaptureCampaignBaseline,
    required this.onCaptureCampaignSnapshot,
    required this.onFreeze,
    required this.api,
    required this.section,
    required this.onSectionSelected,
    required this.searchController,
    required this.stateFilter,
    required this.onSearchChanged,
    required this.onStateFilterChanged,
  });
  final _DashboardData data;
  final bool working;
  final VoidCallback onRefresh;
  final VoidCallback onDemo;
  final VoidCallback onNewDecision;
  final VoidCallback onCaptureCampaignBaseline;
  final VoidCallback onCaptureCampaignSnapshot;
  final VoidCallback onFreeze;
  final HorizonApi api;
  final int section;
  final ValueChanged<int> onSectionSelected;
  final TextEditingController searchController;
  final String stateFilter;
  final ValueChanged<String> onSearchChanged;
  final ValueChanged<String> onStateFilterChanged;

  @override
  Widget build(BuildContext context) {
    final paperOnly = data.health['paper-only'] == true;
    final frozen = data.system['frozen'] == true;
    final normalizedQuery = searchController.text.trim().toLowerCase();
    final states = data.bdrs.map((bdr) => bdr.state).toSet().toList()..sort();
    final filteredBdrs = data.bdrs.where((bdr) {
      final matchesState = stateFilter == 'TODOS' || bdr.state == stateFilter;
      final searchable = '${bdr.symbol} ${bdr.side} ${bdr.strategy} ${bdr.id}'
          .toLowerCase();
      return matchesState &&
          (normalizedQuery.isEmpty || searchable.contains(normalizedQuery));
    }).toList();
    return Row(
      children: [
        Container(
          decoration: const BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topCenter,
              end: Alignment.bottomCenter,
              colors: [Color(0xff2a1027), Color(0xff120b16)],
            ),
          ),
          child: NavigationRail(
            selectedIndex: section,
            onDestinationSelected: onSectionSelected,
            labelType: NavigationRailLabelType.all,
            leading: const Padding(
              padding: EdgeInsets.only(bottom: 24, top: 18),
              child: Column(
                children: [
                  BrandMark(),
                  SizedBox(height: 10),
                  Text(
                    'HORIZON',
                    style: TextStyle(
                      fontSize: 10,
                      letterSpacing: 1.7,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                ],
              ),
            ),
            destinations: const [
              NavigationRailDestination(
                icon: Icon(Icons.dashboard_outlined),
                selectedIcon: Icon(Icons.dashboard),
                label: Text('Visao geral'),
              ),
              NavigationRailDestination(
                icon: Icon(Icons.fact_check_outlined),
                label: Text('BDRs'),
              ),
              NavigationRailDestination(
                icon: Icon(Icons.shield_outlined),
                label: Text('Controles'),
              ),
              NavigationRailDestination(
                icon: Icon(Icons.hub_outlined),
                label: Text('Agentes'),
              ),
              NavigationRailDestination(
                icon: Icon(Icons.emoji_events_outlined),
                label: Text('Campanha'),
              ),
            ],
          ),
        ),
        const VerticalDivider(width: 1),
        Expanded(
          child: Padding(
            padding: const EdgeInsets.all(28),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            section == 0
                                ? 'Bom ter voce no controle.'
                                : section == 1
                                ? 'Decisoes registradas.'
                                : section == 2
                                ? 'Controles de protecao.'
                                : section == 3
                                ? 'Fronteiras dos agentes.'
                                : 'Campanha oficial Paper.',
                            style: TextStyle(
                              fontSize: 28,
                              fontWeight: FontWeight.w700,
                            ),
                          ),
                          SizedBox(height: 4),
                          Text(
                            'Decisoes governadas, com rastreabilidade local.',
                          ),
                        ],
                      ),
                    ),
                    _Pill(
                      label: paperOnly ? 'PAPER ONLY' : 'AMBIENTE INVALIDO',
                      color: paperOnly ? Colors.teal : Colors.red,
                    ),
                    const SizedBox(width: 8),
                    const _Pill(label: 'DEMO MOCK', color: Color(0xffffb454)),
                    const SizedBox(width: 12),
                    IconButton(
                      onPressed: working ? null : onRefresh,
                      icon: const Icon(Icons.refresh),
                      tooltip: 'Atualizar',
                    ),
                  ],
                ),
                const SizedBox(height: 22),
                if (section == 0)
                  Container(
                    width: double.infinity,
                    padding: const EdgeInsets.all(22),
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(20),
                      gradient: const LinearGradient(
                        colors: [
                          Color(0xff8c1749),
                          Color(0xff38122c),
                          Color(0xff241021),
                        ],
                      ),
                      boxShadow: const [
                        BoxShadow(
                          color: Color(0x55000000),
                          blurRadius: 22,
                          offset: Offset(0, 10),
                        ),
                      ],
                    ),
                    child: Row(
                      children: [
                        const Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                'Operacao protegida',
                                style: TextStyle(
                                  fontSize: 21,
                                  fontWeight: FontWeight.w700,
                                ),
                              ),
                              SizedBox(height: 6),
                              Text(
                                'Toda ordem exige BDR, limites deterministas e autorizacao antes de chegar ao gateway.',
                              ),
                            ],
                          ),
                        ),
                        Icon(
                          Icons.shield_moon_outlined,
                          size: 48,
                          color: Color(0xffffc5d6),
                        ),
                      ],
                    ),
                  ),
                if (section == 0) const SizedBox(height: 18),
                if (section == 0)
                  Wrap(
                    spacing: 16,
                    runSpacing: 16,
                    children: [
                      _Metric(
                        icon: Icons.health_and_safety_outlined,
                        label: 'Nucleo',
                        value: '${data.health['status'] ?? 'indisponivel'}',
                        color: Colors.green,
                      ),
                      _Metric(
                        icon: Icons.fact_check_outlined,
                        label: 'BDRs registrados',
                        value: '${data.bdrs.length}',
                        color: Colors.indigoAccent,
                      ),
                      _Metric(
                        icon: Icons.lock_outline,
                        label: 'Kill switch',
                        value: frozen ? 'CONGELADO' : 'ATIVO',
                        color: frozen ? Colors.orange : Colors.teal,
                      ),
                      _Metric(
                        icon: Icons.verified_user_outlined,
                        label: 'Prontidao',
                        value: data.ready['ready?'] == true
                            ? 'PRONTO'
                            : 'CONFIGURAR',
                        color: data.ready['ready?'] == true
                            ? Colors.teal
                            : Colors.orangeAccent,
                      ),
                      _Metric(
                        icon: Icons.lock_person_outlined,
                        label: 'BDRs selados',
                        value: '${data.metrics['sealed-total'] ?? 0}',
                        color: Colors.pinkAccent,
                      ),
                      _Metric(
                        icon: Icons.link_outlined,
                        label: 'Replays validos',
                        value: '${data.metrics['replay-valid-total'] ?? 0}',
                        color: Colors.lightBlueAccent,
                      ),
                      _Metric(
                        icon: Icons.timeline_outlined,
                        label: 'Eventos auditaveis',
                        value: '${data.metrics['events-total'] ?? 0}',
                        color: Colors.amberAccent,
                      ),
                    ],
                  ),
                if (section == 2)
                  _ControlSummary(
                    frozen: frozen,
                    ready: data.ready['ready?'] == true,
                    missing:
                        (data.ready['missing'] as List<dynamic>? ?? const [])
                            .map((item) => item.toString())
                            .toList(),
                  ),
                if (section == 3) _AgentRegistry(agents: data.agents),
                if (section == 4)
                  _OfficialCampaign(
                    campaign: data.campaign,
                    busy: working,
                    onBaseline: onCaptureCampaignBaseline,
                    onSnapshot: onCaptureCampaignSnapshot,
                  ),
                const SizedBox(height: 26),
                if (section != 3 && section != 4)
                  Row(
                    children: [
                      Text(
                        section == 1
                            ? 'Todos os Decision Records'
                            : 'Decision Records',
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      const Spacer(),
                      OutlinedButton.icon(
                        onPressed: working || frozen ? null : onDemo,
                        icon: const Icon(Icons.science_outlined),
                        label: const Text('Rodar jornada MOCK'),
                      ),
                      if (section == 1) ...[
                        const SizedBox(width: 12),
                        FilledButton.icon(
                          onPressed: working || frozen ? null : onNewDecision,
                          icon: const Icon(Icons.add_task_outlined),
                          label: const Text('Nova decisao'),
                        ),
                      ],
                      const SizedBox(width: 12),
                      FilledButton.tonalIcon(
                        onPressed: working || frozen ? null : onFreeze,
                        icon: const Icon(Icons.pause_circle_outline),
                        label: const Text('Congelar sistema'),
                      ),
                    ],
                  ),
                if (section != 3 && section != 4) const SizedBox(height: 12),
                if (section == 1)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 12),
                    child: Row(
                      children: [
                        SizedBox(
                          width: 300,
                          child: TextField(
                            controller: searchController,
                            onChanged: onSearchChanged,
                            decoration: const InputDecoration(
                              prefixIcon: Icon(Icons.search),
                              hintText: 'Buscar simbolo, estrategia ou BDR',
                              border: OutlineInputBorder(),
                              isDense: true,
                            ),
                          ),
                        ),
                        const SizedBox(width: 12),
                        DropdownButton<String>(
                          value: stateFilter,
                          items: ['TODOS', ...states]
                              .map(
                                (state) => DropdownMenuItem(
                                  value: state,
                                  child: Text(state),
                                ),
                              )
                              .toList(),
                          onChanged: (state) {
                            if (state != null) onStateFilterChanged(state);
                          },
                        ),
                        const SizedBox(width: 16),
                        Text(
                          '${filteredBdrs.length} de ${data.bdrs.length} registros',
                          style: const TextStyle(color: Colors.grey),
                        ),
                      ],
                    ),
                  ),
                Expanded(
                  child: Card(
                    child: section == 3
                        ? _AgentRegistryList(agents: data.agents)
                        : section == 4
                        ? _CampaignLedger(campaign: data.campaign)
                        : filteredBdrs.isEmpty
                        ? const Center(
                            child: Text('Nenhum BDR para este filtro.'),
                          )
                        : ListView.separated(
                            itemCount: filteredBdrs.length,
                            separatorBuilder: (_, _) =>
                                const Divider(height: 1),
                            itemBuilder: (context, index) {
                              final bdr = filteredBdrs[index];
                              return ListTile(
                                leading: CircleAvatar(
                                  child: Text(
                                    bdr.symbol.isNotEmpty
                                        ? bdr.symbol[0]
                                        : '?',
                                  ),
                                ),
                                title: Text('${bdr.symbol} · ${bdr.side}'),
                                subtitle: Text('${bdr.strategy}\n${bdr.id}'),
                                isThreeLine: true,
                                trailing: _Pill(
                                  label: bdr.state,
                                  color: _stateColor(bdr.state),
                                ),
                                onTap: () => Navigator.push(
                                  context,
                                  MaterialPageRoute(
                                    builder: (_) =>
                                        BdrDetailPage(api: api, bdrId: bdr.id),
                                  ),
                                ),
                              );
                            },
                          ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

class _DemoJourneyDialog extends StatelessWidget {
  const _DemoJourneyDialog({
    required this.journey,
    required this.api,
    required this.onOpen,
  });
  final Map<String, dynamic> journey;
  final HorizonApi api;
  final ValueChanged<String> onOpen;

  @override
  Widget build(BuildContext context) {
    final denied = Map<String, dynamic>.from(
      journey['denied'] as Map? ?? const {},
    );
    final authorized = Map<String, dynamic>.from(
      journey['authorized'] as Map? ?? const {},
    );
    final lifecycle = Map<String, dynamic>.from(
      journey['lifecycle'] as Map? ?? const {},
    );
    final denialReason =
        ((denied['authorization'] as Map?)?['reason-codes'] as List?)?.join(
          ' · ',
        ) ??
        'DENY';
    return AlertDialog(
      title: const Row(
        children: [
          Icon(Icons.route_outlined),
          SizedBox(width: 10),
          Text('Jornada MOCK concluida'),
        ],
      ),
      content: SizedBox(
        width: 680,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Dados sintéticos. Nenhuma ordem ou consulta foi enviada à Alpaca.',
            ),
            const SizedBox(height: 18),
            _JourneyStep(
              number: '01',
              title: 'A tese é desafiada',
              detail: 'Concentração excedida: $denialReason',
              color: Colors.redAccent,
              action: TextButton(
                onPressed: denied['bdr-id'] == null
                    ? null
                    : () => onOpen(denied['bdr-id'].toString()),
                child: const Text('Ver recusa'),
              ),
            ),
            _JourneyStep(
              number: '02',
              title: 'A intenção passa pelos engines',
              detail:
                  'Autorização: ${(authorized['authorization'] as Map?)?['result'] ?? 'ALLOW'}',
              color: Colors.tealAccent,
              action: TextButton(
                onPressed: authorized['bdr-id'] == null
                    ? null
                    : () => onOpen(authorized['bdr-id'].toString()),
                child: const Text('Ver autorização'),
              ),
            ),
            _JourneyStep(
              number: '03',
              title: 'Ciclo é observado e encerrado',
              detail: 'Execução sintética, HOLD e post-mortem com BDR selado.',
              color: const Color(0xffffb454),
              action: FilledButton(
                onPressed: lifecycle['bdr-id'] == null
                    ? null
                    : () => onOpen(lifecycle['bdr-id'].toString()),
                child: const Text('Abrir prova final'),
              ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Fechar'),
        ),
      ],
    );
  }
}

class _JourneyStep extends StatelessWidget {
  const _JourneyStep({
    required this.number,
    required this.title,
    required this.detail,
    required this.color,
    required this.action,
  });
  final String number;
  final String title;
  final String detail;
  final Color color;
  final Widget action;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 12),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          width: 34,
          height: 34,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: color.withValues(alpha: .18),
            borderRadius: BorderRadius.circular(10),
          ),
          child: Text(
            number,
            style: TextStyle(color: color, fontWeight: FontWeight.bold),
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title, style: const TextStyle(fontWeight: FontWeight.w700)),
              const SizedBox(height: 3),
              Text(detail, style: const TextStyle(color: Colors.grey)),
            ],
          ),
        ),
        action,
      ],
    ),
  );
}

class _Offline extends StatelessWidget {
  const _Offline({required this.apiUrl, required this.onRetry, this.error});
  final String apiUrl;
  final VoidCallback onRetry;
  final Object? error;
  String get _title => error is BackendUnavailable
      ? 'Nucleo local indisponivel'
      : 'Nao foi possivel falar com o nucleo';

  String get _guidance {
    if (error is BackendUnavailable) {
      final unavailable = error! as BackendUnavailable;
      if (unavailable.issue == BackendIssue.sidecarNotFound) {
        return 'Abra o app pelo bundle de release ou defina HORIZON_BACKEND_EXECUTABLE para o sidecar local.';
      }
      return 'O sidecar foi encontrado, mas nao concluiu o boot. Verifique Java, permissao de execucao e o diretorio de dados local.';
    }
    return 'Confirme que a API local esta ativa em $apiUrl. Configuracao Paper incompleta aparece em Controles depois que o nucleo inicia.';
  }

  @override
  Widget build(BuildContext context) => Center(
    child: ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 510),
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(28),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.cloud_off_outlined, size: 42),
              const SizedBox(height: 16),
              Text(
                _title,
                style: TextStyle(fontSize: 22, fontWeight: FontWeight.w600),
              ),
              const SizedBox(height: 10),
              Text(_guidance, textAlign: TextAlign.center),
              const SizedBox(height: 18),
              FilledButton.icon(
                onPressed: onRetry,
                icon: const Icon(Icons.refresh),
                label: const Text('Tentar novamente'),
              ),
              if (error != null)
                Padding(
                  padding: const EdgeInsets.only(top: 12),
                  child: Text(
                    'Diagnostico: $error',
                    style: const TextStyle(fontSize: 11, color: Colors.grey),
                    maxLines: 2,
                  ),
                ),
            ],
          ),
        ),
      ),
    ),
  );
}

class _ControlSummary extends StatelessWidget {
  const _ControlSummary({
    required this.frozen,
    required this.ready,
    required this.missing,
  });
  final bool frozen;
  final bool ready;
  final List<String> missing;

  @override
  Widget build(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.all(22),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(
                frozen
                    ? Icons.pause_circle_filled_outlined
                    : Icons.shield_outlined,
                color: frozen ? Colors.orangeAccent : const Color(0xffff7193),
                size: 34,
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      frozen
                          ? 'Novas entradas congeladas'
                          : 'Barreiras de capital ativas',
                      style: const TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      frozen
                          ? 'A reconciliação e a auditoria continuam disponíveis; somente novos riscos são bloqueados.'
                          : 'Paper-only, autorização com TTL, hash da intenção, idempotência e replay são exigidos antes da execução.',
                    ),
                  ],
                ),
              ),
              const _Pill(label: 'FAIL-CLOSED', color: Color(0xffff7193)),
            ],
          ),
          const Divider(height: 32),
          Row(
            children: [
              Icon(
                ready ? Icons.check_circle_outline : Icons.settings_outlined,
                color: ready ? Colors.tealAccent : Colors.orangeAccent,
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  ready ? 'Gateway Paper pronto para os gates locais.' : 'Dispatch bloqueado ate a configuracao Paper estar completa.',
                ),
              ),
            ],
          ),
          if (!ready) ...[
            const SizedBox(height: 10),
            Text(
              'Faltando: ${missing.join(' · ')}',
              style: const TextStyle(color: Color(0xffffc5d6)),
            ),
            const SizedBox(height: 6),
            const Text(
              'Configure somente no ambiente do sidecar ou em backend/.env; a interface nunca solicita ou persiste chaves.',
            ),
          ],
        ],
      ),
    ),
  );
}

class _AgentRegistry extends StatelessWidget {
  const _AgentRegistry({required this.agents});
  final Map<String, dynamic> agents;

  @override
  Widget build(BuildContext context) {
    final valid = agents['registry-valid?'] == true;
    final entries = agents['agents'] as List<dynamic>? ?? const [];
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(22),
        child: Row(
          children: [
            Icon(
              valid ? Icons.verified_user_outlined : Icons.gpp_maybe_outlined,
              color: valid ? Colors.tealAccent : Colors.redAccent,
              size: 34,
            ),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    valid
                        ? 'Registro de agentes valido'
                        : 'Registro de agentes invalido',
                    style: const TextStyle(
                      fontSize: 18,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    '${entries.length} workloads declarados. Os escopos proibidos permanecem visíveis para auditoria.',
                  ),
                ],
              ),
            ),
            const _Pill(label: 'LEAST PRIVILEGE', color: Color(0xff75b7ff)),
          ],
        ),
      ),
    );
  }
}

class _OfficialCampaign extends StatelessWidget {
  const _OfficialCampaign({
    required this.campaign,
    required this.busy,
    required this.onBaseline,
    required this.onSnapshot,
  });
  final Map<String, dynamic> campaign;
  final bool busy;
  final VoidCallback onBaseline;
  final VoidCallback onSnapshot;

  @override
  Widget build(BuildContext context) {
    final enabled = campaign['enabled?'] == true;
    final active = campaign['window-active?'] == true;
    final baseline = campaign['baseline-captured?'] == true;
    final autonomy = campaign['autonomy-enabled?'] == true;
    final status = !enabled
        ? 'Desligada'
        : !active
        ? 'Aguardando janela ET'
        : baseline
        ? 'Em medicao'
        : 'Pronta para baseline';
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(22),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Icon(enabled ? Icons.timer_outlined : Icons.lock_outline, color: enabled ? Colors.amberAccent : Colors.grey, size: 34),
            const SizedBox(width: 14),
            Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(status, style: const TextStyle(fontSize: 19, fontWeight: FontWeight.w700)),
              const SizedBox(height: 4),
              Text('Janela: ${campaign['starts-at'] ?? 'não configurada'} → ${campaign['ends-at'] ?? 'não configurada'}'),
            ])),
            _Pill(label: autonomy ? 'AUTONOMIA CONFIGURADA' : 'AUTONOMIA DESLIGADA', color: autonomy ? Colors.orangeAccent : Colors.grey),
          ]),
          const Divider(height: 28),
          Text(enabled
              ? 'Baseline e snapshots consultam somente equity da conta Paper oficial. Nenhuma dessas ações cria ordem.'
              : 'Para ativar, configure a conta oficial e a janela UTC no ambiente. O app não permite ativação por interface.'),
          const SizedBox(height: 14),
          Wrap(spacing: 10, runSpacing: 8, children: [
            FilledButton.tonalIcon(
              onPressed: busy || !active || baseline ? null : onBaseline,
              icon: const Icon(Icons.flag_outlined),
              label: const Text('Capturar baseline'),
            ),
            OutlinedButton.icon(
              onPressed: busy || !active || !baseline ? null : onSnapshot,
              icon: const Icon(Icons.monitor_heart_outlined),
              label: const Text('Capturar equity'),
            ),
          ]),
        ]),
      ),
    );
  }
}

class _CampaignLedger extends StatelessWidget {
  const _CampaignLedger({required this.campaign});
  final Map<String, dynamic> campaign;

  @override
  Widget build(BuildContext context) {
    final pnl = campaign['pnl'] as Map<String, dynamic>?;
    if (pnl == null) return const Center(child: Text('Nenhum baseline oficial capturado ainda.'));
    return Padding(
      padding: const EdgeInsets.all(22),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        const Text('Ledger de equity', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
        const SizedBox(height: 16),
        Wrap(spacing: 18, runSpacing: 18, children: [
          _CampaignMetric(label: 'Baseline', value: 'US\$${pnl['baseline-equity']}'),
          _CampaignMetric(label: 'Equity atual', value: 'US\$${pnl['latest-equity']}'),
          _CampaignMetric(label: 'P&L', value: 'US\$${pnl['pnl']}'),
          _CampaignMetric(label: 'Snapshots', value: '${pnl['snapshot-count']}'),
        ]),
      ]),
    );
  }
}

class _CampaignMetric extends StatelessWidget {
  const _CampaignMetric({required this.label, required this.value});
  final String label;
  final String value;
  @override
  Widget build(BuildContext context) => SizedBox(width: 180, child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(value, style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700)), const SizedBox(height: 4), Text(label, style: const TextStyle(color: Colors.grey))]));
}

class _AgentRegistryList extends StatelessWidget {
  const _AgentRegistryList({required this.agents});
  final Map<String, dynamic> agents;

  @override
  Widget build(BuildContext context) {
    final entries = agents['agents'] as List<dynamic>? ?? const [];
    if (entries.isEmpty) {
      return const Center(child: Text('Nenhum agente registrado.'));
    }
    return ListView.separated(
      padding: const EdgeInsets.all(12),
      itemCount: entries.length,
      separatorBuilder: (_, _) => const Divider(),
      itemBuilder: (context, index) {
        final agent = Map<String, dynamic>.from(entries[index] as Map);
        final scopes = (agent['scopes'] as List<dynamic>? ?? const []).join(
          ' · ',
        );
        final forbidden = (agent['forbidden'] as List<dynamic>? ?? const [])
            .join(' · ');
        return ListTile(
          leading: const CircleAvatar(child: Icon(Icons.smart_toy_outlined)),
          title: Text(
            '${agent['agent-id'] ?? agent['agent_id'] ?? 'agent'} · v${agent['version'] ?? 'n/a'}',
          ),
          subtitle: Text('Permitido: $scopes\nProibido: $forbidden'),
          isThreeLine: true,
          trailing: const _Pill(label: 'SEM BROKER', color: Color(0xff75b7ff)),
        );
      },
    );
  }
}

class _Metric extends StatelessWidget {
  const _Metric({
    required this.icon,
    required this.label,
    required this.value,
    required this.color,
  });
  final IconData icon;
  final String label, value;
  final Color color;
  @override
  Widget build(BuildContext context) => SizedBox(
    width: 205,
    child: Card(
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(icon, color: color),
            const SizedBox(height: 16),
            Text(
              value,
              style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 4),
            Text(label, style: const TextStyle(color: Colors.grey)),
          ],
        ),
      ),
    ),
  );
}

class _Pill extends StatelessWidget {
  const _Pill({required this.label, required this.color});
  final String label;
  final Color color;
  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
    decoration: BoxDecoration(
      color: color.withValues(alpha: .18),
      borderRadius: BorderRadius.circular(20),
      border: Border.all(color: color.withValues(alpha: .7)),
    ),
    child: Text(
      label,
      style: TextStyle(color: color, fontSize: 11, fontWeight: FontWeight.bold),
    ),
  );
}

Color _stateColor(String state) {
  if (state.contains('DENIED') || state.contains('REJECTED')) {
    return Colors.redAccent;
  }
  if (state.contains('AUTHORIZED') ||
      state.contains('SUBMITTED') ||
      state.contains('FILLED')) {
    return Colors.tealAccent;
  }
  if (state.contains('REVIEW') || state.contains('PENDING')) {
    return Colors.orangeAccent;
  }
  return Colors.blueGrey;
}

class _DashboardData {
  const _DashboardData({
    required this.health,
    required this.ready,
    required this.system,
    required this.bdrs,
    required this.agents,
    required this.metrics,
    required this.campaign,
  });
  final Map<String, dynamic> health, ready, system, agents, metrics, campaign;
  final List<BdrSummary> bdrs;
}
