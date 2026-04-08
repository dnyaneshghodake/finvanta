<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Business Calendar &amp; Day Control" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <c:if test="${not empty success}">
        <div class="fv-alert alert alert-success"><c:out value="${success}" /></div>
    </c:if>
    <c:if test="${not empty error}">
        <div class="fv-alert alert alert-danger"><c:out value="${error}" /></div>
    </c:if>

    <!-- Current Open Day Indicator -->
    <div class="fv-card">
        <div class="card-header">Current Business Day</div>
        <div class="card-body">
            <c:choose>
                <c:when test="${not empty openDay}">
                    <div class="fv-stat-card stat-success" style="display:inline-block;padding:12px 24px;">
                        <div class="stat-value"><c:out value="${openDay.businessDate}" /></div>
                        <div class="stat-label">DAY OPEN</div>
                    </div>
                </c:when>
                <c:otherwise>
                    <div class="fv-stat-card stat-danger" style="display:inline-block;padding:12px 24px;">
                        <div class="stat-value">NO DAY OPEN</div>
                        <div class="stat-label">Open a day to allow transactions</div>
                    </div>
                </c:otherwise>
            </c:choose>
        </div>
    </div>

    <!-- Calendar Table with Day Control -->
    <div class="fv-card">
        <div class="card-header">Business Calendar &mdash; Day Lifecycle</div>
        <div class="card-body">
            <div class="table-responsive">
            <table class="table fv-table fv-datatable">
                <thead>
                    <tr>
                        <th>Business Date</th>
                        <th>Holiday</th>
                        <th>Holiday Type</th>
                        <th>Day Status</th>
                        <th>EOD</th>
                        <th>Opened By</th>
                        <th>Closed By</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="cal" items="${calendarDates}">
                        <tr>
                            <td class="fw-bold"><c:out value="${cal.businessDate}" /></td>
                            <td>
                                <c:choose>
                                    <c:when test="${cal.holiday}"><span class="fv-badge fv-badge-rejected">Holiday</span></c:when>
                                    <c:otherwise><span class="fv-badge fv-badge-active">Working</span></c:otherwise>
                                </c:choose>
                            </td>
                            <td>
                                <c:if test="${not empty cal.holidayType}">
                                    <c:out value="${cal.holidayType}" />
                                    <c:if test="${not empty cal.holidayRegion}"> <small class="text-muted">(<c:out value="${cal.holidayRegion}" />)</small></c:if>
                                </c:if>
                                <c:if test="${empty cal.holidayType}">--</c:if>
                            </td>
                            <td>
                                <c:choose>
                                    <c:when test="${cal.dayStatus == 'DAY_OPEN'}"><span class="fv-badge fv-badge-approved">DAY OPEN</span></c:when>
                                    <c:when test="${cal.dayStatus == 'EOD_RUNNING'}"><span class="fv-badge fv-badge-pending">EOD RUNNING</span></c:when>
                                    <c:when test="${cal.dayStatus == 'DAY_CLOSED'}"><span class="fv-badge fv-badge-active">DAY CLOSED</span></c:when>
                                    <c:otherwise><span class="fv-badge">NOT OPENED</span></c:otherwise>
                                </c:choose>
                            </td>
                            <td>
                                <c:choose>
                                    <c:when test="${cal.eodComplete}"><span class="fv-badge fv-badge-active">Complete</span></c:when>
                                    <c:otherwise>--</c:otherwise>
                                </c:choose>
                            </td>
                            <td><c:out value="${cal.dayOpenedBy}" default="--" /></td>
                            <td><c:out value="${cal.dayClosedBy}" default="--" /></td>
                            <td>
                                <c:if test="${cal.dayStatus == 'NOT_OPENED' and not cal.holiday}">
                                    <form method="post" action="${pageContext.request.contextPath}/calendar/day-open" class="d-inline">
                                        <input type="hidden" name="businessDate" value="${cal.businessDate}" />
                                        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                                        <button type="submit" class="btn btn-sm btn-success" data-confirm="Open business day ${cal.businessDate}?">Open Day</button>
                                    </form>
                                </c:if>
                                <c:if test="${cal.dayStatus == 'DAY_OPEN' and cal.eodComplete}">
                                    <form method="post" action="${pageContext.request.contextPath}/calendar/day-close" class="d-inline">
                                        <input type="hidden" name="businessDate" value="${cal.businessDate}" />
                                        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                                        <button type="submit" class="btn btn-sm btn-danger" data-confirm="Close business day ${cal.businessDate}? This is irreversible.">Close Day</button>
                                    </form>
                                </c:if>
                            </td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty calendarDates}">
                        <tr><td colspan="8" class="text-center text-muted">No calendar dates configured</td></tr>
                    </c:if>
                </tbody>
            </table>
            </div>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>