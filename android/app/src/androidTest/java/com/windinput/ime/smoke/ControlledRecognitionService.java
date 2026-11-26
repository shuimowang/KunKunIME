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
    private static final ArrayList<PendingRecognition> pending = new ArrayList<>();
    private static int nextRequestId;
    private final ArrayList<PendingRecognition> owned = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private static final class PendingRecognition {
        final int id;
        final Callback callback;

        PendingRecognition(int id, Callback callback) {
            this.id = id;
            this.callback = callback;
        }
    }

    private static Bundle result(String value) {
        Bundle bundle = new Bundle();
        ArrayList<String> alternatives = new ArrayList<>();
        alternatives.add(value);
        bundle.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, alternatives);
        return bundle;
    }

    @Override
    protected void onStartListening(Intent intent, Callback listener) {
        PendingRecognition request = new PendingRecognition(++nextRequestId, listener);
        pending.add(request);
        owned.add(request);
        try {
            Log.i(TAG, "READY: request=" + request.id + " controlled provider; no audio captured");
            listener.readyForSpeech(Bundle.EMPTY);
            listener.partialResults(result(PARTIAL));
        } catch (android.os.RemoteException error) {
            Log.w(TAG, "Client left before readiness", error);
        }
    }

    @Override
    protected void onStopListening(Callback listener) {
        PendingRecognition request = findOwned(listener);
        if (request == null) {
            Log.w(TAG, "STOP without a matching controlled request");
            return;
        }
        Log.i(TAG, "STOP: request=" + request.id + " real stopListening IPC received");
        try {
            listener.endOfSpeech();
        } catch (android.os.RemoteException error) {
            Log.w(TAG, "Client left before endOfSpeech", error);
        }
        handler.postDelayed(() -> {
            // Normal completion must not look like a pending cancelled request
            // if Android destroys this service immediately after the result.
            pending.remove(request);
            owned.remove(request);
            try {
                listener.results(result(FINAL));
                Log.i(TAG, "FINAL: request=" + request.id + " fixed result returned over recognizer IPC");
            } catch (android.os.RemoteException error) {
                Log.w(TAG, "Client left before final result", error);
            }
        }, 250);
    }

    @Override
    protected void onCancel(Callback listener) {
        PendingRecognition request = findOwned(listener);
        if (request != null) {
            Log.i(TAG, "CANCEL: request=" + request.id + " retained for late-result probe");
        }
    }

    @Override
    public void onDestroy() {
        // SpeechRecognizer.cancel() followed by destroy() can unbind before
        // RecognitionService dispatches its queued onCancel callback. Retain
        // only test callbacks across destruction so the late-IPC probe still
        // exercises that valid lifecycle order, without altering production.
        for (PendingRecognition request : owned) {
            if (pending.contains(request)) {
                Log.i(TAG, "DISCONNECTED: request=" + request.id + " provider destroyed with pending result");
            }
        }
        owned.clear();
        super.onDestroy();
    }

    private PendingRecognition findOwned(Callback listener) {
        for (PendingRecognition request : owned) {
            if (request.callback == listener) return request;
        }
        return null;
    }

    static void releasePendingResult(int requestId) {
        for (PendingRecognition request : new ArrayList<>(pending)) {
            if (request.id != requestId) continue;
            pending.remove(request);
            Log.i(TAG, "LATE_ATTEMPT: request=" + request.id + " sending fixed result after editor change");
            try {
                request.callback.results(result(LATE));
            } catch (android.os.RemoteException error) {
                // Framework rejection is a correct outcome too; the host must
                // remain unchanged either way. The attempt itself is observed.
                Log.i(TAG, "LATE_REJECTED: request=" + request.id + " recognizer connection already closed");
            }
        }
    }
}
