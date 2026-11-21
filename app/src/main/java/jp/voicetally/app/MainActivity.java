package jp.voicetally.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;

public final class MainActivity extends Activity {
    private static final int MICROPHONE = 10;
    private static final String READY = "ボタンを押して、ワードを1つ話す";
    private final LinkedHashMap<String, Long> counts = new LinkedHashMap<>();
    private SharedPreferences storage;
    private SpeechRecognizer speech;
    private boolean active;
    private long started;
    private TextView summary, rows, status;
    private Button session, mic, share;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        storage = getSharedPreferences("tally", MODE_PRIVATE);
        active = storage.getBoolean("active", false);
        started = storage.getLong("started", 0);
        try {
            JSONArray saved = new JSONArray(storage.getString("counts", "[]"));
            for (int i = 0; i < saved.length(); i++) {
                JSONArray row = saved.getJSONArray(i);
                counts.put(row.getString(0), row.getLong(1));
            }
        } catch (JSONException error) {
            counts.clear();
        }
        createScreen();
        updateScreen(active ? READY : started > 0 ? "集計が終了しました" : "集計を始めてください");
    }

    private void createScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xfff2f6f3);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            root.setPadding(dp(20) + bars.left, dp(14) + bars.top,
                    dp(20) + bars.right, dp(14) + bars.bottom);
            return insets;
        });
        TextView title = text(28);
        title.setText("ヴォイスカウンター");
        root.addView(title);
        summary = text(18);
        root.addView(summary);
        ScrollView list = new ScrollView(this);
        rows = text(20);
        rows.setPadding(0, dp(12), 0, dp(12));
        list.addView(rows);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        status = text(16);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status);
        session = button(root, "", 52, view -> changeSession());
        share = button(root, "CSVを共有", 56, view -> shareCsv());
        mic = button(root, "話してカウント", 84, view -> requestVoice());
        mic.setTextSize(22);
        setContentView(root);
        ViewCompat.requestApplyInsets(root);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(int size) {
        TextView view = new TextView(this);
        view.setTextSize(size);
        view.setTextColor(0xff18362f);
        return view;
    }

    private Button button(LinearLayout root, String label, int height, View.OnClickListener action) {
        Button view = new Button(this);
        view.setAllCaps(false);
        view.setText(label);
        view.setMinHeight(dp(height));
        view.setOnClickListener(action);
        root.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    private void updateScreen(String message) {
        StringBuilder items = new StringBuilder();
        long total = 0;
        for (Map.Entry<String, Long> row : counts.entrySet()) {
            total += row.getValue();
            items.append(row.getKey()).append("　").append(row.getValue()).append(" 件\n");
        }
        String phase = active ? "集計中" : started > 0 ? "終了" : "待機中";
        summary.setText(phase + "　合計 " + total + " 件　" + counts.size() + " 種類");
        rows.setText(items);
        status.setText(message);
        session.setText(active ? "集計終了" : "集計開始");
        mic.setVisibility(active ? View.VISIBLE : View.GONE);
        mic.setEnabled(speech == null);
        mic.setText(speech == null ? "話してカウント" : "聞き取り中…");
        share.setVisibility(!active && started > 0 ? View.VISIBLE : View.GONE);
        if (active) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void changeSession() {
        if (active) {
            if (save(started, false, counts)) {
                releaseSpeech();
                updateScreen("集計が終了しました");
            }
        } else if (started > 0) {
            new AlertDialog.Builder(this).setTitle("新しい集計を開始しますか？")
                    .setMessage("前回の集計結果が置き換わります。")
                    .setNegativeButton("キャンセル", null)
                    .setPositiveButton("開始", (dialog, which) -> startSession()).show();
        } else startSession();
    }

    private void startSession() {
        if (save(System.currentTimeMillis(), true, new LinkedHashMap<>())) updateScreen(READY);
    }

    private boolean save(long start, boolean running, Map<String, Long> values) {
        JSONArray data = new JSONArray();
        for (Map.Entry<String, Long> row : values.entrySet()) {
            data.put(new JSONArray().put(row.getKey()).put(row.getValue()));
        }
        if (!storage.edit().putLong("started", start).putBoolean("active", running)
                .putString("counts", data.toString()).commit()) {
            updateScreen("集計を保存できませんでした");
            return false;
        }
        started = start;
        active = running;
        if (values != counts) {
            counts.clear();
            counts.putAll(values);
        }
        return true;
    }

    private void requestVoice() {
        if (!active || speech != null) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateScreen("端末で音声認識サービスを利用できません");
            return;
        }
        try {
            SpeechRecognizer request = SpeechRecognizer.createSpeechRecognizer(this);
            speech = request;
            request.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle bundle) {
                    if (speech == request) status.setText("どうぞ、ワードを話してください");
                }

                @Override public void onEndOfSpeech() {
                    if (speech == request) status.setText("認識中…");
                }

                @Override public void onError(int error) {
                    if (speech != request) return;
                    releaseSpeech();
                    updateScreen("認識できませんでした。もう一度押してください");
                }

                @Override public void onResults(Bundle result) {
                    if (speech != request || !active) return;
                    releaseSpeech();
                    ArrayList<String> words = result.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    record(words == null || words.isEmpty() ? null : words.get(0));
                }

                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rms) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onPartialResults(Bundle result) {}
                @Override public void onEvent(int type, Bundle bundle) {}
            });
            updateScreen("マイクを準備中…");
            request.startListening(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1));
            mic.postDelayed(() -> {
                if (speech != request) return;
                releaseSpeech();
                updateScreen("認識がタイムアウトしました。もう一度押してください");
            }, 12000);
        } catch (RuntimeException error) {
            releaseSpeech();
            updateScreen("音声認識を開始できませんでした");
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == MICROPHONE) {
            boolean allowed = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            updateScreen(allowed ? READY : "音声入力にはマイクの許可が必要です");
        }
    }

    private void record(String recognized) {
        String word = recognized == null ? "" : Normalizer.normalize(recognized, Normalizer.Form.NFKC)
                .replaceAll("[\\p{Z}\\s]+", " ").trim().replaceAll("[。！？!?、,. ]+$", "");
        if (word.isEmpty()) {
            updateScreen("聞き取れませんでした。もう一度押してください");
            return;
        }
        String key = word.toLowerCase(Locale.ROOT);
        for (String label : counts.keySet()) {
            if (label.toLowerCase(Locale.ROOT).equals(key)) {
                word = label;
                break;
            }
        }
        Long previous = counts.get(word);
        long count = previous == null ? 1 : previous + 1;
        Map<String, Long> next = new LinkedHashMap<>(counts);
        next.put(word, count);
        if (!save(started, true, next)) return;
        updateScreen(word + "　" + count + " 件" + (previous == null ? "（新規）" : ""));
        vibrate(previous == null ? 280 : 65);
    }

    private void vibrate(int milliseconds) {
        Vibrator motor = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (motor == null || !motor.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= 26) {
            motor.vibrate(VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE));
        } else motor.vibrate(milliseconds);
    }

    private void shareCsv() {
        StringBuilder csv = new StringBuilder("\uFEFFワード,カウント\r\n");
        for (Map.Entry<String, Long> row : counts.entrySet()) {
            String word = row.getKey();
            if ("=+-@".indexOf(word.charAt(0)) >= 0) word = "'" + word;
            csv.append('"').append(word.replace("\"", "\"\"")).append("\",")
                    .append(row.getValue()).append("\r\n");
        }
        File folder = new File(getCacheDir(), "exports");
        if (!folder.isDirectory() && !folder.mkdirs()) {
            status.setText("CSVの保存先を作成できませんでした");
            return;
        }
        File file = new File(folder, "voice-counter-" + started + ".csv");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(csv.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            status.setText("CSVを書き出せませんでした");
            return;
        }
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".csv", file);
        Intent intent = new Intent(Intent.ACTION_SEND).setType("text/csv")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri(file.getName(), uri));
        startActivity(Intent.createChooser(intent, "集計結果を共有"));
    }

    private void releaseSpeech() {
        SpeechRecognizer request = speech;
        speech = null;
        if (request != null) request.destroy();
    }

    @Override protected void onPause() {
        if (speech != null) {
            releaseSpeech();
            updateScreen(READY);
        }
        super.onPause();
    }
}
