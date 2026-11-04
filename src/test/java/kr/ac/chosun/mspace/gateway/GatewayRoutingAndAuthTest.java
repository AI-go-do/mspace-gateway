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
    @DisplayName("health는 토큰 없이 열린다")
    void health() throws Exception {
        assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);
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

    static HttpServer fake(String name) {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", ex -> {
                String auth = ex.getRequestHeaders().getFirst("Authorization");
                byte[] body = (name + (auth == null ? "" : " " + auth)).getBytes(StandardCharsets.UTF_8);
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
