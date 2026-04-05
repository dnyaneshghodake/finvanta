<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Add Branch" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <div class="fv-card">
        <div class="card-header">Branch Details <a href="${pageContext.request.contextPath}/branch/list" class="btn btn-sm btn-outline-secondary float-end">Back</a></div>
        <div class="card-body">
            <form method="post" action="${pageContext.request.contextPath}/branch/add" class="fv-form">
                <div class="row mb-3">
                    <div class="col-md-6"><label class="form-label">Branch Code *</label><input type="text" name="branchCode" class="form-control" required maxlength="20" /></div>
                    <div class="col-md-6"><label class="form-label">Branch Name *</label><input type="text" name="branchName" class="form-control" required /></div>
                </div>
                <div class="row mb-3">
                    <div class="col-md-6"><label class="form-label">IFSC Code</label><input type="text" name="ifscCode" class="form-control" maxlength="11" /></div>
                    <div class="col-md-6"><label class="form-label">Region</label><input type="text" name="region" class="form-control" /></div>
                </div>
                <div class="mb-3"><label class="form-label">Address</label><textarea name="address" class="form-control" rows="2"></textarea></div>
                <div class="row mb-3">
                    <div class="col-md-4"><label class="form-label">City</label><input type="text" name="city" class="form-control" /></div>
                    <div class="col-md-4"><label class="form-label">State</label><input type="text" name="state" class="form-control" /></div>
                    <div class="col-md-4"><label class="form-label">PIN Code</label><input type="text" name="pinCode" class="form-control" maxlength="6" /></div>
                </div>
                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                <button type="submit" class="btn btn-fv-primary mt-2">Add Branch</button>
            </form>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
