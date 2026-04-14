<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Customer Details" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <c:if test="${not empty success}">
        <div class="fv-alert alert alert-success"><c:out value="${success}" /></div>
    </c:if>
    <c:if test="${not empty error}">
        <div class="fv-alert alert alert-danger"><c:out value="${error}" /></div>
    </c:if>

    <div class="fv-card">
        <div class="card-header">Customer Details
            <div class="float-end">
                <a href="${pageContext.request.contextPath}/customer/list" class="btn btn-sm btn-outline-secondary"><i class="bi bi-arrow-left"></i> Back</a>
                <c:if test="${pageContext.request.isUserInRole('ROLE_MAKER') || pageContext.request.isUserInRole('ROLE_ADMIN')}">
                <a href="${pageContext.request.contextPath}/customer/edit/${customer.id}" class="btn btn-sm btn-fv-primary"><i class="bi bi-pencil"></i> Edit</a>
                </c:if>
            </div>
        </div>
        <div class="card-body">
            <table class="table fv-table">
                <tbody>
                <tr><td class="fw-bold">Customer Number</td><td><c:out value="${customer.customerNumber}" /></td></tr>
                <tr><td class="fw-bold">Full Name</td><td><c:out value="${customer.fullName}" /></td></tr>
                <tr><td class="fw-bold">Customer Type</td><td><c:out value="${customer.customerType}" /></td></tr>
                <tr><td class="fw-bold">Gender</td><td><c:choose><c:when test="${customer.gender == 'M'}">Male</c:when><c:when test="${customer.gender == 'F'}">Female</c:when><c:when test="${customer.gender == 'T'}">Transgender</c:when><c:otherwise>--</c:otherwise></c:choose></td></tr>
                <tr><td class="fw-bold">Date of Birth</td><td><c:out value="${customer.dateOfBirth}" /></td></tr>
                <tr><td class="fw-bold">Marital Status</td><td><c:out value="${customer.maritalStatus}" default="--" /></td></tr>
                <tr><td class="fw-bold">Father's Name</td><td><c:out value="${customer.fatherName}" default="--" /></td></tr>
                <tr><td class="fw-bold">Mother's Name</td><td><c:out value="${customer.motherName}" default="--" /></td></tr>
                <c:if test="${not empty customer.spouseName}"><tr><td class="fw-bold">Spouse Name</td><td><c:out value="${customer.spouseName}" /></td></tr></c:if>
                <tr><td class="fw-bold">Nationality</td><td><c:out value="${customer.nationality}" default="INDIAN" /></td></tr>
                <tr><td class="fw-bold">Occupation</td><td><c:out value="${customer.occupationCode}" default="--" /></td></tr>
                <%-- CBS: PII masking per RBI IT Governance Direction 2023 / UIDAI Aadhaar Act 2016.
                     PAN/Aadhaar/Mobile are masked in display — only last 4 digits visible.
                     Full values are available in DB (encrypted) for authorized operations. --%>
                <tr><td class="fw-bold">PAN</td><td><c:out value="${maskedPan}" /></td></tr>
                <tr><td class="fw-bold">Aadhaar</td><td><c:out value="${maskedAadhaar}" /></td></tr>
                <tr><td class="fw-bold">Mobile</td><td><c:out value="${maskedMobile}" /></td></tr>
                <tr><td class="fw-bold">Email</td><td><c:out value="${customer.email}" /></td></tr>
                <tr><td class="fw-bold">Address</td><td><c:out value="${customer.address}" />, <c:out value="${customer.city}" />, <c:out value="${customer.state}" /> - <c:out value="${customer.pinCode}" /></td></tr>
                <tr><td class="fw-bold">KYC Status</td><td>
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
                </td></tr>
                <%-- CBS Sprint 1.2: KYC Risk, Expiry, PEP, and Customer Group fields --%>
                <tr><td class="fw-bold">KYC Risk Category</td><td>
                    <c:choose>
                        <c:when test="${customer.kycRiskCategory == 'HIGH'}"><span class="fv-badge fv-badge-npa">HIGH</span></c:when>
                        <c:when test="${customer.kycRiskCategory == 'LOW'}"><span class="fv-badge fv-badge-active">LOW</span></c:when>
                        <c:otherwise><span class="fv-badge fv-badge-pending">MEDIUM</span></c:otherwise>
                    </c:choose>
                </td></tr>
                <tr><td class="fw-bold">KYC Expiry Date</td><td>
                    <c:choose>
                        <c:when test="${customer.kycExpired}"><span class="fv-badge fv-badge-npa">EXPIRED</span> <c:out value="${customer.kycExpiryDate}" /></c:when>
                        <c:when test="${customer.kycExpiringSoon}"><span class="fv-badge fv-badge-pending">Expiring Soon</span> <c:out value="${customer.kycExpiryDate}" /></c:when>
                        <c:when test="${not empty customer.kycExpiryDate}"><c:out value="${customer.kycExpiryDate}" /></c:when>
                        <c:otherwise>--</c:otherwise>
                    </c:choose>
                </td></tr>
                <c:if test="${customer.rekycDue}">
                <tr><td class="fw-bold">Re-KYC Status</td><td><span class="fv-badge fv-badge-npa"><i class="bi bi-exclamation-triangle"></i> RE-KYC DUE</span></td></tr>
                </c:if>
                <tr><td class="fw-bold">PEP (Politically Exposed)</td><td>
                    <c:choose>
                        <c:when test="${customer.pep}"><span class="fv-badge fv-badge-npa">YES - Enhanced Due Diligence Required</span></c:when>
                        <c:otherwise>No</c:otherwise>
                    </c:choose>
                </td></tr>
                <c:if test="${not empty customer.customerGroupName}">
                <tr><td class="fw-bold">Customer Group</td><td><c:out value="${customer.customerGroupName}" /> (ID: <c:out value="${customer.customerGroupId}" />)</td></tr>
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
                <tr><td class="fw-bold">KYC Mode</td><td><c:out value="${customer.kycMode}" default="--" /></td></tr>
                <c:if test="${not empty customer.photoIdType}"><tr><td class="fw-bold">Photo ID</td><td><c:out value="${customer.photoIdType}" /></td></tr></c:if>
                <c:if test="${not empty customer.addressProofType}"><tr><td class="fw-bold">Address Proof</td><td><c:out value="${customer.addressProofType}" /></td></tr></c:if>
                <c:if test="${customer.videoKycDone}"><tr><td class="fw-bold">Video KYC</td><td><span class="fv-badge fv-badge-active">Completed</span></td></tr></c:if>
                <tr><td class="fw-bold">Annual Income Band</td><td><c:out value="${customer.annualIncomeBand}" default="--" /></td></tr>
                <tr><td class="fw-bold">CIBIL Score</td><td><c:out value="${customer.cibilScore}" /></td></tr>
                <tr><td class="fw-bold">Branch</td><td><a href="${pageContext.request.contextPath}/branch/view/${customer.branch.id}"><c:out value="${customer.branch.branchCode}" /> - <c:out value="${customer.branch.branchName}" /></a></td></tr>
                <tr><td class="fw-bold">Monthly Income</td><td class="amount"><c:if test="${customer.monthlyIncome != null}"><fmt:formatNumber value="${customer.monthlyIncome}" type="number" maxFractionDigits="2" /> INR</c:if><c:if test="${customer.monthlyIncome == null}">--</c:if></td></tr>
                <tr><td class="fw-bold">Max Borrowing Limit</td><td class="amount"><c:if test="${customer.maxBorrowingLimit != null}"><fmt:formatNumber value="${customer.maxBorrowingLimit}" type="number" maxFractionDigits="2" /> INR</c:if><c:if test="${customer.maxBorrowingLimit == null}">--</c:if></td></tr>
                <tr><td class="fw-bold">Employment</td><td><c:out value="${customer.employmentType}" default="--" /><c:if test="${not empty customer.employerName}"> &mdash; <c:out value="${customer.employerName}" /></c:if></td></tr>
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

<%@ include file="../layout/footer.jsp" %>
