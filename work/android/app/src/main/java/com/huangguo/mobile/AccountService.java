package com.huangguo.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;
import com.parse.ParseACL;
import com.parse.ParseException;
import com.parse.ParseFile;
import com.parse.ParseObject;
import com.parse.ParseQuery;
import com.parse.ParseUser;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/**
 * AIIvideo —— 账号服务。
 *
 * 一个账号能做的事都在这儿：注册、登录、改密码、换头像、把片单同步到云端。
 * 没有本地账号体系，一切都压在 Parse 上 —— ParseUser 是账号，ParseObject 是片单。
 *
 * ── 后端 ────────────────────────────────────────────────────────────
 * 自建的 Parse REST 子集，地址见 Endpoints.API_BASE。
 * 地址、ApplicationId、ClientKey 三项都在 HuangGuoApp.onCreate() 里喂给
 * Parse.initialize()，其中 ApplicationId 取自 R.string.back4app_app_id、ClientKey 取自
 * R.string.back4app_client_key —— 名字里的 back4app 是历史遗留，指向的其实是自己的服务器。
 * 下面出现的字段名（username / password / avatar / owner / source / payload /
 * updatedAt）直接就是后端 schema，不是随手起的变量名。
 *
 * ── 云端片单 ────────────────────────────────────────────────────────
 * 整个片单塞进一条 ParseObject 的 payload 字段，JSON 字符串，不拆表：
 *   "huangguo" 源 → Parse 类 UserLibrary
 *   其它源        → DualUserLibrary，并且查询时多带一个 source 条件
 * 一条记录 = 一个用户在该源下的全部书签与历史。sync() 取最新一条、跟本地 merge() 之后
 * 写回；merge() 是按 updatedAt 的逐条 last-write-wins，删除用 tombstone（*Deleted 组）
 * 表示，历史超过 50 条的旧记录顺手转成 tombstone 清出去。
 *
 * ── 头像 ────────────────────────────────────────────────────────────
 * 原图走 cropAvatar()：读 EXIF 方向 → 长边缩到 768 以内 → 居中裁正方形 → 256×256 JPEG。
 * 结果上传成 ParseUser 的 ParseFile("avatar")，同时在本地留一份
 *   files/avatars/<objectId>.jpg
 * 所以断网也有头像可显示。SharedPreferences "huangguo-avatars" 记的是「上次缓存的是哪个
 * ParseFile」，文件名变了（用户确实换了头像）才重新下载 —— 否则每次刷新都拉一遍原图。
 *
 * ── 和 AppStore 的关系 ──────────────────────────────────────────────
 * AppStore 只管本地 SharedPreferences 里的书签和历史，完全不知道账号的存在。
 * 这个类是两者之间唯一的桥：登录成功后调 store.activateUser(objectId) 把本地读写切到
 * 对应账号下，sync() 用 store.cloudSnapshot() / applyCloudSnapshot() 交换整份快照。
 * 也就是说 AppStore 不碰网络，这里不碰 SharedPreferences（头像那一个除外）。
 *
 * 另外 refreshProfile() 有 5 分钟节流。别在界面里直接拿 profile(true) 绕过去 ——
 * 每次 fetch 都是一次真实请求，切个 Tab 就发一次会被后端限流。
 */
final class AccountService {
    /** profile(true) 的最小刷新间隔。5 分钟。 */
    private static final long PROFILE_REFRESH_INTERVAL_MS = 300000L;
    /** 头像本地缓存目录，位于 files/ 下，卸载即清。 */
    private static final String AVATAR_DIR = "avatars";
    /** 记录「本地缓存的是哪个 ParseFile」，用于判断要不要重新下载。 */
    private static final String AVATAR_PREFS = "huangguo-avatars";
    /** 历史最多留这么多条，多出来的转 tombstone。 */
    private static final int HISTORY_LIMIT = 50;

    private final Context context;
    private volatile long lastProfileRefresh;
    private final AtomicBoolean refreshingProfile = new AtomicBoolean();
    private final AppStore store;

    AccountService(Context context, AppStore appStore) {
        this.context = context.getApplicationContext();
        this.store = appStore;
    }

    JSONObject profile() throws Exception {
        return profile(false);
    }

    JSONObject refreshProfile() throws Exception {
        if (!this.refreshingProfile.compareAndSet(false, true)) {
            return profile(false);
        }
        try {
            return profile(true);
        } finally {
            this.refreshingProfile.set(false);
        }
    }

    private JSONObject profile(boolean refresh) throws Exception {
        ParseUser currentUser = ParseUser.getCurrentUser();
        if (currentUser == null) {
            return new JSONObject().put("loggedIn", false);
        }
        if (refresh && System.currentTimeMillis() - this.lastProfileRefresh > PROFILE_REFRESH_INTERVAL_MS) {
            try {
                currentUser.fetch();
                this.lastProfileRefresh = System.currentTimeMillis();
            } catch (Exception unused) {
                // 刷新失败不算错（本地这份还能用），但失败本身要留痕，
                // 否则「资料一直是旧的」查不出是没到间隔还是一直在失败。
                Log.w("AIIvideo", "[account] profile 刷新失败，先用本地缓存：" + unused);
            }
        }
        String userId = currentUser.getObjectId();
        JSONObject result = new JSONObject()
                .put("loggedIn", true)
                .put("username", currentUser.getUsername())
                .put("userId", userId);
        File avatarFile = new File(new File(this.context.getFilesDir(), AVATAR_DIR), userId + ".jpg");
        SharedPreferences avatarPrefs = this.context.getSharedPreferences(AVATAR_PREFS, 0);
        ParseFile remoteAvatar = currentUser.getParseFile("avatar");
        if (remoteAvatar != null) {
            String remoteName = remoteAvatar.getName();
            // 只有真刷新时才算这一步：本地没文件，或者缓存的 ParseFile 名字对不上。
            boolean needsDownload = refresh
                    && (!avatarFile.isFile() || !remoteName.equals(avatarPrefs.getString(userId, "")));
            if (needsDownload) {
                try {
                    saveAvatar(avatarFile, remoteAvatar.getData());
                    avatarPrefs.edit().putString(userId, remoteName).apply();
                } catch (Exception unused2) {
                    // 下载失败本地留旧图，但得留痕：不记的话「头像一直是旧的」
                    // 分不清是没换过还是每次都下失败。
                    Log.w("AIIvideo", "[account] 头像下载失败，本地留旧图：" + unused2);
                }
            }
        }
        if (avatarFile.isFile()) {
            result.put("avatar", "data:image/jpeg;base64," + Base64.encodeToString(Files.readAllBytes(avatarFile.toPath()), 2));
        }
        return result;
    }

    /**
     * 落一份头像到本地：先写临时文件，再原子 rename 覆盖目标。
     * 先写临时文件是为了避免「写了一半被读到」—— 播放器线程随时可能在读这张图。
     */
    private static void saveAvatar(File file, byte[] bytes) throws Exception {
        file.getParentFile().mkdirs();
        File tempFile = File.createTempFile("avatar-", ".tmp", file.getParentFile());
        try {
            try (FileOutputStream out = new FileOutputStream(tempFile)) {
                out.write(bytes);
            }
            Files.move(tempFile.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    synchronized JSONObject register(String username, String password) throws Exception {
        validate(username, password);
        ParseUser newUser = new ParseUser();
        newUser.setUsername(username);
        newUser.setPassword(password);
        try {
            newUser.signUp();
            this.store.activateUser(newUser.getObjectId());
        } catch (ParseException e) {
            // 119 = ACL/权限被后端拒了，多半是账号创建权限没开
            if (e.getCode() == 119) {
                throw new IllegalStateException("当前无法注册，请联系管理员检查账号创建权限");
            }
            throw e;
        }
        return profile(false);
    }

    synchronized JSONObject login(String username, String password) throws Exception {
        validate(username, password);
        try {
            this.store.activateUser(ParseUser.logIn(username, password).getObjectId());
        } catch (ParseException e) {
            // 101 = 用户名或密码不对
            if (e.getCode() == 101) {
                throw new IllegalArgumentException("账号不存在或密码错误，请检查后重试");
            }
            throw e;
        }
        return profile(false);
    }

    synchronized JSONObject changePassword(String oldPassword, String newPassword) throws Exception {
        ParseUser currentUser = ParseUser.getCurrentUser();
        if (currentUser == null) {
            throw new IllegalStateException("请先登录账号");
        }
        if (oldPassword == null || oldPassword.isEmpty()) {
            throw new IllegalArgumentException("请输入旧密码");
        }
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("新密码至少需要 8 位");
        }
        if (newPassword.equals(oldPassword)) {
            throw new IllegalArgumentException("新密码不能与旧密码相同");
        }
        String username = currentUser.getUsername();
        try {
            // Parse 改密码没有「验证旧密码」的接口，只能重新 logIn 一次确认。
            ParseUser verified = ParseUser.logIn(username, oldPassword);
            if (!currentUser.getObjectId().equals(verified.getObjectId())) {
                throw new IllegalStateException("账号验证失败，请重新登录");
            }
            verified.setPassword(newPassword);
            verified.save();
            // 改完密码会话会失效，用新密码重新登一次拿回 sessionToken。
            ParseUser.logIn(username, newPassword);
        } catch (ParseException e) {
            if (e.getCode() == 101) {
                throw new IllegalArgumentException("旧密码不正确");
            }
            throw e;
        }
        return profile();
    }

    private static void validate(String username, String password) {
        if (username == null || !username.matches("[A-Za-z0-9_]{3,24}")) {
            throw new IllegalArgumentException("账号需为 3–24 位字母、数字或下划线");
        }
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("密码至少需要 8 位");
        }
    }

    synchronized void logout() throws Exception {
        // 退登前尽力同步一次，失败就放弃 —— 本地数据不会丢，下次登录照样能合回去。
        if (ParseUser.getCurrentUser() != null) {
            try {
                sync();
            } catch (Exception unused) {
            }
        }
        ParseUser.logOut();
        this.store.activateUser("");
    }

    public synchronized JSONObject sync() throws Exception {
        ParseUser currentUser = ParseUser.getCurrentUser();
        if (currentUser == null) {
            return new JSONObject().put("loggedIn", false);
        }
        this.store.activateUser(currentUser.getObjectId());
        JSONObject localSnapshot = this.store.cloudSnapshot();
        String libraryClass = "huangguo".equals(this.store.source) ? "UserLibrary" : "DualUserLibrary";
        ParseObject libraryObject = null;
        try {
            ParseQuery query = ParseQuery.getQuery(libraryClass);
            query.whereEqualTo("owner", currentUser);
            if ("hongguo".equals(this.store.source)) {
                query.whereEqualTo("source", this.store.source);
            }
            query.orderByDescending(ParseObject.KEY_UPDATED_AT);
            query.setLimit(1);
            List results = query.find();
            libraryObject = !results.isEmpty() ? (ParseObject) results.get(0) : null;
        } catch (ParseException e) {
            // 101 = 查询条件不匹配（这个账号还没同步过），新建一条就是，不算错误。
            if (e.getCode() != 101) {
                throw e;
            }
        }
        String payloadJson = null;
        if (libraryObject != null) {
            payloadJson = libraryObject.getString(VlcPlayerActivity.EXTRA_PAYLOAD);
        }
        if (payloadJson == null || payloadJson.isEmpty()) {
            payloadJson = "{}";
        }
        JSONObject remoteSnapshot = new JSONObject(payloadJson);
        JSONObject mergedSnapshot = merge(localSnapshot, remoteSnapshot);
        this.store.applyCloudSnapshot(mergedSnapshot);
        // 云端和合并结果不一致才回写，避免每次同步都产生一次写请求。
        if (libraryObject == null || !mergedSnapshot.toString().equals(remoteSnapshot.toString())) {
            if (libraryObject == null) {
                libraryObject = new ParseObject(libraryClass);
            }
            libraryObject.put("owner", currentUser);
            libraryObject.put("source", this.store.source);
            libraryObject.put(VlcPlayerActivity.EXTRA_PAYLOAD, mergedSnapshot.toString());
            libraryObject.setACL(new ParseACL(currentUser));
            libraryObject.save();
        }
        return new JSONObject().put("loggedIn", true).put("synced", true);
    }

    /**
     * 本地快照和云端快照的逐条合并。
     *
     * bookmarks / history 两组都用 mergeGroup()；history 多一步裁剪到 HISTORY_LIMIT 条，
     * 多出来的写进 historyDeleted 当墓碑 —— 不然云端那条 payload 会一直涨。
     */
    private static JSONObject merge(JSONObject local, JSONObject remote) throws Exception {
        JSONObject merged = new JSONObject();
        mergeGroup(merged, "bookmarks", "bookmarkDeleted", "addedAt", local, remote);
        mergeGroup(merged, "history", "historyDeleted", ParseObject.KEY_UPDATED_AT, local, remote);
        JSONObject history = merged.getJSONObject("history");
        List<String> orderedIds = new ArrayList<>();
        Iterator<String> itKeys = history.keys();
        while (itKeys.hasNext()) {
            orderedIds.add(itKeys.next());
        }
        // 按 updatedAt 倒序，最新的排前面，后面的才裁得掉。
        orderedIds.sort((left, right) -> Long.compare(
                history.optJSONObject(right).optLong(ParseObject.KEY_UPDATED_AT),
                history.optJSONObject(left).optLong(ParseObject.KEY_UPDATED_AT)));
        JSONObject deletedHistory = merged.getJSONObject("historyDeleted");
        for (int i = HISTORY_LIMIT; i < orderedIds.size(); i++) {
            String id = orderedIds.get(i);
            // 墓碑时间必须严格大于记录本身的 updatedAt，否则合并时会被当成「删除更早」而复活。
            deletedHistory.put(id, Math.max(System.currentTimeMillis(),
                    history.optJSONObject(id).optLong(ParseObject.KEY_UPDATED_AT) + 1));
            history.remove(id);
        }
        return merged;
    }

    /**
     * 合并一组「记录 + 墓碑」。
     *
     * target 里写回两个 key：groupKey 放合并后的记录，deletedKey 放合并后的墓碑。
     * 单条记录的胜负只看 timeKey（书签看 addedAt，历史看 updatedAt）：
     *   记录取本地/云端里时间大的那个；
     *   墓碑取两边时间大的那个，且必须 >= 记录时间才生效。
     */
    private static void mergeGroup(JSONObject target, String groupKey, String deletedKey, String timeKey,
                                   JSONObject local, JSONObject remote) throws Exception {
        JSONObject localGroup = local.optJSONObject(groupKey);
        JSONObject remoteGroup = remote.optJSONObject(groupKey);
        JSONObject localDeleted = local.optJSONObject(deletedKey);
        JSONObject remoteDeleted = remote.optJSONObject(deletedKey);
        if (localGroup == null) {
            localGroup = new JSONObject();
        }
        if (remoteGroup == null) {
            remoteGroup = new JSONObject();
        }
        if (localDeleted == null) {
            localDeleted = new JSONObject();
        }
        if (remoteDeleted == null) {
            remoteDeleted = new JSONObject();
        }
        JSONObject mergedGroup = new JSONObject();
        JSONObject mergedDeleted = new JSONObject();
        // 四份 JSON 的 key 并集，逐条决胜负。
        HashSet<String> keys = new HashSet<>();
        JSONObject[] sources = {localGroup, remoteGroup, localDeleted, remoteDeleted};
        for (JSONObject source : sources) {
            Iterator<String> itKeys = source.keys();
            while (itKeys.hasNext()) {
                keys.add(itKeys.next());
            }
        }
        for (String id : keys) {
            JSONObject localEntry = localGroup.optJSONObject(id);
            JSONObject remoteEntry = remoteGroup.optJSONObject(id);
            if (remoteEntry != null && (localEntry == null || localEntry.optLong(timeKey) <= remoteEntry.optLong(timeKey))) {
                localEntry = remoteEntry;
            }
            long updatedAt = localEntry == null ? 0L : localEntry.optLong(timeKey);
            long deletedAt = Math.max(localDeleted.optLong(id), remoteDeleted.optLong(id));
            if (deletedAt >= updatedAt && deletedAt > 0) {
                mergedDeleted.put(id, deletedAt);
            } else if (localEntry != null) {
                mergedGroup.put(id, localEntry);
            }
        }
        target.put(groupKey, mergedGroup).put(deletedKey, mergedDeleted);
    }

    synchronized JSONObject setAvatar(Uri uri) throws Exception {
        ParseUser currentUser = ParseUser.getCurrentUser();
        if (currentUser == null) {
            throw new IllegalStateException("请先登录");
        }
        byte[] avatarBytes = cropAvatar(uri);
        ParseFile avatarFile = new ParseFile("avatar.jpg", avatarBytes);
        avatarFile.save();
        currentUser.put("avatar", avatarFile);
        currentUser.save();
        saveAvatar(new File(new File(this.context.getFilesDir(), AVATAR_DIR), currentUser.getObjectId() + ".jpg"), avatarBytes);
        this.context.getSharedPreferences(AVATAR_PREFS, 0).edit().putString(currentUser.getObjectId(), avatarFile.getName()).apply();
        return profile();
    }

    /**
     * 头像裁剪：EXIF 方向 → 长边缩到 768 以内 → 居中裁正方形 → 256×256 JPEG。
     *
     * 读 EXIF 失败不是错误：方向按 1（不旋转）处理，图照裁不误。
     * 同一张图要开三次流，因为 BitmapFactory 只解码当前设置的Options，
     * 先量尺寸（inJustDecodeBounds）、再按 sampleSize 真解码，是标准做法。
     */
    private byte[] cropAvatar(Uri uri) throws Exception {
        // 这一段故意不用 try-with-resources：关流本身失败要吞掉，方向已经读到了就不该让
        // close 的异常打断流程，而 try-with-resources 会把 close 异常抛出来。
        int attributeInt = 1;
        try {
            InputStream exifStream = this.context.getContentResolver().openInputStream(uri);
            try {
                attributeInt = new ExifInterface(exifStream)
                        .getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1);
            } finally {
                if (exifStream != null) {
                    try {
                        exifStream.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception unused) {
            attributeInt = 1;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream boundsStream = this.context.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(boundsStream, null, options);
        }

        int sampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / sampleSize > 768) {
            sampleSize *= 2;
        }
        options.inJustDecodeBounds = false;
        options.inSampleSize = sampleSize;

        Bitmap bitmap;
        try (InputStream imageStream = this.context.getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(imageStream, null, options);
        }
        if (bitmap == null) {
            throw new IllegalArgumentException("无法读取这张图片");
        }

        int rotation = 0;
        if (attributeInt == 6) {
            rotation = 90;
        } else if (attributeInt == 3) {
            rotation = 180;
        } else if (attributeInt == 8) {
            // 修复反编译错位：原来被换成 MediaPlayer.Event.PausableChanged（267）。
            // EXIF orientation 8 = 拍的时候横握，回正要顺时针转 270 度。
            rotation = 270;
        }
        if (rotation != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(rotation);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            bitmap.recycle();
            bitmap = rotated;
        }

        int side = Math.min(bitmap.getWidth(), bitmap.getHeight());
        Bitmap cropped = Bitmap.createBitmap(bitmap,
                (bitmap.getWidth() - side) / 2, (bitmap.getHeight() - side) / 2, side, side);
        Bitmap scaled = Bitmap.createScaledBitmap(cropped, 256, 256, true);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, output);
        // createBitmap 在「尺寸没变」时会直接把入参还回来，那种时候不能 recycle 两次。
        if (scaled != cropped) {
            scaled.recycle();
        }
        if (cropped != bitmap) {
            cropped.recycle();
        }
        bitmap.recycle();
        return output.toByteArray();
    }
}
