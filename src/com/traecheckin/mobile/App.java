package com.traecheckin.mobile;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

public class App extends Application {
    public static final String CHANNEL_ID = "checkin";

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "签到结果通知", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("每日自动签到结果与积分到账通知");
                nm.createNotificationChannel(ch);
            }
        }
    }
}
