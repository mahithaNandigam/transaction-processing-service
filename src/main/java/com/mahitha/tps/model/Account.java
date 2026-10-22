package com.mahitha.tps.model;

import java.math.BigDecimal;

public record Account(String accountId, String currency, BigDecimal openingBalance,
                      BigDecimal balance, long startingSequence, long nextSequence) {}
