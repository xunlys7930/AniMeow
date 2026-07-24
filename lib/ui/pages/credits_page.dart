import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class CreditsPage extends StatelessWidget {
  const CreditsPage({super.key});

  @override
  Widget build(BuildContext context) {
    final themeColor = Theme.of(context).primaryColor;
    return Scaffold(
      backgroundColor: Colors.grey[100],
      appBar: AppBar(title: const Text('鸣谢'), centerTitle: true),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _buildCreditCard(
            context,
            title: "特别鸣谢",
            icon: Icons.favorite,
            iconColor: Colors.redAccent,
            content: [
              _buildCreditItem("陈云", "慷慨赞助 20 元 & 提供诸多建议", isSpecial: true),
            ],
          ),
          const SizedBox(height: 16),
          _buildCreditCard(
            context,
            title: "建议贡献者",
            icon: Icons.lightbulb,
            iconColor: Colors.amber,
            content: [
              _buildCreditItem("凛", "提供宝贵建议"),
              _buildCreditItem("情迁ᵇˡᵘᵉ", "提供宝贵建议"),
              _buildCreditItem("玥然", "提供宝贵建议"),
              _buildCreditItem("杳音讯", "提供宝贵建议"),
              _buildCreditItem("乔木店长", "提供宝贵建议"), // “名字为空白（空格字符）”的优化处理
              _buildCreditItem("了不起的碳基生物", "提供宝贵建议"), // “名字为空白（空格字符）”的优化处理
              _buildCreditItem("全体群友", "为 APP 开发提供全方位的支持"),
            ],
          ),
          const SizedBox(height: 16),
          _buildCommunityCard(context),
          const SizedBox(height: 24),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  "温馨提示：",
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.bold,
                    color: themeColor.withValues(alpha: 0.8),
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  "由于技术实现、设计初衷等多方面原因，部分小伙伴的建议暂时还未完全实装。但请相信，每一份反馈我都已认真记录并将在未来的版本中逐步实现！",
                  style: TextStyle(
                    color: Colors.grey[600],
                    fontSize: 13,
                    height: 1.5,
                  ),
                ),
                const SizedBox(height: 12),
                Text(
                  "如果您发现名单中有所遗漏，或者有任何新的想法，请随时联系开发者。每一份支持都至关重要！",
                  style: TextStyle(
                    color: Colors.grey[600],
                    fontSize: 13,
                    height: 1.5,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 32),
          Center(
            child: Text(
              "您的支持是我持续开发的动力 ❤️",
              style: TextStyle(color: Colors.grey[400], fontSize: 13),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildCommunityCard(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(24),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Padding(
            padding: EdgeInsets.fromLTRB(20, 20, 20, 12),
            child: Row(
              children: [
                Icon(Icons.pets, color: Colors.pinkAccent, size: 22),
                SizedBox(width: 8),
                Text(
                  "加入我们",
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
                ),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 20),
            child: Text(
              "欢迎加入交流群一起讨论使用心得、反馈 Bug 或提出新想法喵~ ฅ( ̳• ◡ • ̳)ฅ",
              style: TextStyle(
                color: Colors.grey[700],
                fontSize: 14,
                height: 1.5,
              ),
            ),
          ),
          const SizedBox(height: 16),
          const Divider(height: 1, endIndent: 20, indent: 20),
          InkWell(
            onTap: () {
              Clipboard.setData(const ClipboardData(text: '1017300957'));
              ScaffoldMessenger.of(context).showSnackBar(
                SnackBar(
                  content: const Text('已复制交流群号到剪贴板喵~'),
                  behavior: SnackBarBehavior.floating,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(10),
                  ),
                ),
              );
            },
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
              child: Row(
                children: [
                  Icon(Icons.group, color: Colors.blue[400], size: 20),
                  const SizedBox(width: 8),
                  const Expanded(
                    child: Text(
                      "用户交流群：1017300957",
                      style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                  Text(
                    "点击复制群号",
                    style: TextStyle(fontSize: 12, color: Colors.grey[500]),
                  ),
                  const SizedBox(width: 4),
                  Icon(Icons.copy, size: 14, color: Colors.grey[400]),
                ],
              ),
            ),
          ),
          const SizedBox(height: 4),
        ],
      ),
    );
  }

  Widget _buildCreditCard(
    BuildContext context, {
    required String title,
    required IconData icon,
    required Color iconColor,
    required List<Widget> content,
  }) {
    return Container(
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(24),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 20, 20, 12),
            child: Row(
              children: [
                Icon(icon, color: iconColor, size: 22),
                const SizedBox(width: 8),
                Text(
                  title,
                  style: const TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ],
            ),
          ),
          ...content,
          const SizedBox(height: 12),
        ],
      ),
    );
  }

  Widget _buildCreditItem(
    String name,
    String description, {
    bool isSpecial = false,
  }) {
    return ListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 20),
      title: Text(
        name,
        style: TextStyle(
          fontWeight: isSpecial ? FontWeight.bold : FontWeight.normal,
          fontSize: 15,
        ),
      ),
      trailing: Text(
        description,
        style: TextStyle(
          color: isSpecial ? Colors.orange[700] : Colors.grey[600],
          fontSize: 14,
          fontWeight: isSpecial ? FontWeight.bold : FontWeight.normal,
        ),
      ),
    );
  }
}
