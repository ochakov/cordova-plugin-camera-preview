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
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Camera2 preview view. Owns the CameraDevice, the active CameraCaptureSession, the repeating
 * request builder and the camera background thread. Everything that needs to change the running
 * request goes through {@link #getPreviewRequestBuilder()} + {@link #updatePreviewRequest()} so
 * that there is exactly one session and one builder in play at any time.
 */
class Preview extends FrameLayout {
    private static final String TAG = "Preview";

    private static final int MAX_PREVIEW_WIDTH = 1920;
    private static final int MAX_PREVIEW_HEIGHT = 1080;
    private static final long CAMERA_LOCK_TIMEOUT_MS = 2500;
    // Sizes whose aspect ratio differs by less than this are treated as the same ratio
    // (1920x1080 vs 1280x720); distinct sensor ratios such as 4:3 and 16:9 are 0.44 apart.
    private static final double ASPECT_RATIO_TOLERANCE = 0.02;

    public interface PreviewCallback {
        /**
         * Called on the camera thread right after the camera device has been opened and before the
         * preview session is created. Return any additional output surfaces (for example an
         * ImageReader surface) that must be part of the preview session, or null.
         */
        List<Surface> onCameraDeviceOpened();

        /**
         * Called every time a new preview request builder is created so the owner can re-apply its
         * current settings (flash, zoom, focus mode, effects, ...).
         */
        void onConfigurePreviewRequest(CaptureRequest.Builder builder);

        /** Called once the first preview session after opening the device is streaming. */
        void onCameraOpened();

        /** Called when the camera could not be opened, was disconnected, or the session failed. */
        void onCameraError(String error);
    }

    /** Listener for sessions created with {@link #startCustomSession}. */
    public interface SessionListener {
        void onReady(CameraCaptureSession session);
        void onFailed(String error);
    }

    private final AutoFitTextureView mTextureView;
    private volatile CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private CaptureRequest.Builder mPreviewRequestBuilder;
    private Surface mPreviewSurface;
    private SurfaceTexture mPreviewSurfaceTexture;
    private volatile Size mPreviewSize;
    private HandlerThread mBackgroundThread;
    private volatile Handler mBackgroundHandler;
    private final Semaphore mCameraOpenCloseLock = new Semaphore(1);
    private final Object mSessionLock = new Object();
    private volatile boolean mOpening = false;
    private int mSessionGeneration = 0;
    private boolean mNotifyOpenedOnConfigure = false;

    private volatile String mCameraId;
    private volatile CameraCharacteristics mCharacteristics;
    private int mSensorOrientation;
    private boolean mFlashSupported;
    private boolean mSwappedDimensions;
    // Container size the current outputs were chosen for (0 until setUpCameraOutputs ran).
    private int mOutputsWidth;
    private int mOutputsHeight;

    private PreviewCallback mCallback;

    Preview(Context context) {
        super(context);
        mTextureView = new AutoFitTextureView(context);
        mTextureView.setCoverMode(true);
        mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        // In cover mode the texture view is larger than this container in one dimension. Centering
        // it crops the same amount on both sides; left/top aligned it would show the top-left part
        // of the frame and cut the rest off on one side.
        addView(mTextureView, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    public void setPreviewCallback(PreviewCallback callback) {
        mCallback = callback;
    }

    @Override
    public void setOnTouchListener(OnTouchListener listener) {
        // Touch events are delivered to the texture view, which is what the user sees.
        if (mTextureView != null) {
            mTextureView.setOnTouchListener(listener);
        }
        super.setOnTouchListener(listener);
    }

    public TextureView getTextureView() {
        return mTextureView;
    }

    private final TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            Log.d(TAG, "onSurfaceTextureAvailable - width=" + width + ", height=" + height);
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
            Log.d(TAG, "onSurfaceTextureSizeChanged - width=" + width + ", height=" + height);
            // The texture view follows the aspect ratio chosen for the container (onSizeChanged);
            // here only the display transform has to follow the new texture view size.
            updateCameraOutputsIfNeeded();
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

    private static boolean sameAspectRatio(Size a, Size b) {
        double ra = (double) a.getWidth() / a.getHeight();
        double rb = (double) b.getWidth() / b.getHeight();
        return Math.abs(ra - rb) < ASPECT_RATIO_TOLERANCE;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        Log.d(TAG, "onSizeChanged - " + oldw + "x" + oldh + " -> " + w + "x" + h);
        // Called during layout; do the camera work once the pass is over.
        post(new Runnable() {
            @Override
            public void run() {
                updateCameraOutputsIfNeeded();
            }
        });
    }

    /** Re-applies the output selection and display transform after a display rotation. */
    public void onDisplayRotationChanged() {
        mOutputsWidth = 0;
        mOutputsHeight = 0;
        post(new Runnable() {
            @Override
            public void run() {
                updateCameraOutputsIfNeeded();
            }
        });
    }

    /**
     * Re-selects the preview size for the current container size while the camera is open and
     * recreates the session when the aspect ratio changed (rotation, setPreviewSize).
     */
    private void updateCameraOutputsIfNeeded() {
        if (mCameraDevice == null || mCharacteristics == null) {
            // Not open yet: openCamera() picks the outputs for the size at that time.
            return;
        }
        int width = previewAreaWidth();
        int height = previewAreaHeight();
        if (width == 0 || height == 0 || (width == mOutputsWidth && height == mOutputsHeight)) {
            return;
        }
        Size previous = mPreviewSize;
        setUpCameraOutputs(width, height);
        Size current = mPreviewSize;
        if (previous != null && current != null && !sameAspectRatio(previous, current) && getCaptureSession() != null) {
            Log.d(TAG, "Preview aspect ratio changed from " + previous + " to " + current + ", recreating session");
            recreatePreviewSession(null);
        }
        configureTransform(mTextureView.getWidth(), mTextureView.getHeight());
    }

    /**
     * Width of the area the preview has to cover: this container, as laid out by the plugin. The
     * texture view is not a usable reference because cover mode already stretched it to the aspect
     * ratio of the previous camera, which would make every choice depend on the previous one (a
     * front camera opened by switchCamera after a 16:9 back camera would be forced to 16:9 while
     * startCamera with the same front camera would pick another ratio).
     */
    private int previewAreaWidth() {
        int width = getWidth();
        return width > 0 ? width : mTextureView.getWidth();
    }

    private int previewAreaHeight() {
        int height = getHeight();
        return height > 0 ? height : mTextureView.getHeight();
    }

    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice cameraDevice) {
            Log.d(TAG, "Camera device opened: " + cameraDevice.getId());
            // Assign before releasing the lock so a concurrent closeCamera() sees the device.
            mCameraDevice = cameraDevice;
            mOpening = false;
            mCameraOpenCloseLock.release();

            List<Surface> additionalSurfaces = null;
            if (mCallback != null) {
                try {
                    additionalSurfaces = mCallback.onCameraDeviceOpened();
                } catch (Exception e) {
                    Log.e(TAG, "onCameraDeviceOpened callback failed, continuing with preview only", e);
                }
            }
            synchronized (mSessionLock) {
                mNotifyOpenedOnConfigure = true;
            }
            createCameraPreviewSession(additionalSurfaces, null);
        }

        @Override
        public void onDisconnected(CameraDevice cameraDevice) {
            Log.w(TAG, "Camera device disconnected: " + cameraDevice.getId());
            mOpening = false;
            mCameraOpenCloseLock.release();
            boolean wasCurrent = handleDeviceGone(cameraDevice);
            if (wasCurrent) {
                notifyError("Camera disconnected");
            }
        }

        @Override
        public void onError(CameraDevice cameraDevice, int error) {
            String message = "Camera error: " + describeError(error);
            Log.e(TAG, message + " (camera " + cameraDevice.getId() + ")");
            mOpening = false;
            mCameraOpenCloseLock.release();
            handleDeviceGone(cameraDevice);
            notifyError(message);
        }
    };

    /** Closes a device that reported disconnect/error. Returns true if it was the active device. */
    private boolean handleDeviceGone(CameraDevice cameraDevice) {
        boolean wasCurrent = (mCameraDevice == cameraDevice);
        if (wasCurrent) {
            synchronized (mSessionLock) {
                closeSessionLocked();
                mPreviewRequestBuilder = null;
            }
            mCameraDevice = null;
        }
        try {
            cameraDevice.close();
        } catch (Exception e) {
            Log.w(TAG, "Error closing camera device", e);
        }
        return wasCurrent;
    }

    private static String describeError(int error) {
        switch (error) {
            case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE: return "camera in use";
            case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE: return "max cameras in use";
            case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED: return "camera disabled";
            case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE: return "fatal device error";
            case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE: return "fatal service error";
            default: return String.valueOf(error);
        }
    }

    private void notifyError(String message) {
        if (mCallback != null) {
            try {
                mCallback.onCameraError(message);
            } catch (Exception e) {
                Log.e(TAG, "onCameraError callback failed", e);
            }
        }
    }

    public synchronized void startBackgroundThread() {
        if (mBackgroundThread != null && mBackgroundThread.isAlive()) {
            return;
        }
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    public synchronized void stopBackgroundThread() {
        HandlerThread thread = mBackgroundThread;
        mBackgroundThread = null;
        mBackgroundHandler = null;
        if (thread != null) {
            thread.quitSafely();
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void openCamera() {
        openCamera(previewAreaWidth(), previewAreaHeight());
    }

    /** Opens the current camera for a preview area of the given size (container size). */
    @SuppressLint("MissingPermission")
    private void openCamera(int width, int height) {
        if (!checkPermissions()) {
            notifyError("Camera permission not granted");
            return;
        }
        if (mCameraId == null) {
            notifyError("No camera selected");
            return;
        }
        if (mCameraDevice != null || mOpening) {
            Log.d(TAG, "openCamera - camera already open or opening, ignoring");
            return;
        }
        startBackgroundThread();

        Log.d(TAG, "openCamera - width=" + width + ", height=" + height + ", mCameraId=" + mCameraId);

        setUpCameraOutputs(width, height);
        if (mPreviewSize == null || mCharacteristics == null) {
            notifyError("Could not configure camera outputs for camera " + mCameraId);
            return;
        }
        configureTransform(mTextureView.getWidth(), mTextureView.getHeight());

        Activity activity = (Activity) getContext();
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);

        boolean acquired = false;
        try {
            if (!mCameraOpenCloseLock.tryAcquire(CAMERA_LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                notifyError("Time out waiting to lock camera opening");
                return;
            }
            acquired = true;
            mOpening = true;
            manager.openCamera(mCameraId, mStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Cannot access camera", e);
            mOpening = false;
            if (acquired) mCameraOpenCloseLock.release();
            notifyError("Cannot open camera " + mCameraId + ": " + e.getMessage());
        } catch (RuntimeException e) {
            // IllegalArgumentException (unknown id), SecurityException, etc.
            Log.e(TAG, "Cannot open camera", e);
            mOpening = false;
            if (acquired) mCameraOpenCloseLock.release();
            notifyError("Cannot open camera " + mCameraId + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            notifyError("Interrupted while trying to lock camera opening");
        }
    }

    public void closeCamera() {
        boolean acquired = false;
        try {
            acquired = mCameraOpenCloseLock.tryAcquire(CAMERA_LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!acquired) {
                Log.w(TAG, "closeCamera - timed out waiting for camera lock, closing anyway");
            }
            synchronized (mSessionLock) {
                closeSessionLocked();
                mPreviewRequestBuilder = null;
                mNotifyOpenedOnConfigure = false;
            }
            CameraDevice device = mCameraDevice;
            mCameraDevice = null;
            if (device != null) {
                device.close();
            }
            if (mPreviewSurface != null) {
                mPreviewSurface.release();
                mPreviewSurface = null;
                mPreviewSurfaceTexture = null;
            }
            mOpening = false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "Interrupted while trying to lock camera closing", e);
        } finally {
            if (acquired) {
                mCameraOpenCloseLock.release();
            }
        }
    }

    private void closeSessionLocked() {
        if (mCaptureSession != null) {
            try {
                mCaptureSession.close();
            } catch (Exception e) {
                Log.w(TAG, "Error closing capture session", e);
            }
            mCaptureSession = null;
        }
    }

    public void setCamera(String cameraId) {
        mCameraId = cameraId;
    }

    public void resumePreview() {
        if (mTextureView.isAvailable()) {
            openCamera();
        }
        // Otherwise onSurfaceTextureAvailable() will open the camera once the view is laid out.
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
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(mCameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                Log.e(TAG, "No stream configuration map for camera " + mCameraId);
                return;
            }
            Size[] choices = map.getOutputSizes(SurfaceTexture.class);
            if (choices == null || choices.length == 0) {
                Log.e(TAG, "No preview sizes for camera " + mCameraId);
                return;
            }

            Integer sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            mSensorOrientation = sensorOrientation == null ? 0 : sensorOrientation;
            mCharacteristics = characteristics;

            // Find the rotation of the device relative to the native device orientation.
            int displayRotation = activity.getWindowManager().getDefaultDisplay().getRotation();
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

            if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
                maxPreviewWidth = MAX_PREVIEW_WIDTH;
            }
            if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
                maxPreviewHeight = MAX_PREVIEW_HEIGHT;
            }

            Size previewSize = chooseOptimalSize(choices, rotatedPreviewWidth, rotatedPreviewHeight,
                    maxPreviewWidth, maxPreviewHeight);
            mPreviewSize = previewSize;
            mSwappedDimensions = swappedDimensions;
            mOutputsWidth = width;
            mOutputsHeight = height;

            Log.d(TAG, "setUpCameraOutputs - Selected preview size: " + previewSize.getWidth() + "x" + previewSize.getHeight()
                    + " for area " + width + "x" + height + (swappedDimensions ? " (swapped)" : ""));

            // Fit the aspect ratio of the TextureView to the preview size we picked (cover mode).
            // The frame is shown rotated by 90 degrees exactly when the sensor is rotated relative
            // to the display, which is what swappedDimensions says.
            if (swappedDimensions) {
                mTextureView.setAspectRatio(previewSize.getHeight(), previewSize.getWidth());
            } else {
                mTextureView.setAspectRatio(previewSize.getWidth(), previewSize.getHeight());
            }

            Boolean available = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            mFlashSupported = available != null && available;

        } catch (CameraAccessException e) {
            Log.e(TAG, "Cannot access camera characteristics", e);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Unknown camera id " + mCameraId, e);
        } catch (NullPointerException e) {
            Log.e(TAG, "Camera2 API not supported on this device", e);
        }
    }

    /**
     * Picks the preview size for an area of areaWidth x areaHeight (in sensor orientation). Sizes
     * are capped at maxWidth x maxHeight, the PREVIEW stream size the Camera2 API guarantees next
     * to a full size JPEG stream. Among the sizes that cover the area without upscaling, the ones
     * with the aspect ratio closest to the area are kept (smallest cover-mode crop) and the
     * smallest of them is used; when no size covers the area (full screen on most phones, since
     * the cap is below the screen size) the closest aspect ratio among all sizes wins and the
     * largest of them is used. The choice depends only on the area, never on the camera that was
     * open before.
     */
    private static Size chooseOptimalSize(Size[] choices, int areaWidth, int areaHeight, int maxWidth, int maxHeight) {
        List<Size> allowed = new ArrayList<>();
        List<Size> covering = new ArrayList<>();
        for (Size option : choices) {
            if (option.getWidth() <= maxWidth && option.getHeight() <= maxHeight) {
                allowed.add(option);
                if (option.getWidth() >= areaWidth && option.getHeight() >= areaHeight) {
                    covering.add(option);
                }
            }
        }
        if (allowed.isEmpty()) {
            Log.w(TAG, "No preview size within " + maxWidth + "x" + maxHeight + ", using the smallest available");
            return Collections.min(Arrays.asList(choices), new CompareSizesByArea());
        }

        List<Size> pool = covering.isEmpty() ? allowed : covering;
        double targetRatio = (double) areaWidth / areaHeight;
        double bestDiff = Double.MAX_VALUE;
        for (Size option : pool) {
            bestDiff = Math.min(bestDiff, Math.abs((double) option.getWidth() / option.getHeight() - targetRatio));
        }
        List<Size> closest = new ArrayList<>();
        for (Size option : pool) {
            double diff = Math.abs((double) option.getWidth() / option.getHeight() - targetRatio);
            if (diff - bestDiff <= ASPECT_RATIO_TOLERANCE) {
                closest.add(option);
            }
        }
        return covering.isEmpty()
                ? Collections.max(closest, new CompareSizesByArea())
                : Collections.min(closest, new CompareSizesByArea());
    }

    /**
     * Sets the texture view transform for the current display rotation. The camera service
     * delivers frames upright for the natural device orientation (it rotates by the sensor
     * orientation); when the display is rotated the content has to be rotated back and scaled so
     * that it keeps covering the view.
     */
    private void configureTransform(int viewWidth, int viewHeight) {
        Activity activity = (Activity) getContext();
        Size previewSize = mPreviewSize;
        if (null == mTextureView || null == previewSize || null == activity || viewWidth == 0 || viewHeight == 0) {
            return;
        }

        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        final Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();

        // Frame size as it appears in the natural device orientation.
        boolean sensorRotated = mSensorOrientation == 90 || mSensorOrientation == 270;
        int naturalWidth = sensorRotated ? previewSize.getHeight() : previewSize.getWidth();
        int naturalHeight = sensorRotated ? previewSize.getWidth() : previewSize.getHeight();

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            // Undo the stretch of the frame onto the view, then scale the rotated frame to cover it.
            RectF naturalRect = new RectF(0, 0, naturalWidth, naturalHeight);
            naturalRect.offset(centerX - naturalRect.centerX(), centerY - naturalRect.centerY());
            matrix.setRectToRect(viewRect, naturalRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewWidth / naturalHeight,
                    (float) viewHeight / naturalWidth);
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        mTextureView.post(new Runnable() {
            @Override
            public void run() {
                mTextureView.setTransform(matrix);
            }
        });
    }

    public Surface getPreviewSurface() {
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

    /** Makes sure mPreviewSurface wraps the current SurfaceTexture. Returns null if unavailable. */
    private Surface ensurePreviewSurface() {
        SurfaceTexture texture = mTextureView.getSurfaceTexture();
        Size previewSize = mPreviewSize;
        if (texture == null || previewSize == null) {
            return null;
        }
        texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        if (mPreviewSurface == null || mPreviewSurfaceTexture != texture || !mPreviewSurface.isValid()) {
            if (mPreviewSurface != null) {
                mPreviewSurface.release();
            }
            mPreviewSurface = new Surface(texture);
            mPreviewSurfaceTexture = texture;
        }
        return mPreviewSurface;
    }

    /**
     * Recreates the preview session, asking the callback for its additional surfaces again.
     * Used after a custom session (video recording) ends or when the preview size changes.
     */
    public void recreatePreviewSession(Runnable onReady) {
        List<Surface> additionalSurfaces = null;
        if (mCallback != null) {
            try {
                additionalSurfaces = mCallback.onCameraDeviceOpened();
            } catch (Exception e) {
                Log.e(TAG, "onCameraDeviceOpened callback failed, continuing with preview only", e);
            }
        }
        createCameraPreviewSession(additionalSurfaces, onReady);
    }

    /**
     * Creates (or replaces) the preview capture session with the preview surface plus the given
     * additional surfaces. {@code onReady} runs on the camera thread once the preview is streaming.
     */
    public void createCameraPreviewSession(List<Surface> additionalSurfaces, final Runnable onReady) {
        final CameraDevice device = mCameraDevice;
        if (device == null) {
            Log.w(TAG, "createCameraPreviewSession - camera device is not open");
            return;
        }
        try {
            Surface previewSurface = ensurePreviewSurface();
            if (previewSurface == null) {
                Log.w(TAG, "createCameraPreviewSession - surface texture not available");
                return;
            }

            final CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(previewSurface);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            if (mCallback != null) {
                try {
                    mCallback.onConfigurePreviewRequest(builder);
                } catch (Exception e) {
                    Log.e(TAG, "onConfigurePreviewRequest callback failed", e);
                }
            }

            List<Surface> surfaces = new ArrayList<>();
            surfaces.add(previewSurface);
            if (additionalSurfaces != null) {
                for (Surface surface : additionalSurfaces) {
                    if (surface != null && surface.isValid()) {
                        surfaces.add(surface);
                    }
                }
            }

            startSession(device, surfaces, builder, new SessionListener() {
                @Override
                public void onReady(CameraCaptureSession session) {
                    configureTransform(mTextureView.getWidth(), mTextureView.getHeight());
                    boolean notifyOpened;
                    synchronized (mSessionLock) {
                        notifyOpened = mNotifyOpenedOnConfigure;
                        mNotifyOpenedOnConfigure = false;
                    }
                    if (notifyOpened && mCallback != null) {
                        mCallback.onCameraOpened();
                    }
                    if (onReady != null) {
                        onReady.run();
                    }
                }

                @Override
                public void onFailed(String error) {
                    notifyError(error);
                }
            });
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to create camera preview session", e);
            notifyError("Failed to create camera preview session: " + e.getMessage());
        } catch (IllegalStateException e) {
            // The device was closed while we were setting up; not an error worth reporting.
            Log.w(TAG, "Camera closed while creating preview session", e);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Failed to create camera preview session", e);
            notifyError("Failed to create camera preview session: " + e.getMessage());
        }
    }

    /**
     * Creates a session with arbitrary surfaces and an arbitrary repeating request (video
     * recording). The session and builder become the active ones so that zoom/flash/focus changes
     * keep working while it runs. The preview surface must be included by the caller.
     */
    public void startCustomSession(List<Surface> surfaces, CaptureRequest.Builder builder, SessionListener listener) {
        CameraDevice device = mCameraDevice;
        if (device == null) {
            if (listener != null) listener.onFailed("Camera device is not open");
            return;
        }
        try {
            startSession(device, surfaces, builder, listener);
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            Log.e(TAG, "Failed to create custom session", e);
            if (listener != null) listener.onFailed("Failed to create camera session: " + e.getMessage());
        }
    }

    private void startSession(final CameraDevice device, List<Surface> surfaces,
            final CaptureRequest.Builder builder, final SessionListener listener) throws CameraAccessException {
        final int generation;
        synchronized (mSessionLock) {
            generation = ++mSessionGeneration;
            closeSessionLocked();
        }

        device.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(CameraCaptureSession session) {
                synchronized (mSessionLock) {
                    if (mCameraDevice != device || generation != mSessionGeneration) {
                        Log.d(TAG, "Ignoring stale capture session (generation " + generation + ")");
                        try { session.close(); } catch (Exception ignored) { }
                        return;
                    }
                    mCaptureSession = session;
                    mPreviewRequestBuilder = builder;
                }
                try {
                    session.setRepeatingRequest(builder.build(), null, mBackgroundHandler);
                } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
                    Log.e(TAG, "Failed to start repeating request", e);
                    if (listener != null) listener.onFailed("Failed to start camera preview: " + e.getMessage());
                    return;
                }
                if (listener != null) {
                    listener.onReady(session);
                }
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession session) {
                Log.e(TAG, "Failed to configure capture session");
                if (listener != null) listener.onFailed("Failed to configure camera session");
            }
        }, mBackgroundHandler);
    }

    /** Re-submits the current request builder as the repeating request. */
    public boolean updatePreviewRequest() {
        return updatePreviewRequest(null);
    }

    /** Re-submits the current request builder as the repeating request with a capture callback. */
    public boolean updatePreviewRequest(CameraCaptureSession.CaptureCallback callback) {
        CameraCaptureSession session;
        CaptureRequest.Builder builder;
        synchronized (mSessionLock) {
            session = mCaptureSession;
            builder = mPreviewRequestBuilder;
        }
        if (session == null || builder == null) {
            Log.w(TAG, "updatePreviewRequest - no active session");
            return false;
        }
        try {
            session.setRepeatingRequest(builder.build(), callback, mBackgroundHandler);
            return true;
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            Log.e(TAG, "Failed to update preview request", e);
            return false;
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
        synchronized (mSessionLock) {
            return mPreviewRequestBuilder;
        }
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

    /** TextureView that keeps the camera aspect ratio, either covering or fitting its parent. */
    private static class AutoFitTextureView extends TextureView {
        private int mRatioWidth = 0;
        private int mRatioHeight = 0;
        private boolean mCoverMode = false; // true = cover (fill, may crop), false = fit (letterbox)

        public AutoFitTextureView(Context context) {
            super(context);
        }

        public void setAspectRatio(int width, int height) {
            if (width < 0 || height < 0) {
                throw new IllegalArgumentException("Size cannot be negative.");
            }
            if (mRatioWidth == width && mRatioHeight == height) {
                return;
            }
            mRatioWidth = width;
            mRatioHeight = height;
            post(new Runnable() {
                @Override
                public void run() {
                    requestLayout();
                }
            });
        }

        public void setCoverMode(boolean coverMode) {
            mCoverMode = coverMode;
            post(new Runnable() {
                @Override
                public void run() {
                    requestLayout();
                }
            });
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
                    if (width > height * mRatioWidth / mRatioHeight) {
                        setMeasuredDimension(width, width * mRatioHeight / mRatioWidth);
                    } else {
                        setMeasuredDimension(height * mRatioWidth / mRatioHeight, height);
                    }
                } else {
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
