# 目前沒有開啟混淆（isMinifyEnabled = false）。之後開啟 R8 時要保留下面這些（CR-34）。
-keep class com.nuwarobotics.** { *; }
-keep class tw.kebbi.medreminder.api.** { *; }
