<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Approve Application" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <ul class="fv-breadcrumb">
        <li><a href="${pageContext.request.contextPath}/dashboard"><i class="bi bi-speedometer2"></i> Home</a></li>
        <li><a href="${pageContext.request.contextPath}/loan/applications">Loan Applications</a></li>
        <li class="active">Approve &mdash; <c:out value="${application.applicationNumber}" /></li>
    </ul>

    <div class="fv-card">
        <div class="card-header"><i class="bi bi-check2-square"></i> Application Summary &mdash; <c:out value="${application.applicationNumber}" /> <div class="float-end"><a href="${pageContext.request.contextPath}/loan/applications" class="btn btn-sm btn-outline-secondary" data-fv-cancel="${pageContext.request.contextPath}/loan/applications"><i class="bi bi-arrow-left"></i> Back <span class="fv-kbd">F3</span></a></div></div>
        <div class="card-body">
            <table class="table fv-table">
                <tbody>
                <tr><td class="fw-bold">Application No.</td><td><c:out value="${application.applicationNumber}" /></td></tr>
                <tr><td class="fw-bold">Customer</td><td><a href="${pageContext.request.contextPath}/customer/view/${application.customer.id}"><c:out value="${application.customer.fullName}" /></a> (<c:out value="${application.customer.customerNumber}" />)</td></tr>
                <tr><td class="fw-bold">Branch</td><td><c:out value="${application.branch.branchCode}" /> - <c:out value="${application.branch.branchName}" /></td></tr>
                <tr><td class="fw-bold">Product Type</td><td><c:out value="${application.productType}" /></td></tr>
                <tr><td class="fw-bold">Requested Amount</td><td class="amount"><fmt:formatNumber value="${application.requestedAmount}" type="number" maxFractionDigits="2" /> INR</td></tr>
                <tr><td class="fw-bold">Interest Rate</td><td><fmt:formatNumber value="${application.interestRate}" type="number" maxFractionDigits="2" />% p.a.</td></tr>
                <tr><td class="fw-bold">Penal Rate</td><td><c:out value="${application.penalRate}" default="--" />% p.a.</td></tr>
                <tr><td class="fw-bold">Tenure</td><td><c:out value="${application.tenureMonths}" /> months</td></tr>
                <tr><td class="fw-bold">Purpose</td><td><c:out value="${application.purpose}" default="--" /></td></tr>
                <tr><td class="fw-bold">Risk Category</td><td><c:out value="${application.riskCategory}" default="--" /></td></tr>
                <tr><td class="fw-bold">Collateral</td><td><c:out value="${application.collateralReference}" default="Unsecured" /></td></tr>
                <tr><td class="fw-bold">KYC Status</td><td>
                    <c:choose>
                        <c:when test="${application.customer.kycVerified}"><span class="fv-badge fv-badge-active">Verified</span></c:when>
                        <c:otherwise><span class="fv-badge fv-badge-rejected">NOT Verified</span></c:otherwise>
                    </c:choose>
                </td></tr>
                <tr><td class="fw-bold">CIBIL Score</td><td><c:out value="${application.customer.cibilScore}" /></td></tr>
                <tr><td class="fw-bold">Verified By</td><td><c:out value="${application.verifiedBy}" /></td></tr>
                <tr><td class="fw-bold">Verification Date</td><td><c:out value="${application.verifiedDate}" /></td></tr>
                </tbody>
            </table>
        </div>
    </div>

    <div class="fv-card">
        <div class="card-header">Approval Decision</div>
        <div class="card-body">
            <form method="post" action="${pageContext.request.contextPath}/loan/approve/${application.id}" class="fv-form">
                <div class="mb-3">
                    <label for="remarks" class="form-label">Approval Remarks *</label>
                    <textarea name="remarks" id="remarks" class="form-control" rows="3" required placeholder="Enter approval remarks"></textarea>
                </div>
                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                <button type="submit" class="btn btn-success" data-confirm="Approve this loan application for INR ${application.requestedAmount}? This action creates a loan account."><i class="bi bi-check-circle"></i> Approve Application</button>
            </form>
            <hr class="my-3" />
            <form method="post" action="${pageContext.request.contextPath}/loan/reject/${application.id}" class="fv-form">
                <div class="mb-3">
                    <label for="reason" class="form-label">Rejection Reason *</label>
                    <textarea name="reason" id="reason" class="form-control" rows="2" required placeholder="Mandatory per RBI Fair Practices Code"></textarea>
                </div>
                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                <button type="submit" class="btn btn-danger" data-confirm="Are you sure you want to reject this application?">Reject Application</button>
            </form>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
