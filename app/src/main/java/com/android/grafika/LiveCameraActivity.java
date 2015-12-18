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
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import android.view.Gravity;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * More or less straight out of TextureView's doc.
 * <p>
 * TODO: add options for different display sizes, frame rates, camera selection, etc.
 */
public class LiveCameraActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final String TAG = MainActivity.TAG;

    private Camera mCamera;
    private TextureView mTextureView;
    File mOutputDir;

    Bitmap mBitmap;
    long mBitmapTimestamp;

    Object mSignaler= new Object();
    AtomicBoolean _busy= new AtomicBoolean(false);
    Thread mWorkerThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, getClass().getSimpleName() + " onCreate");

        mOutputDir= getExternalCacheDir();
        Log.d(TAG, "output dir " + mOutputDir);

        mWorkerThread= new Thread("worker") {
            @Override
            public void run() {
                while (!isFinishing()) {
                    try {
                        synchronized (mSignaler) {
                            mSignaler.wait();
                            _busy.set(true);
                            saveBitmap(mBitmap, mBitmapTimestamp);
                            _busy.set(false);
                        }
                    } catch (InterruptedException e) {
                    }
                }
                Log.d(TAG, "worker thread shutdown");
            }
        };

        mTextureView = new TextureView(this);
        mTextureView.setSurfaceTextureListener(this);

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
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
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

        int imageRotation, displayRotation, deviceRotation= CameraUtils.getRotationDegrees(this);
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            imageRotation = (info.orientation + deviceRotation) % 360;
            displayRotation = (360 - imageRotation) % 360; // compensate the mirror
        } else {
            imageRotation =
                    displayRotation = (info.orientation - deviceRotation + 360) % 360;
        }


        Camera.Parameters params= mCamera.getParameters();
        Camera.Size preferredSize= params.getPreferredPreviewSizeForVideo();
        //params.setPreviewSize(preferredSize.width, preferredSize.height);
        params.setPreviewSize(720, 480);
        params.setRotation(imageRotation);
        mCamera.setParameters(params);
        mCamera.setDisplayOrientation(displayRotation);

        // deal with camera sizing

        Camera.Size previewSize= params.getPreviewSize();
        int screenOrientation= CameraUtils.getScreenOrientation(this);
        if (screenOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT || screenOrientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT) {
            previewSize= mCamera.new Size(previewSize.height, previewSize.width);
        }
        float previewAspectRatio = previewSize.width / (float) previewSize.height;
        int scaledWidth = (int) Math.round(height * (double) previewAspectRatio);
        int scaledHeight = (int) Math.round(width / (double) previewAspectRatio);
        if (scaledWidth <= width) {
            width = scaledWidth;
        } else {
            height = scaledHeight;
        }

        mBitmap= Bitmap.createBitmap(previewSize.width, previewSize.height, Bitmap.Config.ARGB_8888);
        mTextureView.setLayoutParams(new FrameLayout.LayoutParams(width, height, Gravity.CENTER));

        try {
            mCamera.setPreviewTexture(surface);
            mCamera.startPreview();
            mWorkerThread.start();
        } catch (IOException ioe) {
            // Something bad happened
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        // Ignored, Camera does all the work for us
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        mCamera.stopPreview();
        mCamera.release();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        if (!_busy.get()) {
            synchronized (mSignaler) {
                mSignaler.notifyAll();
                mTextureView.getBitmap(mBitmap);
                mBitmapTimestamp = surface.getTimestamp();
            }
        } else {
            Log.d(TAG, "dropping frame");
        }
    }

    protected void saveBitmap(Bitmap bmp, long timestamp) {
        FileOutputStream out = null;
        try {
            File outputFile= new File(mOutputDir, Long.toString(timestamp) + ".jpg");
            Log.d(TAG, "output " + outputFile);
            out = new FileOutputStream(outputFile);
            bmp.compress(Bitmap.CompressFormat.JPEG, 100, out);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
