<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Fund Transfer" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
<c:if test="${not empty success}"><div class="alert alert-success"><c:out value="${success}"/></div></c:if>
<c:if test="${not empty error}"><div class="alert alert-danger"><c:out value="${error}"/></div></c:if>

<h4>Internal Fund Transfer</h4>
<div class="card"><div class="card-body">
<form method="post" action="${pageContext.request.contextPath}/deposit/transfer">
    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
    <div class="row mb-3">
    <div class="col-md-6">
        <label class="form-label">From Account <span class="text-danger">*</span></label>
        <select name="fromAccount" class="form-select" required>
            <option value="">-- Select Source --</option>
            <c:forEach var="a" items="${accounts}">
            <option value="${a.accountNumber}"><c:out value="${a.accountNumber}"/> - <c:out value="${a.customer.firstName}"/> <c:out value="${a.customer.lastName}"/> (Bal: <fmt:formatNumber value="${a.ledgerBalance}" type="number" maxFractionDigits="2"/>)</option>
            </c:forEach>
        </select>
    </div>
    <div class="col-md-6">
        <label class="form-label">To Account <span class="text-danger">*</span></label>
        <select name="toAccount" class="form-select" required>
            <option value="">-- Select Target --</option>
            <c:forEach var="a" items="${accounts}">
            <option value="${a.accountNumber}"><c:out value="${a.accountNumber}"/> - <c:out value="${a.customer.firstName}"/> <c:out value="${a.customer.lastName}"/></option>
            </c:forEach>
        </select>
    </div>
    </div>
    <div class="row mb-3">
    <div class="col-md-6">
        <label class="form-label">Amount (INR) <span class="text-danger">*</span></label>
        <input type="number" name="amount" class="form-control" step="0.01" min="0.01" required/>
    </div>
    <div class="col-md-6">
        <label class="form-label">Narration</label>
        <input type="text" name="narration" class="form-control" placeholder="Fund transfer" maxlength="500"/>
    </div>
    </div>
    <button type="submit" class="btn btn-info" onclick="if(document.querySelector('[name=fromAccount]').value === document.querySelector('[name=toAccount]').value){alert('Source and target accounts cannot be the same.');return false;} return confirm('Confirm fund transfer? This will debit the source and credit the target immediately.');"><i class="bi bi-arrow-left-right"></i> Execute Transfer</button>
    <a href="${pageContext.request.contextPath}/deposit/accounts" class="btn btn-secondary">Cancel</a>
</form>
</div></div>
</div>

<%@ include file="../layout/footer.jsp" %>
