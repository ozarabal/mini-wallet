package com.rafif.wallet.ledger;

import com.rafif.wallet.common.ApiException;
import com.rafif.wallet.common.ErrorCode;
import com.rafif.wallet.common.PageResponse;
import com.rafif.wallet.wallet.WalletRepository;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerQueryService {

    private final LedgerEntryRepository entries;
    private final WalletRepository wallets;

    public LedgerQueryService(LedgerEntryRepository entries, WalletRepository wallets) {
        this.entries = entries;
        this.wallets = wallets;
    }

    @Transactional(readOnly = true)
    public PageResponse<LedgerEntryResponse> history(UUID walletId, int page, int size) {
        if (!wallets.existsById(walletId)) {
            throw new ApiException(ErrorCode.WALLET_NOT_FOUND, "Wallet " + walletId + " not found");
        }
        return PageResponse.from(entries.findHistory(walletId, PageRequest.of(page, size)));
    }
}
