package com.huangguo.mobile;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * spider 源仓库：先找热更包里的 {@code ui/spiders/*.js}，再回落到 APK 的
 * {@code assets/spiders/*.js}，并在这一层做 ABI 门禁。
 *
 * <p>阶段 0 的源是硬编码读 assets 的（`_dev-hello.js`）。阶段 1 让它来自热更包 ——
 * 那才是「加源不发版」真正落地的一步：新源写一份 JS、打进热更包，壳不用动。
 *
 * <p>══ 为什么元信息靠正则、不靠执行 ══
 * 要拿 {@code id/version/requires} 只需要读文件头。执行一遍得先建 WebView（几百毫秒
 * 加一个渲染进程），而这一步在「列出所有源」时会对每个文件都做一次 —— 那是给自己
 * 造一个卡顿。所以这里只用正则抓字面量；抓不到就明确拒绝，不猜。
 *
 * <p>══ ABI 门禁为什么在这一层，而不是在 JS 里 ══
 * 老壳装上新源时，唯一的正确行为是<b>当场说清楚</b>「这个源要用新版应用」。
 * 放进 JS 里判断就变成了「跑起来之后才发现」，而那时用户已经看到空白页了。
 * 所以门禁在读文件的时候就判，判不过的源连注入都不做。
 *
 * <p>没有缓存：热更会换目录、换文件，缓存要配一套失效逻辑，而失效逻辑写错的症状
 * （「源更新了但界面还是旧的」）极难查。一次扫描读几个 10KB 的文件，不值得为它
 * 引入那类 bug。
 */
final class SpiderStore {

    private static final String TAG = "HG.Spider";

    /**
     * 用户自己装的源（应用私有目录下的 {@code files/spiders/}）。
     *
     * <p>这是「像 TVBox 那样贴一个地址就能加源」的落点：地址指向一个 js 文件，
     * 下回来校验、落盘，之后就当一个普通源用。优先级最高 —— 用户手动装的版本
     * 应该盖过热更下发的同名源，否则他改了也没用，排错时会疯。
     */
    private static final String INSTALL_DIR = "spiders";

    /** 安装时的下载超时。源文件很小，20 秒足够；再久就是站点或代理出了问题。 */
    private static final int INSTALL_TIMEOUT_S = 20;

    /**
     * 单份源码的上限 2MB。
     *
     * <p>正常的源是几 KB 到几十 KB；2MB 已经能装下最离谱的站。设上限是为了防
     * 「热更包里混进一个大文件把内存吃光」—— 热更包是有签名的，但它也可能是
     * 发版时手滑打进去的东西，而这道闸门只要一行。
     */
    private static final long MAX_SOURCE_BYTES = 2 * 1024 * 1024;

    private static final Comparator<File> FILE_BY_NAME = new Comparator<File>() {
        @Override
        public int compare(File a, File b) {
            return a.getName().compareTo(b.getName());
        }
    };

    private SpiderStore() {
    }

    // ── 对外入口 ──────────────────────────────────────────────────────

    /**
     * 列出所有能找到的源（含被拒绝的），按「热更在前、assets 在后」的顺序。
     *
     * <p>被拒绝的源也列出来，而且带 reason：设置页要能告诉用户「这个源要新版应用」，
     * 而不是让它凭空消失 —— 消失的话用户只会觉得「热更没生效」。
     *
     * @return 每项 {@code {id, name, version, requires, source, ok, reason}}，
     *         {@code source} 取值 {@code "hot"} / {@code "apk"}
     */
    static List<JSONObject> list(Context ctx) {
        List<Entry> entries = scan(ctx);
        List<JSONObject> out = new ArrayList<JSONObject>(entries.size());
        for (Entry e : entries) {
            try {
                out.add(new JSONObject()
                        .put("id", e.id)
                        .put("name", e.name)
                        .put("version", e.version)
                        .put("requires", e.requires)
                        .put("source", e.source)
                        .put("ok", e.ok)
                        .put("reason", e.reason));
            } catch (Throwable t) {
                // JSONObject.put 只会在 key 为 null 时抛，这里不可能；真抛了也别让
                // 一个条目毁掉整个列表。
                Log.w(TAG, "spider 条目编码失败：" + e.id);
            }
        }
        return out;
    }

    /**
     * 调用某个源的一个能力：找到源码 → 交给 {@link SpiderRuntime} 跑。
     *
     * <p><b>后台线程调用</b>（SpiderRuntime 那边要阻塞等 WebView）。
     *
     * @throws IOException 找不到源、源被 ABI 门禁拒了、源码读不出来 —— message
     *                     都是能直接给用户/日志看的那一句
     */
    static JSONObject call(Context ctx, String spiderId, String cap,
                           JSONObject args, int timeoutMs) throws Exception {
        if (spiderId == null || spiderId.length() == 0) {
            throw new IOException("没有指定要调用哪个 spider。");
        }
        Entry e = find(ctx, spiderId);
        if (e == null) {
            throw new IOException("找不到 spider：" + spiderId);
        }
        if (!e.ok) {
            // 门禁的裁决原样往外抛：调用方/日志看到的就是「需要更新版本的应用
            // （需要 abi 2，当前 1）」这种能直接转述给用户的话。
            throw new IOException(e.reason);
        }
        String source = e.readSource(ctx);
        if (source == null || source.length() == 0) {
            throw new IOException("spider 源码是空的：" + spiderId);
        }
        Log.i(TAG, "cap=" + cap + " id=" + spiderId + " src=" + e.source
                + " bytes=" + source.length());
        // 2026-10-19：宿主在 :spider 独立进程里跑（见 SpiderService 类注释）——
        // Android WebView 全 app 共享渲染主线程，spider 一次抓取解析会冻住主页面
        // 的动画/滚动，进程隔离是唯一根治。这里只是换了个调用通道，契约不变。
        return SpiderClient.call(ctx, source, cap, args, timeoutMs);
    }

    /**
     * 装一个源：贴地址 → 下载 → 校验 → 落盘。**后台线程调用。**
     *
     * <p>这就是「像 TVBox 那样加源」的那一步。它只做三件事：
     *
     * <ol>
     *   <li>只认 https —— http 在移动网络里会被运营商插页，下回来的 js 可能是
     *       别人的广告脚本，而这段代码要以源的身份联网；</li>
     *   <li>大小、id、requires 三道校验全过才落盘 —— 一个 id 不合法的源装进去，
     *       列表里会出现一个永远调不动的孤儿；</li>
     *   <li>落盘是「写临时文件 → rename」—— 直接覆盖写的话，正好在那一刻被调用
     *       就会读到半截 js，报的错是语法错误，和真正的原因（正在装）毫无关系。</li>
     * </ol>
     *
     * <p><b>信任模型说明在 {@link #installBytes} 上</b>，那是这条链路的入口。
     *
     * @return 装好的源的元信息（和 {@link #list} 里那一项同形，source 是 "user"）
     */
    static JSONObject install(Context ctx, String url) throws Exception {
        if (url == null || !url.trim().toLowerCase(java.util.Locale.ROOT).startsWith("https://")) {
            throw new IOException("只支持 https 地址");
        }
        byte[] raw = AiiHttp.getRaw(url.trim(), null, INSTALL_TIMEOUT_S);
        return installBytes(ctx, url.trim(), raw);
    }

    /**
     * 装一个源（已下载好的字节）。和 {@link #install} 分开是为了能单测。
     *
     * <p><b>信任模型</b>：装进来的 js 会在一个 CSP 锁死的宿主页里跑，能力只有
     * {@code net / log / done} 三样，而 {@code net} 只放行它在 {@code hosts} 里
     * 自己声明的域。所以一个源能碰什么，写在它自己的文件里 —— 和 TVBox 一家人
     * 一样的模型：<b>边界是「你装了什么」，不是「它能干什么」</b>。壳这侧只保证
     * 它没有 DOM、没有文件、没有 socket、没有 dex。
     */
    static JSONObject installBytes(Context ctx, String url, byte[] raw) throws Exception {
        return installTo(installDir(ctx), url, raw);
    }

    /**
     * 把源写进指定目录。和 Context 解耦是为了能在 JVM 单测里跑 —— 这条链路
     * 「校验 → 落盘 → 可再删除」，每一步错了都不会崩，只会让用户看到一个
     * 永远调不动的孤儿源。
     */
    static JSONObject installTo(File dir, String url, byte[] raw) throws Exception {
        if (raw == null || raw.length == 0) throw new IOException("下载回来的内容是空的");
        if (raw.length > MAX_SOURCE_BYTES) {
            throw new IOException("源文件太大（" + (raw.length / 1024) + "KB），上限 2MB");
        }
        String src = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        String fallbackName = guessName(url);
        Entry e = parse(src, "user", fallbackName);
        if (!e.ok) {
            // 元信息读不出来 / ABI 不满足：直接拒绝，别把装不上的东西写进目录。
            throw new IOException(e.reason);
        }
        if (!e.id.matches("[a-z0-9][a-z0-9_-]{0,31}")) {
            throw new IOException("源 id 只能是小写字母、数字、下划线、连字符（最多 32 字符）：" + e.id);
        }

        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("建不了源的目录：" + dir);
        File target = new File(dir, e.id + ".js");
        File tmp = new File(dir, e.id + ".js.tmp");
        java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
        try {
            out.write(raw);
            out.getFD().sync();
        } catch (IOException writeError) {
            // 写一半抛异常时清掉残件：.js.tmp 留在目录里，下次扫描会冒出一个
            // 调不动的孤儿。delete 必须只挂在失败路径上 —— 放进 finally 的话
            // 成功路径会在 rename 之前把 tmp 删掉，install 永远「写不进去」。
            tmp.delete();
            throw writeError;
        } finally {
            out.close();
        }
        if (!tmp.renameTo(target)) {
            tmp.delete();
            throw new IOException("写不进去：" + target.getName());
        }
        Log.i(TAG, "已安装源 " + e.id + " ← " + url + "（" + raw.length + " 字节）");
        return new JSONObject()
                .put("id", e.id)
                .put("name", e.name)
                .put("version", e.version)
                .put("requires", e.requires)
                .put("source", "user")
                .put("ok", true)
                .put("reason", "");
    }

    /** 删掉一个用户装的源。热更/内置的不能删（它们不归用户管）。 */
    static boolean remove(Context ctx, String spiderId) throws Exception {
        return removeFrom(installDir(ctx), spiderId);
    }

    /** 删一个已安装的源。和 Context 解耦同样是为了单测。 */
    static boolean removeFrom(File dir, String spiderId) throws Exception {
        if (spiderId == null || !spiderId.matches("[a-z0-9][a-z0-9_-]{0,31}")) {
            throw new IOException("源 id 不合法：" + spiderId);
        }
        File target = new File(dir, spiderId + ".js");
        boolean gone = target.isFile() && target.delete();
        if (gone) Log.i(TAG, "已删除源 " + spiderId);
        return gone;
    }

    /** 用户装的源放在应用私有目录：卸载应用就一起没了，不会被别的应用读到。 */
    private static File installDir(Context ctx) {
        return new File(ctx.getFilesDir(), INSTALL_DIR);
    }

    /** 从地址里猜一个文件名，只在 js 里没写 id 时用得上（那种情况其实会被拒）。 */
    /** 这个 id 的源装过没有 —— 换源时用来决定「要不要顺手装配置里那个 spider」。 */
    static boolean has(Context ctx, String spiderId) {
        return spiderId != null && spiderId.length() > 0 && find(ctx, spiderId) != null;
    }

    /**
     * 从 URL 猜源 id：`…/spiders/dozagonza.js?v=2` → `dozagonza`。
     *
     * <p>和 {@link #guessName} 是同一套取名字的规则，只是把 `.js` 去掉 ——
     * 装进目录的文件名是 `<id>.js`，查的 id 不能带后缀。
     */
    static String idOfUrl(String url) {
        String name = guessName(url);
        if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".js")) {
            name = name.substring(0, name.length() - 3);
        }
        return name;
    }

    private static String guessName(String url) {
        try {
            String path = new java.net.URI(url).getPath();
            if (path == null) return "spider.js";
            int slash = path.lastIndexOf('/');
            String name = slash >= 0 ? path.substring(slash + 1) : path;
            return name.length() > 0 ? name : "spider.js";
        } catch (Throwable t) {
            return "spider.js";
        }
    }

    // ── 扫描 ──────────────────────────────────────────────────────────

    /**
     * 现在只有一个来源：**用户自己装的**。
     *
     * <p>2026-10-19 撤掉了另外两条（热更包 {@code ui/spiders/}、APK assets
     * {@code spiders/}）。留着它们的时候，「这个源哪来的」每加一个源都要解释一遍，
     * 而且三条来源的优先级规则会咬人：用户装了 v1、热更推了 v2，谁赢？
     * 现在答案只有一句：谁装的就是谁的。
     *
     * <p>代价是出厂不带任何源 —— 这是有意的，和「不带任何内容」是同一条线：
     * 壳只提供运行环境，源由用户自己决定装什么。
     */
    private static List<Entry> scan(Context ctx) {
        List<Entry> out = new ArrayList<Entry>();
        File[] files = installDir(ctx).listFiles();
        if (files == null) return out;
        Arrays.sort(files, FILE_BY_NAME);
        for (File f : files) {
            Entry e = fromFile(f, "user");
            if (e != null) out.add(e);
        }
        return out;
    }

    private static Entry find(Context ctx, String spiderId) {
        for (Entry e : scan(ctx)) {
            if (spiderId.equals(e.id)) return e;
        }
        return null;
    }

    private static Entry fromFile(File f, String source) {
        if (!f.isFile() || !f.getName().endsWith(".js")) return null;
        if (f.length() > MAX_SOURCE_BYTES) {
            return rejected(f.getName(), source,
                    "文件太大（" + (f.length() / 1024) + "KB），上限 2MB");
        }
        String src;
        try {
            src = readFully(new FileInputStream(f));
        } catch (Throwable t) {
            return rejected(f.getName(), source, "读不出来：" + t);
        }
        Entry e = parse(src, source, f.getName());
        e.file = f;
        return e;
    }

    // ── 元信息 ────────────────────────────────────────────────────────

    /** 一个源的描述。字段都是「读文件就能知道」的那些，没有一个需要执行。 */
    private static final class Entry {
        String id;
        String name;
        int version;
        String requires = "";
        String source = "apk";
        boolean ok = true;
        String reason = "";
        String fileName = "";
        File file;

        /** 源永远来自磁盘上那个文件（用户装的）。 */
        String readSource(Context ctx) throws IOException {
            if (file == null || !file.isFile()) {
                throw new IOException("源文件不见了：" + fileName);
            }
            return readFully(new FileInputStream(file));
        }
    }

    private static final class Meta {
        String id;
        String name;
        String version;
        String requires;
    }

    private static Entry rejected(String fileName, String source, String reason) {
        Entry e = new Entry();
        e.fileName = fileName;
        e.source = source;
        e.id = baseName(fileName);
        e.name = e.id;
        e.ok = false;
        e.reason = reason;
        Log.w(TAG, "拒绝 spider " + fileName + "（" + source + "）：" + reason);
        return e;
    }

    private static Entry parse(String src, String source, String fileName) {
        Meta m = metaOf(src);
        if (m == null || m.id == null || m.id.length() == 0) {
            return rejected(fileName, source,
                    "spider 元信息读不出来（找不到 __spiderSite 或里面的 id）");
        }
        Entry e = new Entry();
        e.id = m.id;
        e.name = m.name == null || m.name.length() == 0 ? m.id : m.name;
        e.version = parseInt(m.version);
        e.requires = m.requires == null ? "" : m.requires;
        e.source = source;
        e.fileName = fileName;

        String problem = abiProblem(e.requires);
        if (problem != null) {
            e.ok = false;
            e.reason = problem;
            Log.w(TAG, "拒绝 spider " + e.id + "（" + source + "）：" + problem);
        }
        return e;
    }

    /**
     * 从源码里抓 {@code __spiderSite = { … }} 里的四个字面量。
     *
     * <p>找的是<b>对象字面量本身</b>而不是全文：全文搜 {@code id:} 会命中函数体里
     * 的 {@code id: row.id} 这种赋值，抓出来是错的值。
     */
    private static Meta metaOf(String src) {
        int open = siteLiteralAt(src);
        if (open < 0) return null;
        int close = matchingBrace(src, open);
        // 花括号配平失败（压缩过、或者正则里写了不配对的 {）时退化成「从 { 起最多
        // 8KB」：这四个字段按约定都在字面量最前面，够用，而且不会因为一个怪文件
        // 就把整条链路搞挂。
        String body = close > open
                ? src.substring(open, close + 1)
                : src.substring(open, Math.min(src.length(), open + 8192));
        Meta m = new Meta();
        m.id = strField(body, "id");
        m.name = strField(body, "name");
        m.version = numField(body, "version");
        m.requires = strField(body, "requires");
        return m;
    }

    /**
     * 找 {@code __spiderSite = {} 后面那个 '{'} 的位置，跳过注释和字符串。
     *
     * <p><b>为什么不能直接 {@code indexOf("__spiderSite")}</b>：源文件开头常有一段
     * 说明注释，里面本来就会提到这个名字（自检源里就有一句「直接给 __spiderSite
     * 赋值」）。indexOf 命中的是注释里的那个词，接下来找到的 '{' 就成了旁边某个
     * 函数体的开头，抓出来的四个字段全是 null —— 这个 bug 在真实文件上是「好源
     * 被判成元信息读不出来」，而且看不出来为什么。
     */
    private static int siteLiteralAt(String src) {
        final String name = "__spiderSite";
        int i = 0;
        int n = src.length();
        while (i < n) {
            int next = skipNoiseAt(src, i);
            if (next != NOT_NOISE) {
                if (next < 0) return -1;
                i = next;
                continue;
            }
            if (!src.startsWith(name, i)) {
                i++;
                continue;
            }
            int j = i + name.length();
            while (j < n && Character.isWhitespace(src.charAt(j))) j++;
            if (j < n && src.charAt(j) == '=') {
                j++;
                while (j < n && Character.isWhitespace(src.charAt(j))) j++;
                if (j < n && src.charAt(j) == '{') return j;
            }
            i = j;
        }
        return -1;
    }

    /**
     * 从 {@code open} 这个 '{' 起找配对的那个 '}'。
     *
     * <p>注释、字符串、<b>正则字面量</b>都要跳过：源里一个 {@code /title=["']([^"']+)["']/}
     * 这种正则，里面的引号一旦被当成字符串开头，扫描就会被带偏到文件另一边 ——
     * 那不是理论风险，yhdm 的第一个版本就踩在这上面（表现为「好源被判成元信息
     * 读不出来」）。
     */
    private static int matchingBrace(String s, int open) {
        int depth = 0;
        int i = open;
        while (i < s.length()) {
            int next = skipNoiseAt(s, i);
            if (next == NOT_NOISE) {
                char c = s.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) return i;
                }
                i++;
            } else if (next < 0) {
                return -1;
            } else {
                i = next;
            }
        }
        return -1;
    }

    /** {@link #skipNoiseAt} 的返回值：i 处不是注释/字符串/正则，照常处理。 */
    private static final int NOT_NOISE = -2;

    /**
     * 如果 i 处是注释、字符串或正则字面量的开头，返回它结束后的位置；
     * 不是这些就返回 {@link #NOT_NOISE}；一直到文件尾都没结束返回 -1。
     */
    private static int skipNoiseAt(String s, int i) {
        int n = s.length();
        char c = s.charAt(i);
        if (c == '/' && i + 1 < n) {
            char d = s.charAt(i + 1);
            if (d == '/') {
                int nl = s.indexOf('\n', i);
                return nl < 0 ? -1 : nl + 1;
            }
            if (d == '*') {
                int end = s.indexOf("*/", i + 2);
                return end < 0 ? -1 : end + 2;
            }
            if (regexAllowedAfter(s, i)) {
                return skipRegex(s, i);
            }
            return NOT_NOISE;                 // 除号
        }
        if (c == '\'' || c == '"' || c == '`') {
            return skipString(s, i);
        }
        return NOT_NOISE;
    }

    /**
     * 这个 '/' 是正则的开头还是除号？
     *
     * <p>看前一个非空白字符：出现在运算符/括号/逗号/分号后面的一定是正则，
     * 出现在标识符或右括号后面的大概率是除号。JS 本身就得靠上下文判，
     * 我们只需要「别把正则里的引号当字符串」这一条，这个启发式够用。
     */
    private static boolean regexAllowedAfter(String s, int i) {
        int j = i - 1;
        while (j >= 0 && Character.isWhitespace(s.charAt(j))) j--;
        if (j < 0) return true;
        return "([{,;:=!&|?+-*%~^<>".indexOf(s.charAt(j)) >= 0;
    }

    /** 跳过 /…/flags。正则不能跨行，遇到换行就是没闭合。 */
    private static int skipRegex(String s, int start) {
        int i = start + 1;
        boolean inClass = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '[') {
                inClass = true;
            } else if (c == ']') {
                inClass = false;
            } else if (c == '/' && !inClass) {
                return i + 1;
            } else if (c == '\n') {
                return -1;
            }
            i++;
        }
        return -1;
    }

    /** 返回收尾引号之后的位置；没闭合返回 -1。 */
    private static int skipString(String s, int start) {
        char quote = s.charAt(start);
        int i = start + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) return i + 1;
            i++;
        }
        return -1;
    }

    /**
     * 抓 {@code key: 'value'} 这类字符串字面量。
     *
     * <p>前缀要求一个 {@code , { 空白} 之一，是为了避开 {@code bridgeVersion: 1} 命中
     * {@code version} 这种情况 —— 不用后行断言是因为这类正则要在最短路径上跑，
     * 而前缀匹配已经够准。
     */
    private static String strField(String body, String key) {
        Matcher m = Pattern.compile("(?:^|[,{\\s])" + key + "\\s*:\\s*(['\"`])([^'\"`]*)\\1")
                .matcher(body);
        return m.find() ? m.group(2) : null;
    }

    private static String numField(String body, String key) {
        Matcher m = Pattern.compile("(?:^|[,{\\s])" + key + "\\s*:\\s*(\\d+)").matcher(body);
        return m.find() ? m.group(1) : null;
    }

    // ── ABI 门禁 ──────────────────────────────────────────────────────

    /**
     * requires 与 {@link SpiderRuntime#ABI} 比对。返回 null 表示放行。
     *
     * <p>只支持 {@code >=N}：约定里有且只有这一种形状，多支持一种写法就要多维护
     * 一套解析和一套测试。认不出来的一律<strong>拒绝</strong>而不是放行 ——
     * 放行一个我们没读懂的版本声明，等于把「老壳跑新源」的风险留在外面。
     */
    private static String abiProblem(String requires) {
        if (requires == null) return null;
        String text = requires.trim();
        if (text.length() == 0) return null;
        if (!text.startsWith(">=")) {
            return "requires 写法不支持（只支持 >=N）：" + text;
        }
        int need;
        try {
            need = Integer.parseInt(text.substring(2).trim());
        } catch (NumberFormatException e) {
            return "requires 写法不支持（只支持 >=N）：" + text;
        }
        if (SpiderRuntime.ABI < need) {
            return "需要更新版本的应用（需要 abi " + need + "，当前 " + SpiderRuntime.ABI + "）";
        }
        return null;
    }

    // ── 小工具 ────────────────────────────────────────────────────────

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Throwable e) {
            return 0;
        }
    }

    private static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** 读到底，超过上限直接抛 —— 不截断，截断出来的源码是坏源码，报错更难查。 */
    private static String readFully(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_SOURCE_BYTES) {
                    throw new IOException("文件超过 2MB 上限");
                }
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
