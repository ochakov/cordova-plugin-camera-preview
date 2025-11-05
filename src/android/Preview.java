package com.cordovaplugincamerapreview;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
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
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.widget.RelativeLayout;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

class Preview extends RelativeLayout {
    private final String TAG = "Preview";

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    public AutoFitTextureView mTextureView;
    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private CaptureRequest.Builder mPreviewRequestBuilder;
    public CaptureRequest mPreviewRequest;
    private Surface mPreviewSurface;
    private Size mPreviewSize;
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);
    private final Object mSessionLock = new Object(); // Lock for session access

    private String mCameraId;
    private CameraCharacteristics mCharacteristics;
    private int mSensorOrientation;
    private boolean mFlashSupported;
    private int mState = STATE_PREVIEW;

    private static final int STATE_PREVIEW = 0;
    private static final int STATE_WAITING_LOCK = 1;
    private static final int STATE_WAITING_PRECAPTURE = 2;
    private static final int STATE_WAITING_NON_PRECAPTURE = 3;
    private static final int STATE_PICTURE_TAKEN = 4;

    public interface PreviewCallback {
        void onCameraOpened();
        void onCameraError(String error);
        void onCameraDeviceOpened(); // Called before session is created
    }

    private PreviewCallback mCallback;

    Preview(Context context) {
        super(context);
        mTextureView = new AutoFitTextureView(context);
        addView(mTextureView);
        requestLayout();
    }

    public void setPreviewCallback(PreviewCallback callback) {
        mCallback = callback;
    }

    @Override
    public void setOnTouchListener(OnTouchListener listener) {
        // Set the touch listener on the texture view instead of the preview container
        // This ensures touch events on the camera preview are captured
        if (mTextureView != null) {
            mTextureView.setOnTouchListener(listener);
        }
        super.setOnTouchListener(listener);
    }

    private final TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            openCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
            Log.d(TAG, "onSurfaceTextureSizeChanged - width=" + width + ", height=" + height);
            // Recalculate camera outputs when TextureView size changes
            // This ensures correct aspect ratio when switching cameras or on initial layout
            setUpCameraOutputs(width, height);
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        }
    };

    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            mCameraDevice = cameraDevice;

            // Notify callback that camera device is opened (before session creation)
            // This allows setting up ImageReader surface and creating the session
            // The callback should call createCameraPreviewSession() with any additional surfaces
            if (mCallback != null) {
                mCallback.onCameraDeviceOpened();
            }

            // Don't create session here - let the callback do it
            // This allows the callback to add additional surfaces like ImageReader
            // If callback doesn't create session, we'll create a basic one
            // (This is handled by the callback calling createCameraPreviewSession)

            if (mCallback != null) {
                mCallback.onCameraOpened();
            }
        }

        @Override
        public void onDisconnected(CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(CameraDevice cameraDevice, int error) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
            String errorMessage = "Camera error: " + error;
            Log.e(TAG, errorMessage);
            if (mCallback != null) {
                mCallback.onCameraError(errorMessage);
            }
        }
    };

    public void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    public void stopBackgroundThread() {
        if (mBackgroundThread != null) {
            mBackgroundThread.quitSafely();
            try {
                mBackgroundThread.join();
                mBackgroundThread = null;
                mBackgroundHandler = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void openCamera(int width, int height) {
        if (!checkPermissions()) {
            return;
        }

        Log.d(TAG, "openCamera - width=" + width + ", height=" + height + ", mCameraId=" + mCameraId);

        setUpCameraOutputs(width, height);
        configureTransform(width, height);

        Activity activity = (Activity) getContext();
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);

        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            manager.openCamera(mCameraId, mStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Cannot access camera", e);
            if (mCallback != null) {
                mCallback.onCameraError("Cannot access camera: " + e.getMessage());
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }

    public void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            synchronized (mSessionLock) {
                if (null != mCaptureSession) {
                    mCaptureSession.close();
                    mCaptureSession = null;
                }
            }
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != mPreviewSurface) {
                mPreviewSurface.release();
                mPreviewSurface = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    public void setCamera(String cameraId) {
        mCameraId = cameraId;
    }

    public void setCaptureSession(CameraCaptureSession session) {
        synchronized (mSessionLock) {
            mCaptureSession = session;
        }
    }

    public void resumePreview() {
        if (mTextureView.isAvailable()) {
            openCamera(mTextureView.getWidth(), mTextureView.getHeight());
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    public void pausePreview() {
        closeCamera();
        stopBackgroundThread();
    }

    private void setUpCameraOutputs(int width, int height) {
        Activity activity = (Activity) getContext();
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);

        Log.d(TAG, "setUpCameraOutputs - Input: width=" + width + ", height=" + height + ", mCameraId=" + mCameraId);

        try {
            mCharacteristics = manager.getCameraCharacteristics(mCameraId);
            Integer facing = mCharacteristics.get(CameraCharacteristics.LENS_FACING);
            Log.d(TAG, "setUpCameraOutputs - Camera facing: " + (facing == CameraCharacteristics.LENS_FACING_FRONT ? "FRONT" : "BACK"));

            StreamConfigurationMap map = mCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                return;
            }

            // Find the rotation of the device relative to the native device orientation.
            int displayRotation = activity.getWindowManager().getDefaultDisplay().getRotation();
            mSensorOrientation = mCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            boolean swappedDimensions = false;
            switch (displayRotation) {
                case Surface.ROTATION_0:
                case Surface.ROTATION_180:
                    if (mSensorOrientation == 90 || mSensorOrientation == 270) {
                        swappedDimensions = true;
                    }
                    break;
                case Surface.ROTATION_90:
                case Surface.ROTATION_270:
                    if (mSensorOrientation == 0 || mSensorOrientation == 180) {
                        swappedDimensions = true;
                    }
                    break;
                default:
                    Log.e(TAG, "Display rotation is invalid: " + displayRotation);
            }

            int rotatedPreviewWidth = width;
            int rotatedPreviewHeight = height;
            int maxPreviewWidth = activity.getResources().getDisplayMetrics().widthPixels;
            int maxPreviewHeight = activity.getResources().getDisplayMetrics().heightPixels;

            if (swappedDimensions) {
                rotatedPreviewWidth = height;
                rotatedPreviewHeight = width;
                maxPreviewWidth = activity.getResources().getDisplayMetrics().heightPixels;
                maxPreviewHeight = activity.getResources().getDisplayMetrics().widthPixels;
            }

            if (maxPreviewWidth > 1920) {
                maxPreviewWidth = 1920;
            }

            if (maxPreviewHeight > 1080) {
                maxPreviewHeight = 1080;
            }

            Log.d(TAG, "setUpCameraOutputs - rotatedPreviewWidth=" + rotatedPreviewWidth + ", rotatedPreviewHeight=" + rotatedPreviewHeight);
            Log.d(TAG, "setUpCameraOutputs - maxPreviewWidth=" + maxPreviewWidth + ", maxPreviewHeight=" + maxPreviewHeight);
            Log.d(TAG, "setUpCameraOutputs - aspectRatio Size(" + width + ", " + height + ")");

            // Choose the optimal preview size
            mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                    rotatedPreviewWidth, rotatedPreviewHeight, maxPreviewWidth,
                    maxPreviewHeight, new Size(width, height));

            Log.d(TAG, "setUpCameraOutputs - Selected preview size: " + mPreviewSize.getWidth() + "x" + mPreviewSize.getHeight());

            // We fit the aspect ratio of TextureView to the size of preview we picked.
            int orientation = getResources().getConfiguration().orientation;

            // Always use cover mode to fill the entire preview area
            // This ensures the preview fills the specified dimensions without letterboxing
            mTextureView.setCoverMode(true);

            // Always set the aspect ratio to match camera preview
            if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                mTextureView.setAspectRatio(mPreviewSize.getWidth(), mPreviewSize.getHeight());
                Log.d(TAG, "setUpCameraOutputs - Landscape aspect ratio: " + mPreviewSize.getWidth() + "x" + mPreviewSize.getHeight() + ", coverMode=true");
            } else {
                mTextureView.setAspectRatio(mPreviewSize.getHeight(), mPreviewSize.getWidth());
                Log.d(TAG, "setUpCameraOutputs - Portrait aspect ratio: " + mPreviewSize.getHeight() + "x" + mPreviewSize.getWidth() + ", coverMode=true");
            }

            // Check if the flash is supported.
            Boolean available = mCharacteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            mFlashSupported = available == null ? false : available;

        } catch (CameraAccessException e) {
            Log.e(TAG, "Cannot access camera characteristics", e);
        } catch (NullPointerException e) {
            Log.e(TAG, "Camera2 API not supported on this device", e);
        }
    }

    private static Size chooseOptimalSize(Size[] choices, int textureViewWidth,
            int textureViewHeight, int maxWidth, int maxHeight, Size aspectRatio) {

        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        // Collect the supported resolutions that are smaller than the preview Surface
        List<Size> notBigEnough = new ArrayList<>();

        // Use a tolerance for aspect ratio matching instead of exact equality
        final double ASPECT_TOLERANCE = 0.15;
        double targetRatio = (double) aspectRatio.getWidth() / aspectRatio.getHeight();

        for (Size option : choices) {
            // Check if size is within max bounds
            if (option.getWidth() <= maxWidth && option.getHeight() <= maxHeight) {
                // Check aspect ratio with tolerance
                double ratio = (double) option.getWidth() / option.getHeight();
                boolean aspectRatioMatches = Math.abs(ratio - targetRatio) <= ASPECT_TOLERANCE;

                if (aspectRatioMatches) {
                    if (option.getWidth() >= textureViewWidth &&
                        option.getHeight() >= textureViewHeight) {
                        bigEnough.add(option);
                    } else {
                        notBigEnough.add(option);
                    }
                }
            }
        }

        // Pick the smallest of those big enough. If there is no one big enough, pick the
        // largest of those not big enough.
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else if (notBigEnough.size() > 0) {
            return Collections.max(notBigEnough, new CompareSizesByArea());
        } else {
            // If no size matches the aspect ratio, just find the best size that fits
            Log.w("CameraPreview", "No size found matching aspect ratio, finding best fit");
            bigEnough.clear();
            notBigEnough.clear();

            for (Size option : choices) {
                if (option.getWidth() <= maxWidth && option.getHeight() <= maxHeight) {
                    if (option.getWidth() >= textureViewWidth &&
                        option.getHeight() >= textureViewHeight) {
                        bigEnough.add(option);
                    } else {
                        notBigEnough.add(option);
                    }
                }
            }

            if (bigEnough.size() > 0) {
                return Collections.min(bigEnough, new CompareSizesByArea());
            } else if (notBigEnough.size() > 0) {
                return Collections.max(notBigEnough, new CompareSizesByArea());
            } else {
                Log.e("CameraPreview", "Couldn't find any suitable preview size, using first available");
                return choices[0];
            }
        }
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        Activity activity = (Activity) getContext();
        if (null == mTextureView || null == mPreviewSize || null == activity) {
            return;
        }

        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        // Ensure setTransform runs on the main UI thread
        mTextureView.post(new Runnable() {
            @Override
            public void run() {
                mTextureView.setTransform(matrix);
            }
        });
    }

    public Surface getPreviewSurface() {
        if (mPreviewSurface == null) {
            Log.w(TAG, "Preview surface not initialized yet");
        }
        return mPreviewSurface;
    }

    public boolean isTextureViewAvailable() {
        return mTextureView != null && mTextureView.isAvailable();
    }

    public Bitmap getPreviewBitmap() {
        if (isTextureViewAvailable()) {
            return mTextureView.getBitmap();
        }
        Log.w(TAG, "TextureView not available for bitmap capture");
        return null;
    }

    private void createCameraPreviewSession() {
        createCameraPreviewSession(null);
    }

    public void createCameraPreviewSession(List<Surface> additionalSurfaces) {
        try {
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            // This is the output Surface we need to start preview.
            mPreviewSurface = new Surface(texture);

            // We set up a CaptureRequest.Builder with the output Surface.
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(mPreviewSurface);

            // Build the list of surfaces (preview + any additional surfaces like ImageReader)
            List<Surface> surfaces = new ArrayList<>();
            surfaces.add(mPreviewSurface);
            if (additionalSurfaces != null) {
                surfaces.addAll(additionalSurfaces);
            }

            // Here, we create a CameraCaptureSession for camera preview.
            mCameraDevice.createCaptureSession(surfaces,
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(CameraCaptureSession cameraCaptureSession) {
                            // The camera is already closed
                            if (null == mCameraDevice) {
                                return;
                            }

                            synchronized (mSessionLock) {
                                // When the session is ready, we start displaying the preview.
                                mCaptureSession = cameraCaptureSession;
                                try {
                                    // Auto focus should be continuous for camera preview.
                                    mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

                                    // Finally, we start displaying the camera preview.
                                    mPreviewRequest = mPreviewRequestBuilder.build();
                                    mCaptureSession.setRepeatingRequest(mPreviewRequest,
                                            null, mBackgroundHandler);

                                    // Reconfigure transform after session is configured to ensure correct aspect ratio
                                    // This is important when switching cameras with different sensor aspect ratios
                                    configureTransform(mTextureView.getWidth(), mTextureView.getHeight());
                                    Log.d(TAG, "Transform reconfigured after camera session configured");

                                } catch (CameraAccessException e) {
                                    Log.e(TAG, "Failed to set up camera preview", e);
                                } catch (IllegalStateException e) {
                                    Log.e(TAG, "Failed to set up camera preview", e);
                                }
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
                            Log.e(TAG, "Failed to configure camera preview");
                            if (mCallback != null) {
                                mCallback.onCameraError("Failed to configure camera preview");
                            }
                        }
                    }, null
            );
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to create camera preview session", e);
        }
    }

    private boolean checkPermissions() {
        Activity activity = (Activity) getContext();
        return activity.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    public CameraDevice getCameraDevice() {
        return mCameraDevice;
    }

    public CameraCaptureSession getCaptureSession() {
        synchronized (mSessionLock) {
            return mCaptureSession;
        }
    }

    public CaptureRequest.Builder getPreviewRequestBuilder() {
        return mPreviewRequestBuilder;
    }

    public Handler getBackgroundHandler() {
        return mBackgroundHandler;
    }

    public Size getPreviewSize() {
        return mPreviewSize;
    }

    public CameraCharacteristics getCharacteristics() {
        return mCharacteristics;
    }

    public String getCameraId() {
        return mCameraId;
    }

    public int getSensorOrientation() {
        return mSensorOrientation;
    }

    public boolean isFlashSupported() {
        return mFlashSupported;
    }

    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    // AutoFitTextureView class for maintaining aspect ratio
    private static class AutoFitTextureView extends TextureView {
        private int mRatioWidth = 0;
        private int mRatioHeight = 0;
        private boolean mCoverMode = false; // true = cover (fill screen), false = fit (fit inside)

        public AutoFitTextureView(Context context) {
            super(context);
        }

        public void setAspectRatio(int width, int height) {
            if (width < 0 || height < 0) {
                throw new IllegalArgumentException("Size cannot be negative.");
            }
            mRatioWidth = width;
            mRatioHeight = height;

            // Ensure requestLayout() runs on the main UI thread
            post(new Runnable() {
                @Override
                public void run() {
                    requestLayout();
                }
            });
        }

        public void setCoverMode(boolean coverMode) {
            mCoverMode = coverMode;
            requestLayout();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = MeasureSpec.getSize(heightMeasureSpec);
            if (0 == mRatioWidth || 0 == mRatioHeight) {
                setMeasuredDimension(width, height);
            } else {
                if (mCoverMode) {
                    // Cover mode: fill the entire space, may crop edges
                    if (width > height * mRatioWidth / mRatioHeight) {
                        setMeasuredDimension(width, width * mRatioHeight / mRatioWidth);
                    } else {
                        setMeasuredDimension(height * mRatioWidth / mRatioHeight, height);
                    }
                } else {
                    // Fit mode: fit inside the space, may have letterboxing
                    if (width < height * mRatioWidth / mRatioHeight) {
                        setMeasuredDimension(width, width * mRatioHeight / mRatioWidth);
                    } else {
                        setMeasuredDimension(height * mRatioWidth / mRatioHeight, height);
                    }
                }
            }
        }
    }
}
