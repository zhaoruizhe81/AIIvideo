# 待办

> **2026-10-18 分发链路变了，本文里凡是提到分发方式的段落都可能过时。**
> 热更清单、热更包、APK 清单、免费节点池这四样全部搬出自己的服务器，改走
> GitHub + gh 镜像；自己那台现在只剩账号 API。当前状态以
> [`docs/开发说明.md`](docs/开发说明.md) 的「发版清单」和
> [`docs/热更新说明.md`](docs/热更新说明.md) 为准。
>
> 本文是按时间累积的开发记录，**早期段落保留当时的写法，不回改** —— 那是
> 历史。看的时候注意每段的日期。哪份文档是现行口径、哪份只剩历史价值，
> 看 [`docs/README.md`](docs/README.md)。

## 2026-10-31 深夜：封面信任模型改造落地（P1+P2+P3）

「封面灰块」按当晚评审通过的方案（`docs/history/2026-10-31-封面信任模型改造方案.md`）
实施了主体三阶段。核心变化：`allowedHosts` 从「唯一的门」降级为「安全地板」——名单外
的封面主机先用**裸请求**（只带 `Referer: <源 origin>`，绝不带 cookie/源 UA）试拉一次，
验图通过就进按源隔离的观测集（上限 32 条、7 天未命中过期、连续 3 次失败降回待验证），
此后带源 header 正常取——图床轮换从此不用任何人跟。配套：缓存键自适应易变参数剔除
（`CoverParamMemory`，同 path 出现第二个不同值当场判易变，七名种子之上增量）；
`cover-diag` 桥命令 + debug 构建的「封面诊断」页（观测集 / 最近失败 / 冻结中三块）；
前端灰块按页面计数合并上报 `cover_placeholder`。顺手修掉负缓存跨源不清的旧账
（`resetCoverFailureCache` 挂进装源 / 清源 / 恢复出厂）。

验证（本机）：单测 **350 个 0 失败**（41 个测试类，+3：`CoverTrustStoreTest` /
`CoverTierTest` / `CoverParamMemoryTest`），`./scripts/build.sh verify` 通过，
APK 39.1 MB。线上配置一个字没动（方案 §九：先实现验证，后发版）。
**待办：真机 7f8f5254 按 §七 清单跑模拟轮换 / 换源清空 / 断网归因三项（debug 包看
诊断页），过了再出 2.0.29。**

## 2026-10-31 夜：全量代码审计，44 条真实 bug，修掉 42 条

对 `work/android` 全部 56 个主源码文件做了一轮逐行审计（4 路并行：网络、播放、
spider、基础设施；关键条目由主审逐一对照源码复核），确认 **44 条真实 bug**：
高危 6、中危 16、其余为低危。随后 4 个常驻队友按互斥写范围并行修复，当晚收口。

装机验证：`2.0.28`（20028）19:35 覆盖安装到真机 7f8f5254（原 2.0.27，数据保留），
冷启动正常；`compileDebugJavaWithJavac` + `testDebugUnitTest` 全绿，单测
**312 个 0 失败**（38 个测试类，较此前 +1 类：BackGestureTest 重写扩边值用例）。
装完测试就撞上了下一节的封面问题 —— 修出来的包先踩了坑。

### 高危 6 条（都是「功能在但方向反了」）

| 位置 | 症状 |
|---|---|
| `MainActivity.dark()` | 条件反转：跟随系统恒深色，用户显式选浅色被无视 |
| `VlcRelay` 播放列表改写分支 | Content-Length 用字符数、body 按 UTF-8 字节写，带中文标题的列表被 VLC 截尾 |
| `VlcRelay.writeHeadAs` | 上游无 Content-Length 时声明 `chunked`，body 却是裸流直写，严格解 chunk 必炸 |
| `AiiHttp` CLIENTS 缓存键 | DNS 只记 `getSimpleName()`（恒 `"SmartDns"`），源 A 的钉死 hosts/DoH 会被源 B 复用 —— 换源后解析到错 IP 的「串源」 |
| `ProxyCore.sameNode/copy` | 漏 `publicKey/shortId/allowInsecure/upMbps/downMbps` 等：reality/hy2 节点换源 reapplyRouting() 用缺参配置重启核心，握手必败 |
| `SpiderRuntime.driverJs` | Promise 没有 `.catch`，源里异步 throw 被掩盖成「15 秒超时」并触发看门狗销毁页面 |

### 中危里值得记的

- **ReportQueue**：claimFiles 把目录里全部 `.jsonl` 改名 `.sending`，但单批只消费
  200 条 —— 超出的滞留 `.sending`，本进程内永不发送（recoverSending 只在启动跑）。
  修法是溢出部分当场 release 回去，`release()` 顺手加 `isFile()` 守卫防恢复路径刷假警。
- **SpiderClient**：`:spider` 进程被杀后 `bindAttempted` 不复位、`bound` 闩锁已开，
  重连窗口内所有调用立刻报「绑定失败」；`onBindingDied` 不 unbind 还漏 binder 注册。
  两个回调统一「try-unbind → connection=null → bindAttempted=false」。
- **AppStore.applyCloudSnapshot**：bookmarks 整份照抄云端不走墓碑（history 那半走了），
  多设备丢本地未上云收藏。补了对称的墓碑合并。
- **XrayConfig**：分享链接 `sni=` 直接覆写 `node.host`，ws 的 Host 头被顶掉。Node 新增
  `sni` 字段分家，生成侧三处 serverName 收口到 `serverNameOf()`（sni 优先 → host → server）。
- 另有 GeoIp 的 HashMap 跨线程读写、SocksBridge accept 单次异常永久退出、
  CmsXml 两条 `$$$` 列表计数源不一致（线路名和地址错位）、封面缓存键漏 query、
  WebView 调试开关无条件打开、EXIF 270° 被错位成 libVLC 常量 267 等。

### 与审计单不同的三处落地（都有证据，记下防止后人按旧结论改回去）

1. **sni-only 链接保留回填 host**：审计原案「sni 完全不碰 host」会让 4 条现有单测变红
   （`ShareLinkTest` 等），实际改成「host 已有值绝不覆写、为空才用 sni 补缺省」——
   bug 场景（host 与 sni 同现）已修死，sni-only 行为不变。ss 密码 percent-encode
   顺带在 `parseShadowsocks` 补了 percentDecode 闭环，否则编码后的密码到对端是 `%40` 字面量。
2. **BackGesture 的边语义**：审计引的「1=左缘/2=右缘」来自恢复时写错的那句注释。
   platform jar `javap` 实证 `EDGE_LEFT=0 / EDGE_RIGHT=1`——只删了不可达的
   `swipeEdge == 2` 短路、修正注释，travelled 公式按真实语义保留（照审计字面改会把
   左缘手势进度钉死 0）。
3. **0x510 的构成**：`LAYOUT_STABLE | LAYOUT_FULLSCREEN | LIGHT_NAVIGATION_BAR`，
   不是审计括号里写的那组（算术对不上）；深色分支取 0，浅色挂旗标，原逻辑如此。

### 跳过 1 条 + Lead 收尾修 2 条

- 跳过：ReportQueue 两条「已发送重发」路径 —— 服务端按条去重可容忍，文件头注释已
  声明该权衡，改动风险大于收益。
- Lead 收尾：spider 组留了两个会卡编译的尾巴当场修掉 —— `URL.getRawQuery()` 不存在
  （改 `getQuery()`）、`SpiderStore` 把 `tmp.delete()` 放进 finally 导致成功路径
  rename 前就删临时文件（改为仅失败路径删）。

修法与逐条 diff 都在工作区（29 文件 +508/-135，未提交）。这轮另发现：封面缓存键
补 query 之后，当晚又被下一节的 `cacheKeyQuery()` 签名剔除叠了一层 —— 现在的键是
「query 算键、签名参数先剔掉」，两层不冲突。

## 2026-10-31 晚：黄果没有封面，三件事叠在一起

用户报「黄果短剧没有封面」。真机（7f8f5254，2.0.28）上确实是整页占位首字
（神/高/女/少/失/时），连 banner 都是空渐变。重启 App 立刻全好 —— 一句话
说明**源、密钥、接口都没坏**，坏的是「一次失败之后不肯再试」。

拆开是三层：

### 一、封面主机在轮换，白名单没跟上（一部分设备永远没封面）

同一族加密图床在 51cg / mrds66 / 黄果之间共用，二级域**六个随机字符**换来换去。
遥测里近 14 天被拒的主机名，按源排一遍就看清了：

| 源 | 被拒主机 | 次数 / 设备 |
|---|---|---|
| mrds66 | `pic.gyezbp.cn` / `pic.nihmcl.cn` | 139/61、61/27 |
| huangguo | `pic.sugfew.cn` / `pic.gyezbp.cn` | 16/4、10/5 |
| porn91 | `pic.gyezbp.cn` / `pic.drleao.cn` | 7/5、7/4 |
| missav / yhdm / 51cg | `pic.drleao.cn` 等 | 各 4~13 |

而白名单是**精确匹配**，`SiteProfile.coverUrl()` 对表外域名直接抛
`无效封面域名：xxx（白名单：[…]）`，不走降级。所以每轮换一次，就得挨个源补一次；
没补上的那批设备，封面是**永久**灰的 —— 它们不会自己好。

当场把三个 aes-cbc 源的线上配置补齐（`/srv/cfg/spiders/*.json`，各留
`*.bak-cover-<时间戳>`），并给 `allowedHosts` 加了**单层通配**：
`pic.*.cn` 放行 `pic.sugfew.cn`，不放行 `pic.a.b.cn`（跨层）、
`evilpic.sugfew.cn`（前缀不对）、`*.cn` / `pic.*` / `pic.*.*.cn`（模式本身非法，
加载源时就拒）。以后轮换不用再跟。

线上配置暂时**只写精确主机**：通配语法要 2.0.29 才认，老版本读到 `pic.*.cn`
会把整份源判成非法 —— 得等 2.0.29 铺开再换。

### 二、一次网络抖动 = 整页封面黑五分钟（这台设备的现象）

`rememberFailure()` 里 URL 级和主机级用的是同一个 5 分钟。主机级那一层的
本意是「同一个配置错误别报 34 次」，可它对**网络类**失败也照冻：

```
19:40:27 E/AIIvideo: cover failed
19:40:27 E/AIIvideo: CoverFailure: 封面主机刚刚失败过（pic.drleao.cn），本轮不再重复上报
```

三秒一条，一条真请求都不发。那会儿手机直连 `pic.drleao.cn` 已经是
`code=200 size=174816 tls=0.123`（adb shell curl 验的）—— 网络早好了，界面还黑着。

改法：主机级冻结期**按失败类型分档**。配置类（白名单 / 格式 / 密钥）重试一万次
结果一样，继续冻满 5 分钟；网络类只冻 `FAIL_HOST_NET_MS = 45 秒`。另外补了
`forgetFailure()` —— 以前成功之后主机记录还挂着，白挂到 TTL 到期。

### 三、缓存键每 90 分钟整体作废，等于每 90 分钟重下一次图床

封面 URL 上挂着 `?auth_key=<过期戳>-0-0-<md5>`，实测约 90 分钟换一批。缓存键
把整个 query 算进去了，所以每一轮签名都让全部封面 miss 一次 —— 每多下一遍，
就多一次「运气不好撞上抖动 → 整页黑掉」的机会。

改成剔掉签名参数（`auth_key` / `sign` / `signature` / `token` / `expires` /
`ossaccesskeyid` / `x-oss-credential`），其余业务参数照留。签名只决定「给不给你看」，
不决定「是哪张图」。

顺带修了同一段里的两处：

- `Files.readAllBytes()` 的 ENOENT。`isFile()` 和读之间文件被 `trimCoverCache`
  删掉了，报成 FileNotFoundException 进上报 —— 线上那 70 条（同一个 uid 150 的
  设备）就是这么来的，而它其实是「缓存没了，重抓就行」。
- banner 的相对路径拼 `PIC` 常量（`https://pic.drleao.cn`）这一层没动：接口
  目前所有相对路径都由它给，四个活主机里它也在（`drleao` / `gyezbp` / `sugfew` /
  `nihmcl`；`fisawck` / `tkzdds` / `wirqed` 已死）。

### 验证

- 单测 322 条全过（311 → 322：`CoverFailureCacheTest` +4、`CoverHostWhitelistTest` +7）
- 真机 7f8f5254（2.0.29）：黄果首页 banner + 列表封面全部出图
- 线上配置：`pic.sugfew.cn` / `pic.nihmcl.cn` / `pic.jtzatly.cn` / `pic.gyezbp.cn`
  都 `code=200`（从一个真实封面路径试的）


## 2026-10-31 下午：hy2 接上了（订阅里的「1 个不支持」）

订阅里一直有一条 `type: hysteria2`，UI 上显示成「1 个不支持（hysteria2）」。那是我们的
**主线路** —— `64.64.239.223:24443/udp`，UDP 直连，不像 TCP 那样被封。它被丢掉了一周多，
客户端实际只能走 relay（149 中转）或 CF 边缘（丢包 40%~70%）。

### 为什么会被当成「不支持」

三处协议白名单都只写了 http/socks5/ss/trojan/vmess/vless，`hy2://` 一个都不认：

| 位置 | 症状 |
|---|---|
| `Subscription.looksLikeLink` | 链接在进解析器**之前**就被判掉，记成 `unsupported`（名字是 `hy2`） |
| `Subscription.containsScheme` | 一份**只有** hy2 的订阅会被当成「内容既不是节点列表也不是 base64」 |
| `XrayConfig.parseShareLinkBody` | 根本没有 `hy2://` 分支 |

而核心其实一直支持：`app/libs/libv2ray.aar` 里是 v2fly v2ray-core v5，带
`proxy/hysteria2` 出站（`proxy/hysteria2/config.proto`、`v2ray.core.proxy.hysteria2`）。
是**我们自己没接**。

### 核心里还有两个键名坑（都踩了）

1. **`settings.servers`（复数）**，不是 proto 里那个 `server`。走的是 `infra/conf/v4`
   那层，那里写的是 `Hysteria2ClientConfig struct { Servers []*Hysteria2ServerTarget
   `json:"servers"` }`。写成 `server` 报 `0 Hysteria2 server configured.`
2. **`streamSettings.hy2Settings`**，不是 `hysteria2Settings`。`StreamConfig` 里是
   `Hy2Settings *Hy2Config `json:"hy2Settings"``。写成全名不报错 —— 键被静默忽略，
   `password` 变空串，服务端回 404（hysteria 2 认证失败的伪装），日志是
   `proxy/hysteria2: authentication error, HTTP status code: 404`。

这两个都是一个字一个字试出来的，单元测试现在锁着（`XrayHysteria2Test`）。

### 服务器：加了一条不带混淆的入站

核心的 `transport/internet/hysteria2` 只有 password / congestion /
ignore_client_bandwidth / use_udp_extension 四项，**没有 obfs**。而现有 hysteria 服务端
全局开了 `obfs: salamander`，所以另起一个实例：

```yaml
# /etc/hysteria/config-plain.yaml —— listen: :24444，没有 obfs 段，其余同 24443
```

`hysteria-plain.service`（`systemctl enable --now`），`firewall-cmd --add-port=24444/udp
--permanent`（这次记得加 `--permanent`，2053 那次 reload 冲掉过一回）。
24443 带混淆的原样不动，mihomo / sing-box 照旧用它。

订阅 `使用/for-friend.yaml` 加 `hy2-plain`，同时塞进 `AUTO`（hy2 之后）和 `PROXY`；
已推到 `149:/root/clash/XGl1wBMRf7QwfWj7.yaml`（备份 `.bak-20261031-…`）。

### 验证（真机 7f8f5254 / 23113RKC6C）

```
订阅 → 9 个节点，1 个不支持（hysteria2(salamander)）    ← 带混淆那条仍被明确记账
测速 → 测了 9 个，通了 2 个
        164ms  美国  hy2-plain      ← 主线路
        204ms  加拿大 cf-ws-ip2
curl -x socks5h://127.0.0.1:<core port> https://www.google.com/generate_204
        → 204 / 0.36~0.40s × 3
```

服务器侧对照：官方 hysteria 客户端连 24444，`204 / 0.036s`；手机那条在服务端日志里是
`client connected {"id": "user"}`。

### 顺带

- `SpeedTest.shortLink` 对 hy2 链接显示成「6」—— 它按 `@` 切掉前半段，而 hy2 的
  password 在 `@` 前面且没有别的分隔符，切完只剩尾巴。只影响日志可读性，没动。
- 带 `obfs` 的节点现在会明确记成 `hysteria2(salamander)` 而不是静默丢掉 —— 用户至少
  知道「这条在订阅里有、但内置核心用不了」，而不是「我的机场少了条节点」。

## 2026-10-31 中午：测速的「延迟」不是节点的延迟

用户想起来「测试节点延迟不是真的延迟」，查下来确有其事，而且底下压着两个 bug。

### 一、探针请求被 `geosite:cn → direct` 截去直连

`ProxyCore.PROBE_URL` 是 `https://www.gstatic.com/generate_204`，而 `gstatic.com`
在 geosite 的 **cn** 分类里（和 `gstatic.cn`、`gstaticcnapps.cn` 同组，属 Google 中国
那批）。`XrayConfig.routing()` 的 ③ 是 `geosite:cn → direct`，于是
`Libv2ray.measureOutboundDelay` 里的 `core.Dial(ctx, inst, dest)` 直接把探针送去直连。

真机证据（`D/VLC-std` 那个 tag 就是 Xray 的 access log）：

```
11:58:50 tcp:127.0.0.1:40456 accepted tcp:www.gstatic.com:443 [direct]
11:58:51 tcp:127.0.0.1:40470 accepted tcp:www.gstatic.com:443 [direct]
```

同一时刻同一个核心里，`//missav.live:443` 和 `//content-autofill.googleapis.com:443`
都是 `[proxy]` —— 只有 gstatic 被截。

国内直连 gstatic 是**通的**（联通缓存 114.250.64.34，204 只要 0.21~0.23s），AAR 里
`measureInstDelay` 再发两次取较小值、第二次复用 keep-alive 只剩纯 RTT —— 落到界面上
就是那个数字。于是 8 个不同国家的节点连报 `84 / 85 / 85 / 88 / 84`，全都一样。

后果不止数字难看：**节点全坏也全绿灯**。11:56:00 测速 6 个全通，11:56:08 真正启动后
每一条出站都是 `failed to dial to (wss://zhaoruizhe.site/): 404 Not Found`。

修法两条，都做了：

| 改动 | 位置 |
|---|---|
| 新增 `XrayConfig.buildProbe/buildProbeString`，routing 里**只有一条**「所有流量 → proxy」，不加载 geosite / DirectHosts / 源声明 | `XrayConfig.java` |
| `ProxyCore.probe` 改用它 | `ProxyCore.java` |
| `PROBE_URL` 换成 `https://www.google.com/generate_204`（不在 geosite:cn，国内直连必不通 —— 哪天规则又漏回来，表现是「测不通」而不是「假的通」） | `ProxyCore.java` |

### 二、Clash 订阅的 `ws-opts` 行内写法没解析，path 丢了

订阅里 8 条节点全是这个写法：

```yaml
ws-opts: {path: /ws, headers: {Host: zhaoruizhe.site}}
```

`Subscription.clashToLinks` 只认「整行 key: value」，这一行进去只有一个键 `ws-opts`，
值是整段花括号文本（注释里写的「嵌套层会被摊平成顶层键」只对**多行缩进**形式成立）。
`pick(p, "path")` 于是取空，拼出的链接没有 `&path=`，Xray 按默认 `/` 去拨
`wss://zhaoruizhe.site/`。服务器上 nginx 只有 `location /ws` 且 `$http_upgrade !=
"websocket"` 就 `return 404` —— **每一条 ws 节点都是 404**。

这是「测速绿灯但用不了」的另一半：就算把探针修好，path 不修也是全红。

修法：`clashToLinks` 遇 `{` 开头的值走新的 `putFlow`，按花括号深度切逗号递归摊平，
叶键名（`path` / `Host`）和全名（`ws-opts.path` / `headers.Host`）都进 map。

### 验证（真机 7f8f5254 / 23113RKC6C）

测速数字从「全一样」变成真实分层：

```
改前  8 个订阅节点  84 / 85 / 85 / 88 / 84 / …            全是直连 gstatic
改后  7 个免费节点  192 美国洛杉矶 / 205 罗马尼亚 / 209 荷兰 / 254 / 308 / 365 / 超时
改后  8 个订阅节点  超时 ×6、不通 ×1、262ms（cf-ws-ip2）
```

地理排序对了（美西 < 荷兰 < 罗马尼亚），免费节点还测出一条真超时。订阅那边只剩
`cf-ws-ip2` 一条能通 —— 那是 Cloudflare 边缘按 IP 概率丢包（`P/vps/ops-notes-2026-10-30.md`
记着 40%~70%），不是 App 的问题。源站本地 `curl -sk --http1.1` 带 upgrade 头拿 `101`，
不带拿 `404`，配置本身是好的。

端到端：点 `cf-ws-ip2` 启动后，通过本地 socks 连 google 三次全 `204`，Xray 日志：

```
12:09:06 tcp:127.0.0.1:44468 accepted tcp:www.google.com:443 [proxy]
```

**`[proxy]` 而不是 `[direct]`** —— 和改前那张日志正好对照。

单测 302 条全过（新增 `XrayProbeConfigTest` 5 条 + `SubscriptionClashTest` 3 条）。

### 还没做

- 这两个改动要发版才到用户手上。**没发热更** —— `buildProbeString` 和 `putFlow` 都在
  Java 侧，热更换不了。
- CF 边缘丢包是环境问题，跟这次改动无关。`cf-ws-ip1/2/3` 需要定期扫干净 IP 换进去。

## 2026-10-31 上午：发版后第一次读数

读数全文在 [`server/track/reports/2026-10-31-读数.md`](server/track/reports/2026-10-31-读数.md)。
窗口 10-23 22:05 → 10-31 11:46，60,147 条 / 1,079 个 device_id / 8,098 会话。

**三项改动都验到了线上效果**：crash 从 10-29 起归零（全库最后一条就是修掉的那条
`10-28 18:45 session 无效或已过期`）；hanime1 的 403 从每天 200 条量级掉到 18 条，
2.0.27 的 133 台用 hanime1 的设备里只剩 1 台还在 403（其余是没重拉源的老安装）；
reconcile 那条从 48 条/天降到 1 条/天。失败率跟着从 24% 掉到 19.3%，是上线以来第一次
跌破 20%。10-30 单日 312 台 / 9,721 条 / 214 台新安装，三项都是新高。

**新冒出来四件事，按优先级：**

1. **mrds66 图床又换到 `pic.gyezbp.cn`（156 条 / 66 台）—— 已补，等用户重装源。**
   10-28 刚补的 `pic.nihmcl.cn` 三天就过时。改前先抓了首页核对：两个站点的封面
   **现在全在 gyezbp**（51cg 90 张、mrds66 100 张，零例外），文件是密文，同一套
   key/iv 解出来分别是 `GIF89a`（368×340）和 `ffd8ffdb`（1300×640 JPEG）。
   `pic.drleao.cn`（23 条 / 13 台）是 51cg 的历史图床，当前首页一张都没有，一起写进
   白名单当防守。改了 `mrds66.json` 的 `allowedHosts`、`mrds66.js` 第 533 行的 `hosts`、
   `51cg.json` 的 `allowedHosts`，备份 `*.bak-20261031-035303`；`json.load`、`node --check`、
   公网分发抓取、`market-check` 13 条全过。用户重装一次 mrds66 才生效。
2. **missav 出帧率掉到 10%**（80 起播 / 8 出帧，10-28 还有 21%），71 条
   `vlc-error EncounteredError`。cms 10.7%、dozagonza 43.3% 是同类。要真机看 VLC 详情。
3. **盾机制自己在报错：1,347 条 / 678 台**，几乎每台用过盾后源的设备都撞过。
   形态是 `passLock` 全局串行排队超时、15 秒预算掐断（「预算已交给主进程」）、
   `ensureWarm` 试满三轮的兜底文案。两件要分开修：**文案**（「没通过站点的访问验证
   （Cloudflare）」兜住了所有原因，100 台「被 Cloudflare 拦」是假象）和 **`source_id`**
   （这条路径上报的是当前源而不是发起源，`source_id=mrds66` 的 49 条文案里写着 hanime1）。
   机制层面，hanime1 553 个会话里 157 个撞盾失败、112 个靠重试救回页面，但只有 9 个
   最终播出来 —— 重试救得回页面，救不回播放。
4. **黄果 `/api/home` 重定向 21 次**（`Too many follow-up requests: 21`，147 条 / 12 台）。

顺带记两条口径问题：`command_cancelled` 8,700 条占三天事件量的 45%，读活跃度别用它；
`play_error` 里仍有假源 `hongguo`（16 条 `stage=resolve`），是那个一直没动的
`contentSource` 状态机。

## 2026-10-29 凌晨：埋点归因 → 两个真 bug + hanime1 改盾后

数据在 [`server/track/reports/2026-10-28-读数.md`](server/track/reports/2026-10-28-读数.md)。
六天 35,124 条事件 / 640 个 device_id，10-28 一天 210 台设备、9,303 条，创上线以来新高。
真失败率 23.9%（昨天 24.1%，稳住了），但**出帧率从 81.6% 掉到 73.5%**。

逐条对着看下来，能归因的分三类，这一轮把「我们这边能动的」都动了。

### 一、reconcile 对没有 origin 的源硬拼相对路径（前端，已修）

线上 56 条 / 5 台，**跨 2.0.21 / 2.0.23 / 2.0.26 三个版本都在报** —— 从来没修过：

```
Expected URL scheme 'http' or 'https' but no scheme was found for /api/reconcile?ids=…
```

`app.js` 的 `reconcileCards()` 分两支：spider 源走 `spider call cap=reconcile`，
REST 源走 `bridgeCall('detail', { id: '/api/reconcile?ids=…' })`。后者给的是**纯路径**，
得靠 `origin` 拼成地址。而 2026-10-16 起允许「`origin` 留空、paths 全写绝对地址」这种
配置形态（接口开在另一个域名的那类源，比如 AGE 的 `api.agedm.io`），这种源拼不出来，
OkHttp 当场抛。

后果比多一条错误严重：`catch` 里是 `return`，所以这批源的卡片标题/封面错位
**永远不校正**；而且它不是「源没有实现 reconcile」（进不了 `reconcileUnsupported`），
是个确定性失败，于是每次进网格页重报一遍。这批事件的 `source_id` 也是空的 ——
源身份都没建立起来，在更早一步就失败了。

修法：`restReconcileBroken` 会话级开关。撞上 `Expected URL scheme` / `no scheme was found`
就置位，之后 REST 那条路直接 `return`，不再发注定失败的请求、也不再刷错误。
治本（给 reconcile 传绝对基址）要前端先拿到 origin，那是加一个桥命令的事，
这轮不做 —— 这批源本来就没有 `/api/reconcile` 可问，跳过就是正确行为。

### 二、清空收藏/历史时同步失败会把 App 崩掉（Java，已修）

`MainActivity` 的 `clear-bookmarks` 和 `clear-history` 在后台线程里同步账号，
而 catch 是 `throw new RuntimeException(e)` —— 直接抛到 `UncaughtExceptionHandler`。
线上 2.0.26 唯一一条真崩溃就是它（10-28 18:45）：

```
com.parse.ParseRequest$ParseRequestException: session 无效或已过期
  at MainActivity$15.run(MainActivity.java:1091)   ← clear-history 的 Runnable
```

用户点了个「清空历史」，因为会话过期被崩掉。本地历史早就清完了，云端同步是顺带的事
（`AccountService.logout()` 里本来就是「失败就放弃」）。两处都改成记录日志、不重抛。

### 三、hanime1 改盾后源（配置，已改）

**这是这一轮唯一的功能性修复，也是最严重的问题**：568 条 / 68 台（32% 的设备），
今天还有 180 条 / 18 台。

四个版本一样多（2.0.26 236 / 2.0.23 151 / 2.0.21 104 / 2.0.20 77）—— **跨版本一致
就不是哪一版改坏的**。2026-10-17 那次实测 okhttp 是过的（Conscrypt 和 Chrome 同族的
TLS 指纹），现在从境外机房带完整安卓 Chrome 头复测是 **403 / 0.06 秒秒拒**，换 UA 无效。
CF 那侧收紧了，绑的是 JA3/JA4，okhttp 那层改不了。

所以照 missav / iwara 的样子声明盾后：

```json
"web": { "spider": "…/hanime1.js",
  "shield": { "warmup": "https://hanime1.me/",
              "hosts": ["hanime1.me"], "cookie": "https://hanime1.me/" } }
```

机制在 `SpiderRuntime` 的 net 里：`isShielded(url)` 为真就**跳过 okhttp**，
抛 `ShieldSignal` 给主进程，`SiteFetchWebView.fetchPage()` 用真 WebView 过挑战，
把 HTML 回填进 `__shieldPages`，然后重跑整个 cap —— 回填表里命中的请求直接吃正文。
所以 **hanime1.js 一行都不用改**，拦截和回填都在同一条 `ctx.call('net')` 上。

过盾判据是「页面里 `a[href]` 数量 ≥ 8，且不含 Just a moment / _cf_chl_opt」，
hanime1 首页 144 张卡，够了。`passed` 是进程级的，过一次之后同会话的导航都带
`cf_clearance`，不会每个请求都过一遍。

`srv/cfg/spiders/hanime1.json` + `hanime1.js`（只改文件头 ① 的说明），
备份 `*.bak-20261028-160541` 和 `*.bak-20261028-1605xx`。**用户要重装一次源才生效**，
而且 shield 是 2.0.21 才有的机制 —— 2.0.20 及以前的 97 台设备装了新配置也不会过盾
（它们现在本来就 403，不会更坏）。

### 已发版 2.0.27（2026-10-29 00:41）

`v2.0.27` 已上架，versionCode 20027、APK 40,995,094 字节、
sha256 `0480ac3c…01d963`。`android-update.json` 的两份（仓库 main + Release 资产）都换了，
硬校验六条逐条过。发版前那张单子：294 条单测 / 35 类全过、APK 断言过（27 个 JS 全部
ES2017 可解析、dex 1 / so 4 / assets 77）、账号后端 `/api/healthz` 有响应（281 用户）、
源市场 13 条 `market-check` 全过。

**这一版没发热更**：两处改动都不涉及新桥指令，APK 自带新 JS。代价是装了老包的人
拿不到 reconcile 那条修复，等重装一起生效 —— 反过来，如果哪天想给老包单发这条修复，
`HOT_MIN_VERSION_CODE` 得设成 20027 以下，因为改动不依赖任何新桥。

两个源的配置改动在服务器上，不随 APK 走。发布说明里已经写了「重新添加一次那个源才生效」。

### 真机验过了（23113RKC6C / 7f8f5254 / Android 16）

装的是带本轮修复的 2.0.26。hanime1 从首页到播放全程通了：

```
cap=home   撞盾（https://hanime1.me/）→ 过盾 1825ms → 回填 219054 字节 → ok ms=146
cap=detail 撞盾（…watch?v=408878）    → 过盾 1809ms → 回填 345002 字节 → ok ms=160
play_start / play_first_frame(1589ms) / play_end(21157ms)，source 全是 hanime1
```

切走再切回来照样过（缓存配置里带着 `web.shield`），说明不是一次性的。
porn91 的 `cap=reconcile netAll n=24 conc=8` 也照常工作 —— 改的是 REST 那条分支，
spider 分支一行没动。

两件没在真机上验到：mrds66 的白名单（切源时点偏了，落到 yhdm / porn91；图能解密是实测过的）、
清空历史不崩（这台设备没登录，`ParseUser.getCurrentUser()` 是 null，那个 Runnable 不执行）。

### 没动的

- **`play_error / resolve` 的 source 标签还是假源**：24 条 / 11 台，全在 2.0.26，
  只有 `resolve` 这一支还挂着 `contentSource`（恒为 `'hongguo'`）。别的阶段 811 条
  已经是对的。这是 2026-10-25 那条已知 bug 的残留尾巴 —— 修它要动 `contentSource`
  那个状态机（私密库 / 用户源双态），单独一轮，别和别的混。
- **missav / 51cg / mrds66 的 `vlc-error`**：要真机 logcat 看 VLC 的详细错误，
  光看库判断不了（51cg 的 `ms` 只有 76~294，像是地址本身不能用）。
- **51cg 的 523**：17 条 / 8 台，10-28 才出现，Cloudflare 报「源站不可达」，纯上游。

## 2026-10-28 夜里：发版之后，日志自己说出来的三件事

`v2.0.26` 已经上架（GitHub release，含 2.0.24 / 2.0.25 的全部内容）。24 小时后的
数据：**9259 条事件 / 210 台设备**，其中 2.0.26 占 **171 台**（昨天是 85 台 / 10 台）。

`js_error` 从 51 条掉到 **17 条 / 4 台**，且全是 2.0.23 及以前 —— ES2017 降级那一刀
是有效的。`boot_fail` 昨天那 2 条是模拟器（`sdk_gphone64_arm64`、`Android SDK built
for arm64`），真用户 0 条。

### 一、订阅里加了 ws 节点 ≠ 用户会用

昨天判断「reality 在手机上根本跑不起来」，处置是在订阅里加了一条 `bwh-ws-tls`。
但 App 存的是**旧的那条 reality 链接**，冷启动照着存的链接起核心，不会去订阅里重新
挑节点。结果就是：除了手动切过一次的那台，所有人的隧道照旧是死的。

10-28 全天 `connection closed` 按主机摊开：

```
（主机名在 msg 里被截断的那批）  165 条 / 43 台
hanime1.me                        87 条 / 27 台
51cg1.com                         83 条 / 23 台
www.mrds66.com                    62 条 / 17 台
cn.pornhub.com                    50 条 / 13 台
901.26702.app                     46 条 / 15 台
yhdm.one                          31 条 /  9 台
api.apibdzy.com                   22 条 / 10 台
```

全是 2.0.26。**这不是某一台设备坏了，是全网同一条死隧道。** 昨晚那份「8 台设备、
57 条」现在是一百多台、五百多条。

两条待办，缺一条都不算修好：

1. 把 `bwh-ws-tls` 提到 `clash/XGl1wBMRf7QwfWj7.yaml` 的**第一位**。现在它排在
   reality 后面，新装的、没选过节点的仍会落到 reality。
2. App 侧：存的节点连不上时，自己从订阅里换下一个可用的。现在 `proxyBoot` 只认
   `proxyStore` 里那条链接，「当前节点不行」这件事没有任何出口。

### 二、「滑得快就加载失败」—— 后台预取把前台翻页杀了

用户报「滑得快容易出『加载失败，点击重试』」。不是网络，也不是滑太快本身：

```
16:30:35.061  [spider-api] detail → ref=2026595207      ← 手指按下卡片触发的详情预取
16:30:35.163  [spider-api] detail → ref=2026593369      ← 又一次
16:30:35.186  [spider-api] category → slug=cn page=11   ← 用户真正在等的翻页
16:30:36.299  E spider failed: java.io.IOException: 被更新的请求取代
```

两个地方坏在一起：

- `content.addEventListener('pointerdown', …)` 在手指按下瞬间就发 `fetchDetail`（注释
  里写的是「滑列表时顺手按到的卡片」）。而 `SpiderClient.call` 每调一次就
  `GENERATION.incrementAndGet()`，宿主只有一条串行通道，**后到者把前面的判死**。
  一个后台预取因此能杀掉用户正在看的那次翻页。
- `loadMoreListing` 的 `catch` 不分类别一律 `listing.error = true`，于是「被自己人
  取消」在界面上变成「加载失败，点击重试」，而且这个 catch 一行日志都没有 ——
  第一次复现时 logcat 里 `AIIvideo` 是 0 条，完全查不到原因。

修法方向：给 `SpiderClient.call` 分优先级（预取不许作废旧请求、抢不到锁就静默放弃），
JS 侧把「被更新的请求取代」当取消处理、不置错误态，必要时静默重试一次。

### 三、播放埋点第一次给出能用的数

```
play_start 518（85 台）  play_first_frame 381（73 台）  play_end 496  play_error 124
首帧均值 1312ms   播完均值 22s
play_error 分阶段：vlc-error 94 / alts-exhausted 16 / resolve 13 / no-url 1
```

**首帧率 381/518 = 73.6%。** 起播没出画面的那 137 次里 124 次报了错，剩下 13 次既
没报错也没出画面 —— 这就是之前说过的「起了但一直没画面」的洞，需要一个看门狗
（`play_start` 后 N 秒没有 first_frame 就报一条 `stage=no-first-frame` 并降级/换集）。

顺带一条 crash，今天唯一一条：`com.parse.ParseRequest$ParseRequestException: session
无效或已过期`（2505APX7BC / 2.0.26）。会话过期抛到了顶层，应该被捕获成「重新登录」。

### 还开着的

- **黄果 `/api/home` 的重定向环**：`Too many follow-up requests: 21`，2.0.26 上仍在报。
  2.0.23 只加了「看清往哪绕」的观测，环本身没解。
- **missav 分类 0 张卡**（`/dmN/cn/fc2`）：源侧 slug 变了，改 js 即可，不发版。

## 2026-10-27 夜里：手机端的代理一直是死的 —— `libv2ray.aar` 不是 Xray，没有 REALITY

用户截图 `51吃瓜 请求失败 https://51cg1.com/：net 失败：connection closed`。一开始
当成站点问题，绕开 App 直接在手机上用系统自带的 `curl` 打本地 SOCKS 端口：

```
socks5h://127.0.0.1:<core 端口>
  https://www.google.com/    000   0.487s
  https://51cg1.com/         000   0.461s
  http://example.com/        empty reply 0.655s
直连对照
  https://www.google.com/    timeout 8.0s
  https://www.gstatic.com/   204   0.226s（联通缓存 114.250.69.34）
```

**所有走代理的请求全挂**，不是某一个站点。而节点是好的：在 VPS 上用同一份参数
（uuid / flow / pbk / sid / sni 全同）起 Xray 客户端连自己，`51cg1.com` 回 200 / 1.82s。
手机那条链接也逐字段核对过，没错。

抓包（`tcpdump -i eth0 'tcp port N'`）把差别摊开了，同一个手机核心、两个节点：

```
trojan :8443（普通 TLS）   手机→1527B（1440+87）  服务器→2438B   手机→30B+FIN（TLS alert）
reality :20999             手机→289B  一次        服务器→0B      服务器直接 FIN
```

289 字节不是 ClientHello。服务端 `error.log` 对应那行：

```
REALITY: processed invalid connection from …: failed to read client hello
```

**手机根本没跑 TLS。** 把核心拆出来看：

```
libgojni.so → github.com/v2fly/v2ray-core/v5（还有 v2fly/hysteria、VSign、BrowserBridge）
strings "reality" 全文件命中 1 次，且是这段单词沙拉里的碎片：
  …certainrealitystorageanotherdesktopofferedpattern…
strings "xtls-rprx-vision" 命中 0 次
```

`app/libs/libv2ray.aar` 是 v2fly 的 v2ray-core v5，**REALITY 是 Xray 独有的协议**。
v2ray 读到 `"security":"reality"` 不认识，静默按 `none` 处理 —— 于是 reality 节点被
当成裸 VLESS 去连 443。`XrayConfig` 里那句「libgojni.so 里 reality 只有 1 处字符串
命中，八成是残缺实现」早就摸到边了，但当时只是写了注释，没验证。

**这也解释了测速为什么一直给绿灯。** `Libv2ray.measureOutboundDelay` 走的是同一个
核心，reality 一样被降级，它报的 `80ms ok` 从来没到过服务器。上一轮我据此说「隧道
是通的」，是错的。

### 处置：换协议，不动核心

v2ray v5 认识 VLESS + WS + TLS。VPS 上本来就有 `vless-ws-in`（`127.0.0.1:8445`），
nginx 在 8444 上有该域名的 Let's Encrypt 证书（订阅就是从那儿拉的），所以只加了一段：

```nginx
location = /ws {
    proxy_pass http://127.0.0.1:8445;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_read_timeout 3600s;
}
```

订阅 `clash/XGl1wBMRf7QwfWj7.yaml` 里多一条 `bwh-ws-tls`（**排在 reality 后面**，
PC 的 select 分组默认还选第一条，不受影响）。手机实测：

```
gstatic 204 / 0.263s     51cg1 200 / 2.064s     youtube 200 / 2.823s
VPS access.log:  accepted tcp:51cg1.com:443 [vless-ws-in >> direct]
测速：bwh-ws-tls 82ms ok ／ bwh-usca6-reality -1 超时   ← 这才诚实
```

### 顺带修掉：51吃瓜 封面全空

站点改版，卡片封面从 `<script>loadBannerDirect('URL')</script>` 换成了声明式懒加载
`<div class="blog-background" z-bg-loader-url="URL">`（站点源码注释自己写的）。老正则
一次都不命中，`cover` 全空串，前端只能显示占位块。改法：逐条扫 `z-bg-loader-url`，
跳过广告卡那种 `…/uploads/default/other/xxx.gif` 占位图。离线跑一遍首页 21 条，
**21/21 有封面**；真机 今日吃瓜 25 张图全出。

### 还没修的

- **首页「精选推荐」的封面仍是占位。** 图片请求发出去了（`[http] GET pic.gyezbp.cn/…`
    十一条），但 `div.poster` 还是 `fallback` 状态 —— 同一个 `cover.get` 在分类页是
    好的，所以是首页那条渲染路径的问题（多半是渲染后又重渲染，把 await 回来的封面
    覆盖掉了），跟代理无关。
- **直连路径会把被污染的 IPv6 地址拿去连。** `source set` 那次失败是
    `failed to connect to 51cg1.com/2001::9df0:332 … after 12000ms` —— 手机有公网
    IPv6，`51cg1.com` 的 AAAA 是被投毒的假地址，直接拨就是 12 秒超时。`AiiHttpDns`
    该把这族 AAAA 挡掉。
- 想根治还是得换 `AndroidLibXrayLite`（真 Xray），但那要重写
    `ProxyCore` / `CoreController` / `measureOutboundDelay` 那一套。

## 2026-10-27 傍晚：2.0.26 —— 「更新订阅」那个转不完的圈

用户发来一张截图：「测速转了**一个小时**还是这样」。第一反应是测速慢，但直接在
他手机上打那次桥命令，1.9 秒就回来了：

```
speed?batch=0&source=subscription   1866ms   done=true  usable=1
  bwh-usca6-reality  90ms  ok
免费 7 个节点  3 批共 2961ms  7 个全通
```

所以不是慢，是**根本没跑**。

真凶在 `app.js` 订阅那个 handler 里：

```js
state.proxySub = Object.assign({}, prev, {
  ...
  subNote: '订阅已拉到 N 个节点，正在测速…',
  subTesting: true,          // ← 先置上
});
renderProxySettingsKeepScroll();
runSpeedTest('subscription');   // ← 而它第一句是：
                                //   if (isSub ? sub.subTesting : ...) return;
```

**它一次都不会跑。** `finally` 不执行，`subTesting` 永远停在 true，按钮永久
「测速中…」且 disabled，只有刷新页面解得开。所以那一屏挂一个小时不是夸张 ——
它本来就不会自己好。

这条「拉完顺手测一次」从 2.0.24 及以前一直是坏的。拉取本身是好的，坏的是那句
「正在测速」的承诺从来没兑现过 —— 而它长得和「真的在跑」一模一样，所以一直没人发现。

改两处：置 `subTesting: false`，让 `runSpeedTest` 自己管这个开关；把「置位 → 渲染」
之间那次 `renderProxySettingsKeepScroll()` 挪进 `try`（渲染一抛，标志位同样回不来，
是同一类故障）。

**加了一条构建期断言**（`js-errors-check.js` 第 14 条）：剥掉注释后，`app.js` 里
不许出现 `subTesting: true` / `freeTesting: true`。反证过 —— 改回 `true` 立刻红。
这一条不写下来，下次谁再"顺手"预置一下就会原样复发。

教训和 2.0.25 那条是同一个形状：**状态标志在 try 之前置位，就等于把「会不会卡死」
交给了一次可能抛异常的调用**；而一个永远转圈的按钮，看起来和「正在忙」没法区分。

---

## 2026-10-27 下午：2.0.25 —— 一条会把真因吃掉的路由重试

用户手机上 pornhub 打不开，界面给的是：

```
pornhub 请求失败 https://cn.pornhub.com/video：net 失败：请求失败：
https://cn.pornhub.com/video（两条路由都没走通，且没有留下异常）
```

**那句话是假的，而且是它自己造成的。**

`AiiHttp.routeAndSend` 里有一条「本机代理刚就绪，重试一次」的分支：

```java
if (isConnectFailure(e) && ROUTE_PROXY.equals(route) && awaitLocalProxy(...)) {
    log("本机代理刚就绪，重试一次 " + host);
    continue;          // ← first 没记，异常丢了
}
```

循环只有两轮。两轮都从这个口子 `continue` 出去，于是：
换路那条支路**一次都没走**（直连根本没试过），`first` 一直是 null，
最后落到兜底那句「两条路由都没走通，且没有留下异常」。

真机 logcat 是直接证据 —— 同一秒里那条日志出现了两次：

```
15:15:18.372  net cn.pornhub.com → 代理 profile=有 mode=builtin proxy=有
15:15:18.838  本机代理刚就绪，重试一次 cn.pornhub.com
15:15:19.286  本机代理刚就绪，重试一次 cn.pornhub.com
```

改三处：只能重试一次（`proxyWakeRetried`）；`continue` 之前先把异常记进 `first`；
兜底文案改成如实报告「试过的路由：proxy」。修完同一次失败报出来的是真因 ——
`net 失败：connection closed`。

顺带记一个**没动**的地方：`isConnectFailure` 的判据是「message 不以 `HTTP ` 开头就算
连接失败」，SSL 握手失败、connection reset、DNS 失败全算。它同时是「换路」的触发条件，
收紧会削弱直连自愈（`路由自愈` 那条），所以这次只补证据不动判据，等有数据再谈。

### 同一天的另一件事：代理节点发的是旧 IP

用户问「是代理不对吗」。是。订阅 `https://64.64.239.223.sslip.io:8444/sub` 能拉、
返回 200、解开 4 条 —— 但 4 条的地址全是 `64.64.224.121`，而那个 IP 从 10-26 起
被墙（`nc -z 64.64.224.121 443` 不通，`64.64.239.223:443` 通）。

根因在服务器：`/opt/sublink.py` 第 18 行 `HOST = '64.64.224.121'` 是写死的常量。
换 IP 时证书和 nginx 的 `server_name` 都跟着换了，唯独这一行没换 —— 而那个脚本
自己的注释还写着「为什么从 config.json 生成而不是写死：改了之后这里会自动跟上」。

修法不写死新的：`HOST` 改成从请求的 `Host` 头渲染（nginx 转的是 `Host $host`），
`<IP>.sslip.io` 再剥成裸 IP，客户端仍用裸 IP、不多一次 DNS 依赖。以后换 IP 或加域名
都不用再动它。同一天漏掉的 `/opt/light-status/clash.yaml`、`surfboard.conf`、
`/root/light-status-url.txt` 一起换掉，都留了 `.bak-20261027-ip`。

**这条教训值得单独立住：写死的常量不会报错，它只是让客户端「连上了但每个请求都死」。**
App 侧看到的是代理故障，服务器侧一切 `systemctl is-active` 都是 active。

---

## 2026-10-26：2.0.24 —— 旧 WebView 白屏的根因，和播放链路上那个一直没有数据的洞

2.0.23 发出去 21 小时，库里多了一天数据。翻的时候先看到的是这个：

```
js_error  Unexpected token .      168 条 / 1 台   app.js:14:42 等
js_error  Unexpected token '='      4 条 / 2 台   app.js:2982:21
```

两台设备（`SM-G9860` / Android 9、`V2164PA` / Android 11）的**全部**事件就是
`app_open` 加 `js_error`，一个业务事件都没有。`where` 列里是 15 个不同的文件，
每个都停在它的第一处 `?.`：

```
app.js:14:42 · app-nav.js:28:18 · app-route.js:36:17 · app-render.js:23:19
app-list.js:27:10 · app-detail.js:21:47 · app-search.js:80:38 · app-update.js:72:21
card-transition.js:16:31 · cover.js:120:34 · feed-player.js:66:47 · proxy-panel.js:364:72
setup.js:52:36 · source-market.js:252:30        + app.js:2982:21（`||=`）
```

`?.` 要 Chrome 80（2020-04），`||=` 要 Chrome 85（2020-09）。`minSdk 26` 是
Android 8.0，出厂 WebView 是 Chrome 58；国内没有 Play 商店，一大批机器的
WebView 就停在出厂版本，永远不会更新。所以这不是「报错多」，是**那部分用户
根本用不了** —— App 起来了、页面也加载了，然后每个脚本在解析阶段就死，
`app.js` 一行都没执行过。

用 acorn 对着源码验了一遍，和机型上报的坐标差 1（acorn 列 0 起、浏览器 1 起），
一个字都不差：

```
app.js  ES2017 FAIL Unexpected token (14:41)      ← 对应上报 app.js:14:42
app.js  ES2020 FAIL Unexpected token (2982:20)    ← 对应上报 app.js:2982:21
```

### 真机实测：把源装上、真播了一集

模拟器里配真源（VPS 那台 `/srv/cfg` 托管着现成的源，`handle` 直接 file_server，
所以 `https://149.104.21.177.sslip.io/spiders/dozagonza.json` 就是一份能用的配置）。
走的是 App 自己的三步（`source set?url` → `source fetch?url` 读回 `web.spider` →
`spider install`），和 setup.js 里 `addOneSource` 一模一样。

首页正常：精选推荐、分类胶囊、149 张封面全部落地。点进《芳名三九》、点第 1 集，
内置播放器起来了，VLC 解出 1920×960 并出画面。

库里收到的三段（这台设备的真数据）：

```
play_start        host=play.hhuus.com  eps=29  alts=0  mode=feed  decode=hw
play_first_frame  ms=3951   hw=true
play_end          ms=50960  pos=77680  dur=2638167  frame=true  err=false
```

**这一轮抓到我自己的一个 bug。** 第一次跑出来的 `play_end` 是
`pos: 0, finished: false`，而那一场明明放了 41 秒 —— 因为 feed 模式（现在的主路径）
根本不写 `savedPosition`：写它的是单集模式的 `handleEvent`，feed 走 `handleFeedEvent`。
`finished` 同理：feed 的 EndReached 是「置 true → 落进度 → 立刻置回 false」（要自动
滑下一集），所以退出时报的永远是 false。改成 `currentPosition()` 现读活着的那个
player，`finished` 取 `ended || sawEnd`。修完再跑一集，`pos` 从 0 变成 77680。

顺带记一个**还没有解**的现象：第二次跑的时候第 3 集过盾一直失败，播放页开了但永远
不出画面。那一场库里只有 `play_start`，后面什么都没有 —— 因为既没出帧（不报
`play_first_frame`）、VLC 也没报 EncounteredError（不报 `play_error`）。
**「点了播放、然后什么都没有」这种最像用户抱怨的场景，现在仍然只在库里留一行
`play_start`。** 该加一个「N 秒没首帧就报 `play_error stage=no-first-frame`」的看门狗，
这次没做。

### host_start / host_stop 的对账（同一台设备）

```
host_start  reason=onCreate  pid=12105  ← :spider 进程自己报
host_start  reason=onCreate  pid=14675
host_start  reason=onCreate  pid=15479
host_stop   reason=unbind    pid=6603   ← 主进程报（pid 是主进程的）
host_stop   reason=unbind    pid=15354
host_stop   reason=unbind    pid=6603
```

两处 `host_stop` 都来自真正的 `SpiderClient.shutdown()`（一次 Activity onDestroy、
一次换源重建）。剩下三次 start 没配上 stop，是因为我用 `am force-stop` 重启——
那种死法不发任何回调，谁都报不出来。所以**对账口径不能用 pid join**（两边 pid 本来
就不是同一个进程），要看 `reason`：正常用户退出是 onDestroy，系统回收也是 onDestroy，
只有强杀才留不下。

### 真机实测（Android 9 / WebView 66）

模拟器上跑了一遍 A/B，只改一个变量：同一个 APK，只把 `assets/app.js` 换回未降级的
ES2021 源码。设备是 API 28 的 `google_apis` 镜像，WebView **66.0.3359.158** —— 正是
线上那台 `SM-G9860 / Android 9` 的档位。

```
                    事件
降级后（2.0.24）     app_open ×1                        0 js_error  0 boot_fail
未降级（同一 APK）   app_open ×1  js_error ×2  boot_fail ×1
                     └─ SyntaxError  Unexpected token .
                        where = app.js:25:42        （2.0.23 线上是 14:42）
```

`25:42` 而不是 `14:42`，正好是我在文件头加的那段注释的行数 —— 同一个错误、同一列，
只是第一处 `?.` 被往下推了。也就是说线上那条报错的签名在真机上**逐字节复现**了。

降级后同一台机器上：整屏正常渲染（头部搜索、底部四格导航、设置源卡片全在），
零 `js_error`、零 `boot_fail`。

顺带把白屏兜底也在真机上验了：把 `app.js` 弄成解析不了，弹出「界面没能加载出来 /
这个版本的界面脚本没能运行…」并带上完整 UA，后台同时收到
`js|boot_fail|stage=parse`。也就是说 2.0.23 那种「只剩 app_open 的白屏」下次会自己
报出 WebView 版本，不用再靠猜。

**这一轮没验到的**：`play_*` 四个事件和 `host_start/host_stop` 的对账需要先配一个源，
而源必须从 https 地址装 —— 模拟器里配不了，所以这两条仍然只有代码路径和 jsdom 差分
的把握，没有真实数据。发版后看第一天数据。

### 改法：构建期降级到 ES2017，而不是加一道版本闸门

版本号判断两头不讨好：降级后的产物在 Chrome 55 就能跑，闸门却会按 60 把能用的
机器一起拦掉；而且它挡不住下一次别的原因造成的白屏。

所以做成**构建期降级**（`scripts/transpile-assets.js`，vendor 了 Babel
standalone + acorn，见 `work/android/tools/VENDOR.md`）：

- 首方 19 个 JS 过 Babel，`targets: chrome 58`、`sourceType: script`、
  `retainLines: true`。`vendor/` 和 `hxm/` 跳过（第三方 + 410KB wasm glue，
  本来就没有 `?.`，改它只有风险没有收益），但仍然要过 ES2017 解析断言。
- assets 只从**一个**生成目录取（`build/generated/assets/webview`）。两个 srcDir
  撞同名文件时谁赢取决于 AGP 的合并顺序 —— 那正是「某个分片没打进包」这类静默
  故障的温床。
- `retainLines` 是关键：行号漂移实测一律 `-1`（Babel 吃掉末尾换行），所以
  `js_error` 的 `where` 和堆栈里的**行**以后仍然对得上源码。列号不保证 ——
  降级需要临时变量时 Babel 会把几条语句塞进一行。

**光改语法不够。** 顺带查出四处运行期 API 也高于 Chrome 58，而且它们不是解析
失败、是「用着用着 TypeError」，更难查：

```
replaceChildren   Chrome 86  4 处   切页/返回/改倍速直接 TypeError
matchAll          Chrome 73  1 处   agedm 播放地址解析失败
Element.scrollTo  Chrome 61  1 处   点下一集没反应（feed 分页）
Object.fromEntries Chrome 73 1 处   卡片转场
replaceAll        Chrome 85  2 处   活动页文案
```

### 断言：三处，都在构建期

降级这件事坏了从来不报错，只是某天某台机器白屏。所以：

- `js-target-check.js` 按 ES2017 解析每一份产物；`transpile-assets.js` 自带
  六条自检（行号漂移、顶层声明不许丢、不许注入 `"use strict"`、ES2017 可解析、
  残留语法扫描、体积增长）。
- `check-apk.py` 从**打好的包里**抽出 `assets/**/*.js`，再验一遍。它证明的是
  「srcDir 真接上了生成目录」和「打进去的字节就是解析得动的字节」——
  `transpileAssets` 验的是自己产出的目录，这两件事它证明不了。拿它跑 2.0.23 的
  APK 会当场报 `app.js:14:42`，和生产数据一个坐标。
- `js-errors-check.js` 加 `--file/--html`，跑**生成目录**里的那份。那三条静默
  约束（`trace` 键名、不走桥、不带 `source`）验源码等于没验。

另外 `app.html` 里加了块白屏兜底面板：6 秒内 `window.__hgParsed` 没出现就把
「为什么是空的」说出来（带 UA 和更新 WebView 的指引），同时走
`AndroidHost.reportJs` 报一条 `boot_fail` —— 白屏设备即使一个业务事件都发不出，
也留下这一条。判据是**标记在不在**、不是等了多久，慢网络不会误报；那块内联
脚本必须手写 ES5（它不经过 Babel，出现一个箭头函数兜底自己就成了新的白屏），
`jscheck` 里有一条断言按 ES5 解析它。

### 第二个洞：播放链路上一条数据都没有

修上面那些的时候顺手核了一件事，结果是全库 15,932 条事件里 **`kind='play'` 是 0
条**。「播不了」是最常见的用户抱怨，而：

- `VlcPlayerActivity`（2019 行）里一条 `Report` 都没有。它自己的
  `handleEvent` 认得出 `EncounteredError`，`playbackFailureText()` 甚至能从
  `VlcRelay.recentUpstreamFailure()` 分出「上游 404」和「解不了」—— 这些话只
  说给用户看，没有一个字进库。
- `app.js` 的 `hgNativeResolve()` 收尾是 `.catch(() => {})`，而它恰好罩住了最常见
  的「播不了」：接口没给地址、给了但不是 http(s)、wasm 解不出来。
- `feed-player.js` 那三个 `trackPlayError` 只在**网页**播放器生效，而网页播放器
  现在不是默认路径。

补了四个事件，全部 `Report.event("play", ...)`，URL / 标题 / id 一律不上报
（URL 里带 token），只留 host：

```
play_start        {mode, decode, host, alts, eps}
play_first_frame  {ms, submit, hw, mode}      起播耗时 —— 「卡不卡」唯一客观的数
play_error        {stage, code, msg, mode, ms, up}
play_end          {ms, pos, dur, finished, err, frame, mode}
```

`play_error` 的 stage 是归因的唯一依据：`no-url` / `unavailable` / `init-throw` /
`vlc-error` / `alts-exhausted` / `resolve`。一次会话只报第一条 —— `EncounteredError`
会重复触发，逐条上报能让一台设备刷出几十条同一件事（2.0.20~2.0.21 那 1407 条
「封面解密结果无效」里 1359 条就是这么来的）。

服务端 `stats.py` 的 overview 加了 `play_starts / play_frames / play_errors` 和
派生出来的 `play_ok_rate`（分母为 0 时给 `None`，不是 0%）。

### 第三个：三个具体缺陷

**`无效封面地址：`（15 条 / 13 台）** —— 冒号后面是空的，`ms` 只有 0~3。
`adapters.js` 的选集适配器会给条目 `cover: ''`，而 hero 轮播取封面时没判空，
空串一路走到 `SiteProfile.coverUrl("")`，`new URI("")` 的 scheme 是 null，当场抛。
每轮 banner 重报一遍。两层都补了。

**`盾后过盾失败（主进程）：null`（19 条 / 16 台）** —— `MainActivity` 那两处裸拼
`getMessage()`。顺手把原始异常挂成 `cause`（原来拼在字符串里，`isCancellation`
和事后排查都够不着）。同一个毛病另外四处（`UserSource` / `PlayerControl` /
`ProgressSink` / `PlaybackHttpBridge`）一并换成 `AiiHttp.describe`。

**`host_stop` 缺 234 条 / 110 台** —— 原来由 `:spider` 自己在 `onDestroy` 写，
而那个进程 `canFlush=false`；进程被系统杀掉时 `onDestroy` 根本不执行。改成由
**掌握生命周期的那一方**报：`SpiderClient.shutdown()` 报 `reason=unbind`，
`onServiceDisconnected` 报 `reason=disconnected`。主进程活得比宿主久、队列也归它发。**进程内那条 `host_stop` 删掉了** —— 两边都写会一次启动配出两个终点，对账反而更乱；终点只由掌握生命周期的那一方说，`host_start` 仍留在 `SpiderService.onCreate`（那是「这个进程真的起来了」的唯一第一手证据）。

**顺带修了取消判定的误判（32 条 / 9 台）**：`CANCEL_WORDS` 是对整条 msg 做无锚点
`contains`，而 `盾后过盾失败…排队超时。；原始失败：…` 这种包装过的复合文案里也
带着取消词。改成「带 `；原始失败` 一律不算取消」。取舍方向和这个类里原有的
「先排掉超时」一致：宁可把一次真取消算成失败，也不要把真失败藏进取消。

---

## 2026-10-25：2.0.23 —— 接上 JS 异常（C 批那条一直空着）

C 批写了三条，另外两条都落了（`UncaughtExceptionHandler` 在 `HuangGuoApp`，
`play_error` 四个 stage 在 feed-player），只有中间那条 `window.onerror` /
`unhandledrejection` 一行代码都没有。核过三层：`assets/` 全树（含 `hxm/`、
`vendor/`）grep `unhandledrejection` 是 0 条；Java 侧 `onConsoleMessage` 只打
logcat，二十多个 `evaluateJavascript` 注入点没一个有错误回调；最硬的旁证是
`Report.fromFrontend` 的 kind 默认值就写着 `"js"` —— 口子挖好了，管子没接，
而全 App 唯一调 `track()` 的地方是 `feed-player.js:807` 的 `play_error`。

### 接了，但没走桥

`assets/js-errors.js` 直调新加的 `Bridge.reportJs`（`@JavascriptInterface`、
**void、不回包**），不走 `request("report", …)`。

原因是回包：桥走完会灌一句 `window.NativeCallbacks.resolve(id, …)`，而那个对象
定义在 `app.js`。`app.js` 整份没执行时这一句自己就是个 TypeError，钩子接住、
再报一次、再回包 —— 死循环。偏偏「app.js 没跑起来导致白屏」正是这条链最该抓
的故障，所以它不能有回程。

配套三件事，都写进 `js-errors.js` 文件头和 `Report` 类注释了：

1. **钩子必须是 `app.html` 里的第一个脚本**。放后面就漏掉前面所有脚本的解析
   错误。现在插在 `<title>` 之后、主题那段内联脚本之前。
2. **堆栈的键叫 `trace`，不叫 `stack`**。`Report.fit()` 只认 `trace` 和 `msg`
   两个键做收缩，用 `stack` 就是整条顶穿 `EVENT_BUDGET(3800)`、服务端 4096
   拦下进 `dead/` —— 2.0.22 刚修掉的那种「数据在看不见的地方消失」。
3. **不带 `source`**。不传才回落到 `SiteProfile` 挂上的真实源标签；传了就是拿
   前端的 `contentSource` 覆盖它，而那个值恒为 `'hongguo'`。

### 防刷：对数收敛 + 会话硬顶

封面那 1340 条是「同一个坏东西被重试 16 次」刷出来的，坏掉的渲染循环比那猛两个
数量级（每帧一条）。所以同一个 key（`stage|code|msg|where`）只在第 1、10、100、
1000 次上报，`props.seen` 带累计次数；每个页面会话硬顶 50 条，到顶只补一条带
`suppressed` 的汇总。50 远低于 `MAX_BACKLOG(600)`，撑不爆队列。

### 口径变了：JS 异常不进失败率

这是 10-24 那次「失败率 93% 是假的」的同一种误读，先堵。**JS 异常没有分母** ——
一次坏渲染循环能刷出上千条 `js_error`，背后只是「用户点了一下，这屏没渲染出来」
这一次操作。混进失败率，这个数会因为和源健康无关的原因上下跳。

落法是加法不是减法（`errors` / `noise` 两个字段语义不动，免得模板里
`errors - noise` 被减两次）：`stats.py` 新增 `FAIL_SQL` =
`code is not null and kind <> 'js' and not NOISE_SQL`，在 daily / total / sources
三处各出一列 `fails`，total 与 daily 再加一列 `js`。后台「数据」页的失败率
分子换成 `fails`，另起一行「JS 异常」单列计数，事件流加一个 `kind=js` 过滤钮。
**js 事件照样进「失败排行」** —— 那才是真正要看它的地方。

### 怎么验的

- `node scripts/js-errors-check.js`：39 条断言，跑在假 window 上。钉住三条静默
  约束（trace 键名、不走桥、不带 source）、四种 reason 形态（Error / 字符串 /
  `undefined` / 循环引用）、资源 404 不算 JS 异常、30 次同错只发 2 条、200 条
  不同错误被截到 50 条、钩子自己抛异常不外泄。接进了 `build.sh verify` 和新增的
  `build.sh jscheck`。
- `ReportTest` 新增 2 条（16 条全过）：JS 堆栈放 `trace` 时整条在预算内且裁完
  还剩函数名；不带 source 时回落到当前源。
- `check-apk.py` 的 `REQUIRED` 加 `assets/js-errors.js`。
- 后台模板用假数据渲染过一遍：失败数字取 `fails`、JS 单列、过滤钮到位。

真机那条还没跑 —— 当前没有设备在线。`diag` 加了两个自检口子，和 `mode=crash`
同一个入口：`{mode:"js"}` 在 setTimeout 里抛一个，`{mode:"rejection"}` 造一个
没人 catch 的 Promise。

### 没做，理由记着

- **`spider-host.html` 的钩子。** spider 在另一个 WebView，CSP 写死
  `connect-src 'none'`，唯一出口是 `__spider.call(op)`，要接得给 `SpiderBridge`
  加个 op 并同步改 `docs/spider-接入规范.md` 的契约表 —— 独立一块。而且绝大多数
  spider JS 异常今天已经上来了：`__spiderRun` 把同步异常和 rejection 都收敛成
  `{ok:false,error}`，最终变成 `bridge/command_error`。真正漏的只有 spider 自己
  detach 出去的 then / setTimeout 里抛的。
- **`play_error` 的 source 标签。** `app.js:62` 的 `bridgeCall` 无差别注入
  `source: contentSource`，而它恒为 `'hongguo'` —— 所以播放事件那一列今天全是
  假源。是个真 bug，但修它会改变既有历史数据的可比性，单独一轮。

## 2026-10-24 傍晚：2.0.21 —— 把四条报错从「看得出坏了」变成「看得出为什么坏」

对着 2.0.20 的数据查了一遍用户报错（72 台设备、3,335 条事件、真失败 2,505 条 / 49 台，
零真实崩溃），顺着查出四件该改的事，这一版全做了。

### 一、封面报错以前把三种原因混成一句

`封面解密结果无效` 1,340 条、6 台设备，看着像密钥坏了。实际去图床抽了 20 张：全部解得出
JPEG / GIF / PNG；但抽到过一张 URL 回的是 160 字节 HTML —— 图床 404 或防盗链时回错误页，
App 拿它去 AES 解密，解出来当然不是图片。

现在报错带上原始字节数、解出来的前四字节、主机：

```
封面不是图片：mode=aes-cbc 原始160字节 解出3c6874（HTML，图床回了错误页） @pic.sufgew.cn
```

同时加了**五分钟负缓存**：同一条地址第一次照常抛、照常报；五分钟内重试直接抛缓存里
那条，异常实现 `Report.Once`，`dispatch` 见到就不再上报。那 1,340 条里绝大多数是
`cover.js` 每 30 秒重试同一批坏图刷出来的（一台设备 10 分钟 659 条 ≈ 40 张坏图 × 16 次）。

### 二、取消和真失败混在一起，失败率被顶到 75%

用户快速划过时上一个请求被取代（503 条）、spider 宿主退出时掐掉在途调用（39 条）、
排队超时（14 条）、`InterruptedException`（49 条）—— 这些不是故障，但带着 `code` 上报，
服务端按「code 非空」判失败，于是一半以上的"失败"是假的。

现在 `Report.bridgeFailure` 先分类，取消类发成 `bridge/command_cancelled` 且**不带 code**
（服务端就不计进失败），真失败还是 `command_error`。后台那层按文案正则的临时过滤可以
慢慢退休了。

### 三、每条事件补上源身份

2.0.20 的库里 `source_id` 全是空的：只有前端播放事件带 source，桥指令（封面、spider、
详情）一律不带，所以「哪个源在出事」只能靠报错文案倒推。

现在 `SiteProfile` 组装时把 `SourceConfig.label()` 挂进 `Report`（spider 取地址最后一段：
`51cg` / `mrds66`；REST 取主机名），`build()` 时补进每条事件。挂载点选在 SiteProfile
构造里，是因为注释里早就写着「这是全 App 唯一一处『当前源长什么样』的组装点」。

### 四、spider 绑定失败以前看不出是哪种

118 条 / 6 台，集中在几分钟内，没有对应的崩溃事件 —— 说明不是 Java 异常退出。
原来的消息只有一句「spider 服务绑定失败。」，分不清三种情况：bindService 被拒、
服务连上又断、回调到了但 channel 为空。现在分开写，并且 `SpiderService` 补了
`spider/host_start`、`spider/host_stop` 两个端点事件（带 pid，进程内只写队列，
由主进程统一发）—— 下次再出，时间线上能直接看出是「没起来」还是「起来又死」。

### 验证方式

先在服务端做**契约验证**：拿服务端密钥签一批新形状的事件直接 POST 进去，再看 `/stats/*`
和后台页面 —— `command_cancelled` 计入事件流但失败数为 0、`source_id` 落成 51cg / mrds66、
`host_start` / `host_stop` 都进了「事件类型」表。验完把那 6 条 selftest 行删了。

然后装到真机（23113RKC6C / Android 16）上跑，四条里有三条拿到了实机证据：

```
18:12:26  app/app_open              2.0.21
18:12:26  spider/host_start   pid=22421      ← :spider 进程的起点事件，带 pid
18:16:52  bridge/command_error  src=yhdm     ← 每条事件带源身份了
18:17:26  bridge/command_cancelled src=yhdm code=-   ← 取消不再是失败，且不带 code
```

`command_cancelled` 是这么造出来的：并发发三个 spider home 调用，第三个进来之后，
第二个拿到锁时 generation 已经不是自己那一代，`SpiderClient` 抛「被更新的请求取代」——
正是线上那 503 条的成因。

封面那条（`封面不是图片：mode=… 原始N字节 解出xxxx…`）没有现成的坏图可造，改成单测盯文案：
`coverFailureDetail` 放开成包可见，用一段 HTML 和一个 JPEG 头各断言一次（字节数、前四字节、
HTML 提示、主机名）。

`host_stop` 只验了代码路径：它走 `SpiderService.onDestroy`，而服务被 `bindService` 绑着，
`am stopservice` 停不掉（`Error stopping service`），只能等 App 正常退出时自然触发。

单测 260 个全过（新增 4 条：源回填、取消分类、Once 标记、封面文案）。
APK 构建成 2.0.21（20021，40.97 MB）并已装到真机，冷启动、首页、分类列表都正常，
`logcat -b crash` 里没有 App 的崩溃（那几条是 adb 注入器 `dsh.Injector` 自己的）。
还没发版 —— 发版要动 android-update.json 和 GitHub release。

## 2026-10-24：后台「数据」页 —— 埋点在哪儿看

用户问「我在哪里看见用户的操作」。答案在那之前是：没有地方，只有我自己 ssh 上去敲 psql。
现在有了 `https://149.104.21.177.sslip.io/admin/data`（左侧「数据」）。

**为什么不是后台直接连库**：track-db 一步都没出 track 那套 compose 网络，后台是
`network_mode: host`，够不着；而且不该把埋点库再分一份写权限给第二个服务 —— 那张表
每天 00:10 重算、90 天清理，多一个写入方就多一种「谁删的」的可能。

所以读也走 HTTP：track 加两个只读接口 `/stats/overview`、`/stats/events`，
口令是 `X-Stats-Token`（新的 `STATS_TOKEN`，和 App 签名用的 `TRACK_SECRET` 分开：
那把验请求体完整性，这把验「你是值班台」）。没配口令时整组回 503 而不是放行。
`deploy.sh` 现在会给老的 `.env` 补一条 `STATS_TOKEN`。

页面按「值班台」的语言排：规模 / 每天 / 源 / 失败排行 / 版本机型 / 事件类型 / 事件流，
窗口可切 1/7/30/90 天，事件流可按 kind 过滤。

**口径比数字重要，两个坑当场踩到了：**

1. **失败率 93% 是假的。** 库里的 `bridge/command_error` 里有一大批不是故障：用户快速
   划过时上一个请求被取代（「被更新的请求取代」503 条）、spider 宿主生命周期结束时掐掉
   在途调用（「宿主已关闭」39 条）、排队超时（14 条）、`InterruptedException` 49 条。
   现在统计里单列 `noise`，页面上写「真失败 2505 · 取消 619」，失败率按真失败算。
   治本要去 App 侧把这些加进 `bridgeFailure` 的安静名单，排在下一版。
2. **源那一列全是「没报源」。** App 侧只有前端播放事件带 source，桥指令（封面、spider、
   详情）一律不带，服务端只能落空。要在这一页回答「哪个源在出事」，得让 Java 侧
   `Report` 在每条事件上补当前源身份（`UserSource.identity()`），也是下一版。

**页面一上线就照出三件之前不知道的事：**

- `cover / 封面解密结果无效` 1340 次、6 台设备，最后一次 10-24 14:07 —— 51cg 那种整份
  AES 的图床，解不开的比例比想象的高。
- `spider / spider 服务绑定失败。` 118 次、6 台设备 —— 独立进程起不来，这些用户的源
  等于全废。
- `detail / Too many follow-up requests: 21` 9 次、2 台 —— 重定向环。

## 2026-10-23 深夜：内容源页 —— 一个 CSS 语法错误吃掉 98 条规则

用户发来两条反馈：备注那个按钮丑得不像这一页的东西，以及「点了一下莫名其妙变大」。
第一条查下去，根因比想象的严重得多。

### 断在一行 data URI 上

`app.css` 第 5169 行：

```css
background-image:url("data:image/svg+xml;
    charset=utf-8,%3Csvg …");
```

引号里的字符串跨了行 —— CSS 里这是语法错误。Chrome 的恢复策略是把出错位置之后的
规则一起丢掉。实测 `document.styleSheets` 只解析出 **881 条**，而 5169 行之后还有
**98 条**一条都没进去，包括：

- 整个「设置页 v2 · 内容源列表」块（`.scripts-card .source-row/.source-pick/.source-note/.source-drop`）
- `.press-host` / `.press-ripple`（按压涟漪）
- `.settings-danger-button`、`.settings-row .settings-mini` 的收口
- 外观那组「选中打勾」的 `:after`

所以「备注」按钮压根没吃到任何 App 样式，用的是浏览器默认 `<button>`：
`background: rgb(107,107,107)`、`border: 2px outset`、`border-radius: 0px`、`font: 16px`。
旁边那个 × 用的是断点之前的旧规则，圆角 15px —— 一对方角一对方角，谁看都觉得别扭。

改法就一个字：把 data URI 收回一行。规则数 881 → 994，`.settings-card .source-list`
回到第 932 条，外观页的蓝色勾也回来了。

```
修复前  note: bg=rgb(107,107,107) radius=0px border=1.9px outset   ← 浏览器默认
修复后  note: bg=rgba(118,118,128,.08) radius=12px border=1px        ← 设计里那个
```

### 备注改成一张「正在编辑的卡片」

`.source-note-form` 之前**一条 CSS 都没有**（不只是被断点吃掉，全文就没有）。点开
备注看到的是默认样式的 label + input + 三个系统按钮。

补上：青蓝描边的卡片占住原来那一行的位置，标签在上、输入框铺满、保存（蓝色胶囊）/
清空 / 取消在右下角，清空在没有备注时自动置灰。行高从 56 长到 114，量的就是输入框
34 + 标签 15 + 间距。

顺手把「备注」按钮本体也调成和 × 同一种安静按钮（44/46 × 56，12px 圆角，
8% 灰底），一行的读法变成「一张卡片 + 两个次要动作」。

端到端真机验证：填「测试备注」→ 保存 → `state.sources` 里第 2 项的 alias 变成它 →
点开再清空 → 全部回到 `(无)`，没留测试数据。

### 涟漪：改成内联定位

`spawnRipple` 只把圆心和直径写成内联，定位靠 `.press-ripple{position:absolute}`。
样式表一断，那个 span 就退回静态行内元素 —— 尺寸是 π×半径² 的直径，参与布局。
现在 `position:absolute;pointer-events:none` 也写进内联，样式表再怎么断，它也只是
浮在上面的一个圆。

### 另外三处（同一轮里查出来的）

1. **进页面就跳到底部**：`goSourceSettings()` 结尾 focus 了页面最底下那个输入框，
   滚动位置从 0 被拽到 783，同时弹键盘，紧接着异步刷新又整页重画一次。删掉 focus，
   二级页进来一律 `scrollTop = 0`（网络代理页同样有这毛病，325）。
2. **键盘开着按返回，整页跟着退**：`handleBack()` 不看输入法状态。加 `hideImeIfShown()`，
   两个判据都要试 —— `insets.isVisible(ime())` 在这台机器上恒为 false，而
   `imm.isAcceptingText()` 收完键盘还是 true（WebView 里那个 input 还攥着焦点），
   所以还得同时 `document.activeElement.blur()`。真机：back#1 收键盘停在原页，
   back#2 才返回。
3. **隐私说明被省略号截断**：「关掉之后不再上报…崩溃还会上报」用的是
   `.settings-body small`（nowrap + ellipsis），真机上只剩「…崩溃…」。换成
   `<small class="settings-note">`，两行铺满。

### 两条教训

- **样式表整体失效这种故障，症状是散装的。** 一个按钮丑、一页间距不对、打勾没了，
  看着像三个 bug，其实是同一行。以后遇到「这一块完全不像设计稿」，先去控制台数
  `document.styleSheets[0].cssRules.length`，比逐个比对像素快得多。
- **用预览图上的坐标点真机一定点错。** 838 宽的预览和 1080 的屏幕差 1.29 倍，我按
  0.596 的 y 去点「备注」，落到了下一行的「切换到这个源」上，把内容源从 mrds66 切成
  了 hanime1（首页 tab 都变了）。之后要么用 `android_tap_element` 按元素点，要么先
  换算成设备像素。

## 2026-10-23：可观测性 —— 崩溃、源健康、埋点（商业化地基）

**这是下一轮的主线。** 现在对线上发生的事一无所知：没有崩溃上报、没有播放失败记录、
没有页面行为。用户报「播不了」，能查的只有 adb logcat；商业化要回答的问题（谁在用、
看什么、在哪一步走）一个都答不上。

三件事共用一条管道：**App → 批量上报 → 服务端入表 → 后台一页看板**。先建管道，再往上加。
埋点的口径按商业化问题倒推，不是「有啥记啥」—— 见下面三个假设。

### 落地进度（2026-10-23 晚）

| 批次 | 状态 | 落点 |
|---|---|---|
| B 管道 | **已实现并部署** | `server/track/`（FastAPI + 自带 pg，`:8092`，Caddy `handle_path /t/*`）；App 侧 `Report.java` / `ReportQueue.java` |
| C 崩溃 / 播放失败 | **已接入** | `HuangGuoApp.installCrashReporter()`；`dispatch()` 统一出口报 `bridge/command_error`；前端 `trackPlayError()` 三个 stage |
| D 行为埋点 | 只落了 `app_open` | 其余 11 个事件按需接 |
| E 源 smoke | 未动 | |
| F 后台数据页 | 未动 | |
| A 包体 | 卡在「本机没装 NDK」 | 见下 |

**A 已解决（2026-10-23）**：根因是本机没有 NDK —— AGP 的 `stripDebugSymbols` 找不到
`llvm-strip` 就静默跳过，把带 `.debug_info` 的 so 原样打了进去，构建日志一句不吭。

做法是装 NDK + 在 `android {}` 里声明 `ndkVersion = "27.0.12077973"`。声明这一行是关键：
没有它，即使 NDK 装了 AGP 也不会把 strip 任务挂上去。

```bash
yes | sdkmanager --sdk_root=$HOME/Library/Android/sdk "ndk;27.0.12077973"
```

结果：`libc++_shared.so` 8.9 MB → **1.3 MB**，APK 42.89 MB → **39.07 MB**（-3.82 MB）。
`llvm-readelf` 复核：`.dynsym` 2502 个符号、`.dynstr`、`.text` 都在，`.debug_*` 没了，
所以只是删调试信息，动态链接不受影响；模拟器上装完启动、上报都正常。

剩下两项没动：assets 里 `geosite.dat` + `ip-country.bin` 那 3.6MB 要改成首次进代理设置时
下载（缺了没有 routing 规则，得分支处理，不是删文件那么简单）；R8 等有 instrumentation
测试之后再开。

**密钥怎么放**：`TRACK_SECRET` 不进源码。服务器在 `/srv/track/.env`，本机在
`keystore.properties` 的 `trackSecret`（已 gitignore），经 `buildConfigField` 注入
`BuildConfig.TRACK_SECRET`。留空等于关掉上报，构建不报错。

**两端对齐的验收**（已跑过）：

| 场景 | 结果 |
|---|---|
| 正确签名 + 2 条事件（gzip，签未压缩体） | 200 `{"ok":true,"accepted":2}` |
| 错签名 | 401 `签名不对：X-Sig 不是…` |
| 同一 `event_id` 发两次 | 第二次 `accepted:0`，库里 1 行 |
| 模拟器冷启动 | `app_open` 入库，device_id 是 UUID、os 33、model `sdk_gphone64_arm64` |
| 飞行模式冷启动 | 本地 `files/reports/*.jsonl` 有 1 行，库里 0 行 |
| 恢复网络 | 约 1 分钟后补发成功，库里出现，本地文件删除 |

### 开关撤了：埋点常开（2026-10-23 23:20）

上面第 1、2 条（「设置页有了开关」和「关掉时清队列」）已经被推翻 —— 那个开关的 UI 撤掉了，
埋点一直开着。

改动：`renderSettings()` 里那张「隐私」卡片整块删除，跟着删的还有 `data-track` 的点击处理、
`state.track` 的懒加载、Java 侧 `Report.setOptOut` / `isOptOut` / `ReportQueue.clearBehaviorEvents`
和桥命令 `report-config`。`Report.event()` 不再看 optOut，来了就入队。

老用户那边要单独处理：装过带开关那版的机器，`SharedPreferences("report")` 里可能留着
`opt_out=true`，而界面上已经没有地方能打开它 —— 那样这些设备会永远静默。
所以 `install()` 里读到这个键就直接删掉，日志打一行「清掉老版本的「关掉使用统计」标记」。

顺手确认的事：`diag` 的自检口子留着，返回的 `state()` 现在只有 `pending`。

### 埋点上线两小时就抓到一条真 bug：pornhub 封面全挂

删完开关顺手查了一眼库，`bridge/command_error` 在 2 秒里刷了 8 条：

```
stage=cover  code=IllegalArgumentException  msg=不支持的封面格式
```

`SiteRepository.coverData()` 拿路径最后一段当扩展名，不在 jpg/jpeg/png/webp/gif 里就抛。
问题出在 `lastIndexOf('.')` 返回 -1 的情况：pornhub 那批缩略图是裸路径，压根没有扩展名
（`/videos/202111/29/398816751/180P_225K_...`），于是每一张都判成「格式不支持」，整页灰块。

改成「点在最后一段路径里才算有扩展名」，没有扩展名就放行 —— 真正的边界是 `allowedHosts`
白名单加上拿回来之后必须解得开图片，有扩展名但不是图片类型才拒。报错文案也带上了
扩展名和主机，下次再出这种问题不用猜。

改完整页封面正常，同一时段的库里 `cover` 错误归零，只有一条 `app/app_open`。

这条值得记：埋点不是为了报表，第一天就用来定位了一个没人报的 bug。

### 三条补完（2026-10-23 深夜，真机验证）

1. ~~**设置页有了开关**~~：已撤销，见上面「开关撤了」。
2. ~~**关掉时清队列**~~：`clearBehaviorEvents()` 已删除。
3. **崩溃可触发**：桥命令 `diag`，`mode=crash` 时在主线程 post 一个必抛的 Runnable，
   逃出 Looper 走真正的 `UncaughtExceptionHandler`。

真机验证（7f8f5254 / 23113RKC6C / Android 16）：

```
22:27:07 FATAL EXCEPTION  java.lang.IllegalStateException: diag: 手动触发的崩溃（验证上报链路，不是 bug）
22:27:44 库里查到 kind=crash name=uncaught code=IllegalStateException
         msg=「diag: 手动触发的崩溃（验证上报链路，不是 bug）」
         trace=com.huangguo.mobile.HuangGuoApp.lambda$onCreate$0(HuangGuoApp.java:35)…
```

中文不再是乱码，说明 `readLines` 的编码修复在真机上同样成立。

**两个踩了很久的坑，记下来：**

- **改了 assets 必须 `--rerun-tasks`。** `./gradlew :app:assembleRelease` 在增量状态下会把
  `mergeReleaseAssets` 判成 UP-TO-DATE，打进包里的还是上一次的 app.css / app.js。我改了四遍
  CSS、装了四遍包、每次都看到旧的渲染，就是这个原因。判断方法：`unzip -p app.apk assets/app.css
  | grep 你刚写的类名`。
- **自绘开关在这个 WebView 里渲染不出来。** `<button>` + `::after` 圆点 / `span` 圆点 /
  `aria-checked` 属性选择器 / `.is-on` 类 / 内联宽高，五种写法都试过，胶囊在真机上始终是
  「灰色关态」且圆点看不见，而写死 `is-on` 类也不生效 —— 说明问题在渲染而不是 JS 状态
  （Java 日志打出 `optOut=false`）。最后换成同页其它按钮在用的 `.settings-mini` 纯文字按钮，
  一次就对了。那个胶囊的 CSS 已经删掉，别再加回来。

真机验证抓到两个只有真机才暴露的 bug：

1. **事件永远发不出去。** `enqueue()` 只写文件，没有任何东西踢它；`install()` 里那次
   `maybeFlush(true)` 跑在队列还空的时候。表现是「日志看着一切正常，库里永远空」。
   修法：入队后调一次 `maybeFlush(false)`，再把发送窗口从 60 秒压到 3 秒（这个量级
   不需要攒批），另起一个 15 秒的兜底节拍器。
2. **节拍器把发送线程占死了。** 第一版把 `while(true) + sleep` 丢进单线程 executor，
   后面的 flush 任务永远排不上。改成独立 daemon 线程。

**还有一个更隐蔽的：中文在落盘那一刻就烂了。** `readLines()` 用的是
`RandomAccessFile.readLine()` —— 它按字节逐 char 读（等于 ISO-8859-1），「中」的
E4B8AD 读回来变成 `ä¸­` 三个字符，再按 UTF-8 写出去就是 C3A4 C2B8 C2AD。ASCII 部分完全
正常，所以现象是「只有中文乱码」。自己加的探针打了一遍字符级 hex 才定位到：String 对
（char0=4e2d、utf8=e4b8ad）、JSON 对、字节对 —— 坏在**读回队列文件**那一步。改成
`BufferedReader(new InputStreamReader(..., UTF_8))` 之后往返正确。

这个 bug 顺带说明一件事：App 里所有 Java 侧的中文异常消息以前都是这种形态，只是没有
任何地方把它们发出来看过。

还有两处问题。`device_id` 一开始全落成 `unknown`：App 把 `device` 写在每条事件里，
服务端只读 `X-Device` 头和请求体顶层。两边各补一半 —— App 在 payload 顶层也带一份，
服务端优先级改成「事件的 device → X-Device → 顶层」，谁先谁后都不再落空。

`:spider` 是独立进程，Application 会在两个进程各跑一次 `onCreate`，`app_open` 上报了两条，
两个队列还会抢同一批文件。现在按 `/proc/self/cmdline` 判主进程：主进程完整跑（队列 +
节拍器 + 发送），`:spider` 只写不发（`ReportQueue.canFlush`），它的崩溃由主进程顺路带走。

### B 上报管道（先做，其余都挂在它上面）

**服务端** `server/track/`：FastAPI + Docker，绑 `127.0.0.1:8092`，Caddy 挂 `handle_path /t/*`。

- `POST /t/events`：批量，gzip 请求体，单次 ≤ 200 条、单条 ≤ 4KB
- 鉴权：`X-Sig: hmac_sha256(app_secret, body)` + 单 IP 60 次/分钟。公网无账号，少这两道
  就一定被刷满
- 表 `events(id, device_id, session_id, at, kind, name, props jsonb, app_version,
  patch, os, model)`；`kind` 里常用字段（source_id / stage / error_code / ms）落库前
  抽成列，不然查不动
- 明细留 90 天，每天凌晨聚合进 `events_daily` 永久保留
- 量级：189 个用户 × 每天 200 条 ≈ 4 万/天，Postgres 毫无压力，不需要采样

**App 端** `Report.java` + `ReportQueue.java`

- 事件先进内存队列，写 `files/reports/<date>.jsonl`；满 30 条 / 60 秒 / 切后台时发一次，
  发成功删文件。杀进程不丢
- **独立通道**：不走 `AiiHttp`、不走代理、不复用 `Outbound.applyHeaders`。源和代理挂了，
  上报必须还活着 —— 这条是整条链的立足点，别图省事合并
- 失败退避 3 次（1 / 5 / 30 分钟），仍失败留到下个会话
- `device_id` 安装时生成 UUID 存 SharedPreferences；不采账号、不采手机号
- 设置页给一个「不发送使用统计」，关掉之后只上报崩溃

### C 崩溃与播放失败（复用管道，一天内能落地）

- `Thread.setDefaultUncaughtExceptionHandler` → 写 `crash` 事件（堆栈前 40 行、机型、
  版本、当前源 id、当前阶段），再交回系统默认处理。该崩还是崩，但留下证据
- WebView 侧 `window.onerror` / `unhandledrejection` 经桥转成 `js_error`
- `play()` 的每个失败出口收敛成一个 `play_error`，带 `stage`：装源 / 取详情 / 取播放地址 /
  解密 / 起播 / 首帧。现在各写各的文案，用户只看到「播放失败」，你也一样

**验收**：真机上人为断网、把某个源 js 改成抛错、播一集再退出，后台能看到对应的三条记录。

### D 行为埋点

| 事件 | 关键字段 | 回答什么 |
|---|---|---|
| `app_open` | cold/warm、首屏 ms | 启动是不是变慢了 |
| `source_install` / `remove` / `switch` | source_id、来源（市场/手贴） | 装源这道坎有多高 |
| `home_view` | source_id、条目数、耗时 | 首屏出得来吗 |
| `card_click` | source_id、content_id、位置 | 用户看不看得到内容 |
| `search` | 关键词哈希、结果数、是否点开 | 站内搜有没有用 |
| `detail_view` | source_id、content_id、集数 | 内容偏好 |
| `play_start` / `play_first_frame` | source_id、content_id、ep、首帧 ms | 播放成功率、哪个源慢 |
| `play_error` | stage、error_code | 坏在哪一环 |
| `play_exit` | 时长 s、进度 %、是否播完 | 内容留不留人 |
| `episode_switch` | from / to | 追剧行为 |
| `favorite_add` / `history_view` | content_id | 账号体系值不值 |
| `proxy_enable` / `proxy_result` | 协议、成功与否 | 代理是不是最大的坎 |

切片维度只留三个：`app_version`、`source_id`、日期。多一个都要解释为什么。

### E 源健康 smoke

后台「源」页点「验证」，服务端按序打三枪：配置 JSON 200 → 首页/分类有条目 → 详情能取到
播放地址。结果写 `checks` 表（source_id / at / stage / ok / ms / 错误），灯带从「静态
可解析」升级成「最近一次验证的结果」。

之后可选：每天定点跑一遍，坏了发邮件。**这是把「用户先发现」改成「你先发现」的唯一办法。**

### A 包体（顺手，独立于上面）

- `libc++_shared.so` 换 strip 过的：8.9MB → 1.1MB（里面 7.1MB 是 `.debug_info`）
- assets 的 `geosite.dat` + `ip-country.bin`（3.6MB）改成首次进代理设置时下载
- R8 等 E 落地、有 instrumentation 测试之后再动，现在开会让 JS 桥全线白屏

### 这批数据要回答的三个商业假设

写死在这里，到时候拿数据判，别拍脑袋：

1. **多数人卡在「配代理」** —— 若 `source_install` → `play_start` 的转化低于 30%，
   且 `proxy_enable` 失败率高，付费点就是「内置中转 + 开箱即用」。判定阈值：漏斗第 3 步
   流失 > 50% 即成立。
2. **内容集中在少数源** —— 若 Top 2 源吃掉 60% 以上播放，谈白标/接口比撒网式接源值钱；
   若长尾源合计不到 10%，直接砍掉，省维护。
3. **留存靠片单** —— 若收藏用户的次留是非收藏用户的 1.5 倍以上，账号和跨端同步值得投入；
   否则账号只是登录负担，别为它做付费墙。

### 不做的

- 不接第三方统计 SDK（体积、隐私政策、国内合规都划不来）
- 不自建 BI，后台一页表格够用
- 不上报内容标题全文，只存 `content_id` + 源 + 分类。要标题时按 id 回源查，避免把站点
  内容抄进自己的库

### 工时

| 批次 | 内容 | 估 |
|---|---|---|
| B | 上报管道（服务端 + App 端） | 2 天 |
| C | 崩溃 / 播放失败接入 | 0.5 天 |
| D | 行为埋点（12 个事件） | 1 天 |
| E | 源 smoke + 灯带升级 | 1.5 天 |
| F | 后台「数据」页（漏斗 / 源榜 / 错误榜） | 1 天 |
| A | 包体三项 | 0.5 天 |

顺序：B → C → D → F。E 独立可并行。A 随时插队。

### 2.0.22：埋点管道里那个「数据在看不见的地方消失」的洞

上线两天（10-23 14:05 ~ 10-25 03:10 UTC）攒了 10065 条事件、218 台设备。按报错分桶
之后发现真正该看的不是那一堆 `command_error`，是**两个为 0 的计数**：

```
select count(*) from events where name='play_error';   -- 0
select count(*) from events where kind='crash';        -- 2（都是手搓的 diag）
```

TODO 里 C 那批写着「已接入」，实际一条都没进来过。根因不是接入漏了，是**一条坏事件
会炸掉自己的批次**，而客户端对 4xx 的处理是「丢掉」：

1. `feed-player.js:517` 的 decode 分支把 `video.error.code`（MediaError 枚举，数字）
   塞进 `props.code`；服务端 `ingest.py` 对 `code` 走 `_text()`，数字过不去 → 400。
2. `parse_batch` 遇到第 N 条不合法就 `raise`，前面 N-1 条合法的也一起不要。
3. `ReportQueue` 收到 400 走 `acknowledge(sending)`，把队列文件删掉，日志只一行 `Log.w`。

线上实测（`/t/events`，验证完已删掉探针行）：

```
对照(code=字符串)        200 {"ok":true,"accepted":1}
play decode(code=3)     400 {"ok":false,"error":"第 1 条的 code 必须是字符串"}
混合批次                 400 {"ok":false,"error":"第 2 条的 code 必须是字符串"}
```

**修法**：服务端逐条拒收（返回 `rejected`/`errors`，其余照收），`code` 列收数字；
客户端 4xx 改成二分隔离到 `files/reports/dead/`，401 单独退避。

顺带查出三个同源问题：

- **崩溃一直上报不上去。** `Report.crash` 把堆栈按**字符**截到 4000，而服务端算的是
  UTF-8 **字节**。真实 Android 堆栈（40 帧 × 70 字符 + cause 10 行）序列化后 4434 字节，
  被 413 掉；只有手搓的十帧 diag 崩溃（958 字节）刚好过得去 —— 线上 crash 表里就那两条。
  现在堆栈按字节裁（2400）、整条事件压到 `EVENT_BUDGET = 3800`，服务端留 296 字节余量。
- **单日超 200 条就丢。** `claimFiles()` 认领全部文件、只发前 200 条、然后
  `acknowledge()` 删掉**整个**文件。线上已发生 4 台设备日（708 / 539 / 449 / 322 条），
  丢了多少无从得知 —— 因为丢的时候日志写的是「发送成功」。现在按已发送条数把剩下的
  写回（`.tmp` + rename）。
- **封面负缓存漏了配置错误那一关。** `coverData()` 第一行就是 `profile.coverUrl(str)`，
  它在 `FAILED.get()` **之前**抛「无效封面域名」，于是这类失败永远进不了
  `rememberFailure`。证据：一台设备 34 条「无效封面域名」，横跨 351 分钟。缓存键也用
  原始 URL 而不是归一化后的（`coverUrl()` 抛的时候拿不到）。

**一个必须记住的判据坑**：`SocketTimeoutException extends InterruptedIOException`，
okhttp 的 callTimeout 也抛 `InterruptedIOException("timeout")`。按类名把
`InterruptedIOException` 加进「取消」名单，会把线上 26 条真超时（均值 30 秒、全在
detail 阶段）洗成取消 —— 那等于把「站点慢到读不出来」这个信号藏起来。只能按 message 判。

单测 260 → 271（`ReportTest` 新增 5 条、新建 `ReportQueueTest` 6 条），服务端从 0 个
测试变成 19 个（`server/track/tests/`，标准库 unittest，不需要库）。

**二分隔离里两个容易写错的地方**，都写进单测钉住了：

- 返回值不能是「成功条数」。坏事件在中间时，「前 N 条成功」这个假设不成立，按条数切
  前缀会把没发出去的删掉。改成按**对象身份**返回（`IdentityHashMap`）—— 用 equals 更糟，
  重试刷出来的事件内容可能完全相同。
- 从 tail 递归下来的单条**从没被单独发过**（父层只 post 了 head），直接判它有问题就是
  冤杀。进 `isolate` 之前必须自己试一次。

### 2.0.23：确属我们代码的那三类报错

上一轮按归因分组时，有一档是「这个确实是我们的问题」。量小，但每一条都能修。

**一、源没实现的兜底从来没生效过。**

`contentFallback`（tag-list → category、tags → 用配置的分类现拼）只挂在 `api()` 的
能力预判上，而那句预判要 `info.caps.length` 非空才走：

```js
if (info.caps.length && info.caps.indexOf(cap) < 0) { /* 兜底 */ }
```

探测本身会失败（宿主页建不起来、`__meta` 超时），失败时 caps 是空数组 —— 整句跳过，
直接调，然后源抛错。线上 19 条 `没有实现 tagList`、3 条 `没有实现 tags` 全来自两台
没探到能力集的设备，全是 2.0.20。用户看到的是「这个源没有实现「类型列表」（spider 里
没有 tagList 函数）」—— 一句带内部函数名的技术报错，而那个源本身完全正常。

现在 `spiderApi` 在 catch 里也兜一次：`isMissingCapability(error)` → 调
`contentFallback`，兜不了才把人话错误抛出去。两条路都试过才算真没有。

**二、失败消息把原因吃掉了。**

`AiiHttp.java:276` 和 `:613` 两处 `throw new IOException("请求失败：" + url)` 把底层
异常扔了，只剩地址。线上 **313 条**这种消息，`code` 列统一是 `IOException` —— 想知
道是超时、被 RST、还是 TLS 失败，翻数据库翻不出来。

现在带上 `describe(底层异常)`（类名 + message，null 安全，不重复拼已经带了类名的）。
顺带修 `fetchUncached` 的降级循环：原来第二次重试的异常会覆盖第一次，拿不到根因。

**三、重定向环只说数字。**

OkHttp 抛 `ProtocolException("Too many follow-up requests: 21")`，74 条，全是这个形态。
看不出绕去哪，也分不清是站点在环、还是我们发的 Host/Referer 触发了循环。本地拿
huangguoai.com 复现不出来（无 UA / okhttp UA / http→https 都只 1 跳），所以这一条
**改成「看得出原因」而不是猜原因**：

- 加一个只观测、不改行为的 OkHttp 拦截器，每跳记进 ThreadLocal；
- 撞环时 `withRedirectHint` 把前三跳 + 总跳数拼进错误消息；
- 上限**保持 OkHttp 默认的 20**。放宽只会让用户多等，看不出来才是要修的。

**四、封面负缓存补主机一层。**

一个源的封面主机挂了，列表里每张封面 URL 都**不同**，按 URL 缓存一次都命中不了。
一台设备 34 条「无效封面域名」横跨 351 分钟，就是这么来的 —— 34 张不同封面的同一个
错误。现在 `FAILED_HOSTS` 记主机，同主机五分钟内只报第一条，后面那些照常失败但带
`Report.Once` 不上报（`dispatch` 里 `!Report.isOnce(error)` 会挡）。

`不支持的封面格式` 也接进了缓存 —— 原来它直接抛，绕过了 `rememberFailure`。

单测 271 → 283（新增 `CoverFailureCacheTest` 5 条、`AiiHttpErrorTextTest` 5 条，
`ReportQueueTest` 补到 6 条）。缓存是静态的，用例之间要 `resetCoverFailureCache()`，
和 `ReportTest` 里 `setSource("")` 那个坑同一类。

## 2026-10-21：中继取流与环境装配 —— 已完成（真机验证）

一天三个「看不了」，两个是同一个结构缺陷、一个是配置漂移。全文见
[出站请求的两条通道](docs/出站请求的两条通道.md)。

- **2.0.16 · 搜索历史**：点搜索框浮出历史、点词重搜、清空要确认；上限 20 条、
  去重不分大小写；面板宽度现量搜索框。存 `hgpref:search-history`，不进 `hgcache:`。
- **2.0.16 · 剔 m3u8 插播广告**：四条判据（路径特征 / 序号断裂 / 换域 / 两长夹一短），
  片头另有一条更保守的。整段连分片一起删，段内 `EXT-X-KEY` / `EXT-X-MAP` 保留。
- **2.0.17 · AGE 一集都看不了**：2.0.16 把播放列表改成「一律绕中继」后，中继仍无条件
  跟着代理走，直连表里的 `ffzy-plays.com` 被塞回境外节点，CDN 回
  `403 The region has been denied`。改成按 `NetRoute.mustProxy` 判。
- **2.0.18 · Pornhub 分片全 403**：`master` / `variant` 200、`seg-*.ts` 403 —— 中继没套
  源声明的 Referer/UA，phncdn 的 HLS 只认站点 Referer。播放器打开时把当前源交给中继。
- **2.0.19 · `Outbound`**：请求头模板与代理判定各一份实现，`AiiHttp` 和 `VlcRelay`
  都只调它；7 条用例盯边界。以后加通道必须先来这儿确认。
- **mrds66 封面全空**：站点图床 `pic.jtzatly.cn` → `pic.sugfew.cn`，服务器上那份
  `spiders/mrds66.json` 的 `cover.allowedHosts` 没跟（已改，两个域都留）。
  **这类问题靠配置更新解决，用户要重装一次源才生效** —— 白名单在配置里是要付代价的。

顺带确认的事实：AGE 站点自己在批量失效（抽样 10 部 7 部 `Code=9`，官网同集也是
「视频加载失败」）；香港那台 `149.104.21.177` 的配置目录在 `/srv/cfg/`，
`spiders/*.json` 公开读、`admin/sources/` 要鉴权。

## 工作流约定（2026-10-17 定，别再搞反）

## 2026-10-19 晚：动画根治 + 路由智能化 —— 已完成（真机验证）

一轮四件事，细节全在
[`docs/history/2026-10-19-卡片动画卡顿排查.md`](docs/history/2026-10-19-卡片动画卡顿排查.md) 和
[`docs/history/2026-10-19-代理路由智能化.md`](docs/history/2026-10-19-代理路由智能化.md)：

1. **卡片动画恢复 + 缓存修复 + 过场盖加载**：`CARD_TRANSITION_ENABLED` 开关
   重新打开，`capture` 跟着开关走（之前关着动画还照跑 cloneNode，还把缓存渲染
   挡死了）；动画中不重建 DOM，渲染排队到 `onOpened`；帧停顿看门狗兜底。
2. **spider 宿主迁 `:spider` 独立进程**：Android WebView 全 app 共享渲染主线程，
   spider 抓取解析会把主页面动画冻住 1.7~2.4s——进程隔离是根治。AIDL 通道 +
   WebView 数据目录后缀（多进程官方解法）+ 代理快照跨进程传。
3. **代理路由智能化**：Xray 核心接 `geosite:cn` 分流（dat 由 libv2ray.aar 自带，
   零额外工作）+ `RouteMemory` 失败自愈（连接层失败换路重试一次，学习 24h）。
   `DirectHosts` 手填白名单过渡保留，待退役。
4. **闪退一课**：geosite 规则让核心第一次读 geo 文件，`go.Seq.setContext` 没调
   → Go `os.Exit(1)` → 点图标闪退且无 Java 栈。新核心配置资源类型必须在
   「核心真实启动」的档位下验收。

## 2026-10-18 下午：代理分流 + 播放三修 —— 已完成（真机验证）

四个 bug 一轮修完，细节全在
[`docs/history/2026-10-18-代理分流与播放器修复.md`](docs/history/2026-10-18-代理分流与播放器修复.md)：

1. **代理全局拖垮直连源**（AGE动漫 SSLHandshake）：新 `DirectHosts` 显式域名表，
   `AiiHttp` / `VlcRelay.wrap` / `XrayConfig` 三个出口共用。以后新接直连源往
   `DirectHosts.HOSTS` 加一行即可。
2. **视频 → 选集 → 返回死循环**：`hgResume` 先把 route 归位再进 `goDetail`，
   boot 残页不再污染 `state.previous`。
3. **设置四个子页「返回设置」落首页**：`hgBack` 补 `source/proxy/player/market`
   四个返回分支（老坑，一直没暴露）。
4. **VLC 第 N 集有声无画 + 上下常驻暗带**：pager 没滚到 `curPos` 导致 vout
   永远不挂（`pager.post(scrollToPosition)` 修）；scrim 跟控件一起显隐。

**本地 git = 代码回滚用。** 想提交就提交，不纠结提交信息、不纠结某个文件该不该进
仓库。它就是个存档点，唯一用途是出问题时能退回去。

**GitHub = 只保证 APK 是最新版 + README 好看。** 公开仓库面向使用者，不是开发记录。
有值得发布的改动就把最新 APK 挂 Releases；README 照
[piko](https://github.com/NihilDigit/piko) 的排版来（结构化风格基准见 README 本身）。

### 推论：不要为了「干净地推送」去删本地代码

这条是踩过的坑，写下来免得再犯。

2026-10-17 为了让仓库里不出现某个成人内容站的痕迹，我把 `UserSource.wrapHanime1()`
（约 230 行）、域名识别、探针全删了，还加了一堆 `.gitignore` 排除规则。**这是错的** ——
本地要**持续写这类适配器**，代码删掉等于自断一臂，而且当时并没有「干净推送」的要求。

真要避免某些内容进公开仓库，办法是**推的时候挑提交**，或者用 `.gitignore` 挡
**内容文件**（页面快照、密钥、配置），不是删代码。代码是可执行的资产，内容才是要挡的。

那次删除已全部复原，编译与两套回归（13 + 32 项）都过。

---

## 原生库集成 —— 已验证通过（2026-10-17）

> ⚠️ **2026-10-18 更新：本节描述的是旧链路（apktool 重打包），已经不存在了。**
> 项目已改成 Gradle 正面工程（`work/android/`），塞 .so 只需放 `jniLibs/` 或引 AAR，
> 下面那套「doNotCompress + zipalign -p 4」的手工配方作废。
>
> 结论层面**唯一还有用的**是那句「传递依赖要自己捆」——但它也只对 `extractNativeLibs="false"`
> 成立。现在配置是 `"true"`（安装时解压），系统库之外的依赖照样得捆，`libc++_shared.so`
> 仍要跟 `libvlc.so` 一起进包（libvlc 那个 9MB 就是这么来的）。
>
> 代理核心的体积实测见 `docs/开发说明.md` 的「so 打包方式」一节。

要决定的是「App 能不能内置一个代理核心（mihomo / Xray）」。前置问题是构建层：
这个项目是 apktool 重打包、没有 Gradle、从来没塞过 .so，而 manifest 里写着
`android:extractNativeLibs="false"` —— 那要求 .so **不压缩 + 4KB 页对齐**。
这条不通，后面所有讨论都白搭。

**结论：通了。** 五项检查全过：

| 检查 | 结果 |
|---|---|
| apktool 导入 `lib/` | ✅ 构建日志出现 `I: Importing lib...` |
| .so 落位 | ✅ `lib/arm64-v8a/libX.so` |
| 不压缩 | ✅ `unzip -v` 显示 **Stored, 0%** |
| 4KB 页对齐 | ✅ offset 6918144 = 1689×4096；`zipalign -c -p 4` 通过 |
| 真加载 | ✅ 真机 `System.loadLibrary()` 成功，日志「原生库探针 OK」 |

### 唯一一个坑：传递依赖要自己捆

第一次拿 `/system/lib64/liblog.so` 当载荷，`dlopen` **找到了文件但失败**：

```
dlopen failed: library "libc++.so" not found: needed by libtestprobe.so
```

**这条错误反过来证明打包是对的** —— 它已经成功打开 APK 内的库了，失败发生在之后
的依赖解析。系统的 `libc++.so` 不暴露给 App 命名空间。

把 `libc++.so` 一起放进 `lib/arm64-v8a/` 就过了。所以接真核心时，**先查它的
`DT_NEEDED`，把每个非系统库一起捆**。mihomo / Xray 的 gomobile 产物大概率依赖
`libc++_shared.so`（约 1 MB/ABI）。

### 配方（四步，已固化）

```text
① cz_repack/lib/<abi>/*.so                   把库放进去
② apktool.yml 的 doNotCompress 加一行 - so   （已加，留在配置里了）
③ build.sh 的 zipalign 已经是 -p 4           （本来就对，不用改）
④ System.loadLibrary("名字")                 从 java-src 调，不用碰 smali
```

第 ④ 步是这次验证出来的额外收获：**加载原生库不需要改 smali**，在 java-src 里
加个静态方法就行，`build.sh` 会把它编进 classes5.dex。

### 下一步的前置（还没装）

```text
Go        1.27.2   已装
NDK       未装
gomobile  未装
```

所以接真核心有两条路：装 NDK + gomobile 自己编（要下几个 G），或者直接用
别人打好的 AAR 只取里面的 `.so` 和 `classes.jar`。

---

## 单站 HTML 适配器（2026-10-17 **真机跑通**）

设备 `7f8f5254` 上首页 / 分类 / 详情 / 播放 / 标签**五层全通**，画面已渲染。
上一轮担心的「安卓 OkHttp 过不了 CF 的 TLS 指纹闸」**不存在** —— 推断成立，有真机证据了。

**这个适配器的代码和它的目标站都不在仓库里**（目标是成人内容站，内容不留、
教训留，见 .gitignore 末尾）。所以下面记的每条都是自足的、跟具体站点无关的结论。

- 回归：32 项离线断言（fixture 现场抓，不进仓库）
- 接线：`AiiHttp` 分流（含 `stripOrigin`）+ `MediaProxy.HOSTS` + 标签系统
- 当前包：`cz-final.apk` sha256 `7a86ed62719bc3d3…`

**真机抓出四个 bug，三个已修**：

```text
① 详情 404，URL 里带着 origin     SiteRepository.detail() 拼了 origin，适配器分流落空
                                 → AiiHttp.stripOrigin() 还原
                                 ★ 所有 origin 非空的适配器源都会踩，跟哪个站无关
② 分类 403，URL 里 id 是空的       分类列表掉进了 detail() 分支
                                 → fetch() 里按参数名分流，判据顺序不能换
③ 播放 MediaError 4              WebView 自己发的请求挂；服务端全对（裸 curl 206、
                                 编码 H.264、moov 在头）。接管过来走 OkHttp 强制 h1.1
                                 → MediaProxy.HOSTS 加该 CDN 域
```

第三条**成因和 bilibili 那族不同**（那族是服务端 h2 返 403，这族是服务端全对、
只有 WebView 发才挂），`MediaProxy` 注释里分开写了，别合并。

**标签系统那两个坑是跨源的**（任何想用标签的用户自带源都会撞上）：

```text
④ adapters.js 的 adaptTags 只认 code → TAG_GROUP_BY_CODE，标签自带的
   group 字段被完全忽略 → 走适配器的源自定义分组全折进 other → 整页空白
⑤ app.js 的 renderTags 在非 private 模式下**不看配置**，写死
   [['short','短剧类型'],['comic','漫剧类型']] —— 而 privateMode 只对内置
   黄果源为 true，于是所有用户自带源都被按死在那对分组上
```

两个都是**静默失败**：接口拿到了数据（右上角还显示着「240 个标签」），
页面却是空的，不报错。见 `adapters.js` / `app.js` 里对应位置的注释。

**还没修的一条**：详情页「正在加载… / 正在获取选集…」不消失。
`/api/videos/408297` 在 561ms 就 `ok` 了，是前端没更新状态。
**不影响功能**（画面照常出），但是个真眼可见的瑕疵。

**签名 TTL 的口径要重测**：原来写「约 12 小时」，但真机上每次抓到的 ts 都是
固定值、不随请求时间滑动 —— 更像按批次签发的固定窗口。别拿 12 小时当准数。
**「播放地址必须零缓存」这条结论不受影响**，仍然成立。

其余未验：带空格的分类、播放进度上报、收藏、横屏 / 倍速 / 拖进度。

**一个调试上的坑值得记**：改完适配器**光重装 APK 不够** —— 设备上的源配置是
上次「重新拉取」时生成的，不会因为换包而更新。要在源管理页点一次
「重新拉取这个源的配置」，否则你看到的是旧配置配新代码。

---

2026-10-16 当前状态：改造版功能完整、真机验证通过。红果源已摘除，用户自带源跑通，
出厂不带任何源（装完是空的，用户填地址才有内容）。

产物现状：`work/shells/cz-final.apk`（仓库里不放，挂 GitHub Releases），8045170 字节，补丁集 22 个文件 + 76 条删除记录 684K。
前端体量：`app.js` 1958 行，`setup.js` 186 行，`cover.js` 228 行，`adapters.js` 231 行，
`feed-player.js` 665 行，`app.css` 245 行。
Java 侧：`java-src/com/huangguo/mobile/` —— `AiiHttp.java` 322 行（HTTP + DoH），
`UserSource.java` 350 行（读写/校验/下载/桥指令），编译成 `classes5.dex`。

**已完成（2026-10-16 第二轮）**：苹果CMS 适配器（JSON + XML，`CmsAdapter` 340 行 / `CmsXml` 190 行，
真机验证三个源能播一个不能播）、网络代理（HTTP/SOCKS5，设置页可配）、源管理（清单 / 切换 / 删除 / 添加，
切换走完整校验 + `MainActivity.recreate()`，**点完即时生效**——主线程重建 + 前端不再 await 重启指令，
两个坑见 docs 坑 14/15，换源还要清掉进程里的预热与连接池，见坑 16）、缓存按源分作用域（换源不再画出
上一个源的内容）、首屏预热（`AiiHttp.warmup` + 在飞等待）、DoH 结果磁盘缓存（TTL 30 分钟 + 后台刷新）。
另有三个前端坑：局部 `cover` 遮蔽封面模块（坑 17）、`goHome` 的 try 把渲染异常说成接口失败（坑 18）。
**底部导航也归配置了**（`web.ui.tabs`）：以前那四格写死在 `app.html`，换成苹果CMS 之后「剧场」和
「AI」点进去是同一份数据、名字还跟内容没关系。现在 CMS 那边按源自己的 `type_pid` 分类树生成入口
（实测 `cj.lziapi.com` 出到 首页/电影/连续剧/动漫/综艺/其他/我的），一维分类退成一个「影视库」。
两个新坑：苹果CMS 查父类返回 0 条（坑 19）、XML 接口的 `<class>` 没有 `type_pid`（坑 20）。
**首页那排标签也修好了**：上一版把 `popularTags` / `tagGroups` 清成空数组，CMS 源上首页一个胶囊
都没有、「全部类型」页整页空白。现在标签走**分类**（`tag-list` = `/api/videos?cate_id={slug}`，
CMS 的搜是标题匹配，点标签等于白点）、每个频道轮流挑一个（从头截 8 个全是电影片的子类）。
第三个坑：`adaptTags` 把不认识的 group code 一律折进 `other`（坑 21）。
**抽象边界推后了一格**（10-16 下午）：① 字段改名从 Java 挪进配置 —— 适配器不再认字段名，只按 `web.fields` 把命中的键原样搬过去（同一个族里换字段名的站不用改代码了）；② `paths` 支持 https 绝对地址、`origin` 可以留空 —— 接口开在别的域名、或者路径不在 `/api/` 下面的站，现在纯配置能接；③ `CmsAdapter` 头上写清了它是**族适配器**不是站适配器。顺带发现并补上一条文档漏项：**REST 响应最外层必须有 `data` 信封**。

**又推远一格**（10-16 傍晚）：加了 `web.shapes` —— 响应形状映射（`featured: "recommend"`
这种，直译「拿站点的 D 当我的 A」）+ 带点的字段路径 + `{div:[a,b]}` 一个除法算页数 +
可配置的 `data` 信封。分界线画在「声明 vs 计算」：`shapes` 的键和值形态 `build.sh`
和 `UserSource.validate()` 两头都查，因为它拼错了不会自己报错、只会静默不生效。

**账号后端自建完成**（10-16 傍晚）：`server/account/` —— PostgreSQL 16 + FastAPI 写的
**Parse REST 子集**，跑在云服务器 149.104.21.177 上，Caddy 剥 `/api` 前缀转发。
客户端只改了**一行**（`HuangGuoApp.smali` 里的 server 地址）—— 因为这 APK 里打包了
完整的 Parse Android SDK，原版挂的是 Back4App，沿用协议最省事，拆掉它要动 2000 多行 smali。

真机全通：注册 / 登录 / 收藏 / 观看历史 / 换账号隔离 / 退出重登恢复 / 头像上传。
隔离那几条是拿另一个账号打同一批请求验的（直取 id、改、删全部 404）。

顺带补了一个**真缺口**：播放进度原来只写进 localStorage 的 `hgpref:progress`，
从没送到原生侧 —— 所以「我的 → 观看历史」永远是空的，也永远同步不上去。
`AppStore.recordProgress()` 其实**现成就有**，只是零调用者（原版大概是从抓 HTML
那条路调的，那条路整块删掉时把它一起带走了）。现在走 `__progress:` 指令接上了。

踩到的两个坑值得记：
1. **`__` 前缀**：桥上的指令判断原本写的是 `"__source:"`，于是 `__progress:` 掉进
   路径检查里，报「路径既不是 / 开头也不是 https」。改成按 `"__"` 判断 ——
   多看几个字符没有任何好处，却意味着每加一种指令都要回来改 smali。
2. **进度单位是毫秒**：`AppStore.library()` 里有一句
   `if (!completed && position <= 5000) 跳过`，判的是「看了不到 5 秒不算历史」。
   送秒进去的话，看了 456 秒变成 456「毫秒」，正好落在闸门下面 ——
   记录写进去了（`get-progress` 读得回来）但列表里一条不显示。

发版走 `./build.sh release`（打包→证补丁集→刷补丁→再打包→报 sha256），别手动 cp ——
踩过一次：验证自检时往 site-default.json 塞的临时 shapes 被发进 APK，而 verify 照样说「完全一致」
（`site-web.js` 是生成的、不参与比对）。见坑 22。

**AGE动漫 接进来了**（`AgedmAdapter.java` + 粘 `www.agedm.io` 自动生成配置）。
它的首页和列表本来纯配置就能接（实测过），卡在详情 ——
`playlists{源:[[集名,地址]]}` → 平的 `episodes[]` 是**结构重排**，属第 ⑥ 层，只能写代码。
播放绕过去了：拿标题去同名苹果CMS 采集站匹配（标题必须完全一致，不猜），
真机 `readyState=4` 在播。
详见 [`docs/history/2026-10-16-用户自带源与适配层.md`](docs/history/2026-10-16-用户自带源与适配层.md)。

**已完成（2026-10-16）**：红果源整块移除（723KB smali）、用户自带源（读写校验 + `__source`
桥 + 首次设置页 + 运行时时更新 web 配置）、原生播放器摘除（63 个文件 660KB）、
抓 HTML 那套整块移除（`SiteRepository` 3959→1445 行、`SiteProfile` 1518→977 行，共 3055 行）、
HTTP 层换 OkHttp + DoH、前端端点全配置化（`site-default.json` 单文件 scheme）、
`app.js` 拆出 `cover.js` 与 `adapters.js`、字段别名层（16 个角色）。

---

# 当前主线：兼容苹果CMS / TVBox

## 背景：拆开看是两件事，价值差很多

实测一份真实的 TVBox 单仓配置（`qist/tvbox` 的 `0821.json`，23535 字节，86 个源）：

| 类型 | 占比 | 是什么 | 可行性 |
|---|---|---|---|
| `type 0/1` 苹果CMS 采集 | **7 / 86** | 一条 URL，纯数据接口 | 直接做 |
| `type 3` spider JAR | **79 / 86** | 指向要下载执行的 JAR | 要 `DexClassLoader`，不做 |

**所以一期独立可交付**：不依赖 TVBox 配置，用户直接填一个 `api.php/provide/vod` 地址。

---

## 一期：苹果CMS 适配器 —— **已完成**（2026-10-16 真机验证）

```text
1.1 自动识别        ✅
1.2 CMS 客户端      ✅ JSON + XML 两种接口
1.3 字段映射        ✅
1.4 播放链路        ✅ 暴風源和量子 XML 源都播通了
1.5 前端文案        ⬜ 用户决定不改（没必要）
1.6 验收            ✅ 首页/分类/详情/搜索/播放全过
                    ⬜ build.sh 的配置自检还没加 CMS 分支
```

**实测结论（用手机测，我本机走代理美国 IP 全被 403 地域封锁）：**

```text
暴風  bfzyapi.com          分片 0000000.ts  video/mp2t     → 能播
索尼  suoniapi.com         标准 HLS 主播放列表            → 能播
量子  cj.lziapi.com/xml/   XML 接口，/share/ 与 m3u8 双线路 → 能播
百度  api.apibdzy.com      index0.jpeg  image/jpeg        → 播不了（图片混淆）
```

**3 个能播 / 4 个测过。** 图片混淆那种要在分片层解密，是 TVBox 用自研播放器解决的一层，
**不做**。

### 已落地的文件

```text
java-src/com/huangguo/mobile/CmsAdapter.java   约 340 行，三个动作 + 字段映射 + 线路选择
java-src/com/huangguo/mobile/CmsXml.java       约 190 行，XML → JSON 同形
java-src/com/huangguo/mobile/UserSource.java   detect 派发 / 干跑校验（适配器已拆到 *Site.java）
smali/com/huangguo/mobile/SiteProfile.smali    新增 rule(String)，读标量配置
```

### 一期踩过的坑（都记着，别再犯）

**① 探测要拉两次，结果互补。** 裸地址给 `class[]` 不给 `vod_pic`，`?ac=detail&pg=1`
反过来。少任何一边都不行——没有 `vod_pic` 就提取不出封面域名，而空的 `allowedHosts`
会在 `SiteProfile` 构造时抛「缺少封面域名」，那是 `Activity.onCreate` 里，
**表现是一装完就闪退，用户连设置页都进不去**。

**② 存配置前必须干跑一次 `SiteProfile` 构造。** 否则一份坏配置把 App 永久锁死。
现在 `__source:set` 会先 `new SiteProfile(context, config)` 试一遍，抛异常就拒绝落盘。

**③ 封面要显式覆盖两块。** `mode: "plain"`（CMS 是普通 JPEG，内置模板是 aes-cbc，
照内置那套解密会把图毁掉）+ `allowedHosts`（`coverUrl()` 逐个精确比对 host，
不匹配直接抛「无效封面域名」）。

**④ CMS 分流只接 `/api/` 开头的路径。** 封面是绝对 URL 也走 `AiiHttp.get`，
全路由给适配器的话会被以「不认识的路径」拒掉，表现是封面全挂。

**⑤ 界面常量的 key 必须是 `adult` / `ai`。** 那是前端 `state.tab` 的取值。
写成 `main` 前端读不到，静默退回黄果那套硬编码文案。

**⑥ `categoryTitles` 是字符串不是数组。** 塞数组进 innerHTML 会变成
「电影片,连续剧,综艺片,…」四十个名字连成一行的巨标题。前端加了类型检查兜底。

---

### 1.1 配置识别（自动识别，不给用户加选择）

用户贴地址 → Java 看响应形状决定用哪个适配器：

```text
{origin, web}      → REST（现在这种）
{code, class[]}    → 苹果CMS
{urls[]}           → TVBox 多仓（二期）
{spider, sites[]}  → TVBox 单仓（二期）
```

**落点**：`UserSource.fetch()` 之后、`validate()` 之前加一层 `detect()`，把结果写进
`adapter` 字段。`validate()` 按 adapter 分支校验（CMS 不需要 `web.paths`）。

**验收**：贴一个 CMS 地址，设置页不报错，存下的配置里 `adapter: "cms"`。

### 1.2 CMS 客户端（三个动作）

已实测确认的接口形状：

```text
取分类  GET ?ac=list
        → {code, class:[{type_id:1, type_name:"电影"}, …]}

取列表  GET ?ac=detail&t=<type_id>&pg=<page>
        → {code, page, pagecount, list:[{vod_id, vod_name, vod_pic, vod_remarks, …}]}

取详情  GET ?ac=detail&ids=<vod_id>
        → {code, list:[{…, vod_play_url, vod_play_from, vod_content}]}
```

**落点**：新建 `java-src/com/huangguo/mobile/CmsAdapter.java`。

### 1.3 字段映射（CMS → 我们内部 shape）

**这是整个计划最关键的一步。** 适配器负责让外部源的响应「看起来像我们的站」，于是
JS 的 `adapt*`（那 16 个字段别名角色）**一行不用改**。

| CMS | 我们 | 备注 |
|---|---|---|
| `vod_id` | `id` | |
| `vod_name` | `title` | |
| `vod_pic` | `cover` | |
| `vod_remarks` | `badge` | "更新至12集" 这类 |
| `vod_score` | `score` | |
| `vod_content` | `description` | **要去 HTML 标签**，实测是 `<p>…</p>` |
| `vod_play_url` | `episodes[].{number,title,source}` | 见 1.4 |
| `class[].type_id/type_name` | `categories` | 覆盖 `web.categories` |

对应的语义转换：`/api/home`、`/api/videos/{id}` 这些路径**从「黄果站的真实路径」升级成
「我们内部的动作词汇表」**，适配器负责实现它。

### 1.4 播放链路

```text
vod_play_from: "dbm3u8"                              线路名
vod_play_url:  "第1集$https://…/index.m3u8#第2集$…"
               └ # 分集    └ $ 分「集名 / 地址」
```

**里面是 m3u8 直链**，我们的 WebView HLS 播放器直接吃，不需要解析器或嗅探。

注意点：
- 线路可能有多条（`vod_play_from` 用 `$$$` 分隔），先取第一条或做成可选
- 集名可能是空的，要给个 `第 N 集` 兜底
- 地址可能是**分享页**而不是 m3u8（部分源），那种要 `parses[]` 才能解——**一期先不处理**，
  遇到就让播放报明确的错，别静默失败

### 1.5 前端

- 设置页文案：从「填一个配置地址」改成能说清支持哪几类
- 错误回显：识别失败时告诉用户「这不像一个已知的源」
- 不做「类型选择」——自动识别，用户不需要知道适配器是什么

### 1.6 验收（真机）—— 已通过

```text
首页 20 条带封面     ✅   分类翻页（pages=249）  ✅
详情 336 集          ✅   搜索 20 条            ✅
点播放出画面         ✅   无崩溃 / 无封面失败    ✅
build.sh 配置自检    ⬜   还没加 CMS 分支
```

---

## 二期：TVBox 配置

### 2.1 多仓 / 单仓识别

```text
多仓  { "urls": [ {"url": "./99188.json", "name": "…"} ] }   ← 94 字节，先拉子配置
单仓  { "spider", "sites": […] }                             ← 相对路径按父地址解析
```

**注意**：`./99188.json` 是相对路径，要按父配置的 base URL 解析。

### 2.2 站点选择

存选中结果：

```json
{ "adapter": "tvbox", "config": "<原配置URL>", "siteKey": "百度", "sites": [...] }
```

设置页列出 `sites[]`（`key` / `name` / `type`），选中 `type 0/1` 就转成一期的 CMS 适配器
（`sites[].api` 本身就是 CMS 接口地址）。

### 2.3 `type 3` 置灰

**必须在 UI 上说清楚**：86 个源里 79 个是 `type 3`，一期二期做完用户能挑的里**只有 7 个
能用**。别让人以为「兼容了 TVBox」。

---

## 三期：spider JAR —— 不做，记着理由

要做的话得：下载 JAR → `DexClassLoader` → 反射实例化 `csp_XXX` → 实现 CatVod 的 Spider
接口契约（`init` / `homeContent` / `categoryContent` / `detailContent` / `searchContent` /
`playerContent`）→ 还要处理不同 fork 的接口差异。

**代价是执行任意下载代码**，这正是 TVBox 生态「源里带毒」的根因，也是我们前几轮明确
拒绝的那条路。建议永久置灰。

---

# 其它待办

## 账号后端接到自己的服务器 —— **已完成**（核实于 2026-10-17）

**已上线的形态**：`HuangGuoApp.onCreate` 里 serverUrl 指向自己的 VPS：

```smali
const-string v0, "https://149.104.21.177.sslip.io/api/"
Parse.initialize(appId, clientKey, v0)
```

自建服务在 `server/account/`（Parse REST 子集 + PostgreSQL），VPS 上以 Docker 跑着
`account-api-1` + `account-db-1`（postgres:16-alpine），和 Caddy 同机。

**appId / clientKey**：还是 `res/values/strings.xml` 里那两个（`back4app_app_id` /
`back4app_client_key`，名字是历史遗留），服务端 `PARSE_APP_ID` 与之一致，不然每个
请求都是 403。要换 key 得两端同时改。

**核实记录**（2026-10-17）：`GET /api/classes/_User` 带 appId 请求返回
`{"code":206,"error":"没有 session token"}` —— Parse REST 语义正确，链路活着。
smali 里已搜不到任何 `parseapi.back4app.com` 引用。

**遗留**（不紧急）：字段名 `back4app_*` 只是名字难看，换名要动 `strings.xml` +
`R$string.smali` 两处，属于「纯洁癖项」，单独一轮做。

---

## 建仓库

一直悬着，因为「141MB smali 要不要入库」没定。现在补丁集形态已经跑通，随时可以提。

```text
chengzi-v0.3.15.apk     8.4MB   原始样本，clean 路径的唯一输入，要入库
patches/                684K    22 个文件 + 76 条删除记录，相对样本的全部改动
java-src/ java-stubs/           编译成 classes5.dex 的源码
build.sh / smoke.sh / gen-web-config.py
docs/ + README.md + LICENSE
```

`work/shells/cz_repack/`（146MB）不入库——`./build.sh clean` 能从样本 + 补丁精确重建。

**决策点**：`work/shots/` 那 20 张验证截图（7.6MB）入不入库。现在 `.gitignore` 忽略了，
但 README 和主文档引用了其中 9 张，不入库的话新克隆会看到断链。

---

## 双向滑动

主文档第八节那张表里**唯一还没做的功能项**：现在是单向 feed（上下滑切集），左右滑切剧没做。

2026-10-16 补一句：左右滑动这条轴已经给了「拖动进度」（见主文档第十四节），屏幕横向
被 seek 占着了。要做左右滑切剧，只能二选一：要么把 seek 挪走（比如只在暂停时横滑调
进度、播放中横滑切剧），要么切剧走别的入口（详情页选集、播完自动连播）。

---

## 资源名换掉 `orange_*`

`orange_icon_background` / `orange_icon_foreground` / `orange_logo`——名字是橙色主题时代
留下的，内容早就是白底黑 A 了。

改要同步三处：`res/values/public.xml` 的 id、`R$color.smali` / `R$drawable.smali` 的字段、
`res/mipmap-anydpi/ic_launcher.xml` 的引用。

**纯洁癖项**：用户永远看不到资源名。风险中等（动 `public.xml` 的 id 可能让 aapt2 重排资源
ID，把 smali 里的 R 常量对错位），收益是零。**要做单独一轮，别跟别的改动混。**

---

## 包名 `com.huangguo.mobile`

**改包名等于换一个应用**：覆盖安装会失败、老用户得先卸载、签名链路重走一遍，而且 smali 里
所有 `Lcom/huangguo/mobile/...` 的类型引用都要跟着改。

绑在一起的还有：`HuangGuoApp` 类名、它的 `.source` 调试属性、FileProvider 的 authority
（`com.huangguo.mobile.updates`）。

**建议**：除非要上应用商店，否则不动。

---

## `app.css` 的可读性

245 行却有 54KB——平均每行 224 字节，大段是压缩过的长行。不影响运行，但下次改样式很难定位。

展开成一行一规则，或者按区块拆成几个文件（`app.css` + `feed.css` + `settings.css`）。

---

## 已知的取舍（不是待办，是记着）

**恢复出厂设置不清 `content-access`，也不动账号。** 前者清了会让 `ContentMode` 的内存态
（`huangguo`）和盘上的解锁位对不上，`require()` 当场给每个桥指令抛「内容已切换，请重新打开」
（见「用户自带源与适配层」坑那一节的七之二）。而它本来就不是用户数据 —— 每次冷启动前端都会
自动 enter-private + confirm-private-age 写回来。后者是有意的：清的是**这台设备**，云端片单
留着，重新登录就回来了。

**出厂不带源。** 装完是空的，用户自己填。这是有意的——把「源提供者」和「工具提供者」
分开。代价是首次体验差（空界面 + 设置页），转化率会掉。恢复出厂设置之后也是这样一屏，
两个入口现在是同一个动作（`__source:restart`）。

**`headers` 不能热更新了。** 切断订阅前，站点改 UA/Referer 策略还能推一版 `site.json` 救；
现在只能重新发包。走 `/api/` REST + 钉死 origin 的必然代价。

**域名漂移不自愈。** `origin` 在配置里，站点换域名就得让用户改配置（比重新打包好，
但仍要用户动手）。

**`huangguo-data` / `huangguo-avatars` 这类 SharedPreferences 文件名没改。** 改了会丢
本地的观看历史、收藏、设置——而它们用户永远看不到。

**`/api/` 路径是内部契约，不是站点真实路径。** 一期做完之后这个性质会更明显：
适配器负责让外部源「看起来像我们的站」，JS 的 `adapt*` 才能一行不改地复用。
代价是语义上有点绕。
