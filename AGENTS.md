# AGENTS.md

AIIvideo —— 安卓播放器壳（GPL-3.0），内容全部来自用户自配的源。形态是 WebView 前端 + Java 桥 + libVLC 播放器。文档、注释、commit message 全用中文。

## 目录

- `work/android/` — **唯一真源码**：Gradle 工程（2026-10-17 起取代 apktool 补丁链路）。`app/src/main/java/com/huangguo/mobile/` 是 Java 类，`app/src/main/assets/` 是前端（`app.js` + 按屏拆的 `app-*.js`），`app/src/test/` 是 JVM 单测，`scripts/` 是构建与离线检查。
- `docs/` — 文件名**不带日期 = 现行口径**；带日期的在 `history/`，是当时的现场记录，别照它改代码，也别在原地改它的结论。入口先读 `docs/README.md`（索引）。
- `hot/` — 前端热更通道（签名信封 + 资源包，走 GitHub）。
- `server/` — `account/`（账号 API，跑在自己服务器）、`admin/`、`track/`、`p91/`，各有 README。
- `android-update.json`（仓库根）— APK 自更新清单，发版后**必须**更新并 push。
- `work/shells/` 只剩 `fixtures/`（Hanime1 离线样本，不进仓库）和 `nativetest/` 还有用；`work/` 顶层是工作台脚本。

## 构建与测试

需要 JDK 17 + Android SDK 36 + node（前端降级链上必需，缺了直接停）。

```bash
cd work/android
./scripts/build.sh check     # 编译+资源+assets，秒级
./scripts/build.sh test      # 单测 + 前端降级自检 + JS 钩子
./scripts/build.sh build     # 出 release APK
./scripts/build.sh verify    # 清空重建 + 测试 + 断言 APK 内容（发版前跑这个）
./scripts/build.sh install   # 构建 → 装真机 → 冷启动 → 截图（SERIAL=xxx 指定设备）
node scripts/spider-all-check.js   # spider 源离线回归（另有 model-check / market-check / js-errors-check）
```

- 单测全在 JVM（当前 312 个用例、38 个测试类），不需要设备。联网用例加 `-Dhg.live=1` 才跑；`Hanime1AdapterTest` 的 fixture 不在仓库，缺了会 `Assume` 跳过（不红，但等于没测）。
- 单测 classpath 里 `org.json:json:20231013` 是刻意的——android.jar 的 `org.json` 是空壳，会让所有断言假绿，别删。

## 架构边界

- **代码按 9 个模块维护**：改哪个文件、属于哪个模块、有什么坑，先查 [docs/模块划分.md](docs/模块划分.md)——改动直接落对应模块，不做跨模块顺手修改；文末的跨模块约定（Bridge 单入口、Outbound 单实现、中继 HMAC、pinned DNS、超时参数）别破坏。
- **前端↔原生只走 Bridge 一条路**：`request(回调id, 指令名, json)`。约 40 条指令注册在 `Bridge.registerCommands()` 的 Map 里；`content-status` 一条**故意**留在 `dispatch()` 里（必须在内容门禁校验之前执行；双态的其余 content-* 已于 2026-10-19 拆除）。判定路由全通 = logcat「未知请求」0 次。
- **所有后端地址集中在 `Endpoints.java`**，业务类不写死 URL。分发（热更清单/热更包/APK 清单/节点池）走 GitHub + gh 镜像；只有账号 API 跑自己服务器。
- **出站请求只有一份实现 `Outbound`**（请求头模板 + 代理判定），`AiiHttp`（接口/封面/spider net）与 `VlcRelay`（播放取流）两条通道共用。新加出站请求前先读 `docs/出站请求的两条通道.md`。
- 新接直连可用的源时，往 `DirectHosts.HOSTS` 加一行，三个出口自动生效。
- spider 源 = 一个 json + 一个 js，WebView 沙箱执行（无 DOM），ABI = 1，js ≤ 2MB。写新源看 `docs/spider-接入规范.md`，照抄 `docs/samples/spiders/` 的 15 份样例。
- 前端源码是 ES2021，构建时 `transpileAssets` 降级成 ES2017（minSdk 26 的出厂 WebView）；`site-web.js` 由 `gen-web-config.py` 从 `site-default.json` 生成。两者都是构建产物，改源文件，别改产物。

## 已知的坑

- 代码有 jadx 反编译残留（`UpdateManager` 仍是反编译形态；`MainActivity` 有 JADX INFO 注释）。看到 `JADX WARN: Failed to restore switch over string` 必须对照 smali 逐分支核，不能信还原出来的 Java 控制流。
- 静默子系统必须打日志（惯例 `Log.d("AIIvideo", "[hot] ...")`）——热更通道曾因一个静默失败整条断了没人发现。
- 包名 `com.huangguo.mobile`、类前缀 `HuangGuo`、`res/` 里的 `orange_*` 是历史残留，改了等于换应用（覆盖安装失败），别动。
- 文档里的数字（单测数、APK 体积、版本号）历史上漂过多次——写进文档前现算一遍。
- 真机调试：WebView 调试常开，CDP 端口 9333（`adb forward tcp:9333 localabstract:webview_devtools_remote_$(adb shell pidof com.huangguo.mobile)`），驱动脚本 `work/android/ui.js`。见 `docs/真机WebView调试.md`。

## 发版（无 CI，全手动）

改 `app/build.gradle.kts` 的 versionCode/versionName → `./scripts/build.sh verify` → `gh release create` → **更新仓库根 `android-update.json` 并 push**（字段有硬校验：tag = "v"+versionName、sha256 64 位十六进制等，一条不对整份作废）→ 前端有改动就同时发热更，`HOT_MIN_VERSION_CODE` 设成本次 versionCode（带新桥指令的界面不能发给没有那个桥的旧 APK）。完整清单见 `docs/开发说明.md`「发版清单」。

**仓库里不放 APK**（二进制不进 git 历史；包里烧着账号服务器地址）。

## 约定

- 直接在 `main` 上开发；commit message 中文，前缀 `fix:` / `docs:` / `chore:` / `release:` / `test fixture:`。
- 当天的事写成 `docs/history/YYYY-MM-DD-主题.md`；沉淀成规矩的部分进现行文档（契约→spider-接入规范，字段→站点配置参考，流程→开发说明）。
- 改敏感区域前先读：`docs/README.md`（索引）→ `work/android/README.md`（构建/桥/测试）→ `docs/开发说明.md`（业务与协议总览）。
