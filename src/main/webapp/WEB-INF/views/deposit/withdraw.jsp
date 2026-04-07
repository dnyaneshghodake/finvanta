<%@ page contentType="text/html;charset=UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<!DOCTYPE html>
<html><head><title>Cash Withdrawal - Finvanta CBS</title>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css"/>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap-icons@1.11.0/font/bootstrap-icons.css"/>
<link rel="stylesheet" href="${pageContext.request.contextPath}/css/finvanta.css"/>
</head><body><div class="fv-layout">
<jsp:include page="/WEB-INF/views/layout/sidebar.jsp"/>
<div class="fv-main">
<c:if test="${not empty error}"><div class="alert alert-danger"><c:out value="${error}"/></div></c:if>

<h4>Cash Withdrawal</h4>
<div class="card"><div class="card-body">
    <p>Account: <strong><c:out value="${account.accountNumber}"/></strong>
    | Customer: <c:out value="${account.customer.firstName}"/> <c:out value="${account.customer.lastName}"/>
    | Available Balance: <strong><fmt:formatNumber value="${account.effectiveAvailable}" type="currency" currencyCode="INR"/></strong></p>

    <form method="post" action="${pageContext.request.contextPath}/deposit/withdraw/${account.accountNumber}">
        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
        <div class="row mb-3">
        <div class="col-md-6">
            <label class="form-label">Amount (INR) <span class="text-danger">*</span></label>
            <input type="number" name="amount" class="form-control" step="0.01" min="0.01" required autofocus/>
            <small class="text-muted">Max available: <fmt:formatNumber value="${account.effectiveAvailable}" type="currency" currencyCode="INR"/></small>
        </div>
        <div class="col-md-6">
            <label class="form-label">Narration</label>
            <input type="text" name="narration" class="form-control" placeholder="Cash withdrawal" maxlength="500"/>
        </div>
        </div>
        <button type="submit" class="btn btn-warning"><i class="bi bi-dash-circle"></i> Post Withdrawal</button>
        <a href="${pageContext.request.contextPath}/deposit/view/${account.accountNumber}" class="btn btn-secondary">Cancel</a>
    </form>
</div></div>
</div></div></body></html>
