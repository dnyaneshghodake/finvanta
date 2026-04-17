<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="form" uri="http://www.springframework.org/tags/form" %>
<c:set var="pageTitle" value="New Loan Application" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <ul class="fv-breadcrumb">
        <li><a href="${pageContext.request.contextPath}/dashboard"><i class="bi bi-speedometer2"></i> Home</a></li>
        <li><a href="${pageContext.request.contextPath}/loan/applications">Loan Applications</a></li>
        <li class="active">New Application</li>
    </ul>

    <c:if test="${not empty error}">
        <div class="fv-alert alert alert-danger"><c:out value="${error}" /></div>
    </c:if>

    <div class="fv-card">
        <div class="card-header"><i class="bi bi-file-earmark-plus"></i> Loan Application Form <div class="float-end"><a href="${pageContext.request.contextPath}/loan/applications" class="btn btn-sm btn-outline-secondary" data-fv-cancel="${pageContext.request.contextPath}/loan/applications"><i class="bi bi-arrow-left"></i> Back <span class="fv-kbd">F3</span></a></div></div>
        <div class="card-body">
            <form method="post" action="${pageContext.request.contextPath}/loan/apply" class="fv-form" id="loanForm">
                <div class="row mb-3">
                    <div class="col-md-6 fv-mandatory-group">
                        <label for="customerId" class="form-label fv-required">Customer</label><span class="fv-help-icon" data-fv-help="Only KYC-verified customers with active CIF are eligible for loan origination per RBI norms.">?</span>
                        <select name="customerId" id="customerId" class="form-select" required tabindex="1">
                            <option value="">-- Select Customer --</option>
                            <c:forEach var="cust" items="${customers}">
                                <option value="${cust.id}"><c:out value="${cust.customerNumber}" /> - <c:out value="${cust.fullName}" /></option>
                            </c:forEach>
                        </select>
                    </div>
                    <div class="col-md-6 fv-mandatory-group">
                        <label for="branchId" class="form-label fv-required">Branch</label>
                        <select name="branchId" id="branchId" class="form-select" required tabindex="2">
                            <option value="">-- Select Branch --</option>
                            <c:forEach var="branch" items="${branches}">
                                <option value="${branch.id}"><c:out value="${branch.branchCode}" /> - <c:out value="${branch.branchName}" /></option>
                            </c:forEach>
                        </select>
                    </div>
                </div>

                <div class="row mb-3">
                    <div class="col-md-6 fv-mandatory-group">
                        <label for="productType" class="form-label fv-required">Product Type</label><span class="fv-help-icon" data-fv-help="Per Finacle PDDEF: product determines rate range, tenure limits, penal rate, and interest type (fixed/floating).">?</span>
                        <select name="productType" id="productType" class="form-select" required>
                            <option value="">-- Select Product --</option>
                            <c:forEach var="product" items="${products}">
                                <option value="<c:out value='${product.productCode}'/>"
                                    data-min-amount="${product.minLoanAmount}"
                                    data-max-amount="${product.maxLoanAmount}"
                                    data-min-tenure="${product.minTenureMonths}"
                                    data-max-tenure="${product.maxTenureMonths}"
                                    data-min-rate="${product.minInterestRate}"
                                    data-max-rate="${product.maxInterestRate}"
                                    data-default-penal="${product.defaultPenalRate}"
                                    data-category="<c:out value='${product.productCategory}'/>"
                                    data-interest-type="<c:out value='${product.interestType}'/>"
                                    data-prepayment-penalty="${product.prepaymentPenaltyApplicable}"><c:out value="${product.productCode}" /> - <c:out value="${product.productName}" /></option>
                            </c:forEach>
                        </select>
                        <small id="productHint" class="form-text text-muted"></small>
                    </div>
                    <div class="col-md-6 fv-mandatory-group">
                        <label for="requestedAmount" class="form-label fv-required">Requested Amount (INR)</label><span class="fv-help-icon" data-fv-help="Per product PDDEF: min/max amount auto-populated. Per RBI exposure norms: subject to borrower's max borrowing limit.">?</span>
                        <input type="number" name="requestedAmount" id="requestedAmount" class="form-control" data-fv-type="amount" min="10000" max="50000000" required placeholder="e.g., 1000000" />
                        <small id="amountHint" class="form-text text-muted"></small>
                    </div>
                </div>

                <div class="row mb-3">
                    <div class="col-md-6 fv-mandatory-group">
                        <label for="interestRate" class="form-label fv-required">Interest Rate (% p.a.)</label><span class="fv-help-icon" data-fv-help="Per RBI Fair Practices Code: rate must be within product range. Auto-populated with midpoint on product selection.">?</span>
                        <input type="number" name="interestRate" id="interestRate" class="form-control" data-fv-type="rate" step="0.25" required placeholder="Auto-populated from product" />
                        <small id="rateHint" class="form-text text-muted"></small>
                    </div>
                    <div class="col-md-6 fv-mandatory-group">
                        <label for="tenureMonths" class="form-label fv-required">Tenure (Months)</label>
                        <input type="number" name="tenureMonths" id="tenureMonths" class="form-control" data-fv-type="tenure" required placeholder="e.g., 120" />
                        <small id="tenureHint" class="form-text text-muted"></small>
                    </div>
                </div>

                <div class="row mb-3">
                    <div class="col-md-4">
                        <label for="penalRate" class="form-label">Penal Rate (% p.a.)</label>
                        <input type="number" name="penalRate" id="penalRate" class="form-control" data-fv-type="penal-rate" step="0.25" placeholder="Auto from product" />
                    </div>
                    <div class="col-md-4">
                        <label for="riskCategory" class="form-label">Risk Category</label>
                        <select name="riskCategory" id="riskCategory" class="form-select">
                            <option value="">-- Auto --</option>
                            <option value="LOW">Low</option>
                            <option value="MEDIUM">Medium</option>
                            <option value="HIGH">High</option>
                            <option value="VERY_HIGH">Very High</option>
                        </select>
                    </div>
                    <div class="col-md-4">
                        <label for="collateralReference" class="form-label">Collateral Reference</label>
                        <input type="text" name="collateralReference" id="collateralReference" class="form-control" placeholder="e.g., PROP-MUM-001" />
                    </div>
                </div>

                <div class="row mb-3">
                    <div class="col-md-6">
                        <label for="disbursementAccountNumber" class="form-label">Disbursement Account (CASA) *</label>
                        <select name="disbursementAccountNumber" id="disbursementAccountNumber" class="form-select">
                            <option value="">-- Cash/DD Disbursement (no CASA) --</option>
                            <c:forEach var="depAcct" items="${casaAccounts}">
                                <option value="<c:out value='${depAcct.accountNumber}'/>" data-customer-id="${depAcct.customer.id}"><c:out value="${depAcct.accountNumber}" /> - <c:out value="${depAcct.accountType}" /> (Bal: <c:out value="${depAcct.ledgerBalance}" />)</option>
                            </c:forEach>
                        </select>
                        <small class="form-text text-muted">Per RBI: loan proceeds must credit borrower's own CASA account. Select the customer's SB/CA account.</small>
                    </div>
                    <div class="col-md-6">
                        <label for="purpose" class="form-label">Purpose</label>
                        <textarea name="purpose" id="purpose" class="form-control" rows="3" placeholder="Purpose of the loan"></textarea>
                    </div>
                </div>

                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                <div class="mt-3">
                    <button type="submit" class="btn btn-fv-primary"><i class="bi bi-check-circle"></i> Submit Application <span class="fv-kbd">F2</span></button>
                    <a href="${pageContext.request.contextPath}/loan/applications" class="btn btn-outline-secondary ms-2" data-fv-cancel="${pageContext.request.contextPath}/loan/applications"><i class="bi bi-x-circle"></i> Cancel <span class="fv-kbd">F3</span></a>
                </div>
            </form>
        </div>
    </div>
</div>

<!-- CBS: Product-driven auto-population per Finacle PDDEF -->
<script>
document.getElementById('productType').addEventListener('change', function() {
    var sel = this.options[this.selectedIndex];
    if (!sel.value) {
        document.getElementById('productHint').textContent = '';
        document.getElementById('amountHint').textContent = '';
        document.getElementById('rateHint').textContent = '';
        document.getElementById('tenureHint').textContent = '';
        return;
    }

    var minAmt = sel.getAttribute('data-min-amount');
    var maxAmt = sel.getAttribute('data-max-amount');
    var minTenure = sel.getAttribute('data-min-tenure');
    var maxTenure = sel.getAttribute('data-max-tenure');
    var minRate = sel.getAttribute('data-min-rate');
    var maxRate = sel.getAttribute('data-max-rate');
    var defaultPenal = sel.getAttribute('data-default-penal');
    var category = sel.getAttribute('data-category');
    var interestType = sel.getAttribute('data-interest-type');

    // Update HTML5 validation bounds from product
    var amtField = document.getElementById('requestedAmount');
    var rateField = document.getElementById('interestRate');
    var tenureField = document.getElementById('tenureMonths');
    var penalField = document.getElementById('penalRate');

    if (minAmt && minAmt !== 'null') amtField.min = minAmt;
    if (maxAmt && maxAmt !== 'null') amtField.max = maxAmt;
    if (minTenure && minTenure !== 'null') tenureField.min = minTenure;
    if (maxTenure && maxTenure !== 'null') tenureField.max = maxTenure;
    if (minRate && minRate !== 'null') rateField.min = minRate;
    if (maxRate && maxRate !== 'null') rateField.max = maxRate;

    // Auto-populate interest rate with midpoint of product range
    if (minRate && maxRate && minRate !== 'null' && maxRate !== 'null') {
        var mid = ((parseFloat(minRate) + parseFloat(maxRate)) / 2).toFixed(2);
        rateField.value = mid;
    }

    // Auto-populate penal rate from product default
    if (defaultPenal && defaultPenal !== 'null') {
        penalField.value = parseFloat(defaultPenal).toFixed(2);
    }

    // Display product hints
    document.getElementById('productHint').textContent =
        (category || '') + ' | ' + (interestType || '') + ' rate';
    document.getElementById('amountHint').textContent =
        (minAmt && minAmt !== 'null' ? 'Min: INR ' + Number(minAmt).toLocaleString('en-IN') : '') +
        (maxAmt && maxAmt !== 'null' ? ' | Max: INR ' + Number(maxAmt).toLocaleString('en-IN') : '');
    document.getElementById('rateHint').textContent =
        (minRate && minRate !== 'null' ? 'Range: ' + minRate + '%' : '') +
        (maxRate && maxRate !== 'null' ? ' - ' + maxRate + '%' : '');
    document.getElementById('tenureHint').textContent =
        (minTenure && minTenure !== 'null' ? minTenure + ' - ' : '') +
        (maxTenure && maxTenure !== 'null' ? maxTenure + ' months' : '');
});
</script>

<%@ include file="../layout/footer.jsp" %>
