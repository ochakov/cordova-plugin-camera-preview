package com.cordovaplugincamerapreview;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.AudioManager;
import android.media.CamcorderProfile;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.exifinterface.media.ExifInterface;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CameraActivity extends Fragment implements Preview.PreviewCallback {

    public interface CameraPreviewListener {
        void onPictureTaken(String originalPicture);
        void onPictureTakenError(String message);
        void onSnapshotTaken(String originalPicture);
        void onSnapshotTakenError(String message);
        void onFocusSet(int pointX, int pointY);
        void onFocusSetError(String message);
        void onBackButton();
        void onCameraStarted();
        void onCameraError(String message);
        void onStartRecordVideo();
        void onStartRecordVideoError(String message);
        void onStopRecordVideo(String file);
        void onStopRecordVideoError(String error);
    }

    private static final String TAG = "CameraActivity";

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    /** Lens switch results: nothing to switch to, applied via digital zoom, or a camera switch was started. */
    public static final int LENS_RESULT_UNAVAILABLE = -1;
    public static final int LENS_RESULT_APPLIED = 0;
    public static final int LENS_RESULT_SWITCHING = 1;

    private static final int HIDDEN_CAMERA_PROBE_LIMIT = 20;
    private static final int TAKE_PICTURE_MAX_RETRIES = 100;
    private static final long TAKE_PICTURE_RETRY_DELAY_MS = 50;
    private static final long PRECAPTURE_TIMEOUT_MS = 1500;
    private static final long FOCUS_TIMEOUT_MS = 3000;
    private static final int MAX_VIDEO_WIDTH = 1920;
    private static final int MAX_VIDEO_HEIGHT = 1080;
    private static final double ASPECT_TOLERANCE = 0.15;

    private volatile CameraPreviewListener eventListener;
    public FrameLayout frameContainerLayout;
    private Preview mPreview;
    private View view;
    private CameraManager mCameraManager;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private String appResourcesPackage;

    // Camera selection
    private volatile String mCameraId;
    private volatile int cameraCurrentlyLocked = CameraCharacteristics.LENS_FACING_BACK;
    // Camera and lens to go back to when the camera being opened cannot be used.
    private volatile String fallbackCameraId;
    private volatile int fallbackFacing = -1;
    private volatile float fallbackFocalLength = -1f;
    // Set for the fallback of a remembered lens at startCamera(): that revert is not an error.
    private volatile boolean silentFallback = false;
    // The camera and lens that last streamed. Only this lens is remembered for the next start.
    private volatile String workingCameraId;
    private volatile int workingFacing = -1;
    private volatile float workingFocalLength = -1f;

    // Still capture
    private volatile boolean canTakePicture = false;
    private final Object mImageReaderLock = new Object();
    private ImageReader mImageReader;
    private Size mImageSize;
    private volatile int currentQuality = 85;

    // Settings that are re-applied to every new capture request
    private volatile String currentFlashMode = "auto";
    private volatile float currentZoom = 1.0f;
    private volatile String currentColorEffect = "none";
    private volatile String currentFocusMode = "continuous-picture";
    private volatile String currentWhiteBalanceMode = "auto";
    private volatile String currentExposureMode = "continuous";
    private volatile int currentExposureCompensation = 0;
    private volatile MeteringRectangle[] focusRegions; // set by tap-to-focus, cleared by setFocusMode / camera switch

    // Lens (focal length) switching. Values are zoom factors relative to the main camera (1.0).
    private float[] availableFocalLengths = new float[0];
    private int currentFocalLengthIndex = 0;
    private final Map<Float, String> focalLengthToCameraId = new HashMap<>();
    private final Map<String, Float> cameraIdToNativeFactor = new HashMap<>();

    // The last selected lens is remembered (per process) and restored by the next camera start
    // with the same facing. This is what keeps the lens across activity recreation and across
    // stopCamera()/startCamera() cycles that apps perform on rotation.
    private static float savedFocalLength = -1f;
    private static int savedFacing = -1;

    public String defaultCamera;
    public boolean tapToTakePicture;
    public boolean dragEnabled;
    public boolean tapToFocus;
    public boolean disableExifHeaderStripping;
    public boolean storeToFile;
    public boolean toBack;

    public int width;
    public int height;
    public int x;
    public int y;

    private enum RecordingState {IDLE, STARTING, STARTED, STOPPING}

    private volatile RecordingState mRecordingState = RecordingState.IDLE;
    private MediaRecorder mRecorder = null;
    private String recordFilePath;
    private Surface mRecorderSurface;

    public CameraActivity() {
    }

    public void setEventListener(CameraPreviewListener listener) {
        eventListener = listener;
    }

    public void setRect(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    // ---------------------------------------------------------------------------------------------
    // Fragment lifecycle
    // ---------------------------------------------------------------------------------------------

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Activity activity = getActivity();
        appResourcesPackage = activity.getPackageName();

        if (eventListener == null) {
            // The system restored this fragment (activity recreation) but the plugin instance that
            // owns it is gone. Nothing can talk to us, so remove ourselves instead of crashing.
            Log.w(TAG, "Fragment restored without a plugin listener, removing it");
            removeSelf();
            return new FrameLayout(activity);
        }

        view = inflater.inflate(getResources().getIdentifier("camera_activity", "layout", appResourcesPackage), container, false);
        frameContainerLayout = (FrameLayout) view.findViewById(getResources().getIdentifier("frame_container", "id", appResourcesPackage));

        createCameraPreview();
        return view;
    }

    private void removeSelf() {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    FragmentManager fragmentManager = getFragmentManager();
                    if (fragmentManager != null) {
                        fragmentManager.beginTransaction().remove(CameraActivity.this).commitAllowingStateLoss();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to remove orphaned fragment", e);
                }
            }
        });
    }

    private void createCameraPreview() {
        if (frameContainerLayout == null) {
            Log.e(TAG, "frameContainerLayout is null, cannot create camera preview");
            return;
        }
        Activity activity = getActivity();
        if (activity == null) {
            Log.e(TAG, "Activity is null, cannot create camera preview");
            return;
        }
        if (mCameraManager == null) {
            mCameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        }
        if (mCameraId == null) {
            setDefaultCameraId();
        }
        if (mCameraId == null) {
            notifyCameraError("No camera available on this device");
            return;
        }

        if (mPreview != null) {
            frameContainerLayout.removeView(mPreview);
        }

        mPreview = new Preview(activity);
        mPreview.setLayoutParams(buildLayoutParams(activity));
        mPreview.setPreviewCallback(this);
        frameContainerLayout.addView(mPreview);
        mPreview.setCamera(mCameraId);

        if (toBack) {
            frameContainerLayout.setZ(-1);
        }

        // The camera itself is opened from onResume() once the surface is available.
        setupTouchHandling();
    }

    private FrameLayout.LayoutParams buildLayoutParams(Activity activity) {
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        Log.d(TAG, "buildLayoutParams - x=" + x + ", y=" + y + ", width=" + width + ", height=" + height
                + ", screen=" + metrics.widthPixels + "x" + metrics.heightPixels);

        FrameLayout.LayoutParams layoutParams;
        // Use full screen if width/height are 0 or close to the screen size (within 10% tolerance)
        boolean isFullScreenWidth = (width == 0) || (width >= metrics.widthPixels * 0.9);
        boolean isFullScreenHeight = (height == 0) || (height >= metrics.heightPixels * 0.9);

        if (isFullScreenWidth && isFullScreenHeight) {
            layoutParams = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
            layoutParams.setMargins(0, 0, 0, 0);
        } else {
            layoutParams = new FrameLayout.LayoutParams(width, height);
            layoutParams.setMargins(x, y, 0, 0);
        }
        return layoutParams;
    }

    private void setDefaultCameraId() {
        int wantedFacing = "front".equals(defaultCamera)
                ? CameraCharacteristics.LENS_FACING_FRONT
                : CameraCharacteristics.LENS_FACING_BACK;
        try {
            String[] cameraIds = mCameraManager.getCameraIdList();
            String chosen = null;
            int chosenFacing = wantedFacing;

            for (String cameraId : cameraIds) {
                Integer facing = getFacing(cameraId);
                if (facing != null && facing == wantedFacing) {
                    chosen = cameraId;
                    break;
                }
            }
            if (chosen == null && cameraIds.length > 0) {
                // Fallback to the first available camera
                chosen = cameraIds[0];
                Integer facing = getFacing(chosen);
                chosenFacing = facing != null ? facing : CameraCharacteristics.LENS_FACING_BACK;
            }
            if (chosen == null) {
                Log.e(TAG, "No cameras available");
                return;
            }

            cameraCurrentlyLocked = chosenFacing;
            mCameraId = initializeFocalLengths(chosen, chosenFacing);
            currentZoom = zoomForLens(mCameraId, getCurrentFocalLength());
            if (mCameraId != null && !mCameraId.equals(chosen)) {
                // The remembered lens is on another camera of this facing. If that camera cannot be
                // opened, start the default camera instead of failing startCamera().
                fallbackCameraId = chosen;
                fallbackFacing = chosenFacing;
                fallbackFocalLength = nativeFactorOf(chosen);
                silentFallback = true;
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Cannot access camera", e);
        }
    }

    private String firstCameraWithFacing(int facing) {
        try {
            for (String cameraId : mCameraManager.getCameraIdList()) {
                Integer f = getFacing(cameraId);
                if (f != null && f == facing) {
                    return cameraId;
                }
            }
        } catch (CameraAccessException e) {
            Log.w(TAG, "Cannot list cameras", e);
        }
        return null;
    }

    private Integer getFacing(String cameraId) {
        try {
            return mCameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING);
        } catch (CameraAccessException | IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        saveFocalLengthState();
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Log.d(TAG, "onViewCreated - tapToFocus=" + tapToFocus + ", tapToTakePicture=" + tapToTakePicture + ", dragEnabled=" + dragEnabled);
    }

    private void setupTouchHandling() {
        if (frameContainerLayout == null || mPreview == null) {
            Log.w(TAG, "setupTouchHandling - frameContainerLayout or mPreview is null");
            return;
        }

        final TapGestureDetector tapGestureDetector = new TapGestureDetector(getActivity(), new TapGestureDetector.OnTapListener() {
            @Override
            public void onTap(View v, MotionEvent e) {
                if (tapToFocus) {
                    // The texture view receives the touch; report the point relative to the
                    // preview area like tapToFocus() from JS does.
                    int pointX = (int) e.getX();
                    int pointY = (int) e.getY();
                    if (v != null && v != mPreview) {
                        pointX += v.getLeft();
                        pointY += v.getTop();
                    }
                    setFocus(pointX, pointY);
                }
                if (tapToTakePicture && canTakePicture) {
                    takePicture(0, 0, 85);
                }
            }
        });

        if (dragEnabled) {
            mPreview.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (event.getAction() == MotionEvent.ACTION_MOVE) {
                        frameContainerLayout.setX(event.getRawX() - frameContainerLayout.getWidth() / 2);
                        frameContainerLayout.setY(event.getRawY() - frameContainerLayout.getHeight() / 2);
                        return true;
                    }
                    return tapGestureDetector.onTouch(v, event);
                }
            });
        } else {
            mPreview.setOnTouchListener(tapGestureDetector);
        }

        mPreview.setClickable(true);
        mPreview.setFocusable(true);
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
        if (mPreview != null) {
            mPreview.startBackgroundThread();
            mPreview.resumePreview();
        }
    }

    @Override
    public void onPause() {
        Log.d(TAG, "onPause");
        saveFocalLengthState();
        if (mRecordingState == RecordingState.STARTED) {
            stopRecord();
        }
        canTakePicture = false;
        if (mPreview != null) {
            mPreview.pausePreview();
        }
        // Frees the picture buffers; the reader is recreated when the camera is opened again.
        closeImageReaderAfterCamera();
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        Log.d(TAG, "onDestroyView");
        saveFocalLengthState();
        releaseRecorder();
        mRecordingState = RecordingState.IDLE;
        if (mPreview != null) {
            mPreview.pausePreview();
        }
        closeImageReaderAfterCamera();
        super.onDestroyView();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Log.d(TAG, "onConfigurationChanged");
        updatePreviewLayout();
        if (mPreview != null) {
            mPreview.onDisplayRotationChanged();
        }
    }

    private void updatePreviewLayout() {
        Activity activity = getActivity();
        if (mPreview == null || frameContainerLayout == null || activity == null) {
            return;
        }
        mPreview.setLayoutParams(buildLayoutParams(activity));
        mPreview.requestLayout();
    }

    /** Sets the preview size (in pixels) and re-lays out the preview. */
    public void setPreviewSize(final int widthPx, final int heightPx) {
        this.width = widthPx;
        this.height = heightPx;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                updatePreviewLayout();
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Preview.PreviewCallback
    // ---------------------------------------------------------------------------------------------

    @Override
    public List<Surface> onCameraDeviceOpened() {
        synchronized (mImageReaderLock) {
            Size target = chooseDefaultPictureSize();
            if (target == null) {
                closeImageReaderLocked();
                return null;
            }
            ensureImageReaderLocked(target);
            if (mImageReader == null) {
                return null;
            }
            return Collections.singletonList(mImageReader.getSurface());
        }
    }

    @Override
    public void onConfigurePreviewRequest(CaptureRequest.Builder builder) {
        applyCurrentSettings(builder);
    }

    @Override
    public void onCameraOpened() {
        Log.d(TAG, "Camera opened - preview session is streaming");
        workingCameraId = mCameraId;
        workingFacing = cameraCurrentlyLocked;
        workingFocalLength = getCurrentFocalLength();
        fallbackCameraId = null;
        silentFallback = false;
        canTakePicture = true;
        if (eventListener != null) {
            eventListener.onCameraStarted();
        }
    }

    @Override
    public void onCameraError(String error) {
        Log.e(TAG, "Camera error: " + error);

        final String revertTo = fallbackCameraId;
        if (revertTo != null && !revertTo.equals(mCameraId) && mPreview != null) {
            // A camera switch failed (typically a hidden lens the OEM does not allow us to open),
            // or the remembered lens could not be opened at start. Go back to the camera that was
            // working, or to the default camera.
            final int revertFacing = fallbackFacing;
            final float revertFactor = fallbackFocalLength;
            final boolean silent = silentFallback;
            fallbackCameraId = null;
            silentFallback = false;
            Log.w(TAG, "Opening camera " + mCameraId + " failed (" + error + "), reverting to " + revertTo);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (mPreview == null) return;
                    canTakePicture = false;
                    focusRegions = null;
                    mPreview.closeCamera();
                    closeImageReaderAfterCamera();
                    selectLens(revertTo, revertFacing, revertFactor);
                    mPreview.setCamera(revertTo);
                    mPreview.resumePreview();
                }
            });
            if (!silent) {
                notifyCameraError("Failed to switch camera: " + error);
            }
            return;
        }

        notifyCameraError(error);
    }

    private void notifyCameraError(String error) {
        if (eventListener != null) {
            eventListener.onCameraError(error);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Camera / lens switching
    // ---------------------------------------------------------------------------------------------

    /**
     * Switches between front and back cameras. Returns true if a switch was started (the result is
     * reported through onCameraStarted / onCameraError), false if there is no camera to switch to.
     */
    public boolean switchCamera() {
        Log.d(TAG, "switchCamera");
        if (mPreview == null || mCameraManager == null) {
            return false;
        }
        try {
            for (String cameraId : mCameraManager.getCameraIdList()) {
                if (cameraId.equals(mCameraId)) continue;
                Integer facing = getFacing(cameraId);
                if (facing != null && facing != cameraCurrentlyLocked
                        && (facing == CameraCharacteristics.LENS_FACING_FRONT || facing == CameraCharacteristics.LENS_FACING_BACK)) {
                    int newFacing = facing;
                    String target = initializeFocalLengths(cameraId, newFacing);
                    switchToCamera(target, newFacing, zoomForLens(target, getCurrentFocalLength()));
                    return true;
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Cannot switch camera", e);
        }
        Log.w(TAG, "No camera with a different facing found");
        return false;
    }

    private void switchToCamera(String newCameraId, int facing, float zoom) {
        if (mPreview == null) return;
        Log.d(TAG, "Switching from camera " + mCameraId + " to camera " + newCameraId);
        canTakePicture = false;
        if (workingCameraId != null) {
            // Go back to the camera that last streamed if this one cannot be used. Before the first
            // camera streamed, the start fallback (if any) stays in place.
            fallbackCameraId = workingCameraId;
            fallbackFacing = workingFacing;
            fallbackFocalLength = workingFocalLength;
            silentFallback = false;
        }
        focusRegions = null;
        currentZoom = zoom;

        mPreview.closeCamera();
        closeImageReaderAfterCamera();

        mCameraId = newCameraId;
        cameraCurrentlyLocked = facing;
        mPreview.setCamera(newCameraId);
        mPreview.resumePreview();
    }

    /**
     * Builds the lens table for all cameras facing the same way as {@code defaultId} (including
     * hidden physical cameras) and returns the camera id to open: the remembered lens if one
     * applies, otherwise {@code defaultId}.
     */
    private String initializeFocalLengths(String defaultId, int facing) {
        focalLengthToCameraId.clear();
        cameraIdToNativeFactor.clear();
        List<Float> factors = new ArrayList<>();
        String preferredId = defaultId;

        try {
            LinkedHashMap<String, CameraCharacteristics> cameras = new LinkedHashMap<>();
            String[] listed = mCameraManager.getCameraIdList();
            List<String> listedIds = Arrays.asList(listed);

            CameraCharacteristics defaultChars = mCameraManager.getCameraCharacteristics(defaultId);
            cameras.put(defaultId, defaultChars);

            // Listed cameras with the same facing (the logical camera usually comes first).
            for (String cameraId : listed) {
                if (cameras.containsKey(cameraId)) continue;
                try {
                    CameraCharacteristics chars = mCameraManager.getCameraCharacteristics(cameraId);
                    Integer f = chars.get(CameraCharacteristics.LENS_FACING);
                    if (f != null && f == facing) {
                        cameras.put(cameraId, chars);
                    }
                } catch (Exception e) {
                    Log.d(TAG, "Cannot read camera " + cameraId + ": " + e.getMessage());
                }
            }

            // Hidden physical cameras that are not part of getCameraIdList().
            for (int i = 0; i < HIDDEN_CAMERA_PROBE_LIMIT; i++) {
                String cameraId = String.valueOf(i);
                if (cameras.containsKey(cameraId) || listedIds.contains(cameraId)) continue;
                try {
                    CameraCharacteristics chars = mCameraManager.getCameraCharacteristics(cameraId);
                    Integer f = chars.get(CameraCharacteristics.LENS_FACING);
                    if (f == null || f != facing) continue;
                    if (!isUsableHiddenCamera(cameraId, chars)) continue;
                    Log.d(TAG, "Found hidden camera " + cameraId);
                    cameras.put(cameraId, chars);
                } catch (Exception e) {
                    // Camera id does not exist or is not accessible
                    Log.d(TAG, "Camera ID " + cameraId + " not available: " + e.getMessage());
                }
            }

            float[] refFocalLengths = defaultChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            SizeF refSensor = defaultChars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            float refFocalLength = refFocalLengths != null && refFocalLengths.length > 0 ? refFocalLengths[0] : 0f;

            for (Map.Entry<String, CameraCharacteristics> entry : cameras.entrySet()) {
                String cameraId = entry.getKey();
                CameraCharacteristics chars = entry.getValue();
                float[] focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                SizeF sensor = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                if (focalLengths == null || focalLengths.length == 0) continue;

                for (float focalLength : focalLengths) {
                    float factor = roundFactor(computeZoomFactor(focalLength, sensor, refFocalLength, refSensor));
                    if (!focalLengthToCameraId.containsKey(factor)) {
                        focalLengthToCameraId.put(factor, cameraId);
                        factors.add(factor);
                        Log.d(TAG, "Lens " + factor + "x (" + focalLength + "mm) -> camera " + cameraId);
                    }
                    if (!cameraIdToNativeFactor.containsKey(cameraId)) {
                        cameraIdToNativeFactor.put(cameraId, factor);
                    }
                }
            }

            Collections.sort(factors);
            availableFocalLengths = new float[factors.size()];
            for (int i = 0; i < factors.size(); i++) {
                availableFocalLengths[i] = factors.get(i);
            }

            Float defaultFactor = cameraIdToNativeFactor.get(defaultId);
            int index = indexOfFactor(defaultFactor != null ? defaultFactor : 1.0f);
            if (index < 0) index = 0;

            if (savedFocalLength > 0 && savedFacing == facing) {
                int restored = indexOfFactor(savedFocalLength);
                if (restored >= 0) {
                    index = restored;
                    String restoredId = focalLengthToCameraId.get(availableFocalLengths[restored]);
                    if (restoredId != null) {
                        preferredId = restoredId;
                    }
                    Log.d(TAG, "Restored lens " + savedFocalLength + "x -> camera " + preferredId);
                }
            }
            savedFocalLength = -1f;
            savedFacing = -1;

            currentFocalLengthIndex = index;
            Log.d(TAG, "Available lenses: " + Arrays.toString(availableFocalLengths) + ", current index " + currentFocalLengthIndex);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize focal lengths", e);
        }
        return preferredId;
    }

    private boolean isUsableHiddenCamera(String cameraId, CameraCharacteristics chars) {
        Integer level = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        if (level != null && level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
            Log.d(TAG, "Skipping hidden camera " + cameraId + ": LEGACY hardware level");
            return false;
        }
        int[] capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (capabilities != null && !contains(capabilities, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) {
            Log.d(TAG, "Skipping hidden camera " + cameraId + ": not backward compatible (depth/IR sensor)");
            return false;
        }
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] jpeg = map != null ? map.getOutputSizes(ImageFormat.JPEG) : null;
        if (jpeg == null || jpeg.length == 0) {
            Log.d(TAG, "Skipping hidden camera " + cameraId + ": no JPEG output");
            return false;
        }
        return true;
    }

    private static float computeZoomFactor(float focalLength, SizeF sensor, float refFocalLength, SizeF refSensor) {
        if (focalLength <= 0 || refFocalLength <= 0) {
            return 1.0f;
        }
        if (sensor != null && refSensor != null && sensor.getWidth() > 0 && refSensor.getWidth() > 0) {
            // Ratio of the horizontal fields of view: tan(refFov/2) / tan(fov/2)
            return (refSensor.getWidth() * focalLength) / (refFocalLength * sensor.getWidth());
        }
        return focalLength / refFocalLength;
    }

    private static float roundFactor(float factor) {
        return Math.round(factor * 100f) / 100f;
    }

    private int indexOfFactor(float factor) {
        for (int i = 0; i < availableFocalLengths.length; i++) {
            if (Math.abs(availableFocalLengths[i] - factor) < 0.01f) {
                return i;
            }
        }
        return -1;
    }

    private void saveFocalLengthState() {
        // Only the lens that last streamed is remembered. A lens that was still opening (for example
        // when the camera is left right after a lens switch), or that could not be opened, would
        // otherwise be opened again by every following startCamera().
        if (workingFocalLength > 0 && workingFacing >= 0) {
            savedFocalLength = workingFocalLength;
            savedFacing = workingFacing;
        }
    }

    /** Native zoom factor of a camera relative to the main camera of its facing (1.0 if unknown). */
    private float nativeFactorOf(String cameraId) {
        Float factor = cameraId != null ? cameraIdToNativeFactor.get(cameraId) : null;
        return factor != null && factor > 0 ? factor : 1.0f;
    }

    /** Digital zoom that shows the lens factor on the given camera. */
    private float zoomForLens(String cameraId, float factor) {
        if (factor <= 0) {
            return 1.0f;
        }
        return Math.max(1.0f, factor / nativeFactorOf(cameraId));
    }

    /**
     * Points the camera and lens state at cameraId with the given lens factor, rebuilding the lens
     * table when the facing differs from the current one (a failed front/back switch).
     */
    private void selectLens(String cameraId, int facing, float factor) {
        if (facing >= 0 && (facing != cameraCurrentlyLocked || availableFocalLengths.length == 0)) {
            String defaultId = firstCameraWithFacing(facing);
            if (defaultId != null) {
                initializeFocalLengths(defaultId, facing);
            }
        }
        if (facing >= 0) {
            cameraCurrentlyLocked = facing;
        } else {
            Integer actual = getFacing(cameraId);
            if (actual != null) cameraCurrentlyLocked = actual;
        }
        int index = factor > 0 ? indexOfFactor(factor) : -1;
        if (index >= 0) {
            currentFocalLengthIndex = index;
        }
        mCameraId = cameraId;
        currentZoom = zoomForLens(cameraId, getCurrentFocalLength());
    }

    /** Cycles to the next lens. Returns one of the LENS_RESULT_* values. */
    public int switchFocalLength() {
        if (mPreview == null || availableFocalLengths.length <= 1) {
            Log.w(TAG, "Cannot switch focal length: only one lens available");
            return LENS_RESULT_UNAVAILABLE;
        }
        return setFocalLengthIndex((currentFocalLengthIndex + 1) % availableFocalLengths.length);
    }

    /** Switches to the lens closest to the given zoom factor. Returns one of the LENS_RESULT_* values. */
    public int setFocalLength(float targetFactor) {
        if (mPreview == null || availableFocalLengths.length == 0) {
            Log.w(TAG, "Cannot set focal length: camera not ready or no lenses available");
            return LENS_RESULT_UNAVAILABLE;
        }
        int closestIndex = 0;
        float minDifference = Float.MAX_VALUE;
        for (int i = 0; i < availableFocalLengths.length; i++) {
            float difference = Math.abs(availableFocalLengths[i] - targetFactor);
            if (difference < minDifference) {
                minDifference = difference;
                closestIndex = i;
            }
        }
        return setFocalLengthIndex(closestIndex);
    }

    private int setFocalLengthIndex(int index) {
        currentFocalLengthIndex = index;
        float target = availableFocalLengths[index];
        String targetCameraId = focalLengthToCameraId.get(target);
        Log.d(TAG, "Selecting lens " + target + "x (camera " + targetCameraId + ")");

        if (targetCameraId != null && !targetCameraId.equals(mCameraId)) {
            switchToCamera(targetCameraId, cameraCurrentlyLocked, zoomForLens(targetCameraId, target));
            return LENS_RESULT_SWITCHING;
        }

        // Same physical camera: emulate the lens with digital zoom relative to its native factor.
        setZoom(zoomForLens(mCameraId, target));
        if (mCameraId != null && mCameraId.equals(workingCameraId)) {
            workingFocalLength = target;
        }
        return LENS_RESULT_APPLIED;
    }

    public float getCurrentFocalLength() {
        if (currentFocalLengthIndex >= 0 && currentFocalLengthIndex < availableFocalLengths.length) {
            return availableFocalLengths[currentFocalLengthIndex];
        }
        return 0f;
    }

    public float[] getAvailableFocalLengths() {
        return availableFocalLengths.clone();
    }

    public int getCurrentFocalLengthIndex() {
        return currentFocalLengthIndex;
    }

    // ---------------------------------------------------------------------------------------------
    // Request settings
    // ---------------------------------------------------------------------------------------------

    /** Applies every user-visible setting to a request builder (preview, still or record). */
    private void applyCurrentSettings(CaptureRequest.Builder builder) {
        CameraCharacteristics chars = mPreview != null ? mPreview.getCharacteristics() : null;
        MeteringRectangle[] regions = focusRegions;
        if (regions != null && supportsAfMode(chars, CaptureRequest.CONTROL_AF_MODE_AUTO)) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            if (maxRegions(chars, CameraCharacteristics.CONTROL_MAX_REGIONS_AF) > 0) {
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, regions);
            }
            if (maxRegions(chars, CameraCharacteristics.CONTROL_MAX_REGIONS_AE) > 0) {
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, regions);
            }
        } else {
            applyFocusMode(builder, chars, currentFocusMode);
        }
        applyFlashMode(builder, currentFlashMode);
        applyZoom(builder, chars, currentZoom);
        applyColorEffect(builder, chars, currentColorEffect);
        applyWhiteBalance(builder, chars, currentWhiteBalanceMode);
        applyExposure(builder, chars);
    }

    private static int maxRegions(CameraCharacteristics chars, CameraCharacteristics.Key<Integer> key) {
        if (chars == null) return 0;
        Integer value = chars.get(key);
        return value != null ? value : 0;
    }

    private static boolean contains(int[] values, int value) {
        if (values == null) return false;
        for (int v : values) {
            if (v == value) return true;
        }
        return false;
    }

    private static boolean supportsAfMode(CameraCharacteristics chars, int afMode) {
        if (chars == null) return afMode == CaptureRequest.CONTROL_AF_MODE_OFF;
        return contains(chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES), afMode);
    }

    private static int focusModeToAf(String mode) {
        switch (mode) {
            case "auto": return CaptureRequest.CONTROL_AF_MODE_AUTO;
            case "macro": return CaptureRequest.CONTROL_AF_MODE_MACRO;
            case "continuous-video": return CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO;
            case "edof": return CaptureRequest.CONTROL_AF_MODE_EDOF;
            case "fixed":
            case "infinity": return CaptureRequest.CONTROL_AF_MODE_OFF;
            case "continuous":
            case "continuous-picture":
            default: return CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
        }
    }

    private void applyFocusMode(CaptureRequest.Builder builder, CameraCharacteristics chars, String mode) {
        int afMode = focusModeToAf(mode);
        if (!supportsAfMode(chars, afMode)) {
            if (supportsAfMode(chars, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                afMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
            } else if (supportsAfMode(chars, CaptureRequest.CONTROL_AF_MODE_AUTO)) {
                afMode = CaptureRequest.CONTROL_AF_MODE_AUTO;
            } else {
                afMode = CaptureRequest.CONTROL_AF_MODE_OFF;
            }
        }
        builder.set(CaptureRequest.CONTROL_AF_MODE, afMode);
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
        builder.set(CaptureRequest.CONTROL_AF_REGIONS, null);
        builder.set(CaptureRequest.CONTROL_AE_REGIONS, null);
        if ("infinity".equals(mode) && afMode == CaptureRequest.CONTROL_AF_MODE_OFF) {
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f);
        }
    }

    private void applyFlashMode(CaptureRequest.Builder builder, String flashMode) {
        if (mPreview == null || !mPreview.isFlashSupported()) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
            return;
        }
        // FLASH_MODE has to be reset explicitly, otherwise a previous torch keeps burning.
        switch (flashMode) {
            case "on":
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
                break;
            case "auto":
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
                break;
            case "red-eye":
                builder.set(CaptureRequest.CONTROL_AE_MODE, isAeModeAvailable(CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE)
                        ? CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE
                        : CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
                break;
            case "torch":
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
                break;
            case "off":
            default:
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
                break;
        }
    }

    private boolean isAeModeAvailable(int aeMode) {
        CameraCharacteristics chars = mPreview != null ? mPreview.getCharacteristics() : null;
        return chars != null && contains(chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES), aeMode);
    }

    private static void applyZoom(CaptureRequest.Builder builder, CameraCharacteristics chars, float zoom) {
        if (chars == null) return;
        Rect sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (sensorRect == null) return;
        Float maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        float clamped = Math.max(1.0f, Math.min(zoom, maxZoom != null ? maxZoom : 1.0f));
        int cropW = (int) (sensorRect.width() / clamped);
        int cropH = (int) (sensorRect.height() / clamped);
        int cropX = sensorRect.left + (sensorRect.width() - cropW) / 2;
        int cropY = sensorRect.top + (sensorRect.height() - cropH) / 2;
        builder.set(CaptureRequest.SCALER_CROP_REGION, new Rect(cropX, cropY, cropX + cropW, cropY + cropH));
    }

    private static Integer colorEffectToMode(String effect) {
        switch (effect) {
            case "none": return CaptureRequest.CONTROL_EFFECT_MODE_OFF;
            case "mono": return CaptureRequest.CONTROL_EFFECT_MODE_MONO;
            case "negative": return CaptureRequest.CONTROL_EFFECT_MODE_NEGATIVE;
            case "solarize": return CaptureRequest.CONTROL_EFFECT_MODE_SOLARIZE;
            case "sepia": return CaptureRequest.CONTROL_EFFECT_MODE_SEPIA;
            case "posterize": return CaptureRequest.CONTROL_EFFECT_MODE_POSTERIZE;
            case "whiteboard": return CaptureRequest.CONTROL_EFFECT_MODE_WHITEBOARD;
            case "blackboard": return CaptureRequest.CONTROL_EFFECT_MODE_BLACKBOARD;
            case "aqua": return CaptureRequest.CONTROL_EFFECT_MODE_AQUA;
            default: return null;
        }
    }

    private static final String[] COLOR_EFFECT_NAMES = {"none", "mono", "negative", "solarize", "sepia", "posterize", "whiteboard", "blackboard", "aqua"};

    private static void applyColorEffect(CaptureRequest.Builder builder, CameraCharacteristics chars, String effect) {
        Integer mode = colorEffectToMode(effect);
        if (mode == null || chars == null || !contains(chars.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS), mode)) {
            mode = CaptureRequest.CONTROL_EFFECT_MODE_OFF;
        }
        builder.set(CaptureRequest.CONTROL_EFFECT_MODE, mode);
    }

    private static Integer whiteBalanceToAwb(String mode) {
        switch (mode) {
            case "auto":
            case "continuous": return CaptureRequest.CONTROL_AWB_MODE_AUTO;
            case "incandescent": return CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT;
            case "fluorescent": return CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT;
            case "warm-fluorescent": return CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT;
            case "daylight": return CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT;
            case "cloudy-daylight": return CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT;
            case "twilight": return CaptureRequest.CONTROL_AWB_MODE_TWILIGHT;
            case "shade": return CaptureRequest.CONTROL_AWB_MODE_SHADE;
            default: return null;
        }
    }

    private static final String[] WHITE_BALANCE_NAMES = {"auto", "incandescent", "fluorescent", "warm-fluorescent", "daylight", "cloudy-daylight", "twilight", "shade"};

    private static boolean isAwbLockAvailable(CameraCharacteristics chars) {
        if (chars == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Boolean available = chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE);
            return available != null && available;
        }
        return true;
    }

    private static boolean isAeLockAvailable(CameraCharacteristics chars) {
        if (chars == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Boolean available = chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE);
            return available != null && available;
        }
        return true;
    }

    private static void applyWhiteBalance(CaptureRequest.Builder builder, CameraCharacteristics chars, String mode) {
        if ("lock".equals(mode)) {
            if (isAwbLockAvailable(chars)) {
                builder.set(CaptureRequest.CONTROL_AWB_LOCK, true);
            }
            return;
        }
        if (isAwbLockAvailable(chars)) {
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, false);
        }
        Integer awb = whiteBalanceToAwb(mode);
        if (awb == null || chars == null || !contains(chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES), awb)) {
            awb = CaptureRequest.CONTROL_AWB_MODE_AUTO;
        }
        builder.set(CaptureRequest.CONTROL_AWB_MODE, awb);
    }

    private void applyExposure(CaptureRequest.Builder builder, CameraCharacteristics chars) {
        if (isAeLockAvailable(chars)) {
            builder.set(CaptureRequest.CONTROL_AE_LOCK, "lock".equals(currentExposureMode));
        }
        int[] range = getExposureCompensationRange();
        if (range != null) {
            int value = Math.max(range[0], Math.min(range[1], currentExposureCompensation));
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, value);
        }
    }

    /** Re-applies the current settings to the running preview request. */
    private void refreshPreviewRequest() {
        final Preview preview = mPreview;
        if (preview == null) return;
        runOnCameraThread(new Runnable() {
            @Override
            public void run() {
                CaptureRequest.Builder builder = preview.getPreviewRequestBuilder();
                if (builder == null) return;
                applyCurrentSettings(builder);
                preview.updatePreviewRequest();
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Flash / zoom / effects / focus mode / white balance / exposure API
    // ---------------------------------------------------------------------------------------------

    public List<String> getSupportedFlashModes() {
        List<String> flashModes = new ArrayList<>();
        flashModes.add("off");
        if (mPreview != null && mPreview.isFlashSupported()) {
            flashModes.add("on");
            flashModes.add("auto");
            flashModes.add("torch");
            if (isAeModeAvailable(CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE)) {
                flashModes.add("red-eye");
            }
        }
        return flashModes;
    }

    public String getFlashMode() {
        return currentFlashMode;
    }

    public boolean setFlashMode(String flashMode) {
        if (!getSupportedFlashModes().contains(flashMode)) {
            return false;
        }
        currentFlashMode = flashMode;
        refreshPreviewRequest();
        return true;
    }

    public float getMaxZoom() {
        CameraCharacteristics chars = mPreview != null ? mPreview.getCharacteristics() : null;
        if (chars == null) return 1.0f;
        Float maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        return maxZoom != null ? maxZoom : 1.0f;
    }

    public float getZoom() {
        return currentZoom;
    }

    /** Sets the digital zoom factor (1.0 = no zoom). Returns the value actually applied. */
    public float setZoom(float zoom) {
        float clamped = Math.max(1.0f, Math.min(zoom, getMaxZoom()));
        currentZoom = clamped;
        refreshPreviewRequest();
        return clamped;
    }

    public List<String> getSupportedColorEffects() {
        List<String> effects = new ArrayList<>();
        CameraCharacteristics chars = mPreview != null ? mPreview.getCharacteristics() : null;
        int[] available = chars != null ? chars.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS) : null;
        for (String name : COLOR_EFFECT_NAMES) {
            Integer mode = colorEffectToMode(name);
            if (mode != null && (mode == CaptureRequest.CONTROL_EFFECT_MODE_OFF || contains(available, mode))) {
                effects.add(name);
            }
        }
        return effects;
    }

    public String getColorEffect() {
        return currentColorEffect;
    }

    public boolean setColorEffect(String effect) {
        if (!getSupportedColorEffects().contains(effect)) {
            return false;
        }
        currentColorEffect = effect;
        refreshPreviewRequest();
        return true;
    }

    public List<String> getSupportedFocusModes() {
        List<String> modes = new ArrayList<>();
        CameraCharacteristics chars = mPreview != null ? mPreview.getCharacteristics() : null;
        if (supportsAfMode(chars, CaptureRequest.CONTROL_AF_MODE_AUTO)) modes.add("auto");
        if (supportsAfMode(chars, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) modes.add("continuous-picture");
        if (supportsAfMode(chars, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) modes.add("continuous-video");
        if (supportsAfMode(chars, CaptureRequest.CONTROL_AF_MODE_MACRO)) modes.add("macro");
        if (supportsAfMode(chars, CaptureRequest.CONTROL_AF_MODE_EDOF)) modes.add("edof");
        if (supportsAfMode(chars, CaptureRequest.CONTROL_AF_MODE_OFF)) {
            modes.add("fixed");
            Float minFocus = chars != null ? chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) : null;
            if (minFocus != null && minFocus > 0) {
                modes.add("infinity");
            }
        }
        return modes;
    }

    public String getFocusMode() {
        return currentFocusMode;
    }

    public boolean setFocusMode(String focusMode) {
        if (!getSupportedFocusModes().contains(focusMode)) {
            return false;
        }
        currentFocusMode = focusMode;
        focusRegions = null;
        refreshPreviewRequest();
        return true;
    }

    public List<String> getSupportedWhiteBalanceModes() {
        List<String> modes = new ArrayList<>();
        CameraCharacteristics chars = mPreview != null ? mPreview.getCharacteristics() : null;
        int[] available = chars != null ? chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) : null;
        if (isAwbLockAvailable(chars)) {
            modes.add("lock");
        }
        for (String name : WHITE_BALANCE_NAMES) {
            Integer awb = whiteBalanceToAwb(name);
            if (awb != null && contains(available, awb)) {
                modes.add(name);
            }
        }
        if (modes.isEmpty()) {
            modes.add("auto");
        }
        return modes;
    }

    public String getWhiteBalanceMode() {
        return currentWhiteBalanceMode;
    }

    public boolean setWhiteBalanceMode(String mode) {
        if (!getSupportedWhiteBalanceModes().contains(mode)) {
            return false;
        }
        currentWhiteBalanceMode = mode;
        refreshPreviewRequest();
        return true;
    }

    public List<String> getExposureModes() {
        List<String> modes = new ArrayList<>();
        CameraCharacteristics chars = mPreview != null ? mPreview.getCharacteristics() : null;
        if (isAeLockAvailable(chars)) {
            modes.add("lock");
        }
        modes.add("continuous");
        return modes;
    }

    public String getExposureMode() {
        return currentExposureMode;
    }

    public boolean setExposureMode(String mode) {
        if (!getExposureModes().contains(mode)) {
            return false;
        }
        currentExposureMode = mode;
        refreshPreviewRequest();
        return true;
    }

    /** Returns {min, max} exposure compensation steps, or null when the camera has no range. */
    public int[] getExposureCompensationRange() {
        CameraCharacteristics chars = mPreview != null ? mPreview.getCharacteristics() : null;
        if (chars == null) return null;
        Range<Integer> range = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        if (range == null) return null;
        return new int[]{range.getLower(), range.getUpper()};
    }

    public int getExposureCompensation() {
        return currentExposureCompensation;
    }

    /** Sets the exposure compensation (in steps). Returns the clamped value actually applied. */
    public int setExposureCompensation(int value) {
        int[] range = getExposureCompensationRange();
        int applied = value;
        if (range != null) {
            applied = Math.max(range[0], Math.min(range[1], value));
        }
        currentExposureCompensation = applied;
        refreshPreviewRequest();
        return applied;
    }

    public List<Size> getSupportedPictureSizes() {
        Size[] sizes = getJpegSizes();
        return sizes != null ? Arrays.asList(sizes) : new ArrayList<Size>();
    }

    public float getHorizontalFOV() {
        CameraCharacteristics chars = mPreview != null ? mPreview.getCharacteristics() : null;
        if (chars == null) return 0f;
        try {
            float[] focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            SizeF sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            if (focalLengths != null && focalLengths.length > 0 && sensorSize != null) {
                double focalLength = focalLengths[0];
                double sensorWidth = sensorSize.getWidth();
                return (float) (2 * Math.atan(sensorWidth / (2 * focalLength)) * 180 / Math.PI);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get horizontal FOV", e);
        }
        return 0f;
    }

    // ---------------------------------------------------------------------------------------------
    // Tap to focus
    // ---------------------------------------------------------------------------------------------

    /** Focuses on a point given in pixels relative to the preview area (the Preview container). */
    public void setFocus(final int pointX, final int pointY) {
        final Preview preview = mPreview;
        if (preview == null || preview.getCameraDevice() == null) {
            reportFocusError("Camera not available");
            return;
        }
        runOnCameraThread(new Runnable() {
            @Override
            public void run() {
                setFocusOnCameraThread(preview, pointX, pointY);
            }
        });
    }

    private void setFocusOnCameraThread(final Preview preview, final int pointX, final int pointY) {
        final CameraCaptureSession session = preview.getCaptureSession();
        final CaptureRequest.Builder builder = preview.getPreviewRequestBuilder();
        CameraCharacteristics chars = preview.getCharacteristics();
        if (session == null || builder == null || chars == null) {
            reportFocusError("Camera not ready");
            return;
        }
        final Handler handler = cameraHandler();

        try {
            MeteringRectangle region = computeMeteringRectangle(preview, chars, builder, pointX, pointY);
            if (region != null) {
                MeteringRectangle[] regions = new MeteringRectangle[]{region};
                if (maxRegions(chars, CameraCharacteristics.CONTROL_MAX_REGIONS_AF) > 0) {
                    builder.set(CaptureRequest.CONTROL_AF_REGIONS, regions);
                    focusRegions = regions;
                }
                if (maxRegions(chars, CameraCharacteristics.CONTROL_MAX_REGIONS_AE) > 0) {
                    builder.set(CaptureRequest.CONTROL_AE_REGIONS, regions);
                }
            }

            if (!supportsAfMode(chars, CaptureRequest.CONTROL_AF_MODE_AUTO)) {
                // Fixed focus camera: only the metering region can be applied.
                preview.updatePreviewRequest();
                reportFocusSet(pointX, pointY);
                return;
            }

            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);

            // Cancel any running AF sequence, then trigger a new one at the tapped region.
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
            session.capture(builder.build(), null, handler);
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            session.capture(builder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureFailed(CameraCaptureSession s, CaptureRequest request, CaptureFailure failure) {
                    reportFocusError("Focus failed");
                }
            }, handler);
            // The trigger is a one-shot; the repeating request must not keep re-triggering.
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);

            final boolean[] reported = {false};
            final Runnable report = new Runnable() {
                @Override
                public void run() {
                    synchronized (reported) {
                        if (reported[0]) return;
                        reported[0] = true;
                    }
                    preview.updatePreviewRequest();
                    reportFocusSet(pointX, pointY);
                }
            };
            preview.updatePreviewRequest(new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request, TotalCaptureResult result) {
                    Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                    if (afState == null
                            || afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                            || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                        report.run();
                    }
                }
            });
            handler.postDelayed(report, FOCUS_TIMEOUT_MS);
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            Log.e(TAG, "Failed to set focus", e);
            reportFocusError("Failed to set focus: " + e.getMessage());
        }
    }

    /** Maps a point in preview area coordinates to a sensor-space metering rectangle. */
    private MeteringRectangle computeMeteringRectangle(Preview preview, CameraCharacteristics chars,
            CaptureRequest.Builder builder, int pointX, int pointY) {
        TextureView textureView = preview.getTextureView();
        int viewWidth = textureView.getWidth();
        int viewHeight = textureView.getHeight();
        Rect sensor = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (viewWidth <= 0 || viewHeight <= 0 || sensor == null) {
            return null;
        }
        Rect crop = builder.get(CaptureRequest.SCALER_CROP_REGION);
        if (crop == null) {
            crop = sensor;
        }

        // The texture view is centered in the preview area and larger than it in cover mode.
        int viewX = pointX - textureView.getLeft();
        int viewY = pointY - textureView.getTop();
        float nx = clamp01(viewX / (float) viewWidth);
        float ny = clamp01(viewY / (float) viewHeight);

        int displayRotation = deviceOrientationDegrees(getDisplayRotation());
        int sensorOrientation = preview.getSensorOrientation();
        boolean front = cameraCurrentlyLocked == CameraCharacteristics.LENS_FACING_FRONT;
        int rotation;
        if (front) {
            // The front preview is mirrored, and the device rotation works the other way around.
            nx = 1f - nx;
            rotation = (sensorOrientation + displayRotation) % 360;
        } else {
            rotation = (sensorOrientation - displayRotation + 360) % 360;
        }

        // Undo the rotation that turns the sensor buffer into the upright preview.
        float sx;
        float sy;
        switch (rotation) {
            case 90:  sx = ny;      sy = 1f - nx; break;
            case 180: sx = 1f - nx; sy = 1f - ny; break;
            case 270: sx = 1f - ny; sy = nx;      break;
            default:  sx = nx;      sy = ny;      break;
        }

        int regionWidth = Math.max(1, crop.width() / 10);
        int regionHeight = Math.max(1, crop.height() / 10);
        int centerX = crop.left + (int) (sx * crop.width());
        int centerY = crop.top + (int) (sy * crop.height());
        int left = clamp(centerX - regionWidth / 2, sensor.left, Math.max(sensor.left, sensor.right - regionWidth));
        int top = clamp(centerY - regionHeight / 2, sensor.top, Math.max(sensor.top, sensor.bottom - regionHeight));
        return new MeteringRectangle(left, top, regionWidth, regionHeight, MeteringRectangle.METERING_WEIGHT_MAX - 1);
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void reportFocusSet(final int pointX, final int pointY) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (eventListener != null) eventListener.onFocusSet(pointX, pointY);
            }
        });
    }

    private void reportFocusError(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (eventListener != null) eventListener.onFocusSetError(message);
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Still capture
    // ---------------------------------------------------------------------------------------------

    private Size[] getJpegSizes() {
        CameraCharacteristics chars = mPreview != null ? mPreview.getCharacteristics() : null;
        if (chars == null) return null;
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        return map != null ? map.getOutputSizes(ImageFormat.JPEG) : null;
    }

    private Size chooseDefaultPictureSize() {
        Size[] jpegSizes = getJpegSizes();
        if (jpegSizes == null || jpegSizes.length == 0) return null;
        Size previewSize = mPreview != null ? mPreview.getPreviewSize() : null;
        Size size = chooseOptimalPictureSize(jpegSizes, previewSize);
        return size != null ? size : largest(jpegSizes);
    }

    private static Size largest(Size[] sizes) {
        Size result = sizes[0];
        for (Size size : sizes) {
            if ((long) size.getWidth() * size.getHeight() > (long) result.getWidth() * result.getHeight()) {
                result = size;
            }
        }
        return result;
    }

    private void ensureImageReaderLocked(Size size) {
        if (mImageReader != null && mImageSize != null && mImageSize.equals(size)) {
            return;
        }
        closeImageReaderLocked();
        Handler handler = cameraHandler();
        try {
            Log.d(TAG, "Creating ImageReader " + size.getWidth() + "x" + size.getHeight());
            mImageReader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 2);
            mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, handler);
            mImageSize = size;
        } catch (Exception e) {
            Log.e(TAG, "Failed to create ImageReader", e);
            mImageReader = null;
            mImageSize = null;
        }
    }

    private void closeImageReader() {
        synchronized (mImageReaderLock) {
            closeImageReaderLocked();
        }
    }

    /**
     * Closes the ImageReader on the camera thread. Preview.closeCamera() closes the device there,
     * so the reader goes after the camera that writes into it and before the next camera opens.
     */
    private void closeImageReaderAfterCamera() {
        cameraHandler().post(new Runnable() {
            @Override
            public void run() {
                closeImageReader();
            }
        });
    }

    private void closeImageReaderLocked() {
        if (mImageReader != null) {
            try {
                mImageReader.close();
            } catch (Exception e) {
                Log.w(TAG, "Error closing ImageReader", e);
            }
            mImageReader = null;
        }
        mImageSize = null;
    }

    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            byte[] bytes = null;
            try {
                Image image = reader.acquireLatestImage();
                if (image == null) {
                    return;
                }
                try {
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                } finally {
                    image.close();
                }
            } catch (IllegalStateException e) {
                Log.w(TAG, "ImageReader was closed before the image could be read", e);
            } catch (Exception e) {
                Log.e(TAG, "Failed to read captured image", e);
            }
            if (bytes == null) {
                reportPictureError("Failed to read captured image");
                return;
            }
            processCapturedImage(bytes);
        }
    };

    private void processCapturedImage(byte[] bytes) {
        try {
            byte[] output = disableExifHeaderStripping ? bytes : normalizeOrientation(bytes);
            if (storeToFile) {
                String path = getTempFilePath();
                if (path == null) {
                    reportPictureError("Could not create temporary file");
                    return;
                }
                FileOutputStream out = new FileOutputStream(path);
                try {
                    out.write(output);
                } finally {
                    out.close();
                }
                deliverPicture(path);
            } else {
                deliverPicture(Base64.encodeToString(output, Base64.NO_WRAP));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving image", e);
            reportPictureError("Error saving image: " + e.getMessage());
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "Out of memory while processing image", e);
            reportPictureError("Out of memory while processing image");
        }
    }

    /**
     * Rotates the JPEG according to its EXIF orientation so the pixels are upright, and mirrors
     * front camera pictures so they match the (mirrored) preview.
     */
    private byte[] normalizeOrientation(byte[] bytes) throws IOException {
        ExifInterface exifInterface = new ExifInterface(new ByteArrayInputStream(bytes));
        int orientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        int degrees = exifToDegrees(orientation);
        boolean mirror = cameraCurrentlyLocked == CameraCharacteristics.LENS_FACING_FRONT;

        if (degrees == 0 && !mirror) {
            return bytes;
        }

        Matrix matrix = new Matrix();
        if (degrees != 0) {
            matrix.postRotate(degrees);
        }
        if (mirror) {
            matrix.postScale(-1f, 1f);
        }

        Bitmap source = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (source == null) {
            return bytes;
        }
        Bitmap transformed = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
        if (transformed != source) {
            source.recycle();
        }
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        transformed.compress(Bitmap.CompressFormat.JPEG, currentQuality, outputStream);
        transformed.recycle();
        return outputStream.toByteArray();
    }

    private void deliverPicture(final String data) {
        canTakePicture = true;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (eventListener != null) eventListener.onPictureTaken(data);
            }
        });
    }

    private void reportPictureError(final String message) {
        canTakePicture = true;
        reportPictureErrorKeepState(message);
    }

    private void reportPictureErrorKeepState(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (eventListener != null) eventListener.onPictureTakenError(message);
            }
        });
    }

    public void takePicture(final int width, final int height, final int quality) {
        runOnCameraThread(new Runnable() {
            @Override
            public void run() {
                takePictureInternal(width, height, quality, 0);
            }
        });
    }

    private void takePictureInternal(final int width, final int height, final int quality, final int retryCount) {
        final Preview preview = mPreview;
        if (preview == null || preview.getCameraDevice() == null) {
            reportPictureErrorKeepState("Camera not available");
            return;
        }
        if (mRecordingState != RecordingState.IDLE) {
            reportPictureErrorKeepState("Cannot take picture while recording video");
            return;
        }
        if (!canTakePicture || preview.getCaptureSession() == null) {
            if (retryCount < TAKE_PICTURE_MAX_RETRIES) {
                cameraHandler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        takePictureInternal(width, height, quality, retryCount + 1);
                    }
                }, TAKE_PICTURE_RETRY_DELAY_MS);
            } else {
                Log.w(TAG, "takePicture - camera still busy after " + TAKE_PICTURE_MAX_RETRIES + " retries");
                reportPictureErrorKeepState("Camera is busy, could not take picture");
            }
            return;
        }

        canTakePicture = false;
        currentQuality = quality;

        boolean needsNewSession;
        Surface readerSurface;
        synchronized (mImageReaderLock) {
            Size[] jpegSizes = getJpegSizes();
            if (jpegSizes == null || jpegSizes.length == 0) {
                reportPictureError("No picture sizes available");
                return;
            }
            Size optimalSize;
            if (width > 0 && height > 0) {
                optimalSize = getOptimalPictureSize(width, height, preview.getPreviewSize(), Arrays.asList(jpegSizes));
            } else {
                optimalSize = chooseDefaultPictureSize();
            }
            if (optimalSize == null) {
                optimalSize = largest(jpegSizes);
            }
            needsNewSession = mImageReader == null || mImageSize == null || !mImageSize.equals(optimalSize);
            if (needsNewSession) {
                Log.d(TAG, "takePicture - ImageReader size " + mImageSize + " -> " + optimalSize + ", recreating session");
                ensureImageReaderLocked(optimalSize);
            }
            if (mImageReader == null) {
                reportPictureError("Could not create image reader");
                return;
            }
            readerSurface = mImageReader.getSurface();
        }

        if (needsNewSession) {
            preview.createCameraPreviewSession(Collections.singletonList(readerSurface), new Runnable() {
                @Override
                public void run() {
                    captureStillPicture();
                }
            });
        } else {
            captureStillPicture();
        }
    }

    private void captureStillPicture() {
        final Preview preview = mPreview;
        if (preview == null) {
            reportPictureError("Camera not available");
            return;
        }
        final CameraCaptureSession session = preview.getCaptureSession();
        final CameraDevice device = preview.getCameraDevice();
        final CaptureRequest.Builder previewBuilder = preview.getPreviewRequestBuilder();
        final Surface target;
        synchronized (mImageReaderLock) {
            target = mImageReader != null ? mImageReader.getSurface() : null;
        }
        if (session == null || device == null || target == null) {
            reportPictureError("Camera not ready");
            return;
        }

        boolean needsPrecapture = preview.isFlashSupported() && previewBuilder != null
                && ("on".equals(currentFlashMode) || "auto".equals(currentFlashMode) || "red-eye".equals(currentFlashMode));

        if (needsPrecapture) {
            runPrecaptureSequence(preview, session, previewBuilder, new Runnable() {
                @Override
                public void run() {
                    submitStillCapture(preview, session, device, target);
                }
            });
        } else {
            submitStillCapture(preview, session, device, target);
        }
    }

    /** Runs the AE precapture metering sequence (needed for flash), then calls {@code then}. */
    private void runPrecaptureSequence(final Preview preview, final CameraCaptureSession session,
            final CaptureRequest.Builder previewBuilder, final Runnable then) {
        final Handler handler = cameraHandler();
        final boolean[] done = {false};
        final Runnable finish = new Runnable() {
            @Override
            public void run() {
                synchronized (done) {
                    if (done[0]) return;
                    done[0] = true;
                }
                preview.updatePreviewRequest();
                then.run();
            }
        };

        CameraCaptureSession.CaptureCallback watcher = new CameraCaptureSession.CaptureCallback() {
            private boolean waitingForPrecapture = true;

            @Override
            public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request, TotalCaptureResult result) {
                Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                if (waitingForPrecapture) {
                    if (aeState == null
                            || aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE
                            || aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        waitingForPrecapture = false;
                    }
                } else if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                    finish.run();
                }
            }
        };

        try {
            previewBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            session.capture(previewBuilder.build(), null, handler);
            previewBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
            preview.updatePreviewRequest(watcher);
            handler.postDelayed(finish, PRECAPTURE_TIMEOUT_MS);
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            Log.w(TAG, "Precapture sequence failed, capturing directly", e);
            finish.run();
        }
    }

    private void submitStillCapture(Preview preview, CameraCaptureSession session, CameraDevice device, Surface target) {
        try {
            CaptureRequest.Builder captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(target);
            applyCurrentSettings(captureBuilder);
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation(getDisplayRotation()));
            captureBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) Math.max(1, Math.min(100, currentQuality)));

            session.capture(captureBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request, TotalCaptureResult result) {
                    Log.d(TAG, "Still capture completed, waiting for image");
                }

                @Override
                public void onCaptureFailed(CameraCaptureSession s, CaptureRequest request, CaptureFailure failure) {
                    Log.e(TAG, "Still capture failed: " + failure.getReason());
                    reportPictureError("Capture failed: " + failure.getReason());
                }
            }, preview.getBackgroundHandler());
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            Log.e(TAG, "Failed to capture still picture", e);
            reportPictureError("Failed to capture picture: " + e.getMessage());
        }
    }

    public void takeSnapshot(final int quality) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mPreview == null || !mPreview.isTextureViewAvailable()) {
                    if (eventListener != null) eventListener.onSnapshotTakenError("Preview not available");
                    return;
                }
                try {
                    Bitmap bitmap = mPreview.getPreviewBitmap();
                    if (bitmap == null) {
                        if (eventListener != null) eventListener.onSnapshotTakenError("Failed to capture bitmap");
                        return;
                    }
                    ByteArrayOutputStream stream = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream);
                    bitmap.recycle();
                    String encodedImage = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP);
                    if (eventListener != null) eventListener.onSnapshotTaken(encodedImage);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to take snapshot", e);
                    if (eventListener != null) eventListener.onSnapshotTakenError("Failed to take snapshot: " + e.getMessage());
                }
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Video recording
    // ---------------------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    public void startRecord(final String filePath, final String camera, final int width, final int height, final int quality, final boolean withFlash) {
        Log.d(TAG, "startRecord camera: " + camera + " width: " + width + ", height: " + height + ", quality: " + quality);

        final Preview preview = mPreview;
        if (preview == null || preview.getCameraDevice() == null) {
            reportRecordStartError("Camera not available");
            return;
        }
        if (mRecordingState != RecordingState.IDLE) {
            reportRecordStartError("Already recording");
            return;
        }
        final Surface previewSurface = preview.getPreviewSurfaceForSession();
        if (previewSurface == null || !previewSurface.isValid()) {
            reportRecordStartError("Preview surface not available");
            return;
        }
        final CameraDevice device = preview.getCameraDevice();

        mRecordingState = RecordingState.STARTING;
        canTakePicture = false;
        recordFilePath = filePath;
        muteStream(true, getActivity());

        try {
            setUpMediaRecorder(preview, width, height);

            CaptureRequest.Builder recordBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            recordBuilder.addTarget(previewSurface);
            recordBuilder.addTarget(mRecorderSurface);
            applyCurrentSettings(recordBuilder);
            if (supportsAfMode(preview.getCharacteristics(), CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
                recordBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            }
            if (withFlash && preview.isFlashSupported()) {
                recordBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                recordBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
            }

            preview.startCustomSession(Arrays.asList(previewSurface, mRecorderSurface), recordBuilder, new Preview.SessionListener() {
                @Override
                public void onReady(CameraCaptureSession session) {
                    try {
                        mRecorder.start();
                        mRecordingState = RecordingState.STARTED;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (eventListener != null) eventListener.onStartRecordVideo();
                            }
                        });
                    } catch (RuntimeException e) {
                        Log.e(TAG, "MediaRecorder.start failed", e);
                        failRecordingStart("Failed to start recording: " + e.getMessage());
                    }
                }

                @Override
                public void onFailed(String error) {
                    failRecordingStart(error);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to set up recording", e);
            failRecordingStart("Failed to set up recording: " + e.getMessage());
        }
    }

    private void failRecordingStart(String message) {
        releaseRecorder();
        mRecordingState = RecordingState.IDLE;
        muteStream(false, getActivity());
        restorePreviewAfterRecording();
        reportRecordStartError(message);
    }

    private void reportRecordStartError(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (eventListener != null) eventListener.onStartRecordVideoError(message);
            }
        });
    }

    private void restorePreviewAfterRecording() {
        Preview preview = mPreview;
        if (preview != null && preview.getCameraDevice() != null) {
            preview.recreatePreviewSession(new Runnable() {
                @Override
                public void run() {
                    canTakePicture = true;
                }
            });
        } else {
            canTakePicture = true;
        }
    }

    private void setUpMediaRecorder(Preview preview, int requestedWidth, int requestedHeight) throws IOException {
        releaseRecorder();

        MediaRecorder recorder = new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setOutputFile(recordFilePath);

        CamcorderProfile profile = findCamcorderProfile();
        Size videoSize = chooseVideoSize(preview, requestedWidth, requestedHeight, profile);
        int bitRate = profile != null && profile.videoBitRate > 0
                ? profile.videoBitRate
                : videoSize.getWidth() * videoSize.getHeight() * 4;
        int frameRate = profile != null && profile.videoFrameRate > 0 ? profile.videoFrameRate : 30;

        recorder.setVideoEncodingBitRate(bitRate);
        recorder.setVideoFrameRate(frameRate);
        recorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        if (profile != null) {
            if (profile.audioBitRate > 0) recorder.setAudioEncodingBitRate(profile.audioBitRate);
            if (profile.audioSampleRate > 0) recorder.setAudioSamplingRate(profile.audioSampleRate);
            if (profile.audioChannels > 0) recorder.setAudioChannels(profile.audioChannels);
        }
        recorder.setOrientationHint(getJpegOrientation(getDisplayRotation()));

        Log.d(TAG, "MediaRecorder: " + videoSize.getWidth() + "x" + videoSize.getHeight() + " @" + frameRate + "fps, " + bitRate + "bps");
        recorder.prepare();
        mRecorderSurface = recorder.getSurface();
        mRecorder = recorder;
    }

    private CamcorderProfile findCamcorderProfile() {
        try {
            int cameraIndex = Integer.parseInt(mCameraId);
            if (CamcorderProfile.hasProfile(cameraIndex, CamcorderProfile.QUALITY_HIGH)) {
                return CamcorderProfile.get(cameraIndex, CamcorderProfile.QUALITY_HIGH);
            }
        } catch (RuntimeException e) {
            Log.d(TAG, "No camcorder profile for camera " + mCameraId + ": " + e.getMessage());
        }
        try {
            if (CamcorderProfile.hasProfile(CamcorderProfile.QUALITY_HIGH)) {
                return CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH);
            }
        } catch (RuntimeException e) {
            Log.d(TAG, "No default camcorder profile: " + e.getMessage());
        }
        return null;
    }

    private Size chooseVideoSize(Preview preview, int requestedWidth, int requestedHeight, CamcorderProfile profile) {
        CameraCharacteristics chars = preview.getCharacteristics();
        StreamConfigurationMap map = chars != null ? chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) : null;
        Size[] choices = map != null ? map.getOutputSizes(MediaRecorder.class) : null;
        if (choices == null || choices.length == 0) {
            return profile != null ? new Size(profile.videoFrameWidth, profile.videoFrameHeight) : new Size(1280, 720);
        }

        List<Size> candidates = new ArrayList<>();
        for (Size size : choices) {
            if (size.getWidth() <= MAX_VIDEO_WIDTH && size.getHeight() <= MAX_VIDEO_HEIGHT) {
                candidates.add(size);
            }
        }
        if (candidates.isEmpty()) {
            candidates.addAll(Arrays.asList(choices));
        }

        if (requestedWidth > 0 && requestedHeight > 0) {
            long requestedArea = (long) Math.max(requestedWidth, requestedHeight) * Math.min(requestedWidth, requestedHeight);
            Size best = candidates.get(0);
            long bestDiff = Long.MAX_VALUE;
            for (Size size : candidates) {
                long diff = Math.abs((long) size.getWidth() * size.getHeight() - requestedArea);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = size;
                }
            }
            return best;
        }

        if (profile != null) {
            for (Size size : candidates) {
                if (size.getWidth() == profile.videoFrameWidth && size.getHeight() == profile.videoFrameHeight) {
                    return size;
                }
            }
        }

        Size previewSize = preview.getPreviewSize();
        Size best = null;
        if (previewSize != null) {
            double previewRatio = (double) previewSize.getWidth() / previewSize.getHeight();
            for (Size size : candidates) {
                double ratio = (double) size.getWidth() / size.getHeight();
                if (Math.abs(ratio - previewRatio) <= ASPECT_TOLERANCE
                        && (best == null || (long) size.getWidth() * size.getHeight() > (long) best.getWidth() * best.getHeight())) {
                    best = size;
                }
            }
        }
        return best != null ? best : largest(candidates.toArray(new Size[0]));
    }

    public void stopRecord() {
        Log.d(TAG, "stopRecord");
        if (mRecordingState != RecordingState.STARTED) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (eventListener != null) eventListener.onStopRecordVideoError("Not recording");
                }
            });
            return;
        }
        mRecordingState = RecordingState.STOPPING;
        muteStream(false, getActivity());

        Preview preview = mPreview;
        try {
            CameraCaptureSession session = preview != null ? preview.getCaptureSession() : null;
            if (session != null) {
                session.stopRepeating();
                session.abortCaptures();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to stop recording session", e);
        }

        final String file = recordFilePath;
        String error = null;
        try {
            if (mRecorder != null) {
                mRecorder.stop();
            } else {
                error = "Recorder not available";
            }
        } catch (RuntimeException e) {
            // Thrown when no valid data was recorded (e.g. stopped right after start)
            Log.e(TAG, "MediaRecorder.stop failed", e);
            error = "Failed to stop recording: " + e.getMessage();
        }
        releaseRecorder();
        mRecordingState = RecordingState.IDLE;

        restorePreviewAfterRecording();

        final String finalError = error;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (eventListener == null) return;
                if (finalError == null) {
                    eventListener.onStopRecordVideo(file);
                } else {
                    eventListener.onStopRecordVideoError(finalError);
                }
            }
        });
    }

    private void releaseRecorder() {
        if (mRecorder != null) {
            try {
                mRecorder.reset();
            } catch (Exception ignored) {
            }
            try {
                mRecorder.release();
            } catch (Exception ignored) {
            }
            mRecorder = null;
        }
        if (mRecorderSurface != null) {
            try {
                mRecorderSurface.release();
            } catch (Exception ignored) {
            }
            mRecorderSurface = null;
        }
    }

    private void muteStream(boolean mute, Activity activity) {
        if (activity == null) return;
        try {
            AudioManager audioManager = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                int flag = mute ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE;
                audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, flag, 0);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to change system stream mute state", e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Orientation helpers
    // ---------------------------------------------------------------------------------------------

    private int getDisplayRotation() {
        Activity activity = getActivity();
        if (activity == null) return Surface.ROTATION_0;
        return activity.getWindowManager().getDefaultDisplay().getRotation();
    }

    /** Converts a Surface.ROTATION_* value to the clockwise device orientation in degrees. */
    private static int deviceOrientationDegrees(int rotation) {
        return (ORIENTATIONS.get(rotation) + 270) % 360;
    }

    /**
     * Clockwise rotation to apply to the sensor image so it is upright for the current device
     * orientation. Front cameras rotate the opposite way (see CameraCharacteristics docs).
     */
    private int getJpegOrientation(int rotation) {
        int sensorOrientation = mPreview != null ? mPreview.getSensorOrientation() : 0;
        int deviceOrientation = deviceOrientationDegrees(rotation);
        if (cameraCurrentlyLocked == CameraCharacteristics.LENS_FACING_FRONT) {
            return (sensorOrientation - deviceOrientation + 360) % 360;
        }
        return (sensorOrientation + deviceOrientation) % 360;
    }

    private static int exifToDegrees(int exifOrientation) {
        if (exifOrientation == ExifInterface.ORIENTATION_ROTATE_90) {
            return 90;
        } else if (exifOrientation == ExifInterface.ORIENTATION_ROTATE_180) {
            return 180;
        } else if (exifOrientation == ExifInterface.ORIENTATION_ROTATE_270) {
            return 270;
        }
        return 0;
    }

    // ---------------------------------------------------------------------------------------------
    // Size helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Picture size for takePicture(width, height): among the JPEG sizes with the aspect ratio
     * closest to the preview, the smallest one covering the requested size, otherwise the largest.
     * Keeping the still stream at the preview's ratio matters because the session carries both
     * streams and some camera HALs stretch the preview when their ratios differ.
     */
    private Size getOptimalPictureSize(int width, int height, Size previewSize, List<Size> sizes) {
        if (sizes == null || sizes.isEmpty()) {
            return previewSize;
        }
        List<Size> candidates = closestRatioSizes(sizes.toArray(new Size[0]), previewSize);
        if (width == 0 || height == 0) {
            return largest(candidates.toArray(new Size[0]));
        }

        // Sensor sizes are landscape; compare in the same orientation.
        int wantedWidth = Math.max(width, height);
        int wantedHeight = Math.min(width, height);

        // Prefer the smallest size that is big enough in both dimensions.
        Size optimalSize = null;
        long minArea = Long.MAX_VALUE;
        for (Size size : candidates) {
            if (size.getWidth() >= wantedWidth && size.getHeight() >= wantedHeight) {
                long area = (long) size.getWidth() * size.getHeight();
                if (area < minArea) {
                    optimalSize = size;
                    minArea = area;
                }
            }
        }
        // Otherwise the largest available.
        if (optimalSize == null) {
            optimalSize = largest(candidates.toArray(new Size[0]));
        }
        return optimalSize;
    }

    /** Largest JPEG size with the aspect ratio closest to the preview's. */
    private Size chooseOptimalPictureSize(Size[] jpegSizes, Size previewSize) {
        if (jpegSizes == null || jpegSizes.length == 0 || previewSize == null) {
            return null;
        }
        return largest(closestRatioSizes(jpegSizes, previewSize).toArray(new Size[0]));
    }

    /** The sizes whose aspect ratio is (nearly) the closest to the reference size's. */
    private static List<Size> closestRatioSizes(Size[] sizes, Size reference) {
        List<Size> result = new ArrayList<>();
        if (reference == null) {
            result.addAll(Arrays.asList(sizes));
            return result;
        }
        double target = (double) reference.getWidth() / reference.getHeight();
        double bestDiff = Double.MAX_VALUE;
        for (Size size : sizes) {
            bestDiff = Math.min(bestDiff, Math.abs((double) size.getWidth() / size.getHeight() - target));
        }
        if (bestDiff > 0.03) {
            Log.w(TAG, "No picture size with the aspect ratio of the preview " + reference + ", using the closest");
        }
        for (Size size : sizes) {
            if (Math.abs((double) size.getWidth() / size.getHeight() - target) - bestDiff <= 0.02) {
                result.add(size);
            }
        }
        return result;
    }

    // ---------------------------------------------------------------------------------------------
    // Misc helpers
    // ---------------------------------------------------------------------------------------------

    private String getTempFilePath() {
        Activity activity = getActivity();
        if (activity == null) return null;
        File outputDir = activity.getCacheDir();
        try {
            return File.createTempFile("picture", ".jpg", outputDir).getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "Failed to create temp file", e);
            return null;
        }
    }

    private Handler cameraHandler() {
        Handler handler = mPreview != null ? mPreview.getBackgroundHandler() : null;
        return handler != null ? handler : mMainHandler;
    }

    private void runOnCameraThread(Runnable runnable) {
        Handler handler = mPreview != null ? mPreview.getBackgroundHandler() : null;
        if (handler != null && Thread.currentThread() != handler.getLooper().getThread()) {
            handler.post(runnable);
        } else {
            runnable.run();
        }
    }

    private void runOnUiThread(Runnable runnable) {
        Activity activity = getActivity();
        if (activity != null) {
            activity.runOnUiThread(runnable);
        } else {
            mMainHandler.post(runnable);
        }
    }

    public Preview getPreview() {
        return mPreview;
    }
}
