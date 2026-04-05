<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="form" uri="http://www.springframework.org/tags/form" %>
<c:set var="pageTitle" value="New Loan Application" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <c:if test="${not empty error}">
        <div class="fv-alert alert alert-danger"><c:out value="${error}" /></div>
    </c:if>

    <div class="fv-card">
        <div class="card-header">Loan Application Form</div>
        <div class="card-body">
            <form method="post" action="${pageContext.request.contextPath}/loan/apply" class="fv-form" id="loanForm">
                <div class="row mb-3">
                    <div class="col-md-6">
                        <label for="customerId" class="form-label">Customer *</label>
                        <select name="customerId" id="customerId" class="form-select" required>
                            <option value="">-- Select Customer --</option>
                            <c:forEach var="cust" items="${customers}">
                                <option value="${cust.id}"><c:out value="${cust.customerNumber}" /> - <c:out value="${cust.fullName}" /></option>
                            </c:forEach>
                        </select>
                    </div>
                    <div class="col-md-6">
                        <label for="branchId" class="form-label">Branch *</label>
                        <select name="branchId" id="branchId" class="form-select" required>
                            <option value="">-- Select Branch --</option>
                            <c:forEach var="branch" items="${branches}">
                                <option value="${branch.id}"><c:out value="${branch.branchCode}" /> - <c:out value="${branch.branchName}" /></option>
                            </c:forEach>
                        </select>
                    </div>
                </div>

                <div class="row mb-3">
                    <div class="col-md-6">
                        <label for="productType" class="form-label">Product Type *</label>
                        <select name="productType" id="productType" class="form-select" required>
                            <option value="">-- Select Product --</option>
                            <c:forEach var="product" items="${products}">
                                <option value="${product.productCode}"><c:out value="${product.productCode}" /> — <c:out value="${product.productName}" /></option>
                            </c:forEach>
                        </select>
                    </div>
                    <div class="col-md-6">
                        <label for="requestedAmount" class="form-label">Requested Amount (INR) *</label>
                        <input type="number" name="requestedAmount" id="requestedAmount" class="form-control" min="10000" max="50000000" step="1000" required placeholder="e.g., 1000000" />
                    </div>
                </div>

                <div class="row mb-3">
                    <div class="col-md-6">
                        <label for="interestRate" class="form-label">Interest Rate (% p.a.) *</label>
                        <input type="number" name="interestRate" id="interestRate" class="form-control" min="1" max="36" step="0.25" required placeholder="e.g., 10.50" />
                    </div>
                    <div class="col-md-6">
                        <label for="tenureMonths" class="form-label">Tenure (Months) *</label>
                        <input type="number" name="tenureMonths" id="tenureMonths" class="form-control" min="3" max="360" required placeholder="e.g., 120" />
                    </div>
                </div>

                <div class="row mb-3">
                    <div class="col-md-4">
                        <label for="penalRate" class="form-label">Penal Rate (% p.a.)</label>
                        <input type="number" name="penalRate" id="penalRate" class="form-control" min="0" max="24" step="0.25" placeholder="e.g., 2.00" />
                    </div>
                    <div class="col-md-4">
                        <label for="riskCategory" class="form-label">Risk Category</label>
                        <select name="riskCategory" id="riskCategory" class="form-select">
                            <option value="">-- Auto --</option>
                            <option value="LOW">Low</option>
                            <option value="MEDIUM">Medium</option>
                            <option value="HIGH">High</option>
                            <option value="VERY_HIGH">Very High</option>
                        </select>
                    </div>
                    <div class="col-md-4">
                        <label for="collateralReference" class="form-label">Collateral Reference</label>
                        <input type="text" name="collateralReference" id="collateralReference" class="form-control" placeholder="e.g., PROP-MUM-001" />
                    </div>
                </div>

                <div class="mb-3">
                    <label for="purpose" class="form-label">Purpose</label>
                    <textarea name="purpose" id="purpose" class="form-control" rows="3" placeholder="Purpose of the loan"></textarea>
                </div>

                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                <div class="mt-3">
                    <button type="submit" class="btn btn-fv-primary">Submit Application</button>
                    <a href="${pageContext.request.contextPath}/loan/applications" class="btn btn-outline-secondary ms-2">Cancel</a>
                </div>
            </form>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
