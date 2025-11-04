package com.cordovaplugincamerapreview;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.media.AudioManager;
import android.media.CamcorderProfile;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
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
import java.util.List;
import java.util.concurrent.Semaphore;

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

    private static final int STATE_PREVIEW = 0;

    private CameraPreviewListener eventListener;
    public FrameLayout mainLayout;
    public FrameLayout frameContainerLayout;

    private Preview mPreview;
    private boolean canTakePicture = true;

    private View view;
    private CameraManager mCameraManager;
    private String mCameraId;
    private CameraCharacteristics mCharacteristics;
    private Size mImageSize;
    private ImageReader mImageReader;
    private File mFile;
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;
    private int mSensorOrientation;
    private boolean mFlashSupported;
    private int mState = STATE_PREVIEW;
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);
    private final Object mImageReaderLock = new Object(); // Lock for ImageReader access
    private String currentFlashMode = "auto"; // Track current flash mode

    private int numberOfCameras;
    private int cameraCurrentlyLocked;
    private int currentQuality;

    // Focal length switching variables
    private float[] availableFocalLengths;
    private int currentFocalLengthIndex = 0;

    // Map of focal lengths to camera IDs for multi-camera support
    private java.util.Map<Float, String> focalLengthToCameraId = new java.util.HashMap<>();

    // The first rear facing camera
    private String defaultCameraId;
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

    private enum RecordingState {INITIALIZING, STARTED, STOPPED}

    private RecordingState mRecordingState = RecordingState.INITIALIZING;
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

    private String appResourcesPackage;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        appResourcesPackage = getActivity().getPackageName();

        // Inflate the layout for this fragment
        view = inflater.inflate(getResources().getIdentifier("camera_activity", "layout", appResourcesPackage), container, false);

        // Initialize frameContainerLayout before calling createCameraPreview
        frameContainerLayout = (FrameLayout) view.findViewById(getResources().getIdentifier("frame_container", "id", appResourcesPackage));

        createCameraPreview();
        return view;
    }

    private void createCameraPreview() {
        if (mPreview == null) {
            setDefaultCameraId();
        }

        // Check if frameContainerLayout is available
        if (frameContainerLayout == null) {
            Log.e(TAG, "frameContainerLayout is null, cannot create camera preview");
            return;
        }

        if (mPreview != null) {
            frameContainerLayout.removeView(mPreview);
        }

        Activity activity = getActivity();
        if (activity == null) {
            Log.e(TAG, "Activity is null, cannot create camera preview");
            return;
        }

        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        Log.d(TAG, "createCameraPreview - Input: x=" + x + ", y=" + y + ", width=" + width + ", height=" + height);
        Log.d(TAG, "createCameraPreview - Screen: width=" + metrics.widthPixels + ", height=" + metrics.heightPixels);

        FrameLayout.LayoutParams layoutParams;

        // Use full screen if width/height are 0 or close to screen size (within 10% tolerance)
        boolean isFullScreenWidth = (width == 0) || (width >= metrics.widthPixels * 0.9);
        boolean isFullScreenHeight = (height == 0) || (height >= metrics.heightPixels * 0.9);

        if (isFullScreenWidth && isFullScreenHeight) {
            // Use MATCH_PARENT for full screen
            layoutParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            );
            layoutParams.setMargins(0, 0, 0, 0);
            Log.d(TAG, "createCameraPreview - Using MATCH_PARENT for full screen");
        } else {
            // Use custom dimensions
            layoutParams = new FrameLayout.LayoutParams(width, height);
            layoutParams.setMargins(x, y, 0, 0);
            Log.d(TAG, "createCameraPreview - Using custom dimensions: " + width + "x" + height + " at (" + x + "," + y + ")");
        }
        mPreview = new Preview(getActivity());
        mPreview.setLayoutParams(layoutParams);
        mPreview.setPreviewCallback(this);

        frameContainerLayout.addView(mPreview);
        mPreview.setCamera(mCameraId);

        if (toBack && frameContainerLayout != null) {
            frameContainerLayout.setZ(-1);
        }

        mPreview.startBackgroundThread();
        mPreview.resumePreview();

        // Set up touch handling after preview is created
        setupTouchHandling();
    }

    private void setDefaultCameraId() {
        if (mCameraManager == null) {
            mCameraManager = (CameraManager) getActivity().getSystemService(Context.CAMERA_SERVICE);
        }

        try {
            for (String cameraId : mCameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(cameraId);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);

                if (defaultCamera.equals("front") && facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    mCameraId = cameraId;
                    cameraCurrentlyLocked = CameraCharacteristics.LENS_FACING_FRONT;
                    initializeFocalLengths(characteristics);
                    break;
                } else if (defaultCamera.equals("back") && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    mCameraId = cameraId;
                    cameraCurrentlyLocked = CameraCharacteristics.LENS_FACING_BACK;
                    initializeFocalLengths(characteristics);
                    break;
                }
            }

            if (mCameraId == null) {
                // Fallback to first available camera
                String[] cameraIds = mCameraManager.getCameraIdList();
                if (cameraIds.length > 0) {
                    mCameraId = cameraIds[0];
                    CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(mCameraId);
                    Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                    cameraCurrentlyLocked = facing != null ? facing : CameraCharacteristics.LENS_FACING_BACK;
                    initializeFocalLengths(characteristics);
                }
            }

            defaultCameraId = mCameraId;
            numberOfCameras = mCameraManager.getCameraIdList().length;

        } catch (CameraAccessException e) {
            Log.e(TAG, "Cannot access camera", e);
        }
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Log.d(TAG, "onViewCreated - tapToFocus=" + tapToFocus + ", tapToTakePicture=" + tapToTakePicture + ", dragEnabled=" + dragEnabled);

        // Set up touch handling after the preview is created
        setupTouchHandling();
    }

    private void setupTouchHandling() {
        if (frameContainerLayout == null || mPreview == null) {
            Log.w(TAG, "setupTouchHandling - frameContainerLayout or mPreview is null");
            return;
        }

        Log.d(TAG, "setupTouchHandling - Setting up touch handlers");

        // Create a unified touch handler that supports tap-to-focus, tap-to-take-picture, and drag
        final TapGestureDetector tapGestureDetector = new TapGestureDetector(getActivity(), new TapGestureDetector.OnTapListener() {
            @Override
            public void onTap(MotionEvent e) {
                Log.d(TAG, "onTap callback - x=" + e.getX() + ", y=" + e.getY());
                // Handle tap to focus
                if (tapToFocus) {
                    Log.d(TAG, "Calling setFocus");
                    setFocus((int) e.getX(), (int) e.getY());
                }
                // Handle tap to take picture
                if (tapToTakePicture && canTakePicture) {
                    Log.d(TAG, "Calling takePicture");
                    takePicture(0, 0, 85);
                }
            }
        });

        if (dragEnabled) {
            Log.d(TAG, "Setting up combined drag and tap handler on Preview");
            // Combine drag and tap handling on the preview (for dragging the whole preview)
            mPreview.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    int action = event.getAction();
                    Log.d(TAG, "Combined handler - action=" + action);
                    if (action == MotionEvent.ACTION_MOVE) {
                        frameContainerLayout.setX(event.getRawX() - frameContainerLayout.getWidth() / 2);
                        frameContainerLayout.setY(event.getRawY() - frameContainerLayout.getHeight() / 2);
                        return true;
                    } else {
                        // For non-move events, let the tap gesture detector handle it
                        return tapGestureDetector.onTouch(v, event);
                    }
                }
            });
        } else {
            Log.d(TAG, "Setting up tap gesture detector on Preview");
            // Set the tap gesture detector on the preview
            // This ensures touch events on the camera preview are captured
            mPreview.setOnTouchListener(tapGestureDetector);
        }

        // Make sure the preview is clickable so it receives touch events
        mPreview.setClickable(true);
        mPreview.setFocusable(true);

        Log.d(TAG, "Touch listener setup complete");
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
        if (mPreview != null) {
            mPreview.pausePreview();
            mPreview.stopBackgroundThread();
        }
        super.onPause();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Log.d(TAG, "onConfigurationChanged");

        // Update preview layout to fit the new screen dimensions
        if (mPreview != null && frameContainerLayout != null) {
            updatePreviewLayout();
        }
    }

    private void updatePreviewLayout() {
        if (mPreview == null || frameContainerLayout == null) {
            return;
        }

        Activity activity = getActivity();
        if (activity == null) {
            return;
        }

        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        Log.d(TAG, "updatePreviewLayout - Current dimensions: x=" + x + ", y=" + y + ", width=" + width + ", height=" + height);

        FrameLayout.LayoutParams layoutParams;

        // Use full screen if width/height are 0 or close to screen size (within 10% tolerance)
        boolean isFullScreenWidth = (width == 0) || (width >= metrics.widthPixels * 0.9);
        boolean isFullScreenHeight = (height == 0) || (height >= metrics.heightPixels * 0.9);

        if (isFullScreenWidth && isFullScreenHeight) {
            // Use MATCH_PARENT for full screen
            layoutParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            );
            layoutParams.setMargins(0, 0, 0, 0);
            Log.d(TAG, "updatePreviewLayout - Using MATCH_PARENT for full screen");
        } else {
            // Keep custom dimensions
            layoutParams = new FrameLayout.LayoutParams(width, height);
            layoutParams.setMargins(x, y, 0, 0);
            Log.d(TAG, "updatePreviewLayout - Using custom dimensions: " + width + "x" + height);
        }

        mPreview.setLayoutParams(layoutParams);
        mPreview.requestLayout();
    }

    @Override
    public void onCameraDeviceOpened() {
        Log.d(TAG, "Camera device opened");

        // Set up ImageReader before the session is created
        try {
            synchronized (mImageReaderLock) {
                // Close existing ImageReader if any
                if (mImageReader != null) {
                    mImageReader.close();
                    mImageReader = null;
                }

                CameraCharacteristics characteristics = mPreview.getCharacteristics();
                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map != null) {
                    Size[] jpegSizes = map.getOutputSizes(ImageFormat.JPEG);
                    Size previewSize = mPreview.getPreviewSize();

                    // Choose an optimal picture size based on preview size
                    // This ensures the aspect ratio matches and the size is appropriate
                    if (previewSize != null) {
                        // Find a JPEG size that matches or is close to the preview aspect ratio
                        // but is larger for better quality
                        mImageSize = chooseOptimalPictureSize(jpegSizes, previewSize);
                    } else {
                        // Fallback: use the largest available size
                        mImageSize = jpegSizes[0];
                        for (Size size : jpegSizes) {
                            if (size.getWidth() * size.getHeight() > mImageSize.getWidth() * mImageSize.getHeight()) {
                                mImageSize = size;
                            }
                        }
                    }

                    Log.d(TAG, "onCameraDeviceOpened - Creating ImageReader with size: " + mImageSize.getWidth() + "x" + mImageSize.getHeight());

                    mImageReader = ImageReader.newInstance(mImageSize.getWidth(), mImageSize.getHeight(), ImageFormat.JPEG, 2);
                    mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mPreview.getBackgroundHandler());

                    // Create the session with both preview and ImageReader surfaces
                    List<Surface> additionalSurfaces = Arrays.asList(mImageReader.getSurface());
                    mPreview.createCameraPreviewSession(additionalSurfaces);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to setup ImageReader", e);
            // Fall back to creating session without ImageReader
            mPreview.createCameraPreviewSession(null);
        }
    }

    @Override
    public void onCameraOpened() {
        Log.d(TAG, "Camera opened - session is ready");

        // Don't set up ImageReader here - it causes race condition with Preview's session creation
        // Instead, set it up on-demand in takePicture()

        // Re-enable taking pictures now that camera is ready
        canTakePicture = true;

        if (eventListener != null) {
            eventListener.onCameraStarted();
        }
    }

    @Override
    public void onCameraError(String error) {
        Log.e(TAG, "Camera error: " + error);
        if (eventListener != null) {
            eventListener.onPictureTakenError(error);
        }
    }

    public void switchCamera() {
        Log.d(TAG, "switchCamera");

        // Disable taking pictures while camera is switching
        canTakePicture = false;

        // Close ImageReader since we're switching cameras
        synchronized (mImageReaderLock) {
            if (mImageReader != null) {
                mImageReader.close();
                mImageReader = null;
                mImageSize = null;
            }
        }

        if (mPreview != null) {
            mPreview.closeCamera();
        }

        try {
            for (String cameraId : mCameraManager.getCameraIdList()) {
                if (!cameraId.equals(mCameraId)) {
                    CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(cameraId);
                    Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);

                    if (facing != null && facing != cameraCurrentlyLocked) {
                        mCameraId = cameraId;
                        cameraCurrentlyLocked = facing;
                        initializeFocalLengths(characteristics);
                        mPreview.setCamera(mCameraId);
                        mPreview.resumePreview();
                        // canTakePicture will be set to true in onCameraOpened() callback
                        break;
                    }
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Cannot switch camera", e);
            canTakePicture = true; // Re-enable on error
        }
    }

    private void initializeFocalLengths(CameraCharacteristics characteristics) {
        try {
            // Clear previous mappings
            focalLengthToCameraId.clear();
            java.util.List<Float> allFocalLengths = new java.util.ArrayList<>();
            java.util.Set<String> processedCameraIds = new java.util.HashSet<>();

            // Get the facing direction of the current camera
            Integer currentFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (currentFacing == null) {
                Log.e(TAG, "Cannot determine camera facing direction");
                return;
            }

            Log.d(TAG, "Initializing focal lengths for cameras with facing: " +
                  (currentFacing == CameraCharacteristics.LENS_FACING_BACK ? "BACK" : "FRONT"));

            // First pass: Get all cameras from getCameraIdList() with the same facing direction
            String[] cameraIdList = mCameraManager.getCameraIdList();
            Log.d(TAG, "getCameraIdList() returned " + cameraIdList.length + " cameras");

            for (String cameraId : cameraIdList) {
                processedCameraIds.add(cameraId);
                CameraCharacteristics chars = mCameraManager.getCameraCharacteristics(cameraId);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);

                // Only include cameras with the same facing direction as the current camera
                if (facing != null && facing.equals(currentFacing)) {
                    float[] focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                    if (focalLengths != null && focalLengths.length > 0) {
                        // For each focal length, map it to this camera ID
                        for (float focalLength : focalLengths) {
                            // Round to 2 decimal places to avoid floating point precision issues
                            float roundedFocalLength = Math.round(focalLength * 100f) / 100f;
                            if (!focalLengthToCameraId.containsKey(roundedFocalLength)) {
                                focalLengthToCameraId.put(roundedFocalLength, cameraId);
                                allFocalLengths.add(roundedFocalLength);
                                Log.d(TAG, "Mapped focal length " + roundedFocalLength + "mm to camera " + cameraId);
                            }
                        }
                    }
                }
            }

            // Second pass: Probe for hidden camera IDs (0-9)
            Log.d(TAG, "Probing for hidden camera IDs with same facing direction...");
            for (int i = 0; i < 10; i++) {
                String cameraId = String.valueOf(i);

                // Skip if already processed
                if (processedCameraIds.contains(cameraId)) {
                    continue;
                }

                try {
                    CameraCharacteristics chars = mCameraManager.getCameraCharacteristics(cameraId);
                    Integer facing = chars.get(CameraCharacteristics.LENS_FACING);

                    // Only include cameras with the same facing direction as the current camera
                    if (facing != null && facing.equals(currentFacing)) {
                        Log.d(TAG, "Found hidden camera ID: " + cameraId);

                        float[] focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                        if (focalLengths != null && focalLengths.length > 0) {
                            // For each focal length, map it to this camera ID
                            for (float focalLength : focalLengths) {
                                // Round to 2 decimal places to avoid floating point precision issues
                                float roundedFocalLength = Math.round(focalLength * 100f) / 100f;
                                if (!focalLengthToCameraId.containsKey(roundedFocalLength)) {
                                    focalLengthToCameraId.put(roundedFocalLength, cameraId);
                                    allFocalLengths.add(roundedFocalLength);
                                    Log.d(TAG, "Mapped hidden camera focal length " + roundedFocalLength + "mm to camera " + cameraId);
                                }
                            }
                        }
                    }
                } catch (CameraAccessException | IllegalArgumentException e) {
                    // Camera ID doesn't exist, continue to next
                    Log.d(TAG, "Camera ID " + cameraId + " not available");
                }
            }

            // Sort focal lengths (typically from widest to most telephoto)
            java.util.Collections.sort(allFocalLengths);

            // Convert to array
            availableFocalLengths = new float[allFocalLengths.size()];
            for (int i = 0; i < allFocalLengths.size(); i++) {
                availableFocalLengths[i] = allFocalLengths.get(i);
            }

            // Find the index of the current camera's focal length
            float[] currentFocalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            if (currentFocalLengths != null && currentFocalLengths.length > 0) {
                float currentFocalLength = Math.round(currentFocalLengths[0] * 100f) / 100f;
                for (int i = 0; i < availableFocalLengths.length; i++) {
                    if (Math.abs(availableFocalLengths[i] - currentFocalLength) < 0.01f) {
                        currentFocalLengthIndex = i;
                        break;
                    }
                }
            }

            Log.d(TAG, "Initialized focal lengths: " + java.util.Arrays.toString(availableFocalLengths));
            Log.d(TAG, "Current focal length index: " + currentFocalLengthIndex);
            Log.d(TAG, "Focal length to camera ID mapping: " + focalLengthToCameraId.toString());
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize focal lengths", e);
        }
    }

    public void switchFocalLength() {
        Log.d(TAG, "switchFocalLength");

        if (mPreview == null || availableFocalLengths == null || availableFocalLengths.length <= 1) {
            Log.w(TAG, "Cannot switch focal length: camera not ready or only one focal length available");
            return;
        }

        try {
            // Cycle to next focal length
            currentFocalLengthIndex = (currentFocalLengthIndex + 1) % availableFocalLengths.length;
            float targetFocalLength = availableFocalLengths[currentFocalLengthIndex];

            Log.d(TAG, "Switching to focal length: " + targetFocalLength + "mm (index: " + currentFocalLengthIndex + ")");

            // Get the camera ID for this focal length
            String targetCameraId = focalLengthToCameraId.get(targetFocalLength);

            if (targetCameraId != null && !targetCameraId.equals(mCameraId)) {
                // Need to switch to a different physical camera
                Log.d(TAG, "Switching from camera " + mCameraId + " to camera " + targetCameraId);

                // Disable taking pictures while camera is switching
                canTakePicture = false;

                // Close ImageReader since we're switching cameras
                synchronized (mImageReaderLock) {
                    if (mImageReader != null) {
                        mImageReader.close();
                        mImageReader = null;
                        mImageSize = null;
                    }
                }

                // Close current camera
                if (mPreview != null) {
                    mPreview.closeCamera();
                }

                // Switch to the new camera
                mCameraId = targetCameraId;
                mPreview.setCamera(mCameraId);
                mPreview.resumePreview();

                // canTakePicture will be set to true in onCameraOpened() callback
            } else {
                // Same camera, just use digital zoom
                Log.d(TAG, "Using digital zoom for focal length on same camera");
                float defaultFocalLength = availableFocalLengths[0];
                float zoomRatio = targetFocalLength / defaultFocalLength;
                setZoom(zoomRatio);
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to switch focal length", e);
            canTakePicture = true; // Re-enable on error
        }
    }

    public void setFocalLength(float targetFocalLength) {
        Log.d(TAG, "setFocalLength - Switching to focal length: " + targetFocalLength);

        if (mPreview == null || availableFocalLengths == null || availableFocalLengths.length == 0) {
            Log.w(TAG, "Cannot set focal length: camera not ready or no focal lengths available");
            return;
        }

        try {
            // Find the closest available focal length
            int closestIndex = 0;
            float minDifference = Float.MAX_VALUE;

            for (int i = 0; i < availableFocalLengths.length; i++) {
                float difference = Math.abs(availableFocalLengths[i] - targetFocalLength);
                if (difference < minDifference) {
                    minDifference = difference;
                    closestIndex = i;
                }
            }

            currentFocalLengthIndex = closestIndex;
            float actualFocalLength = availableFocalLengths[closestIndex];

            Log.d(TAG, "Setting to focal length: " + actualFocalLength + "mm (index: " + closestIndex + ")");

            // Get the camera ID for this focal length
            String targetCameraId = focalLengthToCameraId.get(actualFocalLength);

            if (targetCameraId != null && !targetCameraId.equals(mCameraId)) {
                // Need to switch to a different physical camera
                Log.d(TAG, "Switching from camera " + mCameraId + " to camera " + targetCameraId);

                // Disable taking pictures while camera is switching
                canTakePicture = false;

                // Close ImageReader since we're switching cameras
                synchronized (mImageReaderLock) {
                    if (mImageReader != null) {
                        mImageReader.close();
                        mImageReader = null;
                        mImageSize = null;
                    }
                }

                // Close current camera
                if (mPreview != null) {
                    mPreview.closeCamera();
                }

                // Switch to the new camera
                mCameraId = targetCameraId;
                mPreview.setCamera(mCameraId);
                mPreview.resumePreview();

                // canTakePicture will be set to true in onCameraOpened() callback
            } else {
                // Same camera, just use digital zoom
                Log.d(TAG, "Using digital zoom for focal length on same camera");
                float defaultFocalLength = availableFocalLengths[0];
                float zoomRatio = actualFocalLength / defaultFocalLength;
                setZoom(zoomRatio);
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to set focal length", e);
            canTakePicture = true; // Re-enable on error
        }
    }

    public float getCurrentFocalLength() {
        if (availableFocalLengths != null && currentFocalLengthIndex >= 0 && currentFocalLengthIndex < availableFocalLengths.length) {
            return availableFocalLengths[currentFocalLengthIndex];
        }
        return 0f;
    }

    public float[] getAvailableFocalLengths() {
        return availableFocalLengths != null ? availableFocalLengths.clone() : new float[0];
    }

    public int getCurrentFocalLengthIndex() {
        return currentFocalLengthIndex;
    }

    public void setFocus(final int pointX, final int pointY) {
        if (mPreview == null || mPreview.getCameraDevice() == null) {
            return;
        }

        try {
            CameraCaptureSession captureSession = mPreview.getCaptureSession();
            CaptureRequest.Builder requestBuilder = mPreview.getPreviewRequestBuilder();

            if (captureSession != null && requestBuilder != null) {
                // Set AF trigger to start auto focus
                requestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);

                captureSession.capture(requestBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                    @Override
                    public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                        super.onCaptureCompleted(session, request, result);
                        if (eventListener != null) {
                            eventListener.onFocusSet(pointX, pointY);
                        }
                    }

                    @Override
                    public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, android.hardware.camera2.CaptureFailure failure) {
                        super.onCaptureFailed(session, request, failure);
                        if (eventListener != null) {
                            eventListener.onFocusSetError("Focus failed");
                        }
                    }
                }, mPreview.getBackgroundHandler());
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to set focus", e);
            if (eventListener != null) {
                eventListener.onFocusSetError("Failed to set focus: " + e.getMessage());
            }
        }
    }

    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Log.d(TAG, "onImageAvailable - Image is ready!");
            if (mPreview != null && mPreview.getBackgroundHandler() != null) {
                mPreview.getBackgroundHandler().post(new ImageSaver(reader.acquireLatestImage()));
            } else {
                Log.e(TAG, "onImageAvailable - Preview or handler is null!");
            }
        }
    };

    private class ImageSaver implements Runnable {
        private final Image mImage;

        ImageSaver(Image image) {
            mImage = image;
        }

        @Override
        public void run() {
            try {
                ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);

                if (!disableExifHeaderStripping) {
                    Matrix matrix = new Matrix();

                    // For front cameras, apply vertical flip only in certain rotations
                    if (cameraCurrentlyLocked == CameraCharacteristics.LENS_FACING_FRONT) {
                        int deviceRotation = getActivity().getWindowManager().getDefaultDisplay().getRotation();
                        // Apply vertical flip for ROTATION_0 and ROTATION_180 (portrait modes)
                        // For landscape modes (ROTATION_90, ROTATION_270), the orientation handles it
                        if (deviceRotation == Surface.ROTATION_0 || deviceRotation == Surface.ROTATION_180) {
                            matrix.preScale(1.0f, -1.0f);
                        }
                    }

                    ExifInterface exifInterface = new ExifInterface(new ByteArrayInputStream(bytes));
                    int rotation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                    int rotationInDegrees = exifToDegrees(rotation);

                    if (rotation != 0f) {
                        matrix.preRotate(rotationInDegrees);
                    }

                    // Check if matrix has changed. In that case, apply matrix and override data
                    if (!matrix.isIdentity()) {
                        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        bitmap = applyMatrix(bitmap, matrix);

                        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                        bitmap.compress(Bitmap.CompressFormat.JPEG, currentQuality, outputStream);
                        bytes = outputStream.toByteArray();
                    }
                }

                if (!storeToFile) {
                    String encodedImage = Base64.encodeToString(bytes, Base64.NO_WRAP);
                    if (eventListener != null) {
                        getActivity().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                eventListener.onPictureTaken(encodedImage);
                                canTakePicture = true;
                            }
                        });
                    }
                } else {
                    String path = getTempFilePath();
                    FileOutputStream out = new FileOutputStream(path);
                    out.write(bytes);
                    out.close();
                    if (eventListener != null) {
                        getActivity().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                eventListener.onPictureTaken(path);
                                canTakePicture = true;
                            }
                        });
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error saving image", e);
                if (eventListener != null) {
                    getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            eventListener.onPictureTakenError("Error saving image: " + e.getMessage());
                            canTakePicture = true;
                        }
                    });
                }
            } finally {
                mImage.close();
            }
        }
    }

    public void takePicture(final int width, final int height, final int quality) {
        takePictureInternal(width, height, quality, 0);
    }

    private void takePictureInternal(final int width, final int height, final int quality, final int retryCount) {
        Log.d(TAG, "CameraPreview takePicture width: " + width + ", height: " + height + ", quality: " + quality + ", retryCount: " + retryCount);
        Log.d(TAG, "takePicture - Current camera ID: " + mCameraId);
        Log.d(TAG, "takePicture - Preview camera ID: " + mPreview.getCameraId());

        if (mPreview == null || mPreview.getCameraDevice() == null) {
            if (eventListener != null) {
                eventListener.onPictureTakenError("Camera not available");
            }
            return;
        }

        if (!canTakePicture) {
            if (retryCount < 100) {
                // Retry after 50 milliseconds
                Log.d(TAG, "takePicture - canTakePicture is false, retrying in 50ms (attempt " + (retryCount + 1) + "/100)");
                Handler backgroundHandler = mPreview.getBackgroundHandler();
                if (backgroundHandler != null) {
                    backgroundHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            takePictureInternal(width, height, quality, retryCount + 1);
                        }
                    }, 50);
                } else {
                    Log.e(TAG, "takePicture - Background handler is null, cannot retry");
                    if (eventListener != null) {
                        eventListener.onPictureTakenError("Camera is not ready");
                    }
                }
            } else {
                Log.d(TAG, "takePicture - Blocked: canTakePicture is false after 100 retries");
                if (eventListener != null) {
                    eventListener.onPictureTakenError("Camera is busy, could not take picture");
                }
            }
            return;
        }

        canTakePicture = false;
        currentQuality = quality;

        try {
            // Check if we need to create/recreate the ImageReader
            boolean needsNewImageReader = false;
            ImageReader imageReaderToUse = null;

            synchronized (mImageReaderLock) {
                CameraCharacteristics characteristics = mPreview.getCharacteristics();
                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map != null) {
                    Size[] jpegSizes = map.getOutputSizes(ImageFormat.JPEG);
                    Size optimalSize;

                    // If specific width/height requested, use getOptimalPictureSize
                    // Otherwise, use chooseOptimalPictureSize to match preview aspect ratio
                    // (same logic as when ImageReader was created in onCameraDeviceOpened)
                    if (width > 0 && height > 0) {
                        optimalSize = getOptimalPictureSize(width, height, mPreview.getPreviewSize(), Arrays.asList(jpegSizes));
                    } else {
                        optimalSize = chooseOptimalPictureSize(jpegSizes, mPreview.getPreviewSize());
                    }

                    // Check if ImageReader exists and has the right size
                    if (mImageReader == null ||
                        mImageSize == null ||
                        mImageSize.getWidth() != optimalSize.getWidth() ||
                        mImageSize.getHeight() != optimalSize.getHeight()) {
                        needsNewImageReader = true;
                        mImageSize = optimalSize;
                        Log.d(TAG, "takePicture - ImageReader size mismatch. Old: " +
                            (mImageSize != null ? mImageSize.getWidth() + "x" + mImageSize.getHeight() : "null") +
                            ", New: " + optimalSize.getWidth() + "x" + optimalSize.getHeight());
                    }
                }

                if (needsNewImageReader) {
                    Log.d(TAG, "takePicture - Creating new ImageReader and session");

                    // Close existing ImageReader if any
                    if (mImageReader != null) {
                        mImageReader.close();
                    }

                    mImageReader = ImageReader.newInstance(mImageSize.getWidth(), mImageSize.getHeight(), ImageFormat.JPEG, 2);
                    mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mPreview.getBackgroundHandler());
                    imageReaderToUse = mImageReader;
                } else {
                    Log.d(TAG, "takePicture - Reusing existing ImageReader and session");
                    imageReaderToUse = mImageReader;
                }
            }

            if (needsNewImageReader) {
                // Create a new capture session with both preview and ImageReader surfaces
                Surface previewSurface = mPreview.getPreviewSurface();
                List<Surface> surfaces = Arrays.asList(previewSurface, imageReaderToUse.getSurface());

                mPreview.getCameraDevice().createCaptureSession(surfaces,
                        new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(CameraCaptureSession session) {
                                try {
                                    Log.d(TAG, "takePicture - Session configured, starting preview and capture");
                                    // Update the preview's capture session
                                    mPreview.setCaptureSession(session);

                                    // Start the preview on the new session
                                    // This is necessary because creating a new session closes the old one
                                    CaptureRequest.Builder previewBuilder = mPreview.getPreviewRequestBuilder();
                                    if (previewBuilder != null) {
                                        // Make sure continuous autofocus is enabled
                                        previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                        session.setRepeatingRequest(previewBuilder.build(), null, mPreview.getBackgroundHandler());
                                    }

                                    // Then capture the still picture
                                    captureStillPictureWithoutRecreatingSession(session);
                                } catch (CameraAccessException e) {
                                    Log.e(TAG, "Failed to start preview after session creation", e);
                                    if (eventListener != null) {
                                        eventListener.onPictureTakenError("Failed to start preview");
                                    }
                                    canTakePicture = true;
                                }
                            }

                            @Override
                            public void onConfigureFailed(CameraCaptureSession session) {
                                Log.e(TAG, "Failed to configure capture session for picture");
                                if (eventListener != null) {
                                    eventListener.onPictureTakenError("Failed to configure camera");
                                }
                                canTakePicture = true;
                            }
                        }, mPreview.getBackgroundHandler());
            } else {
                Log.d(TAG, "takePicture - Reusing existing ImageReader and session");
                // Use existing session
                captureStillPictureWithoutRecreatingSession(mPreview.getCaptureSession());
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to take picture", e);
            if (eventListener != null) {
                eventListener.onPictureTakenError("Failed to take picture: " + e.getMessage());
            }
            canTakePicture = true;
        }
    }

    private void captureStillPicture(CameraCaptureSession session) {
        try {
            CaptureRequest.Builder captureBuilder = mPreview.getCameraDevice().createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(mImageReader.getSurface());

            // Auto focus
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            // Flash - use the current flash mode
            applyFlashMode(captureBuilder, currentFlashMode);

            // Orientation
            int rotation = getActivity().getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(rotation));

            // Quality
            captureBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) currentQuality);

            session.stopRepeating();
            session.abortCaptures();
            session.capture(captureBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                    Log.d(TAG, "Picture captured successfully");
                    // Restart preview
                    try {
                        session.setRepeatingRequest(mPreview.mPreviewRequest, null, mPreview.getBackgroundHandler());
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "Failed to restart preview", e);
                    }
                }
            }, mPreview.getBackgroundHandler());

        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to capture still picture", e);
            if (eventListener != null) {
                eventListener.onPictureTakenError("Failed to capture picture: " + e.getMessage());
            }
            canTakePicture = true;
        }
    }

    private void captureStillPictureWithoutRecreatingSession(CameraCaptureSession session) {
        try {
            Log.d(TAG, "captureStillPictureWithoutRecreatingSession - Starting capture");

            ImageReader imageReaderToUse;
            synchronized (mImageReaderLock) {
                if (mImageReader == null) {
                    Log.e(TAG, "captureStillPictureWithoutRecreatingSession - ImageReader is null!");
                    if (eventListener != null) {
                        eventListener.onPictureTakenError("ImageReader not initialized");
                    }
                    canTakePicture = true;
                    return;
                }
                imageReaderToUse = mImageReader;
            }

            CaptureRequest.Builder captureBuilder = mPreview.getCameraDevice().createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(imageReaderToUse.getSurface());

            // Auto focus
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            // Flash - use the current flash mode
            applyFlashMode(captureBuilder, currentFlashMode);

            // Orientation
            int rotation = getActivity().getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(rotation));

            // Quality
            captureBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) currentQuality);

            Log.d(TAG, "captureStillPictureWithoutRecreatingSession - Submitting capture request");

            // Capture without stopping the preview - just submit a single capture request
            session.capture(captureBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                    Log.d(TAG, "Picture captured successfully without stopping preview");
                }

                @Override
                public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
                    super.onCaptureFailed(session, request, failure);
                    Log.e(TAG, "Picture capture failed: " + failure.getReason());
                    if (eventListener != null) {
                        eventListener.onPictureTakenError("Capture failed: " + failure.getReason());
                    }
                    canTakePicture = true;
                }
            }, mPreview.getBackgroundHandler());

        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to capture still picture", e);
            if (eventListener != null) {
                eventListener.onPictureTakenError("Failed to capture picture: " + e.getMessage());
            }
            canTakePicture = true;
        }
    }

    public void takeSnapshot(final int quality) {
        // For snapshot, we can capture from the preview TextureView
        if (mPreview == null || !mPreview.isTextureViewAvailable()) {
            if (eventListener != null) {
                eventListener.onSnapshotTakenError("Preview not available");
            }
            return;
        }

        try {
            Bitmap bitmap = mPreview.getPreviewBitmap();
            if (bitmap != null) {
                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream);
                byte[] byteArray = stream.toByteArray();
                String encodedImage = Base64.encodeToString(byteArray, Base64.NO_WRAP);

                if (eventListener != null) {
                    eventListener.onSnapshotTaken(encodedImage);
                }
                stream.close();
            } else {
                if (eventListener != null) {
                    eventListener.onSnapshotTakenError("Failed to capture bitmap");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to take snapshot", e);
            if (eventListener != null) {
                eventListener.onSnapshotTakenError("Failed to take snapshot: " + e.getMessage());
            }
        }
    }

    @SuppressLint("MissingPermission")
    public void startRecord(final String filePath, final String camera, final int width, final int height, final int quality, final boolean withFlash) {
        Log.d(TAG, "CameraPreview startRecord camera: " + camera + " width: " + width + ", height: " + height + ", quality: " + quality);

        if (mPreview == null || mPreview.getCameraDevice() == null) {
            if (eventListener != null) {
                eventListener.onStartRecordVideoError("Camera not available");
            }
            return;
        }

        Activity activity = getActivity();
        muteStream(true, activity);

        if (this.mRecordingState == RecordingState.STARTED) {
            Log.d(TAG, "Already Recording");
            return;
        }

        this.recordFilePath = filePath;

        try {
            setUpMediaRecorder();

            // Create capture session with preview and recording surfaces
            Surface previewSurface = mPreview.getPreviewSurface();
            if (previewSurface == null) {
                if (eventListener != null) {
                    eventListener.onStartRecordVideoError("Preview surface not available");
                }
                return;
            }

            List<Surface> surfaces = Arrays.asList(
                previewSurface,
                mRecorderSurface
            );

            mPreview.getCameraDevice().createCaptureSession(surfaces,
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession session) {
                        try {
                            CaptureRequest.Builder recordBuilder = mPreview.getCameraDevice()
                                .createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                            recordBuilder.addTarget(previewSurface);
                            recordBuilder.addTarget(mRecorderSurface);

                            // Auto focus
                            recordBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);

                            if (withFlash && mPreview.isFlashSupported()) {
                                recordBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
                            }

                            session.setRepeatingRequest(recordBuilder.build(), null, mPreview.getBackgroundHandler());

                            // Start recording
                            mRecorder.start();
                            mRecordingState = RecordingState.STARTED;

                            if (eventListener != null) {
                                eventListener.onStartRecordVideo();
                            }

                        } catch (CameraAccessException e) {
                            Log.e(TAG, "Failed to start recording", e);
                            if (eventListener != null) {
                                eventListener.onStartRecordVideoError("Failed to start recording: " + e.getMessage());
                            }
                        }
                    }

                    @Override
                    public void onConfigureFailed(CameraCaptureSession session) {
                        Log.e(TAG, "Failed to configure recording session");
                        if (eventListener != null) {
                            eventListener.onStartRecordVideoError("Failed to configure recording session");
                        }
                    }
                }, mPreview.getBackgroundHandler());

        } catch (Exception e) {
            Log.e(TAG, "Failed to set up recording", e);
            if (eventListener != null) {
                eventListener.onStartRecordVideoError("Failed to set up recording: " + e.getMessage());
            }
        }
    }

    private void setUpMediaRecorder() throws IOException {
        Activity activity = getActivity();

        if (mRecorder == null) {
            mRecorder = new MediaRecorder();
        }

        mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mRecorder.setOutputFile(recordFilePath);

        CamcorderProfile profile;
        if (CamcorderProfile.hasProfile(Integer.parseInt(defaultCameraId), CamcorderProfile.QUALITY_HIGH)) {
            profile = CamcorderProfile.get(Integer.parseInt(defaultCameraId), CamcorderProfile.QUALITY_HIGH);
        } else {
            profile = CamcorderProfile.get(Integer.parseInt(defaultCameraId), CamcorderProfile.QUALITY_LOW);
        }

        mRecorder.setVideoEncodingBitRate(profile.videoBitRate);
        mRecorder.setVideoFrameRate(profile.videoFrameRate);
        mRecorder.setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight);
        mRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);

        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        int orientation = getOrientation(rotation);
        mRecorder.setOrientationHint(orientation);

        mRecorder.prepare();
        mRecorderSurface = mRecorder.getSurface();
    }

    public void stopRecord() {
        Log.d(TAG, "CameraPreview stopRecord");

        if (mRecordingState != RecordingState.STARTED) {
            return;
        }

        Activity activity = getActivity();
        muteStream(false, activity);

        try {
            mRecorder.stop();
            mRecorder.reset();
            mRecordingState = RecordingState.STOPPED;

            if (eventListener != null) {
                eventListener.onStopRecordVideo(recordFilePath);
            }

            // Restart preview
            if (mPreview != null) {
                mPreview.resumePreview();
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to stop recording", e);
            if (eventListener != null) {
                eventListener.onStopRecordVideoError("Failed to stop recording: " + e.getMessage());
            }
        }
    }

    private void muteStream(boolean mute, Activity activity) {
        AudioManager audioManager = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);

        if (audioManager != null) {
            int flag = mute ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE;
            audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, flag, 0);
        }
    }

    private int getOrientation(int rotation) {
        int sensorOrientation = mPreview.getSensorOrientation();
        int orientationFromRotation = ORIENTATIONS.get(rotation);
        int result = (orientationFromRotation + sensorOrientation + 270) % 360;

        // For front cameras, we need to add 180 degrees to flip the image
        // because the front camera is mirrored horizontally
        if (cameraCurrentlyLocked == CameraCharacteristics.LENS_FACING_FRONT) {
            result = (result + 180) % 360;
        }

        return result;
    }

    private Size getOptimalPictureSize(int width, int height, Size previewSize, List<Size> sizes) {
        if (width == 0 || height == 0) {
            // Use the largest available size if no specific size requested
            if (sizes != null && !sizes.isEmpty()) {
                Size largest = sizes.get(0);
                for (Size size : sizes) {
                    if (size.getWidth() * size.getHeight() > largest.getWidth() * largest.getHeight()) {
                        largest = size;
                    }
                }
                return largest;
            }
            return previewSize;
        }

        if (sizes == null || sizes.isEmpty()) {
            return previewSize;
        }

        // Strategy: Find the smallest size that's still big enough
        // This ensures we always get a size that meets the requirements

        Size optimalSize = null;
        int minArea = Integer.MAX_VALUE;
        int requestedArea = width * height;

        // First pass: Find sizes that are big enough (both width and height >= requested)
        for (Size size : sizes) {
            if (size.getWidth() >= width && size.getHeight() >= height) {
                int area = size.getWidth() * size.getHeight();
                if (area < minArea) {
                    optimalSize = size;
                    minArea = area;
                }
            }
        }

        // Second pass: If no size is big enough in both dimensions, find the one with the largest area
        if (optimalSize == null) {
            int maxArea = 0;
            for (Size size : sizes) {
                int area = size.getWidth() * size.getHeight();
                if (area > maxArea) {
                    optimalSize = size;
                    maxArea = area;
                }
            }
        }

        return optimalSize != null ? optimalSize : previewSize;
    }

    /**
     * Choose optimal picture size based on preview size.
     * Finds a JPEG size that matches the preview aspect ratio but is larger for better quality.
     */
    private Size chooseOptimalPictureSize(Size[] jpegSizes, Size previewSize) {
        if (jpegSizes == null || jpegSizes.length == 0 || previewSize == null) {
            return null;
        }

        final double ASPECT_TOLERANCE = 0.15;
        double previewRatio = (double) previewSize.getWidth() / previewSize.getHeight();

        Size optimalSize = null;
        int maxArea = 0;

        // First pass: Find the largest size that matches the preview aspect ratio
        for (Size size : jpegSizes) {
            double ratio = (double) size.getWidth() / size.getHeight();
            if (Math.abs(ratio - previewRatio) <= ASPECT_TOLERANCE) {
                int area = size.getWidth() * size.getHeight();
                if (area > maxArea) {
                    optimalSize = size;
                    maxArea = area;
                }
            }
        }

        // Second pass: If no size matches aspect ratio, just use the largest available
        if (optimalSize == null) {
            Log.w(TAG, "No JPEG size matches preview aspect ratio, using largest available");
            for (Size size : jpegSizes) {
                int area = size.getWidth() * size.getHeight();
                if (area > maxArea) {
                    optimalSize = size;
                    maxArea = area;
                }
            }
        }

        Log.d(TAG, "chooseOptimalPictureSize - Preview: " + previewSize.getWidth() + "x" + previewSize.getHeight() +
                   ", Selected: " + (optimalSize != null ? optimalSize.getWidth() + "x" + optimalSize.getHeight() : "null"));

        return optimalSize;
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

    private Bitmap applyMatrix(Bitmap source, Matrix matrix) {
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    private String getTempFilePath() {
        File outputDir = getActivity().getCacheDir();
        File outputFile;
        try {
            outputFile = File.createTempFile("picture", ".jpg", outputDir);
        } catch (IOException e) {
            Log.e(TAG, "Failed to create temp file", e);
            return null;
        }
        return outputFile.getAbsolutePath();
    }

    // Camera control methods for Camera2
    public void setColorEffect(String effect) {
        // Camera2 doesn't support color effects directly like Camera1
        // This would need to be implemented with image processing if needed
        Log.w(TAG, "Color effects not supported in Camera2");
    }

    public void setZoom(float zoom) {
        if (mPreview == null || mPreview.getCaptureSession() == null) {
            return;
        }

        try {
            CameraCharacteristics characteristics = mPreview.getCharacteristics();
            float maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);

            zoom = Math.max(1.0f, Math.min(zoom, maxZoom));

            CaptureRequest.Builder builder = mPreview.getPreviewRequestBuilder();
            if (builder != null) {
                android.graphics.Rect sensorRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                if (sensorRect != null) {
                    int cropW = (int) (sensorRect.width() / zoom);
                    int cropH = (int) (sensorRect.height() / zoom);
                    int cropX = (sensorRect.width() - cropW) / 2;
                    int cropY = (sensorRect.height() - cropH) / 2;
                    android.graphics.Rect cropRect = new android.graphics.Rect(cropX, cropY, cropX + cropW, cropY + cropH);

                    builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect);
                    mPreview.getCaptureSession().setRepeatingRequest(builder.build(), null, mPreview.getBackgroundHandler());
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to set zoom", e);
        }
    }

    public float getZoom() {
        if (mPreview == null || mPreview.getPreviewRequestBuilder() == null) {
            return 1.0f;
        }

        try {
            CameraCharacteristics characteristics = mPreview.getCharacteristics();
            android.graphics.Rect sensorRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            android.graphics.Rect cropRect = mPreview.getPreviewRequestBuilder().get(CaptureRequest.SCALER_CROP_REGION);

            if (sensorRect != null && cropRect != null) {
                return (float) sensorRect.width() / cropRect.width();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get zoom", e);
        }
        return 1.0f;
    }

    public float getMaxZoom() {
        if (mPreview == null) {
            return 1.0f;
        }

        try {
            CameraCharacteristics characteristics = mPreview.getCharacteristics();
            Float maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            return maxZoom != null ? maxZoom : 1.0f;
        } catch (Exception e) {
            Log.e(TAG, "Failed to get max zoom", e);
            return 1.0f;
        }
    }

    public List<String> getSupportedFlashModes() {
        List<String> flashModes = new ArrayList<>();
        flashModes.add("off");

        if (mPreview != null && mPreview.isFlashSupported()) {
            flashModes.add("on");
            flashModes.add("auto");
            flashModes.add("torch");
        }

        return flashModes;
    }

    /**
     * Apply the flash mode settings to a capture request builder
     */
    private void applyFlashMode(CaptureRequest.Builder builder, String flashMode) {
        if (!mPreview.isFlashSupported()) {
            return;
        }

        switch (flashMode) {
            case "off":
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
                break;
            case "on":
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
                break;
            case "auto":
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                break;
            case "torch":
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
                break;
        }
    }

    public void setFlashMode(String flashMode) {
        if (mPreview == null || mPreview.getPreviewRequestBuilder() == null) {
            return;
        }

        // Store the current flash mode
        currentFlashMode = flashMode;

        try {
            CaptureRequest.Builder builder = mPreview.getPreviewRequestBuilder();
            applyFlashMode(builder, flashMode);
            mPreview.getCaptureSession().setRepeatingRequest(builder.build(), null, mPreview.getBackgroundHandler());
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to set flash mode", e);
        }
    }

    // Add getter methods for camera characteristics that the plugin needs
    public List<Size> getSupportedPictureSizes() {
        if (mPreview == null) {
            return new ArrayList<>();
        }

        try {
            CameraCharacteristics characteristics = mPreview.getCharacteristics();
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                return Arrays.asList(map.getOutputSizes(ImageFormat.JPEG));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get supported picture sizes", e);
        }
        return new ArrayList<>();
    }

    public float getHorizontalFOV() {
        if (mPreview == null) {
            return 0f;
        }

        try {
            CameraCharacteristics characteristics = mPreview.getCharacteristics();
            float[] focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            android.util.SizeF sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);

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

    // Getter method for mPreview to allow access from CameraPreview.java
    public Preview getPreview() {
        return mPreview;
    }
}
