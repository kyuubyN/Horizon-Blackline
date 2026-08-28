import 'package:flutter/material.dart';

import 'features/dashboard/dashboard_page.dart';
import 'services/horizon_api.dart';

class HorizonBlacklineApp extends StatelessWidget {
  const HorizonBlacklineApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'Horizon Blackline',
    debugShowCheckedModeBanner: false,
    theme: ThemeData(
      colorScheme: const ColorScheme.dark(
        primary: Color(0xffe61e4d),
        secondary: Color(0xff9f1454),
        surface: Color(0xff17111b),
        error: Color(0xffff5c6c),
      ),
      scaffoldBackgroundColor: const Color(0xff100b11),
      cardTheme: CardThemeData(
        elevation: 0,
        margin: EdgeInsets.zero,
        color: const Color(0xff1c1520),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(18),
          side: const BorderSide(color: Color(0xff342434)),
        ),
      ),
      navigationRailTheme: const NavigationRailThemeData(
        backgroundColor: Color(0xff170d18),
        indicatorColor: Color(0xff5a173e),
        selectedIconTheme: IconThemeData(color: Color(0xffffd8e2)),
        selectedLabelTextStyle: TextStyle(
          color: Color(0xffffd8e2),
          fontWeight: FontWeight.w700,
        ),
        unselectedIconTheme: IconThemeData(color: Color(0xffb9a5b2)),
        unselectedLabelTextStyle: TextStyle(color: Color(0xffb9a5b2)),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          backgroundColor: const Color(0xffd61b4e),
          foregroundColor: Colors.white,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
        ),
      ),
      useMaterial3: true,
    ),
    home: DashboardPage(api: HorizonApi()),
  );
}
