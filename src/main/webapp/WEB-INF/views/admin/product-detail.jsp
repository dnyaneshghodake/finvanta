<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Product Details" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <div class="fv-card">
        <div class="card-header">
            Product: <c:out value="${product.productCode}" /> &mdash; <c:out value="${product.productName}" />
            <a href="${pageContext.request.contextPath}/admin/products" class="btn btn-sm btn-outline-secondary float-end"><i class="bi bi-arrow-left"></i> Back</a>
        </div>
        <div class="card-body">
            <h6 class="mb-3">Product Configuration</h6>
            <table class="table fv-table">
                <tbody>
                <tr><td class="fw-bold" style="width:250px">Product Code</td><td><c:out value="${product.productCode}" /></td></tr>
                <tr><td class="fw-bold">Product Name</td><td><c:out value="${product.productName}" /></td></tr>
                <tr><td class="fw-bold">Category</td><td><c:out value="${product.productCategory}" /></td></tr>
                <tr><td class="fw-bold">Currency</td><td><c:out value="${product.currencyCode}" /></td></tr>
                <tr><td class="fw-bold">Interest Method</td><td><c:out value="${product.interestMethod}" /></td></tr>
                <tr><td class="fw-bold">Interest Type</td><td><c:out value="${product.interestType}" /></td></tr>
                <tr><td class="fw-bold">Repayment Frequency</td><td><c:out value="${product.repaymentFrequency}" /></td></tr>
                <tr><td class="fw-bold">Repayment Allocation</td><td><c:out value="${product.repaymentAllocation}" /></td></tr>
                <tr><td class="fw-bold">Prepayment Penalty</td><td><c:out value="${product.prepaymentPenaltyApplicable ? 'Yes' : 'No'}" /></td></tr>
                <tr><td class="fw-bold">Processing Fee</td><td><fmt:formatNumber value="${product.processingFeePct}" maxFractionDigits="2" />%</td></tr>
                <tr><td class="fw-bold">Default Penal Rate</td><td><fmt:formatNumber value="${product.defaultPenalRate}" maxFractionDigits="2" />% p.a.</td></tr>
                <tr><td class="fw-bold">Interest Rate Range</td><td><fmt:formatNumber value="${product.minInterestRate}" maxFractionDigits="2" />% &ndash; <fmt:formatNumber value="${product.maxInterestRate}" maxFractionDigits="2" />%</td></tr>
                <tr><td class="fw-bold">Loan Amount Range</td><td class="amount"><fmt:formatNumber value="${product.minLoanAmount}" type="number" maxFractionDigits="0" /> &ndash; <fmt:formatNumber value="${product.maxLoanAmount}" type="number" maxFractionDigits="0" /></td></tr>
                <tr><td class="fw-bold">Tenure Range</td><td><c:out value="${product.minTenureMonths}" /> &ndash; <c:out value="${product.maxTenureMonths}" /> months</td></tr>
                <tr><td class="fw-bold">Description</td><td><c:out value="${product.description}" default="--" /></td></tr>
                <%-- CBS Sprint 1.4: Floating Rate & CASA Tiering display --%>
                <c:if test="${not empty product.defaultBenchmarkName}">
                <tr><td class="fw-bold">Benchmark Rate</td><td><c:out value="${product.defaultBenchmarkName}" /> | Reset: <c:out value="${product.defaultRateResetFrequency}" default="--" /> | Default Spread: <fmt:formatNumber value="${product.defaultSpread}" maxFractionDigits="2" />%</td></tr>
                </c:if>
                <c:if test="${product.interestTieringEnabled}">
                <tr><td class="fw-bold">Interest Tiering</td><td><span class="fv-badge fv-badge-active">ENABLED</span> <small class="text-muted font-monospace"><c:out value="${product.interestTieringJson}" /></small></td></tr>
                </c:if>
                <tr><td class="fw-bold">Lifecycle Status</td><td>
                    <c:choose>
                        <c:when test="${product.productStatus == 'ACTIVE'}"><span class="fv-badge fv-badge-active">ACTIVE</span></c:when>
                        <c:when test="${product.productStatus == 'DRAFT'}"><span class="fv-badge fv-badge-pending">DRAFT</span></c:when>
                        <c:when test="${product.productStatus == 'SUSPENDED'}"><span class="fv-badge fv-badge-pending">SUSPENDED</span></c:when>
                        <c:when test="${product.productStatus == 'RETIRED'}"><span class="fv-badge fv-badge-rejected">RETIRED</span></c:when>
                        <c:otherwise><span class="fv-badge fv-badge-active">ACTIVE</span></c:otherwise>
                    </c:choose>
                </td></tr>
                <tr><td class="fw-bold">Active Accounts</td><td>
                    <c:choose>
                        <c:when test="${activeAccountCount > 0}"><span class="fw-bold text-primary">${activeAccountCount}</span> loan/deposit account(s) using this product</c:when>
                        <c:otherwise><span class="text-muted">No active accounts</span></c:otherwise>
                    </c:choose>
                </td></tr>
                </tbody>
            </table>

            <%-- CBS: Product Lifecycle Actions per Finacle PDDEF --%>
            <div class="mt-3">
                <a href="${pageContext.request.contextPath}/admin/products/${product.id}/edit" class="btn btn-sm btn-fv-primary me-2"><i class="bi bi-pencil"></i> Edit Product</a>
                <c:if test="${product.productStatus == 'DRAFT'}">
                    <form method="post" action="${pageContext.request.contextPath}/admin/products/${product.id}/status" class="d-inline">
                        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                        <input type="hidden" name="status" value="ACTIVE" />
                        <button type="submit" class="btn btn-sm btn-success" data-confirm="Activate this product? It will be available for new origination."><i class="bi bi-check-circle"></i> Activate</button>
                    </form>
                </c:if>
                <c:if test="${product.productStatus == 'ACTIVE'}">
                    <form method="post" action="${pageContext.request.contextPath}/admin/products/${product.id}/status" class="d-inline">
                        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                        <input type="hidden" name="status" value="SUSPENDED" />
                        <button type="submit" class="btn btn-sm btn-warning" data-confirm="Suspend this product? No new origination, existing accounts continue."><i class="bi bi-pause-circle"></i> Suspend</button>
                    </form>
                    <form method="post" action="${pageContext.request.contextPath}/admin/products/${product.id}/status" class="d-inline">
                        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                        <input type="hidden" name="status" value="RETIRED" />
                        <button type="submit" class="btn btn-sm btn-danger" data-confirm="Retire this product permanently? This cannot be undone."><i class="bi bi-x-octagon"></i> Retire</button>
                    </form>
                </c:if>
                <c:if test="${product.productStatus == 'SUSPENDED'}">
                    <form method="post" action="${pageContext.request.contextPath}/admin/products/${product.id}/status" class="d-inline">
                        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                        <input type="hidden" name="status" value="ACTIVE" />
                        <button type="submit" class="btn btn-sm btn-success" data-confirm="Reactivate this product?"><i class="bi bi-play-circle"></i> Reactivate</button>
                    </form>
                    <form method="post" action="${pageContext.request.contextPath}/admin/products/${product.id}/status" class="d-inline">
                        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                        <input type="hidden" name="status" value="RETIRED" />
                        <button type="submit" class="btn btn-sm btn-danger" data-confirm="Retire this product permanently? This cannot be undone."><i class="bi bi-x-octagon"></i> Retire</button>
                    </form>
                </c:if>
            </div>

            <h6 class="mt-4 mb-3">GL Code Mapping (Product &rarr; GL)</h6>
            <%-- CBS: Category-aware GL labels per Finacle PDDEF --%>
            <c:set var="isCasaProduct" value="${product.productCategory == 'CASA_SAVINGS' || product.productCategory == 'CASA_CURRENT' || product.productCategory == 'TERM_DEPOSIT'}" />
            <table class="table fv-table table-bordered">
                <thead class="table-light">
                    <tr><th>Transaction Type</th><th>GL Code</th><th>Description</th></tr>
                </thead>
                <tbody>
                <c:choose>
                    <c:when test="${isCasaProduct}">
                <tr><td>Deposit Liability</td><td class="fw-bold"><c:out value="${product.glLoanAsset}" /></td><td>Customer deposit balance (LIABILITY)</td></tr>
                <tr><td>Interest Expense</td><td class="fw-bold"><c:out value="${product.glInterestReceivable}" /></td><td>Interest expense on deposits (EXPENSE)</td></tr>
                <tr><td>Bank Operations</td><td class="fw-bold"><c:out value="${product.glBankOperations}" /></td><td>Cash / teller operations</td></tr>
                <tr><td>Interest Expense (P&L)</td><td class="fw-bold"><c:out value="${product.glInterestIncome}" /></td><td>Interest expense on deposits (EXPENSE)</td></tr>
                <tr><td>Fee Income</td><td class="fw-bold"><c:out value="${product.glFeeIncome}" /></td><td>Service charges, fees</td></tr>
                <tr><td>Penalty Charges</td><td class="fw-bold"><c:out value="${product.glPenalIncome}" /></td><td>Penalty charges income</td></tr>
                <tr><td>Interest Expense (Provision)</td><td class="fw-bold"><c:out value="${product.glProvisionExpense}" /></td><td>Interest expense on deposits (EXPENSE)</td></tr>
                <tr><td>TDS Payable</td><td class="fw-bold"><c:out value="${product.glProvisionNpa}" /></td><td>TDS payable u/s 194A (LIABILITY)</td></tr>
                <tr><td>Closure/Write-Off Expense</td><td class="fw-bold"><c:out value="${product.glWriteOffExpense}" /></td><td>Account closure charges (EXPENSE)</td></tr>
                <tr><td>Interest Suspense</td><td class="fw-bold"><c:out value="${product.glInterestSuspense}" /></td><td>Interest suspense (LIABILITY)</td></tr>
                    </c:when>
                    <c:otherwise>
                <tr><td>Loan Asset</td><td class="fw-bold"><c:out value="${product.glLoanAsset}" /></td><td>Outstanding principal</td></tr>
                <tr><td>Interest Receivable</td><td class="fw-bold"><c:out value="${product.glInterestReceivable}" /></td><td>Accrued interest not yet collected</td></tr>
                <tr><td>Bank Operations</td><td class="fw-bold"><c:out value="${product.glBankOperations}" /></td><td>Disbursement / collection</td></tr>
                <tr><td>Interest Income</td><td class="fw-bold"><c:out value="${product.glInterestIncome}" /></td><td>Interest income from loans</td></tr>
                <tr><td>Fee Income</td><td class="fw-bold"><c:out value="${product.glFeeIncome}" /></td><td>Processing fees, charges</td></tr>
                <tr><td>Penal Interest Income</td><td class="fw-bold"><c:out value="${product.glPenalIncome}" /></td><td>Penal interest on overdue</td></tr>
                <tr><td>Provision Expense</td><td class="fw-bold"><c:out value="${product.glProvisionExpense}" /></td><td>P&L charge for provisioning</td></tr>
                <tr><td>Provision for NPA</td><td class="fw-bold"><c:out value="${product.glProvisionNpa}" /></td><td>Contra-asset for loan loss</td></tr>
                <tr><td>Write-Off Expense</td><td class="fw-bold"><c:out value="${product.glWriteOffExpense}" /></td><td>NPA write-off expense</td></tr>
                <tr><td>Interest Suspense</td><td class="fw-bold"><c:out value="${product.glInterestSuspense}" /></td><td>NPA interest parking</td></tr>
                    </c:otherwise>
                </c:choose>
                </tbody>
            </table>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
