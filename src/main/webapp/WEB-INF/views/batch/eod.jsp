<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="EOD Processing" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <c:if test="${not empty success}">
        <div class="fv-alert alert alert-success"><c:out value="${success}" /></div>
    </c:if>
    <c:if test="${not empty error}">
        <div class="fv-alert alert alert-danger"><c:out value="${error}" /></div>
    </c:if>

    <div class="fv-card">
        <div class="card-header">Run EOD Batch</div>
        <div class="card-body">
            <c:if test="${not empty currentBusinessDate}">
                <p class="mb-3">Current Business Date: <strong><c:out value="${currentBusinessDate.businessDate}" /></strong>
                    <c:if test="${currentBusinessDate.eodComplete}">
                        <span class="fv-badge fv-badge-active">EOD Complete</span>
                    </c:if>
                </p>
            </c:if>
            <form method="post" action="${pageContext.request.contextPath}/batch/eod/run" class="fv-form">
                <div class="row mb-3">
                    <div class="col-md-4">
                        <label for="businessDate" class="form-label">Business Date *</label>
                        <input type="date" name="businessDate" id="businessDate" class="form-control" required />
                    </div>
                </div>
                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                <button type="submit" class="btn btn-warning" data-confirm="Are you sure you want to run EOD batch?">
                    Run EOD Batch
                </button>
            </form>
        </div>
    </div>

    <div class="fv-card">
        <div class="card-header">Batch History</div>
        <div class="card-body">
            <table class="table fv-table fv-datatable">
                <thead>
                    <tr>
                        <th>Job Name</th>
                        <th>Business Date</th>
                        <th>Status</th>
                        <th>Step</th>
                        <th>Total</th>
                        <th>Processed</th>
                        <th>Failed</th>
                        <th>Started</th>
                        <th>Completed</th>
                        <th>Initiated By</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="job" items="${batchHistory}">
                        <tr>
                            <td><c:out value="${job.jobName}" /></td>
                            <td><c:out value="${job.businessDate}" /></td>
                            <td>
                                <c:choose>
                                    <c:when test="${job.status == 'COMPLETED'}"><span class="fv-badge fv-badge-active"><c:out value="${job.status}" /></span></c:when>
                                    <c:when test="${job.status == 'PARTIALLY_COMPLETED'}"><span class="fv-badge fv-badge-pending"><c:out value="${job.status}" /></span></c:when>
                                    <c:when test="${job.status == 'FAILED'}"><span class="fv-badge fv-badge-rejected"><c:out value="${job.status}" /></span></c:when>
                                    <c:when test="${job.status == 'RUNNING'}"><span class="fv-badge fv-badge-approved"><c:out value="${job.status}" /></span></c:when>
                                    <c:otherwise><span class="fv-badge"><c:out value="${job.status}" /></span></c:otherwise>
                                </c:choose>
                            </td>
                            <td><c:out value="${job.stepName}" /></td>
                            <td><c:out value="${job.totalRecords}" /></td>
                            <td><c:out value="${job.processedRecords}" /></td>
                            <td>
                                <c:choose>
                                    <c:when test="${job.failedRecords > 0}"><span class="text-danger fw-bold"><c:out value="${job.failedRecords}" /></span></c:when>
                                    <c:otherwise><c:out value="${job.failedRecords}" /></c:otherwise>
                                </c:choose>
                            </td>
                            <td><c:out value="${job.startedAt}" /></td>
                            <td><c:out value="${job.completedAt}" /></td>
                            <td><c:out value="${job.initiatedBy}" /></td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty batchHistory}">
                        <tr><td colspan="10" class="text-center text-muted">No batch jobs executed</td></tr>
                    </c:if>
                </tbody>
            </table>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
