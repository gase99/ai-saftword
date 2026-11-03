# JGit loads filesystem/config backends and resource bundles reflectively.
-keep class org.eclipse.jgit.** { *; }
-dontwarn org.eclipse.jgit.**
-dontwarn org.slf4j.**
-dontwarn java.awt.**
-dontwarn javax.naming.**
# Kotlin serialization generates serializers reflectively through companion lookups.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keepclassmembers class com.aicode.glass.core.mcp.McpServer { *** Companion; }
-keepclasseswithmembers class com.aicode.glass.core.mcp.McpServer { kotlinx.serialization.KSerializer serializer(...); }
-keepclassmembers class com.aicode.glass.agent.WireCall { *** Companion; }
-keepclasseswithmembers class com.aicode.glass.agent.WireCall { kotlinx.serialization.KSerializer serializer(...); }
# CommonMark extensions are optional; this app uses the core parser only.
-dontwarn org.commonmark.ext.**
