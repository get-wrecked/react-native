/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.react.views.scroll;

import android.graphics.Canvas;
import android.os.Build;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.OverScroller;
import android.widget.ScrollView;
import com.facebook.infer.annotation.Assertions;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.common.ReactConstants;
import com.facebook.react.uimanager.MeasureSpecAssertions;
import com.facebook.react.uimanager.ReactClippingViewGroup;
import com.facebook.react.uimanager.ReactClippingViewGroupHelper;
import com.facebook.react.uimanager.events.NativeGestureUtil;
import com.facebook.react.views.view.ReactViewBackgroundManager;
import java.lang.reflect.Field;
import javax.annotation.Nullable;
import android.annotation.TargetApi;
import android.graphics.drawable.LayerDrawable;
import android.view.ViewGroup;
import com.facebook.react.views.view.ReactViewBackgroundDrawable;

/**
 * A simple subclass of ScrollView that doesn't dispatch measure and layout to its children and has
 * a scroll listener to send scroll events to JS.
 *
 * <p>ReactScrollView only supports vertical scrolling. For horizontal scrolling,
 * use {@link ReactHorizontalScrollView}.
 */
public class ReactScrollView extends ScrollView implements ReactClippingViewGroup, ViewGroup.OnHierarchyChangeListener, View.OnLayoutChangeListener {


  private static final int O_MRI = 27; // todo(pim) move to our own branch in github
  private static Field sScrollerField;
  private static boolean sTriedToGetScrollerField = false;

  private final OnScrollDispatchHelper mOnScrollDispatchHelper = new OnScrollDispatchHelper();
  private final OverScroller mScroller;
  private final VelocityHelper mVelocityHelper = new VelocityHelper();
  private @Nullable Runnable mPostTouchRunnable;
  private @Nullable Rect mClippingRect;
  private boolean mActivelyScrolling;
  private boolean mDoneFlinging;
  private boolean mDragging;
  private boolean mFlinging;
  private boolean mRemoveClippedSubviews;
  private boolean mScrollEnabled = true;
  private boolean mSendMomentumEvents;
  private @Nullable FpsListener mFpsListener = null;
  private @Nullable String mScrollPerfTag;
  private @Nullable Drawable mEndBackground;
  private int mEndFillColor = Color.TRANSPARENT;
  private View mContentView;
  private ReactViewBackgroundManager mReactBackgroundManager;

  private boolean mPagingEnabled = false;
  private int mSnapInterval = 0; // add this line

  public void setPagingEnabled(boolean pagingEnabled) {
    mPagingEnabled = pagingEnabled;
  }

  public void setSnapInterval(int snapInterval) {
    mSnapInterval = snapInterval;
  }

  private int getSnapInterval() {
    if (mSnapInterval != 0) {
      return mSnapInterval;
    }
    return getHeight();
  }

  public ReactScrollView(ReactContext context) {
    this(context, null);
  }

  public ReactScrollView(ReactContext context, @Nullable FpsListener fpsListener) {
    super(context);
    mFpsListener = fpsListener;
    mReactBackgroundManager = new ReactViewBackgroundManager(this);

    if (!sTriedToGetScrollerField) {
      sTriedToGetScrollerField = true;
      try {
        sScrollerField = ScrollView.class.getDeclaredField("mScroller");
        sScrollerField.setAccessible(true);
      } catch (NoSuchFieldException e) {
        Log.w(
          ReactConstants.TAG,
          "Failed to get mScroller field for ScrollView! " +
            "This app will exhibit the bounce-back scrolling bug :(");
      }
    }

    if (sScrollerField != null) {
      try {
        Object scroller = sScrollerField.get(this);
        if (scroller instanceof OverScroller) {
          mScroller = (OverScroller) scroller;
        } else {
          Log.w(
            ReactConstants.TAG,
            "Failed to cast mScroller field in ScrollView (probably due to OEM changes to AOSP)! " +
              "This app will exhibit the bounce-back scrolling bug :(");
          mScroller = null;
        }
      } catch (IllegalAccessException e) {
        throw new RuntimeException("Failed to get mScroller from ScrollView!", e);
      }
    } else {
      mScroller = null;
    }

    setOnHierarchyChangeListener(this);
    setScrollBarStyle(SCROLLBARS_OUTSIDE_OVERLAY);
  }

  public void setSendMomentumEvents(boolean sendMomentumEvents) {
    mSendMomentumEvents = sendMomentumEvents;
  }

  public void setScrollPerfTag(String scrollPerfTag) {
    mScrollPerfTag = scrollPerfTag;
  }

  public void setScrollEnabled(boolean scrollEnabled) {
    mScrollEnabled = scrollEnabled;
  }

  public void flashScrollIndicators() {
    awakenScrollBars();
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    MeasureSpecAssertions.assertExplicitMeasureSpec(widthMeasureSpec, heightMeasureSpec);

    setMeasuredDimension(
        MeasureSpec.getSize(widthMeasureSpec),
        MeasureSpec.getSize(heightMeasureSpec));
  }

  private void smoothScrollToPage(int velocity) {
    int height = getSnapInterval();
    int currentY = getScrollY();
    // TODO (t11123799) - Should we do anything beyond linear accounting of the velocity
    int predictedY = currentY + velocity;
    int page = currentY / height;
    if (predictedY > page * height + height / 2) {
      page = page + 1;
    }
    smoothScrollTo(getScrollX(), page * height);
  }
  @Override
  protected void onLayout(boolean changed, int l, int t, int r, int b) {
    // Call with the present values in order to re-layout if necessary
    scrollTo(getScrollX(), getScrollY());
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    if (mRemoveClippedSubviews) {
      updateClippingRect();
    }
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    if (mRemoveClippedSubviews) {
      updateClippingRect();
    }
  }

  @Override
  protected void onScrollChanged(int x, int y, int oldX, int oldY) {
    super.onScrollChanged(x, y, oldX, oldY);

    if (mOnScrollDispatchHelper.onScrollChanged(x, y)) {
      if (mRemoveClippedSubviews) {
        updateClippingRect();
      }

      if (mFlinging) {
        mDoneFlinging = false;
      }

      ReactScrollViewHelper.emitScrollEvent(
        this,
        mOnScrollDispatchHelper.getXFlingVelocity(),
        mOnScrollDispatchHelper.getYFlingVelocity());
    }
  }

  @Override
  public boolean onInterceptTouchEvent(MotionEvent ev) {
    if (!mScrollEnabled) {
      return false;
    }

    if (super.onInterceptTouchEvent(ev)) {
      NativeGestureUtil.notifyNativeGestureStarted(this, ev);
      ReactScrollViewHelper.emitScrollBeginDragEvent(this);
      mDragging = true;
      enableFpsListener();
      return true;
    }

    return false;
  }

  @Override
  public boolean onTouchEvent(MotionEvent ev) {
    if (!mScrollEnabled) {
      return false;
    }

    mVelocityHelper.calculateVelocity(ev);
    int action = ev.getAction() & MotionEvent.ACTION_MASK;
    if (action == MotionEvent.ACTION_UP && mDragging) {
      ReactScrollViewHelper.emitScrollEndDragEvent(
        this,
        mVelocityHelper.getXVelocity(),
        mVelocityHelper.getYVelocity());
      mDragging = false;
      disableFpsListener();
    }

    return super.onTouchEvent(ev);
  }

  @Override
  public void setRemoveClippedSubviews(boolean removeClippedSubviews) {
    if (removeClippedSubviews && mClippingRect == null) {
      mClippingRect = new Rect();
    }
    mRemoveClippedSubviews = removeClippedSubviews;
    updateClippingRect();
  }

  @Override
  public boolean getRemoveClippedSubviews() {
    return mRemoveClippedSubviews;
  }

  @Override
  public void updateClippingRect() {
    if (!mRemoveClippedSubviews) {
      return;
    }

    Assertions.assertNotNull(mClippingRect);

    ReactClippingViewGroupHelper.calculateClippingRect(this, mClippingRect);
    View contentView = getChildAt(0);
    if (contentView instanceof ReactClippingViewGroup) {
      ((ReactClippingViewGroup) contentView).updateClippingRect();
    }
  }

  @Override
  public void getClippingRect(Rect outClippingRect) {
    outClippingRect.set(Assertions.assertNotNull(mClippingRect));
  }

  @Override
  public void fling(int velocityY) {
    if (mPagingEnabled || mSnapInterval != 0) {
      ReactScrollViewHelper.emitScrollEvent(
              this,
              mOnScrollDispatchHelper.getXFlingVelocity(),
              mOnScrollDispatchHelper.getYFlingVelocity());
      smoothScrollToPage(velocityY);
    } else {
      if (mScroller != null) {
        // FB SCROLLVIEW CHANGE

        // We provide our own version of fling that uses a different call to the standard OverScroller
        // which takes into account the possibility of adding new content while the ScrollView is
        // animating. Because we give essentially no max Y for the fling, the fling will continue as long
        // as there is content. See #onOverScrolled() to see the second part of this change which properly
        // aborts the scroller animation when we get to the bottom of the ScrollView content.

        int scrollWindowHeight = getHeight() - getPaddingBottom() - getPaddingTop();

        if (this.getScaleY() < 0 && Build.VERSION.SDK_INT > O_MRI) {
                velocityY *= -1;
        }


        mScroller.fling(
                getScrollX(),
                getScrollY(),
                0,
                velocityY,
                0,
                0,
                0,
                Integer.MAX_VALUE,
                0,
                scrollWindowHeight / 2);

        postInvalidateOnAnimation();

        // END FB SCROLLVIEW CHANGE
      } else {
        super.fling(velocityY);
      }

      if (mSendMomentumEvents || isScrollPerfLoggingEnabled()) {
        mFlinging = true;
        enableFpsListener();
        ReactScrollViewHelper.emitScrollMomentumBeginEvent(this);
        Runnable r = new Runnable() {
          @Override
          public void run() {
            if (mDoneFlinging) {
              mFlinging = false;
              disableFpsListener();
              ReactScrollViewHelper.emitScrollMomentumEndEvent(ReactScrollView.this);
            } else {
              mDoneFlinging = true;
              ReactScrollView.this.postOnAnimationDelayed(this, ReactScrollViewHelper.MOMENTUM_DELAY);
            }
          }
        };
        postOnAnimationDelayed(r, ReactScrollViewHelper.MOMENTUM_DELAY);
      }
    }
  }

  private void enableFpsListener() {
    if (isScrollPerfLoggingEnabled()) {
      Assertions.assertNotNull(mFpsListener);
      Assertions.assertNotNull(mScrollPerfTag);
      mFpsListener.enable(mScrollPerfTag);
    }
  }

  private void disableFpsListener() {
    if (isScrollPerfLoggingEnabled()) {
      Assertions.assertNotNull(mFpsListener);
      Assertions.assertNotNull(mScrollPerfTag);
      mFpsListener.disable(mScrollPerfTag);
    }
  }

  private boolean isScrollPerfLoggingEnabled() {
    return mFpsListener != null && mScrollPerfTag != null && !mScrollPerfTag.isEmpty();
  }

  private int getMaxScrollY() {
    int contentHeight = mContentView.getHeight();
    int viewportHeight = getHeight() - getPaddingBottom() - getPaddingTop();
    return Math.max(0, contentHeight - viewportHeight);
  }

  @Override
  public void draw(Canvas canvas) {
    if (mEndFillColor != Color.TRANSPARENT) {
      final View content = getChildAt(0);
      if (mEndBackground != null && content != null && content.getBottom() < getHeight()) {
        mEndBackground.setBounds(0, content.getBottom(), getWidth(), getHeight());
        mEndBackground.draw(canvas);
      }
    }
    super.draw(canvas);
  }

  public void setEndFillColor(int color) {
    if (color != mEndFillColor) {
      mEndFillColor = color;
      mEndBackground = new ColorDrawable(mEndFillColor);
    }
  }

  @Override
  protected void onOverScrolled(int scrollX, int scrollY, boolean clampedX, boolean clampedY) {
    if (mScroller != null) {
      // FB SCROLLVIEW CHANGE

      // This is part two of the reimplementation of fling to fix the bounce-back bug. See #fling() for
      // more information.

      if (!mScroller.isFinished() && mScroller.getCurrY() != mScroller.getFinalY()) {
        int scrollRange = getMaxScrollY();
        if (scrollY >= scrollRange) {
          mScroller.abortAnimation();
          scrollY = scrollRange;
        }
      }

      // END FB SCROLLVIEW CHANGE
    }

    super.onOverScrolled(scrollX, scrollY, clampedX, clampedY);
  }

  @Override
  public void onChildViewAdded(View parent, View child) {
    mContentView = child;
    mContentView.addOnLayoutChangeListener(this);
  }

  @Override
  public void onChildViewRemoved(View parent, View child) {
    mContentView.removeOnLayoutChangeListener(this);
    mContentView = null;
  }

  /**
   * Called when a mContentView's layout has changed. Fixes the scroll position if it's too large
   * after the content resizes. Without this, the user would see a blank ScrollView when the scroll
   * position is larger than the ScrollView's max scroll position after the content shrinks.
   */
  @Override
  public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
    if (mContentView == null) {
      return;
    }

    int currentScrollY = getScrollY();
    int maxScrollY = getMaxScrollY();
    if (currentScrollY > maxScrollY) {
      scrollTo(getScrollX(), maxScrollY);
    }
  }

  @Override
  public void setBackgroundColor(int color) {
    mReactBackgroundManager.setBackgroundColor(color);
  }

  public void setBorderWidth(int position, float width) {
    mReactBackgroundManager.setBorderWidth(position, width);
  }

  public void setBorderColor(int position, float color, float alpha) {
    mReactBackgroundManager.setBorderColor(position, color, alpha);
  }

  public void setBorderRadius(float borderRadius) {
    mReactBackgroundManager.setBorderRadius(borderRadius);
  }

  public void setBorderRadius(float borderRadius, int position) {
    mReactBackgroundManager.setBorderRadius(borderRadius, position);
  }

  public void setBorderStyle(@Nullable String style) {
    mReactBackgroundManager.setBorderStyle(style);
  }

  @TargetApi(16)
  private void handlePostTouchScrolling() {
    // If we aren't going to do anything (send events or snap to page), we can early out.
    if (!mSendMomentumEvents && !mPagingEnabled && !isScrollPerfLoggingEnabled()) {
      return;
    }

    // Check if we are already handling this which may occur if this is called by both the touch up
    // and a fling call
    if (mPostTouchRunnable != null) {
      return;
    }

    if (mSendMomentumEvents) {
      ReactScrollViewHelper.emitScrollMomentumBeginEvent(this);
    }

    mActivelyScrolling = false;
    mPostTouchRunnable = new Runnable() {

      private boolean mSnappingToPage = false;

      @Override
      public void run() {
        if (mActivelyScrolling) {
          // We are still scrolling so we just post to check again a frame later
          mActivelyScrolling = false;
          ReactScrollView.this.postOnAnimationDelayed(this, ReactScrollViewHelper.MOMENTUM_DELAY);
        } else {
          boolean doneWithAllScrolling = true;
          if (mPagingEnabled && !mSnappingToPage) {
            // Only if we have pagingEnabled and we have not snapped to the page do we
            // need to continue checking for the scroll.  And we cause that scroll by asking for it
            mSnappingToPage = true;
            smoothScrollToPage(0);
            doneWithAllScrolling = false;
          }
          if (doneWithAllScrolling) {
            if (mSendMomentumEvents) {
              ReactScrollViewHelper.emitScrollMomentumEndEvent(ReactScrollView.this);
            }
            ReactScrollView.this.mPostTouchRunnable = null;
            disableFpsListener();
          } else {
            ReactScrollView.this.postOnAnimationDelayed(this, ReactScrollViewHelper.MOMENTUM_DELAY);
          }
        }
      }

    };
    postOnAnimationDelayed(mPostTouchRunnable, ReactScrollViewHelper.MOMENTUM_DELAY);
  }

}
