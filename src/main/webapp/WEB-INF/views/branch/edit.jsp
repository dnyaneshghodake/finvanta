<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Edit Branch" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <c:if test="${not empty error}">
        <div class="fv-alert alert alert-danger"><c:out value="${error}" /></div>
    </c:if>
    <div class="fv-card">
        <div class="card-header"><i class="bi bi-pencil-square"></i> Edit Branch &mdash; <c:out value="${branch.branchCode}" /> <a href="${pageContext.request.contextPath}/branch/view/${branch.id}" class="btn btn-sm btn-outline-secondary float-end"><i class="bi bi-x-circle"></i> Cancel</a></div>
        <div class="card-body">
            <form method="post" action="${pageContext.request.contextPath}/branch/edit/${branch.id}" class="fv-form">
                <!-- Section: Identity (branchCode and branchType are IMMUTABLE — shown as disabled) -->
                <h6 class="text-muted border-bottom pb-1 mb-3"><i class="bi bi-tag"></i> Branch Identity <small class="text-muted">(Code &amp; Type are immutable)</small></h6>
                <div class="row mb-3">
                    <div class="col-md-3"><label class="form-label">Branch Code</label><input type="text" class="form-control" value="${branch.branchCode}" disabled /><small class="text-muted">Immutable</small></div>
                    <div class="col-md-3"><label class="form-label">Branch Type</label><input type="text" class="form-control" value="${branch.branchType}" disabled /><small class="text-muted">Immutable</small></div>
                    <div class="col-md-3"><label class="form-label">Branch Name *</label><input type="text" name="branchName" class="form-control" value="${branch.branchName}" required maxlength="200" /></div>
                    <div class="col-md-3"><label class="form-label">IFSC Code</label><input type="text" name="ifscCode" class="form-control" value="${branch.ifscCode}" maxlength="11" pattern="[A-Z]{4}0[A-Za-z0-9]{6}" title="11 chars: 4-letter prefix + 0 + 6-char code" /></div>
                </div>

                <!-- Section: Hierarchy (zone/region codes are mutable) -->
                <h6 class="text-muted border-bottom pb-1 mb-3"><i class="bi bi-diagram-3"></i> Hierarchy Codes</h6>
                <div class="row mb-3">
                    <div class="col-md-4"><label class="form-label">Zone Code</label><input type="text" name="zoneCode" class="form-control" value="${branch.zoneCode}" maxlength="20" placeholder="e.g., WEST" /></div>
                    <div class="col-md-4"><label class="form-label">Region Code</label><input type="text" name="regionCode" class="form-control" value="${branch.regionCode}" maxlength="20" placeholder="e.g., MH" /></div>
                    <div class="col-md-4"><label class="form-label">Region (Display)</label><input type="text" name="region" class="form-control" value="${branch.region}" maxlength="100" /></div>
                </div>

                <!-- Section: Location -->
                <h6 class="text-muted border-bottom pb-1 mb-3"><i class="bi bi-geo-alt"></i> Location Details</h6>
                <div class="mb-3"><label class="form-label">Address</label><textarea name="address" class="form-control" rows="2" maxlength="500"><c:out value="${branch.address}" /></textarea></div>
                <div class="row mb-3">
                    <div class="col-md-4"><label class="form-label">City</label><input type="text" name="city" class="form-control" value="${branch.city}" maxlength="100" /></div>
                    <div class="col-md-4"><label class="form-label">State</label><input type="text" name="state" class="form-control" value="${branch.state}" maxlength="100" /></div>
                    <div class="col-md-4"><label class="form-label">PIN Code</label><input type="text" name="pinCode" class="form-control" value="${branch.pinCode}" maxlength="6" pattern="[0-9]{6}" title="6-digit PIN code" /></div>
                </div>

                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                <button type="submit" class="btn btn-fv-primary mt-2" data-confirm="Save changes to this branch?"><i class="bi bi-check-circle"></i> Save Changes</button>
            </form>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>