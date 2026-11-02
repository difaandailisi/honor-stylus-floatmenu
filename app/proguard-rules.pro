# ============================================================
# ProGuard Rules for Bluetooth Battery Analyzer
# ============================================================

# ---- Kotlin ----
-keepattributes *Annotation*
-keep class kotlin.Metadata { *; }
-keep class kotlin.coroutines.Continuation
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ---- Keep app classes ----
-keep class com.batteryanalyzer.ble.** { *; }
-keep class com.batteryanalyzer.model.** { *; }
-keep class com.batteryanalyzer.data.** { *; }
-keep class com.batteryanalyzer.viewmodel.** { *; }
-keep class com.batteryanalyzer.notification.** { *; }
-keep class com.batteryanalyzer.service.** { *; }
-keep class com.batteryanalyzer.ui.components.** { *; }

# ---- Room Database ----
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class *
-keepclassmembers @androidx.room.Dao class * { *; }
-dontwarn androidx.room.paging.**

# ---- Compose ----
-keep class androidx.compose.** { *; }
-keepclassmembers class androidx.compose.** { *; }

# ---- Navigation ----
-keep class androidx.navigation.** { *; }

# ---- Glance Widget ----
-keep class com.batteryanalyzer.widget.** { *; }
-keep class androidx.glance.** { *; }

# ---- DataStore ----
-keep class androidx.datastore.** { *; }

# ---- WorkManager ----
-keep class androidx.work.** { *; }

# ---- BLE / Bluetooth ----
-keep class android.bluetooth.** { *; }
-keep class android.bluetooth.le.** { *; }

# ---- Serialization ----
-keepclassmembers class * implements java.io.Serializable { *; }
-keepclassmembers class * implements android.os.Parcelable { *; }

# ---- Enum ----
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---- Resource shrinking ----
-keep class **.R$* { *; }

# ---- Remove logging in release ----
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int d(...);
    public static int v(...);
    public static int i(...);
}

# ---- General ----
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
