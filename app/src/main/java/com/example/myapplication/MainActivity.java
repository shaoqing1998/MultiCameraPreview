package com.example.myapplication;

import android.Manifest;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.hardware.camera2.CameraCharacteristics;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity
        implements CameraFrame.OnResolutionChangeListener, CameraFrame.OnFpsChangeListener,
        CameraFrame.OnFullscreenChangeListener {

    private static final int REQUEST_CAMERA_PERMISSION = 100;
    private static final String TAG = "MainActivity";
    private static final String PREFS_NAME = "camera_settings";
    private static final String PREF_USE_OPENGL = "use_opengl_default";
    private static final String PREF_SESSION = "session_state";
    private static final String PREF_PANEL_PINNED = "panel_pinned";

    private FrameLayout container;
    private LinearLayout infoContent;
    private LinearLayout sidePanel;
    private TextView settingsBtn;
    private TextView closePanelBtn;
    private FrameLayout pinPanelBtn;
    private PinIconView pinIcon;
    private View panelScrim;
    private boolean panelPinned = false;
    private CameraHelper cameraHelper;
    private Map<String, CameraFrame> cameraFrames = new HashMap<>();
    private Map<String, Boolean> cameraEnabled = new HashMap<>();
    private Map<String, CameraState> savedStates = new HashMap<>();
    private String[] cameraIds;
    private boolean isPanelVisible = false;
    private boolean isTunePanelVisible = false;
    private int screenWidth, screenHeight;

    private static final int MARGIN = 10;
    private static final int ANIM_DURATION = 250;

    private static final int COLOR_BG = Color.parseColor("#1A1A1A");
    private static final int COLOR_PANEL_BG = Color.parseColor("#252525");
    private static final int COLOR_CARD_BG = Color.parseColor("#333333");
    private static final int COLOR_TEXT_PRIMARY = Color.parseColor("#D4D4D4");
    private static final int COLOR_TEXT_SECONDARY = Color.parseColor("#8A8A8A");
    private static final int COLOR_ACCENT = Color.parseColor("#7DA8C4");
    private static final int COLOR_360 = Color.parseColor("#7DB87D");
    private static final int COLOR_HDMI = Color.parseColor("#D4A574");
    private static final int COLOR_CVBS = Color.parseColor("#C48DAB");
    private static final int COLOR_SELECTED = Color.parseColor("#FFFFFF");
    private static final int COLOR_RESET = Color.parseColor("#C47D7D");

    private DeinterlaceRenderer currentTuneRenderer;
    private String currentTuneCameraId;
    private int currentAlgorithm = DeinterlaceRenderer.ALGO_WEAVE;
    private boolean currentDeinterlaceEnabled = false;
    private int currentPreset = -1;
    private String fullscreenCameraId = null;
    private CameraState fullscreenSavedState = null;
    private int savedSystemUiVisibility;
    private boolean useOpenGLByDefault = true;
    private List<String> stackedCameraIds = new ArrayList<>();

    private int[] themeColors = {
            Color.parseColor("#7DA8C4"),
            Color.parseColor("#7DB87D"),
            Color.parseColor("#D4A574"),
            Color.parseColor("#C48DAB"),
            Color.parseColor("#A68DC4"),
            Color.parseColor("#74B8B8")
    };

    private static class CameraState {
        float x, y;
        int width, height;
        Size resolution;
        float rotation;
        int fps;
        boolean deinterlaceEnabled;
        boolean letterboxWhite;
        boolean aspectLocked = true;
        boolean oneToOneMode;
        boolean deinterlacePresetActive;
        boolean isNtsc = true;
        int algorithm = DeinterlaceRenderer.ALGO_WEAVE;
        boolean swapFields;
        float blendFactor = 0.5f;
        float motionThreshold = 0.08f;
        float hOffset;
        float hScale = 1f;
        float sourceHeight = 503f;
        float outputHeight = 480f;
        float oddStart;
        float oddLines = 240f;
        float evenStart = 263f;
        float evenLines = 240f;
        String icOverride;
        boolean deinterlaceParamsSaved;

        CameraState(float x, float y, int width, int height, Size resolution, float rotation, int fps) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.resolution = resolution;
            this.rotation = rotation;
            this.fps = fps;
            this.deinterlaceEnabled = false;
            this.letterboxWhite = false;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        container = findViewById(R.id.container);
        container.setBackgroundColor(COLOR_BG);

        infoContent = findViewById(R.id.info_content);
        sidePanel = findViewById(R.id.side_panel);
        sidePanel.setBackgroundColor(COLOR_PANEL_BG);
        sidePanel.setClickable(true);
        sidePanel.setFocusable(true);
        panelScrim = findViewById(R.id.panel_scrim);
        settingsBtn = findViewById(R.id.settings_btn);
        closePanelBtn = findViewById(R.id.close_panel);
        pinPanelBtn = findViewById(R.id.pin_panel);
        pinIcon = new PinIconView(this);
        pinPanelBtn.addView(pinIcon, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        GradientDrawable settingsBg = new GradientDrawable();
        settingsBg.setColor(Color.parseColor("#404040"));
        settingsBg.setCornerRadius(8);
        settingsBtn.setBackground(settingsBg);

        GradientDrawable closeBg = new GradientDrawable();
        closeBg.setColor(Color.parseColor("#404040"));
        closeBg.setCornerRadius(8);
        closePanelBtn.setBackground(closeBg);

        GradientDrawable pinBg = new GradientDrawable();
        pinBg.setColor(Color.parseColor("#404040"));
        pinBg.setCornerRadius(8);
        pinPanelBtn.setBackground(pinBg);
        updatePinButton();

        cameraHelper = new CameraHelper(this);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        useOpenGLByDefault = prefs.getBoolean(PREF_USE_OPENGL, true);
        panelPinned = prefs.getBoolean(PREF_PANEL_PINNED, false);
        updatePinButton();

        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;

        pinPanelBtn.setOnClickListener(v -> {
            panelPinned = !panelPinned;
            updatePinButton();
            updatePanelScrim();
            persistSettings();
        });
        settingsBtn.setOnClickListener(v -> setPanelVisible(true));
        closePanelBtn.setOnClickListener(v -> setPanelVisible(false));
        panelScrim.setOnClickListener(v -> {
            if (!panelPinned) {
                setPanelVisible(false);
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (handleAppBack()) return;
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
            }
        });

        CameraIcReader.setHdmiAudio(true);

        if (checkCameraPermission()) {
            initCameraList();
        } else {
            requestCameraPermission();
        }

        sidePanel.post(() -> {
            if (panelPinned) {
                isPanelVisible = false;
                setPanelVisible(true);
            } else {
                sidePanel.setTranslationX(sidePanel.getWidth());
                isPanelVisible = false;
            }
        });

        Log.d(TAG, CameraIcReader.getDebugInfo());
    }

    private boolean handleAppBack() {
        if (fullscreenCameraId != null) {
            CameraFrame frame = cameraFrames.get(fullscreenCameraId);
            if (frame != null) {
                frame.setFullscreen(false);
            }
            return true;
        }
        if (isTunePanelVisible) {
            hideTunePanel();
            return true;
        }
        if (isPanelVisible) {
            setPanelVisible(false);
            return true;
        }
        return false;
    }

    private void setPanelVisible(boolean visible) {
        if (isPanelVisible == visible) return;
        isPanelVisible = visible;
        int panelWidth = sidePanel.getWidth();
        if (panelWidth <= 0) panelWidth = dp(340);
        if (visible) {
            updatePanelScrim();
            settingsBtn.setVisibility(View.GONE);
            sidePanel.animate()
                    .translationX(0)
                    .setDuration(ANIM_DURATION)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        } else {
            panelScrim.setVisibility(View.GONE);
            if (fullscreenCameraId == null) {
                settingsBtn.setVisibility(View.VISIBLE);
            }
            sidePanel.animate()
                    .translationX(panelWidth)
                    .setDuration(ANIM_DURATION)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    private void updatePanelScrim() {
        panelScrim.setVisibility(isPanelVisible && !panelPinned ? View.VISIBLE : View.GONE);
    }

    private void updatePinButton() {
        if (pinIcon != null) {
            pinIcon.setPinned(panelPinned);
        }
    }

    private void setImmersiveFullscreen(boolean immersive) {
        View decor = getWindow().getDecorView();
        if (immersive) {
            savedSystemUiVisibility = decor.getSystemUiVisibility();
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        } else {
            decor.setSystemUiVisibility(savedSystemUiVisibility);
        }
    }

    private boolean checkCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initCameraList();
            } else {
                Toast.makeText(this, "需要摄像头权限", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private void initCameraList() {
        cameraIds = cameraHelper.getCameraIdList();
        if (cameraIds == null || cameraIds.length == 0) {
            Toast.makeText(this, "未检测到摄像头", Toast.LENGTH_SHORT).show();
            return;
        }

        calculateInitialLayouts();

        for (String id : cameraIds) {
            cameraEnabled.put(id, true);
        }
        loadPersistedSettings();

        updateInfoPanel();
        createCameraViews();
        cameraHelper.prefetchCameraInfo(cameraIds, () -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            updateInfoPanel();
        }));
    }

    private void calculateInitialLayouts() {
        int count = cameraIds.length;
        for (int i = 0; i < count; i++) {
            String cameraId = cameraIds[i];
            float aspectRatio = 4f / 3f;
            Size[] sizes = cameraHelper.getCachedResolutions(cameraId);
            if (sizes != null && sizes.length > 0) {
                Size defaultSize = selectDefaultResolution(sizes);
                aspectRatio = (float) defaultSize.getWidth() / defaultSize.getHeight();
            }

            int cellW;
            int cellH;
            int col;
            int row;
            switch (count) {
                case 1:
                    cellW = screenWidth - MARGIN * 2;
                    cellH = screenHeight - MARGIN * 2;
                    col = 0;
                    row = 0;
                    break;
                case 2:
                    cellW = (screenWidth - MARGIN * 3) / 2;
                    cellH = screenHeight - MARGIN * 2;
                    col = i;
                    row = 0;
                    break;
                case 3:
                    cellW = (screenWidth - MARGIN * 3) / 2;
                    cellH = (screenHeight - MARGIN * 3) / 2;
                    col = i < 2 ? i : 0;
                    row = i < 2 ? 0 : 1;
                    break;
                default:
                    cellW = (screenWidth - MARGIN * 3) / 2;
                    cellH = (screenHeight - MARGIN * 3) / 2;
                    if (count > 4) {
                        cellW = 320;
                        cellH = 280;
                    }
                    col = i % 2;
                    row = i / 2;
                    break;
            }

            int w;
            int h;
            if ((float) cellW / cellH > aspectRatio) {
                h = cellH;
                w = (int) (h * aspectRatio);
            } else {
                w = cellW;
                h = (int) (w / aspectRatio);
            }

            float x = MARGIN + col * (cellW + MARGIN);
            float y = MARGIN + row * (cellH + MARGIN);

            CameraState st = savedStates.get(cameraId);
            if (st == null) {
                savedStates.put(cameraId, new CameraState(x, y, w, h, null, 0, 30));
            } else {
                st.x = x;
                st.y = y;
                st.width = w;
                st.height = h;
            }
        }
    }

    private Size selectDefaultResolution(Size[] sizes) {
        for (Size size : sizes) {
            if (size.getWidth() == 640 && size.getHeight() == 480) {
                return size;
            }
        }
        if (sizes.length > 2) {
            return sizes[sizes.length / 2];
        }
        return sizes[0];
    }

    private boolean isLegacy360Resolution(Size size) {
        return size != null && size.getHeight() >= 4000;
    }

    @Override
    public void onFullscreenChange(String cameraId, boolean isFullscreen) {
        CameraFrame frame = cameraFrames.get(cameraId);
        if (frame == null) return;

        if (isFullscreen) {
            fullscreenSavedState = new CameraState(
                    frame.getX(), frame.getY(),
                    frame.getWidth(), frame.getHeight(),
                    null, 0, 0
            );
            fullscreenCameraId = cameraId;
            setPanelVisible(false);
            settingsBtn.setVisibility(View.GONE);
            setImmersiveFullscreen(true);

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT);
            frame.setLayoutParams(params);
            frame.setX(0);
            frame.setY(0);
            frame.bringToFront();
        } else {
            setImmersiveFullscreen(false);
            if (fullscreenSavedState != null) {
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        fullscreenSavedState.width,
                        fullscreenSavedState.height
                );
                frame.setLayoutParams(params);
                frame.setX(fullscreenSavedState.x);
                frame.setY(fullscreenSavedState.y);
            }
            fullscreenCameraId = null;
            fullscreenSavedState = null;
            settingsBtn.setVisibility(View.VISIBLE);
        }
    }

    private void showIcTypeSelectionDialog(String cameraId) {
        CameraIcReader.IcType[] types = CameraIcReader.getSelectableIcTypes();
        CameraIcReader.IcType currentType = CameraIcReader.getIcType(cameraId);

        String[] options = new String[types.length];
        int selectedIndex = 0;

        for (int i = 0; i < types.length; i++) {
            CameraIcReader.IcType type = types[i];
            options[i] = type == CameraIcReader.IcType.UNKNOWN ? "无" : type.name;
            if (type == currentType) {
                selectedIndex = i;
            }
        }

        ArrayAdapter<String> icAdapter = new ArrayAdapter<>(this,
                R.layout.dialog_choice_item, android.R.id.text1, options);

        new AlertDialog.Builder(this)
                .setTitle("Camera " + cameraId + " - 选择 IC 类型")
                .setSingleChoiceItems(icAdapter, selectedIndex, (dialog, which) -> {
                    CameraIcReader.IcType selectedType = types[which];
                    if (selectedType == CameraIcReader.IcType.UNKNOWN) {
                        CameraIcReader.clearManualIcType(cameraId);
                    } else {
                        CameraIcReader.setManualIcType(cameraId, selectedType);
                    }
                    Log.d(TAG, "IC changed: cam" + cameraId + " -> " + selectedType.name
                            + " is360=" + CameraIcReader.is360Camera(cameraId)
                            + " isHdmi=" + CameraIcReader.isHdmiCamera(cameraId));

                    if (cameraEnabled.getOrDefault(cameraId, false)) {
                        removeCameraView(cameraId);

                        CameraState ss = savedStates.get(cameraId);
                        if (ss != null) {
                            ss.resolution = null;
                        }
                        cameraHelper.clearResolution(cameraId);

                        addCameraView(cameraId);
                    }

                    // 5. 最后更新侧边栏（显示新的 IC 类型和按钮）
                    updateInfoPanel();
                    persistSettings();

                    Toast.makeText(this, "Camera " + cameraId + " IC 类型已设为: "
                                    + (selectedType == CameraIcReader.IcType.UNKNOWN ? "无" : selectedType.name),
                            Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updateInfoPanel() {
        infoContent.removeAllViews();

        LinearLayout glRow = new LinearLayout(this);
        glRow.setOrientation(LinearLayout.HORIZONTAL);
        glRow.setGravity(Gravity.CENTER_VERTICAL);
        glRow.setPadding(14, 12, 14, 12);
        glRow.setClipChildren(false);
        glRow.setClipToPadding(false);
        GradientDrawable glCardBg = new GradientDrawable();
        glCardBg.setColor(COLOR_CARD_BG);
        glCardBg.setCornerRadius(10);
        glRow.setBackground(glCardBg);
        LinearLayout.LayoutParams glRowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        glRowParams.setMargins(0, 0, 0, 12);
        glRow.setLayoutParams(glRowParams);

        TextView glLabel = new TextView(this);
        glLabel.setText("默认使用 OpenGL 打开");
        glLabel.setTextColor(COLOR_TEXT_PRIMARY);
        glLabel.setTextSize(15);
        glLabel.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Switch glSwitch = new Switch(this);
        LinearLayout.LayoutParams glSwitchParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        glSwitchParams.setMargins(8, 0, 0, 0);
        glSwitch.setLayoutParams(glSwitchParams);
        styleSwitch(glSwitch);
        glSwitch.setChecked(useOpenGLByDefault);
        glSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            useOpenGLByDefault = isChecked;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREF_USE_OPENGL, isChecked)
                    .apply();
            recreateOpenGLAffectedCameras();
            persistSettings();
            Toast.makeText(this, isChecked ? "已切换为 OpenGL 打开" : "已切换为 TextureView 打开",
                    Toast.LENGTH_SHORT).show();
        });

        glRow.addView(glLabel);
        glRow.addView(glSwitch);
        infoContent.addView(glRow);

        TextView countView = new TextView(this);
        countView.setText("共 " + cameraIds.length + " 个摄像头");
        countView.setTextColor(COLOR_TEXT_SECONDARY);
        countView.setTextSize(14);
        countView.setPadding(0, 0, 0, 16);
        infoContent.addView(countView);

        try {
            for (String id : cameraIds) {
                int color = themeColorFor(id);

                LinearLayout camContainer = new LinearLayout(this);
                camContainer.setOrientation(LinearLayout.VERTICAL);
                camContainer.setPadding(14, 12, 14, 12);
                GradientDrawable cardBg = new GradientDrawable();
                cardBg.setColor(COLOR_CARD_BG);
                cardBg.setCornerRadius(10);
                camContainer.setBackground(cardBg);
                LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                containerParams.setMargins(0, 0, 0, 10);
                camContainer.setLayoutParams(containerParams);

                LinearLayout headerRow = new LinearLayout(this);
                headerRow.setOrientation(LinearLayout.HORIZONTAL);
                headerRow.setGravity(Gravity.CENTER_VERTICAL);

                TextView camTitle = new TextView(this);
                camTitle.setText("Camera " + id);
                camTitle.setTextColor(color);
                camTitle.setTextSize(16);
                camTitle.setTypeface(null, Typeface.BOLD);
                camTitle.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

                Switch toggle = new Switch(this);
                LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                toggleParams.setMargins(8, 0, 0, 0);
                toggle.setLayoutParams(toggleParams);
                styleSwitch(toggle);
                toggle.setChecked(cameraEnabled.getOrDefault(id, true));
                final String camId = id;
                toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    cameraEnabled.put(camId, isChecked);
                    if (isChecked) {
                        addCameraView(camId);
                    } else {
                        removeCameraView(camId);
                    }
                    persistSettings();
                });

                headerRow.addView(camTitle);
                headerRow.addView(toggle);
                camContainer.addView(headerRow);

                LinearLayout bgRow = new LinearLayout(this);
                bgRow.setOrientation(LinearLayout.HORIZONTAL);
                bgRow.setGravity(Gravity.CENTER_VERTICAL);
                bgRow.setPadding(0, 8, 0, 4);

                TextView bgLabel = new TextView(this);
                bgLabel.setText("白底");
                bgLabel.setTextColor(COLOR_TEXT_PRIMARY);
                bgLabel.setTextSize(15);
                bgLabel.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

                Switch bgSwitch = new Switch(this);
                LinearLayout.LayoutParams bgSwitchParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                bgSwitchParams.setMargins(8, 0, 0, 0);
                bgSwitch.setLayoutParams(bgSwitchParams);
                styleSwitch(bgSwitch);
                CameraFrame existingFrame = cameraFrames.get(camId);
                CameraState savedBg = savedStates.get(camId);
                boolean whiteOn = existingFrame != null ? existingFrame.isLetterboxWhite()
                        : savedBg != null && savedBg.letterboxWhite;
                bgSwitch.setChecked(whiteOn);
                bgSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    CameraState st = savedStates.get(camId);
                    if (st != null) st.letterboxWhite = isChecked;
                    CameraFrame f = cameraFrames.get(camId);
                    if (f != null) f.setLetterboxWhite(isChecked);
                    persistSettings();
                });
                bgRow.addView(bgLabel);
                bgRow.addView(bgSwitch);
                camContainer.addView(bgRow);

                CameraIcReader.IcType icType = CameraIcReader.getIcType(id);

                int icColor;
                if (icType == CameraIcReader.IcType.LT6911C) {
                    icColor = COLOR_HDMI;
                } else if (CameraIcReader.isCvbsCamera(id)) {
                    icColor = COLOR_CVBS;
                } else if (icType == CameraIcReader.IcType.UNKNOWN) {
                    icColor = COLOR_TEXT_SECONDARY;
                } else {
                    icColor = COLOR_360;
                }

                TextView icBtn = new TextView(this);
                String icName = icType == CameraIcReader.IcType.UNKNOWN ? "无" : icType.name;
                icBtn.setText(icName);
                icBtn.setTextColor(Color.WHITE);
                icBtn.setTextSize(20);
                icBtn.setTypeface(null, Typeface.BOLD);
                icBtn.setGravity(Gravity.CENTER);
                icBtn.setPadding(16, 16, 16, 16);
                icBtn.setMinHeight(52);
                applyFilledButton(icBtn, Color.parseColor("#3A3A3A"), icColor);
                LinearLayout.LayoutParams icBtnParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                icBtnParams.setMargins(0, 10, 0, 0);
                icBtn.setLayoutParams(icBtnParams);

                final String cameraId = id;
                icBtn.setOnClickListener(v -> showIcTypeSelectionDialog(cameraId));
                camContainer.addView(icBtn);

                if (icType == CameraIcReader.IcType.UNKNOWN) {
                    TextView hintText = new TextView(this);
                    hintText.setText("未识别，点上方按钮手动选择");
                    hintText.setTextColor(COLOR_ACCENT);
                    hintText.setTextSize(13);
                    hintText.setPadding(0, 8, 0, 0);
                    camContainer.addView(hintText);
                }

                Integer facing = cameraHelper.getCachedLensFacing(id);
                String facingStr = facing == null ? "读取中..." :
                        facing == CameraCharacteristics.LENS_FACING_FRONT ? "前置" :
                        facing == CameraCharacteristics.LENS_FACING_BACK ? "后置" : "外置";
                addInfoRow(camContainer, "朝向: " + facingStr);

                Size[] sizes = cameraHelper.getCachedResolutions(id);
                addInfoRow(camContainer, "分辨率: " + (sizes != null ? sizes.length + " 种" : "读取中..."));

                if (CameraIcReader.needsDeinterlace(id)) {
                    TextView deinterlaceBtn = new TextView(this);
                    deinterlaceBtn.setText("参数");
                    deinterlaceBtn.setTextColor(Color.WHITE);
                    deinterlaceBtn.setTextSize(15);
                    deinterlaceBtn.setGravity(Gravity.CENTER);
                    deinterlaceBtn.setPadding(16, 14, 16, 14);
                    deinterlaceBtn.setMinHeight(48);
                    applyFilledButton(deinterlaceBtn, COLOR_CVBS);
                    LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    btnParams.setMargins(0, 10, 0, 0);
                    deinterlaceBtn.setLayoutParams(btnParams);
                    deinterlaceBtn.setOnClickListener(v -> {
                        CameraFrame frame = cameraFrames.get(cameraId);
                        if (frame != null && frame.isDeinterlaceMode()) {
                            showDeinterlaceTunePanel(cameraId);
                        } else {
                            Toast.makeText(this, "请先启用该摄像头", Toast.LENGTH_SHORT).show();
                        }
                    });
                    camContainer.addView(deinterlaceBtn);
                }

                if (icType == CameraIcReader.IcType.LT6911C) {
                    Size hdmiRes = CameraIcReader.getHdmiResolution();
                    TextView hdmiResView = new TextView(this);
                    hdmiResView.setText("HDMI: " + hdmiRes.getWidth() + "×" + hdmiRes.getHeight());
                    hdmiResView.setTextColor(CameraIcReader.isHdmiSignalValid() ? COLOR_360 : Color.parseColor("#C47D7D"));
                    hdmiResView.setTextSize(14);
                    hdmiResView.setPadding(0, 4, 0, 0);
                    camContainer.addView(hdmiResView);

                    TextView hdmiBtn = new TextView(this);
                    hdmiBtn.setText("HDMI 设置");
                    hdmiBtn.setTextColor(Color.WHITE);
                    hdmiBtn.setTextSize(15);
                    hdmiBtn.setGravity(Gravity.CENTER);
                    hdmiBtn.setPadding(16, 14, 16, 14);
                    hdmiBtn.setMinHeight(48);
                    applyFilledButton(hdmiBtn, COLOR_HDMI);
                    LinearLayout.LayoutParams hdmiBtnParams = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    hdmiBtnParams.setMargins(0, 10, 0, 0);
                    hdmiBtn.setLayoutParams(hdmiBtnParams);
                    hdmiBtn.setOnClickListener(v -> showHdmiSettingsPanel(cameraId));
                    camContainer.addView(hdmiBtn);
                }

                infoContent.addView(camContainer);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        TextView resetLayoutBtn = new TextView(this);
        resetLayoutBtn.setText("还原默认布局");
        resetLayoutBtn.setTextColor(Color.WHITE);
        resetLayoutBtn.setTextSize(16);
        resetLayoutBtn.setGravity(Gravity.CENTER);
        resetLayoutBtn.setPadding(16, 14, 16, 14);
        resetLayoutBtn.setMinHeight(52);
        applyFilledButton(resetLayoutBtn, Color.parseColor("#404040"));
        applyPressFeedback(resetLayoutBtn);
        LinearLayout.LayoutParams resetLayoutParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        resetLayoutParams.setMargins(0, 8, 0, 8);
        resetLayoutBtn.setLayoutParams(resetLayoutParams);
        resetLayoutBtn.setOnClickListener(v -> restoreDefaultLayouts());
        infoContent.addView(resetLayoutBtn);

        TextView hint = new TextView(this);
        hint.setText("\n操作提示:\n• 点击画面显示/隐藏标题栏\n• 拖动标题栏或画面移动窗口\n• 右下角缩放，靠近边缘或其他窗口会吸附\n• 旋转按钮旋转画面，标题栏贴外框边\n• [ ] 全屏，再点画面退出\n• 点击分辨率/帧率切换");
        hint.setTextColor(COLOR_TEXT_SECONDARY);
        hint.setTextSize(13);
        infoContent.addView(hint);
    }

    private void addInfoRow(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(COLOR_TEXT_SECONDARY);
        tv.setTextSize(14);
        tv.setPadding(0, 3, 0, 0);
        parent.addView(tv);
    }

    private void styleSwitch(Switch sw) {
        sw.setShowText(false);
        sw.setTextOn("");
        sw.setTextOff("");
        LinearLayout.LayoutParams params = sw.getLayoutParams() instanceof LinearLayout.LayoutParams
                ? (LinearLayout.LayoutParams) sw.getLayoutParams()
                : new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        params.width = LinearLayout.LayoutParams.WRAP_CONTENT;
        params.height = LinearLayout.LayoutParams.WRAP_CONTENT;
        params.gravity = Gravity.CENTER_VERTICAL;
        sw.setLayoutParams(params);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void applyFilledButton(View view, int fillColor) {
        applyFilledButton(view, fillColor, 0);
    }

    private void applyFilledButton(View view, int fillColor, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fillColor);
        d.setCornerRadius(8);
        if (strokeColor != 0) {
            d.setStroke(2, strokeColor);
        }
        view.setBackground(d);
    }

    private void applyCompactChip(TextView view) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor("#2E2E2E"));
        d.setCornerRadius(dp(8));
        int h = dp(4);
        int v = dp(6);
        view.setBackground(new InsetDrawable(d, h, v, h, v));
        view.setPadding(0, 0, 0, 0);
    }

    private void applyPressFeedback(View view) {
        view.setClickable(true);
        view.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().cancel();
                    v.animate().scaleX(0.94f).scaleY(0.94f).alpha(0.78f).setDuration(70).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().cancel();
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(90).start();
                    break;
            }
            return false;
        });
    }

    private TextView createBackButton() {
        TextView backBtn = new TextView(this);
        backBtn.setText("← 返回上级菜单");
        backBtn.setTextColor(Color.WHITE);
        backBtn.setTextSize(16);
        backBtn.setGravity(Gravity.CENTER);
        backBtn.setPadding(16, 14, 16, 14);
        backBtn.setMinHeight(52);
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        backParams.setMargins(0, 0, 0, 16);
        backBtn.setLayoutParams(backParams);
        applyFilledButton(backBtn, Color.parseColor("#404040"));
        backBtn.setOnClickListener(v -> hideTunePanel());
        return backBtn;
    }

    private void addSubPanelHeader(String title) {
        infoContent.addView(createBackButton());

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(18);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setPadding(0, 0, 0, 16);
        infoContent.addView(titleView);
    }

    private void hideTunePanel() {
        isTunePanelVisible = false;
        currentTuneRenderer = null;
        currentTuneCameraId = null;
        persistSettings();
        updateInfoPanel();
    }

    private LinearLayout createSeekBarWithButtons(String tag, int max, int progress,
                                                  TextView label, SeekBar.OnSeekBarChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView minusBtn = new TextView(this);
        minusBtn.setText("－");
        minusBtn.setTextColor(COLOR_ACCENT);
        minusBtn.setTextSize(22);
        minusBtn.setGravity(Gravity.CENTER);
        minusBtn.setMinWidth(56);
        minusBtn.setMinHeight(56);
        applyCompactChip(minusBtn);
        applyPressFeedback(minusBtn);

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(max);
        seekBar.setProgress(progress);
        seekBar.setTag(tag);
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        seekBar.setLayoutParams(seekParams);
        seekBar.setOnSeekBarChangeListener(listener);

        TextView plusBtn = new TextView(this);
        plusBtn.setText("＋");
        plusBtn.setTextColor(COLOR_ACCENT);
        plusBtn.setTextSize(22);
        plusBtn.setGravity(Gravity.CENTER);
        plusBtn.setMinWidth(56);
        plusBtn.setMinHeight(56);
        applyCompactChip(plusBtn);
        applyPressFeedback(plusBtn);

        minusBtn.setOnClickListener(v -> {
            int p = seekBar.getProgress();
            if (p > 0) {
                seekBar.setProgress(p - 1);
                listener.onProgressChanged(seekBar, p - 1, true);
            }
        });
        plusBtn.setOnClickListener(v -> {
            int p = seekBar.getProgress();
            if (p < seekBar.getMax()) {
                seekBar.setProgress(p + 1);
                listener.onProgressChanged(seekBar, p + 1, true);
            }
        });

        row.addView(minusBtn);
        row.addView(seekBar);
        row.addView(plusBtn);
        return row;
    }

    private void showDeinterlaceTunePanel(String cameraId) {
        CameraFrame frame = cameraFrames.get(cameraId);
        if (frame == null || !frame.isDeinterlaceMode()) return;

        DeinterlaceRenderer renderer = frame.getDeinterlaceRenderer();
        if (renderer == null) return;

        currentTuneRenderer = renderer;
        currentTuneCameraId = cameraId;
        isTunePanelVisible = true;

        currentDeinterlaceEnabled = renderer.isDeinterlaceEnabled();
        currentAlgorithm = renderer.getAlgorithm();
        currentPreset = frame.isDeinterlacePresetActive() ? (frame.isNtsc() ? 0 : 1) : -1;

        infoContent.removeAllViews();
        addSubPanelHeader("Cam " + cameraId + " 参数");

        buildDeinterlaceTunePanelContent();
        updateTunePanelValues();
        updateAlgoButtonStates();
        updatePresetButtonStates();
    }

    private void resetDeinterlaceParams() {
        if (currentTuneRenderer == null || currentTuneCameraId == null) return;

        CameraFrame frame = cameraFrames.get(currentTuneCameraId);
        Size original = frame != null ? frame.getDefaultCaptureResolution() : null;
        if (original == null && frame != null) {
            original = frame.getCurrentResolution();
        }
        boolean isNtsc = (original == null || original.getHeight() <= 503);

        if (original != null) {
            Size current = frame.getCurrentResolution();
            if (current == null
                    || current.getWidth() != original.getWidth()
                    || current.getHeight() != original.getHeight()) {
                switchPresetResolution(currentTuneCameraId, original);
            }
        }

        if (isNtsc) {
            currentTuneRenderer.setNtscMode();
        } else {
            currentTuneRenderer.setPalMode();
        }
        currentTuneRenderer.setDeinterlaceEnabled(false);
        if (frame != null) {
            frame.setDeinterlaceEnabled(false);
            frame.setNtscMode(isNtsc);
            frame.setDeinterlacePresetActive(false);
        }

        currentDeinterlaceEnabled = false;
        currentAlgorithm = currentTuneRenderer.getAlgorithm();
        currentPreset = -1;

        updateTunePanelValues();
        updateAlgoButtonStates();
        updatePresetButtonStates();

        Toast.makeText(this, "参数已重置", Toast.LENGTH_SHORT).show();
        persistSettings();
    }

    private void buildDeinterlaceTunePanelContent() {
        int textColor = COLOR_TEXT_PRIMARY;
        int labelColor = COLOR_TEXT_SECONDARY;

        // === 重置按钮 ===
        TextView resetBtn = new TextView(this);
        resetBtn.setText("重置参数");
        resetBtn.setTextColor(Color.WHITE);
        resetBtn.setTextSize(16);
        resetBtn.setGravity(Gravity.CENTER);
        resetBtn.setPadding(16, 14, 16, 14);
        resetBtn.setMinHeight(52);
        applyFilledButton(resetBtn, COLOR_RESET);
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        resetParams.setMargins(0, 0, 0, 16);
        resetBtn.setLayoutParams(resetParams);
        resetBtn.setOnClickListener(v -> resetDeinterlaceParams());
        infoContent.addView(resetBtn);

        // === 交换场序 ===
        LinearLayout swapRow = new LinearLayout(this);
        swapRow.setOrientation(LinearLayout.HORIZONTAL);
        swapRow.setGravity(Gravity.CENTER_VERTICAL);
        swapRow.setPadding(0, 8, 0, 8);

        TextView swapLabel = new TextView(this);
        swapLabel.setText("交换场序");
        swapLabel.setTextColor(textColor);
        swapLabel.setTextSize(16);
        swapLabel.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Switch swapSwitch = new Switch(this);
        swapSwitch.setTag("swapSwitch");
        LinearLayout.LayoutParams swapSwitchParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        swapSwitchParams.setMargins(8, 0, 0, 0);
        swapSwitch.setLayoutParams(swapSwitchParams);
        styleSwitch(swapSwitch);
        swapSwitch.setOnCheckedChangeListener((b, c) -> {
            if (currentTuneRenderer != null) {
                currentTuneRenderer.setSwapFields(c);
            }
        });

        swapRow.addView(swapLabel);
        swapRow.addView(swapSwitch);
        infoContent.addView(swapRow);

        // === 预设 ===
        TextView presetTitle = new TextView(this);
        presetTitle.setText("\n预设:");
        presetTitle.setTextColor(COLOR_ACCENT);
        presetTitle.setTextSize(16);
        presetTitle.setTypeface(null, Typeface.BOLD);
        infoContent.addView(presetTitle);

        LinearLayout presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        presetRow.setTag("presetRow");

        String[] presets = {"NTSC (720x503)", "PAL (720x601)"};
        for (int i = 0; i < presets.length; i++) {
            TextView btn = new TextView(this);
            btn.setText(presets[i]);
            btn.setTextColor(COLOR_ACCENT);
            btn.setTextSize(14);
            btn.setGravity(Gravity.CENTER);
            btn.setMinHeight(52);
            btn.setPadding(12, 12, 12, 12);
            btn.setTag("presetBtn_" + i);
            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            btnParams.setMargins(2, 2, 2, 2);
            btn.setLayoutParams(btnParams);

            final int presetIdx = i;
            btn.setOnClickListener(v -> {
                if (currentTuneRenderer == null || currentTuneCameraId == null) return;
                currentPreset = presetIdx;
                CameraFrame frame = cameraFrames.get(currentTuneCameraId);
                if (presetIdx == 0) {
                    currentTuneRenderer.setNtscMode();
                    switchPresetResolution(currentTuneCameraId, new Size(720, 503));
                    if (frame != null) frame.setNtscMode(true);
                } else {
                    currentTuneRenderer.setPalMode();
                    switchPresetResolution(currentTuneCameraId, new Size(720, 601));
                    if (frame != null) frame.setNtscMode(false);
                }
                if (frame != null) {
                    frame.setDeinterlaceEnabled(true);
                    frame.setDeinterlacePresetActive(true);
                }
                currentDeinterlaceEnabled = true;
                currentAlgorithm = currentTuneRenderer.getAlgorithm();
                updatePresetButtonStates();
                updateTunePanelValues();
                updateAlgoButtonStates();
            });
            presetRow.addView(btn);
        }
        infoContent.addView(presetRow);

        // === 算法 ===
        TextView algoTitle = new TextView(this);
        algoTitle.setText("\n算法:");
        algoTitle.setTextColor(textColor);
        algoTitle.setTextSize(16);
        infoContent.addView(algoTitle);

        LinearLayout algoContainer = new LinearLayout(this);
        algoContainer.setOrientation(LinearLayout.VERTICAL);
        algoContainer.setTag("algoRow");

        String[] algos = {"Weave", "Blend", "Adaptive", "ELA", "VertFilter", "SmoothBld", "AdaptSmth", "关闭"};
        int[] algoVals = {
                DeinterlaceRenderer.ALGO_WEAVE,
                DeinterlaceRenderer.ALGO_BLEND,
                DeinterlaceRenderer.ALGO_ADAPTIVE,
                DeinterlaceRenderer.ALGO_ELA,
                DeinterlaceRenderer.ALGO_VERT_FILTER,
                DeinterlaceRenderer.ALGO_SMOOTH_BLEND,
                DeinterlaceRenderer.ALGO_ADAPTIVE_SMOOTH,
                -1
        };

        LinearLayout currentRow = null;
        for (int i = 0; i < algos.length; i++) {
            if (i % 2 == 0) {
                currentRow = new LinearLayout(this);
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                algoContainer.addView(currentRow);
            }

            TextView btn = new TextView(this);
            btn.setText(algos[i]);
            btn.setTextColor(COLOR_ACCENT);
            btn.setTextSize(15);
            btn.setGravity(Gravity.CENTER);
            btn.setMinHeight(52);
            btn.setPadding(10, 12, 10, 12);
            btn.setTag("algoBtn_" + i);
            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            btnParams.setMargins(1, 2, 1, 2);
            btn.setLayoutParams(btnParams);

            final int algoIdx = i;
            btn.setOnClickListener(v -> {
                if (currentTuneRenderer == null) return;
                if (algoVals[algoIdx] == -1) {
                    currentTuneRenderer.setDeinterlaceEnabled(false);
                    currentDeinterlaceEnabled = false;
                } else {
                    currentTuneRenderer.setDeinterlaceEnabled(true);
                    currentTuneRenderer.setAlgorithm(algoVals[algoIdx]);
                    currentDeinterlaceEnabled = true;
                    currentAlgorithm = algoVals[algoIdx];
                }
                updateAlgoButtonStates();
            });
            currentRow.addView(btn);
        }
        infoContent.addView(algoContainer);

        // === 运动阈值 ===
        TextView threshLabel = new TextView(this);
        threshLabel.setText("\n运动阈值: 8");
        threshLabel.setTextColor(textColor);
        threshLabel.setTag("threshLabel");
        infoContent.addView(threshLabel);

        SeekBar.OnSeekBarChangeListener threshListener = new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                threshLabel.setText("运动阈值: " + p);
                if (u && currentTuneRenderer != null) currentTuneRenderer.setMotionThreshold(p / 100f);
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        };
        infoContent.addView(createSeekBarWithButtons("threshSeek", 50, 8, threshLabel, threshListener));

        // === 帧结构参数 ===
        TextView frameTitle = new TextView(this);
        frameTitle.setText("\n帧结构参数:");
        frameTitle.setTextColor(COLOR_ACCENT);
        frameTitle.setTextSize(16);
        frameTitle.setTypeface(null, Typeface.BOLD);
        infoContent.addView(frameTitle);

        TextView paramsInfo = new TextView(this);
        paramsInfo.setTextColor(labelColor);
        paramsInfo.setTextSize(14);
        paramsInfo.setTag("paramsInfo");
        infoContent.addView(paramsInfo);

        // 奇场起始
        TextView oddStartLabel = new TextView(this);
        oddStartLabel.setText("奇场起始: 0");
        oddStartLabel.setTextColor(textColor);
        oddStartLabel.setTag("oddStartLabel");
        infoContent.addView(oddStartLabel);

        SeekBar.OnSeekBarChangeListener oddStartListener = new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                oddStartLabel.setText("奇场起始: " + p);
                if (u && currentTuneRenderer != null) {
                    currentTuneRenderer.setFrameParams(
                            currentTuneRenderer.getSourceHeight(),
                            currentTuneRenderer.getOutputHeight(),
                            p,
                            currentTuneRenderer.getOddLines(),
                            currentTuneRenderer.getEvenStart(),
                            currentTuneRenderer.getEvenLines()
                    );
                    updateParamsInfo();
                }
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        };
        infoContent.addView(createSeekBarWithButtons("oddStartSeek", 50, 0, oddStartLabel, oddStartListener));

        // 奇场行数
        TextView oddLinesLabel = new TextView(this);
        oddLinesLabel.setText("奇场行数: 240");
        oddLinesLabel.setTextColor(textColor);
        oddLinesLabel.setTag("oddLinesLabel");
        infoContent.addView(oddLinesLabel);

        SeekBar.OnSeekBarChangeListener oddLinesListener = new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                int lines = p + 200;
                oddLinesLabel.setText("奇场行数: " + lines);
                if (u && currentTuneRenderer != null) {
                    currentTuneRenderer.setFrameParams(
                            currentTuneRenderer.getSourceHeight(),
                            currentTuneRenderer.getOutputHeight(),
                            currentTuneRenderer.getOddStart(),
                            lines,
                            currentTuneRenderer.getEvenStart(),
                            currentTuneRenderer.getEvenLines()
                    );
                    updateParamsInfo();
                }
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        };
        infoContent.addView(createSeekBarWithButtons("oddLinesSeek", 150, 40, oddLinesLabel, oddLinesListener));

        // 偶场起始
        TextView evenStartLabel = new TextView(this);
        evenStartLabel.setText("偶场起始: 263");
        evenStartLabel.setTextColor(textColor);
        evenStartLabel.setTag("evenStartLabel");
        infoContent.addView(evenStartLabel);

        SeekBar.OnSeekBarChangeListener evenStartListener = new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                int start = p + 200;
                evenStartLabel.setText("偶场起始: " + start);
                if (u && currentTuneRenderer != null) {
                    currentTuneRenderer.setFrameParams(
                            currentTuneRenderer.getSourceHeight(),
                            currentTuneRenderer.getOutputHeight(),
                            currentTuneRenderer.getOddStart(),
                            currentTuneRenderer.getOddLines(),
                            start,
                            currentTuneRenderer.getEvenLines()
                    );
                    updateParamsInfo();
                }
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        };
        infoContent.addView(createSeekBarWithButtons("evenStartSeek", 250, 63, evenStartLabel, evenStartListener));

        // 偶场行数
        TextView evenLinesLabel = new TextView(this);
        evenLinesLabel.setText("偶场行数: 240");
        evenLinesLabel.setTextColor(textColor);
        evenLinesLabel.setTag("evenLinesLabel");
        infoContent.addView(evenLinesLabel);

        SeekBar.OnSeekBarChangeListener evenLinesListener = new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                int lines = p + 200;
                evenLinesLabel.setText("偶场行数: " + lines);
                if (u && currentTuneRenderer != null) {
                    currentTuneRenderer.setFrameParams(
                            currentTuneRenderer.getSourceHeight(),
                            currentTuneRenderer.getOutputHeight(),
                            currentTuneRenderer.getOddStart(),
                            currentTuneRenderer.getOddLines(),
                            currentTuneRenderer.getEvenStart(),
                            lines
                    );
                    updateParamsInfo();
                }
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        };
        infoContent.addView(createSeekBarWithButtons("evenLinesSeek", 150, 40, evenLinesLabel, evenLinesListener));

        // === 水平裁剪 ===
        TextView hTitle = new TextView(this);
        hTitle.setText("\n水平裁剪:");
        hTitle.setTextColor(COLOR_HDMI);
        hTitle.setTypeface(null, Typeface.BOLD);
        infoContent.addView(hTitle);

        TextView hOffsetLabel = new TextView(this);
        hOffsetLabel.setText("左边裁剪: 0%");
        hOffsetLabel.setTextColor(textColor);
        hOffsetLabel.setTag("hOffsetLabel");
        infoContent.addView(hOffsetLabel);

        SeekBar.OnSeekBarChangeListener hOffsetListener = new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                hOffsetLabel.setText("左边裁剪: " + p + "%");
                if (u && currentTuneRenderer != null) {
                    currentTuneRenderer.setHOffset(p / 100f);
                }
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        };
        infoContent.addView(createSeekBarWithButtons("hOffsetSeek", 30, 0, hOffsetLabel, hOffsetListener));

        TextView hScaleLabel = new TextView(this);
        hScaleLabel.setText("水平缩放: 100%");
        hScaleLabel.setTextColor(textColor);
        hScaleLabel.setTag("hScaleLabel");
        infoContent.addView(hScaleLabel);

        SeekBar.OnSeekBarChangeListener hScaleListener = new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                float scale = 0.7f + p / 100f;
                hScaleLabel.setText("水平缩放: " + (int)(scale * 100) + "%");
                if (u && currentTuneRenderer != null) {
                    currentTuneRenderer.setHScale(scale);
                }
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        };
        infoContent.addView(createSeekBarWithButtons("hScaleSeek", 30, 30, hScaleLabel, hScaleListener));

        TextView bottomBack = createBackButton();
        LinearLayout.LayoutParams bottomParams = (LinearLayout.LayoutParams) bottomBack.getLayoutParams();
        bottomParams.setMargins(0, 24, 0, 16);
        infoContent.addView(bottomBack);
    }

    private void switchPresetResolution(String cameraId, Size newSize) {
        CameraFrame frame = cameraFrames.get(cameraId);
        if (frame == null || !frame.isDeinterlaceMode()) return;

        DeinterlaceRenderer renderer = frame.getDeinterlaceRenderer();
        GLSurfaceView glView = frame.getGLSurfaceView();
        if (renderer == null || glView == null) return;

        try {
            cameraHelper.closeCamera(cameraId);
        } catch (Exception e) {
            Log.e(TAG, "closeCamera deinterlace " + cameraId, e);
        }
        cameraHelper.setResolution(cameraId, newSize);

        SurfaceTexture surfaceTexture = renderer.getSurfaceTexture();
        if (surfaceTexture != null) {
            cameraHelper.openCamera(cameraId, surfaceTexture, glView.getWidth(), glView.getHeight());
        }

        frame.setCurrentResolution(newSize);
    }

    private void updatePresetButtonStates() {
        String[] presetNames = {"NTSC (720x503)", "PAL (720x601)"};
        for (int i = 0; i < 2; i++) {
            TextView btn = infoContent.findViewWithTag("presetBtn_" + i);
            if (btn == null) continue;
            boolean isSelected = (currentPreset == i);
            if (isSelected) {
                btn.setTextColor(COLOR_SELECTED);
                btn.setTypeface(null, Typeface.BOLD);
                btn.setText(presetNames[i] + " ●");
            } else {
                btn.setTextColor(COLOR_ACCENT);
                btn.setTypeface(null, Typeface.NORMAL);
                btn.setText(presetNames[i]);
            }
        }
    }

    private void updateAlgoButtonStates() {
        int[] algoVals = {
                DeinterlaceRenderer.ALGO_WEAVE,
                DeinterlaceRenderer.ALGO_BLEND,
                DeinterlaceRenderer.ALGO_ADAPTIVE,
                DeinterlaceRenderer.ALGO_ELA,
                DeinterlaceRenderer.ALGO_VERT_FILTER,
                DeinterlaceRenderer.ALGO_SMOOTH_BLEND,
                DeinterlaceRenderer.ALGO_ADAPTIVE_SMOOTH,
                -1
        };
        String[] algoNames = {"Weave", "Blend", "Adaptive", "ELA", "VertFilter", "SmoothBld", "AdaptSmth", "关闭"};

        for (int i = 0; i < algoVals.length; i++) {
            TextView btn = infoContent.findViewWithTag("algoBtn_" + i);
            if (btn == null) continue;
            boolean isSelected;
            if (algoVals[i] == -1) {
                isSelected = !currentDeinterlaceEnabled;
            } else {
                isSelected = currentDeinterlaceEnabled && currentAlgorithm == algoVals[i];
            }
            if (isSelected) {
                btn.setTextColor(COLOR_SELECTED);
                btn.setTypeface(null, Typeface.BOLD);
                btn.setText(algoNames[i] + " ●");
            } else {
                btn.setTextColor(COLOR_ACCENT);
                btn.setTypeface(null, Typeface.NORMAL);
                btn.setText(algoNames[i]);
            }
        }
    }

    private void updateTunePanelValues() {
        if (currentTuneRenderer == null) return;

        currentDeinterlaceEnabled = currentTuneRenderer.isDeinterlaceEnabled();
        currentAlgorithm = currentTuneRenderer.getAlgorithm();

        Switch swapSwitch = infoContent.findViewWithTag("swapSwitch");
        if (swapSwitch != null) swapSwitch.setChecked(currentTuneRenderer.isSwapFields());

        SeekBar hOffsetSeek = infoContent.findViewWithTag("hOffsetSeek");
        if (hOffsetSeek != null) hOffsetSeek.setProgress((int)(currentTuneRenderer.getHOffset() * 100));

        SeekBar hScaleSeek = infoContent.findViewWithTag("hScaleSeek");
        if (hScaleSeek != null) hScaleSeek.setProgress((int)((currentTuneRenderer.getHScale() - 0.7f) * 100));

        SeekBar threshSeek = infoContent.findViewWithTag("threshSeek");
        if (threshSeek != null) threshSeek.setProgress((int)(currentTuneRenderer.getMotionThreshold() * 100));

        SeekBar oddStartSeek = infoContent.findViewWithTag("oddStartSeek");
        if (oddStartSeek != null) oddStartSeek.setProgress((int)currentTuneRenderer.getOddStart());

        SeekBar oddLinesSeek = infoContent.findViewWithTag("oddLinesSeek");
        if (oddLinesSeek != null) oddLinesSeek.setProgress(Math.max(0, (int)currentTuneRenderer.getOddLines() - 200));

        SeekBar evenStartSeek = infoContent.findViewWithTag("evenStartSeek");
        if (evenStartSeek != null) evenStartSeek.setProgress(Math.max(0, (int)currentTuneRenderer.getEvenStart() - 200));

        SeekBar evenLinesSeek = infoContent.findViewWithTag("evenLinesSeek");
        if (evenLinesSeek != null) evenLinesSeek.setProgress(Math.max(0, (int)currentTuneRenderer.getEvenLines() - 200));

        // 更新标签
        TextView oddStartLabel = infoContent.findViewWithTag("oddStartLabel");
        if (oddStartLabel != null) oddStartLabel.setText("奇场起始: " + (int)currentTuneRenderer.getOddStart());

        TextView oddLinesLabel = infoContent.findViewWithTag("oddLinesLabel");
        if (oddLinesLabel != null) oddLinesLabel.setText("奇场行数: " + (int)currentTuneRenderer.getOddLines());

        TextView evenStartLabel = infoContent.findViewWithTag("evenStartLabel");
        if (evenStartLabel != null) evenStartLabel.setText("偶场起始: " + (int)currentTuneRenderer.getEvenStart());

        TextView evenLinesLabel = infoContent.findViewWithTag("evenLinesLabel");
        if (evenLinesLabel != null) evenLinesLabel.setText("偶场行数: " + (int)currentTuneRenderer.getEvenLines());

        TextView hOffsetLabel = infoContent.findViewWithTag("hOffsetLabel");
        if (hOffsetLabel != null) hOffsetLabel.setText("左边裁剪: " + (int)(currentTuneRenderer.getHOffset() * 100) + "%");

        TextView hScaleLabel = infoContent.findViewWithTag("hScaleLabel");
        if (hScaleLabel != null) hScaleLabel.setText("水平缩放: " + (int)(currentTuneRenderer.getHScale() * 100) + "%");

        TextView threshLabel = infoContent.findViewWithTag("threshLabel");
        if (threshLabel != null) threshLabel.setText("运动阈值: " + (int)(currentTuneRenderer.getMotionThreshold() * 100));

        updatePresetButtonStates();
        updateAlgoButtonStates();
        updateParamsInfo();
    }

    private void updateParamsInfo() {
        if (currentTuneRenderer == null) return;
        TextView info = infoContent.findViewWithTag("paramsInfo");
        if (info != null) {
            info.setText(String.format("源高=%.0f 输出=%.0f",
                    currentTuneRenderer.getSourceHeight(),
                    currentTuneRenderer.getOutputHeight()));
        }
    }

    private void showHdmiSettingsPanel(String cameraId) {
        isTunePanelVisible = true;
        infoContent.removeAllViews();
        addSubPanelHeader("Cam " + cameraId + " HDMI 设置");

        Size hdmiRes = CameraIcReader.getHdmiResolution();
        TextView resInfo = new TextView(this);
        resInfo.setText("当前 HDMI 输入: " + hdmiRes.getWidth() + "×" + hdmiRes.getHeight());
        resInfo.setTextColor(CameraIcReader.isHdmiSignalValid() ? COLOR_360 : COLOR_TEXT_SECONDARY);
        resInfo.setTextSize(16);
        resInfo.setPadding(0, 8, 0, 16);
        infoContent.addView(resInfo);

        TextView signalInfo = new TextView(this);
        signalInfo.setText("信号状态: " + (CameraIcReader.isHdmiSignalValid() ? "有效" : "无信号"));
        signalInfo.setTextColor(CameraIcReader.isHdmiSignalValid() ? COLOR_360 : Color.parseColor("#C47D7D"));
        signalInfo.setTextSize(16);
        infoContent.addView(signalInfo);

        LinearLayout audioRow = new LinearLayout(this);
        audioRow.setOrientation(LinearLayout.HORIZONTAL);
        audioRow.setGravity(Gravity.CENTER_VERTICAL);
        audioRow.setPadding(0, 24, 0, 8);

        TextView audioLabel = new TextView(this);
        audioLabel.setText("HDMI 音频");
        audioLabel.setTextColor(COLOR_TEXT_PRIMARY);
        audioLabel.setTextSize(16);
        audioLabel.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Switch audioSwitch = new Switch(this);
        LinearLayout.LayoutParams audioSwitchParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        audioSwitchParams.setMargins(8, 0, 0, 0);
        audioSwitch.setLayoutParams(audioSwitchParams);
        styleSwitch(audioSwitch);
        audioSwitch.setChecked(CameraIcReader.isHdmiAudioEnabled());
        audioSwitch.setOnCheckedChangeListener((btn, checked) -> {
            boolean success = CameraIcReader.setHdmiAudio(checked);
            if (!success) {
                btn.setChecked(!checked);
                Toast.makeText(this, "设置失败", Toast.LENGTH_SHORT).show();
            }
        });

        audioRow.addView(audioLabel);
        audioRow.addView(audioSwitch);
        infoContent.addView(audioRow);

        TextView hint = new TextView(this);
        hint.setText("\n提示:\nHDMI 采集依赖 LT6911C 芯片\n分辨率由输入源决定");
        hint.setTextColor(COLOR_TEXT_SECONDARY);
        hint.setTextSize(14);
        infoContent.addView(hint);
    }

    private void addSeekBarControlToPanel(LinearLayout parent, String label,
                                          String valueNode, String minNode, String maxNode) {
        int minVal = AwellCameraControl.readNodeInt(minNode, 0);
        int maxVal = AwellCameraControl.readNodeInt(maxNode, 100);
        int currentVal = AwellCameraControl.readNodeInt(valueNode, 50);

        TextView labelView = new TextView(this);
        labelView.setText(label + ": " + currentVal);
        labelView.setTextColor(COLOR_TEXT_PRIMARY);
        labelView.setTextSize(16);
        labelView.setPadding(0, 8, 0, 4);
        parent.addView(labelView);

        final int finalMinVal = minVal;
        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int newVal = progress + finalMinVal;
                labelView.setText(label + ": " + newVal);
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int newVal = seekBar.getProgress() + finalMinVal;
                AwellCameraControl.writeNodeInt(valueNode, newVal);
            }
        };

        parent.addView(createSeekBarWithButtons(valueNode + "_seek", maxVal - minVal, currentVal - minVal, labelView, listener));
    }

    private void addNodeRowToPanel(LinearLayout parent, String label, String nodeName) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 8, 0, 8);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextColor(COLOR_TEXT_PRIMARY);
        labelView.setTextSize(16);
        labelView.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(labelView);

        String value = AwellCameraControl.readNode(nodeName);
        TextView valueView = new TextView(this);
        valueView.setText(value != null ? value : "N/A");
        valueView.setTextColor(COLOR_ACCENT);
        valueView.setTextSize(16);
        valueView.setPadding(16, 0, 8, 0);
        row.addView(valueView);

        TextView editBtn = new TextView(this);
        editBtn.setText("Edit");
        editBtn.setTextSize(16);
        editBtn.setTextColor(COLOR_TEXT_SECONDARY);
        editBtn.setPadding(16, 12, 16, 12);
        editBtn.setMinHeight(52);
        editBtn.setGravity(Gravity.CENTER);
        editBtn.setOnClickListener(v -> showEditNodeDialog(nodeName, valueView));
        row.addView(editBtn);

        parent.addView(row);
    }

    private void showEditNodeDialog(String nodeName, TextView valueView) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(AwellCameraControl.readNode(nodeName));

        new AlertDialog.Builder(this)
                .setTitle("设置 " + AwellCameraControl.getNodeDisplayName(nodeName))
                .setView(input)
                .setPositiveButton("确定", (d, w) -> {
                    String newVal = input.getText().toString().trim();
                    if (!newVal.isEmpty()) {
                        AwellCameraControl.writeNode(nodeName, newVal);
                        valueView.setText(newVal);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void createCameraViews() {
        for (String cameraId : cameraAddOrder()) {
            if (!cameraEnabled.getOrDefault(cameraId, true)) continue;
            addCameraView(cameraId);
        }
    }

    private List<String> cameraAddOrder() {
        List<String> order = new ArrayList<>();
        for (String id : stackedCameraIds) {
            if (!order.contains(id)) order.add(id);
        }
        if (cameraIds != null) {
            for (String id : cameraIds) {
                if (!order.contains(id)) order.add(id);
            }
        }
        return order;
    }

    private CameraFrame createCameraFrame(String cameraId) {
        CameraFrame frame = new CameraFrame(this);
        frame.setCameraId(cameraId);
        frame.setThemeColor(themeColorFor(cameraId));
        frame.setOnResolutionChangeListener(this);
        frame.setOnFpsChangeListener(this);
        frame.setOnFullscreenChangeListener(this);

        CameraState state = savedStates.get(cameraId);
        if (state != null && state.resolution != null && !isLegacy360Resolution(state.resolution)
                && !CameraIcReader.isHdmiCamera(cameraId)) {
            cameraHelper.setResolution(cameraId, state.resolution);
        }

        frame.setCurrentResolution(cameraHelper.getCurrentResolution(cameraId));

        if (state != null) {
            frame.setCurrentRotation(state.rotation);
            frame.setCurrentFps(state.fps);
            cameraHelper.setTargetFps(cameraId, state.fps);
        } else {
            frame.setCurrentFps(30);
        }

        if (CameraIcReader.needsDeinterlace(cameraId)) {
            Size res = cameraHelper.getCurrentResolution(cameraId);
            boolean isNtsc = state != null ? state.isNtsc : (res == null || res.getHeight() <= 503);

            frame.enableDeinterlaceMode(isNtsc);

            DeinterlaceRenderer renderer = frame.getDeinterlaceRenderer();
            if (renderer != null) {
                if (state != null && state.deinterlaceParamsSaved) {
                    applyDeinterlaceState(renderer, state);
                    frame.setDeinterlaceEnabled(state.deinterlaceEnabled);
                    frame.setDeinterlacePresetActive(state.deinterlacePresetActive);
                    frame.setNtscMode(state.isNtsc);
                } else {
                    renderer.setDeinterlaceEnabled(false);
                    frame.setDeinterlaceEnabled(false);
                }
            }

            Log.d(TAG, "Camera " + cameraId + " de-interlace mode enabled, NTSC=" + isNtsc
                    + " processing=" + (state != null && state.deinterlaceEnabled));

            bindGlCamera(frame, cameraId);

        } else if (useOpenGLByDefault) {
            frame.enableOpenGLMode();
            Log.d(TAG, "Camera " + cameraId + " OpenGL passthrough enabled");
            bindGlCamera(frame, cameraId);

        } else {
            frame.getTextureView().setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                private boolean firstFrame;
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int w, int h) {
                    cameraHelper.openCamera(cameraId, surface, w, h);
                    frame.setCurrentResolution(cameraHelper.getCurrentResolution(cameraId));
                }
                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture s, int w, int h) {}
                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture s) {
                    return true;
                }
                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture s) {
                    if (!firstFrame) {
                        firstFrame = true;
                        frame.hideLoading();
                    }
                }
            });
        }

        if (state != null) {
            frame.setLetterboxWhite(state.letterboxWhite);
            frame.restoreViewFlags(state.aspectLocked, state.oneToOneMode);
        }

        return frame;
    }

    private void applyDeinterlaceState(DeinterlaceRenderer renderer, CameraState state) {
        if (state.deinterlacePresetActive) {
            if (state.isNtsc) {
                renderer.setNtscMode();
            } else {
                renderer.setPalMode();
            }
        }
        renderer.setFrameParams(state.sourceHeight, state.outputHeight,
                state.oddStart, state.oddLines, state.evenStart, state.evenLines);
        renderer.setHOffset(state.hOffset);
        renderer.setHScale(state.hScale);
        renderer.setBlendFactor(state.blendFactor);
        renderer.setMotionThreshold(state.motionThreshold);
        renderer.setSwapFields(state.swapFields);
        renderer.setAlgorithm(state.algorithm);
        renderer.setDeinterlaceEnabled(state.deinterlaceEnabled);
    }

    private void bindGlCamera(CameraFrame frame, String cameraId) {
        frame.setOnFirstFrameListener(frame::hideLoading);
        frame.setSurfaceReadyListener(surfaceTexture -> {
            GLSurfaceView glView = frame.getGLSurfaceView();
            if (glView != null) {
                cameraHelper.openCamera(cameraId, surfaceTexture, glView.getWidth(), glView.getHeight());
                frame.setCurrentResolution(cameraHelper.getCurrentResolution(cameraId));
            }
        });
    }

    private void recreateOpenGLAffectedCameras() {
        if (cameraIds == null) return;
        for (String id : cameraIds) {
            if (!cameraEnabled.getOrDefault(id, false)) continue;
            if (CameraIcReader.needsDeinterlace(id)) continue;
            if (cameraFrames.containsKey(id)) {
                try {
                    removeCameraView(id);
                    addCameraView(id);
                } catch (Exception e) {
                    Log.e(TAG, "recreate OpenGL camera " + id, e);
                }
            }
        }
    }

    private int themeColorFor(String cameraId) {
        int idx;
        try {
            idx = Integer.parseInt(cameraId);
        } catch (NumberFormatException e) {
            idx = Math.abs(cameraId.hashCode());
        }
        return themeColors[Math.floorMod(idx, themeColors.length)];
    }

    private void addCameraView(String cameraId) {
        if (cameraFrames.containsKey(cameraId)) return;

        CameraFrame frame = createCameraFrame(cameraId);
        CameraState state = savedStates.get(cameraId);
        if (state != null) {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(state.width, state.height);
            frame.setLayoutParams(params);
            frame.setX(state.x);
            frame.setY(state.y);
        }
        frame.setOnGeometryChangeListener(f -> persistSettings());

        container.addView(frame);
        cameraFrames.put(cameraId, frame);
    }

    private void removeCameraView(String cameraId) {
        CameraFrame frame = cameraFrames.remove(cameraId);
        if (frame != null) {
            captureFrameState(cameraId, frame);
            try {
                cameraHelper.closeCamera(cameraId);
            } catch (Exception e) {
                Log.e(TAG, "closeCamera " + cameraId, e);
            }
            try {
                frame.release();
            } catch (Exception e) {
                Log.e(TAG, "frame.release " + cameraId, e);
            }
            container.removeView(frame);

            if (cameraId.equals(fullscreenCameraId)) {
                fullscreenCameraId = null;
                fullscreenSavedState = null;
            }
        }
    }

    @Override
    public void onResolutionChange(String cameraId, Size newResolution) {
        cameraHelper.setResolution(cameraId, newResolution);
        try {
            cameraHelper.closeCamera(cameraId);
        } catch (Exception e) {
            Log.e(TAG, "closeCamera on resolution change " + cameraId, e);
        }

        CameraFrame frame = cameraFrames.get(cameraId);
        if (frame != null) {
            if (frame.isUsingGL()) {
                DeinterlaceRenderer renderer = frame.getDeinterlaceRenderer();
                GLSurfaceView glView = frame.getGLSurfaceView();
                if (renderer != null && glView != null) {
                    SurfaceTexture surfaceTexture = renderer.getSurfaceTexture();
                    if (surfaceTexture != null) {
                        cameraHelper.openCamera(cameraId, surfaceTexture, glView.getWidth(), glView.getHeight());
                        frame.setCurrentResolution(newResolution);
                    } else {
                        frame.setSurfaceReadyListener(st -> {
                            cameraHelper.openCamera(cameraId, st, glView.getWidth(), glView.getHeight());
                            frame.setCurrentResolution(newResolution);
                        });
                    }
                }
            } else {
                TextureView tv = frame.getTextureView();
                if (tv != null && tv.isAvailable()) {
                    SurfaceTexture st = tv.getSurfaceTexture();
                    if (st != null) {
                        cameraHelper.openCamera(cameraId, st, tv.getWidth(), tv.getHeight());
                        frame.setCurrentResolution(newResolution);
                    }
                }
            }
        }

        Toast.makeText(this, "Camera " + cameraId + " → " +
                newResolution.getWidth() + "×" + newResolution.getHeight(), Toast.LENGTH_SHORT).show();
        persistSettings();
    }

    @Override
    public Size[] getAvailableResolutions(String cameraId) {
        return cameraHelper.getSupportedResolutions(cameraId);
    }

    @Override
    public void onFpsChange(String cameraId, int newFps) {
        cameraHelper.updateFps(cameraId, newFps);
        Toast.makeText(this, "Camera " + cameraId + " → " + newFps + " fps", Toast.LENGTH_SHORT).show();
        persistSettings();
    }

    @Override
    protected void onPause() {
        persistSettings();
        super.onPause();
        for (CameraFrame frame : cameraFrames.values()) {
            frame.onPause();
        }
        cameraHelper.closeAllCameras();
    }

    @Override
    protected void onResume() {
        super.onResume();
        for (CameraFrame frame : cameraFrames.values()) {
            frame.onResume();
        }
        if (checkCameraPermission() && cameraIds != null) {
            for (Map.Entry<String, CameraFrame> entry : cameraFrames.entrySet()) {
                String cameraId = entry.getKey();
                CameraFrame frame = entry.getValue();
                if (frame.isUsingGL()) {
                    // GLSurfaceView 会通过回调重新打开
                } else {
                    TextureView tv = frame.getTextureView();
                    if (tv != null && tv.isAvailable()) {
                        SurfaceTexture st = tv.getSurfaceTexture();
                        if (st != null) {
                            cameraHelper.openCamera(cameraId, st, tv.getWidth(), tv.getHeight());
                        }
                    }
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        for (CameraFrame frame : cameraFrames.values()) {
            try {
                frame.release();
            } catch (Exception e) {
                Log.e(TAG, "frame.release onDestroy", e);
            }
        }
        cameraFrames.clear();
    }

    private void restoreDefaultLayouts() {
        if (cameraIds == null) return;
        if (fullscreenCameraId != null) {
            CameraFrame fs = cameraFrames.get(fullscreenCameraId);
            if (fs != null) fs.setFullscreen(false);
        }
        calculateInitialLayouts();
        for (String id : cameraIds) {
            CameraState st = savedStates.get(id);
            if (st == null) continue;
            st.rotation = 0;
            st.oneToOneMode = false;
            st.aspectLocked = true;
            CameraFrame frame = cameraFrames.get(id);
            if (frame == null) continue;
            frame.restoreViewFlags(true, false);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(st.width, st.height);
            frame.setLayoutParams(params);
            frame.setX(st.x);
            frame.setY(st.y);
            frame.setCurrentRotation(0);
        }
        if (cameraIds != null) {
            stackedCameraIds.clear();
            for (String id : cameraIds) {
                CameraFrame frame = cameraFrames.get(id);
                if (frame != null) {
                    frame.bringToFront();
                    stackedCameraIds.add(id);
                }
            }
        }
        persistSettings();
        Toast.makeText(this, "已还原默认布局", Toast.LENGTH_SHORT).show();
    }

    private CameraState ensureState(String cameraId) {
        CameraState state = savedStates.get(cameraId);
        if (state == null) {
            state = new CameraState(0, 0, 320, 240, null, 0, 30);
            savedStates.put(cameraId, state);
        }
        return state;
    }

    private void captureFrameState(String cameraId, CameraFrame frame) {
        if (frame == null) return;
        CameraState state = ensureState(cameraId);
        boolean capturingFullscreen = cameraId.equals(fullscreenCameraId) || frame.isFullscreen();
        if (!capturingFullscreen) {
            int w = frame.getWidth();
            int h = frame.getHeight();
            if (w > 0 && h > 0) {
                state.width = w;
                state.height = h;
                state.x = frame.getX();
                state.y = frame.getY();
            }
        } else if (fullscreenSavedState != null && cameraId.equals(fullscreenCameraId)) {
            state.x = fullscreenSavedState.x;
            state.y = fullscreenSavedState.y;
            state.width = fullscreenSavedState.width;
            state.height = fullscreenSavedState.height;
        }
        state.rotation = frame.getCurrentRotation();
        state.fps = frame.getCurrentFps();
        state.letterboxWhite = frame.isLetterboxWhite();
        state.aspectLocked = frame.isAspectLocked();
        state.oneToOneMode = frame.isOneToOneMode();
        Size res = cameraHelper.getCurrentResolution(cameraId);
        if (res != null) state.resolution = res;
        CameraIcReader.IcType manual = CameraIcReader.getManualIcType(cameraId);
        state.icOverride = manual != null ? manual.name() : null;

        if (CameraIcReader.needsDeinterlace(cameraId)) {
            state.deinterlaceEnabled = frame.isDeinterlaceEnabled();
            state.deinterlacePresetActive = frame.isDeinterlacePresetActive();
            state.isNtsc = frame.isNtsc();
            DeinterlaceRenderer renderer = frame.getDeinterlaceRenderer();
            if (renderer != null) {
                state.deinterlaceParamsSaved = true;
                state.algorithm = renderer.getAlgorithm();
                state.swapFields = renderer.isSwapFields();
                state.blendFactor = renderer.getBlendFactor();
                state.motionThreshold = renderer.getMotionThreshold();
                state.hOffset = renderer.getHOffset();
                state.hScale = renderer.getHScale();
                state.sourceHeight = renderer.getSourceHeight();
                state.outputHeight = renderer.getOutputHeight();
                state.oddStart = renderer.getOddStart();
                state.oddLines = renderer.getOddLines();
                state.evenStart = renderer.getEvenStart();
                state.evenLines = renderer.getEvenLines();
                state.deinterlaceEnabled = renderer.isDeinterlaceEnabled();
            }
        }
    }

    private void persistSettings() {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.putBoolean(PREF_PANEL_PINNED, panelPinned);
        if (cameraIds == null) {
            editor.apply();
            return;
        }
        for (Map.Entry<String, CameraFrame> entry : cameraFrames.entrySet()) {
            captureFrameState(entry.getKey(), entry.getValue());
        }
        JSONObject root = new JSONObject();
        try {
            root.put("pin", panelPinned);
            JSONArray zOrder = new JSONArray();
            if (container != null) {
                stackedCameraIds.clear();
                for (int i = 0; i < container.getChildCount(); i++) {
                    View child = container.getChildAt(i);
                    if (child instanceof CameraFrame) {
                        String id = ((CameraFrame) child).getCameraId();
                        if (id != null) {
                            stackedCameraIds.add(id);
                            zOrder.put(id);
                        }
                    }
                }
            }
            root.put("z", zOrder);
            JSONObject cams = new JSONObject();
            for (String id : cameraIds) {
                CameraState st = savedStates.get(id);
                if (st == null) continue;
                JSONObject o = new JSONObject();
                o.put("x", st.x);
                o.put("y", st.y);
                o.put("w", st.width);
                o.put("h", st.height);
                o.put("rot", st.rotation);
                o.put("fps", st.fps);
                o.put("enabled", cameraEnabled.getOrDefault(id, true));
                o.put("white", st.letterboxWhite);
                o.put("lock", st.aspectLocked);
                o.put("one", st.oneToOneMode);
                if (st.resolution != null) {
                    o.put("rw", st.resolution.getWidth());
                    o.put("rh", st.resolution.getHeight());
                }
                if (st.icOverride != null) {
                    o.put("ic", st.icOverride);
                }
                if (st.deinterlaceParamsSaved) {
                    o.put("de", st.deinterlaceEnabled);
                    o.put("preset", st.deinterlacePresetActive);
                    o.put("ntsc", st.isNtsc);
                    o.put("algo", st.algorithm);
                    o.put("swap", st.swapFields);
                    o.put("blend", st.blendFactor);
                    o.put("motion", st.motionThreshold);
                    o.put("hoff", st.hOffset);
                    o.put("hscale", st.hScale);
                    o.put("srcH", st.sourceHeight);
                    o.put("outH", st.outputHeight);
                    o.put("oddS", st.oddStart);
                    o.put("oddL", st.oddLines);
                    o.put("evenS", st.evenStart);
                    o.put("evenL", st.evenLines);
                }
                cams.put(id, o);
            }
            root.put("cameras", cams);
            editor.putString(PREF_SESSION, root.toString());
            editor.apply();
        } catch (JSONException e) {
            Log.e(TAG, "persistSettings failed", e);
            editor.apply();
        }
    }

    private void loadPersistedSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String json = prefs.getString(PREF_SESSION, null);
        if (json == null || json.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(json);
            panelPinned = root.optBoolean("pin", prefs.getBoolean(PREF_PANEL_PINNED, false));
            updatePinButton();
            stackedCameraIds.clear();
            JSONArray zOrder = root.optJSONArray("z");
            if (zOrder != null) {
                for (int i = 0; i < zOrder.length(); i++) {
                    String id = zOrder.optString(i, "");
                    if (!id.isEmpty()) stackedCameraIds.add(id);
                }
            }
            JSONObject cams = root.optJSONObject("cameras");
            if (cams == null) return;
            for (String id : cameraIds) {
                JSONObject o = cams.optJSONObject(id);
                if (o == null) continue;
                CameraState st = ensureState(id);
                st.x = (float) o.optDouble("x", st.x);
                st.y = (float) o.optDouble("y", st.y);
                st.width = o.optInt("w", st.width);
                st.height = o.optInt("h", st.height);
                st.rotation = (float) o.optDouble("rot", st.rotation);
                st.fps = o.optInt("fps", st.fps);
                st.letterboxWhite = o.optBoolean("white", st.letterboxWhite);
                st.aspectLocked = o.optBoolean("lock", st.aspectLocked);
                st.oneToOneMode = o.optBoolean("one", st.oneToOneMode);
                int rw = o.optInt("rw", 0);
                int rh = o.optInt("rh", 0);
                if (rw > 0 && rh > 0 && rh < 4000) {
                    st.resolution = new Size(rw, rh);
                }
                cameraEnabled.put(id, o.optBoolean("enabled", true));
                String icName = o.optString("ic", "");
                CameraIcReader.IcType ic = icTypeFromName(icName);
                if (ic != null) {
                    st.icOverride = ic.name();
                    CameraIcReader.setManualIcType(id, ic);
                }
                if (o.has("algo")) {
                    st.deinterlaceParamsSaved = true;
                    st.deinterlaceEnabled = o.optBoolean("de", false);
                    st.deinterlacePresetActive = o.optBoolean("preset", false);
                    st.isNtsc = o.optBoolean("ntsc", true);
                    st.algorithm = o.optInt("algo", DeinterlaceRenderer.ALGO_WEAVE);
                    st.swapFields = o.optBoolean("swap", false);
                    st.blendFactor = (float) o.optDouble("blend", 0.5);
                    st.motionThreshold = (float) o.optDouble("motion", 0.08);
                    st.hOffset = (float) o.optDouble("hoff", 0);
                    st.hScale = (float) o.optDouble("hscale", 1);
                    st.sourceHeight = (float) o.optDouble("srcH", 503);
                    st.outputHeight = (float) o.optDouble("outH", 480);
                    st.oddStart = (float) o.optDouble("oddS", 0);
                    st.oddLines = (float) o.optDouble("oddL", 240);
                    st.evenStart = (float) o.optDouble("evenS", 263);
                    st.evenLines = (float) o.optDouble("evenL", 240);
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "loadPersistedSettings failed", e);
        }
    }

    private CameraIcReader.IcType icTypeFromName(String name) {
        if (name == null || name.isEmpty()) return null;
        for (CameraIcReader.IcType type : CameraIcReader.IcType.values()) {
            if (type.name().equals(name) || type.name.equals(name)) {
                return type;
            }
        }
        return null;
    }

    static class PinIconView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean pinned;

        PinIconView(android.content.Context context) {
            super(context);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        void setPinned(boolean pinned) {
            this.pinned = pinned;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float s = Math.min(getWidth(), getHeight());
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float stroke = Math.max(3f, s * 0.1f);
            float headR = s * 0.16f;
            float headY = cy - s * 0.10f;
            float tipY = cy + s * 0.28f;

            paint.setColor(pinned ? Color.parseColor("#7DA8C4") : Color.parseColor("#D4D4D4"));
            paint.setStrokeWidth(stroke);
            paint.setStyle(pinned ? Paint.Style.FILL : Paint.Style.STROKE);
            canvas.drawCircle(cx, headY, headR, paint);
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawLine(cx, headY + headR, cx, tipY, paint);
        }
    }
}