import 'package:flutter/material.dart';
import '../db/database_helper.dart';
import '../utils/notification_service.dart';
import '../add_anime_page.dart';

class ReminderManagementPage extends StatefulWidget {
  const ReminderManagementPage({super.key});

  @override
  State<ReminderManagementPage> createState() => _ReminderManagementPageState();
}

class _ReminderManagementPageState extends State<ReminderManagementPage> {
  List<Map<String, dynamic>> _reminderAnimes = [];
  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    _loadReminders();
  }

  Future<void> _loadReminders() async {
    setState(() => _isLoading = true);
    final data = await DatabaseHelper().getAnimesWithReminders();
    if (mounted) {
      setState(() {
        _reminderAnimes = data;
        _isLoading = false;
      });
    }
  }

  String _formatDay(int day) {
    const days = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
    if (day >= 1 && day <= 7) return days[day - 1];
    return '未知';
  }

  Future<void> _cancelReminder(Map<String, dynamic> anime) async {
    final bool? confirm = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('取消提醒'),
        content: Text('确定取消《${anime['title']}》的追番提醒吗？'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, true),
            style: TextButton.styleFrom(foregroundColor: Colors.red),
            child: const Text('确定取消'),
          ),
        ],
      ),
    );

    if (confirm == true) {
      final id = anime['id'];
      // 更新数据库
      final updatedAnime = Map<String, dynamic>.from(anime);
      updatedAnime['reminder_day'] = null;
      updatedAnime['reminder_time'] = null;
      await DatabaseHelper().updateAnime(updatedAnime);

      // 取消通知
      await NotificationService().cancelNotification(id);

      _loadReminders();
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('已取消提醒')));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final themeColor = Theme.of(context).primaryColor;
    return Scaffold(
      backgroundColor: const Color(0xFFF8F9FA),
      appBar: AppBar(
        title: const Text(
          '追番提醒管理',
          style: TextStyle(fontWeight: FontWeight.bold),
        ),
        centerTitle: true,
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : _reminderAnimes.isEmpty
          ? _buildEmptyState()
          : ListView.builder(
              padding: const EdgeInsets.all(16),
              itemCount: _reminderAnimes.length,
              itemBuilder: (context, index) {
                final anime = _reminderAnimes[index];
                return _buildReminderTile(anime, themeColor);
              },
            ),
    );
  }

  Widget _buildEmptyState() {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            Icons.notifications_off_outlined,
            size: 64,
            color: Colors.grey[300],
          ),
          const SizedBox(height: 16),
          Text(
            '没有设置提醒的番剧',
            style: TextStyle(color: Colors.grey[500], fontSize: 16),
          ),
        ],
      ),
    );
  }

  Widget _buildReminderTile(Map<String, dynamic> anime, Color color) {
    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(16),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.04),
            blurRadius: 10,
            offset: const Offset(0, 4),
          ),
        ],
      ),
      child: ListTile(
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        leading: ClipRRect(
          borderRadius: BorderRadius.circular(8),
          child: SizedBox(
            width: 50,
            height: 70,
            child: anime['cover_url'] != null
                ? Image.network(
                    anime['cover_url'],
                    fit: BoxFit.cover,
                    errorBuilder: (_, __, ___) => Container(
                      color: color.withValues(alpha: 0.1),
                      child: Icon(Icons.movie, color: color),
                    ),
                  )
                : Container(
                    color: color.withValues(alpha: 0.1),
                    child: Icon(Icons.movie, color: color),
                  ),
          ),
        ),
        title: Text(
          anime['title'],
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(fontWeight: FontWeight.bold),
        ),
        subtitle: Padding(
          padding: const EdgeInsets.only(top: 4),
          child: Row(
            children: [
              Icon(Icons.access_time_filled, size: 14, color: color),
              const SizedBox(width: 4),
              Text(
                '每周 ${_formatDay(anime['reminder_day'])} ${anime['reminder_time']}',
                style: TextStyle(
                  color: color,
                  fontWeight: FontWeight.w500,
                  fontSize: 13,
                ),
              ),
            ],
          ),
        ),
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            IconButton(
              icon: const Icon(Icons.edit_outlined, size: 20),
              onPressed: () async {
                final result = await Navigator.push(
                  context,
                  MaterialPageRoute(
                    builder: (context) => AddAnimePage(existingAnime: anime),
                  ),
                );
                if (result == true) {
                  _loadReminders();
                }
              },
            ),
            IconButton(
              icon: const Icon(
                Icons.notifications_off_outlined,
                size: 20,
                color: Colors.redAccent,
              ),
              onPressed: () => _cancelReminder(anime),
            ),
          ],
        ),
      ),
    );
  }
}
