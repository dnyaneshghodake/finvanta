<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<nav class="fv-sidebar">
    <div class="fv-sidebar-brand">
        <h1>FINVANTA</h1>
        <small>Core Banking System</small>
    </div>
    <ul class="nav flex-column">
        <li><a href="${pageContext.request.contextPath}/dashboard" class="nav-link"><i class="bi bi-speedometer2"></i><span class="nav-text">Dashboard</span></a></li>

        <c:if test="${pageContext.request.isUserInRole('ROLE_MAKER') || pageContext.request.isUserInRole('ROLE_ADMIN')}">
        <li class="nav-section">Loan Origination</li>
        <li><a href="${pageContext.request.contextPath}/loan/apply" class="nav-link"><i class="bi bi-plus-circle"></i><span class="nav-text">New Application</span></a></li>
        <li><a href="${pageContext.request.contextPath}/loan/applications" class="nav-link"><i class="bi bi-file-earmark-text"></i><span class="nav-text">Applications</span></a></li>
        </c:if>

        <c:if test="${pageContext.request.isUserInRole('ROLE_CHECKER') || pageContext.request.isUserInRole('ROLE_ADMIN')}">
        <li class="nav-section">Verification</li>
        <li><a href="${pageContext.request.contextPath}/loan/applications" class="nav-link"><i class="bi bi-clipboard-check"></i><span class="nav-text">Verification Queue</span></a></li>
        <li><a href="${pageContext.request.contextPath}/workflow/pending" class="nav-link"><i class="bi bi-check2-square"></i><span class="nav-text">Approval Queue</span></a></li>
        </c:if>

        <li class="nav-section">Loan Accounts</li>
        <li><a href="${pageContext.request.contextPath}/loan/accounts" class="nav-link"><i class="bi bi-bank"></i><span class="nav-text">Active Accounts</span></a></li>

        <li class="nav-section">Accounting</li>
        <li><a href="${pageContext.request.contextPath}/accounting/trial-balance" class="nav-link"><i class="bi bi-journal-bookmark"></i><span class="nav-text">Trial Balance</span></a></li>
        <li><a href="${pageContext.request.contextPath}/accounting/journal-entries" class="nav-link"><i class="bi bi-journal-text"></i><span class="nav-text">Journal Entries</span></a></li>

        <c:if test="${pageContext.request.isUserInRole('ROLE_ADMIN')}">
        <li class="nav-section">EOD / Batch</li>
        <li><a href="${pageContext.request.contextPath}/batch/eod" class="nav-link"><i class="bi bi-gear-wide-connected"></i><span class="nav-text">EOD Processing</span></a></li>
        </c:if>

        <li class="nav-section">Workflow</li>
        <li><a href="${pageContext.request.contextPath}/workflow/pending" class="nav-link"><i class="bi bi-hourglass-split"></i><span class="nav-text">Pending Approvals</span></a></li>

        <c:if test="${pageContext.request.isUserInRole('ROLE_ADMIN')}">
        <li class="nav-section">Admin</li>
        <li><a href="${pageContext.request.contextPath}/customer/list" class="nav-link"><i class="bi bi-people"></i><span class="nav-text">Customers</span></a></li>
        <li><a href="${pageContext.request.contextPath}/branch/list" class="nav-link"><i class="bi bi-building"></i><span class="nav-text">Branches</span></a></li>
        </c:if>

        <c:if test="${pageContext.request.isUserInRole('ROLE_AUDITOR') || pageContext.request.isUserInRole('ROLE_ADMIN')}">
        <li class="nav-section">Audit</li>
        <li><a href="${pageContext.request.contextPath}/audit/logs" class="nav-link"><i class="bi bi-shield-lock"></i><span class="nav-text">Audit Logs</span></a></li>
        </c:if>
    </ul>
</nav>

<!-- Top Navbar -->
<div class="fv-topbar">
    <h2 class="fv-page-title"><c:out value="${pageTitle}" default="Dashboard" /></h2>
    <div class="fv-topbar-right">
        <span class="fv-biz-date"><c:out value="${businessDate}" default="--" /></span>
        <span class="fv-user-role"><c:out value="${userRole}" default="USER" /></span>
        <span><c:out value="${pageContext.request.userPrincipal.name}" default="" /></span>
        <a href="${pageContext.request.contextPath}/logout">Logout</a>
    </div>
</div>
