/*
 * Copyright 2013 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.grafika;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.android.grafika.gles.GlUtil;

import java.io.IOException;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Camera preview to GLSurfaceView
 * <p>
 * TODO: add options for different display sizes, frame rates, camera selection, etc.
 */
public class LiveCameraActivity3 extends Activity implements SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = MainActivity.TAG;

    private GLSurfaceView mGLSurfaceView;

    private Camera mCamera;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, getClass().getSimpleName() + " onCreate");

        ViewGroup view= (ViewGroup)findViewById(android.R.id.content);

        mGLSurfaceView= new GLSurfaceView(this);
        mGLSurfaceView.setEGLContextClientVersion(2);
        mGLSurfaceView.setRenderer(new GLRenderer());
        mGLSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        view.addView(mGLSurfaceView);

        // show that we can overlay stuff on the camera preview
        TextView textView= new TextView(this);
        textView.setText(getClass().getSimpleName());
        textView.setTextColor(getResources().getColor(android.R.color.white));
        textView.setBackground(getResources().getDrawable(android.R.drawable.screen_background_dark_transparent));
        textView.setGravity(Gravity.CENTER_HORIZONTAL);
        textView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((ViewGroup)findViewById(android.R.id.content)).addView(textView);
    }

    @Override
    public void onResume() {
        super.onResume();
        openCamera();
    }

    @Override
    public void onPause() {
        super.onPause();
        mCamera.stopPreview();
        mCamera.release();
    }

    private void openCamera() {
        Camera.CameraInfo info = new Camera.CameraInfo();

        // Try to find a front-facing camera (e.g. for videoconferencing).
        int numCameras = Camera.getNumberOfCameras();
        for (int i = 0; i < numCameras; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                mCamera = Camera.open(i);
                break;
            }
        }
        if (mCamera == null) {
            Log.d(TAG, "No front-facing camera found; opening default");
            mCamera = Camera.open();    // opens first back-facing camera
        }
        if (mCamera == null) {
            throw new RuntimeException("Unable to open camera");
        }

        // deal with device rotation

        int imageRotation, displayRotation, deviceRotation = CameraUtils.getRotationDegrees(this);
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            imageRotation = (info.orientation + deviceRotation) % 360;
            displayRotation = (360 - imageRotation) % 360; // compensate the mirror
        } else {
            imageRotation =
                    displayRotation = (info.orientation - deviceRotation + 360) % 360;
        }

        Camera.Parameters params = mCamera.getParameters();
        Camera.Size preferredSize= params.getPreferredPreviewSizeForVideo();
        params.setPreviewSize(preferredSize.width, preferredSize.height);
        params.setRotation(imageRotation);
        mCamera.setParameters(params);
        mCamera.setDisplayOrientation(displayRotation);
    }

    private void startCamera(SurfaceTexture surfaceTexture, int width, int height) {
        Camera.Parameters params = mCamera.getParameters();

        // deal with camera sizing

        Camera.Size previewSize = params.getPreviewSize();
        int screenOrientation = CameraUtils.getScreenOrientation(this);
        if (screenOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT || screenOrientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT) {
            previewSize = mCamera.new Size(previewSize.height, previewSize.width);
        }
        float previewAspectRatio = previewSize.width / (float) previewSize.height;
        int scaledWidth = (int) Math.round(height * (double) previewAspectRatio);
        int scaledHeight = (int) Math.round(width / (double) previewAspectRatio);
        if (scaledWidth <= width) {
            width = scaledWidth;
        } else {
            height = scaledHeight;
        }

        mGLSurfaceView.setLayoutParams(new FrameLayout.LayoutParams(width, height, Gravity.CENTER));

        try {
            surfaceTexture.setOnFrameAvailableListener(this);
            mCamera.setPreviewTexture(surfaceTexture);
            mCamera.startPreview();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        Log.d(TAG, "onFrameAvailable");
        mGLSurfaceView.requestRender();
    }

    private class GLRenderer implements GLSurfaceView.Renderer {
        private final float[] mMVPMatrix = new float[16];

        GlRectangle mSquare;
        SurfaceTexture mSurfaceTexture;
        private final float[] mTransformationMatrix = new float[16];

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            Log.d(TAG, "onSurfaceCreated");
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            mSquare= new GlRectangle();
            mSurfaceTexture= new SurfaceTexture(mSquare.mTextureHandle);
        }

        @Override
        public void onSurfaceChanged(GL10 gl, final int width, final int height) {
            Log.d(TAG, "onSurfaceChanged");
            GLES20.glViewport(0, 0, width, height);

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    startCamera(mSurfaceTexture, width, height);
                }
            });
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            Log.d(TAG, "onDrawFrame");

            mSurfaceTexture.updateTexImage();
            mSurfaceTexture.getTransformMatrix(mTransformationMatrix);

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            mSquare.draw(GlUtil.IDENTITY_MATRIX, mTransformationMatrix);
        }
    }

    public static class GlRectangle {

        private final String vertexShaderCode =
                "uniform mat4 uMVPMatrix;" +
                "uniform mat4 uTexMatrix;\n" +
                "attribute vec4 aPosition;" +
                "attribute vec4 aTextureCoord;\n" +
                "varying vec2 vTextureCoord;\n" +
                "void main() {" +
                "    gl_Position = uMVPMatrix * aPosition;" +
                "    vTextureCoord = (uTexMatrix * aTextureCoord).xy;\n" +
                "}";

        private final String fragmentShaderCode =
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;" +
                "varying vec2 vTextureCoord;\n" +
                "uniform samplerExternalOES uTexture;\n" +
                "void main() {" +
                "    gl_FragColor = texture2D(uTexture, vTextureCoord);\n" +
                "}";

        private final FloatBuffer vertexBuffer;
        private final FloatBuffer textureBuffer;
        private int mProgramHandle, mPositionHandle, mTextureCoordHandle, mMVPMatrixHandle, mTexMatrixHandle;
        public int mTextureHandle;

        private static final int SIZEOF_FLOAT = Float.SIZE/8;

        static final int COORDS_PER_VERTEX = 2;
        static float vertexCoords[] = {
                -1.0f, -1.0f,   // 0 bottom left
                 1.0f, -1.0f,   // 1 bottom right
                -1.0f, 1.0f,    // 2 top left
                 1.0f, 1.0f,    // 3 top right
        };
        private final int vertexCount= vertexCoords.length / COORDS_PER_VERTEX;
        private final int vertexStride = COORDS_PER_VERTEX * SIZEOF_FLOAT;

        static final int COORDS_PER_TEXTURE = 2;
        static float textureCoords[]= {
                0.0f, 0.0f,     // 0 bottom left
                1.0f, 0.0f,     // 1 bottom right
                0.0f, 1.0f,     // 2 top left
                1.0f, 1.0f      // 3 top right
        };
        private final int textureStride = COORDS_PER_TEXTURE * SIZEOF_FLOAT;

        /**
         * Sets up the drawing object data for use in an OpenGL ES context.
         */
        public GlRectangle() {
            vertexBuffer= GlUtil.createFloatBuffer(vertexCoords);
            textureBuffer= GlUtil.createFloatBuffer(textureCoords);
            mTextureHandle = GlUtil.createTextureObject(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);

            // prepare shaders and OpenGL program
            int vertexShader = GlUtil.loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode);
            int fragmentShader = GlUtil.loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode);

            mProgramHandle = GLES20.glCreateProgram();             // create empty OpenGL Program
            GLES20.glAttachShader(mProgramHandle, vertexShader);   // add the vertex shader to program
            GLES20.glAttachShader(mProgramHandle, fragmentShader); // add the fragment shader to program
            GLES20.glLinkProgram(mProgramHandle);                  // create OpenGL program executables

            mPositionHandle = GLES20.glGetAttribLocation(mProgramHandle, "aPosition");
            GlUtil.checkLocation(mPositionHandle, "aPosition");

            mTextureCoordHandle = GLES20.glGetAttribLocation(mProgramHandle, "aTextureCoord");
            GlUtil.checkLocation(mTextureCoordHandle, "aTextureCoord");

            mTexMatrixHandle = GLES20.glGetUniformLocation(mProgramHandle, "uTexMatrix");
            GlUtil.checkGlError("glGetUniformLocation");

            mMVPMatrixHandle = GLES20.glGetUniformLocation(mProgramHandle, "uMVPMatrix");
            GlUtil.checkGlError("glGetUniformLocation");
        }

        public void draw(float[] mvpMatrix, float[] textMatrix) {
            GLES20.glUseProgram(mProgramHandle);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureHandle);

            // aPosition
            GLES20.glEnableVertexAttribArray(mPositionHandle);
            GlUtil.checkGlError("glEnableVertexAttribArray");
            GLES20.glVertexAttribPointer(
                    mPositionHandle, COORDS_PER_VERTEX,
                    GLES20.GL_FLOAT, false,
                    vertexStride, vertexBuffer);
            GlUtil.checkGlError("glVertexAttribPointer");

            // aTextureCoord
            GLES20.glEnableVertexAttribArray(mTextureCoordHandle);
            GlUtil.checkGlError("glEnableVertexAttribArray");
            GLES20.glVertexAttribPointer(
                    mTextureCoordHandle, COORDS_PER_TEXTURE,
                    GLES20.GL_FLOAT, false,
                    textureStride, textureBuffer);
            GlUtil.checkGlError("glVertexAttribPointer");

            // uTexMatrix
            GLES20.glUniformMatrix4fv(mTexMatrixHandle, 1, false, textMatrix, 0);
            GlUtil.checkGlError("glUniformMatrix4fv");

            // uMVPMatrix
            GLES20.glUniformMatrix4fv(mMVPMatrixHandle, 1, false, mvpMatrix, 0);
            GlUtil.checkGlError("glUniformMatrix4fv");

            // Draw the square
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, vertexCount);
            GlUtil.checkGlError("glDrawElements");

            // cleanup
            GLES20.glDisableVertexAttribArray(mPositionHandle);
            GLES20.glDisableVertexAttribArray(mTextureCoordHandle);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
            GLES20.glUseProgram(0);
        }

    }

}
