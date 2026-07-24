import 'dart:io';
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:anime_tracker/api/anilist_service.dart';
import 'package:anime_tracker/api/bangumi_service.dart';
import 'package:anime_tracker/api/trace_moe_service.dart';
import 'package:anime_tracker/ui/design_tokens.dart';
import 'package:anime_tracker/ui/image_crop_page.dart';
import 'package:anime_tracker/ui/pages/image_search/image_anime_search_state.dart';

class ImageAnimeSearchSelection {
  final BangumiSearchResult result;
  final bool isBangumi;

  const ImageAnimeSearchSelection({
    required this.result,
    required this.isBangumi,
  });
}

class ImageAnimeSearchPage extends StatefulWidget {
  final Future<void> Function(
    BuildContext context,
    ImageAnimeSearchSelection selection,
  )?
  onSelection;
  final String useResultLabel;

  const ImageAnimeSearchPage({
    super.key,
    this.onSelection,
    this.useResultLabel = '使用此结果',
  });

  @override
  State<ImageAnimeSearchPage> createState() => _ImageAnimeSearchPageState();
}

class _ImageAnimeSearchPageState extends State<ImageAnimeSearchPage> {
  static const double _bangumiAcceptScore = 0.72;

  final ImagePicker _picker = ImagePicker();
  final Map<int, Future<AnilistAnimeInfo?>> _infoFutures = {};

  ImageAnimeSearchViewState _viewState = const ImageAnimeSearchViewState();
  int _searchGeneration = 0;
  int _resolveGeneration = 0;

  File? get _queryImage => _viewState.queryImage;
  TraceMoeSearchResponse? get _response => _viewState.response;
  int get _selectedIndex => _viewState.selectedIndex;
  String? get _errorText => _viewState.errorText;
  TraceMoeSearchResult? get _selected => _viewState.selected;
  bool get _isResolving =>
      _viewState.phase == ImageAnimeSearchPhase.resolvingMetadata;
  bool get _isBusy => _viewState.phase.isBusy;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _pickAndSearch());
  }

  @override
  void dispose() {
    _searchGeneration++;
    _resolveGeneration++;
    super.dispose();
  }

  Future<void> _pickAndSearch() async {
    if (_isBusy) return;
    final pickedFile = await _picker.pickImage(source: ImageSource.gallery);
    if (!mounted || pickedFile == null) return;

    await _searchImageFile(File(pickedFile.path));
  }

  Future<void> _cropAndSearch() async {
    final current = _queryImage;
    if (current == null || _isBusy) return;

    final restorePhase = _viewState.hasResults
        ? ImageAnimeSearchPhase.ready
        : _viewState.phase == ImageAnimeSearchPhase.failed
        ? ImageAnimeSearchPhase.failed
        : ImageAnimeSearchPhase.idle;
    setState(() {
      _viewState = _viewState.copyWith(
        phase: ImageAnimeSearchPhase.cropping,
        errorText: null,
      );
    });

    final cropped = await Navigator.of(context).push<String>(
      MaterialPageRoute(
        builder: (_) => ImageCropPage(imagePath: current.path, title: '裁剪搜索截图'),
      ),
    );
    if (!mounted) return;
    if (cropped == null) {
      setState(() {
        _viewState = _viewState.copyWith(phase: restorePhase);
      });
      return;
    }

    await _searchImageFile(File(cropped));
  }

  Future<void> _searchImageFile(File file) async {
    if (!mounted) return;
    final generation = ++_searchGeneration;
    _resolveGeneration++;
    _infoFutures.clear();
    setState(() {
      _viewState = _viewState.copyWith(
        phase: ImageAnimeSearchPhase.searchingTrace,
        queryImage: file,
        response: null,
        selectedIndex: 0,
        errorText: null,
      );
    });

    try {
      final response = await TraceMoeService.searchByImageWithStats(file);
      if (!mounted || generation != _searchGeneration) return;
      setState(() {
        _viewState = _viewState.copyWith(
          phase: response.results.isEmpty
              ? ImageAnimeSearchPhase.failed
              : ImageAnimeSearchPhase.ready,
          response: response,
          errorText: response.results.isEmpty ? '没有识别到匹配的番剧' : null,
        );
      });
    } catch (e) {
      if (!mounted || generation != _searchGeneration) return;
      setState(() {
        _viewState = _viewState.copyWith(
          phase: ImageAnimeSearchPhase.failed,
          response: null,
          errorText: '以图搜番失败：$e',
        );
      });
    }
  }

  Future<AnilistAnimeInfo?>? _infoFutureFor(TraceMoeSearchResult result) {
    final id = result.anilistId;
    if (id == null || id <= 0) return null;
    return _infoFutures.putIfAbsent(
      id,
      () => AnilistService.getAnimeInfoById(id),
    );
  }

  Future<void> _useSelectedResult() async {
    final selected = _selected;
    if (selected == null || _isResolving) return;

    final generation = ++_resolveGeneration;
    setState(() {
      _viewState = _viewState.copyWith(
        phase: ImageAnimeSearchPhase.resolvingMetadata,
        errorText: null,
      );
    });
    try {
      final info = selected.anilistId == null
          ? null
          : await AnilistService.getAnimeInfoById(selected.anilistId!);
      if (!mounted || generation != _resolveGeneration) return;
      final bangumiResult = await _findBangumiResultForTraceMatch(
        selected,
        info,
      );

      if (!mounted || generation != _resolveGeneration) return;
      if (bangumiResult != null) {
        await _completeSelection(
          ImageAnimeSearchSelection(result: bangumiResult, isBangumi: true),
          generation: generation,
        );
        return;
      }

      if (info != null) {
        await _completeSelection(
          ImageAnimeSearchSelection(
            result: info.toSearchResult(),
            isBangumi: false,
          ),
          generation: generation,
        );
        return;
      }

      await _completeSelection(
        ImageAnimeSearchSelection(
          result: BangumiSearchResult(
            id: selected.anilistId ?? 0,
            nameCn: selected.searchTitle,
            nameOriginal: selected.titleRomaji ?? selected.displayTitle,
            source: 'trace.moe',
          ),
          isBangumi: false,
        ),
        generation: generation,
      );
    } catch (e) {
      if (!mounted || generation != _resolveGeneration) return;
      final message = '解析资料失败：$e';
      setState(() {
        _viewState = _viewState.copyWith(
          phase: ImageAnimeSearchPhase.ready,
          errorText: message,
        );
      });
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(message)));
    }
  }

  Future<void> _completeSelection(
    ImageAnimeSearchSelection selection, {
    required int generation,
  }) async {
    final handler = widget.onSelection;
    if (handler == null) {
      Navigator.pop(context, selection);
      return;
    }

    await handler(context, selection);
    if (!mounted || generation != _resolveGeneration) return;
    setState(() {
      _viewState = _viewState.copyWith(phase: ImageAnimeSearchPhase.ready);
    });
  }

  Future<BangumiSearchResult?> _findBangumiResultForTraceMatch(
    TraceMoeSearchResult selected,
    AnilistAnimeInfo? info,
  ) async {
    final queries = _traceSearchQueries(selected, info);
    final candidates = <String, _BangumiCandidate>{};

    for (final query in queries.take(10)) {
      final results = await BangumiService.searchAnime(query);
      if (results.isEmpty) continue;

      for (var i = 0; i < results.length && i < 8; i++) {
        final result = results[i];
        final score = _scoreBangumiResult(result, queries, info, rank: i);
        final key = _bangumiCandidateKey(result);
        final current = candidates[key];
        if (current == null || score > current.score) {
          candidates[key] = _BangumiCandidate(result: result, score: score);
        }
      }
    }

    final ranked = candidates.values.toList()
      ..sort((a, b) => b.score.compareTo(a.score));
    if (ranked.isEmpty) return null;

    final best = ranked.first;
    return best.score >= _bangumiAcceptScore ? best.result : null;
  }

  List<String> _traceSearchQueries(
    TraceMoeSearchResult selected,
    AnilistAnimeInfo? info,
  ) {
    final values = <String>[
      if (info != null) ...info.searchTitles,
      selected.titleNative ?? '',
      selected.titleRomaji ?? '',
      selected.titleEnglish ?? '',
      selected.searchTitle,
      selected.displayTitle,
    ];

    return values
        .map((item) => item.trim())
        .where((item) => item.isNotEmpty)
        .where((item) => item.length >= 2)
        .toSet()
        .take(12)
        .toList();
  }

  double _scoreBangumiResult(
    BangumiSearchResult result,
    List<String> queries,
    AnilistAnimeInfo? info, {
    required int rank,
  }) {
    final resultTitles = <String>[
      result.nameCn,
      result.nameOriginal,
    ].where((item) => item.trim().isNotEmpty).toList();

    var titleScore = 0.0;
    for (final query in queries) {
      for (final title in resultTitles) {
        final score = _titleSimilarity(query, title);
        if (score > titleScore) titleScore = score;
      }
    }

    var score = titleScore;
    final traceYear = _yearOf(info?.airDate);
    final resultYear = _yearOf(result.airDate);
    if (traceYear != null && resultYear != null) {
      final diff = (traceYear - resultYear).abs();
      if (diff == 0) {
        score += 0.08;
      } else if (diff <= 1) {
        score += 0.03;
      } else if (diff >= 3) {
        score -= 0.08;
      }
    }

    final traceEpisodes = info?.episodes;
    final resultEpisodes = result.eps;
    if (traceEpisodes != null &&
        traceEpisodes > 0 &&
        resultEpisodes != null &&
        resultEpisodes > 0) {
      final diff = (traceEpisodes - resultEpisodes).abs();
      if (diff == 0) {
        score += 0.05;
      } else if (diff <= 1) {
        score += 0.02;
      } else if (diff >= 4) {
        score -= 0.04;
      }
    }

    score += (8 - rank).clamp(0, 8) * 0.005;
    return score.clamp(0.0, 1.2).toDouble();
  }

  String _bangumiCandidateKey(BangumiSearchResult result) {
    if (result.id > 0) return '${result.source}:${result.id}';
    return '${_normalizeSearchTitle(result.nameCn)}:${_normalizeSearchTitle(result.nameOriginal)}';
  }

  double _titleSimilarity(String left, String right) {
    final a = _normalizeSearchTitle(left);
    final b = _normalizeSearchTitle(right);
    if (a.isEmpty || b.isEmpty) return 0;
    if (a == b) return 1;

    final shorter = a.length <= b.length ? a : b;
    final longer = a.length > b.length ? a : b;
    if (longer.contains(shorter)) {
      final ratio = shorter.length / longer.length;
      if (ratio >= 0.72) return 0.95;
      if (ratio >= 0.50) return 0.82;
      return 0.55 + ratio * 0.30;
    }

    return _diceCoefficient(a, b);
  }

  double _diceCoefficient(String left, String right) {
    final leftGrams = _bigrams(left);
    final rightGrams = _bigrams(right);
    if (leftGrams.isEmpty || rightGrams.isEmpty) return 0;

    final counts = <String, int>{};
    for (final gram in leftGrams) {
      counts[gram] = (counts[gram] ?? 0) + 1;
    }

    var matches = 0;
    for (final gram in rightGrams) {
      final count = counts[gram] ?? 0;
      if (count <= 0) continue;
      matches++;
      counts[gram] = count - 1;
    }

    return (2 * matches) / (leftGrams.length + rightGrams.length);
  }

  List<String> _bigrams(String value) {
    final chars = value.runes.map(String.fromCharCode).toList();
    if (chars.length <= 1) return chars;
    return [
      for (var i = 0; i < chars.length - 1; i++) '${chars[i]}${chars[i + 1]}',
    ];
  }

  int? _yearOf(String? value) {
    if (value == null || value.length < 4) return null;
    return int.tryParse(value.substring(0, 4));
  }

  String _normalizeSearchTitle(String value) {
    return value.toLowerCase().replaceAll(
      RegExp(r'[\s\-_·・:：~～!！?？,，.。()\[\]（）【】]'),
      '',
    );
  }

  @override
  Widget build(BuildContext context) {
    final response = _response;

    return Scaffold(
      appBar: AppBar(
        title: const Text('以图搜番'),
        actions: [
          if (_queryImage != null)
            IconButton(
              onPressed: _isBusy ? null : _cropAndSearch,
              tooltip: '裁剪截图',
              icon: const Icon(Icons.crop_rounded),
            ),
          IconButton(
            onPressed: _isBusy ? null : _pickAndSearch,
            tooltip: '选择截图',
            icon: const Icon(Icons.add_photo_alternate_outlined),
          ),
        ],
      ),
      body: SafeArea(
        child: Column(
          children: [
            _SearchProgressHeader(
              phase: _viewState.phase,
              hasImage: _queryImage != null,
              hasResults: _viewState.hasResults,
            ),
            Expanded(child: _buildPhaseContent(response)),
          ],
        ),
      ),
    );
  }

  Widget _buildPhaseContent(TraceMoeSearchResponse? response) {
    switch (_viewState.phase) {
      case ImageAnimeSearchPhase.idle:
        return _buildEmptyState();
      case ImageAnimeSearchPhase.cropping:
        return _buildWorkingState(
          title: '正在裁剪截图',
          description: '尽量保留清晰画面，避开字幕、黑边和播放器控件。',
          showProgress: false,
        );
      case ImageAnimeSearchPhase.searchingTrace:
        return _buildWorkingState(
          title: '正在识别画面',
          description: '正在检索相似镜头；搜索期间会保留当前截图。',
        );
      case ImageAnimeSearchPhase.resolvingMetadata:
      case ImageAnimeSearchPhase.ready:
        if (response != null && response.results.isNotEmpty) {
          return _buildResultLayout(response);
        }
        return _buildNoResultState();
      case ImageAnimeSearchPhase.failed:
        return _buildNoResultState();
    }
  }

  Widget _buildEmptyState() {
    return Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(AppSpacing.xl),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 520),
          child: Card(
            margin: EdgeInsets.zero,
            elevation: 0,
            color: Theme.of(context).colorScheme.surfaceContainerLow,
            child: Padding(
              padding: const EdgeInsets.all(AppSpacing.xl),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(
                    Icons.image_search_rounded,
                    size: 64,
                    color: Theme.of(context).colorScheme.primary,
                  ),
                  const SizedBox(height: AppSpacing.lg),
                  Text(
                    '选择一张动画截图',
                    style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                      fontWeight: FontWeight.w900,
                    ),
                  ),
                  const SizedBox(height: AppSpacing.sm),
                  Text(
                    '优先使用清晰、无字幕、主体明确的画面。选图后可先裁剪，再从多个候选中确认。',
                    textAlign: TextAlign.center,
                    style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                      height: 1.5,
                    ),
                  ),
                  const SizedBox(height: AppSpacing.xl),
                  FilledButton.icon(
                    onPressed: _pickAndSearch,
                    icon: const Icon(Icons.add_photo_alternate_outlined),
                    label: const Text('从相册选择截图'),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildWorkingState({
    required String title,
    required String description,
    bool showProgress = true,
  }) {
    final image = _queryImage;
    return Padding(
      padding: const EdgeInsets.all(AppSpacing.xl),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (image != null) _QueryImageCard(image: image),
          const SizedBox(height: AppSpacing.xl),
          Text(
            title,
            style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w900),
          ),
          const SizedBox(height: AppSpacing.sm),
          Text(
            description,
            style: TextStyle(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
          ),
          if (showProgress) ...[
            const SizedBox(height: AppSpacing.lg),
            const LinearProgressIndicator(),
          ],
        ],
      ),
    );
  }

  Widget _buildNoResultState() {
    final image = _queryImage;
    return ListView(
      padding: const EdgeInsets.all(AppSpacing.xl),
      children: [
        if (image != null) _QueryImageCard(image: image),
        const SizedBox(height: AppSpacing.xl),
        Text(
          _errorText ?? '没有识别到匹配的番剧',
          style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
        ),
        const SizedBox(height: AppSpacing.sm),
        Text(
          '可以重试当前截图，或裁剪掉字幕、黑边和无关区域后再次识别。',
          style: TextStyle(
            color: Theme.of(context).colorScheme.onSurfaceVariant,
          ),
        ),
        const SizedBox(height: AppSpacing.lg),
        Wrap(
          spacing: AppSpacing.sm,
          runSpacing: AppSpacing.sm,
          children: [
            if (image != null)
              FilledButton.tonalIcon(
                onPressed: () => _searchImageFile(image),
                icon: const Icon(Icons.refresh_rounded),
                label: const Text('重试识别'),
              ),
            if (image != null)
              OutlinedButton.icon(
                onPressed: _cropAndSearch,
                icon: const Icon(Icons.crop_rounded),
                label: const Text('裁剪重搜'),
              ),
            OutlinedButton.icon(
              onPressed: _pickAndSearch,
              icon: const Icon(Icons.add_photo_alternate_outlined),
              label: const Text('换一张截图'),
            ),
          ],
        ),
      ],
    );
  }

  Widget _buildResultLayout(TraceMoeSearchResponse response) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final wide = constraints.maxWidth >= 760;
        if (!wide) return _buildMobileResultLayout(response);

        final content = Row(
          children: [
            SizedBox(width: 340, child: _buildResultPane(response)),
            const VerticalDivider(width: 1),
            Expanded(child: _buildDetailPane()),
          ],
        );

        return Column(
          children: [
            if (_errorText != null && !_isResolving)
              _InlineSearchError(message: _errorText!),
            if (_isResolving) const LinearProgressIndicator(minHeight: 2),
            Expanded(child: content),
            const Divider(height: 1),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 10, 16, 10),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      '已扫描 ${response.statsText}',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(fontSize: 12, color: Colors.grey[600]),
                    ),
                  ),
                  TextButton(
                    onPressed: _isBusy ? null : _pickAndSearch,
                    child: const Text('换图'),
                  ),
                  TextButton(
                    onPressed: _isBusy ? null : _cropAndSearch,
                    child: const Text('裁剪'),
                  ),
                  const SizedBox(width: 8),
                  FilledButton.icon(
                    onPressed: _isResolving ? null : _useSelectedResult,
                    icon: _isResolving
                        ? const SizedBox(
                            width: 16,
                            height: 16,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.check_rounded),
                    label: Text(
                      _isResolving ? '解析中' : _useResultActionLabel(_selected),
                    ),
                  ),
                ],
              ),
            ),
          ],
        );
      },
    );
  }

  Widget _buildMobileResultLayout(TraceMoeSearchResponse response) {
    final results = response.results;

    return Column(
      children: [
        if (_isResolving) const LinearProgressIndicator(minHeight: 2),
        if (_errorText != null && !_isResolving)
          _InlineSearchError(message: _errorText!),
        Expanded(
          child: ListView.separated(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 28),
            itemCount: results.length + 2,
            separatorBuilder: (_, index) =>
                SizedBox(height: index == 0 ? 14 : 10),
            itemBuilder: (context, index) {
              if (index == 0) {
                return _MobileSearchHeader(
                  image: _queryImage!,
                  statsText: response.statsText,
                  onPick: _isBusy ? null : _pickAndSearch,
                  onCrop: _isBusy ? null : _cropAndSearch,
                );
              }

              if (index == 1) {
                return Row(
                  children: [
                    const Expanded(
                      child: Text(
                        '识别候选',
                        style: TextStyle(
                          fontSize: 18,
                          fontWeight: FontWeight.w900,
                        ),
                      ),
                    ),
                    Text(
                      '${results.length} 个结果',
                      style: TextStyle(color: Colors.grey[600], fontSize: 12),
                    ),
                  ],
                );
              }

              final resultIndex = index - 2;
              final result = results[resultIndex];
              return _TraceResultTile(
                result: result,
                selected: resultIndex == _selectedIndex,
                isBestMatch: resultIndex == 0,
                showDetailsCue: true,
                onTap: () => _openMobileResultSheet(resultIndex),
              );
            },
          ),
        ),
      ],
    );
  }

  Future<void> _openMobileResultSheet(int index) async {
    final results = _response?.results;
    if (results == null || index < 0 || index >= results.length) return;
    final result = results[index];

    setState(() {
      _viewState = _viewState.copyWith(selectedIndex: index, errorText: null);
    });
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      showDragHandle: true,
      builder: (sheetContext) {
        return FractionallySizedBox(
          heightFactor: 0.88,
          child: Column(
            children: [
              Expanded(
                child: _buildDetailContent(
                  result,
                  padding: const EdgeInsets.fromLTRB(20, 0, 20, 18),
                  previewHeight: 178,
                  compact: true,
                ),
              ),
              const Divider(height: 1),
              SafeArea(
                top: false,
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(16, 10, 16, 12),
                  child: Row(
                    children: [
                      Expanded(
                        child: Text(
                          result.displayTitle,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(fontWeight: FontWeight.w800),
                        ),
                      ),
                      const SizedBox(width: 12),
                      FilledButton.icon(
                        onPressed: _isResolving
                            ? null
                            : () async {
                                Navigator.pop(sheetContext);
                                if (!mounted) return;
                                setState(() {
                                  _viewState = _viewState.copyWith(
                                    selectedIndex: index,
                                    errorText: null,
                                  );
                                });
                                await _useSelectedResult();
                              },
                        icon: const Icon(Icons.check_rounded),
                        label: Text(_useResultActionLabel(result)),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  Widget _buildResultPane(TraceMoeSearchResponse response) {
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(14, 14, 14, 10),
          child: _QueryImageCard(image: _queryImage!, compact: true),
        ),
        Expanded(
          child: ListView.separated(
            padding: const EdgeInsets.fromLTRB(10, 0, 10, 12),
            itemCount: response.results.length,
            separatorBuilder: (_, _) => const SizedBox(height: 8),
            itemBuilder: (context, index) {
              final result = response.results[index];
              final selected = index == _selectedIndex;
              return _TraceResultTile(
                result: result,
                selected: selected,
                isBestMatch: index == 0,
                onTap: () => setState(() {
                  _viewState = _viewState.copyWith(
                    selectedIndex: index,
                    errorText: null,
                  );
                }),
              );
            },
          ),
        ),
      ],
    );
  }

  Widget _buildDetailPane() {
    final selected = _selected;
    if (selected == null) return const SizedBox.shrink();

    return _buildDetailContent(selected);
  }

  Widget _buildDetailContent(
    TraceMoeSearchResult selected, {
    EdgeInsets padding = const EdgeInsets.all(16),
    double previewHeight = 240,
    bool compact = false,
  }) {
    final infoFuture = _infoFutureFor(selected);
    final isBestMatch =
        _response?.results.isNotEmpty == true &&
        identical(_response!.results.first, selected);
    return SingleChildScrollView(
      padding: padding,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _NetworkThumb(
            url: selected.previewImageUrl,
            width: double.infinity,
            height: previewHeight,
            borderRadius: 10,
            icon: Icons.movie_filter_outlined,
          ),
          const SizedBox(height: 6),
          Text(
            selected.filename.isEmpty
                ? selected.displayTitle
                : selected.filename,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(fontSize: 12, color: Colors.grey[600]),
          ),
          const SizedBox(height: 14),
          FutureBuilder<AnilistAnimeInfo?>(
            future: infoFuture,
            builder: (context, snapshot) {
              final info = snapshot.data;
              final loading =
                  infoFuture != null &&
                  snapshot.connectionState != ConnectionState.done;

              return Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              info?.displayTitle ?? selected.displayTitle,
                              style: TextStyle(
                                color: Theme.of(context).colorScheme.primary,
                                fontSize: compact ? 21 : 24,
                                fontWeight: FontWeight.w900,
                                height: 1.15,
                              ),
                            ),
                            if ((info?.subtitleTitle ?? '').isNotEmpty)
                              Padding(
                                padding: const EdgeInsets.only(top: 4),
                                child: Text(
                                  info!.subtitleTitle,
                                  style: TextStyle(
                                    color: Theme.of(
                                      context,
                                    ).colorScheme.primary,
                                    fontSize: 14,
                                  ),
                                ),
                              ),
                          ],
                        ),
                      ),
                      const SizedBox(width: 12),
                      _NetworkThumb(
                        url: info?.coverUrl,
                        width: compact ? 88 : 108,
                        height: compact ? 124 : 152,
                        borderRadius: 8,
                        icon: Icons.movie_outlined,
                      ),
                    ],
                  ),
                  const SizedBox(height: 14),
                  _InfoSection(
                    label: '匹配',
                    child: Wrap(
                      spacing: 8,
                      runSpacing: 8,
                      children: [
                        if (isBestMatch)
                          const _MiniInfoChip(
                            text: '最佳候选',
                            color: Colors.deepPurple,
                          ),
                        if (selected.episodeText.isNotEmpty)
                          _MiniInfoChip(
                            text: selected.episodeText,
                            color: Colors.redAccent,
                          ),
                        _MiniInfoChip(
                          text: selected.timeText,
                          color: Colors.green,
                        ),
                        _MiniInfoChip(
                          text: _confidenceText(selected),
                          color: _confidenceColor(selected.similarity),
                        ),
                      ],
                    ),
                  ),
                  if (selected.similarity < 0.75)
                    _ConfidenceWarning(result: selected)
                  else if (selected.similarity < 0.85)
                    _ConfidenceWarning(result: selected, moderate: true),
                  if (loading)
                    const Padding(
                      padding: EdgeInsets.symmetric(vertical: 18),
                      child: LinearProgressIndicator(),
                    ),
                  if (info != null) ...[
                    _InfoSection(
                      label: '资料',
                      child: Text(
                        _buildMetaLine(info),
                        style: const TextStyle(fontSize: 14, height: 1.5),
                      ),
                    ),
                    _InfoSection(
                      label: '别名',
                      child: Text(
                        _buildAliasText(info),
                        style: const TextStyle(fontSize: 14, height: 1.5),
                      ),
                    ),
                    if (info.genres.isNotEmpty)
                      _InfoSection(
                        label: '类型',
                        child: Text(info.genres.join(', ')),
                      ),
                    if (info.studios.isNotEmpty)
                      _InfoSection(
                        label: '制作',
                        child: Text(
                          info.studios.take(8).join('\n'),
                          style: TextStyle(
                            color: Theme.of(context).colorScheme.primary,
                            height: 1.5,
                          ),
                        ),
                      ),
                    if ((info.summary ?? '').isNotEmpty)
                      _InfoSection(
                        label: '简介',
                        child: Text(
                          info.summary!,
                          maxLines: 5,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(height: 1.45),
                        ),
                      ),
                  ] else if (!loading)
                    _InfoSection(
                      label: '资料',
                      child: Text(
                        selected.anilistId == null
                            ? 'trace.moe 未返回 AniList 条目 ID'
                            : '未能加载 AniList 基本资料',
                        style: TextStyle(color: Colors.grey[600]),
                      ),
                    ),
                ],
              );
            },
          ),
        ],
      ),
    );
  }

  String _buildMetaLine(AnilistAnimeInfo info) {
    final parts = <String>[];
    if ((info.format ?? '').isNotEmpty) parts.add(info.format!);
    if ((info.status ?? '').isNotEmpty) parts.add(info.status!);
    if ((info.airDate ?? '').isNotEmpty) parts.add('首播 ${info.airDate}');
    if (info.episodes != null && info.episodes! > 0) {
      parts.add('${info.episodes} 集');
    }
    if (info.score != null && info.score! > 0) {
      parts.add('评分 ${info.score!.toStringAsFixed(1)}');
    }
    return parts.isEmpty ? '暂无资料' : parts.join(' · ');
  }

  String _buildAliasText(AnilistAnimeInfo info) {
    final aliases = <String>[
      info.titleNative,
      info.titleRomaji,
      info.titleEnglish,
      ...info.synonyms.take(5),
    ].where((item) => item.trim().isNotEmpty).toSet().toList();
    return aliases.isEmpty ? '暂无别名' : aliases.join('\n');
  }

  String _useResultActionLabel(TraceMoeSearchResult? result) {
    if (result != null && result.similarity < 0.75) {
      return '仍然${widget.useResultLabel}';
    }
    return widget.useResultLabel;
  }
}

String _confidenceText(TraceMoeSearchResult result) {
  return '${result.confidenceLabel} ${result.similarityText}';
}

Color _confidenceColor(double similarity) {
  if (similarity >= 0.92) return Colors.green;
  if (similarity >= 0.85) return Colors.blue;
  if (similarity >= 0.75) return Colors.orange;
  return Colors.redAccent;
}

class _SearchProgressHeader extends StatelessWidget {
  final ImageAnimeSearchPhase phase;
  final bool hasImage;
  final bool hasResults;

  const _SearchProgressHeader({
    required this.phase,
    required this.hasImage,
    required this.hasResults,
  });

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    const steps = <({String label, IconData icon})>[
      (label: '选图', icon: Icons.add_photo_alternate_outlined),
      (label: '裁剪', icon: Icons.crop_rounded),
      (label: '识别', icon: Icons.image_search_outlined),
      (label: '确认', icon: Icons.task_alt_outlined),
    ];

    return Material(
      color: colorScheme.surfaceContainerLow,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(
          AppSpacing.lg,
          AppSpacing.sm,
          AppSpacing.lg,
          AppSpacing.md,
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  phase == ImageAnimeSearchPhase.failed
                      ? Icons.error_outline_rounded
                      : phase.isBusy
                      ? Icons.sync_rounded
                      : Icons.route_outlined,
                  size: 18,
                  color: phase == ImageAnimeSearchPhase.failed
                      ? colorScheme.error
                      : colorScheme.primary,
                ),
                const SizedBox(width: AppSpacing.sm),
                Text(
                  phase.statusLabel,
                  style: Theme.of(
                    context,
                  ).textTheme.labelLarge?.copyWith(fontWeight: FontWeight.w800),
                ),
                const Spacer(),
                Text(
                  '裁剪为可选步骤',
                  style: Theme.of(context).textTheme.labelSmall?.copyWith(
                    color: colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
            const SizedBox(height: AppSpacing.sm),
            Stack(
              children: [
                Positioned(
                  top: 15,
                  left: 36,
                  right: 36,
                  child: Container(
                    height: 2,
                    color: colorScheme.outlineVariant,
                  ),
                ),
                Row(
                  children: [
                    for (var index = 0; index < steps.length; index++)
                      Expanded(
                        child: _SearchFlowStep(
                          label: steps[index].label,
                          icon: steps[index].icon,
                          active: phase.activeStep == index,
                          completed: switch (index) {
                            0 => hasImage,
                            1 => phase.activeStep > 1 || hasResults,
                            2 => hasResults,
                            _ => false,
                          },
                          failed:
                              index == 2 &&
                              phase == ImageAnimeSearchPhase.failed,
                        ),
                      ),
                  ],
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _SearchFlowStep extends StatelessWidget {
  final String label;
  final IconData icon;
  final bool active;
  final bool completed;
  final bool failed;

  const _SearchFlowStep({
    required this.label,
    required this.icon,
    required this.active,
    required this.completed,
    required this.failed,
  });

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final color = failed
        ? colorScheme.error
        : active || completed
        ? colorScheme.primary
        : colorScheme.outline;
    return Column(
      children: [
        AnimatedContainer(
          duration: AppMotion.resolve(context, AppMotion.short),
          width: 30,
          height: 30,
          decoration: BoxDecoration(
            color: active ? color.withValues(alpha: 0.16) : colorScheme.surface,
            shape: BoxShape.circle,
            border: Border.all(color: color, width: active ? 2 : 1),
          ),
          child: Icon(
            failed
                ? Icons.close_rounded
                : completed
                ? Icons.check_rounded
                : icon,
            size: 17,
            color: color,
          ),
        ),
        const SizedBox(height: AppSpacing.xs),
        Text(
          label,
          style: Theme.of(context).textTheme.labelSmall?.copyWith(
            color: color,
            fontWeight: active ? FontWeight.w900 : FontWeight.w600,
          ),
        ),
      ],
    );
  }
}

class _InlineSearchError extends StatelessWidget {
  final String message;

  const _InlineSearchError({required this.message});

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return Material(
      color: colorScheme.errorContainer,
      child: Padding(
        padding: const EdgeInsets.symmetric(
          horizontal: AppSpacing.lg,
          vertical: AppSpacing.sm,
        ),
        child: Row(
          children: [
            Icon(
              Icons.sync_problem_rounded,
              color: colorScheme.onErrorContainer,
            ),
            const SizedBox(width: AppSpacing.sm),
            Expanded(
              child: Text(
                message,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(color: colorScheme.onErrorContainer),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _ConfidenceWarning extends StatelessWidget {
  final TraceMoeSearchResult result;
  final bool moderate;

  const _ConfidenceWarning({required this.result, this.moderate = false});

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final background = moderate
        ? colorScheme.tertiaryContainer
        : colorScheme.errorContainer;
    final foreground = moderate
        ? colorScheme.onTertiaryContainer
        : colorScheme.onErrorContainer;
    return Card(
      margin: const EdgeInsets.only(top: AppSpacing.md),
      elevation: 0,
      color: background,
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.md),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(
              moderate
                  ? Icons.info_outline_rounded
                  : Icons.warning_amber_rounded,
              color: foreground,
            ),
            const SizedBox(width: AppSpacing.sm),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    moderate ? '匹配仍需核对' : '匹配置信度偏低',
                    style: TextStyle(
                      color: foreground,
                      fontWeight: FontWeight.w900,
                    ),
                  ),
                  const SizedBox(height: AppSpacing.xs),
                  Text(
                    moderate
                        ? '当前相似度为 ${result.similarityText}，建议核对集数和画面时间。'
                        : '当前相似度仅 ${result.similarityText}。建议先查看其他候选，或裁剪画面后重搜；仍可直接继续。',
                    style: TextStyle(color: foreground),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _BangumiCandidate {
  final BangumiSearchResult result;
  final double score;

  const _BangumiCandidate({required this.result, required this.score});
}

class _QueryImageCard extends StatelessWidget {
  final File image;
  final bool compact;

  const _QueryImageCard({required this.image, this.compact = false});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: compact ? EdgeInsets.zero : const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: compact
            ? Colors.transparent
            : Colors.grey.withValues(alpha: 0.06),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          ClipRRect(
            borderRadius: BorderRadius.circular(8),
            child: Image.file(
              image,
              width: compact ? 72 : 108,
              height: compact ? 48 : 72,
              fit: BoxFit.cover,
              cacheWidth: compact ? 240 : 360,
            ),
          ),
          const SizedBox(width: 12),
          const Expanded(
            child: Text(
              '你的搜索截图',
              style: TextStyle(fontWeight: FontWeight.w800, fontSize: 15),
            ),
          ),
        ],
      ),
    );
  }
}

class _MobileSearchHeader extends StatelessWidget {
  final File image;
  final String statsText;
  final VoidCallback? onPick;
  final VoidCallback? onCrop;

  const _MobileSearchHeader({
    required this.image,
    required this.statsText,
    required this.onPick,
    required this.onCrop,
  });

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;

    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: colorScheme.surfaceContainerHighest.withValues(alpha: 0.72),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        children: [
          Row(
            children: [
              ClipRRect(
                borderRadius: BorderRadius.circular(10),
                child: Image.file(
                  image,
                  width: 72,
                  height: 72,
                  fit: BoxFit.cover,
                  cacheWidth: 240,
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      '你的搜索截图',
                      style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w900,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      '已扫描 $statsText',
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: colorScheme.onSurfaceVariant,
                        fontSize: 12,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: onPick,
                  icon: const Icon(Icons.add_photo_alternate_outlined),
                  label: const Text('换图'),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: onCrop,
                  icon: const Icon(Icons.crop_rounded),
                  label: const Text('裁剪重搜'),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _TraceResultTile extends StatelessWidget {
  final TraceMoeSearchResult result;
  final bool selected;
  final bool isBestMatch;
  final bool showDetailsCue;
  final VoidCallback onTap;

  const _TraceResultTile({
    required this.result,
    required this.selected,
    required this.onTap,
    this.isBestMatch = false,
    this.showDetailsCue = false,
  });

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;

    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(10),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 160),
        padding: const EdgeInsets.all(8),
        decoration: BoxDecoration(
          color: selected
              ? colorScheme.primary.withValues(alpha: 0.08)
              : isBestMatch
              ? colorScheme.secondaryContainer.withValues(alpha: 0.42)
              : Colors.grey.withValues(alpha: 0.05),
          borderRadius: BorderRadius.circular(10),
          border: Border.all(
            color: selected
                ? colorScheme.primary
                : isBestMatch
                ? colorScheme.secondary.withValues(alpha: 0.45)
                : Colors.transparent,
            width: selected ? 1.2 : 1,
          ),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _NetworkThumb(url: result.previewImageUrl, width: 96, height: 58),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (isBestMatch) ...[
                    const _MiniInfoChip(text: '最佳候选', color: Colors.deepPurple),
                    const SizedBox(height: 6),
                  ],
                  Text(
                    result.displayTitle,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontWeight: FontWeight.w800,
                      fontSize: 13,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Wrap(
                    spacing: 6,
                    runSpacing: 4,
                    children: [
                      if (result.episodeText.isNotEmpty)
                        _MiniInfoChip(
                          text: result.episodeText,
                          color: Colors.redAccent,
                        ),
                      _MiniInfoChip(text: result.timeText, color: Colors.green),
                      _MiniInfoChip(
                        text: _confidenceText(result),
                        color: _confidenceColor(result.similarity),
                      ),
                    ],
                  ),
                ],
              ),
            ),
            if (showDetailsCue) ...[
              const SizedBox(width: 6),
              Icon(
                Icons.expand_more_rounded,
                color: colorScheme.onSurfaceVariant,
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _NetworkThumb extends StatelessWidget {
  final String? url;
  final double width;
  final double height;
  final double borderRadius;
  final IconData icon;

  const _NetworkThumb({
    required this.url,
    required this.width,
    required this.height,
    this.borderRadius = 6,
    this.icon = Icons.image_search_outlined,
  });

  @override
  Widget build(BuildContext context) {
    final placeholder = Container(
      width: width,
      height: height,
      color: Colors.grey[200],
      child: Icon(icon, color: Colors.grey[500]),
    );

    return ClipRRect(
      borderRadius: BorderRadius.circular(borderRadius),
      child: SizedBox(
        width: width,
        height: height,
        child: url == null || url!.isEmpty
            ? placeholder
            : Image.network(
                url!,
                fit: BoxFit.cover,
                errorBuilder: (_, _, _) => placeholder,
              ),
      ),
    );
  }
}

class _MiniInfoChip extends StatelessWidget {
  final String text;
  final Color color;

  const _MiniInfoChip({required this.text, required this.color});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.10),
        borderRadius: BorderRadius.circular(7),
        border: Border.all(color: color.withValues(alpha: 0.25)),
      ),
      child: Text(
        text,
        style: TextStyle(
          color: color,
          fontSize: 12,
          fontWeight: FontWeight.w700,
        ),
      ),
    );
  }
}

class _InfoSection extends StatelessWidget {
  final String label;
  final Widget child;

  const _InfoSection({required this.label, required this.child});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 12),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 72,
            child: Text(
              label,
              style: TextStyle(
                color: Colors.grey[600],
                fontWeight: FontWeight.w700,
              ),
            ),
          ),
          Expanded(child: child),
        ],
      ),
    );
  }
}
