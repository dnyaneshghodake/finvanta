<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Customer Details" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="main-content">
    <div class="top-bar">
        <h2>Customer - <c:out value="${customer.customerNumber}" /></h2>
        <div class="user-info">
            <a href="${pageContext.request.contextPath}/customer/list">Back</a>
        </div>
    </div>
    <div class="content-area">
        <div class="card">
            <h3>Customer Details</h3>
            <table>
                <tr><td style="width:200px; font-weight:600;">Customer Number</td><td><c:out value="${customer.customerNumber}" /></td></tr>
                <tr><td style="font-weight:600;">Full Name</td><td><c:out value="${customer.fullName}" /></td></tr>
                <tr><td style="font-weight:600;">Date of Birth</td><td><c:out value="${customer.dateOfBirth}" /></td></tr>
                <tr><td style="font-weight:600;">PAN</td><td><c:out value="${customer.panNumber}" /></td></tr>
                <tr><td style="font-weight:600;">Aadhaar</td><td><c:out value="${customer.aadhaarNumber}" /></td></tr>
                <tr><td style="font-weight:600;">Mobile</td><td><c:out value="${customer.mobileNumber}" /></td></tr>
                <tr><td style="font-weight:600;">Email</td><td><c:out value="${customer.email}" /></td></tr>
                <tr><td style="font-weight:600;">Address</td><td><c:out value="${customer.address}" />, <c:out value="${customer.city}" />, <c:out value="${customer.state}" /> - <c:out value="${customer.pinCode}" /></td></tr>
                <tr><td style="font-weight:600;">KYC Status</td><td>
                    <c:choose>
                        <c:when test="${customer.kycVerified}"><span class="badge badge-active">Verified</span> (by <c:out value="${customer.kycVerifiedBy}" /> on <c:out value="${customer.kycVerifiedDate}" />)</c:when>
                        <c:otherwise><span class="badge badge-rejected">Not Verified</span></c:otherwise>
                    </c:choose>
                </td></tr>
                <tr><td style="font-weight:600;">CIBIL Score</td><td><c:out value="${customer.cibilScore}" /></td></tr>
                <tr><td style="font-weight:600;">Branch</td><td><c:out value="${customer.branch.branchCode}" /> - <c:out value="${customer.branch.branchName}" /></td></tr>
            </table>
        </div>
    </div>

<%@ include file="../layout/footer.jsp" %>
