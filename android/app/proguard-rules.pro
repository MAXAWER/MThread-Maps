# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kz.smartcity.walk.** {
    *** Companion;
}
-keepclasseswithmembers class kz.smartcity.walk.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# WebView ↔ JS мост через WebMessagePort: классы не нужны, правила не требуются.
