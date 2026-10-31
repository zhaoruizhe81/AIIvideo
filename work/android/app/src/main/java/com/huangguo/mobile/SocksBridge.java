package com.huangguo.mobile;

import android.util.Log;

import java.io.IOException;

/**
 * 只支持 CONNECT 的本地 HTTP → SOCKS5 桥。
 *
 * <p>存在的唯一原因是 WebView 的 {@code ProxyController} 用 socks5 时不可靠
 * （见 applyProxy 里的注释）。WebView 走 HTTPS 只会发 {@code CONNECT host:port}，
 * 所以这里只管这一种请求：握手、回 200、然后双向管道。
 *
 * <p>不做认证、不监听外网（绑 127.0.0.1）、不缓存 —— 它的生命周期跟进程一样，
 * 端口随机。SOCKS 那头是 Xray 的本地入站，同样只绑回环。
 */
final class SocksBridge {
    private static volatile int port = 0;
    private static volatile String socksHost = "127.0.0.1";
    private static volatile int socksPort = 0;
    private static java.net.ServerSocket server;

    static synchronized int ensure(String host, int targetPort) throws IOException {
        socksHost = host;
        socksPort = targetPort;
        if (port > 0) return port;
        // 线程全程只认自己这个 server 实例：finally 清理时若 ensure() 已经重建过
        // 新桥，绝不能顺手把新桥关掉、把新端口抹成 0。
        final java.net.ServerSocket myServer =
                new java.net.ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"));
        server = myServer;
        port = myServer.getLocalPort();
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                int failures = 0;
                try {
                    while (true) {
                        try {
                            final java.net.Socket client = myServer.accept();
                            failures = 0;
                            Thread worker = new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    try { serve(client); } catch (Throwable ignored) { closeQuietly(client); }
                                }
                            }, "socks-bridge-conn");
                            worker.setDaemon(true);
                            worker.start();
                        } catch (Throwable e) {
                            // 瞬时异常（EMFILE 之类）不能把线程杀掉：port 仍 >0，
                            // ensure() 会一直返回这个死端口。只有 server 真被关了才退；
                            // 其余记日志继续，连挂 8 次再退，防异常风暴空转烧 CPU。
                            if (myServer.isClosed()) return;
                            failures++;
                            log("accept 失败(" + failures + ")：" + e);
                            if (failures >= 8) return;
                            try { Thread.sleep(50); } catch (InterruptedException ie) { return; }
                        }
                    }
                } finally {
                    synchronized (SocksBridge.class) {
                        // accept 线程退出（server 被关 / 连挂 8 次）就必须关 socket 并把
                        // port 复位：不清零的话 ensure() 会一直把这个没人 accept 的死端口
                        // 发出去，WebView 的外部 SOCKS 路径静默全断，只有重启进程才能恢复。
                        // 静态字段还指着自己才清 —— 若已经重建过新桥，新桥的端口不能动。
                        if (server == myServer) {
                            closeQuietly(myServer);
                            port = 0;
                            log("桥 accept 线程已退出，端口复位，下次 ensure 会重建");
                        }
                    }
                }
            }
        }, "socks-bridge");
        thread.setDaemon(true);
        thread.start();
        log("WebView 走本地 HTTP→SOCKS 桥 127.0.0.1:" + port + " → " + host + ":" + targetPort);
        return port;
    }

    private static void serve(java.net.Socket client) throws IOException {
        log("桥收到连接 " + client.getRemoteSocketAddress());
        java.io.InputStream in = client.getInputStream();
        java.io.OutputStream out = client.getOutputStream();
        String head = readHead(in);
        if (head == null) { closeQuietly(client); return; }
        String[] lines = head.split("\r\n");
        String[] parts = lines.length > 0 ? lines[0].split(" ") : new String[0];
        if (parts.length < 2 || !"CONNECT".equalsIgnoreCase(parts[0])) {
            out.write("HTTP/1.1 405 Only CONNECT\r\n\r\n".getBytes("UTF-8"));
            out.flush();
            closeQuietly(client);
            return;
        }
        String target = parts[1];
        int colon = target.lastIndexOf(':');
        if (colon <= 0) { closeQuietly(client); return; }
        String host = target.substring(0, colon);
        int destPort = Integer.parseInt(target.substring(colon + 1));
        log("桥 CONNECT " + host + ":" + destPort + " → socks " + socksHost + ":" + socksPort);

        java.net.Socket upstream = new java.net.Socket();
        try {
            upstream.connect(new java.net.InetSocketAddress(socksHost, socksPort), 8000);
            upstream.setSoTimeout(15000);
            java.io.InputStream upIn = upstream.getInputStream();
            java.io.OutputStream upOut = upstream.getOutputStream();
            // SOCKS5：无认证 + CONNECT + 域名交给落地解析
            upOut.write(new byte[]{5, 1, 0});
            upOut.flush();
            byte[] authReply = new byte[2];
            readFully(upIn, authReply);
            log("桥 socks 认证回复 " + authReply[0] + "/" + authReply[1]);
            byte[] hostBytes = host.getBytes("UTF-8");
            byte[] req = new byte[7 + hostBytes.length];
            req[0] = 5; req[1] = 1; req[2] = 0; req[3] = 3;
            req[4] = (byte) hostBytes.length;
            System.arraycopy(hostBytes, 0, req, 5, hostBytes.length);
            req[5 + hostBytes.length] = (byte) ((destPort >> 8) & 0xff);
            req[6 + hostBytes.length] = (byte) (destPort & 0xff);
            upOut.write(req);
            upOut.flush();
            // SOCKS5 的回复长度**取决于 ATYP**：IPv4 是 4+4+2，域名是
            // 4+1+len+2，IPv6 是 4+16+2。原来这里死读 10 字节 —— Xray 回 IPv4
            // 时刚好对上，回域名类型时就会把后面数据流的开头吞掉，表现是
            // 页面永远加载不出来（net::ERR_TIMED_OUT，innerText=0）。
            byte[] conHead = new byte[4];
            readFully(upIn, conHead);
            if (conHead[1] != 0) { closeQuietly(client); closeQuietly(upstream); return; }
            int atyp = conHead[3];
            log("桥 socks 连接回复 " + conHead[0] + "/" + conHead[1] + " atyp=" + atyp);
            if (atyp == 1) {
                readFully(upIn, new byte[6]);                    // IPv4 + 端口
            } else if (atyp == 3) {
                byte[] len = new byte[1];
                readFully(upIn, len);
                readFully(upIn, new byte[(len[0] & 0xff) + 2]);  // 域名 + 端口
            } else if (atyp == 4) {
                readFully(upIn, new byte[18]);                   // IPv6 + 端口
            } else {
                closeQuietly(client); closeQuietly(upstream); return;
            }

            out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes("UTF-8"));
            out.flush();
            upstream.setSoTimeout(0);
            pump(in, upOut);
            pump(upIn, out);
        } catch (Throwable e) {
            log("桥转发失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
            closeQuietly(upstream);
            closeQuietly(client);
        }
    }

    private static String readHead(java.io.InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        int state = 0;
        int b;
        while ((b = in.read()) != -1) {
            buf.write(b);
            if (b == '\r') state = 1;
            else if (b == '\n' && state == 1) state = 2;
            else if (b == '\r' && state == 2) state = 3;
            else if (b == '\n' && state == 3) break;
            else state = 0;
            if (buf.size() > 8192) break;
        }
        return buf.size() == 0 ? null : buf.toString("UTF-8");
    }

    private static void readFully(java.io.InputStream in, byte[] buf) throws IOException {
        int read = 0;
        while (read < buf.length) {
            int n = in.read(buf, read, buf.length - read);
            if (n < 0) throw new IOException("socks 握手被断开");
            read += n;
        }
    }

    private static void pump(final java.io.InputStream in, final java.io.OutputStream out) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[8192];
                try {
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        out.write(buf, 0, n);
                        out.flush();
                    }
                } catch (Throwable ignored) {
                } finally {
                    try { out.close(); } catch (Throwable ignored) {}
                }
            }
        }, "socks-bridge-pump");
        thread.setDaemon(true);
        thread.start();
    }

    private static void closeQuietly(java.io.Closeable c) {
        try { if (c != null) c.close(); } catch (Throwable ignored) {}
    }

    private static void log(String message) {
        try {
            Log.d("AIIvideo", "[socks-bridge] " + message);
        } catch (Throwable ignored) {
        }
    }
}
