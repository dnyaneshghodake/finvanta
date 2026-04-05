<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Dashboard" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="main-content">
    <div class="top-bar">
        <h2>Dashboard</h2>
        <div class="user-info">
            <span>Welcome, <c:out value="${pageContext.request.userPrincipal.name}" /></span>
            <a href="${pageContext.request.contextPath}/logout">Logout</a>
        </div>
    </div>
    <div class="content-area">
        <div class="stats-grid">
            <div class="stat-card">
                <div class="stat-value"><c:out value="${pendingApplications}" default="0" /></div>
                <div class="stat-label">Pending Applications</div>
            </div>
            <div class="stat-card">
                <div class="stat-value"><c:out value="${activeLoans}" default="0" /></div>
                <div class="stat-label">Active Loans</div>
            </div>
            <div class="stat-card">
                <div class="stat-value"><c:out value="${npaAccounts}" default="0" /></div>
                <div class="stat-label" style="color: #c62828;">NPA Accounts</div>
            </div>
            <div class="stat-card">
                <div class="stat-value"><c:out value="${pendingApprovals}" default="0" /></div>
                <div class="stat-label">Pending Approvals</div>
            </div>
            <div class="stat-card">
                <div class="stat-value amount">
                    <fmt:formatNumber value="${totalOutstanding}" type="currency" currencySymbol="INR " maxFractionDigits="0" />
                </div>
                <div class="stat-label">Total Outstanding</div>
            </div>
        </div>

        <div class="card">
            <h3>Quick Actions</h3>
            <div style="display: flex; gap: 12px; flex-wrap: wrap;">
                <a href="${pageContext.request.contextPath}/loan/apply" class="btn btn-primary">New Loan Application</a>
                <a href="${pageContext.request.contextPath}/loan/applications" class="btn btn-primary">View Applications</a>
                <a href="${pageContext.request.contextPath}/loan/accounts" class="btn btn-primary">Loan Accounts</a>
                <a href="${pageContext.request.contextPath}/accounting/trial-balance" class="btn btn-primary">Trial Balance</a>
                <a href="${pageContext.request.contextPath}/batch/eod" class="btn btn-warning">Run EOD</a>
                <a href="${pageContext.request.contextPath}/workflow/pending" class="btn btn-success">Pending Approvals</a>
            </div>
        </div>
    </div>

<%@ include file="../layout/footer.jsp" %>
