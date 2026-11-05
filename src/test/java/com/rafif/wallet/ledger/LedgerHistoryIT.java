package com.rafif.wallet.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.rafif.wallet.AbstractIntegrationTest;
import com.rafif.wallet.transfer.TopUpService;
import com.rafif.wallet.transfer.TransferService;
import com.rafif.wallet.transfer.TransferTestSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

class LedgerHistoryIT extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TopUpService topUpService;
    @Autowired
    TransferService transferService;

    TransferTestSupport db;

    @BeforeEach
    void setUp() {
        db = new TransferTestSupport(jdbc);
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private JsonNode history(UUID wallet, String query) {
        return rest.getForObject("/api/v1/wallets/" + wallet + "/transactions" + query, JsonNode.class);
    }

    /** alice: +1000 (top-up), -300 (to bob), +100 (from bob). */
    private UUID[] aliceAndBob() {
        UUID alice = db.wallet("hist-alice", 0);
        UUID bob = db.wallet("hist-bob", 0);
        topUpService.topUp(key(), alice, 1_000);
        transferService.transfer(key(), alice, bob, 300);
        transferService.transfer(key(), bob, alice, 100);
        return new UUID[] {alice, bob};
    }

    @Test
    void historyIsNewestFirstWithDirectionTypeAndBalanceAfter() {
        UUID alice = aliceAndBob()[0];

        JsonNode page = history(alice, "");

        assertThat(page.get("totalElements").asInt()).isEqualTo(3);
        JsonNode newest = page.get("content").get(0);
        JsonNode middle = page.get("content").get(1);
        JsonNode oldest = page.get("content").get(2);

        assertThat(newest.get("direction").asText()).isEqualTo("CREDIT");
        assertThat(newest.get("type").asText()).isEqualTo("TRANSFER");
        assertThat(newest.get("amount").asLong()).isEqualTo(100);
        assertThat(newest.get("balanceAfter").asLong()).isEqualTo(800);

        assertThat(middle.get("direction").asText()).isEqualTo("DEBIT");
        assertThat(middle.get("amount").asLong()).isEqualTo(300);
        assertThat(middle.get("balanceAfter").asLong()).isEqualTo(700);

        assertThat(oldest.get("type").asText()).isEqualTo("TOPUP");
        assertThat(oldest.get("direction").asText()).isEqualTo("CREDIT");
        assertThat(oldest.get("balanceAfter").asLong()).isEqualTo(1_000);

        // the newest entry always agrees with the wallet's current balance
        assertThat(newest.get("balanceAfter").asLong()).isEqualTo(db.balance(alice));
    }

    @Test
    void historyOnlyContainsEntriesOfThatWallet() {
        UUID bob = aliceAndBob()[1];

        JsonNode page = history(bob, "");

        assertThat(page.get("totalElements").asInt()).isEqualTo(2); // +300 and -100, not alice's top-up
    }

    @Test
    void pagingSplitsEntriesWithoutOverlap() {
        UUID alice = aliceAndBob()[0];

        JsonNode first = history(alice, "?page=0&size=2");
        JsonNode second = history(alice, "?page=1&size=2");

        assertThat(first.get("content")).hasSize(2);
        assertThat(second.get("content")).hasSize(1);
        assertThat(first.get("totalElements").asInt()).isEqualTo(3);
        assertThat(first.get("totalPages").asInt()).isEqualTo(2);
        assertThat(second.get("page").asInt()).isEqualTo(1);
        assertThat(second.get("content").get(0).get("type").asText()).isEqualTo("TOPUP"); // the oldest one
    }

    @Test
    void walletWithoutEntriesHasEmptyHistory() {
        UUID empty = db.wallet("hist-empty", 0);

        JsonNode page = history(empty, "");

        assertThat(page.get("content")).isEmpty();
        assertThat(page.get("totalElements").asInt()).isZero();
    }

    @Test
    void unknownWalletReturns404() {
        ResponseEntity<Map> response =
                rest.getForEntity("/api/v1/wallets/" + UUID.randomUUID() + "/transactions", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("code", "WALLET_NOT_FOUND");
    }

    @Test
    void invalidPagingParametersReturn400() {
        UUID wallet = db.wallet("hist-bad-paging", 0);

        for (String query : new String[] {"?size=0", "?size=101", "?page=-1"}) {
            ResponseEntity<Map> response =
                    rest.getForEntity("/api/v1/wallets/" + wallet + "/transactions" + query, Map.class);
            assertThat(response.getStatusCode()).as(query).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsEntry("code", "VALIDATION_ERROR");
        }
    }
}
