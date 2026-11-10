package com.traecheckin.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PorterDuff;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 主界面：问候区 + Hero 卡 + 账号列表 + 底部 Tab。
 *
 * <p>全部原生实现（无 WebView / 无 JSBridge）：</p>
 * <ul>
 *   <li>账号列表用 LinearLayout 动态 addView（账号量级 &lt; 50，无需 RecyclerView）；</li>
 *   <li>一键签到用固定 3 线程池并发执行，每完成一个账号立即回主线程刷新该卡片；</li>
 *   <li>点击卡片弹原生 PopupMenu（立即签到 / 查看积分 / 删除），长按弹删除确认。</li>
 * </ul>
 */
public class MainActivity extends Activity {

    /** 版本号（设置页「关于」也会用到）。 */
    public static final String VERSION = "2.4.2";

    private static final int REQ_LOGIN = 2001;

    private static final int MENU_CHECKIN_NOW = 1;
    private static final int MENU_VIEW_CREDITS = 2;
    private static final int MENU_DELETE = 3;
    private static final int MENU_RELOGIN = 4;
    private static final int MENU_SET_DEVICE = 5;

    private static final String[] WEEK_CN = {
            "周日", "周一", "周二", "周三", "周四", "周五", "周六"
    };

    private AccountStore store;
    private ExecutorService executor;

    private LinearLayout accountList;
    private View emptyView;
    private View accountSection;
    private TextView tvDate;
    private TextView tvAccountCount;
    private TextView tvHeroStatus;
    private TextView tvDone;
    private TextView tvTotal;
    private TextView tvHeroSub;
    private TextView tvPercent;
    private ProgressBar ringProgress;
    private View btnCheckinAll;
    private TextView tvCheckinAll;

    /** 账号 id -> 列表行视图，用于签到过程中局部刷新。 */
    private final Map<String, View> rowById = new HashMap<String, View>();
    private boolean checkinRunning = false;
    private boolean firstOpenTriggered = false;
    private boolean destroyed = false;

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        store = AccountStore.get(this);
        executor = Executors.newFixedThreadPool(3);
        bindViews();
        applyStaticText();
        bindActions();
        // 每次进入 App 校准一次闹钟（开机 / 设置变更时也会校准）
        AutoScheduler.scheduleNext(this);
        maybePromptNotificationOnce();
        Anim.enter(findViewById(android.R.id.content));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 跨零点后日期要更新
        applyStaticText();
        loadAccounts();
        maybeFirstOpenCheckin();
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
        rowById.clear();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_LOGIN && resultCode == RESULT_OK) {
            loadAccounts();
        }
    }

    // ------------------------------------------------------------------
    // 初始化
    // ------------------------------------------------------------------

    private void bindViews() {
        accountList = (LinearLayout) findViewById(R.id.accountList);
        emptyView = findViewById(R.id.emptyView);
        accountSection = findViewById(R.id.accountSection);
        tvDate = (TextView) findViewById(R.id.tvDate);
        tvAccountCount = (TextView) findViewById(R.id.tvAccountCount);
        tvHeroStatus = (TextView) findViewById(R.id.tvHeroStatus);
        tvDone = (TextView) findViewById(R.id.tvDone);
        tvTotal = (TextView) findViewById(R.id.tvTotal);
        tvHeroSub = (TextView) findViewById(R.id.tvHeroSub);
        tvPercent = (TextView) findViewById(R.id.tvPercent);
        ringProgress = (ProgressBar) findViewById(R.id.ringProgress);
        btnCheckinAll = findViewById(R.id.btnCheckinAll);
        tvCheckinAll = (TextView) findViewById(R.id.tvCheckinAll);
    }

    /** 日期问候：11月9日 周日。 */
    private void applyStaticText() {
        Calendar now = Calendar.getInstance();
        String label = new SimpleDateFormat("M月d日", Locale.CHINA).format(new Date());
        tvDate.setText(label + " " + WEEK_CN[now.get(Calendar.DAY_OF_WEEK) - 1]);
    }

    private void bindActions() {
        findViewById(R.id.btnAdd).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openLogin();
            }
        });

        View btnAddEmpty = emptyView.findViewById(R.id.btnAddAccountEmpty);
        if (btnAddEmpty != null) {
            btnAddEmpty.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openLogin();
                }
            });
        }

        btnCheckinAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startCheckinAll(false);
            }
        });

        // 底部导航统一绑定（淡紫选中胶囊 + 切换动画）
        NavHelper.bind(this, NavHelper.TAB_CHECKIN);
    }

    private void openLogin() {
        startActivityForResult(new Intent(this, LoginActivity.class), REQ_LOGIN);
    }

    /** 给已有账号重新登录（复用旧 deviceId，只替换 token）。 */
    private void openRelogin(AccountStore.Account a) {
        if (a == null) return;
        Intent intent = new Intent(this, LoginActivity.class);
        intent.putExtra(LoginActivity.EXTRA_REPLACE_ID, a.id);
        startActivityForResult(intent, REQ_LOGIN);
    }

    /**
     * 首次启动且通知权限未授予时，只提示一次。
     *
     * <p>绝不在这里 requestPermissions、也绝不写 {@code Prefs.setNotifyEnabled} ——
     * 否则会把用户在设置页主动关掉的通知开关静默改回开。系统权限框只由设置页
     * 里用户主动打开开关时申请。</p>
     */
    private void maybePromptNotificationOnce() {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            if (Prefs.notifyPromptShown(this)) return;
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED) {
                return;
            }
            Prefs.setNotifyPromptShown(this, true);
            toast(getString(R.string.notify_prompt_once));
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 账号列表
    // ------------------------------------------------------------------

    /** 读取账号并整体重建列表、Hero 卡与空状态。 */
    /** 读取账号并整体重建列表、Hero 卡与空状态；任何异常都降级为空列表，绝不崩。 */
    private void loadAccounts() {
        try {
            loadAccountsInner();
        } catch (Throwable t) {
            try {
                accountList.removeAllViews();
                rowById.clear();
                updateHero(0, 0);
                tvAccountCount.setText("");
                accountSection.setVisibility(View.GONE);
                accountList.setVisibility(View.GONE);
                emptyView.setVisibility(View.VISIBLE);
            } catch (Throwable ignored) {
            }
            toast(getString(R.string.data_load_failed));
        }
    }

    private void loadAccountsInner() {
        final String today = AccountStore.today();
        List<AccountStore.Account> accounts = store.accounts();

        accountList.removeAllViews();
        rowById.clear();

        LayoutInflater inflater = LayoutInflater.from(this);
        int gap = getResources().getDimensionPixelSize(R.dimen.card_account_gap);
        int done = 0;
        for (int i = 0; i < accounts.size(); i++) {
            final AccountStore.Account a = accounts.get(i);
            View row = inflater.inflate(R.layout.item_account, accountList, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.topMargin = gap;
            row.setLayoutParams(lp);

            bindAccountRow(row, a, today);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showAccountMenu(a, v);
                }
            });
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    confirmDelete(a);
                    return true;
                }
            });

            accountList.addView(row);
            rowById.put(a.id, row);
            if (isCheckedInToday(a, today)) done++;
        }

        updateHero(done, accounts.size());

        boolean empty = accounts.isEmpty();
        tvAccountCount.setText(empty ? "" : getString(R.string.account_count_format, accounts.size()));
        accountSection.setVisibility(empty ? View.GONE : View.VISIBLE);
        accountList.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    /** 单个账号卡片的状态渲染。 */
    private void bindAccountRow(View row, AccountStore.Account a, String today) {
        TextView tvAvatar = (TextView) row.findViewById(R.id.tvAvatar);
        TextView tvAlias = (TextView) row.findViewById(R.id.tvAlias);
        TextView tvStatus = (TextView) row.findViewById(R.id.tvAccountStatus);
        TextView tvBadge = (TextView) row.findViewById(R.id.tvBadge);

        String name = AccountStore.displayName(a);
        tvAlias.setText(name);
        tvAvatar.setText(initial(name));

        boolean done = isCheckedInToday(a, today);
        if (done) {
            tvBadge.setText(R.string.status_done);
            tvBadge.setBackgroundResource(R.drawable.bg_badge_ok);
            tvBadge.setTextColor(getColorCompat(R.color.ok_deep));
            tvStatus.setText(a.todayCredits > 0
                    ? getString(R.string.account_sub_checked_fmt, formatCredits(a.todayCredits))
                    : getString(R.string.account_sub_checked));
        } else if (!a.tokenValid) {
            tvBadge.setText(R.string.status_failed);
            tvBadge.setBackgroundResource(R.drawable.bg_badge_gray);
            tvBadge.setTextColor(getColorCompat(R.color.ink_2));
            // 优先展示真实原因（如 9004「需先在电脑端登录一次」），没有原因才用通用文案。
            // 否则用户会照着「重新登录」一路重登，永远修不好 9004。
            String sub = (a.lastMessage != null && a.lastMessage.length() > 0)
                    ? a.lastMessage
                    : getString(R.string.status_invalid_action);
            tvStatus.setText(sub);
        } else {
            tvBadge.setText(R.string.status_pending);
            tvBadge.setBackgroundResource(R.drawable.bg_badge_warn);
            tvBadge.setTextColor(getColorCompat(R.color.warn_deep));
            tvStatus.setText(hasText(a.lastMessage) ? a.lastMessage : getString(R.string.account_sub_pending));
        }
        if (Prefs.isExcluded(row.getContext(), a.id)) {
            tvStatus.setText(tvStatus.getText().toString() + " · "
                    + getString(R.string.account_excluded));
        }
    }

    /** Hero 卡：大数字、圆环百分比、状态胶囊。 */
    private void updateHero(int done, int total) {
        tvDone.setText(String.valueOf(done));
        tvTotal.setText(String.valueOf(total));

        int percent = total <= 0 ? 0 : Math.round(done * 100f / total);
        ringProgress.setProgress(percent);
        tvPercent.setText(percent + "%");

        boolean allDone = total > 0 && done >= total;
        if (allDone) {
            tvHeroStatus.setText(R.string.status_completed_today);
            tvHeroStatus.setBackgroundResource(R.drawable.bg_badge_ok);
            tvHeroStatus.setTextColor(getColorCompat(R.color.ok_deep));
        } else {
            tvHeroStatus.setText(R.string.status_pending);
            tvHeroStatus.setBackgroundResource(R.drawable.bg_badge_warn);
            tvHeroStatus.setTextColor(getColorCompat(R.color.warn_deep));
        }
        tvHeroSub.setText(R.string.hero_sub_default);
    }

    /** 局部刷新：重绘某一行，并同步 Hero 卡数字。 */
    private void updateAccountRow(AccountStore.Account a) {
        if (a == null) return;
        View row = rowById.get(a.id);
        if (row != null) bindAccountRow(row, a, AccountStore.today());
        updateHero(countDone(store.accounts(), AccountStore.today()), store.accounts().size());
    }

    private static int countDone(List<AccountStore.Account> accounts, String today) {
        int done = 0;
        for (AccountStore.Account a : accounts) {
            if (isCheckedInToday(a, today)) done++;
        }
        return done;
    }

    // ------------------------------------------------------------------
    // 一键签到
    // ------------------------------------------------------------------

    /**
     * 一键签到。
     *
     * @param firstOpen true 表示由「首次打开自动签到」触发（不提示「今天都签过了」）
     */
    private void startCheckinAll(final boolean firstOpen) {
        if (checkinRunning) return;
        final List<AccountStore.Account> all = store.accounts();
        if (all.isEmpty()) {
            if (!firstOpen) toast(getString(R.string.checkin_need_account));
            return;
        }
        // 「管理签到时间与排除账号」里勾选排除的账号不参与一键 / 首开签到
        final List<AccountStore.Account> accounts = new ArrayList<AccountStore.Account>();
        for (AccountStore.Account a : all) {
            if (!Prefs.isExcluded(this, a.id)) accounts.add(a);
        }
        if (accounts.isEmpty()) {
            if (!firstOpen) toast(getString(R.string.checkin_all_excluded));
            return;
        }
        if (!firstOpen) {
            String today = AccountStore.today();
            boolean anyPending = false;
            for (AccountStore.Account a : accounts) {
                if (!isCheckedInToday(a, today)) {
                    anyPending = true;
                    break;
                }
            }
            if (!anyPending) {
                toast(getString(R.string.checkin_all_done));
                return;
            }
        }

        setCheckinBusy(true, firstOpen);
        final AtomicInteger ok = new AtomicInteger(0);
        final AtomicInteger fail = new AtomicInteger(0);
        final AtomicInteger left = new AtomicInteger(accounts.size());

        for (final AccountStore.Account a : accounts) {
            final ExecutorService ex = executor;
            if (ex == null) {
                setCheckinBusy(false, firstOpen);
                return;
            }
            ex.execute(new Runnable() {
                @Override
                public void run() {
                    JSONObject r = null;
                    try {
                        r = CheckinEngine.checkinAccount(getApplicationContext(), a, true, false);
                    } catch (Throwable ignored) {
                    }
                    if (r != null && r.optBoolean("ok", false)) {
                        ok.incrementAndGet();
                    } else {
                        fail.incrementAndGet();
                    }
                    AccountStore.Account fresh = store.byId(a.id);
                    final AccountStore.Account shown = fresh == null ? a : fresh;

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed) return;
                            updateAccountRow(shown);
                        }
                    });

                    if (left.decrementAndGet() == 0) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (destroyed) return;
                                finishCheckinAll(ok.get(), fail.get(), firstOpen);
                            }
                        });
                    }
                }
            });
        }
    }

    /** 单账号签到（弹窗「立即签到」）。 */
    private void startCheckinSingle(final AccountStore.Account a) {
        if (checkinRunning) {
            toast(getString(R.string.checkin_running));
            return;
        }
        final ExecutorService ex = executor;
        if (ex == null) return;
        setCheckinBusy(true, false);
        ex.execute(new Runnable() {
            @Override
            public void run() {
                JSONObject r = null;
                try {
                    r = CheckinEngine.checkinAccount(getApplicationContext(), a, true, false);
                } catch (Throwable ignored) {
                }
                final boolean success = r != null && r.optBoolean("ok", false);
                final String msg = r == null ? "" : r.optString("message", "");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed) return;
                        setCheckinBusy(false, false);
                        loadAccounts();
                        toast(success ? getString(R.string.toast_single_ok)
                                : getString(R.string.toast_single_fail,
                                hasText(msg) ? msg : getString(R.string.status_failed)));
                    }
                });
            }
        });
    }

    private void finishCheckinAll(int ok, int fail, boolean firstOpen) {
        setCheckinBusy(false, firstOpen);
        loadAccounts();
        if (firstOpen) {
            if (ok > 0) {
                Prefs.setLastOpenCheckinDate(this, AccountStore.today());
            }
            return;
        }
        toast(getString(R.string.checkin_summary_fmt, ok, fail));
    }

    private void setCheckinBusy(boolean busy, boolean firstOpen) {
        checkinRunning = busy;
        if (btnCheckinAll != null) {
            btnCheckinAll.setEnabled(!busy);
            btnCheckinAll.setAlpha(busy ? 0.6f : 1f);
        }
        if (tvCheckinAll != null) {
            if (busy) {
                tvCheckinAll.setText(firstOpen
                        ? R.string.checkin_first_open_running : R.string.checkin_running);
            } else {
                tvCheckinAll.setText(R.string.checkin_all);
            }
        }
    }

    /** 每日首次打开自动签到。 */
    private void maybeFirstOpenCheckin() {
        if (firstOpenTriggered) return;
        if (!Prefs.firstOpenCheckinEnabled(this)) return;
        if (store.accounts().isEmpty()) return;
        String today = AccountStore.today();
        if (today.equals(Prefs.lastOpenCheckinDate(this))) return;
        firstOpenTriggered = true;
        startCheckinAll(true);
    }

    // ------------------------------------------------------------------
    // 账号菜单 / 删除
    // ------------------------------------------------------------------

    private void showAccountMenu(final AccountStore.Account a, View anchor) {
        try {
            PopupMenu menu = new PopupMenu(this, anchor);
            menu.getMenu().add(0, MENU_CHECKIN_NOW, 0, R.string.menu_checkin_now);
            menu.getMenu().add(0, MENU_VIEW_CREDITS, 1, R.string.menu_view_credits);
            menu.getMenu().add(0, MENU_RELOGIN, 2, R.string.menu_relogin);
            menu.getMenu().add(0, MENU_SET_DEVICE, 3, R.string.menu_set_device);
            menu.getMenu().add(0, MENU_DELETE, 4, R.string.menu_delete_account);
            menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(android.view.MenuItem item) {
                    switch (item.getItemId()) {
                        case MENU_CHECKIN_NOW:
                            startCheckinSingle(a);
                            return true;
                        case MENU_VIEW_CREDITS:
                            startActivity(new Intent(MainActivity.this, CreditsActivity.class));
                            return true;
                        case MENU_RELOGIN:
                            openRelogin(a);
                            return true;
                        case MENU_SET_DEVICE:
                            showDeviceDialog(a);
                            return true;
                        case MENU_DELETE:
                            confirmDelete(a);
                            return true;
                        default:
                            return false;
                    }
                }
            });
            menu.show();
        } catch (Throwable t) {
            // 极端 ROM 上 PopupMenu 可能异常，退化为直接删除确认
            confirmDelete(a);
        }
    }

    /**
     * 「设置设备号」对话框（9074 兜底入口）。
     *
     * <p>用户可填 12~20 位纯数字，或直接粘贴电脑版 TRAE 的 storage.json 整段文本，
     * 由 {@link AccountStore#extractDeviceId(String)} 从键名提取。写入前校验形态与撞号
     * （同一设备一天只能签一个账号，撞号会回 9095）。</p>
     */
    private void showDeviceDialog(final AccountStore.Account a) {
        if (a == null) return;
        try {
            final String current = a.deviceId == null ? "" : a.deviceId;
            final String src = getString(a.deviceIdManual
                    ? R.string.device_src_manual : R.string.device_src_auto);
            String currentText = current.length() > 0
                    ? current : getString(R.string.placeholder_empty);
            String msg = getString(R.string.device_dialog_msg) + "\n\n"
                    + getString(R.string.device_current_fmt, currentText, src);

            final EditText input = new EditText(this);
            input.setText(current);
            input.setSelection(input.getText().length());
            input.setHint(R.string.device_dialog_hint);
            input.setSingleLine(false);
            input.setMaxLines(6);
            int pad = dp(16);
            input.setPadding(pad, pad / 2, pad, pad / 2);

            AlertDialog.Builder b = new AlertDialog.Builder(this)
                    .setTitle(R.string.device_dialog_title)
                    .setMessage(msg)
                    .setView(input)
                    .setNegativeButton(R.string.action_cancel, null)
                    .setNeutralButton(R.string.device_action_reset,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int which) {
                                    java.util.List<String> used = new java.util.ArrayList<String>();
                                    for (AccountStore.Account o : store.accounts()) {
                                        if (o != null && !o.id.equals(a.id)) used.add(o.deviceId);
                                    }
                                    String fresh = AccountStore.newDeviceId(used);
                                    if (store.setDeviceId(a.id, fresh, false)) {
                                        loadAccounts();
                                        toast(getString(R.string.device_reset_done));
                                    }
                                }
                            })
                    .setPositiveButton(R.string.device_action_save,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int which) {
                                    String raw = input.getText() == null
                                            ? "" : input.getText().toString();
                                    String id = AccountStore.extractDeviceId(raw);
                                    if (id.length() == 0) {
                                        toast(getString(R.string.device_extract_failed));
                                        return;
                                    }
                                    if (store.findByDeviceId(id, a.id) != null) {
                                        toast(getString(R.string.device_duplicate));
                                        return;
                                    }
                                    if (!store.setDeviceId(a.id, id, true)) {
                                        toast(getString(R.string.device_invalid));
                                        return;
                                    }
                                    loadAccounts();
                                    toast(getString(R.string.device_saved));
                                }
                            });
            b.show();
        } catch (Throwable t) {
            toast(getString(R.string.device_invalid));
        }
    }

    private void confirmDelete(final AccountStore.Account a) {
        try {
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_delete_title)
                    .setMessage(getString(R.string.dialog_delete_msg, AccountStore.displayName(a)))
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.action_delete, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            boolean removed = store.delete(a.id);
                            loadAccounts();
                            if (removed) toast(getString(R.string.toast_account_deleted));
                        }
                    })
                    .create();
            dialog.show();
            try {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getColorCompat(R.color.err));
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static boolean isCheckedInToday(AccountStore.Account a, String today) {
        return a != null && a.checkedIn && today != null && today.equals(a.lastCheckinDate);
    }

    private static boolean hasText(String s) {
        return s != null && s.trim().length() > 0;
    }

    /** 头像首字母：中文取首字，英文取首字母大写。 */
    private static String initial(String name) {
        if (name == null) return "T";
        String s = name.trim();
        if (s.length() == 0) return "T";
        return s.substring(0, 1).toUpperCase(Locale.CHINA);
    }

    /** 积分数值格式化：整数不带小数位。 */
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
        if (Build.VERSION.SDK_INT >= 23) {
            return getColor(resId);
        }
        return getResources().getColor(resId);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
