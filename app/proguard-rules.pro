# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep OpenCV classes
-keep class org.opencv.** { *; }

# Keep Gson classes
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class com.tobacco.detection.data.** { *; }

# Keep Apache POI classes
-keep class org.apache.poi.** { *; }
-keep class org.apache.xmlbeans.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
