package com.corematch.rwmodinjector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 最小 JSON 解析器 (对象/数组/字符串/数字/布尔/null), 只服务于清单解析.
 * 零第三方依赖; 输入来自控制面清单端点, 解析失败即装配失败回退.
 */
public final class Json {

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) {
            throw new IllegalArgumentException("trailing JSON content at " + p.i);
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object v) {
        if (v instanceof Map) {
            return (Map<String, Object>) v;
        }
        throw new IllegalArgumentException("expected JSON object, got " + typeName(v));
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object v) {
        if (v instanceof List) {
            return (List<Object>) v;
        }
        throw new IllegalArgumentException("expected JSON array, got " + typeName(v));
    }

    public static String asString(Object v) {
        if (v instanceof String) {
            return (String) v;
        }
        throw new IllegalArgumentException("expected JSON string, got " + typeName(v));
    }

    public static long asLong(Object v) {
        if (v instanceof Long) {
            return (Long) v;
        }
        throw new IllegalArgumentException("expected JSON number, got " + typeName(v));
    }

    private static String typeName(Object v) {
        return v == null ? "null" : v.getClass().getSimpleName();
    }

    private Object value() {
        if (i >= s.length()) {
            throw new IllegalArgumentException("unexpected end of JSON");
        }
        char c = s.charAt(i);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        i++; // {
        ws();
        if (peek() == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            String key = string();
            ws();
            if (s.charAt(i) != ':') {
                throw new IllegalArgumentException("expected ':' at " + i);
            }
            i++;
            ws();
            m.put(key, value());
            ws();
            char c = s.charAt(i);
            if (c == ',') {
                i++;
                continue;
            }
            if (c == '}') {
                i++;
                return m;
            }
            throw new IllegalArgumentException("expected ',' or '}' at " + i);
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<Object>();
        i++; // [
        ws();
        if (peek() == ']') {
            i++;
            return list;
        }
        while (true) {
            ws();
            list.add(value());
            ws();
            char c = s.charAt(i);
            if (c == ',') {
                i++;
                continue;
            }
            if (c == ']') {
                i++;
                return list;
            }
            throw new IllegalArgumentException("expected ',' or ']' at " + i);
        }
    }

    private String string() {
        if (s.charAt(i) != '"') {
            throw new IllegalArgumentException("expected string at " + i);
        }
        i++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default:
                        throw new IllegalArgumentException("bad escape at " + i);
                }
            } else {
                sb.append(c);
            }
        }
    }

    private Object number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        String num = s.substring(start, i);
        try {
            if (num.indexOf('.') < 0 && num.indexOf('e') < 0 && num.indexOf('E') < 0) {
                return Long.valueOf(num);
            }
            return (long) Double.parseDouble(num);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad number '" + num + "' at " + start);
        }
    }

    private void expect(String word) {
        if (!s.startsWith(word, i)) {
            throw new IllegalArgumentException("expected '" + word + "' at " + i);
        }
        i += word.length();
    }

    private char peek() {
        return s.charAt(i);
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }
}
