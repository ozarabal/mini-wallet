package com.rafif.wallet.transfer;

import com.rafif.wallet.common.ApiException;
import com.rafif.wallet.common.ErrorCode;
import com.rafif.wallet.ledger.LedgerEntryRepository;
import com.rafif.wallet.ledger.TransactionRepository;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Service;

/**
 * Makes a money operation safe to retry: the same (owner, Idempotency-Key) is applied at most once.
 * <p>
 * This class is NOT transactional on purpose. The action runs in its own database transaction, and when
 * two requests race, the loser's transaction fails on the unique constraint. PostgreSQL marks a failed
 * transaction as aborted, so it cannot be used for anything else; we must catch the error outside it
 * and read the winner's committed row in a fresh transaction.
 * <p>
 * The database unique constraint (owner_id, idempotency_key) is what guarantees "exactly one wins".
 * The lookup before running is only a fast path for ordinary retries.
 */
@Service
public class IdempotencyService {

    static final String UNIQUE_CONSTRAINT = "uq_transactions_owner_key";

    private final TransactionRepository transactions;
    private final LedgerEntryRepository ledgerEntries;

    public IdempotencyService(TransactionRepository transactions, LedgerEntryRepository ledgerEntries) {
        this.transactions = transactions;
        this.ledgerEntries = ledgerEntries;
    }

    TransactionResult run(String ownerId, String key, String requestHash, Supplier<TransactionResponse> action) {
        // 1. Fast path: this key was already used and committed.
        Optional<TransactionResult> previous = replay(ownerId, key, requestHash);
        if (previous.isPresent()) {
            return previous.get();
        }

        // 2. First time we see the key: apply it. If a concurrent request with the same key wins the race,
        //    the unique constraint rejects us here.
        try {
            return new TransactionResult(action.get(), false);
        } catch (DataIntegrityViolationException e) {
            if (!isIdempotencyConflict(e)) {
                throw e; // some other integrity problem: not ours to hide
            }
            // 3. We lost the race. The winner has committed, so read its result (in a new transaction).
            return replay(ownerId, key, requestHash).orElseThrow(() -> e);
        }
    }

    private Optional<TransactionResult> replay(String ownerId, String key, String requestHash) {
        return transactions.findByOwnerIdAndIdempotencyKey(ownerId, key).map(existing -> {
            if (!existing.getRequestHash().equals(requestHash)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "Idempotency-Key '" + key + "' was already used with a different request");
            }
            var entries = ledgerEntries.findByTransactionId(existing.getId());
            return new TransactionResult(TransactionResponse.fromStored(existing, entries), true);
        });
    }

    private static boolean isIdempotencyConflict(DataIntegrityViolationException e) {
        String message = NestedExceptionUtils.getMostSpecificCause(e).getMessage();
        return message != null && message.contains(UNIQUE_CONSTRAINT);
    }
}
