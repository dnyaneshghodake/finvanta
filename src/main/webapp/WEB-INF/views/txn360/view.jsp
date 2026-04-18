<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Transaction 360" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">

    <!-- Search Bar -->
    <div class="fv-card mb-3">
        <div class="card-body">
            <form method="get" action="${pageContext.request.contextPath}/txn360/search" class="row g-2 align-items-end">
                <div class="col-md-8">
                    <label class="form-label fw-bold">Transaction 360 Inquiry</label>
                    <input type="text" name="q" class="form-control" placeholder="Enter TXN ref, VCH voucher, or JRN journal ref..." value="${lookupValue}" required />
                </div>
                <div class="col-md-2">
                    <button type="submit" class="btn btn-fv-primary w-100"><i class="bi bi-search"></i> Search</button>
                </div>
                <div class="col-md-2 text-muted small">
                    Prefix: TXN... | VCH/... | JRN...
                </div>
            </form>
        </div>
    </div>

    <c:if test="${not empty transaction}">

    <!-- Transaction Header -->
    <div class="fv-card mb-3">
        <div class="card-header d-flex justify-content-between align-items-center">
            <span>Transaction Details</span>
            <span>
                <c:choose>
                    <c:when test="${transaction.reversed}"><span class="fv-badge fv-badge-npa">REVERSED</span></c:when>
                    <c:when test="${transaction.transactionType == 'REVERSAL'}"><span class="fv-badge fv-badge-pending">REVERSAL</span></c:when>
                    <c:otherwise><span class="fv-badge fv-badge-active">POSTED</span></c:otherwise>
                </c:choose>
            </span>
        </div>
        <div class="card-body">
            <div class="row">
                <div class="col-md-6">
                    <table class="table fv-table mb-0">
                        <tr><td class="fw-bold fv-label-col">Transaction Ref</td><td class="font-monospace"><c:out value="${transaction.transactionRef}" /></td></tr>
                        <tr><td class="fw-bold">Type</td><td><c:out value="${transaction.transactionType}" /></td></tr>
                        <tr><td class="fw-bold">Amount</td><td class="amount fw-bold"><fmt:formatNumber value="${transaction.amount}" type="number" maxFractionDigits="2" /> INR</td></tr>
                        <tr><td class="fw-bold">Value Date</td><td><c:out value="${transaction.valueDate}" /></td></tr>
                        <tr><td class="fw-bold">Posting Date</td><td><c:out value="${transaction.postingDate}" /></td></tr>
                        <tr><td class="fw-bold">Initiated By</td><td><c:out value="${transaction.createdBy}" /></td></tr>
                    </table>
                </div>
                <div class="col-md-6">
                    <table class="table fv-table mb-0">
                        <tr><td class="fw-bold fv-label-col">Voucher Number</td><td class="font-monospace"><c:out value="${transaction.voucherNumber}" default="--" /></td></tr>
                        <tr><td class="fw-bold">Journal Entry ID</td><td><c:out value="${transaction.journalEntryId}" default="--" /></td></tr>
                        <tr><td class="fw-bold">Balance After</td><td class="amount"><fmt:formatNumber value="${transaction.balanceAfter}" type="number" maxFractionDigits="2" /></td></tr>
                        <tr><td class="fw-bold">Idempotency Key</td><td class="font-monospace small"><c:out value="${transaction.idempotencyKey}" default="--" /></td></tr>
                        <tr><td class="fw-bold">Narration</td><td><c:out value="${transaction.narration}" /></td></tr>
                        <c:if test="${transaction.reversed}">
                        <tr><td class="fw-bold text-danger">Reversed By</td><td class="font-monospace"><c:out value="${transaction.reversedByRef}" /></td></tr>
                        </c:if>
                    </table>
                </div>
            </div>
        </div>
    </div>

    <!-- Component Breakdown -->
    <div class="row mb-3">
        <div class="col-md-4">
            <div class="fv-stat-card">
                <div class="stat-value"><fmt:formatNumber value="${transaction.principalComponent}" type="number" maxFractionDigits="2" /></div>
                <div class="stat-label">Principal Component</div>
            </div>
        </div>
        <div class="col-md-4">
            <div class="fv-stat-card">
                <div class="stat-value"><fmt:formatNumber value="${transaction.interestComponent}" type="number" maxFractionDigits="2" /></div>
                <div class="stat-label">Interest Component</div>
            </div>
        </div>
        <div class="col-md-4">
            <div class="fv-stat-card">
                <div class="stat-value"><fmt:formatNumber value="${transaction.penaltyComponent}" type="number" maxFractionDigits="2" /></div>
                <div class="stat-label">Penalty Component</div>
            </div>
        </div>
    </div>

    <!-- Account Context -->
    <div class="fv-card mb-3">
        <div class="card-header">Account Context</div>
        <div class="card-body">
            <div class="row">
                <div class="col-md-6">
                    <table class="table fv-table mb-0">
                        <tr><td class="fw-bold fv-label-col">Account Number</td><td><a href="${pageContext.request.contextPath}/loan/account/${account.accountNumber}"><c:out value="${account.accountNumber}" /></a></td></tr>
                        <tr><td class="fw-bold">Product Type</td><td><c:out value="${account.productType}" /></td></tr>
                        <tr><td class="fw-bold">Status</td><td>
                            <c:choose>
                                <c:when test="${account.status.npa}"><span class="fv-badge fv-badge-npa"><c:out value="${account.status}" /></span></c:when>
                                <c:when test="${account.status.terminal}"><span class="fv-badge fv-badge-closed"><c:out value="${account.status}" /></span></c:when>
                                <c:otherwise><span class="fv-badge fv-badge-active"><c:out value="${account.status}" /></span></c:otherwise>
                            </c:choose>
                        </td></tr>
                        <tr><td class="fw-bold">Outstanding Principal</td><td class="amount"><fmt:formatNumber value="${account.outstandingPrincipal}" type="number" maxFractionDigits="2" /></td></tr>
                    </table>
                </div>
                <div class="col-md-6">
                    <table class="table fv-table mb-0">
                        <tr><td class="fw-bold fv-label-col">Customer</td><td><a href="${pageContext.request.contextPath}/customer/view/${customer.id}"><c:out value="${customer.firstName}" /> <c:out value="${customer.lastName}" /></a> (<c:out value="${customer.customerNumber}" />)</td></tr>
                        <tr><td class="fw-bold">Branch</td><td><a href="${pageContext.request.contextPath}/branch/view/${branch.id}"><c:out value="${branch.branchCode}" /> - <c:out value="${branch.branchName}" /></a></td></tr>
                        <tr><td class="fw-bold">Currency</td><td><c:out value="${account.currencyCode}" /></td></tr>
                        <tr><td class="fw-bold">DPD</td><td><c:out value="${account.daysPastDue}" /></td></tr>
                    </table>
                </div>
            </div>
        </div>
    </div>

    <!-- GL Posting -->
    <c:if test="${not empty journal}">
    <div class="fv-card mb-3">
        <div class="card-header">GL Posting - Journal: <span class="font-monospace"><c:out value="${journal.journalRef}" /></span>
            <c:if test="${not empty compoundJournals}"><span class="fv-badge fv-badge-pending ms-2">Compound (${compoundJournals.size()} journals)</span></c:if>
        </div>
        <div class="card-body">
            <table class="table fv-table">
                <thead>
                    <tr><th>GL Code</th><th>GL Name</th><th>DR/CR</th><th class="text-end">Amount</th><th>Narration</th></tr>
                </thead>
                <tbody>
                    <c:forEach var="line" items="${journalLines}">
                    <tr>
                        <td class="font-monospace"><c:out value="${line.glCode}" /></td>
                        <td><c:out value="${line.glName}" /></td>
                        <td>
                            <c:choose>
                                <c:when test="${line.debitCredit == 'DEBIT'}"><span class="text-danger fw-bold">DR</span></c:when>
                                <c:otherwise><span class="text-success fw-bold">CR</span></c:otherwise>
                            </c:choose>
                        </td>
                        <td class="text-end amount"><fmt:formatNumber value="${line.amount}" type="number" maxFractionDigits="2" /></td>
                        <td class="small"><c:out value="${line.narration}" /></td>
                    </tr>
                    </c:forEach>
                </tbody>
                <tfoot>
                    <tr class="table-light fw-bold">
                        <td colspan="3">Total</td>
                        <td class="text-end">DR: <fmt:formatNumber value="${journal.totalDebit}" type="number" maxFractionDigits="2" /> | CR: <fmt:formatNumber value="${journal.totalCredit}" type="number" maxFractionDigits="2" /></td>
                        <td>
                            <c:choose>
                                <c:when test="${journal.totalDebit == journal.totalCredit}"><span class="text-success">Balanced &#10003;</span></c:when>
                                <c:otherwise><span class="text-danger">IMBALANCED &#10007;</span></c:otherwise>
                            </c:choose>
                        </td>
                    </tr>
                </tfoot>
            </table>
        </div>
    </div>
    </c:if>

    <!-- CBS Compound Journals (e.g., Write-Off with multiple balanced journal groups) -->
    <c:if test="${not empty compoundJournals}">
    <c:forEach var="cj" items="${compoundJournals}" varStatus="cjStatus">
    <c:if test="${cj.id != journal.id}">
    <div class="fv-card mb-3">
        <div class="card-header">Compound Journal ${cjStatus.index + 1}: <span class="font-monospace"><c:out value="${cj.journalRef}" /></span></div>
        <div class="card-body">
            <p class="text-muted small mb-2"><c:out value="${cj.narration}" /></p>
            <table class="table fv-table">
                <thead>
                    <tr><th>GL Code</th><th>GL Name</th><th>DR/CR</th><th class="text-end">Amount</th><th>Narration</th></tr>
                </thead>
                <tbody>
                    <c:forEach var="cjLine" items="${cj.lines}">
                    <tr>
                        <td class="font-monospace"><c:out value="${cjLine.glCode}" /></td>
                        <td><c:out value="${cjLine.glName}" /></td>
                        <td>
                            <c:choose>
                                <c:when test="${cjLine.debitCredit == 'DEBIT'}"><span class="text-danger fw-bold">DR</span></c:when>
                                <c:otherwise><span class="text-success fw-bold">CR</span></c:otherwise>
                            </c:choose>
                        </td>
                        <td class="text-end amount"><fmt:formatNumber value="${cjLine.amount}" type="number" maxFractionDigits="2" /></td>
                        <td class="small"><c:out value="${cjLine.narration}" /></td>
                    </tr>
                    </c:forEach>
                </tbody>
                <tfoot>
                    <tr class="table-light fw-bold">
                        <td colspan="3">Total</td>
                        <td class="text-end">DR: <fmt:formatNumber value="${cj.totalDebit}" type="number" maxFractionDigits="2" /> | CR: <fmt:formatNumber value="${cj.totalCredit}" type="number" maxFractionDigits="2" /></td>
                        <td>
                            <c:choose>
                                <c:when test="${cj.totalDebit == cj.totalCredit}"><span class="text-success">Balanced &#10003;</span></c:when>
                                <c:otherwise><span class="text-danger">IMBALANCED &#10007;</span></c:otherwise>
                            </c:choose>
                        </td>
                    </tr>
                </tfoot>
            </table>
        </div>
    </div>
    </c:if>
    </c:forEach>
    </c:if>

    <!-- Reversal Linkage -->
    <c:if test="${not empty reversalTransaction}">
    <div class="fv-card mb-3">
        <div class="card-header text-danger">Reversed By</div>
        <div class="card-body">
            <p>This transaction was reversed by:
                <a href="${pageContext.request.contextPath}/txn360/${reversalTransaction.transactionRef}" class="font-monospace">
                    <c:out value="${reversalTransaction.transactionRef}" />
                </a>
                on <c:out value="${reversalTransaction.valueDate}" />
            </p>
        </div>
    </div>
    </c:if>

    <c:if test="${not empty originalTransaction}">
    <div class="fv-card mb-3">
        <div class="card-header">Original Transaction (This is a Reversal)</div>
        <div class="card-body">
            <p>This reversal was created for original transaction:
                <a href="${pageContext.request.contextPath}/txn360/${originalTransaction.transactionRef}" class="font-monospace">
                    <c:out value="${originalTransaction.transactionRef}" />
                </a>
                - <c:out value="${originalTransaction.transactionType}" />
                - <fmt:formatNumber value="${originalTransaction.amount}" type="number" maxFractionDigits="2" /> INR
            </p>
        </div>
    </div>
    </c:if>

    </c:if>

    <c:if test="${empty transaction and empty journal}">
    <div class="fv-card">
        <div class="card-body text-center text-muted py-5">
            <i class="bi bi-search" style="font-size:3rem"></i>
            <h5 class="mt-3">Transaction 360 Inquiry</h5>
            <p>Enter a Transaction Ref (TXN...), Voucher Number (VCH/...), or Journal Ref (JRN...) to view the complete transaction lifecycle.</p>
        </div>
    </div>
    </c:if>

</div>

<%@ include file="../layout/footer.jsp" %>
