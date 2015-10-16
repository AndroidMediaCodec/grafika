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
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.TextureView;
import android.widget.FrameLayout;

import java.io.IOException;

/**
 * More or less straight out of TextureView's doc.
 * <p>
 * TODO: add options for different display sizes, frame rates, camera selection, etc.
 */
public class LiveCameraActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final String TAG = MainActivity.TAG;

    private Camera mCamera;
    private TextureView mTextureView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mTextureView = new TextureView(this);
        mTextureView.setSurfaceTextureListener(this);

        setContentView(mTextureView);
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

        mTextureView.setLayoutParams(new FrameLayout.LayoutParams(width, height, Gravity.CENTER));

        try {
            mCamera.setPreviewTexture(surface);
            mCamera.startPreview();
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
        // Invoked every time there's a new Camera preview frame
        //Log.d(TAG, "updated, ts=" + surface.getTimestamp());
    }
}
