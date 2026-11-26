# The instrumentation APK calls these exact APIs in the installed preview APK.
# They are not all reachable from app code, so keep this small cross-APK contract.
# JNA/UniFFI reflection and native callbacks are covered by proguard-rules.pro.

# The test APK shares the app's Kotlin stdlib. Its separate R8 pass applies the
# app mapping, but cannot restore APIs already removed by the app's R8 pass.
# Preserve the runtime's public/protected ABI, including file facades (LazyKt,
# FilesKt, CollectionsKt, StringsKt), their parent implementations and fields.
# Do not restrict class visibility: public static functions can be declared on
# package-private file-part parents. This matches kotlin.*, not kotlinx.*;
# release does not include this preview-only instrumentation contract.
-keep class kotlin.** {
    public *;
    protected *;
}

# AndroidJUnitRunner and its tracer call these shared AndroidX tracing APIs.
# forceEnableAppTracing is otherwise unused by the app and was removed by R8.
-keep class androidx.tracing.Trace {
    public static void beginSection(java.lang.String);
    public static void endSection();
    public static void forceEnableAppTracing();
}

# AGP also packages AndroidX Test's ActivityScenario/capture/future helpers in
# the test APK. Their bytecode shares these app-side APIs (test core 1.6.1,
# concurrent-futures-ktx 1.1.0, coroutines 1.10.2). Keep the referenced members
# and callback contracts, not entire AndroidX or kotlinx packages. Recheck this
# boundary when upgrading the test libraries or adding new instrumentation APIs.
-keep,includedescriptorclasses class androidx.concurrent.futures.CallbackToFutureAdapter {
    public static com.google.common.util.concurrent.ListenableFuture getFuture(androidx.concurrent.futures.CallbackToFutureAdapter$Resolver);
}
-keep,includedescriptorclasses class androidx.concurrent.futures.CallbackToFutureAdapter$Completer {
    public boolean set(java.lang.Object);
    public boolean setException(java.lang.Throwable);
}
-keep,includedescriptorclasses interface androidx.concurrent.futures.CallbackToFutureAdapter$Resolver {
    public java.lang.Object attachCompleter(androidx.concurrent.futures.CallbackToFutureAdapter$Completer);
}
-keep,includedescriptorclasses class androidx.concurrent.futures.ResolvableFuture {
    public static androidx.concurrent.futures.ResolvableFuture create();
    public boolean set(java.lang.Object);
    public boolean setException(java.lang.Throwable);
}
-keep,includedescriptorclasses class androidx.concurrent.futures.AbstractResolvableFuture {
    public void addListener(java.lang.Runnable, java.util.concurrent.Executor);
    public boolean cancel(boolean);
    public java.lang.Object get();
    public java.lang.Object get(long, java.util.concurrent.TimeUnit);
    public boolean isCancelled();
    public boolean isDone();
    # Called across the APK boundary from the same package in futures-ktx.
    static java.lang.Object getUninterruptibly(java.util.concurrent.Future);
}
-keep,includedescriptorclasses class androidx.concurrent.futures.DirectExecutor {
    public static final androidx.concurrent.futures.DirectExecutor INSTANCE;
    public void execute(java.lang.Runnable);
}
-keep interface com.google.common.util.concurrent.ListenableFuture {
    public void addListener(java.lang.Runnable, java.util.concurrent.Executor);
}
-keep enum androidx.lifecycle.Lifecycle$State {
    public static final androidx.lifecycle.Lifecycle$State CREATED;
    public static final androidx.lifecycle.Lifecycle$State DESTROYED;
    public static final androidx.lifecycle.Lifecycle$State RESUMED;
    public static final androidx.lifecycle.Lifecycle$State STARTED;
    public static androidx.lifecycle.Lifecycle$State[] values();
}

-keep,includedescriptorclasses class kotlinx.coroutines.BuildersKt {
    public static kotlinx.coroutines.Deferred async(kotlinx.coroutines.CoroutineScope, kotlin.coroutines.CoroutineContext, kotlinx.coroutines.CoroutineStart, kotlin.jvm.functions.Function2);
    public static java.lang.Object runBlocking(kotlin.coroutines.CoroutineContext, kotlin.jvm.functions.Function2);
}
-keep,includedescriptorclasses interface kotlinx.coroutines.CancellableContinuation {
    public void resume(java.lang.Object, kotlin.jvm.functions.Function1);
    public void invokeOnCancellation(kotlin.jvm.functions.Function1);
}
-keep,includedescriptorclasses class kotlinx.coroutines.CancellableContinuation$DefaultImpls {
    public static boolean cancel$default(kotlinx.coroutines.CancellableContinuation, java.lang.Throwable, int, java.lang.Object);
}
-keep,includedescriptorclasses class kotlinx.coroutines.CancellableContinuationImpl {
    public <init>(kotlin.coroutines.Continuation, int);
    public void initCancellability();
    public java.lang.Object getResult();
}
-keep,includedescriptorclasses interface kotlinx.coroutines.CoroutineScope {
    public kotlin.coroutines.CoroutineContext getCoroutineContext();
}
-keep enum kotlinx.coroutines.CoroutineStart {
    public static final kotlinx.coroutines.CoroutineStart DEFAULT;
    public static final kotlinx.coroutines.CoroutineStart UNDISPATCHED;
}
-keep,includedescriptorclasses interface kotlinx.coroutines.Deferred {
    public java.lang.Object await(kotlin.coroutines.Continuation);
}
-keep,includedescriptorclasses class kotlinx.coroutines.Dispatchers {
    public static kotlinx.coroutines.MainCoroutineDispatcher getMain();
    public static kotlinx.coroutines.CoroutineDispatcher getUnconfined();
}
-keep,includedescriptorclasses class kotlinx.coroutines.ExecutorsKt {
    public static kotlinx.coroutines.CoroutineDispatcher from(java.util.concurrent.Executor);
}
-keep,includedescriptorclasses class kotlinx.coroutines.Job$DefaultImpls {
    public static void cancel$default(kotlinx.coroutines.Job, java.util.concurrent.CancellationException, int, java.lang.Object);
}
-keep,includedescriptorclasses class kotlinx.coroutines.TimeoutKt {
    public static java.lang.Object withTimeout-KLykuaI(long, kotlin.jvm.functions.Function2, kotlin.coroutines.Continuation);
}

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

# A11yActionTest and MultiTouchTest construct these views and call them from the
# separate test APK. App-side callers alone do not stop R8 inlining/removing APIs.
# Kotlin calls with omitted arguments also need their generated default bridges.
-keep class com.windinput.ime.keyboard.KeyboardView {
    public <init>(android.content.Context);
    public void setListener(com.windinput.ime.keyboard.KeyboardView$Listener);
    public boolean onTouchEvent(android.view.MotionEvent);
    public void setLayer(com.windinput.ime.keyboard.KeyboardLayer, com.windinput.ime.keyboard.KeyboardLayer);
    public static void setLayer$default(com.windinput.ime.keyboard.KeyboardView, com.windinput.ime.keyboard.KeyboardLayer, com.windinput.ime.keyboard.KeyboardLayer, int, java.lang.Object);
    public void setSideSymbols(java.util.List);
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
    public void onKeyAction(com.windinput.ime.keyboard.KeyAction, boolean);
    public void onCursorMove(int);
    public void onBackspaceSelect(int);
    public void onBackspaceSwipeDelete();
    public void onBackspaceDeleteBeforeCursor();
    public void onBackspaceClearAll();
    public void onBackspaceRestore();
    public boolean canRestoreDeleted();
    public void onKeyLongPress(com.windinput.ime.keyboard.KeyAction);
    public com.windinput.ime.keyboard.KeyboardView$KeyOptions longPressOptions(com.windinput.ime.keyboard.KeyAction);
    public void onLongPressOption(com.windinput.ime.keyboard.KeyAction, java.lang.Integer);
    public void onSideCustomize();
}
-keep interface com.windinput.ime.keyboard.CandidateBarView$Listener {
    public void onCandidateSelected(int);
    public void onCandidateLongPressed(int);
}

# Tests inherit these five callbacks. Kotlin's JVM-default compatibility mode
# also exposes their DefaultImpls bridges to separately compiled implementors.
-keep class com.windinput.ime.keyboard.KeyboardView$Listener$DefaultImpls {
    public static void onBackspaceRestore(com.windinput.ime.keyboard.KeyboardView$Listener);
    public static boolean canRestoreDeleted(com.windinput.ime.keyboard.KeyboardView$Listener);
    public static com.windinput.ime.keyboard.KeyboardView$KeyOptions longPressOptions(com.windinput.ime.keyboard.KeyboardView$Listener, com.windinput.ime.keyboard.KeyAction);
    public static void onLongPressOption(com.windinput.ime.keyboard.KeyboardView$Listener, com.windinput.ime.keyboard.KeyAction, java.lang.Integer);
    public static void onSideCustomize(com.windinput.ime.keyboard.KeyboardView$Listener);
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
