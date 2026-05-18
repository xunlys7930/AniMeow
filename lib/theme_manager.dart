import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// 主题管理器，负责应用主题色的管理和持久化
/// 使用单例模式确保全局只有一个实例
class ThemeManager {
  // 单例实例
  static final ThemeManager _instance = ThemeManager._internal();

  /// 单例工厂构造函数
  factory ThemeManager() => _instance;

  /// 私有构造函数
  ThemeManager._internal();

  // ============== 核心设置 ==============

  /// 主题色变化通知器，默认使用耐看的深靛青色
  final ValueNotifier<Color> colorNotifier = ValueNotifier(Colors.indigo);

  // ============== 二次元配色方案定义（精确 Hex 版本） ==============

  /// 预设主题配色方案列表
  final List<Map<String, dynamic>> themeColors = [
    {'name': '小粉红', 'color': const Color(0xFFFB7299), 'desc': '变身！代表月亮消灭你'},
    {
      'name': '基佬紫', // EVA 初号机紫
      'color': const Color(0xFF673AB7),
      'desc': '哲学气息浓厚，懂得都懂',
    },
    {'name': '智障蓝', 'color': const Color(0xFF23C9ED), 'desc': '充满了智慧的颜色'},
    {
      'name': '葱娘绿', // 初音未来 (Miku Teal)
      'color': const Color(0xFF39C5BB),
      'desc': '世界第一的公主殿下',
    },
    {
      'name': '三倍速红', // 夏亚专用红（带一点暗色）
      'color': const Color(0xFFD32F2F),
      'desc': '也就是说是普通颜色的三倍',
    },
    {
      'name': '橘里橘气', // 柑橘味香气（高亮橙）
      'color': const Color(0xFFFF9800),
      'desc': '大局已定 (Citrus)',
    },
    {
      'name': '皮卡黄', // 皮卡丘（使用 Amber 防止在白底上看不清）
      'color': const Color(0xFFFFC107),
      'desc': '十万伏特的高压警告',
    },
    {
      'name': '黑化模式', // 酷黑
      'color': const Color(0xFF424242),
      'desc': '我洗海带哟，洗海带哟',
    },
  ];

  // ============== 主题管理方法 ==============

  /// 从本地存储加载主题设置
  Future<void> loadTheme() async {
    final prefs = await SharedPreferences.getInstance();
    final int? colorValue = prefs.getInt('theme_color_value');

    if (colorValue != null) {
      colorNotifier.value = Color(colorValue);
    }
  }

  /// 切换主题色并保存到本地存储
  Future<void> changeTheme(Color newColor) async {
    // 更新内存中的颜色值
    colorNotifier.value = newColor;

    // 持久化存储到本地
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt('theme_color_value', newColor.value);
  }
}
