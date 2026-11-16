# Locker R8 规则
#
# 应用本身不依赖反射：Room 通过生成的 *_Impl 直接调用，JSON 用 org.json，
# 二维码用 ZXing。下面是第三方库的保守保留规则，避免 R8 裁掉运行时才用到的类。

# ---- Room ----
# Room 通过类名反射查找生成的实现类（如 AppDatabase_Impl）
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class **.*_Impl { *; }
-keepclassmembers class * {
    @androidx.room.* <fields>;
}
-dontwarn androidx.room.**

# ---- ML Kit 文字识别（内置拉丁模型）----
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# ---- ZXing ----
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ---- Kotlin 协程 / 序列化相关（保守）----
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# ---- 桌面小组件 ----
# AppWidgetProvider 由系统按清单里的类名实例化，必须保留
-keep class com.yxsdc.locker.widget.** { *; }
-keep class * extends android.appwidget.AppWidgetProvider { *; }
# RemoteViews 用反射调用 setBackgroundResource / setChronometer 等方法名前缀，
# 保留 View 上被 RemoteViews 用到的 setter 名称
-keepclassmembers class * extends android.view.View {
    public void set*(***);
}

# 保留行号与源文件名，便于崩溃栈定位
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
