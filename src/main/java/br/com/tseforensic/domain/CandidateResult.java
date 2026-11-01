package br.com.tseforensic.domain;

import java.math.BigDecimal;

public record CandidateResult(
        int number,
        String name,
        long votes,
        BigDecimal percentage
) {
}
