import 'dart:io';
import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:provider/provider.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'main_shell.dart';
import 'providers/data_refresh_provider.dart';
import 'settings_manager.dart';
import 'theme_manager.dart';
import 'ui/app_theme.dart';
import 'ui/components/restart_widget.dart';
import 'ui/splash_screen.dart';
import 'utils/error_logger.dart';
import 'utils/notification_service.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // 初始化日期格式化数据 (修复日历红屏)
  await initializeDateFormatting('zh_CN', null);

  // 初始化主题与设置
  await ThemeManager().loadTheme();
  await SettingsManager().loadSettings();
  await NotificationService().init();

  // 桌面端数据库初始化
  if (Platform.isWindows || Platform.isLinux || Platform.isMacOS) {
    sqfliteFfiInit();
    databaseFactory = databaseFactoryFfi;
  }

  // 全局错误捕获
  FlutterError.onError = (FlutterErrorDetails details) {
    FlutterError.presentError(details);
    ErrorLogger.instance.addError(details.exception, details.stack);
  };

  PlatformDispatcher.instance.onError = (error, stack) {
    ErrorLogger.instance.addError(error, stack);
    return true; // 防止崩溃
  };

  runApp(
    MultiProvider(
      providers: [ChangeNotifierProvider(create: (_) => DataRefreshProvider())],
      child: const RestartWidget(child: MyApp()),
    ),
  );
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<Color>(
      valueListenable: ThemeManager().colorNotifier,
      builder: (context, currentColor, child) {
        return ValueListenableBuilder<double>(
          valueListenable: SettingsManager().fontScaleNotifier,
          builder: (context, fontScale, child) {
            return MaterialApp(
              title: '追番喵',
              debugShowCheckedModeBanner: false,
              scrollBehavior: const MaterialScrollBehavior().copyWith(
                dragDevices: {
                  PointerDeviceKind.mouse,
                  PointerDeviceKind.touch,
                  PointerDeviceKind.stylus,
                  PointerDeviceKind.trackpad,
                },
              ),

              // M3 Expressive 主题（集中在 lib/ui/app_theme.dart）
              theme: buildAppTheme(currentColor),

              // 全局字体缩放注入
              builder: (context, child) {
                final mediaQuery = MediaQuery.of(context);
                return MediaQuery(
                  data: mediaQuery.copyWith(
                    textScaler: TextScaler.linear(fontScale),
                  ),
                  child: child!,
                );
              },

              localizationsDelegates: const [
                GlobalMaterialLocalizations.delegate,
                GlobalWidgetsLocalizations.delegate,
                GlobalCupertinoLocalizations.delegate,
              ],
              supportedLocales: const [
                Locale('zh', 'CN'),
                Locale('en', 'US'),
              ],

              // 首页 = 4-Tab 主壳（替代旧的 MainScreen）
              home: const SplashScreen(next: MainShell()),
            );
          },
        );
      },
    );
  }
}
