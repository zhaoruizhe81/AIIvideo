package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * 封面信任三档（方案 §4.1）与硬性拒绝地板。
 *
 * <p>核心不变量：**名单只能收紧地板，不能洞穿它**。allowedHosts 写什么、
 * hostRewrites 重写成什么，字面 IP / 内网 / .local / 带 fragment / 超长主机
 * 一律不放行 —— 白名单是 SSRF 的闸，地板是闸下面的地基。
 */
public class CoverTierTest {

    private static SiteProfile profileWith(String... hosts) throws Exception {
        JSONArray allowed = new JSONArray();
        for (String h : hosts) allowed.put(h);
        JSONObject cover = new JSONObject()
                .put("mode", "plain")
                .put("transformation", "AES/CBC/PKCS5Padding")
                .put("allowedHosts", allowed);
        return new SiteProfile(new JSONObject(), new JSONObject()
                .put("origin", "https://site.example")
                .put("cover", cover));
    }

    // ── 三档判定 ───────────────────────────────────────────────────────

    @Test
    public void 名单内是A档声明信任() throws Exception {
        SiteProfile profile = profileWith("pic.drleao.cn");
        SiteProfile.CoverTarget target = profile.coverTarget("https://pic.drleao.cn/a.jpg");
        assertEquals(SiteProfile.CoverTier.DECLARED, target.tier);
        assertEquals("pic.drleao.cn", target.host);
    }

    @Test
    public void 名单外是C档未知_由调用方决定裸拉() throws Exception {
        SiteProfile profile = profileWith("pic.drleao.cn");
        SiteProfile.CoverTarget target = profile.coverTarget("https://pic.nhqktz.cn/a.jpg");
        assertEquals(SiteProfile.CoverTier.UNKNOWN, target.tier);
        // C 档本身不抛 —— coverUrl 才抛。coverData 拿着 UNKNOWN 去裸拉。
        assertEquals("pic.nhqktz.cn", target.host);
    }

    @Test
    public void B档经观测集谓词放行_单参coverUrl不感知观测集() throws Exception {
        SiteProfile profile = profileWith("pic.drleao.cn");
        // 双参：观测集说这台主机信 → 放行（地址与归一化后一致）。
        assertEquals("https://pic.nhqktz.cn/a.jpg",
                profile.coverUrl("https://pic.nhqktz.cn/a.jpg", host -> host.equals("pic.nhqktz.cn")));
        // 谓词说不信 → 照旧拒。
        try {
            profile.coverUrl("https://pic.nhqktz.cn/a.jpg", host -> false);
            fail("观测集没放行的 C 档必须被拒");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("pic.nhqktz.cn"));
        }
        // 单参版（老调用方/老用例的口径）不查观测集，照样拒。
        try {
            profile.coverUrl("https://pic.nhqktz.cn/a.jpg");
            fail("单参 coverUrl 不该感知观测集");
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Test
    public void 档位判定用小写主机() throws Exception {
        SiteProfile profile = profileWith("pic.drleao.cn");
        assertEquals(SiteProfile.CoverTier.DECLARED,
                profile.coverTarget("https://PIC.DRLEAO.CN/a.jpg").tier);
        assertEquals("pic.drleao.cn", profile.coverTarget("https://PIC.DRLEAO.CN/a.jpg").host);
    }

    // ── 硬性拒绝地板（任何来源都不放行）────────────────────────────────

    private void assertRejected(String url) throws Exception {
        // 白名单内容无关紧要 —— 硬拒发生在名单匹配之前，地基不靠名单撑着。
        SiteProfile profile = profileWith("pic.example.com");
        try {
            profile.coverTarget(url);
            fail("必须被硬拒：" + url);
        } catch (IllegalArgumentException e) {
            assertTrue("报错要带上原串或主机：" + e.getMessage(), e.getMessage().contains("无效封面地址"));
        }
    }

    @Test
    public void 非https拒绝() throws Exception {
        assertRejected("http://pic.example.com/a.jpg");
    }

    @Test
    public void 带userinfo拒绝() throws Exception {
        assertRejected("https://user:pass@pic.example.com/a.jpg");
    }

    @Test
    public void 字面IP点分十进制拒绝() throws Exception {
        assertRejected("https://192.168.1.1/a.jpg");
        assertRejected("https://8.8.8.8/a.jpg");
        // 拒掉的不只是内网 —— 公网字面 IP 一样拒（我们要的是域名，不是地址）。
    }

    @Test
    public void 字面IP变体拒绝() throws Exception {
        assertRejected("https://2130706433/a.jpg");          // 纯数字整数
        assertRejected("https://0x7f.0.0.1/a.jpg");          // 十六进制段
        assertRejected("https://[::1]/a.jpg");               // IPv6 loopback
    }

    @Test
    public void isLiteralIp的判定面() {
        assertTrue(SiteProfile.isLiteralIp("192.168.1.1"));
        assertTrue(SiteProfile.isLiteralIp("2130706433"));
        assertTrue(SiteProfile.isLiteralIp("0x7f.0.0.1"));
        assertTrue(SiteProfile.isLiteralIp("[::1]"));
        assertFalse(SiteProfile.isLiteralIp("pic.nhqktz.cn"));
        assertFalse(SiteProfile.isLiteralIp("123.cn"));
        assertFalse(SiteProfile.isLiteralIp("p1c.cn"));
    }

    @Test
    public void local后缀拒绝() throws Exception {
        assertRejected("https://nas.local/a.jpg");
        assertRejected("https://printer.sub.local/a.jpg");
    }

    @Test
    public void 带fragment拒绝() throws Exception {
        assertRejected("https://pic.example.com/a.jpg#x");
    }

    @Test
    public void 超长主机拒绝() throws Exception {
        StringBuilder host = new StringBuilder("a");
        while (host.length() < 260) host.append(".bbbbbbbbbbbb");
        assertRejected("https://" + host + "/a.jpg");
    }

    @Test
    public void hostRewrites出来的主机也过硬拒() throws Exception {
        // 配置是可信的一方，但地板不认人情：重写到字面 IP 的写法照样拒。
        JSONArray allowed = new JSONArray().put("pic.drleao.cn");
        JSONObject cover = new JSONObject()
                .put("mode", "plain")
                .put("transformation", "AES/CBC/PKCS5Padding")
                .put("allowedHosts", allowed)
                .put("hostRewrites", new JSONObject().put("pic.drleao.cn", "10.0.0.5"));
        SiteProfile profile = new SiteProfile(new JSONObject(), new JSONObject()
                .put("origin", "https://site.example")
                .put("cover", cover));
        try {
            profile.coverTarget("https://pic.drleao.cn/a.jpg");
            fail("重写出来的字面 IP 必须被拒");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("无效封面地址"));
        }
    }
}
