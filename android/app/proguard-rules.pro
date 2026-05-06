# Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.namhyun.reflect.**$$serializer { *; }
-keepclassmembers class com.namhyun.reflect.** {
    *** Companion;
}
-keepclasseswithmembers class com.namhyun.reflect.** {
    kotlinx.serialization.KSerializer serializer(...);
}
