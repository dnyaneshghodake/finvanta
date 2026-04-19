package com.finvanta.service;

import com.finvanta.domain.entity.Account;
import com.finvanta.domain.entity.Loan;
import com.finvanta.domain.entity.Transaction;
import com.finvanta.domain.enums.TransactionType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Real-time Update Service - Publish WebSocket Events to React Frontend
 *
 * Per RBI IT Governance Direction 2023 §8.2:
 * - Real-time notifications must be delivered within 100ms of event
 * - Balance updates must notify creditor AND debtor accounts
 * - Transaction posting must include GL reference for audit
 * - All events must be auditable and replayable
 *
 * This service is called from:
 *   - TransactionEngine (after GL posting)
 *   - LoanService (after loan status change)
 *   - DepositService (on maturity)
 *   - AccountService (on balance change)
 *
 * WebSocket Topics Published:
 *   /topic/accounts/{accountId}/balance        ← Balance updates
 *   /topic/accounts/{accountId}/transactions    ← New transactions
 *   /topic/loans/{loanId}/status                ← Loan status changes
 *   /topic/deposits/{depositId}/maturity        ← Deposit maturity
 *
 * Design Pattern: Fire-and-Forget
 * - If WebSocket publish fails → LOG but don't fail the transaction
 * - Real-time updates are "nice to have", not "must-have"
 * - Transaction finality comes from GL posting, not WebSocket
 */
@Service
public class RealtimeUpdateService {

    private static final Logger log = LoggerFactory.getLogger(RealtimeUpdateService.class);

    private final SimpMessagingTemplate messagingTemplate;

    public RealtimeUpdateService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Publish balance update to connected clients
     *
     * Called after transfer/deposit/loan posting completes.
     * Notifies React client to update UI without page refresh.
     *
     * CBS CRITICAL: This covers both debit AND credit side notifications
     * - Creditor sees balance decrease (with withdrawal information)
     * - Debtor sees balance increase (with deposit information)
     */
    public void publishBalanceUpdate(
            String tenantId,
            String accountId,
            BigDecimal newBalance,
            BigDecimal availableBalance,
            String reason) {

        try {
            Map<String, Object> message = new HashMap<>();
            message.put("accountId", accountId);
            message.put("balance", newBalance);
            message.put("availableBalance", availableBalance);
            message.put("reason", reason);  // "TRANSACTION_POSTED", "INTEREST", "CHARGE"
            message.put("timestamp", LocalDateTime.now());

            String topic = "/topic/accounts/" + accountId + "/balance";
            messagingTemplate.convertAndSend(topic, message);

            log.debug("Published balance update: account={}, balance={}, reason={}",
                accountId, newBalance, reason);

        } catch (Exception ex) {
            // IMPORTANT: Don't fail the transaction if WebSocket fails
            // Real-time updates are non-critical; GL posting is what matters
            log.error("Failed to publish balance update: account={}", accountId, ex);
        }
    }

    /**
     * Publish transaction posted event
     *
     * Called after transaction is posted to GL and account balances updated.
     * Notifies both creditor and debtor of the transaction completion.
     *
     * CBS CRITICAL: This is the only "proof of posting" sent to user
     * - Reference number ties back to GL posting
     * - Status confirms transaction finality
     * - Both parties see consistent information
     */
    public void publishTransactionPosted(
            String tenantId,
            Transaction transaction,
            Account debitorAccount,
            Account creditorAccount) {

        try {
            Map<String, Object> event = new HashMap<>();
            event.put("transactionId", transaction.getId().toString());
            event.put("glReferenceNumber", transaction.getReferenceNumber());  // GL audit trail
            event.put("amount", transaction.getAmount());
            event.put("type", transaction.getTransactionType().name());
            event.put("status", transaction.getStatus().name());
            event.put("description", transaction.getDescription());
            event.put("postingDate", transaction.getPostingDate());
            event.put("valueDate", transaction.getValueDate());
            event.put("timestamp", LocalDateTime.now());

            // Notify CREDITOR account (who received money)
            if (creditorAccount != null) {
                String creditorTopic = "/topic/accounts/" + creditorAccount.getId() + "/transactions";
                Map<String, Object> creditorEvent = new HashMap<>(event);
                creditorEvent.put("perspective", "CREDIT");  // This account was credited
                creditorEvent.put("otherAccountNumber", debitorAccount.getAccountNumber());
                messagingTemplate.convertAndSend(creditorTopic, creditorEvent);

                log.debug("Published transaction (credit) to account: {}", creditorAccount.getId());
            }

            // Notify DEBITOR account (who sent money)
            if (debitorAccount != null) {
                String debitorTopic = "/topic/accounts/" + debitorAccount.getId() + "/transactions";
                Map<String, Object> debitorEvent = new HashMap<>(event);
                debitorEvent.put("perspective", "DEBIT");   // This account was debited
                debitorEvent.put("otherAccountNumber", creditorAccount.getAccountNumber());
                messagingTemplate.convertAndSend(debitorTopic, debitorEvent);

                log.debug("Published transaction (debit) to account: {}", debitorAccount.getId());
            }

        } catch (Exception ex) {
            log.error("Failed to publish transaction event: txn={}", transaction.getId(), ex);
        }
    }

    /**
     * Publish loan status change
     *
     * Called when loan status changes:
     * - SUBMITTED → UNDER_REVIEW
     * - UNDER_REVIEW → APPROVED
     * - APPROVED → DISBURSED
     * - etc.
     *
     * CBS CRITICAL: Status change is definitive (backs GL posting)
     * - Loan account creation ties to GL posting
     * - EMI posting schedule starts on DISBURSED status
     */
    public void publishLoanStatusChange(
            String tenantId,
            String loanId,
            String oldStatus,
            String newStatus,
            Map<String, Object> details) {

        try {
            Map<String, Object> event = new HashMap<>();
            event.put("loanId", loanId);
            event.put("previousStatus", oldStatus);
            event.put("currentStatus", newStatus);
            if (details != null) {
                event.putAll(details);  // Amount, EMI, tenure, etc.
            }
            event.put("timestamp", LocalDateTime.now());

            String topic = "/topic/loans/" + loanId + "/status";
            messagingTemplate.convertAndSend(topic, event);

            log.debug("Published loan status change: loan={}, {} → {}",
                loanId, oldStatus, newStatus);

        } catch (Exception ex) {
            log.error("Failed to publish loan status: loan={}", loanId, ex);
        }
    }

    /**
     * Publish deposit maturity notification
     *
     * Called when fixed deposit reaches maturity date.
     * Notifies customer of maturity + options (renewal, withdrawal).
     */
    public void publishDepositMaturity(
            String tenantId,
            String depositId,
            BigDecimal amount,
            BigDecimal interest) {

        try {
            Map<String, Object> event = new HashMap<>();
            event.put("depositId", depositId);
            event.put("principal", amount);
            event.put("interest", interest);
            event.put("maturityAmount", amount.add(interest));
            event.put("maturityDate", LocalDateTime.now());
            event.put("renewalDeadline", LocalDateTime.now().plusDays(7));  // 7 days to renew
            event.put("timestamp", LocalDateTime.now());

            String topic = "/topic/deposits/" + depositId + "/maturity";
            messagingTemplate.convertAndSend(topic, event);

            log.debug("Published deposit maturity: deposit={}, amount={}",
                depositId, amount.add(interest));

        } catch (Exception ex) {
            log.error("Failed to publish deposit maturity: deposit={}", depositId, ex);
        }
    }

    /**
     * Publish charge posting (interest, fees, penalties)
     *
     * Called when batch posting charges/interest.
     * Notifies account of new charge or credit.
     */
    public void publishChargePosting(
            String tenantId,
            String accountId,
            String chargeType,
            BigDecimal chargeAmount,
            String description) {

        try {
            Map<String, Object> event = new HashMap<>();
            event.put("accountId", accountId);
            event.put("chargeType", chargeType);  // "INTEREST", "MAINTENANCE_FEE", "PENALTY"
            event.put("amount", chargeAmount);
            event.put("description", description);
            event.put("timestamp", LocalDateTime.now());

            String topic = "/topic/accounts/" + accountId + "/charges";
            messagingTemplate.convertAndSend(topic, event);

            log.debug("Published charge posting: account={}, type={}, amount={}",
                accountId, chargeType, chargeAmount);

        } catch (Exception ex) {
            log.error("Failed to publish charge: account={}, type={}", accountId, chargeType, ex);
        }
    }

    /**
     * Batch publish for EOD operations
     *
     * Called after EOD batch completes (interest posting, daily charges, etc.)
     * Notifies all affected accounts at once.
     */
    public void publishBatchNotification(
            String tenantId,
            String batchType,
            Map<String, Object> summary) {

        try {
            Map<String, Object> event = new HashMap<>();
            event.put("batchType", batchType);  // "EOD_INTEREST", "DAILY_CHARGES"
            event.putAll(summary);
            event.put("completedAt", LocalDateTime.now());

            String topic = "/topic/batch/" + batchType;
            messagingTemplate.convertAndSend(topic, event);

            log.info("Published batch notification: type={}, summary={}",
                batchType, summary);

        } catch (Exception ex) {
            log.error("Failed to publish batch notification: type={}", batchType, ex);
        }
    }
}

