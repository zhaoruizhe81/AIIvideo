package com.huangguo.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.parse.ParseObject;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.ToLongFunction;

/**
 * AIIvideo —— 本地存储层：收藏、观看历史、页面缓存，以及云同步用的快照。
 *
 * ── 数据放哪 ────────────────────────────────────────────────────────
 * 全在 SharedPreferences 里，按源分两个文件："huangguo" 走 huangguo-data，
 * 其它走 dual-data-hongguo。每个键存一坨 JSON 字符串，键名是
 * bookmarks / progress / bookmarkDeleted / historyDeleted / cache:&lt;key&gt;。
 * 没上数据库：量级就几十条记录，键值够用，也省得上来就碰 SQLite 迁移。
 *
 * ── 登录前后的键空间 ────────────────────────────────────────────────
 * 未登录时键是裸名（bookmarks、progress）；login 写进 activeUserId 之后，
 * 所有读写都过 scoped()，变成 user:&lt;objectId&gt;:bookmarks。
 * 匿名时期攒下的东西在 activateUser() 里按 addedAt / updated_at 合并到用户名下，
 * 同一个 id 谁新用谁 —— 「先看后登」的进度和收藏不该凭空消失。
 *
 * ── 删除墓碑 ────────────────────────────────────────────────────────
 * 删收藏、删历史都不真删：id 连同时刻写进 bookmarkDeleted / historyDeleted。
 * 云同步是双向的，没有墓碑的话，本地删掉的条目会被服务端那份原样推回来。
 * 合并时只比墓碑时间和记录自身的 updated_at，谁晚听谁的。
 *
 * ── 容量 ────────────────────────────────────────────────────────────
 * 首页缓存留 80 条，其它类别留 79 条（算上刚写进来的这条正好 80）；
 * 观看历史按剧集去重后留 50 条，被裁掉的剧集同样进墓碑，否则下次同步又回来。
 *
 * 调用方：MainActivity 的 api 桥（读写缓存和库）、AccountService（Parse 云同步）、
 * UserSource（播放器回写进度）。方法都 synchronized 在实例上，内部直接改
 * SharedPreferences，返回的 JSONObject 是新对象，改它不会反向污染存储。
 */
final class AppStore {
    /** 首页缓存的条数上限。其它类别是 79，加上当前这条凑成同一个 80。 */
    private static final int CONTENT_CACHE_LIMIT = 80;
    /** 单剧观看历史的条数上限。trimProgress 里目前写死 50，这个常量还没接上。 */
    private static final int HISTORY_LIMIT = 50;

    private final SharedPreferences preferences;
    final String source;

    /**
     * 本地数据目录固定成一个。
     *
     * 原来按 ContentMode 分两个文件（私密态 "huangguo-data"、公开态
     * "dual-data-hongguo"）。2026-10-19 双态拆掉之后只剩一个内容源，
     * 再分文件只会让「换一次源，收藏和历史看起来没了」。这里固定用公开态那份
     * —— 私密态那个文件里的记录对应的是已经不存在的内容 id。
     */
    AppStore(Context context) {
        this.source = "hongguo";
        this.preferences = context.getSharedPreferences("dual-data-hongguo", 0);
    }

    /** 登录后把键名加上 user:&lt;objectId&gt;: 前缀，未登录原样返回。 */
    private String scoped(String key) {
        String userId = this.preferences.getString("activeUserId", "");
        if (userId.isEmpty()) {
            return key;
        }
        return "user:" + userId + ":" + key;
    }

    private JSONObject readObject(String key) {
        try {
            return new JSONObject(this.preferences.getString(key, "{}"));
        } catch (Exception unused) {
            // 落盘的 JSON 读不回来时按空处理 —— 静默的话「数据坏了」就无痕消失，
            // 这里至少留一句给 logcat 对账。
            Log.w("AIIvideo", "[store] 本地数据读不出来，按空处理 key=" + key);
            return new JSONObject();
        }
    }

    synchronized void activateUser(String userId) throws Exception {
        String currentUserId = this.preferences.getString("activeUserId", "");
        if (currentUserId.equals(userId)) {
            return;
        }
        SharedPreferences.Editor editor = this.preferences.edit();
        if (currentUserId.isEmpty() && !userId.isEmpty()) {
            // 匿名期 -> 登录：把裸键下的收藏和进度按时间戳并进用户键空间，然后清掉裸键。
            String userPrefix = "user:" + userId + ":";
            editor.putString(userPrefix + "bookmarks", mergeRecords(readObject("bookmarks"), readObject(userPrefix + "bookmarks"), "addedAt").toString());
            editor.putString(userPrefix + NotificationCompat.CATEGORY_PROGRESS, mergeRecords(readObject(NotificationCompat.CATEGORY_PROGRESS), readObject(userPrefix + NotificationCompat.CATEGORY_PROGRESS), ParseObject.KEY_UPDATED_AT).toString());
            // 匿名期的墓碑必须一并带走：不带走的话云同步查不到「删过」的记录，
            // 匿名期删掉的收藏/历史会被云端那份原样复活。
            editor.putString(userPrefix + "bookmarkDeleted", mergeTombstones(readObject("bookmarkDeleted"), readObject(userPrefix + "bookmarkDeleted")).toString());
            editor.putString(userPrefix + "historyDeleted", mergeTombstones(readObject("historyDeleted"), readObject(userPrefix + "historyDeleted")).toString());
            editor.remove("bookmarks").remove(NotificationCompat.CATEGORY_PROGRESS).remove("bookmarkDeleted").remove("historyDeleted");
        }
        editor.putString("activeUserId", userId).apply();
    }

    /**
     * 墓碑合并：形状是 { id: 删除时刻 }，值本身就是时间戳（没有记录体、没有额外
     * 的时间戳字段），按 id 取两边较大者 —— 删除只认最新的那一次。
     */
    private static JSONObject mergeTombstones(JSONObject local, JSONObject remote) throws Exception {
        JSONObject merged = new JSONObject(remote.toString());
        Iterator<String> idIterator = local.keys();
        while (idIterator.hasNext()) {
            String id = idIterator.next();
            long localAt = local.optLong(id);
            if (localAt > merged.optLong(id)) {
                merged.put(id, localAt);
            }
        }
        return merged;
    }

    /**
     * 按 timestampKey 做一次「谁新用谁」的合并，返回合并结果的新对象。
     * 只按 local 的 id 走：local 是当前设备的动作来源，为 null 的槽位说明对面没有。
     */
    private static JSONObject mergeRecords(JSONObject local, JSONObject remote, String timestampKey) throws Exception {
        JSONObject merged = new JSONObject(remote.toString());
        Iterator<String> idIterator = local.keys();
        while (idIterator.hasNext()) {
            String id = idIterator.next();
            JSONObject localRecord = local.optJSONObject(id);
            JSONObject remoteRecord = merged.optJSONObject(id);
            if (localRecord != null && (remoteRecord == null || localRecord.optLong(timestampKey) > remoteRecord.optLong(timestampKey))) {
                merged.put(id, localRecord);
            }
        }
        return merged;
    }

    synchronized JSONObject cached(String key) {
        JSONObject cached = null;
        try {
            String raw = this.preferences.getString("cache:" + key, null);
            if (raw != null) {
                cached = new JSONObject(raw);
            }
        } catch (Exception unused) {
            Log.w("AIIvideo", "[store] 页面缓存读不出来，按未命中处理 key=cache:" + key);
            return null;
        }
        return cached;
    }

    synchronized void saveCache(String cacheKey, JSONObject data) throws Exception {
        String prefKey = "cache:" + cacheKey;
        SharedPreferences.Editor editor = this.preferences.edit()
                .putString(prefKey, new JSONObject().put("at", System.currentTimeMillis()).put("data", data).toString());
        // 顺手给所有 cache 键记一次写入时间，超量时从最旧的开始淘汰。
        final HashMap ages = new HashMap();
        for (Map.Entry<String, ?> entry : this.preferences.getAll().entrySet()) {
            String existingKey = entry.getKey();
            if (existingKey.startsWith("cache:") && !"cache:home".equals(existingKey) && !existingKey.equals(prefKey)) {
                try {
                    ages.put(existingKey, Long.valueOf(new JSONObject(String.valueOf(entry.getValue())).optLong("at")));
                } catch (Exception unused) {
                    // 这条缓存本身已经坏了，直接清掉，不参与淘汰排序。
                    editor.remove(existingKey);
                }
            }
        }
        int limit = "home".equals(cacheKey) ? CONTENT_CACHE_LIMIT : 79;
        if (ages.size() > limit) {
            ArrayList oldestFirst = new ArrayList(ages.keySet());
            oldestFirst.sort(Comparator.comparingLong(new ToLongFunction() {
                @Override
                public final long applyAsLong(Object prefName) {
                    return ((Long) ages.get((String) prefName)).longValue();
                }
            }));
            int excess = ages.size() - limit;
            for (int n = 0; n < excess; n++) {
                editor.remove((String) oldestFirst.get(n));
            }
        }
        editor.apply();
    }

    synchronized void clearCache() {
        SharedPreferences.Editor editor = this.preferences.edit();
        for (String key : this.preferences.getAll().keySet()) {
            if (key.startsWith("cache:")) {
                editor.remove(key);
            }
        }
        editor.apply();
    }

    private JSONObject object(String name) {
        return readObject(scoped(name));
    }

    synchronized boolean toggleBookmark(JSONObject item) throws Exception {
        String id = item.optString(Text.ATTR_ID);
        if (!id.matches("\\d+")) {
            throw new IllegalArgumentException("无效剧集");
        }
        JSONObject bookmarks = object("bookmarks");
        JSONObject deleted = object("bookmarkDeleted");
        if (bookmarks.has(id)) {
            bookmarks.remove(id);
            deleted.put(id, System.currentTimeMillis());
            this.preferences.edit().putString(scoped("bookmarks"), bookmarks.toString()).putString(scoped("bookmarkDeleted"), deleted.toString()).apply();
            return false;
        }
        JSONObject record = new JSONObject();
        record.put(Text.ATTR_ID, id);
        record.put(Json.TITLE, item.optString(Json.TITLE));
        record.put("cover", item.optString("cover"));
        record.put("addedAt", System.currentTimeMillis());
        bookmarks.put(id, record);
        deleted.remove(id);
        this.preferences.edit().putString(scoped("bookmarks"), bookmarks.toString()).putString(scoped("bookmarkDeleted"), deleted.toString()).apply();
        return true;
    }

    synchronized boolean hasBookmark(String id) {
        return object("bookmarks").has(id);
    }

    synchronized JSONObject getProgress(String id, int episode) {
        return object(NotificationCompat.CATEGORY_PROGRESS).optJSONObject(id + ":" + episode);
    }

    /** 取某部剧最新的一条进度：同一部剧每集各一条记录，按 updated_at 比。 */
    synchronized JSONObject latestProgress(String seriesId) {
        JSONObject latest = null;
        JSONObject progress = object(NotificationCompat.CATEGORY_PROGRESS);
        Iterator<String> keys = progress.keys();
        while (keys.hasNext()) {
            JSONObject entry = progress.optJSONObject(keys.next());
            if (entry != null && seriesId.equals(entry.optString(Text.ATTR_ID)) && (latest == null || entry.optLong(ParseObject.KEY_UPDATED_AT) > latest.optLong(ParseObject.KEY_UPDATED_AT))) {
                latest = entry;
            }
        }
        return latest;
    }

    synchronized void recordProgress(JSONObject input) throws Exception {
        String id = input.optString(Text.ATTR_ID);
        int episode = input.optInt("episode", 1);
        long position = input.optLong("position");
        long duration = input.optLong("duration");
        if (id.matches("\\d+") && episode >= 1 && duration > 0) {
            JSONObject progress = object(NotificationCompat.CATEGORY_PROGRESS);
            JSONObject entry = new JSONObject();
            entry.put(Text.ATTR_ID, id);
            entry.put("episode", episode);
            entry.put("episodeCount", input.optInt("episodeCount"));
            entry.put(Json.TITLE, input.optString(Json.TITLE));
            entry.put("cover", input.optString("cover"));
            entry.put("source", input.optString("source"));
            // 进度超出片长（换集、切源、时长取错）会把播放器卡在结尾，这里夹一下。
            entry.put("position", Math.max(0L, Math.min(position, duration)));
            entry.put("duration", duration);
            entry.put("completed", input.optBoolean("completed"));
            entry.put(ParseObject.KEY_UPDATED_AT, System.currentTimeMillis());
            progress.put(id + ":" + episode, entry);
            JSONObject deleted = object("historyDeleted");
            // 重新看了就撤销墓碑，否则这条记录下次同步会被当成「已删」。
            deleted.remove(id);
            Iterator<String> trimmed = trimProgress(progress).iterator();
            while (trimmed.hasNext()) {
                deleted.put(trimmed.next(), System.currentTimeMillis());
            }
            this.preferences.edit().putString(scoped(NotificationCompat.CATEGORY_PROGRESS), progress.toString()).putString(scoped("historyDeleted"), deleted.toString()).apply();
        }
    }

    /**
     * 按剧集去重后只留 50 条：同一部剧保留它最新那集的时间做排序依据。
     * 返回被裁掉的剧集 id，调用方负责把它们写进墓碑。
     */
    private static Set<String> trimProgress(JSONObject progress) {
        final HashMap latestAt = new HashMap();
        Iterator<String> keys = progress.keys();
        while (keys.hasNext()) {
            JSONObject entry = progress.optJSONObject(keys.next());
            if (entry != null) {
                String id = entry.optString(Text.ATTR_ID);
                latestAt.put(id, Long.valueOf(Math.max(((Long) latestAt.getOrDefault(id, 0L)).longValue(), entry.optLong(ParseObject.KEY_UPDATED_AT))));
            }
        }
        if (latestAt.size() <= 50) {
            return new HashSet();
        }
        ArrayList idsOldestFirst = new ArrayList(latestAt.keySet());
        idsOldestFirst.sort(new Comparator() {
            @Override
            public final int compare(Object left, Object right) {
                return Long.compare(((Long) latestAt.get((String) right)).longValue(), ((Long) latestAt.get((String) left)).longValue());
            }
        });
        HashSet keepIds = new HashSet(idsOldestFirst.subList(0, 50));
        ArrayList removeKeys = new ArrayList();
        HashSet trimmedIds = new HashSet();
        Iterator<String> remaining = progress.keys();
        while (remaining.hasNext()) {
            String key = remaining.next();
            JSONObject entry = progress.optJSONObject(key);
            if (entry == null || !keepIds.contains(entry.optString(Text.ATTR_ID))) {
                removeKeys.add(key);
                if (entry != null) {
                    trimmedIds.add(entry.optString(Text.ATTR_ID));
                }
            }
        }
        Iterator it = removeKeys.iterator();
        while (it.hasNext()) {
            progress.remove((String) it.next());
        }
        return trimmedIds;
    }

    /** 「我的」页要的库：收藏按 addedAt 倒序，历史按 updated_at 倒序，各取一份。 */
    synchronized JSONObject library() throws Exception {
        JSONObject result = new JSONObject();
        JSONObject bookmarks = object("bookmarks");
        JSONObject progress = object(NotificationCompat.CATEGORY_PROGRESS);
        Set<String> trimmedIds = trimProgress(progress);
        if (!trimmedIds.isEmpty()) {
            JSONObject deleted = object("historyDeleted");
            Iterator<String> trimmed = trimmedIds.iterator();
            while (trimmed.hasNext()) {
                deleted.put(trimmed.next(), System.currentTimeMillis());
            }
            this.preferences.edit().putString(scoped(NotificationCompat.CATEGORY_PROGRESS), progress.toString()).putString(scoped("historyDeleted"), deleted.toString()).apply();
        }
        ArrayList bookmarkList = new ArrayList();
        HashMap latestBySeries = new HashMap();
        Iterator<String> keys = bookmarks.keys();
        while (keys.hasNext()) {
            JSONObject entry = bookmarks.optJSONObject(keys.next());
            if (entry != null) {
                bookmarkList.add(entry);
            }
        }
        Iterator<String> progressKeys = progress.keys();
        while (progressKeys.hasNext()) {
            JSONObject entry = progress.optJSONObject(progressKeys.next());
            // 只有看得差不多（追完，或过了 5 秒）才进历史，点开就退的别污染列表。
            if (entry != null && (entry.optBoolean("completed") || entry.optLong("position") > 5000)) {
                String id = entry.optString(Text.ATTR_ID);
                JSONObject existing = (JSONObject) latestBySeries.get(id);
                if (existing == null || entry.optLong(ParseObject.KEY_UPDATED_AT) > existing.optLong(ParseObject.KEY_UPDATED_AT)) {
                    latestBySeries.put(id, entry);
                }
            }
        }
        ArrayList historyList = new ArrayList(latestBySeries.values());
        bookmarkList.sort(Comparator.comparingLong(new ToLongFunction() {
            @Override
            public final long applyAsLong(Object record) {
                return ((JSONObject) record).optLong("addedAt");
            }
        }).reversed());
        historyList.sort(Comparator.comparingLong(new ToLongFunction() {
            @Override
            public final long applyAsLong(Object record) {
                return ((JSONObject) record).optLong(ParseObject.KEY_UPDATED_AT);
            }
        }).reversed());
        JSONArray bookmarkArray = new JSONArray();
        JSONArray historyArray = new JSONArray();
        Iterator bookmarkIt = bookmarkList.iterator();
        while (bookmarkIt.hasNext()) {
            bookmarkArray.put((JSONObject) bookmarkIt.next());
        }
        Iterator historyIt = historyList.iterator();
        while (historyIt.hasNext()) {
            historyArray.put((JSONObject) historyIt.next());
        }
        result.put("bookmarks", bookmarkArray);
        result.put(NotificationCompat.CATEGORY_PROGRESS, historyArray);
        return result;
    }

    /**
     * 上传给云端的形状：收藏和进度都按 id 摊成 map（服务端按 id 合并），
     * 外加两份墓碑。注意这里用 history，本地那侧叫 progress，
     * 是 Parse 那侧的历史遗留，改不得。
     */
    synchronized JSONObject cloudSnapshot() throws Exception {
        JSONObject bookmarkMap = new JSONObject();
        JSONObject historyMap = new JSONObject();
        JSONObject library = library();
        JSONArray bookmarks = library.getJSONArray("bookmarks");
        JSONArray history = library.getJSONArray(NotificationCompat.CATEGORY_PROGRESS);
        for (int index = 0; index < bookmarks.length(); index++) {
            JSONObject entry = bookmarks.optJSONObject(index);
            if (entry != null) {
                bookmarkMap.put(entry.optString(Text.ATTR_ID), entry);
            }
        }
        for (int index = 0; index < history.length(); index++) {
            JSONObject entry = history.optJSONObject(index);
            if (entry != null) {
                historyMap.put(entry.optString(Text.ATTR_ID), entry);
            }
        }
        return new JSONObject().put("bookmarks", bookmarkMap).put("history", historyMap).put("bookmarkDeleted", object("bookmarkDeleted")).put("historyDeleted", object("historyDeleted"));
    }

    /** 云端拉回来的快照并到本地：墓碑优先删，同 key 比时间戳，最后整体落盘。 */
    synchronized void applyCloudSnapshot(JSONObject snapshot) throws Exception {
        JSONObject cloudBookmarks = snapshot.optJSONObject("bookmarks");
        JSONObject cloudHistory = snapshot.optJSONObject("history");
        JSONObject cloudBookmarkDeleted = snapshot.optJSONObject("bookmarkDeleted");
        JSONObject cloudHistoryDeleted = snapshot.optJSONObject("historyDeleted");
        // 收藏那半和进度走同一套墓碑合并：本地有、云端无、且没被云端墓碑标记的
        // 键保留，否则多设备场景下本地还没上过云的收藏会被整份覆盖掉。
        JSONObject bookmarks = object("bookmarks");
        if (cloudBookmarkDeleted != null) {
            ArrayList expired = new ArrayList();
            Iterator<String> keys = bookmarks.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject entry = bookmarks.optJSONObject(key);
                if (entry != null && cloudBookmarkDeleted.optLong(entry.optString(Text.ATTR_ID)) >= entry.optLong("addedAt")) {
                    expired.add(key);
                }
            }
            Iterator it = expired.iterator();
            while (it.hasNext()) {
                bookmarks.remove((String) it.next());
            }
        }
        if (cloudBookmarks != null) {
            Iterator<String> cloudIds = cloudBookmarks.keys();
            while (cloudIds.hasNext()) {
                String bookmarkId = cloudIds.next();
                JSONObject cloudEntry = cloudBookmarks.optJSONObject(bookmarkId);
                if (cloudEntry != null) {
                    JSONObject localEntry = bookmarks.optJSONObject(bookmarkId);
                    if (localEntry == null || cloudEntry.optLong("addedAt") >= localEntry.optLong("addedAt")) {
                        bookmarks.put(bookmarkId, cloudEntry);
                    }
                }
            }
        }
        JSONObject progress = object(NotificationCompat.CATEGORY_PROGRESS);
        if (cloudHistoryDeleted != null) {
            ArrayList expired = new ArrayList();
            Iterator<String> keys = progress.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject entry = progress.optJSONObject(key);
                if (entry != null && cloudHistoryDeleted.optLong(entry.optString(Text.ATTR_ID)) >= entry.optLong(ParseObject.KEY_UPDATED_AT)) {
                    expired.add(key);
                }
            }
            Iterator it = expired.iterator();
            while (it.hasNext()) {
                progress.remove((String) it.next());
            }
        }
        if (cloudHistory != null) {
            Iterator<String> cloudIds = cloudHistory.keys();
            while (cloudIds.hasNext()) {
                String seriesId = cloudIds.next();
                JSONObject cloudEntry = cloudHistory.optJSONObject(seriesId);
                if (cloudEntry != null) {
                    String progressKey = seriesId + ":" + cloudEntry.optInt("episode", 1);
                    JSONObject localEntry = progress.optJSONObject(progressKey);
                    if (localEntry == null || cloudEntry.optLong(ParseObject.KEY_UPDATED_AT) >= localEntry.optLong(ParseObject.KEY_UPDATED_AT)) {
                        progress.put(progressKey, cloudEntry);
                    }
                }
            }
        }
        trimProgress(progress);
        // bookmarks/bookmarkDeleted 落盘的是合并结果，不再整份照抄云端。
        this.preferences.edit().putString(scoped("bookmarks"), bookmarks.toString()).putString(scoped(NotificationCompat.CATEGORY_PROGRESS), progress.toString()).putString(scoped("bookmarkDeleted"), cloudBookmarkDeleted == null ? "{}" : cloudBookmarkDeleted.toString()).putString(scoped("historyDeleted"), cloudHistoryDeleted == null ? "{}" : cloudHistoryDeleted.toString()).apply();
    }

    synchronized void clearHistory() {
        JSONObject progress = object(NotificationCompat.CATEGORY_PROGRESS);
        JSONObject deleted = object("historyDeleted");
        long now = System.currentTimeMillis();
        Iterator<String> keys = progress.keys();
        while (keys.hasNext()) {
            JSONObject entry = progress.optJSONObject(keys.next());
            if (entry != null) {
                try {
                    deleted.put(entry.optString(Text.ATTR_ID), now);
                } catch (Exception unused) {
                }
            }
        }
        this.preferences.edit().remove(scoped(NotificationCompat.CATEGORY_PROGRESS)).putString(scoped("historyDeleted"), deleted.toString()).apply();
    }

    synchronized void clearBookmarks() {
        JSONObject bookmarks = object("bookmarks");
        JSONObject deleted = object("bookmarkDeleted");
        long now = System.currentTimeMillis();
        Iterator<String> keys = bookmarks.keys();
        while (keys.hasNext()) {
            try {
                deleted.put(keys.next(), now);
            } catch (Exception unused) {
            }
        }
        this.preferences.edit().remove(scoped("bookmarks")).putString(scoped("bookmarkDeleted"), deleted.toString()).apply();
    }
}
