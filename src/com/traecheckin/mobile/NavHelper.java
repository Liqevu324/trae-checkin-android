package com.traecheckin.mobile;

import android.app.Activity;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.os.Build;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * 底部导航统一绑定：淡紫选中胶囊 + 图标着色 + 切换跳转 + 选中态缩放淡入动画。
 *
 * <p>四个页面（签到 / 积分 / 日志 / 设置）都调用 {@link #bind(Activity, int)}，
 * 不再各自维护 setNavItem，避免高亮状态与点击行为不一致。Activity 级转场动画
 * 由主题 {@code PageAnimation} 统一提供。</p>
 */
public final class NavHelper {

    public static final int TAB_CHECKIN = 0;
    public static final int TAB_CREDITS = 1;
    public static final int TAB_LOGS = 2;
    public static final int TAB_SETTINGS = 3;

    private static final int[] NAVS = {
            R.id.navCheckin, R.id.navCredits, R.id.navLogs, R.id.navSettings
    };
    private static final int[] PILLS = {
            R.id.pillCheckin, R.id.pillCredits, R.id.pillLogs, R.id.pillSettings
    };
    private static final int[] ICONS = {
            R.id.ivNavCheckin, R.id.ivNavCredits, R.id.ivNavLogs, R.id.ivNavSettings
    };
    private static final int[] LABELS = {
            R.id.tvNavCheckin, R.id.tvNavCredits, R.id.tvNavLogs, R.id.tvNavSettings
    };
    private static final Class<?>[] TARGETS = {
            MainActivity.class, CreditsActivity.class, LogsActivity.class, SettingsActivity.class
    };

    private NavHelper() {
    }

    public static void bind(final Activity act, int active) {
        for (int i = 0; i < NAVS.length; i++) {
            final int idx = i;
            View nav = act.findViewById(NAVS[i]);
            View pill = act.findViewById(PILLS[i]);
            if (nav == null || pill == null) continue;  // 该页没有底部导航

            final boolean on = (i == active);
            if (on) {
                pill.setBackgroundResource(R.drawable.bg_tab_selected);
                pill.setAlpha(0f);
                pill.setScaleX(0.86f);
                pill.setScaleY(0.86f);
                pill.animate().alpha(1f).scaleX(1f).scaleY(1f)
                        .setDuration(220)
                        .setInterpolator(new DecelerateInterpolator(1.5f))
                        .start();
            } else {
                pill.setBackgroundResource(0);
                pill.setAlpha(1f);
                pill.setScaleX(1f);
                pill.setScaleY(1f);
            }
            tint(act, ICONS[i], LABELS[i], on);

            if (on) {
                nav.setOnClickListener(null);
                nav.setClickable(false);
            } else {
                nav.setClickable(true);
                nav.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        switchTo(act, idx);
                    }
                });
            }
        }
    }

    private static void tint(Activity act, int iconId, int labelId, boolean on) {
        int color = color(act, on ? R.color.brand_primary : R.color.ink_3);
        ImageView iv = (ImageView) act.findViewById(iconId);
        if (iv != null) iv.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        TextView tv = (TextView) act.findViewById(labelId);
        if (tv != null) tv.setTextColor(color);
    }

    private static void switchTo(Activity act, int idx) {
        if (idx < 0 || idx >= TARGETS.length) return;
        try {
            act.startActivity(new Intent(act, TARGETS[idx]));
            act.finish();
        } catch (Throwable ignored) {
        }
    }

    private static int color(Activity act, int resId) {
        if (Build.VERSION.SDK_INT >= 23) return act.getColor(resId);
        return act.getResources().getColor(resId);
    }
}
