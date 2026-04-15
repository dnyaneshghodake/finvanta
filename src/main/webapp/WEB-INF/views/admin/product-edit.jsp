<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Edit Product" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <c:if test="${not empty error}"><div class="alert alert-danger"><c:out value="${error}"/></div></c:if>
    <c:if test="${not empty success}"><div class="alert alert-success"><c:out value="${success}"/></div></c:if>

    <div class="fv-card">
        <div class="card-header"><i class="bi bi-pencil-square"></i> Edit Product &mdash; <c:out value="${product.productCode}" />
            <a href="${pageContext.request.contextPath}/admin/products/${product.id}" class="btn btn-sm btn-outline-secondary float-end"><i class="bi bi-x-circle"></i> Cancel</a>
        </div>
        <div class="card-body">
            <c:if test="${activeAccountCount > 0}">
                <div class="alert alert-warning"><i class="bi bi-exclamation-triangle"></i> <strong>${activeAccountCount}</strong> active account(s) use this product. GL code changes will affect future transactions on these accounts.</div>
            </c:if>

            <form method="post" action="${pageContext.request.contextPath}/admin/products/${product.id}/edit" class="fv-form">
                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>

                <%-- Immutable fields (read-only) --%>
                <h6 class="mb-3 text-primary">Product Identity (Immutable)</h6>
                <div class="row mb-3">
                    <div class="col-md-3"><label class="form-label">Product Code</label><input type="text" class="form-control" value="<c:out value='${product.productCode}'/>" disabled /></div>
                    <div class="col-md-3"><label class="form-label">Category</label><input type="text" class="form-control" value="<c:out value='${product.productCategory}'/>" disabled /></div>
                    <div class="col-md-3"><label class="form-label">Product Name *</label><input type="text" name="productName" class="form-control" value="<c:out value='${product.productName}'/>" required minlength="2" maxlength="200" pattern="[A-Za-z0-9\s\-\(\)\/&amp;,.]{2,200}" title="Letters, digits, spaces, hyphens, parentheses, slashes, ampersands, commas, periods only"/></div>
                    <div class="col-md-3"><label class="form-label">Description</label><input type="text" name="description" class="form-control" value="<c:out value='${product.description}'/>" maxlength="500"/></div>
                </div>

                <h6 class="mb-3 text-primary">Interest Configuration</h6>
                <div class="row mb-3">
                    <div class="col-md-2"><label class="form-label">Method</label><select name="interestMethod" class="form-select"><option value="ACTUAL_365" ${product.interestMethod == 'ACTUAL_365' ? 'selected' : ''}>Actual/365</option><option value="ACTUAL_360" ${product.interestMethod == 'ACTUAL_360' ? 'selected' : ''}>Actual/360</option><option value="ACTUAL_ACTUAL" ${product.interestMethod == 'ACTUAL_ACTUAL' ? 'selected' : ''}>Actual/Actual</option><option value="THIRTY_360" ${product.interestMethod == 'THIRTY_360' ? 'selected' : ''}>30/360</option></select></div>
                    <div class="col-md-2"><label class="form-label">Type</label><select name="interestType" class="form-select"><option value="FIXED" ${product.interestType == 'FIXED' ? 'selected' : ''}>Fixed</option><option value="FLOATING" ${product.interestType == 'FLOATING' ? 'selected' : ''}>Floating</option></select></div>
                    <div class="col-md-2"><label class="form-label">Min Rate % *</label><input type="number" name="minInterestRate" class="form-control" step="0.01" min="0" max="100" required value="${product.minInterestRate}"/></div>
                    <div class="col-md-2"><label class="form-label">Max Rate % *</label><input type="number" name="maxInterestRate" class="form-control" step="0.01" min="0" max="100" required value="${product.maxInterestRate}"/></div>
                    <div class="col-md-2"><label class="form-label">Penal Rate %</label><input type="number" name="defaultPenalRate" class="form-control" step="0.01" min="0" max="36" title="RBI usury ceiling: 36% max" value="${product.defaultPenalRate}"/></div>
                    <div class="col-md-2"><label class="form-label">Frequency</label><select name="repaymentFrequency" class="form-select"><option value="MONTHLY" ${product.repaymentFrequency == 'MONTHLY' ? 'selected' : ''}>Monthly</option><option value="QUARTERLY" ${product.repaymentFrequency == 'QUARTERLY' ? 'selected' : ''}>Quarterly</option><option value="BULLET" ${product.repaymentFrequency == 'BULLET' ? 'selected' : ''}>Bullet</option><option value="MATURITY" ${product.repaymentFrequency == 'MATURITY' ? 'selected' : ''}>Maturity</option></select></div>
                </div>

                <div class="row mb-3">
                    <div class="col-md-3"><label class="form-label">Repayment Allocation</label><select name="repaymentAllocation" class="form-select"><option value="INTEREST_FIRST" ${product.repaymentAllocation == 'INTEREST_FIRST' ? 'selected' : ''}>Interest First</option><option value="PRINCIPAL_FIRST" ${product.repaymentAllocation == 'PRINCIPAL_FIRST' ? 'selected' : ''}>Principal First</option><option value="PRO_RATA" ${product.repaymentAllocation == 'PRO_RATA' ? 'selected' : ''}>Pro-Rata</option></select></div>
                    <div class="col-md-3"><label class="form-label">Processing Fee %</label><input type="number" name="processingFeePct" class="form-control" step="0.01" min="0" max="100" value="${product.processingFeePct}"/></div>
                    <div class="col-md-3"><label class="form-label">Prepayment Penalty</label><div class="form-check mt-2"><input type="hidden" name="_prepaymentPenaltyApplicable" value="on" /><input type="checkbox" name="prepaymentPenaltyApplicable" value="true" class="form-check-input" id="prepayEditCheck" ${product.prepaymentPenaltyApplicable ? 'checked' : ''}/><label class="form-check-label" for="prepayEditCheck">Applicable</label></div></div>
                    <div class="col-md-3"><label class="form-label">Currency</label><input type="text" class="form-control" value="<c:out value='${product.currencyCode}'/>" disabled /><small class="text-muted">Immutable after creation</small></div>
                </div>

                <h6 class="mb-3 text-primary">Floating Rate Configuration (RBI EBLR/MCLR)</h6>
                <div class="row mb-3">
                    <div class="col-md-2"><label class="form-label">Benchmark</label><select name="defaultBenchmarkName" class="form-select"><option value="">-- None --</option><option value="EBLR" ${product.defaultBenchmarkName == 'EBLR' ? 'selected' : ''}>EBLR</option><option value="MCLR" ${product.defaultBenchmarkName == 'MCLR' ? 'selected' : ''}>MCLR</option><option value="RLLR" ${product.defaultBenchmarkName == 'RLLR' ? 'selected' : ''}>RLLR</option><option value="T_BILL" ${product.defaultBenchmarkName == 'T_BILL' ? 'selected' : ''}>T-Bill</option></select></div>
                    <div class="col-md-2"><label class="form-label">Reset Frequency</label><select name="defaultRateResetFrequency" class="form-select"><option value="">-- N/A --</option><option value="QUARTERLY" ${product.defaultRateResetFrequency == 'QUARTERLY' ? 'selected' : ''}>Quarterly</option><option value="HALF_YEARLY" ${product.defaultRateResetFrequency == 'HALF_YEARLY' ? 'selected' : ''}>Half Yearly</option><option value="YEARLY" ${product.defaultRateResetFrequency == 'YEARLY' ? 'selected' : ''}>Yearly</option></select></div>
                    <div class="col-md-2"><label class="form-label">Default Spread %</label><input type="number" name="defaultSpread" class="form-control" step="0.01" min="0" max="100" value="${product.defaultSpread}"/></div>
                    <div class="col-md-2"><label class="form-label">CASA Tiering</label><div class="form-check mt-2"><input type="hidden" name="_interestTieringEnabled" value="on" /><input type="checkbox" name="interestTieringEnabled" value="true" class="form-check-input" id="tieringEditCheck" ${product.interestTieringEnabled ? 'checked' : ''}/><label class="form-check-label" for="tieringEditCheck">Enable</label></div></div>
                    <div class="col-md-4"><label class="form-label">Tiering JSON</label><input type="text" name="interestTieringJson" class="form-control" value="<c:out value='${product.interestTieringJson}'/>"/></div>
                </div>

                <h6 class="mb-3 text-primary">Amount &amp; Tenure Limits</h6>
                <div class="row mb-3">
                    <div class="col-md-3"><label class="form-label">Min Amount (INR) *</label><input type="number" name="minLoanAmount" class="form-control" step="0.01" min="0" required value="${product.minLoanAmount}"/></div>
                    <div class="col-md-3"><label class="form-label">Max Amount (INR) *</label><input type="number" name="maxLoanAmount" class="form-control" step="0.01" min="0" required value="${product.maxLoanAmount}"/></div>
                    <div class="col-md-3"><label class="form-label">Min Tenure (months) *</label><input type="number" name="minTenureMonths" class="form-control" step="1" min="1" max="600" required value="${product.minTenureMonths}"/></div>
                    <div class="col-md-3"><label class="form-label">Max Tenure (months) *</label><input type="number" name="maxTenureMonths" class="form-control" step="1" min="1" max="600" required value="${product.maxTenureMonths}"/></div>
                </div>

                <h6 class="mb-3 text-primary">GL Code Mapping (Product &rarr; GL)</h6>
                <%-- CBS: Category-aware GL labels per Finacle PDDEF.
                     CASA/FD products show deposit-specific labels (Deposit Liability, Interest Expense, TDS Payable).
                     Loan products show loan-specific labels (Loan Asset, Interest Receivable, Provision NPA). --%>
                <c:set var="glFields" value="glLoanAsset,glInterestReceivable,glBankOperations,glInterestIncome,glFeeIncome,glPenalIncome,glProvisionExpense,glProvisionNpa,glWriteOffExpense,glInterestSuspense"/>
                <c:choose>
                    <c:when test="${product.productCategory == 'CASA_SAVINGS' || product.productCategory == 'CASA_CURRENT'}">
                        <c:set var="glLabels" value="Deposit Liability,Interest Expense,Bank Operations,Interest Expense (P&L),Fee Income,Penalty Charges,Interest Expense (Provision),TDS Payable,Closure/Write-Off Expense,Interest Suspense"/>
                    </c:when>
                    <c:when test="${product.productCategory == 'TERM_DEPOSIT'}">
                        <c:set var="glLabels" value="FD Deposit Liability,FD Interest Payable,Bank Operations,FD Interest Expense (P&L),Fee Income,Premature Penalty Income,FD Interest Expense,TDS Payable,Closure/Write-Off Expense,Interest Suspense"/>
                    </c:when>
                    <c:otherwise>
                        <c:set var="glLabels" value="Loan Asset,Interest Receivable,Bank Operations,Interest Income,Fee Income,Penal Income,Provision Expense,Provision NPA,Write-Off Expense,Interest Suspense"/>
                    </c:otherwise>
                </c:choose>
                <div class="row mb-3">
                    <c:forTokens var="field" items="${glFields}" delims="," varStatus="i">
                        <div class="col-md-4 mb-2">
                            <label class="form-label small">${glLabels.split(',')[i.index]} *</label>
                            <select name="${field}" class="form-select form-select-sm" required>
                                <c:forEach var="gl" items="${glAccounts}">
                                    <option value="${gl.glCode}" ${gl.glCode == product[field] ? 'selected' : ''}><c:out value="${gl.glCode}"/> &mdash; <c:out value="${gl.glName}"/></option>
                                </c:forEach>
                            </select>
                        </div>
                    </c:forTokens>
                </div>

                <hr/>
                <button type="submit" class="btn btn-fv-primary" data-confirm="Save product changes? GL cache will be auto-evicted."><i class="bi bi-check-circle"></i> Save Changes</button>
                <a href="${pageContext.request.contextPath}/admin/products/${product.id}" class="btn btn-outline-secondary ms-2">Cancel</a>

                <script>
                // CBS: Client-side cross-field validation per Finacle PDDEF.
                (function() {
                    var form = document.querySelector('form.fv-form');
                    if (!form) return;
                    form.addEventListener('submit', function(e) {
                        var minRate = parseFloat(form.querySelector('[name="minInterestRate"]').value) || 0;
                        var maxRate = parseFloat(form.querySelector('[name="maxInterestRate"]').value) || 0;
                        var minAmt = parseFloat(form.querySelector('[name="minLoanAmount"]').value) || 0;
                        var maxAmt = parseFloat(form.querySelector('[name="maxLoanAmount"]').value) || 0;
                        var minTen = parseInt(form.querySelector('[name="minTenureMonths"]').value) || 0;
                        var maxTen = parseInt(form.querySelector('[name="maxTenureMonths"]').value) || 0;
                        var errors = [];
                        if (minRate > maxRate) errors.push('Min Rate (' + minRate + '%) cannot exceed Max Rate (' + maxRate + '%).');
                        if (minAmt > maxAmt && maxAmt > 0) errors.push('Min Amount cannot exceed Max Amount.');
                        if (minTen > maxTen) errors.push('Min Tenure (' + minTen + ' months) cannot exceed Max Tenure (' + maxTen + ' months).');
                        if (errors.length > 0) {
                            e.preventDefault();
                            alert('Validation Error:\n\n' + errors.join('\n'));
                        }
                    });
                })();
                </script>
            </form>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
