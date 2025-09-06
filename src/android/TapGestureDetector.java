package com.cordovaplugincamerapreview;

import android.content.Context;
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
    return gestureDetector.onTouchEvent(event);
  }

  @Override
  public boolean onDown(MotionEvent e) {
    return true;
  }

  @Override
  public boolean onSingleTapUp(MotionEvent e) {
    if (onTapListener != null) {
      onTapListener.onTap(e);
    }
    return true;
  }

  @Override
  public boolean onSingleTapConfirmed(MotionEvent e) {
    if (onTapListener != null) {
      onTapListener.onTap(e);
    }
    return true;
  }
}
