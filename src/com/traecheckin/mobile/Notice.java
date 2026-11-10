package com.traecheckin.mobile;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * 系统通知统一出口（自动签到结果、凭证保活异常共用）。
 *
 * <p>所有调用都吞掉异常：通知属于增强功能，绝不能因为个别 ROM 的
 * NotificationManager 异常影响签到主流程。</p>
 */
public final class Notice {

    public static final int ID_CHECKIN = 7;
    public static final int ID_REMINDER = 8;

    private Notice() {
    }

    public static boolean enabled(Context ctx) {
        return Prefs.notifyEnabled(ctx);
    }

    public static void show(Context ctx, int id, String title, String text) {
        try {
            if (ctx == null) return;
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 33) {
                if (ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != PackageManager.PERMISSION_GRANTED) return;
            }
            Intent it = new Intent(ctx, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(ctx, id, it,
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
            nm.notify(id, n);
        } catch (Throwable ignored) {
        }
    }
}
