package com.huangguo.mobile;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 源配置 —— 读、写、校验、缓存、源清单、恢复出厂。
 *
 * ── 为什么单独一个类 ────────────────────────────────────────────────
 * 2026-10-18 之前它和**代理核心、播放器控制、进度上报、播放层 HTTP 出口**
 * 一起挤在 {@code UserSource}（2492 行）里。五个子系统共用一个类，
 * 唯一原因是它们历史上都借同一座 detail 桥 —— 桥早就拆成独立 action 了，
 * 这个类只是一直没人动。
 *
 * 拆完：{@code UserSource} 只剩一张前缀路由表，这里是源配置的全部实现。
 */
final class SourceConfig {

    private SourceConfig() {}

    /** 源配置文件名。用户填的地址、拉下来的配置、备注都在里面。 */
    static final String FILE_NAME = "user-source.json";

    /** 源清单。加过的源都记在这儿，换源时从这儿挑。 */
    static final String LIST_FILE = "user-sources.json";

    /** 拉下来的配置缓存目录。第二次之后换源走本地，不用等网络。 */
    static final String CACHE_DIR = "source-cache";

    /** 拉配置的超时。站点慢的时候 12 秒够，再长用户以为死了。 */
    static final int FETCH_TIMEOUT = 12;

    /**
     * 源身份 —— 这套配置把 `/api/videos/{id}` 翻成哪个地址（把 {id} 那一格挖掉）。
     *
     * ── 为什么不能用名字，也不能用配置地址 ──────────────────────────────
     * 观看历史的条目是 `{id, …}`，而 **id 只在源内部唯一**：红果的 117 和
     * AGE动漫 的 117 是两部不相干的片子。所以「这条历史能不能在当前源里打开」
     * 本质上是问「这里的 id 会被翻到哪儿去」。
     *
     *   名字     会被人改，而且同一个站可以有两个名字（源列表里就同时有
     *            agedm-decl 和 www.agedm.io）。用名字的话，改一次名历史全断。
     *   配置地址 更稳，但同一个站的两份配置地址天然不同 —— 而那两份**确实不是
     *            同一个源**：一份把 `/api/videos/{id}` 落到服务端路径上，另一份
     *            同名的 id 落到别处（历史上是适配器翻到 api.agedm.io/v2/detail/）。
     *            分开是对的。
     *
     * 只有「id 往哪儿翻」决定 id 空间。翻到同一个地方 = 同一个空间 = 历史该互通；
     * 翻到不同地方 = 硬混就是张冠李戴（静默播错片子，比报错糟得多）。
     *
     * 返回的串一律以 hgsrc: 开头 —— 版本标记。早期版本存的是「源名字」，没有
     * 前缀；前端见到没前缀的按老数据处理（当「算」），否则升级之后所有人的
     * 历史会一次性消失。
     */
    static String identity(JSONObject config) {
        if (config == null) return "";

        // spider 源（只有 web.spider、没有 origin/paths）：id 空间就是 spider 的 id。
        // 不加这一段会掉到最下面那条 cfg| 分支 —— 手工写在本地的 spider 配置没有
        // __url，于是恒为 "hgsrc:cfg|"，两个不同的 spider 源共用一份观看历史：
        // 点开历史播的是另一部片子。静默张冠李戴，比报错糟得多。
        JSONObject spiderWeb = config.optJSONObject("web");
        String spiderId = spiderWeb == null ? "" : spiderWeb.optString("spider", "").trim();
        if (spiderId.length() > 0) return "hgsrc:spider|" + spiderId;

        String origin = config.optString("origin", "").trim();
        if (origin.length() > 0) {
            JSONObject web = config.optJSONObject("web");
            JSONObject paths = web == null ? null : web.optJSONObject("paths");
            String detail = paths == null ? "" : paths.optString("detail", "");
            return "hgsrc:rest|" + origin + trimId(detail);
        }

        // 说不出 id 往哪儿翻（配置没配全），就退回配置地址 —— 至少不会和别的源撞上。
        return "hgsrc:cfg|" + config.optString("__url", "");
    }
    /**
     * 源的短名字，只给埋点用：spider 取地址最后一段（51cg / mrds66），REST 取主机名。
     *
     * 为什么不直接用 identity()：那个是「id 空间」的指纹，长这样
     * {@code hgsrc:spider|https://…/spiders/51cg.json}，进每条事件既占地方又没法看。
     * 埋点要的是「能一眼认出是哪个源」，人工看的名字更合适；真要严格对账再按 identity 分组。
     */
    static String label(JSONObject config) {
        if (config == null) return "";
        JSONObject web = config.optJSONObject("web");
        String spider = web == null ? "" : web.optString("spider", "").trim();
        if (spider.length() > 0) {
            int cut = spider.indexOf('?');
            if (cut > 0) spider = spider.substring(0, cut);
            int slash = spider.lastIndexOf('/');
            String name = slash >= 0 ? spider.substring(slash + 1) : spider;
            if (name.endsWith(".json")) name = name.substring(0, name.length() - 5);
            else if (name.endsWith(".js")) name = name.substring(0, name.length() - 3);
            if (name.length() > 0) return name;
        }
        String origin = config.optString("origin", "").trim();
        if (origin.length() > 0) {
            try {
                String host = new URI(origin).getHost();
                if (host != null && host.length() > 0) {
                    return host.startsWith("www.") ? host.substring(4) : host;
                }
            } catch (Exception ignored) {
            }
            return origin;
        }
        return "";
    }

    /** 把路径模板里 `{id}` 那一格挖掉。挖掉之后剩下的就是「id 空间」的边界。 */
    static String trimId(String template) {
        int at = template.indexOf("{id}");
        return at < 0 ? template : template.substring(0, at) + template.substring(at + 4);
    }
    /**
     * 重启应用。换源之后必须走这一步。
     *
     * ── 为什么不能只 reload 页面 ─────────────────────────────────────
     * SiteProfile 是在 MainActivity.onCreate 里构造的，配置**只在那时候读一次**。
     * WebView 的 location.reload() 只重载页面，Activity 不重建 —— Java 侧那份
     * profile 还是旧的。表现是「切了源，但 __source:web 返回的还是上一个源的
     * web 块，首页也还是上一个源的内容」。
     *
     * 这个坑实测踩到过：源表已经切到 cms-xml、缓存作用域也换了，但分类 tab 还是
     * 黄果那两个、标题还是「AI专区」。
     *
     * 所以先叫起新的 launcher intent，再从主线程退出进程 —— 冷启动一次，
     * 配置、SiteProfile、连接池、预热缓存全部重来。
     */
    static void restart(Context context) {
        // 让 MainActivity.recreate() —— 那会走 onDestroy → onCreate。SiteProfile
        // 在 onCreate 的链条里构造（beginSession → new SiteProfile），而它每构造
        // 一次都会重读 files/user-source.json，所以重建就等于换源生效。
        //
        // ── 两条硬约束，都是踩出来的 ──────────────────────────────────
        //
        // 1) 必须挪到主线程。从 API 28 起 Activity.recreate() 自己查线程：
        //        if (Looper.myLooper() != mMainThread.getLooper())
        //            throw new IllegalStateException("Must be called from main thread");
        //    这条指令是从 OkHttp 的线程进来的 —— 在那边直接调必然抛，抛了就被
        //    catch 吞掉，表现是「点了切换，什么都没发生，只能自己退出去重进」。
        //
        // 2) 退路不能只加 FLAG_ACTIVITY_NEW_TASK。任务本来就在前台，系统只是把
        //    原任务拎到前面 —— 没有新 Activity，配置也不会重读。要真重来一遍
        //    得带 CLEAR_TASK。
        //
        // 200ms 是留给桥的：让 {ok:true} 先回到前端，界面才来得及画出「正在
        // 切换…」，不然响应还在路上页面就被销毁了。
        final Context app = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                MainActivity host = MainActivity.host;
                if (host != null && !host.isFinishing() && !host.isDestroyed()) {
                    try {
                        host.restart();
                        UserSource.log("已请求重建 Activity");
                        return;
                    } catch (Throwable e) {
                        UserSource.log("重建失败：" + e);
                    }
                } else {
                    UserSource.log("拿不到可用的 MainActivity，退回重新拉起");
                }
                try {
                    Intent launch = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        app.startActivity(launch);
                    }
                } catch (Throwable e) {
                    UserSource.log("重新拉起失败：" + e);
                }
            }
        }, 200);
    }
    /**
     * 把一个源变成当前配置。返回 null 表示成功，否则是一句给人看的错。
     *
     * 三件事：拿到配置 → 校验 → 干跑一次 SiteProfile → 写进 user-source.json。
     *
     * 「干跑」那段是必须的：SiteProfile 在 Activity.onCreate 里构造，它自己那堆校验
     * （origin 是不是 https、封面 AES 密钥够不够长、allowedHosts 空不空）抛异常的表现
     * 是**一装完就闪退**。而配置存在 files/ 下，App 起不来就进不了设置页 ——
     * 用户被一份填错的配置永久锁在外面，只能清数据。所以在这里先把那条路走一遍。
     *
     * ── 2026-10-16：换源不再等网络 ────────────────────────────────────
     * 每次切源都重新拉一遍那个地址，代价是**一次完整的公网往返** —— 实测量子那种站
     * 1.3~4 秒，慢的时候十几秒（Cloudflare 落在 LAX）。而那份配置一个站在几周里
     * 基本不变：变的只有分类表。所以每份拉回来的配置都在 files/source-cache/ 里
     * 留一份，切源时先用它。
     *
     * **「失效的源会被当场拒绝」这条没丢**：缓存那份同样要过 validate + 干跑，
     * 过不了就丢掉、退回重新拉。丢掉的是「网络还能不能通」，留下的是「这份配置会不会
     * 让应用起不来」—— 而后者才是当初要挡的那个东西。
     *
     * 命中缓存之后会在后台把缓存刷新一遍（refreshCacheAsync），所以下一次切过来
     * 用的是新的；这次先按手上这份跑。
     *
     * @param usedCache 长度 ≥1 的数组，出参：这次是不是吃的缓存（页面要显示「本地缓存」）
     */
    static String applySource(Context context, String url, boolean preferCache, boolean[] usedCache) {
        if (preferCache) {
            JSONObject cached = readJson(cacheFile(context, url));
            JSONObject config = cached == null ? null : cached.optJSONObject("config");
            if (config != null) {
                String problem = verify(context, config);
                if (problem == null) {
                    // 缓存命中也得把 spider 装上 —— 上一次添加时如果只存下了配置、
                    // js 没落盘（比如旧版本、或者当时下载失败），这里不补就永远缺。
                    // 2026-10-21 模拟器就是这样：第二次点同一个源走的是缓存，
                    // 报「找不到 spider: dozagonza」，而地址就在这份缓存配置里。
                    String spiderProblem = ensureSpider(context, config);
                    if (spiderProblem != null) {
                        UserSource.log("换源被拒（缓存）@" + url + "：" + spiderProblem);
                        return spiderProblem;
                    }
                    try {
                        config.put("__url", url);
                        save(context, config);
                    } catch (Exception e) {
                        return "保存失败：" + e.getMessage();
                    }
                    usedCache[0] = true;
                    UserSource.log("换源用的是本地缓存，没有等网络");
                    refreshCacheAsync(context, url);
                    return null;
                }
                // 缓存坏了（旧版本的字段、被改坏的文件）—— 当它不存在，重新拉。
                UserSource.log("缓存那份配置不能用了（" + problem + "），重新拉一次");
            }
        }
        JSONObject config;
        try {
            config = load(url);
        } catch (Exception e) {
            String why = e.getMessage();
            // load() 用固定串标记「这不是一份源，是源的清单」。
            // 原样透出去用户只看到 MARKET_SOURCES，不知道下一步该干什么。
            if ("MARKET_SOURCES".equals(why)) {
                return "这个地址是一份源清单（里面是一串源），不是单个源 —— 去「源市场」里点其中一项";
            }
            return "拉取失败：" + why;
        }
        String problem = verify(context, config);
        if (problem != null) {
            // 带上地址一起记：源市场一次只加一个，但用户报「加不上」时，
            // 光看一句话分不清是哪个条目、拉到的又是哪份东西。
            UserSource.log("换源被拒 @" + url + "：" + problem
                    + "｜顶层键：" + keysOf(config));
            return problem;
        }
        // 配置里把 spider 写成 URL 的（现在的主流写法，像星空影视那样
        // `"spider": "https://…/dozagonza.js"`），js 得先装进运行时。
        //
        // 2026-10-21 模拟器实测：只加配置文件、不加 js，换源是「成功」的，
        // 然后首页弹「加载失败 — 找不到 spider: dozagonza」。用户没有任何
        // 线索知道还差一个文件 —— 而地址就写在配置里，帮他装掉这一跳最省事。
        String spiderProblem = ensureSpider(context, config);
        if (spiderProblem != null) {
            UserSource.log("换源被拒 @" + url + "：" + spiderProblem);
            return spiderProblem;
        }
        try {
            config.put("__url", url);
            save(context, config);
        } catch (Exception e) {
            return "保存失败：" + e.getMessage();
        }
        writeCache(context, url, config);
        return null;
    }

    /**
     * 配置声明的 spider 装了没有；没装就地装。
     *
     * <p>只处理「`web.spider` 写成一个 https URL」这一种 —— 那是自包含的写法，
     * 地址就在配置里，装它是确定的动作。写成裸 id（`"spider": "yhdm"`）的那种
     * 取不到地址，只能由用户自己再贴一次，这里不猜。
     *
     * <p>已经装过就跳过：换源不该每次重下一份 js（几百 KB，而且失败会让
     * 本来好好的源换不过去）。
     *
     * @return null 表示装好了或者本来就有；否则是能直接给用户看的一句
     */
    private static String ensureSpider(Context context, org.json.JSONObject config) {
        org.json.JSONObject web = config == null ? null : config.optJSONObject("web");
        Object raw = web == null ? null : web.opt("spider");
        String ref = raw instanceof String ? ((String) raw).trim() : "";
        if (!ref.toLowerCase(java.util.Locale.ROOT).startsWith("https://")) return null;
        String id = SpiderStore.idOfUrl(ref);
        try {
            if (SpiderStore.has(context, id)) return null;
            SpiderStore.install(context, ref);
            UserSource.log("换源顺带装了 spider " + id + " ← " + ref);
            return null;
        } catch (Exception e) {
            return "这个源要用 spider「" + id + "」，但没装成功：" + e.getMessage();
        }
    }
    private static String keysOf(org.json.JSONObject obj) {
        if (obj == null) return "（空）";
        StringBuilder sb = new StringBuilder();
        java.util.Iterator<String> it = obj.keys();
        while (it.hasNext()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(it.next());
        }
        return sb.toString();
    }

    /** 校验 + 干跑。返回 null 表示这份配置能用。 */
    static String verify(Context context, JSONObject config) {
        String problem = UserSource.validate(config);
        if (problem != null) return problem;
        try {
            SiteProfile.candidate(context, config);
        } catch (Throwable e) {
            String why = e.getMessage();
            if (e.getCause() != null && e.getCause().getMessage() != null) why = e.getCause().getMessage();
            return "这份配置会让应用启动失败，已拒绝：" + why;
        }
        return null;
    }








    // ── 源配置的本地缓存 ──────────────────────────────────────────────

    static File cacheDir(Context context) {
        File dir = new File(context.getFilesDir(), CACHE_DIR);
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }
    /**
     * 缓存文件名 = 地址的 md5。地址里可能有查询参数和百分号编码，
     * 直接拿来当文件名既不安全也没法一眼认出来 —— 反正没人读文件名。
     */
    static File cacheFile(Context context, String url) {
        return new File(cacheDir(context), digest(url) + ".json");
    }




    static String digest(String text) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(text.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (int i = 0; i < bytes.length; i++) {
                sb.append(Character.forDigit((bytes[i] >> 4) & 0xf, 16));
                sb.append(Character.forDigit(bytes[i] & 0xf, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // 拿不到 md5（理论上不会）就退回 hashCode：命中率低无所谓，别在这儿抛。
            return Integer.toHexString(text.hashCode());
        }
    }


    static void writeCache(Context context, String url, JSONObject config) {
        try {
            JSONObject entry = new JSONObject();
            entry.put("url", url);
            entry.put("at", System.currentTimeMillis());
            entry.put("config", config);
            writeJson(cacheFile(context, url), entry);
        } catch (Exception e) {
            UserSource.log("源缓存写入失败：" + e);
        }
    }
    /**
     * 后台把缓存刷新一遍。**只动缓存，不动当前生效那份** —— 动了就得再来一次重启，
     * 用戶刚看着界面回来又要被重启一次。下一次切到这个源用的就是新的。
     */
    static void refreshCacheAsync(final Context context, final String url) {
        final Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject fresh = load(url);
                    if (UserSource.validate(fresh) != null) { UserSource.log("源缓存刷新：新配置没通过校验，保留旧的"); return; }
                    writeCache(app, url, fresh);
                    UserSource.log("源缓存已刷新：" + url);
                } catch (Exception e) {
                    // 离线或站点挂了都很正常，缓存照旧用。
                    UserSource.log("源缓存刷新失败（保留旧的）：" + (e.getMessage() == null ? String.valueOf(e) : e.getMessage()));
                }
            }
        }, "source-cache-refresh").start();
    }




    // ── 源清单 ────────────────────────────────────────────────────────

    static File listFile(Context context) {
        return new File(context.getFilesDir(), LIST_FILE);
    }
    /** 当前生效的地址。取自 user-source.json 里的 __url —— 单一真相，不另存一份。 */
    static String activeUrl(Context context) {
        JSONObject active = read(context);
        return active == null ? "" : active.optString("__url", "");
    }
    /**
     * 源清单。{active, saved:[{url, name, at, active}]}
     *
     * 存的是**地址**不是配置内容 —— 配置内容在 user-source.json 里，只有当前这一份。
     * 切换 = 重新拉那个地址 + 覆盖 user-source.json，跟第一次填一样走完整的校验。
     * 这样切到一个已经失效的源会被当场拒绝，而不是留一份坏配置在盘上等下次启动闪退。
     */
    static JSONObject list(Context context) {
        JSONObject out = new JSONObject();
        JSONArray saved = new JSONArray();
        String active = activeUrl(context);
        try {
            out.put("active", active);
        } catch (Exception ignored) {}

        JSONObject store = readJson(listFile(context));
        JSONArray items = store == null ? null : store.optJSONArray("saved");
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<String>();
        if (active.length() > 0) seen.add(active);
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String url = item.optString("url", "");
                if (url.length() > 0) seen.add(url);
            }
        }
        for (String url : seen) {
            JSONObject row = find(items, url);
            JSONObject entry = new JSONObject();
            try {
                entry.put("url", url);
                // name 是自动的（配置里的 name，其次从地址猜），alias 是用户写的备注。
                // 分开存的原因只有一个：备注不能被自动名字洗掉 —— 每次切回来、
                // 每次配置里没写 name，都会重新算一次 name，合在一个字段里就丢了。
                entry.put("name", row == null ? guessName(url) : row.optString("name", guessName(url)));
                entry.put("alias", row == null ? "" : row.optString("alias", ""));
                entry.put("at", row == null ? 0L : row.optLong("at", 0L));
                entry.put("active", url.equals(active));
            } catch (Exception ignored) {}
            saved.put(entry);
        }
        try {
            out.put("saved", saved);
        } catch (Exception ignored) {}
        return out;
    }






    static JSONObject find(JSONArray items, String url) {
        if (items == null) return null;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && url.equals(item.optString("url", ""))) return item;
        }
        return null;
    }
    /**
     * 把一个地址记进清单（已存在就刷新时间戳）。名字优先用配置里的 name，其次从地址猜。
     *
     * **用户写的备注（alias）原样带着走。** 这个函数每次切换、每次添加都会被调一次，
     * 而它按 url 重建那一行 —— 忘了带 alias 的话，用户改完备注、切一次源，备注就没了。
     */
    static void remember(Context context, String url) {
        try {
            JSONObject store = readJson(listFile(context));
            if (store == null) store = new JSONObject();
            JSONArray items = store.optJSONArray("saved");
            if (items == null) items = new JSONArray();

            String name = "";
            JSONObject config = read(context);
            if (config != null) name = config.optString("name", "");
            if (name.length() == 0) {
                JSONObject row = find(items, url);
                name = row == null ? "" : row.optString("name", "");
            }
            if (name.length() == 0) name = guessName(url);

            JSONObject row = find(items, url);
            String alias = row == null ? "" : row.optString("alias", "");

            JSONArray next = new JSONArray();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null || url.equals(item.optString("url", ""))) continue;
                next.put(item);
            }
            JSONObject entry = new JSONObject();
            entry.put("url", url);
            entry.put("name", name);
            entry.put("alias", alias);
            entry.put("at", System.currentTimeMillis());
            next.put(entry);

            store.put("saved", next);
            writeJson(listFile(context), store);
        } catch (Exception e) {
            UserSource.log("源清单写入失败：" + e);
        }
    }
    /**
     * 写/清一个源的备注。传空串 = 清掉备注，退回自动取的那个名字。
     *
     * 备注只是给人看的一行字，不参与任何判断 —— 源身份（identity()）算的是
     * 「这个源的 id 会翻到哪儿去」，跟名字无关。别把它接到别的地方去。
     */
    static void note(Context context, String url, String alias) {
        try {
            String clean = alias == null ? "" : alias.trim();
            if (clean.length() > 40) clean = clean.substring(0, 40);   // 一行放得下，别让它撑破布局
            JSONObject store = readJson(listFile(context));
            JSONArray items = store == null ? null : store.optJSONArray("saved");
            JSONArray next = new JSONArray();
            boolean hit = false;
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;
                    if (url.equals(item.optString("url", ""))) {
                        hit = true;
                        item.put("alias", clean);
                    }
                    next.put(item);
                }
            }
            if (!hit) {
                // 清单里还没有它（比如当前生效的那个是手改配置弄出来的）：补一行。
                JSONObject entry = new JSONObject();
                entry.put("url", url);
                entry.put("name", guessName(url));
                entry.put("alias", clean);
                entry.put("at", System.currentTimeMillis());
                next.put(entry);
            }
            JSONObject out = new JSONObject();
            out.put("saved", next);
            writeJson(listFile(context), out);
            UserSource.log("源备注：" + (clean.length() == 0 ? "(清空)" : clean));
        } catch (Exception e) {
            UserSource.log("源备注写入失败：" + e);
        }
    }
    /**
     * 从清单里划掉一个地址。
     *
     * 如果划掉的正好是当前生效的那个，连 user-source.json 一起删 —— 留着它会让
     * App 下次启动仍然读那份配置，而设置页里的清单已经没有它了，两边对不上。
     * 删完等于回到「没配源」，用户下次打开会看到设置页。
     *
     * ── 2026-10-16：缓存也要一起删 ──────────────────────────────────────
     * 只划清单不删 files/source-cache/<md5>.json 会留一个很难看出来的坑：
     * 用户把同一个地址再加回来时，`use`/`set` 走的是 applySource 的 preferCache
     * 分支，**直接命中那份旧缓存，一次网络都不发**（实测 7ms 返回，日志是
     * 「换源用的是本地缓存，没有等网络」）。而删源重加这个动作，用户做的场合
     * 十有八九就是「服务器上配置改了，我要它重新读一遍」—— 结果恰恰相反。
     *
     * 后台确实会在 300ms 后把缓存刷新成新的（refreshCacheAsync），但那时
     * Activity 已经建完了，刷新出来的东西没有任何调用方。用户看到的就是
     * 「删了、重加了、还是老样子」。
     *
     * 缓存只是「省一次往返」的优化，源都不要了就没有保留它的理由。
     */
    static void forget(Context context, String url) {
        try {
            JSONObject store = readJson(listFile(context));
            JSONArray items = store == null ? null : store.optJSONArray("saved");
            JSONArray next = new JSONArray();
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null || url.equals(item.optString("url", ""))) continue;
                    next.put(item);
                }
            }
            JSONObject out = new JSONObject();
            out.put("saved", next);
            writeJson(listFile(context), out);
            File cached = cacheFile(context, url);
            if (cached.isFile()) {
                UserSource.log(cached.delete() ? "源缓存一并清掉 " + cached.getName()
                        : "源缓存删不掉（下次加回来会命中旧的那份）：" + cached.getName());
            }
            if (url.equals(activeUrl(context))) {
                clear(context);
                UserSource.log("已删除当前源，回到未配置状态");
            }
        } catch (Exception e) {
            UserSource.log("源清单删除失败：" + e);
        }
    }
    /** 从地址猜个显示名：最后一段去掉扩展名，没有就用域名。 */
    static String guessName(String url) {
        try {
            java.net.URI uri = new java.net.URI(url.trim());
            String path = uri.getPath() == null ? "" : uri.getPath();
            int slash = path.lastIndexOf('/');
            String last = slash < 0 ? path : path.substring(slash + 1);
            int dot = last.lastIndexOf('.');
            if (dot > 0) last = last.substring(0, dot);
            if (last.length() > 0) return last;
            String host = uri.getHost();
            return host == null ? url : host;
        } catch (Exception e) {
            return url;
        }
    }










    static JSONObject readJson(File target) {
        if (target == null || !target.isFile()) return null;
        InputStream in = null;
        try {
            in = new FileInputStream(target);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return new JSONObject(new String(out.toByteArray(), "UTF-8"));
        } catch (Exception e) {
            return null;
        } finally {
            UserSource.close(in);
        }
    }


    static void writeJson(File target, JSONObject value) throws IOException {
        // 原子写：先落 <名>.tmp 再 renameTo 目标，进程被杀也不会留下半截 JSON。
        // 改名失败删掉 tmp、原文件保持不动（写法对齐 ReportQueue.rewrite）。
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        FileOutputStream out = null;
        boolean written = false;
        try {
            out = new FileOutputStream(tmp);
            out.write(value.toString().getBytes("UTF-8"));
            out.flush();
            out.getFD().sync();
            written = true;
        } finally {
            UserSource.close(out);
            if (!written) {
                tmp.delete();           // 写一半的残件别留在目录里
            }
        }
        if (!tmp.renameTo(target)) {
            tmp.delete();
            throw new IOException("写配置失败（rename）：" + target);
        }
    }
    /**
     * 拉一个地址，认出它是不是一份源配置，规范化成 JSON。
     *
     * **2026-10-19：不再「猜站点」。** 这里原来有两轮识别循环 —— 按 URL 认（AGE动漫 /
     * hanime1 / mrds66 那批 HTML 站），按响应形状认（苹果CMS 的接口）。那套能力的
     * 载体是每个站一个 Java 适配器，适配器体系已经整块拆掉，站点知识搬进 spider
     * （JS，热更下发）。所以现在只认「本身就是一份配置」的东西：用户贴的是一份
     * .json，或者一个能返回这种 JSON 的地址。
     *
     * 还认的几种：
     *   {origin|web}         我们自己的 JSON 配置      → 直接就是结果
     *   XML 接口              fetch() 里由 CmsXml 转成同形 JSON（见那边的注）
     *   {sources[]}           源市场清单               → 不是源，见 UserSource.MARKET_LIST
     *   {urls[]}              TVBox 多仓               → 二期
     *   {sites[]}             TVBox 单仓               → 二期
     */
    static JSONObject load(String url) throws Exception {
        JSONObject fetched = fetch(url);
        if (fetched == null) throw new IOException("响应为空");

        // 源市场清单（{sources:[{name,url,desc}]}）。它不是一份源，是一份源**目录** ——
        // 前端收到这个标记就把里面的源列出来让用户勾，而不是自己再猜一遍。
        //
        // 为什么要标记而不是在这里直接展开：勾选是前端的活（要渲染、要落盘、要按顺序
        // 生效），Java 这边只负责告诉它「你贴的是清单，不是源」。写成固定串是因为
        // 桥只能回人类可读的 error 字段，前端按前缀匹配——比多开一条桥指令省事。
        if (fetched.has("sources")) {
            throw new IOException("MARKET_SOURCES");
        }
        if (fetched.has("urls")) {
            throw new IOException("这是 TVBox 多仓配置（列出的是子配置地址），二期才支持");
        }
        if (fetched.has("sites")) {
            throw new IOException("这是 TVBox 单仓配置（86 个源里 79 个是 spider JAR），二期才支持");
        }
        if (fetched.has("origin") || fetched.has("web")) {
            return fetched;
        }
        throw new IOException("认不出这是什么配置 —— 它既没有 origin，也没有 web 块");
    }

    static boolean isAction(String name) {
        for (int i = 0; i < UserSource.ACTIONS.length; i++) if (UserSource.ACTIONS[i].equals(name)) return true;
        return false;
    }
    /** 一个形状位的合法形态：非空字符串（取值路径），或者 {div:[a,b]}。 */
    static boolean isShapeValue(Object value) {
        if (value instanceof String) return ((String) value).length() > 0;
        if (value instanceof JSONObject) {
            JSONArray div = ((JSONObject) value).optJSONArray("div");
            return div != null && div.length() == 2;
        }
        return false;
    }

    // ── 读写 ──────────────────────────────────────────────────────────

    static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }
    /** 有没有可用的用户配置。判断标准是「文件在」且「能解析成 JSON」。 */
    static boolean has(Context context) {
        return read(context) != null;
    }
    /** 读用户配置。没有、读不动、或不是合法 JSON 一律返回 null。 */
    static JSONObject read(Context context) {
        if (context == null) return null;
        File target = file(context);
        if (!target.isFile()) return null;
        InputStream in = null;
        try {
            in = new FileInputStream(target);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return new JSONObject(new String(out.toByteArray(), "UTF-8"));
        } catch (Exception e) {
            UserSource.log("用户配置读取失败：" + e);
            return null;
        } finally {
            UserSource.close(in);
        }
    }






    static void save(Context context, JSONObject config) throws IOException {
        // 原子写：先落 .tmp 再 renameTo（同 writeJson），user-source.json 被杀
        // 在半路时保住上一份完整配置，而不是留一个解析不了的空壳。
        File target = file(context);
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        FileOutputStream out = null;
        boolean written = false;
        try {
            out = new FileOutputStream(tmp);
            out.write(config.toString().getBytes("UTF-8"));
            out.flush();
            out.getFD().sync();
            written = true;
        } finally {
            UserSource.close(out);
            if (!written) {
                tmp.delete();           // 写一半的残件别留在目录里
            }
        }
        if (!tmp.renameTo(target)) {
            tmp.delete();
            throw new IOException("写配置失败（rename）：" + target);
        }
    }


    static void clear(Context context) {
        File target = file(context);
        if (target.isFile() && !target.delete()) UserSource.log("配置删除失败：" + target);
    }
    /**
     * 「我的 → 设置 → 恢复出厂设置」的执行体。回来之后这台设备该和刚装完一样。
     *
     * ── 为什么登录态不在这里退 ────────────────────────────────────────
     * 它得由前端先做（api('logout') → AccountService.logout）。因为**退出登录不是
     * 一件发生在盘上的事**：ParseUser 的当前用户活在内存里，写盘的那份只是它的影子，
     * 而这里的清盘（连同 cacheDir 里的 ParseKeyValueCache 一起删掉）只处理影子。
     * 内存那份还在的话，重建出来的 MainActivity 照样从 `ParseUser.getCurrentUser()`
     * 拿到 objectId、`store.activateUser()` 照样把它当登录用户 —— 界面还是登录态，
     * 只是片单空了。所以顺序是：前端先退，退干净了再进来清。
     *
     * ── 为什么清得掉的和清不掉的都要说出来 ────────────────────────────
     * 这个动作没有撤销，所以返回体里带上「清了几个 prefs、哪几个删不掉」——
     * 真机出问题时 logcat 里那句 `已恢复出厂设置` 是唯一能对账的东西。
     */
    static JSONObject factoryReset(Context context, JSONObject out) throws Exception {
        StringBuilder failed = new StringBuilder();

        // ① 内容源：当前生效那份配置 + 存过的地址清单。
        clear(context);
        File listing = listFile(context);
        if (listing.isFile() && !listing.delete()) failed.append(listing.getName()).append(' ');
        // 进程不死（重启走的是 Activity.recreate），预热结果和连接池都是 static，
        // 不作废的话下一条源会拿到上一条源的字节。和 __source:clear 同一个理由。
        AiiHttp.resetForSourceChange();

        // ② 网络代理。setProxy 同时把两个 OkHttp 客户端置空，下一次请求按直连重建。
        String proxyProblem = AiiHttp.setProxy("");
        if (proxyProblem != null) failed.append("proxy ").append(proxyProblem).append(' ');

        // ②' 内置核心。**必须在这里显式停**：setProxy("") 清的是用户手填的那个串，
        //    而内置核心在跑的时候 AiiHttp.effectiveProxy() 根本不看它 —— 核心的
        //    socksUrl() 优先。不停的话，用户点完「恢复出厂设置」被告知已直连，
        //    实际流量还从节点出去。下面 clearPreferences 会连 aii-proxy-core 一起清，
        //    但清盘只改内存和 XML，停不了 native 那边已经在跑的 goroutine。
        try {
            ProxyCore.get(context).stop();
            AiiHttp.invalidateClients();
        } catch (Throwable e) {
            failed.append("core ").append(e.getClass().getSimpleName()).append(' ');
        }

        // ③ 本机数据：片单、头像索引、DoH 缓存、更新器的记录……（两份例外见
        //    clearPreferences 的说明：hot-updates 与 content-access 不清）
        int prefs = clearPreferences(context);

        // ④ 头像文件。按 objectId 命名，退不退出登录都不会被自己的生命周期清掉，
        //    不删的话换个人登录还能在相册里翻出上一个用户的脸。
        emptyDir(new File(context.getFilesDir(), "avatars"), failed);

        // ⑤ 缓存目录。封面的解码结果、接口响应缓存、WebView 的 HTTP 缓存，
        //    以及 ParseKeyValueCache（SDK 把「当前登录用户」写在这里）。
        emptyDir(context.getCacheDir(), failed);

        // ⑥ WebView 那边的存储。DOM storage 前端自己会清，但 **Cookie 只有这里
        //    清得掉** —— 页面看得见的那些能靠 document.cookie 删，HttpOnly 的看不见。
        try {
            android.webkit.CookieManager.getInstance().removeAllCookies(null);
            android.webkit.WebStorage.getInstance().deleteAllData();
        } catch (Throwable e) {
            failed.append("webview ").append(e).append(' ');
        }

        String notes = failed.toString().trim();
        UserSource.log("已恢复出厂设置：清了 " + prefs + " 份偏好" + (notes.length() == 0 ? "" : "，未删掉 " + notes));
        out.put("ok", true);
        out.put("prefs", prefs);
        out.put("failed", notes);
        return out;
    }
    /**
     * 清掉这个应用的全部 SharedPreferences，返回清了几份。
     *
     * ── 为什么不能直接把 shared_prefs/ 目录删了 ─────────────────────────
     * 那只能删掉盘上那份 XML。SharedPreferences 是**一个名字一个内存实例**，
     * 进程活着它就在，而且之后任何一次 apply() 都会把内存里那张完整的表重新写回
     * 文件 —— 删掉的文件会自己长回来，还带着刚清掉的数据。必须走 API 清：
     * clear() 同时改内存和盘。
     *
     * ── 为什么是遍历目录，而不是列一张名字清单 ──────────────────────────
     * 清单会漏，而且漏了没有任何症状。今天在里面的是 huangguo-data（片单）、
     * aii-net（代理）、aii-dns（DoH 缓存）、huangguo-avatars（头像索引）、
     * updates（更新器），明天再加一个就静默漏一个 —— 目录里有什么就清什么，
     * 名字本身就是唯一真相。
     *
     * ── 两个例外，都不是用户数据，清了反而出事 ──────────────────────────
     *
     * ① hot-updates —— 它守着一位 `originMigrated`：应用还从 file:// 起源启动的
     *    年代留下的标志。清掉它，下一次启动会先载 file:///android_asset/app.html、
     *    跑完那次起源迁移再跳回 appassets —— 用户看到的是「刚恢复完出厂，启动还闪
     *    一下」，而这一位跟用户数据毫无关系。
     *
     * ② content-access —— 成人门槛与私密模式解锁。这个**不能清**，清完当场坏：
     *    ContentMode 的解锁状态是「内存一份、盘上一份」，清盘只会清掉后面那份，
     *    而 require() 恰好卡在这个夹角上 —— 内存里 active 还是 huangguo（进程不死），
     *    盘上 unlocked 已经 false，于是**每一个带 source 的桥指令**都抛
     *    「内容已切换，请重新打开」，包括设置页那次 __source:get。表现就是：刚恢复
     *    完出厂，设置源那一屏顶上多一行红字。而它压根不是用户数据 —— 前端每次冷启动
     *    都会自动 enter-private + confirm-private-age 把两位写回来（密码内置），
     *    清与不清，用户看到的完全一样。
     */
    static int clearPreferences(Context context) {
        File[] files = new File(context.getDataDir(), "shared_prefs").listFiles();
        if (files == null) return 0;
        int count = 0;
        for (File file : files) {
            String name = file.getName();
            if (!name.endsWith(".xml")) continue;
            name = name.substring(0, name.length() - 4);
            // 上面①②那两个例外。
            if ("hot-updates".equals(name) || "content-access".equals(name)) continue;
            try {
                context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit();
                count++;
            } catch (Throwable e) {
                UserSource.log("偏好清空失败 " + name + "：" + e);
            }
        }
        return count;
    }
    /** 把一个目录里的东西删干净，但保留目录本身 —— WebView 和 Parse 都会自己往里再找。 */
    static void emptyDir(File dir, StringBuilder failed) {
        File[] children = dir == null ? null : dir.listFiles();
        if (children == null) return;
        for (File child : children) wipe(child, failed);
    }
    /**
     * 递归删。删不掉的记一笔就走，不抛 —— 恢复出厂设置里半途中断，留下的是一台
     * 「清了一半、还进不去设置页」的设备，比多留一个文件糟得多。
     */
    static void wipe(File target, StringBuilder failed) {
        if (target == null || !target.exists()) return;
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) for (File child : children) wipe(child, failed);
        }
        if (!target.delete()) failed.append(target.getName()).append(' ');
    }
    /**
     * 从一个地址拉配置。
     *
     * 这里不挂 DoH —— DoH 服务商列表本身就在配置里，鸡生蛋问题。
     * 源地址如果是被污染域名，用户会看到「拉取失败」，那是他和他的源之间的事。
     */
    static JSONObject fetch(String url) throws Exception {
        String trimmed = url == null ? "" : url.trim();
        if (trimmed.length() == 0) throw new IOException("地址为空");
        // 明文 HTTP 拉不动：targetSdk >= 28 时 Android 默认 cleartextTrafficPermitted=false，
        // 请求会以 "CLEARTEXT communication not permitted by network security policy" 失败。
        // 这里提前拦下并说人话 —— 那条系统报错用户看不懂，也不知道该怎么办。
        // 不为此开 usesCleartextTraffic：源地址本来就是用户自己填的，要求 https 是合理的。
        if (trimmed.startsWith("http://")) {
            throw new IOException("只支持 https 地址（Android 默认禁止明文 HTTP，http:// 拉不下来）");
        }
        if (!trimmed.startsWith("https://")) {
            throw new IOException("地址要以 https:// 开头");
        }

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(FETCH_TIMEOUT, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(FETCH_TIMEOUT, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder().url(trimmed).build();

        Response response = client.newCall(request).execute();
        try {
            if (!response.isSuccessful()) throw new IOException("服务端返回 HTTP " + response.code());
            ResponseBody body = response.body();
            if (body == null) throw new IOException("响应为空");
            String text = body.string();
            if (text.length() == 0) throw new IOException("响应内容为空");
            // 体积闸门：正常配置几 KB。给到 512KB 是防呆，不是防攻击。
            if (text.length() > 512 * 1024) throw new IOException("配置过大（" + text.length() + " 字节）");
            // XML 接口（苹果CMS 的 /at/xml/，type 0 那种）解析成 JSON 同形结构，
                // 后面识别/映射/播放全部复用 JSON 那条路。
                if (CmsXml.looksLikeXml(text)) {
                    try {
                        return CmsXml.parse(text);
                    } catch (Exception e) {
                        throw new IOException("XML 解析失败：" + e.getMessage());
                    }
                }
                try {
                    return new JSONObject(text);
                } catch (Exception e) {
                    throw new IOException("返回的不是合法 JSON，也不是苹果CMS 的 XML：" + e.getMessage());
                }
        } finally {
            response.close();
        }
    }

    /**
     * 源指令入口。{@code raw} 形如 {@code get} / {@code set?url=…} / {@code web}。
     *
     * <p>原来这段是 {@code UserSource.handle()} 里的一长串 if-chain，
     * 现在它和另外四个子系统一样，是一条独立 action 的实现。
     */
    static JSONObject dispatch(Context context, SiteProfile profile, String raw, JSONObject out) {
        try {
            String body = raw == null ? "" : raw.trim();
            String action = body;
            String url = "";
            String value = "";
            int mark = body.indexOf('?');
            if (mark >= 0) {
                action = body.substring(0, mark);
                String query = body.substring(mark + 1);
                url = UserSource.param(query, "url");
                value = UserSource.param(query, "value");
            }
            if ("get".equals(action)) {
                JSONObject current = read(context);
                // 这一行是给冒烟脚本认的：没有它会分不清「用户还没配源」和
                // 「前端没起来」—— 两种情况下都没有接口调用，外观却完全不同。
                if (current == null) {
                    UserSource.log("未配源 —— 前端会停在设置页");
                } else {
                    // 配了源就立刻预热首屏。这里是最早能拿到 profile 的地方
                    // （WebView 还在载入、闸门还没过），离 goHome() 真发请求还有
                    // 好几百毫秒 —— 正好用来跑 DoH 和 TLS。
                    AiiHttp.init(context);
                    AiiHttp.warmup(profile);
                }
                out.put("ok", true);
                out.put("configured", current != null);
                out.put("origin", current == null ? "" : current.optString("origin", ""));
                out.put("url", current == null ? "" : current.optString("__url", ""));
                out.put("identity", identity(current));
                return out;
            }

            if ("clear".equals(action)) {
                clear(context);
                // 配置没了，进程里那份预热/连接池也得跟着走 —— 否则下次配了别的源，
                // 首页可能拿到「清空之前那个源」的字节。
                AiiHttp.resetForSourceChange();
                UserSource.log("已清除用户源");
                out.put("ok", true);
                out.put("configured", false);
                return out;
            }

            if ("proxy:get".equals(action)) {
                out.put("ok", true);
                out.put("proxy", AiiHttp.proxyRaw());
                return out;
            }

            if ("proxy:set".equals(action)) {
                // 只写不重建连接：setProxy 会把客户端置空，下一次请求自然重建。
                // 这里不主动发请求验证 —— 代理通不通要等真流量才知道，而「填完
                // 立刻测一次」会在代理没起来时给一个吓人的报错。
                String problem = AiiHttp.setProxy(value);
                if (problem != null) return UserSource.fail(out, problem);
                out.put("ok", true);
                out.put("proxy", AiiHttp.proxyRaw());
                return out;
            }

            if ("web".equals(action)) {
                // 把**当前生效**的 web 块交给前端。内置那份 site-web.js 是打包时
                // 从模板生成的，用户换源之后 paths/categories/fields 可能都不一样，
                // 前端必须拿到合并后的值而不是打包时那份。
                //
                // 合并早就在 SiteProfile 构造里做完了（merge(内置, 用户配置)），
                // 这里只是把结果取出来 —— 不重复实现一遍合并。
                JSONObject web = profile == null ? null : profile.object("web");
                if (web == null) {
                    return UserSource.fail(out, "当前配置里没有 web 块");
                }
                out.put("ok", true);
                out.put("web", web);
                return out;
            }

            if ("set".equals(action) || "use".equals(action)) {
                if (url.length() == 0) return UserSource.fail(out, "没有提供源地址");
                // use 优先吃本地缓存（换源不等网络）；set 是「刚填进来的地址」，
                // 缓存里不可能有它，走同一条路不会命中而已。计时是给页面显示用的 ——
                // 「本地缓存 · 180ms」和「拉了 2.4 秒」对用户是两件事。
                long started = System.currentTimeMillis();
                boolean[] usedCache = new boolean[1];
                String problem = applySource(context, url, "use".equals(action), usedCache);
                if (problem != null) return UserSource.fail(out, problem);
                remember(context, url);
                // 换源只重建 Activity，**进程不死** —— 而预热结果和连接池都是 static，
                // 不清的话新源会拿到旧源的首页字节（实测：切完直接弹「内容更新失败」）。
                AiiHttp.resetForSourceChange();
                // 内置核心的分流表也是「启动时固化」的（XrayConfig.buildString 抄的是
                // 那一刻的 NetRoute.declaredProxyHosts）。换源不重建它，就会出现
                // 「App 判代理、核心判直连」各走各的 —— 2026-10-20 黄果 62 秒超时
                // 就是这么来的。这里跟着连接池一起重置。
                try {
                    ProxyCore.get(context).reapplyRouting();
                } catch (Throwable broken) {
                    // 核心没起来 / 正在切换时重建失败不该拦住换源本身 ——
                    // 下一次连节点还会按新声明重建一次。
                    UserSource.log("核心分流表重建失败（不影响换源）：" + broken);
                }
                JSONObject saved = read(context);
                long spent = System.currentTimeMillis() - started;
                UserSource.log("已设置用户源 → " + (saved == null ? "" : saved.optString("origin", ""))
                        + "  " + spent + "ms");
                out.put("ok", true);
                out.put("configured", true);
                out.put("origin", saved == null ? "" : saved.optString("origin", ""));
                out.put("url", url);
                out.put("cached", usedCache[0]);
                out.put("ms", spent);
                return out;
            }

            // 通用抓取：源市场清单、TVBox 单仓配置这类「不是源配置本身的 JSON」
            // 也要经原生 HTTP 拉回来 —— WebView 直接 fetch 会被 CORS 拦（页面
            // origin 是 appassets.androidplatform.net，第三方接口不会带 ACAO 头）。
            // __http: 那条通道有播放层主机白名单，不能挪用；这里单独开一个，
            // 只限 https、限大小、限超时，内容交回前端自己解析。
            if ("fetch".equals(action)) {
                if (url.length() == 0) return UserSource.fail(out, "没有提供地址");
                if (!url.startsWith("https://")) return UserSource.fail(out, "只放行 https：" + url);
                // 故意**不走 AiiHttp**：那条 client 挂着当前源的 DoH 解析器，
                // 对市场这种「和源无关的第三方域名」会解析出坏答案（实测 sslip.io
                // 在 DoH 下直接 UnknownHost）。这里用系统解析的裸连接，和热更新
                // 通道同样的路径。
                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                try {
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(20000);
                    conn.setRequestProperty("User-Agent", "AIIvideo/1.0");
                    int code = conn.getResponseCode();
                    if (code < 200 || code >= 300) return UserSource.fail(out, "HTTP " + code);
                    java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                    java.io.InputStream in = conn.getInputStream();
                    byte[] chunk = new byte[8192];
                    int read;
                    while ((read = in.read(chunk)) > 0) {
                        buffer.write(chunk, 0, read);
                        if (buffer.size() > 2_000_000) return UserSource.fail(out, "内容超过 2MB，不像一份源清单");
                    }
                    out.put("ok", true);
                    out.put("body", new String(buffer.toByteArray(), "UTF-8"));
                    return out;
                } finally {
                    conn.disconnect();
                }
            }

            // 后台把某条地址的配置重新拉一遍，只更新**本地缓存**，不动当前生效那份 ——
            // 于是「用缓存秒切」不会让配置永远停在旧版本，而更新也不会触发第二次重启。
            // 下一次切到这个源时用的就是新的。前端也可以手动调（排障用）。
            if ("refresh".equals(action)) {
                if (url.length() == 0) return UserSource.fail(out, "没有提供源地址");
                try {
                    JSONObject fresh = load(url);
                    String problem = UserSource.validate(fresh);
                    if (problem != null) return UserSource.fail(out, problem);
                    writeCache(context, url, fresh);
                    UserSource.log("已刷新源缓存 " + cacheFile(context, url).getName()
                            + "（" + fresh.toString().length() + " 字节）");
                    out.put("ok", true);
                } catch (Exception e) {
                    String why = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
                    return UserSource.fail(out, "刷新失败：" + why);
                }
                return out;
            }

            // 给一个源写备注（名字是给人看的，为空就退回自动取的那个名字）。
            if ("note".equals(action)) {
                if (url.length() == 0) return UserSource.fail(out, "没有提供源地址");
                note(context, url, value);
                JSONObject listing = list(context);
                out.put("ok", true);
                out.put("items", listing.optJSONArray("saved"));
                return out;
            }

            if ("list".equals(action)) {
                JSONObject listing = list(context);
                out.put("ok", true);
                out.put("active", listing.optString("active", ""));
                out.put("items", listing.optJSONArray("saved"));
                // 当前生效那个源的身份。观看历史靠它判断「这条记录能不能在这个源里打开」。
                // 算的是**当前配置**而不是列表里那条 —— 用户可能手改过配置文件，
                // 列表里存的只是「从哪个地址拉的」，配置本身才是真相。见 identity()。
                String ident = identity(read(context));
                // 打出来看得见。源身份决定了观看历史能不能互通，出问题时
                // 「算出来是什么」比「应该是多少」有用得多。
                UserSource.log("源身份 " + (ident.length() == 0 ? "(未配源)" : ident));
                out.put("identity", ident);
                return out;
            }

            if ("restart".equals(action)) {
                out.put("ok", true);
                restart(context);
                return out;
            }

            if ("forget".equals(action)) {
                if (url.length() == 0) return UserSource.fail(out, "没有提供源地址");
                forget(context, url);
                out.put("ok", true);
                out.put("active", read(context) == null ? "" : url);
                return out;
            }

            // 恢复出厂设置。前端那个入口在「我的 → 设置」的最后一块，语义就是
            // 把这台设备变回刚装好的样子：没有片单、没有缓存、没有源。
            // 登录态不在这里退 —— 它必须由前端的 logout 先做掉，理由见 factoryReset。
            if ("reset".equals(action)) {
                return factoryReset(context, out);
            }

            return UserSource.fail(out, "不认识的指令：" + action);
        } catch (Throwable e) {
            UserSource.log("源设置异常：" + e);
            return UserSource.fail(out, "内部错误：" + e);
        }
    }
}
