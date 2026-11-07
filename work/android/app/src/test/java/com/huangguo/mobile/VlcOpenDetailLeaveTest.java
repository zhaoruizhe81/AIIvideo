package com.huangguo.mobile;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 迭代2「点头像进详情」让出前台收尾的守卫回归。
 *
 * <p>openFeedDetail 反射调前端 hgFeedOpenDetail 后要 finish 自己露出 WebView 详情页。
 * 若 Activity 已在结束/已销毁，就不该再收尾（防重复 finish、防已死时再 touch player）。
 * 这条纯逻辑抽成 {@link VlcPlayerActivity#canLeaveToWebDetail} 钉住。
 */
public class VlcOpenDetailLeaveTest {

    @Test public void 收尾_活着才让出前台() {
        assertTrue(VlcPlayerActivity.canLeaveToWebDetail(false, false));
    }

    @Test public void 收尾_正在结束或已销毁都不做() {
        assertFalse(VlcPlayerActivity.canLeaveToWebDetail(true, false));   // finishing
        assertFalse(VlcPlayerActivity.canLeaveToWebDetail(false, true));   // destroyed
        assertFalse(VlcPlayerActivity.canLeaveToWebDetail(true, true));
    }
}
