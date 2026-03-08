# ===========================================================================
# ProGuard / R8 rules for Offline Manifest & Barcode Sync v2.0
# ===========================================================================

# ---- ML Kit Text Recognition (bundled) ----
-keep class com.google.mlkit.vision.text.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text.** { *; }
-dontwarn com.google.mlkit.vision.text.**

# ---- ML Kit Barcode Scanning (bundled) ----
-keep class com.google.mlkit.vision.barcode.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-dontwarn com.google.mlkit.vision.barcode.**

# ---- ML Kit Common ----
-keep class com.google.mlkit.common.** { *; }
-keep class com.google.mlkit.vision.common.** { *; }
-dontwarn com.google.mlkit.**

# ---- CameraX ----
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ---- App data classes (used with Gson for optional JSON export) ----
-keep class com.manifestsync.app.viewmodel.ManifestItem { *; }
-keep class com.manifestsync.app.viewmodel.ExtraItem { *; }

# ---- General Android ----
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
