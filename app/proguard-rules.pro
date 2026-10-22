# Magpie Pocket Proguard rules
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn okhttp3.**
-dontwarn okio.**
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
