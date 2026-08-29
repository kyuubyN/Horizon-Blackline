import 'dart:convert';
import 'dart:io';

import '../models/bdr.dart';

class HorizonApi {
  HorizonApi({String? baseUrl, HttpClient? client})
    : baseUrl =
          baseUrl ??
          Platform.environment['HORIZON_API_URL'] ??
          'http://127.0.0.1:8080',
      _client = client ?? (HttpClient()..idleTimeout = const Duration(seconds: 15));

  static const _bodyTimeout = Duration(seconds: 10);

  final String baseUrl;
  final HttpClient _client;

  void close() => _client.close(force: true);

  Future<Map<String, dynamic>> health() => _get('/health');
  Future<Map<String, dynamic>> ready() => _getAllowing('/ready', {200, 503});
  Future<Map<String, dynamic>> system() => _get('/v1/system');
  Future<Map<String, dynamic>> agents() => _get('/v1/agents');
  Future<Map<String, dynamic>> metrics() => _get('/v1/metrics');
  Future<Map<String, dynamic>> officialCampaign() =>
      _get('/v1/campaign/official');
  Future<Map<String, dynamic>> captureOfficialBaseline() =>
      _post('/v1/campaign/official/baseline');
  Future<Map<String, dynamic>> captureOfficialSnapshot() =>
      _post('/v1/campaign/official/snapshot');

  Future<List<BdrSummary>> bdrs() async {
    final records = await _getList('/v1/bdr');
    return records
        .whereType<Map>()
        .map((record) => BdrSummary.fromJson(Map<String, dynamic>.from(record)))
        .toList();
  }

  Future<BdrDetail> bdr(String id) async =>
      BdrDetail.fromJson(await _get('/v1/bdr/$id'));

  Future<Map<String, dynamic>> replay(String id) => _get('/v1/bdr/$id/replay');
  Future<Map<String, dynamic>> auditExport(String id) =>
      _get('/v1/bdr/$id/export');
  Future<Map<String, dynamic>> latestStockQuote(String symbol) =>
      _get('/v1/market/quote/${Uri.encodeComponent(symbol)}');
  Future<Map<String, dynamic>> discover(Map<String, dynamic> quote) =>
      _post('/v1/intelligence/discover', quote);
  Future<Map<String, dynamic>> research(Map<String, dynamic> candidate) =>
      _post('/v1/intelligence/research', candidate);

  Future<Map<String, dynamic>> createBdr(Map<String, dynamic> draft) =>
      _post('/v1/bdr', draft);
  Future<Map<String, dynamic>> appendEvidence(
    String bdrId,
    Map<String, dynamic> evidence,
  ) => _post('/v1/bdr/$bdrId/evidence', evidence);
  Future<Map<String, dynamic>> challenge(
    String bdrId,
    Map<String, dynamic> bundle,
  ) => _post('/v1/bdr/$bdrId/challenge', bundle);
  Future<Map<String, dynamic>> appendDiscovery(
    String bdrId,
    Map<String, dynamic> candidate,
  ) => _post('/v1/bdr/$bdrId/discovery', candidate);
  Future<Map<String, dynamic>> appendResearch(
    String bdrId,
    Map<String, dynamic> thesis,
  ) => _post('/v1/bdr/$bdrId/research', thesis);
  Future<Map<String, dynamic>> authorize(Map<String, dynamic> command) =>
      _post('/v1/authorizations', command);

  Future<Map<String, dynamic>> runDemo() => _post('/v1/demo/run');
  Future<void> freeze({required String actor, required String reason}) async =>
      _post('/v1/system/freeze', {'actor': actor, 'reason': reason});
  Future<void> unfreeze({required String actor, required String reason}) async =>
      _post('/v1/system/unfreeze', {
        'actor': actor,
        'reason': reason,
        'operator-confirmation': 'UNFREEZE',
      });
  Future<Map<String, dynamic>> startMonitoring(String bdrId) =>
      _post('/v1/bdr/$bdrId/monitor');
  Future<Map<String, dynamic>> reevaluate(
    String bdrId, {
    required String decision,
    required String trigger,
  }) => _post('/v1/bdr/$bdrId/reevaluate', {
    'decision': decision,
    'trigger': trigger,
  });
  Future<Map<String, dynamic>> closeBdr(
    String bdrId, {
    required String reason,
  }) => _post('/v1/bdr/$bdrId/close', {'reason': reason});
  Future<Map<String, dynamic>> postMortem(
    String bdrId, {
    required String outcome,
    required List<String> limitations,
  }) => _post('/v1/bdr/$bdrId/post-mortem', {
    'outcome': outcome,
    'limitations': limitations,
    'actor': 'desktop-operator',
  });

  Future<Map<String, dynamic>> _get(String path) async {
    final request = await _client.getUrl(Uri.parse('$baseUrl$path'));
    final response = await request.close().timeout(const Duration(seconds: 5));
    return _decode(response);
  }

  Future<Map<String, dynamic>> _getAllowing(
    String path,
    Set<int> allowedStatuses,
  ) async {
    final request = await _client.getUrl(Uri.parse('$baseUrl$path'));
    final response = await request.close().timeout(const Duration(seconds: 5));
    final text = await utf8.decoder.bind(response).join().timeout(_bodyTimeout);
    final decoded = text.isEmpty ? <String, dynamic>{} : jsonDecode(text);
    if (!allowedStatuses.contains(response.statusCode)) {
      throw HorizonApiException(response.statusCode, decoded.toString());
    }
    return Map<String, dynamic>.from(decoded as Map);
  }

  Future<List<dynamic>> _getList(String path) async {
    final request = await _client.getUrl(Uri.parse('$baseUrl$path'));
    final response = await request.close().timeout(const Duration(seconds: 5));
    final text = await utf8.decoder.bind(response).join().timeout(_bodyTimeout);
    final decoded = text.isEmpty ? const <dynamic>[] : jsonDecode(text);
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw HorizonApiException(response.statusCode, decoded.toString());
    }
    if (decoded is! List) {
      throw HorizonApiException(response.statusCode, 'Expected a JSON list.');
    }
    return decoded;
  }

  Future<Map<String, dynamic>> _post(
    String path, [
    Map<String, dynamic> body = const {},
  ]) async {
    final request = await _client.postUrl(Uri.parse('$baseUrl$path'));
    request.headers.contentType = ContentType.json;
    request.write(jsonEncode(body));
    final response = await request.close().timeout(const Duration(seconds: 10));
    return _decode(response);
  }

  Future<Map<String, dynamic>> _decode(HttpClientResponse response) async {
    final text = await utf8.decoder.bind(response).join().timeout(_bodyTimeout);
    final decoded = text.isEmpty ? <String, dynamic>{} : jsonDecode(text);
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw HorizonApiException(response.statusCode, decoded.toString());
    }
    return Map<String, dynamic>.from(decoded as Map);
  }
}

class HorizonApiException implements Exception {
  const HorizonApiException(this.statusCode, this.message);
  final int statusCode;
  final String message;
  @override
  String toString() => 'Horizon API $statusCode: $message';
}
