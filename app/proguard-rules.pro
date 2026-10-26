# kotlinx.serialization and Retrofit ship their own consumer rules.
# Keep generic signatures for Retrofit suspend functions.
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep interface com.dopomine.film.data.api.KinopoiskApi { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
