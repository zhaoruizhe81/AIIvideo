// 欧乐影院（www.olevod.com）—— Vue SPA + Go WASM 站点，真正数据在 api.olelive.com。
//
// ── 逆向结论（2026-11-07 实测）────────────────────────────────────────
// 首页 https://www.olevod.com/ 是一坨 Vue 壳，HTML 里没有数据；接口在
//   BASE = https://api.olelive.com
// 接口全部是 GET，路径参数（不是 query）。已确认的端点：
//   GET /v1/pub/index/banners                       轮播（bannerType=4 是影片）
//   GET /v1/pub/vod/newest/{page}/{size}            全站最新 {list,total,page,pageSize}
//   GET /v1/pub/vod/list/{a}/{b}/{area}/{year}/{typeId1}/{typeId}/{letter}/{order}/{page}/{size}
//                                                  分类列表 {list,total,page,pageSize}（带总数，能算 hasMore）
//   GET /v1/pub/index/search/{kw}/{page}/0/0/0      搜索；返回 data.data[] 按 type 分组，取 type==='vod'
//   GET /v1/pub/vod/detail/{id}/1                   详情；第二参=1 时才把每集真实 m3u8 填进 urls[].url
//                                                   （传 0 只给空 url 的选集目录）
//
// ── 三件必须自己算的事 ──────────────────────────────────────────────
// ① **每个 GET 都要带 `_vv` 签名**，否则 401「用户签名错误」。算法（前端 tv-pc-main.js）：
//      t = 当前 unix 秒（整数）
//      r[0..3] = 把 t 每一位的 ASCII 二进制按位[2]/[3]/[4]/[5..] 各拼一列
//      a[i]   = parseInt(r[i],2).toString(16)，补零到 3 位
//      n      = md5(t)
//      _vv    = n[0:3]+a0+n[6:11]+a1+n[14:19]+a2+n[22:27]+a3+n[30:]   （32 位十六进制）
//    实测：整数秒可通过；带小数（ms/1000 原样）会被拒，所以这里 Math.floor。
// ② **响应是明文**（首页 config `encrypt:false`），不用 AES 解；前端那套
//    AES-CBC(key=iv=md5(日期)[8:24]) 是给 encrypt:true 的镜像站准备的，本镜像不触发。
// ③ **详情必须请求 /1**。第二参是「要不要解析直链」的开关：0 → urls[].url 全空，
//    1 → 全填好。用它一个请求同时拿元数据和每集 m3u8，播放阶段再按集号取一次。
//
// ── 播放 ────────────────────────────────────────────────────────────
// m3u8 是普通 HLS，分片相对同域。抽了 432 部（7360 条 url）只出现两个 CDN：
//   cloud.yuntongclouds.com / dubo.oulehdtv.tv
// 这俩必须写进配置的 http.hosts（精确匹配），否则「列表能看、点播报格式不支持」。
// 午夜影院/部分蓝光片是 VIP 专属，匿名 detail 里 url 为空、vip=true → play 返回
// reason:'vip' 降级，不塞密文。
(function () {
  var API = 'https://api.olelive.com';
  var IMG = 'https://static.olelive.com';
  var PAGE_SIZE = 36;
  // typeId 顶层分类（其余 number 都当子分类，走第 6 个位置参数）
  var TOP = { '1': 1, '2': 1, '3': 1, '4': 1, '5': 1, '6': 1, '14': 1 };

  var JSON_HEADERS = { 'Accept': 'application/json, text/plain, */*' };

  function str(v) { return String(v == null ? '' : v).trim(); }
  function num(v, d) { var n = Number(v); return isFinite(n) ? n : d; }
  function enc(v) { return encodeURIComponent(String(v == null ? '' : v)); }
  function pageOf(args) { var n = num(args && args.page, 1); return n < 1 ? 1 : n; }
  function pad2(n) { n = num(n, 0); return n < 10 ? '0' + n : String(n); }
  function imageOf(pic) {
    var s = str(pic);
    if (!s) return '';
    if (s.indexOf('http://') === 0) return 'https://' + s.substring(7);
    if (s.indexOf('https://') === 0) return s;
    if (s.charAt(0) === '/') return IMG + s;
    return IMG + '/' + s;
  }

  // ── md5（Joseph Myers 公有领域实现，输入为 ASCII，本处只需对秒数串求 md5）──
  function md5cycle(x, k) {
    var a = x[0], b = x[1], c = x[2], d = x[3];
    a = ff(a, b, c, d, k[0], 7, -680876936); d = ff(d, a, b, c, k[1], 12, -389564586);
    c = ff(c, d, a, b, k[2], 17, 606105819); b = ff(b, c, d, a, k[3], 22, -1044525330);
    a = ff(a, b, c, d, k[4], 7, -176418897); d = ff(d, a, b, c, k[5], 12, 1200080426);
    c = ff(c, d, a, b, k[6], 17, -1473231341); b = ff(b, c, d, a, k[7], 22, -45705983);
    a = ff(a, b, c, d, k[8], 7, 1770035416); d = ff(d, a, b, c, k[9], 12, -1958414417);
    c = ff(c, d, a, b, k[10], 17, -42063); b = ff(b, c, d, a, k[11], 22, -1990404162);
    a = ff(a, b, c, d, k[12], 7, 1804603682); d = ff(d, a, b, c, k[13], 12, -40341101);
    c = ff(c, d, a, b, k[14], 17, -1502002290); b = ff(b, c, d, a, k[15], 22, 1236535329);
    a = gg(a, b, c, d, k[1], 5, -165796510); d = gg(d, a, b, c, k[6], 9, -1069501632);
    c = gg(c, d, a, b, k[11], 14, 643717713); b = gg(b, c, d, a, k[0], 20, -373897302);
    a = gg(a, b, c, d, k[5], 5, -701558691); d = gg(d, a, b, c, k[10], 9, 38016083);
    c = gg(c, d, a, b, k[15], 14, -660478335); b = gg(b, c, d, a, k[4], 20, -405537848);
    a = gg(a, b, c, d, k[9], 5, 568446438); d = gg(d, a, b, c, k[14], 9, -1019803690);
    c = gg(c, d, a, b, k[3], 14, -187363961); b = gg(b, c, d, a, k[8], 20, 1163531501);
    a = gg(a, b, c, d, k[13], 5, -1444681467); d = gg(d, a, b, c, k[2], 9, -51403784);
    c = gg(c, d, a, b, k[7], 14, 1735328473); b = gg(b, c, d, a, k[12], 20, -1926607734);
    a = hh(a, b, c, d, k[5], 4, -378558); d = hh(d, a, b, c, k[8], 11, -2022574463);
    c = hh(c, d, a, b, k[11], 16, 1839030562); b = hh(b, c, d, a, k[14], 23, -35309556);
    a = hh(a, b, c, d, k[1], 4, -1530992060); d = hh(d, a, b, c, k[4], 11, 1272893353);
    c = hh(c, d, a, b, k[7], 16, -155497632); b = hh(b, c, d, a, k[10], 23, -1094730640);
    a = hh(a, b, c, d, k[13], 4, 681279174); d = hh(d, a, b, c, k[0], 11, -358537222);
    c = hh(c, d, a, b, k[3], 16, -722521979); b = hh(b, c, d, a, k[6], 23, 76029189);
    a = hh(a, b, c, d, k[9], 4, -640364487); d = hh(d, a, b, c, k[12], 11, -421815835);
    c = hh(c, d, a, b, k[15], 16, 530742520); b = hh(b, c, d, a, k[2], 23, -995338651);
    a = ii(a, b, c, d, k[0], 6, -198630844); d = ii(d, a, b, c, k[7], 10, 1126891415);
    c = ii(c, d, a, b, k[14], 15, -1416354905); b = ii(b, c, d, a, k[5], 21, -57434055);
    a = ii(a, b, c, d, k[12], 6, 1700485571); d = ii(d, a, b, c, k[3], 10, -1894986606);
    c = ii(c, d, a, b, k[10], 15, -1051523); b = ii(b, c, d, a, k[1], 21, -2054922799);
    a = ii(a, b, c, d, k[8], 6, 1873313359); d = ii(d, a, b, c, k[15], 10, -30611744);
    c = ii(c, d, a, b, k[6], 15, -1560198380); b = ii(b, c, d, a, k[13], 21, 1309151649);
    a = ii(a, b, c, d, k[4], 6, -145523070); d = ii(d, a, b, c, k[11], 10, -1120210379);
    c = ii(c, d, a, b, k[2], 15, 718787259); b = ii(b, c, d, a, k[9], 21, -343485551);
    x[0] = add32(a, x[0]); x[1] = add32(b, x[1]); x[2] = add32(c, x[2]); x[3] = add32(d, x[3]);
  }
  function cmn(q, a, b, x, s, t) { a = add32(add32(a, q), add32(x, t)); return add32((a << s) | (a >>> (32 - s)), b); }
  function ff(a, b, c, d, x, s, t) { return cmn((b & c) | ((~b) & d), a, b, x, s, t); }
  function gg(a, b, c, d, x, s, t) { return cmn((b & d) | (c & (~d)), a, b, x, s, t); }
  function hh(a, b, c, d, x, s, t) { return cmn(b ^ c ^ d, a, b, x, s, t); }
  function ii(a, b, c, d, x, s, t) { return cmn(c ^ (b | (~d)), a, b, x, s, t); }
  function md5blk(s) {
    var b = [], i;
    for (i = 0; i < 64; i += 4) {
      b[i >> 2] = s.charCodeAt(i) + (s.charCodeAt(i + 1) << 8) + (s.charCodeAt(i + 2) << 16) + (s.charCodeAt(i + 3) << 24);
    }
    return b;
  }
  function md51(s) {
    var n = s.length, state = [1732584193, -271733879, -1732584194, 271733878], i;
    for (i = 64; i <= n; i += 64) { md5cycle(state, md5blk(s.substring(i - 64, i))); }
    s = s.substring(i - 64);
    var tail = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0];
    for (i = 0; i < s.length; i++) tail[i >> 2] |= s.charCodeAt(i) << ((i % 4) << 3);
    tail[i >> 2] |= 0x80 << ((i % 4) << 3);
    if (i > 55) { md5cycle(state, tail); for (i = 0; i < 16; i++) tail[i] = 0; }
    tail[14] = n * 8;
    md5cycle(state, tail);
    return state;
  }
  function add32(a, b) { return (a + b) & 0xFFFFFFFF; }
  function rhex(n) {
    var s = '', j;
    for (j = 0; j < 4; j++) s += ((n >> (j * 8 + 4)) & 0x0F).toString(16) + ((n >> (j * 8)) & 0x0F).toString(16);
    return s;
  }
  function md5(s) { return md51(s).map(rhex).join(''); }

  // fe(整数秒) → _vv 签名
  function vv() {
    var t = String(Math.floor(Date.now() / 1000));
    var r = ['', '', '', ''], i, b;
    for (i = 0; i < t.length; i++) {
      b = t.charCodeAt(i).toString(2);
      r[0] += b.slice(2, 3); r[1] += b.slice(3, 4); r[2] += b.slice(4, 5); r[3] += b.slice(5);
    }
    var a = [];
    for (i = 0; i < r.length; i++) {
      var h = parseInt(r[i], 2).toString(16);
      if (h.length === 2) h = '0' + h; else if (h.length === 1) h = '00' + h; else if (h.length === 0) h = '000';
      a.push(h);
    }
    var n = md5(t);
    return n.slice(0, 3) + a[0] + n.slice(6, 11) + a[1] + n.slice(14, 19) + a[2] + n.slice(22, 27) + a[3] + n.slice(30);
  }

  function apiUrl(path) {
    var sep = path.indexOf('?') >= 0 ? '&' : '?';
    return API + path + sep + '_vv=' + vv();
  }

  async function getJson(ctx, path) {
    var res;
    try {
      res = await ctx.call('net', { url: apiUrl(path), headers: JSON_HEADERS });
    } catch (e) {
      throw new Error('欧乐接口异常 ' + path + '：' + ((e && e.message) || e));
    }
    if (!res || res.ok !== true) {
      throw new Error('欧乐接口请求失败 ' + path + '：' + ((res && res.error) || '没有响应'));
    }
    var body = String(res.body == null ? '' : res.body);
    if (!body) throw new Error('欧乐接口返回空响应：' + path + '（可能被中间设备拦了）');
    var box;
    try { box = JSON.parse(body); } catch (e) {
      throw new Error('欧乐接口返回的不是 JSON：' + path + '（' + body.length + ' 字节，开头 ' + body.slice(0, 60) + '）');
    }
    if (Number(box.code) !== 0) {
      throw new Error('欧乐接口 code=' + box.code + '：' + (str(box.msg) || '没有说明') + '（' + path + '）');
    }
    return box;
  }

  // netAll 并发取多份，返回与 paths 等长的数组（失败位是 null）。
  async function getMany(ctx, paths) {
    var reqs = paths.map(function (p) { return { url: apiUrl(p), headers: JSON_HEADERS }; });
    var res = await ctx.call('netAll', { requests: reqs, concurrency: 8 });
    var results = (res && res.results) || [];
    return paths.map(function (p, i) {
      var r = results[i];
      if (!r || r.ok !== true) return null;
      try { return JSON.parse(String(r.body == null ? '' : r.body)); } catch (e) { return null; }
    });
  }

  function toCard(it) {
    if (!it || it.id == null) return null;
    var id = String(it.id);
    if (!id) return null;
    var tags = [];
    var extra = [it.area, it.year, it.lang];
    for (var i = 0; i < extra.length; i++) {
      var t = str(extra[i]);
      if (t && tags.indexOf(t) < 0 && tags.length < 3) tags.push(t);
    }
    return {
      id: id,
      title: str(it.name),
      // 接口大图 pic 常是 WebP（部分变体 App 解不了码，表现为灰块）；缩略图 picThumb 是
      // 标准 JPEG，必能解，所以优先缩略图。
      cover: imageOf(it.picThumb || it.pic),
      score: it.score == null ? '' : String(it.score),
      badge: str(it.remarks) || str(it.version),
      tags: tags,
      description: str(it.blurb)
    };
  }

  function cardsOf(list) {
    var out = [], seen = {}, arr = Array.isArray(list) ? list : [];
    for (var i = 0; i < arr.length; i++) {
      var c = toCard(arr[i]);
      if (!c || seen[c.id]) continue;
      seen[c.id] = 1;
      out.push(c);
    }
    return out;
  }

  // 详情 / 播放共用的 ref 切分：按最后一个冒号。
  function splitRef(ref) {
    var raw = str(ref);
    var cut = raw.lastIndexOf(':');
    if (cut <= 0) return { id: raw, token: '' };
    return { id: raw.substring(0, cut), token: raw.substring(cut + 1) };
  }

  __spiderSite = {
    id: 'olevod',
    name: '欧乐影院',
    version: 1,
    requires: '>=1',
    hosts: ['api.olelive.com'],

    async home(ctx) {
      var paths = ['/v1/pub/index/banners', '/v1/pub/vod/newest/1/12'];
      var tids = [1, 2, 3, 4, 14];
      for (var i = 0; i < tids.length; i++) {
        paths.push('/v1/pub/vod/list/1/3/0/0/' + tids[i] + '/0/0/update/1/12');
      }
      var boxes = await getMany(ctx, paths);

      var items = [], seen = {};
      function push(card) {
        if (!card || seen[card.id]) return;
        seen[card.id] = 1;
        items.push(card);
      }

      // 轮播（bannerType=4 才是影片；其它类型（直播）点进去不是详情页，跳过）
      var banners = boxes[0] && boxes[0].data;
      if (Array.isArray(banners)) {
        for (var b = 0; b < banners.length; b++) {
          var bn = banners[b] || {};
          if (bn.id == null) continue;
          push({
            id: String(bn.id),
            title: str(bn.title),
            cover: imageOf(bn.img),
            badge: str(bn.desc),
            description: ''
          });
        }
      }
      // 最新
      var newest = boxes[1] && boxes[1].data;
      cardsOf(newest && newest.list).forEach(push);
      // 各分类最新
      for (var c = 2; c < boxes.length; c++) {
        var d = boxes[c] && boxes[c].data;
        cardsOf(d && d.list).forEach(push);
      }

      if (!items.length) {
        throw new Error('欧乐首页没解析到卡片（banners/newest/list 全空）');
      }
      return { items: items, page: 1, hasMore: false };
    },

    async category(ctx, args) {
      var slug = str(args && args.slug);
      var page = pageOf(args);
      if (!slug) throw new Error('分类 slug 为空，没有发请求');
      var id = parseInt(slug, 10);
      if (!isFinite(id)) throw new Error('分类 slug 不是数字：' + slug);
      var p5 = TOP[String(id)] ? id : 0;
      var p6 = TOP[String(id)] ? 0 : id;
      var path = '/v1/pub/vod/list/1/3/0/0/' + p5 + '/' + p6 + '/0/update/' + page + '/' + PAGE_SIZE;
      var box = await getJson(ctx, path);
      var d = box.data || {};
      var list = Array.isArray(d.list) ? d.list : [];
      var items = cardsOf(list);
      var total = num(d.total, 0);
      var size = num(d.pageSize, PAGE_SIZE) || PAGE_SIZE;
      var pg = num(d.page, page);
      var hasMore = total > 0 ? (pg * size < total) : (list.length >= size);
      if (!items.length && page === 1) {
        throw new Error('分类页没解析到卡片（' + slug + '，' + JSON.stringify(box).length + ' 字节）');
      }
      return { items: items, page: page, hasMore: hasMore };
    },

    async search(ctx, args) {
      var kw = str(args && args.keyword);
      var page = pageOf(args);
      if (!kw) return { items: [], page: page, hasMore: false };
      var path = '/v1/pub/index/search/' + enc(kw) + '/' + page + '/0/0/0';
      var box = await getJson(ctx, path);
      var d = box.data || {};
      var groups = Array.isArray(d.data) ? d.data : [];
      var vod = null;
      for (var i = 0; i < groups.length; i++) {
        if (groups[i] && groups[i].type === 'vod') { vod = groups[i]; break; }
      }
      var list = vod && Array.isArray(vod.list) ? vod.list : [];
      var items = cardsOf(list);
      var total = vod ? num(vod.total, 0) : 0;
      var size = vod ? num(vod.pageSize, 0) : 0;
      var hasMore = (size > 0) ? (page * size < total) : (list.length >= PAGE_SIZE);
      return { items: items, page: page, hasMore: hasMore };
    },

    async detail(ctx, args) {
      var ref = str(args && args.ref);
      if (!ref) throw new Error('详情 ref 为空');
      var box = await getJson(ctx, '/v1/pub/vod/detail/' + enc(ref) + '/1');
      var d = box.data;
      if (!d || d.id == null) throw new Error('详情没拿到：' + str(box.msg));
      var urls = Array.isArray(d.urls) ? d.urls : [];
      var episodes = urls.map(function (u, i) {
        var no = num(u.index, i + 1) || (i + 1);
        return {
          no: no,
          ref: String(u.index == null ? no : u.index),
          title: str(u.title) || ('第' + pad2(no) + '集')
        };
      });
      var tags = [];
      var tk = [d.typeId1Name, d.typeIdName, d.area, d.year, d.lang];
      for (var i = 0; i < tk.length; i++) {
        var t = str(tk[i]);
        if (t && tags.indexOf(t) < 0 && tags.length < 5) tags.push(t);
      }
      return {
        id: String(d.id),
        title: str(d.name),
        cover: imageOf(d.picThumb || d.pic),
        description: str(d.content) || str(d.blurb),
        tags: tags,
        episodeCount: urls.length,
        episodes: episodes,
        lines: [{ line: 'default', name: '默认线路' }]
      };
    },

    async play(ctx, args) {
      var sp = splitRef(args && args.ref);
      var id = sp.id;
      if (!id) return { reason: 'no-source', note: '缺少影片 id' };
      var ep = num(args && args.ep, 1) || 1;
      var want = ep;
      if (sp.token) {
        var m = String(sp.token).match(/(\d+)/);
        if (m) want = num(m[1], ep) || ep;
      }
      var box = await getJson(ctx, '/v1/pub/vod/detail/' + enc(id) + '/1');
      var d = box.data || {};
      var urls = Array.isArray(d.urls) ? d.urls : [];
      var pick = null;
      for (var i = 0; i < urls.length; i++) {
        if (num(urls[i].index, 0) === want) { pick = urls[i]; break; }
      }
      if (!pick && urls[want - 1]) pick = urls[want - 1];
      if (!pick && urls.length === 1) pick = urls[0];
      var url = pick ? str(pick.url) : '';
      if (!url) {
        return {
          reason: (pick && pick.vip) ? 'vip' : 'no-source',
          note: (pick && pick.vip) ? '这一集是会员专属，匿名没有直链' : '这一集源站没有给出直链地址',
          episodeNumber: want
        };
      }
      return {
        url: url,
        alts: [{ line: 'default', name: '默认线路', url: url }],
        episodeNumber: want
      };
    }
  };
})();
