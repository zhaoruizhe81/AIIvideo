// 西瓜短剧（uxbu.j4g3wltg.cc）—— 纯 JSON 接口站，后端和黄果短剧同一套。
//
// ── 接口清单（2026-11-03 实测，裸 curl + 手机 UA 全 200，无 cookie、无签名）──
//   GET /api/home                               banners / featured / latest / cate_rec / topic_rec
//   GET /api/videos?page=&size=                 全站最新 {items, pagination{page,pages,size,total}}
//   GET /api/videos?cate_id=<id>&page=&size=    按数字分类（24/25/26/27）
//   GET /api/videos/category/<slug>?page=&size= 按频道 slug（ai-duanju / ai-manju / …）
//   GET /api/search?q=<kw>&page=&size=          搜索（支持 page/size，pagination 同形）
//   GET /api/videos/<id>                        详情 + episodes[{ep_num,title,duration}]
//   GET /api/videos/<id>/play?ep=<n>            播放：video_url（H.264）/ video_url_hevc（H.265）
//   GET /api/tags                               categories[].tags[]（3 组 60 个标签）
//   GET /api/ranks/hot                          榜单（20 条，暂未接）
//
// ── 四个和别家不一样的地方（都踩过）────────────────────────────────
//
// ① **`status` 不是 HTTP 状态。** 成功是 `status:1 + msg:''`，失败是
//    `status:0 + msg:"该集不存在或已下架"` + `data:[]`，而 HTTP 一律 200。
//    判失败只能看 status/msg，或者看 data 的形状 —— 别看 2xx。
// ② **`/api/videos?tag=<slug>` 是个假参数。** 传了也返回全站（实测 total 573，
//    和不带参数完全一致），所以标签页只能退回站点自己的 SSR 页
//    `/tag/<slug>/page/<n>/`，那边是真筛选（后入 151 条）。别信接口的静默忽略。
// ③ **封面是 AES-CBC 加密图。** pic.drleao.cn 上返回 `binary/octet-stream`，
//    key/iv 和黄果那套一模一样（配置里 cover 块），第 6 字节还被 XOR 过
//    （headerRepair: legacy-xor）。少了这个块，封面全是灰块。
// ④ **首页接口的 cover 是相对路径且不带 auth_key**（`/upload_01/upload/…`），
//    列表接口给的是完整带签名的绝对地址。同一个站两套写法，不补 PIC 前缀
//    首页就是一片空图。
//
// ── 播放地址的时效 ─────────────────────────────────────────────────
//
// `video_url` 带 `auth_key=<unix>-0-0-<md5>`，是**签发时刻**不是过期时刻，
// 有效期由 CDN 决定。所以 play 必须每次现请求、零缓存 —— 缓存一次就等于
// 若干小时后用户点开是 403，而那个报错看起来像「没有权限」。
(function () {
  var BASE = 'https://uxbu.j4g3wltg.cc';
  // 封面图床。首页/杂项接口给相对路径时补这个前缀。
  var PIC = 'https://pic.drleao.cn';

  // 一页多少条。站点默认 20，上限实测能到 50；APP 这边 24 条一屏刚好。
  var PAGE_SIZE = 24;

  var JSON_HEADERS = { 'Accept': 'application/json, text/plain, */*' };

  function enc(v) {
    return encodeURIComponent(String(v == null ? '' : v));
  }

  function str(v) {
    return String(v == null ? '' : v).trim();
  }

  function num(v, fallback) {
    var n = Number(v);
    return isFinite(n) ? n : fallback;
  }

  function absCover(url) {
    var s = str(url);
    if (!s) return '';
    if (s.indexOf('http://') === 0) return 'https://' + s.substring(7);
    if (s.indexOf('https://') === 0) return s;
    if (s.charAt(0) === '/') return PIC + s;
    return s;
  }

  // 秒 → mm:ss。站点给的 duration 是秒（"261"、"223"）。
  function clock(seconds) {
    var total = num(seconds, 0);
    if (total <= 0) return '';
    var m = Math.floor(total / 60);
    var s = Math.floor(total % 60);
    return (m < 10 ? '0' + m : String(m)) + ':' + (s < 10 ? '0' + s : String(s));
  }

  /**
   * 一次 GET + 解 JSON。
   *
   * 失败要说清楚是哪一步：接口改版、域名换了、这一集下架，用户该做的事完全不同。
   * 统一成一句「请求失败」等于把判断成本全推给用户，而且日志里也查不出来。
   */
  async function getJson(ctx, path) {
    var res;
    try {
      res = await ctx.call('net', { url: BASE + path, headers: JSON_HEADERS });
    } catch (e) {
      throw new Error('西瓜接口请求失败 ' + path + '：' + ((e && e.message) || e));
    }
    if (!res || res.ok !== true) {
      throw new Error('西瓜接口请求失败 ' + path + '：' + ((res && res.error) || '没有响应'));
    }
    var body = String(res.body == null ? '' : res.body);
    if (!body) throw new Error('西瓜接口返回了空响应：' + path + '（可能被中间设备拦了）');
    var box;
    try {
      box = JSON.parse(body);
    } catch (e) {
      throw new Error('西瓜接口返回的不是 JSON：' + path + '（' + body.length + ' 字节，开头 '
        + body.slice(0, 60) + '）');
    }
    return box || {};
  }

  // 拿到 data，并把 status:0 翻成可读的错。
  function payloadOf(box, path) {
    if (!box || typeof box !== 'object') throw new Error('西瓜接口返回空对象：' + path);
    if (Number(box.status) !== 1) {
      throw new Error('西瓜接口报了 status=' + box.status + '：'
        + (str(box.msg) || '没有说明') + '（' + path + '）');
    }
    return box.data || {};
  }

  /** 列表项 → 卡片。字段名对齐 model.js 的 card()。 */
  function toCard(item) {
    if (!item || item.id == null) return null;
    var id = String(item.id);
    if (!id) return null;
    var episodes = num(item.episode_count, 0);
    var tagList = Array.isArray(item.tags) ? item.tags : [];
    var tags = [];
    for (var i = 0; i < tagList.length && tags.length < 3; i++) {
      var t = str(tagList[i]);
      if (t) tags.push(t);
    }
    return {
      id: id,
      title: str(item.title),
      cover: absCover(item.cover),
      // 短剧的集数比时长有信息量，角标优先「N集」，没有再退时长。
      badge: episodes > 0 ? (episodes + '集') : clock(item.duration),
      score: item.score == null ? '' : String(item.score),
      episodeCount: episodes,
      duration: clock(item.duration),
      tags: tags,
      description: str(item.description)
    };
  }

  function cardsOf(list) {
    var out = [];
    var seen = {};
    var arr = Array.isArray(list) ? list : [];
    for (var i = 0; i < arr.length; i++) {
      var card = toCard(arr[i]);
      if (!card || seen[card.id]) continue;
      seen[card.id] = 1;
      out.push(card);
    }
    return out;
  }

  /** pagination → hasMore。拿不到 pagination 就按「这页满了还有下一页」猜。 */
  function moreOf(pagination, page, count) {
    var pages = num(pagination && pagination.pages, 0);
    if (pages > 0) return page < pages;
    return count >= PAGE_SIZE;
  }

  function pageOf(args) {
    var n = num(args && args.page, 1);
    return n < 1 ? 1 : n;
  }

  /**
   * ref 三种形态都要兜住：`5033`、`5033:ep2`、`5033:2`。
   * 规范说按**最后一个**冒号切 —— 站点 id 是纯数字，但 ref 里可能被拼进别的段。
   */
  function splitRef(ref) {
    var raw = str(ref);
    var cut = raw.lastIndexOf(':');
    if (cut <= 0) return { id: raw, token: '' };
    return { id: raw.substring(0, cut), token: raw.substring(cut + 1) };
  }

  /** token/ep → 集号。'ep2' / '2' / '第2集' 都认。 */
  function epOf(token, fallback) {
    var s = str(token).toLowerCase();
    var m = s.match(/(\d+)/);
    if (m) {
      var n = Number(m[1]);
      if (isFinite(n) && n > 0) return n;
    }
    var f = num(fallback, 1);
    return f > 0 ? f : 1;
  }

  /** HTML 实体反转义（卡片属性里会出现 &amp; 和 &#39;）。 */
  function unesc(s) {
    return str(s)
      .replace(/&amp;/g, '&')
      .replace(/&lt;/g, '<')
      .replace(/&gt;/g, '>')
      .replace(/&quot;/g, '"')
      .replace(/&#0?39;/g, "'")
      .replace(/&nbsp;/g, ' ');
  }

  /**
   * 标签页（SSR）的卡片解析。
   *
   * 站点卡片的形状（/tag/houru/ 实抓）：
   *   <div class="hg-drama-card" data-track-id="6482" data-track-title="苗疆少女" …>
   *     …<img src="…占位…" data-src="https://pic.drleao.cn/…?auth_key=…" alt="苗疆少女" …
   *     …<span class="hg-drama-card__score">7.8分</span>
   *     …<span class="hg-drama-card__episode" data-ep-base="更新至2集">…
   *
   * 没有 DOM，只能切字符串。切块用 data-track-id 当锚，比按 class 名切稳 ——
   * class 会加修饰类（is-active / is-top），属性名不会。
   */
  function cardsFromHtml(html) {
    var out = [];
    var seen = {};
    var re = /<div class="hg-drama-card"([^>]*)>/g;
    var hits = [];
    var m;
    while ((m = re.exec(html)) !== null) {
      hits.push({ start: m.index, attrs: m[1] });
    }
    for (var i = 0; i < hits.length; i++) {
      var chunk = html.substring(hits[i].start, i + 1 < hits.length ? hits[i + 1].start : hits[i].start + 3000);
      var attrs = hits[i].attrs;
      var idm = attrs.match(/data-track-id="([^"]*)"/);
      var id = idm ? unesc(idm[1]) : '';
      if (!id || seen[id]) continue;
      seen[id] = 1;

      var tm = attrs.match(/data-track-title="([^"]*)"/);
      var title = tm ? unesc(tm[1]) : '';
      var cm = chunk.match(/data-src="([^"]+)"/);
      var cover = cm ? unesc(cm[1]) : '';
      if (!cover) {
        // 有的面板直接写 src（没走懒加载）。占位图不认。
        var sm = chunk.match(/<img[^>]+src="(https?:\/\/[^"]+)"/);
        if (sm) cover = unesc(sm[1]);
      }
      var scm = chunk.match(/hg-drama-card__score">([^<]*)</);
      var score = scm ? str(scm[1]).replace(/分$/, '') : '';
      var epm = chunk.match(/data-ep-base="[^"]*?(\d+)集/) || chunk.match(/更新至(\d+)集/);
      var episodeCount = epm ? num(epm[1], 0) : 0;

      out.push({
        id: id,
        title: title,
        cover: cover,
        score: score,
        badge: episodeCount > 0 ? (episodeCount + '集') : '',
        episodeCount: episodeCount
      });
    }
    return out;
  }

  __spiderSite = {
    id: 'xiguaduanju',
    name: '西瓜短剧',
    version: 1,
    requires: '>=1',
    hosts: ['uxbu.j4g3wltg.cc', 'pic.drleao.cn', 'yd-hls.hzjvlo.cn', 'tp3.drleao.cn'],

    /**
     * 首页。两个请求并发：
     *   /api/home                  运营位（featured + latest + cate_rec）
     *   /api/videos?page=1&size=24 全站最新，补足条数
     *
     * 合并顺序是 featured → latest → cate_rec → 全站最新：model.js 的 home()
     * 拿前 8 条当轮播，所以「运营想让人先看到什么」就排在最前面。
     * banners 不进 items —— 它是图文运营位，title 为空时塞进网格就是空格子。
     */
    async home(ctx) {
      var res;
      try {
        res = await ctx.call('netAll', {
          requests: [
            { url: BASE + '/api/home', headers: JSON_HEADERS },
            { url: BASE + '/api/videos?page=1&size=' + PAGE_SIZE, headers: JSON_HEADERS }
          ],
          concurrency: 2
        });
      } catch (e) {
        throw new Error('西瓜首页请求失败：' + ((e && e.message) || e));
      }
      var results = (res && res.results) || [];
      var boxes = [];
      for (var i = 0; i < results.length; i++) {
        var one = results[i] || {};
        if (one.ok !== true) continue;
        try {
          boxes.push(JSON.parse(String(one.body || '')));
        } catch (e) { /* 一块坏了不影响另一块 */ }
      }
      if (boxes.length === 0) {
        var why = results.map(function (r) { return (r && r.error) || '空响应'; }).join(' / ');
        throw new Error('西瓜首页两块都没拿到：' + why);
      }

      var all = [];
      for (var b = 0; b < boxes.length; b++) {
        var box = boxes[b];
        if (Number(box.status) !== 1) continue;
        var data = box.data || {};
        all = all.concat(cardsOf(data.featured));
        all = all.concat(cardsOf(data.latest));
        var recs = Array.isArray(data.cate_rec) ? data.cate_rec : [];
        for (var r = 0; r < recs.length; r++) {
          all = all.concat(cardsOf(recs[r] && recs[r].videos));
        }
        all = all.concat(cardsOf(data.items));
      }

      var out = [];
      var seen = {};
      for (var k = 0; k < all.length; k++) {
        if (seen[all[k].id]) continue;
        seen[all[k].id] = 1;
        out.push(all[k]);
      }
      if (out.length === 0) {
        throw new Error('西瓜首页没解析到任何卡片（' + boxes.length + ' 块响应都在，但 items 为空）。'
          + '可能：接口改版、或者首页内容被清空了。');
      }
      return { items: out, page: 1, hasMore: false };
    },

    /**
     * 分类。
     *
     * slug 有两种写法，都支持：
     *   数字   → /api/videos?cate_id=24   （和配置里的 ui.categoryIds 对齐）
     *   拼音   → /api/videos/category/ai-duanju
     * 空结果在第一页抛错：站点对不认识的分类回空列表而不是 404，
     * 静默返回空 items 的表现是「这个分类没有内容」，而实际是配置写错了。
     */
    async category(ctx, args) {
      var slug = str((args && (args.slug || args.cateId || args.ref)) || '');
      var page = pageOf(args);
      if (!slug) throw new Error('西瓜分类缺少 slug（检查源配置的 ui.categoryIds）');

      var path = /^\d+$/.test(slug)
        ? '/api/videos?cate_id=' + enc(slug) + '&page=' + page + '&size=' + PAGE_SIZE
        : '/api/videos/category/' + enc(slug) + '?page=' + page + '&size=' + PAGE_SIZE;

      var data = payloadOf(await getJson(ctx, path), path);
      var items = cardsOf(data.items);
      if (items.length === 0 && page === 1) {
        throw new Error('西瓜分类「' + slug + '」第一页就没有内容。'
          + '可能：这个 slug/cate_id 不在站点的分类表里（现有：ai-xiguanyuanchuan / '
          + 'ai-duanju / ai-manju / ai-mogai，对应 26 / 24 / 25 / 27）。');
      }
      return { items: items, page: page, hasMore: moreOf(data.pagination, page, items.length) };
    },

    /** 搜索。空关键词不发请求。engine 是站点的 vector 检索，命中标题与标签。 */
    async search(ctx, args) {
      var keyword = str((args && (args.keyword || args.q || args.ref)) || '');
      var page = pageOf(args);
      if (!keyword) throw new Error('西瓜搜索词为空，没有发请求（检查一下输入框是不是空的）');
      var path = '/api/search?q=' + enc(keyword) + '&page=' + page + '&size=' + PAGE_SIZE;
      var data = payloadOf(await getJson(ctx, path), path);
      var items = cardsOf(data.items);
      return { items: items, page: page, hasMore: moreOf(data.pagination, page, items.length) };
    },

    /**
     * 详情。
     *
     * episodes[] 每项是 {duration, ep_num, status, title}，status 是这一集的状态
     * （1 = 正常）。这里不按 status 拦 —— 拦了会让整部片打不开，而用户点具体
     * 那一集时 play 会给出更准确的原因（"该集不存在或已下架"）。
     *
     * 注意 episode_count 表示「已上架集数」，total_episodes 是「计划总集数」
     * （1003 号：已上 7、计划 12）。选集格子按已上架的来，集数说明用已上架数。
     */
    async detail(ctx, args) {
      var ref = splitRef(args && args.ref);
      var id = ref.id;
      if (!id) throw new Error('西瓜详情缺少 ref（视频 id）');
      if (!/^\d+$/.test(id)) throw new Error('西瓜详情 ref 不是数字 id：' + id);

      var path = '/api/videos/' + enc(id);
      var data = payloadOf(await getJson(ctx, path), path);
      if (!data || data.id == null) throw new Error('西瓜详情返回空（id=' + id + '）');

      var eps = Array.isArray(data.episodes) ? data.episodes : [];
      var episodes = [];
      for (var i = 0; i < eps.length; i++) {
        var ep = eps[i] || {};
        var no = num(ep.ep_num, i + 1);
        var title = str(ep.title);
        // 站点的 title 常常是「片名EP2」这种长串，格子上只留「第2集」更省地方。
        if (!title || title.length > 16) title = '第' + no + '集';
        episodes.push({ no: no, title: title, ref: 'ep' + no, line: 'default' });
      }
      if (episodes.length === 0) {
        // 一集都没上架时前端仍有「立即播放」按钮，空数组会让它变成死按钮。
        episodes.push({ no: 1, title: '正片', ref: 'ep1', line: 'default' });
      }

      var declared = num(data.episode_count, 0);
      var count = declared > 0 ? declared : episodes.length;

      return {
        id: String(data.id),
        title: str(data.title) || ('视频 ' + id),
        cover: absCover(data.cover),
        description: str(data.description),
        score: data.score == null ? '' : String(data.score),
        category: (data.breadcrumb && data.breadcrumb[0] && str(data.breadcrumb[0].name)) || '',
        tags: Array.isArray(data.tags) ? data.tags.map(str).filter(Boolean) : [],
        duration: clock(data.duration),
        episodes: episodes,
        episodeCount: count,
        // 两种编码并存是站点的常态：H.264 一定有，H.265 只有部分片子转好了。
        // 线路在这里只列名，具体哪条有地址由 play 现场判断。
        lines: [
          { line: 'hd', name: '高清 H.264' },
          { line: 'hevc', name: '高清 H.265' }
        ]
      };
    },

    /**
     * 播放。
     *
     * `/api/videos/<id>/play?ep=N` 直接回 video_url（H.264 m3u8）和
     * video_url_hevc（H.265 m3u8，可选）。**每次实时请求，零缓存**：
     * auth_key 是签名，缓存等于「过几小时点开就是 403」。
     *
     * 分片是 AES-128 HLS（m3u8 里带 #EXT-X-KEY），ts 和 crypt.key 在
     * tp3.drleao.cn —— 所以配置的 http.hosts 必须同时列 hzjvlo 和 drleao 两个域。
     */
    async play(ctx, args) {
      var ref = splitRef(args && args.ref);
      var id = ref.id;
      if (!id) throw new Error('西瓜播放缺少 ref（视频 id）');
      var ep = epOf(ref.token, args && args.ep);

      var path = '/api/videos/' + enc(id) + '/play?ep=' + ep;
      var box = await getJson(ctx, path);
      if (Number(box.status) !== 1) {
        // 降级，不是失败：这一集没上架/已下架，界面照常显示，只给一句原因。
        return {
          reason: 'no-source',
          note: '这一集没有地址：' + (str(box.msg) || '站点没有说明') + '（第 ' + ep + ' 集）'
        };
      }
      var data = box.data || {};
      var url = str(data.video_url);
      var hevc = str(data.video_url_hevc);
      if (!url && !hevc) {
        return { reason: 'no-source', note: '站点没有给出这一集的播放地址（第 ' + ep + ' 集）' };
      }

      var alts = [];
      if (url) alts.push({ line: 'hd', name: '高清 H.264', url: url });
      if (hevc) alts.push({ line: 'hevc', name: '高清 H.265', url: hevc });

      var want = str(args && args.line);
      var pick = alts[0];
      if (want && want !== 'default') {
        for (var i = 0; i < alts.length; i++) {
          if (alts[i].line === want) { pick = alts[i]; break; }
        }
      }

      return {
        url: pick.url,
        alts: alts,
        episodeNumber: ep,
        note: (want === 'hevc' && pick.line !== 'hevc')
          ? '这一集没有 H.265 版本，已回落到 H.264'
          : ''
      };
    },

    /**
     * 全部标签：/api/tags 给 3 个分区 60 个标签（另有一个空的 ai短剧 分区，跳过）。
     * group 直接用分区 code，配置里的 ui.tagGroups 抄同一份，否则标签页会
     * 「标题写着 60 个标签、正文空白」。
     */
    async tags(ctx) {
      var path = '/api/tags';
      var data = payloadOf(await getJson(ctx, path), path);
      var cats = Array.isArray(data.categories) ? data.categories : [];
      var out = [];
      var seen = {};
      for (var i = 0; i < cats.length; i++) {
        var cat = cats[i] || {};
        var group = str(cat.code) || 'other';
        var list = Array.isArray(cat.tags) ? cat.tags : [];
        for (var j = 0; j < list.length; j++) {
          var tag = list[j] || {};
          var name = str(tag.name);
          var slug = str(tag.slug) || name;
          if (!name || !slug) continue;
          var key = group + '|' + slug;
          if (seen[key]) continue;
          seen[key] = 1;
          out.push({ name: name, slug: slug, group: group });
        }
      }
      if (out.length === 0) throw new Error('西瓜标签接口没有给出任何标签（/api/tags）');
      return { items: out };
    },

    /**
     * 按标签列内容。
     *
     * **不能用 `/api/videos?tag=<slug>`**：那个参数被站点静默忽略（实测带 tag
     * 和不带 tag 的 total、items 完全一致，都是全站 573），拿它当标签页等于
     * 把「后入」点成了「全站最新」。所以退回站点自己的 SSR 页
     * `/tag/<slug>/page/<n>/`（后入 151 条，每页 20）。
     *
     * hasMore 用页面里的「共 N 条相关内容，每页展示 M 条」算，比数卡片准 ——
     * 末页不满 20 条时按条数猜会少给一页。
     */
    async tagList(ctx, args) {
      var slug = str((args && (args.slug || args.label || args.ref)) || '');
      var page = pageOf(args);
      if (!slug) throw new Error('西瓜标签为空，没有发请求');

      // 第一页不带 /page/1/ —— 站点 canonical 就是这个形状，少一次 301。
      var path = page <= 1
        ? '/tag/' + enc(slug) + '/'
        : '/tag/' + enc(slug) + '/page/' + page + '/';
      var res = await ctx.call('net', { url: BASE + path, headers: { 'Accept': 'text/html' } });
      if (!res || res.ok !== true) {
        throw new Error('西瓜标签页请求失败 ' + path + '：' + ((res && res.error) || '没有响应'));
      }
      var html = String(res.body == null ? '' : res.body);
      if (!html) throw new Error('西瓜标签页返回空：' + path);

      var items = cardsFromHtml(html);
      if (items.length === 0 && page === 1) {
        throw new Error('西瓜标签页没解析到卡片（' + path + '，' + html.length + ' 字节）。'
          + '可能：这个 slug 不存在，或者站点改版了卡片 class。');
      }

      var totalM = html.match(/共\s*(\d+)\s*条相关内容/);
      var sizeM = html.match(/每页展示\s*(\d+)\s*条/);
      var total = totalM ? num(totalM[1], 0) : 0;
      var size = sizeM ? num(sizeM[1], 20) : 20;
      var hasMore = total > 0 ? (page * size < total) : (items.length >= size);

      return { items: items, page: page, hasMore: hasMore };
    }
  };
})();
