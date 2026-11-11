# kotlinx.serialization keeps its generated serializers through the bundled consumer rules.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class app.sonora.player.**$$serializer { *; }
-keepclassmembers class app.sonora.player.** { *** Companion; }
-keepclasseswithmembers class app.sonora.player.** { kotlinx.serialization.KSerializer serializer(...); }
# The FFmpeg renderer is loaded by reflection when its AAR is packaged (harmless when absent).
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer { <init>(...); }
-keep class androidx.media3.decoder.ffmpeg.FfmpegLibrary { *; }
