package com.finvanta.cbs.modules.teller.mapper;

import com.finvanta.cbs.modules.teller.domain.TellerCashMovement;
import com.finvanta.cbs.modules.teller.domain.VaultPosition;
import com.finvanta.cbs.modules.teller.dto.response.TellerCashMovementResponse;
import com.finvanta.cbs.modules.teller.dto.response.VaultPositionResponse;

import org.springframework.stereotype.Component;

/**
 * CBS Vault Mapper per Tier-1 DTO isolation standards.
 *
 * <p>Centralizes entity-to-DTO conversion for {@link VaultPosition} and
 * {@link TellerCashMovement} so the controller layer never sees the JPA
 * entity directly. Mirrors {@code TellerTillMapper} for the till entity.
 *
 * <p>Both methods dereference the lazy {@code branch} relation to project
 * {@code branchName}; callers MUST invoke this from inside the
 * {@code @Transactional} boundary that loaded the entity, OR pass an entity
 * loaded via a {@code JOIN FETCH branch} repository query (per the
 * OSIV-disabled discipline this PR established).
 *
 * <p>Stateless and thread-safe -- one instance per Spring context.
 */
@Component
public class VaultMapper {

    /**
     * Maps {@link VaultPosition} -> {@link VaultPositionResponse}. Returns null
     * on null input so callers can pass through without a guard.
     */
    public VaultPositionResponse toResponse(VaultPosition entity) {
        if (entity == null) {
            return null;
        }
        String branchName = entity.getBranch() != null ? entity.getBranch().getBranchName() : null;
        return new VaultPositionResponse(
                entity.getId(),
                entity.getBranchCode(),
                branchName,
                entity.getBusinessDate(),
                entity.getStatus(),
                entity.getOpeningBalance(),
                entity.getCurrentBalance(),
                entity.getCountedBalance(),
                entity.getVarianceAmount(),
                entity.getOpenedBy(),
                entity.getClosedBy(),
                entity.getRemarks()
        );
    }

    /**
     * Maps {@link TellerCashMovement} -> {@link TellerCashMovementResponse}.
     * Returns null on null input.
     */
    public TellerCashMovementResponse toResponse(TellerCashMovement entity) {
        if (entity == null) {
            return null;
        }
        return new TellerCashMovementResponse(
                entity.getId(),
                entity.getMovementRef(),
                entity.getMovementType(),
                entity.getBranchCode(),
                entity.getTillId(),
                entity.getVaultId(),
                entity.getBusinessDate(),
                entity.getAmount(),
                entity.getStatus(),
                entity.getRequestedBy(),
                entity.getRequestedAt(),
                entity.getApprovedBy(),
                entity.getApprovedAt(),
                entity.getRejectionReason(),
                entity.getRemarks()
        );
    }
}
