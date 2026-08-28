class BdrSummary {
  const BdrSummary({
    required this.id,
    required this.state,
    required this.strategy,
    required this.updatedAt,
    required this.intent,
  });

  final String id;
  final String state;
  final String strategy;
  final String updatedAt;
  final Map<String, dynamic> intent;

  factory BdrSummary.fromJson(Map<String, dynamic> json) => BdrSummary(
    id: json['id']?.toString() ?? json['bdr-id']?.toString() ?? '',
    state: json['state']?.toString() ?? 'UNKNOWN',
    strategy:
        json['strategy']?.toString() ??
        json['run-id']?.toString() ??
        'Sem estrategia',
    updatedAt:
        json['updated-at']?.toString() ??
        json['updated_at']?.toString() ??
        _lastEventAt(json) ??
        json['created-at']?.toString() ??
        '',
    intent: _map(json['intent']).isNotEmpty
        ? _map(json['intent'])
        : <String, dynamic>{'symbol': _symbolFromEvents(json)},
  );

  String get symbol => intent['symbol']?.toString() ?? '—';
  String get side => intent['side']?.toString().toUpperCase() ?? '—';
}

class BdrDetail extends BdrSummary {
  const BdrDetail({
    required super.id,
    required super.state,
    required super.strategy,
    required super.updatedAt,
    required super.intent,
    required this.events,
  });

  final List<BdrEvent> events;

  factory BdrDetail.fromJson(Map<String, dynamic> json) => BdrDetail(
    id: json['id']?.toString() ?? json['bdr-id']?.toString() ?? '',
    state: json['state']?.toString() ?? 'UNKNOWN',
    strategy:
        json['strategy']?.toString() ??
        json['run-id']?.toString() ??
        'Sem estrategia',
    updatedAt:
        json['updated-at']?.toString() ??
        _lastEventAt(json) ??
        json['created-at']?.toString() ??
        '',
    intent: _map(json['intent']).isNotEmpty
        ? _map(json['intent'])
        : <String, dynamic>{'symbol': _symbolFromEvents(json)},
    events: (json['events'] as List<dynamic>? ?? const [])
        .whereType<Map>()
        .map((event) => BdrEvent.fromJson(Map<String, dynamic>.from(event)))
        .toList(),
  );
}

class BdrEvent {
  const BdrEvent({required this.type, required this.at, required this.payload});

  final String type;
  final String at;
  final Map<String, dynamic> payload;

  factory BdrEvent.fromJson(Map<String, dynamic> json) => BdrEvent(
    type: json['event-type']?.toString() ?? json['type']?.toString() ?? 'EVENT',
    at:
        json['at']?.toString() ??
        json['occurred-at']?.toString() ??
        json['created-at']?.toString() ??
        '',
    payload: _map(json['payload']),
  );
}

Map<String, dynamic> _map(Object? value) =>
    value is Map ? Map<String, dynamic>.from(value) : <String, dynamic>{};

String _symbolFromEvents(Map<String, dynamic> json) {
  final events = json['events'];
  if (events is! List) return '—';
  for (final rawEvent in events) {
    if (rawEvent is! Map) continue;
    final payload = _map(rawEvent['payload']);
    if (payload['symbol'] != null) return payload['symbol'].toString();
    final source = payload['source-uri']?.toString();
    if (source != null && source.isNotEmpty) {
      return source.split('/').last.toUpperCase();
    }
  }
  return '—';
}

String? _lastEventAt(Map<String, dynamic> json) {
  final events = json['events'];
  if (events is! List || events.isEmpty || events.last is! Map) return null;
  return (events.last as Map)['occurred-at']?.toString();
}
