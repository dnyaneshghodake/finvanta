<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Dashboard" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <%-- CBS Tier-1: Breadcrumb root --%>
    <ul class="fv-breadcrumb">
        <li class="active"><i class="bi bi-speedometer2"></i> Dashboard</li>
    </ul>

    <c:if test="${not empty success}">
        <div class="fv-alert alert alert-success"><c:out value="${success}" /></div>
    </c:if>

    <!-- Stat Cards Row -->
    <div class="row g-3 mb-3">
        <div class="col">
            <div class="fv-stat-card">
                <div class="stat-icon"><i class="bi bi-people"></i></div>
                <div class="stat-value"><c:out value="${totalCustomers}" default="0" /></div>
                <div class="stat-label">Total Customers</div>
            </div>
        </div>
        <div class="col">
            <div class="fv-stat-card">
                <div class="stat-icon"><i class="bi bi-file-earmark-text"></i></div>
                <div class="stat-value"><c:out value="${pendingApplications}" default="0" /></div>
                <div class="stat-label">Pending Applications</div>
            </div>
        </div>
        <div class="col">
            <div class="fv-stat-card stat-success">
                <div class="stat-icon"><i class="bi bi-bank"></i></div>
                <div class="stat-value"><c:out value="${activeLoans}" default="0" /></div>
                <div class="stat-label">Active Loans</div>
            </div>
        </div>
        <div class="col">
            <div class="fv-stat-card stat-warning">
                <div class="stat-icon"><i class="bi bi-exclamation-circle"></i></div>
                <div class="stat-value"><c:out value="${smaAccounts}" default="0" /></div>
                <div class="stat-label">SMA Accounts</div>
            </div>
        </div>
        <div class="col">
            <div class="fv-stat-card stat-danger">
                <div class="stat-icon"><i class="bi bi-exclamation-triangle"></i></div>
                <div class="stat-value"><c:out value="${npaAccounts}" default="0" /></div>
                <div class="stat-label">NPA Accounts</div>
            </div>
        </div>
        <div class="col">
            <div class="fv-stat-card stat-warning">
                <div class="stat-icon"><i class="bi bi-hourglass-split"></i></div>
                <div class="stat-value"><c:out value="${pendingApprovals}" default="0" /></div>
                <div class="stat-label">Pending Approvals</div>
            </div>
        </div>
        <div class="col">
            <div class="fv-stat-card">
                <div class="stat-icon"><i class="bi bi-currency-rupee"></i></div>
                <div class="stat-value amount">
                    <fmt:formatNumber value="${totalOutstanding}" type="currency" currencySymbol="" maxFractionDigits="0" />
                </div>
                <div class="stat-label">Total Outstanding (INR)</div>
            </div>
        </div>
    </div>

    <!-- RBI Key Financial Ratios -->
    <c:if test="${pageContext.request.isUserInRole('ROLE_CHECKER') || pageContext.request.isUserInRole('ROLE_ADMIN')}">
    <div class="row g-3 mb-3">
        <div class="col">
            <div class="fv-stat-card stat-danger">
                <div class="stat-icon"><i class="bi bi-graph-down-arrow"></i></div>
                <div class="stat-value amount">
                    <fmt:formatNumber value="${npaOutstanding}" type="currency" currencySymbol="" maxFractionDigits="0" />
                </div>
                <div class="stat-label">NPA Outstanding (INR)</div>
            </div>
        </div>
        <div class="col">
            <div class="fv-stat-card">
                <div class="stat-icon"><i class="bi bi-shield-check"></i></div>
                <div class="stat-value amount">
                    <fmt:formatNumber value="${totalProvisioning}" type="currency" currencySymbol="" maxFractionDigits="0" />
                </div>
                <div class="stat-label">Total Provisioning (INR)</div>
            </div>
        </div>
        <div class="col">
            <div class="fv-stat-card ${grossNpaRatio > 5 ? 'stat-danger' : grossNpaRatio > 2 ? 'stat-warning' : 'stat-success'}">
                <div class="stat-icon"><i class="bi bi-percent"></i></div>
                <div class="stat-value"><fmt:formatNumber value="${grossNpaRatio}" maxFractionDigits="2" />%</div>
                <div class="stat-label">Gross NPA Ratio</div>
            </div>
        </div>
        <div class="col">
            <div class="fv-stat-card ${provisionCoverage >= 70 ? 'stat-success' : provisionCoverage >= 50 ? 'stat-warning' : 'stat-danger'}">
                <div class="stat-icon"><i class="bi bi-shield-fill-check"></i></div>
                <div class="stat-value"><fmt:formatNumber value="${provisionCoverage}" maxFractionDigits="2" />%</div>
                <div class="stat-label">Provision Coverage</div>
            </div>
        </div>
    </div>
    </c:if>

    <!-- Quick Actions (role-gated per CBS guidelines) -->
    <div class="fv-card">
        <div class="card-header">Quick Actions</div>
        <div class="card-body">
            <c:if test="${pageContext.request.isUserInRole('ROLE_MAKER') || pageContext.request.isUserInRole('ROLE_ADMIN')}">
            <a href="${pageContext.request.contextPath}/loan/apply" class="btn btn-sm btn-fv-primary me-1">New Loan Application</a>
            <a href="${pageContext.request.contextPath}/loan/applications" class="btn btn-sm btn-fv-primary me-1">View Applications</a>
            </c:if>
            <c:if test="${pageContext.request.isUserInRole('ROLE_MAKER') || pageContext.request.isUserInRole('ROLE_CHECKER') || pageContext.request.isUserInRole('ROLE_ADMIN')}">
            <a href="${pageContext.request.contextPath}/loan/accounts" class="btn btn-sm btn-fv-primary me-1">Loan Accounts</a>
            </c:if>
            <c:if test="${pageContext.request.isUserInRole('ROLE_CHECKER') || pageContext.request.isUserInRole('ROLE_ADMIN')}">
            <a href="${pageContext.request.contextPath}/accounting/trial-balance" class="btn btn-sm btn-fv-primary me-1">Trial Balance</a>
            <a href="${pageContext.request.contextPath}/workflow/pending" class="btn btn-sm btn-outline-success me-1">Pending Approvals</a>
            </c:if>
            <c:if test="${pageContext.request.isUserInRole('ROLE_ADMIN')}">
            <a href="${pageContext.request.contextPath}/batch/eod" class="btn btn-sm btn-outline-warning me-1">Run EOD</a>
            </c:if>
            <c:if test="${pageContext.request.isUserInRole('ROLE_AUDITOR') || pageContext.request.isUserInRole('ROLE_ADMIN')}">
            <a href="${pageContext.request.contextPath}/audit/logs" class="btn btn-sm btn-outline-secondary">Audit Logs</a>
            </c:if>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
