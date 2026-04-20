package com.finvanta.service.txn360;

import com.finvanta.repository.DepositTransactionRepository;
import com.finvanta.repository.LoanTransactionRepository;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * CBS Subledger transaction-ref resolver (TXN prefix) per Finacle TRAN_INQUIRY.
 */
@Component
@Order(20)
public class TransactionRefResolver implements TxnRefResolver {

    private final DepositTransactionRepository depositTxnRepository;
    private final LoanTransactionRepository loanTxnRepository;

    public TransactionRefResolver(
            DepositTransactionRepository depositTxnRepository,
            LoanTransactionRepository loanTxnRepository) {
        this.depositTxnRepository = depositTxnRepository;
        this.loanTxnRepository = loanTxnRepository;
    }

    @Override
    public String prefix() {
        return "TXN";
    }

    @Override
    public boolean supports(String reference) {
        return reference.startsWith("TXN");
    }

    @Override
    public TxnRefResolution resolve(String tenantId, String reference) {
        return depositTxnRepository
                .findByTenantIdAndTransactionRef(tenantId, reference)
                .map(TxnRefResolution::deposit)
                .or(() -> loanTxnRepository
                        .findByTenantIdAndTransactionRef(tenantId, reference)
                        .map(TxnRefResolution::loan))
                .orElseGet(TxnRefResolution::empty);
    }
}
