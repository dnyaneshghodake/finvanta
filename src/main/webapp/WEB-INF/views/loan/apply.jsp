<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="form" uri="http://www.springframework.org/tags/form" %>
<c:set var="pageTitle" value="New Loan Application" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="main-content">
    <div class="top-bar">
        <h2>New Loan Application</h2>
        <div class="user-info">
            <span><c:out value="${pageContext.request.userPrincipal.name}" /></span>
            <a href="${pageContext.request.contextPath}/logout">Logout</a>
        </div>
    </div>
    <div class="content-area">
        <c:if test="${not empty error}">
            <div class="alert alert-error"><c:out value="${error}" /></div>
        </c:if>

        <div class="card">
            <h3>Loan Application Form</h3>
            <form method="post" action="${pageContext.request.contextPath}/loan/apply" id="loanForm">
                <div class="form-row">
                    <div class="form-group">
                        <label for="customerId">Customer *</label>
                        <select name="customerId" id="customerId" required>
                            <option value="">-- Select Customer --</option>
                            <c:forEach var="cust" items="${customers}">
                                <option value="${cust.id}"><c:out value="${cust.customerNumber}" /> - <c:out value="${cust.fullName}" /></option>
                            </c:forEach>
                        </select>
                    </div>
                    <div class="form-group">
                        <label for="branchId">Branch *</label>
                        <select name="branchId" id="branchId" required>
                            <option value="">-- Select Branch --</option>
                            <c:forEach var="branch" items="${branches}">
                                <option value="${branch.id}"><c:out value="${branch.branchCode}" /> - <c:out value="${branch.branchName}" /></option>
                            </c:forEach>
                        </select>
                    </div>
                </div>

                <div class="form-row">
                    <div class="form-group">
                        <label for="productType">Product Type *</label>
                        <select name="productType" id="productType" required>
                            <option value="">-- Select Product --</option>
                            <option value="TERM_LOAN">Term Loan</option>
                            <option value="HOME_LOAN">Home Loan</option>
                            <option value="PERSONAL_LOAN">Personal Loan</option>
                            <option value="VEHICLE_LOAN">Vehicle Loan</option>
                            <option value="EDUCATION_LOAN">Education Loan</option>
                            <option value="BUSINESS_LOAN">Business Loan</option>
                        </select>
                    </div>
                    <div class="form-group">
                        <label for="requestedAmount">Requested Amount (INR) *</label>
                        <input type="number" name="requestedAmount" id="requestedAmount" min="10000" max="50000000" step="1000" required placeholder="e.g., 1000000" />
                    </div>
                </div>

                <div class="form-row">
                    <div class="form-group">
                        <label for="interestRate">Interest Rate (% p.a.) *</label>
                        <input type="number" name="interestRate" id="interestRate" min="1" max="36" step="0.25" required placeholder="e.g., 10.50" />
                    </div>
                    <div class="form-group">
                        <label for="tenureMonths">Tenure (Months) *</label>
                        <input type="number" name="tenureMonths" id="tenureMonths" min="3" max="360" required placeholder="e.g., 120" />
                    </div>
                </div>

                <div class="form-group">
                    <label for="purpose">Purpose</label>
                    <textarea name="purpose" id="purpose" rows="3" placeholder="Purpose of the loan"></textarea>
                </div>

                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                <div style="margin-top: 20px;">
                    <button type="submit" class="btn btn-primary">Submit Application</button>
                    <a href="${pageContext.request.contextPath}/loan/applications" class="btn" style="margin-left: 8px;">Cancel</a>
                </div>
            </form>
        </div>
    </div>

<%@ include file="../layout/footer.jsp" %>

<script>
document.getElementById('loanForm').addEventListener('submit', function(e) {
    var amount = document.getElementById('requestedAmount').value;
    var rate = document.getElementById('interestRate').value;
    var tenure = document.getElementById('tenureMonths').value;
    if (amount < 10000 || amount > 50000000) {
        alert('Loan amount must be between INR 10,000 and INR 5,00,00,000');
        e.preventDefault();
    }
    if (rate < 1 || rate > 36) {
        alert('Interest rate must be between 1% and 36%');
        e.preventDefault();
    }
    if (tenure < 3 || tenure > 360) {
        alert('Tenure must be between 3 and 360 months');
        e.preventDefault();
    }
});
</script>
