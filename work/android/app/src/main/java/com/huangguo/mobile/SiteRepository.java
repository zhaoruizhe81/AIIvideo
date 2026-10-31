package com.huangguo.mobile;

import android.content.Context;
import android.util.Base64;
import com.huangguo.mobile.Text;
import com.huangguo.mobile.Ctl;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

/**
 * 当前源的**内容通路**：REST 取 JSON、封面缓存、控制面 profile。
 *
 * ── 2026-10-18：删掉八条假接口 ─────────────────────────────────────
 * 这里原来有 home / category / search / tags / tagListing / episode /
 * recommend 七个方法，每个都只有一句
 * {@code throw new UnsupportedOperationException("…已改为纯 REST，抓 HTML 的分支
 * 已移除")}，再加上 MainActivity 里对应的七条桥指令。它们不是「以后要实现的桩」，
 * 而是**已经死掉的分支**：前端的内容请求统一走 {@code web.paths} 拼出来的路径
 * （→ detail 桥 → 这里 → AiiHttp），永远到不了这几条命令。
 *
 * 留着它们的代价不是几行代码，而是：任何读代码的人都会以为「内容语义在 Java 里
 * 还有一半」，于是新加一个源时先来这里找该覆盖哪个方法 —— 而正确答案是
 * 「去配置里写 paths」。
 */
final class SiteRepository {
    private final Context appContext;
    private final File coverDir;
    private final SiteProfile profile;

    /**
     * 内容通路：JSON / HTML。盾后站点走 {@code fetchPage}（同源 XHR）。
     */
    private byte[] fetch(String str) throws Exception {
        try {
            return AiiHttp.get(str, this.profile);
        } catch (Exception e) {
            if (SiteFetchWebView.handles(str)) {
                UserSource.log("内容改走真 WebView：" + hostOnly(str)
                        + "（okhttp 失败：" + e.getMessage() + "）");
                return SiteFetchWebView.fetchBytes(str);
            }
            throw e;
        }
    }

    /**
     * 封面通路。
     *
     * <p><b>单独一条的原因</b>（2026-10-19 真机）：封面原来复用 {@link #fetch}，
     * 那条路第一步是 {@code fetchBytes}（站点页里的同源 XHR），而它抢的是
     * {@code SiteFetchWebView} 的 {@code fetchLock} —— 那个锁同时被「抓详情页」
     * 用着。首屏十二张封面一起冲进来，每张都先失败一次（跨源没有 ACAO 头，
     * 必失败）再退到导航，锁就被堵满了：详情页的请求排在后面，15 秒后超时，
     * 界面停在「正在加载…」。
     *
     * <p>封面根本不需要 XHR：{@code <img>} 那条路被 CSP / ORB 挡，唯一能通的是
     * 「navigate 过去读 canvas 像素」，它用的是另一把锁（imageLock）+ 另一个
     * WebView。所以这里直接跳到 {@code fetchImage}，一张封面都不占 fetchLock。
     */
    private byte[] fetchCover(String str) throws Exception {
        try {
            return AiiHttp.get(str, this.profile);
        } catch (Exception e) {
            if (SiteFetchWebView.handles(str)) {
                UserSource.log("封面改走真 WebView：" + hostOnly(str)
                        + "（okhttp 失败：" + e.getMessage() + "）");
                return SiteFetchWebView.fetchImage(str);
            }
            throw e;
        }
    }

    // ── 封面失败的负缓存 ────────────────────────────────────────────────
    //
    // 前台滚动时会不停重试同一张封面（cover.js 每 30 秒一次、且列表重绘又会问）。
    // 没有这一层的话，一次故障能刷出几百条一模一样的上报 —— 线上那 1340 条
    // 「封面解密结果无效」里绝大多数是这么来的。第一次照常抛、照常报；
    // 五分钟内同样的地址直接抛缓存里那条，并且带上 Report.Once，dispatch 不再上报。
    //
    // ── 键分两层（2026-10-25）────────────────────────────────────────
    // 只按 URL 存是不够的：一个源的封面主机挂了，**每一张封面的 URL 都不同**，
    // 缓存一次都命中不了。线上证据：一台设备 34 条「无效封面域名」，横跨 351 分钟
    // —— 那是列表里 34 张不同封面的同一个配置错误。
    //
    // 所以再存一份按**主机**的：同一个主机上的失败，后面那些不再逐个上报。
    // 消息里带上第一次的原文，方便知道是哪一类问题（白名单/网络/解密）。
    private static final long FAIL_CACHE_MS = 5 * 60 * 1000L;
    /**
     * 网络类失败的主机冻结期。**必须远短于 FAIL_CACHE_MS**。
     *
     * 2026-10-31 真机：换完版本第一次打开黄果，一张封面赶上网抖失败，主机级缓存
     * 把整页 20 张全挡了五分钟，用户看到的是「这个源没有封面」。而每 3 秒一次的
     * 重试全打在缓存上，一次真请求都不发 —— 网络早好了，界面还黑着。
     * 配置类（白名单/格式/密钥）重试一万次结果一样，那才配用满 5 分钟。
     */
    static final long FAIL_HOST_NET_MS = 45 * 1000L;
    private static final int FAIL_CACHE_MAX = 300;
    private static final java.util.Map<String, CoverFailure> FAILED =
            new java.util.concurrent.ConcurrentHashMap<String, CoverFailure>();
    /** 主机级：key = 主机名（小写）。值只用来判断「这个主机刚失败过」，消息不共用。 */
    private static final java.util.Map<String, Long> FAILED_HOSTS =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();

    /** 缓存过的那条失败：消息和第一次一模一样，但重试时不再进上报。 */
    static final class CoverFailure extends IllegalStateException implements Report.Once {
        final long until;

        CoverFailure(String message, long until) {
            super(message);
            this.until = until;
        }
    }

    /** 配置类失败：重试无意义，URL 和主机都冻满 5 分钟。 */
    static void rememberFailure(String url, String message) {
        rememberFailure(url, message, FAIL_CACHE_MS);
    }

    /**
     * @param hostTtlMs 主机这一层冻多久。网络类传 {@link #FAIL_HOST_NET_MS}：
     *                  短冻是为了让界面自己好起来，不是为了省上报 —— 上报那边
     *                  有 Report.Once 兜着，重复的那几条本来也发不出去。
     */
    static void rememberFailure(String url, String message, long hostTtlMs) {
        if (FAILED.size() > FAIL_CACHE_MAX) {
            FAILED.clear();
        }
        FAILED.put(url, new CoverFailure(message, System.currentTimeMillis() + FAIL_CACHE_MS));
        String host = hostOf(url);
        if (host.length() > 0) {
            if (FAILED_HOSTS.size() > FAIL_CACHE_MAX) {
                FAILED_HOSTS.clear();
            }
            FAILED_HOSTS.put(host, System.currentTimeMillis() + hostTtlMs);
        }
    }

    /**
     * 这张封面拿到了 —— 把它和它主机的「失败」记录一起抹掉。
     *
     * 缺这一条的时候，主机级缓存在成功之后仍然挂着，于是「第一次失败」的账
     * 一直记到 TTL 到期：后面明明抓得回来，前 45 秒照样抛缓存里的旧异常。
     */
    static void forgetFailure(String url) {
        if (url != null) {
            FAILED.remove(url);
        }
        String host = hostOf(url);
        if (host.length() > 0) {
            FAILED_HOSTS.remove(host);
        }
    }

    /**
     * 这个主机的同一条 URL 之外，还有没有别的封面在同一个坑里。
     *
     * 命中时返回一条**带 Report.Once 的**异常：调用方照常失败（该有的错误态不变），
     * 但 `dispatch` 里 `!Report.isOnce(error)` 会挡住上报 —— 一次主机级故障
     * 只留一条记录，不是几十条。
     */
    static CoverFailure hostFailure(String url) {
        String host = hostOf(url);
        if (host.length() == 0) return null;
        Long until = FAILED_HOSTS.get(host);
        if (until == null) return null;
        if (System.currentTimeMillis() >= until) {
            FAILED_HOSTS.remove(host);
            return null;
        }
        return new CoverFailure("封面主机刚刚失败过（" + host + "），本轮不再重复上报", until);
    }

    // ── 只给单测用的两个口子 ───────────────────────────────────────────
    // 都带 Test 后缀，生产代码里搜不到调用点。缓存是静态的，不重置的话
    // 用例之间会互相污染（和 ReportTest 里 setSource 那个坑同一类）。

    static void resetCoverFailureCache() {
        FAILED.clear();
        FAILED_HOSTS.clear();
    }

    static void rememberHostFailureForTest(String host, long until) {
        FAILED_HOSTS.put(host.toLowerCase(Locale.ROOT), until);
    }

    /** 从任意 URL 里取主机名，取不到返回空串（不抛）。包可见，单测要盯。 */
    static String hostOf(String url) {
        try {
            java.net.URI uri = java.net.URI.create(String.valueOf(url));
            String h = uri.getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * 缓存键里要剔掉的签名参数（小写比较）。
     *
     * 黄果的每张封面上都挂着 {@code ?auth_key=<过期戳>-0-0-<md5>}，**每一批签名
     * 都不一样**（2026-10-31 实测约 90 分钟换一轮）。把它算进缓存键，等于每 90
     * 分钟把整个图床重下一遍；而「一次重下」就是「一次网络抖动 → 整页封面黑掉」
     * 的触发条件。签名只决定「这张图给不给你看」，不决定「这张图是哪张」，
     * 所以同一路径按其余业务参数命中同一份缓存是对的。
     */
    private static final java.util.Set<String> SIGNATURE_PARAMS =
            new java.util.HashSet<String>(java.util.Arrays.asList(
                    "auth_key", "sign", "signature", "token", "expires",
                    "ossaccesskeyid", "x-oss-credential"));

    /** 包可见：单测要盯住「签名剔了、业务参数留着」这两面。 */
    static String cacheKeyQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (String pair : rawQuery.split("&")) {
            if (pair.length() == 0) continue;
            int eq = pair.indexOf('=');
            String name = (eq < 0 ? pair : pair.substring(0, eq)).toLowerCase(Locale.ROOT);
            if (SIGNATURE_PARAMS.contains(name)) continue;
            if (sb.length() > 0) sb.append('&');
            sb.append(pair);
        }
        return sb.toString();
    }

    /** 把「拿回来的到底是什么」写清楚，别再让人靠猜。包可见，方便单测盯住文案。 */
    static String coverFailureDetail(byte[] raw, byte[] decoded, String mode, String host) {
        StringBuilder sb = new StringBuilder("封面不是图片：mode=").append(mode)
                .append(" 原始").append(raw == null ? 0 : raw.length).append("字节");
        if (decoded != null && decoded.length >= 4) {
            sb.append(" 解出").append(String.format("%02x%02x%02x%02x",
                    Integer.valueOf(decoded[0] & 255), Integer.valueOf(decoded[1] & 255),
                    Integer.valueOf(decoded[2] & 255), Integer.valueOf(decoded[3] & 255)));
            if (decoded[0] == 60) {
                sb.append("（HTML，图床回了错误页）");
            }
        }
        return sb.append(" @").append(host).toString();
    }

    /** 只为日志取个主机名，失败返回原串。 */
    private static String hostOnly(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            return uri.getHost() == null ? url : uri.getHost();
        } catch (Throwable e) {
            return url;
        }
    }

    /**
     * 控制面（源/播放器/代理/进度/HTTP）要用的 profile。
     *
     * 单独开一个访问器而不是把字段放开：detail 那条路是「按 id 取数据」，
     * 这条是「把当前生效的源配置交给状态机」—— 两者语义不同，
     * 将来 profile 的来源变了（比如按请求选源），只改这里。
     */
    SiteProfile profileForControl() {
        return this.profile;
    }

    JSONObject detail(String str) throws Exception {
        String str2;
        if (str.startsWith("__")) {
            return UserSource.handle(this.appContext, this.profile, str);
        }
        if (str.startsWith("https://")) {
            str2 = str;
        } else {
            if (!str.startsWith("/")) {
                throw new UnsupportedOperationException("路径既不是 / 开头的动作路径，也不是 https 绝对地址，无法处理");
            }
            str2 = this.profile.origin + str;
        }
        return new JSONObject(new String(fetch(str2), StandardCharsets.UTF_8));
    }

    SiteRepository(Context context) {
        this(context, SiteProfile.current(context));
    }

    SiteRepository(Context context, SiteProfile siteProfile) {
        this.profile = siteProfile;
        this.appContext = context.getApplicationContext();
        File file = new File(context.getCacheDir(), "covers");
        this.coverDir = file;
        if (file.exists()) {
            return;
        }
        file.mkdirs();
    }

    /**
     * 空封面地址 = 这个条目没有封面，不是故障。
     *
     * 抽成静态只是为了让单测打得到 —— {@link #coverData} 的实例要 Context，
     * 在纯 JVM 单测里造不出来。
     */
    static boolean isBlankCover(String str) {
        return str == null || str.trim().isEmpty();
    }

    String coverData(String str) throws Exception {
        // 空地址 = 这个条目没有封面，不是故障。
        //
        // 前端 cover.js 的 cover.get(url) 是这条路的唯一入口，而 hero 轮播那条
        // （app.js 的 setHero）没有判空 —— adapters.js 的选集适配器会给条目
        // cover: ''。以前空串一路走到 coverUrl('')，new URI("") 的 scheme 是
        // null，当场抛「无效封面地址：」（冒号后面什么都没有），被算成一次失败。
        // 线上 2.0.23 里 13 台设备 15 条，ms 全是 0~3 —— 压根没到网络。
        //
        // 前端那侧也补了判空。两层都要有：前端哪天再漏一个空值下来，这里仍然
        // 不该把它当成一场网络/配置故障去上报。
        if (isBlankCover(str)) {
            return "";
        }
        String str2;   // 方法尾部拼 data URI 的 MIME 类型，别删。
        // ── 负缓存查在 coverUrl() **之前** ─────────────────────────────
        // coverUrl() 自己会抛「无效封面域名 / 无效封面地址」（配置问题，不是网络问题）。
        // 缓存原来是放在它后面的，于是这两类失败永远进不了 rememberFailure ——
        // 一张被白名单拒掉的封面会被每 30 秒重试一次、次次都上报。
        // 线上证据：一台设备 34 条「无效封面域名」，横跨 351 分钟。
        //
        // 缓存键用**原始 URL**：coverUrl() 可能抛，那时拿不到归一化后的键。
        CoverFailure cached = FAILED.get(str);
        if (cached != null) {
            if (System.currentTimeMillis() < cached.until) {
                throw cached;
            }
            FAILED.remove(str);
        }
        // 第二层：这个主机的**另一张**封面刚失败过。
        // 列表里 34 张封面是 34 个不同 URL，只按 URL 缓存拦不住；主机关挂了
        // 却会一次性报 34 条。这里把它们合并成一条（仍然抛，界面照常出占位图）。
        CoverFailure hostCached = hostFailure(str);
        if (hostCached != null) {
            throw hostCached;
        }
        String strCoverUrl;
        try {
            strCoverUrl = this.profile.coverUrl(str);
        } catch (IllegalArgumentException configError) {
            // 配置缺主机这类失败，重试一万次结果一样 —— 缓存住，别再刷上报。
            rememberFailure(str, configError.getMessage() == null ? "封面配置不合法" : configError.getMessage());
            throw configError;
        }
        URL url = new URL(strCoverUrl);
        String path = url.getPath();
        int dot = path.lastIndexOf(46);
        // 只有「点在最后一段路径里」才算有扩展名。/covers/202401/abc.def/1 这种
        // 目录里带点的路径不算。
        boolean hasExtension = dot > path.lastIndexOf(47);
        String lowerCase = hasExtension ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        // 没有扩展名的地址放过去 —— 图床用 /covers/12345/aBcDeF 这种裸路径很常见，
        // pornhub 一批缩略图就是这样（埋点里那串「不支持的封面格式」全是它们）。
        // 真正的边界是上面那道 allowedHosts 白名单，加上拿回来之后必须解得开图片；
        // 有扩展名却不是图片类型，才说明这条地址可疑。
        if (this.profile.object("cover").optBoolean("checkExtension", true)
                && hasExtension
                && !Arrays.asList("jpg", "jpeg", "png", "webp", "gif").contains(lowerCase)) {
            rememberFailure(str, "不支持的封面格式：" + lowerCase + " @" + url.getHost());
            throw new IllegalArgumentException("不支持的封面格式：" + lowerCase + " @" + url.getHost());
        }
        // 缓存键把 query 也算进去：同路径不同参数的封面是两张不同的图，
        // 只 hash 到 path 会互相污染（?id=1 的图被 ?id=2 直接吃到缓存）。
        // 但**签名参数不算**：见 cacheKeyQuery。
        byte[] bArrDigest = MessageDigest.getInstance("SHA-256").digest((this.profile.fingerprint + ":" + url.getHost() + path + "?" + cacheKeyQuery(url.getQuery())).getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            sb.append(String.format("%02x", Integer.valueOf(bArrDigest[i] & 255)));
        }
        File file = new File(this.coverDir, ((Object) sb) + ".img");
        byte[] bArr = null;
        if (file.isFile()) {
            byte[] allBytes = null;
            try {
                allBytes = Files.readAllBytes(file.toPath());
            } catch (java.io.IOException gone) {
                // isFile() 和 readAllBytes() 之间文件没了 —— trimCoverCache 在另一个
                // 线程删的，或者系统清了 cache。这不是封面故障，当 miss 重抓就行。
                // 线上那 70 条 FileNotFoundException（同一个 uid 150 的设备）就是它：
                // 报成「封面失败」以后，用户看到的是灰块，而实际问题在缓存目录，
                // 报错里那句 ENOENT 谁也猜不到这层意思。
                file.delete();
            }
            if (allBytes != null) {
                if (repairConfiguredHeader(allBytes)) {
                    FileOutputStream fileOutputStream = new FileOutputStream(file);
                    try {
                        fileOutputStream.write(allBytes);
                        fileOutputStream.close();
                    } catch (Throwable th) {
                        try {
                            fileOutputStream.close();
                        } catch (Throwable th2) {
                            th.addSuppressed(th2);
                        }
                        throw th;
                    }
                }
                if (!validImage(allBytes)) {
                    file.delete();
                } else {
                    file.setLastModified(System.currentTimeMillis());
                    bArr = allBytes;
                }
            }
        }
        if (bArr == null) {
            byte[] raw;
            try {
                raw = fetchCover(strCoverUrl);
            } catch (Exception fetchError) {
                // 抓不下来（超时、连接重置、图床回 4xx）就照原样抛 —— dispatch 会记一条。
                // 同一条地址五分钟内再问，走上面的缓存，不再重复上报。
                // 但主机那一层只冻 45 秒：网络是活的，冻结期满就让它自己重试，
                // 别让一次抖动把整页封面按死五分钟。
                rememberFailure(str, fetchError.getMessage() == null ? "请求失败" : fetchError.getMessage(),
                        FAIL_HOST_NET_MS);
                throw fetchError;
            }
            String strOptString = this.profile.object("cover").optString("mode");
            byte[] bArrFetch = raw;
            if ("aes-cbc".equals(strOptString) || (Text.TEXT_EMPHASIS_AUTO.equals(strOptString) && !validImage(bArrFetch))) {
                Cipher cipher = Cipher.getInstance(this.profile.object("cover").getString("transformation"));
                cipher.init(2, new SecretKeySpec(this.profile.keyBytes("key"), "AES"), new IvParameterSpec(this.profile.keyBytes("iv")));
                bArrFetch = cipher.doFinal(bArrFetch);
            }
            repairConfiguredHeader(bArrFetch);
            if (!validImage(bArrFetch)) {
                // 以前这里只说「解密结果无效」，把两件事混成一件：图床没给图片
                // （404 / 防盗链 / 断流回了个错误页），和密钥 IV 对不上。
                // 现在把原始字节数、解出来的前四字节、主机一起带上，一眼能分清。
                String detail = coverFailureDetail(raw, bArrFetch, strOptString, url.getHost());
                rememberFailure(str, detail);
                throw new IllegalStateException(detail);
            }
            bArr = bArrFetch;
            FileOutputStream fileOutputStream2 = new FileOutputStream(file);
            try {
                fileOutputStream2.write(bArr);
                fileOutputStream2.close();
                trimCoverCache();
            } catch (Throwable th3) {
                try {
                    fileOutputStream2.close();
                } catch (Throwable th4) {
                    th3.addSuppressed(th4);
                }
                throw th3;
            }
        }
        // 拿到图了 —— 这一条和它主机上的失败记录都作废。放在这儿（读缓存命中与
        // 网络抓取两条路都汇到这点）是因为「成功」这个事实只在这里确定一次。
        forgetFailure(str);
        byte b = bArr[0];
        if (b == -1) {
            str2 = Mime.IMAGE_JPEG;
        } else if (b == -119) {
            str2 = Mime.IMAGE_PNG;
        } else {
            str2 = b == 71 ? "image/gif" : Mime.IMAGE_WEBP;
        }
        return "data:" + str2 + ";base64," + Base64.encodeToString(bArr, 2);
    }

    private boolean repairConfiguredHeader(byte[] bArr) throws Exception {
        return ("legacy-xor".equals(this.profile.object("cover").optString("headerRepair")) && repairCoverHeader(bArr)) || this.profile.patchHeader(bArr);
    }

    private static boolean repairCoverHeader(byte[] bArr) {
        if (bArr.length < 12) {
            return false;
        }
        byte b = bArr[0];
        boolean z = b == -119 && bArr[1] == 80 && bArr[2] == 78 && bArr[3] == 71 && bArr[4] == 13 && bArr[5] == 10 && bArr[6] == 20 && bArr[7] == 10;
        boolean z2 = b == -1 && bArr[1] == -40 && bArr[2] == -1;
        boolean z3 = z2 && bArr[3] == -32 && bArr[6] == 68 && bArr[7] == 70 && bArr[8] == 73 && bArr[9] == 70 && bArr[10] == 0;
        boolean z4 = z2 && bArr[3] == -37 && (bArr[6] & 252) == 12;
        boolean z5 = z2 && bArr[3] == -31 && bArr[6] == 75 && bArr[7] == 120 && bArr[8] == 105 && bArr[9] == 102;
        boolean z6 = z2 && bArr[3] == -30 && bArr[6] == 71 && bArr[7] == 67 && bArr[8] == 67 && bArr[9] == 95;
        if (!z && !z3 && !z4 && !z5 && !z6) {
            return false;
        }
        bArr[6] = (byte) (bArr[6] ^ Ctl.SO);
        return true;
    }

    private static boolean validImage(byte[] bArr) {
        if (bArr.length < 16) {
            return false;
        }
        byte b = bArr[0];
        return (b == -1 && bArr[1] == -40) || (b == -119 && bArr[1] == 80 && bArr[2] == 78 && bArr[3] == 71 && bArr[4] == 13 && bArr[5] == 10 && bArr[6] == 26 && bArr[7] == 10) || ((b == 82 && bArr[1] == 73 && bArr[2] == 70 && bArr[3] == 70 && bArr[8] == 87 && bArr[9] == 69 && bArr[10] == 66 && bArr[11] == 80) || (b == 71 && bArr[1] == 73 && bArr[2] == 70));
    }

    private void trimCoverCache() {
        File[] fileArrListFiles = this.coverDir.listFiles();
        if (fileArrListFiles == null) {
            return;
        }
        long length = 0;
        for (File file : fileArrListFiles) {
            length += file.length();
        }
        if (length <= 157286400) {
            return;
        }
        Arrays.sort(fileArrListFiles, Comparator.comparingLong(File::lastModified));
        for (File file2 : fileArrListFiles) {
            if (length <= 157286400) {
                return;
            }
            long length2 = file2.length();
            if (file2.delete()) {
                length -= length2;
            }
        }
    }

    void clearCovers() {
        File[] fileArrListFiles = this.coverDir.listFiles();
        if (fileArrListFiles != null) {
            for (File file : fileArrListFiles) {
                file.delete();
            }
        }
    }
}
