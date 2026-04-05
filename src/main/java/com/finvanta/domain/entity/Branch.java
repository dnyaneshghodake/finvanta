package com.finvanta.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "branches", indexes = {
    @Index(name = "idx_branch_tenant_code", columnList = "tenant_id, branch_code", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
public class Branch extends BaseEntity {

    @Column(name = "branch_code", nullable = false, length = 20)
    private String branchCode;

    @Column(name = "branch_name", nullable = false, length = 200)
    private String branchName;

    @Column(name = "ifsc_code", length = 11)
    private String ifscCode;

    @Column(name = "address", length = 500)
    private String address;

    @Column(name = "city", length = 100)
    private String city;

    @Column(name = "state", length = 100)
    private String state;

    @Column(name = "pin_code", length = 6)
    private String pinCode;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "region", length = 100)
    private String region;
}
