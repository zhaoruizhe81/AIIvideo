package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 进度落盘「来源形态」字段（mode）的回归（M-1 中危）。
 *
 * ── 为什么需要这个字段 ────────────────────────────────────────────────
 * 播放器有两条落盘路径，写到<b>同一个</b> prefs key {@code "last"}：
 *
 * <ul>
 *   <li>{@code persist(boolean)} —— 单集模式（feedMode=false 才走）；</li>
 *   <li>{@code persistFeedProgress()} —— 整部剧 feed 模式。</li>
 * </ul>
 *
 * 之前两条记录都没写 mode，前端 {@code checkNativeReturn}（feed-player.js）拿到
 * 一条 {@code last} 只能当「整部剧的某一集」处理：用 {@code session.episode} 去
 * 定位 {@code .feed-page[data-video-id][data-ep]}、挪播放头、播完自动滑下一集。
 * 一旦这一场其实是单集播放（payload 自带的 episode 号可能正好等于当前 feed 的
 * 某页），就会把用户正在看的 feed 页带跑。
 *
 * <p>修复：两条落盘都给 record 加 {@code mode}（{@code feed} / {@code single}），
 * 前端按 mode 区分来源，单集只回收进度、不碰 feed 页。这里钉住取值口径，
 * 与 play_start / play_end 埋点一致。
 */
public class VlcProgressModeTest {

    /** feed 模式落盘恒为 "feed"。 */
    @Test public void 形态_feed模式落feed() {
        assertEquals("feed", VlcPlayerActivity.progressMode(true));
    }

    /** 单集落盘恒为 "single"。 */
    @Test public void 形态_单集落single() {
        assertEquals("single", VlcPlayerActivity.progressMode(false));
    }

    /**
     * 两侧落盘用同一个判据：persist()（单集，feedMode=false）落 "single"，
     * persistFeedProgress()（feed，feedMode=true）落 "feed" —— 二者必不相同，
     * 前端才能可靠区分，不会把单集误当 feed。
     */
    @Test public void 形态_两份落盘可区分() {
        String single = VlcPlayerActivity.progressMode(false);   // persist() 的调用场景
        String feed = VlcPlayerActivity.progressMode(true);      // persistFeedProgress() 的调用场景
        assertEquals("single", single);
        assertEquals("feed", feed);
        org.junit.Assert.assertNotEquals("单集与 feed 的 mode 不能相同，否则前端无法区分", single, feed);
    }
}
