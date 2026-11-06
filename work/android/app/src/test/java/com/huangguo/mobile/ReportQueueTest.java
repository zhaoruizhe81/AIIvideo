package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 队列写回：哪些事件从磁盘上删掉、哪些留着重发。
 *
 * 这里盯的是 2.0.22 修的那个丢数据的洞 —— 旧代码 `acknowledge()` 把整个队列文件
 * 删掉，而实际只发了前 200 条。线上已经有 4 台设备撞上（单日 708 / 539 / 449 / 322 条），
 * 丢的时候日志写的是「发送成功」，所以从来没人发现。
 *
 * 只测纯逻辑（{@link ReportQueue#remaining}）：ReportQueue 本身要 Android Context
 * 和真实文件系统，那是仪器测试的活。
 */
public class ReportQueueTest {

    private static JSONObject event(String name) {
        try {
            return new JSONObject()
                    .put("id", name)
                    .put("at", 1792762200000L)
                    .put("kind", "bridge")
                    .put("name", name);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    @Test
    public void 发成功的剔除剩下的留着() {
        JSONObject a = event("a");
        JSONObject b = event("b");
        JSONObject c = event("c");
        List<JSONObject> items = Arrays.asList(a, b, c);

        List<JSONObject> rest = ReportQueue.remaining(items, Arrays.asList(a, c));

        assertEquals(1, rest.size());
        assertEquals("b", rest.get(0).optString("name"));
    }

    @Test
    public void 一条都没发成功就一条都不删() {
        // 这个分支对应「整批 500/429，整批复原」—— 少写这个判断，整天的队列会被清空。
        List<JSONObject> items = Arrays.asList(event("a"), event("b"));
        assertEquals(2, ReportQueue.remaining(items, new ArrayList<JSONObject>()).size());
    }

    @Test
    public void 全部发成功就全删() {
        List<JSONObject> items = Arrays.asList(event("a"), event("b"));
        assertTrue(ReportQueue.remaining(items, items).isEmpty());
    }

    @Test
    public void 内容相同但不同对象不会互相带走() {
        // 重试刷出来的事件内容可能一模一样（同一张封面失败三十次）。
        // 按 equals 去重会让「一条发成功」把兄弟事件也删掉 —— 必须按身份。
        JSONObject sent = event("same");
        JSONObject notSent = event("same");
        assertEquals(sent.toString(), notSent.toString());

        List<JSONObject> rest = ReportQueue.remaining(
                Arrays.asList(sent, notSent), Arrays.asList(sent));

        assertEquals(1, rest.size());
        assertTrue("留下的必须是没发出去的那一个", rest.get(0) == notSent);
    }

    @Test
    public void 超过_200_条时后半天的事件不被连坐删掉() {
        // 复刻线上 f81ae6ac 那天：单日 708 条。旧逻辑只发前 200 条然后删整个文件，
        // 剩下 508 条跟着消失。新逻辑：sent 里只有那 200 个对象，剩下的必须留下。
        List<JSONObject> all = new ArrayList<JSONObject>();
        for (int i = 0; i < 708; i++) {
            all.add(event("evt-" + i));
        }
        List<JSONObject> sent = new ArrayList<JSONObject>(all.subList(0, 200));

        List<JSONObject> rest = ReportQueue.remaining(all, sent);

        assertEquals(508, rest.size());
        assertEquals("evt-200", rest.get(0).optString("name"));
        assertEquals("evt-707", rest.get(507).optString("name"));
    }

    @Test
    public void 中间夹一条坏事件时两侧都保住() {
        // 二分隔离的收尾：坏事件（第 100 条）进 dead/，它前后那些发成功的
        // 都在 sent 里，剩下的（这里是零条）才写回。前提是 consume 按身份过滤
        // 而不是按前缀切 —— 按前缀切会把 100 之后的一起删掉。
        List<JSONObject> all = new ArrayList<JSONObject>();
        for (int i = 0; i < 10; i++) {
            all.add(event("evt-" + i));
        }
        JSONObject bad = all.get(5);
        List<JSONObject> sent = new ArrayList<JSONObject>(all);
        sent.remove(bad);
        // 注意 sent 已经被 remove 过（identity 相同，List.remove 用 equals，
        // 但这里每个对象内容不同，所以删的确实是第 5 个）。

        List<JSONObject> rest = ReportQueue.remaining(all, sent);

        assertEquals(1, rest.size());
        assertTrue(rest.get(0) == bad);
    }

    @Test
    public void 写回合并两边都保住同id只留一份() {
        // rewrite/release 改名回去之前，目标 .jsonl 可能已被发送窗口内的新事件
        // 重建（claim 之后 enqueue 直接写进同名文件）。合并后两边都必须在；
        // 崩溃残留让同一事件出现在两边时，按 id 只留一份。
        JSONObject b = event("b");
        JSONObject fresh = event("fresh");

        List<JSONObject> merged = ReportQueue.mergeEvents(Arrays.asList(b), Arrays.asList(b, fresh));

        assertEquals(2, merged.size());
        assertEquals("b", merged.get(0).optString("name"));
        assertEquals("fresh", merged.get(1).optString("name"));
    }

    @Test
    public void 内容相同但id不同的两条合并后都保住() {
        // 和 remaining() 那条「重试刷出孪生事件」同一族：身份看 id 不看内容。
        try {
            JSONObject one = new JSONObject().put("id", "one").put("name", "same");
            JSONObject two = new JSONObject().put("id", "two").put("name", "same");

            List<JSONObject> merged = ReportQueue.mergeEvents(Arrays.asList(one), Arrays.asList(two));

            assertEquals(2, merged.size());
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    @Test
    public void 没有id的事件按整行去重() {
        try {
            JSONObject bare = new JSONObject().put("kind", "app").put("name", "x");
            JSONObject twin = new JSONObject().put("kind", "app").put("name", "x");

            List<JSONObject> merged = ReportQueue.mergeEvents(Arrays.asList(bare), Arrays.asList(twin));

            assertEquals(1, merged.size());
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    // ── 高危修复：4xx 毒事件隔离后不得再写回队列（否则无限重发）──────────
    //
    // 现场：send() 走 4xx 分支 → bisect 把发不出去的那一条 isolate 进 dead/、
    // 把发得出去的收进 sentOk。旧代码 consume(claimed, sentOk) 用
    // remaining(one.items, sentOk) 剔除「已发」—— 但被 isolate 的那条不在
    // sentOk 里，于是被当成「没发出去」写回 .jsonl，下一轮 flush 又读到它、
    // 又 4xx、又 isolate、又写回……死循环，dead/ 每轮重复写，backlog 永不递减。
    //
    // 修法：bisect 同时收集 isolated，consume 的剔除口径变成「已发 ∪ 已隔离」。
    // 下面直接盯 remaining(items, sent, isolated) 这条不变式。

    @Test
    public void 四xx毒事件隔离后不再写回队列() {
        // 一轮 flush：5 条，第 3 条（下标 2）是恒 4xx 的毒事件。
        List<JSONObject> items = new ArrayList<JSONObject>();
        for (int i = 0; i < 5; i++) {
            items.add(event("evt-" + i));
        }
        JSONObject poison = items.get(2);
        // bisect 的产物：好的 4 条进 sentOk，毒的那 1 条进 isolated。
        List<JSONObject> sentOk = new ArrayList<JSONObject>(items);
        sentOk.remove(poison);
        List<JSONObject> isolated = new ArrayList<JSONObject>();
        isolated.add(poison);

        List<JSONObject> rest = ReportQueue.remaining(items, sentOk, isolated);

        // 关键断言：坏的进了 dead/ 就该从队列剔除，好的都发掉了 —— 一条都不该写回。
        assertTrue("毒事件已隔离、好事件已发，队列文件里不该再残留任何一条", rest.isEmpty());
        // 再钉死一遍：毒事件绝不能出现在写回集合里（这就是无限重发的直接来源）。
        assertTrue("毒事件不得写回队列", !rest.contains(poison));
    }

    @Test
    public void 毒事件第二轮flush不会被重发且backlog可递减() {
        // 承接上一条：模拟「第二轮 flush 读到的磁盘内容」。
        // 第二轮只会读到上一轮 consume 之后 rewrite 回 .jsonl 的 rest ——
        // 只要 rest 里没有毒事件，它就再也不会被读取、不会再被 post。
        List<JSONObject> all = new ArrayList<JSONObject>();
        for (int i = 0; i < 5; i++) {
            all.add(event("evt-" + i));
        }
        JSONObject poison = all.get(2);
        List<JSONObject> sentOk = new ArrayList<JSONObject>(all);
        sentOk.remove(poison);
        List<JSONObject> isolated = new ArrayList<JSONObject>();
        isolated.add(poison);

        // 第一轮写回的内容（应该为空）。
        List<JSONObject> rewritten = ReportQueue.remaining(all, sentOk, isolated);
        assertEquals(0, rewritten.size());
        // 第一轮「已处理」（已发 + 已隔离）的条数 = 5 = 文件全部条目 → consume 里
        // dropped 会是 5，backlog 正常减去 5（不再有残留条目把 backlog 卡住）。
        assertEquals(5, all.size() - rewritten.size());

        // 第二轮：磁盘上（rewritten）为空 → 没有任何事件会被再次 post，毒事件不复发。
        List<JSONObject> secondRound = ReportQueue.remaining(rewritten, new ArrayList<JSONObject>(), new ArrayList<JSONObject>());
        assertTrue("第二轮 flush 没有东西可发，毒事件不会重发", secondRound.isEmpty());
    }

    @Test
    public void 未发也未隔离的事件仍然留着重试() {
        // 反向保护：429/5xx 那种「整批待重试」或超出单批未发的事件，既不在 sent
        // 也不在 isolated —— 必须原样留着，不能被这次修复顺手删掉。
        JSONObject retry = event("retry");
        List<JSONObject> rest = ReportQueue.remaining(
                Arrays.asList(retry), new ArrayList<JSONObject>(), new ArrayList<JSONObject>());
        assertEquals(1, rest.size());
        assertTrue(rest.get(0) == retry);
    }

    @Test
    public void 二参remaining行为不变_isolated为空() {
        // 既有调用点（2xx 成功分支）继续用两参重载，语义不变。
        JSONObject a = event("a");
        JSONObject b = event("b");
        List<JSONObject> rest = ReportQueue.remaining(Arrays.asList(a, b), Arrays.asList(a));
        assertEquals(1, rest.size());
        assertTrue(rest.get(0) == b);
    }
}
