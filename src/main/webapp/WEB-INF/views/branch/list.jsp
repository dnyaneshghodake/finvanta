<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Branches" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <ul class="fv-breadcrumb">
        <li><a href="${pageContext.request.contextPath}/dashboard"><i class="bi bi-speedometer2"></i> Home</a></li>
        <li class="active">Branches</li>
    </ul>

    <c:if test="${not empty success}">
        <div class="fv-alert alert alert-success"><c:out value="${success}" /></div>
    </c:if>
    <div class="fv-card">
        <div class="card-header"><i class="bi bi-building"></i> Branch List <a href="${pageContext.request.contextPath}/branch/add" class="btn btn-sm btn-fv-primary float-end"><i class="bi bi-plus-circle"></i> Add Branch</a></div>
        <div class="card-body">
            <!-- CBS: Branch search per Finacle BRNINQ -->
            <form method="get" action="${pageContext.request.contextPath}/branch/search" class="row g-2 mb-3">
                <div class="col-auto">
                    <input type="text" name="q" class="form-control form-control-sm fv-search-input" placeholder="Search by code, name, IFSC, city, zone, region, type..." value="<c:out value='${searchQuery}'/>" minlength="2" />
                </div>
                <div class="col-auto">
                    <button type="submit" class="btn btn-sm btn-fv-primary"><i class="bi bi-search"></i> Search</button>
                </div>
                <c:if test="${not empty searchQuery}">
                <div class="col-auto">
                    <a href="${pageContext.request.contextPath}/branch/list" class="btn btn-sm btn-outline-secondary">Clear</a>
                </div>
                </c:if>
            </form>
            <div class="table-responsive">
            <table class="table fv-table fv-datatable">
                <thead>
                    <tr>
                        <th>Branch Code</th>
                        <th>Branch Name</th>
                        <th>Type</th>
                        <th>IFSC</th>
                        <th>City</th>
                        <th>Zone</th>
                        <th>Region</th>
                        <th>Status</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="branch" items="${branches}">
                        <tr>
                            <td><a href="${pageContext.request.contextPath}/branch/view/${branch.id}" class="fw-bold"><c:out value="${branch.branchCode}" /></a></td>
                            <td><c:out value="${branch.branchName}" /></td>
                            <td>
                                <c:choose>
                                    <c:when test="${branch.branchType == 'HEAD_OFFICE'}"><span class="fv-badge fv-badge-npa">HO</span></c:when>
                                    <c:when test="${branch.branchType == 'ZONAL_OFFICE'}"><span class="fv-badge fv-badge-pending">ZO</span></c:when>
                                    <c:when test="${branch.branchType == 'REGIONAL_OFFICE'}"><span class="fv-badge fv-badge-closed">RO</span></c:when>
                                    <c:otherwise><span class="fv-badge fv-badge-approved">BRANCH</span></c:otherwise>
                                </c:choose>
                            </td>
                            <td><c:out value="${branch.ifscCode}" default="--" /></td>
                            <td><c:out value="${branch.city}" default="--" /></td>
                            <td><c:out value="${branch.zoneCode}" default="--" /></td>
                            <td><c:out value="${branch.regionCode}" default="--" /></td>
                            <td><span class="fv-badge fv-badge-active">Active</span></td>
                            <td><a href="${pageContext.request.contextPath}/branch/view/${branch.id}" class="btn btn-sm btn-fv-primary"><i class="bi bi-eye"></i> View</a></td>
                        </tr>
                    </c:forEach>
                </tbody>
            </table>
            </div>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
