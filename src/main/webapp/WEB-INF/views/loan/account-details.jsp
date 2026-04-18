<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Loan Account Details" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <ul class="fv-breadcrumb">
        <li><a href="${pageContext.request.contextPath}/dashboard"><i class="bi bi-speedometer2"></i> Home</a></li>
        <li><a href="${pageContext.request.contextPath}/loan/accounts">Loan Accounts</a></li>
        <li class="active"><c:out value="${account.accountNumber}" /></li>
    </ul>

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
        <div class="card-header"><i class="bi bi-bank"></i> Account Information &mdash; <c:out value="${account.accountNumber}" />
            <div class="float-end">
                <button type="button" class="btn btn-sm btn-outline-secondary" onclick="window.print();" title="Print Account"><i class="bi bi-printer"></i> Print <span class="fv-kbd">Ctrl+P</span></button>
                <c:if test="${pageContext.request.isUserInRole('ROLE_AUDITOR') || pageContext.request.isUserInRole('ROLE_ADMIN')}">
                    <a href="${pageContext.request.contextPath}/audit/logs?entityType=LoanAccount&entityId=${account.id}" class="btn btn-sm btn-outline-info me-1"><i class="bi bi-shield-lock"></i> Audit Trail</a>
                </c:if>
                <a href="${pageContext.request.contextPath}/loan/accounts" class="btn btn-sm btn-outline-secondary" data-fv-cancel="${pageContext.request.contextPath}/loan/accounts"><i class="bi bi-arrow-left"></i> Back <span class="fv-kbd">F3</span></a>
            </div>
        </div>
        <div class="card-body">
            <table class="table fv-table">
                <tbody>
                <tr><td class="fw-bold">Account Number</td><td><c:out value="${account.accountNumber}" /></td></tr>
                <tr><td class="fw-bold">Customer</td><td><a href="${pageContext.request.contextPath}/customer/view/${account.customer.id}"><c:out value="${account.customer.fullName}" /></a> (<c:out value="${account.customer.customerNumber}" />)</td></tr>
                <tr><td class="fw-bold">Branch</td><td><a href="${pageContext.request.contextPath}/branch/view/${account.branch.id}"><c:out value="${account.branch.branchCode}" /> - <c:out value="${account.branch.branchName}" /></a></td></tr>
                <tr><td class="fw-bold">Application</td><td><c:out value="${account.application.applicationNumber}" /></td></tr>
                <tr><td class="fw-bold">Product Type</td><td><c:out value="${account.productType}" />
                    <c:if test="${pageContext.request.isUserInRole('ROLE_ADMIN') && not empty productId}">
                        <a href="${pageContext.request.contextPath}/admin/products/${productId}" class="btn btn-sm btn-outline-secondary ms-2"><i class="bi bi-diagram-2"></i> GL Config</a>
                    </c:if>
                </td></tr>
                <tr><td class="fw-bold">Currency</td><td><c:out value="${account.currencyCode}" /></td></tr>
                <tr><td class="fw-bold">Status</td><td>
                    <c:choose>
                        <c:when test="${account.status.npa}"><span class="fv-badge fv-badge-npa"><c:out value="${account.status}" /></span></c:when>
                        <c:when test="${account.status.active}"><span class="fv-badge fv-badge-active"><c:out value="${account.status}" /></span></c:when>
                        <c:when test="${account.status.closed}"><span class="fv-badge fv-badge-closed"><c:out value="${account.status}" /></span></c:when>
                        <c:otherwise><span class="fv-badge fv-badge-pending"><c:out value="${account.status}" /></span></c:otherwise>
                    </c:choose>
                </td></tr>
                <tr><td class="fw-bold">Interest Rate</td><td><fmt:formatNumber value="${account.interestRate}" maxFractionDigits="2" />% p.a.
                    <c:if test="${account.floatingRate}"> <span class="badge bg-info">FLOATING</span></c:if>
                    <c:if test="${not account.floatingRate}"> <span class="badge bg-secondary">FIXED</span></c:if>
                </td></tr>
                <%-- CBS Sprint 1.4: Floating rate details per RBI EBLR/MCLR Framework --%>
                <c:if test="${account.floatingRate}">
                <tr><td class="fw-bold">Benchmark</td><td><c:out value="${account.benchmarkRateName}" /> @ <fmt:formatNumber value="${account.benchmarkRate}" maxFractionDigits="4" />% + Spread <fmt:formatNumber value="${account.spread}" maxFractionDigits="4" />%</td></tr>
                <tr><td class="fw-bold">Rate Reset</td><td><c:out value="${account.rateResetFrequency}" default="--" /> | Next: <c:out value="${account.nextRateResetDate}" default="--" /> | Last: <c:out value="${account.lastRateResetDate}" default="--" /></td></tr>
                </c:if>
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
                <tr><td class="fw-bold">Disbursed Amount</td><td class="amount"><fmt:formatNumber value="${account.disbursedAmount}" type="number" maxFractionDigits="2" /> INR</td></tr>
                <tr><td class="fw-bold">Last Accrual Date</td><td><c:out value="${account.lastInterestAccrualDate}" default="--" /></td></tr>
                <tr><td class="fw-bold">Last Penal Accrual Date</td><td><c:out value="${account.lastPenalAccrualDate}" default="--" /></td></tr>
                <tr><td class="fw-bold">NPA Date</td><td><c:out value="${account.npaDate}" default="--" /></td></tr>
                <tr><td class="fw-bold">NPA Classification Date</td><td><c:out value="${account.npaClassificationDate}" default="--" /></td></tr>
                <tr><td class="fw-bold">Provisioning</td><td class="amount"><fmt:formatNumber value="${account.provisioningAmount}" type="number" maxFractionDigits="2" /> INR</td></tr>
                <tr><td class="fw-bold">Disbursement Mode</td><td><c:out value="${account.disbursementMode}" default="SINGLE" />
                    <c:if test="${account.multiDisbursement}"> - Tranches: <c:out value="${account.tranchesDisbursed}" />/<c:out value="${account.totalTranchesPlanned}" default="N/A" /></c:if>
                </td></tr>
                <tr><td class="fw-bold">Fully Disbursed</td><td><c:choose><c:when test="${account.fullyDisbursed}"><span class="fv-badge fv-badge-active">YES</span></c:when><c:otherwise><span class="fv-badge fv-badge-pending">NO - Undisbursed: <fmt:formatNumber value="${account.undisbursedAmount}" type="number" maxFractionDigits="2" /> INR</span></c:otherwise></c:choose></td></tr>
                </tbody>
            </table>
        </div>
    </div>

    <!-- CBS Collaterals -->
    <c:if test="${not empty collaterals}">
    <div class="fv-card">
        <div class="card-header">Collaterals <span class="badge bg-secondary">${collaterals.size()}</span></div>
        <div class="card-body">
            <table class="table fv-table">
                <thead><tr><th>Ref</th><th>Type</th><th>Owner</th><th class="text-end">Market Value</th><th>Lien Status</th><th>Insurance Expiry</th></tr></thead>
                <tbody>
                <c:forEach var="col" items="${collaterals}">
                    <tr>
                        <td class="font-monospace"><c:out value="${col.collateralRef}" /></td>
                        <td><c:out value="${col.collateralType}" /></td>
                        <td><c:out value="${col.ownerName}" /></td>
                        <td class="text-end amount"><fmt:formatNumber value="${col.marketValue}" type="number" maxFractionDigits="2" /> INR</td>
                        <td><c:out value="${col.lienStatus}" /></td>
                        <td><c:out value="${col.insuranceExpiryDate}" default="--" /></td>
                    </tr>
                </c:forEach>
                </tbody>
            </table>
        </div>
    </div>
    </c:if>

    <!-- CBS Documents -->
    <c:if test="${not empty documents}">
    <div class="fv-card">
        <div class="card-header">Documents <span class="badge bg-secondary">${documents.size()}</span></div>
        <div class="card-body">
            <table class="table fv-table">
                <thead><tr><th>Type</th><th>Name</th><th>Mandatory</th><th>Status</th><th>Verified By</th><th>Expiry</th></tr></thead>
                <tbody>
                <c:forEach var="doc" items="${documents}">
                    <tr>
                        <td><c:out value="${doc.documentType}" /></td>
                        <td><c:out value="${doc.documentName}" /></td>
                        <td><c:if test="${doc.mandatory}"><span class="fv-badge fv-badge-npa">Required</span></c:if><c:if test="${!doc.mandatory}">Optional</c:if></td>
                        <td><c:choose><c:when test="${doc.verificationStatus == 'VERIFIED'}"><span class="fv-badge fv-badge-active">VERIFIED</span></c:when><c:when test="${doc.verificationStatus == 'REJECTED'}"><span class="fv-badge fv-badge-npa">REJECTED</span></c:when><c:otherwise><span class="fv-badge fv-badge-pending">PENDING</span></c:otherwise></c:choose></td>
                        <td><c:out value="${doc.verifiedBy}" default="--" /></td>
                        <td><c:out value="${doc.expiryDate}" default="--" /></td>
                    </tr>
                </c:forEach>
                </tbody>
            </table>
        </div>
    </div>
    </c:if>

    <!-- CBS Standing Instructions (Finacle SI_MASTER / Temenos STANDING.ORDER) -->
    <c:if test="${not empty standingInstructions}">
    <div class="fv-card">
        <div class="card-header"><i class="bi bi-arrow-repeat"></i> Standing Instructions <span class="badge bg-secondary"><c:out value="${standingInstructions.size()}" /></span></div>
        <div class="card-body">
            <div class="table-responsive">
            <table class="table fv-table">
                <thead>
                    <tr>
                        <th>SI Reference</th>
                        <th>Type</th>
                        <th>Source CASA</th>
                        <th>Frequency</th>
                        <th>Next Execution</th>
                        <th>Last Status</th>
                        <th>Executions</th>
                        <th>Failures</th>
                        <th>Status</th>
                        <c:if test="${pageContext.request.isUserInRole('ROLE_CHECKER') || pageContext.request.isUserInRole('ROLE_ADMIN')}">
                        <th>Action</th>
                        </c:if>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="si" items="${standingInstructions}">
                        <tr>
                            <td class="font-monospace small"><c:out value="${si.siReference}" /></td>
                            <td><span class="badge ${si.destinationType == 'LOAN_EMI' ? 'bg-primary' : 'bg-info'}"><c:out value="${si.destinationType}" /></span></td>
                            <td><a href="${pageContext.request.contextPath}/deposit/view/${si.sourceAccountNumber}"><c:out value="${si.sourceAccountNumber}" /></a></td>
                            <td><c:out value="${si.frequency}" /></td>
                            <td><c:out value="${si.nextExecutionDate}" default="--" /></td>
                            <td>
                                <c:choose>
                                    <c:when test="${si.lastExecutionStatus == 'SUCCESS'}"><span class="fv-badge fv-badge-active">SUCCESS</span></c:when>
                                    <c:when test="${si.lastExecutionStatus != null && si.lastExecutionStatus.startsWith('FAILED')}"><span class="fv-badge fv-badge-npa"><c:out value="${si.lastExecutionStatus}" /></span></c:when>
                                    <c:when test="${si.lastExecutionStatus == 'SKIPPED'}"><span class="fv-badge fv-badge-pending">SKIPPED</span></c:when>
                                    <c:otherwise><span class="text-muted">--</span></c:otherwise>
                                </c:choose>
                            </td>
                            <td><c:out value="${si.totalExecutions}" /></td>
                            <td><c:if test="${si.totalFailures > 0}"><span class="text-danger"><c:out value="${si.totalFailures}" /></span></c:if><c:if test="${si.totalFailures == 0}">0</c:if></td>
                            <td>
                                <c:choose>
                                    <c:when test="${si.status == 'ACTIVE'}"><span class="fv-badge fv-badge-active">ACTIVE</span></c:when>
                                    <c:when test="${si.status == 'PAUSED'}"><span class="fv-badge fv-badge-pending">PAUSED</span></c:when>
                                    <c:when test="${si.status == 'EXPIRED'}"><span class="fv-badge fv-badge-closed">EXPIRED</span></c:when>
                                    <c:when test="${si.status == 'CANCELLED'}"><span class="fv-badge fv-badge-rejected">CANCELLED</span></c:when>
                                    <c:otherwise><span class="fv-badge fv-badge-pending"><c:out value="${si.status}" /></span></c:otherwise>
                                </c:choose>
                            </td>
                            <c:if test="${pageContext.request.isUserInRole('ROLE_CHECKER') || pageContext.request.isUserInRole('ROLE_ADMIN')}">
                            <td>
                                <c:if test="${si.status == 'ACTIVE'}">
                                    <form method="post" action="${pageContext.request.contextPath}/loan/si/pause/${si.siReference}" class="d-inline">
                                        <input type="hidden" name="accountNumber" value="${account.accountNumber}" />
                                        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                                        <button type="submit" class="btn btn-sm btn-outline-warning" data-confirm="Pause SI ${si.siReference}? EMI auto-debit will stop until resumed."><i class="bi bi-pause-circle"></i> Pause</button>
                                    </form>
                                    <form method="post" action="${pageContext.request.contextPath}/loan/si/cancel/${si.siReference}" class="d-inline">
                                        <input type="hidden" name="accountNumber" value="${account.accountNumber}" />
                                        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                                        <button type="submit" class="btn btn-sm btn-outline-danger" data-confirm="Cancel SI ${si.siReference}? This is permanent."><i class="bi bi-x-circle"></i> Cancel</button>
                                    </form>
                                </c:if>
                                <c:if test="${si.status == 'PAUSED'}">
                                    <form method="post" action="${pageContext.request.contextPath}/loan/si/resume/${si.siReference}" class="d-inline">
                                        <input type="hidden" name="accountNumber" value="${account.accountNumber}" />
                                        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                                        <button type="submit" class="btn btn-sm btn-outline-success" data-confirm="Resume SI ${si.siReference}? EMI auto-debit will restart."><i class="bi bi-play-circle"></i> Resume</button>
                                    </form>
                                </c:if>
                            </td>
                            </c:if>
                        </tr>
                    </c:forEach>
                </tbody>
            </table>
            </div>
            <c:forEach var="si" items="${standingInstructions}">
                <c:if test="${si.lastFailureReason != null}">
                    <div class="alert alert-warning mt-2 small"><strong>Last failure (${si.siReference}):</strong> <c:out value="${si.lastFailureReason}" /></div>
                </c:if>
            </c:forEach>
        </div>
    </div>
    </c:if>

    <!-- CBS Interest Accrual Trail (P0-2: Audit-grade per-day records) -->
    <c:if test="${not empty accrualHistory}">
    <div class="fv-card">
        <div class="card-header">Interest Accrual Trail <span class="badge bg-secondary"><c:out value="${accrualHistory.size()}" /></span></div>
        <div class="card-body">
            <div class="table-responsive">
            <table class="table fv-table fv-datatable">
                <thead>
                    <tr>
                        <th>Date</th>
                        <th>Type</th>
                        <th class="text-end">Principal Base</th>
                        <th class="text-end">Rate %</th>
                        <th>Days</th>
                        <th class="text-end">Amount</th>
                        <th>Posted</th>
                        <th>Txn Ref</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="acc" items="${accrualHistory}">
                        <tr>
                            <td><c:out value="${acc.accrualDate}" /></td>
                            <td>
                                <c:choose>
                                    <c:when test="${acc.accrualType == 'PENAL'}"><span class="fv-badge fv-badge-npa">PENAL</span></c:when>
                                    <c:otherwise><span class="fv-badge fv-badge-active">REGULAR</span></c:otherwise>
                                </c:choose>
                            </td>
                            <td class="amount"><fmt:formatNumber value="${acc.principalBase}" type="number" maxFractionDigits="2" /></td>
                            <td class="text-end"><fmt:formatNumber value="${acc.rateApplied}" maxFractionDigits="4" />%</td>
                            <td><c:out value="${acc.daysCount}" /></td>
                            <td class="amount"><fmt:formatNumber value="${acc.accruedAmount}" type="number" maxFractionDigits="2" /></td>
                            <td>
                                <c:choose>
                                    <c:when test="${acc.postedFlag}"><span class="fv-badge fv-badge-active">Yes</span></c:when>
                                    <c:otherwise><span class="fv-badge fv-badge-pending">No</span></c:otherwise>
                                </c:choose>
                            </td>
                            <td class="font-monospace small">
                                <c:if test="${not empty acc.transactionRef}">
                                    <a href="${pageContext.request.contextPath}/txn360/${acc.transactionRef}"><c:out value="${acc.transactionRef}" /></a>
                                </c:if>
                                <c:if test="${empty acc.transactionRef}">--</c:if>
                            </td>
                        </tr>
                    </c:forEach>
                </tbody>
            </table>
            </div>
        </div>
    </div>
    </c:if>

    <!-- CBS Repayment Schedule Preview (RBI Fair Practices Code 2023) -->
    <c:if test="${not empty schedulePreview}">
    <div class="fv-card">
        <div class="card-header">
            Repayment Schedule Preview
            <span class="badge bg-warning text-dark ms-2">Pre-Disbursement Disclosure</span>
        </div>
        <div class="card-body">
            <div class="row g-3 mb-3">
                <div class="col-md-3"><div class="fv-stat-card"><div class="stat-value amount"><fmt:formatNumber value="${previewEmi}" type="number" maxFractionDigits="2" /></div><div class="stat-label">Monthly EMI (INR)</div></div></div>
                <div class="col-md-3"><div class="fv-stat-card"><div class="stat-value amount"><fmt:formatNumber value="${account.sanctionedAmount}" type="number" maxFractionDigits="2" /></div><div class="stat-label">Principal (INR)</div></div></div>
                <div class="col-md-3"><div class="fv-stat-card stat-warning"><div class="stat-value amount"><fmt:formatNumber value="${previewTotalInterest}" type="number" maxFractionDigits="2" /></div><div class="stat-label">Total Interest (INR)</div></div></div>
                <div class="col-md-3"><div class="fv-stat-card"><div class="stat-value amount"><fmt:formatNumber value="${previewTotalPayable}" type="number" maxFractionDigits="2" /></div><div class="stat-label">Total Payable (INR)</div></div></div>
            </div>
            <p class="text-muted small">Per RBI Fair Practices Code 2023: This schedule is indicative and based on the sanctioned amount at <fmt:formatNumber value="${account.interestRate}" maxFractionDigits="2" />% p.a. for <c:out value="${account.tenureMonths}" /> months. Actual schedule will be generated at disbursement.</p>
            <div class="table-responsive">
            <table class="table fv-table fv-datatable">
                <thead>
                    <tr><th>#</th><th>Due Date</th><th class="text-end">EMI</th><th class="text-end">Principal</th><th class="text-end">Interest</th><th class="text-end">Closing Balance</th></tr>
                </thead>
                <tbody>
                    <c:forEach var="prev" items="${schedulePreview}">
                        <tr>
                            <td><c:out value="${prev.installmentNumber}" /></td>
                            <td><c:out value="${prev.dueDate}" /></td>
                            <td class="amount"><fmt:formatNumber value="${prev.emiAmount}" type="number" maxFractionDigits="2" /></td>
                            <td class="amount"><fmt:formatNumber value="${prev.principalAmount}" type="number" maxFractionDigits="2" /></td>
                            <td class="amount"><fmt:formatNumber value="${prev.interestAmount}" type="number" maxFractionDigits="2" /></td>
                            <td class="amount"><fmt:formatNumber value="${prev.closingBalance}" type="number" maxFractionDigits="2" /></td>
                        </tr>
                    </c:forEach>
                </tbody>
            </table>
            </div>
        </div>
    </div>
    </c:if>

    <c:if test="${account.disbursedAmount.signum() == 0 || (account.multiDisbursement && !account.fullyDisbursed)}">
        <div class="fv-card">
            <div class="card-header">Disbursement
                <c:if test="${account.multiDisbursement}"><span class="badge bg-info ms-2">Multi-Tranche</span></c:if>
            </div>
            <div class="card-body">
                <p>Sanctioned: <strong class="amount"><fmt:formatNumber value="${account.sanctionedAmount}" type="number" maxFractionDigits="2" /> INR</strong>
                    | Disbursed: <strong class="amount"><fmt:formatNumber value="${account.disbursedAmount}" type="number" maxFractionDigits="2" /> INR</strong>
                    | Remaining: <strong class="amount"><fmt:formatNumber value="${account.undisbursedAmount}" type="number" maxFractionDigits="2" /> INR</strong></p>

                <c:if test="${!account.multiDisbursement}">
                <!-- Single disbursement: full sanctioned amount -->
                <form method="post" action="${pageContext.request.contextPath}/loan/disburse/${account.accountNumber}" class="fv-form">
                    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                    <button type="submit" class="btn btn-fv-success" data-confirm="Confirm full disbursement of INR ${account.undisbursedAmount}?"><i class="bi bi-cash-stack"></i> Disburse Full Amount</button>
                </form>
                </c:if>

                <c:if test="${account.multiDisbursement}">
                <!-- Multi-tranche: specify tranche amount -->
                <form method="post" action="${pageContext.request.contextPath}/loan/disburse-tranche/${account.accountNumber}" class="fv-form mb-2">
                    <div class="row mb-2">
                        <div class="col-md-4">
                            <label class="form-label">Tranche Amount (INR) *</label>
                            <input type="number" name="trancheAmount" class="form-control" data-fv-type="amount" step="0.01" min="1" max="${account.undisbursedAmount}" required />
                        </div>
                        <div class="col-md-4">
                            <label class="form-label">Narration</label>
                            <input type="text" name="narration" class="form-control" placeholder="e.g., Foundation complete - Tranche 1" />
                        </div>
                    </div>
                    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                    <button type="submit" class="btn btn-fv-success" data-confirm="Confirm tranche disbursement?"><i class="bi bi-cash-stack"></i> Disburse Tranche</button>
                </form>
                <form method="post" action="${pageContext.request.contextPath}/loan/disburse/${account.accountNumber}" class="fv-form d-inline">
                    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                    <button type="submit" class="btn btn-outline-success" data-confirm="Disburse all remaining INR ${account.undisbursedAmount}?">Disburse All Remaining</button>
                </form>
                </c:if>
            </div>
        </div>
    </c:if>

    <c:if test="${account.disbursedAmount.signum() > 0 and not account.status.terminal}">
        <div class="fv-card">
            <div class="card-header">Process Repayment</div>
            <div class="card-body">
                <form method="post" action="${pageContext.request.contextPath}/loan/repayment/${account.accountNumber}" class="fv-form">
                    <div class="row mb-3">
                        <div class="col-md-4">
                            <label for="amount" class="form-label">Repayment Amount (INR)</label>
                            <input type="number" name="amount" id="amount" class="form-control" data-fv-type="amount" step="0.01" min="1" required value="${account.emiAmount}" />
                        </div>
                    </div>
                    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                    <button type="submit" class="btn btn-fv-primary">Process Repayment</button>
                </form>
            </div>
        </div>

        <!-- CBS Prepayment/Foreclosure - per RBI Fair Lending Code 2023 -->
        <c:if test="${pageContext.request.isUserInRole('ROLE_MAKER') || pageContext.request.isUserInRole('ROLE_ADMIN')}">
        <div class="fv-card">
            <div class="card-header">Prepayment / Foreclosure</div>
            <div class="card-body">
                <p class="text-muted">Pay off total outstanding to close the loan early. Per RBI Fair Lending Code 2023, no prepayment penalty on floating rate loans.</p>
                <form method="post" action="${pageContext.request.contextPath}/loan/prepayment/${account.accountNumber}" class="fv-form">
                    <div class="row mb-3">
                        <div class="col-md-4">
                            <label class="form-label">Total Outstanding (INR)</label>
                            <input type="number" name="amount" class="form-control" data-fv-type="amount" step="0.01" min="1" required value="${account.totalOutstanding}" />
                        </div>
                    </div>
                    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                    <button type="submit" class="btn btn-warning" data-confirm="Confirm prepayment/foreclosure? This will close the loan.">Prepay / Foreclose</button>
                </form>
            </div>
        </div>
        </c:if>

        <!-- CBS Fee Charging - MAKER/ADMIN -->
        <c:if test="${pageContext.request.isUserInRole('ROLE_MAKER') || pageContext.request.isUserInRole('ROLE_ADMIN')}">
        <div class="fv-card">
            <div class="card-header">Charge Fee</div>
            <div class="card-body">
                <p class="text-muted">Processing fee, documentation charge, or other ad-hoc fees. GL: DR Bank Operations / CR Fee Income.</p>
                <form method="post" action="${pageContext.request.contextPath}/loan/fee/${account.accountNumber}" class="fv-form">
                    <div class="row mb-3">
                        <div class="col-md-3">
                            <label class="form-label">Fee Type</label>
                            <select name="feeType" class="form-select" required>
                                <option value="PROCESSING_FEE">Processing Fee</option>
                                <option value="DOCUMENTATION_CHARGE">Documentation Charge</option>
                                <option value="LATE_PAYMENT_FEE">Late Payment Fee</option>
                                <option value="STAMP_DUTY">Stamp Duty</option>
                            </select>
                        </div>
                        <div class="col-md-3">
                            <label class="form-label">Amount (INR)</label>
                            <input type="number" name="feeAmount" class="form-control" data-fv-type="amount" step="0.01" min="1" required />
                        </div>
                    </div>
                    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                    <button type="submit" class="btn btn-outline-primary" data-confirm="Confirm fee charge?">Charge Fee</button>
                </form>
            </div>
        </div>
        </c:if>

        <!-- CBS Write-Off - ADMIN only, NPA accounts only -->
        <c:if test="${account.status.npa and pageContext.request.isUserInRole('ROLE_ADMIN')}">
        <div class="fv-card">
            <div class="card-header text-danger">NPA Write-Off</div>
            <div class="card-body">
                <p class="text-danger">Write off this NPA account. This removes the loan asset from the balance sheet and is <strong>irreversible</strong>.</p>
                <p>Outstanding Principal: <strong class="amount"><fmt:formatNumber value="${account.outstandingPrincipal}" type="number" maxFractionDigits="2" /> INR</strong></p>
                <p>Provisioning Held: <strong class="amount"><fmt:formatNumber value="${account.provisioningAmount}" type="number" maxFractionDigits="2" /> INR</strong></p>
                <form method="post" action="${pageContext.request.contextPath}/loan/write-off/${account.accountNumber}" class="fv-form">
                    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                    <button type="submit" class="btn btn-fv-danger" data-confirm="CONFIRM WRITE-OFF: This action is irreversible and will remove INR ${account.outstandingPrincipal} from the balance sheet."><i class="bi bi-x-octagon"></i> Write Off Account</button>
                </form>
            </div>
        </div>
        </c:if>

        <!-- CBS Loan Restructuring - ADMIN only, per RBI CDR/SDR Framework -->
        <c:if test="${pageContext.request.isUserInRole('ROLE_ADMIN')}">
        <div class="fv-card">
            <div class="card-header">Loan Restructuring <span class="badge bg-warning text-dark ms-2">RBI CDR/SDR</span></div>
            <div class="card-body">
                <p class="text-muted">Modify loan terms (rate/tenure) for stressed borrowers per RBI CDR framework. Restructured accounts get 5% provisioning for 2 years.</p>
                <form method="post" action="${pageContext.request.contextPath}/loan/restructure/${account.accountNumber}" class="fv-form">
                    <div class="row mb-3">
                        <div class="col-md-3">
                            <label class="form-label">New Interest Rate (% p.a.)</label>
                            <input type="number" name="newRate" class="form-control" data-fv-type="rate" step="0.01" min="0" placeholder="Leave blank for no change" value="" />
                        </div>
                        <div class="col-md-3">
                            <label class="form-label">Extend Tenure (months)</label>
                            <input type="number" name="additionalMonths" class="form-control" data-fv-type="numeric" min="0" max="120" value="0" />
                        </div>
                        <div class="col-md-4">
                            <label class="form-label">Reason (mandatory) *</label>
                            <input type="text" name="reason" class="form-control" required placeholder="e.g., Borrower financial stress - rate reduction" />
                        </div>
                    </div>
                    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                    <button type="submit" class="btn btn-warning" data-confirm="Confirm loan restructuring? This modifies the loan terms and flags the account per RBI CDR norms.">Restructure Loan</button>
                </form>

                <hr class="my-3" />
                <p class="text-muted">Apply moratorium (payment holiday). Interest continues to accrue during moratorium per RBI guidelines.</p>
                <form method="post" action="${pageContext.request.contextPath}/loan/moratorium/${account.accountNumber}" class="fv-form">
                    <div class="row mb-3">
                        <div class="col-md-3">
                            <label class="form-label">Moratorium Period (months) *</label>
                            <input type="number" name="moratoriumMonths" class="form-control" data-fv-type="numeric" min="1" max="24" required value="3" />
                        </div>
                        <div class="col-md-4">
                            <label class="form-label">Reason (mandatory) *</label>
                            <input type="text" name="reason" class="form-control" required placeholder="e.g., COVID-19 relief - 3 month moratorium" />
                        </div>
                    </div>
                    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                    <button type="submit" class="btn btn-outline-warning" data-confirm="Confirm moratorium? EMI payments will be deferred.">Apply Moratorium</button>
                </form>
            </div>
        </div>
        </c:if>
    </c:if>

    <!-- CBS Amortization Schedule -->
    <c:if test="${not empty schedule}">
    <div class="fv-card">
        <div class="card-header">Amortization Schedule (${schedule.size()} installments)</div>
        <div class="card-body">
            <div class="table-responsive">
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
                            <td><c:out value="${sched.paidDate}" default="--" /></td>
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
    </div>
    </c:if>

    <div class="fv-card">
        <div class="card-header">Transaction History</div>
        <div class="card-body">
            <div class="table-responsive">
            <table class="table fv-table fv-datatable">
                <thead>
                    <tr>
                        <th>Txn Ref</th>
                        <th>Type</th>
                        <th class="text-end">Amount</th>
                        <th class="text-end">Principal</th>
                        <th class="text-end">Interest</th>
                        <th class="text-end">Penalty</th>
                        <th class="text-end">Balance After</th>
                        <th>Value Date</th>
                        <th>Narration</th>
                        <th>Status</th>
                        <c:if test="${pageContext.request.isUserInRole('ROLE_CHECKER') || pageContext.request.isUserInRole('ROLE_ADMIN')}">
                        <th>Action</th>
                        </c:if>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="txn" items="${transactions}">
                        <tr class="${txn.reversed ? 'table-secondary text-decoration-line-through' : ''}">
                            <td><a href="${pageContext.request.contextPath}/txn360/${txn.transactionRef}" title="Transaction 360 View" class="font-monospace"><c:out value="${txn.transactionRef}" /></a></td>
                            <td><c:out value="${txn.transactionType}" /></td>
                            <td class="amount"><fmt:formatNumber value="${txn.amount}" type="number" maxFractionDigits="2" /></td>
                            <td class="amount"><fmt:formatNumber value="${txn.principalComponent}" type="number" maxFractionDigits="2" /></td>
                            <td class="amount"><fmt:formatNumber value="${txn.interestComponent}" type="number" maxFractionDigits="2" /></td>
                            <td class="amount"><fmt:formatNumber value="${txn.penaltyComponent}" type="number" maxFractionDigits="2" /></td>
                            <td class="amount"><fmt:formatNumber value="${txn.balanceAfter}" type="number" maxFractionDigits="2" /></td>
                            <td><c:out value="${txn.valueDate}" /></td>
                            <td><c:out value="${txn.narration}" /></td>
                            <td>
                                <c:choose>
                                    <c:when test="${txn.reversed}"><span class="fv-badge fv-badge-npa">REVERSED</span></c:when>
                                    <c:when test="${txn.transactionType == 'REVERSAL'}"><span class="fv-badge fv-badge-pending">REVERSAL</span></c:when>
                                    <c:otherwise><span class="fv-badge fv-badge-active">POSTED</span></c:otherwise>
                                </c:choose>
                            </td>
                            <c:if test="${pageContext.request.isUserInRole('ROLE_CHECKER') || pageContext.request.isUserInRole('ROLE_ADMIN')}">
                            <td>
                                <c:if test="${!txn.reversed && txn.transactionType != 'REVERSAL' && !account.status.terminal}">
                                    <form method="post" action="${pageContext.request.contextPath}/loan/reversal/${txn.transactionRef}" class="fv-form d-inline">
                                        <input type="hidden" name="accountNumber" value="${account.accountNumber}" />
                                        <input type="hidden" name="reason" value="" class="fv-reason-field" />
                                        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                                        <button type="button" class="btn btn-sm btn-outline-danger"
                                            data-fv-reason-prompt="Reversal reason (mandatory):"
                                            data-fv-reason-confirm="Reverse transaction ${txn.transactionRef}?"
                                            onclick="fvPromptReason(this);">
                                            Reverse
                                        </button>
                                    </form>
                                </c:if>
                            </td>
                            </c:if>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty transactions}">
                        <tr><td colspan="${pageContext.request.isUserInRole('ROLE_CHECKER') || pageContext.request.isUserInRole('ROLE_ADMIN') ? 11 : 10}" class="text-center text-muted">No transactions yet</td></tr>
                    </c:if>
                </tbody>
            </table>
            </div>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
