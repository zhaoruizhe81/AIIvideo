#!/usr/bin/env node
/**
 * spider 离线回归：把 app/src/test/resources/spiders 里的源放进一个**没有 DOM** 的
 * 沙箱，喂本地 fixture，看它算出来的东西对不对。
 *
 * ## 为什么是这个形状
 *
 * 阶段 1 的验收是「spider 的输出和现有 Java 适配器逐字段一致」。要让这句话可执行，
 * 只有一条路：同一个 HTML，两边各跑一次，比数字。Java 那边是 JUnit（`YhdmAdapterTest`），
 * 这边就是本脚本 —— 喂的是同一批 fixture（`app/src/test/resources/yhdm/`）。
 *
 * 沙箱里**故意不给** `document` / `window` / `DOMParser`：spider 一旦用了 DOM API，
 * 这脚本就跑不了，而那意味着这个源从此没有离线回归，只能靠真机点。约束用沙箱
 * 强制，不靠约定。
 *
 * ## 八家之后：按源分派
 *
 * 以前 ROUTES / CHECKS 是写死给 yhdm 的。现在有八份源（yhdm / hanime1 / porn91 /
 * mrds66 / agedm / cms / missav / openlist），回归配置改成按 `__spiderSite.id`
 * 查表（见下面的 `PROFILES`）。三条规矩：
 *
 *   ① **yhdm 那份原样不动。** 它是唯一已经跑绿过的基线，改它等于把已有回归拆了。
 *      断言一条没少，文案一个字没改。
 *   ② **没有配置不算失败。** 源还在别人手里写着，脚本不认识它是正常状态 ——
 *      打印「这个源还没有离线回归配置」，退出码 0。只有「配置了断言但没过」才是 1。
 *   ③ **未实现的能力不算失败。** 源可能只写了一半（比如 porn91 的 play 还没接），
 *      打印「未实现」继续往下跑，别把一张半成品表变成一堆红。
 *
 * ## 用法
 *
 *   node scripts/spider-check.js app/src/test/resources/spiders/yhdm.js
 *   node scripts/spider-check.js app/src/test/resources/spiders/yhdm.js --dump home   # 看原始输出
 *   node scripts/spider-check.js app/src/test/resources/spiders/porn91.js --list      # 只列配置，不跑
 *
 * 当作模块用（`spider-all-check.js` 就是这么复用的）：
 *
 *   const { runSpider, listSpiders, describeProfile } = require('./spider-check.js');
 *
 * 退出码：0 全过（含「未配置」「未实现」）/ 1 有断言失败 / 2 环境问题（文件不存在、spider 抛错）
 */
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const HERE = __dirname;
const APP_ROOT = path.resolve(HERE, '..');
const FIXTURES = path.join(APP_ROOT, 'app/src/test/resources');

/**
 * 每个源的离线回归配置。
 *
 * 两种路由写法：
 *   `routes`     —— regex 数组，命中哪条去哪条夹具。给路径形状稳定、但夹具要按
 *                   **不同参数**分文件的站点用（yhdm 的 /vod/{id}、mrds66 的
 *                   /category/{slug}/N/）。命中顺序就是数组顺序，别乱排。
 *   `fixtures`   —— { '夹具文件名': /url/ }，夹具名就是 `app/src/test/resources/<dir>/`
 *                   下的文件名。给「一个页面一个 URL」的站点用，读起来更像 JUnit 里
 *                   那句 `fixture("home.html")`，对照着改不会错位。
 *
 * **断言里的每个数字都抄自对应的 JUnit**，注释里标了出处和原文。不要凭感觉写 ——
 * 这里编一个数，等 Java 适配器删掉之后就没有第二个人能反驳它。
 */
const PROFILES = {
  // ── yarn 1：yhdm（基线，一字不改）──────────────────────────────────────
  yhdm: {
    dir: 'yhdm',
    routes: [
      { match: /\/_get_plays\//, file: 'plays.json' },
      { match: /\/vod\/\d+/, file: 'detail-2026476948.html' },
      { match: /\/list\/|\/show\//, file: 'list.html' },
      { match: /yhdm\.one\/?(?:$|\?|#)/, file: 'home.html' },
    ],
    // 数字来自 YhdmAdapterTest 的既有断言。
    checks: {
      home: [
        ['卡片数 ≥ 40', r => r.items.length >= 40],
        ['id 是数字', r => /^\d+$/.test(String(r.items[0].id))],
        ['标题非空', r => String(r.items[0].title || '').length > 0],
        ['封面是绝对地址', r => /^https?:\/\//.test(String(r.items[0].cover || ''))],
      ],
      detail: [
        ['标题对得上', r => String(r.title || '').indexOf('仙逆剧场版') >= 0],
        ['简介非空', r => String(r.description || '').length > 0],
        // 两条线路（sid=1 三集、sid=2 四集）→ 取集数多的那条，所以是 4 条。
        ['剧集 3 条', r => (r.episodes || []).length === 3],
        ['第一集是 ep1', r => String((r.episodes || [])[0] && r.episodes[0].ref) === 'ep1'],
        ['第一集标题「第01集」', r => String((r.episodes || [])[0] && r.episodes[0].title) === '第01集'],
        ['封面落在 /cover/2026476948', r => /\/cover\/2026476948/.test(String(r.cover || ''))],
      ],
      play: [
        ['地址是 m3u8', r => /\.m3u8/.test(String(r.url || ''))],
        ['至少有 1 条备选', r => (r.alts || []).length >= 1],
      ],
    },
    // 三条能力各自的入参。故意写死在配置里，不搞「猜一个 ref 出来」。
    args: {
      home: {},
      detail: { ref: '2026476948' },
      play: { ref: '2026476948', line: 'lz' },
    },
  },

  // ── yarn 2：porn91（91porn，901.26702.app）──────────────────────────────
  porn91: {
    dir: 'porn91',
    fixtures: {
      // 列表校正现在打的是 relay 上的 /p91/reconcile（见 porn91.js 里那段
      // 「为什么校正不在本文件里做」）。夹具返回一份真形状的单条结果。
      'reconcile.json': /\/p91\/reconcile/,
      'home.html': /\/v\.php(?:$|\?)/,
      'list-hd.html': /cate_id=hd|cate_id=original|category=|viewtype=basic/,
      'list-ori.html': /cate_id=ori/,
      'search.html': /search_result\.php/,
      'watch-51dc03e8e27a694f2fc7.html': /viewkey=51dc03e8e27a694f2fc7/,
    },
    checks: {
      // Porn91AdapterTest#listDedupesTo24Cards：
      //   assertEquals("分类页去重后应正好 24 条（站点每页 24）", 24, cards.length())
      // Porn91AdapterTest#homeFixtureHasCards：
      //   assertEquals("home.html（/v.php）应当正好 24 条", 24, cards.length())
      home: [
        ['首页 24 条（JUnit homeFixtureHasCards）', r => (r.items || []).length === 24],
        ['id 非空', r => String((r.items || [])[0] && r.items[0].id || '').length > 0],
        ['标题非空', r => String((r.items || [])[0] && r.items[0].title || '').length > 0],
        ['封面是 https（JUnit everyCardHasTitleAndCover）',
          r => String((r.items || [])[0] && r.items[0].cover || '').startsWith('https://')],
        ['id 无重复', r => {
          const ids = (r.items || []).map(one => String(one.id));
          return ids.length === new Set(ids).size;
        }],
      ],
      // 分类页走 list-ori.html；分类参数按 Java 的 cate_id=ori。
      category: [
        ['分类页 24 条（JUnit listDedupesTo24Cards）', r => (r.items || []).length === 24],
        ['两种 viewkey 形态都在（JUnit listDedupesTo24Cards）', r => {
          const ids = (r.items || []).map(one => String(one.id));
          const numeric = ids.some(id => /^\d+$/.test(id));
          const hex = ids.some(id => /^[0-9a-f]{20}$/.test(id));
          return numeric && hex;
        }],
        ['时长角标 ≥ 20 条（JUnit cardsCarryDurationBadge）', r => {
          const withBadge = (r.items || []).filter(one => /^\d{2}:\d{2}:\d{2}$/.test(String(one.badge || '')));
          return withBadge.length >= 20;
        }],
      ],
      search: [
        ['搜索页有卡片', r => (r.items || []).length > 0],
      ],
      // 标签清单（坑 ⑦）：站点没有话题标签，导航栏那排分类链接就是标签栏。
      // 夹具抓的是英文站（导航文字 91 / Hot / Top this Mon.），而源里名字优先取
      // 站点原文，所以这里断言的是 **code、条数、形状**，不是某几个中文字 ——
      // 英文/中文站都该过，钉死「91原创」等于把夹具的语言当成了契约。
      tags: [
        ['至少 8 个标签', r => (r.items || []).length >= 8],
        ['slug 非空', r => (r.items || []).every(one => String(one.slug || '').length > 0)],
        ['slug 不重复', r => {
          const slugs = (r.items || []).map(one => String(one.slug));
          return slugs.length === new Set(slugs).size;
        }],
        ['十个分类码都在', r => {
          const slugs = new Set((r.items || []).map(one => String(one.slug)));
          return ['ori', 'hot', 'top', 'hd', 'rf', 'tf', 'long', 'longer', 'md', 'mf']
            .every(code => slugs.has(code));
        }],
        ['名字非空、没有 HTML 残留', r => (r.items || []).every(one => {
          const name = String(one.name || '');
          return name.length > 0 && name.indexOf('<') < 0 && name.indexOf('&') < 0;
        })],
        ['组是 category', r => (r.items || []).every(one => String(one.group) === 'category')],
      ],
      // 列表卡校正（坑 ⑧）：只有这一条 id 有详情夹具，其余 id 在离线里必然失败，
      // 所以断言的是「单条成功就带回、失败不影响别人」这条形状，不是覆盖率。
      reconcile: [
        ['给一个 id 就回一条', r => (r.items || []).length === 1],
        ['id 原样带回', r => String((r.items || [])[0] && r.items[0].id) === '51dc03e8e27a694f2fc7'],
        ['标题非空', r => String((r.items || [])[0] && r.items[0].title || '').length > 0],
        ['封面是 https', r => String((r.items || [])[0] && r.items[0].cover || '').startsWith('https://')],
      ],
      // 标签的详情页 = 分类页，slug 就是 category code。
      tagList: [
        ['标签页有卡片', r => (r.items || []).length > 0],
        ['卡片 id 非空', r => String((r.items || [])[0] && r.items[0].id || '').length > 0],
      ],
      // Porn91AdapterTest#playSourceIsNotTheDecoy：
      //   诱饵 358999.mp4 / ccm.91p52.com 必须被排除；签名三件套 st= e= f= 必须都在。
      detail: [
        ['标题非空（JUnit firstH4IsTheTitle）', r => String(r.title || '').length > 0],
        ['标题不是 Video Information（JUnit firstH4IsTheTitle）',
          r => String(r.title || '').indexOf('Video Information') < 0],
        ['标题不是 Comment On This Video（JUnit firstH4IsTheTitle）',
          r => String(r.title || '').indexOf('Comment On This Video') < 0],
        ['封面是 https（JUnit everyCardHasTitleAndCover）',
          r => String(r.cover || '').startsWith('https://')],
        ['简介非空（JUnit descriptionAndUploaderParse）', r => String(r.description || '').length > 0],
      ],
      play: [
        ['必须是 https 直链（JUnit playSourceIsNotTheDecoy）',
          r => String(r.url || '').startsWith('https://')],
        ['不是诱饵 358999.mp4（JUnit playSourceIsNotTheDecoy）',
          r => String(r.url || '').indexOf('358999') < 0],
        ['不是诱饵域名 ccm.91p52.com（JUnit playSourceIsNotTheDecoy）',
          r => String(r.url || '').indexOf('ccm.91p52.com') < 0],
        ['是 mp4 直链（JUnit playSourceIsNotTheDecoy）', r => String(r.url || '').indexOf('.mp4') >= 0],
        ['带 st= 签名（JUnit playSourceIsNotTheDecoy）', r => String(r.url || '').indexOf('st=') >= 0],
        ['带 e= 过期戳（JUnit playSourceIsNotTheDecoy）', r => String(r.url || '').indexOf('e=') >= 0],
        ['带 f= 指纹段（JUnit playSourceIsNotTheDecoy）', r => String(r.url || '').indexOf('f=') >= 0],
      ],
    },
    args: {
      home: {},
      category: { ref: 'ori', page: 1 },
      search: { ref: '东京', page: 1 },
      detail: { ref: '51dc03e8e27a694f2fc7' },
      play: { ref: '51dc03e8e27a694f2fc7' },
      // 前端传的是 slug（goTag 的第二跳），不是 ref。这里两种都写一遍没意义，
      // 就按前端真实形状来 —— 离线回归的意义是复现 App 那条路。
      tags: {},
      tagList: { slug: 'ori', page: 1 },
      reconcile: { ids: ['51dc03e8e27a694f2fc7'] },
    },
  },

  // ── 黄果短剧（huangguoai.com，苹果CMS 式私有 JSON 接口）──────────────
  //
  // 接口全是 JSON，夹具就是原始响应 —— 这和别家的 HTML 夹具不一样：
  // 改断言时可以直接看响应里的字段，不用在标签里找。
  huangguo: {
    dir: 'huangguo',
    fixtures: {
      'home.json': /\/api\/home/,
      'tags.json': /\/api\/tags/,
      'search.json': /\/api\/search/,
      // play 要排在 detail 前面：`/api/videos/7628/play` 也匹配 detail 那条前缀。
      'play.json': /\/api\/videos\/\d+\/play/,
      'list.json': /\/api\/videos\?/,
      'detail.json': /\/api\/videos\/\d+$/,
    },
    checks: {
      // 首页五块摊平后 ≥ 20 条（2026-10-19 实测 featured 4 + latest 8 + cate_rec 4×N）。
      home: [
        ['卡片 ≥ 15', r => (r.items || []).length >= 15],
        ['id 非空', r => String((r.items || [])[0] && r.items[0].id || '').length > 0],
        ['标题非空', r => String((r.items || [])[0] && r.items[0].title || '').length > 0],
        ['封面是 https', r => String((r.items || [])[0] && r.items[0].cover || '').startsWith('https://')],
        ['id 无重复', r => {
          const ids = (r.items || []).map(one => String(one.id));
          return ids.length === new Set(ids).size;
        }],
      ],
      // cate_id=24（AI成人短剧），一页 20 条，角标是「N集」。
      category: [
        ['一页 20 条', r => (r.items || []).length === 20],
        ['角标是 N集', r => /^\d+集$/.test(String((r.items || [])[0] && r.items[0].badge || ''))],
        ['有下一页', r => r.hasMore === true],
      ],
      search: [
        ['搜索有结果', r => (r.items || []).length > 0],
        ['第一页有下一页', r => r.hasMore === true],
      ],
      detail: [
        ['标题是「时停手表」（夹具里那条）', r => String(r.title || '') === '时停手表'],
        ['6 集', r => (r.episodes || []).length === 6],
        ['集 ref 是集号', r => String((r.episodes || [])[0] && r.episodes[0].ref) === '1'],
        ['简介非空', r => String(r.description || '').length > 0],
        ['标签是数组', r => Array.isArray(r.tags) && r.tags.length > 0],
      ],
      play: [
        ['是 m3u8 直链', r => /\.m3u8/.test(String(r.url || ''))],
        ['带 auth_key 签名', r => String(r.url || '').indexOf('auth_key=') >= 0],
        ['至少一条备选', r => (r.alts || []).length >= 1],
      ],
      tags: [
        ['156 个标签', r => (r.items || []).length === 156],
        // 配置里声明了 10 个分区，但「吃瓜黑料」那组现在是空的（站点没给标签），
        // 实际有内容的组是 9 个。空组不渲染，所以这里断言 9。
        ['9 个有内容的分区', r => new Set((r.items || []).map(t => String(t.group))).size === 9],
        ['分区都在配置声明的组里', r => (r.items || []).every(t => {
          const g = String(t.group);
          return ['djtc', 'jiakong', 'nanpin', 'nvpin', 'djll', 'gyk',
            'appearance', 'play', 'changjing', 'chigua-heiliao'].indexOf(g) >= 0;
        })],
      ],
      tagList: [
        ['按标签搜到内容', r => (r.items || []).length > 0],
      ],
    },
    args: {
      home: {},
      category: { slug: '24', page: 1 },
      search: { keyword: '都市', page: 1 },
      detail: { ref: '7628' },
      play: { ref: '7628:1' },
      tags: {},
      tagList: { slug: '都市', page: 1 },
    },
  },

  // ── 西瓜短剧（uxbu.j4g3wltg.cc，JSON 接口，与黄果同一套后端）───────────
  //
  // 夹具全是原始响应，看见的字段就是断言里的字段。三条只有这家才有的坑：
  //   ① `/api/videos?tag=` 被静默忽略（返回全站），tagList 只能走 SSR 标签页；
  //   ② 播放有第二条 H.265 线路（video_url_hevc），但不是每集都有；
  //   ③ 首页接口的 cover 是**相对路径**，列表接口给绝对地址 —— 两条都要能补全。
  xiguaduanju: {
    dir: 'xiguaduanju',
    fixtures: {
      // play 必须排在 detail 前面：`/api/videos/5033/play?ep=1` 也匹配 detail 那条前缀。
      'play-5033-ep1.json': /\/api\/videos\/5033\/play\?ep=1$/,
      'play-5033-ep6.json': /\/api\/videos\/5033\/play\?ep=6$/,
      'play-5033-ep7.json': /\/api\/videos\/5033\/play\?ep=7$/,
      'detail-1003.json': /\/api\/videos\/1003$/,
      'detail-5033.json': /\/api\/videos\/5033$/,
      // page=2 要排在 page=1 前面，否则 `category/ai-duanju` 那条会先命中。
      'category-ai-duanju-p2.json': /\/api\/videos\/category\/ai-duanju\?page=2/,
      'category-ai-duanju.json': /\/api\/videos\/category\/ai-duanju/,
      'category-ai-manju.json': /\/api\/videos\/category\/ai-manju/,
      // cate_id 分支（slug 写数字时走这条）。args 里用的是 slug，所以这条没有断言，
      // 留着是为了 --dump 和手改 args 时能直接跑通，别让人以为夹具缺了。
      'category-cate24.json': /\/api\/videos\?cate_id=24/,
      'search.json': /\/api\/search\?/,
      'tags.json': /\/api\/tags/,
      'home.json': /\/api\/home/,
      'list.json': /\/api\/videos\?page=1&size=24/,
      'tag-houru-p2.html': /\/tag\/houru\/page\/2\//,
      'tag-houru.html': /\/tag\/houru\//,
    },
    checks: {
      // 夹具实测：featured 8 + latest 8 + cate_rec 4×3 + 全站最新 24，去重后 35。
      home: [
        ['卡片 ≥ 30（合并去重后 35）', r => (r.items || []).length >= 30],
        ['首条是 featured 的第一条（1003）', r => String((r.items || [])[0] && r.items[0].id) === '1003'],
        ['id 无重复', r => {
          const ids = (r.items || []).map(one => String(one.id));
          return ids.length === new Set(ids).size;
        }],
        // 首页接口给的是 `/upload_01/…` 相对路径，漏了 PIC 前缀这里就是一片灰。
        ['封面全是 https 绝对地址', r => (r.items || []).every(one => String(one.cover || '').startsWith('https://'))],
        ['至少 20 张卡带「N集」角标', r => (r.items || [])
          .filter(one => /^\d+集$/.test(String(one.badge || ''))).length >= 20],
        ['不谎报还有下一页', r => r.hasMore === false],
      ],
      // 夹具：/api/videos/category/ai-duanju?page=1&size=20，20 条 / 24 页 / 共 465。
      category: [
        ['一页 20 条', r => (r.items || []).length === 20],
        ['角标是 N集', r => /^\d+集$/.test(String((r.items || [])[0] && r.items[0].badge || ''))],
        ['有下一页（24 页里的第 1 页）', r => r.hasMore === true],
        ['id 全是数字', r => (r.items || []).every(one => /^\d+$/.test(String(one.id)))],
      ],
      // 夹具：/api/search?q=妈&page=1&size=20 → 20 条 / 2 页 / 共 26。
      search: [
        ['搜索有结果', r => (r.items || []).length > 0],
        ['第一页有下一页', r => r.hasMore === true],
        ['卡片标题非空', r => String((r.items || [])[0] && r.items[0].title || '').length > 0],
      ],
      // 夹具：/api/videos/5033，6 集，两条线路。
      detail: [
        ['标题对得上', r => String(r.title || '') === '妈妈和姐姐用了我的系统都说好'],
        ['6 集', r => (r.episodes || []).length === 6],
        ['第一集 ref 是 ep1', r => String((r.episodes || [])[0] && r.episodes[0].ref) === 'ep1'],
        // episode_count=6、total_episodes=0；计划集数没填时以已上架为准。
        ['episodeCount 是 6', r => Number(r.episodeCount) === 6],
        ['封面在 pic.drleao.cn', r => /^https:\/\/pic\.drleao\.cn\//.test(String(r.cover || ''))],
        ['两条线路（H.264 / H.265）', r => (r.lines || []).length === 2],
        ['标签非空', r => Array.isArray(r.tags) && r.tags.length > 0],
        ['简介非空', r => String(r.description || '').length > 0],
      ],
      // 夹具：/api/videos/5033/play?ep=1，两条地址都在。
      play: [
        ['是 m3u8 直链', r => /\.m3u8/.test(String(r.url || ''))],
        ['带 auth_key 签名', r => String(r.url || '').indexOf('auth_key=') >= 0],
        ['url 落在 alts 里（切线路不换源）', r => (r.alts || []).some(one => one.url === r.url)],
        ['这一集有 H.265，共 2 条线路', r => (r.alts || []).length === 2],
        ['episodeNumber 是 1', r => Number(r.episodeNumber) === 1],
      ],
      // 夹具：/api/tags → 4 个分区，其中 ai短剧 是空的，实际 45 个标签。
      tags: [
        ['45 个标签', r => (r.items || []).length === 45],
        ['3 个有内容的分区', r => new Set((r.items || []).map(t => String(t.group))).size === 3],
        ['分区都在配置声明的组里', r => (r.items || []).every(t => {
          const g = String(t.group);
          return ['rmtc', 'rsph', 'rqjq'].indexOf(g) >= 0;
        })],
        ['slug 非空', r => (r.items || []).every(t => String(t.slug || '').length > 0)],
      ],
      // 夹具：/tag/houru/（SSR，共 151 条 / 每页 20）。
      tagList: [
        ['一页 20 条', r => (r.items || []).length === 20],
        ['有下一页（151 条 / 20 一页）', r => r.hasMore === true],
        ['卡片 id 非空', r => String((r.items || [])[0] && r.items[0].id || '').length > 0],
        ['封面是 https', r => String((r.items || [])[0] && r.items[0].cover || '').startsWith('https://')],
        // 这里最容易错的地方：卡片封面在 data-src，src 是占位图。
        ['封面不是占位图', r => String((r.items || [])[0] && r.items[0].cover || '').indexOf('cover-placeholder') < 0],
      ],
    },
    args: {
      home: {},
      category: { slug: 'ai-duanju', page: 1 },
      search: { keyword: '妈', page: 1 },
      detail: { ref: '5033' },
      play: { ref: '5033:ep1', ep: 1 },
      tags: {},
      tagList: { slug: 'houru', page: 1 },
    },
  },

  // ── yarn 3：mrds66（每日大赛，www.mrds66.com）──────────────────────────
  mrds66: {
    dir: 'mrds66',
    routes: [
      { match: /\/archives\/(\d+)/, file: 'archives-$1.html' },
      { match: /\/tag\//, file: 'tag-chigua.html' },
      { match: /\/search\//, file: 'search-chigua.html' },
      { match: /\/category\/mrds\/2\//, file: 'category-mrds-2.html' },
      // 第 1 页不带数字（站点对 /category/mrds/1/ 回 301），所以最后一条兜住。
      { match: /\/category\/mrds\/?(?:$|\?)/, file: 'category-mrds.html' },
      { match: /mrds66\.com\/?(?:$|\?|#)/, file: 'home.html' },
    ],
    checks: {
      // Mrds66AdapterTest#首页抠得出卡片：assertTrue("实际 " + cards.length(), cards.length() >= 25)
      home: [
        ['卡片 ≥ 25（JUnit 首页抠得出卡片）', r => (r.items || []).length >= 25],
        ['id 是数字（JUnit 广告位没有混进来）',
          r => /^\d+$/.test(String((r.items || [])[0] && r.items[0].id || ''))],
        ['标题非空（JUnit 首页首条字段齐全）',
          r => String((r.items || [])[0] && r.items[0].title || '').length > 0],
        ['封面在 pic.jtzatly.cn（JUnit 首页首条字段齐全）',
          r => String((r.items || [])[0] && r.items[0].cover || '').startsWith('https://pic.jtzatly.cn/')],
        ['badge 是 yyyy-MM-dd（JUnit 首页首条字段齐全）',
          r => /^\d{4}-\d{2}-\d{2}$/.test(String((r.items || [])[0] && r.items[0].badge || ''))],
        ['标题不带热搜角标（JUnit 标题不带热搜角标）',
          r => (r.items || []).every(one => String(one.title || '').indexOf('HOT') < 0)],
        ['封面没有双斜杠（JUnit 封面没有双斜杠）', r => (r.items || []).every(one => {
          const cover = String(one.cover || '');
          if (!cover) return true;
          return cover.indexOf('//', 'https://'.length) < 0;
        })],
      ],
      // Mrds66AdapterTest#分类页有卡片：assertTrue("实际 " + cards.length(), cards.length() >= 20)
      category: [
        ['分类页 ≥ 20（JUnit 分类页有卡片）', r => (r.items || []).length >= 20],
        ['标题非空（JUnit 没有标题的时候整条丢掉）',
          r => (r.items || []).every(one => String(one.title || '').length > 0)],
        ['id 是数字（JUnit 广告位没有混进来）',
          r => (r.items || []).every(one => /^\d+$/.test(String(one.id || '')))],
      ],
      // Mrds66AdapterTest#搜索页有卡片：assertTrue("实际 " + cards.length(), cards.length() >= 20)
      search: [
        ['搜索页 ≥ 20（JUnit 搜索页有卡片）', r => (r.items || []).length >= 20],
      ],
      // Mrds66AdapterTest#详情的标题和封面 / 简介不含广告友链 / 播放地址是m3u8且带签名
      detail: [
        ['标题非空（JUnit 详情的标题和封面）', r => String(r.title || '').length > 0],
        ['封面在 pic.jtzatly.cn（JUnit 详情的标题和封面）',
          r => String(r.cover || '').indexOf('pic.jtzatly.cn') >= 0],
        ['简介非空（JUnit 简介不含广告友链）', r => String(r.description || '').length > 0],
        ['简介不含「春药高潮」（JUnit 简介不含广告友链）',
          r => String(r.description || '').indexOf('春药高潮') < 0],
        ['简介不含「棋牌电子」（JUnit 简介不含广告友链）',
          r => String(r.description || '').indexOf('棋牌电子') < 0],
        ['简介 ≤ 320 字（JUnit 简介不含广告友链）', r => String(r.description || '').length <= 320],
        ['分类非空（JUnit 详情能读出分类和标签）', r => String(r.category || '').length > 0],
        ['标签非空且已解码（JUnit 详情能读出分类和标签）', r => {
          const tags = r.tags || [];
          if (tags.length === 0) return false;
          return tags.every(tag => String(tag).indexOf('%') < 0);
        }],
      ],
      // Mrds66AdapterTest#播放地址是m3u8且带签名
      play: [
        ['是 m3u8（JUnit 播放地址是m3u8且带签名）', r => String(r.url || '').indexOf('.m3u8') >= 0],
        ['带 auth_key=（JUnit 播放地址是m3u8且带签名）', r => String(r.url || '').indexOf('auth_key=') >= 0],
        ['auth_key 没被改坏（JUnit 播放地址是m3u8且带签名）',
          r => String(r.url || '').indexOf('auth_key= ') < 0],
      ],
    },
    args: {
      home: {},
      category: { ref: 'mrds', page: 1 },
      search: { ref: '吃瓜', page: 1 },
      detail: { ref: '196475' },
      play: { ref: '196475' },
    },
  },

  // ── yarn 4：missav（missav.live）────────────────────────────────────────
  //
  // 这个源在 Cloudflare 盾后面。spider 版遇到挑战页会明确报「需要过盾」，
  // 所以 home/category 有可能整条抛错 —— 那不是解析写错，是运行时限制。
  // 备注列会把它显示成「抛错: …」，看的人别把它当回归失败。
  missav: {
    dir: 'missav',
    fixtures: {
      'missav-home-new.html': /missav\.live\/(?:dm\d+\/)?cn\/new|\/cn\/?(?:$|\?)/,
      'missav-genres-p1.html': /\/cn\/genres/,
      'missav-detail-fct210.html': /\/cn\/fct-210|\/cn\/[a-z0-9_-]+-\d+/,
    },
    checks: {
      // MissavAdapterTest#homeParsesTwelveCards：assertEquals("首页卡片数不对 —— 真实页面是 12 张", 12, items.length())
      home: [
        ['首页正好 12 张（JUnit homeParsesTwelveCards）', r => (r.items || []).length === 12],
        ['id 不含斜杠（JUnit cardIdIsBareSlug）', r => (r.items || []).every(one => String(one.id || '').indexOf('/') < 0)],
        ['id 不带 dm 前缀（JUnit cardIdIsBareSlug）', r => (r.items || []).every(one => String(one.id || '').indexOf('dm') !== 0)],
        ['id 形态 [a-z0-9_-]+（JUnit cardIdIsBareSlug）',
          r => (r.items || []).every(one => /^[a-z0-9_-]+$/.test(String(one.id || '')))],
        ['封面在 fourhoi.com（JUnit homeParsesTwelveCards）',
          r => (r.items || []).every(one => String(one.cover || '').indexOf('fourhoi.com') >= 0)],
        ['至少 10 条有时长（JUnit durationShape）', r => {
          const withDuration = (r.items || []).filter(one => String(one.duration || '').length > 0);
          return withDuration.length >= 10;
        }],
      ],
      // MissavAdapterTest#genresPage：类型页第一页 36 个，解析出 35 个（去重后）。
      // 断言写的是 cards.length() >= 30，第一个分类是「高清」/ dm96 / 290816 条。
      category: [
        ['类型条目 ≥ 30（JUnit genresPage）', r => (r.items || []).length >= 30],
        ['第一个分类是「高清」（JUnit genresPage）',
          r => String((r.items || [])[0] && r.items[0].name || '') === '高清'],
        ['分类 id 是 dm96（JUnit genresPage）',
          r => String((r.items || [])[0] && r.items[0].id || '') === 'dm96'],
        ['分类 id 都是 dm+数字（JUnit categoryIdsWellFormedAndUnique）',
          r => (r.items || []).every(one => /^dm\d+$/.test(String(one.id || '')))],
        ['分类 id 无重复（JUnit categoryIdsWellFormedAndUnique）', r => {
          const ids = (r.items || []).map(one => String(one.id));
          return ids.length === new Set(ids).size;
        }],
      ],
      // MissavAdapterTest#detailMetadata / #playUrlMatchesBrowserNetworkPanel
      detail: [
        ['id 是 fct-210（JUnit detailMetadata）', r => String(r.id || '') === 'fct-210'],
        ['标题非空（JUnit detailMetadata）', r => String(r.title || '').length > 0],
        ['封面在 fourhoi.com/fct-210/（JUnit detailMetadata）',
          r => String(r.cover || '').startsWith('https://fourhoi.com/fct-210/')],
      ],
      play: [
        ['播放地址是那条 surrit m3u8（JUnit playUrlMatchesBrowserNetworkPanel）',
          r => String(r.url || '') === 'https://surrit.com/18b4bbc2-c802-44a7-ab7a-78531695c531/playlist.m3u8'],
      ],
    },
    args: {
      home: {},
      category: { ref: 'genres/dm96/高清', page: 1 },
      search: { ref: 'test', page: 1 },
      detail: { ref: 'fct-210' },
      play: { ref: 'fct-210' },
    },
  },

  // ── yarn 5：hanime1（hanime1.me）─────────────────────────────────────────
  //
  // ⚠ 夹具不在仓库里。Hanime1AdapterTest 用的是**根目录**的 home.html /
  // genre-1.html / query-1.html / watch-408706.html / tagpanel.html /
  // empty-page.html，而不是 hanime1/ 子目录 —— 任务描述里说的 hanime1/ 目录
  // 根本不存在，别去新建一个空的假装有。真抓下来之后把 dir 改成对应目录即可。
  hanime1: {
    dir: '.',
    fixtures: {
      'home.html': /hanime1\.me\/?(?:$|\?|#)/,
      'genre-1.html': /\/search\?(?:[^#]*&)?(?:genre|sort)=/,
      'query-1.html': /\/search\?(?:[^#]*&)?query=/,
      'watch-408706.html': /\/watch\?v=408706/,
      'tagpanel.html': /\/tag/,
      'empty-page.html': /page=99/,
    },
    checks: {
      // ⚠ 这里钉的是 48，不是 Hanime1AdapterTest 里那个 100。
      //
      // 两处测的不是同一层：
      //   parseCards(html)  → 144 张，JUnit 断言 `>= 100`
      //   home()            → 截到 HOME_LIMIT = 48（Hanime1Adapter.java:136，
      //                       注释「首页有 10 个横排区、144 张卡，全塞进去首屏太重」），
      //                       循环是 `i < all.length() && i < HOME_LIMIT`，里面还按 id 去重
      //
      // spider 的 home 对应的是 Java 的 home()，所以 48 是**对的**，不是丢数据。
      // 别看到 48 < 144 就去改正则 —— 那会把正确的截断当成 bug 来修。
      home: [
        ['卡片 ≥ 48（JUnit 对齐 Hanime1Adapter.home() 的 HOME_LIMIT = 48）',
          r => (r.items || []).length >= 48],
        ['id 非空（JUnit 首页首条字段齐全）', r => String((r.items || [])[0] && r.items[0].id || '').length > 0],
        ['封面在 vdownload.hembed.com（JUnit 首页首条字段齐全）',
          r => String((r.items || [])[0] && r.items[0].cover || '').indexOf('vdownload.hembed.com') >= 0],
        ['id 无重复（JUnit 首页id无重复）', r => {
          const ids = (r.items || []).map(one => String(one.id));
          return ids.length === new Set(ids).size;
        }],
      ],
      // Hanime1AdapterTest#分类页数量正常：n >= 30 && n <= 45
      category: [
        ['分类页 30~45 条（JUnit 分类页数量正常）', r => {
          const n = (r.items || []).length;
          return n >= 30 && n <= 45;
        }],
        ['id 是数字（JUnit 分类页没有广告卡混入）',
          r => (r.items || []).every(one => /^\d+$/.test(String(one.id || '')))],
        ['封面没有 /image/icon/（JUnit 分类页没有广告位图集当封面）',
          r => (r.items || []).every(one => String(one.cover || '').indexOf('/image/icon/') < 0)],
      ],
      // Hanime1AdapterTest#搜索页抠得出至少30张：>= 30
      // 240 个标签 / 7 个分区。夹具就是分类页（tagpanel.html 与 genre-1.html 同一份），
      // 面板在模态框里，所以 tags() 打分类页这条路径离线也能覆盖。
      tags: [
        ['240 个标签（对齐 Java 版实测的 240）', r => (r.items || []).length === 240],
        ['7 个分区', r => new Set((r.items || []).map(t => String(t.group))).size === 7],
        ['分区都在配置声明的组里', r => (r.items || []).every(t =>
          ['hanime-attr', 'relationship', 'hanime-role', 'style', 'setting', 'plot', 'hanime-act', 'other']
            .indexOf(String(t.group)) >= 0)],
        ['無碼归影片属性（繁体分区名要能映射）', r => {
          const hit = (r.items || []).find(t => String(t.name) === '無碼');
          return !!hit && String(hit.group) === 'hanime-attr';
        }],
        ['slug 不重复', r => {
          const slugs = (r.items || []).map(t => String(t.slug));
          return slugs.length === new Set(slugs).size;
        }],
      ],
      // 标签 → 列表：拼 ?genre=…&tags%5B%5D=…（夹具同样命中 genre-1.html）。
      tagList: [
        ['有卡片', r => (r.items || []).length > 0],
        ['卡片 id 非空', r => String((r.items || [])[0] && r.items[0].id || '').length > 0],
      ],
      search: [
        ['搜索页 ≥ 30（JUnit 搜索页抠得出至少30张）', r => (r.items || []).length >= 30],
      ],
      // Hanime1AdapterTest#详情页meta全部取到
      detail: [
        ['标题非空（JUnit 详情页meta全部取到）', r => String(r.title || '').length > 0],
        ['封面在 vdownload.hembed.com（JUnit 详情页meta全部取到）',
          r => String(r.cover || '').indexOf('vdownload.hembed.com') >= 0],
      ],
      // Hanime1AdapterTest#抠到三档直链且都带签名：3 档，quality 依次 480/720/1080，
      // 每条 url 都含 ?secure=
      play: [
        ['三档直链（JUnit 抠到三档直链且都带签名）', r => (r.alts || []).length === 3],
        ['每条都带 ?secure=（JUnit 抠到三档直链且都带签名）',
          r => (r.alts || []).every(one => String(one.url || '').indexOf('?secure=') >= 0)],
        ['url 是直链且带签名（JUnit 抠到三档直链且都带签名）',
          r => String(r.url || '').indexOf('?secure=') >= 0],
      ],
    },
    args: {
      home: {},
      category: { ref: '裏番', page: 1 },
      search: { ref: 'RD', page: 1 },
      detail: { ref: '408706' },
      play: { ref: '408706' },
      tags: {},
      // 前端点标签走的是 slug（标签名本身），和 detail 的 id 不是一回事。
      tagList: { slug: '巨乳', page: 1 },
    },
  },

  // ── yarn 9：iwara（JSON 接口 + 盾后站）────────────────────────────────
  //
  // 这个源和其它八家的差别只有两条，都在夹具里体现：
  //   ① 列表夹具故意做成「盾后回填形态」—— JSON 被包在 <pre> 里，外面还有
  //      <html><body>。真机上主进程过盾交回来的就是这个（WebView 的 outerHTML），
  //      所以回归必须跑这条，否则 unwrap() 那半边永远没人验。
  //   ② 搜索和详情是裸 JSON（模拟壳直接回字节的那条路），两条路都覆盖。
  //
  // 数字全部来自 2026-10-19 的真实响应：首页 limit=32 回 32 条（count=33 是站点的
  // 假数 —— 恒等于 page*limit+n+1，别拿它判 hasMore）；搜索 "hatsune miku" 的 count=1566。
  iwara: {
    dir: 'iwara',
    routes: [
      // 第二跳：签名清单。必须排在 /video/ 前面（都在 iwara 域，先命中先赢）。
      { match: /filesq\.iwara\.tv\/file\//, file: 'file-manifest.json' },
      { match: /api\.iwara\.tv\/search\?/, file: 'search-hatsune-p0.json' },
      { match: /api\.iwara\.tv\/video\/PCueI2c9srkJ0r/, file: 'video-pcue.json' },
      { match: /api\.iwara\.tv\/videos\?/, file: 'videos-date-p0.html' },
    ],
    checks: {
      home: [
        ['32 张卡（实测 limit=32 满页）', r => (r.items || []).length === 32],
        ['hasMore 是布尔 true', r => r.hasMore === true],
        ['首条 id 是 0bmwbE50acZY4B', r => String((r.items || [])[0] && r.items[0].id) === '0bmwbE50acZY4B'],
        ['封面走 i.iwara.tv（<pre> 包壳已剥开）',
          r => (r.items || []).every(one => String(one.cover || '').indexOf('https://i.iwara.tv/image/') === 0)],
        ['自定义封面走 /image/original/（FqzgOSpbK2hwsY）',
          r => (r.items || []).some(one => String(one.cover || '').indexOf('/image/original/') > 0)],
        ['缩略图序号两位零填充',
          r => (r.items || []).filter(one => String(one.cover || '').indexOf('/image/thumbnail/') > 0)
            .every(one => /thumbnail-\d{2}\.jpg$/.test(String(one.cover)))],
        ['时长格式 m:ss（494 秒 → 8:14）',
          r => String((r.items || [])[0] && r.items[0].duration) === '8:14'],
        ['标签非空', r => ((r.items || [])[0] && (r.items[0].tags || []).length) > 0],
      ],
      category: [
        ['32 张卡', r => (r.items || []).length === 32],
        ['page 回 1', r => r.page === 1],
        ['hasMore 是布尔 true', r => r.hasMore === true],
      ],
      search: [
        ['32 张卡', r => (r.items || []).length === 32],
        // 搜索接口的 count 是真总数（1566），所以 hasMore 该按 count 算。
        ['hasMore 是布尔 true（count=1566）', r => r.hasMore === true],
        ['命中的是 miku（首条标题含 Miku）',
          r => /miku/i.test(String((r.items || [])[0] && r.items[0].title))],
      ],
      detail: [
        ['id 是 PCueI2c9srkJ0r', r => String(r.id || '') === 'PCueI2c9srkJ0r'],
        ['标题非空', r => String(r.title || '').length > 0],
        ['单集（一条投稿就是一个视频）', r => (r.episodes || []).length === 1],
        ['episodeCount 是 1', r => r.episodeCount === 1],
        ['episodes[0].ref 是视频 id', r => String((r.episodes || [{}])[0].ref) === 'PCueI2c9srkJ0r'],
        ['封面是 i.iwara.tv 绝对地址',
          r => String(r.cover || '').indexOf('https://i.iwara.tv/') === 0],
      ],
      play: [
        ['两档线路（360 + preview）', r => (r.alts || []).length === 2],
        ['默认播 360P，不是 preview', r => String((r.alts || [{}])[0].line) === '360'],
        // 契约 §5.3：url 必须落在 alts 里，否则切一次线路再切回来就换源了。
        ['url 落在 alts 里', r => (r.alts || []).some(one => one.url === r.url)],
        ['url 是 https 绝对地址（协议相对地址已补全）',
          r => /^https:\/\/[a-z0-9.-]+\.iwara\.tv\//.test(String(r.url || ''))],
        ['没把清单 JSON 的地址当播放地址',
          r => String(r.url || '').indexOf('filesq.iwara.tv/file/') < 0],
      ],
    },
    args: {
      home: {},
      category: { slug: 'date', page: 1 },
      search: { keyword: 'hatsune miku', page: 1 },
      detail: { ref: 'PCueI2c9srkJ0r' },
      play: { ref: 'PCueI2c9srkJ0r', ep: 1 },
    },
  },

  // ── yarn 10：pornhub（服务端渲染 HTML，无盾）──────────────────────────
  //
  // 夹具是从真实页面**裁**出来的，不是造的：1MB 的页面塞进仓库太重，所以只留
  // 两段 —— 前 100KB（含 <link rel="next">，hasMore 就靠它）和头 12 张卡的原始
  // 标记。卡片标记一个字节没改，所以正则这边等价于跑整页。
  //
  // 搜索页必须单独一份：它的时长 span 是 `bgEffect time`、标题 <a> 多一个
  // `thumbnailTitle`，跟列表页不是同一套标记 —— 这正是最容易「看起来正常、
  // 其实一张都没解析出来」的地方。
  pornhub: {
    dir: 'pornhub',
    routes: [
      { match: /view_video\.php\?viewkey=/, file: 'detail.html' },
      { match: /\/video\/search\?/, file: 'search.html' },
      { match: /\/video\?c=/, file: 'category.html' },
      { match: /\/video$/, file: 'list.html' },
    ],
    checks: {
      home: [
        ['12 张卡（夹具裁到头 12 张）', r => (r.items || []).length === 12],
        ['hasMore 是布尔 true（靠 <link rel="next">）', r => r.hasMore === true],
        ['首条 id 是 vkey（不是数字 video-id）',
          r => String((r.items || [])[0] && r.items[0].id) === '6ad1206973670'],
        ['id 是 13 位 viewkey 形状',
          r => (r.items || []).every(one => /^[0-9a-f]{13}$/.test(String(one.id)))],
        ['封面在 phncdn 图床',
          r => (r.items || []).every(one => /^https:\/\/[a-z0-9.-]+\.phncdn\.com\//.test(String(one.cover)))],
        ['时长是 m:ss（首条 15:10）',
          r => String((r.items || [])[0] && r.items[0].duration) === '15:10'],
        ['标题非空且无 HTML 残留',
          r => (r.items || []).every(one => String(one.title).length > 0 && String(one.title).indexOf('<') < 0)],
      ],
      category: [
        ['12 张卡', r => (r.items || []).length === 12],
        ['page 回传 1', r => r.page === 1],
        ['hasMore 是布尔 true', r => r.hasMore === true],
      ],
      search: [
        ['搜索页 12 张卡（bgEffect time 那套标记也能解析）', r => (r.items || []).length === 12],
        ['首条是中文标题（搜索命中的那条）',
          r => String((r.items || [])[0] && r.items[0].title).indexOf('整张床') === 0],
        // videoViews 只有搜索页有，能解析出来就说明走的确实是搜索页那套结构。
        ['能读出播放量（1.9M）',
          r => String((r.items || [])[0] && r.items[0].score) === '1.9M'],
        ['hasMore 是布尔 true', r => r.hasMore === true],
      ],
      detail: [
        ['id 是 viewkey', r => String(r.id || '') === '6ad1206973670'],
        ['标题取自 flashvars.video_title',
          r => String(r.title || '').indexOf('Japanese sexy Yume Hoshino') === 0],
        ['单集', r => (r.episodes || []).length === 1],
        ['选集说明里带时长 15:10',
          r => String((r.episodes || [{}])[0].title).indexOf('15:10') >= 0],
        ['封面在 phncdn', r => /^https:\/\/[a-z0-9.-]+\.phncdn\.com\//.test(String(r.cover || ''))],
      ],
      play: [
        ['四档 HLS（1080/720/480/240）', r => (r.alts || []).length === 4],
        ['默认给 1080P', r => String((r.alts || [{}])[0].line) === '1080'],
        ['url 落在 alts 里', r => (r.alts || []).some(one => one.url === r.url)],
        ['给的是 m3u8，不是 /video/get_media（那条实测回来是空数组）',
          r => String(r.url || '').indexOf('master.m3u8') > 0
            && String(r.url || '').indexOf('/video/get_media') < 0],
        ['线路名可读（1080P 这种）', r => String((r.alts || [{}])[0].name) === '1080P'],
      ],
    },
    args: {
      home: {},
      category: { slug: '1', page: 1 },
      search: { keyword: 'asian', page: 1 },
      detail: { ref: '6ad1206973670' },
      play: { ref: '6ad1206973670', ep: 1 },
    },
  },

  // ── yarn 11：51cg（自研主题，服务端渲染，无盾）────────────────────────
  //
  // 夹具同样是裁的：留着前 60KB（`<link rel="next">` 在这儿）和头 12 张卡的原始标记。
  // 两类详情页各留一份 —— 视频帖（带 data-config）和图文帖（没有），因为
  // 「图文帖要降级成 reason 而不是报错」是这个站独有的一条分支。
  //
  // 卡片封面不在 <img src> 里，而是写在 `<script>loadBannerDirect('…')</script>` 的
  // 参数里，所以断言盯的是「封面来自那个脚本参数」而不是「有 src 属性」。
  '51cg': {
    dir: '51cg',
    routes: [
      { match: /\/archives\/279044\//, file: 'detail-video.html' },
      { match: /\/archives\/279391\//, file: 'detail-text.html' },
      { match: /\/search\//, file: 'search.html' },
      { match: /\/category\//, file: 'category.html' },
      { match: /51cg1\.com\/(?:$|\?|page\/)/, file: 'list.html' },
    ],
    checks: {
      home: [
        ['卡片 ≥ 12 张', r => (r.items || []).length >= 12],
        ['hasMore 是布尔 true（靠 <link rel="next">）', r => r.hasMore === true],
        ['首条 id 是 279044', r => String((r.items || [])[0] && r.items[0].id) === '279044'],
        ['id 都是纯数字', r => (r.items || []).every(one => /^\d+$/.test(String(one.id)))],
        ['封面来自 loadBannerDirect 的 pic.gyezbp.cn',
          r => (r.items || []).every(one => /^https:\/\/pic\.gyezbp\.cn\//.test(String(one.cover)))],
        // 角标「热搜 HOT」嵌在 <h2> 里的 <div class="wrap">，剥不干净就会粘在标题尾巴上。
        ['标题里没有残留的「热搜 HOT」角标',
          r => (r.items || []).every(one => String(one.title).indexOf('热搜 HOT') < 0)],
        ['标题非空且无 HTML 残留',
          r => (r.items || []).every(one => String(one.title).length > 0 && String(one.title).indexOf('<') < 0)],
      ],
      category: [
        ['卡片 ≥ 12 张', r => (r.items || []).length >= 12],
        ['首条 id 是 279407', r => String((r.items || [])[0] && r.items[0].id) === '279407'],
        ['hasMore 是布尔 true', r => r.hasMore === true],
      ],
      search: [
        ['卡片 ≥ 12 张', r => (r.items || []).length >= 12],
        ['首条 id 是 279423', r => String((r.items || [])[0] && r.items[0].id) === '279423'],
        ['hasMore 是布尔 true', r => r.hasMore === true],
      ],
      detail: [
        ['id 回传', r => String(r.id || '') === '279044'],
        ['标题非空（取自 <h1>）', r => String(r.title || '').length > 0],
        ['单集', r => (r.episodes || []).length === 1],
        ['选集说明标了正片（HLS）', r => String((r.episodes || [{}])[0].title).indexOf('HLS') >= 0],
        ['封面在 pic.gyezbp.cn',
          r => /^https:\/\/pic\.gyezbp\.cn\//.test(String(r.cover || ''))],
        ['标签从「关键词：#…」拆出来了', r => (r.tags || []).length > 3],
      ],
      play: [
        // data-config 里同时给了 H.264 和 H.265 两条 m3u8。
        ['两条线路（h264 + h265）', r => (r.alts || []).length === 2],
        ['默认 H.264', r => String((r.alts || [{}])[0].line) === 'h264'],
        ['url 落在 alts 里', r => (r.alts || []).some(one => one.url === r.url)],
        ['给的是 m3u8（hls.weagdnz.cn 带 auth_key）',
          r => /^https:\/\/hls\.weagdnz\.cn\//.test(String(r.url || ''))
            && String(r.url || '').indexOf('auth_key=') > 0],
      ],
    },
    args: {
      home: {},
      category: { slug: 'whhl', page: 1 },
      search: { keyword: '吃瓜', page: 1 },
      detail: { ref: '279044' },
      play: { ref: '279044', ep: 1 },
    },
  },

  // ── yarn 12：dozagonza（苹果CMS + 自定义模板，站前 Cloudflare 盾）──────
  //
  // 夹具按真实结构裁过：首页 3 张卡 + 分页控件，分类页 3 张卡 + 完整的
  // page-num（含「1/1832」那条总页数），详情页 3 集，播放页那段 player_aaaa。
  //
  // 两条最容易被顺手改坏的，断言专门盯：
  //   ① 封面只在 data-original 里，src 恒为模板自带的 load.gif；
  //   ② 详情页元信息是 <p class="director"><label>导演：</label><span>…</span></p>，
  //      先 stripTags 再正则会把「王斯阳主演：」整个吞进导演名。
  dozagonza: {
    dir: 'dozagonza',
    routes: [
      { match: /\/play\/\d+\/\d+\/\d+/, file: 'play.html' },
      { match: /\/search\.html/, file: 'search.html' },
      { match: /\/video\/\d+/, file: 'detail.html' },
      { match: /\/tv\/\d+/, file: 'tv1.html' },
      { match: /dozagonza\.com\/?(?:$|\?|#)/, file: 'home.html' },
    ],
    checks: {
      home: [
        ['卡片 3 张', r => (r.items || []).length === 3],
        ['首条 id 是 275134247', r => String((r.items || [])[0] && r.items[0].id) === '275134247'],
        ['id 都是纯数字', r => (r.items || []).every(one => /^\d+$/.test(String(one.id)))],
        ['标题取自 .thumb 的 title 属性',
          r => String((r.items || [])[0] && r.items[0].title) === '势在必行'],
        ['封面是 img.dozagonza.com 的绝对地址',
          r => (r.items || []).every(one => /^https:\/\/img\.dozagonza\.com\//.test(String(one.cover)))],
        ['没有把占位 gif 当封面',
          r => (r.items || []).every(one => String(one.cover).indexOf('load.gif') < 0)],
        ['更新状态进了 note', r => String((r.items || [])[0] && r.items[0].note) === '已完结'],
        ['分类标签进了 tags', r => String((((r.items || [])[0] || {}).tags || [])[0]) === '悬疑'],
        ['靠 title="下一页" 判 hasMore', r => r.hasMore === true],
      ],
      category: [
        ['卡片 3 张', r => (r.items || []).length === 3],
        ['首条 id 是 27557829', r => String((r.items || [])[0] && r.items[0].id) === '27557829'],
        ['hasMore 是布尔 true（分页控件写着 1/1832）', r => r.hasMore === true],
      ],
      detail: [
        ['id 回传', r => String(r.id || '') === '275179106'],
        ['标题取自 h1.article-subject', r => String(r.title || '') === '海边的秘密'],
        ['封面落在 upload/vod',
          r => /^https:\/\/img\.dozagonza\.com\/upload\/vod\//.test(String(r.cover || ''))],
        ['简介取自 .summary-con（不是 meta description）',
          r => String(r.description || '').indexOf('超清无删减版') >= 0],
        // 两条线路（sid=1 三集、sid=2 四集）→ 取集数多的那条，所以是 4 条。
        ['剧集取线路 2 的 4 条', r => (r.episodes || []).length === 4],
        // 2026-10-23 起 ref 带线路：`id:sid:nid`。夹具那个站只有一条 sid=1 的线路，
        // 但嫁金钗那种两条线路的站必须靠这个值才能请求对播放页（见 dozagonza 的
        // episodesOf 注释）。
        ['第一集 ref 是「id:线路:集号」',
          r => String((((r.episodes || [])[0]) || {}).ref) === '275179106:2:1'],
        ['第一集标题第01集',
          r => String((((r.episodes || [])[0]) || {}).title) === '第01集'],
        ['第三集标题第03集',
          r => String((((r.episodes || [])[2]) || {}).title) === '第03集'],
        // 这一条盯的是「别退回 stripTags 正则」：那样会拿到「王斯阳主演：」。
        ['分类从 p.types 取（不是空的 state 位）',
          r => (r.tags || []).indexOf('国产剧') >= 0],
        ['线路名是「高清线路」', r => String((((r.lines || [])[0]) || {}).name) === '高清线路'],
      ],
      // 搜索页是另一套卡片：<ul class="search-list"> 里 <li class="item mb15 card-wrap">。
      // 2026-10-21 真机就栽在这：锚点写死成 class="item" 切不出东西、标题被
      // 关键词高亮成嵌套 <b> 只抠到一个字、评分 span 粘在片名后面。
      search: [
        ['卡片 2 张（不是 0）', r => (r.items || []).length === 2],
        ['标题是「嫁金钗」全名，不是只抠到高亮那一个字',
          r => String(((r.items || [])[0] || {}).title) === '嫁金钗'],
        ['标题没把「9.0分」粘上去',
          r => String(((r.items || [])[0] || {}).title).indexOf('分') < 0],
        ['第二条标题是「爱在无尽夏」',
          r => String(((r.items || [])[1] || {}).title) === '爱在无尽夏'],
        ['封面是 img.dozagonza.com',
          r => (r.items || []).every(one => /^https:\/\/img\.dozagonza\.com\//.test(String(one.cover)))],
        ['更新状态从「资源：」取到了',
          r => String(((r.items || [])[0] || {}).playAt) === '更新第10集'],
        ['id 是纯数字', r => /^\d+$/.test(String(((r.items || [])[0] || {}).id))],
      ],
      play: [
        ['地址是 m3u8 结尾', r => /\.m3u8$/.test(String(r.url || ''))],
        ['落在 play.hhuus.com', r => String(r.url || '').indexOf('play.hhuus.com') > 0],
        ['备选 1 条', r => (r.alts || []).length === 1],
        ['集号对得上', r => Number(r.episodeNumber) === 3],
        // 这条是 2026-10-23 那次的回归点：play 必须按 ref 里的线路去请求，
        // 不能再写死 /play/<id>/1/<nid>.html —— 站点对不存在的线路一律回第 1 集。
        ['请求的是线路 2 的第 3 集',
          (r, info) => (info.requested || []).some(req =>
            /\/play\/275179106\/2\/3\.html/.test(String((req && req.url) || req)))],
        ['note 写明 AES-128 的相对密钥', r => String(r.note || '').indexOf('AES-128') >= 0],
      ],
    },
    args: {
      home: {},
      category: { slug: '1', page: 1 },
      search: { keyword: '嫁', page: 1 },
      detail: { ref: '275179106' },
      play: { ref: '275179106:2:3', ep: 3 },
    },
  },

  // ── yarn 6/7/8：agedm / cms / openlist ─────────────────────────────────
  //
  // 仓库里一份夹具都没有（这三个源的 JUnit —— AgedmAdapterTest 不存在、
  // CmsAdapterTest 不存在、OpenListAdapterTest 只测纯函数 —— 也不依赖 HTML）。
  // 所以这里只登记「我要去哪找夹具、将来断言抄谁」，checks 留空：
  // 配置了就等于承诺能跑，一份夹具都没有时把它配成空是诚实的写法。
  //
  // 补夹具的做法：把 URL 响应丢进对应目录，然后按上面的形状填 fixtures / routes
  // 和 checks，数字从新写的 JUnit 里抄。
  agedm: {
    dir: 'agedm',
    fixtures: {},
    checks: {},
    args: {},
    note: '没有夹具（AgedmAdapterTest 不存在），也还没有可抄的断言',
  },
  cms: {
    dir: 'cms',
    fixtures: {},
    checks: {},
    args: {},
    note: '没有夹具（CmsAdapterTest 不存在），也还没有可抄的断言',
  },  openlist: {
    dir: 'openlist',
    fixtures: {},
    checks: {},
    args: {},
    // OpenListAdapterTest 只测纯函数（encodeId/decodeId/isMedia/join…），一个 HTML 都不读，
    // 所以那只测试里的数字抄不过来 —— spider 的 home/detail/play 要的是 fs/list 的
    // JSON POST 响应，那类夹具仓库里没有。
    note: '没有夹具（OpenListAdapterTest 只测纯函数，不读 HTML）；POST 响应可用 post-<末段>.json 补',
  },

  // ── 水果派（pie，com.fruit.goodgood 2.5.4）────────────────────────────
  //
  // 接口是 POST /v2_0/<name> + RSA/AES 信封，夹具存的是**信封原文**（不是明文）：
  // 离线回归顺带把纯 JS 的 RSA/AES 解密路径也跑一遍。信封是用 APK 里
  // client_private.key 对应的公钥封的，和服务器返回的形状完全一致。
  //
  // 五个夹具都是 2026-10-20 从 api.25xzguk9.com 的实测响应转出来的。
  pie: {
    dir: 'pie',
    // POST 源按约定吃 `post-<路径末段>.json`（makeResolver 的 POST 分支只看这个，
    // 不看 fixtures 表）—— 这里把名字写出来，等于告诉后来人补夹具放哪。
    fixtures: {
      'post-homePage.json': /\/v2_0\/homePage/,
      'post-classificationContentDetails.json': /\/v2_0\/classificationContentDetails/,
      'post-searchV21List.json': /\/v2_0\/searchV21List/,
      'post-getSingleItem.json': /\/v2_0\/getSingleItem/,
      'post-filmDetails.json': /\/v2_0\/filmDetails/,
    },
    checks: {
      home: [
        ['一页 5 条（接口固定 5 条/页）', r => (r.items || []).length === 5],
        ['id 是数字串', r => /^\d+$/.test(String((r.items || [])[0] && r.items[0].id || ''))],
        ['标题非空', r => String((r.items || [])[0] && r.items[0].title || '').length > 0],
        ['封面是 https 绝对地址',
          r => String((r.items || [])[0] && r.items[0].cover || '').startsWith('https://')],
        ['hasMore 是布尔 true', r => r.hasMore === true],
      ],
      category: [
        ['一页 15 条', r => (r.items || []).length === 15],
        ['id 无重复', r => {
          const ids = (r.items || []).map(one => String(one.id));
          return ids.length === new Set(ids).size;
        }],
        ['有下一页', r => r.hasMore === true],
      ],
      search: [
        ['搜索「探花」有结果', r => (r.items || []).length >= 1],
        ['到底了不谎报下一页', r => r.hasMore === false],
      ],
      detail: [
        ['标题非空（这条是图文帖，标题取自 content）', r => String(r.title || '').length > 0],
        ['单集', r => (r.episodes || []).length === 1],
        ['集 ref 就是 library_id',
          r => String((r.episodes || [])[0] && r.episodes[0].ref) === '2338'],
        ['封面在 img.2ktdfje.top',
          r => String(r.cover || '').indexOf('img.2ktdfje.top') >= 0],
      ],
      play: [
        ['降级成 reason=encrypted，不给假地址', r => r.reason === 'encrypted'],
        ['url 为空（密文不塞进 url）', r => !r.url],
      ],
    },
    args: {
      home: {},
      category: { slug: '1', page: 1 },
      search: { keyword: '探花', page: 1 },
      detail: { ref: '2338' },
      play: { ref: '10451' },
    },
  },
};

/** 能力名的固定顺序：汇总表的列就是它。 */
const CAPS = ['home', 'category', 'search', 'detail', 'play', 'tags', 'tagList', 'reconcile'];

/** 每个源跑哪些能力：配置了断言的能力 + 配置了入参的能力，取并集，按 CAPS 排序。 */
function capsFor(profile) {
  if (!profile) return [];
  const wanted = new Set();
  Object.keys(profile.checks || {}).forEach(cap => wanted.add(cap));
  Object.keys(profile.args || {}).forEach(cap => wanted.add(cap));
  return CAPS.filter(cap => wanted.has(cap));
}

/**
 * 夹具按什么找。
 *
 * 两种写法最终都归到「URL → 文件内容」。找不到时**返回 null 而不是抛错** ——
 * 未配置的 URL 是正常状态（比如源多打了一次请求去拿分类树），要让它继续跑，
 * 最后在报告里把请求过的 URL 列出来。
 */
function makeResolver(profile) {
  const dir = path.join(FIXTURES, profile.dir);
  const requested = [];
  const missed = [];

  function readFixture(file) {
    const full = path.join(dir, file);
    if (!fs.existsSync(full)) return { ok: false, why: '夹具不存在: ' + full };
    return { ok: true, body: fs.readFileSync(full, 'utf8') };
  }

  function resolve(url, method, body) {
    requested.push({ url, method, body });
    const isPost = String(method || 'GET').toUpperCase() === 'POST';

    // POST 的夹具约定：`<dir>/post-<路径最后一段>.json`。
    // 这是给 openlist / 将来任何 JSON POST 源留的口子 —— 补 POST 夹具时
    // 只要放文件，不用改脚本。
    if (isPost) {
      let last = String(url).split('?')[0].replace(/\/+$/, '').split('/').pop() || 'root';
      last = last.replace(/[^\w.-]/g, '_');
      const hit = readFixture('post-' + last + '.json');
      if (hit.ok) return hit;
      missed.push({ url, method, why: '缺 POST 夹具 post-' + last + '.json' });
      return { ok: false, missing: true, why: hit.why };
    }

    if (Array.isArray(profile.routes)) {
      for (const route of profile.routes) {
        const m = route.match.exec(url);
        if (!m) continue;
        // 支持 $1 / $2 这种回填，让 /archives/(\d+)/ 能直接对上文件。
        const file = String(route.file).replace(/\$(\d+)/g, (all, i) => m[Number(i)] || all);
        return readFixture(file);
      }
    }

    if (profile.fixtures) {
      for (const file of Object.keys(profile.fixtures)) {
        if (profile.fixtures[file].test(url)) return readFixture(file);
      }
    }

    missed.push({ url, method, why: '这个 URL 没有路由' });
    return { ok: false, missing: true, why: '没有匹配的夹具路由' };
  }

  return {
    resolve,
    requested,
    missed,
    dir,
    /** 这个源的夹具目录到底在不在 —— 决定「未配置夹具」还是「夹具缺失」。 */
    hasDir: () => fs.existsSync(dir) && fs.statSync(dir).isDirectory(),
    listed: () => {
      if (!fs.existsSync(dir)) return [];
      return fs.readdirSync(dir).filter(name => !name.startsWith('.'));
    },
  };
}

/**
 * spider 能看到的全部能力：`call(op, args)` 是**规范形式**，`net` / `log` 是糖。
 *
 * 两套都给是有原因的：`call` 是宿主页里那个真对象（`window.__spider`），spider 里
 * 写的 `ctx.call('net', …)` 在真机和这里必须走同一条路；而 `ctx.net(…)` 读起来
 * 像普通函数，写源的人更愿意用它。宿主页也提供这两个糖，所以两边一致 —— 糖是
 * 宿主页的一行箭头函数，不是 spider 自己拼的。
 *
 * 刻意没有 window / document / DOMParser：spider 一用 DOM API，这个脚本就跑不了，
 * 等于这个源从此没有离线回归。约束用沙箱强制，不靠约定。
 *
 * POST 也走同一条路：`method` / `format` 只是决定去哪找夹具（见 makeResolver），
 * 这里不做任何协议转换 —— 真实运行时把 form 转成表单体、json 转成 JSON 体，
 * 离线这边两者都是「夹具文件里的那段字符串」。
 */
function makeCtx(resolver, logs) {
  const ctx = {
    async call(op, args) {
      const a = args || {};
      if (op === 'net') {
        const url = String(a.url || '');
        const method = String(a.method || 'GET').toUpperCase();
        const hit = resolver.resolve(url, method, a.body);
        if (!hit.ok) {
          return { ok: false, error: '离线回归里没有这个 URL 的 fixture：' + url };
        }
        const out = { ok: true, status: 200, body: hit.body };
        // keep 是「把这些 Set-Cookie 交回给源」。离线没有响应头，给一个空 jar：
        // 源能拿到形状，但拿不到值 —— 这样它走的就是「站点没下发」那条兜底分支，
        // 不会因为离线缺 cookie 而在某一步 differ。
        if (Array.isArray(a.keep) && a.keep.length) out.cookies = {};
        return out;
      }
      if (op === 'netAll') {
        // 离线沙箱没有线程池，串行跑完再按顺序返回 —— 形状必须和真机一致
        // （results 与 requests 一一对应），否则源里那套「按索引映射回 id」
        // 在真机上会错位，而离线是绿的。
        const list = Array.isArray(a.requests) ? a.requests : [];
        const results = [];
        for (const one of list) {
          results.push(await ctx.call('net', one || {}));
        }
        return { ok: true, results: results };
      }
      if (op === 'log') {
        if (a.msg) logs.push(String(a.msg));
        return { ok: true };
      }
      return { ok: false, error: '离线回归不支持 op=' + op };
    },
    net(args) { return this.call('net', args); },
    log(args) { return this.call('log', args); },
  };
  return ctx;
}

/**
 * 在 vm 里加载一个 spider 文件。
 *
 * 返回 `{ok:true, site, source}` 或 `{ok:false, error}`。**不抛** —— 调用方
 * （汇总表）要的是「这一家为什么没起来」，不是一次挂掉整个脚本。
 */
function loadSpider(spiderPath) {
  const abs = path.resolve(process.cwd(), spiderPath);
  if (!fs.existsSync(abs)) return { ok: false, error: '文件不存在: ' + abs, path: abs };
  let source;
  try {
    source = fs.readFileSync(abs, 'utf8');
  } catch (error) {
    return { ok: false, error: '读不了文件：' + error.message, path: abs };
  }

  const sandbox = { __spiderSite: null, console: { log() {}, error() {} } };
  vm.createContext(sandbox);
  try {
    vm.runInContext(source, sandbox, { filename: abs });
  } catch (error) {
    return { ok: false, error: 'spider 执行失败：' + ((error && error.message) || error), path: abs };
  }
  const site = sandbox.__spiderSite;
  if (!site) return { ok: false, error: 'spider 没有导出 __spiderSite', path: abs };
  if (!Array.isArray(site.hosts)) return { ok: false, error: 'spider 必须声明 hosts 数组', path: abs };
  return { ok: true, site, source, path: abs };
}

/**
 * 调一个能力，带看门狗。
 *
 * ## 为什么不能只靠 `Promise.race([fn(), sleep(N)])`
 *
 * 因为源里的死循环是**同步**的。`race` 的计时器是宏任务，事件循环被一个
 * `while` 占住时计时器根本轮不上 —— 整个进程就卡在那里，表一行都打不出来。
 * 2026-10-19 真撞上了：missav 的 `qualities()` 里 `exec` 命中后 `continue`
 * 没有推进 `lastIndex`，第二次命中同一个画质词就原地打转。
 *
 * ## 所以分两层
 *
 *  ① 同步段（函数体跑到第一个 await 之前）交给 `vm.runInContext` 的 `timeout`。
 *     —— 但它对**已经在跑的 Promise 链**无能为力，只能兜住同步那一截。
 *  ② 异步段交给 `Promise.race` 的计时器，等事件循环空出来才会触发。
 *     所以它必须**在**同步段之后才可能生效，两层缺一不可。
 *
 * 超时不算「断言没过」——它是「这个源把回归脚本拖死了」，单独记成 error，
 * 备注里说明白，别让人以为是解析写错了。
 */
function callWithWatchdog(fn, ctx, args, cap, entry) {
  return new Promise(resolve => {
    let settled = false;
    const done = payload => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve(payload);
    };

    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      entry.status = 'error';
      entry.error = '看门狗超时：' + cap + ' 跑了超过 ' + CAP_TIMEOUT_MS
        + 'ms 还没返回（大概率是源里的死循环 —— 检查 exec 循环有没有推进 lastIndex）';
      resolve(null);
    }, CAP_TIMEOUT_MS);

    // 同步段：这里同步抛的错误直接走 catch，跟 await 抛的走同一条。
    Promise.resolve()
      .then(() => fn(ctx, args))
      .then(payload => done(payload), error => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        entry.status = 'error';
        entry.error = String((error && error.message) || error).split('\n')[0];
        resolve(null);
      });
  });
}

/**
 * 同步死循环的兜底：用子进程跑一次调用。
 *
 * 上面那个 `callWithWatchdog` 只能兜住「跑到第一个 await 之前」的那一截 ——
 * `Promise.race` 的计时器是宏任务，事件循环被占住时它永远轮不上，而
 * `vm.runInContext(…, {timeout})` 对已经跑起来的 Promise 链也管不着。
 * 结果是整个进程挂在那儿，一张表都打不出来（2026-10-19 撞上过）。
 *
 * 唯一可靠的办法是**换一个进程**：kill 得掉的东西只有进程。所以每个能力
 * 单独起一个 node 子进程，父进程 `spawnSync` 带超时 —— 超时就杀掉。
 *
 * 代价是每个能力多一次进程启动（约 40~80ms）。八个源 × 五个能力 ≈ 2~3 秒，
 * 换「任何一家写坏了都不会让汇总表打不出来」，值。
 *
 * 判据：`process.execArgv` 里带 `--child` 就走内联那条（见文件末尾的 CLI），
 * 不带就 spawn。这样 `node scripts/spider-check.js x.js` 的行为和以前一样，
 * 只有汇总脚本（和显式加了 `--isolate` 的调用）才走子进程。
 */
function runIsolated(spiderPath, cap, timeoutMs) {
  const { spawnSync } = require('child_process');
  // 超时必须是一个实数 —— 传 undefined 时 spawnSync 会当「不设超时」，
  // 于是死的源就把父进程一起拖住（2026-10-19 实测：subprocess 卡在
  // missav 的同步死循环上，父进程等了一分钟以上）。
  const limit = Number.isFinite(timeoutMs) && timeoutMs > 0 ? timeoutMs : CAP_TIMEOUT_MS;
  const started = Date.now();
  const out = spawnSync(process.execPath, [__filename, '--child', spiderPath, cap], {
    encoding: 'utf8',
    timeout: limit,
    killSignal: 'SIGKILL',
    // stdio 必须显式给：默认的 inherit/pipe 混搭在子进程被 SIGKILL 之后，
    // 父进程会挂在读管道上，timeout 等于没设。
    stdio: ['ignore', 'pipe', 'pipe'],
    maxBuffer: 8 * 1024 * 1024,
  });
  const elapsed = Date.now() - started;

  const text = String(out.stdout || '');
  // 超时时 stdout 里可能有半截结果 —— 先看有没有完整的 __RESULT__ 行，
  // 有就照用（子进程其实跑完了，只是没退出），没有才判超时。
  const line = text.split('\n').filter(one => one.startsWith('__RESULT__')).pop();

  if (out.error && out.error.code === 'ETIMEDOUT') {
    if (line) {
      try {
        const parsed = JSON.parse(line.slice('__RESULT__'.length));
        parsed.elapsed = elapsed;
        return parsed;
      } catch (error) { /* 落进下面的超时分支 */ }
    }
    return {
      status: 'error',
      error: '看门狗超时：' + cap + ' 跑了超过 ' + limit + 'ms（子进程被 SIGKILL）'
        + ' —— 大概率是源里的同步死循环，检查 exec 循环有没有推进 lastIndex',
      elapsed,
    };
  }
  if (out.error) {
    return { status: 'error', error: '子进程起不来：' + out.error.message, elapsed };
  }
  if (!line) {
    const err = String(out.stderr || '').split('\n').filter(Boolean).pop() || '子进程没有输出';
    return { status: 'error', error: err, elapsed };
  }
  try {
    const parsed = JSON.parse(line.slice('__RESULT__'.length));
    parsed.elapsed = elapsed;
    return parsed;
  } catch (error) {
    return { status: 'error', error: '子进程结果不是 JSON：' + line.slice(0, 200), elapsed };
  }
}

/** 单个能力的看门狗上限。真机的 SpiderRuntime 超时是 15s，这里给 5s 就够暴露死循环。 */
const CAP_TIMEOUT_MS = 5000;

/**
 * 跑一个源。**这是 `spider-all-check.js` 唯一需要的东西。**
 *
 * 返回：
 *   { id, version, ok, file, error,
 *     configured,            // 有没有离线回归配置
 *     dir, hasFixtures,      // 夹具目录在不在
 *     caps: { home:{status, failed, checks, note, error, requested}, … },
 *     requested, missed, logs }
 *
 * status 取值：'pass' | 'fail' | 'unimplemented'（源没写这个 cap）
 *              | 'not-configured'（没配入参/断言）| 'no-fixtures'（缺夹具）
 *              | 'error'（cap 抛错）| 'skipped'（--skip 指定跳过）
 *
 * 注意「fail」和「error」不是一回事：fail 是断言没过（解析写错了），
 * error 是抛错（沙箱缺夹具、源自己报错）。汇总表两列分开显示。
 */
async function runSpider(spiderPath, options) {
  const opts = options || {};
  const loaded = loadSpider(spiderPath);  if (!loaded.ok) {
    return {
      id: path.basename(spiderPath, '.js'),
      file: spiderPath,
      ok: false,
      error: loaded.error,
      configured: false,
      caps: {},
      requested: [],
      missed: [],
      logs: [],
    };
  }

  const site = loaded.site;
  const id = String(site.id || path.basename(spiderPath, '.js'));
  const profile = PROFILES[id];
  const result = {
    id,
    version: site.version,
    file: spiderPath,
    ok: true,
    error: null,
    configured: !!profile,
    profile,
    caps: {},
    requested: [],
    missed: [],
    logs: [],
  };

  if (!profile) {
    result.note = '这个源还没有离线回归配置';
    return result;
  }

  const resolver = makeResolver(profile);
  const logs = [];
  const ctx = makeCtx(resolver, logs);
  result.hasFixtures = resolver.hasDir();
  result.fixtureDir = resolver.dir;
  result.fixtureFiles = resolver.listed();

  const caps = capsFor(profile);
  const only = opts.only && opts.only.length ? new Set(opts.only) : null;

  // 一条断言都没配的源（agedm / cms / openlist）：给每个能力补一个
  // 'not-configured' 条目，让汇总表显示 ⊘ 而不是一片 —。
  // 一片「未实现」会误导成「源没写完」，实际是「回归还没配」——两件事的
  // 下一步动作不一样（一个等 A/B 组，一个等补夹具）。
  if (caps.length === 0) {
    for (const cap of CAPS) {
      result.caps[cap] = {
        status: 'not-configured',
        failed: 0,
        checks: [],
        error: null,
        note: '没有断言可跑（' + (profile.note || '夹具和断言都还没配') + '）',
        requested: [],
      };
    }
    result.requested = [];
    result.missed = [];
    result.logs = [];
    return result;
  }

  // 隔离模式：每个能力起一个子进程跑。汇总脚本默认走这条 —— 任何一家
  // 写了个同步死循环，死的也只是那个子进程。
  const isolate = !!opts.isolate;

  for (const cap of caps) {
    if (only && !only.has(cap)) continue;

    const entry = {
      status: 'pass',
      failed: 0,
      checks: [],
      error: null,
      note: null,
      requested: [],
    };
    result.caps[cap] = entry;

    if (typeof site[cap] !== 'function') {
      entry.status = 'unimplemented';
      entry.note = '未实现';
      continue;
    }

    const checks = (profile.checks && profile.checks[cap]) || [];
    const args = (profile.args && profile.args[cap]) || {};

    if (checks.length === 0 && Object.keys(args).length === 0) {
      entry.status = 'not-configured';
      entry.note = '未配置';
      continue;
    }

    if (isolate) {
      const child = runIsolated(spiderPath, cap, opts.timeoutMs || CAP_TIMEOUT_MS);
      entry.status = child.status;
      entry.error = child.error || null;
      entry.note = child.note || null;
      entry.requested = child.requested || [];
      entry.checks = child.checks || [];
      entry.failed = child.failed || 0;
      entry.firstFailure = child.firstFailure;
      entry.elapsed = child.elapsed;
      // payload 由子进程序列化带回，`--dump` 打的就是它。
      entry.payload = child.payload;
      // 子进程里只跑了这一个能力，夹具的「没命中」也要带回来。
      if (child.missed) result.missed = (result.missed || []).concat(child.missed);
      if (child.requestedList) result.requested = (result.requested || []).concat(child.requestedList);
      if (child.logs) logs.push.apply(logs, child.logs);
      continue;
    }

    const payload = await callWithWatchdog(site[cap], ctx, args, cap, entry);
    if (entry.status === 'error') {
      entry.requested = resolver.requested.slice();
      continue;
    }

    entry.requested = resolver.requested.slice();
    // 存一份原始 payload：`--dump` 打的是它（不是内部分析结果）——
    // 补断言的人要看的是「spider 到底吐了什么」，不是「我们判它过了没」。
    entry.payload = payload;

    if (checks.length === 0) {
      // 有入参没断言：跑通了就算「能跑」，但不判对错。
      entry.note = '无断言（只验证能跑）';
      continue;
    }

    for (const [label, predicate] of checks) {
      let ok = false;
      try {
        ok = !!predicate(payload || {}, { requested: entry.requested, missed: entry.missed });
      } catch (error) {
        ok = false;
      }
      entry.checks.push({ label, ok });
      if (!ok) {
        entry.failed++;
        if (entry.firstFailure === undefined) {
          entry.firstFailure = {
            label,
            actual: String(JSON.stringify(payload)).slice(0, 240),
          };
        }
      }
    }
    entry.status = entry.failed === 0 ? 'pass' : 'fail';
  }

  result.requested = resolver.requested.slice();
  result.missed = resolver.missed.slice();
  result.logs = logs;
  return result;
}

/** 列出 spiders 目录里所有源文件，跳过 `_` 开头（阶段 0 的自检样本）。 */
function listSpiders(dir) {
  const base = dir || path.join(FIXTURES, 'spiders');
  if (!fs.existsSync(base)) return [];
  return fs
    .readdirSync(base)
    .filter(name => name.endsWith('.js') && !name.startsWith('_') && !name.startsWith('.'))
    .sort()
    .map(name => path.join(base, name));
}

/** 给 `--list` 用：一句话说清这个源配了什么。 */
function describeProfile(id) {
  const profile = PROFILES[id];
  if (!profile) return '未配置';
  const caps = capsFor(profile);
  if (caps.length === 0) {
    return '没有断言（' + (profile.note || '还没抄到可用的数字') + '）';
  }
  const parts = caps.map(cap => {
    const n = ((profile.checks && profile.checks[cap]) || []).length;
    return cap + (n ? '(' + n + ')' : '(0)');
  });
  return parts.join(' ') + ' · 夹具目录 ' + profile.dir;
}

// ── CLI ────────────────────────────────────────────────────────────────────
// 被 require 时不跑。判据是 `require.main === module`，不用 argv[1] —— 后者在
// `node -e "require('./spider-check.js')"` 下也会命中。
function main(argv) {
  const spiderPath = argv[2];
  if (!spiderPath || spiderPath.startsWith('--')) {
    fail('用法: node scripts/spider-check.js <spider.js> [--dump <cap>] [--list] [--skip cap,cap] [--no-isolate]');
  }

  const dumpIdx = argv.indexOf('--dump');
  const dumpCap = dumpIdx > 0 ? argv[dumpIdx + 1] : null;
  const skipIdx = argv.indexOf('--skip');
  const only = skipIdx > 0 && argv[skipIdx + 1]
    ? CAPS.filter(cap => argv[skipIdx + 1].split(',').indexOf(cap) < 0)
    : null;

  // 单源模式**默认也走 isolate**。理由：`runSpider` 里的 `Promise.race` 看门狗
  // 兜不住同步死循环（计时器是宏任务，事件循环被 `while` 占住时永远轮不上），
  // 唯一杀得掉的是进程。2026-10-19 实测：`spider-check.js missav.js` 裸跑
  // 60 秒不返回，只能 SIGKILL —— 而同样的源在汇总脚本里是好的，因为它走了
  // 子进程。保护不能只装在汇总那一侧。
  //
  // `--no-isolate` 保留快路：调试时想看完整堆栈、想单步，就用它。
  const isolate = argv.indexOf('--no-isolate') < 0;

  const loaded = loadSpider(spiderPath);
  if (!loaded.ok) fail(loaded.error);

  const site = loaded.site;
  console.error('spider: ' + site.id + ' v' + site.version + ' requires=' + site.requires
    + ' hosts=' + JSON.stringify(site.hosts));

  const profile = PROFILES[site.id];
  if (!profile) {
    // 没配置**不是失败**：源可能还在别人手里，或者是个自检样本。
    console.error('这个源还没有离线回归配置（' + site.id + '）');
    const known = Object.keys(PROFILES);
    console.error('已配置：' + known.map(id => id + ' → ' + describeProfile(id)).join('\n         '));
    process.exit(0);
  }

  console.error('离线回归配置：' + describeProfile(site.id));
  if (argv.indexOf('--list') > 0) process.exit(0);

  if (isolate) {
    console.error('看门狗：每个能力独立子进程，单次上限 ' + CAP_TIMEOUT_MS + 'ms（--no-isolate 关掉）');
  }

  runSpider(spiderPath, { only, isolate }).then(result => {
    const caps = Object.keys(result.caps);
    if (caps.length === 0) {
      console.error('这个源还没有配断言（' + site.id + '）');
      process.exit(0);
    }

    let failed = 0;
    let ran = 0;

    for (const cap of caps) {
      const entry = result.caps[cap];

      if (entry.status === 'unimplemented') {
        console.error('· ' + cap + ' 未实现');
        continue;
      }
      if (entry.status === 'not-configured') {
        console.error('· ' + cap + ' 未配置');
        continue;
      }
      if (entry.status === 'error') {
        console.error('✗ ' + cap + ' 抛错：' + entry.error);
        for (const one of entry.requested || []) console.error('   请求过：' + one.url);
        failed++;
        continue;
      }
      if (dumpCap === cap) {
        ran++;
        continue;
      }

      ran++;
      for (const one of entry.checks) {
        if (one.ok) continue;
        failed++;
        console.error('✗ ' + cap + ' · ' + one.label);
        if (entry.firstFailure) {
          console.error('   实际: ' + entry.firstFailure.actual);
        }
      }
      if (entry.failed === 0) console.error('✓ ' + cap + ' ' + entry.checks.length + ' 条');
    }

    if (dumpCap) {
      // --dump 只跑那一格，把**原始输出**打出来（给补断言的人看字段长什么样）。
      // 打的是 payload 不是结果对象 —— 和改动前一致，别在这里换了形状。
      runSpider(spiderPath, { only: [dumpCap] }).then(one => {
        const entry = one.caps[dumpCap];
        if (!entry || entry.status === 'unimplemented' || entry.status === 'not-configured'
          || entry.payload === undefined) {
          console.error('· ' + dumpCap + ' 没有可 dump 的输出');
          process.exit(0);
        }
        console.log(JSON.stringify(entry.payload, null, 2));
        process.exit(failed ? 1 : 0);
      });
      return;
    }

    if (!ran) {
      console.error('这个源还没有配断言（' + site.id + '）');
      process.exit(0);
    }

    if (failed) {
      const urls = result.requested;
      if (urls.length) {
        console.error('  蜘蛛请求过的 URL：');
        for (const one of urls) console.error('   ' + one.method + ' ' + one.url);
      }
      if (result.missed.length) {
        console.error('  没有夹具的 URL（补夹具用）：');
        for (const one of result.missed) console.error('   ' + one.method + ' ' + one.url + ' —— ' + one.why);
      }
      console.error('✗ 共 ' + failed + ' 条失败');
      process.exit(1);
    }
    console.error('全部通过');
    process.exit(0);
  });
}

function fail(message) {
  console.error('✗ ' + message);
  process.exit(2);
}

if (require.main === module) {
  const argv = process.argv;
  // --child <spider.js> <cap>：子进程模式，只跑一个能力，把结果打一行 JSON 就走。
  // 父进程（runIsolated）拿 spawnSync 的 timeout 兜住整个子进程 —— 这是唯一
  // 能杀掉同步死循环的办法。
  if (argv[2] === '--child') {
    runChild(argv[3], argv[4]);
  } else {
    main(argv);
  }
}

/** 子进程：跑一个源的一个能力，结果打成 `__RESULT__{…}` 一行。 */
function runChild(spiderPath, cap) {
  const loaded = loadSpider(spiderPath);
  if (!loaded.ok) {
    console.log('__RESULT__' + JSON.stringify({ status: 'error', error: loaded.error }));
    process.exit(0);
  }
  const site = loaded.site;
  const profile = PROFILES[String(site.id || '')];
  if (!profile) {
    console.log('__RESULT__' + JSON.stringify({ status: 'not-configured', note: '未配置' }));
    process.exit(0);
  }
  if (typeof site[cap] !== 'function') {
    console.log('__RESULT__' + JSON.stringify({ status: 'unimplemented', note: '未实现' }));
    process.exit(0);
  }

  const resolver = makeResolver(profile);
  const logs = [];
  const ctx = makeCtx(resolver, logs);
  const checks = (profile.checks && profile.checks[cap]) || [];
  const args = (profile.args && profile.args[cap]) || {};

  Promise.resolve()
    .then(() => site[cap](ctx, args))
    .then(payload => {
      const out = {
        status: 'pass',
        failed: 0,
        checks: [],
        requested: resolver.requested.slice(),
        missed: resolver.missed.slice(),
        requestedList: resolver.requested.slice(),
        logs,
      };
      // payload 要跟着回来 —— 父进程的 `--dump` 打的是它。
      // JSON 能序列化就带着，带不动（循环引用之类）就算了，父进程会判
      // 「没有可 dump 的输出」，不会崩。
      try {
        const json = JSON.stringify(payload);
        out.payload = json === undefined ? null : JSON.parse(json);
      } catch (error) {
        out.payloadNote = 'payload 不能序列化成 JSON：' + error.message;
      }
      if (checks.length === 0) {
        out.status = 'not-configured';
        out.note = '无断言（只验证能跑）';
      } else {
        for (const [label, predicate] of checks) {
          let ok = false;
          try {
            ok = !!predicate(payload || {}, { requested: resolver.requested.slice(), missed: resolver.missed.slice() });
          } catch (error) {
            ok = false;
          }
          out.checks.push({ label, ok });
          if (!ok) {
            out.failed++;
            if (out.firstFailure === undefined) {
              out.firstFailure = { label, actual: String(JSON.stringify(payload)).slice(0, 240) };
            }
          }
        }
        out.status = out.failed === 0 ? 'pass' : 'fail';
      }
      console.log('__RESULT__' + JSON.stringify(out));
      process.exit(0);
    })
    .catch(error => {
      console.log('__RESULT__' + JSON.stringify({
        status: 'error',
        error: String((error && error.message) || error).split('\n')[0],
        requested: resolver.requested.slice(),
        missed: resolver.missed.slice(),
        requestedList: resolver.requested.slice(),
        logs,
      }));
      process.exit(0);
    });
}

module.exports = {
  PROFILES,
  CAPS,
  FIXTURES,
  capsFor,
  describeProfile,
  listSpiders,
  loadSpider,
  runSpider,
};
