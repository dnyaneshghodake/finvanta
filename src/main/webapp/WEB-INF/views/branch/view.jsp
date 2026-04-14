<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Branch Details" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <c:if test="${not empty success}">
        <div class="fv-alert alert alert-success"><c:out value="${success}" /></div>
    </c:if>

    <div class="row g-3 mb-3">
        <div class="col"><div class="fv-stat-card"><div class="stat-value"><c:out value="${customers.size()}" default="0" /></div><div class="stat-label">Customers</div></div></div>
        <div class="col"><div class="fv-stat-card"><div class="stat-value"><c:out value="${activeCount}" default="0" /></div><div class="stat-label">Active Accounts</div></div></div>
        <div class="col"><div class="fv-stat-card"><div class="stat-value amount"><fmt:formatNumber value="${totalOutstanding}" type="number" maxFractionDigits="0" /></div><div class="stat-label">Outstanding (INR)</div></div></div>
        <div class="col"><div class="fv-stat-card stat-warning"><div class="stat-value"><c:out value="${smaCount}" default="0" /></div><div class="stat-label">SMA Accounts</div></div></div>
        <div class="col"><div class="fv-stat-card stat-danger"><div class="stat-value"><c:out value="${npaCount}" default="0" /></div><div class="stat-label">NPA Accounts</div></div></div>
    </div>

    <div class="fv-card">
        <div class="card-header">Branch Information
            <div class="float-end">
                <a href="${pageContext.request.contextPath}/branch/list" class="btn btn-sm btn-outline-secondary"><i class="bi bi-arrow-left"></i> Back</a>
                <c:if test="${pageContext.request.isUserInRole('ROLE_ADMIN')}">
                <a href="${pageContext.request.contextPath}/branch/edit/${branch.id}" class="btn btn-sm btn-fv-primary"><i class="bi bi-pencil"></i> Edit</a>
                </c:if>
            </div>
        </div>
        <div class="card-body">
            <div class="row">
                <div class="col-md-6">
                    <h6 class="text-muted border-bottom pb-1 mb-2"><i class="bi bi-tag"></i> Identity</h6>
                    <table class="table fv-table table-sm">
                        <tbody>
                        <tr><td class="fw-bold" style="width:40%">Branch Code</td><td><c:out value="${branch.branchCode}" /></td></tr>
                        <tr><td class="fw-bold">Branch Name</td><td><c:out value="${branch.branchName}" /></td></tr>
                        <tr><td class="fw-bold">IFSC Code</td><td><c:out value="${branch.ifscCode}" default="--" /></td></tr>
                        <tr><td class="fw-bold">Status</td><td><c:choose><c:when test="${branch.active}"><span class="fv-badge fv-badge-active">Active</span></c:when><c:otherwise><span class="fv-badge fv-badge-rejected">Inactive</span></c:otherwise></c:choose></td></tr>
                        </tbody>
                    </table>
                </div>
                <div class="col-md-6">
                    <h6 class="text-muted border-bottom pb-1 mb-2"><i class="bi bi-diagram-3"></i> Hierarchy</h6>
                    <table class="table fv-table table-sm">
                        <tbody>
                        <tr><td class="fw-bold" style="width:40%">Branch Type</td><td>
                            <c:choose>
                                <c:when test="${branch.branchType == 'HEAD_OFFICE'}"><span class="fv-badge fv-badge-npa">HEAD OFFICE</span></c:when>
                                <c:when test="${branch.branchType == 'ZONAL_OFFICE'}"><span class="fv-badge fv-badge-pending">ZONAL OFFICE</span></c:when>
                                <c:when test="${branch.branchType == 'REGIONAL_OFFICE'}"><span class="fv-badge fv-badge-closed">REGIONAL OFFICE</span></c:when>
                                <c:otherwise><span class="fv-badge fv-badge-approved">BRANCH</span></c:otherwise>
                            </c:choose>
                        </td></tr>
                        <tr><td class="fw-bold">Parent Branch</td><td><c:choose><c:when test="${not empty branch.parentBranch}"><a href="${pageContext.request.contextPath}/branch/view/${branch.parentBranch.id}"><c:out value="${branch.parentBranch.branchCode}" /></a> &mdash; <c:out value="${branch.parentBranch.branchName}" /></c:when><c:otherwise><span class="text-muted">-- Root (No Parent) --</span></c:otherwise></c:choose></td></tr>
                        <tr><td class="fw-bold">Zone Code</td><td><c:out value="${branch.zoneCode}" default="--" /></td></tr>
                        <tr><td class="fw-bold">Region Code</td><td><c:out value="${branch.regionCode}" default="--" /></td></tr>
                        </tbody>
                    </table>
                </div>
            </div>
            <h6 class="text-muted border-bottom pb-1 mb-2 mt-2"><i class="bi bi-geo-alt"></i> Location</h6>
            <table class="table fv-table table-sm">
                <tbody>
                <tr><td class="fw-bold" style="width:20%">Address</td><td><c:out value="${branch.address}" default="--" /></td></tr>
                <tr><td class="fw-bold">City / State / PIN</td><td><c:out value="${branch.city}" default="--" /> / <c:out value="${branch.state}" default="--" /> - <c:out value="${branch.pinCode}" default="--" /></td></tr>
                <tr><td class="fw-bold">Region (Display)</td><td><c:out value="${branch.region}" default="--" /></td></tr>
                </tbody>
            </table>
        </div>
    </div>

    <!-- CBS Branch Loan Portfolio -->
    <div class="fv-card">
        <div class="card-header">Loan Accounts at this Branch</div>
        <div class="card-body">
            <div class="table-responsive">
            <table class="table fv-table fv-datatable">
                <thead>
                    <tr>
                        <th>Account No.</th>
                        <th>Customer</th>
                        <th>Product</th>
                        <th class="text-end">Outstanding</th>
                        <th>DPD</th>
                        <th>Status</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="acc" items="${loanAccounts}">
                        <tr>
                            <td><a href="${pageContext.request.contextPath}/loan/account/${acc.accountNumber}"><c:out value="${acc.accountNumber}" /></a></td>
                            <td><a href="${pageContext.request.contextPath}/customer/view/${acc.customer.id}"><c:out value="${acc.customer.fullName}" /></a></td>
                            <td><c:out value="${acc.productType}" /></td>
                            <td class="amount"><fmt:formatNumber value="${acc.outstandingPrincipal}" type="number" maxFractionDigits="2" /></td>
                            <td>
                                <c:choose>
                                    <c:when test="${acc.daysPastDue > 90}"><span class="fv-badge fv-badge-npa"><c:out value="${acc.daysPastDue}" /></span></c:when>
                                    <c:when test="${acc.daysPastDue > 0}"><span class="fv-badge fv-badge-pending"><c:out value="${acc.daysPastDue}" /></span></c:when>
                                    <c:otherwise>0</c:otherwise>
                                </c:choose>
                            </td>
                            <td>
                                <c:choose>
                                    <c:when test="${acc.status.npa}"><span class="fv-badge fv-badge-npa"><c:out value="${acc.status}" /></span></c:when>
                                    <c:when test="${acc.status.sma}"><span class="fv-badge fv-badge-pending"><c:out value="${acc.status}" /></span></c:when>
                                    <c:when test="${acc.status.terminal}"><span class="fv-badge fv-badge-closed"><c:out value="${acc.status}" /></span></c:when>
                                    <c:otherwise><span class="fv-badge fv-badge-active"><c:out value="${acc.status}" /></span></c:otherwise>
                                </c:choose>
                            </td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty loanAccounts}">
                        <tr><td colspan="6" class="text-center text-muted">No loan accounts at this branch</td></tr>
                    </c:if>
                </tbody>
            </table>
            </div>
        </div>
    </div>

    <div class="fv-card">
        <div class="card-header">Customers at this Branch</div>
        <div class="card-body">
            <div class="table-responsive">
            <table class="table fv-table fv-datatable">
                <thead>
                    <tr><th>Customer No.</th><th>Name</th><th>Type</th><th>KYC</th><th>CIBIL</th><th>Actions</th></tr>
                </thead>
                <tbody>
                    <c:forEach var="cust" items="${customers}">
                        <tr>
                            <td><c:out value="${cust.customerNumber}" /></td>
                            <td><c:out value="${cust.fullName}" /></td>
                            <td><c:out value="${cust.customerType}" /></td>
                            <td><c:choose><c:when test="${cust.kycVerified}"><span class="fv-badge fv-badge-active">Verified</span></c:when><c:otherwise><span class="fv-badge fv-badge-rejected">Pending</span></c:otherwise></c:choose></td>
                            <td><c:out value="${cust.cibilScore}" /></td>
                            <td><a href="${pageContext.request.contextPath}/customer/view/${cust.id}" class="btn btn-sm btn-fv-primary"><i class="bi bi-eye"></i> View</a></td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty customers}">
                        <tr><td colspan="6" class="text-center text-muted">No customers at this branch</td></tr>
                    </c:if>
                </tbody>
            </table>
            </div>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>