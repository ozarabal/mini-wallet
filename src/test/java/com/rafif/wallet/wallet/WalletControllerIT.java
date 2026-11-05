package com.rafif.wallet.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.rafif.wallet.AbstractIntegrationTest;
import com.rafif.wallet.support.TestUser;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class WalletControllerIT extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    TestUser user;

    @BeforeEach
    void setUp() {
        user = newUser();
    }

    @Test
    void createWalletStartsWithZeroBalance() {
        ResponseEntity<WalletResponse> response =
                user.post(rest, "/api/v1/wallets", Map.of("ownerId", "alice"), WalletResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().id()).isNotNull();
        assertThat(response.getBody().ownerId()).isEqualTo("alice");
        assertThat(response.getBody().currency()).isEqualTo("IDR");
        assertThat(response.getBody().balance()).isZero();
    }

    @Test
    void getWalletReturnsWhatWasCreated() {
        WalletResponse created =
                user.post(rest, "/api/v1/wallets", Map.of("ownerId", "bob"), WalletResponse.class).getBody();

        ResponseEntity<WalletResponse> response =
                user.get(rest, "/api/v1/wallets/" + created.id(), WalletResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().id()).isEqualTo(created.id());
    }

    @Test
    void unknownWalletReturns404WithStableErrorCode() {
        ResponseEntity<Map> response = user.get(rest, "/api/v1/wallets/" + UUID.randomUUID(), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("code", "WALLET_NOT_FOUND");
    }

    @Test
    void blankOwnerReturns400ValidationError() {
        ResponseEntity<Map> response = user.post(rest, "/api/v1/wallets", Map.of("ownerId", " "), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "VALIDATION_ERROR");
    }

    @Test
    void malformedWalletIdReturns400() {
        ResponseEntity<Map> response = user.get(rest, "/api/v1/wallets/not-a-uuid", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "VALIDATION_ERROR");
    }
}
