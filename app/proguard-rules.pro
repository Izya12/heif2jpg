# ProGuard rules for heif2jpg

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Keep companion object members
-keepclassmembers class kotlinx.coroutines.Dispatchers {
    public static ** DEFAULT;
}

# Keep annotation
-keepattributes *Annotation*
