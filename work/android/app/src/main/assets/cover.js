/*
 * AIIvideo —— 封面模块
 *
 * 从 app.js 抽出来的一整块封面职责：URL 归一 → 拉取解密 → 解码上屏 → 失败重试 → 主色提取。
 * 独立成文件是因为这四件事互相咬得很紧（都围着 <img data-cover> 转），
 * 而对外只依赖 api / content / scroll 三样。
 *
 * 用法（app.html 里 hot 顺序：本文件排在 app.js 之前）：
 *   const cover = window.HGCover.create({ api, content, scroll });
 *
 * ── 为什么封面要绕一圈 ─────────────────────────────────────────────────
 * 站点返回的封面地址指向的是**密文图**，不是能直接塞 <img src> 的 URL：
 *   Java 的 SiteRepository.coverData() 抓到图 → AES/CBC/NoPadding 解密
 *   → repairCoverHeader() 修复 JPEG 头（加密时把 'J' 0x4A 异或成了 'D' 0x44）
 *   → 以 data:image/jpeg;base64 回传。
 * 所以这里拿到的 `data` 是 data URL，不是 http 地址。直接把原始地址丢给
 * <img src> 会渲染出一片花屏噪声。
 */
(() => {
  'use strict';

  // 封面 CDN 从 site-web.js 读，不再硬编码在这儿。
  //
  // 这个值原来在代码里写死，同时 site-default.json 的 cover.allowedHosts 里也有一份
  // —— Java 的 SiteProfile.coverUrl() 会拿那份逐个比对 host，不在列表里直接拒收。
  // 两边必须一致，而只改一边的后果是：JS 拼出一个 Java 拒收的地址，封面静默全挂
  // （降级成占位字、不报错，很难第一时间发现）。build.sh 里有一道校验会比对这两个值。
  // 实时读，不在模块加载时捕获 —— 用户换源之后封面 CDN 可能也换了，
  // 而 setup.js 是在启动时把当前生效的 web 块写回 window.HG_SITE 的。
  function imgOrigin() {
    const configured = typeof window !== 'undefined' && window.HG_SITE && window.HG_SITE.coverOrigin;
    return configured || 'https://pic.drleao.cn';
  }

  // 相对路径补成绝对 https，并剥掉没有意义的 auth_key —— 封面走 Java 解密，
  // 鉴权由 Java 侧负责，留在 URL 上只会让同一张图算出不同的 key。
  function resolveUrl(raw) {
    const value = String(raw == null ? '' : raw).trim();
    if (!value) return '';
    let url = value;
    if (/^\/\//.test(url)) url = 'https:' + url;
    else if (!/^https?:\/\//i.test(url)) url = imgOrigin() + (url[0] === '/' ? url : '/' + url);
    try {
      const parsed = new URL(url);
      const names = [...parsed.searchParams.keys()];
      if (names.length && names.every(name => name === 'auth_key')) parsed.search = '';
      url = parsed.toString();
    } catch (error) {}
    return url;
  }

  // 从图上提两个主题色，给 hero 的首屏做同色渐变用。
  // 只做小图重采样（48×48），跳过过暗过亮的像素，按饱和度加权。
  function paletteFromImage(image) {
    const canvas = document.createElement('canvas'); canvas.width = canvas.height = 48;
    const context = canvas.getContext('2d', { willReadFrequently: true });
    context.drawImage(image, 0, 0, 48, 48);
    const pixels = context.getImageData(0, 0, 48, 48).data;
    let red = 0, green = 0, blue = 0, weightSum = 0;
    for (let index = 0; index < pixels.length; index += 16) {
      const r = pixels[index], g = pixels[index + 1], b = pixels[index + 2];
      const brightness = (r + g + b) / 3;
      if (brightness < 28 || brightness > 225) continue;
      const weight = 0.6 + (Math.max(r, g, b) - Math.min(r, g, b)) / 255;
      red += r * weight; green += g * weight; blue += b * weight; weightSum += weight;
    }
    if (!weightSum) throw new Error('未找到可用颜色');
    const r = red / weightSum / 255, g = green / weightSum / 255, b = blue / weightSum / 255;
    const max = Math.max(r, g, b), min = Math.min(r, g, b), difference = max - min;
    let hue = 28;
    if (difference) {
      hue = max === r ? ((g - b) / difference) % 6 : max === g ? (b - r) / difference + 2 : (r - g) / difference + 4;
      hue = (hue * 60 + 360) % 360;
    }
    const saturation = Math.round(Math.max(17, Math.min(32, difference / Math.max(max, .01) * 75)));
    return { light: `hsl(${Math.round(hue)} ${saturation}% 62%)`, dark: `hsl(${Math.round(hue)} ${Math.min(34, saturation + 2)}% 25%)` };
  }

  function create(deps) {
    const { api, content, scroll } = deps;

    // 同一张封面可能同时出现在 hero、卡片、榜单里，用路径当 key 去重，
    // 避免同一张图并发拉好几次。上限 80，超了丢最早的。
    const coverRequests = new Map();
    let observer = null;
    let coverScrollTimer;

    // ── 灰块上报（封面信任模型方案 §4.5，2026-10-31）──────────────────
    //
    // 按「页面灰块数」计数，不按「请求失败数」：Java 侧的负缓存会把失败合并、
    // Report.Once 会把上报挡掉 —— 那批根本没发请求的封面在前端这里全看得见
    // （占位字就是它们）。攒 15 秒合并成一条，防刷口径照 js-errors 的 MARKS：
    // 首批必报，之后每满 10 个灰块再报一条。
    const trackFn = typeof deps.track === 'function' ? deps.track : null;
    let placeholderBuf = {};
    let placeholderTimer = null;
    let placeholderReported = 0;

    function placeholderReason(message) {
      const text = String(message || '');
      if (/无法解码/.test(text)) return 'decode';
      if (/刚刚失败过|冻结/.test(text)) return 'frozen';
      if (/无效封面/.test(text)) return 'config';
      if (/解密|不是图片/.test(text)) return 'decrypt';
      return 'network';
    }

    function notePlaceholder(message) {
      if (!trackFn) return;
      const reason = placeholderReason(message);
      placeholderBuf[reason] = (placeholderBuf[reason] || 0) + 1;
      if (placeholderTimer) return;
      placeholderTimer = setTimeout(() => {
        placeholderTimer = null;
        const reasons = placeholderBuf;
        placeholderBuf = {};
        let count = 0;
        for (const key in reasons) count += reasons[key];
        const previous = placeholderReported;
        placeholderReported += count;
        // 首批（之前一个都没报过）必报；之后跨过 10 的倍数再报。
        if (previous === 0 || Math.floor(placeholderReported / 10) > Math.floor(previous / 10)) {
          try { trackFn('cover', 'cover_placeholder', { count: count, reasons: reasons }); } catch (ignored) {}
        }
      }, 15000);
    }

    function coverKey(url) {
      // host 要进 key：同族加密图床的二级域在源之间轮换（c5e396f 的背景），
      // 不同主机挂同一路径不是同一张图，只按 path 去重会把 A 源的字节灌给 B 源。
      try { const parsed = new URL(url); return parsed.host + parsed.pathname; }
      catch { return String(url).split('?')[0]; }
    }

    function get(url) {
      const key = coverKey(url);
      if (coverRequests.has(key)) return coverRequests.get(key);
      const request = api('cover', { url }).catch(error => { coverRequests.delete(key); throw error; });
      coverRequests.set(key, request);
      if (coverRequests.size > 80) coverRequests.delete(coverRequests.keys().next().value);
      return request;
    }

    function applyPalette(image) {
      const slide = image.closest('.hero-slide') || image.closest('.hero');
      if (!slide) return;
      const key = (image.dataset.cover || '').split('?')[0];
      let cache = {};
      try { cache = JSON.parse(localStorage.getItem('banner-palettes') || '{}'); } catch { }
      let colors = cache[key];
      if (!colors) {
        try { colors = paletteFromImage(image); cache[key] = colors; localStorage.setItem('banner-palettes', JSON.stringify(cache)); }
        catch { colors = { light: '#B5A08B', dark: '#49332F' }; }
      }
      slide.style.setProperty('--hero-tint-light', colors.light);
      slide.style.setProperty('--hero-tint-dark', colors.dark);
    }

    async function loadInto(image) {
      const url = image.dataset.cover;
      if (image.dataset.loading === '1') return;
      if (!url) {
        image.closest('.poster')?.classList.remove('loading');
        image.closest('.poster')?.classList.add('fallback');
        return;
      }
      image.dataset.loading = '1';
      const poster = image.closest('.poster');
      // 字节是 Java 解密返回的，还是兜底直连的原址。失败分档（见下面 catch）靠它区分。
      let fromJava = false;
      try {
        let data = '';
        try { data = await get(url); fromJava = true; }
        catch {
          await new Promise(resolve => setTimeout(resolve, 450));
          try { data = await get(url); fromJava = true; }
          catch { data = ''; }
        }
        // 取字节失败时，最后一条路是把原址直接交给 <img>。
        //
        // <img> 不是 XHR，加载图片不受 CORS 约束 —— MissAV 的封面域 fourhoi.com
        // 只有这条路能通：Java 侧 okhttp 取它会被拦，WebView 里 fetch 它必炸
        // TypeError: Failed to fetch（跨源没有 ACAO 头），但图片加载器照样能画出图。
        // 对密文源（封面是 AES 密文的那种）它只会解码失败回到占位字，
        // 和原来「抛出去走占位」的结果一样，所以这条兜底不会让老源变差。
        if (!data) {
          if (!/^https?:\/\//i.test(url)) throw new Error('封面解密失败');
          data = url;
        }
        if (!image.isConnected || image.dataset.cover !== url) return;
        await new Promise((resolve, reject) => {
          image.onload = () => { image.onload = image.onerror = null; resolve(); };
          image.onerror = () => { image.onload = image.onerror = null; reject(new Error('图片无法解码')); };
          image.src = data;
          if (image.complete && image.naturalWidth > 0) { image.onload = image.onerror = null; resolve(); }
        });
        if (!image.isConnected || image.dataset.cover !== url) return;
        poster?.classList.remove('loading', 'fallback');
        poster?.classList.add('has-image');
        delete image.dataset.retryAfter;
        if (image.classList.contains('hero-image')) applyPalette(image);
      } catch (error) {
        coverRequests.delete(coverKey(url));
        if (image.isConnected) {
          image.removeAttribute('src');
          poster?.classList.remove('loading');
          poster?.classList.add('fallback');
          // 冻结分两档：Java 给了字节、字节本身解不开（真坏数据）才值得冻 5 分钟；
          // 兜底直连炸掉的（fromJava=false）根因是网络 / Java 层那次失败 —— 对密文
          // 图床直连本来就必炸，按解码失败冻 5 分钟就成了「一次抖动整页灰五分钟」。
          // c5e396f 在 Java 侧把主机冻结分了档，这里是前端这层的同款口径。
          const decodeFromJava = error.message === '图片无法解码' && fromJava;
          image.dataset.retryAfter = String(Date.now() + (decodeFromJava ? 300000 : 30000));
          // 灰块落地了，记一笔（合并上报，见 notePlaceholder）。
          notePlaceholder(error.message);
        }
        const id = image.closest('[data-open-id]')?.dataset.openId || image.closest('.hero-slide,.category-spotlight')?.querySelector('[data-open-id]')?.dataset.openId || 'unknown';
        console.warn('封面加载失败', id, (() => { try { return new URL(url).host; } catch { return 'invalid'; } })(), error.message);
      } finally { delete image.dataset.loading; }
    }

    function retry(force = false) {
      const viewport = scroll.getBoundingClientRect();
      content.querySelectorAll('img[data-cover]').forEach(image => {
        if (image.naturalWidth > 0 || image.dataset.loading === '1') return;
        if (!force && Date.now() < Number(image.dataset.retryAfter || 0)) return;
        const card = image.closest('.poster');
        if (card && !card.classList.contains('fallback')) return;
        const box = image.getBoundingClientRect();
        if (box.bottom < viewport.top - 320 || box.top > viewport.bottom + 320) return;
        const row = image.closest('.film-row');
        if (row) {
          const visible = row.getBoundingClientRect();
          if (box.right < visible.left - 250 || box.left > visible.right + 250) return;
        }
        card?.classList.remove('fallback');
        card?.classList.add('loading');
        loadInto(image);
      });
    }

    // 视口外 320px 以内才建观察，避免把整页几十张图一次性拉起来。
    function observe(image) {
      if (!observer) return;
      observer.observe(image);
    }

    function hydrate(root = content) {
      if (observer) observer.disconnect();
      observer = new IntersectionObserver(entries => {
        entries.forEach(entry => {
          if (!entry.isIntersecting) return;
          const image = entry.target;
          observer.unobserve(image);
          loadInto(image);
        });
      }, { root: scroll, rootMargin: '320px' });
      // 首屏只立刻拉 6 张。
      //
      // 原来是 14 张。一屏 24 张卡、连续切几次分类，就是几十个 cover 请求同时在飞，
      // 每个回来都要 JSON.parse 一段几十 KB 的 base64 再塞进 img.src —— 那是主线程上
      // 一段一段的占用。单次点击看不出来（janky 1%），连点时累积成「卡」。
      // 剩下的交给观察器，滚到哪儿拉哪儿。
      root.querySelectorAll('img[data-cover]').forEach((image, index) => {
        if (image.naturalWidth > 0) return;
        if (index < 6 || image.classList.contains('hero-image')) loadInto(image);
        else observer.observe(image);
      });
    }

    // 列表场景：前 eager 张立刻加载，其余交给观察器。
    // （app.js 里原来直接摸 observer 变量，那是模块内部状态，不该外露。）
    function hydrateList(images, eager = 8) {
      images.forEach((image, index) => index < eager ? loadInto(image) : observe(image));
    }

    scroll.addEventListener('scroll', () => {
      clearTimeout(coverScrollTimer);
      coverScrollTimer = setTimeout(() => retry(), 160);
    }, { passive: true });
    window.addEventListener('online', () => retry(true));

    return {
      resolveUrl,
      get,
      loadInto,
      retry,
      hydrate,
      hydrateList,
      observe,
      applyPalette,
    };
  }

  window.HGCover = { create, resolveUrl };
})();
