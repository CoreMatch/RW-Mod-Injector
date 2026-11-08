package com.corematch.rwmodinjector;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** 最小 HTTP 助手 (JDK HttpURLConnection), 只读 GET. */
public final class Http {

    /** 单次装配下载总量上限 (契约 §9 红线: 大小必须有限) */
    public static final long MAX_TOTAL_DOWNLOAD = 2L * 1024 * 1024 * 1024;

    private Http() {
    }

    public static String getString(String url, int timeoutMs) throws IOException {
        HttpURLConnection conn = open(url, timeoutMs);
        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code + " from " + url);
            }
            InputStream in = conn.getInputStream();
            try {
                byte[] buf = new byte[8192];
                StringBuilder sb = new StringBuilder();
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 8 * 1024 * 1024) {
                        throw new IOException("manifest response too large");
                    }
                    sb.append(new String(buf, 0, n, "UTF-8"));
                }
                return sb.toString();
            } finally {
                in.close();
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 下载 url 到 out 流, 逐块写入; 超过 maxBytes 即失败.
     * 返回实际写入字节数. 调用方负责校验哈希后才可落盘生效.
     */
    public static long download(String url, OutputStream out, long maxBytes, int timeoutMs)
            throws IOException {
        HttpURLConnection conn = open(url, timeoutMs);
        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code + " from " + url);
            }
            InputStream in = conn.getInputStream();
            try {
                byte[] buf = new byte[64 * 1024];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > maxBytes) {
                        throw new IOException("download exceeds size limit");
                    }
                    out.write(buf, 0, n);
                }
                return total;
            } finally {
                in.close();
            }
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection open(String url, int timeoutMs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(false);
        return conn;
    }
}
