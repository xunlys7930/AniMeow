import 'dart:io';

import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';

import 'package:anime_tracker/ui/pages/add_anime_page.dart';
import '../../db/database_helper.dart';
import '../../settings_manager.dart';
import '../series_detail_page.dart';
import 'detail_layout.dart';
import 'detail_props.dart';
import 'layouts/classic_layout.dart';
import 'layouts/dashboard_layout.dart';
import 'layouts/magazine_layout.dart';
import 'layouts/minimal_layout.dart';

/// 番剧只读详情查看页（替代旧的"直接进入 AddAnimePage 表单"流程）
///
/// 进入时从 DB 拉最新数据，根据 [SettingsManager.detailLayoutNotifier]
/// 选择布局，根据 [SettingsManager.detailModuleOrderNotifier] 和
/// [SettingsManager.detailHiddenModulesNotifier] 决定模块顺序和显隐。
///
/// 点编辑按钮跳转 [AddAnimePage]，从那里返回后自动刷新；返回上一页时
/// 透传 `true` 让列表也刷新。
class AnimeDetailPage extends StatefulWidget {
  final Map<String, dynamic> existingAnime;

  const AnimeDetailPage({super.key, required this.existingAnime});

  @override
  State<AnimeDetailPage> createState() => _AnimeDetailPageState();
}

class _AnimeDetailPageState extends State<AnimeDetailPage> {
  Map<String, dynamic>? _anime;
  List<Map<String, dynamic>> _tags = [];
  List<Map<String, dynamic>> _siblings = [];
  List<Map<String, dynamic>> _characters = [];
  String? _seriesName;
  Map<String, Color> _statusColors = {};
  Directory? _appDocDir;
  bool _isLoading = true;
  // 是否有过修改（用于退出时透传给上层列表）
  bool _hasChanges = false;

  @override
  void initState() {
    super.initState();
    _initAppDir();
    _loadAll();
  }

  Future<void> _initAppDir() async {
    final dir = await getApplicationDocumentsDirectory();
    if (mounted) setState(() => _appDocDir = dir);
  }

  Future<void> _loadAll() async {
    final id = widget.existingAnime['id'];
    final db = DatabaseHelper();
    Map<String, dynamic> anime = widget.existingAnime;
    if (id is int) {
      final latest = await db.getAnimeById(id);
      if (latest != null) anime = latest;
    }

    // 标签和角色
    List<Map<String, dynamic>> tags = const [];
    List<Map<String, dynamic>> characters = const [];
    if (anime['id'] is int) {
      final animeId = anime['id'] as int;
      tags = await db.getTagsByAnimeId(animeId);
      characters = await db.getCharactersByAnimeId(animeId);
    }

    // 状态色
    final statusList = await db.getAllStatuses();
    final colors = <String, Color>{};
    for (final s in statusList) {
      final name = s['name'];
      final colorValue = s['color'];
      if (name is String && colorValue is int) {
        colors[name] = Color(colorValue);
      }
    }

    // 系列与兄弟
    String? seriesName;
    List<Map<String, dynamic>> siblings = const [];
    final seriesId = anime['series_id'];
    if (seriesId is int) {
      final series = await db.getSeriesById(seriesId);
      seriesName = series?['name'] as String?;
      final allInSeries = await db.getAnimesInSeries(seriesId);
      siblings = allInSeries
          .where((e) => e['id'] != anime['id'])
          .toList(growable: false);
    }

    if (!mounted) return;
    setState(() {
      _anime = anime;
      _tags = tags;
      _siblings = siblings;
      _characters = characters;
      _seriesName = seriesName;
      _statusColors = colors;
      _isLoading = false;
    });
  }

  Future<void> _openEditor() async {
    final anime = _anime;
    if (anime == null) return;
    final result = await Navigator.of(context).push<bool>(
      MaterialPageRoute(builder: (_) => AddAnimePage(existingAnime: anime)),
    );
    if (result == true) {
      _hasChanges = true;
      // 刷新本页数据
      setState(() => _isLoading = true);
      _loadAll();
    }
  }

  void _openSeriesPage() {
    final anime = _anime;
    if (anime == null) return;
    final seriesId = anime['series_id'];
    if (seriesId is! int) return;
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => SeriesDetailPage(
          series: {'id': seriesId, 'name': _seriesName ?? ''},
          statusColors: _statusColors,
        ),
      ),
    );
  }

  void _openSibling(Map<String, dynamic> sibling) {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => AnimeDetailPage(existingAnime: sibling),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (didPop) return;
        Navigator.of(context).pop(_hasChanges);
      },
      child: ValueListenableBuilder<DetailLayout>(
        valueListenable: SettingsManager().detailLayoutNotifier,
        builder: (context, layout, _) {
          return ValueListenableBuilder<List<DetailModule>>(
            valueListenable: SettingsManager().detailModuleOrderNotifier,
            builder: (context, order, _) {
              return ValueListenableBuilder<Set<DetailModule>>(
                valueListenable: SettingsManager().detailHiddenModulesNotifier,
                builder: (context, hidden, _) {
                  return ValueListenableBuilder<double>(
                    valueListenable:
                        SettingsManager().coverBorderRadiusNotifier,
                    builder: (context, coverBorderRadius, _) {
                      final loading = _isLoading || _anime == null;
                      // Magazine 自带 SliverAppBar，需要 body 顶到状态栏下
                      final isMagazine = layout == DetailLayout.magazine;
                      return Scaffold(
                        extendBodyBehindAppBar: isMagazine,
                        appBar: loading || isMagazine
                            ? null
                            : _buildAppBar(layout),
                        body: loading
                            ? const Center(child: CircularProgressIndicator())
                            : _buildContent(
                                layout,
                                order,
                                hidden,
                                isMagazine,
                                coverBorderRadius,
                              ),
                      );
                    },
                  );
                },
              );
            },
          );
        },
      ),
    );
  }

  PreferredSizeWidget _buildAppBar(DetailLayout layout) {
    final isAnime = (_anime?['subject_type'] ?? 'anime') == 'anime';
    return AppBar(
      leading: IconButton(
        icon: const Icon(Icons.arrow_back_ios_new, size: 20),
        tooltip: '返回',
        onPressed: () => Navigator.of(context).pop(_hasChanges),
      ),
      title: Row(
        children: [
          Icon(
            isAnime ? Icons.movie_filter_outlined : Icons.menu_book_rounded,
            size: 18,
            color: isAnime ? Colors.blue : Colors.teal,
          ),
          const SizedBox(width: 6),
          Flexible(
            child: Text(
              (_anime?['name_cn'] ?? _anime?['title'] ?? _anime?['name'] ?? '')
                  .toString(),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w800),
            ),
          ),
        ],
      ),
      actions: [
        IconButton(
          icon: const Icon(Icons.edit_outlined),
          tooltip: '编辑',
          onPressed: _openEditor,
        ),
      ],
    );
  }

  Widget _buildContent(
    DetailLayout layout,
    List<DetailModule> order,
    Set<DetailModule> hidden,
    bool isMagazine,
    double coverBorderRadius,
  ) {
    final visible = order
        .where((m) => !hidden.contains(m))
        .toList(growable: false);
    final props = DetailViewProps(
      anime: _anime!,
      tags: _tags,
      siblings: _siblings,
      characters: _characters,
      seriesName: _seriesName,
      statusColors: _statusColors,
      appDocDir: _appDocDir,
      coverBorderRadius: coverBorderRadius,
      layout: layout,
      orderedVisibleModules: visible,
      onEditTap: _openEditor,
      onSeriesTap: _seriesName == null ? null : _openSeriesPage,
      onSiblingTap: _openSibling,
      onBackTap: () => Navigator.of(context).pop(_hasChanges),
      onCharactersChanged: () {
        _hasChanges = true;
        _loadAll();
      },
    );
    // Magazine 不套 SafeArea（其 SliverAppBar 自己处理状态栏）
    if (isMagazine) {
      return _buildLayout(layout, props);
    }
    return SafeArea(bottom: false, child: _buildLayout(layout, props));
  }

  Widget _buildLayout(DetailLayout layout, DetailViewProps props) {
    switch (layout) {
      case DetailLayout.classic:
        return ClassicLayout(props: props);
      case DetailLayout.magazine:
        return MagazineLayout(props: props);
      case DetailLayout.dashboard:
        return DashboardLayout(props: props);
      case DetailLayout.minimal:
        return MinimalLayout(props: props);
    }
  }
}
