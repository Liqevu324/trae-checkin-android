package com.traecheckin.mobile;

import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** 轻量进入动画：内容淡入 + 轻微上移，用于页面切换时的「丝滑」观感。 */
public final class Anim {

    private Anim() {
    }

    /** 对整个页面根视图做一次 260ms 的浮入。 */
    public static void enter(View v) {
        if (v == null) return;
        float dy = 14f * v.getContext().getResources().getDisplayMetrics().density;
        v.setAlpha(0f);
        v.setTranslationY(dy);
        v.animate().alpha(1f).translationY(0f)
                .setDuration(260)
                .setInterpolator(new DecelerateInterpolator(1.4f))
                .start();
    }

    /** 一组卡片依次浮入（每项延迟 28ms），用于设置页分组。 */
    public static void stagger(View... views) {
        if (views == null) return;
        int delay = 0;
        for (View v : views) {
            if (v == null) continue;
            float dy = 12f * v.getContext().getResources().getDisplayMetrics().density;
            v.setAlpha(0f);
            v.setTranslationY(dy);
            v.animate().alpha(1f).translationY(0f)
                    .setDuration(240)
                    .setStartDelay(delay)
                    .setInterpolator(new DecelerateInterpolator(1.4f))
                    .start();
            delay += 28;
        }
    }
}
