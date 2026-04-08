<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Edit Branch" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <div class="fv-card">
        <div class="card-header">Edit Branch &mdash; <c:out value="${branch.branchCode}" /> <a href="${pageContext.request.contextPath}/branch/view/${branch.id}" class="btn btn-sm btn-outline-secondary float-end"><i class="bi bi-x-circle"></i> Cancel</a></div>
        <div class="card-body">
            <form method="post" action="${pageContext.request.contextPath}/branch/edit/${branch.id}" class="fv-form">
                <!-- Branch code is immutable after creation -->
                <div class="row mb-3">
                    <div class="col-md-6"><label class="form-label">Branch Code</label><input type="text" class="form-control" value="${branch.branchCode}" disabled /></div>
                    <div class="col-md-6"><label class="form-label">Branch Name *</label><input type="text" name="branchName" class="form-control" value="${branch.branchName}" required /></div>
                </div>
                <div class="row mb-3">
                    <div class="col-md-6"><label class="form-label">IFSC Code</label><input type="text" name="ifscCode" class="form-control" value="${branch.ifscCode}" maxlength="11" /></div>
                    <div class="col-md-6"><label class="form-label">Region</label><input type="text" name="region" class="form-control" value="${branch.region}" /></div>
                </div>
                <div class="mb-3"><label class="form-label">Address</label><textarea name="address" class="form-control" rows="2">${branch.address}</textarea></div>
                <div class="row mb-3">
                    <div class="col-md-4"><label class="form-label">City</label><input type="text" name="city" class="form-control" value="${branch.city}" /></div>
                    <div class="col-md-4"><label class="form-label">State</label><input type="text" name="state" class="form-control" value="${branch.state}" /></div>
                    <div class="col-md-4"><label class="form-label">PIN Code</label><input type="text" name="pinCode" class="form-control" value="${branch.pinCode}" maxlength="6" /></div>
                </div>
                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                <button type="submit" class="btn btn-fv-primary mt-2">Save Changes</button>
            </form>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>