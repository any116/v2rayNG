# Resource shrinking requires R8 code shrinking. Keep DEX names stable and disable bytecode
# optimization; the rules below preserve reflection, serialization, native and Android entry points.
-dontobfuscate
-dontoptimize

# Gson reads generic signatures, annotations and TypeToken subclasses at runtime.
-keepattributes Signature,RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,EnclosingMethod
-keep class com.google.gson.reflect.TypeToken
-keep class * extends com.google.gson.reflect.TypeToken

# JSON protocol models, including nested beans stored in Any-typed fields.
# Preserve reflective construction and fields, not unrelated model methods.
-keep class com.v2ray.ang.dto.V2rayConfig {
    <init>(...);
    !static !transient <fields>;
}
-keep class com.v2ray.ang.dto.V2rayConfig$** {
    <init>(...);
    !static !transient <fields>;
}
-keep class com.v2ray.ang.dto.V2rayNShareItem {
    <init>(...);
    !static !transient <fields>;
}
-keep class com.v2ray.ang.dto.V2rayNShareItem$** {
    <init>(...);
    !static !transient <fields>;
}
-keep class com.v2ray.ang.dto.VmessQRCode {
    <init>(...);
    !static !transient <fields>;
}
-keep class com.v2ray.ang.dto.GitHubRelease {
    <init>(...);
    !static !transient <fields>;
}
-keep class com.v2ray.ang.dto.GitHubRelease$Asset {
    <init>(...);
    !static !transient <fields>;
}
-keep class com.v2ray.ang.dto.IPAPIInfo {
    <init>(...);
    !static !transient <fields>;
}
-keep class com.v2ray.ang.dto.IPAPIInfo$LocationBean {
    <init>(...);
    !static !transient <fields>;
}
-keep class com.v2ray.ang.dto.CertSha256Request {
    <init>(...);
    !static !transient <fields>;
}
-keep class com.v2ray.ang.dto.CertSha256Result {
    <init>(...);
    !static !transient <fields>;
}
-keep class com.v2ray.ang.dto.TranslatorsCredit {
    <init>(...);
    !static !transient <fields>;
}
-keep class com.v2ray.ang.dto.Contributor {
    <init>(...);
    !static !transient <fields>;
}

# Import spools, routing exports, dedupe hashes and stored WebDAV settings use Gson.
-keep class com.v2ray.ang.dto.ProfileImportRecord {
    <init>(...);
    !static !transient <fields>;
}
-keep class com.v2ray.ang.data.entities.ProfileItem {
    <init>(...);
    !static !transient <fields>;
}
-keep enum com.v2ray.ang.enums.EConfigType { *; }
-keep class com.v2ray.ang.data.entities.RulesetItem {
    <init>(...);
    !static !transient <fields>;
}
-keep class com.v2ray.ang.data.entities.WebDavConfig {
    <init>(...);
    !static !transient <fields>;
}

# Editor forms are serialized to JSON for saved-state restoration after process death.
-keep class com.v2ray.ang.ui.server.ServerForm {
    <init>(...);
    !static !transient <fields>;
}
-keep class com.v2ray.ang.ui.server.ChainMember {
    <init>(...);
    !static !transient <fields>;
}
-keep class com.v2ray.ang.ui.subscription.SubEditForm {
    <init>(...);
    !static !transient <fields>;
}
-keep class com.v2ray.ang.ui.routing.RoutingForm {
    <init>(...);
    !static !transient <fields>;
}

# Cross-process Intent messages and saved-state objects use Java serialization.
-keep class com.v2ray.ang.** implements java.io.Serializable {
    !static !transient <fields>;
    static final long serialVersionUID;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    private void readObjectNoData();
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Native code resolves generated Go bindings and calls Java callback methods via JNI.
-keep class go.** { *; }
-keep class libv2ray.** { *; }
-keepclassmembers class * implements libv2ray.CoreCallbackHandler { public <methods>; }
-keepclassmembers class * implements libv2ray.ProcessFinder { public <methods>; }
-keepclasseswithmembers class com.v2ray.ang.service.TProxyService {
    native <methods>;
}
-keepclasseswithmembers class com.v2ray.ang.service.TProxyService$Companion {
    native <methods>;
}

# Room instantiates the generated implementation reflectively.
-keep class com.v2ray.ang.data.AppDatabase_Impl { <init>(); }

# WorkManager persists this class name; Hilt supplies its assisted constructor.
-keep class com.v2ray.ang.handler.SubscriptionUpdateWorker {
    public <init>(...);
}
