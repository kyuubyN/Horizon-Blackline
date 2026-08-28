import 'dart:convert';
import 'dart:io';

class AuditExport {
  /// Saves only data already returned by the local API. Secrets are never part
  /// of a BDR export. The directory can be set explicitly for managed devices.
  static Future<File> save(
    Map<String, dynamic> payload,
    String bdrId, {
    String? directory,
  }) async {
    final configured = Platform.environment['HORIZON_EXPORT_DIR'];
    final base =
        directory ??
        configured ??
        '${Platform.environment['HOME'] ?? Directory.current.path}/Documents/Horizon Blackline';
    final targetDirectory = await Directory(base).create(recursive: true);
    final safeId = bdrId.replaceAll(RegExp(r'[^a-zA-Z0-9-]'), '_');
    final stamp = DateTime.now().toUtc().microsecondsSinceEpoch;
    final file = File(
      '${targetDirectory.path}${Platform.pathSeparator}bdr-$safeId-$stamp.audit.json',
    );
    await file.writeAsString(
      const JsonEncoder.withIndent('  ').convert(payload),
      flush: true,
    );
    return file;
  }
}
