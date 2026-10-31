'use strict';

(function () {
  const SPEEDS = [0.75, 1, 1.25, 1.5, 2];
  const STORE_KEY = 'hgpref:progress';
  const URL_KEY = 'hgpref:epurl';
  const SPEED_KEY = 'hgpref:feedrate';
  const ORIENTATION_KEY = 'hgpref:feedorientation';
  const FLIP_KEY = 'hgpref:feedflip';
  // 播放地址缓存的有效期。
  //
  // 原来写 8 分钟，2026-10-18 被它骗了两次：线路已经判死，缓存里还留着
  // 那个 403 地址，点播放**连 /api/play 都不发**，日志里什么都看不到。
  // 现在两条一起上：TTL 收到 2 分钟，播放器报错时主动 urlForget。
  // 采集站的 m3u8 本来就活不长，长缓存省下的那点时间不值这个坑。
  const URL_TTL = 2 * 60 * 1000;
  const PROGRESS_LIMIT = 300;   // 进度记忆的条数上限：这份数据只增不删，见 remember
  const HOLD_MS = 280;
  const PRELOAD_DISTANCE = 1;
  const KEEP_DISTANCE = 3;
  const SEEK_SPAN = 120;   // 横向划满一屏 = 120 秒
  const SEEK_SLOP = 14;    // 横向死区（px），小于它不认成拖进度
  const SEEK_BIAS = 1.2;   // 横向位移要超过纵向的倍数，否则算纵向滑动（切集）
  const BRIGHT_SPAN = 0.7; // 竖向划满一屏 = 亮度走 70% 的量程
  const BRIGHT_SLOP = 16;  // 竖向死区（px）

  function createFeedPlayer(deps) {
    let observer = null;
    // 当前可见那一集。相邻集会被预热/回收，它们同样会触发 play/pause 事件，
    // 所以判断「要不要收起外壳」必须只认这一集，不能看任意一集的 paused。
    let activePage = null;
    // 本窗口的亮度（0~1）。进播放器时问一次原生：窗口上没覆盖过就用系统亮度的
    // 估计值 —— 不这样的话第一次上下滑会从我们瞎猜的数起跳，屏幕先闪一下。
    let brightness = null;
    // 打开内置播放器的代币与防重入锁。openAt 有三段 await（取详情 / 解析地址 /
    // 唤起原生），中途用户可能按返回退出 —— 退出后剩下的流程绝不能把播放器
    // 又拉起来。cleanup()/close() 作废当前代币；opening 挡双击连发的第二条链。
    let openToken = 0;
    let opening = false;

    const {
      state, content, api, asArray, esc, icon, toast, readCache,
      bridgeCall, playerCommand, renderDetail, goHome, updateBackEnabled
    } = deps;

    function speedLabel(rate) {
      const text = String(Number(rate).toFixed(2)).replace(/\.00$/, '').replace(/0$/, '');
      return text + '×';
    }

    function selectedRate() {
      try {
        const rate = Number(localStorage.getItem(SPEED_KEY));
        return SPEEDS.includes(rate) ? rate : 1;
      } catch (error) { return 1; }
    }

    function selectedOrientation() {
      try { return localStorage.getItem(ORIENTATION_KEY) === 'landscape' ? 'landscape' : 'portrait'; }
      catch (error) { return 'portrait'; }
    }

    function rememberRate(rate) {
      try { localStorage.setItem(SPEED_KEY, String(rate)); } catch (error) {}
    }

    function rememberOrientation(mode) {
      try { localStorage.setItem(ORIENTATION_KEY, mode); } catch (error) {}
    }

    function updateRateUi(feed, rate = selectedRate()) {
      // 不用 replaceChildren（Chrome 86）：在 Chrome 58 上那是「改倍速就 TypeError」。
      const rateTag = feed.querySelector('.feed-rate-toggle');
      if (rateTag) {
        while (rateTag.firstChild) rateTag.removeChild(rateTag.firstChild);
        rateTag.appendChild(document.createTextNode(speedLabel(rate)));
      }
      feed.querySelectorAll('.feed-rate-option').forEach(button => {
        const active = Number(button.dataset.rate) === rate;
        button.classList.toggle('active', active);
        button.setAttribute('aria-pressed', active ? 'true' : 'false');
      });
    }

    function applyRate(feed, rate = selectedRate()) {
      updateRateUi(feed, rate);
      feed.querySelectorAll('.feed-page video').forEach(video => {
        if (video.dataset.tempSpeed === '1') return;
        try { video.playbackRate = rate; } catch (error) {}
      });
    }

    // 播放中收起的播放器外壳：进度条 / 集数 / 时间 / 倍速·横屏按钮。
    // 返回键不在此列——它是导航，不占视野，留着省一次点击。
    // 收起时顺手把倍速面板关掉，否则下次暂停会看到上次留着展开的面板。
    function setChrome(feed, playing) {
      if (!feed) return;
      feed.classList.toggle('is-playing', playing);
      if (playing) feed.querySelector('.feed-rate-panel')?.classList.add('hidden');
    }

    function isLandscape(feed) {
      return feed.dataset.orientation === 'landscape';
    }

    // 滚动容器始终是竖向的（横屏也只转「内容舞台」，不转滚动容器本身），
    // 所以取当前集只看 scrollTop。这一点很关键：一旦把滚动容器旋转，
    // 触摸滚动方向就会和手指在屏幕上的实际滑动对不上，切集直接失效。
    function currentPage(feed) {
      const pages = [...feed.querySelectorAll('.feed-page')];
      if (!pages.length) return null;
      const center = feed.scrollTop + feed.clientHeight / 2;
      return pages.reduce((best, page) => Math.abs(page.offsetTop + page.offsetHeight / 2 - center) < Math.abs(best.offsetTop + best.offsetHeight / 2 - center) ? page : best, pages[0]);
    }

    function scrollToPage(feed, page, behavior = 'auto') {
      if (!feed || !page) return;
      // Element.scrollTo 的对象形式是 Chrome 61 才有的。Chrome 58~60 上它根本不存在，
      // 切集直接 TypeError —— 用户看到的是「点了下一集没反应」。退回 scrollTop。
      if (typeof feed.scrollTo === 'function') { feed.scrollTo({ top: page.offsetTop, left: 0, behavior }); return; }
      feed.scrollTop = page.offsetTop;
      feed.scrollLeft = 0;
    }

    // 横屏走「旋转舞台」而不是 Fullscreen API。
    // 原因：AndroidManifest 里 MainActivity 是 screenOrientation="portrait"，
    // 且 MainActivity$2 这个 WebChromeClient 没有实现 onShowCustomView，
    // 所以 requestFullscreen() 与 screen.orientation.lock() 在这个包里都不会生效。
    // 做法：只把每集的 .feed-stage 旋转 90°，用户把手机横过来就是满屏正片。
    // 滚动容器与页面高度都不变，因此竖滑切集、滚动吸附、可见性判定全部保持原样。
    function applyOrientation(feed, mode = selectedOrientation(), persist = false) {
      feed.dataset.orientation = mode;
      const button = feed.querySelector('.feed-orientation-toggle');
      if (button) {
        button.textContent = mode === 'landscape' ? '竖屏' : '横屏';
        button.setAttribute('aria-pressed', mode === 'landscape' ? 'true' : 'false');
      }
      if (persist) rememberOrientation(mode);
      const page = currentPage(feed);
      requestAnimationFrame(() => scrollToPage(feed, page));
    }

    // 手机从另一边横过来时（充电口朝左还是朝右），旋转方向要反过来，否则画面倒着。
    // 不用 deviceorientation 自动判断：WebView 里传感器事件不一定可用，且会抖动，
    // 手动一个「翻转」按钮更可靠，也更好验证。
    function applyFlip(feed, flip) {
      feed.dataset.flip = flip ? '1' : '0';
      try { localStorage.setItem(FLIP_KEY, flip ? '1' : '0'); } catch (error) {}
      const page = currentPage(feed);
      requestAnimationFrame(() => scrollToPage(feed, page));
    }

    function savedFlip() {
      try { return localStorage.getItem(FLIP_KEY) === '1'; } catch (error) { return false; }
    }

    function urlRecall(videoId, ep) {
      try {
        const hit = JSON.parse(localStorage.getItem(URL_KEY) || '{}')[videoId + ':' + ep];
        if (!hit || !hit.url) return '';
        if (Date.now() - (hit.at || 0) > URL_TTL) return '';
        return hit.url;
      } catch (error) { return ''; }
    }

    /** 从缓存里取备选线路（和 urlRecall 同一条记录，同一个 TTL）。 */
    function urlLinesRecall(videoId, ep) {
      try {
        const hit = JSON.parse(localStorage.getItem(URL_KEY) || '{}')[videoId + ':' + ep];
        if (!hit || !Array.isArray(hit.alts)) return [];
        if (Date.now() - (hit.at || 0) > URL_TTL) return [];
        return hit.alts.map(u => ({ url: u }));
      } catch (error) { return []; }
    }

    function urlRemember(videoId, ep, url, lines) {
      if (!url) return;
      try {
        const all = JSON.parse(localStorage.getItem(URL_KEY) || '{}');
        // 备选线路一起存：重进这一集时内置播放器还要靠它换线兜底。
        // 不存的话「缓存命中」这条路上 alts 恒为空，兜底就少一层。
        const alts = Array.isArray(lines)
          ? lines.map(l => l && (l.url || l.source)).filter(Boolean).filter(u => u !== url).slice(0, 6)
          : [];
        all[videoId + ':' + ep] = { url, at: Date.now(), alts };
        const keys = Object.keys(all);
        if (keys.length > 80) {
          keys.sort((a, b) => (all[a].at || 0) - (all[b].at || 0))
            .slice(0, keys.length - 80)
            .forEach(key => { delete all[key]; });
        }
        localStorage.setItem(URL_KEY, JSON.stringify(all));
      } catch (error) {}
    }

    function urlForget(videoId, ep) {
      try {
        const all = JSON.parse(localStorage.getItem(URL_KEY) || '{}');
        delete all[videoId + ':' + ep];
        localStorage.setItem(URL_KEY, JSON.stringify(all));
      } catch (error) {}
    }

    function storeAll() {
      try { return JSON.parse(localStorage.getItem(STORE_KEY) || '{}'); } catch (error) { return {}; }
    }

    function remember(videoId, ep, time, dur) {
      if (!videoId || !dur || dur < 30) return;
      try {
        const all = storeAll();
        all[videoId] = { ep, time, dur, at: Date.now() };
        // 只留最近 300 条。这份数据原本只增不删，攒到 localStorage 配额爆掉之后
        // setItem 会永远抛错、进度从此静默不存 —— 裁剪跟 urlRemember 同一套写法。
        const keys = Object.keys(all);
        if (keys.length > PROGRESS_LIMIT) {
          keys.sort((a, b) => (all[a].at || 0) - (all[b].at || 0))
            .slice(0, keys.length - PROGRESS_LIMIT)
            .forEach(key => { delete all[key]; });
        }
        localStorage.setItem(STORE_KEY, JSON.stringify(all));
      } catch (error) {
        // 写不进去（多半是配额）必须留痕，不然「进度怎么没了」无从查起。
        console.warn('[feed] 进度保存失败：' + (error && error.message ? error.message : error));
      }
    }

    // 自动同步的防抖定时器。
    //
    // 原生侧的 sync() 只在**登录时**和**点「立即同步片单」**时才会跑
    // （见 app.js 的 data-sync-now）。于是「看完一集直接退出」的进度永远不会
    // 传上去 —— 服务器上那份还是几天前的。这里在每次进度上报之后约 20 秒
    // 触发一次同步；连续上报会不断把它往后推，所以一次连续观看只同步一次。
    let syncTimer = null;
    function scheduleSync() {
      if (!state.account || !state.account.loggedIn) return;   // 没登录就没什么可同步的
      if (syncTimer) clearTimeout(syncTimer);
      syncTimer = setTimeout(() => {
        syncTimer = null;
        api('sync').catch(() => {});      // 同步失败不打扰用户，下次还会再来
      }, 20000);
    }

    // 进度上报的节流表：videoId → 上次送出去的秒数。
    // 不需要持久化 —— 它的作用只是「同一条进度别重复送十几次」。
    const reported = new Map();
    const REPORT_GAP = 10;

    /**
     * 把进度送到原生侧。
     *
     * remember() 那份 localStorage 记忆只够「这台设备上次看到哪」；送到
     * AppStore 之后才进得了「我的 → 观看历史」，登录了还会同步到账号后端。
     * 之前只写了 localStorage，「观看历史」永远是空的。
     *
     * 走 progress 桥的 set 指令（2026-10-18 起控制面独立 action）——
     * MainActivity 的 dispatch 是 packed-switch 按编号跳的，加动作要同时改两张表。
     */
    function report(page, time, dur, force) {
      if (!bridgeCall || !page) return;
      const videoId = String(page.dataset.videoId || '');
      // 原生那边有 matches("\\d+") 这一关，非数字的 id 送过去是白跑一趟。
      if (!/^\d+$/.test(videoId)) return;
      if (!dur || !isFinite(dur) || dur < 30) return;

      const seconds = Math.floor(time || 0);
      const last = reported.get(videoId);
      // 拖进度条往回/往前大跳时也要送一次，不然「继续观看」会停在旧位置。
      if (!force && last != null && Math.abs(seconds - last) < REPORT_GAP) return;
      reported.set(videoId, seconds);

      // ⚠️ **单位是毫秒，不是秒。**
      // AppStore 的 library() 里有这么一段：
      //     if (!completed && position <= 5000) 跳过这条
      // 判的是「看了不到 5 秒的不算历史」。送秒进去的话，看了 456 秒会变成
      // 456「毫秒」，正好落在闸门下面 —— 记录写进去了（get-progress 读得回来），
      // 但「观看历史」里一条都不显示。界面上算「已看 N 分钟」用的也是
      // position / 60000，单位是毫秒这件事两边一致。
      const payload = JSON.stringify({
        id: videoId,
        episode: Number(page.dataset.ep) || 1,
        position: Math.floor((time || 0) * 1000),
        duration: Math.floor(dur * 1000),
        title: page.dataset.title || '',
        cover: page.dataset.cover || '',
        // 这条记录属于哪个内容源。历史是按 id 索引的，而 id 只在源内部唯一 ——
        // 不存源，换源之后要么点开报「找不到」，要么撞上同号的另一部片子。
        //
        source: window.HG_SOURCE_KEY || ''
      });
      bridgeCall('progress', { id: 'set?payload=' + encodeURIComponent(payload) })
        .catch(() => {});
      scheduleSync();
    }

    function recall(videoId) {
      const entry = storeAll()[videoId];
      if (!entry) return null;
      if (Date.now() - (entry.at || 0) > 30 * 864e5) return null;
      return entry;
    }

    function fmtClock(sec) {
      sec = Math.max(0, Math.floor(Number(sec) || 0));
      const m = Math.floor(sec / 60);
      return m + ':' + String(sec % 60).padStart(2, '0');
    }

    function cleanup() {
      observer?.disconnect();
      observer = null;
      activePage = null;
      currentFeed = null;
      openToken++;      // 有打开流程（openAt）在路上就作废它，见 alive()
      const feed = document.querySelector('.feed');
      if (feed) {
        feed.querySelectorAll('.feed-page video').forEach(video => {
          try { video.pause(); } catch (error) {}
          video.removeAttribute('src');
          try { video.load(); } catch (error) {}
        });
        feed.remove();
      }
    }

    function close() {
      // 现在没有 feed 页了（播放全程在原生），但「正在打开播放器…」那一屏
      // 也得让返回键能退出去 —— 之前这里一句 `if (!feed) return false` 就把
      // 返回键吃掉了。
      const hadFeed = !!document.querySelector('.feed');
      if (hadFeed) cleanup();
      // boot 屏（还没有 .feed 元素）时上面那句不会跑，代币在这里也要作废 ——
      // 不然返回之后解析完成，播放器还是会自己弹出来。
      openToken++;
      // 离开播放器把亮度交回系统 —— 播放器里调暗是为了看片，退出之后还黑着
      // 就成了「这个 App 把我的屏幕调暗了」。
      playerCommand?.('brightness?value=-1').catch(() => {});
      brightness = null;
      if (state.route !== 'feed') return hadFeed;
      state.route = 'detail';
      if (state.detail) renderDetail(); else goHome();
      updateBackEnabled();
      return true;
    }

    /**
     * 问一次当前亮度，作为上下滑的起点。
     *
     * 不问的话只能瞎猜一个数（比如 50%），用户第一次滑会先「跳」到那个数 ——
     * 而系统播放器不会跳。原生那边读的是窗口覆盖值，没覆盖过就退到系统亮度估计值。
     */
    function primeBrightness() {
      if (!playerCommand || brightness != null) return;
      playerCommand('brightness').then(result => {
        const value = Number(result && result.value);
        if (value >= 0) brightness = Math.max(0.05, Math.min(1, value));
      }).catch(() => {});
    }

    /**
     * 解析某一集的播放地址。从原来 attach() 里抽出来的 —— 现在页面不再装载
     * <video>，这一步唯一的用途就是把地址交给内置播放器。
     */
    async function resolveUrl(videoId, ep, onStep) {
      const step = typeof onStep === 'function' ? onStep : () => {};
      const cached = urlRecall(videoId, ep);
      if (cached) {
        // 缓存命中也把备选带出来：备选来自上一次 /api/play，命中缓存说明
        // 那条地址还在 2 分钟内，备选一样新鲜。缓存里没存备选就给空数组，
        // 播放器兜底那条路就少一层 —— 所以下面 remember 时把备选一起存了。
        return { url: cached, lines: urlLinesRecall(videoId, ep) };
      }
      const info = await api('episode', { id: videoId, episode: ep });
      const rawLines = Array.isArray(info.lines) ? info.lines : [];
      let url = info.source || info.url || '';
      if (!url) throw new Error('未取得播放地址');
      // AGE动漫 给的是密文，解密要跑站点的 wasm —— 最长十几秒，期间把进度写出来。
      if (url.indexOf(AGEDM_MARKER) === 0) {
        if (!window.HG_AGEDM_PLAY) throw new Error('播放层没加载（hxm/agedm-play.js）');
        const lines = Array.isArray(info.lines) ? info.lines : null;
        try {
          url = lines && lines.length
            ? await window.HG_AGEDM_PLAY.resolveAny(lines, step)
            : await window.HG_AGEDM_PLAY.resolve(url.slice(AGEDM_MARKER.length), step);
        } catch (error) {
          throw new Error('解析失败：' + (error && error.message ? error.message : String(error)));
        }
      }
      if (url) urlRemember(videoId, ep, url, rawLines);
      return { url, lines: rawLines };
    }

    /**
     * 直接进内置播放器 —— 全应用只剩这一条播放路径。
     *
     * 不再渲染 feed 页。那套竖滑 UI 是给网页 <video> 用的，而原生那头自己就是
     * mini-feed：上下滑切集、左右滑拖进度、单击出控件、倍速面板全在 Activity 里，
     * 比 WebView 里再套一层顺得多。页面这边只做三件事：
     *   拿全集数 → 解析这一集的地址 → 把 payload 递过去。
     *
     * 起播位置从进度记忆里读。原来靠 <video>.currentTime 记的那一套在这里没有
     * 载体了，直接换算成毫秒塞进 payload.start。
     */
    async function openAt(videoId, episode) {
      const id = String(videoId);
      cleanup();
      state.route = 'feed';
      state.feedReturnId = id;
      // 代币守卫：下面有三段 await（取详情 / 解析地址 / 唤起原生），任何一段的
      // 途中用户都可能按返回退出（close 拨回详情页）。退出了就绝不能在 await
      // 落定后把原生播放器又拉起来。cleanup()/close() 会作废这里的代币。
      const token = ++openToken;
      const alive = () => token === openToken && state.route === 'feed';
      const boot = text => {
        if (!alive()) return;   // 解析中的进度文字同理：退出后别再往 content 里写
        content.innerHTML = '<div class="feed-boot">' + esc(text) + '</div>';
      };
      boot('正在打开播放器…');

      let detail = (state.detail && String(state.detail.id) === id && asArray(state.detail.episodes).length)
        ? state.detail : null;
      if (!detail) {
        try { detail = await api('detail', { id }); }
        catch (error) { if (alive()) boot(error.message || '加载失败'); return; }
      }
      if (!alive()) return;
      const episodes = asArray(detail.episodes)
        .map(entry => Number(entry.number ?? entry.episode ?? entry.ep ?? 0))
        .filter(Boolean);
      if (!episodes.length) { if (alive()) boot('这部剧还没有可播放的选集'); return; }

      let ep = Number(episode) || episodes[0];
      // 记忆里的集数可能已经不在这部剧里（下架、换源后选集变了）。index 退到 0
      // 的同时 episode 也得跟着归一 —— 不然原生按 index 高亮第 1 集，却按 episode
      // 去取旧集数的流、记旧集数的进度，两边对不上。
      if (episodes.indexOf(ep) < 0) ep = episodes[0];
      const index = episodes.indexOf(ep);
      const memory = recall(id);
      const start = (memory && Number(memory.ep) === ep && memory.time > 5)
        ? Math.round(memory.time * 1000) : 0;

      let resolved;
      try { resolved = await resolveUrl(id, ep, boot); }
      catch (error) { if (alive()) boot(error.message || '取不到播放地址'); return; }
      if (!alive()) return;
      const url = resolved.url;
      const lines = Array.isArray(resolved.lines) ? resolved.lines : [];

      if (!window.HG_PLAYER) { boot('这个版本没有带内置播放器'); return; }
      const prefs = playerPrefs();
      // 备选线路：适配器排好序的 lines（第一条就是 url 本身，去掉）。
      // 内置播放器在 EncounteredError 时按这个列表换线重试 —— 这条兜底
      // 不依赖任何探测/缓存判断，是整条链路上唯一永远成立的机制。
      // 只有当前这一集有；别的集是滑动时现取的。
      const alts = lines
        .map(line => line && (line.url || line.source))
        .filter(Boolean)
        .filter(u => u !== url);
      const payload = {
        url,
        alts,
        eps: episodes,
        index,
        id,
        episode: ep,
        title: detail.title || '',
        cover: detail.cover || '',
        source: window.HG_SOURCE_KEY || '',
        start,
        decode: prefs.decode === 'sw' ? 'sw' : 'auto',
        cache: Number(prefs.cache) || 1500,
        rate: selectedRate()
      };
      try {
        const reply = await window.HG_PLAYER.command('native?payload=' + encodeURIComponent(JSON.stringify(payload)));
        if (!alive()) return;
        if (reply && reply.fallback) boot('这个安装包没有带本机架构的解码库（需要 arm64-v8a）');
      } catch (error) {
        if (alive()) boot(error.message || '内置播放器打不开');
      }
    }

    function open(videoId, episode) {
      const id = videoId ?? state.detail?.id ?? state.sourceItem?.id;
      if (id == null) { toast('缺少剧集信息', true); return; }
      // 防重入：上一次打开还在解析中（取详情 / wasm 解密最长十几秒）就不再起
      // 第二条 —— 双击「立即播放」本来会对原生连发两次 native?payload=。
      if (opening) return;
      primeBrightness();
      const memory = recall(String(id));
      const explicit = episode != null && Number(episode) > 0;
      const ep = explicit ? Number(episode) : (memory ? memory.ep : 1);
      opening = true;
      openAt(String(id), Math.max(1, ep))
        .catch(error => toast(error.message || '播放失败，请重试', true))
        .then(() => { opening = false; });
    }

    function bindVideo(page) {
      const video = page.querySelector('video');
      if (!video || video.dataset.bound === '1') return;
      video.dataset.bound = '1';
      const fill = page.querySelector('.feed-bar-fill');
      const cur = page.querySelector('.feed-time-cur');
      const tot = page.querySelector('.feed-time-total');

      video.addEventListener('timeupdate', () => {
        const dur = video.duration;
        if (!dur || !isFinite(dur)) return;
        // 拖进度期间由手势接管进度条与当前时间，别让 timeupdate 把它覆盖回播放头的位置。
        const previewing = page.classList.contains('is-seek');
        if (fill && !previewing && !page.querySelector('.feed-bar')?.classList.contains('dragging')) {
          fill.style.width = ((video.currentTime / dur) * 100).toFixed(2) + '%';
        }
        if (cur && !previewing) cur.textContent = fmtClock(video.currentTime);
        if (tot) tot.textContent = fmtClock(dur);
        const tick = Math.floor(video.currentTime);
        if (tick % 5 === 0 && tick !== Number(video.dataset.lastSave)) {
          video.dataset.lastSave = String(tick);
          remember(page.dataset.videoId, Number(page.dataset.ep), video.currentTime, dur);
          report(page, video.currentTime, dur);
        }
      });
      video.addEventListener('ended', () => {
        remember(page.dataset.videoId, Number(page.dataset.ep), video.duration, video.duration);
        report(page, video.duration, video.duration, true);
        const next = page.nextElementSibling;
        if (next?.classList.contains('feed-page')) scrollToPage(page.parentElement, next, 'smooth');
        else toast('已经是最后一集了');
      });
      video.addEventListener('waiting', () => { if (!video.paused) page.querySelector('.feed-spinner')?.classList.remove('hidden'); });
      video.addEventListener('playing', () => page.querySelector('.feed-spinner')?.classList.add('hidden'));
      video.addEventListener('canplay', () => page.querySelector('.feed-spinner')?.classList.add('hidden'));
      // 起播即收起外壳、暂停即展开。用户点屏幕暂停/继续走的也是这两个事件。
      video.addEventListener('pause', () => {
        page.classList.add('is-paused');
        if (page === activePage) setChrome(page.closest('.feed'), false);
      });
      video.addEventListener('play', () => {
        page.classList.remove('is-paused');
        if (page === activePage) setChrome(page.closest('.feed'), true);
      });
      video.addEventListener('error', () => {
        if (video.dataset.ready !== '1') return;
        // ── 这个 error 不一定真 ────────────────────────────────────────
        // 实测（模拟器 Chrome，和 WebView 同一套 Blink）：给 `preload="none"` 的
        // <video> 设完 src，**30ms 内先派发一个 code=4，然后照常 loadstart →
        // loadedmetadata → loadeddata → canplay → play() 成功**。那一下是假的。
        //
        // 而这里原来是「一收到 error 就当场判死」：urlForget + 清 ready + 弹错误框。
        // 于是把一次本来会成功的加载掐掉了 —— 「徒然喜欢你 App 播不了、网页能看」
        // 就是这么来的：流没问题、网络没问题、编码没问题（在 Chrome 里
        // loadedmetadata 1920x1080 / readyState=4，从手机上 curl 是 206 + video/mp4）。
        //
        // 所以先别下结论。给它 1.2 秒：真错的话那时候仍是 networkState=
        // NETWORK_NO_SOURCE(3) 且 readyState=0；起来了就什么都不做。
        const stamp = video.dataset.loadStamp || '';
        const me = video.error;
        const code = me ? me.code : 0;
        setTimeout(() => {
          if (video.dataset.ready !== '1') return;              // 期间被 release / 切集了
          if (video.dataset.loadStamp !== stamp) return;        // 已经换成新的 src 了
          if (video.networkState !== 3 || video.readyState > 0) {
            console.log('[video] 收到 error(code=' + code + ') 但流随后正常起来了，忽略');
            return;
          }
          const why = MEDIA_ERROR[code] || (code ? '未知错误' : '原因不明');
          trackPlayError('decode', null, { code: code, why: why, ep: Number(page.dataset.ep) || 0 });
          urlForget(page.dataset.videoId, Number(page.dataset.ep));
          // 把这一集的地址钉在页面上。<video> 上的 src 会在 release()、重挂、
          // 以及某些 WebView 的致命错误路径里被清掉，那时错误框里的按钮就
          // 变成「点了没反应」——有这一份就不怕了。
          page.dataset.nativeUrl = video.currentSrc || video.getAttribute('src') || '';
          video.dataset.ready = '';
          video.dataset.bound = '';
          page.querySelector('.feed-spinner')?.classList.add('hidden');
          const box = page.querySelector('.feed-error');
          if (box) {
            box.classList.remove('hidden');
            const label = box.querySelector('.feed-error-text');
            if (label) {
              // 把地址的头也带上：有的 CDN 只回一小段就断，光看错误码分不出是
              // 「这个域名不行」还是「这个地址过期了」。用竖线分隔而不是换行 ——
              // .feed-error-text 不是 pre，\n 不换行。
              const head = (video.currentSrc || '').replace(/^https?:\/\//, '').slice(0, 60);
              label.textContent = '这个视频播不了：' + why + '（MediaError ' + code + '）'
                + (head ? ' ｜ ' + head + '…' : '');
            }
          }
          // 4 = SRC_NOT_SUPPORTED：解码器不认这个流（HEVC / 10bit / 冷门封装最常见）。
          // 同一部片在别的手机上能放、这台不行，就是这么来的 —— 不是网络问题。
          // 这种时候把地址交给系统播放器（MX / VLC），它们用自己的解码链。
          const external = box && box.querySelector('.feed-external');
          if (external) external.hidden = !(code === 4 || code === 3);
          // 内置播放器是这两类错误的正解，所以它比「外部播放器」更靠前：
          // 用户要的是能看，不是被送去装一个新 App。
          const nativeBtn = box && box.querySelector('.feed-native');
          if (nativeBtn) nativeBtn.hidden = !(code === 4 || code === 3);
          if (code === 4 && label) {
            label.textContent += '。这台设备的播放器不认这个编码，点「内置播放器」用 App 自带的软解试试';
          }
          // 自动切换：只认当前这一集、只做一次、只在这两类错误上做。
          // 不加这三道限制的话，用户往回滑时每一集都会弹一次全屏播放器。
          const prefs = playerPrefs();
          markNeedsNative(page.dataset.videoId, page.dataset.ep);
          // 「当前这一集」不能只认 activePage：IntersectionObserver 在某些滚动
          // 状态下（尤其是旋转/快速滑动）没把 ratio 顶过 0.55，activePage 还停在
          // 上一集，于是自动切换静默不触发。这里补一个几何判据：这一页真的在
          // 屏幕中间，就算当前集。
          let onScreen = false;
          try {
            const rect = page.getBoundingClientRect();
            const height = window.innerHeight || 1;
            onScreen = rect.height > 0 && rect.bottom > height * 0.4 && rect.top < height * 0.6;
          } catch (error) {}
          if (prefs.autoFallback && (page === activePage || onScreen)
              && (code === 4 || code === 3) && page.dataset.autoNative !== '1') {
            page.dataset.autoNative = '1';
            openNative(page, 'auto');
            return;
          }
          console.log('[native] 没自动切：fallback=' + prefs.autoFallback
            + ' active=' + (page === activePage) + ' onScreen=' + onScreen
            + ' flag=' + page.dataset.autoNative);
          console.log('[video] error code=' + code + ' (' + why + ') src=' + (video.currentSrc || '').slice(0, 100));
        }, 1200);
      });
    }

    /**
     * 往 <video> 里装一个地址。**所有设 src 的地方都走它。**
     *
     * 存在的理由只有一个：盖一个「第几次加载」的戳。错误处理器要判断
     * 「这个 error 属于哪一次加载」—— 给 `preload="none"` 的元素设完 src 会先
     * 派发一个假的 code=4（实测 30ms，然后照常播起来），而 1.2 秒后做判断时，
     * 用户可能已经滑到下一集、元素上换了新的 src。没有戳的话，上一集的假 error
     * 会被算到下一集头上。
     */
    function loadInto(video, url) {
      const host = video.closest('.feed-page');
      if (host) delete host.dataset.nativeUrl;      // 换地址了，错误框里那份作废
      video.dataset.loadStamp = String(Date.now()) + '-' + Math.random().toString(36).slice(2, 8);
      video.src = url;
      try { video.load(); } catch (error) {}
    }

    function bindBar(page) {
      const bar = page.querySelector('.feed-bar');
      if (!bar || bar.dataset.bound === '1') return;
      bar.dataset.bound = '1';
      // 横屏时舞台被旋转 90°，进度条在屏幕上变成一条竖线，所以比例要按纵向算。
      // 由前面的映射反解：
      //   竖屏        local x = clientX - rect.left
      //   横屏非翻转  local x = clientY - rect.top        （视口 Y 就是局部 X）
      //   横屏翻转    local x = rect.bottom - clientY
      const ratioAt = point => {
        const feed = bar.closest('.feed');
        const rect = bar.getBoundingClientRect();
        const landscape = feed?.dataset.orientation === 'landscape';
        const flip = feed?.dataset.flip === '1';
        const span = landscape ? rect.height : rect.width;
        const offset = landscape
          ? (flip ? rect.bottom - point.clientY : point.clientY - rect.top)
          : point.clientX - rect.left;
        return Math.max(0, Math.min(1, offset / Math.max(1, span)));
      };
      bar.addEventListener('pointerdown', event => {
        event.stopPropagation();
        const video = page.querySelector('video');
        if (!video || !video.duration) return;
        const fill = page.querySelector('.feed-bar-fill');
        const move = ev => { if (fill) fill.style.width = (ratioAt(ev) * 100).toFixed(2) + '%'; };
        const up = ev => {
          const r = ratioAt(ev);
          try { video.currentTime = r * video.duration; } catch (error) {}
          window.removeEventListener('pointermove', move);
          window.removeEventListener('pointerup', up);
          window.removeEventListener('pointercancel', up);
          bar.classList.remove('dragging');
        };
        bar.classList.add('dragging');
        move(event);
        window.addEventListener('pointermove', move);
        window.addEventListener('pointerup', up);
        window.addEventListener('pointercancel', up);
      });
    }

    // 和 AgedmAdapter.PLAY_MARKER 对齐。改了那边这里也要改。
    const AGEDM_MARKER = 'agedm-wasm:';

    // HTMLMediaElement 的 error.code。写成人话是因为「黑屏 + 一圈转不停的圈」
    // 什么都说明不了，而 3 和 4 是完全不同的两件事：4 是容器/编码不认识（换一路
    // 清晰度可能就好了），3 是流本身坏了（换线路）。不写出来就只能靠猜。
    const MEDIA_ERROR = {
      1: '加载被中止',
      2: '网络错误',
      3: '解码失败，流本身坏了',
      4: '格式不支持，容器或编码不认识'
    };

    /**
     * 更新转圈下面那行进度文字。空串 = 藏起来。
     *
     * 为什么要有它：AGE动漫 的解析要跑站点那个 307 KB 的 wasm、还要一条条试线路，
     * 这十几秒里界面上如果只有一圈转不停的 spinner，用户没法分辨是在干活还是卡死了。
     * 有了这行字，「取播放页… 2/6」和「线路 ffm3u8（3/5）」自己就把话说清楚了。
     */
    function setSpinText(page, text) {
      const el = page.querySelector('.feed-spinner-text');
      if (!el) return;
      if (text) { el.textContent = text; el.classList.remove('hidden'); }
      else { el.textContent = ''; el.classList.add('hidden'); }
    }

    async function attach(page) {
      const video = page.querySelector('video');
      if (!video || video.dataset.ready === '1') return video;
      const ep = Number(page.dataset.ep);
      let url = urlRecall(page.dataset.videoId, ep);
      let cover = '';
      let returned = null;          // 只为出错时能把返回体打出来
      if (!url) {
        const info = await api('episode', { id: page.dataset.videoId, episode: ep });
        returned = info;
        url = info.source || info.url;
        cover = info.cover || '';
        // AGE动漫 那条线给的是一串密文（Java 侧只负责取，解要跑站点的 wasm），
        // 解析要跑 wasm、还要一条条试线路，最长能到十几秒 —— 期间那圈 spinner
        // 和下面那行文字就是给这段留的。
        if (url && url.indexOf(AGEDM_MARKER) === 0) {
          if (!window.HG_AGEDM_PLAY) throw new Error('播放层没加载（hxm/agedm-play.js）');
          const step = text => setSpinText(page, text);
          setSpinText(page, '解析播放地址…');
          try {
            // lines 是 Java 侧按 player_vip 排好序的全部线路。用它一条条试 ——
            // 以前只试第一条，第一条挂了后面几条从没被碰过。
            // 拿不到 lines（旧版 Java 侧）就退回单条，行为不变。
            const lines = Array.isArray(info.lines) ? info.lines : null;
            url = lines && lines.length
              ? await window.HG_AGEDM_PLAY.resolveAny(lines, step)
              : await window.HG_AGEDM_PLAY.resolve(url.slice(AGEDM_MARKER.length), step);
          } catch (error) {
            throw new Error('解析失败：' + (error && error.message ? error.message : String(error)));
          } finally {
            setSpinText(page, '');
          }
        }
        // 这里**不能**对「不是 agedm 密文」的情况抛错 —— 除 AGE动漫 之外的每个源
        // 拿到的都是普通 m3u8，那样的判断会把所有源都毙掉。曾经这么写过一版，
        // 表现就是「好几个源看不了东西」。
        if (url) urlRemember(page.dataset.videoId, ep, url);
      }
      if (!url) throw new Error('未取得播放地址｜返回体：' + JSON.stringify(returned).slice(0, 200));
      video.dataset.ready = '1';
      const poster = cover || page.dataset.cover;
      if (poster && !video.poster) video.poster = poster;
      // 只走 WebView 原生 HLS：媒体请求不带 Origin，CDN 才给正片。
      // hls.js 那条路已删（它的 XHR 必带 Origin，CDN 见之返回 54 秒宣传片），
      // 连带把 CSP 的 connect-src / blob: 一起收回，见 build 说明。
      loadInto(video, url);
      bindVideo(page);
      bindBar(page);
      applyRate(page.closest('.feed') || document, selectedRate());

      const episodeNo = Number(page.dataset.ep);
      const memory = recall(page.dataset.videoId);
      if (memory && memory.ep === episodeNo && memory.time > 5 && (!memory.dur || memory.time < memory.dur - 15)) {
        let tries = 0;
        const timer = setInterval(() => {
          if (video.duration && isFinite(video.duration)) {
            if (memory.time < video.duration - 5) { try { video.currentTime = memory.time; } catch (error) {} }
            clearInterval(timer);
          } else if (++tries > 30) clearInterval(timer);
        }, 400);
      }
      return video;
    }

    async function warm(page) {
      if (!page || page.querySelector('video')?.dataset.ready === '1') return;
      try { await attach(page); } catch (error) {}
    }

    function release(page) {
      const video = page?.querySelector('video');
      if (!video || video.dataset.ready !== '1' || !video.paused) return;
      video.removeAttribute('src');
      try { video.load(); } catch (error) {}
      video.dataset.ready = '';
      video.dataset.bound = '';
      video.removeAttribute('poster');
      page.querySelector('.feed-spinner')?.classList.remove('hidden');
      const fill = page.querySelector('.feed-bar-fill');
      if (fill) fill.style.width = '0%';
      const cur = page.querySelector('.feed-time-cur');
      if (cur) cur.textContent = '0:00';
    }

    // ── 内置播放器（libVLC） ──────────────────────────────────────────
    //
    // WebView 的 <video> 用哪个解码器由系统 WebView 决定，页面上没有旋钮；
    // 内置播放器把解码链搬到 App 里，所以「这台手机播不了」这种事有了第二条路。
    // 设置项和桥指令都挂在 app.js 的 window.HG_PLAYER 上（那边已经有一份
    // playerCommand，不重复实现）。

    let currentFeed = null;

    // ── 「这一集这台机器解不了」的短期记忆 ──────────────────────────────
    //
    // 只靠「收到 MediaError 的那一刻切过去」是不够的：那一刻的判断依赖
    // activePage、依赖 <video> 还挂着 src、还依赖错误码真的是 4 —— 任何一条
    // 不成立就静默不切，用户看到的就是「自动切换没生效」。
    // 补一条持久事实：这集失败的当场记下来，下次播这一集直接起内置播放器。
    const FAIL_KEY = 'hgpref:nativefail';
    const FAIL_TTL = 7 * 24 * 3600 * 1000;

    function failMap() {
      try {
        const raw = JSON.parse(localStorage.getItem(FAIL_KEY) || '{}');
        const now = Date.now();
        const out = {};
        Object.keys(raw).forEach(key => {
          if (now - Number(raw[key] || 0) < FAIL_TTL) out[key] = raw[key];
        });
        return out;
      } catch (error) { return {}; }
    }

    function markNeedsNative(id, ep) {
      try {
        const map = failMap();
        map[String(id) + ':' + Number(ep || 1)] = Date.now();
        const keys = Object.keys(map);
        if (keys.length > 200) keys.slice(0, keys.length - 200).forEach(k => delete map[k]);
        localStorage.setItem(FAIL_KEY, JSON.stringify(map));
      } catch (error) {}
    }

    function needsNative(id, ep) {
      return Boolean(failMap()[String(id) + ':' + Number(ep || 1)]);
    }

    /**
     * 播放失败统一上报。stage 决定「死在哪一环」：resolve 是拿不到/解不开播放地址，
     * start 是地址有了但起不来，native 是交给 VLC 时失败，decode 是浏览器不认这个流。
     *
     * 以前这些分支各写各的文案，用户看到一句「播放失败」，事后也只能猜。
     */
    function trackPlayError(stage, error, extra) {
      try {
        const props = {
          stage: stage,
          code: (error && error.name) || 'error',
          msg: String((error && error.message) || error || '').slice(0, 200)
        };
        if (extra) Object.assign(props, extra);
        track('play', 'play_error', props);
      } catch (ignored) {}
    }

    function playerPrefs() {
      const read = window.HG_PLAYER && window.HG_PLAYER.prefs;
      return (read && read()) || { core: 'web', decode: 'auto', cache: 1500, autoFallback: true };
    }

    /** 这一集现在该播的地址。和解码器无关，网页/内置两个播放器共用同一个 URL。 */
    function playableUrl(page) {
      if (!page) return '';
      const video = page.querySelector('video');
      const live = video && (video.currentSrc || video.getAttribute('src'));
      return live || page.dataset.nativeUrl || '';
    }

    /**
     * 交给内置播放器。reason：'user' 是用户点的，'auto' 是解码失败自动切。
     *
     * 起播前先把网页那个 <video> 停掉 —— 否则 Activity 一盖上来，WebView 里的
     * 声音还在放着，用户会听到两路音频叠在一起。
     */
    async function openNative(page, reason) {
      if (!page) return;
      const url = playableUrl(page);
      if (!url) { toast('还没有可播放的地址', true); return; }
      if (!window.HG_PLAYER) { toast('这个版本没有带内置播放器', true); return; }
      const prefs = playerPrefs();
      const video = page.querySelector('video');
      // 整部剧的集数带进去：内置播放器那头有 mini-feed（上下滑切集）。
      // 地址不在这里解析 —— 原生会按需回来问 __hgNativeEpisode。
      const eps = [...(page.parentElement?.querySelectorAll('.feed-page') || [])]
        .map(p2 => Number(p2.dataset.ep) || 0);
      const payload = {
        url,
        eps,
        index: eps.indexOf(Number(page.dataset.ep) || 1),
        id: page.dataset.videoId || '',
        episode: Number(page.dataset.ep) || 1,
        title: page.dataset.title || '',
        cover: page.dataset.cover || '',
        source: window.HG_SOURCE_KEY || '',
        start: Math.round(((video && video.currentTime) || 0) * 1000),
        // 自动切换的那一次一律软解：能从 <video> 掉下来，多半就是硬解不认这个流，
        // 再让 VLC 走一遍同一条硬解只是把失败重演一次。
        decode: reason === 'auto' ? 'sw' : (prefs.decode === 'sw' ? 'sw' : 'auto'),
        cache: Number(prefs.cache) || 1500,
        rate: selectedRate()
      };
      try { if (video) video.pause(); } catch (error) {}
      try {
        await window.HG_PLAYER.command('native?payload=' + encodeURIComponent(JSON.stringify(payload)));
        if (reason === 'auto') toast('这台设备解不了这个编码，已切到内置播放器');
      } catch (error) {
        trackPlayError('native', error, { ep: Number(page.dataset.ep) || 0, reason: reason });
        toast(error.message || '内置播放器打不开', true);
      }
    }

    /**
     * 从内置播放器回来之后，把那一场播完的现场收下：
     * 位置写回进度（和网页播放器同一份 localStorage + 同一个进度桥），
     * 网页播放器的播放头也挪到那儿，播完了就滑到下一集。
     */
    async function checkNativeReturn(feed) {
      if (!window.HG_PLAYER) return;
      if (!feed) feed = document.querySelector('.feed') || currentFeed;
      // feed 可能压根不存在 —— 现在播放全程在原生，页面这边没有 feed 页。
      // 进度照样要收：下面只有「把播放头挪回去」那一步需要 page，没有就跳过。
      let result = null;
      try { result = await window.HG_PLAYER.last(); } catch (error) { return; }
      const session = result && result.session;
      if (!session || !session.id) return;
      const id = String(session.id);
      if (!/^\d+$/.test(id)) return;         // 原生那边只认纯数字 id，别的存不了

      const ep = Number(session.episode) || 1;
      const pos = Math.max(0, (Number(session.position) || 0) / 1000);
      const dur = Math.max(0, (Number(session.duration) || 0) / 1000);
      if (dur > 0) remember(id, ep, pos, dur);

      const page = feed
        ? feed.querySelector('.feed-page[data-video-id="' + id + '"][data-ep="' + ep + '"]')
        : null;
      if (dur > 0) {
        if (page) report(page, pos, dur, true);
        else if (bridgeCall) {
          // 记的这一集不在当前这部里（用户从观看历史进来的），照样要把进度送回去。
          bridgeCall('progress', {
            id: 'set?payload=' + encodeURIComponent(JSON.stringify({
              id,
              episode: ep,
              position: Math.round(pos * 1000),
              duration: Math.round(dur * 1000),
              title: session.title || '',
              cover: session.cover || '',
              source: session.source || ''
            }))
          }).catch(() => {});
        }
      }
      if (!page) return;

      const video = page.querySelector('video');
      if (video && pos > 3 && (!dur || pos < dur - 8)) {
        // duration 还没出来时这一刻的赋值会被丢掉，attach() 里那份 recall()
        // 才是兜底 —— 它等 duration 就绪再落位置。两条都留着不冲突。
        try { video.currentTime = pos; } catch (error) {}
      }

      if (session.finished) {
        const next = page.nextElementSibling;
        if (next && next.classList.contains('feed-page')) scrollToPage(feed, next, 'smooth');
        else toast('已经是最后一集了');
      } else if (pos > 1) {
        toast('已记下进度 ' + fmtClock(pos));
      }
    }

    function neighbors(page) {
      const feed = page.parentElement;
      if (!feed) return;
      const pages = [...feed.querySelectorAll('.feed-page')];
      const here = pages.indexOf(page);
      pages.forEach((item, index) => {
        const distance = Math.abs(index - here);
        if (distance === 0) return;
        if (distance <= PRELOAD_DISTANCE) warm(item);
        else if (distance > KEEP_DISTANCE) release(item);
      });
    }

    async function play(page) {
      if (!page?.classList.contains('feed-page')) return;
      const video = page.querySelector('video');
      if (!video) return;

      // 内核设成「内置」时，滑到一集就直接交给 VLC —— 但得先 attach 一次，
      // 地址是那个接口给的（网页和内置用的是同一个 URL）。autoNative 这个
      // 标记是防回环的：从 VLC 回来后 IntersectionObserver 会再触发一次 play()，
      // 没有它就会立刻又跳出去，用户永远回不到页面。
      if (playerPrefs().core === 'native' && page.dataset.autoNative !== '1') {
        page.dataset.autoNative = '1';
        try { await attach(page); } catch (error) {}
        openNative(page, 'user');
        return;
      }

      // 这一集在这台机器上出过「解不了」的错：不等错误再来一次，直接起内置。
      // 地址还得先 attach 出来（网页和内置用的是同一个 URL）。
      if (playerPrefs().autoFallback && page.dataset.autoNative !== '1'
          && needsNative(page.dataset.videoId, page.dataset.ep)) {
        page.dataset.autoNative = '1';
        try { await attach(page); } catch (error) {}
        openNative(page, 'auto');
        return;
      }

      try {
        await attach(page);
        page.querySelector('.feed-error')?.classList.add('hidden');
        await video.play().catch(() => {});
        // 清晰度只问**当前这一集**，不问预热的那几集 —— 每集一次请求，
        // 而预热一集只是为了切过去时不用等，不是为了让面板提前准备好。
        loadQuality(page, video.getAttribute('src') || '');
      } catch (error) {
        // ready 还是空 = attach 那一步就挂了（取详情 / 取播放地址 / 解密）；
        // 否则是地址拿到了、<video> 起不来。
        trackPlayError(video.dataset.ready === '1' ? 'start' : 'resolve', error, {
          ep: Number(page.dataset.ep) || 0
        });
        page.querySelector('.feed-spinner')?.classList.add('hidden');
        const box = page.querySelector('.feed-error');
        if (box) {
          box.classList.remove('hidden');
          const label = box.querySelector('.feed-error-text');
          if (label) label.textContent = error.message || '播放失败';
        }
      }
      neighbors(page);
    }

    function pause(page) {
      const video = page.querySelector('video');
      if (!video || video.paused) return;
      try { video.pause(); } catch (error) {}
      remember(page.dataset.videoId, Number(page.dataset.ep), video.currentTime, video.duration);
      report(page, video.currentTime, video.duration, true);      // 暂停时必送
    }

    function controlsHtml() {
      return `<div class="feed-controls">
        <button type="button" class="feed-control feed-ep-prev" aria-label="上一集">上一集</button>
        <button type="button" class="feed-control feed-ep-next" aria-label="下一集">下一集</button>
        <button type="button" class="feed-control feed-quality-toggle" hidden aria-label="选择清晰度">清晰度</button>
        <button type="button" class="feed-control feed-rate-toggle" aria-label="选择倍速">${speedLabel(selectedRate())}</button>
        <button type="button" class="feed-control feed-native-toggle" aria-label="用内置播放器播放">内置</button>
        <button type="button" class="feed-control feed-orientation-toggle" aria-label="切换横屏播放">横屏</button>
        <button type="button" class="feed-control feed-flip-toggle" aria-label="翻转画面方向">翻转</button>
        <div class="feed-quality-panel hidden" role="menu" aria-label="清晰度"></div>
        <div class="feed-rate-panel hidden" role="menu" aria-label="播放倍速">
          ${SPEEDS.map(rate => `<button type="button" class="feed-rate-option" data-rate="${rate}" role="menuitemcheckbox">${speedLabel(rate)}</button>`).join('')}
        </div>
      </div>`;
    }

    /**
     * 问一次清晰度。主 m3u8 里那张码率表只有取回文本才看得见，而页面 CSP 的
     * connect-src 是 'none' —— 所以这段走 Java（player 桥的 variants）。
     *
     * 空列表**不是错误**：那个站只有一路（实测量子/暴风两家的 master 都只有
     * 一档：RESOLUTION=1080x608 + BANDWIDTH=800000）。这时按钮照旧显示当前这一路，
     * 面板里如实写一句，而不是给一个点不动的选择器。
     */
    async function loadQuality(page, url) {
      if (!playerCommand || !url || url.indexOf('.m3u8') < 0) return;
      if (page.dataset.quality != null) return;      // 一集只问一次
      try {
        const result = await playerCommand('variants?url=' + encodeURIComponent(url));
        const items = asArray(result.items);
        page.dataset.quality = JSON.stringify(items);
        page.dataset.qualityPicked = url;
        updateQualityUi(page);
      } catch (error) {
        // 拿不到就不显示这一项，不打扰用户。
        page.dataset.quality = '';
      }
    }

    function qualityLabelOf(page) {
      const items = asArray(JSON.parse(page.dataset.quality || '[]'));
      const picked = page.dataset.qualityPicked || '';
      const hit = items.find(item => item.url === picked);
      return (hit && hit.name) || (items.length === 1 ? items[0].name : '');
    }

    function updateQualityUi(page) {
      const feed = page.closest('.feed');
      if (!feed) return;
      const items = asArray(JSON.parse(page.dataset.quality || '[]'));
      const button = feed.querySelector('.feed-quality-toggle');
      if (!button) return;
      const label = qualityLabelOf(page);
      // 一档都没有（不是主列表 / 探测失败）就不出现 —— 编一个数字比不显示更糟。
      button.hidden = items.length === 0;
      if (items.length) button.textContent = label || '清晰度';
      const panel = feed.querySelector('.feed-quality-panel');
      if (!panel) return;
      const rows = items.map(item => {
        // 「当前这一路」有两种认法：地址就是正在播的那个，或者压根只有一路。
        // 后者很重要 —— 只有一路时如果还让人点，点下去是把同一个流换个地址重挂一遍，
        // 白白黑一下屏。这里把它标成当前并置灰，面板的职责就回到「告诉用户是什么」。
        const current = items.length === 1 || item.url === (page.dataset.qualityPicked || '');
        return `<button type="button" class="feed-quality-option${current ? ' is-current' : ''}" data-quality="${esc(item.url)}"${current ? ' disabled' : ''}>${esc(item.name)}${item.note ? `<small>${esc(item.note)}</small>` : ''}</button>`;
      }).join('');
      panel.innerHTML = rows
        + (items.length === 1 ? '<p class="feed-quality-note">这个源只有这一路清晰度</p>' : '');
    }

    /** 换清晰度 = 换地址重挂。位置、倍速、播放状态都带过去。 */
    function pickQuality(page, url) {
      const video = page.querySelector('video');
      if (!video || !url || url === page.dataset.qualityPicked) return;
      const wasPlaying = !video.paused;
      const at = video.currentTime;
      const rate = video.playbackRate;
      page.dataset.qualityPicked = url;
      video.dataset.ready = '1';                  // attach() 里那句判断用不到，直接重挂
      loadInto(video, url);
      const restore = () => {
        video.removeEventListener('loadedmetadata', restore);
        try { if (at > 0) video.currentTime = at; } catch (error) {}
        video.playbackRate = rate;
        if (wasPlaying) video.play().catch(() => {});
      };
      video.addEventListener('loadedmetadata', restore);
      page.querySelector('.feed-spinner')?.classList.remove('hidden');
      urlRemember(page.dataset.videoId, Number(page.dataset.ep), url);
      updateQualityUi(page);
      toast('已切到 ' + (qualityLabelOf(page) || '这一路'));
    }

    async function render(videoId, startEpisode = 1) {
      cleanup();
      state.route = 'feed';
      let detail = (state.detail && String(state.detail.id) === String(videoId) && asArray(state.detail.episodes).length) ? state.detail : null;
      if (!detail) {
        const cached = readCache(`detail:${videoId}`);
        if (cached?.data && asArray(cached.data.episodes).length) detail = cached.data;
      }
      if (!detail) {
        content.innerHTML = '<div class="feed-boot">正在加载…</div>';
        try { detail = await api('detail', { id: videoId }); }
        catch (error) { content.innerHTML = `<div class="feed-boot">${esc(error.message || '加载失败')}</div>`; return; }
      }
      const episodes = asArray(detail.episodes);
      if (!episodes.length) { content.innerHTML = '<div class="feed-boot">这部剧还没有可播放的选集</div>'; return; }

      const feed = document.createElement('div');
      feed.className = 'feed';
      feed.dataset.orientation = selectedOrientation();
      feed.dataset.flip = savedFlip() ? '1' : '0';
      // 返回键和倍速/横屏控件放在独立的 overlay 里，横屏时跟着舞台一起旋转。
      // 只放一份，不随集数重复。
      feed.innerHTML = `<div class="feed-overlay">
          <button type="button" class="feed-back" aria-label="返回">${icon('back')}</button>
          ${controlsHtml()}
        </div>`
        + episodes.map(entry => {
            const n = entry.number ?? entry.episode ?? entry.ep ?? 0;
            // 竖屏时 .feed-stage 就是整屏；横屏时只有它被旋转 90°，滚动容器不动。
            return `<section class="feed-page" data-ep="${n}" data-video-id="${esc(videoId)}" data-cover="${esc(detail.cover || '')}" data-title="${esc(detail.title || '')}">
              <div class="feed-stage">
                <video playsinline webkit-playsinline preload="none"></video>
                <div class="feed-spinner"></div>
                <b class="feed-spinner-text hidden"></b>
                <div class="feed-error hidden"><span class="feed-error-text"></span><button type="button" class="feed-native">内置播放器</button><button type="button" class="feed-retry">重试</button><button type="button" class="feed-external">外部播放器</button></div>
                <div class="feed-speed">2×</div>
                <div class="feed-seek"><b class="feed-seek-delta">快进 0 秒</b><span class="feed-seek-clock">0:00 / 0:00</span></div>
                <div class="feed-brightness"><b class="feed-brightness-value">亮度 50%</b><i class="feed-brightness-fill"></i></div>
                <div class="feed-hud"><b>第 ${n} 集</b><span>${esc(detail.title || '')}</span></div>
                <div class="feed-bar"><i class="feed-bar-fill"></i></div>
                <div class="feed-times"><span class="feed-time-cur">0:00</span><span class="feed-time-total">--:--</span></div>
              </div>
            </section>`;
          }).join('');
      (document.getElementById('app') || document.body).appendChild(feed);
      updateRateUi(feed);
      applyOrientation(feed, selectedOrientation(), false);

      observer = new IntersectionObserver(entries => {
        for (const entry of entries) {
          // 先登记当前集再起播：play 事件要拿它判断该不该收起外壳。
          if (entry.isIntersecting && entry.intersectionRatio >= 0.55) {
            activePage = entry.target;
            play(entry.target);
          } else {
            pause(entry.target);
          }
        }
      }, { root: feed, threshold: [0, 0.55, 1] });
      feed.querySelectorAll('.feed-page').forEach(page => observer.observe(page));

      let holdTimer = 0;
      let wentLong = false;
      let speedPage = null;
      const endHold = () => {
        clearTimeout(holdTimer);
        holdTimer = 0;
        if (speedPage) {
          const v = speedPage.querySelector('video');
          if (v) { delete v.dataset.tempSpeed; v.playbackRate = selectedRate(); }
          speedPage.classList.remove('is-speed');
          speedPage = null;
        }
      };

      feed.addEventListener('pointerdown', event => {
        const externalBtn = event.target.closest('.feed-external');
        if (externalBtn) {
          const page = externalBtn.closest('.feed-page');
          const src = page?.querySelector('video')?.currentSrc || page?.querySelector('video')?.getAttribute('src') || '';
          if (!src) { toast('还没有可播放的地址', true); return; }
          window.HG_BRIDGE?.('player', { id: 'external?url=' + encodeURIComponent(src) })
            .then(reply => {
              const result = typeof reply === 'string' ? JSON.parse(reply) : reply;
              if (result && result.ok === false) toast(result.error || '没能打开外部播放器', true);
            })
            .catch(error => toast(error.message || '没能打开外部播放器', true));
          return;
        }
        if (event.target.closest('.feed-back, .feed-retry, .feed-bar, .feed-error, .feed-controls')) return;
        const page = event.target.closest('.feed-page');
        if (!page) return;
        wentLong = false;
        clearTimeout(holdTimer);
        holdTimer = setTimeout(() => {
          if (drag?.active) return;   // 已经在拖进度了，长按 2× 让位
          const v = page.querySelector('video');
          if (!v || v.paused || !v.duration) return;
          wentLong = true;
          speedPage = page;
          v.dataset.tempSpeed = '1';
          v.playbackRate = 2;
          page.classList.add('is-speed');
        }, HOLD_MS);
      });
      feed.addEventListener('pointerup', endHold);
      feed.addEventListener('pointercancel', endHold);
      feed.addEventListener('pointerleave', endHold);

      // 左右滑动拖动进度。轴的选择是这里唯一需要动脑的地方：
      // 滚动容器永远是竖向的，所以「屏幕横向」这条轴空着，拿它做 seek 不会跟上下滑切集抢。
      // 横屏虽然把画面转了 90°，手指位移仍在屏幕坐标系里，取横向照样能用。
      let drag = null;
      let seekSwipeAt = 0;

      const seekCeiling = video => {
        const dur = video.duration;
        if (dur && isFinite(dur) && dur > 0) return dur;
        // duration 还没定下来时（流刚起来那一瞬），退到浏览器给出的可跳范围。
        try {
          const ranges = video.seekable;
          if (ranges && ranges.length) return ranges.end(ranges.length - 1) || 0;
        } catch (error) {}
        return 0;
      };

      // 拖动中只改画面，不动播放头：进度条、当前时间、中央提示都按目标位置画，
      // 视频接着按原速播，松手才真正 seek。这样手上不会出现「跳过去又跳回来」。
      const paintDrag = () => {
        const { page, video, target, fromTime } = drag;
        const dur = video.duration || 0;
        const offset = Math.round(target - fromTime);
        const delta = page.querySelector('.feed-seek-delta');
        if (delta) delta.textContent = (offset < 0 ? '后退 ' : '快进 ') + Math.abs(offset) + ' 秒';
        const clock = page.querySelector('.feed-seek-clock');
        if (clock) clock.textContent = fmtClock(target) + ' / ' + fmtClock(dur);
        const fill = page.querySelector('.feed-bar-fill');
        if (fill && dur) fill.style.width = ((target / dur) * 100).toFixed(2) + '%';
        const cur = page.querySelector('.feed-time-cur');
        if (cur) cur.textContent = fmtClock(target);
      };

      const endDrag = commit => {
        if (!drag) return;
        const { page, video, fromTime, target, active, wasPlaying } = drag;
        drag = null;
        if (!active) return;
        page.classList.remove('is-seek');
        feed.classList.remove('is-seeking');
        if (!commit || Math.abs(target - fromTime) < 0.5) {
          // 手势被打断（pointercancel）也走这里：放弃这次 seek，但要把播放还回去，
          // 否则激活时暂停的那一下就成了"视频卡住不动"。
          if (wasPlaying) video.play().catch(() => {});
          return;
        }
        try { video.currentTime = target; } catch (error) {}
        if (wasPlaying) video.play().catch(() => {});
        const dur = video.duration;
        const fill = page.querySelector('.feed-bar-fill');
        if (fill && dur) fill.style.width = ((target / dur) * 100).toFixed(2) + '%';
        const cur = page.querySelector('.feed-time-cur');
        if (cur) cur.textContent = fmtClock(target);
      };

      /**
       * 手势收尾。两种模式收的东西不一样：
       *   seek   —— 落到目标位置、按原状态恢复播放（endDrag 里那套）
       *   bright —— 只把 HUD 收起来；亮度已经一格格写进窗口了，没有「确认」这一步
       */
      const endGesture = commit => {
        if (!drag) return;
        if (drag.mode === 'seek') { endDrag(commit); return; }
        drag.page?.classList.remove('is-bright');
        feed.classList.remove('is-bright');
        drag = null;
      };

      // ── 手势：两个功能共用一个 pointer 状态机，按**主导轴**分派 ──────
      //
      //   内容坐标里的横轴 → 拖进度（竖屏横屏都能用）
      //   内容坐标里的纵轴 → 横屏是亮度；竖屏不接（那条轴归滚动容器，用来切集）
      //
      // ── 为什么是「内容坐标」而不是屏幕坐标 ────────────────────────────
      // 横屏是纯 CSS 把 `.feed-stage` 转了 90°（见 app.css 那一节），视口本身
      // 还是竖的。所以「用户手上往左划」在视口里可能是 +x，也可能是 ±y ——
      // 取决于转了哪个方向（翻转开关）：
      //
      //   非翻转  stage(x, y) → 视口(100vw - y, x)   用户向右 = dy，用户向下 = -dx
      //   翻转    stage(x, y) → 视口(y, 100vh - x)   用户向右 = -dy，用户向下 = dx
      //
      // 上一版直接拿 dx 当「横向」，在竖屏下没问题，在横屏下的实际含义是
      // **物理上竖着划 = 拖进度、横着划 = 切集**（那也是有意的，见改造说明）。
      // 现在改成在内容坐标里判轴：横屏时物理横划 = 进度、物理竖划 = 亮度，
      // 和系统播放器一套手感。代价是横屏下不能滑动切集，所以控件里补了
      // 「上一集 / 下一集」两个按钮（只在横屏显示）。
      //
      // 那条被让出来的纵轴靠 CSS 的 touch-action:none 才抓得住 —— 横屏时原生
      // 滚动不再吃手势，同一个 pointer 序列才能按轴分流。

      /** 视口位移 → 内容坐标位移（用户视角：x 向右、y 向下）。 */
      function contentDelta(feed, dx, dy) {
        if (!isLandscape(feed)) return { x: dx, y: dy };
        const flip = feed.dataset.flip === '1';
        return flip ? { x: -dy, y: dx } : { x: dy, y: -dx };
      }

      function paintBrightness(page, value) {
        const label = page.querySelector('.feed-brightness-value');
        if (label) label.textContent = '亮度 ' + Math.round(value * 100) + '%';
        const fill = page.querySelector('.feed-brightness-fill');
        if (fill) fill.style.width = (value * 100).toFixed(1) + '%';
      }

      /**
       * 竖向划满多少像素等于走完 BRIGHT_SPAN 的量程。
       *
       * 取的是**内容坐标**里那条竖边的长度：竖屏就是视口高，横屏是视口宽
       * （画面转了 90°，用户在手上量到的「一屏高」正好是视口宽）。
       */
      function brightStep() {
        const extent = isLandscape(feed)
          ? (window.innerWidth || feed.clientWidth || 1)
          : (window.innerHeight || feed.clientHeight || 1);
        return Math.max(180, extent * BRIGHT_SPAN);
      }

      feed.addEventListener('pointerdown', event => {
        if (drag) endDrag(false);
        const externalBtn = event.target.closest('.feed-external');
        if (externalBtn) {
          const page = externalBtn.closest('.feed-page');
          const src = page?.querySelector('video')?.currentSrc || page?.querySelector('video')?.getAttribute('src') || '';
          if (!src) { toast('还没有可播放的地址', true); return; }
          window.HG_BRIDGE?.('player', { id: 'external?url=' + encodeURIComponent(src) })
            .then(reply => {
              const result = typeof reply === 'string' ? JSON.parse(reply) : reply;
              if (result && result.ok === false) toast(result.error || '没能打开外部播放器', true);
            })
            .catch(error => toast(error.message || '没能打开外部播放器', true));
          return;
        }
        if (event.target.closest('.feed-back, .feed-retry, .feed-bar, .feed-error, .feed-controls')) return;
        const page = event.target.closest('.feed-page');
        if (!page) return;
        const video = page.querySelector('video');
        const seekable = Boolean(video && video.duration && isFinite(video.duration));
        // 竖屏只需要「能拖进度」；横屏还多一条「能调亮度」，所以视频还没起来也接。
        if (!seekable && !isLandscape(feed)) return;
        drag = {
          page, video, seekable,
          x: event.clientX, y: event.clientY,
          fromTime: video ? video.currentTime : 0,
          target: video ? video.currentTime : 0,
          base: brightness == null ? 0.5 : brightness,
          value: brightness == null ? 0.5 : brightness,
          mode: '', active: false
        };
      });

      feed.addEventListener('pointermove', event => {
        if (!drag) return;
        const delta = contentDelta(feed, event.clientX - drag.x, event.clientY - drag.y);
        const dx = delta.x;
        const dy = delta.y;
        if (!drag.mode) {
          const horizontal = Math.abs(dx) >= SEEK_SLOP && Math.abs(dx) >= Math.abs(dy) * SEEK_BIAS;
          const vertical = Math.abs(dy) >= BRIGHT_SLOP && Math.abs(dy) > Math.abs(dx);
          // 谁先成立算谁，**之后不再改判** —— 手一抖就中途换功能比不响应更糟。
          if (horizontal && drag.seekable) drag.mode = 'seek';
          else if (vertical && isLandscape(feed)) drag.mode = 'bright';
          else return;
          endHold();                       // 和长按 2× 互斥，谁先成立算谁
          scrollToPage(feed, drag.page);   // 斜着划带起来的那几像素滚动，压回本集
          drag.active = true;
          if (drag.mode === 'seek') {
            drag.page.classList.add('is-seek');
            feed.classList.add('is-seeking');
            // 按住进度时先停住播放头：画面静止，预览的那个位置才是真的位置。
            // 松手后按原状态恢复，本来是暂停的就别自作主张播起来。
            drag.wasPlaying = !drag.video.paused;
            if (drag.wasPlaying) { try { drag.video.pause(); } catch (error) {} }
          } else {
            drag.page.classList.add('is-bright');
            feed.classList.add('is-bright');
          }
        }
        if (drag.mode === 'seek') {
          const span = Math.max(240, window.innerWidth || feed.clientWidth || 1);
          const ceiling = seekCeiling(drag.video);
          const raw = drag.fromTime + (dx / span) * SEEK_SPAN;
          drag.target = Math.max(0, ceiling ? Math.min(ceiling, raw) : raw);
          paintDrag();
        } else if (drag.mode === 'bright') {
          drag.value = Math.max(0.05, Math.min(1, drag.base - dy / brightStep()));
          brightness = drag.value;
          paintBrightness(drag.page, drag.value);
          // 不节流：一次 pointermove 一次桥调用。真的卡了再按帧合并，别提前优化。
          playerCommand?.('brightness?value=' + drag.value.toFixed(3)).catch(() => {});
        }
      });

      feed.addEventListener('pointerup', () => {
        if (drag?.mode === 'seek' && drag.active) seekSwipeAt = Date.now();
        endGesture(true);
      });
      feed.addEventListener('pointercancel', () => endGesture(false));

      feed.addEventListener('click', event => {
        if (event.target.closest('.feed-back')) { close(); return; }
        const controls = event.target.closest('.feed-controls');
        if (controls) {
          event.stopPropagation();
          const panel = feed.querySelector('.feed-rate-panel');
          const qualityPanel = feed.querySelector('.feed-quality-panel');
          if (event.target.closest('.feed-quality-toggle')) {
            qualityPanel?.classList.toggle('hidden');
            panel?.classList.add('hidden');
            return;
          }
          const qualityButton = event.target.closest('.feed-quality-option');
          if (qualityButton) {
            const page = activePage || feed.querySelector('.feed-page');
            if (page) pickQuality(page, qualityButton.dataset.quality);
            qualityPanel?.classList.add('hidden');
            return;
          }
          if (event.target.closest('.feed-ep-prev') || event.target.closest('.feed-ep-next')) {
            const back = Boolean(event.target.closest('.feed-ep-prev'));
            const here = activePage || feed.querySelector('.feed-page');
            const target = back ? here?.previousElementSibling : here?.nextElementSibling;
            if (target && target.classList.contains('feed-page')) scrollToPage(feed, target, 'smooth');
            return;
          }
          if (event.target.closest('.feed-native-toggle')) {
            openNative(activePage || feed.querySelector('.feed-page'), 'user');
            return;
          }
          if (event.target.closest('.feed-rate-toggle')) {
            panel?.classList.toggle('hidden');
            qualityPanel?.classList.add('hidden');
            return;
          }
          if (event.target.closest('.feed-orientation-toggle')) {
            const next = feed.dataset.orientation === 'landscape' ? 'portrait' : 'landscape';
            applyOrientation(feed, next, true);
            return;
          }
          if (event.target.closest('.feed-flip-toggle')) {
            applyFlip(feed, feed.dataset.flip !== '1');
            return;
          }
          return;
        }
        const nativeBtn = event.target.closest('.feed-native');
        if (nativeBtn) {
          const page = nativeBtn.closest('.feed-page');
          openNative(page, 'user');
          return;
        }
        const retry = event.target.closest('.feed-retry');
        if (retry) {
          const page = retry.closest('.feed-page');
          release(page);
          page.querySelector('.feed-error')?.classList.add('hidden');
          play(page);
          return;
        }
        // 错误框里除了两个按钮之外的地方也接住点击：手机上这两个按钮在
        // 旋转过的舞台上只有 40px 宽，手指差几个像素就落到空隙里，看起来就是
        // 「点了没反应」——朋友实测就是这么报的。可点区域放大到整个错误框，
        // 反正这时点空白处本来也只是暂停/继续，没有别的作用。
        const errorBox = event.target.closest('.feed-error');
        if (errorBox && !errorBox.classList.contains('hidden')) {
          openNative(errorBox.closest('.feed-page'), 'user');
          return;
        }
        if (event.target.closest('.feed-bar')) return;
        if (Date.now() - seekSwipeAt < 400) return;   // 刚拖完进度，这一下不算暂停/继续
        if (wentLong) { wentLong = false; return; }
        const page = event.target.closest('.feed-page');
        if (!page) return;
        const video = page.querySelector('video');
        if (!video) return;
        if (video.paused) video.play().catch(() => {}); else video.pause();
      });

      feed.addEventListener('pointerdown', event => {
        if (event.target.closest('.feed-controls')) event.stopPropagation();
      }, true);

      const target = feed.querySelector(`.feed-page[data-ep="${startEpisode}"]`) || feed.querySelector('.feed-page');
      requestAnimationFrame(() => scrollToPage(feed, target));
      updateBackEnabled();

      // 从内置播放器回来：Activity 盖在 WebView 上时页面只是 hidden，
      // 所以「回来」这件事在 visibilitychange 上，而不是某一次 render。
      currentFeed = feed;
      if (!window.__hgNativeWatcher) {
        window.__hgNativeWatcher = true;
        document.addEventListener('visibilitychange', () => {
          if (!document.hidden && currentFeed && document.body.contains(currentFeed)) {
            checkNativeReturn(currentFeed);
          }
        });
      }
      checkNativeReturn(feed);
    }

    // resume：原生 onResume 会调 hgResume()，那里要顺带把内置播放器的现场收回来
    // —— visibilitychange 在 Android WebView 上并不可靠（被 Activity 盖住时
    // document.hidden 常常一直是 false），那条路只当备份。
    return { open, close, cleanup, resume: () => checkNativeReturn() };
  }

  window.HGFeedPlayer = { create: createFeedPlayer };
})();
