<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<%@ page import="java.util.UUID" %>
<%-- CBS Idempotency per RBI Operational Risk Guidelines (mirrors the F1 fix
     applied to deposit/withdraw.jsp): mint a server-side UUID once per page
     render. Browser retries reuse the same key and the service-layer dedupe
     returns the original DepositTransaction instead of double-debiting both
     the customer ledger AND the till. A new render gets a fresh key. --%>
<c:set var="idempotencyKey" value="<%= UUID.randomUUID().toString() %>" />
<c:set var="pageTitle" value="Teller Cash Withdrawal" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
<ul class="fv-breadcrumb">
    <li><a href="${pageContext.request.contextPath}/dashboard"><i class="bi bi-speedometer2"></i> Home</a></li>
    <li class="active">Teller / Cash Withdrawal</li>
</ul>

<%-- Flash messages: success (with optional receipt summary) and error. --%>
<c:if test="${not empty success}"><div class="fv-alert alert alert-success"><c:out value="${success}"/></div></c:if>
<c:if test="${not empty error}">
    <div class="fv-alert alert alert-danger">
        <c:if test="${not empty errorCode}"><strong>[<c:out value="${errorCode}"/>]</strong> </c:if>
        <c:out value="${error}"/>
    </div>
</c:if>

<%-- Last-withdrawal receipt summary (flash from a successful POST). --%>
<c:if test="${not empty lastReceipt}">
    <div class="fv-card mb-3">
        <div class="card-header">
            <i class="bi bi-receipt"></i> Last Withdrawal
            <c:if test="${lastReceipt.pendingApproval}"><span class="badge bg-warning ms-2">PENDING APPROVAL</span></c:if>
            <c:if test="${lastReceipt.ctrTriggered}"><span class="badge bg-info ms-2">CTR (PMLA)</span></c:if>
            <c:if test="${not empty lastReceipt.chequeNumber}"><span class="badge bg-secondary ms-2">Cheque ${lastReceipt.chequeNumber}</span></c:if>
        </div>
        <div class="card-body">
            <p>
                Ref: <strong><c:out value="${lastReceipt.transactionRef}"/></strong>
                | Voucher: <c:out value="${lastReceipt.voucherNumber}"/>
                | Account: <c:out value="${lastReceipt.accountNumber}"/>
                | Amount: <fmt:formatNumber value="${lastReceipt.amount}" type="currency" currencyCode="INR"/>
            </p>
            <p>
                Balance: <fmt:formatNumber value="${lastReceipt.balanceBefore}" type="currency" currencyCode="INR"/>
                &rarr; <strong><fmt:formatNumber value="${lastReceipt.balanceAfter}" type="currency" currencyCode="INR"/></strong>
                | Till after: <strong><fmt:formatNumber value="${lastReceipt.tillBalanceAfter}" type="currency" currencyCode="INR"/></strong>
            </p>
        </div>
    </div>
</c:if>

<%-- Till status panel -- mirrors cash-deposit.jsp. --%>
<c:choose>
    <c:when test="${empty currentTill}">
        <div class="fv-alert alert alert-warning">
            <strong>No till open for today.</strong>
            <a href="${pageContext.request.contextPath}/teller/till/open" class="alert-link">Open your till</a>
            before paying out cash.
        </div>
    </c:when>
    <c:otherwise>
        <div class="fv-card mb-3">
            <div class="card-header"><i class="bi bi-cash-stack"></i> Your Till
                <span class="badge bg-${currentTill.status == 'OPEN' ? 'success' : 'secondary'} ms-2">
                    <c:out value="${currentTill.status}"/>
                </span>
            </div>
            <div class="card-body py-2">
                Branch: <c:out value="${currentTill.branchCode}"/>
                | Business Date: <c:out value="${currentTill.businessDate}"/>
                | Cash in hand: <strong><fmt:formatNumber value="${currentTill.currentBalance}" type="currency" currencyCode="INR"/></strong>
            </div>
        </div>
    </c:otherwise>
</c:choose>

<%-- ============================================================
     Cash withdrawal form. Server-side validation is the source of truth;
     the JS denom-sum + till-cash gate is purely UX so the operator
     catches a miscount or insufficient-till BEFORE submitting.
     ============================================================ --%>
<div class="fv-card">
    <div class="card-header"><i class="bi bi-dash-circle"></i> Cash Withdrawal (Counter)</div>
    <div class="card-body">
        <form method="post" action="${pageContext.request.contextPath}/teller/cash-withdrawal"
              class="fv-form" id="tellerCashWithdrawalForm">
            <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
            <%-- CBS Idempotency: server-minted UUID -- see scriptlet at top.
                 Browser retries reuse this key; the service layer dedupes via
                 the unique idempotency index, so the till is never double-debited. --%>
            <input type="hidden" name="idempotencyKey" value="${idempotencyKey}"/>

            <div class="row mb-3">
                <div class="col-md-4 fv-mandatory-group">
                    <label for="accountNumber" class="form-label fv-required">Customer Account</label>
                    <input type="text" id="accountNumber" name="accountNumber" class="form-control"
                           required maxlength="40" tabindex="1" placeholder="SB / CA account number"/>
                </div>
                <div class="col-md-4 fv-mandatory-group">
                    <label for="amount" class="form-label fv-required">Amount (INR)</label>
                    <input type="number" id="amount" name="amount" class="form-control"
                           min="0.01" step="0.01" required tabindex="2"/>
                </div>
                <div class="col-md-4">
                    <label for="narration" class="form-label">Narration</label>
                    <input type="text" id="narration" name="narration" class="form-control"
                           maxlength="500" tabindex="3" placeholder="Optional"/>
                </div>
            </div>

            <div class="row mb-3">
                <div class="col-md-4 fv-mandatory-group">
                    <label for="beneficiaryName" class="form-label fv-required">Beneficiary Name</label>
                    <input type="text" id="beneficiaryName" name="beneficiaryName" class="form-control"
                           required maxlength="200" tabindex="4"
                           placeholder="Per PMLA 2002 §12 (recipient of cash)"/>
                </div>
                <div class="col-md-4">
                    <label for="beneficiaryMobile" class="form-label">Beneficiary Mobile</label>
                    <input type="text" id="beneficiaryMobile" name="beneficiaryMobile" class="form-control"
                           maxlength="20" tabindex="5"/>
                </div>
                <div class="col-md-4">
                    <label for="chequeNumber" class="form-label">Cheque Number</label>
                    <input type="text" id="chequeNumber" name="chequeNumber" class="form-control"
                           maxlength="20" tabindex="6" placeholder="Required for cheque-based withdrawal"/>
                </div>
            </div>

            <%-- Denomination grid placeholder -- enriched by the next edit
                 (3-column variant: NO counterfeit column on withdrawals
                 because the bank never pays out counterfeit notes). --%>
            <h5 class="mt-3">Denomination Breakdown (Cash Paid Out)</h5>
            <p class="text-muted small">
                The denomination total MUST equal the withdrawal amount above.
                Counterfeits are not accepted on the payout path -- the bank
                only dispenses genuine notes per RBI Currency Management.
            </p>
            <div id="denominationGridContainer">
                <%-- 3-column variant: Denomination | Count | Subtotal.
                     The deposit screen has 4 columns (extra "Counterfeit Count"
                     column) but withdrawals never pay out counterfeits -- the
                     CashWithdrawalRequest @AssertTrue rejects any non-zero
                     counterfeit count at the API boundary. --%>
                <div class="table-responsive">
                    <table class="table table-sm table-bordered align-middle">
                        <thead class="table-light">
                            <tr>
                                <th style="width: 30%;">Denomination</th>
                                <th style="width: 30%;">Count</th>
                                <th style="width: 40%;" class="text-end">Subtotal (INR)</th>
                            </tr>
                        </thead>
                        <tbody id="denominationGridBody">
                            <c:forEach var="d" items="${denominationOrder}" varStatus="loop">
                                <tr data-denom="${d.name()}" data-face-value="${d.value()}">
                                    <td>
                                        <c:choose>
                                            <c:when test="${d.kind() == 'COIN'}">
                                                Coin Bucket (INR value)
                                            </c:when>
                                            <c:otherwise>
                                                INR <fmt:formatNumber value="${d.value()}" type="number" maxFractionDigits="0"/>
                                                <c:if test="${not d.primaryCirculation}">
                                                    <span class="badge bg-secondary ms-1">limited</span>
                                                </c:if>
                                            </c:otherwise>
                                        </c:choose>
                                    </td>
                                    <td>
                                        <input type="number" name="denom_${d.name()}"
                                               class="form-control form-control-sm fv-denom-count"
                                               min="0" step="1" placeholder="0"
                                               tabindex="${10 + loop.index}"/>
                                    </td>
                                    <td class="text-end fv-denom-subtotal">0.00</td>
                                </tr>
                            </c:forEach>
                        </tbody>
                        <tfoot class="table-light">
                            <tr>
                                <th colspan="2" class="text-end">Denomination Total:</th>
                                <th class="text-end" id="denomGrandTotal">0.00</th>
                            </tr>
                            <tr>
                                <th colspan="2" class="text-end">Amount Above:</th>
                                <th class="text-end" id="amountEcho">0.00</th>
                            </tr>
                            <tr id="denomMatchRow">
                                <th colspan="2" class="text-end">Match:</th>
                                <th class="text-end" id="denomMatchBadge">
                                    <span class="badge bg-secondary">enter amount</span>
                                </th>
                            </tr>
                            <tr id="tillCashRow">
                                <th colspan="2" class="text-end">Till Cash Available:</th>
                                <th class="text-end" id="tillCashBadge">
                                    <span class="badge bg-secondary">--</span>
                                </th>
                            </tr>
                        </tfoot>
                    </table>
                </div>
            </div>

<%-- ============================================================
     Live-sum + till-cash JS. Identical to the deposit screen except for
     the additional "till has enough cash" gate -- the submit button
     stays disabled when amount > till.currentBalance. Authoritative
     enforcement is server-side (CBS-TELLER-006); this is UX only.
     ============================================================ --%>
<script>
(function () {
    var amountInput = document.getElementById("amount");
    var grandTotalEl = document.getElementById("denomGrandTotal");
    var amountEchoEl = document.getElementById("amountEcho");
    var matchBadge = document.getElementById("denomMatchBadge");
    var tillCashBadge = document.getElementById("tillCashBadge");
    var postBtn = document.getElementById("postWithdrawalBtn");

    // Till state pulled from the server-rendered model.
    var tillIsOpen = ${not empty currentTill and currentTill.status == 'OPEN'};
    var tillCash = parseFloat("${not empty currentTill ? currentTill.currentBalance : 0}") || 0;

    function fmt(n) {
        if (!isFinite(n)) return "0.00";
        return n.toFixed(2);
    }

    function recompute() {
        var rows = document.querySelectorAll("#denominationGridBody tr");
        var grand = 0;
        rows.forEach(function (row) {
            var face = parseFloat(row.getAttribute("data-face-value")) || 0;
            var unitInput = row.querySelector(".fv-denom-count");
            var unit = unitInput ? (parseInt(unitInput.value, 10) || 0) : 0;
            var subtotal = face * unit;
            var subEl = row.querySelector(".fv-denom-subtotal");
            if (subEl) subEl.textContent = fmt(subtotal);
            grand += subtotal;
        });
        grandTotalEl.textContent = fmt(grand);

        var amount = parseFloat(amountInput.value) || 0;
        amountEchoEl.textContent = fmt(amount);

        // Match indicator (epsilon-based).
        var matchSpan;
        if (amount <= 0) {
            matchSpan = '<span class="badge bg-secondary">enter amount</span>';
        } else if (Math.abs(grand - amount) < 0.005) {
            matchSpan = '<span class="badge bg-success">match</span>';
        } else {
            matchSpan = '<span class="badge bg-danger">diff '
                + fmt(grand - amount) + '</span>';
        }
        matchBadge.innerHTML = matchSpan;

        // Till-cash availability indicator.
        var tillSpan;
        if (amount <= 0) {
            tillSpan = '<span class="badge bg-secondary">' + fmt(tillCash) + '</span>';
        } else if (tillCash >= amount) {
            tillSpan = '<span class="badge bg-success">'
                + fmt(tillCash) + ' &ge; ' + fmt(amount) + '</span>';
        } else {
            tillSpan = '<span class="badge bg-danger">'
                + fmt(tillCash) + ' &lt; ' + fmt(amount)
                + ' (request vault buy)</span>';
        }
        tillCashBadge.innerHTML = tillSpan;

        // Submit gate: till OPEN, denom sum matches, AND till has enough cash.
        var canSubmit = tillIsOpen
                && amount > 0
                && Math.abs(grand - amount) < 0.005
                && tillCash >= amount;
        postBtn.disabled = !canSubmit;
    }

    document.querySelectorAll("#denominationGridBody input")
        .forEach(function (el) { el.addEventListener("input", recompute); });
    if (amountInput) amountInput.addEventListener("input", recompute);

    recompute();
})();
</script>

            <div class="mt-3">
                <button type="submit" class="btn btn-fv-primary" id="postWithdrawalBtn"
                        data-confirm="Confirm cash payout? This will debit the customer account and decrement your till.">
                    <i class="bi bi-check-circle"></i> Pay Out Cash <span class="fv-kbd">F2</span>
                </button>
                <a href="${pageContext.request.contextPath}/dashboard" class="btn btn-outline-secondary ms-2">
                    <i class="bi bi-x-circle"></i> Cancel <span class="fv-kbd">F3</span>
                </a>
            </div>
        </form>
    </div>
</div>
</div>

<%@ include file="../layout/footer.jsp" %>
