package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 订阅里的重复条目与公告条目。
 *
 * 真实样本（youzizi 那份）：10 条「节点」去掉名字后 md5 完全一致 —— 同一台机器
 * 复制五份，另外几条是「剩余流量：50GB」「套餐到期」这种公告。以前全进测速池，
 * 列表上就是六条一模一样的 host:port。
 */
public class SubscriptionDedupeTest {

    private static String vless(String uuid, String name) {
        return "vless://" + uuid + "@cdn.example.xyz:443"
                + "?type=ws&security=tls&path=%2Fapi%2Fv1%2Fguests%2Fabc%2F&host=mao.example.net"
                + (name == null ? "" : "#" + name);
    }

    @Test
    public void 同一份配置只留一条且保留名字() {
        String raw = vless("af8b57d4-0000-0000-0000-000000000000", "%E7%BE%8E%E5%9B%BD%201") + "\n"
                + vless("af8b57d4-0000-0000-0000-000000000000", "%E7%BE%8E%E5%9B%BD%202") + "\n"
                + vless("af8b57d4-0000-0000-0000-000000000000", "%E7%BE%8E%E5%9B%BD%203") + "\n";
        Subscription.Result r = Subscription.parse(raw);
        assertEquals("重复配置只该留一条", 1, r.size());
        assertEquals("其余两条算重复", 2, r.duplicates);
        assertEquals("名字要保住", "美国 1", r.nodes.get(0).name);
    }

    @Test
    public void 公告条目直接忽略不计入节点() {
        String raw = vless("af8b57d4-0000-0000-0000-000000000000", "%E7%BE%8E%E5%9B%BD%201") + "\n"
                + vless("af8b57d4-0000-0000-0000-000000000000", "%E5%89%A9%E4%BD%99%E6%B5%81%E9%87%8F%EF%BC%9A50%20GB") + "\n"
                + vless("af8b57d4-0000-0000-0000-000000000000", "%E5%A5%97%E9%A4%90%E5%88%B0%E6%9C%9F%EF%BC%9A2026-11-20") + "\n";
        Subscription.Result r = Subscription.parse(raw);
        assertEquals(1, r.size());
        assertEquals(2, r.notices);
        assertEquals(0, r.duplicates);
        assertTrue("摘要要提到公告", Subscription.summary(r).contains("公告"));
    }

    /** 不同 uuid 就是不同节点，不能因为 host:port 一样被合并。 */
    @Test
    public void 同机器不同凭据不算重复() {
        String raw = vless("aaaaaaaa-0000-0000-0000-000000000000", "A") + "\n"
                + vless("bbbbbbbb-0000-0000-0000-000000000000", "B") + "\n";
        Subscription.Result r = Subscription.parse(raw);
        assertEquals(2, r.size());
        assertEquals(0, r.duplicates);
    }

    /**
     * 公告排在真节点前面时，保留的名字要是真节点那个。
     *
     * 真实订阅（youzizi）第一条就是「剩余流量：50 GB」，真名字在后面几条 ——
     * 只留第一条的话，列表里那条节点就叫「剩余流量：50 GB」。
     */
    @Test
    public void 公告在前时用真名字覆盖() {
        String raw = vless("af8b57d4-0000-0000-0000-000000000000", "%E5%89%A9%E4%BD%99%E6%B5%81%E9%87%8F%EF%BC%9A50%20GB") + "\n"
                + vless("af8b57d4-0000-0000-0000-000000000000", "%E5%A5%97%E9%A4%90%E5%88%B0%E6%9C%9F") + "\n"
                + vless("af8b57d4-0000-0000-0000-000000000000", "%E7%BE%8E%E5%9B%BD%201") + "\n"
                + vless("af8b57d4-0000-0000-0000-000000000000", "%E7%BE%8E%E5%9B%BD%202") + "\n";
        Subscription.Result r = Subscription.parse(raw);
        assertEquals(1, r.size());
        assertEquals("美国 1", r.nodes.get(0).name);
        assertEquals(2, r.notices);
        assertEquals(1, r.duplicates);
    }

    /** 唯一一条恰好叫「到期提醒」时，它仍然是个节点。 */
    @Test
    public void 唯一的节点就算名字像公告也留着() {
        Subscription.Result r = Subscription.parse(vless("cccccccc-0000-0000-0000-000000000000", "套餐到期"));
        assertEquals(1, r.size());
        assertEquals(0, r.notices);
    }

    // ── signatureOf 补齐 method/security/upMbps 等 6 字段后的去重口径 ──
    //
    // 去重指纹必须与 ProxyCore.sameNode 字段集对齐：同机同 uuid 但加密方式 /
    // vmess 安全选路 / hy2 带宽不同的，本就是不同节点，不能被当重复吞掉。
    // 而完全相同的两条仍要合并（否则测速会把同一条测两遍）。

    private static String ss(String method, String pass) {
        // 第一形态 base64(method:password)@host:port —— 同 host/port/password，只差 method。
        // pass 用纯 ASCII 且不含 @ : #，保证「同 uuid 仅 method 不同」这条前提成立。
        String userInfo = java.util.Base64.getEncoder()
                .encodeToString((method + ":" + pass).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "ss://" + userInfo + "@ss.example.xyz:8388#n1";
    }

    private static String hy2(String password, String up) {
        return "hy2://" + password + "@hy.example.xyz:443?sni=hy.example.xyz&up=" + up + "#h-" + up;
    }

    @Test
    public void 仅加密方式不同不算重复() {
        String raw = ss("aes-256-gcm", "s3cret") + "\n"
                + ss("chacha20-ietf-poly1305", "s3cret") + "\n";
        Subscription.Result r = Subscription.parse(raw);
        assertEquals("加密方式不同的两条 ss 都是真节点", 2, r.size());
        assertEquals(0, r.duplicates);
    }

    @Test
    public void 仅vmess安全选路不同不算重复() {
        String a = "vless://dddddddd-0000-0000-0000-000000000000@cdn.example.xyz:443"
                + "?type=ws&security=tls&path=%2Fa&host=x.example.net#A";
        // security 相同、uuid/server/path/host 全相同，仅 flow 不同 —— flow 已在旧指纹里；
        // 这里改测 security（tls vs reality 走不同握手）不可轻易构造同 path，故用
        // vmess 的 security 选路：直接构造两条仅 security 不同的 vless（reality 需 pbk，
        // 只用 tls vs none 会在 buildString 层面都合法）。
        String b = "vless://dddddddd-0000-0000-0000-000000000000@cdn.example.xyz:443"
                + "?type=ws&security=none&path=%2Fa&host=x.example.net#B";
        Subscription.Result r = Subscription.parse(a + "\n" + b);
        assertEquals("security 不同的两条不是重复", 2, r.size());
        assertEquals(0, r.duplicates);
    }

    @Test
    public void 仅hy2上行带宽不同不算重复() {
        String raw = hy2("pw", "50") + "\n" + hy2("pw", "200") + "\n";
        Subscription.Result r = Subscription.parse(raw);
        assertEquals("hy2 仅带宽不同是两条不同节点", 2, r.size());
        assertEquals(0, r.duplicates);
    }

    @Test
    public void 完全相同的ss仍被去重() {
        // 用两条「指纹相同但原始文本不同」的 ss（备注名字不同）验证 signatureOf 命中：
        // 完全逐字相同的两条会被上游 `seen` 直接跳过，根本不进指纹比对，测不到去重逻辑。
        String raw = ss("aes-256-gcm", "s3cret") + "#A\n" + ss("aes-256-gcm", "s3cret") + "#B\n";
        Subscription.Result r = Subscription.parse(raw);
        assertEquals("完全相同的配置只留一条", 1, r.size());
        assertEquals("名字不同不算公告，按 duplicate 计", 1, r.duplicates);
    }
}
