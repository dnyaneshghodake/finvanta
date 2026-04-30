<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Branch Vault" />
<%-- Project convention: role checks use pageContext.request.isUserInRole(...)
     rather than the Spring Security taglib (spring-security-taglibs is not on
     the classpath). Mirrors deposit/view.jsp, dashboard/index.jsp, etc. --%>
<c:set var="isCustodian" value="${pageContext.request.isUserInRole('ROLE_CHECKER') || pageContext.request.isUserInRole('ROLE_ADMIN')}" />
<c:set var="isTellerOrMaker" value="${pageContext.request.isUserInRole('ROLE_TELLER') || pageContext.request.isUserInRole('ROLE_MAKER') || pageContext.request.isUserInRole('ROLE_ADMIN')}" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<%-- CBS Vault Dashboard per CBS VAULT_POS standard.
     - CHECKER / ADMIN: open the vault, close the vault, see PENDING movements link.
     - TELLER / MAKER:  view current vault balance + submit BUY/SELL requests
                        (which then await CHECKER/ADMIN approval).
     Per RBI Internal Controls / vault custodian dual control: every BUY/SELL
     between vault and till requires a checker sign-off before balances move. --%>

<div class="fv-main">
<ul class="fv-breadcrumb">
    <li><a href="${pageContext.request.contextPath}/dashboard"><i class="bi bi-speedometer2"></i> Home</a></li>
    <li class="active">Teller / Vault</li>
</ul>

<c:if test="${not empty success}"><div class="fv-alert alert alert-success"><c:out value="${success}"/></div></c:if>
<c:if test="${not empty error}">
    <div class="fv-alert alert alert-danger">
        <c:if test="${not empty errorCode}"><strong>[<c:out value="${errorCode}"/>]</strong> </c:if>
        <c:out value="${error}"/>
    </div>
</c:if>

<c:choose>
    <c:when test="${empty vault}">
        <%-- No vault open today: CHECKER/ADMIN see open form; others see notice. --%>
        <c:if test="${isCustodian}">
            <div class="fv-card">
                <div class="card-header"><i class="bi bi-bank"></i> Open Branch Vault</div>
                <div class="card-body">
                    <p class="text-muted">
                        Vault is not yet open for today. As custodian (CHECKER/ADMIN), enter
                        the carry-forward physical cash balance to start the day.
                    </p>
                    <form method="post" action="${pageContext.request.contextPath}/teller/vault/open" class="fv-form">
                        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
                        <div class="row mb-3">
                            <div class="col-md-4 fv-mandatory-group">
                                <label for="openingBalance" class="form-label fv-required">Opening Balance (INR)</label>
                                <input type="number" id="openingBalance" name="openingBalance"
                                       class="form-control" min="0" step="0.01" required autofocus/>
                            </div>
                        </div>
                        <button type="submit" class="btn btn-fv-primary"
                                data-confirm="Open vault with this opening balance?">
                            <i class="bi bi-check-circle"></i> Open Vault
                        </button>
                    </form>
                </div>
            </div>
        </c:if>
        <c:if test="${not isCustodian}">
            <div class="fv-alert alert alert-warning">
                <strong>Branch vault is not open for today.</strong>
                Ask a CHECKER / ADMIN custodian to open the vault before requesting cash movements.
            </div>
        </c:if>
    </c:when>
    <c:otherwise>
        <%-- Vault exists: show position card + role-conditional action panels. --%>
        <div class="fv-card mb-3">
            <div class="card-header">
                <i class="bi bi-bank"></i> Branch Vault
                <span class="badge bg-${vault.status == 'OPEN' ? 'success' : 'secondary'} ms-2">
                    <c:out value="${vault.status}"/>
                </span>
            </div>
            <div class="card-body">
                <p class="mb-1">
                    Branch: <c:out value="${vault.branchCode}"/>
                    | Business Date: <c:out value="${vault.businessDate}"/>
                    | Opened by: <c:out value="${vault.openedBy}" default="--"/>
                </p>
                <p class="mb-1">
                    Opening Balance: <strong><fmt:formatNumber value="${vault.openingBalance}" type="currency" currencyCode="INR"/></strong>
                    | Current Balance: <strong><fmt:formatNumber value="${vault.currentBalance}" type="currency" currencyCode="INR"/></strong>
                </p>
                <c:if test="${vault.status == 'CLOSED'}">
                    <p class="mb-0">
                        Closed by: <c:out value="${vault.closedBy}"/>
                        | Counted: <fmt:formatNumber value="${vault.countedBalance}" type="currency" currencyCode="INR"/>
                        | Variance:
                        <c:choose>
                            <c:when test="${vault.varianceAmount.signum() == 0}">
                                <span class="badge bg-success">zero</span>
                            </c:when>
                            <c:otherwise>
                                <span class="badge bg-warning text-dark">
                                    <fmt:formatNumber value="${vault.varianceAmount}" type="currency" currencyCode="INR"/>
                                </span>
                            </c:otherwise>
                        </c:choose>
                    </p>
                </c:if>
            </div>
        </div>

        <c:if test="${isTellerOrMaker and vault.status == 'OPEN'}">
                <div class="row">
                    <div class="col-md-6">
                        <div class="fv-card mb-3">
                            <div class="card-header"><i class="bi bi-arrow-down-circle"></i> Vault &rarr; Till (BUY)</div>
                            <div class="card-body">
                                <p class="text-muted small">
                                    Request cash from the vault to your till. A vault custodian
                                    (CHECKER / ADMIN) must approve before balances move. Your till
                                    must be OPEN.
                                </p>
                                <form method="post" action="${pageContext.request.contextPath}/teller/vault/buy" class="fv-form">
                                    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
                                    <div class="mb-2 fv-mandatory-group">
                                        <label class="form-label fv-required">Amount (INR)</label>
                                        <input type="number" name="amount" class="form-control" min="0.01" step="0.01" required/>
                                    </div>
                                    <div class="mb-2">
                                        <label class="form-label">Remarks</label>
                                        <input type="text" name="remarks" class="form-control" maxlength="500"/>
                                    </div>
                                    <button type="submit" class="btn btn-fv-primary"
                                            data-confirm="Submit BUY request? It will await custodian approval before vault and till balances move.">
                                        <i class="bi bi-check-circle"></i> Submit BUY Request
                                    </button>
                                </form>
                            </div>
                        </div>
                    </div>
                    <div class="col-md-6">
                        <div class="fv-card mb-3">
                            <div class="card-header"><i class="bi bi-arrow-up-circle"></i> Till &rarr; Vault (SELL)</div>
                            <div class="card-body">
                                <p class="text-muted small">
                                    Return excess cash from your till to the vault. Typically
                                    requested when till balance approaches the soft limit or at
                                    end-of-shift before till close.
                                </p>
                                <form method="post" action="${pageContext.request.contextPath}/teller/vault/sell" class="fv-form">
                                    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
                                    <div class="mb-2 fv-mandatory-group">
                                        <label class="form-label fv-required">Amount (INR)</label>
                                        <input type="number" name="amount" class="form-control" min="0.01" step="0.01" required/>
                                    </div>
                                    <div class="mb-2">
                                        <label class="form-label">Remarks</label>
                                        <input type="text" name="remarks" class="form-control" maxlength="500"/>
                                    </div>
                                    <button type="submit" class="btn btn-fv-primary"
                                            data-confirm="Submit SELL request? It will await custodian approval before vault and till balances move.">
                                        <i class="bi bi-check-circle"></i> Submit SELL Request
                                    </button>
                                </form>
                            </div>
                        </div>
                    </div>
                </div>
        </c:if>

        <c:if test="${isCustodian and vault.status == 'OPEN'}">
                <div class="fv-card">
                    <div class="card-header"><i class="bi bi-x-circle"></i> Close Branch Vault</div>
                    <div class="card-body">
                        <p class="text-muted small">
                            All teller tills must be CLOSED before the vault can close. Enter the
                            physical cash count; variance from the system balance will be flagged
                            in the audit trail.
                        </p>
                        <form method="post" action="${pageContext.request.contextPath}/teller/vault/close" class="fv-form">
                            <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
                            <div class="row mb-3">
                                <div class="col-md-4 fv-mandatory-group">
                                    <label for="countedBalance" class="form-label fv-required">Counted Balance (INR)</label>
                                    <input type="number" id="countedBalance" name="countedBalance"
                                           class="form-control" min="0" step="0.01" required/>
                                </div>
                                <div class="col-md-8">
                                    <label for="remarks" class="form-label">Remarks</label>
                                    <input type="text" id="remarks" name="remarks" class="form-control" maxlength="500"/>
                                </div>
                            </div>
                            <button type="submit" class="btn btn-outline-danger"
                                    data-confirm="Close the vault for today? All tills must already be CLOSED.">
                                <i class="bi bi-x-circle"></i> Close Vault
                            </button>
                        </form>
                    </div>
                </div>
        </c:if>
    </c:otherwise>
</c:choose>
</div>

<%@ include file="../layout/footer.jsp" %>
