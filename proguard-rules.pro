# Keep every PersonalAuction class and member unchanged.
# ProGuard's member/exception type rewriting caused VerifyError on Java 25.
-keep class de.personalarauction.** { *; }

# Keep Fabric entrypoint metadata and Java runtime metadata.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,StackMapTable

# Strip optional debug metadata only. Do not shrink, optimize, or repackage classes.
-dontshrink
-dontoptimize
-dontpreverify
-dontwarn **
