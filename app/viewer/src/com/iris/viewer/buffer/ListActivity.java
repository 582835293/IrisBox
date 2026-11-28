package com.irisbox.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.ArrayAdapter;
import android.widget.Toast;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.ListView;
import android.widget.BaseAdapter;
import android.view.ViewGroup;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 宿主虚拟机实例列表界面（ListActivity）：
 * 1. 提权与握手：启动后先通过 HostRootEntry 申请宿主 Magisk root 权限以拉起后台 Broker，随后与 127.0.0.1:19762 建立 HTTP 会话；
 * 2. 身份与版本概念：
 *    - id：不可变的永久内部 ID（如 7）；
 *    - number：界面动态展示的实例序号（如 实例 1）；
 *    - session：内核防复用身份标识（PID:START_TICK）；
 *    - revision：数据目录版本哈希（防并发数据修改冲突）；
 * 3. 页面代次（generation / epoch）：
 *    每次离开页面或换页递增 generation，所有异步 HTTP 回调在回写 UI 前必须二次比对 epoch，防止旧请求串入新卡片；
 * 4. 多线程隔离：
 *    - poller：每秒轮询各实例整体状态（/state）；
 *    - previewer：独立工作线程轮流抓取两张卡片的 JPEG 小窗，绝不阻塞状态轮询；
 *    - logReader：独立增量拉取启动引导日志；
 *    - actions：单线程排队执行用户操作（HTTP 202 异步应答）；
 *    - 全屏高帧率交互显示则由独立进程的 DisplayActivity 承载。
 * 5. 列表页仅负责低频小窗预览，高帧率交互渲染保留在独立的 :display 进程中。
 */
public final class ListActivity extends Activity {
    @Override protected void attachBaseContext(android.content.Context base) {super.attachBaseContext(I18n.wrap(base));}

    /** 主线程 UI 调度 Handler */
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** 当前页面呈现的实例卡片面板数组（每页最多 2 个实例） */
    private volatile Panel[] panels = new Panel[0];

    /** 卡片容器布局与顶部/底部功能按钮 */
    private LinearLayout cards;
    private Button add, previousPage, nextPage, reconnect;

    /** 专用单线程池：在后台异步执行 su --mount-master 申请 Root 提权 */
    private final ExecutorService rootWorker = Executors.newSingleThreadExecutor();

    /** Root 提权连接状态标志与错误提示 */
    private boolean connecting, rootAttempted;
    private int connectionFailures;
    private String connectionError = "";

    /** 分页控制与统计变量 */
    private TextView pageText, hostTelemetryText;
    private volatile int page;
    private int count;
    private boolean creating;
    private int knownCreated;

    /** 正在运行中的 HTTP 连接集合（页面暂停或换页时遍历强行 disconnect 终止） */
    private final Set<HttpURLConnection> connections = Collections.newSetFromMap(new ConcurrentHashMap<HttpURLConnection, Boolean>());

    /** 用户动作排队执行器（容量为 8 的单线程池，超出抛出 AbortPolicy） */
    private final ExecutorService actions = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(8), new ThreadPoolExecutor.AbortPolicy());

    /** 后台状态轮询、小窗截图抓取和引导日志增量拉取的定时线程池 */
    private ScheduledExecutorService poller, previewer, logReader;

    /** Activity 可见性标志与页面访问代次计数器（epoch 防串线） */
    private volatile boolean resumed;
    private volatile int generation;

    /** Broker 认证凭证与本地回环基地址 */
    private String brokerToken = "", base = "http://127.0.0.1:19762";

    /** 顶部全局提示文本条 */
    private TextView notice;

    /** 轮流捕获小窗和读取日志的卡片轮转游标 */
    private int nextPreview, nextLog;

    /** 导出超长日志时的暂存内容与 RequestCode 常量 */
    private String exportingLog = "";
    private static final int EXPORT_LOG = 41;

    /** 从 Broker 动态加载的分辨率预设列表、默认 ROM Google 支持模式与宿主机实时网络状态 */
    private JSONArray resolutionPresets = new JSONArray();
    private String defaultRomGoogleMode = "optional";
    private volatile JSONObject hostNetwork = new JSONObject();

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state); I18n.init(this);
        load(getIntent());
        makeHome();
    }

    /** 从 Intent 或持久化 SharedPreferences 中加载 Broker Token 与端口号 */
    private void load(Intent intent) {
        SharedPreferences prefs = getSharedPreferences("connection", MODE_PRIVATE);
        String token = intent == null ? null : intent.getStringExtra("brokerToken");
        if (token != null && token.matches("[A-Za-z0-9_-]{32,256}")) prefs.edit().putString("brokerToken", token).apply();
        brokerToken = prefs.getString("brokerToken", "");
        int port = prefs.getInt("brokerPort", 19762);
        if (intent != null && intent.hasExtra("brokerPort") && intent.getIntExtra("brokerPort", 0) == 19762) {
            port = 19762;
            prefs.edit().putInt("brokerPort", port).apply();
        }
        base = "http://127.0.0.1:" + (port == 19762 ? port : 19762);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        pauseNetwork();
        load(intent);
        if (resumed) startNetwork();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        startNetwork();
    }

    @Override
    protected void onPause() {
        resumed = false;
        pauseNetwork();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        pauseNetwork();
        actions.shutdownNow();
        rootWorker.shutdownNow();
        super.onDestroy();
    }

    /** dp 单位转屏幕物理像素 px 辅助函数 */
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    /** 快速生成白色文本控件 */
    private TextView text(String value, int size) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextColor(Color.WHITE);
        t.setTextSize(size);
        return t;
    }

    /** 快速生成垂直方向线性布局 */
    private LinearLayout column() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    /** 构建主界面 UI 结构（纯代码布局，脱离 XML 以保持极简无依赖） */
    private void makeHome() {
        LinearLayout root = column();
        root.setPadding(dp(14), dp(8), dp(14), dp(8));
        root.setBackgroundColor(Color.rgb(20, 24, 30));

        // 顶部操作栏：标题 + 重新连接 + 新增实例
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(text(I18n.s(R.string.ui_f8c2bbefe46a), 24), new LinearLayout.LayoutParams(0, -2, 1));
        reconnect = new Button(this);
        reconnect.setText(I18n.s(R.string.ui_bac95e1e52fe));
        reconnect.setContentDescription("iris-reconnect-manager");
        reconnect.setOnClickListener(v -> { rootAttempted = false; connectManager(); });
        heading.addView(reconnect);
        Button language=new Button(this);language.setText("EN / 中文");language.setOnClickListener(v->I18n.choose(this));heading.addView(language);
        Button swap=new Button(this);swap.setText(I18n.known("交换空间"));swap.setOnClickListener(v->{startActivity(new Intent(this,HostSwapActivity.class).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION));overridePendingTransition(0,0);});heading.addView(swap);
        Button roms = new Button(this);
        roms.setText(I18n.s(R.string.ui_624266d31bd4));
        roms.setOnClickListener(v -> { startActivity(new Intent(this, SetupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)); overridePendingTransition(0, 0); });
        heading.addView(roms);
        add = new Button(this);
        add.setText(I18n.s(R.string.ui_4b95f6df9752));
        add.setTextSize(14);
        add.setContentDescription("iris-add-instance");
        add.setOnClickListener(v -> createInstance());
        heading.addView(add);
        root.addView(heading);

        // 顶部全局状态通告栏与宿主机 Linux/硬件状态条
        notice = text(I18n.s(R.string.ui_9f4a28e615cf), 13);
        notice.setMaxLines(1);
        notice.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(notice);

        hostTelemetryText = text("", 11);
        hostTelemetryText.setTextColor(Color.rgb(168, 212, 240));
        hostTelemetryText.setMaxLines(1);
        hostTelemetryText.setEllipsize(TextUtils.TruncateAt.END);
        hostTelemetryText.setVisibility(View.GONE);
        root.addView(hostTelemetryText);

        // 中间主卡片区（横向排布）
        cards = new LinearLayout(this);
        root.addView(cards, new LinearLayout.LayoutParams(-1, 0, 1));

        // 底部翻页控制器
        LinearLayout pager = new LinearLayout(this);
        pager.setGravity(Gravity.CENTER);
        previousPage = new Button(this);
        previousPage.setText(I18n.s(R.string.ui_b41561d80765));
        previousPage.setOnClickListener(v -> changePage(page - 1));
        pager.addView(previousPage, new LinearLayout.LayoutParams(dp(100), dp(36)));
        pageText = text(I18n.s(R.string.ui_5a510839d8b1), 12);
        pageText.setGravity(Gravity.CENTER);
        pager.addView(pageText, new LinearLayout.LayoutParams(0, -2, 1));
        nextPage = new Button(this);
        nextPage.setText(I18n.s(R.string.ui_67a246a344ae));
        nextPage.setOnClickListener(v -> changePage(page + 1));
        pager.addView(nextPage, new LinearLayout.LayoutParams(dp(100), dp(36)));
        root.addView(pager);

        add.setEnabled(false);
        previousPage.setEnabled(false);
        nextPage.setEnabled(false);

        // 底部免责与原理说明提示
        TextView footer = text(I18n.s(R.string.ui_c7d02585d66f), 12);
        footer.setTextColor(Color.LTGRAY);
        footer.setMaxLines(1);
        footer.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(footer);

        setContentView(root);
    }

    /**
     * 【启动后台轮询网络】
     * 1. 绑定本次页面访问代次（epoch = generation）；
     * 2. 若缺少 Token 则触发 Root 提权并拉起 Broker；
     * 3. poller：每隔 1000ms 拉取一次整体实例状态；
     * 4. previewer：单独一个 worker 线程以 500ms 周期轮流对两张卡片采样，慢预览绝不阻塞状态轮询；
     * 5. logReader：每隔 200ms 轮流为卡片增量拉取启动日志，小窗与状态无需等待日志追赶。
     */
    private void startNetwork() {
        pauseNetwork();
        final int epoch = generation;
        if (brokerToken.isEmpty()) {
            for (Panel p : panels) p.clear(I18n.s(R.string.ui_6e6fbca5c994));
            connectManager();
            return;
        }
        poller = Executors.newSingleThreadScheduledExecutor();
        poller.scheduleWithFixedDelay(() -> poll(epoch), 0, 1000, TimeUnit.MILLISECONDS);

        // 独立线程轮流为两个卡片抓取小窗截图，慢速预览绝不阻塞 /state 状态轮询
        nextPreview = 0;
        previewer = Executors.newSingleThreadScheduledExecutor();
        previewer.scheduleWithFixedDelay(() -> {
            Panel[] current = panels;
            if (current.length > 0) preview(epoch, current[(nextPreview++ & 0x7fffffff) % current.length]);
        }, 0, 500, TimeUnit.MILLISECONDS);

        // 启动日志单独分页增量获取；/state 响应体中不携带全量历史，保持轻量高效
        nextLog = 0;
        logReader = Executors.newSingleThreadScheduledExecutor();
        logReader.scheduleWithFixedDelay(() -> {
            Panel[] current = panels;
            if (current.length > 0) readLogs(epoch, current[(nextLog++ & 0x7fffffff) % current.length]);
        }, 0, 200, TimeUnit.MILLISECONDS);
    }

    /**
     * 【提权连接管理服务】
     * 1. 弹出 Root 授权框会导致 Activity 暂时 onPause，故此 worker 独立运行，不随网络暂停取消；
     * 2. 失败后只自动尝试一次，被拒绝后必须由用户手动点击“重新连接”，杜绝循环弹窗骚扰；
     * 3. manager.sh open 会通过显式 Intent 回传最新 Token，绝不读取 root 私有目录或私自点火实例。
     */
    private void connectManager() {
        if (connecting || rootAttempted) {
            notice.setText(connecting ? I18n.s(R.string.ui_664be1b554d0) : connectionError);
            return;
        }
        connecting = true;
        rootAttempted = true;
        connectionError = "";
        reconnect.setVisibility(View.VISIBLE);
        reconnect.setEnabled(false);
        notice.setText(I18n.s(R.string.ui_50f106ba30fc));
        rootWorker.execute(() -> {
            String error = HostRootEntry.connect(this);
            ui.post(() -> {
                if (isDestroyed()) return;
                connecting = false;
                reconnect.setEnabled(true);
                connectionError = error;
                if (!error.isEmpty()) {
                    if (resumed) notice.setText(error);
                    return;
                }
                // 不重放旧 Intent 里的陈旧 Token；由 manager.sh open 回传新 Intent 更新配置
                load(null);
                if (resumed) startNetwork();
            });
        });
    }

    /** 暂停所有网络轮询：递增 generation 失效旧回调，销毁并切断所有 HTTP 活跃连接 */
    private void pauseNetwork() {
        ++generation;
        if (poller != null) poller.shutdownNow();
        if (previewer != null) previewer.shutdownNow();
        if (logReader != null) logReader.shutdownNow();
        poller = null;
        previewer = null;
        logReader = null;
        for (HttpURLConnection c : connections) c.disconnect();
        connections.clear();
        // 暂停时仅停止抓图拉取并保留同会话最后一帧缩略图，避免切进/返回全屏时闪烁或露出日志；若会话变更则由 update() 清理。
        for (Panel p : panels) if (p != null) { p.previewAllowed = false; if (brokerToken.isEmpty()) p.clearImage(); }
    }

    /**
     * 【定时拉取状态：poll】
     * 工作线程发起 GET /state 请求，ui.post 回到主线程后二次严格核验 epoch，
     * 杜绝因用户换页或离开 Activity 导致已废弃的旧数据写入当前界面。
     */
    private void poll(int epoch) {
        if (epoch != generation || !resumed) return;
        try {
            JSONObject response = new JSONObject(new String(request("GET", "/state?offset=" + (page * 2) + "&limit=2", 200, epoch), "UTF-8"));
            JSONArray states = response.getJSONArray("instances"), presets = response.optJSONArray("resolutionPresets");
            JSONObject hostNet = response.optJSONObject("hostNetwork");
            JSONObject hostTel = response.optJSONObject("hostTelemetry");
            ui.post(() -> {
                if (epoch != generation || !resumed) return;
                connectionFailures = 0;
                connectionError = "";
                rootAttempted = false;
                reconnect.setVisibility(View.GONE);
                resolutionPresets = presets == null ? new JSONArray() : presets;
                defaultRomGoogleMode = response.optString("defaultRomGoogleMode", "optional");
                if (hostNet != null) hostNetwork = hostNet;
                if (hostTel != null) {
                    String telSummary = TelemetryUi.hostBarText(hostTel);
                    hostTelemetryText.setText(telSummary);
                    hostTelemetryText.setVisibility(telSummary.isEmpty() ? View.GONE : View.VISIBLE);
                }
                count = response.optInt("count", 0);
                int actualPage = response.optInt("offset", 0) / 2;
                int created = response.optInt("lastCreatedId", 0);
                String catalogBusy = response.optString("catalogBusy", ""), catalogError = response.optString("catalogError", "");

                // 捕获新实例创建完毕并自动跳转到最后一页
                if (creating && catalogBusy.isEmpty() && (created != knownCreated || !catalogError.isEmpty())) {
                    creating = false;
                    if (created != knownCreated && catalogError.isEmpty()) {
                        knownCreated = created;
                        changePage(Math.max(0, (count - 1) / 2));
                        return;
                    }
                }
                if (!creating) knownCreated = created;
                page = actualPage;
                replacePanels(states);
                add.setEnabled(catalogBusy.isEmpty() && !creating);
                previousPage.setEnabled(page > 0);
                nextPage.setEnabled((page + 1) * 2 < count);
                pageText.setText(count == 0 ? I18n.s(R.string.ui_8c3afb3b80b3) : I18n.s(R.string.ui_f495347d6acf) + (page + 1) + " / " + Math.max(1, (count + 1) / 2) + I18n.s(R.string.ui_6fe2770f2a49) + count + I18n.s(R.string.ui_8f2085358747));

                for (Panel panel : panels) {
                    JSONObject state = null;
                    for (int n = 0; n < states.length(); n++) {
                        JSONObject candidate = states.optJSONObject(n);
                        if (candidate != null && candidate.optInt("id") == panel.id) state = candidate;
                    }
                    if (state == null) { panel.clear(I18n.s(R.string.ui_c490e74b8ded)); continue; }
                    panel.update(state);
                }
                notice.setText(!catalogError.isEmpty() ? catalogError :
                    !catalogBusy.isEmpty() ? I18n.s(R.string.ui_87533ec64f72) :
                    I18n.s(R.string.ui_56f4916f8177));
            });
        } catch (Exception error) {
            ui.post(() -> {
                if (epoch == generation && resumed) {
                    add.setEnabled(false);
                    hostTelemetryText.setVisibility(View.GONE);
                    reconnect.setVisibility(View.VISIBLE);
                    for (Panel p : panels) p.clear(I18n.s(R.string.ui_f2f3e9803ccb));
                    // 连续 3 次连接失败且尚未触发过提权时，自动触发一次重新连接
                    if (++connectionFailures >= 3 && !rootAttempted) connectManager();
                    else notice.setText(connecting ? I18n.s(R.string.ui_df9e3f1b3ad9) : connectionError.isEmpty() ? I18n.s(R.string.ui_5e0e1dd4e1a9) : connectionError);
                }
            });
        }
    }

    /**
     * 【增量拉取引导日志：readLogs】
     * 1. 必须上一页成功返回后才能推进游标（offset），防止丢字漏行；
     * 2. 校验 key 与 offset，旧启动轮次或非当前实例的日志响应绝不拼入新日志列表；
     * 3. 待显示文本先缓存在 pendingLines，攒批处理；变更 adapter 数据与 notifyDataSetChanged 必须在同一次 UI 回调中完成，
     *    若提前向列表添加数据但延迟 notify，用户的触控滑动会直接触发 ListView 的“数据数量不一致”致命异常崩溃。
     */
    private void readLogs(int epoch, Panel panel) {
        final String key = panel.logKey;
        final long offset = panel.logOffset;
        if (epoch != generation || !resumed || key.isEmpty() || !panel.logVisible) return;
        HttpURLConnection connection = null;
        try {
            connection = open("GET", "/boot-log?id=" + panel.id + "&key=" + key + "&offset=" + offset, epoch);
            if (connection.getResponseCode() != 200) return;
            JSONObject page = new JSONObject(new String(readBounded(connection, 65536), "UTF-8"));
            String expected = page.getString("key"), value = page.getString("text");
            long began = page.getLong("offset"), next = page.getLong("next"), total = page.getLong("total");
            if (!key.equals(expected) || began != offset || next < offset || total < next || next - offset > 8192)
                throw new IOException("Invalid log cursor");

            ui.post(() -> {
                if (epoch != generation || !resumed || !Arrays.asList(panels).contains(panel) || !key.equals(panel.logKey) || panel.logOffset != offset)
                    return;
                boolean follow = panel.logLines.isEmpty() || panel.logScroll.getLastVisiblePosition() >= panel.renderedLineCount - 1;
                panel.logOffset = next;
                panel.logTotal = total;
                panel.history.append(value);
                panel.pendingLines.append(value);
                long now = SystemClock.elapsedRealtime();
                if (!value.isEmpty() && (now - panel.lastLogRender >= 1000 || next == total)) {
                    int end;
                    while ((end = panel.pendingLines.indexOf("\n")) >= 0) {
                        panel.logLines.add(panel.pendingLines.substring(0, end));
                        panel.pendingLines.delete(0, end + 1);
                    }
                    panel.lastLogRender = now;
                    panel.renderedLineCount = panel.logLines.size();
                    panel.logAdapter.notifyDataSetChanged();
                    if (follow && !panel.logLines.isEmpty()) panel.logScroll.setSelection(panel.logLines.size() - 1);
                    panel.renderPreview();
                }
            });
        } catch (Exception ignored) {
            // 网络波动时下一轮使用相同 offset 重试，绝不丢失前面的日志
        } finally {
            if (connection != null) {
                connection.disconnect();
                connections.remove(connection);
            }
        }
    }

    /** 复制当前启动日志全文至剪贴板（若超大则引导用户导出到独立 txt 文件） */
    private void copyLog(Panel panel) {
        if (panel.logOffset < panel.logTotal) {
            Toast.makeText(this, I18n.s(R.string.ui_bd6948a6f441), Toast.LENGTH_SHORT).show();
            return;
        }
        if (panel.history.length() == 0) return;
        String value = InstanceNames.title(panel.number) + " · " + (panel.running ? I18n.s(R.string.ui_7fd47e102e12) : I18n.s(R.string.ui_20275ef27d3a)) + I18n.s(R.string.ui_8e4f83eef218) + panel.history.toString();
        // Android 剪贴板跨进程通过 Binder 传输（容量限制约 1MB），超大日志改用系统 Storage Access Framework 导出
        if (value.length() > 200000) { exportLog(value, panel.number); return; }
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText(InstanceNames.title(panel.number) + I18n.s(R.string.ui_8ad868abbca6), value));
            Toast.makeText(this, I18n.s(R.string.ui_54309b76b734), Toast.LENGTH_SHORT).show();
        } catch (RuntimeException tooLarge) {
            exportLog(value, panel.number);
        }
    }

    /** 通过系统的 ACTION_CREATE_DOCUMENT 导出超大日志文件 */
    private void exportLog(String value, int number) {
        exportingLog = value;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, I18n.s(R.string.ui_93676a2553d5) + number + I18n.s(R.string.ui_2fbc9da21a8d));
        Toast.makeText(this, I18n.s(R.string.ui_b89eacc953d5), Toast.LENGTH_LONG).show();
        startActivityForResult(intent, EXPORT_LOG);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != EXPORT_LOG) return;
        final String value = exportingLog;
        exportingLog = "";
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (value.isEmpty()) {
            Toast.makeText(this, I18n.s(R.string.ui_610dc926d655), Toast.LENGTH_LONG).show();
            return;
        }
        final android.net.Uri uri = data.getData();
        actions.execute(() -> {
            try (java.io.OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
                if (output == null) throw new IOException("No document stream");
                output.write(value.getBytes("UTF-8"));
                ui.post(() -> Toast.makeText(this, I18n.s(R.string.ui_ffed807bad4f), Toast.LENGTH_SHORT).show());
            } catch (Exception failed) {
                ui.post(() -> Toast.makeText(this, I18n.s(R.string.ui_a2f2e13bab88), Toast.LENGTH_LONG).show());
            }
        });
    }

    /**
     * 【抓取小窗 JPEG 缩略图：preview】
     * 1. 发起 GET /preview 请求；
     * 2. 若返回 202（正在准备）、409（全屏显示中暂挂）或 503（正在引导），展示文字提示；
     * 3. 若返回 200，严格核验 Content-Type 为 image/jpeg、X-Iris-Session 匹配当前会话；
     * 4. 核验服务端抓取时间戳，防止过期陈旧图片落入缓存；
     * 5. 解码降采样优化：
     *    - 先通过 inJustDecodeBounds 读取图片尺寸，防超大图片攻击（限制 4096×4096 内）；
     *    - 采用 RGB_565 像素格式（比 ARGB_8888 节省一半内存）；
     *    - 按 640×360 做 2 的整数幂倍数下采样（inSampleSize）；
     * 6. 回到主线程比对 sequence 序列号递增更新图片，若图片过期则自动 recycle() 释放原生显存。
     */
    private void preview(int epoch, Panel panel) {
        final String session = panel.session;
        if (epoch != generation || !resumed || !panel.previewAllowed || !validSession(session)) return;
        HttpURLConnection connection = null;
        Bitmap decoded = null;
        try {
            connection = open("GET", "/preview?id=" + panel.id + "&session=" + URLEncoder.encode(session, "UTF-8"), epoch);
            connection.setReadTimeout(3500);
            int code = connection.getResponseCode();
            if (code != 200) {
                if (code != 202 && code != 409 && code != 503) throw new IOException("Preview rejected");
                final int response = code;
                ui.post(() -> {
                    if (current(epoch, panel, session)) {
                        if (response != 202 && response != 409) panel.clearImage();
                        panel.previewMessage = response == 202 ? I18n.s(R.string.ui_e29e654d3987) : response == 409 ? I18n.s(R.string.ui_4bd5dfa2f736) : I18n.s(R.string.ui_1316079be06d);
                        panel.renderPreview();
                    }
                });
                return;
            }
            String content = connection.getContentType();
            if (content == null || !content.toLowerCase(java.util.Locale.ROOT).startsWith("image/jpeg") ||
                !session.equals(connection.getHeaderField("X-Iris-Session")))
                throw new IOException("Preview response identity or type");

            long sequence = positiveHeader(connection, "X-Iris-Sequence");
            long captured = positiveHeader(connection, "X-Iris-Captured-Elapsed-Ms");
            long now = SystemClock.elapsedRealtime();
            if (captured > now + 1000 || now - captured > 20000) throw new IOException("Preview timestamp outside cache window");

            byte[] bytes = readBounded(connection, 1024 * 1024);
            if (epoch != generation || !resumed || !session.equals(panel.session) || !panel.previewAllowed) return;

            // 1. 仅解析边界尺寸
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth < 1 || bounds.outHeight < 1 || bounds.outWidth > 4096 || bounds.outHeight > 4096 ||
                (long) bounds.outWidth * bounds.outHeight > 16777216)
                throw new IOException("Preview dimensions");

            // 2. 采用 RGB_565 并按长/短边比例计算采样率（横竖屏皆适配 640×360 / 360×640，避免竖屏压扁）
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            options.inSampleSize = 1;
            int longSide = Math.max(bounds.outWidth, bounds.outHeight);
            int shortSide = Math.min(bounds.outWidth, bounds.outHeight);
            while (longSide / options.inSampleSize > 640 || shortSide / options.inSampleSize > 360)
                options.inSampleSize *= 2;

            decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (decoded == null) throw new IOException("Preview decode failed");
            final Bitmap bitmap = decoded;
            decoded = null;

            ui.post(() -> {
                if (!current(epoch, panel, session) || !panel.previewAllowed || sequence <= panel.previewSequence) {
                    bitmap.recycle();
                    return;
                }
                panel.setImage(bitmap, sequence, captured);
                panel.previewMessage = "";
                panel.renderPreview();
            });
        } catch (Exception failure) {
            ui.post(() -> {
                if (current(epoch, panel, session)) {
                    if (!panel.running || !panel.ready) panel.clearImage();
                    panel.previewMessage = (panel.openingScreen || panel.displayingScreen) ? I18n.s(R.string.ui_4bd5dfa2f736) : I18n.s(R.string.ui_2b1557f6ad3d);
                    panel.renderPreview();
                }
            });
        } finally {
            if (decoded != null) decoded.recycle();
            if (connection != null) {
                connection.disconnect();
                connections.remove(connection);
            }
        }
    }

    /** 校验当前异步回调是否仍旧对应当前打开的代次、当前面板和该内核会话 */
    private boolean current(int epoch, Panel panel, String session) {
        return epoch == generation && resumed && java.util.Arrays.asList(panels).contains(panel) && session.equals(panel.session);
    }

    /** 校验会话字符串格式（PID:START_TICK 纯数字配对） */
    private static boolean validSession(String value) {
        return value.matches("[1-9][0-9]*:[1-9][0-9]*");
    }

    /** 安全解析大于零的长整型 HTTP 头部字段 */
    private static long positiveHeader(HttpURLConnection connection, String name) throws IOException {
        try {
            long value = Long.parseLong(connection.getHeaderField(name));
            if (value <= 0) throw new NumberFormatException();
            return value;
        } catch (Exception bad) {
            throw new IOException("Invalid preview header");
        }
    }

    /**
     * 【用户管理操作触发：action】
     * 发送 POST /action 请求。
     * HTTP 202 仅代表 Broker 后台已受理并在队列中排队，界面并不直接标记成功，
     * 而是暂时禁用按钮并等待下一次 poll() 轮询时由 Broker 权威状态驱动刷新。
     */
    private void action(Panel panel, String type, String app) {
        final int epoch = generation, id = panel.id;
        final String session = panel.session;
        if (!type.equals("start") && !validSession(session)) return;
        panel.disable();
        panel.previewAllowed = false;
        if (type.equals("open")) {
            panel.openingScreen = true;
            panel.openGraceUntil = SystemClock.elapsedRealtime() + 2500;
            panel.previewMessage = I18n.s(R.string.ui_4bd5dfa2f736);
            panel.renderPreview();
        }
        if (type.equals("start") || type.equals("stop")) panel.clearImage();
        notice.setText(type.equals("open") ? I18n.s(R.string.ui_300d18c126af) : I18n.s(R.string.ui_f82e3b292bba));
        try {
            actions.execute(() -> {
                try {
                    String query = "/action?id=" + id + "&session=" + URLEncoder.encode(session, "UTF-8") + "&type=" + type + (app.isEmpty() ? "" : "&app=" + app);
                    request("POST", query, 202, epoch);
                    ui.post(() -> {
                        if (epoch == generation && resumed)
                            notice.setText(type.equals("open") ? I18n.s(R.string.ui_1f0d991f5ec0) + InstanceNames.title(panel.number) : I18n.s(R.string.ui_780a4c8c9b91));
                    });
                } catch (Exception error) {
                    ui.post(() -> {
                        if (epoch == generation && resumed) {
                            if (type.equals("open")) {
                                panel.openingScreen = false;
                                panel.openGraceUntil = 0;
                                panel.renderPreview();
                            }
                            notice.setText(I18n.s(R.string.ui_b96f65eefc0b));
                        }
                    });
                }
            });
        } catch (Exception busy) {
            notice.setText(I18n.s(R.string.ui_4a5b3e65561d));
        }
    }

    /** 切换实例卡片分页 */
    private void changePage(int next) {
        page = Math.max(0, next);
        for (Panel p : panels) { p.previewAllowed = false; p.clearImage(); }
        cards.removeAllViews();
        panels = new Panel[0];
        notice.setText(I18n.s(R.string.ui_98bcb8fa5647));
        if (resumed) startNetwork();
    }

    /** 根据 /state 返回的实例列表动态重构/复用 UI 面板卡片 */
    private void replacePanels(JSONArray states) {
        Panel[] old = panels;
        boolean same = old.length == states.length();
        for (int n = 0; same && n < old.length; n++)
            same = old[n].id == states.optJSONObject(n).optInt("id") && old[n].number == states.optJSONObject(n).optInt("number");
        if (same) return;

        for (Panel p : old) { p.previewAllowed = false; p.clearImage(); }
        cards.removeAllViews();
        Panel[] next = new Panel[states.length()];
        for (int n = 0; n < next.length; n++) {
            next[n] = new Panel(states.optJSONObject(n).optInt("id"), states.optJSONObject(n).optInt("number"));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
            lp.setMargins(dp(5), dp(5), dp(5), dp(4));
            cards.addView(next[n].root, lp);
        }
        panels = next;
        if (next.length == 0) {
            TextView empty = text(I18n.s(R.string.ui_e8b0602f9b64), 18);
            empty.setGravity(Gravity.CENTER);
            cards.addView(empty, new LinearLayout.LayoutParams(-1, -1));
        }
    }

    /** 弹出新增实例配置对话框（可勾选 Google Play 框架与 Magisk Root 选项） */
    private void createInstance() {
        if (!add.isEnabled()) return;
        final int epoch = generation;
        LinearLayout content = column();
        content.setPadding(dp(20), dp(8), dp(20), dp(8));
        TextView help = new TextView(this);
        help.setText(I18n.s(R.string.ui_847ed9179f56));
        help.setTextSize(15);
        content.addView(help);
        CheckBox google = new CheckBox(this);
        if ("builtin".equals(defaultRomGoogleMode)) {
            google.setText(I18n.s(R.string.ui_75d88b88c190));
            google.setChecked(true);
            google.setEnabled(false);
        } else if ("none".equals(defaultRomGoogleMode)) {
            google.setText(I18n.s(R.string.ui_a6be439cb8af));
            google.setChecked(false);
            google.setEnabled(false);
        } else {
            google.setText(I18n.s(R.string.ui_a655ea55c447));
        }
        content.addView(google);
        CheckBox rootChoice = new CheckBox(this);
        rootChoice.setText(I18n.s(R.string.ui_7ae34f47b8bb));
        rootChoice.setContentDescription("create-root-option");
        content.addView(rootChoice);

        new AlertDialog.Builder(this)
            .setTitle(I18n.s(R.string.ui_4b95f6df9752))
            .setView(content)
            .setNegativeButton(I18n.s(R.string.ui_4d0b4688c787), null)
            .setPositiveButton(I18n.s(R.string.ui_fcbd0932929e), (d, w) -> {
                if (epoch != generation || !resumed) return;
                creating = true;
                add.setEnabled(false);
                notice.setText(I18n.s(R.string.ui_4a6fc3dd7805));
                final int enabled = google.isChecked() ? 1 : 0;
                final String rootProfile = rootChoice.isChecked() ? "magisk" : "off";
                try {
                    actions.execute(() -> {
                        try {
                            request("POST", "/instances/create?google=" + enabled + "&root=" + rootProfile, 202, epoch);
                        } catch (Exception e) {
                            ui.post(() -> {
                                if (epoch == generation) {
                                    creating = false;
                                    notice.setText(I18n.s(R.string.ui_5dd2ec509b6f));
                                }
                            });
                        }
                    });
                } catch (Exception e) {
                    creating = false;
                    notice.setText(I18n.s(R.string.ui_4a5b3e65561d));
                }
            }).show();
    }

    /** 弹出重置数据或彻底删除实例的二次确认对话框（需输入编号防误触） */
    private void clearData(Panel panel, String type) {
        if (panel.running || !panel.revision.matches("[a-f0-9]{64}")) return;
        final int epoch = generation;
        final String revision = panel.revision;
        boolean deleting = type.equals("delete");
        LinearLayout content = column();
        content.setPadding(dp(20), dp(8), dp(20), dp(8));
        TextView help = new TextView(this);
        help.setTextSize(15);
        help.setText((deleting ? I18n.s(R.string.ui_b1b8c89c34e7) : I18n.s(R.string.ui_13357ab34dca)) +
            I18n.s(R.string.ui_9eef47300b92) + panel.number + I18n.s(R.string.ui_371b8345ec33));
        content.addView(help);
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(I18n.s(R.string.ui_91b40ea9119e));
        content.addView(input);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle((deleting ? I18n.s(R.string.ui_3755f56f2f83) : I18n.s(R.string.ui_3d81345303ab)) + InstanceNames.title(panel.number))
            .setView(content)
            .setNegativeButton(I18n.s(R.string.ui_4d0b4688c787), null)
            .setPositiveButton(deleting ? I18n.s(R.string.ui_3c06abe11651) : I18n.s(R.string.ui_30a6079ce340), null)
            .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!Integer.toString(panel.number).equals(input.getText().toString().trim())) {
                input.setError(I18n.s(R.string.ui_75f8cff9ecbb) + panel.number);
                return;
            }
            if (epoch != generation || !resumed || !Arrays.asList(panels).contains(panel) || panel.running || !revision.equals(panel.revision)) {
                dialog.dismiss();
                return;
            }
            panel.disable();
            dialog.dismiss();
            notice.setText(deleting ? I18n.s(R.string.ui_a3f772ee79f4) : I18n.s(R.string.ui_24af1956d124));
            try {
                actions.execute(() -> {
                    try {
                        request("POST", "/action?id=" + panel.id + "&session=&type=" + type + "&revision=" + revision + "&confirm=" + panel.number, 202, epoch);
                    } catch (Exception e) {
                        ui.post(() -> {
                            if (epoch == generation) notice.setText(I18n.s(R.string.ui_9eeeaf573a19));
                        });
                    }
                });
            } catch (Exception e) {
                notice.setText(I18n.s(R.string.ui_4a5b3e65561d));
            }
        }));
        dialog.show();
    }

    /** 弹出应用 Root（Magisk）配置与救援模式管理对话框 */
    private void applicationRoot(Panel panel) {
        if (!panel.rootValid) return;
        final int epoch = generation;
        final String previous = panel.rootProfile, revision = panel.revision;
        LinearLayout content = column();
        content.setPadding(dp(20), dp(8), dp(20), dp(8));
        TextView help = new TextView(this);
        help.setTextSize(15);
        help.setText(I18n.s(R.string.ui_4a3c8724af26) +
            (panel.running ? panel.rootReady ? I18n.s(R.string.ui_5f6720f567bc) : I18n.s(R.string.ui_3383d777d4d8) + (previous.equals("off") ? I18n.s(R.string.ui_2ecfd7e9af20) : I18n.s(R.string.ui_0ea00180a025)) : ""));
        content.addView(help);

        CheckBox choice = new CheckBox(this);
        choice.setText(I18n.s(R.string.ui_9e3667cb5ffd));
        choice.setContentDescription("root-enabled-option");
        choice.setChecked(!previous.equals("off"));
        choice.setEnabled(!panel.running);
        content.addView(choice);

        CheckBox rescue = new CheckBox(this);
        rescue.setText(I18n.s(R.string.ui_f7a937916bc3));
        rescue.setContentDescription("root-rescue-option");
        rescue.setChecked(previous.equals("magisk-safe"));
        rescue.setEnabled(!panel.running && choice.isChecked());
        content.addView(rescue);

        choice.setOnCheckedChangeListener((b, checked) -> {
            rescue.setEnabled(!panel.running && checked);
            if (!checked) rescue.setChecked(false);
        });

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(InstanceNames.title(panel.number) + I18n.s(R.string.ui_b9ebebdde63f))
            .setView(content)
            .setNegativeButton(I18n.s(R.string.ui_6c14bd7f6f9e), null);

        if (panel.running) {
            if (panel.rootReady)
                builder.setPositiveButton(I18n.s(R.string.ui_7744147917be), (d, w) -> {
                    if (epoch == generation && resumed && Arrays.asList(panels).contains(panel) && panel.rootReady)
                        action(panel, "app", "magisk");
                });
        } else {
            builder.setPositiveButton(I18n.s(R.string.ui_6d9bea7b8726), (d, w) -> {
                if (epoch != generation || !resumed || !Arrays.asList(panels).contains(panel) || panel.running || !revision.equals(panel.revision))
                    return;
                String selected = choice.isChecked() ? rescue.isChecked() ? "magisk-safe" : "magisk" : "off";
                panel.disable();
                notice.setText(I18n.s(R.string.ui_0e6529e156a5));
                try {
                    actions.execute(() -> {
                        try {
                            request("POST", "/action?id=" + panel.id + "&session=&type=root&profile=" + selected + "&previousProfile=" + previous + "&revision=" + revision, 202, epoch);
                        } catch (Exception e) {
                            ui.post(() -> {
                                if (epoch == generation) notice.setText(I18n.s(R.string.ui_0218a26e7532));
                            });
                        }
                    });
                } catch (Exception e) {
                    notice.setText(I18n.s(R.string.ui_4a5b3e65561d));
                }
            });
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        builder.setView(scroll).show();
    }

    /** 唤起文件管理 Activity 与当前实例互传文件 */
    private void files(Panel panel) {
        if (!panel.ready || !validSession(panel.session)) return;
        Intent intent = new Intent(this, FileActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra("instanceId", panel.id)
            .putExtra("instanceNumber", panel.number)
            .putExtra("session", panel.session)
            .putExtra("brokerToken", brokerToken)
            .putExtra("brokerPort", 19762);
        startActivity(intent);
        overridePendingTransition(0, 0);
    }

    /** 枚举宿主或指定运行中实例已安装第三方应用并直接迁入目标实例（支持 Split APKs、OBB、Android/data 与私有数据） */
    private void importHostApp(Panel panel) {
        if (!panel.ready || !validSession(panel.session)) return;
        final int epoch = generation, id = panel.id, number = panel.number;
        final String session = panel.session;
        final AlertDialog loading = new AlertDialog.Builder(this)
            .setTitle(InstanceNames.title(number) + I18n.s(R.string.ui_e4855d59ac50))
            .setMessage(I18n.s(R.string.ui_8c5e2a1bcdac))
            .setCancelable(true)
            .show();

        try {
            actions.execute(() -> {
                try {
                    JSONObject st = new JSONObject(new String(request("GET", "/state?offset=0&limit=128", 200, epoch, 8000, 524288), "UTF-8"));
                    JSONArray instances = st.optJSONArray("instances");
                    final ArrayList<String> srcValues = new ArrayList<>();
                    final ArrayList<String> srcLabels = new ArrayList<>();
                    srcValues.add("host");
                    srcLabels.add(I18n.s(R.string.ui_65bc12869c9f));
                    if (instances != null) {
                        for (int i = 0; i < instances.length(); i++) {
                            JSONObject inst = instances.optJSONObject(i);
                            if (inst == null) continue;
                            int oid = inst.optInt("id", 0);
                            if (oid == 0 || oid == id) continue;
                            if (!inst.optBoolean("running") || !inst.optBoolean("ready")) continue;
                            int onum = inst.optInt("number", 0);
                            String title = onum > 0 ? InstanceNames.title(onum) : ("ID " + oid);
                            srcValues.add("instance:" + oid);
                            srcLabels.add(String.format(java.util.Locale.ROOT, I18n.s(R.string.ui_bec9f0deb38a), title));
                        }
                    }

                    ui.post(() -> {
                        if (loading.isShowing()) loading.dismiss();
                        if (epoch != generation || !resumed || !validSession(panel.session)) return;
                        if (srcValues.size() == 1) {
                            loadAppsFromSource(panel, id, number, session, "host", srcLabels.get(0), epoch);
                            return;
                        }
                        final int[] chosen = {0};
                        new AlertDialog.Builder(this)
                            .setTitle(InstanceNames.title(number) + I18n.s(R.string.ui_e4855d59ac50) + " · " + I18n.s(R.string.ui_f63db871a067))
                            .setSingleChoiceItems(srcLabels.toArray(new String[0]), 0, (d, which) -> chosen[0] = which)
                            .setNegativeButton(I18n.s(R.string.ui_4d0b4688c787), null)
                            .setPositiveButton(I18n.s(R.string.ui_6c14bd7f6f9e), (d, w) -> {
                                int idx = chosen[0];
                                loadAppsFromSource(panel, id, number, session, srcValues.get(idx), srcLabels.get(idx), epoch);
                            })
                            .show();
                    });
                } catch (Exception e) {
                    ui.post(() -> {
                        if (loading.isShowing()) loading.dismiss();
                        if (epoch == generation && resumed) notice.setText(I18n.s(R.string.ui_cfbc5107c8bf));
                    });
                }
            });
        } catch (Exception busy) {
            if (loading.isShowing()) loading.dismiss();
            notice.setText(I18n.s(R.string.ui_4a5b3e65561d));
        }
    }

    private void loadAppsFromSource(Panel panel, int id, int number, String session, String source, String sourceLabel, int epoch) {
        final AlertDialog loading = new AlertDialog.Builder(this)
            .setTitle(InstanceNames.title(number) + I18n.s(R.string.ui_e4855d59ac50))
            .setMessage(I18n.s(R.string.ui_8c5e2a1bcdac))
            .setCancelable(true)
            .show();

        try {
            actions.execute(() -> {
                try {
                    String listPath = "/apps/list?source=" + URLEncoder.encode(source, "UTF-8");
                    JSONObject resp = new JSONObject(new String(request("GET", listPath, 200, epoch, 30000, 1048576), "UTF-8"));
                    JSONArray raw = resp.optJSONArray("apps");
                    PackageManager pm = getPackageManager();
                    final ArrayList<JSONObject> items = new ArrayList<>();
                    if (raw != null) {
                        for (int n = 0; n < raw.length(); n++) {
                            JSONObject entry = raw.optJSONObject(n);
                            if (entry == null) continue;
                            String pkg = entry.optString("pkg", "");
                            if (pkg.isEmpty()) continue;
                            String appLabel = entry.optString("label", "").trim();
                            if (appLabel.isEmpty() || appLabel.equals(pkg)) {
                                String resolved = pkg;
                                try {
                                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                                    CharSequence cs = pm.getApplicationLabel(ai);
                                    if (cs != null && cs.length() > 0) resolved = cs.toString();
                                } catch (Exception ignored) {}
                                appLabel = resolved;
                            }
                            entry.put("label", appLabel);
                            items.add(entry);
                        }
                    }
                    java.util.Collections.sort(items, (a, b) -> {
                        long tb = b.optLong("totalBytes", 0), ta = a.optLong("totalBytes", 0);
                        int cmp = a.optString("label").compareToIgnoreCase(b.optString("label"));
                        return "host".equals(source) ? cmp : (tb != ta ? Long.compare(tb, ta) : cmp);
                    });

                    ui.post(() -> {
                        if (loading.isShowing()) loading.dismiss();
                        if (epoch != generation || !resumed || !validSession(panel.session)) return;
                        if (items.isEmpty()) {
                            new AlertDialog.Builder(this)
                                .setTitle(InstanceNames.title(number) + I18n.s(R.string.ui_e4855d59ac50))
                                .setMessage(I18n.s(R.string.ui_b3bec5fd233e))
                                .setPositiveButton(I18n.s(R.string.ui_6c14bd7f6f9e), null)
                                .show();
                            return;
                        }

                        LinearLayout content = column();
                        content.setPadding(dp(18), dp(8), dp(18), dp(8));
                        TextView help = new TextView(this);
                        help.setTextSize(14);
                        help.setText(sourceLabel + "\n" + I18n.s(R.string.ui_f45b850c64cc));
                        content.addView(help);

                        CheckBox obbCheck = new CheckBox(this);
                        obbCheck.setText(I18n.s(R.string.ui_2eb55f49fee8));
                        obbCheck.setChecked(true);
                        content.addView(obbCheck);

                        CheckBox extDataCheck = new CheckBox(this);
                        extDataCheck.setText(I18n.s(R.string.ui_d7e9f3e7c89f));
                        extDataCheck.setChecked(true);
                        content.addView(extDataCheck);

                        final CheckBox appDataCheck = new CheckBox(this);
                        appDataCheck.setText(I18n.s(R.string.ui_43a05689d2d1));
                        appDataCheck.setChecked(false);
                        if (!"host".equals(source)) {
                            appDataCheck.setOnClickListener(v -> {
                                if (appDataCheck.isChecked()) {
                                    // 必须先重置为未勾选，并通过文字确认口令才能正式解锁
                                    appDataCheck.setChecked(false);
                                    appDataCheck.setTextColor(0xFFE0E0E0);
                                    promptAppDataRiskUnlock(appDataCheck);
                                } else {
                                    appDataCheck.setTextColor(0xFFE0E0E0);
                                }
                            });
                            content.addView(appDataCheck);
                        }

                        RadioGroup choices = new RadioGroup(this);
                        final int[] selected = {0};
                        for (int n = 0; n < items.size(); n++) {
                            JSONObject entry = items.get(n);
                            String label = entry.optString("label");
                            String pkg = entry.optString("pkg");
                            long apkBytes = entry.optLong("apkBytes", 0);
                            int splits = entry.optInt("splitCount", 1);
                            long obbBytes = entry.optLong("obbBytes", 0);
                            long extBytes = entry.optLong("extDataBytes", 0);
                            long appBytes = entry.optLong("appDataBytes", 0);

                            String extra = (splits > 1 ? String.format(java.util.Locale.ROOT, I18n.s(R.string.ui_9630b7b37bf7), splits) : "")
                                + (obbBytes > 0 ? String.format(java.util.Locale.ROOT, I18n.s(R.string.ui_05f5f2434ff0), formatAppSize(obbBytes)) : "")
                                + (extBytes > 0 ? String.format(java.util.Locale.ROOT, I18n.s(R.string.ui_a0d49c672dbf), formatAppSize(extBytes)) : "")
                                + (appBytes > 0 ? String.format(java.util.Locale.ROOT, I18n.s(R.string.ui_9c99415ac51a), formatAppSize(appBytes)) : "");

                            boolean highRisk = isHighRiskSocialApp(pkg);
                            String displayLabel = highRisk ? (label + I18n.s(R.string.ui_dddc64b67449)) : label;

                            RadioButton radio = new RadioButton(this);
                            radio.setId(View.generateViewId());
                            radio.setTextSize(14);
                            radio.setPadding(dp(4), dp(8), dp(4), dp(8));
                            if (highRisk) radio.setTextColor(0xFFFFB74D);
                            radio.setText(String.format(java.util.Locale.ROOT, I18n.s(R.string.ui_8fe932fbeb20), displayLabel, pkg, formatAppSize(apkBytes), extra));
                            choices.addView(radio);
                            if (n == 0) radio.setChecked(true);
                            final int idx = n;
                            radio.setOnClickListener(v -> selected[0] = idx);
                        }
                        ScrollView listScroll = new ScrollView(this);
                        listScroll.addView(choices);
                        content.addView(listScroll, new LinearLayout.LayoutParams(-1, dp(300)));

                        new AlertDialog.Builder(this)
                            .setTitle(InstanceNames.title(number) + I18n.s(R.string.ui_e4855d59ac50))
                            .setView(content)
                            .setNegativeButton(I18n.s(R.string.ui_4d0b4688c787), null)
                            .setPositiveButton(I18n.s(R.string.ui_51256e8caae0), (d, w) -> {
                                JSONObject target = items.get(selected[0]);
                                String targetPkg = target.optString("pkg");
                                String targetLabel = target.optString("label");
                                boolean withObb = obbCheck.isChecked();
                                boolean withExtData = extDataCheck.isChecked();
                                boolean withAppData = !"host".equals(source) && appDataCheck.isChecked();
                                if (isHighRiskSocialApp(targetPkg)) {
                                    if (withAppData) {
                                        new AlertDialog.Builder(this)
                                            .setTitle(I18n.s(R.string.ui_983198675136))
                                            .setMessage(I18n.s(R.string.ui_90cf6d0198c4))
                                            .setPositiveButton(I18n.s(R.string.ui_6c14bd7f6f9e), null)
                                            .show();
                                        return;
                                    }
                                    new AlertDialog.Builder(this)
                                        .setTitle(I18n.s(R.string.ui_15420a5702e0))
                                        .setMessage(I18n.s(R.string.ui_6fe9974f4c1c))
                                        .setNegativeButton(I18n.s(R.string.ui_4d0b4688c787), null)
                                        .setPositiveButton(I18n.s(R.string.ui_51256e8caae0), (d2, w2) ->
                                            startHostAppImport(id, number, session, source, targetPkg, targetLabel, withObb, withExtData, false, epoch))
                                        .show();
                                    return;
                                }
                                startHostAppImport(id, number, session, source, targetPkg, targetLabel, withObb, withExtData, withAppData, epoch);
                            })
                            .show();
                    });
                } catch (Exception e) {
                    ui.post(() -> {
                        if (loading.isShowing()) loading.dismiss();
                        if (epoch == generation && resumed) notice.setText(I18n.s(R.string.ui_cfbc5107c8bf));
                    });
                }
            });
        } catch (Exception busy) {
            if (loading.isShowing()) loading.dismiss();
            notice.setText(I18n.s(R.string.ui_4a5b3e65561d));
        }
    }

    /** 判定高风控即时通讯/社交包（跨设备指纹容器迁移极易触发封控，禁止迁入 /data/data）。 */
    private static boolean isHighRiskSocialApp(String pkg) {
        return "com.tencent.mm".equals(pkg)
            || "com.tencent.mobileqq".equals(pkg)
            || "com.tencent.tim".equals(pkg)
            || "com.tencent.wework".equals(pkg)
            || "com.tencent.qqlite".equals(pkg);
    }

    /** 弹出高危账号封控警告框，必须手动输入指定确认文字后才解锁 /data/data 迁移勾选。 */
    private void promptAppDataRiskUnlock(CheckBox appDataCheck) {
        final String requiredPhrase = I18n.s(R.string.ui_843303cad082);
        LinearLayout box = column();
        box.setPadding(dp(20), dp(12), dp(20), dp(8));

        TextView warn = new TextView(this);
        warn.setTextSize(14);
        warn.setTextColor(0xFFFF8A80);
        warn.setText(String.format(java.util.Locale.ROOT, I18n.s(R.string.ui_a77a7e1a6afa), requiredPhrase));
        box.addView(warn);

        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(String.format(java.util.Locale.ROOT, I18n.s(R.string.ui_4449b0306813), requiredPhrase));
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF9E9E9E);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        box.addView(input, lp);

        new AlertDialog.Builder(this)
            .setTitle(I18n.s(R.string.ui_cda744734fbe))
            .setView(box)
            .setNegativeButton(I18n.s(R.string.ui_4d0b4688c787), null)
            .setPositiveButton(I18n.s(R.string.ui_c3ad3c4f53a4), (d, w) -> {
                String entered = input.getText() == null ? "" : input.getText().toString().trim();
                if (requiredPhrase.equals(entered)) {
                    appDataCheck.setChecked(true);
                    appDataCheck.setTextColor(0xFFFFB74D);
                    Toast.makeText(this, I18n.s(R.string.ui_897fd4797df7), Toast.LENGTH_SHORT).show();
                } else {
                    appDataCheck.setChecked(false);
                    appDataCheck.setTextColor(0xFFE0E0E0);
                    Toast.makeText(this, I18n.s(R.string.ui_c2f495d6703b), Toast.LENGTH_LONG).show();
                }
            })
            .show();
    }

    private void startHostAppImport(int id, int number, String session, String source, String pkg, String label,
            boolean withObb, boolean withExtData, boolean withAppData, int epoch) {
        LinearLayout content = column();
        content.setPadding(dp(20), dp(12), dp(20), dp(12));
        TextView detail = new TextView(this);
        detail.setTextSize(14);
        detail.setText(String.format(java.util.Locale.ROOT, I18n.s(R.string.ui_33b107080eaf), label));
        content.addView(detail);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setIndeterminate(true);
        content.addView(bar, new LinearLayout.LayoutParams(-1, dp(24)));

        final boolean[] closed = {false};
        final AlertDialog progressDialog = new AlertDialog.Builder(this)
            .setTitle(InstanceNames.title(number) + I18n.s(R.string.ui_e4855d59ac50))
            .setView(content)
            .setPositiveButton(I18n.s(R.string.ui_6c14bd7f6f9e), (d, w) -> closed[0] = true)
            .setOnDismissListener(d -> closed[0] = true)
            .create();
        progressDialog.show();

        try {
            actions.execute(() -> {
                try {
                    String path = "/apps/import?id=" + id
                        + "&session=" + URLEncoder.encode(session, "UTF-8")
                        + "&source=" + URLEncoder.encode(source, "UTF-8")
                        + "&pkg=" + URLEncoder.encode(pkg, "UTF-8")
                        + "&obb=" + (withObb ? "1" : "0")
                        + "&extData=" + (withExtData ? "1" : "0")
                        + "&appData=" + (withAppData ? "1" : "0");
                    request("POST", path, 202, epoch, 20000, 65536);
                    ui.postDelayed(() -> pollHostAppImport(id, session, label, epoch, progressDialog, detail, bar, closed), 600);
                } catch (Exception e) {
                    ui.post(() -> {
                        if (progressDialog.isShowing()) progressDialog.dismiss();
                        if (epoch == generation && resumed) {
                            Toast.makeText(this, I18n.s(R.string.ui_f344492a9aff), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            });
        } catch (Exception busy) {
            if (progressDialog.isShowing()) progressDialog.dismiss();
            notice.setText(I18n.s(R.string.ui_4a5b3e65561d));
        }
    }

    private void pollHostAppImport(int id, String session, String label, int epoch,
            AlertDialog dialog, TextView detail, ProgressBar bar, boolean[] closed) {
        if (epoch != generation || !resumed) return;
        try {
            actions.execute(() -> {
                try {
                    String path = "/apps/import/status?id=" + id + "&session=" + URLEncoder.encode(session, "UTF-8");
                    JSONObject st = new JSONObject(new String(request("GET", path, 200, epoch, 3000, 65536), "UTF-8"));
                    boolean finished = st.optBoolean("finished");
                    boolean success = st.optBoolean("success");
                    long done = st.optLong("doneBytes", 0), total = st.optLong("totalBytes", 0);
                    String statusDetail = st.optString("detail", "");
                    String error = st.optString("error", "");

                    ui.post(() -> {
                        if (epoch != generation || !resumed) return;
                        if (!closed[0] && dialog.isShowing()) {
                            if (total > 0) {
                                bar.setIndeterminate(false);
                                bar.setProgress((int) Math.min(1000L, done * 1000L / total));
                                detail.setText(statusDetail + "\n" + formatAppSize(done) + " / " + formatAppSize(total));
                            } else if (!statusDetail.isEmpty()) {
                                detail.setText(statusDetail);
                            }
                        }
                        if (finished) {
                            if (success) {
                                if (!closed[0] && dialog.isShowing()) dialog.dismiss();
                                Toast.makeText(this, I18n.s(R.string.ui_09f06e521c97) + label, Toast.LENGTH_LONG).show();
                            } else {
                                if (!closed[0] && dialog.isShowing()) {
                                    bar.setIndeterminate(false);
                                    bar.setProgress(0);
                                    detail.setText(I18n.s(R.string.ui_c8446ed45e71) + error);
                                }
                                Toast.makeText(this, I18n.s(R.string.ui_c8446ed45e71) + label, Toast.LENGTH_LONG).show();
                            }
                        } else {
                            ui.postDelayed(() -> pollHostAppImport(id, session, label, epoch, dialog, detail, bar, closed), 800);
                        }
                    });
                } catch (Exception e) {
                    ui.postDelayed(() -> pollHostAppImport(id, session, label, epoch, dialog, detail, bar, closed), 1200);
                }
            });
        } catch (Exception ignored) {
            ui.postDelayed(() -> pollHostAppImport(id, session, label, epoch, dialog, detail, bar, closed), 1200);
        }
    }

    private static String formatAppSize(long bytes) {
        if (bytes >= 1073741824L) return String.format(java.util.Locale.ROOT, "%.2f GiB", bytes / 1073741824.0);
        if (bytes >= 1048576L) return String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / 1048576.0);
        if (bytes >= 1024L) return String.format(java.util.Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        return bytes + " B";
    }

    /** 双向应用互通入口：支持从宿主机/其他实例导入到本实例，或从本实例导出安装包/直接安装到宿主机 */
    private void importOrExportApp(Panel panel) {
        if (!panel.ready || !validSession(panel.session)) return;
        String[] modes = {
            I18n.known("导入应用到本实例（来自宿主机 / 其他实例）"),
            I18n.known("导出应用到宿主机（保存 APK 或安装到宿主机）")
        };
        new AlertDialog.Builder(this)
            .setTitle(InstanceNames.title(panel.number) + " · " + I18n.known("导入/导出应用"))
            .setItems(modes, (d, which) -> {
                if (which == 0) importHostApp(panel);
                else exportAppFromGuest(panel);
            })
            .setNegativeButton(I18n.s(R.string.ui_4d0b4688c787), null)
            .show();
    }

    /** 从当前实例枚举已安装第三方应用并导出至宿主机（APK 文件或宿主机 pm 安装，严禁导出 /data/data） */
    private void exportAppFromGuest(Panel panel) {
        if (!panel.ready || !validSession(panel.session)) return;
        final int epoch = generation, id = panel.id, number = panel.number;
        final String session = panel.session;
        final AlertDialog loading = new AlertDialog.Builder(this)
            .setTitle(InstanceNames.title(number) + " · " + I18n.known("导出应用到宿主机"))
            .setMessage(I18n.s(R.string.ui_8c5e2a1bcdac))
            .setCancelable(true)
            .show();
        try {
            actions.execute(() -> {
                try {
                    String listPath = "/apps/list?source=" + URLEncoder.encode("instance:" + id, "UTF-8");
                    JSONObject resp = new JSONObject(new String(request("GET", listPath, 200, epoch, 30000, 1048576), "UTF-8"));
                    JSONArray raw = resp.optJSONArray("apps");
                    final ArrayList<JSONObject> items = new ArrayList<>();
                    if (raw != null) {
                        for (int n = 0; n < raw.length(); n++) {
                            JSONObject entry = raw.optJSONObject(n);
                            if (entry != null && !entry.optString("pkg", "").isEmpty()) items.add(entry);
                        }
                    }
                    java.util.Collections.sort(items, (a, b) -> Long.compare(b.optLong("apkBytes", 0), a.optLong("apkBytes", 0)));

                    ui.post(() -> {
                        if (loading.isShowing()) loading.dismiss();
                        if (epoch != generation || !resumed || !validSession(panel.session)) return;
                        if (items.isEmpty()) {
                            new AlertDialog.Builder(this)
                                .setTitle(InstanceNames.title(number) + " · " + I18n.known("导出应用到宿主机"))
                                .setMessage(I18n.known("当前实例未找到可导出的第三方应用。"))
                                .setPositiveButton(android.R.string.ok, null).show();
                            return;
                        }
                        LinearLayout box = column();
                        box.setPadding(dp(16), dp(8), dp(16), dp(6));
                        TextView tip = text(I18n.known("选择要导出的容器应用（仅导出 APK 与可选 OBB，不含账号私密数据）："), 13);
                        box.addView(tip);
                        RadioGroup rg = new RadioGroup(this);
                        for (int i = 0; i < items.size(); i++) {
                            JSONObject it = items.get(i);
                            RadioButton rb = new RadioButton(this);
                            rb.setId(View.generateViewId());
                            int splits = it.optInt("splitCount", 1);
                            long obbB = it.optLong("obbBytes", 0);
                            String pkg = it.optString("pkg", "");
                            String appLabel = it.optString("label", "").trim();
                            if (appLabel.isEmpty()) appLabel = pkg;
                            String appTitle = appLabel.equals(pkg) ? pkg : (appLabel + "  (" + pkg + ")");
                            String line = appTitle + "\nAPK " + formatAppSize(it.optLong("apkBytes", 0))
                                + (splits > 1 ? " (" + splits + " splits)" : "")
                                + (obbB > 0 ? " · OBB " + formatAppSize(obbB) : "");
                            rb.setText(line);
                            rb.setTextSize(13);
                            rb.setPadding(dp(4), dp(6), dp(4), dp(6));
                            rg.addView(rb);
                            if (i == 0) rb.setChecked(true);
                        }
                        ScrollView sc = new ScrollView(this);
                        sc.addView(rg);
                        box.addView(sc, new LinearLayout.LayoutParams(-1, dp(260)));

                        final int[] modeChoice = {0};
                        RadioGroup targetGroup = new RadioGroup(this);
                        RadioButton rFile = new RadioButton(this);
                        rFile.setId(View.generateViewId());
                        rFile.setText(I18n.known("导出安装包至宿主下载目录 (Download/IrisBoxExport)"));
                        rFile.setChecked(true);
                        rFile.setOnClickListener(v -> modeChoice[0] = 0);
                        RadioButton rInstall = new RadioButton(this);
                        rInstall.setId(View.generateViewId());
                        rInstall.setText(I18n.known("直接安装到宿主机系统"));
                        rInstall.setOnClickListener(v -> modeChoice[0] = 1);
                        targetGroup.addView(rFile);
                        targetGroup.addView(rInstall);
                        box.addView(targetGroup);

                        CheckBox cbObb = new CheckBox(this);
                        cbObb.setText(I18n.known("同时迁入 OBB 数据包（若有）"));
                        cbObb.setChecked(true);
                        box.addView(cbObb);

                        new AlertDialog.Builder(this)
                            .setTitle(InstanceNames.title(number) + " · " + I18n.known("导出应用到宿主机"))
                            .setView(box)
                            .setNegativeButton(I18n.s(R.string.ui_4d0b4688c787), null)
                            .setPositiveButton(I18n.known("开始导出"), (dlg, w) -> {
                                int idx = rg.indexOfChild(rg.findViewById(rg.getCheckedRadioButtonId()));
                                if (idx < 0 || idx >= items.size()) return;
                                JSONObject picked = items.get(idx);
                                String pkg = picked.optString("pkg", "");
                                String appLabel = picked.optString("label", "").trim();
                                if (appLabel.isEmpty()) appLabel = pkg;
                                String mode = modeChoice[0] == 1 ? "install_host" : "apk_file";
                                startGuestAppExport(panel, id, number, session, pkg, appLabel, mode, cbObb.isChecked(), epoch);
                            }).show();
                    });
                } catch (Exception e) {
                    ui.post(() -> {
                        if (loading.isShowing()) loading.dismiss();
                        if (epoch == generation && resumed) notice.setText(I18n.known("读取容器应用列表失败，请稍后重试"));
                    });
                }
            });
        } catch (Exception busy) {
            if (loading.isShowing()) loading.dismiss();
            notice.setText(I18n.s(R.string.ui_4a5b3e65561d));
        }
    }

    private void startGuestAppExport(Panel panel, int id, int number, String session,
            String pkg, String appLabel, String targetMode, boolean withObb, int epoch) {
        final String displayName = (appLabel == null || appLabel.isEmpty() || appLabel.equals(pkg))
            ? pkg : (appLabel + " (" + pkg + ")");
        final boolean[] closed = {false};
        LinearLayout box = column();
        box.setPadding(dp(20), dp(14), dp(20), dp(10));
        TextView detail = text(I18n.known("正在准备导出应用：") + displayName, 14);
        box.addView(detail);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setIndeterminate(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        box.addView(bar, lp);

        final AlertDialog progressDialog = new AlertDialog.Builder(this)
            .setTitle(InstanceNames.title(number) + " · " + displayName)
            .setView(box)
            .setCancelable(true)
            .setNegativeButton(I18n.known("后台继续"), (d, w) -> closed[0] = true)
            .setOnCancelListener(d -> closed[0] = true)
            .create();
        progressDialog.show();

        try {
            actions.execute(() -> {
                try {
                    String path = "/apps/export?id=" + id
                        + "&session=" + URLEncoder.encode(session, "UTF-8")
                        + "&pkg=" + URLEncoder.encode(pkg, "UTF-8")
                        + "&targetMode=" + URLEncoder.encode(targetMode, "UTF-8")
                        + "&obb=" + (withObb ? "1" : "0");
                    request("POST", path, 202, epoch, 20000, 65536);
                    ui.postDelayed(() -> pollGuestAppExport(id, session, pkg, epoch, progressDialog, detail, bar, closed), 600);
                } catch (Exception e) {
                    ui.post(() -> {
                        if (progressDialog.isShowing()) progressDialog.dismiss();
                        if (epoch == generation && resumed) {
                            Toast.makeText(this, I18n.known("提交应用导出失败：") + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            });
        } catch (Exception busy) {
            if (progressDialog.isShowing()) progressDialog.dismiss();
            notice.setText(I18n.s(R.string.ui_4a5b3e65561d));
        }
    }

    private void pollGuestAppExport(int id, String session, String pkg, int epoch,
            AlertDialog dialog, TextView detail, ProgressBar bar, boolean[] closed) {
        if (epoch != generation || !resumed) return;
        try {
            actions.execute(() -> {
                try {
                    String path = "/apps/export/status?id=" + id + "&session=" + URLEncoder.encode(session, "UTF-8");
                    JSONObject st = new JSONObject(new String(request("GET", path, 200, epoch, 3000, 65536), "UTF-8"));
                    boolean finished = st.optBoolean("finished");
                    boolean success = st.optBoolean("success");
                    long done = st.optLong("doneBytes", 0), total = st.optLong("totalBytes", 0);
                    String statusDetail = st.optString("detail", "");
                    String error = st.optString("error", "");

                    ui.post(() -> {
                        if (epoch != generation || !resumed) return;
                        if (!closed[0] && dialog.isShowing()) {
                            if (total > 0) {
                                bar.setIndeterminate(false);
                                bar.setProgress((int) Math.min(1000L, done * 1000L / total));
                                detail.setText(statusDetail + "\n" + formatAppSize(done) + " / " + formatAppSize(total));
                            } else if (!statusDetail.isEmpty()) {
                                detail.setText(statusDetail);
                            }
                        }
                        if (finished) {
                            if (success) {
                                if (!closed[0] && dialog.isShowing()) dialog.dismiss();
                                Toast.makeText(this, statusDetail, Toast.LENGTH_LONG).show();
                                notice.setText(statusDetail);
                            } else {
                                if (!closed[0] && dialog.isShowing()) {
                                    bar.setIndeterminate(false);
                                    bar.setProgress(0);
                                    detail.setText(I18n.known("应用导出失败：") + error);
                                }
                                Toast.makeText(this, I18n.known("应用导出失败：") + error, Toast.LENGTH_LONG).show();
                            }
                        } else {
                            ui.postDelayed(() -> pollGuestAppExport(id, session, pkg, epoch, dialog, detail, bar, closed), 800);
                        }
                    });
                } catch (Exception e) {
                    ui.postDelayed(() -> pollGuestAppExport(id, session, pkg, epoch, dialog, detail, bar, closed), 1200);
                }
            });
        } catch (Exception ignored) {
            ui.postDelayed(() -> pollGuestAppExport(id, session, pkg, epoch, dialog, detail, bar, closed), 1200);
        }
    }

    /** 弹出实例音频控制弹窗（输出开关、静音、实时音量及输入说明） */
    private void audioControl(Panel panel) {
        final int epoch = generation, id = panel.id;
        LinearLayout box = column();
        box.setPadding(dp(18), dp(10), dp(18), dp(6));

        Switch swOut = new Switch(this);
        swOut.setText(I18n.known("启用实例音频输出"));
        swOut.setChecked(panel.audioOutEnabled);
        box.addView(swOut);

        Switch swMute = new Switch(this);
        swMute.setText(I18n.known("静音（保活连接不卡应用）"));
        swMute.setChecked(panel.audioMuted);
        box.addView(swMute);

        TextView volLabel = text(I18n.known("输出音量：") + panel.audioVolume + "%", 14);
        volLabel.setPadding(0, dp(10), 0, dp(4));
        box.addView(volLabel);

        SeekBar volBar = new SeekBar(this);
        volBar.setMax(100);
        volBar.setProgress(panel.audioVolume);
        volBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { volLabel.setText(I18n.known("输出音量：") + p + "%"); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        box.addView(volBar);

        TextView micNote = text(I18n.known("输入（麦克风）：当前设备无物理麦克风直通，默认保持静音输入。"), 12);
        micNote.setTextColor(Color.LTGRAY);
        micNote.setPadding(0, dp(10), 0, 0);
        box.addView(micNote);

        new AlertDialog.Builder(this)
            .setTitle(InstanceNames.title(panel.number) + " · " + I18n.known("音频控制"))
            .setView(box)
            .setNegativeButton(I18n.s(R.string.ui_4d0b4688c787), null)
            .setPositiveButton(I18n.known("保存"), (d, w) -> {
                boolean nextOut = swOut.isChecked();
                boolean nextMute = swMute.isChecked();
                int nextVol = volBar.getProgress();
                try {
                    actions.execute(() -> {
                        try {
                            String path = "/audio/config?id=" + id
                                + "&outputEnabled=" + (nextOut ? "1" : "0")
                                + "&muted=" + (nextMute ? "1" : "0")
                                + "&volume=" + nextVol
                                + "&inputPolicy=" + URLEncoder.encode(panel.audioInPolicy, "UTF-8");
                            request("POST", path, 200, epoch);
                            ui.post(() -> {
                                if (epoch == generation && resumed) {
                                    panel.audioOutEnabled = nextOut;
                                    panel.audioMuted = nextMute;
                                    panel.audioVolume = nextVol;
                                    notice.setText(I18n.known("音频设置已生效"));
                                }
                            });
                        } catch (Exception e) {
                            ui.post(() -> {
                                if (epoch == generation && resumed) notice.setText(I18n.known("音频设置失败，请重试"));
                            });
                        }
                    });
                } catch (Exception busy) {
                    notice.setText(I18n.s(R.string.ui_4a5b3e65561d));
                }
            }).show();
    }

    /** 弹出实例精准参数调节弹窗（线程上限、CPU 亲和/配额/权重、IO 权重、显示 DPI、安装编译优化、图形模式） */
    private void precisionTuning(Panel panel) {
        final int epoch = generation, id = panel.id;
        final AlertDialog loading = new AlertDialog.Builder(this)
            .setTitle(InstanceNames.title(panel.number) + " · " + I18n.known("精准参数"))
            .setMessage(I18n.known("正在读取精准参数配置…"))
            .setCancelable(true)
            .show();

        try {
            actions.execute(() -> {
                try {
                    JSONObject cur = new JSONObject(new String(request("GET", "/tuning/get?id=" + id, 200, epoch, 10000, 65536), "UTF-8"));
                    ui.post(() -> {
                        if (loading.isShowing()) loading.dismiss();
                        if (epoch != generation || !resumed) return;
                        showPrecisionTuningDialog(panel, cur, epoch);
                    });
                } catch (Exception e) {
                    ui.post(() -> {
                        if (loading.isShowing()) loading.dismiss();
                        if (epoch == generation && resumed) notice.setText(I18n.known("读取精准参数失败，请稍后重试"));
                    });
                }
            });
        } catch (Exception busy) {
            if (loading.isShowing()) loading.dismiss();
            notice.setText(I18n.s(R.string.ui_4a5b3e65561d));
        }
    }

    private Spinner addTuningSpinner(LinearLayout box, String label, String[] keys, String[] titles, String current, boolean enabled) {
        TextView tv = text(label, 13);
        tv.setPadding(0, dp(8), 0, dp(2));
        box.addView(tv);
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, titles);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        int sel = 0;
        for (int i = 0; i < keys.length; i++) if (keys[i].equals(current)) { sel = i; break; }
        sp.setSelection(sel);
        sp.setEnabled(enabled);
        box.addView(sp);
        return sp;
    }

    private void showPrecisionTuningDialog(Panel panel, JSONObject cur, int epoch) {
        boolean isRunning = cur.optBoolean("running", panel.running);
        LinearLayout box = column();
        box.setPadding(dp(18), dp(8), dp(18), dp(8));

        TextView head = text(
            I18n.known("宿主核心：") + cur.optString("hostCpus")
            + " · " + I18n.known("大核：") + cur.optString("hostBigCpus")
            + " · " + I18n.known("小核：") + cur.optString("hostLittleCpus")
            + (isRunning ? ("\n" + I18n.known("运行中（线程上限与图形模式需停机后修改，其余即时生效）")) : ""), 12);
        head.setTextColor(Color.rgb(176, 196, 222));
        box.addView(head);

        final String[] pidsKeys = {"auto", "2048", "4096", "6144", "8192", "12288", "16384"};
        final String[] pidsLabels = {
            I18n.known("自动（板卡默认：") + cur.optString("boardDefaultTasks", "4096") + ")",
            "2048", "4096", "6144", "8192", "12288", "16384"
        };
        Spinner spPids = addTuningSpinner(box, I18n.known("进程/线程上限 (pids.max · 需停机改)"), pidsKeys, pidsLabels, cur.optString("pidsMax", "auto"), !isRunning);

        final String[] cpuKeys = {"all", "big", "little", "0-3", "4-7", "0-5"};
        final String[] cpuLabels = {
            I18n.known("全部 CPU 核心 (") + cur.optString("hostCpus") + ")",
            I18n.known("仅性能大核 (") + cur.optString("hostBigCpus") + ")",
            I18n.known("仅能效小核 (") + cur.optString("hostLittleCpus") + ")",
            "0-3", "4-7", "0-5"
        };
        Spinner spCpu = addTuningSpinner(box, I18n.known("CPU 核心亲和性 (cpuset)"), cpuKeys, cpuLabels, cur.optString("cpusetCpus", "all"), true);

        final String[] quotaKeys = {"0", "100", "200", "400", "600", "800"};
        final String[] quotaLabels = {I18n.known("不限制 (默认)"), "100% (1核)", "200% (2核)", "400% (4核)", "600% (6核)", "800% (8核)"};
        Spinner spQuota = addTuningSpinner(box, I18n.known("CPU 算力配额上限"), quotaKeys, quotaLabels, Integer.toString(cur.optInt("cpuQuotaPct", 0)), true);

        final String[] shareKeys = {"512", "1024", "2048", "4096"};
        final String[] shareLabels = {I18n.known("512 · 后台低优"), I18n.known("1024 · 标准 (默认)"), I18n.known("2048 · 游戏优先"), I18n.known("4096 · 极高权重")};
        Spinner spShares = addTuningSpinner(box, I18n.known("CPU 调度优先级权重"), shareKeys, shareLabels, Integer.toString(cur.optInt("cpuShares", 1024)), true);

        final String[] blkKeys = {"250", "500", "800", "1000"};
        final String[] blkLabels = {"250", I18n.known("500 · 标准 (默认)"), "800", I18n.known("1000 · 存储最高优先")};
        Spinner spBlk = addTuningSpinner(box, I18n.known("磁盘 I/O 调度权重"), blkKeys, blkLabels, Integer.toString(cur.optInt("blkioWeight", 500)), true);

        final String[] dpiKeys = {"auto", "240", "320", "420", "440"};
        final String[] dpiLabels = {
            I18n.known("自动（按分辨率默认）"),
            I18n.known("240 · 平板大视野"),
            I18n.known("320 · 高清平衡"),
            I18n.known("420 · 手机标准（修复拼多多等比例）"),
            I18n.known("440 · 手机紧凑")
        };
        Spinner spDpi = addTuningSpinner(box, I18n.known("显示密度 (DPI · 即时生效)"), dpiKeys, dpiLabels, cur.optString("displayDpi", "auto"), true);

        final String[] dexKeys = {"verify", "speed-profile", "speed"};
        final String[] dexLabels = {
            I18n.known("verify · 极速安装（推荐，大幅加快装包）"),
            I18n.known("speed-profile · 折中优化"),
            I18n.known("speed · 全量预编译（装包慢）")
        };
        Spinner spDex = addTuningSpinner(box, I18n.known("安装编译模式 (pm.dexopt.install)"), dexKeys, dexLabels, cur.optString("dexoptInstall", "verify"), true);

        final String[] thrKeys = {"0", "2", "4", "6", "8"};
        final String[] thrLabels = {I18n.known("系统默认"), "2 线程", I18n.known("4 线程 (推荐)"), "6 线程", "8 线程"};
        Spinner spThr = addTuningSpinner(box, I18n.known("安装编译并发线程数 (dex2oat)"), thrKeys, thrLabels, Integer.toString(cur.optInt("dex2oatThreads", 4)), true);

        final String[] gfxKeys = {"native", "opengl"};
        final String[] gfxLabels = {I18n.known("native · 原生 Vulkan + GLES"), I18n.known("opengl · GLES 3.0 游戏兼容")};
        Spinner spGfx = addTuningSpinner(box, I18n.known("图形渲染模式 · 需停机改"), gfxKeys, gfxLabels, cur.optString("graphicsMode", "native"), !isRunning);

        ScrollView sc = new ScrollView(this);
        sc.addView(box);

        new AlertDialog.Builder(this)
            .setTitle(InstanceNames.title(panel.number) + " · " + I18n.known("精准参数"))
            .setView(sc)
            .setNegativeButton(I18n.s(R.string.ui_4d0b4688c787), null)
            .setPositiveButton(I18n.known("保存"), (d, w) -> {
                String pPids = pidsKeys[spPids.getSelectedItemPosition()];
                String pCpu = cpuKeys[spCpu.getSelectedItemPosition()];
                String pQuota = quotaKeys[spQuota.getSelectedItemPosition()];
                String pShares = shareKeys[spShares.getSelectedItemPosition()];
                String pBlk = blkKeys[spBlk.getSelectedItemPosition()];
                String pDpi = dpiKeys[spDpi.getSelectedItemPosition()];
                String pDex = dexKeys[spDex.getSelectedItemPosition()];
                String pThr = thrKeys[spThr.getSelectedItemPosition()];
                String pGfx = gfxKeys[spGfx.getSelectedItemPosition()];
                try {
                    actions.execute(() -> {
                        try {
                            String path = "/tuning/set?id=" + panel.id
                                + "&session=" + URLEncoder.encode(panel.session, "UTF-8")
                                + "&pidsMax=" + URLEncoder.encode(pPids, "UTF-8")
                                + "&cpusetCpus=" + URLEncoder.encode(pCpu, "UTF-8")
                                + "&cpuQuotaPct=" + pQuota
                                + "&cpuShares=" + pShares
                                + "&blkioWeight=" + pBlk
                                + "&displayDpi=" + URLEncoder.encode(pDpi, "UTF-8")
                                + "&dexoptInstall=" + URLEncoder.encode(pDex, "UTF-8")
                                + "&dex2oatThreads=" + pThr
                                + "&graphicsMode=" + (!isRunning ? URLEncoder.encode(pGfx, "UTF-8") : "");
                            request("POST", path, 200, epoch);
                            ui.post(() -> {
                                if (epoch == generation && resumed) {
                                    notice.setText(I18n.known("精准参数已保存并应用"));
                                    Toast.makeText(this, I18n.known("精准参数已保存并应用"), Toast.LENGTH_SHORT).show();
                                }
                            });
                        } catch (Exception e) {
                            ui.post(() -> {
                                if (epoch == generation && resumed) notice.setText(I18n.known("精准参数保存失败：") + e.getMessage());
                            });
                        }
                    });
                } catch (Exception busy) {
                    notice.setText(I18n.s(R.string.ui_4a5b3e65561d));
                }
            }).show();
    }

    /** 弹出屏幕分辨率预设选择对话框（单选列表） */
    private void resolution(Panel panel) {
        if (panel.running || resolutionPresets.length() == 0) return;
        final String previous = panel.resolutionProfile;
        final int epoch = generation;
        final String[] keys = new String[resolutionPresets.length()], labels = new String[keys.length];
        int checked = -1;
        for (int n = 0; n < keys.length; n++) {
            JSONObject p = resolutionPresets.optJSONObject(n);
            if (p == null) return;
            keys[n] = p.optString("profile");
            labels[n] = I18n.known(p.optString("label"));
            if (keys[n].equals(previous)) checked = n;
        }
        final int[] choice = {checked};
        LinearLayout content = column();
        content.setPadding(dp(18), dp(8), dp(18), dp(8));
        TextView help = new TextView(this);
        help.setTextSize(15);
        help.setText(I18n.s(R.string.ui_9e3a5417b05e));
        content.addView(help);

        RadioGroup choices = new RadioGroup(this);
        for (int n = 0; n < labels.length; n++) {
            RadioButton item = new RadioButton(this);
            item.setId(View.generateViewId());
            item.setText(labels[n]);
            item.setTextSize(15);
            choices.addView(item);
            if (n == checked) item.setChecked(true);
            final int index = n;
            item.setOnClickListener(v -> choice[0] = index);
        }
        content.addView(choices);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);

        new AlertDialog.Builder(this)
            .setTitle(InstanceNames.title(panel.number) + I18n.s(R.string.ui_0392436f28b0))
            .setView(scroll)
            .setNegativeButton(I18n.s(R.string.ui_4d0b4688c787), null)
            .setPositiveButton(I18n.s(R.string.ui_6d9bea7b8726), (dialog, which) -> {
                if (epoch != generation || !resumed || choice[0] < 0 || panel.running) return;
                panel.disable();
                notice.setText(I18n.s(R.string.ui_49cb08761631));
                try {
                    actions.execute(() -> {
                        try {
                            String path = "/action?id=" + panel.id + "&session=&type=resolution&profile=" + URLEncoder.encode(keys[choice[0]], "UTF-8") + "&previousProfile=" + URLEncoder.encode(previous, "UTF-8");
                            request("POST", path, 202, epoch);
                            ui.post(() -> {
                                if (epoch == generation && resumed) notice.setText(I18n.s(R.string.ui_87016bfffbd7));
                            });
                        } catch (Exception error) {
                            ui.post(() -> {
                                if (epoch == generation && resumed) notice.setText(I18n.s(R.string.ui_30f209548d85));
                            });
                        }
                    });
                } catch (Exception busy) {
                    notice.setText(I18n.s(R.string.ui_4a5b3e65561d));
                }
            }).show();
    }

    /** MiB 单位换算成 GiB 字符串（保留最多 3 位小数） */
    private static String gib(int mib) {
        return BigDecimal.valueOf(mib).divide(BigDecimal.valueOf(1024), 3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /** 提交保存内存配置请求 */
    private void saveMemory(Panel panel, int mib, int previous, int epoch) {
        if (epoch != generation || !resumed || panel.running) return;
        panel.disable();
        notice.setText(I18n.s(R.string.ui_2715af053797));
        try {
            actions.execute(() -> {
                try {
                    request("POST", "/action?id=" + panel.id + "&session=&type=memory&mib=" + mib + "&previousMib=" + previous, 202, epoch);
                    ui.post(() -> {
                        if (epoch == generation && resumed) notice.setText(I18n.s(R.string.ui_4bd157a9d214));
                    });
                } catch (Exception error) {
                    ui.post(() -> {
                        if (epoch == generation && resumed) notice.setText(I18n.s(R.string.ui_30f209548d85));
                    });
                }
            });
        } catch (Exception busy) {
            notice.setText(I18n.s(R.string.ui_4a5b3e65561d));
        }
    }

    /** 弹出内存上限调节对话框（基于板卡与物理内存动态放开上限或恢复默认） */
    private void memory(Panel panel) {
        if (panel.running || !panel.memoryAvailable) return;
        final int epoch = generation, previous = panel.memoryMiB;
        LinearLayout content = column();
        content.setPadding(dp(20), dp(8), dp(20), dp(8));
        TextView help = new TextView(this);
        help.setTextSize(15);
        help.setText(String.format(java.util.Locale.ROOT, I18n.s(R.string.ui_db2b25e5c8ee), gib(panel.memoryMinMiB), gib(panel.memoryMaxMiB), gib(panel.memoryDefaultMiB)));
        content.addView(help);

        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(20);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setContentDescription(InstanceNames.description("memory-input", panel.number));
        input.setHint(I18n.s(R.string.ui_01ab180f0a34));
        input.setText(previous > 0 ? gib(previous) : gib(panel.memoryDefaultMiB));
        input.selectAll();
        content.addView(input);

        TextView units = new TextView(this);
        units.setText(panel.hostTotalMiB > 0
            ? String.format(java.util.Locale.ROOT, I18n.s(R.string.ui_524499799121), gib(panel.memoryDefaultMiB), gib(panel.hostTotalMiB))
            : String.format(java.util.Locale.ROOT, I18n.s(R.string.ui_5e5d942c3c54), gib(panel.memoryDefaultMiB)));
        units.setTextSize(13);
        content.addView(units);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(InstanceNames.title(panel.number) + I18n.s(R.string.ui_9e92c4cb6d22))
            .setView(content)
            .setNegativeButton(I18n.s(R.string.ui_4d0b4688c787), null)
            .setNeutralButton(I18n.s(R.string.ui_a191935bc678), (d, w) -> saveMemory(panel, panel.memoryDefaultMiB, previous, epoch))
            .setPositiveButton(I18n.s(R.string.ui_fadf24dbc5a9), null)
            .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = input.getText().toString().trim();
            try {
                if (!value.matches("[0-9]{1,2}(\\.[0-9]{1,3})?")) throw new IllegalArgumentException();
                BigDecimal amount = new BigDecimal(value);
                if (amount.multiply(new BigDecimal(1024)).compareTo(BigDecimal.valueOf(panel.memoryMinMiB)) < 0 || amount.multiply(new BigDecimal(1024)).compareTo(BigDecimal.valueOf(panel.memoryMaxMiB)) > 0)
                    throw new IllegalArgumentException();
                if (previous < 0) { input.setError(I18n.s(R.string.ui_bd76d510bf82)); return; }
                int mib = amount.multiply(BigDecimal.valueOf(1024)).setScale(0, RoundingMode.HALF_UP).intValueExact();
                saveMemory(panel, mib, previous, epoch);
                dialog.dismiss();
            } catch (IllegalArgumentException | ArithmeticException invalid) {
                input.setError(String.format(java.util.Locale.ROOT, I18n.s(R.string.ui_05e8a1e4c5f3), gib(panel.memoryMinMiB), gib(panel.memoryMaxMiB)));
            }
        }));
        dialog.show();
    }

    /** 校验固定绑定的物理网卡名称（仅允许非虚拟上行接口） */
    private static boolean validNetIfaceInput(String iface) {
        return !iface.isEmpty() && iface.length() <= 16 && iface.matches("[a-zA-Z0-9_.-]+") &&
            !iface.equals("lo") && !iface.startsWith("tun") && !iface.startsWith("tap") &&
            !iface.startsWith("rv") && !iface.startsWith("veth") && !iface.startsWith("dummy");
    }

    /** 格式化宿主机实时网络摘要（物理默认路由列表 + VPN 状态） */
    private String hostNetworkSummary(JSONObject hn) {
        JSONObject data = hn == null ? hostNetwork : hn;
        JSONArray list = data.optJSONArray("physicalInterfaces");
        StringBuilder phys = new StringBuilder();
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                String item = list.optString(i, "");
                if (!item.isEmpty()) {
                    if (phys.length() > 0) phys.append(", ");
                    phys.append(item);
                }
            }
        }
        if (phys.length() == 0) {
            String primary = data.optString("activePhysical", "");
            if (!primary.isEmpty()) phys.append(primary);
        }
        String physText = phys.length() == 0 ? I18n.s(R.string.ui_df54098aa67d) : phys.toString();
        String vpnText = data.optBoolean("vpnActive") && !data.optString("vpnInterface", "").isEmpty() ?
            data.optString("vpnInterface", "tun0") : I18n.s(R.string.ui_b9004e6f7bb1);
        return String.format(java.util.Locale.ROOT, I18n.s(R.string.ui_d79cc5a8e001), physText, vpnText);
    }

    private static boolean validIpv4Part(String ip) {
        if (ip == null || !ip.matches("([0-9]{1,3}\\.){3}[0-9]{1,3}") || ip.equals("0.0.0.0")) return false;
        for (String part : ip.split("\\.")) {
            int n = Integer.parseInt(part);
            if (n < 0 || n > 255 || (part.length() > 1 && part.startsWith("0"))) return false;
        }
        return true;
    }

    private static String normalizeCustomDns(String raw) {
        if (raw == null) return null;
        String clean = raw.trim().replaceAll("\\s+", ",");
        if (clean.isEmpty()) return null;
        String[] parts = clean.split(",", -1);
        if (parts.length < 1 || parts.length > 2) return null;
        for (String p : parts) {
            if (!validIpv4Part(p)) return null;
        }
        return String.join(",", parts);
    }

    private static String dnsShortLabel(String dns) {
        if ("domestic".equals(dns)) return I18n.s(R.string.ui_8b294c5f664a);
        if ("overseas".equals(dns)) return I18n.s(R.string.ui_a00bc7617b9f);
        if (dns == null || dns.isEmpty() || "auto".equals(dns)) return I18n.s(R.string.ui_4afad877551a);
        return dns;
    }

    /** 将实例当前策略与实时生效出口映射为简洁可读的状态文案 */
    private String networkSummary(Panel panel) {
        if (!panel.netValid) return I18n.s(R.string.ui_45aa05c62049);
        String eff = panel.netEffectiveUplink;
        String base;
        switch (panel.netMode) {
            case "fixed_uplink":
                if (!panel.running) base = String.format(java.util.Locale.ROOT, I18n.s(R.string.ui_ff2859c8b4aa), panel.netIface);
                else base = eff.isEmpty() || eff.equals("none") ?
                    String.format(java.util.Locale.ROOT, I18n.s(R.string.ui_601b7110667f), panel.netIface) :
                    String.format(java.util.Locale.ROOT, I18n.s(R.string.ui_d5470b16ca16), panel.netIface, eff);
                break;
            case "host_vpn_tun":
                if (!panel.running) base = I18n.s(R.string.ui_f40b3e2ce715);
                else if (eff.startsWith("tun")) base = String.format(java.util.Locale.ROOT, I18n.s(R.string.ui_bc69e7fdc2f7), eff);
                else base = eff.isEmpty() || eff.equals("none") ?
                    I18n.s(R.string.ui_1c2ed4a9ce87) :
                    String.format(java.util.Locale.ROOT, I18n.s(R.string.ui_647a8ff0a426), eff);
                break;
            case "host_proxy":
                if (!panel.running) base = String.format(java.util.Locale.ROOT, I18n.s(R.string.ui_ba1d0107054c), panel.netPort);
                else base = panel.netProxyApplied ?
                    String.format(java.util.Locale.ROOT, I18n.s(R.string.ui_2f6e55e7f694), panel.netPort,
                        eff.isEmpty() ? "none" : eff) :
                    String.format(java.util.Locale.ROOT, I18n.s(R.string.ui_9f70df99d29c), panel.netPort);
                break;
            default:
                if (!panel.running) base = I18n.s(R.string.ui_d8d6333d8669);
                else base = eff.isEmpty() || eff.equals("none") ?
                    I18n.s(R.string.ui_03d48485a934) :
                    String.format(java.util.Locale.ROOT, I18n.s(R.string.ui_1db6deec58ef), eff);
                break;
        }
        return base + I18n.s(R.string.ui_af7062df2338) + dnsShortLabel(panel.netDns);
    }

    /** 提交实例网络出口策略到 Broker（停机与运行中均可直接保存并热切换） */
    private void saveNetwork(Panel panel, String mode, String iface, int port, String vpnFallback, String dns, String expectedSession, int epoch) {
        if (epoch != generation || !resumed) return;
        panel.disable();
        notice.setText(I18n.s(R.string.ui_401596f7c7db));
        try {
            actions.execute(() -> {
                try {
                    String query = "/action?id=" + panel.id
                        + "&session=" + URLEncoder.encode(expectedSession, "UTF-8")
                        + "&type=network"
                        + "&mode=" + URLEncoder.encode(mode, "UTF-8")
                        + "&iface=" + URLEncoder.encode(iface, "UTF-8")
                        + "&port=" + port
                        + "&vpnFallback=" + URLEncoder.encode(vpnFallback, "UTF-8")
                        + "&dns=" + URLEncoder.encode(dns, "UTF-8");
                    request("POST", query, 202, epoch);
                    ui.post(() -> {
                        if (epoch == generation && resumed) notice.setText(I18n.s(R.string.ui_f0c3b1b2f683));
                    });
                } catch (Exception error) {
                    ui.post(() -> {
                        if (epoch == generation && resumed) notice.setText(I18n.s(R.string.ui_30f209548d85));
                    });
                }
            });
        } catch (Exception busy) {
            notice.setText(I18n.s(R.string.ui_4a5b3e65561d));
        }
    }

    /** 弹出实例网络出口策略设置与诊断对话框 */
    private void network(Panel panel) {
        if (!panel.network.isEnabled()) return;
        final int epoch = generation;
        final String expectedSession = panel.session;

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = column();
        content.setPadding(dp(20), dp(8), dp(20), dp(8));

        final TextView hostInfoText = new TextView(this);
        hostInfoText.setTextSize(13);
        hostInfoText.setText(hostNetworkSummary(hostNetwork));
        content.addView(hostInfoText);

        final TextView currentEgressText = new TextView(this);
        currentEgressText.setTextSize(13);
        currentEgressText.setPadding(0, dp(2), 0, dp(8));
        currentEgressText.setText(String.format(java.util.Locale.ROOT, I18n.s(R.string.ui_c5b9ec8b7e9e),
            panel.running ? (panel.netEffectiveUplink.isEmpty() ? "none" : panel.netEffectiveUplink) : I18n.s(R.string.ui_7b4c157a1308)));
        content.addView(currentEgressText);

        final RadioGroup modeGroup = new RadioGroup(this);
        final RadioButton rbFollow = new RadioButton(this);
        rbFollow.setId(View.generateViewId());
        rbFollow.setText(I18n.s(R.string.ui_47850cbbbbb0));
        rbFollow.setTextSize(14);
        modeGroup.addView(rbFollow);

        final RadioButton rbVpn = new RadioButton(this);
        rbVpn.setId(View.generateViewId());
        rbVpn.setText(I18n.s(R.string.ui_e53905718aa8));
        rbVpn.setTextSize(14);
        modeGroup.addView(rbVpn);

        final LinearLayout vpnBox = column();
        vpnBox.setPadding(dp(28), dp(2), 0, dp(6));
        final CheckBox vpnFailClosed = new CheckBox(this);
        vpnFailClosed.setText(I18n.s(R.string.ui_851c082fc074));
        vpnFailClosed.setTextSize(13);
        vpnFailClosed.setChecked("fail_closed".equals(panel.netVpnFallback));
        vpnBox.addView(vpnFailClosed);
        modeGroup.addView(vpnBox);

        final RadioButton rbProxy = new RadioButton(this);
        rbProxy.setId(View.generateViewId());
        rbProxy.setText(I18n.s(R.string.ui_296877a88812));
        rbProxy.setTextSize(14);
        modeGroup.addView(rbProxy);

        final LinearLayout proxyBox = column();
        proxyBox.setPadding(dp(28), dp(2), 0, dp(6));
        TextView proxyLabel = new TextView(this);
        proxyLabel.setText(I18n.s(R.string.ui_3e977326f63b));
        proxyLabel.setTextSize(13);
        proxyBox.addView(proxyLabel);
        final EditText portInput = new EditText(this);
        portInput.setSingleLine(true);
        portInput.setTextSize(16);
        portInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        portInput.setText(Integer.toString(panel.netPort > 0 && panel.netPort <= 65535 ? panel.netPort : 7890));
        portInput.setContentDescription(InstanceNames.description("network-port", panel.number));
        proxyBox.addView(portInput);
        modeGroup.addView(proxyBox);

        final RadioButton rbFixed = new RadioButton(this);
        rbFixed.setId(View.generateViewId());
        rbFixed.setText(I18n.s(R.string.ui_cd75e3894213));
        rbFixed.setTextSize(14);
        modeGroup.addView(rbFixed);

        final LinearLayout fixedBox = column();
        fixedBox.setPadding(dp(28), dp(2), 0, dp(6));
        TextView fixedLabel = new TextView(this);
        fixedLabel.setText(I18n.s(R.string.ui_1858af82008e));
        fixedLabel.setTextSize(13);
        fixedBox.addView(fixedLabel);
        final EditText ifaceInput = new EditText(this);
        ifaceInput.setSingleLine(true);
        ifaceInput.setTextSize(16);
        ifaceInput.setText(panel.netIface);
        ifaceInput.setContentDescription(InstanceNames.description("network-iface", panel.number));
        fixedBox.addView(ifaceInput);

        final LinearLayout ifaceQuickRow = new LinearLayout(this);
        ifaceQuickRow.setOrientation(LinearLayout.HORIZONTAL);
        JSONArray knownIfaces = hostNetwork.optJSONArray("physicalInterfaces");
        if (knownIfaces != null) {
            for (int i = 0; i < knownIfaces.length(); i++) {
                final String cand = knownIfaces.optString(i, "");
                if (!validNetIfaceInput(cand)) continue;
                Button chip = new Button(this);
                chip.setText(cand);
                chip.setTextSize(12);
                chip.setAllCaps(false);
                chip.setMinHeight(0);
                chip.setMinimumHeight(0);
                chip.setPadding(dp(8), dp(4), dp(8), dp(4));
                chip.setOnClickListener(v -> ifaceInput.setText(cand));
                ifaceQuickRow.addView(chip);
            }
        }
        fixedBox.addView(ifaceQuickRow);
        modeGroup.addView(fixedBox);

        content.addView(modeGroup);

        TextView dnsHeader = new TextView(this);
        dnsHeader.setTextSize(14);
        dnsHeader.setPadding(0, dp(10), 0, dp(2));
        String curDnsShow = panel.running && !panel.netEffectiveDns.isEmpty()
            ? String.format(java.util.Locale.ROOT, I18n.s(R.string.ui_02cd2bc1522e), panel.netEffectiveDns)
            : I18n.s(R.string.ui_ef1aebd42c7a);
        dnsHeader.setText(curDnsShow);
        content.addView(dnsHeader);

        final RadioGroup dnsGroup = new RadioGroup(this);
        final RadioButton rbDnsAuto = new RadioButton(this);
        rbDnsAuto.setId(View.generateViewId());
        rbDnsAuto.setText(I18n.s(R.string.ui_1da381822229));
        rbDnsAuto.setTextSize(13);
        dnsGroup.addView(rbDnsAuto);

        final RadioButton rbDnsDomestic = new RadioButton(this);
        rbDnsDomestic.setId(View.generateViewId());
        rbDnsDomestic.setText(I18n.s(R.string.ui_d64103c57e22));
        rbDnsDomestic.setTextSize(13);
        dnsGroup.addView(rbDnsDomestic);

        final RadioButton rbDnsOverseas = new RadioButton(this);
        rbDnsOverseas.setId(View.generateViewId());
        rbDnsOverseas.setText(I18n.s(R.string.ui_d7691f5a1ba6));
        rbDnsOverseas.setTextSize(13);
        dnsGroup.addView(rbDnsOverseas);

        final RadioButton rbDnsCustom = new RadioButton(this);
        rbDnsCustom.setId(View.generateViewId());
        rbDnsCustom.setText(I18n.s(R.string.ui_22e3ff6e9a54));
        rbDnsCustom.setTextSize(13);
        dnsGroup.addView(rbDnsCustom);

        final LinearLayout dnsCustomBox = column();
        dnsCustomBox.setPadding(dp(28), dp(2), 0, dp(6));
        final EditText dnsCustomInput = new EditText(this);
        dnsCustomInput.setSingleLine(true);
        dnsCustomInput.setTextSize(15);
        dnsCustomInput.setHint(I18n.s(R.string.ui_13f4f6afa2bb));
        boolean isCustomDns = !"auto".equals(panel.netDns) && !"domestic".equals(panel.netDns)
            && !"overseas".equals(panel.netDns) && !panel.netDns.isEmpty();
        if (isCustomDns) dnsCustomInput.setText(panel.netDns);
        dnsCustomBox.addView(dnsCustomInput);
        dnsGroup.addView(dnsCustomBox);
        content.addView(dnsGroup);

        final TextView probeResultText = new TextView(this);
        probeResultText.setTextSize(12);
        probeResultText.setTypeface(Typeface.MONOSPACE);
        probeResultText.setPadding(0, dp(8), 0, 0);
        probeResultText.setVisibility(View.GONE);
        content.addView(probeResultText);

        Runnable syncSubOptions = () -> {
            int checked = modeGroup.getCheckedRadioButtonId();
            vpnBox.setVisibility(checked == rbVpn.getId() ? View.VISIBLE : View.GONE);
            proxyBox.setVisibility(checked == rbProxy.getId() ? View.VISIBLE : View.GONE);
            fixedBox.setVisibility(checked == rbFixed.getId() ? View.VISIBLE : View.GONE);
            dnsCustomBox.setVisibility(dnsGroup.getCheckedRadioButtonId() == rbDnsCustom.getId() ? View.VISIBLE : View.GONE);
        };
        modeGroup.setOnCheckedChangeListener((g, checkedId) -> syncSubOptions.run());
        dnsGroup.setOnCheckedChangeListener((g, checkedId) -> syncSubOptions.run());
        if ("host_vpn_tun".equals(panel.netMode)) modeGroup.check(rbVpn.getId());
        else if ("host_proxy".equals(panel.netMode)) modeGroup.check(rbProxy.getId());
        else if ("fixed_uplink".equals(panel.netMode)) modeGroup.check(rbFixed.getId());
        else modeGroup.check(rbFollow.getId());

        if ("domestic".equals(panel.netDns)) dnsGroup.check(rbDnsDomestic.getId());
        else if ("overseas".equals(panel.netDns)) dnsGroup.check(rbDnsOverseas.getId());
        else if (isCustomDns) dnsGroup.check(rbDnsCustom.getId());
        else dnsGroup.check(rbDnsAuto.getId());
        syncSubOptions.run();

        scroll.addView(content);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(InstanceNames.title(panel.number) + I18n.s(R.string.ui_c681b7343000))
            .setView(scroll)
            .setNegativeButton(I18n.s(R.string.ui_4d0b4688c787), null)
            .setNeutralButton(I18n.s(R.string.ui_eaab5e4ec4c9), null)
            .setPositiveButton(I18n.s(R.string.ui_fadf24dbc5a9), null)
            .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                probeResultText.setVisibility(View.VISIBLE);
                probeResultText.setText(I18n.s(R.string.ui_5edba3beff4a));
                Thread probeThread = new Thread(() -> {
                    try {
                        JSONObject resp = new JSONObject(new String(
                            request("GET", "/network/probe?id=" + panel.id + "&test=1", 200, epoch, 18000), "UTF-8"));
                        JSONObject hn = resp.optJSONObject("hostNetwork");
                        String eff = resp.optString("effectiveUplink", "");
                        String report = resp.optString("report", "");
                        ui.post(() -> {
                            if (hn != null) hostNetwork = hn;
                            if (epoch == generation && resumed && dialog.isShowing()) {
                                hostInfoText.setText(hostNetworkSummary(hn));
                                currentEgressText.setText(String.format(java.util.Locale.ROOT, I18n.s(R.string.ui_c5b9ec8b7e9e),
                                    panel.running ? (eff.isEmpty() ? "none" : eff) : I18n.s(R.string.ui_7b4c157a1308)));
                                probeResultText.setText(I18n.s(R.string.ui_cfc55190390a) + report);
                            }
                        });
                    } catch (Exception err) {
                        ui.post(() -> {
                            if (epoch == generation && resumed && dialog.isShowing()) {
                                probeResultText.setText(I18n.s(R.string.ui_cd3c4683bb6c));
                            }
                        });
                    }
                }, "iris-net-probe");
                probeThread.setDaemon(true);
                probeThread.start();
            });

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                int checked = modeGroup.getCheckedRadioButtonId();
                String mode = checked == rbVpn.getId() ? "host_vpn_tun" :
                    checked == rbProxy.getId() ? "host_proxy" :
                    checked == rbFixed.getId() ? "fixed_uplink" : "follow_host_uplink";
                String iface = mode.equals("fixed_uplink") ? ifaceInput.getText().toString().trim() : "";
                if (mode.equals("fixed_uplink") && !validNetIfaceInput(iface)) {
                    ifaceInput.setError(I18n.s(R.string.ui_2cafefc6b8b6));
                    return;
                }
                int port = panel.netPort > 0 && panel.netPort <= 65535 ? panel.netPort : 7890;
                if (mode.equals("host_proxy")) {
                    String portStr = portInput.getText().toString().trim();
                    if (!portStr.matches("[1-9][0-9]{0,4}")) {
                        portInput.setError(I18n.s(R.string.ui_2cafefc6b8b6));
                        return;
                    }
                    port = Integer.parseInt(portStr);
                    if (port < 1 || port > 65535) {
                        portInput.setError(I18n.s(R.string.ui_2cafefc6b8b6));
                        return;
                    }
                }
                String fallback = vpnFailClosed.isChecked() ? "fail_closed" : "fallback_physical";
                int dnsChecked = dnsGroup.getCheckedRadioButtonId();
                String dns = "auto";
                if (dnsChecked == rbDnsDomestic.getId()) dns = "domestic";
                else if (dnsChecked == rbDnsOverseas.getId()) dns = "overseas";
                else if (dnsChecked == rbDnsCustom.getId()) {
                    dns = normalizeCustomDns(dnsCustomInput.getText().toString());
                    if (dns == null) {
                        dnsCustomInput.setError(I18n.s(R.string.ui_61431715c6f5));
                        return;
                    }
                }
                saveNetwork(panel, mode, iface, port, fallback, dns, expectedSession, epoch);
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    /** 打开 HTTP 连接并配置 Bearer Token 与超时参数（默认 2500ms） */
    private HttpURLConnection open(String method, String path, int epoch) throws Exception {
        return open(method, path, epoch, 2500);
    }

    /** 打开 HTTP 连接并配置 Bearer Token 与超时参数 */
    private HttpURLConnection open(String method, String path, int epoch, int readTimeoutMs) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
        connections.add(connection);
        // 先登记连接再校验 epoch：pauseNetwork 会遍历强断登记的连接，或此处校验拦截注册过迟的请求
        if (epoch != generation || !resumed) {
            connection.disconnect();
            connections.remove(connection);
            throw new IOException("Page paused");
        }
        connection.setConnectTimeout(1500);
        connection.setReadTimeout(readTimeoutMs);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", "Bearer " + brokerToken);
        return connection;
    }

    /** 发送 HTTP 请求并严格核验预期返回码，读取限制长度的内容 */
    private byte[] request(String method, String path, int expected, int epoch) throws Exception {
        return request(method, path, expected, epoch, 2500);
    }

    /** 发送 HTTP 请求并允许指定读取超时（用于容器出网诊断等慢请求） */
    private byte[] request(String method, String path, int expected, int epoch, int readTimeoutMs) throws Exception {
        return request(method, path, expected, epoch, readTimeoutMs, 65536);
    }

    /** 发送 HTTP 请求并允许指定读取超时和最大响应体限制 */
    private byte[] request(String method, String path, int expected, int epoch, int readTimeoutMs, int maxBytes) throws Exception {
        HttpURLConnection connection = open(method, path, epoch, readTimeoutMs);
        try {
            if (method.equals("POST")) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(0);
                connection.getOutputStream().close();
            }
            if (connection.getResponseCode() != expected) throw new IOException("Request rejected");
            return readBounded(connection, maxBytes);
        } finally {
            connection.disconnect();
            connections.remove(connection);
        }
    }

    /** 严格限制最大字节数的输入流读取工具（防超大响应耗尽内存） */
    private static byte[] readBounded(HttpURLConnection connection, int maximum) throws Exception {
        long declared = connection.getContentLengthLong();
        if (declared > maximum) throw new IOException("Reply too long");
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] data = new byte[8192];
            int count;
            while ((count = input.read(data)) != -1) {
                if (output.size() + count > maximum) throw new IOException("Reply too long");
                output.write(data, 0, count);
            }
            return output.toByteArray();
        }
    }

    /**
     * 【16:9 比例锁定视口容器：PreviewBox】
     * 自定义 FrameLayout：
     * 测量阶段（onMeasure）与布局阶段（onLayout）自动根据父布局可用空间，
     * 计算并强制将其子视图（图片与覆盖层）保持在标准的 16:9 比例并居中对齐。
     */
    private final class PreviewBox extends FrameLayout {
        private int innerWidth, innerHeight;
        PreviewBox() { super(ListActivity.this); }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int w = View.MeasureSpec.getSize(widthSpec), h = View.MeasureSpec.getSize(heightSpec);
            setMeasuredDimension(w, h);
            innerWidth = Math.min(w, h * 16 / 9);
            innerHeight = innerWidth * 9 / 16;
            for (int n = 0; n < getChildCount(); n++)
                getChildAt(n).measure(View.MeasureSpec.makeMeasureSpec(innerWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(innerHeight, View.MeasureSpec.EXACTLY));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int x = (getWidth() - innerWidth) / 2, y = (getHeight() - innerHeight) / 2;
            for (int n = 0; n < getChildCount(); n++)
                getChildAt(n).layout(x, y, x + innerWidth, y + innerHeight);
        }
    }

    /**
     * 【单个实例展示与交互面板：Panel】
     * 负责单个实例卡片的完整生命周期呈现：
     * - 头部：实例标题与当前运行阶段状态；
     * - 画面区：16:9 比例小窗图片，带启动引导日志 ListView 覆盖层；
     * - 主按钮栏：进入全屏、启动、停止；
     * - 扩展栏：设置、时钟、文件、分辨率、内存上限、Root 与重置/删除。
     */
    private final class Panel {
        final int id, number;
        final LinearLayout root = column();
        final TextView status, stageText, logText, caption;
        final PreviewBox box = new PreviewBox();
        final ImageView image;
        final LinearLayout overlay;
        final ListView logScroll;
        final Button start, stop, enter, files, audioBtn, tuningBtn, importApp, logs, monitor, resolution, memory, network, reset, delete, applicationRoot;

        // 日志分页拉取与滑动状态
        volatile String logKey = "";
        volatile long logOffset;
        long logTotal, lastLogRender;
        int renderedLineCount;
        volatile boolean logVisible = true;
        final StringBuilder history = new StringBuilder(), pendingLines = new StringBuilder();
        final ArrayList<String> logLines = new ArrayList<>();
        BaseAdapter logAdapter;

        // 配置版本与 Root 状态
        String revision = "", rootProfile = "off";
        boolean rootValid, rootReady;

        // 分辨率状态
        final TextView resolutionText;
        String resolutionProfile = "legacy";

        // 内存状态
        final TextView memoryText;
        int memoryMiB = 1280;
        int memoryMinMiB = 512;
        int memoryMaxMiB = 2560;
        int memoryDefaultMiB = 1280;
        int hostTotalMiB = 0;
        boolean memoryAvailable;

        // 网络出口策略与实时状态
        final TextView networkText, perfText;
        boolean netValid;
        String netMode = "follow_host_uplink", netIface = "", netVpnFallback = "fallback_physical",
            netDns = "auto", netEffectiveDns = "",
            netEffectiveUplink = "", netEffectiveMode = "";
        int netPort = 7890;
        boolean netProxyApplied;

        // 音频配置缓存
        boolean audioOutEnabled = true, audioMuted = false;
        int audioVolume = 100;
        String audioInPolicy = "mute_no_mic";

        // 运行时状态标志
        volatile String session = "";
        volatile boolean previewAllowed;
        boolean ready, running, showLogs, openingScreen, displayingScreen;
        String stage = I18n.s(R.string.ui_09cb521a07d3), bootLog = "", operationError = "", previewMessage = I18n.s(R.string.ui_d9dce1b78d9e);
        long bootElapsedMs, previewSequence, capturedAt, openGraceUntil;
        Bitmap bitmap;

        Panel(int id, int number) {
            this.id = id;
            this.number = number;
            root.setPadding(dp(10), dp(8), dp(10), dp(8));
            root.setBackgroundColor(Color.rgb(34, 40, 49));

            // 1. 顶部标题栏
            LinearLayout top = new LinearLayout(ListActivity.this);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(text(InstanceNames.title(number), 22), new LinearLayout.LayoutParams(0, -2, 1));
            status = text(I18n.s(R.string.ui_09cb521a07d3), 13);
            status.setMaxLines(1);
            status.setEllipsize(TextUtils.TruncateAt.END);
            top.addView(status, new LinearLayout.LayoutParams(0, -2, 2));
            root.addView(top);

            // 2. 预览区（图片 + 引导日志覆盖层）
            image = new ImageView(ListActivity.this);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setBackgroundColor(Color.BLACK);
            image.setContentDescription(InstanceNames.description("preview", number));
            box.addView(image);

            overlay = column();
            overlay.setPadding(dp(12), dp(8), dp(12), dp(8));
            overlay.setBackgroundColor(Color.rgb(13, 18, 25));
            stageText = text(I18n.s(R.string.ui_d9dce1b78d9e), 17);
            stageText.setMaxLines(2);
            overlay.addView(stageText);

            logText = text(I18n.s(R.string.ui_b28f7fc03b17), 13);
            logText.setTextColor(Color.rgb(169, 187, 202));
            logText.setTypeface(Typeface.MONOSPACE);
            logText.setLongClickable(true);
            logText.setOnLongClickListener(v -> { if (logVisible) copyLog(this); return true; });

            // 视图复用：复用屏幕上仅可见的几行 TextView，不随全文增长反复排版几万行，杜绝日志卡死点火
            logScroll = new ListView(ListActivity.this);
            logScroll.setDivider(null);
            logScroll.setDividerHeight(0);
            logAdapter = new BaseAdapter() {
                public int getCount() { return logLines.size(); }
                public Object getItem(int position) { return logLines.get(position); }
                public long getItemId(int position) { return position; }
                public boolean hasStableIds() { return true; }
                public View getView(int position, View reused, ViewGroup parent) {
                    TextView row = reused instanceof TextView ? (TextView) reused : text("", 13);
                    row.setTextColor(Color.rgb(169, 187, 202));
                    row.setTypeface(Typeface.MONOSPACE);
                    row.setText(logLines.get(position));
                    return row;
                }
            };
            logScroll.setAdapter(logAdapter);
            logScroll.setOnItemLongClickListener((parent, view, position, itemId) -> { copyLog(this); return true; });
            overlay.addView(logText, new LinearLayout.LayoutParams(-1, -2));
            overlay.addView(logScroll, new LinearLayout.LayoutParams(-1, 0, 1));
            overlay.setContentDescription(InstanceNames.description("boot-log", number));
            box.addView(overlay);
            root.addView(box, new LinearLayout.LayoutParams(-1, 0, 1));

            // 3. 预览信息条与日志切换按钮
            LinearLayout previewInfo = new LinearLayout(ListActivity.this);
            previewInfo.setGravity(Gravity.CENTER_VERTICAL);
            caption = text(I18n.s(R.string.ui_b0569c4c4048), 12);
            caption.setTextColor(Color.LTGRAY);
            caption.setMaxLines(1);
            caption.setEllipsize(TextUtils.TruncateAt.END);
            previewInfo.addView(caption, new LinearLayout.LayoutParams(0, -2, 1));
            logs = button(I18n.s(R.string.ui_8ad868abbca6), "logs", () -> { showLogs = !showLogs; renderPreview(); });
            logs.setTextSize(12);
            previewInfo.addView(logs, new LinearLayout.LayoutParams(dp(84), dp(36)));
            monitor = button(I18n.known("监控"), "monitor", () -> TelemetryUi.showLinuxMonitorDialog(ListActivity.this, brokerToken, id, number, stage));
            monitor.setTextSize(12);
            previewInfo.addView(monitor, new LinearLayout.LayoutParams(dp(64), dp(36)));
            root.addView(previewInfo);

            // 4. 核心主控制栏（进入全屏、启动、停止）
            LinearLayout primary = new LinearLayout(ListActivity.this);
            enter = button(I18n.s(R.string.ui_0e25578e8fa1), "enter", () -> action(this, "open", ""));
            start = button(I18n.s(R.string.ui_ebd26da42171), "start", () -> action(this, "start", ""));
            stop = button(I18n.s(R.string.ui_a17f70a8d3d6), "stop", () -> action(this, "stop", ""));
            primary.addView(enter, new LinearLayout.LayoutParams(0, dp(42), 2));
            primary.addView(start, new LinearLayout.LayoutParams(0, dp(42), 1));
            primary.addView(stop, new LinearLayout.LayoutParams(0, dp(42), 1));
            root.addView(primary);

            // 点击小窗截图直接快捷进入全屏
            image.setOnClickListener(v -> { if (enter.isEnabled()) action(this, "open", ""); });

            // 5. 状态指标展示条
            resolutionText = text(I18n.s(R.string.ui_88069eee8fdf), 12);
            resolutionText.setMaxLines(1);
            resolutionText.setEllipsize(TextUtils.TruncateAt.END);
            root.addView(resolutionText);
            memoryText = text(I18n.s(R.string.ui_a059ca83fc97), 12);
            root.addView(memoryText);
            networkText = text(I18n.s(R.string.ui_8950be7d6600), 12);
            networkText.setMaxLines(1);
            networkText.setEllipsize(TextUtils.TruncateAt.END);
            root.addView(networkText);
            perfText = text(I18n.known("性能：实例未运行"), 11);
            perfText.setTextColor(Color.rgb(156, 220, 176));
            perfText.setMaxLines(1);
            perfText.setEllipsize(TextUtils.TruncateAt.END);
            root.addView(perfText);

            // 6. 常用功能与参数调节栏（文件、分辨率、内存、音频、精准参数）
            LinearLayout apps = new LinearLayout(ListActivity.this);
            files = button(I18n.s(R.string.ui_49deaf7da20d), "files", () -> files(this));
            resolution = button(I18n.s(R.string.ui_cde9b52b2395), "resolution", () -> resolution(this));
            memory = button(I18n.s(R.string.ui_d014ab77038c), "memory", () -> memory(this));
            audioBtn = button(I18n.known("音频"), "audio", () -> audioControl(this));
            tuningBtn = button(I18n.known("精准参数"), "tuning", () -> precisionTuning(this));
            files.setTextSize(13);
            resolution.setTextSize(13);
            memory.setTextSize(13);
            audioBtn.setTextSize(13);
            tuningBtn.setTextSize(13);
            apps.addView(files, new LinearLayout.LayoutParams(0, dp(38), 1));
            apps.addView(resolution, new LinearLayout.LayoutParams(0, dp(38), 1));
            apps.addView(memory, new LinearLayout.LayoutParams(0, dp(38), 1));
            apps.addView(audioBtn, new LinearLayout.LayoutParams(0, dp(38), 1));
            apps.addView(tuningBtn, new LinearLayout.LayoutParams(0, dp(38), 1));
            root.addView(apps);

            // 7. 高级与数据维护栏（网络出口、Root、导入/导出、重置数据、删除实例）
            LinearLayout data = new LinearLayout(ListActivity.this);
            network = button(I18n.s(R.string.ui_c0164072e48e), "network", () -> network(this));
            network.setTextSize(13);
            data.addView(network, new LinearLayout.LayoutParams(0, dp(36), 1));
            applicationRoot = button(I18n.s(R.string.ui_b72827b39c61), "root", () -> applicationRoot(this));
            applicationRoot.setTextSize(13);
            data.addView(applicationRoot, new LinearLayout.LayoutParams(0, dp(36), 1));
            importApp = button(I18n.known("导入/导出"), "import-app", () -> importOrExportApp(this));
            importApp.setTextSize(13);
            data.addView(importApp, new LinearLayout.LayoutParams(0, dp(36), 1));
            reset = button(I18n.s(R.string.ui_b1dd040286c1), "reset", () -> clearData(this, "reset"));
            reset.setTextSize(13);
            delete = button(I18n.s(R.string.ui_6e73a5ffa5cc), "delete", () -> clearData(this, "delete"));
            delete.setTextSize(13);
            data.addView(reset, new LinearLayout.LayoutParams(0, dp(36), 1));
            data.addView(delete, new LinearLayout.LayoutParams(0, dp(36), 1));
            root.addView(data);

            disable();
            renderPreview();
        }

        /** 构造具有语义描述和防连击的通用按钮 */
        Button button(String title, String name, Runnable callback) {
            Button b = new Button(ListActivity.this);
            b.setText(title);
            b.setTextSize(14);
            b.setMinHeight(0);
            b.setMinimumHeight(0);
            b.setPadding(dp(4), 0, dp(4), 0);
            b.setContentDescription(InstanceNames.description(name, number));
            b.setOnClickListener(v -> callback.run());
            return b;
        }

        /** 根据轮询获取的最新实例 JSON 状态更新卡片所有控件 */
        void update(JSONObject state) {
            revision = state.optString("revision", "");
            String next = state.optString("session", "");
            if (!next.equals(session)) {
                session = next;
                clearImage();
                showLogs = false;
                previewMessage = I18n.s(R.string.ui_1226729a0d9e);
            }
            running = state.optBoolean("running");
            ready = state.optBoolean("ready");
            String busy = state.optString("busy", "");
            boolean displaying = state.optBoolean("displaying");
            if (displaying) openGraceUntil = 0;
            boolean opening = state.optBoolean("opening") || busy.equals("open") || (!displaying && SystemClock.elapsedRealtime() < openGraceUntil);

            stage = state.optBoolean("cleanupPending") ? I18n.s(R.string.ui_389284dd8300) :
                I18n.known(state.optString("stage", ready ? I18n.s(R.string.ui_594249700590) : running ? I18n.s(R.string.ui_6017dccf86b2) : I18n.s(R.string.ui_b2ba9634d9cf)));
            bootElapsedMs = Math.max(0, state.optLong("bootElapsedMs", 0));
            operationError = state.optString("error", "");
            if (operationError.length() > 220) operationError = operationError.substring(0, 220);

            String key = state.optString("bootLogKey", "");
            if (!key.equals(logKey)) {
                logKey = key;
                logOffset = 0;
                logTotal = 0;
                history.setLength(0);
                pendingLines.setLength(0);
                logLines.clear();
                renderedLineCount = 0;
                logAdapter.notifyDataSetChanged();
                bootLog = "";
            }

            // 兼容老后台：若缺少独立端点则从 state 摘要中拼装简要日志
            if (key.isEmpty()) {
                JSONArray lines = state.optJSONArray("bootLog");
                StringBuilder value = new StringBuilder();
                if (lines != null) {
                    for (int n = 0; n < lines.length(); n++) {
                        if (value.length() > 0) value.append('\n');
                        value.append(lines.optString(n, ""));
                    }
                }
                bootLog = value.toString();
            }

            boolean free = busy.isEmpty() && !opening;
            reset.setEnabled(!running && free && revision.matches("[a-f0-9]{64}"));
            delete.setEnabled(reset.isEnabled());

            JSONObject config = state.optJSONObject("resolution");
            if (config != null) {
                resolutionProfile = config.optString("profile", "invalid");
                String actual = config.optString("effective", "");
                resolutionText.setText(actual.isEmpty() ? I18n.s(R.string.ui_e0c5746ef927) + I18n.known(config.optString("label")) : I18n.s(R.string.ui_da31dfc47974) + actual);
            }
            resolution.setEnabled(!running && free && config != null && resolutionPresets.length() > 0);

            JSONObject mem = state.optJSONObject("memory");
            memoryAvailable = mem != null;
            if (mem != null) {
                memoryMinMiB = mem.optInt("minMiB", 512);
                memoryMaxMiB = mem.optInt("maxMiB", 2560);
                memoryDefaultMiB = mem.optInt("defaultMiB", 1280);
                hostTotalMiB = mem.optInt("hostTotalMiB", 0);
                memoryMiB = mem.optInt("mib", -1);
                int effective = mem.optInt("effectiveMiB", 0);
                memoryText.setText(effective > 0 ? I18n.s(R.string.ui_37a78c8dc0a6) + gib(effective) + " GiB" :
                    memoryMiB > 0 ? I18n.s(R.string.ui_3ea894a932dd) + gib(memoryMiB) + " GiB" : I18n.s(R.string.ui_3204497ae97d));
            }
            memory.setEnabled(!running && free && memoryAvailable);

            JSONObject net = state.optJSONObject("network");
            if (net != null) {
                netValid = net.optBoolean("valid");
                netMode = net.optString("mode", "follow_host_uplink");
                netIface = net.optString("iface", "");
                netPort = net.optInt("port", 7890);
                netVpnFallback = net.optString("vpnFallback", "fallback_physical");
                netDns = net.optString("dns", "auto");
                netEffectiveDns = net.optString("effectiveDns", "");
                netEffectiveUplink = net.optString("effectiveUplink", "");
                netEffectiveMode = net.optString("effectiveMode", "");
                netProxyApplied = net.optBoolean("proxyApplied");
                networkText.setText(networkSummary(this));
            } else {
                netValid = false;
                networkText.setText(I18n.s(R.string.ui_8950be7d6600));
            }
            network.setEnabled(net != null && free && !state.optBoolean("cleanupPending"));
            perfText.setText(TelemetryUi.instanceBriefText(state.optJSONObject("perf")));
            monitor.setEnabled(!brokerToken.isEmpty());

            JSONObject rt = state.optJSONObject("root");
            rootValid = rt != null && rt.optBoolean("valid");
            rootProfile = rt == null ? "invalid" : rt.optString("profile", "invalid");
            rootReady = rt != null && rt.optBoolean("ready");
            applicationRoot.setText(!rootValid ? I18n.s(R.string.ui_63265c9e9d1a) :
                rootProfile.equals("off") ? I18n.s(R.string.ui_eac94fb94a6b) :
                rootProfile.equals("magisk-safe") ? I18n.s(R.string.ui_2c37c4f350ec) :
                !running ? I18n.s(R.string.ui_624b011b8eaa) :
                rootReady ? I18n.s(R.string.ui_95471e8317e2) : ready ? I18n.s(R.string.ui_edf72b391180) : I18n.s(R.string.ui_cd7761cd9dba));
            applicationRoot.setEnabled(rootValid && free && !state.optBoolean("cleanupPending"));

            openingScreen = opening;
            displayingScreen = displaying;
            previewAllowed = ready && validSession(session) && !opening && !displaying && !busy.equals("stop");
            if (!running || !ready) clearImage();
            if (!running || !ready || opening || displaying) {
                previewMessage = opening ? I18n.s(R.string.ui_4bd5dfa2f736) :
                    displaying ? I18n.s(R.string.ui_e1216a93fd24) :
                    running ? I18n.s(R.string.ui_dd920408cf5b) : I18n.s(R.string.ui_d9dce1b78d9e);
            }
            status.setText(stage + (operationError.isEmpty() ? "" : I18n.s(R.string.ui_4958d1bb5dd6)));
            JSONObject audioObj = state.optJSONObject("audio");
            if (audioObj != null) {
                audioOutEnabled = audioObj.optBoolean("outputEnabled", true);
                audioMuted = audioObj.optBoolean("muted", false);
                audioVolume = audioObj.optInt("volume", 100);
                audioInPolicy = audioObj.optString("inputPolicy", "mute_no_mic");
            }
            audioBtn.setEnabled(!brokerToken.isEmpty() && free && !state.optBoolean("cleanupPending"));
            tuningBtn.setEnabled(!brokerToken.isEmpty() && free && !state.optBoolean("cleanupPending"));

            start.setEnabled(!running && free && !state.optBoolean("cleanupPending"));
            stop.setEnabled(running && free);
            enter.setEnabled(ready && free && validSession(session));
            files.setEnabled(ready && free && validSession(session));
            importApp.setEnabled(ready && free && validSession(session));
            image.setEnabled(enter.isEnabled());

            renderPreview();
        }

        // 显示的 Bitmap 可能仍被系统的 RenderThread 渲染线程持有引用；
        // 丢弃 Java 引用让 Android 垃圾回收安全释放即可。
        // 只有从未展示过的陈旧解码结果才由预览回调显式调用 recycle()。
        void setImage(Bitmap next, long sequence, long captured) {
            bitmap = next;
            previewSequence = sequence;
            capturedAt = captured;
            image.setImageBitmap(next);
        }

        void clearImage() {
            image.setImageDrawable(null);
            bitmap = null;
            previewSequence = 0;
            capturedAt = 0;
        }

        /** 刷新小窗与覆盖层状态（控制日志与画面的层叠显示） */
        void renderPreview() {
            if (bitmap != null && !openingScreen && !displayingScreen && SystemClock.elapsedRealtime() - capturedAt > 15000) {
                clearImage();
                previewMessage = I18n.s(R.string.ui_aa135ad31231);
            }
            boolean picture = bitmap != null;
            boolean suppressingLogs = (openingScreen || displayingScreen) && !showLogs;
            // 停机或正在切换全屏时默认不展示开机日志覆盖层，保留最后一帧缩略图避免弹日志闪屏
            logVisible = showLogs || (running && !picture && !suppressingLogs);
            overlay.setVisibility(showLogs || (!picture && !suppressingLogs) ? View.VISIBLE : View.GONE);

            boolean rows = logVisible && !logLines.isEmpty();
            logScroll.setVisibility(rows ? View.VISIBLE : View.GONE);
            logText.setVisibility(rows ? View.GONE : View.VISIBLE);

            String heading = logVisible && !logKey.isEmpty() ?
                InstanceNames.title(number) + " · " + (running ? I18n.s(R.string.ui_442f1f0429ab) : I18n.s(R.string.ui_510aa6e5ed10)) + I18n.s(R.string.ui_fc4b4d491ceb) : stage;
            if (running && !ready && bootElapsedMs > 0) heading += I18n.s(R.string.ui_3b0267254d6b) + (bootElapsedMs / 1000) + I18n.s(R.string.ui_d9ad5055c354);
            if (!heading.contentEquals(stageText.getText())) stageText.setText(heading);

            String detail = bootLog.isEmpty() ?
                (running ? I18n.s(R.string.ui_e6b83064ea2c) : I18n.s(R.string.ui_abaa5827dc73)) : bootLog;
            if (!running && !showLogs)
                detail = InstanceNames.title(number) + I18n.s(R.string.ui_f225019fde8f) + (logKey.isEmpty() ? I18n.s(R.string.ui_17ebdf18caeb) : I18n.s(R.string.ui_5f46da0526f1));
            if (!operationError.isEmpty()) detail = I18n.s(R.string.ui_ec0d69e062e3) + operationError + "\n" + detail;
            if (!detail.contentEquals(logText.getText())) logText.setText(detail);

            logs.setText(!running ? (showLogs ? I18n.s(R.string.ui_0d8fd3900016) : I18n.s(R.string.ui_984a52c7c7dc)) : showLogs && picture ? I18n.s(R.string.ui_5abb2c0afb2b) : I18n.s(R.string.ui_8ad868abbca6));
            logs.setEnabled(!logKey.isEmpty() || history.length() > 0 || !bootLog.isEmpty() || picture || !operationError.isEmpty());
            caption.setText((openingScreen || displayingScreen) ? previewMessage :
                (picture ? I18n.s(R.string.ui_c941c5958c8c) + Math.max(0, (SystemClock.elapsedRealtime() - capturedAt) / 1000) + I18n.s(R.string.ui_4a22b12d9909) : previewMessage));
        }

        /** 禁用面板上的所有操作按钮（在异步请求发起或等待时调用） */
        void disable() {
            network.setEnabled(false);
            applicationRoot.setEnabled(false);
            reset.setEnabled(false);
            delete.setEnabled(false);
            start.setEnabled(false);
            stop.setEnabled(false);
            enter.setEnabled(false);
            audioBtn.setEnabled(false);
            tuningBtn.setEnabled(false);
            files.setEnabled(false);
            importApp.setEnabled(false);
            resolution.setEnabled(false);
            memory.setEnabled(false);
            monitor.setEnabled(!brokerToken.isEmpty());
            image.setEnabled(false);
        }

        /** 重置并清空面板显示状态 */
        void clear(String message) {
            session = "";
            ready = false;
            running = false;
            openingScreen = false;
            displayingScreen = false;
            memoryAvailable = false;
            netValid = false;
            netDns = "auto";
            netEffectiveDns = "";
            netEffectiveUplink = "";
            netEffectiveMode = "";
            netProxyApplied = false;
            rootValid = false;
            rootReady = false;
            applicationRoot.setText(I18n.s(R.string.ui_38ce86b1ada5));
            previewAllowed = false;
            stage = message;
            previewMessage = I18n.s(R.string.ui_bdefa3f1511a);
            operationError = "";
            clearImage();
            status.setText(message);
            resolutionText.setText(I18n.s(R.string.ui_e96adf072c24));
            memoryText.setText(I18n.s(R.string.ui_ccdfdd38867f));
            networkText.setText(I18n.s(R.string.ui_8950be7d6600));
            perfText.setText(I18n.known("性能：实例未运行"));
            disable();
            renderPreview();
        }
    }
}

