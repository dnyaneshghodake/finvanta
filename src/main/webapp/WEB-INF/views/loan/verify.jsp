<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Verify Application" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <ul class="fv-breadcrumb">
        <li><a href="${pageContext.request.contextPath}/dashboard"><i class="bi bi-speedometer2"></i> Home</a></li>
        <li><a href="${pageContext.request.contextPath}/loan/applications">Loan Applications</a></li>
        <li class="active">Verify &mdash; <c:out value="${application.applicationNumber}" /></li>
    </ul>

    <div class="fv-card">
        <div class="card-header"><i class="bi bi-clipboard-check"></i> Application Details &mdash; <c:out value="${application.applicationNumber}" /> <div class="float-end"><a href="${pageContext.request.contextPath}/loan/applications" class="btn btn-sm btn-outline-secondary" data-fv-cancel="${pageContext.request.contextPath}/loan/applications"><i class="bi bi-arrow-left"></i> Back <span class="fv-kbd">F3</span></a></div></div>
        <div class="card-body">
            <table class="table fv-table">
                <tbody>
                    <tr><td class="fw-bold">Application Number</td><td><c:out value="${application.applicationNumber}" /></td></tr>
                    <tr><td class="fw-bold">Customer</td><td><a href="${pageContext.request.contextPath}/customer/view/${application.customer.id}"><c:out value="${application.customer.fullName}" /></a> (<c:out value="${application.customer.customerNumber}" />)</td></tr>
                    <tr><td class="fw-bold">Branch</td><td><c:out value="${application.branch.branchCode}" /> - <c:out value="${application.branch.branchName}" /></td></tr>
                    <tr><td class="fw-bold">Product Type</td><td><c:out value="${application.productType}" /></td></tr>
                    <tr><td class="fw-bold">Requested Amount</td><td class="amount"><fmt:formatNumber value="${application.requestedAmount}" type="number" maxFractionDigits="2" /> INR</td></tr>
                    <tr><td class="fw-bold">Interest Rate</td><td><fmt:formatNumber value="${application.interestRate}" type="number" maxFractionDigits="2" />% p.a.</td></tr>
                    <tr><td class="fw-bold">Penal Rate</td><td><c:out value="${application.penalRate}" default="--" />% p.a.</td></tr>
                    <tr><td class="fw-bold">Tenure</td><td><c:out value="${application.tenureMonths}" /> months</td></tr>
                    <tr><td class="fw-bold">Purpose</td><td><c:out value="${application.purpose}" default="--" /></td></tr>
                    <tr><td class="fw-bold">Risk Category</td><td><c:out value="${application.riskCategory}" default="--" /></td></tr>
                    <tr><td class="fw-bold">Collateral</td><td><c:out value="${application.collateralReference}" default="Unsecured" /></td></tr>
                    <tr><td class="fw-bold">KYC Status</td><td>
                        <c:choose>
                            <c:when test="${application.customer.kycVerified}"><span class="fv-badge fv-badge-active">KYC Verified</span></c:when>
                            <c:otherwise><span class="fv-badge fv-badge-rejected">KYC NOT Verified</span></c:otherwise>
                        </c:choose>
                    </td></tr>
                    <tr><td class="fw-bold">CIBIL Score</td><td><c:out value="${application.customer.cibilScore}" /></td></tr>
                </tbody>
            </table>
        </div>
    </div>

    <!-- CBS Collaterals (Finacle COLMAS) -->
    <div class="fv-card">
        <div class="card-header">Collaterals <span class="badge bg-secondary ms-2">${not empty collaterals ? collaterals.size() : 0}</span></div>
        <div class="card-body">
            <c:if test="${not empty collaterals}">
            <table class="table fv-table">
                <thead><tr><th>Ref</th><th>Type</th><th>Owner</th><th class="text-end">Market Value</th><th>Lien</th><th>Status</th></tr></thead>
                <tbody>
                <c:forEach var="col" items="${collaterals}">
                    <tr>
                        <td class="font-monospace"><c:out value="${col.collateralRef}" /></td>
                        <td><c:out value="${col.collateralType}" /></td>
                        <td><c:out value="${col.ownerName}" /> (<c:out value="${col.ownerRelationship}" />)</td>
                        <td class="text-end amount"><fmt:formatNumber value="${col.marketValue}" type="number" maxFractionDigits="2" /> INR</td>
                        <td><c:out value="${col.lienStatus}" /></td>
                        <td><span class="fv-badge fv-badge-active"><c:out value="${col.status}" /></span></td>
                    </tr>
                </c:forEach>
                </tbody>
            </table>
            </c:if>
            <c:if test="${empty collaterals}"><p class="text-muted">No collaterals registered.</p></c:if>
            <hr />
            <h6>Register Collateral</h6>
            <form method="post" action="${pageContext.request.contextPath}/loan/collateral/${application.id}" class="fv-form">
                <div class="row mb-2">
                    <div class="col-md-3">
                        <label class="form-label">Type *</label>
                        <select name="collateralType" id="colType" class="form-select" required onchange="document.getElementById('goldF').style.display=this.value==='GOLD'?'':'none';document.getElementById('propF').style.display=this.value==='PROPERTY'?'':'none';document.getElementById('vehF').style.display=this.value==='VEHICLE'?'':'none';document.getElementById('fdF').style.display=this.value==='FD'?'':'none';">
                            <option value="">-- Select --</option>
                            <c:forEach var="ct" items="${collateralTypes}"><option value="${ct}"><c:out value="${ct}" /></option></c:forEach>
                        </select>
                    </div>
                    <div class="col-md-3"><label class="form-label">Owner *</label><input type="text" name="ownerName" class="form-control" required value="${application.customer.fullName}" /></div>
                    <div class="col-md-3"><label class="form-label">Relationship</label><select name="ownerRelationship" class="form-select"><option value="SELF">Self</option><option value="SPOUSE">Spouse</option><option value="PARENT">Parent</option><option value="GUARANTOR">Guarantor</option></select></div>
                    <div class="col-md-3"><label class="form-label">Market Value</label><input type="number" name="marketValue" class="form-control" step="0.01" min="0" /></div>
                </div>
                <div id="goldF" style="display:none" class="row mb-2">
                    <div class="col-md-3"><label class="form-label">Purity</label><select name="goldPurity" class="form-select"><option value="">--</option><option value="24K">24K</option><option value="22K">22K</option><option value="18K">18K</option></select></div>
                    <div class="col-md-3"><label class="form-label">Weight (g)</label><input type="number" name="goldWeightGrams" class="form-control" step="0.001" /></div>
                    <div class="col-md-3"><label class="form-label">Net Weight (g)</label><input type="number" name="goldNetWeightGrams" class="form-control" step="0.001" /></div>
                    <div class="col-md-3"><label class="form-label">Rate/g (INR)</label><input type="number" name="goldRatePerGram" class="form-control" step="0.01" /></div>
                </div>
                <div id="propF" style="display:none" class="row mb-2">
                    <div class="col-md-4"><label class="form-label">Address</label><input type="text" name="propertyAddress" class="form-control" /></div>
                    <div class="col-md-2"><label class="form-label">Type</label><select name="propertyType" class="form-select"><option value="">--</option><option value="RESIDENTIAL">Residential</option><option value="COMMERCIAL">Commercial</option><option value="LAND">Land</option></select></div>
                    <div class="col-md-3"><label class="form-label">Area (sqft)</label><input type="number" name="propertyAreaSqft" class="form-control" step="0.01" /></div>
                    <div class="col-md-3"><label class="form-label">Reg. No.</label><input type="text" name="registrationNumber" class="form-control" /></div>
                </div>
                <div id="vehF" style="display:none" class="row mb-2">
                    <div class="col-md-4"><label class="form-label">Registration</label><input type="text" name="vehicleRegistration" class="form-control" /></div>
                    <div class="col-md-4"><label class="form-label">Make</label><input type="text" name="vehicleMake" class="form-control" /></div>
                    <div class="col-md-4"><label class="form-label">Model</label><input type="text" name="vehicleModel" class="form-control" /></div>
                </div>
                <div id="fdF" style="display:none" class="row mb-2">
                    <div class="col-md-4"><label class="form-label">FD Number</label><input type="text" name="fdNumber" class="form-control" /></div>
                    <div class="col-md-4"><label class="form-label">Bank</label><input type="text" name="fdBankName" class="form-control" /></div>
                    <div class="col-md-4"><label class="form-label">Amount</label><input type="number" name="fdAmount" class="form-control" step="0.01" /></div>
                </div>
                <div class="mb-2"><label class="form-label">Description</label><input type="text" name="description" class="form-control" placeholder="Brief description" /></div>
                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                <button type="submit" class="btn btn-outline-primary btn-sm">Register Collateral</button>
            </form>
        </div>
    </div>

    <!-- CBS Documents (Finacle DOCMAS) -->
    <div class="fv-card">
        <div class="card-header">Documents <span class="badge bg-secondary ms-2">${not empty documents ? documents.size() : 0}</span></div>
        <div class="card-body">
            <c:if test="${not empty documents}">
            <table class="table fv-table">
                <thead><tr><th>Type</th><th>Name</th><th>Mandatory</th><th>Status</th><th>Verified By</th><th>Actions</th></tr></thead>
                <tbody>
                <c:forEach var="doc" items="${documents}">
                    <tr>
                        <td><c:out value="${doc.documentType}" /></td>
                        <td><c:out value="${doc.documentName}" /></td>
                        <td><c:if test="${doc.mandatory}"><span class="fv-badge fv-badge-npa">Required</span></c:if><c:if test="${!doc.mandatory}">Optional</c:if></td>
                        <td><c:choose><c:when test="${doc.verificationStatus == 'VERIFIED'}"><span class="fv-badge fv-badge-active">VERIFIED</span></c:when><c:when test="${doc.verificationStatus == 'REJECTED'}"><span class="fv-badge fv-badge-npa">REJECTED</span></c:when><c:otherwise><span class="fv-badge fv-badge-pending">PENDING</span></c:otherwise></c:choose></td>
                        <td><c:out value="${doc.verifiedBy}" default="--" /></td>
                        <td><c:if test="${doc.verificationStatus == 'PENDING'}">
                            <form method="post" action="${pageContext.request.contextPath}/loan/document/verify/${doc.id}" class="d-inline"><input type="hidden" name="applicationId" value="${application.id}" /><input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" /><button type="submit" class="btn btn-sm btn-success">Verify</button></form>
                            <form method="post" action="${pageContext.request.contextPath}/loan/document/reject/${doc.id}" class="d-inline"><input type="hidden" name="applicationId" value="${application.id}" /><input type="hidden" name="rejectionReason" value="" id="docRej_${doc.id}" /><input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" /><button type="submit" class="btn btn-sm btn-danger" onclick="var r=prompt('Rejection reason:'); if(!r){return false;} document.getElementById('docRej_${doc.id}').value=r;">Reject</button></form>
                        </c:if></td>
                    </tr>
                </c:forEach>
                </tbody>
            </table>
            </c:if>
            <c:if test="${empty documents}"><p class="text-muted">No documents uploaded.</p></c:if>
            <hr />
            <h6>Upload Document</h6>
            <form method="post" action="${pageContext.request.contextPath}/loan/document/${application.id}" class="fv-form">
                <div class="row mb-2">
                    <div class="col-md-3"><label class="form-label">Type *</label><select name="documentType" class="form-select" required><option value="">-- Select --</option><option value="IDENTITY_PROOF">Identity Proof</option><option value="ADDRESS_PROOF">Address Proof</option><option value="INCOME_PROOF">Income Proof</option><option value="BANK_STATEMENT">Bank Statement</option><option value="ITR">ITR</option><option value="PROPERTY_TITLE">Property Title</option><option value="SALE_AGREEMENT">Sale Agreement</option><option value="VALUATION_REPORT">Valuation Report</option><option value="GOLD_APPRAISAL">Gold Appraisal</option><option value="VEHICLE_RC">Vehicle RC</option><option value="INSURANCE">Insurance</option><option value="NOC">NOC</option><option value="OTHER">Other</option></select></div>
                    <div class="col-md-3"><label class="form-label">Name *</label><input type="text" name="documentName" class="form-control" required placeholder="e.g., PAN Card" /></div>
                    <div class="col-md-3"><label class="form-label">Remarks</label><input type="text" name="remarks" class="form-control" /></div>
                    <div class="col-md-3 d-flex align-items-end"><div class="form-check"><input type="checkbox" name="mandatory" value="true" class="form-check-input" id="mand" /><label class="form-check-label" for="mand">Mandatory</label></div></div>
                </div>
                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                <button type="submit" class="btn btn-outline-primary btn-sm">Upload Document</button>
            </form>
        </div>
    </div>

    <!-- Verification Action -->
    <div class="fv-card">
        <div class="card-header">Verification Action</div>
        <div class="card-body">
            <form method="post" action="${pageContext.request.contextPath}/loan/verify/${application.id}" class="fv-form">
                <div class="mb-3">
                    <label for="remarks" class="form-label">Verification Remarks *</label>
                    <textarea name="remarks" id="remarks" class="form-control" rows="3" required placeholder="Enter verification remarks"></textarea>
                </div>
                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                <button type="submit" class="btn btn-success">Verify &amp; Approve for Next Stage</button>
                <a href="${pageContext.request.contextPath}/loan/applications" class="btn btn-outline-secondary ms-2">Cancel</a>
            </form>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
