-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,*Annotation*

-keep class com.base.realtime.service.WatchdogWorker { *; }
-keep class com.base.realtime.data.** { *; }
-keepclassmembers class com.base.realtime.ui.LaunchActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
