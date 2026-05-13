import 'dart:io';
void main() {
  final logPath = r'C:\Users\86153\.gemini\antigravity\brain\85246ef0-f924-41ee-949f-d605cd8a1f3e\.system_generated\logs\overview.txt';
  final lines = File(logPath).readAsLinesSync();
  int startIdx = -1;
  for (int i = lines.length - 1; i >= 0; i--) {
    if (lines[i].contains('File Path: `file:///d:/Projects/cursor/anime_tracker/lib/add_anime_page.dart`') && lines[i+1].contains('Total Lines:')) {
      startIdx = i;
      break;
    }
  }
  if (startIdx != -1) {
    print('Found file view at line \');
    final codeLines = <String>[];
    bool foundStart = false;
    for (int j = startIdx; j < lines.length; j++) {
      final line = lines[j];
      if (line.startsWith('1: ')) foundStart = true;
      if (line.contains('The above content')) break;
      if (line.contains('tool call completed')) break;
      if (foundStart) {
        final match = RegExp(r'^\d+:\s(.*)').firstMatch(line);
        if (match != null) {
          codeLines.add(match.group(1)!);
        } else {
          codeLines.add(line);
        }
      }
    }
    File('d:/Projects/cursor/anime_tracker/lib/add_anime_page.dart').writeAsStringSync(codeLines.join('\n'));
    print('Restored successfully! length: \');
  } else {
    print('Not found');
  }
}
