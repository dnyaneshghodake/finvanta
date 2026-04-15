<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Transaction Limits" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <c:if test="${not empty success}"><div class="alert alert-success"><c:out value="${success}"/></div></c:if>
    <c:if test="${not empty error}"><div class="alert alert-danger"><c:out value="${error}"/></div></c:if>

    <!-- CBS: Create Transaction Limit per Finacle LIMDEF -->
    <div class="fv-card mb-3">
        <div class="card-header"><i class="bi bi-plus-circle"></i> Create New Limit (Finacle LIMDEF)</div>
        <div class="card-body">
            <p class="text-muted small">Per RBI Internal Controls: per-role, per-type amount limits. Set to 0 to block a transaction type for a role (e.g., MAKER cannot WRITE_OFF).</p>
            <form method="post" action="${pageContext.request.contextPath}/admin/limits/create" class="fv-form">
                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
                <div class="row mb-2">
                    <div class="col-md-2">
                        <label class="form-label small">Role *</label>
                        <select name="role" class="form-select form-select-sm" required>
                            <option value="MAKER">MAKER</option>
                            <option value="CHECKER">CHECKER</option>
                            <option value="ADMIN">ADMIN</option>
                        </select>
                    </div>
                    <div class="col-md-2">
                        <label class="form-label small">Transaction Type *</label>
                        <select name="transactionType" class="form-select form-select-sm" required>
                            <option value="ALL">ALL (Default)</option>
                            <option value="REPAYMENT">Repayment</option>
                            <option value="DISBURSEMENT">Disbursement</option>
                            <option value="PREPAYMENT">Prepayment</option>
                            <option value="FEE_CHARGE">Fee Charge</option>
                            <option value="WRITE_OFF">Write-Off</option>
                            <option value="CASH_DEPOSIT">Cash Deposit</option>
                            <option value="CASH_WITHDRAWAL">Cash Withdrawal</option>
                            <option value="TRANSFER_DEBIT">Transfer</option>
                        </select>
                    </div>
                    <div class="col-md-2">
                        <label class="form-label small">Per-Transaction Limit</label>
                        <input type="number" name="perTransactionLimit" class="form-control form-control-sm" data-fv-type="amount" placeholder="e.g., 1000000"/>
                    </div>
                    <div class="col-md-2">
                        <label class="form-label small">Daily Aggregate Limit</label>
                        <input type="number" name="dailyAggregateLimit" class="form-control form-control-sm" data-fv-type="amount" placeholder="e.g., 5000000"/>
                    </div>
                    <div class="col-md-3">
                        <label class="form-label small">Description</label>
                        <input type="text" name="description" class="form-control form-control-sm" maxlength="500" placeholder="e.g., Maker default limit"/>
                    </div>
                    <div class="col-md-1 d-flex align-items-end">
                        <button type="submit" class="btn btn-sm btn-fv-primary" data-confirm="Create this limit?"><i class="bi bi-plus-circle"></i> Create</button>
                    </div>
                </div>
            </form>
        </div>
    </div>

    <div class="fv-card">
        <div class="card-header">Transaction Limits <span class="badge bg-secondary"><c:out value="${limits.size()}"/></span></div>
        <div class="card-body">
            <div class="table-responsive">
            <table class="table fv-table fv-datatable">
                <thead>
                    <tr>
                        <th>Role</th>
                        <th>Transaction Type</th>
                        <th class="text-end">Per-Transaction Limit</th>
                        <th class="text-end">Daily Aggregate Limit</th>
                        <th>Status</th>
                        <th>Description</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="limit" items="${limits}">
                        <tr class="${!limit.active ? 'table-secondary' : limit.perTransactionLimit != null && limit.perTransactionLimit == 0 ? 'table-warning' : ''}">
                            <td class="fw-bold"><span class="badge ${limit.role == 'ADMIN' ? 'bg-danger' : limit.role == 'CHECKER' ? 'bg-primary' : 'bg-success'}"><c:out value="${limit.role}" /></span></td>
                            <td><c:out value="${limit.transactionType}" /></td>
                            <td class="text-end amount">
                                <c:choose>
                                    <c:when test="${limit.perTransactionLimit != null && limit.perTransactionLimit == 0}"><span class="text-danger fw-bold">BLOCKED</span></c:when>
                                    <c:when test="${limit.perTransactionLimit != null}">
                                        <fmt:formatNumber value="${limit.perTransactionLimit}" type="number" maxFractionDigits="2" />
                                    </c:when>
                                    <c:otherwise><span class="text-muted">Unlimited</span></c:otherwise>
                                </c:choose>
                            </td>
                            <td class="text-end amount">
                                <c:choose>
                                    <c:when test="${limit.dailyAggregateLimit != null && limit.dailyAggregateLimit == 0}"><span class="text-danger fw-bold">BLOCKED</span></c:when>
                                    <c:when test="${limit.dailyAggregateLimit != null}">
                                        <fmt:formatNumber value="${limit.dailyAggregateLimit}" type="number" maxFractionDigits="2" />
                                    </c:when>
                                    <c:otherwise><span class="text-muted">Unlimited</span></c:otherwise>
                                </c:choose>
                            </td>
                            <td>
                                <c:choose>
                                    <c:when test="${limit.active}"><span class="fv-badge fv-badge-active">ACTIVE</span></c:when>
                                    <c:otherwise><span class="fv-badge fv-badge-rejected">INACTIVE</span></c:otherwise>
                                </c:choose>
                            </td>
                            <td><c:out value="${limit.description}" default="--" /></td>
                            <td>
                                <%-- Inline Edit via Modal --%>
                                <button type="button" class="btn btn-sm btn-outline-primary" data-bs-toggle="modal" data-bs-target="#editLimit_${limit.id}" title="Edit"><i class="bi bi-pencil"></i></button>
                                <form method="post" action="${pageContext.request.contextPath}/admin/limits/${limit.id}/toggle-active" class="d-inline">
                                    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
                                    <c:choose>
                                        <c:when test="${limit.active}">
                                            <button type="submit" class="btn btn-sm btn-outline-warning" data-confirm="Deactivate this limit?"><i class="bi bi-pause-circle"></i></button>
                                        </c:when>
                                        <c:otherwise>
                                            <button type="submit" class="btn btn-sm btn-outline-success" data-confirm="Activate this limit?"><i class="bi bi-play-circle"></i></button>
                                        </c:otherwise>
                                    </c:choose>
                                </form>
                                <%-- Edit Modal --%>
                                <div class="modal fade" id="editLimit_${limit.id}" tabindex="-1">
                                    <div class="modal-dialog"><div class="modal-content">
                                        <div class="modal-header"><h5 class="modal-title">Edit: <c:out value="${limit.role}"/> / <c:out value="${limit.transactionType}"/></h5><button type="button" class="btn-close" data-bs-dismiss="modal"></button></div>
                                        <form method="post" action="${pageContext.request.contextPath}/admin/limits/${limit.id}/edit" class="fv-form">
                                            <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
                                            <div class="modal-body">
                                                <div class="mb-3"><label class="form-label">Per-Transaction Limit</label><input type="number" name="perTransactionLimit" class="form-control" data-fv-type="amount" value="${limit.perTransactionLimit}"/><small class="text-muted">Set to 0 to block this transaction type. Leave empty for unlimited.</small></div>
                                                <div class="mb-3"><label class="form-label">Daily Aggregate Limit</label><input type="number" name="dailyAggregateLimit" class="form-control" data-fv-type="amount" value="${limit.dailyAggregateLimit}"/></div>
                                                <div class="mb-3"><label class="form-label">Description</label><input type="text" name="description" class="form-control" maxlength="500" value="<c:out value='${limit.description}'/>"/></div>
                                            </div>
                                            <div class="modal-footer"><button type="button" class="btn btn-secondary" data-bs-dismiss="modal">Cancel</button><button type="submit" class="btn btn-fv-primary">Save Changes</button></div>
                                        </form>
                                    </div></div>
                                </div>
                            </td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty limits}">
                        <tr><td colspan="7" class="text-center text-muted">No limits configured</td></tr>
                    </c:if>
                </tbody>
            </table>
            </div>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
