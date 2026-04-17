<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<%@ taglib prefix="fn" uri="jakarta.tags.functions" %>
<c:set var="pageTitle" value="Transaction 360" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <!-- Search Form -->
    <div class="fv-card mb-3">
        <div class="card-header"><i class="bi bi-diagram-3"></i> Transaction 360 &mdash; Unified Inquiry</div>
        <div class="card-body">
            <p class="text-muted small">Enter a Transaction Ref (TXN...), Voucher Number (VCH/...), or Journal Ref (JRN...) to view the complete transaction lifecycle.</p>
            <form method="get" action="${pageContext.request.contextPath}/txn360/search" class="fv-form">
                <div class="row g-2 align-items-end">
                    <div class="col-md-6">
                        <input type="text" name="q" class="form-control" placeholder="e.g., VCH/HQ001/20260401/000001 or TXN20260401..." value="<c:out value='${query}'/>" required autofocus />
                    </div>
                    <div class="col-auto">
                        <button type="submit" class="btn btn-fv-primary"><i class="bi bi-search"></i> Search</button>
                    </div>
                </div>
            </form>
        </div>
    </div>

    <c:if test="${not empty error}">
        <div class="alert alert-warning"><i class="bi bi-exclamation-triangle"></i> <c:out value="${error}" /></div>
    </c:if>

    <!-- Deposit Transaction (CASA Subledger Entry)
         CBS Tier-1: detail panels use `.fv-table.table-sm` so font/row density/border
         tokens come from theme, not raw Bootstrap `.table`. --> 
    <c:if test="${not empty depositTxn}">
    <div class="fv-card mb-3">
        <div class="card-header"><i class="bi bi-receipt"></i> CASA Deposit Transaction (Subledger) <span class="badge bg-info">DEPOSIT</span></div>
        <div class="card-body">
            <div class="row">
                <div class="col-md-6">
                    <table class="table fv-table table-sm mb-0">
                        <tr><td class="text-muted">Transaction Ref</td><td class="font-monospace"><c:out value="${depositTxn.transactionRef}" /></td></tr>
                        <tr><td class="text-muted">Voucher Number</td><td class="font-monospace"><c:out value="${depositTxn.voucherNumber}" default="--" /></td></tr>
                        <tr><td class="text-muted">Journal Entry ID</td><td class="font-monospace"><c:out value="${depositTxn.journalEntryId}" default="--" /></td></tr>
                        <tr><td class="text-muted">Type</td><td><c:out value="${depositTxn.transactionType}" /></td></tr>
                        <tr><td class="text-muted">Direction</td><td><span class="${depositTxn.debitCredit == 'DEBIT' ? 'text-danger' : 'text-success'}"><c:out value="${depositTxn.debitCredit}" /></span></td></tr>
                        <tr><td class="text-muted">Branch</td><td><c:out value="${depositTxn.branchCode}" default="--" /></td></tr>
                    </table>
                </div>
                <div class="col-md-6">
                    <table class="table fv-table table-sm mb-0">
                        <tr><td class="text-muted">Amount</td><td class="amount fs-6"><strong><fmt:formatNumber value="${depositTxn.amount}" type="currency" currencyCode="INR" /></strong></td></tr>
                        <tr><td class="text-muted">Balance After</td><td class="amount"><fmt:formatNumber value="${depositTxn.balanceAfter}" type="currency" currencyCode="INR" /></td></tr>
                        <tr><td class="text-muted">Value Date</td><td><c:out value="${depositTxn.valueDate}" /></td></tr>
                        <tr><td class="text-muted">Posting Date</td><td><c:out value="${depositTxn.postingDate}" /></td></tr>
                        <tr><td class="text-muted">Channel</td><td><c:out value="${depositTxn.channel}" default="--" /></td></tr>
                        <tr><td class="text-muted">Narration</td><td><c:out value="${depositTxn.narration}" /></td></tr>
                        <tr><td class="text-muted">Posted By</td><td><c:out value="${depositTxn.createdBy}" default="--" /></td></tr>
                        <tr><td class="text-muted">Status</td><td><c:choose>
                            <c:when test="${depositTxn.reversed}"><span class="fv-badge fv-badge-npa">REVERSED</span></c:when>
                            <c:otherwise><span class="fv-badge fv-badge-active">POSTED</span></c:otherwise>
                        </c:choose></td></tr>
                    </table>
                </div>
            </div>
        </div>
    </div>
    </c:if>

    <!-- Loan Transaction (Loan Subledger Entry) -->
    <c:if test="${not empty loanTxn}">
    <div class="fv-card mb-3">
        <div class="card-header"><i class="bi bi-receipt"></i> Loan Transaction (Subledger) <span class="badge bg-primary">LOAN</span></div>
        <div class="card-body">
            <div class="row">
                <div class="col-md-6">
                    <table class="table fv-table table-sm mb-0">
                        <tr><td class="text-muted">Transaction Ref</td><td class="font-monospace"><c:out value="${loanTxn.transactionRef}" /></td></tr>
                        <tr><td class="text-muted">Voucher Number</td><td class="font-monospace"><c:out value="${loanTxn.voucherNumber}" default="--" /></td></tr>
                        <tr><td class="text-muted">Journal Entry ID</td><td class="font-monospace"><c:out value="${loanTxn.journalEntryId}" default="--" /></td></tr>
                        <tr><td class="text-muted">Type</td><td><c:out value="${loanTxn.transactionType}" /></td></tr>
                        <tr><td class="text-muted">Account</td><td class="font-monospace"><c:out value="${loanTxn.loanAccount.accountNumber}" /></td></tr>
                    </table>
                </div>
                <div class="col-md-6">
                    <table class="table fv-table table-sm mb-0">
                        <tr><td class="text-muted">Amount</td><td class="amount fs-6"><strong><fmt:formatNumber value="${loanTxn.amount}" type="currency" currencyCode="INR" /></strong></td></tr>
                        <tr><td class="text-muted">Value Date</td><td><c:out value="${loanTxn.valueDate}" /></td></tr>
                        <tr><td class="text-muted">Posting Date</td><td><c:out value="${loanTxn.postingDate}" /></td></tr>
                        <tr><td class="text-muted">Narration</td><td><c:out value="${loanTxn.narration}" /></td></tr>
                        <tr><td class="text-muted">Posted By</td><td><c:out value="${loanTxn.createdBy}" default="--" /></td></tr>
                        <tr><td class="text-muted">Status</td><td><c:choose>
                            <c:when test="${loanTxn.reversed}"><span class="fv-badge fv-badge-npa">REVERSED</span></c:when>
                            <c:otherwise><span class="fv-badge fv-badge-active">POSTED</span></c:otherwise>
                        </c:choose></td></tr>
                    </table>
                </div>
            </div>
        </div>
    </div>
    </c:if>

    <!-- Journal Entry (Double-Entry) -->
    <c:if test="${not empty journalEntry}">
    <div class="fv-card mb-3">
        <div class="card-header"><i class="bi bi-journal-text"></i> Journal Entry (Double-Entry)</div>
        <div class="card-body">
            <table class="table fv-table table-sm mb-0">
                <tr><td class="text-muted" style="width:200px;">Journal Ref</td><td class="font-monospace"><c:out value="${journalEntry.journalRef}" /></td></tr>
                <tr><td class="text-muted">Value Date</td><td><c:out value="${journalEntry.valueDate}" /></td></tr>
                <tr><td class="text-muted">Source Module</td><td><c:out value="${journalEntry.sourceModule}" /></td></tr>
                <tr><td class="text-muted">Source Ref</td><td class="font-monospace"><c:out value="${journalEntry.sourceRef}" default="--" /></td></tr>
                <tr><td class="text-muted">Total Debit</td><td class="amount"><fmt:formatNumber value="${journalEntry.totalDebit}" type="currency" currencyCode="INR" /></td></tr>
                <tr><td class="text-muted">Total Credit</td><td class="amount"><fmt:formatNumber value="${journalEntry.totalCredit}" type="currency" currencyCode="INR" /></td></tr>
                <tr><td class="text-muted">Narration</td><td><c:out value="${journalEntry.narration}" /></td></tr>
            </table>
        </div>
    </div>
    </c:if>

    <!-- Ledger Entries (GL Postings) -->
    <c:if test="${not empty ledgerEntries}">
    <div class="fv-card">
        <div class="card-header"><i class="bi bi-receipt-cutoff"></i> Ledger Entries (GL Postings) <span class="badge bg-secondary">${fn:length(ledgerEntries)}</span></div>
        <div class="card-body">
            <div class="table-responsive">
            <table class="table fv-table table-sm">
                <thead><tr><th>Seq</th><th>GL Code</th><th>GL Name</th><th class="text-end">Debit</th><th class="text-end">Credit</th><th>Narration</th></tr></thead>
                <tbody>
                <c:forEach var="entry" items="${ledgerEntries}">
                    <tr>
                        <td><c:out value="${entry.ledgerSequence}" /></td>
                        <td><c:out value="${entry.glCode}" /></td>
                        <td><c:out value="${entry.glName}" /></td>
                        <td class="amount"><c:if test="${entry.debitAmount > 0}"><fmt:formatNumber value="${entry.debitAmount}" type="number" maxFractionDigits="2" /></c:if></td>
                        <td class="amount"><c:if test="${entry.creditAmount > 0}"><fmt:formatNumber value="${entry.creditAmount}" type="number" maxFractionDigits="2" /></c:if></td>
                        <td class="small"><c:out value="${entry.narration}" /></td>
                    </tr>
                </c:forEach>
                </tbody>
            </table>
            </div>
        </div>
    </div>
    </c:if>
</div>

<%@ include file="../layout/footer.jsp" %>
