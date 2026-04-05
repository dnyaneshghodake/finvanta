<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Loan Account Details" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="main-content">
    <div class="top-bar">
        <h2>Loan Account - <c:out value="${account.accountNumber}" /></h2>
        <div class="user-info">
            <a href="${pageContext.request.contextPath}/loan/accounts">Back to Accounts</a>
        </div>
    </div>
    <div class="content-area">
        <c:if test="${not empty success}">
            <div class="alert alert-success"><c:out value="${success}" /></div>
        </c:if>
        <c:if test="${not empty error}">
            <div class="alert alert-error"><c:out value="${error}" /></div>
        </c:if>

        <div class="stats-grid">
            <div class="stat-card">
                <div class="stat-value amount"><fmt:formatNumber value="${account.sanctionedAmount}" type="number" maxFractionDigits="2" /></div>
                <div class="stat-label">Sanctioned Amount</div>
            </div>
            <div class="stat-card">
                <div class="stat-value amount"><fmt:formatNumber value="${account.outstandingPrincipal}" type="number" maxFractionDigits="2" /></div>
                <div class="stat-label">Outstanding Principal</div>
            </div>
            <div class="stat-card">
                <div class="stat-value amount"><fmt:formatNumber value="${account.accruedInterest}" type="number" maxFractionDigits="2" /></div>
                <div class="stat-label">Accrued Interest</div>
            </div>
            <div class="stat-card">
                <div class="stat-value amount"><fmt:formatNumber value="${account.emiAmount}" type="number" maxFractionDigits="2" /></div>
                <div class="stat-label">EMI Amount</div>
            </div>
            <div class="stat-card">
                <div class="stat-value"><c:out value="${account.daysPastDue}" /></div>
                <div class="stat-label" style="color: ${account.daysPastDue > 90 ? '#c62828' : '#333'}">Days Past Due</div>
            </div>
        </div>

        <div class="card">
            <h3>Account Information</h3>
            <table>
                <tr><td style="width:200px; font-weight:600;">Account Number</td><td><c:out value="${account.accountNumber}" /></td></tr>
                <tr><td style="font-weight:600;">Customer</td><td><c:out value="${account.customer.fullName}" /></td></tr>
                <tr><td style="font-weight:600;">Product Type</td><td><c:out value="${account.productType}" /></td></tr>
                <tr><td style="font-weight:600;">Status</td><td>
                    <span class="badge ${account.status.npa() ? 'badge-npa' : (account.status == 'ACTIVE' ? 'badge-active' : 'badge-pending')}">
                        <c:out value="${account.status}" />
                    </span>
                </td></tr>
                <tr><td style="font-weight:600;">Interest Rate</td><td><fmt:formatNumber value="${account.interestRate}" maxFractionDigits="2" />% p.a.</td></tr>
                <tr><td style="font-weight:600;">Tenure</td><td><c:out value="${account.tenureMonths}" /> months (Remaining: <c:out value="${account.remainingTenure}" />)</td></tr>
                <tr><td style="font-weight:600;">Disbursement Date</td><td><c:out value="${account.disbursementDate}" /></td></tr>
                <tr><td style="font-weight:600;">Maturity Date</td><td><c:out value="${account.maturityDate}" /></td></tr>
                <tr><td style="font-weight:600;">Next EMI Date</td><td><c:out value="${account.nextEmiDate}" /></td></tr>
                <tr><td style="font-weight:600;">Last Payment Date</td><td><c:out value="${account.lastPaymentDate}" /></td></tr>
            </table>
        </div>

        <c:if test="${account.disbursedAmount.unscaledValue() == 0}">
            <div class="card">
                <h3>Disbursement</h3>
                <form method="post" action="${pageContext.request.contextPath}/loan/disburse/${account.accountNumber}">
                    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                    <p>Disbursement Amount: <strong class="amount"><fmt:formatNumber value="${account.sanctionedAmount}" type="number" maxFractionDigits="2" /> INR</strong></p>
                    <button type="submit" class="btn btn-success" onclick="return confirm('Confirm disbursement?')" style="margin-top: 12px;">Disburse Loan</button>
                </form>
            </div>
        </c:if>

        <c:if test="${account.disbursedAmount.unscaledValue() > 0 && account.status != 'CLOSED'}">
            <div class="card">
                <h3>Process Repayment</h3>
                <form method="post" action="${pageContext.request.contextPath}/loan/repayment/${account.accountNumber}">
                    <div class="form-row">
                        <div class="form-group">
                            <label for="amount">Repayment Amount (INR)</label>
                            <input type="number" name="amount" id="amount" step="0.01" min="1" required
                                   value="${account.emiAmount}" />
                        </div>
                    </div>
                    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                    <button type="submit" class="btn btn-primary">Process Repayment</button>
                </form>
            </div>
        </c:if>

        <div class="card">
            <h3>Transaction History</h3>
            <table>
                <thead>
                    <tr>
                        <th>Txn Ref</th>
                        <th>Type</th>
                        <th class="text-right">Amount</th>
                        <th class="text-right">Principal</th>
                        <th class="text-right">Interest</th>
                        <th class="text-right">Balance After</th>
                        <th>Value Date</th>
                        <th>Narration</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="txn" items="${transactions}">
                        <tr>
                            <td><c:out value="${txn.transactionRef}" /></td>
                            <td><c:out value="${txn.transactionType}" /></td>
                            <td class="text-right amount"><fmt:formatNumber value="${txn.amount}" type="number" maxFractionDigits="2" /></td>
                            <td class="text-right"><fmt:formatNumber value="${txn.principalComponent}" type="number" maxFractionDigits="2" /></td>
                            <td class="text-right"><fmt:formatNumber value="${txn.interestComponent}" type="number" maxFractionDigits="2" /></td>
                            <td class="text-right amount"><fmt:formatNumber value="${txn.balanceAfter}" type="number" maxFractionDigits="2" /></td>
                            <td><c:out value="${txn.valueDate}" /></td>
                            <td><c:out value="${txn.narration}" /></td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty transactions}">
                        <tr><td colspan="8" style="text-align: center; color: #999;">No transactions yet</td></tr>
                    </c:if>
                </tbody>
            </table>
        </div>
    </div>

<%@ include file="../layout/footer.jsp" %>
