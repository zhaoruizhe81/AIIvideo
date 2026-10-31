package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 「贴一个地址就装一个源」那条链路的落盘部分。
 *
 * <p>为什么值得单测：这条链路每一步错了都<b>不会崩</b>。id 不合法 → 列表里出现
 * 一个永远调不动的孤儿；写文件不是原子操作 → 正好在装的时候被调用会读到半截 js，
 * 报的错是语法错误，跟真正的原因（正在装）毫无关系；没校验 requires → 装进去
 * 一个当前壳跑不了的源，用户点下去只看到空白。
 */
public class SpiderInstallTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static byte[] src(String id, String extra) {
        return ("__spiderSite = { id: '" + id + "', name: '测试源', version: 1, requires: '>=1',"
                + " hosts: [], async home(ctx) { return { items: [] }; } };"
                + (extra == null ? "" : extra)).getBytes(StandardCharsets.UTF_8);
    }

    /** 正常情况：落盘、文件名叫 id.js、返回的元信息能直接用。 */
    @Test public void 装上之后文件就在目录里() throws Exception {
        File dir = tmp.newFolder("spiders");
        JSONObject meta = SpiderStore.installTo(dir, "https://example.com/x.js", src("demo", null));
        assertEquals("demo", meta.optString("id"));
        assertEquals("user", meta.optString("source"));
        assertTrue(new File(dir, "demo.js").isFile());
    }

    /** 装两次是覆盖，不是并排两个文件（否则列表里会出现两个同名源）。 */
    @Test public void 重复安装是覆盖() throws Exception {
        File dir = tmp.newFolder("spiders");
        SpiderStore.installTo(dir, "https://example.com/x.js", src("demo", "// v1"));
        SpiderStore.installTo(dir, "https://example.com/x.js", src("demo", "// v2"));
        assertEquals(1, dir.listFiles((d, n) -> n.endsWith(".js")).length);
        String text = new String(Files.readAllBytes(new File(dir, "demo.js").toPath()), StandardCharsets.UTF_8);
        assertTrue("应该是第二份的内容", text.contains("// v2"));
    }

    /** id 不合法直接拒：它要当文件名用，`../x` 这种能把文件写到目录外面去。 */
    @Test public void 非法id被拒() throws Exception {
        File dir = tmp.newFolder("spiders");
        for (String bad : new String[]{"../evil", "大写", "a b", ""}) {
            try {
                SpiderStore.installTo(dir, "https://example.com/x.js", src(bad, null));
                throw new AssertionError("应该被拒：" + bad);
            } catch (Exception expected) {
                // ok
            }
        }
        assertEquals("一个文件都不该写进去", 0, dir.listFiles().length);
    }

    /** 元信息读不出来（没有 __spiderSite）→ 拒，不留垃圾。 */
    @Test public void 没有元信息的文件被拒() throws Exception {
        File dir = tmp.newFolder("spiders");
        try {
            SpiderStore.installTo(dir, "https://example.com/x.js",
                    "console.log('hi')".getBytes(StandardCharsets.UTF_8));
            throw new AssertionError("应该被拒");
        } catch (Exception expected) {
            assertEquals(0, dir.listFiles().length);
        }
    }

    /** 空的、超大的都拒。上限 2MB 是防「装一个把内存吃光的文件」。 */
    @Test public void 空与超大被拒() throws Exception {
        File dir = tmp.newFolder("spiders");
        for (byte[] raw : new byte[][]{new byte[0], new byte[3 * 1024 * 1024]}) {
            try {
                SpiderStore.installTo(dir, "https://example.com/x.js", raw);
                throw new AssertionError("应该被拒");
            } catch (Exception expected) {
                // ok
            }
        }
    }

    /** 删掉之后文件没了；不存在的 id 返回 false 而不是抛。 */
    @Test public void 删除是可逆的() throws Exception {
        File dir = tmp.newFolder("spiders");
        SpiderStore.installTo(dir, "https://example.com/x.js", src("demo", null));
        assertTrue(new File(dir, "demo.js").isFile());
        assertFalse("删一个不存在的应该老实返回 false", SpiderStore.removeFrom(dir, "nope"));
        assertTrue(SpiderStore.removeFrom(dir, "demo"));
        assertFalse(new File(dir, "demo.js").isFile());
    }

    /**
     * requires 写成数字（读不出字符串字面量）：门禁自己声明「认不出来的一律
     * 拒绝」，当没写放行的话老壳会静默跑上一个要求新 ABI 的源。
     */
    @Test public void requires写成数字被拒() throws Exception {
        File dir = tmp.newFolder("spiders");
        byte[] raw = ("__spiderSite = { id: 'demo', name: '测试源', version: 1, requires: 2,"
                + " hosts: [], async home(ctx) { return { items: [] }; } };")
                .getBytes(StandardCharsets.UTF_8);
        try {
            SpiderStore.installTo(dir, "https://example.com/x.js", raw);
            throw new AssertionError("数字 requires 应该被拒");
        } catch (Exception expected) {
            assertEquals("被拒的源不该落盘", 0, dir.listFiles().length);
        }
    }

    /**
     * 跨进程读回（Binder 上只传「路径 + 大小 + 修改时间」）：指纹对得上才能读；
     * 对不上说明主进程门禁扫描之后文件被换过（重新装/删），那一版没过过门禁，
     * 拒绝执行；路径跑出源目录、文件不存在，同样拒。
     */
    @Test public void 按路径指纹读回源文件() throws Exception {
        File dir = tmp.newFolder("spiders");
        SpiderStore.installTo(dir, "https://example.com/x.js", src("demo", null));
        File f = new File(dir, "demo.js");
        String text = SpiderStore.readFromSourceDir(dir, f.getAbsolutePath(),
                f.length(), f.lastModified());
        assertTrue("读回来的应该是源码本身", text.contains("__spiderSite"));

        try {
            SpiderStore.readFromSourceDir(dir, f.getAbsolutePath(),
                    f.length() + 1, f.lastModified());
            throw new AssertionError("大小对不上应该被拒");
        } catch (Exception expected) {
            // ok
        }
        try {
            File outside = tmp.newFile("evil.js");
            SpiderStore.readFromSourceDir(dir, outside.getAbsolutePath(),
                    outside.length(), outside.lastModified());
            throw new AssertionError("源目录外的路径应该被拒");
        } catch (Exception expected) {
            // ok
        }
        try {
            SpiderStore.readFromSourceDir(dir, new File(dir, "nope.js").getAbsolutePath(), 1, 1);
            throw new AssertionError("不存在的文件应该被拒");
        } catch (Exception expected) {
            // ok
        }
    }
}
