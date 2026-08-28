import 'package:flutter_test/flutter_test.dart';
import 'package:horizon_blackline_desktop/models/bdr.dart';

void main() {
  test('normaliza resumo de BDR vindo da API Clojure', () {
    final bdr = BdrSummary.fromJson({
      'id': 'bdr-1',
      'state': 'AUTHORIZED',
      'strategy': 'mean-reversion',
      'updated-at': '2026-08-28T00:00:00Z',
      'intent': {'symbol': 'AAPL', 'side': 'buy'},
    });
    expect(bdr.symbol, 'AAPL');
    expect(bdr.side, 'BUY');
    expect(bdr.state, 'AUTHORIZED');
  });

  test('aceita o formato append-only entregue pela API Clojure', () {
    final bdr = BdrDetail.fromJson({
      'bdr-id': 'bdr-api-1',
      'state': 'POST_MORTEM_COMPLETE',
      'run-id': 'demo-authorized-limit-order',
      'created-at': '2026-08-28T12:00:00Z',
      'events': [
        {
          'event-type': 'EVIDENCE_CAPTURED',
          'occurred-at': '2026-08-28T12:01:00Z',
          'payload': {'source-uri': 'fixture://demo/aapl'},
        },
      ],
    });
    expect(bdr.id, 'bdr-api-1');
    expect(bdr.symbol, 'AAPL');
    expect(bdr.events.single.at, '2026-08-28T12:01:00Z');
  });
}
