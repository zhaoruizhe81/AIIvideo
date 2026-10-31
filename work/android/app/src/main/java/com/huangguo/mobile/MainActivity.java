package com.huangguo.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import java.io.IOException;
import android.view.RoundedCorner;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;
import android.window.OnBackInvokedCallback;
import androidx.core.content.FileProvider;
import com.huangguo.mobile.Text;
import com.huangguo.mobile.Text;
import androidx.webkit.WebViewAssetLoader;
import com.parse.ParseUser;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * 主界面。整个应用就这一个 Activity（播放器另有 VlcPlayerActivity，从原生播放器起）。
 *
 * ── 结构 ──
 * 一个撑满的 WebView 加载 assets/app.html，页面本身是纯粹的 HTML/CSS/JS。
 * 原生侧只做四件 WebView 做不到的事：
 *   1. 资源供给   通过 WebViewAssetLoader 把 assets/ 挂到
 *                 https://appassets.androidplatform.net/assets/
 *                 （热更层 2026-10-19 已整块拆除，界面只有 APK 里这一份）
 *   2. 桥接指令   @JavascriptInterface 的 Bridge，前端调 AndroidHost.request()
 *   3. 外观       状态栏/导航栏配色、边到边、返回手势
 *   4. 系统能力   选头像、装 APK、拉起播放器、通知
 *
 * ── 桥指令 ──
 * 40 条，全在 registerCommands() 里注册成 Map<String, Command>。
 * 加一条指令 = 加一行 commands.put(...)，不用碰 dispatch()。
 * 另有 4 条 content-* 留在 dispatch() 里，因为必须在内容源门禁之前执行。
 *
 * ── origin 迁移 ──
 * 老版本用 file:///android_asset/app.html 加载，file:// 的 origin 是 null，
 * 很多 Web API 受限。首次启动时会先加载 file:// 读一次 localStorage
 * （把老数据带过来），然后切到 https://appassets.androidplatform.net 并记
 * originMigrated=true，之后不再走这条路。迁移逻辑在 AnonymousClass1.onPageFinished。
 */
public final class MainActivity extends Activity {
    private static final int AVATAR_PICKER = 7101;
    private static final String TAG = "AIIvideo";

    /** 撞盾后最多外包过盾几次（见 spider/call 的 __shieldPages 循环）。 */
    private static final int SHIELD_RETRY_MAX = 3;
    public static volatile MainActivity host;
    private AccountService account;
    private OnBackInvokedCallback backCallback;
    private float backLeadPx;
    private int backProgressGeneration;
    private boolean backProgressInFlight;
    private boolean backRegistered;
    private int backViewLeft;
    private int backViewWidth;
    private boolean detailSystemBars;
    private boolean migratingOrigin;
    private File pendingUpdateApk;
    private DualContentRepository repository;
    private FrameLayout root;
    private volatile float statusBarInsetDp;
    private AppStore store;
    private UpdateManager updates;
    private WebView webView;
    private final ExecutorService requests = Executors.newFixedThreadPool(4);
    private final ExecutorService localRequests = Executors.newSingleThreadExecutor();
    private final ExecutorService coverRequests = Executors.newFixedThreadPool(2);
    /**
     * JS 异常上报专用。**不蹭上面那三个池子**：
     *
     * <p>{@code requests} 是网络（4 线程），{@code localRequests} 是单线程且用户在等
     * 结果，{@code coverRequests} 是封面解码。一个坏掉的渲染循环能在一秒内灌几十条
     * 异常，塞进任何一条既有池子都会把它顶住 —— 用户看到的是「点什么都没反应」，
     * 而那个症状和真实故障完全分不开。
     *
     * <p>单线程而不是多线程：入队这条路上真正的开销是写 jsonl，ReportQueue 自己
     * 有 synchronized，给多线程只是多一层锁竞争，没有收益。
     */
    private final ExecutorService reports = Executors.newSingleThreadExecutor();
    private final Handler hotHandler = new Handler(Looper.getMainLooper());
    private float pendingBackProgress = -1.0f;
    private final Map<String, Command> commands = new HashMap<>();

    /**
     * 只放行「我们自己的 assets 域」。
     *
     * <p>从 HotUpdateManager 搬过来的，逻辑没变：WebView 只该在
     * {@code https://appassets.androidplatform.net/assets/} 底下导航，
     * 任何别的主机（含 userInfo、非 443 端口、非 /assets/ 路径）都要拦住。
     * 热更层拆掉之后这条判断仍然需要 —— 它挡的是外部导航，不是热更包。
     */
    static boolean trusted(Uri uri) {
        if (uri == null) return false;
        if ("https".equals(uri.getScheme())
                && WebViewAssetLoader.DEFAULT_DOMAIN.equals(uri.getHost())
                && uri.getUserInfo() == null) {
            return (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getPath() != null
                    && uri.getPath().startsWith("/assets/");
        }
        return false;
    }

    public void restart() {
        recreate();
    }

    public AppStore store() {
        return this.store;
    }

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        registerCommands();
        host = this;
        // 站点配置只有两个来源：APK 里的 site-default.json 打底，用户自己配的
        // user-source.json 覆盖上去。热更层那条「拿包里的 site.json 顶掉内置」的
        // 路已经整块拆掉了（2026-10-19）—— 它带来的问题是越级覆盖：装一版带新界面
        // 的 APK 之后，设备上跑的还是旧热更包里的前端，而版本号完全看不出来。
        SiteProfile site = SiteProfile.current(this);
        this.repository = new DualContentRepository(this, site);
        this.store = new AppStore(this);
        SharedPreferences sharedPreferences = getSharedPreferences("hot-updates", 0);
        String objectId = "";
        String string = sharedPreferences.getString("siteFingerprint", "");
        if (!string.isEmpty() && !string.equals(site.fingerprint)) {
            this.store.clearCache();
        }
        sharedPreferences.edit().putString("siteFingerprint", site.fingerprint).apply();
        this.migratingOrigin = !sharedPreferences.getBoolean("originMigrated", false);
        this.account = new AccountService(this, this.store);
        try {
            ParseUser currentUser = ParseUser.getCurrentUser();
            AppStore appStore = this.store;
            if (currentUser != null) {
                objectId = currentUser.getObjectId();
            }
            appStore.activateUser(objectId);
        } catch (Exception e) {
            Log.w(TAG, "无法恢复账号数据", e);
        }
        this.updates = new UpdateManager(this);
        int i = getApplicationInfo().flags & 2;
        // 只有 debuggable 构建（FLAG_DEBUGGABLE）才开 WebView 远程调试，
        // release 包里无条件开等于给任何一台 USB 连着的机器留检查器。
        if (i != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        this.root = new FrameLayout(this);
        WebView webView = new WebView(this);
        this.webView = webView;
        webView.setBackgroundColor(0);
        this.root.addView(this.webView, new FrameLayout.LayoutParams(-1, -1));
        setContentView(this.root);
        UiRefreshRate.apply(this, this.webView);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attributes = getWindow().getAttributes();
            attributes.layoutInDisplayCutoutMode = 1;
            getWindow().setAttributes(attributes);
        }
        this.root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public final WindowInsets onApplyWindowInsets(View view, WindowInsets windowInsets) {
                return MainActivity.this.lambda$onCreate$0(view, windowInsets);
            }
        });
        applyTheme();
        this.webView.getSettings().setJavaScriptEnabled(true);
        this.webView.getSettings().setDomStorageEnabled(true);
        this.webView.getSettings().setAllowFileAccess(this.migratingOrigin);
        this.webView.getSettings().setAllowFileAccessFromFileURLs(false);
        this.webView.getSettings().setAllowUniversalAccessFromFileURLs(false);
        this.webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        this.webView.setWebViewClient(new AnonymousClass1(this, new WebViewAssetLoader.Builder().addPathHandler("/assets/",
                new WebViewAssetLoader.AssetsPathHandler(this)).build()));
        this.webView.setWebChromeClient(new AnonymousClass2());
        this.webView.addJavascriptInterface(new Bridge(), "AndroidHost");
        this.webView.loadUrl(this.migratingOrigin ? "file:///android_asset/app.html" : "https://appassets.androidplatform.net/assets/app.html");
        // 命令行自检入口（阶段 0 专用，没有界面入口）：
        //   adb shell am start -n com.huangguo.mobile/.MainActivity --es spider_demo 1
        // 它只把「宿主页 + bridge + 超时 + 回收」这条链在真机上跑一遍。
        // 阶段 1 接上热更、设置页有真入口之后，这段可以删。
        if (getIntent() != null && getIntent().hasExtra("spider_demo")) {
            // 命令行自检：只跑**已安装**的源（SpiderStore 现在只有这一个来源）。
            //   adb shell am start -n com.huangguo.mobile/.MainActivity \
            //       --es spider_demo 1 --es spider_id yhdm --es spider_cap home
            // 阶段 0 那套「读 assets 自检样本 + 超时后重建」的分支随 assets 一起删了。
            final String spiderId = getIntent().getStringExtra("spider_id");
            final String cap = getIntent().getStringExtra("spider_cap");
            final String spiderArgs = getIntent().getStringExtra("spider_args");
            final int timeout = getIntent().getIntExtra("spider_timeout", 15000);
            if (spiderId != null && spiderId.length() > 0) {
                this.requests.execute(() -> {
                    try {
                        JSONObject args = spiderArgs == null || spiderArgs.length() == 0
                                ? new JSONObject() : new JSONObject(spiderArgs);
                        long t0 = System.currentTimeMillis();
                        JSONObject payload = SpiderStore.call(this, spiderId,
                                cap == null || cap.length() == 0 ? "home" : cap, args, timeout);
                        Log.i("HG.Spider", "真源 " + spiderId + "." + cap + " ok "
                                + (System.currentTimeMillis() - t0) + "ms payload="
                                + payload.toString().substring(0, Math.min(600, payload.toString().length())));
                    } catch (Throwable e) {
                        Log.e("HG.Spider", "真源调用失败：" + e);
                    }
                });
            }
        }
    }

    public WindowInsets lambda$onCreate$0(View view, WindowInsets windowInsets) {
        if (Build.VERSION.SDK_INT >= 30) {
            Insets insets = windowInsets.getInsets(WindowInsets.Type.systemBars());
            this.statusBarInsetDp = insets.top / getResources().getDisplayMetrics().density;
            this.root.setPadding(insets.left, 0, insets.right, insets.bottom);
        } else {
            this.statusBarInsetDp = windowInsets.getSystemWindowInsetTop() / getResources().getDisplayMetrics().density;
            this.root.setPadding(windowInsets.getSystemWindowInsetLeft(), 0, windowInsets.getSystemWindowInsetRight(), windowInsets.getSystemWindowInsetBottom());
        }
        publishWindowInsets();
        return windowInsets;
    }

    class AnonymousClass1 extends WebViewClient {
        final MainActivity this$0;
        final WebViewAssetLoader val$loader;

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView webView, WebResourceRequest webResourceRequest) {
            WebResourceResponse webResourceResponseFetch;
            return (!MediaProxy.handles(webResourceRequest.getUrl().toString()) || (webResourceResponseFetch = MediaProxy.fetch(webResourceRequest)) == null) ? this.val$loader.shouldInterceptRequest(webResourceRequest.getUrl()) : webResourceResponseFetch;
        }

        AnonymousClass1(MainActivity mainActivity, WebViewAssetLoader webViewAssetLoader) {
            this.val$loader = webViewAssetLoader;
            this.this$0 = mainActivity;
        }

        @Override
        public void onPageStarted(WebView webView, String str, Bitmap bitmap) {
            // 页面开始加载。热更 trial 的超时回退随热更层一起删掉了 ——
            // 现在界面只有 assets 这一个来源，加载不出来就是 APK 本身的问题。
        }

        @Override
        public void onPageFinished(final WebView webView, String str) {
            if (this.this$0.migratingOrigin && "file:///android_asset/app.html".equals(str)) {
                webView.evaluateJavascript("localStorage.getItem('adult-confirmed')", new ValueCallback() {
                    @Override
                    public final void onReceiveValue(Object obj) {
                        AnonymousClass1.this.lambda$onPageFinished$0(webView, (String) obj);
                    }
                });
            } else {
                this.this$0.publishWindowInsets();
            }
        }

        public void lambda$onPageFinished$0(WebView webView, String str) {
            if (this.this$0.isDestroyed()) {
                return;
            }
            try {
                this.this$0.getSharedPreferences("hot-updates", 0).edit().putBoolean("originMigrated", true).putBoolean("adultConfirmed", Text.REQUEST_HEADER_ENABLE_METADATA_VALUE.equals(new JSONTokener(str).nextValue())).apply();
            } catch (Exception unused) {
            }
            this.this$0.migratingOrigin = false;
            webView.getSettings().setAllowFileAccess(false);
            webView.loadUrl("https://appassets.androidplatform.net/assets/app.html");
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView webView, WebResourceRequest webResourceRequest) {
            return !trusted(webResourceRequest.getUrl());
        }

        @Override
        public boolean onRenderProcessGone(WebView webView, RenderProcessGoneDetail renderProcessGoneDetail) {
            this.this$0.root.removeView(webView);
            webView.destroy();
            this.this$0.webView = null;
            this.this$0.recreate();
            return true;
        }
    }

    class AnonymousClass2 extends WebChromeClient {
        AnonymousClass2() {
        }

        @Override
        public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
            Log.d(MainActivity.TAG, consoleMessage.message() + " @" + consoleMessage.lineNumber());
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView webView, String str, String str2, final JsResult jsResult) {
            new AlertDialog.Builder(MainActivity.this).setMessage(str2).setPositiveButton("清空", new DialogInterface.OnClickListener() {
                @Override
                public final void onClick(DialogInterface dialogInterface, int i) {
                    jsResult.confirm();
                }
            }).setNegativeButton("取消", new DialogInterface.OnClickListener() {
                @Override
                public final void onClick(DialogInterface dialogInterface, int i) {
                    jsResult.cancel();
                }
            }).setOnCancelListener(new DialogInterface.OnCancelListener() {
                @Override
                public final void onCancel(DialogInterface dialogInterface) {
                    jsResult.cancel();
                }
            }).show();
            return true;
        }
    }

    private boolean dark() {
        int iOverride = ThemeBridge.override();
        // 修复反编译错位：-1 是「跟随系统」，显式选择（0 浅 / 1 深）要直接生效。
        // 原来的 iOverride == -1 恒为真，用户选浅色也永远按深色画。
        if (iOverride != -1) {
            return iOverride != 0;
        }
        return (getResources().getConfiguration().uiMode & 48) == 32;
    }

    private float screenCornerRadius(int i) {
        if (Build.VERSION.SDK_INT < 31) {
            return 22.0f;
        }
        RoundedCorner roundedCorner = getDisplay() == null ? null : getDisplay().getRoundedCorner(i);
        if (roundedCorner == null || roundedCorner.getRadius() <= 0) {
            return 22.0f;
        }
        return roundedCorner.getRadius() / getResources().getDisplayMetrics().density;
    }

    private JSONObject screenCorners() throws Exception {
        return new JSONObject().put("topLeft", screenCornerRadius(0)).put("topRight", screenCornerRadius(1)).put("bottomRight", screenCornerRadius(2)).put("bottomLeft", screenCornerRadius(3));
    }

    private void applyTheme() {
        boolean zDark = dark();
        this.root.setBackgroundColor(Color.parseColor(zDark ? "#171B1D" : "#F5F2E9"));
        applySystemBars();
        WebView webView = this.webView;
        if (webView != null) {
            webView.evaluateJavascript("window.hgTheme && window.hgTheme(" + zDark + ")", null);
        }
    }

    public void publishWindowInsets() {
        WebView webView = this.webView;
        if (webView != null) {
            webView.evaluateJavascript("window.hgWindowInsets && window.hgWindowInsets(" + this.statusBarInsetDp + ")", null);
        }
    }

    public void applySystemBars() {
        boolean zDark = dark();
        getWindow().setStatusBarColor(0);
        getWindow().setNavigationBarColor(Color.parseColor(zDark ? "#171B1D" : "#F5F2E9"));
        if (Build.VERSION.SDK_INT >= 29) {
            getWindow().setStatusBarContrastEnforced(false);
        }
        // 修复反编译错位：原深色分支被错位成 MediaDiscoverer.Event.Started。
        // 0x510 布局旗标 = LAYOUT_STABLE | LAYOUT_FULLSCREEN | LIGHT_NAVIGATION_BAR，
        // 浅色挂着、深色清空；下面 |= 8192（LIGHT_STATUS_BAR）的分支不动。
        int i = zDark ? 0 : 0x510;
        if (!zDark && !this.detailSystemBars) {
            i |= 8192;
        }
        getWindow().getDecorView().setSystemUiVisibility(i);
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        applyTheme();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 用户很可能这一下就再也没回来 —— 手上这批先发出去
        Report.flushNow();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applySystemBars();
        WebView webView = this.webView;
        if (webView != null) {
            UiRefreshRate.apply(this, webView);
        }
        WebView webView2 = this.webView;
        if (webView2 != null) {
            webView2.evaluateJavascript("window.hgResume && window.hgResume()", null);
        }
        if (this.account != null && ParseUser.getCurrentUser() != null) {
            this.requests.execute(new Runnable() {
                @Override
                public final void run() {
                    MainActivity.this.lambda$onResume$0();
                }
            });
        }
        File file = this.pendingUpdateApk;
        if (file != null) {
            this.pendingUpdateApk = null;
            if (getPackageManager().canRequestPackageInstalls()) {
                openUpdateInstaller(file);
            } else if (this.webView != null) {
                // 渲染进程崩掉后 webView 已置 null（onRenderProcessGone），recreate
                // 前插进来的一次 resume 不能再往它上发 JavaScript。
                this.webView.evaluateJavascript("window.hgUpdateInstallPermissionDenied && window.hgUpdateInstallPermissionDenied()", null);
            }
        }
    }

    public void lambda$onResume$0() {
        try {
            this.account.sync();
            runOnUiThread(new Runnable() {
                @Override
                public final void run() {
                    MainActivity.this.lambda$onResume$1();
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "云端同步稍后重试", e);
        }
    }

    public void lambda$onResume$1() {
        this.webView.evaluateJavascript("window.hgCloudSynced && window.hgCloudSynced()", null);
    }

    @Override
    public void onWindowFocusChanged(boolean z) {
        WebView webView;
        super.onWindowFocusChanged(z);
        if (!z || (webView = this.webView) == null) {
            return;
        }
        UiRefreshRate.apply(this, webView);
    }

    @Override
    protected void onActivityResult(int i, int i2, Intent intent) {
        super.onActivityResult(i, i2, intent);
        if (i != AVATAR_PICKER || i2 != -1 || intent == null || intent.getData() == null) {
            return;
        }
        final Uri data = intent.getData();
        this.requests.execute(new Runnable() {
            @Override
            public final void run() {
                MainActivity.this.lambda$onActivityResult$0(data);
            }
        });
    }

    public void lambda$onActivityResult$0(Uri uri) {
        try {
            final JSONObject avatar = this.account.setAvatar(uri);
            runOnUiThread(new Runnable() {
                @Override
                public final void run() {
                    MainActivity.this.lambda$onActivityResult$1(avatar);
                }
            });
        } catch (Exception e) {
            StringBuilder sb = new StringBuilder("window.hgAvatarError && window.hgAvatarError(");
            sb.append(JSONObject.quote(e.getMessage() == null ? "头像保存失败" : e.getMessage()));
            sb.append(")");
            final String string = sb.toString();
            runOnUiThread(new Runnable() {
                @Override
                public final void run() {
                    MainActivity.this.lambda$onActivityResult$2(string);
                }
            });
        }
    }

    public void lambda$onActivityResult$1(JSONObject jSONObject) {
        this.webView.evaluateJavascript("window.hgAvatarUpdated && window.hgAvatarUpdated(" + jSONObject + ")", null);
    }

    public void lambda$onActivityResult$2(String str) {
        this.webView.evaluateJavascript(str, null);
    }

    private void installUpdate(File file) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            this.pendingUpdateApk = file;
            startActivity(new Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES", Uri.parse("package:" + getPackageName())));
            return;
        }
        openUpdateInstaller(file);
    }

    private void openUpdateInstaller(File file) {
        try {
            Uri uriForFile = FileProvider.getUriForFile(this, getPackageName() + ".updates", file);
            Intent intent = new Intent("android.intent.action.INSTALL_PACKAGE");
            intent.setData(uriForFile);
            intent.addFlags(1);
            startActivity(intent);
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            Log.e(TAG, "无法打开系统安装程序", e);
            this.webView.evaluateJavascript("window.hgUpdateInstallError && window.hgUpdateInstallError()", null);
        }
    }

    public void updateBackRegistration(boolean z) {
        if (Build.VERSION.SDK_INT < 33 || z == this.backRegistered) {
            return;
        }
        if (z) {
            if (Build.VERSION.SDK_INT >= 34) {
                this.backCallback = new OnBackAnimationCallback() {
                    public void onBackStarted(BackEvent backEvent) {
                        MainActivity.this.resetBackProgress();
                        MainActivity.this.captureBackGeometry();
                        WebView webView = MainActivity.this.webView;
                        StringBuilder sb = new StringBuilder("window.hgPredictiveBackStart && window.hgPredictiveBackStart(");
                        sb.append(backEvent.getSwipeEdge() == 1);
                        sb.append(")");
                        webView.evaluateJavascript(sb.toString(), null);
                    }

                    public void onBackProgressed(BackEvent backEvent) {
                        MainActivity mainActivity = MainActivity.this;
                        mainActivity.pendingBackProgress = mainActivity.backTouchFraction(backEvent);
                        MainActivity.this.sendLatestBackProgress();
                    }

                    public void onBackCancelled() {
                        MainActivity.this.resetBackProgress();
                        MainActivity.this.webView.evaluateJavascript("window.hgPredictiveBackCancel && window.hgPredictiveBackCancel()", null);
                    }

                    public void onBackInvoked() {
                        MainActivity.this.resetBackProgress();
                        MainActivity.this.handleBack(true);
                    }
                };
            } else {
                this.backCallback = new OnBackInvokedCallback() {
                    public final void onBackInvoked() {
                        MainActivity.this.lambda$updateBackRegistration$0();
                    }
                };
            }
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, this.backCallback);
        } else {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(this.backCallback);
            this.backCallback = null;
        }
        this.backRegistered = z;
    }

    public void lambda$updateBackRegistration$0() {
        handleBack(false);
    }

    public void resetBackProgress() {
        this.backProgressGeneration++;
        this.pendingBackProgress = -1.0f;
        this.backProgressInFlight = false;
    }

    public void captureBackGeometry() {
        int[] iArr = new int[2];
        this.webView.getLocationOnScreen(iArr);
        this.backViewLeft = iArr[0];
        this.backViewWidth = this.webView.getWidth();
        this.backLeadPx = getResources().getDisplayMetrics().density * 18.0f;
    }

    public float backTouchFraction(BackEvent backEvent) {
        return BackGesture.fraction(backEvent.getTouchX(), backEvent.getSwipeEdge(),
                backEvent.getProgress(), this.backViewWidth, this.backViewLeft, this.backLeadPx);
    }

    public void sendLatestBackProgress() {
        WebView webView;
        if (this.backProgressInFlight) {
            return;
        }
        float f = this.pendingBackProgress;
        if (f < 0.0f || (webView = this.webView) == null) {
            return;
        }
        this.pendingBackProgress = -1.0f;
        this.backProgressInFlight = true;
        final int i = this.backProgressGeneration;
        webView.evaluateJavascript("window.hgPredictiveBackProgress && window.hgPredictiveBackProgress(" + f + ")", new ValueCallback() {
            @Override
            public final void onReceiveValue(Object obj) {
                MainActivity.this.lambda$sendLatestBackProgress$0(i, (String) obj);
            }
        });
    }

    public void lambda$sendLatestBackProgress$0(int i, String str) {
        if (i != this.backProgressGeneration) {
            return;
        }
        this.backProgressInFlight = false;
        sendLatestBackProgress();
    }

    /**
     * 键盘开着的时候，返回键只该收起键盘。
     *
     * 不判这一下的话，用户在输入框里按返回（最常见的「先收键盘」动作）会连带把
     * 整页退掉 —— 内容源页点「备注」改一行字、按返回，回到的是设置首页，那行字
     * 白改了。输入法自己抢不到这个键：OnBackInvokedCallback 注册在优先级 0，
     * 系统先问的就是我们。
     */
    private boolean hideImeIfShown() {
        View focus = getCurrentFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
        boolean byInsets = Build.VERSION.SDK_INT >= 30 && insets != null && insets.isVisible(WindowInsets.Type.ime());
        boolean byImm = imm != null && imm.isAcceptingText();
        // 两条判据都要看：真机上 insets 有时不认输入法窗口（窗口本身没 fit system
        // windows），isAcceptingText 才是「现在真的有输入框在收字」。
        if (!byInsets && !byImm) {
            return false;
        }
        View token = focus != null ? focus : this.webView;
        if (imm != null && token != null) {
            imm.hideSoftInputFromWindow(token.getWindowToken(), 0);
        }
        // 只收键盘不够。WebView 里那个 input 还攥着焦点，输入法连接一直挂着，
        // isAcceptingText() 就永远为真 —— 第二次按返回还是被吞掉。焦点一起撤。
        if (this.webView != null) {
            this.webView.evaluateJavascript("(function(){var a=document.activeElement;a&&a.blur&&a.blur();return 1})()", null);
        }
        return true;
    }

    public void handleBack(boolean z) {
        if (hideImeIfShown()) {
            resetBackProgress();
            return;
        }
        String str;
        WebView webView = this.webView;
        if (webView == null) {
            finish();
            return;
        }
        if (z) {
            str = "window.hgPredictiveBackCommit ? window.hgPredictiveBackCommit() : (window.hgBack ? window.hgBack() : false)";
        } else {
            str = "window.hgBack ? window.hgBack() : false";
        }
        webView.evaluateJavascript(str, new ValueCallback() {
            @Override
            public final void onReceiveValue(Object obj) {
                MainActivity.this.lambda$handleBack$0((String) obj);
            }
        });
    }

    public void lambda$handleBack$0(String str) {
        if ("true".equals(str) || moveTaskToBack(true)) {
            return;
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        handleBack(false);
    }

    /**
     * 已经在运行时再收到一次带 spider extra 的 intent 就跑一次自检。
     *
     * 为什么需要它：冷启动那一刻内置代理核心还没起来（实测启动后 0.3 秒时
     * effectiveProxy() 仍是空串），spider 只能直连，撞上被污染的 IPv6 要烧 15 秒。
     * 有了这条，验证流程可以先正常启动、等核心就绪、再补一条 intent 触发 ——
     * 否则每次都要和启动时序赛跑。
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.hasExtra("spider_demo")) {
            String id = intent.getStringExtra("spider_id");
            String cap = intent.getStringExtra("spider_cap");
            String argsRaw = intent.getStringExtra("spider_args");
            int timeout = intent.getIntExtra("spider_timeout", 15000);
            this.requests.execute(() -> {
                try {
                    JSONObject args = argsRaw == null || argsRaw.length() == 0
                            ? new JSONObject() : new JSONObject(argsRaw);
                    long t0 = System.currentTimeMillis();
                    JSONObject payload = SpiderStore.call(this, id,
                            cap == null || cap.length() == 0 ? "home" : cap, args, timeout);
                    Log.i("HG.Spider", "真源 " + id + "." + cap + " ok "
                            + (System.currentTimeMillis() - t0) + "ms payload="
                            + payload.toString().substring(0, Math.min(600, payload.toString().length())));
                } catch (Throwable e) {
                    Log.e("HG.Spider", "真源调用失败：" + e);
                }
            });
        }
    }

    @Override
    protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= 33 && this.backRegistered) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(this.backCallback);
        }
        this.hotHandler.removeCallbacksAndMessages(null);
        this.requests.shutdownNow();
        this.localRequests.shutdownNow();
        this.coverRequests.shutdownNow();
        // JS 异常那个池子也要关。**不写这一句的代价不是崩溃，是泄漏**：
        // Activity 重建（换源、改配置都会）时旧的池子留着跑，每条都握着
        // 已经被系统回收的 WebView 上下文；而且这个池子是 2.0.23 才加的，
        // 前三个都关了、只有它没关，属于最容易被忘掉的那一类。
        this.reports.shutdownNow();
        // 站点侧那个隐藏 WebView（见 SiteFetchWebView）。它挂在 decor 上，
        // 不销毁的话渲染进程会一直留着，定时器和视频缓冲都不退。
        SiteFetchWebView.shutdown();
        // spider 宿主同理：留着渲染进程的话，页面里的定时器和 spider 的
        // 半截 Promise 都不会退。宿主在 :spider 进程里（SpiderService），
        // 这里解绑 + 停服务，进程随之回收。
        SpiderClient.shutdown();
        WebView webView = this.webView;
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidHost");
            this.webView.destroy();
        }
        if (host == this) {
            host = null;
        }
        super.onDestroy();
    }


    public Object dispatch(String str, JSONObject jSONObject) throws Exception {
        // 内容状态：双态（公开 / 私密）2026-10-19 整块拆掉了。
        //
        // 那个「私密库」是个内置源，而它早就没有接口（site-default.json 的 origin
        // 是空串）—— 剩下的只有一套解锁流程和一份界面默认值，却把用户装的每个
        // spider 源都判成了「私密模式」。命令留着是因为老前端还会问一句，
        // 应答固定成公开态，不让它启动时卡在解锁上。
        if ("content-status".equals(str)) {
            return new JSONObject()
                    .put("source", "hongguo")
                    .put("unlocked", true)
                    .put("needsAgeConfirmation", false);
        }
        Command command = this.commands.get(str);
        if (command == null) {
            throw new IllegalArgumentException("未知请求");
        }
        long startedAt = System.currentTimeMillis();
        try {
            return command.run(jSONObject);
        } catch (Exception error) {
            // 桥指令失败的统一出口。以前这些异常要么被前端的 catch 吞掉、要么
            // 变成一句「播放失败」；现在至少知道是哪个 stage、什么异常、花了多久。
            // 少数常态失败的指令不报，见 QUIET_COMMANDS。
            // 已经报过一次的（封面重试那条路）不再报，否则一次故障能刷出几百条。
            if (!QUIET_COMMANDS.contains(str) && !Report.isOnce(error)) {
                Report.bridgeFailure(str, error, System.currentTimeMillis() - startedAt);
            }
            throw error;
        }
    }

    /**
     * 这些指令失败是常态：没缓存、老前端瞎问一句、内容状态固定应答。
     * 上报它们只会把真正的问题淹掉。
     */
    private static final java.util.Set<String> QUIET_COMMANDS =
            new java.util.HashSet<String>(java.util.Arrays.asList(
                    "app-version", "content-status", "hot-ready", "cached"));

    /** 一条桥指令：拿到 JSON 参数，返回 JSONObject / String / boolean / Number，或者抛异常。 */
    private interface Command {
        Object run(JSONObject jSONObject) throws Exception;
    }

    /**
     * dispatch 里原来的 synchronized(this) 快照：repository / store / account 会被
     * rebindContent() 整体换掉，每条指令必须在自己的入口处取一次快照再往下用。
     */
    private static final class Services {
        final DualContentRepository repository;
        final AppStore store;
        final AccountService account;

        Services(DualContentRepository dualContentRepository, AppStore appStore, AccountService accountService) {
            this.repository = dualContentRepository;
            this.store = appStore;
            this.account = accountService;
        }
    }

    private Services services() {
        synchronized (this) {
            return new Services(this.repository, this.store, this.account);
        }
    }

    /**
     * 桥指令注册表。加一条指令 = 在这里加一行 put，dispatch 不用动。
     * 名字与前端 AndroidHost.request(id, action, ...) 的 action 一一对应。
     */
    private void registerCommands() {
        this.commands.put("app-version", jSONObject -> getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        // 热更已经停用（2026-10-19）。这条命令留着只为「老前端还发得出来时不至于抛异常」，
        // 直接应答即可 —— 前端那边整块 UI 也一起删了。
        this.commands.put("hot-ready", jSONObject -> true);
        this.commands.put("open-player", jSONObject -> {
            jSONObject.put("source", "hongguo");
            return true;
        });
        this.commands.put("cached", jSONObject -> services().store.cached(jSONObject.optString("key")));
        // 前端上报：播放阶段（取详情 / 取播放地址 / 解密 / 起播 / 首帧）和 JS 异常。
        // 参数就是一条事件 {kind,name,source,props}，见 Report.fromFrontend。
        this.commands.put("report", jSONObject -> {
            Report.fromFrontend(jSONObject);
            return true;
        });
        // 自检口子：和崩溃那条一样的用途，只是换成验证另外两条链路。
        // 不这么留入口，「JS 异常上报」就只能靠等真用户踩一次才能验。
        this.commands.put("diag", jSONObject -> {
            String mode = jSONObject.optString("mode");
            if ("crash".equals(mode)) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    throw new IllegalStateException("diag: 手动触发的崩溃（验证上报链路，不是 bug）");
                });
                return new JSONObject().put("ok", true).put("note", "主线程即将崩溃");
            }
            // 另外两条走 WebView：必须回主线程 evaluateJavascript，diag 本身在后台池里。
            // 抛在 setTimeout 里而不是当场抛 —— 当场抛会被 evaluateJavascript 那层
            // 吞成一句 "Uncaught Error"，拿不到完整的 ErrorEvent（也就没有 filename/lineno）。
            if ("js".equals(mode) || "rejection".equals(mode)) {
                final String script = "js".equals(mode)
                        ? "setTimeout(function(){throw new Error('diag: 手动触发的 JS 异常（验证上报链路，不是 bug）')},0)"
                        : "Promise.reject(new Error('diag: 手动造一个未处理 rejection（不是 bug）'))";
                final WebView view = this.webView;
                if (view == null) {
                    return new JSONObject().put("ok", false).put("note", "webView 还没起来");
                }
                view.post(() -> view.evaluateJavascript(script, null));
                return new JSONObject().put("ok", true).put("note",
                        "js".equals(mode) ? "主线程 setTimeout 里抛一个" : "一个没人 catch 的 Promise");
            }
            return Report.state();
        });
        this.commands.put("detail", jSONObject -> {
            Services s = services();
            String strOptString = jSONObject.optString(Text.ATTR_ID);
            JSONObject jSONObjectDetail = s.repository.detail(strOptString);
            s.store.saveCache("detail:" + strOptString, jSONObjectDetail);
            return jSONObjectDetail;
        });

        // ── 控制面：五个子系统各占一条 action ────────────────────────────
        //
        // 2026-10-18 之前这些全挤在 detail 桥里，靠 "__source:xxx" 这种前缀
        // 字符串分流 —— 一条 action 同时承载「取详情页」和「六个子系统的控制面」，
        // 加一个功能要在 app.js 包一层、再在 UserSource 的 startsWith 链上插一段。
        //
        // 现在按前缀拆成独立 action：detail 只留详情，控制面各回各家。
        // 前端的调用也从 bridgeCall('detail', {id:'__xxx:'+cmd}) 改成
        // bridgeCall('source', {id:cmd})，语义直接写在 action 名上。
        //
        // SiteRepository.detail 里那段 "__ 开头就转控制面" 的兼容分支**保留**：
        // 热更新的旧版 JS 还在用旧写法，删了就是「升级完页面全废」。
        this.commands.put("source", jSONObject ->
                control(UserSource.PREFIX, jSONObject));
        this.commands.put("player", jSONObject ->
                control(UserSource.PLAYER_PREFIX, jSONObject));
        this.commands.put("proxy", jSONObject ->
                control(UserSource.PROXY_PREFIX, jSONObject));
        this.commands.put("progress", jSONObject ->
                control(UserSource.PROGRESS_PREFIX, jSONObject));
        this.commands.put("http", jSONObject ->
                control(UserSource.HTTP_PREFIX, jSONObject));
        this.commands.put("check-update", jSONObject -> this.updates.check(jSONObject.optBoolean("manual")));
        this.commands.put("account", jSONObject -> services().account.profile());
        this.commands.put("apply-hot-update", jSONObject -> true);
        this.commands.put("logout", jSONObject -> {
            Services s = services();
            s.account.logout();
            return s.account.profile();
        });
        this.commands.put("clear-bookmarks", jSONObject -> {
            final Services s = services();
            s.store.clearBookmarks();
            if (ParseUser.getCurrentUser() != null) {
                this.requests.execute(new Runnable() {
                    @Override
                    public final void run() {
                        try {
                            s.account.sync();
                        } catch (Exception e) {
                            // 会话过期（Parse 209）或网络抖动都不该让「清空收藏」把 App 崩掉：
                            // 本地已经清完了，云端同步是顺带的事（logout() 里同样是「失败就放弃」）。
                            // 原来这行是 throw new RuntimeException(e)，线上 2026-10-28 18:45
                            // 那条唯一的真崩溃就是它 —— 用户点了个清空，被 session 过期崩掉。
                            UserSource.log("clear-bookmarks 后同步失败：" + AiiHttp.describe(e));
                        }
                    }
                });
            }
            return true;
        });
        this.commands.put("download-update", jSONObject -> {
            final File fileDownload = this.updates.download(new UpdateManager.Progress() {
                @Override
                public final void onPercent(int i) {
                    MainActivity.this.postUpdateProgress(i);
                }
            });
            runOnUiThread(new Runnable() {
                @Override
                public final void run() {
                    MainActivity.this.onUpdateDownloaded(fileDownload);
                }
            });
            return true;
        });
        this.commands.put("change-password", jSONObject -> services().account.changePassword(jSONObject.optString("oldPassword"), jSONObject.optString("newPassword")));
        this.commands.put("register", jSONObject -> services().account.register(jSONObject.optString("username"), jSONObject.optString("password")));
        this.commands.put("quit", jSONObject -> {
            runOnUiThread(new Runnable() {
                @Override
                public final void run() {
                    MainActivity.this.finish();
                }
            });
            return true;
        });
        this.commands.put("sync", jSONObject -> services().account.sync());
        this.commands.put("cover", jSONObject -> services().repository.coverData(jSONObject.optString("url")));
        this.commands.put("login", jSONObject -> services().account.login(jSONObject.optString("username"), jSONObject.optString("password")));
        // ── spider 运行时（阶段 0）────────────────────────────────────────
        //
        // op 决定动作：list（源清单）/ install（贴地址装一个）/ remove / call（跑能力）。
        // 源的唯一来源是用户自己装的（files/spiders/），所以这里没有「内置源」那种分支。
        this.commands.put("spider", jSONObject -> {
            String op = jSONObject.optString("op", "list");
            if ("list".equals(op)) {
                // 源清单：热更 ui/spiders/ 优先，APK assets 兜底。每项都带 ok/reason，
                // 被 ABI 门禁拒掉的那些也在里面 —— 让页面能说清「为什么这个源用不了」。
                JSONObject out = new JSONObject();
                JSONArray arr = new JSONArray();
                for (JSONObject one : SpiderStore.list(this)) arr.put(one);
                out.put("spiders", arr);
                out.put("abi", SpiderRuntime.ABI);
                return out;
            }
            if ("install".equals(op)) {
                // 贴一个 https 地址就装一个源 —— 这条路走完，「加源不发版」才算真的闭环：
                // 之前源只能靠热更包下发，而热更包要自己签名发布，普通用户加不了源。
                return SpiderStore.install(this, jSONObject.optString("url"));
            }
            if ("remove".equals(op)) {
                JSONObject out = new JSONObject();
                out.put("removed", SpiderStore.remove(this, jSONObject.optString("id")));
                return out;
            }
            if ("call".equals(op)) {
                final String id = jSONObject.optString("id");
                final String cap = jSONObject.optString("cap", "home");
                final JSONObject callArgs = jSONObject.optJSONObject("args") == null
                        ? new JSONObject() : jSONObject.optJSONObject("args");
                final int timeoutMs = jSONObject.optInt("timeoutMs", 15000);
                // 盾后站点 :spider 进程的 headless WebView 过不去（挑战要真窗口）。
                // 主进程的 SiteFetchWebView 有窗口有 decor，挑战 1~2 秒就过 ——
                // 在这里过盾取 HTML，回填给 spider JS（方案 1 兜底）。
                //
                // 回填是「url → html」一张表（__shieldPages），不是单条：一次调用里
                // home 和详情页可能先后撞盾，只带最新一张就会把前一张挤掉，下一轮
                // 又撞回去，重试永远追不上。表跨轮累积，命中的请求直接吃正文不发网络。
                //
                // 最多 3 轮：每轮都是一次完整的 spider 调用（含 15 秒 headless 预算），
                // 再多了前端早就把这次调用丢了。同一个地址被回填过仍然再次被挡，
                // 说明回填没被采纳，继续轮下去只会空转 —— 当场停。
                final JSONObject shieldPages = new JSONObject();
                for (int round = 0; ; round++) {
                    try {
                        return SpiderStore.call(this, id, cap, callArgs, timeoutMs);
                    } catch (ShieldSignal e) {
                        if (round >= SHIELD_RETRY_MAX) throw e;
                        if (shieldPages.has(e.url)) {
                            // describe 而不是裸 getMessage：异常没有 message 时后者会
                            // 拼出字面量 "null"，线上 props.msg 恰好等于 "null" 的
                            // 369 条里，有一批就是这么来的。
                            // 原始异常同时挂成 cause —— 拼进字符串的话，isCancellation
                            // 和事后排查都够不着它。
                            throw new IOException("盾后回填无效（同一地址再次被挡）：" + e.url
                                    + "；原始失败：" + AiiHttp.describe(e), e);
                        }
                        Log.w("HG.Spider", "cap=" + cap + " 撞盾（" + e.url + "），主进程过盾后重试，第 "
                                + (round + 1) + " 轮");
                        final String html;
                        try {
                            html = SiteFetchWebView.fetchPage(e.url);
                        } catch (Throwable fetchError) {
                            // 这条以前会报出「盾后过盾失败（主进程）：null」——
                            // 线上 2.0.23 里 16 台设备 19 条。取不到 message 的异常
                            // （超时、被取消这类）本来就没有 message，裸拼只能拼出
                            // 一个字面量 null，看着像有信息，实际什么都没说。
                            throw new IOException("盾后过盾失败（主进程）：" + AiiHttp.describe(fetchError)
                                    + "；原始失败：" + AiiHttp.describe(e), e);
                        }
                        shieldPages.put(e.url, html);
                        callArgs.put(SpiderRuntime.ARG_SHIELD_PAGES, shieldPages);
                    }
                }
            }
            throw new IllegalArgumentException("spider 运行时的 op 还不支持：" + op);
        });
        this.commands.put("theme", jSONObject -> {
            String strOptString4 = jSONObject.optString("mode");
            if (strOptString4.isEmpty()) {
                return Boolean.valueOf(dark());
            }
            ThemeBridge.applyMode(this, strOptString4);
            return true;
        });
        this.commands.put("window-insets", jSONObject -> Float.valueOf(this.statusBarInsetDp));
        this.commands.put("library", jSONObject -> services().store.library());
        this.commands.put("clear-content-cache", jSONObject -> {
            Services s = services();
            s.store.clearCache();
            s.repository.clearCovers();
            return true;
        });
        this.commands.put("cached-update", jSONObject -> this.updates.cached());
        this.commands.put("check-hot-update", jSONObject -> new JSONObject().put("enabled", false));
        this.commands.put("clear-history", jSONObject -> {
            final Services s = services();
            s.store.clearHistory();
            if (ParseUser.getCurrentUser() != null) {
                this.requests.execute(new Runnable() {
                    @Override
                    public final void run() {
                        try {
                            s.account.sync();
                        } catch (Exception e) {
                            // 同上：清本地历史不该因为云端会话过期而抛到未捕获处理器。
                            UserSource.log("clear-history 后同步失败：" + AiiHttp.describe(e));
                        }
                    }
                });
            }
            return true;
        });
        this.commands.put("bookmark-state", jSONObject -> Boolean.valueOf(services().store.hasBookmark(jSONObject.optString(Text.ATTR_ID))));
        this.commands.put("hot-update-status", jSONObject -> new JSONObject().put("enabled", false));
        this.commands.put("get-progress", jSONObject -> services().store.getProgress(jSONObject.optString(Text.ATTR_ID), jSONObject.optInt("episode", 1)));
        this.commands.put("choose-avatar", jSONObject -> {
            if (ParseUser.getCurrentUser() == null) {
                throw new IllegalStateException("请先登录");
            }
            runOnUiThread(new Runnable() {
                @Override
                public final void run() {
                    MainActivity.this.pickAvatar();
                }
            });
            return true;
        });
        this.commands.put("screen-corners", jSONObject -> screenCorners());
        this.commands.put("bookmark", jSONObject -> {
            Services s = services();
            boolean z = s.store.toggleBookmark(jSONObject);
            if (ParseUser.getCurrentUser() != null) {
                final AccountService accountService = s.account;
                this.requests.execute(new Runnable() {
                    @Override
                    public final void run() {
                        MainActivity.syncBookmarksQuietly(accountService);
                    }
                });
            }
            return Boolean.valueOf(z);
        });
    }

    /**
     * 控制面统一入口。前缀由这里补上，前端只传裸指令。
     *
     * <p>五个子系统仍然共用 {@link UserSource#handle} 那一条链（它们的实现本来
     * 就在一起：源配置、代理核心、播放器、进度，互相都要读同一份状态）。
     * 拆的是**桥上的入口**，不是实现 —— 实现拆不拆是下一个问题，
     * 而入口不拆的话，加一个功能永远要动两个文件。
     */
    private Object control(String prefix, JSONObject args) throws Exception {
        Services s = services();
        String command = prefix + (args == null ? "" : args.optString(Text.ATTR_ID));
        return UserSource.handle(this, s.repository.profileForControl(), command);
    }

    public void pickAvatar() {
        Intent intent = new Intent("android.intent.action.OPEN_DOCUMENT");
        intent.addCategory("android.intent.category.OPENABLE");
        intent.setType("image/*");
        startActivityForResult(intent, AVATAR_PICKER);
    }

    public void postUpdateProgress(final int i) {
        runOnUiThread(new Runnable() {
            @Override
            public final void run() {
                MainActivity.this.showUpdateProgress(i);
            }
        });
    }

    public void showUpdateProgress(int i) {
        this.webView.evaluateJavascript("window.hgUpdateProgress && window.hgUpdateProgress(" + i + ")", null);
    }

    public void onUpdateDownloaded(File file) {
        if (file == null) {
            this.webView.evaluateJavascript("window.hgUpdateFailed && window.hgUpdateFailed()", null);
        } else {
            installUpdate(file);
        }
    }

    static void syncBookmarksQuietly(AccountService accountService) {
        try {
            accountService.sync();
        } catch (Exception e) {
            Log.w(TAG, "收藏稍后同步", e);
        }
    }

    final class Bridge {
        /** 上一次触感的时间戳，用来压掉同一帧里叠上来的重复震动。 */
        private volatile long hapticLastAt;

        private Bridge() {
        }

        @JavascriptInterface
        public void setDetailMode(final boolean z) {
            MainActivity.this.runOnUiThread(new Runnable() {
                @Override
                public final void run() {
                    Bridge.this.lambda$setDetailMode$0(z);
                }
            });
        }

        public void lambda$setDetailMode$0(boolean z) {
            if (MainActivity.this.detailSystemBars == z) {
                return;
            }
            MainActivity.this.detailSystemBars = z;
            MainActivity.this.applySystemBars();
        }

        public void lambda$setBackEnabled$0(boolean z) {
            MainActivity.this.updateBackRegistration(z);
        }

        @JavascriptInterface
        public void setBackEnabled(final boolean z) {
            MainActivity.this.runOnUiThread(new Runnable() {
                @Override
                public final void run() {
                    Bridge.this.lambda$setBackEnabled$0(z);
                }
            });
        }

        /**
         * 触感反馈。前端每一个「按下去有反应」的动作都会调这里，让手指也收到信号。
         *
         * 走 @JavascriptInterface 而不是桥指令：桥是异步的（回包走 evaluateJavascript），
         * 一次点击要等一个来回才震，那就晚了 —— 震动必须和按压同帧。
         *
         * 强度只用四档，名字按「用户感受到了什么」取，不按毫秒数取：
         *   light   —— 选项切换、页签、开关（10ms，像按到实体键）
         *   medium  —— 进入下一屏、提交（18ms）
         *   success —— 做成了（双段，先重后轻）
         *   error   —— 失败了（三段急促）
         * 系统里关掉「触感反馈」时 Vibrator.hasVibrator() 仍为 true，但这里不去猜 ——
         * 用户在系统设置里关的开关由系统自己拦，App 侧不做二次判断。
         */
        @JavascriptInterface
        public void haptic(final String kind) {
            if (this.hapticLastAt != 0 && System.currentTimeMillis() - this.hapticLastAt < 24) {
                return;
            }
            this.hapticLastAt = System.currentTimeMillis();
            final Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) {
                return;
            }
            final String name = kind == null ? "light" : kind;
            // 有的 ROM 在振动器被别的 App 占着时抛异常，触感不该把页面搞崩。
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    int id;
                    if ("error".equals(name)) {
                        id = VibrationEffect.EFFECT_DOUBLE_CLICK;
                    } else if ("success".equals(name)) {
                        id = VibrationEffect.EFFECT_CLICK;
                    } else if ("medium".equals(name)) {
                        id = VibrationEffect.EFFECT_HEAVY_CLICK;
                    } else {
                        id = VibrationEffect.EFFECT_TICK;
                    }
                    vibrator.vibrate(VibrationEffect.createPredefined(id));
                    return;
                }
                long[] pattern;
                if ("error".equals(name)) {
                    pattern = new long[]{0, 18, 42, 18, 42, 18};
                } else if ("success".equals(name)) {
                    pattern = new long[]{0, 16, 40, 10};
                } else if ("medium".equals(name)) {
                    pattern = new long[]{0, 18};
                } else {
                    pattern = new long[]{0, 10};
                }
                vibrator.vibrate(pattern, -1);
            } catch (Exception ignored) {
                // 触感是锦上添花，失败就当没有。
            }
        }

        /**
         * 前端未捕获异常与未处理 rejection 的唯一入口。见 assets/js-errors.js。
         *
         * <p><b>和 {@code request("report", …)} 的区别只有一条：不回包。</b> 桥那条路
         * 走完会灌一句 {@code window.NativeCallbacks.resolve(id, …)}，而
         * {@code NativeCallbacks} 定义在 app.js —— app.js 整份没执行时这一句自己就是
         * 个 TypeError，被钩子接住再报一次就是死循环。偏偏「app.js 没跑起来」正是
         * 这条链最该抓到的故障，所以它不能有回程。
         *
         * <p>参数是一条完整事件 {@code {kind,name,props}}，和前端 {@code track()} 同形，
         * 只是不带 source —— 不传才能让 {@link Report#build} 回落到 SiteProfile 装配时
         * 挂上的真实源标签（前端那个 contentSource 恒为 hongguo，传了就是假源）。
         *
         * <p>线程：{@link #reports}。js-errors.js 已经把量压到每会话 50 条以内，这里
         * 只是再兜一层 —— 一个坏掉的渲染循环不该把请求池顶住。
         */
        @JavascriptInterface
        public void reportJs(final String json) {
            if (json == null || json.isEmpty()) {
                return;
            }
            MainActivity.this.reports.execute(() -> {
                try {
                    Report.fromFrontend(new JSONObject(json));
                } catch (Throwable ignored) {
                    // 上报失败不影响页面，也不该在 logcat 里制造噪音。
                }
            });
        }

        /**
         * 按指令挑线程池。
         *
         * <p>三档，分的依据是「这条指令会不会被别人的慢请求拖住」：
         *
         * <ul>
         *   <li>{@link #localRequests}（单线程）：纯本地读写 —— 缓存、观看记录、
         *       收藏状态。它们不联网，几十毫秒就回来，但**用户按下去就在等**。</li>
         *   <li>{@link #coverRequests}（2 线程）：封面解密。一屏二十张是常态，
         *       混进通用池会把别的请求挤到队尾。</li>
         *   <li>{@link #requests}（4 线程）：其余全部，内容动作都在这里。</li>
         * </ul>
         *
         * <p>{@code library} 原来走通用池。spider 化之后一个内容动作要 2~5 秒
         * （建页 + 网络 + 解析），而 {@code SpiderRuntime} 内部还是全局串行的，
         * 于是四五个内容请求一起发出去时，池子里会有线程卡在等锁上；用户这时点
         * 「我的」，一条纯本地请求排在它们后面 —— 表现就是「点我的卡几秒」。
         * 本地指令落到单线程池就不会再被内容流量连坐。
         */
        @JavascriptInterface
        public void request(final String str, final String str2, final String str3) {
            ExecutorService executorService;
            if (isLocalCommand(str2)) {
                executorService = MainActivity.this.localRequests;
            } else {
                boolean zEquals = "cover".equals(str2);
                MainActivity mainActivity = MainActivity.this;
                executorService = zEquals ? mainActivity.coverRequests : mainActivity.requests;
            }
            executorService.execute(new Runnable() {
                @Override
                public final void run() {
                    Bridge.this.requestDone(str3, str2, str);
                }
            });
        }

        /**
         * 不联网、只读本机状态的指令。
         *
         * <p>漏一个进去不会错，只会慢；多一个进来会占住单线程池，所以宁可少列 ——
         * 只收「用户点一下立刻要看结果」的那几个。
         */
        private boolean isLocalCommand(String command) {
            if (command == null) return false;
            switch (command) {
                case "cached":
                case "library":
                case "bookmark-state":
                case "get-progress":
                case "window-insets":
                case "app-version":
                case "age-status":
                case "cached-update":
                case "hot-update-status":
                    return true;
                default:
                    return false;
            }
        }

        /*
         * 反编译提示：原字节码里三个 catch handler 全部跳到同一处
         * （Log.e + 构造 reject 回调 + runOnUiThread），jadx 把它展开成了多份
         * 内联副本。所以下面几段 catch 看起来重复，是编译器产物，不是手写冗余。
         * 合并它们要先把 reject 构造抽成方法，动之前务必对着 smali 的
         * MainActivity$Bridge.smali :goto_4 核一遍。
         */
        public void requestDone(String str, String str2, String str3) {
            Exception exc;
            String message;
            String string;
            try {
                Object objDispatch = MainActivity.this.dispatch(str2, new JSONObject(str));
                try {
                    if (objDispatch == null) {
                        string = "null";
                    } else {
                        string = ((objDispatch instanceof JSONObject) || (objDispatch instanceof Boolean) || (objDispatch instanceof Number)) ? objDispatch.toString() : JSONObject.quote(String.valueOf(objDispatch));
                    }
                    final String str4 = "window.NativeCallbacks.resolve(" + JSONObject.quote(str3) + "," + string + ")";
                    String strOptString = "";
                    final String strOptString2 = (("login".equals(str2) || "register".equals(str2)) && (objDispatch instanceof JSONObject)) ? ((JSONObject) objDispatch).optString("userId") : "";
                    final AccountService accountService = "account".equals(str2) ? MainActivity.this.account : null;
                    if (accountService != null && (objDispatch instanceof JSONObject)) {
                        strOptString = ((JSONObject) objDispatch).optString("userId");
                    }
                    final String str5 = strOptString;
                    try {
                        MainActivity.this.runOnUiThread(new Runnable() {
                            @Override
                            public final void run() {
                                Bridge.this.lambda$request$1(str4, strOptString2, str5, accountService);
                            }
                        });
                    } catch (Exception e) {
                        exc = e;
                        Log.e(MainActivity.TAG, str2 + " failed", exc);
                        StringBuilder sb = new StringBuilder("window.NativeCallbacks.reject(");
                        sb.append(JSONObject.quote(str3));
                        sb.append(",");
                        if (exc.getMessage() == null) {
                            message = "请求失败";
                        } else {
                            message = exc.getMessage();
                        }
                        sb.append(JSONObject.quote(message));
                        sb.append(")");
                        final String string2 = sb.toString();
                        MainActivity.this.runOnUiThread(new Runnable() {
                            @Override
                            public final void run() {
                                Bridge.this.lambda$request$2(string2);
                            }
                        });
                    }
                } catch (Exception e2) {
                    exc = e2;
                    Log.e(MainActivity.TAG, str2 + " failed", exc);
                    StringBuilder sb2 = new StringBuilder("window.NativeCallbacks.reject(");
                    sb2.append(JSONObject.quote(str3));
                    sb2.append(",");
                    if (exc.getMessage() == null) {
                        message = "请求失败";
                    } else {
                        message = exc.getMessage();
                    }
                    sb2.append(JSONObject.quote(message));
                    sb2.append(")");
                    final String string3 = sb2.toString();
                    MainActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public final void run() {
                            Bridge.this.lambda$request$2(string3);
                        }
                    });
                }
            } catch (Exception e3) {
                exc = e3;
                Log.e(MainActivity.TAG, str2 + " failed", exc);
                StringBuilder sb3 = new StringBuilder("window.NativeCallbacks.reject(");
                sb3.append(JSONObject.quote(str3));
                sb3.append(",");
                if (exc.getMessage() == null) {
                    message = "请求失败";
                } else {
                    message = exc.getMessage();
                }
                sb3.append(JSONObject.quote(message));
                sb3.append(")");
                final String string4 = sb3.toString();
                MainActivity.this.runOnUiThread(new Runnable() {
                    @Override
                    public final void run() {
                        Bridge.this.lambda$request$2(string4);
                    }
                });
            }
        }

        public void lambda$request$1(String str, String str2, String str3, AccountService accountService) {
            if (MainActivity.this.isDestroyed() || MainActivity.this.webView == null) {
                return;
            }
            MainActivity.this.webView.evaluateJavascript(str, null);
            if (!str2.isEmpty()) {
                MainActivity.this.refreshAccountAfterAuth(str2);
            }
            if (str3.isEmpty()) {
                return;
            }
            MainActivity.this.refreshAccountSilently(accountService, str3);
        }

        public void lambda$request$2(String str) {
            if (MainActivity.this.isDestroyed() || MainActivity.this.webView == null) {
                return;
            }
            MainActivity.this.webView.evaluateJavascript(str, null);
        }
    }

    public void refreshAccountAfterAuth(final String str) {
        this.requests.execute(new Runnable() {
            @Override
            public final void run() {
                MainActivity.this.lambda$refreshAccountAfterAuth$0(str);
            }
        });
    }

    public void lambda$refreshAccountAfterAuth$0(String str) {
        try {
            this.account.sync();
            runOnUiThread(new Runnable() {
                @Override
                public final void run() {
                    MainActivity.this.lambda$refreshAccountAfterAuth$1();
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "登录后片单稍后同步", e);
        }
        try {
            JSONObject jSONObjectRefreshProfile = this.account.refreshProfile();
            if (str.equals(jSONObjectRefreshProfile.optString("userId"))) {
                final String str2 = "window.hgAccountRefreshed && window.hgAccountRefreshed(" + jSONObjectRefreshProfile + ")";
                runOnUiThread(new Runnable() {
                    @Override
                    public final void run() {
                        MainActivity.this.lambda$refreshAccountAfterAuth$2(str2);
                    }
                });
            }
        } catch (Exception e2) {
            Log.w(TAG, "登录后账号资料稍后刷新", e2);
        }
    }

    public void lambda$refreshAccountAfterAuth$1() {
        this.webView.evaluateJavascript("window.hgCloudSynced && window.hgCloudSynced()", null);
    }

    public void lambda$refreshAccountAfterAuth$2(String str) {
        this.webView.evaluateJavascript(str, null);
    }

    public void refreshAccountSilently(final AccountService accountService, final String str) {
        this.requests.execute(new Runnable() {
            @Override
            public final void run() {
                MainActivity.this.lambda$refreshAccountSilently$0(accountService, str);
            }
        });
    }

    public void lambda$refreshAccountSilently$0(AccountService accountService, String str) {
        try {
            final JSONObject jSONObjectRefreshProfile = accountService.refreshProfile();
            ParseUser currentUser = ParseUser.getCurrentUser();
            if (currentUser != null && str.equals(currentUser.getObjectId()) && str.equals(jSONObjectRefreshProfile.optString("userId"))) {
                runOnUiThread(new Runnable() {
                    @Override
                    public final void run() {
                        MainActivity.this.lambda$refreshAccountSilently$1(jSONObjectRefreshProfile);
                    }
                });
            }
        } catch (Exception e) {
            Log.w(TAG, "账号后台刷新稍后重试", e);
        }
    }

    public void lambda$refreshAccountSilently$1(JSONObject jSONObject) {
        WebView webView;
        if (isDestroyed() || (webView = this.webView) == null) {
            return;
        }
        webView.evaluateJavascript("window.hgAccountRefreshed && window.hgAccountRefreshed(" + jSONObject + ")", null);
    }
}
