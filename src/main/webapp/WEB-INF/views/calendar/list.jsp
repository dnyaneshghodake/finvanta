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

    <!-- Current Open Day Indicator (branch-scoped per Tier-1 CBS) -->
    <div class="fv-card">
        <div class="card-header">Current Business Day <c:if test="${not empty currentBranchCode}"><span class="fv-badge fv-badge-approved">Branch: <c:out value="${currentBranchCode}" /></span></c:if></div>
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

    <!-- Calendar Generation + Holiday Management -->
    <div class="row mb-3">
        <div class="col-md-6">
            <div class="fv-card">
                <div class="card-header"><i class="bi bi-calendar-plus"></i> Generate Calendar</div>
                <div class="card-body">
                    <p class="text-muted small">Per Finacle DAYCTRL: generates one entry per date per operational branch. Weekends auto-marked as holidays. Idempotent &mdash; safe to re-run.</p>
                    <form method="post" action="${pageContext.request.contextPath}/calendar/generate" class="row g-2 align-items-end">
                        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                        <div class="col-auto">
                            <label class="form-label">Year</label>
                            <input type="number" name="year" class="form-control" value="2026" min="2024" max="2030" required style="width:100px;" />
                        </div>
                        <div class="col-auto">
                            <label class="form-label">Month</label>
                            <select name="month" class="form-select" required style="width:130px;">
                                <option value="1">January</option><option value="2">February</option>
                                <option value="3">March</option><option value="4" selected>April</option>
                                <option value="5">May</option><option value="6">June</option>
                                <option value="7">July</option><option value="8">August</option>
                                <option value="9">September</option><option value="10">October</option>
                                <option value="11">November</option><option value="12">December</option>
                            </select>
                        </div>
                        <div class="col-auto">
                            <button type="submit" class="btn btn-primary"><i class="bi bi-calendar-plus"></i> Generate</button>
                        </div>
                    </form>
                </div>
            </div>
        </div>
        <div class="col-md-6">
            <div class="fv-card">
                <div class="card-header"><i class="bi bi-calendar-x"></i> Add / Remove Holiday</div>
                <div class="card-body">
                    <p class="text-muted small">Per RBI NI Act: gazetted holidays must be configured. Cannot mark dates that are already DAY_OPEN or DAY_CLOSED.</p>
                    <form method="post" action="${pageContext.request.contextPath}/calendar/add-holiday" class="row g-2 align-items-end mb-2">
                        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                        <div class="col-auto">
                            <label class="form-label">Date</label>
                            <input type="date" name="date" class="form-control" required style="width:160px;" />
                        </div>
                        <div class="col">
                            <label class="form-label">Description</label>
                            <input type="text" name="description" class="form-control" placeholder="e.g., Independence Day" required />
                        </div>
                        <div class="col-auto">
                            <button type="submit" class="btn btn-warning"><i class="bi bi-calendar-x"></i> Add Holiday</button>
                        </div>
                    </form>
                    <form method="post" action="${pageContext.request.contextPath}/calendar/remove-holiday" class="row g-2 align-items-end">
                        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                        <div class="col-auto">
                            <label class="form-label">Date</label>
                            <input type="date" name="date" class="form-control" required style="width:160px;" />
                        </div>
                        <div class="col-auto">
                            <button type="submit" class="btn btn-outline-secondary"><i class="bi bi-calendar-check"></i> Remove Holiday</button>
                        </div>
                    </form>
                </div>
            </div>
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