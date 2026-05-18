@echo off
echo Building anime_tracker for Android with cloud API configuration...
flutter build apk --dart-define=CLOUD_API_BASE=http://47.103.83.247:3000 --dart-define=API_TOKEN=0ee3976d68f886648ac2bbaf6a6e605dfe843bd6ec0adf547b13f7ebadd221b0
echo Build completed. Output in build/app/outputs/flutter-apk/app-release.apk
pause
