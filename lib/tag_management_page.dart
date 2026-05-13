import 'package:flutter/material.dart';
import 'db/database_helper.dart';

/// 标签管理页面，用于查看、编辑和删除标签
class TagManagementPage extends StatefulWidget {
  const TagManagementPage({super.key});

  @override
  State<TagManagementPage> createState() => _TagManagementPageState();
}

class _TagManagementPageState extends State<TagManagementPage> {
  // 标签及其使用计数列表
  List<Map<String, dynamic>> _tagCounts = [];
  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    _loadTagData();
  }

  /// 从数据库加载标签及其使用次数
  Future<void> _loadTagData() async {
    setState(() => _isLoading = true);
    final counts = await DatabaseHelper().getTagCounts();
    if (mounted) {
      setState(() {
        _tagCounts = counts;
        _isLoading = false;
      });
    }
  }

  /// 显示编辑标签对话框
  void _editTag(Map<String, dynamic> tag) {
    final controller = TextEditingController(text: tag['name']);

    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('重命名标签'),
        content: TextField(
          controller: controller,
          decoration: const InputDecoration(
            labelText: '标签名称',
            hintText: '输入新名称',
          ),
          autofocus: true,
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () async {
              final newName = controller.text.trim();
              if (newName.isNotEmpty && newName != tag['name']) {
                await DatabaseHelper().updateTag(tag['id'], newName);
                _loadTagData();
              }
              if (mounted) Navigator.pop(context);
            },
            child: const Text('保存'),
          ),
        ],
      ),
    );
  }

  /// 显示删除标签确认对话框
  void _deleteTag(Map<String, dynamic> tag) {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('删除标签'),
        content: Text(
          '确定要删除标签 "${tag['name']}" 吗？\n'
          '删除后，已关联该标签的番剧将不再拥有此标签。',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: Colors.red),
            onPressed: () async {
              await DatabaseHelper().deleteTag(tag['id']);
              _loadTagData();
              if (mounted) Navigator.pop(context);
            },
            child: const Text('确认删除'),
          ),
        ],
      ),
    );
  }

  /// 显示创建标签对话框
  void _addTag() {
    final controller = TextEditingController();
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('新建标签'),
        content: TextField(
          controller: controller,
          decoration: const InputDecoration(hintText: "例如：#热血"),
          autofocus: true,
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () async {
              final name = controller.text.trim();
              if (name.isNotEmpty) {
                await DatabaseHelper().insertTag(name);
                _loadTagData();
              }
              if (mounted) Navigator.pop(context);
            },
            child: const Text('创建'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final themeColor = Theme.of(context).primaryColor;
    return Scaffold(
      backgroundColor: Colors.grey[50], // 浅灰色背景
      appBar: AppBar(
        title: const Text('标签管理'),
        centerTitle: true,
        backgroundColor: Colors.transparent,
        scrolledUnderElevation: 0,
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : _tagCounts.isEmpty
          ? Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(
                    Icons.label_off_outlined,
                    size: 64,
                    color: Colors.grey[300],
                  ),
                  const SizedBox(height: 16),
                  Text('暂无标签', style: TextStyle(color: Colors.grey[500])),
                ],
              ),
            )
          : ListView.separated(
              padding: const EdgeInsets.all(16),
              itemCount: _tagCounts.length,
              separatorBuilder: (context, index) => const SizedBox(height: 12),
              itemBuilder: (context, index) {
                final tag = _tagCounts[index];
                return Container(
                  decoration: BoxDecoration(
                    color: Colors.white,
                    borderRadius: BorderRadius.circular(16),
                    boxShadow: [
                      BoxShadow(
                        color: Colors.black.withValues(alpha: 0.03),
                        blurRadius: 10,
                        offset: const Offset(0, 4),
                      ),
                    ],
                  ),
                  child: ListTile(
                    contentPadding: const EdgeInsets.symmetric(
                      horizontal: 20,
                      vertical: 8,
                    ),
                    leading: CircleAvatar(
                      backgroundColor: themeColor.withValues(alpha: 0.1),
                      child: Icon(
                        Icons.label_outlined,
                        color: themeColor,
                        size: 20,
                      ),
                    ),
                    title: Text(
                      tag['name'],
                      style: const TextStyle(fontWeight: FontWeight.bold),
                    ),
                    subtitle: Padding(
                      padding: const EdgeInsets.only(top: 4.0),
                      child: Text(
                        "${tag['count']} 部番剧使用",
                        style: TextStyle(fontSize: 12, color: Colors.grey[600]),
                      ),
                    ),
                    trailing: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        IconButton(
                          icon: const Icon(Icons.edit_outlined, size: 20),
                          onPressed: () => _editTag(tag),
                          tooltip: '重命名',
                        ),
                        IconButton(
                          icon: const Icon(
                            Icons.delete_outline,
                            size: 20,
                            color: Colors.redAccent,
                          ),
                          onPressed: () => _deleteTag(tag),
                          tooltip: '删除',
                        ),
                      ],
                    ),
                  ),
                );
              },
            ),
      floatingActionButton: FloatingActionButton(
        onPressed: _addTag,
        backgroundColor: themeColor,
        tooltip: '新建标签',
        child: const Icon(Icons.add, color: Colors.white),
      ),
    );
  }
}
