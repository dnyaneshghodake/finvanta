<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="IB Settlement" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <ul class="fv-breadcrumb">
        <li><a href="${pageContext.request.contextPath}/dashboard"><i class="bi bi-speedometer2"></i> Home</a></li>
        <li class="active">IB Settlement</li>
    </ul>

    <c:if test="${not empty success}"><div class="fv-alert alert alert-success"><c:out value="${success}" /></div></c:if>
    <c:if test="${not empty error}"><div class="fv-alert alert alert-danger"><c:out value="${error}" /></div></c:if>

    <div class="fv-card">
        <div class="card-header">Inter-Branch Settlement &mdash; HO Manual Settle (Finacle IB_SETTLEMENT)</div>
        <div class="card-body">
            <p class="text-muted">Stale PENDING inter-branch transactions from prior dates indicate a failed EOD. These require explicit Head Office authorization to settle because cross-date settlement affects prior-day GL balances and regulatory reporting.</p>

            <div class="row mb-4">
                <div class="col-md-4">
                    <div class="fv-stat-card ${stalePendingCount > 0 ? 'stat-warning' : 'stat-success'}">
                        <div class="card-body text-center">
                            <h2 class="stat-value mb-0">
                                <c:out value="${stalePendingCount}" />
                            </h2>
                            <p class="text-muted mb-0">Stale PENDING Transactions</p>
                        </div>
                    </div>
                </div>
            </div>

            <c:if test="${stalePendingCount > 0}">
                <div class="fv-card">
                    <div class="card-header">HO Manual Settlement</div>
                    <div class="card-body">
                        <form method="post" action="${pageContext.request.contextPath}/admin/ib-settlement/manual-settle">
                            <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                            <div class="row">
                                <div class="col-md-6 mb-3">
                                    <label class="form-label">Reason for HO Settlement *</label>
                                    <textarea name="reason" class="form-control" rows="3" required
                                              placeholder="e.g., Prior-day EOD failed due to network outage. Root cause resolved."></textarea>
                                    <small class="text-muted">Per RBI audit norms: mandatory for cross-date settlement</small>
                                </div>
                                <div class="col-md-6 mb-3">
                                    <label class="form-label">HO Authorization Reference *</label>
                                    <input type="text" name="hoAuthorizationRef" class="form-control" required
                                           placeholder="e.g., HO/IB/2026/04/001" />
                                    <small class="text-muted">Head Office authorization number for audit trail</small>
                                </div>
                            </div>
                            <button type="submit" class="btn btn-fv-warning"
                                    data-confirm="This will settle ${stalePendingCount} stale PENDING IB transactions. This action cannot be undone. Continue?">
                                <i class="bi bi-exclamation-triangle"></i> Settle <c:out value="${stalePendingCount}" /> Stale Transaction(s)
                            </button>
                        </form>
                    </div>
                </div>
            </c:if>

            <c:if test="${stalePendingCount == 0}">
                <div class="alert alert-success"><i class="bi bi-check-circle"></i> No stale PENDING inter-branch transactions. All settlements are current.</div>
            </c:if>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
