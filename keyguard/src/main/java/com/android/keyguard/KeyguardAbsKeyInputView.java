/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.keyguard;

import android.content.Context;
import android.os.AsyncTask;
import android.os.CountDownTimer;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.widget.LinearLayout;

import github.tornaco.keyguard.LockPatternChecker;
import github.tornaco.keyguard.R;

/**
 * Base class for PIN and password unlock screens.
 */
public abstract class KeyguardAbsKeyInputView extends LinearLayout
        implements KeyguardSecurityView {
    protected KeyguardSecurityCallback mCallback;
    protected AsyncTask<?, ?, ?> mPendingLockCheck;
    protected SecurityMessageDisplay mSecurityMessageDisplay;
    protected boolean mEnableHaptics;
    private boolean mDismissing;
    private int mMaxCountdownTimes = 0;

    // To avoid accidental lockout due to events while the device in in the pocket, ignore
    // any passwords with length less than or equal to this length.
    protected static final int MINIMUM_PASSWORD_LENGTH_BEFORE_REPORT = 3;

    public KeyguardAbsKeyInputView(Context context) {
        this(context, null);
    }

    public KeyguardAbsKeyInputView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void setKeyguardCallback(KeyguardSecurityCallback callback) {
        mCallback = callback;
    }

    @Override
    public void reset() {
        // start fresh
        mDismissing = false;
        resetPasswordText(false /* animate */, false /* announce */);
        resetState();
    }

    protected abstract int getPasswordTextViewId();

    protected abstract void resetState();

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mSecurityMessageDisplay = KeyguardMessageArea.findSecurityMessageDisplay(this);

        mMaxCountdownTimes = mContext.getResources()
                .getInteger(R.integer.config_max_unlock_countdown_times);
    }

    /*
     * Override this if you have a different string for "wrong password"
     *
     * Note that PIN/PUK have their own implementation of verifyPasswordAndUnlock and so don't need this
     */
    protected int getWrongPasswordStringId() {
        return R.string.kg_wrong_password;
    }

    protected void verifyPasswordAndUnlock() {
        if (mDismissing) return; // already verified but haven't been dismissed; don't do it again.

        final String entry = getPasswordText();
        setPasswordEntryInputEnabled(false);
        if (mPendingLockCheck != null) {
            mPendingLockCheck.cancel(false);
        }

        if (entry.length() <= MINIMUM_PASSWORD_LENGTH_BEFORE_REPORT) {
            // to avoid accidental lockout, only count attempts that are long enough to be a
            // real password. This may require some tweaking.
            setPasswordEntryInputEnabled(true);
            onPasswordChecked(false /* matched */, false /* not valid - too short */);
            return;
        }

        mPendingLockCheck = LockPatternChecker.checkPassword(
                entry,
                new LockPatternChecker.OnCheckCallback() {


                    @Override
                    public void onChecked(boolean matched) {
                        setPasswordEntryInputEnabled(true);
                        mPendingLockCheck = null;
                        if (!matched) {
                            onPasswordChecked(false /* matched */,
                                    true /* isValidPassword */);
                        }
                    }
                });
    }

    private void onPasswordChecked(boolean matched,
                                   boolean isValidPassword) {
        if (matched) {
            mCallback.reportUnlockAttempt(true);
            mDismissing = true;
            mCallback.dismiss(true);
        } else {
            if (isValidPassword) {
                mCallback.reportUnlockAttempt(false);
            }
        }
        resetPasswordText(true /* animate */, !matched /* announce deletion if no match */);
    }

    protected abstract void resetPasswordText(boolean animate, boolean announce);

    protected abstract String getPasswordText();

    protected abstract void setPasswordEntryEnabled(boolean enabled);

    protected abstract void setPasswordEntryInputEnabled(boolean enabled);

    // Prevent user from using the PIN/Password entry until scheduled deadline.
    protected void handleAttemptLockout(long elapsedRealtimeDeadline) {
        setPasswordEntryEnabled(false);
        long elapsedRealtime = SystemClock.elapsedRealtime();
        new CountDownTimer(elapsedRealtimeDeadline - elapsedRealtime, 1000) {

            @Override
            public void onTick(long millisUntilFinished) {
                int secondsRemaining = (int) (millisUntilFinished / 1000);
                mSecurityMessageDisplay.setMessage(
                        R.string.kg_too_many_failed_attempts_countdown, true, secondsRemaining);
            }

            @Override
            public void onFinish() {
                mSecurityMessageDisplay.setMessage("", false);
                resetState();
            }
        }.start();
    }

    protected String getMessageWithCount(int msgId) {
        return getContext().getString(msgId);
    }

    protected void onUserInput() {
        if (mCallback != null) {
            mCallback.userActivity();
        }
        mSecurityMessageDisplay.setMessage("", false);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        onUserInput();
        return false;
    }

    @Override
    public boolean needsInput() {
        return false;
    }

    @Override
    public void onPause() {
        if (mPendingLockCheck != null) {
            mPendingLockCheck.cancel(false);
            mPendingLockCheck = null;
        }
    }

    @Override
    public void onResume(int reason) {
        reset();
    }

    @Override
    public KeyguardSecurityCallback getCallback() {
        return mCallback;
    }

    @Override
    public void showPromptReason(int reason) {
        if (reason != PROMPT_REASON_NONE) {
            int promtReasonStringRes = getPromtReasonStringRes(reason);
            if (promtReasonStringRes != 0) {
                mSecurityMessageDisplay.setMessage(promtReasonStringRes,
                        true /* important */);
            }
        }
    }

    @Override
    public void showMessage(String message, int color) {
        mSecurityMessageDisplay.setNextMessageColor(color);
        mSecurityMessageDisplay.setMessage(message, true /* important */);
    }

    protected abstract int getPromtReasonStringRes(int reason);

    // Cause a VIRTUAL_KEY vibration
    public void doHapticKeyClick() {
        if (mEnableHaptics) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                            | HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        }
    }

    @Override
    public boolean startDisappearAnimation(Runnable finishRunnable) {
        return false;
    }
}
