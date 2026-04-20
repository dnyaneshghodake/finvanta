<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Cash Withdrawal" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
<ul class="fv-breadcrumb">
    <li><a href="${pageContext.request.contextPath}/dashboard"><i class="bi bi-speedometer2"></i> Home</a></li>
    <li><a href="${pageContext.request.contextPath}/deposit/accounts">CASA Accounts</a></li>
    <li><a href="${pageContext.request.contextPath}/deposit/view/${account.accountNumber}"><c:out value="${account.accountNumber}"/></a></li>
    <li class="active">Cash Withdrawal</li>
</ul>

<c:if test="${not empty error}"><div class="fv-alert alert alert-danger"><c:out value="${error}"/></div></c:if>

<%-- CBS Tier-1: standardized card shell matching deposit.jsp — title in `.card-header`,
     Back affordance with `data-fv-cancel` + F3 kbd badge, `fv-mandatory-group` + `fv-required`
     on Amount, `btn-fv-warning` with F2 kbd badge, tabindex for field ordering. --%>
<div class="fv-card">
    <div class="card-header"><i class="bi bi-dash-circle"></i> Cash Withdrawal <div class="float-end"><a href="${pageContext.request.contextPath}/deposit/view/${account.accountNumber}" class="btn btn-sm btn-outline-secondary" data-fv-cancel="${pageContext.request.contextPath}/deposit/view/${account.accountNumber}"><i class="bi bi-arrow-left"></i> Back <span class="fv-kbd">F3</span></a></div></div>
    <div class="card-body">
        <p class="mb-3">Account: <strong><c:out value="${account.accountNumber}"/></strong>
        | Customer: <c:out value="${account.customer.firstName}"/> <c:out value="${account.customer.lastName}"/>
        | Available Balance: <strong class="text-success"><fmt:formatNumber value="${account.effectiveAvailable}" type="currency" currencyCode="INR"/></strong>
        <c:if test="${account.minimumBalance.signum() > 0}">| Min Balance: <fmt:formatNumber value="${account.minimumBalance}" type="currency" currencyCode="INR"/></c:if></p>

        <%-- CBS Tier-1: Transaction Preview Panel per Finacle TRAN_PREVIEW.
             Hidden by default — populated via AJAX when amount is entered.
             Shows all validation checks BEFORE the operator clicks Post. --%>
        <div id="txnPreviewPanel" style="display:none;" class="mb-3"></div>

        <form method="post" action="${pageContext.request.contextPath}/deposit/withdraw/${account.accountNumber}" class="fv-form" id="withdrawForm">
            <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
            <div class="row mb-3">
                <div class="col-md-6 fv-mandatory-group">
                    <label for="withdrawAmount" class="form-label fv-required">Amount (INR)</label>
                    <input type="number" id="withdrawAmount" name="amount" class="form-control" data-fv-type="amount" min="0.01" step="0.01" required autofocus tabindex="1"/>
                    <small class="text-muted">Max available: <fmt:formatNumber value="${account.effectiveAvailable}" type="currency" currencyCode="INR"/></small>
                </div>
                <div class="col-md-6">
                    <label for="withdrawNarration" class="form-label">Narration</label>
                    <input type="text" id="withdrawNarration" name="narration" class="form-control" placeholder="Cash withdrawal" maxlength="500" tabindex="2"/>
                </div>
            </div>
            <div class="mt-3">
                <button type="submit" class="btn btn-fv-warning" data-confirm="Confirm cash withdrawal? This will debit the account immediately."><i class="bi bi-dash-circle"></i> Post Withdrawal <span class="fv-kbd">F2</span></button>
                <a href="${pageContext.request.contextPath}/deposit/view/${account.accountNumber}" class="btn btn-outline-secondary ms-2" data-fv-cancel="${pageContext.request.contextPath}/deposit/view/${account.accountNumber}"><i class="bi bi-x-circle"></i> Cancel <span class="fv-kbd">F3</span></a>
            </div>
        </form>
    </div>
</div>
</div>

<%@ include file="../layout/footer.jsp" %>
