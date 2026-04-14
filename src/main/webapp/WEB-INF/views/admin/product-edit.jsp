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
                    <div class="col-md-3"><label class="form-label">Product Name *</label><input type="text" name="productName" class="form-control" value="<c:out value='${product.productName}'/>" required maxlength="200"/></div>
                    <div class="col-md-3"><label class="form-label">Description</label><input type="text" name="description" class="form-control" value="<c:out value='${product.description}'/>" maxlength="500"/></div>
                </div>

                <h6 class="mb-3 text-primary">Interest Configuration</h6>
                <div class="row mb-3">
                    <div class="col-md-2"><label class="form-label">Method</label><select name="interestMethod" class="form-select"><option value="ACTUAL_365" ${product.interestMethod == 'ACTUAL_365' ? 'selected' : ''}>Actual/365</option><option value="ACTUAL_360" ${product.interestMethod == 'ACTUAL_360' ? 'selected' : ''}>Actual/360</option><option value="ACTUAL_ACTUAL" ${product.interestMethod == 'ACTUAL_ACTUAL' ? 'selected' : ''}>Actual/Actual</option><option value="THIRTY_360" ${product.interestMethod == 'THIRTY_360' ? 'selected' : ''}>30/360</option></select></div>
                    <div class="col-md-2"><label class="form-label">Type</label><select name="interestType" class="form-select"><option value="FIXED" ${product.interestType == 'FIXED' ? 'selected' : ''}>Fixed</option><option value="FLOATING" ${product.interestType == 'FLOATING' ? 'selected' : ''}>Floating</option></select></div>
                    <div class="col-md-2"><label class="form-label">Min Rate % *</label><input type="number" name="minInterestRate" class="form-control" step="0.01" required value="${product.minInterestRate}"/></div>
                    <div class="col-md-2"><label class="form-label">Max Rate % *</label><input type="number" name="maxInterestRate" class="form-control" step="0.01" required value="${product.maxInterestRate}"/></div>
                    <div class="col-md-2"><label class="form-label">Penal Rate %</label><input type="number" name="defaultPenalRate" class="form-control" step="0.01" value="${product.defaultPenalRate}"/></div>
                    <div class="col-md-2"><label class="form-label">Frequency</label><select name="repaymentFrequency" class="form-select"><option value="MONTHLY" ${product.repaymentFrequency == 'MONTHLY' ? 'selected' : ''}>Monthly</option><option value="QUARTERLY" ${product.repaymentFrequency == 'QUARTERLY' ? 'selected' : ''}>Quarterly</option><option value="BULLET" ${product.repaymentFrequency == 'BULLET' ? 'selected' : ''}>Bullet</option><option value="MATURITY" ${product.repaymentFrequency == 'MATURITY' ? 'selected' : ''}>Maturity</option></select></div>
                </div>

                <h6 class="mb-3 text-primary">Amount &amp; Tenure Limits</h6>
                <div class="row mb-3">
                    <div class="col-md-3"><label class="form-label">Min Amount *</label><input type="number" name="minLoanAmount" class="form-control" step="0.01" required value="${product.minLoanAmount}"/></div>
                    <div class="col-md-3"><label class="form-label">Max Amount *</label><input type="number" name="maxLoanAmount" class="form-control" step="0.01" required value="${product.maxLoanAmount}"/></div>
                    <div class="col-md-3"><label class="form-label">Min Tenure (months) *</label><input type="number" name="minTenureMonths" class="form-control" required value="${product.minTenureMonths}"/></div>
                    <div class="col-md-3"><label class="form-label">Max Tenure (months) *</label><input type="number" name="maxTenureMonths" class="form-control" required value="${product.maxTenureMonths}"/></div>
                </div>

                <h6 class="mb-3 text-primary">GL Code Mapping (Product &rarr; GL)</h6>
                <div class="row mb-3">
                    <c:set var="glFields" value="glLoanAsset,glInterestReceivable,glBankOperations,glInterestIncome,glFeeIncome,glPenalIncome,glProvisionExpense,glProvisionNpa,glWriteOffExpense,glInterestSuspense"/>
                    <c:set var="glLabels" value="Loan Asset,Interest Receivable,Bank Operations,Interest Income,Fee Income,Penal Income,Provision Expense,Provision NPA,Write-Off Expense,Interest Suspense"/>
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
            </form>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
