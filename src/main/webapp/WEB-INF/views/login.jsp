<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta name="robots" content="noindex, nofollow">
    <title>Finvanta CBS - Login</title>
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/bootstrap.min.css">
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/finvanta-theme.css">
</head>
<body class="fv-login-wrapper">
    <div class="fv-login-card">
        <h1>FINVANTA</h1>
        <p class="subtitle">Core Banking System &mdash; RBI Compliant</p>

        <c:if test="${not empty error}">
            <div class="fv-alert alert alert-danger" role="alert"><c:out value="${error}" /></div>
        </c:if>
        <c:if test="${param.error != null && empty error}">
            <div class="fv-alert alert alert-danger" role="alert">Invalid username or password. Please check your credentials and try again.</div>
        </c:if>
        <c:if test="${param.expired != null}">
            <div class="fv-alert alert alert-warning" role="alert">Your password has expired. Please contact your administrator or login to change it.</div>
        </c:if>
        <c:if test="${param.logout != null}">
            <div class="fv-alert alert alert-success" role="alert">You have been logged out successfully.</div>
        </c:if>

        <form method="post" action="${pageContext.request.contextPath}/login" class="fv-form">
            <div class="mb-3">
                <label for="username" class="form-label">Username</label>
                <input type="text" class="form-control" id="username" name="username" required autofocus placeholder="Enter your username" />
            </div>
            <div class="mb-3">
                <label for="password" class="form-label">Password</label>
                <input type="password" class="form-control" id="password" name="password" required placeholder="Enter your password" />
            </div>
            <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
            <button type="submit" class="btn btn-fv-primary w-100 py-2">Sign In</button>
        </form>
        <p class="text-center mt-4" style="font-size:11px;color:#90a4ae;">
            Finvanta Systems Pvt Ltd &copy; 2026
        </p>
    </div>
</body>
</html>
