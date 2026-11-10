package com.traecheckin.mobile;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 登录页：WebView 打开 Trae 官方 SOLO OAuth 授权页（{@code /authorization}），
 * 全程在手机上完成，不需要电脑或任何外部服务器。
 *
 * <p><b>为什么必须用 OAuth 而不是网页版 /login</b>：签到接口按 SOLO 客户端谱系校验，
 * 网页版 cookie 换来的短期 JWT 会被判成设备校验失败，稳定返回 9074「当前参与用户太多」。
 * 官方客户端会在授权链接里一并注册 {@code device_id / machine_id}，登录成功后回调
 * 下发长期 {@code refreshToken}，再用 {@code ExchangeToken} 换 accessToken。本项目
 * 用 {@link WebViewClient#shouldOverrideUrlLoading} 系列回调直接截住
 * {@code http://127.0.0.1:18080/authorize}，不真正起本地端口。</p>
 *
 * <p>链路：</p>
 * <ol>
 *   <li>{@link #startNewAuthorization()} 生成 12~20 位纯数字设备号（避开已有账号），
 *       拼进授权链接 {@link #buildAuthorizationUrl()}；</li>
 *   <li>用户在 WebView 里完成手机号登录，上游回跳 callback，
 *       {@link #handleOAuthCallback(String)} 解析 refreshToken / userJwt / userInfo；</li>
 *   <li>{@link TraeApi#exchangeRefreshToken(String)} 换 accessToken，汇入
 *       {@link #handleToken(String)}；</li>
 *   <li>{@link #verifyAndSave(String)} 用<b>同一个设备号</b>调 {@link TraeApi#status} 校验，
 *       通过后 {@link #saveAccount} 把整份 OAuth 凭证一起落库。</li>
 * </ol>
 *
 * <p><b>页面兼容性</b>：授权页仍是 React + Modern.js 的 SSR 输出，「获取验证码 / 勾选协议 /
 * 第三方登录」依赖 bundle hydrate 后才挂事件。因此本项目<b>不对页面做任何注入或写操作</b>，
 * 唯一的 {@link #HYDRATE_JS} 是只读探针；旧内核（Chrome/83）对 {@code 8049.*.js} 的
 * 私有方法与 {@code ??=} 会 SyntaxError，由 {@link #downgradeJs(String)} 在
 * {@code shouldInterceptRequest} 里做语法降级，保证按钮可点。</p>
 */
public class LoginActivity extends Activity {

    /** 可选参数：给已有账号「重新登录」时传入账号 id。 */
    public static final String EXTRA_REPLACE_ID = "replace_id";
    /** 结果回传：新增 / 更新的账号 id。 */
    public static final String EXTRA_ACCOUNT_ID = "account_id";

    /**
     * 官方 SOLO OAuth 授权页。设备号必须在登录链接里一并注册，
     * 否则 /checkin_credits/claim 会稳定返回 9074「参与用户太多」。
     */
    private static final String AUTH_BASE = "https://www.trae.cn/authorization";
    /** 原生 WebView 直接拦截回调，不需要本地端口或电脑。 */
    private static final String OAUTH_CALLBACK = "http://127.0.0.1:18080/authorize";
    private static final String PLUGIN_VERSION = "2.3.62834";

    /**
     * 只读水合探针（<b>全项目唯一允许的 evaluateJavascript</b>）。
     *
     * <p>判据是 React 的<b>内部标记属性</b>，而不是页面文案 —— 服务端会返回不同变体的 HTML
     * （「获取验证　　码」/「获取验证码」两种都出现过），文本判据会随机误判：</p>
     * <ul>
     *   <li>{@code __reactContainer$xxx}：React 17+ 挂载在容器元素上的标记，hydrate 成功才有；</li>
     *   <li>{@code _reactListeningxxx}：React 事件委托标记，存在即代表点击事件已绑到容器上，
     *       直接对应「按钮点不点得动」这个现象。</li>
     * </ul>
     * <p>只读属性名，不写全局变量、不改 DOM、不发请求。返回值：</p>
     * <ul>
     *   <li>{@code "ok"}：两个标记都在 —— React 已接管且事件已绑定，交互正常；</li>
     *   <li>{@code "noevents"}：有容器标记但没有事件标记 —— JS 跑了但事件没绑上；</li>
     *   <li>{@code "ssr"}：两个都没有 —— 页面 JS 从未执行；</li>
     *   <li>{@code "noroot"} / {@code "err"}：结构变了或探针异常，不下结论（避免误报）。</li>
     * </ul>
     */
    static final String HYDRATE_JS =
            "(function(){try{"
                    + "var r=document.getElementById('root');"
                    + "if(!r)return 'noroot';"
                    + "var ks=Object.keys(r),hasC=false,hasL=false;"
                    + "for(var i=0;i<ks.length;i++){"
                    + "var k=ks[i];"
                    + "if(k.indexOf('__reactContainer')===0)hasC=true;"
                    + "if(k.indexOf('_reactListening')===0)hasL=true;"
                    + "}"
                    + "if(hasC&&hasL)return 'ok';"
                    + "if(hasC)return 'noevents';"
                    + "return 'ssr';"
                    + "}catch(e){return 'err'}})()";

    /** cookie 探针间隔：2s。 */
    private static final long PROBE_INTERVAL_MS = 2000L;
    /** 轮询上限：5 分钟。 */
    private static final long POLL_TIMEOUT_MS = 5L * 60L * 1000L;
    /** token 最短长度（JWT 远长于此，用于过滤空串 / 脏数据）。 */
    private static final int MIN_TOKEN_LEN = 20;
    /** 30s 仍未检测到登录状态时提前给出提示（轮询继续）。 */
    private static final long SLOW_HINT_MS = 30L * 1000L;
    /** 20s 内页面还没加载完就提示网络慢（只提示，不中断）。 */
    private static final long PAGE_WATCHDOG_MS = 20L * 1000L;
    /** 两次换 token 的最小间隔，避免 cookie 抖动时把 GetUserToken 打成洪水。 */
    private static final long EXCHANGE_THROTTLE_MS = 3000L;
    /** 换 token 失败后的退避：未登录时最多每 15s 试一次（5 分钟约 20 次）。 */
    private static final long EXCHANGE_FAIL_BACKOFF_MS = 15000L;
    /** 手动按钮点击后多久仍没换到 token 就给出「还没检测到」提示。 */
    private static final long NOT_YET_HINT_MS = 3000L;
    /** 水合探针首次执行延迟（页面加载完成后 5s）。 */
    private static final long HYDRATE_PROBE_DELAY_MS = 5000L;
    /** 水合探针复测间隔：6s（5s / 11s / 17s 共三次）。 */
    private static final long HYDRATE_RETRY_MS = 6000L;
    /** 水合探针最多执行次数：网络慢时 bundle 可能 15s 才跑完，只测一次会误判。 */
    private static final int HYDRATE_MAX_TRIES = 3;
    /** 探针回调超时兜底：页面导航会吞掉 onReceiveValue，8s 没回就当本次采样失败。 */
    private static final long HYDRATE_CALLBACK_TIMEOUT_MS = 8000L;
    /** popup → 主 WebView 的转交去重窗口：同一 URL 3s 内只转交一次。 */
    private static final long HANDOFF_DEDUPE_MS = 3000L;

    /**
     * 旧内核语法降级的命中锚点。
     *
     * <p>该 chunk 是整个站点唯一使用 Chrome 84+ 私有方法（{@code #x(){}}）与 {@code ??=}
     * 的文件，其余 25 个 chunk 都满足 Chrome 80+，因此只拦它一个。</p>
     */
    private static final String JS_PATCH_NEEDLE = "/static/js/8049.";
    /** 需要降级的内核主版本上界：{@code 主版本 < 84} 才拦（私有方法需 84+，{@code ??=} 需 85+）。 */
    private static final int JS_PATCH_MAJOR_LIMIT = 84;
    /** 下载 chunk 的连接 / 读取超时。 */
    private static final int JS_PATCH_TIMEOUT_MS = 8000;
    /** 诊断里最多记录的命中 URL 条数。 */
    private static final int JS_PATCH_MAX_URLS = 2;
    /** 最多收集的 JS 错误条数。 */
    private static final int MAX_CONSOLE_ERRORS = 5;
    /** 最多收集的资源加载失败条数。 */
    private static final int MAX_RESOURCE_FAILS = 3;
    /** 诊断信息里每类最多输出的条数。 */
    private static final int DIAG_ITEMS = 3;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private AccountStore store;
    private ExecutorService executor;
    private WebView webView;
    /** window.open 的临时承载 WebView（attached，JS 才能跑）。 */
    private WebView popupView;
    private View loadingRow;
    private ProgressBar progressLoading;
    private TextView tvLoadingText;
    private TextView btnLoginDone;
    private TextView tvHint;

    /** 重新登录时的目标账号 id，null 表示新增。 */
    private String replaceId;
    /** 本次授权会话参数，登录链接与落库凭证必须一致。 */
    private String oauthTraceId = "";
    private String oauthMachineId = "";
    private String oauthDeviceId = "";
    private boolean oauthCallbackHandled = false;
    /** 回调解析出的完整 SOLO 凭证，校验通过后写入账号库。 */
    private final AuthBundle pendingAuth = new AuthBundle();
    private boolean polling = false;
    private boolean tokenHandled = false;
    private boolean verifying = false;
    private boolean destroyed = false;
    /** 「还没检测到登录状态」的 30s 提示是否已经展示过。 */
    private boolean slowHintShown = false;
    private boolean pageFinished = false;
    private long pollStartAt = 0L;
    private Runnable pollTask;
    private Runnable watchdogTask;
    /** 最近一次校验失败的 token：同一个值不再重复投递，避免死循环重试。 */
    private String lastFailedToken = "";
    /** 本次正在校验的 token（失败时写入 {@link #lastFailedToken}）。 */
    private String verifyingToken = "";

    /** 登录前（页面加载完成时）的 cookie 快照，用于判断「cookie 变了」= 可能已登录。 */
    private String cookieBaseline = "";
    /** 最近一次提交换 token 的时间戳，用于 3s 节流。 */
    private long lastExchangeAt = 0L;
    /** 换 token 失败后的退避截止时间（毫秒时间戳），失败时置 now + 15s。 */
    private long exchangeBackoffUntil = 0L;
    /** 换 token 是否在途：网络悬挂（连接+读各 15s）时防止轮询把同 cookie 请求堆叠起来。 */
    private boolean exchangeInFlight = false;

    /** 最近一次从 popup 转交主 WebView 的 URL，用于 3s 内同 URL 去重。 */
    private String lastHandoffUrl = "";
    /** 最近一次转交的时间戳。 */
    private long lastHandoffAt = 0L;

    /** 是否需要对本机旧内核做 JS 语法降级（主版本 < 84 时为 true）。 */
    private volatile boolean needJsDowngrade = false;
    /** 降级链路状态：unknown / off / on / hit / fail（写入诊断信息）。 */
    private volatile String jsPatchState = "unknown";
    /** 已降级内容的缓存（key = 原始 URL，value = UTF-8 字节），避免重复下载改写。 */
    private final Map<String, byte[]> jsPatchCache = new ConcurrentHashMap<String, byte[]>();
    /** 命中并降级成功的 chunk 文件名（最多 {@link #JS_PATCH_MAX_URLS} 条），写入诊断信息。 */
    private final List<String> jsPatchUrls = Collections.synchronizedList(new ArrayList<String>());
    /** 降级过程中发现的残留语法（静态，便于 downgradeJs 做静态方法被单测调用）。 */
    private static final List<String> JS_PATCH_NOTES =
            Collections.synchronizedList(new ArrayList<String>());
    /** 初始化时抓下来的 WebView UA，供后台线程下载 chunk 时使用（后台不许碰 WebView）。 */
    private volatile String webUa = "";

    /** 当前 WebView 内核版本（从默认 UA 里提取 Chrome/x.y.z），取不到为 "unknown"。 */
    private String coreVersion = "unknown";
    /** 页面 JS 错误（只收 ERROR 级别），最多 {@link #MAX_CONSOLE_ERRORS} 条。 */
    private final List<String> consoleErrors = new ArrayList<String>();
    /** 非主文档资源加载失败（host + path），最多 {@link #MAX_RESOURCE_FAILS} 条。 */
    private final List<String> resourceFails = new ArrayList<String>();
    /** 非主文档资源失败总次数（列表只留前几条，计数不丢）。 */
    private int resourceFailCount = 0;
    /** 命中原生诊断模式：提示文案已换成诊断结论（按钮仍是「我已完成登录」，长按复制诊断）。 */
    private boolean diagMode = false;
    /** 手动按钮点击后的「还没检测到」延迟提示。 */
    private Runnable notYetTask;
    /** 水合探针任务（onPageFinished 后 5s 首次，之后每 6s 复测，最多 3 次）。 */
    private Runnable hydrateTask;
    /** 探针回调超时兜底任务（页面导航会吞掉 onReceiveValue）。 */
    private Runnable hydrateTimeoutTask;
    /** 水合探针已执行次数（1~{@link #HYDRATE_MAX_TRIES}）。 */
    private int hydrateTry = 0;
    /** 水合探针是否已收敛（拿到 ok 或已用满 3 次），收敛后不再复测。 */
    private boolean hydrateFinished = false;
    /** 探针已发出、结果还没回来的窗口期标记，避免 onPageFinished 重入重置计数。 */
    private boolean hydrateInFlight = false;
    /** 最后一次水合探针结论，写入诊断信息便于判断失败类型。 */
    private String hydrateVerdict = "";
    /** 拷贝到剪贴板的具体结论（ok / ssr / noevents / noroot / err）。 */
    private String diagVerdict = "";

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        executor = Executors.newSingleThreadExecutor();
        store = AccountStore.get(this);
        replaceId = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_REPLACE_ID);
        coreVersion = detectCoreVersion();

        // WebView 缺失（部分 ROM 精简掉了 Android System WebView）时不能闪退
        if (!safeInflate()) return;

        bindViews();
        initWebView();
        startPageWatchdog();
    }

    /** 加载布局；失败则展示降级页并返回 false。 */
    private boolean safeInflate() {
        try {
            setContentView(R.layout.activity_login);
            return true;
        } catch (Throwable t) {
            try {
                setContentView(R.layout.activity_login_error);
                View back = findViewById(R.id.btnErrorBack);
                if (back != null) {
                    back.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            finish();
                        }
                    });
                }
            } catch (Throwable ignored) {
            }
            toast(getString(R.string.login_webview_broken));
            return false;
        }
    }

    private void bindViews() {
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        loadingRow = findViewById(R.id.loadingRow);
        progressLoading = (ProgressBar) findViewById(R.id.progressLoading);
        tvLoadingText = (TextView) findViewById(R.id.tvLoadingText);
        btnLoginDone = (TextView) findViewById(R.id.btnLoginDone);
        tvHint = (TextView) findViewById(R.id.tvHint);

        // 标题区分「添加账号」与「重新登录」
        int titleRes = replaceId != null ? R.string.login_title_relogin : R.string.login_title;
        TextView tvTitle = (TextView) findViewById(R.id.tvTitle);
        if (tvTitle != null) tvTitle.setText(titleRes);
        try {
            setTitle(titleRes);
        } catch (Throwable ignored) {
        }

        // 点击永远是「我已完成登录」（诊断模式下也必须保留手动重试入口，
        // 否则用户失败一次后就只剩复制，只能退出重进）
        btnLoginDone.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onDoneClicked();
            }
        });
        // 长按复制诊断信息（任何时候都可用，不需要先进诊断模式）
        btnLoginDone.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                copyDiagnostics();
                return true;
            }
        });
    }

    // ------------------------------------------------------------------
    // WebView：一律浏览器默认，不做任何干扰页面的设置
    // ------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private void initWebView() {
        try {
            webView = (WebView) findViewById(R.id.webView);
            if (webView == null) {
                showWebViewBroken();
                return;
            }
            WebSettings s = webView.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            // 登录过程可能用 window.open 发起跳转，这里放开多窗口并用 onCreateWindow 接管
            s.setJavaScriptCanOpenWindowsAutomatically(true);
            s.setSupportMultipleWindows(true);
            s.setCacheMode(WebSettings.LOAD_DEFAULT);
            // 兼容模式：验证码 iframe 可能涉及混合内容，严格模式会把它整块拦掉
            try {
                s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
            } catch (Throwable ignored) {
            }
            // 登录页不需要读本地文件，收紧攻击面
            try {
                s.setAllowFileAccess(false);
            } catch (Throwable ignored) {
            }
            try {
                s.setAllowContentAccess(false);
            } catch (Throwable ignored) {
            }
            // 刻意不再设置 UA / 视口 / 缩放 / Database：桌面 UA 与宽视口会改变页面分支，
            // 与「让网页 JS 在原样环境下跑起来」的目标背道而驰。

            // 旧内核（Chrome < 84）需要语法降级的 chunk 拦截：只在必要时启用，
            // 84+ 一律不拦，让页面原样加载。
            // 诊断容器是静态 / 长生命周期的，进页面先清一次，避免跨页面、跨会话累积
            // 出上一轮的命中记录和残留提示。
            try {
                synchronized (JS_PATCH_NOTES) {
                    JS_PATCH_NOTES.clear();
                }
                synchronized (jsPatchUrls) {
                    jsPatchUrls.clear();
                }
            } catch (Throwable ignored) {
            }
            try {
                webUa = s.getUserAgentString();
            } catch (Throwable ignored) {
            }
            int major = coreMajor();
            needJsDowngrade = major > 0 && major < JS_PATCH_MAJOR_LIMIT;
            jsPatchState = needJsDowngrade ? "on" : "off";

            webView.setWebViewClient(new WebViewClient() {
                /**
                 * 旧内核语法降级：命中目标 chunk 时用改写后的内容替换。
                 *
                 * <p>本方法运行在 WebView 的后台线程，允许同步阻塞下载，但<b>绝不能碰 UI</b>；
                 * 任何失败都返回 null，让 WebView 走正常加载（保底不劣化）。</p>
                 */
                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view,
                                                                  WebResourceRequest request) {
                    try {
                        if (!needJsDowngrade) return null;
                        if (request == null || request.getUrl() == null) return null;
                        String url = request.getUrl().toString();
                        if (url.length() == 0 || url.indexOf(JS_PATCH_NEEDLE) < 0) return null;
                        byte[] bytes = fetchAndDowngrade(url);
                        if (bytes == null || bytes.length == 0) {
                            jsPatchState = "fail";
                            recordJsPatchNote("patch:fail");
                            return null;
                        }
                        jsPatchState = "hit";
                        recordJsPatchUrl(url);
                        return new WebResourceResponse("application/javascript", "UTF-8",
                                new ByteArrayInputStream(bytes));
                    } catch (Throwable t) {
                        jsPatchState = "fail";
                        return null;
                    }
                }

                @Override
                public void onPageStarted(WebView view, String url, Bitmap favicon) {
                    // OAuth 可能用 302 跳回调，部分机型不会走 shouldOverrideUrlLoading。
                    // 在首个页面开始事件里兜底截住，阻止真正访问 127.0.0.1。
                    if (isOAuthCallback(url)) {
                        try {
                            view.stopLoading();
                        } catch (Throwable ignored) {
                        }
                        handleOAuthCallback(url);
                    }
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    if (destroyed) return;
                    if (isOAuthCallback(url)) return;
                    pageFinished = true;
                    cancelPageWatchdog();
                    if (!tokenHandled && !verifying) {
                        showLoading(false, null);
                        if (!diagMode && tvHint != null) {
                            tvHint.setText(R.string.login_oauth_pending);
                        }
                    }
                    startHydrateProbe();
                }

                /** 站内跳转交给 WebView；只拦截官方 OAuth 回调。 */
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    if (isOAuthCallback(url)) {
                        handleOAuthCallback(url);
                        return true;
                    }
                    return false;
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    String url = request == null || request.getUrl() == null
                            ? null : request.getUrl().toString();
                    if (isOAuthCallback(url)) {
                        handleOAuthCallback(url);
                        return true;
                    }
                    return false;
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request,
                                            WebResourceError error) {
                    if (request == null) return;
                    String url = request.getUrl() == null ? null : request.getUrl().toString();
                    if (isOAuthCallback(url)) {
                        handleOAuthCallback(url);
                        return;
                    }
                    if (!request.isForMainFrame()) {
                        // 子资源（JS chunk / 图片 / 字体）失败：只记录，用于诊断「为何 JS 没跑起来」
                        recordResourceFail(request);
                        return;
                    }
                    int code = error == null ? -1 : error.getErrorCode();
                    onPageLoadFailed(code);
                }

                @SuppressWarnings("deprecation")
                @Override
                public void onReceivedError(WebView view, int errorCode, String description,
                                            String failingUrl) {
                    if (isOAuthCallback(failingUrl)) {
                        handleOAuthCallback(failingUrl);
                        return;
                    }
                    // 旧回调兜底
                    onPageLoadFailed(errorCode);
                }
            });

            webView.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onCreateWindow(WebView view, boolean isDialog,
                                              boolean isUserGesture, Message resultMsg) {
                    // window.open：第三方登录（抖音 / 苹果 / 企业账号）必须真正 attach 的 WebView
                    return openPopupInSelf(resultMsg);
                }

                @Override
                public boolean onConsoleMessage(ConsoleMessage cm) {
                    // 只「旁听」ERROR 级别用于诊断，不拦截：返回 false 让默认处理继续打日志，
                    // 否则调试时 console 输出会被吞掉。
                    if (cm == null || cm.messageLevel() != ConsoleMessage.MessageLevel.ERROR) {
                        return false;
                    }
                    if (consoleErrors.size() >= MAX_CONSOLE_ERRORS) return false;
                    try {
                        String msg = abbreviate(cm.message(), 160);
                        String src = cm.sourceId() == null ? "" : cm.sourceId();
                        // 只留文件名，完整 CDN 地址对用户没有意义，反而会让提示变长
                        int slash = src.lastIndexOf('/');
                        if (slash >= 0 && slash + 1 < src.length()) src = src.substring(slash + 1);
                        consoleErrors.add("L" + cm.lineNumber() + " "
                                + (src.length() > 0 ? src + ": " : "") + msg);
                    } catch (Throwable ignored) {
                    }
                    return false;
                }
            });
            webView.setBackgroundColor(0xFFFFFFFF);

            try {
                CookieManager cm = CookieManager.getInstance();
                cm.setAcceptCookie(true);
                cm.setAcceptThirdPartyCookies(webView, true);
            } catch (Throwable ignored) {
            }

            showLoading(true, getString(R.string.login_loading));
            startNewAuthorization();
        } catch (Throwable t) {
            webView = null;
            showWebViewBroken();
        }
    }

    // ------------------------------------------------------------------
    // SOLO OAuth 授权（全程在手机 WebView 内完成）
    // ------------------------------------------------------------------

    /** 生成新的授权会话参数并加载官方授权页。 */
    private void startNewAuthorization() {
        if (destroyed || webView == null) return;
        oauthTraceId = randomHex16();
        List<String> used = new ArrayList<String>();
        try {
            for (AccountStore.Account a : store.accounts()) {
                if (a != null && a.deviceId != null) used.add(a.deviceId);
            }
        } catch (Throwable ignored) {
        }
        // 用户手动设置的真机号必须原样沿用：重新登录会重新走授权页，若这里又
        // 随机生成一个新号，上游注册的是新号、真机号反而被覆盖，9074 永远修不好。
        String manualId = null;
        if (replaceId != null) {
            try {
                AccountStore.Account old = store.byId(replaceId);
                if (old != null && old.deviceIdManual
                        && AccountStore.isValidDeviceId(old.deviceId)) manualId = old.deviceId;
            } catch (Throwable ignored) {
            }
        }
        oauthDeviceId = manualId != null ? manualId : AccountStore.newDeviceId(used);
        // 对齐官方完整实现（xiaoyinge/trae-signin-gui login.rs::gen_machine_id）：
        // machine_id 是独立的 32 位 hex，device_id 才是 12~20 位纯数字 Aha 号。
        // claim 请求不带 machine_id（upstream.rs 有单元测试断言），它只影响授权页。
        oauthMachineId = UUID.randomUUID().toString().replace("-", "");
        oauthCallbackHandled = false;
        tokenHandled = false;
        verifying = false;
        lastFailedToken = "";
        if (btnLoginDone != null) btnLoginDone.setEnabled(true);
        if (!diagMode && tvHint != null) tvHint.setText(R.string.login_oauth_pending);
        showLoading(true, getString(R.string.login_loading));
        webView.loadUrl(buildAuthorizationUrl());
    }

    /**
     * login_trace_id 仅用于「上游原样回传、本地比对」的回调防注入，
     * 不参与设备校验：纯签到版 login.sh 用 16 位 hex（secrets.token_hex(8)），
     * 完整客户端用 32 位 hex。这里沿用 16 位，够用且与回调校验逻辑一致。
     */
    private static String randomHex16() {
        String h = UUID.randomUUID().toString().replace("-", "");
        return h.length() >= 16 ? h.substring(0, 16) : h;
    }

    /** 构造官方客户端同款授权链接：device_id 必须在登录阶段就传给上游。 */
    private String buildAuthorizationUrl() {
        try {
            return AUTH_BASE
                    + "?login_version=1"
                    + "&auth_from=solo"
                    + "&login_channel=native_ide"
                    + "&plugin_version=" + PLUGIN_VERSION
                    + "&auth_type=local"
                    + "&client_id=" + TraeApi.CLIENT_ID
                    + "&redirect=0"
                    + "&login_trace_id=" + oauthTraceId
                    + "&auth_callback_url=" + URLEncoder.encode(OAUTH_CALLBACK, "UTF-8")
                    + "&machine_id=" + oauthMachineId
                    + "&device_id=" + oauthDeviceId
                    + "&x_device_id=" + oauthDeviceId
                    + "&x_machine_id=" + oauthMachineId
                    + "&x_device_brand=PC"
                    + "&x_device_type=PC"
                    + "&x_os_version=1.0"
                    + "&x_app_version=" + TraeApi.IDE_VERSION
                    + "&x_app_type=stable";
        } catch (Throwable t) {
            return AUTH_BASE;
        }
    }

    private static boolean isOAuthCallback(String url) {
        // 上游可能把凭证挂在 /authorize 之外的 loopback 路径上，按端口前缀一并截住，
        // 避免「回调没抓住」时用户对着 127.0.0.1 报错页发呆。
        return url != null && (url.startsWith(OAUTH_CALLBACK)
                || url.startsWith("http://127.0.0.1:18080/"));
    }

    /** 解析授权回调 -> ExchangeToken 换 accessToken -> 汇入既有校验落库流程。 */
    private void handleOAuthCallback(String url) {
        if (destroyed || oauthCallbackHandled || tokenHandled || verifying) return;
        oauthCallbackHandled = true;
        stopPolling();
        cancelNotYetHint();
        try {
            if (webView != null) webView.stopLoading();
        } catch (Throwable ignored) {
        }

        try {
            Uri uri = Uri.parse(url);
            String trace = queryParam(uri, "loginTraceID", "login_trace_id");
            if (trace != null && trace.length() > 0 && !trace.equals(oauthTraceId)) {
                oauthFailed(getString(R.string.login_oauth_invalid_callback));
                return;
            }
            String refresh = queryParam(uri, "refreshToken", "refresh_token");
            JSONObject userJwt = parseJsonParam(queryParam(uri, "userJwt", "user_jwt"));
            JSONObject userInfo = parseJsonParam(queryParam(uri, "userInfo", "user_info"));
            if (refresh == null || refresh.length() == 0) {
                refresh = TraeApi.string(userJwt, "RefreshToken", "refreshToken", "refresh_token");
            }
            final String fallbackToken = TraeApi.string(userJwt, "Token", "AccessToken", "access_token");
            long fallbackExpiry = TraeApi.longValue(userJwt, "TokenExpireAt", "tokenExpireAt");
            if (fallbackExpiry > 1000000000000L) fallbackExpiry /= 1000L;
            final long finalFallbackExpiry = fallbackExpiry;
            final String uid = TraeApi.string(userInfo, "UserID", "uid", "user_id");
            final String nickname = TraeApi.string(userInfo, "ScreenName", "nickname", "name");
            final String cookie = captureCookie();

            if ((refresh == null || refresh.length() == 0)
                    && (fallbackToken == null || fallbackToken.length() <= 20)) {
                oauthFailed(getString(R.string.login_oauth_no_credential));
                return;
            }

            final String finalRefresh = refresh == null ? "" : refresh;
            verifying = true;
            showLoading(true, getString(R.string.login_verifying));
            if (btnLoginDone != null) btnLoginDone.setEnabled(false);

            final ExecutorService ex = executor;
            if (ex == null || ex.isShutdown()) {
                oauthFailed(getString(R.string.login_oauth_exchange_failed));
                return;
            }
            ex.execute(new Runnable() {
                @Override
                public void run() {
                    String access = "";
                    String nextRefresh = finalRefresh;
                    long expiresAt = finalFallbackExpiry;
                    if (finalRefresh.length() > 0) {
                        JSONObject r = TraeApi.exchangeRefreshToken(finalRefresh);
                        if (r != null && r.optBoolean("ok", false)) {
                            access = r.optString("token", "");
                            nextRefresh = r.optString("refresh_token", finalRefresh);
                            expiresAt = r.optLong("expires_at", 0L);
                        } else if (fallbackToken != null && fallbackToken.length() > 20) {
                            access = fallbackToken;
                            nextRefresh = "";
                        }
                    } else {
                        access = fallbackToken;
                        nextRefresh = "";
                    }
                    final String finalAccess = access;
                    final String finalNextRefresh = nextRefresh;
                    final long finalExpiresAt = expiresAt;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed) return;
                            verifying = false;
                            showLoading(false, null);
                            if (btnLoginDone != null) btnLoginDone.setEnabled(true);
                            if (finalAccess == null || finalAccess.length() <= 20) {
                                oauthFailed(getString(R.string.login_oauth_exchange_failed));
                                return;
                            }
                            pendingAuth.accessToken = finalAccess;
                            pendingAuth.refreshToken = finalNextRefresh == null ? "" : finalNextRefresh;
                            pendingAuth.expiresAt = finalExpiresAt;
                            pendingAuth.deviceId = oauthDeviceId;
                            pendingAuth.machineId = oauthMachineId;
                            pendingAuth.uid = uid;
                            pendingAuth.nickname = nickname;
                            pendingAuth.cookie = cookie;
                            handleToken(finalAccess);
                        }
                    });
                }
            });
        } catch (Throwable t) {
            oauthFailed(getString(R.string.login_oauth_invalid_callback));
        }
    }

    private static String queryParam(Uri uri, String key, String fallbackKey) {
        try {
            String v = uri.getQueryParameter(key);
            if (v == null || v.length() == 0) v = uri.getQueryParameter(fallbackKey);
            // 部分授权页把凭证放在 fragment（#a=1&b=2）而不是 query，query 取不到时兜底。
            if (v == null || v.length() == 0) v = fragmentParam(uri, key);
            if (v == null || v.length() == 0) v = fragmentParam(uri, fallbackKey);
            return v == null ? "" : v;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 从 URL fragment 里按键取值（fragment 不参与 getQueryParameter）。 */
    private static String fragmentParam(Uri uri, String key) {
        try {
            String frag = uri.getFragment();
            if (frag == null || frag.length() == 0 || key == null) return "";
            String[] pairs = frag.split("&");
            for (int i = 0; i < pairs.length; i++) {
                int eq = pairs[i].indexOf('=');
                if (eq < 0) continue;
                String k = Uri.decode(pairs[i].substring(0, eq));
                if (key.equals(k)) return Uri.decode(pairs[i].substring(eq + 1));
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static JSONObject parseJsonParam(String raw) {
        if (raw == null || raw.length() == 0) return new JSONObject();
        try {
            return new JSONObject(raw);
        } catch (Throwable ignored) {
        }
        try {
            return new JSONObject(Uri.decode(raw));
        } catch (Throwable ignored) {
            return new JSONObject();
        }
    }

    private void oauthFailed(String message) {
        if (destroyed) return;
        verifying = false;
        tokenHandled = false;
        showLoading(false, null);
        if (btnLoginDone != null) btnLoginDone.setEnabled(true);
        if (!diagMode && tvHint != null) tvHint.setText(message);
        toast(message);
    }

    // ------------------------------------------------------------------
    // window.open 弹窗
    // ------------------------------------------------------------------

    /**
     * window.open 兜底：创建一个<b>真正挂在视图层级上</b>的 1x1 WebView 承接弹窗。
     *
     * <p>未 attach 的 WebView 既不渲染也不派发 JS 执行，第三方登录的授权页会「点了没反应」。
     * 1x1 是为了不干扰主页面布局；弹窗里的导航仍会转交主 WebView 展示。</p>
     *
     * <p><b>注意{@code window.open(url)} 的首个 URL 由系统直接加载进新窗口，不经过
     * {@code shouldOverrideUrlLoading}</b>，只用那个回调会让授权页整页渲染在这块 1x1 的
     * 不可见 WebView 里 —— 所以必须靠 {@code onPageStarted} 在首跳时就把 URL 转交主
     * WebView，并立刻 {@code stopLoading()} 避免两边重复下载。</p>
     */
    private boolean openPopupInSelf(Message resultMsg) {
        if (resultMsg == null || !(resultMsg.obj instanceof WebView.WebViewTransport)) return false;
        try {
            destroyPopup();
            WebView popup = new WebView(this);
            WebSettings ps = popup.getSettings();
            ps.setJavaScriptEnabled(true);
            ps.setDomStorageEnabled(true);
            ps.setSupportMultipleWindows(false);
            ps.setJavaScriptCanOpenWindowsAutomatically(true);
            try {
                ps.setAllowFileAccess(false);
            } catch (Throwable ignored) {
            }
            try {
                ps.setAllowContentAccess(false);
            } catch (Throwable ignored) {
            }
            try {
                if (webView != null) {
                    ps.setUserAgentString(webView.getSettings().getUserAgentString());
                }
            } catch (Throwable ignored) {
            }
            try {
                CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true);
            } catch (Throwable ignored) {
            }

            popup.setLayoutParams(new ViewGroup.LayoutParams(1, 1));
            ViewGroup root = (ViewGroup) findViewById(android.R.id.content);
            if (root != null) root.addView(popup);

            popup.setWebViewClient(new WebViewClient() {
                /** 首跳（window.open 的 URL）只会走这里，不抢就永远看不见。 */
                @Override
                public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                    loadInMainWebView(url);
                    try {
                        view.stopLoading();
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    loadInMainWebView(url);
                    return true;
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    String url = request == null || request.getUrl() == null
                            ? null : request.getUrl().toString();
                    loadInMainWebView(url);
                    return true;
                }
            });
            popupView = popup;
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(popup);
            resultMsg.sendToTarget();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 把 popup 里的 URL 转交主 WebView。
     *
     * <p>只放行 http/https，避免 intent:// 之类的协议把页面带飞。同一 URL 在
     * {@link #HANDOFF_DEDUPE_MS} 内只转交一次 —— {@code onPageStarted} 与
     * {@code shouldOverrideUrlLoading} 会对同一次跳转各触发一次，主 WebView 若被连续
     * {@code loadUrl} 两次会白白重下一遍页面。</p>
     */
    private void loadInMainWebView(String url) {
        if (url == null || destroyed) return;
        if (!url.startsWith("http://") && !url.startsWith("https://")) return;
        long now = System.currentTimeMillis();
        if (url.equals(lastHandoffUrl) && now - lastHandoffAt < HANDOFF_DEDUPE_MS) return;
        lastHandoffUrl = url;
        lastHandoffAt = now;
        try {
            if (webView != null) webView.loadUrl(url);
        } catch (Throwable ignored) {
        }
    }

    private void destroyPopup() {
        if (popupView != null) {
            try {
                popupView.stopLoading();
            } catch (Throwable ignored) {
            }
            try {
                ViewGroup parent = popupView.getParent() instanceof ViewGroup
                        ? (ViewGroup) popupView.getParent() : null;
                if (parent != null) parent.removeView(popupView);
            } catch (Throwable ignored) {
            }
            try {
                popupView.destroy();
            } catch (Throwable ignored) {
            }
            popupView = null;
        }
    }

    // ------------------------------------------------------------------
    // 旧内核 JS 语法降级
    // ------------------------------------------------------------------

    /** 从 {@link #coreVersion} 里取主版本号；解析失败返回 -1。 */
    private int coreMajor() {
        try {
            if (coreVersion == null || coreVersion.length() == 0) return -1;
            int dot = coreVersion.indexOf('.');
            String major = dot > 0 ? coreVersion.substring(0, dot) : coreVersion;
            return Integer.parseInt(major.trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 把 Chrome 84+ 语法降级为 ES2020，供旧内核 WebView 使用。
     *
     * <p>只做两件事，都是纯文本等价改写（语义不变）：</p>
     * <ol>
     *   <li>类私有成员 {@code #name} → {@code _$name}：{@code #} 是 Chrome 84+ 的私有标识符，
     *       旧内核解析到它就 SyntaxError；改成普通标识符后行为等价（该 chunk 内 53 个标识符
     *       全是单字母，且与 CSS 十六进制颜色无冲突，398 处替换一一对应）。</li>
     *   <li>逻辑赋值 {@code a ??= b()} → {@code a ?? (a = b())}：{@code ??=} 需 Chrome 85+，
     *       展开成 ES2020 的 {@code ??} + 赋值后语义完全一致。</li>
     * </ol>
     *
     * <p>改写后仍发现 {@code ??=} / {@code ||=} / {@code &&=} / {@code static{} 残留时，
     * 记一条诊断（说明规则没覆盖到），但照常返回改写结果。</p>
     *
     * @param src 原始 JS 文本（可为 null）
     * @return 降级后的文本；入参为空或无相关语法时原样返回
     */
    static String downgradeJs(String src) {
        if (src == null || src.length() == 0) return src;
        // 观测点（只记录、不改写）：字符串字面量里出现 '#' 前缀（如 '#root'）时，
        // 下面的机械替换可能误伤选择器。当前 chunk 已验证无冲突，这里只留信号给下一轮诊断。
        try {
            if (src.indexOf("'#") >= 0 || src.indexOf("\"#") >= 0) {
                recordJsPatchNote("string-hash");
            }
        } catch (Throwable ignored) {
        }
        // 快速短路：不含私有成员与逻辑赋值语法，直接放过
        if (src.indexOf('#') < 0 && src.indexOf("??=") < 0) return src;

        String out = src;
        try {
            // #name -> _$name（$ 在替换串里是特殊字符，必须写成 \\$）
            Matcher m1 = Pattern.compile("#([A-Za-z_$][A-Za-z0-9_$]*)(?![A-Za-z0-9_$])")
                    .matcher(out);
            out = m1.replaceAll("_\\$$1");
        } catch (Throwable ignored) {
        }
        try {
            // a ??= b() -> a ?? (a = b())；前面不能紧跟标识符字符，避免误伤 xxx??
            Matcher m2 = Pattern.compile(
                    "(?<![\\w$.])([A-Za-z_$][A-Za-z0-9_$.]*)\\s*\\?\\?=\\s*"
                            + "([A-Za-z_$][A-Za-z0-9_$.]*\\(\\))").matcher(out);
            out = m2.replaceAll("$1??($1=$2)");
        } catch (Throwable ignored) {
        }

        if (out.indexOf("??=") >= 0) recordJsPatchNote("residual:??=");
        if (out.indexOf("||=") >= 0) recordJsPatchNote("residual:||=");
        if (out.indexOf("&&=") >= 0) recordJsPatchNote("residual:&&=");
        if (out.indexOf("static{") >= 0) recordJsPatchNote("residual:static{");
        return out;
    }

    /**
     * 下载并降级目标 chunk（运行在 WebView 后台线程，禁止碰 UI）。
     *
     * @param url chunk 地址
     * @return 改写后的 UTF-8 字节；失败返回 null（调用方据此回退到正常加载）
     */
    private byte[] fetchAndDowngrade(String url) {
        if (url == null || url.length() == 0) return null;
        byte[] cached = jsPatchCache.get(url);
        if (cached != null && cached.length > 0) return cached;

        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(JS_PATCH_TIMEOUT_MS);
            conn.setReadTimeout(JS_PATCH_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            // 必须明文：gzip 压缩流无法直接做文本改写
            conn.setRequestProperty("Accept-Encoding", "identity");
            if (webUa != null && webUa.length() > 0) {
                conn.setRequestProperty("User-Agent", webUa);
            }
            // 国内 CDN 普遍做防盗链：缺 Referer 会 403，或回 200 但内容是 HTML 错误页
            conn.setRequestProperty("Referer", "https://www.trae.cn/");
            if (conn.getResponseCode() != 200) return null;

            // 编码 / 类型兜底：CDN 可能忽略 Accept-Encoding 仍回 gzip（读出来是乱码），
            // 也可能回 200 的 HTML 错误页。两种情况都必须放弃拦截，让 WebView 原生加载，
            // 否则乱码会被当 JS 交回去，还错误地标记成 hit。
            if (!isIdentityEncoding(conn.getContentEncoding())) return null;
            if (isHtmlContent(conn.getContentType())) return null;

            in = conn.getInputStream();
            String src = readUtf8(in);
            if (src == null || src.length() == 0) return null;

            String out = downgradeJs(src);
            if (out == null || out.length() == 0) return null;
            byte[] bytes = out.getBytes("UTF-8");
            if (bytes.length == 0) return null;
            jsPatchCache.put(url, bytes);
            return bytes;
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 响应是否未压缩。{@code Content-Encoding} 为空或 {@code identity} 才算安全。
     *
     * @param enc 响应头值，可为 null
     * @return true 表示可以按明文读取
     */
    private static boolean isIdentityEncoding(String enc) {
        if (enc == null) return true;
        String v = enc.trim().toLowerCase(java.util.Locale.US);
        return v.length() == 0 || "identity".equals(v);
    }

    /**
     * 响应是否其实是 HTML（CDN 防盗链 / 错误页的典型表现）。
     *
     * <p>只把明确含 {@code text/html} 的拦下来：{@code text/javascript}、
     * {@code application/octet-stream} 等都放行（不同 CDN 给的 MIME 不统一）。</p>
     *
     * @param ctype 响应头值，可为 null
     * @return true 表示内容不可用，应放弃拦截
     */
    private static boolean isHtmlContent(String ctype) {
        if (ctype == null) return false;
        return ctype.toLowerCase(java.util.Locale.US).indexOf("text/html") >= 0;
    }

    /** 按 UTF-8 读完整个流（chunk 是纯 ASCII+UTF-8 文本，大小几十 KB）。 */
    private static String readUtf8(InputStream in) {
        if (in == null) return "";
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder(64 * 1024);
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 记一条降级诊断（最多 3 条，去重）。 */
    static void recordJsPatchNote(String note) {
        if (note == null || note.length() == 0) return;
        try {
            synchronized (JS_PATCH_NOTES) {
                if (JS_PATCH_NOTES.size() >= 3) return;
                if (!JS_PATCH_NOTES.contains(note)) JS_PATCH_NOTES.add(note);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 记一条命中并改写成功的 chunk 文件名（最多 {@link #JS_PATCH_MAX_URLS} 条）。 */
    private void recordJsPatchUrl(String url) {
        if (url == null || url.length() == 0) return;
        try {
            String name = url;
            int cut = name.indexOf('?');
            if (cut >= 0) name = name.substring(0, cut);
            int slash = name.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < name.length()) name = name.substring(slash + 1);
            synchronized (jsPatchUrls) {
                if (jsPatchUrls.contains(name)) return;
                if (jsPatchUrls.size() >= JS_PATCH_MAX_URLS) return;
                jsPatchUrls.add(name);
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 自诊断
    // ------------------------------------------------------------------

    /** 从 WebView 默认 UA 里提取内核版本，取不到返回 "unknown"。 */
    private String detectCoreVersion() {
        try {
            String ua = WebSettings.getDefaultUserAgent(this);
            if (ua != null) {
                Matcher m = Pattern.compile("Chrome/([0-9.]+)").matcher(ua);
                if (m.find()) {
                    String v = m.group(1);
                    if (v != null && v.length() > 0) return v;
                }
            }
        } catch (Throwable ignored) {
        }
        return "unknown";
    }

    /**
     * 启动水合探针周期：首次在页面加载完成后 5s，之后每 6s 复测，最多 3 次。
     *
     * <p>只测一次太脆弱 —— 网络慢时 bundle 可能 15s 才执行完，5s 一次采样会把正常页面
     * 判成 {@code ssr} 并锁死诊断模式。这里用「多次采样、最后一次仍失败才下结论」的策略，
     * 同时避免文案「闪一下就变脸」。</p>
     */
    private void startHydrateProbe() {
        if (destroyed || hydrateFinished) return;
        // 已有待执行的探针，或已有结果在途：保持原周期，不重置计数
        if (hydrateTask != null || hydrateInFlight) return;
        hydrateTry = 0;
        hydrateVerdict = "";
        postHydrateProbe(HYDRATE_PROBE_DELAY_MS);
    }

    /** 排一次探针（delay 后执行），并arm 一个回调超时兜底。 */
    private void postHydrateProbe(long delay) {
        cancelHydrateProbe();
        hydrateTask = new Runnable() {
            @Override
            public void run() {
                hydrateTask = null;
                // 每次复测前都要重新确认环境没变（页面已关闭 / 已拿到 token / 正在校验）
                if (destroyed || tokenHandled || verifying || hydrateFinished) return;
                if (webView == null) return;
                hydrateInFlight = true;
                armHydrateTimeout();
                try {
                    webView.evaluateJavascript(HYDRATE_JS, new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            onHydrateResult(unquoteJs(value));
                        }
                    });
                } catch (Throwable ignored) {
                    hydrateInFlight = false;
                    cancelHydrateTimeout();
                }
            }
        };
        ui.postDelayed(hydrateTask, delay);
    }

    /**
     * 回调超时兜底：页面正在导航时 {@code onReceiveValue} 可能永远不回来，
     * 若不兜底 {@link #hydrateInFlight} 会永久为 true，探针从此静默失效。
     */
    private void armHydrateTimeout() {
        cancelHydrateTimeout();
        hydrateTimeoutTask = new Runnable() {
            @Override
            public void run() {
                hydrateTimeoutTask = null;
                if (destroyed || !hydrateInFlight) return;
                // 回调丢了：按「本次采样失败」推进，不改变已有结论
                hydrateInFlight = false;
                onHydrateProbeLost();
            }
        };
        ui.postDelayed(hydrateTimeoutTask, HYDRATE_CALLBACK_TIMEOUT_MS);
    }

    private void cancelHydrateTimeout() {
        if (hydrateTimeoutTask != null) {
            ui.removeCallbacks(hydrateTimeoutTask);
            hydrateTimeoutTask = null;
        }
    }

    /** 一次采样没拿到结果（回调丢失）：等同于失败采样，继续复测或静默收敛。 */
    private void onHydrateProbeLost() {
        if (destroyed || hydrateFinished) return;
        if (tokenHandled || verifying) {
            hydrateFinished = true;
            return;
        }
        hydrateTry++;
        if (hydrateTry < HYDRATE_MAX_TRIES) {
            postHydrateProbe(HYDRATE_RETRY_MS);
            return;
        }
        // 三次采样全部拿不到结果：不下结论，保持默认提示（绝不误报）
        hydrateFinished = true;
    }

    private void cancelHydrateProbe() {
        cancelHydrateTimeout();
        if (hydrateTask != null) {
            ui.removeCallbacks(hydrateTask);
            hydrateTask = null;
        }
    }

    /**
     * 水合探针结论处理（UI 线程）。
     *
     * <p>采样规则：</p>
     * <ul>
     *   <li>任一次 {@code ok}：立刻收敛，退出诊断模式、按钮还原、提示还原，不再复测；</li>
     *   <li>未到第 {@link #HYDRATE_MAX_TRIES} 次：先不展示任何结论（避免闪变），6s 后复测；</li>
     *   <li>第 3 次仍失败：按最终结论进诊断模式 —— {@code ssr} → {@code login_js_dead}，
     *       {@code noevents} → {@code login_js_no_events}；</li>
     *   <li>{@code noroot} / {@code err} / null：结构变化或探针异常，不下结论，保持默认提示。</li>
     * </ul>
     */
    private void onHydrateResult(String verdict) {
        hydrateInFlight = false;
        cancelHydrateTimeout();
        if (destroyed || hydrateFinished) return;
        if (tokenHandled || verifying) {
            // 已经拿到 token 在走校验流程，诊断没有意义，直接收敛
            hydrateFinished = true;
            return;
        }
        hydrateTry++;
        hydrateVerdict = verdict == null ? "" : verdict;

        if ("ok".equals(verdict)) {
            hydrateFinished = true;
            applyHydrateOk();
            return;
        }
        if (hydrateTry < HYDRATE_MAX_TRIES) {
            // 中途失败不下结论：可能只是 bundle 还没跑完
            postHydrateProbe(HYDRATE_RETRY_MS);
            return;
        }
        hydrateFinished = true;
        if ("ssr".equals(verdict)) {
            applyHydrateDiag(R.string.login_js_dead, "ssr");
            return;
        }
        if ("noevents".equals(verdict)) {
            applyHydrateDiag(R.string.login_js_no_events, "noevents");
            return;
        }
        // "noroot" / "err" / "" / null：不误报
    }

    /** 探针确认 React 已接管：退出诊断模式并还原提示（按钮文案全程不变）。 */
    private void applyHydrateOk() {
        boolean wasDiag = diagMode;
        diagMode = false;
        diagVerdict = "";
        // 只在没进过诊断模式时才回写默认提示，避免覆盖用户正在看的结论
        if (!wasDiag && tvHint != null) tvHint.setText(R.string.login_hint_auto);
    }

    /**
     * 探针确认页面脚本未生效：进诊断模式。
     *
     * <p>按钮文案<b>不改</b>，仍然是「我已完成登录」，保证手动重试入口常在；
     * 复制诊断改由长按按钮触发，并在提示末尾追加一句引导。</p>
     */
    private void applyHydrateDiag(int hintRes, String verdict) {
        diagMode = true;
        diagVerdict = verdict;
        if (tvHint != null) {
            tvHint.setText(getString(hintRes, coreVersion)
                    + getString(R.string.login_diag_longpress_tip));
        }
    }

    /**
     * 剥掉 {@code evaluateJavascript} 返回值的 JSON 字符串包装。
     *
     * <p>返回值形如 {@code "ssr"}（带首尾双引号），也可能是 {@code null} / {@code "null"}。
     * 不剥引号会把 {@code "ssr"} 当成另一个值，导致永远命中不了诊断分支。</p>
     *
     * @param raw 原生回调的原始字符串（可能为 null）
     * @return 去掉引号后的值；无有效值时返回 null
     */
    static String unquoteJs(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.length() == 0 || "null".equals(s)) return null;
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            s = s.substring(1, s.length() - 1);
        }
        if (s.length() == 0) return null;
        return s;
    }

    /** 记录一条子资源失败（host + path），列表只留前 3 条。 */
    private void recordResourceFail(WebResourceRequest request) {
        resourceFailCount++;
        if (resourceFails.size() >= MAX_RESOURCE_FAILS) return;
        try {
            String host = request.getUrl() == null ? "" : request.getUrl().getHost();
            String path = request.getUrl() == null ? "" : request.getUrl().getPath();
            String entry = (host == null ? "" : host) + (path == null ? "" : path);
            resourceFails.add(abbreviate(entry, 120));
        } catch (Throwable ignored) {
        }
    }

    /** 把诊断信息复制到剪贴板（内核版本 / JS 错误 / 资源失败 / 当前 URL）。 */
    private void copyDiagnostics() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null) return;
            cm.setPrimaryClip(ClipData.newPlainText("trae-login-diag", buildDiagText()));
            toast(getString(R.string.login_diag_copied));
        } catch (Throwable ignored) {
        }
    }

    /** 组装诊断文本（英文标签，便于用户原样粘贴给开发者）。 */
    private String buildDiagText() {
        StringBuilder sb = new StringBuilder();
        sb.append("TraeCheckin login diag\n");
        sb.append("webview_core=Chrome/").append(coreVersion).append('\n');
        // 降级链路自证：off=内核够新没启用，on=启用但一次都没命中（拦截规则可能没匹配上），
        // hit=已拦截并改写返回，fail=命中后下载/改写失败
        sb.append("js_patch=").append(jsPatchState).append('\n');
        sb.append("js_patch_urls=").append(joinDiag(jsPatchUrls, JS_PATCH_MAX_URLS)).append('\n');
        sb.append("js_patch_notes=").append(joinDiag(JS_PATCH_NOTES, 3)).append('\n');
        sb.append("hydrate_verdict=").append(hydrateVerdict).append('\n');
        sb.append("hydrate_tries=").append(hydrateTry).append('\n');
        sb.append("diag_verdict=").append(diagVerdict).append('\n');
        sb.append("page_url=").append(currentPageUrl()).append('\n');
        sb.append("resource_fail_count=").append(resourceFailCount).append('\n');
        sb.append("js_errors:\n");
        appendDiagList(sb, consoleErrors);
        sb.append("resource_fails:\n");
        appendDiagList(sb, resourceFails);
        return sb.toString();
    }

    private static void appendDiagList(StringBuilder sb, List<String> list) {
        if (list == null || list.isEmpty()) {
            sb.append("- (none)\n");
            return;
        }
        int n = Math.min(list.size(), DIAG_ITEMS);
        for (int i = 0; i < n; i++) {
            sb.append("- ").append(list.get(i)).append('\n');
        }
    }

    /**
     * 把（可能被后台线程并发写的）列表拼成一行 CSV。
     *
     * @param list 目标列表，可为 null
     * @param max  最多输出几条
     * @return 逗号分隔的一行；空列表返回 "(none)"
     */
    private static String joinDiag(List<String> list, int max) {
        if (list == null || list.isEmpty()) return "(none)";
        StringBuilder sb = new StringBuilder();
        synchronized (list) {
            int n = Math.min(list.size(), Math.max(1, max));
            for (int i = 0; i < n; i++) {
                String item = list.get(i);
                if (item == null || item.length() == 0) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(item);
            }
        }
        return sb.length() == 0 ? "(none)" : sb.toString();
    }

    /**
     * 当前页面位置，<b>只保留 host + path</b>。
     *
     * <p>诊断信息会被用户粘贴到聊天窗口，第三方登录回调 URL 的 query 里可能带
     * {@code code} / {@code state} 等一次性凭证，绝不能带出去。</p>
     */
    private String currentPageUrl() {
        String host = "www.trae.cn";
        String path = "/authorization";
        try {
            if (webView != null) {
                String u = webView.getUrl();
                if (u != null && u.length() > 0) {
                    android.net.Uri uri = android.net.Uri.parse(u);
                    String h = uri.getHost();
                    String p = uri.getPath();
                    if (h != null && h.length() > 0) host = h;
                    if (p != null && p.length() > 0) path = p;
                }
            }
        } catch (Throwable ignored) {
        }
        return host + path;
    }

    /** WebView 不可用：禁用按钮并给出明确提示，绝不闪退。 */
    private void showWebViewBroken() {
        stopPolling();
        tokenHandled = true;
        if (btnLoginDone != null) btnLoginDone.setEnabled(false);
        showLoading(false, null);
        if (tvHint != null) tvHint.setText(R.string.login_webview_broken);
        toast(getString(R.string.login_webview_broken));
    }

    /** 主文档加载失败：停止轮询并给出可读错误码，避免用户对着白屏等 5 分钟。 */
    private void onPageLoadFailed(int code) {
        if (destroyed) return;
        stopPolling();
        cancelPageWatchdog();
        showLoading(false, null);
        String msg = getString(R.string.login_page_error, code);
        if (tvHint != null) tvHint.setText(msg);
        toast(msg);
    }

    // ------------------------------------------------------------------
    // Cookie 探针（原生，不依赖页面 JS）
    // ------------------------------------------------------------------

    /**
     * 启动 cookie 探针（幂等重排）：每 {@link #PROBE_INTERVAL_MS} 读一次 cookie，最长 5 分钟。
     *
     * <p>每次进入都重置 5 分钟计时与 30s 提示起点，这样「超时后再点按钮」不会被旧起点
     * 顶成秒超时。</p>
     */
    private void startCookieProbe() {
        if (tokenHandled || destroyed || webView == null) return;
        stopPolling();
        polling = true;
        pollStartAt = System.currentTimeMillis();
        slowHintShown = false;
        pollTask = new Runnable() {
            @Override
            public void run() {
                pollTask = null;
                if (!polling || tokenHandled || destroyed) return;
                long elapsed = System.currentTimeMillis() - pollStartAt;
                if (elapsed > POLL_TIMEOUT_MS) {
                    polling = false;
                    showLoading(false, null);
                    // 诊断结论不能被「登录超时」冲掉：只 stopPolling + toast
                    if (!diagMode && tvHint != null) tvHint.setText(R.string.login_timeout);
                    toast(getString(R.string.login_timeout));
                    return;
                }
                // 30s 还没拿到就早点给出可行动的提示，但探针继续到 5 分钟
                if (!slowHintShown && elapsed > SLOW_HINT_MS) {
                    slowHintShown = true;
                    if (!diagMode && tvHint != null) tvHint.setText(R.string.login_hint_wait);
                }
                pollOnce();
                if (polling && !tokenHandled && !destroyed) {
                    pollTask = this;
                    ui.postDelayed(this, PROBE_INTERVAL_MS);
                }
            }
        };
        ui.postDelayed(pollTask, PROBE_INTERVAL_MS);
    }

    private void stopPolling() {
        polling = false;
        if (pollTask != null) {
            ui.removeCallbacks(pollTask);
            pollTask = null;
        }
    }

    /**
     * 执行一次探测：cookie 内容与基线不同就尝试换 token（内容判据，不用长度）。
     *
     * <p>为什么不用长度：登录时旧匿名 cookie 可能被<b>替换</b>，会话值与旧值等长甚至更短，
     * 用「变长」判据会漏判；而基线为空时长度闸又会失效，导致每 2s 打一次接口。</p>
     *
     * <p>闸门顺序：cookie 非空 → 与基线内容不同 → 首次拿到 cookie 时先试一次并立刻固化基线
     * → 3s 节流 → 失败退避（{@link #exchangeBackoffUntil}）。</p>
     */
    private void pollOnce() {
        if (webView == null || tokenHandled || destroyed) return;
        final String cookie = captureCookie();
        if (cookie.length() == 0) return;
        if (cookie.equals(cookieBaseline)) return;
        if (cookieBaseline.length() == 0) {
            // 首次拿到 cookie：可能是「已有登录态」（站点会直接跳 /agent），先试一次；
            // 无论成败都把基线固化为当前值，避免空基线导致每轮都被判成「变了」。
            exchangeTokenByCookie(cookie);
            cookieBaseline = cookie;
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastExchangeAt < EXCHANGE_THROTTLE_MS) return;
        if (now < exchangeBackoffUntil) return;
        exchangeTokenByCookie(cookie);
    }

    /**
     * 自动路径入口：在途去重，直接受 {@link #exchangeInFlight} 约束。
     *
     * @param cookie 合并去重后的 cookie 串（可为 null / 空，此时直接忽略）
     */
    private void exchangeTokenByCookie(final String cookie) {
        exchangeTokenByCookie(cookie, false);
    }

    /**
     * 子线程用 cookie 换 JWT（{@code GetUserToken}，只认 HTTP 200 + {@code Result.Token}）。
     *
     * <p>换不到就静默等下一轮 —— 这里刻意不 toast：未登录时每次都会失败，弹提示就是轰炸。
     * 失败<b>不前移基线</b>（cookie 变了才值得再试），但把 {@link #exchangeBackoffUntil} 推到
     * now + {@link #EXCHANGE_FAIL_BACKOFF_MS}，未登录时最多 15s 打一次；成功则清零退避并
     * 前移基线。</p>
     *
     * <p>去重：只有「时间」节流挡不住网络悬挂（连接 + 读各 15s 超时），轮询会堆叠出近 10 个
     * 同 cookie 请求，所以再加一道 {@link #exchangeInFlight} 在途闸。手动点击（{@code force}）
     * 可以穿透这道闸做一次新尝试；但已有请求在途时不重复发起，只提示「正在校验」，
     * 避免同一 cookie 打两次。</p>
     *
     * @param cookie 合并去重后的 cookie 串（可为 null / 空，此时直接忽略）
     * @param force  是否为用户手动触发（true 时忽略退避与节流，仅受在途闸约束）
     */
    private void exchangeTokenByCookie(final String cookie, final boolean force) {
        if (cookie == null || cookie.length() == 0) return;
        if (exchangeInFlight) {
            // 在途时不重复发起：自动路径静默跳过，手动路径给个可读反馈
            if (force) toast(getString(R.string.login_verifying));
            return;
        }
        lastExchangeAt = System.currentTimeMillis();
        final ExecutorService ex = executor;
        if (ex == null || ex.isShutdown()) return;
        exchangeInFlight = true;
        try {
            ex.execute(new Runnable() {
                @Override
                public void run() {
                    String raw = null;
                    try {
                        raw = TraeApi.refreshToken(cookie);
                    } catch (Throwable t) {
                        raw = null;
                    }
                    final boolean ok = raw != null && raw.length() >= MIN_TOKEN_LEN;
                    final String jwt = ok ? raw : "";
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            // 先复位在途标志：即使页面已销毁也要放行，否则永远不会再换 token
                            exchangeInFlight = false;
                            if (destroyed) return;
                            if (!ok) {
                                // 失败退避：不做基线前移，只压住下一次自动尝试的频率
                                exchangeBackoffUntil =
                                        System.currentTimeMillis() + EXCHANGE_FAIL_BACKOFF_MS;
                                return;
                            }
                            if (tokenHandled) return;
                            // 成功才前移基线：失败时基线不动，避免把「未登录」的 cookie 当成已登录
                            exchangeBackoffUntil = 0L;
                            cookieBaseline = cookie;
                            handleToken(jwt);
                        }
                    });
                }
            });
        } catch (Throwable ignored) {
            exchangeInFlight = false;
        }
    }

    /** 手动按钮：重启一次官方授权流程，不依赖 cookie 探针。 */
    private void onDoneClicked() {
        if (verifying) {
            toast(getString(R.string.login_verifying));
            return;
        }
        startPageWatchdog();
        startNewAuthorization();
    }

    /** 3s 后仍未换到 token 就提示「还没检测到登录状态」，并引导复制诊断信息。 */
    private void scheduleNotYetHint() {
        cancelNotYetHint();
        notYetTask = new Runnable() {
            @Override
            public void run() {
                notYetTask = null;
                if (destroyed || tokenHandled || verifying || diagMode) return;
                if (tvHint != null) tvHint.setText(R.string.login_hint_not_yet);
            }
        };
        ui.postDelayed(notYetTask, NOT_YET_HINT_MS);
    }

    private void cancelNotYetHint() {
        if (notYetTask != null) {
            ui.removeCallbacks(notYetTask);
            notYetTask = null;
        }
    }

    /** 20s 看门狗：页面迟迟不 finished 只做提示，不中断、不弹窗。 */
    private void startPageWatchdog() {
        cancelPageWatchdog();
        watchdogTask = new Runnable() {
            @Override
            public void run() {
                if (destroyed || pageFinished) return;
                if (tvHint != null) tvHint.setText(R.string.login_page_slow);
            }
        };
        ui.postDelayed(watchdogTask, PAGE_WATCHDOG_MS);
    }

    private void cancelPageWatchdog() {
        if (watchdogTask != null) {
            ui.removeCallbacks(watchdogTask);
            watchdogTask = null;
        }
    }

    // ------------------------------------------------------------------
    // 校验与落库
    // ------------------------------------------------------------------

    /** 收到 token 的唯一入口：去重 → 停探针 → 停页面 → 子线程校验。 */
    private void handleToken(String jwt) {
        if (destroyed || tokenHandled) return;
        if (jwt == null || jwt.length() < MIN_TOKEN_LEN) return;
        if (jwt.equals(lastFailedToken)) return;   // 同一个坏 token 不重复校验
        tokenHandled = true;
        verifyingToken = jwt;
        stopPolling();
        cancelNotYetHint();
        try {
            if (webView != null) webView.stopLoading();
        } catch (Throwable ignored) {
        }
        startVerify(jwt);
    }

    /** 拿到 JWT：停页面加载 -> 显示校验中 -> 子线程校验。 */
    private void startVerify(final String token) {
        // 守卫保证只校验一次
        if (destroyed || verifying) return;
        verifying = true;
        // 登录成功后站点会跳到 /agent（网页版 IDE，很重），手机上没必要加载
        stopWebLoading();
        showLoading(true, getString(R.string.login_verifying));
        if (btnLoginDone != null) btnLoginDone.setEnabled(false);

        final ExecutorService ex = executor;
        if (ex == null || ex.isShutdown()) {
            LoginResult res = new LoginResult();
            res.ok = false;
            res.message = getString(R.string.login_err_unknown);
            onVerifyFinished(res);
            return;
        }
        try {
            ex.execute(new Runnable() {
                @Override
                public void run() {
                    verifyAndSave(token);
                }
            });
        } catch (Throwable t) {
            verifying = false;
            LoginResult res = new LoginResult();
            res.ok = false;
            res.message = getString(R.string.login_err_unknown);
            onVerifyFinished(res);
        }
    }

    private void stopWebLoading() {
        if (webView != null) {
            try {
                webView.stopLoading();
            } catch (Throwable ignored) {
            }
        }
    }

    private void verifyAndSave(String token) {
        // 关键：status / claim 必须用「授权链接里注册给上游」的那个设备号。
        // 早期版本在这里另取旧账号设备号或随机新号，导致上游设备校验不匹配，
        // 签到稳定返回 9074「当前参与用户太多，请稍后再试」。
        final AuthBundle auth = pendingAuth;
        final String cookie = auth.cookie == null ? "" : auth.cookie;
        final String deviceId = AccountStore.isValidDeviceId(auth.deviceId)
                ? auth.deviceId : AccountStore.newDeviceId();
        final String uid = auth.uid.length() > 0
                ? auth.uid : AccountStore.uidFromToken(token);

        LoginResult res = new LoginResult();
        final android.content.Context app = getApplicationContext();
        final AccountStore store = AccountStore.get(app);
        try {
            // 重复添加保护（重新登录时不判断）：优先用回调里的稳定 UserID 查重。
            // 每次 ExchangeToken 签发的 token 整串都不同，整串比对必然漏判。
            if (replaceId == null) {
                boolean dup = uid.length() > 0 && store.findByUid(uid) != null;
                if (!dup && store.findToken(token) != null) dup = true;
                if (dup) {
                    res.ok = false;
                    res.message = getString(R.string.login_err_duplicate);
                    postResult(res);
                    return;
                }
            }

            JSONObject resp;
            try {
                resp = TraeApi.status(token, deviceId);
            } catch (Throwable t) {
                res.ok = false;
                String detail = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                res.message = getString(R.string.login_err_network, detail);
                postResult(res);
                return;
            }

            if (resp == null) {
                res.ok = false;
                res.message = getString(R.string.login_err_unknown);
                postResult(res);
                return;
            }
            if (resp.has("__error")) {
                res.ok = false;
                res.message = getString(R.string.login_err_network, resp.optString("__error"));
                postResult(res);
                return;
            }
            // 关键防护：HTTP 成功但响应不是合法 JSON 时 request() 只塞 __text、不带 code，
            // 若继续往下走 optInt("code", 0) 会得到默认值 0，被误判成「登录成功」。
            if (resp.has("__text")) {
                res.ok = false;
                res.message = getString(R.string.login_err_server_unexpected,
                        abbreviate(resp.optString("__text"), 80));
                postResult(res);
                return;
            }

            JSONObject d = TraeApi.unwrap(resp);
            int http = resp.optInt("__http", 0);
            int code = resp.optInt("code", d.optInt("code", 0));
            String serverMsg = d.optString("message", resp.optString("message", ""));

            if (http == 401 || code == 1001) {
                res.ok = false;
                res.message = getString(R.string.login_err_invalid);
                postResult(res);
                return;
            }
            // 9004（设备未注册）/ 9074（设备校验未通过）：用户已完成登录，账号必须存下来
            // （标记失效），否则一次白登录、列表里什么都没有，也没法点「重新登录」。
            if (code == 9004 || code == 9074) {
                String reason = code == 9004
                        ? getString(R.string.login_err_device)
                        : getString(R.string.login_err_device_rejected);
                String accountId = saveAccount(store, token, deviceId, false, reason, cookie);
                // 落库失败时同样不能报成功（例如 replaceAuth 返回 false）
                if (accountId == null || accountId.length() == 0) {
                    res.ok = false;
                    res.message = getString(R.string.login_err_unknown);
                    postResult(res);
                    return;
                }
                res.ok = true;
                res.accountId = accountId;
                res.message = getString(R.string.login_saved_need_device);
                postResult(res);
                return;
            }
            // 401 已按「凭证无效」处理；其余 4xx/5xx 统一按服务端异常提示（带 http 码）
            if (http >= 400 && http != 401) {
                res.ok = false;
                res.message = getString(R.string.login_err_server_http, http);
                postResult(res);
                return;
            }
            if (code != 0) {
                res.ok = false;
                res.message = serverMsg.length() > 0
                        ? serverMsg : getString(R.string.login_err_code, code);
                postResult(res);
                return;
            }

            // 校验通过 -> 落库
            boolean replace = replaceId != null;
            String accountId = saveAccount(store, token, deviceId, true, "", cookie);
            // 落库失败（例如 replaceToken 返回 false）绝不能报成功：
            // 否则界面提示「已添加」，列表里却什么都没有。
            if (accountId == null || accountId.length() == 0) {
                res.ok = false;
                res.message = getString(R.string.login_err_unknown);
                postResult(res);
                return;
            }
            res.ok = true;
            res.accountId = accountId;
            if (replace) {
                res.message = getString(R.string.login_relogin_ok);
            } else {
                res.message = getString(R.string.login_ok_fmt,
                        AccountStore.displayName(store.byId(accountId)));
            }
            postResult(res);
        } catch (Throwable t) {
            res.ok = false;
            res.message = getString(R.string.login_err_unknown);
            postResult(res);
        }
    }

    /**
     * 落库（新增或替换账号），写入<b>完整 SOLO OAuth 凭证</b>。
     *
     * <p>只存 token 是不够的：没有 refreshToken 就无法在 401/1001 时自愈续期，
     * 没有 machineId / 一致的 deviceId 就会在签到时撞上 9074 / 9004。</p>
     *
     * @param valid   token 是否可用；9004 / 9074 场景存账号但标记失效
     * @param message 失效原因（valid=false 时写入 lastMessage）
     * @param cookie  登录时的网页 cookie（旧账号续期兜底，可为空串）
     * @return 账号 id（失败返回空串）
     */
    private String saveAccount(AccountStore store, String token, String deviceId,
                               boolean valid, String message, String cookie) {
        try {
            final AuthBundle auth = pendingAuth;
            final String ck = cookie == null ? "" : cookie;
            final String uid = auth.uid.length() > 0
                    ? auth.uid : AccountStore.uidFromToken(token);
            final String refresh = auth.refreshToken == null ? "" : auth.refreshToken;
            final long expires = auth.expiresAt;
            final String machine = auth.machineId == null ? "" : auth.machineId;
            final String nick = auth.nickname == null ? "" : auth.nickname.trim();

            if (replaceId != null) {
                if (!store.replaceAuth(replaceId, token, refresh, expires,
                        deviceId, machine, ck, uid)) return "";
                AccountStore.Account a = store.byId(replaceId);
                if (a != null) {
                    if (!valid) {
                        a.tokenValid = false;
                        a.lastMessage = message == null ? "" : message;
                    }
                    // 只替换自动生成的默认别名，不覆盖用户手动改过的名字
                    if (nick.length() > 0 && a.alias != null
                            && a.alias.startsWith("Trae 账号")) {
                        a.alias = nick;
                    }
                    store.update(a);
                }
                return replaceId;
            }
            String alias = nick.length() > 0 ? nick
                    : AccountStore.aliasFromToken(token, store.count() + 1);
            AccountStore.Account a = store.add(token, deviceId, alias);
            a.tokenValid = valid;
            a.lastMessage = message == null ? "" : message;
            a.cookie = ck;
            a.uid = uid;
            a.refreshToken = refresh;
            a.expiresAt = expires;
            a.machineId = machine;
            store.update(a);
            return a.id;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 从 WebView 的 CookieManager 取 trae.cn 域的 cookie 字符串。
     *
     * <p>{@code api.trae.cn} 与 {@code www.trae.cn} 的 cookie 可能不同（一个是接口域、
     * 一个是页面域），按出现顺序去重合并；{@code GetUserToken} 与 cookie 探针都用这份合并串。
     * CookieManager 读的是底层 cookie store，<b>能读到 HttpOnly cookie</b>，所以不需要页面 JS。
     * 任何异常都返回空串，绝不影响登录主流程。</p>
     */
    private String captureCookie() {
        try {
            CookieManager cm = CookieManager.getInstance();
            String wwwCookie = cm.getCookie("https://www.trae.cn");
            String apiCookie = cm.getCookie("https://api.trae.cn");
            LinkedHashSet<String> parts = new LinkedHashSet<String>();
            addCookieParts(parts, wwwCookie);
            addCookieParts(parts, apiCookie);
            StringBuilder sb = new StringBuilder();
            for (String p : parts) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(p);
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 把 {@code "a=1; b=2"} 拆成去重集合，忽略空段。 */
    private static void addCookieParts(Set<String> out, String raw) {
        if (out == null || raw == null || raw.length() == 0) return;
        String[] arr = raw.split(";");
        for (int i = 0; i < arr.length; i++) {
            String s = arr[i] == null ? "" : arr[i].trim();
            if (s.length() > 0) out.add(s);
        }
    }

    private void postResult(final LoginResult res) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                onVerifyFinished(res);
            }
        });
    }

    /** 校验结束（UI 线程）：成功回传结果并关闭，失败留在本页可重试。 */
    private void onVerifyFinished(LoginResult res) {
        if (destroyed || isFinishing()) return;
        verifying = false;
        showLoading(false, null);
        if (btnLoginDone != null) btnLoginDone.setEnabled(true);

        if (res != null && res.ok) {
            toast(res.message);
            Intent data = new Intent();
            data.putExtra(EXTRA_ACCOUNT_ID, res.accountId);
            setResult(RESULT_OK, data);
            finish();
        } else {
            String msg = res == null || res.message == null
                    ? getString(R.string.login_err_unknown) : res.message;
            // 诊断模式下不能覆盖结论（「请更新 Android System WebView…」），否则用户
            // 刚看到可操作的指引就被一句通用错误冲掉；此时只用 toast 报告本次失败。
            if (tvHint != null && !diagMode) tvHint.setText(msg);
            toast(msg);

            // 失败后留在本页：记住坏 token、复位状态，让用户点按钮重新发起官方授权。
            stopPolling();
            lastFailedToken = verifyingToken == null ? "" : verifyingToken;
            tokenHandled = false;
            verifying = false;
            oauthCallbackHandled = false;
            if (tvHint != null && !diagMode) tvHint.setText(R.string.login_hint_retry);
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 截断过长文本用于提示：超过 max 字符时保留前 max 个字符并追加省略号。 */
    static String abbreviate(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.length() <= max) return t;
        return t.substring(0, Math.max(0, max - 1)) + "…";
    }

    private void showLoading(boolean show, String text) {
        if (loadingRow != null) loadingRow.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show && tvLoadingText != null) {
            tvLoadingText.setText(text == null ? getString(R.string.login_loading) : text);
        }
        if (show && progressLoading != null) progressLoading.setVisibility(View.VISIBLE);
    }

    private void toast(String msg) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 生命周期收尾
    // ------------------------------------------------------------------

    @Override
    public void onBackPressed() {
        if (webView != null) {
            try {
                if (webView.canGoBack()) {
                    webView.goBack();
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        stopPolling();
        cancelPageWatchdog();
        cancelHydrateProbe();
        hydrateTry = 0;
        hydrateInFlight = false;
        hydrateFinished = true;
        cancelNotYetHint();
        ui.removeCallbacksAndMessages(null);
        destroyPopup();
        if (executor != null) {
            try {
                executor.shutdownNow();
            } catch (Throwable ignored) {
            }
            executor = null;
        }
        if (webView != null) {
            try {
                ViewGroup parent = (ViewGroup) webView.getParent();
                if (parent != null) parent.removeView(webView);
                webView.stopLoading();
                webView.destroy();
            } catch (Throwable ignored) {
            }
            webView = null;
        }
        super.onDestroy();
    }

    /**
     * 一次 OAuth 回调解析出的完整凭证。
     *
     * <p>UI 线程填好后才 submit 到 executor（{@code ui.post -> ex.execute} 建立
     * happens-before），所以 {@link #verifyAndSave(String)} 在子线程能安全读到。</p>
     *
     * <p>deviceId 必须与 {@link #buildAuthorizationUrl()} 注册给上游的完全一致，
     * 这是避免签到 9074 的关键。</p>
     */
    private static final class AuthBundle {
        String accessToken = "";
        String refreshToken = "";
        long expiresAt = 0L;
        String deviceId = "";
        String machineId = "";
        String uid = "";
        String nickname = "";
        String cookie = "";
    }

    /** 校验结果载体（子线程 -> UI 线程）。 */
    private static final class LoginResult {
        boolean ok = false;
        String message = "";
        String accountId = "";
    }
}
