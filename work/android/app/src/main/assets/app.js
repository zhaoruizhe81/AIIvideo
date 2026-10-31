'use strict';
/*
 * 「这个文件从头到尾解析通过了」的标记。
 *
 * app.html 末尾那块兜底面板只看它：整份解析失败时下面一句都不会执行，标记缺席，
 * 面板就出来说人话，而不是留一片空白。2.0.23 线上两台设备就是这样 —— `?.` 和
 * `||=` 让 14 个脚本同时倒在解析阶段，除了 app_open 一个业务事件都没有。
 *
 * 为什么放在**最前面**而不是启动链末尾：解析失败是整份的，标记放哪儿都评不上；
 * 而放最前面能顺带把「解析通过但启动早期就抛了」和「解析失败」分开。
 */
window.__hgParsed = 1;
const callbacks = new Map();
let requestNumber = 0;
let contentSource = 'hongguo';
let contentReady;
const bridgeInstance = Date.now() + '-' + Math.random().toString(36).slice(2);
// contentSource 只有一个值了（'hongguo'），双态那套判断整块删掉。
// 留这行注释是因为下面还有几处历史注释提到 privateMode()，读代码时别去找函数。
// 「恢复出厂设置」跑到一半留下的标记。它只交代一件事：那一次登出如果没等到网络
// 回来，下一次启动补一刀（见 finishResetLogout）。住在 localStorage 里，所以
// **必须最后写** —— 写早了会被重置自己的那次全量 clear() 一起清掉。
const RESET_FLAG = 'hgpref:factoryReset';
window.NativeCallbacks = {
  resolve(id, value) { callbacks.get(id)?.resolve(value); callbacks.delete(id); },
  reject(id, message) { callbacks.get(id)?.reject(new Error(message)); callbacks.delete(id); }
};
// 接口给的封面有两种形态：/api/videos 是完整地址（尾上挂个带过期时间的 auth_key），
// /api/home 是裸相对路径 /upload_01/upload/xxx.jpg。相对路径直接塞进 <img src> 会被
// 解析到 appassets.androidplatform.net 底下，必然 404；而 Java 的 SiteProfile.coverUrl
// 要求 URI 必须是绝对 https + host 在 cover.allowedHosts 白名单里，喂相对路径第一关
// 就抛「无效封面地址」。所以这里统一补域名。实测图片 CDN 不校验 auth_key（拿伪造的
// 过期签名一样 200），顺手剥掉，换成永久地址。
//
// 注意：封面本身是 AES-CBC 加密的（site-default.json: key=f5d965df75336270,
// iv=97b60374abc2fbe1, AES/CBC/NoPadding + headerRepair=legacy-xor），补完域名之后
// 必须继续交给 Java 的 cover 动作解密，不能直接把地址丢给 <img>。
// URL 归一已移进 cover.js（IMG_ORIGIN 和补全逻辑都在那边），这里只留个别名，
// 因为 adaptVideo / adaptHome 拼卡片数据时要反复用。
const coverImageUrl = raw => window.HGCover.resolveUrl(raw);
// 桥调用没有超时，这是「详情页永远停在正在获取选集…」的根源：Java 那侧一旦
// 因为 Activity 重建、线程池卡住、请求丢了而不回调，这个 Promise 就永远不
// settle —— 页面一直 loading，连错误都没有。Java 的读超时是 15 秒，这里给
// 30 秒上限（CMS 的详情偶尔要连两个接口），超时就当失败处理，页面能进错误态、
// 返回键也正常。
// 桥的等待上限。订阅下载实测 TTFB 能到 10 秒、加上重试就 20 秒往上，
// 30 秒会把「正在拉」变成「请求超时」。放宽到 45 秒，仍然有上限。
const BRIDGE_TIMEOUT_MS = 45000;
/**
 * 上报一条事件。fire-and-forget —— 任何失败都不往上冒，调用点不用 try/catch。
 *
 * 走桥的 report 指令，Java 侧负责落盘、攒批、发送（见 Report / ReportQueue）。
 * 这里不 await：上报慢一秒不该让用户多等一秒，播放路径上尤其不能。
 *
 * kind 是粗分类（play / app / js），name 是具体事件，props 里放 stage、错误码、耗时。
 */
function track(kind, name, props) {
  try {
    bridgeCall('report', { kind: kind, name: name, props: props || {} }).catch(() => {});
  } catch (ignored) {}
}

// timeoutMs：本次调用的等待上限（毫秒）。传 0 = 不限时（下载更新要跑几分钟）；
// 缺省或非法值回落 BRIDGE_TIMEOUT_MS。
function bridgeCall(action, args = {}, timeoutMs) {
  return new Promise((resolve, reject) => {
    const id = bridgeInstance + ':' + (++requestNumber);
    const budget = timeoutMs === 0 ? 0 : (Number(timeoutMs) > 0 ? Number(timeoutMs) : BRIDGE_TIMEOUT_MS);
    const timer = budget > 0 ? setTimeout(() => {
      if (!callbacks.has(id)) return;
      callbacks.delete(id);
      reject(new Error('请求超时（' + action + '）'));
    }, budget) : null;
    const finish = fn => value => { if (timer) clearTimeout(timer); fn(value); };
    callbacks.set(id, { resolve: finish(resolve), reject: finish(reject) });
    try { AndroidHost.request(id, action, JSON.stringify({ ...args, source: contentSource })); }
    catch (error) { if (timer) clearTimeout(timer); callbacks.delete(id); reject(error); }
  });
}
// 播放层（hxm/http.js）要用同一条桥。它是本模块作用域里的东西，得显式挂出去。
window.HG_BRIDGE = bridgeCall;
// 源相关的桥指令统一入口。
//
// 2026-10-18 之前这里走的是 detail 桥 + "__source:" 前缀 —— 一条 action 同时
// 承载「取详情页」和「六个子系统的控制面」，加一个功能要在本文件包一层、
// 再去 UserSource 的 startsWith 链上插一段，两个文件都得动。
//
// 现在控制面各占一条 action（source / player / proxy / progress / http），
// detail 只留详情。Java 那边的 dispatch 是 Map<String, Command>，
// 加一条动作就是一行 registerCommands，不用碰别的表。
async function sourceCommand(command) {
  const reply = await bridgeCall('source', { id: command });
  const result = typeof reply === 'string' ? JSON.parse(reply) : reply;
  if (result && result.ok === false) throw new Error(result.error || '操作失败');
  return result || {};
}

// 换源之后必须让 Java 侧重建 Activity。SiteProfile 是在 onCreate 的链条里构造的
// （beginSession → new SiteProfile），而它每构造一次都重读 files/user-source.json ——
// 所以「重建 Activity」就等于「新配置生效」。只 location.reload() 不够：页面重载了
// 但 Activity 还在，配置还是旧的。
//
// 这里刻意不 await：Java 那边先 post 到主线程、200ms 后才 recreate，等它的话页面
// 会在被销毁前挂起，promise 永远不 resolve，界面就卡在「正在切换…」上。所以发出去
// 就不管了，只留一个兜底 —— 2.5 秒后这个页面还在，说明重建没发生，把状态改成人话。
function restartAfterSource(onStuck) {
  sessionStorage.clear();
  sourceCommand('restart').catch(() => {});
  window.setTimeout(() => { if (typeof onStuck === 'function') onStuck(); }, 2500);
}

// 播放器那几条要原生帮忙的指令（亮度 / 清晰度）。和 sourceCommand 同一个套路：
// 控制面独立 action，前缀由 Java 侧补，这里只传裸指令。
async function playerCommand(command) {
  const reply = await bridgeCall('player', { id: command });
  const result = typeof reply === 'string' ? JSON.parse(reply) : reply;
  if (result && result.ok === false) throw new Error(result.error || '播放指令失败');
  return result || {};
}

// ── 播放器设置 ────────────────────────────────────────────────────────
// 两条播放路径：WebView 里的 <video>（默认）和原生 libVLC（1.0.18 起）。
//
// 为什么需要第二条：<video> 用哪个解码器由系统 WebView 决定，页面上没有任何
// 旋钮。于是同一部片在 A 机上能放、B 机上 MediaError 4（SRC_NOT_SUPPORTED）——
// 差别只有那台设备有没有这个硬解、WebView 版本里带没带这个解码器，而软件解码
// 这条路在 WebView 里根本不存在。走内置播放器之后解码链在 App 里，和设备硬解、
// WebView 版本都脱钩。
const PLAYER_PREF_KEY = 'hgpref:player';
// 2026-10-17 起只用内置播放器（libVLC）。core 和 autoFallback 两个开关
// 留着是为了兼容老设备上存过的偏好键，逻辑上已经不起作用 —— 播放一律走原生。
const PLAYER_DEFAULTS = { core: 'native', decode: 'auto', cache: 1500, autoFallback: false };

function playerPrefs() {
  let saved = {};
  try { saved = JSON.parse(localStorage.getItem(PLAYER_PREF_KEY) || '{}') || {}; } catch (error) {}
  const prefs = { ...PLAYER_DEFAULTS, ...saved };
  prefs.core = 'native';
  if (prefs.decode !== 'sw') prefs.decode = 'auto';
  prefs.cache = [800, 1500, 3000].includes(Number(prefs.cache)) ? Number(prefs.cache) : 1500;
  prefs.autoFallback = false;
  return prefs;
}

function savePlayerPrefs(patch) {
  const next = { ...playerPrefs(), ...patch };
  try { localStorage.setItem(PLAYER_PREF_KEY, JSON.stringify(next)); } catch (error) {}
  return next;
}

// 内置播放器到底能不能用（APK 只带了 arm64-v8a）。问一次缓存一页。
// 这个探测会真的去加载 so —— 45MB 的映射，别反复调。
function nativePlayerInfo() {
  if (state.nativeInfo) return Promise.resolve(state.nativeInfo);
  return playerCommand('capability')
    .then(info => { state.nativeInfo = info || { native: false, abi: '' }; return state.nativeInfo; })
    .catch(() => { state.nativeInfo = { native: false, abi: '' }; return state.nativeInfo; });
}

// 内置播放器（VlcPlayerActivity）按需向页面要播放地址的口子。
// 解密、wasm、多线路全在这边，原生只发一个问题：这一集的 URL 是什么。
//
// 两条硬约束：
//   1) 必须是**同步**函数。原生用的是 evaluateJavascript，它不等 Promise ——
//      返回 Promise 的话那边收到的是字符串 "{}"，等于永远拿不到地址。
//   2) 结果要落在 window.__hgNativeEps 这个表里（外加 localStorage 缓存）。
//      原生每 0.8 秒问一次，靠这张表把「正在解」和「解出来了」区分开。
window.__hgNativeEps = window.__hgNativeEps || {};
window.__hgNativeEpsBusy = window.__hgNativeEpsBusy || {};
// 解析失败的负缓存（key → 失败时刻）。原生每 0.8 秒轮询一次 __hgNativeEpisode，
// 失败不记的话一个坏源会在播放页开着期间被全量重解析（桥 + spider + 网络）到天荒地老。
window.__hgNativeEpsFailed = window.__hgNativeEpsFailed || {};

/** epurl 落盘缓存的有效期：签名地址按 8 分钟算，过期就重新解析。 */
const EPURL_TTL_MS = 8 * 60 * 1000;
/** 解析失败后的冷却期：期内 __hgNativeEpisode 不再触发新的解析。 */
const RESOLVE_FAIL_TTL_MS = 45000;

function hgNativeEpKey(id, ep) { return id + ':' + ep; }

/**
 * 播放地址解析失败的上报口。
 *
 * 以前 hgNativeResolve 的收尾是 `.catch(() => {})` —— 一个字都不留。而这一句
 * 恰好罩住了最常见的「播不了」：接口没给地址、给了但不是 http(s)、wasm 解不出来。
 * 结果就是全库 15,932 条事件里 kind='play' 零条 —— 最该有数据的链路一个样本都没有。
 *
 * 按 `stage|code|msg` 去重、上限 10 条：同一个坏源会被反复点，逐条上报只是噪音。
 */
const PLAY_RESOLVE_SEEN = new Set();
function trackPlayResolveError(stage, code, message) {
  try {
    const text = String(message == null ? '' : message);
    const key = stage + '|' + code + '|' + text.slice(0, 80);
    if (PLAY_RESOLVE_SEEN.has(key) || PLAY_RESOLVE_SEEN.size >= 10) return;
    PLAY_RESOLVE_SEEN.add(key);
    track('play', 'play_error', {
      stage: stage,
      code: code || 'error',
      msg: text.slice(0, 200),
      mode: 'single'
    });
  } catch (ignored) {}
}

function hgNativeResolve(id, ep) {
  const key = hgNativeEpKey(id, ep);
  if (window.__hgNativeEpsBusy[key]) return;   // 已经在解了，别重复打接口
  if (Date.now() - (Number(window.__hgNativeEpsFailed[key]) || 0) < RESOLVE_FAIL_TTL_MS) return;   // 刚失败过，冷却期内不再打接口
  window.__hgNativeEpsBusy[key] = true;
  api('episode', { id, episode: ep }).then(info => {
    let url = info.source || info.url || '';
    const resolveWasm = url.indexOf('agedm-wasm:') === 0 && window.HG_AGEDM_PLAY
      ? (Array.isArray(info.lines) && info.lines.length
          ? window.HG_AGEDM_PLAY.resolveAny(info.lines, () => {})
          : window.HG_AGEDM_PLAY.resolve(url.slice('agedm-wasm:'.length), () => {}))
      : Promise.resolve(url);
    return Promise.resolve(resolveWasm).then(resolved => {
      if (!/^https?:\/\//.test(resolved)) {
        // 解析出来是空串或者不是 http(s)：以前这里静默 return，用户看到的是
        // 一个永远转圈的播放页，后台一条痕迹都没有。记一笔负缓存，别让 0.8 秒
        // 的轮询把这条失败路径变成全量重解析的循环。
        window.__hgNativeEpsFailed[key] = Date.now();
        trackPlayResolveError('resolve', 'empty-url',
          '解析结果不是 http(s) 地址：' + String(resolved == null ? '' : resolved).slice(0, 60));
        return;
      }
      window.__hgNativeEps[key] = resolved;
      delete window.__hgNativeEpsFailed[key];   // 成功了就解除冷却
      try {
        const now = Date.now();
        const all = JSON.parse(localStorage.getItem('hgpref:epurl') || '{}');
        all[key] = { url: resolved, at: now };
        // 这份表只增不删会无限膨胀（读取处虽有 TTL 但从不清理），写一次顺手清一遍。
        Object.keys(all).forEach(stale => {
          if (now - ((all[stale] && all[stale].at) || 0) >= EPURL_TTL_MS) delete all[stale];
        });
        localStorage.setItem('hgpref:epurl', JSON.stringify(all));
      } catch (error) {}
    });
  }).catch(error => {
    window.__hgNativeEpsFailed[key] = Date.now();
    trackPlayResolveError('resolve', (error && error.name) || 'error',
      (error && error.message) || String(error));
  }).then(() => { delete window.__hgNativeEpsBusy[key]; });
}

window.__hgNativeEpisode = (id, ep) => {
  const key = hgNativeEpKey(id, ep);
  const live = window.__hgNativeEps[key];
  if (live) return live;
  try {
    const hit = JSON.parse(localStorage.getItem('hgpref:epurl') || '{}')[key];
    if (hit && hit.url && Date.now() - (hit.at || 0) < EPURL_TTL_MS) {
      window.__hgNativeEps[key] = hit.url;
      return hit.url;
    }
  } catch (error) {}
  hgNativeResolve(id, ep);
  return '';
};

// 播放器模块（feed-player.js）从这里取设置和两条桥指令。挂 window 而不是加参数，
// 是因为那块代码已经有一份 deps 列表，再加三个字段要动整个 create() 的签名。
window.HG_PLAYER = {
  prefs: playerPrefs,
  save: savePlayerPrefs,
  command: playerCommand,
  info: nativePlayerInfo,
  last: () => playerCommand('last').catch(() => null)
};

// 内容类动作：路径必须来自源的 web.paths。
//
// 这条边界以前是模糊的 —— 拿不到路径就回退到 Java 那七条「抓 HTML」的命令
// （home / category / search / tags / tag-list / episode / recommend），
// 而它们从转成 REST 那天起就只会抛 UnsupportedOperationException。
// 命令在 2026-10-18 删掉了，所以这里把「没有路径」当成一个明确的配置错误说出来，
// 而不是让用户看到一句 unknown action。
const CONTENT_ACTIONS = new Set(['home', 'category', 'search', 'tags', 'tag-list', 'episode', 'recommend', 'newest']);

// ── spider 源：内容请求不走 /api ────────────────────────────────
//
// 源的配置里只要有 web.spider，内容就由运行时提供，前端不再拼任何 URL ——
// 这是 web.paths 退役的那一步。判断放在 api() 最前面：一条源的形态是二选一
// （路径 or spider），不该两种都走一遍再挑一个。
// 内容动作 → spider 能力名。**这张表只回答「这个动作该调哪个函数」**，
// 不回答「这个源有没有」—— 后者由源自己说了算（见 spiderMetaOf）。
//
// 以前这里是「映射表 + 黑名单」两张表：黑名单里列着的动作，前端直接抛
// 「这个 spider 源暂不支持」。老源 补了 tags / tagList 之后前端照样拦着，
// 用户看到的是「源不支持」，其实源已经实现了。问题不在那张表列了什么，
// 在于**前端不该替所有源做这个判决**。现在改成问源。
const SPIDER_CAP = {
  home: 'home', category: 'category', search: 'search', detail: 'detail',
  tags: 'tags', 'tag-list': 'tagList', recommend: 'recommend', newest: 'newest'
};

/**
 * 源的能力集，按 id 缓存。
 *
 * ── 从哪来 ─────────────────────────────────────────────────────────
 * `spider` 桥的 `__meta` 伪能力：注入源码之后读一遍 __spiderSite，枚举里面的函数名。
 * 它不是源里写的字面量声明，是运行时事实 —— 源加一个函数，壳立刻知道；忘了同步
 * 一份 caps 数组也不会出现「实现了但前端说没有」。
 *
 * ── 为什么缓存 ───────────────────────────────────────────────────
 * 每次探测要开一个 WebView（几百毫秒）。api() 是热路径，不缓存等于每次翻页多花
 * 半秒。缓存粒度是 id：同一个源装一次问一次，重装源时清（见 HG_SPIDER_META_RESET）。
 */
const spiderMetaCache = new Map();
let spiderMetaPending = null;

async function spiderMetaOf(id) {
  const key = String(id || '');
  if (!key) return { caps: [], meta: null };
  if (spiderMetaCache.has(key)) return spiderMetaCache.get(key);
  if (spiderMetaPending && spiderMetaPending.key === key) return spiderMetaPending.promise;
  const promise = (async () => {
    try {
      const payload = await bridgeCall('spider', { op: 'call', id: key, cap: '__meta', args: {}, timeoutMs: 15000 });
      const info = {
        caps: asArray(payload && payload.caps).map(one => String(one)),
        meta: (payload && payload.meta) || null
      };
      spiderMetaCache.set(key, info);
      console.log('[spider-meta] ' + key + ' caps=' + info.caps.join(','));
      return info;
    } catch (error) {
      // 探测失败当空能力集：不拦任何调用，让源自己报「没有实现 xxx」。
      // 拦错方向的代价比漏判大 —— 用户会以为源坏了。
      console.log('[spider-meta] ' + key + ' 探测失败：' + (error.message || error));
      const empty = { caps: [], meta: null };
      spiderMetaCache.set(key, empty);
      return empty;
    } finally {
      if (spiderMetaPending && spiderMetaPending.key === key) spiderMetaPending = null;
    }
  })();
  spiderMetaPending = { key, promise };
  return promise;
}

// 装源/删源之后要重新问一次 —— 源码换了，能力集可能跟着变。
window.HG_SPIDER_META_RESET = function () {
  spiderMetaCache.clear();
  spiderMetaPending = null;
};

/**
 * 这个源用哪个 spider。
 *
 * ── web.spider 现在两种写法都认（2026-10-19 改）─────────────────────
 *
 *   ① "https://…/yhdm.js"  → 源码地址。取最后一段去掉 .js 当 id。
 *   ② "yhdm"               → 纯 id（旧写法，已发布的配置还在用）
 *
 * 为什么改成地址优先：TVBox 的 sites[] 里每一条都带自己的 spider 字段指向源码，
 * 配置**自己声明**源码在哪。之前我们靠「配置和源码放同一个目录」去猜，猜得到
 * 是运气 —— 而且一个配置想引用别处的源码就没办法了。
 *
 * 注意取出来的是 **id 不是 url**：SpiderStore.call 要的是已安装源的 id
 * （源码装进 files/spiders/<id>.js），源码的下载发生在装源那一步，不在这里。
 * 这里只做「地址 → id」这一小步，和 java.spiderMetaId 里的正则保持同一套规则。
 */
function spiderIdOf() {
  const site = window.HG_SITE || {};
  const raw = String(site.spider || '').trim();
  if (!raw) return '';
  if (!/^https?:\/\//i.test(raw)) return raw;
  // 去掉查询串和结尾斜杠，取最后一段去掉 .js
  const path = raw.replace(/[?#].*$/, '').replace(/\/+$/, '');
  const last = path.substring(path.lastIndexOf('/') + 1);
  return last.replace(/\.js$/i, '');
}

/** 内部动作名 → spider 能力名。episode 是「要第 n 集的地址」，对应 play。 */
function spiderCapFor(action) {
  if (action === 'episode') return 'play';
  return SPIDER_CAP[action] || '';
}

/**
 * 第 n 集在 spider 里的 ref。
 *
 * 转换层把 episodes[].ref 原样留了下来（那是站点自己的标识：yhdm 的 `ep1`、
 * cms 的 `1`、单集源的整个 id），这里就该用它 —— 不再自己拼 `id:epN`。
 * 四条规则：
 *   · ref 和 id 相同（单集源，ref 就是它自己）→ 只给 id。拼成
 *     `id:id` 会让它把整个串当 id 去请求，直接 404。
 *   · ref **已经以 `id:` 开头**（星空影视这种源，episodes[].ref 直接给
 *     `275189709:13`）→ 原样用。再拼一次就成了 `id:id:13`，spider 按最后一个
 *     冒号切，id 变成 `id:id`，站点对不存在的 id 一律回第一集 —— 真机症状是
 *     「点第 13 集播第 1 集」，而且每一集都这样。
 *   · ref 不同 → `id:ref`。所有源的 play 都用 splitRef 拆冒号，这是通行写法。
 *   · 拿不到详情（深链直接进播放器、或详情还没回来）→ `id:ep{n}`，
 *     拆出来 id 是对的，集号退化成 1~n 里能识别的那个。
 */
function spiderEpisodeRef(rawId, no) {
  const id = String(rawId || '');
  const detail = state.detail;
  const sameVideo = detail && String(detail.id || '') === id;
  const eps = sameVideo ? asArray(detail.episodes) : [];
  const hit = eps.find(one => Number(one.number || one.episodeNumber) === no) || eps[no - 1];
  const ref = hit && hit.ref ? String(hit.ref) : '';
  if (!ref) return id + ':ep' + no;
  if (ref === id) return ref;
  if (id && ref.indexOf(id + ':') === 0) return ref;   // 已经是完整 ref，别重复拼
  return id + ':' + ref;
}

/**
 * 内部动作名 → 模型里的结构化参数。spider 只认 ref/keyword/slug/page/ep/line。
 *
 * ── 为什么还要透传配置项 ──────────────────────────────────────────────
 * 有的源需要**自己的**配置才知道往哪打：苹果CMS 没有固定域名（一个类覆盖几千个站），
 * 接口地址只能由源配置给（`web.api`）。而 spider 拿不到配置 —— 它只有一个 args。
 *
 * 所以约定 `web.spiderArgs`：一个键名数组，列出的键从合并后的 web 块原样抄进 args。
 *
 *   "web": { "spider": "cms", "api": "https://…/api.php/provide/vod",
 *            "spiderArgs": ["api"] }
 *
 * 为什么是「源声明要哪些」而不是「把整个 web 块塞进去」：
 *   · web 块里有 ui / categories / fields / paths，动辄几十 KB，每次都跨桥传一遍
 *     是白花掉的序列化成本，而且 spider 里也没有一个键叫 ui；
 *   · 显式声明等于一份可读的依赖清单 —— 看配置就知道这个源依赖哪几个字段。
 *
 * 这一条是 2026-10-19 拆完适配器才暴露的：`cms.js` 从 `args.api` 读接口地址，
 * 而这里从来没传过，于是「配置里写 web.api」是死的，但表面上看不出问题 ——
 * 源会用兜底域名跑，用户以为自己的配置生效了。
 */
function spiderArgsFor(action, args) {
  const page = Number(args.page) || 1;
  const base = (() => {
    if (action === 'home') return {};
    if (action === 'category') return { slug: args.id || args.slug || '', page };
    if (action === 'tag-list') return { slug: args.slug || args.id || '', label: args.label || '', page };
    // tags 是一次性清单，没有分页也没有入参。给空对象，别让标签页看起来像
    // 缺参数的分类页（spider 那边会把 undefined 当空串，行为一样，但日志脏）。
    if (action === 'tags') return {};
    if (action === 'search') return { keyword: args.query || '', page };
    if (action === 'detail') return { ref: String(args.id || '') };
    if (action === 'episode') {
      const no = Number(args.episode) || 1;
      return { ref: spiderEpisodeRef(args.id, no), ep: no, line: String(args.line || '') };
    }
    return args;
  })();
  const declared = (window.HG_SITE && window.HG_SITE.spiderArgs) || [];
  if (!Array.isArray(declared) || declared.length === 0) return base;
  const out = Object.assign({}, base);
  declared.forEach((key) => {
    // 只抄声明过的、且真有值的键。空值不抄 —— 让 spider 的缺省生效，
    // 比传一个空串过去让它自己判空更不容易出错。
    if (typeof key !== 'string' || !key) return;
    const value = window.HG_SITE[key];
    if (value === undefined || value === null || value === '') return;
    out[key] = value;
  });
  return out;
}

/**
 * 一次 spider 能力调用。**兜底也走这里**，不只在 api() 的能力预判里。
 *
 * ── 为什么要两条路都兜（2026-10-25）────────────────────────────────
 *
 * `contentFallback` 原来只挂在 api() 里那句预判上：
 *
 *     if (info.caps.length && info.caps.indexOf(cap) < 0) { 兜底 }
 *
 * 只有当**探测成功、且明确没有这个能力**时才兜。可探测本身会失败（宿主页
 * 建不起来、`__meta` 超时），失败时 caps 是空数组，`caps.length` 为 0，
 * 整句跳过 —— 直接调，然后源抛「spider 没有实现 tagList」，用户看到一句
 * 带内部函数名的技术报错。
 *
 * 线上就是这么来的：19 条 `没有实现 tagList`、3 条 `没有实现 tags`，全在
 * 2.0.20 上，全来自两台没探到能力集的设备。源本身没坏 —— tag-list 在绝大多数
 * 站上就等于 category（见 siteTagFallback 那段注释），兜底完全能接住。
 *
 * 现在：预判能兜就预判兜，预判兜不了（探测失败/没探过）就在**调用失败时**
 * 再兜一次。两条路都试过才算真没有。
 */
async function spiderApi(action, args) {
  const id = spiderIdOf();
  if (!id) throw new Error('这个源没有声明 web.spider');
  const cap = spiderCapFor(action);
  if (!cap) throw new Error('前端不认识这个内容动作：「' + action + '」（SPIDER_CAP 里没有映射）');
  const model = window.HGModel;
  if (!model) throw new Error('模型层没加载：model.js 不在页面里');
  const spiderArgs = spiderArgsFor(action, args);
  // 一行进/出日志。阶段 2 第一次真机播放时，播放器拿到地址却没有任何网络动作 ——
  // 有 home/detail 的日志、没有 play 的，说不清是「没走这条」还是「走了但命中缓存」。
  // 这条日志就是为了让那种情况一眼可辨：进这一行 = 走了 spider。
  console.log('[spider-api] ' + action + ' → cap=' + cap + ' args=' + JSON.stringify(spiderArgs));
  let payload;
  try {
    // timeoutMs 同时喂两头：args 里那份是原生 spider 宿主的预算，第三参是
    // 前端 bridgeCall 的等待上限。只给原生不给前端的话，45 秒默认值会先把
    // 慢调用 reject 掉，原生的 90 秒预算形同虚设。
    payload = await bridgeCall('spider', {
      op: 'call', id, cap, args: spiderArgs, timeoutMs: 90000
    }, 90000);
  } catch (error) {
    // 能力没实现 → 先试兜底；兜不了再把原始错误翻译成人话抛出去。
    if (isMissingCapability(error)) {
      const info = await spiderMetaOf(id);
      const fallback = contentFallback(action, info.caps, args);
      if (fallback) {
        console.log('[spider-api] ' + action + ' 源没实现 ' + cap + '，走本机兜底');
        return fallback;
      }
      // 不往 caps 里塞假条目做缓存：探测失败那种情况本来就没探到能力集，
      // 往里写事实会让别的读 caps 的地方跟着错。多跑一次失败调用很便宜。
    }
    throw spiderErrorOf(action, error);
  }
  const out = model.fromBridge(payload, cap);
  if (action === 'episode') return episodeShapeOf(out);
  return out;
}

/** 这条错误是不是「源没写这个函数」。宿主页的原文是 `spider 没有实现 xxx`。 */
function isMissingCapability(error) {
  const raw = String((error && error.message) || error || '');
  return /没有实现\s*[A-Za-z0-9_-]+/.test(raw);
}

// spider 内部报的一类错需要翻译一下再交给用户。
//
// 「spider 没有实现 tags」是**源没写这个能力**，不是这个源的形态不兼容 ——
// 前者该说的是「换/升级这个源」，后者说的是「这个源根本干不了这件事」。
// 宿主页只报方法名，这里补上动作的中文名。认不出来就原样抛，不改写别人的错误。
const SPIDER_ACTION_TITLE = {
  home: '首页', category: '分类', search: '搜索', detail: '详情', episode: '播放',
  play: '播放', tags: '全部类型', 'tag-list': '类型列表',
  recommend: '精选推荐', newest: '最新'
};

function spiderErrorOf(action, error) {
  const raw = String((error && error.message) || error || '');
  const hit = /没有实现\s*([A-Za-z0-9_-]+)/.exec(raw);
  if (!hit) return error instanceof Error ? error : new Error(raw);
  const title = SPIDER_ACTION_TITLE[action] || action;
  return new Error('这个源没有实现「' + title + '」（spider 里没有 ' + hit[1] + ' 函数）');
}

/**
 * play 的三种结局，翻成内置播放器能用的样子。
 *
 * 播放器只认「一个地址」，所以「地址为空」这件事必须在这儿翻译成人话，
 * 否则两种情况会糊成同一条报错：
 *
 *   ready    → 直链。agedm-wasm: 标记照旧留在 lines 里（老路径还认它）。
 *   declined → 源说了播不了（agedm 的 age_ 密文）。url 空是它刻意的，但 alts 里的
 *              `agedm-wasm:` 标记必须递上去 —— app.js 的 hgNativeResolve 那段
 *              wasm 解析器就靠它工作，提前判失败等于把本来能播的那批也关掉。
 *   empty    → 源什么都没给。这时候才抛，而且文案和 declined 分开写。
 */
function episodeShapeOf(out) {
  const lines = asArray(out.lines);
  const state = String(out.state || '');
  if (state === 'empty') {
    throw new Error('这个源没有给出这一集的播放地址（既没有直链，也没说明原因）');
  }
  const marker = lines.map(one => String(one.url || ''))
    .find(url => url.indexOf('agedm-wasm:') === 0) || '';
  // declined 时 url 是空串，把密文标记当成「交给 wasm 解析器的线索」，不是地址。
  const source = String(out.url || '') || marker;
  return {
    source,
    url: source,
    lines,
    state,
    reason: String(out.reason || ''),
    note: String(out.note || '')
  };
}

/**
 * 源没实现某个内容能力时的退路。
 *
 * ── 为什么需要 ─────────────────────────────────────────────────────
 *
 * 「全部类型」和「类型列表」这两个动作，在绝大多数站上**不是新东西**：一个站的
 * 分类本来就写在它的配置里（`web.categories` + `ui.categoryIds/categoryLabels`），
 * 点一个分类就是 `category`。只有 老源 那种「导航栏就是标签栏、还要按站点原文
 * 取名」的站才值得单独实现 tags。
 *
 * 没有这层退路时的表现是最糟的那种：源完全正常，用户在首页点一个类型，页面直接
 * 「加载失败 — 这个源没有实现「类型列表」（spider 里没有 tagList 函数）」。
 * 一句技术报错，还把内部函数名漏给了用户。
 *
 * ── 两条退路 ─────────────────────────────────────────────────────
 *
 *   tag-list → category：slug 就是 cateId，模型的 paged() 形状完全一致。
 *   tags     → 用配置里的分类现拼：`ui.categoryIds` 配 `categoryLabels` 取名字，
 *              没有就退回 `web.categories`（cateId → 显示名）。
 *
 * 源自己实现了就优先用源的（老源 的标签是站点原文，配置里那份是旧名字）。
 */
function siteTagFallback() {
  const ui = siteUi();
  const ids = ui.categoryIds || {};
  const labels = ui.categoryLabels || {};
  const titles = ui.categoryTitles || {};
  const out = [];
  const seen = new Set();
  const push = (slug, name) => {
    const key = String(slug == null ? '' : slug).trim();
    if (!key || seen.has(key)) return;
    seen.add(key);
    out.push({ name: String(name || key), slug: key, group: 'category' });
  };

  Object.keys(ids).forEach((tab) => {
    const list = asArray(ids[tab]);
    const names = asArray(labels[tab]);
    list.forEach((slug, i) => {
      // 一个 tab 对一个分类时，tab 的标题就是最好的名字（yhdm 的「日本」→「日本动漫」）；
      // 一个 tab 对多个时只能用 categoryLabels 的同位名字。
      const fallbackName = names[i] || (list.length === 1 ? titles[tab] : '') || '';
      push(slug, fallbackName);
    });
  });

  if (!out.length) {
    const cats = (window.HG_SITE && window.HG_SITE.categories) || {};
    Object.keys(cats).forEach((slug) => {
      const label = cats[slug];
      push(slug, label && label !== slug ? label : slug);
    });
  }
  return { items: out };
}

function contentFallback(action, caps, args) {
  if (action === 'tag-list' && caps.indexOf('category') >= 0) {
    // 标签就是分类。slug 原样交给 category，模型的 paged() 形状一模一样。
    return spiderApi('category', { id: (args && (args.slug || args.id)) || '', page: (args && args.page) || 1 });
  }
  if (action === 'tags') {
    const items = siteTagFallback().items;
    if (items.length) return Promise.resolve({ items });
  }
  return null;
}

// timeoutMs 只对接桥兜底的那两条路（account / library / 下载更新这类非内容动作），
// spider 与 REST 路径有各自的预算，不透传。
function api(action, args = {}, timeoutMs) {
  const run = async () => {
    // 源声明了 spider 就整条内容通路都走它，不再碰 /api 路径。
    // 这里的分支是**穷举**的：能接的进 spiderApi，接不住的内容动作直接抛，
    // 剩下的（account / library / theme / 热更…）不是内容动作，照旧走 Java 桥。
    // 所以「spider 源 + 内容动作」永远走不到下面 apiPathFor 那一行。
    if (spiderIdOf()) {
      const cap = spiderCapFor(action);
      if (cap) {
        // 先问源支持什么。**探测失败（空 caps）时不拦**，直接交给 spider 自己报错 ——
        // 拦错的代价是「源明明是好的，前端说它不支持」，那种 bug 比一句技术报错更难查。
        const info = await spiderMetaOf(spiderIdOf());
        if (info.caps.length && info.caps.indexOf(cap) < 0) {
          const fallback = contentFallback(action, info.caps, args);
          if (fallback) return fallback;
          const title = SPIDER_ACTION_TITLE[action] || action;
          throw new Error('这个源没有实现「' + title + '」（spider 里没有 ' + cap + ' 函数）');
        }
        return spiderApi(action, args);
      }
      if (CONTENT_ACTIONS.has(action)) {
        throw new Error('这个源没有可用的 ' + action + ' 接口路径：检查 web.paths.' + action + '，或这次请求的参数是否齐全');
      }
      return bridgeCall(action, args, timeoutMs);
    }
    // REST 接口统一走 Java api-json 桥，避免 WebView 自动附带 Origin 请求头。
    if (shape.apiPathFor(action, args)) {
      try { return await callApi(action, args); }
      catch (error) {
        console.log('[api] ' + action + ' 接口请求失败：' + (error.message || error));
        if (typeof noticeApiFailure === 'function') noticeApiFailure(action + ': ' + (error.message || error));
        // 绝不回退到旧 HTML 抓取：它仍可能访问被污染的 huangguoai.com，
        // 会把 2 秒级接口故障拖成 40 秒等待。让对应页面立即进入错误态。
        throw error;
      }
    }
    if (CONTENT_ACTIONS.has(action)) {
      // apiPathFor 返回 null 有两种原因：源没声明这条路径，或者参数不全
      // （分类 slug 查不到 cate_id、搜索没关键词…）。两种都得让人看懂该改哪儿。
      throw new Error('这个源没有可用的 ' + action + ' 接口路径：检查 web.paths.' + action + '，或这次请求的参数是否齐全');
    }
    return bridgeCall(action, args, timeoutMs);
  };
  return contentReady ? contentReady.then(run) : run();
}

const scroll = document.getElementById('scroll');
const content = document.getElementById('content');
// 封面模块（cover.js）。api 是函数声明会被提升，所以这里放在 content/scroll
// 之后就能拿到三样依赖，不用往后挪。track 给灰块上报用（cover_placeholder）。
const cover = window.HGCover.create({ api, content, scroll, track });
// 站点接口的形状（adapters.js）：动作名 → REST 路径、响应 JSON → 内部结构。
// 纯逻辑，不碰 DOM 也不发请求，所以只需要一个封面地址归一函数。
const shape = window.HGShape.create({ resolveCoverUrl: cover.resolveUrl });
const avatarButton = document.getElementById('account-avatar');
const avatarImage = document.getElementById('account-avatar-image');
const authOverlay = document.getElementById('auth-overlay');
const compact = document.getElementById('compact-category');
const toastHost = document.getElementById('toast-host');
const searchInput = document.getElementById('search-input');
const headerSettings = document.getElementById('header-settings');
const confirmOverlay = document.getElementById('confirm-overlay');
const unlockOverlay = document.getElementById('unlock-overlay');
// 界面常量全部来自 site-default.json 的 web.ui 块。
//
// 这里**不在模块加载时捕获**，而是每次实时读 window.HG_SITE —— 顺序上的原因：
// site-web.js 是打包时从内置模板生成的，而用户可以在运行时换源，换完之后
// paths/categories/fields 可能都不一样。启动时 setup.js 会调 __source:web
// 把当前生效的 web 块写回 window.HG_SITE，实时读才看得到新值。
// 捕获成 const 的话，那次写入就白写了。
function siteUi() {
  return (window.HG_SITE && window.HG_SITE.ui) || {};
}

// ── 底部导航的中间几格 ──────────────────────────────────────────────
//
// 内置那套是黄果的「剧场 / AI」。换一个源这两个名字就没意义了 —— 苹果 CMS 的分法是
// 「电影/连续剧/综艺/动漫」，源里根本没有「AI」这种东西。两个入口指向同一份数据、
// 名字还跟内容对不上，就是这么来的。
//
// 所以这排按钮也归配置：web.ui.tabs = [{key, label, icon}]。
//   key   —— data-nav 的值，同时是 categoryIds / categoryLabels / categoryTitles
//            / categoryLeads 的键。定下来就别改，换名字等于丢分类。
//   label —— 按钮上的字
//   icon  —— dockIcons 里的名字，认不出来退回 grid
//
// 数量由配置决定，1 到 4 个都行（首页和「我的」不占这个额度）。dock 的列数是按
// 实际长度写进 style 的 —— CSS 里那个 repeat(4,1fr) 是给默认那套写死的，直接复用
// 会让 3 个按钮挤在左半边。
const dockIcons = {
  home: '<path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1z"/>',
  film: '<rect x="3" y="5" width="18" height="15" rx="2"/><path d="M3 10h18M8 5l3 5m3-5 3 5"/>',
  sparkle: '<path d="m12 2 1.8 6.2L20 10l-6.2 1.8L12 18l-1.8-6.2L4 10l6.2-1.8zM19 17l.6 1.4L21 19l-1.4.6L19 21l-.6-1.4L17 19l1.4-.6z"/>',
  user: '<circle cx="12" cy="8" r="3.5"/><path d="M4.5 21c.5-4.3 3-6.5 7.5-6.5s7 2.2 7.5 6.5"/>',
  grid: '<rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/>',
  fire: '<path d="M12 3c1.2 3.2 4 4.4 4 7.4a4 4 0 0 1-8 .2c0-3.4 3-4.2 4-7.6z"/><path d="M8.6 13.8A5.6 5.6 0 0 0 12 21a5.6 5.6 0 0 0 4.6-7.8"/>',
  tv: '<rect x="3" y="6" width="18" height="12" rx="2"/><path d="m9 3 3 3 3-3"/>',
  star: '<path d="m12 3 2.6 5.9 6.4.6-4.8 4.3 1.4 6.3L12 16.9 6.4 20l1.4-6.3L3 9.5l6.4-.6z"/>'
};

const DEFAULT_TABS = [
  { key: 'adult', label: '剧场', icon: 'film' },
  { key: 'ai', label: 'AI', icon: 'sparkle' }
];

const dockIcon = name => {
  const body = dockIcons[name] || dockIcons.grid;
  return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${body}</svg>`;
};

function siteTabs() {
  const configured = siteUi().tabs;
  if (!Array.isArray(configured) || !configured.length) return DEFAULT_TABS;
  const seen = new Set();
  const tabs = [];
  for (const tab of configured) {
    if (!tab || typeof tab.key !== 'string' || !tab.key) continue;
    if (seen.has(tab.key)) continue;          // 同一个 key 两次 = 两个入口同一份数据，就是现在的毛病
    seen.add(tab.key);
    tabs.push({
      key: tab.key,
      label: typeof tab.label === 'string' && tab.label ? tab.label : tab.key,
      icon: typeof tab.icon === 'string' && dockIcons[tab.icon] ? tab.icon : 'grid'
    });
    if (tabs.length === 5) break;             // 加首页和「我的」是 7 格，360dp 的机器上
                                              // 每格 ~52dp —— 再多 label 就要被压成两个字
  }
  return tabs.length ? tabs : DEFAULT_TABS;
}

const dockButton = (key, label, iconName) =>
  `<button type="button" data-nav="${esc(key)}"><span class="nav-icon">${dockIcon(iconName)}</span><span>${esc(label)}</span></button>`;

// 整排重画。必须在 startHome() 之前调 —— 配置是闸门那一步（HGSetup.ensure →
// applySiteConfig）写进 HG_SITE 的，所以第一帧拿到的就已经是最终的那份。
function renderDock() {
  const dock = document.getElementById('dock');
  if (!dock) return;
  const tabs = siteTabs();
  dock.style.gridTemplateColumns = `repeat(${tabs.length + 2}, 1fr)`;
  dock.innerHTML = dockButton('home', '首页', 'home')
    + tabs.map(tab => dockButton(tab.key, tab.label, tab.icon)).join('')
    + dockButton('library', '我的', 'user');
  const active = state.tab || 'home';
  dock.querySelectorAll('[data-nav]').forEach(button => button.classList.toggle('active', button.dataset.nav === active));
}


// 分类 tab 的显示名。默认那套是黄果专用的（成人短剧/成人漫剧…），而来源换成
// 苹果CMS 之后分类是「电影/电视剧/综艺」—— 标签必须跟着源走，所以配置里能覆盖
// （web.ui.categoryLabels），覆盖不了才退回默认。
function categoryTabs() {
  const configured = (siteUi().categoryLabels || {})[state.tab];
  if (configured && configured.length) return configured;
  // 没有配置就用 tab 名当一个「分区」。空数组会让「XX作品」变成「作品」，
  // 看起来像少了个字。
  const tab = (siteUi().tabs || []).find(one => one.key === state.tab);
  return [String((tab && tab.label) || state.tab || '全部')];
}

// 分类页标题，同样可被配置覆盖（web.ui.categoryTitles）。
function categoryTitle() {
  const configured = (siteUi().categoryTitles || {})[state.tab];
  // 只认字符串。配错了（比如塞了数组）就退回 tab 名 —— 不然插进 innerHTML 会变成
  // 「电影片,连续剧,综艺片,…」几十个名字串成一行的巨大标题。
  if (typeof configured === 'string' && configured) return configured;
  // 没有配置就用 tab 自己的名字。以前这里有一套默认（短剧 / 漫剧 / 成人剧场 /
  // AI专区），那是黄果源的分类词汇，对别的源是错的。
  const tab = (siteUi().tabs || []).find(one => one.key === state.tab);
  return String((tab && tab.label) || state.tab || '分类');
}

// 分类页副标题：**只用配置**，没配就不显示。
//
// 这里原来有一套默认文案（「短剧与漫剧，走进不同的人生。」「换脸与魔改，探索影像
// 新可能。」），是黄果源自己的宣传语。套在别的源上就是纯噪音 —— 切到别的源的
// 3DCG 分类，标题下面写着「探索热门漫剧。」，跟页面内容半点关系没有。
//
// 「没配就不显示」比「猜一句通用文案」好：一句对不上内容的副标题，比没有副标题
// 更让人困惑。副标题本来就是源自己的语气，写在源的 ui.categoryLeads 里。
function categoryLead() {
  const configured = (siteUi().categoryLeads || {})[state.tab];
  return typeof configured === 'string' ? configured : '';
}
const state = {
  route: 'home', tab: 'home', sub: { adult: 0, ai: 0 }, home: null,
  category: null, categoryItems: [], detail: null, detailLoading: false, sourceItem: null,
  search: null, searchPending: false, searchError: '', query: '', filter: 'all', banner: 0, categoryBanner: 0,
  listing: null, tags: null, tagsReturn: null, listingReturn: null, settingsReturn: null,
  libraryTab: 'history', libraryQuery: '', accountReturn: null, account: { loggedIn: false },
  sourceGroup: '', sourceQueue: [], previous: null, compactHeld: false,
  // 正在改备注的那个源的地址（内容源页面用）。空串 = 没有在编辑。
  sourceNote: '',
  // 源市场 {loading, items, error}
  market: null, marketReturn: null,
  // 内置播放器能力 {native, abi}；null = 还没问过
  nativeInfo: null,
  // 内置代理核心的状态 {mode, running, port, host, node, error, effective, ...}；
  // 由 __proxy:status 合成，前端只读不算 —— 见 renderProxySettings 上面那段。
  proxyCore: null
};
// 子分类下标。state.sub 只预置了内置那套的两个 key，而换源之后 state.tab 是配置
// 里给的任意 key（苹果CMS 那边是 "1"/"2"/"3"…），取不到就得回 0 —— 不然
// categoryId() 会算出 undefined，请求落到 Java 桥撞上已抛异常的桩。
const subIndex = tab => state.sub[tab] || 0;
// 冷启动固定进黄果源：先解锁（密码内置），再确认年龄门槛，然后锁定 contentSource。
// 这里直接走桥、不经过 contentReady —— 否则 contentReady 会等 api()，而 api() 又在等 contentReady，死锁。
function bridgeDirect(action, args = {}) {
  return new Promise((resolve, reject) => {
    const id = bridgeInstance + ':' + (++requestNumber);
    // 这条路曾经没有超时：Java 侧回包一丢，contentReady 永不 settle，排它后面的
    // 启动链（ensure → startHome，以及所有经 api() 的调用）全部挂死。超时就 reject，
    // 让调用方的 catch 落到各自的兜底值（content-status 是 unlocked 的假状态）。
    const timer = setTimeout(() => {
      if (!callbacks.has(id)) return;
      callbacks.delete(id);
      reject(new Error('请求超时（' + action + '）'));
    }, BRIDGE_TIMEOUT_MS);
    const finish = fn => value => { clearTimeout(timer); fn(value); };
    callbacks.set(id, { resolve: finish(resolve), reject: finish(reject) });
    try { AndroidHost.request(id, action, JSON.stringify({ ...args, source: 'hongguo' })); }
    catch (error) { clearTimeout(timer); callbacks.delete(id); reject(error); }
  });
}
contentReady = (async () => {
  // 热更新 trial 的确认兜底：只要 JS 跑到这里，说明这一版前端能正常启动。
  // 正常路径是 renderHome 里那一枪；但停在首启设置页的机器永远到不了 renderHome，
  // 没有这个兜底，每个热更新都会被判失败、下次启动回滚并拉黑。
  let status;
  try { status = await bridgeDirect('content-status'); }
  catch (error) { status = { source: 'hongguo', unlocked: true }; }

  // ── 2026-10-19：不再自动进内置那个「私密库」 ─────────────────────
  //
  // 这一段原来是「冷启动固定进黄果源」：先 enter-private（密码 ssgnb 写死在
  // 前端），再 confirm-private-age，最后把 contentSource 锁成 'huangguo'。
  //
  // 问题是「黄果源」已经没有内容能力了 —— site-default.json 里 origin 是空串，
  // 没有 paths、没有 api。它剩下的是：一套解锁流程 + 一份界面默认值
  // （tabs / categoryIds / popularTags），以及前端几十处 privateMode 分支。
  //
  // 而 contentSource 被锁成 huangguo 之后，**用户装的任何 spider 源也都算
  // 「私密模式」**：分类页套黄果的模板（「换脸与魔改，探索影像新可能」+ 一个
  // 只有一个按钮的分区选择器），首页标题写「热门漫剧」，标签页按黄果的分组渲染。
  //
  // 现在：内容源只有一种 —— 用户自己装的那个。内置态留在公开态（hongguo），
  // 不解锁、不确认年龄、不覆盖 contentSource。要进「私密库」的那套 UI 等真的
  // 有内容源可迁的时候再说。
  contentSource = 'hongguo';
  document.title = 'AIIvideo';
  return status;
})();

let routeToken = 0;
let toastTimer;
let listingObserver;
let categoryObserver;
const screenSnapshots = {};
let predictivePreview;
let predictiveTimer;
let predictiveSourceNode;
let predictiveFrame;
let pendingPredictiveProgress = 0;
let detailCard;
let bannerChanging = false;
let bannerTimer;
let lastDockTap = { tab: '', at: 0, navigated: false };
let appVersion = '';

const esc = value => String(value ?? '').replace(/[&<>"']/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character]);
const asArray = value => Array.isArray(value) ? value : [];
const icon = name => {
  const paths = {
    play: '<path d="m8 5 11 7-11 7z"/>',
    arrow: '<path d="m9 5 7 7-7 7"/>',
    back: '<path d="m15 5-7 7 7 7"/>',
    // 设置页每一行左边那个「这是哪一类」的符号。全部 1.9 描边、圆角端点，
    // 和 dock 那排同一种手感 —— 混用粗细会让分组看起来像两个人画的。
    account: '<circle cx="12" cy="8" r="3.6"/><path d="M4.8 20.4c.6-4 3.2-6 7.2-6s6.6 2 7.2 6"/>',
    shield: '<path d="M12 3.6 19 6.3v5.3c0 4.2-2.9 7.3-7 8.8-4.1-1.5-7-4.6-7-8.8V6.3z"/>',
    source: '<path d="M10.5 13.5a4 4 0 0 0 5.7 0l2.6-2.6a4 4 0 0 0-5.7-5.7l-1 1"/><path d="M13.5 10.5a4 4 0 0 0-5.7 0l-2.6 2.6a4 4 0 0 0 5.7 5.7l1-1"/>',
    proxy: '<path d="M12 3.2 5 6v6c0 4.2 2.9 7.4 7 8.8 4.1-1.4 7-4.6 7-8.8V6z"/><path d="m9.2 12 2 2 3.6-3.8"/>',
    player: '<circle cx="12" cy="12" r="8.6"/><path d="m10.2 8.6 5.4 3.4-5.4 3.4z" fill="currentColor" stroke="none"/>',
    theme: '<circle cx="12" cy="12" r="8.4"/><path d="M12 3.6v16.8" /><path d="M12 3.6a8.4 8.4 0 0 1 0 16.8z" fill="currentColor" stroke="none" opacity=".9"/>',
    market: '<path d="M4.4 9.6h15.2l-1 9.2a1.8 1.8 0 0 1-1.8 1.6H7.2a1.8 1.8 0 0 1-1.8-1.6z"/><path d="M8.6 9.6V7.4a3.4 3.4 0 0 1 6.8 0v2.2"/>',
    storage: '<path d="M5 7.4h14M6.4 7.4 7.6 19a1.8 1.8 0 0 0 1.8 1.6h5.2A1.8 1.8 0 0 0 16.4 19l1.2-11.6"/><path d="M9.6 11v6M14.4 11v6"/>',
    update: '<path d="M12 3.8v10.4"/><path d="m8 10.6 4 4 4-4"/><path d="M4.6 17.4v1.6a1.6 1.6 0 0 0 1.6 1.6h11.6a1.6 1.6 0 0 0 1.6-1.6v-1.6"/>',
    check: '<path d="m5.4 12.6 4.2 4.2 9-9.4"/>',
    chevron: '<path d="m9 5 7 7-7 7"/>',
    library: '<rect x="3.6" y="5" width="16.8" height="14" rx="2.4"/><path d="M8.6 5v14"/>',
    reset: '<path d="M4.8 12a7.2 7.2 0 1 0 2.2-5.2"/><path d="M4.6 4.6v4h4"/>'
  };
  return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[name] || ''}</svg>`;
};

// ── 按下去要有东西发生 ────────────────────────────────────────────────
//
// 三件事必须同时发生，缺一件就会「感觉没点上」：
//   ① 手指落下的那一帧就震一下（不是等 click、更不是等请求回来）
//   ② 被按的那个面上漾开一圈涟漪，起点就是手指的位置
//   ③ 松开时那一下回弹（CSS :active，见 app.css 的 .press）
//
// 震动走 AndroidHost.haptic 而不是桥指令：桥是异步回包，等一个来回再震，
// 手指早抬起来了。原生侧有 24ms 去重，一帧里叠两个事件也只震一次。
function haptic(kind = 'light') {
  try { window.AndroidHost?.haptic?.(kind); } catch (error) {}
}

// 涟漪。只在 pointerdown 时插一个 span，animationend 自己删 —— 不挂全局状态，
// 连续快速点按不会互相干扰。
const RIPPLE_HOSTS = [
  // 设置页 v2
  'button.settings-row', '.settings-mini', '.settings-cta', '.settings-quiet',
  '.settings-danger-button', '.settings-seg-item', '.settings-row button',
  // 设置页的二级页（内容源 / 代理 / 播放器）
  '.player-options button', '.source-pick', '.source-note', '.source-drop',
  '.speed-item', '.source-refetch', '.settings-account button',
  '.settings-storage button', '.listing-back',
  // 全站
  '#dock button', '.segmented button', '.more', '.filter-row button',
  '.theme-choice-item'
].join(',');

function spawnRipple(target, event) {
  if (!target || target.disabled) return;
  if (reducedMotionQuery && reducedMotionQuery.matches) return;
  const box = target.getBoundingClientRect();
  if (!box.width || !box.height) return;
  // 涟漪半径取到最远的那个角，圆才盖得住整个按钮。
  const x = (event && event.clientX ? event.clientX : box.left + box.width / 2) - box.left;
  const y = (event && event.clientY ? event.clientY : box.top + box.height / 2) - box.top;
  const radius = Math.hypot(Math.max(x, box.width - x), Math.max(y, box.height - y));
  const ripple = document.createElement('span');
  ripple.className = 'press-ripple';
  // 定位写成内联的。这几行原本只在样式表里，而样式表因为一个语法错误把整段
  // 规则丢掉了 —— 那期间涟漪退化成一个静态 span，尺寸是 (2×半径) 那么大，
  // 直接参与布局。内联之后不管样式表解析成什么样，它都只是浮在上面的一个圆。
  ripple.style.cssText = `position:absolute;pointer-events:none;left:${x}px;top:${y}px;width:${radius * 2}px;height:${radius * 2}px`;
  target.classList.add('press-host');
  target.appendChild(ripple);
  ripple.addEventListener('animationend', () => ripple.remove(), { once: true });
  // 兜底：动画被系统跳过（省电模式 / 后台标签）时也要清掉，别越堆越多。
  window.setTimeout(() => ripple.remove(), 700);
}

document.addEventListener('pointerdown', event => {
  const target = event.target.closest?.(RIPPLE_HOSTS);
  if (!target || target.disabled) return;
  haptic(target.closest('#dock') ? 'medium' : 'light');
  spawnRipple(target, event);
}, { capture: true, passive: true });

function toast(message, info = false) {
  // 每条提示都落到 logcat（MainActivity 的 onConsoleMessage 会转发），
  // 真机上"一闪而过的红条"只能靠这个抓 —— 事后看 DOM 分不清是这次弹的还是上次留的。
  console.log('[toast] ' + message);
  // 提示条一闪就没了，眼睛可能正看着别处。补一记触感，让结果不依赖「有没有看到」。
  haptic(info ? 'error' : 'success');
  clearTimeout(toastTimer);
  toastHost.innerHTML = `<div class="toast ${info ? 'info' : ''}"><i></i><span>${esc(message)}</span></div>`;
  toastHost.classList.add('visible');
  toastTimer = setTimeout(() => toastHost.classList.remove('visible'), 3000);
}

let finishConfirmation;
function closeConfirmation(accepted) {
  if (confirmOverlay.hidden) return;
  confirmOverlay.hidden = true;
  updateBackEnabled();
  finishConfirmation?.(accepted);
  finishConfirmation = null;
}
function askConfirmation(title, message, action = '清空') {
  document.getElementById('confirm-title').textContent = title;
  document.getElementById('confirm-message').textContent = message;
  document.getElementById('confirm-accept').textContent = action;
  confirmOverlay.hidden = false;
  updateBackEnabled();
  document.getElementById('confirm-cancel').focus();
  return new Promise(resolve => { finishConfirmation = resolve; });
}
document.getElementById('confirm-cancel').addEventListener('click', () => closeConfirmation(false));
document.getElementById('confirm-accept').addEventListener('click', () => closeConfirmation(true));
confirmOverlay.addEventListener('click', event => { if (event.target === confirmOverlay) closeConfirmation(false); });

function setAccount(profile) {
  const next = profile || { loggedIn: false };
  if (JSON.stringify(state.account) === JSON.stringify(next)) return;
  const savedScroll = scroll.scrollTop;
  const userChanged = state.account.userId !== next.userId;
  state.account = next;
  if (userChanged) { state.library = null; if (state.route === 'home') { updateContinueWatching(); refreshHomeHistory(); } }
  avatarImage.hidden = !profile?.avatar;
  avatarButton.querySelector('.account-avatar-default').hidden = Boolean(profile?.avatar);
  if (profile?.avatar) avatarImage.src = profile.avatar;
  else avatarImage.removeAttribute('src');
  avatarButton.classList.toggle('is-signed-in', Boolean(profile?.loggedIn));
  avatarButton.setAttribute('aria-label', profile?.loggedIn ? `打开${profile.username}的账号管理` : '登录账号');
  if (state.route === 'library') renderLibrary();
  if (state.route === 'settings') renderSettingsKeepScroll();
  if (state.route === 'account-management' && !content.querySelector('#password-form')?.contains(document.activeElement)) renderAccountManagement();
  if (state.route === 'proxy-settings' && !content.querySelector('#proxy-form')?.contains(document.activeElement)) renderProxySettings();
  if (state.route === 'player-settings') renderPlayerSettings();
  if (state.route === 'source-settings' && !content.querySelector('#source-form')?.contains(document.activeElement)) renderSourceSettings();
  scroll.scrollTop = savedScroll;
}
api('account').then(setAccount).catch(() => {});
// 上一次恢复出厂设置如果有件没干完的事（登出没等到网络），在这儿把它做完。
finishResetLogout();
window.hgWindowInsets = height => {
  document.getElementById('app').style.setProperty('--status-bar-height', `${Math.max(0, Number(height) || 0)}px`);
};
api('window-insets').then(window.hgWindowInsets).catch(() => {});
api('screen-corners').then(corners => {
  const app = document.getElementById('app');
  for (const name of ['topLeft', 'topRight', 'bottomRight', 'bottomLeft']) {
    const radius = Number(corners?.[name]);
    if (radius > 0) app.style.setProperty(`--screen-corner-${name}`, `${radius}px`);
  }
}).catch(() => {});

let authMode = 'login';
let authPending = false;
function setAuthPending(pending, mode = authMode) {
  const submit = document.getElementById('auth-submit');
  submit.disabled = pending;
  submit.classList.toggle('is-loading', pending);
  submit.setAttribute('aria-busy', String(pending));
  submit.textContent = pending ? (mode === 'login' ? '正在登录…' : '正在注册…') : (mode === 'login' ? '登录' : '注册并登录');
  document.querySelectorAll('[data-auth-mode]').forEach(button => { button.disabled = pending; });
}
function showAuth(mode = 'login') {
  if (authPending) mode = authMode;
  authMode = mode;
  authOverlay.hidden = false;
  updateBackEnabled();
  document.getElementById('auth-title').textContent = mode === 'login' ? '登录应用账号' : '注册应用账号';
  setAuthPending(authPending, mode);
  document.getElementById('auth-password').autocomplete = mode === 'login' ? 'current-password' : 'new-password';
  document.querySelectorAll('[data-auth-mode]').forEach(button => button.classList.toggle('selected', button.dataset.authMode === mode));
  document.getElementById('auth-error').hidden = true;
}
function hideAuth() {
  authOverlay.hidden = true;
  updateBackEnabled();
  document.getElementById('auth-form').reset();
}
avatarButton.addEventListener('click', () => {
  if (state.account.loggedIn) goAccountManagement();
  else showAuth();
});
headerSettings.addEventListener('click', goSettings);
document.getElementById('auth-close').addEventListener('click', hideAuth);
document.querySelectorAll('[data-auth-mode]').forEach(button => button.addEventListener('click', () => showAuth(button.dataset.authMode)));
document.getElementById('auth-form').addEventListener('submit', async event => {
  event.preventDefault();
  if (authPending) return;
  const mode = authMode;
  const username = document.getElementById('auth-username').value.trim();
  const password = document.getElementById('auth-password').value;
  const error = document.getElementById('auth-error');
  authPending = true;
  setAuthPending(true, mode);
  error.hidden = true;
  try {
    const profile = await api(mode, { username, password });
    hideAuth();
    setAccount(profile);
    if (state.route === 'library') goLibrary(state.libraryTab);
    toast(mode === 'login' ? '登录成功，片单正在同步' : '注册成功，片单正在同步');
  } catch (failure) {
    if (authOverlay.hidden) toast(failure.message || '请稍后重试', true);
    else { error.textContent = failure.message || '请稍后重试'; error.hidden = false; }
  } finally { authPending = false; setAuthPending(false, mode); }
});
window.hgAccountRefreshed = profile => {
  if (profile?.userId && state.account.userId === profile.userId) setAccount(profile);
};
window.hgAvatarUpdated = profile => { setAccount(profile); toast('头像已更新'); };
window.hgAvatarError = message => toast(message || '头像保存失败', true);
window.hgCloudSynced = () => {
  if (state.route === 'home') refreshHomeHistory();
  else if (state.route === 'library') {
    const token = routeToken;
    api('library').then(data => { if (token !== routeToken) return; state.library = data; renderLibrary(); }).catch(() => {});
  }
};

// ── 应用更新面板已移除（2026-10-16）──────────────────────────────────────
// 原来这里是一整套「发现新版本 → 展示更新说明 → 下载并安装」的 UI：
// setAvailableUpdate / showUpdate / checkUpdate / hgUpdateProgress /
// hgUpdateInstallPermissionDenied / hgUpdateInstallError，外加下载按钮和
// 「稍后再说」的监听，以及 app.html 里的 #update-overlay 区块和 app.css 的
// 对应样式。全部删掉。
//
// 原因是它背后的通道已经死了：check-update → UpdateManager →
// github.com/MrSsg/HuangguoAPP-for-Andriod（已 404）。checkUpdate 又已经
// 没有任何调用点，所以这一整套连「永远不显示」都算不上，是纯粹的悬空代码。
// 设置页那个版本号按钮保留，它挂着 data-secret-entry，是独立功能。


// ── 主题 ──────────────────────────────────────────────────────────────
// 偏好存 localStorage，三档：system / light / dark。app.html 的头部脚本在
// CSS 之前已经按同一份偏好定过一次 data-theme（防冷启动闪白），这里负责
// 运行时切换、跟随系统深色，以及把明暗告诉原生（状态栏/导航栏图标）。
const THEME_KEY = 'hgpref:theme';
const THEME_MODES = ['system', 'light', 'dark'];
const themeMedia = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;
const reducedMotionQuery = window.matchMedia ? window.matchMedia('(prefers-reduced-motion: reduce)') : null;

function themeMode() {
  try {
    const saved = localStorage.getItem(THEME_KEY);
    if (THEME_MODES.includes(saved)) return saved;
  } catch (error) {}
  return 'system';
}
function systemDark() { return Boolean(themeMedia && themeMedia.matches); }
function applyTheme(mode = themeMode(), darkOverride) {
  const dark = mode === 'dark' || (mode === 'system' && (darkOverride === undefined ? systemDark() : Boolean(darkOverride)));
  const root = document.documentElement;
  root.dataset.theme = dark ? 'dark' : 'light';
  root.dataset.themePref = mode;
  root.style.colorScheme = dark ? 'dark' : 'light';
  const meta = document.getElementById('theme-color');
  if (meta) meta.setAttribute('content', dark ? '#171b1d' : '#f5f2e9');
  window.HG_THEME_DARK = dark;
}
function syncNativeTheme(mode = themeMode()) {
  // 状态栏/导航栏不归 WebView 管。原生侧拿同一个 mode 决定图标明暗；
  // 这条桥失败也只是图标颜色不跟手，页面主题不受影响。
  bridgeCall('theme', { mode }).catch(() => {});
}
function setThemeMode(mode) {
  if (!THEME_MODES.includes(mode)) return;
  try { localStorage.setItem(THEME_KEY, mode); } catch (error) {}
  const commit = () => { applyTheme(mode); syncNativeTheme(mode); };
  // View Transition 可用时做一次整屏交叉淡化，比让每个元素各自变色干净。
  if (document.startViewTransition && !(reducedMotionQuery && reducedMotionQuery.matches)) {
    try { document.startViewTransition(commit); } catch (error) { commit(); }
  } else commit();
  if (state.route === 'settings') {
    renderSettingsKeepScroll();
    // 焦点留在刚选中的那一格上，键盘/读屏用户连点三个不用重新找位置。
    requestAnimationFrame(() => content.querySelector(`[data-theme-mode="${mode}"]`)?.focus({ preventScroll: true }));
  }
  // 主题切换的反馈就是屏幕本身（整屏交叉淡化），不再补 toast ——
  // 弹一条「外观已切换为深色」去解释一件已经看见的事，纯属噪音。
  haptic('light');
}
// 系统深色变化时原生会调这个。用户明确选了浅/深就尊重用户的选择。
window.hgTheme = dark => {
  if (themeMode() === 'system') applyTheme('system', Boolean(dark));
  else applyTheme();
};
if (themeMedia) {
  const onSystemTheme = event => { if (themeMode() === 'system') applyTheme('system', event.matches); };
  if (themeMedia.addEventListener) themeMedia.addEventListener('change', onSystemTheme);
  else if (themeMedia.addListener) themeMedia.addListener(onSystemTheme);
}
applyTheme();
syncNativeTheme();
api('theme').then(window.hgTheme).catch(() => {});


// ── 列表校正：网格页在乱写，拿详情页盖一遍 ──────────────────────────────
//
// 2026-10-18：老源 的首页/分类页每张卡上有三个 id（href 里的 viewkey、
// 容器 id `playvthumb_<n>`、封面 `thumb/<n>.jpg`），三个一致才靠得住。实测
// 一页 40 张卡里 12 个 viewkey 重复出现，同一个链接挂三四个不同封面，点进去
// 是另一部 —— 站点故意给爬虫的迷魂阵（搜索页反而干净，两次渲染 0 变化）。
//
// 所以不指望网格页：先照它把图铺出来，再拿每条自己的详情页把标题/封面盖掉。
// 只补「这次新见到的 id」，同一批 id 一次请求；拉过的详情在 Java 侧进缓存，
// 第二次进同一页零成本。失败就保留原值 —— 校正失败最多是回到站点的原样，
// 不能让一张卡把整页拖住。
// id → 详情页真值。**按 id 存，不按「这次请求过没」记** —— 同一个 id 会同时出现在
// 首页和分类页的两个数组里（两个不同对象），只记「请求过」的话，第二个列表永远
// 补不上（真机踩到：首页补好了，切到高清 tab 又变回假封面）。
const reconciledFixes = new Map();
// 试过一次「源没有 reconcile 能力」就记住，别再对同一个源反复问。
// 不为这个加一套能力协商：一次失败的调用换来本会话的静默，够了。
const reconcileUnsupported = new Set();
// REST 那条路（下面 else 分支）拼 `/api/reconcile` 时用的是**纯路径**，要靠源的
// origin 才能变成地址。2026-10-16 起允许「origin 留空、paths 全写绝对地址」这种
// 配置形态（接口开在另一个域名的那类源），这种源拼不出来，OkHttp 当场抛
//   Expected URL scheme 'http' or 'https' but no scheme was found for /api/reconcile?ids=…
// 线上跨 2.0.21 / 2.0.23 / 2.0.26 三个版本都在报（56 条 / 5 台），而且每次进网格页
// 重报一遍 —— 因为它既不是「源没有实现 xxx」（进不了 reconcileUnsupported），
// 又是个确定性失败。这类源的卡片错位校正本来就不该走这条路，直接关掉。
let restReconcileBroken = false;

// ── 校正结果落盘 ────────────────────────────────────────────────
//
// 校正很贵：真机上单条详情要 5-6 秒（走代理），一页 24 条哪怕并发 8 也要近 20 秒。
// 只在内存里记，冷启动一次就白干一次；用户每次开 App 都得等那 20 秒。
//
// 存 localStorage，按源分区（不同源的 id 可能撞车，老源 是纯数字 viewkey）。
// 加时间戳，超过 7 天整份丢掉 —— 同一个 viewkey 的标题基本不变，但站点的片子
// 会下架、会被投稿者改标题，留太久就会拿一份过期真值盖掉新的正确值。
const RECONCILE_TTL_MS = 7 * 24 * 3600 * 1000;
const RECONCILE_LIMIT = 400;
let reconcileStoreLoaded = false;

function reconcileStoreKey() {
  return 'hgreconcile:' + (spiderIdOf() || 'default');
}

function loadReconcileStore() {
  if (reconcileStoreLoaded) return;
  reconcileStoreLoaded = true;
  try {
    const raw = localStorage.getItem(reconcileStoreKey());
    if (!raw) return;
    const box = JSON.parse(raw);
    if (!box || !box.savedAt || Date.now() - box.savedAt > RECONCILE_TTL_MS) {
      localStorage.removeItem(reconcileStoreKey());
      return;
    }
    Object.keys(box.items || {}).forEach((id) => {
      const fix = box.items[id];
      if (fix && (fix.title || fix.cover)) reconciledFixes.set(id, fix);
    });
  } catch (error) {
    // 存储坏了就当成空 —— 校正失败最多是回到站点的原样，不影响主流程。
  }
}

function saveReconcileStore() {
  try {
    const entries = [...reconciledFixes.entries()].slice(-RECONCILE_LIMIT);
    const items = {};
    entries.forEach(([id, fix]) => { items[id] = fix; });
    localStorage.setItem(reconcileStoreKey(), JSON.stringify({ savedAt: Date.now(), items }));
  } catch (error) {
    // 超配额就放弃这次写入，内存里那份照样生效。
  }
}

function homeItems(data) {
  return [...asArray(data?.featured), ...asArray(data?.homepageRecommend), ...asArray(data?.homepageNewest),
          ...asArray(data?.recommend?.items), ...asArray(data?.newest?.items)];
}
// 把手上的真值套到这批 item 上，返回改了几个字段。
function applyFixes(items) {
  let touched = 0;
  asArray(items).forEach(item => {
    const fix = item && reconciledFixes.get(item.id);
    if (!fix) return;
    if (fix.title && fix.title !== item.title) { item.title = fix.title; touched += 1; }
    if (fix.cover && fix.cover !== item.cover) { item.cover = fix.cover; touched += 1; }
    if (fix.badge && !item.episodeLabel) { item.episodeLabel = fix.badge; touched += 1; }
  });
  return touched;
}
async function reconcileCards(items, onPatched) {
  // 网格页的标题/封面会错位，用详情页盖一遍。
  //
  // ── 2026-10-19 改：spider 源也要校正 ──────────────────────────────
  //
  // 这里原来有一句 `if (spiderIdOf()) return;`，理由写得很像回事：「spider 的
  // 卡片是它自己从详情页里取的 id，一上来就是真的」。**那句话是错的。**
  // 旧播放器的 parseCards 是从网格页抠 href + 标题 + <img src>，和 Java 时代
  // 一模一样，站点那套迷魂阵原样回来了：
  //
  //   列表卡  viewkey=1536834169  标题「调教07母狗…」  封面 thumb/1252773.jpg
  //   详情页  viewkey=1536834169  标题「怪兽酒馆…」    封面 thumb/1252788.jpg
  //   真身    viewkey=1514471335  标题「调教07母狗…」  封面 thumb/1252737.jpg
  //
  // 也就是标题和封面被挂到了第三个 viewkey 上，点进去就是「货不对版」。
  // 实测 5 条里错 2 条，不是偶发。
  //
  // 现在：源里实现了 reconcile 才问（能力集自报，见 spiderMetaOf）；一次调用、
  // 源内用 netAll 并发拉详情（Java 侧线程池，8 并发，24 条约 7 秒）。
  // 逐条走 bridge 的老路不能要 —— 一次 detail 调用的开头就要重捡一次游客标记，
  // 24 条等于 24 倍请求量。
  loadReconcileStore();
  const list = asArray(items).filter(item => item && item.id);
  if (!list.length) return;
  // 已经校正过的 id 立刻套一遍（不花请求，也不等网络）
  const touchedFirst = applyFixes(list);
  if (touchedFirst && typeof onPatched === 'function') onPatched();

  const missing = [...new Set(list.map(item => item.id))].filter(id => !reconciledFixes.has(id)).slice(0, 30);
  if (!missing.length) return;

  let fixed = [];
  if (spiderIdOf()) {
    const spiderId = spiderIdOf();
    // 源里实现了 reconcile 才问。**开关不再写在配置里**（`web.reconcile`）：
    // 那是把源的实现细节漏到了源配置的结构里，而配置是给人填参数用的。
    // 能力集里有没有这个函数，才是这件事本身。
    if (reconcileUnsupported.has(spiderId)) return;
    const info = await spiderMetaOf(spiderId);
    if (info.caps.length && info.caps.indexOf('reconcile') < 0) return;
    try {
      // 第三参同 spiderApi：前端等待上限与原生预算对齐到 90 秒（见那里的注释）。
      const payload = await bridgeCall('spider', {
        op: 'call', id: spiderId, cap: 'reconcile', args: { ids: missing }, timeoutMs: 90000
      }, 90000);
      fixed = asArray(payload && payload.items);
    } catch (error) {
      const why = String((error && error.message) || error);
      // **超时不能记成「源不支持」**：超时是网络或预算问题，下一次可能就通了，
      // 记住它等于这一会话再也不校正，用户会一直看到错卡。
      if (why.indexOf('没有实现') >= 0) reconcileUnsupported.add(spiderId);
      console.log('[reconcile] ' + spiderId + ' 本轮没校正：' + why);
      return;                       // 整批失败：什么都不记，下次进来还能重试
    }
  } else {
    // 源没有 origin（paths 全写绝对地址那种）就拼不出地址，见 restReconcileBroken。
    if (restReconcileBroken) return;
    let data = null;
    try {
      const reply = await bridgeCall('detail', { id: '/api/reconcile?ids=' + encodeURIComponent(missing.join(',')) });
      const result = typeof reply === 'string' ? JSON.parse(reply) : reply;
      data = (result && (result.data || result)) || null;
    } catch (error) {
      const why = String((error && error.message) || error);
      // 这个错误是配置形态决定的，不是网络抖动 —— 重试只会一遍遍刷同一条。
      if (why.indexOf('no scheme was found') >= 0 || why.indexOf('Expected URL scheme') >= 0) {
        restReconcileBroken = true;
      }
      return;                       // 整批失败：什么都不记（除非上面那条确定性的）
    }
    fixed = asArray(data && data.items);
  }
  if (!fixed.length) return;
  fixed.forEach(entry => { if (entry && entry.id) reconciledFixes.set(entry.id, entry); });
  saveReconcileStore();
  const touched = applyFixes(list);
  if ((touched || fixed.length) && typeof onPatched === 'function') onPatched();
}

function itemCard(item) {
  const id = esc(item.id);
  const title = esc(item.title || '影片');
  const info = esc(item.episodeLabel || asArray(item.tags).slice(0, 2).join(' · ') || '打开详情');
  return `<button class="film-card" type="button" data-open-id="${id}" aria-label="查看${title}">
    <span class="poster loading"><span class="poster-letter">${title.slice(0, 1)}</span><img data-cover="${esc(item.cover || '')}" alt=""><span class="poster-score" ${item.score ? '' : 'hidden'}>${esc(item.score || '')}</span></span>
    <span class="film-title">${title}</span><span class="film-info">${info}</span></button>`;
}

function section(title, items, kind = '') {
  if (!items.length) return '';
  return `<section class="film-section"><div class="section-heading"><h2>${esc(title)}</h2>${kind ? `<button class="more" data-more="${kind}" type="button">更多 ${icon('arrow')}</button>` : ''}</div><div class="film-row">${items.map(itemCard).join('')}</div></section>`;
}
function homeTagBar() {
  const ui = siteUi();
  // 首页「按类型浏览」那排。publicPopularTags 是给公开源用的，popularTags 是
  // 更早的一版字段；两份都空就只剩「全部类型」一个按钮（不是错误，只是空）。
  const choices = (ui.publicPopularTags && ui.publicPopularTags.length)
    ? ui.publicPopularTags : (ui.popularTags || []);
  return `<nav class="home-tags" aria-label="按类型浏览"><button type="button" class="all-tags-link" data-all-tags="1">全部类型 ${icon('arrow')}</button>
    <div class="home-tag-scroll">${choices.map(([slug, name]) => `<button type="button" class="home-tag" data-tag-slug="${esc(slug)}" data-tag-name="${esc(name)}">${esc(name)}</button>`).join('')}</div></nav>`;
}

function homeSignature(data) {
  return JSON.stringify([asArray(data.featured), asArray(data.homepageRecommend), asArray(data.homepageNewest), asArray(data.recommend?.items), asArray(data.newest?.items)]
    .map(list => list.map(item => [item.id, item.title, item.episodeLabel, item.score])));
}
function listSignature(data) {
  return JSON.stringify(asArray(data.items).map(item => [item.id, item.title, item.episodeLabel, item.score]));
}

function activeCampaigns() {
  // 热更新已切断：活动位原本由远端 theme.json 的 campaigns 数组驱动（r16 里
  // 是国庆活动，窗口 2026-10-01 ~ 10-08，早过期）。现在恒为空，首页轮播
  // 回落到正常内容位。保留函数是因为 homeSlides / campaignArtwork / goCampaign
  // 那一串调用链还在，删掉要连带改渲染分支，收益不值。
  return [];
}
function campaignArtwork(item) {
  return new URL(item.artwork, 'https://appassets.androidplatform.net/assets/').href;
}
function homeSlides() {
  const featured = asArray(state.home?.featured);
  const videos = featured.length ? featured : asArray(state.home?.recommend?.items).slice(0, 5);
  return [...activeCampaigns().map(item => ({ ...item, kind: 'campaign', cover: '' })), ...videos];
}
function campaignSlide(item, index, count) {
  return '<div class="hero-slide hero-campaign" data-index="' + index + '">'
    + '<img class="hero-image campaign-image" src="' + esc(campaignArtwork(item)) + '" alt="">'
    + '<button class="hero-cover-button" type="button" data-open-campaign="' + esc(item.id) + '" aria-label="查看国庆庆祝页"></button>'
    + '<div class="hero-copy"><h2>' + esc(item.title) + '</h2><p>' + esc(item.description || '').split('\n').join('<br>') + '</p>'
    + '<button class="hero-action" type="button" data-open-campaign="' + esc(item.id) + '">' + esc(item.button || '查看庆祝页') + '</button></div>'
    + '<span class="campaign-date">' + esc(item.dateLabel || '') + '</span>'
    + '<div class="hero-index"><b>' + String(index + 1).padStart(2, '0') + '</b> / ' + String(count).padStart(2, '0') + '</div></div>';
}
function goCampaign(item) {
  state.campaignReturn = captureScreen();
  ++routeToken;
  setRoute('campaign');
  state.campaign = item;
  content.innerHTML = '<article class="campaign-page"><button class="campaign-back" type="button" data-back>' + icon('back') + ' 返回首页</button>'
    + '<section class="campaign-poster"><img src="' + esc(campaignArtwork(item)) + '" alt="">'
    + '<div><h1>' + esc(item.title) + '</h1><p>' + esc(item.description || '').split('\n').join('<br>') + '</p><span>' + esc(item.dateLabel || '') + '</span></div></section>'
    + '<section class="campaign-message"><h2>共庆好时光</h2><p>' + esc(item.message || '') + '</p><button type="button" data-back>返回首页</button></section></article>';
  scroll.scrollTop = 0;
}
function isBannerRoute() { return state.route === 'home' || state.route === 'category'; }
function bannerSlides() { return state.route === 'category' ? asArray(state.category?.items).slice(0, 5) : homeSlides(); }
function bannerIndex() { return state.route === 'category' ? state.categoryBanner : state.banner; }
function restoreBannerIndex() {
  const index = Number(content.querySelector('.hero-slide:last-child')?.dataset.index || 0);
  if (state.route === 'category') state.categoryBanner = index;
  else if (state.route === 'home') state.banner = index;
}
function categorySlide(item, index, count) {
  return `<div class="hero-slide" data-index="${index}"><img class="hero-image" data-cover="${esc(item.cover || '')}" alt=""><button class="hero-cover-button" type="button" data-open-id="${esc(item.id)}" aria-label="查看${esc(item.title)}详情"></button><div class="hero-shade"></div><div class="category-spotlight-copy"><h2>${esc(item.title)}</h2><button type="button" data-open-id="${esc(item.id)}">查看作品 ${icon('arrow')}</button></div><div class="hero-index"><b>${String(index + 1).padStart(2, '0')}</b> / ${String(count).padStart(2, '0')}</div></div>`;
}
function stopBannerAuto() {
  clearTimeout(bannerTimer);
  bannerTimer = null;
}
function scheduleBannerAuto() {
  stopBannerAuto();
  if (!isBannerRoute() || document.hidden || bannerSlides().length < 2 || !content.querySelector('.hero')) return;
  bannerTimer = setTimeout(() => {
    bannerTimer = null;
    if (!isBannerRoute() || document.hidden) return;
    const count = bannerSlides().length;
    if (count > 1) changeBanner((bannerIndex() + 1) % count, 1, true);
  }, 3000);
}
document.addEventListener('visibilitychange', () => {
  if (document.hidden) stopBannerAuto();
  else scheduleBannerAuto();
});
function heroSlide(item, index, count) {
  if (item.kind === 'campaign') return campaignSlide(item, index, count);
  return `<div class="hero-slide" data-index="${index}"><img class="hero-image" data-cover="${esc(item.cover || '')}" alt=""><button class="hero-cover-button" type="button" data-open-id="${esc(item.id || '')}" aria-label="查看${esc(item.title || '作品')}详情"></button><div class="hero-shade"></div>
    <div class="hero-copy"><span class="hero-label">本周焦点 <i></i> 独家精选</span><h2>${esc(item.title || '为你推荐')}</h2><p>${esc(item.description || item.episodeLabel || '')}</p><button class="hero-action" data-open-id="${esc(item.id || '')}" type="button">${icon('play')} 立即观看</button></div>
    <div class="hero-index"><b>${String(index + 1).padStart(2, '0')}</b> / ${String(count).padStart(2, '0')}</div></div>`;
}
async function changeBanner(targetIndex, direction, automatic = false) {
  const slides = bannerSlides();
  const route = state.route, token = routeToken;
  const currentRoute = () => state.route === route && routeToken === token && !document.hidden;
  const hero = content.querySelector('.hero');
  if (!isBannerRoute() || !hero || !slides.length || bannerChanging || targetIndex === bannerIndex()) return;
  stopBannerAuto();
  bannerChanging = true;
  let incoming = null;
  try {
    const item = slides[targetIndex];
    // 没有封面就别去问桥。adapters.js 的选集适配器会给条目 cover: ''，而
    // cover.get('') 会一路走到 Java 的 coverUrl('') —— 它当场抛「无效封面地址：」
    // （冒号后面是空的），被记成一次失败。线上 13 台设备 15 条就是这么来的，
    // 每轮 banner 重报一遍（ms 只有 0~3ms，压根没到网络）。
    const data = item.kind === 'campaign'
      ? campaignArtwork(item)
      : (item.cover ? await cover.get(item.cover) : '');
    const template = document.createElement('template');
    template.innerHTML = route === 'category' ? categorySlide(item, targetIndex, slides.length) : heroSlide(item, targetIndex, slides.length);
    incoming = template.content.firstElementChild;
    const image = incoming.querySelector('img');
    image.src = data;
    if (!hero.isConnected || !currentRoute()) return;
    incoming.style.transform = `translateX(${direction > 0 ? 100 : -100}%)`;
    hero.appendChild(incoming);
    await image.decode();
    if (!hero.isConnected || !currentRoute()) { incoming.remove(); return; }
    if (route === 'home' && item.kind !== 'campaign') cover.applyPalette(image);
    const current = hero.querySelector('.hero-slide:not(:last-child)');
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    incoming.classList.add('animating');
    current.classList.add('animating');
    incoming.style.transform = 'translateX(0)';
    current.style.transform = `translateX(${direction > 0 ? -100 : 100}%)`;
    await new Promise(resolve => setTimeout(resolve, 330));
    current.remove();
    incoming.classList.remove('animating');
    incoming.style.transform = '';
    if (currentRoute()) {
      if (route === 'category') state.categoryBanner = targetIndex;
      else state.banner = targetIndex;
    }
    content.querySelectorAll('.hero-dots [data-slide]').forEach(dot => dot.classList.toggle('active', Number(dot.dataset.slide) === targetIndex));
  } catch (error) {
    incoming?.remove();
    hero.querySelectorAll('.hero-slide').forEach(slide => { slide.classList.remove('animating'); slide.style.transform = ''; });
    if (!automatic) toast('封面加载失败，请重试', true);
  }
  finally { bannerChanging = false; scheduleBannerAuto(); }
}
function continueWatchingItem() {
  // 要在当前源里挑。不挑的话，换源之后首页那张「继续观看」卡片还是上一个源的
  // 片子，点下去就是「找不到这个 id」—— 和历史列表同一个坑。
  return asArray(state.library?.progress).find(sameSource) || null;
}
function continueWatchingMarkup() {
  const item = continueWatchingItem();
  if (!item) return '';
  const episode = Math.max(1, Number(item.episode) || 1);
  const total = Number(item.episodeCount) || 0;
  const finished = Boolean(item.completed) && total > 0 && episode >= total;
  const seconds = Math.floor(Math.max(0, Number(item.position) || 0) / 1000);
  const time = `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`;
  return `<section class="continue-section"><div class="section-heading"><h2>继续观看</h2><button type="button" class="more" data-all-history="1">全部历史 ${icon('arrow')}</button></div>
    <button type="button" class="continue-card" data-continue-id="${esc(item.id)}" aria-label="${esc(item.title)}，${finished ? '打开详情' : '继续观看'}">
      <span class="continue-poster poster loading"><span class="poster-letter">${esc(String(item.title || '影片').slice(0, 1))}</span><img data-cover="${esc(item.cover || '')}" alt=""></span>
      <span class="continue-copy"><strong>${esc(item.title || '影片')}</strong><span>已看到第 ${episode} 集${total ? ` · 共 ${total} 集` : ''}</span><span class="continue-time">${finished ? '全剧已看完' : item.completed ? '本集已看完，接着看下一集' : `上次看到 ${time}`}</span></span>
      <span class="continue-action">${icon('play')}${finished ? '详情' : item.completed ? '继续看' : '续播'}</span>
    </button></section>`;
}
function updateContinueWatching() {
  const host = content.querySelector('.home-continue');
  if (!host) return;
  const markup = continueWatchingMarkup();
  if (host.continueMarkup === markup) return;
  const savedScroll = scroll.scrollTop;
  host.innerHTML = markup;
  host.continueMarkup = markup;
  host.querySelectorAll('img[data-cover]').forEach(cover.loadInto);
  scroll.scrollTop = savedScroll;
}
async function refreshHomeHistory() {
  const token = routeToken, source = contentSource, userId = state.account.userId;
  try {
    const library = await api('library');
    if (token !== routeToken || source !== contentSource || userId !== state.account.userId) return;
    state.library = library;
    if (state.route === 'home') updateContinueWatching();
  } catch (_) { }
}
async function resumeFromHome(id) {
  const item = continueWatchingItem();
  if (!item || String(item.id) !== id) return;
  const source = contentSource;
  const episode = Math.max(1, Number(item.episode) || 1) + (item.completed ? 1 : 0);
  const token = routeToken + 1;
  await goDetail(id);
  if (routeToken !== token || contentSource !== source || state.route !== 'detail' || state.detailLoading || String(state.detail?.id) !== id) return;
  if (episode <= asArray(state.detail.episodes).length) feedFrom(id, episode);
}

function renderHome() {
  const data = state.home;
  if (!data) { renderSkeleton(); return; }
  const slides = homeSlides();
  const index = Math.min(state.banner, Math.max(0, slides.length - 1));
  const selected = slides[index] || {};
  content.innerHTML = `<div class="home-page"><div class="hero" data-hero="1">${heroSlide(selected, index, slides.length)}</div>
      <div class="hero-dots">${slides.map((_, i) => `<i class="${i === index ? 'active' : ''}" data-slide="${i}"></i>`).join('')}</div>
      ${homeTagBar()}
      <div class="home-continue">${continueWatchingMarkup()}</div>
      ${section('精选推荐', asArray(data.homepageRecommend).length ? asArray(data.homepageRecommend) : asArray(data.recommend?.items).slice(0, 12), 'recommend')}
      ${section('最新上架', asArray(data.homepageNewest).length ? asArray(data.homepageNewest) : asArray(data.newest?.items).slice(0, 12), 'newest')}</div>`;
  cover.hydrate();
  slides.forEach(item => { if (item.cover) cover.get(item.cover).catch(() => {}); });
  scheduleBannerAuto();
}

function renderTags() {
  const tags = asArray(state.tags?.items);
  // ── 2026-10-17 修：非 private 模式原来**不看配置** ──────────────────
  //
  // 原代码：
  //   const sections = （曾经按双态分叉，现在只剩配置这一条）
  //
  // 而当时的双态判断只对内置源成立。
  // 于是**所有用户自带源**都被按死在那一对短剧/漫剧上：配置里写了什么
  // tagGroups 都没用，标签的 group 只要不是 short/comic 就一个都渲染不出来。
  //
  // 症状极具误导性：接口明明拿到了 240 个标签（右上角就写着「240 个标签」），
  // 页面却是空的，而且不报错 —— 正是站点配置参考里警告过的那种静默失败。
  //
  // 现在的顺序：**配置优先**。只有配置里没写 tagGroups 时才退回内置分组，
  // 这样黄果那套和旧的短剧/漫剧源行为不变。
  const configured = siteUi().tagGroups || [];
  // 配置里没写组就空着 —— 下面那段「数据里出现的组按需补上」会把它们捡回来，
  // 分组标题用 GROUP_TITLE 兜底。原来这里默认塞「短剧类型 / 漫剧类型」，
  // 那是内置源的分组词汇，对别的源是错的。
  const base = configured;
  // **数据里出现的组必须都能渲染。** 配置是「这份源有哪些组」的声明，但源是会
  // 变的（老源 就是：标签页列的是站点分类栏，组名叫 category，而它那份
  // user-source.json 是更早的版本生成的，里面根本没有 tagGroups）。只按配置
  // 渲染的结果是「10 个标签」写在标题上、页面一片空 —— 数字对、内容没有，
  // 最难查的那种。所以配置里没声明的组按数据补上，标题用通用名。
  const GROUP_TITLE = { popular: '热门', story: '题材', setting: '场景', plot: '玩法',
                        style: '外貌', relationship: '关系', other: '其他', cms: '分类',
                        category: '分类', short: '短剧类型', comic: '漫剧类型', hanime: '里番' };
  const declared = new Set(base.map(([id]) => id));
  const extra = [...new Set(tags.map(tag => tag.group || 'other'))]
    .filter(group => !declared.has(group))
    .map(group => [group, GROUP_TITLE[group] || '其他']);
  const sections = [...base, ...extra];
  const groups = sections.map(([id, title]) => {
    const members = tags.filter(tag => tag.group === id);
    if (!members.length) return '';
    return `<section class="tags-group"><h2>${esc(title)}</h2><div class="tags-grid">${members.map(tag => {
      const name = String(tag.name || tag.slug);
      const size = name.length > 8 ? 'wide' : name.length > 4 ? 'medium' : '';
      return `<button type="button" class="tag-choice ${size}" data-tag-slug="${esc(tag.slug)}" data-tag-name="${esc(name)}">${esc(name)}</button>`;
    }).join('')}</div></section>`;
  }).join('');
  content.innerHTML = `<div class="tags-page"><div class="tags-heading"><button type="button" data-back="1" aria-label="返回上一页">${icon('back')}</button><h1>全部类型</h1><span>${tags.length} 个标签</span></div>
    <p class="tags-intro">点选标签，发现相关作品</p>${groups}</div>`;
}

function refreshMissingImages(items) {
  const byId = new Map(items.filter(item => item?.id && item?.cover).map(item => [String(item.id), item.cover]));
  content.querySelectorAll('[data-open-id]').forEach(button => {
    const image = button.querySelector('img[data-cover]') || button.closest('.hero-slide,.category-spotlight')?.querySelector('img[data-cover]');
    // 这个局部别叫 cover —— 会遮住模块级那个 cover 对象（第 80 行），
    // 于是下面 cover.loadInto 变成「对字符串取属性」，抛 TypeError。
    // 症状极具误导性：首页接口明明 200，界面上却弹「内容更新失败，正在显示缓存」。
    const url = byId.get(button.dataset.openId);
    if (!image || !url || image.naturalWidth > 0) return;
    image.dataset.cover = url;
    delete image.dataset.loading;
    cover.loadInto(image);
  });
}

function categoryId() {
  const ids = siteUi().categoryIds || {};
  return (ids[state.tab] || [])[subIndex(state.tab)];
}
// 分类页顶部那根「滚动时收成一条」的紧凑头，是内置私密源的双分区产物。
// 双态拆掉之后它没有触发条件了，留两个空函数是为了不动调用点（renderCategory
// 和 scroll 监听都在用）——真要复用得先想清楚它对单分区的源是什么语义。
function compactMarkup() { return ''; }
function updateCompact() { compact.classList.remove('visible'); }
scroll.addEventListener('scroll', updateCompact, { passive: true });

function renderCategory(keepCompact = false) {
  categoryObserver?.disconnect();
  const adult = state.tab === 'adult';
  const tabs = categoryTabs();
  const items = asArray(state.category?.items);
  const lead = items[0] || null;
  const slides = items.slice(0, 5);
  const banner = Math.min(state.categoryBanner, Math.max(0, slides.length - 1));
  state.categoryBanner = banner;
  const spotlight = slides.length ? `<div class="hero category-spotlight category-carousel">${categorySlide(slides[banner], banner, slides.length)}</div><div class="hero-dots">${slides.map((_, index) => `<i class="${index === banner ? 'active' : ''}" data-slide="${index}"></i>`).join('')}</div>` : lead ? `<div class="category-spotlight"><img data-cover="${esc(lead.cover)}" alt=""><div class="category-spotlight-copy"><h2>${esc(lead.title)}</h2><button type="button" data-open-id="${esc(lead.id)}">查看作品 ${icon('arrow')}</button></div></div>` : '';
  // 分类多于两个时挂 `many`：CSS 那边 .segmented 是写死的两列网格（黄果就两个），
  // 塞八个进去会塌成四行、高度还卡在 51px，于是盖住下面的卡片。
  // 挂上类之后改成横向滚动，两个的源看起来跟原来一模一样。
  // 一个 tab 底下有多个分类才需要这排切换器。原来它只在「私密态」渲染，
  // 于是苹果CMS 那种一个 tab 挂四十多个分类的源永远切不到别的分类。
  const switcher = tabs.length > 1 ? `<div class="segmented${tabs.length > 2 ? ' many' : ''}" role="tablist">${tabs.map((name, index) => `<button type="button" role="tab" data-sub="${index}" aria-selected="${index === subIndex(state.tab)}" class="${index === subIndex(state.tab) ? 'selected' : ''}">${esc(name)}</button>`).join('')}</div>` : '';
  // 副标题只有配置给了才画（见 categoryLead）；加载中画骨架卡，不是一片空白。
  //
  // 原来加载时这里是「已显示 0 部」+ 空网格 + 一行「正在加载…」—— 一屏全黑，
  // 用户唯一能判断「它在动」的就是那行小字。骨架至少有形状：6 张海报位在那儿
  // 呼吸，用户知道「这一屏马上有东西」。
  const leadText = categoryLead();
  const loading = !state.category;
  content.innerHTML = `<div class="category-page"><div class="category-head"><h1>${esc(categoryTitle())}</h1>${leadText ? `<p>${esc(leadText)}</p>` : ''}</div>
    ${switcher}
    ${spotlight}
    <section class="category-section"><div class="section-heading"><h2>${esc(tabs[subIndex(state.tab)])}作品</h2><span class="category-count">${loading ? '正在加载…' : `已显示 ${items.length} 部`}</span></div>
    <div class="search-grid category-grid">${loading ? skeletonCards(6) : items.map(itemCard).join('')}</div><div class="category-footer">${categoryFooter()}</div></section></div>`;
  compact.innerHTML = compactMarkup();
  scroll.scrollTop = keepCompact ? 150 : 0;
  state.compactHeld = keepCompact;
  updateCompact();
  cover.hydrate();
  observeCategoryEnd();
  slides.forEach(item => { if (item.cover) cover.get(item.cover).catch(() => {}); });
  scheduleBannerAuto();
  scheduleCategoryWarmup();
}

function categoryFooter() {
  const category = state.category;
  // 壳刚画出来、数据还没到：这时候上面已经是骨架卡 + 「正在加载…」了，
  // 这里再写一句就是同一个词在一屏里出现两次。留空比重复好。
  if (!category) return '';
  if (category?.error) return '<button type="button" data-retry-category="1">加载失败，点击重试</button>';
  if (category?.loading) return '<span role="status">正在加载更多…</span>';
  if (category?.refreshing) return '<span role="status">正在更新列表…</span>';
  if (category?.nextPage) return '<span class="category-sentinel" aria-hidden="true"></span>';
  return '<span>已经到底了</span>';
}

function observeCategoryEnd() {
  categoryObserver?.disconnect();
  const category = state.category;
  if (state.route !== 'category' || !category?.nextPage || category.loading || category.refreshing || category.error) return;
  const sentinel = content.querySelector('.category-sentinel');
  if (!sentinel) return;
  if (!categoryObserver) {
    categoryObserver = new IntersectionObserver(entries => {
      if (entries.some(entry => entry.isIntersecting)) loadMoreCategory();
    }, { root: scroll, rootMargin: '0px 0px 320px 0px' });
  }
  categoryObserver.observe(sentinel);
}

function updateCategoryFooter() {
  const footer = content.querySelector('.category-footer');
  if (!footer) return;
  footer.innerHTML = categoryFooter();
  observeCategoryEnd();
}

// 和 UserSource.identity() 的前缀对齐。改了那边这里也要改。
const IDENTITY_PREFIX = 'hgsrc:';

function renderLibrary() {
  const data = state.library || { progress: [], bookmarks: [] };
  const query = state.libraryQuery.trim().toLocaleLowerCase();
  const matches = item => String(item.title || '').toLocaleLowerCase().includes(query);
  // 不是当前内容源的记录直接不显示 —— 它们的 id 在这个源里没有意义，露出来
  // 只会让人点进去撞错。记录本身没删，切回那个源还在。
  const progress = asArray(data.progress).filter(sameSource).filter(matches);
  const bookmarks = asArray(data.bookmarks).filter(matches);
  const historyActive = state.libraryTab === 'history';
  const historyCards = progress.map(item => {
    const completed = Boolean(item.completed);
    const episode = Number(item.episode) || 1;
    const percentage = completed ? 100 : Math.max(0, Math.min(100, item.duration ? item.position / item.duration * 100 : 0));
    return `<button type="button" class="history-card" data-history-id="${esc(item.id)}" data-history-episode="${episode}" data-history-completed="${completed}" aria-label="${esc(item.title)}，第 ${episode} 集，${completed ? '已看完，打开详情' : '继续观看'}">
      <span class="history-poster poster loading"><span class="poster-letter">${esc(String(item.title || '影片').slice(0, 1))}</span><img data-cover="${esc(item.cover || '')}" alt=""></span><span class="history-copy"><span class="history-status">${completed ? '已看完' : '继续观看'} · 第 ${episode} 集</span>
      <strong>${esc(item.title || '影片')}</strong><span class="progress"><i style="width:${percentage}%"></i></span><span class="history-hint">${completed ? '打开详情选择剧集' : `已看 ${Math.floor(item.position / 60000)} 分钟`}</span></span></button>`;
  }).join('');
  content.innerHTML = `<div class="library-page"><div class="my-heading"><h1>我的</h1></div>
    ${state.account.loggedIn ? '' : `<button type="button" class="my-account" data-account-action="1">点击头像登录，同步收藏和观看历史 ${icon('arrow')}</button>`}
    ${query ? `<div class="library-results"><p class="library-count">在观看历史中找到 ${progress.length} 部，在我的收藏中找到 ${bookmarks.length} 部</p>
      <h2>观看历史</h2>${progress.length ? `<div class="history-list">${historyCards}</div>` : '<p class="library-no-match">没有匹配的观看记录</p>'}
      <h2>我的收藏</h2>${bookmarks.length ? `<div class="search-grid library-bookmark-grid">${bookmarks.map(itemCard).join('')}</div>` : '<p class="library-no-match">没有匹配的收藏</p>'}</div>` : `
    <div class="segmented library-tabs" role="tablist" aria-label="我的片单分类"><button type="button" role="tab" data-library-tab="history" aria-selected="${historyActive}" class="${historyActive ? 'selected' : ''}">观看历史</button>
    <button type="button" role="tab" data-library-tab="bookmarks" aria-selected="${!historyActive}" class="${!historyActive ? 'selected' : ''}">我的收藏</button></div>
    <p class="library-count">${historyActive ? `最近观看的 ${progress.length} 部作品` : `已收藏 ${bookmarks.length} 部作品`}</p>
    ${historyActive ? (progress.length ? `<div class="history-list">${historyCards}</div>` : '<div class="empty-state">还没有观看记录，播放作品后会出现在这里</div>') :
      (bookmarks.length ? `<div class="search-grid library-bookmark-grid">${bookmarks.map(itemCard).join('')}</div>` : '<div class="empty-state">还没有收藏作品</div>')}`}</div>`;
  cover.hydrate();
}

// 外观。原来是一段说明 + 三张带副标题的卡片，现在压成一张卡里的一条分段控件：
// 三个色块本身就是说明，选中态给实心描边 + 顶部一颗小勾，不靠文字确认。
// 点一下整屏交叉淡化（setThemeMode 里的 View Transition），反馈直接落在屏幕上。
function appearanceSection() {
  const mode = themeMode();
  const choices = [['system', '跟随系统'], ['light', '浅色'], ['dark', '深色']];
  return `<section class="settings-card" aria-label="外观">
    <div class="settings-row is-static">
      <span class="settings-icon">${icon('theme')}</span>
      <span class="settings-body"><strong>外观</strong></span>
    </div>
    <div class="settings-seg" role="radiogroup" aria-label="界面主题">${choices.map(([value, label]) => `
      <button type="button" role="radio" aria-checked="${mode === value}" class="settings-seg-item ${mode === value ? 'selected' : ''}" data-theme-mode="${value}">
        <span class="theme-swatch swatch-${value}" aria-hidden="true"><i></i><b></b></span>
        <span class="settings-seg-label">${label}</span>
      </button>`).join('')}</div>
  </section>`;
}

/**
 * 设置页的「版本与更新」一行。
 *
 * <p>热更那半截（界面资源包、待应用、需要新版 APK）2026-10-19 整块拆掉了：
 * 它的问题是**越级覆盖** —— 装一版带新界面的 APK 之后，设备上跑的仍然是旧热更包
 * 里的前端，而「关于」页的版本号完全看不出来，于是「我明明装了新版」和
 * 「怎么还是老样子」会同时成立。现在界面只有 APK 里那一份，装了什么就是什么。
 *
 * <p>剩下的只有 APK 更新：检查清单、下载、拉起安装器，都是原生 UpdateManager 在跑。
 */
function updateSection() {
  return `<section class="settings-card" aria-label="版本与更新">
    <div class="settings-row is-static" id="update-check-area">
      <button type="button" class="settings-icon" data-secret-entry aria-label="当前版本 ${esc(appVersion || '读取中')}">${icon('update')}</button>
      <span class="settings-body"><strong>版本</strong><small>${appVersion ? `v${esc(appVersion)}` : '读取中…'}</small></span>
      <span class="settings-tail" id="update-check-tail">${updateTailMarkup()}</span>
    </div>
  </section>`;
}

/**
 * 版本行右边那一格。
 *
 * <p>四种形态，宽度都控制在一颗胶囊 / 一个按钮之内，所以这一行的高度永远不变 ——
 * 整块 innerHTML 替换的话（提示句 / 进度条 / 按钮各一种高度），每换一次页面就抖一下。
 */
function updateTailMarkup() {
  const u = updateCheck;
  if (u.phase === 'downloading') {
    const pct = Math.min(100, Math.floor(u.progress));
    const speed = u.progress < 100 ? fmtSpeed(updateSpeed.bps) : '';
    return `<span class="update-progress"><span class="update-progress-bar"><i style="width:${pct}%"></i></span><small>${pct}%${speed ? ' · ' + speed : ''}</small></span>`;
  }
  if (u.phase === 'checking') return '<span class="settings-chip">检查中</span>';
  // percent>=100（sha256 校验通过）后的过渡态：安装器由原生自动拉起，这里只说
  // 一句话、不放按钮 —— 用户取消安装器回来时，看门狗/重检查会把状态收走。
  if (u.phase === 'ready') return '<span class="settings-chip is-ok">更新包已就绪，正在打开安装程序…</span>';
  if (u.phase === 'error') {
    return `<button type="button" class="settings-mini" data-check-update="1">重试</button>`;
  }
  if (u.phase === 'available') {
    const version = esc(u.info?.version || '');
    return `<button type="button" class="settings-mini is-primary" data-download-update="1">更新到 v${version}</button>`;
  }
  if (u.phase === 'none') return '<span class="settings-chip is-ok">已是最新</span>';
  return '<button type="button" class="settings-mini" data-check-update="1">检查</button>';
}

function renderSettings() {
  const account = state.account;
  const net = proxyChip();
  const player = playerChip();
  const historyCount = asArray(state.library?.progress).length;
  const bookmarkCount = asArray(state.library?.bookmarks).length;

  content.innerHTML = `<div class="settings-page">
    <button type="button" class="listing-back" data-back="1">${icon('back')} 返回我的</button>
    <h1>设置</h1>

    <section class="settings-card" aria-label="内容">
      ${settingsRow('data-source-settings="1"', 'source', '内容源', state.sourceName || '还没有源',
        state.sourceName ? (state.sourceCount > 1 ? `${state.sourceCount} 个` : '已启用') : '未配置',
        state.sourceName ? 'is-ok' : 'is-warn')}
      ${settingsRow('data-market="1"', 'market', '源市场', '点一下换一个源', '', '')}
    </section>

    <section class="settings-card" aria-label="播放与网络">
      ${settingsRow('data-proxy-settings="1"', 'proxy', '网络代理', '', net.text, net.tone)}
      ${settingsRow('data-player-settings="1"', 'player', '播放器', '', player.text, player.tone)}
    </section>

    ${appearanceSection()}

    <section class="settings-card" aria-label="账号">
      ${settingsRow('data-account-management="1"', 'account', '账号',
        account.loggedIn ? esc(account.username) : '登录后同步片单',
        account.loggedIn ? '已登录' : '登录', account.loggedIn ? 'is-ok' : '')}
    </section>

    ${updateSection()}

    ${window.__hgDebugBuild ? `<section class="settings-card" aria-label="诊断">
      ${settingsRow('data-cover-diagnostics="1"', 'storage', '封面诊断', '观测集 · 最近失败 · 冻结中', 'debug', '')}
    </section>` : ''}

    <section class="settings-card" aria-label="片单与缓存">
      <div class="settings-row is-static">
        <span class="settings-icon">${icon('library')}</span>
        <span class="settings-body"><strong>片单</strong><small>历史 ${historyCount} · 收藏 ${bookmarkCount}</small></span>
        <span class="settings-tail">
          <button type="button" class="settings-mini" data-clear="history" ${historyCount ? '' : 'disabled'}>清历史</button>
          <button type="button" class="settings-mini" data-clear="bookmarks" ${bookmarkCount ? '' : 'disabled'}>清收藏</button>
        </span>
      </div>
      <div class="settings-row is-static">
        <span class="settings-icon">${icon('storage')}</span>
        <span class="settings-body"><strong>缓存</strong><small>目录、封面、接口响应</small></span>
        <span class="settings-tail"><button type="button" class="settings-mini" data-clear="cache">清理</button></span>
      </div>
    </section>

    <section class="settings-card is-danger" aria-label="恢复出厂设置">
      <div class="settings-row is-static">
        <span class="settings-icon">${icon('reset')}</span>
        <span class="settings-body"><strong>恢复出厂设置</strong><small>退出登录，清空内容源、代理、片单和缓存</small></span>
      </div>
      <div class="settings-row is-action">
        <button type="button" class="settings-danger-button" data-factory-reset="1">恢复出厂设置</button>
      </div>
    </section>
  </div>`;
}

/**
 * 设置页的一行。整行是一个按钮：左边类别图标，中间标题，右边状态胶囊 + 箭头。
 *
 * 这里刻意不留说明段落 —— 原来每一块都是「h2 + 三行解释 + 按钮」，一屏塞不下，
 * 用户得读三段话才知道点哪儿。状态改用胶囊（已连接 / 未配置 / 硬解）直接说结论，
 * 解释挪进 aria-label 和二级页。
 */
function settingsRow(attributes, iconName, title, sub, chip, tone) {
  return `<button type="button" class="settings-row" ${attributes}>
    <span class="settings-icon">${icon(iconName)}</span>
    <span class="settings-body"><strong>${esc(title)}</strong>${sub ? `<small>${esc(sub)}</small>` : ''}</span>
    <span class="settings-tail">${chip ? `<span class="settings-chip ${tone || ''}">${esc(chip)}</span>` : ''}${icon('chevron')}</span>
  </button>`;
}

function renderPlayerSettings() {
  const prefs = playerPrefs();
  const info = state.nativeInfo;
  // 这一页原来有四段解释（播放内核是什么、自动和软解的区别、缓存和抖动的关系、
  // 手势怎么用），一屏全是字。现在每个开关都是一条分段控件 + 一句「这样会怎样」，
  // 说明跟着当前选中项变 —— 用户读到的是后果，不是设计背景。
  const decodeHint = prefs.decode === 'sw'
    ? '所有片都走 CPU 解，最稳，但高清片更费电、更容易发热。'
    : '先试硬件解码，花屏或只有声音没画面时自动换软解。';
  const cacheHint = prefs.cache === 800 ? '起播最快，网络一抖就容易卡。'
    : prefs.cache === 3000 ? '最抗抖动，但拖进度条要等更久才出画面。'
      : '起播和抗抖动之间的折中，大多数情况够用。';
  const seg = (key, items, current) => `<div class="settings-seg-control" role="radiogroup">${items.map(([value, label]) =>
    `<button type="button" role="radio" aria-checked="${current === value}" class="settings-seg-pill ${current === value ? 'selected' : ''}" data-player-set="${key}:${value}">${label}</button>`).join('')}</div>`;

  content.innerHTML = `<div class="settings-page">
    <button type="button" class="listing-back" data-back="1">${icon('back')} 返回设置</button>
    <h1>播放器</h1>

    <section class="settings-card" aria-label="解码">
      <div class="settings-row is-static">
        <span class="settings-icon">${icon('player')}</span>
        <span class="settings-body"><strong>解码方式</strong><small>${esc(decodeHint)}</small></span>
      </div>
      <div class="settings-row is-action">
        ${seg('decode', [['auto', '自动'], ['sw', '一律软解']], prefs.decode)}
      </div>
    </section>

    <section class="settings-card" aria-label="缓存">
      <div class="settings-row is-static">
        <span class="settings-icon">${icon('storage')}</span>
        <span class="settings-body"><strong>网络缓存</strong><small>${esc(cacheHint)}</small></span>
      </div>
      <div class="settings-row is-action">
        ${seg('cache', [['800', '小'], ['1500', '中'], ['3000', '大']], String(prefs.cache))}
      </div>
    </section>

    <section class="settings-card" aria-label="解码库状态">
      <div class="settings-row is-static">
        <span class="settings-icon">${icon('check')}</span>
        <span class="settings-body"><strong>解码库</strong><small>${info == null ? '正在检测…' : info.native ? '内置 VLC 可用（' + esc(info.abi || 'arm64-v8a') + '）' : '这个安装包没带本机架构的解码库'}</small></span>
        <span class="settings-tail"><span class="settings-chip ${info == null ? '' : info.native ? 'is-ok' : 'is-warn'}">${info == null ? '检测中' : info.native ? '可用' : '不可用'}</span></span>
      </div>
    </section>

    <section class="settings-card" aria-label="手势">
      <div class="settings-row is-static">
        <span class="settings-icon">${icon('play')}</span>
        <span class="settings-body"><strong>手势</strong><small>上下滑切集 · 左右滑拖进度 · 单击出控件</small></span>
      </div>
    </section>
  </div>`;
}

function goPlayerSettings() {
  if (state.route === 'player-settings') return;
  if (state.route !== 'settings') goSettings();
  state.playerReturn = captureScreen();
  setRoute('player-settings');
  renderPlayerSettings();
  // 页面先用「正在检测…」渲染出来，探测结果回来再补一次 —— 不让这一页等 so 加载。
  nativePlayerInfo().then(() => { if (state.route === 'player-settings') renderPlayerSettings(); });
}

// ── 封面诊断（方案 §4.5，2026-10-31）─────────────────────────────────
//
// 只回答一个问题：「此刻为什么没封面」。三块数据全来自 cover-diag 桥：
// 观测集（哪些主机裸拉成功过、还信不信）、最近 20 条失败、冻结中的主机和剩余秒数。
// 入口只在 debug 构建渲染（window.__hgDebugBuild，见 renderSettings）——
// release 用户的界面上没有这一行，但桥命令本身两边的包都有（排障时可以 CDP 直调）。
let coverDiagTimer = null;

function goCoverDiagnostics() {
  if (state.route === 'cover-diagnostics') return;
  if (state.route !== 'settings') goSettings();
  state.coverDiagReturn = captureScreen();
  setRoute('cover-diagnostics');
  renderCoverDiagnostics();
  refreshCoverDiagnostics();
  // 冻结剩余秒数会走，5 秒刷一轮足够看。离开页面由下一个 tick 自己收掉。
  if (!coverDiagTimer) {
    coverDiagTimer = setInterval(() => {
      if (state.route !== 'cover-diagnostics') { clearInterval(coverDiagTimer); coverDiagTimer = null; return; }
      refreshCoverDiagnostics();
    }, 5000);
  }
}

function refreshCoverDiagnostics() {
  api('cover-diag').then(data => {
    state.coverDiag = data;
    if (state.route === 'cover-diagnostics') renderCoverDiagnostics();
  }).catch(error => {
    state.coverDiag = { error: error.message || '诊断数据拿不到' };
    if (state.route === 'cover-diagnostics') renderCoverDiagnostics();
  });
}

function renderCoverDiagnostics() {
  const data = state.coverDiag || {};
  const now = Date.now();
  const observations = asArray(data.observations);
  const failures = asArray(data.recentFailures);
  const frozen = asArray(data.frozenHosts);
  const observationRows = observations.length ? observations.map(one => {
    const trusted = Boolean(one.trusted);
    const age = Math.max(0, Math.round((now - Number(one.lastSeenAt || 0)) / 60000));
    return `<div class="settings-row is-static"><span class="settings-body"><strong>${esc(one.host || '?')}</strong>
      <small>成功 ${Number(one.ok || 0)} · 失败 ${Number(one.fail || 0)} · ${age} 分钟前命中</small></span>
      <span class="settings-tail"><span class="settings-chip ${trusted ? 'is-ok' : 'is-warn'}">${trusted ? 'B 观测信任' : 'C 降级待验证'}</span></span></div>`;
  }).join('') : '<div class="settings-row is-static"><span class="settings-body"><small>还没有观测记录 —— 名单内的封面不会进观测集，这是正常的。</small></span></div>';
  const failureRows = failures.length ? failures.map(line => {
    const parts = String(line).split('\t');
    const at = Number(parts[0]) ? new Date(Number(parts[0])).toLocaleTimeString() : '?';
    return `<div class="cover-diag-failure"><span class="cover-diag-time">${esc(at)}</span><span>${esc(parts.slice(1).join(' ') || line)}</span></div>`;
  }).join('') : '<div class="settings-row is-static"><span class="settings-body"><small>最近没有失败。</small></span></div>';
  const frozenRows = frozen.length ? frozen.map(one => {
    const left = Math.max(0, Math.ceil((Number(one.until || 0) - now) / 1000));
    return `<div class="settings-row is-static"><span class="settings-body"><strong>${esc(one.host || '?')}</strong><small>网络类失败后的 45 秒冻结</small></span>
      <span class="settings-tail"><span class="settings-chip is-warn">剩 ${left}s</span></span></div>`;
  }).join('') : '<div class="settings-row is-static"><span class="settings-body"><small>没有冻结中的主机。</small></span></div>';
  content.innerHTML = `<div class="settings-page">
    <button type="button" class="listing-back" data-back="1">${icon('back')} 返回设置</button>
    <h1>封面诊断</h1>
    ${data.error ? `<section class="settings-card is-danger"><div class="settings-row is-static"><span class="settings-body"><strong>诊断数据拿不到</strong><small>${esc(data.error)}</small></span></div></section>` : ''}
    <section class="settings-card" aria-label="观测集">
      <div class="settings-row is-static"><span class="settings-body"><strong>观测集</strong><small>裸拉成功进来的图床主机（B 档），上限 32 条、7 天未命中过期</small></span></div>
      ${observationRows}
    </section>
    <section class="settings-card" aria-label="冻结中">
      <div class="settings-row is-static"><span class="settings-body"><strong>冻结中的主机</strong><small>一次失败冻结 45 秒，期满自动重试</small></span></div>
      ${frozenRows}
    </section>
    <section class="settings-card" aria-label="最近失败">
      <div class="settings-row is-static"><span class="settings-body"><strong>最近失败</strong><small>最多 20 条，新在前（本地记录，不上报）</small></span></div>
      <div class="cover-diag-failures">${failureRows}</div>
    </section>
  </div>`;
}

function renderAccountManagement() {
  const account = state.account;
  content.innerHTML = `<div class="settings-page account-page"><button type="button" class="listing-back" data-back="1">${icon('back')} 返回设置</button><h1>账号管理</h1>
    ${account.loggedIn ? `<section class="account-identity"><img src="${esc(account.avatar || 'user.svg')}" alt=""><div><strong>${esc(account.username)}</strong><span>已登录应用账号</span></div></section>
      <section class="settings-account"><h2>个人资料</h2><button type="button" data-choose-avatar="1">从相册更换头像</button></section>
      <section class="settings-account"><h2>修改密码</h2><p>先验证旧密码，再设置至少 8 位的新密码。</p>
        <form id="password-form" class="password-form">
          <label for="old-password">旧密码</label><input id="old-password" type="password" autocomplete="current-password" required>
          <label for="new-password">新密码</label><input id="new-password" type="password" autocomplete="new-password" minlength="8" required>
          <label for="confirm-password">再次输入新密码</label><input id="confirm-password" type="password" autocomplete="new-password" minlength="8" required>
          <p id="password-error" class="auth-error" role="alert" hidden></p><button type="submit">确认修改</button>
        </form></section>
      <section class="settings-account"><h2>同步与登录</h2><button type="button" data-sync-now="1">立即同步片单</button><button type="button" data-logout="1" aria-live="polite">退出登录</button></section>` : `<section class="settings-account"><p>登录后可同步收藏和观看历史，并管理头像与密码。</p><button type="button" data-account-action="1">登录或注册</button></section>`}</div>`;
}

// ── 恢复出厂设置 ──────────────────────────────────────────────────────
//
// 「像刚装好一样」拆开是四件事：没有登录、没有片单、没有缓存、没有源。账号本身
// 和云端的片单不动 —— 重新登录还能拿回来。
//
// 顺序是硬要求：
//   ① 先退出登录。ParseUser 的当前用户活在**内存**里，而重启走的是 Activity 重建、
//      进程不死；MainActivity 在 onCreate 里照样能从它拿到 objectId，界面照样是
//      登录态。所以「把盘上那份账号文件删掉」不等于退出登录，必须让 logout 真的
//      跑完（这正是它排在清盘前面的原因，而不是反过来）。
//   ② 再让 Java 清盘（__source:reset）：源、片单、代理、头像、缓存目录，外加 WebView
//      的 Cookie —— HttpOnly 的 Cookie 前端看不见也删不掉，只有 CookieManager 够得着。
//      这一步又必须在①之后：清盘会把 cacheDir 端掉，而 Parse 的登录态就在那底下，
//      先清它就等于让①没得退。
//   ③ 然后清前端自己这份（localStorage 为主）。排在②之后才有效 —— 反过来的话，
//      ②里那次 cacheDir 清理正好会抽掉③还在写的文件。
//   ④ 最后重启。「配置」是 MainActivity.onCreate 那条链里读的，源没了，重进来的
//      那一屏就是「设置源」，和第一次装好打开时一模一样。
async function runFactoryReset(button) {
  const accepted = await askConfirmation('恢复出厂设置',
    '退出登录，并清掉这台设备上的收藏、观看历史、内容缓存、内容源和代理。清完会重启，回到刚装好、还没填源的样子（要重新填一次源地址）。账号本身和云端片单不受影响。',
    '确认重置');
  if (!accepted) return;

  const original = button.textContent;
  button.disabled = true;
  button.classList.add('is-loading');
  button.textContent = '正在重置…';
  try {
    // ① 退出登录。离线时 Parse 的登出要等它自己的超时，而后面三步全是本地操作，
    //    没理由陪着一起等 —— 给它封个顶：只有**明确回来了**才算退干净，超时（undefined）
    //    和报错（false）都当没退，下一次启动补（finishResetLogout）。
    let loggedOut = true;
    if (state.account.loggedIn) {
      loggedOut = await settleWithin(api('logout').then(() => true).catch(() => false), 6000) === true;
    }
    // ② Java 清盘
    await sourceCommand('reset');
    // ③ 前端存储
    await wipeWebStorage();
    // ④ 没退干净的记一笔给下一次启动（必须在③之后写），然后重启
    if (!loggedOut) {
      try { localStorage.setItem(RESET_FLAG, String(Date.now())); } catch (error) {}
    }
    toast('已恢复出厂设置，正在重启…');
    restartAfterSource(() => { location.reload(); });
  } catch (error) {
    console.log('[reset] 重置中断：' + (error.message || error));
    toast('重置没做完：' + (error.message || error), true);
    button.disabled = false;
    button.classList.remove('is-loading');
    button.textContent = original;
  }
}

/** 给一个动作封顶：到点就往下走，不管它做没做完。做没做完由调用方自己判断。 */
function settleWithin(promise, ms) {
  return Promise.race([promise, new Promise(resolve => window.setTimeout(resolve, ms))]);
}

/**
 * 前端这一侧的存储。Java 那边清的是 Cookie 和 DOM storage（见 source 桥的 reset 指令），
 * 这里把页面自己看得见的全部清空 —— 重点是 localStorage：片单、接口缓存、
 * 首页骨架屏、缓存作用域、倍速档位，全在它里面。
 *
 * Cache Storage 和 IndexedDB 现在没人用，但「像刚装好一样」要成立，就不能按
 * 「我猜没用到」来清；两个 API 在某些 WebView 上会缺席，各自包一层。
 */
async function wipeWebStorage() {
  try { localStorage.clear(); } catch (error) {}
  try { sessionStorage.clear(); } catch (error) {}
  try {
    document.cookie.split(';').forEach(item => {
      const name = item.split('=')[0].trim();
      if (name) document.cookie = name + '=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
    });
  } catch (error) {}
  try { await Promise.all((await caches.keys()).map(name => caches.delete(name))); } catch (error) {}
  try {
    const databases = await indexedDB.databases();
    (databases || []).forEach(db => { if (db && db.name) indexedDB.deleteDatabase(db.name); });
  } catch (error) {}
}

/**
 * 上一次恢复出厂设置如果没能当场退出登录（离线时网络那一截要等超时），这里补一刀。
 * 标记读一次就删 —— 它记的是「还有件事没干完」，不是配置。
 *
 * 为什么不能省：登出没回来，内存里那个 ParseUser 就还在；Activity 重建之后界面
 * 照样是登录态、只是片单空了 —— 「恢复出厂设置」这句承诺就不成立。
 */
function finishResetLogout() {
  let pending = '';
  try { pending = localStorage.getItem(RESET_FLAG) || ''; } catch (error) {}
  if (!pending) return;
  try { localStorage.removeItem(RESET_FLAG); } catch (error) {}
  console.log('[reset] 上一次重置的登出没跑完，补一次');
  api('logout').then(setAccount).catch(() => {});
}

// ---- 搜索历史 -------------------------------------------------------------
//
// 关键词住在 localStorage 的 hgpref: 命名空间，跟主题、播放偏好同一层：
//
//   · 不用 hgcache:。那一层是「某个源的内容缓存」，换源（cacheScope 变）和
//     DATA_SOURCE_VERSION 升级都会成片清掉；而「我之前搜过什么词」跟源没关系，
//     换个源接着搜同一个词是常态。
//   · 「恢复出厂设置」会 localStorage.clear()，这里跟着一起清 —— 那正是想要的。
//
// 上限 20 条，最近搜过的排最前；去重按不分大小写比，但显示保留用户最后一次
// 输入的原样（搜 abc 之后搜 ABC，显示的应是 ABC）。
const SEARCH_HISTORY_KEY = 'hgpref:search-history';
const SEARCH_HISTORY_LIMIT = 20;
const searchForm = document.getElementById('search-form');
const searchHistoryPanel = document.getElementById('search-history');
const searchHistoryTags = document.getElementById('search-history-tags');
const searchHistoryClear = document.getElementById('search-history-clear');
// 「我的」那一栏的搜索框搜的是本机片单，不该往搜索历史里塞。
const LIBRARY_ROUTES = ['library', 'settings', 'account-management'];


// 面板跟搜索框**一样长**：左边从搜索框左边缘起，右边到搜索框右边缘，
// 顶在搜索框下面 8px。
//
// 不写死像素，现量 searchForm 的 rect。原因有两个：
//   · 搜索框左边缘不是固定的 18px —— header 里还站着头像（「我的」那几屏
//     右侧还多一个齿轮），搜索框的起点随这些按钮走；写死就会从头像底下开始，
//     整块比搜索框宽出一个头像的身位。
//   · 状态栏高度、dp、屏宽各家机器都不一样，量出来最省事。
// 换台机器、转屏、系统字体变化都不用再调这行数。


// 面板开着就跟着数据一起变；关着就别自作主张把它弹出来。


// 输入框里出现字了就收起面板，清空之后（光标还在框里）再放出来。


/**
 * 网格位上的骨架卡。分类页 / 标签页 / 搜索页加载时都用这个。
 *
 * 首页那块整套 skeleton-home 是「大图 + 一行卡」，而这三处是纯网格 —— 用首页那套
 * 会画出一个不属于这里的横幅位。
 */

// 重试的统一入口：错误页按钮和自动重试都走这里，保证语义一致。

// 网络类错误的判定和展示是两件事，分开。
const NETWORK_ERROR_RE = /站点连接失败|站点请求失败|failed to connect|unable to resolve host|unknownhost|connectexception|sockettimeoutexception|timed? out|network is unreachable|connection reset|connection refused|网络|连接失败|无法连接/i;

// 把桥上抛上来的原始错误翻译成人话。原文太技术（「接口响应缺少 data」「HTTP 404」），
// 直接糊用户一脸没有信息量；原文收进小字里，排查时还有得看。

const ERROR_ICONS = {
  network: '<svg class="error-icon" viewBox="0 0 48 48" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="24" cy="24" r="21"/><path d="m12 24 6-5 6 5 6-5 6 5"/></svg>',
  gone: '<svg class="error-icon" viewBox="0 0 48 48" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="24" cy="24" r="21"/><circle cx="18" cy="21" r="1.2" fill="currentColor" stroke="none"/><circle cx="30" cy="21" r="1.2" fill="currentColor" stroke="none"/><path d="M17 31c2-2.4 4.4-3.6 7-3.6s5 1.2 7 3.6"/></svg>',
  slow: '<svg class="error-icon" viewBox="0 0 48 48" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="24" cy="24" r="21"/><path d="M24 13v11l7 5"/></svg>',
  alert: '<svg class="error-icon" viewBox="0 0 48 48" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="24" cy="24" r="21"/><path d="M24 14v13"/><circle cx="24" cy="33" r="1.2" fill="currentColor" stroke="none"/></svg>'
};

// 真的会跳的自动重试。原错误页写着「正在自动重试…」其实什么都没重试 —— 用户干等。
// 只对安全页面（首页/分类/标签这类）补刀；详情和搜索有自己的重试节奏，不掺和。
const errorAutoRetry = { timer: 0 };


let viewEnterTimer;
/**
 * 切页的入场动画 —— **已停用**（2026-10-19）。
 *
 * <p>它原来是每次路由变化都给 {@code #content} 的子元素挂一段 0.4s 的
 * {@code view-enter}（从 opacity 0 平移 14px 浮上来）。三个问题叠在一起：
 *
 * <ol>
 *   <li>内容要花 400ms 才「到位」，点一下之后眼睛看着它慢慢浮，体感就是迟滞；</li>
 *   <li>为了能重启动画，连点时每次都要 {@code void app.offsetWidth} ——
 *       那是一次**强制同步布局**，正好发生在用户刚点下去、最不该卡的那一帧；</li>
 *   <li>连点 N 次就有 N 段动画排着队，屏幕上同时有好几层在动。</li>
 * </ol>
 *
 * <p>切页要的是「立刻到位」。淡入留给详情页那种真正的转场（card-transition 管）。
 */


function isRootColumn() { return ['home', 'category', 'library'].includes(state.route); }


window.hgPredictiveBackStart = fromRight => {
  clearPredictivePreview();
  if (state.route === 'detail' && detailCard && authOverlay.hidden && confirmOverlay.hidden && unlockOverlay.hidden) {
    detailCard.fromRight = fromRight;
    return detailCard.begin();
  }
  const destination = backDestination();
  const fromDetail = state.route === 'detail' && state.previous?.route === destination && state.previous.pageNode;
  const snapshot = fromDetail ? state.previous : screenSnapshots[destination];
  if (!snapshot) return false;
  const preview = document.createElement('div');
  preview.className = 'back-preview';
  preview.setAttribute('aria-hidden', 'true');
  if (state.route === 'detail' || state.route === 'settings') {
    const header = document.querySelector('#app > .app-top').cloneNode(true);
    const settings = header.querySelector('.header-settings');
    settings.hidden = destination !== 'library';
    if (destination === 'library') {
      const input = header.querySelector('.search-input');
      input.placeholder = '搜索观看历史和收藏';
      input.value = state.libraryQuery;
    }
    header.querySelectorAll('[id]').forEach(node => node.removeAttribute('id'));
    preview.appendChild(header);
  }
  if (fromDetail) {
    const holder = document.createElement('div');
    holder.style.transform = `translateY(${-snapshot.scrollTop}px)`;
    predictiveSourceNode = snapshot.pageNode;
    holder.appendChild(predictiveSourceNode);
    preview.appendChild(holder);
  } else {
    predictiveSourceNode = snapshot.content;
    predictiveSourceNode.style.transform = `translateY(${-snapshot.scrollTop}px)`;
    preview.appendChild(predictiveSourceNode);
  }
  document.getElementById('app').appendChild(preview);
  preview.querySelectorAll('.film-row,.filter-row,.home-tag-scroll').forEach((row, index) => {
    row.scrollLeft = snapshot.horizontalScroll?.[index] || 0;
  });
  document.getElementById('app').classList.add('predictive-active');
  scroll.style.transition = 'none';
  predictivePreview = preview;
  predictivePreview.fromRight = fromRight;
  return true;
};

window.hgPredictiveBackProgress = progress => {
  if (state.route === 'detail' && detailCard?.phase === 'dragging') {
    detailCard.predictive(Math.max(0, Math.min(1, progress)), detailCard.fromRight);
    return;
  }
  if (!predictivePreview) return;
  pendingPredictiveProgress = Math.max(0, Math.min(1, progress));
  if (predictiveFrame) return;
  predictiveFrame = requestAnimationFrame(() => {
    predictiveFrame = null;
    if (!predictivePreview) return;
    const direction = predictivePreview.fromRight ? -1 : 1;
    scroll.style.transform = `translate3d(${direction * pendingPredictiveProgress * 100}%,0,0)`;
  });
};

window.hgPredictiveBackCancel = () => {
  if (state.route === 'detail' && detailCard?.phase === 'dragging') { detailCard.cancel(); return; }
  if (!predictivePreview) return;
  if (predictiveFrame) cancelAnimationFrame(predictiveFrame);
  predictiveFrame = null;
  scroll.style.transition = 'transform 180ms ease-out';
  scroll.style.transform = 'translate3d(0,0,0)';
  predictiveTimer = setTimeout(clearPredictivePreview, 180);
};

window.hgPredictiveBackCommit = () => {
  if (state.route === 'detail' && detailCard && authOverlay.hidden && confirmOverlay.hidden && unlockOverlay.hidden) {
    detailCard.close(); return true;
  }
  if (!predictivePreview) return window.hgBack();
  if (predictiveFrame) cancelAnimationFrame(predictiveFrame);
  predictiveFrame = null;
  const direction = predictivePreview.fromRight ? -1 : 1;
  scroll.style.transition = 'transform 150ms ease-out';
  scroll.style.transform = `translate3d(${direction * 100}%,0,0)`;
  predictiveTimer = setTimeout(() => {
    const oldPage = content.firstElementChild;
    if (!window.hgBack()) { clearPredictivePreview(); return; }
    if (content.firstElementChild !== oldPage) { clearPredictivePreview(); return; }
    // 只收自己这一手势的尾：predictivePreview 被新手势换掉后就不再动全局，
    // 否则一次滞留的收尾会把它正在拖的预览一把清掉。
    const previewAtCommit = predictivePreview;
    const settleBack = () => {
      changed.disconnect();
      if (predictivePreview === previewAtCommit) clearPredictivePreview();
    };
    const changed = new MutationObserver(() => {
      if (content.firstElementChild === oldPage) return;
      settleBack();
    });
    changed.observe(content, { childList: true });
    // 兜底断开必须挂局部定时器：predictiveTimer 是共享的，下一次手势的
    // clearPredictivePreview 会把它清掉，这个 observer 就永远没人断开了。
    setTimeout(settleBack, 1500);
  }, 150);
  return true;
};

/**
 * 换底部高亮。
 *
 * `keepState` 是给「折叠面板里换分类」用的：那种场景是从分类页**内部**切换，
 * 用户没有离开当前滚动位置，所以不能顺手把折叠面板收掉、把滚动条弹回顶部 ——
 * 那会让他在第八十部作品上点一下「AI」，回来发现位置没了。
 *
 * 但**高亮必须跟着换**。原来这个函数被整个跳过（`if (!keepCompact) setNav(tab)`），
 * 于是折叠面板里点分类，页面内容换了、底部还亮着上一个 tab —— 就是
 * 「页面换过去了按钮没换过去」。
 */
/**
 * 源连不上时的冷却窗口。
 *
 * <p>2026-10-19 实测：手机网络到 {@code api.agedm.io} 的 HTTPS 在 TLS 之后被掐
 * （curl 直接 `Empty reply from server`，同一台机器上百度/GitHub 都是 200）。
 * 这种情况下每次切页都要重新撞一次墙：一个请求 1.8 秒，还带 2s/5s/12s 的退避重试。
 *
 * <p>用户看不到「连不上」，只看到「点了半天没反应」。所以失败之后闭嘴一分钟：
 * 直接用缓存渲染，不再发请求，也不再重试。用户这时换源或开代理，下一次正常。
 */
const NET_COOLDOWN_MS = 60000;

/**
 * 分类页的静默预热。
 *
 * <p>本地实测（2026-10-19 真机）：同一个分类第二次点进来是 **14~25ms**，第一次是
 * **1679ms**。差的全部是 spider 那一趟网络。也就是说「快」和「慢」的唯一区别是
 * 缓存里有没有这份数据。
 *
 * <p>那就提前把它填好：用户停在分类页 2.5 秒不动，后台把当前 tab 下**其他**分类的
 * 第一页各拉一遍，写进同一套缓存。之后点哪个都是十几毫秒。
 *
 * <p>三条约束：
 * <ul>
 *   <li>只拉当前 tab 的，且跳过还新鲜的缓存；</li>
 *   <li>每拉一个停 400ms，把 spider 那条串行通道让出来 —— 用户这时候点别的，
 *       最多等一次往返，而不是排在一串预取后面；</li>
 *   <li>路线一换（切 tab、离开分类页）立刻停，已经发出去的请求会被世代机制作废。</li>
 * </ul>
 */
let warmupTimer = 0;
let warming = false;


/**
 * 把「当前选中哪个子分类」立刻画到界面上。
 *
 * <p>这件事原来只发生在 {@code renderCategory()} 生成 HTML 的那一刻。于是有缓存时
 * 点下去就有反馈（几百毫秒内重画），**没缓存时走进度条分支、不重建 DOM，子 tab 的
 * 高亮就一直停在旧的那个**，直到 1.5 秒后数据回来。
 *
 * <p>用户看到的：点了「运动」，那一排还是「搞笑」亮着，页面也没动 —— 判断成「没点中」，
 * 再点一次。连点几次之后彻底分不清自己在哪一屏。所谓「连续点击很卡」，核心是这个地方。
 *
 * <p>所以选中态必须跟手：切换时同步改 class，与数据到不到无关。
 */


/**
 * 分类切换时的顶栏进度条。
 *
 * <p>只做视觉，不挡交互：内容仍然是上一屏，用户还能继续滑动、还能再点一次。
 * 这比「把整屏清成骨架屏」好一个量级 —— 后者在 1.5 秒里什么都点不了，
 * 用户对「卡」的全部感受就来自那一段。
 */

/**
 * 手指一落下就把底部高亮换过去。
 *
 * <p>为什么不用 click：click 要等 touchend 之后（实测 4ms，本身不慢），但它排在
 * 整个页面共用的事件处理器最后面 —— 那个处理器要从上到下走几十个 {@code closest()}
 * 才轮到 `[data-nav]`。主线程只要在忙（封面解密回来的那一批回调），这段排队就会
 * 变成肉眼可见的「按下去按钮不动」。
 *
 * <p>pointerdown 比 click 早一个阶段（手指按下就发），而且这里只改一个 class，
 * 不进任何网络/渲染路径。真正切页仍然由后面的 click → tapDock → goXxx 完成，
 * 所以按下划走也不会误导航 —— 最多留下一格高亮，下一次导航自己会纠正。
 */


// 详情请求的统一入口：同 id 在飞请求直接复用，成功后**无条件**落缓存 ——
// 原来写缓存在「用户还停在详情页」的判断里，等不及退出来，这次抓取就白跑了，
// 再进去还是从零等。预取（pointerdown）和 goDetail 都走这里。
const detailInflight = new Map();


window.hgBack = () => {
  // 源设置页挡在最前面。这里必须 return true（告诉 Java「我处理了」）而不是
  // 什么都不做就往下走 —— 往下走会落到最后那句 return false，Java 就退出应用了。
  // 用户按返回多半是想收键盘或误触，不该被踢回桌面；而且再进来还是同一屏，
  // 看着像应用坏了。所以这里吞掉返回，不做任何可见动作。
  const setupOverlayForBack = document.getElementById('setup-overlay');
  if (setupOverlayForBack && !setupOverlayForBack.hidden) return true;
  if (!unlockOverlay.hidden) { closeUnlock(); return true; }
  if (!authOverlay.hidden) { hideAuth(); return true; }
  if (!confirmOverlay.hidden) { closeConfirmation(false); return true; }
  // 播放层是独立的全屏图层，盖在 #content 之上，必须在这里显式收掉，
  // 否则路由已经切走了、播放器还在上面盖着，看着像"返回没反应"。
  if (state.route === 'feed' || document.querySelector('.feed')) return feedClose();
  if (isRootColumn()) return false;
  if (state.route === 'campaign') { restoreScreen(state.campaignReturn); return true; }
  if (state.route === 'account-management') { restoreScreen(state.accountReturn); return true; }
  // 设置的四个子页（源/代理/播放器/市场）各有自己的返回快照，之前漏接，
  // 全掉进最后的 goHome() 兜底，「返回设置」就变成「返回首页」了。
  if (state.route === 'source-settings') { restoreScreen(state.sourceReturn); return true; }
  if (state.route === 'proxy-settings') { restoreScreen(state.proxyReturn); return true; }
  if (state.route === 'player-settings') { restoreScreen(state.playerReturn); return true; }
  if (state.route === 'cover-diagnostics') { restoreScreen(state.coverDiagReturn); return true; }
  if (state.route === 'market') { restoreScreen(state.marketReturn); return true; }
  if (state.route === 'settings') { restoreScreen(state.settingsReturn); return true; }
  if (state.route === 'detail') { if (detailCard) detailCard.close(); else returnFromDetail(); return true; }
  if (state.route === 'listing' && state.listing?.kind === 'tag-list') { restoreScreen(state.listingReturn); return true; }
  if (state.route === 'tags') { restoreScreen(state.tagsReturn); return true; }
  if (state.route === 'listing') { goHome(); return true; }
  if (state.route === 'search') { searchInput.value = ''; goHome(); return true; }
  if (state.route !== 'home') { goHome(); return true; }
  return false;
};
window.hgResume = () => {
  cover.retry(true);
  // 从内置播放器（VlcPlayerActivity）回来：把那一场的位置收下、写回进度。
  // 放在这儿而不是只听 visibilitychange：WebView 被 Activity 盖住时
  // document.hidden 经常一直是 false，那条事件根本不发。
  // 这里抛出去会让整个 hgResume 中断，route 就永久停在 'feed' ——
  // 之后每次按返回都只会被 feedClose 拨回详情页，用户看到的就是「返回没反应」。
  try { feedPlayer.resume?.(); } catch (error) { console.log('[resume] 收进度失败：' + (error.message || error)); }
  // 播放全程在原生里，页面这边没有 feed 页可回 —— 退出播放器后落回详情页。
  // 先把 route 归位、清掉「正在打开播放器…」boot 残页，再进 goDetail：
  // goDetail 会把当前屏幕抓成 state.previous，若不先归位，残页会顶掉真正的
  // 返回目标，返回时还原出一屏 boot 残页，和选集来回死循环。
  if (state.route === 'feed' && !document.querySelector('.feed')) {
    feedPlayer.cleanup();
    state.route = 'detail';
    const back = state.feedReturnId || state.detail?.id;
    if (back) goDetail(back); else goHome();
    return;
  }
  if (state.route === 'library') goLibrary(state.libraryTab);
  if (state.route === 'home') refreshHomeHistory();
  if (state.route === 'detail' && state.detail) {
    // 回包可能晚于用户离开详情页（桥池被慢请求占满时要排队）。没有守卫的话
    // renderDetail() 会把详情页盖到当前路由上；catch 也不能省，失败即 unhandled rejection。
    const token = routeToken;
    api('bookmark-state', { id: state.detail.id })
      .then(value => {
        if (token !== routeToken || state.route !== 'detail') return;
        state.bookmarked = value;
        renderDetail();
      })
      .catch(() => {});
  }
};

// ---- 竖屏沉浸流：一屏一集，上下滑切换，自动续播 ----
// 播放器逻辑已拆到 feed-player.js；app.js 只保留路由入口，避免主文件继续膨胀。
const feedPlayer = window.HGFeedPlayer.create({
  state, content, api, asArray, esc, icon, toast, readCache,
  // bridgeCall 是给播放进度上报用的（feed-player 里的 report()）。
  // 它走的是 detail 那条桥上的 __progress: 指令，不是新动作。
  bridgeCall,
  // 亮度走原生（CSS 蒙黑只能变暗），清晰度要取 m3u8 文本（页面 fetch 不了）——
  // 两件都要过桥，包一层在这里给播放器用。
  playerCommand,
  renderDetail, goHome, updateBackEnabled
});

// openPlayer() 已删除（2026-10-16）。它是接原生 PlayerActivity 的入口，
// 而那个类连同 59 个附属类一起摘掉了（63 个文件 660KB），播放走 feedFrom()。
// 桥动作 open-player 在 Java 侧还留着编号（packed-switch 的索引不能动），
// 但 body 已收成空返回，所以就算有人再调也不会启动任何 Activity。


document.getElementById('search-form').addEventListener('submit', event => {
  event.preventDefault();
  const query = searchInput.value.trim();
  searchInput.blur();
  if (['library', 'settings', 'account-management'].includes(state.route)) {
    state.libraryQuery = query;
    if (state.route === 'library') renderLibrary();
    else goLibrary(state.libraryTab);
    scroll.scrollTop = 0;
    return;
  }
  if (/^[#＃]/.test(query)) goTagQuery(query);
  else if (query) goSearch(query);
});
searchInput.addEventListener('input', () => {
  if (state.route !== 'library') { syncSearchHistory(); return; }
  state.libraryQuery = searchInput.value;
  renderLibrary();
  scroll.scrollTop = 0;
});

// ---- 搜索历史的交互 ------------------------------------------------------
// 焦点进来就弹（框里没字、有历史、不是「我的」那几屏）。
searchInput.addEventListener('focus', showSearchHistory);
// 转屏、系统字体变化之后搜索框的 rect 会变，面板还开着就跟着重新贴一次。
window.addEventListener('resize', () => { if (!searchHistoryPanel.hidden) alignSearchHistory(); });
// 点历史项会先让输入框失焦、键盘收起，此时 click 还能正常派发；所以收起面板
// 认「手指按在哪儿」，不认 blur —— 照 blur 收的话，按下那一刻面板就没了，
// 手指抬起来落在空处，点了没反应。
document.addEventListener('pointerdown', event => {
  if (searchHistoryPanel.hidden) return;
  if (searchHistoryPanel.contains(event.target) || searchForm.contains(event.target)) return;
  hideSearchHistory();
}, true);
searchHistoryTags.addEventListener('click', event => {
  const button = event.target.closest('button[data-history-word]');
  if (!button) return;
  const word = button.dataset.historyWord || '';
  if (!word) return;
  hideSearchHistory();
  searchInput.value = word;
  searchInput.blur();
  if (/^[#＃]/.test(word)) goTagQuery(word);
  else goSearch(word);
});
searchHistoryClear.addEventListener('click', async () => {
  const accepted = await askConfirmation('清空搜索历史', '清空之后，这些关键词不会再出现在搜索框下面。', '清空');
  if (!accepted) return;
  try { localStorage.removeItem(SEARCH_HISTORY_KEY); } catch (error) {}
  hideSearchHistory();
  toast('搜索历史已清空');
});

document.addEventListener('submit', async event => {
  if (event.target.id === 'proxy-form') {
    event.preventDefault();
    const input = document.getElementById('proxy-value');
    const error = document.getElementById('proxy-error');
    const button = event.target.querySelector('button[type="submit"]');
    if (!input || !button) return;
    button.disabled = true;
    button.textContent = '正在保存…';
    if (error) error.hidden = true;
    try {
      const saved = await sourceCommand('proxy:set?value=' + encodeURIComponent(input.value.trim()));
      state.proxy = String(saved.proxy ?? '');
      setRoute('settings'); renderSettings();
      toast(state.proxy ? '已走代理 ' + state.proxy : '已改回直连');
    } catch (problem) {
      if (error) { error.textContent = problem.message; error.hidden = false; }
    } finally {
      if (button.isConnected) { button.disabled = false; button.textContent = '保存'; }
    }
    return;
  }
  if (event.target.id === 'source-form') {
    event.preventDefault();
    const input = document.getElementById('source-value');
    const aliasInput = document.getElementById('source-alias');
    const error = document.getElementById('source-error');
    const button = event.target.querySelector('button[type="submit"]');
    if (!input || !button) return;
    const url = input.value.trim();
    const alias = String(aliasInput?.value || '').trim();
    // 一个入口两条路，按后缀分流（规则和理由都在 source-market.js 的 sourceKindOf）。
    // .js 装的是 spider 源码，它不切换源 —— 所以这一步不出重启，只回一句「还差配置」。
    const kind = sourceKindOf(url);
    if (!/^https:\/\//i.test(url)) {
      if (error) { error.textContent = '地址必须以 https:// 开头'; error.hidden = false; }
      return;
    }
    if (kind === 'spider') {
      button.disabled = true;
      button.textContent = '正在安装源码…';
      let hint = '';
      try {
        const meta = await installSpiderSource(url);
        const name = meta?.name || meta?.id || '源';
        hint = '已装源码 ' + name + '。它不会自己切换 —— 再贴这份源对应的 .json 配置（里面写着 web.spider）才会用上。';
        input.value = '';
        toast('已装 spider 源码 ' + name, true);
      } catch (problem) {
        hint = '安装失败：' + (problem.message || problem);
      }
      if (state.route === 'source-settings') renderSourceSettings();
      // 重画之后必须重新取节点：上面那个 `error` 已经随着旧 DOM 一起被换掉了。
      const fresh = document.getElementById('source-error');
      if (fresh) { fresh.textContent = hint; fresh.hidden = false; }
      const freshButton = document.querySelector('#source-form button[type="submit"]');
      if (freshButton) { freshButton.disabled = false; freshButton.textContent = '添加并切换'; }
      return;
    }
    button.disabled = true;
    button.textContent = '正在拉取…';
    if (error) error.hidden = true;
    try {
      // 新填进来的地址必然没有本地缓存，这一次一定要等网络（也就这一次）。
      await sourceCommand('use?url=' + encodeURIComponent(url));
      // 备注单独一条指令写：用户可以随时改，且地址本身不认识它。
      // 写失败不该拦住切换 —— 备注只是给人看的。
      if (alias) await sourceCommand('note?url=' + encodeURIComponent(url) + '&value=' + encodeURIComponent(alias)).catch(() => {});
      restartAfterSource(() => {
        if (error) { error.textContent = '已切换，但没能自动重开，请退出后重新打开'; error.hidden = false; }
        button.disabled = false;
        button.textContent = '添加并切换';
      });
    } catch (problem) {
      if (error) { error.textContent = problem.message; error.hidden = false; }
      button.disabled = false;
      button.textContent = '添加并切换';
    }
    return;
  }
  if (event.target.id === 'market-form') {
    event.preventDefault();
    const input = document.getElementById('market-value');
    const error = document.getElementById('market-error');
    const button = event.target.querySelector('button[type="submit"]');
    const url = String(input?.value || '').trim();
    if (!/^https:\/\//.test(url)) {
      if (error) { error.textContent = '地址必须以 https:// 开头'; error.hidden = false; }
      return;
    }
    button.disabled = true; button.textContent = '正在获取…';
    setMarketSource(url);
    state.market = { loading: true };
    renderMarket();
    loadMarket(true).then(cache => {
      state.market = { loading: false, items: cache.items };
      if (state.route === 'market') renderMarket();
    }).catch(failure => {
      state.market = { loading: false, error: failure.message || '获取失败' };
      if (state.route === 'market') renderMarket();
    });
    return;
  }
  const noteForm = event.target.closest('[data-source-note-form]');
  if (noteForm) {
    event.preventDefault();
    await saveSourceNote(noteForm.dataset.sourceNoteForm, noteForm.querySelector('#source-note')?.value);
    return;
  }
  // 内置核心的节点配置。和上面 proxy-form 的区别：这条不仅要存，还要**启动**，
  // 而且失败时不改路由 —— Java 那边起不来就原样回来一句错，页面只显示不动别的。
  if (event.target.id === 'proxy-core-form') {
    event.preventDefault();
    const input = document.getElementById('proxy-link');
    const error = document.getElementById('proxy-core-form-error');
    const button = event.target.querySelector('button[type="submit"]');
    if (!input || !button) return;
    button.disabled = true;
    button.textContent = '正在启动…';
    if (error) error.hidden = true;
    try {
      const status = await proxyCoreCommand('start?link=' + encodeURIComponent(input.value.trim()));
      applyProxyCore(status);
      if (status.ok === false) throw new Error(status.error || '核心没能启动');
      toast(status.running ? '已连接 ' + (status.host || '') : '已保存，但核心没起来');
    } catch (problem) {
      if (error) { error.textContent = problem.message; error.hidden = false; }
    } finally {
      if (button.isConnected) { button.disabled = false; button.textContent = '保存并启动'; }
    }
    return;
  }
  // 订阅导入。拉取和测速分成两步：拉取几秒就回来，测速有大半分钟的可能，
  // 合起来会让用户点一次等到底还不知道卡在哪一步。
  if (event.target.id === 'proxy-sub-form') {
    event.preventDefault();
    const input = document.getElementById('proxy-sub-url');
    const statusLine = document.getElementById('proxy-sub-status');
    const button = event.target.querySelector('button[type="submit"]');
    if (!input || !button) return;
    const url = input.value.trim();
    if (!url) { toast('先填一个订阅地址', true); return; }
    // http 也要放行：2.0.4 起明文策略已经放开，自建服务和一部分机场只给
    // http://IP:port/p/xxxx。以前这里只认 https://，等于底层允许、表单拦着。
    if (!/^https?:\/\//i.test(url)) { toast('地址要以 http:// 或 https:// 开头', true); return; }
    button.disabled = true;
    button.textContent = '正在拉取…';
    const sub = subState();
    const routeAtSubmit = state.route;
    if (statusLine) { statusLine.hidden = false; statusLine.classList.remove('is-bad'); statusLine.textContent = '正在拉取订阅…'; }
    try {
      const result = await proxyCoreCommand('sub?url=' + encodeURIComponent(url));
      if (result.ok === false) throw new Error(result.error || '订阅没拉到');
      // 换了订阅，上一次的测速结果整套作废 —— 下标对不上了。
      // 注意 busy 必须是 false：那一格现在只管「节点库正在更新」，
      // 测速的进行状态在 subTesting / freeTesting 里，两份状态各管各的。
      // 只换订阅这一半，免费节点那份（nodes / nodesNote / freeSpeed）原样留着。
      // 以前这里整块重建 state，把节点库状态一起清成 null，于是「免费节点」那行
      // 从「维护于 2026-10-18 · 7 条」退化成「节点库：读取中…」，而没人再去读。
      const prev = subState();
      state.proxySub = Object.assign({}, prev, {
        url: result.url || url, count: result.count || 0,
        summary: result.summary || '', busy: false,
        // 拉到了就已经有节点数了，这句比「正在测速，最多 20 秒」有信息量：
        // 慢的是下载那一步（机场 TTFB 能到 10 秒），测本身一两秒。
        subSpeed: [], subNote: '订阅已拉到 ' + (result.count || 0) + ' 个节点，正在测速…',
        // **不能**在这里预置 subTesting: true。
        //
        // 下面紧接着就是 runSpeedTest('subscription')，而它的第一句是
        // 「已经在测就直接 return」。于是它一次都不会跑：finally 不执行、
        // subTesting 永远停在 true、按钮永久「测速中…」且 disabled，只有刷新
        // 页面才解得开。用户实测这一屏挂了快一小时，还以为是测速慢。
        // 2.0.24 及以前这条「拉完顺手测一次」一直是坏的 —— 拉取本身是好的，
        // 坏的是那句「正在测速」的承诺从来没兑现过。
        // 让 runSpeedTest 自己管这个开关：它会立刻置 true 并重画。
        subTesting: false, subBad: false,
        freeTesting: false,
      });
      // await 期间用户可能已经离开代理设置页，别把旧页面盖到新路由上。
      if (state.route === routeAtSubmit) renderProxySettingsKeepScroll();
      // 拉完直接测这份订阅，用户不用再点一次 —— 「拉取」这个动作的意图就是要连上。
      runSpeedTest('subscription');
    } catch (problem) {
      if (statusLine) { statusLine.hidden = false; statusLine.classList.add('is-bad'); statusLine.textContent = problem.message; }
      button.disabled = false;
      button.textContent = '拉取节点';
    }
    return;
  }
  if (event.target.id !== 'password-form') return;
  event.preventDefault();
  const form = event.target;
  const oldPassword = form.querySelector('#old-password').value;
  const newPassword = form.querySelector('#new-password').value;
  const repeated = form.querySelector('#confirm-password').value;
  const error = form.querySelector('#password-error');
  const button = form.querySelector('[type="submit"]');
  error.hidden = true;
  if (newPassword !== repeated) { error.textContent = '两次输入的新密码不一致'; error.hidden = false; return; }
  if (newPassword === oldPassword) { error.textContent = '新密码不能与旧密码相同'; error.hidden = false; return; }
  button.disabled = true;
  try { setAccount(await api('change-password', { oldPassword, newPassword })); toast('密码已修改'); }
  catch (failure) { error.textContent = failure.message || '修改失败，请稍后重试'; error.hidden = false; button.disabled = false; }
});


document.addEventListener('click', async event => {
  const campaignButton = event.target.closest('[data-open-campaign]');
  if (campaignButton) {
    if (campaignButton.closest('.hero') && performance.now() < heroSuppressClickUntil) return;
    const item = activeCampaigns().find(item => item.id === campaignButton.dataset.openCampaign);
    if (item) goCampaign(item);
    return;
  }
  // 必须限定 button：主题偏好在 <html> 上另有一个 data-theme-pref，
  // 但曾经它叫 data-theme-mode，结果 closest() 把每一次点击都匹配到 <html>，
  // 整个应用的点击全被主题切换吞掉。这里再挡一道。
  const themeChoice = event.target.closest('button[data-theme-mode]');
  if (themeChoice) {
    if (themeChoice.getAttribute('aria-checked') !== 'true') setThemeMode(themeChoice.dataset.themeMode);
    return;
  }
  if (event.target.closest('[data-goto-app-update]')) {
    // 这条链已经并成一行了，不用再滚动定位；直接查。
    checkAppUpdate();
    return;
  }
  // data-secret-entry（设置页的版本图标）：连点进私密库的入口删了，
  // handleVersionTap 一并删掉。按钮还在渲染，点击落到底就是无操作。
  if (event.target.closest('[data-account-management]')) { goAccountManagement(); return; }
  if (event.target.closest('[data-proxy-settings]')) { goProxySettings(); return; }
  if (event.target.closest('[data-player-settings]')) { goPlayerSettings(); return; }
  if (event.target.closest('[data-cover-diagnostics]')) { goCoverDiagnostics(); return; }
  const playerSet = event.target.closest('[data-player-set]');
  if (playerSet) {
    const [key, value] = String(playerSet.dataset.playerSet || '').split(':');
    if (key === 'core') savePlayerPrefs({ core: value });
    else if (key === 'decode') savePlayerPrefs({ decode: value });
    else if (key === 'cache') savePlayerPrefs({ cache: Number(value) });
    else if (key === 'autoFallback') savePlayerPrefs({ autoFallback: value === 'on' });
    renderPlayerSettings();
    return;
  }
  const proxyModeButton = event.target.closest('[data-proxy-mode]');
  if (proxyModeButton) {
    const value = proxyModeButton.dataset.proxyMode;
    // 已经是这个模式就别再走一趟：切到 builtin 会真的去启核心，
    // 而用户点一下已选中的按钮只是想「确认」，不该触发一次启停。
    if (value === (proxyState().mode || 'direct')) return;
    proxyModeButton.disabled = true;
    proxyCoreCommand('mode?value=' + encodeURIComponent(value))
      .then(applyProxyCore)
      .catch(problem => toast(problem.message, true))
      .then(() => { if (state.route === 'proxy-settings') renderProxySettings(); });
    return;
  }
  const coreAction = event.target.closest('[data-proxy-core]');
  if (coreAction) {
    const action = coreAction.dataset.proxyCore;
    coreAction.disabled = true;
    if (action === 'stop') {
      proxyCoreCommand('stop')
        .then(applyProxyCore)
        .catch(problem => toast(problem.message, true));
      return;
    }
    // 测试连通性单独一条：它不重画整页（那样会把刚写进 status 的毫秒数擦掉），
    // 只把结果写到下面那行提示里。
    const label = document.getElementById('proxy-core-test');
    if (label) { label.hidden = false; label.classList.remove('is-bad'); label.textContent = '正在测…'; }
    proxyCoreCommand('test').then(result => {
      if (!label) return;
      label.hidden = false;
      if (result.ok && Number(result.ms) > 0) {
        label.classList.remove('is-bad');
        label.textContent = `通了 · ${result.ms}ms`;
      } else {
        label.classList.add('is-bad');
        label.textContent = result.error || '测不通';
      }
    }).catch(problem => {
      if (label) { label.hidden = false; label.classList.add('is-bad'); label.textContent = problem.message; }
    }).then(() => { coreAction.disabled = !proxyState().running; });
    return;
  }
  // ── 订阅 / 测速 / 选节点 ──────────────────────────────────────────
  // 这三个必须挂在 click 上。上一版误放在 submit 监听里，而它们是
  // <button type="button">，根本不触发 submit —— 表现是「点了没反应」。
  const subAction = event.target.closest('[data-proxy-sub]');
  if (subAction) {
    const action = subAction.dataset.proxySub;
    if (action === 'clear') {
      // 这一步会把订阅地址和它的测速结果一起清掉，只留一个「改回免费」的小胶囊
      // 杵在那儿，误触一次就没了。先问一句。
      if (!await askConfirmation('改回用免费节点', '会清空已导入的订阅地址和它测出来的节点，之后可以重新粘贴。', '清空订阅')) return;
      subAction.disabled = true;
      proxyCoreCommand('subclear').then(() => {
        state.proxySub = {
          url: '', count: 0, summary: '', busy: false,
          nodes: null, nodesBusy: false, nodesNote: '',
          subSpeed: [], subNote: '', subTesting: false,
          freeSpeed: [], freeNote: '', freeTesting: false,
        };
        renderProxySettingsKeepScroll();
        loadNodesStatus();
        toast('已改回用内置的免费节点');
      }).catch(problem => { toast(problem.message, true); subAction.disabled = false; });
      return;
    }
    if (action === 'nodes-refresh') {
      refreshNodesFeed();
      return;
    }
    if (action === 'speed-sub') { runSpeedTest('subscription'); return; }
    runSpeedTest('builtin');
    return;
  }
  const useNode = event.target.closest('[data-proxy-use]');
  if (useNode) {
    const index = useNode.dataset.proxyUse;
    useNode.disabled = true;
    try {
      const status = await proxyCoreCommand('use?index=' + encodeURIComponent(index));
      if (status.ok === false) throw new Error(status.error || '这个节点没连上');
      applyProxyCore(status);
      toast(status.running ? '已连接 ' + (status.host || '') : '已切换');
    } catch (problem) {
      toast(problem.message, true);
      useNode.disabled = false;
    }
    return;
  }

  if (event.target.closest('[data-source-settings]')) { goSourceSettings(); return; }
  if (event.target.closest('[data-market]')) { goMarket(); return; }
  if (event.target.closest('[data-market-reload]')) { goMarket(); return; }
  if (event.target.closest('[data-market-change]')) { setMarketSource(''); state.market = null; renderMarket(); return; }
  const marketUse = event.target.closest('[data-market-use]');
  if (marketUse) {
    const url = marketUse.dataset.marketUse;
    marketUse.disabled = true; marketUse.textContent = '正在切换…';
    addSourceFromMarket(url).catch(error => { toast(error.message, true); marketUse.disabled = false; marketUse.textContent = '添加并使用'; });
    return;
  }
  if (event.target.closest('[data-source-forget]')) {
    const dropButton = event.target.closest('[data-source-forget]');
    const dropUrl = dropButton.dataset.sourceForget;
    if (!dropUrl) return;
    const routeAtClick = state.route;
    dropButton.disabled = true;
    try {
      await sourceCommand('forget?url=' + encodeURIComponent(dropUrl));
      await refreshSources();
      // await 期间用户可能已经离开源设置页，别把旧页面盖到新路由上。
      if (state.route === routeAtClick) renderSourceSettings();
      toast('已删除');
    } catch (problem) { toast(problem.message, true); }
    return;
  }
  if (event.target.closest('[data-source-refetch]')) {
    const refetchButton = event.target.closest('[data-source-refetch]');
    const refetchUrl = refetchButton.dataset.sourceRefetch;
    if (!refetchUrl || refetchButton.disabled) return;
    refetchButton.disabled = true;
    const status = document.getElementById('source-error');
    if (status) { status.hidden = false; status.textContent = '正在重新拉取…'; }
    try {
      // 两步，顺序不能反。
      //
      // refresh 是「把服务器上那份重新下下来，只写本地缓存，不动生效那份」——
      // 它本来就是为了后台悄悄更新缓存而写的，单独的它救不了「我现在就要用上」。
      // 跟着的 use 再从缓存取一遍：刚写进去的正好是新的，于是这次切源拿到的
      // 就是新配置，顺带把重启也带了。
      //
      // 想一步到位得给 Java 加个新动作，那要同时改 MainActivity 的 packed-switch
      // 两张表 —— 本工程最容易出事的地方，为这点事不值得。
      await sourceCommand('refresh?url=' + encodeURIComponent(refetchUrl));
      if (status) status.textContent = '已拉回来，正在应用…';
      // 配置拉完还得把脚本补上。
      //
      // 爬虫是刻意不随 APK 打包的（SpiderStore 只扫 files/spiders/），所以
      // 「重新拉配置」如果只拉 json，用户拿到的是一份指向不存在脚本的配置 ——
      // 之后每一次内容请求都报「找不到 spider: xxx」，而这个按钮看起来明明成功了。
      //
      // addOneSource 里已经写了「读 web.spider → 下载那份 js → 落盘」那一步，
      // 直接复用它，不另写一份。
      if (window.HGSetup && typeof window.HGSetup.addOneSource === 'function') {
        await window.HGSetup.addOneSource(refetchUrl).catch(() => {});
      }
      const result = await sourceCommand('use?url=' + encodeURIComponent(refetchUrl));
      const spent = Number(result && result.ms) || 0;
      // use 走了缓存分支（cached=true）是**正常的**：新配置刚被 refresh 写进缓存，
      // 这里就是要那份。所以不按「本地缓存/刚拉了」措辞，直接说清楚做过什么。
      if (status) status.textContent = `已重新拉取并应用（${spent}ms），正在重启…`;
      restartAfterSource(() => {
        if (status) status.textContent = '已重新拉取，但没能自动重开，请退出后重新打开';
        refetchButton.disabled = false;
      });
    } catch (problem) {
      if (status) status.textContent = problem.message;
      refetchButton.disabled = false;
    }
    return;
  }
  const noteEdit = event.target.closest('[data-source-note]');
  if (noteEdit) {
    state.sourceNote = noteEdit.dataset.sourceNote;
    renderSourceSettings();
    content.querySelector('#source-note')?.focus();
    return;
  }
  if (event.target.closest('[data-source-note-cancel]')) {
    state.sourceNote = '';
    renderSourceSettings();
    return;
  }
  const noteClear = event.target.closest('[data-source-note-clear]');
  if (noteClear) {
    if (noteClear.disabled) return;
    await saveSourceNote(noteClear.dataset.sourceNoteClear, '');
    return;
  }
  if (event.target.closest('[data-source-use]')) {
    const pickButton = event.target.closest('[data-source-use]');
    const pickUrl = pickButton.dataset.sourceUse;
    if (!pickUrl || pickButton.disabled) return;
    pickButton.disabled = true;
    const status = document.getElementById('source-error');
    if (status) { status.hidden = false; status.textContent = '正在切换…'; }
    try {
      // 走 addOneSource，不是裸 use。
      //
      // 区别只有一件事，但它是致命的：addOneSource 会读新配置里的 web.spider，
      // 把那份脚本装进 SpiderStore。市场里切到一个从没装过脚本的源时，
      // files/spiders/ 下没有对应文件，之后任何内容动作都报
      // 「找不到 spider: xxx」—— 而用户完全不知道「切源」还包含「装脚本」。
      //
      // set 和 use 在 Java 侧走的是同一条 applySource（SourceConfig:957），
      // 所以换走这条路不会丢切换语义。
      if (window.HGSetup && typeof window.HGSetup.addOneSource === 'function') {
        const one = await window.HGSetup.addOneSource(pickUrl);
        if (!one || one.ok === false) throw new Error((one && one.error) || '切换失败');
      } else {
        await sourceCommand('use?url=' + encodeURIComponent(pickUrl));
      }
      if (status) status.textContent = '已切换（含脚本），正在重启…';
      restartAfterSource(() => {
        if (status) status.textContent = '已切换，但没能自动重开，请退出后重新打开';
        pickButton.disabled = false;
      });
    } catch (problem) {
      if (status) status.textContent = problem.message;
      pickButton.disabled = false;
    }
    return;
  }
  if (event.target.closest('[data-choose-avatar]')) { api('choose-avatar').catch(error => toast(error.message, true)); return; }
  if (event.target.closest('[data-account-action]')) {
    if (state.account.loggedIn) goAccountManagement();
    else showAuth();
    return;
  }
  if (event.target.closest('[data-sync-now]')) {
    try { await api('sync'); state.library = await api('library'); toast('片单已同步'); }
    catch (error) { toast(`同步失败：${error.message}`, true); }
    return;
  }
  if (event.target.closest('[data-logout]')) {
    const button = event.target.closest('[data-logout]');
    if (button.disabled) return;
    button.disabled = true;
    button.classList.add('is-loading');
    button.setAttribute('aria-busy', 'true');
    button.textContent = '正在退出…';
    try {
      setAccount(await api('logout'));
      state.library = await api('library');
      setRoute('settings'); renderSettings();
      toast('已退出登录');
    }
    catch (error) { toast(error.message, true); }
    finally {
      if (button.isConnected) {
        button.disabled = false;
        button.classList.remove('is-loading');
        button.removeAttribute('aria-busy');
        button.textContent = '退出登录';
      }
    }
    return;
  }
  const nav = event.target.closest('[data-nav]');
  if (nav) { tapDock(nav); return; }
  const libraryTab = event.target.closest('[data-library-tab]');
  if (libraryTab) { state.libraryTab = libraryTab.dataset.libraryTab; renderLibrary(); scroll.scrollTop = 0; return; }
  if (event.target.closest('[data-all-history]')) { state.libraryQuery = ''; goLibrary('history'); return; }
  const continueCard = event.target.closest('[data-continue-id]');
  if (continueCard) { resumeFromHome(continueCard.dataset.continueId).catch(error => toast(error.message || '续播失败，请重试', true)); return; }
  const historyCard = event.target.closest('[data-history-id]');
  if (historyCard) {
    const id = historyCard.dataset.historyId;
    const episode = Math.max(1, Number(historyCard.dataset.historyEpisode) || 1);
    const wantsPlay = historyCard.dataset.historyCompleted !== 'true';
    const source = contentSource;
    const token = routeToken + 1;
    // 和 resumeFromHome 走同一条路：**先确认详情真的加载出来了**再决定要不要进
    // 播放器。goDetail 内部把错误 catch 掉了、照样 resolve，所以原来那句
    // `goDetail(id).then(() => feedFrom(...))` 在详情失败时也会进播放器 ——
    // 播放器拿不到地址，又盖在错误页上，两个错误叠一起还退不出来。
    Promise.resolve(wantsPlay ? goDetail(id) : goDetail(id, historyCard)).then(() => {
      if (!wantsPlay) return;
      if (routeToken !== token || contentSource !== source || state.route !== 'detail'
          || state.detailLoading || String(state.detail?.id) !== id) return;
      if (episode > asArray(state.detail.episodes).length) {
        toast('这一集在当前内容源里没有，可能在别的源里', true);
        return;
      }
      feedFrom(id, episode);
    }).catch(error => toast(error.message || '打开失败，请重试', true));
    return;
  }
  if (event.target.closest('[data-all-tags]')) { goTags(); return; }
  const tagChoice = event.target.closest('[data-tag-slug]');
  if (tagChoice) { goTag(tagChoice.dataset.tagSlug, tagChoice.dataset.tagName); return; }
  const sub = event.target.closest('[data-sub]');
  if (sub) { goCategory(state.tab, Number(sub.dataset.sub), !!sub.closest('#compact-category')); return; }
  const card = event.target.closest('[data-open-id]');
  if (card?.dataset.openId) {
    if (card.closest('.hero') && performance.now() < heroSuppressClickUntil) return;
    goDetail(card.dataset.openId, card); return;
  }
  // 搜索页的分类胶囊取消了（见 renderSearch 的注释）：它跟搜索词无关，
  // 而且过滤是靠标题正则猜的。按分类找东西走「全部类型」。
  const slide = event.target.closest('[data-slide]');
  if (slide) { const target = Number(slide.dataset.slide); changeBanner(target, target > bannerIndex() ? 1 : -1); return; }
  if (event.target.closest('[data-back]')) { window.hgBack(); return; }
  const episode = event.target.closest('[data-episode]');
  if (episode) { feedFrom(state.detail?.id ?? state.sourceItem?.id, Number(episode.dataset.episode)); return; }
  if (event.target.closest('[data-play]')) { feedFrom(state.detail?.id ?? state.sourceItem?.id, 1); return; }
  if (event.target.closest('[data-bookmark]')) {
    if (!state.detail) return;
    try { state.bookmarked = await api('bookmark', state.detail); renderDetail(); toast(state.bookmarked ? '已加入我的片单' : '已取消收藏'); }
    catch (error) { toast(error.message, true); }
    return;
  }
  if (event.target.closest('[data-retry-category]')) { state.category.error = false; loadMoreCategory(); return; }
  const more = event.target.closest('[data-more]');
  if (more) { openListing(more.dataset.more); return; }
  if (event.target.closest('[data-retry-listing]')) { state.listing.error = false; loadMoreListing(); return; }
  const retry = event.target.closest('[data-retry]');
  if (retry) { clearTimeout(errorAutoRetry.timer); performRetry(retry.dataset.retry); return; }
  if (event.target.closest('[data-check-update]')) { checkAppUpdate(); return; }
  if (event.target.closest('[data-download-update]')) { downloadAppUpdate(); return; }
  const reset = event.target.closest('[data-factory-reset]');
  if (reset) {
    if (!reset.disabled) runFactoryReset(reset);
    return;
  }
  const clear = event.target.closest('[data-clear]');
  if (clear) {
    if (clear.dataset.clear === 'cache') {
      if (!await askConfirmation('清理内容缓存', '将删除已缓存的目录和封面。收藏与观看进度会保留。', '清理')) return;
      clearContentCache();
      await api('clear-content-cache').catch(() => {});
      toast('内容缓存已清空');
    } else if (clear.dataset.clear === 'history' || clear.dataset.clear === 'bookmarks') {
      const history = clear.dataset.clear === 'history';
      const routeAtClick = state.route;
      if (!await askConfirmation(history ? '清空观看历史' : '清空我的收藏', history ? '所有观看记录会被删除，我的收藏会保留。' : '所有收藏作品会被移除，观看历史会保留。')) return;
      await api(history ? 'clear-history' : 'clear-bookmarks');
      state.library = await api('library');
      // await 期间可能已经离开设置页，别把设置页盖到新路由上。
      if (state.route === routeAtClick) renderSettingsKeepScroll();
      toast(history ? '观看历史已清空' : '收藏已清空');
    }
  }
});

let heroTouch;
let heroSuppressClickUntil = 0;
// 预取：手指按下的瞬间就发详情请求。click 要等抬起、等合成，这一个提前量
// 通常有一两百毫秒；滑列表时顺手按到的卡片，详情页点进去就是秒开。
// 已有缓存或在飞的请求直接跳过，不浪费流量。
content.addEventListener('pointerdown', event => {
  const id = event.target.closest('[data-open-id]')?.dataset.openId
    ?? event.target.closest('[data-history-id]')?.dataset.historyId;
  if (!id || readCache(`detail:${id}`) || detailInflight.has(String(id))) return;
  fetchDetail(id).catch(() => {});
}, { capture: true, passive: true });
content.addEventListener('touchstart', event => {
  if (!event.target.closest('.hero') || event.touches.length !== 1) return;
  stopBannerAuto();
  const touch = event.touches[0];
  heroTouch = { x: touch.clientX, y: touch.clientY, moved: false };
}, { passive: true });
content.addEventListener('touchmove', event => {
  if (!heroTouch || !event.touches.length) return;
  const touch = event.touches[0];
  heroTouch.moved ||= Math.abs(touch.clientX - heroTouch.x) > 10 || Math.abs(touch.clientY - heroTouch.y) > 10;
}, { passive: true });
content.addEventListener('touchend', event => {
  if (!heroTouch) return;
  const touch = event.changedTouches[0];
  const move = touch.clientX - heroTouch.x;
  const vertical = touch.clientY - heroTouch.y;
  if (heroTouch.moved || Math.abs(move) > 10 || Math.abs(vertical) > 10) heroSuppressClickUntil = performance.now() + 400;
  heroTouch = null;
  scheduleBannerAuto();
  if (Math.abs(move) < 55 || Math.abs(move) <= Math.abs(vertical) || !isBannerRoute()) return;
  const count = bannerSlides().length;
  if (!count) return;
  const direction = move < 0 ? 1 : -1;
  changeBanner((bannerIndex() + direction + count) % count, direction);
}, { passive: true });
content.addEventListener('touchcancel', () => {
  if (!heroTouch) return;
  heroTouch = null; heroSuppressClickUntil = performance.now() + 400;
  scheduleBannerAuto();
}, { passive: true });

api('app-version').then(info => {
  // 2026-10-31 起返回 {version, debug}（debug 给「封面诊断」入口做显隐）。
  // 兼容一下旧形状（裸字符串），热更停用后这段兜底只是防御。
  const payload = (info && typeof info === 'object') ? info : { version: info };
  appVersion = String(payload.version || '');
  window.__hgDebugBuild = Boolean(payload.debug);
  const label = content.querySelector('.app-version-text');
  if (label) label.textContent = `版本 v${appVersion}`;
  // debug 构建补渲染设置页：首画时 app-version 多半还没回来，诊断行会缺席。
  if (window.__hgDebugBuild && state.route === 'settings') renderSettingsKeepScroll();
}).catch(() => {});
// 启动 4 秒后静默查一次更新（app-update.js 的 autoCheckUpdate）。
//
// 当年那段是删掉的：它指向 github.com/MrSsg/HuangguoAPP-for-Andriod，那个仓库
// 404 了，每次冷启动都白打一次外网。现在清单在 android-update.json，通道是活的，
// 而且原生那边有 12 小时节流（manual:false），重启几次也只打一次网络。
// 只有真的发现新版本才弹一句提示，同一个版本只弹一次。
// 年龄门的两颗按钮跟着私密入口一起去掉了（那条命令 Java 侧也删了，
// 留着监听只会在用户误触时拿到一句「未知请求」）。DOM 仍在 app.html 里，
// 但 age-gate 没有任何打开路径，恒 hidden。
// 启动链：contentReady → 源配置闸门 → startHome。
//
// 顺序不能颠倒。contentReady 负责把 contentSource 钉成当前内容态（现在恒为
// 'hongguo'），而 bridgeCall 会把它带进每个请求；闸门放在它之后，才能在拉接口
// 之前把没配源的用户拦下。
// 闸门放在它之后：既不影响 contentReady，又能在拉接口之前把没配源的用户拦下。
//
// 代理核心**不能挂在闸门后面**：HGSetup.ensure() 在没配源的机器上永远不 resolve
// （它故意吊着等用户在设置页填源），于是「内置核心」模式下的用户在填源之前，
// 重新打开 App 会发现昨晚开的代理没了 —— 而代理跟内容源毫无关系。
// 所以按盘上模式恢复核心这一步单独挂在 contentReady 上，和闸门并行。
contentReady.then(() => bootProxyCore());
contentReady
  .then(() => (window.HGSetup ? window.HGSetup.ensure() : undefined))
  .then(() => {
      // 闸门过了 = HG_SITE 已经是这个源的配置，这时候画底部导航才画得对。
      // app.html 里那两格是内置默认，配置不一样就在这一步被换掉。
      renderDock();
      bindDockPressFeedback();
      // 代理设置只为设置页那一行文案服务，不阻塞启动 —— 拿不到就当中直连。
      sourceCommand('proxy:get').then(result => { state.proxy = String(result.proxy || ''); }).catch(() => {});
      refreshSources().catch(() => {});
    })
    .then(() => startHome())
  .catch(error => renderError(error, 'home'));


let versionTapCount = 0, versionTapAt = 0;
// 「连点版本号 5 次 → 输入密码 → 过年龄门 → 进私密库」这套入口删了。
//
// 那个私密库是内置源，没有接口（site-default.json 的 origin 是空串），密码
// 「ssgnb」还是写死在前端的。它唯一的效果是把用户装的每个源都判成私密态、
// 套上内置源的界面模板。入口一删，unlock-overlay / age-gate 两块 DOM 永远
// hidden（留着是因为返回键那几处还在读它们的 hidden 状态，删了要连带改逻辑）。

// ---- 卡片铺满：数据不足一屏时撑开，避免右侧留白 ----
// 布局是「固定 2 行 + 横向滚动、每列 106px」，412px 宽能排 3 列。
// 「精选推荐」这类区块如果只返回 4 条，就只占 2 列，右边空掉一块。
// 这里在「撑满后每格仍不小于原尺寸」时才加 is-fit 让它铺开；列数够多时保持原样滚动。
let filmFitTimer = 0;
if (content) {
  // 只监听子节点增删，不监听属性，否则自身加的 class 会把回调再触发一遍
  new MutationObserver(scheduleFilmFit).observe(content, { childList: true, subtree: true });
  window.addEventListener('resize', scheduleFilmFit);
  scheduleFilmFit();
}

// ---- 前端内容缓存 --------------------------------------------------------
// 原版把内容缓存写在 Java 的 AppStore.saveCache()，而那是 dispatch() 在桥调用
// 返回之后执行的。数据层改成 REST 之后，home/detail/category 这些动作不再经过
// dispatch()，saveCache 的调用点就全失效了 —— cache:home 永远是空的，冷启动
// 必然没数据可画。所以缓存必须由前端自己维护。
//
// 另一个好处：localStorage 是同步读的，首屏不用等任何网络往返。原版走
// api('cached') 桥调用虽然快，也要过一次异步往返。
//
// key 沿用原来的命名（home / tags / detail:<id> / category:<id>:<page> /
// tag:<slug>:<page> / search:<query>），这样旧缓存还能接着用。
const CACHE_PREFIX = 'hgcache:';

// 缓存的作用域 —— 当前是哪个源。换源必须换这一层，否则切换之后第一帧画出来的
// 是**上一个源的内容**：封面地址对不上新源的 allowedHosts（Java 侧会抛
// 「无效封面域名」），标题和集数也是别人的。这不是理论问题，从苹果CMS 切回黄果时
// 实测撞到过：31 条封面失败，DOM 里挂的还是上一个源的 img.lzipic.com。
//
// 由 setup.js 在放行启动之前写入（window.HG_CACHE_SCOPE）——顺序是对的：
// ensure() → applySiteConfig() 写作用域 → startHome() → goHome() 读缓存。
// 没配源时是空串，跟改造前行为一致。
function cacheScope() {
  const raw = (typeof window !== 'undefined' && window.HG_CACHE_SCOPE) || '';
  // 配置地址可能是任意长度，压成一个短且稳定的标识当键前缀。
  // 用简单的 32 位散列：这里只需要「不同源 → 不同键」，不需要抗碰撞。
  if (!raw) return '';
  let h = 0;
  for (let i = 0; i < raw.length; i += 1) h = (h * 31 + raw.charCodeAt(i)) | 0;
  return (h >>> 0).toString(36);
}
const CACHE_LIMIT = 80;
function cacheKeyOf(key) {
  const scope = cacheScope();
  return CACHE_PREFIX + (scope ? scope + ':' : '') + key;
}
function readCache(key) {
  try {
    const raw = localStorage.getItem(cacheKeyOf(key));
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (!parsed || !parsed.data) return null;
    return parsed;
  } catch (error) { return null; }
}
function writeCache(key, data) {
  if (!key || !data) return;
  try {
    localStorage.setItem(cacheKeyOf(key), JSON.stringify({ at: Date.now(), data }));
    // 控制条目数，避免把 localStorage 撑爆。
    //
    // 跨作用域一起淘汰、按时间最老的先走。原来这里豁免 home（「首页那条不参与淘汰」），
    // 那是因为全局只有一个 home；加了作用域之后每个源各有一条，再豁免就等于
    // 换几次源就永久留下几份首页数据。按时间淘汰天然保留当前源那份 ——
    // 它每次启动都会被 writeCache 刷新，时间戳最新。
    const keys = [];
    for (let index = 0; index < localStorage.length; index += 1) {
      const name = localStorage.key(index);
      if (name && name.startsWith(CACHE_PREFIX)) keys.push(name);
    }
    if (keys.length <= CACHE_LIMIT) return;
    keys.map(name => {
      let at = 0;
      try { at = JSON.parse(localStorage.getItem(name) || '{}').at || 0; } catch (error) {}
      return { name, at };
    }).sort((a, b) => a.at - b.at)
      .slice(0, keys.length - CACHE_LIMIT)
      .forEach(entry => localStorage.removeItem(entry.name));
  } catch (error) {}
}
function dropCache(key) { try { localStorage.removeItem(cacheKeyOf(key)); } catch (error) {} }
// 清空全部前端内容缓存（设置页的「清理内容缓存」要连这边一起清，
// 否则清完 Java 侧的 cache:*，前端这份还会把旧内容继续画出来）。
function clearContentCache() {
  try {
    const names = [];
    for (let index = 0; index < localStorage.length; index += 1) {
      const name = localStorage.key(index);
      if (name && name.startsWith(CACHE_PREFIX)) names.push(name);
    }
    names.forEach(name => localStorage.removeItem(name));
  } catch (error) {}
}

// 首页接口失败后的自动退避重试。
// 站点入口走 Cloudflare，实测在同一条移动链路上时通时不通（三次里大约一次能通），
// 所以「失败就停在错误页」等于把偶发故障变成永久故障。这里按 2s / 5s / 12s 退避
// 重试三次，任何一次成功页面就自动恢复；期间用户也能手动点「重新尝试」。
const HOME_RETRY_DELAYS = [2000, 5000, 12000];

// 分类页同理。它比首页更容易超时 —— 20 条列表 JSON 比首页大，而首屏已经
// 降到 8 条，重试成功率明显更高。失败就自己退避重来，用户不用手点。

// 内容缓存跟数据来源绑定：换接口版本时把整批旧缓存丢掉，避免旧结构渲染出错。
const DATA_SOURCE_VERSION = 'api-v3';
try {
  if (localStorage.getItem('hgpref:dataver') !== DATA_SOURCE_VERSION) {
    localStorage.setItem('hgpref:dataver', DATA_SOURCE_VERSION);
    const stale = [];
    for (let index = 0; index < localStorage.length; index += 1) {
      const name = localStorage.key(index);
      if (name && name.startsWith(CACHE_PREFIX)) stale.push(name);
    }
    stale.forEach(name => localStorage.removeItem(name));
    api('clear-content-cache').catch(() => {});
  }
} catch (error) {}


// 接口字段 → 原渲染层认的字段


// 接口挂了就在页面上留一行提示，方便真机上肉眼确认（不影响功能）
// 接口失败的页面提示。默认静默 —— 失败已经由各页面自己处理（有缓存就继续显示、
// 没有就出错误页并自动重试），再叠一条红条只会打扰用户。
// 排查时在 WebView 控制台执行 localStorage.setItem('hgpref:apidebug','1') 打开。
