import 'dart:collection';

class ErrorLogger {
  static final ErrorLogger _instance = ErrorLogger._internal();

  factory ErrorLogger() {
    return _instance;
  }

  ErrorLogger._internal();

  static ErrorLogger get instance => _instance;

  final int _maxLogs = 50;
  final Queue<Map<String, dynamic>> _errorLogs = Queue<Map<String, dynamic>>();

  List<Map<String, dynamic>> get logs => _errorLogs.toList();

  void addError(dynamic error, dynamic stackTrace) {
    if (_errorLogs.length >= _maxLogs) {
      _errorLogs.removeFirst();
    }
    _errorLogs.addLast({
      'time': DateTime.now(),
      'error': error.toString(),
      'stackTrace': stackTrace?.toString() ?? '无堆栈信息',
    });
  }

  void clearLogs() {
    _errorLogs.clear();
  }

  String getFormattedLogs() {
    if (_errorLogs.isEmpty) return '暂无错误日志。';

    final buffer = StringBuffer();
    for (var log in _errorLogs) {
      buffer.writeln('====================');
      buffer.writeln('Time: ${log['time']}');
      buffer.writeln('Error:');
      buffer.writeln(log['error']);
      buffer.writeln('Stack Trace:');
      buffer.writeln(log['stackTrace']);
      buffer.writeln('====================\n');
    }
    return buffer.toString();
  }
}
