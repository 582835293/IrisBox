package com.irisbox.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
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
    private TextView pageText;
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

    /** 从 Broker 动态加载的分辨率预设列表 */
    private JSONArray resolutionPresets = new JSONArray();

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
        Button roms = new Button(this);
        roms.setText(I18n.s(R.string.ui_624266d31bd4));
        roms.setOnClickListener(v -> startActivity(new Intent(this, SetupActivity.class)));
        heading.addView(roms);
        add = new Button(this);
        add.setText(I18n.s(R.string.ui_4b95f6df9752));
        add.setTextSize(14);
        add.setContentDescription("iris-add-instance");
        add.setOnClickListener(v -> createInstance());
        heading.addView(add);
        root.addView(heading);

        // 顶部全局状态通告栏
        notice = text(I18n.s(R.string.ui_9f4a28e615cf), 13);
        notice.setMaxLines(1);
        notice.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(notice);

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
        // 页面暂停离开时清除预览缓存，避免下次回到页面在等待认证响应时误展示上一轮的残留画面
        for (Panel p : panels) if (p != null) { p.previewAllowed = false; p.clearImage(); }
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
            ui.post(() -> {
                if (epoch != generation || !resumed) return;
                connectionFailures = 0;
                connectionError = "";
                rootAttempted = false;
                reconnect.setVisibility(View.GONE);
                resolutionPresets = presets == null ? new JSONArray() : presets;
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
                        if (response != 202) panel.clearImage();
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

            // 2. 采用 RGB_565 并计算采样率（缩小至 640×360 范围内）
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            options.inSampleSize = 1;
            while (bounds.outWidth / options.inSampleSize > 640 || bounds.outHeight / options.inSampleSize > 360)
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
                    panel.clearImage();
                    panel.previewMessage = I18n.s(R.string.ui_2b1557f6ad3d);
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
                        if (epoch == generation && resumed) notice.setText(I18n.s(R.string.ui_b96f65eefc0b));
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
        help.setText(I18n.s(R.string.ui_83ff48e09334));
        help.setTextSize(15);
        content.addView(help);
        CheckBox google = new CheckBox(this);
        google.setText(I18n.s(R.string.ui_a655ea55c447));
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
        intent.putExtra("instanceId", panel.id)
            .putExtra("instanceNumber", panel.number)
            .putExtra("session", panel.session)
            .putExtra("brokerToken", brokerToken)
            .putExtra("brokerPort", 19762);
        startActivity(intent);
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

    /** 弹出内存上限调节对话框（支持输入 0.5 ~ 2.5 GiB 或一键恢复默认） */
    private void memory(Panel panel) {
        if (panel.running || !panel.memoryAvailable) return;
        final int epoch = generation, previous = panel.memoryMiB;
        LinearLayout content = column();
        content.setPadding(dp(20), dp(8), dp(20), dp(8));
        TextView help = new TextView(this);
        help.setTextSize(15);
        help.setText(I18n.s(R.string.ui_976a4799e83f));
        content.addView(help);

        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(20);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setContentDescription(InstanceNames.description("memory-input", panel.number));
        input.setHint(I18n.s(R.string.ui_01ab180f0a34));
        input.setText(previous > 0 ? gib(previous) : "1.25");
        input.selectAll();
        content.addView(input);

        TextView units = new TextView(this);
        units.setText(I18n.s(R.string.ui_1225ee44356f));
        units.setTextSize(13);
        content.addView(units);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(InstanceNames.title(panel.number) + I18n.s(R.string.ui_9e92c4cb6d22))
            .setView(content)
            .setNegativeButton(I18n.s(R.string.ui_4d0b4688c787), null)
            .setNeutralButton(I18n.s(R.string.ui_a191935bc678), (d, w) -> saveMemory(panel, 1280, previous, epoch))
            .setPositiveButton(I18n.s(R.string.ui_fadf24dbc5a9), null)
            .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = input.getText().toString().trim();
            try {
                if (!value.matches("[0-9]{1,2}(\\.[0-9]{1,3})?")) throw new IllegalArgumentException();
                BigDecimal amount = new BigDecimal(value);
                if (amount.compareTo(new BigDecimal("0.5")) < 0 || amount.compareTo(new BigDecimal("2.5")) > 0)
                    throw new IllegalArgumentException();
                if (previous < 0) { input.setError(I18n.s(R.string.ui_bd76d510bf82)); return; }
                int mib = amount.multiply(BigDecimal.valueOf(1024)).setScale(0, RoundingMode.HALF_UP).intValueExact();
                saveMemory(panel, mib, previous, epoch);
                dialog.dismiss();
            } catch (IllegalArgumentException | ArithmeticException invalid) {
                input.setError(I18n.s(R.string.ui_62553256518f));
            }
        }));
        dialog.show();
    }

    /** 打开 HTTP 连接并配置 Bearer Token 与超时参数 */
    private HttpURLConnection open(String method, String path, int epoch) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
        connections.add(connection);
        // 先登记连接再校验 epoch：pauseNetwork 会遍历强断登记的连接，或此处校验拦截注册过迟的请求
        if (epoch != generation || !resumed) {
            connection.disconnect();
            connections.remove(connection);
            throw new IOException("Page paused");
        }
        connection.setConnectTimeout(1500);
        connection.setReadTimeout(2500);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", "Bearer " + brokerToken);
        return connection;
    }

    /** 发送 HTTP 请求并严格核验预期返回码，读取限制长度的内容 */
    private byte[] request(String method, String path, int expected, int epoch) throws Exception {
        HttpURLConnection connection = open(method, path, epoch);
        try {
            if (method.equals("POST")) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(0);
                connection.getOutputStream().close();
            }
            if (connection.getResponseCode() != expected) throw new IOException("Request rejected");
            return readBounded(connection, 65536);
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
        final Button start, stop, enter, settings, clock, files, logs, resolution, memory, reset, delete, applicationRoot;

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
        boolean memoryAvailable;

        // 运行时状态标志
        volatile String session = "";
        volatile boolean previewAllowed;
        boolean ready, running, showLogs;
        String stage = I18n.s(R.string.ui_09cb521a07d3), bootLog = "", operationError = "", previewMessage = I18n.s(R.string.ui_d9dce1b78d9e);
        long bootElapsedMs, previewSequence, capturedAt;
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
            previewInfo.addView(logs, new LinearLayout.LayoutParams(dp(88), dp(36)));
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

            // 6. 快捷应用与配置栏（设置、时钟、文件、分辨率、内存）
            LinearLayout apps = new LinearLayout(ListActivity.this);
            settings = button(I18n.s(R.string.ui_7debf9cb0372), "settings", () -> action(this, "app", "settings"));
            clock = button(I18n.s(R.string.ui_cb6d970c41b1), "clock", () -> action(this, "app", "clock"));
            files = button(I18n.s(R.string.ui_49deaf7da20d), "files", () -> files(this));
            resolution = button(I18n.s(R.string.ui_cde9b52b2395), "resolution", () -> resolution(this));
            memory = button(I18n.s(R.string.ui_d014ab77038c), "memory", () -> memory(this));
            apps.addView(settings, new LinearLayout.LayoutParams(0, dp(38), 1));
            apps.addView(clock, new LinearLayout.LayoutParams(0, dp(38), 1));
            apps.addView(files, new LinearLayout.LayoutParams(0, dp(38), 1));
            apps.addView(resolution, new LinearLayout.LayoutParams(0, dp(38), 1));
            apps.addView(memory, new LinearLayout.LayoutParams(0, dp(38), 1));
            root.addView(apps);

            // 7. 高级与数据维护栏（Root、重置数据、删除实例）
            LinearLayout data = new LinearLayout(ListActivity.this);
            applicationRoot = button(I18n.s(R.string.ui_b72827b39c61), "root", () -> applicationRoot(this));
            data.addView(applicationRoot, new LinearLayout.LayoutParams(0, dp(36), 1));
            reset = button(I18n.s(R.string.ui_b1dd040286c1), "reset", () -> clearData(this, "reset"));
            delete = button(I18n.s(R.string.ui_6e73a5ffa5cc), "delete", () -> clearData(this, "delete"));
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
            boolean opening = state.optBoolean("opening") || busy.equals("open");
            boolean displaying = state.optBoolean("displaying");

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
                memoryMiB = mem.optInt("mib", -1);
                int effective = mem.optInt("effectiveMiB", 0);
                memoryText.setText(effective > 0 ? I18n.s(R.string.ui_37a78c8dc0a6) + gib(effective) + " GiB" :
                    memoryMiB > 0 ? I18n.s(R.string.ui_3ea894a932dd) + gib(memoryMiB) + " GiB" : I18n.s(R.string.ui_3204497ae97d));
            }
            memory.setEnabled(!running && free && memoryAvailable);

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

            previewAllowed = ready && validSession(session) && !opening && !displaying && !busy.equals("stop");
            if (!running || !ready || opening || displaying) {
                clearImage();
                previewMessage = opening ? I18n.s(R.string.ui_4bd5dfa2f736) :
                    displaying ? I18n.s(R.string.ui_e1216a93fd24) :
                    running ? I18n.s(R.string.ui_dd920408cf5b) : I18n.s(R.string.ui_d9dce1b78d9e);
            }
            status.setText(stage + (operationError.isEmpty() ? "" : I18n.s(R.string.ui_4958d1bb5dd6)));
            start.setEnabled(!running && free && !state.optBoolean("cleanupPending"));
            stop.setEnabled(running && free);
            enter.setEnabled(ready && free && validSession(session));
            settings.setEnabled(ready && free);
            clock.setEnabled(ready && free);
            files.setEnabled(ready && free && validSession(session));
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
            if (bitmap != null && SystemClock.elapsedRealtime() - capturedAt > 15000) {
                clearImage();
                previewMessage = I18n.s(R.string.ui_aa135ad31231);
            }
            boolean picture = bitmap != null;
            // 停机状态下默认不展示/加载日志历史；用户点击“上次日志”才展示，避免误以为正在点火
            logVisible = showLogs || running && !picture;
            overlay.setVisibility(showLogs || !picture ? View.VISIBLE : View.GONE);

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
            caption.setText(picture ? I18n.s(R.string.ui_c941c5958c8c) + Math.max(0, (SystemClock.elapsedRealtime() - capturedAt) / 1000) + I18n.s(R.string.ui_4a22b12d9909) : previewMessage);
        }

        /** 禁用面板上的所有操作按钮（在异步请求发起或等待时调用） */
        void disable() {
            applicationRoot.setEnabled(false);
            reset.setEnabled(false);
            delete.setEnabled(false);
            start.setEnabled(false);
            stop.setEnabled(false);
            enter.setEnabled(false);
            settings.setEnabled(false);
            clock.setEnabled(false);
            files.setEnabled(false);
            resolution.setEnabled(false);
            memory.setEnabled(false);
            image.setEnabled(false);
        }

        /** 重置并清空面板显示状态 */
        void clear(String message) {
            session = "";
            ready = false;
            running = false;
            memoryAvailable = false;
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
            disable();
            renderPreview();
        }
    }
}

