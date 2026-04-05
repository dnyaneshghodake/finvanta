<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Approve Application" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="main-content">
    <div class="top-bar">
        <h2>Approve Application - <c:out value="${application.applicationNumber}" /></h2>
        <div class="user-info">
            <a href="${pageContext.request.contextPath}/loan/applications">Back</a>
        </div>
    </div>
    <div class="content-area">
        <div class="card">
            <h3>Application Summary</h3>
            <table>
                <tr><td style="width:200px; font-weight:600;">Application No.</td><td><c:out value="${application.applicationNumber}" /></td></tr>
                <tr><td style="font-weight:600;">Customer</td><td><c:out value="${application.customer.fullName}" /> (<c:out value="${application.customer.customerNumber}" />)</td></tr>
                <tr><td style="font-weight:600;">Product Type</td><td><c:out value="${application.productType}" /></td></tr>
                <tr><td style="font-weight:600;">Requested Amount</td><td class="amount"><fmt:formatNumber value="${application.requestedAmount}" type="number" maxFractionDigits="2" /> INR</td></tr>
                <tr><td style="font-weight:600;">Interest Rate</td><td><fmt:formatNumber value="${application.interestRate}" type="number" maxFractionDigits="2" />% p.a.</td></tr>
                <tr><td style="font-weight:600;">Tenure</td><td><c:out value="${application.tenureMonths}" /> months</td></tr>
                <tr><td style="font-weight:600;">KYC Status</td><td style="color: ${application.customer.kycVerified ? '#2e7d32' : '#c62828'}; font-weight: bold;">${application.customer.kycVerified ? 'Verified' : 'NOT Verified'}</td></tr>
                <tr><td style="font-weight:600;">CIBIL Score</td><td><c:out value="${application.customer.cibilScore}" /></td></tr>
                <tr><td style="font-weight:600;">Verified By</td><td><c:out value="${application.verifiedBy}" /></td></tr>
                <tr><td style="font-weight:600;">Verification Date</td><td><c:out value="${application.verifiedDate}" /></td></tr>
            </table>
        </div>

        <div class="card">
            <h3>Approval Decision</h3>
            <form method="post" action="${pageContext.request.contextPath}/loan/approve/${application.id}">
                <div class="form-group">
                    <label for="remarks">Approval Remarks *</label>
                    <textarea name="remarks" id="remarks" rows="3" required placeholder="Enter approval remarks"></textarea>
                </div>
                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                <button type="submit" class="btn btn-success">Approve Application</button>
            </form>
            <hr style="margin: 16px 0;" />
            <form method="post" action="${pageContext.request.contextPath}/loan/reject/${application.id}">
                <div class="form-group">
                    <label for="reason">Rejection Reason</label>
                    <textarea name="reason" id="reason" rows="2" placeholder="Enter reason for rejection"></textarea>
                </div>
                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                <button type="submit" class="btn btn-danger" onclick="return confirm('Are you sure you want to reject this application?')">Reject Application</button>
            </form>
        </div>
    </div>

<%@ include file="../layout/footer.jsp" %>
