<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Open CASA Account" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
<c:if test="${not empty error}"><div class="alert alert-danger"><c:out value="${error}"/></div></c:if>

<h4>Open CASA Account</h4>
<div class="card"><div class="card-body">
<form method="post" action="${pageContext.request.contextPath}/deposit/open">
    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>

    <div class="row mb-3">
    <div class="col-md-6">
        <label class="form-label">Customer <span class="text-danger">*</span></label>
        <select name="customerId" class="form-select" required>
            <option value="">-- Select Customer --</option>
            <c:forEach var="c" items="${customers}">
            <option value="${c.id}"><c:out value="${c.customerNumber}"/> - <c:out value="${c.firstName}"/> <c:out value="${c.lastName}"/></option>
            </c:forEach>
        </select>
    </div>
    <div class="col-md-6">
        <label class="form-label">Branch <span class="text-danger">*</span></label>
        <select name="branchId" class="form-select" required>
            <option value="">-- Select Branch --</option>
            <c:forEach var="b" items="${branches}">
            <option value="${b.id}"><c:out value="${b.branchCode}"/> - <c:out value="${b.branchName}"/></option>
            </c:forEach>
        </select>
    </div>
    </div>

    <div class="row mb-3">
    <div class="col-md-4">
        <label class="form-label">Account Type <span class="text-danger">*</span></label>
        <select name="accountType" class="form-select" required>
            <option value="SAVINGS">Savings Account (SB)</option>
            <option value="CURRENT">Current Account (CA)</option>
            <option value="SAVINGS_PMJDY">Savings - PMJDY (Zero Balance)</option>
            <option value="SAVINGS_NRI">Savings - NRE/NRO</option>
        </select>
    </div>
    <div class="col-md-4">
        <label class="form-label">Initial Deposit (INR)</label>
        <input type="number" name="initialDeposit" class="form-control" step="0.01" min="0" placeholder="0.00"/>
    </div>
    <div class="col-md-4">
        <label class="form-label">Product Code</label>
        <input type="text" name="productCode" class="form-control" placeholder="Auto from account type"/>
    </div>
    </div>

    <div class="row mb-3">
    <div class="col-md-6">
        <label class="form-label">Nominee Name</label>
        <input type="text" name="nomineeName" class="form-control" placeholder="Per RBI Deposit Insurance"/>
    </div>
    <div class="col-md-6">
        <label class="form-label">Nominee Relationship</label>
        <select name="nomineeRelationship" class="form-select">
            <option value="">-- None --</option>
            <option value="SPOUSE">Spouse</option>
            <option value="CHILD">Child</option>
            <option value="PARENT">Parent</option>
            <option value="SIBLING">Sibling</option>
            <option value="OTHER">Other</option>
        </select>
    </div>
    </div>

    <button type="submit" class="btn btn-primary"><i class="bi bi-check-circle"></i> Open Account</button>
    <a href="${pageContext.request.contextPath}/deposit/accounts" class="btn btn-secondary">Cancel</a>
</form>
</div></div>
</div>

<%@ include file="../layout/footer.jsp" %>
