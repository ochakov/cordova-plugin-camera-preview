package com.cordovaplugincamerapreview;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
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
import android.os.Looper;
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
    // A camera that is still held by a device of this app being closed (or by a lens switch that
    // was cancelled while opening) reports "in use"; it is free again shortly after.
    private static final int MAX_OPEN_RETRIES = 10;
    private static final long OPEN_RETRY_DELAY_MS = 300;

    // One camera thread for the whole process, never stopped. Callbacks of a camera that is still
    // opening, configuring or closing when its preview goes away must still be delivered, otherwise
    // that device is never closed and keeps the camera busy until the app is killed.
    private static HandlerThread sCameraThread;
    private static Handler sCameraHandler;
    // Sizes whose aspect ratio differs by less than this are treated as the same ratio
    // (1920x1080 vs 1280x720); distinct sensor ratios such as 4:3 and 16:9 are 0.44 apart.
    private static final double ASPECT_RATIO_TOLERANCE = 0.02;
    // Preview aspect ratios every camera HAL handles well. CameraX offers only these two for the
    // same reason: other ratios (such as the 20:9 of a phone screen) and sessions whose streams
    // have different ratios are stretched instead of cropped by some HALs.
    private static final double[] STANDARD_RATIOS = {4.0 / 3.0, 16.0 / 9.0};
    // 1920x1088 and 864x480 still count as 16:9; 5:3 (1.67) and 3:2 (1.5) do not.
    private static final double STANDARD_RATIO_TOLERANCE = 0.03;

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

        /**
         * Called instead of onCameraError when the camera service rejects the camera itself (for
         * example "No camera device with ID available" for a hidden physical camera that may only be
         * used through its logical camera), so opening it again will not work either.
         */
        void onCameraUnavailable(String cameraId, String error);
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
    private volatile Handler mBackgroundHandler;
    private final Object mSessionLock = new Object();
    // Guards mCameraDevice changes and mDeviceGeneration. Every open attempt and every closeCamera()
    // starts a new generation; device callbacks of an older generation only close their device.
    private final Object mDeviceLock = new Object();
    private int mDeviceGeneration = 0;
    private volatile int mOpenRetries = 0;
    private volatile boolean mOpening = false;
    // Camera open waiting for the layout pass (see openCamera).
    private volatile Runnable mPendingOpen;
    // Buffer size the preview stream of the current (or being created) session uses.
    private volatile Size mBufferSize;
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
        mBackgroundHandler = cameraThreadHandler();
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
            // TextureView has just reset the buffer size to its own size. Put the preview size back
            // right away in case a session is being created on the camera thread.
            reapplyBufferSize(texture);
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

    private void reapplyBufferSize(SurfaceTexture texture) {
        Size size = mBufferSize;
        if (size != null && texture != null) {
            texture.setDefaultBufferSize(size.getWidth(), size.getHeight());
        }
    }

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
            recreatePreviewSessionAfterLayout();
        }
        // A session that is still being configured is checked in onPreviewStreamStarted().
        configureTransform(mTextureView.getWidth(), mTextureView.getHeight());
    }

    /**
     * Recreates the preview session on the camera thread once the pending layout pass is done, so
     * that the texture view is not resized (which resets its buffer size) while the session is
     * being created. A message posted after requestLayout() runs after the layout pass.
     */
    private void recreatePreviewSessionAfterLayout() {
        mTextureView.post(new Runnable() {
            @Override
            public void run() {
                recreatePreviewSessionOnCameraThread();
            }
        });
    }

    private void recreatePreviewSessionOnCameraThread() {
        Handler handler = mBackgroundHandler;
        if (handler == null) {
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (mCameraDevice != null) {
                    recreatePreviewSession(null);
                }
            }
        });
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

    /** Device callback of one open attempt (see mDeviceGeneration). */
    private final class DeviceStateCallback extends CameraDevice.StateCallback {
        private final int mGeneration;

        DeviceStateCallback(int generation) {
            mGeneration = generation;
        }

        @Override
        public void onOpened(CameraDevice cameraDevice) {
            boolean current;
            synchronized (mDeviceLock) {
                current = mGeneration == mDeviceGeneration && mCameraDevice == null;
                if (current) {
                    mCameraDevice = cameraDevice;
                    mOpening = false;
                }
            }
            if (!current) {
                // Closed or replaced while it was opening (for example a lens switch followed right
                // away by leaving the camera).
                Log.d(TAG, "Closing camera " + cameraDevice.getId() + ", it was closed or replaced while opening");
                closeQuietly(cameraDevice);
                return;
            }
            Log.d(TAG, "Camera device opened: " + cameraDevice.getId());

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
            int state = releaseDevice(cameraDevice, mGeneration);
            if (state == DEVICE_ACTIVE) {
                mOpening = false;
                notifyError("Camera disconnected");
            } else if (state == DEVICE_OPENING) {
                // Taken over while opening: wait for it like for a busy camera.
                handleOpenFailure(cameraDevice.getId(), "Camera disconnected while opening", true, false);
            }
        }

        @Override
        public void onError(CameraDevice cameraDevice, int error) {
            String message = "Camera error: " + describeError(error);
            Log.e(TAG, message + " (camera " + cameraDevice.getId() + ")");
            int state = releaseDevice(cameraDevice, mGeneration);
            if (state == DEVICE_ACTIVE) {
                mOpening = false;
                notifyError(message);
            } else if (state == DEVICE_OPENING) {
                boolean busy = error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE
                        || error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE;
                handleOpenFailure(cameraDevice.getId(), message, busy, false);
            }
        }
    }

    private static final int DEVICE_STALE = 0;
    private static final int DEVICE_OPENING = 1;
    private static final int DEVICE_ACTIVE = 2;

    /**
     * Closes a device that reported disconnect or error. Returns DEVICE_ACTIVE if it was the open
     * device, DEVICE_OPENING if it failed while being opened by the current attempt, otherwise
     * DEVICE_STALE (an attempt that was already closed or replaced).
     */
    private int releaseDevice(CameraDevice cameraDevice, int generation) {
        boolean active;
        boolean current;
        synchronized (mDeviceLock) {
            current = generation == mDeviceGeneration;
            active = current && mCameraDevice == cameraDevice;
            if (active) {
                mCameraDevice = null;
            }
        }
        if (active) {
            synchronized (mSessionLock) {
                closeSessionLocked();
                mPreviewRequestBuilder = null;
            }
        }
        closeQuietly(cameraDevice);
        return active ? DEVICE_ACTIVE : (current ? DEVICE_OPENING : DEVICE_STALE);
    }

    private static void closeQuietly(CameraDevice device) {
        if (device == null) {
            return;
        }
        try {
            device.close();
        } catch (Exception e) {
            Log.w(TAG, "Error closing camera device", e);
        }
    }

    private static void closeQuietly(CameraCaptureSession session) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (Exception e) {
            Log.w(TAG, "Error closing capture session", e);
        }
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

    private static synchronized Handler cameraThreadHandler() {
        if (sCameraThread == null || !sCameraThread.isAlive()) {
            sCameraThread = new HandlerThread("CameraBackground");
            sCameraThread.start();
            sCameraHandler = new Handler(sCameraThread.getLooper());
        }
        return sCameraHandler;
    }

    /** Makes sure the (shared, never stopped) camera thread is running. */
    public void startBackgroundThread() {
        mBackgroundHandler = cameraThreadHandler();
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

        // Open once the texture view has been laid out with the shape chosen above. Every resize of
        // a TextureView resets its SurfaceTexture's buffer size to the view size, and a camera that
        // opens within a frame (some front cameras open in about 10 ms) would otherwise create its
        // preview stream while that happens: the stream then gets the view size rounded to some
        // supported size (4:3 shown in a 16:9 view, i.e. stretched) or the session fails with
        // "Surface size not compatible". A message posted after requestLayout() runs after the
        // layout pass.
        mOpening = true;
        mOpenRetries = 0;
        scheduleOpen(0);
    }

    /**
     * Opens the device on the camera thread, after the pending layout pass (see openCamera) or,
     * for a retry, after delayMs. CameraManager.openCamera() blocks its thread while the camera
     * service opens the device, which takes seconds for some lenses, so it does not run on the UI
     * thread. closeCamera() cancels an open that has not started yet.
     */
    private void scheduleOpen(long delayMs) {
        final int generation;
        synchronized (mDeviceLock) {
            generation = mDeviceGeneration;
        }
        Runnable open = new Runnable() {
            @Override
            public void run() {
                if (mPendingOpen != this) {
                    return;
                }
                mPendingOpen = null;
                startBackgroundThread();
                mBackgroundHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        boolean current;
                        synchronized (mDeviceLock) {
                            current = generation == mDeviceGeneration && mOpening;
                        }
                        if (current) {
                            openCameraDevice();
                        }
                    }
                });
            }
        };
        mPendingOpen = open;
        if (delayMs > 0) {
            mTextureView.postDelayed(open, delayMs);
        } else {
            mTextureView.post(open);
        }
    }

    /** Runs on the camera thread. */
    @SuppressLint("MissingPermission")
    private void openCameraDevice() {
        final String cameraId = mCameraId;
        final int generation;
        synchronized (mDeviceLock) {
            generation = ++mDeviceGeneration;
        }
        Activity activity = (Activity) getContext();
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            Log.d(TAG, "Opening camera " + cameraId);
            manager.openCamera(cameraId, new DeviceStateCallback(generation), mBackgroundHandler);
        } catch (CameraAccessException e) {
            // The callback of this attempt also gets onError; handle the failure once, here.
            invalidateGeneration(generation);
            Log.e(TAG, "Cannot open camera " + cameraId, e);
            int reason = e.getReason();
            boolean busy = reason == CameraAccessException.CAMERA_IN_USE
                    || reason == CameraAccessException.MAX_CAMERAS_IN_USE;
            // The camera service rejected the camera itself ("No camera device with ID ... available").
            boolean unavailable = reason == CameraAccessException.CAMERA_DISCONNECTED
                    || reason == CameraAccessException.CAMERA_ERROR;
            handleOpenFailure(cameraId, "Cannot open camera " + cameraId + ": " + e.getMessage(), busy, unavailable);
        } catch (IllegalArgumentException | SecurityException e) {
            // Unknown camera id, or a camera this app may not open.
            invalidateGeneration(generation);
            Log.e(TAG, "Cannot open camera " + cameraId, e);
            handleOpenFailure(cameraId, "Cannot open camera " + cameraId + ": " + e.getMessage(), false, true);
        } catch (RuntimeException e) {
            invalidateGeneration(generation);
            Log.e(TAG, "Cannot open camera " + cameraId, e);
            handleOpenFailure(cameraId, "Cannot open camera " + cameraId + ": " + e.getMessage(), false, false);
        }
    }

    private void invalidateGeneration(int generation) {
        synchronized (mDeviceLock) {
            if (mDeviceGeneration == generation) {
                mDeviceGeneration++;
            }
        }
    }

    /**
     * An open attempt failed: retries a busy camera for a while, otherwise reports the error, as
     * onCameraUnavailable when the camera service rejected the camera itself.
     */
    private void handleOpenFailure(String cameraId, String message, boolean busy, boolean unavailable) {
        if (!mOpening) {
            return;
        }
        if (busy && mOpenRetries < MAX_OPEN_RETRIES) {
            mOpenRetries++;
            Log.w(TAG, message + ", retrying (" + mOpenRetries + "/" + MAX_OPEN_RETRIES + ")");
            scheduleOpen(OPEN_RETRY_DELAY_MS);
            return;
        }
        mOpening = false;
        if (unavailable && mCallback != null) {
            try {
                mCallback.onCameraUnavailable(cameraId, message);
            } catch (Exception e) {
                Log.e(TAG, "onCameraUnavailable callback failed", e);
            }
            return;
        }
        notifyError(message);
    }

    /**
     * Closes the camera without waiting. A camera that is still opening is closed when it arrives
     * (see DeviceStateCallback). The device itself is closed on the camera thread: closing blocks
     * until the camera service has released it, which includes waiting for a session that is still
     * being configured, and running it there also keeps it ahead of the next open.
     */
    public void closeCamera() {
        Runnable pendingOpen = mPendingOpen;
        mPendingOpen = null;
        if (pendingOpen != null) {
            mTextureView.removeCallbacks(pendingOpen);
        }
        final CameraDevice device;
        synchronized (mDeviceLock) {
            mDeviceGeneration++;
            device = mCameraDevice;
            mCameraDevice = null;
            mOpening = false;
        }
        final CameraCaptureSession session;
        synchronized (mSessionLock) {
            session = mCaptureSession;
            mCaptureSession = null;
            mPreviewRequestBuilder = null;
            mNotifyOpenedOnConfigure = false;
            // Sessions still being configured are closed when they report back.
            mSessionGeneration++;
        }
        final Surface surface = mPreviewSurface;
        mPreviewSurface = null;
        mPreviewSurfaceTexture = null;
        mBufferSize = null;
        startBackgroundThread();
        mBackgroundHandler.post(new Runnable() {
            @Override
            public void run() {
                closeQuietly(session);
                closeQuietly(device);
                if (surface != null) {
                    surface.release();
                }
            }
        });
    }

    private void closeSessionLocked() {
        if (mCaptureSession != null) {
            closeQuietly(mCaptureSession);
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

            Size previewSize = choosePreviewSize(choices, map.getOutputSizes(ImageFormat.JPEG),
                    rotatedPreviewWidth, rotatedPreviewHeight, maxPreviewWidth, maxPreviewHeight);
            mPreviewSize = previewSize;
            mSwappedDimensions = swappedDimensions;
            mOutputsWidth = width;
            mOutputsHeight = height;

            Log.d(TAG, "setUpCameraOutputs - Selected preview size: " + previewSize.getWidth() + "x" + previewSize.getHeight()
                    + " for area " + width + "x" + height + (swappedDimensions ? " (swapped)" : ""));

            // Fit the aspect ratio of the TextureView to the preview size we picked (cover mode), so
            // the view has the right shape before the first frame arrives.
            applyAspectRatio(previewSize);

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
     * Picks the preview size for an area of areaWidth x areaHeight (in sensor orientation). The
     * preview gets a standard aspect ratio (4:3 or 16:9) for which the camera also offers a JPEG
     * size, so that the still stream sharing the session has the same ratio, and among those the
     * ratio closest to the area. The texture view covers the area and crops the rest. Falls back to
     * the closest ratio of all sizes when the camera has no 4:3 or 16:9 preview size.
     */
    static Size choosePreviewSize(Size[] previewSizes, Size[] jpegSizes, int areaWidth, int areaHeight,
            int maxWidth, int maxHeight) {
        double areaRatio = (double) areaWidth / areaHeight;
        double bestRatio = 0;
        double bestDiff = Double.MAX_VALUE;
        boolean bestHasJpeg = false;
        for (double ratio : STANDARD_RATIOS) {
            boolean hasPreview = false;
            for (Size size : previewSizes) {
                if (size.getWidth() <= maxWidth && size.getHeight() <= maxHeight && hasRatio(size, ratio)) {
                    hasPreview = true;
                    break;
                }
            }
            if (!hasPreview) {
                continue;
            }
            boolean hasJpeg = false;
            if (jpegSizes != null) {
                for (Size size : jpegSizes) {
                    if (hasRatio(size, ratio)) {
                        hasJpeg = true;
                        break;
                    }
                }
            }
            double diff = Math.abs(ratio - areaRatio);
            // A ratio the still stream can match wins over one it cannot, then the closer one.
            if (bestRatio == 0 || (hasJpeg && !bestHasJpeg) || (hasJpeg == bestHasJpeg && diff < bestDiff)) {
                bestRatio = ratio;
                bestDiff = diff;
                bestHasJpeg = hasJpeg;
            }
        }
        if (bestRatio == 0) {
            return chooseOptimalSize(previewSizes, areaWidth, areaHeight, maxWidth, maxHeight);
        }
        List<Size> matching = new ArrayList<>();
        for (Size size : previewSizes) {
            if (hasRatio(size, bestRatio)) {
                matching.add(size);
            }
        }
        return chooseOptimalSize(matching.toArray(new Size[0]), areaWidth, areaHeight, maxWidth, maxHeight);
    }

    private static boolean hasRatio(Size size, double ratio) {
        return Math.abs((double) size.getWidth() / size.getHeight() - ratio) <= STANDARD_RATIO_TOLERANCE;
    }

    /** Shapes the texture view like the given preview buffer as it is displayed. */
    private void applyAspectRatio(Size size) {
        // The frame is shown rotated by 90 degrees exactly when the sensor is rotated relative to
        // the display, which is what swappedDimensions says.
        if (mSwappedDimensions) {
            mTextureView.setAspectRatio(size.getHeight(), size.getWidth());
        } else {
            mTextureView.setAspectRatio(size.getWidth(), size.getHeight());
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

    /**
     * Returns the preview surface for a new session (video recording) after re-applying the preview
     * buffer size: TextureView resets the default buffer size to its own size whenever it is laid
     * out again, and the camera sizes a new stream from that default.
     */
    public Surface getPreviewSurfaceForSession() {
        Surface surface = mPreviewSurface;
        SurfaceTexture texture = mPreviewSurfaceTexture;
        Size size = mPreviewSize;
        if (surface != null && texture != null && size != null) {
            mBufferSize = size;
            texture.setDefaultBufferSize(size.getWidth(), size.getHeight());
        }
        return surface;
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

    /**
     * Makes sure mPreviewSurface wraps the current SurfaceTexture and sizes its buffers to
     * previewSize. Returns null if unavailable.
     */
    private Surface ensurePreviewSurface(Size previewSize) {
        SurfaceTexture texture = mTextureView.getSurfaceTexture();
        if (texture == null || previewSize == null) {
            return null;
        }
        mBufferSize = previewSize;
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
    public void createCameraPreviewSession(List<Surface> additionalSurfaces, Runnable onReady) {
        createCameraPreviewSession(additionalSurfaces, onReady, 0);
    }

    private void createCameraPreviewSession(final List<Surface> additionalSurfaces, final Runnable onReady,
            final int attempt) {
        final CameraDevice device = mCameraDevice;
        if (device == null) {
            Log.w(TAG, "createCameraPreviewSession - camera device is not open");
            return;
        }
        try {
            // The size this session's preview stream is created with; the view follows it once the
            // stream runs, whatever was chosen meanwhile (onPreviewStreamStarted).
            final Size sessionSize = mPreviewSize;
            Surface previewSurface = ensurePreviewSurface(sessionSize);
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
                    boolean stale = onPreviewStreamStarted(sessionSize);
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
                    } else if (stale) {
                        // Not while a caller (picture, end of recording) uses this session.
                        recreatePreviewSessionAfterLayout();
                    }
                }

                @Override
                public void onFailed(String error) {
                    if (attempt == 0 && mCameraDevice == device) {
                        // Typically the surface changed size during configuration; once more after
                        // the layout pass before giving up.
                        Log.w(TAG, error + ", retrying once");
                        mTextureView.post(new Runnable() {
                            @Override
                            public void run() {
                                Handler handler = mBackgroundHandler;
                                if (handler == null) {
                                    return;
                                }
                                handler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (mCameraDevice == device) {
                                            createCameraPreviewSession(additionalSurfaces, onReady, 1);
                                        }
                                    }
                                });
                            }
                        });
                        return;
                    }
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
     * Called on the camera thread once a preview session streams frames of sessionSize. Shapes the
     * texture view for that stream and returns true if another aspect ratio was chosen while the
     * session was being configured (rotation, setPreviewSize), i.e. the session should be replaced.
     */
    private boolean onPreviewStreamStarted(final Size sessionSize) {
        Log.i(TAG, "Preview streaming: camera " + mCameraId + ", preview " + sessionSize
                + ", area " + getWidth() + "x" + getHeight()
                + ", texture view " + mTextureView.getWidth() + "x" + mTextureView.getHeight()
                + (mSwappedDimensions ? " (rotated)" : ""));
        Size wanted = mPreviewSize;
        if (wanted != null && !sameAspectRatio(wanted, sessionSize)) {
            // The view is already shaped for the newer choice; the replacement session matches it.
            Log.d(TAG, "Preview size changed to " + wanted + " while the session for " + sessionSize
                    + " was configured");
            return true;
        }
        applyAspectRatio(sessionSize);
        configureTransform(mTextureView.getWidth(), mTextureView.getHeight());
        return false;
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
                synchronized (mSessionLock) {
                    if (mCameraDevice != device || generation != mSessionGeneration) {
                        // A newer session (or a closed device) reports its own result.
                        Log.d(TAG, "Ignoring configure failure of replaced capture session (generation " + generation + ")");
                        return;
                    }
                }
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
            requestLayoutOnUiThread();
        }

        public void setCoverMode(boolean coverMode) {
            mCoverMode = coverMode;
            requestLayoutOnUiThread();
        }

        /**
         * On the UI thread the layout is requested right away, so that a message posted afterwards
         * (the camera open, a session recreation) runs after the layout pass.
         */
        private void requestLayoutOnUiThread() {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                requestLayout();
            } else {
                post(new Runnable() {
                    @Override
                    public void run() {
                        requestLayout();
                    }
                });
            }
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
