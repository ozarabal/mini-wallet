package com.rafif.wallet.ledger;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByTransactionId(UUID transactionId);

    /**
     * Newest first. The order is fixed in the query (with the id as tie-breaker) so that paging is stable:
     * the same entry never shows up on two pages. Uses index (wallet_id, created_at DESC).
     */
    @Query(value = """
            select new com.rafif.wallet.ledger.LedgerEntryResponse(
                e.id, e.transactionId, t.type, e.direction, e.amount, e.balanceAfter, e.createdAt)
            from LedgerEntry e join Transaction t on t.id = e.transactionId
            where e.walletId = :walletId
            order by e.createdAt desc, e.id desc
            """,
            countQuery = "select count(e) from LedgerEntry e where e.walletId = :walletId")
    Page<LedgerEntryResponse> findHistory(@Param("walletId") UUID walletId, Pageable pageable);
}
