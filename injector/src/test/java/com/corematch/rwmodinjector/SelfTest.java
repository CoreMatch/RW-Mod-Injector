package com.corematch.rwmodinjector;

import java.util.List;
import java.util.Map;

/**
 * CI 自测试 (零依赖, 纯逻辑面): Json 解析 / 清单解析 / 失败文案分类.
 *
 * 不触碰引擎桩 —— 这些路径在真实客户端里由 E2E 演练覆盖 (设计 §8/§11),
 * 这里只锁纯函数行为, 防止回归. 任一断言失败即非零退出.
 */
public final class SelfTest {

    private static int failures;

    public static void main(String[] args) {
        jsonBasics();
        manifestParse();
        failureClassification();
        if (failures > 0) {
            System.err.println(failures + " assertion(s) failed");
            System.exit(1);
        }
        System.out.println("SelfTest: all assertions passed");
    }

    private static void check(boolean cond, String what) {
        if (!cond) {
            failures++;
            System.err.println("FAIL: " + what);
        }
    }

    private static void jsonBasics() {
        Object root = Json.parse("{\"a\":\"x\",\"n\":42,\"arr\":[1,\"two\"],\"nil\":null}");
        Map<String, Object> obj = Json.asObject(root);
        check("x".equals(Json.asString(obj.get("a"))), "Json string field");
        check(Json.asLong(obj.get("n")) == 42L, "Json number field");
        List<Object> arr = Json.asArray(obj.get("arr"));
        check(arr.size() == 2 && "two".equals(Json.asString(arr.get(1))), "Json array");
        check(obj.get("nil") == null, "Json null");
        // 转义与中文
        Map<String, Object> esc = Json.asObject(Json.parse("{\"s\":\"a\\\"b\\u4e2d\"}"));
        check("a\"b\u4e2d".equals(Json.asString(esc.get("s"))), "Json escapes");
    }

    private static void manifestParse() {
        String body = "{\"mods\":[{\"name\":\"Stone Heads 1.2\",\"file\":\"2296940532.zip\","
                + "\"sha256\":\"8558EE12EA25D201BBFE9F12CB7D90E346FD6B5E57DC8E5861D9508A89C3CCED\","
                + "\"size\":3135490}],\"sig\":null,\"sigAlg\":null}";
        Manifest m = Manifest.parse(body);
        check(m.mods.size() == 1, "manifest entry count");
        Manifest.Entry e = m.mods.get(0);
        check("Stone Heads 1.2".equals(e.name), "manifest name");
        check("2296940532.zip".equals(e.file), "manifest file");
        // 哈希一律小写化 (信任模型: 内容哈希是唯一判据)
        check("8558ee12ea25d201bbfe9f12cb7d90e346fd6b5e57dc8e5861d9508a89c3cced".equals(e.sha256),
                "manifest sha256 lowercased");
        check(e.size == 3135490L, "manifest size");
        check(m.sig == null && m.sigAlg == null, "manifest sig reserved-null");
        check(m.byFile("2296940532.zip") == e && m.byFile("nope.zip") == null, "manifest byFile");
    }

    private static void failureClassification() {
        // 每类失败: 文案必须给出结论与下一步; 关键短语逐一锁定
        assertFriendly("cannot fetch mod manifest: HTTP 404 from http://x/v1/rooms/E2E1/mods",
                "Couldn't fetch the mod list", "try /inj join again");
        assertFriendly("hash mismatch for a.zip: got 1, want 2",
                "Security check failed", "tell the server operator");
        assertFriendly("size mismatch for a.zip: got 1, want 2",
                "arrived incomplete", "try /inj join again");
        assertFriendly("mods directory is not writable: /x/mods/units",
                "Can't write to the mod folder", "try /inj join again");
        assertFriendly("manifest entry a.zip exceeds the size limit",
                "too large to install", "Tell the server operator");
        // zip 损坏按异常类型判定: 用真实 ZipException (extractZip 的原样传播形态)
        assertFriendlyT(new java.util.zip.ZipException("error in opening zip file"),
                "corrupt and was NOT installed", "tell the server operator");
        assertFriendly("zip archive contains no entries: a.zip",
                "corrupt and was NOT installed", "tell the server operator");
        assertFriendly("unsafe path in zip: ../evil",
                "corrupt and was NOT installed", "tell the server operator");
        // 下载失败报错自带 .zip 文件名, 绝不可误归"zip 损坏" (分类顺序回归)
        assertFriendly("HTTP 404 from http://x/v1/rooms/E2E1/mods/a.zip",
                "Downloading a mod file failed", "try /inj join again");
        assertFriendly("not connected to a lobby; connect to the lobby first",
                "Connect to the lobby first", "/inj join");
        assertFriendly("unit reload failed: boom (mod set restored; if the game misbehaves, restart it)",
                "previous mod set was restored", "restart it");
        assertFriendly("cannot reconnect to the previous server; mods are applied - reconnect manually",
                "reconnecting to the lobby failed", "reconnect manually");
        assertFriendly("game engine is not ready",
                "not ready yet", "try /inj join again");
        // 兜底
        assertFriendly("something nobody anticipated",
                "failed unexpectedly", "check the game log");
        // 空消息不崩
        assertFriendly(null, "failed unexpectedly", "check the game log");
    }

    private static void assertFriendly(String cause, String... mustContain) {
        assertFriendlyT(cause == null ? new IllegalStateException()
                : new IllegalStateException(cause), mustContain);
    }

    private static void assertFriendlyT(Throwable t, String... mustContain) {
        String msg = Assembler.friendlyFailure(t);
        for (String want : mustContain) {
            check(msg != null && msg.contains(want),
                    "friendlyFailure(\"" + t + "\") should contain \"" + want + "\", got: " + msg);
        }
    }

    private SelfTest() {
    }
}
