package com.rafif.wallet.transfer;

import jakarta.validation.constraints.Positive;

/** Amount is in minor units (IDR has no decimals). */
public record TopUpRequest(@Positive long amount) {
}
