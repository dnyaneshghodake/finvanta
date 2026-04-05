package com.finvanta.domain.entity;

import com.finvanta.domain.enums.GLAccountType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "gl_master", indexes = {
    @Index(name = "idx_gl_tenant_code", columnList = "tenant_id, gl_code", unique = true),
    @Index(name = "idx_gl_type", columnList = "tenant_id, account_type")
})
@Getter
@Setter
@NoArgsConstructor
public class GLMaster extends BaseEntity {

    @Column(name = "gl_code", nullable = false, length = 20)
    private String glCode;

    @Column(name = "gl_name", nullable = false, length = 200)
    private String glName;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private GLAccountType accountType;

    @Column(name = "parent_gl_code", length = 20)
    private String parentGlCode;

    @Column(name = "debit_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal debitBalance = BigDecimal.ZERO;

    @Column(name = "credit_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal creditBalance = BigDecimal.ZERO;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "is_header_account", nullable = false)
    private boolean headerAccount = false;

    @Column(name = "description", length = 500)
    private String description;

    public BigDecimal getNetBalance() {
        return debitBalance.subtract(creditBalance);
    }
}
