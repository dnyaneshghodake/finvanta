<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="EOD Processing" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="main-content">
    <div class="top-bar">
        <h2>End of Day (EOD) Processing</h2>
        <div class="user-info"></div>
    </div>
    <div class="content-area">
        <c:if test="${not empty success}">
            <div class="alert alert-success"><c:out value="${success}" /></div>
        </c:if>
        <c:if test="${not empty error}">
            <div class="alert alert-error"><c:out value="${error}" /></div>
        </c:if>

        <div class="card">
            <h3>Run EOD Batch</h3>
            <c:if test="${not empty currentBusinessDate}">
                <p style="margin-bottom: 12px;">Current Business Date: <strong><c:out value="${currentBusinessDate.businessDate}" /></strong>
                    <c:if test="${currentBusinessDate.eodComplete}">
                        <span class="badge badge-active">EOD Complete</span>
                    </c:if>
                </p>
            </c:if>
            <form method="post" action="${pageContext.request.contextPath}/batch/eod/run">
                <div class="form-row">
                    <div class="form-group">
                        <label for="businessDate">Business Date *</label>
                        <input type="date" name="businessDate" id="businessDate" required />
                    </div>
                </div>
                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                <button type="submit" class="btn btn-warning" onclick="return confirm('Are you sure you want to run EOD batch?')">
                    Run EOD Batch
                </button>
            </form>
        </div>

        <div class="card">
            <h3>Batch History</h3>
            <table>
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
                                    <c:when test="${job.status == 'COMPLETED'}"><span class="badge badge-active"><c:out value="${job.status}" /></span></c:when>
                                    <c:when test="${job.status == 'FAILED'}"><span class="badge badge-rejected"><c:out value="${job.status}" /></span></c:when>
                                    <c:when test="${job.status == 'RUNNING'}"><span class="badge badge-pending"><c:out value="${job.status}" /></span></c:when>
                                    <c:otherwise><span class="badge"><c:out value="${job.status}" /></span></c:otherwise>
                                </c:choose>
                            </td>
                            <td><c:out value="${job.stepName}" /></td>
                            <td><c:out value="${job.totalRecords}" /></td>
                            <td><c:out value="${job.processedRecords}" /></td>
                            <td style="color: ${job.failedRecords > 0 ? '#c62828' : ''}"><c:out value="${job.failedRecords}" /></td>
                            <td><c:out value="${job.startedAt}" /></td>
                            <td><c:out value="${job.completedAt}" /></td>
                            <td><c:out value="${job.initiatedBy}" /></td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty batchHistory}">
                        <tr><td colspan="10" style="text-align: center; color: #999;">No batch jobs executed</td></tr>
                    </c:if>
                </tbody>
            </table>
        </div>
    </div>

<%@ include file="../layout/footer.jsp" %>
