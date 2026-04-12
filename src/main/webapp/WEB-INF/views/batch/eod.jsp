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

    <!-- === EOD Trial + Apply per Finacle EOD_TRIAL / Temenos COB_VERIFY === -->
    <div class="fv-card">
        <div class="card-header"><i class="bi bi-clipboard-check"></i> EOD Processing (Trial &amp; Apply)</div>
        <div class="card-body">
            <c:if test="${not empty currentBusinessDate}">
                <p class="mb-3">Current Business Date: <strong><c:out value="${currentBusinessDate.businessDate}" /></strong>
                    <c:if test="${currentBusinessDate.eodComplete}">
                        <span class="fv-badge fv-badge-active">EOD Complete</span>
                    </c:if>
                </p>
            </c:if>
            <p class="text-muted small">Per Finacle EOD_TRIAL / Temenos COB_VERIFY: run Trial first to validate all pre-conditions, then Apply to execute EOD.</p>

            <!-- Step 1: Trial Run Form -->
            <form method="post" action="${pageContext.request.contextPath}/batch/eod/trial" class="fv-form mb-3">
                <div class="row align-items-end">
                    <div class="col-md-4">
                        <label for="businessDate" class="form-label">Business Date *</label>
                        <input type="date" name="businessDate" id="businessDate" class="form-control" required
                               value="<c:out value='${trialDate}' />" />
                    </div>
                    <div class="col-md-4">
                        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                        <button type="submit" class="btn btn-info"><i class="bi bi-search"></i> Run Trial (Validate)</button>
                    </div>
                </div>
            </form>

            <!-- Trial Results Checklist -->
            <c:if test="${not empty trialResults}">
                <div class="fv-card mb-3" style="border:2px solid ${trialClean ? '#198754' : '#dc3545'};">
                    <div class="card-header" style="background:${trialClean ? '#d1e7dd' : '#f8d7da'};color:${trialClean ? '#0f5132' : '#842029'};">
                        <i class="bi ${trialClean ? 'bi-check-circle-fill' : 'bi-exclamation-triangle-fill'}"></i>
                        EOD Trial Results for <c:out value="${trialDate}" />
                        &mdash; <c:choose>
                            <c:when test="${trialClean}"><strong>ALL CHECKS PASSED</strong> &mdash; Ready to Apply</c:when>
                            <c:otherwise><strong>BLOCKERS FOUND</strong> &mdash; Resolve before Apply</c:otherwise>
                        </c:choose>
                    </div>
                    <div class="card-body p-0">
                        <table class="table table-sm mb-0">
                            <thead><tr><th style="width:30px;"></th><th>Category</th><th>Check</th><th>Status</th></tr></thead>
                            <tbody>
                                <c:forEach var="check" items="${trialResults}">
                                    <tr>
                                        <td class="text-center">
                                            <c:choose>
                                                <c:when test="${check.passed()}"><i class="bi bi-check-circle-fill text-success"></i></c:when>
                                                <c:when test="${check.severity() == 'BLOCKER'}"><i class="bi bi-x-circle-fill text-danger"></i></c:when>
                                                <c:otherwise><i class="bi bi-exclamation-triangle-fill text-warning"></i></c:otherwise>
                                            </c:choose>
                                        </td>
                                        <td><strong><c:out value="${check.category()}" /></strong></td>
                                        <td><c:out value="${check.description()}" /></td>
                                        <td>
                                            <c:choose>
                                                <c:when test="${check.passed()}"><span class="fv-badge fv-badge-active">PASS</span></c:when>
                                                <c:when test="${check.severity() == 'BLOCKER'}"><span class="fv-badge fv-badge-rejected">BLOCKER</span></c:when>
                                                <c:otherwise><span class="fv-badge fv-badge-pending">WARNING</span></c:otherwise>
                                            </c:choose>
                                        </td>
                                    </tr>
                                </c:forEach>
                            </tbody>
                        </table>
                    </div>
                </div>

                <!-- Step 2: Apply Button (gated by trial results) -->
                <c:choose>
                    <c:when test="${trialClean}">
                        <form method="post" action="${pageContext.request.contextPath}/batch/eod/apply">
                            <input type="hidden" name="businessDate" value="<c:out value='${trialDate}' />" />
                            <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                            <button type="submit" class="btn btn-warning btn-lg" id="applyEodBtn"
                                    data-date="<c:out value='${trialDate}' />">
                                <i class="bi bi-play-circle"></i> Apply EOD for <c:out value="${trialDate}" />
                            </button>
                        </form>
                        <script>
                            document.getElementById('applyEodBtn').addEventListener('click', function(e) {
                                var dt = this.getAttribute('data-date');
                                if (!confirm('Execute EOD for ' + dt + '? This will process all accounts. Continue?')) {
                                    e.preventDefault();
                                }
                            });
                        </script>
                    </c:when>
                    <c:otherwise>
                        <button class="btn btn-secondary btn-lg" disabled title="Resolve all BLOCKER checks before Apply">
                            <i class="bi bi-lock"></i> Apply EOD (Blocked &mdash; resolve issues above)
                        </button>
                    </c:otherwise>
                </c:choose>
            </c:if>
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
