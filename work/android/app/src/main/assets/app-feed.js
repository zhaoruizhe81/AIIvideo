/*
 * AIIvideo —— 刷视频模式（抖音式竖滑）前端映射层（模块 8）
 *
 * 这一层只做三件事，全部是**纯函数**（不碰 DOM、不碰网络、不在加载期读 state）：
 *   1. 把卡片 item 映射成原生竖滑要的 feed 条目 {id, cover, title, source}；
 *   2. 过滤掉不该进竖滑的卡片（非数字 id、无封面、疑似图文/合集）；
 *   3. 把某个浏览入口（首页/分类/列表/搜索）当前的 items 组成「feed 上下文」，
 *      并生成 listToken，供后续续页 append 归属。
 *
 * 为什么单独一层：映射规则改起来最频繁（过滤条件、字段改名），而它又是能用
 * node 直接离线回归的部分（scripts/feed-map-check.js 就是干这个的）。放在这里
 * 而不是塞进 app.js（已 3400 行），既好测也不会再撑大主文件。
 *
 * 契约见 docs/刷视频模式-roadmap.md「接口契约」：feedList 条目只有四个字段
 * {id, cover, title, source}；hasMore 只有分类/搜索这类有分页的才可能为 true，
 * 首页本期不做无限流（hasMore=false）。
 *
 * 挂载：classic script，函数落在全局作用域（和 model.js/adapters.js 一样）。
 * 末尾挂 window.HGFeed，node 的 vm 沙箱塞个 window 壳就能拿到同一份逻辑。
 */
(function () {
  'use strict';

  // 疑似「图文 / 合集 / 专题」的关键词。命中就跳过 —— 这类卡片没有单集可播的
  // 视频地址，塞进竖滑只会一闪而过。判据是**尽力而为**：接口没有稳定的 type 字段，
  // 真到播放层还会由原生「取不到地址就跳过」兜底（契约 H-3），这里只是尽量少放垃圾。
  var AGGREGATE_RE = /(合集|专题|系列集合|播放列表|片单|榜单)/;

  function str(value) {
    return value === undefined || value === null ? '' : String(value);
  }

  /**
   * 一个卡片 item → feed 条目；不可进竖滑的返回 null。
   *
   * @param item  列表卡片（renderHome/renderCategory/renderList/search 那一套的形状）
   * @param source 源标识（埋点/播放用）。不传就看 window.HG_SOURCE_KEY，node 下为空串。
   */
  function toFeedEntry(item, source) {
    if (!item || typeof item !== 'object') return null;
    var id = str(item.id).trim();
    // 原生 __hgNativeEpisode(id, ep) 只认纯数字 id（见 feed-player.js 的 note），
    // 非数字 id 到了原生那边解析不出地址，直接不进流。
    if (!/^\d+$/.test(id)) return null;
    var cover = str(item.cover).trim();
    if (!cover) return null;                    // 无封面：竖滑页没视觉，跳过（图文/占位条目多半无封面）
    var episodeCount = Number(item.episodeCount);
    var hasEpisodeHint = episodeCount > 0 || str(item.episodeLabel).trim().length > 0;
    if (!hasEpisodeHint) return null;           // 既没集数也没「共 N 集」——疑似图文/无剧集
    var blob = str(item.category) + ' ' + (Array.isArray(item.tags) ? item.tags.join(' ') : '');
    if (AGGREGATE_RE.test(blob)) return null;   // 合集/专题等
    var effectiveSource = source !== undefined
      ? str(source)
      : str(typeof window !== 'undefined' && window.HG_SOURCE_KEY);
    return {
      id: id,
      cover: cover,
      title: str(item.title),
      source: effectiveSource
    };
  }

  /**
   * items[] → feedList[]：映射 + 保序去重（同一 id 只占一位，保持首次出现的位置）。
   * 返回的是新数组，不改入参。
   */
  function toFeedList(items, source) {
    var seen = Object.create(null);
    var out = [];
    var list = Array.isArray(items) ? items : [];
    for (var i = 0; i < list.length; i += 1) {
      var entry = toFeedEntry(list[i], source);
      if (!entry) continue;
      if (seen[entry.id]) continue;
      seen[entry.id] = true;
      out.push(entry);
    }
    return out;
  }

  /** 生成一次竖滑会话的 listToken：来源 + 时间 + 序号，保证跨会话/同秒内都不同。 */
  var tokenSeq = 0;
  function makeListToken(kind) {
    tokenSeq += 1;
    return str(kind) + ':' + Date.now().toString(36) + ':' + tokenSeq.toString(36);
  }

  /**
   * 从某个浏览入口当前的 state 组成「feed 上下文」。
   *
   * 返回：
   *   kind        来源类别（home / category / listing / search）
   *   items       已映射的 feed 条目（toFeedList 的产物）
   *   listToken   本次会话身份（续页 append 靠它归属）
   *   hasMore     是否还能续页
   *   nextPage    下一页页码（原始页码，供续页请求用；无分页时为 null）
   *   query/slug/label/id  续页请求要带的参数（按来源）
   *
   * 只读 state、不写它 —— 续页追加由 appendFeedItems 写进独立的 state.feedContext，
   * 绝不污染 state.category / search / listing（那会改变普通浏览的 DOM 与 signature）。
   *
   * @param kind  'home' | 'category' | 'listing' | 'search'
   * @param st    全局 state
   */
  function buildFeedContext(kind, st) {
    var state = st || {};
    var items = [];
    var nextPage = null;
    var hasMore = false;
    var extra = {};
    var k = str(kind);

    if (k === 'home') {
      // 首页推荐流：合并 featured/recommend/newest（复用 app.js homeItems 的语义），
      // **本期不做无限流** —— hasMore 恒 false，滑到底停。
      var home = state.home || {};
      items = []
        .concat(Array.isArray(home.featured) ? home.featured : [])
        .concat(Array.isArray(home.homepageRecommend) ? home.homepageRecommend : [])
        .concat(Array.isArray(home.homepageNewest) ? home.homepageNewest : [])
        .concat(Array.isArray(home.recommend && home.recommend.items) ? home.recommend.items : [])
        .concat(Array.isArray(home.newest && home.newest.items) ? home.newest.items : []);
      hasMore = false;
      nextPage = null;
    } else if (k === 'category') {
      var category = state.category || {};
      items = Array.isArray(category.items) ? category.items : [];
      nextPage = category.nextPage != null ? Number(category.nextPage) : null;
      hasMore = nextPage != null && nextPage > 0;
      extra.id = typeof categoryId === 'function' ? categoryId() : state.tab;
    } else if (k === 'listing') {
      var listing = state.listing || {};
      items = Array.isArray(listing.items) ? listing.items : [];
      nextPage = listing.nextPage != null ? Number(listing.nextPage) : null;
      hasMore = nextPage != null && nextPage > 0;
      extra.pageKind = listing.kind || '';
      extra.slug = listing.slug || '';
      extra.label = listing.label || '';
    } else if (k === 'search') {
      var search = state.search || {};
      items = Array.isArray(search.items) ? search.items : [];
      nextPage = search.nextPage != null ? Number(search.nextPage) : null;
      hasMore = nextPage != null && nextPage > 0;
      extra.query = state.query || '';
    }

    var ctx = {
      kind: k,
      items: toFeedList(items),
      listToken: makeListToken(k),
      hasMore: hasMore,
      nextPage: nextPage
    };
    for (var key in extra) {
      if (Object.prototype.hasOwnProperty.call(extra, key)) ctx[key] = extra[key];
    }
    return ctx;
  }

  /**
   * 续页结果追加进 feed 上下文：映射 → 去重 → append 到 ctx.items 末尾 → 更新 hasMore/nextPage。
   * 返回**本次新增的条目**（原生靠它 append adapter）。就地改 ctx（它是我们自己的副本）。
   *
   * @param ctx       buildFeedContext 的产物（会被就地更新）
   * @param rawItems  续页接口返回的原始 items[]
   * @param nextPage  续页后接口给的下一个 nextPage（没有就 null）
   */
  function appendFeedItems(ctx, rawItems, nextPage) {
    if (!ctx) return [];
    var existing = Object.create(null);
    for (var i = 0; i < ctx.items.length; i += 1) existing[ctx.items[i].id] = true;
    var fresh = toFeedList(rawItems);
    var added = [];
    for (var j = 0; j < fresh.length; j += 1) {
      if (existing[fresh[j].id]) continue;
      existing[fresh[j].id] = true;
      ctx.items.push(fresh[j]);
      added.push(fresh[j]);
    }
    ctx.nextPage = nextPage != null ? Number(nextPage) : null;
    ctx.hasMore = ctx.nextPage != null && ctx.nextPage > 0;
    return added;
  }

  var HGFeed = {
    toFeedEntry: toFeedEntry,
    toFeedList: toFeedList,
    buildFeedContext: buildFeedContext,
    appendFeedItems: appendFeedItems,
    makeListToken: makeListToken
  };

  // 全文件唯一一行碰宿主的代码。node 的 vm 沙箱塞一个 window 壳就能拿到它。
  if (typeof window !== 'undefined') window.HGFeed = HGFeed;
})();
