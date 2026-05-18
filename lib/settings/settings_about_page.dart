import 'dart:io';
import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:package_info_plus/package_info_plus.dart';
import '../changelog_page.dart';
import '../disclaimer_page.dart';
import '../api/update_service.dart';
import '../credits_page.dart';

class SettingsAboutPage extends StatefulWidget {
  const SettingsAboutPage({super.key});

  @override
  State<SettingsAboutPage> createState() => _SettingsAboutPageState();
}

class _SettingsAboutPageState extends State<SettingsAboutPage> {
  String _version = "...";

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
    return Scaffold(
      backgroundColor: Colors.grey[100],
      appBar: AppBar(title: const Text('关于与支持'), centerTitle: true),
      body: ListView(
        padding: const EdgeInsets.symmetric(vertical: 16),
        children: [
          _buildInfoCard(context),
          _buildGroupTitle("软件信息"),
          _buildAboutCard([
            _buildAboutTile(
              context,
              icon: Icons.history,
              color: Colors.deepPurple,
              title: "更新日志",
              onTap: () => Navigator.push(
                context,
                MaterialPageRoute(builder: (context) => const ChangelogPage()),
              ),
            ),
            _buildDivider(),
            _buildAboutTile(
              context,
              icon: Icons.system_update_alt,
              color: Colors.blue,
              title: "检查更新",
              onTap: () =>
                  UpdateService.checkUpdate(context, showNoUpdate: true),
            ),
            _buildDivider(),
            _buildAboutTile(
              context,
              icon: Icons.gavel,
              color: Colors.brown,
              title: "免责声明",
              onTap: () => Navigator.push(
                context,
                MaterialPageRoute(builder: (context) => const DisclaimerPage()),
              ),
            ),
          ]),
          _buildGroupTitle("社交与反馈"),
          _buildAboutCard([
            _buildAboutTile(
              context,
              icon: Icons.tv,
              color: Colors.pinkAccent,
              title: "关注 Bilibili",
              subtitle: "@巡xUx",
              onTap: () => _openBilibili(context),
            ),
            _buildDivider(),
            _buildAboutTile(
              context,
              icon: Icons.volunteer_activism,
              color: Colors.orange,
              title: "鸣谢与支持",
              subtitle: "感谢所有为 APP 提供帮助的人",
              onTap: () => Navigator.push(
                context,
                MaterialPageRoute(builder: (context) => const CreditsPage()),
              ),
            ),
          ]),
          const SizedBox(height: 32),
          Center(
            child: Column(
              children: [
                Text(
                  "AniMeow",
                  style: TextStyle(
                    color: Colors.grey[400],
                    fontSize: 14,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  "Made with ❤️ by 巡xUx",
                  style: TextStyle(color: Colors.grey[400], fontSize: 12),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildInfoCard(BuildContext context) {
    final themeColor = Theme.of(context).primaryColor;
    return Container(
      margin: const EdgeInsets.all(16),
      padding: const EdgeInsets.symmetric(vertical: 32),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(24),
      ),
      child: Column(
        children: [
          Container(
            width: 80,
            height: 80,
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.circular(20),
              boxShadow: [
                BoxShadow(
                  color: themeColor.withValues(alpha: 0.1),
                  blurRadius: 20,
                  offset: const Offset(0, 10),
                ),
              ],
            ),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(20),
              child: Image.asset(
                'assets/icon.png',
                errorBuilder: (context, error, stackTrace) =>
                    Icon(Icons.pets, size: 40, color: themeColor),
              ),
            ),
          ),
          const SizedBox(height: 16),
          RichText(
            text: TextSpan(
              style: const TextStyle(
                fontSize: 22,
                fontWeight: FontWeight.bold,
                color: Colors.black,
              ),
              children: [
                const TextSpan(text: "追番喵"),
                TextSpan(
                  text: " AniMeow",
                  style: TextStyle(
                    fontSize: 14,
                    color: Colors.grey[400],
                    fontWeight: FontWeight.normal,
                    fontStyle: FontStyle.italic,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 8),
          Text(
            "Version $_version",
            style: TextStyle(color: Colors.grey[500], fontSize: 14),
          ),
        ],
      ),
    );
  }

  Widget _buildGroupTitle(String title) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(24, 16, 16, 8),
      child: Text(
        title,
        style: const TextStyle(
          fontSize: 13,
          fontWeight: FontWeight.bold,
          color: Colors.grey,
        ),
      ),
    );
  }

  Widget _buildAboutCard(List<Widget> children) {
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(20),
      ),
      child: Column(children: children),
    );
  }

  Widget _buildDivider() {
    return const Divider(
      height: 1,
      indent: 64,
      endIndent: 16,
      color: Color(0xFFF5F5F5),
    );
  }

  Widget _buildAboutTile(
    BuildContext context, {
    required IconData icon,
    required Color color,
    required String title,
    String? subtitle,
    required VoidCallback onTap,
  }) {
    return ListTile(
      leading: Container(
        padding: const EdgeInsets.all(10),
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.08),
          borderRadius: BorderRadius.circular(14),
        ),
        child: Icon(icon, color: color, size: 24),
      ),
      title: Text(
        title,
        style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 15),
      ),
      subtitle: subtitle != null
          ? Text(
              subtitle,
              style: TextStyle(color: Colors.grey[500], fontSize: 12),
            )
          : null,
      trailing: const Icon(
        Icons.arrow_forward_ios,
        size: 14,
        color: Colors.grey,
      ),
      onTap: onTap,
    );
  }

  // --- 逻辑方法 ---

  void _openBilibili(BuildContext context) async {
    const String spaceId = "60675099";
    final Uri appUri = Uri.parse("bilibili://space/$spaceId");
    final Uri webUri = Uri.parse("https://space.bilibili.com/$spaceId");
    try {
      if (Platform.isAndroid || Platform.isIOS) {
        if (await launchUrl(appUri, mode: LaunchMode.externalApplication)) {
          return;
        }
      }
      await launchUrl(webUri, mode: LaunchMode.platformDefault);
    } catch (e) {
      launchUrl(webUri, mode: LaunchMode.platformDefault);
    }
  }
}
