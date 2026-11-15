# Don't obfuscate code
-dontobfuscate

# Our code
-keep class koharubiyori.starlight.screen.streaming.util.input.evdev.* {*;}

# Moonlight common
-keep class koharubiyori.starlight.nvstream.jni.* {*;}

# BouncyCastle
-keep class org.bouncycastle.jcajce.provider.asymmetric.* {*;}
-keep class org.bouncycastle.jcajce.provider.asymmetric.util.* {*;}
-keep class org.bouncycastle.jcajce.provider.asymmetric.rsa.* {*;}
-keep class org.bouncycastle.jcajce.provider.digest.** {*;}
-keep class org.bouncycastle.jcajce.provider.symmetric.** {*;}
-keep class org.bouncycastle.jcajce.spec.* {*;}
-keep class org.bouncycastle.jce.** {*;}
-dontwarn javax.naming.**

# jMDNS
-dontwarn javax.jmdns.impl.DNSCache
-dontwarn org.slf4j.**

# Preserve Protobuf Lite generated classes and fields for DataStore
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
    public static <methods>;
}

# Preserve Protobuf runtime interfaces
-keep interface com.google.protobuf.MessageLite { *; }
-keep interface com.google.protobuf.Parser { *; }