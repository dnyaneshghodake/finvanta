<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<%@ page import="java.util.UUID" %>
<%-- CBS Idempotency per RBI Operational Risk Guidelines: mint a server-side UUID
     once per page render. The same key flows on every browser-initiated retry
     of this form submission, so the service layer's findByTenantIdAndIdempotencyKey
     dedupe returns the original DepositTransaction instead of double-posting.
     A new render of the page (e.g., after success) gets a fresh key. --%>
<c:set var="idempotencyKey" value="<%= UUID.randomUUID().toString() %>" />
<c:set var="pageTitle" value="Cash Deposit" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
<ul class="fv-breadcrumb">
    <li><a href="${pageContext.request.contextPath}/dashboard"><i class="bi bi-speedometer2"></i> Home</a></li>
    <li><a href="${pageContext.request.contextPath}/deposit/accounts">CASA Accounts</a></li>
    <li><a href="${pageContext.request.contextPath}/deposit/view/${account.accountNumber}"><c:out value="${account.accountNumber}"/></a></li>
    <li class="active">Cash Deposit</li>
</ul>

<c:if test="${not empty error}"><div class="fv-alert alert alert-danger"><c:out value="${error}"/></div></c:if>

<%-- CBS Tier-1: standardized card shell — title in `.card-header`, Back affordance in the
     header float-end, keyboard shortcut badges (F2=Save, F3=Cancel), `data-fv-cancel` routes
     F3 to the correct /view URL instead of falling back to history.back(). --%>
<div class="fv-card">
    <div class="card-header"><i class="bi bi-plus-circle"></i> Cash Deposit <div class="float-end"><a href="${pageContext.request.contextPath}/deposit/view/${account.accountNumber}" class="btn btn-sm btn-outline-secondary" data-fv-cancel="${pageContext.request.contextPath}/deposit/view/${account.accountNumber}"><i class="bi bi-arrow-left"></i> Back <span class="fv-kbd">F3</span></a></div></div>
    <div class="card-body">
        <p class="mb-3">Account: <strong><c:out value="${account.accountNumber}"/></strong>
        | Customer: <c:out value="${account.customer.firstName}"/> <c:out value="${account.customer.lastName}"/>
        | Current Balance: <strong><fmt:formatNumber value="${account.ledgerBalance}" type="currency" currencyCode="INR"/></strong></p>

        <%-- CBS Tier-1: Transaction Preview Panel per Finacle TRAN_PREVIEW.
             Hidden by default — populated via AJAX when amount is entered.
             Shows all validation checks BEFORE the operator clicks Post. --%>
        <div id="txnPreviewPanel" style="display:none;" class="mb-3"></div>

        <form method="post" action="${pageContext.request.contextPath}/deposit/deposit/${account.accountNumber}" class="fv-form">
            <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
            <%-- CBS Idempotency: server-minted UUID -- see scriptlet at top. Browser retries
                 (refresh/back button) resubmit the same key; the service layer dedupe returns
                 the previously-committed DepositTransaction without double-posting GL. --%>
            <input type="hidden" name="idempotencyKey" value="${idempotencyKey}"/>
            <div class="row mb-3">
                <div class="col-md-6 fv-mandatory-group">
                    <label for="depositAmount" class="form-label fv-required">Amount (INR)</label>
                    <input type="number" id="depositAmount" name="amount" class="form-control" data-fv-type="amount" min="0.01" step="0.01" required autofocus tabindex="1"/>
                </div>
                <div class="col-md-6">
                    <label for="depositNarration" class="form-label">Narration</label>
                    <input type="text" id="depositNarration" name="narration" class="form-control" placeholder="Cash deposit" maxlength="500" tabindex="2"/>
                </div>
            </div>
            <div class="mt-3">
                <button type="submit" class="btn btn-fv-primary" data-confirm="Confirm cash deposit? This will credit the account immediately."><i class="bi bi-check-circle"></i> Post Deposit <span class="fv-kbd">F2</span></button>
                <a href="${pageContext.request.contextPath}/deposit/view/${account.accountNumber}" class="btn btn-outline-secondary ms-2" data-fv-cancel="${pageContext.request.contextPath}/deposit/view/${account.accountNumber}"><i class="bi bi-x-circle"></i> Cancel <span class="fv-kbd">F3</span></a>
            </div>
        </form>
    </div>
</div>
</div>

<%@ include file="../layout/footer.jsp" %>
