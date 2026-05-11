package com.wisewallet.transaction.domain.model.vo;

import jakarta.persistence.Embeddable;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Embeddable
public record Money(BigDecimal value) {

    static final int SCALE = 4;
    static final RoundingMode MODE = RoundingMode.HALF_UP;

    public Money {
        if (value == null) {
            throw new IllegalArgumentException("Money value must not be null");
        }
        value = value.setScale(SCALE, MODE);
    }

    public static Money of(BigDecimal value) {
        return new Money(value);
    }

    public static Money of(String value) {
        return new Money(new BigDecimal(value));
    }

    public Money negate() {
        return new Money(value.negate());
    }

    public Money add(Money other) {
        return new Money(value.add(other.value));
    }

    public boolean isNegative() {
        return value.signum() < 0;
    }

    public boolean isPositive() {
        return value.signum() > 0;
    }
}
