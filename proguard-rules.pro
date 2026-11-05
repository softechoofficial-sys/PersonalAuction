# Keep Fabric entrypoints stable; Fabric loads these exact classes from fabric.mod.json.
-keep class de.personalarauction.PresenceRelay {
    public <init>();
    public void onInitialize();
}

# Keep the Fabric client entrypoint stable; Fabric loads this exact class from fabric.mod.json.
-keep class de.personalarauction.PersonalAuctionClient {
    public <init>();
    public void onInitializeClient();
}

# Preserve metadata required by Fabric, Java annotations and generic signatures.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Rename and repackage implementation classes/members to make static inspection harder.
-repackageclasses 'x'
-allowaccessmodification
-useuniqueclassmembernames

# Remove source/debug metadata from the distributable JAR.
-renamesourcefileattribute Source
-keepattributes SourceFile
# Do not preserve line numbers, local variable names or method parameter names.

# Names only: do not shrink or optimize, minimizing behavior changes.
-dontshrink
-dontoptimize
-dontwarn **
-dontusemixedcaseclassnames
