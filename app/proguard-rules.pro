# No custom release rules yet.

# Strip verbose/debug/info/warn logging from release builds so track ids and
# playback diagnostics don't accumulate in the system log. Log.e is kept for
# real failures.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}
