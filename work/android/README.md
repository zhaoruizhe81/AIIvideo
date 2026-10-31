# Gradle 正面工程（路线 C 第一步）

`work/android/` 是 AIIvideo 的**源码工程**，取代 `work/shells/` 那套 apktool 重打包链路。

原来的形态是「别人的 APK + 补丁集」：`cz_repack/` 里 13933 个 smali 文件，只有 12 个是我们的补丁。现在这些补丁和手写源码都进了正式的 Gradle 工程，`apktool` 和 `patches/` 不再是构建的一部分。

## 构建

```bash
cd work/android
./gradlew :app:assembleRelease      # 出 app-arm64-v8a-release.apk（2026-10-31 的 2.0.28 实测 39.1 MB）
./gradlew :app:assembleDebug        # debug 包
./gradlew :app:compileDebugJavaWithJavac   # 只验编译，秒级
./gradlew :app:testDebugUnitTest           # 单元测试，350 个用例
```

或者用脚本（推荐，日志更清楚）：

```bash
./scripts/build.sh check      # 编译 + 资源 + assets
./scripts/build.sh test       # 312 个单元测试
./scripts/build.sh build      # 出 release APK
./scripts/build.sh install    # 构建 → 装真机 → 冷启动 → 截图
./scripts/build.sh verify     # 清空重建 + 测试 + 断言 APK 内容
```

首次需要 `local.properties` 里的 `sdk.dir`，以及 `keystore.properties`（不进 git）。
没有 keystore 也能出 debug 包，release 会退回 debug 签名。

真机：

```bash
adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk
adb shell am start -n com.huangguo.mobile/.MainActivity
```

## 目录

```
work/android/
├── app/src/main/
│   ├── java/com/huangguo/mobile/     36 个类
│   ├── assets/                        前端（8600 行 JS，全部自有；app.js 按屏拆成 8 个文件）
│   │   ├── free-nodes.json            内置免费节点池（可热更新，见 NodesFeed）
│   │   └── hot-update-public.pem      热更验签公钥（私钥不入库）
│   ├── res/                           只有自己的资源（图标、主题、CSP 配置）
│   └── AndroidManifest.xml            40 行
├── scripts/gen-web-config.py          site-default.json → site-web.js
├── publish-nodes.sh                   发布节点池：校验（+ 可选 commit/push）
├── keystore/                          本地签名（git 忽略）
├── ui.js                             真机页面驱动（CDP，不截图认字）
└── FIX_RULES.md                       反编译产物的修复规则（历史参考）
```

## 和旧链路的对照

| | apktool 链路 | Gradle 工程 |
|---|---|---|
| 构建时间 | 几分钟 | **10 秒** |
| dex 体积 | 15.1 MB | **8.8 MB** |
| 补丁管理 | `patches/` + `.deleted` 清单 | 直接改源码 |
| 第三方库 | 一百多 MB smali 源 | 6 个 maven 坐标 |
| 调试 | 只能装 APK 看日志 | 断点、单步 |

## 被删掉的东西（以及为什么）

`androidx/media3`（31 MB）、`com.google.common`（20 MB）、`org.bouncycastle`（47 MB）
原本只服务于 ExoPlayer 播放链 —— `PlayerActivity` / `HongguoPlayback` / `VideoPlaybackCache`
那几个类早在补丁集里就删了。**我们的代码对它们的引用数是 0**，只剩几个字符串常量。

常量值从 smali 里提取出来放进本地类了：

| 类 | 原来在 | 内容 |
|---|---|---|
| `Text` | `media3...TtmlNode` / `IcyHeaders` | `"id"`、`"all"`、`"body"` 等 |
| `Http` | guava `HttpHeaders` | `"Accept-Encoding"`、`"Range"` 等 |
| `Ctl` | guava `Ascii` | 控制字符字节值 |
| `Mime` | `media3...MimeTypes` | `"image/png"` 等 |
| `Json` | bouncycastle | `"title"`、`"label"` |
| `Constants` | `media3...C` / `Renderer` | `3000`、`10000` |

`org.jsoup` 同理 —— 清理后的 `SiteProfile` / `SiteRepository` 引用数是 0。

## 一个真实的 bug（迁移中引入，已修）

`HotUpdateManager.resource()` 是热更通道的资源拦截器，负责给 WebView 返回
`app.css` / `app.js` / 图片的 `WebResourceResponse`。

jadx 把字节码里的 **v7（MIME 类型）拆成了两个 Java 变量**：

- `str3` —— 初始 `application/javascript`，switch 各分支改写它
- `str2` —— 只有 `html` 分支赋 `"text/html"`，其余分支从不赋值

但最终的 `WebResourceResponse` 构造用的是 v10（= v7），也就是 `str3` 那条线。
jadx 版的 return 写成了 `new WebResourceResponse(str2, ...)` —— **所有 css/js/png 都返回了
错误的 MIME**。

配合响应头里的 `X-Content-Type-Options: nosniff`，浏览器直接拒绝加载样式表。
表现就是：页面 HTML 出来了，但完全没有 CSS，图标变成原始尺寸、布局塌陷。

冷启动不会崩、logcat 里没有异常，只有肉眼看截图才发现。按 smali 的
`:goto_4` → `move-object v10, v7` 对照修好，MIME 统一走 `str3`。

**这类错误的特征**：jadx 还原 `switch` over string 时会打
`/* JADX WARN: Failed to restore switch over string */` 警告，看到这句就要
逐分支对照 smali，不能信任生成的 Java 控制流。

## 另一个真实的 bug：热更从来没生效过（2026-10-18 修）

`HotUpdateManager.unpack()` 把资源包（zip 字节）写进了 `envelope.json`，而
`metadata()` 又拿这个文件去 `verifyEnvelope()` —— 对一堆 zip 字节做 JSON 解析，
必然抛异常。

后果不是「热更失败」，是 `beginSession()` 每次都走 catch：新版本被标成 `blocked`，
`active` 也被清掉，最后 `load(0)` 回内置。表现是**下载了、提示了「下次启动生效」、
重启之后永远是内置版本**，而且一条错误日志都不打。磁盘上那个 `active` revision
是旧 apktool 版留下的，所以从 Gradle 工程重建之后就没人发现这条路是断的。

**这类错误的特征**：反编译/重写时把两个 `byte[]` 参数搞混了（一个信封、一个包），
类型一样、编译一样过。修的时候顺手在 `beginSession` 的四个分支各加了一行
`Log.d("AIIvideo", "[hot] ...")` —— 一个「静默工作、静默失败」的子系统，没有
日志就只能靠猜。

## 2026-10-18：代理分流（DirectHosts）

代理三档开关是全局的，为 91porn 开内置核心后，AGE动漫这类直连可用的源也被
拖着走节点，节点抖就报 SSLHandshake。修法是显式域名表 `DirectHosts`（没有
geosite，只能写显式域名），三个出口共用：

- `AiiHttp`（rawWith / getOnce / getWith）命中表就不挂代理；
- `VlcRelay.wrap` 命中表直接返回 null，VLC 自己直连，省掉中继一跳；
- `XrayConfig.routing()` 把表生成 `domain: [...] → direct` 规则。

**新接直连可用的源时，往 `DirectHosts.HOSTS` 加一行**，三个出口自动生效。
背景与实测数据见 [`../docs/history/2026-10-18-代理分流与播放器修复.md`](../../docs/history/2026-10-18-代理分流与播放器修复.md)。

## 桥指令注册表

`Bridge.dispatch()` 原来是**两段嵌套 switch**：`switch (str.hashCode())` 里 40 个
`str.equals("xxx") { b = N; }` 映射到一个 int，再 `switch (b)` 里 40 个 case 装业务逻辑。
加一条指令要改两处、编号还要手工对齐。

现在是一个 `Map<String, Command>`：

```java
private interface Command {
    Object run(JSONObject args) throws Exception;
}
private final Map<String, Command> commands = new HashMap<>();
```

`dispatch()` 从 429 行缩到 33 行。加指令只需要在 `registerCommands()` 里加一行
`commands.put("名字", args -> {...})`。

**39 条指令**（2026-10-31 实数）：account / app-version / apply-hot-update /
bookmark / bookmark-state / cached / cached-update / change-password /
check-hot-update / check-update / choose-avatar / clear-bookmarks /
clear-content-cache / clear-history / cover / cover-diag / detail / diag /
download-update / get-progress / hot-ready / hot-update-status / http / library /
login / logout / open-player / player / progress / proxy / quit / register /
report / screen-corners / source / spider / sync / theme / window-insets

四个 `content-*` 指令（content-status / enter-private / confirm-private-age /
leave-private）**故意留在 `dispatch()` 方法里**，不进注册表——它们必须在
`contentMode.require()` 校验之前执行，否则切换内容源时会先被门禁拦住。
（双态拆掉后这条门禁只剩历史意义，但守规矩的写法留着。）

`services()` 每次取 `repository` / `store` / `account` 的当前快照。原代码是在 switch
之前用 `synchronized(this)` 一次性抓三个引用，因为 `rebindContent()` 会整体替换它们。
抽成注册表后这个快照语义必须跟着每条指令走，否则实现会在换源后用到旧实例。

### 验证方法（可复用）

真机上跑通全部指令，不靠肉眼看界面：

```bash
# 1. 开 WebView 调试（发布版也开着：setWebContentsDebuggingEnabled(true)，见 MainActivity:155）
adb forward tcp:9222 localabstract:webview_devtools_remote_$(adb shell pidof com.huangguo.mobile | tr -d '\r')
curl -s http://127.0.0.1:9222/json   # 取 webSocketDebuggerUrl

# 2. 用 CDP 逐条打桥指令（注意参数顺序是 request(回调id, 指令名, json)）
#    websocket 客户端要 suppress_origin=True，否则 WebView 拒绝握手
```

完整的配方（含 Python 客户端、`awaitPromise`、往页面里塞长文本的办法）在
[`docs/真机WebView调试.md`](../../docs/真机WebView调试.md)。

判定标准：logcat 里 `未知请求` 出现 **0 次** = 全部指令被注册表接住。
出现 `xxx failed` 是业务失败（没配源、没登录、参数不全），不是路由错误。

## 测试

350 个 JUnit 用例（2026-10-31，41 个测试类），全部在 JVM 上跑，不需要设备，秒级。
其中 30 个默认跳过：联网用例要加 `-Dhg.live=1` 才跑，fixture 用例缺快照也跳过。
（2026-10-31 晚起 +3 类：`CoverTrustStoreTest` 观测集生命周期、`CoverTierTest`
信任三档与硬地板、`CoverParamMemoryTest` 自适应缓存键 —— 封面信任模型改造的
配套，见 `docs/history/2026-10-31-封面信任模型改造方案.md`。）

| 测试类 | 用例 | 测什么 |
|---|---|---|
| `ProxyCoreTest` | 30 | 内置核心的生命周期、端口分配、节点 JSON |
| `Hanime1AdapterTest` | 17 | HTML 解析（首页/分类/搜索/详情/标签面板/播放直链） |
| `UserSourceTest` | 15 | 用户填的源配置能否通过校验，以及拒绝理由是否可读 |
| `SubscriptionTest` | 15 | 订阅解析（裸列表 / base64 / 拒绝 YAML）+ 测速结果排序 |
| `ShareLinkTest` | 15 | 分享链接解析：vmess / vless / trojan / ss / 裸代理 |
| `NodesFeedTest` | 11 | 节点池热更新的准入校验（劫持页、半截文件、全是不支持的协议） |
| `ProxyParseTest` | 10 | 代理串解析：哪些能认、哪些返回 null |
| `UpdateMirrorTest` | 7 | 更新清单取 versionCode 最大的那份，坏数据跳过 |
| `EffectiveProxyTest` | 6 | 内置核心与用户手填地址的优先级 |

`./scripts/build.sh verify` 会连测试一起跑，不过就不出包。

### 两个必须知道的坑

**一、单测的 `org.json` 必须是真实现。** classpath 里 `android.jar` 排在前面，
它的 `org.json` 是空壳 —— `put()` 不存值、`size()` 恒 0。表现是所有断言都"通过"
（因为读出来永远是 null/0），看着全绿其实什么都没测。所以 `build.gradle.kts` 里
显式加了 `testImplementation("org.json:json:20231013")`。

**二、`Hanime1AdapterTest` 的 fixture 不在仓库里。** 那些 HTML 是成人内容站的
真实页面，`.gitignore` 挡掉了。缺 fixture 时 16 个用例会被 `Assume` 跳过并标成
skipped，不会红 —— 但也就等于没测。要跑完整版：

```bash
cd ../shells/fixtures && python3 refresh.py
cp *.html ../../../android/app/src/test/resources/
```

## 后端地址

全部集中在 `Endpoints.java`，业务类里不再出现字符串。换域名只改这一个文件。

```java
Endpoints.API_BASE        // https://149.104.21.177.sslip.io/api/   账号服务，跑在自己的机器上
Endpoints.UPDATE_MANIFEST // 三条 gh 镜像的 JSON 数组，逐个拉、取 versionCode 最大的
Endpoints.hotIndexUrls()  // 三条候选，带时间戳查询串绕 CDN
Endpoints.nodesUrls()     // 三条候选，免费节点池
Endpoints.mirrorsOf(url)  // 给 GitHub 地址派生镜像候选（包下载用）
Endpoints.releaseUrl()    // 清单没给 urls 时的兜底，拼 GitHub Releases
```

原来散在 `HuangGuoApp`、`AccountService`（注释）、`HotUpdateManager`、
`UpdateManager`（两处）—— 换一次要改五处，漏一处就是线上半瘫：
可能热更拉得到、但账号登不上。

**2026-10-18 起「分发」和「账号」分家了。** 账号 API 必须跑在自己的服务器上
（有服务端逻辑）；热更清单、热更包、APK 清单、免费节点池这四样是纯静态文件，
全部搬进 GitHub 仓库 + gh 镜像。理由见 `Endpoints` 里的注释。

## 还没做的

- `UpdateManager` 还是 jadx 反编译产物（1219 行 → 现在 224 行）。它是 7 个类里
  唯一没清理的，反编译形态能跑，需要时再处理。
- `MainActivity` 里还有 51 条 `JADX INFO` 注释，以及 `lambda$dispatch$N` 这类
  机器方法名。功能没问题，只是读起来还有反编译味。
- `MainActivity$$ExternalSyntheticApiModelOutline0` 是 R8 的合成类残留。
- 包名 `com.huangguo.mobile` 和类名前缀 `HuangGuo` 还留着。改它等于换一个应用
  （覆盖安装失败、老用户要卸载），除非上架否则不值得。
- `res/` 还留着 `orange_*` 这套名字，内容早就是白底黑 A 了。改要同步
  `values/public.xml` 的 id 和 `R$drawable`，风险中等收益为零，别跟别的改动混。
- `DualContentRepository.sequelCandidates()` 恒返回空数组——原 `HongguoRepository`
  的续集匹配逻辑在整类重写时丢了。要不要补回来取决于「续集」在现在的产品里还有没有意义。
