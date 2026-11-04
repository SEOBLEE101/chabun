# 차분 (Chabun)

운동을 마치고 주차한 차에 돌아왔을 때, 내가 고른 쉼에 맞춰 에어컨·시트·조명을 맞추고 3분 동안 숨을 고르게 해 주는 Pleos 차량 앱입니다.

이름 '차분'에는 숨이 차분해진다는 뜻과 '차(車) 안의 3분(分)'이라는 뜻을 함께 담았습니다.

| 차 · 시작 화면 | 차 · 3분 쉬기 |
|---|---|
| ![차 시작 화면](docs/screens/car-home.png) | ![차 3분 쉬기](docs/screens/car-rest.png) |

| 폰 · 오늘 | 폰 · 취향 |
|---|---|
| <img src="docs/screens/phone-home.png" width="270" alt="폰 오늘 화면"> | <img src="docs/screens/phone-taste.png" width="270" alt="폰 취향 화면"> |

## 무엇을 하나
- **3분 쉬기**: 4초 들이쉬기·4초 내쉬기 안내에 맞춰 180초 동안 숨을 고릅니다. 일시정지하면 시간과 그림이 그 자리에서 멈추고, 다시 시작은 직접 누릅니다.
- **차 안 맞추기와 되돌리기**: 쉼을 시작하면 그 쉼의 에어컨·시트 열선/통풍·앰비언트 조명을 차에 적용하고, 마치면 이 쉼이 바꾼 항목만 쉬기 전 값으로 되돌립니다. 그사이 사람이 손으로 바꾼 값은 그대로 둡니다. 시트 자세는 움직이지 않습니다.
- **주차 안전 조건**: 기어 P, 속도 0, 차 화면 사용 제한 없음, 통화 중 아님을 확인한 뒤에만 시작하고, 차에 값을 보내기 직전마다 다시 확인합니다.
- **폰에서 고르고 차에서 시작**: 폰에서 고른 쉼과 차 안 설정이 차 시작 화면에 도착합니다. 운동 기록(Health Connect) 연결은 선택이며, 동의하면 운동 종류·시간·마친 시각만 전합니다.
- **Gleo 음성**: 조용한 쉼 준비, 차 안 설정 열기, 차 안 맞추기 세 기능을 Gleo App Schema로 등록했습니다(`invokeWord` `차분`). 쉬기 시작은 화면에서 직접 누를 때만 합니다.

## 폴더
| 폴더 | 내용 |
|---|---|
| `vehicle-android/` | 차량 앱. Android Automotive(Pleos), Java |
| `mobile-android/` | 폰 앱. Android, Kotlin |
| `server/` | 폰·차 연결 서버. Node.js, HTTPS |
| `apk/` | 실행 가능한 release APK |
| `docs/screens/` | 화면 캡처(Pleos Emulator, Android 에뮬레이터) |

## APK
| 파일 | 앱 | 패키지 |
|---|---|---|
| `apk/성명_차분_release.apk` | 차량 앱(데모 빌드) | `ai.pitin.vehicle.demo.chabun` |
| `apk/성명_차분-읽기전용_release.apk` | 차량 앱(읽기 전용 빌드) | `ai.pitin.vehicle.readonly.chabun` |
| `apk/성명_차분-폰_release.apk` | 폰 앱 | `com.recovery.companion.chabun` |

- 세 APK 모두 디버그가 꺼진 release 빌드이고, 같은 제출용 키로 서명했습니다. 차량 제어 권한(`CONTROL_CAR_*`)은 어느 APK에도 없습니다.
- **가상 차량으로 차 안 맞추기와 되돌리기까지 보려면 데모 APK**를 쓰세요. 앱 안의 가상 차량에 차 안 설정을 맞추고, 가상 차량은 '주차 중'으로 시작합니다.
- **Pleos 에뮬레이터의 실제 차 상태로 쉬기를 보려면 읽기 전용 APK**를 쓰세요. 기어·속도·화면 제한·통화 상태를 읽어 3분 쉬기를 허용하거나 막습니다. 차 값은 바꾸지 않고 손으로 맞출 값을 안내합니다.
- 실제 차 값을 바꾸는 빌드(`pleos`)는 이 폴더에 넣지 않았습니다. 제어 권한의 지원과 공모전 제출 범위를 운영사무국에 확인 중입니다. 소스에서 만들 수 있습니다(아래 '직접 빌드').

## 실행 방법
### 차량 앱
1. Pleos Emulator(Android 14, API 34 차량 이미지)를 켭니다.
2. 운전자 사용자 번호를 확인하고 그 사용자에 설치합니다. 시험한 에뮬레이터에서는 10번이었습니다.
   ```
   adb shell am get-current-user
   adb install -r --user 10 apk/성명_차분_release.apk
   ```
3. 앱 목록에서 **차분 · Demo**를 엽니다.
4. **3분 쉬기**를 누르면 차 안이 맞춰지고 안내가 시작됩니다. **일시정지**·**마치기**로 멈추거나 끝냅니다.
5. 시작 화면의 **바꾸기**에서 오늘의 쉼, 쉬는 방식, 화면 분위기, 소리, 동작 줄이기를 고릅니다. 머리말 오른쪽의 **이 차에서 쓸 기능** → **차 안 설정**에서 시트·에어컨·조명 값을 정합니다.
6. 읽기 전용 APK도 같은 방법으로 설치해 **차분 · Read only**를 엽니다. 처음 열 때 속도 읽기 권한을 허용하세요. 에뮬레이터의 기어가 P이고 속도가 0이면 시작할 수 있습니다.

### 폰 앱
1. Android 9(API 28) 이상 기기나 에뮬레이터에 설치합니다.
   ```
   adb install -r apk/성명_차분-폰_release.apk
   ```
2. **취향**에서 쉼과 차 안 설정을 고릅니다. 운동 기록 연결은 **연결 → 운동 기록**에서 선택합니다.
3. 폰·차 연결(번호와 확인 숫자), **차에 이 쉼 보내기**, 운동 요약 보내기는 연결 서버가 있어야 합니다. 제출 APK는 서버 없이 바로 실행되도록 구성해서 이 기능들은 동작하지 않습니다. 시연 영상 0:25~1:23에서 볼 수 있습니다. 개발 환경에서는 로컬 HTTPS 서버로 시험했습니다(아래 '연결 서버').

## 테스트 환경
- Windows 11, JDK 17, Node.js 24
- 차량: Pleos Emulator(Android Emulator 37.2.12, `pleos_car_emulator` API 34 시스템 이미지)
- 폰: Android 15(API 35) 에뮬레이터
- 차량 SDK: Pleos Vehicle SDK 2.3.2(`ai.pleos.playground:Vehicle:2.3.2`, Pleos Maven)
- 실차에서는 아직 확인하지 못했습니다.

## 직접 빌드
JDK 17과 Android SDK가 필요합니다. Gradle wrapper가 들어 있습니다.

```
cd vehicle-android
./gradlew :app:assembleDemoDebug                          # 차량 앱 데모(가상 차량)
./gradlew -PincludePleos=true :app:assemblePleosReadOnlyDebug  # 실제 차 상태를 읽기만 하는 빌드(Pleos SDK)
./gradlew -PincludePleos=true :app:assemblePleosDebug     # 실제 차 값을 바꾸는 빌드(Pleos SDK)
./gradlew :app:runVehiclePolicyChecks                     # 차 안 계획·변경 장부·안전 조건 등 순수 Java 검사

cd ../mobile-android
./gradlew :app:assembleDebug                              # 폰 앱
```

- 제출용 서명 빌드는 `-PchabunSigning=<서명 설정 파일>`을 주고 `:app:assembleDemoSubmission`, `-PincludePleos=true :app:assemblePleosReadOnlySubmission`(차), `:app:assembleSubmission`(폰)으로 만듭니다. 서명 키는 이 저장소에 없습니다.
- `pleos` 빌드는 `pleos.car.permission.CONTROL_CAR_CLIMATE`·`CONTROL_CAR_SEATS`·`CONTROL_CAR_LIGHTS` 권한을 선언합니다. App Market에는 승인된 범위에서만 배포합니다. `pleosReadOnly` 빌드는 이 권한을 선언하지 않습니다.

## 연결 서버
- `server/`는 폰·차 연결 승인, 쉼 취향 공유(`propose_rest`), 운동 요약 전달을 맡습니다. 쉼 취향은 `server/preset-sync/index.mjs`(9447), 운동 요약은 `server/https.mjs`(9443)가 받고, 둘 다 이 컴퓨터(127.0.0.1)에서만 엽니다.
- 서버 실행에는 로컬 HTTPS용 개발 인증서가 필요합니다. 인증서와 개인 키는 이 저장소에 넣지 않았습니다.
- 서버 검사:
  ```
  cd server
  node --test tests/continuity.test.mjs tests/preset-sync.test.mjs
  ```
  2026-11-04에 43개 모두 통과했습니다. `npm test`로 운동 요약 중계 시험까지 94개를 돌릴 수 있습니다(같은 날 통과, 약 1분). 컴퓨터 부하가 크면 시간 초과로 일부가 실패할 수 있습니다.

## 검증한 것과 아직 못 한 것
- **확인함**(에뮬레이터): 폰 → 서버 → 차로 쉼과 차 안 설정이 도착하고, 차에서 시작했을 때 시트 통풍 2단계·조명 켜기가 적용됐다가 마칠 때 쉬기 전으로 돌아가는 것을 차에서 다시 읽어 확인했습니다.
- **확인함**(에뮬레이터, 2026-11-04): 읽기 전용 빌드에서 실제 차 상태로 3분 쉬기(시작·일시정지·계속·마치기). 합성 운동 기록의 운동 요약 전달(폰 '차로 보내기' → 차 '운동 기록이 도착했어요 · 동승자 보호로 숨김').
- **확인함**(자동 검사): 차량 앱 순수 Java 검사 항목 788개(차 안 계획 126개, 변경 장부 30개, 설정 53개, Gleo 차 안 기능 113개 등), 서버 검사.
- **아직 못 함**: 에뮬레이터에서 공조 전원이 켜지지 않고 조명 밝기·색이 반영되지 않아, 이 항목은 실차나 공식 검증 환경에서 확인할 예정입니다. 실제 음성 → Gleo → 앱 호출은 스키마 승인 뒤 확인합니다(지금은 같은 요청을 직접 보내 확인).

## 개인정보와 안전
- 차분은 피로나 회복 상태를 판정하지 않고, 의료적 효과를 주장하지 않습니다.
- 운동 기록은 폰에 둡니다. 사용자가 동의한 경우에만 운동 종류·시간(분)·마친 시각을 차에 전하고, 심박 원본과 위치는 보내지 않습니다.
- 차 값은 주차 조건이 맞을 때만 바꾸고, SDK의 '성공' 응답이 아니라 다시 읽은 값으로 결과를 판단합니다.

## 세부 문서
- `vehicle-android/README.md`, `mobile-android/README.md`: 개발 기록(시점별 변경). 최신 설명은 이 README입니다.
- `vehicle-android/GLEO_INTEGRATION.md`: Gleo 연동 기록.
