# SDK 35 Migration Changelog

## Build & Tooling
- build.gradle: AGP 8.6.1 + Kotlin Gradle Plugin 1.9.24, SDK-Levels auf 35 gesetzt.
- gradle/wrapper/gradle-wrapper.properties: Gradle 8.7 Wrapper aktualisiert.
- app/build.gradle: Kotlin-Android Plugin aktiviert, Java/Kotlin auf 17, Dependencies modernisiert, SDK-Level 35 gesetzt.

## Manifest & Permissions
- app/src/main/AndroidManifest.xml: Legacy-Storage entfernt, Application-Entry ergänzt.

## Kotlin Migration & Edge-to-edge
- app/src/main/java/ch/blinkenlights/android/vanilla/PlaybackActivity.kt: Basisklasse Kotlin-idiomatisch migriert.
- app/src/main/java/ch/blinkenlights/android/vanilla/SlidingPlaybackActivity.kt: Basisklasse Kotlin-idiomatisch migriert.
- app/src/main/java/ch/blinkenlights/android/vanilla/PermissionRequestActivity.kt: Permissions modernisiert (API 35 only) und Kotlin-Migration.
- app/src/main/java/ch/blinkenlights/android/vanilla/EdgeToEdgeHelper.kt: zentrale Insets-/Edge-to-edge-Logik.
- app/src/main/java/ch/blinkenlights/android/vanilla/VanillaApplication.kt: globale Edge-to-edge-Initialisierung.
- app/src/main/res/values/ids.xml: Tag-Id für Insets-Padding.
