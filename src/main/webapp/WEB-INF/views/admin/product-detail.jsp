<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Product Details" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <div class="fv-card">
        <div class="card-header">
            Product: <c:out value="${product.productCode}" /> — <c:out value="${product.productName}" />
            <a href="${pageContext.request.contextPath}/admin/products" class="btn btn-sm btn-outline-secondary float-end">Back</a>
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
                <tr><td class="fw-bold">Interest Rate Range</td><td><fmt:formatNumber value="${product.minInterestRate}" maxFractionDigits="2" />% — <fmt:formatNumber value="${product.maxInterestRate}" maxFractionDigits="2" />%</td></tr>
                <tr><td class="fw-bold">Loan Amount Range</td><td class="amount"><fmt:formatNumber value="${product.minLoanAmount}" type="number" maxFractionDigits="0" /> — <fmt:formatNumber value="${product.maxLoanAmount}" type="number" maxFractionDigits="0" /></td></tr>
                <tr><td class="fw-bold">Tenure Range</td><td><c:out value="${product.minTenureMonths}" /> — <c:out value="${product.maxTenureMonths}" /> months</td></tr>
                <tr><td class="fw-bold">Description</td><td><c:out value="${product.description}" default="—" /></td></tr>
                <tr><td class="fw-bold">Status</td><td>
                    <c:choose>
                        <c:when test="${product.active}"><span class="fv-badge fv-badge-active">ACTIVE</span></c:when>
                        <c:otherwise><span class="fv-badge fv-badge-rejected">INACTIVE</span></c:otherwise>
                    </c:choose>
                </td></tr>
                </tbody>
            </table>

            <h6 class="mt-4 mb-3">GL Code Mapping (Product → GL)</h6>
            <table class="table fv-table table-bordered">
                <thead class="table-light">
                    <tr><th>Transaction Type</th><th>GL Code</th><th>Description</th></tr>
                </thead>
                <tbody>
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
                </tbody>
            </table>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
