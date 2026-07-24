import 'package:flutter/material.dart';
import '../repositories/series_repository.dart';
import '../services/service_locator.dart';
import 'neumorphic_style.dart';

/// 系列选择对话框
class SeriesSelectionDialog extends StatefulWidget {
  final int? initialSeriesId;

  const SeriesSelectionDialog({super.key, this.initialSeriesId});

  @override
  State<SeriesSelectionDialog> createState() => _SeriesSelectionDialogState();
}

class _SeriesSelectionDialogState extends State<SeriesSelectionDialog> {
  List<Map<String, dynamic>> _allSeries = [];
  bool _isLoading = true;
  int? _selectedId;
  final SeriesRepository _seriesRepo = getIt<SeriesRepository>();

  @override
  void initState() {
    super.initState();
    _selectedId = widget.initialSeriesId;
    _loadSeries();
  }

  Future<void> _loadSeries() async {
    final list = await _seriesRepo.getAllSeriesMaps();
    if (mounted) {
      setState(() {
        _allSeries = list;
        _isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          const Text('选择系列'),
          IconButton(
            icon: const Icon(Icons.add_circle_outline, color: Colors.blue),
            tooltip: "新建系列",
            onPressed: _showCreateSeriesDialog,
          ),
        ],
      ),
      content: NeumorphicContainer(
        borderRadius: 32,
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: SizedBox(
          width: double.maxFinite,
          height: 300,
          child: _isLoading
              ? const Center(child: CircularProgressIndicator())
              : _allSeries.isEmpty
              ? const Center(child: Text('暂无系列，点击右上角新建'))
              : ListView.builder(
                  shrinkWrap: true,
                  itemCount: _allSeries.length + 1,
                  itemBuilder: (context, index) {
                    if (index == 0) {
                      // "无" 选项
                      return ListTile(
                        title: const Text(
                          '无 (从系列中移除)',
                          style: TextStyle(color: Colors.redAccent),
                        ),
                        leading: Radio<int>(
                          value: -1,
                          groupValue: _selectedId ?? -1,
                          onChanged: (v) => setState(() => _selectedId = v),
                        ),
                        onTap: () => setState(() => _selectedId = -1),
                      );
                    }
                    final series = _allSeries[index - 1];
                    final id = series['id'] as int;
                    return ListTile(
                      title: Text(series['name']),
                      leading: Radio<int>(
                        value: id,
                        groupValue: _selectedId,
                        onChanged: (v) => setState(() => _selectedId = v),
                      ),
                      onTap: () => setState(() => _selectedId = id),
                    );
                  },
                ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('取消'),
        ),
        FilledButton(
          onPressed: () => Navigator.pop(context, _selectedId),
          child: const Text('确定'),
        ),
      ],
    );
  }

  void _showCreateSeriesDialog() {
    final textController = TextEditingController();
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(32)),
        title: const Text('新建系列'),
        content: TextField(
          controller: textController,
          decoration: InputDecoration(
            hintText: "系列名称",
            filled: true,
            fillColor: Colors.grey[100],
            border: OutlineInputBorder(
              borderRadius: BorderRadius.circular(48),
              borderSide: BorderSide.none,
            ),
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
              final name = textController.text.trim();
              if (name.isEmpty) return;

              int newId = await _seriesRepo.createSeries(name);
              if (newId != -1) {
                await _loadSeries();
                setState(() => _selectedId = newId);
                if (mounted) Navigator.pop(context);
              }
            },
            child: const Text('创建'),
          ),
        ],
      ),
    );
  }
}
