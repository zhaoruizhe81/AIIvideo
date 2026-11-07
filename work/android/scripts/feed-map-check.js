#!/usr/bin/env node
/**
 * 刷视频模式前端映射层离线回归：把「列表卡片」喂给 HGFeed，断言它变成原生竖滑
 * 要的 feed 上下文（过滤/去重/hasMore/listToken 都对）。
 *
 * ## 断言什么
 *
 *   scripts/feed-map-check.js  → 断言 **app-feed.js 的映射**：哪些卡片该进竖滑、
 *   哪些被过滤、同 id 是否只占一位、首页 hasMore 是否恒 false、分类/搜索续页是否
 *   正确前移 nextPage 并 append。它出问题时你要改的是 app-feed.js。
 *
 * 纯逻辑，不碰 DOM/网络；和 scripts/model-check.js 同款（vm + window 空壳）。
 *
 * ## 用法
 *
 *   node scripts/feed-map-check.js
 *
 * 退出码：0 全过 / 1 有断言失败 / 2 环境问题（app-feed.js 缺失或没导出 HGFeed）
 */
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const FEED_PATH = path.resolve(__dirname, '../app/src/main/assets/app-feed.js');

function fail(message) {
  console.error('✗ ' + message);
  process.exit(2);
}

if (!fs.existsSync(FEED_PATH)) fail('app-feed.js 不存在: ' + FEED_PATH);

// ── 沙箱：只给一个 window 空壳。不给 document / localStorage / HG_SOURCE_KEY，
//    app-feed.js 一旦在加载期偷偷依赖它们，这里立刻暴露。
const feedWindow = {};
const feedSandbox = { window: feedWindow, console: { log() {}, error() {} } };
vm.createContext(feedSandbox);
try {
  vm.runInContext(fs.readFileSync(FEED_PATH, 'utf8'), feedSandbox, { filename: 'app-feed.js' });
} catch (error) {
  fail('app-feed.js 执行失败：' + error.message);
}
const HGFeed = feedWindow.HGFeed;
if (!HGFeed) fail('app-feed.js 没有导出 window.HGFeed');
['toFeedEntry', 'toFeedList', 'buildFeedContext', 'appendFeedItems', 'makeListToken']
  .forEach(name => { if (typeof HGFeed[name] !== 'function') fail('HGFeed.' + name + ' 不是函数'); });

// ── 断言工具 ────────────────────────────────────────────────────────────
let failed = 0;
function check(label, predicate, actual) {
  let ok = false;
  try { ok = !!predicate(); } catch (error) { ok = false; }
  if (ok) return;
  failed++;
  console.error('✗ ' + label);
  console.error('   实际: ' + JSON.stringify(actual).slice(0, 500));
}

// 造一张列表卡片（字段够映射用即可）。
function card(id, extra) {
  const item = { id: id, cover: 'https://pic.example/' + id + '.jpg', title: '剧' + id, episodeCount: 12, episodeLabel: '共 12 集', tags: [], category: '' };
  return Object.assign(item, extra || {});
}

console.error('feed-map: toFeedEntry / toFeedList / buildFeedContext');

// ---- toFeedEntry：合法 / 各类过滤 ----
check('合法卡片 → 条目', () => {
  const e = HGFeed.toFeedEntry(card('123'), 'src');
  return e && e.id === '123' && e.cover && e.title === '剧123' && e.source === 'src';
}, HGFeed.toFeedEntry(card('123'), 'src'));

check('非数字 id → null', () => HGFeed.toFeedEntry(card('abc')) === null, 'abc');
check('空 id → null', () => HGFeed.toFeedEntry(card('')) === null, '');
check('无 cover → null', () => HGFeed.toFeedEntry(card('9', { cover: '' })) === null, 'no cover');
check('图文（无集数无 label）→ null', () => {
  const e = HGFeed.toFeedEntry(card('9', { episodeCount: 0, episodeLabel: '' }));
  return e === null;
}, 'image-text');
check('合集（tags 命中）→ null', () => {
  const e = HGFeed.toFeedEntry(card('9', { tags: ['古装合集'] }));
  return e === null;
}, 'aggregate');
check('合集（category 命中）→ null', () => {
  const e = HGFeed.toFeedEntry(card('9', { category: '电影专题' }));
  return e === null;
}, 'aggregate-cat');
check('有集数无 label 也算有效', () => {
  const e = HGFeed.toFeedEntry(card('9', { episodeCount: 3, episodeLabel: '' }));
  return e && e.id === '9';
}, 'count-only');
check('无 source 参数时回落到 window（沙箱里是空串）', () => {
  const e = HGFeed.toFeedEntry(card('77'));
  return e && e.source === '';
}, 'fallback source');

// ---- toFeedList：保序去重 + 过滤 ----
check('toFeedList 保序去重（同 id 只留首次）', () => {
  const list = HGFeed.toFeedList([card('1'), card('2'), card('1'), card('bad'), card('3')]);
  return list.length === 3 && list[0].id === '1' && list[1].id === '2' && list[2].id === '3';
}, HGFeed.toFeedList([card('1'), card('2'), card('1'), card('bad'), card('3')]).map(e => e.id));

check('toFeedList 非数组输入 → 空数组', () => HGFeed.toFeedList(null).length === 0, 'null');

// ---- makeListToken：非空且唯一 ----
check('listToken 非空', () => typeof HGFeed.makeListToken('home') === 'string' && HGFeed.makeListToken('home').length > 0, '');
check('两次 listToken 不同', () => HGFeed.makeListToken('home') !== HGFeed.makeListToken('home'), '');

// ---- buildFeedContext：home 不做无限流 ----
check('home 上下文 · hasMore=false / nextPage=null（本期不做无限流）', () => {
  const st = {
    home: {
      featured: [card('1')],
      homepageRecommend: [card('1'), card('2')],
      homepageNewest: [card('3')],
      recommend: { items: [] }, newest: { items: [] }
    }
  };
  const ctx = HGFeed.buildFeedContext('home', st);
  return ctx.kind === 'home' && ctx.hasMore === false && ctx.nextPage === null
    && ctx.items.map(e => e.id).join(',') === '1,2,3';
}, (() => { try { return HGFeed.buildFeedContext('home', { home: { featured: [card('1')], homepageRecommend: [card('1'), card('2')], homepageNewest: [card('3')] } }); } catch (e) { return String(e); } })());

// ---- buildFeedContext：category 有分页 ----
check('category 上下文 · nextPage/hasMore 正确', () => {
  const st = { category: { items: [card('10'), card('11')], nextPage: 2 }, tab: 'adult' };
  const ctx = HGFeed.buildFeedContext('category', st);
  return ctx.items.length === 2 && ctx.nextPage === 2 && ctx.hasMore === true && ctx.listToken.length > 0;
}, '');

check('category 上下文 · 末页 nextPage=null → hasMore=false', () => {
  const st = { category: { items: [card('10')], nextPage: null }, tab: 'adult' };
  const ctx = HGFeed.buildFeedContext('category', st);
  return ctx.nextPage === null && ctx.hasMore === false;
}, '');

// ---- buildFeedContext：search ----
check('search 上下文 · 带 query、hasMore 正确', () => {
  const st = { search: { items: [card('20')], nextPage: 3 }, query: '爱' };
  const ctx = HGFeed.buildFeedContext('search', st);
  return ctx.items.length === 1 && ctx.nextPage === 3 && ctx.hasMore === true && ctx.query === '爱';
}, '');

// ---- appendFeedItems：续页追加去重 + hasMore 前移 ----
check('append 续页 · 去重追加、nextPage 前移、返回新增条目', () => {
  const ctx = HGFeed.buildFeedContext('category', { category: { items: [card('1'), card('2')], nextPage: 2 }, tab: 'x' });
  const added = HGFeed.appendFeedItems(ctx, [card('2'), card('3'), card('4')], 3);
  return ctx.items.map(e => e.id).join(',') === '1,2,3,4'
    && added.map(e => e.id).join(',') === '3,4'
    && ctx.nextPage === 3 && ctx.hasMore === true;
}, '');

check('append 续页 · 最后一页 nextPage=null → hasMore=false', () => {
  const ctx = HGFeed.buildFeedContext('search', { search: { items: [card('1')], nextPage: 2 }, query: 'q' });
  HGFeed.appendFeedItems(ctx, [card('2')], null);
  return ctx.nextPage === null && ctx.hasMore === false;
}, '');

check('append 全重复 · 不新增、items 不变', () => {
  const ctx = HGFeed.buildFeedContext('category', { category: { items: [card('1')], nextPage: 2 }, tab: 'x' });
  const added = HGFeed.appendFeedItems(ctx, [card('1')], 5);
  return added.length === 0 && ctx.items.length === 1;
}, '');

// ---- 空 state 不炸 ----
check('buildFeedContext(空 state) 不炸、items 为空', () => {
  const ctx = HGFeed.buildFeedContext('search', {});
  return Array.isArray(ctx.items) && ctx.items.length === 0 && ctx.hasMore === false;
}, '');

if (failed) {
  console.error('✗ 共 ' + failed + ' 条失败');
  process.exit(1);
}
console.error('全部通过');
