<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Account Details" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
<c:if test="${not empty success}"><div class="alert alert-success"><c:out value="${success}"/></div></c:if>
<c:if test="${not empty error}"><div class="alert alert-danger"><c:out value="${error}"/></div></c:if>

<div class="d-flex justify-content-between align-items-center mb-3">
    <h4>Account: <c:out value="${account.accountNumber}"/></h4>
    <div>
        <span class="badge fs-6 ${account.active ? 'bg-success' : account.frozen ? 'bg-danger' : account.dormant ? 'bg-warning' : 'bg-secondary'}"><c:out value="${account.accountStatus}"/></span>
        <a href="${pageContext.request.contextPath}/deposit/accounts" class="btn btn-sm btn-outline-secondary ms-2"><i class="bi bi-arrow-left"></i> Back</a>
    </div>
</div>

<div class="row mb-4">
<div class="col-md-6">
<div class="card"><div class="card-body">
    <h6 class="card-title">Account Information</h6>
    <table class="table table-sm mb-0">
    <tr><td class="text-muted">Account Type</td><td><strong><c:out value="${account.accountType}"/></strong></td></tr>
    <tr><td class="text-muted">Product Code</td><td><c:out value="${account.productCode}"/></td></tr>
    <tr><td class="text-muted">Currency</td><td><c:out value="${account.currencyCode}"/></td></tr>
    <tr><td class="text-muted">Customer</td><td><a href="${pageContext.request.contextPath}/customer/view/${account.customer.id}"><c:out value="${account.customer.firstName}"/> <c:out value="${account.customer.lastName}"/></a> (<c:out value="${account.customer.customerNumber}"/>)</td></tr>
    <tr><td class="text-muted">Branch</td><td><a href="${pageContext.request.contextPath}/branch/view/${account.branch.id}"><c:out value="${account.branch.branchName}"/></a> (<c:out value="${account.branch.branchCode}"/>)</td></tr>
    <tr><td class="text-muted">Opened Date</td><td><c:out value="${account.openedDate}"/></td></tr>
    <tr><td class="text-muted">Last Transaction</td><td><c:out value="${account.lastTransactionDate}" default="--"/></td></tr>
    <tr><td class="text-muted">Nominee</td><td><c:out value="${account.nomineeName}" default="Not set"/><c:if test="${not empty account.nomineeRelationship}"> (<c:out value="${account.nomineeRelationship}"/>)</c:if></td></tr>
    <c:if test="${not empty account.jointHolderMode}">
    <tr><td class="text-muted">Joint Holder Mode</td><td><c:out value="${account.jointHolderMode}"/></td></tr>
    </c:if>
    <c:if test="${account.savings}">
    <tr><td class="text-muted">Interest Rate</td><td><fmt:formatNumber value="${account.interestRate}" maxFractionDigits="4"/>% p.a.</td></tr>
    <tr><td class="text-muted">Accrued Interest</td><td><fmt:formatNumber value="${account.accruedInterest}" type="currency" currencyCode="INR"/></td></tr>
    <tr><td class="text-muted">Last Interest Accrual</td><td><c:out value="${account.lastInterestAccrualDate}" default="--"/></td></tr>
    <tr><td class="text-muted">Last Interest Credit</td><td><c:out value="${account.lastInterestCreditDate}" default="--"/></td></tr>
    <tr><td class="text-muted">YTD Interest Credited</td><td><fmt:formatNumber value="${account.ytdInterestCredited}" type="currency" currencyCode="INR"/></td></tr>
    <tr><td class="text-muted">YTD TDS Deducted</td><td><fmt:formatNumber value="${account.ytdTdsDeducted}" type="currency" currencyCode="INR"/></td></tr>
    </c:if>
    <c:if test="${account.dailyWithdrawalLimit != null && account.dailyWithdrawalLimit.signum() > 0}">
    <tr><td class="text-muted">Daily Withdrawal Limit</td><td><fmt:formatNumber value="${account.dailyWithdrawalLimit}" type="currency" currencyCode="INR"/></td></tr>
    </c:if>
    <c:if test="${account.dailyTransferLimit != null && account.dailyTransferLimit.signum() > 0}">
    <tr><td class="text-muted">Daily Transfer Limit</td><td><fmt:formatNumber value="${account.dailyTransferLimit}" type="currency" currencyCode="INR"/></td></tr>
    </c:if>
    <tr><td class="text-muted">Cheque Book</td><td><c:choose><c:when test="${account.chequeBookEnabled}"><span class="fv-badge fv-badge-active">Enabled</span></c:when><c:otherwise><span class="text-muted">No</span></c:otherwise></c:choose></td></tr>
    <tr><td class="text-muted">Debit Card</td><td><c:choose><c:when test="${account.debitCardEnabled}"><span class="fv-badge fv-badge-active">Enabled</span></c:when><c:otherwise><span class="text-muted">No</span></c:otherwise></c:choose></td></tr>
    <c:if test="${account.frozen}">
    <tr><td class="text-muted">Freeze Type</td><td class="text-danger"><c:out value="${account.freezeType}"/></td></tr>
    <tr><td class="text-muted">Freeze Reason</td><td class="text-danger"><c:out value="${account.freezeReason}"/></td></tr>
    </c:if>
    <c:if test="${account.dormant}">
    <tr><td class="text-muted">Dormant Since</td><td class="text-warning"><c:out value="${account.dormantDate}"/></td></tr>
    </c:if>
    <c:if test="${account.closed}">
    <tr><td class="text-muted">Closed Date</td><td><c:out value="${account.closedDate}"/></td></tr>
    <tr><td class="text-muted">Closure Reason</td><td><c:out value="${account.closureReason}"/></td></tr>
    </c:if>
    <tr><td class="text-muted">Opened By</td><td><c:out value="${account.createdBy}" default="--"/></td></tr>
    </table>
</div></div>
</div>
<div class="col-md-6">
<div class="card"><div class="card-body">
    <h6 class="card-title">Balance Summary</h6>
    <table class="table table-sm mb-0">
    <tr><td class="text-muted">Ledger Balance</td><td class="text-end fs-5"><strong><fmt:formatNumber value="${account.ledgerBalance}" type="currency" currencyCode="INR"/></strong></td></tr>
    <tr><td class="text-muted">Hold/Lien Amount</td><td class="text-end"><fmt:formatNumber value="${account.holdAmount}" type="currency" currencyCode="INR"/></td></tr>
    <tr><td class="text-muted">Uncleared Funds</td><td class="text-end"><fmt:formatNumber value="${account.unclearedAmount}" type="currency" currencyCode="INR"/></td></tr>
    <tr><td class="text-muted">Minimum Balance</td><td class="text-end"><fmt:formatNumber value="${account.minimumBalance}" type="currency" currencyCode="INR"/></td></tr>
    <c:if test="${account.odLimit.signum() > 0}">
    <tr><td class="text-muted">OD Limit</td><td class="text-end"><fmt:formatNumber value="${account.odLimit}" type="currency" currencyCode="INR"/></td></tr>
    </c:if>
    <tr class="table-active"><td class="text-muted"><strong>Available Balance</strong></td><td class="text-end fs-5"><strong><fmt:formatNumber value="${account.effectiveAvailable}" type="currency" currencyCode="INR"/></strong></td></tr>
    </table>
</div></div>

<c:if test="${account.accountStatus == 'PENDING_ACTIVATION' && (pageContext.request.isUserInRole('ROLE_CHECKER') || pageContext.request.isUserInRole('ROLE_ADMIN'))}">
<div class="card mt-3 border-warning"><div class="card-body">
    <div class="d-flex align-items-center gap-3">
        <span class="badge bg-warning text-dark fs-6"><i class="bi bi-hourglass-split"></i> Pending Activation</span>
        <span class="text-muted">This account requires checker approval before transactions can be processed.</span>
        <form method="post" action="${pageContext.request.contextPath}/deposit/activate/${account.accountNumber}" class="d-inline ms-auto">
            <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
            <button type="submit" class="btn btn-success btn-sm" data-confirm="Activate this account? It will become operational immediately."><i class="bi bi-check-circle"></i> Activate Account</button>
        </form>
    </div>
</div></div>
</c:if>

<c:if test="${account.active}">
<div class="card mt-3"><div class="card-body d-flex gap-2 flex-wrap">
    <a href="${pageContext.request.contextPath}/deposit/deposit/${account.accountNumber}" class="btn btn-success btn-sm"><i class="bi bi-plus-circle"></i> Deposit</a>
    <a href="${pageContext.request.contextPath}/deposit/withdraw/${account.accountNumber}" class="btn btn-warning btn-sm"><i class="bi bi-dash-circle"></i> Withdraw</a>
    <a href="${pageContext.request.contextPath}/deposit/transfer" class="btn btn-info btn-sm"><i class="bi bi-arrow-left-right"></i> Transfer</a>
    <a href="${pageContext.request.contextPath}/deposit/statement/${account.accountNumber}" class="btn btn-outline-secondary btn-sm"><i class="bi bi-journal-text"></i> Statement</a>
    <c:if test="${pageContext.request.isUserInRole('ROLE_ADMIN')}">
    <form method="post" action="${pageContext.request.contextPath}/deposit/freeze/${account.accountNumber}" class="d-inline">
        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
        <input type="hidden" name="freezeType" value="TOTAL_FREEZE"/>
        <input type="hidden" name="reason" value="Admin freeze"/>
        <button type="submit" class="btn btn-danger btn-sm" onclick="return confirm('Freeze this account?')"><i class="bi bi-lock"></i> Freeze</button>
    </form>
    </c:if>
</div></div>
</c:if>
<c:if test="${account.frozen && pageContext.request.isUserInRole('ROLE_ADMIN')}">
<div class="card mt-3"><div class="card-body">
    <form method="post" action="${pageContext.request.contextPath}/deposit/unfreeze/${account.accountNumber}" class="d-inline">
        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
        <button type="submit" class="btn btn-success btn-sm" onclick="return confirm('Unfreeze this account?')"><i class="bi bi-unlock"></i> Unfreeze Account</button>
    </form>
</div></div>
</c:if>
<c:if test="${(pageContext.request.isUserInRole('ROLE_CHECKER') || pageContext.request.isUserInRole('ROLE_ADMIN')) && !account.closed}">
<div class="card mt-3"><div class="card-body">
    <form method="post" action="${pageContext.request.contextPath}/deposit/close/${account.accountNumber}" class="d-inline">
        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
        <input type="hidden" name="reason" value="" id="closeReason"/>
        <button type="submit" class="btn btn-outline-danger btn-sm"
            onclick="var r=prompt('Closure reason (mandatory):'); if(!r){return false;} document.getElementById('closeReason').value=r; return confirm('Close this account? Balance must be zero.');">
            <i class="bi bi-x-circle"></i> Close Account
        </button>
    </form>
</div></div>
</c:if>
</div></div>

<!-- CBS Account Maintenance per Finacle ACCTMOD -->
<c:if test="${account.active && (pageContext.request.isUserInRole('ROLE_CHECKER') || pageContext.request.isUserInRole('ROLE_ADMIN'))}">
<div class="fv-card mt-3">
    <div class="card-header"><i class="bi bi-gear"></i> Account Maintenance (Finacle ACCTMOD)</div>
    <div class="card-body">
        <p class="text-muted small">Modify operational parameters on this ACTIVE account. All changes are audited per RBI IT Governance §8.3.</p>
        <form method="post" action="${pageContext.request.contextPath}/deposit/maintain/${account.accountNumber}" class="fv-form">
            <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
            <div class="row mb-3">
                <div class="col-md-3">
                    <label class="form-label small">Nominee Name</label>
                    <input type="text" name="nomineeName" class="form-control form-control-sm" data-fv-type="name" value="<c:out value='${account.nomineeName}'/>" maxlength="200"/>
                </div>
                <div class="col-md-2">
                    <label class="form-label small">Nominee Relationship</label>
                    <select name="nomineeRelationship" class="form-select form-select-sm">
                        <option value="">-- None --</option>
                        <option value="SPOUSE" ${account.nomineeRelationship == 'SPOUSE' ? 'selected' : ''}>Spouse</option>
                        <option value="CHILD" ${account.nomineeRelationship == 'CHILD' ? 'selected' : ''}>Child</option>
                        <option value="PARENT" ${account.nomineeRelationship == 'PARENT' ? 'selected' : ''}>Parent</option>
                        <option value="SIBLING" ${account.nomineeRelationship == 'SIBLING' ? 'selected' : ''}>Sibling</option>
                        <option value="OTHER" ${account.nomineeRelationship == 'OTHER' ? 'selected' : ''}>Other</option>
                    </select>
                </div>
                <div class="col-md-2">
                    <label class="form-label small">Joint Holder Mode</label>
                    <select name="jointHolderMode" class="form-select form-select-sm">
                        <option value="">-- N/A --</option>
                        <option value="EITHER_SURVIVOR" ${account.jointHolderMode == 'EITHER_SURVIVOR' ? 'selected' : ''}>Either/Survivor</option>
                        <option value="FORMER_SURVIVOR" ${account.jointHolderMode == 'FORMER_SURVIVOR' ? 'selected' : ''}>Former/Survivor</option>
                        <option value="JOINTLY" ${account.jointHolderMode == 'JOINTLY' ? 'selected' : ''}>Jointly</option>
                    </select>
                </div>
                <div class="col-md-2">
                    <label class="form-label small">Cheque Book</label>
                    <select name="chequeBookEnabled" class="form-select form-select-sm">
                        <option value="false" ${!account.chequeBookEnabled ? 'selected' : ''}>Disabled</option>
                        <option value="true" ${account.chequeBookEnabled ? 'selected' : ''}>Enabled</option>
                    </select>
                </div>
                <div class="col-md-2">
                    <label class="form-label small">Debit Card</label>
                    <select name="debitCardEnabled" class="form-select form-select-sm">
                        <option value="false" ${!account.debitCardEnabled ? 'selected' : ''}>Disabled</option>
                        <option value="true" ${account.debitCardEnabled ? 'selected' : ''}>Enabled</option>
                    </select>
                </div>
            </div>
            <div class="row mb-3">
                <div class="col-md-2">
                    <label class="form-label small">Daily Withdrawal Limit</label>
                    <input type="number" name="dailyWithdrawalLimit" class="form-control form-control-sm" data-fv-type="amount" value="${account.dailyWithdrawalLimit}"/>
                    <small class="text-muted">0 = unlimited</small>
                </div>
                <div class="col-md-2">
                    <label class="form-label small">Daily Transfer Limit</label>
                    <input type="number" name="dailyTransferLimit" class="form-control form-control-sm" data-fv-type="amount" value="${account.dailyTransferLimit}"/>
                    <small class="text-muted">0 = unlimited</small>
                </div>
                <c:if test="${account.accountType == 'CURRENT_OD'}">
                <div class="col-md-2">
                    <label class="form-label small">OD Limit (INR)</label>
                    <input type="number" name="odLimit" class="form-control form-control-sm" data-fv-type="amount" value="${account.odLimit}"/>
                </div>
                </c:if>
                <c:if test="${account.savings}">
                <div class="col-md-2">
                    <label class="form-label small">Interest Rate % p.a.</label>
                    <input type="number" name="interestRate" class="form-control form-control-sm" data-fv-type="rate" step="0.0001" value="${account.interestRate}"/>
                    <small class="text-muted">Per-account override</small>
                </div>
                </c:if>
                <div class="col-md-2">
                    <label class="form-label small">Min Balance (INR)</label>
                    <input type="number" name="minimumBalance" class="form-control form-control-sm" data-fv-type="amount" value="${account.minimumBalance}"/>
                    <small class="text-muted">0 = no minimum</small>
                </div>
                <div class="col-md-2 d-flex align-items-end">
                    <button type="submit" class="btn btn-sm btn-fv-primary" data-confirm="Save account maintenance changes? All modifications will be audited."><i class="bi bi-check-circle"></i> Save Changes</button>
                </div>
            </div>
        </form>
    </div>
</div>
</c:if>

<!-- CBS Standing Instructions on this CASA Account -->
<c:if test="${not empty standingInstructions}">
<div class="fv-card mt-3">
    <div class="card-header"><i class="bi bi-arrow-repeat"></i> Standing Instructions <span class="badge bg-secondary"><c:out value="${standingInstructions.size()}" /></span></div>
    <div class="card-body">
        <div class="table-responsive">
        <table class="table fv-table table-sm">
            <thead><tr><th>SI Ref</th><th>Type</th><th>Destination</th><th>Amount</th><th>Frequency</th><th>Next Exec</th><th>Last Status</th><th>Status</th></tr></thead>
            <tbody>
            <c:forEach var="si" items="${standingInstructions}">
                <tr>
                    <td class="font-monospace small"><c:out value="${si.siReference}" /></td>
                    <td><span class="badge ${si.destinationType == 'LOAN_EMI' ? 'bg-primary' : 'bg-info'}"><c:out value="${si.destinationType}" /></span></td>
                    <td><c:out value="${si.destinationAccountNumber}" default="--" /></td>
                    <td><c:if test="${si.amount != null}"><fmt:formatNumber value="${si.amount}" type="currency" currencyCode="INR"/></c:if><c:if test="${si.amount == null}"><span class="text-muted">Dynamic (EMI)</span></c:if></td>
                    <td><c:out value="${si.frequency}" /></td>
                    <td><c:out value="${si.nextExecutionDate}" default="--" /></td>
                    <td><c:choose>
                        <c:when test="${si.lastExecutionStatus == 'SUCCESS'}"><span class="fv-badge fv-badge-active">SUCCESS</span></c:when>
                        <c:when test="${si.lastExecutionStatus != null && si.lastExecutionStatus.startsWith('FAILED')}"><span class="fv-badge fv-badge-npa"><c:out value="${si.lastExecutionStatus}" /></span></c:when>
                        <c:otherwise><span class="text-muted">--</span></c:otherwise>
                    </c:choose></td>
                    <td><c:choose>
                        <c:when test="${si.status == 'ACTIVE'}"><span class="fv-badge fv-badge-active">ACTIVE</span></c:when>
                        <c:when test="${si.status == 'PAUSED'}"><span class="fv-badge fv-badge-pending">PAUSED</span></c:when>
                        <c:when test="${si.status == 'PENDING_APPROVAL'}"><span class="fv-badge fv-badge-pending">PENDING</span></c:when>
                        <c:otherwise><span class="fv-badge fv-badge-closed"><c:out value="${si.status}" /></span></c:otherwise>
                    </c:choose></td>
                </tr>
            </c:forEach>
            </tbody>
        </table>
        </div>
    </div>
</div>
</c:if>

<!-- CBS: Register New Standing Instruction (MAKER/ADMIN) -->
<c:if test="${account.active && (pageContext.request.isUserInRole('ROLE_MAKER') || pageContext.request.isUserInRole('ROLE_ADMIN'))}">
<div class="fv-card mt-3">
    <div class="card-header"><i class="bi bi-plus-circle"></i> Register Standing Instruction</div>
    <div class="card-body">
        <p class="text-muted small">Register a recurring auto-debit from this CASA account. Per RBI Payment Systems: requires checker approval before activation.</p>
        <form method="post" action="${pageContext.request.contextPath}/loan/si/register" class="fv-form">
            <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
            <input type="hidden" name="customerId" value="${account.customer.id}"/>
            <input type="hidden" name="sourceAccountNumber" value="<c:out value='${account.accountNumber}'/>"/>
            <div class="row mb-3">
                <div class="col-md-3">
                    <label class="form-label">Destination Type *</label>
                    <select name="destinationType" class="form-select" required>
                        <option value="INTERNAL_TRANSFER">Internal Transfer</option>
                        <option value="RD_CONTRIBUTION">RD Contribution</option>
                        <option value="SIP">SIP (Mutual Fund)</option>
                        <option value="UTILITY">Utility Payment</option>
                    </select>
                </div>
                <div class="col-md-3">
                    <label class="form-label">Target Account *</label>
                    <select name="destinationAccountNumber" class="form-select" required>
                        <option value="">-- Select --</option>
                        <c:forEach var="acct" items="${activeAccounts}">
                            <c:if test="${acct.accountNumber != account.accountNumber}">
                            <option value="<c:out value='${acct.accountNumber}'/>"><c:out value="${acct.accountNumber}" /> (<c:out value="${acct.accountType}" />)</option>
                            </c:if>
                        </c:forEach>
                    </select>
                </div>
                <div class="col-md-2">
                    <label class="form-label">Amount (INR) *</label>
                    <input type="number" name="amount" class="form-control" data-fv-type="amount" min="1" required/>
                </div>
                <div class="col-md-2">
                    <label class="form-label">Frequency *</label>
                    <select name="frequency" class="form-select" required>
                        <option value="MONTHLY">Monthly</option>
                        <option value="QUARTERLY">Quarterly</option>
                        <option value="WEEKLY">Weekly</option>
                        <option value="DAILY">Daily</option>
                    </select>
                </div>
                <div class="col-md-2">
                    <label class="form-label">Exec Day (1-28) *</label>
                    <input type="number" name="executionDay" class="form-control" min="1" max="28" value="5" required/>
                </div>
            </div>
            <div class="row mb-3">
                <div class="col-md-3">
                    <label class="form-label">Start Date *</label>
                    <input type="date" name="startDate" class="form-control" required/>
                </div>
                <div class="col-md-3">
                    <label class="form-label">End Date</label>
                    <input type="date" name="endDate" class="form-control"/>
                    <small class="text-muted">Leave blank for perpetual</small>
                </div>
                <div class="col-md-6">
                    <label class="form-label">Narration</label>
                    <input type="text" name="narration" class="form-control" placeholder="e.g., Monthly SIP to MF account" maxlength="200"/>
                </div>
            </div>
            <button type="submit" class="btn btn-fv-primary btn-sm" data-confirm="Register this Standing Instruction? It will require checker approval."><i class="bi bi-plus-circle"></i> Register SI (Pending Approval)</button>
        </form>
    </div>
</div>
</c:if>

<h5 class="mt-4">Recent Transactions</h5>
<div class="table-responsive">
<table class="table fv-table fv-datatable table-sm">
<thead><tr><th>Date</th><th>Txn Ref</th><th>Type</th><th>Channel</th><th>Narration</th><th class="text-end">Amount</th><th class="text-end">Balance</th><th>Voucher</th><th>Journal</th><th>Status</th>
<c:if test="${pageContext.request.isUserInRole('ROLE_CHECKER') || pageContext.request.isUserInRole('ROLE_ADMIN')}"><th>Action</th></c:if>
</tr></thead>
<tbody>
<c:forEach var="t" items="${transactions}">
<tr class="${t.reversed ? 'table-secondary text-decoration-line-through' : ''}">
    <td><c:out value="${t.postingDate}"/></td>
    <td><small class="font-monospace"><c:out value="${t.transactionRef}"/></small></td>
    <td><c:out value="${t.transactionType}"/></td>
    <td><c:out value="${t.channel}"/></td>
    <td><c:out value="${t.narration}"/></td>
    <td class="text-end ${t.debitCredit == 'DEBIT' ? 'text-danger' : 'text-success'}"><fmt:formatNumber value="${t.amount}" type="currency" currencyCode="INR"/></td>
    <td class="text-end"><fmt:formatNumber value="${t.balanceAfter}" type="currency" currencyCode="INR"/></td>
    <td><small class="font-monospace"><c:out value="${t.voucherNumber}"/></small></td>
    <td><small class="font-monospace"><c:out value="${t.journalEntryId}" default="--"/></small></td>
    <td><c:choose>
        <c:when test="${t.reversed}"><span class="fv-badge fv-badge-npa">REVERSED</span></c:when>
        <c:when test="${t.transactionType == 'REVERSAL'}"><span class="fv-badge fv-badge-pending">REVERSAL</span></c:when>
        <c:otherwise><span class="fv-badge fv-badge-active">POSTED</span></c:otherwise>
    </c:choose></td>
    <c:if test="${pageContext.request.isUserInRole('ROLE_CHECKER') || pageContext.request.isUserInRole('ROLE_ADMIN')}">
    <td><c:if test="${!t.reversed && t.transactionType != 'REVERSAL' && !account.closed}">
        <form method="post" action="${pageContext.request.contextPath}/deposit/reversal/${t.transactionRef}" style="display:inline">
            <input type="hidden" name="accountNumber" value="<c:out value='${account.accountNumber}'/>"/>
            <input type="hidden" name="reason" value="" id="reason_${t.id}"/>
            <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
            <button type="submit" class="btn btn-sm btn-outline-danger"
                onclick="var r=prompt('Reversal reason (mandatory):'); if(!r){return false;} document.getElementById('reason_${t.id}').value=r; return confirm('Reverse this transaction?');">
                Reverse
            </button>
        </form>
    </c:if></td>
    </c:if>
</tr>
</c:forEach>
<c:if test="${empty transactions}"><tr><td colspan="${pageContext.request.isUserInRole('ROLE_CHECKER') || pageContext.request.isUserInRole('ROLE_ADMIN') ? 11 : 10}" class="text-center text-muted">No transactions</td></tr></c:if>
</tbody></table>
</div>
</div>

<%@ include file="../layout/footer.jsp" %>
