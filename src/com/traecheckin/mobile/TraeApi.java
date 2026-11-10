package com.traecheckin.mobile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Scanner;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;

/**
 * Trae 云端 API 客户端（对齐官方 SOLO 客户端请求契约）。
 *
 * BaseUrl = https://api.trae.cn
 *   POST /trae/api/v2/ug/checkin_credits/status        查询今日签到状态与单日奖励
 *   POST /trae/api/v2/ug/checkin_credits/claim         领取每日签到积分
 *   POST /trae/api/v2/pay/ide_user_ent_usage           查询服务端账本余额
 *
 * OAuthBase = https://api.trae.com.cn
 *   POST /cloudide/api/v3/trae/oauth/ExchangeToken     用 refreshToken 换 accessToken
 *
 * 认证头：Authorization: Cloud-IDE-JWT <token>，X-Device-Id: <12~20 位纯数字设备号>。
 * 签到请求体必须是 {"req_source":2}；旧版网页版谱系的 req_source=1 会被 9074 拒绝。
 */
public class TraeApi {

    public static final String BASE = "https://api.trae.cn";
    public static final String OAUTH_BASE = "https://api.trae.com.cn";
    public static final String CLIENT_ID = "en1oxy7wnw8j9n";
    public static final String IDE_VERSION = "0.1.43";

    private static final String UA = "Trae/" + IDE_VERSION;
    private static final String WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String CHECKIN_BODY = "{\"req_source\":2}";
    private static final String ENT_PATH = "/trae/api/v2/pay/ide_user_ent_usage";
    private static final String ENT_PATH_OLD = "/trae/api/v2/pay/user_current_entitlement_list";

    /** 返回 JSONObject；__http 为 HTTP 状态码；__error 为网络层错误。 */
    static JSONObject request(String path, String token, String deviceId, String bodyJson) {
        return requestUrl(BASE + path, token, deviceId, bodyJson);
    }

    private static JSONObject requestUrl(String url, String token, String deviceId, String bodyJson) {
        HttpsURLConnection c = null;
        try {
            c = (HttpsURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setDoOutput(true);
            // 严格对齐官方客户端 ug_request 的最小头集合（xiaoyinge/trae-signin-gui
            // upstream.rs::ug_request 有单元测试逐项断言）。此前额外补的
            // x-device-brand=Windows / x-os-version=10.0.22631 与授权链接里声明的
            // x_device_brand=PC / x_os_version=1.0 互相矛盾，会被上游当作伪造的
            // 设备指纹，claim 稳定返回 9074（status 只读不受校验）。多发的头一律去掉。
            c.setRequestProperty("Authorization", "Cloud-IDE-JWT " + token);
            c.setRequestProperty("X-Device-Id", deviceId == null ? "" : deviceId);
            c.setRequestProperty("X-User-Region", "CN");
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", UA);
            byte[] body = (bodyJson == null ? "{}" : bodyJson).getBytes("UTF-8");
            OutputStream os = c.getOutputStream();
            try {
                os.write(body);
                os.flush();
            } finally {
                os.close();
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : readAll(in);
            JSONObject r;
            try {
                r = new JSONObject(text);
            } catch (Exception e) {
                r = new JSONObject();
                if (text.length() > 500) text = text.substring(0, 500);
                r.put("__text", text);
            }
            r.put("__http", code);
            return r;
        } catch (Exception e) {
            JSONObject r = new JSONObject();
            try {
                r.put("__error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            } catch (Exception ignored) {
            }
            return r;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String readAll(InputStream in) {
        Scanner s = new Scanner(in, "UTF-8").useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }

    /** 查询今日签到状态。 */
    public static JSONObject status(String token, String deviceId) {
        return request("/trae/api/v2/ug/checkin_credits/status", token, deviceId, CHECKIN_BODY);
    }

    /** 领取今日签到积分（每天一次，重复调用幂等）。 */
    public static JSONObject claim(String token, String deviceId) {
        return request("/trae/api/v2/ug/checkin_credits/claim", token, deviceId, CHECKIN_BODY);
    }

    /**
     * 查询积分权益包原始数据。优先走服务端账本 ide_user_ent_usage，
     * 上游旧版本没有 usage_summary 时回落到旧接口。
     */
    public static JSONObject entitlements(String token, String deviceId) {
        JSONObject current = request(ENT_PATH, token, deviceId, "{}");
        if (parseEntitlements(current, null) >= 0) return current;
        return request(ENT_PATH_OLD, token, deviceId, "{}");
    }

    /**
     * 用 SOLO OAuth refreshToken 换新 accessToken。
     * 返回 {ok, token, refresh_token, expires_at, message}；不会抛异常。
     */
    public static JSONObject exchangeRefreshToken(String refreshToken) {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", false);
            if (refreshToken == null || refreshToken.length() < 10) {
                out.put("message", "刷新令牌为空");
                return out;
            }
            HttpsURLConnection c = null;
            try {
                c = (HttpsURLConnection) new URL(OAUTH_BASE
                        + "/cloudide/api/v3/trae/oauth/ExchangeToken").openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(15000);
                c.setReadTimeout(15000);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("Accept", "application/json");
                c.setRequestProperty("User-Agent", UA);
                JSONObject body = new JSONObject();
                body.put("ClientID", CLIENT_ID);
                body.put("RefreshToken", refreshToken);
                body.put("ClientSecret", "-");
                body.put("UserID", "");
                byte[] bytes = body.toString().getBytes("UTF-8");
                OutputStream os = c.getOutputStream();
                try {
                    os.write(bytes);
                    os.flush();
                } finally {
                    os.close();
                }
                int http = c.getResponseCode();
                InputStream in = http >= 400 ? c.getErrorStream() : c.getInputStream();
                String text = in == null ? "" : readAll(in);
                if (http < 200 || http >= 300) {
                    out.put("message", "换 token 失败（HTTP " + http + "）");
                    return out;
                }
                JSONObject root;
                try {
                    root = new JSONObject(text);
                } catch (Exception e) {
                    out.put("message", "换 token 响应格式异常");
                    return out;
                }
                String token = findString(root, "Token", "AccessToken", "access_token");
                String nextRefresh = findString(root, "RefreshToken", "refresh_token");
                if (nextRefresh == null || nextRefresh.length() == 0) nextRefresh = refreshToken;
                long expiresAt = findLong(root, "TokenExpireAt", "tokenExpireAt",
                        "ExpiresAt", "expires_at");
                if (expiresAt > 1000000000000L) expiresAt /= 1000L;
                if (expiresAt <= 0L) {
                    long duration = findLong(root, "TokenExpireDuration", "token_expire_duration");
                    expiresAt = System.currentTimeMillis() / 1000L
                            + (duration > 0L ? duration : 1209600L);
                }
                if (token == null || token.length() <= 20) {
                    out.put("message", "服务端未返回有效 token");
                    return out;
                }
                out.put("ok", true);
                out.put("token", token);
                out.put("refresh_token", nextRefresh);
                out.put("expires_at", expiresAt);
            } finally {
                if (c != null) c.disconnect();
            }
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("message", "换 token 失败：" + t.getClass().getSimpleName());
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /**
     * 兼容旧版：用网页 cookie 换短期 JWT（GetUserToken）。
     * 新登录链路使用 SOLO OAuth，这里只作为旧账号的兜底续期通道。
     */
    public static String refreshToken(String cookie) {
        if (cookie == null || cookie.length() == 0) return null;
        HttpsURLConnection c = null;
        try {
            c = (HttpsURLConnection) new URL(BASE + "/cloudide/api/v3/common/GetUserToken").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Cookie", cookie);
            c.setRequestProperty("Origin", "https://www.trae.cn");
            c.setRequestProperty("Referer", "https://www.trae.cn/");
            c.setRequestProperty("User-Agent", WEB_UA);
            byte[] body = "{}".getBytes("UTF-8");
            OutputStream os = c.getOutputStream();
            try {
                os.write(body);
                os.flush();
            } finally {
                os.close();
            }
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            String text = in == null ? "" : readAll(in);
            if (text.length() == 0) return null;
            JSONObject root = new JSONObject(text);
            JSONObject result = root.optJSONObject("Result");
            if (result == null) result = root.optJSONObject("result");
            if (result == null) return null;
            String token = result.optString("Token", result.optString("token", ""));
            if (token == null || token.length() <= 20) return null;
            return token;
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 兼容 {checked_in:...}、{checkedIn:...} 与 {code:0,data:{...}} 等响应包装。 */
    public static JSONObject unwrap(JSONObject r) {
        if (r == null) return new JSONObject();
        try {
            JSONObject d = r.optJSONObject("data");
            if (d != null && (d.has("checked_in") || d.has("checkedIn") || d.has("credits")
                    || d.has("enable") || d.has("code") || d.has("usage_summary")
                    || d.has("usageSummary"))) {
                return d;
            }
        } catch (Exception ignored) {
        }
        return r;
    }

    /** 判断响应里是否存在指定字段（忽略大小写与命名风格差异）。 */
    public static boolean hasValue(JSONObject o, String... names) {
        return findValue(o, names) != null;
    }

    /** 灵活取布尔值，兼容 snake_case / camelCase。 */
    public static boolean bool(JSONObject o, String... names) {
        Object v = findValue(o, names);
        if (v == null) return false;
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        String s = String.valueOf(v).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    /** 灵活取字符串，兼容 JSON string / number。 */
    public static String string(JSONObject o, String... names) {
        Object v = findValue(o, names);
        return v == null ? "" : String.valueOf(v);
    }

    /** 灵活取整数值，兼容 JSON number / 字符串。 */
    public static int intValue(JSONObject o, String... names) {
        return (int) longValue(o, names);
    }

    /** 灵活取长整数值，兼容 JSON number / 字符串。 */
    public static long longValue(JSONObject o, String... names) {
        Object v = findValue(o, names);
        if (v == null) return 0L;
        try {
            if (v instanceof Number) return ((Number) v).longValue();
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 灵活取浮点数值，兼容 JSON number / 字符串。 */
    public static double doubleValue(JSONObject o, String... names) {
        Object v = findValue(o, names);
        if (v == null) return -1d;
        try {
            if (v instanceof Number) return ((Number) v).doubleValue();
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (Throwable t) {
            return -1d;
        }
    }

    /**
     * 从权益包原始响应解析剩余积分。
     * 优先使用服务端账本 usage_summary.total_amount - consumed_amount；
     * 旧接口无账本字段时回退到包名义额度累加。
     */
    public static double parseEntitlements(JSONObject resp, JSONObject rawOut) {
        if (resp == null || resp.has("__error") || resp.has("__text")) return -1;
        try {
            Object summaryNode = findValue(resp, "usage_summary", "usageSummary");
            if (summaryNode instanceof JSONObject) {
                JSONObject summary = (JSONObject) summaryNode;
                double total = doubleValue(summary, "total_amount", "totalAmount");
                double consumed = doubleValue(summary, "consumed_amount", "consumedAmount");
                if (total >= 0d && consumed >= 0d) {
                    if (rawOut != null) {
                        rawOut.put("packs", new JSONArray());
                        rawOut.put("raw", resp.toString());
                    }
                    return total - consumed;
                }
            }
            Object packsNode = findValue(resp, "user_entitlement_pack_list",
                    "userEntitlementPackList");
            JSONArray packs = packsNode instanceof JSONArray ? (JSONArray) packsNode : null;
            if (packs == null) return -1;
            double remaining = 0;
            JSONArray out = new JSONArray();
            for (int i = 0; i < packs.length(); i++) {
                JSONObject p = packs.optJSONObject(i);
                if (p == null) continue;
                double limit = doubleValue(p, "credits_limit", "creditsLimit");
                double used = doubleValue(p, "credits_amount", "creditsAmount");
                if (limit < 0) limit = 0;
                if (used < 0) used = 0;
                double rem = limit - used;
                if (rem < 0) rem = 0;
                remaining += rem;
                JSONObject item = new JSONObject();
                item.put("limit", limit);
                item.put("used", used);
                item.put("remaining", rem);
                String title = findString(p, "title", "name", "product_name", "pack_name",
                        "display_name");
                if (title != null && title.length() > 0) item.put("title", title);
                out.put(item);
            }
            if (rawOut != null) {
                rawOut.put("packs", out);
                rawOut.put("raw", resp.toString());
            }
            return remaining;
        } catch (Exception ignored) {
            return -1;
        }
    }

    /** 递归查找字段，忽略大小写、下划线、连字符与点号差异。 */
    private static Object findValue(Object node, String... names) {
        if (node == null || names == null || names.length == 0) return null;
        Set<String> targets = new HashSet<String>();
        for (int i = 0; i < names.length; i++) targets.add(normalize(names[i]));
        return findValueNormalized(node, targets);
    }

    private static Object findValueNormalized(Object node, Set<String> targets) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (targets.contains(normalize(k))) {
                    Object v = o.opt(k);
                    if (v != null && v != JSONObject.NULL) return v;
                }
            }
            keys = o.keys();
            while (keys.hasNext()) {
                Object v = o.opt(keys.next());
                Object found = findValueNormalized(v, targets);
                if (found != null) return found;
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                Object found = findValueNormalized(arr.opt(i), targets);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String normalize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '_' && c != '-' && c != '.') sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    private static String findString(JSONObject root, String... names) {
        Object v = findValue(root, names);
        return v == null ? null : String.valueOf(v);
    }

    private static long findLong(JSONObject root, String... names) {
        return longValue(root, names);
    }
}
