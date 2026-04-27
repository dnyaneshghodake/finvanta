<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Open CASA Account" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
<ul class="fv-breadcrumb">
    <li><a href="${pageContext.request.contextPath}/dashboard"><i class="bi bi-speedometer2"></i> Home</a></li>
    <li><a href="${pageContext.request.contextPath}/deposit/accounts">CASA Accounts</a></li>
    <li class="active">Open Account</li>
</ul>

<c:if test="${not empty error}"><div class="fv-alert alert alert-danger"><c:out value="${error}"/></div></c:if>

<div class="fv-card">
    <div class="card-header"><i class="bi bi-person-plus"></i> CASA Account Opening <span class="badge bg-info ms-2">Finacle ACCTOPN</span> <div class="float-end"><a href="${pageContext.request.contextPath}/deposit/accounts" class="btn btn-sm btn-outline-secondary" data-fv-cancel="${pageContext.request.contextPath}/deposit/accounts"><i class="bi bi-arrow-left"></i> Back <span class="fv-kbd">F3</span></a></div></div>
    <div class="card-body">
    <%-- CBS ACCTOPN Field Coverage Contract per Finacle ACCTOPN / Temenos ACCOUNT.OPENING:
         this form intentionally captures only the 6 fields needed to OPEN the account
         (customerId, branchId, accountType, productCode, nomineeName, nomineeRelationship).
         The remaining 23 fields on OpenAccountRequest (PAN/Aadhaar, KYC status, address,
         contact, FATCA, etc.) are inherited from the CIF record at the service layer --
         see DepositController.openAccount (DepositController.java:241-254) for the explicit
         null fill-ins. This is by design: per Finacle ACCTOPN, account-level personal
         attributes denormalize from the CIF and must not be re-entered at account opening.
         The React UI sends all 29 fields directly because it is API-first; the JSP path
         relies on the service-layer CIF inheritance. See JSP_DTO_CROSSWALK_MATRIX.md §1
         and DTO_PARITY_AUDIT_REPORT.md §2.1 for the full parity map. --%>
    <form method="post" action="${pageContext.request.contextPath}/deposit/open" class="fv-form" id="casaOpenForm">
        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>

        <!-- Section 1: Customer & Branch -->
        <div class="fv-section-header" onclick=""><i class="bi bi-person-badge"></i> Customer &amp; Branch <i class="bi bi-chevron-down fv-chevron"></i></div>
        <div class="fv-section-body">
        <div class="row mb-3">
            <div class="col-md-6 fv-mandatory-group">
                <label for="customerId" class="form-label fv-required">Customer (CIF)</label><span class="fv-help-icon" data-fv-help="Only KYC-verified customers are eligible for CASA account opening per RBI norms.">?</span>
                <select name="customerId" id="customerId" class="form-select" required tabindex="1">
                    <option value="">-- Select Customer --</option>
                    <c:forEach var="c" items="${customers}">
                    <option value="${c.id}" data-kyc="${c.kycVerified}" data-cibil="${c.cibilScore}"><c:out value="${c.customerNumber}"/> &mdash; <c:out value="${c.firstName}"/> <c:out value="${c.lastName}"/></option>
                    </c:forEach>
                </select>
                <small id="customerHint" class="form-text text-muted"></small>
            </div>
            <div class="col-md-6 fv-mandatory-group">
                <label for="branchId" class="form-label fv-required">Branch (SOL)</label>
                <select name="branchId" id="branchId" class="form-select" required tabindex="2">
                    <option value="">-- Select Branch --</option>
                    <c:forEach var="b" items="${branches}">
                    <option value="${b.id}"><c:out value="${b.branchCode}"/> &mdash; <c:out value="${b.branchName}"/></option>
                    </c:forEach>
                </select>
            </div>
        </div>

        </div><%-- end Customer & Branch section body --%>

        <!-- Section 2: Account Configuration -->
        <div class="fv-section-header" onclick=""><i class="bi bi-gear"></i> Account Configuration <i class="bi bi-chevron-down fv-chevron"></i></div>
        <div class="fv-section-body">
        <div class="row mb-3">
            <div class="col-md-4 fv-mandatory-group">
                <label for="accountType" class="form-label fv-required">Account Type</label><span class="fv-help-icon" data-fv-help="Per Finacle ACCTOPN: determines interest rate, minimum balance, and product features. PMJDY = zero balance per PM Jan Dhan Yojana.">?</span>
                <select name="accountType" id="accountType" class="form-select" required>
                    <option value="">-- Select Type --</option>
                    <option value="SAVINGS">Savings Account (SB)</option>
                    <option value="CURRENT">Current Account (CA)</option>
                    <option value="SAVINGS_PMJDY">Savings &mdash; PMJDY (Zero Balance)</option>
                    <option value="SAVINGS_NRI">Savings &mdash; NRE/NRO</option>
                    <option value="SAVINGS_MINOR">Savings &mdash; Minor</option>
                    <option value="SAVINGS_JOINT">Savings &mdash; Joint</option>
                    <option value="CURRENT_OD">Current &mdash; With Overdraft</option>
                </select>
            </div>
            <div class="col-md-4">
                <label for="productCode" class="form-label">Product Code</label>
                <select name="productCode" id="productCode" class="form-select">
                    <option value="">-- Auto from Account Type --</option>
                    <c:forEach var="p" items="${products}">
                    <option value="<c:out value='${p.productCode}'/>" data-rate="${p.minInterestRate}" data-minbal="${p.minLoanAmount}" data-category="<c:out value='${p.productCategory}'/>"><c:out value="${p.productCode}"/> &mdash; <c:out value="${p.productName}"/></option>
                    </c:forEach>
                </select>
                <small id="productHint" class="form-text text-muted"></small>
            </div>
            <div class="col-md-4">
                <label class="form-label">Currency</label>
                <select class="form-select" disabled><option value="INR" selected>INR &mdash; Indian Rupee</option></select>
            </div>
        </div>
        <div class="row mb-3">
            <div class="col-md-3">
                <label class="form-label">Interest Rate (% p.a.)</label>
                <input type="text" id="interestRateDisplay" class="form-control" readonly value="Per product" />
                <small class="form-text text-muted">Auto from product master</small>
            </div>
            <div class="col-md-3">
                <label class="form-label">Minimum Balance (INR)</label>
                <input type="text" id="minBalDisplay" class="form-control" readonly value="Per product" />
                <small class="form-text text-muted">Auto from product master</small>
            </div>
            <div class="col-md-3">
                <label class="form-label">Initial Funding</label>
                <input type="text" class="form-control" readonly value="After activation" disabled/>
                <small class="form-text text-muted">Per Finacle ACCTOPN: deposit via Deposit screen after checker activates the account</small>
            </div>
            <div class="col-md-3">
                <label class="form-label">Joint Holder Mode</label>
                <input type="text" class="form-control" readonly value="Post-activation" disabled/>
                <small class="form-text text-muted">Per Finacle ACCTOPN: configured via account maintenance</small>
            </div>
        </div>

        </div><%-- end Account Configuration section body --%>

        <!-- Section 3: Nomination (RBI Deposit Insurance) -->
        <div class="fv-section-header" onclick=""><i class="bi bi-shield-check"></i> Nomination (per RBI Deposit Insurance) <i class="bi bi-chevron-down fv-chevron"></i></div>
        <div class="fv-section-body">
        <div class="row mb-3">
            <div class="col-md-4">
                <label for="nomineeName" class="form-label">Nominee Name</label>
                <input type="text" name="nomineeName" id="nomineeName" class="form-control" placeholder="Full legal name" maxlength="200"/>
            </div>
            <div class="col-md-4">
                <label for="nomineeRelationship" class="form-label">Nominee Relationship</label>
                <select name="nomineeRelationship" id="nomineeRelationship" class="form-select">
                    <option value="">-- None --</option>
                    <option value="SPOUSE">Spouse</option>
                    <option value="CHILD">Child</option>
                    <option value="PARENT">Parent</option>
                    <option value="SIBLING">Sibling</option>
                    <option value="OTHER">Other</option>
                </select>
            </div>
            <div class="col-md-4">
                <label class="form-label">Cheque Book / Debit Card</label>
                <input type="text" class="form-control" readonly value="Configured post-activation" disabled/>
                <small class="form-text text-muted">Per Finacle ACCTOPN: enabled via account maintenance after activation</small>
            </div>
        </div>

        </div><%-- end Nomination section body --%>

        <div class="mt-3">
            <button type="submit" class="btn btn-fv-primary" data-confirm="Open this CASA account? It will require checker approval before activation."><i class="bi bi-check-circle"></i> Submit Account Opening <span class="fv-kbd">F2</span></button>
            <a href="${pageContext.request.contextPath}/deposit/accounts" class="btn btn-outline-secondary ms-2" data-fv-cancel="${pageContext.request.contextPath}/deposit/accounts"><i class="bi bi-x-circle"></i> Cancel <span class="fv-kbd">F3</span></a>
        </div>
    </form>
    </div>
</div>
</div>

<!-- CBS: Product-driven auto-population per Finacle PDDEF -->
<script>
document.getElementById('productCode').addEventListener('change', function() {
    var sel = this.options[this.selectedIndex];
    if (!sel.value) {
        document.getElementById('productHint').textContent = '';
        document.getElementById('interestRateDisplay').value = 'Per product';
        document.getElementById('minBalDisplay').value = 'Per product';
        return;
    }
    var rate = sel.getAttribute('data-rate');
    var minBal = sel.getAttribute('data-minbal');
    var cat = sel.getAttribute('data-category');
    if (rate && rate !== 'null') document.getElementById('interestRateDisplay').value = rate + '% p.a.';
    if (minBal && minBal !== 'null' && minBal !== '0.00') document.getElementById('minBalDisplay').value = 'INR ' + Number(minBal).toLocaleString('en-IN');
    else document.getElementById('minBalDisplay').value = 'Zero (no minimum)';
    document.getElementById('productHint').textContent = cat || '';
});
document.getElementById('customerId').addEventListener('change', function() {
    var sel = this.options[this.selectedIndex];
    if (!sel.value) { document.getElementById('customerHint').textContent = ''; return; }
    var kyc = sel.getAttribute('data-kyc');
    var cibil = sel.getAttribute('data-cibil');
    document.getElementById('customerHint').textContent =
        'KYC: ' + (kyc === 'true' ? 'Verified' : 'PENDING') + (cibil ? ' | CIBIL: ' + cibil : '');
    if (kyc !== 'true') document.getElementById('customerHint').classList.add('text-danger');
    else document.getElementById('customerHint').classList.remove('text-danger');
});
</script>

<%@ include file="../layout/footer.jsp" %>
