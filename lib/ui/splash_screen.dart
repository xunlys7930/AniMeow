import 'dart:io';
import 'package:flutter/material.dart';
import '../settings_manager.dart';

/// 启动封面屏
///
/// 行为：
/// 1. 若 [SettingsManager.enableCustomSplashNotifier] 为 false 或
///    [SettingsManager.splashImagePathNotifier] 为空，立即跳转 [next]
/// 2. 否则展示带淡入效果的全屏自定义封面，再跳转 [next]，
///    时长由 [SettingsManager.splashDurationNotifier] 控制（默认 1 秒）
/// 3. 右下角提供「跳过」按钮，用户可立即进入应用
class SplashScreen extends StatefulWidget {
  /// 启动封面结束后跳转到的页面
  final Widget next;

  /// 可选：覆盖设置中的展示时长（一般用不到，主要给测试用）
  final Duration? duration;

  const SplashScreen({
    super.key,
    required this.next,
    this.duration,
  });

  @override
  State<SplashScreen> createState() => _SplashScreenState();
}

class _SplashScreenState extends State<SplashScreen>
    with SingleTickerProviderStateMixin {
  late AnimationController _controller;
  late Animation<double> _fade;

  /// 实际要显示的图片文件，null 表示无须展示自定义封面
  File? _imageFile;
  bool _isInitialized = false;
  bool _navigated = false;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 400),
    );
    _fade = CurvedAnimation(parent: _controller, curve: Curves.easeIn);

    // 预检图片并预热
    _initSplash();
  }

  Future<void> _initSplash() async {
    final enable = SettingsManager().enableCustomSplashNotifier.value;
    final path = SettingsManager().splashImagePathNotifier.value;

    if (enable && path.isNotEmpty) {
      final f = File(path);
      if (await f.exists()) {
        _imageFile = f;
      }
    }

    if (_imageFile == null) {
      // 无封面，确保在首帧渲染完成后再导航（修复 Windows 白屏）
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _goNext(instant: true);
      });
      return;
    }

    // 预加载图片到内存，防止第一帧黑屏
    if (mounted) {
      await precacheImage(FileImage(_imageFile!), context);
      if (!mounted) return;
      setState(() {
        _isInitialized = true;
      });
      _controller.forward();

      final ms = widget.duration?.inMilliseconds ??
          SettingsManager().splashDurationNotifier.value;
      Future.delayed(Duration(milliseconds: ms), () {
        if (mounted) {
          _goNext();
        }
      });
    }
  }

  void _goNext({bool instant = false}) {
    if (!mounted || _navigated) return;
    _navigated = true;

    if (instant) {
      Navigator.of(context).pushReplacement(
        MaterialPageRoute(builder: (_) => widget.next),
      );
      return;
    }

    Navigator.of(context).pushReplacement(
      PageRouteBuilder(
        transitionDuration: const Duration(milliseconds: 400),
        pageBuilder: (_, __, ___) => widget.next,
        transitionsBuilder: (_, anim, __, child) {
          return FadeTransition(
            opacity: anim,
            child: child,
          );
        },
      ),
    );
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    // 如果还没初始化完成或者不需要显示，返回与应用背景一致的占位
    if (!_isInitialized || _imageFile == null) {
      return Container(color: Colors.white);
    }

    return Scaffold(
      backgroundColor: Colors.white,
      body: Stack(
        fit: StackFit.expand,
        children: [
          FadeTransition(
            opacity: _fade,
            child: Image.file(
              _imageFile!,
              fit: BoxFit.cover,
              errorBuilder: (context, error, stack) {
                _goNext(instant: true);
                return const SizedBox.shrink();
              },
            ),
          ),
          Positioned(
            bottom: 0,
            right: 0,
            child: SafeArea(
              child: Padding(
                padding: const EdgeInsets.all(20),
                child: _SkipButton(onTap: _goNext),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _SkipButton extends StatelessWidget {
  final VoidCallback onTap;
  const _SkipButton({required this.onTap});

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.black.withValues(alpha: 0.45),
      borderRadius: BorderRadius.circular(24),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(24),
        child: const Padding(
          padding: EdgeInsets.symmetric(horizontal: 14, vertical: 8),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                '跳过',
                style: TextStyle(
                  color: Colors.white,
                  fontWeight: FontWeight.w700,
                  fontSize: 13,
                ),
              ),
              SizedBox(width: 4),
              Icon(Icons.skip_next_rounded, color: Colors.white, size: 16),
            ],
          ),
        ),
      ),
    );
  }
}
