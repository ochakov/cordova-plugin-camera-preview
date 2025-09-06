package com.cordovaplugincamerapreview;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.app.Fragment;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
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
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import androidx.exifinterface.media.ExifInterface;

import org.apache.cordova.LOG;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

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
    private static final int STATE_WAITING_LOCK = 1;
    private static final int STATE_WAITING_PRECAPTURE = 2;
    private static final int STATE_WAITING_NON_PRECAPTURE = 3;
    private static final int STATE_PICTURE_TAKEN = 4;

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

    private int numberOfCameras;
    private int cameraCurrentlyLocked;
    private int currentQuality;

    // Focal length switching variables
    private float[] availableFocalLengths;
    private int currentFocalLengthIndex = 0;

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

        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(width, height);
        layoutParams.setMargins(x, y, 0, 0);
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
        frameContainerLayout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (tapToTakePicture && canTakePicture) {
                    takePicture(0, 0, 85);
                }
            }
        });

        TapGestureDetector tapGestureDetector = new TapGestureDetector(getActivity(), new TapGestureDetector.OnTapListener() {
            @Override
            public void onTap(MotionEvent e) {
                if (tapToFocus) {
                    setFocus((int) e.getX(), (int) e.getY());
                }
            }
        });

        frameContainerLayout.setOnTouchListener(tapGestureDetector);

        if (dragEnabled) {
            frameContainerLayout.setOnTouchListener(new FrameLayout.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    int action = event.getAction();
                    if (action == MotionEvent.ACTION_MOVE) {
                        v.setX(event.getRawX() - v.getWidth() / 2);
                        v.setY(event.getRawY() - v.getHeight() / 2);
                    }
                    return true;
                }
            });
        }
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
    public void onCameraOpened() {
        Log.d(TAG, "Camera opened");
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
                        break;
                    }
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Cannot switch camera", e);
        }
    }

    private void initializeFocalLengths(CameraCharacteristics characteristics) {
        try {
            availableFocalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            if (availableFocalLengths != null && availableFocalLengths.length > 0) {
                currentFocalLengthIndex = 0; // Start with the first (usually widest) focal length
                Log.d(TAG, "Initialized focal lengths: " + java.util.Arrays.toString(availableFocalLengths));
            } else {
                Log.w(TAG, "No focal lengths available for this camera");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize focal lengths", e);
        }
    }

    public void switchFocalLength() {
        Log.d(TAG, "switchFocalLength");
        
        if (mPreview == null || mPreview.getCaptureSession() == null || availableFocalLengths == null || availableFocalLengths.length <= 1) {
            Log.w(TAG, "Cannot switch focal length: camera not ready or only one focal length available");
            return;
        }

        try {
            // Cycle to next focal length
            currentFocalLengthIndex = (currentFocalLengthIndex + 1) % availableFocalLengths.length;
            float targetFocalLength = availableFocalLengths[currentFocalLengthIndex];
            
            Log.d(TAG, "Switching to focal length: " + targetFocalLength + "mm (index: " + currentFocalLengthIndex + ")");
            
            // Calculate zoom ratio based on focal length
            float defaultFocalLength = availableFocalLengths[0];
            float zoomRatio = targetFocalLength / defaultFocalLength;
            
            // Apply zoom using existing setZoom method
            setZoom(zoomRatio);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to switch focal length", e);
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
            if (mPreview != null && mPreview.getBackgroundHandler() != null) {
                mPreview.getBackgroundHandler().post(new ImageSaver(reader.acquireLatestImage()));
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
                    if (cameraCurrentlyLocked == CameraCharacteristics.LENS_FACING_FRONT) {
                        matrix.preScale(1.0f, -1.0f);
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
        Log.d(TAG, "CameraPreview takePicture width: " + width + ", height: " + height + ", quality: " + quality);

        if (mPreview == null || mPreview.getCameraDevice() == null) {
            if (eventListener != null) {
                eventListener.onPictureTakenError("Camera not available");
            }
            return;
        }

        if (!canTakePicture) {
            return;
        }

        canTakePicture = false;
        currentQuality = quality;

        try {
            // Set up ImageReader for capturing still pictures
            if (mImageReader != null) {
                mImageReader.close();
            }

            CameraCharacteristics characteristics = mPreview.getCharacteristics();
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                Size[] jpegSizes = map.getOutputSizes(ImageFormat.JPEG);
                mImageSize = getOptimalPictureSize(width, height, mPreview.getPreviewSize(), Arrays.asList(jpegSizes));
                
                mImageReader = ImageReader.newInstance(mImageSize.getWidth(), mImageSize.getHeight(), ImageFormat.JPEG, 1);
                mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mPreview.getBackgroundHandler());

                // Create capture session for taking pictures
                Surface previewSurface = mPreview.getPreviewSurface();
                if (previewSurface == null) {
                    if (eventListener != null) {
                        eventListener.onPictureTakenError("Preview surface not available");
                    }
                    canTakePicture = true;
                    return;
                }
                
                List<Surface> outputs = Arrays.asList(
                    previewSurface,
                    mImageReader.getSurface()
                );

                mPreview.getCameraDevice().createCaptureSession(outputs, 
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            captureStillPicture(session);
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            Log.e(TAG, "Failed to configure capture session for picture");
                            if (eventListener != null) {
                                eventListener.onPictureTakenError("Failed to configure capture session");
                            }
                            canTakePicture = true;
                        }
                    }, mPreview.getBackgroundHandler());
            }
        } catch (CameraAccessException e) {
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

            // Flash
            if (mPreview.isFlashSupported()) {
                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
            }

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
        return (ORIENTATIONS.get(rotation) + mPreview.getSensorOrientation() + 270) % 360;
    }

    private Size getOptimalPictureSize(int width, int height, Size previewSize, List<Size> sizes) {
        if (width == 0 || height == 0) {
            // Use preview size if no specific size requested
            return previewSize;
        }

        final double ASPECT_TOLERANCE = 0.1;
        double targetRatio = (double) width / height;

        if (sizes == null) {
            return previewSize;
        }

        Size optimalSize = null;
        double minDiff = Double.MAX_VALUE;

        // Try to find an size match aspect ratio and size
        for (Size size : sizes) {
            double ratio = (double) size.getWidth() / size.getHeight();
            if (Math.abs(ratio - targetRatio) > ASPECT_TOLERANCE) continue;
            if (Math.abs(size.getHeight() - height) < minDiff) {
                optimalSize = size;
                minDiff = Math.abs(size.getHeight() - height);
            }
        }

        // Cannot find the one match the aspect ratio, ignore the requirement
        if (optimalSize == null) {
            minDiff = Double.MAX_VALUE;
            for (Size size : sizes) {
                if (Math.abs(size.getHeight() - height) < minDiff) {
                    optimalSize = size;
                    minDiff = Math.abs(size.getHeight() - height);
                }
            }
        }

        return optimalSize != null ? optimalSize : previewSize;
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

    public void setFlashMode(String flashMode) {
        if (mPreview == null || mPreview.getPreviewRequestBuilder() == null) {
            return;
        }

        try {
            CaptureRequest.Builder builder = mPreview.getPreviewRequestBuilder();
            
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
