# 차분(구 Pit-In) Gleo 앱 연동 — 2026-10-26

## 구현 범위

공식 Gleo Interface의 Activity Action과 Broadcast Callback 형식을 구현했다. 별도 AI API, 비밀 키, 서버 호출은 추가하지 않는다.

| Action | 앱 동작 | Callback |
|---|---|---|
| `OPEN_PIT_IN` | 기존 Pit-In 화면을 연다. 선택·세션은 바꾸지 않는다. | `success` |
| `PREPARE_QUIET_REST` | 진행 중 세션이 없으면 quiet_rest를 선택하고 준비 화면을 연다. 로컬 동의를 해제하고 사용자 확인을 기다린다. | `asking` |
| 실행/일시정지 중 준비 요청 | 세션·시간·선택·동의·화면을 유지한다. | `asking`, `mode: silent` |
| 알려진 Action의 잘못된 입력 | 아무 기능도 수행하지 않는다. | `fail` |

두 함수는 무인자다. extras가 없거나 `ACTION_PARAM`이 빈 JSON 객체 문자열인 경우만 허용한다. 다른 키, 자료형, 값, 후행 데이터, 128자를 넘는 문자열은 거부한다. 외부 Intent는 사용자 확인이나 차량 안전 상태의 증거로 쓰이지 않는다.

`MainActivity`의 `singleTask`와 `onNewIntent`로 기존 인스턴스에 전달한다. 처리한 Intent를 일반 진입 Intent로 교체해 화면 재생성 시 이전 요청을 재실행하지 않는다. 외부 요청은 세션을 시작·재개·종료하거나 실제 차량 설정을 바꿀 수 없다. `SafetyGate.java`, `RestSession.java`는 바이트가 유지됐다.

Callback에는 고정된 앱 화면 처리 결과만 담는다. 운동 요약·심박·선택한 운동·연결 번호·토큰은 포함하지 않는다. 실행/일시정지 중에는 Gleo의 음성 출력을 `silent`로 요청한다.

## 스키마와 공식 문서

`app/src/main/gleo/schema.json`은 생성 입력이다. Gradle이 실제 variant의 `applicationId`와 `versionName`을 넣어 APK의 `assets/schema.json`을 만든다. 따라서 demo는 `ai.pitin.vehicle.demo`, 읽기 전용은 `ai.pitin.vehicle.readonly`를 등록한다. `invokeWord`는 `차분`(2026-10-29에 `피트인`에서 바꿈)이고 설명은 공식 규칙에 맞게 영어로 작성했다.

- [Gleo Interface 개념](https://document.pleos.ai/en/docs/pleos-only/app-integration-guide/guide/core-concepts)
- [App Schema 형식](https://document.pleos.ai/en/docs/pleos-only/app-integration-guide/guide/app-capability)
- [Intent 및 Broadcast 예시](https://document.pleos.ai/en/docs/pleos-only/app-integration-guide/guide/examples)
- [Callback 형식](https://document.pleos.ai/en/docs/pleos-only/app-integration-guide/guide/action_callback)
- [스키마 전달·심사 승인](https://document.pleos.ai/en/docs/pleos-only/app-integration-guide/guide/deliver-schema)

공식 문서는 App Schema 심사 승인 뒤 Gleo 연동이 활성화된다고 명시한다. APK에 schema를 포함한 것과 실제 Gleo의 음성 해석·호출 성공은 다르다. 이 작업은 등록 신청이나 심사 승인을 수행하지 않았다. 2026-10-19 에뮬레이터의 기존 LLM SDK 종료를 이 Gleo Interface 전체의 종료로 해석하지 않는다.

## 검증

작업 증거는 워크스페이스 루트 `evidence/gleo-integration-2026-10-26/`에 있다.

- JVM: 기존 안전·동기화·문구 271개 + 새 Gleo 입력 경계 33개 = 304개 통과.
- APK: demoDebug, pleosReadOnlyDebug, demoLocalTlsDebug, pleosReadOnlyLocalTlsDebug, demoLocalTlsDebugAndroidTest 빌드 성공. `apk-metadata-final.json`에 5개 파일의 크기·해시와 실제 APK 안 스키마를 기록했다.
- Android lint: demo/read-only 오류 0, 경고 8/9개. Gleo 소스 신규 lint 항목 없음.
- `emulator-5570`, 사용자 10: 앱과 테스트 APK `install -r` 성공, `GleoInstrumentation` 44개 앱측 통합 검사 통과.
- Android 검사: 냉시작 준비, 잘못된 입력 거부, 공식 콜백 수신, 수동 시작 뒤 ACTIVE/PAUSED 유지, 재개 불가, 동의 불변, 패키지·버전 스키마 일치, 실제 차량 쓰기 금지를 확인했다.
- 주 작업자는 공개 `am start` Action으로 실제 Android 외부 진입도 확인했다. Quiet 선택·체크 해제·시작 차단 화면은 워크스페이스 루트 `evidence/gleo-runtime-2026-10-26/pitin-prepare-public-intent.png`에 기록했다. 이 역시 Gleo 음성이 생성한 요청은 아니다.
- 초기 Android 실행에서 빈 객체 정규식의 닫는 중괄호 이스케이프가 빠져 거부되는 문제가 드러났다. JVM만 통과했던 문제를 수정하고 두 APK를 다시 빌드해 Android 44개 검사를 통과했다. 초기 실패 로그는 보존했다.

이 Android 검사는 합성 차량 fixture와 기존 앱 컨트롤을 사용하며, 재진입 일부는 `onNewIntent`를 호출한다. 실제 음성 인식, Gleo의 스키마 등록, 실제 Gleo가 요청을 생성해 앱으로 전달한 성공 증거는 아니다. 차량의 실제 상태/통화 검증 및 실차 제어는 여전히 범위 밖이다.

## 재현

빌드의 `-PlocalTlsCa`에는 기존 공개 CA 절대 경로를 지정한다. `-PincludePleos=true -PgleoAcceptance=true`와 다음 task를 사용한다.

```text
:app:runVehiclePolicyChecks
:app:assembleDemoLocalTlsDebug
:app:assemblePleosReadOnlyLocalTlsDebug
:app:assembleDemoLocalTlsDebugAndroidTest
```

명시적인 앱측 진입 호출은 다음과 같다. 이 명령의 성공을 Gleo 음성 호출 성공으로 기록하지 않는다.

```text
adb -s emulator-5570 shell am start --user 10 -a OPEN_PIT_IN -p ai.pitin.vehicle.demo
adb -s emulator-5570 shell am start --user 10 -a PREPARE_QUIET_REST -p ai.pitin.vehicle.demo
adb -s emulator-5570 shell am instrument --user 10 -w ai.pitin.vehicle.demo.test/ai.pitin.vehicle.GleoInstrumentation
```

두 Action은 인자가 없으므로 직접 ADB 시연에서도 JSON extra를 생략할 수 있다. 테스트는 앱 상태를 초기화하므로 사용자 시연 중간에 끼워 넣지 않는다. `dist/`와 패키지 manifest는 이 작업자 범위에서 갱신하지 않았으며 최종 통합자가 처리한다.

## 2026-10-28 추가 — 세 번째 기능 `OPEN_CABIN_SETTINGS(section)` (리디자인 사본)

사용자 확정(2026-10-28)에 따라 `schema.json`에 세 번째 함수를 더했다. 1단계는 화면 열기만 한다.

| Action | 앱 동작 | Callback |
|---|---|---|
| `OPEN_CABIN_SETTINGS` + `ACTION_PARAM={"section":"seat"\|"climate"\|"light"}` | 차 안 설정을 그 탭으로 연다. 값은 바꾸지 않는다. | `success` |
| 쉬는 중(ACTIVE·PAUSED)에 같은 요청 | 쉼 화면(보호 원본)을 그대로 둔다. | `fail`, `mode: silent` |
| 인자 없음·다른 키·대소문자 다름·이스케이프·후행 데이터·128자 초과 | 아무것도 바꾸지 않는다. | `fail` |

- 인자 검사는 `GleoActionPolicy.cabinSection()`의 정규식 하나로 끝난다(키 하나, 문자열 enum만).
- `OPEN_PIT_IN`·`PREPARE_QUIET_REST`의 판정과 기존 33개 검사는 그대로다. 새 검사는 `tests/GleoCabinActionTest.java`(110개).
- 화면 표시: 시작 화면 가운데 위 알약(‘Gleo로 열었어요’, ‘조용히 쉬기를 골라 뒀어요 · 시작은 화면에서’), 차 안 설정은 제목 옆 알약. 화면을 한 번 만지면 사라진다.
- `PREPARE_QUIET_REST`는 이제 리디자인의 준비 화면인 ‘시작’을 연다. 선택(quiet_rest)·동의 해제·`asking` 응답은 같다.
- 실제 Gleo 음성 호출·스키마 심사 승인은 여전히 확인되지 않았다. `am start`로 앱 쪽 진입만 확인했다.
