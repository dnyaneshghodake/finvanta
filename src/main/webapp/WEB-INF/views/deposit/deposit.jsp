<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Cash Deposit" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
<c:if test="${not empty error}"><div class="alert alert-danger"><c:out value="${error}"/></div></c:if>

<h4>Cash Deposit</h4>
<div class="card"><div class="card-body">
    <p>Account: <strong><c:out value="${account.accountNumber}"/></strong>
    | Customer: <c:out value="${account.customer.firstName}"/> <c:out value="${account.customer.lastName}"/>
    | Current Balance: <strong><fmt:formatNumber value="${account.ledgerBalance}" type="currency" currencyCode="INR"/></strong></p>

    <form method="post" action="${pageContext.request.contextPath}/deposit/deposit/${account.accountNumber}">
        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
        <div class="row mb-3">
        <div class="col-md-6">
            <label class="form-label">Amount (INR) <span class="text-danger">*</span></label>
            <input type="number" name="amount" class="form-control" step="0.01" min="0.01" required autofocus/>
        </div>
        <div class="col-md-6">
            <label class="form-label">Narration</label>
            <input type="text" name="narration" class="form-control" placeholder="Cash deposit" maxlength="500"/>
        </div>
        </div>
        <button type="submit" class="btn btn-success" data-confirm="Confirm cash deposit? This will credit the account immediately."><i class="bi bi-plus-circle"></i> Post Deposit</button>
        <a href="${pageContext.request.contextPath}/deposit/view/${account.accountNumber}" class="btn btn-secondary">Cancel</a>
    </form>
</div></div>
</div>

<%@ include file="../layout/footer.jsp" %>
