<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Account Details" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
<c:if test="${not empty success}"><div class="alert alert-success"><c:out value="${success}"/></div></c:if>
<c:if test="${not empty error}"><div class="alert alert-danger"><c:out value="${error}"/></div></c:if>

<div class="d-flex justify-content-between align-items-center mb-3">
    <h4>Account: <c:out value="${account.accountNumber}"/></h4>
    <div>
        <span class="badge fs-6 ${account.active ? 'bg-success' : account.frozen ? 'bg-danger' : account.dormant ? 'bg-warning' : 'bg-secondary'}"><c:out value="${account.accountStatus}"/></span>
        <a href="${pageContext.request.contextPath}/deposit/accounts" class="btn btn-sm btn-outline-secondary ms-2"><i class="bi bi-arrow-left"></i> Back</a>
    </div>
</div>

<div class="row mb-4">
<div class="col-md-6">
<div class="card"><div class="card-body">
    <h6 class="card-title">Account Information</h6>
    <table class="table table-sm mb-0">
    <tr><td class="text-muted">Account Type</td><td><strong><c:out value="${account.accountType}"/></strong></td></tr>
    <tr><td class="text-muted">Product Code</td><td><c:out value="${account.productCode}"/></td></tr>
    <tr><td class="text-muted">Currency</td><td><c:out value="${account.currencyCode}"/></td></tr>
    <tr><td class="text-muted">Customer</td><td><a href="${pageContext.request.contextPath}/customer/view/${account.customer.id}"><c:out value="${account.customer.firstName}"/> <c:out value="${account.customer.lastName}"/></a> (<c:out value="${account.customer.customerNumber}"/>)</td></tr>
    <tr><td class="text-muted">Branch</td><td><a href="${pageContext.request.contextPath}/branch/view/${account.branch.id}"><c:out value="${account.branch.branchName}"/></a> (<c:out value="${account.branch.branchCode}"/>)</td></tr>
    <tr><td class="text-muted">Opened Date</td><td><c:out value="${account.openedDate}"/></td></tr>
    <tr><td class="text-muted">Last Transaction</td><td><c:out value="${account.lastTransactionDate}" default="--"/></td></tr>
    <tr><td class="text-muted">Nominee</td><td><c:out value="${account.nomineeName}" default="Not set"/><c:if test="${not empty account.nomineeRelationship}"> (<c:out value="${account.nomineeRelationship}"/>)</c:if></td></tr>
    <c:if test="${account.savings}">
    <tr><td class="text-muted">Interest Rate</td><td><fmt:formatNumber value="${account.interestRate}" maxFractionDigits="4"/>% p.a.</td></tr>
    <tr><td class="text-muted">Accrued Interest</td><td><fmt:formatNumber value="${account.accruedInterest}" type="currency" currencyCode="INR"/></td></tr>
    <tr><td class="text-muted">YTD Interest Credited</td><td><fmt:formatNumber value="${account.ytdInterestCredited}" type="currency" currencyCode="INR"/></td></tr>
    <tr><td class="text-muted">YTD TDS Deducted</td><td><fmt:formatNumber value="${account.ytdTdsDeducted}" type="currency" currencyCode="INR"/></td></tr>
    </c:if>
    <c:if test="${account.dailyWithdrawalLimit != null && account.dailyWithdrawalLimit.signum() > 0}">
    <tr><td class="text-muted">Daily Withdrawal Limit</td><td><fmt:formatNumber value="${account.dailyWithdrawalLimit}" type="currency" currencyCode="INR"/></td></tr>
    </c:if>
    <c:if test="${account.frozen}">
    <tr><td class="text-muted">Freeze Type</td><td class="text-danger"><c:out value="${account.freezeType}"/></td></tr>
    <tr><td class="text-muted">Freeze Reason</td><td class="text-danger"><c:out value="${account.freezeReason}"/></td></tr>
    </c:if>
    <tr><td class="text-muted">Opened By</td><td><c:out value="${account.createdBy}" default="--"/></td></tr>
    </table>
</div></div>
</div>
<div class="col-md-6">
<div class="card"><div class="card-body">
    <h6 class="card-title">Balance Summary</h6>
    <table class="table table-sm mb-0">
    <tr><td class="text-muted">Ledger Balance</td><td class="text-end fs-5"><strong><fmt:formatNumber value="${account.ledgerBalance}" type="currency" currencyCode="INR"/></strong></td></tr>
    <tr><td class="text-muted">Hold/Lien Amount</td><td class="text-end"><fmt:formatNumber value="${account.holdAmount}" type="currency" currencyCode="INR"/></td></tr>
    <tr><td class="text-muted">Uncleared Funds</td><td class="text-end"><fmt:formatNumber value="${account.unclearedAmount}" type="currency" currencyCode="INR"/></td></tr>
    <tr><td class="text-muted">Minimum Balance</td><td class="text-end"><fmt:formatNumber value="${account.minimumBalance}" type="currency" currencyCode="INR"/></td></tr>
    <c:if test="${account.odLimit.signum() > 0}">
    <tr><td class="text-muted">OD Limit</td><td class="text-end"><fmt:formatNumber value="${account.odLimit}" type="currency" currencyCode="INR"/></td></tr>
    </c:if>
    <tr class="table-active"><td class="text-muted"><strong>Available Balance</strong></td><td class="text-end fs-5"><strong><fmt:formatNumber value="${account.effectiveAvailable}" type="currency" currencyCode="INR"/></strong></td></tr>
    </table>
</div></div>

<c:if test="${account.active}">
<div class="card mt-3"><div class="card-body d-flex gap-2 flex-wrap">
    <a href="${pageContext.request.contextPath}/deposit/deposit/${account.accountNumber}" class="btn btn-success btn-sm"><i class="bi bi-plus-circle"></i> Deposit</a>
    <a href="${pageContext.request.contextPath}/deposit/withdraw/${account.accountNumber}" class="btn btn-warning btn-sm"><i class="bi bi-dash-circle"></i> Withdraw</a>
    <a href="${pageContext.request.contextPath}/deposit/transfer" class="btn btn-info btn-sm"><i class="bi bi-arrow-left-right"></i> Transfer</a>
    <a href="${pageContext.request.contextPath}/deposit/statement/${account.accountNumber}" class="btn btn-outline-secondary btn-sm"><i class="bi bi-journal-text"></i> Statement</a>
    <c:if test="${pageContext.request.isUserInRole('ROLE_ADMIN')}">
    <form method="post" action="${pageContext.request.contextPath}/deposit/freeze/${account.accountNumber}" class="d-inline">
        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
        <input type="hidden" name="freezeType" value="TOTAL_FREEZE"/>
        <input type="hidden" name="reason" value="Admin freeze"/>
        <button type="submit" class="btn btn-danger btn-sm" onclick="return confirm('Freeze this account?')"><i class="bi bi-lock"></i> Freeze</button>
    </form>
    </c:if>
</div></div>
</c:if>
<c:if test="${account.frozen && pageContext.request.isUserInRole('ROLE_ADMIN')}">
<div class="card mt-3"><div class="card-body">
    <form method="post" action="${pageContext.request.contextPath}/deposit/unfreeze/${account.accountNumber}" class="d-inline">
        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
        <button type="submit" class="btn btn-success btn-sm" onclick="return confirm('Unfreeze this account?')"><i class="bi bi-unlock"></i> Unfreeze Account</button>
    </form>
</div></div>
</c:if>
<c:if test="${(pageContext.request.isUserInRole('ROLE_CHECKER') || pageContext.request.isUserInRole('ROLE_ADMIN')) && !account.closed}">
<div class="card mt-3"><div class="card-body">
    <form method="post" action="${pageContext.request.contextPath}/deposit/close/${account.accountNumber}" class="d-inline">
        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
        <input type="hidden" name="reason" value="" id="closeReason"/>
        <button type="submit" class="btn btn-outline-danger btn-sm"
            onclick="var r=prompt('Closure reason (mandatory):'); if(!r){return false;} document.getElementById('closeReason').value=r; return confirm('Close account ${account.accountNumber}? Balance must be zero.');">
            <i class="bi bi-x-circle"></i> Close Account
        </button>
    </form>
</div></div>
</c:if>
</div></div>

<h5 class="mt-4">Recent Transactions</h5>
<div class="table-responsive">
<table class="table fv-table fv-datatable table-sm">
<thead><tr><th>Date</th><th>Type</th><th>Channel</th><th>Narration</th><th class="text-end">Amount</th><th class="text-end">Balance</th><th>Voucher</th><th>Status</th>
<c:if test="${pageContext.request.isUserInRole('ROLE_CHECKER') || pageContext.request.isUserInRole('ROLE_ADMIN')}"><th>Action</th></c:if>
</tr></thead>
<tbody>
<c:forEach var="t" items="${transactions}">
<tr class="${t.reversed ? 'table-secondary text-decoration-line-through' : ''}">
    <td><c:out value="${t.postingDate}"/></td>
    <td><c:out value="${t.transactionType}"/></td>
    <td><c:out value="${t.channel}"/></td>
    <td><c:out value="${t.narration}"/></td>
    <td class="text-end ${t.debitCredit == 'DEBIT' ? 'text-danger' : 'text-success'}"><fmt:formatNumber value="${t.amount}" type="currency" currencyCode="INR"/></td>
    <td class="text-end"><fmt:formatNumber value="${t.balanceAfter}" type="currency" currencyCode="INR"/></td>
    <td><small class="font-monospace"><c:out value="${t.voucherNumber}"/></small></td>
    <td><c:choose>
        <c:when test="${t.reversed}"><span class="fv-badge fv-badge-npa">REVERSED</span></c:when>
        <c:when test="${t.transactionType == 'REVERSAL'}"><span class="fv-badge fv-badge-pending">REVERSAL</span></c:when>
        <c:otherwise><span class="fv-badge fv-badge-active">POSTED</span></c:otherwise>
    </c:choose></td>
    <c:if test="${pageContext.request.isUserInRole('ROLE_CHECKER') || pageContext.request.isUserInRole('ROLE_ADMIN')}">
    <td><c:if test="${!t.reversed && t.transactionType != 'REVERSAL' && !account.closed}">
        <form method="post" action="${pageContext.request.contextPath}/deposit/reversal/${t.transactionRef}" style="display:inline">
            <input type="hidden" name="accountNumber" value="${account.accountNumber}"/>
            <input type="hidden" name="reason" value="" id="reason_${t.transactionRef}"/>
            <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
            <button type="submit" class="btn btn-sm btn-outline-danger"
                onclick="var r=prompt('Reversal reason (mandatory):'); if(!r){return false;} document.getElementById('reason_${t.transactionRef}').value=r; return confirm('Reverse transaction ${t.transactionRef}?');">
                Reverse
            </button>
        </form>
    </c:if></td>
    </c:if>
</tr>
</c:forEach>
<c:if test="${empty transactions}"><tr><td colspan="${pageContext.request.isUserInRole('ROLE_CHECKER') || pageContext.request.isUserInRole('ROLE_ADMIN') ? 9 : 8}" class="text-center text-muted">No transactions</td></tr></c:if>
</tbody></table>
</div>
</div>

<%@ include file="../layout/footer.jsp" %>
