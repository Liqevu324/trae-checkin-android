package com.traecheckin.mobile;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;

import org.json.JSONObject;

import java.util.List;

/**
 * 每日定时自动签到：
 * - AlarmManager 闹钟触发后逐账号执行签到，并以系统通知汇报结果；
 * - 执行完调用 AutoScheduler.rescheduleForce() 强制排下一天（不受幂等短路影响）。
 */
public class CheckinReceiver extends BroadcastReceiver {

    private static final int NOTIFY_ID = 7;

    @Override
    public void onReceive(final Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        PowerManager.WakeLock wl = null;
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "traecheckin:auto");
                wl.acquire(10 * 60 * 1000L);
            }
        } catch (Exception ignored) {
        }

        final PowerManager.WakeLock lock = wl;
        new Thread() {
            @Override
            public void run() {
                try {
                    AccountStore store = AccountStore.get(app);
                    List<AccountStore.Account> accounts = store.accounts();
                    int claimed = 0, failed = 0, total = 0;
                    double gained = 0, remaining = 0;
                    StringBuilder okNames = new StringBuilder();
                    for (AccountStore.Account a : accounts) {
                        // 「管理签到时间与排除账号」里排除的账号不参与自动签到
                        if (Prefs.isExcluded(app, a.id)) continue;
                        total++;
                        try {
                            JSONObject r = CheckinEngine.checkinAccount(app, a, true, false);
                            if (r.optBoolean("ok", false)) {
                                if (r.optBoolean("claimed", false)) {
                                    claimed++;
                                    gained += r.optDouble("credits", 0);
                                    if (okNames.length() > 0) okNames.append("、");
                                    okNames.append(a.alias);
                                }
                                remaining += r.optDouble("remaining", 0);
                            } else {
                                failed++;
                            }
                        } catch (Exception e) {
                            failed++;
                        }
                    }
                    if (total > 0) {
                        notifyResult(app, claimed, failed, total, gained, remaining, okNames.toString());
                    }
                } catch (Exception ignored) {
                } finally {
                    try {
                        // 执行完必须强制排下一天：此处不能用幂等短路的 scheduleNext
                        AutoScheduler.rescheduleForce(app);
                    } catch (Exception ignored) {
                    }
                    if (lock != null && lock.isHeld()) lock.release();
                }
            }
        }.start();
    }

    private void notifyResult(Context ctx, int claimed, int failed, int total,
                              double gained, double remaining, String names) {
        try {
            if (!Prefs.notifyEnabled(ctx)) return;
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 33) {
                if (ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != PackageManager.PERMISSION_GRANTED) return;
            }
            String title;
            String text;
            if (claimed > 0) {
                title = "签到成功 +" + fmt(gained) + " 积分";
                text = (names.length() > 0 ? names + " 已领取；" : "") + "当前剩余 " + fmt(remaining) + " 积分";
                if (failed > 0) text += "（" + failed + " 个账号失败）";
            } else if (failed > 0) {
                title = "自动签到失败";
                text = failed + " 个账号签到失败，请打开 App 查看详情";
            } else {
                title = "今日已签到";
                text = "所有账号今日均已签到，当前剩余 " + fmt(remaining) + " 积分";
            }
            Intent it = new Intent(ctx, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(ctx, 1, it,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                    ? new Notification.Builder(ctx, App.CHANNEL_ID)
                    : new Notification.Builder(ctx);
            Notification n = b
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(NOTIFY_ID, n);
        } catch (Exception ignored) {
        }
    }

    static String fmt(double v) {
        return (v == Math.floor(v)) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 10) / 10.0);
    }

    /** 兼容旧调用点：等价于强制重排下一次每日自动签到闹钟。 */
    public static void scheduleNext(Context ctx) {
        AutoScheduler.rescheduleForce(ctx);
    }
}
