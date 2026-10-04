Junba AI Transcriber v3.8.2 APK R2 — Android Only Overlay
==========================================================

目的
----
本包只更新 Android APK，不含也不修改 Windows Single EXE。
R2 是從「R1 之前、已在手機實機成功開啟並完成 Gemini 長音訊轉錄」的 Android 原始碼重新製作，
不是從會一開就閃退的 R1 繼續補洞。

這次改善
--------
1. Gemini API Key：已設定後預設收折，需要修改時才展開。
2. Whisper / Gemini：顯示目前階段、進度或估計區間、已執行時間、仍在執行狀態。
   - 上傳與模型下載：依實際 bytes 顯示較準確百分比。
   - Whisper / Gemini 模型推論：API 沒有即時百分比，因此標示「約 xx–xx%」，避免假精準。
3. 逐字稿預覽：加大結果區，可上下瀏覽；有 [HH:MM:SS] 時間標記時會依時間排序。
4. Gemini 長音訊提示：要求每個自然段前標示 [HH:MM:SS] 並依時間先後輸出。
5. 一鍵存檔：建立 Documents/Junba AI Transcriber/YYYY-MM-DD/YYYY-MM-DD_HHmmss/
   - 原始音檔：保留原始檔名，不更名。
   - 逐字稿：同一原始檔名主體，輸出 .txt 與 .md。
6. 版本：versionCode 384 / versionName 3.8.2-apk-r2。

R1 閃退防護
-----------
R2 不使用 R1 在 Activity 欄位初始化階段建立的 Handler/Looper 心跳物件。
心跳改成在畫面建立完成、真正開始工作後，才由 progress TextView.postDelayed 啟動。
另外 GitHub Actions 增加 Robolectric MainActivity 啟動 smoke test：如果 MainActivity.onCreate/buildUi 一開就丟例外，
工作流程會直接失敗，不會把該版本當成可用 APK。

GitHub 上傳方式
--------------
將 ZIP 解壓後的：
  android/
  .github/workflows/build-android-v3.8.2-r2.yml
直接覆蓋/上傳至 b52tw/Junba-AI-Transcriber-3.8.2 Repository 根目錄。
不要再上傳 R1 的 build-android-v3.8.2-r1.yml。

建置
----
GitHub -> Actions -> Build Junba Android APK v3.8.2 R2 -> Run workflow

成功後下載 Artifact：
  Junba-v382-APK-R2
其中包含：
  Junba-R2.apk
  SHA256.txt
  BUILD_CHECKS.txt

工作流程會依序做：
  Java/Kotlin compile
  MainActivity startup smoke test
  APK assembleDebug
  arm64 native library 檢查
  manifest / launchable MainActivity 檢查

注意
----
本封包在此環境已做 Java 語法解析與檔案結構檢查；真正 Android SDK 編譯、Robolectric 啟動測試與 APK assemble
由 GitHub Actions 完成。只有 Actions 全綠燈後才安裝 Junba-R2.apk。
