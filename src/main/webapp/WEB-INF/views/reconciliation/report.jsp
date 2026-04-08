<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="GL Reconciliation Report" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <!-- Summary -->
    <div class="row g-3 mb-3">
        <div class="col">
            <div class="fv-stat-card ${reconResult.isBalanced ? 'stat-success' : 'stat-danger'}">
                <div class="stat-value"><c:choose><c:when test="${reconResult.isBalanced}">BALANCED</c:when><c:otherwise>IMBALANCED</c:otherwise></c:choose></div>
                <div class="stat-label">Reconciliation Status</div>
            </div>
        </div>
        <div class="col">
            <div class="fv-stat-card">
                <div class="stat-value amount"><fmt:formatNumber value="${reconResult.totalGlDebit}" type="number" maxFractionDigits="2" /></div>
                <div class="stat-label">Total GL Debit</div>
            </div>
        </div>
        <div class="col">
            <div class="fv-stat-card">
                <div class="stat-value amount"><fmt:formatNumber value="${reconResult.totalGlCredit}" type="number" maxFractionDigits="2" /></div>
                <div class="stat-label">Total GL Credit</div>
            </div>
        </div>
        <div class="col">
            <div class="fv-stat-card ${reconResult.varianceCount > 0 ? 'stat-danger' : 'stat-success'}">
                <div class="stat-value"><c:out value="${reconResult.varianceCount}" /></div>
                <div class="stat-label">Variances</div>
            </div>
        </div>
    </div>

    <!-- Variance Details -->
    <c:if test="${not empty reconResult.variances}">
    <div class="fv-card">
        <div class="card-header text-danger">GL Variances &mdash; Requires Resolution Before Day Close</div>
        <div class="card-body">
            <div class="table-responsive">
            <table class="table fv-table">
                <thead>
                    <tr>
                        <th>GL Code</th>
                        <th>GL Name</th>
                        <th class="text-end">GL Debit</th>
                        <th class="text-end">GL Credit</th>
                        <th class="text-end">GL Net</th>
                        <th class="text-end">Journal Debit</th>
                        <th class="text-end">Journal Credit</th>
                        <th class="text-end">Journal Net</th>
                        <th class="text-end text-danger">Variance</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="v" items="${reconResult.variances}">
                        <tr>
                            <td class="fw-bold"><c:out value="${v.glCode}" /></td>
                            <td><c:out value="${v.glName}" /></td>
                            <td class="amount"><fmt:formatNumber value="${v.glDebit}" type="number" maxFractionDigits="2" /></td>
                            <td class="amount"><fmt:formatNumber value="${v.glCredit}" type="number" maxFractionDigits="2" /></td>
                            <td class="amount fw-bold"><fmt:formatNumber value="${v.glNet}" type="number" maxFractionDigits="2" /></td>
                            <td class="amount"><fmt:formatNumber value="${v.journalDebit}" type="number" maxFractionDigits="2" /></td>
                            <td class="amount"><fmt:formatNumber value="${v.journalCredit}" type="number" maxFractionDigits="2" /></td>
                            <td class="amount fw-bold"><fmt:formatNumber value="${v.journalNet}" type="number" maxFractionDigits="2" /></td>
                            <td class="amount text-danger fw-bold"><fmt:formatNumber value="${v.variance}" type="number" maxFractionDigits="2" /></td>
                        </tr>
                    </c:forEach>
                </tbody>
            </table>
            </div>
        </div>
    </div>
    </c:if>

    <c:if test="${empty reconResult.variances and reconResult.isBalanced}">
    <div class="fv-card">
        <div class="card-header">Reconciliation Result</div>
        <div class="card-body text-center text-muted">
            <p class="mt-3 mb-3">All ${reconResult.glAccountCount} GL accounts are balanced. No variances detected.</p>
        </div>
    </div>
    </c:if>
</div>

<%@ include file="../layout/footer.jsp" %>