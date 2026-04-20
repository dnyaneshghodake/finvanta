<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="GL Reconciliation Report" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <ul class="fv-breadcrumb">
        <li><a href="${pageContext.request.contextPath}/dashboard"><i class="bi bi-speedometer2"></i> Home</a></li>
        <li class="active">GL Reconciliation</li>
    </ul>
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
        <div class="card-header">GL vs Journal Reconciliation</div>
        <div class="card-body text-center text-muted">
            <p class="mt-3 mb-3"><i class="bi bi-check-circle text-success"></i> All ${reconResult.glAccountCount} GL accounts are balanced. No variances detected.</p>
        </div>
    </div>
    </c:if>

    <%-- CBS Sprint 0.3: Subledger-to-GL Reconciliation Report --%>
    <c:if test="${not empty subledgerResult}">
    <div class="fv-card">
        <div class="card-header ${subledgerResult.balanced ? '' : 'text-danger'}">
            <i class="bi bi-arrow-left-right"></i> Subledger vs GL Reconciliation
            <c:choose>
                <c:when test="${subledgerResult.balanced}"><span class="fv-badge fv-badge-active float-end">BALANCED</span></c:when>
                <c:otherwise><span class="fv-badge fv-badge-npa float-end">IMBALANCED (${subledgerResult.discrepancyCount()} issues)</span></c:otherwise>
            </c:choose>
        </div>
        <div class="card-body">
            <c:if test="${subledgerResult.balanced}">
                <p class="text-center text-muted"><i class="bi bi-check-circle text-success"></i> All subledger totals match GL balances. Loan outstanding = GL 1001, CASA Savings = GL 2010, CASA Current = GL 2020.</p>
            </c:if>
            <c:if test="${not empty subledgerResult.discrepancies()}">
                <div class="table-responsive">
                <table class="table fv-table">
                    <thead>
                        <tr>
                            <th>Check</th>
                            <th>Description</th>
                            <th>GL Code</th>
                            <th class="text-end">Subledger Total</th>
                            <th class="text-end">GL Total</th>
                            <th class="text-end text-danger">Variance</th>
                        </tr>
                    </thead>
                    <tbody>
                        <c:forEach var="d" items="${subledgerResult.discrepancies()}">
                            <tr>
                                <td class="fw-bold"><c:out value="${d.checkCode()}" /></td>
                                <td><c:out value="${d.checkName()}" /></td>
                                <td><c:out value="${d.glCode()}" /></td>
                                <td class="amount"><fmt:formatNumber value="${d.subledgerTotal()}" type="number" maxFractionDigits="2" /></td>
                                <td class="amount"><fmt:formatNumber value="${d.glTotal()}" type="number" maxFractionDigits="2" /></td>
                                <td class="amount text-danger fw-bold"><fmt:formatNumber value="${d.variance()}" type="number" maxFractionDigits="2" /></td>
                            </tr>
                        </c:forEach>
                    </tbody>
                </table>
                </div>
            </c:if>
        </div>
    </div>
    </c:if>

    <%-- CBS Tier-1: Branch Balance vs GL Master Reconciliation --%>
    <c:if test="${not empty branchBalanceResult}">
    <div class="fv-card">
        <div class="card-header ${branchBalanceResult.balanced ? '' : 'text-danger'}">
            <i class="bi bi-building"></i> Branch Balance vs GL Master Reconciliation (Tier-1)
            <c:choose>
                <c:when test="${branchBalanceResult.balanced}"><span class="fv-badge fv-badge-active float-end">BALANCED</span></c:when>
                <c:otherwise><span class="fv-badge fv-badge-npa float-end">IMBALANCED (${branchBalanceResult.discrepancyCount()} issues)</span></c:otherwise>
            </c:choose>
        </div>
        <div class="card-body">
            <c:if test="${branchBalanceResult.balanced}">
                <p class="text-center text-muted"><i class="bi bi-check-circle text-success"></i> SUM(GLBranchBalance) == GLMaster for all ${branchBalanceResult.checkedCount()} GL codes. Branch-level accounting is consistent.</p>
            </c:if>
            <c:if test="${not empty branchBalanceResult.discrepancies()}">
                <div class="table-responsive">
                <table class="table fv-table">
                    <thead>
                        <tr>
                            <th>GL Code</th>
                            <th>GL Name</th>
                            <th class="text-end">GL Master Debit</th>
                            <th class="text-end">Branch Sum Debit</th>
                            <th class="text-end">GL Master Credit</th>
                            <th class="text-end">Branch Sum Credit</th>
                        </tr>
                    </thead>
                    <tbody>
                        <c:forEach var="d" items="${branchBalanceResult.discrepancies()}">
                            <tr>
                                <td class="fw-bold"><c:out value="${d.glCode()}" /></td>
                                <td><c:out value="${d.glName()}" /></td>
                                <td class="amount"><fmt:formatNumber value="${d.glMasterDebit()}" type="number" maxFractionDigits="2" /></td>
                                <td class="amount text-danger"><fmt:formatNumber value="${d.ledgerDebit()}" type="number" maxFractionDigits="2" /></td>
                                <td class="amount"><fmt:formatNumber value="${d.glMasterCredit()}" type="number" maxFractionDigits="2" /></td>
                                <td class="amount text-danger"><fmt:formatNumber value="${d.ledgerCredit()}" type="number" maxFractionDigits="2" /></td>
                            </tr>
                        </c:forEach>
                    </tbody>
                </table>
                </div>
            </c:if>
        </div>
    </div>
    </c:if>
</div>

<%@ include file="../layout/footer.jsp" %>