# Dimroom

A native Android photo editor in the spirit of Lightroom Mobile: a local photo library, a
non-destructive GPU editing pipeline, presets, and export — all running entirely against on-device
storage, behind an abstraction that a cloud backend can slot into later without touching the app.

**This phase ships no cloud sync.** There is no OAuth, no API key, and no network call anywhere in
the codebase. What exists is the `StorageProvider` contract, a complete `LocalStorageProvider`, and
two deliberately empty cloud stubs.

---

## Building

```bash
./gradlew assembleDebug          # debug APK -> app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # JVM unit tests
./gradlew lintDebug              # Android lint
```

Requirements: JDK 17, Android SDK with API 35 installed. Min SDK 26 (Android 8.0).

Open the project root in Android Studio and it will sync as-is — no extra local configuration.

---

## Architecture

MVVM with a repository layer and Hilt for dependency injection. One Gradle module (`:app`) with a
package-per-layer structure.

```
com.dimroom
├── domain/model/          Pure Kotlin: EditStack, EditSidecar, Photo, Album, Preset
├── data/
│   ├── storage/           StorageProvider + LocalStorageProvider + cloud stubs
│   ├── db/                Room entities, DAOs, DimroomDatabase
│   └── repository/        PhotoRepository, AlbumRepository, PresetRepository, SettingsRepository
├── editor/
│   ├── gl/                Shader source, GlProgram, EditUniforms, preview + offscreen renderers
│   ├── hdr/               Exposure fusion, MTB alignment, HdrMerger
│   └── export/            ImageExporter (MediaStore + share sheet)
├── ui/                    Compose screens and view models (library, editor, settings)
└── di/                    Hilt modules
```

**Data flow.** View models talk only to repositories. Repositories hold metadata in Room and hand
every byte to a `StorageProvider`. Nothing above the repository layer knows whether a photo lives on
the device or in a bucket somewhere.

### The editing engine

Edits are never applied to the original file. A photo's adjustments live as an `EditStack` — a plain
data class — which is:

1. rendered live by a single-pass GLSL ES 2.0 fragment shader (`editor/gl/EditShaders.kt`),
2. stored in Room for fast library queries, and
3. mirrored to a portable JSON sidecar through the storage provider.

The preview and the exporter share the same `GlProgram` and the same `EditUniforms` binding code, so
the exported pixels come from exactly the numbers the user saw while dragging sliders. Export
re-decodes the full-resolution original and renders it through an offscreen EGL pbuffer + FBO.

Adjustment stages, in pipeline order: white balance → exposure → contrast → highlights/shadows →
whites/blacks → dehaze → clarity → HSL (8 bands) → vibrance/saturation → vignette → grain. Crop,
90° rotation, straighten and flips are applied as a texture-coordinate matrix, so they cost nothing
extra per frame.

Clarity uses an 8-tap unsharp mask on luminance and dehaze uses a per-pixel dark-channel estimate;
both are GPU-cheap approximations of the desktop algorithms rather than reimplementations of them.

**Known limitation:** the crop UI is slider-driven (aspect presets, size, position, straighten,
rotate, flip) rather than direct-manipulation handles on the preview. The underlying crop model is a
full normalised rect, so adding drag handles later is a UI change only.

### HDR merge

Select two or more photos in the library and merge them into a single image.

**What the algorithm is.** Dimroom uses **Mertens–Kautz–Van Reeth exposure fusion**, not a
Debevec-style radiance map with tone mapping. Each frame is scored per pixel for local contrast,
colour saturation and well-exposedness; the scores are normalised across the bracket and the frames
are blended band by band over a Laplacian pyramid. Blending per frequency band is what avoids the
seams and halos a single-scale weighted average produces.

That choice is deliberate. Radiance-map HDR needs trustworthy exposure times from EXIF and a
recovered camera response curve, and it still has to be tone mapped back down to something you can
look at. Fusion needs neither, copes with an uneven bracket, and outputs an ordinary displayable
image — so a merged photo is a normal library photo that the existing edit, preset and export paths
handle with no special cases.

**Alignment.** Handheld brackets are never pixel-aligned. `MtbAligner` implements Ward's Median
Threshold Bitmap: each frame is thresholded at its own median, which makes the comparison nearly
immune to the exposure differences that define a bracket, then matched over a pyramid. It corrects
translation only — rotation and parallax are out of scope, so a badly swung handheld set can still
ghost. It can be switched off for tripod brackets.

**Memory.** Fusion holds, per frame, an RGB float buffer and a weight map, plus a result pyramid and
the pyramids of the frame currently being folded in. `HdrMerger` derives its working resolution from
the device's own heap class, so a nine-shot bracket on a modest phone quietly merges at a lower
resolution instead of dying. Frames are accumulated into the result pyramid one at a time rather
than all being held at once. Up to 9 frames per merge.

The maths lives in `ExposureFusion`, `Pyramids` and `MtbAligner`, which are pure Kotlin with no
Android types — so pyramid reconstruction, weight normalisation, shadow/highlight recovery and
alignment of a known translation are all covered by JVM unit tests rather than left to be eyeballed
on a device.

**What happens to the originals** is the user's choice at merge time:

| Mode | Result in the library |
| --- | --- |
| Keep photos separate | The merge is added alongside the sources, which stay exactly where they are. |
| Group as one HDR | The merge takes one tile and the sources are tucked inside it, badged `HDR n`. |

Grouping is a *stack*: `PhotoEntity.stackId` plus one `isStackPrimary` member, and the library
queries collapse non-primary members. Ungrouping is non-destructive and available from the selection
bar; deleting a stack's primary releases its members rather than burying them. Like albums, stacking
is library metadata held in Room — it deliberately does not alter the storage layout, so the folder
contract above is unchanged and a merged photo is just another file under `/Originals`.

---

## The storage abstraction

`data/storage/StorageProvider.kt` is the single seam between Dimroom and wherever photos physically
live:

```kotlin
interface StorageProvider {
    suspend fun listPhotos(folderPath: String): List<PhotoMetadata>
    suspend fun uploadPhoto(localFile: File, targetPath: String): StorageResult
    suspend fun downloadPhoto(remoteId: String, targetLocalFile: File): StorageResult
    suspend fun saveEditSidecar(photoId: String, editJson: String): StorageResult
    suspend fun loadEditSidecar(photoId: String): String?
    suspend fun deletePhoto(remoteId: String): StorageResult
    suspend fun createFolder(path: String): StorageResult
    fun getProviderName(): String
    fun isConfigured(): Boolean
}
```

### Layout contract

Every provider uses the same logical layout, so a folder written by one is readable by another:

```
/Originals/{albumName}/{photoId}.jpg    untouched imported bytes
/Edits/{photoId}.json                   non-destructive edit sidecar
/Previews/{photoId}_thumb.jpg           regenerable grid thumbnail
```

Paths crossing the interface are always provider-relative, `/`-separated, and never start with a
separator. `LocalStorageProvider` resolves them beneath `filesDir/library` and rejects anything that
would traverse out of that sandbox.

### Sidecar schema

`/Edits/{photoId}.json` is the portable source of truth for an edit. It is written by the local
provider today in exactly the shape a cloud folder will eventually hold:

```json
{
  "schemaVersion": 1,
  "photoId": "0f0b2e0e-...",
  "originalFileName": "IMG_0042.jpg",
  "updatedAt": 1717171717171,
  "edits": {
    "schemaVersion": 1,
    "light":    { "exposure": 0.25, "contrast": 12, "highlights": -30,
                  "shadows": 20, "whites": 0, "blacks": -8 },
    "color":    { "temperature": 14, "tint": -2, "vibrance": 25, "saturation": 0 },
    "hsl":      { "bands": [ { "band": "red", "hue": 0, "saturation": 0, "luminance": 0 }, "…8 bands" ] },
    "effects":  { "clarity": 15, "dehaze": 0, "vignette": -20, "grain": 12 },
    "geometry": { "cropLeft": 0.0, "cropTop": 0.0, "cropRight": 1.0, "cropBottom": 1.0,
                  "rotationDegrees": 0, "straightenDegrees": 0.0,
                  "flipHorizontal": false, "flipVertical": false, "aspectRatio": "original" },
    "presetId": "builtin.warm_film",
    "presetName": "Warm Film"
  }
}
```

Design rules that keep it cloud-portable, all covered by tests in `EditSidecarTest`:

* **Values are in user-facing units.** `exposure` is in stops; everything else is `-100..100` (or
  `0..100` one-sided). Normalising to shader units happens at draw time, so the file never encodes a
  detail of the current renderer.
* **Crop is normalised to the unrotated original**, so a sidecar stays correct if the original is
  later re-downloaded at a different resolution.
* **Every field has a default and unknown keys are ignored**, so a newer client's sidecar still opens
  in an older one.
* **A corrupt sidecar decodes to `null`**, never an exception — one bad file cannot brick a library.
* `presetId` refers to a preset by stable id; built-in ids (`builtin.*`) resolve across installs.

### Adding a new StorageProvider

1. Implement `StorageProvider` (start from the `GoogleDriveStorageProvider` stub). Honour the layout
   contract above and return `StorageResult.Failure` rather than throwing.
2. Make `isConfigured()` return true only once the backend actually has credentials — the Settings
   screen reads that directly to decide whether a row is live or "Coming soon".
3. Change the binding in `di/StorageModule.kt`:

   ```kotlin
   @Binds
   @Singleton
   abstract fun bindStorageProvider(drive: GoogleDriveStorageProvider): StorageProvider
   ```

   (Or make it a `@Provides` that picks a provider from a user preference.)

No repository, view model or composable needs to change. The one place that assumes local files is
`PhotoRepository.localFileFor`, which already falls back to `downloadPhoto` into the cache for any
path the local provider does not hold.

---

## Continuous integration

`.github/workflows/android-build.yml` runs on pushes and pull requests to `main`, on `v*` tags, and
on manual dispatch. It sets up JDK 17 with Gradle caching, then:

| Trigger | What runs |
| --- | --- |
| every run | lint, unit tests, **debug APK** uploaded as the `dimroom-debug-apk` artifact |
| `v*` tags | additionally builds and signs a **release APK**, verifies the signature, uploads it as `dimroom-release-apk`, and attaches it to a GitHub Release |

### Required secrets for release signing

Set these in **Settings → Secrets and variables → Actions**. They are only needed for tagged builds;
ordinary pushes never touch them.

| Secret | What it is |
| --- | --- |
| `KEYSTORE_BASE64` | Your release keystore, base64-encoded: `base64 -w 0 release.keystore` |
| `KEYSTORE_PASSWORD` | Password for the keystore file |
| `KEY_ALIAS` | Alias of the signing key inside the keystore |
| `KEY_PASSWORD` | Password for that key |

Creating a keystore, if you don't have one:

```bash
keytool -genkey -v -keystore release.keystore -alias dimroom \
        -keyalg RSA -keysize 2048 -validity 10000
base64 -w 0 release.keystore   # paste the output into KEYSTORE_BASE64
```

Cutting a release:

```bash
git tag v0.1.0 && git push origin v0.1.0
```

The workflow fails loudly if `KEYSTORE_BASE64` is missing or if the produced APK does not verify, so
an unsigned artifact can never reach a Release.

### Signing locally

Create `keystore.properties` in the repo root (already git-ignored):

```properties
storeFile=/absolute/path/to/release.keystore
storePassword=…
keyAlias=dimroom
keyPassword=…
```

Without it, `assembleRelease` still succeeds and simply produces an unsigned APK.

---

## Features in this phase

**Library** — grid of imported photos with lazy thumbnails; import through the Android photo picker
(no storage permission needed); sort by date added, date captured or name; albums with create,
rename, delete and add/remove; long-press to multi-select.

**HDR merge** — fuse 2–9 selected photos into one image, with optional alignment for handheld
brackets; keep the originals separate or group them under the merge as a single tile.

**Editor** — pinch-zoom and pan; before/after toggle and draggable split compare; Light, Color, HSL
(8 bands), Effects and Crop panels; undo/redo and reset-to-original; edits autosave to Room and the
sidecar.

**Presets** — six built-ins (B&W Classic, Warm Film, Cool Matte, Punchy Landscape, Soft Portrait,
High Key B&W); save the current look as a named preset; apply to any photo. Presets carry tone and
colour only — crop and rotation stay with the photo they belong to.

**Export** — full-resolution render to the device gallery (`Pictures/Dimroom`) or the share sheet,
with JPEG quality and longest-edge resize options.

**Settings** — theme (system/light/dark), cache size and clearing, and a Storage section listing the
active backend alongside the two cloud providers marked "Coming soon".

## Explicitly out of scope for this phase

OAuth flows, API keys, and any network call to Google Drive or OpenDrive; background sync; conflict
resolution; offline queueing; multi-device sync; iOS.
