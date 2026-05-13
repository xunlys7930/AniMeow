import 'package:flutter/material.dart';
import 'db/database_helper.dart';

class StatusManagementPage extends StatefulWidget {
  const StatusManagementPage({super.key});

  @override
  State<StatusManagementPage> createState() => _StatusManagementPageState();
}

class _StatusManagementPageState extends State<StatusManagementPage> {
  List<Map<String, dynamic>> _statuses = [];
  bool _isLoading = true;

  // 预定义颜色选项
  final List<Color> _colorOptions = [
    Colors.blue,
    Colors.green,
    Colors.orange,
    Colors.red,
    Colors.purple,
    Colors.teal,
    Colors.pink,
    Colors.indigo,
    Colors.amber,
    Colors.cyan,
    Colors.brown,
    Colors.grey,
  ];

  @override
  void initState() {
    super.initState();
    _loadStatuses();
  }

  Future<void> _loadStatuses() async {
    final list = await DatabaseHelper().getAllStatuses();
    if (mounted) {
      setState(() {
        _statuses = List.from(list);
        _isLoading = false;
      });
    }
  }

  Future<void> _addOrUpdateStatus({
    Map<String, dynamic>? existingStatus,
  }) async {
    final isEdit = existingStatus != null;
    final nameController = TextEditingController(
      text: isEdit ? existingStatus['name'] : '',
    );
    Color selectedColor = isEdit
        ? Color(existingStatus['color'])
        : _colorOptions[0];

    await showDialog(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, setState) {
          return AlertDialog(
            title: Text(isEdit ? '编辑状态' : '新建状态'),
            content: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                TextField(
                  controller: nameController,
                  decoration: const InputDecoration(
                    labelText: "状态名称",
                    hintText: "例如：弃坑",
                    border: OutlineInputBorder(),
                  ),
                  autofocus: true,
                ),
                const SizedBox(height: 16),
                const Align(
                  alignment: Alignment.centerLeft,
                  child: Text(
                    "选择颜色",
                    style: TextStyle(fontWeight: FontWeight.bold),
                  ),
                ),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: _colorOptions.map((color) {
                    final isSelected = color.value == selectedColor.value;
                    return GestureDetector(
                      onTap: () {
                        setState(() {
                          selectedColor = color;
                        });
                      },
                      child: Container(
                        width: 32,
                        height: 32,
                        decoration: BoxDecoration(
                          color: color,
                          shape: BoxShape.circle,
                          border: isSelected
                              ? Border.all(color: Colors.black, width: 2)
                              : null,
                        ),
                        child: isSelected
                            ? const Icon(
                                Icons.check,
                                size: 20,
                                color: Colors.white,
                              )
                            : null,
                      ),
                    );
                  }).toList(),
                ),
              ],
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('取消'),
              ),
              FilledButton(
                onPressed: () async {
                  final name = nameController.text.trim();
                  if (name.isEmpty) return;

                  if (isEdit) {
                    await DatabaseHelper().updateStatus(
                      existingStatus['id'],
                      name,
                      selectedColor.value,
                    );
                  } else {
                    await DatabaseHelper().insertStatus(
                      name,
                      selectedColor.value,
                    );
                  }
                  if (mounted) Navigator.pop(context);
                  _loadStatuses();
                },
                child: const Text('保存'),
              ),
            ],
          );
        },
      ),
    );
  }

  Future<void> _deleteStatus(int id, String name) async {
    final result = await DatabaseHelper().deleteStatus(id);
    if (!mounted) return;

    if (result == -2) {
      showDialog(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('无法删除'),
          content: Text('状态 "$name" 正在被某些番剧使用中，无法直接删除。\n\n请修改这些番剧的状态后再试。'),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('知道了'),
            ),
          ],
        ),
      );
    } else {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('删除成功')));
      _loadStatuses();
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('自定义状态管理')),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : _statuses.isEmpty
          ? const Center(child: Text('暂无状态'))
          : ReorderableListView(
              onReorder: (oldIndex, newIndex) async {
                setState(() {
                  if (oldIndex < newIndex) {
                    newIndex -= 1;
                  }
                  final item = _statuses.removeAt(oldIndex);
                  _statuses.insert(newIndex, item);
                });
                // 同步到数据库
                final ids = _statuses.map((e) => e['id'] as int).toList();
                await DatabaseHelper().updateStatusesOrder(ids);
              },
              children: List.generate(_statuses.length, (index) {
                final status = _statuses[index];
                final color = Color(status['color'] ?? 0xFF000000);
                return ListTile(
                  key: ValueKey(status['id']),
                  leading: CircleAvatar(backgroundColor: color, radius: 12),
                  title: Text(status['name']),
                  trailing: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      IconButton(
                        icon: const Icon(Icons.edit, color: Colors.blue),
                        onPressed: () =>
                            _addOrUpdateStatus(existingStatus: status),
                      ),
                      IconButton(
                        icon: const Icon(Icons.delete, color: Colors.red),
                        onPressed: () =>
                            _deleteStatus(status['id'], status['name']),
                      ),
                      const Icon(Icons.drag_handle, color: Colors.grey),
                    ],
                  ),
                );
              }),
            ),
      floatingActionButton: FloatingActionButton(
        onPressed: () => _addOrUpdateStatus(),
        child: const Icon(Icons.add),
      ),
    );
  }
}
