package com.rafif.wallet.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.rafif.wallet.AbstractIntegrationTest;
import com.rafif.wallet.support.TestUser;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Who gets in and who does not. Bad tokens are made by hand with the Nimbus library (the same one Spring uses), so
 * each attack is spelled out in the test: forged signature, wrong key, expired, unsigned, wrong algorithm...
 */
class SecurityIT extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    TestUser user;
    String someWallet = "/api/v1/wallets/" + UUID.randomUUID();

    @BeforeEach
    void setUp() {
        user = newUser();
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    /** Signs a token exactly like the app does, but lets the test choose key, algorithm, issuer and expiry. */
    private static String signedToken(JWSAlgorithm algorithm, String secret, String issuer, UUID subject,
                                      Instant expiresAt) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(subject.toString())
                .issueTime(Date.from(expiresAt.minusSeconds(900)))
                .expirationTime(Date.from(expiresAt))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(algorithm), claims);
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private static String goodToken(UUID subject) throws Exception {
        return signedToken(JWSAlgorithm.HS256, TEST_JWT_SECRET, "mini-wallet", subject, Instant.now().plusSeconds(600));
    }

    private ResponseEntity<JsonNode> getWith(String authorizationHeader) {
        HttpHeaders headers = new HttpHeaders();
        if (authorizationHeader != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authorizationHeader);
        }
        return rest.exchange(someWallet, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> getWithBearer(String token) {
        return getWith("Bearer " + token);
    }

    private static void assertRejected(ResponseEntity<JsonNode> response, String expectedCode) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code").asText()).isEqualTo(expectedCode);
    }

    private static String replaceFirstCharOfSignature(String token) {
        String[] parts = token.split("\\.");
        char first = parts[2].charAt(0);
        String changed = (first == 'A' ? 'B' : 'A') + parts[2].substring(1);
        return parts[0] + "." + parts[1] + "." + changed;
    }

    // ---- no token ------------------------------------------------------------------------------------------------

    @Test
    void everyProtectedEndpointRejectsARequestWithoutAToken() {
        record Call(HttpMethod method, String url, Object body) {
        }
        UUID wallet = UUID.randomUUID();
        Call[] calls = {
                new Call(HttpMethod.GET, "/api/v1/wallets/" + wallet, null),
                new Call(HttpMethod.POST, "/api/v1/wallets", Map.of("ownerId", "x")),
                new Call(HttpMethod.POST, "/api/v1/transfers", Map.of("fromWalletId", wallet, "toWalletId", UUID.randomUUID(), "amount", 1)),
                new Call(HttpMethod.POST, "/api/v1/wallets/" + wallet + "/topups", Map.of("amount", 1)),
                new Call(HttpMethod.GET, "/api/v1/wallets/" + wallet + "/transactions", null),
        };

        for (Call call : calls) {
            ResponseEntity<JsonNode> response =
                    rest.exchange(call.url(), call.method(), new HttpEntity<>(call.body()), JsonNode.class);

            assertThat(response.getStatusCode()).as(call.url()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody().get("code").asText()).as(call.url()).isEqualTo("UNAUTHENTICATED");
        }
    }

    @Test
    void theRejectionIsAProperProblemDocumentWithABearerChallenge() {
        ResponseEntity<JsonNode> response = getWith(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        JsonNode body = response.getBody();
        assertThat(body.get("status").asInt()).isEqualTo(401);
        assertThat(body.get("title").asText()).isEqualTo("Authentication required");
        assertThat(body.get("code").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(body.get("instance").asText()).isEqualTo(someWallet);
    }

    @Test
    void authenticationIsCheckedBeforeTheRequestIsValidated() {
        // Invalid request (no Idempotency-Key, empty body) AND no token: the answer must be 401, never a 400 that
        // tells an anonymous caller how the API validates its input.
        ResponseEntity<JsonNode> response =
                rest.exchange("/api/v1/transfers", HttpMethod.POST, new HttpEntity<>(Map.of()), JsonNode.class);

        assertRejected(response, "UNAUTHENTICATED");
    }

    @Test
    void anAuthorizationHeaderThatIsNotBearerIsTreatedAsNoToken() {
        assertRejected(getWith("Basic dXNlcjpwYXNzd29yZA=="), "UNAUTHENTICATED");
    }

    // ---- a token that is sent but is bad ------------------------------------------------------------------------

    @Test
    void aValidTokenIsAccepted() {
        ResponseEntity<JsonNode> response = user.get(rest, someWallet, JsonNode.class);

        // got past security and reached the business code, which says this wallet does not exist
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code").asText()).isEqualTo("WALLET_NOT_FOUND");
    }

    @Test
    void theRejectionOfABadTokenDoesNotSayWhyAndAsksForANewOne() {
        ResponseEntity<JsonNode> response = getWithBearer("not-a-jwt");

        assertRejected(response, "TOKEN_INVALID");
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer error=\"invalid_token\"");
        assertThat(response.getBody().get("detail").asText()).isEqualTo("The access token is invalid or has expired");
    }

    @Test
    void aTokenWithATamperedPayloadIsRejected() throws Exception {
        // An attacker edits the claims (here: pretends to be another user) but cannot re-sign without the secret.
        String[] parts = goodToken(user.id()).split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String forged = payload.replace(user.id().toString(), UUID.randomUUID().toString());
        assertThat(forged).isNotEqualTo(payload);
        String token = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(forged.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];

        assertRejected(getWithBearer(token), "TOKEN_INVALID");
    }

    @Test
    void aTokenWithATamperedSignatureIsRejected() throws Exception {
        assertRejected(getWithBearer(replaceFirstCharOfSignature(goodToken(user.id()))), "TOKEN_INVALID");
    }

    @Test
    void aTokenSignedWithAnotherSecretIsRejected() throws Exception {
        String token = signedToken(JWSAlgorithm.HS256, "another-secret-another-secret-another-secret!!",
                "mini-wallet", user.id(), Instant.now().plusSeconds(600));

        assertRejected(getWithBearer(token), "TOKEN_INVALID");
    }

    @Test
    void anExpiredTokenIsRejected() throws Exception {
        String token = signedToken(JWSAlgorithm.HS256, TEST_JWT_SECRET, "mini-wallet", user.id(),
                Instant.now().minusSeconds(600));

        assertRejected(getWithBearer(token), "TOKEN_INVALID");
    }

    @Test
    void aTokenFromAnotherIssuerIsRejected() throws Exception {
        String token = signedToken(JWSAlgorithm.HS256, TEST_JWT_SECRET, "some-other-service", user.id(),
                Instant.now().plusSeconds(600));

        assertRejected(getWithBearer(token), "TOKEN_INVALID");
    }

    @Test
    void anUnsignedTokenWithAlgNoneIsRejected() {
        // The classic attack: claim "no signature needed". Valid claims, correct issuer, not expired.
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("mini-wallet").subject(user.id().toString())
                .expirationTime(Date.from(Instant.now().plusSeconds(600))).build();
        String unsigned = new PlainJWT(claims).serialize();
        assertThat(unsigned).endsWith("."); // no signature part at all

        assertRejected(getWithBearer(unsigned), "TOKEN_INVALID");
    }

    @Test
    void aTokenUsingADifferentAlgorithmIsRejectedEvenWithTheRightSecret() throws Exception {
        // Correct key, correct claims, but HS512 instead of HS256: the decoder accepts exactly one algorithm.
        String token = signedToken(JWSAlgorithm.HS512, TEST_JWT_SECRET, "mini-wallet", user.id(),
                Instant.now().plusSeconds(600));

        assertRejected(getWithBearer(token), "TOKEN_INVALID");
    }

    // ---- public endpoints ----------------------------------------------------------------------------------------

    @Test
    void publicEndpointsNeedNoToken() {
        assertThat(rest.getForEntity("/actuator/health", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity("/v3/api-docs", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity("/swagger-ui/index.html", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        // login is public: it is reached, and answers about the credentials (not "you need a token")
        ResponseEntity<JsonNode> login = rest.postForEntity("/api/v1/auth/login",
                Map.of("username", "nobody", "password", "whatever"), JsonNode.class);
        assertThat(login.getBody().get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
    }
}
