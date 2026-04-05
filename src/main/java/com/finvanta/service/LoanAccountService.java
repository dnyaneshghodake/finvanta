package com.finvanta.service;

import com.finvanta.domain.entity.LoanAccount;
import com.finvanta.domain.entity.LoanTransaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface LoanAccountService {

    LoanAccount createLoanAccount(Long applicationId);

    LoanAccount disburseLoan(String accountNumber);

    LoanTransaction applyInterestAccrual(String accountNumber, LocalDate accrualDate);

    LoanTransaction processRepayment(String accountNumber, BigDecimal amount, LocalDate valueDate);

    void classifyNPA(String accountNumber);

    LoanAccount getAccount(String accountNumber);

    List<LoanAccount> getActiveAccounts();
}
