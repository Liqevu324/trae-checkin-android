package com.traecheckin.mobile;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 签到执行引擎：状态查询 -> 领取 -> 回查状态 -> 余额刷新。
 *
 * <p>对齐官方 SOLO 客户端契约：{@code req_source=2}、12~20 位纯数字设备号、
 * 登录时 device_id 一并注册；{@code code=9074} 是设备校验未通过而不是「人多」。</p>
 */
public class CheckinEngine {

    /**
     * 对单个账号执行完整签到流程。
     * doClaim=true 时只有服务端回查确认 checked_in=true 才算成功。
     */
    public static JSONObject checkinAccount(Context ctx, AccountStore.Account a,
                                            boolean doClaim, boolean withDetail) {
        JSONObject out = new JSONObject();
        final AccountStore store = AccountStore.get(ctx);
        boolean attemptRecorded = false;
        try {
            out.put("account_id", a.id);

            // Wi-Fi / 移动数据切换或刚从 Doze 唤醒时，网络可能尚未就绪。
            // 先短等系统恢复；后续真实请求仍有有限次重试，不以预检代替请求。
            if (!NetHelper.awaitNetwork(ctx, 15000L)) {
                String detail = Thread.currentThread().isInterrupted()
                        ? "网络请求已中断" : "当前无可用网络，请检查 Wi-Fi 或移动数据";
                return fail(store, a, out,
                        ctx.getString(R.string.checkin_err_network_fmt, detail), 0, doClaim);
            }

            JSONObject st = TraeApi.status(a.token, a.deviceId);
            if (st.has("__error")) {
                out.put("network_attempts", st.optInt("__attempts", 1));
                return fail(store, a, out,
                        ctx.getString(R.string.checkin_err_network_fmt, st.optString("__error")),
                        0, doClaim);
            }
            int http = st.optInt("__http", 0);
            int statusCode = TraeApi.intValue(st, "code", "Code");
            if ((http == 401 || statusCode == 1001) && ensureFreshToken(store, a)) {
                st = TraeApi.status(a.token, a.deviceId);
                if (st.has("__error")) {
                    out.put("network_attempts", st.optInt("__attempts", 1));
                    return fail(store, a, out,
                            ctx.getString(R.string.checkin_err_network_fmt, st.optString("__error")),
                            0, doClaim);
                }
                http = st.optInt("__http", 0);
                statusCode = TraeApi.intValue(st, "code", "Code");
            }
            if (http == 401 || statusCode == 1001) {
                return fail(store, a, out, ctx.getString(R.string.checkin_err_login_expired),
                        1001, doClaim);
            }
            if (http == 403) {
                return fail(store, a, out, ctx.getString(R.string.checkin_err_forbidden),
                        403, doClaim);
            }
            if (http >= 400) {
                return fail(store, a, out, ctx.getString(R.string.checkin_err_server_fmt, http),
                        http, doClaim);
            }
            if (!TraeApi.hasValue(st, "checked_in", "checkedIn")
                    && !TraeApi.hasValue(st, "enable")) {
                String msg = TraeApi.string(st, "message", "Message", "msg");
                if (msg.length() == 0) {
                    return fail(store, a, out, ctx.getString(R.string.checkin_err_status_unknown),
                            statusCode, doClaim);
                }
                return fail(store, a, out, ctx.getString(R.string.checkin_err_status_fmt, msg),
                        statusCode, doClaim);
            }

            boolean enable = TraeApi.hasValue(st, "enable")
                    ? TraeApi.bool(st, "enable") : true;
            if (!enable) {
                return fail(store, a, out, ctx.getString(R.string.checkin_err_disabled),
                        0, doClaim);
            }

            boolean checkedIn = TraeApi.bool(st, "checked_in", "checkedIn");
            double todayCredits = Math.max(0d,
                    TraeApi.doubleValue(st, "credits", "today_credits", "todayCredits"));
            boolean claimed = false;
            boolean networkError = false;
            String message = "";
            int resultCode = 0;

            if (!checkedIn && doClaim) {
                ClaimResult cr = performClaim(ctx, store, a);
                claimed = cr.claimed;
                checkedIn = claimed;
                networkError = cr.network;
                message = cr.message;
                resultCode = cr.code;
                if (cr.credits >= 0d) todayCredits = cr.credits;
            }

            // 刷新余额；即使领取被拒，也保留本次成功解析到的最新余额。
            double remaining = a.remaining;
            JSONObject ent = TraeApi.entitlements(a.token, a.deviceId);
            JSONObject detail = new JSONObject();
            double r = TraeApi.parseEntitlements(ent, detail);
            if (r >= 0) remaining = r;
            if (withDetail && detail.has("packs")) {
                out.put("packs", detail.optJSONArray("packs"));
            }

            String today = AccountStore.today();
            a.tokenValid = !message.equals(ctx.getString(R.string.checkin_err_login_expired));
            a.checkedIn = checkedIn;
            a.todayCredits = todayCredits;
            a.remaining = remaining;
            a.lastCheckTime = System.currentTimeMillis();
            if (checkedIn) a.lastCheckinDate = today;
            // 只有真正领取成功（含 9095 回查确认已签）才把当前设备号标记为已验证。
            // 以后 9074 时绝不自动换掉这个号。
            if (claimed) a.deviceIdVerified = true;
            if (claimed) {
                message = ctx.getString(R.string.checkin_msg_success);
                store.addHistory(a, todayCredits, remaining);
                attemptRecorded = true;
            } else if (message.length() == 0) {
                message = checkedIn ? ctx.getString(R.string.checkin_msg_already)
                        : ctx.getString(R.string.checkin_msg_pending);
            }
            if (doClaim && !checkedIn) {
                if (message.length() == 0) message = ctx.getString(R.string.checkin_msg_pending);
                store.addFailedHistory(a, message, resultCode, remaining);
                attemptRecorded = true;
            }
            a.lastMessage = message;
            store.update(a);

            out.put("ok", CheckinResult.isSuccessful(doClaim, checkedIn));
            out.put("checked_in", checkedIn);
            out.put("claimed", claimed);
            out.put("credits", todayCredits);
            out.put("remaining", remaining);
            out.put("message", message);
            out.put("network_error", networkError);
            if (networkError) out.put("error_type", "network");
            if (resultCode != 0) out.put("code", resultCode);
            if (withDetail && detail.has("raw")) {
                String raw = detail.optString("raw");
                if (raw.length() > 8000) raw = raw.substring(0, 8000);
                out.put("raw_entitlement", raw);
            }
        } catch (Exception e) {
            return fail(store, a, out,
                    ctx.getString(R.string.checkin_err_exec_fmt, String.valueOf(e)),
                    0, doClaim && !attemptRecorded);
        }
        return out;
    }

    /** 只刷新余额与状态，不领取。 */
    public static JSONObject refreshOnly(Context ctx, AccountStore.Account a) {
        JSONObject r = checkinAccount(ctx, a, false, true);
        try {
            r.put("claimed", false);
        } catch (Exception ignored) {
        }
        return r;
    }

    /** 列出所有账号的简单状态（不做网络请求）。 */
    public static JSONArray snapshot(AccountStore store) {
        JSONArray arr = new JSONArray();
        for (AccountStore.Account a : store.accounts()) {
            arr.put(a.toUiJson(AccountStore.today()));
        }
        return arr;
    }

    /** token 失效时优先用 SOLO refreshToken 续期；旧账号才回落到 cookie 换短期 JWT。 */
    static boolean ensureFreshToken(AccountStore store, AccountStore.Account a) {
        try {
            if (store == null || a == null) return false;
            if (a.refreshToken != null && a.refreshToken.length() >= 10) {
                JSONObject fresh = TraeApi.exchangeRefreshToken(a.refreshToken);
                if (fresh != null && fresh.optBoolean("ok", false)) {
                    a.token = fresh.optString("token");
                    a.refreshToken = fresh.optString("refresh_token", a.refreshToken);
                    a.expiresAt = fresh.optLong("expires_at", 0L);
                    a.tokenValid = true;
                    store.update(a);
                    return a.token != null && a.token.length() > 20;
                }
            }
            String cookie = a.cookie;
            if (cookie == null || cookie.length() == 0) return false;
            String fresh = TraeApi.refreshToken(cookie);
            if (fresh == null || fresh.length() <= 20) return false;
            a.token = fresh;
            a.tokenValid = true;
            store.update(a);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 回查今日是否已签到。
     * @return TRUE/FALSE 为服务端明确结论；null 表示网络或 HTTP 层无法确认，调用方应按原错误处理。
     */
    private static Boolean statusCheckedIn(AccountStore.Account a) {
        JSONObject c = TraeApi.status(a.token, a.deviceId);
        if (c == null || c.has("__error") || c.optInt("__http", 0) >= 400) return null;
        if (!TraeApi.hasValue(c, "checked_in", "checkedIn")) return null;
        return Boolean.valueOf(TraeApi.bool(c, "checked_in", "checkedIn"));
    }

    /** 判断一次接口响应是否属于「凭证过期」（HTTP 401 或 code 1001）。 */
    static boolean isAuthError(JSONObject resp) {
        if (resp == null) return false;
        try {
            if (resp.optInt("__http", 0) == 401) return true;
            return TraeApi.intValue(resp, "code", "Code") == 1001;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 执行一次领取：处理 1001、9074、9095、9004，并在 code=0 后回查 status 才认成功。 */
    private static ClaimResult performClaim(Context ctx, AccountStore store,
                                            AccountStore.Account a) {
        ClaimResult result = new ClaimResult();
        JSONObject resp = TraeApi.claim(a.token, a.deviceId);
        readClaim(resp, result);
        if (result.network) {
            result.message = ctx.getString(R.string.checkin_err_network_fmt, result.message);
            return result;
        }

        if (result.code == 1001 || result.http == 401) {
            if (!ensureFreshToken(store, a)) {
                result.code = 1001;
                result.message = ctx.getString(R.string.checkin_err_login_expired);
                return result;
            }
            resp = TraeApi.claim(a.token, a.deviceId);
            readClaim(resp, result);
            if (result.network) {
                result.code = 0;
                result.message = ctx.getString(R.string.checkin_err_network_fmt, result.message);
                return result;
            }
            if (result.code == 1001 || result.http == 401) {
                result.code = 1001;
                result.message = ctx.getString(R.string.checkin_err_login_expired);
                return result;
            }
        }

        if (result.code == 9074) {
            // 参考实现 xiaoyinge/trae-signin-gui 的两条硬规则：
            //  1) 用户手动设置的真机号必须原样沿用 —— 它被上游拒绝时换号只会
            //     把唯一可能有效的号弄丢（assign_device_id 的 Existing 语义）；
            //  2) 自动号一天最多自动换一次 —— 每轮都换会污染凭证、掩盖真因。
            // 手机端没有桌面客户端，自动生成的号本就很难通过上游绑定校验，
            // 所以最终处置是引导用户去「设置设备号」填一次真机号。
            if (a.deviceIdManual || a.deviceIdVerified) {
                result.code = 9074;
                result.message = ctx.getString(a.deviceIdManual
                        ? R.string.checkin_err_device_rejected_manual
                        : R.string.checkin_err_device_rejected_verified);
                return result;
            }
            long now = System.currentTimeMillis();
            boolean rateLimited = a.deviceRotatedAt > 0L
                    && now - a.deviceRotatedAt < 24L * 60L * 60L * 1000L;
            if (rateLimited) {
                result.code = 9074;
                result.message = ctx.getString(R.string.checkin_err_device_rejected);
                return result;
            }
            List<String> used = new ArrayList<String>();
            for (AccountStore.Account other : store.accounts()) {
                if (other != null && !a.id.equals(other.id) && other.deviceId != null) {
                    used.add(other.deviceId);
                }
            }
            String rotated = AccountStore.rotateDeviceId(a.deviceId, used);
            a.deviceId = rotated;
            a.deviceRotatedAt = now;
            a.deviceIdVerified = false;
            store.update(a);
            result.deviceRotated = true;
            resp = TraeApi.claim(a.token, a.deviceId);
            readClaim(resp, result);
            if (result.network) {
                result.code = 0;
                result.message = ctx.getString(R.string.checkin_err_network_fmt, result.message);
                return result;
            }
            if (result.code == 9074) {
                result.code = 9074;
                result.message = ctx.getString(R.string.checkin_err_device_rejected_rotated);
                return result;
            }
        }

        if (result.code == 9095) {
            // 9095 = 该设备今日已签到。可能来自两种情况：
            //  1) 另一个账号占用了同一设备号（本次确实没签上）；
            //  2) 上一次 claim 其实已被服务端受理，只是响应在网络上丢了，重试时命中幂等。
            // 用 status 回查区分：本人今天已签到就按「已领取」处理，避免误记失败。
            Boolean already = statusCheckedIn(a);
            if (Boolean.TRUE.equals(already)) {
                result.claimed = true;
                result.code = 0;
                result.message = ctx.getString(R.string.checkin_msg_already);
                return result;
            }
            result.message = ctx.getString(R.string.checkin_err_device_already);
            return result;
        }
        if (result.code == 9004) {
            result.message = ctx.getString(R.string.checkin_err_device_register);
            return result;
        }
        if (result.http >= 400) {
            result.message = ctx.getString(R.string.checkin_err_server_fmt, result.http);
            return result;
        }
        if (result.code != 0) {
            result.message = result.message.length() > 0
                    ? result.message
                    : ctx.getString(R.string.checkin_err_claim_fmt, result.code);
            return result;
        }
        // code=0 但文案仍是拥塞类：服务端其实没有发放，不能记成功。
        if (isCongestion(result.message)) {
            result.message = result.message + "（code=" + result.code + "）";
            return result;
        }

        JSONObject confirm = TraeApi.status(a.token, a.deviceId);
        if (confirm.has("__error")) {
            // claim 可能已被服务端接受，只是回查时断网；标记网络错误以便稍后补查，
            // 后续补签会先读 status，已签到时不会重复领取。
            result.network = true;
            result.code = 0;
            result.message = ctx.getString(R.string.checkin_err_network_fmt,
                    confirm.optString("__error") + "；领取结果待回查");
            return result;
        }
        if (confirm.optInt("__http", 0) >= 400
                || !TraeApi.bool(confirm, "checked_in", "checkedIn")) {
            result.code = TraeApi.intValue(confirm, "code", "Code");
            result.message = ctx.getString(R.string.checkin_err_unconfirmed);
            return result;
        }
        result.claimed = true;
        result.message = ctx.getString(R.string.checkin_msg_success);
        double credits = TraeApi.doubleValue(resp, "credits", "Credits");
        if (credits < 0d) {
            credits = TraeApi.doubleValue(confirm, "credits", "today_credits", "todayCredits");
        }
        result.credits = Math.max(0d, credits);
        return result;
    }

    private static void readClaim(JSONObject resp, ClaimResult result) {
        if (resp == null) {
            result.network = true;
            result.message = "空响应";
            return;
        }
        result.http = resp.optInt("__http", 0);
        result.network = resp.has("__error");
        result.code = TraeApi.intValue(resp, "code", "Code");
        if (result.network) {
            result.message = resp.optString("__error", "网络错误");
            return;
        }
        result.message = TraeApi.string(resp, "message", "Message", "msg");
    }

    private static boolean isCongestion(String message) {
        if (message == null) return false;
        String m = message.toLowerCase(java.util.Locale.US);
        return m.contains("太多") || m.contains("繁忙") || m.contains("拥挤")
                || m.contains("稍后") || m.contains("too many") || m.contains("busy");
    }

    private static JSONObject fail(AccountStore store, AccountStore.Account a,
                                   JSONObject out, String message, int code,
                                   boolean recordAttempt) {
        try {
            out.put("ok", false);
            out.put("message", message);
            boolean network = NetHelper.isNetworkMessage(message);
            out.put("network_error", network);
            if (network) out.put("error_type", "network");
            if (!out.has("checked_in")) out.put("checked_in", false);
            if (!out.has("claimed")) out.put("claimed", false);
            out.put("remaining", a.remaining);
            if (code != 0) out.put("code", code);
            if (code == 1001) {
                out.put("need_relogin", true);
                a.tokenValid = false;
            }
            a.lastMessage = message;
            a.lastCheckTime = System.currentTimeMillis();
            store.update(a);
            if (recordAttempt) store.addFailedHistory(a, message, code, a.remaining);
        } catch (Exception ignored) {
        }
        return out;
    }

    private static final class ClaimResult {
        boolean claimed = false;
        boolean network = false;
        boolean deviceRotated = false;
        int http = 0;
        int code = 0;
        double credits = -1d;
        String message = "";
    }
}
