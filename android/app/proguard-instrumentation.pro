# smoke-test.sh selects these classes by name through AndroidJUnitRunner's -e class.
# Keep JUnit's runtime annotations and these test entry points in the separate test
# APK; AGP handles references to the tested app using the same build's mapping.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keep class com.windinput.ime.smoke.RealImeSmokeTest { *; }
-keep class com.windinput.ime.smoke.VoiceImeSmokeTest { *; }
-keep class com.windinput.ime.smoke.OfflineVoiceSmokeTest { *; }
-keep class com.windinput.ime.smoke.PersonalDictionaryUiTest { *; }
-keep class com.windinput.ime.service.EditorConnectionTest { *; }
-keep class com.windinput.ime.smoke.ControlledRecognitionService { *; }
-keep class com.windinput.ime.smoke.ControlledRecognitionReleaseReceiver { *; }
-keep class com.windinput.ime.keyboard.VoiceGestureTest { *; }
-keep class com.windinput.ime.keyboard.A11yActionTest {
    public <init>();
    public void clickingKeyNodeTypesTheKey();
    public void candidateNodeReadsCommentAndSelectsOnClick();
}
-keep class com.windinput.ime.keyboard.MultiTouchTest {
    public <init>();
    public void secondFingerDownCommitsFirstKeyAndTypesBoth();
    public void laterFingerLiftingFirstStillTypesEachKeyOnce();
    public void secondFingerOnNumberPadSideColumnDoesNotTypeANeighbourKey();
    public void movesOfAFinishedFingerDoNotCancelTheActiveKey();
}
