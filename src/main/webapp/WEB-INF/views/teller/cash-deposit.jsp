<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<%@ page import="java.util.UUID" %>
<%-- CBS Idempotency per RBI Operational Risk Guidelines (mirrors the F1 fix
     applied to deposit/deposit.jsp): mint a server-side UUID once per page
     render. The same key flows on every browser-initiated retry of this form
     submission, so the service-layer findByTenantIdAndIdempotencyKey dedupe
     returns the original DepositTransaction instead of double-posting both
     the customer ledger AND the till. A new render of the page (e.g., after
     success) gets a fresh key. --%>
<c:set var="idempotencyKey" value="<%= UUID.randomUUID().toString() %>" />
<c:set var="pageTitle" value="Teller Cash Deposit" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
<ul class="fv-breadcrumb">
    <li><a href="${pageContext.request.contextPath}/dashboard"><i class="bi bi-speedometer2"></i> Home</a></li>
    <li class="active">Teller / Cash Deposit</li>
</ul>

<%-- Flash messages: success (with optional receipt summary) and error. --%>
<c:if test="${not empty success}"><div class="fv-alert alert alert-success"><c:out value="${success}"/></div></c:if>
<c:if test="${not empty error}">
    <div class="fv-alert alert alert-danger">
        <c:if test="${not empty errorCode}"><strong>[<c:out value="${errorCode}"/>]</strong> </c:if>
        <c:out value="${error}"/>
    </div>
</c:if>

<%-- ============================================================
     FICN Acknowledgement slip per RBI Master Direction on Counterfeit
     Notes. Rendered when the previous POST detected counterfeit notes
     and FicnRegisterService committed a CounterfeitNoteRegister row.
     The slip is the customer's regulatory receipt and MUST be printed --
     the .fv-ficn-print scope is wired to a print-stylesheet rule so a
     print dialog produces a clean A4 receipt without the surrounding
     chrome. See ficnAcknowledgement flash attribute set by
     TellerWebController.submitCashDeposit (FICN catch block).

     Mutually exclusive with the lastReceipt card above -- a single POST
     either succeeds (lastReceipt) or fails with FICN (ficnAcknowledgement).
     ============================================================ --%>
<c:if test="${not empty ficnAcknowledgement}">
    <div class="fv-card mb-3 border-danger fv-ficn-print">
        <div class="card-header bg-danger text-white">
            <i class="bi bi-shield-exclamation"></i> FICN Acknowledgement -- Counterfeit Notes Impounded
            <c:if test="${ficnAcknowledgement.firRequired}">
                <span class="badge bg-warning text-dark ms-2">FIR MANDATORY (RBI: count &ge; 5)</span>
            </c:if>
            <span class="float-end">
                <button type="button" class="btn btn-sm btn-light"
                        onclick="window.print();return false;">
                    <i class="bi bi-printer"></i> Print Slip
                </button>
            </span>
        </div>
        <div class="card-body">
            <p class="mb-2">
                <strong>Register Ref:</strong>
                <code><c:out value="${ficnAcknowledgement.registerRef}"/></code>
                | Branch: <c:out value="${ficnAcknowledgement.branchCode}"/>
                (<c:out value="${ficnAcknowledgement.branchName}"/>)
                | Detected: <c:out value="${ficnAcknowledgement.detectionTimestamp}"/>
                | Teller: <c:out value="${ficnAcknowledgement.detectedByTeller}"/>
            </p>
            <p class="mb-2">
                <strong>Depositor:</strong> <c:out value="${ficnAcknowledgement.depositorName}"/>
                <c:if test="${not empty ficnAcknowledgement.depositorIdType}">
                    | <c:out value="${ficnAcknowledgement.depositorIdType}"/>:
                    <c:out value="${ficnAcknowledgement.depositorIdNumber}"/>
                </c:if>
                <c:if test="${not empty ficnAcknowledgement.depositorMobile}">
                    | Mobile: <c:out value="${ficnAcknowledgement.depositorMobile}"/>
                </c:if>
            </p>

            <h6 class="mt-3">Impounded Denominations</h6>
            <div class="table-responsive">
                <table class="table table-sm table-bordered align-middle">
                    <thead class="table-light">
                        <tr>
                            <th>Denomination</th>
                            <th class="text-end">Count</th>
                            <th class="text-end">Face Value (INR)</th>
                        </tr>
                    </thead>
                    <tbody>
                        <c:forEach var="line" items="${ficnAcknowledgement.impoundedDenominations}">
                            <tr>
                                <td><c:out value="${line.denomination}"/></td>
                                <td class="text-end"><c:out value="${line.counterfeitCount}"/></td>
                                <td class="text-end"><fmt:formatNumber value="${line.totalFaceValue}" type="currency" currencyCode="INR"/></td>
                            </tr>
                        </c:forEach>
                    </tbody>
                    <tfoot class="table-light">
                        <tr>
                            <th>Total Impounded</th>
                            <th class="text-end">--</th>
                            <th class="text-end"><fmt:formatNumber value="${ficnAcknowledgement.totalFaceValue}" type="currency" currencyCode="INR"/></th>
                        </tr>
                    </tfoot>
                </table>
            </div>

            <p class="text-muted small mb-1">
                <strong>Chest Dispatch Status:</strong>
                <c:out value="${ficnAcknowledgement.chestDispatchStatus}"/>
                -- the impounded notes will be remitted to the nearest currency chest per RBI Master Direction.
            </p>
            <c:if test="${ficnAcknowledgement.firRequired}">
                <p class="text-danger small mb-0">
                    <strong>FIR Notice:</strong> per RBI Master Direction, this incident
                    (counterfeit count &ge; 5 in a single transaction) requires the branch
                    to file an FIR with the local police. The customer will be notified of
                    the FIR reference once filed.
                </p>
            </c:if>
        </div>
    </div>
</c:if>

<%-- Last-deposit receipt summary (flash from a successful POST). --%>
<c:if test="${not empty lastReceipt}">
    <div class="fv-card mb-3">
        <div class="card-header">
            <i class="bi bi-receipt"></i> Last Deposit
            <c:if test="${lastReceipt.pendingApproval}"><span class="badge bg-warning ms-2">PENDING APPROVAL</span></c:if>
            <c:if test="${lastReceipt.ctrTriggered}"><span class="badge bg-info ms-2">CTR (PMLA)</span></c:if>
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

<%-- Till status panel. Always renders so the operator sees their cash-in-hand
     and so the form-submit JS can disable when the till is not OPEN. --%>
<c:choose>
    <c:when test="${empty currentTill}">
        <div class="fv-alert alert alert-warning">
            <strong>No till open for today.</strong>
            <a href="${pageContext.request.contextPath}/teller/till/open" class="alert-link">Open your till</a>
            before posting cash deposits.
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
     Cash deposit form. Server-side validation is the source of truth;
     the JS denom-sum guard below is purely UX so the operator catches
     a miscount before submitting.
     ============================================================ --%>
<div class="fv-card">
    <div class="card-header"><i class="bi bi-plus-circle"></i> Cash Deposit (Counter)</div>
    <div class="card-body">
        <form method="post" action="${pageContext.request.contextPath}/teller/cash-deposit"
              class="fv-form" id="tellerCashDepositForm">
            <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
            <%-- CBS Idempotency: server-minted UUID -- see scriptlet at top.
                 Browser retries (refresh / back-button resubmit) reuse this key
                 and the service layer dedupes via the unique idempotency
                 index, so the till is never double-credited. --%>
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
                    <label for="depositorName" class="form-label fv-required">Depositor Name</label>
                    <input type="text" id="depositorName" name="depositorName" class="form-control"
                           required maxlength="200" tabindex="4"
                           placeholder="Per PMLA 2002 §12"/>
                </div>
                <div class="col-md-4">
                    <label for="depositorMobile" class="form-label">Depositor Mobile</label>
                    <input type="text" id="depositorMobile" name="depositorMobile" class="form-control"
                           maxlength="20" tabindex="5"/>
                </div>
            </div>

            <%-- CTR/PMLA panel (shown via JS when amount >= ctrPanThreshold). --%>
            <div id="ctrPanel" style="display:none;" class="border rounded p-3 mb-3 bg-light">
                <p class="mb-2">
                    <i class="bi bi-shield-exclamation text-danger"></i>
                    <strong>CTR threshold:</strong> deposits at or above
                    <fmt:formatNumber value="${ctrPanThreshold}" type="currency" currencyCode="INR"/>
                    require depositor PAN or Form 60/61 per <em>PMLA Rule 9</em>.
                </p>
                <div class="row">
                    <div class="col-md-4">
                        <label for="panNumber" class="form-label">Depositor PAN</label>
                        <input type="text" id="panNumber" name="panNumber" class="form-control"
                               maxlength="10" pattern="[A-Z]{5}[0-9]{4}[A-Z]"
                               placeholder="ABCDE1234F"/>
                    </div>
                    <div class="col-md-4">
                        <label for="form60Reference" class="form-label">Form 60/61 Ref</label>
                        <input type="text" id="form60Reference" name="form60Reference" class="form-control"
                               maxlength="30" placeholder="Required when PAN unavailable"/>
                    </div>
                </div>
            </div>

            <%-- Denomination grid placeholder -- enriched in the next edit. --%>
            <h5 class="mt-3">Denomination Breakdown</h5>
            <p class="text-muted small">
                Per RBI Currency Management. The denomination total MUST equal
                the deposit amount above. Counterfeit notes (if any) trigger
                the FICN flow per RBI Master Direction on Counterfeit Notes --
                the entire deposit is rejected and a separate FICN
                acknowledgement workflow runs.
            </p>
            <div id="denominationGridContainer">
                <%-- One row per IndianCurrencyDenomination, driven by the
                     ${denominationOrder} list pushed from the controller --
                     single source of truth for grid ordering. The face-value
                     attribute on the row carries the rupee amount used by
                     the live-sum JS so the UI never needs to map enum names
                     back to amounts. --%>
                <div class="table-responsive">
                    <table class="table table-sm table-bordered align-middle">
                        <thead class="table-light">
                            <tr>
                                <th style="width: 25%;">Denomination</th>
                                <th style="width: 20%;">Count</th>
                                <th style="width: 20%;">Counterfeit Count</th>
                                <th style="width: 35%;" class="text-end">Subtotal (INR)</th>
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
                                    <td>
                                        <c:choose>
                                            <c:when test="${d.kind() == 'NOTE'}">
                                                <input type="number" name="counterfeit_${d.name()}"
                                                       class="form-control form-control-sm fv-counterfeit-count"
                                                       min="0" step="1" placeholder="0"
                                                       tabindex="${30 + loop.index}"/>
                                            </c:when>
                                            <c:otherwise>
                                                <%-- Coins are not subject to FICN reporting per RBI
                                                     Currency Management; the field is disabled and
                                                     never submitted. --%>
                                                <input type="number" class="form-control form-control-sm" disabled placeholder="N/A"/>
                                            </c:otherwise>
                                        </c:choose>
                                    </td>
                                    <td class="text-end fv-denom-subtotal">0.00</td>
                                </tr>
                            </c:forEach>
                        </tbody>
                        <tfoot class="table-light">
                            <tr>
                                <th colspan="3" class="text-end">Denomination Total:</th>
                                <th class="text-end" id="denomGrandTotal">0.00</th>
                            </tr>
                            <tr>
                                <th colspan="3" class="text-end">Amount Above:</th>
                                <th class="text-end" id="amountEcho">0.00</th>
                            </tr>
                            <tr id="denomMatchRow">
                                <th colspan="3" class="text-end">Match:</th>
                                <th class="text-end" id="denomMatchBadge">
                                    <span class="badge bg-secondary">enter amount</span>
                                </th>
                            </tr>
                        </tfoot>
                    </table>
                </div>
            </div>

<%-- ============================================================
     Live-sum + CTR-panel JS. The script element is intentionally placed
     INSIDE the form scope so the IDs it references resolve. Inline JS
     (not a separate file) so the page is self-contained and the threshold
     value can be EL-interpolated without an extra REST round-trip.

     IMPORTANT: this guard is UX only. The authoritative validation is
     server-side in DenominationValidator.validateSum and
     TellerServiceImpl.validateCtrCompliance -- the JS bypassed scenario
     fails cleanly with the same CBS-TELLER-004 / CBS-COMP-002 error codes.
     ============================================================ --%>
<script>
(function () {
    var amountInput = document.getElementById("amount");
    var grandTotalEl = document.getElementById("denomGrandTotal");
    var amountEchoEl = document.getElementById("amountEcho");
    var matchBadge = document.getElementById("denomMatchBadge");
    var ctrPanel = document.getElementById("ctrPanel");
    var postBtn = document.getElementById("postDepositBtn");
    var ctrThreshold = parseFloat("${ctrPanThreshold}") || 50000;

    // Till status is rendered server-side; if the till is not OPEN,
    // disable the submit so the operator cannot post.
    var tillIsOpen = ${not empty currentTill and currentTill.status == 'OPEN'};

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
            var counterfeitInput = row.querySelector(".fv-counterfeit-count");
            var unit = unitInput ? (parseInt(unitInput.value, 10) || 0) : 0;
            var counterfeit = counterfeitInput ? (parseInt(counterfeitInput.value, 10) || 0) : 0;
            // Counterfeit-counted units are part of the bundle the customer
            // tendered, so they count toward the sum (matches
            // DenominationValidator.validateSum). Reject-or-credit decisioning
            // happens server-side per RBI FICN guidelines.
            var subtotal = face * (unit + counterfeit);
            var subEl = row.querySelector(".fv-denom-subtotal");
            if (subEl) subEl.textContent = fmt(subtotal);
            grand += subtotal;
        });
        grandTotalEl.textContent = fmt(grand);

        var amount = parseFloat(amountInput.value) || 0;
        amountEchoEl.textContent = fmt(amount);

        // Match indicator. Use a small epsilon for FP comparison so 0.30 vs
        // 0.30000000000000004 doesn't fail. Rupee amounts are 2dp so 0.005
        // is plenty of headroom.
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

        // CTR panel toggles when amount >= threshold.
        if (amount >= ctrThreshold) {
            ctrPanel.style.display = "";
        } else {
            ctrPanel.style.display = "none";
        }

        // Submit gate: till must be OPEN AND denom sum must match.
        var canSubmit = tillIsOpen && amount > 0 && Math.abs(grand - amount) < 0.005;
        postBtn.disabled = !canSubmit;
    }

    // Wire change/input listeners on every grid input + the amount field.
    document.querySelectorAll("#denominationGridBody input")
        .forEach(function (el) { el.addEventListener("input", recompute); });
    if (amountInput) amountInput.addEventListener("input", recompute);

    // Initial render so the badge state is correct on page load.
    recompute();
})();
</script>

            <div class="mt-3">
                <button type="submit" class="btn btn-fv-primary" id="postDepositBtn"
                        data-confirm="Confirm cash deposit? This will credit the customer account and increment your till.">
                    <i class="bi bi-check-circle"></i> Post Deposit <span class="fv-kbd">F2</span>
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
