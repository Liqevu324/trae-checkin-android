package com.traecheckin.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 签到日志页：读取 {@link AccountStore#history()}（time / account_id / alias / success /
 * message / code / credits / remaining），按天分组展示，最新在前。
 */
public class LogsActivity extends Activity {

    private static final String[] WEEK_CN = {
            "周日", "周一", "周二", "周三", "周四", "周五", "周六"
    };

    private AccountStore store;

    private LinearLayout logsList;
    private View contentScroll;
    private View emptyView;
    private TextView tvEmptyMsg;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logs);
        store = AccountStore.get(this);

        logsList = (LinearLayout) findViewById(R.id.logsList);
        contentScroll = findViewById(R.id.contentScroll);
        emptyView = findViewById(R.id.emptyView);
        tvEmptyMsg = (TextView) findViewById(R.id.tvEmptyMsg);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btnClear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClear();
            }
        });

        bindTabs();
        Anim.enter(findViewById(android.R.id.content));
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    // ------------------------------------------------------------------
    // 底部 Tab
    // ------------------------------------------------------------------

    private void bindTabs() {
        NavHelper.bind(this, NavHelper.TAB_LOGS);
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    private void render() {
        JSONArray history = store.history();
        logsList.removeAllViews();

        if (history.length() == 0) {
            showEmpty(true);
            return;
        }
        showEmpty(false);

        LayoutInflater inflater = LayoutInflater.from(this);
        String currentDay = null;
        LinearLayout currentCard = null;

        for (int i = 0; i < history.length(); i++) {
            JSONObject item = history.optJSONObject(i);
            if (item == null) continue;
            long time = item.optLong("time", 0L);
            if (time <= 0L) continue;

            String day = dayKey(time);
            if (!day.equals(currentDay)) {
                currentDay = day;
                logsList.addView(dayHeader(time));
                currentCard = newCard();
                logsList.addView(currentCard);
            }
            if (currentCard == null) continue;
            if (currentCard.getChildCount() > 0) {
                currentCard.addView(divider());
            }
            currentCard.addView(logRow(inflater, item));
        }
    }

    private void showEmpty(boolean empty) {
        if (emptyView != null) emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (contentScroll != null) contentScroll.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (tvEmptyMsg != null) tvEmptyMsg.setText(R.string.logs_empty);
    }

    /** 日期分组标题：今天 · 11月9日 / 昨天 · 11月8日 / 11月7日 周五。 */
    private TextView dayHeader(long time) {
        TextView tv = new TextView(this);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(getColorCompat(R.color.ink_2));
        tv.setPadding(0, dp(16), 0, dp(8));
        tv.setText(dayLabel(time));
        return tv;
    }

    private String dayLabel(long time) {
        String key = dayKey(time);
        String today = dayKey(System.currentTimeMillis());
        String yesterday = dayKey(System.currentTimeMillis() - 24L * 60L * 60L * 1000L);
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(time);
        String md = new SimpleDateFormat("M月d日", Locale.CHINA).format(new Date(time));
        if (key.equals(today)) {
            return getString(R.string.logs_today) + " · " + md;
        }
        if (key.equals(yesterday)) {
            return getString(R.string.logs_yesterday) + " · " + md;
        }
        return md + " " + WEEK_CN[c.get(Calendar.DAY_OF_WEEK) - 1];
    }

    private static String dayKey(long time) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date(time));
    }

    /** 每天一个白卡容器。 */
    private LinearLayout newCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setElevation(dp(1));
        card.setPadding(dp(14), dp(4), dp(14), dp(4));
        return card;
    }

    private View divider() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        v.setBackgroundColor(getColorCompat(R.color.line_2));
        return v;
    }

    private View logRow(LayoutInflater inflater, JSONObject item) {
        View row = inflater.inflate(R.layout.item_log, logsList, false);

        View dot = row.findViewById(R.id.dotStatus);
        TextView tvName = (TextView) row.findViewById(R.id.tvLogName);
        TextView tvResult = (TextView) row.findViewById(R.id.tvLogResult);
        TextView tvTime = (TextView) row.findViewById(R.id.tvLogTime);

        String alias = item.optString("alias", "");
        if (alias == null || alias.trim().length() == 0) {
            alias = getString(R.string.account_default_name);
        }
        tvName.setText(alias);

        double credits = item.optDouble("credits", 0d);
        double remaining = item.optDouble("remaining", -1d);
        String remainingText = remaining >= 0 ? formatCredits(remaining)
                : getString(R.string.placeholder_empty);

        boolean success = item.optBoolean("success", true);
        if (!success) {
            String message = item.optString("message", "");
            if (message.trim().length() == 0) message = getString(R.string.log_failure_unknown);
            String result = getString(R.string.log_failure_fmt, message);
            int code = item.optInt("code", 0);
            if (code != 0 && !message.contains("code=")) {
                result += getString(R.string.log_failure_code_fmt, code);
            }
            tvResult.setText(result);
            if (dot != null) dot.setBackgroundResource(R.drawable.dot_warn);
        } else if (credits > 0) {
            tvResult.setText(getString(R.string.log_credits_fmt, formatCredits(credits), remainingText));
            if (dot != null) dot.setBackgroundResource(R.drawable.dot_ok);
        } else if (remaining >= 0 || item.has("credits")) {
            tvResult.setText(getString(R.string.log_zero_fmt, remainingText));
            if (dot != null) dot.setBackgroundResource(R.drawable.dot_warn);
        } else {
            tvResult.setText(getString(R.string.log_zero_fmt, remainingText));
            if (dot != null) dot.setBackgroundResource(R.drawable.dot_gray);
        }

        long time = item.optLong("time", 0L);
        tvTime.setText(new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(time)));

        final String copyText = buildLogCopyText(item, alias, tvResult.getText().toString(), time);
        row.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                copyLog(copyText);
                return true;
            }
        });
        return row;
    }

    /** 复制完整但脱敏的单条日志，便于反馈被界面省略号遮住的服务端错误码。 */
    private String buildLogCopyText(JSONObject item, String alias, String result, long time) {
        StringBuilder text = new StringBuilder();
        text.append("TraeCheckin log\n");
        text.append("app_version=").append(MainActivity.VERSION).append('\n');
        text.append("account=").append(alias).append('\n');
        text.append("time=").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                .format(new Date(time))).append('\n');
        text.append("result=").append(result).append('\n');
        text.append("code=").append(item.optInt("code", 0)).append('\n');
        String errorType = item.optString("error_type", "");
        if (errorType.length() == 0 && NetHelper.isNetworkMessage(item.optString("message", ""))) {
            errorType = "network";
        }
        if (errorType.length() > 0) {
            text.append("error_type=").append(errorType).append('\n');
            if ("network".equals(errorType) && item.optInt("code", 0) == 0) {
                text.append("code_note=网络失败时没有上游业务码，0 不代表签到成功\n");
            }
        }
        double remaining = item.optDouble("remaining", -1d);
        text.append("remaining=").append(remaining >= 0 ? formatCredits(remaining)
                : getString(R.string.placeholder_empty));
        // 9074 是设备号校验，排障必须看到号、来源与请求契约。
        // 这些字段由 CheckinEngine 写入，绝不含 token / refreshToken / cookie / 原始响应体。
        String deviceId = item.optString("device_id", "");
        if (deviceId.length() > 0) {
            text.append('\n').append("device_id=").append(deviceId);
            text.append('\n').append("device_src=").append(item.optString("device_src", "auto"));
            text.append('\n').append("device_rotated=")
                    .append(item.optBoolean("device_rotated", false));
            text.append('\n').append("req_source=").append(item.optInt("req_source", 2));
        }
        return text.toString();
    }

    private void copyLog(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                toast(getString(R.string.log_copy_failed));
                return;
            }
            cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.logs_title), text));
            toast(getString(R.string.log_copy_done));
        } catch (Throwable t) {
            toast(getString(R.string.log_copy_failed));
        }
    }

    // ------------------------------------------------------------------
    // 清空
    // ------------------------------------------------------------------

    private void confirmClear() {
        try {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.logs_clear_title)
                    .setMessage(R.string.logs_clear_msg)
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.action_delete, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            store.clearHistory();
                            render();
                            toast(getString(R.string.logs_cleared));
                        }
                    })
                    .show();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static String formatCredits(double v) {
        if (v == Math.floor(v)) return String.valueOf((long) v);
        return String.valueOf(Math.round(v * 10) / 10.0);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
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
