<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Fund Transfer" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
<c:if test="${not empty success}"><div class="alert alert-success"><c:out value="${success}"/></div></c:if>
<c:if test="${not empty error}"><div class="alert alert-danger"><c:out value="${error}"/></div></c:if>

<div class="fv-card">
    <div class="card-header">Internal Fund Transfer <span class="badge bg-info ms-2">Finacle TRAN_POSTING</span></div>
    <div class="card-body">
    <form method="post" action="${pageContext.request.contextPath}/deposit/transfer" class="fv-form" id="transferForm">
        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>

        <!-- Section 1: Source Account -->
        <h6 class="mb-3 text-primary"><i class="bi bi-box-arrow-right"></i> Source (Debit)</h6>
        <div class="row mb-3">
            <div class="col-md-8">
                <label for="fromAccount" class="form-label">From Account <span class="text-danger">*</span></label>
                <select name="fromAccount" id="fromAccount" class="form-select" required>
                    <option value="">-- Select Source Account --</option>
                    <c:forEach var="a" items="${accounts}">
                    <option value="<c:out value='${a.accountNumber}'/>" data-bal="${a.ledgerBalance}" data-avail="${a.effectiveAvailable}" data-type="<c:out value='${a.accountType}'/>" data-branch="<c:out value='${a.branch.branchCode}'/>" data-cust="<c:out value='${a.customer.firstName}'/> <c:out value='${a.customer.lastName}'/>"><c:out value="${a.accountNumber}"/> &mdash; <c:out value="${a.customer.firstName}"/> <c:out value="${a.customer.lastName}"/> | <c:out value="${a.accountType}"/> | <c:out value="${a.branch.branchCode}"/> | Bal: <fmt:formatNumber value="${a.ledgerBalance}" type="number" maxFractionDigits="2"/></option>
                    </c:forEach>
                </select>
            </div>
            <div class="col-md-4">
                <label class="form-label">Available Balance</label>
                <input type="text" id="sourceBalDisplay" class="form-control fw-bold text-success" readonly value="--" />
                <small class="form-text text-muted">Ledger - Holds - Uncleared + OD</small>
            </div>
        </div>

        <hr/>

        <!-- Section 2: Target Account -->
        <h6 class="mb-3 text-primary"><i class="bi bi-box-arrow-in-left"></i> Target (Credit)</h6>
        <div class="row mb-3">
            <div class="col-md-8">
                <label for="toAccount" class="form-label">To Account <span class="text-danger">*</span></label>
                <select name="toAccount" id="toAccount" class="form-select" required>
                    <option value="">-- Select Target Account --</option>
                    <c:forEach var="a" items="${accounts}">
                    <option value="<c:out value='${a.accountNumber}'/>" data-cust="<c:out value='${a.customer.firstName}'/> <c:out value='${a.customer.lastName}'/>" data-type="<c:out value='${a.accountType}'/>" data-branch="<c:out value='${a.branch.branchCode}'/>"><c:out value="${a.accountNumber}"/> &mdash; <c:out value="${a.customer.firstName}"/> <c:out value="${a.customer.lastName}"/> | <c:out value="${a.accountType}"/> | <c:out value="${a.branch.branchCode}"/></option>
                    </c:forEach>
                </select>
            </div>
            <div class="col-md-4">
                <label class="form-label">Beneficiary</label>
                <input type="text" id="beneficiaryDisplay" class="form-control" readonly value="--" />
            </div>
        </div>

        <hr/>

        <!-- Section 3: Transaction Details -->
        <h6 class="mb-3 text-primary"><i class="bi bi-cash-stack"></i> Transaction Details</h6>
        <div class="row mb-3">
            <div class="col-md-4">
                <label for="amount" class="form-label">Transfer Amount (INR) <span class="text-danger">*</span></label>
                <input type="number" name="amount" id="amount" class="form-control" step="0.01" min="0.01" required placeholder="0.00"/>
            </div>
            <div class="col-md-4">
                <label class="form-label">Transfer Mode</label>
                <select class="form-select" disabled>
                    <option value="INTERNAL" selected>Internal (Same Bank)</option>
                </select>
                <small class="form-text text-muted">NEFT/RTGS/IMPS in Phase 3</small>
            </div>
            <div class="col-md-4">
                <label for="narration" class="form-label">Narration / Purpose</label>
                <input type="text" name="narration" id="narration" class="form-control" placeholder="e.g., Salary credit, Rent payment" maxlength="500"/>
            </div>
        </div>

        <hr/>

        <div class="mt-3">
            <button type="submit" class="btn btn-fv-primary" id="transferBtn" data-confirm="Confirm fund transfer? This will debit the source and credit the target immediately."><i class="bi bi-arrow-left-right"></i> Execute Transfer</button>
            <a href="${pageContext.request.contextPath}/deposit/accounts" class="btn btn-outline-secondary ms-2">Cancel</a>
        </div>
    </form>
    </div>
</div>
</div>

<!-- CBS: Dynamic balance display + same-account guard -->
<script>
document.getElementById('fromAccount').addEventListener('change', function() {
    var sel = this.options[this.selectedIndex];
    if (!sel.value) { document.getElementById('sourceBalDisplay').value = '--'; return; }
    var avail = sel.getAttribute('data-avail');
    document.getElementById('sourceBalDisplay').value = avail ? 'INR ' + Number(avail).toLocaleString('en-IN', {minimumFractionDigits: 2}) : '--';
});
document.getElementById('toAccount').addEventListener('change', function() {
    var sel = this.options[this.selectedIndex];
    if (!sel.value) { document.getElementById('beneficiaryDisplay').value = '--'; return; }
    var cust = sel.getAttribute('data-cust');
    var type = sel.getAttribute('data-type');
    var branch = sel.getAttribute('data-branch');
    document.getElementById('beneficiaryDisplay').value = (cust || '') + ' | ' + (type || '') + ' | ' + (branch || '');
});
document.getElementById('transferForm').addEventListener('submit', function(e) {
    var from = document.getElementById('fromAccount').value;
    var to = document.getElementById('toAccount').value;
    if (from && to && from === to) {
        e.preventDefault();
        alert('Source and target accounts cannot be the same.');
        return false;
    }
});
</script>

<%@ include file="../layout/footer.jsp" %>
