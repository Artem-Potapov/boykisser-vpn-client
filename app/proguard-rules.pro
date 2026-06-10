# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
-dontobfuscate

# ---------------------------------------------------------------------------
# gomobile / Xray-core bridge (app/libs/xray.aar)
# ---------------------------------------------------------------------------
# bridge/XrayBridge.kt loads these classes by name (Class.forName) and invokes
# StartXray/StopXray reflectively; the Go runtime also calls back into them over
# JNI. R8 sees no direct reference, so without these keeps the classes are
# renamed or stripped and the tunnel fails at runtime. gomobile bind emits no
# consumer ProGuard rules of its own. These are -keep (not just no-obfuscate),
# so they remain correct even if -dontobfuscate above is ever removed.
-keep class xraybridge.** { *; }
-keep class go.** { *; }

# JNI binds native methods by name + signature. (Also present in
# proguard-android-optimize.txt; repeated so it survives independently.)
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ---------------------------------------------------------------------------
# Crash readability
# ---------------------------------------------------------------------------
# Keep line numbers so production stacktraces stay deobfuscatable via the
# mapping.txt emitted at app/build/outputs/mapping/release/.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile