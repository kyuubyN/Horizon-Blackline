import 'dart:math';

import 'package:flutter/material.dart';

import '../../services/horizon_api.dart';

class NewDecisionDialog extends StatefulWidget {
  const NewDecisionDialog({super.key, required this.api});
  final HorizonApi api;

  @override
  State<NewDecisionDialog> createState() => _NewDecisionDialogState();
}

class _NewDecisionDialogState extends State<NewDecisionDialog> {
  final _form = GlobalKey<FormState>();
  final _symbol = TextEditingController(text: 'AAPL');
  final _quantity = TextEditingController(text: '1');
  final _entry = TextEditingController(text: '100');
  final _stop = TextEditingController(text: '95');
  final _symbolWeight = TextEditingController(text: '0.05');
  final _grossExposure = TextEditingController(text: '0.15');
  final _participation = TextEditingController(text: '0.01');
  final _drawdown = TextEditingController(text: '0.01');
  bool _submitting = false;
  bool _loadingQuote = false;
  Map<String, dynamic>? _quoteEvidence;
  Map<String, dynamic>? _discovery;
  Map<String, dynamic>? _research;

  @override
  void dispose() {
    for (final controller in [
      _symbol,
      _quantity,
      _entry,
      _stop,
      _symbolWeight,
      _grossExposure,
      _participation,
      _drawdown,
    ]) {
      controller.dispose();
    }
    super.dispose();
  }

  String _uuid() {
    final random = Random.secure();
    final bytes = List<int>.generate(16, (_) => random.nextInt(256));
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    String hex(int value) => value.toRadixString(16).padLeft(2, '0');
    final value = bytes.map(hex).join();
    return '${value.substring(0, 8)}-${value.substring(8, 12)}-${value.substring(12, 16)}-${value.substring(16, 20)}-${value.substring(20)}';
  }

  String? _decimal(String? value) {
    if (value == null ||
        double.tryParse(value) == null ||
        double.parse(value) <= 0) {
      return 'Enter a positive decimal.';
    }
    return null;
  }

  Future<void> _submit() async {
    if (!_form.currentState!.validate()) return;
    setState(() => _submitting = true);
    try {
      final now = DateTime.now().toUtc().toIso8601String();
      final runId = 'desktop-${_uuid()}';
      final created = await widget.api.createBdr({
        'run-id': runId,
        'correlation-id': runId,
        'actor': 'desktop-operator',
      });
      final bdrId = created['bdr-id'].toString();
      await widget.api.appendEvidence(
        bdrId,
        _quoteEvidence ??
            {
              'source-uri':
                  'fixture://desktop/${_symbol.text.trim().toUpperCase()}',
              'source-type': 'fixture',
              'content-hash': 'sha256:desktop-${_uuid().replaceAll('-', '')}',
              'observed-at': now,
              'ingested-at': now,
              'valid-to': DateTime.now()
                  .toUtc()
                  .add(const Duration(minutes: 5))
                  .toIso8601String(),
              'confidence': 1.0,
            },
      );
      if (_discovery != null && _research != null) {
        await widget.api.appendDiscovery(
          bdrId,
          Map<String, dynamic>.from(_discovery!['candidate'] as Map),
        );
        await widget.api.appendResearch(bdrId, _research!);
      }
      await widget.api.challenge(bdrId, {
        'critics': [
          {'critic-id': 'contrarian', 'severity': 'low', 'complete': true},
          {'critic-id': 'evidence', 'severity': 'none', 'complete': true},
          {'critic-id': 'risk', 'severity': 'none', 'complete': true},
        ],
      });
      final intent = {
        'intent-id': _uuid(),
        'bdr-id': bdrId,
        'asset-class': 'stock',
        'symbol': _symbol.text.trim().toUpperCase(),
        'side': 'buy',
        'order-type': 'limit',
        'quantity': _quantity.text.trim(),
        'entry-price': _entry.text.trim(),
        'stop-price': _stop.text.trim(),
        'requested-risk-budget': '100',
        'as-of': now,
        'evidence-refs': <String>[],
      };
      final authorization = await widget.api.authorize({
        'bdr-id': bdrId,
        'intent': intent,
        'policy-bundle-id': 'desktop-policy@1',
        'ttl-seconds': 60,
        'snapshot': {
          'account-id': 'DESKTOP-LOCAL',
          'equity': '10000',
          'buying-power': '5000',
          'post-trade-symbol-weight': _symbolWeight.text.trim(),
          'post-trade-gross-exposure': _grossExposure.text.trim(),
          'estimated-participation': _participation.text.trim(),
          'daily-drawdown': _drawdown.text.trim(),
          'as-of': now,
          'source-digest': 'fixture:desktop@1',
        },
        'policy': {
          'limits': {
            'remaining-risk-budget': '100',
            'max-symbol-weight': '0.10',
            'max-gross-exposure': '0.20',
            'max-adv-participation': '0.05',
            'hard-drawdown-limit': '0.05',
          },
        },
        'evidence-valid?': true,
        'critics-complete?': true,
        'snapshot-valid?': true,
        'policy-active?': true,
      });
      if (mounted) Navigator.pop(context, DecisionResult(bdrId, authorization));
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Core rejected the decision: $error')),
        );
      }
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  Future<void> _loadQuote() async {
    final symbol = _symbol.text.trim().toUpperCase();
    if (symbol.isEmpty) return;
    setState(() => _loadingQuote = true);
    try {
      final quote = await widget.api.latestStockQuote(symbol);
      final evidence = Map<String, dynamic>.from(quote['evidence'] as Map);
      final discovery = await widget.api.discover(quote);
      final research = await widget.api.research(
        Map<String, dynamic>.from(discovery['candidate'] as Map),
      );
      final price = _quotePrice(quote['data'], symbol);
      if (price != null) {
        _entry.text = price;
        final parsed = double.tryParse(price);
        if (parsed != null && parsed > 0) {
          _stop.text = (parsed * .95).toStringAsFixed(2);
        }
      }
      if (mounted) {
        setState(() {
          _quoteEvidence = evidence;
          _discovery = discovery;
          _research = research;
        });
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('$symbol quote captured as Alpaca evidence.'),
          ),
        );
      }
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Could not fetch the quote: $error'),
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _loadingQuote = false);
    }
  }

  String? _quotePrice(Object? data, String symbol) {
    if (data is! Map) return null;
    final keyed = data[symbol] ?? data[symbol.toLowerCase()] ?? data['quote'];
    final quote = keyed is Map ? keyed : data;
    for (final key in ['ask_price', 'ap', 'bid_price', 'bp']) {
      final value = quote[key];
      if (value != null && double.tryParse(value.toString()) != null) {
        return value.toString();
      }
    }
    return null;
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('New governed decision'),
    content: SizedBox(
      width: 620,
      child: Form(
        key: _form,
        child: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'This journey uses local fixture evidence. It creates and challenges the BDR, but never sends an order to the broker.',
              ),
              const SizedBox(height: 16),
              _row(
                _field(
                  _symbol,
                  'Symbol',
                  validator: (value) => value == null || value.trim().isEmpty
                      ? 'Enter the symbol.'
                      : null,
                ),
                _field(_quantity, 'Quantity', validator: _decimal),
              ),
              Align(
                alignment: Alignment.centerLeft,
                child: OutlinedButton.icon(
                  onPressed: _loadingQuote || _submitting ? null : _loadQuote,
                  icon: _loadingQuote
                      ? const SizedBox.square(
                          dimension: 16,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.query_stats_outlined),
                  label: Text(
                    _quoteEvidence == null
                        ? 'Fetch Alpaca quote (read-only)'
                        : 'Alpaca quote captured',
                  ),
                ),
              ),
              const SizedBox(height: 8),
              _row(
                _field(_entry, 'Entry price', validator: _decimal),
                _field(_stop, 'Stop price', validator: _decimal),
              ),
              const SizedBox(height: 16),
              const Text(
                'Local snapshot for risk evaluation',
                style: TextStyle(fontWeight: FontWeight.w700),
              ),
              const SizedBox(height: 8),
              _row(
                _field(_symbolWeight, 'Post-trade weight', validator: _decimal),
                _field(_grossExposure, 'Gross exposure', validator: _decimal),
              ),
              _row(
                _field(
                  _participation,
                  'Estimated participation',
                  validator: _decimal,
                ),
                _field(_drawdown, 'Daily drawdown', validator: _decimal),
              ),
            ],
          ),
        ),
      ),
    ),
    actions: [
      TextButton(
        onPressed: _submitting ? null : () => Navigator.pop(context),
        child: const Text('Cancel'),
      ),
      FilledButton.icon(
        onPressed: _submitting ? null : _submit,
        icon: _submitting
            ? const SizedBox.square(
                dimension: 16,
                child: CircularProgressIndicator(strokeWidth: 2),
              )
            : const Icon(Icons.gavel_outlined),
        label: const Text('Evaluate and authorize'),
      ),
    ],
  );

  Widget _field(
    TextEditingController controller,
    String label, {
    String? Function(String?)? validator,
  }) => Padding(
    padding: const EdgeInsets.only(bottom: 10),
    child: TextFormField(
      controller: controller,
      validator: validator,
      decoration: InputDecoration(
        labelText: label,
        border: const OutlineInputBorder(),
        isDense: true,
      ),
    ),
  );

  Widget _row(Widget left, Widget right) => Row(
    children: [
      Expanded(child: left),
      const SizedBox(width: 12),
      Expanded(child: right),
    ],
  );
}

class DecisionResult {
  const DecisionResult(this.bdrId, this.authorization);
  final String bdrId;
  final Map<String, dynamic> authorization;
}
