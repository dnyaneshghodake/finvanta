package com.finvanta.domain.enums;

/**
 * CBS User Role per RBI IT Governance Direction 2023 §8.3.
 *
 * <p>Role hierarchy (least → most privilege for transactional roles):
 * <pre>
 *   TELLER  &lt; MAKER  &lt; CHECKER  &lt; ADMIN
 * </pre>
 *
 * <ul>
 *   <li>{@code TELLER} -- over-the-counter cash channel only (CBS TELLER module:
 *       open till, cash deposit, cash withdrawal, vault movements). Per RBI
 *       Internal Controls the teller is a specialization of MAKER restricted
 *       to the cash counter; it has its own transaction limits
 *       (typically lower than MAKER because it is amount-sensitive tellerage).</li>
 *   <li>{@code MAKER} -- initiator of loan / deposit / clearing operations.</li>
 *   <li>{@code CHECKER} -- verifier/approver. Maker-checker separation per
 *       RBI Circular on Internal Controls.</li>
 *   <li>{@code ADMIN} -- branch manager / system admin. Full access;
 *       self-approval is blocked by the workflow engine, not the enum.</li>
 *   <li>{@code AUDITOR} -- read-only audit trail access. Excluded from the
 *       transactional role hierarchy by design (see {@code SecurityUtil}).</li>
 * </ul>
 */
public enum UserRole {
    TELLER,
    MAKER,
    CHECKER,
    ADMIN,
    AUDITOR
}
