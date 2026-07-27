import 'package:flutter/material.dart';

import '../repositories/anime_repository.dart';
import '../services/service_locator.dart';
import '../ui/pages/add_anime_page.dart';
import '../utils/notification_service.dart';

class ReminderManagementPage extends StatefulWidget {
  const ReminderManagementPage({super.key});

  @override
  State<ReminderManagementPage> createState() => _ReminderManagementPageState();
}

class _ReminderManagementPageState extends State<ReminderManagementPage> {
  final AnimeRepository _animeRepo = getIt<AnimeRepository>();
  final NotificationService _notificationService = NotificationService();

  List<Map<String, dynamic>> _reminderAnimes = [];
  ReminderServiceStatus? _serviceStatus;
  bool _isLoading = true;
  bool _isActionRunning = false;
  String? _loadError;

  @override
  void initState() {
    super.initState();
    _loadReminders();
  }

  Future<void> _loadReminders({bool showLoading = true}) async {
    if (showLoading && mounted) setState(() => _isLoading = true);

    final remindersFuture = _animeRepo.getAnimesWithReminders();
    final statusFuture = _notificationService.getStatus();
    List<Map<String, dynamic>>? reminders;
    ReminderServiceStatus? status;
    String? error;

    try {
      reminders = await remindersFuture;
    } catch (caught) {
      error = '读取提醒列表失败：${_notificationService.describeError(caught)}';
    }
    try {
      status = await statusFuture;
    } catch (caught) {
      error ??= '读取通知状态失败：${_notificationService.describeError(caught)}';
    }

    if (!mounted) return;
    setState(() {
      if (reminders != null) _reminderAnimes = reminders;
      if (status != null) _serviceStatus = status;
      _loadError = error;
      _isLoading = false;
    });
  }

  String _formatDay(int? day) {
    const days = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
    if (day != null && day >= 1 && day <= 7) return days[day - 1];
    return '未知';
  }

  String? _nextOccurrenceLabel(Map<String, dynamic> anime) {
    final day = anime['reminder_day'];
    final time = NotificationService.parseReminderTime(
      anime['reminder_time']?.toString(),
    );
    if (day is! int || time == null) return null;
    try {
      final next = _notificationService.nextInstanceOfDayAndTime(
        day: day,
        time: time,
      );
      final month = next.month.toString().padLeft(2, '0');
      final date = next.day.toString().padLeft(2, '0');
      final hour = next.hour.toString().padLeft(2, '0');
      final minute = next.minute.toString().padLeft(2, '0');
      return '下次：$month-$date $hour:$minute';
    } catch (_) {
      return null;
    }
  }

  Future<ReminderSyncResult> _syncStoredReminders() async {
    final reminders = await _animeRepo.getAnimesWithReminders();
    return _notificationService.synchronizeStoredReminders(reminders);
  }

  String _syncSummary(ReminderSyncResult result) {
    final summary =
        '已同步 ${result.scheduledReminderCount} 条提醒，创建 ${result.systemTaskCount} 个系统任务';
    if (result.isSuccess) return summary;
    return '$summary；${result.failures.join('；')}';
  }

  Future<void> _runAction(Future<String> Function() action) async {
    if (_isActionRunning) return;
    setState(() => _isActionRunning = true);
    var isError = false;
    late String message;
    try {
      message = await action();
    } catch (caught) {
      isError = true;
      message = _notificationService.describeError(caught);
    }

    if (!mounted) return;
    await _loadReminders(showLoading: false);
    if (!mounted) return;
    setState(() => _isActionRunning = false);
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
        backgroundColor: isError ? Theme.of(context).colorScheme.error : null,
      ),
    );
  }

  Future<void> _requestPermissionAndSync() => _runAction(() async {
    final permission = await _notificationService.requestPermissions();
    if (!permission.granted) {
      throw ReminderSchedulingException(permission.message);
    }
    return _syncSummary(await _syncStoredReminders());
  });

  Future<void> _resynchronize() => _runAction(() async {
    final result = await _syncStoredReminders();
    if (!result.isSuccess) {
      throw ReminderSchedulingException(_syncSummary(result));
    }
    return _syncSummary(result);
  });

  Future<void> _sendTestNotification() => _runAction(() async {
    final permission = await _notificationService.requestPermissions();
    if (!permission.granted) {
      throw ReminderSchedulingException(permission.message);
    }
    await _notificationService.showTestNotification();
    return '测试通知已发送，请检查系统通知中心';
  });

  Future<void> _cancelReminder(Map<String, dynamic> anime) async {
    final bool? confirm = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('取消提醒'),
        content: Text('确定取消《${anime['title']}》的追番提醒吗？'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('返回'),
          ),
          FilledButton.tonal(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('取消提醒'),
          ),
        ],
      ),
    );
    if (confirm != true) return;

    await _runAction(() async {
      final id = anime['id'];
      if (id is! int) {
        throw const ReminderSchedulingException('提醒记录 ID 无效');
      }
      await _animeRepo.updateAnime({
        'id': id,
        'reminder_day': null,
        'reminder_time': null,
      });
      try {
        await _notificationService.cancelNotification(id);
      } catch (caught) {
        return '数据库提醒已关闭，但系统任务清理失败：${_notificationService.describeError(caught)}。请点击“重新同步全部”。';
      }
      return '已取消《${anime['title']}》的提醒';
    });
  }

  Future<void> _editReminder(Map<String, dynamic> anime) async {
    final result = await Navigator.push(
      context,
      MaterialPageRoute(builder: (_) => AddAnimePage(existingAnime: anime)),
    );
    if (result == true) await _loadReminders(showLoading: false);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('追番提醒管理'),
        actions: [
          IconButton(
            tooltip: '刷新状态',
            onPressed: _isActionRunning ? null : _loadReminders,
            icon: const Icon(Icons.refresh_rounded),
          ),
        ],
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : RefreshIndicator(
              onRefresh: () => _loadReminders(showLoading: false),
              child: ListView(
                physics: const AlwaysScrollableScrollPhysics(),
                padding: const EdgeInsets.fromLTRB(16, 12, 16, 32),
                children: [
                  Center(
                    child: ConstrainedBox(
                      constraints: const BoxConstraints(maxWidth: 860),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          _buildServiceStatusCard(),
                          if (_loadError != null) ...[
                            const SizedBox(height: 12),
                            _buildLoadError(),
                          ],
                          const SizedBox(height: 22),
                          _buildListHeader(),
                          const SizedBox(height: 10),
                          if (_reminderAnimes.isEmpty)
                            _buildEmptyState()
                          else
                            for (final anime in _reminderAnimes)
                              _buildReminderTile(anime),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ),
    );
  }

  Widget _buildServiceStatusCard() {
    final colorScheme = Theme.of(context).colorScheme;
    final status = _serviceStatus;
    final availability = status?.availability ?? ReminderAvailability.error;
    final (icon, color, title) = switch (availability) {
      ReminderAvailability.ready => (
        Icons.notifications_active_rounded,
        colorScheme.primary,
        '通知服务已就绪',
      ),
      ReminderAvailability.permissionDenied => (
        Icons.notifications_off_rounded,
        colorScheme.error,
        '需要通知权限',
      ),
      ReminderAvailability.unsupported => (
        Icons.devices_other_rounded,
        colorScheme.outline,
        '当前平台暂不支持',
      ),
      ReminderAvailability.error => (
        Icons.error_outline_rounded,
        colorScheme.error,
        '通知服务异常',
      ),
    };

    return Card(
      margin: EdgeInsets.zero,
      color: color.withValues(alpha: 0.08),
      elevation: 0,
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Container(
                  width: 42,
                  height: 42,
                  decoration: BoxDecoration(
                    color: color.withValues(alpha: 0.14),
                    borderRadius: BorderRadius.circular(13),
                  ),
                  child: Icon(icon, color: color),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        title,
                        style: Theme.of(context).textTheme.titleMedium
                            ?.copyWith(fontWeight: FontWeight.w700),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        status?.message ?? '暂时无法读取系统通知状态',
                        style: TextStyle(color: colorScheme.onSurfaceVariant),
                      ),
                    ],
                  ),
                ),
                if (_isActionRunning)
                  const Padding(
                    padding: EdgeInsets.all(6),
                    child: SizedBox.square(
                      dimension: 20,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    ),
                  ),
              ],
            ),
            const SizedBox(height: 14),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                _buildInfoChip(
                  Icons.public_rounded,
                  '时区 ${status?.timeZoneName ?? '未知'}',
                ),
                _buildInfoChip(
                  Icons.schedule_rounded,
                  '待执行任务 ${status?.pendingTaskCount ?? 0}',
                ),
                _buildInfoChip(
                  Icons.bookmark_added_outlined,
                  '已配置 ${_reminderAnimes.length} 条',
                ),
              ],
            ),
            const SizedBox(height: 16),
            Wrap(
              spacing: 10,
              runSpacing: 10,
              children: [
                if (availability != ReminderAvailability.ready)
                  FilledButton.icon(
                    onPressed: _isActionRunning
                        ? null
                        : _requestPermissionAndSync,
                    icon: const Icon(Icons.lock_open_rounded),
                    label: const Text('授予权限并同步'),
                  ),
                OutlinedButton.icon(
                  onPressed: _isActionRunning ? null : _resynchronize,
                  icon: const Icon(Icons.sync_rounded),
                  label: const Text('重新同步全部'),
                ),
                TextButton.icon(
                  onPressed: _isActionRunning ? null : _sendTestNotification,
                  icon: const Icon(Icons.notification_add_outlined),
                  label: const Text('发送测试通知'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildInfoChip(IconData icon, String label) {
    return Chip(
      avatar: Icon(icon, size: 16),
      label: Text(label),
      visualDensity: VisualDensity.compact,
    );
  }

  Widget _buildLoadError() {
    final colorScheme = Theme.of(context).colorScheme;
    return Material(
      color: colorScheme.errorContainer,
      borderRadius: BorderRadius.circular(12),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Row(
          children: [
            Icon(Icons.error_outline, color: colorScheme.onErrorContainer),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                _loadError!,
                style: TextStyle(color: colorScheme.onErrorContainer),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildListHeader() {
    return Row(
      children: [
        Expanded(
          child: Text(
            '每周提醒',
            style: Theme.of(
              context,
            ).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700),
          ),
        ),
        Text(
          '${_reminderAnimes.length} 条',
          style: TextStyle(color: Theme.of(context).colorScheme.outline),
        ),
      ],
    );
  }

  Widget _buildEmptyState() {
    final colorScheme = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 48),
      decoration: BoxDecoration(
        color: colorScheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(18),
      ),
      child: Column(
        children: [
          Icon(
            Icons.notifications_none_rounded,
            size: 54,
            color: colorScheme.outlineVariant,
          ),
          const SizedBox(height: 14),
          Text(
            '还没有设置每周提醒',
            style: Theme.of(
              context,
            ).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600),
          ),
          const SizedBox(height: 6),
          Text(
            '在番剧或小说编辑页开启提醒，并选择星期与时间。',
            textAlign: TextAlign.center,
            style: TextStyle(color: colorScheme.onSurfaceVariant),
          ),
        ],
      ),
    );
  }

  Widget _buildReminderTile(Map<String, dynamic> anime) {
    final colorScheme = Theme.of(context).colorScheme;
    final title = (anime['title'] ?? '未命名作品').toString();
    final nextLabel = _nextOccurrenceLabel(anime);
    final schedule =
        '每周 ${_formatDay(anime['reminder_day'] as int?)} ${anime['reminder_time'] ?? '--:--'}';

    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      elevation: 0,
      color: colorScheme.surfaceContainerLow,
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: () => _editReminder(anime),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(12, 12, 6, 12),
          child: Row(
            children: [
              Container(
                width: 46,
                height: 62,
                decoration: BoxDecoration(
                  color: colorScheme.primaryContainer,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Icon(
                  anime['subject_type'] == 'book'
                      ? Icons.menu_book_rounded
                      : Icons.movie_creation_outlined,
                  color: colorScheme.onPrimaryContainer,
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontWeight: FontWeight.w700),
                    ),
                    const SizedBox(height: 6),
                    Row(
                      children: [
                        Icon(
                          Icons.repeat_rounded,
                          size: 16,
                          color: colorScheme.primary,
                        ),
                        const SizedBox(width: 5),
                        Flexible(
                          child: Text(
                            schedule,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              color: colorScheme.primary,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ),
                      ],
                    ),
                    if (nextLabel != null) ...[
                      const SizedBox(height: 3),
                      Text(
                        nextLabel,
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: colorScheme.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ],
                ),
              ),
              PopupMenuButton<String>(
                tooltip: '提醒操作',
                onSelected: (value) {
                  if (value == 'edit') {
                    _editReminder(anime);
                  } else if (value == 'cancel') {
                    _cancelReminder(anime);
                  }
                },
                itemBuilder: (_) => const [
                  PopupMenuItem(
                    value: 'edit',
                    child: ListTile(
                      contentPadding: EdgeInsets.zero,
                      leading: Icon(Icons.edit_outlined),
                      title: Text('编辑提醒'),
                    ),
                  ),
                  PopupMenuItem(
                    value: 'cancel',
                    child: ListTile(
                      contentPadding: EdgeInsets.zero,
                      leading: Icon(Icons.notifications_off_outlined),
                      title: Text('取消提醒'),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
