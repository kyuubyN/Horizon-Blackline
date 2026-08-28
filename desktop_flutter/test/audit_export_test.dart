import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:horizon_blackline_desktop/services/audit_export.dart';

void main() {
  test(
    'salva uma prova auditavel em JSON sem sobrescrever exportacoes',
    () async {
      final directory = await Directory.systemTemp.createTemp(
        'horizon-export-test-',
      );
      addTearDown(() => directory.delete(recursive: true));
      final payload = {
        'format': 'horizon-blackline/audit-export@1',
        'replay': {'valid?': true},
      };

      final first = await AuditExport.save(
        payload,
        'bdr/unsafe-id',
        directory: directory.path,
      );
      final second = await AuditExport.save(
        payload,
        'bdr/unsafe-id',
        directory: directory.path,
      );

      expect(first.existsSync(), isTrue);
      expect(second.existsSync(), isTrue);
      expect(first.path, isNot(second.path));
      expect(
        jsonDecode(await first.readAsString())['replay']['valid?'],
        isTrue,
      );
    },
  );
}
