# Pocket Budget release shrinking rules.
#
# SQLCipher, Room and AndroidX ship their own consumer rules inside their AARs,
# so nothing needs to be kept here for them.

# Strip verbose/debug/info logging from release builds so no financial or
# diagnostic details reach logcat. Warnings and errors are kept.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
