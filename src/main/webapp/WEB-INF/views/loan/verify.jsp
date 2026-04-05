<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Verify Application" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <div class="fv-card">
        <div class="card-header">Application Details</div>
        <div class="card-body">
            <table class="table fv-table">
                <tbody>
                    <tr><td class="fw-bold">Application Number</td><td><c:out value="${application.applicationNumber}" /></td></tr>
                    <tr><td class="fw-bold">Customer</td><td><c:out value="${application.customer.fullName}" /></td></tr>
                    <tr><td class="fw-bold">Product Type</td><td><c:out value="${application.productType}" /></td></tr>
                    <tr><td class="fw-bold">Requested Amount</td><td class="amount"><c:out value="${application.requestedAmount}" /></td></tr>
                    <tr><td class="fw-bold">Interest Rate (%)</td><td><c:out value="${application.interestRate}" /></td></tr>
                    <tr><td class="fw-bold">Tenure (Months)</td><td><c:out value="${application.tenureMonths}" /></td></tr>
                    <tr><td class="fw-bold">KYC Status</td><td>
                        <c:choose>
                            <c:when test="${application.customer.kycVerified}"><span class="fv-badge fv-badge-active">KYC Verified</span></c:when>
                            <c:otherwise><span class="fv-badge fv-badge-rejected">KYC NOT Verified</span></c:otherwise>
                        </c:choose>
                    </td></tr>
                    <tr><td class="fw-bold">CIBIL Score</td><td><c:out value="${application.customer.cibilScore}" /></td></tr>
                </tbody>
            </table>
        </div>
    </div>

    <div class="fv-card">
        <div class="card-header">Verification Action</div>
        <div class="card-body">
            <form method="post" action="${pageContext.request.contextPath}/loan/verify/${application.id}" class="fv-form">
                <div class="mb-3">
                    <label for="remarks" class="form-label">Verification Remarks *</label>
                    <textarea name="remarks" id="remarks" class="form-control" rows="3" required placeholder="Enter verification remarks"></textarea>
                </div>
                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                <button type="submit" class="btn btn-success">Verify &amp; Approve for Next Stage</button>
                <a href="${pageContext.request.contextPath}/loan/applications" class="btn btn-outline-secondary ms-2">Cancel</a>
            </form>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
