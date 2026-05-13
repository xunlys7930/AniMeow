import 'package:flutter/foundation.dart';

class DataRefreshProvider extends ChangeNotifier {
  void refreshAll() {
    notifyListeners();
  }
}
