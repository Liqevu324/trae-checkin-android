package com.traecheckin.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 应用设置持久化。
 *
 * <p>写法与原版 Prefs 保持一致：全部静态方法 + SharedPreferences，零依赖、无状态。
 * 每个 UI 控件都必须在 onCreate 里从这里读回真实值，否则会出现
 * 「重进设置页开关自己关掉」的问题。</p>
 */
public final class Prefs {

    /** SharedPreferences 文件名。 */
    private static final String FILE = "settings";

    public static final String KEY_AUTO_ENABLED = "auto_enabled";
    public static final String KEY_HOUR = "hour";
    public static final String KEY_MINUTE = "minute";
    public static final String KEY_JITTER_MIN = "jitter_min";
    public static final String KEY_NOTIFY_ENABLED = "notify_enabled";
    /** 启动期「通知权限未开启」提示是否已弹过（只提示一次，不弹系统框、不改开关）。 */
    public static final String KEY_NOTIFY_PROMPT_SHOWN = "notify_prompt_shown";
    public static final String KEY_TIMES = "times";
    public static final String KEY_FIRST_OPEN_CHECKIN = "first_open_checkin";
    public static final String KEY_LAST_OPEN_CHECKIN_DATE = "last_open_checkin_date";

    /** v2.4.0：每日凭证保活 / 排除账号。 */
    public static final String KEY_KEEP_ALIVE = "token_keep_alive";
    public static final String KEY_LAST_KEEP_ALIVE_DATE = "last_keep_alive_date";
    public static final String KEY_EXCLUDED_IDS = "excluded_account_ids";

    /** 默认签到时间：09:00。 */
    public static final int DEFAULT_HOUR = 9;
    public static final int DEFAULT_MINUTE = 0;
    /** 默认随机延迟：10 分钟。 */
    public static final int DEFAULT_JITTER_MIN = 10;

    private Prefs() {
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------
    // 自动签到
    // ------------------------------------------------------------------

    /** 是否开启每日自动签到（默认关闭）。 */
    public static boolean autoEnabled(Context ctx) {
        return sp(ctx).getBoolean(KEY_AUTO_ENABLED, false);
    }

    public static void setAutoEnabled(Context ctx, boolean enabled) {
        sp(ctx).edit().putBoolean(KEY_AUTO_ENABLED, enabled).apply();
    }

    // ------------------------------------------------------------------
    // 签到时间
    // ------------------------------------------------------------------

    public static int hour(Context ctx) {
        return sp(ctx).getInt(KEY_HOUR, DEFAULT_HOUR);
    }

    public static int minute(Context ctx) {
        return sp(ctx).getInt(KEY_MINUTE, DEFAULT_MINUTE);
    }

    /** 只写入单时间点的 hour/minute（times 未设置时 {@link #times(Context)} 会回落到这两个值）。 */
    public static void setTime(Context ctx, int hour, int minute) {
        sp(ctx).edit().putInt(KEY_HOUR, hour).putInt(KEY_MINUTE, minute).apply();
    }

    /** 返回所有签到时间点；未设置时返回单条 09:00（或已保存的 hour/minute）作为默认值。 */
    public static List<TimePoint> times(Context ctx) {
        String raw = sp(ctx).getString(KEY_TIMES, "");
        List<TimePoint> list = new ArrayList<TimePoint>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    list.add(new TimePoint(o.optInt("hour", DEFAULT_HOUR), o.optInt("minute", DEFAULT_MINUTE)));
                }
            }
        } catch (Exception ignored) {
            // 首次运行或数据损坏时走默认值
        }
        if (list.isEmpty()) {
            list.add(new TimePoint(sp(ctx).getInt(KEY_HOUR, DEFAULT_HOUR),
                    sp(ctx).getInt(KEY_MINUTE, DEFAULT_MINUTE)));
        }
        Collections.sort(list);
        return list;
    }

    /** 保存所有签到时间点（多时间点支持）。 */
    public static void setTimes(Context ctx, List<TimePoint> times) {
        JSONArray arr = new JSONArray();
        for (TimePoint t : times) {
            JSONObject o = new JSONObject();
            try {
                o.put("hour", t.hour);
                o.put("minute", t.minute);
            } catch (Exception ignored) {
            }
            arr.put(o);
        }
        sp(ctx).edit().putString(KEY_TIMES, arr.toString()).apply();
    }

    // ------------------------------------------------------------------
    // 随机延迟
    // ------------------------------------------------------------------

    public static int jitterMin(Context ctx) {
        return sp(ctx).getInt(KEY_JITTER_MIN, DEFAULT_JITTER_MIN);
    }

    public static void setJitterMin(Context ctx, int minutes) {
        sp(ctx).edit().putInt(KEY_JITTER_MIN, Math.max(0, minutes)).apply();
    }

    // ------------------------------------------------------------------
    // 通知
    // ------------------------------------------------------------------

    /** 是否开启签到结果通知（默认开启）。 */
    public static boolean notifyEnabled(Context ctx) {
        return sp(ctx).getBoolean(KEY_NOTIFY_ENABLED, true);
    }

    public static void setNotifyEnabled(Context ctx, boolean enabled) {
        sp(ctx).edit().putBoolean(KEY_NOTIFY_ENABLED, enabled).apply();
    }

    /** 启动期通知提示是否已展示过。 */
    public static boolean notifyPromptShown(Context ctx) {
        return sp(ctx).getBoolean(KEY_NOTIFY_PROMPT_SHOWN, false);
    }

    public static void setNotifyPromptShown(Context ctx, boolean shown) {
        sp(ctx).edit().putBoolean(KEY_NOTIFY_PROMPT_SHOWN, shown).apply();
    }

    // ------------------------------------------------------------------
    // 首次打开签到
    // ------------------------------------------------------------------

    /** 是否在每天首次打开 APP 时自动签到（默认开启）。 */
    public static boolean firstOpenCheckinEnabled(Context ctx) {
        return sp(ctx).getBoolean(KEY_FIRST_OPEN_CHECKIN, true);
    }

    public static void setFirstOpenCheckinEnabled(Context ctx, boolean enabled) {
        sp(ctx).edit().putBoolean(KEY_FIRST_OPEN_CHECKIN, enabled).apply();
    }

    /** 上次首开签到日期（yyyy-MM-dd），用于判断今天是否已经触发过。 */
    public static String lastOpenCheckinDate(Context ctx) {
        return sp(ctx).getString(KEY_LAST_OPEN_CHECKIN_DATE, "");
    }

    public static void setLastOpenCheckinDate(Context ctx, String date) {
        sp(ctx).edit().putString(KEY_LAST_OPEN_CHECKIN_DATE, date == null ? "" : date).apply();
    }

    // ------------------------------------------------------------------
    // 每日凭证保活（v2.4.0）
    // ------------------------------------------------------------------

    /** 是否每天 03:30 自动换新一次登录凭证，避免到点签到时过期（默认开启）。 */
    public static boolean keepAliveEnabled(Context ctx) {
        return sp(ctx).getBoolean(KEY_KEEP_ALIVE, true);
    }

    public static void setKeepAliveEnabled(Context ctx, boolean enabled) {
        sp(ctx).edit().putBoolean(KEY_KEEP_ALIVE, enabled).apply();
    }

    public static String lastKeepAliveDate(Context ctx) {
        return sp(ctx).getString(KEY_LAST_KEEP_ALIVE_DATE, "");
    }

    public static void setLastKeepAliveDate(Context ctx, String date) {
        sp(ctx).edit().putString(KEY_LAST_KEEP_ALIVE_DATE, date == null ? "" : date).apply();
    }

    // ------------------------------------------------------------------
    // 排除账号（不参与自动签到 / 一键签到）
    // ------------------------------------------------------------------

    /** 返回被排除的账号 id 列表（永不返回 null）。 */
    public static List<String> excludedIds(Context ctx) {
        List<String> out = new ArrayList<String>();
        String raw = sp(ctx).getString(KEY_EXCLUDED_IDS, "");
        if (raw == null || raw.length() == 0) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String id = arr.optString(i, "");
                if (id.length() > 0 && !out.contains(id)) out.add(id);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void setExcludedIds(Context ctx, List<String> ids) {
        JSONArray arr = new JSONArray();
        if (ids != null) {
            for (String id : ids) {
                if (id != null && id.length() > 0) arr.put(id);
            }
        }
        sp(ctx).edit().putString(KEY_EXCLUDED_IDS, arr.toString()).apply();
    }

    public static boolean isExcluded(Context ctx, String accountId) {
        if (accountId == null) return false;
        return excludedIds(ctx).contains(accountId);
    }

    /** 签到时间点值对象（不可变，按分钟排序）。 */
    public static final class TimePoint implements Comparable<TimePoint> {
        public final int hour;
        public final int minute;

        public TimePoint(int hour, int minute) {
            this.hour = hour;
            this.minute = minute;
        }

        public int toMinutes() {
            return hour * 60 + minute;
        }

        /** 形如 09:00 的展示值。 */
        public String format() {
            return String.format(java.util.Locale.CHINA, "%02d:%02d", hour, minute);
        }

        @Override
        public int compareTo(TimePoint other) {
            if (other == null) return 1;
            return Integer.compare(toMinutes(), other.toMinutes());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TimePoint)) return false;
            TimePoint t = (TimePoint) o;
            return hour == t.hour && minute == t.minute;
        }

        @Override
        public int hashCode() {
            return toMinutes();
        }
    }
}
