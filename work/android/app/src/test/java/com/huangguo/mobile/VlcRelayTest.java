package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * HLS 播放列表改写的回归。
 *
 * ── 这段逻辑为什么必须有测试 ──────────────────────────────────────────
 * 2026-10-18 真机：missav 的视频挂在 {@code surrit.com}，Cloudflare 对 OkHttp
 * 一律 403（{@code [relay] 上游 403 …/playlist.m3u8}），只有 WebView 栈能过。
 * 于是取流改成「借 WebView 取字节」，随之而来的问题全在<b>改写</b>这一段：
 *
 *   · master 里子播放列表是相对路径（{@code 720p/video.m3u8}），
 *   · 子列表里 1147 个分片也是相对路径（{@code video0.jpeg}），
 *   · 而 VLC 拿到的地址是 {@code http://127.0.0.1:PORT/s?u=…}。
 *
 * VLC 会把相对路径挂到<b>那个</b>地址下面解析 —— query 直接丢失，中继只会回
 * 404。写错的表现不是「打不开」，是「能开播、一拖进度就断」，真机上极难二分。
 * 所以这里拿真实响应（fixtures/missav-*.m3u8）把每一条都钉死。
 *
 * ── fixture 来源 ──────────────────────────────────────────────────────
 * fct-210 的真实响应（2026-10-18，Chrome DevTools 里 copy 出来的原文）：
 * <ul>
 *   <li>{@code missav-master-fct210.m3u8} —— master，4 个码率，完整；</li>
 *   <li>{@code missav-media-720p.m3u8} —— 720p 媒体列表，<b>节选</b>：
 *       真实的有 1147 条分片（video0 … video1146），这里只留头三条和末两条，
 *       中间用同样格式补齐没有意义，还会让 fixture 变成 36KB 的噪音。</li>
 * </ul>
 * 分片在服务器上标的是 {@code image/jpeg}（伪装），实际首字节是 TS 同步字节
 * {@code 0x47} —— 这也是 relay 自己判 Content-Type 而不是照抄的原因。
 */
public class VlcRelayTest {

    private static final String DIR = "missav/";

    /** 真实 UUID，用来验证相对路径解析确实拼回了原目录。 */
    private static final String UUID = "18b4bbc2-c802-44a7-ab7a-78531695c531";
    private static final String MASTER = "https://surrit.com/" + UUID + "/playlist.m3u8";
    private static final String PREFIX = "http://127.0.0.1:40603/s?u=";

    private static String fixture(String name) throws Exception {
        InputStream in = VlcRelayTest.class.getClassLoader().getResourceAsStream(DIR + name);
        if (in == null) throw new IllegalStateException("缺少 fixture：" + DIR + name);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 每一行 URI 都必须是中继地址，且解码后能还原成原始绝对地址。 */
    private static java.util.List<String> uris(String rewritten) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (String line : rewritten.split("\n")) {
            String t = line.trim();
            if (t.length() > 0 && !t.startsWith("#")) out.add(t);
        }
        return out;
    }

    /** 回指 URL 剥掉签名参数，留下 u 的 base64 串（decode 断言用）。 */
    private static String u(String line) {
        int k = line.indexOf("&k=");
        return k >= 0 ? line.substring(0, k) : line;
    }

    @Test
    public void master里的子播放列表全部指向中继() throws Exception {
        String[] out = VlcRelay.rewriteM3u8(fixture("missav-master-fct210.m3u8"), MASTER, PREFIX);
        java.util.List<String> lines = uris(out[0]);

        assertEquals("master 里正好 4 条 URItest", 4, lines.size());
        assertEquals("4", out[1]);
        for (String line : lines) {
            assertTrue("没包成中继地址：" + line, line.startsWith(PREFIX));
        }
        assertEquals("360p 的相对路径必须解析回原目录",
                "https://surrit.com/" + UUID + "/360p/video.m3u8",
                VlcRelay.decode(u(lines.get(0)).substring(PREFIX.length())));
        assertEquals("1080p 同理",
                "https://surrit.com/" + UUID + "/1080p/video.m3u8",
                VlcRelay.decode(u(lines.get(3)).substring(PREFIX.length())));
    }

    @Test
    public void 媒体列表的每个分片都指向中继() throws Exception {
        String media = "https://surrit.com/" + UUID + "/720p/video.m3u8";
        String[] out = VlcRelay.rewriteM3u8(fixture("missav-media-720p.m3u8"), media, PREFIX);
        java.util.List<String> lines = uris(out[0]);

        assertEquals("fixture 里 5 条分片", 5, lines.size());
        assertEquals("5", out[1]);
        assertEquals("分片是 .jpeg 伪装的 TS，路径同样要拼回原目录",
                "https://surrit.com/" + UUID + "/720p/video0.jpeg",
                VlcRelay.decode(u(lines.get(0)).substring(PREFIX.length())));
        assertEquals("末尾那条也要改，别只改头几条",
                "https://surrit.com/" + UUID + "/720p/video1146.jpeg",
                VlcRelay.decode(u(lines.get(4)).substring(PREFIX.length())));
    }

    /**
     * {@code #EXT-X-TOKEN} 必须原样留着。
     *
     * 它是站点自己加的标签（标准 HLS 里没有），VLC 会忽略；但如果改写时连带
     * 重排/清洗掉，分片就可能对不上站点那边的校验。这条断言很小，但正因为
     * 它「看起来无用」才最容易被后来的人顺手删掉。
     */
    @Test
    public void 注释与自定义标签原样保留() throws Exception {
        String[] out = VlcRelay.rewriteM3u8(fixture("missav-media-720p.m3u8"),
                "https://surrit.com/" + UUID + "/720p/video.m3u8", PREFIX);
        assertTrue(out[0].contains("#EXT-X-TOKEN=1EWZyAjZ4MjZ1MDZ2MGNhxHMygTNywXMyEjL0IjMuQjNuQjN"));
        assertTrue(out[0].contains("#EXT-X-TARGETDURATION:4"));
        assertTrue("结束标记不能被吃掉", out[0].contains("#EXT-X-ENDLIST"));
        // 每个 #EXTINF 后面必须跟一条改写过的 URI，两个都不能少。
        assertEquals(5, out[0].split("#EXTINF").length - 1);
    }

    /** 已经是绝对地址的不受影响（站点换 CDN 时会混排）。 */
    @Test
    public void 绝对地址原样解析() throws Exception {
        String text = "#EXTM3U\n#EXTINF:4,\nhttps://cdn.example.com/a/b/video0.ts\n";
        String[] out = VlcRelay.rewriteM3u8(text, MASTER, PREFIX);
        java.util.List<String> lines = uris(out[0]);
        assertEquals(1, lines.size());
        assertEquals("https://cdn.example.com/a/b/video0.ts",
                VlcRelay.decode(u(lines.get(0)).substring(PREFIX.length())));
    }

    /**
     * {@code #EXT-X-KEY} 的 URI 也必须走中继。
     *
     * <p>dozagonza 的媒体列表是 {@code #EXT-X-KEY:METHOD=AES-128,URI="enc.key"}，
     * 相对路径。不改写的话 VLC 拿它对着中继地址解析，相对解析丢掉 query 落到
     * {@code http://127.0.0.1:PORT/enc.key}，中继只认 {@code /s?u=}，回 404 ——
     * 症状是能开播但永远黑屏，解密失败不报错。
     */
    @Test
    public void 加密密钥的相对路径也要走中继() throws Exception {
        String text = "#EXTM3U\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"enc.key\",IV=0x00000000000000000000000000000000\n"
                + "#EXTINF:3.000000,\n"
                + "plist0.ts\n"
                + "#EXT-X-ENDLIST\n";
        String base = "https://play.hhuus.com/play/lejBBoPe/index.m3u8";
        String[] out = VlcRelay.rewriteM3u8(text, base, PREFIX);

        String key = out[0].split("\n")[1];
        assertTrue("KEY 行还在：" + key, key.startsWith("#EXT-X-KEY:"));
        assertTrue("METHOD 不能丢：" + key, key.contains("METHOD=AES-128"));
        assertTrue("IV 不能丢：" + key, key.contains("IV=0x00"));
        assertFalse("相对路径必须被换掉：" + key, key.contains("URI=\"enc.key\""));

        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("URI=\"([^\"]+)\"").matcher(key);
        assertTrue("找不到 URI 属性：" + key, m.find());
        String wrapped = m.group(1);
        assertTrue("要包成中继：" + wrapped, wrapped.startsWith(PREFIX));
        assertEquals("解码后必须还原成 CDN 上的绝对地址",
                "https://play.hhuus.com/play/lejBBoPe/enc.key",
                VlcRelay.decode(u(wrapped).substring(PREFIX.length())));

        assertEquals("一条分片 + 一条 KEY，别把 #EXTINF 也算进去", "2", out[1]);
        assertTrue("结束标记不能被吃掉", out[0].contains("#EXT-X-ENDLIST"));
    }

    /** fMP4 的 init segment 用单引号写，同样要包成中继。 */
    @Test
    public void 单引号的URI属性同样处理() {
        String text = "#EXTM3U\n#EXT-X-MAP:URI='init.mp4'\n#EXTINF:4,\nseg1.m4s\n";
        String[] out = VlcRelay.rewriteM3u8(text, "https://a.example/v/index.m3u8", PREFIX);
        assertTrue("单引号也要认：" + out[0], out[0].contains("URI='" + PREFIX));
        assertFalse("原始相对路径不能留：" + out[0], out[0].contains("URI='init.mp4'"));
        assertEquals("EXT-X-MAP 一条 + 分片一条，标签里的 URI 也算改写", "2", out[1]);
    }

    /**
     * 空行和未知标签不能导致「改写条数」虚高。
     *
     * 真机上 {@code [relay] 播放列表改写 N 条} 是判断这条链路好没好用的第一眼
     * 依据（master 应该 4，子列表应该 1147）。数错了会把排查带偏。
     */
    @Test
    public void 空行与注释不计入改写条数() throws Exception {
        String text = "\n\n#EXTM3U\n\n# a comment\n\nvideo0.jpeg\n\n";
        String[] out = VlcRelay.rewriteM3u8(text, MASTER, PREFIX);
        assertEquals("1", out[1]);
        assertEquals(1, uris(out[0]).size());
    }

    /** base64url 编码必须是无 padding、只含 [A-Za-z0-9_-]，否则拼进 query 会被 VLC 弄坏。 */
    @Test
    public void 中继地址的编码能无损还原() {
        String url = "https://surrit.com/" + UUID + "/720p/video1146.jpeg";
        String encoded = VlcRelay.encode(url);
        assertTrue("不能带 = padding：" + encoded, encoded.indexOf('=') < 0);
        assertTrue("不能出现 + 或 /：" + encoded, encoded.indexOf('+') < 0 && encoded.indexOf('/') < 0);
        assertEquals(url, VlcRelay.decode(encoded));
    }

    /**
     * 伪装后缀的判定。
     *
     * 2026-10-19 真机：AGE动漫 的解析站回的是
     * {@code https://jx.wuzhoupai.com:8443/playurl/<hash>…&media_type=jpg}，
     * 里面装的是 MPEG-TS 视频流。VLC 见 .jpg 就当图片，报
     * {@code libvlc demux: Failed to create demuxer 0x0 Unknown}，黑屏且无限重试。
     *
     * 这条用例钉住「哪些地址必须绕中继」—— 它错了的表现是「AGE 永远播不了」，
     * 而那看起来像源的问题，不像路由的问题。
     */
    @Test
    public void 伪装后缀的地址必须绕中继() {
        // 真实形状
        assertTrue(VlcRelay.disguised(
                "https://jx.wuzhoupai.com:8443/playurl/62362434d34a0ccaf59e1d?t=82.30&media_type=jpg"));
        // 单纯换扩展名
        assertTrue(VlcRelay.disguised("https://a.example/x/000.jpg"));
        assertTrue(VlcRelay.disguised("https://a.example/x/000.png"));
        assertTrue(VlcRelay.disguised("https://a.example/x/000.woff2"));
        assertTrue(VlcRelay.disguised("https://a.example/x/000.JPG"));
        // 只是带了 media_type 参数，后缀本身正常
        assertTrue(VlcRelay.disguised("https://a.example/x/play?media_type=jpg"));

        // 正常地址不能被误判 —— 误判的代价是每条流都白绕一跳本地中继
        assertFalse(VlcRelay.disguised("https://a.example/x/index.m3u8"));
        assertFalse(VlcRelay.disguised("https://a.example/x/720p/video.m3u8?sig=abc"));
        assertFalse(VlcRelay.disguised("https://a.example/x/000.ts"));
        assertFalse(VlcRelay.disguised("https://a.example/api/videos/123"));
        assertFalse(VlcRelay.disguised("https://a.example/x/segment.MP4"));
        assertFalse(VlcRelay.disguised(null));
    }

    // ── 本地中继的签名（防同机借用）────────────────────────────────────
    //
    // 中继是无鉴权代理、绑 127.0.0.1，而 Android 各应用共享 loopback：不验签
    // 等于同机任意应用都能借道我们配的代理出口。这里钉住三件事——纯函数层的
    // 签名/验签回环、回指 URL 统一带有效签名、以及起真中继打三种坏请求全 403。

    /** HMAC 链路：自己的 k 能验过；缺失 / 篡改 / 挪到别的 u 上都验不过。 */
    @Test
    public void 签名与验证回环() {
        String a = "https://surrit.com/x/720p/video0.jpeg";
        String b = "https://surrit.com/x/720p/video1.jpeg";
        String k = VlcRelay.signFor(a);
        assertTrue("k 非空：" + k, k.length() > 0);
        assertTrue("自己的 k 能验过", VlcRelay.verifyFor(a, k));
        assertFalse("k 缺失不能过（空串）", VlcRelay.verifyFor(a, ""));
        assertFalse("k 缺失不能过（null）", VlcRelay.verifyFor(a, null));
        assertFalse("挪到别的 u 上不能过（u 与 k 绑定）", VlcRelay.verifyFor(b, k));
        assertFalse("篡改的 k 不能过",
                VlcRelay.verifyFor(a, k.substring(0, k.length() - 2) + "AA"));
    }

    /** 统一出口 relayUrl：地址带签名参数，且这个签名对解码还原出的 u 有效。 */
    @Test
    public void 回指URL都带有效签名() {
        String url = VlcRelay.relayUrl(PREFIX, MASTER);
        assertTrue("仍以 prefix 开头：" + url, url.startsWith(PREFIX));
        int k = url.indexOf("&k=");
        assertTrue("回指 URL 必须带签名：" + url, k >= 0);
        String target = VlcRelay.decode(url.substring(PREFIX.length(), k));
        assertEquals(MASTER, target);
        assertTrue("URL 里的 k 必须对这条 u 有效",
                VlcRelay.verifyFor(target, url.substring(k + 3)));
        // m3u8 改写出来的每一行也必须是这个形状（统一出口，漏一个就是 403 黑屏）
        String[] out = VlcRelay.rewriteM3u8Smart("#EXTM3U\n#EXTINF:4,\nvideo0.jpeg\n", MASTER, PREFIX);
        String line = uris(out[0]).get(0);
        assertTrue("改写行也带签名：" + line, line.contains("&k="));
        assertTrue("改写行的签名对这条 u 有效",
                VlcRelay.verifyFor(VlcRelay.decode(u(line).substring(PREFIX.length())),
                        line.substring(line.indexOf("&k=") + 3)));
    }

    /** 打一个回指请求，返回状态码。 */
    private static int relayStatus(String path) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                new java.net.URL("http://127.0.0.1:" + VlcRelay.ensure().port() + path).openConnection();
        c.setConnectTimeout(2000);
        c.setReadTimeout(2000);
        int code = c.getResponseCode();
        c.disconnect();
        return code;
    }

    /** 起真中继打坏请求：k 缺失 / k 篡改 / 换 u 后旧 k，一律 403，且不碰上游。 */
    @Test
    public void 无签名与坏签名一律403() throws Exception {
        String a = "https://surrit.com/x/720p/video0.jpeg";
        String b = "https://surrit.com/x/720p/video1.jpeg";
        String kA = VlcRelay.signFor(a);
        try {
            assertEquals(403, relayStatus("/s?u=" + VlcRelay.encode(a)));                    // k 缺失
            assertEquals(403, relayStatus("/s?u=" + VlcRelay.encode(a) + "&k=AAAA"));        // k 篡改
            assertEquals(403, relayStatus("/s?u=" + VlcRelay.encode(b) + "&k=" + kA));       // 换 u 后旧 k
        } finally {
            VlcRelay.shutdown();
        }
    }
}
