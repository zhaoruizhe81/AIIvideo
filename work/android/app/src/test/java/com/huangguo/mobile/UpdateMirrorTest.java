package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * 更新清单的候选挑选。
 *
 * 这一层出错的后果很隐蔽：不报错、不崩，只是让一部分用户永远收不到更新。
 * 2026-10-18 实测就是这么撞上的 —— 发完 1.0.24，gh.dpik.top 那条还在吐
 * 1.0.22（服务端缓存，加时间戳查询串都绕不过），另外两条已是新版。按顺序
 * 「第一个成功就用」的话，走这条镜像的用户会一直卡在旧版。
 */
public class UpdateMirrorTest {

    private static String manifest(long versionCode, String versionName) {
        return "{\"versionName\":\"" + versionName + "\",\"versionCode\":" + versionCode + "}";
    }

    @Test
    public void 选出版本号最大的那份() {
        List<String> bodies = new ArrayList<String>();
        bodies.add(manifest(10022, "1.0.22"));
        bodies.add(manifest(10024, "1.0.24"));
        bodies.add(manifest(10023, "1.0.23"));

        String best = UpdateMirror.newestOf(bodies);
        assertTrue("应该选中 1.0.24，实际：" + best, best.contains("1.0.24"));
    }

    @Test
    public void 旧的排在最前面也不影响() {
        // 就是真机上的那个顺序：镜像 A 缓存着旧版，排在候选第一位。
        List<String> bodies = new ArrayList<String>();
        bodies.add(manifest(10022, "1.0.22"));
        bodies.add(manifest(10024, "1.0.24"));

        assertTrue(UpdateMirror.newestOf(bodies).contains("1.0.24"));
    }

    @Test
    public void 全是旧版时返回它自己() {
        List<String> bodies = new ArrayList<String>();
        bodies.add(manifest(10022, "1.0.22"));

        assertTrue(UpdateMirror.newestOf(bodies).contains("1.0.22"));
    }

    @Test
    public void 坏数据被跳过不影响好数据() {
        List<String> bodies = new ArrayList<String>();
        bodies.add("HTTP 502 Bad Gateway");
        bodies.add("<html>请先登录 Wi-Fi</html>");
        bodies.add("");
        bodies.add(manifest(10024, "1.0.24"));

        assertTrue(UpdateMirror.newestOf(bodies).contains("1.0.24"));
    }

    @Test
    public void 只有坏数据返回null() {
        List<String> bodies = new ArrayList<String>();
        bodies.add("not json");
        bodies.add("{ broken");

        assertNull(UpdateMirror.newestOf(bodies));
    }

    @Test
    public void 空列表返回null() {
        assertNull(UpdateMirror.newestOf(new ArrayList<String>()));
    }

    @Test
    public void 没有versionCode的清单当作0_不会压过正常清单() {
        List<String> bodies = new ArrayList<String>();
        bodies.add("{\"versionName\":\"9.9.9\"}");   // 没 versionCode
        bodies.add(manifest(10024, "1.0.24"));

        assertTrue(UpdateMirror.newestOf(bodies).contains("1.0.24"));
    }

    /**
     * 有的镜像在 200 状态下吐 JSON 错误页（限流、上游故障），versionCode 缺失算 0。
     * 让它当选的话，check() 会判「0 ≤ 已装版本」直接报「已是最新」，还会把本地
     * 待更新的清单 clearCandidate() 抹掉，且 12 小时内不再真正查新。
     */
    @Test
    public void versionCode缺失或非正的body不参选() {
        List<String> bodies = new ArrayList<String>();
        bodies.add("{\"error\":\"rate limited\"}");          // JSON 错误页，没 versionCode
        bodies.add("{\"versionCode\":0}");                   // 显式 0
        bodies.add("{\"versionCode\":-5}");                  // 负数

        assertNull("错误页 JSON 不能当选，实际：", UpdateMirror.newestOf(bodies));
    }

    @Test
    public void 错误页JSON混在正常清单里也不影响() {
        List<String> bodies = new ArrayList<String>();
        bodies.add("{\"error\":\"rate limited\"}");
        bodies.add(manifest(10022, "1.0.22"));
        bodies.add(manifest(10024, "1.0.24"));

        assertTrue(UpdateMirror.newestOf(bodies).contains("1.0.24"));
    }
}
