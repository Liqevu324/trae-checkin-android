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
        // 让系统在后台网络请求结束前保持进程；旧版裸线程可能在 onReceive 返回后被回收。
        final PendingResult pendingResult = goAsync();
        final boolean isRetry = intent != null
                && AutoScheduler.ACTION_NETWORK_RETRY.equals(intent.getAction());
        final int retryCount = isRetry
                ? intent.getIntExtra(AutoScheduler.EXTRA_RETRY_COUNT, 0) : 0;
        PowerManager.WakeLock wl = null;
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "traecheckin:auto");
                // 多账号 + 有限重试的最坏耗时可能超过 10 分钟，留足余量；结束时 finally 释放。
                wl.acquire(20 * 60 * 1000L);
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
                    int claimed = 0, failed = 0, total = 0, deferred = 0;
                    boolean networkFailed = false;
                    boolean networkBroken = false;
                    boolean retryScheduled = false;
                    double gained = 0, remaining = 0;
                    StringBuilder okNames = new StringBuilder();
                    for (AccountStore.Account a : accounts) {
                        // 「管理签到时间与排除账号」里排除的账号不参与自动签到
                        if (Prefs.isExcluded(app, a.id)) continue;
                        total++;
                        // 第一个账号就判定网络不可用时，不再让后面每个账号各等一轮超时：
                        // 剩余账号交给补签闹钟统一处理（补签会先读 status，已签到不会重复领）。
                        if (networkBroken) {
                            deferred++;
                            continue;
                        }
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
                                if (NetHelper.isNetworkError(r)) {
                                    networkFailed = true;
                                    networkBroken = true;
                                }
                            }
                        } catch (Exception e) {
                            failed++;
                        }
                    }
                    if (networkFailed) {
                        retryScheduled = AutoScheduler.scheduleNetworkRetry(app, retryCount + 1);
                    } else {
                        // 网络恢复后无论业务成功还是业务失败，都停止网络补签，避免无意义循环。
                        AutoScheduler.cancelNetworkRetry(app);
                    }
                    if (total > 0) {
                        notifyResult(app, claimed, failed, deferred, gained, remaining,
                                okNames.toString(), networkFailed, retryScheduled, retryCount + 1);
                    }
                } catch (Exception ignored) {
                } finally {
                    try {
                        if (!isRetry) {
                            // 正常每日闹钟执行完强制排下一天；补签闹钟使用独立请求码，不覆盖它。
                            AutoScheduler.rescheduleForce(app);
                        }
                    } catch (Exception ignored) {
                    }
                    if (lock != null && lock.isHeld()) lock.release();
                    pendingResult.finish();
                }
            }
        }.start();
    }

    private void notifyResult(Context ctx, int claimed, int failed, int deferred,
                              double gained, double remaining, String names,
                              boolean networkFailed, boolean retryScheduled, int retryCount) {
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
                if (deferred > 0) text += "（" + deferred + " 个账号待补签）";
            } else if (failed > 0 || deferred > 0) {
                title = networkFailed ? "网络异常，签到待补" : "自动签到失败";
                if (networkFailed) {
                    int pending = failed + deferred;
                    text = pending + " 个账号因网络异常未完成";
                } else {
                    text = failed + " 个账号签到失败，请打开 App 查看详情";
                }
            } else {
                title = "今日已签到";
                text = "所有账号今日均已签到，当前剩余 " + fmt(remaining) + " 积分";
            }
            if (networkFailed) {
                if (retryScheduled) {
                    text += "；" + AutoScheduler.networkRetryDelayLabel(retryCount)
                            + "后自动补签（第 " + retryCount + "/"
                            + AutoScheduler.MAX_NETWORK_RETRIES + " 次）";
                } else {
                    text += "；已达补签上限，请联网后打开 App 或手动签到";
                }
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
