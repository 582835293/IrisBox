package com.irisbox.viewer;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.File;
import java.util.concurrent.*;

/**
 * 虹膜空间首页部署向导与状态总览（SetupActivity）：
 * 采用分层卡片式深色仪表盘，清晰呈现宿主环境、外置存储、内置引擎版本、已导入 ROM 与实例概况。
 */
public final class SetupActivity extends Activity {
    private static final int PICK_ROM = 41;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView logView, envBadge, envDesc, engineBadge, engineDesc, romBadge, romDesc, instBadge, instDesc;
    private TextView ssdChip, boardChip;
    private Button checkBtn, installBtn, importBtn, openBtn, languageBtn, swapBtn, storageBtn, cleanupBtn;
    private ScrollView logScroll;
    private boolean busy, logVisible;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(I18n.wrap(base));
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        I18n.init(this);
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.rgb(12, 16, 22));
        window.setNavigationBarColor(Color.rgb(12, 16, 22));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(12, 16, 22));
        root.setPadding(dp(16), dp(14), dp(16), dp(14));

        // 1. 顶部标题区与系统快捷栏
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(I18n.known("虹膜空间 · ARM64 多实例容器"));
        title.setTextColor(Color.rgb(240, 245, 252));
        title.setTextSize(21);
        title.setTypeface(null, Typeface.BOLD);
        TextView subtitle = new TextView(this);
        subtitle.setText(I18n.known("ARM64 独立虚拟容器控制台 · 内置引擎与存储管理"));
        subtitle.setTextColor(Color.rgb(138, 152, 172));
        subtitle.setTextSize(12);
        titleBox.addView(title);
        titleBox.addView(subtitle);
        headerRow.addView(titleBox, new LinearLayout.LayoutParams(0, -2, 1));

        languageBtn = topToolButton("EN / 中文", () -> I18n.choose(this));
        swapBtn = topToolButton(I18n.known("交换空间"), () -> startActivity(new Intent(this, HostSwapActivity.class)));
        storageBtn = topToolButton(I18n.known("外置存储"), () -> HostStorage.openDialog(this, this::refreshDashboard));
        cleanupBtn = topToolButton(I18n.known("清理并卸载"), this::confirmFullCleanup);
        cleanupBtn.setTextColor(Color.rgb(255, 138, 128));
        headerRow.addView(languageBtn);
        headerRow.addView(spacer(6));
        headerRow.addView(swapBtn);
        headerRow.addView(spacer(6));
        headerRow.addView(storageBtn);
        headerRow.addView(spacer(6));
        headerRow.addView(cleanupBtn);
        root.addView(headerRow);

        // 2. 硬件与外置存储概况胶囊条
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setPadding(0, dp(10), 0, dp(10));
        boardChip = chipView(I18n.known("板卡：检测中…"));
        ssdChip = chipView(I18n.known("外置存储：检测中…"));
        chipRow.addView(boardChip);
        chipRow.addView(spacer(8));
        chipRow.addView(ssdChip);
        root.addView(chipRow);

        // 3. 四步引导仪表盘卡片滚动区
        ScrollView bodyScroll = new ScrollView(this);
        bodyScroll.setFillViewport(true);
        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.VERTICAL);

        // 第1步 + 第2步 并排
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        View card1 = buildStepCard(
            I18n.known("1 · 授权与检查"),
            I18n.known("核验 Magisk Root、User Namespace、Binder 与上行网络"),
            I18n.known("1 · 授权与检查"),
            false,
            v -> { envBadge = v.badge; envDesc = v.desc; checkBtn = v.action; checkBtn.setOnClickListener(x -> inspect()); }
        );
        View card2 = buildStepCard(
            I18n.known("2 · 安装内置引擎"),
            I18n.known("部署或校验容器隔离引擎（版本随 APK 同步升级）"),
            I18n.known("2 · 安装内置引擎"),
            false,
            v -> { engineBadge = v.badge; engineDesc = v.desc; installBtn = v.action; installBtn.setOnClickListener(x -> runSetup(I18n.known("2 · 安装内置引擎"), p -> { EngineInstaller.install(this, p); return I18n.known("引擎安装成功。请选择并导入 ROM 包。"); })); }
        );
        row1.addView(card1, new LinearLayout.LayoutParams(0, -2, 1));
        row1.addView(spacer(10));
        row1.addView(card2, new LinearLayout.LayoutParams(0, -2, 1));
        cards.addView(row1);
        cards.addView(vSpacer(10));

        // 第3步 + 第4步
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        View card3 = buildStepCard(
            I18n.known("3 · 导入 ROM"),
            I18n.known("校验并导入与当前板卡匹配的 ARM64 Android ROM 包"),
            I18n.known("3 · 导入 ROM"),
            false,
            v -> { romBadge = v.badge; romDesc = v.desc; importBtn = v.action; importBtn.setOnClickListener(x -> confirmImport()); }
        );
        View card4 = buildStepCard(
            I18n.known("4 · 管理实例"),
            I18n.known("进入多实例控制面板：管理启停、音频、精准参数与画面"),
            I18n.known("进入实例管理"),
            true,
            v -> { instBadge = v.badge; instDesc = v.desc; openBtn = v.action; openBtn.setOnClickListener(x -> startActivity(new Intent(this, ListActivity.class))); }
        );
        row2.addView(card3, new LinearLayout.LayoutParams(0, -2, 1));
        row2.addView(spacer(10));
        row2.addView(card4, new LinearLayout.LayoutParams(0, -2, 1));
        cards.addView(row2);
        bodyScroll.addView(cards);
        root.addView(bodyScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        // 4. 底部操作诊断日志栏
        LinearLayout logBar = new LinearLayout(this);
        logBar.setOrientation(LinearLayout.HORIZONTAL);
        logBar.setGravity(Gravity.CENTER_VERTICAL);
        logBar.setPadding(0, dp(8), 0, dp(4));
        TextView logHeader = new TextView(this);
        logHeader.setText(I18n.known("环境诊断与部署日志"));
        logHeader.setTextColor(Color.rgb(154, 168, 188));
        logHeader.setTextSize(12);
        logHeader.setTypeface(null, Typeface.BOLD);
        logBar.addView(logHeader, new LinearLayout.LayoutParams(0, -2, 1));

        Button toggleLog = topToolButton(I18n.known("展开 / 收起日志"), null);
        toggleLog.setOnClickListener(v -> {
            logVisible = !logVisible;
            logScroll.setVisibility(logVisible ? View.VISIBLE : View.GONE);
        });
        logBar.addView(toggleLog);
        root.addView(logBar);

        logScroll = new ScrollView(this);
        GradientDrawable logBg = new GradientDrawable();
        logBg.setColor(Color.rgb(17, 22, 30));
        logBg.setCornerRadius(dp(8));
        logBg.setStroke(dp(1), Color.rgb(42, 52, 68));
        logScroll.setBackground(logBg);
        logScroll.setPadding(dp(10), dp(8), dp(10), dp(8));

        logView = new TextView(this);
        logView.setTextColor(Color.rgb(200, 214, 232));
        logView.setTextSize(12);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setText(I18n.known("等待 Magisk 授权并检查宿主…"));
        logScroll.addView(logView);
        logVisible = false;
        logScroll.setVisibility(View.GONE);
        root.addView(logScroll, new LinearLayout.LayoutParams(-1, dp(140)));

        setContentView(root);
        inspect();
    }

    private static final class CardViews {
        TextView badge, desc;
        Button action;
    }

    private interface CardBinder { void bind(CardViews v); }

    private View buildStepCard(String titleText, String initDesc, String btnText, boolean primary, CardBinder binder) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(22, 28, 38));
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), primary ? Color.rgb(44, 114, 200) : Color.rgb(44, 54, 70));
        card.setBackground(bg);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextColor(Color.rgb(235, 242, 250));
        title.setTextSize(15);
        title.setTypeface(null, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        TextView badge = new TextView(this);
        badge.setText(I18n.known("检测中"));
        badge.setTextSize(11);
        badge.setTypeface(null, Typeface.BOLD);
        badge.setPadding(dp(8), dp(2), dp(8), dp(2));
        applyBadgeStyle(badge, Color.rgb(55, 65, 81), Color.rgb(209, 213, 219));
        top.addView(badge);
        card.addView(top);

        TextView desc = new TextView(this);
        desc.setText(initDesc);
        desc.setTextColor(Color.rgb(176, 188, 206));
        desc.setTextSize(13);
        desc.setPadding(0, dp(8), 0, dp(10));
        card.addView(desc, new LinearLayout.LayoutParams(-1, 0, 1));

        Button btn = new Button(this);
        btn.setText(btnText);
        btn.setAllCaps(false);
        btn.setTextSize(13);
        btn.setTypeface(null, Typeface.BOLD);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setCornerRadius(dp(7));
        btnBg.setColor(primary ? Color.rgb(37, 99, 235) : Color.rgb(45, 56, 74));
        btn.setBackground(btnBg);
        btn.setTextColor(Color.WHITE);
        btn.setPadding(dp(12), dp(8), dp(12), dp(8));
        card.addView(btn, new LinearLayout.LayoutParams(-1, -2));

        CardViews cv = new CardViews();
        cv.badge = badge;
        cv.desc = desc;
        cv.action = btn;
        binder.bind(cv);
        return card;
    }

    private void applyBadgeStyle(TextView tv, int bgColor, int fgColor) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(12));
        d.setColor(bgColor);
        tv.setBackground(d);
        tv.setTextColor(fgColor);
    }

    private TextView chipView(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(Color.rgb(190, 212, 238));
        tv.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(14));
        d.setColor(Color.rgb(28, 37, 50));
        d.setStroke(dp(1), Color.rgb(52, 68, 92));
        tv.setBackground(d);
        return tv;
    }

    private Button topToolButton(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTextColor(Color.rgb(220, 230, 245));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(7));
        d.setColor(Color.rgb(32, 40, 54));
        d.setStroke(dp(1), Color.rgb(55, 68, 90));
        b.setBackground(d);
        if (action != null) b.setOnClickListener(v -> action.run());
        return b;
    }

    private View spacer(int widthDp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(widthDp), 1));
        return v;
    }

    private View vSpacer(int heightDp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(heightDp)));
        return v;
    }

    private void confirmImport() {
        new AlertDialog.Builder(this)
            .setTitle(I18n.known("导入 ROM"))
            .setMessage(I18n.known("请选择可信来源、与当前宿主设备适配的 虹膜空间 ROM 包。校验完成后将用于新建实例。"))
            .setNegativeButton(I18n.known("取消"), null)
            .setPositiveButton(I18n.known("导入"), (d, w) -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_ROM))
            .show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == PICK_ROM && res == RESULT_OK && data != null && data.getData() != null) {
            final Uri uri = data.getData();
            runSetup(I18n.known("3 · 导入 ROM"), p -> EngineInstaller.importRom(this, uri, p));
        }
    }

    private void runSetup(String title, SetupTask task) {
        if (busy) return;
        busy = true;
        buttons(false);
        ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle(title);
        pd.setMessage(I18n.known("正在准备"));
        pd.setCancelable(false);
        pd.show();
        PackageArchive.Progress p = msg -> ui.post(() -> pd.setMessage(msg));
        worker.execute(() -> {
            String result;
            try {
                String msg = task.run(p);
                JSONObject st = EngineInstaller.structuredStatus(this);
                String diag = EngineInstaller.preflight(this);
                result = "OK: " + msg + "\n" + diag;
                ui.post(() -> applyStatusToCards(st, true, diag));
            } catch (Exception e) {
                result = "ERR: " + e.getMessage();
                ui.post(() -> {
                    logVisible = true;
                    logScroll.setVisibility(View.VISIBLE);
                });
            }
            String out = result;
            ui.post(() -> {
                pd.dismiss();
                busy = false;
                buttons(true);
                logView.setText(out);
            });
        });
    }

    private void refreshDashboard() {
        inspect();
    }

    private void inspect() {
        if (busy) return;
        busy = true;
        buttons(false);
        worker.execute(() -> {
            String text;
            JSONObject st = null;
            boolean preflightOk = false;
            try {
                text = EngineInstaller.preflight(this);
                preflightOk = true;
                st = EngineInstaller.structuredStatus(this);
            } catch (Exception e) {
                text = e.getMessage() == null ? e.toString() : e.getMessage();
                try { st = EngineInstaller.structuredStatus(this); } catch (Exception ignored) {}
            }
            String display = I18n.diagnostic(text);
            JSONObject finalSt = st;
            boolean finalOk = preflightOk;
            ui.post(() -> {
                busy = false;
                buttons(true);
                logView.setText(display);
                if (!finalOk) {
                    logVisible = true;
                    logScroll.setVisibility(View.VISIBLE);
                }
                applyStatusToCards(finalSt, finalOk, display);
            });
        });
    }

    private void applyStatusToCards(JSONObject st, boolean hostOk, String diagText) {
        if (hostOk) {
            applyBadgeStyle(envBadge, Color.rgb(20, 83, 45), Color.rgb(134, 239, 172));
            envBadge.setText(I18n.known("已通过"));
            envDesc.setText(I18n.known("宿主 Magisk Root、命名空间、Binder、GPU 与资源组就绪。"));
        } else {
            applyBadgeStyle(envBadge, Color.rgb(127, 29, 29), Color.rgb(254, 202, 202));
            envBadge.setText(I18n.known("未通过"));
            envDesc.setText(diagText);
        }

        if (st == null) {
            boardChip.setText(I18n.known("板卡：待授权"));
            ssdChip.setText(I18n.known("外置存储：待检测"));
            applyBadgeStyle(engineBadge, Color.rgb(55, 65, 81), Color.rgb(209, 213, 219));
            engineBadge.setText(I18n.known("未知"));
            applyBadgeStyle(romBadge, Color.rgb(55, 65, 81), Color.rgb(209, 213, 219));
            romBadge.setText(I18n.known("未知"));
            applyBadgeStyle(instBadge, Color.rgb(55, 65, 81), Color.rgb(209, 213, 219));
            instBadge.setText(I18n.known("等待检查"));
            return;
        }

        String boardName = st.optString("boardName", "");
        String boardProfile = st.optString("boardProfile", "");
        boardChip.setText(I18n.known("板卡：") + boardName + " (" + boardProfile + ")");

        boolean ssdMounted = "1".equals(st.optString("ssdMounted", "0"));
        long availMb = 0;
        try { availMb = Long.parseLong(st.optString("ssdAvailMb", "0")); } catch (Exception ignored) {}
        if (ssdMounted) {
            ssdChip.setText(String.format(java.util.Locale.US, I18n.known("外置存储：已挂载 · 可用 %.1f GiB"), availMb / 1024.0));
            ssdChip.setTextColor(Color.rgb(134, 239, 172));
        } else {
            ssdChip.setText(st.optBoolean("requireExternalSsd") ? I18n.known("外置存储：未挂载（需先绑定）") : I18n.known("外置存储：未挂载"));
            ssdChip.setTextColor(Color.rgb(253, 224, 71));
        }

        boolean engInst = st.optBoolean("engineInstalled");
        boolean engMatch = st.optBoolean("engineMatches");
        String ev = st.optString("engineVersion", "");
        String expectedEv = st.optString("expectedEngineVersion", EngineInstaller.VERSION);
        if (engInst && engMatch) {
            applyBadgeStyle(engineBadge, Color.rgb(20, 83, 45), Color.rgb(134, 239, 172));
            engineBadge.setText(I18n.known("已安装"));
            engineDesc.setText(I18n.known("当前运行引擎：") + ev + I18n.known("（与当前版本一致）"));
            installBtn.setText(I18n.known("校验内置引擎"));
        } else if (engInst) {
            applyBadgeStyle(engineBadge, Color.rgb(120, 53, 15), Color.rgb(253, 224, 71));
            engineBadge.setText(I18n.known("版本差异"));
            engineDesc.setText(I18n.known("已装引擎：") + ev + "\n" + I18n.known("APK 内置：") + expectedEv);
            installBtn.setText(I18n.known("2 · 安装内置引擎"));
        } else {
            applyBadgeStyle(engineBadge, Color.rgb(127, 29, 29), Color.rgb(254, 202, 202));
            engineBadge.setText(I18n.known("未安装"));
            engineDesc.setText(I18n.known("内置版本：") + expectedEv + I18n.known(" · 请点击下方按钮完成首次安装。"));
            installBtn.setText(I18n.known("2 · 安装内置引擎"));
        }

        boolean romOk = st.optBoolean("romImported");
        String defRom = st.optString("defaultRom", "");
        String allRoms = st.optString("installedRoms", "");
        if (romOk) {
            applyBadgeStyle(romBadge, Color.rgb(20, 83, 45), Color.rgb(134, 239, 172));
            romBadge.setText(I18n.known("已导入"));
            String listStr = allRoms.isEmpty() ? defRom : allRoms.replace("|", ", ");
            romDesc.setText(I18n.known("默认 ROM：") + (defRom.isEmpty() ? listStr : defRom) + "\n" + I18n.known("已入库：") + listStr);
        } else {
            applyBadgeStyle(romBadge, Color.rgb(120, 53, 15), Color.rgb(253, 224, 71));
            romBadge.setText(I18n.known("未导入"));
            romDesc.setText(I18n.known("尚未导入系统 ROM 包，请导入匹配当前板卡的 ROM。"));
        }

        int totalInst = 0, runInst = 0;
        try { totalInst = Integer.parseInt(st.optString("instanceCount", "0")); } catch (Exception ignored) {}
        try { runInst = Integer.parseInt(st.optString("runningCount", "0")); } catch (Exception ignored) {}
        if (engInst && romOk) {
            applyBadgeStyle(instBadge, Color.rgb(30, 58, 138), Color.rgb(147, 197, 253));
            instBadge.setText(I18n.known("已就绪"));
            instDesc.setText(String.format(java.util.Locale.US, I18n.known("已创建 %d 个实例（其中 %d 个运行中）。点击下方进入控制面板。"), totalInst, runInst));
        } else {
            applyBadgeStyle(instBadge, Color.rgb(55, 65, 81), Color.rgb(209, 213, 219));
            instBadge.setText(I18n.known("待配置"));
            instDesc.setText(I18n.known("请先完成引擎安装与 ROM 导入后再管理或创建容器实例。"));
        }
    }

    private void confirmFullCleanup() {
        if (busy) return;
        new AlertDialog.Builder(this)
            .setTitle(I18n.known("彻底清理并卸载虹膜空间"))
            .setMessage(I18n.known("本操作将不可逆地彻底删除 /data/irisbox 下的容器引擎、全部 ROM、所有虚拟实例及应用数据、宿主自管 Swap 文件与开机恢复脚本，随后拉起系统卸载界面。\n\n• 操作前请务必先手动停止全部运行中的容器实例。\n• 不会删除宿主机共享系统网络分派器与系统自带 zRAM。"))
            .setNegativeButton(I18n.known("取消"), null)
            .setPositiveButton(I18n.known("确认清理并卸载"), (d, w) -> executeFullCleanup())
            .show();
    }

    private void executeFullCleanup() {
        if (busy) return;
        busy = true;
        buttons(false);
        ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle(I18n.known("彻底清理并卸载虹膜空间"));
        pd.setMessage(I18n.known("正在彻底清理运行环境…"));
        pd.setCancelable(false);
        pd.show();
        worker.execute(() -> {
            String err = null;
            boolean externalOffline = false;
            try {
                String script;
                try (java.io.InputStream in = getAssets().open("cleanup_all.sh")) {
                    java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                    byte[] data = new byte[4096];
                    int n;
                    while ((n = in.read(data)) != -1) b.write(data, 0, n);
                    script = new String(b.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                }
                String out = RootShell.run(script, 120);
                externalOffline = out.contains("CLEANUP_EXTERNAL_OFFLINE");
                if (!out.contains("CLEANUP_ALL_COMPLETE")) {
                    err = out;
                } else {
                    try {
                        getSharedPreferences("pointer_modes", MODE_PRIVATE).edit().clear().apply();
                        getSharedPreferences("display_hud", MODE_PRIVATE).edit().clear().apply();
                        File fdir = getFilesDir();
                        PackageArchive.erase(new File(fdir, "package-work"));
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final String finalErr = err;
            final boolean finalExtOffline = externalOffline;
            ui.post(() -> {
                pd.dismiss();
                busy = false;
                buttons(true);
                if (finalErr != null) {
                    logVisible = true;
                    logScroll.setVisibility(View.VISIBLE);
                    String readable = I18n.diagnostic(finalErr);
                    logView.setText(readable);
                    new AlertDialog.Builder(this)
                        .setTitle(I18n.known("未能执行彻底清理"))
                        .setMessage(readable)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                    return;
                }
                if (finalExtOffline) {
                    new AlertDialog.Builder(this)
                        .setTitle(I18n.known("清理完成"))
                        .setMessage(I18n.known("机内环境、开机脚本与配置已全部清空。\n\n检测到此前绑定过外置 SSD 但当前未插入/挂载，外置硬盘内旧文件无法代为清除；请在下方点击确定继续卸载应用，外置盘可后续按需清理。"))
                        .setCancelable(false)
                        .setPositiveButton(I18n.known("卸载应用"), (d, w) -> launchSystemUninstall())
                        .show();
                } else {
                    Toast.makeText(this, I18n.known("宿主环境与残余已彻底清空，正在拉起系统卸载…"), Toast.LENGTH_LONG).show();
                    launchSystemUninstall();
                }
            });
        });
    }

    private void launchSystemUninstall() {
        Intent uninstall = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + getPackageName()));
        uninstall.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(uninstall);
        finish();
    }

    private void buttons(boolean enabled) {
        checkBtn.setEnabled(enabled);
        installBtn.setEnabled(enabled);
        importBtn.setEnabled(enabled);
        openBtn.setEnabled(enabled);
        languageBtn.setEnabled(enabled);
        swapBtn.setEnabled(enabled);
        storageBtn.setEnabled(enabled);
        cleanupBtn.setEnabled(enabled);
    }

    interface SetupTask { String run(PackageArchive.Progress p) throws Exception; }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
