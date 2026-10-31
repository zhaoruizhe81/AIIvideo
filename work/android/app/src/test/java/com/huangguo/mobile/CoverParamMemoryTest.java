package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 自适应易变参数记忆（方案 §4.4）：
 * 缓存键的签名参数剔除不能只靠七名种子 —— 下一个图床用 {@code e=} / 
 * {@code X-Oss-Expires} 之流就得漏。同一路径下某参数出现过两个不同值，
 * 当场判易变、当场从键剔除。
 *
 * <p>误判的代价是「缓存少命中」（多下一次图），不是错误 —— 所以阈值可以松，
 * 两个值就判，不用等三次。
 */
public class CoverParamMemoryTest {

    private static final String FP = "fp-param-test";
    private static final String PATH = "/upload/abc.jpg";

    @After
    public void tearDown() {
        CoverParamMemory.resetForTest();
        CoverTrustStore.resetForTest();
    }

    private static Map<String, String> params(String... pairs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put(pairs[i], pairs[i + 1]);
        return out;
    }

    @Test
    public void 稳定参数不判易变() {
        CoverParamMemory.observe(null, FP, PATH, params("id", "1"));
        CoverParamMemory.observe(null, FP, PATH, params("id", "1"));
        assertTrue(CoverParamMemory.volatileParams(null, FP, PATH).isEmpty());
    }

    @Test
    public void 第二个不同值当场判易变() {
        CoverParamMemory.observe(null, FP, PATH, params("e", "1793000000"));
        Set<String> before = CoverParamMemory.volatileParams(null, FP, PATH);
        assertFalse("只见过一个值，还不能判", before.contains("e"));
        CoverParamMemory.observe(null, FP, PATH, params("e", "1793003600"));
        assertTrue("换值的那一刻就该判出来，等下一张图才剔等于白换",
                CoverParamMemory.volatileParams(null, FP, PATH).contains("e"));
    }

    @Test
    public void 参数名大小写归一() {
        CoverParamMemory.observe(null, FP, PATH, params("X-Oss-Expires", "900"));
        CoverParamMemory.observe(null, FP, PATH, params("x-oss-expires", "1800"));
        assertTrue(CoverParamMemory.volatileParams(null, FP, PATH).contains("x-oss-expires"));
    }

    @Test
    public void 判定不因后续观察翻案() {
        CoverParamMemory.observe(null, FP, PATH, params("sign", "aaa"));
        CoverParamMemory.observe(null, FP, PATH, params("sign", "bbb"));
        assertTrue(CoverParamMemory.volatileParams(null, FP, PATH).contains("sign"));
        // 之后就算它连续一万个值都一样，判定也不撤 —— 撤了就是给下一次轮换埋雷。
        for (int i = 0; i < 10; i++) {
            CoverParamMemory.observe(null, FP, PATH, params("sign", "bbb"));
        }
        assertTrue(CoverParamMemory.volatileParams(null, FP, PATH).contains("sign"));
    }

    @Test
    public void 路径之间互不影响() {
        CoverParamMemory.observe(null, FP, "/a/1.jpg", params("v", "2"));
        CoverParamMemory.observe(null, FP, "/a/1.jpg", params("v", "3"));
        CoverParamMemory.observe(null, FP, "/b/2.jpg", params("v", "2"));
        assertTrue(CoverParamMemory.volatileParams(null, FP, "/a/1.jpg").contains("v"));
        assertFalse("/b/2.jpg 只见过一个值，不能被 /a/1.jpg 连坐",
                CoverParamMemory.volatileParams(null, FP, "/b/2.jpg").contains("v"));
    }

    @Test
    public void 换源reset只清这个源() {
        CoverParamMemory.observe(null, "fp-a", PATH, params("k", "1"));
        CoverParamMemory.observe(null, "fp-b", PATH, params("k", "1"));
        CoverParamMemory.observe(null, "fp-a", PATH, params("k", "2"));
        CoverParamMemory.reset(null, "fp-a");
        assertTrue(CoverParamMemory.volatileParams(null, "fp-a", PATH).isEmpty());
        assertFalse(CoverParamMemory.volatileParams(null, "fp-b", PATH).contains("k"));
    }

    @Test
    public void cacheKeyQuery重载_种子与自适应叠加() {
        Set<String> empty = Collections.emptySet();
        // 种子行为不变（单参/双参 null 一致）。
        assertEquals("id=7", SiteRepository.cacheKeyQuery("id=7&auth_key=abc-0-0-x"));
        assertEquals("id=7", SiteRepository.cacheKeyQuery("id=7&auth_key=abc-0-0-x", empty));
        // 自适应集里的参数被剔，种子外的稳定参数保留。
        assertEquals("id=7", SiteRepository.cacheKeyQuery("id=7&e=900", Collections.singleton("e")));
        // 观测 + 键计算走通整条链：判易变后键里不再有它。
        CoverParamMemory.observe(null, FP, PATH, params("oss", "a"));
        CoverParamMemory.observe(null, FP, PATH, params("oss", "b"));
        assertEquals("id=7", SiteRepository.cacheKeyQuery("id=7&oss=zzz",
                CoverParamMemory.volatileParams(null, FP, PATH)));
    }
}
