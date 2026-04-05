package com.finvanta.domain.enums;

public enum LoanStatus {
    ACTIVE,
    CLOSED,
    NPA_SUBSTANDARD,
    NPA_DOUBTFUL,
    NPA_LOSS,
    WRITTEN_OFF,
    RESTRUCTURED,
    OVERDUE;

    public boolean isNpa() {
        return this == NPA_SUBSTANDARD || this == NPA_DOUBTFUL || this == NPA_LOSS;
    }

    public boolean isClosed() {
        return this == CLOSED;
    }

    public boolean isActive() {
        return this == ACTIVE;
    }
}
