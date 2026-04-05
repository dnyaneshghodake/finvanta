<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Finvanta CBS - ${pageTitle}</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background: #f4f6f9; color: #333; }
        .app-container { display: flex; min-height: 100vh; }
        .main-content { flex: 1; margin-left: 250px; padding: 20px; }
        .top-bar { background: #1a237e; color: white; padding: 12px 24px; display: flex; justify-content: space-between; align-items: center; position: fixed; top: 0; left: 250px; right: 0; z-index: 100; height: 56px; }
        .top-bar h2 { font-size: 16px; font-weight: 500; }
        .top-bar .user-info { font-size: 13px; }
        .top-bar .user-info a { color: #90caf9; text-decoration: none; margin-left: 16px; }
        .content-area { margin-top: 76px; }
        .card { background: white; border-radius: 8px; box-shadow: 0 1px 3px rgba(0,0,0,0.12); padding: 24px; margin-bottom: 20px; }
        .card h3 { color: #1a237e; margin-bottom: 16px; font-size: 18px; }
        table { width: 100%; border-collapse: collapse; }
        table th { background: #e8eaf6; color: #1a237e; padding: 10px 12px; text-align: left; font-size: 13px; font-weight: 600; }
        table td { padding: 10px 12px; border-bottom: 1px solid #eee; font-size: 13px; }
        table tr:hover { background: #f5f5f5; }
        .btn { padding: 8px 16px; border: none; border-radius: 4px; cursor: pointer; font-size: 13px; font-weight: 500; text-decoration: none; display: inline-block; }
        .btn-primary { background: #1a237e; color: white; }
        .btn-primary:hover { background: #283593; }
        .btn-success { background: #2e7d32; color: white; }
        .btn-success:hover { background: #388e3c; }
        .btn-danger { background: #c62828; color: white; }
        .btn-danger:hover { background: #d32f2f; }
        .btn-warning { background: #ef6c00; color: white; }
        .btn-sm { padding: 4px 10px; font-size: 12px; }
        .alert { padding: 12px 16px; border-radius: 4px; margin-bottom: 16px; font-size: 14px; }
        .alert-success { background: #e8f5e9; color: #2e7d32; border: 1px solid #c8e6c9; }
        .alert-error { background: #ffebee; color: #c62828; border: 1px solid #ffcdd2; }
        .form-group { margin-bottom: 16px; }
        .form-group label { display: block; margin-bottom: 6px; font-weight: 500; font-size: 13px; color: #555; }
        .form-group input, .form-group select, .form-group textarea { width: 100%; padding: 8px 12px; border: 1px solid #ddd; border-radius: 4px; font-size: 14px; }
        .form-group input:focus, .form-group select:focus { border-color: #1a237e; outline: none; box-shadow: 0 0 0 2px rgba(26,35,126,0.1); }
        .form-row { display: flex; gap: 16px; }
        .form-row .form-group { flex: 1; }
        .badge { padding: 3px 8px; border-radius: 12px; font-size: 11px; font-weight: 600; }
        .badge-active { background: #e8f5e9; color: #2e7d32; }
        .badge-pending { background: #fff3e0; color: #ef6c00; }
        .badge-approved { background: #e3f2fd; color: #1565c0; }
        .badge-rejected { background: #ffebee; color: #c62828; }
        .badge-npa { background: #fce4ec; color: #ad1457; }
        .stats-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px; margin-bottom: 20px; }
        .stat-card { background: white; border-radius: 8px; padding: 20px; box-shadow: 0 1px 3px rgba(0,0,0,0.12); text-align: center; }
        .stat-card .stat-value { font-size: 28px; font-weight: 700; color: #1a237e; }
        .stat-card .stat-label { font-size: 13px; color: #777; margin-top: 4px; }
        .amount { font-family: 'Courier New', monospace; font-weight: 600; }
        .text-right { text-align: right; }
        @media (max-width: 768px) {
            .sidebar { width: 60px; }
            .sidebar .nav-text { display: none; }
            .main-content { margin-left: 60px; }
            .top-bar { left: 60px; }
            .form-row { flex-direction: column; }
        }
    </style>
</head>
<body>
<div class="app-container">
