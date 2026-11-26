# Debug-only cross-APK test entry points. Release never includes this file.
# JNA/UniFFI boundaries are in proguard-rules.pro; shared test runtime ABI is
# in proguard-instrumentation-runtime.pro.

-keep class com.windinput.ime.core.CoreStartupGate {
    public static final com.windinput.ime.core.CoreStartupGate INSTANCE;
    public com.windinput.ime.core.CoreStartupGate$Hold holdForTest();
}
-keep class com.windinput.ime.core.CoreStartupGate$Hold {
    public boolean awaitBlocked(long);
    public void close();
}
-keep class com.windinput.ime.core.RustCoreLoader {
    public static final com.windinput.ime.core.RustCoreLoader INSTANCE;
    public boolean getAvailable();
}
-keep class com.windinput.ime.settings.TestPadActivity {
    public <init>();
    public static final java.lang.String TEST_PAD_DESC;
}

# A11yActionTest, MultiTouchTest and VoiceGestureTest construct these views and call them from the
# separate test APK. App-side callers alone do not stop R8 inlining/removing APIs.
# Kotlin calls with omitted arguments also need their generated default bridges.
-keep class com.windinput.ime.keyboard.KeyboardView {
    public <init>(android.content.Context);
    public void setListener(com.windinput.ime.keyboard.KeyboardView$Listener);
    public boolean onTouchEvent(android.view.MotionEvent);
    public void setLayer(com.windinput.ime.keyboard.KeyboardLayer, com.windinput.ime.keyboard.KeyboardLayer);
    public static void setLayer$default(com.windinput.ime.keyboard.KeyboardView, com.windinput.ime.keyboard.KeyboardLayer, com.windinput.ime.keyboard.KeyboardLayer, int, java.lang.Object);
    public void setSideSymbols(java.util.List);
    public void setSpaceVoiceEnabled(boolean);
    public void setLongPressDelayMs(int);
    public void cancelSpaceVoiceGesture();
}
-keep class com.windinput.ime.keyboard.CandidateBarView {
    public <init>(android.content.Context);
    public void setListener(com.windinput.ime.keyboard.CandidateBarView$Listener);
    public void setCandidates(java.util.List, java.util.List, java.util.List);
    public static void setCandidates$default(com.windinput.ime.keyboard.CandidateBarView, java.util.List, java.util.List, java.util.List, int, java.lang.Object);
}

# The callback implementations live in the test APK. Keep the interface methods
# and their descriptor types so app optimization cannot close this boundary.
-keep,includedescriptorclasses interface com.windinput.ime.keyboard.KeyboardView$Listener {
    public void onKeyboardInteraction();
    public void onKeyAction(com.windinput.ime.keyboard.KeyAction, boolean);
    public void onCursorMove(int);
    public void onBackspaceSelect(int);
    public void onBackspaceSwipeDelete();
    public void onBackspaceDeleteBeforeCursor();
    public void onBackspaceClearAll();
    public void onBackspaceRestore();
    public boolean canRestoreDeleted();
    public void onKeyLongPress(com.windinput.ime.keyboard.KeyAction);
    public boolean onSpaceVoiceStart();
    public void onSpaceVoiceCancelChanged(boolean);
    public void onSpaceVoiceEnd(boolean);
    public com.windinput.ime.keyboard.KeyboardView$KeyOptions longPressOptions(com.windinput.ime.keyboard.KeyAction);
    public void onLongPressOption(com.windinput.ime.keyboard.KeyAction, java.lang.Integer);
    public void onSideCustomize();
}
-keep interface com.windinput.ime.keyboard.CandidateBarView$Listener {
    public void onCandidateSelected(int);
    public void onCandidateLongPressed(int);
}

# Tests inherit default callbacks. Kotlin's JVM-default compatibility mode
# also exposes their DefaultImpls bridges to separately compiled implementors.
-keep class com.windinput.ime.keyboard.KeyboardView$Listener$DefaultImpls {
    public static void onKeyboardInteraction(com.windinput.ime.keyboard.KeyboardView$Listener);
    public static void onBackspaceRestore(com.windinput.ime.keyboard.KeyboardView$Listener);
    public static boolean canRestoreDeleted(com.windinput.ime.keyboard.KeyboardView$Listener);
    public static com.windinput.ime.keyboard.KeyboardView$KeyOptions longPressOptions(com.windinput.ime.keyboard.KeyboardView$Listener, com.windinput.ime.keyboard.KeyAction);
    public static void onLongPressOption(com.windinput.ime.keyboard.KeyboardView$Listener, com.windinput.ime.keyboard.KeyAction, java.lang.Integer);
    public static void onSideCustomize(com.windinput.ime.keyboard.KeyboardView$Listener);
    public static boolean onSpaceVoiceStart(com.windinput.ime.keyboard.KeyboardView$Listener);
    public static void onSpaceVoiceCancelChanged(com.windinput.ime.keyboard.KeyboardView$Listener, boolean);
    public static void onSpaceVoiceEnd(com.windinput.ime.keyboard.KeyboardView$Listener, boolean);
}

# Test inputs and expected callback values cross the same APK boundary.
-keep enum com.windinput.ime.keyboard.KeyboardLayer {
    public static final com.windinput.ime.keyboard.KeyboardLayer NUMBER;
}
-keep class com.windinput.ime.keyboard.KeyAction$Text {
    public <init>(char);
    public boolean equals(java.lang.Object);
}
-keep class com.windinput.ime.keyboard.KeyAction$Backspace {
    public static final com.windinput.ime.keyboard.KeyAction$Backspace INSTANCE;
    public boolean equals(java.lang.Object);
}
-keep class com.windinput.ime.keyboard.KeyAction$Space {
    public static final com.windinput.ime.keyboard.KeyAction$Space INSTANCE;
    public boolean equals(java.lang.Object);
}
