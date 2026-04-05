<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<nav class="sidebar" style="width: 250px; background: #0d1b3e; color: white; position: fixed; top: 0; left: 0; bottom: 0; overflow-y: auto; z-index: 200;">
    <div style="padding: 20px; text-align: center; border-bottom: 1px solid #1a2d5e;">
        <h1 style="font-size: 22px; font-weight: 700; color: #64b5f6;">FINVANTA</h1>
        <p style="font-size: 11px; color: #90a4ae; margin-top: 4px;">Core Banking System</p>
    </div>
    <ul style="list-style: none; padding: 12px 0;">
        <li style="padding: 0;">
            <a href="${pageContext.request.contextPath}/dashboard"
               style="display: block; padding: 12px 24px; color: #b0bec5; text-decoration: none; font-size: 14px; transition: all 0.2s;"
               onmouseover="this.style.background='#1a2d5e';this.style.color='white'"
               onmouseout="this.style.background='';this.style.color='#b0bec5'">
                Dashboard
            </a>
        </li>
        <li style="padding: 8px 24px 4px; font-size: 11px; color: #546e7a; text-transform: uppercase; letter-spacing: 1px;">Loan Origination</li>
        <li>
            <a href="${pageContext.request.contextPath}/loan/apply"
               style="display: block; padding: 10px 24px 10px 36px; color: #b0bec5; text-decoration: none; font-size: 13px;"
               onmouseover="this.style.background='#1a2d5e';this.style.color='white'"
               onmouseout="this.style.background='';this.style.color='#b0bec5'">
                New Application
            </a>
        </li>
        <li>
            <a href="${pageContext.request.contextPath}/loan/applications"
               style="display: block; padding: 10px 24px 10px 36px; color: #b0bec5; text-decoration: none; font-size: 13px;"
               onmouseover="this.style.background='#1a2d5e';this.style.color='white'"
               onmouseout="this.style.background='';this.style.color='#b0bec5'">
                Applications
            </a>
        </li>
        <li style="padding: 8px 24px 4px; font-size: 11px; color: #546e7a; text-transform: uppercase; letter-spacing: 1px;">Loan Accounts</li>
        <li>
            <a href="${pageContext.request.contextPath}/loan/accounts"
               style="display: block; padding: 10px 24px 10px 36px; color: #b0bec5; text-decoration: none; font-size: 13px;"
               onmouseover="this.style.background='#1a2d5e';this.style.color='white'"
               onmouseout="this.style.background='';this.style.color='#b0bec5'">
                Active Accounts
            </a>
        </li>
        <li style="padding: 8px 24px 4px; font-size: 11px; color: #546e7a; text-transform: uppercase; letter-spacing: 1px;">Accounting</li>
        <li>
            <a href="${pageContext.request.contextPath}/accounting/trial-balance"
               style="display: block; padding: 10px 24px 10px 36px; color: #b0bec5; text-decoration: none; font-size: 13px;"
               onmouseover="this.style.background='#1a2d5e';this.style.color='white'"
               onmouseout="this.style.background='';this.style.color='#b0bec5'">
                Trial Balance
            </a>
        </li>
        <li>
            <a href="${pageContext.request.contextPath}/accounting/journal-entries"
               style="display: block; padding: 10px 24px 10px 36px; color: #b0bec5; text-decoration: none; font-size: 13px;"
               onmouseover="this.style.background='#1a2d5e';this.style.color='white'"
               onmouseout="this.style.background='';this.style.color='#b0bec5'">
                Journal Entries
            </a>
        </li>
        <li style="padding: 8px 24px 4px; font-size: 11px; color: #546e7a; text-transform: uppercase; letter-spacing: 1px;">EOD / Batch</li>
        <li>
            <a href="${pageContext.request.contextPath}/batch/eod"
               style="display: block; padding: 10px 24px 10px 36px; color: #b0bec5; text-decoration: none; font-size: 13px;"
               onmouseover="this.style.background='#1a2d5e';this.style.color='white'"
               onmouseout="this.style.background='';this.style.color='#b0bec5'">
                EOD Processing
            </a>
        </li>
        <li style="padding: 8px 24px 4px; font-size: 11px; color: #546e7a; text-transform: uppercase; letter-spacing: 1px;">Workflow</li>
        <li>
            <a href="${pageContext.request.contextPath}/workflow/pending"
               style="display: block; padding: 10px 24px 10px 36px; color: #b0bec5; text-decoration: none; font-size: 13px;"
               onmouseover="this.style.background='#1a2d5e';this.style.color='white'"
               onmouseout="this.style.background='';this.style.color='#b0bec5'">
                Pending Approvals
            </a>
        </li>
        <li style="padding: 8px 24px 4px; font-size: 11px; color: #546e7a; text-transform: uppercase; letter-spacing: 1px;">Admin</li>
        <li>
            <a href="${pageContext.request.contextPath}/customer/list"
               style="display: block; padding: 10px 24px 10px 36px; color: #b0bec5; text-decoration: none; font-size: 13px;"
               onmouseover="this.style.background='#1a2d5e';this.style.color='white'"
               onmouseout="this.style.background='';this.style.color='#b0bec5'">
                Customers
            </a>
        </li>
        <li>
            <a href="${pageContext.request.contextPath}/branch/list"
               style="display: block; padding: 10px 24px 10px 36px; color: #b0bec5; text-decoration: none; font-size: 13px;"
               onmouseover="this.style.background='#1a2d5e';this.style.color='white'"
               onmouseout="this.style.background='';this.style.color='#b0bec5'">
                Branches
            </a>
        </li>
    </ul>
</nav>
