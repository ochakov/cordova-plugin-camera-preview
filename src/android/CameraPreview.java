package com.cordovaplugincamerapreview;

import android.Manifest;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.util.SizeF;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CameraPreview extends CordovaPlugin implements CameraActivity.CameraPreviewListener {

  private static final String TAG = "CameraPreview";

  private static final String COLOR_EFFECT_ACTION = "setColorEffect";
  private static final String SUPPORTED_COLOR_EFFECTS_ACTION = "getSupportedColorEffects";
  private static final String ZOOM_ACTION = "setZoom";
  private static final String GET_ZOOM_ACTION = "getZoom";
  private static final String GET_HFOV_ACTION = "getHorizontalFOV";
  private static final String GET_MAX_ZOOM_ACTION = "getMaxZoom";
  private static final String SUPPORTED_FLASH_MODES_ACTION = "getSupportedFlashModes";
  private static final String GET_FLASH_MODE_ACTION = "getFlashMode";
  private static final String SET_FLASH_MODE_ACTION = "setFlashMode";
  private static final String START_CAMERA_ACTION = "startCamera";
  private static final String STOP_CAMERA_ACTION = "stopCamera";
  private static final String PREVIEW_SIZE_ACTION = "setPreviewSize";
  private static final String SWITCH_CAMERA_ACTION = "switchCamera";
  private static final String SWITCH_FOCAL_LENGTH_ACTION = "switchFocalLength";
  private static final String GET_CURRENT_FOCAL_LENGTH_ACTION = "getCurrentFocalLength";
  private static final String GET_AVAILABLE_FOCAL_LENGTHS_ACTION = "getAvailableFocalLengths";
  private static final String TAKE_PICTURE_ACTION = "takePicture";
  private static final String START_RECORD_VIDEO_ACTION = "startRecordVideo";
  private static final String STOP_RECORD_VIDEO_ACTION = "stopRecordVideo";
  private static final String TAKE_SNAPSHOT_ACTION = "takeSnapshot";
  private static final String SHOW_CAMERA_ACTION = "showCamera";
  private static final String HIDE_CAMERA_ACTION = "hideCamera";
  private static final String TAP_TO_FOCUS = "tapToFocus";
  private static final String SUPPORTED_PICTURE_SIZES_ACTION = "getSupportedPictureSizes";
  private static final String SUPPORTED_FOCUS_MODES_ACTION = "getSupportedFocusModes";
  private static final String SUPPORTED_WHITE_BALANCE_MODES_ACTION = "getSupportedWhiteBalanceModes";
  private static final String GET_FOCUS_MODE_ACTION = "getFocusMode";
  private static final String SET_FOCUS_MODE_ACTION = "setFocusMode";
  private static final String GET_EXPOSURE_MODES_ACTION = "getExposureModes";
  private static final String GET_EXPOSURE_MODE_ACTION = "getExposureMode";
  private static final String SET_EXPOSURE_MODE_ACTION = "setExposureMode";
  private static final String GET_EXPOSURE_COMPENSATION_ACTION = "getExposureCompensation";
  private static final String SET_EXPOSURE_COMPENSATION_ACTION = "setExposureCompensation";
  private static final String GET_EXPOSURE_COMPENSATION_RANGE_ACTION = "getExposureCompensationRange";
  private static final String GET_WHITE_BALANCE_MODE_ACTION = "getWhiteBalanceMode";
  private static final String SET_WHITE_BALANCE_MODE_ACTION = "setWhiteBalanceMode";
  private static final String SET_BACK_BUTTON_CALLBACK = "onBackButton";
  private static final String GET_CAMERA_CHARACTERISTICS_ACTION = "getCameraCharacteristics";

  private static final int CAM_REQ_CODE = 0;
  private static final int VID_REQ_CODE = 1;
  private static final int HIDDEN_CAMERA_PROBE_LIMIT = 20;
  private String VIDEO_FILE_PATH = "";
  private static final String VIDEO_FILE_EXTENSION = ".mp4";

  private static final String [] permissions = {
    Manifest.permission.CAMERA
  };

  private CameraActivity fragment;
  private CallbackContext takePictureCallbackContext;
  private CallbackContext takeSnapshotCallbackContext;
  private CallbackContext startRecordVideoCallbackContext;
  private CallbackContext stopRecordVideoCallbackContext;
  private CallbackContext setFocusCallbackContext;
  private CallbackContext startCameraCallbackContext;
  private CallbackContext switchCameraCallbackContext;
  private CallbackContext tapBackButtonContext = null;

  private CallbackContext execCallback;
  private JSONArray execArgs;

  private ViewParent webViewParent;

  private int containerViewId = 20; //<- set to random number to prevent conflict with other plugins
  public CameraPreview(){
    super();
    Log.d(TAG, "Constructing");
  }

  @Override
  public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {

    if (START_CAMERA_ACTION.equals(action)) {
      if (cordova.hasPermission(permissions[0])) {
        return startCamera(args.getInt(0), args.getInt(1), args.getInt(2), args.getInt(3), args.getString(4), args.getBoolean(5), args.getBoolean(6), args.getBoolean(7), args.getString(8), args.getBoolean(9), args.getBoolean(10), args.getBoolean(11), callbackContext);
      } else {
        this.execCallback = callbackContext;
        this.execArgs = args;
        cordova.requestPermissions(this, CAM_REQ_CODE, permissions);
        return true;
      }
    } else if (TAKE_PICTURE_ACTION.equals(action)) {
      return takePicture(args.getInt(0), args.getInt(1), args.getInt(2), callbackContext);
    } else if (TAKE_SNAPSHOT_ACTION.equals(action)) {
      return takeSnapshot(args.getInt(0), callbackContext);
    } else if (START_RECORD_VIDEO_ACTION.equals(action)) {
      String[] videoPermissions = getVideoPermissions();
      if (hasAllPermissions(videoPermissions)) {
        return startRecordVideo(args.optString(0), args.getInt(1), args.getInt(2), args.getInt(3), args.optBoolean(4, false), callbackContext);
      } else {
        this.execCallback = callbackContext;
        this.execArgs = args;
        cordova.requestPermissions(this, VID_REQ_CODE, videoPermissions);
        return true;
      }
    } else if (STOP_RECORD_VIDEO_ACTION.equals(action)) {
      return stopRecordVideo(callbackContext);
    } else if (COLOR_EFFECT_ACTION.equals(action)) {
      return setColorEffect(args.getString(0), callbackContext);
    } else if (ZOOM_ACTION.equals(action)) {
      return setZoom((float) args.getDouble(0), callbackContext);
    } else if (GET_ZOOM_ACTION.equals(action)) {
      return getZoom(callbackContext);
    } else if (GET_HFOV_ACTION.equals(action)) {
      return getHorizontalFOV(callbackContext);
    } else if (GET_MAX_ZOOM_ACTION.equals(action)) {
      return getMaxZoom(callbackContext);
    } else if (PREVIEW_SIZE_ACTION.equals(action)) {
      return setPreviewSize(args.getInt(0), args.getInt(1), callbackContext);
    } else if (SUPPORTED_FLASH_MODES_ACTION.equals(action)) {
      return getSupportedFlashModes(callbackContext);
    } else if (GET_FLASH_MODE_ACTION.equals(action)) {
      return getFlashMode(callbackContext);
    } else if (SET_FLASH_MODE_ACTION.equals(action)) {
      return setFlashMode(args.getString(0), callbackContext);
    } else if (STOP_CAMERA_ACTION.equals(action)){
      return stopCamera(callbackContext);
    } else if (SHOW_CAMERA_ACTION.equals(action)) {
      return showCamera(callbackContext);
    } else if (HIDE_CAMERA_ACTION.equals(action)) {
      return hideCamera(callbackContext);
    } else if (TAP_TO_FOCUS.equals(action)) {
      return tapToFocus(args.getInt(0), args.getInt(1), callbackContext);
    } else if (SWITCH_CAMERA_ACTION.equals(action)) {
      return switchCamera(callbackContext);
    } else if (SWITCH_FOCAL_LENGTH_ACTION.equals(action)) {
      if (args.length() > 0 && !args.isNull(0)) {
        return switchFocalLength((float) args.getDouble(0), callbackContext);
      } else {
        return switchFocalLength(callbackContext);
      }
    } else if (GET_CURRENT_FOCAL_LENGTH_ACTION.equals(action)) {
      return getCurrentFocalLength(callbackContext);
    } else if (GET_AVAILABLE_FOCAL_LENGTHS_ACTION.equals(action)) {
      return getAvailableFocalLengths(callbackContext);
    } else if (SUPPORTED_PICTURE_SIZES_ACTION.equals(action)) {
      return getSupportedPictureSizes(callbackContext);
    } else if (GET_EXPOSURE_MODES_ACTION.equals(action)) {
      return getExposureModes(callbackContext);
    } else if (SUPPORTED_FOCUS_MODES_ACTION.equals(action)) {
      return getSupportedFocusModes(callbackContext);
    } else if (GET_FOCUS_MODE_ACTION.equals(action)) {
      return getFocusMode(callbackContext);
    } else if (SET_FOCUS_MODE_ACTION.equals(action)) {
      return setFocusMode(args.getString(0), callbackContext);
    } else if (GET_EXPOSURE_MODE_ACTION.equals(action)) {
      return getExposureMode(callbackContext);
    } else if (SET_EXPOSURE_MODE_ACTION.equals(action)) {
      return setExposureMode(args.getString(0), callbackContext);
    } else if (GET_EXPOSURE_COMPENSATION_ACTION.equals(action)) {
      return getExposureCompensation(callbackContext);
    } else if (SET_EXPOSURE_COMPENSATION_ACTION.equals(action)) {
      return setExposureCompensation(args.getInt(0), callbackContext);
    } else if (GET_EXPOSURE_COMPENSATION_RANGE_ACTION.equals(action)) {
      return getExposureCompensationRange(callbackContext);
    } else if (SUPPORTED_WHITE_BALANCE_MODES_ACTION.equals(action)) {
      return getSupportedWhiteBalanceModes(callbackContext);
    } else if (GET_WHITE_BALANCE_MODE_ACTION.equals(action)) {
      return getWhiteBalanceMode(callbackContext);
    } else if (SET_WHITE_BALANCE_MODE_ACTION.equals(action)) {
      return setWhiteBalanceMode(args.getString(0),callbackContext);
    } else if (SET_BACK_BUTTON_CALLBACK.equals(action)) {
      return setBackButtonListener(callbackContext);
    } else if (SUPPORTED_COLOR_EFFECTS_ACTION.equals(action)) {
      return getSupportedColorEffects(callbackContext);
    } else if (GET_CAMERA_CHARACTERISTICS_ACTION.equals(action)) {
      return getCameraCharacteristics(callbackContext);
    }

    return false;
  }

  private boolean hasAllPermissions(String[] wanted) {
    for (String permission : wanted) {
      if (!cordova.hasPermission(permission)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
    CallbackContext callback = this.execCallback;
    JSONArray args = this.execArgs;
    this.execCallback = null;
    this.execArgs = null;
    if (callback == null || args == null) {
      return;
    }

    for(int r:grantResults){
      if(r == PackageManager.PERMISSION_DENIED){
        callback.sendPluginResult(new PluginResult(PluginResult.Status.ILLEGAL_ACCESS_EXCEPTION));
        return;
      }
    }

    if(requestCode == CAM_REQ_CODE){
      startCamera(args.getInt(0), args.getInt(1), args.getInt(2), args.getInt(3), args.getString(4), args.getBoolean(5), args.getBoolean(6), args.getBoolean(7), args.getString(8), args.getBoolean(9), args.getBoolean(10), args.getBoolean(11), callback);
    }else if(requestCode == VID_REQ_CODE){
      startRecordVideo(args.optString(0), args.getInt(1), args.getInt(2), args.getInt(3), args.optBoolean(4, false), callback);
    }
  }

  private boolean hasView(CallbackContext callbackContext) {
    if(fragment == null) {
      callbackContext.error("No preview");
      return false;
    }

    return true;
  }

  private boolean hasCamera(CallbackContext callbackContext) {
    if(this.hasView(callbackContext) == false){
      return false;
    }

    if(fragment.getPreview() == null || fragment.getPreview().getCameraDevice() == null) {
      callbackContext.error("No Camera");
      return false;
    }

    return true;
  }

  private static PluginResult floatResult(float value) {
    return new PluginResult(PluginResult.Status.OK, value);
  }

  private static JSONArray toJsonArray(List<String> values) {
    JSONArray array = new JSONArray();
    if (values != null) {
      for (String value : values) {
        array.put(value);
      }
    }
    return array;
  }

  private int dipToPixels(int dip, DisplayMetrics metrics) {
    return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dip, metrics);
  }

  private boolean getSupportedPictureSizes(CallbackContext callbackContext) {
    if(this.hasView(callbackContext) == false){
      return true;
    }

    List<Size> supportedSizes = fragment.getSupportedPictureSizes();
    JSONArray sizes = new JSONArray();
    for (Size size : supportedSizes) {
      JSONObject jsonSize = new JSONObject();
      try {
        jsonSize.put("height", size.getHeight());
        jsonSize.put("width", size.getWidth());
      } catch (JSONException e) {
        Log.e(TAG, "getSupportedPictureSizes failed to build payload", e);
      }
      sizes.put(jsonSize);
    }
    callbackContext.success(sizes);
    return true;
  }

  private boolean startCamera(int x, int y, int width, int height, String defaultCamera, Boolean tapToTakePicture, Boolean dragEnabled, final Boolean toBack, String alpha, boolean tapFocus, boolean disableExifHeaderStripping, boolean storeToFile, CallbackContext callbackContext) {
    Log.d(TAG, "start camera action");

    if (fragment != null) {
      callbackContext.error("Camera already started");
      return true;
    }

    float parsedOpacity = 1f;
    try {
      parsedOpacity = Float.parseFloat(alpha);
    } catch (NumberFormatException e) {
      Log.w(TAG, "Invalid alpha value '" + alpha + "', using 1");
    }
    final float opacity = parsedOpacity;

    fragment = new CameraActivity();
    fragment.setEventListener(this);
    fragment.defaultCamera = defaultCamera;
    fragment.tapToTakePicture = tapToTakePicture;
    fragment.dragEnabled = dragEnabled;
    fragment.tapToFocus = tapFocus;
    fragment.disableExifHeaderStripping = disableExifHeaderStripping;
    fragment.storeToFile = storeToFile;
    fragment.toBack = toBack;

    DisplayMetrics metrics = cordova.getActivity().getResources().getDisplayMetrics();

    Log.d(TAG, "startCamera - Input dimensions: x=" + x + ", y=" + y + ", width=" + width + ", height=" + height
        + ", screen=" + metrics.widthPixels + "x" + metrics.heightPixels + ", density=" + metrics.density);

    int computedX;
    int computedY;
    int computedWidth;
    int computedHeight;

    if (width == 0 || height == 0) {
      // Zero dimensions mean full screen
      computedX = 0;
      computedY = 0;
      computedWidth = 0;
      computedHeight = 0;
    } else {
      // Values are in DIP, convert to pixels
      computedX = dipToPixels(x, metrics);
      computedY = dipToPixels(y, metrics);
      computedWidth = dipToPixels(width, metrics);
      computedHeight = dipToPixels(height, metrics);
    }

    startCameraCallbackContext = callbackContext;

    cordova.getActivity().runOnUiThread(new Runnable() {
      @Override
      public void run() {
        // cordova-android 15 draws the window edge-to-edge and keeps only the WebView out of the system bars
        // (layout margins), so the activity content view, where the container is added below, now starts at
        // the device top while the x/y received here are web-page coordinates that start below the status
        // bar. Shift the position by the WebView's offset inside the content view; on older cordova-android
        // that offset is zero, so nothing changes there.
        int[] webViewOffset = getWebViewOffsetInContentView();
        fragment.setRect(computedX + webViewOffset[0], computedY + webViewOffset[1], computedWidth, computedHeight);

        //create or update the layout params for the container view
        FrameLayout containerView = (FrameLayout)cordova.getActivity().findViewById(containerViewId);
        if(containerView == null){
          containerView = new FrameLayout(cordova.getActivity().getApplicationContext());
          containerView.setId(containerViewId);

          FrameLayout.LayoutParams containerLayoutParams = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
          cordova.getActivity().addContentView(containerView, containerLayoutParams);
        }

        //display camera below the webview
        if(toBack){
          View view = webView.getView();
          ViewParent rootParent = containerView.getParent();
          ViewParent curParent = view.getParent();

          view.setBackgroundColor(0x00000000);

          // If parents do not match look for.
          if(curParent.getParent() != rootParent) {
            while(curParent != null && curParent.getParent() != rootParent) {
              curParent = curParent.getParent();
            }

            if(curParent != null) {
              ((ViewGroup)curParent).setBackgroundColor(0x00000000);
              ((ViewGroup)curParent).bringToFront();
            } else {
              // Do default...
              curParent = view.getParent();
              webViewParent = curParent;
              ((ViewGroup)view).bringToFront();
            }
          }else{
            // Default
            webViewParent = curParent;
            rootParent.bringChildToFront(((View) webViewParent));
          }

        }else{
          //set camera back to front
          containerView.setAlpha(opacity);
          containerView.bringToFront();
        }

        try {
          //add the fragment to the container
          FragmentManager fragmentManager = cordova.getActivity().getFragmentManager();
          FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
          fragmentTransaction.add(containerView.getId(), fragment);
          fragmentTransaction.commitAllowingStateLoss();
        } catch (Exception e) {
          Log.e(TAG, "Failed to add camera fragment", e);
          CallbackContext start = startCameraCallbackContext;
          startCameraCallbackContext = null;
          fragment = null;
          if (start != null) {
            start.error("Failed to start camera: " + e.getMessage());
          }
        }

      }
    });

    return true;
  }

  // Left/top offset of the WebView relative to the activity content view (android.R.id.content), i.e. the
  // sum of the positions of the WebView and its ancestors up to that view. Must run on the UI thread.
  private int[] getWebViewOffsetInContentView() {
    int[] offset = new int[2];
    View contentView = cordova.getActivity().findViewById(android.R.id.content);
    View current = webView.getView();
    while (current != null && current != contentView) {
      offset[0] += current.getLeft();
      offset[1] += current.getTop();
      ViewParent parent = current.getParent();
      current = (parent instanceof View) ? (View) parent : null;
    }
    return offset;
  }

  public void onCameraStarted() {
    Log.d(TAG, "Camera started");

    CallbackContext start = startCameraCallbackContext;
    startCameraCallbackContext = null;
    if (start != null) {
      PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, "Camera started");
      pluginResult.setKeepCallback(false);
      start.sendPluginResult(pluginResult);
    }

    CallbackContext switchContext = switchCameraCallbackContext;
    switchCameraCallbackContext = null;
    if (switchContext != null) {
      switchContext.success();
    }
  }

  public void onCameraError(String message) {
    Log.e(TAG, "Camera error: " + message);

    CallbackContext start = startCameraCallbackContext;
    startCameraCallbackContext = null;
    CallbackContext switchContext = switchCameraCallbackContext;
    switchCameraCallbackContext = null;

    if (start != null) {
      // The camera never came up: tear the fragment down so the app can call startCamera again.
      removeFragment();
      start.error(message);
    }
    if (switchContext != null) {
      switchContext.error(message);
    }
  }

  private boolean takeSnapshot(int quality, CallbackContext callbackContext) {
    if(this.hasView(callbackContext) == false){
      return true;
    }

    takeSnapshotCallbackContext = callbackContext;
    fragment.takeSnapshot(quality);
    return true;
  }

  public void onSnapshotTaken(String originalPicture) {
    Log.d(TAG, "returning snapshot");

    CallbackContext context = takeSnapshotCallbackContext;
    takeSnapshotCallbackContext = null;
    if (context == null) {
      return;
    }

    JSONArray data = new JSONArray();
    data.put(originalPicture);

    PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, data);
    pluginResult.setKeepCallback(false);
    context.sendPluginResult(pluginResult);
  }

  public void onSnapshotTakenError(String message) {
    Log.d(TAG, "CameraPreview onSnapshotTakenError: " + message);
    CallbackContext context = takeSnapshotCallbackContext;
    takeSnapshotCallbackContext = null;
    if (context != null) {
      context.error(message);
    }
  }

  private boolean takePicture(int width, int height, int quality, CallbackContext callbackContext) {
    if(this.hasView(callbackContext) == false){
      return true;
    }
    takePictureCallbackContext = callbackContext;

    fragment.takePicture(width, height, quality);

    return true;
  }

  public void onPictureTaken(String originalPicture) {
    Log.d(TAG, "returning picture");

    CallbackContext context = takePictureCallbackContext;
    if (context == null) {
      Log.w(TAG, "Picture taken but nobody is waiting for it");
      return;
    }

    JSONArray data = new JSONArray();
    data.put(originalPicture);

    PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, data);
    // With tapPhoto the same callback keeps receiving pictures taken by tapping the preview.
    pluginResult.setKeepCallback(fragment != null && fragment.tapToTakePicture);
    context.sendPluginResult(pluginResult);
  }

  public void onPictureTakenError(String message) {
    Log.d(TAG, "CameraPreview onPictureTakenError: " + message);
    CallbackContext context = takePictureCallbackContext;
    if (context != null) {
      PluginResult pluginResult = new PluginResult(PluginResult.Status.ERROR, message);
      pluginResult.setKeepCallback(fragment != null && fragment.tapToTakePicture);
      context.sendPluginResult(pluginResult);
    }
  }

  private boolean startRecordVideo(final String camera, final int width, final int height, final int quality, final boolean withFlash, CallbackContext callbackContext) {
    if(this.hasView(callbackContext) == false){
      return true;
    }
    final String filename = "videoTmp";
    VIDEO_FILE_PATH = cordova.getActivity().getCacheDir().toString() + "/";
    startRecordVideoCallbackContext = callbackContext;
    cordova.getThreadPool().execute(new Runnable() {
      @Override
      public void run() {
        CameraActivity current = fragment;
        if (current != null) {
          current.startRecord(getFilePath(filename), camera, width, height, quality, withFlash);
        }
      }
    });

    return true;
  }

  public void onStartRecordVideo() {
    Log.d(TAG, "onStartRecordVideo started");

    CallbackContext context = startRecordVideoCallbackContext;
    startRecordVideoCallbackContext = null;
    if (context != null) {
      PluginResult pluginResult = new PluginResult(PluginResult.Status.OK);
      pluginResult.setKeepCallback(false);
      context.sendPluginResult(pluginResult);
    }
  }

  public void onStartRecordVideoError(String message) {
    Log.d(TAG, "CameraPreview onStartRecordVideoError: " + message);

    CallbackContext context = startRecordVideoCallbackContext;
    startRecordVideoCallbackContext = null;
    if (context != null) {
      context.error(message);
    }
  }

  private boolean stopRecordVideo(CallbackContext callbackContext) {
    if(this.hasView(callbackContext) == false){
      return true;
    }

    stopRecordVideoCallbackContext = callbackContext;

    cordova.getThreadPool().execute(new Runnable() {
      @Override
      public void run() {
        CameraActivity current = fragment;
        if (current != null) {
          current.stopRecord();
        }
      }
    });

    return true;
  }

  public void onStopRecordVideo(String file) {
    Log.d(TAG, "onStopRecordVideo success");

    CallbackContext context = stopRecordVideoCallbackContext;
    stopRecordVideoCallbackContext = null;
    if (context != null) {
      PluginResult result = new PluginResult(PluginResult.Status.OK, file);
      result.setKeepCallback(false);
      context.sendPluginResult(result);
    }
  }

  public void onStopRecordVideoError(String err) {
    Log.d(TAG, "onStopRecordVideo error: " + err);

    CallbackContext context = stopRecordVideoCallbackContext;
    stopRecordVideoCallbackContext = null;
    if (context != null) {
      context.error(err);
    }
  }

  private String getFilePath(String filename) {
    String fileName = filename;

    int i = 1;

    while (new File(VIDEO_FILE_PATH + fileName + VIDEO_FILE_EXTENSION).exists()) {
      // Add number suffix if file exists
      fileName = filename + '_' + i;
      i++;
    }

    return VIDEO_FILE_PATH + fileName + VIDEO_FILE_EXTENSION;
  }

  private boolean setColorEffect(String effect, CallbackContext callbackContext) {
    if(this.hasView(callbackContext) == false){
      return true;
    }

    if (fragment.setColorEffect(effect)) {
      callbackContext.success(effect);
    } else {
      callbackContext.error("Color effect not supported: " + effect);
    }
    return true;
  }

  private boolean getSupportedColorEffects(CallbackContext callbackContext) {
    if(this.hasView(callbackContext) == false){
      return true;
    }

    callbackContext.success(toJsonArray(fragment.getSupportedColorEffects()));
    return true;
  }

  private boolean getExposureModes(CallbackContext callbackContext) {
    if(this.hasView(callbackContext) == false){
      return true;
    }

    callbackContext.success(toJsonArray(fragment.getExposureModes()));
    return true;
  }

  private boolean getExposureMode(CallbackContext callbackContext) {
    if(this.hasView(callbackContext) == false){
      return true;
    }

    callbackContext.success(fragment.getExposureMode());
    return true;
  }

  private boolean setExposureMode(String exposureMode, CallbackContext callbackContext) {
    if(this.hasView(callbackContext) == false){
      return true;
    }

    if (fragment.setExposureMode(exposureMode)) {
      callbackContext.success(exposureMode);
    } else {
      callbackContext.error("Exposure mode not supported: " + exposureMode);
    }
    return true;
  }

  private boolean getExposureCompensation(CallbackContext callbackContext) {
    if(this.hasView(callbackContext) == false){
      return true;
    }

    callbackContext.success(fragment.getExposureCompensation());
    return true;
  }

  private boolean setExposureCompensation(int exposureCompensation, CallbackContext callbackContext) {
    if(this.hasView(callbackContext) == false){
      return true;
    }

    int applied = fragment.setExposureCompensation(exposureCompensation);
    callbackContext.success(applied);
    return true;
  }

  private boolean getExposureCompensationRange(CallbackContext callbackContext) {
    if(this.hasView(callbackContext) == false){
      return true;
    }

    int[] range = fragment.getExposureCompensationRange();
    JSONObject jsonExposureRange = new JSONObject();
    try {
      jsonExposureRange.put("min", range != null ? range[0] : 0);
      jsonExposureRange.put("max", range != null ? range[1] : 0);
    } catch (JSONException e) {
      Log.e(TAG, "getExposureCompensationRange failed to build payload", e);
    }
    callbackContext.success(jsonExposureRange);
    return true;
  }

  private boolean getSupportedWhiteBalanceModes(CallbackContext callbackContext) {
    if(this.hasView(callbackContext) == false){
      return true;
    }

    callbackContext.success(toJsonArray(fragment.getSupportedWhiteBalanceModes()));
    return true;
  }

  private boolean getWhiteBalanceMode(CallbackContext callbackContext) {
    if(this.hasView(callbackContext) == false){
      return true;
    }

    callbackContext.success(fragment.getWhiteBalanceMode());
    return true;
  }

  private boolean setWhiteBalanceMode(String whiteBalanceMode, CallbackContext callbackContext) {
    if(this.hasView(callbackContext) == false){
      return true;
    }

    if (fragment.setWhiteBalanceMode(whiteBalanceMode)) {
      callbackContext.success(whiteBalanceMode);
    } else {
      callbackContext.error("White balance mode not supported: " + whiteBalanceMode);
    }
    return true;
  }

  private boolean getMaxZoom(CallbackContext callbackContext) {
    if(this.hasView(callbackContext) == false){
      return true;
    }

    callbackContext.sendPluginResult(floatResult(fragment.getMaxZoom()));
    return true;
  }

  private boolean getHorizontalFOV(CallbackContext callbackContext) {
    if(this.hasView(callbackContext) == false){
      return true;
    }

    callbackContext.sendPluginResult(floatResult(fragment.getHorizontalFOV()));
    return true;
  }

  private boolean getZoom(CallbackContext callbackContext) {
    if(this.hasView(callbackContext) == false){
      return true;
    }

    callbackContext.sendPluginResult(floatResult(fragment.getZoom()));
    return true;
  }

  private boolean setZoom(float zoom, CallbackContext callbackContext) {
    if(this.hasView(callbackContext) == false){
      return true;
    }

    float applied = fragment.setZoom(zoom);
    callbackContext.sendPluginResult(floatResult(applied));
    return true;
  }

  private boolean setPreviewSize(int width, int height, CallbackContext callbackContext) {
    if(this.hasView(callbackContext) == false){
      return true;
    }

    DisplayMetrics metrics = cordova.getActivity().getResources().getDisplayMetrics();
    int widthPx = width > 0 ? dipToPixels(width, metrics) : 0;
    int heightPx = height > 0 ? dipToPixels(height, metrics) : 0;
    fragment.setPreviewSize(widthPx, heightPx);
    callbackContext.success();
    return true;
  }

  private boolean getSupportedFlashModes(CallbackContext callbackContext) {
    if(this.hasView(callbackContext) == false){
      return true;
    }

    callbackContext.success(toJsonArray(fragment.getSupportedFlashModes()));
    return true;
  }

  private boolean getSupportedFocusModes(CallbackContext callbackContext) {
    if(this.hasView(callbackContext) == false){
      return true;
    }

    callbackContext.success(toJsonArray(fragment.getSupportedFocusModes()));
    return true;
  }

  private boolean getFocusMode(CallbackContext callbackContext) {
    if(this.hasView(callbackContext) == false){
      return true;
    }

    callbackContext.success(fragment.getFocusMode());
    return true;
  }

  private boolean setFocusMode(String focusMode, CallbackContext callbackContext) {
    if(this.hasView(callbackContext) == false){
      return true;
    }

    if (fragment.setFocusMode(focusMode)) {
      callbackContext.success(focusMode);
    } else {
      callbackContext.error("Focus mode not recognised: " + focusMode);
    }
    return true;
  }

  private boolean getFlashMode(CallbackContext callbackContext) {
    if(this.hasView(callbackContext) == false){
      return true;
    }

    callbackContext.success(fragment.getFlashMode());
    return true;
  }

  private boolean setFlashMode(String flashMode, CallbackContext callbackContext) {
    if(this.hasView(callbackContext) == false){
      return true;
    }

    if (fragment.setFlashMode(flashMode)) {
      callbackContext.success(flashMode);
    } else {
      callbackContext.error("Flash mode not recognised: " + flashMode);
    }
    return true;
  }

  private void failPendingCallbacks(String message) {
    CallbackContext[] pending = {
      startCameraCallbackContext, switchCameraCallbackContext, takePictureCallbackContext,
      takeSnapshotCallbackContext, setFocusCallbackContext, startRecordVideoCallbackContext,
      stopRecordVideoCallbackContext
    };
    startCameraCallbackContext = null;
    switchCameraCallbackContext = null;
    takePictureCallbackContext = null;
    takeSnapshotCallbackContext = null;
    setFocusCallbackContext = null;
    startRecordVideoCallbackContext = null;
    stopRecordVideoCallbackContext = null;
    for (CallbackContext context : pending) {
      if (context != null) {
        context.error(message);
      }
    }
  }

  private void removeFragment() {
    final CameraActivity current = fragment;
    fragment = null;
    if (current == null) {
      return;
    }
    // A removed fragment can still report late events (a lens that finishes opening, a camera
    // error). They must not resolve or cancel the callbacks of the next startCamera().
    current.setEventListener(null);
    cordova.getActivity().runOnUiThread(new Runnable() {
      @Override
      public void run() {
        try {
          FragmentManager fragmentManager = cordova.getActivity().getFragmentManager();
          FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
          fragmentTransaction.remove(current);
          fragmentTransaction.commitAllowingStateLoss();
        } catch (Exception e) {
          // prevent "Can not perform this action after onSaveInstanceState"
          Log.w(TAG, "Failed to remove camera fragment", e);
        }
      }
    });
  }

  private boolean stopCamera(CallbackContext callbackContext) {
    if(webViewParent != null) {
      cordova.getActivity().runOnUiThread(new Runnable() {
        @Override
        public void run() {
          ((ViewGroup)webView.getView()).bringToFront();
          webViewParent = null;
        }
      });
    }

    if(this.hasView(callbackContext) == false){
      return true;
    }

    failPendingCallbacks("Camera stopped");
    removeFragment();

    callbackContext.success();
    return true;
  }

  private boolean showCamera(CallbackContext callbackContext) {
    if(this.hasView(callbackContext) == false){
      return true;
    }

    FragmentManager fragmentManager = cordova.getActivity().getFragmentManager();
    FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
    fragmentTransaction.show(fragment);
    fragmentTransaction.commitAllowingStateLoss();

    callbackContext.success();
    return true;
  }

  private boolean hideCamera(CallbackContext callbackContext) {
    if(this.hasView(callbackContext) == false){
      return true;
    }

    FragmentManager fragmentManager = cordova.getActivity().getFragmentManager();
    FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
    fragmentTransaction.hide(fragment);
    fragmentTransaction.commitAllowingStateLoss();

    callbackContext.success();
    return true;
  }

  private boolean tapToFocus(final int pointX, final int pointY, CallbackContext callbackContext) {
    if(this.hasView(callbackContext) == false){
      return true;
    }

    setFocusCallbackContext = callbackContext;
    fragment.setFocus(pointX, pointY);
    return true;
  }

  public void onFocusSet(final int pointX, final int pointY) {
    Log.d(TAG, "Focus set, returning coordinates");

    CallbackContext context = setFocusCallbackContext;
    if (context == null) {
      return;
    }

    JSONObject data = new JSONObject();
    try {
      data.put("x", pointX);
      data.put("y", pointY);
    } catch (JSONException e) {
      Log.d(TAG, "onFocusSet failed to set output payload");
    }

    PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, data);
    pluginResult.setKeepCallback(true);
    context.sendPluginResult(pluginResult);
  }

  public void onFocusSetError(String message) {
    Log.d(TAG, "CameraPreview onFocusSetError: " + message);
    CallbackContext context = setFocusCallbackContext;
    if (context != null) {
      PluginResult pluginResult = new PluginResult(PluginResult.Status.ERROR, message);
      pluginResult.setKeepCallback(true);
      context.sendPluginResult(pluginResult);
    }
  }

  private void setSwitchCallback(CallbackContext callbackContext) {
    CallbackContext previous = switchCameraCallbackContext;
    switchCameraCallbackContext = callbackContext;
    if (previous != null) {
      previous.error("Superseded by another camera switch");
    }
  }

  private boolean switchCamera(final CallbackContext callbackContext) {
    if(this.hasView(callbackContext) == false){
      return true;
    }

    setSwitchCallback(callbackContext);
    cordova.getActivity().runOnUiThread(new Runnable() {
      @Override
      public void run() {
        CameraActivity current = fragment;
        if (current == null || !current.switchCamera()) {
          if (switchCameraCallbackContext == callbackContext) {
            switchCameraCallbackContext = null;
          }
          callbackContext.error("No camera to switch to");
        }
        // Otherwise the result arrives through onCameraStarted / onCameraError.
      }
    });

    return true;
  }

  private boolean switchFocalLength(final CallbackContext callbackContext) {
    if(this.hasView(callbackContext) == false){
      return true;
    }

    setSwitchCallback(callbackContext);
    cordova.getActivity().runOnUiThread(new Runnable() {
      @Override
      public void run() {
        CameraActivity current = fragment;
        int result = current != null ? current.switchFocalLength() : CameraActivity.LENS_RESULT_UNAVAILABLE;
        finishLensSwitch(result, callbackContext);
      }
    });

    return true;
  }

  private boolean switchFocalLength(final float targetFocalLength, final CallbackContext callbackContext) {
    if(this.hasView(callbackContext) == false){
      return true;
    }

    setSwitchCallback(callbackContext);
    cordova.getActivity().runOnUiThread(new Runnable() {
      @Override
      public void run() {
        CameraActivity current = fragment;
        int result = current != null ? current.setFocalLength(targetFocalLength) : CameraActivity.LENS_RESULT_UNAVAILABLE;
        finishLensSwitch(result, callbackContext);
      }
    });

    return true;
  }

  private void finishLensSwitch(int result, CallbackContext callbackContext) {
    if (result == CameraActivity.LENS_RESULT_SWITCHING) {
      // The result arrives through onCameraStarted / onCameraError.
      return;
    }
    if (switchCameraCallbackContext == callbackContext) {
      switchCameraCallbackContext = null;
    }
    if (result == CameraActivity.LENS_RESULT_APPLIED) {
      callbackContext.success();
    } else {
      callbackContext.error("No other lens available");
    }
  }

  private boolean getCurrentFocalLength(CallbackContext callbackContext) {
    if(this.hasView(callbackContext) == false){
      return true;
    }

    callbackContext.sendPluginResult(floatResult(fragment.getCurrentFocalLength()));
    return true;
  }

  private boolean getAvailableFocalLengths(CallbackContext callbackContext) {
    if(this.hasView(callbackContext) == false){
      return true;
    }

    float[] focalLengths = fragment.getAvailableFocalLengths();
    JSONArray focalLengthsArray = new JSONArray();

    try {
      for (float focalLength : focalLengths) {
        focalLengthsArray.put((double) focalLength);
      }
    } catch (JSONException e) {
      Log.d(TAG, "getAvailableFocalLengths failed to set output payload");
    }

    callbackContext.success(focalLengthsArray);
    return true;
  }

  public boolean setBackButtonListener(CallbackContext callbackContext) {
    // Kept for backwards compatibility. On Android the back button is delivered through the
    // Cordova "backbutton" document event, which www/CameraPreview.js listens to.
    tapBackButtonContext = callbackContext;
    PluginResult pluginResult = new PluginResult(PluginResult.Status.NO_RESULT);
    pluginResult.setKeepCallback(true);
    callbackContext.sendPluginResult(pluginResult);
    return true;
  }

  public void onBackButton() {
    if(tapBackButtonContext == null) {
      return;
    }

    Log.d(TAG, "Back button tapped, notifying");

    PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, "Back button pressed");
    pluginResult.setKeepCallback(true); // Keep the callback alive for multiple back button presses
    tapBackButtonContext.sendPluginResult(pluginResult);
  }

  private JSONObject extractCameraData(CameraCharacteristics characteristics) throws JSONException {
    JSONObject cameraData = new JSONObject();

    Integer supportLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
    cameraData.put("INFO_SUPPORTED_HARDWARE_LEVEL", supportLevel != null ? supportLevel : JSONObject.NULL);

    Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
    cameraData.put("LENS_FACING", lensFacing != null ? lensFacing : JSONObject.NULL);

    SizeF sensorInfoPhysicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
    if (sensorInfoPhysicalSize != null) {
      cameraData.put("SENSOR_INFO_PHYSICAL_SIZE_WIDTH", (double) sensorInfoPhysicalSize.getWidth());
      cameraData.put("SENSOR_INFO_PHYSICAL_SIZE_HEIGHT", (double) sensorInfoPhysicalSize.getHeight());
    }

    Size sensorInfoPixelSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
    if (sensorInfoPixelSize != null) {
      cameraData.put("SENSOR_INFO_PIXEL_ARRAY_SIZE_WIDTH", sensorInfoPixelSize.getWidth());
      cameraData.put("SENSOR_INFO_PIXEL_ARRAY_SIZE_HEIGHT", sensorInfoPixelSize.getHeight());
    }

    float[] focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
    JSONArray focalLengthsArray = new JSONArray();
    if (focalLengths != null) {
      for (float focalLength : focalLengths) {
        JSONObject focalLengthsData = new JSONObject();
        focalLengthsData.put("FOCAL_LENGTH", (double) focalLength);
        focalLengthsArray.put(focalLengthsData);
      }
    }
    cameraData.put("LENS_INFO_AVAILABLE_FOCAL_LENGTHS", focalLengthsArray);

    return cameraData;
  }

  private boolean getCameraCharacteristics(CallbackContext callbackContext) {
    if(this.hasCamera(callbackContext) == false){
      return true;
    }

    JSONObject data = new JSONObject();
    JSONArray cameraCharacteristicsArray = new JSONArray();

    CameraManager cManager = (CameraManager) this.cordova.getActivity().getApplicationContext().getSystemService(Context.CAMERA_SERVICE);

    try {
      Set<String> processedCameraIds = new HashSet<>();

      for (String cameraId : cManager.getCameraIdList()) {
        processedCameraIds.add(cameraId);
        try {
          CameraCharacteristics characteristics = cManager.getCameraCharacteristics(cameraId);
          JSONObject cameraData = extractCameraData(characteristics);
          cameraData.put("CAMERA_ID", cameraId);
          cameraCharacteristicsArray.put(cameraData);
        } catch (Exception e) {
          Log.w(TAG, "Failed to read characteristics of camera " + cameraId, e);
        }
      }

      // Probe for hidden physical camera IDs that are not part of getCameraIdList()
      for (int i = 0; i < HIDDEN_CAMERA_PROBE_LIMIT; i++) {
        String cameraId = String.valueOf(i);
        if (processedCameraIds.contains(cameraId)) {
          continue;
        }
        try {
          CameraCharacteristics characteristics = cManager.getCameraCharacteristics(cameraId);
          JSONObject cameraData = extractCameraData(characteristics);
          cameraData.put("CAMERA_ID", cameraId);
          cameraData.put("IS_HIDDEN_CAMERA", true);
          cameraCharacteristicsArray.put(cameraData);
          processedCameraIds.add(cameraId);
        } catch (Exception e) {
          // Camera ID doesn't exist or is not accessible
          Log.d(TAG, "Camera ID " + cameraId + " not available: " + e.getMessage());
        }
      }

      data.put("CAMERA_CHARACTERISTICS", cameraCharacteristicsArray);
    } catch (CameraAccessException e) {
      Log.e(TAG, e.getMessage(), e);
      callbackContext.error("Cannot access camera characteristics: " + e.getMessage());
      return true;
    } catch (JSONException e) {
      Log.d(TAG, "getCameraCharacteristics failed to set output payload");
    }

    callbackContext.success(data);
    return true;
  }

  private String[] getVideoPermissions() {
    // Recordings are written to the app cache directory, which needs no storage permission.
    return new String[] {
      Manifest.permission.CAMERA,
      Manifest.permission.RECORD_AUDIO
    };
  }
}
