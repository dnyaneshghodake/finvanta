<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Verify Application" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="main-content">
    <div class="top-bar">
        <h2>Verify Application - <c:out value="${application.applicationNumber}" /></h2>
        <div class="user-info">
            <a href="${pageContext.request.contextPath}/loan/applications">Back to Applications</a>
        </div>
    </div>
    <div class="content-area">
        <div class="card">
            <h3>Application Details</h3>
            <div class="form-row">
                <div class="form-group">
                    <label>Application Number</label>
                    <input type="text" value="${application.applicationNumber}" disabled />
                </div>
                <div class="form-group">
                    <label>Customer</label>
                    <input type="text" value="${application.customer.fullName}" disabled />
                </div>
            </div>
            <div class="form-row">
                <div class="form-group">
                    <label>Product Type</label>
                    <input type="text" value="${application.productType}" disabled />
                </div>
                <div class="form-group">
                    <label>Requested Amount</label>
                    <input type="text" value="${application.requestedAmount}" disabled class="amount" />
                </div>
            </div>
            <div class="form-row">
                <div class="form-group">
                    <label>Interest Rate (%)</label>
                    <input type="text" value="${application.interestRate}" disabled />
                </div>
                <div class="form-group">
                    <label>Tenure (Months)</label>
                    <input type="text" value="${application.tenureMonths}" disabled />
                </div>
            </div>
            <div class="form-group">
                <label>KYC Status</label>
                <input type="text" value="${application.customer.kycVerified ? 'KYC Verified' : 'KYC NOT Verified'}" disabled
                       style="color: ${application.customer.kycVerified ? '#2e7d32' : '#c62828'}; font-weight: bold;" />
            </div>
            <div class="form-group">
                <label>CIBIL Score</label>
                <input type="text" value="${application.customer.cibilScore}" disabled />
            </div>
        </div>

        <div class="card">
            <h3>Verification Action</h3>
            <form method="post" action="${pageContext.request.contextPath}/loan/verify/${application.id}">
                <div class="form-group">
                    <label for="remarks">Verification Remarks *</label>
                    <textarea name="remarks" id="remarks" rows="3" required placeholder="Enter verification remarks"></textarea>
                </div>
                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                <button type="submit" class="btn btn-success">Verify & Approve for Next Stage</button>
                <a href="${pageContext.request.contextPath}/loan/applications" class="btn" style="margin-left: 8px;">Cancel</a>
            </form>
        </div>
    </div>

<%@ include file="../layout/footer.jsp" %>
