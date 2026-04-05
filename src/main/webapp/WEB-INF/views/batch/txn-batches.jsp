<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Transaction Batch Control" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <c:if test="${not empty success}">
        <div class="fv-alert alert alert-success"><c:out value="${success}" /></div>
    </c:if>
    <c:if test="${not empty error}">
        <div class="fv-alert alert alert-danger"><c:out value="${error}" /></div>
    </c:if>

    <!-- Open New Batch -->
    <div class="fv-card">
        <div class="card-header">Open New Transaction Batch — <c:out value="${businessDate}" /></div>
        <div class="card-body">
            <form method="post" action="${pageContext.request.contextPath}/batch/txn/open" class="fv-form">
                <input type="hidden" name="businessDate" value="${businessDate}" />
                <div class="row mb-3">
                    <div class="col-md-4">
                        <label class="form-label">Batch Name *</label>
                        <select name="batchName" class="form-select" required>
                            <option value="MORNING_BATCH">Morning Batch</option>
                            <option value="AFTERNOON_BATCH">Afternoon Batch</option>
                            <option value="EVENING_BATCH">Evening Batch</option>
                            <option value="CLEARING_BATCH">Clearing Batch</option>
                            <option value="SPECIAL_BATCH">Special Batch</option>
                        </select>
                    </div>
                    <div class="col-md-4">
                        <label class="form-label">Batch Type *</label>
                        <select name="batchType" class="form-select" required>
                            <option value="INTRA_DAY">Intra-Day</option>
                            <option value="CLEARING">Clearing (RTGS/NEFT)</option>
                            <option value="SYSTEM">System</option>
                        </select>
                    </div>
                    <div class="col-md-4 mt-4">
                        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                        <button type="submit" class="btn btn-fv-primary">Open Batch</button>
                    </div>
                </div>
            </form>
        </div>
    </div>

    <!-- Batch List -->
    <div class="fv-card">
        <div class="card-header">Batches for <c:out value="${businessDate}" /></div>
        <div class="card-body">
            <table class="table fv-table">
                <thead>
                    <tr>
                        <th>Batch Name</th>
                        <th>Type</th>
                        <th>Status</th>
                        <th>Opened By</th>
                        <th>Opened At</th>
                        <th>Txns</th>
                        <th class="text-end">Total Debit</th>
                        <th class="text-end">Total Credit</th>
                        <th>Closed By</th>
                        <th>Closed At</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="b" items="${batches}">
                        <tr>
                            <td class="fw-bold"><c:out value="${b.batchName}" /></td>
                            <td><c:out value="${b.batchType}" /></td>
                            <td>
                                <c:choose>
                                    <c:when test="${b.status == 'OPEN'}"><span class="fv-badge fv-badge-approved">OPEN</span></c:when>
                                    <c:when test="${b.status == 'CLOSED'}"><span class="fv-badge fv-badge-active">CLOSED</span></c:when>
                                    <c:otherwise><span class="fv-badge fv-badge-rejected"><c:out value="${b.status}" /></span></c:otherwise>
                                </c:choose>
                            </td>
                            <td><c:out value="${b.openedBy}" /></td>
                            <td><c:out value="${b.openedAt}" /></td>
                            <td><c:out value="${b.totalTransactions}" /></td>
                            <td class="amount"><fmt:formatNumber value="${b.totalDebit}" type="number" maxFractionDigits="2" /></td>
                            <td class="amount"><fmt:formatNumber value="${b.totalCredit}" type="number" maxFractionDigits="2" /></td>
                            <td><c:out value="${b.closedBy}" default="—" /></td>
                            <td><c:out value="${b.closedAt}" default="—" /></td>
                            <td>
                                <c:if test="${b.status == 'OPEN'}">
                                    <form method="post" action="${pageContext.request.contextPath}/batch/txn/close/${b.id}" class="d-inline">
                                        <input type="hidden" name="businessDate" value="${businessDate}" />
                                        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                                        <button type="submit" class="btn btn-sm btn-danger" data-confirm="Close this batch? This action is irreversible.">Close Batch</button>
                                    </form>
                                </c:if>
                            </td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty batches}">
                        <tr><td colspan="11" class="text-center text-muted">No batches for this business date</td></tr>
                    </c:if>
                </tbody>
            </table>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>