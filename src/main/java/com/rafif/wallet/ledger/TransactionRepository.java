package com.rafif.wallet.ledger;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByOwnerIdAndIdempotencyKey(String ownerId, String idempotencyKey);
}
