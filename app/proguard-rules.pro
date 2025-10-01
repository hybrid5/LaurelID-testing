# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# ML Kit barcode scanning relies on reflection to load detectors and
# dynamically referenced models. Keep the public API surface along with the
# generated Google Play Services implementation packages.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode_bundled.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.mlkit_vision_barcode.**
-dontwarn com.google.android.gms.internal.mlkit_vision_barcode_bundled.**

# Ensure NFC payload classes remain available when accessed through parcelable
# extras and system reflection.
-keep class android.nfc.** { *; }

# Retrofit and Moshi rely on generated/reflective adapters. Keep our service
# interfaces and Moshi metadata to prevent stripping required annotations.
-keep interface com.laurelid.network.TrustListApi { *; }
-keep class com.squareup.moshi.** { *; }
-dontwarn com.squareup.moshi.**
