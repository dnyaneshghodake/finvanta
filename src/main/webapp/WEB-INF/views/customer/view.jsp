<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Customer Details" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <c:if test="${not empty success}">
        <div class="fv-alert alert alert-success"><c:out value="${success}" /></div>
    </c:if>
    <c:if test="${not empty error}">
        <div class="fv-alert alert alert-danger"><c:out value="${error}" /></div>
    </c:if>

    <div class="fv-card">
        <div class="card-header">Customer Details <a href="${pageContext.request.contextPath}/customer/list" class="btn btn-sm btn-outline-secondary float-end">Back</a></div>
        <div class="card-body">
            <table class="table fv-table">
                <tbody>
                <tr><td class="fw-bold">Customer Number</td><td><c:out value="${customer.customerNumber}" /></td></tr>
                <tr><td class="fw-bold">Full Name</td><td><c:out value="${customer.fullName}" /></td></tr>
                <tr><td class="fw-bold">Customer Type</td><td><c:out value="${customer.customerType}" /></td></tr>
                <tr><td class="fw-bold">Date of Birth</td><td><c:out value="${customer.dateOfBirth}" /></td></tr>
                <tr><td class="fw-bold">PAN</td><td><c:out value="${customer.panNumber}" /></td></tr>
                <tr><td class="fw-bold">Aadhaar</td><td><c:out value="${customer.aadhaarNumber}" /></td></tr>
                <tr><td class="fw-bold">Mobile</td><td><c:out value="${customer.mobileNumber}" /></td></tr>
                <tr><td class="fw-bold">Email</td><td><c:out value="${customer.email}" /></td></tr>
                <tr><td class="fw-bold">Address</td><td><c:out value="${customer.address}" />, <c:out value="${customer.city}" />, <c:out value="${customer.state}" /> - <c:out value="${customer.pinCode}" /></td></tr>
                <tr><td class="fw-bold">KYC Status</td><td>
                    <c:choose>
                        <c:when test="${customer.kycVerified}"><span class="fv-badge fv-badge-active">Verified</span> (by <c:out value="${customer.kycVerifiedBy}" /> on <c:out value="${customer.kycVerifiedDate}" />)</c:when>
                        <c:otherwise>
                            <span class="fv-badge fv-badge-rejected">Not Verified</span>
                            <form method="post" action="${pageContext.request.contextPath}/customer/verify-kyc/${customer.id}" class="d-inline ms-2">
                                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                                <button type="submit" class="btn btn-sm btn-success" data-confirm="Confirm KYC verification for this customer?">Verify KYC</button>
                            </form>
                        </c:otherwise>
                    </c:choose>
                </td></tr>
                <tr><td class="fw-bold">CIBIL Score</td><td><c:out value="${customer.cibilScore}" /></td></tr>
                <tr><td class="fw-bold">Branch</td><td><c:out value="${customer.branch.branchCode}" /> - <c:out value="${customer.branch.branchName}" /></td></tr>
                <tr><td class="fw-bold">Status</td><td>
                    <c:choose>
                        <c:when test="${customer.active}"><span class="fv-badge fv-badge-active">Active</span></c:when>
                        <c:otherwise><span class="fv-badge fv-badge-rejected">Inactive</span></c:otherwise>
                    </c:choose>
                </td></tr>
                </tbody>
            </table>
        </div>
    </div>

    <!-- Loan Applications for this Customer -->
    <div class="fv-card">
        <div class="card-header">Loan Applications</div>
        <div class="card-body">
            <table class="table fv-table">
                <thead>
                    <tr>
                        <th>App No.</th>
                        <th>Product</th>
                        <th class="text-end">Amount</th>
                        <th>Rate</th>
                        <th>Status</th>
                        <th>Date</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="app" items="${loanApplications}">
                        <tr>
                            <td><c:out value="${app.applicationNumber}" /></td>
                            <td><c:out value="${app.productType}" /></td>
                            <td class="amount"><fmt:formatNumber value="${app.requestedAmount}" type="number" maxFractionDigits="2" /></td>
                            <td><fmt:formatNumber value="${app.interestRate}" type="number" maxFractionDigits="2" />%</td>
                            <td><span class="fv-badge fv-badge-pending"><c:out value="${app.status}" /></span></td>
                            <td><c:out value="${app.applicationDate}" /></td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty loanApplications}">
                        <tr><td colspan="6" class="text-center text-muted">No loan applications</td></tr>
                    </c:if>
                </tbody>
            </table>
        </div>
    </div>

    <!-- Loan Accounts for this Customer -->
    <div class="fv-card">
        <div class="card-header">Loan Accounts</div>
        <div class="card-body">
            <table class="table fv-table">
                <thead>
                    <tr>
                        <th>Account No.</th>
                        <th>Product</th>
                        <th class="text-end">Sanctioned</th>
                        <th class="text-end">Outstanding</th>
                        <th>DPD</th>
                        <th>Status</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="acc" items="${loanAccounts}">
                        <tr>
                            <td><a href="${pageContext.request.contextPath}/loan/account/${acc.accountNumber}"><c:out value="${acc.accountNumber}" /></a></td>
                            <td><c:out value="${acc.productType}" /></td>
                            <td class="amount"><fmt:formatNumber value="${acc.sanctionedAmount}" type="number" maxFractionDigits="2" /></td>
                            <td class="amount"><fmt:formatNumber value="${acc.outstandingPrincipal}" type="number" maxFractionDigits="2" /></td>
                            <td><c:out value="${acc.daysPastDue}" /></td>
                            <td><span class="fv-badge ${acc.status.npa() ? 'fv-badge-npa' : 'fv-badge-active'}"><c:out value="${acc.status}" /></span></td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty loanAccounts}">
                        <tr><td colspan="6" class="text-center text-muted">No loan accounts</td></tr>
                    </c:if>
                </tbody>
            </table>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
