'use strict';
// 自 app.js 拆出：源市场、源设置页。
// 这三块只碰「源的地址清单」，不碰当前生效配置的内容（那是 Java 侧 user-source.json）。
// 入口还留在 app.js，这里提供 renderMarket / goMarket / renderSourceSettings / goSourceSettings。
// 内容源管理。
//
// 存的是**地址清单**，配置内容只有当前生效那一份（Java 侧 user-source.json）。
// 切换 = 拿那个地址的本地缓存 + 覆盖当前配置，跟着第一次填走完全一样的校验 ——
// 校验和干跑一个都不少（那才是「别把应用锁死」的那道闸），少的只是**网络那一段**。
// 缓存不在时（第一次切到某个源）照旧现场拉一次，拉回来的那份会存成缓存。
// ── 源市场 ────────────────────────────────────────────────────────────
// 市场是一份静态清单（服务器上的 market.json），点一下等于走一遍和手动粘贴
// 完全相同的 set 流程 —— 校验链一条不少，市场只是把「从哪找地址」这件事
// 替你办了。
// 市场地址不内置 —— App 出厂不带任何地址，谁来维护清单由用户自己决定。
// 用户粘一份清单地址，存在本地；换地址随时改。清单格式见 market.json 约定。
const MARKET_KEY = 'hgmarket:v1';
const MARKET_SRC_KEY = 'hgmarket:src';
const MARKET_TTL = 6 * 3600 * 1000;

function marketSource() {
  try { return String(localStorage.getItem(MARKET_SRC_KEY) || ''); } catch (error) { return ''; }
}
function setMarketSource(url) {
  try { localStorage.setItem(MARKET_SRC_KEY, url); localStorage.removeItem(MARKET_KEY); } catch (error) {}
}

// 经原生 HTTP 抓任意 https 文本（__source:fetch）。WebView 直接 fetch 会被 CORS
// 拦，第三方源清单不会给我们带 ACAO 头。
async function fetchTextViaBridge(url) {
  const result = await sourceCommand('fetch?url=' + encodeURIComponent(url));
  return String(result.body || '');
}

async function loadMarket(force = false) {
  const source = marketSource();
  if (!source) throw new Error('还没有填写源市场地址');
  if (!force) {
    try {
      const cached = JSON.parse(localStorage.getItem(MARKET_KEY) || 'null');
      if (cached && cached.at && Date.now() - cached.at < MARKET_TTL) return cached;
    } catch (error) {}
  }
  const text = await fetchTextViaBridge(source);
  const parsed = JSON.parse(text);
  const items = asArray(parsed.sources).filter(item => item && /^https:\/\//.test(String(item.url || '')));
  if (!items.length) throw new Error('市场清单是空的');
  const cache = { at: Date.now(), updated: parsed.updated || '', items };
  try { localStorage.setItem(MARKET_KEY, JSON.stringify(cache)); } catch (error) {}
  return cache;
}

// 加源并在成功后重启（换源必须重建 Activity，理由见 restartAfterSource）。
//
// ⚠ 必须走 HGSetup.addOneSource，不能直接用 `source set`。
//
// 爬虫脚本是**刻意不打包进 APK 的**（SpiderStore 只扫 files/spiders/，不读 assets），
// 所以「装配置」和「装脚本」是必须成对发生的两件事。裸 source set 只做前者，
// 结果是配置生效了、spider 文件却不在于磁盘上 —— 之后每一次内容请求都报
// 「找不到 spider: xxx」，用户看到的是一个装好了却什么都打不开的源。
// 市场安装的全局互斥。app.js 的点击入口只 disable 被点的那一颗，A 还在装
// （要走 source set + spider install 两趟桥）时点 B 会并发装两个源，谁最后
// 落盘谁生效，全凭完成顺序。装上一颗未落定就拒绝新的。
let marketInstalling = false;

async function addSourceFromMarket(url) {
  if (marketInstalling) {
    toast('正在切换源，等这一轮结束再选');
    return;
  }
  marketInstalling = true;
  // 入口只按住了被点的那颗；这里把整组按住，别的行不再可点。
  // 「使用中」那颗本来就该 disabled，整页重画时由 renderMarket 恢复。
  content.querySelectorAll('[data-market-use]').forEach(button => { button.disabled = true; });
  try {
    if (window.HGSetup && typeof window.HGSetup.addOneSource === 'function') {
      const one = await window.HGSetup.addOneSource(url);
      if (!one || one.ok === false) throw new Error((one && one.error) || '设置失败');
      // 装的是「只有配置、脚本还没跟上」的源：把话说明白，别让人点进去撞空。
      if (one.needsConfig) throw new Error('这个地址是爬虫脚本，还需要一份对应的源配置');
    } else {
      const result = await sourceCommand('set?url=' + encodeURIComponent(url));
      if (!result || !result.ok) throw new Error((result && result.error) || '设置失败');
    }
    // 到这里安装已成功、重启已排上；页面马上整体重载，标志不必（也不该）解开 ——
    // 重载后是全新一份模块状态。
    toast('已切换到这个源，正在重启…');
    restartAfterSource(() => { try { location.reload(); } catch (error) {} });
  } catch (error) {
    marketInstalling = false;
    // 按钮整组是我按住的，失败就得整组放开。整页重画最干净：
    // 「使用中」那颗本来就 disabled，不能被我一把 false 解了禁。
    if (state.route === 'market') renderMarket();
    throw error;
  }
}

function renderMarket() {
  const source = marketSource();
  const m = state.market || { loading: true };
  const saved = new Set(asArray(state.sources).map(item => item.url));
  const active = asArray(state.sources).find(item => item.active);
  if (!source) {
    content.innerHTML = `<div class="listing-page"><button type="button" class="listing-back" data-back="1">${icon('back')} 返回设置</button>
      <h1>源市场</h1>
      <p>粘一个源清单地址。清单由提供者维护，App 不内置任何地址。</p>
      <form id="market-form" class="password-form">
        <label for="market-value">源市场地址</label>
        <input id="market-value" type="text" inputmode="url" autocomplete="off" spellcheck="false" placeholder="https://example.com/market.json">
        <p class="field-hint">地址必须以 https:// 开头，返回 JSON：{"sources":[{"name":"…","desc":"…","url":"…"}]}</p>
        <p id="market-error" class="auth-error" role="alert" hidden></p>
        <button type="submit">使用这个市场</button>
      </form>
      <p class="market-footnote">没有清单地址？可以在「内容源」里手动添加单个源。</p>
    </div>`;
    return;
  }
  const cards = m.loading
    ? '<div class="market-loading"><span class="search-spinner" aria-hidden="true"></span><p>正在获取源清单…</p></div>'
    : m.error
      ? `<div class="market-loading"><p>${esc(m.error)}</p><button type="button" data-market-reload="1">重试</button></div>`
      : asArray(m.items).map(item => {
          const url = String(item.url || '');
          const isActive = active && active.url === url;
          const isSaved = saved.has(url);
          return `<li class="market-item">
            <div class="market-copy"><strong>${esc(item.name || url)}</strong><small>${esc(item.desc || '')}</small></div>
            <button type="button" class="market-add" data-market-use="${esc(url)}" ${isActive ? 'disabled' : ''}>${isActive ? '使用中' : isSaved ? '切换' : '添加并使用'}</button>
          </li>`;
        }).join('');
  content.innerHTML = `<div class="listing-page"><button type="button" class="listing-back" data-back="1">${icon('back')} 返回设置</button>
    <h1>源市场</h1>
    <p>清单里的源，点一下就会添加并切换过去，需要重启应用。</p>
    <ul class="market-list">${cards}</ul>
    <p class="market-footnote">清单来自 ${esc(source)}。<button type="button" class="market-change" data-market-change="1">更换地址</button></p>
  </div>`;
}

function goMarket() {
  state.marketReturn = captureScreen();
  state.market = { loading: Boolean(marketSource()) };
  setRoute('market');
  renderMarket();
  if (!marketSource()) return;
  loadMarket(true).then(cache => {
    state.market = { loading: false, items: cache.items };
    if (state.route === 'market') renderMarket();
  }).catch(error => {
    state.market = { loading: false, error: error.message || '获取失败' };
    if (state.route === 'market') renderMarket();
  });
}

// ── 加源入口的分流（阶段 2）────────────────────────────────────────
//
// 一个输入框，两条路。后缀决定装什么：
//
//   …/x.js    → spider 源码。bridgeCall('spider', {op:'install'})，进运行时清单。
//               **不切换内容源** —— 源码和「哪个源生效」是两件事，装完还要贴它的
//               json 配置（那份配置里写着 web.spider: 'x'）才真的用上。
//   …/x.json  → 源配置。走 sourceCommand('use')，切换并重启。
//   没有后缀  → 按配置处理。老用户粘的本来就是这种地址，那条路一直是配置。
//
// 为什么不让用户自己选入口：这是加源唯一的门，多一个下拉框就多一次「我该点哪个」。
function sourceKindOf(url) {
  const clean = String(url || '').trim().replace(/[?#].*$/, '').toLowerCase();
  return /\.js$/.test(clean) ? 'spider' : 'config';
}

/** 装 spider 源码，返回 {id,name,version}；失败把原因抛给调用方。 */
async function installSpiderSource(url) {
  return (await bridgeCall('spider', { op: 'install', url })) || {};
}

function renderSourceSettings() {
  const items = asArray(state.sources);
  const active = items.find(item => item.active);
  const rows = items.map(item => {
    const url = String(item.url || '');
    const label = item.alias || item.name || url;
    // 备注编辑：就地展开，不弹窗。备注只是给人看的一行字，改它不值得一个模态。
    if (state.sourceNote === url) {
      return `<li class="source-row is-editing">
        <form class="source-note-form" data-source-note-form="${esc(url)}">
          <label for="source-note">备注（可留空）</label>
          <input id="source-note" type="text" maxlength="40" autocomplete="off" placeholder="${esc(item.name || url)}" value="${esc(item.alias || '')}">
          <div class="source-note-actions">
            <button type="submit">保存</button>
            <button type="button" data-source-note-clear="${esc(url)}" ${item.alias ? '' : 'disabled'}>清空</button>
            <button type="button" data-source-note-cancel="1">取消</button>
          </div>
        </form>
      </li>`;
    }
    return `<li class="source-row${item.active ? ' is-active' : ''}">
      <button type="button" class="source-pick" data-source-use="${esc(url)}" ${item.active ? 'disabled' : ''}>
        <span><strong>${esc(label)}</strong><small>${esc(url)}</small></span>
        ${item.active ? '<em>当前</em>' : icon('arrow')}
      </button>
      <button type="button" class="source-note" data-source-note="${esc(url)}" aria-label="改 ${esc(label)} 的备注">备注</button>
      <button type="button" class="source-drop" data-source-forget="${esc(url)}" aria-label="删除 ${esc(label)}">×</button>
    </li>`;
  }).join('');

  content.innerHTML = `<div class="settings-page">
    <button type="button" class="listing-back" data-back="1">${icon('back')} 返回设置</button>
    <h1>内容源</h1>

    <section class="settings-card" aria-label="已保存的源">
      <div class="settings-row is-static">
        <span class="settings-icon">${icon('source')}</span>
        <span class="settings-body"><strong>已保存</strong><small>${items.length ? `${items.length} 个 · 点一个切过去` : '还没有保存过任何源'}</small></span>
        <span class="settings-tail"><button type="button" class="settings-mini" data-market="1">源市场</button></span>
      </div>
      ${items.length ? `<ul class="source-list">${rows}</ul>` : ''}
      <p class="settings-form-hint">切换源会重启应用。第二次之后走本地缓存，不用再等网络。</p>
      ${active ? `<div class="settings-row is-action"><button type="button" class="settings-quiet" data-source-refetch="${esc(String(active.url || ''))}">重新拉取这个源的配置</button></div>` : ''}
    </section>

    <section class="settings-card" aria-label="添加源">
      <div class="settings-row is-static">
        <span class="settings-icon">${icon('market')}</span>
        <span class="settings-body"><strong>添加一个源</strong><small>贴 .json 配置或 .js 源码，按后缀自动分流</small></span>
      </div>
      <form id="source-form" class="password-form settings-form">
        <input id="source-value" type="text" inputmode="url" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="https://…/source.json 或 …/source.js" aria-label="源地址">
        <input id="source-alias" type="text" maxlength="40" autocomplete="off" placeholder="备注（可留空），比如「量子」" aria-label="备注">
        <p id="source-error" class="auth-error" role="alert" hidden></p>
        <button type="submit">添加并切换</button>
      </form>
      <p class="settings-form-hint">.json 是源配置（切换并重启）；.js 是 spider 源码（装进运行时，不切换源）。</p>
    </section>

  </div>`;
}

/** 写/清一个源的备注。空串 = 清掉，退回自动取的名字。 */
async function saveSourceNote(url, text) {
  const clean = String(text == null ? '' : text).trim();
  try {
    const result = await sourceCommand('note?url=' + encodeURIComponent(url) + '&value=' + encodeURIComponent(clean));
    state.sources = asArray(result.items);
    state.sourceNote = '';
    renderSourceSettings();
    toast(clean ? '备注已保存' : '备注已清空');
  } catch (error) {
    toast(error.message, true);
  }
}

async function refreshSources() {
  const listing = await sourceCommand('list');
  state.sources = asArray(listing.items);
  const active = state.sources.find(item => item.active);
  state.sourceName = active ? (active.alias || active.name || active.url) : '';
  state.sourceCount = state.sources.length;
  // 挂出去给 feed-player.js 用：上报观看进度时要带上「这条属于哪个源」。
  //
  // 存的是**源身份**（Java 侧 UserSource.identity() 算的），不是源名字。
  // 规则：这套配置把 /api/videos/{id} 翻成哪个地址，把 {id} 挖掉剩下的那串。
  // 名字会被人改、同一个站还能挂两个名字，靠名字的话改一次名历史就全断；
  // 而 id 只在源内部唯一，只有「翻到哪儿」才决定了历史能不能互通。
  window.HG_SOURCE_KEY = listing.identity || '';
}

// 这条历史记录属不属于当前内容源。
//
// 三种情况当「算」，都按当前源显示：
//   · 记录压根没记来源   —— 这次改造之前的老记录
//   · 记的不是源身份     —— 早期版本存的是「源名字」，没有 hgsrc: 前缀。
//                          不加这条，升级之后所有人的历史会一次性消失。
//   · 当前源身份还没读出来 —— 首屏那几帧
function sameSource(item) {
  const mine = window.HG_SOURCE_KEY || '';
  const theirs = String(item?.source || '');
  if (!theirs || !mine) return true;
  if (theirs.indexOf(IDENTITY_PREFIX) !== 0) return true;
  return theirs === mine;
}

// 列表内容的指纹。重画一次整页的代价不只是掉帧 —— 用户可能已经往下滚了，
// 也可能正在改某一条的备注，重画会把这两样都打回原形。数据没变就不重画。
function sourceSignature(items) {
  return asArray(items).map(item => [item.url, item.alias, item.active ? 1 : 0].join('\u0001')).join('\u0002');
}

function goSourceSettings() {
  if (state.route === 'source-settings') return;
  if (state.route !== 'settings') goSettings();
  state.sourceReturn = captureScreen();
  const before = sourceSignature(state.sources);
  setRoute('source-settings');
  renderSourceSettings();
  // 二级页一律从顶上开始。不写这一行的话，从「设置」滚到一半再点进来，
  // 新页面会停在母页那个偏移上 —— 开头那几行直接看不到。
  scroll.scrollTop = 0;
  // 不 focus 底部那个输入框。它会把页面一路拽到底、顺带弹起键盘：
  // 用户点「内容源」是想看自己的源，不是想加一个新的。
  refreshSources().then(() => {
    if (state.route !== 'source-settings') return;
    if (sourceSignature(state.sources) === before) return;
    const top = scroll.scrollTop;
    renderSourceSettings();
    scroll.scrollTop = top;
  }).catch(() => {});
}
