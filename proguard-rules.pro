# Keep the Fabric client entrypoint name stable; Fabric loads it from fabric.mod.json.
-keep class de.personalarauction.PersonalAuctionClient {
    public <init>();
    public void onInitializeClient();
}

# Preserve runtime annotations and generic/lambda metadata used by Java and Fabric.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Obfuscate names only. Do not remove or optimize code, so behavior stays unchanged.
-dontshrink
-dontoptimize
-dontpreverify
-dontwarn **
-dontusemixedcaseclassnames
