# 3분 쉬기 수동 테스트 안내

로컬 continuity 서버의 `/rest-guide/`에서 제공하는 독립 정적 안내 페이지입니다. `/`는 기존 연결 화면으로 돌아갑니다. 루트 서버에서 `/rest-guide/styles.css`, 기존 네 미디어, 아래 모션 인계 여섯 경로와 기존 `/fonts/NotoSansKR.ttf`를 제공해야 합니다. JavaScript, 외부 의존성, 외부 요청은 없습니다.

- 종이색·올리브는 기존 `web/continuity/styles.css`의 토큰을 사용합니다. Graphite 참고 자료는 기존 차콜 디자인을 보존합니다.
- 두 PNG와 두 MP4는 기존 파일의 바이트 동일 복사본입니다. 원본 경로, 크기, SHA-256은 `asset-manifest.json`에 기록했습니다. 원본 파일은 수정하지 않았습니다.
- 영상은 `controls muted playsinline preload="metadata"`이며 자동 재생·반복 재생하지 않습니다. 실제 앱 세션과 연결되지 않은 참고 영상으로 표시합니다. 10초 루프 소스도 페이지에서는 자동 반복하지 않습니다.
- 실제 실행 테스트는 폰 프리셋 선택 → 차량 확인 → 이 선호로 쉬기 준비 → 로컬 확인 → 3분 쉬기 → 일시정지 → 계속하기 순서입니다. 합성 Call/Parked 복구 후에도 수동 재개가 필요합니다.
- 이 안내의 파일 검사는 차량 실행·영상 재생·실차 제어·건강 연동 검증을 뜻하지 않습니다. 서버 경로 및 실제 화면 검수는 통합 작업에서 별도로 수행합니다.

수동 페이지 검수: 넓은 화면과 390px 폭에서 이미지/영상 두 쌍과 문구가 잘리지 않는지, 키보드로 링크와 기본 영상 조작에 접근 가능한지, 영상이 자동 재생하지 않는지 확인합니다. 원본 열기와 연결 화면 복귀 링크도 확인합니다.

## 2026-10-25 모션 인계 갤러리

최신 Hybrid Motion 32초 영상을 안내 상단에 크게 배치했습니다. 접을 수 있는 기본 HTML `details` 영역에서 Code Motion 32초 및 Graphite 진행/재개, Pearl 진행, Umber 자유 모드의 10초 루프 네 개를 비교합니다. 기존 PNG/MP4 두 쌍과 수동 시작·정지·재개 안내는 보존했습니다.

실제 차량 앱은 Grok 정적 곡선 위에 코드로 제어하는 점 하나를 표시하고 4초 들숨·4초 날숨 세션 시간에 맞춰 움직입니다. 일시정지 중 시간·안내·점은 동결됩니다. 영상의 자동 네 장면 전환·테마 페이드·정지 화면의 움직이는 후광은 쇼릴 참고 표현입니다. Pearl/Umber 참고 영상의 존재는 해당 테마의 실제 차량 구현·실행 검증을 의미하지 않습니다.

루트 서버가 아래 URL을 `reference/motion-handoff-2026-10-25/Pit-In/`의 원본 파일로 매핑합니다. 추가 미디어를 복사하지 않으며, 경로·바이트 수·SHA-256은 `asset-manifest.json`에 기록했습니다.

| URL (`/rest-guide/assets/` 아래) | 인계 폴더 내 원본 |
| --- | --- |
| `hybrid-motion.mp4` | `hybrid-motion/renders/pit-in-hybrid-motion.mp4` |
| `code-motion.mp4` | `code-motion/renders/pit-in-code-motion.mp4` |
| `graphite-guided-loop-10s.mp4` | `grok-videos/loops/graphite-guided-loop-10s.mp4` |
| `graphite-resume-loop-10s.mp4` | `grok-videos/loops/graphite-resume-loop-10s.mp4` |
| `pearl-guided-loop-10s.mp4` | `grok-videos/loops/pearl-guided-loop-10s.mp4` |
| `umber-freedom-loop-10s.mp4` | `grok-videos/loops/umber-freedom-loop-10s.mp4` |

Graphite 루프에만 기존 해당 Graphite 이미지 포스터를 연결했습니다. Pearl·Umber와 합성 영상에는 다른 테마의 포스터를 사용하지 않습니다. 새 영상 영역은 16:9 비율이며 좁은 화면에서는 한 열로 표시합니다. 추가 검수에서는 최신 영상 재생, 비교 영역의 키보드 펼치기, 여섯 원본 링크와 기본 영상 조작을 확인합니다.
