# mspace-gateway

M-space의 **API 게이트웨이**예요. 밖에서 들어오는 요청은 모두 여기를 지나요.

```
학생 폰 → nginx(학교 서버, HTTPS) → mspace-gateway → web / core / agent
```

| 경로 | 보내는 곳 | 로그인 |
|---|---|---|
| `/api/agent/**` | agent (비서 채팅, core를 거치지 않음) | 필요 |
| `/api/climate/**` | climate (방 공기 기준선·예측·기기 제어, 11/12) | 필요 |
| `/api/**`, `/actuator/health` | core | 필요 (아래 공개 API 빼고) |
| 그 밖 (`/`, 화면 파일) | web | 필요 없음 |

**로그인 없이 여는 API** (`PublicPaths.java`): `POST /api/auth/email-codes`, `/api/auth/login`, `/api/auth/refresh`, `/api/auth/logout`, `/api/access/verify`·`/api/sensor-events`·`/api/rooms/{roomId}/tidy-checks`·`/api/rooms/{roomId}/tidy-photos`(장치 토큰은 core가 확인), `GET /api/rules`, `/api/rooms`, `/api/rooms/availability`, `/api/rooms/status`, `/api/rooms/{roomId}/current`(장치 토큰은 core가 확인), `/actuator/health`(core 상태, 배포 확인용)

게이트웨이 자기 상태는 관리 포트 **8001**의 `/actuator/health`예요 (밖으로 열지 않음)

## 하는 일
- **인증**: core가 HS256으로 서명한 JWT를 확인해요 (서명, 만료, 발급자 `mspace-core`). 틀리면 401로 막고 core까지 보내지 않아요
- **인가**: 지금은 "로그인했는가"까지만 봐요. 관리자 화면이 생기면 토큰의 `scope`로 경로를 나눠요
- **로드밸런싱**: `lb://core`처럼 이름으로 보내요. 같은 서비스를 더 띄우면 `application.yml`의 주소 목록에 한 줄 더하면 돌아가며 보내요
- core도 지금처럼 토큰을 한 번 더 확인해요 (core 코드는 바꾸지 않음)
- **센서 값 사본**(`SensorEventMirrorFilter`): `POST /api/sensor-events`를 core가 받아 주면(2xx) 같은 본문을 climate에도 줘요. core가 거절한 값은 안 보내고, 사본은 기다리지 않아 장치 응답이 늦어지지 않아요

## 설정 (환경 변수)
| 이름 | 기본값 | 설명 |
|---|---|---|
| `JWT_SECRET` | (없음, 필수) | core와 **같은 값**. 32바이트 이상. 비면 켜지지 않아요 |
| `JWT_ISSUER` | `mspace-core` | |
| `CORE_URI` | `http://core:8080` | |
| `AGENT_URI` | `http://agent:8091` | |
| `WEB_URI` | `http://web:80` | |
| `CLIMATE_URI` | `http://climate:8095` | |
| `CLIMATE_MIRROR_URL` | (없음) | 센서 값 사본을 받을 climate 안쪽 주소(예: `http://climate:8095/internal/sensor-events`). 비면 사본을 안 보내요 |
| `MANAGEMENT_PORT` | `8001` | 게이트웨이 자기 health |

## 버전
Spring Boot **4.0.9** + Spring Cloud **2025.1.4** (Gateway 5.0.4). core(4.1.2)와 다른 이유: Boot 4.1용 Spring Cloud 2026.0이 아직 RC라서 정식 버전을 써요. 2026.0이 정식으로 나오면 맞춰요.

## 실행
```bash
./mvnw verify                      # 테스트 12개: 토큰 없음·틀린 비밀값·만료·발급자 → 401, 공개 API·agent·web 길, health는 core로
JWT_SECRET=... ./mvnw spring-boot:run   # :8000
```
