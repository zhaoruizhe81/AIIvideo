# Spider 接入规范

给第三方开发者：写一个能在 AIIvideo 里跑起来的内容源。

**适用版本**：App ≥ v1.0.32，spider ABI = `1`（`__spiderSite.requires` 写 `">=1"`）。
当前开发版本 2.0.19，ABI 仍是 `1`。
**参考实现**：配置在 `docs/samples/spiders/*.json`（15 份，覆盖接口站/HTML 站/盾后站/
网盘站四种形态），对应的源码在 `work/android/app/src/test/resources/spiders/*.js`；
那批源都跑过离线回归（`node scripts/spider-all-check.js`，当前 14 个源全过），照着改比从零写快。

---

## 0. 先用三分钟判断你这个站属于哪一类

| 站点形态 | 你要写的东西 | 难度 |
|---|---|---|
| 有 JSON/XML 接口（苹果CMS、AList/OpenList、自建 API） | 只拼 URL 和解析 JSON，`JSON.parse` 就够了 | 低 |
| 服务端渲染的 HTML（分类页、详情页里直接有卡片） | 正则/字符串切片解析。**运行时没有 DOM** | 中 |
| 请求要登录态、要游客 cookie、要签名 | `net` 带 `headers` + `keep` 回传 cookie | 中 |
| 整站挂在 Cloudflare 的 JS 挑战后面 | 配置里声明 `web.shield`，源本身**不用管挑战** | 低（但必须声明对） |

判断依据只有一条：**你能不能用 `curl` 拿到目标页面的 HTML/JSON**。

- 能 → 正常写，本文第 3 节起。
- `curl` 拿到的是 `Just a moment...` 那种 5KB 的页面 → 盾后站点，看第 7 节。

---

## 1. 交付物：一个 json + 一个 js

```
my-site.json      # 站点配置：域名、封面规则、路由、UI 文案、源码地址
my-site.js        # 解析器：把站点数据翻成 App 认识的形状
```

安装走「设置 → 内容源 → 加源」，贴 json 的地址（或文件内容）。装源时：

1. 读 json 做校验：`origin` 必须是 HTTPS 裸域名；`cover.allowedHosts` 非空且每项只含
   合法字符；`cover.mode` 在 `plain`/`aes-cbc`/`auto` 里；加密封面还要校验 key/iv 长度；
2. 读 `web.spider` → 是 http(s) 地址就下那份 js，是纯 id 就找同目录 `<id>.js`；
3. 校验 js 大小（≤ 2MB）、能读出头部的 `id` 和 `requires`、id 形状合法、ABI 相容；
4. 落盘到 `files/spiders/<id>.js`，配置写进 `files/site.json`。

**js 单独贴进来也能装**，但只装了源码、没有站点参数，`origin` 之类的还得有配置，
所以正常流程是发 json。

`web.spider` 指向的地址要能匿名下载（App 装源时就是一个普通的 https GET，不带任何
自定义头、不带 cookie）。放在 GitHub raw、对象存储、自己的静态站都行；要求带
Referer 或登录才能下的地址装不上。同 id 再装一次是覆盖，不会留下两份。

---

## 2. 配置文件字段

```json
{
  "origin": "https://example.com",
  "headers": { "User-Agent": "…Chrome/130 Mobile…", "Referer": "{origin}/" },
  "cover": {
    "mode": "plain",
    "allowedHosts": ["img.example.com"]
  },
  "web": {
    "spider": "https://cdn.example.com/spiders/my-site.js",
    "spiderArgs": ["api"],
    "api": "https://api.example.com/provide/vod",
    "ui": { "tabs": [ { "key": "movie", "label": "电影", "icon": "film" } ] },
    "categories": { "movie": "电影" }
  }
}
```

### 2.1 必填

| 字段 | 类型 | 规则 |
|---|---|---|
| `origin` | string | **HTTPS + 裸域名**。带路径、query、userinfo、http 一律拒装。**spider 源可以留空**——它一条路径都不拼；留空时也就不用管 `web.paths.home` 那套绝对地址前提 |
| `cover.allowedHosts` | string[] | 非空，每项只能含 `[a-zA-Z0-9.-]` —— 这是封面图床的白名单，也是防 SSRF 的闸 |
| `web.spider` | string | 源码地址，或纯 id |

### 2.2 选填

| 字段 | 作用 | 不填的后果 |
|---|---|---|
| `headers` | App 侧（封面、播放取流）发的请求头，`{origin}` 会被替换成源域名。两条通道共用 `Outbound.applyHeaders` 这一个实现 | 用默认 UA，有的站会 403 |
| `cover.mode` | `plain` / `aes-cbc` / `auto` | 默认 `plain` |
| `cover.transformation` | `AES/CBC/NoPadding` / `AES/CBC/PKCS5Padding` | 加密封面必须填 |
| `cover.key` / `cover.iv` / `cover.keyEncoding` | AES 密钥（16/24/32 字节）与 IV（16 字节），编码 `text`/`base64`/`hex` | 同上 |
| `cover.hostRewrites` | `{ "旧域名": "新域名" }`，换图床时不用改源 | — |
| `cover.bytePatches` | `[{offset, from, to}]`，修坏掉的图片头 | — |
| `web.spiderArgs` | 字符串数组：把 `web` 里哪几个键抄进 spider 的 `args` | 需要站点参数的源拿不到配置 |
| `web.categories` | `{ slug: 显示名 }`，没实现 `tags` 时的类型页兜底 | 类型页可能空 |
| `web.ui.tabs` | 底部导航中间几格 `{key,label,icon}` | 用内置导航 |
| `web.ui.categoryIds/categoryLabels/categoryTitles` | tab → 分类 slug 的映射 | 分类胶囊点进去可能是空的 |
| `net` | 路由声明，见 7.1 | 跟随 App 的全局代理开关 |
| `hosts` | DNS 钉 IP `{ "域名": ["1.2.3.4"] }`，只在**直连**时生效 | 域名被污染时连不上 |
| `doh` | DoH 配置，见 `huangguo.json` | — |
| `http.hosts` | 播放层允许访问的主机（m3u8/分片在别的域时必须声明） | 播放直接报「这个源没有声明 http.hosts」 |

> **`headers` 覆盖的是全部出站请求，不只是接口。** 2026-10-21 之前播放取流那条路
> （`VlcRelay`）没接这份模板，表现是「列表能拿到、分片全 403」—— Pornhub 的
> `phncdn` 只认站点 Referer，m3u8 放行、ts 一律拒。现在两条通道都走
> `Outbound.applyHeaders`，加新通道时也必须走它，别各自抄一份。
> 详见[出站请求的两条通道](出站请求的两条通道.md)。

### 2.3 `spiderArgs`：源怎么拿到自己的配置

spider 只收到一个 `args` 对象，读不到整份配置。需要站点参数的源（苹果CMS 的接口地址
是个典型）在配置里声明要哪几个键：

```json
{ "web": { "spider": "…/cms.js", "api": "https://x/api.php/provide/vod",
           "spiderArgs": ["api"] } }
```

前端每次调用会把 `api` 抄进 args，源里直接 `args.api`。**不声明就拿不到**，而且
空值不抄（让源自己的缺省生效）。

---

## 3. JS 骨架：安装器能读懂的写法

```js
__spiderSite = {
  id: 'my-site',            // 必填，和文件名一致；装源时按它落盘
  name: '示例站',
  version: 1,
  requires: '>=1',          // 只支持 ">=N"
  hosts: ['example.com', 'api.example.com'],   // net 的白名单，必填

  async home(ctx) { … },
  async category(ctx, args) { … },
  async search(ctx, args) { … },
  async detail(ctx, args) { … },
  async play(ctx, args) { … }
};
```

四个硬要求，每一条都对应一个已经踩过的坑：

1. **必须是顶层赋值 `__spiderSite = { … }`**，不是 `window.__spiderSite =`，不是
   `module.exports`。`id` 只能是小写字母、数字、下划线、连字符，首字符不能是符号，
   最长 32 字符（`^[a-z0-9][a-z0-9_-]{0,31}$`）—— 它就是落盘的文件名。
2. **这一个对象字面量要能被文本扫描读懂**：`id / name / version / requires` 四个字段
   写在最前面、用字面量（字符串/数字），不要用变量拼、不要写成 `id: SITE.id`。
   安装器不执行源码就读这四项，读不出来直接拒装 —— 报错是「元信息读不出来」。
3. **`hosts` 是 `net` 的唯一白名单**：要请求的每个域名都得列，精确匹配、小写、
   不带协议和路径。漏一个的表现是 `net 拒绝：主机不在白名单`。
4. **每次能力调用都是一个全新的页面**。模块级变量、闭包缓存都不跨调用保留。
   要缓存就放进返回的数据里，或者接受每次重算。

---

## 4. 运行时给的 API

宿主页只暴露一个入口，页面的全局对象上有三个名字：

```js
ctx.call(op, args)   // 通用入口，返回**已解析**的对象
ctx.net(args)        // = ctx.call('net', args)
ctx.log(args)        // = ctx.call('log', args)
```

`op` 只有三个：`net` / `netAll` / `log`。**没有 `fetch`、没有 `XMLHttpRequest`、
没有 WebSocket、没有 `document`** —— 宿主页的 CSP 是
`connect-src 'none'; img-src 'none'; default-src 'none'`，出站请求只有 `net` 一条路。
想用 `new Image().src = '…?d=' + 数据` 外带也走不通，`img-src` 也是 none。

### 4.1 `net`

```js
const r = await ctx.net({
  url: 'https://example.com/list?page=1',
  method: 'GET',                       // GET（默认）/ POST；其他值（PUT/DELETE/…）会直接报错，不再静默按 POST 发
  headers: { 'User-Agent': '…' },
  body: 'a=1&b=2',                     // POST 的表单体（默认 form）
  format: 'form',                      // 'form'（默认）/ 'json'
  charset: 'utf-8',                    // 解析响应用的字符集，GBK/Big5 站必填
  keep: ['PHPSESSID']                  // 要回传哪些 Set-Cookie
});
// 成功：{ ok: true, status: 200, body: '…', cookies?: { PHPSESSID: '…' } }
```

要点：

- **只允许 https**。`http://` 直接抛 `net 拒绝：只允许 https` —— 明文会被运营商插页，
  源拿到的 body 就不是站点给的了。
- **`status` 恒为 200**。非 2xx 时 `AiiHttp` 直接抛错，报错文本是 `HTTP 404 @ url`，
  没有 `status` 字段。判失败看 `ok` 或 `try/catch`，别看 `status >= 400`。
- **`cookies` 只在你要了（`keep`）且真的收到了才出现**，不给你一个空对象。
- 单次请求超时 **15 秒**，响应体上限 **4MB**（超了报错，不截断）。
- `charset` 认 `Charset.forName` 里的一切名字（`gbk`、`gb18030`、`big5`、`shift_jis`…）。
- 表单 POST 的非 ASCII 字符会按 `charset` 转成 `%XX` 再发，老服务器能收到对的中文。
  强依赖请求头 charset 的站点仍会解错 —— 那种站请改用 `format:'json'` 或让接口返回 UTF-8。

### 4.2 `netAll`：真正的并发

**`Promise.all` 在 spider 里不是并发。** `ctx.call` 是同步桥，JS 每次都要等到 Java 回包，
写成 `Promise.all(arr.map(fetch))` 实际是一条一条排队 —— 24 条详情能把 90 秒预算烧光。

要并发就用 `netAll`，Java 侧线程池一次跑完：

```js
const res = await ctx.call('netAll', {
  requests: ids.map(id => ({ url: base + '/detail/' + id })),
  concurrency: 8
});
// { results: [ {ok:true,status:200,body}, {ok:false,error:'…'}, … ] }
```

- 上限 **30 条**，`concurrency` 上限 **8**（超了按 8 算）。
- **单项失败不抛异常**，那一位是 `{ok:false,error}`，顺序和入参一一对应 —— 按索引
  映射回自己的 id，别用返回顺序去猜。
- 子请求的参数和 `net` 完全一致（每项可以带自己的 `headers`）。

### 4.3 `log`

```js
ctx.log({ msg: '抓到 ' + list.length + ' 张卡' });
```

真机上用 `adb logcat -s HG.Spider` 就能看到，格式是 `cap=home log: …`。
**这是你唯一能用的调试输出** —— `console.log` 落在 WebView 的 console 里，没人看得到。

---

## 5. 能力（capability）

前端按动作调函数，映射是固定的：

| 前端动作 | spider 函数 | 入参 |
|---|---|---|
| 首页 | `home` | `{}` |
| 分类 / 类型 | `category` | `{ slug, page }` |
| 搜索 | `search` | `{ keyword, page }` |
| 全部类型（标签） | `tags` | `{}` |
| 点某个标签 | `tagList` | `{ slug, label, page }` |
| 详情 / 选集 | `detail` | `{ ref }` |
| 播放 | `play` | `{ ref, ep, line }` |
| 精选 / 最新 | `recommend` / `newest` | `{ page }` |
| 卡片校正（可选） | `reconcile` | `{ ids: [...] }` |

**只实现用得到的能力。** 没实现的函数，前端会明确报「这个源没有实现「详情」」，
而不是白屏。`tags` / `tagList` 是例外：能力集探测确认你没实现之后，会自动退回
用配置里的分类（`ui.categoryIds` + `categoryLabels`，没有就 `web.categories`），不报错。

### 5.1 列表页：`home` / `category` / `search` / `tagList` / `recommend` / `newest`

```js
return { items: [card, …], page: 1, hasMore: true };
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `items` | array | 卡片数组，见 5.5 |
| `page` | number | 当前页，从 1 开始；不填按 1 |
| `hasMore` | **boolean** | **必须是字面量 `true`**，`"true"` / `1` 都不算 —— 判断写的是 `=== true` |

`home` 也一样返回 `{items}`（App 自己取前 8 条当轮播、其余铺网格），不要返回
`{featured, recommend, newest}` 那种分块结构 —— 分区是 UI 的事。

翻页到最后一页时 `hasMore: false`。**宁可多给一页空的，也不要谎报有下一页**：
谎报的表现是用户一直往下滑，每滑一次多一次请求，永远到底不了。

### 5.2 `detail`

```js
return {
  id: 'fct-210',
  title: '…',
  cover: 'https://img.example.com/x.jpg',
  description: '…',
  tags: ['人妻', '中文字幕'],
  episodeCount: 12,                 // 可选：只返回一页剧集时务必填总集数
  episodes: [
    { no: 1, ref: '/cn/fct-210?ep=1', title: '第01集' },
    { no: 2, ref: '/cn/fct-210?ep=2' }
  ],
  lines: [ { line: 'default', name: '默认线路' } ]
};
```

- `episodes[].no` 从 1 开始，是选集格子上的数字。
- `episodes[].ref` 是**站内标识，不是 URL**，播放时原样回传给你（见 5.3）。
  缺了它 App 只能传 `id:epN` 给你，你的 `play` 得能兜住。
- `episodeCount` 不填就取 `episodes.length`。只回一页剧集的站必须填，否则
  「共 3 集」会是谎话。
- `lines` 是线路清单，可以只有名字（地址在播放阶段才解析）。不填给一条默认线路。

### 5.3 `play`

```js
return {
  url: 'https://cdn.example.com/hls/x.m3u8',    // 默认播这条
  alts: [                                        // 全部线路，第一条通常就是 url
    { line: 'default', name: '默认线路', url: 'https://…m3u8' },
    { line: 'hd', name: '高清', url: 'https://…m3u8' }
  ],
  episodeNumber: 1,
  reason: '',      // 播不了时给原因
  note: ''         // 给用户看的附注
};
```

三条规则：

1. **`url` 必须先出现在 `alts` 里**（或者不写 `alts`，只给一条 `url`）。对不上的话，
   用户切一次线路再切回来就换了源。
2. **`url` 和 `reason` 互斥**。给了 `reason`（比如 `encrypted`）就别把密文塞进 `url` ——
   播放器会报格式错，用户看不出真正原因。只给 `reason` 是**正确的降级**，不是失败：
   上层拿到空地址 + reason 按原因提示；真正会报错的是「既没有 url 也没有 reason」
   （`state: 'empty'`，文案是「这个源没有给出这一集的播放地址」）。
3. **地址必须是 http(s)**。`blob:`、自定义 scheme、相对路径都不算可播，
   会落到 `state: 'empty'`。

入参里的 `ref` 有两种形态，都要能解析：

- `ref === 卡片 id`（详情页 ref 和 id 相同）
- `ref === "<id>:<剧集ref>"`，或 **`"<id>:ep<n>"`**（详情没给 ref 时的兜底）

所以 `String(args.ref)` 后按**最后一个** `:` 切开最稳。

### 5.4 `tags`（可选）

```js
return { items: [ { name: '中文字幕', slug: 'chinese-sub', group: 'subtitle' } ] };
```

- `slug` 缺失时用 `name` 顶；
- `group` 缺失时归到 `other`，否则标签页会出现「标题写着 N 个标签、列表是空的」。

### 5.5 卡片字段（列表和详情共用）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | **必填且非空**。详情/播放都靠它，空了卡片点不动 |
| `title` | string | 卡片标题 |
| `cover` | string | **https 绝对地址**，主机必须在 `cover.allowedHosts` 里（2.0.29 起漏了的域会自动裸拉自愈，但别依赖——老客户端直接丢） |
| `score` | number/string | 评分，可空 |
| `tags` | string[] | 角标标签 |
| `description` | string | 简介 |
| `category` | string | 分类名 |
| `badge` | string | 右下角角标，没有集数信息时显示它 |
| `episodeCount` | number | 有就显示「共 N 集」 |
| `duration` | string | 时长，`1:16:25` 这种 |
| `playAt` | string | 没有 badge/episodeCount 时的兜底小字 |

封面只有一种「就地补全」的形态：协议相对地址 `//cdn/x.jpg` 会补成 `https:`。
`/cover/1.jpg` 这种站内相对路径**补不了**（转换层读不到你的 origin），必须给绝对地址。

---

## 6. 封面

- `cover.mode: "plain"`：原样用。
- `cover.mode: "aes-cbc"`：App 下载后按 `key/iv/transformation` 解密，
  `keyEncoding` 支持 `text`/`base64`/`hex`。
- **域名不在 `allowedHosts` 里的封面**：老客户端（< 2.0.29）直接丢掉、卡片灰块；
  新客户端（≥ 2.0.29）会对它**裸拉试一次**，验图通过就进观测集（自动信任），此后
  正常取图——图床轮换不用再改配置。名单建议照写全（老客户端只认名单），
  换图床也可以用 `hostRewrites` 把旧域映射到新域。
- **封面地址必须 https**：对非 https 直接抛「无效封面地址」，这张卡就没有封面。
  协议相对地址 `//cdn/x.jpg` 会被补成 `https:`。字面 IP、`.local`、带 userinfo/
  fragment 的地址在 2.0.29 起任何情况下都不放行（安全地板，名单洞穿不了）。

---

## 7. 网络路由与盾

### 7.1 `net`：这个源走直连还是代理

```json
"net": { "declared": true, "proxy": [] }
```

| 写法 | 含义 |
|---|---|
| 不写 `net` | 跟随 App 的全局代理开关 |
| `{"declared": true, "proxy": []}` | 显式声明「本源全直连」，不看全局开关 |
| `{"declared": true, "proxy": ["missav.live"]}` | 只有这些域走代理，其余直连 |

国内 CDN 站**必须**显式声明直连：走境外节点会被掐，表现是 45 秒超时。

`hosts`（DNS 钉 IP）只在直连时生效，是「优先试这些 IP、失败再回落 DoH/系统解析」，
不是唯一答案。域名被污染时加它，走代理的源加了没意义（域名是交给节点解析的）。

### 7.2 `web.shield`：盾后站点

`curl` 只能拿到 `Just a moment...` 的站，声明这个块就行：

```json
"web": {
  "spider": "https://…/missav.js",
  "shield": {
    "warmup": "https://missav.live/dm539/cn/new",
    "hosts": ["missav.live", "surrit.com", "fourhoi.com"],
    "cookie": "https://missav.live/"
  }
}
```

| 字段 | 说明 |
|---|---|
| `warmup` | 拿去挑战的那一页，选**最轻**的列表页 |
| `hosts` | 要过盾的域：站点本身、封面图床、视频 CDN |
| `cookie` | clearance 归属的 origin（跨进程搬运 cookie 时用） |

声明之后，**源里什么都不用改**：

1. `ctx.net` 请求 `hosts` 里的域 → 壳发现「这是盾后站点」，直接交给主进程；
2. 主进程用有窗口的 WebView 过挑战（0.5~3 秒），把真页面 HTML 取回来；
3. 那份 HTML 通过 `__shieldPages` 回填，spider 的 `net` **不发网络**、
   直接当 200 响应交给你 —— 你照常解析就行。

所以：**不要在源里写挑战检测、不要自己判 `Just a moment`、不要因为拿到挑战页就抛错**。
壳已经处理了；源里再判一次只会把好数据判成坏数据。

盾后站点的 `hosts` 白名单还要同时出现在 `__spiderSite.hosts` 里，两处都要有：
配置的 `shield.hosts` 解决「怎么过盾」，源码的 `hosts` 解决「允许请求谁」。

---

## 8. 硬限制

| 项 | 上限 | 超了会怎样 |
|---|---|---|
| 单次 `net` 响应体 | 4MB | 报错，不截断 |
| 单次 `net` 耗时 | 15 秒 | 报 `net 失败：…` |
| `netAll` 子请求数 | 30 | 只处理前 30 条 |
| `netAll` 并发 | 8 | 按 8 跑 |
| 一次能力调用总预算 | 90 秒（前端给的） | 看门狗销毁页面，报 `spider 超时（90s）`，你写的所有中间结果都没了 |
| 源码大小 | 2MB | 拒装 |
| 域名白名单 | 只放行 `__spiderSite.hosts` | `net 拒绝：主机不在白名单` |
| 协议 | 只 https | `net 拒绝：只允许 https` |

90 秒是**整次调用**的预算，不是每个请求。一页 24 条详情用逐条 `net` 是跑不完的，
必须 `netAll`。

---

## 9. 报错怎么写

抛 `Error` 就行，`message` 会原样进日志和界面：

```js
if (!cards.length) throw new Error('分类页没解析到卡片（' + html.length + ' 字节）');
```

写错误信息的三条经验（都是真机上被问过很多次「为什么是空的」换来的）：

1. **带上体量**：`html.length` 字节数，能立刻区分「拿到挑战页（5KB）」「拿到 404 页」
   「页面是空的」。
2. **带上地址**：哪个 URL 挂了。
3. **说人话，别甩栈**：`加载失败 — 站点改版了` 比 `TypeError: Cannot read property
   'match' of undefined` 有用得多。

注意区分两类失败：

- `throw Error('…')` = **这次调用失败**，界面显示错误块。
- `return { …, reason: '…' }` = **降级**，界面显示正常内容 + 一句说明（见 5.3）。

---

## 10. 怎么自测

### 10.1 离线沙箱（最快的回路，不需要手机）

```bash
cd work/android
node scripts/spider-check.js app/src/test/resources/spiders/my-site.js --list     # 只列能力
node scripts/spider-check.js app/src/test/resources/spiders/my-site.js --dump home # 看 home 原始输出
node scripts/spider-check.js app/src/test/resources/spiders/my-site.js             # 跑断言
```

沙箱里**没有 DOM**：`document` / `window` / `DOMParser` 一概不存在。用它们，这里就跑不了，
也就等于你这个源没有离线回归，只能靠真机点。

夹具放 `app/src/test/resources/<站点>/`，路由和断言写进 `scripts/spider-check.js` 的
`PROFILES`（`routes` 按正则匹配 URL，`fixtures` 按文件名对 URL）。
模型转换层可以单独验：`node scripts/model-check.js`。

### 10.2 真机看日志

```bash
adb logcat -s HG.Spider
```

几条关键行：

```
cap=__meta ok ms=354 caps=["home","category","search","detail","play"]
cap=home id=my-site src=user bytes=26750
op=net host=example.com ms=812
cap=home fail: 分类页没解析到卡片（184959 字节）
cap=home ok ms=139
```

`__meta` 是能力集自报（按函数存在性枚举），装完源它会先跑一次。

### 10.3 盾后站点专用的落盘

`files/site-dump/*.html` 是主进程过盾后取回的原始页面（最多留 8 份）。解析失败时：

```bash
adb shell ls /data/data/com.huangguo.mobile/files/site-dump/   # 需要 root 或 debuggable 包
```

拿到原文直接对着你的正则跑一遍，比反复猜「是不是站点改版了」快得多。

### 10.4 市场文件：三层引用一次走完

`market-spider.json` 是三层引用的最外层：

```
市场 {sources:[{url}]}
  → 源配置 {web:{spider: '<js 地址>'}}
    → js 源码
```

**任何一层写错，症状都是「用户点了，然后什么都没有」**，而三层里每一层单独看都是
合法 JSON。2026-10-19 就同时踩过两个：市场里写 `/admin/...`（后台界面，401）、配置里
`web.spider` 是纯 id（靠同目录猜）。

```bash
cd work/android
node scripts/market-check.js https://<你的服务器>/market-spider.json
```

它把三层全走一遍：市场能解析、`sources` 非空、每条有 name 和 https url、不是 `/admin/`
路径；源配置能 200 拿到、`web.spider` 是完整 https 地址；源码能下载、沙箱里能加载、
导出 `__spiderSite`、id 与文件名一致、`hosts` 非空、五个能力都在。

改完服务器上任何一份文件都跑一次，比在手机上点八遍快，而且能查出「点进去才知道」
的那种错。另外三个回归配合着用：

```bash
node scripts/spider-all-check.js   # 全部源离线回归（有夹具的才断言）
node scripts/spider-id-check.js    # web.spider 两种写法 → id
node scripts/model-check.js        # spider 输出 → 前端形状
```

市场里的 `desc` 是给人看的，错了一样误导人：2026-10-19 有一条写「播放地址加密（播不了）」，
而修完 `.jpg` 伪装之后已经能跑到解码器。**desc 跟着能力走，不跟印象走。**

---

## 11. 提交前检查清单

配置：

- [ ] `origin` 是 https 裸域名（无路径、无 query）
- [ ] `cover.allowedHosts` 覆盖了所有封面域名（2.0.29 起新客户端对名单外主机会自动
      裸拉自愈，漏一两个不再致命；但老客户端只认名单，仍建议写全）
- [ ] `web.spider` 指向的 js 能下载、大小 < 2MB
- [ ] 需要站点参数的源写了 `web.spiderArgs`，且配置里真有那些键
- [ ] 路由要写死的源才写 `net.declared: true`（不写就跟随 App 的全局代理开关）

源码：

- [ ] `__spiderSite = { … }` 是顶层赋值，`id/name/version/requires` 是字面量
- [ ] `hosts` 列全了所有会请求的域名（含图片、接口、播放 CDN）
- [ ] 没有用 `fetch` / `XHR` / `document` / `new Image`
- [ ] 批量请求走 `netAll`，没有用 `Promise.all` 假装并发
- [ ] 所有 `net` 的地址都是 https
- [ ] 返回的是对象，不是数组
- [ ] 列表的 `hasMore` 是布尔 `true`/`false`
- [ ] 卡片的 `id` 非空、`cover` 是 https 绝对地址
- [ ] 详情页的 `episodes[].ref` 能在 `play` 里被正确切出来
- [ ] `play` 的 `url` 落在 `alts` 里，或者干脆不写 `alts`

行为：

- [ ] 首页在真机能出卡片，`cap=home ok` 出现
- [ ] 详情页有选集，点播放能起播
- [ ] 翻到最后一页 `hasMore: false`，不会无限翻
- [ ] 断网/站点 5xx 时报错信息能看懂

上线（**做源和上市场是同一次动作**，缺这步用户看不到你的源）：

- [ ] 配置和 js 都传到 `/srv/cfg/spiders/`，旧文件先备份
- [ ] 市场清单里加了这条：后台
      `https://149.104.21.177.sslip.io/admin/sources/market-spider.json/edit`
- [ ] `node scripts/market-check.js https://149.104.21.177.sslip.io/market-spider.json` 全过
- [ ] 真机贴市场地址，勾上这条，能出首页

---

## 12. 常见故障对照表

| 现象 | 真实原因 | 改哪 |
|---|---|---|
| 装源报「元信息读不出来」 | `__spiderSite` 的四个字段不是字面量，或被前面的正则/注释带偏了扫描 | 把 id/name/version/requires 挪到最前面，纯字面量 |
| 装源报「需要更新版本的应用」 | `requires` 比 App 的 ABI 高 | 降 `requires`，或用新版本 App |
| `net 拒绝：主机不在白名单` | 请求的域名没写进 `hosts`，或写了 `https://` / 大写 / 带路径 | 只写小写域名 |
| `net 拒绝：只允许 https` | 接口是 http | 换 https 接口，或让站点支持 |
| `HTTP 404 @ …` 但浏览器能开 | 少了 `headers`（UA/Referer），或被防盗链 | 补请求头 |
| 首页空、没有报错 | `items` 为空数组，或卡片 `id` 是空串被下游丢掉 | 空列表就抛错（见 §9） |
| 一直能往下滑，翻不到底 | `hasMore` 写成了 `"true"` / `1` | 用布尔 `true` |
| 「共 3 集」但实际 12 集 | 只返回了一页剧集又没给 `episodeCount` | 填 `episodeCount` |
| 点播放报格式不支持 | `url` 是密文/相对地址/自定义 scheme | 给 http(s) 直链，或改用 `reason` |
| 切一次线路回来换源了 | `url` 不在 `alts` 里 | 让 `url` 落进 `alts` |
| 封面全是灰块 | 封面域名不在 `cover.allowedHosts`，或给了相对路径（2.0.29 起新客户端会自动裸拉自愈；先过 45 秒再刷一次，仍灰才是名单问题） | 加白名单 / 给绝对地址 |
| 拉 24 条详情超时 | 用了 `Promise.all`（实际串行） | 改 `netAll` |
| 拿到的是 5KB 的挑战页 | 盾后站点没声明 `web.shield` | 加 `shield.warmup/hosts/cookie` |
| 每次请求都慢十几秒 | 源在被盾站点上自己重试 | 删掉自己的挑战检测和重试，交给壳 |
| 日志里 `cap=xxx fail:` 但没细节 | 只 `throw` 了空错误 | 带上 URL 和字节数 |

---

## 13. 一份能跑的最小源

配置 `hello.json`：

```json
{
  "origin": "https://example.com",
  "headers": { "User-Agent": "Mozilla/5.0 (Linux; Android 14) Chrome/130 Mobile Safari/537.36" },
  "cover": { "mode": "plain", "allowedHosts": ["img.example.com"] },
  "web": {
    "spider": "https://cdn.example.com/spiders/hello.js",
    "ui": { "tabs": [ { "key": "new", "label": "最近更新", "icon": "film" } ] },
    "categories": { "new": "最近更新" }
  }
}
```

源码 `hello.js`：

```js
__spiderSite = {
  id: 'hello',
  name: '示例站',
  version: 1,
  requires: '>=1',
  hosts: ['example.com'],

  async home(ctx) {
    const r = await ctx.net({ url: 'https://example.com/api/list?page=1' });
    if (!r.ok) throw new Error('首页请求失败：' + r.error);
    const data = JSON.parse(r.body);
    const items = (data.list || []).map(it => ({
      id: String(it.id),
      title: it.title || '',
      cover: it.cover || '',
      badge: it.duration || ''
    }));
    if (!items.length) throw new Error('首页没解析到卡片（' + r.body.length + ' 字节）');
    return { items: items, page: 1, hasMore: !!data.has_more };
  },

  async category(ctx, args) {
    const page = Number(args.page) || 1;
    const r = await ctx.net({
      url: 'https://example.com/api/list?cate=' + encodeURIComponent(args.slug) + '&page=' + page
    });
    const data = JSON.parse(r.body);
    return {
      items: (data.list || []).map(it => ({ id: String(it.id), title: it.title, cover: it.cover })),
      page: page,
      hasMore: !!data.has_more
    };
  },

  async search(ctx, args) {
    const page = Number(args.page) || 1;
    const r = await ctx.net({
      url: 'https://example.com/api/search?q=' + encodeURIComponent(args.keyword) + '&page=' + page
    });
    const data = JSON.parse(r.body);
    return {
      items: (data.list || []).map(it => ({ id: String(it.id), title: it.title, cover: it.cover })),
      page: page,
      hasMore: !!data.has_more
    };
  },

  async detail(ctx, args) {
    const r = await ctx.net({ url: 'https://example.com/api/detail?id=' + encodeURIComponent(args.ref) });
    const data = JSON.parse(r.body);
    if (!data || !data.id) throw new Error('详情没拿到：' + String(r.body).slice(0, 80));
    return {
      id: String(data.id),
      title: data.title,
      cover: data.cover,
      description: data.intro || '',
      tags: data.tags || [],
      episodeCount: (data.episodes || []).length,
      episodes: (data.episodes || []).map((ep, i) => ({
        no: i + 1,
        ref: String(ep.token),
        title: ep.name || ''
      })),
      lines: [{ line: 'default', name: '默认线路' }]
    };
  },

  async play(ctx, args) {
    // ref 可能是 "<id>:<剧集ref>" 或 "<id>:ep3"：按最后一个冒号切
    const ref = String(args.ref || '');
    const cut = ref.lastIndexOf(':');
    const id = cut > 0 ? ref.slice(0, cut) : ref;
    const token = cut > 0 ? ref.slice(cut + 1) : '';

    const r = await ctx.net({
      url: 'https://example.com/api/play?id=' + encodeURIComponent(id)
         + '&token=' + encodeURIComponent(token)
    });
    const data = JSON.parse(r.body);
    if (!data.url) return { reason: 'no-source', note: '这一集源站没有地址' };
    return {
      url: data.url,
      alts: [{ line: 'default', name: '默认线路', url: data.url }],
      episodeNumber: Number(args.ep) || 1
    };
  }
};
```

跑一遍：

```bash
node work/android/scripts/spider-check.js path/to/hello.js --dump home
```

---

## 14. 还有问题

- 契约的底层细节（宿主页 CSP、bridge 编码为什么只有一层、ABI 门禁）见
  `docs/history/2026-10-19-spider阶段0契约.md`；
- 运行时为什么这么设计（进程隔离、看门狗、为什么不给 DOM）见
  `docs/history/2026-10-19-spider运行时设计.md`；
- 站点配置的通用字段（不限于 spider 源）见 `docs/站点配置参考.md`。
