# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Install on connected device/emulator
./gradlew installDebug

# Run unit tests
./gradlew test

# Run instrumented tests (requires connected device/emulator)
./gradlew connectedAndroidTest

# Run lint
./gradlew lint

# Run a single test class
./gradlew test --tests "com.recordsapp.ExampleTest"
```

## Architecture

This is an Android app (minSdk 26, Kotlin + Jetpack Compose) for managing a vinyl record collection. It follows a standard layered architecture:

```
domain/model/     — Pure Kotlin enums: Grade (Mint → Poor), Country (Israel, USA, UK, ...)
data/local/       — Room database, DAOs, entities, ImageStorage
data/repository/  — AlbumRepository (single repository, injected as @Singleton)
ui/screens/       — One package per screen, each with a Screen + ViewModel
ui/components/    — Shared composables (dropdowns, image picker, album card)
ui/navigation/    — NavGraph + Screen sealed class
di/               — Single Hilt module (DatabaseModule) in SingletonComponent
```

### Data Model

Two Room entities with a one-to-many relationship:
- **`AlbumEntity`** (`albums` table): artist, album name, num records, year, cover image path (nullable), comment
- **`CopyEntity`** (`copies` table): FK to album (CASCADE delete), grade side 1, grade side 2, country, listened flag

`Grade` and `Country` enum values are stored in the DB as their `displayName` strings, not ordinals/names.

`AlbumWithCopies` is a Room relation that joins them — it's the primary read model throughout the app.

### Key Patterns

**ViewModels** expose a single `StateFlow<XxxState>` for UI state and a `MutableSharedFlow<Boolean>` named `saveComplete` for one-shot navigation-after-save events.

**`AddEditAlbumScreen`** is reused for both add and edit flows. When `albumId` is present in `SavedStateHandle`, the VM loads existing data. On add, it atomically creates the album + first copy via `AlbumRepository.insertAlbumWithCopy`. On edit, it only updates the album (not copies).

**`AddCopyScreen`** adds additional physical copies to an existing album (same album, different pressing/condition).

**Image storage**: `ImageStorage` copies images from content URIs to `context.filesDir` as `cover_<uuid>.jpg`. The path is stored in `AlbumEntity.coverImagePath`. Coil 3 loads images from these file paths.

### Navigation

`Screen` is a sealed class in `ui/navigation/Screen.kt`. Routes with arguments use `{albumId}` (Long). The NavGraph wires all screens in `RecordsNavGraph`.

### Dependency Injection

Hilt with KSP. `DatabaseModule` provides `RecordsDatabase`, `AlbumDao`, and `CopyDao`. All ViewModels use `@HiltViewModel`. The application class is `RecordsApplication`.

## gstack

Use the `/browse` skill from gstack for all web browsing. Never use `mcp__claude-in-chrome__*` tools directly.

Available gstack skills: `/office-hours`, `/plan-ceo-review`, `/plan-eng-review`, `/plan-design-review`, `/design-consultation`, `/design-shotgun`, `/design-html`, `/review`, `/ship`, `/land-and-deploy`, `/canary`, `/benchmark`, `/browse`, `/connect-chrome`, `/qa`, `/qa-only`, `/design-review`, `/setup-browser-cookies`, `/setup-deploy`, `/setup-gbrain`, `/retro`, `/investigate`, `/document-release`, `/document-generate`, `/codex`, `/cso`, `/autoplan`, `/plan-devex-review`, `/devex-review`, `/careful`, `/freeze`, `/guard`, `/unfreeze`, `/gstack-upgrade`, `/learn`.