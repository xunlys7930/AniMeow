import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'settings/testing_features_page.dart';
import 'theme_manager.dart';
import 'settings/settings_data_page.dart';
import 'settings/settings_about_page.dart';
import 'settings/settings_display_page.dart';
import 'settings/settings_general_page.dart';
import 'settings/reminder_management_page.dart';
import 'package:package_info_plus/package_info_plus.dart';

/// 设置页面，采用简约化分级导航设计
class SettingsPage extends StatefulWidget {
  final VoidCallback? onDatabaseRefresh;

  const SettingsPage({super.key, this.onDatabaseRefresh});

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  String _version = "1.2.5"; // 默认回退值

  @override
  void initState() {
    super.initState();
    _loadVersion();
  }

  Future<void> _loadVersion() async {
    try {
      final packageInfo = await PackageInfo.fromPlatform();
      if (mounted) {
        setState(() {
          _version = packageInfo.version;
        });
      }
    } catch (_) {}
  }

  @override
  Widget build(BuildContext context) {
    final themeColor = Theme.of(context).primaryColor;
    return Scaffold(
      backgroundColor: const Color(0xFFF8F9FA),
      appBar: AppBar(
        title: const Text('设置', style: TextStyle(fontWeight: FontWeight.bold)),
        centerTitle: true,
        elevation: 0,
        backgroundColor: Colors.transparent,
      ),
      body: ListView(
        padding: const EdgeInsets.only(bottom: 100),
        children: [
          _buildAppHeader(themeColor),
          const SizedBox(height: 16),

          _buildSectionTitle("界面与显示"),
          _buildSettingsCard([
            _buildMenuTile(
              icon: Icons.notifications_active_outlined,
              color: Colors.orange,
              title: "追番提醒管理",
              subtitle: "查看并管理所有已设定的追番提醒",
              onTap: () => Navigator.push(
                context,
                MaterialPageRoute(
                  builder: (context) => const ReminderManagementPage(),
                ),
              ),
            ),
            _buildDivider(),
            _buildMenuTile(
              icon: Icons.tune_rounded,
              color: Colors.indigo,
              title: "通用管理",
              subtitle: "交互、自动保存等全局配置",
              onTap: () => Navigator.push(
                context,
                MaterialPageRoute(
                  builder: (context) => const SettingsGeneralPage(),
                ),
              ),
            ),
            _buildDivider(),
            _buildMenuTile(
              icon: Icons.palette_outlined,
              color: Colors.purple,
              title: "个性化主题",
              subtitle: "选择你喜欢的应用配色",
              trailing: CircleAvatar(
                radius: 10,
                backgroundColor: ThemeManager().colorNotifier.value,
              ),
              onTap: () => _showThemePicker(context),
            ),
            _buildDivider(),
            _buildMenuTile(
              icon: Icons.dashboard_customize_outlined,
              color: Colors.blue,
              title: "展示定制",
              subtitle: "海报墙、字体及数据显隐",
              onTap: () async {
                await Navigator.push(
                  context,
                  MaterialPageRoute(
                    builder: (context) => const SettingsDisplayPage(),
                  ),
                );
                setState(() {});
              },
            ),
          ]),

          _buildSectionTitle("数据与关于"),
          _buildSettingsCard([
            _buildMenuTile(
              icon: Icons.storage_outlined,
              color: Colors.blue,
              title: "数据与管理",
              subtitle: "管理状态、标签、备份导出及缓存",
              onTap: () => Navigator.push(
                context,
                MaterialPageRoute(
                  builder: (_) => SettingsDataPage(
                    onDatabaseRefresh: widget.onDatabaseRefresh,
                  ),
                ),
              ),
            ),
            _buildDivider(),
            _buildMenuTile(
              icon: Icons.info_outline,
              color: Colors.indigo,
              title: "关于与支持",
              subtitle: "更新日志、反馈及软件信息",
              onTap: () => Navigator.push(
                context,
                MaterialPageRoute(builder: (_) => const SettingsAboutPage()),
              ),
            ),
            _buildDivider(),
            _buildMenuTile(
              icon: Icons.group_add_outlined,
              color: Colors.blueAccent,
              title: "加入 QQ 交流群",
              subtitle: "群号: 1073623448",
              onTap: () => _showQQGroupDialog(context, "1073623448"),
            ),
            _buildDivider(),
            _buildMenuTile(
              icon: Icons.science,
              color: Colors.orange,
              title: '测试功能',
              subtitle: '错误日志与更多实验性功能',
              onTap: () {
                Navigator.push(
                  context,
                  MaterialPageRoute(
                    builder: (context) => const TestingFeaturesPage(),
                  ),
                );
              },
            ),
          ]),

          const SizedBox(height: 40),
          Center(
            child: Text(
              "陪你记录每一份热爱",
              style: TextStyle(
                color: Colors.grey[400],
                fontSize: 13,
                letterSpacing: 1.2,
              ),
            ),
          ),
        ],
      ),
    );
  }

  // --- UI 构建组件 ---

  Widget _buildAppHeader(Color color) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
      child: Row(
        children: [
          Container(
            width: 72,
            height: 72,
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.circular(32),
              boxShadow: [
                BoxShadow(
                  color: color.withValues(alpha: 0.08),
                  blurRadius: 20,
                  offset: const Offset(0, 10),
                ),
              ],
            ),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(32),
              child: Image.asset(
                'assets/icon.png',
                errorBuilder: (context, error, stackTrace) =>
                    Icon(Icons.pets_outlined, size: 36, color: color),
              ),
            ),
          ),
          const SizedBox(width: 20),
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              RichText(
                text: TextSpan(
                  style: const TextStyle(
                    color: Colors.black,
                    fontSize: 24,
                    fontWeight: FontWeight.w900,
                    letterSpacing: -0.5,
                  ),
                  children: [
                    const TextSpan(text: "追番喵"),
                    TextSpan(
                      text: " AniMeow",
                      style: TextStyle(
                        fontSize: 16,
                        color: Colors.grey[400],
                        fontWeight: FontWeight.w500,
                        fontStyle: FontStyle.italic,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 6),
              Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 10,
                  vertical: 4,
                ),
                decoration: BoxDecoration(
                  color: color.withValues(alpha: 0.08),
                  borderRadius: BorderRadius.circular(16),
                ),
                child: Text(
                  "Version $_version",
                  style: TextStyle(
                    color: color,
                    fontSize: 11,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildSectionTitle(String title) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(24, 20, 16, 10),
      child: Text(
        title,
        style: const TextStyle(
          fontSize: 14,
          fontWeight: FontWeight.bold,
          color: Colors.black54,
        ),
      ),
    );
  }

  Widget _buildSettingsCard(List<Widget> children) {
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 16),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(32),
      ),
      child: Column(children: children),
    );
  }

  Widget _buildDivider() {
    return const Divider(
      height: 1,
      indent: 64,
      endIndent: 20,
      color: Color(0xFFF1F3F5),
    );
  }

  Widget _buildMenuTile({
    required IconData icon,
    required Color color,
    required String title,
    String? subtitle,
    Widget? trailing,
    required VoidCallback onTap,
  }) {
    return ListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      leading: Container(
        padding: const EdgeInsets.all(10),
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.08),
          borderRadius: BorderRadius.circular(20),
        ),
        child: Icon(icon, color: color, size: 24),
      ),
      title: Text(
        title,
        style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 16),
      ),
      subtitle: subtitle != null
          ? Text(
              subtitle,
              style: TextStyle(color: Colors.grey[500], fontSize: 12),
            )
          : null,
      trailing:
          trailing ??
          const Icon(
            Icons.arrow_forward_ios,
            size: 14,
            color: Color(0xFFCED4DA),
          ),
      onTap: onTap,
    );
  }

  void _showThemePicker(BuildContext context) {
    // 逻辑内容保持不变，仅更新 UI 风格
    showModalBottomSheet(
      context: context,
      backgroundColor: Colors.white,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(32)),
      ),
      builder: (context) => Container(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              "选择本命色",
              style: TextStyle(fontSize: 20, fontWeight: FontWeight.w900),
            ),
            const SizedBox(height: 20),
            Expanded(
              child: GridView.builder(
                gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                  crossAxisCount: 4,
                  mainAxisSpacing: 16,
                  crossAxisSpacing: 16,
                  childAspectRatio: 0.85,
                ),
                itemCount: ThemeManager().themeColors.length,
                itemBuilder: (context, index) {
                  final item = ThemeManager().themeColors[index];
                  final color = item['color'] as Color;
                  final isSelected =
                      ThemeManager().colorNotifier.value.value == color.value;
                  return InkWell(
                    onTap: () {
                      ThemeManager().changeTheme(color);
                      Navigator.pop(context);
                    },
                    child: Column(
                      children: [
                        Container(
                          width: 52,
                          height: 52,
                          decoration: BoxDecoration(
                            color: color,
                            shape: BoxShape.circle,
                            border: isSelected
                                ? Border.all(color: Colors.black, width: 2)
                                : null,
                            boxShadow: [
                              BoxShadow(
                                color: color.withValues(alpha: 0.3),
                                blurRadius: 10,
                                offset: const Offset(0, 6),
                              ),
                            ],
                          ),
                          child: isSelected
                              ? const Icon(Icons.check, color: Colors.white)
                              : null,
                        ),
                        const SizedBox(height: 8),
                        Text(
                          item['name'],
                          style: const TextStyle(
                            fontSize: 12,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                      ],
                    ),
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }

  void _showQQGroupDialog(BuildContext context, String groupNumber) {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text("加入 QQ 交流群"),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text("交流群号："),
            const SizedBox(height: 8),
            Text(
              groupNumber,
              style: const TextStyle(
                fontSize: 20,
                fontWeight: FontWeight.bold,
                color: Colors.blueAccent,
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text("取消"),
          ),
          ElevatedButton(
            onPressed: () {
              _copyToClipboard(context, groupNumber, "群号已复制");
              Navigator.pop(context);
            },
            child: const Text("复制群号"),
          ),
        ],
      ),
    );
  }

  void _copyToClipboard(
    BuildContext context,
    String text,
    String message,
  ) async {
    await Clipboard.setData(ClipboardData(text: text));
    if (context.mounted) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(message)));
    }
  }
}
