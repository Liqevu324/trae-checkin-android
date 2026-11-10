package com.traecheckin.mobile;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.util.Calendar;
import java.util.List;
import java.util.Random;

/**
 * 每日自动签到调度（AlarmManager，禁止 WorkManager）。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>目标时间取 {@link Prefs#times(Context)} 中「下一个未来的时间点」，附加 0～jitter 分钟随机延迟；</li>
 *   <li>Android 12+ 未授予「闹钟和提醒」权限时退化为 {@code setAndAllowWhileIdle}，绝不让调度失败；</li>
 *   <li>{@link CheckinReceiver} 执行完必须调用 {@link #rescheduleForce(Context)} 重排下一天，
 *       否则定时任务只跑一次；被动入口（开 App、设置页 onResume）用
 *       {@link #scheduleNext(Context)}，它对已在队列的闹钟做幂等短路，避免把当天还没触发的
 *       闹钟改排到明天。</li>
 * </ul>
 *
 * <p>与规格书的一处差异：引导用户授予精确闹钟权限的动作放在设置页（{@link #openExactAlarmSettings(Context)}），
 * 调度器本身不在后台线程里 startActivity —— 后台启动 Activity 在 Android 10+ 会被系统直接拦截，
 * 且从 BroadcastReceiver 里启动会带来崩溃风险。</p>
 */
public final class AutoScheduler {

    /** CheckinReceiver 的 PendingIntent 请求码。 */
    public static final int REQUEST_CODE = 1001;
    /** MaintenanceReceiver（每日凭证保活）的 PendingIntent 请求码。 */
    public static final int KEEP_ALIVE_REQUEST_CODE = 1002;

    private AutoScheduler() {
    }

    // ------------------------------------------------------------------
    // 时间计算
    // ------------------------------------------------------------------

    /** 随机延迟毫秒数：0～jitter 分钟，jitter 为 0 时返回 0。 */
    static long jitterMs(Context ctx) {
        int jitter = Math.max(0, Prefs.jitterMin(ctx));
        if (jitter == 0) return 0L;
        return new Random().nextInt(jitter + 1) * 60_000L;
    }

    /** 下一次触发的时间戳（毫秒）：最近的一个未来时间点 + 随机延迟。 */
    public static long nextTriggerAt(Context ctx) {
        long now = System.currentTimeMillis();
        List<Prefs.TimePoint> times = Prefs.times(ctx);
        long minDelay = Long.MAX_VALUE;
        for (Prefs.TimePoint t : times) {
            Calendar target = Calendar.getInstance();
            target.set(Calendar.HOUR_OF_DAY, t.hour);
            target.set(Calendar.MINUTE, t.minute);
            target.set(Calendar.SECOND, 0);
            target.set(Calendar.MILLISECOND, 0);
            if (target.getTimeInMillis() <= now) {
                target.add(Calendar.DAY_OF_YEAR, 1);
            }
            long delay = target.getTimeInMillis() - now;
            if (delay < minDelay) minDelay = delay;
        }
        if (minDelay == Long.MAX_VALUE) {
            minDelay = 24 * 60 * 60 * 1000L;
        }
        return now + minDelay + jitterMs(ctx);
    }

    // ------------------------------------------------------------------
    // 调度
    // ------------------------------------------------------------------

    /** 广播用 PendingIntent（FLAG_IMMUTABLE 必须，Android 12+ 强制）。 */
    public static PendingIntent checkinPendingIntent(Context ctx) {
        Intent i = new Intent(ctx, CheckinReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, REQUEST_CODE, i, flags);
    }

    /** 探测是否已有排程中的闹钟；不创建新的 PendingIntent。 */
    private static boolean hasPendingAlarm(Context ctx) {
        return hasPending(ctx, REQUEST_CODE, CheckinReceiver.class);
    }

    private static boolean hasPending(Context ctx, int requestCode, Class<?> receiver) {
        try {
            Intent i = new Intent(ctx, receiver);
            int flags = PendingIntent.FLAG_NO_CREATE;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            return PendingIntent.getBroadcast(ctx, requestCode, i, flags) != null;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 每日凭证保活（独立于「每日自动签到」开关，由自己的开关控制）
    // ------------------------------------------------------------------

    public static PendingIntent keepAlivePendingIntent(Context ctx) {
        Intent i = new Intent(ctx, MaintenanceReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, KEEP_ALIVE_REQUEST_CODE, i, flags);
    }

    /** 下一个 03:30（若今天已过则明天）。 */
    static long nextKeepAliveAt() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 3);
        c.set(Calendar.MINUTE, 30);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) {
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return c.getTimeInMillis();
    }

    /** 幂等排程：每天 03:30 换一次登录凭证。 */
    public static void scheduleKeepAlive(Context ctx) {
        Context app = ctx.getApplicationContext();
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = keepAlivePendingIntent(app);
        if (!Prefs.keepAliveEnabled(app)) {
            try {
                am.cancel(pi);
            } catch (Exception ignored) {
            }
            return;
        }
        if (hasPending(app, KEEP_ALIVE_REQUEST_CODE, MaintenanceReceiver.class)) return;
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextKeepAliveAt(), pi);
        } catch (Exception ignored) {
        }
    }

    /** 保活执行完后强制排下一天（不能用幂等短路，否则只跑一次）。 */
    public static void rescheduleKeepAlive(Context ctx) {
        Context app = ctx.getApplicationContext();
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = keepAlivePendingIntent(app);
        try {
            am.cancel(pi);
        } catch (Exception ignored) {
        }
        if (!Prefs.keepAliveEnabled(app)) return;
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextKeepAliveAt(), pi);
        } catch (Exception ignored) {
        }
    }

    /** 开关关闭时立即取消保活闹钟。 */
    public static void cancelKeepAlive(Context ctx) {
        Context app = ctx.getApplicationContext();
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            am.cancel(keepAlivePendingIntent(app));
        } catch (Exception ignored) {
        }
    }

    /**
     * 被动重排（开 App / 回到设置页）：<b>幂等短路</b>。
     *
     * <p>已有闹钟在队列里就直接返回，不 cancel 不重算 —— 否则用户在
     * 「签到时间 ~ 签到时间+随机延迟」这段窗口内打开 App，会把稍后要触发的闹钟
     * 改成明天，导致当天漏签。</p>
     */
    public static void scheduleNext(Context ctx) {
        Context app = ctx.getApplicationContext();
        // 保活闹钟独立于自动签到开关，任何一次校准都顺带确认它还在队列里
        scheduleKeepAlive(app);
        if (!Prefs.autoEnabled(app)) {
            cancel(app);
            return;
        }
        if (hasPendingAlarm(app)) return;
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        setAlarm(app, am, checkinPendingIntent(app));
    }

    /**
     * 强制重排（先 cancel 再排）。
     *
     * <p>仅用于：自动签到开关切换、签到时间/随机延迟变更、{@link CheckinReceiver}
     * 执行完排下一天、{@link BootReceiver} 开机恢复。</p>
     */
    public static void rescheduleForce(Context ctx) {
        Context app = ctx.getApplicationContext();
        scheduleKeepAlive(app);
        cancel(app);
        if (!Prefs.autoEnabled(app)) return;
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        setAlarm(app, am, checkinPendingIntent(app));
    }

    private static void setAlarm(Context app, AlarmManager am, PendingIntent pi) {
        long triggerAt = nextTriggerAt(app);
        try {
            if (canScheduleExact(app)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                // 未授予精确闹钟权限时退化，保证仍能被唤醒执行
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
        } catch (SecurityException se) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
        }
    }

    /** 用户改设置（开关 / 时间 / 延迟）后的重排：语义是强制。 */
    public static void rescheduleNow(Context ctx) {
        rescheduleForce(ctx);
    }

    /** 取消所有自动签到闹钟。 */
    public static void cancel(Context ctx) {
        Context app = ctx.getApplicationContext();
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            am.cancel(checkinPendingIntent(app));
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 精确闹钟权限
    // ------------------------------------------------------------------

    /** 当前是否具备精确定时能力（Android 11 及以下恒为 true）。 */
    public static boolean canScheduleExact(Context ctx) {
        if (Build.VERSION.SDK_INT < 31) return true;
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            return am == null || am.canScheduleExactAlarms();
        } catch (Exception e) {
            return false;
        }
    }

    /** 跳转系统「闹钟和提醒」权限页，失败则静默返回 false（各机型入口可能不存在）。 */
    public static boolean openExactAlarmSettings(Context ctx) {
        if (Build.VERSION.SDK_INT < 31) return false;
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
