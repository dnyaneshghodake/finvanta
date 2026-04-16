package com.finvanta.service.txn360;

import com.finvanta.domain.entity.DepositTransaction;
import com.finvanta.domain.entity.JournalEntry;
import com.finvanta.domain.entity.LoanTransaction;

/**
 * CBS Transaction-360 Reference Resolver per Finacle TRAN_INQUIRY.
 *
 * <p>One strategy per reference prefix. The registry ({@link TxnRefResolverRegistry})
 * asks each strategy whether it can handle the presented reference; the first one
 * that returns {@code true} from {@link #supports(String)} is invoked. A
 * fallback strategy (prefix-less) handles references that do not match any known
 * prefix by probing every lookup path in order.
 *
 * <p>Replaces the pre-refactor {@code if (ref.startsWith("VCH")) ... else if
 * (ref.startsWith("TXN")) ...} chain in {@code Txn360Controller.search} with a
 * single, open-for-extension interface so new reference families (e.g.
 * {@code CLR} clearing ref, {@code IB} inter-branch ref) can be plugged in
 * without touching the controller.
 */
public interface TxnRefResolver {

    /**
     * @return the prefix this resolver claims -- used only for diagnostics / logging.
     *         The actual matching decision is made by {@link #supports(String)}.
     */
    String prefix();

    /**
     * @param reference Trimmed, non-blank reference string.
     * @return {@code true} if this resolver wants to handle the reference.
     */
    boolean supports(String reference);

    /**
     * Resolve the reference for the current tenant. Must return a {@link TxnRefResolution}
     * with at most one populated subledger and / or one journal entry. An empty
     * resolution (all fields null) means the reference was recognised as this
     * resolver's family but not found in the database.
     */
    TxnRefResolution resolve(String tenantId, String reference);

    /**
     * Value object returned from {@link #resolve(String, String)}. Kept package-public
     * so each strategy implementation can construct one; the controller treats it as
     * opaque beyond reading the fields.
     *
     * @param depositTxn   CASA subledger hit, or {@code null}.
     * @param loanTxn      Loan subledger hit, or {@code null}.
     * @param journalEntry GL journal hit, or {@code null}.
     * @param sourceModule {@code "DEPOSIT"}, {@code "LOAN"}, {@code "GL"}, or {@code null}
     *                      if nothing was found. Used by the JSP to label the resolved origin.
     */
    record TxnRefResolution(
            DepositTransaction depositTxn,
            LoanTransaction loanTxn,
            JournalEntry journalEntry,
            String sourceModule) {

        public static TxnRefResolution empty() {
            return new TxnRefResolution(null, null, null, null);
        }

        public static TxnRefResolution deposit(DepositTransaction d) {
            return new TxnRefResolution(d, null, null, "DEPOSIT");
        }

        public static TxnRefResolution loan(LoanTransaction l) {
            return new TxnRefResolution(null, l, null, "LOAN");
        }

        public static TxnRefResolution journal(JournalEntry j) {
            return new TxnRefResolution(null, null, j, "GL");
        }

        public boolean isEmpty() {
            return depositTxn == null && loanTxn == null && journalEntry == null;
        }
    }
}
