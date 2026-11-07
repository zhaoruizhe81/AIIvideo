package com.huangguo.mobile;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * 播放器的控制层 —— 顶栏、底栏、进度条、倍速面板、提示、错误块，以及那套
 * 手绘控件的工厂。
 *
 * ── 为什么从 Activity 里拿出来 ──────────────────────────────────────
 * 2026-10-18 之前，{@code VlcPlayerActivity.buildUi()} 从 651 行铺到 1030 行：
 * 270 行纯 UI 搭建，外加一整套自制控件（{@code IconButton} 用 Canvas 画图标、
 * {@code makeIconButton}、{@code makeButton}、{@code styleSeekBar}、{@code scrim}、
 * {@code rounded}、{@code animateBar}）。等于把一套 mini UI 框架焊死在播放器里，
 * 而播放器本身还背着 libVLC 生命周期、RecyclerView 翻页、JS 桥、进度落盘。
 *
 * 搬出来之后 Activity 只剩编排：这层负责「长什么样、什么时候显隐」，
 * Activity 负责「播什么、出错了怎么办」。
 *
 * ── 为什么图标是画的不是字符 ────────────────────────────────────────
 * ⏪⏸▶ 这类字符在小米上是**彩色 emoji**，控制栏里会出现三个五颜六色的小图。
 * 所以 {@link IconButton} 用 Canvas 画几何图形。这条是踩出来的，别改回字符。
 */
final class VlcControlsView {

    /**
     * 控件要回调宿主的事。刻意只有「用户做了什么」，
     * 不带任何播放器状态 —— 状态留在 Activity，这里不存副本。
     */
    interface Host {
        void onBack();
        void onPlayPause();
        void onSeekBy(long deltaMs);
        void onSeekTo(long progressPermille);
        void onSpeedToggle();
        void onScrubStart();
        void onScrubEnd();
        int durationMs();
        /** 用户点了锁。锁的状态归宿主，这里只管画。 */
        void onLockToggle();
        /** 右侧「进主页/合集」被点（刷视频形态下由宿主反射调 hgFeedOpenDetail）。 */
        void onOpenDetail();
        /** 右侧「收藏★」被点（宿主反射调 hgFeedToggleBookmark 并回填状态）。 */
        void onToggleBookmark();
    }

    private final Activity activity;
    private final Host host;

    View topScrim, bottomScrim;
    LinearLayout topBar, bottomBar;
    TextView titleView, metaView, clockView, hintView, errorView;
    IconButton playButton;
    IconButton lockButton;
    /**
     * 锁定态「轻触屏幕」浮现的小提示（「双击解锁」）。不是常驻角标 ——
     * 只在锁定态轻触时浮现约 2 秒后自动淡出，屏幕平时保持干净（对齐抖音/B站）。
     */
    TextView lockChip;
    private long lastLockTap;
    /**
     * 防误触锁。锁着的时候控制栏一律不出来，屏幕上只剩这一个锁 ——
     * 口袋里的误触、手指乱划都不会改进度、切集、退出。
     */
    boolean locked;
    TextView speedButton;
    SeekBar seekBar;
    LinearLayout speedPanel;
    ProgressBar spinner;

    // ── 右侧竖排入口（刷视频用）：圆形封面头像进详情 + 收藏★ ──────────
    LinearLayout rightRail;
    CoverAvatarView railDetailButton;
    IconButton railBookmarkButton;

    private final Runnable hideHint = new Runnable() {
        @Override public void run() { if (hintView != null) hintView.setVisibility(View.GONE); }
    };

    /**
     * 锁定态浮现的小锁 + 提示自动淡出。和 {@link #hintHandler} 共用同一个 Handler
     * 实例（removeCallbacks 才能删掉上一条）。
     */
    private final Runnable hideLockPrompt = new Runnable() {
        @Override public void run() {
            if (lockButton != null && locked) fadeOut(lockButton);
            if (lockChip != null) fadeOut(lockChip);
        }
    };

    /**
     * 收提示专用的 Handler，全类共用一个实例。
     *
     * removeCallbacks 按 Handler 实例匹配消息（MessageQueue 要求 target 是同一个），
     * 每次现起一个的话，上一条还没触发的定时永远删不掉 —— 表现是解锁后的
     * 「已解锁」提示被先前锁定时那次的定时提前收掉。
     */
    private final android.os.Handler hintHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    VlcControlsView(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    // ── 搭建 ──────────────────────────────────────────────────────────

    /**
     * 把控制层铺到 root 上。
     *
     * @param interactive true 时给 root 挂「单击显隐」的触摸监听（单集模式）。
     *                    feed 模式下手势由 RecyclerView 的 OnItemTouchListener 管，
     *                    这里不能抢。
     */
    void attach(FrameLayout root, String title, boolean interactive, View.OnTouchListener touch) {
        // 上下两条渐变压暗。控件浮在渐变上，不再是整条黑块。
        // scrim 跟着控件一起显隐（animateBar 里同步），否则控件收了渐变还在，
        // 上下永远压着两道暗带 —— 「画面上半截发黑」就是它。
        topScrim = scrim(true);
        topScrim.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(130), Gravity.TOP));
        root.addView(topScrim);
        bottomScrim = scrim(false);
        bottomScrim.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(170), Gravity.BOTTOM));
        root.addView(bottomScrim);

        // 错误时不黑屏 —— 黑着屏最说不清是「在缓冲」还是「已经死了」。
        errorView = new TextView(activity);
        errorView.setTextColor(0xFFE9E9EC);
        errorView.setTextSize(14f);
        errorView.setGravity(Gravity.CENTER);
        errorView.setBackground(rounded(0xE6171720, 18));
        errorView.setPadding(dp(26), dp(20), dp(26), dp(20));
        errorView.setVisibility(View.GONE);
        FrameLayout.LayoutParams errorParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        errorParams.setMargins(dp(32), 0, dp(32), 0);
        root.addView(errorView, errorParams);

        spinner = new ProgressBar(activity);
        spinner.setLayoutParams(new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER));
        root.addView(spinner);

        hintView = new TextView(activity);
        hintView.setTextColor(0xFFFFFFFF);
        hintView.setTextSize(14f);
        hintView.setGravity(Gravity.CENTER);
        hintView.setBackground(rounded(0xB3000000, 20));
        hintView.setPadding(dp(20), dp(11), dp(20), dp(11));
        hintView.setVisibility(View.GONE);
        hintView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.addView(hintView);

        buildTopBar(root, title);
        buildBottomBar(root);
        buildSpeedPanel(root);
        buildLockButton(root);
        buildRightRail(root);

        if (interactive && touch != null) root.setOnTouchListener(touch);
        setVisible(true);
    }

    /** 顶栏：返回（圆钮）+ 标题 + 编码信息。整条透明，靠渐变压暗。 */
    private void buildTopBar(FrameLayout root, String title) {
        topBar = new LinearLayout(activity);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(14), dp(10), dp(18), dp(10));

        TextView back = new TextView(activity);
        back.setText("←");
        back.setTextSize(19f);
        back.setTextColor(0xFFFFFFFF);
        back.setGravity(Gravity.CENTER);
        back.setBackground(rounded(0x33000000, 24));
        back.setContentDescription("返回");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { host.onBack(); }
        });
        topBar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout titles = new LinearLayout(activity);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.setMargins(dp(14), 0, 0, 0);
        titles.setLayoutParams(titleParams);

        titleView = new TextView(activity);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(15.5f);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setShadowLayer(dp(2), 0, dp(1), 0x99000000);
        titleView.setText(title == null || title.length() == 0 ? "播放中" : title);
        titles.addView(titleView);

        metaView = new TextView(activity);
        metaView.setTextColor(0xB3FFFFFF);
        metaView.setTextSize(11.5f);
        metaView.setSingleLine(true);
        titles.addView(metaView);
        topBar.addView(titles);

        root.addView(topBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
    }

    /** 底栏：细进度条 + 时间 + 控制按钮。 */
    private void buildBottomBar(FrameLayout root) {
        bottomBar = new LinearLayout(activity);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setPadding(dp(18), dp(8), dp(18), dp(20));

        seekBar = new SeekBar(activity);
        styleSeekBar(seekBar);
        bottomBar.addView(seekBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(4), dp(2), 0);

        clockView = new TextView(activity);
        clockView.setTextColor(0xFFFFFFFF);
        clockView.setTextSize(12f);
        clockView.setText("00:00 / --:--");
        row.addView(clockView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 中间一组要真正居中：左右各放一个等重占位
        LinearLayout group = new LinearLayout(activity);
        group.setOrientation(LinearLayout.HORIZONTAL);
        group.setGravity(Gravity.CENTER_VERTICAL);
        group.addView(makeIconButton(IconButton.REW, new View.OnClickListener() {
            @Override public void onClick(View v) { host.onSeekBy(-10000); }
        }));
        playButton = makeIconButton(IconButton.PAUSE, new View.OnClickListener() {
            @Override public void onClick(View v) { host.onPlayPause(); }
        });
        group.addView(playButton);
        group.addView(makeIconButton(IconButton.FWD, new View.OnClickListener() {
            @Override public void onClick(View v) { host.onSeekBy(10000); }
        }));
        row.addView(group);

        speedButton = makeButton("1×", new View.OnClickListener() {
            @Override public void onClick(View v) { host.onSpeedToggle(); }
        });
        speedButton.setGravity(Gravity.CENTER);
        // 倍速是胶囊，不能被 weight 拉宽 —— 外面包一个等重容器、靠右放
        FrameLayout speedSlot = new FrameLayout(activity);
        speedSlot.addView(speedButton, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL));
        row.addView(speedSlot, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        bottomBar.addView(row);
        root.addView(bottomBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // 进度条拖动：拖动期间不跟着播放头跳，松手才 seek。
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser || host.durationMs() <= 0) return;
                clockView.setText(fmt(host.durationMs() * progress / 1000)
                        + " / " + fmt(host.durationMs()));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { host.onScrubStart(); }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                host.onSeekTo(bar.getProgress());
                // 松手要把自动隐藏的计时重新开起来：onStartTrackingTouch 里停掉了它，
                // 少了这一句，拖过一次进度条之后控制栏就一直挂在那儿不收了。
                host.onScrubEnd();
            }
        });
    }

    /**
     * 锁。贴在左边缘、竖直居中 —— 大拇指够得到，又不挡画面中间。
     *
     * 只有它自己接收点击，别的控件在锁定时全被藏起来（见 setVisible），
     * 所以解锁不会被别的东西挡住。
     */
    private void buildLockButton(FrameLayout root) {
        lockButton = new IconButton(activity);
        lockButton.setIcon(IconButton.UNLOCK);
        lockButton.setContentDescription("锁定屏幕");
        lockButton.setBackground(rounded(0x40000000, 22));
        // 锁定态要「双击」才解开：单击只提示一次。锁的存在意义是防误触，
        // 单击即解等于把防误触反过来用在解锁上 —— 手指蹭一下屏幕就解开了。
        lockButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!locked) { host.onLockToggle(); return; }
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastLockTap <= 900) {
                    lastLockTap = 0;
                    host.onLockToggle();
                    return;
                }
                lastLockTap = now;
                // 再提示一次并续命 2 秒，让用户看清「要双击」。
                flashLockPrompt();
            }
        });
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(44), dp(44),
                Gravity.START | Gravity.CENTER_VERTICAL);
        params.setMargins(dp(10), 0, 0, 0);
        root.addView(lockButton, params);
        // 未锁定时由 setVisible 跟随控制栏显隐 —— 先按不可见起步。
        lockButton.setVisibility(View.INVISIBLE);

        // 锁定态轻触时浮现的小提示（非常驻）。文案随浮现场景变化。
        lockChip = new TextView(activity);
        lockChip.setText("双击解锁");
        lockChip.setTextColor(0xE6FFFFFF);
        lockChip.setTextSize(11f);
        lockChip.setGravity(Gravity.CENTER);
        lockChip.setBackground(rounded(0x66000000, 12));
        lockChip.setPadding(dp(10), dp(5), dp(10), dp(5));
        FrameLayout.LayoutParams chipParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL);
        chipParams.setMargins(dp(14), 0, 0, dp(58));
        root.addView(lockChip, chipParams);
        lockChip.setVisibility(View.GONE);
    }

    /**
     * 锁定态轻触屏幕：浮现小锁 + 「双击解锁」小提示，约 2 秒后自动淡出。
     *
     * <p>这是锁定态下唯一可见的控件（平时锁图标也藏着，屏幕干净）。双击锁 → 解锁；
     * 轻触只「唤醒」它，不会直接解开（防误触）。用 {@link #hintHandler} 定时淡出。
     */
    void flashLockPrompt() {
        if (!locked || lockButton == null) return;
        lockButton.setVisibility(View.VISIBLE);
        lockButton.setAlpha(1f);
        if (lockChip != null) {
            lockChip.setText("双击解锁");
            lockChip.setVisibility(View.VISIBLE);
            lockChip.setAlpha(1f);
        }
        hintHandler.removeCallbacks(hideLockPrompt);
        hintHandler.postDelayed(hideLockPrompt, 2000);
    }

    /** 淡出后置 GONE（保留 alpha 动画，避免生硬消失）。 */
    private void fadeOut(View view) {
        if (view == null) return;
        view.animate().alpha(0f).setDuration(180).withEndAction(new Runnable() {
            @Override public void run() { view.setVisibility(View.GONE); }
        }).start();
    }

    // ── 右侧竖排入口（刷视频）────────────────────────────────────────
    //
    // 两个：头像（进主页/合集=进详情）+ 收藏★。竖排靠右、垂直居中偏上，
    // 不挡画面中心。默认隐藏，由宿主（FEED_LIST 形态）调 showRightRail(true) 打开。
    private void buildRightRail(FrameLayout root) {
        rightRail = new LinearLayout(activity);
        rightRail.setOrientation(LinearLayout.VERTICAL);
        rightRail.setGravity(Gravity.CENTER_HORIZONTAL);
        rightRail.setVisibility(View.GONE);

        railDetailButton = new CoverAvatarView(activity);
        railDetailButton.setContentDescription("进主页/合集");
        railDetailButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { host.onOpenDetail(); }
        });
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(dp(46), dp(46));
        p1.bottomMargin = dp(20);
        rightRail.addView(railDetailButton, p1);

        railBookmarkButton = new IconButton(activity);
        railBookmarkButton.setIcon(IconButton.STAR);
        railBookmarkButton.setContentDescription("收藏");
        railBookmarkButton.setBackground(rounded(0x40000000, 22));
        railBookmarkButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { host.onToggleBookmark(); }
        });
        rightRail.addView(railBookmarkButton, new LinearLayout.LayoutParams(dp(46), dp(46)));

        // 靠右、垂直居中偏上一点（大约屏高 45% 处起排）。
        FrameLayout.LayoutParams railParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL);
        railParams.setMargins(0, 0, dp(12), 0);
        root.addView(rightRail, railParams);
        // 显式提到最前 + 抬 elevation：确保浮在 pager 之上、点击不被下面的 RecyclerView 抢走。
        rightRail.bringToFront();
        railDetailButton.setClickable(true);
        railBookmarkButton.setClickable(true);
    }

    /** 宿主：刷视频形态下才显右侧竖排；退出刷视频/单集隐藏。 */
    void showRightRail(boolean visible) {
        if (rightRail != null) rightRail.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** 收藏星标亮/灭。 */
    void setBookmarked(boolean on) {
        if (railBookmarkButton != null) railBookmarkButton.setFilled(on);
    }

    /** 设置圆形封面头像的图；null = 纯色圆兜底（无封面/未就绪/解密失败）。 */
    void setRailCover(android.graphics.Bitmap bmp) {
        if (railDetailButton != null) railDetailButton.setCover(bmp);
    }

    /**
     * 抖音式圆形封面头像：圆形裁切显示封面 Bitmap，右下角叠红底白「+」小角标（纯装饰，
     * 不单独响应点击 —— 点整个圈都是进详情）。无图时画纯色圆兜底，不崩。
     */
    static final class CoverAvatarView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clipPath = new Path();
        private final RectF oval = new RectF();
        private android.graphics.Bitmap cover;

        CoverAvatarView(Context context) {
            super(context);
            // 半透明黑底（沿用原 railDetailButton 的 0x40000000）—— 无图时就是纯色圆。
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(context.getResources().getDisplayMetrics().density * 1.5f);
            ringPaint.setColor(0x66FFFFFF);   // 细白描边，抖音观感
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);   // clip 圆路径在软件层最稳
        }

        void setCover(android.graphics.Bitmap bmp) {
            this.cover = bmp;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float pad = getResources().getDisplayMetrics().density * 1f;
            oval.set(pad, pad, w - pad, h - pad);
            float r = Math.min(oval.width(), oval.height()) / 2f;
            float cx = w / 2f, cy = h / 2f;

            // 底圆（无图时的纯色兜底，也是头像圆底）。
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x40000000);
            canvas.drawCircle(cx, cy, r, paint);

            if (cover != null && !cover.isRecycled()) {
                // 圆形裁切：save → clipPath(圆) → 画居中裁切的 bitmap（cover 效果）→ restore。
                canvas.save();
                clipPath.reset();
                clipPath.addCircle(cx, cy, r, Path.Direction.CW);
                canvas.clipPath(clipPath);
                // 居中裁切成正方形再铺满圆（避免拉伸变形）。
                float side = Math.min(cover.getWidth(), cover.getHeight());
                float sx = (cover.getWidth() - side) / 2f;
                float sy = (cover.getHeight() - side) / 2f;
                android.graphics.Rect src = new android.graphics.Rect(
                        (int) sx, (int) sy, (int) (sx + side), (int) (sy + side));
                android.graphics.RectF dst = new android.graphics.RectF(cx - r, cy - r, cx + r, cy + r);
                canvas.drawBitmap(cover, src, dst, paint);
                canvas.restore();
            }

            // 细白环。
            canvas.drawCircle(cx, cy, r, ringPaint);

            // 右下角红底白「+」小角标（装饰）。角标直径约半径的 55%，压在下边缘。
            float badgeR = r * 0.30f;
            float bx = cx + r * 0.66f;
            float by = cy + r * 0.66f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFFF2A4D);   // 抖音红
            canvas.drawCircle(bx, by, badgeR, paint);
            // 白色「+」
            paint.setColor(0xFFFFFFFF);
            paint.setStrokeWidth(Math.max(1.5f, badgeR * 0.28f));
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawLine(bx - badgeR * 0.5f, by, bx + badgeR * 0.5f, by, paint);
            canvas.drawLine(bx, by - badgeR * 0.5f, bx, by + badgeR * 0.5f, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    /**
     * 屏幕坐标 (rawX,rawY) 是否落在某个「当前可见、可点击」的 overlay 按钮上。
     *
     * <p>用途：Activity 的 onRootTouch 挂在 root（父 FrameLayout）上，父容器的 OnTouchListener
     * 在 dispatchTouchEvent 阶段先于子 View 收到 DOWN；对 DOWN 返回 true 就消费掉整条事件，
     * 子按钮永远点不到。所以 DOWN 时必须先判「这点是不是按在按钮上」——是就 return false
     * 放行给子 View。
     *
     * <p>坐标口径统一用**屏幕坐标**：event 用 getRawX/getRawY，按钮用 getGlobalVisibleRect
     * （同为屏幕坐标），绝不混用 getX/getY（root 内坐标）。
     *
     * @return true = 命中某 overlay 按钮（调用方应 return false 不消费）
     */
    boolean hitOverlayButton(int rawX, int rawY) {
        // 右侧竖排（刷视频）：进详情 + 收藏，任一可见按钮命中都算。
        if (rightRail != null && rightRail.getVisibility() == View.VISIBLE) {
            if (hitView(railDetailButton, rawX, rawY)) return true;
            if (hitView(railBookmarkButton, rawX, rawY)) return true;
        }
        // 左上锁按钮（未锁时随控制栏显隐；锁定时仅在 flashLockPrompt 浮现期间可见）——
        // 只要当前可见且命中，就放行给子 View，让锁按钮自己处理单击/双击解锁。
        if (lockButton != null && lockButton.getVisibility() == View.VISIBLE) {
            if (hitView(lockButton, rawX, rawY)) return true;
        }
        return false;
    }

    /** 单个 view 的屏幕矩形是否含点（view 必须 VISIBLE 且有面积）。 */
    private boolean hitView(View view, int rawX, int rawY) {
        if (view == null || view.getVisibility() != View.VISIBLE) return false;
        android.graphics.Rect r = new android.graphics.Rect();
        if (!view.getGlobalVisibleRect(r)) return false;
        return hitRect(r.left, r.top, r.right, r.bottom, rawX, rawY);
    }

    /** 纯函数：点是否在矩形内（含边界）。屏幕坐标。单测直接钉。 */
    static boolean hitRect(int left, int top, int right, int bottom, int x, int y) {
        return x >= left && x < right && y >= top && y < bottom;
    }

    /**
     * 锁/解锁。
     *
     * 锁上时立刻收起上下两条栏，只留锁自己在屏上；解开时把控制栏叫回来
     * （宿主的自动隐藏计时会接着收它）。图标跟着换：锁着画合上的锁，解开画
     * 打开的半边 —— 一眼能看出现在能不能划。
     */
    void setLocked(boolean value) {
        locked = value;
        if (lockButton == null) return;
        lockButton.setIcon(value ? IconButton.LOCK : IconButton.UNLOCK);
        lockButton.setContentDescription(value ? "解锁屏幕" : "锁定屏幕");
        if (value) {
            // 锁上：控件全藏，锁图标也淡出 —— 屏幕干净只剩视频。
            // 之后要动锁，靠「轻触屏幕浮现小锁（flashLockPrompt）→ 双击解锁」。
            animateBar(topBar, false);
            animateBar(bottomBar, false);
            if (topScrim != null) topScrim.animate().alpha(0f).setDuration(180).start();
            if (bottomScrim != null) bottomScrim.animate().alpha(0f).setDuration(180).start();
            hideSpeedPanel();
            hintHandler.removeCallbacks(hideLockPrompt);
            fadeOut(lockButton);
            if (lockChip != null) fadeOut(lockChip);
            if (rightRail != null) rightRail.setVisibility(View.GONE);
        } else {
            // 解锁：把控制栏叫回来（宿主自动隐藏计时会接着收它），锁按钮归位为普通控件。
            hintHandler.removeCallbacks(hideLockPrompt);
            if (lockChip != null) { lockChip.setAlpha(1f); lockChip.setVisibility(View.GONE); }
            setVisible(true);
        }
    }

    /** 倍速面板：挂在底栏上方靠右，点击选项即生效。 */
    private void buildSpeedPanel(FrameLayout root) {
        speedPanel = new LinearLayout(activity);
        speedPanel.setOrientation(LinearLayout.VERTICAL);
        speedPanel.setBackground(rounded(0xE6202128, 14));
        speedPanel.setPadding(dp(6), dp(6), dp(6), dp(6));
        speedPanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                dp(120), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.BOTTOM);
        panelParams.setMargins(0, 0, dp(20), dp(96));
        root.addView(speedPanel, panelParams);
    }

    // ── 状态写入 ──────────────────────────────────────────────────────

    void setClock(String text) { if (clockView != null) clockView.setText(text); }

    void setTitle(String text) { if (titleView != null && text != null) titleView.setText(text); }

    void setMeta(String text) { if (metaView != null) metaView.setText(text); }

    void setPlayIcon(int icon) { if (playButton != null) playButton.setIcon(icon); }

    void setSpeedLabel(String text) { if (speedButton != null) speedButton.setText(text); }

    void setError(String text) {
        if (errorView == null) return;
        if (text == null || text.length() == 0) {
            errorView.setVisibility(View.GONE);
            errorView.setText("");
        } else {
            errorView.setText(text);
            errorView.setVisibility(View.VISIBLE);
        }
    }

    void setSpinnerVisible(boolean visible) {
        if (spinner != null) spinner.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** 居中那行提示（「快进 15 秒 · 12:34 / 24:00」）。 */
    void showHint(String text) {
        if (hintView == null || text == null || text.length() == 0) return;
        hintView.setText(text);
        hintView.setVisibility(View.VISIBLE);
    }

    void hideHint() {
        if (hintView != null) hintView.setVisibility(View.GONE);
    }

    /** 与 Activity 侧 hideHintSoon 语义一致：延迟 700ms 再收，立即隐藏会闪一下。 */
    void hideHintSoon() {
        hideHintAfter(700);
    }

    /** 延后收提示。一律走自己的 Handler，前一条没触发的定时才能真正被取消。 */
    void hideHintAfter(long delayMs) {
        hintHandler.removeCallbacks(hideHint);
        hintHandler.postDelayed(hideHint, delayMs);
    }

    // ── 显隐 ──────────────────────────────────────────────────────────

    void setVisible(boolean visible) {
        if (locked) {
            // 锁着：任何「显示控制栏」的调用都不算数，屏幕保持干净（锁也藏着）。
            // 要动锁只能靠 flashLockPrompt()（轻触浮现）。
            animateBar(topBar, false);
            animateBar(bottomBar, false);
            if (topScrim != null) topScrim.animate().alpha(0f).setDuration(180).start();
            if (bottomScrim != null) bottomScrim.animate().alpha(0f).setDuration(180).start();
            hideSpeedPanel();
            if (lockChip != null && lockChip.getVisibility() != View.VISIBLE) {
                lockButton.setVisibility(View.GONE);
            }
            return;
        }
        // 未锁定：锁按钮只是普通控件，跟着控制栏一起显/隐。
        if (lockButton != null) {
            lockButton.setAlpha(1f);
            lockButton.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        }
        animateBar(topBar, visible);
        animateBar(bottomBar, visible);
        // 渐变跟着走：它是给控件垫底的，控件收了它还挂着就是两道死黑带。
        if (topScrim != null) topScrim.animate().alpha(visible ? 1f : 0f).setDuration(180).start();
        if (bottomScrim != null) bottomScrim.animate().alpha(visible ? 1f : 0f).setDuration(180).start();
    }

    private void animateBar(View bar, boolean visible) {
        if (bar == null) return;
        bar.animate().alpha(visible ? 1f : 0f).setDuration(180).start();
        bar.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** 倍速选项回调。 */
    interface RatePicker { void onPick(float rate); }

    /**
     * 显示/隐藏倍速面板。选项内容由这里搭，当前倍速由宿主给 ——
     * 面板长什么样是 UI 的事，现在播到几倍速是播放器的事。
     */
    void toggleSpeedPanel(float current, float[] choices, RatePicker picker) {
        if (speedPanel == null) return;
        if (speedPanel.getVisibility() == View.VISIBLE) {
            speedPanel.setVisibility(View.GONE);
            return;
        }
        speedPanel.removeAllViews();
        for (final float rate : choices) {
            final boolean on = Math.abs(rate - current) < 0.01f;
            TextView item = new TextView(activity);
            item.setText(trimRate(rate) + "×");
            item.setTextSize(14f);
            item.setTextColor(on ? 0xFF4C8DFF : 0xFFF2F2F5);
            item.getPaint().setFakeBoldText(on);
            item.setGravity(Gravity.CENTER);
            item.setPadding(dp(10), dp(13), dp(10), dp(13));
            item.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { picker.onPick(rate); }
            });
            speedPanel.addView(item, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        speedPanel.setVisibility(View.VISIBLE);
    }

    void hideSpeedPanel() {
        if (speedPanel != null) speedPanel.setVisibility(View.GONE);
    }

    static String trimRate(float rate) {
        String text = String.format(java.util.Locale.US, "%.2f", rate);
        if (text.endsWith("0")) text = text.substring(0, text.length() - 1);
        if (text.endsWith(".0")) text = text.substring(0, text.length() - 2);
        return text;
    }

    boolean isBarVisible() {
        return bottomBar != null && bottomBar.getVisibility() == View.VISIBLE;
    }

    // ── 控件工厂 ──────────────────────────────────────────────────────

    int dp(float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    android.graphics.drawable.GradientDrawable rounded(int color, int radiusDp) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private View scrim(boolean fromTop) {
        View view = new View(activity);
        view.setBackground(new android.graphics.drawable.GradientDrawable(
                fromTop ? android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM
                        : android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xD9000000, 0x00000000}));
        return view;
    }

    private IconButton makeIconButton(int icon, View.OnClickListener click) {
        IconButton button = new IconButton(activity);
        button.setIcon(icon);
        button.setContentDescription(icon == IconButton.PAUSE ? "暂停"
                : icon == IconButton.PLAY ? "播放"
                : icon == IconButton.REW ? "快退" : "快进");
        button.setOnClickListener(click);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(46), dp(46)));
        return button;
    }

    private void styleSeekBar(SeekBar bar) {
        // 轨道和圆点共用一个半径：两端才不是「差不多圆」，而是和圆点同心的弧。
        // 半径 = 圆点半径（7dp）要求轨道本身也有 14dp 高 —— 细轨道的圆角最大只能
        // 到「高度的一半」，那样两端是 2dp 的小圆角，跟圆点并排看着就是方头。
        final int knobSize = dp(14);
        final int radius = knobSize / 2;

        final RoundedTrack trackBg = new RoundedTrack(0x40FFFFFF, radius);
        final RoundedTrack trackPro = new RoundedTrack(0xFFFFFFFF, radius);

        // 进度层必须是「按 level 自己改宽度」的 drawable，系统那套 <clip> 不行。
        //
        // 上一次用 ClipDrawable 包一层是对的（裸 GradientDrawable 不认 level，
        // 永远画满整条，真机就是「整条白 + 圆点看不见」），但 clip 只裁边界：
        // 左端能保留圆角，右端会被切成一条竖直的直角边，进度走到中间时右端就是方的。
        // RoundedTrack 每次按 level 重算宽度再画圆角矩形，两端永远是完整半圆。
        android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{trackBg, trackPro});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        bar.setProgressDrawable(layers);

        // 圆点：白底 + 半透明黑描边，压在白色进度上也能看出边界。
        android.graphics.drawable.GradientDrawable knob = new android.graphics.drawable.GradientDrawable();
        knob.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        knob.setColor(0xFFFFFFFF);
        knob.setStroke(dp(1), 0x55000000);
        knob.setSize(knobSize, knobSize);
        bar.setThumb(knob);
        bar.setThumbOffset(0);
        // 不分段：默认会在圆点位置把轨道划开一道缝，两端圆弧就断了。
        bar.setSplitTrack(false);
        // 左右各留一个半径，0% / 100% 时圆点正好落在两端半圆的圆心上，不被切。
        bar.setPadding(radius, dp(6), radius, dp(6));

        // 满值必须是 1000 —— 全链路用的都是千分比：
        // VlcPlayerActivity 里两处 setProgress 都是 {@code time*1000/durationMs}，
        // onSeekTo 收到的也是千分比。SeekBar 默认 max=100，不设就会被截到 100，
        // 播放头永远钉在最右端 —— 和「整条白、没圆点」是同一个现场。
        bar.setMax(1000);
    }

    /**
     * 两端半圆、中间矩形的进度块，宽度随 {@code level} 走。
     *
     * <p>SeekBar 只对 id=progress 那一层 {@code setLevel(0..10000)}，所以这里
     * 在 {@link #draw} 里按 level 算出宽度，再画一条圆角矩形 —— 圆角半径固定，
     * 宽度小于两倍半径时自然退化成两段半圆相接，不会画出一坨。
     */
    private static final class RoundedTrack extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF rect = new android.graphics.RectF();

        RoundedTrack(int color, int radius) {
            paint.setColor(color);
            this.radius = radius;
        }

        private final int radius;

        @Override public void draw(android.graphics.Canvas canvas) {
            final android.graphics.Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;
            final int w = Math.max(0, Math.round(b.width() * (getLevel() / 10000f)));
            if (w <= 0) return;
            rect.set(b.left, b.top, b.left + w, b.bottom);
            canvas.drawRoundRect(rect, radius, radius, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { paint.setColorFilter(cf); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }

        /** level 变了要重画（默认实现只标记 dirty，不会通知重绘）。 */
        @Override protected boolean onLevelChange(int level) {
            invalidateSelf();
            return true;
        }
    }

    private TextView makeButton(String text, View.OnClickListener click) {
        return makeButton(text, click, false);
    }

    private TextView makeButton(String text, View.OnClickListener click, boolean primary) {
        TextView button = new TextView(activity);
        button.setText(text);
        button.setTextSize(primary ? 15f : 12.5f);
        button.setTextColor(0xFFFFFFFF);
        button.setGravity(Gravity.CENTER);
        button.setBackground(primary ? rounded(0x30FFFFFF, 24) : rounded(0x1FFFFFFF, 19));
        button.setOnClickListener(click);
        button.setPadding(primary ? dp(8) : dp(14), dp(9), primary ? dp(8) : dp(14), dp(9));
        button.setLayoutParams(new LinearLayout.LayoutParams(
                primary ? dp(48) : ViewGroup.LayoutParams.WRAP_CONTENT,
                primary ? dp(48) : ViewGroup.LayoutParams.WRAP_CONTENT));
        return button;
    }

    /** 沉浸式全屏。上滑调出系统栏是系统行为，不拦。 */
    static void applyImmersive(Activity activity) {
        try {
            Window window = activity.getWindow();
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false);
                android.view.WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    controller.hide(android.view.WindowInsets.Type.systemBars());
                    controller.setSystemBarsBehavior(
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                window.getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }
        } catch (Throwable ignored) {}
    }

    static String fmt(long ms) {
        if (ms <= 0) return "00:00";
        long total = ms / 1000;
        long hour = total / 3600;
        long minute = (total % 3600) / 60;
        long second = total % 60;
        if (hour > 0) return String.format(java.util.Locale.US, "%d:%02d:%02d", hour, minute, second);
        return String.format(java.util.Locale.US, "%02d:%02d", minute, second);
    }

    /**
     * 播放控制键：图标用 Canvas 画，不走字符 —— ⏪⏸ 那类字符在小米上是彩色 emoji。
     */
    static class IconButton extends View {
        static final int PAUSE = 0, PLAY = 1, REW = 2, FWD = 3, LOCK = 4, UNLOCK = 5, PERSON = 6, STAR = 7;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int icon = PLAY;
        /** STAR 用：true=已收藏（实心亮），false=未收藏（空心）。 */
        private boolean filled;

        IconButton(Context context) { super(context); }
        IconButton(Context context, AttributeSet attrs) { super(context, attrs); }

        void setIcon(int which) { icon = which; invalidate(); }

        /** 收藏星标：切实心/空心。 */
        void setFilled(boolean value) { filled = value; invalidate(); }

        @Override protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float u = getWidth() / 12f;
            paint.setColor(0xFFFFFFFF);
            if (icon == PAUSE) {
                float w = u;
                canvas.drawRect(cx - u * 1.5f - w / 2, cy - u * 2f, cx - u * 1.5f + w / 2, cy + u * 2f, paint);
                canvas.drawRect(cx + u * 1.5f - w / 2, cy - u * 2f, cx + u * 1.5f + w / 2, cy + u * 2f, paint);
            } else if (icon == PLAY) {
                Path path = new Path();
                path.moveTo(cx - u * 1.2f, cy - u * 2f);
                path.lineTo(cx + u * 1.8f, cy);
                path.lineTo(cx - u * 1.2f, cy + u * 2f);
                path.close();
                canvas.drawPath(path, paint);
            } else if (icon == LOCK || icon == UNLOCK) {
                // 挂锁：锁体一个圆角方块，锁梁一段圆弧。UNLOCK 时锁梁向右上张开，
                // 并且整体往左下挪一点，形状上直接和 LOCK 区分开。
                float bodyW = u * 2.6f, bodyH = u * 2.1f;
                float bx = cx - bodyW / 2f, by = cy - u * 0.1f;
                RectF body = new RectF(bx, by, bx + bodyW, by + bodyH);
                canvas.drawRoundRect(body, u * 0.5f, u * 0.5f, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(u * 0.55f);
                RectF shackle;
                if (icon == LOCK) {
                    shackle = new RectF(cx - u * 1.05f, cy - u * 2.6f, cx + u * 1.05f, cy + u * 0.4f);
                    canvas.drawArc(shackle, 180f, 180f, false, paint);
                } else {
                    // 左半边固定在锁体上，右半边往上掀开
                    shackle = new RectF(cx - u * 0.35f, cy - u * 2.8f, cx + u * 1.75f, cy + u * 0.2f);
                    canvas.drawArc(shackle, 200f, 150f, false, paint);
                    canvas.drawLine(cx - u * 0.35f, cy - u * 1.3f, cx - u * 0.35f, cy, paint);
                }
                paint.setStyle(Paint.Style.FILL);
            } else if (icon == PERSON) {
                // 头像式「进主页/合集」：一个圆头 + 半圆肩。
                canvas.drawCircle(cx, cy - u * 1.1f, u * 1.3f, paint);
                Path shoulders = new Path();
                shoulders.addArc(new RectF(cx - u * 2.4f, cy + u * 0.1f, cx + u * 2.4f, cy + u * 4.9f),
                        180f, 180f);
                shoulders.close();
                canvas.drawPath(shoulders, paint);
            } else if (icon == STAR) {
                // 收藏星标：五角星。filled=实心（已收藏），否则描边（未收藏）。
                drawStar(canvas, cx, cy, u * 2.6f, u * 1.1f, filled);
            } else {
                boolean fwd = icon == FWD;
                for (int i = 0; i < 2; i++) {
                    float off = (fwd ? 1 : -1) * (i == 0 ? u * 0.6f : u * 2.6f);
                    Path path = new Path();
                    if (fwd) {
                        path.moveTo(cx + off - u * 1.1f, cy - u * 1.6f);
                        path.lineTo(cx + off + u * 0.9f, cy);
                        path.lineTo(cx + off - u * 1.1f, cy + u * 1.6f);
                    } else {
                        path.moveTo(cx + off + u * 1.1f, cy - u * 1.6f);
                        path.lineTo(cx + off - u * 0.9f, cy);
                        path.lineTo(cx + off + u * 1.1f, cy + u * 1.6f);
                    }
                    path.close();
                    canvas.drawPath(path, paint);
                }
            }
        }

        /** 画五角星。filled=实心（金色），否则描边（白色细线）。 */
        private void drawStar(Canvas canvas, float cx, float cy, float outer, float inner, boolean filled) {
            Path star = new Path();
            double start = -Math.PI / 2;   // 顶点朝上
            for (int i = 0; i < 10; i++) {
                double r = (i % 2 == 0) ? outer : inner;
                double a = start + i * Math.PI / 5;
                float x = (float) (cx + Math.cos(a) * r);
                float y = (float) (cy + Math.sin(a) * r);
                if (i == 0) star.moveTo(x, y); else star.lineTo(x, y);
            }
            star.close();
            if (filled) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFFFFC107);   // 金色
                canvas.drawPath(star, paint);
                paint.setColor(0xFFFFFFFF);   // 复原，别污染后续绘制
            } else {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, outer * 0.09f));
                paint.setColor(0xFFFFFFFF);
                canvas.drawPath(star, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }
    }
}
