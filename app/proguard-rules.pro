-keep class io.legere.pdfiumandroid.** { *; }

# Room-generated database implementations are instantiated reflectively (WorkManager, ML Kit).
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**
