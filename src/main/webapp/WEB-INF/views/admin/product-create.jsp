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
                    <div class="col-md-2"><label class="form-label">Product Code *</label><input type="text" name="productCode" class="form-control" required maxlength="50" placeholder="e.g., VEHICLE_LOAN"/></div>
                    <div class="col-md-3"><label class="form-label">Product Name *</label><input type="text" name="productName" class="form-control" required maxlength="200" placeholder="e.g., Vehicle Loan - Secured"/></div>
                    <div class="col-md-2"><label class="form-label">Category *</label>
                        <select name="productCategory" class="form-select" required>
                            <option value="TERM_LOAN">Term Loan</option>
                            <option value="DEMAND_LOAN">Demand Loan</option>
                            <option value="CASA_SAVINGS">CASA Savings</option>
                            <option value="CASA_CURRENT">CASA Current</option>
                        </select>
                    </div>
                    <div class="col-md-5"><label class="form-label">Description</label><input type="text" name="description" class="form-control" maxlength="500"/></div>
                </div>

                <h6 class="mb-3 text-primary">Interest Configuration</h6>
                <div class="row mb-3">
                    <div class="col-md-2"><label class="form-label">Method</label><select name="interestMethod" class="form-select"><option value="ACTUAL_365">Actual/365</option><option value="ACTUAL_360">Actual/360</option><option value="ACTUAL_ACTUAL">Actual/Actual</option><option value="THIRTY_360">30/360</option></select></div>
                    <div class="col-md-2"><label class="form-label">Type</label><select name="interestType" class="form-select"><option value="FIXED">Fixed</option><option value="FLOATING">Floating</option></select></div>
                    <div class="col-md-2"><label class="form-label">Min Rate % *</label><input type="number" name="minInterestRate" class="form-control" step="0.01" required value="8.00"/></div>
                    <div class="col-md-2"><label class="form-label">Max Rate % *</label><input type="number" name="maxInterestRate" class="form-control" step="0.01" required value="24.00"/></div>
                    <div class="col-md-2"><label class="form-label">Penal Rate %</label><input type="number" name="defaultPenalRate" class="form-control" step="0.01" value="2.00"/></div>
                    <div class="col-md-2"><label class="form-label">Frequency</label><select name="repaymentFrequency" class="form-select"><option value="MONTHLY">Monthly</option><option value="QUARTERLY">Quarterly</option><option value="BULLET">Bullet</option></select></div>
                </div>

                <%-- CBS Sprint 1.4: Floating Rate & CASA Tiering Configuration --%>
                <h6 class="mb-3 text-primary">Floating Rate Configuration (RBI EBLR/MCLR)</h6>
                <div class="row mb-3">
                    <div class="col-md-2"><label class="form-label">Benchmark</label><select name="defaultBenchmarkName" class="form-select"><option value="">-- None (Fixed) --</option><option value="EBLR">EBLR</option><option value="MCLR">MCLR</option><option value="RLLR">RLLR</option><option value="T_BILL">T-Bill</option></select></div>
                    <div class="col-md-2"><label class="form-label">Reset Frequency</label><select name="defaultRateResetFrequency" class="form-select"><option value="">-- N/A --</option><option value="QUARTERLY">Quarterly</option><option value="HALF_YEARLY">Half Yearly</option><option value="YEARLY">Yearly</option></select></div>
                    <div class="col-md-2"><label class="form-label">Default Spread %</label><input type="number" name="defaultSpread" class="form-control" step="0.01" placeholder="e.g., 2.50"/></div>
                    <div class="col-md-2"><label class="form-label">CASA Tiering</label><div class="form-check mt-2"><input type="checkbox" name="interestTieringEnabled" value="true" class="form-check-input" id="tieringCheck"/><label class="form-check-label" for="tieringCheck">Enable Balance Tiering</label></div></div>
                    <div class="col-md-4"><label class="form-label">Tiering JSON</label><input type="text" name="interestTieringJson" class="form-control" placeholder='[{"min":0,"max":100000,"rate":3.0}]'/></div>
                </div>

                <h6 class="mb-3 text-primary">Amount &amp; Tenure Limits</h6>
                <div class="row mb-3">
                    <div class="col-md-3"><label class="form-label">Min Amount *</label><input type="number" name="minLoanAmount" class="form-control" step="0.01" required value="50000"/></div>
                    <div class="col-md-3"><label class="form-label">Max Amount *</label><input type="number" name="maxLoanAmount" class="form-control" step="0.01" required value="5000000"/></div>
                    <div class="col-md-3"><label class="form-label">Min Tenure (months) *</label><input type="number" name="minTenureMonths" class="form-control" required value="6"/></div>
                    <div class="col-md-3"><label class="form-label">Max Tenure (months) *</label><input type="number" name="maxTenureMonths" class="form-control" required value="84"/></div>
                </div>

                <h6 class="mb-3 text-primary">GL Code Mapping (Product &rarr; GL)</h6>
                <div class="row mb-3">
                    <c:set var="glFields" value="glLoanAsset,glInterestReceivable,glBankOperations,glInterestIncome,glFeeIncome,glPenalIncome,glProvisionExpense,glProvisionNpa,glWriteOffExpense,glInterestSuspense"/>
                    <c:set var="glLabels" value="Loan Asset,Interest Receivable,Bank Operations,Interest Income,Fee Income,Penal Income,Provision Expense,Provision NPA,Write-Off Expense,Interest Suspense"/>
                    <c:set var="glDefaults" value="1001,1002,1100,4001,4002,4003,5001,1003,5002,2100"/>
                    <c:forTokens var="field" items="${glFields}" delims="," varStatus="i">
                        <div class="col-md-4 mb-2">
                            <label class="form-label small">${glLabels.split(',')[i.index]} *</label>
                            <select name="${field}" class="form-select form-select-sm" required>
                                <c:forEach var="gl" items="${glAccounts}">
                                    <option value="${gl.glCode}" ${gl.glCode == glDefaults.split(',')[i.index] ? 'selected' : ''}><c:out value="${gl.glCode}"/> &mdash; <c:out value="${gl.glName}"/></option>
                                </c:forEach>
                            </select>
                        </div>
                    </c:forTokens>
                </div>

                <hr/>
                <button type="submit" class="btn btn-fv-primary" data-confirm="Create this product? GL mapping will be used for all future transactions."><i class="bi bi-plus-circle"></i> Create Product</button>
                <a href="${pageContext.request.contextPath}/admin/products" class="btn btn-outline-secondary ms-2">Cancel</a>
            </form>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
