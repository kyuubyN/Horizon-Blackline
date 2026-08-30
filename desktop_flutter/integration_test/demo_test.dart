// Scripted demo walkthrough for recording. Drives the real running app
// through Flutter's own widget tree (not screen coordinates), so it works
// under Wayland and is robust to window position/resolution. Run with:
//
//   flutter test integration_test/demo_test.dart -d linux
//
// while screen-recording (e.g. `ffmpeg -f x11grab ...` or a Wayland
// recorder). Requires a healthy local API already running at
// 127.0.0.1:8080 (bin/run-api) so the app connects immediately instead of
// trying to spawn a bundled sidecar.
//
// Never touches the freeze/kill-switch button: that is a real, disruptive
// action and is intentionally left out of an unattended demo script.

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';

import 'package:horizon_blackline_desktop/app.dart';

Future<void> hold(WidgetTester tester, [int seconds = 3]) async {
  await Future.delayed(Duration(seconds: seconds));
  await tester.pump();
}

Future<void> tapText(WidgetTester tester, String text) async {
  final finder = find.text(text).first;
  await tester.ensureVisible(finder);
  await tester.tap(finder);
  await tester.pumpAndSettle(const Duration(milliseconds: 400));
}

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('recorded demo walkthrough', (tester) async {
    await tester.pumpWidget(const HorizonBlacklineApp());
    // First load hits several real API calls; give it real time to settle.
    await tester.pumpAndSettle(const Duration(seconds: 5));
    await hold(tester, 3);

    // --- Run the MOCK journey: denial -> authorization -> sealed closure ---
    await tapText(tester, 'Run MOCK journey');
    await hold(tester, 2);
    await tester.tap(find.text('View denial'));
    await tester.pumpAndSettle();
    await hold(tester, 3);
    await tester.pageBack();
    await tester.pumpAndSettle();
    await hold(tester, 1);

    await tapText(tester, 'Run MOCK journey');
    await hold(tester, 2);
    await tester.tap(find.text('View authorization'));
    await tester.pumpAndSettle();
    await hold(tester, 3);
    await tester.pageBack();
    await tester.pumpAndSettle();
    await hold(tester, 1);

    await tapText(tester, 'Run MOCK journey');
    await hold(tester, 2);
    await tester.tap(find.text('Open final proof'));
    await tester.pumpAndSettle();
    await hold(tester, 4);
    await tester.pageBack();
    await tester.pumpAndSettle();
    await hold(tester, 2);

    // --- BDRs list ---
    await tapText(tester, 'BDRs');
    await hold(tester, 3);
    final firstBdr = find.byType(ListTile).first;
    await tester.tap(firstBdr);
    await tester.pumpAndSettle();
    await hold(tester, 4);
    await tester.pageBack();
    await tester.pumpAndSettle();
    await hold(tester, 1);

    // --- Controls: fail-closed guardrails ---
    await tapText(tester, 'Controls');
    await hold(tester, 4);

    // --- Agents: least-privilege registry ---
    await tapText(tester, 'Agents');
    await hold(tester, 3);

    // --- Campaign: official window / P&L ledger ---
    await tapText(tester, 'Campaign');
    await hold(tester, 4);
  });
}
