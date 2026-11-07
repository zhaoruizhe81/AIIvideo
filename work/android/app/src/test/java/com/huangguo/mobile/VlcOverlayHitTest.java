package com.huangguo.mobile;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 迭代2 真机 bug 回归：root 手势层吞掉 overlay 按钮点击的「命中判断」。
 *
 * <p>根因：Activity 的 onRootTouch 挂在 root（父 FrameLayout）上，父的 OnTouchListener
 * 在 dispatch 阶段先于子 View 收到 DOWN；对 DOWN 返回 true 就消费掉整条事件，子按钮
 * （左上锁 / 右侧头像 / 收藏★）永远收不到点击。修法：DOWN 先判「这点是否按在可见的
 * overlay 按钮上」——命中就让 onRootTouch return false 放行。
 *
 * <p>这里钉的是最底层的纯函数 {@link VlcControlsView#hitRect}：屏幕/root 坐标下点是否
 * 落在按钮矩形内。坐标口径务必统一（都屏幕坐标），别一半 getX/Y 一半 getRawX/Y。
 */
public class VlcOverlayHitTest {

    private static final int L = 100, T = 200, R = 146, B = 246;   // 一个 46x46 的按钮

    @Test public void 命中_点在矩形内() {
        assertTrue(VlcControlsView.hitRect(L, T, R, B, L, T));           // 左上角（含）
        assertTrue(VlcControlsView.hitRect(L, T, R, B, 120, 220));       // 内部
        assertTrue(VlcControlsView.hitRect(L, T, R, B, R - 1, B - 1));   // 右下内侧（不含 right/bottom）
    }

    @Test public void 命中_点在矩形外() {
        assertFalse(VlcControlsView.hitRect(L, T, R, B, L - 1, T));      // 左外
        assertFalse(VlcControlsView.hitRect(L, T, R, B, R, T));          // right 边界不含（半开区间）
        assertFalse(VlcControlsView.hitRect(L, T, R, B, L, B));          // bottom 边界不含
        assertFalse(VlcControlsView.hitRect(L, T, R, B, 500, 500));      // 远外
    }

    /** 半开区间 [left,right) × [top,bottom)：App 里邻接两个按钮时不会重复命中。 */
    @Test public void 命中_半开区间邻接不重叠() {
        // 两个上下相邻按钮：上 [0,46)，下 [46,92)
        assertTrue(VlcControlsView.hitRect(0, 0, 46, 46, 10, 45));    // 上按钮最后一行
        assertFalse(VlcControlsView.hitRect(0, 0, 46, 46, 10, 46));   // y=46 归下按钮
        assertTrue(VlcControlsView.hitRect(0, 46, 46, 92, 10, 46));   // 下按钮第一行
    }
}
