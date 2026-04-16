<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Audit Logs" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <div class="row g-3 mb-3">
        <div class="col-auto">
            <div class="fv-stat-card ${chainIntegrity ? 'stat-success' : 'stat-danger'}">
                <div class="stat-value"><c:choose><c:when test="${chainIntegrity}">INTACT</c:when><c:otherwise>VIOLATED</c:otherwise></c:choose></div>
                <div class="stat-label">
                    <c:choose>
                        <c:when test="${fullChainVerified}">Full Chain Verified</c:when>
                        <c:otherwise>Recent Window (500)</c:otherwise>
                    </c:choose>
                </div>
            </div>
        </div>
        <div class="col-auto d-flex align-items-center">
            <c:if test="${empty fullChainVerified}">
                <a href="${pageContext.request.contextPath}/audit/verify" class="btn btn-sm btn-outline-primary"
                   onclick="return confirm('Run full O(N) chain verification? This may take several minutes on large audit tables.');">
                    <i class="bi bi-shield-check"></i> Verify Full Chain
                </a>
            </c:if>
            <c:if test="${fullChainVerified}">
                <span class="badge bg-success"><i class="bi bi-shield-fill-check"></i> Full chain walk completed</span>
            </c:if>
        </div>
    </div>

    <div class="fv-card">
        <div class="card-header">Audit Trail</div>
        <div class="card-body">
            <!-- CBS: Audit Trail search per RBI IT Governance Direction 2023 §8.3 -->
            <form method="get" action="${pageContext.request.contextPath}/audit/search" class="row g-2 mb-3">
                <div class="col-auto">
                    <input type="text" name="q" class="form-control form-control-sm" placeholder="Search by entity, action, user, module..." value="<c:out value='${searchQuery}'/>" minlength="2" style="width:320px;" />
                </div>
                <div class="col-auto">
                    <input type="date" name="fromDate" class="form-control form-control-sm" value="<c:out value='${fromDate}'/>" title="From date" style="width:150px;" />
                </div>
                <div class="col-auto">
                    <input type="date" name="toDate" class="form-control form-control-sm" value="<c:out value='${toDate}'/>" title="To date" style="width:150px;" />
                </div>
                <div class="col-auto">
                    <button type="submit" class="btn btn-sm btn-fv-primary"><i class="bi bi-search"></i> Search</button>
                </div>
                <c:if test="${not empty searchQuery}">
                <div class="col-auto">
                    <a href="${pageContext.request.contextPath}/audit/logs" class="btn btn-sm btn-outline-secondary">Clear</a>
                </div>
                </c:if>
            </form>
            <div class="table-responsive">
            <table class="table fv-table fv-datatable">
                <thead>
                    <tr>
                        <th>ID</th>
                        <th>Timestamp</th>
                        <th>Entity</th>
                        <th>Entity ID</th>
                        <th>Action</th>
                        <th>Module</th>
                        <th>Performed By</th>
                        <th>Description</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="log" items="${auditLogs}">
                        <tr>
                            <td><c:out value="${log.id}" /></td>
                            <td><c:out value="${log.eventTimestamp}" /></td>
                            <td><c:out value="${log.entityType}" /></td>
                            <td><c:out value="${log.entityId}" /></td>
                            <td><c:out value="${log.action}" /></td>
                            <td><c:out value="${log.module}" /></td>
                            <td><c:out value="${log.performedBy}" /></td>
                            <td><c:out value="${log.description}" /></td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty auditLogs}">
                        <tr><td colspan="8" class="text-center text-muted">No audit records found</td></tr>
                    </c:if>
                </tbody>
            </table>
            </div>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
