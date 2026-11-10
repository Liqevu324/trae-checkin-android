package com.traecheckin.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.app.TimePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置页 v2.4.0：分组式设置 + 文档入口 + 关于与免责声明。
 *
 * <p>关键约束（对应历史报障）：</p>
 * <ul>
 *   <li>每个 Switch 都在 {@link #bindValues()} 里从 {@link Prefs} 读回真实值，绝不写死；</li>
 *   <li>切换即写 Prefs 并重排闹钟，权限被拒<b>不回滚</b>开关；</li>
 *   <li>所有 startActivity 都 try/catch，OPPO 等机型入口缺失时不闪退；</li>
 *   <li>所有新增开关都真实生效（凭证保活 / 排除账号 / 导入导出），不做假开关。</li>
 * </ul>
 */
public class SettingsActivity extends Activity {

    private static final int REQ_NOTIFY_PERMISSION = 100;

    /** 随机延迟可选值（分钟）。 */
    private static final int[] JITTER_OPTIONS = {0, 5, 10, 15, 30, 60};

    private Switch swAuto;
    private Switch swKeepAlive;
    private Switch swFirstOpen;
    private Switch swNotify;
    private TextView tvScheduleValue;
    private TextView tvJitterValue;
    private TextView tvVersion;
    private View noticeBar;
    private TextView tvNotice;

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        swAuto = (Switch) findViewById(R.id.swAuto);
        swKeepAlive = (Switch) findViewById(R.id.swKeepAlive);
        swFirstOpen = (Switch) findViewById(R.id.swFirstOpen);
        swNotify = (Switch) findViewById(R.id.swNotify);
        tvScheduleValue = (TextView) findViewById(R.id.tvScheduleValue);
        tvJitterValue = (TextView) findViewById(R.id.tvJitterValue);
        tvVersion = (TextView) findViewById(R.id.tvVersion);
        noticeBar = findViewById(R.id.noticeBar);
        tvNotice = (TextView) findViewById(R.id.tvNotice);

        noticeBar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAppNotificationSettings();
            }
        });

        NavHelper.bind(this, NavHelper.TAB_SETTINGS);
        Anim.enter(findViewById(android.R.id.content));
        bindValues();
        bindSwitchActions();
        bindRowActions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 用户可能刚从系统设置页改了权限，重进时必须重新读一次
        bindValues();
        // 被动校准：幂等，已有排程时不会改写（顺带确认凭证保活闹钟在队列里）
        AutoScheduler.scheduleNext(this);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_NOTIFY_PERMISSION) return;
        boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        updateNoticeBar();
        if (!granted) {
            // 开关保持开启（设置已存），只做提示 + 引导，绝不回滚用户的选择
            DialogHelper.show(this,
                    getString(R.string.setting_notify),
                    getString(R.string.setting_perm_denied_notify),
                    getString(R.string.action_cancel),
                    getString(R.string.setting_go_settings),
                    new Runnable() {
                        @Override
                        public void run() {
                            openAppNotificationSettings();
                        }
                    });
        }
    }

    // ------------------------------------------------------------------
    // 状态读取
    // ------------------------------------------------------------------

    /** 关键：所有控件状态从 Prefs 读回真实值。 */
    private void bindValues() {
        swAuto.setChecked(Prefs.autoEnabled(this));
        swKeepAlive.setChecked(Prefs.keepAliveEnabled(this));
        swFirstOpen.setChecked(Prefs.firstOpenCheckinEnabled(this));
        swNotify.setChecked(Prefs.notifyEnabled(this));

        tvScheduleValue.setText(scheduleLabel());
        tvJitterValue.setText(jitterValueLabel(Prefs.jitterMin(this)));
        tvVersion.setText(versionName());
        updateNoticeBar();
    }

    /** 形如「09:00 · 未排除任何账号」。 */
    private String scheduleLabel() {
        List<Prefs.TimePoint> times = Prefs.times(this);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times.size(); i++) {
            if (i > 0) sb.append("、");
            sb.append(times.get(i).format());
        }
        String time = sb.length() == 0 ? "09:00" : sb.toString();
        int excluded = Prefs.excludedIds(this).size();
        return time + " · " + (excluded == 0
                ? getString(R.string.setting_exclude_none)
                : getString(R.string.setting_exclude_fmt, excluded));
    }

    /** 通知被关闭 / 权限未授予时显示提示条。 */
    private void updateNoticeBar() {
        if (noticeBar == null) return;
        boolean permissionOk = true;
        if (Build.VERSION.SDK_INT >= 33) {
            permissionOk = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        boolean channelOk = notificationsEnabled();
        if (permissionOk && channelOk) {
            noticeBar.setVisibility(View.GONE);
            return;
        }
        noticeBar.setVisibility(View.VISIBLE);
        if (tvNotice != null) {
            tvNotice.setText(permissionOk ? R.string.setting_notice_off : R.string.setting_notice_denied);
        }
    }

    private boolean notificationsEnabled() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            return nm == null || nm.areNotificationsEnabled();
        } catch (Throwable t) {
            return true;
        }
    }

    /** 对话框里的选项文案：不延迟 / 5 分钟 / … */
    private String jitterLabel(int minutes) {
        return minutes <= 0 ? getString(R.string.jitter_no_delay)
                : getString(R.string.jitter_minutes_format, minutes);
    }

    /** 设置行右侧的蓝色数值：±0 分 / ±30 分（对齐原版）。 */
    private String jitterValueLabel(int minutes) {
        return minutes <= 0 ? getString(R.string.jitter_label_none)
                : getString(R.string.jitter_label_fmt, minutes);
    }

    private String versionName() {
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            if (v != null && v.length() > 0) return v;
        } catch (Throwable ignored) {
        }
        return MainActivity.VERSION;
    }

    // ------------------------------------------------------------------
    // 开关（全部真实生效）
    // ------------------------------------------------------------------

    private void bindSwitchActions() {
        swAuto.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Prefs.setAutoEnabled(SettingsActivity.this, isChecked);
                if (isChecked) {
                    AutoScheduler.rescheduleNow(SettingsActivity.this);
                    toast(getString(R.string.setting_auto_on));
                    ensureBatteryWhitelist();
                    ensureExactAlarm();
                } else {
                    AutoScheduler.cancel(SettingsActivity.this);
                    toast(getString(R.string.setting_auto_off));
                }
            }
        });

        swKeepAlive.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Prefs.setKeepAliveEnabled(SettingsActivity.this, isChecked);
                if (isChecked) {
                    AutoScheduler.scheduleKeepAlive(SettingsActivity.this);
                    toast(getString(R.string.setting_keep_alive_on));
                } else {
                    AutoScheduler.cancelKeepAlive(SettingsActivity.this);
                    toast(getString(R.string.setting_keep_alive_off));
                }
            }
        });

        swFirstOpen.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Prefs.setFirstOpenCheckinEnabled(SettingsActivity.this, isChecked);
                toast(getString(isChecked
                        ? R.string.setting_first_open_on : R.string.setting_first_open_off));
            }
        });

        swNotify.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Prefs.setNotifyEnabled(SettingsActivity.this, isChecked);
                if (isChecked) {
                    toast(getString(R.string.setting_notify_on));
                    requestNotificationPermissionIfNeeded();
                } else {
                    toast(getString(R.string.setting_notify_off));
                }
                updateNoticeBar();
            }
        });
    }

    /** Android 13+ 请求通知权限（拒绝也不回滚开关）。 */
    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_NOTIFY_PERMISSION);
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 行操作
    // ------------------------------------------------------------------

    private void bindRowActions() {
        setRowClick(R.id.rowSchedule, new Runnable() {
            @Override public void run() { showScheduleDialog(); }
        });
        setRowClick(R.id.rowJitter, new Runnable() {
            @Override public void run() { showJitterPicker(); }
        });
        setRowClick(R.id.rowBattery, new Runnable() {
            @Override public void run() { showBatteryDialog(); }
        });
        setRowClick(R.id.rowAutostart, new Runnable() {
            @Override public void run() { showAutostartDialog(); }
        });
        setRowClick(R.id.rowAlarm, new Runnable() {
            @Override public void run() { showAlarmDialog(); }
        });
        setRowClick(R.id.rowExport, new Runnable() {
            @Override public void run() { exportAccounts(); }
        });
        setRowClick(R.id.rowImport, new Runnable() {
            @Override public void run() { showImportDialog(); }
        });
        setRowClick(R.id.rowClearLogs, new Runnable() {
            @Override public void run() { confirmClearLogs(); }
        });
        setRowClick(R.id.rowGuide, new Runnable() {
            @Override public void run() {
                openDoc(R.raw.doc_guide, R.string.doc_guide_title, R.string.doc_sub_guide);
            }
        });
        setRowClick(R.id.rowPrivacy, new Runnable() {
            @Override public void run() {
                openDoc(R.raw.doc_privacy, R.string.doc_privacy_title, R.string.doc_sub_privacy);
            }
        });
        setRowClick(R.id.rowTerms, new Runnable() {
            @Override public void run() {
                openDoc(R.raw.doc_terms, R.string.doc_terms_title, R.string.doc_sub_terms);
            }
        });
        setRowClick(R.id.rowOpensource, new Runnable() {
            @Override public void run() {
                openDoc(R.raw.doc_opensource, R.string.doc_opensource_title, R.string.doc_sub_opensource);
            }
        });
        setRowClick(R.id.authorCard, new Runnable() {
            @Override public void run() {
                openDoc(R.raw.doc_opensource, R.string.doc_opensource_title, R.string.doc_sub_opensource);
            }
        });
        setRowClick(R.id.rowAbout, new Runnable() {
            @Override public void run() {
                openDoc(R.raw.doc_opensource, R.string.doc_opensource_title, R.string.doc_sub_opensource);
            }
        });
    }

    private void setRowClick(int id, final Runnable action) {
        View v = findViewById(id);
        if (v == null) return;
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { action.run(); }
        });
    }

    /** 打开通用文档页。 */
    private void openDoc(int rawRes, int titleRes, int subRes) {
        try {
            Intent i = new Intent(this, DocsActivity.class);
            i.putExtra(DocsActivity.EXTRA_RES, rawRes);
            i.putExtra(DocsActivity.EXTRA_TITLE, getString(titleRes));
            i.putExtra(DocsActivity.EXTRA_SUBTITLE, getString(subRes));
            startActivity(i);
        } catch (Throwable ignored) {
            toast(getString(R.string.docs_load_failed));
        }
    }

    /** 「管理签到时间与排除账号」入口。 */
    private void showScheduleDialog() {
        final CharSequence[] items = {
                getString(R.string.setting_schedule_time),
                getString(R.string.setting_schedule_exclude)
        };
        try {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.setting_schedule_title)
                    .setItems(items, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (which == 0) showTimePicker();
                            else showExcludeDialog();
                        }
                    })
                    .setNegativeButton(R.string.action_cancel, null)
                    .show();
        } catch (Throwable ignored) {
        }
    }

    /** 多选排除账号：被排除的账号不参与一键签到 / 首开签到 / 定时签到 / 凭证保活。 */
    private void showExcludeDialog() {
        final List<AccountStore.Account> accounts = AccountStore.get(this).accounts();
        if (accounts.isEmpty()) {
            toast(getString(R.string.setting_exclude_no_account));
            return;
        }
        final List<String> current = Prefs.excludedIds(this);
        final String[] names = new String[accounts.size()];
        final boolean[] checked = new boolean[accounts.size()];
        for (int i = 0; i < accounts.size(); i++) {
            names[i] = AccountStore.displayName(accounts.get(i));
            checked[i] = current.contains(accounts.get(i).id);
        }
        try {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.setting_schedule_exclude)
                    .setMultiChoiceItems(names, checked,
                            new DialogInterface.OnMultiChoiceClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                                    checked[which] = isChecked;
                                }
                            })
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.action_confirm,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    List<String> ids = new ArrayList<String>();
                                    for (int i = 0; i < accounts.size(); i++) {
                                        if (checked[i]) ids.add(accounts.get(i).id);
                                    }
                                    Prefs.setExcludedIds(SettingsActivity.this, ids);
                                    bindValues();
                                    toast(getString(ids.isEmpty()
                                            ? R.string.setting_exclude_none
                                            : R.string.setting_exclude_fmt, ids.size()));
                                }
                            })
                    .show();
        } catch (Throwable ignored) {
        }
    }

    /** 签到时间：系统 TimePickerDialog（24 小时制）。 */
    private void showTimePicker() {
        int hour = Prefs.hour(this);
        int minute = Prefs.minute(this);
        List<Prefs.TimePoint> times = Prefs.times(this);
        if (!times.isEmpty()) {
            hour = times.get(0).hour;
            minute = times.get(0).minute;
        }
        try {
            TimePickerDialog dialog = new TimePickerDialog(this,
                    new TimePickerDialog.OnTimeSetListener() {
                        @Override
                        public void onTimeSet(TimePicker view, int h, int m) {
                            applyTime(h, m);
                        }
                    }, hour, minute, true);
            dialog.show();
        } catch (Throwable t) {
            toast(getString(R.string.login_err_unknown));
        }
    }

    private void applyTime(int hour, int minute) {
        Prefs.setTime(this, hour, minute);
        List<Prefs.TimePoint> single = new ArrayList<Prefs.TimePoint>();
        single.add(new Prefs.TimePoint(hour, minute));
        Prefs.setTimes(this, single);
        AutoScheduler.rescheduleNow(this);
        bindValues();
        toast(getString(R.string.setting_time_saved_fmt, single.get(0).format()));
    }

    /** 随机延迟：单选对话框（0/5/10/15/30/60 分钟）。 */
    private void showJitterPicker() {
        final int current = Prefs.jitterMin(this);
        final String[] labels = new String[JITTER_OPTIONS.length];
        int checked = 0;
        for (int i = 0; i < JITTER_OPTIONS.length; i++) {
            labels[i] = jitterLabel(JITTER_OPTIONS[i]);
            if (JITTER_OPTIONS[i] == current) checked = i;
        }
        try {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.setting_jitter2)
                    .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            int minutes = JITTER_OPTIONS[which];
                            Prefs.setJitterMin(SettingsActivity.this, minutes);
                            AutoScheduler.rescheduleNow(SettingsActivity.this);
                            bindValues();
                            toast(getString(R.string.setting_jitter_saved_fmt, labels[which]));
                            dialog.dismiss();
                        }
                    })
                    .setNegativeButton(R.string.action_cancel, null)
                    .show();
        } catch (Throwable t) {
            toast(getString(R.string.login_err_unknown));
        }
    }

    // ------------------------------------------------------------------
    // 导入 / 导出账号（含完整凭据，必须先给出安全提示）
    // ------------------------------------------------------------------

    private void exportAccounts() {
        AccountStore store = AccountStore.get(this);
        if (store.count() == 0) {
            toast(getString(R.string.setting_export_empty));
            return;
        }
        final String json = store.exportAccountsJson();
        if (json.length() == 0) {
            toast(getString(R.string.setting_export_empty));
            return;
        }
        DialogHelper.show(this,
                getString(R.string.setting_export),
                getString(R.string.setting_export_warn),
                getString(R.string.action_cancel),
                getString(R.string.action_copy),
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(ClipData.newPlainText("TraeCheckin accounts", json));
                                toast(getString(R.string.setting_export_done));
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
    }

    private void showImportDialog() {
        try {
            final EditText input = new EditText(this);
            input.setHint(R.string.setting_import_hint);
            input.setSingleLine(false);
            input.setMaxLines(8);
            int pad = Math.round(16 * getResources().getDisplayMetrics().density);
            input.setPadding(pad, pad / 2, pad, pad / 2);
            // 剪贴板里若已是导出文本，自动预填，用户只需点「导入」
            String clip = readClipboard();
            if (clip.length() > 0) {
                input.setText(clip);
                input.setSelection(input.getText().length());
            }
            new AlertDialog.Builder(this)
                    .setTitle(R.string.setting_import_title)
                    .setMessage(R.string.setting_import_warn)
                    .setView(input)
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.action_import,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    String text = input.getText() == null
                                            ? "" : input.getText().toString();
                                    try {
                                        int n = AccountStore.get(SettingsActivity.this)
                                                .importAccountsJson(text);
                                        toast(getString(n > 0
                                                ? R.string.setting_import_done_fmt
                                                : R.string.setting_import_none, n));
                                        bindValues();
                                    } catch (IllegalArgumentException e) {
                                        toast(getString(R.string.setting_import_invalid));
                                    } catch (Throwable ignored) {
                                        toast(getString(R.string.setting_import_invalid));
                                    }
                                }
                            })
                    .show();
        } catch (Throwable ignored) {
        }
    }

    private String readClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || cm.getPrimaryClip() == null) return "";
            ClipData clip = cm.getPrimaryClip();
            if (clip.getItemCount() == 0) return "";
            CharSequence t = clip.getItemAt(0).getText();
            return t == null ? "" : t.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    // ------------------------------------------------------------------
    // 权限引导（全部 try/catch）
    // ------------------------------------------------------------------

    /** 电池优化白名单：OPPO / 小米 / 华为必做，否则定时任务不执行。 */
    private void ensureBatteryWhitelist() {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        } catch (Throwable t) {
            return;
        }
        showBatteryDialog();
    }

    private void showBatteryDialog() {
        if (Build.VERSION.SDK_INT < 23) return;
        DialogHelper.show(this,
                getString(R.string.setting_battery_title),
                getString(R.string.setting_battery_msg),
                getString(R.string.setting_later),
                getString(R.string.setting_go_settings),
                new Runnable() {
                    @Override
                    public void run() {
                        requestIgnoreBatteryOptimizations();
                    }
                });
    }

    private void requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            openAppDetailsSettings();
        }
    }

    /** 精确闹钟（Android 12+）。 */
    private void ensureExactAlarm() {
        if (AutoScheduler.canScheduleExact(this)) return;
        DialogHelper.show(this,
                getString(R.string.setting_alarm_title),
                getString(R.string.setting_alarm_msg),
                getString(R.string.setting_later),
                getString(R.string.setting_go_settings),
                new Runnable() {
                    @Override
                    public void run() {
                        if (!AutoScheduler.openExactAlarmSettings(SettingsActivity.this)) {
                            openAppDetailsSettings();
                        }
                    }
                });
    }

    private void showAlarmDialog() {
        if (AutoScheduler.canScheduleExact(this)) {
            toast(getString(R.string.setting_alarm_granted));
            return;
        }
        ensureExactAlarm();
    }

    /** 自启动与后台运行：列出厂商路径 + 一键打开应用设置。 */
    private void showAutostartDialog() {
        DialogHelper.show(this,
                getString(R.string.setting_autostart_title),
                getString(R.string.setting_autostart_msg),
                getString(R.string.action_cancel),
                getString(R.string.setting_open_app_details),
                new Runnable() {
                    @Override
                    public void run() {
                        if (!openVendorAutostart()) openAppDetailsSettings();
                    }
                });
    }

    private void confirmClearLogs() {
        DialogHelper.show(this,
                getString(R.string.logs_clear_title),
                getString(R.string.logs_clear_msg),
                getString(R.string.action_cancel),
                getString(R.string.action_delete),
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            AccountStore.get(SettingsActivity.this).clearHistory();
                            toast(getString(R.string.setting_logs_cleared));
                        } catch (Throwable ignored) {
                        }
                    }
                });
    }

    // ------------------------------------------------------------------
    // 系统跳转
    // ------------------------------------------------------------------

    private void openAppNotificationSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(i);
            return;
        } catch (Throwable ignored) {
        }
        try {
            Intent i = new Intent("android.settings.APP_NOTIFICATION_SETTINGS");
            i.putExtra("app_package", getPackageName());
            i.putExtra("app_uid", getApplicationInfo().uid);
            startActivity(i);
            return;
        } catch (Throwable ignored) {
        }
        openAppDetailsSettings();
    }

    private void openAppDetailsSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            toast(getString(R.string.setting_autostart_msg));
        }
    }

    private boolean openVendorAutostart() {
        String[][] candidates = {
                {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
                {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
        };
        for (String[] c : candidates) {
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName(c[0], c[1]));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private void toast(String msg) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    /** 统一的两按钮提示框（正/负按钮可为空）。 */
    private static final class DialogHelper {
        private DialogHelper() {
        }

        static void show(Activity activity, String title, String message,
                         String negative, String positive, final Runnable onPositive) {
            try {
                AlertDialog.Builder b = new AlertDialog.Builder(activity)
                        .setTitle(title)
                        .setMessage(message);
                if (negative != null) b.setNegativeButton(negative, null);
                if (positive != null) {
                    b.setPositiveButton(positive, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (onPositive != null) onPositive.run();
                        }
                    });
                } else {
                    b.setPositiveButton(activity.getString(R.string.action_confirm), null);
                }
                b.show();
            } catch (Throwable ignored) {
            }
        }
    }
}
