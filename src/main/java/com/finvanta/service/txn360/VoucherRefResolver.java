package com.finvanta.service.txn360;

import com.finvanta.domain.entity.DepositTransaction;
import com.finvanta.domain.entity.LoanTransaction;
import com.finvanta.repository.DepositTransactionRepository;
import com.finvanta.repository.LoanTransactionRepository;

import java.util.List;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * CBS Voucher-number resolver (VCH prefix) per Finacle VCH_INQUIRY.
 *
 * <p>Vouchers belong to the branch daily book of record and can be shared
 * between the two legs of a fund transfer. We return the first matching
 * subledger leg; the JSP follows the journal link to render both sides.
 */
@Component
@Order(10)
public class VoucherRefResolver implements TxnRefResolver {

    private final DepositTransactionRepository depositTxnRepository;
    private final LoanTransactionRepository loanTxnRepository;

    public VoucherRefResolver(
            DepositTransactionRepository depositTxnRepository,
            LoanTransactionRepository loanTxnRepository) {
        this.depositTxnRepository = depositTxnRepository;
        this.loanTxnRepository = loanTxnRepository;
    }

    @Override
    public String prefix() {
        return "VCH";
    }

    @Override
    public boolean supports(String reference) {
        return reference.startsWith("VCH");
    }

    @Override
    public TxnRefResolution resolve(String tenantId, String reference) {
        List<DepositTransaction> deposits = depositTxnRepository
                .findByTenantIdAndVoucherNumber(tenantId, reference);
        if (!deposits.isEmpty()) {
            return TxnRefResolution.deposit(deposits.get(0));
        }
        return loanTxnRepository
                .findByTenantIdAndVoucherNumber(tenantId, reference)
                .map(TxnRefResolution::loan)
                .orElseGet(TxnRefResolution::empty);
    }
}
