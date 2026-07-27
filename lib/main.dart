import 'dart:async';
import 'dart:io';
import 'dart:ui';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:logger/logger.dart' show Level, Logger;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'main_shell.dart';
import 'repositories/anime_repository.dart';
import 'settings_manager.dart';
import 'services/service_locator.dart';
import 'theme_manager.dart';
import 'ui/app_theme.dart';
import 'ui/components/restart_widget.dart';
import 'ui/splash_screen.dart';
import 'utils/error_logger.dart';
import 'utils/notification_service.dart';
import 'utils/operation_log_service.dart';
import 'package:provider/provider.dart';
import 'providers/data_refresh_provider.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // 尽早安装全局错误捕获，连启动阶段的异常也能保留在内存错误日志中。
  FlutterError.onError = (FlutterErrorDetails details) {
    FlutterError.presentError(details);
    ErrorLogger.instance.addError(details.exception, details.stack);
  };

  PlatformDispatcher.instance.onError = (error, stack) {
    ErrorLogger.instance.addError(error, stack);
    return true; // 防止崩溃
  };

  // 初始化日期格式化数据 (修复日历红屏)
  await initializeDateFormatting('zh_CN', null);

  // 初始化主题与设置
  await ThemeManager().loadTheme();
  await SettingsManager().loadSettings();
  Logger.addLogListener((event) {
    if (event.level >= Level.error) {
      OperationLogService.instance.recordError(
        event.error ?? event.message,
        event.stackTrace,
        screen: '运行日志',
      );
    }
  });

  // 桌面端数据库初始化
  if (!kIsWeb && (Platform.isWindows || Platform.isLinux || Platform.isMacOS)) {
    sqfliteFfiInit();
    databaseFactory = databaseFactoryFfi;
  }
  setupServiceLocator();
  OperationLogService.instance.record('应用启动', screen: '启动');

  // 通知服务异常不应阻止 APP 启动；具体原因会在提醒管理页中展示。
  if (!kIsWeb && NotificationService().isSupported) {
    try {
      await NotificationService().init();
    } catch (error, stack) {
      ErrorLogger.instance.addError(error, stack);
    }
  }

  runApp(const RestartWidget(child: MyApp()));

  // 不在启动阶段弹权限窗口；仅在已有权限时恢复 Android/iOS 排程，
  // 并为不支持永久周期任务的 Windows 续排未来数周。
  if (!kIsWeb && NotificationService().isSupported) {
    unawaited(_restoreScheduledReminders());
  }
}

Future<void> _restoreScheduledReminders() async {
  try {
    final reminders = await getIt<AnimeRepository>().getAnimesWithReminders();
    final result = await NotificationService().synchronizeStoredReminders(
      reminders,
    );
    if (!result.isSuccess) {
      debugPrint('启动同步追番提醒未完全成功: ${result.failures.join('；')}');
    }
  } catch (error, stack) {
    ErrorLogger.instance.addError(error, stack);
  }
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return ChangeNotifierProvider<DataRefreshProvider>(
      create: (_) => DataRefreshProvider(),
      child: ValueListenableBuilder<Color>(
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
      ),
    );
  }
}
