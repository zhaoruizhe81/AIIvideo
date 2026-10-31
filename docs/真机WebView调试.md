# 真机 WebView 调试：把 App 里的页面接进 Chrome DevTools

2026-10-18 第一次用。起因是查「贴源市场清单为什么报错」，十分钟内定位到 JS 侧，
省掉了「加日志 → 重新打包 → 装真机」那一圈。

前提只有一个：`MainActivity` 里开了 `WebView.setWebContentsDebuggingEnabled(true)`
（onCreate 里，跟着 `FLAG_DEBUGGABLE` 走）。**2026-10-31 起发布版关掉了这个开关**
——以前无条件打开，等于每台装了 release 包的手机都留着检查器；现在只有 debug 包
（或 manifest 里显式 `android:debuggable`）能连。要调真机页面，先
`./gradlew :app:assembleDebug` 装个 debug 包。

---

## 一、找 socket 名并转发

DevTools 通道是个 Unix domain socket，名字里带进程号：

```bash
PID=$(adb shell pidof com.huangguo.mobile | tr -d '\r')
adb shell cat /proc/net/unix | grep -o "webview_devtools_remote_[0-9]*" | sort -u
# webview_devtools_remote_26818

adb forward tcp:9333 localabstract:webview_devtools_remote_$PID
curl -s http://127.0.0.1:9333/json/list
```

返回里有 `webSocketDebuggerUrl`，那就是这个页面的调试入口。App 重启后 pid 会变，
`adb forward` 要重做一次。

---

## 二、从命令行执行 JS

`websocket-client` 够用，注意**必须关掉 Origin**：Chromium 会拒绝带
`Origin` 的握手（`403 Rejected an incoming WebSocket connection from …`）。

```python
import json, sys, websocket

ws = websocket.create_connection(sys.argv[1], timeout=120, suppress_origin=True)
ws.send(json.dumps({
    "id": 1, "method": "Runtime.evaluate",
    "params": {"expression": sys.argv[2], "awaitPromise": True, "returnByValue": True},
}))
while True:
    msg = json.loads(ws.recv())
    if msg.get("id") == 1:
        print(json.dumps(msg["result"], ensure_ascii=False))
        break
```

```bash
WS=$(curl -s http://127.0.0.1:9333/json/list | python3 -c "import json,sys;print(json.load(sys.stdin)[0]['webSocketDebuggerUrl'])")
python3 cdp.py "$WS" "typeof bridgeCall"
```

`awaitPromise: true` 是关键 —— 桥调用全是 Promise，不 await 拿到的是
`{}` 或者一个 pending 的壳。

---

## 三、能干什么

**直接调桥，不用点界面**。`bridgeCall(action, args)` 在 `app.js` 里是全局的，
`setup.js`、`feed-player.js` 都走它：

```js
JSON.stringify(await bridgeCall('detail', { id: '__source:get' }))
JSON.stringify(await bridgeCall('detail', { id: '__source:fetch?url=' + encodeURIComponent('https://…') }))
```

**走完整交互流程**，比截图比对靠谱：

```js
document.getElementById('setup-input').value = 'https://149.104.21.177.sslip.io/market.json';
await window.HGSetup.submit();
document.getElementById('setup-error').textContent;          // 空 = 没报错
document.querySelectorAll('.setup-market-check').length;     // 8
```

**看页面实际跑的是哪份 JS**。热更新会把 `assets/` 那份换掉，本地源码对了不等于
设备上的对：

```js
document.querySelectorAll('script').length
performance.getEntriesByType('resource').map(r => r.name).filter(n => n.endsWith('.js'))
```

---

## 四、坑

**`pageId` 换了要重新取。** `adb forward` 之后 `/json/list` 里的 id 会变，脚本里别写死。

**大段中文别走命令行拼字符串。** 命令行参数里的引号、换行、中文都可能被 shell 吃掉。
要么用 base64 传进去由页面自己 `TextDecoder` 解，要么分成几次 `ta.value += \`...\``
追加（模板字符串里的真换行是合法的 JS）。

**只读不自证。** 页面里 `submit()` 成功只证明前端流程通，`__source:set` 写盘、
Activity 重建这些还是得看 logcat（`adb logcat -s AIIvideo:*`）。前端和 Java 各有各的
日志，两边都看。

**改完别忘了关转发**：`adb forward --remove tcp:9333`。

---

## 五、跟 logcat 怎么配合

| 现象 | 看哪儿 |
|---|---|
| 界面报错但不知道谁抛的 | DevTools → `Runtime.evaluate` 里手动调那条桥，看返回 |
| 桥返回 ok，界面还是错 | 前端状态问题，在页面里读 DOM（`#setup-error` 的 textContent） |
| 网络请求本身失败 | `adb logcat -s AIIvideo:*`，`[http]` 前缀那几行有 URL 和耗时 |
| 崩溃 / ANR | `logcat -b crash`，或者插件面板的崩溃快照 |

2026-10-18 那次就是靠「桥手动调一次返回 ok」把嫌疑从 Java 侧整个排除掉的，
剩下的范围立刻缩到 `offerMarket` 那十几行。

---

## 六、拿它当「可达性探针」：跨域请求到底谁挡的

排查 CDN / 静态域被挡时，最省时间的做法不是改代码试，而是在**已经过了盾的
那个 WebView 里**直接 fetch，和 okhttp 的结果对照。同一个地址、同一台手机、
同一个出口，两边不一样，就说明挡的是浏览器栈那一层（TLS 指纹 / UA），
而不是 IP、cookie 或 Referer。

```bash
python3 - <<'PY'
# 一次 evaluate，带超时，别让挂住的 fetch 把 CDP 一起拖死
expr = """(async()=>{
  const t=(u,ms=8000)=>Promise.race([
    fetch(u).then(async x=>u+' -> '+x.status+' | '+(await x.text()).slice(0,120)),
    new Promise(r=>setTimeout(()=>r(u+' -> TIMEOUT'),ms))]);
  return JSON.stringify([await t('https://missav.live/cn'), await t('https://surrit.com/')],null,1);
})()"""
PY
```

2026-10-18 的实测就靠这一条定位：`surrit.com` 在 WebView 里 200、
okhttp 里 403 —— 于是取流改走 WebView（见 missav 那份文档第四节）。

### 两个必须知道的细节

**`credentials:'include'` 会让跨源请求直接失败。** 对方 CORS 回
`Access-Control-Allow-Origin: *` 时，浏览器不允许带凭据，报
`TypeError: Failed to fetch`。排查时先不带凭据试一次，别一上来就 include。

**回包大就把 `awaitPromise` 关掉。** 一次 evaluate 里串好几个网络请求，
只要有一个挂住，整条 CDP 调用就一起超时。稳定的写法是「先写
`window.__r=null` 启动异步任务，再用短表达式轮询 `window.__r`」。

---

## 脚本：`work/android/scripts/ui.js`

上面那套手搓 CDP 的流程包成了一个脚本。**WebView 里的页面一律用它，不要再截图认字点坐标**
—— `uiautomator dump` 在这个 App 上只会返回 5 个节点、0 个带文字，DOM 在 Chromium 进程里，
View 树看不见（2026-10-23 实测）。

```bash
cd work/android
node scripts/ui.js targets                        # 列页面，确认连上了
node scripts/ui.js text "#content"                # 读某块的内文
node scripts/ui.js click "#search-input"          # 按选择器点，没命中直接退出 1
node scripts/ui.js eval "document.title"
node scripts/ui.js assert "#content .card" --count 12
node scripts/ui.js assert "#content" --text "本周焦点"
node scripts/ui.js wait "#content .card" --timeout 8000
```

它每次运行自己解析 pid 并重做 `adb forward`（进程重启后 forward 会指到死 socket，这个坑
脚本替你绕了）。多台设备用 `HG_SERIAL` 指定，包名默认 `com.huangguo.mobile`。

`assert` 的退出码就是断言结果，可以串进 shell：

```bash
node scripts/ui.js click "#header-settings" && \
node scripts/ui.js assert "#content" --text "内容源"
```

分工：

| 界面 | 用什么 |
|---|---|
| WebView 里的页面（首页、详情、设置、源管理） | 这个脚本 |
| 原生播放器控件（返回、锁屏、倍速） | `uiautomator` + `contentDescription`（`VlcControlsView` 里都设了） |
| 系统弹窗（装包、权限、文件选择） | `uiautomator` |
| 数据层（详情、播放地址、收藏） | 直接 `bridgeCall`，不碰界面 |
