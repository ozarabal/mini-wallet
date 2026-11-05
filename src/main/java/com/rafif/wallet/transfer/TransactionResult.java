package com.rafif.wallet.transfer;

/**
 * Outcome of a top-up or transfer request.
 *
 * @param transaction what happened
 * @param replayed    true if this was a retry of an already-applied request (nothing new was written)
 */
public record TransactionResult(TransactionResponse transaction, boolean replayed) {
}
