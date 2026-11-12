package kr.ac.chosun.mspace.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 진짜 게이트웨이를 띄우고, core·agent·web 자리에 가짜 서버를 둔다.
 * 가짜 서버는 자기 이름(core/agent/web)과 받은 Authorization 헤더를 돌려준다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutingAndAuthTest {

    static final String SECRET = "test-secret-test-secret-test-secret-0123456789";
    static final HttpServer CORE = fake("core");
    static final HttpServer AGENT = fake("agent");
    static final HttpServer WEB = fake("web");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("mspace.jwt.secret", () -> SECRET);
        r.add("management.server.port", () -> "0");
        r.add("CORE_URI", () -> "http://127.0.0.1:" + CORE.getAddress().getPort());
        r.add("AGENT_URI", () -> "http://127.0.0.1:" + AGENT.getAddress().getPort());
        r.add("WEB_URI", () -> "http://127.0.0.1:" + WEB.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        CORE.stop(0);
        AGENT.stop(0);
        WEB.stop(0);
    }

    @Value("${local.server.port}")
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("토큰 없이 로그인이 필요한 API를 부르면 게이트웨이가 401로 막는다 (core까지 안 감)")
    void noTokenIs401() throws Exception {
        assertThat(get("/api/reservations/me", null).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("맞는 토큰이면 core로 보내고, Authorization 헤더도 그대로 넘긴다")
    void validTokenGoesToCore() throws Exception {
        String token = token(SECRET, "mspace-core", Instant.now().plusSeconds(600));
        HttpResponse<String> res = get("/api/reservations/me", token);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).isEqualTo("core Bearer " + token);
    }

    @Test
    @DisplayName("다른 비밀값으로 서명한 토큰은 401")
    void wrongSecretIs401() throws Exception {
        String token = token("other-secret-other-secret-other-secret-0123", "mspace-core", Instant.now().plusSeconds(600));
        assertThat(get("/api/reservations/me", token).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("만료된 토큰은 401")
    void expiredIs401() throws Exception {
        String token = token(SECRET, "mspace-core", Instant.now().minusSeconds(600));
        assertThat(get("/api/reservations/me", token).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("발급자(iss)가 다르면 401")
    void wrongIssuerIs401() throws Exception {
        String token = token(SECRET, "someone-else", Instant.now().plusSeconds(600));
        assertThat(get("/api/reservations/me", token).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("공개 API(refresh)는 만료된 토큰을 들고 와도 막지 않고 core로 보낸다")
    void publicPathIgnoresExpiredToken() throws Exception {
        String expired = token(SECRET, "mspace-core", Instant.now().minusSeconds(600));
        HttpResponse<String> res = post("/api/auth/refresh", expired);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).startsWith("core");
    }

    @Test
    @DisplayName("공개 API(빈방 조회)는 토큰 없이 core로 간다")
    void publicGetWithoutToken() throws Exception {
        HttpResponse<String> res = get("/api/rooms/availability?date=2026-11-05", null);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).startsWith("core");
    }

    @Test
    @DisplayName("키패드 중계(/api/access/verify)는 토큰 없이 core로 간다 (장치 토큰은 core가 확인)")
    void deviceVerifyGoesToCore() throws Exception {
        HttpResponse<String> res = post("/api/access/verify", null);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).startsWith("core");
    }

    @Test
    @DisplayName("재실·CO2 센서(/api/sensor-events)는 JWT가 아닌 장치 토큰을 그대로 core에 넘긴다")
    void sensorEventsPassDeviceToken() throws Exception {
        HttpResponse<String> res = post("/api/sensor-events", "device-token-not-a-jwt");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).isEqualTo("core Bearer device-token-not-a-jwt");
    }

    @Test
    @DisplayName("방 카메라의 지금 예약 조회(GET /api/rooms/{id}/current)는 장치 토큰을 그대로 core에 넘긴다")
    void roomCurrentPassesDeviceToken() throws Exception {
        HttpResponse<String> res = get("/api/rooms/2/current", "device-token-not-a-jwt");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).isEqualTo("core Bearer device-token-not-a-jwt");
    }

    @Test
    @DisplayName("방 카메라의 정리 확인(POST /api/rooms/{id}/tidy-checks)은 장치 토큰을 그대로 core에 넘긴다")
    void tidyChecksPassDeviceToken() throws Exception {
        HttpResponse<String> res = post("/api/rooms/2/tidy-checks", "device-token-not-a-jwt");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).isEqualTo("core Bearer device-token-not-a-jwt");
    }

    @Test
    @DisplayName("방 카메라의 정리 확인 사진(POST /api/rooms/{id}/tidy-photos)은 장치 토큰을 그대로 core에 넘긴다")
    void tidyPhotosPassDeviceToken() throws Exception {
        HttpResponse<String> res = post("/api/rooms/2/tidy-photos", "device-token-not-a-jwt");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).isEqualTo("core Bearer device-token-not-a-jwt");
    }

    @Test
    @DisplayName("젯슨 카메라 모델 시험 점수(POST /api/rooms/{id}/model-scores)는 장치 토큰을 그대로 core에 넘긴다")
    void modelScoresPassDeviceToken() throws Exception {
        HttpResponse<String> res = post("/api/rooms/2/model-scores", "device-token-not-a-jwt");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).isEqualTo("core Bearer device-token-not-a-jwt");
    }

    @Test
    @DisplayName("방 아래 다른 경로는 공개가 아니라 토큰 없으면 401")
    void otherRoomSubPathNeedsToken() throws Exception {
        HttpResponse<String> res = post("/api/rooms/2/current", null);
        assertThat(res.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("이용 규칙(GET /api/rules)은 로그인 없이 core로 간다")
    void rulesWithoutToken() throws Exception {
        HttpResponse<String> res = get("/api/rules", null);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).startsWith("core");
    }

    @Test
    @DisplayName("방 현황(GET /api/rooms/status)은 로그인 없이 core로 간다")
    void roomStatusWithoutToken() throws Exception {
        HttpResponse<String> res = get("/api/rooms/status", null);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).startsWith("core");
    }

    @Test
    @DisplayName("관리자 방 환경(GET /api/admin/rooms/environment)은 공개가 아니라 토큰 없으면 401")
    void adminEnvironmentNeedsToken() throws Exception {
        assertThat(get("/api/admin/rooms/environment?minutes=60", null).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("관리자 방 환경은 토큰을 그대로 core에 넘긴다 (관리자인지는 core가 403 ADMIN_ONLY로 가린다)")
    void adminEnvironmentGoesToCore() throws Exception {
        String token = token(SECRET, "mspace-core", Instant.now().plusSeconds(600));
        HttpResponse<String> res = get("/api/admin/rooms/environment?minutes=60", token);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).isEqualTo("core Bearer " + token);
    }

    @Test
    @DisplayName("비서 채팅은 core를 거치지 않고 agent로 바로 간다")
    void agentRoute() throws Exception {
        String token = token(SECRET, "mspace-core", Instant.now().plusSeconds(600));
        HttpResponse<String> res = get("/api/agent/chat", token);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).startsWith("agent");
    }

    @Test
    @DisplayName("비서 채팅도 토큰이 없으면 401")
    void agentNeedsToken() throws Exception {
        assertThat(get("/api/agent/chat", null).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("/api 밖은 화면 파일이라 토큰 없이 web으로 간다")
    void webRoute() throws Exception {
        HttpResponse<String> res = get("/", null);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).startsWith("web");
    }

    @Test
    @DisplayName("/actuator/health는 토큰 없이 core로 간다 (배포 뒤 core 확인용)")
    void healthGoesToCore() throws Exception {
        HttpResponse<String> res = get("/actuator/health", null);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).startsWith("core");
    }

    @Test
    @DisplayName("만료된 지 30초 된 토큰도 401 (core처럼 시계 오차를 봐주지 않는다)")
    void justExpiredIs401() throws Exception {
        String token = token(SECRET, "mspace-core", Instant.now().minusSeconds(30));
        assertThat(get("/api/reservations/me", token).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("만료 시각(exp)이 없는 토큰은 401")
    void noExpIs401() throws Exception {
        assertThat(get("/api/reservations/me", tokenWithoutExp()).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("401 응답은 {code, message} 형식이고, 토큰이 왜 틀렸는지는 알려 주지 않는다")
    void unauthorizedBodyHidesReason() throws Exception {
        HttpResponse<String> res = get("/api/reservations/me", "not-a-jwt");
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.body()).contains("\"code\":\"UNAUTHORIZED\"");
        assertThat(res.headers().firstValue("WWW-Authenticate")).hasValue("Bearer");
    }

    @Test
    @DisplayName("공개 GET API는 HEAD로 불러도 토큰 없이 통과한다")
    void publicHeadWithoutToken() throws Exception {
        HttpResponse<String> res = send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/rooms"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()), null);
        assertThat(res.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("밖에서 보낸 X-User-Id 헤더는 지우고 core로 넘긴다")
    void stripsUserIdHeader() throws Exception {
        String token = token(SECRET, "mspace-core", Instant.now().plusSeconds(600));
        HttpResponse<String> res = send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/reservations/me")).header("X-User-Id", "1").GET(), token);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).doesNotContain("uid=");
    }

    @Test
    @DisplayName("키패드는 JWT가 아닌 장치 토큰을 Bearer로 보내도 막지 않고 그대로 core에 넘긴다")
    void deviceTokenPassesThrough() throws Exception {
        HttpResponse<String> res = post("/api/access/verify", "device-token-not-a-jwt");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).isEqualTo("core Bearer device-token-not-a-jwt");
    }

    @Test
    @DisplayName("DNS 기억 시간을 10초로 줄여, core를 새로 띄워 IP가 바뀌어도 금방 새 주소로 보낸다")
    void shortDnsCache() {
        assertThat(HttpClientConfig.DNS_CACHE_MAX_TTL).isEqualTo(java.time.Duration.ofSeconds(10));
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET(), token);
    }

    private HttpResponse<String> post(String path, String token) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")), token);
    }

    private HttpResponse<String> send(HttpRequest.Builder b, String token) throws Exception {
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String token(String secret, String issuer, Instant exp) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer).subject("42").claim("scope", "user")
                .issueTime(Date.from(exp.minusSeconds(3600))).expirationTime(Date.from(exp)).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    static String tokenWithoutExp() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("mspace-core").subject("42").claim("scope", "user").issueTime(new Date()).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    static HttpServer fake(String name) {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", ex -> {
                String auth = ex.getRequestHeaders().getFirst("Authorization");
                String uid = ex.getRequestHeaders().getFirst("X-User-Id");
                byte[] body = (name + (auth == null ? "" : " " + auth) + (uid == null ? "" : " uid=" + uid))
                        .getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
                ex.close();
            });
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
