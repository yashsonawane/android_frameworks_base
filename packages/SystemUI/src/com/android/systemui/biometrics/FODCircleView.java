/**
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.biometrics;

import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_LOCKOUT;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_TIMEOUT;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.AnimationDrawable;
import android.hardware.biometrics.BiometricSourceType;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.net.Uri;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;

import vendor.lineage.biometrics.fingerprint.inscreen.V1_0.IFingerprintInscreen;
import vendor.lineage.biometrics.fingerprint.inscreen.V1_0.IFingerprintInscreenCallback;

import java.util.NoSuchElementException;
import java.util.Timer;
import java.util.TimerTask;

public class FODCircleView extends ImageView implements ConfigurationListener {

    private final int mPositionX;
    private final int mPositionY;
    private final int mSize;
    private final int mDreamingMaxOffset;
    private final int mNavigationBarSize;
    private final boolean mShouldBoostBrightness;
    private final Paint mPaintFingerprint = new Paint();
    private final WindowManager.LayoutParams mParams = new WindowManager.LayoutParams();
    private final WindowManager mWindowManager;

    private IFingerprintInscreen mFingerprintInscreenDaemon;

    private int mDreamingOffsetY;

    private int mCurrentBrightness;

    private boolean mIsBouncer;
    private boolean mIsDreaming;
    private boolean mIsKeyguard;
    private boolean mIsShowing;
    private boolean mIsCircleShowing;
    private boolean mIsAuthenticated;
    private boolean mCanUnlockWithFp;

    private float mCurrentDimAmount = 0.0f;

    private Handler mHandler;

    private PowerManager mPowerManager;
    private PowerManager.WakeLock mWakeLock;

    private Timer mBurnInProtectionTimer;

    private final boolean mFodPressedImage;

    private FODAnimation mFODAnimation;

    private IFingerprintInscreenCallback mFingerprintInscreenCallback =
            new IFingerprintInscreenCallback.Stub() {
        @Override
        public void onFingerDown() {
            mHandler.post(() -> showCircle());
        }

        @Override
        public void onFingerUp() {
            mHandler.post(() -> hideCircle());
        }
    };

    private KeyguardUpdateMonitor mUpdateMonitor;

    private KeyguardUpdateMonitorCallback mMonitorCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onDreamingStateChanged(boolean dreaming) {
            mIsDreaming = dreaming;
            updateAlpha();

            if (dreaming) {
                mBurnInProtectionTimer = new Timer();
                mBurnInProtectionTimer.schedule(new BurnInProtectionTask(), 0, 60 * 1000);
            } else if (mBurnInProtectionTimer != null) {
                mBurnInProtectionTimer.cancel();
            }
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            mIsKeyguard = showing;

            if (mFODAnimation != null) {
                mFODAnimation.setAnimationKeyguard(mIsKeyguard);
            }
        }

        @Override
        public void onKeyguardBouncerChanged(boolean isBouncer) {
            mIsBouncer = isBouncer;

            if (mUpdateMonitor.isFingerprintDetectionRunning()) {
                final SecurityMode sec = mUpdateMonitor.getSecurityMode();
                final boolean maybeShow = sec == SecurityMode.Pattern ||
                        sec == SecurityMode.PIN;
                if (maybeShow || !mIsBouncer) {
                    show();
                } else {
                    hide();
                }
            } else {
                hide();
            }
        }

        @Override
        public void onBiometricAuthenticated(int userId, BiometricSourceType biometricSourceType) {
            super.onBiometricAuthenticated(userId, biometricSourceType);
            mIsAuthenticated = true;
        }

        @Override
        public void onScreenTurnedOff() {
            hide();
        }

        @Override
        public void onScreenTurnedOn() {
            if (mUpdateMonitor.isFingerprintDetectionRunning()) {
                show();
            }
        }

        @Override
        public void onBiometricHelp(int msgId, String helpString,
                BiometricSourceType biometricSourceType) {
            if (biometricSourceType == BiometricSourceType.FINGERPRINT &&
                    msgId == -1){ // Auth error
                hideCircle();
                mHandler.post(() -> mFODAnimation.hideFODanimation());
            }
        }

        @Override
        public void onStrongAuthStateChanged(int userId) {
            mCanUnlockWithFp = canUnlockWithFp();
            if (mIsShowing && !mCanUnlockWithFp) {
                hide();
            }
        }
    };

    private class BrightnessObserver extends ContentObserver {
        Context mContext;

        BrightnessObserver(Context context, Handler handler) {
            super(handler);
            mContext = context;
        }

        void registerBrightnessListener() {
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(
                    Settings.System.SCREEN_BRIGHTNESS),
                    false, this, UserHandle.USER_ALL);
            updateCurrentBrightnessValue();
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            if (uri.equals(Settings.System.getUriFor(
                    Settings.System.SCREEN_BRIGHTNESS))) {
                updateCurrentBrightnessValue();
            }
        }

        public void updateCurrentBrightnessValue() {
            int currentBrightness = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, 0,
                    UserHandle.USER_CURRENT);
            mCurrentBrightness = currentBrightness;
            updateDim();
        }
    }

    private boolean canUnlockWithFp() {
        int currentUser = ActivityManager.getCurrentUser();
        boolean biometrics = mUpdateMonitor.isUnlockingWithBiometricsPossible(currentUser);
        KeyguardUpdateMonitor.StrongAuthTracker strongAuthTracker =
                mUpdateMonitor.getStrongAuthTracker();
        int strongAuth = strongAuthTracker.getStrongAuthForUser(currentUser);
        if (biometrics && !strongAuthTracker.hasUserAuthenticatedSinceBoot()) {
            return false;
        } else if (biometrics && (strongAuth & STRONG_AUTH_REQUIRED_AFTER_TIMEOUT) != 0) {
            return false;
        } else if (biometrics && (strongAuth & STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW) != 0) {
            return false;
        } else if (biometrics && (strongAuth & STRONG_AUTH_REQUIRED_AFTER_LOCKOUT) != 0) {
            return false;
        } else if (biometrics && (strongAuth & STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN) != 0) {
            return false;
        }
        return true;
    }

    private boolean mCutoutMasked;
    private int mStatusbarHeight;

    private BrightnessObserver mBrightnessObserver;

    public FODCircleView(Context context) {
        super(context);

        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        if (daemon == null) {
            throw new RuntimeException("Unable to get IFingerprintInscreen");
        }

        try {
            mShouldBoostBrightness = daemon.shouldBoostBrightness();
            mPositionX = daemon.getPositionX();
            mPositionY = daemon.getPositionY();
            mSize = daemon.getSize();
        } catch (RemoteException e) {
            throw new RuntimeException("Failed to retrieve FOD circle position or size");
        }

        Resources res = context.getResources();

        mPaintFingerprint.setAntiAlias(true);
        mPaintFingerprint.setColor(res.getColor(R.color.config_fodColor));

        mWindowManager = context.getSystemService(WindowManager.class);

        mNavigationBarSize = res.getDimensionPixelSize(R.dimen.navigation_bar_size);

        mDreamingMaxOffset = (int) (mSize * 0.1f);

        mHandler = new Handler(Looper.getMainLooper());

        mParams.height = mSize;
        mParams.width = mSize;
        mParams.format = PixelFormat.TRANSLUCENT;

        mParams.setTitle("Fingerprint on display");
        mParams.packageName = "android";
        mParams.type = WindowManager.LayoutParams.TYPE_DISPLAY_OVERLAY;
        mParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_DIM_BEHIND |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        mParams.gravity = Gravity.TOP | Gravity.LEFT;

        mWindowManager.addView(this, mParams);

        updatePosition();
        hide();

        mUpdateMonitor = KeyguardUpdateMonitor.getInstance(context);
        mUpdateMonitor.registerCallback(mMonitorCallback);

        mBrightnessObserver = new BrightnessObserver(context, mHandler);
        mBrightnessObserver.registerBrightnessListener();

        mCanUnlockWithFp = canUnlockWithFp();

        updateCutoutFlags();

        Dependency.get(ConfigurationController.class).addCallback(this);

        getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            float drawingDimAmount = mParams.dimAmount;
            if (mCurrentDimAmount == 0.0f && drawingDimAmount > 0.0f) {
                dispatchPress();
                mCurrentDimAmount = drawingDimAmount;
            } else if (mCurrentDimAmount > 0.0f && drawingDimAmount == 0.0f) {
                mCurrentDimAmount = drawingDimAmount;
            }
        });

        mPowerManager = context.getSystemService(PowerManager.class);
        mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                FODCircleView.class.getSimpleName());

        mFodPressedImage = res.getBoolean(R.bool.config_fodPressedImage);

        mFODAnimation = new FODAnimation(context, mPositionX, mPositionY);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (mIsCircleShowing) {
            canvas.drawCircle(mSize / 2, mSize / 2, mSize / 2.0f, mPaintFingerprint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getAxisValue(MotionEvent.AXIS_X);
        float y = event.getAxisValue(MotionEvent.AXIS_Y);

        boolean newIsInside = (x > 0 && x < mSize) && (y > 0 && y < mSize);

        if (event.getAction() == MotionEvent.ACTION_DOWN && newIsInside) {
            showCircle();
            mHandler.post(() -> mFODAnimation.showFODanimation());
            return true;
        } else if (event.getAction() == MotionEvent.ACTION_UP) {
            hideCircle();
            mHandler.post(() -> mFODAnimation.hideFODanimation());
            return true;
        } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
            return true;
        }

        mHandler.post(() -> mFODAnimation.hideFODanimation());
        return false;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        updatePosition();
    }

    public IFingerprintInscreen getFingerprintInScreenDaemon() {
        if (mFingerprintInscreenDaemon == null) {
            try {
                mFingerprintInscreenDaemon = IFingerprintInscreen.getService();
                if (mFingerprintInscreenDaemon != null) {
                    mFingerprintInscreenDaemon.setCallback(mFingerprintInscreenCallback);
                    mFingerprintInscreenDaemon.asBinder().linkToDeath((cookie) -> {
                        mFingerprintInscreenDaemon = null;
                    }, 0);
                }
            } catch (NoSuchElementException | RemoteException e) {
                // do nothing
            }
        }
        return mFingerprintInscreenDaemon;
    }

    public void dispatchPress() {
        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        try {
            daemon.onPress();
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void dispatchRelease() {
        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        try {
            daemon.onRelease();
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void dispatchShow() {
        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        try {
            daemon.onShowFODView();
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void dispatchHide() {
        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        try {
            daemon.onHideFODView();
        } catch (RemoteException e) {
            // do nothing
        }
    }

    public void showCircle() {
        if (mIsAuthenticated) {
            return;
        }

        mIsCircleShowing = true;

        setKeepScreenOn(true);

        if (mIsDreaming) {
            mWakeLock.acquire(300);
        }

        updateDim();
        updateBoost();
        updateAlpha();

        if (mFodPressedImage) {
            setImageResource(R.drawable.fod_icon_pressed);
        } else {
            setImageDrawable(null);
        }

        invalidate();
    }

    public void hideCircle() {
        mIsCircleShowing = false;

        setImageResource(R.drawable.fod_icon_default);
        invalidate();

        dispatchRelease();

        updateBoost();
        updateDim();
        updateAlpha();

        setKeepScreenOn(false);
    }

    public void show() {
        if (!mUpdateMonitor.isScreenOn()) {
            // Keyguard is shown just after screen turning off
            return;
        }

        if (mIsBouncer) {
            // Ignore show calls when Keyguard pin screen is being shown
            return;
        }

        if (!mCanUnlockWithFp) {
            // Ignore when unlocking with fp is not possible
            return;
        }

        mIsShowing = true;
        mIsAuthenticated = false;

        updatePosition();

        dispatchShow();
        setVisibility(View.VISIBLE);
    }

    public void hide() {
        mIsShowing = false;

        setVisibility(View.GONE);
        hideCircle();
        dispatchHide();
    }

    private void updateAlpha() {
        if (mIsCircleShowing) {
            setAlpha(1.0f);
        } else {
            setAlpha(mIsDreaming ? 0.75f : 1.0f);
        }
    }

    private void updatePosition() {
        Display defaultDisplay = mWindowManager.getDefaultDisplay();

        Point size = new Point();
        defaultDisplay.getRealSize(size);

        int rotation = defaultDisplay.getRotation();
        int cutoutMaskedExtra = mCutoutMasked ? mStatusbarHeight : 0;

        switch (rotation) {
            case Surface.ROTATION_0:
                mParams.x = mPositionX;
                mParams.y = mPositionY - cutoutMaskedExtra;
                break;
            case Surface.ROTATION_90:
                mParams.x = mPositionY;
                mParams.y = mPositionX - cutoutMaskedExtra;
                break;
            case Surface.ROTATION_180:
                mParams.x = mPositionX;
                mParams.y = size.y - mPositionY - mSize - cutoutMaskedExtra;
                break;
            case Surface.ROTATION_270:
                mParams.x = size.x - mPositionY - mSize - mNavigationBarSize - cutoutMaskedExtra;
                mParams.y = mPositionX;
                break;
            default:
                throw new IllegalArgumentException("Unknown rotation: " + rotation);
        }

        if (mIsDreaming) {
            mParams.y += mDreamingOffsetY;
            mFODAnimation.updateParams(mParams.y);
        }

        mWindowManager.updateViewLayout(this, mParams);
    }

    private void updateDim() {
        if (mIsCircleShowing) {
            int dimAmount = 0;

            IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
            try {
                dimAmount = daemon.getDimAmount(mCurrentBrightness);
            } catch (RemoteException e) {
                return;
            }

            mParams.dimAmount = dimAmount / 255.0f;
        } else {
            mParams.dimAmount = 0.0f;
        }

        mWindowManager.updateViewLayout(this, mParams);
    }

    private void updateBoost() {
        if (mShouldBoostBrightness) {
            if (mIsCircleShowing) {
                mParams.screenBrightness = 1.0f;
            } else {
                mParams.screenBrightness = 0.0f;
            }

            mWindowManager.updateViewLayout(this, mParams);
        }
    }

    private class BurnInProtectionTask extends TimerTask {
        @Override
        public void run() {
            long now = System.currentTimeMillis() / 1000 / 60;
            // Let y to be not synchronized with x, so that we get maximum movement
            mDreamingOffsetY = (int) ((now + mDreamingMaxOffset / 3) % (mDreamingMaxOffset * 2));
            mDreamingOffsetY -= mDreamingMaxOffset;

            mHandler.post(() -> updatePosition());
        }
    };

    @Override
    public void onOverlayChanged() {
        updateCutoutFlags();
    }

    private void updateCutoutFlags() {
        mStatusbarHeight = getContext().getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height_portrait);
        boolean cutoutMasked = getContext().getResources().getBoolean(
                com.android.internal.R.bool.config_maskMainBuiltInDisplayCutout);
        if (mCutoutMasked != cutoutMasked) {
            mCutoutMasked = cutoutMasked;
            updatePosition();
        }
    }
}
