package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * 迭代2-C 圆形封面头像：data URL / base64 载荷截取的纯逻辑回归。
 *
 * <p>前端 __hgFeedCoverData(id) 同步返回 'data:image/jpeg;base64,XXXX' 或 ''（未就绪）。
 * 原生要从中截出 base64 载荷再解码；截错就解不出图（头像一直是纯色圆）或抛异常。
 * 这里钉住截取规则（含裸 base64 兼容、空/无逗号兜底）。
 */
public class VlcCoverDataTest {

    @Test public void 截取_dataURL带前缀取逗号后() {
        assertEquals("AAAA", VlcPlayerActivity.base64PayloadOf("data:image/jpeg;base64,AAAA"));
        assertEquals("BBBB", VlcPlayerActivity.base64PayloadOf("data:image/png;base64,BBBB"));
        // 大小写/空白容错
        assertEquals("CCCC", VlcPlayerActivity.base64PayloadOf("  DATA:image/jpeg;base64,CCCC  "));
    }

    @Test public void 截取_裸base64原样() {
        assertEquals("QUJD", VlcPlayerActivity.base64PayloadOf("QUJD"));
    }

    @Test public void 截取_空与无逗号返回null() {
        assertNull(VlcPlayerActivity.base64PayloadOf(null));
        assertNull(VlcPlayerActivity.base64PayloadOf(""));
        assertNull(VlcPlayerActivity.base64PayloadOf("   "));
        // data: 前缀但没有逗号 → 无效
        assertNull(VlcPlayerActivity.base64PayloadOf("data:image/jpeg;base64"));
    }
}
