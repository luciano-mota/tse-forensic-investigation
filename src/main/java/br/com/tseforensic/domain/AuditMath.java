package br.com.tseforensic.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class AuditMath {

    private AuditMath() {
    }

    public static BigDecimal percent(long numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    public static BigDecimal percentagePointChange(long firstVotes, long firstTotal,
                                                    long secondVotes, long secondTotal) {
        return percent(secondVotes, secondTotal).subtract(percent(firstVotes, firstTotal));
    }
}
