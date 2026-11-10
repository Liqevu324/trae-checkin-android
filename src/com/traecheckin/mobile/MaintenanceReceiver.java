package com.traecheckin.mobile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

/**
 * 每日 03:30 凭证保活：对所有账号换新一次登录凭证，避免到点签到时 token 过期。
 *
 * <p>只做「换 token」，不发起任何签到请求 —— 保活与领取是两件独立的事。
 * 执行完调用 {@link AutoScheduler#rescheduleKeepAlive(Context)} 排下一天，
 * 否则这个闹钟只会在第一次触发。被排除的账号同样跳过。</p>
 */
public class MaintenanceReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        PowerManager.WakeLock wl = null;
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "traecheckin:keepalive");
                wl.acquire(3 * 60 * 1000L);
            }
        } catch (Exception ignored) {
        }
        final PowerManager.WakeLock lock = wl;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    AccountStore store = AccountStore.get(app);
                    int ok = 0;
                    int failed = 0;
                    for (AccountStore.Account a : store.accounts()) {
                        if (Prefs.isExcluded(app, a.id)) continue;
                        try {
                            if (CheckinEngine.ensureFreshToken(store, a)) ok++;
                            else failed++;
                        } catch (Throwable ignored) {
                            failed++;
                        }
                    }
                    Prefs.setLastKeepAliveDate(app, AccountStore.today());
                    if (ok == 0 && failed > 0 && Notice.enabled(app)) {
                        Notice.show(app, Notice.ID_REMINDER,
                                "登录凭证保活失败",
                                failed + " 个账号未能刷新凭证，请打开 App 重新登录");
                    }
                } catch (Throwable ignored) {
                } finally {
                    try {
                        AutoScheduler.rescheduleKeepAlive(app);
                    } catch (Throwable ignored) {
                    }
                    if (lock != null && lock.isHeld()) {
                        try {
                            lock.release();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }).start();
    }
}
