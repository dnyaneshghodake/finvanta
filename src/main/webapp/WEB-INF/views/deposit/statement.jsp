<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Account Statement" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
<ul class="fv-breadcrumb">
    <li><a href="${pageContext.request.contextPath}/dashboard"><i class="bi bi-speedometer2"></i> Home</a></li>
    <li><a href="${pageContext.request.contextPath}/deposit/accounts">CASA Accounts</a></li>
    <li><a href="${pageContext.request.contextPath}/deposit/view/${account.accountNumber}"><c:out value="${account.accountNumber}"/></a></li>
    <li class="active">Statement</li>
</ul>

<c:if test="${not empty error}"><div class="fv-alert alert alert-danger"><c:out value="${error}"/></div></c:if>

<div class="d-flex justify-content-between align-items-center mb-3">
    <h4><i class="bi bi-journal-text"></i> Account Statement: <c:out value="${account.accountNumber}"/></h4>
    <div>
        <button type="button" class="btn btn-sm btn-outline-secondary" onclick="window.print();" title="Print Statement"><i class="bi bi-printer"></i> Print <span class="fv-kbd">Ctrl+P</span></button>
        <a href="${pageContext.request.contextPath}/deposit/view/${account.accountNumber}" class="btn btn-sm btn-outline-secondary" data-fv-cancel="${pageContext.request.contextPath}/deposit/view/${account.accountNumber}"><i class="bi bi-arrow-left"></i> Back <span class="fv-kbd">F3</span></a>
    </div>
</div>

<div class="fv-card mb-3">
    <div class="card-body">
        <form method="get" action="${pageContext.request.contextPath}/deposit/statement/${account.accountNumber}" class="fv-form">
            <div class="row g-2 align-items-end">
                <div class="col-auto">
                    <label class="form-label">From Date</label>
                    <input type="date" name="fromDate" class="form-control" value="${fromDate}" required/>
                </div>
                <div class="col-auto">
                    <label class="form-label">To Date</label>
                    <input type="date" name="toDate" class="form-control" value="${toDate}" required/>
                </div>
                <div class="col-auto">
                    <label class="form-label">&nbsp;</label>
                    <button type="submit" class="btn btn-fv-primary d-block"><i class="bi bi-funnel"></i> Filter</button>
                </div>
                <div class="col-auto">
                    <label class="form-label">&nbsp;</label>
                    <a href="${pageContext.request.contextPath}/deposit/statement/${account.accountNumber}/export?fromDate=${fromDate}&toDate=${toDate}"
                       class="btn btn-outline-success d-block"><i class="bi bi-download"></i> Download CSV</a>
                </div>
            </div>
        </form>
    </div>
</div>

<div class="fv-card">
    <div class="card-header">
        Statement: <c:out value="${fromDate}"/> to <c:out value="${toDate}"/>
        | Customer: <c:out value="${account.customer.firstName}"/> <c:out value="${account.customer.lastName}"/>
        | Branch: <c:out value="${account.branch.branchCode}"/>
    </div>
    <div class="card-body">
        <div class="table-responsive">
        <table class="table fv-table fv-datatable table-sm">
            <thead>
                <tr>
                    <th>Date</th>
                    <th>Type</th>
                    <th>Channel</th>
                    <th>Narration</th>
                    <th>Counterparty</th>
                    <th class="text-end">Debit</th>
                    <th class="text-end">Credit</th>
                    <th class="text-end">Balance</th>
                    <th>Voucher</th>
                </tr>
            </thead>
            <tbody>
                <c:forEach var="t" items="${transactions}">
                    <tr class="${t.reversed ? 'table-secondary text-decoration-line-through' : ''}">
                        <td><c:out value="${t.valueDate}"/></td>
                        <td><c:out value="${t.transactionType}"/></td>
                        <td><c:out value="${t.channel}"/></td>
                        <td><c:out value="${t.narration}"/></td>
                        <td><c:out value="${t.counterpartyAccount}" default="--"/></td>
                        <td class="text-end text-danger"><c:if test="${t.debitCredit == 'DEBIT'}"><fmt:formatNumber value="${t.amount}" type="currency" currencyCode="INR"/></c:if></td>
                        <td class="text-end text-success"><c:if test="${t.debitCredit == 'CREDIT'}"><fmt:formatNumber value="${t.amount}" type="currency" currencyCode="INR"/></c:if></td>
                        <td class="text-end"><fmt:formatNumber value="${t.balanceAfter}" type="currency" currencyCode="INR"/></td>
                        <td><small class="font-monospace"><c:out value="${t.voucherNumber}"/></small></td>
                    </tr>
                </c:forEach>
                <c:if test="${empty transactions}">
                    <tr><td colspan="9" class="text-center text-muted">No transactions found for the selected period</td></tr>
                </c:if>
            </tbody>
        </table>
        </div>
    </div>
</div>
</div>

<%@ include file="../layout/footer.jsp" %>