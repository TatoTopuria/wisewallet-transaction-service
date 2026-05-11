package com.wisewallet.transaction.domain.model.vo;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyTest {

    @Test
    void of_positiveAmount_returnsPositiveMoney() {
        var money = Money.of(new BigDecimal("100.00"));
        assertThat(money.isPositive()).isTrue();
        assertThat(money.isNegative()).isFalse();
        assertThat(money.value()).isEqualByComparingTo("100.00");
    }

    @Test
    void of_negativeAmount_returnsNegativeMoney() {
        var money = Money.of(new BigDecimal("-50.00"));
        assertThat(money.isNegative()).isTrue();
        assertThat(money.isPositive()).isFalse();
    }

    @Test
    void negate_flipSign() {
        var money = Money.of(new BigDecimal("75.00"));
        var negated = money.negate();
        assertThat(negated.value()).isEqualByComparingTo("-75.00");
    }

    @Test
    void add_twoMoneyValues_returnsSum() {
        var a = Money.of(new BigDecimal("100.00"));
        var b = Money.of(new BigDecimal("50.00"));
        assertThat(a.add(b).value()).isEqualByComparingTo("150.00");
    }

    @Test
    void add_positiveAndNegative_returnsCorrectSum() {
        var a = Money.of(new BigDecimal("100.00"));
        var b = Money.of(new BigDecimal("-30.00"));
        assertThat(a.add(b).value()).isEqualByComparingTo("70.00");
    }

    @Test
    void equality_sameValue_equal() {
        assertThat(Money.of(new BigDecimal("10.0000")))
                .isEqualTo(Money.of(new BigDecimal("10.0000")));
    }
}
