package com.rafif.wallet.outbox;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /** The oldest events that have not been sent yet. The partial index idx_outbox_unpublished serves this query. */
    @Query("SELECT e FROM OutboxEvent e WHERE e.publishedAt IS NULL ORDER BY e.id")
    List<OutboxEvent> findOldestUnpublished(Pageable page);

    /**
     * Tries to become THE publisher. Returns false at once if another transaction already is.
     * The lock belongs to the current transaction and is released automatically when it ends (commit, rollback or
     * crash). 4242001 is an arbitrary number that only has to be the same in every instance.
     */
    @Query(value = "SELECT pg_try_advisory_xact_lock(4242001)", nativeQuery = true)
    boolean tryAcquirePublisherLock();
}
