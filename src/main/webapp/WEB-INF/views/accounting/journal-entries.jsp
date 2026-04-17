<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Journal Entries" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <ul class="fv-breadcrumb">
        <li><a href="${pageContext.request.contextPath}/dashboard"><i class="bi bi-speedometer2"></i> Home</a></li>
        <li class="active">Journal Entries</li>
    </ul>

    <div class="fv-card">
        <div class="card-header"><i class="bi bi-journal-text"></i> Filter</div>
        <div class="card-body">
            <form method="get" action="${pageContext.request.contextPath}/accounting/journal-entries" class="fv-form">
                <div class="row g-2 align-items-end">
                    <div class="col-auto">
                        <label class="form-label">From Date</label>
                        <input type="date" name="fromDate" class="form-control" value="${fromDate}" />
                    </div>
                    <div class="col-auto">
                        <label class="form-label">To Date</label>
                        <input type="date" name="toDate" class="form-control" value="${toDate}" />
                    </div>
                    <div class="col-auto">
                        <label class="form-label">&nbsp;</label>
                        <button type="submit" class="btn btn-fv-primary d-block"><i class="bi bi-funnel"></i> Filter</button>
                    </div>
                </div>
            </form>
            <!-- CBS: Journal Entry search per Finacle JRNL_INQUIRY -->
            <form method="get" action="${pageContext.request.contextPath}/accounting/journal-entries/search" class="row g-2 mt-2">
                <div class="col-auto">
                    <input type="text" name="q" class="form-control form-control-sm" placeholder="Search by journal ref, narration, source module, source ref, branch..." value="<c:out value='${searchQuery}'/>" minlength="2" style="width:400px;" />
                </div>
                <div class="col-auto">
                    <button type="submit" class="btn btn-sm btn-fv-primary"><i class="bi bi-search"></i> Search</button>
                </div>
                <c:if test="${not empty searchQuery}">
                <div class="col-auto">
                    <a href="${pageContext.request.contextPath}/accounting/journal-entries" class="btn btn-sm btn-outline-secondary">Clear</a>
                </div>
                </c:if>
            </form>
        </div>
    </div>

    <div class="fv-card">
        <div class="card-header">Journal Entries</div>
        <div class="card-body">
            <div class="table-responsive">
            <table class="table fv-table fv-datatable">
                <thead>
                    <tr>
                        <th>Journal Ref</th>
                        <th>Value Date</th>
                        <th>Narration</th>
                        <th>Source</th>
                        <th class="text-end">Total Debit</th>
                        <th class="text-end">Total Credit</th>
                        <th>Posted</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="entry" items="${entries}">
                        <tr>
                            <td><c:out value="${entry.journalRef}" /></td>
                            <td><c:out value="${entry.valueDate}" /></td>
                            <td><c:out value="${entry.narration}" /></td>
                            <td><c:out value="${entry.sourceModule}" /> / <c:out value="${entry.sourceRef}" /></td>
                            <td class="amount"><fmt:formatNumber value="${entry.totalDebit}" type="number" maxFractionDigits="2" /></td>
                            <td class="amount"><fmt:formatNumber value="${entry.totalCredit}" type="number" maxFractionDigits="2" /></td>
                            <td>
                                <c:choose>
                                    <c:when test="${entry.posted}"><span class="fv-badge fv-badge-active">Yes</span></c:when>
                                    <c:otherwise><span class="fv-badge fv-badge-pending">No</span></c:otherwise>
                                </c:choose>
                            </td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty entries}">
                        <tr><td colspan="7" class="text-center text-muted">No journal entries found</td></tr>
                    </c:if>
                </tbody>
                <%-- CBS Tier-1: tfoot totals row (Debit / Credit aggregate) per Finacle JRNL_INQUIRY
                     standard. Uses `.amount` class so digits line up with tbody via monospace. --%>
                <c:if test="${not empty totalDebit or not empty totalCredit}">
                <tfoot>
                    <tr class="fw-bold" style="background:#f8f9fa;border-top:2px solid var(--fv-primary);">
                        <td colspan="4" class="text-end">Totals:</td>
                        <td class="amount"><fmt:formatNumber value="${totalDebit}" type="number" maxFractionDigits="2" /></td>
                        <td class="amount"><fmt:formatNumber value="${totalCredit}" type="number" maxFractionDigits="2" /></td>
                        <td></td>
                    </tr>
                </tfoot>
                </c:if>
            </table>
            </div>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
