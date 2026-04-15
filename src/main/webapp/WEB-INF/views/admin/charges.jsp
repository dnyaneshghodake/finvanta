<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Charge Configuration" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <c:if test="${not empty success}"><div class="alert alert-success"><c:out value="${success}"/></div></c:if>
    <c:if test="${not empty error}"><div class="alert alert-danger"><c:out value="${error}"/></div></c:if>

    <!-- CBS: Create Charge Form per Finacle CHRG_MASTER -->
    <div class="fv-card mb-3">
        <div class="card-header"><i class="bi bi-plus-circle"></i> Create New Charge</div>
        <div class="card-body">
            <form method="post" action="${pageContext.request.contextPath}/admin/charges/create" class="fv-form">
                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
                <div class="row mb-2">
                    <div class="col-md-2"><label class="form-label small">Charge Code *</label><input type="text" name="chargeCode" class="form-control form-control-sm" required maxlength="50" placeholder="e.g., CHEQUE_RETURN" style="text-transform:uppercase;" oninput="this.value=this.value.toUpperCase();"/></div>
                    <div class="col-md-3"><label class="form-label small">Charge Name *</label><input type="text" name="chargeName" class="form-control form-control-sm" required maxlength="200"/></div>
                    <div class="col-md-2"><label class="form-label small">Event Trigger *</label>
                        <select name="eventTrigger" class="form-select form-select-sm" required>
                            <option value="DISBURSEMENT">Disbursement</option><option value="OVERDUE_EMI">Overdue EMI</option>
                            <option value="CHEQUE_RETURN">Cheque Return</option><option value="ACCOUNT_CLOSURE">Account Closure</option>
                            <option value="STATEMENT_REQUEST">Statement Request</option><option value="MANUAL">Manual</option>
                        </select>
                    </div>
                    <div class="col-md-2"><label class="form-label small">Calculation *</label>
                        <select name="calculationType" class="form-select form-select-sm" required>
                            <option value="FLAT">Flat Amount</option><option value="PERCENTAGE">Percentage</option><option value="SLAB">Slab-Based</option>
                        </select>
                    </div>
                    <div class="col-md-1"><label class="form-label small">Base Amt</label><input type="number" name="baseAmount" class="form-control form-control-sm" step="0.01" placeholder="500"/></div>
                    <div class="col-md-1"><label class="form-label small">Pct %</label><input type="number" name="percentage" class="form-control form-control-sm" step="0.01" placeholder="1.00"/></div>
                    <div class="col-md-1"><label class="form-label small">Min</label><input type="number" name="minAmount" class="form-control form-control-sm" step="0.01"/></div>
                </div>
                <div class="row mb-2">
                    <div class="col-md-1"><label class="form-label small">Max</label><input type="number" name="maxAmount" class="form-control form-control-sm" step="0.01"/></div>
                    <div class="col-md-1"><label class="form-label small">GST</label><div class="form-check mt-2"><input type="checkbox" name="gstApplicable" value="true" class="form-check-input" id="gstCheck"/><label class="form-check-label small" for="gstCheck">Yes</label></div></div>
                    <div class="col-md-1"><label class="form-label small">GST %</label><input type="number" name="gstRate" class="form-control form-control-sm" step="0.01" value="18.00"/></div>
                    <div class="col-md-2"><label class="form-label small">GL Income *</label>
                        <select name="glChargeIncome" class="form-select form-select-sm" required>
                            <c:forEach var="gl" items="${glAccounts}"><option value="${gl.glCode}" ${gl.glCode == '4002' ? 'selected' : ''}><c:out value="${gl.glCode}"/> &mdash; <c:out value="${gl.glName}"/></option></c:forEach>
                        </select>
                    </div>
                    <div class="col-md-2"><label class="form-label small">GL GST</label>
                        <select name="glGstPayable" class="form-select form-select-sm">
                            <option value="">-- N/A --</option>
                            <c:forEach var="gl" items="${glAccounts}"><option value="${gl.glCode}" ${gl.glCode == '2200' ? 'selected' : ''}><c:out value="${gl.glCode}"/> &mdash; <c:out value="${gl.glName}"/></option></c:forEach>
                        </select>
                    </div>
                    <div class="col-md-1"><label class="form-label small">Waiver</label><div class="form-check mt-2"><input type="checkbox" name="waiverAllowed" value="true" class="form-check-input"/><label class="form-check-label small">Yes</label></div></div>
                    <div class="col-md-1"><label class="form-label small">Max W%</label><input type="number" name="maxWaiverPercent" class="form-control form-control-sm" step="0.01" placeholder="50"/></div>
                    <div class="col-md-2"><label class="form-label small">Product</label>
                        <select name="productCode" class="form-select form-select-sm">
                            <option value="">ALL Products</option>
                            <c:forEach var="p" items="${products}"><option value="${p.productCode}"><c:out value="${p.productCode}"/></option></c:forEach>
                        </select>
                    </div>
                    <div class="col-md-1 d-flex align-items-end"><button type="submit" class="btn btn-sm btn-fv-primary" data-confirm="Create this charge?"><i class="bi bi-plus-circle"></i> Create</button></div>
                </div>
                <div class="row"><div class="col-md-6"><label class="form-label small">Slab JSON (for SLAB type)</label><input type="text" name="slabJson" class="form-control form-control-sm" placeholder='[{"min":0,"max":100000,"rate":0.10}]'/></div></div>
            </form>
        </div>
    </div>

    <div class="fv-card">
        <div class="card-header">Active Charges <span class="badge bg-secondary"><c:out value="${charges.size()}"/></span></div>
        <div class="card-body">
            <div class="table-responsive">
            <table class="table fv-table fv-datatable">
                <thead>
                    <tr>
                        <th>Code / Name</th>
                        <th>Trigger</th>
                        <th>Calculation</th>
                        <th class="text-end">Bounds</th>
                        <th>GST</th>
                        <th>GL / Product</th>
                        <th>Status</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="c" items="${charges}">
                        <tr>
                            <td><span class="fw-bold"><c:out value="${c.chargeCode}" /></span><br/><small class="text-muted"><c:out value="${c.chargeName}" /></small></td>
                            <td><c:out value="${c.eventTrigger}" /></td>
                            <td>
                                <c:out value="${c.calculationType}" />:
                                <span class="amount">
                                <c:choose>
                                    <c:when test="${c.calculationType == 'FLAT'}"><fmt:formatNumber value="${c.baseAmount}" type="number" maxFractionDigits="2" /></c:when>
                                    <c:when test="${c.calculationType == 'PERCENTAGE'}"><fmt:formatNumber value="${c.percentage}" maxFractionDigits="2" />%</c:when>
                                    <c:otherwise>Tiered</c:otherwise>
                                </c:choose>
                                </span>
                            </td>
                            <td class="text-end"><c:if test="${c.minAmount != null || c.maxAmount != null}"><small class="text-muted"><c:if test="${c.minAmount != null}"><fmt:formatNumber value="${c.minAmount}" type="number" maxFractionDigits="0" /></c:if><c:if test="${c.minAmount == null}">0</c:if> &ndash; <c:if test="${c.maxAmount != null}"><fmt:formatNumber value="${c.maxAmount}" type="number" maxFractionDigits="0" /></c:if><c:if test="${c.maxAmount == null}">No cap</c:if></small></c:if><c:if test="${c.minAmount == null && c.maxAmount == null}">--</c:if></td>
                            <td>
                                <c:choose>
                                    <c:when test="${c.gstApplicable}"><span class="fv-badge fv-badge-active"><fmt:formatNumber value="${c.gstRate}" maxFractionDigits="0" />%</span></c:when>
                                    <c:otherwise><span class="fv-badge fv-badge-closed">Exempt</span></c:otherwise>
                                </c:choose>
                            </td>
                            <td><span class="font-monospace"><c:out value="${c.glChargeIncome}" /></span><br/><small class="text-muted"><c:out value="${c.productCode}" default="ALL" /></small></td>
                            <td>
                                <c:choose>
                                    <c:when test="${c.isActive}"><span class="fv-badge fv-badge-active">ACTIVE</span></c:when>
                                    <c:otherwise><span class="fv-badge fv-badge-rejected">INACTIVE</span></c:otherwise>
                                </c:choose>
                            </td>
                            <td>
                                <form method="post" action="${pageContext.request.contextPath}/admin/charges/${c.id}/toggle-active" class="d-inline">
                                    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
                                    <c:choose>
                                        <c:when test="${c.isActive}">
                                            <button type="submit" class="btn btn-sm btn-outline-warning" data-confirm="Deactivate this charge?"><i class="bi bi-pause-circle"></i></button>
                                        </c:when>
                                        <c:otherwise>
                                            <button type="submit" class="btn btn-sm btn-outline-success" data-confirm="Activate this charge?"><i class="bi bi-play-circle"></i></button>
                                        </c:otherwise>
                                    </c:choose>
                                </form>
                            </td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty charges}">
                        <tr><td colspan="8" class="text-center text-muted">No charge configurations found</td></tr>
                    </c:if>
                </tbody>
            </table>
            </div>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
