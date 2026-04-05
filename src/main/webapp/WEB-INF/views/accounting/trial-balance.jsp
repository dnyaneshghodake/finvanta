<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Trial Balance" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="main-content">
    <div class="top-bar">
        <h2>Trial Balance</h2>
        <div class="user-info">
            <span>Balance Status:
                <c:choose>
                    <c:when test="${trialBalance.isBalanced}">
                        <span style="color: #4caf50; font-weight: bold;">BALANCED</span>
                    </c:when>
                    <c:otherwise>
                        <span style="color: #f44336; font-weight: bold;">IMBALANCED</span>
                    </c:otherwise>
                </c:choose>
            </span>
        </div>
    </div>
    <div class="content-area">
        <div class="stats-grid">
            <div class="stat-card">
                <div class="stat-value amount"><fmt:formatNumber value="${trialBalance.totalDebit}" type="number" maxFractionDigits="2" /></div>
                <div class="stat-label">Total Debits</div>
            </div>
            <div class="stat-card">
                <div class="stat-value amount"><fmt:formatNumber value="${trialBalance.totalCredit}" type="number" maxFractionDigits="2" /></div>
                <div class="stat-label">Total Credits</div>
            </div>
        </div>

        <div class="card">
            <h3>GL Account Balances</h3>
            <table>
                <thead>
                    <tr>
                        <th>GL Code</th>
                        <th>GL Name</th>
                        <th>Account Type</th>
                        <th class="text-right">Debit Balance</th>
                        <th class="text-right">Credit Balance</th>
                        <th class="text-right">Net Balance</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="entry" items="${trialBalance.accounts}">
                        <tr>
                            <td><c:out value="${entry.value.glCode}" /></td>
                            <td><c:out value="${entry.value.glName}" /></td>
                            <td><c:out value="${entry.value.accountType}" /></td>
                            <td class="text-right amount"><fmt:formatNumber value="${entry.value.debitBalance}" type="number" maxFractionDigits="2" /></td>
                            <td class="text-right amount"><fmt:formatNumber value="${entry.value.creditBalance}" type="number" maxFractionDigits="2" /></td>
                            <td class="text-right amount" style="font-weight: bold;"><fmt:formatNumber value="${entry.value.netBalance}" type="number" maxFractionDigits="2" /></td>
                        </tr>
                    </c:forEach>
                </tbody>
                <tfoot>
                    <tr style="font-weight: bold; background: #e8eaf6;">
                        <td colspan="3">TOTAL</td>
                        <td class="text-right amount"><fmt:formatNumber value="${trialBalance.totalDebit}" type="number" maxFractionDigits="2" /></td>
                        <td class="text-right amount"><fmt:formatNumber value="${trialBalance.totalCredit}" type="number" maxFractionDigits="2" /></td>
                        <td class="text-right amount">
                            <fmt:formatNumber value="${trialBalance.totalDebit - trialBalance.totalCredit}" type="number" maxFractionDigits="2" />
                        </td>
                    </tr>
                </tfoot>
            </table>
        </div>
    </div>

<%@ include file="../layout/footer.jsp" %>
