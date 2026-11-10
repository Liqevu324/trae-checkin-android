package com.traecheckin.mobile;

import android.app.Activity;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 积分明细页：汇总所有账号的剩余积分，并逐账号列出。
 *
 * <p>数据来源：{@link TraeApi#entitlements} + {@link TraeApi#parseEntitlements}。
 * 单账号失败不影响其它账号，失败行显示「—」，汇总副标题标注「部分获取失败」。</p>
 */
public class CreditsActivity extends Activity {

    private AccountStore store;
    private ExecutorService executor;

    private LinearLayout creditsList;
    private View contentScroll;
    private View loadingView;
    private View emptyView;
    private TextView tvRemaining;
    private TextView tvAccountCount;
    private TextView tvEmptyMsg;
    private TextView btnRetry;

    private final Map<String, TextView> valueById = new HashMap<String, TextView>();
    private final Map<String, TextView> subById = new HashMap<String, TextView>();

    private boolean loading = false;
    private boolean destroyed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_credits);
        store = AccountStore.get(this);
        executor = Executors.newFixedThreadPool(3);

        creditsList = (LinearLayout) findViewById(R.id.creditsList);
        contentScroll = findViewById(R.id.contentScroll);
        loadingView = findViewById(R.id.loadingView);
        emptyView = findViewById(R.id.emptyView);
        tvRemaining = (TextView) findViewById(R.id.tvRemaining);
        tvAccountCount = (TextView) findViewById(R.id.tvAccountCount);
        tvEmptyMsg = (TextView) findViewById(R.id.tvEmptyMsg);
        btnRetry = (TextView) findViewById(R.id.btnRetry);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btnRefresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                load();
            }
        });
        btnRetry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                load();
            }
        });

        bindTabs();
        Anim.enter(findViewById(android.R.id.content));
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (executor != null) {
            try {
                executor.shutdownNow();
            } catch (Throwable ignored) {
            }
            executor = null;
        }
        valueById.clear();
        subById.clear();
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // 底部 Tab
    // ------------------------------------------------------------------

    private void bindTabs() {
        NavHelper.bind(this, NavHelper.TAB_CREDITS);
    }

    // ------------------------------------------------------------------
    // 数据加载
    // ------------------------------------------------------------------

    private void load() {
        if (loading || destroyed) return;
        final List<AccountStore.Account> accounts = store.accounts();

        creditsList.removeAllViews();
        valueById.clear();
        subById.clear();

        if (accounts.isEmpty()) {
            tvRemaining.setText(getString(R.string.placeholder_empty));
            tvAccountCount.setText(getString(R.string.credits_account_count_fmt, 0));
            showState(R.string.credits_empty, false);
            return;
        }

        // 先铺占位行，网络结果回来再逐行填
        LayoutInflater inflater = LayoutInflater.from(this);
        int gap = getResources().getDimensionPixelSize(R.dimen.card_account_gap);
        for (int i = 0; i < accounts.size(); i++) {
            AccountStore.Account a = accounts.get(i);
            View row = inflater.inflate(R.layout.item_credit, creditsList, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.topMargin = gap;
            row.setLayoutParams(lp);

            TextView tvAvatar = (TextView) row.findViewById(R.id.tvCreditAvatar);
            TextView tvName = (TextView) row.findViewById(R.id.tvCreditName);
            TextView tvSub = (TextView) row.findViewById(R.id.tvCreditSub);
            TextView tvValue = (TextView) row.findViewById(R.id.tvCreditValue);

            String name = AccountStore.displayName(a);
            tvName.setText(name);
            tvAvatar.setText(initial(name));
            tvSub.setText(R.string.credits_fail);
            tvValue.setText(getString(R.string.placeholder_empty));

            valueById.put(a.id, tvValue);
            subById.put(a.id, tvSub);
            creditsList.addView(row);
        }

        tvRemaining.setText(getString(R.string.placeholder_empty));
        tvAccountCount.setText(getString(R.string.credits_account_count_fmt, accounts.size()));
        showState(0, false);
        loading = true;
        if (loadingView != null) loadingView.setVisibility(View.VISIBLE);

        final AtomicInteger left = new AtomicInteger(accounts.size());
        final AtomicInteger success = new AtomicInteger(0);
        final AtomicInteger fail = new AtomicInteger(0);
        // 用「分」做原子累加（long），避免多线程 double 累加的可见性/精度问题
        final AtomicLong totalCents = new AtomicLong(0L);
        final AccountStore store = AccountStore.get(getApplicationContext());

        for (final AccountStore.Account a : accounts) {
            final ExecutorService ex = executor;
            if (ex == null) {
                loading = false;
                if (loadingView != null) loadingView.setVisibility(View.GONE);
                return;
            }
            ex.execute(new Runnable() {
                @Override
                public void run() {
                    double remaining = -1d;
                    try {
                        JSONObject resp = TraeApi.entitlements(a.token, a.deviceId);
                        double r = TraeApi.parseEntitlements(resp, null);
                        // 凭证过期（401/1001）：用 cookie 续期后重试一次，只重试一次
                        if (r < 0d && CheckinEngine.isAuthError(resp)
                                && CheckinEngine.ensureFreshToken(store, a)) {
                            resp = TraeApi.entitlements(a.token, a.deviceId);
                            r = TraeApi.parseEntitlements(resp, null);
                        }
                        remaining = r;
                    } catch (Throwable ignored) {
                    }
                    final double value = remaining;
                    if (value >= 0) {
                        success.incrementAndGet();
                        totalCents.addAndGet(Math.round(value * 100d));
                    } else {
                        fail.incrementAndGet();
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed) return;
                            bindRowValue(a.id, value);
                            if (left.decrementAndGet() == 0) {
                                finishLoading(success.get(), fail.get(),
                                        totalCents.get() / 100d, accounts.size());
                            }
                        }
                    });
                }
            });
        }
    }

    /** 把单个账号的积分结果写回对应行。 */
    private void bindRowValue(String accountId, double remaining) {
        TextView tvValue = valueById.get(accountId);
        TextView tvSub = subById.get(accountId);
        if (remaining >= 0) {
            if (tvValue != null) tvValue.setText(formatCredits(remaining));
            if (tvSub != null) tvSub.setText(R.string.credits_row_sub);
        } else {
            if (tvValue != null) tvValue.setText(getString(R.string.placeholder_empty));
            if (tvSub != null) tvSub.setText(R.string.credits_fail);
        }
    }

    private void finishLoading(int success, int fail, double total, int count) {
        loading = false;
        if (loadingView != null) loadingView.setVisibility(View.GONE);

        if (success == 0 && count > 0) {
            tvRemaining.setText(getString(R.string.placeholder_empty));
            tvAccountCount.setText(getString(R.string.credits_account_count_fmt, count));
            showState(R.string.credits_all_fail, true);
            return;
        }

        tvRemaining.setText(formatCredits(total));
        if (fail > 0) {
            tvAccountCount.setText(getString(R.string.credits_account_count_fail_fmt, count));
        } else {
            tvAccountCount.setText(getString(R.string.credits_account_count_fmt, count));
        }
        showState(0, false);
    }

    /**
     * 切换「内容 / 空状态」。
     *
     * @param msgRes 空状态文案；0 表示隐藏空状态
     * @param retry  是否显示「重试」按钮
     */
    private void showState(int msgRes, boolean retry) {
        boolean showEmpty = msgRes != 0;
        if (emptyView != null) emptyView.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        if (contentScroll != null) contentScroll.setVisibility(showEmpty ? View.GONE : View.VISIBLE);
        if (showEmpty && tvEmptyMsg != null) tvEmptyMsg.setText(msgRes);
        if (btnRetry != null) btnRetry.setVisibility(retry ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static String initial(String name) {
        if (name == null) return "T";
        String s = name.trim();
        if (s.length() == 0) return "T";
        return s.substring(0, 1).toUpperCase(Locale.CHINA);
    }

    private static String formatCredits(double v) {
        if (v == Math.floor(v)) return String.valueOf((long) v);
        return String.valueOf(Math.round(v * 10) / 10.0);
    }

    private void toast(String msg) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private int getColorCompat(int resId) {
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            return getColor(resId);
        }
        return getResources().getColor(resId);
    }
}
