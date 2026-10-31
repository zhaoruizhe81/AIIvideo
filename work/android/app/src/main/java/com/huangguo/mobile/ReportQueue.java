package com.huangguo.mobile;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;

/**
 * 上报的底层队列：落盘、攒批、发送、退避。
 *
 * ── 为什么落盘而不是纯内存 ──────────────────────────────────────────
 * 播放失败最集中的时刻，往往就是接下来 App 被划掉、被系统杀掉、或者用户断网的
 * 时刻。纯内存队列在这三种情况下全白报。所以每条事件先 append 到
 * files/reports/YYYY-MM-DD.jsonl，发成功了才删文件 —— 杀进程不丢，断网等下次。
 *
 * ── 队列就是文件本身 ───────────────────────────────────────────────
 * 发送时把文件改名成 `<名字>.sending`，成功后删、失败改回来。没有「内存里的一份
 * 副本」，也就没有重发时把事件写两遍的问题。`.sending` 是进程被杀的残留证据，
 * 下次 install 时会改回来。
 *
 * ── 为什么不走 AiiHttp / 不走代理 ──────────────────────────────────
 * 源挂了、代理挂了的时候恰恰最需要上报。这条通道必须直连、不共享源的 header
 * 和路由 —— 否则它跟被观测的东西一起死。见 TODO.md 2026-10-23 那节。
 *
 * ── 签名挡不住谁 ───────────────────────────────────────────────────
 * 密钥在 APK 里，反编译就拿得到，它只挡「随手 curl 刷库」。真正的防线是服务端
 * 单 IP 每分 60 次、单次 200 条上限。别把它当鉴权。
 */
final class ReportQueue {

    private static final String TAG = "AIIvideo.Report";
    private static final String DIR = "reports";
    private static final String EXT = ".jsonl";
    private static final String SENDING = ".sending";
    /** 发不出去的那几条隔离在这里。不进 backlog、不参与发送，纯本地取证。 */
    private static final String DEAD_DIR = "dead";
    private static final int MAX_BATCH = 200;      // 服务端上限
    private static final int MAX_BACKLOG = 600;    // 攒到这个数就不收新的，别把磁盘撑爆
    // 事件量级很小（一次会话几十条），攒批没有意义，反而让「打开 App 看一眼就划掉」
    // 的那批事件压在盘里。3 秒的窗口只是把连续的几条合成一个请求。
    private static final long FLUSH_AGE_MS = 3_000L;
    private static final long BACKOFF_BASE_MS = 60_000L;
    private static final long BACKOFF_MAX_MS = 30L * 60_000L;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "report");
        thread.setDaemon(true);
        return thread;
    });

    private final Context context;
    private final File dir;
    /**
     * 只有主进程负责发送。spider 是独立进程，它要是也 claim 同一批文件，两边会
     * 抢着 rename、重发、互相删 —— 谁先谁后没法保证。所以 :spider 只写不发，
     * 它产生的崩溃由主进程的 ticker 顺路带走。
     */
    private final boolean canFlush;
    private long lastFlushAt = 0L;
    private int failures = 0;
    private long nextAttemptAt = 0L;
    private int backlog = 0;

    ReportQueue(Context context, boolean canFlush) {
        this.context = context.getApplicationContext();
        this.canFlush = canFlush;
        this.dir = new File(this.context.getFilesDir(), DIR);
        if (!this.dir.isDirectory() && !this.dir.mkdirs()) {
            Log.w(TAG, "建不了上报目录，这次的事件会丢");
        }
        if (canFlush) {
            recoverSending();
        }
        this.backlog = countEvents();
        if (canFlush) {
            startTicker();
        }
    }

    /** 落盘一条。失败就算了 —— 上报坏掉不该影响任何用户路径。 */
    void enqueue(JSONObject event) {
        if (this.backlog >= MAX_BACKLOG) {
            // 满 600 就开始丢事件，这件事必须留痕：丢了多少、从什么时候开始丢的，
            // 只能靠这里。静默 return 的话队列卡死在 600 上谁也发现不了。
            Log.w(TAG, "队列已满（" + MAX_BACKLOG + "），丢掉一条："
                    + event.optString("kind") + "/" + event.optString("name"));
            return;
        }
        byte[] line = (event.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (this) {
            FileOutputStream out = null;
            try {
                out = new FileOutputStream(fileOfToday(), true);
                out.write(line);
                this.backlog += 1;
            } catch (IOException error) {
                Log.w(TAG, "写队列失败：" + error.getMessage());
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        // 写完了得有人踢一脚，否则这批事件要等到下一次入队才动
        if (this.canFlush) {
            maybeFlush(false);
        }
    }

    /**
     * 兜底节拍器。上面那次 maybeFlush 只在「又有新事件入队」时发生 —— 用户发完一条就
     * 把 App 划掉的话，那条就再也不动了。15 秒一次很便宜，换的是「离线事件不留过夜」。
     */
    private void startTicker() {
        // 必须是自己的线程。放到 WORKER 上就是一个 while(true) 把单线程占死，
        // 后面 every 一个 flush 任务都排不上 —— 事件会静静地躺在盘里。
        Thread ticker = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(15_000L);
                } catch (InterruptedException stop) {
                    return;
                }
                maybeFlush(false);
            }
        }, "report-tick");
        ticker.setDaemon(true);
        ticker.start();
    }

    /** 够一批、够一分钟、或者被叫醒（切后台 / 退出前）就发一次。 */
    void maybeFlush(boolean force) {
        if (!this.canFlush) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean due = force
                || this.backlog >= MAX_BATCH
                || (this.backlog > 0 && now - this.lastFlushAt >= FLUSH_AGE_MS);
        if (!due || now < this.nextAttemptAt || this.backlog <= 0) {
            return;
        }
        this.lastFlushAt = now;
        WORKER.execute(this::flushOnWorker);
    }

    private void flushOnWorker() {
        List<File> sending = claimFiles();
        if (sending.isEmpty()) {
            return;
        }
        // 每个文件读出来的行要分开记：发完得知道「这个文件还剩几条」，
        // 不然超过 MAX_BATCH 的那部分会被连同文件一起删掉（见 acknowledge 的注释）。
        List<Batch> claimed = new ArrayList<Batch>();
        int total = 0;
        for (File file : sending) {
            List<JSONObject> items = readLines(file);
            claimed.add(new Batch(file, items));
            total += items.size();
            if (total >= MAX_BATCH) {
                break;
            }
        }
        // 因 break 没进本批的 .sending 要立刻改回 .jsonl：还原残件的
        // recoverSending 只在启动跑一次，这里不还回去的话，超出单批的那部分
        // 事件在本进程里就永远躺在 .sending 上，再也不会被发送。
        for (int i = claimed.size(); i < sending.size(); i++) {
            release(sending.get(i));
        }
        if (total == 0) {
            for (File file : sending) {
                release(file);
            }
            return;
        }
        try {
            boolean ok = send(claimed);
            if (ok) {
                this.failures = 0;
                this.nextAttemptAt = 0L;
            } else {
                backoff();
            }
        } catch (Exception error) {
            for (File file : sending) {
                release(file);
            }
            backoff();
            Log.w(TAG, "发送失败：" + error.getMessage());
        } finally {
            maybeFlush(false);
        }
    }

    /** 一个已认领的队列文件和它的行。发完要按「已发送的条数」把剩下的写回去。 */
    private static final class Batch {
        final File file;
        final List<JSONObject> items;

        Batch(File file, List<JSONObject> items) {
            this.file = file;
            this.items = items;
        }
    }

    /**
     * 把已认领的批次发出去。返回 true = 已处理完（成功或已隔离毒事件），false = 该退避。
     *
     * ── 为什么是「发完写回剩余」而不是「删文件」──────────────────────
     * 原来 acknowledge() 直接把整个文件删掉，而上面只发了前 200 条。单日单设备
     * 超过 200 条时，剩下的就跟着文件一起消失，日志里只有一行「发送成功」。
     * 线上已经有 4 台设备撞上（单日 708 / 539 / 449 / 322 条）。
     *
     * ── 为什么 4xx 要二分 ──────────────────────────────────────────
     * 服务端现在逐条拒收（不会再因为一条坏事件回 4xx），但老服务端还在跑、
     * 而且以后还会有别的单条问题。一条毒事件不该带走整批：二分到单条，
     * 把它扔进 dead/ 留证，其余照常发。
     */
    private boolean send(List<Batch> claimed) throws Exception {
        List<JSONObject> batch = new ArrayList<JSONObject>();
        for (Batch one : claimed) {
            for (JSONObject event : one.items) {
                if (batch.size() >= MAX_BATCH) {
                    break;
                }
                batch.add(event);
            }
        }
        int status = post(encode(batch));
        if (status >= 200 && status < 300) {
            consume(claimed, batch);
            return true;
        }
        if (status == 429 || status >= 500) {
            for (Batch one : claimed) {
                release(one.file);
            }
            return false;
        }
        if (status == 401) {
            // 签名被拒：TRACK_SECRET 两边对不上，每一批都会 401。二分只会白打 N 次，
            // 留在队列里等下次 —— 换密钥之后旧客户端本来就该等新版。
            Log.w(TAG, "服务端回 401（TRACK_SECRET 对不上），" + batch.size() + " 条留在队列里");
            for (Batch one : claimed) {
                release(one.file);
            }
            return false;
        }
        // 400/413：单条问题。二分到最小，把发不出去的那条隔离，其余收下。
        Log.w(TAG, "服务端拒收（HTTP " + status + "），" + batch.size() + " 条开始二分隔离");
        List<JSONObject> sentOk = new ArrayList<JSONObject>();
        bisect(batch, sentOk);
        consume(claimed, sentOk);
        return true;
    }

    /**
     * 二分：把这一批里发得出去的收进 `ok`，发不出去的单条隔离进 dead/。
     *
     * **不能用「成功的条数」当返回值。** 坏事件可能在中间，那时「前 200 条成功」
     * 这个假设就不成立了 —— 直接按条数从前缀切，会把没发出去当成发过的删掉。
     * 所以这里按**身份**返回：ok 里是真正被服务端收下的那些对象。
     *
     * 两半都要走：head 失败只说明坏事件在 head 里，tail 完全可能是好的。
     * 只递归 head 的话，tail 得等下一轮 flush 才试 —— 一次故障要发好几轮才清空。
     */
    private void bisect(List<JSONObject> batch, List<JSONObject> ok) throws Exception {
        if (batch.isEmpty()) {
            return;
        }
        if (batch.size() == 1) {
            // 必须自己试一次再判死刑：从 tail 递归下来的这一条**从没被单独发过**
            // （父层只 post 了 head），直接 isolate 等于无辜地杀掉一条好事件。
            JSONObject only = batch.get(0);
            int solo = post(encode(batch));
            if (solo >= 200 && solo < 300) {
                ok.add(only);
                return;
            }
            if (solo == 429 || solo >= 500 || solo == 401) {
                throw new java.io.IOException("二分途中服务端回 " + solo);
            }
            isolate(only);
            return;
        }
        int mid = batch.size() / 2;
        List<JSONObject> head = new ArrayList<JSONObject>(batch.subList(0, mid));
        List<JSONObject> tail = new ArrayList<JSONObject>(batch.subList(mid, batch.size()));
        int status = post(encode(head));
        if (status >= 200 && status < 300) {
            ok.addAll(head);
        } else if (status == 429 || status >= 500 || status == 401) {
            // 中途变成服务端问题，别再往下切了。抛出去让 flushOnWorker 的 catch
            // 把本次认领的文件全部 release，等退避重来。
            throw new java.io.IOException("二分途中服务端回 " + status);
        } else {
            bisect(head, ok);
        }
        bisect(tail, ok);
    }

    /** 发不出去的那条：进 dead/，不进 backlog、不再重发。 */
    private void isolate(JSONObject event) {
        File dead = new File(this.dir, DEAD_DIR);
        if (!dead.isDirectory() && !dead.mkdirs()) {
            Log.w(TAG, "建不了 dead 目录，丢掉一条 " + event.optString("kind") + "/" + event.optString("name"));
            return;
        }
        File target = new File(dead, Report.dayKey(System.currentTimeMillis()) + EXT);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(target, true);
            out.write((event.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            Log.w(TAG, "隔离一条发不出去的事件：" + event.optString("kind") + "/" + event.optString("name"));
        } catch (IOException error) {
            Log.w(TAG, "隔离失败：" + error.getMessage());
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
        trimDead(dead);
    }

    /** dead/ 只留最近 200 条 —— 它不参与发送，纯粹是本地取证，别让它长成第二个队列。 */
    private void trimDead(File dead) {
        File[] files = dead.listFiles();
        if (files == null || files.length <= 1) {
            return;
        }
        Arrays.sort(files);
        for (int i = 0; i < files.length - 1; i++) {
            if (!files[i].delete()) {
                return;
            }
        }
    }

    /**
     * 把发成功的那些从队列里去掉，剩下的写回去。
     *
     * `sent` 是**真正被服务端收下的那些事件对象**，不是条数 —— 坏事件可能在中间，
     * 按条数切前缀会把没发出去的一起删掉。这里逐个文件过滤：还在 sent 里的删掉，
     * 剩下的重写。写回用 `.tmp` + rename，半路被杀也不会留下一个写了一半的队列文件。
     *
     * 用 IdentityHashMap：同一批里可能有多条内容相同的事件（重试刷出来的），
     * 按 equals 去重会连没发出去的一起删。
     */
    private void consume(List<Batch> claimed, List<JSONObject> sent) {
        synchronized (this) {
            for (Batch one : claimed) {
                List<JSONObject> rest = remaining(one.items, sent);
                int dropped = one.items.size() - rest.size();
                if (dropped == 0) {
                    // 这个文件一条都没发出去，原样还回去。
                    release(one.file);
                    continue;
                }
                if (rest.isEmpty()) {
                    if (one.file.delete()) {
                        this.backlog = Math.max(0, this.backlog - dropped);
                    } else {
                        Log.w(TAG, "删不掉 " + one.file.getName() + "，下次会重发");
                    }
                    continue;
                }
                if (rewrite(one.file, rest)) {
                    this.backlog = Math.max(0, this.backlog - dropped);
                } else {
                    release(one.file);
                }
            }
        }
    }

    /**
     * 从 `items` 里剔掉**已经发成功**的那些（按对象身份，不按 equals）。
     *
     * 按 equals 会出事：重试刷出来的事件内容可能完全相同，一条发成功就把另一条
     * 也当成发过了删掉。所以用 IdentityHashMap，只认同一个对象引用。
     *
     * 包可见 + 不碰文件系统，纯逻辑，单测直接盯。
     */
    static List<JSONObject> remaining(List<JSONObject> items, List<JSONObject> sent) {
        java.util.IdentityHashMap<JSONObject, Boolean> acked =
                new java.util.IdentityHashMap<JSONObject, Boolean>();
        for (JSONObject event : sent) {
            acked.put(event, Boolean.TRUE);
        }
        List<JSONObject> rest = new ArrayList<JSONObject>();
        for (JSONObject event : items) {
            if (!acked.containsKey(event)) {
                rest.add(event);
            }
        }
        return rest;
    }

    /**
     * 两份事件行合并去重，first 在前（较旧）。有 id 按 id 认身份；没有 id 的退回按整行。
     *
     * 用在「.sending 改名回去之前，目标 .jsonl 已被发送窗口内的新事件重建」的场合：
     * 两份都必须保住，又不能把同一条事件存两遍。这里按 id 而不是按对象身份 ——
     * {@link #remaining} 合并的是内存里同一批对象，这里合并的是**两份磁盘文件**，
     * 同一条事件在两边是不同的对象实例，只有 id 是靠得住的身份。
     */
    static List<JSONObject> mergeEvents(List<JSONObject> first, List<JSONObject> second) {
        LinkedHashMap<String, JSONObject> merged = new LinkedHashMap<String, JSONObject>();
        for (JSONObject event : first) {
            merged.put(eventKeyOf(event), event);
        }
        for (JSONObject event : second) {
            if (!merged.containsKey(eventKeyOf(event))) {
                merged.put(eventKeyOf(event), event);
            }
        }
        return new ArrayList<JSONObject>(merged.values());
    }

    /** 事件去重键：有 id 用 id；没有的退回整行（前缀 # 防止和真 id 撞）。 */
    private static String eventKeyOf(JSONObject event) {
        String id = event.optString("id");
        return id.length() > 0 ? id : "#" + event.toString();
    }

    /**
     * 把剩下的行写回原文件（先写 .tmp 再改名）。失败就保持 .sending 等下次。
     *
     * 两个坑，都是发送窗口（claim 之后、写回之前）造成的：
     * · 窗口内 enqueue 会把同名 .jsonl 重建出来（新事件直接写进它），直接改名
     *   会把它整块盖掉 —— 先读出来按事件 id 合并再落盘。
     * · 改名成功后必须删掉 .sending：它的内容已经由写回的 .jsonl 代表，留着的话
     *   下次启动 recoverSending 会把已发过的旧内容整份复活（重复发送）。
     */
    private boolean rewrite(File sendingFile, List<JSONObject> rest) {
        if (rest.isEmpty()) {
            return sendingFile.delete();
        }
        File tmp = new File(this.dir, sendingFile.getName() + ".tmp");
        java.io.BufferedWriter writer = null;
        try {
            String name = sendingFile.getName();
            File target = new File(this.dir, name.substring(0, name.length() - SENDING.length()));
            List<JSONObject> merged = rest;
            if (target.isFile()) {
                merged = mergeEvents(rest, readLines(target));
            }
            writer = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                    new FileOutputStream(tmp), StandardCharsets.UTF_8));
            for (JSONObject event : merged) {
                writer.write(event.toString());
                writer.write('\n');
            }
            writer.flush();
            writer.close();
            writer = null;
            // tmp → 正常的队列文件名（去掉 .sending）
            if (tmp.renameTo(target)) {
                if (!sendingFile.delete()) {
                    Log.w(TAG, "删不掉已写回的 " + name + "，下次启动可能重发旧内容");
                }
                return true;
            }
            Log.w(TAG, "写回改名失败 " + name);
            return false;
        } catch (IOException error) {
            Log.w(TAG, "写回队列失败：" + error.getMessage());
            return false;
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private byte[] encode(List<JSONObject> batch) throws Exception {
        return new JSONObject()
                .put("app", Report.appInfo(this.context))
                .put("device", Report.deviceId())
                .put("events", new JSONArray(batch))
                .toString().getBytes(StandardCharsets.UTF_8);
    }

    private int post(byte[] body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(Endpoints.TRACK_EVENTS).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(15000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Content-Encoding", "gzip");
            connection.setRequestProperty("X-Sig", Report.sign(body));
            connection.setRequestProperty("User-Agent", "AIIvideo/" + Report.versionName(this.context));
            byte[] gzipped = gzip(body);
            connection.setFixedLengthStreamingMode(gzipped.length);
            OutputStream out = connection.getOutputStream();
            try {
                out.write(gzipped);
            } finally {
                out.close();
            }
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] gzip(byte[] body) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(body.length / 4 + 64);
        GZIPOutputStream gzip = new GZIPOutputStream(buffer);
        try {
            gzip.write(body);
        } finally {
            gzip.close();
        }
        return buffer.toByteArray();
    }

    /** 把待发文件认领成 `.sending`，同一时刻只有一个线程拿着它们。 */
    private List<File> claimFiles() {
        List<File> claimed = new ArrayList<File>();
        synchronized (this) {
            File[] files = this.dir.listFiles();
            if (files == null) {
                return claimed;
            }
            Arrays.sort(files);                     // 文件名带日期，旧的先发
            for (File file : files) {
                // dead/ 是子目录，countEvents 里也按 isFile 过滤；这里同样只认普通文件。
                if (!file.isFile() || !file.getName().endsWith(EXT)) {
                    continue;
                }
                File staging = new File(this.dir, file.getName() + SENDING);
                if (!file.renameTo(staging)) {
                    continue;
                }
                claimed.add(staging);
            }
        }
        return claimed;
    }

    /** 没发出去：改回普通队列文件。 */
    private void release(File file) {
        String name = file.getName();
        // isFile 守卫：上面那步已经改回去过的文件（发送异常的 catch 会再全量放一遍）
        // 直接跳过，别在恢复路径上刷「改名失败」的假警。
        if (!name.endsWith(SENDING) || !file.isFile()) {
            return;
        }
        File target = new File(this.dir, name.substring(0, name.length() - SENDING.length()));
        if (target.isFile()) {
            // 发送窗口内 enqueue 重建了同名 .jsonl：直接改名会把新事件盖掉，
            // 先按事件 id 合并再原子替换。合并失败就把 .sending 留在盘上 ——
            // 下次启动 recoverSending 还能再来一遍，宁可滞留不能覆盖。
            if (mergeSendingBack(file, target)) {
                return;
            }
            Log.w(TAG, "写回合并失败 " + name + "，留在盘上等下次启动恢复");
            return;
        }
        if (!file.renameTo(target)) {
            Log.w(TAG, "改名失败 " + name);
        }
    }

    /** 把 .sending 的内容并进目标 .jsonl（原子替换）；成功返回 true，.sending 一并删掉。 */
    private boolean mergeSendingBack(File sendingFile, File target) {
        File tmp = new File(this.dir, sendingFile.getName() + ".tmp");
        try {
            List<JSONObject> merged = mergeEvents(readLines(sendingFile), readLines(target));
            java.io.BufferedWriter writer = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                    new FileOutputStream(tmp), StandardCharsets.UTF_8));
            try {
                for (JSONObject event : merged) {
                    writer.write(event.toString());
                    writer.write('\n');
                }
            } finally {
                writer.close();
            }
            if (!tmp.renameTo(target)) {
                tmp.delete();
                return false;
            }
        } catch (IOException error) {
            Log.w(TAG, "合并写回失败：" + error.getMessage());
            tmp.delete();
            return false;
        }
        if (!sendingFile.delete()) {
            Log.w(TAG, "删不掉已合并的 " + sendingFile.getName() + "，下次启动可能重发旧内容");
        }
        return true;
    }

    /**
     * 进程被杀留下的残局：`.sending` 改回来接着发，`.tmp`（写回到一半）直接删。
     *
     * `.tmp` 不能改名回来 —— 它可能是半截内容，改回去等于把一半的事件当成完整的发。
     * 而它对应的原 `.sending` 还在，那份是完整的，重发一次就够了。
     */
    private void recoverSending() {
        File[] files = this.dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }
            if (file.getName().endsWith(".tmp")) {
                if (!file.delete()) {
                    Log.w(TAG, "删不掉残留的 " + file.getName());
                }
                continue;
            }
            if (file.getName().endsWith(SENDING)) {
                release(file);
            }
        }
    }

    private List<JSONObject> readLines(File file) {
        List<JSONObject> items = new ArrayList<JSONObject>();
        // ── 这里必须显式指定 UTF-8 ───────────────────────────────────────
        // 原来用 RandomAccessFile.readLine()，它把每个字节原样当成一个 char
        // （等于 ISO-8859-1）：「中」的 E4B8AD 回来就变成 "ä¸­" 三个字符，
        // 再按 UTF-8 写出去就是 C3A4C2B8C2AD —— 服务端存进去全是乱码，而
        // ASCII 部分完全正常，所以看起来「只有中文坏了」。
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                try {
                    items.add(new JSONObject(line));
                } catch (Exception ignored) {
                    // 半行（写到一半被杀）跳过
                }
            }
        } catch (IOException error) {
            Log.w(TAG, "读队列失败：" + error.getMessage());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }
        return items;
    }

    /** 还压在盘里的条数。设置页显示用。 */
    int pending() {
        synchronized (this) {
            return this.backlog;
        }
    }

    private void backoff() {
        this.failures = Math.min(this.failures + 1, 6);
        long delay = Math.min(BACKOFF_BASE_MS << (this.failures - 1), BACKOFF_MAX_MS);
        this.nextAttemptAt = System.currentTimeMillis() + delay;
    }

    private File fileOfToday() {
        return new File(this.dir, Report.dayKey(System.currentTimeMillis()) + EXT);
    }

    private int countEvents() {
        int total = 0;
        File[] files = this.dir.listFiles();
        if (files == null) {
            return 0;
        }
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(EXT)) {
                total += readLines(file).size();
            }
        }
        return Math.min(total, MAX_BACKLOG);
    }

    static String hmacHex(String secret, byte[] body) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(body);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception error) {
            return "";
        }
    }
}
