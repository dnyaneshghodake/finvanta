<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Voucher Register" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <!-- Date Filter -->
    <div class="fv-card mb-3">
        <div class="card-body">
            <form method="get" action="${pageContext.request.contextPath}/accounting/voucher-register" class="fv-form">
                <div class="row g-2 align-items-end">
                    <div class="col-auto">
                        <label class="form-label">Business Date</label>
                        <input type="date" name="businessDate" class="form-control" value="${reportDate}" required/>
                    </div>
                    <div class="col-auto">
                        <label class="form-label">&nbsp;</label>
                        <button type="submit" class="btn btn-fv-primary d-block"><i class="bi bi-funnel"></i> Filter</button>
                    </div>
                </div>
            </form>
        </div>
    </div>

    <!-- Ledger Entries (GL Postings) -->
    <div class="fv-card mb-3">
        <div class="card-header"><i class="bi bi-receipt-cutoff"></i> GL Ledger Entries &mdash; <c:out value="${reportDate}" /> <span class="badge bg-secondary"><c:out value="${ledgerEntries.size()}" /></span></div>
        <div class="card-body">
            <div class="table-responsive">
            <table class="table fv-table fv-datatable table-sm">
                <thead><tr>
                    <th>Seq</th><th>Journal Ref</th><th>GL Code</th><th>GL Name</th><th>Account Ref</th>
                    <th class="text-end">Debit</th><th class="text-end">Credit</th><th>Module</th><th>Narration</th>
                </tr></thead>
                <tbody>
                <c:forEach var="entry" items="${ledgerEntries}">
                    <tr>
                        <td><c:out value="${entry.ledgerSequence}" /></td>
                        <td class="font-monospace small"><c:out value="${entry.journalRef}" /></td>
                        <td><c:out value="${entry.glCode}" /></td>
                        <td><c:out value="${entry.glName}" /></td>
                        <td class="font-monospace small"><c:out value="${entry.accountReference}" default="--" /></td>
                        <td class="text-end amount"><c:if test="${entry.debitAmount.signum() > 0}"><fmt:formatNumber value="${entry.debitAmount}" type="number" maxFractionDigits="2" /></c:if></td>
                        <td class="text-end amount"><c:if test="${entry.creditAmount.signum() > 0}"><fmt:formatNumber value="${entry.creditAmount}" type="number" maxFractionDigits="2" /></c:if></td>
                        <td><c:out value="${entry.moduleCode}" default="--" /></td>
                        <td class="small"><c:out value="${entry.narration}" /></td>
                    </tr>
                </c:forEach>
                <c:if test="${empty ledgerEntries}"><tr><td colspan="9" class="text-center text-muted">No ledger entries for this date</td></tr></c:if>
                </tbody>
            </table>
            </div>
        </div>
    </div>

    <!-- Loan Transactions -->
    <c:if test="${not empty loanTransactions}">
    <div class="fv-card mb-3">
        <div class="card-header">Loan Transactions <span class="badge bg-secondary"><c:out value="${loanTransactions.size()}" /></span></div>
        <div class="card-body">
            <div class="table-responsive">
            <table class="table fv-table table-sm">
                <thead><tr><th>Txn Ref</th><th>Type</th><th class="text-end">Amount</th><th>Voucher</th><th>Account</th><th>Narration</th></tr></thead>
                <tbody>
                <c:forEach var="t" items="${loanTransactions}">
                    <tr class="${t.reversed ? 'table-secondary' : ''}">
                        <td class="font-monospace small"><c:out value="${t.transactionRef}" /></td>
                        <td><c:out value="${t.transactionType}" /></td>
                        <td class="text-end amount"><fmt:formatNumber value="${t.amount}" type="number" maxFractionDigits="2" /></td>
                        <td class="font-monospace small"><c:out value="${t.voucherNumber}" default="--" /></td>
                        <td><c:out value="${t.loanAccount.accountNumber}" /></td>
                        <td class="small"><c:out value="${t.narration}" /></td>
                    </tr>
                </c:forEach>
                </tbody>
            </table>
            </div>
        </div>
    </div>
    </c:if>

    <!-- Deposit Transactions -->
    <c:if test="${not empty depositTransactions}">
    <div class="fv-card">
        <div class="card-header">Deposit Transactions <span class="badge bg-secondary"><c:out value="${depositTransactions.size()}" /></span></div>
        <div class="card-body">
            <div class="table-responsive">
            <table class="table fv-table table-sm">
                <thead><tr><th>Txn Ref</th><th>Type</th><th>DR/CR</th><th class="text-end">Amount</th><th>Voucher</th><th>Channel</th><th>Narration</th></tr></thead>
                <tbody>
                <c:forEach var="t" items="${depositTransactions}">
                    <tr class="${t.reversed ? 'table-secondary' : ''}">
                        <td class="font-monospace small"><c:out value="${t.transactionRef}" /></td>
                        <td><c:out value="${t.transactionType}" /></td>
                        <td><span class="${t.debitCredit == 'DEBIT' ? 'text-danger' : 'text-success'}"><c:out value="${t.debitCredit}" /></span></td>
                        <td class="text-end amount"><fmt:formatNumber value="${t.amount}" type="number" maxFractionDigits="2" /></td>
                        <td class="font-monospace small"><c:out value="${t.voucherNumber}" default="--" /></td>
                        <td><c:out value="${t.channel}" default="--" /></td>
                        <td class="small"><c:out value="${t.narration}" /></td>
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
