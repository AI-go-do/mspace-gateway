package kr.ac.chosun.mspace.gateway;

import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.server.util.matcher.OrServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;

/**
 * 로그인 없이 열어 두는 API. core 코드(develop d9724f7)에서 LoginUser를 받지 않는 엔드포인트와 같다.
 * core에 공개 API가 늘면 여기에도 더한다.
 */
final class PublicPaths {

    private PublicPaths() {
    }

    static ServerWebExchangeMatcher matcher() {
        List<ServerWebExchangeMatcher> list = List.of(
                post("/api/auth/email-codes"),   // 인증번호 메일 보내기
                post("/api/auth/login"),         // 번호 확인 + 토큰 받기
                post("/api/auth/refresh"),       // 액세스 토큰이 끝났을 때 (만료 토큰을 들고 와도 막지 않는다)
                post("/api/auth/logout"),
                post("/api/access/verify"),      // 문 앞 키패드 중계. 장치 토큰은 core가 확인
                post("/api/sensor-events"),      // 재실·CO2 센서(FR-DEV-101~104). 장치 토큰은 core가 확인 (core #60)
                get("/api/rules"),               // 이용 규칙, 로그인 전에도 본다 (FR-WEB-003, core #57)
                get("/api/rooms"),
                get("/api/rooms/availability"),
                get("/api/rooms/status"),       // 방 현황, 로그인 전 홈 화면에서도 본다 (FR-DEV-102, core #62)
                get("/actuator/health"),        // core의 health (배포 확인용)
                head("/api/rules"),
                head("/api/rooms"),             // HEAD는 GET과 같은 응답에서 본문만 뺀 것
                head("/api/rooms/availability"),
                head("/api/rooms/status"),
                head("/actuator/health"));
        return new OrServerWebExchangeMatcher(list);
    }

    private static ServerWebExchangeMatcher post(String path) {
        return new PathPatternParserServerWebExchangeMatcher(path, HttpMethod.POST);
    }

    private static ServerWebExchangeMatcher get(String path) {
        return new PathPatternParserServerWebExchangeMatcher(path, HttpMethod.GET);
    }

    private static ServerWebExchangeMatcher head(String path) {
        return new PathPatternParserServerWebExchangeMatcher(path, HttpMethod.HEAD);
    }
}
