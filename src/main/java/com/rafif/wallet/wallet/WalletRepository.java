package com.rafif.wallet.wallet;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    /**
     * Locks all requested wallets with one {@code SELECT ... ORDER BY id FOR UPDATE}.
     * <p>
     * Every transfer acquires its row locks in ascending id order (the order is decided by the database,
     * so it is the same for every transaction). Two transfers can therefore never wait on each other in a
     * cycle, which is what prevents deadlock for A to B and B to A at the same time.
     * Must be called inside a transaction; locks are released at commit or rollback.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.id in :ids order by w.id")
    List<Wallet> findAllByIdForUpdate(@Param("ids") Collection<UUID> ids);
}
