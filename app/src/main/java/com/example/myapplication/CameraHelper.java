package com.example.myapplication;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class CameraHelper {
    private static final String TAG = "CameraHelper";

    private Context context;
    private CameraManager cameraManager;
    private Map<String, CameraDevice> openCameras = new HashMap<>();
    private Map<String, CameraCaptureSession> captureSessions = new HashMap<>();
    private Map<String, Size> cameraResolutions = new HashMap<>();
    private Map<String, Integer> cameraTargetFps = new HashMap<>();  // 目标帧率
    private Map<String, Surface> cameraSurfaces = new HashMap<>();   // 保存 Surface 用于重启
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;

    public CameraHelper(Context context) {
        this.context = context;
        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        startBackgroundThread();
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    public String[] getCameraIdList() {
        try {
            return cameraManager.getCameraIdList();
        } catch (CameraAccessException e) {
            Log.e(TAG, "getCameraIdList: ", e);
            return null;
        }
    }

    public Size[] getSupportedResolutions(String cameraId) {
        try {
            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
                if (sizes == null) sizes = new Size[0];
                // 额外查 high-resolution 列表（Camera2 可能把大尺寸放这里）
                try {
                    Size[] hiRes = map.getHighResolutionOutputSizes(android.graphics.ImageFormat.PRIVATE);
                    if (hiRes != null && hiRes.length > 0) {
                        Size[] merged = new Size[sizes.length + hiRes.length];
                        System.arraycopy(sizes, 0, merged, 0, sizes.length);
                        System.arraycopy(hiRes, 0, merged, sizes.length, hiRes.length);
                        sizes = merged;
                        Log.d(TAG, "Cam " + cameraId + " merged " + hiRes.length + " high-res sizes");
                    }
                } catch (Exception e) {
                    // getHighResolutionOutputSizes 可能不支持 PRIVATE 格式
                }
                if (sizes.length == 0) {
                    return new Size[]{new Size(640, 480)};
                }
                Arrays.sort(sizes, (a, b) -> b.getWidth() * b.getHeight() - a.getWidth() * a.getHeight());
                return sizes;
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "getSupportedResolutions: ", e);
        }
        return new Size[]{new Size(640, 480)};
    }

    /**
     * 获取支持的帧率范围
     */
    public Range<Integer>[] getSupportedFpsRanges(String cameraId) {
        try {
            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(cameraId);
            return chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        } catch (CameraAccessException e) {
            Log.e(TAG, "getSupportedFpsRanges: ", e);
        }
        return null;
    }

    public Size getCurrentResolution(String cameraId) {
        return cameraResolutions.getOrDefault(cameraId, new Size(640, 480));
    }

    public void setResolution(String cameraId, Size resolution) {
        cameraResolutions.put(cameraId, resolution);
    }

    /**
     * 获取当前目标帧率
     */
    public int getTargetFps(String cameraId) {
        return cameraTargetFps.getOrDefault(cameraId, 30);
    }

    /**
     * 设置目标帧率
     */
    public void setTargetFps(String cameraId, int fps) {
        cameraTargetFps.put(cameraId, fps);
    }

    /**
     * 为 HDMI 摄像头选择最佳分辨率
     */
    public Size selectResolutionForHdmi(String cameraId) {
        Size hdmiRes = CameraIcReader.getHdmiResolution();
        Size[] supportedSizes = getSupportedResolutions(cameraId);

        for (Size size : supportedSizes) {
            if (size.getWidth() == hdmiRes.getWidth() &&
                    size.getHeight() == hdmiRes.getHeight()) {
                Log.d(TAG, "Found exact match for HDMI: " + size);
                return size;
            }
        }

        Size bestMatch = null;
        int minDiff = Integer.MAX_VALUE;
        for (Size size : supportedSizes) {
            int diff = Math.abs(size.getWidth() - hdmiRes.getWidth()) +
                    Math.abs(size.getHeight() - hdmiRes.getHeight());
            if (diff < minDiff) {
                minDiff = diff;
                bestMatch = size;
            }
        }

        if (bestMatch != null) {
            Log.d(TAG, "Best match for HDMI " + hdmiRes + ": " + bestMatch);
            return bestMatch;
        }

        return selectDefaultResolution(supportedSizes);
    }

    /**
     * 为 360 摄像头选择分辨率
     * 选择 1920×4320 使 MDP 不缩放，App 直接收原始交织帧
     */
    public Size select360Resolution(String cameraId) {
        Size[] sizes = getSupportedResolutions(cameraId);
        // 打印所有支持的分辨率，便于调试
        StringBuilder sb = new StringBuilder("360 cam " + cameraId + " supported sizes: ");
        for (Size s : sizes) sb.append(s.getWidth()).append("x").append(s.getHeight()).append(", ");
        Log.d(TAG, sb.toString());

        // 优先: 精确匹配 1920×4320
        for (Size size : sizes) {
            if (size.getWidth() == 1920 && size.getHeight() == 4320) {
                cameraResolutions.put(cameraId, size);
                Log.d(TAG, "360 resolution exact: " + size);
                return size;
            }
        }
        // 次选: 宽 1920 且高 > 2000 的尺寸（可能是 1920×2160 等）
        for (Size size : sizes) {
            if (size.getWidth() == 1920 && size.getHeight() > 2000) {
                cameraResolutions.put(cameraId, size);
                Log.d(TAG, "360 resolution wide-tall: " + size);
                return size;
            }
        }
        // 退路：找 height > width*2 的尺寸
        for (Size size : sizes) {
            if (size.getHeight() > size.getWidth() * 2) {
                cameraResolutions.put(cameraId, size);
                Log.d(TAG, "360 resolution fallback tall: " + size);
                return size;
            }
        }
        // 最后退路: 最大分辨率
        Size largest = sizes[0];
        cameraResolutions.put(cameraId, largest);
        Log.w(TAG, "No 360 resolution found, using largest: " + largest);
        return largest;
    }

    /**
     * 读取 HAL 传来的 360 通道交织顺序 (system property)
     * HAL 在 flush_buffer 时读取前4行 TP2815 header，写入 vendor.cam.360.ch_order
     * @return chMap[lineOffset] = channelNumber, 默认 {0,1,2,3}
     */
    public static int[] readChannelOrder() {
        try {
            String order = "0,1,2,3";
            try {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                java.lang.reflect.Method get = sp.getMethod("get", String.class, String.class);
                order = (String) get.invoke(null, "vendor.cam.360.ch_order", "0,1,2,3");
            } catch (Exception e) {
                Log.w(TAG, "SystemProperties.get failed, using default", e);
            }
            String[] parts = order.split(",");
            if (parts.length == 4) {
                int[] map = new int[4];
                for (int i = 0; i < 4; i++) {
                    map[i] = Integer.parseInt(parts[i].trim());
                }
                Log.d(TAG, "360 ch_order from HAL: " + order);
                return map;
            }
        } catch (Exception e) {
            Log.e(TAG, "readChannelOrder failed", e);
        }
        return new int[]{0, 1, 2, 3};
    }

    /**
     * 清除 HAL 的 360 通道顺序 property（IC 切换时调用，防止读到残留值）
     */
    public static void clearChannelOrder() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method set = sp.getMethod("set", String.class, String.class);
            set.invoke(null, "vendor.cam.360.ch_order", "");
            Log.d(TAG, "360 ch_order cleared");
        } catch (Exception e) {
            Log.w(TAG, "clearChannelOrder failed", e);
        }
    }

    public void clearResolution(String cameraId) {
        cameraResolutions.remove(cameraId);
    }

    public void openCamera(String cameraId, SurfaceTexture surfaceTexture, int viewWidth, int viewHeight) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        Size resolution = cameraResolutions.get(cameraId);
        if (resolution == null) {
            Size[] sizes = getSupportedResolutions(cameraId);
            resolution = selectDefaultResolution(sizes);
            cameraResolutions.put(cameraId, resolution);
        }
        Log.d(TAG, "openCamera " + cameraId + " resolution=" + resolution);

        // 默认帧率 30fps
        if (!cameraTargetFps.containsKey(cameraId)) {
            cameraTargetFps.put(cameraId, 30);
        }

        try {
            surfaceTexture.setDefaultBufferSize(resolution.getWidth(), resolution.getHeight());
            final Surface surface = new Surface(surfaceTexture);
            cameraSurfaces.put(cameraId, surface);

            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    openCameras.put(cameraId, camera);
                    createCaptureSession(cameraId, camera, surface);
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    openCameras.remove(cameraId);
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    openCameras.remove(cameraId);
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "openCamera: ", e);
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

    private void createCaptureSession(String cameraId, CameraDevice camera, Surface surface) {
        try {
            camera.createCaptureSession(Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            captureSessions.put(cameraId, session);
                            startPreview(cameraId, camera, session, surface);
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "onConfigureFailed: " + cameraId);
                        }
                    }, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "createCaptureSession: ", e);
        }
    }

    private void startPreview(String cameraId, CameraDevice camera,
                              CameraCaptureSession session, Surface surface) {
        try {
            CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(surface);

            // 设置目标帧率
            int targetFps = cameraTargetFps.getOrDefault(cameraId, 30);
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    new Range<>(targetFps, targetFps));

            Log.d(TAG, "Camera " + cameraId + " preview with FPS: " + targetFps);

            session.setRepeatingRequest(builder.build(), null, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "startPreview: ", e);
        }
    }

    /**
     * 更新帧率（不重新打开摄像头）
     */
    public void updateFps(String cameraId, int newFps) {
        cameraTargetFps.put(cameraId, newFps);

        CameraDevice camera = openCameras.get(cameraId);
        CameraCaptureSession session = captureSessions.get(cameraId);
        Surface surface = cameraSurfaces.get(cameraId);

        if (camera != null && session != null && surface != null) {
            startPreview(cameraId, camera, session, surface);
        }
    }

    public void closeCamera(String cameraId) {
        CameraCaptureSession session = captureSessions.remove(cameraId);
        if (session != null) session.close();

        CameraDevice camera = openCameras.remove(cameraId);
        if (camera != null) camera.close();

        cameraSurfaces.remove(cameraId);
        // 保留分辨率设置，避免切换分辨率/重开时被默认值覆盖
    }

    public void closeAllCameras() {
        for (CameraCaptureSession session : captureSessions.values()) {
            session.close();
        }
        captureSessions.clear();

        for (CameraDevice camera : openCameras.values()) {
            camera.close();
        }
        openCameras.clear();
        cameraSurfaces.clear();
    }

    public int getOpenCameraCount() {
        return openCameras.size();
    }
}