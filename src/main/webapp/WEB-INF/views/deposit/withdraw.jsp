<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Cash Withdrawal" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
<ul class="fv-breadcrumb">
    <li><a href="${pageContext.request.contextPath}/dashboard"><i class="bi bi-speedometer2"></i> Home</a></li>
    <li><a href="${pageContext.request.contextPath}/deposit/accounts">CASA Accounts</a></li>
    <li><a href="${pageContext.request.contextPath}/deposit/view/${account.accountNumber}"><c:out value="${account.accountNumber}"/></a></li>
    <li class="active">Cash Withdrawal</li>
</ul>

<c:if test="${not empty error}"><div class="fv-alert alert alert-danger"><c:out value="${error}"/></div></c:if>

<h4><i class="bi bi-dash-circle"></i> Cash Withdrawal</h4>
<div class="card"><div class="card-body">
    <p>Account: <strong><c:out value="${account.accountNumber}"/></strong>
    | Customer: <c:out value="${account.customer.firstName}"/> <c:out value="${account.customer.lastName}"/>
    | Available Balance: <strong><fmt:formatNumber value="${account.effectiveAvailable}" type="currency" currencyCode="INR"/></strong></p>

    <form method="post" action="${pageContext.request.contextPath}/deposit/withdraw/${account.accountNumber}" class="fv-form">
        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
        <div class="row mb-3">
        <div class="col-md-6">
            <label class="form-label">Amount (INR) <span class="text-danger">*</span></label>
            <input type="number" name="amount" class="form-control" data-fv-type="amount" min="0.01" required autofocus/>
            <small class="text-muted">Max available: <fmt:formatNumber value="${account.effectiveAvailable}" type="currency" currencyCode="INR"/></small>
        </div>
        <div class="col-md-6">
            <label class="form-label">Narration</label>
            <input type="text" name="narration" class="form-control" placeholder="Cash withdrawal" maxlength="500"/>
        </div>
        </div>
        <button type="submit" class="btn btn-warning" data-confirm="Confirm cash withdrawal? This will debit the account immediately."><i class="bi bi-dash-circle"></i> Post Withdrawal</button>
        <a href="${pageContext.request.contextPath}/deposit/view/${account.accountNumber}" class="btn btn-secondary">Cancel</a>
    </form>
</div></div>
</div>

<%@ include file="../layout/footer.jsp" %>
