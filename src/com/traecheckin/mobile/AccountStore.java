package com.traecheckin.mobile;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 本地数据存储：多账号、设置与签到历史。
 * 全部持久化在 SharedPreferences 的一个 JSON 字符串中（应用私有目录，无需文件权限）。
 */
public class AccountStore {

    public static class Account {
        public String id;
        public String alias;
        public String token;
        public String deviceId;
        public long addedAt;
        public boolean checkedIn;
        public double todayCredits;
        public double remaining = -1;
        public String lastCheckinDate = "";
        public long lastCheckTime;
        public String lastMessage = "";
        public boolean tokenValid = true;
        /**
         * 网页版登录态 cookie（{@code www.trae.cn} + {@code api.trae.cn} 合并）。
         *
         * <p>网页版的 JWT 是短期凭证（约 1 小时），过期后可用这份长期 cookie
         * 重新调用 {@code GetUserToken} 换新 token，实现「token 过期自愈」。
         * 永不出现在 {@link #toUiJson(String)} 快照里。</p>
         */
        public String cookie = "";
        /**
         * JWT 里的稳定用户标识（{@code uid/sub/user_id} 任一）。
         *
         * <p>每次 {@code GetUserToken} 签发的 JWT 整串都不同（{@code iat}/{@code exp} 在变），
         * 因此「同一账号是否已存在」必须按 uid 判断，不能比整串 token。</p>
         */
        public String uid = "";
        /** SOLO OAuth 的长期刷新令牌（网页版 GetUserToken 没有该字段）。 */
        public String refreshToken = "";
        /** accessToken 过期时间（Unix 秒，0=未知）。 */
        public long expiresAt = 0L;
        /**
         * 官方授权链接里的 machine_id（32 位十六进制）。
         *
         * <p>它与 {@link #deviceId} 是两套标识：device_id 是 12~20 位纯数字 Aha 号，
         * machine_id 是独立的 32 位 hex，只随授权链接下发；claim 请求不携带它。</p>
         */
        public String machineId = "";
        /**
         * 设备号来源：true = 用户在「设置设备号」里手动填入的真机号。
         *
         * <p>手动号必须原样沿用、绝不能被 9074 自动换号覆盖（参考实现
         * {@code assign_device_id} 的 Existing 语义：轮换等于把已验证的绑定弄坏）。</p>
         */
        public boolean deviceIdManual = false;
        /** 上次因 9074 自动换号的时间戳（毫秒），用于 24 小时限频。 */
        public long deviceRotatedAt = 0L;
        /**
         * 当前设备号是否已经过一次成功领取验证。
         *
         * <p>已验证的号在 9074 时绝不自动轮换：自动换号会把上游唯一认可的
         * 绑定换掉，导致原本能签到的账号永久 9074。设备号变化时必须清零。</p>
         */
        public boolean deviceIdVerified = false;

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("alias", alias);
                o.put("token", token);
                o.put("device_id", deviceId);
                o.put("added_at", addedAt);
                o.put("checked_in", checkedIn);
                o.put("today_credits", todayCredits);
                o.put("remaining", remaining);
                o.put("last_checkin_date", lastCheckinDate);
                o.put("last_check_time", lastCheckTime);
                o.put("last_message", lastMessage);
                o.put("token_valid", tokenValid);
                o.put("cookie", cookie);
                o.put("uid", uid);
                o.put("refresh_token", refreshToken);
                o.put("expires_at", expiresAt);
                o.put("machine_id", machineId);
                o.put("device_id_manual", deviceIdManual);
                o.put("device_rotated_at", deviceRotatedAt);
                o.put("device_id_verified", deviceIdVerified);
            } catch (Exception ignored) {
            }
            return o;
        }

        static Account fromJson(JSONObject o) {
            if (o == null) return null;
            Account a = new Account();
            a.id = o.optString("id");
            a.alias = o.optString("alias", "账号");
            a.token = o.optString("token");
            a.deviceId = o.optString("device_id");
            a.addedAt = o.optLong("added_at");
            a.checkedIn = o.optBoolean("checked_in");
            a.todayCredits = o.optDouble("today_credits", 0);
            a.remaining = o.optDouble("remaining", -1);
            a.lastCheckinDate = o.optString("last_checkin_date");
            a.lastCheckTime = o.optLong("last_check_time");
            a.lastMessage = o.optString("last_message");
            a.tokenValid = o.optBoolean("token_valid", true);
            a.cookie = o.optString("cookie");
            a.uid = o.optString("uid");
            a.refreshToken = o.optString("refresh_token");
            a.expiresAt = o.optLong("expires_at", 0L);
            a.machineId = o.optString("machine_id");
            a.deviceIdManual = o.optBoolean("device_id_manual", false);
            a.deviceRotatedAt = o.optLong("device_rotated_at", 0L);
            // 旧版 JSON 没有该字段：已有合法设备号视为已验证，避免升级后
            // 第一次 9074 就把历史账号的可用号自动换掉。
            a.deviceIdVerified = o.has("device_id_verified")
                    ? o.optBoolean("device_id_verified", false)
                    : isValidDeviceId(a.deviceId);
            return a;
        }

        /** 给 UI 的快照（不含 token）。 */
        JSONObject toUiJson(String today) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("alias", alias);
                o.put("added_at", addedAt);
                o.put("checked_in", checkedIn && today.equals(lastCheckinDate));
                o.put("today_credits", todayCredits);
                o.put("remaining", remaining);
                o.put("last_check_time", lastCheckTime);
                o.put("last_message", lastMessage);
                o.put("token_valid", tokenValid);
            } catch (Exception ignored) {
            }
            return o;
        }
    }

    private static AccountStore sInstance;

    public static synchronized AccountStore get(android.content.Context ctx) {
        if (sInstance == null) sInstance = new AccountStore(
                ctx.getApplicationContext().getSharedPreferences("trae_checkin_store", 0));
        return sInstance;
    }

    private final android.content.SharedPreferences sp;
    private JSONObject data;

    private AccountStore(android.content.SharedPreferences sp) {
        this.sp = sp;
        try {
            data = new JSONObject(sp.getString("data", ""));
        } catch (Exception e) {
            data = new JSONObject();
        }
        sanitizeSchema();
        migrateDeviceIds();
    }

    /**
     * 校验存储结构。
     *
     * <p>旧版（WebView 版）可能写过不同 schema，若 {@code accounts} 不是数组，
     * 先把整份原始数据备份到 {@code data_legacy} 再重置为空数组，
     * 避免「账号静默消失」且不丢原始数据。</p>
     */
    private void sanitizeSchema() {
        try {
            if (!data.has("accounts")) return;
            Object raw = data.opt("accounts");
            if (raw instanceof JSONArray) return;
            data.put("data_legacy", data.toString());
            data.put("accounts", new JSONArray());
            persist();
        } catch (Exception ignored) {
        }
    }

    private void persist() {
        try {
            sp.edit().putString("data", data.toString()).commit();
        } catch (Exception ignored) {
        }
    }

    private JSONArray accountsArr() {
        JSONArray a = data.optJSONArray("accounts");
        if (a == null) {
            a = new JSONArray();
            try {
                data.put("accounts", a);
            } catch (Exception ignored) {
            }
        }
        return a;
    }

    public static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
    }

    /** UI 友好别名：读取全部账号（与 {@link #accounts()} 等价）。 */
    public synchronized List<Account> loadAccounts() {
        return accounts();
    }

    /** UI 友好别名：删除账号（与 {@link #remove(String)} 等价）。 */
    public synchronized boolean delete(String id) {
        return remove(id);
    }

    /** 账号展示名：优先自定义别名，空则回落到「Trae 账号」。 */
    public static String displayName(Account a) {
        if (a == null) return "Trae 账号";
        String alias = a.alias == null ? "" : a.alias.trim();
        return alias.length() == 0 ? "Trae 账号" : alias;
    }

    /** 清空全部签到历史（设置页「清除日志」）。 */
    public synchronized void clearHistory() {
        try {
            data.put("history", new JSONArray());
            persist();
        } catch (Exception ignored) {
        }
    }

    public synchronized List<Account> accounts() {
        List<Account> list = new ArrayList<Account>();
        JSONArray arr = accountsArr();
        for (int i = 0; i < arr.length(); i++) {
            Account a = Account.fromJson(arr.optJSONObject(i));
            if (a == null) continue;   // 脏数据（null 元素）直接跳过，绝不 NPE
            list.add(a);
        }
        return list;
    }

    public synchronized Account byId(String id) {
        if (id == null) return null;
        JSONArray arr = accountsArr();
        for (int i = 0; i < arr.length(); i++) {
            Account a = Account.fromJson(arr.optJSONObject(i));
            if (a != null && id.equals(a.id)) return a;
        }
        return null;
    }

    /** token 已存在则返回对应账号 id，否则 null。 */
    public synchronized String findToken(String token) {
        if (token == null) return null;
        JSONArray arr = accountsArr();
        for (int i = 0; i < arr.length(); i++) {
            Account a = Account.fromJson(arr.optJSONObject(i));
            if (a != null && token.equals(a.token)) return a.id;
        }
        return null;
    }

    /**
     * 按 JWT 用户标识查重（同一账号重复登录时整串 token 不同、uid 相同）。
     *
     * @param uid {@link #uidFromToken(String)} 的结果
     * @return 命中的账号；没命中返回 null
     */
    public synchronized Account findByUid(String uid) {
        if (uid == null || uid.length() == 0) return null;
        JSONArray arr = accountsArr();
        for (int i = 0; i < arr.length(); i++) {
            Account a = Account.fromJson(arr.optJSONObject(i));
            if (a != null && uid.equals(a.uid)) return a;
        }
        return null;
    }

    /** 新增账号（由登录页捕获的 token 创建），返回账号对象。 */
    public synchronized Account add(String token) {
        return add(token, newDeviceId(), null);
    }

    /**
     * 新增账号，允许指定设备号与别名（登录页先校验、后落库时使用）。
     *
     * @param token    登录凭证（JWT）
     * @param deviceId 校验时使用的设备号，必须与落库保持一致，否则签到会报 9004
     * @param alias    展示别名，为空时按 JWT 载荷推导
     */
    public synchronized Account add(String token, String deviceId, String alias) {
        JSONArray arr = accountsArr();
        List<String> used = new ArrayList<String>();
        for (int i = 0; i < arr.length(); i++) {
            Account old = Account.fromJson(arr.optJSONObject(i));
            if (old != null && old.deviceId != null) used.add(old.deviceId);
        }
        Account a = new Account();
        a.id = "a" + System.currentTimeMillis() + "_" + (int) (Math.random() * 10000);
        a.token = token;
        // 新账号只接受合法且不撞号的设备号；否则生成一个唯一号，避免 9095。
        a.deviceId = isValidDeviceId(deviceId) && !used.contains(deviceId)
                ? deviceId : newDeviceId(used);
        a.addedAt = System.currentTimeMillis();
        a.alias = (alias == null || alias.trim().length() == 0)
                ? aliasFromToken(token, arr.length() + 1) : alias.trim();
        a.checkedIn = false;
        a.tokenValid = true;
        a.deviceIdVerified = false;
        a.lastMessage = "";
        arr.put(a.toJson());
        persist();
        return a;
    }

    /** 当前账号数量。 */
    public synchronized int count() {
        return accountsArr().length();
    }

    /** 生成一个新的设备号（官方客户端形态：12~20 位纯数字，签到接口才会认可）。 */
    public static String newDeviceId() {
        return randomNumericDeviceId();
    }

    /** 生成一个不与已有账号撞号的设备号（一个设备号一天只能签一个账号）。 */
    public static String newDeviceId(List<String> used) {
        for (int i = 0; i < 100; i++) {
            String id = randomNumericDeviceId();
            if (used == null || !used.contains(id)) return id;
        }
        String base = randomNumericDeviceId();
        return uniqueSibling(base, used);
    }

    /** 生成一个不与当前已保存账号撞号的设备号。 */
    public synchronized String newUniqueDeviceId() {
        List<String> used = new ArrayList<String>();
        for (Account a : accounts()) {
            if (a != null && a.deviceId != null) used.add(a.deviceId);
        }
        return newDeviceId(used);
    }

    /** 设备号是否符合官方客户端形态：12~20 位纯数字。 */
    public static boolean isValidDeviceId(String d) {
        if (d == null || d.length() < 12 || d.length() > 20) return false;
        for (int i = 0; i < d.length(); i++) {
            if (d.charAt(i) < '0' || d.charAt(i) > '9') return false;
        }
        return true;
    }

    /**
     * 从用户输入中提取合法设备号。
     *
     * <p>两种输入都支持：直接就是 12~20 位纯数字；或是电脑版 TRAE 客户端
     * {@code storage.json} 的整段文本 —— 号写在键名上：
     * {@code iCubeAuthInfo://icube-dc:1234567890123}（参考
     * {@code login.rs::extract_device_ids_from_storage_json}）。
     * 键名里的值可能含 token，这里只读键名、绝不解析值。</p>
     *
     * @return 合法设备号；提取不到返回 ""
     */
    public static String extractDeviceId(String text) {
        if (text == null) return "";
        String s = text.trim();
        if (isValidDeviceId(s)) return s;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("iCubeAuthInfo://icube-dc:\\s*(\\d{12,20})")
                    .matcher(s);
            while (m.find()) {
                if (isValidDeviceId(m.group(1))) return m.group(1);
            }
            // 兜底：文本里任意一段 12~20 位连续数字（复制时只带了号的情况）
            java.util.regex.Matcher n = java.util.regex.Pattern
                    .compile("(?<!\\d)(\\d{12,20})(?!\\d)").matcher(s);
            while (n.find()) {
                if (isValidDeviceId(n.group(1))) return n.group(1);
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** 查找占用该设备号的其它账号（同一设备一天只能签一个账号，撞号会回 9095）。 */
    public synchronized Account findByDeviceId(String deviceId, String excludeId) {
        if (deviceId == null || deviceId.length() == 0) return null;
        JSONArray arr = accountsArr();
        for (int i = 0; i < arr.length(); i++) {
            Account a = Account.fromJson(arr.optJSONObject(i));
            if (a == null) continue;
            if (excludeId != null && excludeId.equals(a.id)) continue;
            if (deviceId.equals(a.deviceId)) return a;
        }
        return null;
    }

    /**
     * 给用户手动设置的账号写入设备号（或把号恢复成自动生成）。
     *
     * <p>同时重置今日签到缓存与换号时间戳：换号后旧状态不再可信，
     * 需要重新走一次 status / claim。</p>
     */
    public synchronized boolean setDeviceId(String id, String deviceId, boolean manual) {
        if (!isValidDeviceId(deviceId)) return false;
        JSONArray arr = accountsArr();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null || !o.optString("id").equals(id)) continue;
            try {
                o.put("device_id", deviceId);
                o.put("device_id_manual", manual);
                o.put("device_id_verified", false);
                o.put("device_rotated_at", 0L);
                o.put("checked_in", false);
                o.put("last_checkin_date", "");
                o.put("today_credits", 0);
                o.put("last_message", "");
                o.put("token_valid", true);
                arr.put(i, o);
                data.put("accounts", arr);
                persist();
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;
    }

    /** 9074 后换号：保持真机码形态、避开已有账号，且必须与当前被拒号不同。 */
    public static String rotateDeviceId(String current, List<String> used) {
        List<String> blocked = new ArrayList<String>();
        if (used != null) blocked.addAll(used);
        if (current != null && current.length() > 0) blocked.add(current);
        if (isValidDeviceId(current)) {
            String sibling = uniqueSibling(current, blocked);
            if (!sibling.equals(current)) return sibling;
        }
        return newDeviceId(blocked);
    }

    private static String randomNumericDeviceId() {
        long bits = UUID.randomUUID().getLeastSignificantBits() & 0x7fffffffffffffffL;
        long n = 1000000000000000L + bits % 8000000000000000L;
        return Long.toString(n);
    }

    /** 前缀不变、末 4 位递增，得到同源但不同的纯数字设备号。 */
    private static String uniqueSibling(String base, List<String> used) {
        if (!isValidDeviceId(base)) return newDeviceId(used);
        String head = base.substring(0, base.length() - 4);
        int tail;
        try {
            tail = Integer.parseInt(base.substring(base.length() - 4));
        } catch (Throwable t) {
            return newDeviceId(used);
        }
        for (int step = 1; step <= 9999; step++) {
            String candidate = head + String.format(Locale.US, "%04d", (tail + step) % 10000);
            if (candidate.equals(base)) continue;
            if (used != null && used.contains(candidate)) continue;
            return candidate;
        }
        return newDeviceId(used);
    }

    /** 从账号标识稳定推导一个 16 位纯数字设备号，用于迁移旧版 UUID / hex 设备号。 */
    private static String stableDeviceId(String seed, List<String> used) {
        long hash = 0xcbf29ce484222325L;
        String s = seed == null ? "" : seed;
        for (int i = 0; i < s.length(); i++) {
            hash ^= (long) s.charAt(i);
            hash *= 0x100000001b3L;
        }
        long n = 1000000000000000L + (hash & 0x7fffffffffffffffL) % 8000000000000000L;
        String base = Long.toString(n);
        if (used == null || !used.contains(base)) return base;
        return uniqueSibling(base, used);
    }

    /**
     * 旧版账号迁移：把 32 位 hex/UUID 设备号换成官方客户端纯数字设备号。
     * 同一账号多次启动推导结果稳定；不同账号自动避让，避免 9095 撞号。
     */
    private void migrateDeviceIds() {
        try {
            JSONArray arr = accountsArr();
            List<String> used = new ArrayList<String>();
            boolean changed = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("device_id");
                String uid = o.optString("uid");
                String accountId = o.optString("id");
                // 手动设置的真机号是用户明确指定的，自动迁移绝不改它，
                // 否则一次启动就把唯一可能有效的号换成了随机号。
                if (o.optBoolean("device_id_manual", false) && isValidDeviceId(id)) {
                    used.add(id);
                    continue;
                }
                if (isValidDeviceId(id) && !used.contains(id)) {
                    used.add(id);
                    continue;
                }
                String fresh = stableDeviceId(accountId + "|" + uid + "|" + id, used);
                o.put("device_id", fresh);
                o.put("device_id_verified", false);
                o.put("last_message", "");
                o.put("token_valid", true);
                used.add(fresh);
                changed = true;
            }
            if (changed) persist();
        } catch (Throwable ignored) {
        }
    }

    /** 回写账号状态（按 id 定位）。 */
    public synchronized void update(Account acc) {
        if (acc == null) return;
        JSONArray arr = accountsArr();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && o.optString("id").equals(acc.id)) {
                try {
                    // 写库前强制不变量：设备号一旦变化，旧号上的“已验证”必须清零。
                    // 这样即使未来某个调用方忘了重置，也不会把新号误标成已验证。
                    String oldDeviceId = o.optString("device_id");
                    if (!oldDeviceId.equals(acc.deviceId)) {
                        acc.deviceIdVerified = false;
                    }
                    arr.put(i, acc.toJson());
                    data.put("accounts", arr);
                    persist();
                } catch (Exception ignored) {
                }
                return;
            }
        }
    }

    public synchronized boolean remove(String id) {
        JSONArray arr = accountsArr();
        JSONArray out = new JSONArray();
        boolean found = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && o.optString("id").equals(id)) {
                found = true;
                continue;
            }
            out.put(o);
        }
        if (found) {
            try {
                data.put("accounts", out);
                persist();
            } catch (Exception ignored) {
            }
        }
        return found;
    }

    public synchronized void rename(String id, String alias) {
        JSONArray arr = accountsArr();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && o.optString("id").equals(id)) {
                try {
                    o.put("alias", alias == null ? "" : alias.trim());
                    arr.put(i, o);
                    data.put("accounts", arr);
                    persist();
                } catch (Exception ignored) {
                }
                return;
            }
        }
    }

    /** 兼容旧调用点：只替换 token，保留旧的整份 OAuth 凭证。 */
    public synchronized boolean replaceToken(String id, String token) {
        return replaceAuth(id, token, null, 0L, null, null, null, null);
    }

    /**
     * 用 SOLO OAuth 凭证给已有账号重新登录。
     * 空参数表示「保留旧值」，避免只更新 token 时把 refreshToken / 设备号清掉。
     */
    public synchronized boolean replaceAuth(String id, String token, String refreshToken,
                                            long expiresAt, String deviceId, String machineId,
                                            String cookie, String uid) {
        JSONArray arr = accountsArr();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null || !o.optString("id").equals(id)) continue;
            try {
                String oldDeviceId = o.optString("device_id");
                boolean oldVerified = o.has("device_id_verified")
                        ? o.optBoolean("device_id_verified", false)
                        : isValidDeviceId(oldDeviceId);
                o.put("token", token);
                if (refreshToken != null && refreshToken.length() > 0) {
                    o.put("refresh_token", refreshToken);
                }
                if (expiresAt > 0L) o.put("expires_at", expiresAt);
                if (isValidDeviceId(deviceId)) {
                    o.put("device_id", deviceId);
                    // 同号重新登录保留“已验证”；换号必须重新验证，不能继承旧标记。
                    o.put("device_id_verified",
                            deviceId.equals(oldDeviceId) ? oldVerified : false);
                }
                if (machineId != null && machineId.length() > 0) {
                    o.put("machine_id", machineId);
                }
                if (cookie != null && cookie.length() > 0) o.put("cookie", cookie);
                if (uid != null && uid.length() > 0) o.put("uid", uid);
                o.put("token_valid", true);
                o.put("last_message", "");
                o.put("checked_in", false);
                o.put("last_checkin_date", "");
                o.put("today_credits", 0);
                arr.put(i, o);
                data.put("accounts", arr);
                persist();
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 账号导入 / 导出（v2.4.0）
    // ------------------------------------------------------------------

    /**
     * 导出全部账号为 JSON 文本。
     *
     * <p><b>含完整登录凭据（token / refreshToken / cookie）</b>，调用方必须提示用户
     * 妥善保管、不要外传。带 app/version/exported_at 头，便于跨版本识别。</p>
     */
    public synchronized String exportAccountsJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("app", "TraeCheckin");
            root.put("version", 1);
            root.put("exported_at", System.currentTimeMillis());
            JSONArray arr = new JSONArray();
            for (Account a : accounts()) arr.put(a.toJson());
            root.put("accounts", arr);
            return root.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 从 JSON 文本导入账号，返回实际新增数量。
     *
     * <p>只追加、不覆盖现有账号；按 token / uid 去重；保留设备号与 refreshToken，
     * 但把「今日已签到」状态清零，避免把旧设备上的签到状态带过来。</p>
     *
     * @throws IllegalArgumentException 文本不是本应用的导出格式
     */
    public synchronized int importAccountsJson(String text) throws IllegalArgumentException {
        if (text == null || text.trim().length() == 0) {
            throw new IllegalArgumentException("empty");
        }
        JSONObject root;
        JSONArray arr;
        try {
            root = new JSONObject(text.trim());
            if (!"TraeCheckin".equals(root.optString("app", ""))) {
                throw new IllegalArgumentException("app mismatch");
            }
            arr = root.optJSONArray("accounts");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("bad json");
        }
        if (arr == null) throw new IllegalArgumentException("no accounts");

        int added = 0;
        for (int i = 0; i < arr.length(); i++) {
            Account a = Account.fromJson(arr.optJSONObject(i));
            if (a == null || a.token == null || a.token.length() < 10) continue;
            String uid = (a.uid != null && a.uid.length() > 0) ? a.uid : uidFromToken(a.token);
            if (findToken(a.token) != null) continue;
            if (uid != null && uid.length() > 0 && findByUid(uid) != null) continue;

            // 用 add() 生成新的稳定 id，再补齐 OAuth 凭证字段。
            // 如果导入的设备号非法/撞号，add() 会换新号，此时不能继承“已验证”。
            String importedDeviceId = a.deviceId == null ? "" : a.deviceId;
            Account fresh = add(a.token, importedDeviceId, a.alias);
            fresh.cookie = a.cookie == null ? "" : a.cookie;
            fresh.uid = uid == null ? "" : uid;
            fresh.refreshToken = a.refreshToken == null ? "" : a.refreshToken;
            fresh.expiresAt = a.expiresAt;
            fresh.machineId = a.machineId == null ? "" : a.machineId;
            boolean deviceKept = importedDeviceId.equals(fresh.deviceId);
            fresh.deviceIdManual = deviceKept && a.deviceIdManual;
            fresh.deviceIdVerified = deviceKept && a.deviceIdVerified;
            fresh.tokenValid = true;
            fresh.checkedIn = false;
            fresh.lastCheckinDate = "";
            fresh.todayCredits = 0;
            fresh.lastMessage = "";
            update(fresh);
            added++;
        }
        return added;
    }

    public synchronized JSONObject settings() {
        JSONObject s = data.optJSONObject("settings");
        if (s == null) {
            s = new JSONObject();
            try {
                s.put("auto_enabled", false);
                s.put("auto_time", "08:00");
                s.put("open_auto", true);
                data.put("settings", s);
            } catch (Exception ignored) {
            }
        }
        return s;
    }

    public synchronized void setSettings(JSONObject in) {
        try {
            JSONObject cur = settings();
            java.util.Iterator<String> it = in.keys();
            while (it.hasNext()) {
                String k = it.next();
                cur.put(k, in.get(k));
            }
            data.put("settings", cur);
            persist();
        } catch (Exception ignored) {
        }
    }

    /** 全局签到历史（最新在前）。 */
    public synchronized JSONArray history() {
        JSONArray h = data.optJSONArray("history");
        return h == null ? new JSONArray() : h;
    }

    /** 成功记录入口：保留原签名，并为新旧记录统一补上 success/message/code。 */
    public synchronized void addHistory(Account a, double credits, double remaining) {
        addHistoryAttempt(a, true, "签到成功", 0, credits, remaining);
    }

    /** 记录一次真实签到尝试的失败原因，不保存凭据或完整接口响应。 */
    public synchronized void addFailedHistory(Account a, String message, int code, double remaining) {
        addHistoryAttempt(a, false, message, code, 0, remaining);
    }

    /** 历史统一写入入口：最新在前，最多保留 200 条。 */
    private void addHistoryAttempt(Account a, boolean success, String message, int code,
                                   double credits, double remaining) {
        try {
            JSONArray h = history();
            JSONObject item = new JSONObject();
            item.put("time", System.currentTimeMillis());
            item.put("account_id", a.id);
            item.put("alias", a.alias);
            item.put("success", success);
            item.put("message", message == null ? "" : message);
            item.put("code", code);
            // code 是上游业务码；网络失败时没有业务码，code=0 不代表成功。
            if (!success && NetHelper.isNetworkMessage(message)) {
                item.put("error_type", "network");
            }
            item.put("credits", credits);
            item.put("remaining", remaining);
            // 排障字段：9074 是设备号校验，日志必须带上号与来源才能定位。
            // 只写设备标识，绝不写 token / refreshToken / cookie / 完整响应体。
            item.put("device_id", a.deviceId == null ? "" : a.deviceId);
            item.put("device_src", a.deviceIdManual ? "manual" : "auto");
            item.put("device_rotated", a.deviceRotatedAt > 0L);
            item.put("device_verified", a.deviceIdVerified);
            item.put("req_source", 2);
            JSONArray out = new JSONArray();
            out.put(item);
            for (int i = 0; i < h.length() && i < 199; i++) out.put(h.opt(i));
            data.put("history", out);
            persist();
        } catch (Exception ignored) {
        }
    }

    /** 登录后解析 JWT 载荷，尽力取一个可读别名（旧 API，等价于 {@link #aliasFromToken}）。 */
    static String jwtAlias(String token, int index) {
        return aliasFromToken(token, index);
    }

    /**
     * 从 JWT 载荷推导展示别名。
     *
     * <p>取值优先级：email / 手机号 / nickname / sub，取到第一个非空值后脱敏；
     * 全都没有时回落成「Trae 账号 N」（N 为序号）。</p>
     */
    public static String aliasFromToken(String token, int index) {
        JSONObject payload = jwtPayload(token);
        if (payload != null) {
            String[] keys = {"email", "phone_number", "phone", "mobile",
                    "nickname", "nick_name", "display_name", "name", "user_name", "sub"};
            for (String k : keys) {
                if (!payload.has(k) || payload.isNull(k)) continue;
                String v = String.valueOf(payload.opt(k)).trim();
                if (v.length() == 0 || "null".equalsIgnoreCase(v)) continue;
                return mask(v);
            }
        }
        return "Trae 账号 " + index;
    }

    /**
     * 从 JWT payload 取稳定的用户标识。
     *
     * <p>支持的 key（按优先级）：{@code uid}、{@code user_id}、{@code userId}、
     * {@code userid}、{@code sub}。全都没有时返回空串，调用方按整串 token 兜底查重。</p>
     *
     * @param token JWT
     * @return 用户标识；取不到返回 ""
     */
    public static String uidFromToken(String token) {
        JSONObject payload = jwtPayload(token);
        if (payload == null) return "";
        String[] keys = {"uid", "user_id", "userId", "userid", "sub"};
        for (String k : keys) {
            try {
                if (!payload.has(k) || payload.isNull(k)) continue;
                String v = String.valueOf(payload.opt(k)).trim();
                if (v.length() == 0 || "null".equalsIgnoreCase(v)) continue;
                return v;
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    /** 解析 JWT 第二段（payload），失败返回 null。 */
    private static JSONObject jwtPayload(String token) {
        try {
            if (token == null) return null;
            String[] parts = token.split("\\.");
            if (parts.length < 2) return null;
            byte[] raw = Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            return new JSONObject(new String(raw, "UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    /** 邮箱 / 手机号脱敏：邮箱保留前 2 位，手机号保留前 3 后 4。 */
    static String mask(String v) {
        if (v == null) return "";
        String s = v.trim();
        try {
            if (s.contains("@")) {
                int at = s.indexOf('@');
                String local = s.substring(0, at);
                String domain = s.substring(at);
                int keep = Math.min(2, local.length());
                return local.substring(0, keep) + "***" + domain;
            }
            String digits = s.replaceAll("[^0-9]", "");
            if (digits.length() >= 11) {
                return digits.substring(0, 3) + "****" + digits.substring(digits.length() - 4);
            }
        } catch (Exception ignored) {
        }
        return s;
    }
}
