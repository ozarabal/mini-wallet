package com.rafif.wallet.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.rafif.wallet.AbstractIntegrationTest;
import com.rafif.wallet.common.ApiException;
import com.rafif.wallet.common.ErrorCode;
import com.rafif.wallet.support.ConcurrentRunner;
import com.rafif.wallet.support.LedgerInvariants;
import com.rafif.wallet.support.TestUser;
import com.rafif.wallet.transfer.SystemWallets;
import com.rafif.wallet.transfer.TransferService;
import com.rafif.wallet.transfer.TransferTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * You can only look at, top up and spend from wallets you own. Anyone may pay INTO any wallet.
 * Every refusal must also leave the database completely untouched.
 */
class OwnershipIT extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransferService transferService;

    TransferTestSupport db;
    TestUser alice;
    TestUser bob;
    UUID aliceWallet;
    UUID bobWallet;

    @BeforeEach
    void setUp() {
        db = new TransferTestSupport(jdbc);
        alice = newUser();
        bob = newUser();
        aliceWallet = db.wallet(alice.id().toString(), 1_234);
        bobWallet = db.wallet(bob.id().toString(), 500);
    }

    private ResponseEntity<JsonNode> transfer(TestUser who, String key, UUID from, UUID to, long amount) {
        HttpHeaders headers = who.headers();
        headers.set("Idempotency-Key", key);
        return who.post(rest, "/api/v1/transfers", Map.of("fromWalletId", from, "toWalletId", to, "amount", amount),
                headers, JsonNode.class);
    }

    private ResponseEntity<JsonNode> topUp(TestUser who, UUID wallet, long amount) {
        HttpHeaders headers = who.headers();
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return who.post(rest, "/api/v1/wallets/" + wallet + "/topups", Map.of("amount", amount), headers,
                JsonNode.class);
    }

    private static void assertNotTheOwner(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code").asText()).isEqualTo("NOT_WALLET_OWNER");
    }

    // ---- reading -------------------------------------------------------------------------------------------------

    @Test
    void theOwnerCanSeeTheirWalletAndItsHistory() {
        assertThat(alice.get(rest, "/api/v1/wallets/" + aliceWallet, JsonNode.class).getBody().get("balance").asLong())
                .isEqualTo(1_234);
        assertThat(alice.get(rest, "/api/v1/wallets/" + aliceWallet + "/transactions", JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anotherUserCannotSeeTheBalanceAndTheAnswerLeaksNothing() {
        ResponseEntity<JsonNode> response = bob.get(rest, "/api/v1/wallets/" + aliceWallet, JsonNode.class);

        assertNotTheOwner(response);
        assertThat(response.getBody().toString()).doesNotContain("1234").doesNotContain("balance");
    }

    @Test
    void anotherUserCannotSeeTheHistory() {
        transferService.transfer(alice.id(), UUID.randomUUID().toString(), aliceWallet, bobWallet, 10);

        assertNotTheOwner(bob.get(rest, "/api/v1/wallets/" + aliceWallet + "/transactions", JsonNode.class));
    }

    // ---- writing -------------------------------------------------------------------------------------------------

    @Test
    void anotherUserCannotTopUpMyWalletAndNothingIsWritten() {
        ResponseEntity<JsonNode> response = topUp(bob, aliceWallet, 100);

        assertNotTheOwner(response);
        assertThat(db.balance(aliceWallet)).isEqualTo(1_234);
        assertThat(db.ledgerRows(aliceWallet)).isZero();
        assertThat(db.transactionRows(alice.id().toString())).isZero();
        assertThat(db.transactionRows(bob.id().toString())).isZero();
    }

    @Test
    void anotherUserCannotSpendMyMoneyAndNothingIsWritten() {
        ResponseEntity<JsonNode> response = transfer(bob, UUID.randomUUID().toString(), aliceWallet, bobWallet, 100);

        assertNotTheOwner(response);
        assertThat(db.balance(aliceWallet)).isEqualTo(1_234);
        assertThat(db.balance(bobWallet)).isEqualTo(500);
        assertThat(db.ledgerRows(aliceWallet)).isZero();
        assertThat(db.ledgerRows(bobWallet)).isZero();
        assertThat(db.transactionRows(alice.id().toString())).isZero();
        assertThat(db.transactionRows(bob.id().toString())).isZero();
    }

    @Test
    void payingIntoAnotherUsersWalletIsAllowed() {
        ResponseEntity<JsonNode> response = transfer(bob, UUID.randomUUID().toString(), bobWallet, aliceWallet, 200);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(db.balance(aliceWallet)).isEqualTo(1_434);
        assertThat(db.balance(bobWallet)).isEqualTo(300);
    }

    @Test
    void createdWalletsBelongToTheirCreatorAndToNobodyElse() {
        UUID created = alice.post(rest, "/api/v1/wallets", null, WalletResponse.class).getBody().id();

        assertThat(alice.get(rest, "/api/v1/wallets/" + created, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertNotTheOwner(bob.get(rest, "/api/v1/wallets/" + created, JsonNode.class));
    }

    // ---- the system cash-in wallets ------------------------------------------------------------------------------

    /** Without the ownership check, anyone could "transfer" money out of a cash-in wallet to themselves. */
    @Test
    void nobodyCanTakeMoneyOutOfACashInWallet() {
        for (UUID cashIn : SystemWallets.CASH_IN_IDS) {
            long before = db.balance(cashIn);

            ResponseEntity<JsonNode> response = transfer(alice, UUID.randomUUID().toString(), cashIn, aliceWallet, 1_000);

            assertNotTheOwner(response);
            assertThat(db.balance(cashIn)).as(cashIn.toString()).isEqualTo(before);
        }
        assertThat(db.balance(aliceWallet)).isEqualTo(1_234);
        assertThat(db.ledgerRows(aliceWallet)).isZero();
    }

    @Test
    void nobodyCanReadACashInWallet() {
        for (UUID cashIn : SystemWallets.CASH_IN_IDS) {
            assertNotTheOwner(alice.get(rest, "/api/v1/wallets/" + cashIn, JsonNode.class));
            assertNotTheOwner(alice.get(rest, "/api/v1/wallets/" + cashIn + "/transactions", JsonNode.class));
        }
    }

    // ---- ownership and idempotency -------------------------------------------------------------------------------

    @Test
    void someoneElsesIdempotencyKeyCannotRevealMyTransfer() {
        String key = UUID.randomUUID().toString();
        assertThat(transfer(alice, key, aliceWallet, bobWallet, 100).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Bob replays Alice's exact request with Alice's key. He must NOT get Alice's stored result (200).
        ResponseEntity<JsonNode> response = transfer(bob, key, aliceWallet, bobWallet, 100);

        assertNotTheOwner(response);
        assertThat(response.getBody().has("transactionId")).isFalse();
    }

    @Test
    void theSameKeyUsedByTwoUsersIsIndependentPerUser() {
        String key = "both-of-us-picked-this-key";

        ResponseEntity<JsonNode> fromAlice = transfer(alice, key, aliceWallet, bobWallet, 100);
        ResponseEntity<JsonNode> fromBob = transfer(bob, key, bobWallet, aliceWallet, 50);

        assertThat(fromAlice.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(fromBob.getStatusCode()).isEqualTo(HttpStatus.CREATED); // not a "key reused" error: separate scopes
        assertThat(fromAlice.getBody().get("transactionId")).isNotEqualTo(fromBob.getBody().get("transactionId"));

        // and each user's retry gets their own result back
        ResponseEntity<JsonNode> aliceRetry = transfer(alice, key, aliceWallet, bobWallet, 100);
        assertThat(aliceRetry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(aliceRetry.getBody().get("transactionId")).isEqualTo(fromAlice.getBody().get("transactionId"));
    }

    // ---- what is revealed, and in what order --------------------------------------------------------------------

    @Test
    void anUnknownSourceWalletIs404WhileSomeoneElsesWalletIs403() {
        assertThat(transfer(bob, UUID.randomUUID().toString(), UUID.randomUUID(), bobWallet, 1).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(transfer(bob, UUID.randomUUID().toString(), aliceWallet, bobWallet, 1).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aSelfTransferIsRejectedBeforeOwnershipIsLookedAt() {
        // Bob "transfers" from Alice's wallet to itself: 400, so a self-transfer cannot be used to probe wallets.
        ResponseEntity<JsonNode> response = transfer(bob, UUID.randomUUID().toString(), aliceWallet, aliceWallet, 1);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("SELF_TRANSFER");
    }

    // ---- under concurrency ---------------------------------------------------------------------------------------

    /**
     * Alice and Bob pay each other while Carol hammers both wallets trying to drain them, all at the same moment.
     * Every one of Carol's attempts must be refused, and the legitimate transfers must be unaffected.
     */
    @Test
    void thievesRacingTheOwnersChangeNothing() throws Exception {
        TestUser carol = newUser();
        UUID carolWallet = db.wallet(carol.id().toString(), 0);
        var invariants = new LedgerInvariants(jdbc);
        var start = invariants.snapshot(aliceWallet, bobWallet, carolWallet);

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tasks.add(() -> { // legitimate: alice pays bob
                transferService.transfer(alice.id(), UUID.randomUUID().toString(), aliceWallet, bobWallet, 10);
                return true;
            });
            tasks.add(() -> { // legitimate: bob pays alice
                transferService.transfer(bob.id(), UUID.randomUUID().toString(), bobWallet, aliceWallet, 10);
                return true;
            });
            for (UUID victim : new UUID[] {aliceWallet, bobWallet}) {
                tasks.add(() -> { // theft attempt by carol
                    try {
                        transferService.transfer(carol.id(), UUID.randomUUID().toString(), victim, carolWallet, 10);
                        return false; // it went through: that is the bug this test exists to catch
                    } catch (ApiException e) {
                        assertThat(e.code()).isEqualTo(ErrorCode.NOT_WALLET_OWNER);
                        return true;
                    }
                });
            }
        }
        List<Boolean> results = ConcurrentRunner.runAll(tasks);

        assertThat(results).doesNotContain(false);
        assertThat(db.balance(aliceWallet)).isEqualTo(1_234); // 50 out, 50 in
        assertThat(db.balance(bobWallet)).isEqualTo(500);
        assertThat(db.balance(carolWallet)).isZero();
        invariants.assertHolds(start);
    }
}
