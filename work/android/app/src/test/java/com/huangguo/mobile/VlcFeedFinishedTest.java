package com.huangguo.mobile;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * feed 末集「已看完」落盘判据的回归（H-2）。
 *
 * ── 这段为什么必须有测试 ──────────────────────────────────────────────
 * feed 模式 {@code EndReached} 是这么写的：
 *
 * <pre>
 *   ended = true; sawEnd = true;
 *   persistFeedProgress();   // 想把「看完了」写进 prefs
 *   ended = false;           // 立刻置回：紧接着要自动滑下一集
 * </pre>
 *
 * 而退出/退后台时 {@code onDestroy} 还会再调一次 {@code persistFeedProgress()}。
 * 如果落盘只读 {@code ended}，这一次读到的就是已经复位的 false ——
 * 「看完最后一集」在观看历史里永远记成「没看完」（真机上极难二分，因为要正好
 * 看完末集再退出）。
 *
 * <p>修复把 {@code record.put("finished", ended)} 换成
 * {@code record.put("finished", feedFinished(ended, sawEnd))}，用 {@code sawEnd}
 * 留住「本场播完过一整集」这个事实。这里钉住那条判据，并用真实的事件顺序模拟
 * 「末集 EndReached → 退出 onDestroy 再落盘」两拍，证明第二拍不会被覆盖成 false。
 */
public class VlcFeedFinishedTest {

    /** 判据本身：ended 或 sawEnd 任一为真即算看完。 */
    @Test public void 判据_ended为真算看完() {
        assertTrue(VlcPlayerActivity.feedFinished(true, false));
    }

    /** 末集 EndReached 复位 ended 之后，靠 sawEnd 仍算看完 —— 这是 H-2 的核心。 */
    @Test public void 判据_ended已复位但sawEnd为真仍算看完() {
        assertTrue(VlcPlayerActivity.feedFinished(false, true));
    }

    /** 两者都为假才是没看完。 */
    @Test public void 判据_两者皆假才算没看完() {
        assertFalse(VlcPlayerActivity.feedFinished(false, false));
    }

    /**
     * 端到端模拟真实时序（feed 末集）：
     *
     * <ol>
     *   <li>EndReached：ended=true; sawEnd=true; 落盘（应 finished=true）; ended=false;</li>
     *   <li>onDestroy 退出：再落一次盘（ended 已是 false）——</li>
     * </ol>
     * 断言第二拍落盘仍然是 finished=true，不被覆盖成 false。
     */
    @Test public void 末集看完后退出再落盘不被覆盖成没看完() {
        boolean ended = false;
        boolean sawEnd = false;

        // 第 1 拍：EndReached 分支（对照 VlcPlayerActivity:2028-2032）
        ended = true;
        sawEnd = true;
        boolean firstWrite = VlcPlayerActivity.feedFinished(ended, sawEnd);
        ended = false;   // 自动滑下一集，立即复位

        // 第 2 拍：退出 onDestroy 再落盘（对照 :674 再调 persistFeedProgress）
        boolean secondWrite = VlcPlayerActivity.feedFinished(ended, sawEnd);

        assertTrue("末集 EndReached 那一拍应写 finished=true", firstWrite);
        assertTrue("退出再落盘不能把已看完覆盖成 false", secondWrite);
    }

    /**
     * 反例护栏：换集（onNewIntent）会把 sawEnd 清掉（:726），
     * 所以「上一部看完」不会串到「下一部没看完」上 —— 清掉后两者皆假。
     */
    @Test public void 换集清sawEnd后新一轮为未看完() {
        boolean sawEnd = false;   // onNewIntent :726 sawEnd = false
        boolean ended = false;
        assertFalse(VlcPlayerActivity.feedFinished(ended, sawEnd));
    }
}
