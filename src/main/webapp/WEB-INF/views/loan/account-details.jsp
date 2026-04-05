<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Loan Account Details" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <c:if test="${not empty success}">
        <div class="fv-alert alert alert-success"><c:out value="${success}" /></div>
    </c:if>
    <c:if test="${not empty error}">
        <div class="fv-alert alert alert-danger"><c:out value="${error}" /></div>
    </c:if>

    <div class="row g-3 mb-3">
        <div class="col"><div class="fv-stat-card"><div class="stat-value amount"><fmt:formatNumber value="${account.sanctionedAmount}" type="number" maxFractionDigits="2" /></div><div class="stat-label">Sanctioned</div></div></div>
        <div class="col"><div class="fv-stat-card"><div class="stat-value amount"><fmt:formatNumber value="${account.outstandingPrincipal}" type="number" maxFractionDigits="2" /></div><div class="stat-label">Outstanding</div></div></div>
        <div class="col"><div class="fv-stat-card stat-warning"><div class="stat-value amount"><fmt:formatNumber value="${account.accruedInterest}" type="number" maxFractionDigits="2" /></div><div class="stat-label">Accrued Int.</div></div></div>
        <div class="col"><div class="fv-stat-card"><div class="stat-value amount"><fmt:formatNumber value="${account.emiAmount}" type="number" maxFractionDigits="2" /></div><div class="stat-label">EMI</div></div></div>
        <div class="col"><div class="fv-stat-card ${account.daysPastDue > 90 ? 'stat-danger' : ''}"><div class="stat-value"><c:out value="${account.daysPastDue}" /></div><div class="stat-label">DPD</div></div></div>
        <div class="col"><div class="fv-stat-card stat-danger"><div class="stat-value amount"><fmt:formatNumber value="${account.provisioningAmount}" type="number" maxFractionDigits="2" /></div><div class="stat-label">Provisioning</div></div></div>
    </div>

    <div class="fv-card">
        <div class="card-header">Account Information <a href="${pageContext.request.contextPath}/loan/accounts" class="btn btn-sm btn-outline-secondary float-end">Back</a></div>
        <div class="card-body">
            <table class="table fv-table">
                <tbody>
                <tr><td class="fw-bold">Account Number</td><td><c:out value="${account.accountNumber}" /></td></tr>
                <tr><td class="fw-bold">Customer</td><td><a href="${pageContext.request.contextPath}/customer/view/${account.customer.id}"><c:out value="${account.customer.fullName}" /></a> (<c:out value="${account.customer.customerNumber}" />)</td></tr>
                <tr><td class="fw-bold">Branch</td><td><c:out value="${account.branch.branchCode}" /> - <c:out value="${account.branch.branchName}" /></td></tr>
                <tr><td class="fw-bold">Application</td><td><c:out value="${account.application.applicationNumber}" /></td></tr>
                <tr><td class="fw-bold">Product Type</td><td><c:out value="${account.productType}" /></td></tr>
                <tr><td class="fw-bold">Status</td><td>
                    <c:choose>
                        <c:when test="${account.status.npa}"><span class="fv-badge fv-badge-npa"><c:out value="${account.status}" /></span></c:when>
                        <c:when test="${account.status.active}"><span class="fv-badge fv-badge-active"><c:out value="${account.status}" /></span></c:when>
                        <c:when test="${account.status.closed}"><span class="fv-badge fv-badge-closed"><c:out value="${account.status}" /></span></c:when>
                        <c:otherwise><span class="fv-badge fv-badge-pending"><c:out value="${account.status}" /></span></c:otherwise>
                    </c:choose>
                </td></tr>
                <tr><td class="fw-bold">Interest Rate</td><td><fmt:formatNumber value="${account.interestRate}" maxFractionDigits="2" />% p.a.</td></tr>
                <tr><td class="fw-bold">Penal Rate</td><td><fmt:formatNumber value="${account.penalRate}" maxFractionDigits="2" />% p.a.</td></tr>
                <tr><td class="fw-bold">Repayment Frequency</td><td><c:out value="${account.repaymentFrequency}" /></td></tr>
                <tr><td class="fw-bold">Tenure</td><td><c:out value="${account.tenureMonths}" /> months (Remaining: <c:out value="${account.remainingTenure}" />)</td></tr>
                <tr><td class="fw-bold">Disbursement Date</td><td><c:out value="${account.disbursementDate}" /></td></tr>
                <tr><td class="fw-bold">Maturity Date</td><td><c:out value="${account.maturityDate}" /></td></tr>
                <tr><td class="fw-bold">Next EMI Date</td><td><c:out value="${account.nextEmiDate}" /></td></tr>
                <tr><td class="fw-bold">Last Payment Date</td><td><c:out value="${account.lastPaymentDate}" /></td></tr>
                <tr><td class="fw-bold">Risk Category</td><td><c:out value="${account.riskCategory}" default="--" /></td></tr>
                <tr><td class="fw-bold">Collateral</td><td><c:out value="${account.collateralReference}" default="Unsecured" /></td></tr>
                <tr><td class="fw-bold">Outstanding Interest</td><td class="amount"><fmt:formatNumber value="${account.outstandingInterest}" type="number" maxFractionDigits="2" /> INR</td></tr>
                <tr><td class="fw-bold">Penal Interest Accrued</td><td class="amount"><fmt:formatNumber value="${account.penalInterestAccrued}" type="number" maxFractionDigits="2" /> INR</td></tr>
                <tr><td class="fw-bold">Total Outstanding</td><td class="amount fw-bold"><fmt:formatNumber value="${account.totalOutstanding}" type="number" maxFractionDigits="2" /> INR</td></tr>
                <tr><td class="fw-bold">Overdue Principal</td><td class="amount"><fmt:formatNumber value="${account.overduePrincipal}" type="number" maxFractionDigits="2" /> INR</td></tr>
                <tr><td class="fw-bold">Overdue Interest</td><td class="amount"><fmt:formatNumber value="${account.overdueInterest}" type="number" maxFractionDigits="2" /> INR</td></tr>
                <tr><td class="fw-bold">Last Accrual Date</td><td><c:out value="${account.lastInterestAccrualDate}" default="--" /></td></tr>
                <tr><td class="fw-bold">NPA Date</td><td><c:out value="${account.npaDate}" default="--" /></td></tr>
                <tr><td class="fw-bold">NPA Classification Date</td><td><c:out value="${account.npaClassificationDate}" default="--" /></td></tr>
                <tr><td class="fw-bold">Provisioning</td><td class="amount"><fmt:formatNumber value="${account.provisioningAmount}" type="number" maxFractionDigits="2" /> INR</td></tr>
                </tbody>
            </table>
        </div>
    </div>

    <c:if test="${account.disbursedAmount.unscaledValue() == 0}">
        <div class="fv-card">
            <div class="card-header">Disbursement</div>
            <div class="card-body">
                <form method="post" action="${pageContext.request.contextPath}/loan/disburse/${account.accountNumber}">
                    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                    <p>Disbursement Amount: <strong class="amount"><fmt:formatNumber value="${account.sanctionedAmount}" type="number" maxFractionDigits="2" /> INR</strong></p>
                    <button type="submit" class="btn btn-success mt-2" data-confirm="Confirm disbursement?">Disburse Loan</button>
                </form>
            </div>
        </div>
    </c:if>

    <c:if test="${account.disbursedAmount.unscaledValue() > 0 and not account.status.terminal}">
        <div class="fv-card">
            <div class="card-header">Process Repayment</div>
            <div class="card-body">
                <form method="post" action="${pageContext.request.contextPath}/loan/repayment/${account.accountNumber}" class="fv-form">
                    <div class="row mb-3">
                        <div class="col-md-4">
                            <label for="amount" class="form-label">Repayment Amount (INR)</label>
                            <input type="number" name="amount" id="amount" class="form-control" step="0.01" min="1" required value="${account.emiAmount}" />
                        </div>
                    </div>
                    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                    <button type="submit" class="btn btn-fv-primary">Process Repayment</button>
                </form>
            </div>
        </div>
    </c:if>

    <!-- CBS Amortization Schedule -->
    <c:if test="${not empty schedule}">
    <div class="fv-card">
        <div class="card-header">Amortization Schedule (${schedule.size()} installments)</div>
        <div class="card-body">
            <table class="table fv-table fv-datatable">
                <thead>
                    <tr>
                        <th>#</th>
                        <th>Due Date</th>
                        <th class="text-end">EMI</th>
                        <th class="text-end">Principal</th>
                        <th class="text-end">Interest</th>
                        <th class="text-end">Closing Bal.</th>
                        <th class="text-end">Paid</th>
                        <th>Paid Date</th>
                        <th>DPD</th>
                        <th>Status</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="sched" items="${schedule}">
                        <tr>
                            <td><c:out value="${sched.installmentNumber}" /></td>
                            <td><c:out value="${sched.dueDate}" /></td>
                            <td class="amount"><fmt:formatNumber value="${sched.emiAmount}" type="number" maxFractionDigits="2" /></td>
                            <td class="amount"><fmt:formatNumber value="${sched.principalAmount}" type="number" maxFractionDigits="2" /></td>
                            <td class="amount"><fmt:formatNumber value="${sched.interestAmount}" type="number" maxFractionDigits="2" /></td>
                            <td class="amount"><fmt:formatNumber value="${sched.closingBalance}" type="number" maxFractionDigits="2" /></td>
                            <td class="amount"><fmt:formatNumber value="${sched.paidAmount}" type="number" maxFractionDigits="2" /></td>
                            <td><c:out value="${sched.paidDate}" default="—" /></td>
                            <td><c:if test="${sched.daysPastDue > 0}"><span class="fv-badge fv-badge-npa"><c:out value="${sched.daysPastDue}" /></span></c:if><c:if test="${sched.daysPastDue == 0}">0</c:if></td>
                            <td>
                                <c:choose>
                                    <c:when test="${sched.status == 'PAID'}"><span class="fv-badge fv-badge-active">PAID</span></c:when>
                                    <c:when test="${sched.status == 'OVERDUE'}"><span class="fv-badge fv-badge-npa">OVERDUE</span></c:when>
                                    <c:when test="${sched.status == 'PARTIALLY_PAID'}"><span class="fv-badge fv-badge-pending">PARTIAL</span></c:when>
                                    <c:otherwise><span class="fv-badge fv-badge-approved">SCHEDULED</span></c:otherwise>
                                </c:choose>
                            </td>
                        </tr>
                    </c:forEach>
                </tbody>
            </table>
        </div>
    </div>
    </c:if>

    <div class="fv-card">
        <div class="card-header">Transaction History</div>
        <div class="card-body">
            <table class="table fv-table fv-datatable">
                <thead>
                    <tr>
                        <th>Txn Ref</th>
                        <th>Type</th>
                        <th class="text-end">Amount</th>
                        <th class="text-end">Principal</th>
                        <th class="text-end">Interest</th>
                        <th class="text-end">Balance After</th>
                        <th>Value Date</th>
                        <th>Narration</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="txn" items="${transactions}">
                        <tr>
                            <td><c:out value="${txn.transactionRef}" /></td>
                            <td><c:out value="${txn.transactionType}" /></td>
                            <td class="amount"><fmt:formatNumber value="${txn.amount}" type="number" maxFractionDigits="2" /></td>
                            <td class="amount"><fmt:formatNumber value="${txn.principalComponent}" type="number" maxFractionDigits="2" /></td>
                            <td class="amount"><fmt:formatNumber value="${txn.interestComponent}" type="number" maxFractionDigits="2" /></td>
                            <td class="amount"><fmt:formatNumber value="${txn.balanceAfter}" type="number" maxFractionDigits="2" /></td>
                            <td><c:out value="${txn.valueDate}" /></td>
                            <td><c:out value="${txn.narration}" /></td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty transactions}">
                        <tr><td colspan="8" class="text-center text-muted">No transactions yet</td></tr>
                    </c:if>
                </tbody>
            </table>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
