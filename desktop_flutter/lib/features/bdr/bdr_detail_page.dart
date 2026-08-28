import 'dart:convert';

import 'package:flutter/material.dart';

import '../../models/bdr.dart';
import '../../services/audit_export.dart';
import '../../services/horizon_api.dart';

class BdrDetailPage extends StatefulWidget {
  const BdrDetailPage({super.key, required this.api, required this.bdrId});
  final HorizonApi api;
  final String bdrId;

  @override
  State<BdrDetailPage> createState() => _BdrDetailPageState();
}

class _BdrDetailPageState extends State<BdrDetailPage> {
  late Future<_DetailData> _data;
  bool _working = false;
  @override
  void initState() {
    super.initState();
    _data = _load();
  }

  Future<_DetailData> _load() async => _DetailData(
    await widget.api.bdr(widget.bdrId),
    await widget.api.replay(widget.bdrId),
  );

  Future<void> _runOperation(
    String successMessage,
    Future<Object?> Function() operation,
  ) async {
    setState(() => _working = true);
    try {
      await operation();
      if (!mounted) return;
      setState(() => _data = _load());
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(successMessage)));
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Operacao recusada pelo nucleo: $error')),
        );
      }
    } finally {
      if (mounted) setState(() => _working = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('BDR auditavel'),
      actions: [
        IconButton(
          onPressed: () => setState(() => _data = _load()),
          icon: const Icon(Icons.refresh),
        ),
        IconButton(
          onPressed: _working ? null : _exportAudit,
          icon: const Icon(Icons.download_outlined),
          tooltip: 'Exportar prova auditavel',
        ),
      ],
    ),
    body: FutureBuilder<_DetailData>(
      future: _data,
      builder: (context, snapshot) {
        if (snapshot.connectionState != ConnectionState.done) {
          return const Center(child: CircularProgressIndicator());
        }
        if (snapshot.hasError) {
          return Center(
            child: Text('Falha ao carregar BDR: ${snapshot.error}'),
          );
        }
        final data = snapshot.data!;
        final bdr = data.bdr;
        return Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '${bdr.symbol} · ${bdr.side}',
                style: const TextStyle(
                  fontSize: 28,
                  fontWeight: FontWeight.w700,
                ),
              ),
              const SizedBox(height: 6),
              SelectableText(
                bdr.id,
                style: const TextStyle(color: Colors.grey),
              ),
              const SizedBox(height: 18),
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Row(
                    children: [
                      const Icon(Icons.link),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Text(
                          (data.replay['valid?'] == true ||
                                  data.replay['valid'] == true)
                              ? 'Cadeia de evidencia valida'
                              : 'Cadeia requer revisao',
                        ),
                      ),
                      Chip(label: Text('${bdr.events.length} eventos')),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 12),
              _WorkflowActions(
                state: bdr.state,
                busy: _working,
                onMonitor: () => _runOperation(
                  'Monitoramento iniciado.',
                  () => widget.api.startMonitoring(bdr.id),
                ),
                onHold: () => _runOperation(
                  'Reavaliacao HOLD registrada.',
                  () => widget.api.reevaluate(
                    bdr.id,
                    decision: 'HOLD',
                    trigger: 'desktop-operator-review',
                  ),
                ),
                onClose: () => _runOperation(
                  'BDR encerrado; registre o post-mortem.',
                  () => widget.api.closeBdr(
                    bdr.id,
                    reason: 'desktop-operator-close',
                  ),
                ),
                onPostMortem: () => _confirmPostMortem(bdr.id),
              ),
              const SizedBox(height: 16),
              _DecisionNarrative(events: bdr.events),
              const SizedBox(height: 16),
              Expanded(
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      flex: 2,
                      child: Card(
                        child: ListView.separated(
                          padding: const EdgeInsets.all(12),
                          itemCount: bdr.events.length,
                          separatorBuilder: (_, _) => const Divider(),
                          itemBuilder: (context, index) {
                            final event = bdr.events[index];
                            return ListTile(
                              leading: const Icon(
                                Icons.fiber_manual_record,
                                size: 16,
                              ),
                              title: Text(event.type),
                              subtitle: Text(event.at),
                              trailing: IconButton(
                                icon: const Icon(Icons.code),
                                tooltip: 'Ver payload',
                                onPressed: () => _payload(context, event),
                              ),
                            );
                          },
                        ),
                      ),
                    ),
                    const SizedBox(width: 16),
                    Expanded(
                      child: Card(
                        child: Padding(
                          padding: const EdgeInsets.all(18),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              const Text(
                                'Intencao normalizada',
                                style: TextStyle(
                                  fontSize: 17,
                                  fontWeight: FontWeight.w600,
                                ),
                              ),
                              const SizedBox(height: 12),
                              Expanded(
                                child: SingleChildScrollView(
                                  child: SelectableText(
                                    const JsonEncoder.withIndent('  ')
                                        .convert(bdr.intent),
                                    style: const TextStyle(
                                      fontFamily: 'monospace',
                                    ),
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        );
      },
    ),
  );

  void _payload(BuildContext context, BdrEvent event) => showDialog<void>(
    context: context,
    builder: (context) => AlertDialog(
      title: Text(event.type),
      content: SizedBox(
        width: 640,
        child: SingleChildScrollView(
          child: SelectableText(
            const JsonEncoder.withIndent('  ').convert(event.payload),
            style: const TextStyle(fontFamily: 'monospace'),
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Fechar'),
        ),
      ],
    ),
  );

  Future<void> _confirmPostMortem(String bdrId) async {
    final approved = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Registrar post-mortem'),
        content: const Text(
          'O registro sera anexado ao BDR e o selara. Confirme somente apos revisar a linha do tempo.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Voltar'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Registrar'),
          ),
        ],
      ),
    );
    if (approved == true) {
      await _runOperation(
        'Post-mortem registrado e BDR selado.',
        () => widget.api.postMortem(
          bdrId,
          outcome: 'operator-reviewed outcome',
          limitations: const ['Registered from local desktop operator console'],
        ),
      );
    }
  }

  Future<void> _exportAudit() async {
    setState(() => _working = true);
    try {
      final payload = await widget.api.auditExport(widget.bdrId);
      final file = await AuditExport.save(payload, widget.bdrId);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Prova auditavel salva em ${file.path}')),
        );
      }
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Nao foi possivel exportar: $error')),
        );
      }
    } finally {
      if (mounted) setState(() => _working = false);
    }
  }
}

class _WorkflowActions extends StatelessWidget {
  const _WorkflowActions({
    required this.state,
    required this.busy,
    required this.onMonitor,
    required this.onHold,
    required this.onClose,
    required this.onPostMortem,
  });
  final String state;
  final bool busy;
  final VoidCallback onMonitor;
  final VoidCallback onHold;
  final VoidCallback onClose;
  final VoidCallback onPostMortem;

  @override
  Widget build(BuildContext context) {
    final actions = <Widget>[];
    if (state == 'FILLED') {
      actions.add(
        OutlinedButton.icon(
          onPressed: busy ? null : onMonitor,
          icon: const Icon(Icons.visibility_outlined),
          label: const Text('Iniciar monitoramento'),
        ),
      );
    }
    if (state == 'MONITORING') {
      actions.add(
        OutlinedButton.icon(
          onPressed: busy ? null : onHold,
          icon: const Icon(Icons.pause_outlined),
          label: const Text('Registrar HOLD'),
        ),
      );
    }
    if ({'FILLED', 'MONITORING', 'CANCELED', 'REJECTED'}.contains(state)) {
      actions.add(
        FilledButton.tonalIcon(
          onPressed: busy ? null : onClose,
          icon: const Icon(Icons.task_alt_outlined),
          label: const Text('Encerrar BDR'),
        ),
      );
    }
    if (state == 'CLOSED') {
      actions.add(
        FilledButton.icon(
          onPressed: busy ? null : onPostMortem,
          icon: const Icon(Icons.auto_stories_outlined),
          label: const Text('Registrar post-mortem'),
        ),
      );
    }
    if (actions.isEmpty) return const SizedBox.shrink();
    return Wrap(spacing: 10, runSpacing: 8, children: actions);
  }
}

class _DecisionNarrative extends StatelessWidget {
  const _DecisionNarrative({required this.events});
  final List<BdrEvent> events;

  BdrEvent? _event(String type) {
    for (final event in events) {
      if (event.type == type) return event;
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final evidence = _event('EVIDENCE_CAPTURED');
    final discovery = _event('CANDIDATE_DISCOVERED');
    final research = _event('THESIS_RESEARCHED');
    final critics = _event('CRITIQUE_BUNDLE_COMPLETED');
    final authorization = _event('AUTHORIZATION_ISSUED');
    final observation = _event('BROKER_OBSERVED');
    final postMortem = _event('POST_MORTEM_RECORDED');
    final cards = <Widget>[
      _StageCard(
        icon: Icons.article_outlined,
        title: 'Evidencia',
        color: const Color(0xff75b7ff),
        primary:
            evidence?.payload['source-uri']?.toString() ?? 'Nao registrada',
        secondary: evidence == null
            ? 'Nao participa da decisao.'
            : 'Observada: ${evidence.payload['observed-at'] ?? 'nao informado'}',
      ),
      _StageCard(
        icon: Icons.travel_explore_outlined,
        title: 'Descoberta',
        color: const Color(0xffc2a6ff),
        primary: discovery?.payload['symbol']?.toString() ?? 'Nao registrada',
        secondary: discovery == null
            ? 'Sem candidato estruturado.'
            : '${discovery.payload['discovery-method'] ?? 'candidate_set@1'} · ${discovery.at}',
      ),
      _StageCard(
        icon: Icons.menu_book_outlined,
        title: 'Pesquisa',
        color: const Color(0xff9be0a1),
        primary: research?.payload['thesis-id']?.toString() ?? 'Nao registrada',
        secondary: research == null
            ? 'Sem tese estruturada.'
            : '${(research.payload['claims'] as List?)?.length ?? 0} claims com proveniencia',
      ),
      _StageCard(
        icon: Icons.groups_2_outlined,
        title: 'Criticos',
        color: const Color(0xffcf9cff),
        primary: _criticSummary(critics?.payload),
        secondary: critics == null
            ? 'Challenge ausente.'
            : 'Bundle registrado em ${critics.at}',
      ),
      _StageCard(
        icon: Icons.verified_user_outlined,
        title: 'Autorizacao',
        color: const Color(0xff65d6b3),
        primary: authorization?.payload['result']?.toString() ?? 'PENDENTE',
        secondary: _reasonSummary(authorization?.payload),
      ),
      _StageCard(
        icon: Icons.receipt_long_outlined,
        title: 'Observacao',
        color: const Color(0xffffc36a),
        primary:
            observation?.payload['status']?.toString() ?? 'Sem efeito externo',
        secondary: observation == null
            ? 'Nenhuma ordem/fill registrado.'
            : 'Registrada em ${observation.at}',
      ),
      _StageCard(
        icon: Icons.auto_stories_outlined,
        title: 'Post-mortem',
        color: const Color(0xffff8fac),
        primary: postMortem?.payload['outcome']?.toString() ?? 'Ainda aberto',
        secondary: postMortem == null
            ? 'Aguardando encerramento.'
            : 'Registro final selado.',
      ),
    ];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          'Explicacao da decisao',
          style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700),
        ),
        const SizedBox(height: 10),
        SizedBox(
          height: 146,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            itemCount: cards.length,
            separatorBuilder: (_, _) => const SizedBox(width: 12),
            itemBuilder: (_, index) => cards[index],
          ),
        ),
      ],
    );
  }

  String _criticSummary(Map<String, dynamic>? payload) {
    final critics = payload?['critics'];
    if (critics is! List || critics.isEmpty) return 'Nao registrados';
    return critics
        .whereType<Map>()
        .map(
          (critic) =>
              '${critic['critic-id'] ?? 'critic'}: ${critic['severity'] ?? 'n/a'}',
        )
        .join(' · ');
  }

  String _reasonSummary(Map<String, dynamic>? payload) {
    final reasons = payload?['reason-codes'];
    if (reasons is! List || reasons.isEmpty) return 'Sem breaches de politica.';
    return reasons.join(' · ');
  }
}

class _StageCard extends StatelessWidget {
  const _StageCard({
    required this.icon,
    required this.title,
    required this.color,
    required this.primary,
    required this.secondary,
  });
  final IconData icon;
  final String title;
  final Color color;
  final String primary;
  final String secondary;

  @override
  Widget build(BuildContext context) => SizedBox(
    width: 230,
    child: Card(
      child: Padding(
        padding: const EdgeInsets.all(15),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(icon, color: color),
            const SizedBox(height: 10),
            Text(title, style: const TextStyle(fontWeight: FontWeight.w700)),
            const SizedBox(height: 5),
            Text(primary, maxLines: 2, overflow: TextOverflow.ellipsis),
            const SizedBox(height: 4),
            Text(
              secondary,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 11, color: Colors.grey),
            ),
          ],
        ),
      ),
    ),
  );
}

class _DetailData {
  const _DetailData(this.bdr, this.replay);
  final BdrDetail bdr;
  final Map<String, dynamic> replay;
}
