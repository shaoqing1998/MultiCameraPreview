package com.example.myapplication;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.opengl.GLSurfaceView;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

public class CameraFrame extends LinearLayout {

    private GLSurfaceView glSurfaceView;
    private DeinterlaceRenderer deinterlaceRenderer;
    private boolean useDeinterlace = false;
    private boolean deinterlaceEnabled = true;
    private boolean isNtscMode = true;
    private boolean openGLPassthrough = false;

    private FrameLayout videoContainer;
    private FrameLayout textureContainer;
    private FrameLayout contentRoot;
    private LinearLayout titleBar;
    private TextView titleText;
    private TextView fpsText;
    private TextView resolutionText;
    private TextView displaySizeText;
    private TextView lockText;
    private TextView oneToOneText;
    private View rotateButton;
    private TextView fullscreenButton;
    private CornerResizeView resizeHandle;
    private int resizeHandleRotation = 0;
    private float resizeStartX, resizeStartY;
    private int resizeStartW, resizeStartH;
    private float resizeDownRawX, resizeDownRawY;
    private ProgressBar loadingSpinner;
    private LinearLayout loadingLayout;
    private TextView loadingText;
    private TextView openDurationText;
    private long openStartElapsedMs;
    private final Runnable loadingTick = new Runnable() {
        @Override
        public void run() {
            if (loadingLayout == null || loadingLayout.getVisibility() != VISIBLE) return;
            updateLoadingElapsed();
            postDelayed(this, 100);
        }
    };
    private android.view.TextureView textureView;

    private String cameraId;
    private float dX, dY;
    private boolean isResizing = false;
    private float currentRotation = 0;
    private boolean aspectLocked = true;
    private boolean oneToOneMode = false;
    private boolean isFullscreen = false;
    private float aspectRatio = 4f / 3f;
    private static final int MIN_SIZE = 160;
    private int themeColor = Color.parseColor("#7DA8C4");
    private Size currentResolution;
    private int currentFps = 30;
    private boolean letterboxWhite = false;
    private boolean deinterlacePresetActive = false;
    private Size defaultCaptureResolution;
    private boolean titleBarVisible = false;
    private View touchLayer;
    private boolean isDragging = false;
    private float downRawX, downRawY;
    private int snapRange;
    private int tapSlop;

    private static final int DEINTERLACE_WIDTH = 720;
    private static final int DEINTERLACE_NTSC_HEIGHT = 480;
    private static final int DEINTERLACE_PAL_HEIGHT = 576;

    private OnResolutionChangeListener resolutionChangeListener;
    private OnFpsChangeListener fpsChangeListener;
    private OnFullscreenChangeListener fullscreenChangeListener;
    private OnGeometryChangeListener geometryChangeListener;

    private static final int COLOR_BG_DARK = Color.parseColor("#2D2D2D");
    private static final int COLOR_BG_HEADER = Color.parseColor("#3A3A3A");
    private static final int COLOR_TEXT_PRIMARY = Color.parseColor("#D4D4D4");
    private static final int COLOR_TEXT_SECONDARY = Color.parseColor("#8A8A8A");
    private static final int COLOR_BORDER = Color.parseColor("#505050");
    private static final int COLOR_1TO1_ACTIVE = Color.parseColor("#7DB87D");
    private static final int COLOR_FULLSCREEN_ACTIVE = Color.parseColor("#D4A574");

    public interface OnResolutionChangeListener {
        void onResolutionChange(String cameraId, Size newResolution);
        Size[] getAvailableResolutions(String cameraId);
    }

    public interface OnFpsChangeListener {
        void onFpsChange(String cameraId, int newFps);
    }

    public interface OnFullscreenChangeListener {
        void onFullscreenChange(String cameraId, boolean isFullscreen);
    }

    public interface OnGeometryChangeListener {
        void onGeometryChange(CameraFrame frame);
    }

    public CameraFrame(Context context) {
        super(context);
        init(context);
    }

    private void init(Context context) {
        setOrientation(VERTICAL);
        snapRange = Math.round(8 * context.getResources().getDisplayMetrics().density);
        tapSlop = Math.round(16 * context.getResources().getDisplayMetrics().density);

        GradientDrawable bgDrawable = new GradientDrawable();
        bgDrawable.setColor(COLOR_BG_DARK);
        bgDrawable.setCornerRadius(0);
        bgDrawable.setStroke(1, COLOR_BORDER);
        setBackground(bgDrawable);
        setClipToOutline(true);
        disableSelectionHighlight(this);
        setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRect(0, 0, view.getWidth(), view.getHeight());
            }
        });

        // === 标题栏（叠在画面内，拖动优先于子控件） ===
        titleBar = new LinearLayout(context) {
            @Override
            public boolean onInterceptTouchEvent(MotionEvent ev) {
                if (isFullscreen) return false;
                switch (ev.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dX = CameraFrame.this.getX() - ev.getRawX();
                        dY = CameraFrame.this.getY() - ev.getRawY();
                        downRawX = ev.getRawX();
                        downRawY = ev.getRawY();
                        isDragging = false;
                        return false;
                    case MotionEvent.ACTION_MOVE: {
                        float dist = Math.abs(ev.getRawX() - downRawX)
                                + Math.abs(ev.getRawY() - downRawY);
                        if (dist > tapSlop) {
                            isDragging = true;
                            CameraFrame.this.bringToFront();
                            return true;
                        }
                        return false;
                    }
                    default:
                        return false;
                }
            }

            @Override
            public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
                // 忽略子 View（如横向滚动标题）阻止拦截，保证标题栏可以拖动窗口
            }

            @Override
            public boolean onTouchEvent(MotionEvent event) {
                return handleDrag(event, false);
            }
        };
        titleBar.setOrientation(HORIZONTAL);
        titleBar.setPadding(10, 8, 10, 8);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.setBackgroundColor(Color.parseColor("#CC3A3A3A"));
        titleBar.setMinimumHeight(Math.round(48 * context.getResources().getDisplayMetrics().density));
        titleBar.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        disableSelectionHighlight(titleBar);

        titleText = new TextView(context);
        titleText.setText("Cam ?");
        titleText.setTextColor(COLOR_TEXT_PRIMARY);
        titleText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        titleText.setTypeface(null, Typeface.BOLD);
        titleText.setSingleLine(true);
        titleText.setIncludeFontPadding(false);
        titleText.setGravity(Gravity.CENTER_VERTICAL);

        fpsText = new TextView(context);
        fpsText.setText("[30fps]");
        fpsText.setTextColor(COLOR_TEXT_SECONDARY);
        fpsText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        fpsText.setPadding(10, 0, 0, 0);
        fpsText.setSingleLine(true);
        fpsText.setIncludeFontPadding(false);
        fpsText.setGravity(Gravity.CENTER_VERTICAL);
        fpsText.setOnClickListener(v -> showFpsDialog());

        resolutionText = new TextView(context);
        resolutionText.setText("[采集: --×--]");
        resolutionText.setTextColor(COLOR_TEXT_SECONDARY);
        resolutionText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        resolutionText.setPadding(8, 0, 0, 0);
        resolutionText.setSingleLine(true);
        resolutionText.setIncludeFontPadding(false);
        resolutionText.setGravity(Gravity.CENTER_VERTICAL);
        resolutionText.setOnClickListener(v -> showResolutionDialog());

        displaySizeText = new TextView(context);
        displaySizeText.setText("[显示: --×--]");
        displaySizeText.setTextColor(COLOR_TEXT_SECONDARY);
        displaySizeText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        displaySizeText.setPadding(8, 0, 0, 0);
        displaySizeText.setSingleLine(true);
        displaySizeText.setIncludeFontPadding(false);
        displaySizeText.setGravity(Gravity.CENTER_VERTICAL);

        lockText = new TextView(context);
        lockText.setText("等比");
        lockText.setTextColor(COLOR_TEXT_PRIMARY);
        lockText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        lockText.setIncludeFontPadding(false);
        lockText.setPadding(8, 4, 8, 4);
        lockText.setMinWidth(44);
        lockText.setMinHeight(40);
        lockText.setGravity(Gravity.CENTER);
        lockText.setOnClickListener(v -> {
            aspectLocked = !aspectLocked;
            if (aspectLocked && oneToOneMode) {
                oneToOneMode = false;
                updateOneToOneButtonState();
            }
            lockText.setText(aspectLocked ? "等比" : "自由");
            lockText.setTextColor(aspectLocked ? COLOR_TEXT_PRIMARY : COLOR_TEXT_SECONDARY);
            updateTextureViewSize();
            notifyGeometryChanged();
        });

        oneToOneText = new TextView(context);
        oneToOneText.setText("1:1");
        oneToOneText.setTextColor(COLOR_TEXT_SECONDARY);
        oneToOneText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        oneToOneText.setIncludeFontPadding(false);
        oneToOneText.setPadding(8, 4, 8, 4);
        oneToOneText.setMinWidth(40);
        oneToOneText.setMinHeight(40);
        oneToOneText.setGravity(Gravity.CENTER);
        oneToOneText.setOnClickListener(v -> {
            oneToOneMode = !oneToOneMode;
            if (oneToOneMode) {
                aspectLocked = false;
                lockText.setText("自由");
                lockText.setTextColor(COLOR_TEXT_SECONDARY);
                applyOneToOneSize();
            }
            updateOneToOneButtonState();
            updateTextureViewSize();
            notifyGeometryChanged();
        });

        fullscreenButton = new TextView(context);
        fullscreenButton.setText("[ ]");
        fullscreenButton.setTextColor(COLOR_TEXT_PRIMARY);
        fullscreenButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        fullscreenButton.setIncludeFontPadding(false);
        fullscreenButton.setPadding(8, 4, 6, 4);
        fullscreenButton.setMinWidth(44);
        fullscreenButton.setMinHeight(40);
        fullscreenButton.setGravity(Gravity.CENTER);
        fullscreenButton.setOnClickListener(v -> toggleFullscreen());

        rotateButton = new RotateIconView(context);
        int rotateSize = Math.round(22 * context.getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams rotateParams = new LinearLayout.LayoutParams(rotateSize, rotateSize);
        rotateParams.gravity = Gravity.CENTER_VERTICAL;
        rotateParams.setMargins(4, 0, 2, 0);
        rotateButton.setLayoutParams(rotateParams);
        rotateButton.setOnClickListener(v -> {
            currentRotation += 90;
            if (currentRotation >= 360) currentRotation = 0;
            applyContentRotation();
            notifyGeometryChanged();
        });

        LinearLayout infoRow = new LinearLayout(context);
        infoRow.setOrientation(HORIZONTAL);
        infoRow.setGravity(Gravity.CENTER_VERTICAL);
        infoRow.addView(titleText);
        infoRow.addView(fpsText);
        infoRow.addView(resolutionText);
        infoRow.addView(displaySizeText);

        HorizontalScrollView infoScroll = new HorizontalScrollView(context);
        infoScroll.setHorizontalScrollBarEnabled(false);
        infoScroll.setFillViewport(false);
        infoScroll.setOverScrollMode(OVER_SCROLL_NEVER);
        infoScroll.addView(infoRow, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        infoScroll.setLayoutParams(scrollParams);

        titleBar.addView(infoScroll);
        titleBar.addView(lockText);
        titleBar.addView(oneToOneText);
        titleBar.addView(fullscreenButton);
        titleBar.addView(rotateButton);
        titleBar.setClickable(true);
        titleBar.setVisibility(View.GONE);

        // === 视频容器（标题栏叠在画面内部） ===
        videoContainer = new FrameLayout(context);
        videoContainer.setBackgroundColor(Color.BLACK);
        disableSelectionHighlight(videoContainer);

        textureContainer = new FrameLayout(context);
        textureView = new android.view.TextureView(context);
        textureContainer.addView(textureView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams textureContainerParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        textureContainerParams.gravity = Gravity.CENTER;
        videoContainer.addView(textureContainer, textureContainerParams);

        // 加载布局
        loadingLayout = new LinearLayout(context);
        loadingLayout.setOrientation(LinearLayout.VERTICAL);
        loadingLayout.setGravity(Gravity.CENTER);
        loadingLayout.setBackgroundColor(Color.BLACK);

        loadingSpinner = new ProgressBar(context, null, android.R.attr.progressBarStyleLarge);
        loadingSpinner.setIndeterminate(true);
        try {
            loadingSpinner.getIndeterminateDrawable().setColorFilter(
                    Color.parseColor("#7DA8C4"), android.graphics.PorterDuff.Mode.SRC_IN);
        } catch (Exception e) {}

        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(80, 80);
        loadingLayout.addView(loadingSpinner, spinnerParams);

        loadingText = new TextView(context);
        loadingText.setText("正在打开摄像头...");
        loadingText.setTextColor(COLOR_TEXT_SECONDARY);
        loadingText.setTextSize(14);
        loadingText.setGravity(Gravity.CENTER);
        loadingText.setPadding(0, 12, 0, 0);
        loadingLayout.addView(loadingText);

        FrameLayout.LayoutParams loadingParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        videoContainer.addView(loadingLayout, loadingParams);

        openDurationText = new TextView(context);
        openDurationText.setTextColor(Color.WHITE);
        openDurationText.setTextSize(13);
        openDurationText.setPadding(14, 8, 14, 8);
        openDurationText.setVisibility(View.GONE);
        GradientDrawable durationBg = new GradientDrawable();
        durationBg.setColor(Color.parseColor("#CC1A1A1A"));
        durationBg.setCornerRadius(8);
        openDurationText.setBackground(durationBg);
        FrameLayout.LayoutParams durationParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        durationParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        durationParams.setMargins(0, 0, 0, 16);
        videoContainer.addView(openDurationText, durationParams);

        startOpenTimer();

        touchLayer = new View(context);
        touchLayer.setClickable(true);
        disableSelectionHighlight(touchLayer);
        touchLayer.setOnTouchListener((v, event) -> handleDrag(event, true));
        videoContainer.addView(touchLayer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        contentRoot = new FrameLayout(context);
        contentRoot.setClipChildren(false);
        disableSelectionHighlight(contentRoot);
        contentRoot.addView(videoContainer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        titleParams.gravity = Gravity.TOP;
        contentRoot.addView(titleBar, titleParams);

        resizeHandle = new CornerResizeView(context);
        FrameLayout.LayoutParams handleParams = new FrameLayout.LayoutParams(48, 48);
        handleParams.gravity = Gravity.BOTTOM | Gravity.END;
        handleParams.setMargins(0, 0, 6, 6);
        contentRoot.addView(resizeHandle, handleParams);

        addView(contentRoot, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setClipChildren(false);
        setupTouchHandling();
    }

    private void disableSelectionHighlight(View view) {
        view.setSoundEffectsEnabled(false);
        view.setHapticFeedbackEnabled(false);
        view.setForeground(null);
        if (Build.VERSION.SDK_INT >= 26) {
            view.setDefaultFocusHighlightEnabled(false);
        }
    }

    private boolean isQuarterTurn() {
        int r = ((int) currentRotation) % 180;
        return r == 90 || r == -90;
    }

    private void applyContentRotation() {
        setRotation(0);
        if (videoContainer != null) {
            videoContainer.setRotation(0);
        }
        if (textureContainer != null) {
            textureContainer.setRotation(currentRotation);
        }
        layoutTitleBar();
        post(() -> {
            layoutTitleBar();
            updateTextureViewSize();
        });
    }

    private void layoutTitleBar() {
        if (contentRoot == null || titleBar == null || isFullscreen) return;
        int frameW = contentRoot.getWidth();
        int frameH = contentRoot.getHeight();
        if (frameW <= 0 || frameH <= 0) return;

        int rot = ((int) currentRotation) % 360;
        if (rot < 0) rot += 360;

        int edge = (rot == 90 || rot == 270) ? frameH : frameW;
        int specW = View.MeasureSpec.makeMeasureSpec(Math.max(frameW, frameH), View.MeasureSpec.EXACTLY);
        int specH = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        titleBar.measure(specW, specH);
        int barH = titleBar.getMeasuredHeight();
        if (barH <= 0) {
            barH = Math.round(48 * getResources().getDisplayMetrics().density);
        }

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) titleBar.getLayoutParams();
        lp.width = edge;
        lp.height = barH;
        lp.gravity = Gravity.TOP | Gravity.START;
        titleBar.setLayoutParams(lp);

        float x;
        float y;
        switch (rot) {
            case 90:
                x = frameW - barH / 2f - edge / 2f;
                y = frameH / 2f - barH / 2f;
                break;
            case 180:
                x = 0;
                y = frameH - barH;
                break;
            case 270:
                x = barH / 2f - edge / 2f;
                y = frameH / 2f - barH / 2f;
                break;
            default:
                x = 0;
                y = 0;
                break;
        }
        titleBar.setPivotX(edge / 2f);
        titleBar.setPivotY(barH / 2f);
        titleBar.setRotation(rot);
        titleBar.setX(x);
        titleBar.setY(y);
        layoutResizeHandle(rot);
    }

    private void layoutResizeHandle(int rot) {
        if (resizeHandle == null) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) resizeHandle.getLayoutParams();
        int m = 6;
        switch (rot) {
            case 90:
                lp.gravity = Gravity.BOTTOM | Gravity.START;
                lp.setMargins(m, 0, 0, m);
                resizeHandleRotation = 90;
                break;
            case 180:
                lp.gravity = Gravity.TOP | Gravity.END;
                lp.setMargins(0, m, m, 0);
                resizeHandleRotation = 270;
                break;
            case 270:
                lp.gravity = Gravity.BOTTOM | Gravity.END;
                lp.setMargins(0, 0, m, m);
                resizeHandleRotation = 0;
                break;
            default:
                lp.gravity = Gravity.BOTTOM | Gravity.END;
                lp.setMargins(0, 0, m, m);
                resizeHandleRotation = 0;
                break;
        }
        resizeHandle.setLayoutParams(lp);
        resizeHandle.setRotation(resizeHandleRotation);
    }

    private void toggleFullscreen() {
        isFullscreen = !isFullscreen;
        applyFullscreenUi();
        if (fullscreenChangeListener != null) {
            fullscreenChangeListener.onFullscreenChange(cameraId, isFullscreen);
        }
    }

    private void applyFullscreenUi() {
        titleBar.setVisibility(isFullscreen ? View.GONE : (titleBarVisible ? View.VISIBLE : View.GONE));
        resizeHandle.setVisibility(isFullscreen ? View.GONE : View.VISIBLE);
        setClipToOutline(!isFullscreen);
        if (isFullscreen) {
            setBackgroundColor(Color.BLACK);
        } else {
            GradientDrawable bgDrawable = new GradientDrawable();
            bgDrawable.setColor(COLOR_BG_DARK);
            bgDrawable.setCornerRadius(0);
            bgDrawable.setStroke(1, COLOR_BORDER);
            setBackground(bgDrawable);
            updateFullscreenButtonState();
            post(this::layoutTitleBar);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (isFullscreen) {
            if (ev.getAction() == MotionEvent.ACTION_UP) {
                toggleFullscreen();
            }
            return true;
        }
        return super.dispatchTouchEvent(ev);
    }

    private void updateFullscreenButtonState() {
        if (isFullscreen) {
            fullscreenButton.setText("[X]");
            fullscreenButton.setTextColor(COLOR_FULLSCREEN_ACTIVE);
            fullscreenButton.setTypeface(null, Typeface.BOLD);
        } else {
            fullscreenButton.setText("[ ]");
            fullscreenButton.setTextColor(COLOR_TEXT_PRIMARY);
            fullscreenButton.setTypeface(null, Typeface.NORMAL);
        }
    }

    public boolean isFullscreen() {
        return isFullscreen;
    }

    public void setFullscreen(boolean fullscreen) {
        if (this.isFullscreen != fullscreen) {
            toggleFullscreen();
        }
    }

    public void setOnFullscreenChangeListener(OnFullscreenChangeListener listener) {
        this.fullscreenChangeListener = listener;
    }

    public void setOnGeometryChangeListener(OnGeometryChangeListener listener) {
        this.geometryChangeListener = listener;
    }

    @Override
    public void bringToFront() {
        super.bringToFront();
        notifyGeometryChanged();
    }

    private void notifyGeometryChanged() {
        if (geometryChangeListener != null && !isFullscreen) {
            geometryChangeListener.onGeometryChange(this);
        }
    }

    public void restoreViewFlags(boolean locked, boolean oneToOne) {
        this.aspectLocked = locked;
        this.oneToOneMode = oneToOne;
        if (oneToOne) {
            this.aspectLocked = false;
        }
        lockText.setText(this.aspectLocked ? "等比" : "自由");
        lockText.setTextColor(this.aspectLocked ? COLOR_TEXT_PRIMARY : COLOR_TEXT_SECONDARY);
        updateOneToOneButtonState();
    }

    private void updateOneToOneButtonState() {
        oneToOneText.setTextColor(oneToOneMode ? COLOR_1TO1_ACTIVE : COLOR_TEXT_SECONDARY);
        oneToOneText.setTypeface(null, oneToOneMode ? Typeface.BOLD : Typeface.NORMAL);
    }

    private int getDeinterlaceOutputWidth() {
        return DEINTERLACE_WIDTH;
    }

    private int getDeinterlaceOutputHeight() {
        return isNtscMode ? DEINTERLACE_NTSC_HEIGHT : DEINTERLACE_PAL_HEIGHT;
    }

    private void applyOneToOneSize() {
        int targetWidth, targetHeight;

        if (useDeinterlace && !openGLPassthrough && !is360Mode()) {
            targetWidth = getDeinterlaceOutputWidth();
            targetHeight = getDeinterlaceOutputHeight();
        } else if (currentResolution != null) {
            targetWidth = currentResolution.getWidth();
            targetHeight = currentResolution.getHeight();
        } else {
            return;
        }

        android.view.ViewGroup.LayoutParams params = getLayoutParams();
        params.width = targetWidth;
        params.height = targetHeight;
        setLayoutParams(params);

        post(() -> updateTextureViewSize());
    }

    public void enableDeinterlaceMode(boolean ntsc) {
        try {
            useDeinterlace = true;
            deinterlaceEnabled = true;
            openGLPassthrough = false;
            isNtscMode = ntsc;
            deinterlacePresetActive = false;

            // CVBS 默认等比，但可以手动切换
            aspectLocked = true;
            lockText.setText("等比");
            lockText.setTextColor(COLOR_TEXT_PRIMARY);

            aspectRatio = (float) getDeinterlaceOutputWidth() / getDeinterlaceOutputHeight();

            teardownGlSurface();
            textureContainer.removeAllViews();

            glSurfaceView = new GLSurfaceView(getContext());
            glSurfaceView.setEGLContextClientVersion(2);

            deinterlaceRenderer = new DeinterlaceRenderer(glSurfaceView, ntsc);

            glSurfaceView.setRenderer(deinterlaceRenderer);
            glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

            if (ntsc) {
                deinterlaceRenderer.setNtscMode();
            } else {
                deinterlaceRenderer.setPalMode();
            }

            textureContainer.addView(glSurfaceView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));

            updateResolutionText();
            setLetterboxWhite(letterboxWhite);

        } catch (Exception e) {
            android.util.Log.e("CameraFrame", "enableDeinterlaceMode failed", e);
            disableDeinterlaceMode();
        }
    }

    public void enable360Mode() {
        try {
            useDeinterlace = true;  // 复用 GL 渲染路径
            deinterlaceEnabled = false;
            openGLPassthrough = false;
            isNtscMode = true;

            aspectLocked = true;
            lockText.setText("等比");
            lockText.setTextColor(COLOR_TEXT_PRIMARY);

            // 360 直通模式: 输出 2×2 网格 (3840×2160) = 16:9
            aspectRatio = 16.0f / 9.0f;

            teardownGlSurface();
            textureContainer.removeAllViews();

            glSurfaceView = new GLSurfaceView(getContext());
            glSurfaceView.setEGLContextClientVersion(2);

            deinterlaceRenderer = new DeinterlaceRenderer(glSurfaceView, true);
            deinterlaceRenderer.set360Mode(true);
            // 直通模式不需要旧的 de_vc round-robin 参数

            glSurfaceView.setRenderer(deinterlaceRenderer);
            glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

            textureContainer.addView(glSurfaceView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));

            updateResolutionText();
            setLetterboxWhite(letterboxWhite);
        } catch (Exception e) {
            android.util.Log.e("CameraFrame", "enable360Mode failed", e);
            disableDeinterlaceMode();
        }
    }

    public boolean is360Mode() {
        return deinterlaceRenderer != null && deinterlaceRenderer.is360Mode();
    }

    public void enableOpenGLMode() {
        try {
            useDeinterlace = true;
            deinterlaceEnabled = false;
            openGLPassthrough = true;
            isNtscMode = true;

            aspectLocked = true;
            lockText.setText("等比");
            lockText.setTextColor(COLOR_TEXT_PRIMARY);

            if (currentResolution != null) {
                aspectRatio = (float) currentResolution.getWidth() / currentResolution.getHeight();
            }

            teardownGlSurface();
            textureContainer.removeAllViews();

            glSurfaceView = new GLSurfaceView(getContext());
            glSurfaceView.setEGLContextClientVersion(2);

            deinterlaceRenderer = new DeinterlaceRenderer(glSurfaceView, true);
            deinterlaceRenderer.setDeinterlaceEnabled(false);

            glSurfaceView.setRenderer(deinterlaceRenderer);
            glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

            textureContainer.addView(glSurfaceView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));

            updateResolutionText();
            setLetterboxWhite(letterboxWhite);
        } catch (Exception e) {
            android.util.Log.e("CameraFrame", "enableOpenGLMode failed", e);
            disableDeinterlaceMode();
        }
    }

    public boolean isOpenGLPassthrough() {
        return openGLPassthrough;
    }

    public boolean isUsingGL() {
        return glSurfaceView != null;
    }

    public void disableDeinterlaceMode() {
        useDeinterlace = false;
        openGLPassthrough = false;
        teardownGlSurface();

        textureContainer.removeAllViews();
        textureView = new android.view.TextureView(getContext());
        textureContainer.addView(textureView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
    }

    public void setSurfaceReadyListener(DeinterlaceRenderer.OnSurfaceReadyListener listener) {
        if (deinterlaceRenderer != null) {
            deinterlaceRenderer.setOnSurfaceReadyListener(listener);
        }
    }

    public void setOnFirstFrameListener(Runnable listener) {
        if (deinterlaceRenderer != null) {
            deinterlaceRenderer.setOnFirstFrameListener(listener);
        }
    }

    public boolean isDeinterlaceMode() {
        return useDeinterlace;
    }

    public boolean isDeinterlaceEnabled() {
        return deinterlaceEnabled;
    }

    public void setDeinterlaceEnabled(boolean enabled) {
        this.deinterlaceEnabled = enabled;
        if (deinterlaceRenderer != null) {
            deinterlaceRenderer.setDeinterlaceEnabled(enabled);
        }
    }

    public DeinterlaceRenderer getDeinterlaceRenderer() {
        return deinterlaceRenderer;
    }

    public void setLetterboxWhite(boolean white) {
        this.letterboxWhite = white;
        if (videoContainer != null) {
            videoContainer.setBackgroundColor(white ? Color.WHITE : Color.BLACK);
        }
        if (deinterlaceRenderer != null) {
            deinterlaceRenderer.setWhiteBackground(white);
        }
    }

    public boolean isLetterboxWhite() {
        return letterboxWhite;
    }

    private void updateTextureViewSize() {
        if (textureContainer == null || videoContainer == null) return;

        int containerWidth = videoContainer.getWidth();
        int containerHeight = videoContainer.getHeight();
        if (containerWidth <= 0 || containerHeight <= 0) return;

        float fitAspect = aspectRatio;
        if (useDeinterlace && !openGLPassthrough) {
            if (is360Mode()) {
                fitAspect = 16.0f / 9.0f;
            } else {
                fitAspect = (float) getDeinterlaceOutputWidth() / getDeinterlaceOutputHeight();
            }
        }
        if (isQuarterTurn() && fitAspect > 0) {
            fitAspect = 1f / fitAspect;
        }

        if (oneToOneMode && !is360Mode()) {
            int visualW;
            int visualH;
            if (useDeinterlace && !openGLPassthrough) {
                visualW = getDeinterlaceOutputWidth();
                visualH = getDeinterlaceOutputHeight();
            } else if (currentResolution != null) {
                visualW = currentResolution.getWidth();
                visualH = currentResolution.getHeight();
            } else {
                return;
            }
            if (isQuarterTurn()) {
                int t = visualW;
                visualW = visualH;
                visualH = t;
            }
            applyTextureLayout(visualW, visualH);
        } else if (aspectLocked && fitAspect > 0) {
            float containerRatio = (float) containerWidth / containerHeight;
            int visualW, visualH;
            if (containerRatio > fitAspect) {
                visualH = containerHeight;
                visualW = (int) (containerHeight * fitAspect);
            } else {
                visualW = containerWidth;
                visualH = (int) (containerWidth / fitAspect);
            }
            applyTextureLayout(visualW, visualH);
        } else {
            applyTextureLayout(containerWidth, containerHeight);
        }

        updateDisplaySizeText();
    }

    private void applyTextureLayout(int visualW, int visualH) {
        FrameLayout.LayoutParams params;
        if (isQuarterTurn()) {
            params = new FrameLayout.LayoutParams(visualH, visualW);
        } else {
            params = new FrameLayout.LayoutParams(visualW, visualH);
        }
        params.gravity = Gravity.CENTER;
        textureContainer.setLayoutParams(params);
        textureContainer.setRotation(currentRotation);
    }

    public void hideLoading() {
        boolean wasLoading = loadingLayout != null && loadingLayout.getVisibility() == VISIBLE;
        removeCallbacks(loadingTick);
        if (wasLoading) {
            long ms = android.os.SystemClock.elapsedRealtime() - openStartElapsedMs;
            android.util.Log.d("CameraFrame", "Camera " + cameraId + " opened in " + ms + "ms");
            showOpenDuration(ms);
        }
        if (loadingLayout != null) {
            loadingLayout.setVisibility(View.GONE);
        }
    }

    public void showLoading() {
        if (loadingLayout != null) {
            loadingLayout.setVisibility(View.VISIBLE);
        }
        if (openDurationText != null) {
            openDurationText.setVisibility(View.GONE);
            openDurationText.removeCallbacks(hideOpenDuration);
        }
        startOpenTimer();
    }

    private void startOpenTimer() {
        openStartElapsedMs = SystemClock.elapsedRealtime();
        updateLoadingElapsed();
        removeCallbacks(loadingTick);
        post(loadingTick);
    }

    private void updateLoadingElapsed() {
        if (loadingText == null) return;
        loadingText.setText("正在打开摄像头...\n" + formatOpenDuration(
                SystemClock.elapsedRealtime() - openStartElapsedMs));
    }

    private void showOpenDuration(long ms) {
        if (openDurationText == null) return;
        openDurationText.setText("打开耗时 " + formatOpenDuration(ms));
        openDurationText.setVisibility(View.VISIBLE);
        openDurationText.removeCallbacks(hideOpenDuration);
        openDurationText.postDelayed(hideOpenDuration, 4000);
    }

    private final Runnable hideOpenDuration = () -> {
        if (openDurationText != null) {
            openDurationText.setVisibility(View.GONE);
        }
    };

    private static String formatOpenDuration(long ms) {
        if (ms < 1000) {
            return ms + "ms";
        }
        return String.format(java.util.Locale.US, "%.2fs", ms / 1000f);
    }

    private static class RotateIconView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();
        private final RectF arc = new RectF();

        RotateIconView(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(COLOR_TEXT_PRIMARY);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            canvas.save();
            canvas.rotate(180f, w / 2f, h / 2f);
            float stroke = Math.max(2.5f, Math.min(w, h) * 0.09f);
            paint.setStrokeWidth(stroke);
            float pad = stroke * 2.2f;
            arc.set(pad, pad, w - pad, h - pad);
            canvas.drawArc(arc, 40f, 250f, false, paint);

            float cx = arc.centerX();
            float cy = arc.centerY();
            float r = arc.width() / 2f;
            double end = Math.toRadians(40f + 250f);
            float ex = cx + r * (float) Math.cos(end);
            float ey = cy + r * (float) Math.sin(end);
            float ah = Math.min(w, h) * 0.22f;
            double ang = end + Math.PI / 2;
            arrow.reset();
            arrow.moveTo(ex, ey);
            arrow.lineTo(ex - ah * (float) Math.cos(ang - 0.7),
                    ey - ah * (float) Math.sin(ang - 0.7));
            arrow.moveTo(ex, ey);
            arrow.lineTo(ex - ah * (float) Math.cos(ang + 0.15),
                    ey - ah * (float) Math.sin(ang + 0.15));
            canvas.drawPath(arrow, paint);
            canvas.restore();
        }
    }

    private static class CornerResizeView extends View {
        private Paint paint;
        private Path path;

        public CornerResizeView(Context context) {
            super(context);
            paint = new Paint();
            paint.setColor(Color.parseColor("#80808080"));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(4.5f);
            paint.setAntiAlias(true);
            paint.setStrokeCap(Paint.Cap.ROUND);
            path = new Path();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            path.reset();
            int margin = 4;
            path.moveTo(getWidth() - margin, margin + 4);
            path.lineTo(getWidth() - margin, getHeight() - margin);
            path.lineTo(margin + 4, getHeight() - margin);
            canvas.drawPath(path, paint);

            path.reset();
            path.moveTo(getWidth() - margin - 6, margin + 10);
            path.lineTo(getWidth() - margin - 6, getHeight() - margin - 6);
            path.lineTo(margin + 10, getHeight() - margin - 6);
            canvas.drawPath(path, paint);
        }
    }

    private void setupTouchHandling() {
        resizeHandle.setOnTouchListener((v, event) -> {
            if (isFullscreen) return false;
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    isResizing = true;
                    resizeDownRawX = event.getRawX();
                    resizeDownRawY = event.getRawY();
                    resizeStartX = getX();
                    resizeStartY = getY();
                    resizeStartW = getWidth();
                    resizeStartH = getHeight();
                    bringToFront();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (isResizing) {
                        applyResizeFromHandle(event.getRawX(), event.getRawY());
                        if (oneToOneMode) {
                            oneToOneMode = false;
                            updateOneToOneButtonState();
                        }
                        post(() -> updateTextureViewSize());
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    isResizing = false;
                    notifyGeometryChanged();
                    return true;
            }
            return false;
        });
    }

    private boolean handleDrag(MotionEvent event, boolean toggleTitleOnTap) {
        if (isFullscreen) return false;
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                dX = getX() - event.getRawX();
                dY = getY() - event.getRawY();
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                isDragging = false;
                bringToFront();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dist = Math.abs(event.getRawX() - downRawX) + Math.abs(event.getRawY() - downRawY);
                if (!isDragging && dist > tapSlop) {
                    isDragging = true;
                }
                if (isDragging) {
                    float x = event.getRawX() + dX;
                    float y = event.getRawY() + dY;
                    float[] snapped = snapPosition(x, y, getWidth(), getHeight());
                    setX(snapped[0]);
                    setY(snapped[1]);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!isDragging && toggleTitleOnTap) {
                    toggleTitleBar();
                }
                if (isDragging) {
                    notifyGeometryChanged();
                }
                isDragging = false;
                return true;
        }
        return false;
    }

    private void toggleTitleBar() {
        titleBarVisible = !titleBarVisible;
        titleBar.setVisibility(titleBarVisible ? View.VISIBLE : View.GONE);
    }

    private float[] snapPosition(float x, float y, int w, int h) {
        ViewGroup parent = (ViewGroup) getParent();
        if (parent == null) return new float[]{x, y};

        java.util.ArrayList<Float> xs = new java.util.ArrayList<>();
        java.util.ArrayList<Float> ys = new java.util.ArrayList<>();
        xs.add(0f);
        xs.add((float) (parent.getWidth() - w));
        ys.add(0f);
        ys.add((float) (parent.getHeight() - h));

        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (!(child instanceof CameraFrame) || child == this) continue;
            CameraFrame other = (CameraFrame) child;
            if (other.getVisibility() != VISIBLE || other.isFullscreen()) continue;
            float ox = other.getX();
            float oy = other.getY();
            int ow = other.getWidth();
            int oh = other.getHeight();
            xs.add(ox);
            xs.add(ox + ow);
            xs.add(ox - w);
            xs.add(ox + ow - w);
            ys.add(oy);
            ys.add(oy + oh);
            ys.add(oy - h);
            ys.add(oy + oh - h);
        }

        return new float[]{snapValue(x, xs), snapValue(y, ys)};
    }

    private void applyResizeFromHandle(float rawX, float rawY) {
        float dx = rawX - resizeDownRawX;
        float dy = rawY - resizeDownRawY;
        float left = resizeStartX;
        float top = resizeStartY;
        float right = resizeStartX + resizeStartW;
        float bottom = resizeStartY + resizeStartH;
        int rot = ((int) currentRotation) % 360;
        if (rot < 0) rot += 360;

        boolean moveLeft = false;
        boolean moveTop = false;
        boolean moveRight = false;
        boolean moveBottom = false;
        switch (rot) {
            case 90:
                left = resizeStartX + dx;
                bottom = resizeStartY + resizeStartH + dy;
                moveLeft = true;
                moveBottom = true;
                break;
            case 180:
                right = resizeStartX + resizeStartW + dx;
                top = resizeStartY + dy;
                moveRight = true;
                moveTop = true;
                break;
            default:
                right = resizeStartX + resizeStartW + dx;
                bottom = resizeStartY + resizeStartH + dy;
                moveRight = true;
                moveBottom = true;
                break;
        }

        float[] snapped = snapResizeEdges(left, top, right, bottom,
                moveLeft, moveTop, moveRight, moveBottom);
        left = snapped[0];
        top = snapped[1];
        right = snapped[2];
        bottom = snapped[3];

        int newW = Math.max(MIN_SIZE, Math.round(right - left));
        int newH = Math.max(MIN_SIZE, Math.round(bottom - top));
        if (moveLeft) left = right - newW;
        if (moveTop) top = bottom - newH;

        android.view.ViewGroup.LayoutParams params = getLayoutParams();
        params.width = newW;
        params.height = newH;
        setLayoutParams(params);
        setX(left);
        setY(top);
    }

    private float[] snapResizeEdges(float left, float top, float right, float bottom,
                                    boolean moveLeft, boolean moveTop,
                                    boolean moveRight, boolean moveBottom) {
        ViewGroup parent = (ViewGroup) getParent();
        if (parent == null) return new float[]{left, top, right, bottom};

        java.util.ArrayList<Float> xs = new java.util.ArrayList<>();
        java.util.ArrayList<Float> ys = new java.util.ArrayList<>();
        xs.add(0f);
        xs.add((float) parent.getWidth());
        ys.add(0f);
        ys.add((float) parent.getHeight());

        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (!(child instanceof CameraFrame) || child == this) continue;
            CameraFrame other = (CameraFrame) child;
            if (other.getVisibility() != VISIBLE || other.isFullscreen()) continue;
            xs.add(other.getX());
            xs.add(other.getX() + other.getWidth());
            ys.add(other.getY());
            ys.add(other.getY() + other.getHeight());
        }

        if (moveLeft) left = snapValue(left, xs);
        if (moveRight) right = snapValue(right, xs);
        if (moveTop) top = snapValue(top, ys);
        if (moveBottom) bottom = snapValue(bottom, ys);
        return new float[]{left, top, right, bottom};
    }

    private float snapValue(float value, java.util.List<Float> targets) {
        float best = value;
        float bestDist = snapRange;
        for (float target : targets) {
            float dist = Math.abs(value - target);
            if (dist < bestDist) {
                bestDist = dist;
                best = target;
            }
        }
        return best;
    }

    private void showResolutionDialog() {
        if (resolutionChangeListener == null || cameraId == null) return;

        Size[] sizes = resolutionChangeListener.getAvailableResolutions(cameraId);
        if (sizes == null || sizes.length == 0) return;

        String[] options = new String[sizes.length];
        for (int i = 0; i < sizes.length; i++) {
            Size s = sizes[i];
            String mark = "";
            if (currentResolution != null &&
                    s.getWidth() == currentResolution.getWidth() &&
                    s.getHeight() == currentResolution.getHeight()) {
                mark = " ✓";
            }
            float mp = (s.getWidth() * s.getHeight()) / 1000000f;
            options[i] = s.getWidth() + " × " + s.getHeight() +
                    String.format(" (%.1fMP)", mp) + mark;
        }

        new android.app.AlertDialog.Builder(getContext())
                .setTitle("Camera " + cameraId + " 采集分辨率")
                .setAdapter(largeChoiceAdapter(options), (dialog, which) -> {
                    Size selected = sizes[which];
                    currentResolution = selected;
                    if (!useDeinterlace || openGLPassthrough) {
                        aspectRatio = (float) selected.getWidth() / selected.getHeight();
                    }
                    resolutionChangeListener.onResolutionChange(cameraId, selected);
                    updateResolutionText();
                    if (oneToOneMode) {
                        post(() -> applyOneToOneSize());
                    } else {
                        post(() -> updateTextureViewSize());
                    }
                })
                .show();
    }

    private void showFpsDialog() {
        if (cameraId == null) return;

        String[] fpsOptions = {"10 fps (省带宽)", "15 fps", "20 fps", "25 fps", "30 fps (流畅)"};
        int[] fpsValues = {10, 15, 20, 25, 30};

        int currentIndex = 4;
        for (int i = 0; i < fpsValues.length; i++) {
            if (fpsValues[i] == currentFps) {
                currentIndex = i;
                break;
            }
        }

        new android.app.AlertDialog.Builder(getContext())
                .setTitle("帧率设置 - Cam " + cameraId)
                .setSingleChoiceItems(largeChoiceAdapter(fpsOptions), currentIndex, (dialog, which) -> {
                    int selectedFps = fpsValues[which];
                    currentFps = selectedFps;
                    fpsText.setText("[" + selectedFps + "fps]");
                    if (fpsChangeListener != null) {
                        fpsChangeListener.onFpsChange(cameraId, selectedFps);
                    }
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private ArrayAdapter<String> largeChoiceAdapter(String[] options) {
        return new ArrayAdapter<>(getContext(),
                R.layout.dialog_choice_item, android.R.id.text1, options);
    }

    private void updateResolutionText() {
        if (useDeinterlace && !is360Mode() && !openGLPassthrough && deinterlacePresetActive) {
            String captureStr = currentResolution != null ?
                    currentResolution.getWidth() + "×" + currentResolution.getHeight() : "--×--";
            String outputStr = getDeinterlaceOutputWidth() + "×" + getDeinterlaceOutputHeight();
            resolutionText.setText("[" + captureStr + "→" + outputStr + "]");
        } else if (currentResolution != null) {
            resolutionText.setText("[" + currentResolution.getWidth() + "×" + currentResolution.getHeight() + "]");
        } else {
            resolutionText.setText("[--×--]");
        }
    }

    private void updateDisplaySizeText() {
        if (textureContainer != null) {
            int w = textureContainer.getWidth();
            int h = textureContainer.getHeight();
            if (w > 0 && h > 0) {
                if (isQuarterTurn()) {
                    int t = w;
                    w = h;
                    h = t;
                }
                displaySizeText.setText("[显示: " + w + "×" + h + "]");
                return;
            }
        }
        displaySizeText.setText("[显示: --×--]");
    }

    public android.view.TextureView getTextureView() {
        return textureView;
    }

    public GLSurfaceView getGLSurfaceView() {
        return glSurfaceView;
    }

    public void setCameraId(String id) {
        this.cameraId = id;
        titleText.setText("Cam " + id);
        updateResolutionText();
        updateDisplaySizeText();
    }

    public String getCameraId() {
        return cameraId;
    }

    public void setThemeColor(int color) {
        this.themeColor = color;
        titleText.setTextColor(color);
    }

    public void setCurrentResolution(Size resolution) {
        this.currentResolution = resolution;
        if (defaultCaptureResolution == null && resolution != null) {
            defaultCaptureResolution = resolution;
        }
        if (resolution != null && (!useDeinterlace || openGLPassthrough)) {
            aspectRatio = (float) resolution.getWidth() / resolution.getHeight();
        }
        updateResolutionText();
        post(() -> updateTextureViewSize());
    }

    public Size getCurrentResolution() {
        return currentResolution;
    }

    public Size getDefaultCaptureResolution() {
        return defaultCaptureResolution;
    }

    public void setDeinterlacePresetActive(boolean active) {
        this.deinterlacePresetActive = active;
        updateResolutionText();
    }

    public boolean isDeinterlacePresetActive() {
        return deinterlacePresetActive;
    }

    public void setNtscMode(boolean ntsc) {
        this.isNtscMode = ntsc;
        if (useDeinterlace && !openGLPassthrough && !is360Mode()) {
            aspectRatio = (float) getDeinterlaceOutputWidth() / getDeinterlaceOutputHeight();
        }
        updateResolutionText();
    }

    public void setOnResolutionChangeListener(OnResolutionChangeListener listener) {
        this.resolutionChangeListener = listener;
    }

    public void setOnFpsChangeListener(OnFpsChangeListener listener) {
        this.fpsChangeListener = listener;
    }

    public void setCurrentFps(int fps) {
        this.currentFps = fps;
        fpsText.setText("[" + fps + "fps]");
    }

    public int getCurrentFps() {
        return currentFps;
    }

    public float getCurrentRotation() {
        return currentRotation;
    }

    public void setCurrentRotation(float rotation) {
        this.currentRotation = rotation;
        applyContentRotation();
    }

    public boolean isAspectLocked() {
        return aspectLocked;
    }

    public void setAspectLocked(boolean locked) {
        this.aspectLocked = locked;
        lockText.setText(locked ? "等比" : "自由");
        lockText.setTextColor(locked ? COLOR_TEXT_PRIMARY : COLOR_TEXT_SECONDARY);
        updateTextureViewSize();
    }

    public boolean isOneToOneMode() {
        return oneToOneMode;
    }

    public void setOneToOneMode(boolean enabled) {
        this.oneToOneMode = enabled;
        updateOneToOneButtonState();
        if (enabled) {
            aspectLocked = false;
            lockText.setText("自由");
            lockText.setTextColor(COLOR_TEXT_SECONDARY);
            applyOneToOneSize();
        }
        updateTextureViewSize();
    }

    public boolean isNtsc() {
        return isNtscMode;
    }

    public void onPause() {
        if (glSurfaceView != null) {
            glSurfaceView.onPause();
        }
    }

    public void onResume() {
        if (glSurfaceView != null) {
            glSurfaceView.onResume();
        }
    }

    /**
     * Stop drawing immediately, release SurfaceTexture on the GL thread,
     * and pause EGL without blocking the UI thread.
     */
    private void teardownGlSurface() {
        GLSurfaceView glView = glSurfaceView;
        DeinterlaceRenderer renderer = deinterlaceRenderer;
        glSurfaceView = null;
        deinterlaceRenderer = null;
        if (renderer != null) {
            renderer.markReleased();
            if (glView != null) {
                try {
                    glView.queueEvent(renderer::release);
                } catch (Exception e) {
                    android.util.Log.w("CameraFrame", "queueEvent release failed", e);
                    renderer.release();
                }
            } else {
                renderer.release();
            }
        }
        if (glView != null) {
            glView.post(() -> {
                try {
                    glView.onPause();
                } catch (Exception e) {
                    android.util.Log.e("CameraFrame", "GLSurfaceView.onPause failed", e);
                }
            });
        }
    }

    public void release() {
        removeCallbacks(loadingTick);
        if (openDurationText != null) {
            openDurationText.removeCallbacks(hideOpenDuration);
        }
        teardownGlSurface();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        post(() -> {
            layoutTitleBar();
            updateTextureViewSize();
        });
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (changed) {
            post(() -> {
                layoutTitleBar();
                updateTextureViewSize();
            });
        }
    }
}