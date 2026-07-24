import 'package:flutter/material.dart';

import '../design_tokens.dart';
import 'empty_state.dart';

/// 页面级异步状态容器。
///
/// - 首次加载时展示加载占位；
/// - 首次失败时展示可重试错误页；
/// - 已有内容刷新时保留内容，只在顶部显示进度；
/// - 已有内容刷新失败时显示非阻断错误条。
class AsyncContentView extends StatelessWidget {
  final bool isLoading;
  final bool hasData;
  final bool isEmpty;
  final String? errorMessage;
  final VoidCallback? onRetry;
  final Widget child;
  final Widget? loading;
  final IconData emptyIcon;
  final String emptyMessage;
  final String? emptyDescription;
  final String emptyActionLabel;

  const AsyncContentView({
    super.key,
    required this.isLoading,
    required this.hasData,
    required this.isEmpty,
    required this.child,
    this.errorMessage,
    this.onRetry,
    this.loading,
    this.emptyIcon = Icons.inbox_outlined,
    this.emptyMessage = '暂无数据',
    this.emptyDescription,
    this.emptyActionLabel = '重新加载',
  });

  @override
  Widget build(BuildContext context) {
    if (!hasData && isLoading) {
      return loading ?? const _DefaultLoadingState();
    }

    if (!hasData && errorMessage != null) {
      return EmptyStateWidget(
        icon: Icons.cloud_off_rounded,
        message: '加载失败',
        description: errorMessage,
        buttonText: onRetry == null ? null : '重试',
        onButtonPressed: onRetry,
      );
    }

    if (!hasData || isEmpty) {
      return EmptyStateWidget(
        icon: emptyIcon,
        message: emptyMessage,
        description: emptyDescription,
        buttonText: onRetry == null ? null : emptyActionLabel,
        onButtonPressed: onRetry,
      );
    }

    return Stack(
      children: [
        Positioned.fill(child: child),
        if (isLoading)
          const Positioned(
            top: 0,
            left: 0,
            right: 0,
            child: LinearProgressIndicator(minHeight: 2),
          ),
        if (!isLoading && errorMessage != null)
          Positioned(
            top: AppSpacing.sm,
            left: AppSpacing.lg,
            right: AppSpacing.lg,
            child: _RefreshErrorBanner(
              message: errorMessage!,
              onRetry: onRetry,
            ),
          ),
      ],
    );
  }
}

class _DefaultLoadingState extends StatelessWidget {
  const _DefaultLoadingState();

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const CircularProgressIndicator(),
          const SizedBox(height: AppSpacing.lg),
          Text(
            '正在加载…',
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
          ),
        ],
      ),
    );
  }
}

class _RefreshErrorBanner extends StatelessWidget {
  final String message;
  final VoidCallback? onRetry;

  const _RefreshErrorBanner({required this.message, this.onRetry});

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return Material(
      color: colorScheme.errorContainer,
      elevation: 2,
      borderRadius: BorderRadius.circular(AppRadius.sm),
      child: Padding(
        padding: const EdgeInsets.symmetric(
          horizontal: AppSpacing.md,
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
            if (onRetry != null)
              TextButton(
                onPressed: onRetry,
                style: TextButton.styleFrom(
                  foregroundColor: colorScheme.onErrorContainer,
                ),
                child: const Text('重试'),
              ),
          ],
        ),
      ),
    );
  }
}
