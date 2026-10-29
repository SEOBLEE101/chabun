# 차분(구 Pit-In) vehicle Android prototype

> 개발하면서 남긴 기록입니다. 일부는 지난 시점의 내용이고, 이 저장소에 없는 문서를 가리킬 수 있습니다. 최신 설명은 저장소 맨 위 `README.md`를 보세요.

## 2026-10-28 검수 수정

현재 차량 UI의 실제 테마는 **Graphite** 하나다. Home/Setup/Connected의 보조 동작과 선택 스타일을 통일했고,
MainScreen의 진행·일시정지·완료 및 보호된 모션·안전 정책은 변경하지 않았다.

건강 연결과 공유 선호 연결의 DELETE 실패를 구분한다. 화면의 캐시는 즉시 지우되 삭제만을 위한 자격을
Activity 메모리에 남겨 명시적으로 재시도한다. 401은 삭제 확인이 아니며, 403/네트워크 실패/불명확한 결과는 pending이다.
Activity 종료 뒤 영구 삭제 작업이 계속된다고 보장하지 않는다.

승인된 `ConnectedPresetsActivity`는 전면에서 현재 Graphite 앱 외관을 별도 `/appearance` 경로로 약 30초 간격 보고한다.
`schemaVersion:1/themeId:graphite/paletteVersion:1`만 전송하며 기존 건강 DTO·선호 `/state` 계약을 변경하지 않는다.
최초 GET, 자체 report revision, epoch/deviceId/credential, 응답 ack와 수명 검사를 적용한다.
응답 유실은 동일 requestId·payload로 재시도하고, 409 거절은 다음 GET에서 다시 준비한다.
외관 보고 실패는 선호 연결·변경 상태를 지우지 않는다. 이 보고는 차량 공조·조명·시트 제어가 아니다.

세부 근거와 인계:

- [공식 Pleos capability·권한·차량 지원 검증표](../../docs/PLEOS_CAPABILITY_MATRIX_2026_10_28.md)
- [감각 경험·adapter·삭제·appearance 인계](../../docs/VEHICLE_EXPERIENCE_ADAPTER_HANDOFF_2026_10_28.md)
- 새 검증 결과: `../../evidence/review-2026-10-28/vehicle/`.
- 프로젝트 루트 `node scripts/test-vehicle-core.mjs` 또는 `bash gradlew runVehiclePolicyChecks`가 전체 순수 Java 검증이다.
  예전 `scripts/test-core.sh`는 SafetyGate/RestSession 89 assertions만 실행한다.

아래 과거 인계는 해당 날짜의 범위를 설명한다. 현재 결과와 혼합하지 않는다.

## Connected presets v0.5 (2026-10-24)

MainActivity → 연결·설정 → 연결된 프리셋 opens the unexported Graphite `ConnectedPresetsActivity`.
It uses the independent HTTPS `10.0.2.2:9447` preset service; the existing health relay is unchanged.
Create a vehicle code in the web UI, enter six digits here, compare the four digits, and approve in the web UI.
Bearer credentials and uncertain requests exist only in Activity memory. Leaving/destroying this screen requires pairing again; process death never queues a resend.
Foreground reads poll every two seconds. Offline observations are labeled and new changes are disabled;
uncertain commands require an explicit same-request-ID retry, while revision conflicts require a new reviewed action.
Selection/default/automatic-default/proposal/acceptance are shared state. Arrival requested and observed fields are read-only here.
Only explicit `이 선호로 쉬기 준비` returns a copied preset to MainActivity, where the existing local checkbox
and SafetyGate still apply. It does not invoke start/resume or alter the existing timer. Existing onStop pause remains.
No new permissions/dependencies, vehicle setters, or home SDK were introduced.

Fresh build/policy/lint evidence: `../../evidence/preset-sync-2026-10-24/vehicle-report.md`.
Emulator UI and cross-client behavior must be checked separately by the integration owner; these builds do not prove them.

This native client receives an explicitly shared exercise summary through the own-relay protocol in [the contract](../../docs/CONTRACT.md), lets the user choose a comfort preset, and offers a three-minute **simulated** parked pause. All climate/audio adjustments remain manual. It never writes to a vehicle property.

## What is implemented and verified

| Path | Implementation | Evidence in this workspace |
| --- | --- | --- |
| `demo` | Native pairing, summary polling, manual mode, synthetic vehicle scenarios, Graphite path/point, fixed session controls, three-minute preview, pause/resume and early end | Windows JDK 17 / Gradle 8.7 / AGP 8.5.2 `assembleDemoDebug` succeeds. Pure policy/session tests pass 89 assertions. |
| `pleosReadOnly` | Optional native client with the same relay flow; AAOS gear, vehicle speed and UX restriction reads; Pleos climate-capability and front-left set-temperature reads | Java sources compile against actual Android 34 / `android.car` jars and the official Vehicle 2.3.2 AAR plus Kotlin 1.9.25. |
| Live rest session | Disabled by two gates | Call state is unknown, and independently `SafetyGate.preview()` finally denies every `simulation=false` snapshot. Resolving call state alone will not enable live rest. |
| HVAC/audio/navigation/seat actuation | Absent | No control permissions, no SDK setters and no route or audio commands. `SafetyGate.permitsVehicleWrite()` always returns false. |
| APK build | Demo, read-only and demo instrumentation APKs built on 2026-10-24 | Fresh logs in `../../evidence/run-2026-10-24-astra/vehicle-*-build.log`. Build and runtime are distinct; see the parent integration evidence for install/service observations. |

## Build the development app

Open this folder as an independent Android Studio project. Use JDK 17 and Android SDK Platform / Build Tools 34.0.0. The included Gradle wrapper pins 8.7; these versions match the documented AGP 8.5 compatibility matrix. The project pins AGP 8.5.2. The wrapper scripts/JAR came from Gradle's official `v8.7.0` GitHub tag; its JAR checksum was verified against Gradle's published checksum. The properties also pin the distribution SHA-256. The wrapper may download Gradle on its first run. On Windows use `gradlew.bat`.

For the self-contained demo flavor:

```bash
./gradlew :app:assembleDemoDebug
```

For the optional Pleos read-only flavor:

```bash
./gradlew -PincludePleos=true :app:assemblePleosReadOnlyDebug
```

For an explicitly provisioned local HTTPS relay, pass `-PlocalTlsCa=/absolute/path/to/public-ca.pem`.
Only that opt-in registers `localTlsDebug`, with the same package/signing as the corresponding debug flavor.
Build `:app:assembleDemoLocalTlsDebug` or, with `-PincludePleos=true`,
`:app:assemblePleosReadOnlyLocalTlsDebug`. This variant defaults to `https://10.0.2.2:9443`.
Its generated resources contain one public X.509 CA certificate, never a private key. Only exact
`localhost`, `127.0.0.1`, and `10.0.2.2` trust that CA; other hosts retain system trust.
All cleartext traffic is denied in this variant, and platform chain/hostname validation remains enabled.
The existing debug localhost HTTP exceptions and release security configuration are unchanged.
Without the CA property, the local TLS variants and certificate-generation task do not exist.

With that same opt-in, `:app:assembleDemoLocalTlsDebugAndroidTest` and
`:app:assemblePleosReadOnlyLocalTlsDebugAndroidTest` build the `TlsTransportInstrumentation` runner.
It takes `goodOrigin`, `wrongCertOrigin`, `wrongHostnameOrigin`, and optional `expiredOrigin` arguments,
all exact local HTTPS origins, and calls only `/api/health` through the production `RelayClient`.
Certificate/hostname failures must be observed at the TLS layer; connection errors fail the test.
No pairing, credentials or health records are sent. The report is saved to the target app's internal
`files/tls-transport-report.txt`. Build evidence lives in `../../evidence/https-2026-10-24/`;
APK build and packaged-policy inspection do not by themselves prove a successful runtime TLS request.

The ordinary project configuration has no Pleos dependency. The opt-in property adds the official Maven repository and exact coordinate `ai.pleos.playground:Vehicle:2.3.2`, and compiles against `android.car`. The named `android.car` shared-library declaration is optional: the selected official Pleos image did not advertise that library name, while its runtime could resolve `android.car.Car`. The read-only factory checks runtime class availability; if absent, parking, speed, UX and call remain unknown with an explicit unavailable message. This does not grant permissions or enable live rest. Run this flavor only in a compatible Pleos environment; denied permission and unsupported properties remain unknown. See the parent integration evidence for actual install and observed service values.

The official Pleos installation guide currently specifies emulator **2.1.0**, **API 34**, **cold boot**, **4 GB RAM**, minimum app SDK 28 and Gradle 8+. Configure the official Pleos image in Android Studio, then build and install explicitly. A normal Android emulator can show the demo UI, but it does not prove Pleos services work. See the [official emulator guide](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator).

## Local relay demonstration

1. Start the root project's local relay on port 8787. In the Android Emulator, the demo debug default `http://10.0.2.2:8787` reaches the development host. A physical phone uses a different route and must be configured separately.
2. In the vehicle app choose **Create vehicle pairing**. Enter its one-use code in the phone client. Check the same four-digit confirmation on both screens and confirm in the vehicle app.
3. Share a minimal exercise summary on the phone. The vehicle shows it without automatically choosing or starting anything. Or choose **Continue without workout data** to stay in local manual mode.
4. Choose a preset and check the local confirmation box. In the demo flavor explicitly select the synthetic **Parked** scenario; the initial state is unknown.
5. Start the **demo preview**. Choose Driving, Call, Unknown or UX limit to pause it. Returning to Parked does not resume it automatically. Leaving the foreground pauses it and clears local confirmation; confirm and explicitly resume after returning. **End preview** records an early end rather than completion.
6. **Disconnect and delete shared summary** immediately clears local data, invalidates pending callbacks and queues relay deletion. If deletion fails, the app does not report successful remote deletion. The relay's expiry still applies.

Debug HTTP is allowed only to `10.0.2.2`, `127.0.0.1` and `localhost`. Release transport requires HTTPS, TLS 1.2/1.3 and normal platform certificate/hostname validation. Redirects are rejected. Tokens are held in Activity memory, passed in `Authorization` headers and never persisted or logged. On Activity destruction the local token is lost; relay sessions expire under the protocol. The activity performs foreground polling; it does not claim an always-on vehicle background service.

## Timing and visual scope

The native session uses the same timing rules as the supplied browser core: 180 seconds of monotonic active time, 176 seconds of 22 four-second inhale/four-second exhale cycles, then four seconds of natural rest. A single clock computes both elapsed time and quintic shape expansion. Pause freezes the clock and shape, including a pause at 178 seconds; resume requires a fresh explicit action and valid local gates. Repeated Start cannot reset an active or paused session. A reduced-motion option fixes expansion at 0.5, while normal completion ends at 0. Workout expiry or relay loss clears received workout context; the local silent session can continue if its local safety gates remain valid.

`RestOrbView` now renders Graphite's single open cubic path and one 4.5dp point, sampled by path length. The curve uses normalized v5.1 points `(0.18,0.76) / (0.38,0.76) / (0.62,0.24) / (0.82,0.24)`, a 1.25dp stroke, and no halo. Night Graphite palette tokens are shared with the native screen. Session status/end and timer/pause/resume sit outside the setup scroll. `연결·설정` opens pairing, preset, synthetic-signal and reduced-motion controls. Window/font configuration changes rebuild the layout without replacing the in-memory session. The system animator-disable preference and app reduced-motion choice are ORed; the renderer stops scheduling frames during natural rest, pause and terminal states.

A separate Handler deadline is scheduled on explicit Start/Continue and invalidated on pause/end/background. It does not depend on the periodic UI refresh or the renderer. Clock observations also commit completion at 180000ms even if a frame arrives at 185000ms. No sound or vehicle writes are introduced. Other themes and the complete motion catalog remain outside this P0.

The test-only `GraphiteInstrumentation` runner drives the actual demo Activity and captures screenshots. It waits ten real seconds while paused; the default full suite's long-boundary screenshots use explicitly synthetic elapsed time injected in test code. It removes the periodic tick and hides the renderer to check independent completion. Build it with `:app:assembleDemoDebugAndroidTest`, install both APKs on the selected test emulator, then run `adb -s SERIAL shell am instrument -w ai.pitin.vehicle.demo.test/ai.pitin.vehicle.GraphiteInstrumentation`. Output is under the target app's internal `files/graphite-acceptance/`; collect it with `node scripts/collect-native-evidence.mjs SERIAL ANDROID_USER OUTPUT_FOLDER` from the package root. Official Pleos uses user10 in this run. Optional `-e scenario system-reduced`, `paused-resize`, and `real-duration` runners verify system preference, host-driven resize, and a real180-second monotonic run respectively. The last scenario does not inject elapsed time. See the parent execution report for fresh passing runtime results and screenshots; a built test APK alone is not runtime proof.

## Read-only parking signals

`ParkingSignals` uses `CarPropertyManager` to check capability and read `GEAR_SELECTION` and `PERF_VEHICLE_SPEED`, with `Car.PERMISSION_POWERTRAIN` and `Car.PERMISSION_SPEED`. The speed property is meters/second and is converted to km/h for display. It reads `CarUxRestrictionsManager.getCurrentCarUxRestrictions()` and registers an update listener. Reads run off the UI thread. A successful current-service query has a monotonic observation time; a value becomes unknown if no successful query completes within five seconds. This measures service-observation freshness, not independent physical sensor freshness.

The API 34 `CarPropertyValue.getStatus()` check is intentionally retained despite its deprecation annotation: the implementation rejects unavailable/error results. The Windows follow-up exercised the official Pleos AVD's running Car Service; its observations and unsupported climate read are recorded in the parent execution report. This is emulator evidence, not physical sensor validation. There is no inference that a matching car model has a property or that a permission declaration proves a grant.

**Call state stays unknown.** A phone being paired, the vehicle being in P, an audio-focus state, or a missing mobile telephony call is insufficient evidence that the vehicle has no active Bluetooth call. Separately, `SafetyGate.preview()` rejects all non-simulation snapshots. A verified call adapter alone is not an activation switch: any future live gate requires explicit integration review and negative-path tests. This source shows a static preset preview and does not start a live rest session. It also makes no claim of app-market approval or distraction-optimized status.

## Repeat the checks

Run `npm run test:vehicle-core` from the **package root** for the portable Windows/macOS/Linux runner. The 2026-10-24 Windows JDK 17 run passes 89 assertions, including delayed-frame completion, terminal non-resurrection, ten-second pause exclusion and multiple blockers. RED/GREEN and final results are in `../../evidence/run-2026-10-24-astra/vehicle-session-*.log`. The older 81-assertion results in `../../evidence/handoff-v0.2/` remain historical evidence.

The policy/session test needs a Java compiler but no Android SDK:

```bash
bash scripts/test-core.sh
```

To repeat Java source compilation with locally available API binaries:

```bash
python scripts/check-native-java.py \
  --android-jar /path/to/android-34/android.jar \
  --car-jar /path/to/android-34/optional/android.car.jar \
  --pleos-aar /path/to/Vehicle-2.3.2.aar \
  --kotlin-stdlib /path/to/kotlin-stdlib-1.9.25.jar \
  --flavor all
```

This helper checks the Pleos AAR's recorded SHA-256 before compiling. It generates only a temporary `BuildConfig`, does not replace SDK classes with mocks, and explicitly does not test APK packaging or runtime behavior.

The checked Vehicle AAR SHA-256 is `e11fdfe498cad3ecc0d5a2a6992b0c4a4d135195f1455018127bc069fd5ef937`. The dependency is downloaded from Pleos during an opted-in Gradle build; third-party SDK binaries are not bundled here. Further evidence and availability constraints are recorded in [research-pleos.md](../../docs/research-pleos.md).

Gradle wrapper JAR SHA-256: `cb0da6751c2b753a16ac168bb354870ebb1e162e9083f116729cec9c781156b8`. Gradle 8.7 binary distribution SHA-256: `544c35d6bd849ae8a5ed0bcea39ba677dc40f49df7d1835561582da2009b961d`. See [Gradle's official checksum reference](https://gradle.org/release-checksums/).
