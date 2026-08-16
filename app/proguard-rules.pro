# kotlinx.serialization keeps its serializers in synthetic companion members that R8 cannot see
# being used, so both the @Serializable classes and their generated serializers must survive.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.dimroom.**$$serializer { *; }
-keepclassmembers class com.dimroom.** {
    *** Companion;
}
-keepclasseswithmembers class com.dimroom.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# The edit model is the on-disk sidecar schema: obfuscating enum entry names would silently change
# the JSON a future cloud backend has to read.
-keep class com.dimroom.domain.model.** { *; }
