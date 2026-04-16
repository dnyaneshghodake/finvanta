<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Create Product" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <c:if test="${not empty error}"><div class="alert alert-danger"><c:out value="${error}"/></div></c:if>

    <div class="fv-card">
        <div class="card-header"><i class="bi bi-box-seam"></i> Create New Product (Finacle PDDEF)</div>
        <div class="card-body">
            <form method="post" action="${pageContext.request.contextPath}/admin/products/create" class="fv-form">
                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>

                <h6 class="mb-3 text-primary">Product Identity</h6>
                <div class="row mb-3">
                    <div class="col-md-2"><label class="form-label">Product Code *</label><input type="text" name="productCode" class="form-control" required data-fv-type="code" placeholder="e.g., VEHICLE_LOAN" value="<c:out value='${product.productCode}'/>" style="text-transform:uppercase;"/></div>
                    <div class="col-md-3"><label class="form-label">Product Name *</label><input type="text" name="productName" class="form-control" required data-fv-type="name" placeholder="e.g., Vehicle Loan - Secured" value="<c:out value='${product.productName}'/>"/></div>
                    <div class="col-md-2"><label class="form-label">Category *</label>
                        <select name="productCategory" class="form-select" required>
                            <option value="TERM_LOAN" ${product.productCategory == 'TERM_LOAN' ? 'selected' : ''}>Term Loan</option>
                            <option value="DEMAND_LOAN" ${product.productCategory == 'DEMAND_LOAN' ? 'selected' : ''}>Demand Loan</option>
                            <option value="OVERDRAFT" ${product.productCategory == 'OVERDRAFT' ? 'selected' : ''}>Overdraft (OD)</option>
                            <option value="CASH_CREDIT" ${product.productCategory == 'CASH_CREDIT' ? 'selected' : ''}>Cash Credit (CC)</option>
                            <option value="CASA_SAVINGS" ${product.productCategory == 'CASA_SAVINGS' ? 'selected' : ''}>CASA Savings</option>
                            <option value="CASA_CURRENT" ${product.productCategory == 'CASA_CURRENT' ? 'selected' : ''}>CASA Current</option>
                            <option value="TERM_DEPOSIT" ${product.productCategory == 'TERM_DEPOSIT' ? 'selected' : ''}>Term Deposit (FD)</option>
                        </select>
                    </div>
                    <div class="col-md-2"><label class="form-label">Currency *</label><select name="currencyCode" class="form-select"><option value="INR" ${empty product.currencyCode or product.currencyCode == 'INR' ? 'selected' : ''}>INR</option><option value="USD" ${product.currencyCode == 'USD' ? 'selected' : ''}>USD</option><option value="EUR" ${product.currencyCode == 'EUR' ? 'selected' : ''}>EUR</option><option value="GBP" ${product.currencyCode == 'GBP' ? 'selected' : ''}>GBP</option></select></div>
                    <div class="col-md-3"><label class="form-label">Description</label><input type="text" name="description" class="form-control" maxlength="500" value="<c:out value='${product.description}'/>"/></div>
                </div>

                <h6 class="mb-3 text-primary">Interest Configuration</h6>
                <div class="row mb-3">
                    <div class="col-md-2"><label class="form-label">Method</label><select name="interestMethod" class="form-select"><option value="ACTUAL_365" ${empty product.interestMethod or product.interestMethod == 'ACTUAL_365' ? 'selected' : ''}>Actual/365</option><option value="ACTUAL_360" ${product.interestMethod == 'ACTUAL_360' ? 'selected' : ''}>Actual/360</option><option value="ACTUAL_ACTUAL" ${product.interestMethod == 'ACTUAL_ACTUAL' ? 'selected' : ''}>Actual/Actual</option><option value="THIRTY_360" ${product.interestMethod == 'THIRTY_360' ? 'selected' : ''}>30/360</option></select></div>
                    <div class="col-md-2"><label class="form-label">Type</label><select name="interestType" class="form-select"><option value="FIXED" ${empty product.interestType or product.interestType == 'FIXED' ? 'selected' : ''}>Fixed</option><option value="FLOATING" ${product.interestType == 'FLOATING' ? 'selected' : ''}>Floating</option></select></div>
                    <div class="col-md-2"><label class="form-label">Min Rate % *</label><input type="number" name="minInterestRate" class="form-control" required data-fv-type="rate" data-fv-max-field="maxInterestRate" data-fv-label="Min Rate" value="${not empty product.minInterestRate ? product.minInterestRate : '8.00'}"/></div>
                    <div class="col-md-2"><label class="form-label">Max Rate % *</label><input type="number" name="maxInterestRate" class="form-control" required data-fv-type="rate" data-fv-label="Max Rate" value="${not empty product.maxInterestRate ? product.maxInterestRate : '24.00'}"/></div>
                    <div class="col-md-2"><label class="form-label">Penal Rate %</label><input type="number" name="defaultPenalRate" class="form-control" data-fv-type="penal-rate" value="${not empty product.defaultPenalRate ? product.defaultPenalRate : '2.00'}"/></div>
                    <div class="col-md-2"><label class="form-label">Frequency</label><select name="repaymentFrequency" class="form-select"><option value="MONTHLY" ${empty product.repaymentFrequency or product.repaymentFrequency == 'MONTHLY' ? 'selected' : ''}>Monthly</option><option value="QUARTERLY" ${product.repaymentFrequency == 'QUARTERLY' ? 'selected' : ''}>Quarterly</option><option value="BULLET" ${product.repaymentFrequency == 'BULLET' ? 'selected' : ''}>Bullet</option><option value="MATURITY" ${product.repaymentFrequency == 'MATURITY' ? 'selected' : ''}>Maturity</option></select></div>
                </div>
                <div class="row mb-3">
                    <div class="col-md-3"><label class="form-label">Repayment Allocation</label><select name="repaymentAllocation" class="form-select"><option value="INTEREST_FIRST" selected>Interest First</option><option value="PRINCIPAL_FIRST">Principal First</option><option value="PRO_RATA">Pro-Rata</option></select></div>
                    <div class="col-md-3"><label class="form-label">Processing Fee %</label><input type="number" name="processingFeePct" class="form-control" data-fv-type="rate" value="0.00"/></div>
                    <div class="col-md-3"><label class="form-label">Prepayment Penalty</label><div class="form-check mt-2"><input type="hidden" name="_prepaymentPenaltyApplicable" value="on" /><input type="checkbox" name="prepaymentPenaltyApplicable" value="true" class="form-check-input" id="prepayCheck"/><label class="form-check-label" for="prepayCheck">Applicable (per RBI: not for floating rate)</label></div></div>
                </div>

                <%-- CBS Sprint 1.4: Floating Rate & CASA Tiering Configuration --%>
                <h6 class="mb-3 text-primary">Floating Rate Configuration (RBI EBLR/MCLR)</h6>
                <div class="row mb-3">
                    <div class="col-md-2"><label class="form-label">Benchmark</label><select name="defaultBenchmarkName" class="form-select"><option value="">-- None (Fixed) --</option><option value="EBLR">EBLR</option><option value="MCLR">MCLR</option><option value="RLLR">RLLR</option><option value="T_BILL">T-Bill</option></select></div>
                    <div class="col-md-2"><label class="form-label">Reset Frequency</label><select name="defaultRateResetFrequency" class="form-select"><option value="">-- N/A --</option><option value="QUARTERLY">Quarterly</option><option value="HALF_YEARLY">Half Yearly</option><option value="YEARLY">Yearly</option></select></div>
                    <div class="col-md-2"><label class="form-label">Default Spread %</label><input type="number" name="defaultSpread" class="form-control" data-fv-type="rate" placeholder="e.g., 2.50"/></div>
                    <div class="col-md-2"><label class="form-label">CASA Tiering</label><div class="form-check mt-2"><input type="checkbox" name="interestTieringEnabled" value="true" class="form-check-input" id="tieringCheck"/><label class="form-check-label" for="tieringCheck">Enable Balance Tiering</label></div></div>
                    <div class="col-md-4"><label class="form-label">Tiering JSON</label><input type="text" name="interestTieringJson" class="form-control" placeholder='[{"min":0,"max":100000,"rate":3.0}]'/></div>
                </div>

                <h6 class="mb-3 text-primary">Amount &amp; Tenure Limits</h6>
                <div class="row mb-3">
                    <div class="col-md-3"><label class="form-label">Min Amount (INR) *</label><input type="number" name="minLoanAmount" class="form-control" required data-fv-type="amount" data-fv-max-field="maxLoanAmount" data-fv-label="Min Amount" value="${not empty product.minLoanAmount ? product.minLoanAmount : '50000'}"/></div>
                    <div class="col-md-3"><label class="form-label">Max Amount (INR) *</label><input type="number" name="maxLoanAmount" class="form-control" required data-fv-type="amount" data-fv-label="Max Amount" value="${not empty product.maxLoanAmount ? product.maxLoanAmount : '5000000'}"/></div>
                    <div class="col-md-3"><label class="form-label">Min Tenure (months) *</label><input type="number" name="minTenureMonths" class="form-control" required data-fv-type="tenure" data-fv-max-field="maxTenureMonths" data-fv-label="Min Tenure" value="${not empty product.minTenureMonths ? product.minTenureMonths : '6'}"/></div>
                    <div class="col-md-3"><label class="form-label">Max Tenure (months) *</label><input type="number" name="maxTenureMonths" class="form-control" required data-fv-type="tenure" data-fv-label="Max Tenure" value="${not empty product.maxTenureMonths ? product.maxTenureMonths : '84'}"/></div>
                </div>

                <h6 class="mb-3 text-primary">GL Code Mapping (Product &rarr; GL)</h6>
                <p class="text-muted small mb-2">
                    <i class="bi bi-info-circle"></i> GL labels and defaults change based on product category.
                    <strong>Loan:</strong> Asset/Income semantics. <strong>CASA/FD:</strong> Liability/Expense semantics per Finacle PDDEF.
                </p>
                <%-- CBS: GL defaults are category-aware per Finacle PDDEF / Temenos AA.PRODUCT.CATALOG.
                     Loan products: glLoanAsset=1001(ASSET), glInterestReceivable=1002(ASSET), etc.
                     CASA products: glLoanAsset→Deposit Liability=2010(LIABILITY), glInterestReceivable→Interest Expense=5010(EXPENSE), etc.
                     JavaScript switches defaults when category dropdown changes. --%>
                <div class="row mb-3" id="glMappingSection">
                    <c:set var="glFields" value="glLoanAsset,glInterestReceivable,glBankOperations,glInterestIncome,glFeeIncome,glPenalIncome,glProvisionExpense,glProvisionNpa,glWriteOffExpense,glInterestSuspense"/>
                    <c:set var="glLabels" value="Loan Asset,Interest Receivable,Bank Operations,Interest Income,Fee Income,Penal Income,Provision Expense,Provision NPA,Write-Off Expense,Interest Suspense"/>
                    <c:set var="glDefaults" value="1001,1002,1100,4001,4002,4003,5001,1003,5002,2100"/>
                    <c:forTokens var="field" items="${glFields}" delims="," varStatus="i">
                        <div class="col-md-4 mb-2">
                            <label class="form-label small gl-label" id="label_${field}">${glLabels.split(',')[i.index]} *</label>
                            <select name="${field}" id="select_${field}" class="form-select form-select-sm" required>
                                <c:forEach var="gl" items="${glAccounts}">
                                    <option value="${gl.glCode}" ${gl.glCode == glDefaults.split(',')[i.index] ? 'selected' : ''}><c:out value="${gl.glCode}"/> &mdash; <c:out value="${gl.glName}"/></option>
                                </c:forEach>
                            </select>
                        </div>
                    </c:forTokens>
                </div>
                <script>
                // CBS: Category-aware GL label + default switching per Finacle PDDEF.
                // When admin selects CASA_SAVINGS/CASA_CURRENT/TERM_DEPOSIT, the GL field
                // labels and default selections switch to deposit-appropriate values.
                (function() {
                    var loanLabels = {
                        glLoanAsset: 'Loan Asset (ASSET)', glInterestReceivable: 'Interest Receivable (ASSET)',
                        glBankOperations: 'Bank Operations (ASSET)', glInterestIncome: 'Interest Income (INCOME)',
                        glFeeIncome: 'Fee Income (INCOME)', glPenalIncome: 'Penal Income (INCOME)',
                        glProvisionExpense: 'Provision Expense (EXPENSE)', glProvisionNpa: 'Provision NPA (ASSET)',
                        glWriteOffExpense: 'Write-Off Expense (EXPENSE)', glInterestSuspense: 'Interest Suspense (LIABILITY)'
                    };
                    var loanDefaults = {
                        glLoanAsset:'1001', glInterestReceivable:'1002', glBankOperations:'1100',
                        glInterestIncome:'4001', glFeeIncome:'4002', glPenalIncome:'4003',
                        glProvisionExpense:'5001', glProvisionNpa:'1003', glWriteOffExpense:'5002', glInterestSuspense:'2100'
                    };
                    var casaLabels = {
                        glLoanAsset: 'Deposit Liability (LIABILITY)', glInterestReceivable: 'Interest Expense (EXPENSE)',
                        glBankOperations: 'Bank Operations (ASSET)', glInterestIncome: 'Interest Expense P&L (EXPENSE)',
                        glFeeIncome: 'Fee Income (INCOME)', glPenalIncome: 'Penalty Charges (INCOME)',
                        glProvisionExpense: 'Interest Expense (EXPENSE)', glProvisionNpa: 'TDS Payable (LIABILITY)',
                        glWriteOffExpense: 'Closure Expense (EXPENSE)', glInterestSuspense: 'Interest Suspense (LIABILITY)'
                    };
                    var fdLabels = {
                        glLoanAsset: 'FD Deposit Liability (LIABILITY)', glInterestReceivable: 'FD Interest Payable (LIABILITY)',
                        glBankOperations: 'Bank Operations (ASSET)', glInterestIncome: 'FD Interest Expense P&L (EXPENSE)',
                        glFeeIncome: 'Fee Income (INCOME)', glPenalIncome: 'Premature Penalty Income (INCOME)',
                        glProvisionExpense: 'FD Interest Expense (EXPENSE)', glProvisionNpa: 'TDS Payable (LIABILITY)',
                        glWriteOffExpense: 'Closure Expense (EXPENSE)', glInterestSuspense: 'Interest Suspense (LIABILITY)'
                    };
                    var casaSBDefaults = {
                        glLoanAsset:'2010', glInterestReceivable:'5010', glBankOperations:'1100',
                        glInterestIncome:'5010', glFeeIncome:'4002', glPenalIncome:'4003',
                        glProvisionExpense:'5010', glProvisionNpa:'2500', glWriteOffExpense:'5002', glInterestSuspense:'2100'
                    };
                    var casaCADefaults = {
                        glLoanAsset:'2020', glInterestReceivable:'5010', glBankOperations:'1100',
                        glInterestIncome:'5010', glFeeIncome:'4002', glPenalIncome:'4003',
                        glProvisionExpense:'5010', glProvisionNpa:'2500', glWriteOffExpense:'5002', glInterestSuspense:'2100'
                    };
                    var fdDefaults = {
                        glLoanAsset:'2030', glInterestReceivable:'2031', glBankOperations:'1100',
                        glInterestIncome:'5011', glFeeIncome:'4002', glPenalIncome:'4003',
                        glProvisionExpense:'5011', glProvisionNpa:'2500', glWriteOffExpense:'5002', glInterestSuspense:'2100'
                    };
                    var catSelect = document.querySelector('select[name="productCategory"]');
                    if (catSelect) {
                        catSelect.addEventListener('change', function() {
                            var cat = this.value;
                            var labels;
                            if (cat === 'CASA_SAVINGS' || cat === 'CASA_CURRENT') labels = casaLabels;
                            else if (cat === 'TERM_DEPOSIT') labels = fdLabels;
                            else labels = loanLabels;
                            var defaults;
                            if (cat === 'CASA_SAVINGS') defaults = casaSBDefaults;
                            else if (cat === 'CASA_CURRENT') defaults = casaCADefaults;
                            else if (cat === 'TERM_DEPOSIT') defaults = fdDefaults;
                            else defaults = loanDefaults;
                            for (var field in labels) {
                                var lbl = document.getElementById('label_' + field);
                                var sel = document.getElementById('select_' + field);
                                if (lbl) lbl.textContent = labels[field] + ' *';
                                if (sel && defaults[field]) {
                                    for (var j = 0; j < sel.options.length; j++) {
                                        if (sel.options[j].value === defaults[field]) { sel.selectedIndex = j; break; }
                                    }
                                }
                            }
                        });
                    }
                })();
                </script>

                <hr/>
                <button type="submit" class="btn btn-fv-primary" data-confirm="Create this product? GL mapping will be used for all future transactions."><i class="bi bi-plus-circle"></i> Create Product</button>
                <a href="${pageContext.request.contextPath}/admin/products" class="btn btn-outline-secondary ms-2">Cancel</a>

                <%-- CBS: Cross-field validation (min<=max) is now handled by the centralized
                     FV.Validation library via data-fv-max-field attributes on the min fields.
                     No inline script needed. Per Finacle FIELD_TYPE_MASTER pattern. --%>
            </form>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
