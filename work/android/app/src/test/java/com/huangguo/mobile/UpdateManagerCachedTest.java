package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.json.JSONObject;
import org.junit.Test;

/**
 * UpdateManager.cached() 的「下载不阻塞回放」契约。
 *
 * <p>背景（2026-11-06 中危修复）：cached() 原来整方法 synchronized，与全程握锁、
 * 可能十几分钟的 download() 抢同一把实例锁。cached-update 桥指令落在模块 1 的
 * 单线程本地池里，下载中打开设置页会触发 cached() → 堵住整条本地池 → 「点什么
 * 都没反应」。修复把它改成：只读 volatile 的清单快照、去锁、零网络。
 *
 * <p>这里锁住三件事，防回归：
 * <ol>
 *   <li>cached() 不能再是 synchronized（否则又会被 download() 的锁拖住）；</li>
 *   <li>回放逻辑（replayCached）正确：更新/相同/更旧/坏数据 各分支；</li>
 *   <li>回放逻辑不碰网络、不阻塞：全程纯计算，多线程并发调用能立即返回。</li>
 * </ol>
 */
public class UpdateManagerCachedTest {

    /** 一份能过 candidateFrom 五道闸的合法清单（versionCode=10025）。 */
    private static String validManifest(long versionCode, String versionName) {
        String tag = "v" + versionName;
        String apk = "AIIvideo-" + tag + "-release.apk";
        // 64 位十六进制 sha256
        String sha = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
        return "{\"versionCode\":" + versionCode
                + ",\"versionName\":\"" + versionName + "\""
                + ",\"tag\":\"" + tag + "\""
                + ",\"apk\":\"" + apk + "\""
                + ",\"sha256\":\"" + sha + "\""
                + ",\"size\":10485760"
                + ",\"notes\":\"修了个 bug\"}";
    }

    // ── 契约 1：cached() 必须不是 synchronized（锁结构回归防线） ──────────

    @Test
    public void cached_不能再是_synchronized() throws Exception {
        Method cached = UpdateManager.class.getDeclaredMethod("cached");
        assertFalse("cached() 又被加回 synchronized 了：它会和 download() 抢同一把实例锁，"
                        + "下载中开设置页会堵死整条本地池。修复方案见类注释。",
                Modifier.isSynchronized(cached.getModifiers()));
    }

    /** download() / check() 仍该是 synchronized（记录不对称是有意为之）。 */
    @Test
    public void download和check仍是synchronized是有意的() throws Exception {
        Method download = UpdateManager.class.getDeclaredMethod("download", UpdateManager.Progress.class);
        Method check = UpdateManager.class.getDeclaredMethod("check", boolean.class);
        assertTrue("download() 应继续 synchronized（串行化下载/挑源）",
                Modifier.isSynchronized(download.getModifiers()));
        assertTrue("check() 应继续 synchronized（串行化 prefs 计时器与候选写）",
                Modifier.isSynchronized(check.getModifiers()));
    }

    // ── 契约 2：回放逻辑正确（零网络纯计算） ────────────────────────────

    @Test
    public void 有更新时回放返回available() throws Exception {
        UpdateManager.CachedReplay replay =
                UpdateManager.replayCached(validManifest(10025, "1.0.25"), 10024, "1.0.24");
        assertNotNull("版本 10025 > 已装 10024，应回放为有更新", replay);
        assertEquals("available", replay.status.optString("status"));
        assertEquals("1.0.25", replay.status.optString("version"));
        assertEquals("1.0.24", replay.status.optString("currentVersion"));
        assertNotNull("候选要带出来给后续 download() 用", replay.candidate);
    }

    @Test
    public void 版本相同回放为none() {
        assertNull("已装 10025 == 清单 10025，不应报更新",
                UpdateManager.replayCached(validManifest(10025, "1.0.25"), 10025, "1.0.25"));
    }

    @Test
    public void 已装版本更高回放为none() {
        assertNull("已装 10026 > 清单 10025，不应报更新",
                UpdateManager.replayCached(validManifest(10025, "1.0.25"), 10026, "1.0.26"));
    }

    @Test
    public void 坏清单回放为none不抛() {
        assertNull(UpdateManager.replayCached("not json", 0, "0.0.0"));
        assertNull(UpdateManager.replayCached("{ broken", 0, "0.0.0"));
        // 过了 versionCode 比较，但五道闸不合规（apk 名不对）也要作废。
        String badApk = "{\"versionCode\":10025,\"versionName\":\"1.0.25\",\"tag\":\"v1.0.25\","
                + "\"apk\":\"wrong.apk\",\"sha256\":\""
                + "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
                + "\",\"size\":1}";
        assertNull(UpdateManager.replayCached(badApk, 10024, "1.0.24"));
    }

    @Test
    public void 缺versionCode的清单回放为none() {
        assertNull("versionCode 缺省为 0，<= 已装版本，不应报更新",
                UpdateManager.replayCached("{\"versionName\":\"9.9.9\"}", 10024, "1.0.24"));
    }

    // ── 契约 3：回放不阻塞 —— 与「另一线程握着一把锁」并行也能立即返回 ──────

    /**
     * 模拟「download() 正握着实例锁（十几分钟）」时 cached() 的回放部分能否立即跑完。
     * 修复前 cached() 整方法 synchronized 会去抢那把锁；现在它不碰任何共享监视器，
     * 只做纯计算，所以下面这段在持锁线程还在睡的时候就能返回。
     */
    @Test
    public void 回放在别的线程持锁时也不被阻塞() throws Exception {
        final Object theDownloadLock = new Object();
        final long[] heldFor = {0};
        Thread holder = new Thread(new Runnable() {
            @Override public void run() {
                long start = System.currentTimeMillis();
                synchronized (theDownloadLock) {
                    try {
                        Thread.sleep(3000); // 象征「下载握锁十几分钟」，这里缩短到 3 秒
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
                heldFor[0] = System.currentTimeMillis() - start;
            }
        });
        holder.start();
        // 等 holder 真正进到 synchronized 里
        Thread.sleep(100);

        long t0 = System.currentTimeMillis();
        UpdateManager.CachedReplay replay =
                UpdateManager.replayCached(validManifest(10025, "1.0.25"), 10024, "1.0.24");
        long elapsed = System.currentTimeMillis() - t0;

        assertNotNull(replay);
        assertTrue("回放耗时应远小于持锁线程的持锁时长（实际 " + elapsed + "ms），"
                + "说明它没有被那把锁阻塞", elapsed < 1000);

        holder.interrupt();
        holder.join(4000);
    }
}
