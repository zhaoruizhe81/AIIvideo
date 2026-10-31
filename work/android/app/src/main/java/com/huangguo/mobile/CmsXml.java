package com.huangguo.mobile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AIIvideo —— 苹果CMS **XML 接口**（`/at/xml/`）的解析。
 *
 * 它跟 JSON 那套是同一个 CMS 的两代接口，字段一一对应，只是包在 XML 里：
 *
 *   JSON                          XML
 *   list[].vod_id                 <video><id>
 *   list[].vod_name               <video><name><![CDATA[…]]>
 *   list[].type_id / type_name     <video><tid> / <type>
 *   list[].vod_pic                <video><pic>          ← **只在详情里有**
 *   list[].vod_remarks            <video><note><![CDATA[…]]>
 *   list[].vod_content            <video><des><![CDATA[…]]>
 *   list[].vod_play_from          <dl><dd flag="…">（多条）
 *   list[].vod_play_url           <dl><dd> 的内容（多条）
 *   class[].type_id / type_name   <class><ty id="…">…</ty>
 *
 * **所以这个类只做一件事：把 XML 翻译成 JSON 那套形状。**
 * 它是「同一个 CMS 的两种接口」的差异收敛点 —— XML 转成 JSON 之后，读它的代码
 * 不必再写第二套字段解析。
 *
 * ⚠️ 2026-10-19：以前读这份 JSON 的是 CmsAdapter（Java 适配器体系的一员），
 *    那套已经整块拆掉。现在唯一的调用点是 `SourceConfig.fetch()`：XML 接口地址
 *    仍能拉下来、翻译成 JSON，但下游没有谁再把「CMS 的 list/class 形状」变成一份
 *    源配置 —— 加源只认本身就是配置的 JSON。这个悬空消费方已单独报给 lead。
 *
 * ── 两个实测出来的坑 ──────────────────────────────────────────────
 *
 * ① **列表里没有封面。** 旧版 XML 接口的 list 只有 id/tid/name/type/dt/note，
 *    <pic> 只在详情响应里。所以：
 *      · 首页和分类页拿不到封面（这是接口的限制，不是我们的 bug）
 *      · 提取封面域名时得**单独探一次详情**，否则一个域名都提不出来
 *
 * ② **多条线路，第一条不一定是直链。** 实测某源：
 *      <dd flag="liangzi">第01集$https://v.lzcdn27.com/share/47fa1e27…#…
 *      <dd flag="lzm3u8">第01集$https://v.lzcdn27.com/20261009/…/index.m3u8#…
 *    第一条是 `/share/` 分享页（要第三方解析），第二条才是直链。
 *    所以这里把多条线路按 `$$$` 拼起来（跟 JSON 那套一致），谁是直链由读它的人挑。
 */
final class CmsXml {

    private static final Pattern VIDEO = Pattern.compile("<video>(.*?)</video>", Pattern.DOTALL);
    /** <dd> 整条匹配：group(1) 是属性段（可缺省），group(2) 是标签体。flags/urls 共用这一条正则。 */
    private static final Pattern DD_FULL = Pattern.compile("<dd([^>]*)>(.*?)</dd>", Pattern.DOTALL);
    /** flag 属性的值，单/双引号都认（老源里两种都出现过）。 */
    private static final Pattern FLAG_ATTR = Pattern.compile("flag\\s*=\\s*([\"'])(.*?)\\1");
    private static final Pattern TY = Pattern.compile("<ty[^>]*id=\"([^\"]*)\"[^>]*>(.*?)</ty>", Pattern.DOTALL);
    private static final Pattern ATTR = Pattern.compile("(\\w+)=\"([^\"]*)\"");

    private CmsXml() {}

    /** 认一下是不是 XML 接口的响应。 */
    static boolean looksLikeXml(String body) {
        if (body == null) return false;
        String head = body.trim();
        if (head.length() > 200) head = head.substring(0, 200);
        return head.startsWith("<?xml") || head.startsWith("<rss") || head.startsWith("<list");
    }

    /**
     * 把 XML 响应转成 JSON CMS 的那套形状。
     * 解析不了就抛 —— 上层会把消息带给用户，不静默返回空。
     */
    static JSONObject parse(String xml) throws Exception {
        if (xml == null || xml.indexOf("<list") < 0) {
            throw new Exception("XML 响应里没有 <list>，这个接口可能不是苹果CMS 的 XML 格式");
        }
        JSONObject out = new JSONObject();
        out.put("code", 1);

        // <list page="1" pagecount="7960" pagesize="20" recordcount="159193">
        int listStart = xml.indexOf("<list");
        int listEnd = xml.indexOf('>', listStart);
        if (listStart >= 0 && listEnd > listStart) {
            Matcher m = ATTR.matcher(xml.substring(listStart, listEnd));
            while (m.find()) {
                out.put(m.group(1), intOf(m.group(2)));
            }
        }
        if (!out.has("page")) out.put("page", 1);
        if (!out.has("pagecount")) out.put("pagecount", 1);

        JSONArray videos = new JSONArray();
        Matcher vm = VIDEO.matcher(xml);
        while (vm.find()) videos.put(video(vm.group(1)));
        out.put("list", videos);

        // <class><ty id="1">电影片</ty>…</class>
        JSONArray classes = new JSONArray();
        Matcher tm = TY.matcher(xml);
        while (tm.find()) {
            JSONObject c = new JSONObject();
            c.put("type_id", intOf(tm.group(1)));
            c.put("type_name", text(tm.group(2)));
            classes.put(c);
        }
        if (classes.length() > 0) out.put("class", classes);

        return out;
    }

    private static JSONObject video(String body) {
        JSONObject v = new JSONObject();
        try {
            v.put("vod_id", intOf(tag(body, "id")));
            v.put("vod_name", tag(body, "name"));
            v.put("type_id", intOf(tag(body, "tid")));
            v.put("type_name", tag(body, "type"));
            v.put("vod_pic", tag(body, "pic"));
            v.put("vod_remarks", tag(body, "note"));
            v.put("vod_content", tag(body, "des"));
            v.put("vod_score", "");                  // XML 接口没有评分
            v.put("vod_play_from", playFlags(body));
            v.put("vod_play_url", playUrls(body));
        } catch (Exception ignored) {}
        return v;
    }

    /**
     * 单遍扫描 <dl> 里的 <dd>，同时产出线路名和地址（$$$ 拼，跟 JSON 那套一致）。
     *
     * <p>必须同源：原来 flags 只认 flag="…" 双引号、urls 认所有 <dd>，两个计数
     * 对不上时线路名和地址就错位了。现在两者从同一条正则的同一轮匹配里来，
     * 两个列表永远等长、按下标一一对应；没有 flag 属性的条目线路名记空串。
     */
    private static String[] ddLines(String body) {
        StringBuilder flags = new StringBuilder();
        StringBuilder urls = new StringBuilder();
        Matcher m = DD_FULL.matcher(body);
        while (m.find()) {
            Matcher f = FLAG_ATTR.matcher(m.group(1));
            String flag = f.find() ? f.group(2) : "";
            if (flags.length() > 0) flags.append("$$$");
            flags.append(flag);
            if (urls.length() > 0) urls.append("$$$");
            urls.append(text(m.group(2)));
        }
        return new String[]{flags.toString(), urls.toString()};
    }

    /** <dl> 里各条 <dd flag="…"> 的 flag，多条用 $$$ 拼（跟 JSON 那套的格式一致）。 */
    private static String playFlags(String body) {
        return ddLines(body)[0];
    }

    /** 各条 <dd> 的内容，多条用 $$$ 拼。 */
    private static String playUrls(String body) {
        return ddLines(body)[1];
    }

    /** 取一个标签的内容。取不到返回空串（不返回 null，上面到处在用它拼字符串）。 */
    private static String tag(String body, String name) {
        Matcher m = Pattern.compile("<" + name + ">(.*?)</" + name + ">", Pattern.DOTALL).matcher(body);
        return m.find() ? text(m.group(1)) : "";
    }

    /**
     * 去掉 CDATA 包裹、解掉几个基本实体、压掉空白。
     *
     * CDATA 是必须处理的：片名和简介里全是它（`<![CDATA[你却爱着一个他]]>`），
     * 不剥掉的话界面上显示的就是「<![CDATA[…]]>」。简介里还常有成片的
     * 全角空格，一并压掉。
     */
    private static String text(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("<![CDATA[")) {
            int end = s.lastIndexOf("]]>");
            s = end > 9 ? s.substring(9, end) : s.substring(9);
        }
        s = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
             .replace("&apos;", "'").replace("&amp;", "&");
        // 全角空格和连续空白：简介里常有一大段缩进用的
        s = s.replace('\u3000', ' ').replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n{2,}", "\n");
        return s.trim();
    }

    private static int intOf(String value) {
        try { return Integer.parseInt(value.trim()); } catch (Exception e) { return 0; }
    }
}
