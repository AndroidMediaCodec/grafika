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
import android.opengl.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.TextView;

import com.android.grafika.gles.GlUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

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

        mGLSurfaceView= new GLSurfaceView(this);
        mGLSurfaceView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mGLSurfaceView.setEGLContextClientVersion(2);
        mGLSurfaceView.setRenderer(new GLRenderer());
        mGLSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

        ((ViewGroup)findViewById(android.R.id.content)).addView(mGLSurfaceView);

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
        private final float[] mProjectionMatrix = new float[16];
        private final float[] mViewMatrix = new float[16];

        GlRectangle mSquare;
        SurfaceTexture mSurfaceTexture;

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            mSquare= new GlRectangle();
        }

        @Override
        public void onSurfaceChanged(GL10 gl, final int width, final int height) {
            GLES20.glViewport(0, 0, width, height);

            float ratio = (float) width / height;
            Matrix.frustumM(mProjectionMatrix, 0, -ratio, ratio, -1, 1, 3, 7);

            mSurfaceTexture= new SurfaceTexture(mSquare.mTextureHandle);
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

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            Matrix.setLookAtM(mViewMatrix, 0, 0, 0, -3, 0f, 0f, 0f, 0f, 1.0f, 0.0f);
            Matrix.multiplyMM(mMVPMatrix, 0, mProjectionMatrix, 0, mViewMatrix, 0);
            mSquare.draw(mMVPMatrix);
        }
    }

    public static class GlRectangle {

        private final String vertexShaderCode =
                "uniform mat4 uMVPMatrix;" +
                "attribute vec4 aPosition;" +
                "void main() {" +
                "  gl_Position = uMVPMatrix * aPosition;" +
                "}";

        private final String fragmentShaderCode =
                "precision mediump float;" +
                "uniform vec4 uColor;" +
                "void main() {" +
                "  gl_FragColor = uColor;" +
                "}";

        private final FloatBuffer vertexBuffer;
        private final ShortBuffer drawListBuffer;
        private final int mProgram;
        private int mPositionHandle;
        private int mColorHandle;
        private int mMVPMatrixHandle;
        public int mTextureHandle;

        private static final int SIZEOF_FLOAT = Float.SIZE/8;
        private static final int SIZEOF_SHORT = Short.SIZE/8;

        // number of coordinates per vertex in this array
        static final int COORDS_PER_VERTEX = 3;
        static float squareCoords[] = {
                -1.0f,  1.0f, 0.0f,   // top left
                -1.0f, -1.0f, 0.0f,   // bottom left
                 1.0f, -1.0f, 0.0f,   // bottom right
                 1.0f,  1.0f, 0.0f }; // top right
        private final int vertexStride = COORDS_PER_VERTEX * SIZEOF_FLOAT;
        private final short drawOrder[] = { 0, 1, 2, 0, 2, 3 }; // order to draw vertices

        float color[] = { 0.2f, 0.709803922f, 0.898039216f, 1.0f };

        /**
         * Sets up the drawing object data for use in an OpenGL ES context.
         */
        public GlRectangle() {
            // initialize vertex byte buffer for shape coordinates
            ByteBuffer bb = ByteBuffer.allocateDirect(squareCoords.length * SIZEOF_FLOAT);
            bb.order(ByteOrder.nativeOrder());
            vertexBuffer = bb.asFloatBuffer();
            vertexBuffer.put(squareCoords);
            vertexBuffer.position(0);

            // initialize byte buffer for the draw list
            ByteBuffer dlb = ByteBuffer.allocateDirect(drawOrder.length * SIZEOF_SHORT);
            dlb.order(ByteOrder.nativeOrder());
            drawListBuffer = dlb.asShortBuffer();
            drawListBuffer.put(drawOrder);
            drawListBuffer.position(0);

            mTextureHandle = GlUtil.createTextureObject(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);

            // prepare shaders and OpenGL program
            int vertexShader = GlUtil.loadShader(
                    GLES20.GL_VERTEX_SHADER,
                    vertexShaderCode);
            int fragmentShader = GlUtil.loadShader(
                    GLES20.GL_FRAGMENT_SHADER,
                    fragmentShaderCode);

            mProgram = GLES20.glCreateProgram();             // create empty OpenGL Program
            GLES20.glAttachShader(mProgram, vertexShader);   // add the vertex shader to program
            GLES20.glAttachShader(mProgram, fragmentShader); // add the fragment shader to program
            GLES20.glLinkProgram(mProgram);                  // create OpenGL program executables
        }

        public void draw(float[] mvpMatrix) {
            GLES20.glUseProgram(mProgram);

            // aPosition
            mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
            GlUtil.checkLocation(mPositionHandle, "aPosition");
            GLES20.glEnableVertexAttribArray(mPositionHandle);
            GlUtil.checkGlError("glEnableVertexAttribArray");
            GLES20.glVertexAttribPointer(
                    mPositionHandle, COORDS_PER_VERTEX,
                    GLES20.GL_FLOAT, false,
                    vertexStride, vertexBuffer);
            GlUtil.checkGlError("glVertexAttribPointer");

            // uColor
            mColorHandle = GLES20.glGetUniformLocation(mProgram, "uColor");
            GlUtil.checkLocation(mColorHandle, "uColor");
            GLES20.glUniform4fv(mColorHandle, 1, color, 0);

            // transformation matrix
            mMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
            GlUtil.checkGlError("glGetUniformLocation");
            GLES20.glUniformMatrix4fv(mMVPMatrixHandle, 1, false, mvpMatrix, 0);
            GlUtil.checkGlError("glUniformMatrix4fv");

            // Draw the square
            GLES20.glDrawElements(
                    GLES20.GL_TRIANGLES, drawOrder.length,
                    GLES20.GL_UNSIGNED_SHORT, drawListBuffer);
            GlUtil.checkGlError("glDrawElements");

            // cleanup
            GLES20.glDisableVertexAttribArray(mPositionHandle);
            GLES20.glUseProgram(0);
        }

    }

}
