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
                <div class="stat-label">Chain Integrity</div>
            </div>
        </div>
    </div>

    <div class="fv-card">
        <div class="card-header">Audit Trail</div>
        <div class="card-body">
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

<%@ include file="../layout/footer.jsp" %>
