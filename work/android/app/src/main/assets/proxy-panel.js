'use strict';
// 自 app.js 拆出：网络代理那一整屏 —— 状态读取、节点库、测速、整页渲染、进页引导。
// 同一段里还住着 renderSettingsKeepScroll 和 playerChip：前者是「重画设置页但保住
// 滚动位置」，代理状态是它最主要的触发者（核心连上、节点读回来都在异步里改胶囊）；
// 后者是播放器那一行的胶囊，和 proxyChip 共用同一份 state 读法。切到别处只会
// 制造第二处状态读取。
// 网络代理。实测直连那条链六次里四次 9~18 秒（cf-ray 落在 LAX），而换路由
// 是客户端唯一能真正改变延迟的手段 —— DoH、连接复用、预热都只是把已经确定的
// 那段延迟挪个位置，换路才是换一条路。
// ── 网络代理 ──────────────────────────────────────────────────────────
//
// 三条路：直连 / 内置核心 / 外部地址。前面那条老链路（手填一个地址自己搭代理）
// 还留着，因为它是唯一不依赖核心能不能在这台机器上跑起来的办法 —— 老设备没
// arm64，内置核心根本起不来（ProxyCore.unavailableReason() 会给原因）。
//
// 页面不自己推导「现在到底走哪条」：Java 的 __proxy:status 已经把核心状态、
// 用户手填值、以及 AiiHttp.effectiveProxy() 的结论合成了一份。这里只负责显示，
// 因为「模式是内置、核心没起来、于是回落到外部」这三种事实的交叉判断只该有一处。
const PROXY_MODES = [
  { value: 'direct', label: '直连', note: '所有请求都不走代理。' },
  { value: 'builtin', label: '内置核心', note: '粘一条分享链接，App 自己在本机起 SOCKS5。' },
  { value: 'external', label: '外部地址', note: '自己有一个代理（路由器、电脑上跑的那个）。' }
];

// __proxy: 走 detail 桥，和 __source: 同一个套路。加动作不用碰 dispatch 那两张表。
async function proxyCoreCommand(command) {
  const reply = await bridgeCall('proxy', { id: command });
  const result = typeof reply === 'string' ? JSON.parse(reply) : reply;
  return result || {};
}

// 状态只放一份在 state 上，渲染和操作都读它，操作完把新状态整块换掉。
function proxyState() {
  if (!state.proxyCore) state.proxyCore = { mode: 'direct', running: false, loaded: false };
  return state.proxyCore;
}

function applyProxyCore(status) {
  // 合并不是覆盖：失败答复（ok:false 只带 error）不该把上一次读到的 mode/port
  // 抹成 undefined —— 那样页面会在「内置核心」和「直连」之间闪一下。
  const patch = {};
  Object.keys(status || {}).forEach(key => { if (status[key] !== undefined) patch[key] = status[key]; });
  state.proxyCore = { ...proxyState(), ...patch, loaded: true };
  if (state.route === 'proxy-settings') renderProxySettings();
  // 设置页那一行的胶囊也是从这里来的。不重画的话，用户在代理页连上节点、
  // 退回设置，看到的还是进来时那个「未启动」。
  else if (state.route === 'settings') renderSettingsKeepScroll();
  return state.proxyCore;
}

/**
 * 重画设置页但保住滚动位置。
 *
 * 设置页的胶囊会跟着后台状态自己变（代理连上了、播放器探测回来了、源列表刷新了），
 * 那些都是**异步**回来的。直接 renderSettings() 会整块换 innerHTML，滚动条跟着
 * 跳回顶部 —— 用户刚滚到「恢复出厂设置」，一刷新就被扔回标题。
 */
function renderSettingsKeepScroll() {
  const top = scroll.scrollTop;
  renderSettings();
  scroll.scrollTop = top;
}

// 冷启动恢复一次：盘上是「内置核心」就顺手拉起，不然用户重启完 App 会发现
// 昨晚开的代理今天没了，而页面上还写着「内置核心」。
//
// force = 每次进设置页都要重问一次。状态是会**自己变**的（核心可能崩了、用户可能
// 在别处点了停止），所以「刚才查过」不能当成「现在还这样」—— 只靠启动时那一次，
// 进设置页看到的可能还是启动那一刻的画面。页面重画时读内存那份，避免闪烁。
function bootProxyCore(force) {
  const core = proxyState();
  if (core.booting) return core.booting;
  if (core.loaded && !force) return Promise.resolve(core);
  core.booting = proxyCoreCommand(force ? 'status' : 'boot')
    .then(applyProxyCore)
    .catch(error => { core.booting = null; core.error = error.message || '代理状态读不到'; applyProxyCore({}); })
    .then(value => { core.booting = null; return value; });
  return core.booting;
}

/** 播放器那一行的胶囊。内核已经是 VLC 了，用户能选的实际只有硬解 / 软解。 */
function playerChip() {
  const info = state.nativeInfo;
  if (info == null) return { text: '检测中', tone: '' };
  if (!info.native) return { text: '不支持', tone: 'is-warn' };
  return playerPrefs().decode === 'sw' ? { text: '软解', tone: 'is-ok' } : { text: '硬解', tone: 'is-ok' };
}

/**
 * 设置页那一行右侧的状态胶囊。
 *
 * 返回 {text, tone}，text 控制在 2~5 个字 —— 那一格只有约 90px 宽，
 * 塞一句「内置核心 · 127.0.0.1:39693 已连接」会把标题挤成省略号。
 * 详细状态（端口、节点、出口）在代理页里，那儿有地方展开。
 */
function proxyChip() {
  const core = proxyState();
  const mode = core.mode || 'direct';
  if (mode === 'builtin') {
    if (core.running) return { text: '已连接', tone: 'is-ok' };
    return { text: core.error ? '启动失败' : '未启动', tone: 'is-warn' };
  }
  if (mode === 'external') return core.external ? { text: '外部地址', tone: 'is-ok' } : { text: '未填地址', tone: 'is-warn' };
  return { text: '直连', tone: '' };
}

/**
 * 订阅导入 + 测速的状态。
 *
 * 和 proxyState 分开放：代理状态是「核心在不在跑」（Java 侧的事实），
 * 订阅状态是「这一页正在干什么」（纯前端），混一起会让 applyProxyCore
 * 的合并逻辑把测速进度冲掉。
 */
function subState() {
  if (!state.proxySub) {
    state.proxySub = {
      url: '', count: 0, summary: '', busy: false,
      nodes: null, nodesBusy: false, nodesNote: '',
      // 订阅和免费节点各测各的、各显示各的。分成两份状态是因为它们本来就是
      // 两类东西：一份是用户付了钱的线路，一份是公开共享、随时会挂的节点。
      // 混成一份的话，「通了几个」这种数字永远说不清是在说谁。
      subSpeed: [], subNote: '', subTesting: false, subBad: false,
      freeSpeed: [], freeNote: '', freeTesting: false, freeBad: false,
    };
  }
  return state.proxySub;
}

/**
 * 订阅卡片标题下面那行：只放「地址来自哪、多少条」，别再重复一遍摘要。
 *
 * 以前这里是 `sub.summary`（「10 个节点」），表单下面又有一行同样的字 —— 同一句
 * 话在一张卡里出现两次，用户第一反应是「是不是加载重了」。
 */
function subLine(sub) {
  if (!sub.url) return '有机场的话比免费节点稳得多';
  let host = '';
  try { host = new URL(sub.url).host; } catch (error) { host = '已配置'; }
  const count = Number(sub.count) || 0;
  return host + (count ? ' · ' + count + ' 条' : '');
}

/**
 * 免费节点那一行说明：这批是哪天的、有多少条、是不是包里的老货。
 *
 * 只说免费那一份 —— 订阅的条数在下面「我的订阅」里，两边混着算过一次，
 * 卡片上写「订阅 12 条」而订阅卡写「10 个节点」，谁看都对不上。
 */
function freeLine(sub) {
  const nodes = sub.nodes;
  if (!nodes) return '节点库：读取中…';
  if (nodes.ok === false) return '节点库：读不出来' + (nodes.error ? '（' + esc(nodes.error) + '）' : '');
  const when = nodes.updated ? '维护于 ' + esc(String(nodes.updated)) : '没有维护日期';
  const how = nodes.source === 'assets' ? '，随安装包自带' : '';
  const fresh = nodes.stale && nodes.source !== 'assets' ? '，可以点「换一批」' : '';
  return when + ' · ' + (Number(nodes.nodes) || 0) + ' 条' + how + fresh;
}

/**
 * 读一次节点库状态。不联网，几毫秒就回来，所以进设置页就调。
 *
 * 失败不弹 toast：这只是个说明行，读不到就显示「读取中」，
 * 拿它报错会让人以为整个代理坏了。
 */
async function loadNodesStatus() {
  const sub = subState();
  try {
    const result = await proxyCoreCommand('nodes');
    if (result && result.nodes) {
      sub.nodes = result.nodes;
      renderProxySettingsKeepScroll();
    }
  } catch (ignored) {}
}

/**
 * 手动检查更新。
 *
 * 三个候选地址逐个试，全失败时后端会把原因带回来。成功之后**必须重测**——
 * 旧的测速结果指向的是上一批节点，下标已经对不上了。
 */
async function refreshNodesFeed() {
  const sub = subState();
  // 测速跑着的时候别来捣乱：下面会把 freeSpeed/freeNote 清空，正在跑的那轮
  // 下一批又把列表写回来，中间状态闪一次、计数对不上。和测速按钮同一把守卫。
  if (sub.busy || sub.nodesBusy || sub.freeTesting || sub.subTesting) return;
  sub.nodesBusy = true;
  sub.nodesNote = '正在获取…';
  sub.freeSpeed = [];
  sub.freeNote = '';
  renderProxySettingsKeepScroll();
  try {
    const result = await proxyCoreCommand('nodes?do=refresh');
    const nodes = (result && result.nodes) || {};
    sub.nodes = nodes;
    if (result.ok === false) {
      sub.nodesNote = '更新失败：' + (nodes.error || result.error || '网络不通') + '。还在用旧的那份。';
      sub.nodesBad = true;
    } else {
      sub.nodesNote = '已更新到 ' + (nodes.updated || '最新版本') + '，共 ' + (nodes.nodes || 0) + ' 条。';
      sub.busy = false;
      sub.nodesBusy = false;
      renderProxySettingsKeepScroll();
      await runSpeedTest('builtin');
      return;
    }
  } catch (problem) {
    sub.nodesNote = '更新失败：' + problem.message;
    sub.nodesBad = true;
  } finally {
    sub.nodesBusy = false;
    renderProxySettingsKeepScroll();
  }
}

/**
 * 测速结果列表。只列「通了」的，按延迟升序（Java 侧已经排好）。
 *
 * 不通的不显示 —— 用户要的是「点哪个能用」，不是一份体检报告。
 * 数量写在上面的提示行里，看得到「40 个里通了 6 个」这个事实。
 */
/**
 * 重画设置页但保住滚动位置。
 *
 * 订阅和测速这两步是**原地更新**的（不换路由），如果直接 renderProxySettings()，
 * 整页 innerHTML 换掉会让滚动条弹回顶部 —— 用户刚往下滚到测速列表，一刷新就
 * 被扔回「返回设置」，体验很糟。
 */
function renderProxySettingsKeepScroll() {
  // 滚动容器是 #scroll（html/body 是 overflow:hidden，document 永远滚不动），
  // 保存/恢复必须认它 —— 之前写 document.scrollingElement，那上面恒是 0，
  // 等于没保，测速每批回来还是被弹回顶部。变量名也不能叫 scroll，
  // 会把全局那个 #scroll 元素遮住。
  const top = scroll.scrollTop;
  // 整页 innerHTML 换掉会连输入框一起重建：用户正在粘订阅地址、测速结果每批
  // 回来就重画一次，刚粘进去的那串就被「上次成功拉取的地址」覆盖了。
  // 所以重画前先把焦点那一格的 id / 值 / 光标位置记下来，重画后放回去。
  const active = document.activeElement;
  const focusId = active && active.id && active.tagName === 'INPUT' ? active.id : '';
  const focusValue = focusId ? active.value : '';
  const caret = focusId && typeof active.selectionStart === 'number' ? active.selectionStart : null;
  renderProxySettings();
  scroll.scrollTop = top;
  if (!focusId) return;
  const again = document.getElementById(focusId);
  if (!again || again.tagName !== 'INPUT') return;
  if (again.value !== focusValue) again.value = focusValue;
  try {
    if (caret != null) again.setSelectionRange(caret, caret);
    again.focus({ preventScroll: true });
  } catch (error) {}
}

/**
 * 跑一轮测速。**分批调用**，每批回来就刷新一次界面。
 *
 * 为什么不一次测完：一次测 12 个要 20 多秒，中途页面什么都不变，用户会以为
 * 按钮坏了。分批之后每 5~9 秒能看到「已测 3 个，通了 1 个」，而且先测出来的
 * 能用的节点会立刻出现在列表里。
 *
 * source 决定测哪一类：'subscription' 只测用户自己的订阅，'builtin' 只测内置
 * 免费节点。两轮互不干扰 —— 分开测是刻意的，混成一锅时免费节点延迟低就会
 * 排在付费线路前面，点错一下就把用户的出口换成陌生人的服务器。
 */
async function runSpeedTest(source) {
  const sub = subState();
  const isSub = source === 'subscription';
  if (isSub ? sub.subTesting : sub.freeTesting) return;
  // 两边同时跑会踩坏后端的累积表（speedAccum 只有一份，下标会串）。所以互斥。
  if (isSub ? sub.freeTesting : sub.subTesting) {
    toast('正在测另一类节点，等它跑完再点');
    return;
  }
  if (isSub) { sub.subTesting = true; sub.subSpeed = []; sub.subNote = '正在测速…'; sub.subBad = false; }
  else { sub.freeTesting = true; sub.freeSpeed = []; sub.freeNote = '正在测速…'; sub.freeBad = false; }

  const setSpeed = list => { if (isSub) sub.subSpeed = list; else sub.freeSpeed = list; };
  const setNote = (text, bad) => {
    if (isSub) { sub.subNote = text; sub.subBad = Boolean(bad); }
    else { sub.freeNote = text; sub.freeBad = Boolean(bad); }
  };

  let batch = 0;
  try {
    // 这次渲染放进 try：标志位上面已经置上了，它要是抛出去，finally 之前就
    // 已经离开函数，subTesting 同样永远回不来（和「订阅流程预置 true」同一类）。
    renderProxySettingsKeepScroll();
    // 上限 10 批只是保险：正常 12 个 / 每批 3 个 = 4 批就结束。
    // 后端 done=true 或返回空就停，不会真的跑满 10 轮。
    while (batch < 10) {
      const result = await proxyCoreCommand('speed?batch=' + batch + '&source=' + source);
      if (result.ok === false) throw new Error(result.error || '测速失败');
      const list = Array.isArray(result.nodes) ? result.nodes : [];
      setSpeed(list);
      const tested = Number(result.tested) || list.length;
      const usable = Number(result.usable) || 0;
      if (result.done) {
        setNote('测了 ' + tested + ' 个，通了 ' + usable + ' 个。'
          + (usable ? '点一行直接连。' : '这一轮没测到通的，过会儿再试。'));
        break;
      }
      setNote('正在测速… ' + tested + '/' + (result.total || '?') + '，已通 ' + usable + ' 个');
      renderProxySettingsKeepScroll();
      batch++;
    }
  } catch (problem) {
    // 中断也要说人话：已经测出来的还能点，别让用户以为整轮全废了。
    const kept = (isSub ? sub.subSpeed : sub.freeSpeed).filter(item => item.ok).length;
    setNote('测速中断：' + (problem.message || '网络不稳')
      + (kept ? '。下面 ' + kept + ' 个是中断前测通的，还能点。' : '。'), true);
  } finally {
    if (isSub) sub.subTesting = false; else sub.freeTesting = false;
    renderProxySettingsKeepScroll();
  }
}

/**
 * 测速结果列表。只列「通了」的，按延迟升序（Java 侧已经排好）。
 *
 * 名字优先：机场给的备注（`#` 后面那段）才是人看得懂的东西，host:port 只是
 * 兜底 —— 一台机器上挂十个节点时，光看 IP 谁也分不清哪条是哪条。
 */
function renderSpeedList(items) {
  const usable = (items || []).filter(item => item.ok);
  if (!usable.length) return '';
  const active = Number(proxyState().index);
  return `<div class="speed-list">${usable.slice(0, 12).map(item => {
    const current = Number.isFinite(active) && active === Number(item.index);
    return `
    <button type="button" class="speed-item${current ? ' is-current' : ''}" data-proxy-use="${item.index}">
      <span class="speed-ms">${item.ms}ms</span>
      <span class="speed-node">${item.country ? `<span class="speed-country">${esc(item.country)}</span>` : ''}${esc(item.name || item.node)}</span>
      ${current ? '<span class="speed-current">使用中</span>' : ''}
    </button>`;
  }).join('')}</div>`;
}

function renderProxySettings() {
  const core = proxyState();
  const sub = subState();
  // 订阅地址存在 Java 那边（SUB_URL_KEY）。前端状态是内存里的，进程重启就没了，
  // 于是「我的订阅」会退回空状态、输入框空着、点拉取还提示「先填一个订阅地址」。
  // 每次拿到 status 都补一次，用户看到的才是「上次存的那条」。
  if (!sub.url && core.subUrl) sub.url = String(core.subUrl);
  const mode = core.mode || 'direct';
  const modePill = (item, on) =>
    `<button type="button" role="radio" aria-checked="${on ? 'true' : 'false'}" class="settings-seg-pill ${on ? 'selected' : ''}" data-proxy-mode="${item.value}">${item.label}</button>`;

  // 状态。核心在跑时给出口，没跑时给原因 ——「未启动」和「起不来」是两件事，
  // 但都只用一句短话说，细节（端口、节点、实际出口）收进第二行小字。
  let chip, chipTone, detail;
  if (mode === 'builtin') {
    if (core.running) {
      chip = '已连接'; chipTone = 'is-ok';
      detail = (core.host || '127.0.0.1') + ' · ' + (core.nodeName || core.node || '');
    } else if (core.unavailable) {
      chip = '不支持'; chipTone = 'is-warn'; detail = '这台设备跑不了内置核心：' + core.unavailable;
    } else if (core.error) {
      chip = '启动失败'; chipTone = 'is-warn'; detail = core.error;
    } else {
      chip = '未启动'; chipTone = 'is-warn'; detail = '保存一条分享链接就会启动';
    }
  } else if (mode === 'external') {
    chip = core.external ? '外部地址' : '未填地址';
    chipTone = core.external ? 'is-ok' : 'is-warn';
    detail = core.external ? core.external : '填一个地址，所有请求都走它';
  } else {
    chip = '直连'; chipTone = '';
    detail = '所有请求都不走代理';
  }

  const activeModeNote = PROXY_MODES.find(item => item.value === mode)?.note || '';

  content.innerHTML = `<div class="settings-page">
    <button type="button" class="listing-back" data-back="1">${icon('back')} 返回设置</button>
    <h1>网络代理</h1>

    <section class="settings-card" aria-label="连接方式">
      <div class="settings-row is-static">
        <span class="settings-icon">${icon('proxy')}</span>
        <span class="settings-body"><strong>连接方式</strong></span>
        <span class="settings-tail"><span class="settings-chip ${chipTone}">${esc(chip)}</span></span>
      </div>
      <div class="settings-row is-action">
        <div class="settings-seg-control" role="radiogroup" aria-label="代理模式">${PROXY_MODES.map(item => modePill(item, item.value === mode)).join('')}</div>
      </div>
      <div class="settings-row is-static"><small class="settings-note">${esc(activeModeNote)}　${esc(detail)}</small></div>
      <p id="proxy-core-error" class="auth-error" role="alert" hidden></p>
    </section>

    ${mode === 'builtin' ? `<section class="settings-card" aria-label="节点">
      <div class="settings-row is-static">
        <span class="settings-icon">${icon('source')}</span>
        <span class="settings-body"><strong>节点</strong><small>分享链接 · vmess / vless / trojan / ss</small></span>
        <span class="settings-tail">
          <button type="button" class="settings-mini" data-proxy-core="test"${core.running ? '' : ' disabled'}>测速</button>
          <button type="button" class="settings-mini" data-proxy-core="stop"${core.running ? '' : ' disabled'}>停止</button>
        </span>
      </div>
      <form id="proxy-core-form" class="password-form settings-form">
        <input id="proxy-link" type="text" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="vmess:// 、vless:// 、trojan:// 、ss://" value="${esc(core.link || '')}" aria-label="分享链接">
        <p id="proxy-core-form-error" class="auth-error" role="alert" hidden></p>
        <button type="submit">保存并启动</button>
      </form>
      <p class="settings-form-hint" id="proxy-core-test" hidden></p>
      <p class="settings-form-hint">链接存在 App 私有目录，含 uuid / 密码，卸载即清。核心会一直跑到手动停止或重启手机。</p>
    </section>

    <section class="settings-card" aria-label="免费节点">
      <div class="settings-row is-static">
        <span class="settings-icon">${icon('market')}</span>
        <span class="settings-body"><strong>免费节点</strong><small>${esc(freeLine(sub))}</small></span>
        <span class="settings-tail">
          <button type="button" class="settings-mini" data-proxy-sub="nodes-refresh"${sub.busy || sub.nodesBusy || sub.freeTesting || sub.subTesting ? ' disabled' : ''}>${sub.nodesBusy ? '更新中…' : '更新'}</button>
        </span>
      </div>
      <div class="settings-row is-action">
        <button type="button" class="settings-cta" data-proxy-sub="speed-free"${sub.freeTesting || sub.subTesting ? ' disabled' : ''}>${sub.freeTesting ? '测速中…' : '测速并挑选'}</button>
      </div>
      ${sub.nodesNote ? `<div class="settings-row is-static"><small class="settings-note${sub.nodesBad ? ' is-bad' : ''}">${esc(sub.nodesNote)}</small></div>` : ''}
      ${sub.freeNote ? `<div class="settings-row is-static"><small class="settings-note${sub.freeBad ? ' is-bad' : ''}">${esc(sub.freeNote)}</small>${sub.freeBad ? '<button type="button" class="settings-mini" data-proxy-sub="speed-free">重试</button>' : ''}</div>` : ''}
      ${renderSpeedList(sub.freeSpeed)}
      <p class="settings-form-hint">公开共享节点随时可能失效，流量会经过陌生人的服务器 —— 别用来登录重要账号。</p>
    </section>

    <section class="settings-card" aria-label="我的订阅">
      <div class="settings-row is-static">
        <span class="settings-icon">${icon('update')}</span>
        <span class="settings-body"><strong>我的订阅</strong><small>${esc(subLine(sub))}</small></span>
        ${sub.url ? '<span class="settings-tail"><button type="button" class="settings-mini" data-proxy-sub="clear">改回免费</button></span>' : ''}
      </div>
      <form id="proxy-sub-form" class="password-form settings-form">
        <input id="proxy-sub-url" type="text" inputmode="url" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="https://…/sub（V2Ray 订阅链接）" value="${esc(sub.url || '')}" aria-label="订阅地址">
        <button type="submit">${sub.url ? '更新订阅' : '拉取节点'}</button>
      </form>
      ${sub.url ? `<div class="settings-row is-action">
        <button type="button" class="settings-cta" data-proxy-sub="speed-sub"${sub.subTesting || sub.freeTesting ? ' disabled' : ''}>${sub.subTesting ? '测速中…' : '测我的订阅'}</button>
      </div>` : ''}
      ${sub.subNote ? `<div class="settings-row is-static"><small class="settings-note${sub.subBad ? ' is-bad' : ''}">${esc(sub.subNote)}</small>${sub.subBad ? '<button type="button" class="settings-mini" data-proxy-sub="speed-sub">重试</button>' : ''}</div>` : ''}
      ${renderSpeedList(sub.subSpeed)}
      <p id="proxy-sub-status" class="field-hint settings-form-hint"${sub.summary ? '' : ' hidden'}>${esc(sub.summary || '')}</p>
    </section>` : ''}

    ${mode === 'external' ? `<section class="settings-card" aria-label="代理地址">
      <div class="settings-row is-static">
        <span class="settings-icon">${icon('proxy')}</span>
        <span class="settings-body"><strong>代理地址</strong><small>所有请求都走它，留空等于直连</small></span>
      </div>
      <form id="proxy-form" class="password-form settings-form">
        <input id="proxy-value" type="text" inputmode="url" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="127.0.0.1:7890 或 socks5://127.0.0.1:7891" value="${esc(state.proxy || '')}" aria-label="代理地址">
        <p id="proxy-error" class="auth-error" role="alert" hidden></p>
        <button type="submit">保存</button>
      </form>
    </section>` : ''}

    ${mode === 'direct' ? `<section class="settings-card" aria-label="直连">
      <div class="settings-row is-static">
        <span class="settings-icon">${icon('check')}</span>
        <span class="settings-body"><strong>直连</strong><small>之前配过的节点和地址都还留着，切回去就能用</small></span>
      </div>
    </section>` : ''}
  </div>`;
}

function goProxySettings() {
  if (state.route === 'proxy-settings') return;
  if (state.route !== 'settings') goSettings();
  state.proxyReturn = captureScreen();
  setRoute('proxy-settings');
  // 先用上次那份状态画一屏（不然会白一下），再问 Java 要一次真实的。
  renderProxySettings();
  bootProxyCore(true);
  // 节点库那一行也要有值。这条路不联网，所以不等着 boot 的结果。
  loadNodesStatus();
  // 只有「外部地址」模式下才有那个输入框 —— 别的模式下别抢焦点弹键盘，
  // 并且要把偏移清掉：从母页滚到一半进来，页头那几行会被一起带出屏幕。
  const field = content.querySelector('#proxy-value');
  if (field) field.focus({ preventScroll: true });
  else scroll.scrollTop = 0;
}
