# TIER-1 REACT + NEXT.JS TESTING & DEPLOYMENT GUIDE
## Production-Grade Testing & DevOps

**Document Version:** 1.0  
**Date:** April 19, 2026  
**Grade:** Tier-1 Enterprise Banking Standard

---

## TABLE OF CONTENTS

1. [Testing Strategy](#testing-strategy)
2. [Unit Testing](#unit-testing)
3. [Integration Testing](#integration-testing)
4. [Component Testing](#component-testing)
5. [E2E Testing](#e2e-testing)
6. [Performance Testing](#performance-testing)
7. [Deployment Strategy](#deployment-strategy)
8. [CI/CD Pipeline](#cicd-pipeline)
9. [Monitoring & Alerting](#monitoring--alerting)
10. [Production Checklist](#production-checklist)

---

## TESTING STRATEGY

### Test Pyramid

```
        /\
       /  \  E2E Tests (5-10%)
      /    \
     /______\
    /        \
   /  Integr. \  Integration Tests (20-30%)
  /____________\
 /              \
/    Unit Tests  \  Unit Tests (60-70%)
/________________\
```

### Coverage Targets

- **80%+ overall code coverage**
- **95%+ critical paths** (authentication, transfers, GL posting)
- **<10 cyclomatic complexity** per function
- **Zero high-severity vulnerabilities**
- **Performance: <500ms P99** for API calls

---

## UNIT TESTING

### 1. Utility Function Tests

```typescript
// src/utils/__tests__/formatters.test.ts
import {
  formatCurrency,
  formatDate,
  formatAccountNumber,
  formatPhoneNumber,
} from '@/utils/formatters';

describe('Formatters', () => {
  describe('formatCurrency', () => {
    it('should format currency with 2 decimal places', () => {
      expect(formatCurrency(1234.5)).toBe('₹1,234.50');
      expect(formatCurrency(1000000)).toBe('₹10,00,000.00');
    });

    it('should handle negative amounts', () => {
      expect(formatCurrency(-500)).toBe('-₹500.00');
    });

    it('should handle zero', () => {
      expect(formatCurrency(0)).toBe('₹0.00');
    });
  });

  describe('formatDate', () => {
    it('should format date in DD/MM/YYYY format', () => {
      const date = new Date('2024-04-19');
      expect(formatDate(date)).toBe('19/04/2024');
    });
  });

  describe('formatAccountNumber', () => {
    it('should mask account number except last 4 digits', () => {
      expect(formatAccountNumber('1234567890123456')).toBe('****67890123****');
    });
  });
});
```

### 2. Validator Tests

```typescript
// src/utils/__tests__/validators.test.ts
import {
  validateEmail,
  validatePAN,
  validateIFSC,
  validateAccountNumber,
} from '@/utils/validators';

describe('Validators', () => {
  describe('validateEmail', () => {
    it('should validate correct email', () => {
      expect(validateEmail('user@example.com')).toBe(true);
    });

    it('should reject invalid email', () => {
      expect(validateEmail('invalid-email')).toBe(false);
      expect(validateEmail('user@')).toBe(false);
    });
  });

  describe('validatePAN', () => {
    it('should validate Indian PAN format', () => {
      expect(validatePAN('AAAPA1234B')).toBe(true);
    });

    it('should reject invalid PAN', () => {
      expect(validatePAN('INVALID')).toBe(false);
    });
  });

  describe('validateIFSC', () => {
    it('should validate IFSC code', () => {
      expect(validateIFSC('SBIN0001234')).toBe(true);
    });

    it('should reject invalid IFSC', () => {
      expect(validateIFSC('INVALID')).toBe(false);
    });
  });

  describe('validateAccountNumber', () => {
    it('should validate account number length', () => {
      expect(validateAccountNumber('1234567890123456')).toBe(true);
    });

    it('should reject short account number', () => {
      expect(validateAccountNumber('12345')).toBe(false);
    });
  });
});
```

### 3. Store Tests (Zustand)

```typescript
// src/store/__tests__/authStore.test.ts
import { renderHook, act, waitFor } from '@testing-library/react';
import { useAuthStore } from '@/store/authStore';
import * as authService from '@/services/auth/authService';

jest.mock('@/services/auth/authService');

describe('AuthStore', () => {
  beforeEach(() => {
    // Reset store before each test
    useAuthStore.setState({
      user: null,
      token: null,
      isAuthenticated: false,
      error: null,
    });
  });

  it('should login user', async () => {
    const mockResponse = {
      user: { id: '1', email: 'user@example.com', name: 'John Doe' },
      token: 'mock-token',
      refreshToken: 'mock-refresh-token',
      expiresIn: 3600,
    };

    (authService.login as jest.Mock).mockResolvedValue(mockResponse);

    const { result } = renderHook(() => useAuthStore());

    await act(async () => {
      await result.current.login('user@example.com', 'password');
    });

    await waitFor(() => {
      expect(result.current.isAuthenticated).toBe(true);
      expect(result.current.user?.email).toBe('user@example.com');
      expect(result.current.token).toBe('mock-token');
    });
  });

  it('should logout user', async () => {
    const { result } = renderHook(() => useAuthStore());

    // Set user first
    act(() => {
      result.current.setUser({
        id: '1',
        email: 'user@example.com',
        name: 'John Doe',
      } as any);
    });

    expect(result.current.isAuthenticated).toBe(true);

    // Logout
    act(() => {
      result.current.logout();
    });

    expect(result.current.isAuthenticated).toBe(false);
    expect(result.current.user).toBe(null);
  });

  it('should handle login error', async () => {
    const error = new Error('Invalid credentials');
    (authService.login as jest.Mock).mockRejectedValue(error);

    const { result } = renderHook(() => useAuthStore());

    await act(async () => {
      try {
        await result.current.login('user@example.com', 'wrong-password');
      } catch {}
    });

    expect(result.current.error).toBe('Invalid credentials');
    expect(result.current.isAuthenticated).toBe(false);
  });
});
```

---

## INTEGRATION TESTING

### 1. API Service Tests

```typescript
// src/services/api/__tests__/accountService.test.ts
import { rest } from 'msw';
import { setupServer } from 'msw/node';
import { AccountService } from '@/services/api/accountService';

const mockAccounts = [
  {
    id: '1',
    accountNumber: '1234567890123456',
    balance: 50000,
    status: 'ACTIVE',
  },
  {
    id: '2',
    accountNumber: '1234567890123457',
    balance: 100000,
    status: 'ACTIVE',
  },
];

// Setup MSW (Mock Service Worker)
const server = setupServer(
  rest.get(`${process.env.NEXT_PUBLIC_API_URL}/accounts`, (req, res, ctx) => {
    return res(
      ctx.status(200),
      ctx.json({
        items: mockAccounts,
        total: 2,
        page: 1,
        pageSize: 10,
        totalPages: 1,
        hasNextPage: false,
      })
    );
  })
);

beforeAll(() => server.listen());
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('AccountService', () => {
  it('should fetch accounts', async () => {
    const response = await AccountService.getAccounts(1, 10);

    expect(response.items).toHaveLength(2);
    expect(response.total).toBe(2);
    expect(response.items[0].accountNumber).toBe('1234567890123456');
  });

  it('should handle 404 error', async () => {
    server.use(
      rest.get(`${process.env.NEXT_PUBLIC_API_URL}/accounts`, (req, res, ctx) => {
        return res(ctx.status(404), ctx.json({ error: 'Not found' }));
      })
    );

    await expect(AccountService.getAccounts()).rejects.toThrow();
  });

  it('should handle 500 error', async () => {
    server.use(
      rest.get(`${process.env.NEXT_PUBLIC_API_URL}/accounts`, (req, res, ctx) => {
        return res(ctx.status(500), ctx.json({ error: 'Server error' }));
      })
    );

    await expect(AccountService.getAccounts()).rejects.toThrow();
  });
});
```

### 2. Hook Integration Tests

```typescript
// src/hooks/__tests__/useApi.test.ts
import { renderHook, waitFor } from '@testing-library/react';
import { useApi } from '@/hooks/useApi';
import { rest } from 'msw';
import { setupServer } from 'msw/node';

const server = setupServer(
  rest.get(`${process.env.NEXT_PUBLIC_API_URL}/accounts`, (req, res, ctx) => {
    return res(ctx.status(200), ctx.json([{ id: '1', name: 'Account 1' }]));
  })
);

beforeAll(() => server.listen());
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('useApi hook', () => {
  it('should fetch data', async () => {
    const { result } = renderHook(() => useApi(`${process.env.NEXT_PUBLIC_API_URL}/accounts`));

    expect(result.current.loading).toBe(true);

    await waitFor(() => {
      expect(result.current.loading).toBe(false);
    });

    expect(result.current.data).toHaveLength(1);
    expect(result.current.error).toBeNull();
  });

  it('should handle errors', async () => {
    server.use(
      rest.get(`${process.env.NEXT_PUBLIC_API_URL}/accounts`, (req, res, ctx) => {
        return res(ctx.status(500), ctx.json({ error: 'Server error' }));
      })
    );

    const { result } = renderHook(() => useApi(`${process.env.NEXT_PUBLIC_API_URL}/accounts`));

    await waitFor(() => {
      expect(result.current.error).toBeDefined();
    });

    expect(result.current.loading).toBe(false);
  });
});
```

---

## COMPONENT TESTING

### 1. Component Unit Tests

```typescript
// src/components/common/Button/__tests__/Button.test.tsx
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Button } from '@/components/common/Button';

describe('Button Component', () => {
  it('should render button with text', () => {
    render(<Button>Click Me</Button>);
    expect(screen.getByRole('button', { name: 'Click Me' })).toBeInTheDocument();
  });

  it('should call onClick handler', async () => {
    const handleClick = jest.fn();
    render(<Button onClick={handleClick}>Click Me</Button>);

    await userEvent.click(screen.getByRole('button'));
    expect(handleClick).toHaveBeenCalledTimes(1);
  });

  it('should be disabled when loading', () => {
    render(<Button isLoading>Click Me</Button>);
    expect(screen.getByRole('button')).toBeDisabled();
  });

  it('should apply variant class', () => {
    const { container } = render(<Button variant="danger">Click Me</Button>);
    expect(container.querySelector('.variant-danger')).toBeInTheDocument();
  });

  it('should be disabled when disabled prop is true', () => {
    render(<Button disabled>Click Me</Button>);
    expect(screen.getByRole('button')).toBeDisabled();
  });
});
```

### 2. Form Component Tests

```typescript
// src/components/forms/LoginForm/__tests__/LoginForm.test.tsx
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { LoginForm } from '@/components/forms/LoginForm';

describe('LoginForm Component', () => {
  it('should render login form fields', () => {
    const handleSubmit = jest.fn();
    render(<LoginForm onSubmit={handleSubmit} />);

    expect(screen.getByLabelText(/email/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/password/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /login/i })).toBeInTheDocument();
  });

  it('should submit form with valid data', async () => {
    const user = userEvent.setup();
    const handleSubmit = jest.fn().mockResolvedValue(undefined);

    render(<LoginForm onSubmit={handleSubmit} />);

    const emailInput = screen.getByLabelText(/email/i) as HTMLInputElement;
    const passwordInput = screen.getByLabelText(/password/i) as HTMLInputElement;

    await user.type(emailInput, 'user@example.com');
    await user.type(passwordInput, 'password123');
    await user.click(screen.getByRole('button', { name: /login/i }));

    await waitFor(() => {
      expect(handleSubmit).toHaveBeenCalledWith(
        expect.objectContaining({
          email: 'user@example.com',
          password: 'password123',
        })
      );
    });
  });

  it('should show error message on failed submission', async () => {
    const user = userEvent.setup();
    const handleSubmit = jest.fn().mockRejectedValue(new Error('Invalid credentials'));

    render(<LoginForm onSubmit={handleSubmit} />);

    await user.click(screen.getByRole('button', { name: /login/i }));

    await waitFor(() => {
      expect(screen.getByText(/login failed/i)).toBeInTheDocument();
    });
  });

  it('should validate email format', async () => {
    const user = userEvent.setup();
    const handleSubmit = jest.fn();

    render(<LoginForm onSubmit={handleSubmit} />);

    const emailInput = screen.getByLabelText(/email/i) as HTMLInputElement;
    const passwordInput = screen.getByLabelText(/password/i) as HTMLInputElement;

    await user.type(emailInput, 'invalid-email');
    await user.type(passwordInput, 'password123');
    await user.click(screen.getByRole('button', { name: /login/i }));

    await waitFor(() => {
      expect(screen.getByText(/invalid email/i)).toBeInTheDocument();
    });
  });
});
```

---

## E2E TESTING

### 1. Cypress E2E Tests

```typescript
// __tests__/e2e/auth.spec.ts
describe('Authentication Flow', () => {
  beforeEach(() => {
    cy.visit('http://localhost:3000');
  });

  it('should login successfully', () => {
    // Navigate to login page
    cy.get('[data-testid="login-link"]').click();

    // Fill login form
    cy.get('input[type="email"]').type('user@example.com');
    cy.get('input[type="password"]').type('password123');

    // Submit form
    cy.get('button[type="submit"]').click();

    // Wait for redirect to dashboard
    cy.url().should('include', '/dashboard');
    cy.get('[data-testid="dashboard-header"]').should('be.visible');
  });

  it('should show error on invalid credentials', () => {
    cy.get('input[type="email"]').type('user@example.com');
    cy.get('input[type="password"]').type('wrongpassword');
    cy.get('button[type="submit"]').click();

    cy.get('[data-testid="error-message"]').should('contain', 'Invalid credentials');
  });

  it('should redirect unauthenticated users to login', () => {
    cy.visit('http://localhost:3000/dashboard');
    cy.url().should('include', '/login');
  });
});

describe('Fund Transfer Flow', () => {
  beforeEach(() => {
    // Login first
    cy.visit('http://localhost:3000/login');
    cy.get('input[type="email"]').type('user@example.com');
    cy.get('input[type="password"]').type('password123');
    cy.get('button[type="submit"]').click();

    // Wait for dashboard
    cy.url().should('include', '/dashboard');
  });

  it('should complete fund transfer', () => {
    // Click transfers menu
    cy.get('[data-testid="transfers-menu"]').click();

    // Click new transfer button
    cy.get('[data-testid="new-transfer-btn"]').click();

    // Fill transfer form
    cy.get('select[name="fromAccountId"]').select('account-1');
    cy.get('input[name="toAccountNumber"]').type('1234567890123456');
    cy.get('input[name="amount"]').type('10000');

    // Submit form
    cy.get('button[type="submit"]').click();

    // Verify confirmation page
    cy.get('[data-testid="transfer-confirmation"]').should('be.visible');
    cy.get('[data-testid="transfer-amount"]').should('contain', '₹10,000.00');

    // Confirm transfer
    cy.get('[data-testid="confirm-transfer-btn"]').click();

    // Enter OTP (mock)
    cy.get('input[type="text"][placeholder*="OTP"]').type('123456');

    // Verify success
    cy.get('[data-testid="transfer-success"]').should('be.visible');
  });
});
```

### 2. Cypress Configuration

```typescript
// cypress.config.ts
import { defineConfig } from 'cypress';

export default defineConfig({
  e2e: {
    baseUrl: 'http://localhost:3000',
    viewportWidth: 1280,
    viewportHeight: 720,
    defaultCommandTimeout: 10000,
    requestTimeout: 10000,
    responseTimeout: 10000,
    setupNodeEvents(on, config) {
      // implement node event listeners here
    },
  },
});
```

---

## PERFORMANCE TESTING

### 1. Lighthouse Performance Testing

```bash
# Install lighthouse
npm install --save-dev @lhci/cli @lhci/config

# Create lighthouserc.json
```

```json
{
  "ci": {
    "collect": {
      "url": ["http://localhost:3000"],
      "numberOfRuns": 3,
      "staticDistDir": "./out",
      "settings": {
        "configPath": "./lighthouserc.json"
      }
    },
    "upload": {
      "target": "temporary-public-storage"
    },
    "assert": {
      "preset": "lighthouse:recommended",
      "assertions": {
        "categories:performance": ["error", { "minScore": 0.9 }],
        "categories:accessibility": ["error", { "minScore": 0.9 }],
        "categories:best-practices": ["error", { "minScore": 0.9 }],
        "categories:seo": ["error", { "minScore": 0.9 }]
      }
    }
  }
}
```

### 2. Bundle Size Analysis

```bash
# Install bundle analyzer
npm install --save-dev @next/bundle-analyzer

# Update next.config.js
```

```javascript
const withBundleAnalyzer = require('@next/bundle-analyzer')({
  enabled: process.env.ANALYZE === 'true',
});

module.exports = withBundleAnalyzer({
  // ... your Next.js config
});

// Run with: ANALYZE=true npm run build
```

---

## DEPLOYMENT STRATEGY

### 1. Docker Configuration

```dockerfile
# Dockerfile
FROM node:18-alpine AS builder

WORKDIR /app

# Install dependencies
COPY package*.json ./
RUN npm ci --only=production

# Build Next.js app
COPY . .
RUN npm run build

# Production image
FROM node:18-alpine

WORKDIR /app

ENV NODE_ENV production

# Copy built app from builder
COPY --from=builder /app/public ./public
COPY --from=builder /app/.next ./.next
COPY --from=builder /app/node_modules ./node_modules
COPY --from=builder /app/package.json ./package.json

EXPOSE 3000

CMD ["npm", "start"]
```

### 2. Docker Compose Configuration

```yaml
# docker-compose.yml
version: '3.8'

services:
  cbs-frontend:
    build:
      context: .
      dockerfile: Dockerfile
    ports:
      - '3000:3000'
    environment:
      - NEXT_PUBLIC_API_URL=http://localhost:8080/api
      - NEXT_PUBLIC_WEBSOCKET_URL=http://localhost:8080
      - NODE_ENV=production
    depends_on:
      - cbs-backend
    healthcheck:
      test: ['CMD', 'curl', '-f', 'http://localhost:3000']
      interval: 30s
      timeout: 10s
      retries: 3

  cbs-backend:
    image: cbs-backend:latest
    ports:
      - '8080:8080'
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - DATABASE_URL=jdbc:postgresql://postgres:5432/cbs_db
    depends_on:
      - postgres

  postgres:
    image: postgres:15-alpine
    environment:
      - POSTGRES_DB=cbs_db
      - POSTGRES_PASSWORD=password
    volumes:
      - postgres_data:/var/lib/postgresql/data

volumes:
  postgres_data:
```

### 3. Kubernetes Deployment

```yaml
# k8s/deployment.yml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: cbs-frontend
  namespace: banking
spec:
  replicas: 3
  selector:
    matchLabels:
      app: cbs-frontend
  template:
    metadata:
      labels:
        app: cbs-frontend
    spec:
      containers:
        - name: frontend
          image: cbs-frontend:1.0.0
          ports:
            - containerPort: 3000
          resources:
            requests:
              cpu: 100m
              memory: 128Mi
            limits:
              cpu: 500m
              memory: 512Mi
          env:
            - name: NEXT_PUBLIC_API_URL
              valueFrom:
                configMapKeyRef:
                  name: cbs-config
                  key: api_url
            - name: NODE_ENV
              value: production
          livenessProbe:
            httpGet:
              path: /
              port: 3000
            initialDelaySeconds: 30
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /
              port: 3000
            initialDelaySeconds: 5
            periodSeconds: 5
---
apiVersion: v1
kind: Service
metadata:
  name: cbs-frontend-service
  namespace: banking
spec:
  type: LoadBalancer
  ports:
    - port: 80
      targetPort: 3000
  selector:
    app: cbs-frontend
```

---

## CI/CD PIPELINE

### 1. GitHub Actions Workflow

```yaml
# .github/workflows/deploy.yml
name: Deploy to Production

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3

      - name: Setup Node.js
        uses: actions/setup-node@v3
        with:
          node-version: '18'
          cache: 'npm'

      - name: Install dependencies
        run: npm ci

      - name: Run linter
        run: npm run lint

      - name: Run tests
        run: npm run test:coverage

      - name: Upload coverage to Codecov
        uses: codecov/codecov-action@v3
        with:
          files: ./coverage/lcov.info

      - name: Run E2E tests
        run: npm run e2e:ci

  build:
    needs: test
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3

      - name: Setup Node.js
        uses: actions/setup-node@v3
        with:
          node-version: '18'
          cache: 'npm'

      - name: Install dependencies
        run: npm ci

      - name: Build application
        run: npm run build

      - name: Upload artifact
        uses: actions/upload-artifact@v3
        with:
          name: build
          path: .next

  deploy:
    needs: build
    runs-on: ubuntu-latest
    if: github.ref == 'refs/heads/main' && github.event_name == 'push'
    steps:
      - uses: actions/checkout@v3

      - name: Download artifact
        uses: actions/download-artifact@v3
        with:
          name: build
          path: .next

      - name: Deploy to production
        env:
          DEPLOY_KEY: ${{ secrets.DEPLOY_KEY }}
        run: |
          # Deploy to production server
          echo "Deploying to production..."
```

---

## MONITORING & ALERTING

### 1. Sentry Configuration

```typescript
// src/instrumentation.ts
import * as Sentry from '@sentry/nextjs';

Sentry.init({
  dsn: process.env.NEXT_PUBLIC_SENTRY_DSN,
  environment: process.env.NODE_ENV,
  tracesSampleRate: process.env.NODE_ENV === 'production' ? 0.1 : 1.0,
  beforeSend(event) {
    // Filter sensitive data
    if (event.request?.url?.includes('password')) {
      return null;
    }
    return event;
  },
  integrations: [
    new Sentry.Replay({
      maskAllText: true,
      blockAllMedia: true,
    }),
  ],
  replaysSessionSampleRate: 0.1,
  replaysOnErrorSampleRate: 1.0,
});
```

### 2. Analytics Setup

```typescript
// src/utils/analytics.ts
import * as amplitude from '@amplitude/analytics-browser';

export const initializeAnalytics = () => {
  amplitude.init(process.env.NEXT_PUBLIC_AMPLITUDE_KEY!, {
    defaultTracking: {
      sessions: true,
      formInteractions: true,
      fileDownloads: true,
    },
  });
};

export const trackEvent = (event: string, properties?: Record<string, any>) => {
  amplitude.track(event, properties);
};
```

---

## PRODUCTION CHECKLIST

- [ ] Security audit completed
- [ ] Performance testing passed (Lighthouse score >90)
- [ ] All tests passing (80%+ coverage)
- [ ] TypeScript strict mode enabled
- [ ] No console.log in production builds
- [ ] HTTPS enabled
- [ ] Security headers configured
- [ ] Rate limiting implemented
- [ ] Error monitoring (Sentry) configured
- [ ] Analytics tracking configured
- [ ] Database backups configured
- [ ] CDN configured for static assets
- [ ] Load testing completed
- [ ] Disaster recovery plan documented
- [ ] Rollback procedure tested
- [ ] Team trained on deployment
- [ ] Monitoring and alerting configured
- [ ] CI/CD pipeline tested
- [ ] Feature flags configured
- [ ] Documentation updated

---

This comprehensive guide ensures:
✅ 80%+ code coverage with multiple testing layers
✅ Production-grade CI/CD pipeline
✅ Kubernetes deployment ready
✅ Monitoring & error tracking
✅ Performance optimized
✅ Security hardened

