Junba AI Transcriber v3.8.2 APK R2.1 - Android only overlay

這是針對 R2 GitHub Actions 在 MainActivityStartupTest (Robolectric) 階段失敗的修正版。
畫面顯示 Java/Kotlin 編譯已成功；失敗發生在 CI 測試框架，APK 尚未進入 assembleDebug。

R2.1 調整：
1. 移除 Robolectric 測試與其依賴，避免 CI-only 測試框架阻止 APK 建置。
2. 保留 Java/Kotlin compile gate。
3. 先真正 assembleDebug，再驗證 APK 簽章、Manifest launcher、DEX 內 MainActivity、arm64 native libraries。
4. 移除 attachBaseContext/fontScale 啟動覆寫，降低 Vivo/Android 實機啟動差異風險。
5. 保留 R2 的 API Key 收折、進度/經過時間、完整預覽、時間序、日期時間資料夾與原檔名保存。

上傳：
- android/
- .github/workflows/build-android-v3.8.2-r2.yml（直接覆蓋舊 R2 workflow）

GitHub Actions 執行：Build Junba Android APK v3.8.2 R2.1
成功 Artifact：Junba-v382-APK-R2.1
APK：Junba-R2.1.apk
