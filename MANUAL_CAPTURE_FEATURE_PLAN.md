# Cross-App Manual Capture and Analysis — Implementation Plan

## Objective

Add a user-controlled way to analyse suspicious content visible in supported chat apps without reading chat screens in the background.

The feature must let the user:

1. Toggle a small scan overlay from a Quick Settings tile.
2. Tap **Capture & analyse** while viewing a suspicious WhatsApp, Telegram, or Instagram one-to-one chat.
3. Explicitly approve one Android screen-capture session.
4. Save the unmodified screenshot as evidence before any OCR or analysis.
5. Extract text locally, infer incoming versus outgoing message blocks from alignment, and let the user correct the selection.
6. Send only the user-confirmed incoming text into the existing `ScamDetectionEngine`.
7. Show a queued/ready response in the overlay and preserve the capture, selected text, result, and extracted entities in History and exported reports.

Discord is intentionally out of scope for v1 because its layout does not support the left/right chat-bubble heuristic.

## Non-negotiable product and privacy boundaries

- Never inspect another app's screen without an explicit capture action by the user.
- Never use Accessibility to scrape chat UI or infer messages in the background.
- Do not capture a screen when the device is locked.
- Android must show its native MediaProjection consent prompt for every capture session.
- Do not upload screenshots or text by default. Existing cloud-reply consent must not be re-used for screenshots; manual-capture analysis is local unless a separately designed, explicit contribution feature is later added.
- Store original screenshots in app-private storage, not shared gallery storage.
- Give users a deletion action that removes both the database record and the screenshot file.
- Do not imply support for apps/layouts not tested. The supported-app list must be visible in UI.

## Existing project integration map

Reuse these existing components rather than creating a second detection system:

| Concern | Existing location | Required integration |
|---|---|---|
| Detection pipeline | `app/src/main/java/com/example/myapplication/engine/ScamDetectionEngine.kt` | Feed selected text through `processMessage()` as a synthetic `IncomingSms`. Do not duplicate rule, vector, LLM, fact-check, or entity-extraction logic. |
| Heavy-engine lifecycle | `engine/ScamDetectionEngineManager.kt` | Obtain exactly one engine instance from the manager on a background coroutine. |
| Persistence | `knowledge/KnowledgeBaseDatabase.kt`, `KnowledgeBaseDao.kt` | Add manual-capture entities/queries and a forward Room migration. Do not rely only on screenshot paths in existing simulation tables. |
| SMS/session schema | `model/SimulationSession.kt`, `SimulatedMessage.kt`, `AiAnalysisLog.kt` | Existing sessions may be reused for History compatibility, but add capture-specific storage for screenshot provenance and selected text. |
| Evidence extraction | `ScamDetectionEngine.kt` and `model/ScamEvidence.kt` | Preserve engine-created indicators and relate them to the manual capture session. |
| History | `MainActivity.kt`, `ui/EvidenceActivity.kt`, `ui/EvidenceDetailActivity.kt` | Show manual-capture records and a screenshot/evidence preview. |
| PDF export | `export/PdfReportGenerator.kt` | Embed the saved screenshot or include a clearly marked evidence appendix. |
| UI system bars/theme | `ui/SystemBars.kt`, `res/values/colors.xml`, `res/values-night/colors.xml` | Apply the existing insets/theme treatment to each new activity and overlay UI. |

## Architecture

```text
Quick Settings tile
        |
        v
OverlayController + ScanOverlayService
        |
        +-- View response --> ManualAnalysisActivity (latest result)
        |
        +-- Capture & analyse --> CaptureConsentActivity
                                      |
                                      v
                             Android MediaProjection consent
                                      |
                                      v
                              OneShotCaptureService
                                      |
                           save immutable PNG evidence
                                      |
                                      v
                            CaptureReviewActivity + on-device OCR
                                      |
                       infer/correct incoming text selection
                                      |
                                      v
                              ManualAnalysisRepository
                                      |
                                      v
                             ScamDetectionEngineManager
                                      |
                                      v
                    database + overlay "response ready" state
```

### Suggested package structure

```text
manualcapture/
  CaptureConsentActivity.kt
  CaptureReviewActivity.kt
  ManualAnalysisActivity.kt
  ManualAnalysisRepository.kt
  ManualCapturePrefs.kt
  OneShotCaptureService.kt
  ScanOverlayService.kt
  OverlayController.kt
  ScanQuickSettingsTileService.kt
  ocr/ScreenTextRecognizer.kt
  ocr/MessageSideClassifier.kt
  model/ManualCapture.kt
  model/ManualCaptureAnalysis.kt
  model/OcrTextBlock.kt
```

Keep Android UI activities under `ui/` only if that is the established project convention; otherwise keep this feature cohesive in `manualcapture/`.

## Dependencies and Android manifest

### Gradle

Add the bundled Latin ML Kit recognizer so OCR works immediately and remains on-device:

```kotlin
implementation("com.google.mlkit:text-recognition:16.0.1")
```

Bundled is preferred over the Play-services model because an urgent manual scan must work without waiting for a model download. Consider Devanagari only if required by the intended test corpus; it adds APK size and should not be added speculatively.

### Manifest additions

Add only the minimum required pieces:

```xml
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />

<service
    android:name=".manualcapture.ScanQuickSettingsTileService"
    android:exported="true"
    android:icon="@drawable/ic_scan"
    android:label="Scan suspicious message"
    android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
    <intent-filter>
        <action android:name="android.service.quicksettings.action.QS_TILE" />
    </intent-filter>
    <meta-data
        android:name="android.service.quicksettings.TOGGLEABLE_TILE"
        android:value="true" />
</service>

<service
    android:name=".manualcapture.ScanOverlayService"
    android:exported="false" />

<service
    android:name=".manualcapture.OneShotCaptureService"
    android:exported="false"
    android:foregroundServiceType="mediaProjection" />

<activity android:name=".manualcapture.CaptureConsentActivity" android:exported="false" />
<activity android:name=".manualcapture.CaptureReviewActivity" android:exported="false" />
<activity android:name=".manualcapture.ManualAnalysisActivity" android:exported="false" />
```

`OneShotCaptureService` must display a clear, short-lived notification while capture is active, call `startForeground()` promptly, capture exactly one frame, immediately release the virtual display/image reader/MediaProjection, and then stop itself.

Do not request notification-listener or Accessibility permissions for this feature.

## Quick Settings tile and overlay

### Tile behaviour

- Tile label: `Scan message`.
- Inactive: overlay disabled.
- Active: overlay enabled.
- On first activation, launch a small permission explainer. If overlay permission is missing, deep-link the user to `ACTION_MANAGE_OVERLAY_PERMISSION`; do not mark the tile active until the service is actually running.
- The tile must not capture a screen, obtain content, or start a long-lived analysis merely by being toggled.
- On Android 13+, use the system tile-add request only after the user taps “Add Quick Setting” in Settings; never surprise-add it.

### Overlay UX

- Use `TYPE_APPLICATION_OVERLAY`, a small non-deceptive shield button, and clear app branding.
- It must be draggable and have a persistent close/disable path.
- Tap opens a compact card with exactly two controls:
  - `View response` — opens the latest completed manual analysis.
  - `Capture & analyse` — launches `CaptureConsentActivity`.
- If an analysis is running, show a neutral progress state, e.g. `Analysing…`.
- If a new result is complete and unviewed, draw an accessible badge/pulse on `View response`; do not rely on colour alone.
- Never cover a chat's send button, ask for credentials, simulate a system control, or accept touch events outside its visible bounds.
- Any failure, cancellation, or capture denial should restore the overlay without retaining content.

## Capture implementation

### CaptureConsentActivity

1. Check the device is unlocked and overlay permission remains granted.
2. Show an in-app explanation: “Honeypot will capture one screenshot only after Android asks for permission. The screenshot is stored as evidence on this device.”
3. Call `MediaProjectionManager.createScreenCaptureIntent()` through Activity Result APIs.
4. If cancelled, return to overlay with no database record and no file.
5. If granted, start `OneShotCaptureService` with only the activity result data required by MediaProjection.
6. Finish the activity promptly; do not retain the MediaProjection result for reuse.

### OneShotCaptureService

1. Start as `mediaProjection` foreground service before creating the projection.
2. Derive current screen dimensions/density using modern APIs; handle rotation safely.
3. Create one virtual display + `ImageReader`.
4. Wait for the first non-null image with a bounded timeout.
5. Crop row padding correctly, convert the buffer to an ARGB bitmap, and write a PNG atomically:
   - write to a temp file;
   - rename only after successful compression;
   - destination: `filesDir/evidence_captures/<uuid>.png`.
6. Calculate a SHA-256 digest of the saved bytes and store it for integrity verification.
7. Create a `ManualCapture` row with `status = CAPTURED` and file metadata.
8. Release all capture resources in `finally`, stop foreground state, and stop the service.
9. Launch `CaptureReviewActivity` using the new capture ID.

Avoid persistent screen recording, repeated captures, and screenshot collection while the user is not actively initiating a scan.

## OCR and text-selection experience

### OCR

- `ScreenTextRecognizer` receives the saved bitmap and runs ML Kit text recognition on `Dispatchers.Default` or a dedicated executor.
- Save raw OCR blocks only after the user confirms use of the capture. A cancelled review must delete the image and draft row.
- Represent each block with:

```kotlin
data class OcrTextBlock(
    val text: String,
    val bounds: Rect,
    val confidence: Float?,
    val side: MessageSide,
    val role: BlockRole,
    val isSelected: Boolean
)
```

ML Kit may not return confidence for all text variants; code must allow null.

### Alignment classifier v1

Support only vertical one-to-one chat screenshots from WhatsApp, Telegram, and Instagram DMs.

1. Work in bitmap coordinates.
2. Ignore tiny blocks and likely timestamps/system labels using geometry/text heuristics.
3. Group nearby OCR blocks into candidate message groups based on vertical gap and shared horizontal alignment.
4. Determine each group's horizontal center:
   - left third -> `INCOMING`;
   - right third -> `OUTGOING`;
   - centre / ambiguous -> `SYSTEM_OR_UNKNOWN`.
5. Use a layout calibration option: `My messages are on: Right / Left`. Default right only after the user confirms it. This handles RTL layouts and UI variations.
6. Never send `OUTGOING` or `SYSTEM_OR_UNKNOWN` text to detection by default.

This is a heuristic only. Do not label its result as certain sender identification.

### CaptureReviewActivity UI

- Top app bar: `Review captured message` and a visible discard action.
- Main content: screenshot preview with semi-transparent bounding boxes.
- Selected incoming blocks have a distinctive outline; right-side/user/system blocks use muted outlines.
- Tapping a block cycles/selects classification: `Incoming`, `Mine`, `Ignore`.
- Provide an editable text preview beneath the image containing only selected Incoming blocks, ordered top-to-bottom.
- Provide `Analyse selected text` only when the editable preview contains nonblank text.
- Include source selector: WhatsApp / Telegram / Instagram / Other. `Other` is allowed but marked as unverified layout; it must never pretend sender-side inference is reliable.
- Include a note explaining that the original screenshot is preserved as evidence.
- On cancel/delete, delete the screenshot and draft database row.

Do not imitate Google Lens branding. The interaction can be Lens-like but must be app-native.

## Persistence model and migrations

Do not overload `ScamEvidence` with a file path. Add dedicated tables.

### `ManualCapture`

```kotlin
@Entity(tableName = "manual_captures")
data class ManualCapture(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val sourceApp: String,
    val imagePath: String,
    val imageSha256: String,
    val capturedAt: Long,
    val width: Int,
    val height: Int,
    val status: String, // CAPTURED, REVIEWED, ANALYSING, COMPLETE, FAILED, DISCARDED
    val selectedText: String = "",
    val senderLayout: String = "RIGHT_IS_MINE",
    val analysisSessionId: String? = null,
    val errorMessage: String? = null,
    val viewedAt: Long? = null
)
```

### `ManualCaptureAnalysis`

Store a durable user-facing summary independent of temporary UI state:

```kotlin
@Entity(tableName = "manual_capture_analyses")
data class ManualCaptureAnalysis(
    @PrimaryKey val captureId: String,
    val isScam: Boolean,
    val confidence: Float,
    val scamType: String,
    val reasoning: String,
    val firedTriggers: String,
    val completedAt: Long
)
```

### Migration

- Increment Room DB version from 12 to 13.
- Add a `MIGRATION_12_13` that creates both tables and necessary indices.
- Add both entities to `@Database.entities`.
- Register the migration with all existing migrations.
- Verify install over an existing DB, not just a fresh install.
- Keep `fallbackToDestructiveMigration()` only as the project currently has it; do not use it as a substitute for testing this migration.

### History/report integration

- For each confirmed scan, create a `SimulationSession` named `[CAPTURE] <source app> <date/time>` only if current History adapters require sessions; use a non-real synthetic sender such as `MANUAL_CAPTURE`.
- Add one `SimulatedMessage(senderType = "A", content = selectedText, ...)` so existing `AiAnalysisLog` and evidence extraction work without duplicating pipeline persistence.
- Save `analysisSessionId` back onto `ManualCapture`.
- Extend evidence session summaries to identify manual captures separately rather than showing a fake phone number.
- In detail view, add `Original screenshot` section with checksum, capture time, source app, selected text, and analysis outcome.
- Update `PdfReportGenerator` to embed a scaled image (preserving aspect ratio) or append an evidence image page. It must print the SHA-256 hash and capture timestamp.
- On user deletion, use a transaction-like sequence: remove dependent database data, delete screenshot file, and show whether deletion succeeded. Handle missing files gracefully.

## Pipeline integration

Create `ManualAnalysisRepository.analyseCapture(captureId, selectedText)`:

1. Atomically transition the capture from `REVIEWED` to `ANALYSING`.
2. Create/retrieve its capture-backed session and insert its synthetic attacker message.
3. Build:

```kotlin
IncomingSms(
    sender = "MANUAL_CAPTURE",
    body = selectedText,
    timestamp = capture.capturedAt,
    subscriptionId = -1,
    serviceCenterAddress = "manual:${capture.sourceApp}"
)
```

4. Call `ScamDetectionEngineManager.getEngine(context).processMessage(...)` on an IO/background coroutine.
5. Persist `AiAnalysisLog` using the same fields/pattern used in `SimulationActivity`.
6. Persist `ManualCaptureAnalysis` even when the result is not a scam; a result screen needs a clear safe/low-risk explanation.
7. Mark capture `COMPLETE`, store result-ready state in `ManualCapturePrefs`, refresh tile/overlay UI, and issue an app notification only if notification permission exists.
8. On failure, mark `FAILED`, save an actionable non-sensitive error, and show it in `View response`.

Do not invoke the cloud reply generator for manual captures. This feature analyses a suspicious message; it does not initiate a honeypot conversation in the third-party app.

## Result view

`ManualAnalysisActivity` should show:

- source-app badge and capture timestamp;
- screenshot thumbnail that opens an evidence preview;
- selected incoming text;
- `Likely scam` / `No strong scam signal` state with confidence explained plainly;
- scam type, reasons, fired triggers, and extracted entities;
- actions: `Save to History`, `Delete capture`, `Open History`;
- no automatic "reply" action.

When opened from the overlay, mark `viewedAt` and clear the response-ready badge.

## UI/visual requirements

- Reuse Material 3 theme colours, current dark/light resources, `SystemBars.apply()`, typography, and compact bottom navigation conventions.
- Screen capture/review activities must handle camera cutouts, status bars, gesture nav, rotation, and keyboard insets.
- Overlay must be small, accessible, and not visually impersonate Android/WhatsApp/Telegram system UI.
- All icons must be standard Material-style vectors; avoid emoji as functional UI icons.
- Give every control a `contentDescription` and ensure selection state is announced by TalkBack.

## Settings placement

Add a **Cross-app scanning** section to `SettingsActivity`:

- Add Scan message Quick Setting
- Overlay enabled toggle/status
- Supported apps: WhatsApp, Telegram, Instagram DMs
- My messages appear on: Right / Left
- Manage saved captures
- Clear all manual-capture evidence

Do not mix these controls into caller or SMS-only settings.

## Test plan

### Functional tests

1. Tile can be added, toggled on/off, and accurately reflects overlay state.
2. First overlay enable follows the overlay-permission flow; denial leaves service off.
3. Capture cancellation creates no file, no DB row, and no queued response.
4. Successful capture produces one PNG, a valid SHA-256, and exactly one capture record.
5. OCR extracts an English Latin-script WhatsApp/Telegram/Instagram screenshot offline.
6. Incoming left-aligned content is preselected; right-aligned user content is not.
7. User can change any block classification and edit the final text.
8. Empty selection cannot reach the pipeline.
9. Confirmed scam reaches existing evidence extraction and appears in History.
10. Safe/low-risk result still receives a meaningful result state and does not silently disappear.
11. Overlay response badge appears when complete and clears after viewing.
12. Deleting capture removes image, DB rows, thumbnails, and report availability.
13. PDF report contains screenshot + hash + selected text + analysis results.

### Compatibility tests

- Android 26 minimum supported device/emulator.
- Android 35 target behaviour, including per-session MediaProjection consent and foreground-service requirements.
- Light and dark themes.
- Gesture and three-button navigation.
- Portrait and rotation during review; abort/retry capture safely.
- Notification permission denied.
- Overlay permission revoked while tile remains present.
- OCR failure, blank screenshot, app process death, no network, and engine initialization failure.
- WhatsApp/Telegram/Instagram layouts with long messages, timestamp labels, quoted replies, image captions, group names, and RTL messages.

### Security/privacy tests

- Confirm no Accessibility service or NotificationListener is declared by this feature.
- Confirm no screenshot leaves `filesDir` without a future, separate opt-in export/contribution action.
- Confirm cloud API is never called from manual capture analysis.
- Confirm no content is retained after capture/review cancellation.
- Confirm evidence image cannot be reached through a public file URI.

## Delivery sequence for agents

1. **Foundation:** add dependency, manifest declarations, prefs, Room entities/DAO/migration, app-private evidence file helper, and unit tests for state transitions.
2. **Capture:** implement one-shot MediaProjection service and capture-consent activity; verify on Android 35 before adding overlay.
3. **OCR review:** implement ML Kit extraction, local block geometry grouping, manual selection/editing UI, cancellation cleanup.
4. **Pipeline:** add repository integration, synthetic session/message/log persistence, result state, failure handling.
5. **Overlay/tile:** implement Quick Settings tile, safe overlay lifecycle, response badge, and result routing.
6. **History/reporting:** render captures in History/detail/PDF, including screenshot integrity details and deletion.
7. **QA/UI polish:** execute the test matrix, fix insets/dark mode/accessibility, then build a signed-equivalent debug APK for device verification.

At every phase, preserve existing SMS, testing sandbox, History, and honeypot behavior. Run `:app:assembleDebug --no-daemon` after each coherent phase and resolve warnings/errors before moving on.

## Explicit out-of-scope items for this iteration

- Background screen scraping.
- Accessibility-based message extraction.
- Automatic capture from any chat app.
- Discord layout parsing.
- Automatic reply/sending in third-party chat apps.
- Voice-call recording/transcription.
- Community/cloud contribution of screenshots or message text.
- Any claim that alignment perfectly identifies message ownership.

