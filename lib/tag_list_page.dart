import 'package:flutter/material.dart';
import 'package:lpinyin/lpinyin.dart';
import 'db/database_helper.dart';
import 'filtered_anime_list_page.dart';

/// 标签列表页面（按拼音首字母分组 + 侧边索引导航）
class TagListPage extends StatefulWidget {
  const TagListPage({super.key});

  @override
  State<TagListPage> createState() => _TagListPageState();
}

class _TagListPageState extends State<TagListPage> {
  List<Map<String, dynamic>> _tagCounts = [];
  bool _isLoading = true;

  final ScrollController _scrollController = ScrollController();

  @override
  void initState() {
    super.initState();
    _loadData();
  }

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  Future<void> _loadData() async {
    final tagData = await DatabaseHelper().getTagCounts();
    if (mounted) {
      setState(() {
        _tagCounts = tagData;
        _isLoading = false;
      });
    }
  }

  /// 获取汉字首字母
  String _getFirstLetter(String text) {
    if (text.isEmpty) return '#';
    String firstChar = text[0].toUpperCase();
    if (RegExp(r'[A-Z]').hasMatch(firstChar)) return firstChar;

    try {
      String pinyin = PinyinHelper.getFirstWordPinyin(text);
      if (pinyin.isNotEmpty) {
        String letter = pinyin[0].toUpperCase();
        if (RegExp(r'[A-Z]').hasMatch(letter)) return letter;
      }
    } catch (e) {
      debugPrint("Pinyin conversion error: $e");
    }
    return '#';
  }

  @override
  Widget build(BuildContext context) {
    if (_isLoading) {
      return Scaffold(
        appBar: AppBar(title: const Text('全部标签')),
        body: const Center(child: CircularProgressIndicator()),
      );
    }

    // 按首字母分组
    final Map<String, List<Map<String, dynamic>>> groupedTags = {};
    for (var tag in _tagCounts) {
      final letter = _getFirstLetter(tag['name'] as String);
      groupedTags.putIfAbsent(letter, () => []).add(tag);
    }

    final letters = groupedTags.keys.toList()
      ..sort((a, b) {
        if (a == '#') return 1;
        if (b == '#') return -1;
        return a.compareTo(b);
      });

    return Scaffold(
      appBar: AppBar(title: Text('全部标签 (${_tagCounts.length})')),
      body: _tagCounts.isEmpty
          ? const Center(
              child: Text("暂无标签数据", style: TextStyle(color: Colors.grey)),
            )
          : ListView.builder(
              controller: _scrollController,
              padding: const EdgeInsets.all(16),
              itemCount: letters.length,
              itemBuilder: (context, index) {
                final letter = letters[index];
                final tags = groupedTags[letter]!;

                return Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Padding(
                      padding: const EdgeInsets.symmetric(
                        vertical: 12,
                        horizontal: 4,
                      ),
                      child: Text(
                        letter,
                        style: const TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.bold,
                          color: Colors.black87,
                        ),
                      ),
                    ),
                    Card(
                      elevation: 0,
                      color: Colors.white,
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(16),
                      ),
                      child: Column(
                        children: tags
                            .map((tag) => _buildTagItem(tag))
                            .toList(),
                      ),
                    ),
                  ],
                );
              },
            ),
    );
  }

  Widget _buildTagItem(Map<String, dynamic> tag) {
    return InkWell(
      onTap: () {
        Navigator.push(
          context,
          MaterialPageRoute(
            builder: (context) => FilteredAnimeListPage(
              tagId: tag['id'] as int,
              tagName: tag['name'] as String,
            ),
          ),
        );
      },
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
        child: Row(
          children: [
            const Text(
              "# ",
              style: TextStyle(
                color: Colors.black26,
                fontSize: 18,
                fontWeight: FontWeight.bold,
              ),
            ),
            Expanded(
              child: Text(
                tag['name'] as String,
                style: const TextStyle(fontSize: 16, color: Colors.black87),
              ),
            ),
            Text(
              "${tag['count']} ",
              style: const TextStyle(color: Colors.black26, fontSize: 14),
            ),
            const Icon(Icons.chevron_right, color: Colors.black12, size: 18),
          ],
        ),
      ),
    );
  }
}
