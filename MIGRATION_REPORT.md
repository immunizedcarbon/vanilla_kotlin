# Android 15 (SDK 35) Migration Report

## Ausgangszustand
- Projektstand mit AGP 7.3.1, Gradle 7.4, compile/target SDK 34 und minSdk 26.
- Java-dominierter Codebestand, Kotlin nur punktuell vorhanden.
- Legacy-Storage-Flags sowie READ/WRITE_EXTERNAL_STORAGE in der Manifest-Konfiguration.

## Durchgeführte Änderungen nach Phasen

### Phase 0 – Baseline & Absicherung
- Projektstruktur und zentrale Entry-Points (Activities, Services, Widgets) geprüft.
- Migration-Plan: Uplift von Toolchain + SDK, Bereinigung Legacy-Storage, Edge-to-edge, Kotlin-Migration der zentralen Basisklassen.

### Phase 1 – Build-/SDK-Uplift
- AGP auf 8.6.1 und Gradle Wrapper auf 8.7 aktualisiert.
- Kotlin-Gradle-Plugin integriert, Java/Kotlin Toolchain auf Java 17 gehoben.
- compileSdk/targetSdk/minSdk auf 35 gesetzt.

### Phase 2 – API-35-Verhaltensanpassungen
- Edge-to-edge zentral über Application-Lifecycle aktiviert und systemweite Insets sicher auf Root-Views angewandt.
- Legacy-Storage-Flags/Permissions entfernt, Permissions auf READ_MEDIA_* und POST_NOTIFICATIONS vereinheitlicht.

### Phase 3 – Kotlin-first Migration
- Kern-Basisklassen (PlaybackActivity, SlidingPlaybackActivity) nach Kotlin migriert.
- PermissionRequestActivity nach Kotlin migriert und vereinfacht (API 35 only).
- Kotlin Hilfsklassen für Edge-to-edge eingeführt.

### Phase 4 – Clean-up & Hardening
- Abhängigkeiten aktualisiert (AndroidX/Material).
- Lint-Konfiguration aktualisiert, Kompatibilitätsaltlasten < 35 entfernt.

### Phase 5 – Verifikation
- Lokale Build-/Testausführung nicht durchgeführt (siehe TEST_REPORT.md).

## Gründe für wichtige Architektur-/Technikentscheidungen
- Edge-to-edge zentral via Application-Lifecycle statt Scatter in jede Activity, um konsistente Insets für alle Screens sicherzustellen.
- Entfernung von Legacy-Storage (requestLegacyExternalStorage, READ/WRITE_EXTERNAL_STORAGE) aufgrund minSdk=35.
- Kotlin-Migration zuerst auf Basisklassen konzentriert, um maximale Hebelwirkung auf Subklassen/Flüsse zu erzielen.

## Was bewusst entfernt wurde
- Legacy-Storage-Handling (requestLegacyExternalStorage, READ/WRITE_EXTERNAL_STORAGE, API<35 Permission-Branches).

## Offene Punkte/Risiken
- Build/Instrumentation-Tests noch nicht lokal verifiziert (siehe TEST_REPORT.md).
