package com.huangguo.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import java.io.File;
import java.util.ArrayList;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 应用自更新：查清单、比版本、下载、校验、交给系统安装。
 *
 * ── 和 UpdateMirror 的分工 ──
 * 这个类只管「什么版本、要不要下、下到哪」，真正的 HTTP 全在 UpdateMirror：
 * 那边手动跟 302（GitHub 的 release 附件跳到 objects.githubusercontent.com、
 * 镜像站跳到自己的 CDN，而 HttpURLConnection 的自动重定向只认同主机，不跟就
 * 卡在 0%）、64KB buffer 边下边算 sha256、多镜像挑一个能通的。
 *
 * ── 清单 ──
 * android-update.json：versionCode / versionName / tag / apk / sha256 / size / urls / notes。
 * candidateFrom 里四道闸门：versionName 三段数字、tag == "v"+versionName、
 * apk == "AIIvideo-v"+versionName+"-release.apk"、sha256 是 64 位十六进制。
 * 一条对不上整份作废 —— 宁可报「没有发布信息」，也不装来路不明的包。
 *
 * ── 状态 ──
 * SharedPreferences "updates"：
 *   available_manifest  最近一次清单原文，cached() 从这里回放
 *   manual_checks       手点时间戳（逗号分隔），MANUAL_LIMIT 限流用
 *   last_auto_check     上次检查时间，AUTO_INTERVAL_MS 内不打网络
 *
 * ── status 取值 ──
 * none / throttled / unpublished / current / available，前端按它分支。
 */
final class UpdateManager {
    private static final long AUTO_INTERVAL_MS = 43200000;
    /**
     * 自动检查失败后的重试间隔。
     *
     * 计时器是在打网络**之前**写的（这样离线状态下反复冷启动也不会每次都去撞墙），
     * 代价是一次失败就把整段 12 小时窗口用掉了 —— 第二天才补一次，用户当版
     * 早就该升了。所以失败时把时间戳往回拨到「12 小时前 + 30 分钟」，下一次
     * 冷启动 30 分钟后再试；离线连撞几次也是每半小时一次，不心疼。
     */
    private static final long AUTO_RETRY_MS = 1800000;
    private static final String MANIFEST_URL = Endpoints.UPDATE_MANIFEST;
    private static final int MANUAL_LIMIT = 5;
    private static final long MANUAL_WINDOW_MS = 3600000;
    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+");
    private Candidate candidate;
    private final Context context;
    /** volatile：isDownloading() 是给桥做防重入预检的，不能等锁（download() 会握锁几分钟）。 */
    private volatile boolean downloading;
    private final SharedPreferences preferences;

    interface Progress {
        void onPercent(int i);
    }

    private static JSONObject fetchManifest() throws Exception {
        String body = UpdateMirror.fetch(MANIFEST_URL, 15);
        return body == null ? null : new JSONObject(body);
    }

    /**
     * 桥的 download-update 处理器在入口用它做防重入预检。故意**不加 synchronized**：
     * download() 全程握着实例锁，同步读会等几分钟才返回，而且等到了多半是 false，
     * 预检就失去了意义。读 volatile，瞬时返回。
     */
    public boolean isDownloading() {
        return this.downloading;
    }

    synchronized File download(Progress progress) throws Exception {
        Candidate target = this.candidate;
        if (this.downloading || target == null) {
            return null;
        }
        File dir = new File(this.context.getCacheDir(), "updates");
        dir.mkdirs();
        File apk = new File(dir, "AIIvideo-v" + target.version + "-release.apk");
        // 清掉别的版本留下的旧安装包，别让 40MB 一份的文件在缓存里攒着。
        File[] stale = dir.listFiles();
        if (stale != null) {
            for (File oldApk : stale) {
                if (!oldApk.getName().equals(apk.getName()) && !oldApk.delete()) {
                    Log.d("AIIvideo", "[update] 旧安装包删除失败：" + oldApk.getName());
                }
            }
        }
        this.downloading = true;
        try {
            // 挑源（pick 会并发探速）放到真正要下载的这一刻才做：check()/cached()
            // 是查更新路径，cached() 还是节流命中的回放缓存，都不该碰网络。
            return UpdateMirror.download(UpdateMirror.pick(target.mirrors), apk, progress, target.digest);
        } finally {
            this.downloading = false;
        }
    }

    private static final class Candidate {
        String digest;
        String notes;
        long size;
        /** 镜像原文（JSON 数组字符串）。挑最快的源放到 download() 时做，见 download() 内注释。 */
        String mirrors;
        String version;

        private Candidate() {
        }
    }

    UpdateManager(Context context) {
        Context applicationContext = context.getApplicationContext();
        this.context = applicationContext;
        this.preferences = applicationContext.getSharedPreferences("updates", 0);
    }

    synchronized JSONObject cached() throws Exception {
        String manifestJson = this.preferences.getString("available_manifest", null);
        if (manifestJson == null) {
            return status("none");
        }
        try {
            JSONObject manifest = new JSONObject(manifestJson);
            PackageInfo self = this.context.getPackageManager().getPackageInfo(this.context.getPackageName(), 0);
            if (manifest.optLong("versionCode") <= installedVersionCode(self)) {
                clearCandidate();
                return status("none");
            }
            Candidate parsed = candidateFrom(manifest);
            this.candidate = parsed;
            return availableStatus(parsed, self.versionName);
        } catch (Exception unused) {
            clearCandidate();
            return status("none");
        }
    }

    /** SDK 28 起 versionCode 是 long，之前是 int。两处比较都要走这里，别再各写一份。 */
    private static long installedVersionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private void clearCandidate() {
        this.candidate = null;
        this.preferences.edit().remove("available_manifest").apply();
    }

    /**
     * 校验并解析发布清单。四道闸门缺一不可：
     * versionName 必须是三段数字、tag 必须是 "v" + versionName、apk 文件名必须按
     * AIIvideo-v<版本>-release.apk 拼、sha256 必须是 64 位十六进制。
     * 任何一条对不上就整份清单作废——宁可报「没有发布信息」，也不装一个来路不明的包。
     */
    private static Candidate candidateFrom(JSONObject manifest) {
        String version = manifest.optString("versionName");
        String tag = manifest.optString("tag");
        String apkName = manifest.optString("apk");
        String sha256 = manifest.optString("sha256");

        boolean versionOk = VERSION.matcher(version).matches();
        boolean tagOk = tag.equals("v" + version);
        boolean apkOk = apkName.equals("AIIvideo-v" + version + "-release.apk");
        boolean digestOk = sha256.matches("[0-9a-fA-F]{64}");
        boolean sizeOk = manifest.optLong("size") > 0;
        if (!(versionOk && tagOk && apkOk && digestOk && sizeOk)) {
            throw new IllegalStateException("发布信息格式有误");
        }

        Candidate candidate = new Candidate();
        candidate.version = version;
        // 清单里给了 urls 就把候选组原样存下来（发布时写死的，正常情况都走这条）；
        // 没给就自己拼 GitHub Releases 的地址 —— 而且**要带镜像**：裸的
        // github.com/releases 地址在国内基本下不动。挑最快的源（UpdateMirror.pick，
        // 要并发拉 256KB 探速）放到 download() 那一刻才做 —— 这段代码是 check() 和
        // cached() 的共用路径，cached() 是节流命中的回放缓存，不能在这里碰网络。
        JSONArray mirrors = manifest.optJSONArray("urls");
        if (mirrors == null) {
            mirrors = new JSONArray(Endpoints.mirrorsOf(Endpoints.releaseUrl(tag, apkName)));
        }
        candidate.mirrors = mirrors.toString();
        candidate.digest = "sha256:" + sha256;
        candidate.size = manifest.optLong("size");
        candidate.notes = manifest.optString("notes");
        return candidate;
    }

    private static JSONObject availableStatus(Candidate candidate, String currentVersion) throws Exception {
        return status("available").put("version", candidate.version).put("currentVersion", currentVersion).put("notes", candidate.notes).put("size", candidate.size);
    }

    /**
     * 查更新。manual=true 是用户主动点，受 MANUAL_LIMIT / MANUAL_WINDOW_MS 限流；
     * manual=false 是自动检查，受 AUTO_INTERVAL_MS 节流，命中节流时直接回缓存。
     *
     * 返回的 JSONObject 里 status 是以下之一，前端按它分支：
     *   none        没有可用清单（或缓存里的版本已经装上了）
     *   throttled   手点太频繁
     *   unpublished 服务端没有发布信息
     *   current     已经是最新
     *   available   有新版本，附带 version/currentVersion/notes/size
     */
    synchronized JSONObject check(boolean manual) throws Exception {
        long now = System.currentTimeMillis();

        if (manual) {
            ArrayList<Long> recent = new ArrayList<>();
            for (String raw : this.preferences.getString("manual_checks", "").split(",")) {
                try {
                    long at = Long.parseLong(raw);
                    if (at <= now && now - at < MANUAL_WINDOW_MS) {
                        recent.add(at);
                    }
                } catch (NumberFormatException ignored) {
                    // 存坏了就跳过这一个，不清空整份记录
                }
            }
            if (recent.size() >= MANUAL_LIMIT) {
                return status("throttled");
            }
            recent.add(now);
            StringBuilder joined = new StringBuilder();
            for (Long at : recent) {
                if (joined.length() > 0) {
                    joined.append(',');
                }
                joined.append(at);
            }
            this.preferences.edit().putString("manual_checks", joined.toString()).apply();
        } else {
            long lastAuto = this.preferences.getLong("last_auto_check", 0L);
            if (lastAuto > 0 && now >= lastAuto && now - lastAuto < AUTO_INTERVAL_MS) {
                return cached();
            }
            this.preferences.edit().putLong("last_auto_check", now).apply();
        }

        JSONObject manifest = fetchManifest();
        if (manifest == null && !manual) {
            // 没拿到清单：把自动检查的窗口缩短到 30 分钟，别把一次网络抖动
            // 变成「今天不查了」。
            this.preferences.edit()
                    .putLong("last_auto_check", now - AUTO_INTERVAL_MS + AUTO_RETRY_MS)
                    .apply();
        }
        if (manual) {
            // 手点一次也算「刚查过」，把自动检查的计时器一起推后
            this.preferences.edit().putLong("last_auto_check", now).apply();
        }

        PackageInfo self = this.context.getPackageManager().getPackageInfo(this.context.getPackageName(), 0);
        String currentVersion = self.versionName;

        if (manifest == null) {
            // 整条更新通道全断（镜像全挂/DNS 劫持）时，这是唯一能留下痕迹的地方 ——
            // read() 里都是静默 return null，不抛异常，桥的统一上报拦不到它。
            Log.d("AIIvideo", "[update] 更新清单拉不到：试了 "
                    + UpdateMirror.parse(MANIFEST_URL).size() + " 个地址，按没有发布信息处理");
            clearCandidate();
            return status("unpublished");
        }
        if (manifest.optLong("versionCode") <= installedVersionCode(self)) {
            clearCandidate();
            return status("current").put("version", currentVersion);
        }

        this.candidate = candidateFrom(manifest);
        this.preferences.edit().putString("available_manifest", manifest.toString()).apply();
        return availableStatus(this.candidate, currentVersion);
    }

    private static JSONObject status(String state) throws Exception {
        return new JSONObject().put(NotificationCompat.CATEGORY_STATUS, state);
    }
}
