import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../models/anime_analysis_record.dart';
import '../services/anime_analysis_service.dart';
import '../services/cloud_account_service.dart';
import '../ui/pages/cloud_account_page.dart';
import '../utils/api_config.dart';

class AnimeStyleAnalysisPage extends StatefulWidget {
  const AnimeStyleAnalysisPage({super.key});

  @override
  State<AnimeStyleAnalysisPage> createState() => _AnimeStyleAnalysisPageState();
}

class _AnimeStyleAnalysisPageState extends State<AnimeStyleAnalysisPage> {
  CloudSession? _session;
  Map<String, dynamic>? _stats;
  List<AnimeAnalysisRecord> _records = const [];
  bool _loading = true;
  bool _submitting = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _loadData();
  }

  Future<void> _loadData() async {
    setState(() {
      _loading = true;
      _error = null;
    });

    try {
      final session = await CloudAccountService.loadSession();
      final stats = await AnimeAnalysisService.buildStatsPayload();
      final records = await AnimeAnalysisService.loadLocalRecords(
        userId: session?.userId,
      );
      if (!mounted) return;
      setState(() {
        _session = session;
        _stats = stats;
        _records = records;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.toString();
        _loading = false;
      });
    }
  }

  Future<void> _openAccountPage() async {
    await Navigator.push(
      context,
      MaterialPageRoute(builder: (context) => const CloudAccountPage()),
    );
    if (mounted) await _loadData();
  }

  Future<void> _requestAnalysis() async {
    final session = _session;
    if (session == null || _submitting) return;

    final cooldown = AnimeAnalysisService.todayCooldownRemaining(_latestRecord);
    if (cooldown != null) {
      _showSnack(
        '今天已经分析过啦，约 ${AnimeAnalysisService.formatRemaining(cooldown)} 后再来。',
      );
      return;
    }

    final shouldContinue = await _confirmUploadStats();
    if (shouldContinue != true) return;

    setState(() => _submitting = true);
    try {
      final record = await AnimeAnalysisService.requestAnalysis(session);
      final records = await AnimeAnalysisService.loadLocalRecords(
        userId: session.userId,
      );
      if (!mounted) return;
      setState(() {
        _records = records.isEmpty ? [record] : records;
      });
      _showSnack('分析完成，新的看番风格报告已经保存。');
    } catch (e) {
      _showSnack(e.toString());
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  Future<Map<String, dynamic>> _currentStats() async {
    final stats = _stats;
    if (stats != null) return stats;

    final freshStats = await AnimeAnalysisService.buildStatsPayload();
    if (mounted) setState(() => _stats = freshStats);
    return freshStats;
  }

  Future<void> _showPromptDialog() async {
    if (!_hasAnimeData) {
      _showSnack('还没有可分析的追番数据');
      return;
    }

    final stats = await _currentStats();
    final prompt = AnimeAnalysisService.buildPrompt(stats);
    if (!mounted) return;

    await showDialog<void>(
      context: context,
      builder: (context) {
        return AlertDialog(
          title: const Text('分析提示词'),
          content: SizedBox(
            width: double.maxFinite,
            child: SingleChildScrollView(child: SelectableText(prompt)),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('关闭'),
            ),
            FilledButton.icon(
              onPressed: () {
                Clipboard.setData(ClipboardData(text: prompt));
                Navigator.pop(context);
                _showSnack('提示词已复制');
              },
              icon: const Icon(Icons.copy),
              label: const Text('复制'),
            ),
          ],
        );
      },
    );
  }

  Future<void> _showCustomModelDialog() async {
    if (!_hasAnimeData) {
      _showSnack('还没有可分析的追番数据');
      return;
    }

    final endpointController = TextEditingController(
      text: 'https://api.deepseek.com',
    );
    final modelController = TextEditingController(text: 'deepseek-v4-flash');
    final apiKeyController = TextEditingController();
    final formKey = GlobalKey<FormState>();
    var dialogBusy = false;

    try {
      await showDialog<void>(
        context: context,
        barrierDismissible: !dialogBusy,
        builder: (dialogContext) {
          return StatefulBuilder(
            builder: (dialogContext, setDialogState) {
              Future<void> submit() async {
                if (dialogBusy) return;
                if (formKey.currentState?.validate() != true) return;

                setDialogState(() => dialogBusy = true);
                var completed = false;
                try {
                  final stats = await _currentStats();
                  final record =
                      await AnimeAnalysisService.requestCustomModelAnalysis(
                        stats: stats,
                        endpoint: endpointController.text,
                        model: modelController.text,
                        apiKey: apiKeyController.text,
                        userId: _session?.userId ?? 0,
                        username: _session?.username ?? '本地用户',
                      );
                  final records = await AnimeAnalysisService.loadLocalRecords(
                    userId: _session?.userId,
                  );
                  if (!mounted) return;
                  setState(() {
                    _records = records.isEmpty ? [record] : records;
                  });
                  apiKeyController.clear();
                  completed = true;
                  if (!dialogContext.mounted) return;
                  Navigator.pop(dialogContext);
                  _showSnack('自定义模型分析已保存');
                } catch (e) {
                  if (mounted) _showSnack(e.toString());
                } finally {
                  if (!completed) setDialogState(() => dialogBusy = false);
                }
              }

              return AlertDialog(
                title: const Text('自定义模型'),
                content: Form(
                  key: formKey,
                  child: SingleChildScrollView(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        TextFormField(
                          controller: endpointController,
                          decoration: const InputDecoration(
                            labelText: 'OpenAI 兼容 Base URL',
                            hintText: 'https://api.example.com/v1',
                          ),
                          validator: (value) =>
                              value == null || value.trim().isEmpty
                              ? '请填写接口地址'
                              : null,
                        ),
                        const SizedBox(height: 12),
                        TextFormField(
                          controller: modelController,
                          decoration: const InputDecoration(labelText: '模型名'),
                          validator: (value) =>
                              value == null || value.trim().isEmpty
                              ? '请填写模型名'
                              : null,
                        ),
                        const SizedBox(height: 12),
                        TextFormField(
                          controller: apiKeyController,
                          decoration: const InputDecoration(
                            labelText: 'API Key',
                            helperText: '仅用于本次直连请求，不保存，不发送到追番喵云端',
                          ),
                          obscureText: true,
                        ),
                      ],
                    ),
                  ),
                ),
                actions: [
                  TextButton(
                    onPressed: dialogBusy
                        ? null
                        : () => Navigator.pop(dialogContext),
                    child: const Text('取消'),
                  ),
                  FilledButton.icon(
                    onPressed: dialogBusy ? null : submit,
                    icon: dialogBusy
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.auto_awesome),
                    label: Text(dialogBusy ? '分析中...' : '开始分析'),
                  ),
                ],
              );
            },
          );
        },
      );
    } finally {
      endpointController.dispose();
      modelController.dispose();
      apiKeyController.dispose();
    }
  }

  Future<bool?> _confirmUploadStats() {
    return showDialog<bool>(
      context: context,
      builder: (context) {
        return AlertDialog(
          title: const Text('生成今日分析'),
          content: const Text(
            '会根据你的追番统计生成一份可爱的看番风格报告。测试阶段每个账号每天只能生成一次，今天生成后明天再来。',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('取消'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('开始分析'),
            ),
          ],
        );
      },
    );
  }

  AnimeAnalysisRecord? get _latestRecord =>
      _records.isEmpty ? null : _records.first;

  bool get _hasAnimeData => _summaryInt('total_entries') > 0;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;

    return Scaffold(
      appBar: AppBar(
        title: const Text('AI 看番风格分析'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: '刷新',
            onPressed: _loading || _submitting ? null : _loadData,
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : RefreshIndicator(
              onRefresh: _loadData,
              child: ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  if (_error != null)
                    _buildMessageCard(
                      icon: Icons.error_outline,
                      color: cs.error,
                      title: '加载失败',
                      message: _error!,
                    ),
                  _buildIntroCard(cs),
                  const SizedBox(height: 12),
                  _buildLocalAnalysisCard(cs),
                  const SizedBox(height: 12),
                  if (!ApiConfig.hasCloudBase) ...[
                    _buildMessageCard(
                      icon: Icons.cloud_off_outlined,
                      color: cs.error,
                      title: '云端不可用',
                      message: '当前构建未配置 CLOUD_API_BASE，暂时无法使用云端 AI 分析。',
                    ),
                    const SizedBox(height: 12),
                  ],
                  if (_session == null) ...[
                    _buildLoginCard(cs),
                    const SizedBox(height: 12),
                  ] else ...[
                    _buildActionCard(cs),
                    const SizedBox(height: 12),
                  ],
                  _buildStatsPreview(cs),
                  const SizedBox(height: 16),
                  _buildHistoryHeader(),
                  const SizedBox(height: 8),
                  if (_records.isEmpty)
                    _buildEmptyHistory()
                  else
                    ..._records.map(_buildRecordCard),
                ],
              ),
            ),
    );
  }

  Widget _buildIntroCard(ColorScheme cs) {
    return Card(
      elevation: 0,
      color: cs.primaryContainer.withValues(alpha: 0.65),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(Icons.auto_awesome, color: cs.primary, size: 30),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    '生成一份软乎乎的看番风格报告',
                    style: Theme.of(context).textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    '测试阶段仅限云账号用户使用，每个账号每天最多生成一次。',
                    style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                      color: cs.onPrimaryContainer.withValues(alpha: 0.8),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildLoginCard(ColorScheme cs) {
    return Card(
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(18),
        side: BorderSide(color: cs.outlineVariant),
      ),
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              '需要先登录云账号',
              style: Theme.of(
                context,
              ).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 8),
            Text(
              '测试阶段需要登录后使用，每个账号每天只能生成一次。',
              style: TextStyle(color: cs.onSurfaceVariant),
            ),
            const SizedBox(height: 16),
            FilledButton.icon(
              onPressed: _openAccountPage,
              icon: const Icon(Icons.login),
              label: const Text('去登录 / 注册'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildLocalAnalysisCard(ColorScheme cs) {
    return Card(
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(18),
        side: BorderSide(color: cs.outlineVariant),
      ),
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.lock_outline, color: cs.primary),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    '本地提示词 / 自定义模型',
                    style: Theme.of(context).textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 10),
            Text(
              '可以只复制提示词到其他 AI 官网或 APP，也可以用自己的 OpenAI 兼容接口直连分析。自定义 API Key 不会保存，也不会发送到追番喵云端。',
              style: TextStyle(color: cs.onSurfaceVariant),
            ),
            const SizedBox(height: 16),
            Wrap(
              spacing: 10,
              runSpacing: 10,
              children: [
                OutlinedButton.icon(
                  onPressed: _hasAnimeData ? _showPromptDialog : null,
                  icon: const Icon(Icons.copy_all_outlined),
                  label: const Text('复制提示词'),
                ),
                OutlinedButton.icon(
                  onPressed: _hasAnimeData ? _showCustomModelDialog : null,
                  icon: const Icon(Icons.hub_outlined),
                  label: const Text('自定义模型'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildActionCard(ColorScheme cs) {
    final cooldown = AnimeAnalysisService.todayCooldownRemaining(_latestRecord);
    final disabledReason = !_hasAnimeData
        ? '还没有可分析的追番数据'
        : cooldown != null
        ? '今天已经生成过，约 ${AnimeAnalysisService.formatRemaining(cooldown)} 后可再次使用'
        : null;

    return Card(
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(18),
        side: BorderSide(color: cs.outlineVariant),
      ),
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.verified_user_outlined, color: cs.primary),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    '已登录：${_session!.username}',
                    style: Theme.of(context).textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            if (disabledReason != null)
              Text(disabledReason, style: TextStyle(color: cs.onSurfaceVariant))
            else
              Text(
                '服务器会保存本次调用记录，并返回分析结果给 APP 本地留档。',
                style: TextStyle(color: cs.onSurfaceVariant),
              ),
            const SizedBox(height: 16),
            FilledButton.icon(
              onPressed:
                  disabledReason == null &&
                      ApiConfig.hasCloudBase &&
                      !_submitting
                  ? _requestAnalysis
                  : null,
              icon: _submitting
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.auto_awesome),
              label: Text(_submitting ? '分析中...' : '生成今日分析'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildStatsPreview(ColorScheme cs) {
    return Card(
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(18),
        side: BorderSide(color: cs.outlineVariant),
      ),
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              '本次统计摘要',
              style: Theme.of(
                context,
              ).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 14),
            Wrap(
              spacing: 10,
              runSpacing: 10,
              children: [
                _buildMetricChip('总收录', _summaryInt('total_entries')),
                _buildMetricChip('看完', _summaryInt('completed_entries')),
                _buildMetricChip('已评分', _summaryInt('rated_entries')),
                _buildMetricChip('已看集数', _summaryInt('watched_episodes')),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildMetricChip(String label, int value) {
    return Chip(
      avatar: const Icon(Icons.insights, size: 16),
      label: Text('$label $value'),
      visualDensity: VisualDensity.compact,
    );
  }

  Widget _buildHistoryHeader() {
    return Text(
      '本地调用记录',
      style: Theme.of(
        context,
      ).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700),
    );
  }

  Widget _buildEmptyHistory() {
    return const Padding(
      padding: EdgeInsets.symmetric(vertical: 28),
      child: Center(child: Text('还没有分析记录')),
    );
  }

  Widget _buildRecordCard(AnimeAnalysisRecord record) {
    final cs = Theme.of(context).colorScheme;
    return Card(
      elevation: 0,
      margin: const EdgeInsets.only(bottom: 12),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(18),
        side: BorderSide(color: cs.outlineVariant),
      ),
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.history, size: 18, color: cs.primary),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    _formatDate(record.createdAt),
                    style: Theme.of(context).textTheme.titleSmall?.copyWith(
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
                Text(
                  record.model,
                  style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
                ),
                IconButton(
                  tooltip: '复制分析',
                  onPressed: () {
                    Clipboard.setData(ClipboardData(text: record.analysis));
                    _showSnack('分析内容已复制');
                  },
                  icon: const Icon(Icons.copy_rounded, size: 18),
                  constraints: const BoxConstraints.tightFor(
                    width: 36,
                    height: 36,
                  ),
                  padding: EdgeInsets.zero,
                ),
              ],
            ),
            const SizedBox(height: 12),
            _buildAnalysisContent(record.analysis),
            if (record.serverRecordId != null) ...[
              const SizedBox(height: 10),
              Text(
                '服务端记录 #${record.serverRecordId}',
                style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildAnalysisContent(String analysis) {
    final lines = analysis.split(RegExp(r'\r?\n'));
    return SelectionArea(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          for (final line in lines) _buildAnalysisLine(line),
        ],
      ),
    );
  }

  Widget _buildAnalysisLine(String line) {
    final cs = Theme.of(context).colorScheme;
    final textTheme = Theme.of(context).textTheme;
    final trimmedRight = line.trimRight();
    final trimmed = trimmedRight.trim();

    if (trimmed.isEmpty) {
      return const SizedBox(height: 8);
    }
    if (RegExp(r'^-{3,}$').hasMatch(trimmed)) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 10),
        child: Divider(height: 1, color: cs.outlineVariant),
      );
    }

    final headingMatch = RegExp(r'^(#{1,6})\s+(.+)$').firstMatch(trimmed);
    if (headingMatch != null) {
      final level = headingMatch.group(1)!.length;
      final text = headingMatch.group(2)!;
      final style = (level <= 2 ? textTheme.titleMedium : textTheme.titleSmall)
          ?.copyWith(fontWeight: FontWeight.w800, color: cs.onSurface);
      return Padding(
        padding: const EdgeInsets.only(top: 12, bottom: 6),
        child: Text.rich(_boldSpan(text, style)),
      );
    }

    final bulletMatch = RegExp(r'^([-*•])\s+(.+)$').firstMatch(trimmed);
    final numberMatch = RegExp(r'^(\d+[.)])\s+(.+)$').firstMatch(trimmed);
    if (bulletMatch != null || numberMatch != null) {
      final marker = bulletMatch?.group(1) ?? numberMatch?.group(1) ?? '';
      final text = bulletMatch?.group(2) ?? numberMatch?.group(2) ?? trimmed;
      final style = textTheme.bodyMedium?.copyWith(
        color: cs.onSurface.withValues(alpha: 0.9),
        height: 1.55,
      );
      return Padding(
        padding: const EdgeInsets.only(bottom: 6),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(
              width: 28,
              child: Text(
                marker == '-' || marker == '*' ? '•' : marker,
                style: style?.copyWith(color: cs.primary),
              ),
            ),
            Expanded(child: Text.rich(_boldSpan(text, style))),
          ],
        ),
      );
    }

    final style = textTheme.bodyMedium?.copyWith(
      color: cs.onSurface.withValues(alpha: 0.9),
      height: 1.6,
    );
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Text.rich(_boldSpan(trimmedRight, style)),
    );
  }

  TextSpan _boldSpan(String text, TextStyle? style) {
    final spans = <InlineSpan>[];
    final pattern = RegExp(r'\*\*(.+?)\*\*');
    var start = 0;

    for (final match in pattern.allMatches(text)) {
      if (match.start > start) {
        spans.add(TextSpan(text: text.substring(start, match.start)));
      }
      spans.add(
        TextSpan(
          text: match.group(1),
          style: style?.copyWith(fontWeight: FontWeight.w800),
        ),
      );
      start = match.end;
    }

    if (start < text.length) {
      spans.add(TextSpan(text: text.substring(start)));
    }

    return TextSpan(style: style, children: spans);
  }

  Widget _buildMessageCard({
    required IconData icon,
    required Color color,
    required String title,
    required String message,
  }) {
    return Card(
      elevation: 0,
      margin: const EdgeInsets.only(bottom: 12),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(icon, color: color),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: const TextStyle(fontWeight: FontWeight.w700),
                  ),
                  const SizedBox(height: 4),
                  Text(message),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  int _summaryInt(String key) {
    final summary = _stats?['summary'];
    if (summary is! Map<String, dynamic>) return 0;
    final value = summary[key];
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value?.toString() ?? '') ?? 0;
  }

  String _formatDate(String raw) {
    final date = DateTime.tryParse(raw)?.toLocal();
    if (date == null) return raw;
    return '${date.year}-${date.month.toString().padLeft(2, '0')}-'
        '${date.day.toString().padLeft(2, '0')} '
        '${date.hour.toString().padLeft(2, '0')}:'
        '${date.minute.toString().padLeft(2, '0')}';
  }

  void _showSnack(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(message)));
  }
}
