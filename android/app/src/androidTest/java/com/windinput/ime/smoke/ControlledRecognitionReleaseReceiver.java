package com.windinput.ime.smoke;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/** Shell-only fixture control declared exclusively in the instrumentation manifest. */
public final class ControlledRecognitionReleaseReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        PendingResult pending = goAsync();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                ControlledRecognitionService.releaseCancelledResults();
            } finally {
                pending.finish();
            }
        }, 250);
    }
}
