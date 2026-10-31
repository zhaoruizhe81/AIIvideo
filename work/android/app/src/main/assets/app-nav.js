/**
 * 导航动作：首页 / 类型 / 搜索 / 详情 / 设置，以及分类胶囊与预热。
 *
 * <p>2026-10-23 从 app.js 拆出。app.js 4280 行、236 个顶层声明，其中 2435 行没有
 * 一条分节线，改一处得全文搜。这里只搬**顶层函数声明** —— 它们靠提升生效，
 * 搬走不改变任何加载期行为；顶层 `const` 与立即执行的语句仍留在 app.js。
 *
 * <p>classic script，不是模块：函数仍落在同一个全局作用域里，调用点一行没动。
 * app.html 里这七块排在 app.js 之前。
 */
'use strict';

function scheduleCategoryWarmup() {
  clearTimeout(warmupTimer);
  warmupTimer = setTimeout(runCategoryWarmup, 2500);
}

async function runCategoryWarmup() {
  if (warming || state.route !== 'category') return;
  warming = true;
  const tab = state.tab;
  try {
    const ids = (siteUi().categoryIds || {})[tab] || [];
    for (let i = 0; i < ids.length; i++) {
      if (state.route !== 'category' || state.tab !== tab) return;
      const cacheKey = `category:${ids[i]}:1`;
      const cached = readCache(cacheKey);
      if (cached?.data && Date.now() - (cached.at || 0) < 120000) continue;
      try {
        const data = await api('category', { id: ids[i], page: 1 });
        if (data) writeCache(cacheKey, data);
      } catch (error) {
        // 预取失败不打扰用户：他真点进去时那条路自己会报错。
      }
      await new Promise(resolve => setTimeout(resolve, 400));
    }
  } finally {
    warming = false;
  }
}

function applySubSelection(sub) {
  const page = content.querySelector('.category-page');
  const hosts = [page, compact].filter(Boolean);
  hosts.forEach(host => {
    host.querySelectorAll('[data-sub]').forEach(button => {
      const on = Number(button.dataset.sub) === sub;
      button.classList.toggle('selected', on);
      button.setAttribute('aria-selected', String(on));
    });
  });
  // 区块标题（「搞笑作品」→「运动作品」）也是同一件事：它描述的是「你要看什么」，
  // 不是「已经拿到了什么」。
  const heading = page?.querySelector('.category-section .section-heading h2');
  if (heading) {
    const tabs = categoryTabs();
    if (tabs[sub]) heading.textContent = tabs[sub] + '作品';
  }
  // 旧卡片还挂在那儿，明确标成「这条不是你要的」，免得标题和内容对不上。
  page?.querySelector('.category-grid')?.classList.add('is-stale');
}

function clearStaleCards() {
  content.querySelector('.category-grid')?.classList.remove('is-stale');
}

function setNav(tab, keepState = false) {
  listingObserver?.disconnect();
  categoryObserver?.disconnect();
  document.querySelectorAll('#dock [data-nav]').forEach(button => button.classList.toggle('active', button.dataset.nav === tab));
  if (keepState) return;
  compact.classList.remove('visible');
  compact.inert = true;
  state.compactHeld = false;
  scroll.scrollTop = 0;
}

function setCategoryBusy(on) {
  const app = document.getElementById('app');
  if (!app) return;
  let bar = document.getElementById('category-busy');
  if (on) {
    if (!bar) {
      bar = document.createElement('i');
      bar.id = 'category-busy';
      bar.className = 'busy-bar';
      app.appendChild(bar);
    }
    bar.classList.add('visible');
    return;
  }
  if (bar) bar.classList.remove('visible');
}

function bindDockPressFeedback() {
  const dock = document.getElementById('dock');
  if (!dock) return;
  dock.addEventListener('pointerdown', event => {
    const button = event.target.closest('[data-nav]');
    if (!button) return;
    dock.querySelectorAll('[data-nav]').forEach(other =>
      other.classList.toggle('active', other.dataset.nav === button.dataset.nav));
  }, { passive: true });
}

async function goHome() {
  const token = ++routeToken;
  stopBannerAuto();
  setRoute('home'); state.tab = 'home'; setNav('home');
  // 三级兜底，顺序不能反：内存 → localStorage → 骨架屏。
  // localStorage 是同步读的，冷启动第一帧就有内容，不用等网络也不用等桥。
  let cachedHome = null;
  if (!state.home) {
    cachedHome = readCache('home');
    if (cachedHome?.data) {
      state.home = cachedHome.data;
      state.homeAt = cachedHome.at || 0;
    }
  }
  if (state.home) renderHome(); else renderSkeleton();
  // 首屏 DOM 建完再钉一次高亮：上面那次 setNav 的样式变更可能和这批 DOM
  // 落在同一帧里被合并，表现就是「页面换了、按钮还停在上一格」。
  // 这里只改高亮不动滚动（keepState），重复调用没有副作用。
  setNav('home', true);
  // 首页和分类用同一套新鲜度判断：两分钟内不再打网络。
  //
  // 原来首页每次进都发一次请求（1.4~1.6 秒），期间屏幕上一直是上一次的内容 ——
  // 用户点「首页」看到的是「没反应」，过一会儿内容自己跳一下。分类早就有这个
  // 窗口了，首页漏了。
  const HOME_FRESH_MS = 120000;
  if (state.home && state.homeAt && Date.now() - state.homeAt < HOME_FRESH_MS) {
    // 两分钟内不再打网络，但**已经拿到手的真值还是要套一遍**。
    // writeCache 存的是网格页原样（未校正），所以「跳过刷新」如果连
    // reconcileCards 也一起跳过，第二次进首页看到的就是旧标题 —— 而校正结果
    // 明明已经在 localStorage 里。这一句只花内存查找；真的缺 id 才会发请求，
    // 那也是后台的，不挡首屏。
    reconcileCards(homeItems(state.home), () => renderHome());
    return;
  }
  const refreshing = api('home');
  // 先挂一个空 catch。这个 promise 可能在下面 await 之前就 reject ——
  // spider 那条世代机制会把被取代的调用判死，而 V8 在微任务清空时发现
  // 没有 handler 就会打一条 Uncaught (in promise)，把控制台刷红。
  // 错误本身由下面的 await 处理，这里只挡噪音。
  refreshing.catch(() => {});
  const library = await api('library').catch(() => null);
  if (token !== routeToken) return;
  // 拉取失败（null）不能清掉手上已有的片单 —— 那会把「我的」和继续观看
  // 从有数据退化成空态/错误页。只认成功的结果（refreshHomeHistory 同一口径）。
  if (library) state.library = library;
  let fresh;
  try {
    fresh = await refreshing;
  } catch (error) {
    // 接口挂了但手上有缓存：留着旧内容，只提示一下。把已经画好的首页换成
    // 错误页是最糟的选择——用户看到的是一片空白，而内容其实就在本地。
    if (token !== routeToken) return;
    noteNetFailure();
    if (!state.home) renderError(error, 'home');
    else toast('源暂时连不上，稍后自动重试', true);
    // 这条线路是间歇性的（实测同一个域名三次里有一次能通），所以失败后
    // 自动退避重试，而不是把「重新尝试」丢给用户点 —— 也不分有没有缓存。
    scheduleHomeRetry(token);
    return;
  }
  if (token !== routeToken) return;
  try {
    const changed = state.home && homeSignature(state.home) !== homeSignature(fresh);
    const firstLoad = !state.home;
    state.home = fresh;
    state.homeAt = Date.now();
    noteNetSuccess();
    writeCache('home', fresh);
    if (firstLoad || changed) renderHome();
    else refreshMissingImages([...asArray(fresh.featured), ...asArray(fresh.recommend?.items), ...asArray(fresh.newest?.items)]);
    // 先把网格页的图画出来，再拿详情页把标题/封面盖成真值（见 reconcileCards）。
    reconcileCards(homeItems(fresh), () => {
      if (token !== routeToken || state.route !== 'home') return;
      writeCache('home', state.home);
      renderHome();
    });
  } catch (error) {
    // ⚠️ 渲染和网络必须分开 catch。以前这两段在同一个 try 里，后面几句抛出来，
    // 用户看到的也是「内容更新失败，正在显示缓存」—— 实测日志是
    //   [api] /api/home ok 2076ms
    //   [toast] 内容更新失败，正在显示缓存      ← 成功之后 3ms
    // 真实原因（refreshMissingImages 里变量遮蔽）跟网络毫无关系，却害人顺着
    // 「接口不稳」查了半天。拿到了数据就说明接口是好的 —— 只打日志，不弹提示。
    console.log('[home] 渲染失败（接口是好的）：' + ((error && error.stack) || error));
    state.home = fresh;   // 数据是好的，先认下来，别因为一次渲染失败丢掉它
  }
}

async function goTags() {
  const token = ++routeToken;
  if (state.route !== 'tags') state.tagsReturn = captureScreen();
  setRoute('tags'); state.tab = 'home'; setNav('home');
  const refreshing = api('tags');
  refreshing.catch(() => {});
  const cached = readCache('tags');
  if (token !== routeToken) return;
  state.tags = cached?.data || state.tags;
  if (state.tags) renderTags(); else renderSkeleton();
  try {
    const fresh = await refreshing;
    if (token !== routeToken) return;
    const changed = JSON.stringify(state.tags?.items) !== JSON.stringify(fresh.items);
    const previousScroll = scroll.scrollTop;
    state.tags = fresh;
    writeCache('tags', fresh);
    if (changed) { renderTags(); scroll.scrollTop = previousScroll; }
  } catch (error) {
    if (token !== routeToken) return;
    if (!state.tags) renderError(error, 'tags');
    else toast('标签更新失败，正在显示缓存', true);
  }
}

async function goTag(slug, label) {
  const token = ++routeToken;
  if (state.route !== 'listing' || state.listing?.kind !== 'tag-list' || state.listing?.slug !== slug) state.listingReturn = captureScreen();
  setRoute('listing'); state.tab = 'home'; setNav('home');
  // 「短剧·/漫剧·」这个前缀只在源真的按短剧/漫剧分标签时才有意义。别的源
  // （比如 老源，它的标签就是分类栏）套上会变成「#短剧·高清版」，纯噪音。
  const dramaTagged = (siteUi().tagGroups || []).some(([id]) => id === 'short' || id === 'comic');
  const prefix = dramaTagged ? (slug.startsWith('comic:') ? '漫剧·' : '短剧·') : '';
  searchInput.value = `#${prefix}${String(label).replace(/^#+/, '')}`;
  const refreshing = api('tag-list', { slug, label, page: 1 });
  refreshing.catch(() => {});
  const cached = readCache(`tag:${slug}:1`);
  if (token !== routeToken) return;
  const firstLoad = !cached?.data;
  state.listing = cached?.data ? { ...cached.data, kind: 'tag-list', slug, label, refreshing: true, loading: false, error: false } :
    { items: [], nextPage: null, kind: 'tag-list', slug, label, refreshing: true, loading: false, error: false };
  renderListing();
  try {
    const fresh = await refreshing;
    if (token !== routeToken) return;
    const changed = listSignature(state.listing) !== listSignature(fresh) || state.listing.nextPage !== fresh.nextPage;
    const previousScroll = scroll.scrollTop;
    state.listing = { ...fresh, kind: 'tag-list', slug, label, refreshing: false, loading: false, error: false };
    writeCache(`tag:${slug}:1`, fresh);
    if (firstLoad || changed) { renderListing(); scroll.scrollTop = previousScroll; }
    else updateListingFooter();
    reconcileCards(state.listing.items, () => {
      if (token !== routeToken || state.route !== 'listing') return;
      const top = scroll.scrollTop;
      renderListing();
      scroll.scrollTop = top;
    });
  } catch (error) {
    if (token !== routeToken) return;
    if (!state.listing.items.length) renderError(error, 'tag-list');
    else { state.listing.refreshing = false; updateListingFooter(); toast('列表更新失败，已显示缓存内容', true); }
  }
}

async function goTagQuery(query) {
  const name = query.replace(/^[#＃]\s*/, '').trim();
  if (!name) { goTags(); return; }
  let tags = asArray(state.tags?.items);
  if (!tags.length) {
    const cached = readCache('tags');
    tags = asArray(cached?.data?.items);
  }
  if (!tags.length) {
    try { const fresh = await api('tags'); state.tags = fresh; writeCache('tags', fresh); tags = asArray(fresh.items); }
    catch { toast('标签暂时无法加载，请重试', true); return; }
  }
  const normalize = value => String(value || '').replace(/^#+/, '').trim().toLocaleLowerCase();
  const matches = item => normalize(item.name) === normalize(name)
    || normalize(`${item.group === 'comic' ? '漫剧' : '短剧'}·${item.name}`) === normalize(name);
  let tag = tags.find(matches);
  if (!tag) {
    try { const fresh = await api('tags'); state.tags = fresh; tag = asArray(fresh.items).find(matches); }
    catch { /* 已有清单仍可用于搜索 */ }
  }
  if (tag) goTag(tag.slug, tag.name);
  else toast('没有找到这个标签', true);
}

async function openListing(kind) {
  if (kind !== 'recommend' && kind !== 'newest') return;
  const token = ++routeToken;
  rememberScreen();
  listingObserver?.disconnect();
  setRoute('listing'); state.tab = 'home';
  setNav('home');
  const initial = state.home?.[kind];
  state.listing = {
    kind, items: [...asArray(initial?.items)], nextPage: initial?.nextPage || null,
    refreshing: true, loading: false, error: false
  };
  renderListing();
  try {
    const fresh = await api(kind, { page: 1 });
    if (token !== routeToken) return;
    state.listing.items = asArray(fresh.items);
    state.listing.nextPage = fresh.nextPage;
    state.listing.refreshing = false;
    const previousScroll = scroll.scrollTop;
    renderListing();
    scroll.scrollTop = previousScroll;
    reconcileCards(state.listing.items, () => {
      if (token !== routeToken || state.route !== 'listing') return;
      const top = scroll.scrollTop;
      renderListing();
      scroll.scrollTop = top;
    });
  } catch (error) {
    if (token !== routeToken) return;
    state.listing.refreshing = false;
    if (!state.listing.items.length) renderError(error, 'listing');
    else { updateListingFooter(); toast('列表更新失败，已显示缓存内容', true); }
  }
}

async function loadMoreListing() {
  const listing = state.listing;
  if (state.route !== 'listing' || !listing?.nextPage || listing.refreshing || listing.loading || listing.error) return;
  const token = routeToken;
  const pageNumber = listing.nextPage;
  listing.loading = true;
  updateListingFooter();
  try {
    const page = await api(listing.kind, { page: pageNumber, slug: listing.slug, label: listing.label });
    if (token !== routeToken || state.listing !== listing || state.route !== 'listing') return;
    const known = new Set(listing.items.map(item => String(item.id)));
    const added = asArray(page.items).filter(item => {
      const id = String(item.id);
      if (known.has(id)) return false;
      known.add(id);
      return true;
    });
    listing.items.push(...added);
    listing.nextPage = page.items?.length && page.nextPage > pageNumber ? page.nextPage : null;
    const grid = content.querySelector('.listing-grid');
    if (grid && added.length) {
      grid.insertAdjacentHTML('beforeend', added.map(itemCard).join(''));
      const images = [...grid.querySelectorAll('img[data-cover]')].slice(-added.length);
      cover.hydrateList(images, 8);
    }
    const count = content.querySelector('.listing-page > p');
    if (count) count.textContent = `已显示 ${listing.items.length} 部作品`;
    reconcileCards(added, () => {
      if (state.route !== 'listing' || state.listing !== listing) return;
      const top = scroll.scrollTop;
      renderListing();
      scroll.scrollTop = top;
    });
  } catch (error) {
    if (token === routeToken && state.listing === listing && state.route === 'listing') listing.error = true;
  } finally {
    listing.loading = false;
    if (state.listing === listing && state.route === 'listing') updateListingFooter();
  }
}

async function goCategory(tab, sub = subIndex(tab), keepCompact = false) {
  const token = ++routeToken;
  categoryObserver?.disconnect();
  if (state.route === 'home') rememberScreen();
  setRoute('category'); state.tab = tab; state.sub[tab] = sub; state.category = null; state.categoryBanner = 0;
  setNav(tab, keepCompact);
  // 选中态先跟手，再谈数据。见 applySubSelection 的注释。
  applySubSelection(sub);
  // 先读缓存再决定画什么。
  //
  // 原来是 renderSkeleton() → 发请求 → readCache() → 有缓存再 renderCategory()。
  // 那中间有一次多余的骨架屏：缓存明明在手，用户还是要先看见一屏闪白，再被内容
  // 替换掉。`readCache` 是同步读 localStorage，几十微秒的事，放在骨架屏之前没有代价。
  const id = categoryId();
  const cached = readCache(`category:${id}:1`);
  if (token !== routeToken) return;
  // 缓存够新就直接用，不发请求。
  //
  // 原来无论缓存多新都要走一趟 spider：网络 1.4~1.6 秒，而用户只是从「运动」
  // 切回「搞笑」看一眼。那 1.5 秒他什么也做不了，感觉就是卡。
  // 两分钟这个窗口是权衡：列表页的内容两分钟内不会变，而用户来回切分类
  // 基本都在这个尺度内。
  const CACHE_FRESH_MS = 120000;
  const cacheAge = cached?.at ? Date.now() - cached.at : Infinity;
  const freshEnough = Boolean(cached?.data) && cacheAge < CACHE_FRESH_MS;
  state.category = cached?.data
    ? { ...cached.data, refreshing: !freshEnough, loading: false, error: false }
    : null;
  // 永远先把分类页的「壳」画出来，有没有数据都一样。
  //
  // 这一条是死规矩。原来的分支是「没缓存 → renderSkeleton()」，而骨架屏里
  // **没有那一排子 tab**：用户点下去 closest('[data-sub]') 匹配不到任何东西，
  // 事件直接被丢掉，goCategory 根本不执行 —— 界面一动不动。
  //
  // 实测日志（2026-10-19 真机，冷启动后第一次进分类页）：
  //   [sub] sub=0 page=false btns=-1        ← page 是 null，一个可点的按钮都没有
  // 这就是「连续点击很卡」的真正来源：不是慢，是那几下点击压根没被接收。
  //
  // renderCategory 对 state.category 为 null 是安全的：它把 items 当空数组，
  // 画出来的就是「有标题、有子 tab、有区块标题、列表为空」的一屏，正好可以点。
  renderCategory(keepCompact);
  if (state.category) {
    setCategoryBusy(false);
    if (freshEnough) return;   // 缓存判定够新 —— 到此为止，不碰网络
  } else {
    setCategoryBusy(true);
  }
  setNav(tab, true);
  const refreshing = api('category', { id, page: 1 });
  refreshing.catch(() => {});
  try {
    const fresh = await refreshing;
    if (token !== routeToken) return;
    setCategoryBusy(false);
    const changed = state.category && listSignature(state.category) !== listSignature(fresh);
    const firstLoad = !state.category;
    const previousScroll = scroll.scrollTop;
    state.category = { ...fresh, refreshing: false, loading: false, error: false };
    state.categoryItems = asArray(fresh.items);
    noteNetSuccess();
    writeCache(`category:${id}:1`, fresh);
    if (firstLoad || changed) {
      renderCategory(keepCompact || state.compactHeld);
      if (!firstLoad) scroll.scrollTop = previousScroll;
    } else {
      refreshMissingImages(asArray(fresh.items));
      updateCategoryFooter();
    }
    reconcileCards(state.categoryItems, () => {
      if (token !== routeToken || state.route !== 'category' || categoryId() !== id) return;
      const top = scroll.scrollTop;
      writeCache(`category:${id}:1`, state.category);
      renderCategory(state.compactHeld);
      scroll.scrollTop = top;
    });
  } catch (error) {
    if (token !== routeToken) return;
    setCategoryBusy(false);
    noteNetFailure();
    if (!state.category) renderError(error, 'category');
    else { state.category.refreshing = false; updateCategoryFooter(); toast('源暂时连不上，稍后自动重试', true); }
    scheduleCategoryRetry(token, id);
  }
}

async function goLibrary(tab = 'history') {
  const token = ++routeToken;
  if (state.route === 'home') rememberScreen();
  state.libraryTab = tab;
  setRoute('library'); state.tab = 'library'; setNav('library');
  // 观看记录和收藏都是本地数据，手上已经有的先画出来。
  // 桥调用统一排在 4 个线程的池子里，而真机上网络请求要 25 秒才超时 ——
  // 池子被慢请求占满时这里就得排队，干等骨架屏就成了「点我的要加载很久」。
  if (state.library) renderLibrary(); else renderSkeleton();
  setNav('library', true);
  try {
    const library = await api('library');
    if (token !== routeToken) return;
    state.library = library;
    renderLibrary();
  } catch (error) {
    if (token === routeToken && !state.library) renderError(error, 'library');
  }
}

function goSettings() {
  state.settingsReturn = captureScreen();
  setRoute('settings'); state.tab = 'library'; setNav('library');
  renderSettings();
  // 设置页上那几个胶囊（已连接 / 硬解 / 已启用）是**读出来的事实**，不是偏好。
  // 只靠启动时那一次，用户从代理页连上节点再退回来，胶囊还写着「未启动」。
  // 这里补一次静默刷新：两个都不联网，几毫秒回来，回来才重画。
  bootProxyCore(true).catch(() => {});
  nativePlayerInfo().then(() => { if (state.route === 'settings') renderSettingsKeepScroll(); }).catch(() => {});
  refreshSources().then(() => { if (state.route === 'settings') renderSettingsKeepScroll(); }).catch(() => {});
}

function goAccountManagement() {
  if (state.route === 'account-management') return;
  if (state.route !== 'settings') goSettings();
  state.accountReturn = captureScreen();
  setRoute('account-management');
  renderAccountManagement();
}

async function goSearch(query) {
  const token = ++routeToken;
  if (state.route === 'home') rememberScreen();
  state.previous = { route: state.route, tab: state.tab };
  setRoute('search'); state.query = query.trim(); state.search = null;
  // 记录放在这一层而不是 submit 事件里：历史、重试（goSearch(state.query)）、
  // 以后的深链入口都会走到这儿，一处记全。重复的词由 recordSearchHistory 去重。
  recordSearchHistory(state.query);
  state.searchPending = true; state.searchError = '';
  scroll.scrollTop = 0;
  renderSearchLoading();
  let timeout;
  const refreshing = api('search', { query: state.query });
  refreshing.catch(() => {});
  // 同步读缓存：上一次搜同一个词的记录先顶上，不用等接口。
  const cachedSearch = readCache(`search:${state.query}`);
  if (cachedSearch?.data) { state.search = cachedSearch.data; renderSearch(); }
  try {
    const fresh = await Promise.race([refreshing, new Promise((_, reject) => {
      timeout = setTimeout(() => reject(new Error('搜索连接超时，请重试')), 25000);
    })]);
    if (token !== routeToken) return;
    state.searchPending = false; state.search = fresh;
    writeCache(`search:${state.query}`, fresh);
    renderSearch();
    reconcileCards(state.search.items, () => {
      if (token !== routeToken || state.route !== 'search') return;
      writeCache(`search:${state.query}`, state.search);
      renderSearch();
    });
  } catch (error) {
    if (token !== routeToken) return;
    state.searchPending = false;
    if (state.search) { state.searchError = error.message || '搜索刷新失败'; renderSearch(); }
    else renderError(error, 'search');
  } finally {
    clearTimeout(timeout);
  }
}

function sourceItem(id) {
  // 不用 flatMap：那是 Chrome 69。这条链上最低要照顾到 Chrome 58 ——
  // 详情页从列表回推标题就靠它，整句解析失败的话详情页直接白屏。
  const candidates = [state.listing?.items, state.home?.featured, state.home?.homepageRecommend, state.home?.homepageNewest, state.home?.recommend?.items, state.home?.newest?.items, state.category?.items, state.search?.items, state.library?.bookmarks, state.library?.progress]
    .reduce((all, group) => all.concat(asArray(group)), []);
  // id 一律按字符串比：调用点传进来的是 dataset（恒为字符串），而 REST 适配层
  // 的 item.id 可能还是数值，严格相等会永远 miss，标题退化成「剧集 N」兜底。
  return candidates.find(item => String(item.id) === String(id)) || { id, title: `剧集 ${id}` };
}

function fetchDetail(id) {
  const key = String(id);
  if (!detailInflight.has(key)) {
    const promise = api('detail', { id }).then(fresh => {
      writeCache(`detail:${key}`, fresh);
      detailInflight.delete(key);
      return fresh;
    }).catch(error => { detailInflight.delete(key); throw error; });
    detailInflight.set(key, promise);
  }
  return detailInflight.get(key);
}

async function goDetail(id, trigger) {
  // 同 sourceItem：dataset 来的 id 是字符串，state.sourceItem.id 可能是数值，
  // 严格相等会让「详情页原位重试」对数值 id 永远不命中。
  if (state.route === 'detail' && String(state.sourceItem?.id ?? '') === String(id)) {
    // Retry in place; preserve the original source card and return destination.
    state.detailLoading = true; renderDetail();
    const token = ++routeToken;
    try { const fresh = await fetchDetail(id); if (token === routeToken) { state.detail = fresh; state.detailLoading = false; renderDetail(); } }
    catch (error) { if (token === routeToken) { state.detailLoading = false; renderError(error, 'detail'); } }
    return;
  }
  const token = ++routeToken;
  // 卡片过渡动画的开关必须**先于** capture 生效：capture 里有两趟 cloneNode
  // （页头 + 折叠面板），关着动画还照跑就是纯浪费；更严重的是它让 entrySource
  // 恒为真，下面 `hasDetailData && !entrySource` 永远不成立 —— 明明有缓存，
  // 从卡片进详情还是要白等一趟网络。2026-10-19 用户反馈动画消失 + 缓存失效，
  // 根子都在这行没跟着开关走。动画重新打开（用户要求），capture 跟着开关走。
  const CARD_TRANSITION_ENABLED = true;
  const entrySource = !CARD_TRANSITION_ENABLED || (bannerChanging && trigger?.closest('.hero'))
    ? null
    : HGCardTransition.capture(trigger);
  // 从播放器回来时 content 里可能还留着「正在打开播放器…」的 boot 占位页，
  // 把它当返回目标存下来，返回时就会还原出那一屏残页。占位页不算页面。
  const placeholder = state.route === 'feed' || content.firstElementChild?.classList?.contains('feed-boot');
  if (placeholder) {
  // replaceChildren 是 Chrome 86 才有的。这条链要照顾到 Chrome 58 ——
  // 在那些机器上它不是解析失败，是每次切页/返回都 TypeError，
  // 表现成「点了返回没反应」。等价写法只有这几行。
    while (content.firstChild) content.removeChild(content.firstChild);
    state.route = 'detail';
  }
  rememberScreen();
  state.previous = {
    route: state.route, tab: state.tab, sub: subIndex(state.tab), query: state.query,
    scrollTop: scroll.scrollTop, compactHeld: state.compactHeld,
    horizontalScroll: [...content.querySelectorAll('.film-row,.filter-row,.home-tag-scroll')].map(row => row.scrollLeft),
    pageNode: content.firstElementChild
  };
  if (state.route === 'listing' && state.listing) state.listing.refreshing = false;
  listingObserver?.disconnect();
  categoryObserver?.disconnect();
  state.sourceItem = sourceItem(id);
  state.sourceGroup = state.route === 'category' ? categoryId() : state.sourceItem.category || '';
  const aiGroup = state.sourceGroup === 'ai-huanlian' || state.sourceGroup === 'ai-mogai';
  const candidates = state.route === 'category' ? asArray(state.category?.items) : state.route === 'search' ? asArray(state.search?.items) : state.route === 'listing' ? asArray(state.listing?.items) :
    [...asArray(state.home?.recommend?.items), ...asArray(state.home?.newest?.items)];
  state.sourceQueue = aiGroup ? candidates.filter(item => item.category === state.sourceGroup) : [];
  setRoute('detail'); state.detail = { ...state.sourceItem }; state.detailLoading = true; state.bookmarked = false;
  scroll.scrollTop = 0;
  updateCompact();
  renderDetail();
  // 开关和取舍说明在 entrySource 那一段（上面）。这里的旧注释已并入那边。
  if (entrySource && CARD_TRANSITION_ENABLED) {
    detailCard = new HGCardTransition({
      app: document.getElementById('app'), scroll, content, source: entrySource, snapshot: state.previous,
      isDetail: () => state.route === 'detail',
      onClose: () => { returnFromDetail(); trigger?.focus({ preventScroll: true }); }
    });
    detailCard.open();
  }
  const refreshing = fetchDetail(id);
  const cached = readCache(`detail:${id}`);
  if (token !== routeToken) return;
  // 「用动画盖加载」：放大动画进行中 #content 保持 prepare() 时克隆的那一屏，
  // 任何要重建详情 DOM 的渲染都排到 onOpened（动画落定）之后 —— 动画中途的
  // innerHTML 重建 + 封面 hydrate 就是「点一下直接变大」那个掉帧的来源。
  // 没有过渡动画（重试、无卡片入口）时 detailCard 为空，渲染照旧立即执行。
  const queueDetailRender = render => {
    if (!detailCard || detailCard.phase === 'opened' || detailCard.phase === 'closed') { render(); return; }
    const previous = detailCard.onOpened;
    detailCard.onOpened = () => { if (typeof previous === 'function') previous(); render(); };
  };
  let hasDetailData = Boolean(cached?.data);
  // 有缓存就先渲染缓存，不等网络 —— 即便这次点击带了过渡动画。
  // 缓存渲染顶掉的只是首屏那一份列表数据，数据回来后「内容没变就不重建」
  // 的判断会挡住第二次白做（见下面 changed）。
  if (hasDetailData) { state.detail = cached.data; state.detailLoading = false; queueDetailRender(() => renderDetail()); }
  api('bookmark-state', { id }).then(saved => { if (token === routeToken) { state.bookmarked = saved; if (state.detail) queueDetailRender(() => renderDetail()); } }).catch(() => {});
  try {
    const fresh = await refreshing;
    if (token === routeToken) {
      // 只有「内容真的变了」才重建整页 DOM。
      //
      // 卡片点进去时先用手上那份（列表里的 title/cover）渲染了一屏，数据回来后
      // 原来无条件 renderDetail() —— 又是几十个节点的 innerHTML 重建 + 封面 hydrate，
      // 正好落在动画结束那几百毫秒里。多数时候标题封面是一样的，这一次重建纯属白做。
      const changed = !state.detail
        || state.detail.title !== fresh.title
        || state.detail.cover !== fresh.cover
        || state.detail.description !== fresh.description
        || asArray(state.detail.episodes).length !== asArray(fresh.episodes).length;
      state.detail = fresh;
      state.detailLoading = false;
      if (changed) queueDetailRender(() => renderDetail());
    }
  } catch (error) {
    if (!hasDetailData && token === routeToken) { state.detailLoading = false; renderError(error, 'detail'); }
  }
}

function returnFromDetail() {
  const previous = state.previous || { route: 'home', tab: 'home' };
  if (!previous.pageNode) { goHome(); return; }
  ++routeToken;
  setRoute(previous.route);
  state.tab = previous.tab;
  if (previous.route === 'category') state.sub[previous.tab] = previous.sub;
  state.query = previous.query || '';
  state.compactHeld = previous.compactHeld;
  while (content.firstChild) content.removeChild(content.firstChild);
  content.appendChild(previous.pageNode);
  state.previous = null;
  content.querySelectorAll('.film-row,.filter-row,.home-tag-scroll').forEach((row, index) => {
    row.scrollLeft = previous.horizontalScroll?.[index] || 0;
  });
  document.querySelectorAll('#dock [data-nav]').forEach(button =>
    button.classList.toggle('active', button.dataset.nav === state.tab));
  scroll.scrollTop = previous.scrollTop || 0;
  if (previous.route === 'category') compact.innerHTML = compactMarkup();
  updateCompact();
  cover.hydrate();
  if (previous.route === 'home') refreshHomeHistory();
  if (previous.route === 'category') observeCategoryEnd();
  if (previous.route === 'listing') observeListingEnd();
  if (isBannerRoute()) { restoreBannerIndex(); scheduleBannerAuto(); }
}
