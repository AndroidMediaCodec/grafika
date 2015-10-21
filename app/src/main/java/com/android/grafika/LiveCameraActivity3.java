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
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.android.grafika.gles.GlUtil;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Camera preview to GLSurfaceView
 * <p>
 * TODO: add options for different display sizes, frame rates, camera selection, etc.
 */
public class LiveCameraActivity3 extends Activity implements SurfaceTexture.OnFrameAvailableListener {
    private final String TAG= getClass().getSimpleName();

    private GLSurfaceView mGLSurfaceView;
    private GLPreviewRenderer mPreviewRenderer;

    private Camera mCamera;
    private Camera.CameraInfo mCameraInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, getClass().getSimpleName() + " onCreate");

        ViewGroup view= (ViewGroup)findViewById(android.R.id.content);

        mGLSurfaceView= new GLSurfaceView(this);
        mGLSurfaceView.setEGLContextClientVersion(2);
        mPreviewRenderer= new GLPreviewRenderer();
        mGLSurfaceView.setRenderer(mPreviewRenderer);
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
        mPreviewRenderer.release();
    }

    private void openCamera() {
        mCameraInfo = new Camera.CameraInfo();

        // Try to find a front-facing camera (e.g. for videoconferencing).
        int numCameras = Camera.getNumberOfCameras();
        for (int i = 0; i < numCameras; i++) {
            Camera.getCameraInfo(i, mCameraInfo);
            if (mCameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
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
        if (mCameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            imageRotation = (mCameraInfo.orientation + deviceRotation) % 360;
            displayRotation = (360 - imageRotation) % 360; // compensate the mirror
        } else {
            imageRotation =
                    displayRotation = (mCameraInfo.orientation - deviceRotation + 360) % 360;
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

    private class GLPreviewRenderer implements GLSurfaceView.Renderer {
        private final String TAG= getClass().getSimpleName();
        private final float[] mMVPMatrix = new float[16];

        GLPreview mPreview;
        SurfaceTexture mSurfaceTexture;
        private final float[] mTransformationMatrix = new float[16];
        int _width, _height;

        AVEncoder mEncoder;
        PreviewConsumer mConsumer;

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            Log.d(TAG, "onSurfaceCreated");
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            mPreview = new GLPreview(GLPreview.FlipDirection.NONE);
            mSurfaceTexture= new SurfaceTexture(mPreview.mTextureHandle);
            mConsumer= new PreviewConsumer(getExternalCacheDir(),
                                           mCameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? GLPreview.FlipDirection.BOTH : GLPreview.FlipDirection.VERTICAL);

            try {
                mEncoder = new AVEncoder(new File(getExternalCacheDir(), "movie.mp4"),
                                         mCameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? GLPreview.FlipDirection.HORIZONTAL : GLPreview.FlipDirection.NONE);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void onSurfaceChanged(GL10 gl, final int width, final int height) {
            Log.d(TAG, "onSurfaceChanged");
            GLES20.glViewport(0, 0, width, height);
            _width= width;
            _height= height;

            if (!mConsumer.isRunning()) {
                mEncoder.start(EGL14.eglGetCurrentContext(), mPreview.mTextureHandle, width, height);
                mConsumer.start(EGL14.eglGetCurrentContext(), mPreview.mTextureHandle, width, height);
            }

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

            // update to the current incoming frame
            mSurfaceTexture.updateTexImage();
            mSurfaceTexture.getTransformMatrix(mTransformationMatrix);
            long timestamp= mSurfaceTexture.getTimestamp();

            // notify consumers of the new frame
            mEncoder.renderFrame(mTransformationMatrix, timestamp);
            mConsumer.renderFrame(mTransformationMatrix, timestamp);

            // render the frame
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            mPreview.draw(GlUtil.IDENTITY_MATRIX, mTransformationMatrix);
        }

        public void release() {
            mEncoder.stop();
            mConsumer.stop();
        }
    }

    public class PreviewConsumer {
        private final String TAG= getClass().getSimpleName();

        protected HandlerThread _worker;
        protected Handler _handler;
        protected File _outputFile;
        protected GLPreview.FlipDirection _flipDirection;

        protected EGLSurface _eglSurface;

        ByteBuffer mFrame;
        int _frameCount;
        protected int _width, _height;

        GLPreview _preview;

        protected android.opengl.EGLConfig _eglConfig;
        protected EGLDisplay _eglDisplay= EGL14.EGL_NO_DISPLAY;
        protected EGLContext _eglContext= EGL14.EGL_NO_CONTEXT;

        // @see https://www.khronos.org/registry/egl/extensions/ANDROID/EGL_ANDROID_recordable.txt
        private static final int EGL_RECORDABLE_ANDROID = 0x3142;

        private static final int MSG_START = 0;
        private static final int MSG_STOP= 1;
        private static final int MSG_RENDER_FRAME = 2;

        private static final int SIZEOF_INT = Integer.SIZE/8;

        public PreviewConsumer(File outputFile, GLPreview.FlipDirection flipDirection) {
            _outputFile= outputFile;
            _flipDirection= flipDirection;
        }

        public boolean isRunning() { return _worker != null; }

        public void start(EGLContext eglContext, int textureHandle, int width, int height) {
            _width= width;
            _height= height;
            _worker= new HandlerThread(getClass().getSimpleName() + "Worker");
            _worker.start();
            _handler= new Handler(_worker.getLooper()) {
                @Override  // runs on encoder thread
                public void handleMessage(Message inputMessage) {
                    int what = inputMessage.what;
                    Object obj = inputMessage.obj;

                    switch (what) {
                        case MSG_START:
                            onStart((EGLContext) obj, inputMessage.arg1);
                            break;
                        case MSG_RENDER_FRAME:
                            // un-bitshift the timestamp
                            long timestamp = (((long) inputMessage.arg1) << 32) |
                                    (((long) inputMessage.arg2) & 0xffffffffL);
                            onRenderFrame((float[]) obj, timestamp);
                            break;
                        case MSG_STOP:
                            onStop();
                            break;
                    }
                }
            };

            _handler.sendMessage(_handler.obtainMessage(MSG_START, textureHandle, 0, eglContext));
        }

        public void onStart(EGLContext eglContext, int textureHandle) {
            // create an EGLContext from the input context
            _eglDisplay= EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            _eglConfig= getConfig();
            _eglContext= EGL14.eglCreateContext(_eglDisplay,
                    _eglConfig, eglContext,
                    new int[] { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE },
                    0);
            if (_eglContext == null || EGL14.eglGetError() != EGL14.EGL_SUCCESS) {
                throw new RuntimeException("eglCreateContext failed");
            }

            // create an off-screen EGLSurface
            _eglSurface = EGL14.eglCreatePbufferSurface(_eglDisplay, _eglConfig,
                                            new int[]{
                                                EGL14.EGL_WIDTH, _width,
                                                EGL14.EGL_HEIGHT, _height,
                                                EGL14.EGL_TEXTURE_TARGET, EGL14.EGL_NO_TEXTURE,
                                                EGL14.EGL_TEXTURE_FORMAT, EGL14.EGL_NO_TEXTURE,
                                                EGL14.EGL_NONE
                                            },
                                            0);
            if (_eglSurface == null || EGL14.eglGetError() != EGL14.EGL_SUCCESS) {
                throw new RuntimeException("eglCreateWindowSurface failed " + Integer.toHexString(EGL14.eglGetError()));
            }
            if (!EGL14.eglMakeCurrent(_eglDisplay, _eglSurface, _eglSurface, _eglContext)) {
                throw new RuntimeException("eglMakeCurrent failed " + Integer.toHexString(EGL14.eglGetError()));
            }

            mFrame= ByteBuffer.allocateDirect(_width * _height * SIZEOF_INT);
            mFrame.order(ByteOrder.LITTLE_ENDIAN);

            _preview= new GLPreview(GLPreview.FlipDirection.BOTH, textureHandle);
        }

        public void stop() {
            try {
                _handler.sendMessage(_handler.obtainMessage(MSG_STOP));
                _worker.join();
            } catch (InterruptedException e) {
            }
            _worker= null;
        }

        protected void onStop() {
            Log.d(TAG, "onStop");
            Looper.myLooper().quit();
        }

        public void renderFrame(float[] transform, long timestamp) {
            // bitshift the timestamp so that it can fit in arg1/arg2
            _handler.sendMessage(_handler.obtainMessage(MSG_RENDER_FRAME,
                    (int) (timestamp >> 32), (int) timestamp, transform));
        }

        protected void onRenderFrame(float[] transformation, long timestamp) {
            Log.d(TAG, "onRenderFrame");

            // ignore zero timestamps, as it can really throw off the MediaCodec
            if (timestamp == 0) return;

            _preview.draw(GlUtil.IDENTITY_MATRIX, transformation);

            boolean ok= EGL14.eglSwapBuffers(_eglDisplay, _eglSurface);
            if (!ok) Log.d(TAG, "eglSwapBuffers fail: " + Integer.toHexString(EGL14.eglGetError()));

            _frameCount++;
            pullFrame();
            try {
                saveFrame(mFrame, new File(_outputFile, "IMG_" + _frameCount + ".jpg"));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        protected void pullFrame() {
            Log.d(TAG, "pullFrame");

            mFrame.rewind();
            GLES20.glReadPixels(0, 0, _width, _height,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, mFrame);
            GlUtil.checkGlError("glReadPixels");
            mFrame.rewind();
        }

        protected void saveFrame(ByteBuffer frame, File file) throws IOException {
            Log.d(TAG, "saveFrame " + file);
            BufferedOutputStream bos = null;
            try {
                bos = new BufferedOutputStream(new FileOutputStream(file));
                Bitmap bmp = Bitmap.createBitmap(_width, _height, Bitmap.Config.ARGB_8888);
                bmp.copyPixelsFromBuffer(frame);
                bmp.compress(Bitmap.CompressFormat.JPEG, 100, bos);
                bmp.recycle();
            } finally {
                if (bos != null) bos.close();
            }
        }

        private android.opengl.EGLConfig getConfig() {
            int[] attribList = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL_RECORDABLE_ANDROID, 1,
                    EGL14.EGL_NONE
            };
            android.opengl.EGLConfig[] configs = new android.opengl.EGLConfig[1];
            int[] numConfigs = new int[1];
            if (!EGL14.eglChooseConfig(_eglDisplay, attribList, 0, configs, 0, configs.length, numConfigs, 0)) {
                throw new RuntimeException("eglChooseConfig failed " + Integer.toHexString(EGL14.eglGetError()));
            }
            return configs[0];
        }
    }

    public class AVEncoder {
        private final String TAG= getClass().getSimpleName();

        protected HandlerThread _worker;
        protected Handler _handler;

        protected MediaMuxer _muxer;
        protected File _outputFile;
        protected int _videoTrackIndex;

        private static final String VIDEO_MIME_TYPE = "video/avc";    // H.264 Advanced Video Coding
        private static final int VIDEO_FRAME_RATE = 30;               // 30fps
        private static final int VIDEO_IFRAME_INTERVAL = 5;           // 5 seconds between I-frames
        private static final int VIDEO_BIT_RATE= 10000000;
        protected MediaCodec _videoCodec;
        protected android.opengl.EGLConfig _eglConfig;
        protected EGLDisplay _eglDisplay= EGL14.EGL_NO_DISPLAY;
        protected EGLContext _eglContext= EGL14.EGL_NO_CONTEXT;
        protected EGLSurface _eglSurface;
        protected int _width, _height;
        protected GLPreview.FlipDirection _flipDirection;
        GLPreview _preview;

        private static final int MSG_START_RECORDING = 0;
        private static final int MSG_STOP_RECORDING = 1;
        private static final int MSG_RENDER_FRAME = 2;

        // @see https://www.khronos.org/registry/egl/extensions/ANDROID/EGL_ANDROID_recordable.txt
        private static final int EGL_RECORDABLE_ANDROID = 0x3142;

        public AVEncoder(File outputFile, GLPreview.FlipDirection flipDirection) throws IOException {
            _outputFile= outputFile;
            _flipDirection= flipDirection;
            _muxer = new MediaMuxer(_outputFile.toString(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }

        public boolean isRunning() { return _worker != null; }

        public void start(EGLContext eglContext, int textureHandle, int width, int height) {
            _width= width;
            _height= height;
            _worker= new HandlerThread(getClass().getSimpleName() + "Worker");
            _worker.start();
            _handler= new Handler(_worker.getLooper()) {
                @Override  // runs on encoder thread
                public void handleMessage(Message inputMessage) {
                    int what = inputMessage.what;
                    Object obj = inputMessage.obj;

                    switch (what) {
                        case MSG_START_RECORDING:
                            onStartRecording((EGLContext)obj, inputMessage.arg1);
                            break;
                        case MSG_RENDER_FRAME:
                            // un-bitshift the timestamp
                            long timestamp = (((long) inputMessage.arg1) << 32) |
                                    (((long) inputMessage.arg2) & 0xffffffffL);
                            onRenderFrame((float[]) obj, timestamp);
                            break;
                        case MSG_STOP_RECORDING:
                            onStopRecording();
                            break;
                    }
                }
            };

            _handler.sendMessage(_handler.obtainMessage(MSG_START_RECORDING, textureHandle, 0, eglContext));
        }

        public void stop() {
            try {
                _handler.sendMessage(_handler.obtainMessage(MSG_STOP_RECORDING));
                _worker.join();
            } catch (InterruptedException e) {
            }
            _worker= null;
            _videoCodec.release();
        }

        public void renderFrame(float[] transform, long timestamp) {
            // bitshift the timestamp so that it can fit in arg1/arg2
            _handler.sendMessage(_handler.obtainMessage(MSG_RENDER_FRAME,
                    (int) (timestamp >> 32), (int) timestamp, transform));
        }

        private android.opengl.EGLConfig getConfig() {
            int[] attribList = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL_RECORDABLE_ANDROID, 1,
                    EGL14.EGL_NONE
            };
            android.opengl.EGLConfig[] configs = new android.opengl.EGLConfig[1];
            int[] numConfigs = new int[1];
            if (!EGL14.eglChooseConfig(_eglDisplay, attribList, 0, configs, 0, configs.length, numConfigs, 0)) {
                throw new RuntimeException("eglChooseConfig failed " + Integer.toHexString(EGL14.eglGetError()));
            }
            return configs[0];
        }

        protected MediaCodec createVideoCodec(int width, int height) {
            MediaFormat format = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, width, height);

            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BIT_RATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_IFRAME_INTERVAL);

            MediaCodec codec= MediaCodec.createEncoderByType(VIDEO_MIME_TYPE);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            return codec;
        }

        protected void flushVideoCodec(boolean drain) {
            final int TIMEOUT_USEC = 10000;
            MediaCodec.BufferInfo bufferInfo= new MediaCodec.BufferInfo();
            ByteBuffer[] encoderOutputBuffers = _videoCodec.getOutputBuffers();

            if (drain) {
                _videoCodec.signalEndOfInputStream();
            }

            boolean moreData= true;

            while (moreData) {
                int encoderStatus = _videoCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);
                if (encoderStatus < 0) {
                    switch (encoderStatus) {
                        case MediaCodec.INFO_TRY_AGAIN_LATER:
                            // if we're not draining, then stop
                            moreData= drain;
                            break;
                        case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                            // start the muxer
                            MediaFormat format = _videoCodec.getOutputFormat();
                            _videoTrackIndex = _muxer.addTrack(format);
                            _muxer.start();
                            break;
                    }
                } else {
                    try {
                        ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                        if (bufferInfo.size > 0 && (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            // adjust the ByteBuffer values to match BufferInfo (not needed?)
                            encodedData.position(bufferInfo.offset);
                            encodedData.limit(bufferInfo.offset + bufferInfo.size);
                            _muxer.writeSampleData(_videoTrackIndex, encodedData, bufferInfo);
                        }
                    } finally {
                        _videoCodec.releaseOutputBuffer(encoderStatus, false);

                        // stop when we're draining and reach EOS
                        if (drain && (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            moreData= false;
                        }
                    }
                }
            }
        }

        protected void onStartRecording(EGLContext eglContext, int textureHandle) {
            Log.d(TAG, "onStartRecording");

            // create an EGLContext from the input context
            _eglDisplay= EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            _eglConfig= getConfig();
            _eglContext= EGL14.eglCreateContext(_eglDisplay,
                    _eglConfig, eglContext,
                    new int[] { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE },
                    0);
            if (_eglContext == null || EGL14.eglGetError() != EGL14.EGL_SUCCESS) {
                throw new RuntimeException("eglCreateContext failed");
            }

            // create a video codec
            _videoCodec= createVideoCodec(_width, _height);
            Surface codecSurface= _videoCodec.createInputSurface();
            _videoCodec.start();

            // create an EGLSurface bound to the MediaCodec surface
            _eglSurface = EGL14.eglCreateWindowSurface(_eglDisplay,
                    _eglConfig, codecSurface,
                    new int[]{ EGL14.EGL_NONE }, 0);
            if (_eglSurface == null || EGL14.eglGetError() != EGL14.EGL_SUCCESS) {
                throw new RuntimeException("eglCreateWindowSurface failed");
            }
            if (!EGL14.eglMakeCurrent(_eglDisplay, _eglSurface, _eglSurface, _eglContext)) {
                throw new RuntimeException("eglMakeCurrent failed");
            }

            // create an OpenGL program for rendering
            _preview= new GLPreview(GLPreview.FlipDirection.HORIZONTAL, textureHandle);
        }

        protected void onStopRecording() {
            Log.d(TAG, "onStopRecording");

            flushVideoCodec(true);
            _muxer.stop();
            Looper.myLooper().quit();
        }

        protected void onRenderFrame(float[] transformation, long timestamp) {
            Log.d(TAG, "onRenderFrame");
            flushVideoCodec(false);

            // ignore zero timestamps, as it can really throw off the MediaCodec
            if (timestamp == 0) return;

            _preview.draw(GlUtil.IDENTITY_MATRIX, transformation);

            EGLExt.eglPresentationTimeANDROID(_eglDisplay, _eglSurface, timestamp);
            boolean ok= EGL14.eglSwapBuffers(_eglDisplay, _eglSurface);
        }
    }

    public static class GLPreview {

        public enum FlipDirection {
            NONE,
            HORIZONTAL,
            VERTICAL,
            BOTH
        }

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
                -1.0f,  1.0f,   // 2 top left
                 1.0f,  1.0f,   // 3 top right
        };
        private final int vertexCount= vertexCoords.length / COORDS_PER_VERTEX;
        private final int vertexStride = COORDS_PER_VERTEX * SIZEOF_FLOAT;

        static final int COORDS_PER_TEXTURE = 2;
        static float textureCoordsFlipNone[]= {
                0.0f, 0.0f,     // 0 bottom left
                1.0f, 0.0f,     // 1 bottom right
                0.0f, 1.0f,     // 2 top left
                1.0f, 1.0f      // 3 top right
        };
        static float textureCoordsFlipHorizontal[]= {
                1.0f, 0.0f,     // 0 bottom left
                0.0f, 0.0f,     // 1 bottom right
                1.0f, 1.0f,     // 2 top left
                0.0f, 1.0f      // 3 top right
        };
        static float textureCoordsFlipVertical[]= {
                0.0f, 1.0f,     // 0 bottom left
                1.0f, 1.0f,     // 1 bottom right
                0.0f, 0.0f,     // 2 top left
                1.0f, 0.0f      // 3 top right
        };
        static float textureCoordsFlipBoth[]= {
                1.0f, 1.0f,     // 0 bottom left
                0.0f, 1.0f,     // 1 bottom right
                1.0f, 0.0f,     // 2 top left
                0.0f, 0.0f      // 3 top right
        };

        private final int textureStride = COORDS_PER_TEXTURE * SIZEOF_FLOAT;

        /**
         * Sets up the drawing object data for use in an OpenGL ES context.
         */
        public GLPreview(FlipDirection flipDirection) {
            this(flipDirection, 0);
        }

        public GLPreview(FlipDirection flipDirection, int textureHandle) {
            switch (flipDirection) {
                case HORIZONTAL:
                    textureBuffer= GlUtil.createFloatBuffer(textureCoordsFlipHorizontal);
                    break;
                case VERTICAL:
                    textureBuffer= GlUtil.createFloatBuffer(textureCoordsFlipVertical);
                    break;
                case BOTH:
                    textureBuffer= GlUtil.createFloatBuffer(textureCoordsFlipBoth);
                    break;
                case NONE:
                default:
                    textureBuffer= GlUtil.createFloatBuffer(textureCoordsFlipNone);
                    break;
            }

            if (textureHandle > 0) {
                mTextureHandle= textureHandle;
            } else {
                mTextureHandle = GlUtil.createTextureObject(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
            }

            vertexBuffer= GlUtil.createFloatBuffer(vertexCoords);


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
