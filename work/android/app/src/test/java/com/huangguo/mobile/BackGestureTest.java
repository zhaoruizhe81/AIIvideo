package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 手势返回的进度换算。原来这几个判断埋在 MainActivity 里，跟着 Activity 字段一起
 * 没法测；拆出来之后全是数，直接断言。
 */
public class BackGestureTest {

    private static final float EPS = 0.0001f;

    @Test
    public void 没有触点是退化到系统进度() {
        assertEquals(0.42f, BackGesture.fraction(Float.NaN, 1, 0.42f, 1080, 0, 0), EPS);
    }

    @Test
    public void 还没量到宽度时用系统进度() {
        assertEquals(0.5f, BackGesture.fraction(900f, 1, 0.5f, 0, 0, 100), EPS);
    }

    @Test
    public void 右缘起手进度为零() {
        // EDGE_RIGHT=1：手指贴着右边界（left+f=1000）刚起手，位移是 0。
        // 旧实现把 1 当左缘、还保留了个永不出现的 swipeEdge==2 短路，都是反编译
        // 恢复时把系统常量标反了（EDGE_LEFT=0、EDGE_RIGHT=1）。
        assertEquals(0f, BackGesture.fraction(1000f, 1, 0f, 1000, 0, 100), EPS);
    }

    @Test
    public void 右缘划动按到右边界的距离算() {
        // left=0, width=1000, touch=880 → 位移 120 → 120 + min(lead,42) = 162 → 0.162
        assertEquals(0.162f, BackGesture.fraction(880f, 1, 0f, 1000, 0, 100), EPS);
    }

    @Test
    public void 左缘划动按到左边界的距离算() {
        // EDGE_LEFT=0：touch=200 → 位移 200 → 200 + min(100,70) = 270 → 0.27
        assertEquals(0.27f, BackGesture.fraction(200f, 0, 0f, 1000, 0, 100), EPS);
    }

    @Test
    public void 提前量封顶在三成五() {
        // 位移 20，35% 是 7，leadPx 给 100 也只能加 7 → 0.027
        assertEquals(0.027f, BackGesture.fraction(980f, 1, 0f, 1000, 0, 100), EPS);
    }

    @Test
    public void 位移超过边界时夹到一() {
        // touch 跑到左边界外面（负数），右缘的位移算出来比宽度还大 → 夹 1
        assertEquals(1.0f, BackGesture.fraction(-200f, 1, 0f, 1000, 0, 0), EPS);
    }
}
