package com.huangguo.mobile;

import android.util.Log;
import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 更新包的取源。
 *
 * ── 为什么不在代码里写死一个服务器 ──────────────────────────────────
 * 1.0.19 之前清单和 APK 都放在自己的 VPS 上（149.104.21.177，香港）。问题是
 * 那一台一挂、一欠费、一被封，所有人的更新就断了，而且没有第二条路。
 *
 * 现在包发在 GitHub Releases 上，国内直连 github.com 是件碰运气的事，所以清单里
 * 带的是**一组** url：GitHub 原始地址 + 几个 gh 镜像 + 我们自己的 VPS 兜底。
 *
 * ── 两个入口，用途不同 ──────────────────────────────────────────────
 *   fetch()  清单就几十 KB，按顺序试，第一个成功的就用 —— 不用测速，
 *            因为重试一次也就几百毫秒。
 *   pick()   APK 有 60MB，谁都怕下到一半发现这条路最慢。所以并发探一遍
 *            HEAD，谁先回谁上。全部不通就返回第一个，让下载端自己报错。
 */
public final class UpdateMirror {

    private static final String UA = "AIIvideo-Updater";

    private UpdateMirror() {}

    /**
     * 把清单的所有候选都拉一遍，返回 **versionCode 最大**的那份。
     *
     * ── 为什么不「第一个成功就用」──
     * 2026-10-18 实测：发完 1.0.24，gh.dpik.top 那条还在吐 1.0.22（服务端缓存，
     * **加时间戳查询串也绕不过**），另外两条已经是新版。按顺序取第一个的结果是
     * 「发布了，但走这条镜像的用户永远看不到」—— 而且下个周期可能又读到同一份。
     *
     * 清单不到 1KB，全拉一遍挑最新的，代价可以忽略。
     *
     * 全部失败返回 null；任何一份不是合法 JSON 的直接跳过。
     */
    public static String fetch(String urlsJson, int timeoutSeconds) {
        List<String> urls = parse(urlsJson);
        List<String> bodies = new ArrayList<String>(urls.size());
        for (int i = 0; i < urls.size(); i++) {
            String body = read(urls.get(i), timeoutSeconds);
            if (body != null) bodies.add(body);
        }
        return newestOf(bodies);
    }

    /**
     * 从若干份候选清单里挑 versionCode 最大的那份。
     *
     * 抽成独立方法是为了能单测 —— 这一层出错的后果很隐蔽：不会报错，只会
     * 让一部分用户永远收不到更新。网络那部分（read）交给真机验。
     */
    static String newestOf(List<String> bodies) {
        String best = null;
        long bestCode = -1;
        for (int i = 0; i < bodies.size(); i++) {
            String body = bodies.get(i);
            if (body == null) continue;
            String trimmed = body.trim();
            if (trimmed.length() <= 2 || !trimmed.startsWith("{")) continue;
            long code;
            try {
                code = new org.json.JSONObject(trimmed).optLong("versionCode", 0L);
            } catch (Throwable th) {
                continue;
            }
            // versionCode 缺失或 <=0 的不参选：有的镜像在 200 状态下吐 JSON 错误页
            // （限流、上游故障），让它当选会把「没有新版本」坐实，还会顺着 check()
            // 的 clearCandidate() 把本地待更新的清单抹掉。
            if (code <= 0) continue;
            if (code > bestCode) {
                bestCode = code;
                best = body;
            }
        }
        return best;
    }

    /** 一行人的异常摘要：类名 + message（可空），给 [update] 日志用。 */
    private static String brief(Throwable e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : (": " + message));
    }

    /**
     * 并发下载前 256KB，返回**真实下载最快**的那条。全部失败返回第一个
     * （让下载端给出真实错误）。
     *
     * 为什么不用 HEAD 探速：GitHub 的 release asset 在边缘节点回 302 很快，
     * HEAD 一测全是 30ms，但真正的下行被限得厉害（实测 52KB/s，60MB 要下
     * 二十分钟）；gh 镜像反过来 —— TTFB 慢一点，带宽却够。只探头会把最慢的
     * 那条选出来，所以这里直接拉一段数据来计时。
     */
    private static final int PROBE_BYTES = 262144;

    public static String pick(String urlsJson) {
        final List<String> urls = parse(urlsJson);
        if (urls.isEmpty()) return "";
        if (urls.size() == 1) return urls.get(0);

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, urls.size()));
        try {
            List<Future<Long>> probes = new ArrayList<Future<Long>>();
            for (int i = 0; i < urls.size(); i++) {
                final String url = urls.get(i);
                probes.add(pool.submit(new Callable<Long>() {
                    @Override public Long call() {
                        long started = System.currentTimeMillis();
                        HttpURLConnection conn = null;
                        try {
                            conn = open(url, 8, "bytes=0-" + (PROBE_BYTES - 1));
                            if (conn == null) return Long.MAX_VALUE;
                            int code = conn.getResponseCode();
                            // 200 是服务器不支持 Range（那就照读），206 是正常分段。
                            if (code != 200 && code != 206) return Long.MAX_VALUE;
                            InputStream in = conn.getInputStream();
                            byte[] buf = new byte[8192];
                            int total = 0;
                            int read;
                            while (total < PROBE_BYTES) {
                                read = in.read(buf);
                                if (read <= 0) break;
                                total += read;
                            }
                            if (total <= 0) return Long.MAX_VALUE;
                            return System.currentTimeMillis() - started;
                        } catch (Throwable e) {
                            // 测速失败是常态（挂掉的镜像排到最后），但一个都不留痕的话，
                            // 「四条全挂、永远选中第一条」这类故障没人能对账。
                            Log.d("AIIvideo", "[update] 测速失败 " + url + "：" + brief(e));
                            return Long.MAX_VALUE;
                        } finally {
                            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
                        }
                    }
                }));
            }
            long best = Long.MAX_VALUE;
            String bestUrl = urls.get(0);
            for (int i = 0; i < probes.size(); i++) {
                try {
                    long cost = probes.get(i).get(6, TimeUnit.SECONDS).longValue();
                    if (cost < best) { best = cost; bestUrl = urls.get(i); }
                } catch (Throwable ignored) {}
            }
            return bestUrl;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 打开连接，**手动跟重定向**。
     *
     * HttpURLConnection 的 setInstanceFollowRedirects(true) 只跟同主机、同协议的
     * 跳转。GitHub 的 release 附件会 302 到 objects.githubusercontent.com（换主机），
     * 它当场罢工 —— 拿到的是 302，被当成「拉不到」。这就是「清单明明在，App 却说
     * 服务器上没有发布信息」的原因，也是 pick 永远选中慢源的原因。
     */
    static HttpURLConnection open(String url, int timeoutSeconds, String range) throws Exception {
        String current = url;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(timeoutSeconds * 1000);
            conn.setReadTimeout(timeoutSeconds * 1000);
            conn.setRequestProperty("User-Agent", UA);
            if (range != null) conn.setRequestProperty("Range", range);
            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.length() == 0) return null;
                current = new URL(new URL(current), location).toString();
                continue;
            }
            return conn;
        }
        return null;
    }

    /** 拉一段文本。清单用，超过 1MB 直接截断（正常只有 1~2KB）。 */
    public static String read(String url, int timeoutSeconds) {
        HttpURLConnection conn = null;
        try {
            conn = open(url, timeoutSeconds, null);
            if (conn == null) return null;
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream(16384);
            byte[] buf = new byte[8192];
            int read;
            int total = 0;
            while ((read = in.read(buf)) > 0) {
                out.write(buf, 0, read);
                total += read;
                if (total > 1048576) break;
            }
            return new String(out.toByteArray(), "UTF-8");
        } catch (Throwable e) {
            // 静默失败是这条通道的历史事故根源（见 docs/热更新说明.md），至少留个痕迹。
            Log.d("AIIvideo", "[update] 清单地址拉取失败 " + url + "：" + brief(e));
            return null;
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

    /**
     * 把 APK 下到 dest，边下边算 sha256，最后校验。进度通过**反射**回调：
     * 传进来的是 UpdateManager$Progress（smali 里的接口，Java 编译期看不到它）。
     *
     * @param sink 有 onPercent(int) 的对象，可以为 null
     * @return dest（校验通过时）
     */
    public static java.io.File download(String url, java.io.File dest, Object sink, String digest)
            throws Exception {
        HttpURLConnection conn = open(url, 30, null);
        if (conn == null) throw new java.io.IOException("连不上下载地址");
        java.io.FileOutputStream out = null;
        java.io.InputStream in = null;
        try {
            int code = conn.getResponseCode();
            if (code != 200 && code != 206) throw new java.io.IOException("HTTP " + code);
            long total = conn.getContentLengthLong();
            if (total <= 0) total = conn.getContentLength();
            in = conn.getInputStream();
            out = new java.io.FileOutputStream(dest);
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            long done = 0;
            int lastPct = -1;
            int read;
            while ((read = in.read(buf)) > 0) {
                out.write(buf, 0, read);
                md.update(buf, 0, read);
                done += read;
                if (total > 0) {
                    int pct = (int) (done * 100 / total);
                    if (pct != lastPct) {
                        lastPct = pct;
                        emit(sink, pct);
                    }
                }
            }
            out.flush();
            out.close();
            out = null;
            in.close();
            in = null;

            String want = null;
            if (digest != null && digest.startsWith("sha256:")) want = digest.substring(7).toLowerCase();
            if (want != null) {
                StringBuilder hex = new StringBuilder();
                byte[] sum = md.digest();
                for (int i = 0; i < sum.length; i++) {
                    String piece = Integer.toHexString(sum[i] & 0xff);
                    if (piece.length() == 1) hex.append('0');
                    hex.append(piece);
                }
                // 常量时间比较，不给逐字节猜哈希留计时侧信道。
                if (!java.security.MessageDigest.isEqual(
                        want.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        hex.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                    dest.delete();
                    throw new java.io.IOException("下载包的 sha256 不对，可能被截断了");
                }
            }
            emit(sink, 100);
            return dest;
        } finally {
            if (out != null) try { out.close(); } catch (Throwable ignored) {}
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
            try { conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

    /** 反射调 onPercent(int)。拿不到就当没有进度条，不影响下载。 */
    private static void emit(Object sink, int pct) {
        if (sink == null) return;
        try {
            java.lang.reflect.Method method = sink.getClass().getMethod("onPercent", int.class);
            method.invoke(sink, Integer.valueOf(pct));
        } catch (Throwable ignored) {}
    }

    /** JSON 数组 → 只留 https 的。也容忍传单个 url 字符串。 */
    static List<String> parse(String urlsJson) {
        List<String> out = new ArrayList<String>();
        if (urlsJson == null || urlsJson.length() == 0) return out;
        try {
            JSONArray array = new JSONArray(urlsJson);
            for (int i = 0; i < array.length(); i++) {
                String url = array.optString(i, "");
                if (url != null && url.startsWith("https://")) out.add(url);
            }
        } catch (Throwable e) {
            if (urlsJson.startsWith("https://")) out.add(urlsJson.trim());
        }
        return out;
    }
}
