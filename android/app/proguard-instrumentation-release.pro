# The release test APK runs only through Android's actual IME and InputConnection.
# No app-side startup gate or direct keyboard-view test API is retained.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keep class com.windinput.ime.smoke.RealImeSmokeTest { *; }
