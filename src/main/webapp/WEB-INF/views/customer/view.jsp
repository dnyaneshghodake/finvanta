<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Customer Details" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <%-- CBS Tier-1: Breadcrumb per Finacle/Temenos: Home > Module > Screen --%>
    <ul class="fv-breadcrumb">
        <li><a href="${pageContext.request.contextPath}/dashboard"><i class="bi bi-speedometer2"></i> Home</a></li>
        <li><a href="${pageContext.request.contextPath}/customer/list">Customers</a></li>
        <li class="active">CIF Detail &mdash; <c:out value="${customer.customerNumber}" /></li>
    </ul>

    <c:if test="${not empty success}">
        <div class="fv-alert alert alert-success"><c:out value="${success}" /></div>
    </c:if>
    <c:if test="${not empty error}">
        <div class="fv-alert alert alert-danger"><c:out value="${error}" /></div>
    </c:if>

    <div class="fv-card">
        <div class="card-header"><i class="bi bi-person-badge"></i> Customer Details &mdash; <c:out value="${customer.customerNumber}" />
            <div class="float-end">
                <button type="button" class="btn btn-sm btn-outline-secondary" onclick="window.print();" title="Print CIF"><i class="bi bi-printer"></i> Print <span class="fv-kbd">Ctrl+P</span></button>
                <a href="${pageContext.request.contextPath}/customer/list" class="btn btn-sm btn-outline-secondary" data-fv-cancel="${pageContext.request.contextPath}/customer/list"><i class="bi bi-arrow-left"></i> Back <span class="fv-kbd">F3</span></a>
                <c:if test="${pageContext.request.isUserInRole('ROLE_MAKER') || pageContext.request.isUserInRole('ROLE_ADMIN')}">
                <a href="${pageContext.request.contextPath}/customer/edit/${customer.id}" class="btn btn-sm btn-fv-primary"><i class="bi bi-pencil"></i> Edit</a>
                </c:if>
            </div>
        </div>
        <div class="card-body">
            <%-- CBS Tier-1: Multi-column detail grid per Finacle CIF_VIEW — 3 columns --%>
            <div class="fv-detail-grid fv-detail-3col">
                <div class="fv-detail-section-title"><i class="bi bi-person-badge"></i> CIF Identity</div>
                <div class="fv-detail-item"><div class="fv-detail-label">Customer Number</div><div class="fv-detail-value"><c:out value="${customer.customerNumber}" /></div></div>
                <div class="fv-detail-item"><div class="fv-detail-label">Full Name</div><div class="fv-detail-value"><c:out value="${customer.fullName}" /></div></div>
                <div class="fv-detail-item"><div class="fv-detail-label">Customer Type</div><div class="fv-detail-value"><c:choose><c:when test="${customer.customerType == 'INDIVIDUAL'}">Individual</c:when><c:when test="${customer.customerType == 'JOINT'}">Joint</c:when><c:when test="${customer.customerType == 'HUF'}">HUF</c:when><c:when test="${customer.customerType == 'PARTNERSHIP'}">Partnership</c:when><c:when test="${customer.customerType == 'COMPANY'}">Company</c:when><c:when test="${customer.customerType == 'TRUST'}">Trust</c:when><c:when test="${customer.customerType == 'NRI'}">NRI</c:when><c:when test="${customer.customerType == 'MINOR'}">Minor</c:when><c:when test="${customer.customerType == 'GOVERNMENT'}">Government</c:when><c:otherwise><c:out value="${customer.customerType}" /></c:otherwise></c:choose></div></div>
                <div class="fv-detail-item"><div class="fv-detail-label">Status</div><div class="fv-detail-value"><c:choose><c:when test="${customer.active}"><span class="fv-badge fv-badge-active">Active</span></c:when><c:otherwise><span class="fv-badge fv-badge-rejected">Inactive</span></c:otherwise></c:choose></div></div>
                <div class="fv-detail-item"><div class="fv-detail-label">Branch</div><div class="fv-detail-value"><a href="${pageContext.request.contextPath}/branch/view/${customer.branch.id}"><c:out value="${customer.branch.branchCode}" /> &mdash; <c:out value="${customer.branch.branchName}" /></a></div></div>
                <div class="fv-detail-item"><div class="fv-detail-label">CIBIL Score</div><div class="fv-detail-value"><c:out value="${customer.cibilScore}" default="--" /></div></div>

                <div class="fv-detail-section-title"><i class="bi bi-person"></i> Personal Details &amp; Demographics (CKYC)</div>
                <div class="fv-detail-item"><div class="fv-detail-label">Gender</div><div class="fv-detail-value"><c:choose><c:when test="${customer.gender == 'M'}">Male</c:when><c:when test="${customer.gender == 'F'}">Female</c:when><c:when test="${customer.gender == 'T'}">Transgender</c:when><c:otherwise>--</c:otherwise></c:choose></div></div>
                <div class="fv-detail-item"><div class="fv-detail-label">Date of Birth</div><div class="fv-detail-value"><c:out value="${customer.dateOfBirth}" default="--" /></div></div>
                <div class="fv-detail-item"><div class="fv-detail-label">Marital Status</div><div class="fv-detail-value"><c:choose><c:when test="${customer.maritalStatus == 'SINGLE'}">Single</c:when><c:when test="${customer.maritalStatus == 'MARRIED'}">Married</c:when><c:when test="${customer.maritalStatus == 'DIVORCED'}">Divorced</c:when><c:when test="${customer.maritalStatus == 'WIDOWED'}">Widowed</c:when><c:when test="${customer.maritalStatus == 'SEPARATED'}">Separated</c:when><c:otherwise>--</c:otherwise></c:choose></div></div>
                <div class="fv-detail-item"><div class="fv-detail-label">Father's Name</div><div class="fv-detail-value"><c:out value="${customer.fatherName}" default="--" /></div></div>
                <div class="fv-detail-item"><div class="fv-detail-label">Mother's Name</div><div class="fv-detail-value"><c:out value="${customer.motherName}" default="--" /></div></div>
                <c:if test="${not empty customer.spouseName}"><div class="fv-detail-item"><div class="fv-detail-label">Spouse Name</div><div class="fv-detail-value"><c:out value="${customer.spouseName}" /></div></div></c:if>
                <div class="fv-detail-item"><div class="fv-detail-label">Nationality</div><div class="fv-detail-value"><c:choose><c:when test="${customer.nationality == 'INDIAN'}">Indian</c:when><c:when test="${customer.nationality == 'NRI'}">NRI</c:when><c:when test="${customer.nationality == 'PIO'}">PIO</c:when><c:when test="${customer.nationality == 'OCI'}">OCI</c:when><c:when test="${customer.nationality == 'FOREIGN'}">Foreign</c:when><c:otherwise>--</c:otherwise></c:choose></div></div>
                <div class="fv-detail-item"><div class="fv-detail-label">Occupation</div><div class="fv-detail-value"><c:choose><c:when test="${customer.occupationCode == 'SALARIED_PRIVATE'}">Salaried (Pvt)</c:when><c:when test="${customer.occupationCode == 'SALARIED_GOVT'}">Salaried (Govt)</c:when><c:when test="${customer.occupationCode == 'BUSINESS'}">Business</c:when><c:when test="${customer.occupationCode == 'PROFESSIONAL'}">Professional</c:when><c:when test="${customer.occupationCode == 'SELF_EMPLOYED'}">Self Employed</c:when><c:when test="${customer.occupationCode == 'RETIRED'}">Retired</c:when><c:when test="${customer.occupationCode == 'HOUSEWIFE'}">Housewife</c:when><c:when test="${customer.occupationCode == 'STUDENT'}">Student</c:when><c:when test="${customer.occupationCode == 'AGRICULTURIST'}">Agriculturist</c:when><c:when test="${customer.occupationCode == 'OTHER'}">Other</c:when><c:otherwise>--</c:otherwise></c:choose></div></div>
                <%-- === PII & Contact (Masked per RBI IT Governance / UIDAI) === --%>
                <div class="fv-detail-section-title"><i class="bi bi-shield-lock"></i> PII &amp; Contact (Masked per RBI/UIDAI)</div>
                <div class="fv-detail-item"><div class="fv-detail-label">PAN</div><div class="fv-detail-value"><c:out value="${maskedPan}" default="--" /></div></div>
                <div class="fv-detail-item"><div class="fv-detail-label">Aadhaar</div><div class="fv-detail-value"><c:out value="${maskedAadhaar}" default="--" /></div></div>
                <div class="fv-detail-item"><div class="fv-detail-label">Mobile</div><div class="fv-detail-value"><c:out value="${maskedMobile}" default="--" /></div></div>
                <div class="fv-detail-item"><div class="fv-detail-label">Email</div><div class="fv-detail-value"><c:out value="${customer.email}" default="--" /></div></div>

                <%-- === Address === --%>
                <div class="fv-detail-section-title"><i class="bi bi-geo-alt"></i> Address</div>
                <div class="fv-detail-item"><div class="fv-detail-label">Correspondence Address</div><div class="fv-detail-value"><c:out value="${customer.address}" />, <c:out value="${customer.city}" />, <c:out value="${customer.state}" /> - <c:out value="${customer.pinCode}" /></div></div>
                <c:if test="${not customer.addressSameAsPermanent}">
                <div class="fv-detail-item"><div class="fv-detail-label">Permanent Address</div><div class="fv-detail-value"><c:out value="${customer.permanentAddress}" />, <c:out value="${customer.permanentCity}" />, <c:out value="${customer.permanentState}" /> - <c:out value="${customer.permanentPinCode}" /> (<c:out value="${customer.permanentCountry}" />)</div></div>
                </c:if>
                <c:if test="${customer.addressSameAsPermanent}">
                <div class="fv-detail-item"><div class="fv-detail-label">Permanent Address</div><div class="fv-detail-value"><span class="text-muted">Same as correspondence</span></div></div>
                </c:if>
                <%-- === KYC & Compliance === --%>
                <div class="fv-detail-section-title"><i class="bi bi-shield-check"></i> KYC &amp; Compliance</div>
                <div class="fv-detail-item"><div class="fv-detail-label">KYC Status</div><div class="fv-detail-value">
                    <c:choose>
                        <c:when test="${customer.kycVerified}"><span class="fv-badge fv-badge-active">Verified</span> (by <c:out value="${customer.kycVerifiedBy}" /> on <c:out value="${customer.kycVerifiedDate}" />)</c:when>
                        <c:otherwise>
                            <span class="fv-badge fv-badge-rejected">Not Verified</span>
                            <c:if test="${pageContext.request.isUserInRole('ROLE_CHECKER') || pageContext.request.isUserInRole('ROLE_ADMIN')}">
                            <form method="post" action="${pageContext.request.contextPath}/customer/verify-kyc/${customer.id}" class="d-inline ms-2">
                                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                                <button type="submit" class="btn btn-sm btn-success" data-confirm="Confirm KYC verification for this customer?"><i class="bi bi-patch-check"></i> Verify KYC</button>
                            </form>
                            </c:if>
                        </c:otherwise>
                    </c:choose>
                </div></div>
                <div class="fv-detail-item"><div class="fv-detail-label">KYC Risk Category</div><div class="fv-detail-value"><c:choose><c:when test="${customer.kycRiskCategory == 'HIGH'}"><span class="fv-badge fv-badge-npa">HIGH</span></c:when><c:when test="${customer.kycRiskCategory == 'LOW'}"><span class="fv-badge fv-badge-active">LOW</span></c:when><c:otherwise><span class="fv-badge fv-badge-pending">MEDIUM</span></c:otherwise></c:choose></div></div>
                <div class="fv-detail-item"><div class="fv-detail-label">KYC Expiry Date</div><div class="fv-detail-value"><c:choose><c:when test="${customer.kycExpired}"><span class="fv-badge fv-badge-npa">EXPIRED</span> <c:out value="${customer.kycExpiryDate}" /></c:when><c:when test="${customer.kycExpiringSoon}"><span class="fv-badge fv-badge-pending">Expiring Soon</span> <c:out value="${customer.kycExpiryDate}" /></c:when><c:when test="${not empty customer.kycExpiryDate}"><c:out value="${customer.kycExpiryDate}" /></c:when><c:otherwise>--</c:otherwise></c:choose></div></div>
                <c:if test="${customer.rekycDue}">
                <div class="fv-detail-item"><div class="fv-detail-label">Re-KYC Status</div><div class="fv-detail-value"><span class="fv-badge fv-badge-npa"><i class="bi bi-exclamation-triangle"></i> RE-KYC DUE</span></div></div>
                </c:if>
                <div class="fv-detail-item"><div class="fv-detail-label">PEP (Politically Exposed)</div><div class="fv-detail-value"><c:choose><c:when test="${customer.pep}"><span class="fv-badge fv-badge-npa">YES - Enhanced Due Diligence</span></c:when><c:otherwise>No</c:otherwise></c:choose></div></div>
                <c:if test="${not empty customer.customerGroupName}">
                <div class="fv-detail-item"><div class="fv-detail-label">Customer Group</div><div class="fv-detail-value"><c:out value="${customer.customerGroupName}" /> (ID: <c:out value="${customer.customerGroupId}" />)</div></div>
                </c:if>
                <%-- CKYC / CERSAI Status --%>
                <tr><td class="fw-bold">CKYC Status</td><td>
                    <c:choose>
                        <c:when test="${customer.ckycStatus == 'REGISTERED'}"><span class="fv-badge fv-badge-active">REGISTERED</span> KIN: <c:out value="${customer.ckycNumber}" /></c:when>
                        <c:when test="${customer.ckycStatus == 'UPLOADED'}"><span class="fv-badge fv-badge-pending">UPLOADED</span></c:when>
                        <c:when test="${customer.ckycStatus == 'FAILED'}"><span class="fv-badge fv-badge-npa">FAILED</span></c:when>
                        <c:otherwise><span class="text-muted">Not Registered</span></c:otherwise>
                    </c:choose>
                </td></tr>
                <c:if test="${not empty customer.ckycNumber}"><tr><td class="fw-bold">CKYC Number (KIN)</td><td><c:out value="${customer.ckycNumber}" /></td></tr></c:if>
                <tr><td class="fw-bold">KYC Mode</td><td><c:choose><c:when test="${customer.kycMode == 'IN_PERSON'}">In-Person</c:when><c:when test="${customer.kycMode == 'VIDEO_KYC'}">Video KYC</c:when><c:when test="${customer.kycMode == 'DIGITAL_KYC'}">Digital KYC</c:when><c:when test="${customer.kycMode == 'CKYC_DOWNLOAD'}">CKYC Download</c:when><c:otherwise>--</c:otherwise></c:choose></td></tr>
                <c:if test="${not empty customer.photoIdType}"><tr><td class="fw-bold">Photo ID</td><td><c:choose><c:when test="${customer.photoIdType == 'PASSPORT'}">Passport</c:when><c:when test="${customer.photoIdType == 'VOTER_ID'}">Voter ID</c:when><c:when test="${customer.photoIdType == 'DRIVING_LICENSE'}">Driving License</c:when><c:when test="${customer.photoIdType == 'PAN_CARD'}">PAN Card</c:when><c:when test="${customer.photoIdType == 'AADHAAR'}">Aadhaar</c:when><c:when test="${customer.photoIdType == 'NREGA_CARD'}">NREGA Card</c:when><c:otherwise><c:out value="${customer.photoIdType}" /></c:otherwise></c:choose><c:if test="${not empty customer.photoIdNumber}"> &mdash; <c:out value="${customer.photoIdNumber}" /></c:if></td></tr></c:if>
                <c:if test="${not empty customer.addressProofType}"><tr><td class="fw-bold">Address Proof</td><td><c:choose><c:when test="${customer.addressProofType == 'PASSPORT'}">Passport</c:when><c:when test="${customer.addressProofType == 'VOTER_ID'}">Voter ID</c:when><c:when test="${customer.addressProofType == 'DRIVING_LICENSE'}">Driving License</c:when><c:when test="${customer.addressProofType == 'UTILITY_BILL'}">Utility Bill</c:when><c:when test="${customer.addressProofType == 'BANK_STATEMENT'}">Bank Statement</c:when><c:when test="${customer.addressProofType == 'AADHAAR'}">Aadhaar</c:when><c:when test="${customer.addressProofType == 'RATION_CARD'}">Ration Card</c:when><c:when test="${customer.addressProofType == 'RENT_AGREEMENT'}">Rent Agreement</c:when><c:otherwise><c:out value="${customer.addressProofType}" /></c:otherwise></c:choose><c:if test="${not empty customer.addressProofNumber}"> &mdash; <c:out value="${customer.addressProofNumber}" /></c:if></td></tr></c:if>
                <c:if test="${customer.videoKycDone}"><tr><td class="fw-bold">Video KYC</td><td><span class="fv-badge fv-badge-active">Completed</span></td></tr></c:if>
                <tr><td class="fw-bold">Annual Income Band</td><td><c:choose><c:when test="${customer.annualIncomeBand == 'BELOW_1L'}">&lt; 1 Lakh</c:when><c:when test="${customer.annualIncomeBand == '1L_TO_5L'}">1 - 5 Lakhs</c:when><c:when test="${customer.annualIncomeBand == '5L_TO_10L'}">5 - 10 Lakhs</c:when><c:when test="${customer.annualIncomeBand == '10L_TO_25L'}">10 - 25 Lakhs</c:when><c:when test="${customer.annualIncomeBand == '25L_TO_1CR'}">25 Lakhs - 1 Crore</c:when><c:when test="${customer.annualIncomeBand == 'ABOVE_1CR'}">&gt; 1 Crore</c:when><c:otherwise>--</c:otherwise></c:choose></td></tr>
                <tr><td class="fw-bold">CIBIL Score</td><td><c:out value="${customer.cibilScore}" /></td></tr>
                <tr><td class="fw-bold">Branch</td><td><a href="${pageContext.request.contextPath}/branch/view/${customer.branch.id}"><c:out value="${customer.branch.branchCode}" /> - <c:out value="${customer.branch.branchName}" /></a></td></tr>
                <tr><td class="fw-bold">Monthly Income</td><td class="amount"><c:if test="${customer.monthlyIncome != null}"><fmt:formatNumber value="${customer.monthlyIncome}" type="number" maxFractionDigits="2" /> INR</c:if><c:if test="${customer.monthlyIncome == null}">--</c:if></td></tr>
                <tr><td class="fw-bold">Max Borrowing Limit</td><td class="amount"><c:if test="${customer.maxBorrowingLimit != null}"><fmt:formatNumber value="${customer.maxBorrowingLimit}" type="number" maxFractionDigits="2" /> INR</c:if><c:if test="${customer.maxBorrowingLimit == null}">--</c:if></td></tr>
                <tr><td class="fw-bold">Employment</td><td><c:choose><c:when test="${customer.employmentType == 'SALARIED'}">Salaried</c:when><c:when test="${customer.employmentType == 'SELF_EMPLOYED'}">Self Employed</c:when><c:when test="${customer.employmentType == 'BUSINESS'}">Business</c:when><c:when test="${customer.employmentType == 'RETIRED'}">Retired</c:when><c:when test="${customer.employmentType == 'OTHER'}">Other</c:when><c:otherwise>--</c:otherwise></c:choose><c:if test="${not empty customer.employerName}"> &mdash; <c:out value="${customer.employerName}" /></c:if></td></tr>
                <%-- Nominee Details per RBI Nomination Guidelines --%>
                <c:if test="${not empty customer.nomineeDob or not empty customer.nomineeAddress or not empty customer.nomineeGuardianName}">
                <tr><td class="fw-bold">Nominee DOB</td><td><c:out value="${customer.nomineeDob}" default="--" /></td></tr>
                <c:if test="${not empty customer.nomineeGuardianName}"><tr><td class="fw-bold">Nominee Guardian</td><td><c:out value="${customer.nomineeGuardianName}" /></td></tr></c:if>
                <c:if test="${not empty customer.nomineeAddress}"><tr><td class="fw-bold">Nominee Address</td><td><c:out value="${customer.nomineeAddress}" /></td></tr></c:if>
                </c:if>
                <tr><td class="fw-bold">Status</td><td>
                    <c:choose>
                        <c:when test="${customer.active}"><span class="fv-badge fv-badge-active">Active</span></c:when>
                        <c:otherwise><span class="fv-badge fv-badge-rejected">Inactive</span></c:otherwise>
                    </c:choose>
                </td></tr>
                </tbody>
            </table>
        </div>
    </div>

    <!-- Loan Applications for this Customer -->
    <div class="fv-card">
        <div class="card-header">Loan Applications</div>
        <div class="card-body">
            <div class="table-responsive">
            <table class="table fv-table">
                <thead>
                    <tr>
                        <th>App No.</th>
                        <th>Product</th>
                        <th class="text-end">Amount</th>
                        <th>Rate</th>
                        <th>Status</th>
                        <th>Date</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="app" items="${loanApplications}">
                        <tr>
                            <td><c:out value="${app.applicationNumber}" /></td>
                            <td><c:out value="${app.productType}" /></td>
                            <td class="amount"><fmt:formatNumber value="${app.requestedAmount}" type="number" maxFractionDigits="2" /></td>
                            <td><fmt:formatNumber value="${app.interestRate}" type="number" maxFractionDigits="2" />%</td>
                            <td><span class="fv-badge fv-badge-pending"><c:out value="${app.status}" /></span></td>
                            <td><c:out value="${app.applicationDate}" /></td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty loanApplications}">
                        <tr><td colspan="6" class="text-center text-muted">No loan applications</td></tr>
                    </c:if>
                </tbody>
            </table>
            </div>
        </div>
    </div>

    <!-- Loan Accounts for this Customer -->
    <div class="fv-card">
        <div class="card-header">Loan Accounts</div>
        <div class="card-body">
            <div class="table-responsive">
            <table class="table fv-table">
                <thead>
                    <tr>
                        <th>Account No.</th>
                        <th>Product</th>
                        <th class="text-end">Sanctioned</th>
                        <th class="text-end">Outstanding</th>
                        <th>DPD</th>
                        <th>Status</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="acc" items="${loanAccounts}">
                        <tr>
                            <td><a href="${pageContext.request.contextPath}/loan/account/${acc.accountNumber}"><c:out value="${acc.accountNumber}" /></a></td>
                            <td><c:out value="${acc.productType}" /></td>
                            <td class="amount"><fmt:formatNumber value="${acc.sanctionedAmount}" type="number" maxFractionDigits="2" /></td>
                            <td class="amount"><fmt:formatNumber value="${acc.outstandingPrincipal}" type="number" maxFractionDigits="2" /></td>
                            <td><c:out value="${acc.daysPastDue}" /></td>
                            <td>
                                <c:choose>
                                    <c:when test="${acc.status.npa}"><span class="fv-badge fv-badge-npa"><c:out value="${acc.status}" /></span></c:when>
                                    <c:when test="${acc.status.sma}"><span class="fv-badge fv-badge-pending"><c:out value="${acc.status}" /></span></c:when>
                                    <c:when test="${acc.status.terminal}"><span class="fv-badge fv-badge-closed"><c:out value="${acc.status}" /></span></c:when>
                                    <c:otherwise><span class="fv-badge fv-badge-active"><c:out value="${acc.status}" /></span></c:otherwise>
                                </c:choose>
                            </td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty loanAccounts}">
                        <tr><td colspan="6" class="text-center text-muted">No loan accounts</td></tr>
                    </c:if>
                </tbody>
            </table>
            </div>
        </div>
    </div>

    <!-- CASA Deposit Accounts for this Customer -->
    <div class="fv-card">
        <div class="card-header">Deposit Accounts (CASA)</div>
        <div class="card-body">
            <div class="table-responsive">
            <table class="table fv-table">
                <thead>
                    <tr>
                        <th>Account No.</th>
                        <th>Type</th>
                        <th class="text-end">Balance</th>
                        <th>Status</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="dep" items="${depositAccounts}">
                        <tr>
                            <td><a href="${pageContext.request.contextPath}/deposit/view/${dep.accountNumber}"><c:out value="${dep.accountNumber}" /></a></td>
                            <td><c:out value="${dep.accountType}" /></td>
                            <td class="amount"><fmt:formatNumber value="${dep.ledgerBalance}" type="number" maxFractionDigits="2" /></td>
                            <td>
                                <c:choose>
                                    <c:when test="${dep.accountStatus == 'ACTIVE'}"><span class="fv-badge fv-badge-active"><c:out value="${dep.accountStatus}" /></span></c:when>
                                    <c:when test="${dep.accountStatus == 'CLOSED'}"><span class="fv-badge fv-badge-closed"><c:out value="${dep.accountStatus}" /></span></c:when>
                                    <c:otherwise><span class="fv-badge fv-badge-pending"><c:out value="${dep.accountStatus}" /></span></c:otherwise>
                                </c:choose>
                            </td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty depositAccounts}">
                        <tr><td colspan="4" class="text-center text-muted">No deposit accounts</td></tr>
                    </c:if>
                </tbody>
            </table>
            </div>
        </div>
    </div>

    <!-- CBS KYC Documents (per Finacle DOC_MASTER / RBI KYC Direction) -->
    <div class="fv-card">
        <div class="card-header"><i class="bi bi-file-earmark-text"></i> KYC Documents</div>
        <div class="card-body">
            <c:if test="${customer.active}">
            <c:if test="${pageContext.request.isUserInRole('ROLE_MAKER') || pageContext.request.isUserInRole('ROLE_ADMIN')}">
            <form method="post" action="${pageContext.request.contextPath}/customer/document/upload/${customer.id}" enctype="multipart/form-data" class="row g-2 mb-3 align-items-end">
                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                <div class="col-md-2">
                    <label class="form-label">Document Type *</label>
                    <select name="documentType" class="form-select form-select-sm" required>
                        <option value="">-- Select --</option>
                        <option value="PAN_CARD">PAN Card</option>
                        <option value="AADHAAR_FRONT">Aadhaar (Front)</option>
                        <option value="AADHAAR_BACK">Aadhaar (Back)</option>
                        <option value="PASSPORT">Passport</option>
                        <option value="VOTER_ID">Voter ID</option>
                        <option value="DRIVING_LICENSE">Driving License</option>
                        <option value="UTILITY_BILL">Utility Bill</option>
                        <option value="PHOTO">Photo</option>
                        <option value="SIGNATURE">Signature</option>
                        <option value="SALARY_SLIP">Salary Slip</option>
                        <option value="ITR">ITR</option>
                        <option value="OTHER">Other</option>
                    </select>
                </div>
                <div class="col-md-3">
                    <label class="form-label">File (PDF/JPG/PNG, max 5MB) *</label>
                    <input type="file" name="file" class="form-control form-control-sm" required accept=".pdf,.jpg,.jpeg,.png" />
                </div>
                <div class="col-md-2">
                    <label class="form-label">Doc Number</label>
                    <input type="text" name="documentNumber" class="form-control form-control-sm" maxlength="50" />
                </div>
                <div class="col-md-3">
                    <label class="form-label">Remarks</label>
                    <input type="text" name="remarks" class="form-control form-control-sm" maxlength="500" />
                </div>
                <div class="col-md-2">
                    <button type="submit" class="btn btn-sm btn-fv-primary"><i class="bi bi-upload"></i> Upload</button>
                </div>
            </form>
            </c:if>
            </c:if>
            <div class="table-responsive">
            <table class="table fv-table table-sm">
                <thead><tr><th>Type</th><th>File</th><th>Size</th><th>Doc No.</th><th>Status</th><th>Uploaded</th><th>Verified</th><th>Actions</th></tr></thead>
                <tbody>
                    <c:forEach var="doc" items="${documents}">
                        <tr>
                            <td><c:out value="${doc.documentType.displayName}" /></td>
                            <td>
                                <%-- CBS: Thumbnail preview for image documents per Finacle DOC_MASTER.
                                     Images (JPG/PNG) show inline thumbnail for quick visual identification.
                                     PDFs show file icon. Clicking opens the inline preview modal. --%>
                                <c:choose>
                                    <c:when test="${doc.contentType == 'image/jpeg' || doc.contentType == 'image/png'}">
                                        <a href="javascript:void(0);" onclick="previewDoc('${pageContext.request.contextPath}/customer/document/download/${doc.id}', '${doc.contentType}', '${doc.documentType.displayName}');" title="Click to preview">
                                            <img src="${pageContext.request.contextPath}/customer/document/download/${doc.id}" alt="<c:out value='${doc.fileName}'/>" style="max-height:40px; max-width:60px; border:1px solid #dee2e6; border-radius:3px; cursor:pointer;" />
                                            <small class="ms-1"><c:out value="${doc.fileName}" /></small>
                                        </a>
                                    </c:when>
                                    <c:otherwise>
                                        <a href="javascript:void(0);" onclick="previewDoc('${pageContext.request.contextPath}/customer/document/download/${doc.id}', '${doc.contentType}', '${doc.documentType.displayName}');" title="Click to preview">
                                            <i class="bi bi-file-earmark-pdf text-danger"></i>
                                            <small class="ms-1"><c:out value="${doc.fileName}" /></small>
                                        </a>
                                    </c:otherwise>
                                </c:choose>
                            </td>
                            <td><fmt:formatNumber value="${doc.fileSize / 1024}" maxFractionDigits="0" /> KB</td>
                            <td><c:out value="${doc.documentNumber}" default="--" /></td>
                            <td>
                                <c:choose>
                                    <c:when test="${doc.verificationStatus == 'VERIFIED'}"><span class="fv-badge fv-badge-active">Verified</span></c:when>
                                    <c:when test="${doc.verificationStatus == 'REJECTED'}"><span class="fv-badge fv-badge-rejected">Rejected</span><c:if test="${not empty doc.rejectionReason}"><br/><small class="text-danger"><i class="bi bi-info-circle"></i> <c:out value="${doc.rejectionReason}" /></small></c:if></c:when>
                                    <c:otherwise><span class="fv-badge fv-badge-pending">Pending</span></c:otherwise>
                                </c:choose>
                            </td>
                            <td><small><c:out value="${doc.createdBy}" /><br/><c:out value="${doc.createdAt}" /></small></td>
                            <td><c:if test="${not empty doc.verifiedBy}"><small><c:out value="${doc.verifiedBy}" /><br/><c:out value="${doc.verifiedDate}" /></small></c:if><c:if test="${empty doc.verifiedBy}"><small class="text-muted">--</small></c:if></td>
                            <td>
                                <%-- CBS: Action buttons with icon + text per Finacle DOC_MASTER / CBS UX standards.
                                     Per Finacle/Temenos: all action buttons must have icon + text label for
                                     clarity — icon-only buttons are ambiguous for branch operations staff. --%>
                                <button type="button" class="btn btn-sm btn-outline-primary me-1" title="Preview document" onclick="previewDoc('${pageContext.request.contextPath}/customer/document/download/${doc.id}', '${doc.contentType}', '${doc.documentType.displayName}');"><i class="bi bi-eye"></i> Preview</button>
                                <a href="${pageContext.request.contextPath}/customer/document/download/${doc.id}" target="_blank" class="btn btn-sm btn-outline-secondary me-1" title="Open in new tab"><i class="bi bi-box-arrow-up-right"></i> Open</a>
                                <c:if test="${doc.verificationStatus == 'UPLOADED'}">
                                <c:if test="${pageContext.request.isUserInRole('ROLE_CHECKER') || pageContext.request.isUserInRole('ROLE_ADMIN')}">
                                    <form method="post" action="${pageContext.request.contextPath}/customer/document/verify/${doc.id}" class="d-inline">
                                        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                                        <input type="hidden" name="action" value="VERIFY" />
                                        <button type="submit" class="btn btn-sm btn-success me-1" title="Verify document" data-confirm="Verify this document?"><i class="bi bi-patch-check"></i> Verify</button>
                                    </form>
                                    <form method="post" action="${pageContext.request.contextPath}/customer/document/verify/${doc.id}" class="d-inline">
                                        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                                        <input type="hidden" name="action" value="REJECT" />
                                        <input type="hidden" name="rejectionReason" value="" id="rejReason_${doc.id}" />
                                        <button type="submit" class="btn btn-sm btn-danger" title="Reject document"
                                            onclick="var r=prompt('Rejection reason (mandatory):'); if(!r||r.trim().length<3){alert('Reason is mandatory');return false;} document.getElementById('rejReason_${doc.id}').value=r; return confirm('Reject this document?');">
                                            <i class="bi bi-x-circle"></i> Reject</button>
                                    </form>
                                </c:if>
                                </c:if>
                            </td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty documents}">
                        <tr><td colspan="8" class="text-center text-muted">No documents uploaded</td></tr>
                    </c:if>
                </tbody>
            </table>
            </div>
        </div>
    </div>

    <%-- CBS: Inline Document Preview Modal per Finacle DOC_MASTER / Temenos IM.DOCUMENT.IMAGE.
         Per CBS standards: CHECKER must preview the document on the SAME SCREEN where they
         verify/reject — no context-switching to a separate tab. This modal renders:
         - PDF documents via <iframe> (browser's built-in PDF viewer)
         - Image documents (JPG/PNG) via <img> with zoom capability
         Per RBI KYC audit: document preview access is already logged via the download endpoint. --%>
    <div class="modal fade" id="docPreviewModal" tabindex="-1" aria-labelledby="docPreviewLabel" aria-hidden="true">
        <div class="modal-dialog modal-xl modal-dialog-centered">
            <div class="modal-content">
                <div class="modal-header">
                    <h6 class="modal-title" id="docPreviewLabel"><i class="bi bi-file-earmark-text"></i> Document Preview</h6>
                    <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close"></button>
                </div>
                <div class="modal-body p-0" style="min-height:500px;">
                    <div id="docPreviewContent" class="text-center" style="min-height:500px;"></div>
                </div>
                <div class="modal-footer">
                    <a id="docPreviewOpenTab" href="#" target="_blank" class="btn btn-sm btn-outline-primary"><i class="bi bi-box-arrow-up-right"></i> Open in New Tab</a>
                    <button type="button" class="btn btn-sm btn-outline-secondary" data-bs-dismiss="modal">Close</button>
                </div>
            </div>
        </div>
    </div>

    <!-- CBS Actions: Apply for Loan + Deactivate -->
    <div class="fv-card">
        <div class="card-header">Actions</div>
        <div class="card-body">
            <c:if test="${customer.active and customer.kycVerified}">
                <c:if test="${pageContext.request.isUserInRole('ROLE_MAKER') || pageContext.request.isUserInRole('ROLE_ADMIN')}">
                <a href="${pageContext.request.contextPath}/loan/apply?customerId=${customer.id}" class="btn btn-sm btn-fv-primary me-1"><i class="bi bi-plus-circle"></i> Apply for Loan</a>
                </c:if>
            </c:if>
            <c:if test="${customer.active}">
                <c:if test="${pageContext.request.isUserInRole('ROLE_ADMIN')}">
                <form method="post" action="${pageContext.request.contextPath}/customer/deactivate/${customer.id}" class="d-inline">
                    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                    <button type="submit" class="btn btn-sm btn-danger" data-confirm="Deactivate this customer? This action cannot be undone."><i class="bi bi-person-x"></i> Deactivate Customer</button>
                </form>
                </c:if>
            </c:if>
            <c:if test="${not customer.active}">
                <span class="fv-badge fv-badge-rejected">Customer is Inactive</span>
            </c:if>
        </div>
    </div>
</div>

<%-- CBS: Document Preview JavaScript per Finacle DOC_MASTER / Temenos IM.DOCUMENT.IMAGE.
     Renders uploaded KYC documents inline within a Bootstrap modal:
     - PDF: embedded via <iframe> using browser's native PDF viewer
     - JPG/PNG: rendered via <img> with responsive sizing
     Per CBS maker-checker workflow: CHECKER previews the document on the same screen
     where verify/reject buttons are available — no context-switching required.
     Per RBI KYC audit: every preview triggers the download endpoint which is already
     audited via CustomerDocumentServiceImpl.getDocument() branch access enforcement. --%>
<script>
function previewDoc(url, contentType, docTitle) {
    var container = document.getElementById('docPreviewContent');
    var label = document.getElementById('docPreviewLabel');
    var openTabLink = document.getElementById('docPreviewOpenTab');

    label.innerHTML = '<i class="bi bi-file-earmark-text"></i> ' + docTitle;
    openTabLink.href = url;

    if (contentType === 'application/pdf') {
        container.innerHTML = '<iframe src="' + url + '" style="width:100%; height:600px; border:none;"></iframe>';
    } else if (contentType === 'image/jpeg' || contentType === 'image/png') {
        container.innerHTML = '<img src="' + url + '" alt="' + docTitle + '" style="max-width:100%; max-height:600px; padding:16px;" />';
    } else {
        container.innerHTML = '<div class="p-5 text-muted"><i class="bi bi-file-earmark-x" style="font-size:3rem;"></i><br/>Preview not available for this file type.<br/>Please open in a new tab.</div>';
    }

    var modal = new bootstrap.Modal(document.getElementById('docPreviewModal'));
    modal.show();
}
</script>

<%@ include file="../layout/footer.jsp" %>
