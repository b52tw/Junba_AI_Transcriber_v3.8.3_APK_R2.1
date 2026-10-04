package tw.junba.transcriber;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final int REQ_AUDIO = 1001;
    private static final int REQ_SAVE_TXT = 1002;
    private static final int REQ_SAVE_MD = 1003;
    private static final String PREFS = "junba_android_v382";
    private static final String TRANSCRIBE_MODEL = "gemini-3.5-transcribe";
    private static final String LONG_AUDIO_MODEL = "gemini-3.8-flash";
    private static final long LONG_AUDIO_RANGE_THRESHOLD_MS = 3L * 60L * 60L * 1000L;
    private static final long LONG_AUDIO_RANGE_MS = 60L * 60L * 1000L;
    private static final String AI_STUDIO_URL = "https://aistudio.google.com/app/apikey";
    private static final String MODEL_BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);
    private final AtomicBoolean failoverUsed = new AtomicBoolean(false);

    private Uri audioUri;
    private String audioName = "";
    private String lastTranscript = "";
    private String lastRawResponse = "";
    private String lastEngineLabel = "—";

    private TextView audioLabel;
    private EditText apiKey;
    private Spinner engineSpinner;
    private Spinner modeSpinner;
    private Spinner languageSpinner;
    private Spinner localModelSpinner;
    private CheckBox diarization;
    private CheckBox timestamps;
    private ProgressBar progress;
    private TextView stage;
    private TextView modelStatus;
    private TextView deviceStatus;
    private EditText result;
    private Button startButton;
    private Button cancelButton;
    private Button modelButton;
    private Button keyToggleButton;
    private LinearLayout keyPanel;
    private TextView progressDetail;
    private TextView saveFolderHint;

    private final AtomicBoolean busyState = new AtomicBoolean(false);
    private Runnable heartbeatRunnable;
    private long operationStartedMs = 0L;
    private String progressRangeText = "";
    private String sessionDate = "";
    private String sessionStamp = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        migrateLegacyApiKey();
        setContentView(buildUi());
        updateModelStatus();
    }

    private void migrateLegacyApiKey() {
        if (!SecureKeyStore.load(this).isEmpty()) return;
        try {
            SharedPreferences old = getSharedPreferences("junba_android", MODE_PRIVATE);
            String legacy = old.getString("api_key", "");
            if (legacy != null && !legacy.trim().isEmpty()) {
                SecureKeyStore.save(this, legacy.trim());
                old.edit().remove("api_key").apply();
            }
        } catch (Exception ignored) {}
    }

    private View buildUi() {
        int pad = dp(14);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(text("峻爸 AI Transcriber v3.8.2 APK R2.1｜Android 自適應混合版", 22, true));
        TextView note = text("自動混合會依手機、檔案格式、Whisper 模型與進階需求選擇本機 Whisper 或 Gemini。Gemini 採 Files API 整檔上傳；長錄音會自動切換 AI Studio 同級長上下文模式，不先切實體音檔。API Key 只儲存在這一台手機。", 13, false);
        note.setPadding(0, dp(4), 0, dp(10));
        root.addView(note);

        Button pick = button("選擇錄音檔");
        pick.setOnClickListener(v -> chooseAudio());
        root.addView(pick, lpMatch());
        audioLabel = text("尚未選擇錄音檔", 14, false);
        audioLabel.setPadding(0, dp(6), 0, dp(10));
        root.addView(audioLabel);

        root.addView(sectionLabel("辨識引擎"));
        engineSpinner = new Spinner(this);
        String[] engines = {
                "自動混合（建議）",
                "本機 Whisper（離線）",
                "Google Gemini（雲端）"
        };
        engineSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, engines));
        engineSpinner.setSelection(getSharedPreferences(PREFS, MODE_PRIVATE).getInt("engine", 0));
        root.addView(engineSpinner, lpMatch());

        deviceStatus = text(deviceSummary(), 12, false);
        deviceStatus.setPadding(0, dp(4), 0, dp(8));
        root.addView(deviceStatus);

        root.addView(sectionLabel("本機 Whisper 模型"));
        localModelSpinner = new Spinner(this);
        String[] models = {
                "自動（依手機記憶體）",
                "tiny｜約 75 MB｜最快",
                "base｜約 142 MB｜建議",
                "small｜約 466 MB｜較準但較慢"
        };
        localModelSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, models));
        localModelSpinner.setSelection(getSharedPreferences(PREFS, MODE_PRIVATE).getInt("model", 0));
        localModelSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("model", position).apply();
                updateModelStatus();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        root.addView(localModelSpinner, lpMatch());
        modelStatus = text("檢查模型中…", 12, false);
        modelStatus.setPadding(0, dp(4), 0, dp(4));
        root.addView(modelStatus);
        modelButton = button("下載／準備本機 Whisper 模型");
        modelButton.setOnClickListener(v -> downloadSelectedModel());
        root.addView(modelButton, lpMatch());
        TextView localNote = text("本機 Whisper 完全離線，免費 AAR 目前支援 arm64-v8a。WAV／MP3／FLAC 由 Whisper 直接解碼；M4A／AAC／OGG／Opus／MP4 由 Android MediaCodec 在手機本機轉成 PCM WAV。此路徑不再使用 FFmpegKit。", 12, false);
        localNote.setPadding(0, dp(2), 0, dp(10));
        root.addView(localNote);

        String savedKey = SecureKeyStore.load(this);
        keyToggleButton = button(savedKey.isEmpty() ? "Gemini API Key｜未設定 ▼" : "Gemini API Key｜已記憶 ▶");
        root.addView(keyToggleButton, lpMatch());

        keyPanel = new LinearLayout(this);
        keyPanel.setOrientation(LinearLayout.VERTICAL);
        keyPanel.setPadding(0, dp(2), 0, dp(8));

        apiKey = new EditText(this);
        apiKey.setSingleLine(true);
        apiKey.setHint("先到 AI Studio 建立 Key，再貼回這裡");
        apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKey.setText(savedKey);
        keyPanel.addView(apiKey, lpMatch());

        CheckBox showKey = new CheckBox(this);
        showKey.setText("顯示 API Key");
        showKey.setOnCheckedChangeListener((buttonView, checked) -> {
            apiKey.setInputType(InputType.TYPE_CLASS_TEXT | (checked ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            apiKey.setSelection(apiKey.length());
        });
        keyPanel.addView(showKey);

        Button aiStudio = button("前往 AI Studio 取得 API Key");
        aiStudio.setOnClickListener(v -> openAiStudio());
        Button pasteKey = button("從剪貼簿貼上並儲存");
        pasteKey.setOnClickListener(v -> pasteAndSaveKey());
        Button saveKey = button("儲存 Key");
        saveKey.setOnClickListener(v -> saveApiKey());
        Button testKey = button("測試 Key");
        testKey.setOnClickListener(v -> testApiKey());
        keyPanel.addView(adaptiveButtonRow(aiStudio, pasteKey));
        keyPanel.addView(adaptiveButtonRow(saveKey, testKey));
        TextView keyNote = text("Key 只加密保存在這台手機。已設定後此區預設收折，需要修改時再展開。", 12, false);
        keyPanel.addView(keyNote);
        root.addView(keyPanel, lpMatch());
        keyPanel.setVisibility(savedKey.isEmpty() ? View.VISIBLE : View.GONE);
        keyToggleButton.setOnClickListener(v -> toggleKeyPanel());

        root.addView(sectionLabel("轉錄方式"));
        modeSpinner = new Spinner(this);
        String[] modes = {"逐字稿 verbatim", "智慧逐字稿 smart（Gemini 閱讀優先）"};
        modeSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, modes));
        root.addView(modeSpinner, lpMatch());

        languageSpinner = new Spinner(this);
        String[] langs = {"自動偵測語言", "繁體中文／華語・台語", "英文", "日文"};
        languageSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, langs));
        root.addView(languageSpinner, lpMatch());

        diarization = new CheckBox(this);
        diarization.setText("多人講者辨識（需要 Gemini）");
        diarization.setChecked(false);
        root.addView(diarization);
        timestamps = new CheckBox(this);
        timestamps.setText("字詞級時間戳（需要 Gemini；Whisper 仍會提供段落時間）");
        timestamps.setChecked(false);
        root.addView(timestamps);

        modeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                boolean smart = position == 1;
                diarization.setEnabled(!smart);
                timestamps.setEnabled(!smart);
                if (smart) {
                    diarization.setChecked(false);
                    timestamps.setChecked(false);
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        startButton = button("開始自動辨識");
        cancelButton = button("取消");
        cancelButton.setEnabled(false);
        startButton.setOnClickListener(v -> startTranscription());
        cancelButton.setOnClickListener(v -> {
            cancelFlag.set(true);
            setStage("已要求取消；目前工作安全結束後停止。", true);
        });
        root.addView(adaptiveButtonRow(startButton, cancelButton));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setIndeterminate(false);
        progress.setProgress(0);
        progress.setVisibility(View.GONE);
        root.addView(progress, lpMatch());
        progressDetail = text("待命", 12, false);
        progressDetail.setVisibility(View.GONE);
        root.addView(progressDetail);
        stage = text("待命", 14, true);
        stage.setPadding(0, dp(4), 0, dp(8));
        root.addView(stage);

        root.addView(sectionLabel("轉錄結果"));
        result = new EditText(this);
        result.setGravity(Gravity.TOP | Gravity.START);
        result.setTextSize(16);
        result.setMinLines(16);
        result.setVerticalScrollBarEnabled(true);
        result.setScrollbarFadingEnabled(false);
        result.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        result.setHint("完成後會依辨識時間順序顯示逐字稿，可在框內上下完整預覽並直接修正。\n\nWhisper 保留段落時間；Gemini 有時間標記時會依時間排序顯示。");
        int previewHeight = Math.max(dp(400), getResources().getDisplayMetrics().heightPixels * 50 / 100);
        root.addView(result, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, previewHeight));

        Button topPreview = button("↑ 最前");
        Button bottomPreview = button("↓ 最後");
        topPreview.setOnClickListener(v -> { result.setSelection(0); result.scrollTo(0, 0); });
        bottomPreview.setOnClickListener(v -> result.setSelection(result.length()));
        root.addView(adaptiveButtonRow(topPreview, bottomPreview));

        Button saveBundle = button("儲存本次（原音檔＋TXT＋Markdown）");
        saveBundle.setOnClickListener(v -> saveSessionBundle());
        root.addView(saveBundle, lpMatch());

        Button saveTxt = button("手動另存 TXT");
        Button saveMd = button("手動另存 Markdown");
        saveTxt.setOnClickListener(v -> createDocument(false));
        saveMd.setOnClickListener(v -> createDocument(true));
        root.addView(adaptiveButtonRow(saveTxt, saveMd));

        saveFolderHint = text("自動存檔：Documents/Junba AI Transcriber/今天日期/日期時間/；原始音檔檔名不更動。", 11, false);
        saveFolderHint.setPadding(0, dp(4), 0, dp(2));
        root.addView(saveFolderHint);

        TextView limits = text("自動規則：已下載本機模型＋不要求 Gemini 專屬功能 → 優先離線 Whisper。Gemini 先用專用 Transcribe；錄音太長或遇到 98,304 token／模型長度限制時，自動改用 Gemini 3.8 Flash 長音訊模式，同一檔案只上傳一次。超長輸出才以同一雲端檔案分時段取得並自動合併，不先切實體音檔。", 12, false);
        limits.setPadding(0, dp(10), 0, 0);
        root.addView(limits);
        return scroll;
    }

    private void chooseAudio() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        startActivityForResult(intent, REQ_AUDIO);
    }

    private void openAiStudio() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(AI_STUDIO_URL)));
            toast("在 AI Studio 建立／複製 Key 後，回到 App 按「從剪貼簿貼上並儲存」");
        } catch (Exception e) {
            toast("無法開啟瀏覽器：" + e.getMessage());
        }
    }

    private void pasteAndSaveKey() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = cm == null ? null : cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) { toast("剪貼簿目前沒有內容"); return; }
        CharSequence cs = clip.getItemAt(0).coerceToText(this);
        String key = cs == null ? "" : cs.toString().trim();
        if (key.isEmpty()) { toast("剪貼簿內容是空的"); return; }
        apiKey.setText(key);
        saveApiKey();
    }

    private void saveApiKey() {
        try {
            String key = apiKey.getText().toString().trim();
            SecureKeyStore.save(this, key);
            toast("API Key 已加密儲存在這一台手機");
            if (!key.isEmpty()) collapseKeyPanel();
        } catch (Exception e) {
            toast("Key 儲存失敗：" + e.getMessage());
        }
    }

    private void toggleKeyPanel() {
        if (keyPanel == null || keyToggleButton == null) return;
        boolean show = keyPanel.getVisibility() != View.VISIBLE;
        keyPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        boolean hasKey = !SecureKeyStore.load(this).isEmpty();
        if (hasKey) keyToggleButton.setText(show ? "Gemini API Key｜已記憶 ▲" : "Gemini API Key｜已記憶 ▶");
        else keyToggleButton.setText(show ? "Gemini API Key｜未設定 ▲" : "Gemini API Key｜未設定 ▼");
    }

    private void collapseKeyPanel() {
        runOnUiThread(() -> {
            if (keyPanel != null) keyPanel.setVisibility(View.GONE);
            if (keyToggleButton != null) keyToggleButton.setText("Gemini API Key｜已記憶 ▶");
        });
    }

    private void testApiKey() {
        String key = apiKey.getText().toString().trim();
        if (key.isEmpty()) { toast("請先輸入 API Key"); return; }
        setBusy(true);
        setProgressState("測試 Gemini API Key…", 20, "約 20–90%", false);
        executor.submit(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://generativelanguage.googleapis.com/v1beta/models").openConnection();
                c.setRequestMethod("GET");
                c.setConnectTimeout(15_000);
                c.setReadTimeout(20_000);
                c.setRequestProperty("x-goog-api-key", key);
                int code = c.getResponseCode();
                String body = readResponse(c);
                c.disconnect();
                if (code >= 200 && code < 300) {
                    SecureKeyStore.save(this, key);
                    setProgressState("Gemini API Key 測試成功，已記憶在此手機。", 100, "100%", false);
                    collapseKeyPanel();
                } else {
                    setStage("API Key 測試失敗 HTTP " + code + "：" + shortText(body, 180), true);
                }
            } catch (Exception e) {
                setStage("API Key 測試失敗：" + e.getMessage(), true);
            } finally {
                setBusy(false);
            }
        });
    }

    private void createDocument(boolean markdown) {
        String text = result.getText().toString().trim();
        if (text.isEmpty()) { toast("目前沒有可儲存的逐字稿"); return; }
        lastTranscript = text;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(markdown ? "text/markdown" : "text/plain");
        String stem = audioName.isEmpty() ? "逐字稿" : audioName.replaceFirst("\\.[^.]+$", "");
        intent.putExtra(Intent.EXTRA_TITLE, stem + (markdown ? "_逐字稿.md" : "_逐字稿.txt"));
        startActivityForResult(intent, markdown ? REQ_SAVE_MD : REQ_SAVE_TXT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_AUDIO) {
            audioUri = uri;
            audioName = displayName(uri);
            beginNewSessionStamp();
            audioLabel.setText(audioName + "\n" + safeMime(uri));
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
        } else if (requestCode == REQ_SAVE_TXT || requestCode == REQ_SAVE_MD) {
            boolean md = requestCode == REQ_SAVE_MD;
            String payload = md ? markdownText(result.getText().toString().trim()) : result.getText().toString().trim() + "\n";
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new Exception("無法開啟輸出檔");
                out.write(payload.getBytes(StandardCharsets.UTF_8));
                toast(md ? "Markdown 已儲存" : "TXT 已儲存");
            } catch (Exception e) {
                toast("儲存失敗：" + e.getMessage());
            }
        }
    }

    private void startTranscription() {
        if (audioUri == null) { toast("請先選擇錄音檔"); return; }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt("engine", engineSpinner.getSelectedItemPosition())
                .putInt("model", localModelSpinner.getSelectedItemPosition())
                .apply();
        String key = apiKey.getText().toString().trim();
        int engine = resolveEngine(key);
        if (engine == 0) return;
        cancelFlag.set(false);
        failoverUsed.set(false);
        setBusy(true);
        setProgressState("準備辨識…", 3, "約 3–10%", false);
        result.setText("");
        if (engine == 1) startLocalWhisper(); else startGemini(key);
    }

    // 0=不能開始，1=Whisper，2=Gemini
    private int resolveEngine(String key) {
        int choice = engineSpinner.getSelectedItemPosition();
        boolean localReady = supportsLocalWhisper() && localAudioSupported(audioName) && selectedModelFile().isFile();
        boolean geminiNeeded = modeSpinner.getSelectedItemPosition() == 1 || diarization.isChecked() || timestamps.isChecked();

        if (choice == 1) {
            if (!supportsLocalWhisper()) { setStage("此手機 ABI 不支援目前的本機 Whisper AAR；請改用自動或 Gemini。", true); return 0; }
            if (!localAudioSupported(audioName)) { setStage("目前音訊格式不在本機 Whisper 支援清單；請改用自動或 Gemini。", true); return 0; }
            if (!selectedModelFile().isFile()) { setStage("尚未下載本機 Whisper 模型，請先按「下載／準備本機 Whisper 模型」。", true); return 0; }
            if (geminiNeeded) setStage("提示：本機 Whisper 不提供多人講者／字詞級時間戳／smart；將以段落時間逐字稿執行。", true);
            return 1;
        }
        if (choice == 2) {
            if (key.isEmpty()) { setStage("Gemini 模式需要 API Key。請按「前往 AI Studio 取得 API Key」。", true); return 0; }
            return 2;
        }

        // 自動混合：Gemini 專屬功能優先；其餘優先離線 Whisper。
        if (geminiNeeded) {
            if (!key.isEmpty()) return 2;
            if (localReady) {
                setStage("沒有 Gemini Key，已自動改用本機 Whisper；多人講者／字詞級時間戳將略過。", true);
                return 1;
            }
            setStage("目前設定需要 Gemini，但尚未設定 Key；也沒有可用的本機 Whisper 模型。", true);
            return 0;
        }
        if (localReady) return 1;
        if (!key.isEmpty()) {
            if (!localAudioSupported(audioName)) setStage("目前音檔格式不在本機轉碼支援清單，已自動改用 Gemini。", false);
            else if (!selectedModelFile().isFile()) setStage("尚未下載本機 Whisper 模型，已自動改用 Gemini。", false);
            return 2;
        }
        if (!supportsLocalWhisper()) setStage("此手機本機 Whisper 不支援，且尚未設定 Gemini API Key。", true);
        else if (!localAudioSupported(audioName)) setStage("目前格式無法走本機 Whisper；請先設定 Gemini API Key。", true);
        else setStage("請先下載本機 Whisper 模型，或設定 Gemini API Key。", true);
        return 0;
    }

    private void startLocalWhisper() {
        setProgressState("本機 Whisper：準備音檔…", 8, "約 8–20%", false);
        executor.submit(() -> {
            File temp = null;
            try {
                temp = copyUriToCache(audioUri);
                if (cancelFlag.get()) throw new InterruptedException("已取消");
                File finalTemp = temp;
                setProgressState("本機 Whisper 辨識中（音訊不會上傳）…", 35, "約 35–95%", false);
                LocalWhisperEngine.transcribeAsync(this, selectedModelFile().getAbsolutePath(), temp.getAbsolutePath(), whisperLanguageCode(), new WhisperCallback() {
                    @Override public void onSuccess(String text) {
                        if (!cancelFlag.get()) {
                            lastEngineLabel = "Whisper.cpp / " + selectedModelId();
                            lastTranscript = normalizeTimeline(text == null ? "" : text);
                            runOnUiThread(() -> { result.setText(lastTranscript); result.setSelection(0); });
                            setProgressState("本機 Whisper 轉錄完成", 100, "100%", false);
                        } else {
                            setStage("已取消", true);
                        }
                        finalTemp.delete();
                        setBusy(false);
                    }
                    @Override public void onError(String message) {
                        finalTemp.delete();
                        String key = apiKey.getText().toString().trim();
                        if (!cancelFlag.get() && engineSpinner.getSelectedItemPosition() == 0 && !key.isEmpty() && failoverUsed.compareAndSet(false, true)) {
                            setStage("本機 Whisper 失敗，已自動改用 Gemini：" + message, true);
                            startGemini(key);
                        } else {
                            setStage("本機 Whisper 失敗：" + message, true);
                            setBusy(false);
                        }
                    }
                });
                temp = null; // callback 負責刪除
            } catch (InterruptedException e) {
                setStage("已取消", true);
                setBusy(false);
            } catch (Exception e) {
                setStage("本機 Whisper 失敗：" + e.getMessage(), true);
                setBusy(false);
            } finally {
                if (temp != null) temp.delete();
            }
        });
    }

    private void startGemini(String key) {
        if (key.isEmpty()) { setStage("Gemini 需要 API Key。", true); setBusy(false); return; }
        try { SecureKeyStore.save(this, key); } catch (Exception ignored) {}
        setProgressState("Gemini：準備整份音檔…", 8, "約 8–15%", false);
        executor.submit(() -> {
            File temp = null;
            String uploadedName = null;
            boolean handedOff = false;
            try {
                temp = copyUriToCache(audioUri);
                if (cancelFlag.get()) throw new InterruptedException("已取消");
                String mime = safeMime(audioUri);
                long durationMs = audioDurationMs(temp);

                setProgressState("上傳整份音訊至 Gemini Files API…", 15, "15–45%", false);
                JSONObject uploaded = uploadFile(key, temp, mime);
                JSONObject fileObj = uploaded.optJSONObject("file");
                if (fileObj == null) throw new Exception("Files API 沒有回傳 file 資訊：" + uploaded);
                String fileUri = fileObj.optString("uri", "");
                uploadedName = fileObj.optString("name", "");
                if (fileUri.isEmpty()) throw new Exception("Files API 沒有回傳 file URI");
                if (!uploadedName.isEmpty()) waitUntilFileReady(key, uploadedName);
                if (cancelFlag.get()) throw new InterruptedException("已取消");

                String text;
                boolean longMode = shouldUseLongAudioMode(durationMs);
                if (!longMode) {
                    try {
                        setProgressState("Gemini 3.5 Transcribe 辨識中…", 58, "約 58–95%", false);
                        JSONObject response = transcribe(key, fileUri, mime);
                        lastRawResponse = response.toString(2);
                        text = extractOutputText(response).trim();
                        lastEngineLabel = TRANSCRIBE_MODEL;
                    } catch (Exception first) {
                        if (!isLengthLimitError(first)) throw first;
                        longMode = true;
                        setProgressState("專用 Transcribe 已達長度／token 上限，改用 Gemini 3.8 Flash 長音訊模式…", 58, "約 58–95%", true);
                        text = transcribeLongAudio(key, fileUri, mime, durationMs);
                        lastEngineLabel = LONG_AUDIO_MODEL + " / Files API";
                    }
                } else {
                    setProgressState("Gemini 3.8 Flash 長音訊模式：整檔辨識中…", 58, "約 58–95%", false);
                    text = transcribeLongAudio(key, fileUri, mime, durationMs);
                    lastEngineLabel = LONG_AUDIO_MODEL + " / Files API";
                }

                if (text == null || text.trim().isEmpty()) {
                    text = "【Gemini 沒有回傳可解析的逐字稿】\n\n" + lastRawResponse;
                }
                lastTranscript = normalizeTimeline(text.trim());
                String finalText = lastTranscript;
                runOnUiThread(() -> { result.setText(finalText); result.setSelection(0); });
                setProgressState(longMode ? "Gemini 長音訊轉錄完成" : "Gemini 轉錄完成", 100, "100%", false);
            } catch (InterruptedException e) {
                setStage("已取消", true);
            } catch (Exception e) {
                boolean canFallbackLocal = !cancelFlag.get() && engineSpinner.getSelectedItemPosition() == 0 &&
                        supportsLocalWhisper() && localAudioSupported(audioName) && selectedModelFile().isFile() &&
                        failoverUsed.compareAndSet(false, true);
                if (canFallbackLocal) {
                    setStage("Gemini 失敗，已自動改用本機 Whisper（進階 Gemini 功能將略過）：" + e.getMessage(), true);
                    final boolean hadUpload = uploadedName != null && !uploadedName.isEmpty();
                    if (hadUpload) { try { deleteUploaded(key, uploadedName); } catch (Exception ignored) {} }
                    if (temp != null) temp.delete();
                    uploadedName = null;
                    temp = null;
                    handedOff = true;
                    startLocalWhisper();
                    return;
                } else {
                    setStage("Gemini 失敗：" + e.getClass().getSimpleName() + "：" + e.getMessage(), true);
                }
            } finally {
                if (uploadedName != null && !uploadedName.isEmpty()) {
                    try { deleteUploaded(key, uploadedName); } catch (Exception ignored) {}
                }
                if (temp != null) temp.delete();
                if (!handedOff) setBusy(false);
            }
        });
    }

    private boolean shouldUseLongAudioMode(long durationMs) {
        if (durationMs <= 0) return false; // unknown: try dedicated Transcribe first, then fail over automatically
        long safeLimit = (diarization.isChecked() || timestamps.isChecked())
                ? 25L * 60L * 1000L
                : 45L * 60L * 1000L;
        return durationMs > safeLimit;
    }

    private boolean isLengthLimitError(Exception e) {
        String m = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        return m.contains("98304") || m.contains("token count exceeds") ||
                m.contains("maximum number of tokens") || m.contains("context") ||
                m.contains("too long") || m.contains("audio limit");
    }

    private long audioDurationMs(File file) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(file.getAbsolutePath());
            String v = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return v == null ? -1L : Long.parseLong(v);
        } catch (Exception ignored) {
            return -1L;
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    private void waitUntilFileReady(String key, String name) throws Exception {
        String path = name.startsWith("files/") ? name : "files/" + name;
        for (int i = 0; i < 40; i++) {
            if (cancelFlag.get()) throw new InterruptedException("已取消");
            if (i == 0 || i % 4 == 0) setProgressState("Gemini 雲端檔案準備中…", 50, "約 50–57%", false);
            HttpURLConnection c = (HttpURLConnection) new URL("https://generativelanguage.googleapis.com/v1beta/" + path).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(15_000);
            c.setReadTimeout(20_000);
            c.setRequestProperty("x-goog-api-key", key);
            int code = c.getResponseCode();
            String body = readResponse(c);
            c.disconnect();
            if (code < 200 || code >= 300) throw new Exception("查詢 Gemini 檔案狀態失敗 HTTP " + code + "：" + shortText(body, 240));
            JSONObject o = new JSONObject(body);
            String state = o.optString("state", "ACTIVE");
            if ("ACTIVE".equalsIgnoreCase(state) || state.isEmpty()) return;
            if ("FAILED".equalsIgnoreCase(state)) throw new Exception("Gemini 檔案處理失敗：" + shortText(body, 240));
            Thread.sleep(1500L);
        }
        throw new Exception("Gemini 檔案處理逾時，請稍後重試");
    }

    private String transcribeLongAudio(String key, String fileUri, String mime, long durationMs) throws Exception {
        if (durationMs > LONG_AUDIO_RANGE_THRESHOLD_MS) {
            setStage("超長錄音：同一雲端檔案分時段取得逐字稿（不重新切檔／不上傳多份）…", false);
            return transcribeLongAudioRanges(key, fileUri, mime, durationMs);
        }

        JSONObject response = generateLongAudio(key, fileUri, mime, buildLongAudioPrompt(-1L, -1L));
        lastRawResponse = response.toString(2);
        String text = extractGenerateText(response).trim();
        if (isMaxTokensResponse(response) && durationMs > 0) {
            setStage("整檔輸出達上限，改由同一雲端檔案分時段取得並自動合併…", true);
            return transcribeLongAudioRanges(key, fileUri, mime, durationMs);
        }
        return text;
    }

    private String transcribeLongAudioRanges(String key, String fileUri, String mime, long durationMs) throws Exception {
        long total = durationMs > 0 ? durationMs : LONG_AUDIO_RANGE_MS;
        StringBuilder merged = new StringBuilder();
        int part = 0;
        for (long start = 0; start < total; start += LONG_AUDIO_RANGE_MS) {
            if (cancelFlag.get()) throw new InterruptedException("已取消");
            long end = Math.min(total, start + LONG_AUDIO_RANGE_MS);
            part++;
            final int partNo = part;
            int totalParts = (int) Math.max(1L, (total + LONG_AUDIO_RANGE_MS - 1L) / LONG_AUDIO_RANGE_MS);
            int pct = 58 + (int) Math.min(34L, (34L * (partNo - 1L)) / totalParts);
            setProgressState("Gemini 長音訊模式：取得第 " + partNo + "/" + totalParts + " 段 " + formatClock(start) + "–" + formatClock(end) + "…", pct, "約 " + pct + "–95%", false);
            JSONObject response = generateLongAudio(key, fileUri, mime, buildLongAudioPrompt(start, end));
            lastRawResponse = response.toString(2);
            String text = extractGenerateText(response).trim();
            if (text.isEmpty()) throw new Exception("第 " + partNo + " 段沒有回傳文字");
            if (merged.length() > 0) merged.append("\n\n");
            merged.append(text);
        }
        return merged.toString();
    }

    private String buildLongAudioPrompt(long startMs, long endMs) {
        boolean smart = modeSpinner.getSelectedItemPosition() == 1;
        StringBuilder p = new StringBuilder();
        p.append("請將這份錄音轉成繁體中文介面的完整逐字稿。保留原本語言，不要摘要、不要漏掉有意義內容。\n");
        if (startMs >= 0 && endMs > startMs) {
            p.append("只處理音訊時間 ").append(formatClock(startMs)).append(" 到 ").append(formatClock(endMs)).append("，不要重複範圍外內容。\n");
        }
        if (smart) {
            p.append("使用智慧逐字稿：可移除明顯贅詞與無意義重複，修正常見口語斷句與標點，但不可改變原意。\n");
        } else {
            p.append("使用 verbatim 逐字稿：盡量保留原話、語助詞與口語表達。\n");
        }
        if (diarization.isChecked()) p.append("若能可靠辨識不同說話者，請以「講者1：」「講者2：」標示；不確定時不要亂猜身分。\n");
        p.append("每一個自然段前都請標示 [HH:MM:SS] 開始時間，必須依時間先後順序輸出；若有講者標籤，放在時間後方。不要倒序或重複時間區段。\n");
        String lang = geminiLanguageCode();
        if (!lang.isEmpty()) p.append("主要語言提示：").append(lang).append("。若實際錄音有多語切換，仍照實轉錄。\n");
        p.append("直接輸出逐字稿正文，不要加入分析、前言或結語。");
        return p.toString();
    }

    private JSONObject generateLongAudio(String key, String fileUri, String mime, String prompt) throws Exception {
        URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + LONG_AUDIO_MODEL + ":generateContent");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(600_000);
        conn.setDoOutput(true);
        conn.setRequestProperty("x-goog-api-key", key);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

        JSONObject fileData = new JSONObject().put("file_uri", fileUri).put("mime_type", mime);
        JSONArray parts = new JSONArray()
                .put(new JSONObject().put("file_data", fileData))
                .put(new JSONObject().put("text", prompt));
        JSONObject content = new JSONObject().put("role", "user").put("parts", parts);
        JSONObject generation = new JSONObject()
                .put("temperature", 0.0)
                .put("maxOutputTokens", 64000)
                .put("thinkingConfig", new JSONObject().put("thinkingLevel", "low"));
        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(content))
                .put("generationConfig", generation);

        writeUtf8(conn, body.toString());
        int code = conn.getResponseCode();
        String response = readResponse(conn);
        conn.disconnect();
        if (code < 200 || code >= 300) throw new Exception("Gemini 長音訊模式失敗 HTTP " + code + "：" + response);
        return new JSONObject(response);
    }

    private boolean isMaxTokensResponse(JSONObject root) {
        JSONArray candidates = root.optJSONArray("candidates");
        if (candidates == null) return false;
        for (int i = 0; i < candidates.length(); i++) {
            JSONObject c = candidates.optJSONObject(i);
            if (c != null && "MAX_TOKENS".equalsIgnoreCase(c.optString("finishReason", ""))) return true;
        }
        return false;
    }

    private String extractGenerateText(JSONObject root) {
        JSONArray candidates = root.optJSONArray("candidates");
        if (candidates == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < candidates.length(); i++) {
            JSONObject c = candidates.optJSONObject(i);
            JSONObject content = c == null ? null : c.optJSONObject("content");
            JSONArray parts = content == null ? null : content.optJSONArray("parts");
            if (parts == null) continue;
            for (int j = 0; j < parts.length(); j++) {
                JSONObject part = parts.optJSONObject(j);
                if (part == null) continue;
                String t = part.optString("text", "");
                if (!t.isEmpty()) b.append(t).append('\n');
            }
        }
        return b.toString();
    }

    private static String formatClock(long ms) {
        long total = Math.max(0L, ms) / 1000L;
        long h = total / 3600L;
        long m = (total % 3600L) / 60L;
        long s = total % 60L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", h, m, s);
    }

    private void downloadSelectedModel() {
        if (!supportsLocalWhisper()) {
            setStage("此手機不是 arm64-v8a，本版免費 Whisper AAR 無法使用。", true);
            return;
        }
        String id = selectedModelId();
        File target = modelFile(id);
        if (target.isFile() && target.length() > 20_000_000L) {
            toast("模型已存在：" + target.getName());
            updateModelStatus();
            return;
        }
        cancelFlag.set(false);
        setBusy(true);
        modelButton.setEnabled(false);
        setProgressState("下載 Whisper " + id + " 模型…", 0, "0%", false);
        executor.submit(() -> {
            File part = new File(target.getParentFile(), target.getName() + ".part");
            try {
                if (!target.getParentFile().exists() && !target.getParentFile().mkdirs()) throw new Exception("無法建立模型資料夾");
                URL u = new URL(MODEL_BASE_URL + target.getName());
                HttpURLConnection c = (HttpURLConnection) u.openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(30_000);
                c.setReadTimeout(120_000);
                int code = c.getResponseCode();
                if (code < 200 || code >= 300) throw new Exception("下載失敗 HTTP " + code);
                long total = c.getContentLengthLong();
                try (InputStream in = new BufferedInputStream(c.getInputStream()); OutputStream out = new BufferedOutputStream(new FileOutputStream(part))) {
                    byte[] buf = new byte[256 * 1024];
                    long done = 0;
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        if (cancelFlag.get()) throw new InterruptedException("已取消");
                        out.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            int pct = (int) Math.min(99, done * 100L / total);
                            setExactProgress(pct, "下載 Whisper " + id + " 模型");
                        }
                    }
                } finally { c.disconnect(); }
                if (!part.renameTo(target)) {
                    try (InputStream in = new FileInputStream(part); OutputStream out = new FileOutputStream(target)) {
                        byte[] b = new byte[128 * 1024]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n);
                    }
                    part.delete();
                }
                setProgressState("Whisper " + id + " 模型下載完成，可離線使用。", 100, "100%", false);
            } catch (InterruptedException e) {
                part.delete();
                setStage("模型下載已取消", true);
            } catch (Exception e) {
                part.delete();
                setStage("模型下載失敗：" + e.getMessage(), true);
            } finally {
                runOnUiThread(() -> {
                    modelButton.setEnabled(true);
                    updateModelStatus();
                });
                setBusy(false);
            }
        });
    }

    private boolean supportsLocalWhisper() {
        return Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a");
    }

    private boolean localAudioSupported(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.endsWith(".wav") || n.endsWith(".mp3") || n.endsWith(".flac") ||
                n.endsWith(".m4a") || n.endsWith(".aac") || n.endsWith(".ogg") ||
                n.endsWith(".opus") || n.endsWith(".mp4");
    }

    private String selectedModelId() {
        int p = localModelSpinner == null ? 0 : localModelSpinner.getSelectedItemPosition();
        if (p == 1) return "tiny";
        if (p == 2) return "base";
        if (p == 3) return "small";
        return recommendedModelId();
    }

    private String recommendedModelId() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            long gb = mi.totalMem / (1024L * 1024L * 1024L);
            return gb < 5 ? "tiny" : "base";
        } catch (Exception e) {
            return "base";
        }
    }

    private File modelFile(String id) {
        File dir = getExternalFilesDir("models");
        if (dir == null) dir = new File(getFilesDir(), "models");
        return new File(dir, "ggml-" + id + ".bin");
    }

    private File selectedModelFile() { return modelFile(selectedModelId()); }

    private void updateModelStatus() {
        if (modelStatus == null) return;
        String id = selectedModelId();
        File f = modelFile(id);
        String state = f.isFile() ? String.format(Locale.ROOT, "已下載 %.0f MB", f.length() / 1024.0 / 1024.0) : "尚未下載";
        modelStatus.setText("自動建議：" + recommendedModelId() + "｜目前：" + id + "｜" + state);
    }

    private String deviceSummary() {
        String ram = "?";
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            ram = String.format(Locale.ROOT, "%.1f GB", mi.totalMem / 1024.0 / 1024.0 / 1024.0);
        } catch (Exception ignored) {}
        return "裝置：" + Build.MODEL + "｜RAM " + ram + "｜ABI " + String.join(",", Build.SUPPORTED_ABIS) + "｜本機 Whisper " + (supportsLocalWhisper() ? "可用" : "不可用");
    }

    private File copyUriToCache(Uri uri) throws Exception {
        String ext = ".audio";
        String n = displayName(uri);
        int dot = n.lastIndexOf('.');
        if (dot >= 0 && dot < n.length() - 1) ext = n.substring(dot);
        File dst = File.createTempFile("jba_", ext, getCacheDir());
        try (InputStream in = new BufferedInputStream(getContentResolver().openInputStream(uri));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dst))) {
            if (in == null) throw new Exception("無法讀取選取的音檔");
            byte[] buf = new byte[256 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) {
                if (cancelFlag.get()) throw new InterruptedException("已取消");
                out.write(buf, 0, r);
            }
        }
        return dst;
    }

    private JSONObject uploadFile(String key, File file, String mime) throws Exception {
        URL startUrl = new URL("https://generativelanguage.googleapis.com/upload/v1beta/files");
        HttpURLConnection start = (HttpURLConnection) startUrl.openConnection();
        start.setRequestMethod("POST");
        start.setConnectTimeout(30_000);
        start.setReadTimeout(60_000);
        start.setDoOutput(true);
        start.setRequestProperty("x-goog-api-key", key);
        start.setRequestProperty("X-Goog-Upload-Protocol", "resumable");
        start.setRequestProperty("X-Goog-Upload-Command", "start");
        start.setRequestProperty("X-Goog-Upload-Header-Content-Length", String.valueOf(file.length()));
        start.setRequestProperty("X-Goog-Upload-Header-Content-Type", mime);
        start.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        JSONObject meta = new JSONObject().put("file", new JSONObject().put("display_name", audioName.isEmpty() ? "audio" : audioName));
        writeUtf8(start, meta.toString());
        int sc = start.getResponseCode();
        if (sc < 200 || sc >= 300) throw new Exception("建立上傳工作失敗 HTTP " + sc + "：" + readResponse(start));
        String uploadUrl = start.getHeaderField("X-Goog-Upload-URL");
        if (uploadUrl == null || uploadUrl.isEmpty()) uploadUrl = start.getHeaderField("x-goog-upload-url");
        start.disconnect();
        if (uploadUrl == null || uploadUrl.isEmpty()) throw new Exception("Gemini 未回傳 upload URL");

        HttpURLConnection up = (HttpURLConnection) new URL(uploadUrl).openConnection();
        up.setRequestMethod("POST");
        up.setConnectTimeout(30_000);
        up.setReadTimeout(180_000);
        up.setDoOutput(true);
        up.setFixedLengthStreamingMode(file.length());
        up.setRequestProperty("Content-Length", String.valueOf(file.length()));
        up.setRequestProperty("X-Goog-Upload-Offset", "0");
        up.setRequestProperty("X-Goog-Upload-Command", "upload, finalize");
        up.setRequestProperty("Content-Type", mime);
        try (InputStream in = new BufferedInputStream(new FileInputStream(file)); OutputStream out = new BufferedOutputStream(up.getOutputStream())) {
            byte[] buf = new byte[256 * 1024];
            int r;
            long sent = 0L;
            long totalBytes = Math.max(1L, file.length());
            while ((r = in.read(buf)) != -1) {
                if (cancelFlag.get()) throw new InterruptedException("已取消");
                out.write(buf, 0, r);
                sent += r;
                int pct = 15 + (int) Math.min(30L, sent * 30L / totalBytes);
                setExactProgress(pct, "Gemini 音訊上傳中");
            }
        }
        int code = up.getResponseCode();
        String body = readResponse(up);
        up.disconnect();
        if (code < 200 || code >= 300) throw new Exception("音訊上傳失敗 HTTP " + code + "：" + body);
        return new JSONObject(body);
    }

    private JSONObject transcribe(String key, String fileUri, String mime) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("https://generativelanguage.googleapis.com/v1beta/interactions").openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(240_000);
        conn.setDoOutput(true);
        conn.setRequestProperty("x-goog-api-key", key);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

        JSONObject audio = new JSONObject().put("type", "audio").put("uri", fileUri).put("mime_type", mime);
        JSONArray input = new JSONArray().put(audio);
        JSONObject tc = new JSONObject();
        if (modeSpinner.getSelectedItemPosition() == 1) {
            tc.put("mode", "smart");
        } else {
            JSONObject mode = new JSONObject().put("type", "verbatim");
            if (diarization.isChecked()) mode.put("diarization_mode", "speaker");
            if (timestamps.isChecked()) mode.put("timestamp_granularities", new JSONArray().put("word"));
            tc.put("mode", mode);
        }
        String lang = geminiLanguageCode();
        if (!lang.isEmpty()) tc.put("language_codes", new JSONArray().put(lang));

        JSONObject body = new JSONObject()
                .put("model", TRANSCRIBE_MODEL)
                .put("input", input)
                .put("generation_config", new JSONObject().put("transcription_config", tc));
        writeUtf8(conn, body.toString());
        int code = conn.getResponseCode();
        String response = readResponse(conn);
        conn.disconnect();
        if (code < 200 || code >= 300) throw new Exception("Gemini 轉錄失敗 HTTP " + code + "：" + response);
        return new JSONObject(response);
    }

    private void deleteUploaded(String key, String name) throws Exception {
        String path = name.startsWith("files/") ? name : "files/" + name;
        HttpURLConnection c = (HttpURLConnection) new URL("https://generativelanguage.googleapis.com/v1beta/" + path).openConnection();
        c.setRequestMethod("DELETE");
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("x-goog-api-key", key);
        try { c.getResponseCode(); } finally { c.disconnect(); }
    }

    private static void writeUtf8(HttpURLConnection c, String s) throws Exception {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
    }

    private static String readResponse(HttpURLConnection c) throws Exception {
        InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return "";
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) b.append(line).append('\n');
        }
        return b.toString().trim();
    }

    private String extractOutputText(JSONObject root) {
        String v = root.optString("output_text", "");
        if (!v.isEmpty()) return v;
        JSONArray outputs = root.optJSONArray("outputs");
        if (outputs != null) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < outputs.length(); i++) {
                JSONObject o = outputs.optJSONObject(i);
                if (o != null && "text".equalsIgnoreCase(o.optString("type"))) {
                    String t = o.optString("text", "");
                    if (!t.isEmpty()) b.append(t).append('\n');
                }
            }
            if (b.length() > 0) return b.toString();
        }
        JSONArray steps = root.optJSONArray("steps");
        if (steps != null) {
            for (int i = steps.length() - 1; i >= 0; i--) {
                JSONObject step = steps.optJSONObject(i);
                if (step == null) continue;
                JSONArray content = step.optJSONArray("content");
                if (content == null) continue;
                StringBuilder b = new StringBuilder();
                for (int j = 0; j < content.length(); j++) {
                    JSONObject part = content.optJSONObject(j);
                    if (part != null) {
                        String t = part.optString("text", "");
                        if (!t.isEmpty()) b.append(t).append('\n');
                    }
                }
                if (b.length() > 0) return b.toString();
            }
        }
        return findTextRecursive(root, 0);
    }

    private String findTextRecursive(Object obj, int depth) {
        if (obj == null || depth > 8) return "";
        if (obj instanceof JSONObject) {
            JSONObject o = (JSONObject) obj;
            String direct = o.optString("text", "");
            if (!direct.isEmpty()) return direct;
            JSONArray names = o.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String key = names.optString(i);
                    String found = findTextRecursive(o.opt(key), depth + 1);
                    if (!found.isEmpty()) return found;
                }
            }
        } else if (obj instanceof JSONArray) {
            JSONArray a = (JSONArray) obj;
            for (int i = 0; i < a.length(); i++) {
                String found = findTextRecursive(a.opt(i), depth + 1);
                if (!found.isEmpty()) return found;
            }
        }
        return "";
    }

    private String markdownText(String transcript) {
        String stem = audioName.isEmpty() ? "逐字稿" : audioName.replaceFirst("\\.[^.]+$", "");
        return "# " + stem + "_逐字稿\n\n" +
                "- 來源音檔：" + (audioName.isEmpty() ? "—" : audioName) + "\n" +
                "- 辨識引擎：" + lastEngineLabel + "\n\n" +
                "## 逐字稿\n\n" + transcript + "\n";
    }

    private String geminiLanguageCode() {
        switch (languageSpinner.getSelectedItemPosition()) {
            case 1: return "zh-TW";
            case 2: return "en-US";
            case 3: return "ja-JP";
            default: return "";
        }
    }

    private String whisperLanguageCode() {
        switch (languageSpinner.getSelectedItemPosition()) {
            case 1: return "zh";
            case 2: return "en";
            case 3: return "ja";
            default: return "";
        }
    }

    private String safeMime(Uri uri) {
        String m = getContentResolver().getType(uri);
        if (m == null || m.trim().isEmpty()) {
            String n = displayName(uri).toLowerCase(Locale.ROOT);
            if (n.endsWith(".m4a")) return "audio/m4a";
            if (n.endsWith(".mp3")) return "audio/mpeg";
            if (n.endsWith(".wav")) return "audio/wav";
            if (n.endsWith(".aac")) return "audio/aac";
            if (n.endsWith(".flac")) return "audio/flac";
            if (n.endsWith(".ogg")) return "audio/ogg";
            if (n.endsWith(".opus")) return "audio/opus";
            if (n.endsWith(".webm")) return "audio/webm";
            if (n.endsWith(".aiff") || n.endsWith(".aif")) return "audio/aiff";
            if (n.endsWith(".mp4")) return "audio/mp4";
            return "application/octet-stream";
        }
        return m;
    }

    private String displayName(Uri uri) {
        String name = "audio";
        try (android.database.Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {}
        return name == null ? "audio" : name;
    }

    private void setBusy(boolean busy) {
        busyState.set(busy);
        runOnUiThread(() -> {
            progress.setVisibility(busy ? View.VISIBLE : View.GONE);
            if (progressDetail != null) progressDetail.setVisibility(busy ? View.VISIBLE : View.GONE);
            startButton.setEnabled(!busy);
            cancelButton.setEnabled(busy);
            modelButton.setEnabled(!busy);
        });
        if (busy) {
            operationStartedMs = SystemClock.elapsedRealtime();
            startHeartbeat();
        } else {
            heartbeatRunnable = null;
        }
    }

    private void startHeartbeat() {
        runOnUiThread(() -> {
            if (progressDetail == null) return;
            heartbeatRunnable = new Runnable() {
                @Override public void run() {
                    if (!busyState.get() || heartbeatRunnable != this) return;
                    long elapsed = SystemClock.elapsedRealtime() - operationStartedMs;
                    String range = progressRangeText == null || progressRangeText.isEmpty() ? "" : "｜進度 " + progressRangeText;
                    progressDetail.setText("仍在執行" + range + "｜已執行 " + formatElapsed(elapsed));
                    progressDetail.postDelayed(this, 1000L);
                }
            };
            progressDetail.post(heartbeatRunnable);
        });
    }

    private void setProgressState(String message, int pct, String rangeText, boolean warn) {
        int p = Math.max(0, Math.min(100, pct));
        progressRangeText = rangeText == null ? "" : rangeText;
        runOnUiThread(() -> {
            progress.setIndeterminate(false);
            progress.setProgress(p);
            stage.setText(message);
            stage.setTextColor(warn ? 0xFFB71C1C : 0xFF1565C0);
            if (progressDetail != null && busyState.get()) {
                long elapsed = SystemClock.elapsedRealtime() - operationStartedMs;
                String range = progressRangeText.isEmpty() ? "" : "｜進度 " + progressRangeText;
                progressDetail.setText("仍在執行" + range + "｜已執行 " + formatElapsed(elapsed));
            }
        });
    }

    private void setExactProgress(int pct, String label) {
        int p = Math.max(0, Math.min(100, pct));
        progressRangeText = p + "%";
        runOnUiThread(() -> {
            progress.setIndeterminate(false);
            progress.setProgress(p);
            if (label != null && !label.isEmpty()) stage.setText(label + "…");
        });
    }

    private static String formatElapsed(long ms) {
        long total = Math.max(0L, ms) / 1000L;
        long h = total / 3600L;
        long m = (total % 3600L) / 60L;
        long sec = total % 60L;
        return h > 0
                ? String.format(Locale.ROOT, "%02d:%02d:%02d", h, m, sec)
                : String.format(Locale.ROOT, "%02d:%02d", m, sec);
    }

    private void setStage(String message, boolean warn) {
        runOnUiThread(() -> {
            stage.setText(message);
            stage.setTextColor(warn ? 0xFFB71C1C : 0xFF1565C0);
        });
    }


    private static final Pattern TIMELINE_LINE = Pattern.compile("^\\s*\\[(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\]\\s*(.*)$");

    private String normalizeTimeline(String transcript) {
        if (transcript == null) return "";
        String[] lines = transcript.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        List<TimedLine> blocks = new ArrayList<>();
        StringBuilder current = null;
        long currentSeconds = -1L;
        StringBuilder prefix = new StringBuilder();

        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) {
                if (current != null && current.length() > 0) current.append('\n');
                continue;
            }
            Matcher m = TIMELINE_LINE.matcher(t);
            if (m.matches()) {
                if (current != null) blocks.add(new TimedLine(currentSeconds, current.toString().trim()));
                long a = Long.parseLong(m.group(1));
                long b = Long.parseLong(m.group(2));
                String c = m.group(3);
                currentSeconds = c == null ? a * 60L + b : a * 3600L + b * 60L + Long.parseLong(c);
                current = new StringBuilder(t);
            } else if (current != null) {
                current.append('\n').append(t);
            } else {
                if (prefix.length() > 0) prefix.append('\n');
                prefix.append(t);
            }
        }
        if (current != null) blocks.add(new TimedLine(currentSeconds, current.toString().trim()));
        if (blocks.size() < 2) return transcript.trim();

        Collections.sort(blocks, Comparator.comparingLong(x -> x.seconds));
        StringBuilder out = new StringBuilder();
        if (prefix.length() > 0) out.append(prefix).append("\n\n");
        for (TimedLine block : blocks) {
            if (out.length() > 0 && !out.toString().endsWith("\n\n")) out.append("\n\n");
            out.append(block.line);
        }
        return out.toString().trim();
    }

    private static final class TimedLine {
        final long seconds;
        final String line;
        TimedLine(long seconds, String line) {
            this.seconds = seconds;
            this.line = line;
        }
    }

    private void beginNewSessionStamp() {
        Date now = new Date();
        sessionDate = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(now);
        sessionStamp = new SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.ROOT).format(now);
        if (saveFolderHint != null) {
            saveFolderHint.setText("本次資料夾：Documents/Junba AI Transcriber/" + sessionDate + "/" + sessionStamp + "/");
        }
    }

    private void saveSessionBundle() {
        String transcript = result.getText().toString().trim();
        if (transcript.isEmpty()) { toast("目前沒有可儲存的逐字稿"); return; }
        if (audioUri == null) { toast("找不到原始音檔"); return; }
        if (sessionStamp.isEmpty()) beginNewSessionStamp();

        final String base = audioName.isEmpty() ? "逐字稿" : audioName.replaceFirst("\\.[^.]+$", "");
        final String txt = transcript + "\n";
        final String md = markdownText(transcript);
        final String relative = Environment.DIRECTORY_DOCUMENTS + "/Junba AI Transcriber/" + sessionDate + "/" + sessionStamp + "/";

        setBusy(true);
        setProgressState("建立今日日期／時間資料夾…", 5, "約 5–15%", false);
        executor.submit(() -> {
            try {
                saveOriginalAudio(relative);
                setProgressState("儲存 TXT…", 72, "約 72–82%", false);
                saveTextPublic(relative, base + ".txt", "text/plain", txt);
                setProgressState("儲存 Markdown…", 88, "約 88–98%", false);
                saveTextPublic(relative, base + ".md", "text/markdown", md);
                setProgressState("本次檔案已儲存完成", 100, "100%", false);
                runOnUiThread(() -> saveFolderHint.setText(
                        "已儲存：" + relative + "\n原始音檔：" + audioName + "（檔名不更動）"));
                toast("已存到今天的日期／時間資料夾");
            } catch (Exception e) {
                setStage("自動存檔失敗：" + e.getMessage(), true);
            } finally {
                setBusy(false);
            }
        });
    }

    private void saveOriginalAudio(String relative) throws Exception {
        String name = audioName.isEmpty() ? "audio" : audioName;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri dst = createMediaStoreFile(relative, name, safeMime(audioUri));
            try (InputStream in = new BufferedInputStream(getContentResolver().openInputStream(audioUri));
                 OutputStream out = new BufferedOutputStream(getContentResolver().openOutputStream(dst, "w"))) {
                if (in == null || out == null) throw new Exception("無法建立原始音檔副本");
                long total = querySize(audioUri);
                long done = 0L;
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        int pct = 15 + (int) Math.min(50L, done * 50L / total);
                        setExactProgress(pct, "複製原始音檔");
                    }
                }
            }
        } else {
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
                    "Junba AI Transcriber/" + sessionDate + "/" + sessionStamp);
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("無法建立資料夾");
            File dst = new File(dir, name);
            try (InputStream in = new BufferedInputStream(getContentResolver().openInputStream(audioUri));
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(dst))) {
                if (in == null) throw new Exception("無法讀取原始音檔");
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
    }

    private void saveTextPublic(String relative, String name, String mime, String payload) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri dst = createMediaStoreFile(relative, name, mime);
            try (OutputStream out = getContentResolver().openOutputStream(dst, "w")) {
                if (out == null) throw new Exception("無法建立 " + name);
                out.write(payload.getBytes(StandardCharsets.UTF_8));
            }
        } else {
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
                    "Junba AI Transcriber/" + sessionDate + "/" + sessionStamp);
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("無法建立資料夾");
            try (OutputStream out = new FileOutputStream(new File(dir, name))) {
                out.write(payload.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private Uri createMediaStoreFile(String relative, String name, String mime) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE,
                mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relative);
        Uri uri = getContentResolver().insert(MediaStore.Files.getContentUri("external"), values);
        if (uri == null) throw new Exception("Android 無法在 Documents 建立 " + name);
        return uri;
    }

    private long querySize(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(
                uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx);
            }
        } catch (Exception ignored) {}
        return -1L;
    }

    private TextView sectionLabel(String s) {
        TextView t = text(s, 16, true);
        t.setPadding(0, dp(10), 0, dp(4));
        return t;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(0xFF17202A);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        b.setMinHeight(dp(46));
        return b;
    }

    private View adaptiveButtonRow(Button... buttons) {
        boolean narrow = getResources().getConfiguration().screenWidthDp < 600;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(narrow ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, dp(2));
        for (Button b : buttons) row.addView(b, narrow ? lpMatch() : lpWeight());
        return row;
    }

    private LinearLayout.LayoutParams lpMatch() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams lpWeight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private int dp(int x) { return Math.round(x * getResources().getDisplayMetrics().density); }
    private void toast(String s) { runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }
    private static String shortText(String s, int max) { return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…"); }

    @Override
    protected void onDestroy() {
        cancelFlag.set(true);
        busyState.set(false);
        heartbeatRunnable = null;
        executor.shutdownNow();
        super.onDestroy();
    }
}
