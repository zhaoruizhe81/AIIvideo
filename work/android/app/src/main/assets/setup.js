/*
 * AIIvideo —— 首次设置页
 *
 * 出厂的 APK 里不带任何站点配置：装完是空的，用户自己填一个源地址才有内容。
 * 这个文件负责那一屏，以及「还没配源就别往下启动」这个闸门。
 *
 * ── 为什么单独一个文件 ──────────────────────────────────────────────
 * app.js 已经 1900 行，而这块逻辑跟播放、缓存、导航都无关，只跟源配置有关。
 * 而且它需要在**启动之前**跑完，跟 app.js 的启动顺序是两件事。
 *
 * ── 闸门怎么用 ──────────────────────────────────────────────────────
 * app.js 的启动改成先等 HGSetup.ensure()：
 *     HGSetup.ensure().then(() => contentReady).then(() => startHome())
 * 没配源时 ensure() 不 resolve，界面停在设置页，不会去拉一个注定失败的接口。
 */
(() => {
  'use strict';

  const overlay = document.getElementById('setup-overlay');
  const input = document.getElementById('setup-input');
  const button = document.getElementById('setup-submit');
  const errorBox = document.getElementById('setup-error');
  const hint = document.getElementById('setup-hint');

  let resolveReady = null;
  const ready = new Promise(resolve => { resolveReady = resolve; });
  let prompting = false;

  function showError(message) {
    errorBox.textContent = message || '';
    errorBox.hidden = !message;
  }

  // 软键盘会把「获取并应用」盖住 —— 面板垂直居中，而输入框一聚焦键盘就顶上来，
  // 按钮正好落在键盘区域里，用户填完得先手动收键盘才点得到。
  //
  // visualViewport 能拿到键盘占掉的高度（内层视口变矮 + 整体上移）。
  // 把这段高度转成容器的 padding-bottom，居中区域缩到键盘以上，面板就自己上移了。
  // 只在设置页可见时挂监听，别让它在别的页面上白跑。
  function syncKeyboardInset() {
    const viewport = window.visualViewport;
    if (!viewport) return;
    const covered = Math.max(0, window.innerHeight - viewport.height - viewport.offsetTop);
    overlay.style.setProperty('--kb', covered + 'px');
  }

  function show(showIt) {
    overlay.hidden = !showIt;
    syncKeyboardInset();
    // 通知 Java 重算返回键归属。设置页显示时必须保持「JS 接管」，
    // 否则 Java 会把返回回调注销掉，按返回直接退到后台，连 hgBack() 都不会被调。
    if (showIt) window.AndroidHost?.setBackEnabled?.(true);
    if (showIt) {
      // 自动聚焦会让安卓把软键盘顶起来，短暂挡住半屏。延迟一点等过渡结束。
      setTimeout(() => { try { input.focus(); } catch (error) {} }, 320);
    }
  }

  /** 问 Java 现在配没配源。走 source 桥的 get 指令（2026-10-18 起控制面独立 action）。 */
  async function current() {
    try {
      const reply = await bridgeCall('source', { id: 'get' });
      return typeof reply === 'string' ? JSON.parse(reply) : reply;
    } catch (error) {
      // 桥本身出问题（比如 detail 那个分支没打进去）时不能装死，
      // 否则用户看到的是一个永远转圈、也永远没有提示的设置页。
      return { ok: false, error: '无法读取配置：' + (error.message || error) };
    }
  }

  // 把「市场清单」当输入接受：拉回 JSON，若形如 {sources:[{name,url}]} 就在面板里
  // 列出可勾选的源。可以多选 —— 一次把要用的都加进源列表，最后选中的那个生效。
  // 返回 true 表示已接手渲染（调用方不再报原来的错）。
  //
  // 拉取失败时**必须自己说话** —— 但只在「确实是一份清单」的时候。
  //
  // 以前这里 return false 就完事，屏幕上留着的是上一条报错（set 那句「认不出这是
  // 什么配置」），于是「网络超时」被显示成「格式不认识」，用户和排查的人都会照错的
  // 方向找。现在：Java 已经断言过是清单（confident）就把失败原因写在它该在的位置；
  // 只是「顺便试试形状」的场合仍旧闭嘴，让调用方报原来那条更准确的原因。
  async function offerMarket(url, confident) {
    const fail = message => {
      if (confident) showError('这是一份源清单，但没拉下来：' + message);
      return confident;
    };
    let body = '';
    try {
      const reply = await bridgeCall('source', { id: 'fetch?url=' + encodeURIComponent(url) });
      const result = typeof reply === 'string' ? JSON.parse(reply) : reply;
      if (!result || !result.ok) return fail((result && result.error) || '未知原因');
      body = result.body || '';
    } catch (error) {
      return fail(error.message || String(error));
    }
    let parsed = null;
    try { parsed = JSON.parse(body); } catch (error) { return fail('响应不是 JSON'); }
    const items = Array.isArray(parsed && parsed.sources) ? parsed.sources : [];
    if (!items.length) return fail('清单里没有 sources 数组');
    const box = document.getElementById('setup-market');
    box.hidden = false;
    box.innerHTML = '<p class="setup-market-loading">这是一份源清单，勾选要用的（可多选，最后一个为当前源）：</p>'
      + items.map(item => `<label class="setup-market-item">
          <input type="checkbox" class="setup-market-check" value="${String(item.url || '').replace(/"/g, '&quot;')}">
          <span class="setup-market-copy"><strong>${String(item.name || '').replace(/</g, '&lt;')}</strong>
          <small>${String(item.desc || '').replace(/</g, '&lt;')}</small></span>
        </label>`).join('');
    const checks = [...box.querySelectorAll('.setup-market-check')];
    // 按钮在列表**外面**（app.html 里那个）。放进 innerHTML 的话它就成了 flex 列表的
    // 最后一项，跟着列表一起滚，还会被 .setup-panel button 的通栏样式压住末项。
    const apply = document.getElementById('setup-market-apply');
    apply.hidden = false;
    apply.disabled = true;
    apply.textContent = '先勾选至少一个源';
    const sync = () => {
      const picked = checks.filter(node => node.checked);
      apply.disabled = picked.length === 0;
      apply.textContent = picked.length ? `添加 ${picked.length} 个源并进入` : '先勾选至少一个源';
    };
    checks.forEach(node => node.addEventListener('change', sync));
    // apply 是 app.html 里的静态按钮，offerMarket 每渲染一次都会走到这里。
    // addEventListener 只会往上摞：第二回渲染后点一下，旧监听还握着上一轮
    // 已经脱离 DOM 的勾选框，把两轮勾选的源一起装上。覆盖式赋值保证只有一个。
    apply.onclick = () => addSelected(checks.filter(node => node.checked).map(node => node.value));
    showError('');
    return true;
  }

  /**
   * 装一个源。**spider 源要装两样东西**，这一条是 2026-10-19 真机踩出来的。
   *
   * <p>市场里每一条指向的是一份**源配置** json。REST 源到这就算装完了；
   * 但 spider 源的配置里只有一句 {@code web.spider: "agedm"} —— 内容是那份
   * js 跑出来的，配置本身不含任何解析逻辑。于是只装配置会出现：
   *
   * <pre>
   *   加载失败 / 找不到 spider: agedm
   * </pre>
   *
   * 用户视角完全无法理解：他明明"装上了"，源列表里也有这一条，界面上却什么都没有。
   * 而且这个错要等到首页请求才暴露，装的时候一声不吭。
   *
   * <p>所以这里在装完配置之后补一步：拿地址去探一次配置，读 {@code web.spider}，
   * 有就去同目录拉 {@code <id>.js} 装进 SpiderStore。两样齐了才算装好。
   *
   * <p>为什么用"同目录"推 js 地址而不是让市场条目多写一个字段：市场清单是
   * 已经发布出去的东西，加字段等于要求所有人重发一遍。而"配置和源码放一起"
   * 本来就是这套设计的约定（{@code /spiders/yhdm.json} 旁边就是 {@code yhdm.js}）。
   *
   * @return {ok, spider, installedJs, error} —— installedJs 是 js 的 id 或空串
   */
  /**
   * 装一个源。**spider 源要装两样东西**，这一条是 2026-10-19 真机踩出来的。
   *
   * <p>市场里每一条指向的是一份**源配置** json。REST 源到这就算装完了；
   * 但 spider 源的配置里只有一句 {@code web.spider: "agedm"} —— 内容是那份
   * js 跑出来的，配置本身不含任何解析逻辑。于是只装配置会出现：
   *
   * <pre>
   *   加载失败 / 找不到 spider: agedm
   * </pre>
   *
   * 用户视角完全无法理解：他明明"装上了"，源列表里也有这一条，界面上却什么都没有。
   * 而且这个错要等到首页请求才暴露，装的时候一声不吭。
   *
   * <p>── 反向也认：直接贴 .js ─────────────────────────────────────────
   *
   * 「用户手里只有一个地址时该贴哪个」这件事不该由他来决定。所以两条路都通：
   *
   * <ul>
   *   <li>贴 <b>json 配置</b> → 读 {@code web.spider}，去同目录拉 {@code <id>.js}</li>
   *   <li>贴 <b>js 源码</b>   → 先装源码，再去同目录找 {@code <id>.json} 当配置</li>
   * </ul>
   *
   * 同目录这个约定本来就是这套设计的形状（{@code /spiders/yhdm.json} 旁边就是
   * {@code yhdm.js}）。两边都找不到另一半时，至少把能装的那半装上，并明确说
   * 还差什么 —— 而不是留一句「设置失败」。
   *
   * @return {ok, spider, installedJs, error}
   */
  async function addOneSource(url) {
    const target = String(url || '').trim();
    const isJs = /\.js$/i.test(target.replace(/[?#].*$/, ''));
    // 同目录的兄弟地址：yhdm.json ↔ yhdm.js。
    // **只用于兼容老配置** —— 2026-10-19 之前发布的配置里 web.spider 写的是纯 id
    // （"yhdm" 而不是完整地址），只能靠「配置和源码放同一个目录」这个约定去猜。
    // 新写法请把源码地址直接写进 web.spider，理由见下面路 2 那段。
    const sibling = (name) => target.replace(/[^/]*$/, '') + name;

    // ── 路 1：贴的是 js 源码 ──────────────────────────────────────────
    //
    // 只装源码，不猜配置。理由：TVBox 的模型里「源码」和「站配置」本来就是
    // 两件事 —— 源码是可复用的解析器（苹果CMS 一个 js 覆盖几千个站），配置是
    // 某个具体站的参数（接口地址、分类、封面域）。贴一个 .js 就想变出一个能用的站，
    // 等于要求那个 js 自带站点参数，而按设计它恰恰不带。
    //
    // 所以这里装完就返回，明说下一步要用一份配置（配置里的 web.spider 会指回
    // 这份源码）。这比之前「去同目录猜 <id>.json」诚实 —— 猜得到是运气，
    // 猜不到是一句让人摸不着头脑的报错。
    if (isJs) {
      let meta = null;
      try {
        meta = await bridgeCall('spider', { op: 'install', url: target });
      } catch (error) {
        return { ok: false, error: '源码没装上：' + (error.message || error) };
      }
      const id = String((meta && meta.id) || '').trim();
      if (!id) return { ok: false, error: '源码装上了但读不出 id，这个源用不了' };
      return {
        ok: true,
        spider: id,
        installedJs: id,
        needsConfig: true,
        error: '',
      };
    }

    // ── 路 2：贴的是 json 配置（原来的路径）──────────────────────────
    const reply = await bridgeCall('source', { id: 'set?url=' + encodeURIComponent(target) });
    const result = typeof reply === 'string' ? JSON.parse(reply) : reply;
    if (!result || !result.ok) return { ok: false, error: (result && result.error) || '设置失败' };

    // 装完配置再问一次它是不是 spider 源。取配置本身（不是合并后的），
    // 因为 web.spider 就写在源文件里，不需要经过 SiteProfile。
    // 装完配置再问一次它是不是 spider 源。取配置本身（不是合并后的），
    // 因为 web.spider 就写在源文件里，不需要经过 SiteProfile。
    let spiderField = '';
    try {
      const fetched = await bridgeCall('source', { id: 'fetch?url=' + encodeURIComponent(target) });
      const body = typeof fetched === 'string' ? JSON.parse(fetched) : fetched;
      const cfg = body && body.ok ? JSON.parse(body.body || '{}') : null;
      spiderField = String((cfg && cfg.web && cfg.web.spider) || '').trim();
    } catch (error) {
      // 探不到就算了 —— 配置已经装好，REST 源本来就没有 web.spider。
    }
    if (!spiderField) return { ok: true, spider: '', installedJs: '' };

    // ── web.spider 的两种写法 ────────────────────────────────────────
    //
    //   ① "https://…/yhdm.js"  → 源码地址**自己写在配置里**（TVBox 就是这么做的：
    //      它的 sites[] 每一条都带自己的 spider 字段指向源码，不靠猜）
    //   ② "yhdm"               → 纯 id，退回去同目录找 <id>.js（兼容已发出去的配置）
    //
    // ① 更好：一个配置能指向任何地方的另一份源码，不必和配置放在一个目录里。
    // ② 留着只为一件事 —— 已经发出去的市场条目里写的是纯 id，用户手里那些还能用。
    // 新写的配置请用 ①。
    const jsUrl = /^https?:\/\//i.test(spiderField)
      ? spiderField
      : sibling(spiderField + '.js');
    let id = spiderField;
    try {
      const meta = await bridgeCall('spider', { op: 'install', url: jsUrl });
      id = String((meta && meta.id) || spiderField).trim();
      return { ok: true, spider: id, installedJs: id };
    } catch (error) {
      return {
        ok: false,
        spider: id,
        installedJs: '',
        error: '配置已装好，但源码 ' + jsUrl + ' 没拉下来：' + (error.message || error),
      };
    }
  }

  // 逐个装 — 每一条都会加进源列表并切换为当前，最后一条留下生效。
  // 只在全部处理完后重启一次，避免每个源各重启一轮。
  async function addSelected(urls) {
    if (!urls.length) return;
    const apply = document.getElementById('setup-market-apply');
    const original = apply.textContent;
    apply.disabled = true;
    const failed = [];
    for (let index = 0; index < urls.length; index += 1) {
      apply.textContent = `正在添加 ${index + 1}/${urls.length}…`;
      try {
        const one = await addOneSource(urls[index]);
        if (!one.ok) failed.push(one.error || '设置失败');
        // 源码装上了、配置还没跟上：这不算装好，跟 addSourceFromMarket 一个口径。
        else if (one.needsConfig) failed.push('这个地址是爬虫脚本，还需要一份对应的源配置');
      } catch (error) {
        failed.push(error.message || '设置失败');
      }
    }
    if (failed.length === urls.length) {
      showError('都没能加上：' + failed[0]);
      apply.disabled = false;
      apply.textContent = original;
      return;
    }
    if (failed.length) {
      // 部分成功也要把原因说出来 —— 尤其是「配置装了但源码没拉下来」，
      // 那种源点进去会是一片空白，用户不知道是网络还是源本身有问题。
      showError('有 ' + failed.length + ' 个没装上：' + failed[0]);
    }
    apply.textContent = failed.length ? `已添加 ${urls.length - failed.length} 个，正在重启…` : '已添加，正在重启…';
    if (typeof window.restartAfterSource === 'function') {
      window.restartAfterSource(() => { try { location.reload(); } catch (error) {} });
    } else {
      setTimeout(() => { try { location.reload(); } catch (error) {} }, 200);
    }
  }

  async function submit() {
    // 回车监听不看按钮的 disabled：点击提交进入「正在获取…」后再按回车，
    // 会并发跑第二条安装链路。入口先挡一道。
    if (button.disabled) return;
    const url = (input.value || '').trim();
    if (!url) { showError('请填写源地址'); return; }

    button.disabled = true;
    const original = button.textContent;
    button.textContent = '正在获取…';
    showError('');

    try {
      // 直接贴一个源地址也要走 addOneSource —— 它比单纯的 source set 多做一步：
      // 如果这份配置声明了 web.spider，把同目录的 <id>.js 也装进 SpiderStore。
      // 少了这一步，spider 源装完必报「找不到 spider: xxx」，而用户毫无办法。
      const one = await addOneSource(url);
      if (!one.ok) {
        // 粘进来的可能不是「一个源」，而是「一份源清单」（市场）。这种情况别直接
        // 报错 —— 拉回来看看是不是 {sources:[…]}，是就把里面的源列出来让用户挑。
        //
        // Java 那边认出来时 throw 的是固定串 MARKET_SOURCES（UserSource.MARKET_LIST）。
        // 那是「清单，不是源」的确定结论，不是失败；其余错误也照旧试一次市场，
        // 因为老版本的服务端没有这个标记，靠形状认是唯一的退路。
        const message = one.error || '';
        const marketMark = /MARKET_SOURCES/.test(message);
        // 「配置装了但源码没拉下来」不该被当成市场清单再试一遍 —— 那份配置已经
        // 落盘了，re-probe 只会拿到同一个错误。直接把它显示出来。
        if (/配置已装好，但源码/.test(message)) {
          showError(message);
          button.disabled = false;
          button.textContent = original;
          return;
        }
        const picked = await offerMarket(url, marketMark);
        if (picked) return;
        showError(marketMark
          ? '你贴的是一份源清单，但里面没有可用的源'
          : (message || '设置失败'));
        return;
      }
      // 贴的是 .js：源码装上了，但还没有任何配置指向它，重启只会回到同一屏。
      // 不重启，把下一步说清楚（配置里的 web.spider 会指回这份源码）。
      if (one.needsConfig) {
        showError('这个地址是爬虫脚本，还需要一份对应的源配置：源码已装好，再贴它的 .json 即可');
        return;
      }
      // 成功。这里**必须重建 Activity**，光 reload 页面是不够的 —— SiteProfile 是在
      // Activity.onCreate 里建的，只在启动时读一次配置，页面重载了 Activity 还在，
      // 拿到的还是上一份 profile。表现是：源填完了、首页却报一个跟自己填的地址毫无
      // 关系的错（「Expected URL scheme … but no scheme was found for /api/home」），
      // 用户只能自己把应用退掉重开。第一版就是 location.reload()，而这一屏恰好是
      // 「恢复出厂设置」之后必经的那一屏，所以这里跟换源那条路走同一个动作。
      //
      // 不 await：Java 那边先回包、200ms 后才 recreate，等它的话页面会在被销毁前挂起。
      // 留一个兜底 —— 2.5 秒后页面还在，说明重建没发生，退回重载页面。
      button.textContent = '已保存，正在重启…';
      if (typeof window.restartAfterSource === 'function') {
        window.restartAfterSource(() => { try { location.reload(); } catch (error) {} });
      } else {
        setTimeout(() => { try { location.reload(); } catch (error) {} }, 200);
      }
      return;
    } catch (error) {
      showError('设置失败：' + (error.message || error));
    } finally {
      if (!button.disabled) return;
      if (button.textContent !== '已保存，正在重启…') {
        button.disabled = false;
        button.textContent = original;
      }
    }
  }


  /**
   * 把**当前生效**的 web 块（域名之外的站点描述：paths / categories / fields / ui）
   * 写回 window.HG_SITE。
   *
   * 为什么需要：assets/site-web.js 是打包时从内置模板生成的，而用户换源之后
   * 这些值可能完全不同 —— 路径不一样、分类 id 不一样、字段名不一样。
   * 不重新拉一次，前端读的还是打包时那份，表现是「换了源但界面里的东西对不上」。
   *
   * 合并本身在 Java 侧做完了（SiteProfile 构造时 merge(内置, 用户配置)），
   * 这里只是把结果取回来盖上，不重复实现一遍。
   */
  async function applySiteConfig() {
    try {
      const reply = await bridgeCall('source', { id: 'web' });
      const result = typeof reply === 'string' ? JSON.parse(reply) : reply;
      if (!result || !result.ok || !result.web) return;
      Object.assign(window.HG_SITE = window.HG_SITE || {}, result.web);
      console.log('[setup] 已套用源里的 web 配置');
    } catch (error) {
      // 拿不到就继续用打包时那份。不阻断启动 —— 同一套 schema 换个域名的话，
      // 两份其实一模一样，这里失败是完全无害的。
      console.log('[setup] web 配置拉取失败，沿用内置：' + (error.message || error));
    }
  }

  /** 等一个可用的源。没配就弹设置页，配好了立刻 resolve。 */
  async function ensure() {
    if (prompting) return ready;
    prompting = true;

    const state = await current();
    if (state && state.ok && state.configured) {
      // 先把缓存作用域钉住，再放行。
      //
      // 顺序是硬要求：goHome() 是**同步**读 localStorage 的（冷启动第一帧就要有内容，
      // 不能等网络也不能等桥），所以作用域必须在 startHome() 之前就写好。
      // 写到 url 上——那是用户填的配置地址，换源就变，而且是唯一稳定的标识：
      // CMS 源的 origin 是空的，只有 url 能区分。
      window.HG_CACHE_SCOPE = state.url || state.origin || '';
      await applySiteConfig();
      prompting = false;
      return;                       // 已经有源，直接放行
    }
    if (state && !state.ok) {
      // 连读都读不了：仍然显示设置页，但把原因说清楚，
      // 别让用户对着一个沉默的输入框猜。
      showError(state.error);
    }
    show(true);
    return ready;                   // 等用户在页面上提交成功
  }

  if (window.visualViewport) {
    window.visualViewport.addEventListener('resize', syncKeyboardInset);
    window.visualViewport.addEventListener('scroll', syncKeyboardInset);
  }

  if (button) button.addEventListener('click', submit);
  if (input) {
    input.addEventListener('keydown', event => {
      if (event.key === 'Enter') { event.preventDefault(); submit(); }
    });
  }

  window.HGSetup = {
    ensure,
    submit,
    applySiteConfig,
    /**
     * 装一个源（含「spider 要装两样」那套推另一半的逻辑）。
     *
     * 挂出来是给 source-market.js 那个安装表单用的 —— 两个入口必须走同一条路，
     * 否则「贴 js」在一个地方能自动补配置、在另一个地方只装源码然后让用户自己
     * 再贴一次，两种行为对用户来说是随机出现的。
     */
    addOneSource,
    /** 手动重开设置页（「我的」里的换源入口用）。 */
    open() {
      showError('');
      input.value = '';
      show(true);
      current().then(state => {
        if (state && state.ok && state.url) {
          input.value = state.url;
          hint.textContent = '当前源：' + state.origin;
        }
      });
    },
    close() { show(false); resolveReady(); },
  };
})();
