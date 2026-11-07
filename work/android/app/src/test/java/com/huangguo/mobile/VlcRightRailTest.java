package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 迭代2-B 右侧入口的纯逻辑回归：前端钩子返回值解析 + 收藏状态复读决策。
 *
 * <p>这两条一旦写错：`parseBool` 认错 'true'/'false' → 星标亮灭反了；
 * 复读决策写错 → 首帧恒灭（缓存没热没复读）或无限轮询（永远复读）。所以钉死。
 */
public class VlcRightRailTest {

    // ── parseBool：前端同步返回的布尔字符串 ───────────────────────

    @Test public void 解析_true与false大小写都认() {
        assertTrue(VlcPlayerActivity.parseBool("true", false));
        assertTrue(VlcPlayerActivity.parseBool("TRUE", false));
        assertTrue(VlcPlayerActivity.parseBool(" True ", false));   // 带空白
        assertFalse(VlcPlayerActivity.parseBool("false", true));
        assertFalse(VlcPlayerActivity.parseBool("false", true));
    }

    /** 缺函数兜底字符串 'false' / 空 / 脏 → fallback。 */
    @Test public void 解析_脏输入走fallback() {
        assertFalse(VlcPlayerActivity.parseBool("false", false));
        assertFalse(VlcPlayerActivity.parseBool(null, false));
        assertTrue(VlcPlayerActivity.parseBool(null, true));         // fallback 生效
        assertEquals(false, VlcPlayerActivity.parseBool("", false));
        assertEquals(true, VlcPlayerActivity.parseBool("undefined", true));  // 认不出→fallback
        assertEquals(false, VlcPlayerActivity.parseBool("undefined", false));
    }

    // ── 复读决策：只有「缓存未就绪」才复读一次 ─────────────────────

    @Test public void 复读_未就绪才复读() {
        assertTrue("缓存没热要复读一次兜底", VlcPlayerActivity.bookmarkNeedsReread(false));
        assertFalse("缓存已热不用复读", VlcPlayerActivity.bookmarkNeedsReread(true));
    }
}
