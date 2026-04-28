<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Open Till" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<%-- CBS Teller Till Open screen per CBS TELLER_OPEN standard.
     Renders one of two states:
       (a) No till open today  -> show the open form (opening balance + cash limit).
       (b) Till already exists -> show its current status; suppress the form.
     The teller's branch and userId are NOT on this form -- they are derived
     server-side from the authenticated principal per RBI Internal Controls /
     segregation of duties. See OpenTillRequest Javadoc. --%>

<div class="fv-main">
<ul class="fv-breadcrumb">
    <li><a href="${pageContext.request.contextPath}/dashboard"><i class="bi bi-speedometer2"></i> Home</a></li>
    <li class="active">Teller / Open Till</li>
</ul>

<c:if test="${not empty success}"><div class="fv-alert alert alert-success"><c:out value="${success}"/></div></c:if>
<c:if test="${not empty error}">
    <div class="fv-alert alert alert-danger">
        <c:if test="${not empty errorCode}"><strong>[<c:out value="${errorCode}"/>]</strong> </c:if>
        <c:out value="${error}"/>
    </div>
</c:if>

<c:choose>
    <%-- A till already exists for this teller today -- show status, not form. --%>
    <c:when test="${not empty currentTill}">
        <div class="fv-card">
            <div class="card-header"><i class="bi bi-cash-stack"></i> Till Already Open</div>
            <div class="card-body">
                <p class="mb-3">
                    Status: <strong><c:out value="${currentTill.status}"/></strong> |
                    Branch: <c:out value="${currentTill.branchCode}"/> |
                    Business Date: <c:out value="${currentTill.businessDate}"/>
                </p>
                <p>
                    Opening Balance: <strong><fmt:formatNumber value="${currentTill.openingBalance}" type="currency" currencyCode="INR"/></strong>
                    | Current Balance: <strong><fmt:formatNumber value="${currentTill.currentBalance}" type="currency" currencyCode="INR"/></strong>
                    <c:if test="${not empty currentTill.tillCashLimit}">
                        | Soft Limit: <fmt:formatNumber value="${currentTill.tillCashLimit}" type="currency" currencyCode="INR"/>
                    </c:if>
                </p>
                <a href="${pageContext.request.contextPath}/teller/cash-deposit" class="btn btn-fv-primary">
                    <i class="bi bi-arrow-right-circle"></i> Go to Cash Deposit
                </a>
            </div>
        </div>
    </c:when>

    <%-- No till today -- show the open form. --%>
    <c:otherwise>
        <div class="fv-card">
            <div class="card-header"><i class="bi bi-cash-stack"></i> Open Till for Today</div>
            <div class="card-body">
                <p class="text-muted">
                    Opens your till for the current branch business date. Branch and teller
                    user are derived from your login per RBI Internal Controls. Opening
                    balances above the branch threshold route to a supervisor for sign-off
                    (PENDING_OPEN); smaller balances auto-approve to OPEN.
                </p>
                <form method="post" action="${pageContext.request.contextPath}/teller/till/open" class="fv-form">
                    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
                    <div class="row mb-3">
                        <div class="col-md-4 fv-mandatory-group">
                            <label for="openingBalance" class="form-label fv-required">Opening Balance (INR)</label>
                            <input type="number" id="openingBalance" name="openingBalance"
                                   class="form-control" min="0" step="0.01" required autofocus tabindex="1"/>
                        </div>
                        <div class="col-md-4">
                            <label for="tillCashLimit" class="form-label">Till Cash Limit (INR)</label>
                            <input type="number" id="tillCashLimit" name="tillCashLimit"
                                   class="form-control" min="0" step="0.01" tabindex="2"
                                   placeholder="Defaults to branch policy"/>
                        </div>
                    </div>
                    <div class="row mb-3">
                        <div class="col-md-12">
                            <label for="remarks" class="form-label">Remarks</label>
                            <input type="text" id="remarks" name="remarks" class="form-control"
                                   maxlength="500" tabindex="3"/>
                        </div>
                    </div>
                    <button type="submit" class="btn btn-fv-primary"
                            data-confirm="Open till with this opening balance?">
                        <i class="bi bi-check-circle"></i> Open Till <span class="fv-kbd">F2</span>
                    </button>
                </form>
            </div>
        </div>
    </c:otherwise>
</c:choose>
</div>

<%@ include file="../layout/footer.jsp" %>
