package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 封面失败的负缓存：**两层键**。
 *
 * 线上证据（2026-10-25）：一台设备 34 条「无效封面域名」，横跨 351 分钟。只按 URL
 * 缓存拦不住 —— 列表里 34 张封面是 34 个不同 URL，同一个配置错误一次都命中不了。
 *
 * 这里测的是「两层缓存的键怎么取」和「拿不到键时不抛异常」。真正的重试行为要靠
 * 真机看（{@code cover.js} 每 30 秒问一次那种节奏在单测里复刻不出来）。
 */
public class CoverFailureCacheTest {

    @Test
    public void 主机名取自_url_统一小写() {
        // 大小写必须归一：URL 里 HTTPS://IMG.Example.COM 和 img.example.com 是同一台。
        assertEquals("img.dozagonza.com", SiteRepository.hostOf("https://img.dozagonza.com/a.jpg"));
        assertEquals("img.dozagonza.com", SiteRepository.hostOf("https://IMG.Dozagonza.COM/a.jpg"));
        assertEquals("901.26702.app", SiteRepository.hostOf("https://901.26702.app/thumb/1.jpg"));
    }

    @Test
    public void 取不出主机时返回空串不抛() {
        // 相对路径（/upload_01/x.jpg）正是「无效封面地址」那条错误的来源 ——
        // 它连主机都没有，这里不能抛，否则错误处理本身会变成新的崩溃点。
        assertEquals("", SiteRepository.hostOf("/upload_01/x.jpg"));
        assertEquals("", SiteRepository.hostOf(""));
        assertEquals("", SiteRepository.hostOf(null));
        assertEquals("", SiteRepository.hostOf("不是 URL"));
    }

    @Test
    public void 同一主机的第二张封面不再重复上报() {
        // 第一次：正常失败，该报。
        SiteRepository.resetCoverFailureCache();
        assertNull("还没失败过，不该命中", SiteRepository.hostFailure("https://cdn.example.com/a.jpg"));

        // 记一条之后：同主机换了 URL，仍然算「这个主机刚失败过」。
        SiteRepository.rememberFailure("https://cdn.example.com/a.jpg", "无效封面域名：cdn.example.com");
        SiteRepository.CoverFailure second =
                SiteRepository.hostFailure("https://cdn.example.com/b.jpg");
        assertTrue("同主机的第二张要拦下来", second != null);
        // 拦下来的时候也必须带 Once 标记，否则 dispatch 照旧上报，等于没拦。
        assertTrue(Report.isOnce(second));
    }

    @Test
    public void 不同主机互不影响() {
        SiteRepository.resetCoverFailureCache();
        SiteRepository.rememberFailure("https://a.example.com/x.jpg", "失败");

        assertNull(SiteRepository.hostFailure("https://b.example.com/y.jpg"));
        assertTrue(SiteRepository.hostFailure("https://a.example.com/z.jpg") != null);
    }

    @Test
    public void 缓存过期后重新放过() {
        // 五分钟那个窗口不能写死成永久：站点恢复了还得能重试。
        SiteRepository.resetCoverFailureCache();
        SiteRepository.rememberHostFailureForTest("stale.example.com", System.currentTimeMillis() - 1);

        assertNull("过期了就该放过", SiteRepository.hostFailure("https://stale.example.com/a.jpg"));
    }

    /**
     * 网络类失败的冻结期必须远短于配置类。
     *
     * 真机证据（2026-10-31）：换完版本第一次打开黄果，一张封面赶上网络抖动，
     * 主机级缓存把整页二十张全挡了五分钟 —— 用户看到的是「这个源没有封面」，
     * 而每三秒一次的重试全打在缓存上，一次真请求都没发出去。
     */
    @Test
    public void 网络类失败不能把主机冻满五分钟() {
        assertTrue("网络抖一下的代价必须小", SiteRepository.FAIL_HOST_NET_MS <= 60 * 1000L);
    }

    /** 拿到图之后，「这个主机刚失败过」这笔旧账必须立刻作废。 */
    @Test
    public void 成功之后主机记录立刻作废() {
        SiteRepository.resetCoverFailureCache();
        SiteRepository.rememberFailure("https://cdn.example.com/a.jpg",
                "Connection reset", SiteRepository.FAIL_HOST_NET_MS);
        assertTrue("记上之后同主机其它封面该被挡",
                SiteRepository.hostFailure("https://cdn.example.com/b.jpg") != null);

        SiteRepository.forgetFailure("https://cdn.example.com/a.jpg");

        assertNull("抓回来了就不该再拿旧账挡后面那些",
                SiteRepository.hostFailure("https://cdn.example.com/b.jpg"));
    }

    /**
     * 缓存键剔掉签名参数、留着业务参数。
     *
     * 黄果封面挂着 {@code ?auth_key=<过期戳>-0-0-<md5>}，约九十分钟换一批。
     * 把它算进键里等于每九十分钟把整个图床重下一遍，而每多下一遍就多一次
     * 「运气不好碰上一次抖动 → 整页封面黑掉」的机会。
     */
    @Test
    public void 缓存键剔签名留业务参数() {
        assertEquals("", SiteRepository.cacheKeyQuery("auth_key=1793454192-0-0-41a0ba24"));
        assertEquals("id=7&w=480", SiteRepository.cacheKeyQuery("id=7&auth_key=abc&w=480"));
        // 前缀像但不是签名参数，不能误伤（精确比名字，不是 startsWith）。
        assertEquals("auth_level=2", SiteRepository.cacheKeyQuery("auth_level=2"));
        assertEquals("", SiteRepository.cacheKeyQuery(null));
        assertEquals("", SiteRepository.cacheKeyQuery(""));
    }
}
