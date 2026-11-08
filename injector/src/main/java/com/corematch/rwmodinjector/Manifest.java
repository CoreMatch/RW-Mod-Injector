package com.corematch.rwmodinjector;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 房间模组清单 (契约 Cascade-Backend-v1 v1.3 RoomModManifest).
 *
 * 信任模型 (设计 §3): sha256 是唯一信任依据; sig/sigAlg 为预留签名位,
 * v1.3 恒 null, 读取但忽略 —— 未来接入签名只动校验接缝, 不动装配流程.
 */
public final class Manifest {

    public static final class Entry {
        public final String name;
        public final String file;
        public final String sha256;
        public final long size;

        Entry(String name, String file, String sha256, long size) {
            this.name = name;
            this.file = file;
            this.sha256 = sha256;
            this.size = size;
        }
    }

    public final List<Entry> mods;
    public final String sig;
    public final String sigAlg;

    private Manifest(List<Entry> mods, String sig, String sigAlg) {
        this.mods = mods;
        this.sig = sig;
        this.sigAlg = sigAlg;
    }

    public static Manifest fetch(Config config, String roomCode, int timeoutMs)
            throws IOException {
        String url = config.controlUrl + "/rooms/" + urlEncode(roomCode) + "/mods";
        String body = Http.getString(url, timeoutMs);
        return parse(body);
    }

    public static Manifest parse(String body) {
        Map<String, Object> root = Json.asObject(Json.parse(body));
        List<Object> arr = Json.asArray(root.get("mods"));
        List<Entry> mods = new ArrayList<Entry>();
        for (Object o : arr) {
            Map<String, Object> e = Json.asObject(o);
            mods.add(new Entry(
                    Json.asString(e.get("name")),
                    Json.asString(e.get("file")),
                    Json.asString(e.get("sha256")).toLowerCase(),
                    Json.asLong(e.get("size"))));
        }
        String sig = root.get("sig") == null ? null : Json.asString(root.get("sig"));
        String sigAlg = root.get("sigAlg") == null ? null : Json.asString(root.get("sigAlg"));
        return new Manifest(mods, sig, sigAlg);
    }

    public Entry byFile(String file) {
        for (Entry e : mods) {
            if (e.file.equals(file)) {
                return e;
            }
        }
        return null;
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
