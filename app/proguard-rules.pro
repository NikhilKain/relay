# Relay's protocol messages are kotlinx.serialization classes looked up by serial name.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.vythera.relay.**$$serializer { *; }
-keepclassmembers class com.vythera.relay.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# BouncyCastle is used only to build the device certificate; keep what JCA looks up reflectively.
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.asn1.** { *; }
-keep class org.bouncycastle.cert.** { *; }
-keep class org.bouncycastle.operator.** { *; }
