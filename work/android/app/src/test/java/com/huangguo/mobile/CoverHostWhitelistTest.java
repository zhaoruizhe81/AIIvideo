package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * 封面白名单：精确主机 + 单层通配。
 *
 * <p>为什么要有通配（2026-10-31）：同一个图床族在 51cg / mrds66 / 黄果之间共用，
 * 二级域六个字符随机换 —— drleao / gyezbp / sugfew / nihmcl / fisawck …
 * 每换一次，没跟上的那批设备看到的就是「这个源没有封面」，而报错只有一句
 * 「无效封面域名」。补一个域名撑两周，加通配才是不用再管的那种修法。
 *
 * <p>边界同样是重点：通配只能吃**一层**。`pic.*.cn` 放行 `pic.sugfew.cn`，
 * 但不能放行 `pic.a.b.cn`，更不能放行 `evilpic.sugfew.cn` —— 白名单是 SSRF 的
 * 那道闸，放宽一档要能说出放宽到哪儿为止。
 */
public class CoverHostWhitelistTest {

    // ── 匹配规则本身 ───────────────────────────────────────────────────

    @Test
    public void 精确条目忽略大小写() {
        assertTrue(SiteProfile.hostMatches("pic.drleao.cn", "pic.drleao.cn"));
        assertTrue(SiteProfile.hostMatches("PIC.DRLEAO.CN", "pic.drleao.cn"));
        assertFalse(SiteProfile.hostMatches("pic.drleao.cn", "pic.sugfew.cn"));
    }

    @Test
    public void 单层通配放过同族主机() {
        assertTrue(SiteProfile.hostMatches("pic.*.cn", "pic.sugfew.cn"));
        assertTrue(SiteProfile.hostMatches("pic.*.cn", "pic.nihmcl.cn"));
        assertTrue(SiteProfile.hostMatches("pic.*.cn", "PIC.GYEZBP.CN"));
    }

    @Test
    public void 通配不跨层也不跨前缀() {
        // 中间那段带点 = 多了一层，不算同族。
        assertFalse(SiteProfile.hostMatches("pic.*.cn", "pic.a.b.cn"));
        // 前缀必须完整对齐，否则 pic.*.cn 会顺手放行 evil-pic.x.cn 这类仿冒。
        assertFalse(SiteProfile.hostMatches("pic.*.cn", "evilpic.sugfew.cn"));
        assertFalse(SiteProfile.hostMatches("pic.*.cn", "pic.sugfew.cn.evil.com"));
        // 星号什么都不吃的情况不算匹配。
        assertFalse(SiteProfile.hostMatches("pic.*.cn", "pic..cn"));
        // 档位不对（.com 不是 .cn）。
        assertFalse(SiteProfile.hostMatches("pic.*.cn", "pic.sugfew.com"));
    }

    @Test
    public void 多个星号一律不匹配() {
        // 不支持两层通配，写了就当这条白名单失效 —— 失效比误放行好。
        assertFalse(SiteProfile.hostMatches("pic.*.*.cn", "pic.a.b.cn"));
        assertFalse(SiteProfile.hostMatches("*.example.com", "a.example.com"));
    }

    // ── 配置校验：坏模式要在加载源的时候就被拦下 ────────────────────────

    private static SiteProfile profileWithHosts(String... hosts) throws Exception {
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

    @Test
    public void 通配写法能通过校验并被用上() throws Exception {
        SiteProfile profile = profileWithHosts("pic.drleao.cn", "pic.*.cn");
        // 精确条目照旧
        assertEquals("https://pic.drleao.cn/upload/a.jpg",
                profile.coverUrl("https://pic.drleao.cn/upload/a.jpg"));
        // 通配条目：没在白名单里逐条列过的主机也放行
        assertEquals("https://pic.sugfew.cn/upload/a.jpg",
                profile.coverUrl("https://pic.sugfew.cn/upload/a.jpg"));
    }

    @Test
    public void 白名单外的域名照旧被拒并带上原文() throws Exception {
        SiteProfile profile = profileWithHosts("pic.*.cn");
        try {
            profile.coverUrl("https://evil.example.com/upload/a.jpg");
            fail("白名单外的域名必须被拒");
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            assertTrue("报错要带上被拒的主机：" + msg, msg.contains("evil.example.com"));
            assertTrue("报错要带上白名单，否则不知道该补什么：" + msg, msg.contains("pic.*.cn"));
        }
    }

    @Test
    public void 星号放首尾或连写都不合法() throws Exception {
        for (String bad : new String[]{"*.cn", "pic.*", "pic.*.*.cn"}) {
            try {
                profileWithHosts(bad);
                fail("这个模式不该通过校验：" + bad);
            } catch (IllegalArgumentException e) {
                assertEquals("无效封面域名", e.getMessage());
            }
        }
    }
}
