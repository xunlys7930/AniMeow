import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:path/path.dart' as path;
import 'package:lpinyin/lpinyin.dart';
import '../components/empty_state.dart';
import '../components/status_badge.dart';
import '../design_tokens.dart';
import '_shared/rating_icon.dart';
import 'home_layout.dart';
import '_shared/anime_swipe_actions.dart';
import '../../utils/anime_rating.dart';

/// 紧凑索引（Notion-like 列表 + 拼音字母锚点）
///
/// 当 [HomeViewProps.isSortedByPinyin] 为 true 时：
/// - 按首字母分组，每组前置一个 A/B/C... 粘头
/// - 右侧固定一根 A-Z 字母条，点按瞬移到对应分组
///
/// 否则退化为平铺紧凑列表（保持当前可用版本）。
class CompactIndexView extends StatefulWidget {
  final HomeViewProps props;

  const CompactIndexView({super.key, required this.props});

  @override
  State<CompactIndexView> createState() => _CompactIndexViewState();
}

class _CompactIndexViewState extends State<CompactIndexView> {
  final ScrollController _scrollController = ScrollController();
  static const double _itemHeight = 76.0;
  static const double _headerHeight = 36.0;

  /// 扁平条目：header (letter) 或 item (anime/series)
  late List<_FlatEntry> _flat;

  /// 字母 → 该字母的 header 在 _flat 中的 index
  late Map<String, int> _letterIndex;

  @override
  void initState() {
    super.initState();
    _rebuildFlat();
  }

  @override
  void didUpdateWidget(covariant CompactIndexView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.props.items != widget.props.items ||
        oldWidget.props.isSortedByPinyin != widget.props.isSortedByPinyin) {
      _rebuildFlat();
    }
  }

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  void _rebuildFlat() {
    _flat = [];
    _letterIndex = {};

    if (!widget.props.isSortedByPinyin) {
      _flat = widget.props.items.map(_FlatEntry.item).toList();
      return;
    }

    String? prev;
    for (final it in widget.props.items) {
      final name = (it['name'] ?? it['title'] ?? '').toString();
      final letter = _firstLetterOf(name);
      if (letter != prev) {
        _letterIndex[letter] = _flat.length;
        _flat.add(_FlatEntry.header(letter));
        prev = letter;
      }
      _flat.add(_FlatEntry.item(it));
    }
  }

  String _firstLetterOf(String name) {
    if (name.isEmpty) return '#';
    final pinyin = PinyinHelper.getShortPinyin(name);
    if (pinyin.isEmpty) return '#';
    final c = pinyin[0].toUpperCase();
    return RegExp(r'[A-Z]').hasMatch(c) ? c : '#';
  }

  double _offsetForFlatIndex(int idx) {
    double offset = 0;
    for (var i = 0; i < idx; i++) {
      offset += _flat[i].isHeader ? _headerHeight : _itemHeight;
    }
    return offset;
  }

  void _jumpToLetter(String letter) {
    final idx = _letterIndex[letter];
    if (idx == null) return;
    HapticFeedback.selectionClick();
    _scrollController.animateTo(
      _offsetForFlatIndex(idx),
      duration: const Duration(milliseconds: 280),
      curve: Curves.easeOutCubic,
    );
  }

  @override
  Widget build(BuildContext context) {
    if (widget.props.items.isEmpty) {
      return RefreshIndicator(
        onRefresh: () async {
          HapticFeedback.selectionClick();
          await widget.props.onRefresh();
        },
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          children: const [
            SizedBox(height: 200),
            EmptyStateWidget(message: '还没添加番剧', buttonText: '去添加一部吧'),
          ],
        ),
      );
    }

    final list = RefreshIndicator(
      onRefresh: () async {
        HapticFeedback.selectionClick();
        await widget.props.onRefresh();
      },
      child: NotificationListener<ScrollNotification>(
        onNotification: (n) {
          final m = n.metrics;
          if (n is ScrollUpdateNotification &&
              m.pixels >= m.maxScrollExtent - 800) {
            widget.props.onLoadMore?.call();
          }
          return false;
        },
        child: ListView.builder(
          controller: _scrollController,
          keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
          padding: EdgeInsets.only(
            bottom: AppSpacing.xxl * 2,
            // 给右侧字母条让出 24dp 触控宽度
            right: widget.props.isSortedByPinyin ? 24 : 0,
          ),
          physics: const AlwaysScrollableScrollPhysics(
            parent: BouncingScrollPhysics(),
          ),
          itemCount: _flat.length + (widget.props.hasMore ? 1 : 0),
          itemExtentBuilder: (i, _) {
            if (i >= _flat.length) return _headerHeight;
            return _flat[i].isHeader ? _headerHeight : _itemHeight;
          },
          itemBuilder: (context, i) {
            if (i >= _flat.length) {
              return const Center(
                child: SizedBox(
                  width: 24,
                  height: 24,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
              );
            }
            final entry = _flat[i];
            if (entry.isHeader) {
              return _GroupHeader(letter: entry.letter!);
            }
            final item = entry.item!;
            // 系列与多选模式不允许滑动
            if (item['type'] == 'series' || widget.props.isSelectionMode) {
              return _CompactItem(item: item, props: widget.props);
            }
            return SwipeActionTile(
              dismissKey: ValueKey('ci_${item['id']}'),
              onSwipeRight: () => incrementEpisode(
                item,
                context: context,
                onRefresh: widget.props.onRefresh,
              ),
              onSwipeLeft: () => cycleStatus(
                item,
                context: context,
                onRefresh: widget.props.onRefresh,
              ),
              child: _CompactItem(item: item, props: widget.props),
            );
          },
        ),
      ),
    );

    if (!widget.props.isSortedByPinyin) {
      return list;
    }

    // 加右侧 A-Z 字母条
    return Stack(
      children: [
        Positioned.fill(child: list),
        Positioned(
          right: 2,
          top: 0,
          bottom: 0,
          child: Center(
            child: _AlphabetRail(
              letters: _letterIndex.keys.toList(),
              onTap: _jumpToLetter,
            ),
          ),
        ),
      ],
    );
  }
}

/// 扁平条目（header 或 item）
class _FlatEntry {
  final String? letter;
  final Map<String, dynamic>? item;

  const _FlatEntry._({this.letter, this.item});
  factory _FlatEntry.header(String letter) => _FlatEntry._(letter: letter);
  factory _FlatEntry.item(Map<String, dynamic> item) =>
      _FlatEntry._(item: item);

  bool get isHeader => letter != null;
}

class _GroupHeader extends StatelessWidget {
  final String letter;
  const _GroupHeader({required this.letter});

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return Container(
      height: 36,
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg),
      alignment: Alignment.centerLeft,
      decoration: BoxDecoration(
        color: colorScheme.surface,
        border: Border(
          bottom: BorderSide(
            color: colorScheme.outlineVariant.withValues(alpha: 0.3),
            width: 0.5,
          ),
        ),
      ),
      child: Text(
        letter,
        style: TextStyle(
          fontSize: 13,
          fontWeight: FontWeight.w900,
          letterSpacing: 1.5,
          color: colorScheme.primary,
        ),
      ),
    );
  }
}

class _AlphabetRail extends StatelessWidget {
  final List<String> letters;
  final void Function(String letter) onTap;

  const _AlphabetRail({required this.letters, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 6),
      decoration: BoxDecoration(
        color: colorScheme.surfaceContainerHigh.withValues(alpha: 0.92),
        borderRadius: BorderRadius.circular(24),
        boxShadow: AppElevation.card(context),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: letters.map((letter) {
          return InkWell(
            onTap: () => onTap(letter),
            customBorder: const CircleBorder(),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
              child: Text(
                letter,
                style: TextStyle(
                  fontSize: 10,
                  fontWeight: FontWeight.w800,
                  color: colorScheme.primary,
                  letterSpacing: 0.5,
                ),
              ),
            ),
          );
        }).toList(),
      ),
    );
  }
}

class _CompactItem extends StatelessWidget {
  final Map<String, dynamic> item;
  final HomeViewProps props;

  const _CompactItem({required this.item, required this.props});

  bool get _isSeries => item['type'] == 'series';
  int get _id => item['id'] as int;
  bool get _isSelected => props.selectedIds.contains(_id);

  String get _title => _isSeries
      ? (item['name'] ?? item['series_name'] ?? '').toString()
      : (item['title'] ?? '').toString();

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final status = (item['status'] ?? '').toString();
    final statusColor = props.statusColors[status] ?? Colors.grey;
    final rating = animeRatingOf(item);

    return InkWell(
      onTap: () => props.onItemTap(item),
      onLongPress: () => props.onItemLongPress(_id),
      child: Container(
        color: props.isSelectionMode && _isSelected
            ? colorScheme.primaryContainer.withValues(alpha: 0.3)
            : Colors.transparent,
        padding: const EdgeInsets.symmetric(
          horizontal: AppSpacing.lg,
          vertical: AppSpacing.md,
        ),
        child: Row(
          children: [
            ClipRRect(
              borderRadius: BorderRadius.circular(props.coverBorderRadius),
              child: SizedBox(
                width: 48,
                height: 64,
                child: Hero(
                  tag: _isSeries ? 'series_cover_$_id' : 'cover_$_id',
                  child: _buildThumbnail(context),
                ),
              ),
            ),
            const SizedBox(width: AppSpacing.md),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (props.showTitle)
                    Text(
                      _title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.titleSmall?.copyWith(
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  const SizedBox(height: 2),
                  Row(
                    children: [
                      if (_isSeries)
                        Text(
                          '系列 · ${item['anime_count'] ?? 0} 部',
                          style: Theme.of(context).textTheme.bodySmall
                              ?.copyWith(
                                color: colorScheme.tertiary,
                                fontWeight: FontWeight.w600,
                              ),
                        )
                      else ...[
                        if (props.showStatus && status.isNotEmpty)
                          StatusBadge(
                            status: status,
                            color: statusColor,
                            isCompact: true,
                          ),
                        if (props.showProgress &&
                            props.progressTextOf(item).isNotEmpty) ...[
                          const SizedBox(width: AppSpacing.sm),
                          Text(
                            props.progressTextOf(item),
                            style: Theme.of(context).textTheme.bodySmall
                                ?.copyWith(color: colorScheme.onSurfaceVariant),
                          ),
                        ],
                      ],
                    ],
                  ),
                ],
              ),
            ),
            if (!_isSeries && props.showRating && rating.hasValue) ...[
              const SizedBox(width: AppSpacing.sm),
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const RatingIconWidget(size: 12),
                  const SizedBox(width: 2),
                  Text(
                    rating.label,
                    style: TextStyle(
                      fontSize: 11,
                      fontWeight: FontWeight.w800,
                      color: Colors.amber[800],
                    ),
                  ),
                ],
              ),
            ],
            const SizedBox(width: AppSpacing.sm),
            if (props.isSelectionMode)
              Icon(
                _isSelected
                    ? Icons.check_circle_rounded
                    : Icons.radio_button_unchecked,
                size: 20,
                color: _isSelected
                    ? colorScheme.primary
                    : colorScheme.onSurfaceVariant,
              )
            else
              Icon(
                Icons.chevron_right_rounded,
                size: 18,
                color: colorScheme.onSurfaceVariant,
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildThumbnail(BuildContext context) {
    final coverUrl = item['cover_url'] as String?;
    final pixelRatio = MediaQuery.of(context).devicePixelRatio;
    final fallback = Container(
      color: Theme.of(context).colorScheme.surfaceContainerHigh,
      child: Icon(
        _isSeries ? Icons.layers : Icons.movie_outlined,
        size: 22,
        color: Theme.of(context).colorScheme.onSurfaceVariant,
      ),
    );
    if (coverUrl == null || coverUrl.isEmpty) return fallback;

    if (coverUrl.startsWith('http')) {
      return CachedNetworkImage(
        imageUrl: coverUrl,
        fit: BoxFit.cover,
        memCacheWidth: (96 * pixelRatio).toInt(),
        placeholder: (_, _) => Container(
          color: Theme.of(context).colorScheme.surfaceContainerHigh,
        ),
        errorWidget: (_, _, _) => fallback,
      );
    }

    final base = props.appDocDir;
    if (base == null) return fallback;

    final f = path.isAbsolute(coverUrl)
        ? File(coverUrl)
        : File(path.join(base.path, coverUrl));
    return Image.file(
      f,
      fit: BoxFit.cover,
      cacheWidth: (96 * pixelRatio).toInt(),
      errorBuilder: (_, _, _) => fallback,
    );
  }
}
