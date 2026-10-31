package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

/**
 * 封面观测集（CoverTrustStore）：C 档裸拉成功进来的主机，B 档信任的生命周期。
 *
 * <p>方案（docs/history/2026-10-31-封面信任模型改造方案.md §4.2）定的三件套都要守住：
 * 有界（32）、有 TTL（7 天）、失败降级（连续 3 次回 C 档不删条目）。这三件是
 * 「观测集不会变成第二个需要人维护的名单」的全部依据，缺一个它就会腐化。
 *
 * <p>纯 JVM：context 传 null 走纯内存路径（持久化自动跳过）；TTL/降级靠
 * seedForTest 直接种时间戳 —— 时钟不注入，种状态。
 */
public class CoverTrustStoreTest {

    private static final String FP = "fp-test-source";

    @After
    public void tearDown() {
        CoverTrustStore.resetForTest();
        CoverParamMemory.resetForTest();
    }

    @Test
    public void 首次记录返回真_再次记录返回假() {
        assertTrue(CoverTrustStore.record(null, FP, "pic.nhqktz.cn"));
        assertFalse(CoverTrustStore.record(null, FP, "pic.nhqktz.cn"));
        // 大小写归一：同一台主机换种写法不算新主机。
        assertFalse(CoverTrustStore.record(null, FP, "PIC.NHQKTZ.CN"));
    }

    @Test
    public void 记录过的主机在TTL内算观测信任() {
        assertFalse(CoverTrustStore.isObserved(null, FP, "pic.nhqktz.cn"));
        CoverTrustStore.record(null, FP, "pic.nhqktz.cn");
        assertTrue(CoverTrustStore.isObserved(null, FP, "pic.nhqktz.cn"));
        // 观测集按源隔离：别的指纹下不算。
        assertFalse(CoverTrustStore.isObserved(null, "fp-other", "pic.nhqktz.cn"));
    }

    @Test
    public void 七天未命中过期() {
        long now = System.currentTimeMillis();
        CoverTrustStore.seedForTest(FP, "pic.stale.cn", now - 8L * 24 * 3600_000L, true, 3, 0);
        assertFalse("超过 7 天没命中就该重新裸拉验证", CoverTrustStore.isObserved(null, FP, "pic.stale.cn"));
        // 边界内侧：6 天前还在 TTL 内。
        CoverTrustStore.seedForTest(FP, "pic.fresh.cn", now - 6L * 24 * 3600_000L, true, 3, 0);
        assertTrue(CoverTrustStore.isObserved(null, FP, "pic.fresh.cn"));
    }

    @Test
    public void 连续三次失败降回C档_条目保留() throws Exception {
        long now = System.currentTimeMillis();
        CoverTrustStore.seedForTest(FP, "pic.shaky.cn", now, true, 5, 2);
        // 第 3 次失败：降级。
        CoverTrustStore.recordFail(null, FP, "pic.shaky.cn");
        assertFalse("连续 3 次失败后要重新裸拉验证", CoverTrustStore.isObserved(null, FP, "pic.shaky.cn"));
        // 降级不是删除：条目还在（诊断页要能看见「C 降级待验证」）。
        boolean found = false;
        for (int i = 0; i < CoverTrustStore.dump(null, FP).length(); i++) {
            if ("pic.shaky.cn".equals(CoverTrustStore.dump(null, FP).getJSONObject(i).optString("host"))) found = true;
        }
        assertTrue("降级的条目要保留，不能直接删", found);
        // 重新裸拉成功（record）就再升回来。
        CoverTrustStore.record(null, FP, "pic.shaky.cn");
        assertTrue(CoverTrustStore.isObserved(null, FP, "pic.shaky.cn"));
    }

    @Test
    public void 成功会复位失败计数() {
        long now = System.currentTimeMillis();
        CoverTrustStore.seedForTest(FP, "pic.ok.cn", now, true, 1, 2);
        CoverTrustStore.recordOk(null, FP, "pic.ok.cn");
        // 计数清零后，单次失败不再触发降级。
        CoverTrustStore.recordFail(null, FP, "pic.ok.cn");
        assertTrue(CoverTrustStore.isObserved(null, FP, "pic.ok.cn"));
    }

    @Test
    public void 三十二条上限_淘汰最久未命中的() throws Exception {
        long now = System.currentTimeMillis();
        for (int i = 0; i < CoverTrustStore.CAPACITY; i++) {
            // 最老的那条留最旧的时间戳，等它被挤出去。
            long age = (CoverTrustStore.CAPACITY - i) * 60_000L;
            CoverTrustStore.seedForTest(FP, "pic.old" + i + ".cn", now - age, true, 1, 0);
        }
        // 新主机进来，挤掉的必须是 lastSeenAt 最老的 old0（种子循环里 i=0 的 age 最大）。
        CoverTrustStore.record(null, FP, "pic.new.cn");
        assertEquals(CoverTrustStore.CAPACITY, CoverTrustStore.dump(null, FP).length());
        String evicted = "pic.old0.cn";
        for (int i = 0; i < CoverTrustStore.dump(null, FP).length(); i++) {
            assertFalse("最老的条目该被淘汰",
                    evicted.equals(CoverTrustStore.dump(null, FP).getJSONObject(i).optString("host")));
        }
        // 最新的种子和刚记录的都在。
        assertTrue(CoverTrustStore.isObserved(null, FP, "pic.new.cn"));
        assertTrue(CoverTrustStore.isObserved(null, FP, "pic.old" + (CoverTrustStore.CAPACITY - 1) + ".cn"));
        assertTrue(CoverTrustStore.isObserved(null, FP, "pic.new.cn"));
    }

    @Test
    public void 换源reset只清这个源() {
        CoverTrustStore.record(null, "fp-a", "pic.a.cn");
        CoverTrustStore.record(null, "fp-b", "pic.b.cn");
        CoverTrustStore.reset(null, "fp-a");
        assertFalse(CoverTrustStore.isObserved(null, "fp-a", "pic.a.cn"));
        assertTrue("别的源的观测集不能被连坐", CoverTrustStore.isObserved(null, "fp-b", "pic.b.cn"));
    }

    @Test
    public void 没记录过的主机做成功失败记账都是空操作() throws Exception {
        // A 档（名单内）主机从来不进观测集 —— 记账不能凭空给它造档案。
        CoverTrustStore.recordOk(null, FP, "pic.declared.cn");
        CoverTrustStore.recordFail(null, FP, "pic.declared.cn");
        assertEquals(0, CoverTrustStore.dump(null, FP).length());
        assertFalse(CoverTrustStore.isObserved(null, FP, "pic.declared.cn"));
    }
}
