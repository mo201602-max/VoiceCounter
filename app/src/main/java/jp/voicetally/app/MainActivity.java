package jp.voicetally.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.VibrationAttributes;
import android.os.VibratorManager;
import android.view.Gravity;
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
    private static final int LIME = 0xffd0f9a9;
    private static final int LEAF = 0xffb2df8b;
    private static final int FOREST = 0xff24543a;
    private static final int INK = 0xff142e20;
    private static final int PANEL = 0xfff2fbe9;
    private static final int MICROPHONE = 10;
    private static final String READY = "ボタンを押して、ワードを1つ話す";
    private final LinkedHashMap<String, Long> counts = new LinkedHashMap<>();
    private SharedPreferences storage;
    private SpeechRecognizer speech;
    private boolean active;
    private long started;
    private TextView summary, status;
    private LinearLayout rows;
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
        root.setBackgroundColor(LIME);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            root.setPadding(dp(20) + bars.left, dp(14) + bars.top,
                    dp(20) + bars.right, dp(14) + bars.bottom);
            return insets;
        });
        TextView title = text(29);
        title.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        title.setLetterSpacing(0.035f);
        title.setTextColor(LIME);
        title.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable banner = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{INK, FOREST, 0xff37774e});
        banner.setCornerRadius(dp(26));
        title.setBackground(banner);
        title.setText("ヴォイスカウンター");
        root.addView(title);
        summary = text(18);
        summary.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        summary.setPadding(dp(12), dp(12), dp(12), dp(8));
        root.addView(summary);
        ScrollView list = new ScrollView(this);
        list.setBackground(shape(PANEL, 22));
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(14), dp(8), dp(14), dp(10));
        list.addView(rows);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        status = text(16);
        status.setPadding(dp(8), dp(10), dp(8), dp(6));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status);
        session = button(root, "", 52, view -> changeSession());
        share = button(root, "CSVを共有", 56, view -> shareCsv());
        mic = button(root, "話してカウント", 84, view -> requestVoice());
        mic.setTextSize(22);
        styleButton(session, LEAF, INK);
        styleButton(share, FOREST, LIME);
        styleButton(mic, FOREST, LIME);
        setContentView(root);
        ViewCompat.requestApplyInsets(root);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(int size) {
        TextView view = new TextView(this);
        view.setTextSize(size);
        view.setTextColor(INK);
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
        long total = 0;
        for (long count : counts.values()) total += count;
        boolean finished = !active && started > 0;
        String phase = active ? "集計中" : finished ? "集計結果" : "待機中";
        summary.setText(phase + "　合計 " + total + " 件　" + counts.size() + " 種類");
        renderRows(finished, total);
        status.setText(message);
        session.setText(active ? "集計終了" : "集計開始");
        mic.setVisibility(active ? View.VISIBLE : View.GONE);
        mic.setEnabled(speech == null);
        mic.setText(speech == null ? "話してカウント" : "聞き取り中…");
        share.setVisibility(!active && started > 0 ? View.VISIBLE : View.GONE);
        if (active) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }

    private void styleButton(Button button, int background, int foreground) {
        button.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        button.setLetterSpacing(0.06f);
        button.setTextColor(foreground);
        button.setBackgroundTintList(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40679b56),
                shape(background, 24), null));
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) button.getLayoutParams();
        params.topMargin = dp(8);
        button.setLayoutParams(params);
    }

    private void renderRows(boolean finished, long total) {
        rows.removeAllViews();
        if (counts.isEmpty()) {
            TextView empty = text(16);
            empty.setText(finished ? "カウントされたワードはありません" : "ワードがここに並びます");
            empty.setPadding(0, dp(10), 0, 0);
            rows.addView(empty);
            return;
        }
        ArrayList<Map.Entry<String, Long>> entries = new ArrayList<>(counts.entrySet());
        if (finished) {
            java.util.Collections.sort(entries, (left, right) -> Long.compare(right.getValue(), left.getValue()));
            TextView scale = text(13);
            scale.setText("棒グラフの右端 ＝ 全 " + total + " 件");
            rows.addView(scale);
        }
        for (Map.Entry<String, Long> entry : entries) {
            LinearLayout line = new LinearLayout(this);
            line.setPadding(0, dp(10), 0, dp(4));
            TextView name = text(18);
            name.setText(entry.getKey());
            line.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            TextView value = text(18);
            value.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
            value.setText(entry.getValue() + " 件");
            value.setPadding(dp(8), 0, 0, 0);
            line.addView(value);
            rows.addView(line);
            if (finished) {
                View track = new View(this);
                track.setBackground(shape(0xffc8dfb9, 9));
                ViewCompat.setImportantForAccessibility(track, ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_NO);
                ClipDrawable fill = new ClipDrawable(shape(FOREST, 9), Gravity.LEFT, ClipDrawable.HORIZONTAL);
                fill.setLevel(total > 0 ? (int) Math.round(10000.0 * entry.getValue() / total) : 0);
                track.setForeground(fill);
                rows.addView(track, new LinearLayout.LayoutParams(-1, dp(12)));
            }
        }
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
        vibrate(previous == null ? 320 : 110);
    }

    private void vibrate(int milliseconds) {
        Vibrator motor;
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager manager = getSystemService(VibratorManager.class);
            motor = manager != null ? manager.getDefaultVibrator() : null;
        } else {
            motor = getSystemService(Vibrator.class);
        }
        if (motor == null || !motor.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= 26) {
            int amplitude = motor.hasAmplitudeControl() ? 255 : VibrationEffect.DEFAULT_AMPLITUDE;
            VibrationEffect effect = VibrationEffect.createOneShot(milliseconds, amplitude);
            if (Build.VERSION.SDK_INT >= 33) {
                motor.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH));
            } else motor.vibrate(effect);
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
