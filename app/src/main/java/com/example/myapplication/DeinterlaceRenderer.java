package com.example.myapplication;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class DeinterlaceRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = "DeinterlaceRenderer";

    public static final int ALGO_WEAVE = 0;
    public static final int ALGO_BLEND = 1;
    public static final int ALGO_ADAPTIVE = 2;
    public static final int ALGO_ELA = 3;
    public static final int ALGO_VERT_FILTER = 4;
    public static final int ALGO_SMOOTH_BLEND = 5;
    public static final int ALGO_ADAPTIVE_SMOOTH = 6;

    private boolean needUpdateViewport = false;
    private int surfaceWidth = 0;
    private int surfaceHeight = 0;

    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
                    "attribute vec2 aTexCoord;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "void main() {\n" +
                    "    gl_Position = aPosition;\n" +
                    "    vTexCoord = aTexCoord;\n" +
                    "}\n";

    private static final String FRAGMENT_SHADER_WEAVE =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision highp float;\n" +
                    "uniform samplerExternalOES uTexture;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "uniform float uSrcHeight;\n" +
                    "uniform float uOutHeight;\n" +
                    "uniform float uOddStart;\n" +
                    "uniform float uOddLines;\n" +
                    "uniform float uEvenStart;\n" +
                    "uniform float uEvenLines;\n" +
                    "uniform int uSwapFields;\n" +
                    "uniform float uHOffset;\n" +
                    "uniform float uHScale;\n" +
                    "void main() {\n" +
                    "    float texX = uHOffset + vTexCoord.x * uHScale;\n" +
                    "    texX = clamp(texX, 0.001, 0.999);\n" +
                    "    int outLine = int(floor(vTexCoord.y * uOutHeight));\n" +
                    "    bool isOddLine = (outLine / 2 * 2 != outLine);\n" +
                    "    float fieldLine = float(outLine / 2);\n" +
                    "    float oddFieldLine = min(fieldLine, uOddLines - 1.0);\n" +
                    "    float evenFieldLine = min(fieldLine, uEvenLines - 1.0);\n" +
                    "    float oddY, evenY;\n" +
                    "    if (uSwapFields == 0) {\n" +
                    "        oddY = (uOddStart + oddFieldLine) / uSrcHeight;\n" +
                    "        evenY = (uEvenStart + evenFieldLine) / uSrcHeight;\n" +
                    "    } else {\n" +
                    "        oddY = (uEvenStart + evenFieldLine) / uSrcHeight;\n" +
                    "        evenY = (uOddStart + oddFieldLine) / uSrcHeight;\n" +
                    "    }\n" +
                    "    oddY = clamp(oddY, 0.0, 1.0);\n" +
                    "    evenY = clamp(evenY, 0.0, 1.0);\n" +
                    "    if (isOddLine) {\n" +
                    "        gl_FragColor = texture2D(uTexture, vec2(texX, evenY));\n" +
                    "    } else {\n" +
                    "        gl_FragColor = texture2D(uTexture, vec2(texX, oddY));\n" +
                    "    }\n" +
                    "}\n";

    private static final String FRAGMENT_SHADER_BLEND =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision highp float;\n" +
                    "uniform samplerExternalOES uTexture;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "uniform float uSrcHeight;\n" +
                    "uniform float uOutHeight;\n" +
                    "uniform float uOddStart;\n" +
                    "uniform float uOddLines;\n" +
                    "uniform float uEvenStart;\n" +
                    "uniform float uEvenLines;\n" +
                    "uniform int uSwapFields;\n" +
                    "uniform float uBlend;\n" +
                    "uniform float uHOffset;\n" +
                    "uniform float uHScale;\n" +
                    "void main() {\n" +
                    "    float texX = uHOffset + vTexCoord.x * uHScale;\n" +
                    "    texX = clamp(texX, 0.001, 0.999);\n" +
                    "    int outLine = int(floor(vTexCoord.y * uOutHeight));\n" +
                    "    bool isOddLine = (outLine / 2 * 2 != outLine);\n" +
                    "    float fieldLine = float(outLine / 2);\n" +
                    "    float oddFieldLine = min(fieldLine, uOddLines - 1.0);\n" +
                    "    float evenFieldLine = min(fieldLine, uEvenLines - 1.0);\n" +
                    "    float oddY, evenY;\n" +
                    "    if (uSwapFields == 0) {\n" +
                    "        oddY = (uOddStart + oddFieldLine) / uSrcHeight;\n" +
                    "        evenY = (uEvenStart + evenFieldLine) / uSrcHeight;\n" +
                    "    } else {\n" +
                    "        oddY = (uEvenStart + evenFieldLine) / uSrcHeight;\n" +
                    "        evenY = (uOddStart + oddFieldLine) / uSrcHeight;\n" +
                    "    }\n" +
                    "    oddY = clamp(oddY, 0.0, 1.0);\n" +
                    "    evenY = clamp(evenY, 0.0, 1.0);\n" +
                    "    vec4 oddColor = texture2D(uTexture, vec2(texX, oddY));\n" +
                    "    vec4 evenColor = texture2D(uTexture, vec2(texX, evenY));\n" +
                    "    float blendFactor = uBlend * 0.5;\n" +
                    "    if (isOddLine) {\n" +
                    "        gl_FragColor = mix(evenColor, oddColor, blendFactor);\n" +
                    "    } else {\n" +
                    "        gl_FragColor = mix(oddColor, evenColor, blendFactor);\n" +
                    "    }\n" +
                    "}\n";

    private static final String FRAGMENT_SHADER_ADAPTIVE =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision highp float;\n" +
                    "uniform samplerExternalOES uTexture;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "uniform float uSrcHeight;\n" +
                    "uniform float uOutHeight;\n" +
                    "uniform float uOddStart;\n" +
                    "uniform float uOddLines;\n" +
                    "uniform float uEvenStart;\n" +
                    "uniform float uEvenLines;\n" +
                    "uniform int uSwapFields;\n" +
                    "uniform float uThreshold;\n" +
                    "uniform float uHOffset;\n" +
                    "uniform float uHScale;\n" +
                    "void main() {\n" +
                    "    float texX = uHOffset + vTexCoord.x * uHScale;\n" +
                    "    texX = clamp(texX, 0.001, 0.999);\n" +
                    "    int outLine = int(floor(vTexCoord.y * uOutHeight));\n" +
                    "    bool isOddLine = (outLine / 2 * 2 != outLine);\n" +
                    "    float fieldLine = float(outLine / 2);\n" +
                    "    float oddFieldLine = min(fieldLine, uOddLines - 1.0);\n" +
                    "    float evenFieldLine = min(fieldLine, uEvenLines - 1.0);\n" +
                    "    float oddY, evenY;\n" +
                    "    if (uSwapFields == 0) {\n" +
                    "        oddY = (uOddStart + oddFieldLine) / uSrcHeight;\n" +
                    "        evenY = (uEvenStart + evenFieldLine) / uSrcHeight;\n" +
                    "    } else {\n" +
                    "        oddY = (uEvenStart + evenFieldLine) / uSrcHeight;\n" +
                    "        evenY = (uOddStart + oddFieldLine) / uSrcHeight;\n" +
                    "    }\n" +
                    "    oddY = clamp(oddY, 0.0, 1.0);\n" +
                    "    evenY = clamp(evenY, 0.0, 1.0);\n" +
                    "    vec4 oddColor = texture2D(uTexture, vec2(texX, oddY));\n" +
                    "    vec4 evenColor = texture2D(uTexture, vec2(texX, evenY));\n" +
                    "    float diff = length(oddColor.rgb - evenColor.rgb);\n" +
                    "    if (diff > uThreshold) {\n" +
                    "        gl_FragColor = mix(oddColor, evenColor, 0.5);\n" +
                    "    } else {\n" +
                    "        gl_FragColor = isOddLine ? evenColor : oddColor;\n" +
                    "    }\n" +
                    "}\n";

    private static final String FRAGMENT_SHADER_ELA =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision highp float;\n" +
                    "uniform samplerExternalOES uTexture;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "uniform float uSrcHeight;\n" +
                    "uniform float uOutHeight;\n" +
                    "uniform float uOddStart;\n" +
                    "uniform float uOddLines;\n" +
                    "uniform float uEvenStart;\n" +
                    "uniform float uEvenLines;\n" +
                    "uniform int uSwapFields;\n" +
                    "uniform float uHOffset;\n" +
                    "uniform float uHScale;\n" +
                    "void main() {\n" +
                    "    float texX = uHOffset + vTexCoord.x * uHScale;\n" +
                    "    texX = clamp(texX, 0.001, 0.999);\n" +
                    "    float texelX = 1.0 / 720.0;\n" +
                    "    int outLine = int(floor(vTexCoord.y * uOutHeight));\n" +
                    "    bool isOddLine = (outLine / 2 * 2 != outLine);\n" +
                    "    float fieldLine = float(outLine / 2);\n" +
                    "    float oddFieldLine = min(fieldLine, uOddLines - 1.0);\n" +
                    "    float fieldLineAbove = max(0.0, oddFieldLine - 1.0);\n" +
                    "    float fieldLineBelow = min(uOddLines - 1.0, oddFieldLine);\n" +
                    "    float oddY, oddYAbove, oddYBelow;\n" +
                    "    if (uSwapFields == 0) {\n" +
                    "        oddY = (uOddStart + oddFieldLine) / uSrcHeight;\n" +
                    "        oddYAbove = (uOddStart + fieldLineAbove) / uSrcHeight;\n" +
                    "        oddYBelow = (uOddStart + fieldLineBelow) / uSrcHeight;\n" +
                    "    } else {\n" +
                    "        oddY = (uEvenStart + oddFieldLine) / uSrcHeight;\n" +
                    "        oddYAbove = (uEvenStart + fieldLineAbove) / uSrcHeight;\n" +
                    "        oddYBelow = (uEvenStart + fieldLineBelow) / uSrcHeight;\n" +
                    "    }\n" +
                    "    if (!isOddLine) {\n" +
                    "        gl_FragColor = texture2D(uTexture, vec2(texX, clamp(oddY, 0.0, 1.0)));\n" +
                    "    } else {\n" +
                    "        vec4 above = texture2D(uTexture, vec2(texX, clamp(oddYAbove, 0.0, 1.0)));\n" +
                    "        vec4 below = texture2D(uTexture, vec2(texX, clamp(oddYBelow, 0.0, 1.0)));\n" +
                    "        vec4 aboveL = texture2D(uTexture, vec2(texX - texelX, clamp(oddYAbove, 0.0, 1.0)));\n" +
                    "        vec4 aboveR = texture2D(uTexture, vec2(texX + texelX, clamp(oddYAbove, 0.0, 1.0)));\n" +
                    "        vec4 belowL = texture2D(uTexture, vec2(texX - texelX, clamp(oddYBelow, 0.0, 1.0)));\n" +
                    "        vec4 belowR = texture2D(uTexture, vec2(texX + texelX, clamp(oddYBelow, 0.0, 1.0)));\n" +
                    "        float diffV = length(above.rgb - below.rgb);\n" +
                    "        float diffDL = length(aboveL.rgb - belowR.rgb);\n" +
                    "        float diffDR = length(aboveR.rgb - belowL.rgb);\n" +
                    "        if (diffV <= diffDL && diffV <= diffDR) {\n" +
                    "            gl_FragColor = mix(above, below, 0.5);\n" +
                    "        } else if (diffDL <= diffDR) {\n" +
                    "            gl_FragColor = mix(aboveL, belowR, 0.5);\n" +
                    "        } else {\n" +
                    "            gl_FragColor = mix(aboveR, belowL, 0.5);\n" +
                    "        }\n" +
                    "    }\n" +
                    "}\n";

    private static final String FRAGMENT_SHADER_VERT_FILTER =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision highp float;\n" +
                    "uniform samplerExternalOES uTexture;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "uniform float uSrcHeight;\n" +
                    "uniform float uOutHeight;\n" +
                    "uniform float uOddStart;\n" +
                    "uniform float uOddLines;\n" +
                    "uniform float uEvenStart;\n" +
                    "uniform float uEvenLines;\n" +
                    "uniform int uSwapFields;\n" +
                    "uniform float uHOffset;\n" +
                    "uniform float uHScale;\n" +
                    "void main() {\n" +
                    "    float texX = uHOffset + vTexCoord.x * uHScale;\n" +
                    "    texX = clamp(texX, 0.001, 0.999);\n" +
                    "    int outLine = int(floor(vTexCoord.y * uOutHeight));\n" +
                    "    bool isOddLine = (outLine / 2 * 2 != outLine);\n" +
                    "    float fieldLine = float(outLine / 2);\n" +
                    "    float oddFieldLine = min(fieldLine, uOddLines - 1.0);\n" +
                    "    float evenFieldLine = min(fieldLine, uEvenLines - 1.0);\n" +
                    "    float fieldLineAbove = max(0.0, fieldLine - 1.0);\n" +
                    "    float fieldLineBelow = fieldLine + 1.0;\n" +
                    "    float oddY, evenY, oddYAbove, oddYBelow, evenYAbove, evenYBelow;\n" +
                    "    if (uSwapFields == 0) {\n" +
                    "        oddY = (uOddStart + oddFieldLine) / uSrcHeight;\n" +
                    "        evenY = (uEvenStart + evenFieldLine) / uSrcHeight;\n" +
                    "        oddYAbove = (uOddStart + min(fieldLineAbove, uOddLines - 1.0)) / uSrcHeight;\n" +
                    "        oddYBelow = (uOddStart + min(fieldLineBelow, uOddLines - 1.0)) / uSrcHeight;\n" +
                    "        evenYAbove = (uEvenStart + min(fieldLineAbove, uEvenLines - 1.0)) / uSrcHeight;\n" +
                    "        evenYBelow = (uEvenStart + min(fieldLineBelow, uEvenLines - 1.0)) / uSrcHeight;\n" +
                    "    } else {\n" +
                    "        oddY = (uEvenStart + evenFieldLine) / uSrcHeight;\n" +
                    "        evenY = (uOddStart + oddFieldLine) / uSrcHeight;\n" +
                    "        oddYAbove = (uEvenStart + min(fieldLineAbove, uEvenLines - 1.0)) / uSrcHeight;\n" +
                    "        oddYBelow = (uEvenStart + min(fieldLineBelow, uEvenLines - 1.0)) / uSrcHeight;\n" +
                    "        evenYAbove = (uOddStart + min(fieldLineAbove, uOddLines - 1.0)) / uSrcHeight;\n" +
                    "        evenYBelow = (uOddStart + min(fieldLineBelow, uOddLines - 1.0)) / uSrcHeight;\n" +
                    "    }\n" +
                    "    vec4 current, above, below;\n" +
                    "    if (isOddLine) {\n" +
                    "        current = texture2D(uTexture, vec2(texX, clamp(evenY, 0.0, 1.0)));\n" +
                    "        above = texture2D(uTexture, vec2(texX, clamp(oddY, 0.0, 1.0)));\n" +
                    "        below = texture2D(uTexture, vec2(texX, clamp(oddYBelow, 0.0, 1.0)));\n" +
                    "    } else {\n" +
                    "        current = texture2D(uTexture, vec2(texX, clamp(oddY, 0.0, 1.0)));\n" +
                    "        above = texture2D(uTexture, vec2(texX, clamp(evenYAbove, 0.0, 1.0)));\n" +
                    "        below = texture2D(uTexture, vec2(texX, clamp(evenY, 0.0, 1.0)));\n" +
                    "    }\n" +
                    "    gl_FragColor = above * 0.25 + current * 0.5 + below * 0.25;\n" +
                    "}\n";

    private static final String FRAGMENT_SHADER_SMOOTH_BLEND =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision highp float;\n" +
                    "uniform samplerExternalOES uTexture;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "uniform float uSrcHeight;\n" +
                    "uniform float uOutHeight;\n" +
                    "uniform float uOddStart;\n" +
                    "uniform float uOddLines;\n" +
                    "uniform float uEvenStart;\n" +
                    "uniform float uEvenLines;\n" +
                    "uniform int uSwapFields;\n" +
                    "uniform float uHOffset;\n" +
                    "uniform float uHScale;\n" +
                    "void main() {\n" +
                    "    float texX = uHOffset + vTexCoord.x * uHScale;\n" +
                    "    texX = clamp(texX, 0.001, 0.999);\n" +
                    "    float outY = vTexCoord.y * uOutHeight;\n" +
                    "    int outLine = int(floor(outY));\n" +
                    "    float oddFieldLine = min(float(outLine) * 0.5, uOddLines - 1.0);\n" +
                    "    float evenFieldLine = min((float(outLine) - 1.0) * 0.5, uEvenLines - 1.0);\n" +
                    "    evenFieldLine = max(0.0, evenFieldLine);\n" +
                    "    float oddY, evenY;\n" +
                    "    if (uSwapFields == 0) {\n" +
                    "        oddY = (uOddStart + oddFieldLine) / uSrcHeight;\n" +
                    "        evenY = (uEvenStart + evenFieldLine) / uSrcHeight;\n" +
                    "    } else {\n" +
                    "        oddY = (uEvenStart + evenFieldLine) / uSrcHeight;\n" +
                    "        evenY = (uOddStart + oddFieldLine) / uSrcHeight;\n" +
                    "    }\n" +
                    "    oddY = clamp(oddY, 0.0, 1.0);\n" +
                    "    evenY = clamp(evenY, 0.0, 1.0);\n" +
                    "    vec4 oddColor = texture2D(uTexture, vec2(texX, oddY));\n" +
                    "    vec4 evenColor = texture2D(uTexture, vec2(texX, evenY));\n" +
                    "    float blendFactor = float(outLine - (outLine / 2) * 2) * 0.6 + 0.2;\n" +
                    "    gl_FragColor = mix(oddColor, evenColor, blendFactor);\n" +
                    "}\n";

    private static final String FRAGMENT_SHADER_ADAPTIVE_SMOOTH =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision highp float;\n" +
                    "uniform samplerExternalOES uTexture;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "uniform float uSrcHeight;\n" +
                    "uniform float uOutHeight;\n" +
                    "uniform float uOddStart;\n" +
                    "uniform float uOddLines;\n" +
                    "uniform float uEvenStart;\n" +
                    "uniform float uEvenLines;\n" +
                    "uniform int uSwapFields;\n" +
                    "uniform float uThreshold;\n" +
                    "uniform float uHOffset;\n" +
                    "uniform float uHScale;\n" +
                    "void main() {\n" +
                    "    float texX = uHOffset + vTexCoord.x * uHScale;\n" +
                    "    texX = clamp(texX, 0.001, 0.999);\n" +
                    "    int outLine = int(floor(vTexCoord.y * uOutHeight));\n" +
                    "    bool isOddLine = (outLine / 2 * 2 != outLine);\n" +
                    "    float fieldLine = float(outLine / 2);\n" +
                    "    float oddFieldLine = min(fieldLine, uOddLines - 1.0);\n" +
                    "    float evenFieldLine = min(fieldLine, uEvenLines - 1.0);\n" +
                    "    float fieldLineAbove = max(0.0, fieldLine - 1.0);\n" +
                    "    float fieldLineBelow = fieldLine + 1.0;\n" +
                    "    float oddY, evenY, oddYAbove, oddYBelow, evenYAbove, evenYBelow;\n" +
                    "    if (uSwapFields == 0) {\n" +
                    "        oddY = (uOddStart + oddFieldLine) / uSrcHeight;\n" +
                    "        evenY = (uEvenStart + evenFieldLine) / uSrcHeight;\n" +
                    "        oddYAbove = (uOddStart + min(fieldLineAbove, uOddLines - 1.0)) / uSrcHeight;\n" +
                    "        oddYBelow = (uOddStart + min(fieldLineBelow, uOddLines - 1.0)) / uSrcHeight;\n" +
                    "        evenYAbove = (uEvenStart + min(fieldLineAbove, uEvenLines - 1.0)) / uSrcHeight;\n" +
                    "        evenYBelow = (uEvenStart + min(fieldLineBelow, uEvenLines - 1.0)) / uSrcHeight;\n" +
                    "    } else {\n" +
                    "        oddY = (uEvenStart + evenFieldLine) / uSrcHeight;\n" +
                    "        evenY = (uOddStart + oddFieldLine) / uSrcHeight;\n" +
                    "        oddYAbove = (uEvenStart + min(fieldLineAbove, uEvenLines - 1.0)) / uSrcHeight;\n" +
                    "        oddYBelow = (uEvenStart + min(fieldLineBelow, uEvenLines - 1.0)) / uSrcHeight;\n" +
                    "        evenYAbove = (uOddStart + min(fieldLineAbove, uOddLines - 1.0)) / uSrcHeight;\n" +
                    "        evenYBelow = (uOddStart + min(fieldLineBelow, uOddLines - 1.0)) / uSrcHeight;\n" +
                    "    }\n" +
                    "    vec4 oddColor = texture2D(uTexture, vec2(texX, clamp(oddY, 0.0, 1.0)));\n" +
                    "    vec4 evenColor = texture2D(uTexture, vec2(texX, clamp(evenY, 0.0, 1.0)));\n" +
                    "    float diff = length(oddColor.rgb - evenColor.rgb);\n" +
                    "    if (diff > uThreshold) {\n" +
                    "        vec4 current, above, below;\n" +
                    "        if (isOddLine) {\n" +
                    "            current = evenColor;\n" +
                    "            above = oddColor;\n" +
                    "            below = texture2D(uTexture, vec2(texX, clamp(oddYBelow, 0.0, 1.0)));\n" +
                    "        } else {\n" +
                    "            current = oddColor;\n" +
                    "            above = texture2D(uTexture, vec2(texX, clamp(evenYAbove, 0.0, 1.0)));\n" +
                    "            below = evenColor;\n" +
                    "        }\n" +
                    "        gl_FragColor = above * 0.25 + current * 0.5 + below * 0.25;\n" +
                    "    } else {\n" +
                    "        float blendFactor = float(outLine - (outLine / 2) * 2) * 0.6 + 0.2;\n" +
                    "        gl_FragColor = mix(oddColor, evenColor, blendFactor);\n" +
                    "    }\n" +
                    "}\n";

    private static final String FRAGMENT_SHADER_PASSTHROUGH =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "uniform samplerExternalOES uTexture;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "uniform float uHOffset;\n" +
                    "uniform float uHScale;\n" +
                    "void main() {\n" +
                    "    float texX = uHOffset + vTexCoord.x * uHScale;\n" +
                    "    texX = clamp(texX, 0.001, 0.999);\n" +
                    "    gl_FragColor = texture2D(uTexture, vec2(texX, vTexCoord.y));\n" +
                    "}\n";

    // ====== 360°: FBO中间纹理方案 ======
    private static final String FRAGMENT_SHADER_360 =
            "precision highp float;\n" +
                    "uniform sampler2D uFboTex;\n" +
                    "uniform sampler2D uLookupTex;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "void main() {\n" +
                    "    float x = vTexCoord.x;\n" +
                    "    float y = vTexCoord.y;\n" +
                    "    // 2x2 grid: TL=ch0, TR=ch2, BL=ch3, BR=ch1\n" +
                    "    float ch;\n" +
                    "    float localX, localY;\n" +
                    "    if (x < 0.5) {\n" +
                    "        localX = x * 2.0;\n" +
                    "        if (y < 0.5) { ch = 0.0; localY = y * 2.0; }\n" +
                    "        else { ch = 3.0; localY = (y - 0.5) * 2.0; }\n" +
                    "    } else {\n" +
                    "        localX = (x - 0.5) * 2.0;\n" +
                    "        if (y < 0.5) { ch = 2.0; localY = y * 2.0; }\n" +
                    "        else { ch = 1.0; localY = (y - 0.5) * 2.0; }\n" +
                    "    }\n" +
                    "    // Lookup source row for this channel and line\n" +
                    "    float chTexY = (ch + 0.5) / 4.0;\n" +
                    "    float lineInCh = localY * 1079.0;\n" +
                    "    float lookupU = (lineInCh + 0.5) / 1080.0;\n" +
                    "    vec4 lk = texture2D(uLookupTex, vec2(lookupU, chTexY));\n" +
                    "    float srcRow = lk.r * 255.0 + lk.g * 255.0 * 256.0;\n" +
                    "    if (srcRow > 4319.0) {\n" +
                    "        gl_FragColor = vec4(0.1, 0.0, 0.0, 1.0);\n" +
                    "        return;\n" +
                    "    }\n" +
                    "    float rowNorm = (srcRow + 0.5) / 4320.0;\n" +
                    "    float colNorm = localX;\n" +
                    "    float u = 1.0 - rowNorm;\n" +
                    "    float v = 1.0 - colNorm;\n" +
                    "    gl_FragColor = texture2D(uFboTex, vec2(u, v));\n" +
                    "}\n";

    // v20: FBO恢复简单passthrough, 不含ST矩阵
    // (v18无ST和v19有ST结果一样 → 问题不在ST)
    private static final String FBO_FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision highp float;\n" +
                    "uniform samplerExternalOES uTexture;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "void main() {\n" +
                    "    gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
                    "}\n";

    private static final float[] VERTICES = {-1f,-1f, 1f,-1f, -1f,1f, 1f,1f};
    private static final float[] TEX_COORDS = {0f,1f, 1f,1f, 0f,0f, 1f,0f};

    private FloatBuffer vertexBuffer, texCoordBuffer;
    private int programHandle, textureId;
    private SurfaceTexture surfaceTexture;
    private int aPositionLoc, aTexCoordLoc, uTextureLoc;
    private int uSrcHeightLoc, uOutHeightLoc;
    private int uOddStartLoc, uOddLinesLoc, uEvenStartLoc, uEvenLinesLoc;
    private int uSwapFieldsLoc, uBlendLoc, uThresholdLoc;
    private int uHOffsetLoc, uHScaleLoc, uChToLineLoc, uSTMatrixLoc, uMapStatusLoc, uShaderVerLoc;
    private int uOesTexLoc = -1;
    private float[] stMatrix = new float[16];
    private boolean stMatrixValid = false; // ST 矩阵是否已从 SurfaceTexture 获取过

    private final Object lock = new Object();
    private boolean frameAvailable = false;
    private boolean deinterlaceEnabled = true;
    private boolean swapFields = false;
    private int algorithm = ALGO_WEAVE;
    private float blendFactor = 0.5f;
    private float motionThreshold = 0.08f;
    private boolean needRebuildProgram = false;
    private boolean whiteBackground = false;

    private float sourceHeight = 503.0f;
    private float outputHeight = 480.0f;
    private float oddStart = 0.0f;
    private float oddLines = 240.0f;
    private float evenStart = 263.0f;
    private float evenLines = 240.0f;
    private float hOffset = 0.0f;
    private float hScale = 1.0f;

    // 360 虚拟通道模式
    private boolean mode360 = false;
    // HAL 传来的通道→行偏移反查表: chToLine[channelNum] = lineOffset (0-3)
    private float[] chToLine = {0f, 1f, 2f, 3f};

    // 360 lookup 纹理: 从 HAL 映射文件构建, pixel(line,ch) = 源行号
    private int lookupTextureId = -1;
    private int uLookupTexLoc = -1;
    private ByteBuffer lookupBuffer;
    private static final String MAP_FILE_PATH = "/data/cam360/map.bin";
    private static final int TOTAL_ROWS = 4320;
    private static final int LINES_PER_CH = 1080;
    private static final int NUM_CHANNELS = 4;
    private int mapReadCount = 0; // 调试: 读文件计数
    private boolean mapReadOk = false; // MAP 是否成功读取

    // FBO for OES → GL_TEXTURE_2D conversion (360 mode)
    private int fboId = -1;
    private int fboTextureId = -1;
    private int fboProgram = -1;
    private int fboAPositionLoc, fboATexCoordLoc, fboUTextureLoc;
    private int fboUSTMatrixLoc = -1;
    private int uFboTexLoc = -1;
    // 交换FBO尺寸! 因为GPU auto-apply ST矩阵(90°旋转),
    // 4320行被映射到FBO X轴, 1920列映射到FBO Y轴
    // 所以FBO宽度必须=4320才能1:1存储行数据
    private static final int FBO_WIDTH = 4320;
    private static final int FBO_HEIGHT = 1920;

    private static final int FIXED_OUTPUT_WIDTH = 720;
    private boolean isNtscMode = true;

    private OnSurfaceReadyListener surfaceReadyListener;
    private GLSurfaceView glSurfaceView;

    public interface OnSurfaceReadyListener {
        void onSurfaceReady(SurfaceTexture surfaceTexture);
    }

    public DeinterlaceRenderer(GLSurfaceView view) { this(view, true); }

    public DeinterlaceRenderer(GLSurfaceView view, boolean ntsc) {
        this.glSurfaceView = view;
        this.isNtscMode = ntsc;
        initBuffers();
    }

    private void initBuffers() {
        vertexBuffer = ByteBuffer.allocateDirect(VERTICES.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().put(VERTICES);
        vertexBuffer.position(0);
        texCoordBuffer = ByteBuffer.allocateDirect(TEX_COORDS.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().put(TEX_COORDS);
        texCoordBuffer.position(0);
    }

    private int getFixedOutputHeight() { return isNtscMode ? 480 : 576; }

    public void setOnSurfaceReadyListener(OnSurfaceReadyListener l) { surfaceReadyListener = l; }

    public void setDeinterlaceEnabled(boolean e) {
        if (deinterlaceEnabled != e) {
            deinterlaceEnabled = e;
            needRebuildProgram = true;
            needUpdateViewport = true;
            requestRender();
        }
    }
    public boolean isDeinterlaceEnabled() { return deinterlaceEnabled; }

    public void setSwapFields(boolean s) { swapFields = s; requestRender(); }
    public boolean isSwapFields() { return swapFields; }

    public void setAlgorithm(int a) {
        if (algorithm != a) {
            algorithm = a;
            deinterlaceEnabled = true;
            needRebuildProgram = true;
            needUpdateViewport = true;
            requestRender();
        }
    }
    public int getAlgorithm() { return algorithm; }

    public void setBlendFactor(float b) { blendFactor = Math.max(0, Math.min(1, b)); requestRender(); }
    public float getBlendFactor() { return blendFactor; }

    public void setMotionThreshold(float t) { motionThreshold = Math.max(0.01f, Math.min(0.5f, t)); requestRender(); }
    public float getMotionThreshold() { return motionThreshold; }

    public void setWhiteBackground(boolean w) { whiteBackground = w; requestRender(); }
    public boolean isWhiteBackground() { return whiteBackground; }

    public void setHOffset(float offset) { hOffset = Math.max(0, Math.min(0.3f, offset)); requestRender(); }
    public float getHOffset() { return hOffset; }

    public void setHScale(float scale) { hScale = Math.max(0.7f, Math.min(1.0f, scale)); requestRender(); }
    public float getHScale() { return hScale; }

    public float getSourceHeight() { return sourceHeight; }
    public float getOutputHeight() { return outputHeight; }
    public float getOddStart() { return oddStart; }
    public float getOddLines() { return oddLines; }
    public float getEvenStart() { return evenStart; }
    public float getEvenLines() { return evenLines; }

    public void setFrameParams(float srcH, float outH, float oddS, float oddL, float evenS, float evenL) {
        sourceHeight = srcH;
        outputHeight = outH;
        oddStart = oddS;
        oddLines = oddL;
        evenStart = evenS;
        evenLines = evenL;
        Log.d(TAG, String.format("Frame: src=%.0f out=%.0f odd=%.0f+%.0f even=%.0f+%.0f",
                srcH, outH, oddS, oddL, evenS, evenL));
        requestRender();
    }

    public SurfaceTexture getSurfaceTexture() { return surfaceTexture; }

    public void setNtscMode() {
        isNtscMode = true;
        setFrameParams(503, 480, 1, 238, 263, 238);
        setHOffset(0.0f);     // 左边裁剪 0%
        setHScale(1.0f);      // 水平缩放 100%
        setSwapFields(true);  // 交换场序
        setAlgorithm(ALGO_VERT_FILTER);
        needUpdateViewport = true;
        requestRender();
    }

    public void setPalMode() {
        isNtscMode = false;  // 确保这里是 false
        setFrameParams(601, 576, 0, 288, 313, 288);
        setHOffset(0.0f);    // PAL 可能需要不同的裁剪参数
        setHScale(1.0f);
        setSwapFields(false); // PAL 可能不需要交换场序，需要测试
        setAlgorithm(ALGO_VERT_FILTER);
        needUpdateViewport = true;
        requestRender();
    }

    private void requestRender() {
        if (glSurfaceView != null) { try { glSurfaceView.requestRender(); } catch (Exception e) {} }
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        buildProgram();
        createTexture();
    }

    private void buildProgram() {
        if (programHandle != 0) GLES20.glDeleteProgram(programHandle);

        String fs;
        if (mode360) {
            fs = FRAGMENT_SHADER_360;
        } else if (!deinterlaceEnabled) {
            fs = FRAGMENT_SHADER_PASSTHROUGH;
        } else {
            switch (algorithm) {
                case ALGO_WEAVE: fs = FRAGMENT_SHADER_WEAVE; break;
                case ALGO_BLEND: fs = FRAGMENT_SHADER_BLEND; break;
                case ALGO_ADAPTIVE: fs = FRAGMENT_SHADER_ADAPTIVE; break;
                case ALGO_ELA: fs = FRAGMENT_SHADER_ELA; break;
                case ALGO_VERT_FILTER: fs = FRAGMENT_SHADER_VERT_FILTER; break;
                case ALGO_SMOOTH_BLEND: fs = FRAGMENT_SHADER_SMOOTH_BLEND; break;
                case ALGO_ADAPTIVE_SMOOTH: fs = FRAGMENT_SHADER_ADAPTIVE_SMOOTH; break;
                default: fs = FRAGMENT_SHADER_WEAVE;
            }
        }

        programHandle = createProgram(VERTEX_SHADER, fs);
        aPositionLoc = GLES20.glGetAttribLocation(programHandle, "aPosition");
        aTexCoordLoc = GLES20.glGetAttribLocation(programHandle, "aTexCoord");
        uTextureLoc = GLES20.glGetUniformLocation(programHandle, "uTexture");
        uHOffsetLoc = GLES20.glGetUniformLocation(programHandle, "uHOffset");
        uHScaleLoc = GLES20.glGetUniformLocation(programHandle, "uHScale");
        uChToLineLoc = GLES20.glGetUniformLocation(programHandle, "uChToLine");
        uLookupTexLoc = GLES20.glGetUniformLocation(programHandle, "uLookupTex");
        uSTMatrixLoc = GLES20.glGetUniformLocation(programHandle, "uSTMatrix");
        uMapStatusLoc = GLES20.glGetUniformLocation(programHandle, "uMapStatus");
        uFboTexLoc = GLES20.glGetUniformLocation(programHandle, "uFboTex");
        // v16: uOesTex removed, using test pattern FBO instead
        uShaderVerLoc = GLES20.glGetUniformLocation(programHandle, "uShaderVer");
        if (deinterlaceEnabled) {
            uSrcHeightLoc = GLES20.glGetUniformLocation(programHandle, "uSrcHeight");
            uOutHeightLoc = GLES20.glGetUniformLocation(programHandle, "uOutHeight");
            uOddStartLoc = GLES20.glGetUniformLocation(programHandle, "uOddStart");
            uOddLinesLoc = GLES20.glGetUniformLocation(programHandle, "uOddLines");
            uEvenStartLoc = GLES20.glGetUniformLocation(programHandle, "uEvenStart");
            uEvenLinesLoc = GLES20.glGetUniformLocation(programHandle, "uEvenLines");
            uSwapFieldsLoc = GLES20.glGetUniformLocation(programHandle, "uSwapFields");
            uBlendLoc = GLES20.glGetUniformLocation(programHandle, "uBlend");
            uThresholdLoc = GLES20.glGetUniformLocation(programHandle, "uThreshold");
        }

        needRebuildProgram = false;
    }

    private void createTexture() {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        textureId = tex[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        surfaceTexture = new SurfaceTexture(textureId);
        surfaceTexture.setOnFrameAvailableListener(this);

        if (surfaceReadyListener != null) {
            glSurfaceView.post(() -> surfaceReadyListener.onSurfaceReady(surfaceTexture));
        }

        createLookupTexture();
        createFbo();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        surfaceWidth = w;
        surfaceHeight = h;
        updateViewport();
    }

    private void updateViewport() {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return;
        if (mode360 || !deinterlaceEnabled) {
            GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
            return;
        }

        int fixedHeight = getFixedOutputHeight();
        float targetAspect = (float) FIXED_OUTPUT_WIDTH / fixedHeight;
        float viewAspect = (float) surfaceWidth / surfaceHeight;

        int vpWidth, vpHeight, vpX, vpY;
        if (viewAspect > targetAspect) {
            vpHeight = surfaceHeight;
            vpWidth = (int) (surfaceHeight * targetAspect);
            vpX = (surfaceWidth - vpWidth) / 2;
            vpY = 0;
        } else {
            vpWidth = surfaceWidth;
            vpHeight = (int) (surfaceWidth / targetAspect);
            vpX = 0;
            vpY = (surfaceHeight - vpHeight) / 2;
        }

        GLES20.glViewport(vpX, vpY, vpWidth, vpHeight);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (needUpdateViewport) {
            updateViewport();
            needUpdateViewport = false;
        }

        GLES20.glClearColor(whiteBackground ? 1f : 0f, whiteBackground ? 1f : 0f, whiteBackground ? 1f : 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        if (needRebuildProgram) buildProgram();

        synchronized (lock) {
            if (frameAvailable && surfaceTexture != null) {
                surfaceTexture.updateTexImage();
                surfaceTexture.getTransformMatrix(stMatrix);
                stMatrixValid = true;
                frameAvailable = false;
                // 调试: 每 100 帧打印纹理变换矩阵
                if (mapReadCount % 100 == 0) {
                    Log.d(TAG, String.format("ST matrix: [%.4f,%.4f,%.4f,%.4f] [%.4f,%.4f,%.4f,%.4f]",
                            stMatrix[0], stMatrix[1], stMatrix[4], stMatrix[5], stMatrix[8], stMatrix[9], stMatrix[12], stMatrix[13]));
                }
            }
        }

        // ===== FBO Pass 1: OES → GL_TEXTURE_2D (360 mode) =====
        if (mode360 && fboId >= 0) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId);
            GLES20.glViewport(0, 0, FBO_WIDTH, FBO_HEIGHT);
            GLES20.glUseProgram(fboProgram);
            GLES20.glEnableVertexAttribArray(fboAPositionLoc);
            GLES20.glVertexAttribPointer(fboAPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
            GLES20.glEnableVertexAttribArray(fboATexCoordLoc);
            GLES20.glVertexAttribPointer(fboATexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            // ★★★ v8关键: FBO pass用NEAREST, 防止行间混合 ★★★
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
            GLES20.glUniform1i(fboUTextureLoc, 0);
            // v20: 不设ST矩阵(shader中已移除), 测试GPU是否自动apply
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            // 恢复LINEAR (非360模式需要)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glDisableVertexAttribArray(fboAPositionLoc);
            GLES20.glDisableVertexAttribArray(fboATexCoordLoc);
            // v20: 增强FBO诊断 — 沿X轴8个点(对应不同源行区域) + Y轴4个点 + 四角
            if (mapReadCount % 200 == 0) {
                ByteBuffer px = ByteBuffer.allocateDirect(4);
                px.order(ByteOrder.nativeOrder());
                // ===== X轴采样(y=960固定): 不同x对应不同源行 =====
                // block-stacked预期: x<1080=ch0像素, 1080-2159=ch1, 2160-3239=ch2, 3240+=ch3
                // 如果行数据正确: 不同X区间应有不同画面(不同颜色)
                // 如果行数据重复: 所有X位置颜色相似
                int[] sampleX = {0, 540, 1080, 1620, 2160, 2700, 3240, 3780};
                StringBuilder sbX = new StringBuilder("v20 FBO X-scan y=960: ");
                for (int sx : sampleX) {
                    GLES20.glReadPixels(sx, 960, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px);
                    sbX.append(String.format("x%d=(%d,%d,%d) ", sx, px.get(0)&0xFF, px.get(1)&0xFF, px.get(2)&0xFF));
                    px.position(0);
                }
                Log.d(TAG, sbX.toString());
                // ===== Y轴采样(x=2160固定): 同一行的不同列 =====
                // 正确时: 不同Y应显示同一源行的不同水平位置
                int[] sampleY = {0, 480, 960, 1440, 1919};
                StringBuilder sbY = new StringBuilder("v20 FBO Y-scan x=2160: ");
                for (int sy : sampleY) {
                    GLES20.glReadPixels(2160, sy, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px);
                    sbY.append(String.format("y%d=(%d,%d,%d) ", sy, px.get(0)&0xFF, px.get(1)&0xFF, px.get(2)&0xFF));
                    px.position(0);
                }
                Log.d(TAG, sbY.toString());
                // ===== 四角采样: 确认FBO整体内容 =====
                int[][] corners = {{0,0}, {4319,0}, {0,1919}, {4319,1919}};
                String[] cNames = {"BL", "BR", "TL", "TR"};
                StringBuilder sbC = new StringBuilder("v20 FBO corners: ");
                for (int c = 0; c < 4; c++) {
                    GLES20.glReadPixels(corners[c][0], corners[c][1], 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px);
                    sbC.append(String.format("%s=(%d,%d,%d) ", cNames[c], px.get(0)&0xFF, px.get(1)&0xFF, px.get(2)&0xFF));
                    px.position(0);
                }
                Log.d(TAG, sbC.toString());
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
        }

        // ===== Pass 2: Draw to screen =====
        GLES20.glUseProgram(programHandle);

        GLES20.glEnableVertexAttribArray(aPositionLoc);
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        GLES20.glEnableVertexAttribArray(aTexCoordLoc);
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        if (mode360 && fboTextureId >= 0) {
            // 360: sample from FBO texture (GL_TEXTURE_2D, NEAREST)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId);
            if (uFboTexLoc >= 0) GLES20.glUniform1i(uFboTexLoc, 0);
        } else {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glUniform1i(uTextureLoc, 0);
        }

        if (uHOffsetLoc >= 0) GLES20.glUniform1f(uHOffsetLoc, hOffset);
        if (uHScaleLoc >= 0) GLES20.glUniform1f(uHScaleLoc, hScale);
        if (mode360) {
            readAndUpdateLookupTexture();
            if (uChToLineLoc >= 0) {
                GLES20.glUniform4f(uChToLineLoc, chToLine[0], chToLine[1], chToLine[2], chToLine[3]);
            }
            if (uSTMatrixLoc >= 0 && stMatrixValid) {
                GLES20.glUniformMatrix4fv(uSTMatrixLoc, 1, false, stMatrix, 0);
            }
            if (uMapStatusLoc >= 0) {
                GLES20.glUniform1f(uMapStatusLoc, mapReadOk ? 1.0f : 0.0f);
            }
            if (lookupTextureId >= 0 && uLookupTexLoc >= 0) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lookupTextureId);
                GLES20.glUniform1i(uLookupTexLoc, 1);
            }
            // v15: 绑定OES纹理到unit2, 直接在360 shader中采样
            if (uOesTexLoc >= 0) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
                GLES20.glUniform1i(uOesTexLoc, 2);
            }
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        }

        if (deinterlaceEnabled) {
            GLES20.glUniform1f(uSrcHeightLoc, sourceHeight);
            GLES20.glUniform1f(uOutHeightLoc, outputHeight);
            GLES20.glUniform1f(uOddStartLoc, oddStart);
            GLES20.glUniform1f(uOddLinesLoc, oddLines);
            GLES20.glUniform1f(uEvenStartLoc, evenStart);
            GLES20.glUniform1f(uEvenLinesLoc, evenLines);
            GLES20.glUniform1i(uSwapFieldsLoc, swapFields ? 1 : 0);
            if (uBlendLoc >= 0) GLES20.glUniform1f(uBlendLoc, blendFactor);
            if (uThresholdLoc >= 0) GLES20.glUniform1f(uThresholdLoc, motionThreshold);
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(aPositionLoc);
        GLES20.glDisableVertexAttribArray(aTexCoordLoc);
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        synchronized (lock) { frameAvailable = true; }
        requestRender();
    }

    private int createProgram(String vs, String fs) {
        int v = loadShader(GLES20.GL_VERTEX_SHADER, vs);
        int f = loadShader(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        return p;
    }

    private int loadShader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        return s;
    }

    // ============ 360 模式 ============
    public void set360Mode(boolean enabled) {
        if (mode360 != enabled) {
            mode360 = enabled;
            if (enabled) deinterlaceEnabled = false;
            needRebuildProgram = true;
            needUpdateViewport = true;
            Log.d(TAG, "360 mode " + (enabled ? "enabled" : "disabled"));
            requestRender();
        }
    }

    public boolean is360Mode() { return mode360; }

    /**
     * 设置通道映射表 (从 HAL system property 读取)
     * @param chMap chMap[lineOffset] = channelNumber (HAL 传来的原始顺序)
     *             例: {0,1,2,3} 表示 line0=ch0, line1=ch1, line2=ch2, line3=ch3
     */
    public void setChannelMap(int[] chMap) {
        // 反查: chToLine[channel] = lineOffset
        float[] reverse = {0f, 1f, 2f, 3f};
        if (chMap != null && chMap.length == 4) {
            for (int i = 0; i < 4; i++) {
                int ch = chMap[i] & 0x3;
                reverse[ch] = (float) i;
            }
        }
        chToLine = reverse;
        Log.d(TAG, String.format("chToLine: ch0→%.0f ch1→%.0f ch2→%.0f ch3→%.0f",
                chToLine[0], chToLine[1], chToLine[2], chToLine[3]));
        requestRender();
    }

    private void createLookupTexture() {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        lookupTextureId = tex[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lookupTextureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        lookupBuffer = ByteBuffer.allocateDirect(LINES_PER_CH * NUM_CHANNELS * 4);
        lookupBuffer.order(ByteOrder.nativeOrder());
        // 初始化为无效值 (0xFFFF = 无映射, shader 显示黑色)
        for (int ch = 0; ch < NUM_CHANNELS; ch++) {
            for (int line = 0; line < LINES_PER_CH; line++) {
                int offset = (ch * LINES_PER_CH + line) * 4;
                lookupBuffer.put(offset, (byte)0xFF);
                lookupBuffer.put(offset + 1, (byte)0xFF);
                lookupBuffer.put(offset + 2, (byte)0);
                lookupBuffer.put(offset + 3, (byte)0xFF);
            }
        }
        lookupBuffer.position(0);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                LINES_PER_CH, NUM_CHANNELS, 0, GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE, lookupBuffer);
        Log.d(TAG, "Lookup texture created: " + LINES_PER_CH + "x" + NUM_CHANNELS);
    }

    private void createFbo() {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        fboTextureId = tex[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, FBO_WIDTH, FBO_HEIGHT, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        int[] fbo = new int[1];
        GLES20.glGenFramebuffers(1, fbo, 0);
        fboId = fbo[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, fboTextureId, 0);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        int[] maxTexSize = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTexSize, 0);
        Log.d(TAG, "FBO created " + FBO_WIDTH + "x" + FBO_HEIGHT + " status: " +
                (status == GLES20.GL_FRAMEBUFFER_COMPLETE ? "OK" : "FAIL=" + status) +
                " GL_MAX_TEXTURE_SIZE=" + maxTexSize[0]);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        fboProgram = createProgram(VERTEX_SHADER, FBO_FRAGMENT_SHADER);
        fboAPositionLoc = GLES20.glGetAttribLocation(fboProgram, "aPosition");
        fboATexCoordLoc = GLES20.glGetAttribLocation(fboProgram, "aTexCoord");
        fboUTextureLoc = GLES20.glGetUniformLocation(fboProgram, "uTexture");
        fboUSTMatrixLoc = GLES20.glGetUniformLocation(fboProgram, "uSTMatrix");
        // v14: 检查shader编译/链接错误
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(fboProgram, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(fboProgram);
            Log.e(TAG, "FBO program LINK FAILED: " + log);
        }
        Log.d(TAG, "FBO program built, uTexture=" + fboUTextureLoc + " link=" + linkStatus[0]);
    }

    // ===== 调试开关: true=跳过MAP文件,用block-stacked直接映射 =====
    private static final boolean DEBUG_BLOCK_STACKED = true;  // v20诊断: block-stacked对照测试

    private void readAndUpdateLookupTexture() {
        if (lookupTextureId < 0 || lookupBuffer == null) return;

        // ===== 调试: block-stacked 直接映射, 绕过 MAP 文件 =====
        if (DEBUG_BLOCK_STACKED) {
            for (int ch = 0; ch < NUM_CHANNELS; ch++) {
                for (int line = 0; line < LINES_PER_CH; line++) {
                    int sr = ch * LINES_PER_CH + line;
                    int off = (ch * LINES_PER_CH + line) * 4;
                    lookupBuffer.put(off, (byte)(sr & 0xFF));
                    lookupBuffer.put(off + 1, (byte)((sr >> 8) & 0xFF));
                    lookupBuffer.put(off + 2, (byte)0);
                    lookupBuffer.put(off + 3, (byte)0xFF);
                }
            }
            lookupBuffer.position(0);
            // ★★★ v17修复: 切换到unit 1再绑定, 避免覆盖unit 0上的FBO纹理 ★★★
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lookupTextureId);
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0,
                    LINES_PER_CH, NUM_CHANNELS, GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE, lookupBuffer);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            mapReadOk = true;
            return;
        }

        File mapFile = new File(MAP_FILE_PATH);
        if (!mapFile.exists()) {
            if (mapReadCount == 0) Log.w(TAG, "360 MAP file not found: " + MAP_FILE_PATH);
            mapReadOk = false;
            return;
        }
        if (mapFile.length() < TOTAL_ROWS * 3) {
            if (mapReadCount == 0) Log.w(TAG, "360 MAP file too small: " + mapFile.length() + " < " + (TOTAL_ROWS * 3));
            mapReadOk = false;
            return;
        }
        try {
            FileInputStream fis = new FileInputStream(mapFile);
            byte[] raw = new byte[TOTAL_ROWS * 3]; // 4320 channel + 8640 line
            int n = fis.read(raw);
            fis.close();
            if (n < TOTAL_ROWS * 3) return;
            // 构建反向查找: rev[channel][line-1] = sourceRowIndex
            int[][] rev = new int[NUM_CHANNELS][LINES_PER_CH];
            for (int ch = 0; ch < NUM_CHANNELS; ch++)
                java.util.Arrays.fill(rev[ch], -1);
            for (int i = 0; i < TOTAL_ROWS; i++) {
                int channel = raw[i] & 0x03;
                int li = TOTAL_ROWS + i * 2;
                int line = (raw[li] & 0xFF) | ((raw[li + 1] & 0xFF) << 8);
                // LINE 范围 1-1080 (原始 de_vc 格式), 转为 0-based
                if (line >= 1 && line <= LINES_PER_CH && channel < NUM_CHANNELS) {
                    rev[channel][line - 1] = i;
                }
            }
            // 编码到 lookup buffer: R=低8位, G=高8位
            for (int ch = 0; ch < NUM_CHANNELS; ch++) {
                for (int line = 0; line < LINES_PER_CH; line++) {
                    int sr = rev[ch][line];
                    if (sr < 0) sr = 0xFFFF; // 无映射, shader 显示黑色
                    int off = (ch * LINES_PER_CH + line) * 4;
                    lookupBuffer.put(off, (byte)(sr & 0xFF));
                    lookupBuffer.put(off + 1, (byte)((sr >> 8) & 0xFF));
                    lookupBuffer.put(off + 2, (byte)0);
                    lookupBuffer.put(off + 3, (byte)0xFF);
                }
            }
            lookupBuffer.position(0);
            // ★★★ v17修复: 切换到unit 1再绑定, 避免覆盖unit 0上的FBO纹理 ★★★
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lookupTextureId);
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0,
                    LINES_PER_CH, NUM_CHANNELS, GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE, lookupBuffer);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            // ====== 调试 log: 每 100 帧打印一次 ======
            mapReadCount++;
            if (mapReadCount % 100 == 1) {
                // 1) 原始 channel 数据: 前 20 行
                StringBuilder sbCh = new StringBuilder("MAP ch[0..19]: ");
                for (int d = 0; d < 20 && d < TOTAL_ROWS; d++) {
                    sbCh.append(raw[d] & 0xFF).append(",");
                }
                Log.d(TAG, sbCh.toString());
                // 2) 原始 line 数据: 前 20 行
                StringBuilder sbLn = new StringBuilder("MAP ln[0..19]: ");
                for (int d = 0; d < 20 && d < TOTAL_ROWS; d++) {
                    int idx = TOTAL_ROWS + d * 2;
                    int ln = (raw[idx] & 0xFF) | ((raw[idx + 1] & 0xFF) << 8);
                    sbLn.append(ln).append(",");
                }
                Log.d(TAG, sbLn.toString());
                // 3) 每个通道有效映射数量
                int[] validCount = new int[NUM_CHANNELS];
                for (int ch = 0; ch < NUM_CHANNELS; ch++)
                    for (int l = 0; l < LINES_PER_CH; l++)
                        if (rev[ch][l] >= 0) validCount[ch]++;
                Log.d(TAG, String.format("MAP rev valid: ch0=%d ch1=%d ch2=%d ch3=%d (expect 1080 each)",
                        validCount[0], validCount[1], validCount[2], validCount[3]));
                // 4) 每个通道前 5 个 lookup 结果
                for (int ch = 0; ch < NUM_CHANNELS; ch++) {
                    StringBuilder sbR = new StringBuilder("MAP rev[ch" + ch + "] line0..4: ");
                    for (int l = 0; l < 5 && l < LINES_PER_CH; l++) {
                        sbR.append(rev[ch][l]).append(",");
                    }
                    Log.d(TAG, sbR.toString());
                }
            }
            mapReadOk = true;
        } catch (IOException e) {
            mapReadOk = false;
            Log.e(TAG, "360 MAP READ FAILED (SELinux?): " + e.getMessage());
        }
    }

    public void release() {
        synchronized (lock) {
            if (surfaceTexture != null) { surfaceTexture.release(); surfaceTexture = null; }
        }
    }
}