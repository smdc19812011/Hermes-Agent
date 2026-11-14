-keepattributes Signature
-keepattributes *Annotation*
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**

# usb-serial-for-android locates drivers by reflecting on static getSupportedDevices().
-keep class com.hoho.android.usbserial.driver.** { *; }
