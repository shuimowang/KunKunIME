package com.windinput.ime.smoke;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;

/**
 * Test-APK-only speech provider. It exercises Android's real recognizer IPC,
 * not speech accuracy: no microphone, model, network or input text is used.
 *
 * This component runs in the test APK's own process, so it deliberately uses
 * Java/Android APIs only rather than Kotlin runtime shared with the target APK.
 */
public final class ControlledRecognitionService extends RecognitionService {
    public static final String TAG = "KunKunVoiceProbe";
    public static final String PARTIAL = "受控语音草稿";
    public static final String FINAL = "语音联调成功";
    public static final String LATE = "已取消的语音不得输入";
    private static final ArrayList<Callback> cancelled = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private static Bundle result(String value) {
        Bundle bundle = new Bundle();
        ArrayList<String> alternatives = new ArrayList<>();
        alternatives.add(value);
        bundle.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, alternatives);
        return bundle;
    }

    @Override
    protected void onStartListening(Intent intent, Callback listener) {
        try {
            listener.readyForSpeech(Bundle.EMPTY);
            listener.partialResults(result(PARTIAL));
            Log.i(TAG, "READY: controlled provider; no audio captured");
        } catch (android.os.RemoteException error) {
            Log.w(TAG, "Client left before readiness", error);
        }
    }

    @Override
    protected void onStopListening(Callback listener) {
        Log.i(TAG, "STOP: real stopListening IPC received");
        try {
            listener.endOfSpeech();
        } catch (android.os.RemoteException error) {
            Log.w(TAG, "Client left before endOfSpeech", error);
        }
        handler.postDelayed(() -> {
            try {
                listener.results(result(FINAL));
                Log.i(TAG, "FINAL: fixed result returned over recognizer IPC");
            } catch (android.os.RemoteException error) {
                Log.w(TAG, "Client left before final result", error);
            }
        }, 250);
    }

    @Override
    protected void onCancel(Callback listener) {
        // Hold this intentionally late result until the test has switched the
        // real editor to password mode. The shell-only receiver then releases it.
        cancelled.add(listener);
        Log.i(TAG, "CANCEL: cancelled callback retained for late-result probe");
    }

    static void releaseCancelledResults() {
        ArrayList<Callback> callbacks = new ArrayList<>(cancelled);
        cancelled.clear();
        for (Callback callback : callbacks) {
            Log.i(TAG, "LATE_ATTEMPT: sending fixed cancelled result after editor change");
            try {
                callback.results(result(LATE));
            } catch (android.os.RemoteException error) {
                // Framework rejection is a correct outcome too; the host must
                // remain unchanged either way. The attempt itself is observed.
                Log.i(TAG, "LATE_REJECTED: recognizer connection already closed");
            }
        }
    }
}
