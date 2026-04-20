<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Add Branch" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <ul class="fv-breadcrumb">
        <li><a href="${pageContext.request.contextPath}/dashboard"><i class="bi bi-speedometer2"></i> Home</a></li>
        <li><a href="${pageContext.request.contextPath}/branch/list">Branches</a></li>
        <li class="active">Add Branch</li>
    </ul>

    <c:if test="${not empty error}">
        <div class="fv-alert alert alert-danger"><c:out value="${error}" /></div>
    </c:if>
    <div class="fv-card">
        <div class="card-header"><i class="bi bi-building-add"></i> Add New Branch <div class="float-end"><a href="${pageContext.request.contextPath}/branch/list" class="btn btn-sm btn-outline-secondary" data-fv-cancel="${pageContext.request.contextPath}/branch/list"><i class="bi bi-arrow-left"></i> Back <span class="fv-kbd">F3</span></a></div></div>
        <div class="card-body">
            <p class="text-muted small mb-3">Per Finacle BRANCH_MASTER / RBI Banking Regulation Act 1949 Section 23: every branch must be licensed by RBI. Branch code is immutable after creation.</p>
            <form method="post" action="${pageContext.request.contextPath}/branch/add" class="fv-form">
                <!-- Section: Identity -->
                <div class="fv-section-header" onclick=""><i class="bi bi-tag"></i> Branch Identity <i class="bi bi-chevron-down fv-chevron"></i></div>
                <div class="fv-section-body">
                <div class="row mb-3">
                    <div class="col-md-3 fv-mandatory-group">
                        <label class="form-label fv-required">Branch Code</label><span class="fv-help-icon" data-fv-help="Per Finacle BRANCH_MASTER: alphanumeric, 1-20 chars. IMMUTABLE after creation — correction requires branch closure.">?</span>
                        <input type="text" name="branchCode" class="form-control" required maxlength="20" pattern="[A-Za-z0-9_]{1,20}" title="Alphanumeric, 1-20 chars" placeholder="e.g., BR001" tabindex="1" />
                        <small class="text-muted">Immutable after creation</small>
                    </div>
                    <div class="col-md-5 fv-mandatory-group"><label class="form-label fv-required">Branch Name</label><input type="text" name="branchName" class="form-control" required maxlength="200" placeholder="e.g., Mumbai Main Branch" tabindex="2" /></div>
                    <div class="col-md-4"><label class="form-label">IFSC Code</label><span class="fv-help-icon" data-fv-help="Per RBI: 11-char code — 4-letter bank prefix + 0 + 6-char branch code. Used for NEFT/RTGS/IMPS.">?</span><input type="text" name="ifscCode" class="form-control" data-fv-type="ifsc" maxlength="11" pattern="[A-Z]{4}0[A-Za-z0-9]{6}" title="11 chars: 4-letter prefix + 0 + 6-char code" placeholder="e.g., FNVT0000001" tabindex="3" /></div>
                </div>
                </div><%-- end Identity section body --%>

                <!-- Section: Hierarchy -->
                <div class="fv-section-header" onclick=""><i class="bi bi-diagram-3"></i> Branch Hierarchy <i class="bi bi-chevron-down fv-chevron"></i></div>
                <div class="fv-section-body">
                <div class="row mb-3">
                    <div class="col-md-3 fv-mandatory-group">
                        <label class="form-label fv-required">Branch Type</label><span class="fv-help-icon" data-fv-help="Per Finacle: HEAD_OFFICE, ZONAL_OFFICE, REGIONAL_OFFICE, or BRANCH. IMMUTABLE after creation.">?</span>
                        <select name="branchType" class="form-select" required>
                            <c:forEach var="bt" items="${branchTypes}">
                                <option value="${bt}" ${bt == 'BRANCH' ? 'selected' : ''}><c:out value="${bt}" /></option>
                            </c:forEach>
                        </select>
                        <small class="text-muted">Immutable after creation</small>
                    </div>
                    <div class="col-md-3">
                        <label class="form-label">Parent Branch</label>
                        <select name="parentBranch.id" class="form-select">
                            <option value="">-- None (Root) --</option>
                            <c:forEach var="pb" items="${parentBranches}">
                                <option value="${pb.id}"><c:out value="${pb.branchCode}" /> &mdash; <c:out value="${pb.branchName}" /></option>
                            </c:forEach>
                        </select>
                        <small class="text-muted">HO has no parent</small>
                    </div>
                    <div class="col-md-3">
                        <label class="form-label">Zone Code</label>
                        <input type="text" name="zoneCode" class="form-control" maxlength="20" placeholder="e.g., WEST" />
                    </div>
                    <div class="col-md-3">
                        <label class="form-label">Region Code</label>
                        <input type="text" name="regionCode" class="form-control" maxlength="20" placeholder="e.g., MH" />
                    </div>
                </div>

                </div><%-- end Hierarchy section body --%>

                <!-- Section: Location -->
                <div class="fv-section-header" onclick=""><i class="bi bi-geo-alt"></i> Location Details <i class="bi bi-chevron-down fv-chevron"></i></div>
                <div class="fv-section-body">
                <div class="mb-3"><label class="form-label">Address</label><textarea name="address" class="form-control" rows="2" maxlength="500"></textarea></div>
                <div class="row mb-3">
                    <div class="col-md-3"><label class="form-label">City</label><input type="text" name="city" class="form-control" maxlength="100" /></div>
                    <div class="col-md-3"><label class="form-label">State</label><input type="text" name="state" class="form-control" maxlength="100" /></div>
                    <div class="col-md-3"><label class="form-label">PIN Code</label><input type="text" name="pinCode" class="form-control" data-fv-type="pincode" maxlength="6" pattern="[0-9]{6}" title="6-digit PIN code" /></div>
                    <div class="col-md-3"><label class="form-label">Region (Display)</label><input type="text" name="region" class="form-control" maxlength="100" placeholder="e.g., Western India" /></div>
                </div>
                </div><%-- end Location section body --%>

                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                <button type="submit" class="btn btn-fv-primary mt-2" data-confirm="Create this branch? Branch code and type cannot be changed after creation."><i class="bi bi-plus-circle"></i> Add Branch <span class="fv-kbd">F2</span></button>
            </form>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
