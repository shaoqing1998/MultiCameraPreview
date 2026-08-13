package com.example.myapplication;

import android.util.Log;
import android.util.Size;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Map;

public class CameraIcReader {
    private static final String TAG = "CameraIcReader";
    private static final String HDMI_DEVICE_PATH = "/dev/mid_lt6911c";

    private static final String[] KNOWN_HDMI_PATHS = {
            "/sys/devices/platform/16000000.vdec_gcon/consumer:platform:10006000.power-controller/subsystem/platform:10005000.pinctrl--i2c:3-002b/consumer/hdmi_resolution",
            "/sys/devices/platform/1100f000.i2c/i2c-3/3-002b/hdmi_resolution",
            "/sys/devices/platform/11007000.i2c/i2c-1/1-002b/hdmi_resolution",
            "/sys/bus/i2c/devices/3-002b/hdmi_resolution",
            "/sys/bus/i2c/devices/1-002b/hdmi_resolution"
    };

    private static final String[] KNOWN_IC_INFO_PATHS = {
            "/proc/camera_ic_info",
            "/proc/driver/camera_ic_info"
    };

    public enum IcType {
        TP2815("TP2815", "360°环视"),
        TP2815_SUB("TP2815_SUB", "360°副"),
        TP9951("TP9951", "CVBS"),
        TP9950("TP9950", "CVBS"),
        TP2850("TP2850", "CVBS"),
        TP2860("TP2860", "CVBS"),
        LT6911C("LT6911C", "HDMI"),
        UNKNOWN("未知", "未知");

        public final String name;
        public final String displayName;

        IcType(String name, String displayName) {
            this.name = name;
            this.displayName = displayName;
        }
    }

    private static Map<String, IcType> cameraIcMap = null;
    private static Map<String, IcType> manualOverrideMap = new HashMap<>();  // 手动覆盖
    private static boolean hdmiAudioEnabled = true;
    private static String cachedHdmiResolutionPath = null;
    private static String cachedCameraIcInfoPath = null;

    // ============ 手动覆盖 IC 类型 ============

    /**
     * 手动设置某个摄像头的 IC 类型
     */
    public static void setManualIcType(String cameraId, IcType type) {
        if (type == null || type == IcType.UNKNOWN) {
            manualOverrideMap.remove(cameraId);
        } else {
            manualOverrideMap.put(cameraId, type);
        }
        Log.d(TAG, "Manual override: Camera " + cameraId + " -> " + type);
    }

    /**
     * 清除某个摄像头的手动覆盖
     */
    public static void clearManualIcType(String cameraId) {
        manualOverrideMap.remove(cameraId);
    }

    /**
     * 获取手动设置的 IC 类型 (如果有的话)
     */
    public static IcType getManualIcType(String cameraId) {
        return manualOverrideMap.get(cameraId);
    }

    /**
     * 检查是否有手动覆盖
     */
    public static boolean hasManualOverride(String cameraId) {
        return manualOverrideMap.containsKey(cameraId);
    }

    /**
     * 获取所有可选择的 IC 类型 (用于 UI 选择)
     */
    public static IcType[] getSelectableIcTypes() {
        return new IcType[] {
                IcType.UNKNOWN,
                IcType.TP9951,
                IcType.TP2815,
                IcType.TP2815_SUB,
                IcType.LT6911C
        };
    }

    // ============ 路径查找 ============

    private static String findHdmiResolutionPath() {
        if (cachedHdmiResolutionPath != null) return cachedHdmiResolutionPath;

        for (String path : KNOWN_HDMI_PATHS) {
            File f = new File(path);
            if (f.exists() && f.canRead()) {
                cachedHdmiResolutionPath = path;
                Log.d(TAG, "Found hdmi_resolution at known path: " + path);
                return path;
            }
        }

        String[] searchDirs = {"/sys/bus/i2c/devices"};
        for (String dir : searchDirs) {
            String path = findFile(new File(dir), "hdmi_resolution", 3);
            if (path != null) {
                cachedHdmiResolutionPath = path;
                return path;
            }
        }

        Log.w(TAG, "hdmi_resolution not found");
        return null;
    }

    private static String findCameraIcInfoPath() {
        if (cachedCameraIcInfoPath != null) return cachedCameraIcInfoPath;

        for (String path : KNOWN_IC_INFO_PATHS) {
            File file = new File(path);
            if (file.exists() && file.canRead()) {
                cachedCameraIcInfoPath = path;
                Log.d(TAG, "Found camera_ic_info at: " + path);
                return path;
            }
        }

        String path = findFile(new File("/proc"), "camera_ic_info", 2);
        if (path != null) {
            cachedCameraIcInfoPath = path;
            return path;
        }

        Log.w(TAG, "camera_ic_info not found");
        return null;
    }

    private static String findFile(File dir, String fileName, int maxDepth) {
        if (dir == null || !dir.exists() || !dir.isDirectory() || maxDepth <= 0) {
            return null;
        }

        try {
            File[] files = dir.listFiles();
            if (files == null) return null;

            for (File file : files) {
                if (file.isFile() && file.getName().equals(fileName)) {
                    if (file.canRead()) {
                        Log.d(TAG, "Found " + fileName + " at: " + file.getAbsolutePath());
                        return file.getAbsolutePath();
                    }
                } else if (file.isDirectory()) {
                    String result = findFile(file, fileName, maxDepth - 1);
                    if (result != null) return result;
                }
            }
        } catch (Exception e) {
            // 忽略权限异常
        }
        return null;
    }

    // ============ IC 类型读取 ============

    public static Map<String, IcType> getCameraIcMap() {
        if (cameraIcMap != null) return cameraIcMap;

        cameraIcMap = new HashMap<>();
        String path = findCameraIcInfoPath();
        if (path == null) {
            Log.e(TAG, "Camera IC info file not found");
            return cameraIcMap;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Log.d(TAG, "Read line: " + line);
                if (line.startsWith("[Camera")) {
                    int idx = line.indexOf("]");
                    if (idx > 0) {
                        String cameraId = line.substring(7, idx);
                        String icName = line.substring(idx + 2).toLowerCase();

                        IcType type;
                        if (icName.contains("lt6911")) {
                            type = IcType.LT6911C;
                        } else if (icName.contains("tp2815sub") || icName.contains("tp2815_sub")) {
                            type = IcType.TP2815_SUB;
                        } else if (icName.contains("tp2815")) {
                            type = IcType.TP2815;
                        } else if (icName.contains("tp9951")) {
                            type = IcType.TP9951;
                        } else if (icName.contains("tp9950")) {
                            type = IcType.TP9950;
                        } else if (icName.contains("tp2850")) {
                            type = IcType.TP2850;
                        } else if (icName.contains("tp2860")) {
                            type = IcType.TP2860;
                        } else {
                            type = IcType.UNKNOWN;
                        }

                        cameraIcMap.put(cameraId, type);
                        Log.d(TAG, "Camera " + cameraId + " -> " + type);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to read camera IC info: " + e.getMessage());
            e.printStackTrace();
        }

        return cameraIcMap;
    }

    /**
     * 获取 IC 类型 (优先使用手动覆盖)
     */
    public static IcType getIcType(String cameraId) {
        // 优先使用手动覆盖
        IcType manualType = manualOverrideMap.get(cameraId);
        if (manualType != null) {
            return manualType;
        }

        IcType type = getCameraIcMap().get(cameraId);
        return type != null ? type : IcType.UNKNOWN;
    }

    /**
     * 获取原始检测的 IC 类型 (忽略手动覆盖)
     */
    public static IcType getOriginalIcType(String cameraId) {
        IcType type = getCameraIcMap().get(cameraId);
        return type != null ? type : IcType.UNKNOWN;
    }

    public static boolean isHdmiCamera(String cameraId) {
        return getIcType(cameraId) == IcType.LT6911C;
    }

    public static boolean is360Camera(String cameraId) {
        IcType type = getIcType(cameraId);
        return type == IcType.TP2815 || type == IcType.TP2815_SUB;
    }

    /**
     * 判断是否是 CVBS 摄像头
     */
    public static boolean isCvbsCamera(String cameraId) {
        IcType type = getIcType(cameraId);
        return type == IcType.TP9951 || type == IcType.TP9950 ||
                type == IcType.TP2850 || type == IcType.TP2860;
    }

    /**
     * 判断是否需要 de-interlace
     */
    public static boolean needsDeinterlace(String cameraId) {
        IcType type = getIcType(cameraId);
        return type == IcType.TP9951 || type == IcType.TP2860;
    }

    public static Size getHdmiResolution() {
        String path = findHdmiResolutionPath();
        if (path == null) {
            return new Size(1920, 1080);
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line = reader.readLine();
            Log.d(TAG, "HDMI resolution raw: " + line);
            if (line != null) {
                String[] parts = line.trim().split(",");
                if (parts.length >= 5) {
                    int width = Integer.parseInt(parts[0]);
                    int height = Integer.parseInt(parts[1]);
                    int valid = Integer.parseInt(parts[4]);
                    if (valid == 1 && width > 0 && height > 0) {
                        int actualWidth = width * 2;
                        Log.d(TAG, "HDMI resolution: " + actualWidth + "x" + height);
                        return new Size(actualWidth, height);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to read HDMI resolution: " + e.getMessage());
        }
        return new Size(1920, 1080);
    }

    public static boolean isHdmiSignalValid() {
        String path = findHdmiResolutionPath();
        if (path == null) return false;

        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line = reader.readLine();
            if (line != null) {
                String[] parts = line.trim().split(",");
                if (parts.length >= 5) {
                    return Integer.parseInt(parts[4]) == 1;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to check HDMI signal: " + e.getMessage());
        }
        return false;
    }

    public static boolean setHdmiAudio(boolean enable) {
        File device = new File(HDMI_DEVICE_PATH);
        if (!device.exists()) {
            return false;
        }

        try (FileOutputStream fos = new FileOutputStream(HDMI_DEVICE_PATH)) {
            String cmd = enable ? "a" : "9";
            fos.write(cmd.getBytes());
            fos.flush();
            hdmiAudioEnabled = enable;
            Log.d(TAG, "HDMI audio cmd: " + cmd + " (enable=" + enable + ")");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to control HDMI audio: " + e.getMessage());
            return false;
        }
    }

    public static boolean isHdmiAudioEnabled() {
        return hdmiAudioEnabled;
    }

    public static void refresh() {
        cameraIcMap = null;
        cachedHdmiResolutionPath = null;
        cachedCameraIcInfoPath = null;
        // 注意：不清除 manualOverrideMap
    }

    public static String getDebugInfo() {
        return "CameraIcInfo: " + findCameraIcInfoPath() + "\n" +
                "HdmiResolution: " + findHdmiResolutionPath() + "\n" +
                "IcMap: " + getCameraIcMap() + "\n" +
                "ManualOverride: " + manualOverrideMap;
    }
}