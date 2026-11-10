package com.traecheckin.mobile;

import android.app.Activity;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 通用文档页：渲染 res/raw 下的纯中文文本（使用说明书 / 隐私协议 / 用户协议 / 开源许可）。
 *
 * <p>文本用极简标记排版：{@code # } 大标题、{@code ## } 小节标题、{@code - } 列表项、
 * 空行分段。全部用 Spannable 在原生 TextView 里完成，不引入 WebView、不依赖 HTML，
 * 旧内核设备上也能正常显示。</p>
 */
public class DocsActivity extends Activity {

    public static final String EXTRA_RES = "doc_res";
    public static final String EXTRA_TITLE = "doc_title";
    public static final String EXTRA_SUBTITLE = "doc_subtitle";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_docs);

        int resId = getIntent().getIntExtra(EXTRA_RES, R.raw.doc_guide);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String subtitle = getIntent().getStringExtra(EXTRA_SUBTITLE);
        if (title == null || title.length() == 0) title = getString(R.string.docs_title);
        if (subtitle == null) subtitle = "";

        TextView tvTitle = (TextView) findViewById(R.id.tvDocTitle);
        TextView tvSub = (TextView) findViewById(R.id.tvDocSubtitle);
        tvTitle.setText(title);
        tvSub.setText(subtitle);
        tvSub.setVisibility(subtitle.length() > 0 ? View.VISIBLE : View.GONE);

        ImageView back = (ImageView) findViewById(R.id.btnDocBack);
        back.setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        TextView tvDoc = (TextView) findViewById(R.id.tvDoc);
        tvDoc.setText(render(readRaw(resId)));

        TextView tvFooter = (TextView) findViewById(R.id.tvDocFooter);
        tvFooter.setText(getString(R.string.docs_footer_fmt, versionName()));

        // 进入动画：内容轻微上移淡入
        View content = findViewById(R.id.docContent);
        content.setAlpha(0f);
        content.setTranslationY(dp(14));
        content.animate().alpha(1f).translationY(0f)
                .setDuration(260).setStartDelay(40).start();
    }

    private String readRaw(int resId) {
        InputStream is = null;
        try {
            is = getResources().openRawResource(resId);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return getString(R.string.docs_load_failed);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 极简文本排版：标题加粗放大变色，列表项加圆点，空行分段。 */
    private SpannableStringBuilder render(String raw) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (raw == null) return sb;
        String[] lines = raw.replace("\r", "").split("\n", -1);
        int ink = getColorCompat(R.color.ink);
        int brand = getColorCompat(R.color.brand_deep);
        int muted = getColorCompat(R.color.ink_2);
        for (String line : lines) {
            String s = line.trim();
            int start = sb.length();
            if (s.length() == 0) {
                sb.append("\n");
                continue;
            }
            if (s.equals("---")) {
                sb.append("────────────────────\n");
                sb.setSpan(new ForegroundColorSpan(getColorCompat(R.color.line)),
                        start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }
            if (s.startsWith("## ")) {
                String text = s.substring(3);
                if (sb.length() > 0) sb.append("\n");
                int h = sb.length();
                sb.append(text).append("\n");
                sb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD),
                        h, h + text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new RelativeSizeSpan(1.12f),
                        h, h + text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(brand),
                        h, h + text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }
            if (s.startsWith("# ")) {
                String text = s.substring(2);
                int h = sb.length();
                sb.append(text).append("\n\n");
                sb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD),
                        h, h + text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new RelativeSizeSpan(1.32f),
                        h, h + text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new ForegroundColorSpan(ink),
                        h, h + text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }
            if (s.startsWith("- ")) {
                String text = "•  " + s.substring(2);
                sb.append(text).append("\n");
                sb.setSpan(new ForegroundColorSpan(muted),
                        start, start + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }
            sb.append(s).append("\n");
        }
        return sb;
    }

    private String versionName() {
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            if (v != null && v.length() > 0) return v;
        } catch (Throwable ignored) {
        }
        return MainActivity.VERSION;
    }

    private int getColorCompat(int resId) {
        if (android.os.Build.VERSION.SDK_INT >= 23) return getColor(resId);
        return getResources().getColor(resId);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
