package com.huangguo.mobile;

import android.content.Context;

import java.io.InputStream;
import java.util.Locale;

/**
 * IPv4 → 国家。离线查表，不联网。
 *
 * ── 为什么要内置一张表 ──
 * 节点列表上只写 `64.64.224.121:443`，用户没法判断这条是哪儿的线路。做成
 * 在线查（ip-api 之类）有三个问题：节点本来就在墙外，查国家的请求自己也可能
 * 被墙；免费接口有频率限制，一轮测速十几条就要打一串请求；而且把用户的节点
 * 列表交给第三方也没必要。
 *
 * 表来自 DB-IP 的免费库（dbip-country-lite，CC BY 4.0，每月一份），只取 IPv4、
 * 去掉 ZZ（保留段），按起始地址排序后做增量编码：
 *
 *   'HGC3' | uint32 记录数 | uint16 国家数 | 国家表（每条 2 个 ASCII 字符）
 *   每条记录：varint(与上一条起始地址的差) varint(长度-1？不，是长度) uint8(国家下标)
 *
 * 362108 条，1.8MB，gzip 之后 680KB（APK 里是 deflate 的）。查找是二分，
 * 单次几十微秒，不需要缓存。
 *
 * ── 精度说明 ──
 * 免费库是「按注册地」的国家级归属，不是地理定位。机房 IP 尤其容易落在
 * 公司注册地（比如搬瓦工的美国机器偶尔标成 DE）。显示它只是为了让人一眼认出
 * 「这条大概在哪」，别当法律意义上的归属。
 */
final class GeoIp {

    private static final String ASSET = "ip-country.bin";

    private static int[] starts;
    private static int[] ends;
    private static byte[] ccIndex;
    private static String ccTable = "";
    private static int ccCount;
    private static boolean loaded;
    private static boolean failed;

    /** 上次查过的答案。测速一轮里同一台机器可能被查好几次。 */
    // ConcurrentHashMap：测速线程写、UI 线程读，裸 HashMap 并发下会丢条目甚至死循环。
    private static final java.util.concurrent.ConcurrentHashMap<String, String> cache =
            new java.util.concurrent.ConcurrentHashMap<String, String>();

    private GeoIp() {}

    private static synchronized void ensure(Context context) {
        if (loaded || failed) return;
        try {
            InputStream in = context.getAssets().open(ASSET);
            byte[] raw = readAll(in);
            in.close();
            if (!loadBytes(raw)) failed = true;
        } catch (Throwable t) {
            failed = true;
            UserSource.log("GeoIP 表加载失败：" + t);
        }
    }

    /** 解析一份表。单测直接喂字节，不依赖 Context。 */
    static synchronized boolean loadBytes(byte[] raw) {
        if (loaded || failed) return loaded;
        try {
            if (raw.length < 10 || raw[0] != 'H' || raw[1] != 'G' || raw[2] != 'C' || raw[3] != '3') {
                return false;
            }
            int count = readInt(raw, 4);
            ccCount = readShort(raw, 8);
            int off = 10;
            byte[] ccs = new byte[ccCount * 2];
            System.arraycopy(raw, off, ccs, 0, ccs.length);
            ccTable = new String(ccs, java.nio.charset.Charset.forName("US-ASCII"));
            off += ccs.length;

            int[] s = new int[count];
            int[] e = new int[count];
            byte[] idx = new byte[count];
            long prev = 0;
            for (int i = 0; i < count; i++) {
                long delta = 0;
                int shift = 0;
                while (true) {
                    int b = raw[off++] & 0xFF;
                    delta |= ((long) (b & 0x7F)) << shift;
                    shift += 7;
                    if ((b & 0x80) == 0) break;
                }
                long start = prev + delta;
                prev = start;
                long len = 0;
                shift = 0;
                while (true) {
                    int b = raw[off++] & 0xFF;
                    len |= ((long) (b & 0x7F)) << shift;
                    shift += 7;
                    if ((b & 0x80) == 0) break;
                }
                s[i] = (int) start;
                e[i] = (int) (start + len);
                idx[i] = raw[off++];
            }
            starts = s;
            ends = e;
            ccIndex = idx;
            loaded = true;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 「🇺🇸 美国」这种带国旗的标签。查不到就返回空串 —— 调用方据此决定要不要加前缀，
     * 绝不显示「未知」，那行字在列表里只会占地方。
     */
    static String label(Context context, String host) {
        String cc = code(context, host);
        if (cc == null || cc.length() != 2) return "";
        return flagAndName(cc);
    }

    /** 「🇺🇸 美国」。国家名交给系统 Locale 拿中文名，不自带一张 245 条的映射表。 */
    private static String flagAndName(String cc) {
        String name = "";
        try {
            name = new Locale("", cc).getDisplayCountry(Locale.SIMPLIFIED_CHINESE);
        } catch (Throwable ignored) {}
        if (name == null || name.length() == 0 || name.equals(cc)) name = cc;
        // 国旗是「区域指示符」两个码点拼出来的，系统 emoji 字体会渲染成旗子。
        StringBuilder flag = new StringBuilder();
        for (int i = 0; i < 2; i++) {
            char c = Character.toUpperCase(cc.charAt(i));
            if (c < 'A' || c > 'Z') return name;
            flag.appendCodePoint(0x1F1E6 + (c - 'A'));
        }
        return flag + " " + name;
    }

    /** 单测用：某条 IP 落到的段（start/end/cc），查不到给 "none"。 */
    static String debugSegment(String ipText) {
        if (!loaded) return "not-loaded";
        int ip = toInt(ipText);
        int lo = 0, hi = starts.length - 1, best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (Integer.compareUnsigned(starts[mid], ip) <= 0) { best = mid; lo = mid + 1; } else { hi = mid - 1; }
        }
        if (best < 0) return "no-best";
        return "ip=" + ip + " best=" + best + " start=" + starts[best] + " end=" + ends[best]
                + " in=" + (Integer.compareUnsigned(ip, ends[best]) <= 0);
    }

    /** 单测用：表里有多少条、多少个国家。 */
    static String debugCount() {
        return (starts == null ? "null" : starts.length) + "/" + ccCount + "/" + ccTable.length();
    }

    /** 两字母国家码，直接吃 IP 字面量（单测用，不走 DNS 也不缓存）。 */
    static String rawCode(String host) {
        if (!loaded) return null;
        int ip = toInt(host == null ? "" : host);
        if (ip == 0) return null;
        return search(ip);
    }

    /** 带国旗的标签，直接吃 IP 字面量（单测用）。 */
    static String rawLabel(String host) {
        String cc = rawCode(host);
        if (cc == null) return "";
        return flagAndName(cc);
    }

    /** 两字母国家码；查不到返回 null。 */
    static String code(Context context, String host) {
        if (host == null || host.length() == 0) return null;
        String cached = cache.get(host);
        if (cached != null) return cached.length() == 0 ? null : cached;
        ensure(context);
        if (!loaded) return null;
        int ip = toInt(host);
        if (ip == 0) {
            String cc = null;   // 域名：解析一次再查，解不出来就当没有
            try {
                java.net.InetAddress addr = java.net.InetAddress.getByName(host);
                byte[] raw = addr.getAddress();
                if (raw != null && raw.length == 4) {
                    ip = ((raw[0] & 0xFF) << 24) | ((raw[1] & 0xFF) << 16)
                            | ((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF);
                }
            } catch (Throwable ignored) {}
            if (ip == 0) { cache.put(host, ""); return null; }
            cc = search(ip);
            cache.put(host, cc == null ? "" : cc);
            return cc;
        }
        String cc = search(ip);
        cache.put(host, cc == null ? "" : cc);
        return cc;
    }

    /**
     * 二分：找最后一个 start <= ip 的段，再核对 ip 有没有超出它的尾巴。
     *
     * 比较必须按**无符号**来。IPv4 地址是 32 位，200.x 以上那一半在 int 里是负数；
     * 第一版直接用 `starts[mid] <= ip`，于是 8.8.8.8 这种小地址会一路二分到数组末尾
     * 那个负数段上，查出来是「查不到」。整张表按无符号严格递增，所以
     * compareUnsigned 既保序又不用把数组换成 long[]（省 2.9MB 堆）。
     */
    private static String search(int ip) {
        int lo = 0, hi = starts.length - 1, best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (Integer.compareUnsigned(starts[mid], ip) <= 0) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (best < 0 || Integer.compareUnsigned(ip, ends[best]) > 0) return null;
        int k = ccIndex[best] & 0xFF;
        if (k >= ccCount) return null;
        return ccTable.substring(k * 2, k * 2 + 2);
    }

    /** 点分十进制 → int。不是合法 IPv4 就返回 0（0.0.0.0 本来也不在表里）。 */
    private static int toInt(String host) {
        int len = host.length();
        if (len < 7 || len > 15) return 0;
        int value = 0;
        int parts = 0;
        int current = -1;
        for (int i = 0; i < len; i++) {
            char c = host.charAt(i);
            if (c == '.') {
                if (current < 0 || parts >= 3) return 0;
                value = (value << 8) | current;
                current = -1;
                parts++;
            } else if (c >= '0' && c <= '9') {
                current = (current < 0 ? 0 : current * 10) + (c - '0');
                if (current > 255) return 0;
            } else {
                return 0;
            }
        }
        if (current < 0 || parts != 3) return 0;
        return (value << 8) | current;
    }

    /** 小端（生成脚本用的是 struct.pack('<IH')）。 */
    private static int readInt(byte[] raw, int off) {
        return (raw[off] & 0xFF) | ((raw[off + 1] & 0xFF) << 8)
                | ((raw[off + 2] & 0xFF) << 16) | ((raw[off + 3] & 0xFF) << 24);
    }

    private static int readShort(byte[] raw, int off) {
        return (raw[off] & 0xFF) | ((raw[off + 1] & 0xFF) << 8);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(2 * 1024 * 1024);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
