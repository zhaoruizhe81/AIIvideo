/**
 * 页面栈、返回手势、路由与网络状态：哪一屏在哪、退到哪。
 *
 * <p>2026-10-23 从 app.js 拆出。app.js 4280 行、236 个顶层声明，其中 2435 行没有
 * 一条分节线，改一处得全文搜。这里只搬**顶层函数声明** —— 它们靠提升生效，
 * 搬走不改变任何加载期行为；顶层 `const` 与立即执行的语句仍留在 app.js。
 *
 * <p>classic script，不是模块：函数仍落在同一个全局作用域里，调用点一行没动。
 * app.html 里这七块排在 app.js 之前。
 */
'use strict';

function rememberScreen() {
  if (state.route === 'detail') return;
  const previewContent = content.cloneNode(true);
  previewContent.removeAttribute('id');
  screenSnapshots[state.route] = {
    content: previewContent, scrollTop: scroll.scrollTop,
    horizontalScroll: [...content.querySelectorAll('.film-row,.filter-row,.home-tag-scroll')].map(row => row.scrollLeft)
  };
}

function captureScreen() {
  rememberScreen();
  return {
    route: state.route, tab: state.tab, pageNode: content.firstElementChild,
    scrollTop: scroll.scrollTop, searchValue: searchInput.value,
    listing: state.route === 'listing' ? state.listing : null,
    listingReturn: state.listingReturn, tagsReturn: state.tagsReturn,
    compactHeld: state.compactHeld,
    horizontalScroll: [...content.querySelectorAll('.film-row,.filter-row,.home-tag-scroll')].map(row => row.scrollLeft)
  };
}

function restoreScreen(snapshot) {
  if (!snapshot?.pageNode) { goHome(); return; }
  ++routeToken;
  listingObserver?.disconnect();
  categoryObserver?.disconnect();
  setRoute(snapshot.route);
  state.tab = snapshot.tab;
  state.compactHeld = snapshot.compactHeld;
  if (snapshot.listing) state.listing = snapshot.listing;
  state.listingReturn = snapshot.listingReturn;
  state.tagsReturn = snapshot.tagsReturn;
  // replaceChildren 是 Chrome 86；这里改成「清空 + append」。见 app-nav.js 里的同一处理。
  while (content.firstChild) content.removeChild(content.firstChild);
  content.appendChild(snapshot.pageNode);
  if (snapshot.route === 'home') refreshHomeHistory();
  if (snapshot.route === 'library') renderLibrary();
  if (snapshot.route === 'settings') renderSettings();
  searchInput.value = snapshot.searchValue || '';
  document.querySelectorAll('#dock [data-nav]').forEach(button => button.classList.toggle('active', button.dataset.nav === state.tab));
  scroll.scrollTop = snapshot.scrollTop || 0;
  content.querySelectorAll('.film-row,.filter-row,.home-tag-scroll').forEach((row, index) => {
    row.scrollLeft = snapshot.horizontalScroll?.[index] || 0;
  });
  cover.hydrate();
  if (isBannerRoute()) { restoreBannerIndex(); scheduleBannerAuto(); }
  if (snapshot.route === 'category') { compact.innerHTML = compactMarkup(); updateCompact(); observeCategoryEnd(); }
  if (snapshot.route === 'listing') observeListingEnd();
}

function updateBackEnabled() {
  // 兜底：不管哪条路径把路由切走了，都不能把全屏播放层留在屏幕上。
  // feedClose() 会先清掉 .feed 再回调这里，所以不会递归。
  if (state.route !== 'feed' && document.querySelector('.feed')) feedClose();
  // 设置页也算进来。它不是普通弹窗 —— 没配源时它是唯一的一屏，而
  // setBackEnabled(false) 会让 Java 把返回回调整个注销掉，之后按返回就是
  // 系统默认行为（退到后台），连 window.hgBack() 都不会被调用。
  const modalOpen = [unlockOverlay, authOverlay, confirmOverlay, document.getElementById('setup-overlay')]
    .some(overlay => overlay && !overlay.hidden);
  window.AndroidHost?.setBackEnabled(modalOpen || !isRootColumn());
}

function playViewEnter(route) {
  const app = document.getElementById('app');
  if (app.classList.contains('view-enter')) app.classList.remove('view-enter');
}

function setRoute(route) {
  stopBannerAuto();
  // 换页就收起历史面板。留着的话从搜索结果退回来它还悬在半空，
  // 而且底下的页面已经换了 —— 一眼看去像是搜索框卡住了。
  hideSearchHistory();
  const changed = state.route !== route;
  if (state.route === 'detail' && route !== 'detail') { detailCard?.dispose(); detailCard = null; }
  const libraryArea = ['library', 'settings', 'account-management'].includes(route);
  if (state.route === 'library' && route !== 'library') searchInput.value = '';
  state.route = route;
  const app = document.getElementById('app');
  app.dataset.route = route;
  app.classList.toggle('route-detail', route === 'detail');
  // 刷视频态：从列表点进原生竖滑时 state.route 走 'feed'，底栏在 WebView 侧
  // 藏起来（原生全屏盖上来之前的那一瞬也别露底栏）。离开 'feed' 一律摘掉，
  // 这样「关开关即时回退」「从详情返回」都不会把底栏落在隐藏态。
  app.classList.toggle('route-feed', route === 'feed');
  window.AndroidHost?.setDetailMode?.(route === 'detail');
  headerSettings.hidden = route !== 'library';
  searchInput.placeholder = libraryArea ? '搜索观看历史和收藏' : '搜索剧名或 #标签';
  searchInput.setAttribute('aria-label', libraryArea ? '搜索观看历史和收藏' : '搜索剧名或标签');
  if (route === 'library') searchInput.value = state.libraryQuery;
  updateBackEnabled();
  if (changed) playViewEnter(route);
}

function backDestination() {
  if (isRootColumn()) return null;
  if (state.route === 'campaign') return state.campaignReturn?.route || 'home';
  if (state.route === 'detail') return state.previous?.route || 'home';
  if (state.route === 'account-management') return state.accountReturn?.route || 'settings';
  if (state.route === 'proxy-settings') return state.proxyReturn?.route || 'settings';
  if (state.route === 'source-settings') return state.sourceReturn?.route || 'settings';
  if (state.route === 'market') return state.marketReturn?.route || 'settings';
  if (state.route === 'cover-diagnostics') return state.coverDiagReturn?.route || 'settings';
  if (state.route === 'settings') return state.settingsReturn?.route || 'library';
  if (state.route === 'tags') return state.tagsReturn?.route || 'home';
  if (state.route === 'listing' && state.listing?.kind === 'tag-list') return state.listingReturn?.route || 'home';
  return state.route === 'home' ? null : 'home';
}

function clearPredictivePreview() {
  clearTimeout(predictiveTimer);
  if (predictiveFrame) cancelAnimationFrame(predictiveFrame);
  predictiveFrame = null;
  if (predictiveSourceNode && predictivePreview?.contains(predictiveSourceNode)) predictiveSourceNode.remove();
  predictiveSourceNode = null;
  predictivePreview?.remove();
  predictivePreview = null;
  document.getElementById('app').classList.remove('predictive-active');
  scroll.style.removeProperty('transition');
  scroll.style.removeProperty('transform');
}

// ── 刷视频态（抖音式竖滑）的 WebView 配合层 ──────────────────────────────
//
// 竖滑本体是原生 VlcPlayerActivity 的全屏 Activity；这一层只做 WebView 侧的薄事：
// 「进竖滑时把底栏/页面顶栏藏起来 + 画面铺满 + 顶上留一条手势条」和「离开时还原」。
// T2 在拉起原生前后、T1 在原生退出回调里都会调这里，保证不管哪条路径进来/出去，
// 姿态都一致。用 class 而不是内联样式，还原只删一个类的事。
function applyImmersiveShell(on) {
  const app = document.getElementById('app');
  if (!app) return;
  app.classList.toggle('feed-immersive', Boolean(on));
}

// 刷视频态的「进详情」记录。T2 的 hgFeedOpenDetail(id) 进详情前调一次；返回时
// （hgResume / returnFromDetail 兜底）凭它判断「是不是从竖滑进的详情、要不要续刷」。
//
// 存哪：localStorage 的一份**落盘快照**，不是只在 state 上——进详情后 goDetail 会
// 深度克隆整页、随后 Activity 还可能在进详情的路上被系统回收；放内存里的 state
// 会随重建页面/进程一起丢，返回时就续不回去了。序列化只留重建 payload 要用的
// 那几样（id/cover/title/source + listToken + 续页游标），feedContext.items 太大
// 不整个落盘——续刷时若原生自保活（推荐）根本用不上它；只有在原生没保活、需要
// 前端重建时才用 items，那种情况退回「重新取第一页」即可（见 T2 的兜底）。
const FEED_RETURN_KEY = 'hgfeed:returnContext';
const FEED_RETURN_TTL = 30 * 60 * 1000;   // 半小时内回来才算「续刷」，过期当普通返回

function rememberFeedReturnContext(id) {
  try {
    const ctx = state.feedContext;
    const items = ctx && Array.isArray(ctx.items) ? ctx.items : [];
    if (!items.length) { state.feedReturnContext = null; try { localStorage.removeItem(FEED_RETURN_KEY); } catch (e) {} return; }
    const idx = Math.max(0, Number(state.feedIndex) || 0);
    // 只挑重建 payload 必需的最小字段，避免把整份列表塞进 localStorage。
    const slim = items.map(it => ({ id: String(it.id || ''), cover: String(it.cover || ''), title: String(it.title || ''), source: String(it.source || '') }));
    const saved = {
      at: Date.now(),
      id: String(id == null ? '' : id),
      listToken: String(ctx.listToken || ''),
      kind: String(ctx.kind || ''),
      hasMore: Boolean(ctx.hasMore),
      nextPage: ctx.nextPage == null ? null : Number(ctx.nextPage),
      query: ctx.query || '', slug: ctx.slug || '', label: ctx.label || '', pageKind: ctx.pageKind || '', categoryId: ctx.id || '',
      feedIndex: idx,
      items: slim
    };
    state.feedReturnContext = saved;
    try { localStorage.setItem(FEED_RETURN_KEY, JSON.stringify(saved)); } catch (e) {}
  } catch (error) { state.feedReturnContext = null; }
}

// 返回时取回「从竖滑进的详情」那份记录；过期或没有就返回 null（当普通返回处理）。
function takeFeedReturnContext() {
  let saved = state.feedReturnContext;
  if (!saved) {
    try { saved = JSON.parse(localStorage.getItem(FEED_RETURN_KEY) || 'null'); } catch (e) { saved = null; }
  }
  state.feedReturnContext = null;
  try { localStorage.removeItem(FEED_RETURN_KEY); } catch (e) {}
  if (!saved || !Array.isArray(saved.items) || !saved.items.length) return null;
  if (Date.now() - Number(saved.at || 0) > FEED_RETURN_TTL) return null;
  return saved;
}

function clearFeedReturnContext() {
  state.feedReturnContext = null;
  try { localStorage.removeItem(FEED_RETURN_KEY); } catch (e) {}
}

function netCoolingDown() {
  return Date.now() - (state.netFailAt || 0) < NET_COOLDOWN_MS;
}

function noteNetFailure() {
  state.netFailAt = Date.now();
}

function noteNetSuccess() {
  state.netFailAt = 0;
}
