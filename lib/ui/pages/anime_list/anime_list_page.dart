import 'package:provider/provider.dart';
import 'dart:io';
import 'dart:async';
import 'package:path_provider/path_provider.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:anime_tracker/api/bangumi_service.dart';
import 'package:anime_tracker/api/anilist_service.dart';
import 'package:anime_tracker/repositories/anime_repository.dart';
import 'package:anime_tracker/repositories/tag_repository.dart';
import 'package:anime_tracker/services/service_locator.dart';
import 'package:anime_tracker/utils/logger.dart';
import 'package:anime_tracker/utils/operation_log_service.dart';
import 'package:anime_tracker/settings_manager.dart';
import '../add_anime_page.dart';
import '../../anime_detail/anime_detail_page.dart';
import '../../series_detail_page.dart';
import '../../components/empty_state.dart';
import '../../design_tokens.dart';
import '../../views/home_layout.dart';
import 'anime_list_provider.dart';
import 'components/sort_menu_sheet.dart';
import 'components/filter_panel.dart';

/// 追番列表页
class AnimeListPage extends StatelessWidget {
  final VoidCallback onSettingsChanged;
  const AnimeListPage({super.key, required this.onSettingsChanged});

  @override
  Widget build(BuildContext context) {
    return ChangeNotifierProvider<AnimeListProvider>(
      create: (_) => AnimeListProvider()..init(),
      child: _AnimeListPageView(onSettingsChanged: onSettingsChanged),
    );
  }
}

class _AnimeListPageView extends StatefulWidget {
  final VoidCallback onSettingsChanged;
  const _AnimeListPageView({required this.onSettingsChanged});

  @override
  State<_AnimeListPageView> createState() => _AnimeListPageViewState();
}

class _AnimeListPageViewState extends State<_AnimeListPageView> {
  Directory? _appDocDir;

  // --- UI 控制器 ---
  final ScrollController _gridScrollController = ScrollController();
  final ScrollController _cardScrollController = ScrollController();
  final TextEditingController _searchController = TextEditingController();
  final FocusNode _searchFocusNode = FocusNode();

  @override
  void initState() {
    super.initState();
    final provider = context.read<AnimeListProvider>();
    _initAppDir();

    _searchController.addListener(() {
      provider.setSearchQuery(_searchController.text);
    });

    _gridScrollController.addListener(_onGridScroll);
    _cardScrollController.addListener(_onGridScroll);
  }

  Future<void> _initAppDir() async {
    if (kIsWeb) return;
    try {
      final dir = await getApplicationDocumentsDirectory();
      if (mounted) {
        setState(() => _appDocDir = dir);
      }
    } catch (_) {
      // 本地封面目录不可用时仍可正常显示网络封面与其余界面。
    }
  }

  @override
  void dispose() {
    _searchController.dispose();
    _searchFocusNode.dispose();
    _gridScrollController.dispose();
    _cardScrollController.dispose();
    super.dispose();
  }

  void _onGridScroll() {
    final provider = context.read<AnimeListProvider>();
    ScrollController? active;
    if (_gridScrollController.hasClients) {
      active = _gridScrollController;
    } else if (_cardScrollController.hasClients) {
      active = _cardScrollController;
    }
    if (active == null) return;
    if (active.position.pixels >= active.position.maxScrollExtent - 800) {
      provider.loadMoreItems();
    }
  }

  void _handleItemTap(Map<String, dynamic> item) {
    final provider = context.read<AnimeListProvider>();
    _searchFocusNode.unfocus();
    if (provider.isSelectionMode) {
      provider.toggleItemSelection(item['id']);
      return;
    }

    if (item['type'] == 'series') {
      OperationLogService.instance.record(
        '打开系列详情',
        screen: '首页',
        details: {'id': item['id']},
      );
      Navigator.push(
        context,
        MaterialPageRoute(
          builder: (context) => SeriesDetailPage(
            series: item,
            statusColors: provider.statusColors,
          ),
        ),
      ).then((_) => provider.refreshData());
    } else {
      OperationLogService.instance.record(
        '打开作品详情',
        screen: '首页',
        details: {'id': item['id']},
      );
      Navigator.push(
        context,
        MaterialPageRoute(
          builder: (context) => AnimeDetailPage(existingAnime: item),
        ),
      ).then((v) {
        if (v == true) provider.refreshData();
      });
    }
  }

  void _handleItemLongPress(int id) {
    final provider = context.read<AnimeListProvider>();
    if (!provider.isSelectionMode) {
      provider.toggleSelectionMode(true);
      provider.toggleItemSelection(id);
    }
  }

  void _batchSyncCoversToServer() async {
    final provider = context.read<AnimeListProvider>();
    if (provider.selectedAnimeIds.isEmpty) return;

    final bool? confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Row(
          children: [
            Icon(Icons.cloud_upload_outlined, color: Colors.teal),
            SizedBox(width: 8),
            Text('同步封面至资料库'),
          ],
        ),
        content: const Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('该功能会将您选中的番剧封面 URL 匿名同步至追番喵公共资料库。'),
            SizedBox(height: 12),
            Text('这有助于完善资料库缺失的图片信息，让其他用户在搜索时也能看到精美的封面。'),
            SizedBox(height: 12),
            Text(
              '感谢您的无私分享与对资料库建设的支持。',
              style: TextStyle(fontWeight: FontWeight.bold, color: Colors.teal),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            style: FilledButton.styleFrom(backgroundColor: Colors.teal),
            child: const Text('立即同步'),
          ),
        ],
      ),
    );

    if (confirmed != true || !mounted) return;

    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text("已开启后台同步，共计 ${provider.selectedAnimeIds.length} 个项目..."),
        duration: const Duration(seconds: 2),
      ),
    );

    final results = await provider.batchSyncCovers();
    final syncCount = results['success'] ?? 0;
    final skipCount = results['skip'] ?? 0;

    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text("后台封面同步完成：成功 $syncCount 个，跳过 $skipCount 个自定义封面。"),
          behavior: SnackBarBehavior.floating,
          backgroundColor: Colors.teal,
          duration: const Duration(seconds: 4),
        ),
      );
    }
  }

  void _batchDelete() {
    final provider = context.read<AnimeListProvider>();
    if (provider.selectedAnimeIds.isEmpty) return;
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('确认批量删除'),
        content: Text(
          "确定要删除选中的 ${provider.selectedAnimeIds.length} 部番剧吗？\n此操作不可恢复。",
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('取消'),
          ),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: Colors.red),
            onPressed: () async {
              Navigator.pop(ctx);
              await provider.batchDeleteSelected();
              if (mounted) {
                ScaffoldMessenger.of(
                  context,
                ).showSnackBar(const SnackBar(content: Text('删除成功')));
              }
            },
            child: Text('删除(${provider.selectedAnimeIds.length})'),
          ),
        ],
      ),
    );
  }

  void _showDuplicateChecker() async {
    final provider = context.read<AnimeListProvider>();
    final groups = await provider.findDuplicateGroups();
    if (!mounted) return;

    if (groups.isEmpty) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('没有发现同类型同名的重复条目')));
      return;
    }

    final deletedCount = await showDialog<int>(
      context: context,
      builder: (dialogCtx) => _DuplicateCleanupDialog(
        groups: groups,
        onDelete: provider.deleteDuplicateItems,
      ),
    );

    if (!mounted || deletedCount == null || deletedCount <= 0) return;
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text('已删除 $deletedCount 个重复条目')));
  }

  void _navigateToAddPage() async {
    final provider = context.read<AnimeListProvider>();
    OperationLogService.instance.record('打开添加作品页', screen: '首页');
    final result = await Navigator.push(
      context,
      MaterialPageRoute(builder: (context) => const AddAnimePage()),
    );
    if (result == true) {
      OperationLogService.instance.record('完成添加作品', screen: '首页');
      provider.refreshData();
    }
  }

  Future<void> _showSortMenu() {
    OperationLogService.instance.record('打开排序面板', screen: '首页');
    return _showAdaptivePanel(
      initialChildSize: 0.58,
      minChildSize: 0.42,
      builder: (controller) => SortMenuSheet(scrollController: controller),
    );
  }

  Future<void> _showFilterPanel() {
    OperationLogService.instance.record('打开筛选面板', screen: '首页');
    return _showAdaptivePanel(
      initialChildSize: 0.88,
      minChildSize: 0.58,
      builder: (controller) => FilterPanel(scrollController: controller),
    );
  }

  Future<void> _showAdaptivePanel({
    required Widget Function(ScrollController controller) builder,
    required double initialChildSize,
    required double minChildSize,
  }) async {
    final provider = context.read<AnimeListProvider>();
    final useDialog = AppBreakpoints.isMedium(MediaQuery.sizeOf(context).width);

    if (useDialog) {
      final controller = ScrollController();
      await showDialog<void>(
        context: context,
        builder: (dialogContext) {
          final height = (MediaQuery.sizeOf(dialogContext).height * 0.82)
              .clamp(480.0, 760.0)
              .toDouble();
          return ChangeNotifierProvider.value(
            value: provider,
            child: Dialog(
              clipBehavior: Clip.antiAlias,
              insetPadding: const EdgeInsets.all(AppSpacing.xl),
              child: SizedBox(
                width: AppSize.dialogMaxWidth,
                height: height,
                child: builder(controller),
              ),
            ),
          );
        },
      );
      controller.dispose();
      return;
    }

    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      showDragHandle: true,
      clipBehavior: Clip.antiAlias,
      builder: (modalContext) => ChangeNotifierProvider.value(
        value: provider,
        child: DraggableScrollableSheet(
          initialChildSize: initialChildSize,
          minChildSize: minChildSize,
          maxChildSize: 0.96,
          expand: false,
          builder: (_, controller) => builder(controller),
        ),
      ),
    );
  }

  String _getProgressText(Map<String, dynamic> anime) {
    final int watched = anime['watched_episodes'] ?? 0;
    final int total = anime['total_episodes'] ?? 0;
    final String type = anime['subject_type'] ?? 'anime';

    if (type == 'book') {
      if (watched == 0 && total == 0) return '未读';
      if (total == 0) return '已读 $watched 话';
      if (watched >= total) return '已完结';
      return '已读 $watched/$total 话';
    } else {
      if (watched == 0 && total == 0) return '未看';
      if (total == 0) return '已看 $watched 集';
      if (watched >= total) return '已看完';
      return '已看 $watched/$total 集';
    }
  }

  // 批量修改状态
  void _showBatchStatusDialog() {
    final provider = context.read<AnimeListProvider>();
    showDialog(
      context: context,
      builder: (dialogCtx) => AlertDialog(
        title: const Text('批量修改状态'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: provider.statusOptions.skip(1).map((status) {
            return ListTile(
              title: Text(status),
              leading: const Icon(Icons.radio_button_unchecked),
              onTap: () async {
                await provider.batchUpdateStatus(status);
                if (!mounted || !dialogCtx.mounted) return;
                Navigator.pop(dialogCtx);
                ScaffoldMessenger.of(
                  context,
                ).showSnackBar(const SnackBar(content: Text('批量修改状态成功')));
              },
            );
          }).toList(),
        ),
      ),
    );
  }

  // 批量标签菜单
  void _showBatchTagMenu() {
    showModalBottomSheet(
      context: context,
      builder: (context) => Container(
        padding: const EdgeInsets.symmetric(vertical: 20),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text(
              "批量标签操作",
              style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
            ),
            const Divider(),
            ListTile(
              leading: const Icon(Icons.add, color: Colors.blue),
              title: const Text('批量添加标签'),
              subtitle: const Text('给选中的番剧统一加上某个标签'),
              onTap: () {
                Navigator.pop(context);
                _showBatchTagSelector(isAdd: true);
              },
            ),
            ListTile(
              leading: const Icon(Icons.remove, color: Colors.red),
              title: const Text('批量移除标签'),
              subtitle: const Text('从选中的番剧中统一移除某个标签'),
              onTap: () {
                Navigator.pop(context);
                _showBatchTagSelector(isAdd: false);
              },
            ),
          ],
        ),
      ),
    );
  }

  // 选择具体要操作的标签 (支持新建)
  void _showBatchTagSelector({required bool isAdd}) {
    final provider = context.read<AnimeListProvider>();
    showDialog(
      context: context,
      builder: (dialogCtx) => AlertDialog(
        title: Text(isAdd ? '选择要添加的标签' : '选择要移除的标签'),
        content: SizedBox(
          width: double.maxFinite,
          height: 300,
          child: provider.allTags.isEmpty
              ? const Center(child: Text("暂无标签，请新建"))
              : ListView.builder(
                  shrinkWrap: true,
                  itemCount: provider.allTags.length,
                  itemBuilder: (context, index) {
                    final tag = provider.allTags[index];
                    return ListTile(
                      leading: const Icon(Icons.label, color: Colors.indigo),
                      title: Text(tag['name']),
                      onTap: () async {
                        Navigator.pop(dialogCtx);
                        if (isAdd) {
                          await provider.batchAddTag(tag['id']);
                        } else {
                          await provider.batchRemoveTag(tag['id']);
                        }
                        if (!context.mounted) return;
                        ScaffoldMessenger.of(context).showSnackBar(
                          SnackBar(
                            content: Text(isAdd ? '批量添加标签成功' : '批量移除标签成功'),
                          ),
                        );
                      },
                    );
                  },
                ),
        ),
        actions: [
          if (isAdd)
            TextButton.icon(
              icon: const Icon(Icons.add),
              label: const Text("新建并添加"),
              onPressed: () {
                Navigator.pop(dialogCtx);
                _showBatchCreateTagDialog();
              },
            ),
          TextButton(
            onPressed: () => Navigator.pop(dialogCtx),
            child: const Text('取消'),
          ),
        ],
      ),
    );
  }

  void _showBatchCreateTagDialog() {
    final provider = context.read<AnimeListProvider>();
    final textController = TextEditingController();
    showDialog(
      context: context,
      builder: (dialogCtx) => AlertDialog(
        title: const Text('新建标签并批量应用'),
        content: TextField(
          controller: textController,
          decoration: const InputDecoration(
            labelText: "标签名称",
            hintText: "例如：神作",
            border: OutlineInputBorder(),
          ),
          autofocus: true,
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogCtx),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () async {
              if (textController.text.isEmpty) return;
              final selectedCount = provider.selectedAnimeIds.length;

              int newTagId = await getIt<TagRepository>().insertTag(
                textController.text,
              );

              if (newTagId == -1) {
                if (!mounted || !dialogCtx.mounted) return;
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(
                    content: Text('标签已存在！请直接在列表中选择它。'),
                    backgroundColor: Colors.orange,
                  ),
                );
                Navigator.pop(dialogCtx);
                _showBatchTagSelector(isAdd: true);
              } else {
                await provider.batchAddTag(newTagId);
                if (!mounted || !dialogCtx.mounted) return;
                Navigator.pop(dialogCtx);
                ScaffoldMessenger.of(context).showSnackBar(
                  SnackBar(
                    content: Text(
                      '已新建标签 "${textController.text}" 并添加到 $selectedCount 部番剧',
                    ),
                  ),
                );
              }
            },
            child: const Text('新建并添加'),
          ),
        ],
      ),
    );
  }

  // 批量自动匹配信息
  void _batchAutoMatchInfo() async {
    final provider = context.read<AnimeListProvider>();
    final Map<String, bool>? selections = await showDialog<Map<String, bool>>(
      context: context,
      builder: (ctx) {
        Map<String, bool> localSelections = {
          'cover': true,
          'rating': true,
          'episodes': true,
          'studio': true,
          'summary': true,
          'airDate': true,
        };
        return StatefulBuilder(
          builder: (ctx, setDialogState) {
            return AlertDialog(
              title: const Text('批量同步选项'),
              content: SingleChildScrollView(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      '将对选中的 ${provider.selectedAnimeIds.length} 部番剧进行联网匹配。',
                      style: const TextStyle(fontSize: 13, color: Colors.grey),
                    ),
                    const SizedBox(height: 12),
                    CheckboxListTile(
                      title: const Text('封面'),
                      value: localSelections['cover'],
                      onChanged: (v) =>
                          setDialogState(() => localSelections['cover'] = v!),
                    ),
                    CheckboxListTile(
                      title: const Text('评分'),
                      value: localSelections['rating'],
                      onChanged: (v) =>
                          setDialogState(() => localSelections['rating'] = v!),
                    ),
                    CheckboxListTile(
                      title: const Text('集数配置'),
                      value: localSelections['episodes'],
                      onChanged: (v) => setDialogState(
                        () => localSelections['episodes'] = v!,
                      ),
                    ),
                    CheckboxListTile(
                      title: const Text('制作公司'),
                      value: localSelections['studio'],
                      onChanged: (v) =>
                          setDialogState(() => localSelections['studio'] = v!),
                    ),
                    CheckboxListTile(
                      title: const Text('简介'),
                      value: localSelections['summary'],
                      onChanged: (v) =>
                          setDialogState(() => localSelections['summary'] = v!),
                    ),
                    CheckboxListTile(
                      title: const Text('放送日期'),
                      value: localSelections['airDate'],
                      onChanged: (v) =>
                          setDialogState(() => localSelections['airDate'] = v!),
                    ),
                  ],
                ),
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.pop(ctx),
                  child: const Text('取消'),
                ),
                TextButton(
                  onPressed: () => Navigator.pop(ctx, localSelections),
                  child: const Text('开始匹配选中项'),
                ),
                FilledButton(
                  onPressed: () {
                    Navigator.pop(ctx, {
                      'cover': true,
                      'rating': true,
                      'episodes': true,
                      'studio': true,
                      'summary': true,
                      'airDate': true,
                    });
                  },
                  child: const Text('全部同步'),
                ),
              ],
            );
          },
        );
      },
    );

    if (selections == null || !mounted) return;

    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (dialogCtx) {
        return StatefulBuilder(
          builder: (statefulCtx, setState) {
            return _AutoMatchProgressDialog(
              selectedIds: provider.selectedAnimeIds.toList(),
              animes: provider.animes,
              enabledFields: selections,
              onCompleted: () {
                Navigator.pop(dialogCtx);
                provider.toggleSelectionMode(false);
                provider.refreshData();
                if (!mounted) return;
                ScaffoldMessenger.of(
                  context,
                ).showSnackBar(const SnackBar(content: Text('批量匹配完成')));
              },
            );
          },
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final provider = context.watch<AnimeListProvider>();
    return CallbackShortcuts(
      bindings: {
        const SingleActivator(LogicalKeyboardKey.keyK, control: true): () {
          _searchFocusNode.requestFocus();
        },
      },
      child: PopScope(
        canPop: !provider.isSelectionMode,
        onPopInvokedWithResult: (didPop, result) {
          if (didPop) return;
          if (provider.isSelectionMode) {
            provider.toggleSelectionMode(false);
          }
        },
        child: Scaffold(
          appBar: _buildAppBar(provider),
          body: Column(
            children: [
              _buildUnifiedFilterSection(provider),
              AnimatedSize(
                duration: AppMotion.resolve(context, AppMotion.short),
                curve: AppMotion.emphasized,
                child: provider.isRefreshing
                    ? const LinearProgressIndicator(minHeight: 2)
                    : const SizedBox(height: 0),
              ),
              Expanded(child: _buildPageBody(provider)),
            ],
          ),
          bottomNavigationBar: provider.isSelectionMode
              ? _buildSelectionActionBar(provider)
              : null,
          floatingActionButton: provider.isSelectionMode
              ? null
              : FloatingActionButton.extended(
                  heroTag: 'anime-list-add',
                  onPressed: _navigateToAddPage,
                  label: const Text('添加作品'),
                  icon: const Icon(Icons.add_rounded),
                ),
        ),
      ),
    );
  }

  Widget _buildPageBody(AnimeListProvider provider) {
    if (provider.isLoading) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const CircularProgressIndicator(),
            const SizedBox(height: AppSpacing.lg),
            Text(
              '正在整理你的资料库…',
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ),
          ],
        ),
      );
    }
    if (provider.loadError != null && provider.animes.isEmpty) {
      return EmptyStateWidget(
        icon: Icons.cloud_off_rounded,
        message: '资料库加载失败',
        description: '请检查数据文件后重试',
        buttonText: '重新加载',
        onButtonPressed: provider.refreshData,
      );
    }
    if (provider.animes.isEmpty) return _buildEmptyState(provider);
    return _buildContentBody(provider);
  }

  PreferredSizeWidget _buildAppBar(AnimeListProvider provider) {
    if (provider.isSelectionMode) {
      final selectableCount = provider.animes
          .where((item) => item['type'] != 'series')
          .length;
      final allSelected =
          selectableCount > 0 &&
          provider.selectedAnimeIds.length == selectableCount;
      return AppBar(
        leading: IconButton(
          tooltip: '退出多选',
          icon: const Icon(Icons.close_rounded),
          onPressed: () => provider.toggleSelectionMode(false),
        ),
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('已选 ${provider.selectedAnimeIds.length} 项'),
            Text(
              '继续点选作品，或使用下方批量操作',
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                color: Theme.of(context).colorScheme.onSecondaryContainer,
              ),
            ),
          ],
        ),
        backgroundColor: Theme.of(context).colorScheme.secondaryContainer,
        actions: [
          TextButton.icon(
            icon: Icon(
              allSelected ? Icons.deselect_rounded : Icons.select_all_rounded,
              size: 20,
            ),
            label: Text(allSelected ? '取消全选' : '全选'),
            onPressed: () {
              HapticFeedback.selectionClick();
              provider.toggleSelectAll();
            },
          ),
          const SizedBox(width: AppSpacing.sm),
        ],
      );
    }

    final compact = AppBreakpoints.isCompact(MediaQuery.sizeOf(context).width);
    final title = Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text('追番'),
        Text(
          provider.isLoading ? '正在载入资料库' : '${provider.totalItemCount} 部作品',
          style: Theme.of(context).textTheme.bodySmall?.copyWith(
            color: Theme.of(context).colorScheme.onSurfaceVariant,
          ),
        ),
      ],
    );

    return AppBar(
      toolbarHeight: compact ? 64 : 72,
      titleSpacing: AppSpacing.lg,
      title: compact
          ? title
          : Row(
              children: [
                title,
                const SizedBox(width: AppSpacing.xl),
                Expanded(
                  child: Align(
                    alignment: Alignment.centerRight,
                    child: ConstrainedBox(
                      constraints: const BoxConstraints(maxWidth: 420),
                      child: _buildSearchBar(provider),
                    ),
                  ),
                ),
              ],
            ),
      actions: [
        IconButton(
          icon: const Icon(Icons.content_copy_rounded),
          tooltip: '检查重复条目',
          onPressed: _showDuplicateChecker,
        ),
        ValueListenableBuilder<HomeLayout>(
          valueListenable: SettingsManager().homeLayoutNotifier,
          builder: (context, layout, _) {
            if (!layout.supportsGridColumns) {
              return const SizedBox.shrink();
            }
            return _buildGridColumnsMenu();
          },
        ),
        IconButton(
          icon: const Icon(Icons.sort_rounded),
          tooltip: '排序：${provider.selectedSortLabel}',
          onPressed: _showSortMenu,
        ),
        const SizedBox(width: AppSpacing.sm),
      ],
      bottom: compact
          ? PreferredSize(
              preferredSize: const Size.fromHeight(64),
              child: Padding(
                padding: const EdgeInsets.fromLTRB(
                  AppSpacing.lg,
                  0,
                  AppSpacing.lg,
                  AppSpacing.md,
                ),
                child: _buildSearchBar(provider),
              ),
            )
          : null,
    );
  }

  Widget _buildGridColumnsMenu() {
    final settings = SettingsManager();
    return ValueListenableBuilder<int>(
      valueListenable: settings.gridColumnsNotifier,
      builder: (context, columns, _) {
        return PopupMenuButton<int>(
          tooltip: '首页列数：$columns 列',
          icon: Badge(
            label: Text('$columns'),
            child: const Icon(Icons.view_column_rounded),
          ),
          onSelected: (value) {
            HapticFeedback.selectionClick();
            OperationLogService.instance.record(
              '调整首页宫格列数',
              screen: '首页',
              details: {'columns': value},
            );
            unawaited(settings.setGridColumns(value));
          },
          itemBuilder: (context) => [
            for (final count in const [2, 3, 4, 5])
              PopupMenuItem<int>(
                value: count,
                child: Row(
                  children: [
                    SizedBox(
                      width: 28,
                      child: count == columns
                          ? Icon(
                              Icons.check_rounded,
                              size: 18,
                              color: Theme.of(context).colorScheme.primary,
                            )
                          : null,
                    ),
                    Text('$count 列'),
                  ],
                ),
              ),
          ],
        );
      },
    );
  }

  Widget _buildSearchBar(AnimeListProvider provider) {
    return SearchBar(
      controller: _searchController,
      focusNode: _searchFocusNode,
      hintText: '搜索标题、系列或别名',
      leading: const Icon(Icons.search_rounded, size: 21),
      trailing: [
        if (provider.searchQuery.isNotEmpty)
          IconButton(
            tooltip: '清除搜索',
            icon: const Icon(Icons.close_rounded, size: 19),
            onPressed: () {
              _searchController.clear();
              _searchFocusNode.requestFocus();
            },
          )
        else if (!AppBreakpoints.isCompact(MediaQuery.sizeOf(context).width))
          Padding(
            padding: const EdgeInsets.only(right: AppSpacing.sm),
            child: Text(
              'Ctrl K',
              style: Theme.of(context).textTheme.labelSmall?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ),
          ),
      ],
    );
  }

  Widget _buildSelectionActionBar(AnimeListProvider provider) {
    final hasSelection = provider.selectedAnimeIds.isNotEmpty;
    final colorScheme = Theme.of(context).colorScheme;

    return Material(
      color: colorScheme.surfaceContainerHigh,
      child: SafeArea(
        top: false,
        minimum: const EdgeInsets.all(AppSpacing.md),
        child: SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          physics: const BouncingScrollPhysics(),
          child: Row(
            children: [
              _buildActionButton(
                icon: Icons.edit_attributes_rounded,
                label: '修改状态',
                onPressed: hasSelection ? _showBatchStatusDialog : null,
                color: Colors.blue,
              ),
              const SizedBox(width: AppSpacing.sm),
              _buildActionButton(
                icon: Icons.label_outline_rounded,
                label: '批量标签',
                onPressed: hasSelection ? _showBatchTagMenu : null,
                color: Colors.orange,
              ),
              const SizedBox(width: AppSpacing.sm),
              _buildActionButton(
                icon: Icons.auto_fix_high_rounded,
                label: '自动匹配',
                onPressed: hasSelection ? _batchAutoMatchInfo : null,
                color: Colors.purple,
              ),
              const SizedBox(width: AppSpacing.sm),
              _buildActionButton(
                icon: Icons.cloud_upload_outlined,
                label: '同步封面',
                onPressed: hasSelection ? _batchSyncCoversToServer : null,
                color: Colors.teal,
              ),
              const SizedBox(width: AppSpacing.sm),
              _buildActionButton(
                icon: Icons.delete_outline_rounded,
                label: '删除',
                onPressed: hasSelection ? _batchDelete : null,
                color: colorScheme.error,
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildActionButton({
    required IconData icon,
    required String label,
    required VoidCallback? onPressed,
    required Color color,
  }) {
    return FilledButton.tonalIcon(
      onPressed: onPressed,
      icon: Icon(icon, size: 20),
      label: Text(label),
      style: FilledButton.styleFrom(
        foregroundColor: color,
        backgroundColor: color.withValues(alpha: 0.12),
        disabledBackgroundColor: Theme.of(
          context,
        ).colorScheme.surfaceContainerHighest,
        disabledForegroundColor: Theme.of(context).colorScheme.outline,
        minimumSize: const Size(0, AppSize.minInteractive),
      ),
    );
  }

  Widget _buildUnifiedFilterSection(AnimeListProvider provider) {
    if (provider.isSelectionMode) return const SizedBox.shrink();

    final hasYearFilter = provider.selectedYears.isNotEmpty;
    final hasTagFilter = provider.selectedTagIds.isNotEmpty;
    final colorScheme = Theme.of(context).colorScheme;

    return Material(
      color: colorScheme.surfaceContainerLowest,
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.fromLTRB(
          AppSpacing.lg,
          AppSpacing.sm,
          AppSpacing.lg,
          AppSpacing.md,
        ),
        decoration: BoxDecoration(
          border: Border(
            bottom: BorderSide(
              color: colorScheme.outlineVariant.withValues(alpha: 0.35),
            ),
          ),
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Badge(
                  isLabelVisible: provider.activeFilterCount > 0,
                  label: Text('${provider.activeFilterCount}'),
                  child: FilledButton.tonalIcon(
                    onPressed: _showFilterPanel,
                    icon: const Icon(Icons.tune_rounded, size: 20),
                    label: const Text('筛选'),
                  ),
                ),
                const SizedBox(width: AppSpacing.md),
                Expanded(
                  child: SingleChildScrollView(
                    scrollDirection: Axis.horizontal,
                    physics: const BouncingScrollPhysics(),
                    child: Row(
                      children: [
                        ActionChip(
                          avatar: Icon(
                            provider.selectedSortDirection == 'ASC'
                                ? Icons.arrow_upward_rounded
                                : Icons.arrow_downward_rounded,
                            size: 16,
                          ),
                          label: Text(
                            _shortSortLabel(provider.selectedSortLabel),
                          ),
                          tooltip: '调整排序',
                          onPressed: _showSortMenu,
                        ),
                        const SizedBox(width: AppSpacing.sm),
                        if (provider.selectedType != 'all')
                          _buildFilterSummaryChip(
                            provider.selectedType == 'anime' ? '动画' : '小说',
                            () => provider.setType('all'),
                          ),
                        if (provider.selectedStatus != '全部')
                          _buildFilterSummaryChip(
                            provider.selectedStatus,
                            () => provider.setStatus('全部'),
                          ),
                        if (hasYearFilter)
                          _buildFilterSummaryChip(
                            provider.selectedYears.length == 1
                                ? provider.selectedYears.first
                                : '${provider.selectedYears.length} 个年份',
                            provider.clearYears,
                          ),
                        if (hasTagFilter)
                          _buildFilterSummaryChip(
                            '${provider.selectedTagIds.length} 个标签',
                            provider.clearTags,
                          ),
                        if (!provider.hasActiveFilters)
                          Padding(
                            padding: const EdgeInsets.only(left: AppSpacing.xs),
                            child: Text(
                              '全部作品',
                              style: Theme.of(context).textTheme.bodySmall
                                  ?.copyWith(
                                    color: colorScheme.onSurfaceVariant,
                                  ),
                            ),
                          ),
                      ],
                    ),
                  ),
                ),
                if (provider.hasActiveFilters)
                  IconButton(
                    tooltip: '重置所有筛选',
                    onPressed: provider.clearFilters,
                    icon: const Icon(Icons.restart_alt_rounded),
                  ),
              ],
            ),
            if (hasTagFilter && provider.selectedTagIds.length > 1)
              Padding(
                padding: const EdgeInsets.only(top: AppSpacing.sm),
                child: Text(
                  provider.isAndMode ? '标签关系：全部满足（且）' : '标签关系：任一满足（或）',
                  style: Theme.of(context).textTheme.labelSmall?.copyWith(
                    color: colorScheme.primary,
                    fontWeight: FontWeight.w800,
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }

  String _shortSortLabel(String value) {
    return switch (value) {
      '默认 (最新添加)' => '最近添加',
      '我看完的时间' => '看完时间',
      _ => value,
    };
  }

  Widget _buildFilterSummaryChip(String label, VoidCallback onDeleted) {
    return Padding(
      padding: const EdgeInsets.only(right: AppSpacing.sm),
      child: InputChip(
        label: Text(label),
        onDeleted: onDeleted,
        deleteIcon: const Icon(Icons.close_rounded, size: 16),
        visualDensity: VisualDensity.compact,
      ),
    );
  }

  Widget _buildEmptyState(AnimeListProvider provider) {
    if (provider.searchQuery.trim().isNotEmpty) {
      return EmptyStateWidget(
        icon: Icons.search_off_rounded,
        message: '没有找到相关作品',
        description: '换个关键词，或清除搜索后查看全部资料库',
        buttonText: '清除搜索',
        onButtonPressed: () {
          _searchController.clear();
          _searchFocusNode.requestFocus();
        },
      );
    }
    if (provider.hasActiveFilters) {
      return EmptyStateWidget(
        icon: Icons.filter_alt_off_rounded,
        message: '没有符合条件的作品',
        description: '当前筛选组合过于严格，可以重置后重新选择',
        buttonText: '重置筛选',
        onButtonPressed: provider.clearFilters,
      );
    }
    return EmptyStateWidget(
      message: '资料库还是空的',
      description: '添加第一部作品后，这里会自动整理进度与最近记录',
      buttonText: '添加第一部作品',
      onButtonPressed: _navigateToAddPage,
    );
  }

  Widget _buildContentBody(AnimeListProvider provider) {
    final settings = SettingsManager();
    return ListenableBuilder(
      listenable: Listenable.merge([
        settings.homeLayoutNotifier,
        settings.titlePositionNotifier,
        settings.coverBorderRadiusNotifier,
        settings.badgeScaleNotifier,
        settings.badgeOpacityNotifier,
        settings.badgeRadiusNotifier,
        settings.gridColumnsNotifier,
        settings.showTitleNotifier,
        settings.showRatingNotifier,
        settings.showProgressNotifier,
        settings.showStatusNotifier,
        settings.showSubjectTypeNotifier,
        settings.badgeStyleNotifier,
        settings.showCoverStatusNotifier,
        settings.showCoverRatingNotifier,
        settings.showCoverProgressNotifier,
        settings.showCoverTypeNotifier,
        settings.showCoverSeriesCountNotifier,
      ]),
      builder: (context, _) {
        final layout = settings.homeLayoutNotifier.value;
        final props = HomeViewProps(
          items: provider.animes,
          totalItemCount: provider.totalItemCount,
          statusColors: provider.statusColors,
          appDocDir: _appDocDir,
          isSelectionMode: provider.isSelectionMode,
          selectedIds: provider.selectedAnimeIds,
          onItemTap: _handleItemTap,
          onItemLongPress: _handleItemLongPress,
          onRefresh: () async {
            await provider.refreshData();
          },
          onLoadMore: provider.hasMoreData
              ? () => provider.loadMoreItems()
              : null,
          hasMore: provider.hasMoreData,
          gridColumns: settings.gridColumnsNotifier.value,
          titlePosition: settings.titlePositionNotifier.value,
          coverBorderRadius: settings.coverBorderRadiusNotifier.value,
          badgeScale: settings.badgeScaleNotifier.value,
          badgeOpacity: settings.badgeOpacityNotifier.value,
          badgeRadius: settings.badgeRadiusNotifier.value,
          showTitle: settings.showTitleNotifier.value,
          showRating: settings.showRatingNotifier.value,
          showProgress: settings.showProgressNotifier.value,
          showStatus: settings.showStatusNotifier.value,
          showSubjectType: settings.showSubjectTypeNotifier.value,
          badgeStyle: settings.badgeStyleNotifier.value,
          showCoverStatus: settings.showCoverStatusNotifier.value,
          showCoverRating: settings.showCoverRatingNotifier.value,
          showCoverProgress: settings.showCoverProgressNotifier.value,
          showCoverType: settings.showCoverTypeNotifier.value,
          showCoverSeriesCount: settings.showCoverSeriesCountNotifier.value,
          selectedStatus: provider.selectedStatus,
          isInDefaultMode: provider.isInDefaultMode(),
          isSortedByPinyin:
              provider.selectedSortKeys.isNotEmpty &&
              provider.selectedSortKeys.first == '拼音',
          progressTextOf: _getProgressText,
        );
        return buildHomeView(layout, props);
      },
    );
  }
}

class _DuplicateCleanupDialog extends StatefulWidget {
  final List<DuplicateAnimeGroup> groups;
  final Future<int> Function(Iterable<int> ids) onDelete;

  const _DuplicateCleanupDialog({required this.groups, required this.onDelete});

  @override
  State<_DuplicateCleanupDialog> createState() =>
      _DuplicateCleanupDialogState();
}

class _DuplicateCleanupDialogState extends State<_DuplicateCleanupDialog> {
  final Set<int> _selectedIds = {};
  bool _isDeleting = false;

  int get _duplicateItemCount =>
      widget.groups.fold<int>(0, (total, group) => total + group.items.length);

  void _toggleSelection(int id, bool selected) {
    setState(() {
      if (selected) {
        _selectedIds.add(id);
      } else {
        _selectedIds.remove(id);
      }
    });
  }

  void _selectOlderItems() {
    setState(() {
      _selectedIds.clear();
      for (final group in widget.groups) {
        final ids = group.items.map(_idOf).whereType<int>().toList();
        if (ids.length <= 1) continue;
        _selectedIds.addAll(ids.skip(1));
      }
    });
  }

  void _clearSelection() {
    setState(_selectedIds.clear);
  }

  Future<void> _deleteSelected() async {
    if (_selectedIds.isEmpty || _isDeleting) return;

    final invalidGroup = _firstFullySelectedGroup();
    if (invalidGroup != null) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('「${invalidGroup.title}」至少需要保留 1 条')),
      );
      return;
    }

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('确认删除重复项'),
        content: Text('确定要永久删除选中的 ${_selectedIds.length} 个条目吗？此操作不可恢复。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('取消'),
          ),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: Colors.red),
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('确认删除'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;

    setState(() => _isDeleting = true);
    final deletedCount = await widget.onDelete(_selectedIds);
    if (!mounted) return;
    Navigator.pop(context, deletedCount);
  }

  DuplicateAnimeGroup? _firstFullySelectedGroup() {
    for (final group in widget.groups) {
      final ids = group.items.map(_idOf).whereType<int>().toList();
      if (ids.isNotEmpty && ids.every(_selectedIds.contains)) {
        return group;
      }
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Row(
        children: [
          Icon(Icons.content_copy_rounded),
          SizedBox(width: 8),
          Text('重复条目'),
        ],
      ),
      content: SizedBox(
        width: double.maxFinite,
        height: 460,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              '发现 ${widget.groups.length} 组、$_duplicateItemCount 个同类型同名条目。勾选要删除的条目，每组会至少保留 1 条。',
              style: TextStyle(fontSize: 13, color: Colors.grey[700]),
            ),
            const SizedBox(height: 12),
            Expanded(
              child: ListView.separated(
                itemCount: widget.groups.length,
                separatorBuilder: (_, _) => const Divider(height: 1),
                itemBuilder: (context, index) {
                  final group = widget.groups[index];
                  return ExpansionTile(
                    initiallyExpanded: widget.groups.length <= 4,
                    tilePadding: EdgeInsets.zero,
                    title: Text(
                      group.title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontWeight: FontWeight.bold),
                    ),
                    subtitle: Text(
                      '${group.typeLabel} · ${group.items.length} 条',
                    ),
                    children: group.items.map((item) {
                      final id = _idOf(item);
                      final enabled = id != null && !_isDeleting;
                      return CheckboxListTile(
                        dense: true,
                        value: id != null && _selectedIds.contains(id),
                        onChanged: enabled
                            ? (value) => _toggleSelection(id, value ?? false)
                            : null,
                        title: Text(
                          (item['title'] ?? '').toString(),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                        subtitle: Text(_duplicateItemSubtitle(item)),
                        controlAffinity: ListTileControlAffinity.leading,
                      );
                    }).toList(),
                  );
                },
              ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _isDeleting ? null : _selectOlderItems,
          child: const Text('勾选较旧项'),
        ),
        TextButton(
          onPressed: _selectedIds.isEmpty || _isDeleting
              ? null
              : _clearSelection,
          child: const Text('清空'),
        ),
        TextButton(
          onPressed: _isDeleting ? null : () => Navigator.pop(context, 0),
          child: const Text('关闭'),
        ),
        FilledButton.icon(
          style: FilledButton.styleFrom(backgroundColor: Colors.red),
          onPressed: _selectedIds.isEmpty || _isDeleting
              ? null
              : _deleteSelected,
          icon: _isDeleting
              ? const SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : const Icon(Icons.delete_outline),
          label: Text(_isDeleting ? '删除中...' : '删除(${_selectedIds.length})'),
        ),
      ],
    );
  }

  int? _idOf(Map<String, dynamic> item) {
    final id = item['id'];
    if (id is int) return id;
    if (id is num) return id.toInt();
    return int.tryParse(id?.toString() ?? '');
  }

  String _duplicateItemSubtitle(Map<String, dynamic> item) {
    final parts = <String>[];
    final id = _idOf(item);
    if (id != null) parts.add('#$id');

    final status = (item['status'] ?? '').toString().trim();
    if (status.isNotEmpty) parts.add(status);

    final watched = _asInt(item['watched_episodes']) ?? 0;
    final total = _asInt(item['total_episodes']) ?? 0;
    final unit = (item['subject_type'] ?? 'anime') == 'book' ? '话' : '集';
    if (watched > 0 || total > 0) {
      parts.add(total > 0 ? '$watched/$total $unit' : '$watched $unit');
    }

    final date = (item['air_date'] ?? item['created_at'] ?? '')
        .toString()
        .trim();
    if (date.isNotEmpty) parts.add(date);
    return parts.isEmpty ? '无附加信息' : parts.join(' · ');
  }

  int? _asInt(dynamic value) {
    if (value == null) return null;
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value.toString());
  }
}

class _AutoMatchProgressDialog extends StatefulWidget {
  final List<int> selectedIds;
  final List<Map<String, dynamic>> animes;
  final Map<String, bool> enabledFields;
  final VoidCallback onCompleted;

  const _AutoMatchProgressDialog({
    required this.selectedIds,
    required this.animes,
    required this.enabledFields,
    required this.onCompleted,
  });

  @override
  State<_AutoMatchProgressDialog> createState() =>
      _AutoMatchProgressDialogState();
}

class _AutoMatchProgressDialogState extends State<_AutoMatchProgressDialog> {
  int _currentCount = 0;
  int _successCount = 0;
  String _currentTitle = "准备中...";
  double _progress = 0.0;
  bool _stopRequested = false;
  bool _skipRequested = false;
  final Stopwatch _stopwatch = Stopwatch();
  Timer? _timer;
  String _durationText = "0.0s";

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  void initState() {
    super.initState();
    _startBatchProcess();
  }

  void _startBatchProcess() async {
    int total = widget.selectedIds.length;
    for (int i = 0; i < total; i++) {
      if (!mounted || _stopRequested) break;
      int animeId = widget.selectedIds[i];
      final anime = widget.animes.firstWhere(
        (element) => element['id'] == animeId,
        orElse: () => {},
      );
      if (anime.isEmpty) continue;

      String title = anime['title'];

      setState(() {
        _currentCount = i + 1;
        _currentTitle = "正在搜索: $title";
        _progress = _currentCount / total;
      });
      try {
        _stopwatch.reset();
        _stopwatch.start();
        _skipRequested = false;
        _timer?.cancel();
        _timer = Timer.periodic(const Duration(milliseconds: 100), (timer) {
          if (mounted) {
            setState(() {
              _durationText =
                  "${(_stopwatch.elapsedMilliseconds / 1000).toStringAsFixed(1)}s";
            });
          }
        });

        final resultsList = await Future.any([
          Future.wait([
            BangumiService.searchAnime(title),
            AnilistService.searchAnime(title),
          ]),
          _waitForSkip(),
        ]);

        _stopwatch.stop();
        _timer?.cancel();

        if (_skipRequested) {
          continue;
        }

        final allResults = [...resultsList[0], ...resultsList[1]];
        if (allResults.isNotEmpty) {
          final bestMatch = allResults.first;
          Map<String, dynamic> updateData = {'id': animeId};

          if (widget.enabledFields['cover'] == true &&
              bestMatch.coverUrl != null &&
              bestMatch.coverUrl!.isNotEmpty) {
            updateData['cover_url'] = bestMatch.coverUrl;
          }
          if (widget.enabledFields['episodes'] == true &&
              bestMatch.eps != null &&
              bestMatch.eps != 0) {
            updateData['total_episodes'] = bestMatch.eps;
          }
          if (widget.enabledFields['rating'] == true &&
              bestMatch.score != null &&
              bestMatch.score! > 0) {
            updateData['rating'] = bestMatch.score;
          }

          if (bestMatch.source == 'bangumi') {
            final detail = await BangumiService.getAnimeDetail(bestMatch.id);
            if (widget.enabledFields['airDate'] == true) {
              String? finalAirDate = detail['air_date'];
              if (finalAirDate == null || finalAirDate.isEmpty) {
                finalAirDate = bestMatch.airDate;
              }
              if (finalAirDate != null && finalAirDate.isNotEmpty) {
                updateData['air_date'] = finalAirDate;
              }
            }

            if (widget.enabledFields['studio'] == true &&
                detail['studio'] != null) {
              updateData['studio'] = detail['studio'];
            }
            if (widget.enabledFields['episodes'] == true &&
                detail['eps'] != null &&
                detail['eps'] != 0) {
              updateData['total_episodes'] = detail['eps'];
            }
            if (widget.enabledFields['rating'] == true &&
                detail['score'] != null) {
              updateData['rating'] = detail['score'];
            }
            if (widget.enabledFields['summary'] == true) {
              String existingReview = anime['review'] ?? '';
              String newSummary = detail['summary'] ?? '';
              if (newSummary.isNotEmpty && existingReview.isEmpty) {
                updateData['review'] = newSummary;
              }
            }
          } else {
            if (widget.enabledFields['airDate'] == true &&
                bestMatch.airDate != null) {
              updateData['air_date'] = bestMatch.airDate;
            }
            if (widget.enabledFields['studio'] == true &&
                bestMatch.studio != null) {
              updateData['studio'] = bestMatch.studio;
            }
            if (widget.enabledFields['summary'] == true) {
              String existingReview = anime['review'] ?? '';
              if (bestMatch.summary != null &&
                  bestMatch.summary!.isNotEmpty &&
                  existingReview.isEmpty) {
                updateData['review'] = bestMatch.summary;
              }
            }
          }

          await getIt<AnimeRepository>().updateAnime(updateData);
          _successCount++;
        }
      } catch (e) {
        logger.e("Auto match error: $e");
      }

      await Future.delayed(const Duration(milliseconds: 500));
    }

    if (mounted) {
      widget.onCompleted();
    }
  }

  Future<List<dynamic>> _waitForSkip() async {
    while (!_skipRequested && !_stopRequested && mounted) {
      await Future.delayed(const Duration(milliseconds: 200));
    }
    return [[], []];
  }

  @override
  Widget build(BuildContext context) {
    bool canSkip = _stopwatch.elapsed.inSeconds >= 20;

    return AlertDialog(
      title: const Text("自动匹配进度"),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          LinearProgressIndicator(
            value: _progress,
            backgroundColor: _stopRequested ? Colors.grey[300] : null,
            valueColor: _stopRequested
                ? const AlwaysStoppedAnimation<Color>(Colors.grey)
                : null,
          ),
          const SizedBox(height: 20),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                _stopRequested
                    ? "已停止"
                    : "进度: $_currentCount / ${widget.selectedIds.length}",
              ),
              Text(
                "耗时: $_durationText",
                style: const TextStyle(
                  fontSize: 12,
                  fontFamily: 'monospace',
                  color: Colors.blueGrey,
                ),
              ),
            ],
          ),
          Text("成功匹配: $_successCount"),
          const SizedBox(height: 10),
          Text(
            _stopRequested ? "操作已取消" : _currentTitle,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(color: Colors.grey),
          ),
        ],
      ),
      actions: [
        if (!_stopRequested && _currentCount < widget.selectedIds.length) ...[
          if (canSkip)
            TextButton(
              onPressed: () {
                setState(() {
                  _skipRequested = true;
                });
              },
              child: const Text("跳过此项"),
            ),
          TextButton(
            style: TextButton.styleFrom(foregroundColor: Colors.red),
            onPressed: () {
              setState(() {
                _stopRequested = true;
              });
            },
            child: const Text("停止"),
          ),
        ],
      ],
    );
  }
}
