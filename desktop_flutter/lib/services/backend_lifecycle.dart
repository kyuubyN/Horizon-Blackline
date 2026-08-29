import 'dart:convert';
import 'dart:io';

import 'horizon_api.dart';

/// Owns only a locally bundled Clojure sidecar. It never starts a shell and it
/// never receives broker credentials: those remain in the sidecar environment.
class BackendLifecycle {
  BackendLifecycle({
    required this.api,
    String? executable,
    String? appExecutable,
  }) : _configuredExecutable =
           executable ?? Platform.environment['HORIZON_BACKEND_EXECUTABLE'],
       _appExecutable = appExecutable ?? Platform.resolvedExecutable;

  final HorizonApi api;
  final String? _configuredExecutable;
  final String _appExecutable;
  Process? _process;

  Future<void> ensureAvailable() async {
    if (await _isHealthy()) return;
    if (!await _startBundledSidecar()) {
      throw const BackendUnavailable(
        BackendIssue.sidecarNotFound,
        'Nao encontrei um sidecar backend/bin/run-api neste pacote.',
      );
    }
    for (var attempt = 0; attempt < 20; attempt++) {
      if (await _isHealthy()) return;
      await Future<void>.delayed(const Duration(milliseconds: 500));
    }
    throw const BackendUnavailable(
      BackendIssue.startupTimedOut,
      'O sidecar nao respondeu em 10 segundos.',
    );
  }

  Future<bool> _isHealthy() async {
    try {
      final health = await api.health();
      return health['status'] == 'ok' && health['paper-only'] == true;
    } catch (_) {
      return false;
    }
  }

  Future<bool> _startBundledSidecar() async {
    if (_process != null) return true;
    final executable =
        _configuredExecutable ?? bundledSidecarPath(_appExecutable);
    if (executable == null || !File(executable).existsSync()) return false;
    try {
      _process = await Process.start(
        executable,
        const [],
        runInShell: false,
        workingDirectory: File(executable).parent.parent.path,
      );
      _process!.stdout
          .transform(utf8.decoder)
          .transform(const LineSplitter())
          .listen((line) => print('[backend] $line'));
      _process!.stderr
          .transform(utf8.decoder)
          .transform(const LineSplitter())
          .listen((line) => print('[backend:err] $line'));
      return true;
    } on ProcessException {
      return false;
    }
  }

  Future<void> dispose() async {
    final process = _process;
    if (process != null) process.kill();
    _process = null;
  }

  static String? bundledSidecarPath(String appExecutable) {
    final bundle = File(appExecutable).parent;
    final candidate =
        '${bundle.path}${Platform.pathSeparator}backend${Platform.pathSeparator}bin${Platform.pathSeparator}run-api';
    return File(candidate).existsSync() ? candidate : null;
  }
}

enum BackendIssue { sidecarNotFound, startupTimedOut }

class BackendUnavailable implements Exception {
  const BackendUnavailable(this.issue, this.message);
  final BackendIssue issue;
  final String message;
  @override
  String toString() => message;
}
