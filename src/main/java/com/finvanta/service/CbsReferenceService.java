package com.finvanta.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * CBS Persistent Reference Number Service per Finacle SEQ_MASTER / Temenos EB.SEQUENCE.
 *
 * Generates globally unique, sequential, deterministic reference numbers for all
 * persistent CBS entities: Customer CIF, CASA Account, Loan Account, Loan Application,
 * and Collateral. These numbers are:
 *   - Printed on passbooks, cheque books, and demand drafts
 *   - Reported to CIBIL, CRILC, RBI OSMOS, and NEFT/RTGS payment networks
 *   - Quoted by customers over phone and at branch counters
 *   - Used in inter-bank communication (IFSC + account number)
 *
 * Per Finacle/SBI/HDFC/ICICI standards: these numbers MUST be:
 *   1. SEQUENTIAL — no random gaps (RBI/CIBIL expects gap analysis)
 *   2. DETERMINISTIC — same starting point after restart (DB-backed)
 *   3. CLUSTER-SAFE — unique across multiple JVM instances (pessimistic lock)
 *   4. HUMAN-READABLE — short, typed at counters, spoken over phone
 *
 * Architecture:
 *   This service delegates to {@link SequenceGeneratorService} which uses DB-backed
 *   sequences with PESSIMISTIC_WRITE locking and REQUIRES_NEW propagation.
 *   Each entity type has a named sequence (e.g., "CIF_SEQ", "CASA_SEQ_BR001").
 *   Sequences start at 1 and increment monotonically — no random offsets.
 *
 * Replaces the deprecated static methods in {@link ReferenceGenerator} which used
 * in-memory AtomicLong seeded from System.nanoTime() — producing random starting
 * offsets on each JVM restart.
 *
 * Formats:
 *   CIF:     {SOL:3}{SERIAL:8}          → 00200000001  (11 pure digits, incremental)
 *   CASA:    {SB|CA}-{BRANCH}-{6-digit} → SB-BR001-000001
 *   Loan:    LN-{BRANCH}-{6-digit}      → LN-BR001-000045
 *   LoanApp: APP-{BRANCH}-{6-digit}     → APP-BR001-000045
 *   Collateral: COL-{6-digit}           → COL-000056
 *
 * KNOWN LIMITATION — Sequence Overflow at 1M:
 *   The {6-digit} format uses String.format("%06d", val) which is a MINIMUM width,
 *   not a maximum. After 999,999 allocations per sequence, the serial overflows to
 *   7+ digits (e.g., SB-BR001-1000000). This does NOT cause data loss — all reference
 *   columns are VARCHAR(40) with ample headroom. However, the documented 6-digit
 *   format contract is broken, which may affect:
 *     - External system parsing expecting fixed-width fields (NPCI, CIBIL, CRILC)
 *     - Regulatory reports with fixed-width column formats
 *     - Passbook printing with fixed character widths
 *
 *   Mitigation for 1M+ daily transaction banks:
 *     1. Replace SequenceGeneratorService with native DB sequences:
 *        CREATE SEQUENCE finvanta_casa_seq START WITH 1 INCREMENT BY 1 CACHE 1000;
 *     2. Use modular arithmetic with collision detection:
 *        serial = seq.nextValue() % 1000000 (wraps at 999999, DB unique constraint catches collisions)
 *     3. Increase format width to 8 digits (%08d) — requires column width review on external interfaces
 *
 *   Per Finacle SEQ_MASTER: production Finacle uses 10-digit account numbers with
 *   native Oracle SEQUENCE objects that never overflow within operational lifetime.
 */
@Service
public class CbsReferenceService {

    private static final Logger log = LoggerFactory.getLogger(CbsReferenceService.class);

    private final SequenceGeneratorService sequenceGenerator;

    public CbsReferenceService(SequenceGeneratorService sequenceGenerator) {
        this.sequenceGenerator = sequenceGenerator;
    }

    /**
     * Customer CIF Number — 11 pure digits, strictly incremental.
     *
     * Structure: {SOL_ID:3}{SERIAL:8} = 11 digits
     *   SOL_ID = 3-digit branch/SOL identifier (from DB branch ID, mod 1000)
     *   SERIAL = 8-digit sequential from DB sequence "CIF_SEQ" (starts at 1)
     *
     * Per Finacle/SBI/HDFC/ICICI: CIF is 11-digit pure numeric, strictly sequential.
     * The DB-backed sequence ensures clean incremental progression:
     *   CIF_SEQ=1 → 00200000001
     *   CIF_SEQ=2 → 00200000002
     *   CIF_SEQ=3 → 00200000003
     *   ...no gaps, no check digits, no jumps — survives JVM restarts.
     *
     * Per CBS standards: CIF numbers are quoted by customers over phone, typed at
     * branch counters, printed on passbooks/cheque books, and reported to CIBIL/CRILC.
     * A clean incremental sequence is easier to communicate and verify than one with
     * Luhn check digits that cause non-obvious jumps (e.g., 017 → 028 → 039).
     *
     * Capacity: 8-digit serial supports up to 99,999,999 customers per SOL prefix.
     * At 1,000 new customers/day, this lasts ~274 years per branch — well within
     * operational lifetime per Finacle SEQ_MASTER capacity planning.
     *
     * @param branchId Database branch ID (used as SOL prefix, mod 1000)
     * @return 11-digit CIF number, strictly incremental within the SOL prefix
     */
    public String generateCustomerNumber(Long branchId) {
        String sol = String.format("%03d", branchId != null ? branchId % 1000 : 0);
        long serial = sequenceGenerator.nextValue("CIF_SEQ");
        String serialStr = String.format("%08d", serial % 100000000);
        String cif = sol + serialStr; // 11 digits: 3 (SOL) + 8 (serial)
        log.debug("CIF generated: {} (branch={}, serial={})", cif, branchId, serial);
        return cif;
    }

    /**
     * CASA Deposit Account Number — {SB|CA}-{BRANCH}-{6-digit}.
     *
     * Uses branch-scoped DB sequence "CASA_SEQ_{branchCode}" so each branch
     * has its own sequential numbering starting from 1.
     * Per Finacle CUSTACCT: account numbers are branch-scoped.
     *
     * @param branchCode Branch code (e.g., "BR001")
     * @param isSavings  true for SB prefix, false for CA prefix
     * @return Account number like "SB-BR001-000001"
     */
    public String generateDepositAccountNumber(String branchCode, boolean isSavings) {
        String prefix = isSavings ? "SB" : "CA";
        String seqName = "CASA_SEQ_" + branchCode;
        String serial = sequenceGenerator.nextFormattedValue(seqName, 6);
        String accNo = prefix + "-" + branchCode + "-" + serial;
        log.debug("CASA account generated: {} (branch={}, seq={})", accNo, branchCode, serial);
        return accNo;
    }

    /**
     * Loan Account Number — LN-{BRANCH}-{6-digit}.
     *
     * @param branchCode Branch code (e.g., "BR001")
     * @return Loan account number like "LN-BR001-000045"
     */
    public String generateLoanAccountNumber(String branchCode) {
        String serial = sequenceGenerator.nextFormattedValue("LOAN_SEQ_" + branchCode, 6);
        return "LN-" + branchCode + "-" + serial;
    }

    /**
     * Loan Application Number — APP-{BRANCH}-{6-digit}.
     *
     * @param branchCode Branch code (e.g., "BR001")
     * @return Application number like "APP-BR001-000045"
     */
    public String generateApplicationNumber(String branchCode) {
        String serial = sequenceGenerator.nextFormattedValue("APP_SEQ_" + branchCode, 6);
        return "APP-" + branchCode + "-" + serial;
    }

    /**
     * Fixed Deposit Account Number — FD-{BRANCH}-{6-digit}.
     *
     * Uses branch-scoped DB sequence "FD_SEQ_{branchCode}" so each branch
     * has its own sequential numbering starting from 1.
     * Per Finacle TD_MASTER: FD numbers are branch-scoped and sequential.
     *
     * Replaces the previous System.currentTimeMillis() % 1000000 approach
     * which had collision risk under concurrent FD bookings.
     *
     * @param branchCode Branch code (e.g., "BR001")
     * @return FD account number like "FD-BR001-000001"
     */
    public String generateFdAccountNumber(String branchCode) {
        String serial = sequenceGenerator.nextFormattedValue("FD_SEQ_" + branchCode, 6);
        String fdNo = "FD-" + branchCode + "-" + serial;
        log.debug("FD account generated: {} (branch={}, seq={})", fdNo, branchCode, serial);
        return fdNo;
    }

    /**
     * Collateral Reference — COL-{6-digit}.
     *
     * @return Collateral reference like "COL-000056"
     */
    public String generateCollateralRef() {
        String serial = sequenceGenerator.nextFormattedValue("COL_SEQ", 6);
        return "COL-" + serial;
    }

    /**
     * FICN Register Reference per RBI Master Direction on Counterfeit Notes.
     *
     * <p>Format: {@code FICN/{branchCode}/{YYYYMMDD}/{seq}} -- e.g.
     * {@code FICN/BR001/20260401/000003}. The date segment is included
     * (unlike CASA / LOAN refs) because the FICN register is queried by
     * (branch, date) on the supervisor view, the chest-dispatch dashboard,
     * and the RBI quarterly FICN return; embedding the date in the
     * reference makes those queries cheap to filter and the printed
     * receipt instantly self-describing.
     *
     * <p>Sequence is per-branch (not per-branch-per-day) so the count
     * monotonically increases across business dates -- this matches the
     * RBI requirement that the register reference be permanent and
     * never rolled-over.
     *
     * <p>Per RBI: FICN register references appear on the customer
     * acknowledgement receipt, the FIR copy filed with police, and the
     * currency-chest dispatch envelope. Once minted, the reference is
     * immutable -- the underlying entity is INSERT-ONLY (DB triggers
     * {@code trg_ficn_no_update} / {@code trg_ficn_no_delete} enforce
     * this at the storage layer).
     *
     * @param branchCode    detecting branch's code (e.g. "BR001")
     * @param businessDate  CBS business date of detection (formatted YYYYMMDD)
     * @return FICN reference like "FICN/BR001/20260401/000003"
     */
    public String generateFicnRegisterRef(String branchCode, java.time.LocalDate businessDate) {
        String dateStr = businessDate.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        String serial = sequenceGenerator.nextFormattedValue("FICN_SEQ_" + branchCode, 6);
        String ref = "FICN/" + branchCode + "/" + dateStr + "/" + serial;
        log.debug("FICN register ref generated: {} (branch={}, date={}, seq={})",
                ref, branchCode, dateStr, serial);
        return ref;
    }
}
