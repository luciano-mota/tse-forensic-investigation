package br.com.tseforensic.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class AuditMathTest {

    @Test
    void calculatesPercentagePointVariationWithoutInferringVoteTransfer() {
        assertThat(AuditMath.percentagePointChange(40, 100, 60, 100))
                .isEqualByComparingTo(new BigDecimal("20.0000"));
    }

    @Test
    void zeroDenominatorDoesNotProduceNaN() {
        assertThat(AuditMath.percent(50, 0)).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
