package com.huangguo.mobile;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONObject;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.interfaces.IVLCVout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

/**
 * 内置播放器（libVLC）。
 *
 * ── 为什么要有这个 Activity ───────────────────────────────────────────
 * 主播放界面是 WebView 里的 &lt;video&gt;，用哪个解码器由系统 WebView 决定，页面
 * 拿不到任何旋钮。于是同一部片在 A 手机上能放、B 手机上 MediaError 4
 * （SRC_NOT_SUPPORTED）—— 差别只有设备有没有那个硬解、WebView 版本里带没带
 * 那个解码器。软件解码这条路在 WebView 里根本不存在。
 *
 * libVLC 自带完整的 FFmpeg，HEVC、10bit、AV1、AC3/E-AC3 一律软解，和设备的
 * MediaCodec 能力脱钩。所以「别的手机放不了」这个问题只能在原生播放器里解决，
 * 而不是抛给用户去装 MX Player。
 *
 * ── 和页面的关系 ─────────────────────────────────────────────────────
 * 页面通过 `__player:native?payload=<json>` 把它拉起来；退出时这里把播放位置
 * 写进 SharedPreferences（hg_player/last），页面回到前台后读 `__player:last`
 * 拿回去，走原来那套 remember()/report() —— WebView 和原生两边的进度因此是
 * 同一份数据，不各存各的。
 *
 * 这也是**不**用 startActivityForResult 的原因：MainActivity 的
 * onActivityResult 在 smali 里，加一段得动 dispatch 区，而进度只要落到
 * prefs，页面一回到前台就能取，多一条无谓的链路没有必要。
 */
public class VlcPlayerActivity extends Activity implements MediaPlayer.EventListener {

    /** 页面传进来的那份 JSON。字段见 JS 侧 nativePayload()。 */
    public static final String EXTRA_PAYLOAD = "payload";

    /** 进度落盘。和 Java 侧的源配置分开放 —— 那是用户的设置，这是一次播放的现场。 */
    private static final String PREFS = "hg_player";
    private static final String KEY_LAST = "last";

    private static final long HIDE_CONTROLS_MS = 3200;
    private static final long SEEK_SPAN_MS = 180000;   // 横划满屏 = 3 分钟
    private static final float TOUCH_SLOP_DP = 14f;

    private int surfaceWidth;
    private int surfaceHeight;

    private LibVLC libVLC;
    private MediaPlayer player;
    private SurfaceView surface;
    private View topScrim, bottomScrim;

    private final JSONObject payload = new JSONObject();

    private long durationMs = 0;

    // 起播耗时打点。tag 固定成 HG.Vlc，`adb logcat -s HG.Vlc` 就能读到。
    // 这几个数是决定「能不能把整条流搬进原生」的唯一硬指标，所以写进产品里
    // 留着，而不是临时加一行测完就删。
    private long tCreate;
    private long tVlcReady;
    private long tPlay;
    private boolean tLogged;
    private long startedAt = 0;      // 页面要求从哪开始（ms）
    private boolean ended = false;
    private boolean errorShown = false;
    private String errorText = "";
    private long savedPosition = 0;

    // ── 播放埋点 ──────────────────────────────────────────────────────
    //
    // 这条链路以前一条数据都没有：全库 15,932 条事件里 kind='play' 是 **0**
    // 条。「播不了」是最常见的用户抱怨，而我们连「尝试了多少次、失败了几次、
    // 起播多慢」都答不出来 —— 报错表里只有 bridge/spider 那些上游 HTTP 错误，
    // 播放器自己一声不吭。
    //
    // 四个事件把这条链路补齐：
    //   play_start        进了 startPlayback（用户点了播放，且走到了原生）
    //   play_first_frame  出第一帧，带耗时。这是「卡不卡」唯一客观的数
    //   play_error        失败。stage 说明死在哪一环，是归因的唯一依据
    //   play_end          退出播放页，带时长和位置
    //
    // URL / 标题 / id 一律不上报：URL 里带 token，标题是用户内容。只留 host。
    private long playStartedAt;
    private boolean firstFrameReported;
    private boolean playErrorReported;
    /**
     * 这一场里至少有一集播到了 EndReached。
     *
     * 不能直接用 {@code ended}：feed 模式在 EndReached 里是「置 true → 落进度 →
     * 立刻置回 false」（紧接着要自动滑下一集），所以退出时报的是 false 就算播完了
     * 一整集也看不出来。单集模式的 ended 会一直留着，两者取或。
     */
    private boolean sawEnd;
    private boolean hwDecode = true;

    private FrameLayout root;

    /**
     * 控制层：顶栏/底栏/进度条/倍速面板/提示/错误块，以及那套手绘控件工厂。
     * 2026-10-18 从本类搬进 {@link VlcControlsView}。之前 buildUi() 一个人占
     * 270 行、还带一整套自制控件 —— 等于把 mini UI 框架焊死在播放器里。
     */
    private VlcControlsView controls;

    /** 当前倍速。控制层只负责显示，值在这儿 —— 换集要沿用。 */
    /** 上一次写进副标题的文本。逐帧比较用，避免每帧都 setText。 */
    private String lastMeta = "";

    private float feedRate = 1f;
    private static final float[] RATE_CHOICES = {2f, 1.5f, 1.25f, 1f, 0.75f, 0.5f};

    // ── mini-feed（整部剧上下滑）──────────────────────────────────────
    //
    // payload 里带 eps（全部集数）+ index（从第几集进来）时走这一套：
    // 竖向 RecyclerView + PagerSnapHelper 当翻页器，两个 MediaPlayer 固定身份
    // 轮转（A/B 各认一页），地址按需经 MainActivity 的 WebView 向页面要 ——
    // 解密、wasm、多线路那套逻辑全在页面里，原生不重复实现。
    private boolean feedMode;
    private int[] eps = new int[0];
    private int curPos;
    private RecyclerView pager;
    private LinearLayoutManager pagerLayout;
    private FeedAdapter feedAdapter;
    private MediaPlayer playerA, playerB;
    private int pageA = -1, pageB = -1;
    private MediaPlayer activeFeedPlayer;
    /** 退到后台的那一刻是不是正在播。回来时只在这条为真才自动续播。 */
    /**
     * 这一集的全部状态：地址缓存、解析令牌、失败标记、player↔地址、换线游标、续播现场。
     *
     * 以前是五个集合平铺在 Activity 字段里，加第六个时很容易漏一句 clear()，
     * 表现是「上一部的地址被当成这一部的」—— 对着日志猜很久。规则全在
     * {@link VlcSession}，那边有单测钉着；这里只调，不再自己维护集合。
     */
    private final VlcSession session = new VlcSession();

    /**
     * 每个 player 当前挂在哪块 TextureView 上。
     *
     * 用来做 attachVout 的幂等：每次 detachViews + attachViews 都会让 VLC 重建
     * 整条视频输出链（vout 线程、窗口、首个关键帧），几百毫秒里画面是黑的，而
     * 音频输出早就起来了 —— 用户听到声音、屏幕全黑，滑回上一集时最明显。
     * 同一块 view 已经挂着就只更新窗口尺寸，不拆不挂。
     */
    private final java.util.IdentityHashMap<MediaPlayer, android.view.TextureView> attachedTo =
            new java.util.IdentityHashMap<MediaPlayer, android.view.TextureView>();

    /**
     * 换页后等第一帧再放声音。
     *
     * 音频输出起来得比视频输出早：play() 一提交，aout 几十毫秒就能出声，而 vout
     * 要建窗口、解出第一个关键帧，几百毫秒到一秒不等。这段时间用户听到新一集的
     * 声音，屏幕上却是黑的 —— 滑回上一集时最明显。所以换页时先把音量压到 0，
     * 等 VLC 报 Vout（视频输出就绪）再抬回来；万一没有 Vout（纯音频流），
     * {@link #unmuteRunnable} 那个兜底定时器也会把声音放出来。
     */
    private boolean awaitingFirstFrame;
    private final Runnable unmuteRunnable = new Runnable() {
        @Override public void run() { unmute(); }
    };

    /**
     * 相邻页预热。
     *
     * 两页固定落在不同的 player 上（奇偶绑定），所以「另一个槽位」正好空着 ——
     * 把它拿去把用户最可能滑到的那一页先起播、静音、停在第一帧上。滑过去时
     * 只需要 play()，demuxer 和第一帧都现成，翻页基本是即时的。
     *
     * 只预一条，按滑动方向挑：上下两个邻居奇偶相同，抢的是同一个槽位，
     * 同时预两条就得塞第三个 player，多一份硬解实例 —— 不值得。
     *
     * warmPos/warmUrl 是「已就绪」的账，warmingPos 是「正在等第一帧」。
     * 只有 Vout 事件真的来了才算就绪：没就绪就滑过去，仍然走完整重开，
     * 不能让 playPage 误以为手上有一条暖着的流。
     */
    private int warmDirection = 1;   // +1 下一集，-1 上一集
    private int warmPos = -1;
    private String warmUrl = "";
    private int warmingPos = -1;
    /** 防抖：等当前这一页的硬解实例建好再去抢第二份，见 prewarmNeighbor。 */
    private int prewarmPending = -1;
    /**
     * play() 之后欠着没落的续播点。play() 一回来 demuxer 还没建，setTime 会被
     * 直接丢掉 —— 等 LengthChanged 事件到了再落，和单集模式 startedAt 一套机制。
     */
    private long pendingResume;
    private final Runnable prewarmRunnable = new Runnable() {
        @Override public void run() {
            int pos = prewarmPending;
            prewarmPending = -1;
            if (pos >= 0) prewarmNeighborNow(pos);
        }
    };


    /** 没有备选线路时的空表，省得每处都判 null。 */
    private static final String[] NO_ALTS = new String[0];

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable hideControls = new Runnable() {
        @Override public void run() {
            MediaPlayer current = feedMode ? activeFeedPlayer : player;
            if (current != null && current.isPlaying()) setControlsVisible(false);
        }
    };

    // ── 起播入口 ─────────────────────────────────────────────────────

    /** 页面桥调这里。context 通常是 MainActivity。 */
    public static void start(Context context, JSONObject config) {
        Intent intent = new Intent(context, VlcPlayerActivity.class);
        intent.putExtra(EXTRA_PAYLOAD, config.toString());
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /** 只探测一次：loadLibraries 会把 45MB 的 libvlc.so 映射进来，别反复试。 */
    private static Boolean nativeReady;

    /**
     * 这台设备上到底能不能用内置播放器。
     *
     * 判据是「试着加载一次」：LibVLC.loadLibraries() 在 so 缺失/ABI 不匹配时抛
     * UnsatisfiedLinkError，拿到就算能用。
     *
     * **不要改成查 nativeLibraryDir 里有没有 libvlc.so。** 那个目录的内容取决于
     * manifest 的 android:extractNativeLibs，两种取值都出现过：
     *
     *   "false"（1.0.23 及以前）so 留在 APK 里，链接器直接 mmap，目录永远是空的
     *   "true" （1.0.24 起）   安装时解压，目录里才有实体文件
     *
     * 也就是说「目录非空」只在当前配置下成立，换个配置就静默判错（表现为播放器
     * 起不来、页面退回网页播放器）。试加载在两种配置下都对。
     *
     * 为什么从 false 换成 true：下载体积 56.0 → 26.2 MB（libvlc.so 能 deflate 了），
     * 代价是多占 23MB 磁盘。真机实测冷启动无可测差异，播放路径完全等价。
     *
     * 结果缓存住，理由见上面那句 45MB。
     */
    public static boolean available(Context context) {
        if (nativeReady != null) return nativeReady.booleanValue();
        boolean ok = false;
        try {
            ok = supportsAbi();
            if (ok) {
                LibVLC.loadLibraries();
            }
        } catch (Throwable e) {
            ok = false;
        }
        nativeReady = Boolean.valueOf(ok);
        return ok;
    }

    private static boolean supportsAbi() {
        try {
            String[] abis = Build.SUPPORTED_ABIS;
            if (abis == null) return false;
            for (int i = 0; i < abis.length; i++) {
                if ("arm64-v8a".equals(abis[i])) return true;
            }
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    public static String abi() {
        try {
            String[] abis = Build.SUPPORTED_ABIS;
            return abis != null && abis.length > 0 ? abis[0] : "";
        } catch (Throwable e) {
            return "";
        }
    }

    /** 页面回到前台时读走一次，读完就清 —— 同一条记录不能被算了两次。 */
    public static JSONObject takeLastSession(Context context) {
        try {
            android.content.SharedPreferences prefs =
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String raw = prefs.getString(KEY_LAST, "");
            if (raw == null || raw.length() == 0) return null;
            prefs.edit().remove(KEY_LAST).apply();
            return new JSONObject(raw);
        } catch (Throwable e) {
            return null;
        }
    }

    // ── 生命周期 ─────────────────────────────────────────────────────

    @Override protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        tCreate = System.currentTimeMillis();
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        try {
            String raw = getIntent().getStringExtra(EXTRA_PAYLOAD);
            if (raw != null) {
                JSONObject parsed = new JSONObject(raw);
                java.util.Iterator<String> keys = parsed.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    payload.put(key, parsed.opt(key));
                }
            }
        } catch (Throwable ignored) {}

        // 把当前源交给中继：取流请求要套它的 headers 模板（Referer/UA），
        // 否则防盗链的 CDN 对分片一律 403。见 VlcRelay.applySourceHeaders。
        try { VlcRelay.setSourceProfile(SiteProfile.current(this)); } catch (Throwable ignored) {}

        startedAt = payload.optLong("start", 0L);
        savedPosition = startedAt;

        // mini-feed：eps 至少要两集才值得起翻页器，单集走老路。
        org.json.JSONArray epsRaw = payload.optJSONArray("eps");
        if (epsRaw != null && epsRaw.length() > 1) {
            feedMode = true;
            eps = new int[epsRaw.length()];
            for (int i = 0; i < epsRaw.length(); i++) eps[i] = epsRaw.optInt(i, i + 1);
            curPos = Math.max(0, Math.min(eps.length - 1, payload.optInt("index", 0)));
        }

        controls = new VlcControlsView(this, new ControlsHost());
        buildUi();
        if (!feedMode) {
            surface.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override public void surfaceCreated(SurfaceHolder holder) {}
                @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                    surfaceWidth = width;
                    surfaceHeight = height;
                    applyWindowSize();
                }
                @Override public void surfaceDestroyed(SurfaceHolder holder) {
                    surfaceWidth = 0;
                    surfaceHeight = 0;
                }
            });
        }
        VlcControlsView.applyImmersive(this);
        startPlayback();
    }

    private void startPlayback() {
        playStartedAt = System.currentTimeMillis();
        firstFrameReported = false;
        playErrorReported = false;
        JSONObject opening = new JSONObject();
        try {
            opening.put("mode", feedMode ? "feed" : "single");
            // 从 payload 读而不是读 hwDecode：hwDecode 要等 startVlc 才定下来，
            // 而 play_start 必须比任何一个失败分支都早 —— 否则「连 startVlc 都没
            // 走到」这种失败就没有 play_start 跟它配对了。
            opening.put("decode", "sw".equals(payload.optString("decode")) ? "sw" : "hw");
            opening.put("host", hostOf(payload.optString("url", "")));
            org.json.JSONArray alts = payload.optJSONArray("alts");
            org.json.JSONArray epsJson = payload.optJSONArray("eps");
            opening.put("alts", alts == null ? 0 : alts.length());
            opening.put("eps", epsJson == null ? 0 : epsJson.length());
        } catch (Throwable ignored) {}
        reportPlay("play_start", opening);

        if (feedMode) {
            if (!available(this)) {
                showError("unavailable", "这个安装包没有带内置播放器（只带了 arm64-v8a）。可以在设置里换回网页播放器。");
                return;
            }
            try {
                initFeedVlc();
            } catch (Throwable e) {
                showError("init-throw", e.getClass().getSimpleName(), "内置播放器起不来："
                        + AiiHttp.describe(e));
                return;
            }
            activate(curPos);
            return;
        }
        final String url = payload.optString("url", "");
        if (url.length() == 0) {
            showError("no-url", "payload 里没有播放地址 —— 解析链上游就没拿到。");
            return;
        }
        if (!available(this)) {
            showError("unavailable", "这个安装包没有带内置播放器（只带了 arm64-v8a）。可以在设置里换回网页播放器。");
            return;
        }
        try {
            startVlc(url);
        } catch (Throwable e) {
            // libVLC 在加载 so 或建实例时抛的都是 native 的错，能读到的只有消息。
            showError("init-throw", e.getClass().getSimpleName(), "内置播放器起不来："
                    + AiiHttp.describe(e));
        }
    }

    // ── 媒体流走内置核心 ────────────────────────────────────────────
    //
    // 这条路走的是「换地址」，不是「加选项」。原因见下面 relayUrl 的注释：
    // libVLC 的 media option 在这套 Android 构建上对 HTTP 取流全部无效，
    // 实测过 :socks / :http-proxy / 全局 --socks 三种，SOCKS 端口始终零连接。
    //
    // 所以 VLC 拿到的是一个本机 HTTP 地址（VlcRelay），真正取流的是 OkHttp，
    // 代理、TLS、Range 全在 OkHttp 那边，和 AiiHttp 走同一套 effectiveProxy()。
    //
    // 已知限制：核心重启会换端口，此时 VLC 正在播的那条流会断。不做热切换 ——
    // VLC 出错重试会重走 startVlc/switchTo，那时 relayUrl() 读到新端口能自己恢复。
    // 另外 VlcRelay 自己的监听端口一旦启动就不变，所以换的核心端口不影响它。

    /**
     * 把播放地址换成中继地址。
     *
     * **这是让 VLC 走代理的唯一可行办法。** 它的 media option（:socks / :http-proxy）
     * 实测全都不生效 —— 那两个选项挂在 Live555 的 RTSP 栈上，对 HTTP 取流没用。
     * 所以改成「换地址」：给 VLC 一个本机 HTTP 地址，背后是走代理的 OkHttp。
     *
     * 没有内置核心（或中继起不来）时原样返回，行为等同于直连 —— 和以前一样。
     */
    private String relayUrl(String url) {
        if (url == null || url.length() == 0) return url;
        // 判据（核心在不在跑、要不要绕）全在 VlcRelay.wrap 里，这里不再重复判断 ——
        // 两处各判一次，早晚会有一处改漏，然后表现成「核心开着但视频直连」。
        String wrapped = VlcRelay.wrap(url);
        return wrapped == null ? url : wrapped;
    }

    private void startVlc(String url) {
        ArrayList<String> options = new ArrayList<String>();
        options.add("--no-video-title-show");
        options.add("--audio-time-stretch");
        options.add("--network-caching=" + payload.optInt("cache", 1500));

        String decode = payload.optString("decode", "auto");
        hwDecode = !"sw".equals(decode);
        if (!hwDecode) options.add("--avcodec-hw=none");

        try {
            libVLC = new LibVLC(this, options);
        } catch (Throwable e) {
            // 参数被 libvlc_new 拒掉时会走到这里（它只回 0，异常消息里没有原因）。
            // 退到默认参数再试一次：宁可少几个偏好项，也别让用户对着黑屏。
            android.util.Log.w("HG.Vlc", "带参数创建失败：" + e + "，改用默认参数重试");
            libVLC = new LibVLC(this);
        }
        tVlcReady = System.currentTimeMillis();
        player = new MediaPlayer(libVLC);

        IVLCVout vout = player.getVLCVout();
        vout.setVideoView(surface);
        vout.attachViews();
        // 这一步不能省。不设窗口尺寸时 android_window 会把画面按**视频原始分辨率**
        // （比如 320×192）铺在左下角 —— 表现是「有声音、画面缩在角落一小块」。
        applyWindowSize();

        player.setEventListener(this);

        Media media = new Media(libVLC, Uri.parse(relayUrl(url)));
        // 有些站的 CDN 会校验 Referer/UA，缺了就只回一小段或直接 403。页面那边
        // 拿不到这两个头（同样是 WebView 的限制），在这里补上。
        String referer = payload.optString("referer", "");
        String agent = payload.optString("ua", "");
        if (referer.length() > 0) media.addOption(":http-referrer=" + referer);
        if (agent.length() > 0) media.addOption(":http-user-agent=" + agent);
        media.setHWDecoderEnabled(hwDecode, false);
        player.setMedia(media);
        media.release();

        float rate = (float) payload.optDouble("rate", 1.0);
        if (rate > 0.1f && Math.abs(rate - 1.0f) > 0.01f) player.setRate(rate);

        tPlay = System.currentTimeMillis();
        player.play();
        android.util.Log.i("HG.Vlc", "play() 提交：libVLC " + (tVlcReady - tCreate)
                + "ms，建播放器到提交 " + (tPlay - tVlcReady) + "ms");
        if (startedAt > 2000) {
            // play() 之后立刻 setTime 会被丢掉（还没有 demuxer），所以等长度的
            // 第一个事件再来落位置 —— 见 onEvent 里的 LengthChanged。
        }

        controls.setMeta((hwDecode ? "硬解优先" : "软解") + " · " + abi());
        ui.postDelayed(hideControls, HIDE_CONTROLS_MS);
    }

    /**
     * 把 SurfaceView 的像素尺寸交给 vout。
     *
     * IVLCVout.setWindowSize 是「渲染区域占多大」的唯一来源，VLC 不会自己去问
     * View 有多大：不设的话它按视频原始分辨率铺在左下角。
     */
    private void applyWindowSize() {
        // mini-feed：认中心页的 TextureView。旋转时 Activity 不重建
        //（configChanges 里声明了），TextureView 尺寸变化会走
        // onSurfaceTextureSizeChanged → attachVout，这里兜底转屏瞬间。
        if (feedMode) {
            PageView pv = feedAdapter == null ? null : feedAdapter.pageAt(curPos);
            if (pv != null && pv.getWidth() > 0 && pv.getHeight() > 0) {
                setWindowSizeFor(playerFor(curPos), pv.getWidth(), pv.getHeight());
            }
            return;
        }
        try {
            if (player == null) return;
            int width = surfaceWidth > 0 ? surfaceWidth : surface.getWidth();
            int height = surfaceHeight > 0 ? surfaceHeight : surface.getHeight();
            if (width <= 0 || height <= 0) return;
            IVLCVout vout = player.getVLCVout();
            if (vout != null) vout.setWindowSize(width, height);
        } catch (Throwable ignored) {}
    }

    /** 窗口尺寸改完必须 updateVideoSurfaces，否则 vout 还按旧尺寸铺画面。 */
    private void setWindowSizeFor(MediaPlayer mp, int width, int height) {
        if (mp == null || width <= 0 || height <= 0) return;
        try {
            IVLCVout vout = mp.getVLCVout();
            if (vout == null) return;
            vout.setWindowSize(width, height);
            mp.updateVideoSurfaces();
        } catch (Throwable ignored) {}
    }

    /**
     * 退到后台就闭嘴。
     *
     * 上一版只 pause 了单集模式的 player，feed 模式的两个 player 没人管 ——
     * 按 home 键、切到别的 app，画面走了声音还在放。onStop 再兜一次，
     * 因为有些 ROM 的 onPause 与 onStop 中间隔了几百毫秒，够放好几秒声音。
     */
    @Override protected void onPause() {
        if (feedMode) {
            pauseFeedForBackground();
            persistFeedProgress();
        } else {
            persist(false);
            if (player != null && player.isPlaying()) player.pause();
        }
        super.onPause();
    }

    @Override protected void onStop() {
        if (feedMode) pauseFeedForBackground();
        else if (player != null && player.isPlaying()) player.pause();
        super.onStop();
    }

    @Override protected void onStart() {
        super.onStart();
        // 只恢复「是被后台打断的播放」。用户自己按暂停退出去，回来还该是暂停。
        if (feedMode && session.wasPlaying()) {
            session.setWasPlaying(false);
            resumeFeedAfterBackground();
        }
    }

    /**
     * 从后台回来接着放。
     *
     * 和起播同一个顺序问题：后台期间 TextureView 的 buffer 基本都被系统回收了，
     * 回来时 surface 要重建。先 play() 的话音频立刻响，画面要等 surface 恢复、
     * vout 重挂、第一个关键帧解出来 —— 又是有声黑屏。
     *
     * 所以先挂 surface 再出声，并且静音起播，等画面真的有了再抬音量。
     *
     * 兜底只给 600ms，不像起播那样给 3 秒：从后台回来时数据本来就在缓冲区里，
     * 只要 vout 挂上，一两百毫秒就有帧了。VLC 也可能认得出是同一条流、不重发
     * Vout 事件，那时候靠这个短定时器兜住，不能让用户干等。
     */
    private void resumeFeedAfterBackground() {
        final MediaPlayer mp = activeFeedPlayer;
        if (mp == null) return;
        // surface 还在的话这一下就够；不在了就等 onSurfaceTextureAvailable 那边补挂。
        attachVout(curPos, true);
        awaitingFirstFrame = true;
        ui.removeCallbacks(unmuteRunnable);
        ui.postDelayed(unmuteRunnable, 600);
        try { mp.setVolume(0); } catch (Throwable ignored) {}
        try { if (!mp.isPlaying()) mp.play(); } catch (Throwable ignored) {}
        // 刚回到前台时 View 可能还没量好（窗口尺寸是 0），等布局落定再挂一次。
        ui.postDelayed(new Runnable() {
            @Override public void run() { attachVout(curPos, true); }
        }, 150);
    }

    /** 两个 player 全静下来；记录「退后台前是不是在播」。 */
    private void pauseFeedForBackground() {
        ui.removeCallbacks(prewarmRunnable);
        prewarmPending = -1;
        MediaPlayer[] all = { playerA, playerB };
        for (int i = 0; i < all.length; i++) {
            MediaPlayer mp = all[i];
            if (mp == null) continue;
            try {
                if (mp.isPlaying()) {
                    if (mp == activeFeedPlayer) session.setWasPlaying(true);
                    mp.pause();
                }
            } catch (Throwable ignored) {}
        }
    }

    @Override protected void onDestroy() {
        // 报在 persist 之前：persist 有 1.5 秒节流，会被提前 return 掉，
        // 而 play_end 是一次性的，不能跟着被吞。
        if (playStartedAt > 0) {
            JSONObject closed = new JSONObject();
            try {
                long now = System.currentTimeMillis();
                closed.put("ms", (int) Math.min(Math.max(0, now - playStartedAt), 86400000L));
                closed.put("pos", (int) Math.max(0, Math.min(currentPosition(), 86400000L)));
                closed.put("dur", (int) Math.max(0, Math.min(durationMs, 86400000L)));
                closed.put("finished", ended || sawEnd);
                closed.put("err", errorShown);
                closed.put("frame", firstFrameReported);
                closed.put("mode", feedMode ? "feed" : "single");
            } catch (Throwable ignored) {}
            reportPlay("play_end", closed);
        }
        if (feedMode) {
            // 顺序要紧：先落盘再释放。反过来 getTime() 全是 0。
            persistFeedProgress();
            releaseFeedPlayers();
        } else {
            persist(ended);
        }
        ui.removeCallbacksAndMessages(null);
        releaseOne(player);
        player = null;
        try { if (libVLC != null) { libVLC.release(); libVLC = null; } } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        ui.post(new Runnable() {
            @Override public void run() { applyWindowSize(); }
        });
        // 布局动画第二拍再兜一次，防止单靠 TextureView 回调时序不齐。
        ui.postDelayed(new Runnable() {
            @Override public void run() { applyWindowSize(); }
        }, 320);
    }

    /**
     * 同一个 Activity 被再次拉起（比如用户在播放页又点了一次「内置」）。
     * 默认行为是把 intent 丢掉，画面还停在上一条流上 —— 这里重新解析参数、重开一次，
     * 免得用户以为按钮没反应。
     */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        persist(ended);
        try {
            String raw = intent.getStringExtra(EXTRA_PAYLOAD);
            if (raw == null) return;
            JSONObject parsed = new JSONObject(raw);
            java.util.Iterator<String> keys = parsed.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                payload.put(key, parsed.opt(key));
            }
        } catch (Throwable ignored) { return; }

        ended = false;
        tCreate = System.currentTimeMillis();
        tLogged = false;
        android.util.Log.i("HG.Vlc", "换集：重开 media");
        startedAt = payload.optLong("start", 0L);
        savedPosition = startedAt;
        durationMs = 0;
        if (feedMode) {
            // feed 用的是一对 player，closePlayer 只管单集那个 —— 不在这里收掉
            // 旧的一对，再 initFeedVlc 就是两个实例叠着放，声音会双重。
            releaseFeedPlayers();
            // 换集：五个集合 + 换线游标 + 续播现场一起清。
            session.reset();
            // 预热账本和结束标记都是上一部的，不复位的话换集后 playPage 会把
            // 旧页当成暖的直接 play，play_end 还会把 finished 误报成 true。
            sawEnd = false;
            warmPos = -1;
            warmUrl = "";
            warmingPos = -1;
            pendingResume = 0;
        }
        closePlayer();
        startPlayback();
    }

    private void closePlayer() {
        releaseOne(player);
        player = null;
        try { if (libVLC != null) { libVLC.release(); libVLC = null; } } catch (Throwable ignored) {}
    }

    @Override public void onBackPressed() {
        // 锁着的时候返回键不退出 —— 误触锁的一大半用途就是防这个。
        if (locked) {
            controls.showHint("已锁定 · 连点两次小锁解锁");
            controls.hideHintAfter(1500, ui);
            return;
        }
        if (feedMode) persistFeedProgress();
        else persist(ended);
        super.onBackPressed();
    }

    /** 上一次落盘的时间。onPause 和 onDestroy 都会写，别让后者把前者的记录再写一遍。 */
    private long lastPersistAt;

    /** 把这一次播放的现场落到 prefs。finished=true 表示播完了。 */
    private void persist(boolean finished) {
        // feed 模式有自己那份（persistFeedProgress）：集数、当前集地址都不一样。
        // 这里再写一条会把刚写好的覆盖成「payload 里 index 那一集 + 位置 0」，
        // 页面收回去就成了「回滑到第一集、从头开始」。
        if (feedMode) return;
        // 退出时 onPause → onDestroy 会连着来两次。两次都写的话，页面收回第一条之后
        // prefs 里还会剩一条一模一样的 —— 下次回到前台它又被收一遍，把更新的进度盖回旧的。
        long now = System.currentTimeMillis();
        if (!finished && now - lastPersistAt < 1500) return;
        lastPersistAt = now;
        try {
            long position = savedPosition;
            if (player != null) {
                long live = player.getTime();
                if (live > 0) position = live;
            }
            if (durationMs <= 0 && player != null) durationMs = player.getLength();

            JSONObject record = new JSONObject();
            record.put("url", payload.optString("url", ""));
            record.put("id", payload.optString("id", ""));
            record.put("episode", payload.optInt("episode", 1));
            record.put("title", payload.optString("title", ""));
            record.put("cover", payload.optString("cover", ""));
            record.put("source", payload.optString("source", ""));
            record.put("position", Math.max(0, position));
            record.put("duration", Math.max(0, durationMs));
            record.put("finished", finished || ended);
            record.put("error", errorText);
            record.put("at", System.currentTimeMillis());
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_LAST, record.toString()).apply();
        } catch (Throwable ignored) {}
    }

    // ── 界面 ─────────────────────────────────────────────────────────

    /** 手势阈值要用 dp；实现归 VlcControlsView。 */
    private int dp(float value) { return controls.dp(value); }
    /** 控制层回调用到这里。只转「用户做了什么」，不搬状态。 */
    private final class ControlsHost implements VlcControlsView.Host {
        @Override public void onBack() { finish(); }
        @Override public void onPlayPause() { togglePlay(); }
        @Override public void onSeekBy(long deltaMs) { seekBy(deltaMs); }
        @Override public void onSeekTo(long permille) {
            MediaPlayer target = feedMode ? activeFeedPlayer : player;
            if (target != null && durationMs > 0) target.setTime(durationMs * permille / 1000);
        }
        @Override public void onSpeedToggle() { toggleSpeedPanel(); }
        @Override public void onScrubStart() { ui.removeCallbacks(hideControls); }
        @Override public void onScrubEnd() { ui.postDelayed(hideControls, HIDE_CONTROLS_MS); }
        @Override public int durationMs() { return (int) durationMs; }
        @Override public void onLockToggle() { setLocked(!locked); }
    }

    /**
     * 防误触锁。
     *
     * 锁上之后：单击不再出控制栏（只提示怎么解）、横划不再拖进度、竖划不再切集、
     * 系统返回键也不退出。屏幕上只留左边缘那个锁，点它才解开。
     *
     * 为什么不只藏控件：藏了控件，手势还在 —— 兜里蹭一下照样跳进度、切到下一集，
     * 这才是用户真正被咬到的地方。
     */
    private boolean locked;

    private void setLocked(boolean value) {
        if (locked == value) return;
        locked = value;
        controls.setLocked(value);
        ui.removeCallbacks(hideControls);
        if (value) {
            controls.showHint("已锁定 · 连点两次小锁解锁");
        } else {
            controls.showHint("已解锁");
            setControlsVisible(true);
        }
        controls.hideHintAfter(1800, ui);
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (feedMode) {
            pagerLayout = new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false);
            pager = new RecyclerView(this);
            pager.setLayoutManager(pagerLayout);
            pager.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            new PagerSnapHelper().attachToRecyclerView(pager);
            feedAdapter = new FeedAdapter();
            pager.setAdapter(feedAdapter);
            // 从第 N 集点进来时 curPos=N-1，但 RecyclerView 初始布局永远落在第 0
            // 页 —— 不滚过去，pageAt(curPos) 是 null，attachVout 在 pv==null 处
            // 静默 return，音频照播、vout 永远没挂（表现：有声无画，日志
            // Opaque Vout request failed）。滚动必须在布局完成后，所以 post 一拍。
            if (curPos > 0) pager.post(new Runnable() {
                @Override public void run() { pager.scrollToPosition(curPos); }
            });
            pager.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
                    // dy>0 = 手指往上推 = 看下一集；dy<0 = 回上一集。
                    // 有的 ROM 翻页只报一次小位移，所以谁非零就记谁。
                    if (dy > 0) warmDirection = 1;
                    else if (dy < 0) warmDirection = -1;
                }

                @Override public void onScrollStateChanged(RecyclerView rv, int newState) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        int pos = centeredPosition();
                        if (pos >= 0 && pos != curPos) activate(pos);
                        // 停在原地但换过方向（推了一半又回弹），也要把预热的目标换掉。
                        else if (pos >= 0) prewarmNeighbor(pos);
                    }
                }
            });
            pager.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
                private float downX, downY;
                private long downAt;
                private boolean seekMode;

                @Override public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
                    if (locked) {
                        // 全吃掉：翻页、横划拖进度、单击出控件都不该发生。
                        // 只留一句提示告诉用户怎么解开。
                        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                            float dx = e.getX() - downX, dy = e.getY() - downY;
                            if (Math.abs(dx) < dp(TOUCH_SLOP_DP) && Math.abs(dy) < dp(TOUCH_SLOP_DP)) {
                                controls.showHint("已锁定 · 连点两次小锁解锁");
                                controls.hideHintAfter(1500, ui);
                            }
                        }
                        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                            downX = e.getX(); downY = e.getY(); downAt = System.currentTimeMillis();
                            seekMode = false;
                        }
                        return true;
                    }
                    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        downX = e.getX(); downY = e.getY(); downAt = System.currentTimeMillis();
                        seekMode = false;
                        return false;
                    }
                    // 单击（没挪、够快）= 出/收控件。RecyclerView 自己会吞 DOWN，
                    // OnClickListener 收不到，所以 tap 的识别只能放在这里。
                    if (e.getActionMasked() == MotionEvent.ACTION_UP && !seekMode
                            && System.currentTimeMillis() - downAt < 300) {
                        float dx = e.getX() - downX, dy = e.getY() - downY;
                        if (Math.abs(dx) < dp(TOUCH_SLOP_DP) && Math.abs(dy) < dp(TOUCH_SLOP_DP)) {
                            toggleControls();
                        }
                    }
                    if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                        float dx = e.getX() - downX, dy = e.getY() - downY;
                        // 横向够长就归 seek；竖向还给翻页器
                        if (!seekMode && Math.abs(dx) > dp(TOUCH_SLOP_DP) && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                            seekMode = true;
                        }
                        if (seekMode) return true;
                    }
                    return false;
                }

                @Override public void onTouchEvent(RecyclerView rv, MotionEvent e) {
                    if (locked) {
                        // 锁定时 DOWN 已经被 intercept 吃掉，后续的 MOVE/UP 走到这里。
                        // 提示只能在这儿给 —— 拦到 intercept 之后不会再回调它。
                        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                            float dx = e.getX() - downX, dy = e.getY() - downY;
                            if (Math.abs(dx) < dp(TOUCH_SLOP_DP) && Math.abs(dy) < dp(TOUCH_SLOP_DP)) {
                                controls.showHint("已锁定 · 连点两次小锁解锁");
                                controls.hideHintAfter(1500, ui);
                            }
                        }
                        return;
                    }
                    if (e.getActionMasked() == MotionEvent.ACTION_MOVE && seekMode) {
                        float dx = e.getX() - downX;
                        long target = feedTimeOf(curPos) + (long) (dx / Math.max(1, rv.getWidth()) * SEEK_SPAN_MS);
                        long dur = feedDurationOf(curPos);
                        if (dur > 0) target = Math.max(0, Math.min(dur, target));
                        showHint((target >= feedTimeOf(curPos) ? "快进 " : "回退 ")
                                + Math.abs((target - feedTimeOf(curPos)) / 1000) + " 秒 · "
                                + fmt(target) + (dur > 0 ? " / " + fmt(dur) : ""));
                        gestureTarget = target;
                        return;
                    }
                    if (e.getActionMasked() == MotionEvent.ACTION_UP && seekMode) {
                        MediaPlayer mp = activeFeedPlayer;
                        if (mp != null && gestureTarget >= 0) mp.setTime(gestureTarget);
                        hideHintSoon();
                    }
                }

                @Override public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {}
            });
            root.addView(pager);
        } else {
            surface = new SurfaceView(this);
            surface.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(surface);
        }

        // 控制层：顶栏/底栏/进度条/倍速面板/提示/错误块 —— 全在 VlcControlsView。
        controls.attach(root, payload.optString("title", ""), !feedMode,
                new View.OnTouchListener() {
                    @Override public boolean onTouch(View v, MotionEvent event) {
                        return onRootTouch(event);
                    }
                });
        setContentView(root);
        controls.setVisible(true);
    }
    private void setControlsVisible(boolean visible) {
        controls.setVisible(visible);
        MediaPlayer current = feedMode ? activeFeedPlayer : player;
        if (visible) {
            ui.removeCallbacks(hideControls);
            if (current != null && current.isPlaying()) ui.postDelayed(hideControls, HIDE_CONTROLS_MS);
        } else {
            ui.removeCallbacks(hideControls);
        }
    }
    private void toggleControls() {
        setControlsVisible(!controls.isBarVisible());
    }

    // ── 手势 ─────────────────────────────────────────────────────────

    private float downX, downY;
    private long downAt, gestureStartMs;
    private int gesture = 0;              // 0 未定 / 1 拖进度 / 2 亮度 / 3 音量
    private float downBrightness;
    private int downVolume, maxVolume;

    private boolean onRootTouch(MotionEvent event) {
        if (locked) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = event.getX(); downY = event.getY(); downAt = System.currentTimeMillis();
                gesture = 0;
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                float dx = event.getX() - downX, dy = event.getY() - downY;
                if (Math.abs(dx) < dp(TOUCH_SLOP_DP) && Math.abs(dy) < dp(TOUCH_SLOP_DP)
                        && System.currentTimeMillis() - downAt < 300) {
                    controls.showHint("已锁定 · 连点两次小锁解锁");
                    controls.hideHintAfter(1500, ui);
                }
            }
            return true;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX(); downY = event.getY(); downAt = System.currentTimeMillis();
                gesture = 0;
                try { downBrightness = getWindow().getAttributes().screenBrightness; } catch (Throwable ignored) {}
                if (downBrightness < 0) downBrightness = 0.5f;
                try {
                    AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                    maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    downVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                } catch (Throwable ignored) { maxVolume = 0; }
                if (player != null) gestureStartMs = player.getTime();
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (gesture == 0) {
                    if (Math.abs(dx) < dp(TOUCH_SLOP_DP) && Math.abs(dy) < dp(TOUCH_SLOP_DP)) return true;
                    if (Math.abs(dx) > Math.abs(dy)) gesture = 1;
                    else gesture = event.getX() < root.getWidth() / 2f ? 2 : 3;
                    if (gesture == 1) ui.removeCallbacks(hideControls);
                }
                if (gesture == 1) {
                    long target = gestureStartMs + (long) (dx / Math.max(1, root.getWidth()) * SEEK_SPAN_MS);
                    if (durationMs > 0) target = Math.max(0, Math.min(durationMs, target));
                    gestureTarget = Math.max(0, target);
                    long delta = (gestureTarget - gestureStartMs) / 1000;
                    showHint((delta >= 0 ? "快进 " : "回退 ") + Math.abs(delta) + " 秒 · "
                            + fmt(gestureTarget) + (durationMs > 0 ? " / " + fmt(durationMs) : ""));
                } else if (gesture == 2) {
                    float value = downBrightness - dy / Math.max(1, root.getHeight());
                    value = Math.max(0.02f, Math.min(1f, value));
                    WindowManager.LayoutParams attrs = getWindow().getAttributes();
                    attrs.screenBrightness = value;
                    getWindow().setAttributes(attrs);
                    showHint("亮度 " + Math.round(value * 100) + "%");
                } else if (gesture == 3 && maxVolume > 0) {
                    float ratio = downVolume - dy / Math.max(1f, root.getHeight()) * maxVolume;
                    int value = Math.max(0, Math.min(maxVolume, Math.round(ratio)));
                    try {
                        AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0);
                    } catch (Throwable ignored) {}
                    showHint("音量 " + Math.round(value * 100f / maxVolume) + "%");
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                long spent = System.currentTimeMillis() - downAt;
                if (gesture == 1) {
                    if (player != null) player.setTime(gestureTarget);
                    ui.postDelayed(hideControls, HIDE_CONTROLS_MS);
                } else if (gesture == 0 && spent < 260) {
                    toggleControls();
                }
                hideHintSoon();
                return true;
            }

            case MotionEvent.ACTION_CANCEL:
                hideHintSoon();
                return true;
            default:
                return false;
        }
    }

    private long gestureTarget = 0;

    private void showHint(String text) {
        controls.showHint(text);
        ui.removeCallbacks(hideHint);
    }

    private void hideHintSoon() {
        ui.removeCallbacks(hideHint);
        ui.postDelayed(hideHint, 700);
    }

    private final Runnable hideHint = new Runnable() {
        @Override public void run() { controls.hideHint(); }
    };

    // ── 播放控制 ─────────────────────────────────────────────────────

    private MediaPlayer feedTarget() {
        return feedMode ? activeFeedPlayer : player;
    }

    private void seekBy(long delta) {
        MediaPlayer player = feedTarget();
        if (player == null) return;
        long target = Math.max(0, player.getTime() + delta);
        if (durationMs > 0) target = Math.min(durationMs, target);
        player.setTime(target);
        showHint((delta > 0 ? "快进 " : "回退 ") + Math.abs(delta / 1000) + " 秒");
        hideHintSoon();
    }

    private void togglePlay() {
        MediaPlayer player = feedTarget();
        if (player == null) return;
        boolean wasPlaying = player.isPlaying();
        if (wasPlaying) {
            player.pause();
            controls.setPlayIcon(VlcControlsView.IconButton.PLAY);
        } else {
            player.play();
            controls.setPlayIcon(VlcControlsView.IconButton.PAUSE);
        }
        setControlsVisible(true);
    }

    private static final float[] SPEEDS = {1f, 1.25f, 1.5f, 2f, 0.75f};
    private int speedIndex = 0;

    private void toggleSpeedPanel() {
        MediaPlayer player = feedTarget();
        float current = player == null ? feedRate : player.getRate();
        if (current <= 0.01f) current = feedRate;
        // 面板的搭建（选项、高亮、显隐）归控制层；这边只说「现在是多少倍速」。
        controls.toggleSpeedPanel(current, RATE_CHOICES, new VlcControlsView.RatePicker() {
            @Override public void onPick(float rate) { chooseRate(rate); }
        });
        ui.removeCallbacks(hideControls);
    }

    private void chooseRate(float rate) {
        feedRate = rate;
        MediaPlayer player = feedTarget();
        if (player != null) player.setRate(rate);
        controls.setSpeedLabel(trimRate(rate) + "×");
        controls.hideSpeedPanel();
        showHint("倍速 " + trimRate(rate) + "×");
        hideHintSoon();
        ui.postDelayed(hideControls, HIDE_CONTROLS_MS);
    }

    private static String trimRate(float rate) {
        String text = String.format(Locale.US, "%.2f", rate);
        if (text.endsWith("0")) text = text.substring(0, text.length() - 1);
        if (text.endsWith(".0")) text = text.substring(0, text.length() - 2);
        return text;
    }

    /**
     * 播放失败的唯一收口。
     *
     * stage 说明死在哪一环，是这些报错里唯一能拿来做归因的东西：
     *   no-url          没有地址（上游解析链就没拿到）
     *   unavailable     这个包没带内置播放器（32 位机 / so 缺失）
     *   init-throw      libVLC 起不来（so 加载、建实例时 native 抛错）
     *   vlc-error       起来了但解不了（EncounteredError）
     *   alts-exhausted  feed 模式备选线路全试完了
     */
    private void showError(String stage, String code, String text) {
        errorText = text;
        errorShown = true;
        controls.setSpinnerVisible(false);
        controls.setError(text + "\n\n点返回可以换一个源试试。");
        controls.setMeta("播放失败");
        reportPlayFailure(stage, code, text);
    }

    private void showError(String stage, String text) {
        showError(stage, "", text);
    }

    private void reportPlay(String name, JSONObject props) {
        try {
            Report.event("play", name, props);
        } catch (Throwable ignored) {}
    }

    /**
     * 一次播放会话只报第一条失败。
     *
     * EncounteredError 会重复触发（VLC 自己会重试），逐条上报的话一台设备能刷出
     * 几十条同一件事 —— 2.0.20~2.0.21 那 1407 条「封面解密结果无效」里 1359 条
     * 就是这么来的。要答的问题是「有没有失败」，不是「失败了几回合」。
     */
    private void reportPlayFailure(String stage, String code, String text) {
        if (playErrorReported) return;
        playErrorReported = true;
        JSONObject props = new JSONObject();
        try {
            props.put("stage", stage);
            if (code != null && code.length() > 0) props.put("code", code);
            props.put("msg", text == null ? "" : text);
            props.put("mode", feedMode ? "feed" : "single");
            props.put("ms", (int) Math.min(Math.max(0, System.currentTimeMillis() - playStartedAt), 600000L));
            // 上游状态码：它把「源站这一集的地址没了」和「播放器解不了」分成两件事。
            // playbackFailureText() 一直在用这个值说话，但从来没有作为数据留下过。
            int up = VlcRelay.recentUpstreamFailure();
            if (up > 0) props.put("up", up);
        } catch (Throwable ignored) {}
        reportPlay("play_error", props);
    }

    /** 出第一帧。单集模式认 Playing，feed 模式认 Vout（Playing 时一帧都还没解）。 */
    private void reportFirstFrame() {
        if (firstFrameReported) return;
        firstFrameReported = true;
        JSONObject props = new JSONObject();
        try {
            long now = System.currentTimeMillis();
            props.put("ms", (int) Math.min(Math.max(0, now - playStartedAt), 600000L));
            if (tPlay > 0) props.put("submit", (int) Math.min(Math.max(0, now - tPlay), 600000L));
            props.put("hw", hwDecode);
            props.put("mode", feedMode ? "feed" : "single");
        } catch (Throwable ignored) {}
        reportPlay("play_first_frame", props);
    }

    /**
     * 退出播放页时的真实位置。
     *
     * **不能直接读 savedPosition。** feed 模式（现在的主路径）根本不写它 ——
     * TimeChanged 走的是 handleFeedEvent，写 savedPosition 的是单集模式那条
     * handleEvent。真机实测：feed 模式播了 41 秒，play_end.pos 报的是 0，
     * 看着像「一秒都没播」。
     *
     * 所以这里现读活着的那个 player。onDestroy 里这段跑在 persist /
     * releaseFeedPlayers 之前，player 还活着。
     */
    private long currentPosition() {
        try {
            MediaPlayer mp = feedMode ? activeFeedPlayer : player;
            if (mp == null) return savedPosition;
            long live = mp.getTime();
            return live > 0 ? live : savedPosition;
        } catch (Throwable ignored) {
            return savedPosition;
        }
    }

    /** 只留主机名 —— 完整 URL 带 token，标题是用户内容，都不进埋点。 */
    private static String hostOf(String url) {
        try {
            String host = android.net.Uri.parse(url == null ? "" : url).getHost();
            return host == null ? "" : host;
        } catch (Throwable ignored) {
            return "";
        }
    }

    // ── libVLC 回调（跑在 VLC 自己的线程上） ──────────────────────────

    @Override public void onEvent(final MediaPlayer.Event event) {
        ui.post(new Runnable() {
            @Override public void run() { handleEvent(event); }
        });
    }

    private void handleEvent(MediaPlayer.Event event) {
        final int type = event.type;
        if (player == null) return;
        if (type == MediaPlayer.Event.LengthChanged) {
            durationMs = player.getLength();
            if (startedAt > 2000 && durationMs > startedAt + 3000) {
                player.setTime(startedAt);
                startedAt = 0;
            }
        } else if (type == MediaPlayer.Event.TimeChanged) {
            long time = player.getTime();
            if (time > 0) savedPosition = time;
            if (time % 1000 < 400) {
                updateClock(time);
                describeTrack();
            }
        } else if (type == MediaPlayer.Event.Playing) {
            if (!tLogged) {
                tLogged = true;
                long now = System.currentTimeMillis();
                android.util.Log.i("HG.Vlc", "首帧：onCreate→播放 " + (now - tCreate)
                        + "ms（其中 play() 之后 " + (now - tPlay) + "ms）");
            }
            controls.setSpinnerVisible(false);
            controls.setPlayIcon(VlcControlsView.IconButton.PAUSE);
            describeTrack();
            reportFirstFrame();
        } else if (type == MediaPlayer.Event.Buffering) {
            if (event.getBuffering() < 100f && !player.isPlaying()) controls.setSpinnerVisible(true);
            else controls.setSpinnerVisible(false);
        } else if (type == MediaPlayer.Event.Paused) {
            controls.setPlayIcon(VlcControlsView.IconButton.PLAY);
            setControlsVisible(true);
        } else if (type == MediaPlayer.Event.EndReached) {
            ended = true;
            controls.setPlayIcon(VlcControlsView.IconButton.PLAY);
            setControlsVisible(true);
            persist(true);
        } else if (type == MediaPlayer.Event.Vout) {
            // 视频输出就绪：这才是「画面能出来了」的时刻，把声音放开。
            unmute();
        } else if (type == MediaPlayer.Event.EncounteredError) {
            showError("vlc-error", "EncounteredError", playbackFailureText());
        }
    }

    /** 把当前视频轨的编码/分辨率写到副标题上 —— 排查「这台机器放不了」时最有用的一行。 */
    /**
     * 播放失败时该说的话。
     *
     * <p>中继知道上游回了什么，所以能分清两条完全不同的路：上游 404/403 是
     * 源站这一集的地址没了（换一集就能看，重取多少次都一样）；其余才是
     * 播放器解不了。真机实测：三国演义第 1 集三条备选全部指到一台整站 404 的
     * CDN（连根路径都回 404），这时候再让人「回上一页重取」是白折腾。
     */
    private String playbackFailureText() {
        int up = VlcRelay.recentUpstreamFailure();
        if (up == 404) return "在源站已经不存在（上游 404），换一集或者换个源试试。";
        if (up == 403) return "被源站 CDN 拒了（上游 403），换一集或者换个源试试。";
        if (up >= 400) return "源站返回 " + up + "，换一集或者换个源试试。";
        return "内置播放器也解不了，回上一页重新取一次。";
    }

    private void describeTrack() {
        try {
            if (player == null) return;
            IMedia.VideoTrack track = player.getCurrentVideoTrack();
            if (track == null) return;
            String codec = track.codec == null ? "" : track.codec.toUpperCase(Locale.US);
            String text = track.width + "×" + track.height
                    + (codec.length() > 0 ? " " + codec : "")
                    + " · " + (hwDecode ? "硬解优先" : "软解");
            if (!text.equals(lastMeta)) { lastMeta = text; controls.setMeta(text); }
        } catch (Throwable ignored) {}
    }

    private void updateClock(long time) {
        if (durationMs <= 0) durationMs = player == null ? 0 : player.getLength();
        controls.setClock(fmt(time) + " / " + (durationMs > 0 ? fmt(durationMs) : "--:--"));
        if (durationMs > 0) {
            int progress = (int) Math.min(1000, time * 1000 / durationMs);
            if (!controls.seekBar.isPressed()) controls.seekBar.setProgress(progress);
        }
    }

    private static String fmt(long ms) {
        if (ms <= 0) return "00:00";
        long total = ms / 1000;
        long hour = total / 3600;
        long minute = (total % 3600) / 60;
        long second = total % 60;
        if (hour > 0) return String.format(Locale.US, "%d:%02d:%02d", hour, minute, second);
        return String.format(Locale.US, "%02d:%02d", minute, second);
    }

    // ══ mini-feed：两个轮转 + 按需取地址 ══════════════════════════════

    private void initFeedVlc() throws Throwable {
        ArrayList<String> options = new ArrayList<String>();
        options.add("--no-video-title-show");
        options.add("--audio-time-stretch");
        options.add("--network-caching=" + payload.optInt("cache", 1500));
        hwDecode = !"sw".equals(payload.optString("decode", "auto"));
        if (!hwDecode) options.add("--avcodec-hw=none");
        try {
            libVLC = new LibVLC(this, options);
        } catch (Throwable e) {
            android.util.Log.w("HG.Vlc", "带参数创建失败：" + e + "，改用默认参数重试");
            libVLC = new LibVLC(this);
        }
        playerA = new MediaPlayer(libVLC);
        playerB = new MediaPlayer(libVLC);
        playerA.setEventListener(new FeedEvents(playerA));
        playerB.setEventListener(new FeedEvents(playerB));
    }

    /**
     * 某一页固定用哪个 player：按奇偶绑死（偶数页 → A，奇数页 → B）。
     *
     * 上一版是「挑一个不在当前页的」，滑相邻两集时会挑到正在播的那个，
     * 于是 stop 掉用户正在看的流 —— 表现就是「上一集还有声音，新的一集黑屏」。
     * 奇偶绑定后相邻页永远落在两个不同的 player 上，互不干扰，滑回上一集
     * 还能从暂停的位置接着播。
     */
    private MediaPlayer slotFor(int pos) {
        if (playerA == null) return null;
        return (pos % 2 == 0) ? playerA : playerB;
    }

    private int pageOf(MediaPlayer mp) {
        if (mp == null) return -1;
        return mp == playerA ? pageA : (mp == playerB ? pageB : -1);
    }

    /** 认领着这一页的 player。没认领（或认领的是别的页）就给 null。 */
    private MediaPlayer playerFor(int pos) {
        MediaPlayer mp = slotFor(pos);
        return pageOf(mp) == pos ? mp : null;
    }

    /** 播某页。同一槽位换页时先停旧流，vout 拆了再接。 */
    private void playPage(int pos, String url) {
        MediaPlayer mp = slotFor(pos);
        if (mp == null) return;
        int claimed = pageOf(mp);
        if (claimed != pos) {
            if (claimed >= 0) {
                // 这个槽位之前认领的是同奇偶的另一页（0↔2、1↔3）。必须停掉，
                // 否则 setMedia 会在旧流上叠加 —— VLC 会报 Cancellation 然后黑屏。
                try { mp.stop(); } catch (Throwable ignored) {}
            }
            try { mp.getVLCVout().detachViews(); } catch (Throwable ignored) {}
            session.clearPlayingUrl(mp);
            if (mp == playerA) pageA = pos; else pageB = pos;
        }
        // 预热已经就绪的那一页：demuxer、硬解、第一帧都在手上，play() 就能出画。
        // 这条必须排在幂等分支前面 —— 幂等分支是「暂停过的地方接着放」，
        // 预热是「从一开始放」，位置和音量都不一样。
        PageView warmPage = feedAdapter == null ? null : feedAdapter.pageAt(pos);
        boolean warmSurfaceOk = warmPage != null && warmPage.surfaceReady;
        if (pos == warmPos && pageOf(mp) == pos && url.equals(warmUrl) && warmSurfaceOk) {
            try {
                long resume = resumeOf(pos);
                if (resume > 2000 && mp.getTime() < resume - 500) mp.setTime(resume);
                if (!mp.isPlaying()) mp.play();
                mp.setVolume(100);
            } catch (Throwable ignored) {}
            awaitingFirstFrame = false;
            warmPos = -1;
            warmUrl = "";
            if (Math.abs(feedRate - 1f) > 0.01f) {
                try { mp.setRate(feedRate); } catch (Throwable ignored) {}
            }
            attachVout(pos, true);
            activeFeedPlayer = mp;
            MediaPlayer otherWarm = (mp == playerA) ? playerB : playerA;
            try { if (otherWarm.isPlaying()) otherWarm.pause(); } catch (Throwable ignored) {}
            controls.setSpinnerVisible(false);
            setControlsVisible(false);
            updateEpisodeUi();
            return;
        }
        if (pos == warmPos && !warmSurfaceOk) {
            // 暖着的那一页的 TextureView 已经被回收了，暖的那份用不上。
            warmPos = -1;
            warmUrl = "";
        }
        // 幂等：这一页就挂在这个 player 上、地址也没变。滑走又滑回来走这条。
        if (pos == claimed && url.equals(session.playingUrlOf(mp))) {
            try {
                if (!mp.isPlaying()) mp.play();
                mp.setVolume(100);
            } catch (Throwable ignored) {}
            if (Math.abs(feedRate - 1f) > 0.01f) {
                try { mp.setRate(feedRate); } catch (Throwable ignored) {}
            }
            attachVout(pos, true);
            activeFeedPlayer = mp;
            MediaPlayer otherQuiet = (mp == playerA) ? playerB : playerA;
            try { if (otherQuiet.isPlaying()) otherQuiet.pause(); } catch (Throwable ignored) {}
            controls.setSpinnerVisible(false);
            updateEpisodeUi();
            return;
        }
        Media media = new Media(libVLC, Uri.parse(relayUrl(url)));
        media.setHWDecoderEnabled(hwDecode, false);
        String referer = payload.optString("referer", "");
        String agent = payload.optString("ua", "");
        if (referer.length() > 0) media.addOption(":http-referrer=" + referer);
        if (agent.length() > 0) media.addOption(":http-user-agent=" + agent);
        // 先接 surface 再起播。反过来的话 play() 一提交，音频输出立刻起来，
        // 而视频输出还在等 attach —— 声音先到、画面后到，中间那段就是黑屏。
        // 单集模式那条路（startVlc）一直是先 attach 再 play，这里对齐。
        attachVout(pos, true);
        mp.setMedia(media);
        media.release();
        session.setPlayingUrl(mp, url);
        long resume = resumeOf(pos);
        // 先静音起播，等第一帧（Vout 事件）再把声音抬起来 —— 见 awaitingFirstFrame。
        mp.setVolume(0);
        awaitingFirstFrame = true;
        ui.removeCallbacks(unmuteRunnable);
        // 兜底给到 3 秒：--network-caching 是 1500ms，首帧常常 1.5~2 秒才来，
        // 1.5 秒就抬音量等于没等。
        ui.postDelayed(unmuteRunnable, 3000);
        mp.play();
        // play() 后立刻 setTime 会被丢（还没有 demuxer，见单集模式 startVlc 里
        // 那段注释），先记下来，等 LengthChanged 事件再落。
        pendingResume = resume;
        if (Math.abs(feedRate - 1f) > 0.01f) mp.setRate(feedRate);
        // 兜底：setMedia 之后 VLC 有可能重建 vout，而页面此时可能才布局完。
        attachVout(pos, true);
        activeFeedPlayer = mp;
        // 另一个若在响，静下来（它认领着别的页，回滑时从原地继续）
        MediaPlayer other = (mp == playerA) ? playerB : playerA;
        try { if (other.isPlaying()) other.pause(); } catch (Throwable ignored) {}
        // 转圈留到真正出帧（Playing 事件）再撤。play() 一回来就撤掉的话，
        // 用户看到的是一块黑屏，而不是「正在加载」。
        controls.setSpinnerVisible(true);
        setControlsVisible(false);
        updateEpisodeUi();
    }

    private void attachVout(int pos) {
        attachVout(pos, false);
    }

    /**
     * 把某页的 vout 接到它那页的 TextureView 上（页面没就绪就等 surface 回调再接）。
     *
     * force=true 时无论当前挂没挂都先拆再挂。VLC 在已 attach 的状态下换 view 不会
     * 真正切到新 surface 上 —— 复用出来的 PageView 换个 position 之后画面就写进
     * 了旧的那个（已回收的）surface，屏上只剩黑。
     */
    private void attachVout(int pos, boolean force) {
        final MediaPlayer mp = playerFor(pos);
        if (mp == null) return;
        PageView pv = feedAdapter == null ? null : feedAdapter.pageAt(pos);
        if (pv == null || !pv.surfaceReady) return;
        try {
            IVLCVout vout = mp.getVLCVout();
            // 幂等：同一块 TextureView 上已经挂着，就别再拆一次。
            boolean sameView = attachedTo.get(mp) == pv.texture;
            if (sameView && vout.areViewsAttached()) {
                setWindowSizeFor(mp, pv.getWidth(), pv.getHeight());
                return;
            }
            if (force) {
                try { vout.detachViews(); } catch (Throwable ignored) {}
                attachedTo.remove(mp);
            }
            vout.setVideoView(pv.texture);
            if (!vout.areViewsAttached()) vout.attachViews();
            attachedTo.put(mp, pv.texture);
            setWindowSizeFor(mp, pv.getWidth(), pv.getHeight());
        } catch (Throwable ignored) {}
    }

    /** 画面就绪了（或被兜底定时器叫醒）：把音量抬回正常。 */
    private void unmute() {
        ui.removeCallbacks(unmuteRunnable);
        if (!awaitingFirstFrame) return;
        awaitingFirstFrame = false;
        try {
            if (activeFeedPlayer != null) activeFeedPlayer.setVolume(100);
        } catch (Throwable ignored) {}
    }

    /** 除了 keepPos 那一页自己的 player，别的都暂停。 */
    private void silenceOthers(int keepPos) {
        MediaPlayer keep = slotFor(keepPos);
        MediaPlayer first = (playerA != null && playerA != keep) ? playerA : null;
        MediaPlayer second = (playerB != null && playerB != keep) ? playerB : null;
        try { if (first != null && first.isPlaying()) first.pause(); } catch (Throwable ignored) {}
        try { if (second != null && second.isPlaying()) second.pause(); } catch (Throwable ignored) {}
    }

    private void activate(int pos) {
        if (pos < 0 || pos >= eps.length) return;
        // startPlayback 会调一次，RecyclerView 首次布局的 scroll-idle 还会调一次。
        // 不去重的话第二次会对正在播的 player 再 setMedia 一遍 —— VLC 当场把
        // 输入流取消掉（日志是 Cancellation + Failed to create demuxer），黑屏。
        if (pos == curPos && playerFor(pos) != null && playerFor(pos) == activeFeedPlayer) return;
        curPos = pos;
        updateEpisodeUi();
        // 落盘不在这儿做：此刻 activeFeedPlayer 还指着上一集的 player，会写出
        // 「新集号 + 旧集位置」的记录。挪到 playPage 认领完新 player 之后（两条
        // 播放路径里各一次）。
        ui.removeCallbacks(prewarmRunnable);
        prewarmPending = -1;
        // 滑走就闭嘴：上一集的声音不能跟着新的一页继续响。地址还没到之前
        // 屏幕上什么都没有，声音却还是上一集 —— 用户只会当成卡死。
        silenceOthers(pos);
        // 滑回一集失败过的页面时要重新给机会：上一次可能只是那会儿网络抖了。
        session.clearFailed(pos);
        // 页面起播时给的地址（index 那一集）直接吃掉——它刚刚就在网页播放器里放着
        if (pos == payload.optInt("index", -1) && !session.hasUrl(pos)) {
            String seed = payload.optString("url", "");
            session.rememberUrl(pos, seed);
        }
        String url = session.urlOf(pos);
        if (url != null) {
            playPage(pos, url);
            // 到这儿 activeFeedPlayer 已经指向新集了，落盘才是自洽的记录。
            persistFeedProgress();
            preloadAround(pos);
            prewarmNeighbor(pos);
            return;
        }
        // 失败过的页不拉黑名单：AGE 那条 wasm 线路要十几秒，网络抖一下就失败，
        // 拉黑的话用户划走再回来也永远拿不到。重试交给 resolving 去重就行。
        PageView pv = feedAdapter.pageAt(pos);
        if (pv != null) { pv.spin.setVisibility(View.VISIBLE); pv.error.setVisibility(View.GONE); }
        final int target = pos;
        if (!session.beginResolve(pos)) return;   // 已经有人在取了
        new Thread(new Runnable() {
            @Override public void run() {
                final String url = resolveViaJs(eps[target]);
                ui.post(new Runnable() {
                    @Override public void run() {
                        session.endResolve(target);
                        if (url == null || url.length() == 0) {
                            session.markFailed(target);
                            PageView pv = feedAdapter.pageAt(target);
                            if (pv != null) {
                                pv.spin.setVisibility(View.GONE);
                                pv.error.setVisibility(View.VISIBLE);
                                pv.error.setText("第 " + eps[target] + " 集取不到播放地址，滑到别的集再回来试试");
                            }
                            return;
                        }
                        session.rememberUrl(target, url);
                        if (target == curPos) {
                            playPage(target, url);
                            // 同上：等 activeFeedPlayer 指向新集再落盘。
                            persistFeedProgress();
                            preloadAround(target);
                        }
                        prewarmNeighbor(curPos);
                    }
                });
            }
        }).start();
    }

    /**
     * 把用户最可能滑到的那一页先起播、静音、停在第一帧上。
     *
     * 两个 player 按奇偶绑定，所以「另一个槽位」天然空着，不用第三个 player。
     * 代价是不能同时预两个邻居 —— 它们在同一个槽位上，谁后跑谁把谁挤掉。
     * 那就按滑动方向猜：手指往上推就预下一集，往下拉就预上一集，跟用户走。
     *
     * 只有 Vout 事件真的来了才算预热完成（见 onWarmReady）。没完成就滑过去，
     * playPage 会照旧走完整重开 —— 不能拿一条没出过画的黑流当暖的。
     */
    private void prewarmNeighbor(int pos) {
        if (!feedMode) return;
        prewarmPending = pos;
        ui.removeCallbacks(prewarmRunnable);
        // 等 900ms 再动手：刚切过来的那一页正在建硬解实例，这时候再去抢一份，
        // 有的机器只给两份硬件解码器，会把当前这一页挤掉（表现是刚出的画面又黑了）。
        ui.postDelayed(prewarmRunnable, 900);
    }

    private void prewarmNeighborNow(int pos) {
        if (!feedMode) return;
        final int target = pos + warmDirection;
        if (target < 0 || target >= eps.length) return;
        // 已经暖着/正在暖的同一页不重复折腾。
        if (target == warmingPos) return;
        String url = session.urlOf(target);
        // 地址还没取回来（preload 还在排队）。等它回来会再叫一次这个方法。
        if (url == null || url.length() == 0) return;
        if (target == warmPos && url.equals(warmUrl)) return;
        MediaPlayer mp = slotFor(target);
        if (mp == null || mp == activeFeedPlayer) return;
        // 这个槽位本来就停在目标页上（刚从那儿滑过来、被 pause 了）—— 它比新起
        // 一条更暖：解复用、缓冲、连最后一帧都还在。别再 restart 一遍，那样反而
        // 把进度和缓冲全扔掉，来回滑一下就要重开两次。
        if (pageOf(mp) == target && url.equals(session.playingUrlOf(mp))) {
            warmingPos = -1;
            warmPos = target;
            warmUrl = url;
            return;
        }
        PageView pv = feedAdapter == null ? null : feedAdapter.pageAt(target);
        // 没有可用的 TextureView 就挂不上 vout，起播只会得到一个浮空窗口。
        if (pv == null || !pv.surfaceReady) return;

        // 这个槽位可能还认领着同奇偶的旧页（隔了两页以上），停掉换人。
        try { mp.stop(); } catch (Throwable ignored) {}
        try { mp.getVLCVout().detachViews(); } catch (Throwable ignored) {}
        attachedTo.remove(mp);
        session.clearPlayingUrl(mp);
        warmPos = -1;
        warmUrl = "";
        if (mp == playerA) pageA = target; else pageB = target;

        Media media = new Media(libVLC, Uri.parse(relayUrl(url)));
        media.setHWDecoderEnabled(hwDecode, false);
        String referer = payload.optString("referer", "");
        String agent = payload.optString("ua", "");
        if (referer.length() > 0) media.addOption(":http-referrer=" + referer);
        if (agent.length() > 0) media.addOption(":http-user-agent=" + agent);
        mp.setMedia(media);
        media.release();
        session.setPlayingUrl(mp, url);
        attachVout(target, true);
        mp.setVolume(0);
        warmingPos = target;
        try {
            mp.play();
        } catch (Throwable e) {
            warmingPos = -1;
            return;
        }
        // 长时间等不到 Vout（线路慢、或者这条流就是没有视频轨）就把「正在预热」
        // 的标记撤掉，别把这一页永久占住 —— 将来再滑到这儿还能重新试。
        final int pending = target;
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (warmingPos == pending) warmingPos = -1;
            }
        }, 6000);
        android.util.Log.d("HG.Vlc", "预热第 " + eps[target] + " 集（方向 " + warmDirection + "）");
    }

    /**
     * 预热出第一帧了：停在这一帧上，把声音继续压着。
     *
     * 停住而不是让它放，是为了不跟正在看的这一集抢解码和带宽；seek 到这一集
     * 自己的续播点会让 VLC 在暂停态下把那一帧解出来 —— 正好就是滑过去要的第一帧。
     */
    private void onWarmReady(int pos) {
        if (pos != warmingPos) return;
        warmingPos = -1;
        MediaPlayer mp = slotFor(pos);
        if (mp == null || pageOf(mp) != pos) return;
        try {
            long resume = resumeOf(pos);
            mp.setTime(resume > 2000 ? resume : 0);
            mp.pause();
            mp.setVolume(0);
        } catch (Throwable ignored) {}
        warmPos = pos;
        String url = session.playingUrlOf(mp);
        warmUrl = url == null ? "" : url;
        PageView pv = feedAdapter == null ? null : feedAdapter.pageAt(pos);
        if (pv != null) pv.spin.setVisibility(View.GONE);
        android.util.Log.d("HG.Vlc", "预热就绪：第 " + eps[pos] + " 集");
    }

    /** 相邻两集的地址先要回来（只握手，不起播），滑动出去时基本是即时的。 */
    private void preloadAround(int pos) {
        preload(pos + 1);
        preload(pos - 1);
    }

    private void preload(final int pos) {
        if (pos < 0 || pos >= eps.length || session.hasUrl(pos) || session.isResolving(pos)
                || session.hasFailed(pos)) return;
        session.beginResolve(pos);
        new Thread(new Runnable() {
            @Override public void run() {
                final String url = resolveViaJs(eps[pos]);
                ui.post(new Runnable() {
                    @Override public void run() {
                        session.endResolve(pos);
                        if (url != null && url.length() > 0) {
                            session.rememberUrl(pos, url);
                            // 用户可能在预取排队期间就滑到了这一页 —— 那时 activate
                            // 看到 resolving 里已有它就直接返回了，没人负责起播，
                            // 结果就是画面停在上一集、声音也还是上一集。这里补上。
                            if (pos == curPos) {
                                playPage(pos, url);
                                preloadAround(pos);
                            }
                            // 预取回来的地址可能正好是用户要滑向的那一页，趁热接上。
                            prewarmNeighbor(curPos);
                        } else if (pos == curPos) {
                            PageView pv = feedAdapter == null ? null : feedAdapter.pageAt(pos);
                            if (pv != null) {
                                pv.spin.setVisibility(View.GONE);
                                pv.error.setVisibility(View.VISIBLE);
                                pv.error.setText("第 " + eps[pos] + " 集取不到播放地址，滑到别的集再回来试试");
                            }
                        }
                    }
                });
            }
        }).start();
    }

    /**
     * 找页面要播放地址。解密、wasm、多线路全在页面那头，这里只发一个问题。
     *
     * 链路：反射拿 MainActivity.host 的 webView（同一个进程，字段没混淆），
     * evaluateJavascript 调 window.__hgNativeEpisode(id, ep)，拿回一个 URL 字符串。
     * 主线程才能调 evaluateJavascript，所以在主线程发、用闩锁等结果。
     */
    private String resolveViaJs(int ep) {
        // 页面那个口子是同步的：有缓存当场给，没有就回去发起解析、返回空串。
        // 所以这里轮询——当前集一般第一击就中（网页播放器刚播过它），
        // 预取的邻集要等页面解析完，AGE 那条 wasm 线路最慢十几秒。
        // 45 次 × 800ms ≈ 36 秒：宁可多等一会儿，也别让用户滑到一页看到「取不到地址」。
        //
        // 这段包装脚本是关键：页面那个 hook 有两种历史实现 —— 同步的（直接给字符串）
        // 和 async 的（evaluateJavascript 收到的是 "{}"）。这里两种都认：
        // 同步的直接用，Promise 的挂一个 then 把结果写进 __hgNativeEps 表，
        // 下一轮从这里读。热更新还没下发到位的机器也能正常取地址。
        final String idLit = JSONObject.quote(payload.optString("id", ""));
        final String script =
                "(function(){"
                + "var t = window.__hgNativeEps || (window.__hgNativeEps = {});"
                + "var k = " + idLit + " + ':" + ep + "';"
                + "if (t[k]) return t[k];"
                + "try{"
                + "var h = window.__hgNativeEpisode && window.__hgNativeEpisode(" + idLit + "," + ep + ");"
                + "if (typeof h === 'string' && h) { t[k] = h; return h; }"
                + "if (h && typeof h.then === 'function') { h.then(function(u){ if (u) t[k] = u; }, function(){}); }"
                + "}catch(e){}"
                + "return '';"
                + "})()";
        for (int attempt = 0; attempt < 45; attempt++) {
            String url = askPageOnce(script);
            if (url != null && url.length() > 0) return url;
            try { Thread.sleep(800); } catch (InterruptedException e) { return null; }
        }
        return null;
    }

    /** 在页面里跑一段同步脚本，拿回字符串（没有/失败都是空串）。 */
    private String askPageOnce(final String script) {
        try {
            Object host = MainActivity.host;
            if (host == null) return "";
            // 字段是 private，getField 拿不到 —— 得 getDeclaredField + setAccessible，
            // 而且要沿父类链找。
            java.lang.reflect.Field field = null;
            for (Class<?> type = host.getClass(); type != null && field == null; type = type.getSuperclass()) {
                try { field = type.getDeclaredField("webView"); } catch (NoSuchFieldException ignored) {}
            }
            if (field == null) return "";
            field.setAccessible(true);
            final Object webView = field.get(host);
            if (!(webView instanceof android.webkit.WebView)) return "";
            final java.util.concurrent.SynchronousQueue<String> lane =
                    new java.util.concurrent.SynchronousQueue<String>();
            ui.post(new Runnable() {
                @Override public void run() {
                    try {
                        ((android.webkit.WebView) webView).evaluateJavascript(script,
                                new android.webkit.ValueCallback<String>() {
                                    @Override public void onReceiveValue(String value) {
                                        try { lane.put(value == null ? "" : value); } catch (Throwable ignored) {}
                                    }
                                });
                    } catch (Throwable e) {
                        try { lane.put(""); } catch (Throwable ignored) {}
                    }
                }
            });
            String raw = lane.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            if (raw == null || raw.length() < 2) return "";
            Object value = new org.json.JSONTokener(raw.trim()).nextValue();
            return value instanceof String ? (String) value : "";
        } catch (Throwable e) {
            android.util.Log.w("HG.Vlc", "问页面要地址失败：" + e);
            return "";
        }
    }

    private long resumeOf(int pos) {
        if (pos == payload.optInt("index", 0)) return startedAt;
        return 0;
    }

    private long feedTimeOf(int pos) {
        MediaPlayer mp = playerFor(pos);
        return mp == null ? 0 : Math.max(0, mp.getTime());
    }

    private long feedDurationOf(int pos) {
        MediaPlayer mp = playerFor(pos);
        return mp == null ? 0 : mp.getLength();
    }

    private void updateEpisodeUi() {
        controls.setTitle(payload.optString("title", "播放中") + " · 第 " + eps[curPos] + " 集");
        controls.setMeta((hwDecode ? "硬解优先" : "软解") + " · " + abi()
                + " · " + (curPos + 1) + "/" + eps.length);
    }

    /** 把当前这一集的现场写进 prefs。切集和退出都会调。 */
    private void persistFeedProgress() {
        try {
            MediaPlayer mp = activeFeedPlayer;
            long position = mp == null ? 0 : Math.max(0, mp.getTime());
            long duration = mp == null ? 0 : mp.getLength();
            JSONObject record = new JSONObject();
            String cached = session.urlOf(curPos);
            record.put("url", cached != null ? cached : payload.optString("url", ""));
            record.put("id", payload.optString("id", ""));
            record.put("episode", eps[curPos]);
            record.put("title", payload.optString("title", ""));
            record.put("cover", payload.optString("cover", ""));
            record.put("source", payload.optString("source", ""));
            record.put("position", position);
            record.put("duration", duration);
            record.put("finished", ended);
            record.put("error", errorText);
            record.put("at", System.currentTimeMillis());
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_LAST, record.toString()).apply();
        } catch (Throwable ignored) {}
    }

    private void releaseFeedPlayers() {
        // 逐个释放，每个动作单独兜异常的旧写法有坑：playerA.stop() 或
        // detachViews() 只要抛一次，整个 try 就跳出去，playerB 再也不会被释放 ——
        // 表现就是「退出播放器了声音还在放」。
        releaseOne(playerA);
        releaseOne(playerB);
        // 兜底再清一次：releaseOne 已逐条 remove，这里 clear 保证不留任何旧
        // player 的钉子 —— 不然 onNewIntent 换集后 attachVout 的幂等判断
        // 拿旧条目认错 view，新画面挂不到正确的 TextureView 上。
        attachedTo.clear();
        playerA = null;
        playerB = null;
        pageA = pageB = -1;
        activeFeedPlayer = null;
        session.setWasPlaying(false);
        session.forgetPlayers();
    }

    /** 彻底关掉一个 player：先静音再停，stop 之后 VLC 还会吐几百毫秒的尾巴。 */
    private void releaseOne(MediaPlayer mp) {
        if (mp == null) return;
        try { mp.setVolume(0); } catch (Throwable ignored) {}
        try { mp.setEventListener(null); } catch (Throwable ignored) {}
        try { mp.stop(); } catch (Throwable ignored) {}
        try { mp.getVLCVout().detachViews(); } catch (Throwable ignored) {}
        // attachedTo 里这条也得跟着清：player 释放后 map 还钉着它，下次同槽位
        // 复用时 attachVout 的幂等判断（attachedTo.get(mp) == pv.texture）会对着
        // 已释放的对象认错 view。
        attachedTo.remove(mp);
        try { mp.release(); } catch (Throwable ignored) {}
    }

    /** 每个轮转 player 一份事件。只有「当前中心」那份会动 UI。 */
    private class FeedEvents implements MediaPlayer.EventListener {
        private final MediaPlayer mp;
        FeedEvents(MediaPlayer mp) { this.mp = mp; }

        @Override public void onEvent(final MediaPlayer.Event event) {
            ui.post(new Runnable() {
                @Override public void run() { handleFeedEvent(mp, event); }
            });
        }
    }

    private void handleFeedEvent(MediaPlayer mp, MediaPlayer.Event event) {
        if (mp == null) return;
        // 预热中的那个 player 不是 activeFeedPlayer，但它报的 Vout 正是「画面
        // 能出来了」的信号 —— 先在这儿收下，再交给正式播放那一套。
        if (mp != activeFeedPlayer) {
            // 只认 Vout。Playing 在 play() 之后立刻就来，那时一帧都还没解，
            // 拿它当「画面就绪」等于把黑屏提前钉死。
            if (event.type == MediaPlayer.Event.Vout) onWarmReady(pageOf(mp));
            return;
        }
        final int type = event.type;
        if (type == MediaPlayer.Event.LengthChanged) {
            durationMs = mp.getLength();
            // demuxer 就绪了，把 playPage 欠下的续播点补上；这时 setTime 才不会丢。
            if (pendingResume > 2000) {
                try { mp.setTime(pendingResume); } catch (Throwable ignored) {}
            }
            pendingResume = 0;
        } else if (type == MediaPlayer.Event.TimeChanged) {
            long time = mp.getTime();
            if (durationMs <= 0) durationMs = mp.getLength();
            controls.setClock(fmt(time) + " / " + (durationMs > 0 ? fmt(durationMs) : "--:--"));
            if (durationMs > 0 && !controls.seekBar.isPressed()) {
                controls.seekBar.setProgress((int) Math.min(1000, time * 1000 / durationMs));
            }
            describeFeedTrack(mp);
        } else if (type == MediaPlayer.Event.Playing) {
            PageView pv = feedAdapter != null ? feedAdapter.pageAt(curPos) : null;
            if (pv != null) pv.spin.setVisibility(View.GONE);
            // 出帧了才撤转圈。playPage 里不再提前撤，否则等 vout 的那几百毫秒
            // 是一块黑屏 —— 声音已经到了，画面还没到，看着像卡死。
            controls.setSpinnerVisible(false);
            controls.setPlayIcon(VlcControlsView.IconButton.PAUSE);
            // 起播成功 → 换线游标归零。下一次报错还从第一条备选试起。
            session.resetAlt(curPos);
            describeFeedTrack(mp);
            ui.postDelayed(hideControls, HIDE_CONTROLS_MS);
        } else if (type == MediaPlayer.Event.Paused) {
            controls.setPlayIcon(VlcControlsView.IconButton.PLAY);
            setControlsVisible(true);
        } else if (type == MediaPlayer.Event.EndReached) {
            ended = true;
            sawEnd = true;
            persistFeedProgress();
            ended = false;
            // 抖音流的规矩：播完自动滑到下一集
            if (curPos + 1 < eps.length) {
                pager.smoothScrollToPosition(curPos + 1);
            } else {
                controls.setPlayIcon(VlcControlsView.IconButton.PLAY);
                setControlsVisible(true);
            }
        } else if (type == MediaPlayer.Event.Vout) {
            // 视频输出就绪：这才是「画面能出来了」的时刻，把声音放开。
            unmute();
            reportFirstFrame();
        } else if (type == MediaPlayer.Event.EncounteredError) {
            controls.setSpinnerVisible(false);
            tryNextAlt();
        }
    }

    /**
     * 这一页的备选线路。
     *
     * 只有**起播那一集**带 —— 页面把适配器排好序的 lines 去掉第一条之后塞进
     * payload.alts（feed-player.js 那边写着「内置播放器在 EncounteredError 时
     * 按这个列表换线重试」）。别的集是滑动时现取的，没有备选，返回空表。
     */
    private String[] altsOf(int pos) {
        if (pos != payload.optInt("index", -1)) return NO_ALTS;
        org.json.JSONArray raw = payload.optJSONArray("alts");
        if (raw == null || raw.length() == 0) return NO_ALTS;
        ArrayList<String> out = new ArrayList<String>();
        for (int i = 0; i < raw.length(); i++) {
            // 只滤空串，不滤「和当前那条一样」的 —— 数组必须每次调用都一模一样长，
            // 否则会话里那个「试到第几条」的游标会跟着数组一起缩，第二条就提前认输。
            String line = raw.optString(i, "");
            if (line.length() > 0) out.add(line);
        }
        return out.isEmpty() ? NO_ALTS : out.toArray(new String[0]);
    }

    /**
     * 内置播放器解不了这一条线时换代选线路。用完才认输。
     *
     * 这条兜底不依赖任何探测或缓存判断，是整条链路上唯一永远成立的机制 ——
     * 前面那些（首条线路、预取、缓存命中）都可能因为站点抽风而全线失效，
     * 只有「这条放不了就换下一条」不需要任何假设。
     *
     * 游标在 {@link VlcSession#takeNextAlt} 里，每调一次前进一格；成功起播时
     * 由 Playing 事件清零，所以「换过一条能放」之后下一次报错还是从第一条试起。
     */
    private void tryNextAlt() {
        String[] alts = altsOf(curPos);
        int next = session.takeNextAlt(curPos, alts.length);
        if (next < 0) {
            PageView pv = feedAdapter == null ? null : feedAdapter.pageAt(curPos);
            if (pv != null) {
                pv.spin.setVisibility(View.GONE);
                pv.error.setVisibility(View.VISIBLE);
                pv.error.setText("第 " + eps[curPos] + " 集" + playbackFailureText());
            }
            // feed 模式不走 showError（每一页有自己的错误文案），所以单独报一条。
            reportPlayFailure("alts-exhausted", "EncounteredError",
                    "第 " + eps[curPos] + " 集" + playbackFailureText());
            return;
        }
        // 幂等判断认的是「这个 player 上挂着哪条地址」，所以换代选之前必须先把
        // 旧的那条抹掉，否则 playPage 会认为「地址没变」而直接 play 旧流。
        MediaPlayer mp = playerFor(curPos);
        if (mp != null) session.clearPlayingUrl(mp);
        session.rememberUrl(curPos, alts[next]);
        android.util.Log.i("HG.Vlc", "换线：第 " + eps[curPos] + " 集 → 备选 " + (next + 1) + "/" + alts.length);
        playPage(curPos, alts[next]);
    }

    private void describeFeedTrack(MediaPlayer mp) {
        try {
            IMedia.VideoTrack track = mp.getCurrentVideoTrack();
            if (track == null) return;
            String codec = track.codec == null ? "" : track.codec.toUpperCase(Locale.US);
            controls.setMeta(track.width + "×" + track.height
                    + (codec.length() > 0 ? " " + codec : "")
                    + " · " + (hwDecode ? "硬解优先" : "软解")
                    + " · 第 " + eps[curPos] + " 集");
        } catch (Throwable ignored) {}
    }

    private int centeredPosition() {
        if (pagerLayout == null) return -1;
        int first = pagerLayout.findFirstVisibleItemPosition();
        int last = pagerLayout.findLastVisibleItemPosition();
        if (first < 0 || last < 0) return -1;
        int best = -1;
        long bestArea = -1;
        android.graphics.Rect rect = new android.graphics.Rect();
        for (int i = first; i <= last; i++) {
            View view = pagerLayout.findViewByPosition(i);
            if (view == null) continue;
            view.getHitRect(rect);
            long area = (long) Math.max(0, rect.height()) * Math.max(0, rect.width());
            if (area > bestArea) { bestArea = area; best = i; }
        }
        return best;
    }

    /** 一页 = 一块全屏 TextureView + 取地址时的转圈 + 出错时的一行字。 */
    private class PageView extends FrameLayout implements TextureView.SurfaceTextureListener {
        final TextureView texture;
        final ProgressBar spin;
        final TextView error;
        int pos = -1;
        boolean surfaceReady;

        PageView(Context context) {
            super(context);
            setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            texture = new TextureView(context);
            texture.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            texture.setSurfaceTextureListener(this);
            addView(texture);

            spin = new ProgressBar(context);
            LayoutParams spinParams = new LayoutParams(dp(36), dp(36), Gravity.CENTER);
            spin.setLayoutParams(spinParams);
            spin.setVisibility(View.GONE);
            addView(spin);

            error = new TextView(context);
            error.setTextColor(0xFFE9E9EC);
            error.setTextSize(13f);
            error.setGravity(Gravity.CENTER);
            error.setPadding(dp(24), dp(24), dp(24), dp(24));
            error.setVisibility(View.GONE);
            addView(error, new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER));
        }

        @Override public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture st, int w, int h) {
            surfaceReady = true;
            if (pos == curPos) attachVout(pos, true);
        }

        @Override public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture st, int w, int h) {
            // 旋转时只 updateVideoSurfaces 不够 —— SurfaceTexture 的缓冲还停在
            // 旧尺寸，画面就按旧几何塞在新屏的左上角。强制拆掉重挂一次，
            // 延迟一拍等布局落定。
            ui.postDelayed(new Runnable() {
                @Override public void run() {
                    if (pos != curPos) return;
                    MediaPlayer mp = playerFor(pos);
                    if (mp == null) return;
                    try { mp.getVLCVout().detachViews(); } catch (Throwable ignored) {}
                    attachVout(pos);
                }
            }, 160);
        }

        @Override public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture st) {
            surfaceReady = false;
            MediaPlayer mp = playerFor(pos);
            if (mp != null) {
                try { mp.getVLCVout().detachViews(); } catch (Throwable ignored) {}
                attachedTo.remove(mp);
            }
            return true;
        }

        @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st) {}
    }

    private class FeedAdapter extends RecyclerView.Adapter<FeedViewHolder> {
        final Map<Integer, PageView> live = new HashMap<Integer, PageView>();

        @Override public FeedViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new FeedViewHolder(new PageView(parent.getContext()));
        }

        @Override public void onBindViewHolder(FeedViewHolder holder, int position) {
            PageView pv = holder.page;
            pv.pos = position;
            pv.spin.setVisibility(View.GONE);
            pv.error.setVisibility(View.GONE);
            pv.texture.setSurfaceTextureListener(pv);
            // 复用的 View 上 SurfaceTexture 已经存在，setSurfaceTextureListener
            // 不会再回调 onSurfaceTextureAvailable。不在这儿补一手的话
            // surfaceReady 永远是 false，attachVout 次次跳过 —— 黑屏。
            pv.surfaceReady = pv.texture.isAvailable();
            live.put(position, pv);
            if (pv.surfaceReady && position == curPos && playerFor(position) != null) {
                attachVout(position, true);
            }
        }

        @Override public void onViewRecycled(FeedViewHolder holder) {
            int pos = holder.page.pos;
            live.remove(pos);
            MediaPlayer mp = playerFor(pos);
            if (mp != null) {
                try { mp.getVLCVout().detachViews(); } catch (Throwable ignored) {}
            }
            holder.page.surfaceReady = false;
        }

        @Override public int getItemCount() { return eps.length; }

        PageView pageAt(int pos) {
            PageView pv = live.get(pos);
            if (pv != null && pv.pos == pos) return pv;
            // findViewByPosition 兜底：live 表可能被回收时序打乱
            View view = pagerLayout == null ? null : pagerLayout.findViewByPosition(pos);
            return view instanceof PageView ? (PageView) view : null;
        }
    }

    private static class FeedViewHolder extends RecyclerView.ViewHolder {
        final PageView page;
        FeedViewHolder(PageView page) {
            super(page);
            this.page = page;
        }
    }

}
