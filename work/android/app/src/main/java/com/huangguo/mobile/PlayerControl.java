package com.huangguo.mobile;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 播放器要给原生帮忙的几件事：亮度、清晰度变体、外置播放器、内置播放器（libVLC）。
 *
 * ── 为什么这些必须走原生 ────────────────────────────────────────────
 * <ul>
 *   <li><b>亮度</b>：WebView 里的页面改不了系统/窗口亮度。CSS 蒙一层黑只能变暗，
 *       而要「像系统播放器那样上下滑调亮」，得动 Window 的 screenBrightness。</li>
 *   <li><b>清晰度</b>：主 m3u8 里那张码率表只有取回文本才看得见，而页面 CSP 的
 *       connect-src 是 'none' —— 拿不到文本就列不出「有哪些清晰度」。</li>
 *   <li><b>外置/内置播放器</b>：WebView 解不了的编码（HEVC、10bit、冷门封装）
 *       只有设备自己的解码链能放。</li>
 * </ul>
 *
 * ── 为什么单独一个类 ────────────────────────────────────────────────
 * 2026-10-18 之前它在 {@code UserSource}（2492 行）里。这块只跟播放器和
 * 窗口打交道，和源配置、代理核心没有一个共享字段。
 */
final class PlayerControl {

    private PlayerControl() {}

    /** 桥上的前缀。MainActivity 的控制面入口会把它补回来。 */
    static final String PREFIX = "__player:";

    /** 亮度下限。0 会把屏幕熄掉，用户再想调回来就得靠运气 —— 留一档看得见。 */
    private static final float MIN_BRIGHTNESS = 0.05f;


    static JSONObject handle(Context context, String body, JSONObject out) {
        try {
            String action = body;
            String query = "";
            int mark = body.indexOf('?');
            if (mark >= 0) { action = body.substring(0, mark); query = body.substring(mark + 1); }

            if ("brightness".equals(action)) return brightness(context, UserSource.param(query, "value"), out);
            if ("variants".equals(action)) return variants(UserSource.param(query, "url"), out);
            // 交给系统播放器（MX / VLC / 自带图库）：WebView 解不了的编码
            // （HEVC、10bit、冷门封装）只有设备自己的解码链能放，这条路是最后的兜底。
            if ("external".equals(action)) return externalPlayer(context, UserSource.param(query, "url"), out);
            // 内置播放器（libVLC）。这是「别的手机放不了」的正面解法：解码链搬进
            // App，不再跟着 WebView 版本和设备的硬解能力走。
            if ("native".equals(action)) return nativePlayer(context, UserSource.param(query, "payload"), out);
            // 从内置播放器回来时取一次现场（位置/是否播完），取完就清。
            if ("last".equals(action)) return lastNativeSession(context, out);
            // 这台设备上到底有没有内置播放器可用（APK 只带了 arm64-v8a）。
            if ("capability".equals(action)) return nativeCapability(context, out);
            return UserSource.fail(out, "不认识的播放指令：" + action);
        } catch (Throwable e) {
            return UserSource.fail(out, "播放指令失败：" + AiiHttp.describe(e));
        }
    }
    /**
     * 窗口亮度。0~1 是绝对亮度，-1 是「交回系统」，不传值是读当前值。
     *
     * 为什么不用 CSS 蒙一层黑：那只能变暗，而用户滑到顶时期待的是**变亮** ——
     * 系统播放器就是这么做的，靠的是 Window.screenBrightness。
     *
     * 必须回主线程：改窗口属性是 View 系统的事，在桥的工作线程上改，
     * Android 会直接抛（而且抛在用户手指正划着的时候）。
     *
     * 读的时候分两层：窗口上没覆盖过（screenBrightness < 0）就退到系统亮度
     * （0~255 → 0~1）—— 不这样的话，用户第一次上下滑会从我们瞎猜的 50% 起跳，
     * 屏幕先闪一下。读 Settings.System 不需要权限。
     */
    static JSONObject brightness(Context context, String raw, JSONObject out) throws Exception {
        MainActivity host = MainActivity.host;
        if (host == null) return UserSource.fail(out, "Activity 不在，亮度改不了");

        if (raw.length() == 0) {
            float current = -1f;
            boolean system = false;
            try {
                android.view.Window window = host.getWindow();
                if (window != null) current = window.getAttributes().screenBrightness;
            } catch (Throwable ignored) {}
            if (current < 0) {
                system = true;
                try {
                    int value = android.provider.Settings.System.getInt(
                            context.getContentResolver(),
                            android.provider.Settings.System.SCREEN_BRIGHTNESS, 128);
                    current = Math.max(MIN_BRIGHTNESS, Math.min(1f, value / 255f));
                } catch (Throwable e) {
                    current = 0.5f;                       // 问不出来就当中等亮度，别报错
                }
            }
            out.put("ok", true);
            out.put("value", current);
            out.put("system", system);
            return out;
        }

        final float value;
        if ("-1".equals(raw)) {
            value = -1f;                                   // 交回系统
        } else {
            float parsed;
            try {
                parsed = Float.parseFloat(raw);
            } catch (Exception e) {
                return UserSource.fail(out, "亮度值看不懂：" + raw);
            }
            value = Math.max(MIN_BRIGHTNESS, Math.min(1f, parsed));
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try {
                    android.view.Window window = host.getWindow();
                    if (window == null) return;
                    android.view.WindowManager.LayoutParams attrs = window.getAttributes();
                    attrs.screenBrightness = value;
                    window.setAttributes(attrs);
                } catch (Throwable e) {
                    UserSource.log("设置亮度失败：" + e);
                }
            }
        });
        out.put("ok", true);
        out.put("value", value);
        return out;
    }
    /**
     * 把主 m3u8 拆成清晰度档位。页面 CSP 的 connect-src 是 'none'，只能在这儿取文本。
     *
     * 两种列表都要认：
     *   · 主列表（有 #EXT-X-STREAM-INF）→ 每一档一行：下一行是它的播放地址（可能是相对路径）
     *   · 媒体列表（只有 #EXTINF + .ts）→ 没有档位，返回空 items。**这不是错误** ——
     *     站上本来就只有一路清晰度，前端据此说「这个源只有一路」，而不是假装能选。
     *
     * 标签取 RESOLUTION 的宽（1080x608 → 1080P），因为那是 CDN 自己声明的东西；
     * 另外把 BANDWIDTH 一起放进 note —— 实测有站写 RESOLUTION=1080x608 配
     * BANDWIDTH=800000，标成 1080P 是它自己写的，我们只负责照抄并把它摊开。
     */
    static JSONObject variants(String url, JSONObject out) throws Exception {
        if (url.length() == 0) return UserSource.fail(out, "没有提供播放地址");
        if (!url.startsWith("https://")) return UserSource.fail(out, "只认 https 的播放地址");
        if (url.indexOf(".m3u8") < 0) return UserSource.fail(out, "不是 m3u8：" + head(url, 60));

        String text = new String(AiiHttp.getRaw(url, null, 12), "UTF-8");
        JSONArray items = new JSONArray();
        String[] lines = text.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue;
            String attrs = line.substring("#EXT-X-STREAM-INF:".length());
            // 地址在**下一行非空非注释**里 —— 中间允许插别的标签，所以往下找。
            String uri = "";
            for (int j = i + 1; j < lines.length; j++) {
                String next = lines[j].trim();
                if (next.length() == 0 || next.startsWith("#")) continue;
                uri = next;
                break;
            }
            if (uri.length() == 0) continue;

            String resolution = attr(attrs, "RESOLUTION");
            String bandwidth = attr(attrs, "BANDWIDTH");
            String name = qualityLabel(resolution, bandwidth);
            JSONObject item = new JSONObject();
            item.put("name", name);
            item.put("note", qualityNote(resolution, bandwidth));
            item.put("url", absolute(url, uri));
            items.put(item);
        }
        out.put("ok", true);
        out.put("items", items);
        // 打出来：出问题时「服务端到底给了几档」是最先要问的问题。
        UserSource.log("清晰度探测 " + head(url, 48) + " → " + items.length() + " 档");
        return out;
    }
    /** `RESOLUTION=1920x1080,BANDWIDTH=2000000` 里取一个属性。 */
    static String attr(String attrs, String key) {
        String[] parts = attrs.split(",");
        for (int i = 0; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq < 0) continue;
            if (key.equalsIgnoreCase(parts[i].substring(0, eq).trim())) {
                return parts[i].substring(eq + 1).trim().replace("\"", "");
            }
        }
        return "";
    }








    static String qualityLabel(String resolution, String bandwidth) {
        if (resolution.length() > 0) {
            int x = resolution.toLowerCase().indexOf('x');
            if (x > 0) {
                try {
                    int width = Integer.parseInt(resolution.substring(0, x).trim());
                    if (width >= 720) return width + "P";
                    if (width > 0) return width + "宽";
                } catch (Exception ignored) {}
            }
        }
        if (bandwidth.length() > 0) {
            try {
                return Math.round(Integer.parseInt(bandwidth) / 10000f) / 100f + " Mbps";
            } catch (Exception ignored) {}
        }
        return "默认";
    }


    static String qualityNote(String resolution, String bandwidth) {
        StringBuilder sb = new StringBuilder();
        if (resolution.length() > 0) sb.append(resolution);
        if (bandwidth.length() > 0) {
            if (sb.length() > 0) sb.append(" · ");
            try {
                sb.append(Math.round(Integer.parseInt(bandwidth) / 1000f)).append(" kbps");
            } catch (Exception e) {
                sb.append(bandwidth).append(" bps");
            }
        }
        return sb.toString();
    }
    /** 相对地址按主列表所在目录展开（实测 `2000k/hls/index.m3u8` 就是这么给的）。 */
    static String absolute(String base, String uri) {
        if (uri.startsWith("http://") || uri.startsWith("https://")) return uri;
        try {
            java.net.URI b = new java.net.URI(base);
            return b.resolve(uri).toString();
        } catch (Exception e) {
            int slash = base.lastIndexOf('/');
            return slash < 0 ? uri : base.substring(0, slash + 1) + uri;
        }
    }
    /** 只截前 N 个字符给人看（日志和报错里贴整条地址会把有用的部分挤掉）。 */
    static String head(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
    /**
     * 用外部播放器打开一个视频地址。
     *
     * 为什么需要：这个播放器是 WebView 里的 <video>，解码能力跟着 WebView 走。
     * 同一条 m3u8 在 A 手机上能放、B 手机上 MediaError 4（SRC_NOT_SUPPORTED），
     * 差别就在设备/编码：HEVC、10bit、或者系统 WebView 没带那个解码器。
     * 这种时候把地址交出去，让 MX Player / VLC 用自己的解码链放，比报错有用。
     */
    static JSONObject externalPlayer(Context context, String url, JSONObject out) throws Exception {
        if (url == null || url.length() == 0) return UserSource.fail(out, "没有可播放的地址");
        if (!url.startsWith("http://") && !url.startsWith("https://")) return UserSource.fail(out, "只能打开 http(s) 地址");
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.parse(url), "video/*");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (Exception e) {
            // 没有能接管 video/* 的应用：退一步不限定类型再试一次
            Intent fallback = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(fallback);
            } catch (Exception again) {
                return UserSource.fail(out, "这台设备没有能打开视频的应用");
            }
        }
        out.put("ok", true);
        return out;
    }
    /**
     * 拉起内置播放器（libVLC）。
     *
     * payload 是一份 JSON：{url, id, episode, title, cover, source, start, referer,
     * ua, decode, cache, rate}。字段全部可选，缺的按默认值走 —— 页面那边写这份
     * payload 的地方就一处（feed-player.js 的 nativePayload()）。
     *
     * 返回里带一个 fallback 标记：这台设备上没有内置播放器（32 位包没打进 so，
     * 或者装的是精简过的包）时，页面要能退回网页播放器而不是什么都不发生。
     */
    static JSONObject nativePlayer(Context context, String raw, JSONObject out) throws Exception {
        if (raw == null || raw.length() == 0) return UserSource.fail(out, "内置播放器没有收到参数");
        if (!VlcPlayerActivity.available(context)) {
            out.put("ok", false);
            out.put("fallback", true);
            out.put("error", "这个安装包没带内置播放器（只打了 " + VlcPlayerActivity.abi()
                    + " 的 so），可以继续用网页播放器");
            return out;
        }
        JSONObject config = new JSONObject(raw);
        String url = config.optString("url", "");
        // 刷视频（FEED_LIST）形态首条可以不带 url（H-1：地址由原生 __hgNativeEpisode(id,1)
        // 现取），所以 url 必填校验要放行非空 feedList。真正的空列表保护在
        // VlcPlayerActivity.readPayloadShape：feedList 过滤后为空会退回单集。
        org.json.JSONArray feedList = config.optJSONArray("feedList");
        boolean hasFeedList = feedList != null && feedList.length() > 0;
        if (url.length() == 0 && !hasFeedList) return UserSource.fail(out, "没有可播放的地址");
        VlcPlayerActivity.start(context, config);
        out.put("ok", true);
        return out;
    }
    /** 内置播放器退出时写下的那一次现场。读到就清，避免同一条记录被算两次。 */
    static JSONObject lastNativeSession(Context context, JSONObject out) throws Exception {
        JSONObject record = VlcPlayerActivity.takeLastSession(context);
        out.put("ok", true);
        out.put("session", record == null ? JSONObject.NULL : record);
        return out;
    }
    /**
     * `__player:` 的分发。两条指令：
     *
     *   brightness?value=0.42   0~1 的窗口亮度；-1 表示交回系统
     *   variants?url=<m3u8>     把主列表里的清晰度档位列出来（页面自己 fetch 不了）
     */






    static JSONObject nativeCapability(Context context, JSONObject out) throws Exception {
        out.put("ok", true);
        out.put("native", VlcPlayerActivity.available(context));
        out.put("abi", VlcPlayerActivity.abi());
        return out;
    }
}
