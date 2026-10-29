# 차분(구 Pit-In) Android phone companion

> 개발하면서 남긴 기록입니다. 일부는 지난 시점의 내용이고, 이 저장소에 없는 문서를 가리킬 수 있습니다. 최신 설명은 저장소 맨 위 `README.md`를 보세요.

This is a native development prototype. Build and device evidence are recorded separately under `../../evidence/run-2026-10-24-astra/`; source availability alone does not establish either. It reads Health Connect on an Android phone and sends only the allowlisted summary in `../../docs/CONTRACT.md` to our own relay. Do not install this module as the Pleos vehicle app.

## Mobile home update — 2026-10-26

The launcher now opens a paper/olive home with **삼성헬스 연결** and **Google Health Connect 연결** at the top. Both use the existing Health Connect flow; the Samsung action filters for Samsung Health's contributor package. The home checks SDK availability and granted exercise-read permission only. It does not request permissions, read a workout, or share anything automatically. Google Fit account login, watch apps, Bluetooth, Gleo and live vehicle controls are not added.

**운동 요약 보기 · 합성 체험** opens the optional workout/share page. Its **합성 운동으로 체험하기** action is debug-only and never reads Health Connect. Sharing still requires a selected workout, a confirmed vehicle pairing, a separately checked consent, and the existing freshness/TLS policy. A translated comfort label maps to the unchanged protocol value.

**차량 연결 관리** opens pairing, approval status, revoke and shared-preset entry. The relay URL is under **고급 연결 설정**. Navigation between these pages preserves in-memory controls and pairing; app process death still loses the pairing. HTTP 401/403/404/410 invalidates the local pairing and summary so a new connection can be made. Other transport failures require approval status to be checked again before sending. Local invalidation is never described as successful server deletion.

Fresh build, policy results and emulator UI captures are in the workspace-level `evidence/mobile-product-ui-2026-10-26/`, outside the original package manifest. APK build/install, synthetic UI actions and real Health Connect/device validation remain separate evidence levels. Earlier sections below retain the original flow descriptions and historical button labels.

## Build

Open this directory as a Gradle project in Android Studio. Install Android SDK Platform 36 and Build Tools 35.0.0. Use JDK 17 and AGP 8.10.1. The included official Gradle wrapper pins 8.11.1. Run (`gradlew.bat` on Windows):

```bash
./gradlew :app:assembleDebug
```

The scripts and wrapper JAR came from Gradle's official v8.11.1 tag. The wrapper JAR matched its published SHA-256 (`2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046`); the distribution SHA-256 is pinned in `gradle/wrapper/gradle-wrapper.properties`. First execution may download Gradle. Dependencies are pinned. Health Connect stable 1.1.0 requires compileSdk 36 and AGP 8.9.1+ according to the current official setup guide.

The initial handoff workspace had Java 17 but no complete Android SDK, Gradle or Kotlin compiler. Its source-only compilation of the separate vehicle module did not establish mobile build success. The 2026-10-24 Windows follow-up builds this phone module with the pinned wrapper, isolated JDK 17 and SDK 36; see the fresh phone build logs in the evidence directory above. Device installation, Health Connect reads and synthetic demo transmission require their own runtime evidence.

## Device and demo flow

1. Use a personal Android phone, API 28+, with Google Play services. Android 14+ includes Health Connect; on Android 9–13 install/update the Health Connect provider. Work profiles are unsupported. A Samsung Health setup may impose a higher Android minimum.
2. For Samsung data, enable the desired Samsung Health → Health Connect sharing on the phone and wait for Galaxy Watch → Samsung Health sync. Granting 차분 permission does not grant Samsung Health permission to write. The app labels the source as `health_connect`, because it can read records from any permitted contributor.
3. Start the own relay and vehicle prototype. Claim the vehicle's one-use code on the phone. Compare the four-digit confirmation on both screens, approve on the vehicle, then check phone status.
4. Tap Connect Health Connect / read latest workout. Exercise permission is required; optional heart-rate permission only affects the local average. A denied optional permission does not block a workout summary. For device testing use synthetic records inserted by Health Connect Toolbox or a test data source, not another person's records.
5. Review type, duration and end time. A workout must be completed within 30 minutes and have a rounded duration of 1–240 minutes. Select your own comfort choice and tick separate share consent. Send once. Heart rate, raw samples, source IDs, workout IDs and location never enter the request body.
6. Clear local removes the local in-memory selection. Delete/revoke calls the relay to delete the shared summary and both tokens. Process death loses the local pairing; server expiry still applies. Re-pair from the vehicle if local state is lost.

Debug builds allow `http://10.0.2.2:8787` for the Android Emulator (or localhost/127.0.0.1) for pairing and explicitly selected synthetic demo summaries only; release builds require HTTPS. A physical phone cannot use the emulator pass-through address. For **synthetic-only USB testing**, use `adb -s PHONE_SERIAL reverse tcp:8787 tcp:8787` and set the phone debug URL to `http://127.0.0.1:8787`; the ADB server and relay must run in the same host environment. Health Connect summaries require HTTPS even in debug builds. This source does not set up a public tunnel or deploy anything.

For a local synthetic demo, tap **합성 데모 운동 선택 · 건강정보 읽지 않음** in the debug app. This creates an in-memory walking summary labeled `DEMO` and `source=demo`; it neither reads nor inserts Health Connect records. It clears any previous selection and consent. Pair, compare confirmation numbers, approve on the vehicle, choose a preference, separately consent and send. Freshness still expires 30 minutes after the synthetic workout end. This demonstrates the phone-to-relay path, not Health Connect reading. The button and demo sending are unavailable in release builds.

Health Connect does not identify a record as synthetic. All selected Health Connect records therefore remain `source=health_connect` and require HTTPS, including records inserted by a test toolbox. Exercise permission is rechecked before their transmission. See the commands and cleanup steps in [the Codex execution plan](../../docs/CODEX_EXECUTION_PLAN.md#합성-기록용-usb-경로).

Policy regression checks use the production Java policy with JDK 17 and no added dependencies:

```powershell
.\scripts\test-share-policy.ps1
```

The project-local `android.overridePathCheck=true` permits the existing Korean Windows workspace path; it does not change global Gradle settings or SDK versions.

## Implementation limits and required verification

### Opt-in local TLS build

The separate `localTlsDebug` build type exists only when `-PlocalTlsCa=<public CA PEM path>` is supplied. It inherits debug signing/debuggability and keeps `com.recovery.companion`; installing it replaces the ordinary debug app. Existing debug and release network policies are unchanged.

```powershell
.\gradlew.bat --no-daemon '-PlocalTlsCa=C:\path\to\ca.pem' :app:assembleLocalTlsDebug :app:lintLocalTlsDebug
```

The generated resources contain only a validated public X.509 CA certificate, never private keys. Cleartext is disabled for every address. The CA is trusted only for exact `localhost`, `127.0.0.1` and `10.0.2.2` names (no subdomains); other addresses trust only system CAs. Normal TLS certificate-chain and hostname verification remain active. The server certificate must contain a matching SAN and chain to the provided CA. No device-wide CA installation is needed. Default relay is `https://127.0.0.1:9443` for this build only; a selected device's `adb reverse tcp:9443 tcp:9443` must point to the authorized PC relay.

### Samsung Health provenance

Tap **삼성헬스에서 공유한 최근 운동만 읽기** to request records whose Health Connect data origin is `com.sec.android.app.shealth`. The original read button continues to select the latest permitted contributor. Both follow the same explicit permission/read flow, bounded 24-hour window and 30-minute sharing limit. Permission-dialog recreation preserves which read was requested. Reads never start automatically on launch.

The preview shows the record's `dataOrigin.packageName` locally. It is not retained in the export model or sent in JSON; transmitted `source` remains `health_connect`. This identifies the Health Connect contributor, not the hardware that measured the activity, a Samsung account, or a medical assurance. Health Connect unavailable/provider-update states and no Samsung-origin records have distinct messages. Tablet support, Samsung Health availability/sync and real readings require verification on the user's actual device; a missing record is not proof the app is unsupported.

The app reads only in the foreground, requests no write/history/background/location permissions and holds summary/tokens only in memory. Reads page through a bounded 24-hour window and select the latest completed session by end time. HR average is restricted to the workout's data origin and stays local. Exercise duration is rounded elapsed session time; paused intervals are not subtracted in this P0. A long workout spanning the entire 24-hour window is outside the 240-minute export limit anyway.

Check on actual target phones: availability/provider-update states; full/partial permission denial; revocation between read/share; no records; delayed Samsung sync; no HR from the same source; stale/future data; confirmation mismatch; pairing/session expiration; network disconnect; summary deletion; process death. The server remains authoritative for schema, pairing role, freshness and TTL. Production Play distribution also requires declaring the requested health data and providing the same published privacy policy as the rationale screen. No distribution approval is claimed.

### v0.4 local comfort presets

**나의 편안함 · 차와 집 프리셋** opens the standalone warm paper/olive `PresetsActivity` without reading Health Connect. Preferences persist locally; arrival plans and the simulated home device remain Activity-memory fixtures. They do not connect to the continuity browser server or issue home/vehicle commands. The existing Samsung read, explicit synthetic demo, sharing policy and TLS defaults are retained.

Run the 34 pure Kotlin preset/session checks on the build JVM with the pinned compiler:

```powershell
.\gradlew.bat --no-daemon :app:runPresetDomainChecks
```

Fresh Windows debug/localTlsDebug build, lint and policy evidence is in `../../evidence/v04-windows-2026-10-24/phone-*`. This is separate from installation/UI and provider runtime evidence.

## Connected preset collection (v0.5)

MainActivity's `웹·차량과 프리셋 연결` opens `SyncedPresetsActivity`. This collection is authoritative on the independent preset-sync service and does not read, import, or overwrite `PresetStore` offline examples. It uses only HTTPS `https://127.0.0.1:9447` (adb reverse configured by the device operator), the existing opt-in localTlsDebug CA, and platform chain/hostname validation. The transport rejects other origins, credentials/paths/query/fragment and redirects. No new permissions or dependencies.

Create a phone code in the web manager; enter six digits, compare four digits, and explicitly approve in the web manager. Pending/approved bearer tokens and connected state live only in this Activity memory. Server expiration/revocation 401 clears both; explicit disconnect sends DELETE session. Health pairing is separate and unchanged.

Foreground polling is every two seconds. Foreground return disables writes until a new state read. The last observed state remains labelled offline during failure. Generation plus revision checks reject superseded responses. Editors keep their opening epoch/revision, preserve drafts on 409, and require explicit review then a second submit. The review acknowledgement captures the revision displayed when the review opened. Arrival time editing retains the opening plan identity/version and refuses to retarget a replaced plan.

A failed/uncertain command stays in memory with the identical body/requestId; all other writes are blocked until explicit same-request retry resolves it (or disconnect). There is no background queue or automatic retry. Connected app data is real; home observations/time and vehicle proposals remain synthetic. Vehicle rest never starts from this screen.

Build/lint and JVM evidence: `../../evidence/preset-sync-2026-10-24/phone-build-final.log`, `phone-share-policy.log`, and `phone-report.json`. Emulator installation and web/phone/vehicle UI verification are separate parent-owned evidence, not established by these JVM checks.
