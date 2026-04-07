<%@ page contentType="text/html;charset=UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<!DOCTYPE html>
<html><head><title>CASA Accounts - Finvanta CBS</title>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css"/>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap-icons@1.11.0/font/bootstrap-icons.css"/>
<link rel="stylesheet" href="${pageContext.request.contextPath}/css/finvanta.css"/>
</head><body><div class="fv-layout">
<jsp:include page="/WEB-INF/views/layout/sidebar.jsp"/>
<div class="fv-main">
<c:if test="${not empty success}"><div class="alert alert-success"><c:out value="${success}"/></div></c:if>
<c:if test="${not empty error}"><div class="alert alert-danger"><c:out value="${error}"/></div></c:if>

<div class="d-flex justify-content-between align-items-center mb-3">
    <h4>CASA Accounts</h4>
    <c:if test="${pageContext.request.isUserInRole('ROLE_CHECKER') || pageContext.request.isUserInRole('ROLE_ADMIN')}">
    <a href="${pageContext.request.contextPath}/deposit/open" class="btn btn-primary btn-sm"><i class="bi bi-plus-circle"></i> Open Account</a>
    </c:if>
</div>

<table class="table table-striped table-hover table-sm">
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
</div></div></body></html>
