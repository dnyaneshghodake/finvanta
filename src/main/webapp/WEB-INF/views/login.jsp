<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Finvanta CBS - Login</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background: linear-gradient(135deg, #0d1b3e 0%, #1a237e 100%); min-height: 100vh; display: flex; align-items: center; justify-content: center; }
        .login-card { background: white; border-radius: 12px; padding: 40px; width: 400px; box-shadow: 0 20px 60px rgba(0,0,0,0.3); }
        .login-card h1 { text-align: center; color: #1a237e; font-size: 28px; margin-bottom: 8px; }
        .login-card .subtitle { text-align: center; color: #777; font-size: 13px; margin-bottom: 32px; }
        .form-group { margin-bottom: 20px; }
        .form-group label { display: block; margin-bottom: 6px; font-weight: 500; color: #555; font-size: 14px; }
        .form-group input { width: 100%; padding: 12px; border: 1px solid #ddd; border-radius: 6px; font-size: 14px; }
        .form-group input:focus { border-color: #1a237e; outline: none; box-shadow: 0 0 0 3px rgba(26,35,126,0.1); }
        .btn-login { width: 100%; padding: 12px; background: #1a237e; color: white; border: none; border-radius: 6px; font-size: 16px; font-weight: 600; cursor: pointer; }
        .btn-login:hover { background: #283593; }
        .error-msg { background: #ffebee; color: #c62828; padding: 10px; border-radius: 4px; margin-bottom: 16px; font-size: 13px; text-align: center; }
        .logout-msg { background: #e8f5e9; color: #2e7d32; padding: 10px; border-radius: 4px; margin-bottom: 16px; font-size: 13px; text-align: center; }
    </style>
</head>
<body>
    <div class="login-card">
        <h1>FINVANTA</h1>
        <p class="subtitle">Core Banking System - RBI Compliant</p>

        <c:if test="${param.error != null}">
            <div class="error-msg">Invalid username or password</div>
        </c:if>
        <c:if test="${param.logout != null}">
            <div class="logout-msg">You have been logged out successfully</div>
        </c:if>

        <form method="post" action="${pageContext.request.contextPath}/login">
            <div class="form-group">
                <label for="username">Username</label>
                <input type="text" id="username" name="username" required autofocus placeholder="Enter your username" />
            </div>
            <div class="form-group">
                <label for="password">Password</label>
                <input type="password" id="password" name="password" required placeholder="Enter your password" />
            </div>
            <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
            <button type="submit" class="btn-login">Sign In</button>
        </form>
        <p style="text-align: center; margin-top: 24px; font-size: 11px; color: #999;">
            Finvanta Systems Pvt Ltd &copy; 2026
        </p>
    </div>
</body>
</html>
