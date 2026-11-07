package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 刷视频模式（FEED_LIST 形态）纯逻辑回归 —— 形态判定 / index 钳制 / 续页追加解析。
 *
 * <p>这些逻辑一旦写错症状是「刷视频里混进整部剧的集」「滑到底续页串到别的列表」
 * 或「index 越界开播第 0 条」——都是真机上难二分的问题。抽成纯函数在这里钉死。
 */
public class VlcFeedListShapeTest {

    // ── classifyShape：三形态互斥 ──────────────────────────────────

    /** feedList 非空（含带 id 条目）→ FEED_LIST，即便同时带了 eps。 */
    @Test public void 形态_feedList非空即FEED_LIST() throws Exception {
        JSONObject p = new JSONObject();
        p.put("feedList", new org.json.JSONArray("[{\"id\":\"1\"},{\"id\":\"2\"}]"));
        p.put("eps", new org.json.JSONArray("[1,2,3]"));   // 故意也带 eps
        assertEquals(VlcPlayerActivity.SHAPE_FEED_LIST, VlcPlayerActivity.classifyShape(p));
    }

    /** 没有 feedList、eps 长度>1 → SERIES。 */
    @Test public void 形态_eps多集即SERIES() throws Exception {
        JSONObject p = new JSONObject();
        p.put("eps", new org.json.JSONArray("[1,2,3]"));
        assertEquals(VlcPlayerActivity.SHAPE_SERIES, VlcPlayerActivity.classifyShape(p));
    }

    /** 没有 feedList、eps 长度<=1 → SINGLE。 */
    @Test public void 形态_单集即SINGLE() throws Exception {
        JSONObject p = new JSONObject();
        p.put("eps", new org.json.JSONArray("[1]"));
        assertEquals(VlcPlayerActivity.SHAPE_SINGLE, VlcPlayerActivity.classifyShape(p));
        JSONObject empty = new JSONObject();
        assertEquals(VlcPlayerActivity.SHAPE_SINGLE, VlcPlayerActivity.classifyShape(empty));
        assertEquals(VlcPlayerActivity.SHAPE_SINGLE, VlcPlayerActivity.classifyShape(null));
    }

    /** feedList 全是脏数据（无 id）→ 不算 FEED_LIST，退回按 eps 判定。 */
    @Test public void 形态_feedList全脏退回其他形态() throws Exception {
        JSONObject p = new JSONObject();
        p.put("feedList", new org.json.JSONArray("[{\"title\":\"x\"},{}]"));
        p.put("eps", new org.json.JSONArray("[1,2]"));
        assertEquals(VlcPlayerActivity.SHAPE_SERIES, VlcPlayerActivity.classifyShape(p));
    }

    // ── clampIndex：越界钳制 ───────────────────────────────────────

    @Test public void 钳制_正常与越界() {
        assertEquals(0, VlcPlayerActivity.clampIndex(0, 5));
        assertEquals(2, VlcPlayerActivity.clampIndex(2, 5));
        assertEquals(4, VlcPlayerActivity.clampIndex(9, 5));    // 上越界 → count-1
        assertEquals(0, VlcPlayerActivity.clampIndex(-3, 5));   // 下越界 → 0
        assertEquals(0, VlcPlayerActivity.clampIndex(3, 0));    // 空列表 → 0
    }

    // ── appendFeedItems：续页 JSON 解析并追加 ──────────────────────

    @Test public void 续页_正常追加返回条数() {
        List<VlcPlayerActivity.FeedItem> list = new ArrayList<VlcPlayerActivity.FeedItem>();
        list.add(new VlcPlayerActivity.FeedItem("a", "A", "", "s"));
        String json = "{\"items\":[{\"id\":\"b\",\"title\":\"B\"},{\"id\":\"c\",\"cover\":\"u\"}],\"hasMore\":true}";
        int added = VlcPlayerActivity.appendFeedItems(list, json);
        assertEquals(2, added);
        assertEquals(3, list.size());
        assertEquals("b", list.get(1).id);
        assertEquals("c", list.get(2).id);
    }

    /** 空 id 的条目被丢弃，不占位。 */
    @Test public void 续页_空id条目被丢弃() {
        List<VlcPlayerActivity.FeedItem> list = new ArrayList<VlcPlayerActivity.FeedItem>();
        String json = "{\"items\":[{\"id\":\"\"},{\"id\":\"x\"},{}]}";
        assertEquals(1, VlcPlayerActivity.appendFeedItems(list, json));
        assertEquals(1, list.size());
        assertEquals("x", list.get(0).id);
    }

    /** 脏 JSON / 空串 / 缺 items 都不炸、返回 0。 */
    @Test public void 续页_脏输入返回0() {
        List<VlcPlayerActivity.FeedItem> list = new ArrayList<VlcPlayerActivity.FeedItem>();
        assertEquals(0, VlcPlayerActivity.appendFeedItems(list, ""));
        assertEquals(0, VlcPlayerActivity.appendFeedItems(list, null));
        assertEquals(0, VlcPlayerActivity.appendFeedItems(list, "not-json"));
        assertEquals(0, VlcPlayerActivity.appendFeedItems(list, "{\"hasMore\":true}"));   // 无 items
        assertTrue(list.isEmpty());
    }
}
