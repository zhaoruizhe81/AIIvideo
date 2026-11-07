# 刷视频模式（抖音式竖滑 feed）— 设计与任务拆分

> 分支：`feat/douyin-feed`（不动 main）。状态：规划中。

## 目标（已与产品确认）

设置页一个**全局总开关**。开启后：
- 首页推荐流 / 分类列表 / 搜索结果的**浏览**变成抖音式**全屏竖滑 feed**（上下滑切下一个视频）。
- **跨视频**竖滑：一部多集剧只占一位、**只播第 1 集**；上下滑切到列表中下一部剧，**不在剧内滑集**。
- 滑到列表末尾按当前列表 `hasMore` **自动续下一页**追加。
- 刷到某条，**点视频本体 / 右侧「进详情」入口** → 进该视频**详情页**；从详情**返回回到原位置续刷**。
- **全屏沉浸**：隐藏底部 tab，左上角返回退出刷视频态。
- 图文 / 合集 / 无播放地址卡片：**跳过**，不纳入竖滑流。
- **刷走不记观看历史**；只有点进详情认真看才记。多集剧想追剧 → 点「看全剧/选集」进详情。
- 右侧社交按钮（点赞/评论/收藏/分享）**没有真实数据源 → 不做**，只留「进详情」入口。
- 关闭开关 → 立即回到普通列表浏览（点开=单集/整剧连播，维持现状）。

## 现有架构关键事实（已核对源码）

- 前端 H5 `<video>` feed（feed-player.js 的 render/bindVideo 等）是**死代码**，播放全程走原生 `HG_PLAYER.command('native?payload=')`。刷视频要做真的竖滑，靠**原生**。
- 原生 `VlcPlayerActivity` 已有 **竖向 mini-feed**：`RecyclerView + PagerSnapHelper`（`pager`/`pagerLayout`/`feedAdapter`）。
  - payload：`eps`（int[] 同剧集号）+ `index` + `id` + `episode` + `title/cover/source/start/decode/cache/rate`。
  - 每集地址按需回调 `window.__hgNativeEpisode(id, ep)`（0.8s 轮询 + 负缓存）。
  - 形态：`feedMode=eps.length>1`；进度 `persistFeedProgress()`（带 `mode=feed`）；`onNewIntent` 整体替换 payload。

## 技术路线（规划阶段确定，二选一）

- **路线 A · 扩展原生活塞**：给 payload 加 `feedList`（条目数组 `[{id,cover,title,source}]`），`VlcPlayerActivity` 在 `feedList` 存在时把 adapter 数据从「集号」扩成「视频条目」，每条 = 该 id 的第 1 集；`__hgNativeEpisode(id,1)` 取地址；滑到底 callback 前端续页。复用现有 pager/预热/进度骨架，改动集中在原生 + 一条新桥指令 + 前端入口。
- **路线 B · 新增 FeedVideoActivity**：独立 Activity 做跨视频竖滑，不改动现有播放器。更隔离但重复造 pager/预热/中继。

倾向 **A**（复用 mini-feed，最少重复）。规划 task 由架构评估后落定。

## 模块落点（按 docs/模块划分.md）

- 模块 2 播放器：`VlcPlayerActivity` feedList 模式（pager 数据源跨视频化、滑到底回调、单集进度不记历史）。
- 模块 1 桥接：新桥指令（如 `feed-open`）+ `__hgNativeEpisode` 若要支持跨 id 的地址解析扩展。
- 模块 8 前端核心 / 9 前端功能页：设置开关、列表→feed 条目映射与翻页续拉、全屏沉浸壳、点条目进详情/返回续刷。
- 模块 6 存储：开关持久化（prefs 设置项）。

## 待团队规划产出

1. 路线 A/B 决策（评估改动面 / 回归风险）。
2. 每个模块的具体文件级改动清单 + 依赖顺序。
3. 测试点（列表→feed 映射单测、翻页追加、开关关闭回退、滑到底续页、刷走不记历史、全屏退出）。
4. 真机验证步骤。

---

## 接口契约（已定稿 2026-11-07，T1/T2/T3 以此为准）

零新桥指令，复用现有 `native?payload=` 与 `__hgNativeEpisode` 反射注入那套。

| # | 契约 | 定稿 |
|---|---|---|
| H-1 | 拉起 | 复用 `HG_PLAYER.command('native?payload='+encodeURIComponent(json))`。payload 新增：`feedList:[{id,cover,title,source}]`、`index`(从第几条起)、`listToken`(前端生成的列表身份)、`hasMore`。`feedList` 非空即 FEED_LIST 形态。首条地址可不带（原生按 id 现取）。 |
| H-2 | 续页 | 原生滑近尾且 `hasMore` 未在请求 → 反射调 `window.__hgNativeFeedMore(listToken, fromIndex)`；前端拉页后调 `window.__hgNativeFeedAppend(listToken, itemsJson, hasMore)`；原生 `listToken` 仍等于当前才 append + `notifyItemRangeInserted`，否则丢弃（防串台）。 |
| H-3 | 跳过 | FEED_LIST 每条 `__hgNativeEpisode(id,1)` 取不到 / 非 http(s) → 原生自动跳过该条继续下一条，不卡死。 |
| H-4 | 进详情 | 原生反射调 `window.hgFeedOpenDetail(id)`（同 hgResume 模式）；前端 `goDetail(id)`。 |
| H-5 | 返回续刷 | 原生进详情**自保活**（暂停不销毁），返回原位；前端重建 payload 兜底。 |
| H-6 | 刷走不记历史 | 原生 `persistFeedProgress` 落 `mode="feedList"`；前端 `checkNativeReturn` 的 feedList 分支只续位置、**不 report/remember**。 |

## 形态与回归

- 三形态 `SINGLE / SERIES / FEED_LIST` 互斥；判定：`feedList` 非空→FEED_LIST，否则 `eps.length>1`→SERIES，否则 SINGLE。
- `feedMode` 语义保留「用 pager（SERIES 或 FEED_LIST 都为 true）」，另加 `boolean feedListMode` 区分。所有现有 `if(feedMode)` 的 pager 逻辑不动，只在「集号语义」处加 `feedListMode` 分支。

## 范围裁剪（已确认）

- 首页推荐流**本期不做无限流**（滑到底停）；竖滑+续页只在有分页的分类/搜索列表上做。
- 右侧社交按钮不做，只留「进详情」入口。

---

## 迭代 2：锁屏交互 + 右侧入口（2026-11-07 真机反馈）

### A. 锁屏交互改造（模块2 VlcControlsView/VlcPlayerActivity）
现状问题：锁定后「已锁定」chip + 锁图标常驻屏幕，不自动消失；未锁定时锁按钮也常驻左中。
目标（对齐抖音/B站）：
- 未锁定：锁按钮只是普通控件，跟随控制栏一起显/隐（点屏唤出控件时才有，自动隐藏时一起收）。
- 锁定：控件全隐藏，**锁图标也淡出**，屏幕干净只剩视频。
- 锁定态**轻触屏幕** → 浮现小锁图标 + 「双击解锁」小提示，约 2 秒自动淡出。
- 锁定时上下滑切视频/左右滑进度/单击唤控件全部禁用；解锁只能「轻触→浮现锁→双击锁」。
- 去掉常驻「已锁定」chip（改为轻触浮现的短暂提示）。

### B. 右侧入口补两个（抖音式右侧竖排）
现状缺失：刷视频界面右侧没有「进主页/合集」入口和「收藏」。
要加：
1. **进主页/合集按钮**（头像样式，右侧竖排顶部）：点击 → 反射调 window.hgFeedOpenDetail(当前条目 id) → 前端进该视频详情/主页。已有 hgFeedOpenDetail 链路，接上即可。
2. **收藏★按钮**：显示当前条目是否已收藏；点击切换收藏。实现：原生进入某条时反射问前端 `window.hgFeedBookmarkState(id)` 拿 bool → 星标亮/灭；点击时反射调 `window.hgFeedToggleBookmark(id, cb)` → 前端调 api('bookmark', detail)/bookmark-state → 回写星标。
   - 前端已有 api('bookmark', detail) 与 api('bookmark-state',{id})；需在 app.js 新增上面两个 window 钩子，按 id 组 detail 载荷（从 feedContext 条目或查 detail）。
- 右侧竖排布局：头像(进详情) + 收藏★，竖排靠右中；社交按钮（点赞/评论/分享）仍不做。

### C. 右侧「进详情」入口改成圆形封面头像（2026-11-07 迭代2 追加）
抖音样式：右侧竖排顶部入口从几何人形图标 → **圆形**，圈内显示**当前视频封面**（随刷到哪条变），右下角**红底白+圆角标**（纯样式，点了跟圈一样进详情）。点击行为不变（进详情/主页）。
- 原生（VlcControlsView/VlcPlayerActivity）：railDetailButton 换圆形 ImageView（圆形裁切）+ 叠红+角标；activate/换条时更新封面。
- 封面是 AES 加密的，原生拿不到明文 → 反射调前端新钩子 `window.__hgFeedCoverData(id)`，前端用现有 api('cover',{url}) 解密回 data:image/jpeg;base64，原生 BitmapFactory 解码显示。缓存已解密过的，避免每条重复解。
