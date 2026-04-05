<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Customer Details" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <div class="fv-card">
        <div class="card-header">Customer Details <a href="${pageContext.request.contextPath}/customer/list" class="btn btn-sm btn-outline-secondary float-end">Back</a></div>
        <div class="card-body">
            <table class="table fv-table">
                <tbody>
                <tr><td class="fw-bold">Customer Number</td><td><c:out value="${customer.customerNumber}" /></td></tr>
                <tr><td class="fw-bold">Full Name</td><td><c:out value="${customer.fullName}" /></td></tr>
                <tr><td class="fw-bold">Date of Birth</td><td><c:out value="${customer.dateOfBirth}" /></td></tr>
                <tr><td class="fw-bold">PAN</td><td><c:out value="${customer.panNumber}" /></td></tr>
                <tr><td class="fw-bold">Aadhaar</td><td><c:out value="${customer.aadhaarNumber}" /></td></tr>
                <tr><td class="fw-bold">Mobile</td><td><c:out value="${customer.mobileNumber}" /></td></tr>
                <tr><td class="fw-bold">Email</td><td><c:out value="${customer.email}" /></td></tr>
                <tr><td class="fw-bold">Address</td><td><c:out value="${customer.address}" />, <c:out value="${customer.city}" />, <c:out value="${customer.state}" /> - <c:out value="${customer.pinCode}" /></td></tr>
                <tr><td class="fw-bold">KYC Status</td><td>
                    <c:choose>
                        <c:when test="${customer.kycVerified}"><span class="fv-badge fv-badge-active">Verified</span> (by <c:out value="${customer.kycVerifiedBy}" /> on <c:out value="${customer.kycVerifiedDate}" />)</c:when>
                        <c:otherwise><span class="fv-badge fv-badge-rejected">Not Verified</span></c:otherwise>
                    </c:choose>
                </td></tr>
                <tr><td class="fw-bold">CIBIL Score</td><td><c:out value="${customer.cibilScore}" /></td></tr>
                <tr><td class="fw-bold">Branch</td><td><c:out value="${customer.branch.branchCode}" /> - <c:out value="${customer.branch.branchName}" /></td></tr>
                </tbody>
            </table>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
