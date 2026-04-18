<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="CASA Accounts" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
<ul class="fv-breadcrumb">
    <li><a href="${pageContext.request.contextPath}/dashboard"><i class="bi bi-speedometer2"></i> Home</a></li>
    <li class="active">CASA Accounts</li>
</ul>

<c:if test="${not empty success}"><div class="fv-alert alert alert-success"><c:out value="${success}"/></div></c:if>
<c:if test="${not empty error}"><div class="fv-alert alert alert-danger"><c:out value="${error}"/></div></c:if>

<div class="d-flex justify-content-between align-items-center mb-3">
    <h4><i class="bi bi-wallet2"></i> CASA Accounts</h4>
    <c:if test="${pageContext.request.isUserInRole('ROLE_CHECKER') || pageContext.request.isUserInRole('ROLE_ADMIN')}">
    <a href="${pageContext.request.contextPath}/deposit/open" class="btn btn-fv-primary btn-sm"><i class="bi bi-plus-circle"></i> Open Account</a>
    </c:if>
</div>

<!-- CBS: CASA Account search per Finacle ACCTINQ -->
<form method="get" action="${pageContext.request.contextPath}/deposit/search" class="row g-2 mb-3">
    <div class="col-auto">
        <input type="text" name="q" class="form-control form-control-sm fv-search-input" placeholder="Search by account no, CIF, customer name..." value="<c:out value='${searchQuery}'/>" minlength="2" />
    </div>
    <div class="col-auto">
        <button type="submit" class="btn btn-sm btn-fv-primary"><i class="bi bi-search"></i> Search</button>
    </div>
    <c:if test="${not empty searchQuery}">
    <div class="col-auto">
        <a href="${pageContext.request.contextPath}/deposit/accounts" class="btn btn-sm btn-outline-secondary">Clear</a>
    </div>
    </c:if>
</form>

<div class="table-responsive">
<table class="table fv-table fv-datatable table-sm">
<thead><tr>
    <th>Account No</th><th>Customer</th><th>Type</th><th>Branch</th>
    <th>Status</th><th class="text-end">Ledger Balance</th><th class="text-end">Available</th><th>Actions</th>
</tr></thead>
<tbody>
<c:forEach var="a" items="${accounts}">
<tr>
    <td><a href="${pageContext.request.contextPath}/deposit/view/${a.accountNumber}"><c:out value="${a.accountNumber}"/></a></td>
    <td><c:out value="${a.customer.firstName}"/> <c:out value="${a.customer.lastName}"/></td>
    <td><span class="badge ${a.savings ? 'bg-success' : 'bg-info'}"><c:out value="${a.accountType}"/></span></td>
    <td><c:out value="${a.branch.branchCode}"/></td>
    <td><span class="badge ${a.active ? 'bg-success' : a.frozen ? 'bg-danger' : a.dormant ? 'bg-warning' : 'bg-secondary'}"><c:out value="${a.accountStatus}"/></span></td>
    <td class="text-end"><fmt:formatNumber value="${a.ledgerBalance}" type="currency" currencyCode="INR"/></td>
    <td class="text-end"><fmt:formatNumber value="${a.effectiveAvailable}" type="currency" currencyCode="INR"/></td>
    <td>
        <a href="${pageContext.request.contextPath}/deposit/view/${a.accountNumber}" class="btn btn-outline-primary btn-sm">View</a>
        <c:if test="${a.active}">
        <a href="${pageContext.request.contextPath}/deposit/deposit/${a.accountNumber}" class="btn btn-outline-success btn-sm">Deposit</a>
        <a href="${pageContext.request.contextPath}/deposit/withdraw/${a.accountNumber}" class="btn btn-outline-warning btn-sm">Withdraw</a>
        </c:if>
    </td>
</tr>
</c:forEach>
<c:if test="${empty accounts}"><tr><td colspan="8" class="text-center text-muted">No CASA accounts found</td></tr></c:if>
</tbody></table>
</div>
</div>

<%@ include file="../layout/footer.jsp" %>
