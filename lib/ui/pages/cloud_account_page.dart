import 'package:flutter/services.dart';
import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../backup_service.dart';
import '../../services/cloud_account_service.dart';
import '../../utils/api_config.dart';
import '../design_tokens.dart';

enum _AuthMode { login, register, resetPassword }

class CloudAccountPage extends StatefulWidget {
  const CloudAccountPage({super.key});

  @override
  State<CloudAccountPage> createState() => _CloudAccountPageState();
}

class _CloudAccountPageState extends State<CloudAccountPage> {
  static const _qqGroupNumber = '1073623448';

  final _usernameController = TextEditingController();
  final _passwordController = TextEditingController();
  final _confirmPasswordController = TextEditingController();
  final _inviteController = TextEditingController();

  CloudSession? _session;
  List<CloudBackupInfo> _backups = const [];
  _AuthMode _authMode = _AuthMode.login;
  bool _loading = true;
  bool _busy = false;
  bool _obscurePassword = true;
  bool _obscureConfirmPassword = true;

  @override
  void initState() {
    super.initState();
    _loadSession();
  }

  @override
  void dispose() {
    _usernameController.dispose();
    _passwordController.dispose();
    _confirmPasswordController.dispose();
    _inviteController.dispose();
    super.dispose();
  }

  Future<void> _loadSession() async {
    final session = await CloudAccountService.loadSession();
    if (!mounted) return;
    setState(() {
      _session = session;
      _loading = false;
    });
    if (session != null) {
      await _refreshCloudData(session);
    }
  }

  Future<void> _refreshCloudData([CloudSession? session]) async {
    final activeSession = session ?? _session;
    if (activeSession == null) return;
    await _refreshBackups(activeSession);
  }

  Future<void> _refreshBackups(CloudSession session) async {
    try {
      final backups = await CloudAccountService.listBackups(session);
      if (!mounted) return;
      setState(() => _backups = backups);
    } catch (e) {
      _showSnack(e.toString());
    }
  }

  bool get _isRegisterMode => _authMode == _AuthMode.register;
  bool get _isResetPasswordMode => _authMode == _AuthMode.resetPassword;
  bool get _needsConfirmPassword => _authMode != _AuthMode.login;

  Future<void> _submitAuth() async {
    if (_busy) return;
    final username = _usernameController.text.trim();
    final password = _passwordController.text;
    final confirmPassword = _confirmPasswordController.text;
    final inviteCode = _inviteController.text.trim();
    final validationMessage = _validateAuthForm(
      username: username,
      password: password,
      confirmPassword: confirmPassword,
      inviteCode: inviteCode,
    );
    if (validationMessage != null) {
      _showSnack(validationMessage);
      return;
    }

    setState(() => _busy = true);
    try {
      if (_isResetPasswordMode) {
        await CloudAccountService.resetPassword(
          username: username,
          password: password,
          inviteCode: inviteCode,
        );
        if (!mounted) return;
        setState(() {
          _authMode = _AuthMode.login;
          _passwordController.clear();
          _confirmPasswordController.clear();
          _inviteController.clear();
        });
        _showSnack('密码已重置，请使用新密码登录');
        return;
      }

      final session = _isRegisterMode
          ? await CloudAccountService.register(
              username: username,
              password: password,
              inviteCode: inviteCode,
            )
          : await CloudAccountService.login(
              username: username,
              password: password,
            );
      if (!mounted) return;
      setState(() {
        _session = session;
        _passwordController.clear();
        _confirmPasswordController.clear();
        _inviteController.clear();
      });
      await _refreshCloudData(session);
      _showSnack(_isRegisterMode ? '注册成功，欢迎加入' : '登录成功');
    } catch (e) {
      _showSnack(e.toString());
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  String? _validateAuthForm({
    required String username,
    required String password,
    required String confirmPassword,
    required String inviteCode,
  }) {
    if (_isResetPasswordMode) {
      return _validatePasswordResetForm(
        username: username,
        password: password,
        confirmPassword: confirmPassword,
        inviteCode: inviteCode,
      );
    }
    if (username.isEmpty) return '请输入用户名';
    if (password.isEmpty) return '请输入密码';
    if (_needsConfirmPassword) {
      if (confirmPassword.isEmpty) return '请再次输入密码';
      if (password != confirmPassword) return '两次输入的密码不一致';
    }
    if (_isRegisterMode && inviteCode.isEmpty) return '请输入邀请码';
    return null;
  }

  String? _validatePasswordResetForm({
    required String username,
    required String password,
    required String confirmPassword,
    required String inviteCode,
  }) {
    if (username.isEmpty) return '请输入用户名';
    if (inviteCode.isEmpty) return '请输入邀请码';
    if (password.isEmpty) return '请输入新密码';
    if (confirmPassword.isEmpty) return '请再次输入新密码';
    if (password != confirmPassword) return '两次输入的新密码不一致';
    return null;
  }

  Future<void> _logout() async {
    await CloudAccountService.logout();
    if (!mounted) return;
    setState(() {
      _session = null;
      _backups = const [];
    });
  }

  Future<void> _showChangePasswordDialog(String username) async {
    final usernameController = TextEditingController(text: username);
    final inviteController = TextEditingController();
    final passwordController = TextEditingController();
    final confirmPasswordController = TextEditingController();
    var obscurePassword = true;
    var obscureConfirmPassword = true;
    var dialogBusy = false;

    try {
      await showDialog<void>(
        context: context,
        builder: (dialogContext) {
          return StatefulBuilder(
            builder: (dialogContext, setDialogState) {
              Future<void> submit() async {
                if (dialogBusy) return;
                final resetUsername = usernameController.text.trim();
                final validationMessage = _validatePasswordResetForm(
                  username: resetUsername,
                  password: passwordController.text,
                  confirmPassword: confirmPasswordController.text,
                  inviteCode: inviteController.text.trim(),
                );
                if (validationMessage != null) {
                  _showSnack(validationMessage);
                  return;
                }

                setDialogState(() => dialogBusy = true);
                try {
                  await CloudAccountService.resetPassword(
                    username: resetUsername,
                    password: passwordController.text,
                    inviteCode: inviteController.text.trim(),
                  );
                  await CloudAccountService.logout();
                  if (!mounted || !dialogContext.mounted) return;
                  Navigator.of(dialogContext).pop();
                  setState(() {
                    _session = null;
                    _backups = const [];
                    _authMode = _AuthMode.login;
                    _usernameController.text = resetUsername;
                    _passwordController.clear();
                    _confirmPasswordController.clear();
                    _inviteController.clear();
                  });
                  _showSnack('密码已修改，请使用新密码重新登录');
                } catch (e) {
                  _showSnack(e.toString());
                  if (dialogContext.mounted) {
                    setDialogState(() => dialogBusy = false);
                  }
                }
              }

              return AlertDialog(
                title: const Text('修改密码'),
                content: SingleChildScrollView(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      TextField(
                        controller: usernameController,
                        textInputAction: TextInputAction.next,
                        decoration: const InputDecoration(
                          labelText: '用户名',
                          prefixIcon: Icon(Icons.alternate_email_rounded),
                        ),
                      ),
                      const SizedBox(height: AppSpacing.md),
                      TextField(
                        controller: inviteController,
                        textCapitalization: TextCapitalization.characters,
                        textInputAction: TextInputAction.next,
                        decoration: const InputDecoration(
                          labelText: '邀请码',
                          prefixIcon: Icon(Icons.confirmation_number_outlined),
                        ),
                      ),
                      const SizedBox(height: AppSpacing.md),
                      TextField(
                        controller: passwordController,
                        obscureText: obscurePassword,
                        textInputAction: TextInputAction.next,
                        decoration: InputDecoration(
                          labelText: '新密码',
                          prefixIcon: const Icon(Icons.lock_outline_rounded),
                          suffixIcon: IconButton(
                            onPressed: () {
                              setDialogState(() {
                                obscurePassword = !obscurePassword;
                              });
                            },
                            tooltip: obscurePassword ? '显示密码' : '隐藏密码',
                            icon: Icon(
                              obscurePassword
                                  ? Icons.visibility_outlined
                                  : Icons.visibility_off_outlined,
                            ),
                          ),
                        ),
                      ),
                      const SizedBox(height: AppSpacing.md),
                      TextField(
                        controller: confirmPasswordController,
                        obscureText: obscureConfirmPassword,
                        textInputAction: TextInputAction.done,
                        decoration: InputDecoration(
                          labelText: '确认新密码',
                          prefixIcon: const Icon(Icons.lock_reset_rounded),
                          suffixIcon: IconButton(
                            onPressed: () {
                              setDialogState(() {
                                obscureConfirmPassword =
                                    !obscureConfirmPassword;
                              });
                            },
                            tooltip: obscureConfirmPassword ? '显示密码' : '隐藏密码',
                            icon: Icon(
                              obscureConfirmPassword
                                  ? Icons.visibility_outlined
                                  : Icons.visibility_off_outlined,
                            ),
                          ),
                        ),
                        onSubmitted: (_) => submit(),
                      ),
                    ],
                  ),
                ),
                actions: [
                  TextButton(
                    onPressed: dialogBusy
                        ? null
                        : () => Navigator.of(dialogContext).pop(),
                    child: const Text('取消'),
                  ),
                  FilledButton.icon(
                    onPressed: dialogBusy ? null : submit,
                    icon: dialogBusy
                        ? const SizedBox(
                            width: 16,
                            height: 16,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.lock_reset_rounded),
                    label: const Text('保存新密码'),
                  ),
                ],
              );
            },
          );
        },
      );
    } finally {
      usernameController.dispose();
      inviteController.dispose();
      passwordController.dispose();
      confirmPasswordController.dispose();
    }
  }

  Future<void> _uploadBackup() async {
    final session = _session;
    if (session == null || _busy) return;
    setState(() => _busy = true);
    try {
      final remaining =
          await CloudAccountService.getBackupUploadCooldownRemaining(session);
      if (remaining != null) {
        _showSnack(
          '距离上次云存储未满 5 分钟，请 ${CloudAccountService.formatCooldown(remaining)} 后再试',
        );
        return;
      }

      _showSnack('正在打包并上传数据...');
      final zipFile = await BackupService.createBackupZipFile();
      final backup = await CloudAccountService.uploadBackupFile(
        session: session,
        zipFile: zipFile,
      );
      if (!mounted) return;
      setState(() {
        _backups = [
          backup,
          ..._backups.where((item) => item.id != backup.id),
        ].take(CloudAccountService.maxBackupCount).toList(growable: false);
      });
      await _refreshBackups(session);
      _showSnack('已完成云存储，云端会保留最新 3 次');
    } catch (e) {
      _showSnack(e.toString());
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _restoreCloudBackup(CloudBackupInfo backup) async {
    final session = _session;
    if (session == null || _busy) return;
    setState(() => _busy = true);
    try {
      _showSnack('正在下载云端备份...');
      final zipFile = await CloudAccountService.downloadBackupFile(
        session: session,
        backup: backup,
      );
      if (!mounted) return;
      await BackupService.restoreFromZipFile(context, zipFile, () {});
    } catch (e) {
      _showSnack(e.toString());
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _openQQGroup() async {
    final uri = Uri.parse(
      'mqqapi://card/show_pslcard?src_type=internal&version=1'
      '&uin=$_qqGroupNumber&card_type=group&source=qrcode',
    );
    try {
      final opened = await launchUrl(uri, mode: LaunchMode.externalApplication);
      if (opened) return;
    } catch (_) {}
    await _copyQQGroupNumber(fallback: true);
  }

  Future<void> _copyQQGroupNumber({bool fallback = false}) async {
    await Clipboard.setData(const ClipboardData(text: _qqGroupNumber));
    _showSnack(fallback ? '未能直接打开 QQ，已复制群号 $_qqGroupNumber' : 'QQ群号已复制');
  }

  void _showSnack(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message.replaceFirst('Exception: ', ''))),
    );
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(title: const Text('账号与云同步')),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.fromLTRB(
                AppSpacing.lg,
                AppSpacing.md,
                AppSpacing.lg,
                AppSpacing.xxl,
              ),
              children: [
                if (!ApiConfig.hasCloudBase) _buildCloudConfigWarning(cs),
                if (_session == null)
                  _buildAuthCard(cs)
                else ...[
                  _buildAccountCard(cs),
                  const SizedBox(height: AppSpacing.xl),
                  _buildBackupCard(cs),
                ],
              ],
            ),
    );
  }

  Widget _buildCloudConfigWarning(ColorScheme cs) {
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.lg),
      child: _buildCard(
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(Icons.cloud_off_outlined, color: cs.error),
            const SizedBox(width: AppSpacing.md),
            Expanded(
              child: Text(
                '当前构建未配置 CLOUD_API_BASE，云账号和云端备份暂不可用。',
                style: TextStyle(color: cs.error, fontWeight: FontWeight.w700),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildPasswordTextField({
    required TextEditingController controller,
    required String labelText,
    required bool obscureText,
    required VoidCallback onToggleObscure,
    required TextInputAction textInputAction,
    IconData prefixIcon = Icons.lock_outline_rounded,
    ValueChanged<String>? onSubmitted,
  }) {
    return TextField(
      controller: controller,
      obscureText: obscureText,
      textInputAction: textInputAction,
      decoration: InputDecoration(
        labelText: labelText,
        prefixIcon: Icon(prefixIcon),
        suffixIcon: IconButton(
          onPressed: onToggleObscure,
          tooltip: obscureText ? '显示密码' : '隐藏密码',
          icon: Icon(
            obscureText
                ? Icons.visibility_outlined
                : Icons.visibility_off_outlined,
          ),
        ),
      ),
      onSubmitted: onSubmitted,
    );
  }

  Widget _buildAuthCard(ColorScheme cs) {
    final passwordLabel = _isResetPasswordMode ? '新密码' : '密码';
    final confirmPasswordLabel = _isResetPasswordMode ? '确认新密码' : '确认密码';
    final buttonIcon = switch (_authMode) {
      _AuthMode.login => Icons.login,
      _AuthMode.register => Icons.person_add,
      _AuthMode.resetPassword => Icons.lock_reset_rounded,
    };
    final buttonLabel = switch (_authMode) {
      _AuthMode.login => '登录账号',
      _AuthMode.register => '使用邀请码注册',
      _AuthMode.resetPassword => '重置密码',
    };
    final helperText = switch (_authMode) {
      _AuthMode.login => '云账号用于云存储和恢复云端备份，本地使用不受影响。',
      _AuthMode.register => '没有邀请码可以向群主获取；请妥善保存邀请码，忘记密码时可用于重置密码。',
      _AuthMode.resetPassword => '请输入账号和邀请码，验证通过后即可设置新密码。',
    };

    return _buildCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _buildLocalUsePromise(cs),
          const SizedBox(height: AppSpacing.lg),
          SegmentedButton<_AuthMode>(
            segments: const [
              ButtonSegment(
                value: _AuthMode.login,
                icon: Icon(Icons.login_rounded),
                label: Text('登录'),
              ),
              ButtonSegment(
                value: _AuthMode.register,
                icon: Icon(Icons.person_add_alt_1_rounded),
                label: Text('注册'),
              ),
              ButtonSegment(
                value: _AuthMode.resetPassword,
                icon: Icon(Icons.lock_reset_rounded),
                label: Text('重置'),
              ),
            ],
            selected: {_authMode},
            onSelectionChanged: (value) {
              setState(() {
                _authMode = value.first;
                _confirmPasswordController.clear();
              });
            },
          ),
          const SizedBox(height: AppSpacing.lg),
          TextField(
            controller: _usernameController,
            textInputAction: TextInputAction.next,
            decoration: const InputDecoration(
              labelText: '用户名',
              prefixIcon: Icon(Icons.alternate_email_rounded),
            ),
          ),
          if (_isResetPasswordMode) ...[
            const SizedBox(height: AppSpacing.md),
            TextField(
              controller: _inviteController,
              textCapitalization: TextCapitalization.characters,
              textInputAction: TextInputAction.next,
              decoration: const InputDecoration(
                labelText: '邀请码',
                helperText: '请妥善保存，忘记密码时可用它重置密码',
                prefixIcon: Icon(Icons.confirmation_number_outlined),
              ),
            ),
          ],
          const SizedBox(height: AppSpacing.md),
          _buildPasswordTextField(
            controller: _passwordController,
            labelText: passwordLabel,
            obscureText: _obscurePassword,
            onToggleObscure: () {
              setState(() => _obscurePassword = !_obscurePassword);
            },
            textInputAction: _needsConfirmPassword
                ? TextInputAction.next
                : TextInputAction.done,
            onSubmitted: (_) {
              if (!_needsConfirmPassword) _submitAuth();
            },
          ),
          if (_needsConfirmPassword) ...[
            const SizedBox(height: AppSpacing.md),
            _buildPasswordTextField(
              controller: _confirmPasswordController,
              labelText: confirmPasswordLabel,
              obscureText: _obscureConfirmPassword,
              onToggleObscure: () {
                setState(
                  () => _obscureConfirmPassword = !_obscureConfirmPassword,
                );
              },
              textInputAction: _isRegisterMode
                  ? TextInputAction.next
                  : TextInputAction.done,
              prefixIcon: Icons.lock_reset_rounded,
              onSubmitted: (_) {
                if (_isResetPasswordMode) _submitAuth();
              },
            ),
          ],
          if (_isRegisterMode) ...[
            const SizedBox(height: AppSpacing.md),
            TextField(
              controller: _inviteController,
              textCapitalization: TextCapitalization.characters,
              textInputAction: TextInputAction.done,
              decoration: const InputDecoration(
                labelText: '邀请码',
                prefixIcon: Icon(Icons.confirmation_number_outlined),
              ),
              onSubmitted: (_) => _submitAuth(),
            ),
          ],
          const SizedBox(height: AppSpacing.lg),
          FilledButton.icon(
            onPressed: _busy || !ApiConfig.hasCloudBase ? null : _submitAuth,
            icon: _busy
                ? const SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : Icon(buttonIcon),
            label: Text(buttonLabel),
          ),
          const SizedBox(height: AppSpacing.sm),
          Text(
            helperText,
            style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
          ),
          if (_isRegisterMode) ...[
            const SizedBox(height: AppSpacing.md),
            _buildQQGroupActions(cs),
          ],
        ],
      ),
    );
  }

  Widget _buildLocalUsePromise(ColorScheme cs) {
    return Container(
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: BoxDecoration(
        color: cs.secondaryContainer.withValues(alpha: 0.55),
        borderRadius: BorderRadius.circular(AppRadius.xs),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(Icons.verified_user_outlined, color: cs.onSecondaryContainer),
          const SizedBox(width: AppSpacing.md),
          Expanded(
            child: Text(
              '云账号是可选功能。承诺非注册用户也可以永远正常使用 APP，本地追番、记录和设置都会照常保留。',
              style: TextStyle(
                color: cs.onSecondaryContainer,
                fontSize: 13,
                fontWeight: FontWeight.w700,
                height: 1.35,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildQQGroupActions(ColorScheme cs) {
    return Container(
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: BoxDecoration(
        color: cs.surfaceContainerHigh,
        borderRadius: BorderRadius.circular(AppRadius.xs),
      ),
      child: Row(
        children: [
          Icon(Icons.group_add_outlined, color: cs.primary),
          const SizedBox(width: AppSpacing.md),
          Expanded(
            child: Text(
              'QQ群 $_qqGroupNumber',
              style: const TextStyle(fontWeight: FontWeight.w800),
            ),
          ),
          IconButton(
            onPressed: _copyQQGroupNumber,
            tooltip: '复制群号',
            icon: const Icon(Icons.copy_rounded),
          ),
          IconButton(
            onPressed: _openQQGroup,
            tooltip: '打开 QQ',
            icon: const Icon(Icons.open_in_new_rounded),
          ),
        ],
      ),
    );
  }

  Widget _buildAccountCard(ColorScheme cs) {
    final session = _session!;
    return _buildCard(
      child: Row(
        children: [
          CircleAvatar(
            backgroundColor: cs.primaryContainer,
            foregroundColor: cs.onPrimaryContainer,
            child: const Icon(Icons.person_rounded),
          ),
          const SizedBox(width: AppSpacing.md),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  session.username,
                  style: Theme.of(context).textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.w900,
                  ),
                ),
                Text(
                  '已登录云账号',
                  style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
                ),
              ],
            ),
          ),
          IconButton(
            onPressed: _busy
                ? null
                : () => _showChangePasswordDialog(session.username),
            tooltip: '修改密码',
            icon: const Icon(Icons.lock_reset_rounded),
          ),
          IconButton(
            onPressed: _logout,
            tooltip: '退出登录',
            icon: const Icon(Icons.logout_rounded),
          ),
        ],
      ),
    );
  }

  Widget _buildBackupCard(ColorScheme cs) {
    return _buildCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _buildCardHeader(
            icon: Icons.cloud_upload_outlined,
            color: Colors.teal,
            title: '云端数据存储',
            subtitle: '每 5 分钟可上传一次，云端仅保留最新 3 次',
          ),
          const SizedBox(height: AppSpacing.md),
          FilledButton.icon(
            onPressed: _busy ? null : _uploadBackup,
            icon: const Icon(Icons.backup_outlined),
            label: const Text('上传数据'),
          ),
          const SizedBox(height: AppSpacing.lg),
          if (_backups.isEmpty)
            Text('还没有云端备份', style: TextStyle(color: cs.onSurfaceVariant))
          else
            ..._backups.map(
              (backup) => ListTile(
                contentPadding: EdgeInsets.zero,
                leading: const Icon(Icons.inventory_2_outlined),
                title: Text(
                  backup.fileName,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                subtitle: Text(
                  '${_formatBytes(backup.payloadSize)} · ${_formatDate(backup.createdAt)}',
                ),
                trailing: IconButton(
                  onPressed: _busy ? null : () => _restoreCloudBackup(backup),
                  tooltip: '从云端恢复',
                  icon: const Icon(Icons.cloud_download_outlined),
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildCardHeader({
    required IconData icon,
    required Color color,
    required String title,
    required String subtitle,
  }) {
    return Row(
      children: [
        Container(
          width: 40,
          height: 40,
          decoration: BoxDecoration(
            color: color.withValues(alpha: 0.12),
            borderRadius: BorderRadius.circular(AppRadius.xs),
          ),
          child: Icon(icon, color: color),
        ),
        const SizedBox(width: AppSpacing.md),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                title,
                style: Theme.of(
                  context,
                ).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w900),
              ),
              Text(
                subtitle,
                style: TextStyle(
                  fontSize: 12,
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildCard({required Widget child}) {
    return Container(
      padding: const EdgeInsets.all(AppSpacing.lg),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(AppRadius.md),
      ),
      child: child,
    );
  }

  String _formatBytes(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    return '${(bytes / 1024 / 1024).toStringAsFixed(1)} MB';
  }

  String _formatDate(String raw) {
    if (raw.isEmpty) return '-';
    final parsed = DateTime.tryParse(raw);
    if (parsed != null) {
      final local = parsed.isUtc ? parsed.toLocal() : parsed;
      final dateText = [
        local.year.toString().padLeft(4, '0'),
        local.month.toString().padLeft(2, '0'),
        local.day.toString().padLeft(2, '0'),
      ].join('-');
      final timeText = [
        local.hour.toString().padLeft(2, '0'),
        local.minute.toString().padLeft(2, '0'),
      ].join(':');
      return '$dateText $timeText';
    }
    final normalized = raw.replaceFirst('T', ' ');
    return normalized.length > 16 ? normalized.substring(0, 16) : normalized;
  }
}
