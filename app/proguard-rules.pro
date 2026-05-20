# libsu (Root)
-keep class com.topjohnwu.superuser.** { *; }

# Compose
-keep class androidx.compose.runtime.** { *; }

# Foreground Service
-keep class com.cairn.app.service.** { *; }

# Notification CallStyle
-keep class androidx.core.app.NotificationCompat$CallStyle { *; }
-keep class androidx.core.app.Person { *; }

# DataStore
-keep class androidx.datastore.** { *; }
