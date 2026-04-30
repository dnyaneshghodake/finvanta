package com.finvanta.cbs.modules.teller.mapper;

import com.finvanta.cbs.modules.teller.domain.TellerTill;
import com.finvanta.cbs.modules.teller.dto.response.TellerTillResponse;

import org.springframework.stereotype.Component;

/**
 * CBS Teller Till Mapper per Tier-1 DTO isolation standards.
 *
 * <p>Centralizes the entity-to-DTO conversion for till responses so the
 * controller layer never sees the JPA entity directly (the C3 finding from
 * {@code CBS_TIER1_AUDIT_REPORT.md}). The till entity does not carry PII,
 * but this mapper is also the right place to apply any future masking or
 * redaction without touching the controller.
 *
 * <p>Stateless and thread-safe -- one instance per Spring context.
 */
@Component
public class TellerTillMapper {

    /**
     * Maps {@link TellerTill} -> {@link TellerTillResponse}. Returns null on
     * null input so callers can pass through without a guard.
     *
     * <p>The {@code branchName} dereferences the lazy {@code branch} relation;
     * callers MUST invoke this from inside the same {@code @Transactional}
     * boundary that loaded the entity, OR use the eager-fetched repository
     * helper {@code findAndLockByTellerAndDate} / {@code findByTellerAndDate}
     * which JOIN FETCH the branch (per the OSIV-disabled discipline this PR
     * established for the v2 module). Otherwise expect a
     * LazyInitializationException at mapping time.
     */
    public TellerTillResponse toResponse(TellerTill entity) {
        if (entity == null) {
            return null;
        }
        String branchName = entity.getBranch() != null ? entity.getBranch().getBranchName() : null;
        return new TellerTillResponse(
                entity.getId(),
                entity.getTellerUserId(),
                entity.getBranchCode(),
                branchName,
                entity.getBusinessDate(),
                entity.getStatus(),
                entity.getOpeningBalance(),
                entity.getCurrentBalance(),
                entity.getCountedBalance(),
                entity.getVarianceAmount(),
                entity.getTillCashLimit(),
                entity.getOpenedAt(),
                entity.getClosedAt(),
                entity.getOpenedBySupervisor(),
                entity.getClosedBySupervisor(),
                entity.getRemarks()
        );
    }
}
