# Keep BLE service members in release builds. Defensive only: BDD tests
# run against the unminified debug build and read KrakenBleService.state
# directly (no reflection anywhere), so nothing is known to need this —
# but removing it changes release bytecode, which only a housing dive
# would validate. Revisit when a release-build regression test exists.
-keep class ch.fbc.krakenbridge.KrakenBleService { *; }

# Strip every android.util.Log call from release builds. The privacy policy
# and the accessibility disclosure say the app does not log screen content,
# and the adapters log button labels read from the camera/gallery UI. Debug
# builds keep the logs for adb logcat. Uncaught crashes still reach logcat
# through the runtime, which this rule does not touch.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
}
