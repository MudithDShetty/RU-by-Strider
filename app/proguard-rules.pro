-keep class ai.onnxruntime.** { *; }

# App entry points and generated bindings
-keep class com.strider.ru.BuildConfig { *; }
-keep class com.strider.ru.StriderApp { *; }
-keep class com.strider.ru.MainActivity { *; }
-keep class com.strider.ru.databinding.** { *; }

-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses,EnclosingMethod

# PDF text extraction and rendering
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn org.bouncycastle.**

# ML Kit OCR
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }

# Gson
-keep class com.google.gson.** { *; }

# WorkManager workers
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }
-keepclassmembers class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context,androidx.work.WorkerParameters);
}

# Audio metadata
-keep class org.jaudiotagger.** { *; }
-dontwarn javax.swing.**
-dontwarn java.awt.**
-dontwarn org.jaudiotagger.test.**
-dontwarn javax.imageio.**
-dontwarn javax.imageio.stream.**

# Play Asset Delivery
-keep class com.google.android.play.core.** { *; }
-dontwarn com.google.android.gms.common.annotation.NoNullnessRewrite

# Strip verbose logs from release builds
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
