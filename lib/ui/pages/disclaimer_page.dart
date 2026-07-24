import 'package:flutter/material.dart';

/// 免责声明页面，说明应用的使用条款和注意事项
class DisclaimerPage extends StatelessWidget {
  const DisclaimerPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('免责声明')),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // 1. 软件性质
            _buildSectionTitle('1. 软件性质'),
            _buildParagraph(
              '本软件（追番喵）为免费的个人项目，仅供用户进行二次元文化交流、学习以及个人追番进度管理使用。'
              '本软件不收取任何费用，旨在为ACG爱好者提供便捷的记录工具。',
            ),

            // 2. 禁止倒卖
            _buildSectionTitle('2. 禁止倒卖'),
            _buildParagraph(
              '严禁任何个人或组织将本软件进行出售、二次打包盈利或用于任何商业用途。'
              '如果您是付费购买的本软件，请立即退款并举报卖家。'
              '本软件官方仅通过免费渠道发布。',
            ),

            // 3. 数据与隐私
            _buildSectionTitle('3. 数据与隐私'),
            _buildParagraph(
              '本软件为纯本地化应用，所有用户数据（包括但不限于追番记录、封面图片、评价备注）均存储于您的设备本地。'
              '开发者无法获取、也不会上传您的任何隐私数据。\n\n'
              '⚠️ 重要提示：请用户务必定期使用"设置-数据管理"中的备份功能。'
              '因误删应用、清理缓存或设备损坏导致的数据丢失，开发者不承担任何恢复责任。',
            ),

            // 4. 内容与版权
            _buildSectionTitle('4. 内容与版权'),
            _buildParagraph(
              '本软件仅作为信息管理工具（Tracker），不提供任何视频文件的存储、下载、链接或在线播放服务。'
              '软件内展示的番剧封面及简介等信息通常由用户自行添加或来源于第三方公开网络接口，其版权归原作者及制作公司所有。'
              '如有侵权，请联系作者删除。',
            ),

            // 5. 免责条款
            _buildSectionTitle('5. 免责条款'),
            _buildParagraph(
              '本软件按"现状"提供，开发者不对软件的适用性、无病毒或无错误作任何明示或暗示的保证。'
              '在法律允许的最大范围内，开发者对因使用或无法使用本软件而产生的任何直接、间接、意外或附带的损害不承担赔偿责任。',
            ),

            // 6. 联系作者
            _buildSectionTitle('6. 联系作者'),
            _buildParagraph(
              '如果您在使用过程中遇到 Bug、有功能建议，或发现潜在的法律风险，'
              '请通过设置页面的"联系作者"功能与我们取得联系。',
            ),

            // 页脚
            const SizedBox(height: 30),
            Center(
              child: Text(
                '最终解释权归开发者所有',
                style: TextStyle(color: Colors.grey[400], fontSize: 12),
              ),
            ),
            const SizedBox(height: 20),
          ],
        ),
      ),
    );
  }

  /// 构建章节标题
  Widget _buildSectionTitle(String title) {
    return Padding(
      padding: const EdgeInsets.only(top: 20.0, bottom: 8.0),
      child: Text(
        title,
        style: const TextStyle(
          fontSize: 18,
          fontWeight: FontWeight.bold,
          color: Colors.indigo,
        ),
      ),
    );
  }

  /// 构建段落文本
  Widget _buildParagraph(String text) {
    return Text(
      text,
      style: const TextStyle(fontSize: 15, height: 1.6, color: Colors.black87),
      textAlign: TextAlign.justify,
    );
  }
}
