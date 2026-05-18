import 'package:flutter/material.dart';
import '../settings_manager.dart';
import '../db/database_helper.dart';

class SettingsGeneralPage extends StatefulWidget {
  const SettingsGeneralPage({super.key});

  @override
  State<SettingsGeneralPage> createState() => _SettingsGeneralPageState();
}

class _SettingsGeneralPageState extends State<SettingsGeneralPage> {
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFFF8F9FA),
      appBar: AppBar(
        title: const Text(
          '通用设置',
          style: TextStyle(fontWeight: FontWeight.bold),
        ),
        centerTitle: true,
        elevation: 0,
        backgroundColor: Colors.transparent,
        leading: IconButton(
          icon: const Icon(Icons.arrow_back_ios_new, size: 20),
          onPressed: () => Navigator.pop(context),
        ),
      ),
      body: ListView(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        children: [
          _buildSectionTitle("编辑体验"),
          _buildSettingsCard([
            _buildDisplayItemToggle(
              "自动保存详情修改",
              SettingsManager().autoSaveDetailNotifier,
              'detail_auto_save',
              subtitle: "退出番剧详情页时自动保存所有更改",
              onToggle: (val) {
                if (val) {
                  SettingsManager().setShowItem(
                    'detail_discard_changes',
                    false,
                  );
                }
              },
            ),
            _buildDivider(),
            _buildDisplayItemToggle(
              "直接放弃详情修改",
              SettingsManager().discardDetailChangesNotifier,
              'detail_discard_changes',
              subtitle: "退出番剧详情页时直接放弃所有更改",
              onToggle: (val) {
                if (val) {
                  SettingsManager().setShowItem('detail_auto_save', false);
                }
              },
            ),
            _buildDivider(),
            _buildDisplayItemToggle(
              "集数满值自动归纳",
              SettingsManager().autoStatusTransitionNotifier,
              'auto_status_transition',
              subtitle: "观看集数达到总集数时，自动更新番剧状态",
            ),
            ValueListenableBuilder<bool>(
              valueListenable: SettingsManager().autoStatusTransitionNotifier,
              builder: (context, enabled, _) {
                if (!enabled) return const SizedBox.shrink();
                return Column(
                  children: [
                    _buildDivider(),
                    _buildCompletionStatusPickerTile(),
                  ],
                );
              },
            ),
          ]),
          const SizedBox(height: 24),
          _buildSectionTitle("启动设置"),
          _buildSettingsCard([_buildStatusPickerTile()]),
          const SizedBox(height: 24),
          _buildSectionTitle("底部导航入口"),
          _buildSettingsCard([
            _buildDisplayItemToggle(
              "显示日历入口",
              SettingsManager().showCalendarNotifier,
              'calendar',
            ),
            _buildDivider(),
            _buildDisplayItemToggle(
              "显示统计入口",
              SettingsManager().showStatisticsNotifier,
              'statistics',
            ),
            _buildDivider(),
            _buildDisplayItemToggle(
              "显示发现入口",
              SettingsManager().showDiscoveryNotifier,
              'discovery',
            ),
            _buildDivider(),
            _buildDisplayItemToggle(
              "显示资源库入口",
              SettingsManager().showServerSearchNotifier,
              'server_search',
              subtitle: "从服务器资源库搜索并添加番剧",
            ),
          ]),
          const SizedBox(height: 24),
          _buildSectionTitle("页面内入口"),
          _buildSettingsCard([
            _buildDisplayItemToggle(
              "显示云端资源发现",
              SettingsManager().showServerDiscoveryNotifier,
              'server_discovery',
              subtitle: "在「发现」/「资源库」顶部显示进阶云端搜索入口",
            ),
          ]),
        ],
      ),
    );
  }

  Widget _buildDivider() {
    return Divider(
      height: 1,
      indent: 60,
      endIndent: 20,
      color: Colors.grey[100],
    );
  }

  Widget _buildSectionTitle(String title) {
    return Padding(
      padding: const EdgeInsets.only(left: 8, bottom: 12),
      child: Text(
        title,
        style: TextStyle(
          fontSize: 14,
          fontWeight: FontWeight.bold,
          color: Colors.grey[600],
          letterSpacing: 1.2,
        ),
      ),
    );
  }

  Widget _buildSettingsCard(List<Widget> children) {
    return Container(
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(24),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.03),
            blurRadius: 15,
            offset: const Offset(0, 8),
          ),
        ],
      ),
      child: Column(children: children),
    );
  }

  Widget _buildDisplayItemToggle(
    String title,
    ValueNotifier<bool> notifier,
    String key, {
    String? subtitle,
    ValueChanged<bool>? onToggle,
  }) {
    return ValueListenableBuilder<bool>(
      valueListenable: notifier,
      builder: (context, value, _) {
        return InkWell(
          onTap: () {
            final newVal = !value;
            SettingsManager().setShowItem(key, newVal);
            if (onToggle != null) onToggle(newVal);
            setState(() {});
          },
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Row(
              children: [
                Container(
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    color: _getColorForKey(key).withValues(alpha: 0.12),
                    borderRadius: BorderRadius.circular(14),
                  ),
                  child: Icon(
                    _getIconForKey(key),
                    color: _getColorForKey(key),
                    size: 22,
                  ),
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        title,
                        style: const TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      if (subtitle != null)
                        Text(
                          subtitle,
                          style: TextStyle(
                            fontSize: 12,
                            color: Colors.grey[500],
                          ),
                        ),
                    ],
                  ),
                ),
                Switch(
                  value: value,
                  onChanged: (val) {
                    SettingsManager().setShowItem(key, val);
                    if (onToggle != null) onToggle(val);
                    setState(() {});
                  },
                ),
              ],
            ),
          ),
        );
      },
    );
  }

  Widget _buildCompletionStatusPickerTile() {
    return ValueListenableBuilder<String>(
      valueListenable: SettingsManager().completionStatusNotifier,
      builder: (context, current, _) {
        return InkWell(
          onTap: () => _showCompletionStatusDialog(current),
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Row(
              children: [
                Container(
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    color: Colors.green.withValues(alpha: 0.12),
                    borderRadius: BorderRadius.circular(14),
                  ),
                  child: const Icon(
                    Icons.done_all_rounded,
                    color: Colors.green,
                    size: 22,
                  ),
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text(
                        "完成自动归纳至",
                        style: TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      Text(
                        "当前状态: $current",
                        style: TextStyle(fontSize: 12, color: Colors.grey[500]),
                      ),
                    ],
                  ),
                ),
                Icon(
                  Icons.arrow_forward_ios,
                  size: 14,
                  color: Colors.grey[400],
                ),
              ],
            ),
          ),
        );
      },
    );
  }

  void _showCompletionStatusDialog(String current) async {
    final List<Map<String, dynamic>> statuses = await DatabaseHelper()
        .getAllStatuses();
    final List<String> options = statuses
        .map((e) => e['name'] as String)
        .toList();

    if (!mounted) return;

    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('选择完成归纳状态'),
        content: SizedBox(
          width: double.maxFinite,
          child: ListView.builder(
            shrinkWrap: true,
            itemCount: options.length,
            itemBuilder: (context, index) {
              final option = options[index];
              return RadioListTile<String>(
                title: Text(option),
                value: option,
                groupValue: current,
                onChanged: (val) {
                  if (val != null) {
                    SettingsManager().setCompletionStatus(val);
                    Navigator.pop(ctx);
                    setState(() {});
                  }
                },
              );
            },
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('取消'),
          ),
        ],
      ),
    );
  }

  Widget _buildStatusPickerTile() {
    return ValueListenableBuilder<String>(
      valueListenable: SettingsManager().defaultStartStatusNotifier,
      builder: (context, currentDefault, _) {
        return InkWell(
          onTap: () => _showDefaultStatusDialog(currentDefault),
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Row(
              children: [
                Container(
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    color: Colors.orange.withValues(alpha: 0.12),
                    borderRadius: BorderRadius.circular(14),
                  ),
                  child: const Icon(
                    Icons.home_outlined,
                    color: Colors.orange,
                    size: 22,
                  ),
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text(
                        "启动时默认状态",
                        style: TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      Text(
                        "当前: $currentDefault",
                        style: TextStyle(fontSize: 12, color: Colors.grey[500]),
                      ),
                    ],
                  ),
                ),
                Icon(
                  Icons.arrow_forward_ios,
                  size: 14,
                  color: Colors.grey[400],
                ),
              ],
            ),
          ),
        );
      },
    );
  }

  void _showDefaultStatusDialog(String current) async {
    final List<Map<String, dynamic>> statuses = await DatabaseHelper()
        .getAllStatuses();
    final List<String> options = [
      '全部',
      '上次退出前',
      ...statuses.map((e) => e['name'] as String),
    ];

    if (!mounted) return;

    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('选择启动默认状态'),
        content: SizedBox(
          width: double.maxFinite,
          child: ListView.builder(
            shrinkWrap: true,
            itemCount: options.length,
            itemBuilder: (context, index) {
              final option = options[index];
              return RadioListTile<String>(
                title: Text(option),
                value: option,
                groupValue: current,
                onChanged: (val) {
                  if (val != null) {
                    SettingsManager().setDefaultStartStatus(val);
                    Navigator.pop(ctx);
                    setState(() {});
                  }
                },
              );
            },
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('取消'),
          ),
        ],
      ),
    );
  }

  IconData _getIconForKey(String key) {
    switch (key) {
      case 'detail_auto_save':
        return Icons.save_outlined;
      case 'detail_discard_changes':
        return Icons.delete_outline;
      case 'title':
        return Icons.title_outlined;
      case 'rating':
        return Icons.pets_outlined;
      case 'progress':
        return Icons.playlist_add_check_outlined;
      case 'status':
        return Icons.label_outline;
      case 'calendar':
        return Icons.calendar_today_outlined;
      case 'statistics':
        return Icons.bar_chart_outlined;
      case 'discovery':
        return Icons.explore_outlined;
      case 'auto_status_transition':
        return Icons.auto_mode_rounded;
      case 'server_search':
        return Icons.cloud_download_outlined;
      case 'server_discovery':
        return Icons.cloud_outlined;
      default:
        return Icons.settings_outlined;
    }
  }

  Color _getColorForKey(String key) {
    switch (key) {
      case 'detail_auto_save':
      case 'detail_discard_changes':
        return Colors.blue;
      case 'title':
        return Colors.orange;
      case 'rating':
        return Colors.amber;
      case 'progress':
        return Colors.green;
      case 'status':
        return Colors.blue;
      case 'calendar':
        return Colors.indigo;
      case 'statistics':
        return Colors.deepPurple;
      case 'discovery':
        return Colors.purple;
      case 'auto_status_transition':
        return Colors.teal;
      case 'server_search':
        return Colors.lightBlue;
      case 'server_discovery':
        return Colors.cyan;
      default:
        return Colors.grey;
    }
  }
}
