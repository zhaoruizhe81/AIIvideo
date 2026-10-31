package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import okhttp3.OkHttpClient;

import org.junit.Test;

/**
 * 钉死 IP 直连 GET（DNS rebinding 防护的接口侧）。
 *
 * <p>真连接不在这测（联网用例另有一套规矩），这里钉住两条纯本地的行为边界：
 * 钉死 client 的解析<b>只答这组地址</b>（对任何域名都一样，杜绝二次解析）、
 * client 上<b>不挂代理</b>（钉死 IP 就是认定了直连，失败了直接抛）。
 */
public class AiiHttpPinnedTest {

    @Test
    public void 钉死client的解析只答这组地址() throws Exception {
        java.net.InetAddress[] addrs = {
                java.net.InetAddress.getByName("127.0.0.2"),   // 字面量构造，不触网
                java.net.InetAddress.getByName("127.0.0.3")};
        OkHttpClient client = AiiHttp.pinnedDirectClient(addrs);
        assertEquals(java.util.Arrays.asList(addrs), client.dns().lookup("whatever.example"));
        assertEquals("换个域名也只答这组（DNS 层面不存在第二次解析）",
                java.util.Arrays.asList(addrs), client.dns().lookup("other.example"));
    }

    @Test
    public void 钉死client不挂代理() throws Exception {
        OkHttpClient client = AiiHttp.pinnedDirectClient(
                new java.net.InetAddress[]{java.net.InetAddress.getByName("127.0.0.2")});
        assertNull("钉死 IP 只走直连：代理必须摘掉", client.proxy());
    }

    @Test
    public void 钉死地址为空直接拒绝不发请求() {
        try {
            AiiHttp.getRawPinned("https://cdn.example.com/v.ts", null);
            fail("null 地址组必须抛");
        } catch (Exception e) {
            assertEquals("钉死地址为空，拒绝请求：cdn.example.com", e.getMessage());
        }
        try {
            AiiHttp.getRawPinned("https://cdn.example.com/v.ts", new java.net.InetAddress[0]);
            fail("空地址组必须抛");
        } catch (Exception e) {
            assertEquals("钉死地址为空，拒绝请求：cdn.example.com", e.getMessage());
        }
    }

    @Test
    public void 额外头合并进请求且identity不被空map覆盖() {
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<String, String>();
        headers.put("Referer", "https://site.example/");
        headers.put("User-Agent", "probe-ua");
        okhttp3.Request r = AiiHttp.pinnedRequest("https://cdn.example.com/v.ts", headers);
        assertEquals("https://site.example/", r.header("Referer"));
        assertEquals("probe-ua", r.header("User-Agent"));
        assertEquals("identity", r.header("Accept-Encoding"));
        // null/空 headers：只带 identity（与 getRaw 收 null headers 的语义一致）
        assertEquals("identity",
                AiiHttp.pinnedRequest("https://cdn.example.com/v.ts", null).header("Accept-Encoding"));
        assertNull(AiiHttp.pinnedRequest("https://cdn.example.com/v.ts", null).header("Referer"));
    }
}
