/**
 * 列表、详情、骨架屏与错误块：一屏内容怎么画出来。
 *
 * <p>2026-10-23 从 app.js 拆出。app.js 4280 行、236 个顶层声明，其中 2435 行没有
 * 一条分节线，改一处得全文搜。这里只搬**顶层函数声明** —— 它们靠提升生效，
 * 搬走不改变任何加载期行为；顶层 `const` 与立即执行的语句仍留在 app.js。
 *
 * <p>classic script，不是模块：函数仍落在同一个全局作用域里，调用点一行没动。
 * app.html 里这七块排在 app.js 之前。
 */
'use strict';

function listingFooter() {
  const listing = state.listing;
  if (listing.error) return '<button type="button" data-retry-listing="1">加载失败，点击重试</button>';
  if (listing.loading) return '<span role="status">正在加载更多…</span>';
  if (listing.refreshing) return '<span role="status">正在更新列表…</span>';
  if (listing.nextPage) return listing.kind === 'tag-list' ? '<span class="listing-sentinel" aria-hidden="true"></span>' : '<span class="listing-sentinel">继续下滑加载更多</span>';
  return '<span>已经到底了</span>';
}

function observeListingEnd() {
  listingObserver?.disconnect();
  if (state.route !== 'listing' || !state.listing?.nextPage || state.listing.loading ||
      state.listing.refreshing || state.listing.error) return;
  const sentinel = content.querySelector('.listing-sentinel');
  if (!sentinel) return;
  if (!listingObserver) {
    listingObserver = new IntersectionObserver(entries => {
      if (entries.some(entry => entry.isIntersecting)) loadMoreListing();
    }, { root: scroll, rootMargin: '0px 0px 320px 0px' });
  }
  listingObserver.observe(sentinel);
}

function updateListingFooter() {
  const footer = content.querySelector('.listing-footer');
  if (!footer) return;
  footer.innerHTML = listingFooter();
  observeListingEnd();
}

function renderListing() {
  const listing = state.listing;
  if (!listing?.items.length && listing?.refreshing) { renderSkeleton(); return; }
  const tagName = String(listing.label || listing.slug).replace(/^#+/, '');
  // 标签页标题就是标签本身。原来在公开态会拼成「短剧 · XXX」，那是内置源
  // 按短剧/漫剧分标签的写法，别的源套上就是错的。
  const tagTitle = `#${tagName}`;
  const title = listing.kind === 'tag-list' ? tagTitle : listing.kind === 'recommend' ? '精选推荐' : '最新上架';
  const backLabel = listing.kind === 'tag-list' && state.listingReturn?.route === 'tags' ? '返回全部类型' : '返回首页';
  content.innerHTML = `<div class="listing-page"><button class="listing-back" type="button" data-back="1">${icon('back')} ${backLabel}</button>
    <h1>${esc(title)}</h1><p>已显示 ${listing.items.length} 部作品</p>
    ${listing.items.length ? `<div class="search-grid listing-grid">${listing.items.map(itemCard).join('')}</div>` : '<div class="empty-state">这个标签下暂无作品</div>'}
    <div class="listing-footer">${listingFooter()}</div></div>`;
  cover.hydrate();
  observeListingEnd();
}

function renderDetail() {
  const data = state.detail;
  if (!data) { renderSkeleton(); return; }
  const loading = state.detailLoading;
  window.AndroidHost?.setDetailMode?.(true);
  const episodes = asArray(data.episodes);
  content.innerHTML = `<div class="detail-page"><button class="detail-back" type="button" data-back="1">${icon('back')} 返回</button>
    <div class="detail-art"><img data-cover="${esc(data.cover)}" alt=""></div><div class="detail-copy"><h1>${esc(data.title)}</h1><p>${loading ? '正在获取内容…' : `${esc(data.score)} ${esc(data.episodeLabel)}`}</p><p class="detail-description">${esc(data.description || '')}</p>
    <div class="detail-buttons"><button class="detail-play" type="button" data-play="1" ${loading ? 'disabled' : ''}>${loading ? '正在加载…' : '立即播放'}</button><button class="bookmark-button" type="button" data-bookmark="1" aria-label="${state.bookmarked ? '取消收藏' : '收藏'}" ${loading ? 'disabled' : ''}>${state.bookmarked ? '★' : '☆'}</button></div>
    ${loading ? '<div class="detail-loading" role="status">正在获取选集…</div>' : episodes.length ? `<h2>选集</h2><div class="episode-grid">${episodes.map((item, index) => {
      // 选集号必须数值化 + esc：REST 路径 adapters.adaptDetail 的 number 是原值，
      // 源返回含引号/非数字时会破 data-episode 属性（innerHTML 注入面），正常也会
      // 显示 undefined。data-episode 用严格整数（点击侧 Number() 解析依赖它），
      // 文本走 esc() 兜住任何残留的异常值。
      const numberValue = Number(item.number);
      const episodeNumber = Number.isFinite(numberValue) && numberValue > 0 ? Math.floor(numberValue) : 0;
      return `<button type="button" data-episode="${episodeNumber}" class="${index === 0 ? 'active' : ''}">${esc(episodeNumber > 0 ? episodeNumber : (item.number ?? ''))}</button>`;
    }).join('')}</div>` : ''}</div></div>`;
  cover.hydrate();
}

function skeletonCards(count = 6) {
  let out = '';
  for (let i = 0; i < count; i += 1) {
    out += '<div class="skeleton-card" aria-hidden="true"><div class="skeleton skeleton-poster"></div><div class="skeleton skeleton-card-title"></div></div>';
  }
  return out;
}

function renderSkeleton() {
  content.innerHTML = '<div class="skeleton-home" role="status" aria-label="正在加载内容"><div class="skeleton skeleton-hero"></div><div class="skeleton skeleton-title"></div><div class="skeleton-row"><div class="skeleton skeleton-poster"></div><div class="skeleton skeleton-poster"></div><div class="skeleton skeleton-poster"></div></div><div class="skeleton skeleton-line"></div></div>';
}

function performRetry(retry) {
  if (retry === 'home') goHome();
  else if (retry === 'category') goCategory(state.tab);
  else if (retry === 'library') goLibrary();
  else if (retry === 'search') goSearch(state.query);
  else if (retry === 'listing') openListing(state.listing.kind);
  else if (retry === 'tags') goTags();
  else if (retry === 'tag-list') goTag(state.listing.slug, state.listing.label);
  else if (retry === 'detail') goDetail(state.sourceItem.id);
}

function friendlyError(error) {
  const raw = (error?.message || '').trim();
  const m = raw.toLowerCase();
  if (/404|not found|不存在|查不到/.test(m)) {
    return { icon: 'gone', title: '内容不见了', detail: '这条内容可能已下架，或当前源没有收录它。', raw };
  }
  if (/超时|timeout|timed out/.test(m)) {
    return { icon: 'slow', title: '源站响应太慢', detail: '等的时间有点长，可以稍后再试，或者到设置里换一个源。', raw };
  }
  if (/为空|缺少|no data|invalid|格式|parse|json/.test(m)) {
    return { icon: 'gone', title: '源站返回了看不懂的数据', detail: '多半是源站接口变了或这条内容是空的，可以重试或换个源。', raw };
  }
  return { icon: 'alert', title: '加载失败', detail: '出了点小问题，重试一次；总不行就到设置里换个源。', raw };
}

function renderError(error, retry) {
  if (state.route === 'detail') window.AndroidHost?.setDetailMode?.(false);
  // 详情页出错时**必须**留条退路。正常详情页顶部有个「返回」按钮，可出错时整屏
  // 被换成只有「重试」的错误页 —— 那个按钮跟着没了，用户就卡在一屏什么都做不了
  // 的界面上。最典型的触发：换了内容源之后点观看历史里上一个源的条目，那个 id
  // 在新源里查不到，详情必失败。
  const escape = retry === 'detail' ? '<button type="button" data-back="1">返回</button>' : '';
  clearTimeout(errorAutoRetry.timer);
  const isNetwork = NETWORK_ERROR_RE.test(error?.message || '');
  const routeAtRender = state.route;
  if (isNetwork) {
    content.innerHTML = `<div class="error-state error-state--network" role="alert">
      <div class="error-main">
        ${ERROR_ICONS.network}
        <h1>网络走丢了</h1>
      </div>
      <div class="error-footer"><p>几秒后会自动重试，也可以现在手动来。</p>${escape}<button type="button" data-retry="${esc(retry)}">重新尝试</button></div>
    </div>`;
    if (['home', 'category', 'listing', 'tags', 'tag-list', 'library'].includes(retry)) {
      errorAutoRetry.timer = setTimeout(() => {
        // 用户已经离开这一页、或错误页已被别的内容换掉，就不再补刀。
        if (state.route !== routeAtRender || !content.querySelector(`[data-retry="${retry}"]`)) return;
        performRetry(retry);
      }, 5000);
    }
    return;
  }
  const info = friendlyError(error);
  const hint = retry === 'detail' ? '<p>这条内容可能已下架，或当前源没有收录。</p>' : '';
  content.innerHTML = `<div class="error-state error-state--network" role="alert">
    <div class="error-main">
      ${ERROR_ICONS[info.icon]}
      <h1>${esc(info.title)}</h1>
    </div>
    <div class="error-footer">
      ${hint}<p>${esc(info.detail)}</p>
      ${info.raw ? `<p class="error-raw">${esc(info.raw)}</p>` : ''}
      ${escape}<button type="button" data-retry="${esc(retry)}">重试</button>
    </div>
  </div>`;
}
