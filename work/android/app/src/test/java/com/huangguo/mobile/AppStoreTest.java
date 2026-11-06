package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * AppStore 云快照裁剪的墓碑合并（模块6 中危修复）。
 *
 * <p>{@link AppStore#applyCloudSnapshot} 应用云端快照时会再 trim 一次历史到 50 部，
 * 被裁掉的剧集 id 必须并入 historyDeleted 墓碑，否则云端那份还在、下次同步又复活。
 * 这里盯 {@link AppStore#mergeTrimmedIntoDeleted} 这条不变式 —— 它不碰
 * SharedPreferences / Context（纯 JSON），JVM 单测直接可跑。
 */
public class AppStoreTest {

    @Test
    public void 被裁剧集id并入historyDeleted墓碑() throws Exception {
        JSONObject cloudDeleted = new JSONObject();
        Set<String> trimmedIds = new LinkedHashSet<String>(Arrays.asList("s51", "s52"));

        JSONObject merged = AppStore.mergeTrimmedIntoDeleted(cloudDeleted, trimmedIds, 1792762200000L);

        // 被裁的两个 id 都在，且带上了删除时刻（tombstone 的形状是 {id: 时刻}）。
        assertEquals(1792762200000L, merged.optLong("s51"));
        assertEquals(1792762200000L, merged.optLong("s52"));
        assertEquals(2, merged.length());
    }

    @Test
    public void 原有云端墓碑保留不被覆盖() throws Exception {
        JSONObject cloudDeleted = new JSONObject().put("old", 1000L);
        Set<String> trimmedIds = new LinkedHashSet<String>(Arrays.asList("s51"));

        JSONObject merged = AppStore.mergeTrimmedIntoDeleted(cloudDeleted, trimmedIds, 2000L);

        assertEquals(1000L, merged.optLong("old"));   // 云端既有墓碑原样保留
        assertEquals(2000L, merged.optLong("s51"));   // 新裁的补进来
        assertEquals(2, merged.length());
    }

    @Test
    public void 没有裁掉任何条目时墓碑不变() throws Exception {
        JSONObject cloudDeleted = new JSONObject().put("old", 1000L);

        JSONObject merged = AppStore.mergeTrimmedIntoDeleted(cloudDeleted, new HashSet<String>(), 2000L);

        assertEquals(1, merged.length());
        assertEquals(1000L, merged.optLong("old"));
        assertFalse(merged.has("s51"));
    }

    @Test
    public void cloudHistoryDeleted为null时也能安全合并() throws Exception {
        Set<String> trimmedIds = new LinkedHashSet<String>(Arrays.asList("s51"));

        JSONObject merged = AppStore.mergeTrimmedIntoDeleted(null, trimmedIds, 2000L);

        assertEquals(2000L, merged.optLong("s51"));
        assertEquals(1, merged.length());
    }

    @Test
    public void 不修改入参对象() throws Exception {
        JSONObject cloudDeleted = new JSONObject().put("old", 1000L);
        Set<String> trimmedIds = new LinkedHashSet<String>(Arrays.asList("s51"));

        JSONObject merged = AppStore.mergeTrimmedIntoDeleted(cloudDeleted, trimmedIds, 2000L);

        // 返回的是新对象，入参不应被污染（applyCloudSnapshot 之后还会用到原引用语义）。
        assertTrue(merged != cloudDeleted);
        assertFalse(cloudDeleted.has("s51"));
        assertEquals(1, cloudDeleted.length());
    }
}
