# Hello and HelloCloud: a Java shim is the main class; it loads the Kotlin delegate by name.
-keep class * extends org.bukkit.plugin.java.JavaPlugin { <init>(); }
-keep class * implements co.xenastudios.m0.**.loader.Delegate { <init>(...); }
-keepclassmembers class * implements org.bukkit.event.Listener { @org.bukkit.event.EventHandler <methods>; }
# Library classes these jars don't use but the Bukkit API mentions.
-dontwarn com.google.**
-dontwarn com.avaje.**
-dontwarn org.yaml.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn org.apiguardian.**
