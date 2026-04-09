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

        <c:if test="${pageContext.request.isUserInRole('ROLE_MAKER') || pageContext.request.isUserInRole('ROLE_CHECKER') || pageContext.request.isUserInRole('ROLE_ADMIN')}">
        <li class="nav-section">Loan Accounts</li>
        <li><a href="${pageContext.request.contextPath}/loan/accounts" class="nav-link"><i class="bi bi-bank"></i><span class="nav-text">Active Accounts</span></a></li>
        </c:if>
        <c:if test="${pageContext.request.isUserInRole('ROLE_CHECKER') || pageContext.request.isUserInRole('ROLE_ADMIN')}">
        <li><a href="${pageContext.request.contextPath}/loan/si/dashboard" class="nav-link"><i class="bi bi-arrow-repeat"></i><span class="nav-text">SI Dashboard</span></a></li>
        </c:if>

        <c:if test="${pageContext.request.isUserInRole('ROLE_MAKER') || pageContext.request.isUserInRole('ROLE_CHECKER') || pageContext.request.isUserInRole('ROLE_ADMIN')}">
        <li class="nav-section">CASA (Deposits)</li>
        <li><a href="${pageContext.request.contextPath}/deposit/accounts" class="nav-link"><i class="bi bi-wallet2"></i><span class="nav-text">CASA Accounts</span></a></li>
        </c:if>
        <c:if test="${pageContext.request.isUserInRole('ROLE_CHECKER') || pageContext.request.isUserInRole('ROLE_ADMIN')}">
        <li><a href="${pageContext.request.contextPath}/deposit/pipeline" class="nav-link"><i class="bi bi-kanban"></i><span class="nav-text">Account Pipeline</span></a></li>
        <li><a href="${pageContext.request.contextPath}/deposit/open" class="nav-link"><i class="bi bi-person-plus"></i><span class="nav-text">Open Account</span></a></li>
        </c:if>
        <c:if test="${pageContext.request.isUserInRole('ROLE_MAKER') || pageContext.request.isUserInRole('ROLE_ADMIN')}">
        <li><a href="${pageContext.request.contextPath}/deposit/transfer" class="nav-link"><i class="bi bi-arrow-left-right"></i><span class="nav-text">Fund Transfer</span></a></li>
        </c:if>

        <li class="nav-section">Transaction Inquiry</li>
        <li><a href="${pageContext.request.contextPath}/txn360/search?q=" class="nav-link"><i class="bi bi-diagram-3"></i><span class="nav-text">Transaction 360</span></a></li>

        <c:if test="${pageContext.request.isUserInRole('ROLE_CHECKER') || pageContext.request.isUserInRole('ROLE_ADMIN')}">
        <li class="nav-section">Accounting</li>
        <li><a href="${pageContext.request.contextPath}/accounting/trial-balance" class="nav-link"><i class="bi bi-journal-bookmark"></i><span class="nav-text">Trial Balance</span></a></li>
        <li><a href="${pageContext.request.contextPath}/accounting/financial-statements" class="nav-link"><i class="bi bi-file-earmark-bar-graph"></i><span class="nav-text">Financial Statements</span></a></li>
        <li><a href="${pageContext.request.contextPath}/accounting/journal-entries" class="nav-link"><i class="bi bi-journal-text"></i><span class="nav-text">Journal Entries</span></a></li>
        <li><a href="${pageContext.request.contextPath}/accounting/voucher-register" class="nav-link"><i class="bi bi-receipt-cutoff"></i><span class="nav-text">Voucher Register</span></a></li>
        <li><a href="${pageContext.request.contextPath}/reconciliation/report" class="nav-link"><i class="bi bi-arrow-up-right"></i><span class="nav-text">GL Reconciliation</span></a></li>
        </c:if>

        <c:if test="${pageContext.request.isUserInRole('ROLE_CHECKER') || pageContext.request.isUserInRole('ROLE_ADMIN')}">
        <li class="nav-section">Reports</li>
        <li><a href="${pageContext.request.contextPath}/reports/dpd" class="nav-link"><i class="bi bi-clock-history"></i><span class="nav-text">DPD Report</span></a></li>
        <li><a href="${pageContext.request.contextPath}/reports/irac" class="nav-link"><i class="bi bi-exclamation-triangle"></i><span class="nav-text">IRAC Report</span></a></li>
        <li><a href="${pageContext.request.contextPath}/reports/provision" class="nav-link"><i class="bi bi-percent"></i><span class="nav-text">Provision Report</span></a></li>
        </c:if>

        <c:if test="${pageContext.request.isUserInRole('ROLE_ADMIN')}">
        <li class="nav-section">EOD / Batch</li>
        <li><a href="${pageContext.request.contextPath}/batch/txn/list" class="nav-link"><i class="bi bi-cash-stack"></i><span class="nav-text">Transaction Batches</span></a></li>
        <li><a href="${pageContext.request.contextPath}/batch/eod" class="nav-link"><i class="bi bi-gear-wide-connected"></i><span class="nav-text">EOD Processing</span></a></li>
        </c:if>

        <c:if test="${pageContext.request.isUserInRole('ROLE_MAKER') || pageContext.request.isUserInRole('ROLE_CHECKER') || pageContext.request.isUserInRole('ROLE_ADMIN')}">
        <li class="nav-section">Customer &amp; Branch</li>
        <li><a href="${pageContext.request.contextPath}/customer/list" class="nav-link"><i class="bi bi-people"></i><span class="nav-text">Customers</span></a></li>
        <li><a href="${pageContext.request.contextPath}/branch/list" class="nav-link"><i class="bi bi-building"></i><span class="nav-text">Branches</span></a></li>
        </c:if>

        <c:if test="${pageContext.request.isUserInRole('ROLE_ADMIN')}">
        <li class="nav-section">Administration</li>
        <li><a href="${pageContext.request.contextPath}/admin/users" class="nav-link"><i class="bi bi-person-gear"></i><span class="nav-text">User Management</span></a></li>
        <li><a href="${pageContext.request.contextPath}/calendar/list" class="nav-link"><i class="bi bi-calendar-check"></i><span class="nav-text">Business Calendar</span></a></li>
        <li><a href="${pageContext.request.contextPath}/admin/products" class="nav-link"><i class="bi bi-box-seam"></i><span class="nav-text">Product Master</span></a></li>
        <li><a href="${pageContext.request.contextPath}/admin/charges" class="nav-link"><i class="bi bi-receipt"></i><span class="nav-text">Charge Config</span></a></li>
        <li><a href="${pageContext.request.contextPath}/admin/limits" class="nav-link"><i class="bi bi-sliders"></i><span class="nav-text">Transaction Limits</span></a></li>
        <li><a href="${pageContext.request.contextPath}/admin/mfa" class="nav-link"><i class="bi bi-shield-lock"></i><span class="nav-text">MFA Management</span></a></li>
        <li><a href="${pageContext.request.contextPath}/admin/ib-settlement" class="nav-link"><i class="bi bi-arrow-left-right"></i><span class="nav-text">IB Settlement</span></a></li>
        </c:if>

        <c:if test="${pageContext.request.isUserInRole('ROLE_AUDITOR') || pageContext.request.isUserInRole('ROLE_ADMIN')}">
        <li class="nav-section">Audit</li>
        <li><a href="${pageContext.request.contextPath}/audit/logs" class="nav-link"><i class="bi bi-shield-lock"></i><span class="nav-text">Audit Logs</span></a></li>
        </c:if>
    </ul>
</nav>

<!-- Top Navbar — Per Finacle/Temenos: always shows branch context + business date -->
<div class="fv-topbar">
    <h2 class="fv-page-title"><c:out value="${pageTitle}" default="Dashboard" /></h2>
    <div class="fv-topbar-right">
        <span class="fv-branch-code" title="Home Branch"><i class="bi bi-building"></i> <c:out value="${userBranchCode}" default="--" /></span>
        <span class="fv-biz-date"><c:out value="${businessDate}" default="--" /></span>
        <span class="fv-user-role"><c:out value="${userRole}" default="USER" /></span>
        <span><c:out value="${pageContext.request.userPrincipal.name}" default="" /></span>
        <a href="${pageContext.request.contextPath}/password/change" style="color:#90caf9;font-size:12px;text-decoration:none;margin-right:8px;" title="Change Password"><i class="bi bi-key"></i></a>
        <form method="post" action="${pageContext.request.contextPath}/logout" class="d-inline">
            <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
            <button type="submit" class="btn btn-sm" style="color:#90caf9;background:none;border:none;cursor:pointer;font-size:12px;padding:0;">Logout</button>
        </form>
    </div>
</div>
