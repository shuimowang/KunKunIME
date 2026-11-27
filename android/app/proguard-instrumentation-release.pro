# The release test APK runs only through Android's actual IME and InputConnection.
# No app-side startup gate or direct keyboard-view test API is retained.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keep class com.windinput.ime.smoke.RealImeSmokeTest { *; }
-keep class com.windinput.ime.smoke.VoiceImeSmokeTest { *; }
-keep class com.windinput.ime.smoke.OfflineVoiceSmokeTest { *; }
-keep class com.windinput.ime.smoke.ControlledRecognitionService { *; }
-keep class com.windinput.ime.smoke.ControlledRecognitionReleaseReceiver { *; }
