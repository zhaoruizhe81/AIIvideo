package com.huangguo.mobile;

import android.content.Context;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Random;
import libv2ray.CoreCallbackHandler;
import libv2ray.CoreController;
import libv2ray.Libv2ray;

/**
 * AIIvideo —— 本地代理核心（libv2ray 的 Xray）。
 *
 * 做的事情只有一件：在本机 127.0.0.1 上开一个 SOCKS5，把挑好的节点接上去。
 * 不建 VpnService、不改系统路由、不碰 iptables —— 需要走代理的组件
 * （OkHttp 走 AiiHttp 的 proxy 设置、libVLC 走它的 socks 选项）各自连过来。
 * 这样只有我们明确让它走的流量才出去，装了什么别的 App 都不受影响。
 *
 * ── 为什么不用 VpnService ────────────────────────────────────────
 * VpnService 是全局的：一旦起来，整台手机的流量都跟着走，用户关掉 App 之前
 * 一直挂着。而这个应用的代理需求只是「取数据 + 播放」两条链路，走全局等于
 * 给所有 App 增加故障面。代价是别的 App 拿不到这条代理 —— 那是本意。
 *
 * ── 生命周期 ─────────────────────────────────────────────────────
 *   ensureEnv(context)   进程内一次，加载 .so 并告诉核心去哪里找 geoip.dat
 *   start(node)          选端口 → 生成配置 → startLoop；返回能否起来
 *   stop()               stopLoop 并释放
 *
 * 全部方法都可重入：连点两下「开启」不会起第二个核心，只会把上一次的结论返回。
 *
 * ⚠ 真机验证留给阶段 4 —— 这里的 native 调用在 JVM 单测里跑不了（.so 只有
 *   arm64-v8a），所以 XrayConfig 被拆出去做纯 JVM 断言，这个类只做接线。
 */
public final class ProxyCore {

    /** 本地端口候选区间。避开 1080（v2rayNG 的默认，会和用户装的其他客户端撞）。 */
    static final int PORT_MIN = 20000;
    static final int PORT_MAX = 40000;

    private static final int PROBE_ATTEMPTS = 24;

    /**
     * 连通性测试 URL。204 最轻，不产生响应体。
     *
     * <p><b>别换回 {@code www.gstatic.com}。</b> 那个域在 geosite 的 cn 分类里
     * （和 {@code gstatic.cn} 同一组），配上生产 routing 的 {@code geosite:cn →
     * direct} 会被截去直连 —— 国内直连 gstatic 是通的（联通缓存，204/0.21s），
     * 于是测速对**任何**节点都报同一个几十毫秒的绿灯。2026-10-31 真机踩过：
     * 8 个节点 6 个报 84~88ms，切过去全 {@code 404}。
     *
     * <p>{@code google.com} 不在 geosite:cn，国内直连是不通的 —— 这样即使哪天
     * 分流规则又漏回来，表现也是「测不通」而不是「假的通」。
     */
    static final String PROBE_URL = "https://www.google.com/generate_204";

    private static final String TAG = "AIIvideo";

    /** 进程内单例。核心的 .so 是进程级的，多开没有意义。 */
    private static volatile ProxyCore instance;

    private static volatile boolean envReady = false;
    private static volatile String envError = null;

    private final Context appContext;
    private CoreController controller;
    private volatile int port;
    private volatile XrayConfig.Node currentNode;
    private volatile String lastError;

    private ProxyCore(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public static ProxyCore get(Context context) {
        ProxyCore local = instance;
        if (local != null) return local;
        synchronized (ProxyCore.class) {
            if (instance == null) instance = new ProxyCore(context);
            return instance;
        }
    }

    // ── 环境 ──────────────────────────────────────────────────────────

    /**
     * 初始化核心环境。幂等，失败也不会反复重试 —— 缺 .so 的机器（32 位）
     * 重试一百次还是缺，把结论记下来给 UI 一句话就够了。
     *
     * @return 是否可用
     */
    public boolean ensureEnv() {
        if (envReady) return true;
        if (envError != null) return false;
        synchronized (ProxyCore.class) {
            if (envReady) return true;
            if (envError != null) return false;
            try {
                // checkVersionX 是最轻量的探活：它只有 1 行，但会触发 Libv2ray 的
                // 静态块 → Seq.touch() → System.loadLibrary("gojni")。
                // 换成一个真正的 startLoop 才能发现的问题，这里没必要。
                String version = Libv2ray.checkVersionX();
                // 两个参数的实际作用（2026-10-18 查 libgojni.so 的符号确认）：
                //
                // envPath：数据目录的父目录（/data/data/<pkg>）。它为 nil 时核心会退化，
                //   所以必须传。但 **geoip.dat / geosite.dat 不走这个路径** —— libgojni.so
                //   里有 AAssetManager_open / AAsset_read 这套 Android asset API，
                //   那两个文件是 AAR 放进 assets/ 后由它直接读 APK 的。
                //   改 envPath 不会让 geoip 找不到，别在这上面浪费时间排查。
                //
                // key：XUDP 的 base key，传空串即不启用。我们不用 XUDP。
                // go.Seq.setContext 必须在 initCoreEnv 之前调（2026-10-19）：
                // gomobile 的 asset 包读 geoip.dat / geosite.dat 前要 JVM context，
                // 没注册就直接 `asset: no current JVM` → Go 里 os.Exit(1) → 整个
                // 主进程无声消失（现象 = 点图标闪退，无 Java 崩溃栈）。v2rayNG
                // 同款调用。旧配置从未引用 geosite，这条死路是 routing 接入
                // geosite:cn（方案 B）后才第一次被踩到。
                go.Seq.setContext(appContext);
                Libv2ray.initCoreEnv(appContext.getFilesDir().getParent(), "");
                envReady = true;
                log("核心就绪 " + version);
                return true;
            } catch (Throwable e) {
                envError = shortOf(e);
                log("核心不可用（多半是这台机器没有 arm64 的 libgojni.so）：" + envError);
                return false;
            }
        }
    }

    /** available 给人看的结论，null 表示可用。 */
    public static String unavailableReason() {
        return envError;
    }

    // ── 启停 ──────────────────────────────────────────────────────────

    /**
     * 换源之后重建分流表。
     *
     * <p><b>为什么需要这个</b>（2026-10-20 真机，黄果 62 秒超时）：核心的 routing 是
     * {@link XrayConfig#buildString} 在<b>启动那一刻</b>固化的，里面抄的是当时的
     * {@code NetRoute.declaredProxyHosts()}。而用户是「先连代理、后切源」——
     * 切源只重建 Activity，进程不死、核心也不重启，于是核心那份表里根本没有新源的
     * 声明，落到了 DirectHosts 那条 direct 规则上。
     *
     * <p>结果是一条请求被两边判成相反的方向：App 侧说「这个域走代理」（把它塞进
     * SOCKS），核心侧说「这个域直连」（把它交给运营商网络）—— 日志里 Xray 那行
     * {@code accepted tcp:host:443 [direct]} 就是证据。用户看到的是转 60 秒然后失败。
     *
     * <p>不能靠 {@link #start} 自己发现：同一个节点重复调用会在
     * {@code sameNode} 那一行直接 return，配置永远不重建。
     */
    synchronized void reapplyRouting() {
        if (!isRunning() || currentNode == null) return;
        XrayConfig.Node node = copy(currentNode);
        // stop 之后 isRunning() 为 false，再 start 就一定会重新 buildString ——
        // 这正是我们要的：拿新的 declaredProxyHosts 重新生成一遍 routing。
        stop();
        start(node);
    }

    /**
     * 用给定节点起核心。同一个节点重复调用是空操作。
     *
     * @return 起来了（或本来就在跑）返回 true
     */
    public synchronized boolean start(XrayConfig.Node node) {
        if (node == null) {
            lastError = "没有可用节点";
            return false;
        }
        if (!ensureEnv()) {
            lastError = "核心不可用：" + envError;
            return false;
        }
        if (isRunning() && sameNode(node, currentNode)) return true;
        if (isRunning()) stop();

        int chosen = 0;
        CoreController pending = null;
        try {
            chosen = pickPort();
            String config = XrayConfig.buildString(node, chosen);
            pending = new CoreController(new Handler());
            // startLoop 是同步的：它把配置交给核心并起 goroutine，配置不合法会抛。
            // 所以「抛了」等于「没起来」，不用再查 getIsRunning()。
            pending.startLoop(config);
            controller = pending;
            port = chosen;
            currentNode = copy(node);
            lastError = null;
            // startLoop 返回 ≠ 本地 SOCKS 入站已经在 listen。它只是把配置交给核心、
            // 起 goroutine，监听是随后在 native 那边做的。
            //
            // 2026-10-21 真机：第一次安装，填完节点点「保存并启动」，紧接着去拉一个
            // 必须走代理的源，报拉取失败；重启一次 App 就好了 —— 因为重启后核心在
            // 启动链里已经跑了十几秒。差别就是这段时间差：第一个请求撞在还没 listen
            // 的端口上，connect refused，AiiHttp 按「这条路上不去」报错。
            // 等到端口真的能连上再返回，代价是几百毫秒，比让用户「重启一下」便宜。
            boolean listening = awaitListening(chosen, 2500);
            log("已启动 " + node.describe() + " → socks5://127.0.0.1:" + chosen
                    + (listening ? "" : "（2.5 秒内没等到两个入站，先放行）"));
            return true;
        } catch (Throwable e) {
            // 只把 controller 置空是**不够的**：CoreController 在 native 那边登记了一个
            // Go 对象（refnum + trackGoRef），不主动 stopLoop 就永远不会被回收。
            // 用户「点开 → 失败 → 改配置 → 再点」来几轮，进程里就攒下几个僵尸核心，
            // 每个都还占着自己的 goroutine 和内存，而 Java 这边已经没人能碰到它们。
            //
            // stopLoop 必须是「尽力」：配置非法时它本身也可能抛，但那不能掩盖
            // 真正的错误原因 —— lastError 记的永远是启动失败的那条，不是清理失败。
            releaseQuietly(pending);
            controller = null;
            port = 0;
            currentNode = null;
            lastError = shortOf(e);
            log("启动失败（端口 " + chosen + "）：" + lastError);
            return false;
        }
    }


    /**
     * 等本地入站真的能连上。
     *
     * <p>判据只能是「连得上」：端口被占、核心半死、goroutine 还没跑到监听点，
     * 这三种在 Java 侧看不到区别，只有 connect 能证伪。
     *
     * <p>配置里起了两个相邻入站（SOCKS 在 port、HTTP 在 port+1，见
     * XrayConfig.build），两个都要探：只探 SOCKS 的话，HTTP 那个被抢或没起来，
     * WebView 会拿到一个连不上的代理地址还无人察觉。
     *
     * @return 超时前两个都连上返回 true
     */
    private static boolean awaitListening(int port, long timeoutMs) {
        long began = System.currentTimeMillis();
        while (System.currentTimeMillis() - began < timeoutMs) {
            boolean allUp = true;
            for (int p : new int[] {port, port + 1}) {
                java.net.Socket probe = new java.net.Socket();
                try {
                    probe.connect(new java.net.InetSocketAddress("127.0.0.1", p), 150);
                } catch (Throwable ignored) {
                    allUp = false;
                    try {
                        Thread.sleep(60);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                    break;
                } finally {
                    try { probe.close(); } catch (Throwable ignored) {}
                }
            }
            if (allUp) return true;
        }
        return false;
    }

    /** 丢掉一个没起来的 controller。失败只记日志 —— 清理失败不该盖住调用方要看的错因。 */
    private static void releaseQuietly(CoreController dying) {
        if (dying == null) return;
        try {
            dying.stopLoop();
        } catch (Throwable e) {
            log("清理半死的核心时异常：" + shortOf(e));
        }
    }

    /** 停。没跑的时候调也没关系 —— 阶段 4 的「关闭代理」按钮不需要先问状态。 */
    public synchronized void stop() {
        CoreController dying = controller;
        // 先把状态清干净再停 —— 反过来的话，stopLoop 走到一半抛异常时
        // 界面读到的还是「在跑」，而实际上核心已经半死不活了。
        controller = null;
        port = 0;
        currentNode = null;
        if (dying == null) return;
        releaseQuietly(dying);
        log("已停止");
    }

    // ── 状态 ──────────────────────────────────────────────────────────

    /**
     * 核心是否在跑。
     *
     * 同时问两处：本地有没有 controller，以及 native 那边的 getIsRunning()。
     * 只信本地字段的话，核心自己崩了（panic 之后 goroutine 全退）这里还报 true，
     * 阶段 4 的 UI 就会显示「已连接」而实际所有请求都在超时。
     */
    public boolean isRunning() {
        CoreController current = controller;
        if (current == null) return false;
        try {
            return current.getIsRunning();
        } catch (Throwable e) {
            return false;
        }
    }

    /** 当前本地 SOCKS5 端口，0 表示没跑。 */
    public int port() {
        return isRunning() ? port : 0;
    }

    /**
     * 本地 HTTP 入站端口（= SOCKS 端口 + 1），0 表示没跑。
     *
     * <p>存在的唯一理由是 WebView：它的 {@code ProxyController} 对 socks5 不可靠。
     * 两个入站是同一份配置里一起起的（见 XrayConfig.build），所以端口必然相邻。
     */
    public int httpPort() {
        return isRunning() ? port + 1 : 0;
    }

    /**
     * 喂给 AiiHttp.setProxy 的形态。没跑返回空串 —— 空串在 AiiHttp 里就是直连，
     * 正好是「代理关掉」该有的语义，调用方不用写 if。
     */
    public String socksUrl() {
        int live = port();
        return live <= 0 ? "" : "socks5://127.0.0.1:" + live;
    }

    /**
     * 喂给 libVLC 的 `:socks=` 的形态，即 `127.0.0.1:<port>`。
     *
     * **libVLC 不接受 scheme**：`:socks=socks5://127.0.0.1:23456` 会被它当成
     * 一个叫 `socks5` 的主机名去解析，然后连接超时，报错信息里看不出是这个原因
     * （表现只是「播不了」，和真正被墙一模一样）。所以这个剥离必须有一处权威实现，
     * 别让调用方各自拼字符串。
     *
     * 没跑返回空串，调用方判 `length() > 0` 即可。
     */
    public String socksHostPort() {
        int live = port();
        return live <= 0 ? "" : "127.0.0.1:" + live;
    }

    /** 上一次失败的原因，null 表示没失败过。给 UI 一句话。 */
    public String lastError() {
        return lastError;
    }

    public XrayConfig.Node currentNode() {
        return currentNode == null ? null : copy(currentNode);
    }

    /**
     * 测延迟。走的是当前核心，所以测不通 = 节点不通，不是本机网络问题。
     * 12 秒超时是 AAR 里写死的。
     *
     * @return 毫秒；-1 表示没在跑或测不通
     */
    public long measureDelay() {
        return measureDelay(PROBE_URL);
    }

    public long measureDelay(String url) {
        CoreController current = controller;
        if (current == null) return -1L;
        try {
            return current.measureDelay(url == null || url.isEmpty() ? PROBE_URL : url);
        } catch (Throwable e) {
            log("测延迟失败：" + shortOf(e));
            return -1L;
        }
    }

    /**
     * 对**任意一个节点**测一次，不碰正在跑的核心。
     *
     * 这是批量测速要用的：一个进程只有一个核心实例，如果为了测 50 个节点
     * 反复 start/stop，一是慢（每个都要起停），二是会把用户正在用的那条
     * 打断。AAR 的 measureOutboundDelay 是「拿配置和 URL 单独连一次」，
     * 和运行中的核心互不干扰，正好是这里要的语义。
     *
     * 失败一律返回 -1，不抛：测速失败是常态（免费节点大半不通），
     * 抛异常只会让调用方每个节点都写一遍 try。
     *
     * @param timeoutSeconds **无效，只为签名对齐保留**。AAR 里的
     *                       measureOutboundDelay 内部写死了 12 秒超时
     *                       （见 libv2ray 的 Go 源码：12 * time.Second），
     *                       传什么进去都改变不了它等多久。
     * @return 往返毫秒；-1 表示不通或超时
     */
    static long probe(android.content.Context context, XrayConfig.Node node, String url, int timeoutSeconds) {
        if (node == null) return -1L;
        // measureOutboundDelay 是 Libv2ray 的静态方法，**必须先 ensureEnv()**：
        // 它要 go.Seq.setContext + initCoreEnv 才认得了 assets 里的 geoip/geosite，
        // 没初始化就直接返回 -1（几毫秒），看起来像「节点全都不通」。
        // 2026-10-20 真机：冷启动后的新进程里没点过「保存并启动」，7 条免费节点
        // 在 10 毫秒内全报不通，而同一份节点在核心起来过之后再测是 56~64ms。
        ProxyCore core = context == null ? null : get(context);
        if (core != null && !core.ensureEnv()) {
            UserSource.log("测速跳过：核心环境没就绪（" + (envError == null ? "未知" : envError) + "）");
            return -1L;
        }
        try {
            // 端口随便给一个 —— 这份配置只用来发起一次出站连接，
            // 它的 inbound 根本不会被监听。给 0 会被 Xray 判非法，所以给个高位端口。
            //
            // 必须用 buildProbeString 而不是 buildString：后者带生产的 routing，
            // 里面 `geosite:cn → direct` 会把 PROBE_URL 的域截去直连，测出来的是
            // 手机到国内缓存的往返，不是节点的。见 XrayConfig.buildProbe 的注释。
            String config = XrayConfig.buildProbeString(node, 20480);
            String target = url == null || url.isEmpty() ? PROBE_URL : url;
            long started = System.currentTimeMillis();
            long ms = Libv2ray.measureOutboundDelay(config, target);
            // 单条探测瞬间返回 -1（几毫秒）几乎可以肯定是接口本身出错，而不是
            // 「节点不通」——真去连一次，最慢也要 4 秒才判死。真机上见过 7 条
            // 免费节点在 10 毫秒内全部报不通，这种日志能一眼区分开。
            long spent = System.currentTimeMillis() - started;
            if (ms < 0 && spent < 50) {
                UserSource.log("探测立即失败（" + spent + "ms）：" + node.describe());
            }
            return ms;
        } catch (Throwable e) {
            UserSource.log("探测抛异常：" + e.getClass().getSimpleName() + " " + e.getMessage());
            return -1L;
        }
    }

    /**
     * 上下行累计字节。key 用 "proxy" 这个 outbound tag —— Xray 的统计名是
     * {@code <tag>>>>} 与 {@code <tag><<<}，这里按 AAR 的 queryStats(tag, direct)
     * 约定传即可。
     *
     * 读一次会**清零**（AAR 的注释写明 queryStats「retrieves and resets」），
     * 所以调用方要自己累加，别当累计值用。
     */
    public long[] stats() {
        CoreController current = controller;
        if (current == null) return new long[] {0L, 0L};
        try {
            long up = current.queryStats(XrayConfig.TAG_OUT, "uplink");
            long down = current.queryStats(XrayConfig.TAG_OUT, "downlink");
            return new long[] {up, down};
        } catch (Throwable e) {
            return new long[] {0L, 0L};
        }
    }

    // ── 端口 ──────────────────────────────────────────────────────────

    /**
     * 选一个空闲的回环端口。
     *
     * 用 ServerSocket(0) 让系统挑：它知道哪些端口真的能 bind，比自己 random 一个
     * 去试准。读到端口号后立刻 close，再交给 Xray —— 中间有几毫秒窗口，本机
     * 回环端口被抢的概率极低，而且真抢了 startLoop 会抛，上层看到的是「启动失败」
     * 而不是「连上了但不通」。
     *
     * 为什么不固定端口：1080 是 v2rayNG 的默认值，用户装了两套客户端就撞；
     * 而固定一个冷门值又会在重装/前后台切换时被 TIME_WAIT 粘住。
     */
    static int pickPort() throws IOException {
        return pickPort(PORT_MIN, PORT_MAX);
    }

    /**
     * 区间内的空闲端口。
     *
     * 先让系统分配一个（它是真的在 bind 一次，比抽签准），落在区间内就用；
     * 落在外头就抽签 + probe。早先的版本「区间外也直接返回系统给的那个」，
     * 结果实测拿到 63518 —— 区间约束形同虚设，而 63518 这类高位口正是
     * 系统给短命连接的临时口范围，更容易撞上 TIME_WAIT。
     *
     * 系统分配只试一次：它给的结果对区间没有任何倾向性，多试几次不如直接抽签。
     *
     * 「空闲」必须**成对**：配置要起两个相邻入站（SOCKS 在 port、HTTP 在
     * port+1，见 XrayConfig.build），只探一个的话 +1 被别的进程占走，核心这边
     * SOCKS 正常、WebView 的 HTTP 入站悄悄起不来，还没有任何报错。
     */
    static int pickPort(int min, int max) throws IOException {
        if (min <= 0 || max > 65535 || min >= max) {
            throw new IllegalArgumentException("端口区间不合法：" + min + "-" + max);
        }
        int free = freePort();
        if (free >= min && free <= max && probe(free + 1)) return free;

        Random random = new Random();
        for (int i = 0; i < PROBE_ATTEMPTS; i++) {
            int candidate = min + random.nextInt(max - min);
            if (probe(candidate) && probe(candidate + 1)) return candidate;
        }
        throw new IOException("区间 " + min + "-" + max + " 内没有成对的空闲端口");
    }

    /** 系统分配的空闲端口，0 表示拿不到。 */
    static int freePort() {
        ServerSocket socket = null;
        try {
            // 绑 127.0.0.1 而不是通配：Xray 的 inbound 也只绑回环。
            // 不这么写的话，可能拿到一个「通配地址上占用、回环上空着」的端口。
            socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            return socket.getLocalPort();
        } catch (IOException e) {
            return 0;
        } finally {
            closeQuietly(socket);
        }
    }

    /** 能不能在这个端口上 bind。能就是空闲。 */
    static boolean probe(int candidate) {
        ServerSocket socket = null;
        try {
            // 端口 0 会被 ServerSocket 当成「随便给一个」，绕开区间约束，
            // 顺手也把区间检查重复一遍。
            if (candidate <= 0 || candidate > 65535) return false;
            socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), candidate));
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            closeQuietly(socket);
        }
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    // ── 内部 ──────────────────────────────────────────────────────────

    /**
     * 核心回调。三个方法都必须快速返回 —— 它们跑在 Go 调过来的线程上，
     * 阻塞会拖住核心自己的调度。
     */
    private static final class Handler implements CoreCallbackHandler {
        @Override
        public long onEmitStatus(long code, String message) {
            // code 非 0 是错误。这里只记日志不抛：抛出去只会变成一条
            // 看不出所以然的 Go 侧 panic 堆栈。
            if (code != 0) log("核心状态 " + code + " " + message);
            return 0;
        }

        @Override
        public long shutdown() {
            return 0;
        }

        @Override
        public long startup() {
            return 0;
        }
    }

    /** 同一个节点就不重启核心 —— 重启会打断正在播放的流。 */
    private static boolean sameNode(XrayConfig.Node a, XrayConfig.Node b) {
        if (a == null || b == null) return false;
        return equal(a.protocol, b.protocol) && equal(a.server, b.server) && a.port == b.port
                && equal(a.uuid, b.uuid) && equal(a.method, b.method) && a.alterId == b.alterId
                && equal(a.network, b.network) && equal(a.tls, b.tls) && equal(a.path, b.path)
                && equal(a.host, b.host)
                // 这批字段直接决定握手（reality 的 pbk/sid/fp、vless 的 flow、
                // hy2 的带宽）—— 漏比的话换源 reapplyRouting() 会把换过源的
                // 节点当成「同一个」，拿缺参配置重新起核心，握手必失败。
                && equal(a.security, b.security) && equal(a.fingerprint, b.fingerprint)
                && equal(a.flow, b.flow) && equal(a.serviceName, b.serviceName)
                && equal(a.publicKey, b.publicKey) && equal(a.shortId, b.shortId)
                && a.allowInsecure == b.allowInsecure
                && a.upMbps == b.upMbps && a.downMbps == b.downMbps
                && equal(a.sni, b.sni);
    }

    private static boolean equal(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static XrayConfig.Node copy(XrayConfig.Node source) {
        if (source == null) return null;
        XrayConfig.Node node = new XrayConfig.Node(source.protocol, source.server, source.port);
        node.uuid = source.uuid;
        node.method = source.method;
        node.alterId = source.alterId;
        node.security = source.security;
        node.network = source.network;
        node.tls = source.tls;
        node.path = source.path;
        node.host = source.host;
        node.fingerprint = source.fingerprint;
        node.flow = source.flow;
        node.serviceName = source.serviceName;
        // reality / hy2 的握手参数与带宽估算必须跟着走：漏复制的后果是
        // reapplyRouting() 用缺参配置重启核心，节点连握手都过不去。
        node.publicKey = source.publicKey;
        node.shortId = source.shortId;
        node.allowInsecure = source.allowInsecure;
        node.upMbps = source.upMbps;
        node.downMbps = source.downMbps;
        node.sni = source.sni;
        return node;
    }

    /** 异常只留一句，不打堆栈 —— 这行会进 logcat，而用户看不到堆栈。 */
    private static String shortOf(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) return e.getClass().getSimpleName();
        return e.getClass().getSimpleName() + ": " + message;
    }

    private static void log(String message) {
        try {
            android.util.Log.d(TAG, "[core] " + message);
        } catch (Throwable ignored) {
        }
    }
}
