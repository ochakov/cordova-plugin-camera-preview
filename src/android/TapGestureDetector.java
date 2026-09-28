package com.cordovaplugincamerapreview;

import android.content.Context;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

class TapGestureDetector extends GestureDetector.SimpleOnGestureListener implements View.OnTouchListener {
  private final String TAG = "TapGestureDetector";
  private GestureDetector gestureDetector;
  private OnTapListener onTapListener;

  public interface OnTapListener {
    void onTap(MotionEvent e);
  }

  public TapGestureDetector(Context context, OnTapListener listener) {
    this.onTapListener = listener;
    this.gestureDetector = new GestureDetector(context, this);
  }

  @Override
  public boolean onTouch(View v, MotionEvent event) {
    Log.d(TAG, "onTouch: action=" + event.getAction() + ", x=" + event.getX() + ", y=" + event.getY());
    boolean result = gestureDetector.onTouchEvent(event);
    Log.d(TAG, "onTouch: gestureDetector returned " + result);
    return result;
  }

  @Override
  public boolean onDown(MotionEvent e) {
    Log.d(TAG, "onDown: x=" + e.getX() + ", y=" + e.getY());
    return true;
  }

  // Only onSingleTapUp is used: onSingleTapConfirmed fires for the same tap ~300ms later and
  // would trigger a second focus / picture.
  @Override
  public boolean onSingleTapUp(MotionEvent e) {
    Log.d(TAG, "onSingleTapUp: x=" + e.getX() + ", y=" + e.getY());
    if (onTapListener != null) {
      onTapListener.onTap(e);
    }
    return true;
  }
}
