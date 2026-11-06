// 黄鹂电影（katrinbahr.com）—— 服务端渲染的苹果CMS（maccms）+ Cloudflare 盾。
//
// 站点形状（2026-11-01 抓取确认，全部裸 HTML，无接口）：
//   GET /                        首页：轮播 + 若干区块，卡片 li.col-xs-4
//   GET /tv/{1..5}.html          分类：1 电影 / 2 电视剧 / 3 短剧 / 4 动漫 / 5 综艺
//   GET /tv/{n}/page/{p}.html    分类第 p 页；排序 /tv/{n}/by/{time|hits|score}.html
//   GET /new.html                最近更新（ranking-item 列表，无分页）
//   GET /search.html?wd={词}     搜索；第 p 页 /search/page/{p}/wd/{词}.html
//   GET /video/{id}.html         详情：h1 标题、封面（data-original 懒加载）、
//                                简介/分类/主演/更新、播放线路 tab（data-target="#ewave-playlist-N"）
//                                每个线路一个 <ul id="ewave-playlist-N">，里面是
//                                /play/{id}/{line}/{ep}.html
//   GET /play/{id}/{line}/{ep}.html  播放页：<script>var player_aaaa={...,"url":"https://…/index.m3u8",…}</script>
//
// ── 播放线路：为什么一次解析全部线路 ────────────────────────────────
//
// 站点把线路放在详情页的 tab 里（线路1/线路2/线路4/线路9），每条线路一个独立 CDN：
// 实测 2026-11-01 同一部剧四条线路分别是
//   dyttm3u8 → vip.dytt-tvs.com      （部分地区 403「The region has been denied」）
//   jinyingm3u8 → hd.kuktxu.com
//   hhm3u8 → play.hhuus.com
//   dbm3u8 → vodcnd16/17.ajupf.com、vodcnd16.0jtv2.com
// 也就是说「第一条线路在某个地区不可用」是常态，不是故障。所以 play 一次把**所有
// 线路的播放页**都取回来（netAll 并发），按原顺序摆进 alts，第一条当默认 url；
// 内置播放器起播失败会自动换 alts 里的下一条 —— 这是整条链路上唯一永远成立的兜底。
// 不做「先探测再选」：dytt 那种地区拒绝是秒回的 403，探测省不了多少；而真探测要
// 额外 GET 每个 m3u8，撞上慢 CDN 时单次 15 秒 × 重试，很容易把 90 秒预算烧穿。
//
// ── 封面 ─────────────────────────────────────────────────────────────
// 全部在 img.katrinbahr.com，lazyload 的 data-original 才是真图（src 是占位图）。
// 那个域同样在 CF 后面，配置里要写进 shield.hosts，否则封面永远 403。
(function () {
  var BASE = 'https://www.katrinbahr.com';

  // 分类 slug → 站点 /tv/{n}.html 的 n。slug 用英文，和配置里的 ui.categories 对齐。
  var CATS = { movie: 1, tv: 2, short: 3, anime: 4, variety: 5 };

  // ── 文本工具 ───────────────────────────────────────────────────────

  function unescape(s) {
    return String(s == null ? '' : s)
      .replace(/&quot;/g, '"')
      .replace(/&#39;/g, "'")
      .replace(/&apos;/g, "'")
      .replace(/&lt;/g, '<')
      .replace(/&gt;/g, '>')
      .replace(/&nbsp;/g, ' ')
      .replace(/&amp;/g, '&');
  }

  function text(s) {
    return unescape(String(s == null ? '' : s).replace(/<[^>]*>/g, ' '))
      .replace(/\s+/g, ' ').trim();
  }

  function enc(s) { return encodeURIComponent(String(s == null ? '' : s)); }

  function pageOf(v) {
    var n = parseInt(v, 10);
    return isNaN(n) || n < 1 ? 1 : n;
  }

  /** 一次 GET。失败抛人话错误（带地址，方便区分「拿到了挑战页」和「站改版」）。 */
  async function get(ctx, url, opts) {
    var args = { url: url };
    if (opts) for (var k in opts) args[k] = opts[k];
    var r = await ctx.net(args);
    if (!r || r.ok !== true) {
      throw new Error('请求失败：' + url + '（' + ((r && r.error) || '未知错误') + '）');
    }
    var body = r.body == null ? '' : String(r.body);
    if (/<title>\s*Just a moment\.\.\./i.test(body)) {
      // 盾后站点应该由壳处理；走到这里说明 shield.hosts 没声明对。
      throw new Error('站点返回了 Cloudflare 挑战页：' + url + '（检查配置里的 web.shield.hosts）');
    }
    return body;
  }

  // ── 列表卡片 ───────────────────────────────────────────────────────
  //
  // <li class="col-xs-4 …">
  //   <div class="pic"><a href="/video/{id}.html" title="…">
  //     <div class="img-wrapper lazyload img-wrapper-pic" data-original="{封面}"></div>
  //     <span class="fed-list-score">9.0</span><span class="vtitle text-right">第23集</span>
  //   </a></div>
  //   <div class="name"><h3><a href="/video/{id}.html" title="…">标题</a></h3>
  //     <p class="item-status text-overflow">演员…</p></div>
  // </li>

  var LI_RE = /<li class="col-xs-4[^"]*"[\s\S]{0,1600}?<\/li>/g;

  function cards(html) {
    var out = [];
    var m;
    LI_RE.lastIndex = 0;
    while ((m = LI_RE.exec(html))) {
      var li = m[0];
      var idM = /\/video\/(\d+)\.html/.exec(li);
      if (!idM) continue;
      var titleM = /<h3>[\s\S]{0,80}?<a[^>]*title="([^"]*)"/.exec(li) || /<a[^>]*title="([^"]*)"[^>]*>/.exec(li);
      var coverM = /data-original="([^"]+)"/.exec(li);
      var scoreM = /fed-list-score">([^<]*)</.exec(li);
      var badgeM = /vtitle[^"]*">([^<]*)</.exec(li);
      var actorM = /item-status[^>]*>([\s\S]{0,300}?)<\/p>/.exec(li);
      var title = text(titleM ? titleM[1] : '');
      if (!title) continue;
      var card = {
        id: idM[1],
        title: title,
        cover: coverM ? unescape(coverM[1]).trim() : '',
        description: actorM ? text(actorM[1]) : '',
        category: ''
      };
      var score = scoreM ? text(scoreM[1]) : '';
      if (score && score !== '0.0' && score !== '0') card.score = score;
      var badge = badgeM ? text(badgeM[1]) : '';
      if (badge) card.badge = badge;
      out.push(card);
    }
    return out;
  }

  /** 列表页的「还有下一页」：只认站点自己渲染的下一页链接，别拿总数猜。 */
  function hasNext(html, nextSuffix) {
    if (nextSuffix && html.indexOf(nextSuffix) >= 0) return true;
    // 不用正则：源码扫描器把「> 之后的 /」当成新正则的开头，而这里正好有
    // 「</span>/」这种序列，会让整份源的 __spiderSite 元信息读不出来（2026-11-01
    // 真机踩到，报「spider 元信息读不出来」）。纯字符串包含判断效果一样。
    return html.indexOf('下一页</span>') >= 0;
  }

  function listOut(html, page, nextSuffix) {
    var items = cards(html);
    return { items: items, page: page, hasMore: hasNext(html, nextSuffix) };
  }

  // ── 最近更新（/new.html 的 ranking-item 列表）─────────────────────
  function newestCards(html) {
    var out = [];
    var re = /<li class="ranking-item">[\s\S]{0,1200}?<\/li>/g;
    var m;
    while ((m = re.exec(html))) {
      var li = m[0];
      var idM = /\/video\/(\d+)\.html/.exec(li);
      if (!idM) continue;
      var titleM = /title="([^"]*)"/.exec(li);
      var coverM = /data-original="([^"]+)"/.exec(li);
      var card = {
        id: idM[1],
        title: text(titleM ? titleM[1] : ''),
        cover: coverM ? unescape(coverM[1]).trim() : ''
      };
      if (!card.title) continue;
      out.push(card);
    }
    return out;
  }

  // ── 详情 ───────────────────────────────────────────────────────────

  function detailFields(html) {
    var titleM = /<h1><a[^>]*>([\s\S]{0,120}?)<\/a><\/h1>/.exec(html)
      || /<h1[^>]*>([\s\S]{0,120}?)<\/h1>/.exec(html);
    var coverM = /<div class="pic"><img[^>]*class="lazyload"[^>]*data-original="([^"]+)"/.exec(html)
      || /<div class="pic"><img[^>]*data-original="([^"]+)"/.exec(html);
    var descM = /简介[：:]\s*([\s\S]{0,800}?)<\/(?:p|div)>/.exec(html);
    var catM = /分类[：:]\s*<a[^>]*>([^<]{1,30})<\/a>/.exec(html);
    var areaM = /地区[：:]\s*<a[^>]*>([^<]{1,20})<\/a>/.exec(html);
    var yearM = /年份[：:]\s*<a[^>]*>([^<]{1,10})<\/a>/.exec(html);
    var updM = /更新[：:]\s*([^<\n]{1,40})/.exec(html);
    var actorM = /主演[：:]\s*([\s\S]{0,300}?)<\/span>/.exec(html);
    var scoreM = /综合评分为\s*([0-9.]+)/.exec(html);
    return {
      title: text(titleM ? titleM[1] : ''),
      cover: coverM ? unescape(coverM[1]).trim() : '',
      description: descM ? text(descM[1]) : '',
      category: text(catM ? catM[1] : ''),
      area: text(areaM ? areaM[1] : ''),
      year: text(yearM ? yearM[1] : ''),
      update: text(updM ? updM[1] : ''),
      actors: text(actorM ? actorM[1] : ''),
      score: scoreM ? scoreM[1] : ''
    };
  }

  /**
   * 播放线路 tab + 每个线路的剧集锚点。
   *
   * <li class="swiper-slide ewave-tab" data-target="#ewave-playlist-3">线路1<em></em></li>
   * <ul id="ewave-playlist-3"> <li …><a href="/play/{id}/3/{ep}.html">第01集</a></li> …
   */
  function linesOf(html, id) {
    var lines = [];
    var tabs = [];
    var tabRe = /data-target="#ewave-playlist-(\d+)">([\s\S]{0,40}?)<\/li>/g;
    var m;
    while ((m = tabRe.exec(html))) {
      var name = text(m[2]);
      if (!name) continue;
      tabs.push({ line: m[1], name: name });
    }
    for (var i = 0; i < tabs.length; i++) {
      var lineId = tabs[i].line;
      var ulRe = new RegExp('<ul[^>]*id="ewave-playlist-' + lineId + '"[\\s\\S]*?</ul>');
      var ulM = ulRe.exec(html);
      var eps = [];
      if (ulM) {
        var aRe = /<a[^>]*href="\/play\/[^"]*?\/(\d+)\/(\d+)\.html"[^>]*>([\s\S]{0,30}?)<\/a>/g;
        var a;
        while ((a = aRe.exec(ulM[0]))) {
          eps.push({ no: parseInt(a[2], 10), title: text(a[3]) || ('第' + a[2] + '集'), ref: a[2] });
        }
      }
      lines.push({ line: lineId, name: tabs[i].name, eps: eps });
    }
    return lines;
  }

  // ── 播放页 ─────────────────────────────────────────────────────────

  /** 从播放页里抠 player_aaaa → {url, url_next, from}。 */
  function playerOf(html) {
    var m = /player_aaaa\s*=\s*(\{[\s\S]*?\})\s*<\/script>/.exec(html);
    if (!m) return null;
    var obj;
    try {
      obj = JSON.parse(m[1]);
    } catch (e) {
      return null;
    }
    var url = String((obj && obj.url) || '').trim();
    if (!/^https?:\/\//i.test(url)) return null;
    return { url: url, urlNext: String(obj.url_next || ''), from: String(obj.from || '') };
  }

  function splitRef(ref) {
    var raw = String(ref == null ? '' : ref).trim();
    var out = { id: raw, ep: '' };
    var colon = raw.lastIndexOf(':');
    if (colon > 0) {
      out.id = raw.substring(0, colon);
      out.ep = raw.substring(colon + 1);
    }
    return out;
  }

  __spiderSite = {
    id: 'katrinbahr',
    name: '黄鹂电影',
    version: 1,
    requires: '>=1',
    hosts: [
      'www.katrinbahr.com',
      'katrinbahr.com',
      'img.katrinbahr.com',
      'vip.dytt-tvs.com',
      'hd.kuktxu.com',
      'play.hhuus.com',
      
      'vodcnd16.ajupf.com',
      'vodcnd17.ajupf.com',
      'vodcnd16.0jtv2.com',
      'vodcnd17.0jtv2.com',
      'v.fengbao14.com'
    ],

    async home(ctx) {
      var html = await get(ctx, BASE + '/');
      return listOut(html, 1, '');
    },

    async category(ctx, args) {
      args = args || {};
      var slug = String(args.slug || args.id || '').trim();
      var n = CATS[slug] || parseInt(slug, 10) || 1;
      var page = pageOf(args.page);
      var url = page <= 1
        ? BASE + '/tv/' + n + '.html'
        : BASE + '/tv/' + n + '/page/' + page + '.html';
      var html = await get(ctx, url);
      return listOut(html, page, page + 1 > 1 ? '/tv/' + n + '/page/' + (page + 1) + '.html' : '');
    },

    async search(ctx, args) {
      args = args || {};
      var kw = String(args.keyword || args.q || '').trim();
      var page = pageOf(args.page);
      if (!kw) return { items: [], page: 1, hasMore: false };
      var url = page <= 1
        ? BASE + '/search.html?wd=' + enc(kw)
        : BASE + '/search/page/' + page + '/wd/' + enc(kw) + '.html';
      var html = await get(ctx, url);
      return listOut(html, page, '/search/page/' + (page + 1) + '/wd/');
    },

    async newest(ctx, args) {
      args = args || {};
      var page = pageOf(args.page);
      if (page > 1) return { items: [], page: page, hasMore: false };
      var html = await get(ctx, BASE + '/new.html');
      return { items: newestCards(html), page: 1, hasMore: false };
    },

    async detail(ctx, args) {
      args = args || {};
      var id = String(args.ref || args.id || '').trim();
      if (!/^\d+$/.test(id)) throw new Error('黄鹂电影 详情 id 不合法：' + id);
      var html = await get(ctx, BASE + '/video/' + id + '.html');
      var f = detailFields(html);
      var groups = linesOf(html, id);
      var lines = [];
      var episodes = [];
      for (var i = 0; i < groups.length; i++) {
        lines.push({ line: groups[i].line, name: groups[i].name });
        if (i === 0) {
          for (var j = 0; j < groups[i].eps.length; j++) {
            var ep = groups[i].eps[j];
            episodes.push({ no: ep.no, ref: ep.ref, title: ep.title, line: groups[i].line });
          }
        }
      }
      if (!groups.length) {
        throw new Error('详情页没解析到播放线路（' + html.length + ' 字节，id=' + id + '）');
      }
      var tags = [];
      if (f.category) tags.push(f.category);
      if (f.year) tags.push(f.year);
      if (f.area) tags.push(f.area);
      var out = {
        id: id,
        title: f.title,
        cover: f.cover,
        description: f.description,
        category: f.category,
        tags: tags,
        episodes: episodes,
        episodeCount: episodes.length,
        lines: lines,
        related: []
      };
      if (f.score) out.score = f.score;
      if (f.update) out.badge = f.update;
      if (f.actors) out.playNote = '主演：' + f.actors;
      return out;
    },

    /**
     * 播放：把**全部线路**这一集的播放页取回来，按站点顺序摆进 alts。
     *
     * ref 两种形态都接：
     *   '3302189709'        → 第 1 集
     *   '3302189709:23'     → 第 23 集（前端 spiderEpisodeRef 拼出来的）
     */
    async play(ctx, args) {
      args = args || {};
      var parts = splitRef(args.ref || args.id);
      var id = parts.id;
      var ep = parseInt(parts.ep || args.ep || args.episode, 10);
      if (!/^\d+$/.test(id)) throw new Error('黄鹂电影 播放 ref 不合法：' + (args.ref || ''));
      if (isNaN(ep) || ep < 1) ep = 1;

      var detailHtml = await get(ctx, BASE + '/video/' + id + '.html');
      var groups = linesOf(detailHtml, id);
      var lineIds = [];
      for (var i = 0; i < groups.length; i++) {
        if (groups[i].eps && groups[i].eps.length) lineIds.push(groups[i]);
      }
      if (!lineIds.length) {
        throw new Error('这部剧没有可播放的线路（id=' + id + '，详情页 ' + detailHtml.length + ' 字节）');
      }

      // netAll 一次把各线路的播放页并发取回；单项失败只丢那一条线，不影响别的。
      var reqs = [];
      for (var k = 0; k < lineIds.length && reqs.length < 30; k++) {
        reqs.push({ url: BASE + '/play/' + id + '/' + lineIds[k].line + '/' + ep + '.html' });
      }
      var results;
      if (reqs.length === 1) {
        results = { results: [{ ok: true, body: await get(ctx, reqs[0].url) }] };
      } else {
        results = await ctx.call('netAll', { requests: reqs, concurrency: 8 });
      }

      var list = (results && results.results) || [];
      var alts = [];
      for (var r = 0; r < list.length; r++) {
        var one = list[r] || {};
        if (one.ok !== true) continue;
        var player = playerOf(String(one.body || ''));
        if (!player) continue;
        var name = lineIds[r] ? lineIds[r].name : ('线路' + (r + 1));
        alts.push({ line: lineIds[r] ? lineIds[r].line : String(r + 1), name: name, url: player.url });
      }
      if (!alts.length) {
        throw new Error('这一集所有线路都没解析到 m3u8（id=' + id + ' ep=' + ep + '，'
          + list.length + ' 条线路）。可能：线路全挂，或站点改了播放页结构。');
      }

      return {
        url: alts[0].url,
        source: alts[0].url,
        alts: alts,
        episodeNumber: ep,
        note: alts.length > 1
          ? '这条线路放不出来时，播放器会自动换下一条（共 ' + alts.length + ' 条）'
          : ''
      };
    }
  };
})();
