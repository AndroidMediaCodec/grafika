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

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * More or less straight out of android-Camera2Basic sample app.
 * <p>
 * TODO: add options for different display sizes, frame rates, camera selection, etc.
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class LiveCameraActivity4 extends Activity implements TextureView.SurfaceTextureListener, ImageReader.OnImageAvailableListener {
    private static final String TAG = MainActivity.TAG;

    private AutoFitTextureView mTextureView;
    private Surface mPreviewSurface;
    private ImageReader mImageReader;
    File mOutputDir;

    CameraDevice mCameraDevice;

    HandlerThread mWorkerThread;
    Handler mWorkerHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, getClass().getSimpleName() + " onCreate");

        mOutputDir= getExternalCacheDir();
        Log.d(TAG, "output dir " + mOutputDir);

        startWorkerThread();

        mTextureView = new AutoFitTextureView(this);
        mTextureView.setSurfaceTextureListener(this);
        mTextureView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        ((ViewGroup)findViewById(android.R.id.content)).addView(mTextureView);

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
    public void onPause() {
        closeCamera();
        joinWorkerThread();

        super.onPause();
    }

    protected void closeCamera() {
        Log.d(TAG, "closeCamera");
        if (mCameraDevice != null) {
            mCameraDevice.close();
            mCameraDevice= null;
        }

        if (mImageReader != null) {
            mImageReader.close();
            mImageReader= null;
        }
        Log.d(TAG, "closeCamera done");
    }

    void startWorkerThread() {
        mWorkerThread= new HandlerThread("worker");
        mWorkerThread.start();
        mWorkerHandler= new Handler(mWorkerThread.getLooper());
    }

    void stopWorkerThread() {
        mWorkerThread.quitSafely();
    }

    void joinWorkerThread() {
        try {
            Log.d(TAG, "waiting on worker thread");
            mWorkerThread.join();
        } catch (Exception e) {
            Log.e(TAG, e.toString());
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        mPreviewSurface= new Surface(surface);
        Size previewSize= openCamera();

        // We fit the aspect ratio of TextureView to the size of preview we picked.
        int orientation = getResources().getConfiguration().orientation;
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            mTextureView.setAspectRatio(
                    previewSize.getWidth(), previewSize.getHeight());
        } else {
            mTextureView.setAspectRatio(
                    previewSize.getHeight(), previewSize.getWidth());
        }
    }

    public static class CameraInfo {
        public String cameraId;
        public CameraCharacteristics cameraCharacteristics;
        public StreamConfigurationMap streamConfigurationMap;
    }

    protected CameraInfo getCameraInfo(CameraManager manager, int desiredFacing) throws CameraAccessException {
        CameraInfo cameraInfo= new CameraInfo();
        for (String currentCameraId : manager.getCameraIdList()) {
            cameraInfo.cameraCharacteristics = manager.getCameraCharacteristics(currentCameraId);
            Integer facing = cameraInfo.cameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing != desiredFacing) {
                continue;
            }

            cameraInfo.streamConfigurationMap = cameraInfo.cameraCharacteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (cameraInfo.streamConfigurationMap == null) {
                continue;
            }

            cameraInfo.cameraId = currentCameraId;

            break;
        }
        return cameraInfo;
    }

    protected Size openCamera() {
        try {
            CameraManager manager = (CameraManager)getSystemService(Context.CAMERA_SERVICE);
            CameraInfo cameraInfo = getCameraInfo(manager, CameraCharacteristics.LENS_FACING_FRONT);

            int[] outputFormats= cameraInfo.streamConfigurationMap.getOutputFormats();
            logOutputFormats("outputFormats", outputFormats);

            Size[] previewSizes = cameraInfo.streamConfigurationMap.getOutputSizes(SurfaceTexture.class);
            logSizes("SurfaceTexture", cameraInfo.streamConfigurationMap.isOutputSupportedFor(SurfaceTexture.class), previewSizes);
            Size[] videoSizes= cameraInfo.streamConfigurationMap.getOutputSizes(MediaCodec.class);
            logSizes("MediaCodec", cameraInfo.streamConfigurationMap.isOutputSupportedFor(MediaCodec.class), videoSizes);
            Size[] imageSizes = cameraInfo.streamConfigurationMap.getOutputSizes(ImageFormat.YUV_420_888);
            logSizes("image", cameraInfo.streamConfigurationMap.isOutputSupportedFor(ImageFormat.YUV_420_888), imageSizes);

            final float aspectRatio= 720.0f/480.0f;
            Size previewSize= chooseVideoSize(previewSizes, aspectRatio);
            Log.d(TAG, "preview " + previewSize.getWidth() + "x" + previewSize.getHeight());
            Size videoSize= chooseVideoSize(videoSizes, aspectRatio);
            Log.d(TAG, "video " + videoSize.getWidth() + "x" + videoSize.getHeight());
            Size yuvSize= chooseVideoSize(imageSizes, aspectRatio);
            Log.d(TAG, "image " + yuvSize.getWidth() + "x" + yuvSize.getHeight());

            mImageReader = ImageReader.newInstance(yuvSize.getWidth(), yuvSize.getHeight(), ImageFormat.YUV_420_888, 2);
            mImageReader.setOnImageAvailableListener(this, mWorkerHandler);

            manager.openCamera(cameraInfo.cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    Log.d(TAG, "onOpened");
                    mCameraDevice= camera;
                    createCaptureSession(camera);
                }

                @Override
                public void onClosed(CameraDevice camera) {
                    Log.d(TAG, "onClosed");
                    stopWorkerThread();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    Log.d(TAG, "onDisconnected");
                    stopWorkerThread();
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    Log.d(TAG, "onError " + error);
                }
            }, mWorkerHandler);

            return previewSize;
        } catch (CameraAccessException e) {
            throw new RuntimeException(e);
        }
    }

    protected void createCaptureSession(final CameraDevice camera) {
        try {
            List<Surface> surfaces = Arrays.asList(mPreviewSurface, mImageReader.getSurface());

            camera.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    Log.d(TAG, "onConfigured");
                    createCaptureRequest(camera, session);
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    Log.d(TAG, "onConfigureFailed");
                }
            }, null);
        } catch (CameraAccessException e) {
            throw new RuntimeException(e);
        }
    }

    protected void createCaptureRequest(CameraDevice camera, CameraCaptureSession session) {
        try {
            CaptureRequest.Builder requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            requestBuilder.addTarget(mPreviewSurface);
            requestBuilder.addTarget(mImageReader.getSurface());

            session.setRepeatingRequest(requestBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureProgressed(CameraCaptureSession session,
                                                CaptureRequest request,
                                                CaptureResult partialResult) {
                    Log.d(TAG, "onCaptureProgressed ");
                }

                @Override
                public void onCaptureCompleted(CameraCaptureSession session,
                                               CaptureRequest request,
                                               TotalCaptureResult result) {
                    //Log.d(TAG, "onCaptureCompleted ");
                }
            }, mWorkerHandler);
        } catch (CameraAccessException e) {
            throw new RuntimeException(e);
        }
    }

    protected void logOutputFormats(String name, int[] outputFormats) {
        Log.d(TAG, name);
        for (int outputFormat : outputFormats) {
            Log.d(TAG, "\t 0x" + Integer.toHexString(outputFormat));
        }
    }

    protected void logSizes(String name, boolean supported, Size[] sizes) {
        Log.d(TAG, name + " " + supported);
        for (Size size : sizes) {
            Log.d(TAG, "\t " + size.getWidth() + "x" + size.getHeight());
        }
    }

    private static Size chooseVideoSize(Size[] choices, float aspectRatio) {
        for (Size size : choices) {
            if (size.getWidth() / (float)size.getHeight() == aspectRatio && size.getWidth() <= 1080) {
                return size;
            }
        }
        Log.e(TAG, "Couldn't find any suitable video size");
        return choices[choices.length - 1];
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        // Ignored, Camera does all the work for us
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    @Override
    public void onImageAvailable(ImageReader reader) {
        Image image= reader.acquireNextImage();
        //Log.d(TAG, "onImageAvailable " + image.getTimestamp());
        image.close();
    }
}
