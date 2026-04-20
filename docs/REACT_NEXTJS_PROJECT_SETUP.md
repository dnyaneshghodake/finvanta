# TIER-1 REACT + NEXT.JS PROJECT SETUP & COMPONENT LIBRARY
## Complete Implementation Guide

**Document Version:** 1.0  
**Date:** April 19, 2026  
**Grade:** Tier-1 Enterprise Banking Standard

---

## TABLE OF CONTENTS

1. [Project Initialization](#project-initialization)
2. [Directory Structure](#directory-structure)
3. [Dependencies & Installation](#dependencies--installation)
4. [Configuration Files](#configuration-files)
5. [Base Component Library](#base-component-library)
6. [Common Components](#common-components)
7. [Layout Components](#layout-components)
8. [Form Components](#form-components)
9. [Advanced Components](#advanced-components)
10. [Utility Functions](#utility-functions)

---

## PROJECT INITIALIZATION

### 1. Create Next.js Project

```bash
npx create-next-app@latest cbs-banking-app --typescript --tailwind --eslint

# Or for manual setup:
npx create-next-app@14 --typescript --tailwind

cd cbs-banking-app
```

### 2. Environment Setup

```bash
# Create environment files
touch .env.local
touch .env.development
touch .env.production
```

```bash
# .env.local
NEXT_PUBLIC_API_URL=http://localhost:8080/api
NEXT_PUBLIC_APP_NAME=CBS Banking App
NEXT_PUBLIC_APP_VERSION=1.0.0
NEXT_PUBLIC_ENVIRONMENT=development

# JWT Configuration
NEXT_PUBLIC_JWT_EXPIRY=3600
NEXT_PUBLIC_REFRESH_TOKEN_EXPIRY=2592000

# API Configuration
API_TIMEOUT=30000
API_RETRY_ATTEMPTS=3

# Feature Flags
NEXT_PUBLIC_ENABLE_MFA=true
NEXT_PUBLIC_ENABLE_BIOMETRIC=true
NEXT_PUBLIC_ENABLE_REAL_TIME_UPDATE=true

# Third-party Services
NEXT_PUBLIC_SENTRY_DSN=https://...@sentry.io/...
NEXT_PUBLIC_ANALYTICS_KEY=...
```

---

## DIRECTORY STRUCTURE

```
cbs-banking-app/
├── .env.local
├── .env.development
├── .env.production
├── .eslintrc.json
├── .prettierrc
├── tsconfig.json
├── next.config.js
├── tailwind.config.js
├── jest.config.js
├── package.json
├── public/
│   ├── favicon.ico
│   ├── logo.png
│   ├── images/
│   │   ├── hero.png
│   │   └── ...
│   └── icons/
│       ├── account.svg
│       ├── transfer.svg
│       └── ...
├── src/
│   ├── app/
│   │   ├── layout.tsx
│   │   ├── page.tsx
│   │   ├── globals.css
│   │   ├── (auth)/
│   │   │   ├── layout.tsx
│   │   │   ├── login/
│   │   │   │   ├── page.tsx
│   │   │   │   └── layout.tsx
│   │   │   ├── register/
│   │   │   │   └── page.tsx
│   │   │   ├── forgot-password/
│   │   │   │   └── page.tsx
│   │   │   └── mfa-verify/
│   │   │       └── page.tsx
│   │   ├── (dashboard)/
│   │   │   ├── layout.tsx
│   │   │   ├── page.tsx (main dashboard)
│   │   │   ├── accounts/
│   │   │   │   ├── page.tsx
│   │   │   │   ├── [id]/
│   │   │   │   │   └── page.tsx
│   │   │   │   └── new/
│   │   │   │       └── page.tsx
│   │   │   ├── transfers/
│   │   │   │   ├── page.tsx
│   │   │   │   └── new/
│   │   │   │       └── page.tsx
│   │   │   ├── loans/
│   │   │   │   ├── page.tsx
│   │   │   │   ├── [id]/
│   │   │   │   │   └── page.tsx
│   │   │   │   └── apply/
│   │   │   │       └── page.tsx
│   │   │   ├── deposits/
│   │   │   │   ├── page.tsx
│   │   │   │   └── new/
│   │   │   │       └── page.tsx
│   │   │   ├── bills/
│   │   │   │   └── page.tsx
│   │   │   ├── profile/
│   │   │   │   └── page.tsx
│   │   │   ├── settings/
│   │   │   │   ├── page.tsx
│   │   │   │   ├── security/
│   │   │   │   │   └── page.tsx
│   │   │   │   └── preferences/
│   │   │   │       └── page.tsx
│   │   │   ├── admin/
│   │   │   │   ├── page.tsx
│   │   │   │   ├── users/
│   │   │   │   │   └── page.tsx
│   │   │   │   └── audit-logs/
│   │   │   │       └── page.tsx
│   │   │   └── reports/
│   │   │       └── page.tsx
│   │   ├── api/
│   │   │   ├── auth/
│   │   │   │   ├── login/route.ts
│   │   │   │   ├── logout/route.ts
│   │   │   │   ├── refresh/route.ts
│   │   │   │   └── mfa-verify/route.ts
│   │   │   ├── accounts/
│   │   │   │   ├── route.ts
│   │   │   │   └── [id]/route.ts
│   │   │   └── transfers/
│   │   │       ├── route.ts
│   │   │       └── [id]/route.ts
│   │   ├── error.tsx
│   │   ├── not-found.tsx
│   │   └── loading.tsx
│   ├── components/
│   │   ├── common/
│   │   │   ├── Button/
│   │   │   │   ├── Button.tsx
│   │   │   │   ├── Button.test.tsx
│   │   │   │   └── Button.module.css
│   │   │   ├── Card/
│   │   │   │   ├── Card.tsx
│   │   │   │   ├── Card.test.tsx
│   │   │   │   └── Card.module.css
│   │   │   ├── Modal/
│   │   │   ├── Spinner/
│   │   │   ├── Badge/
│   │   │   ├── Alert/
│   │   │   ├── Avatar/
│   │   │   ├── Tabs/
│   │   │   ├── Pagination/
│   │   │   ├── Breadcrumb/
│   │   │   └── Toast/
│   │   ├── layout/
│   │   │   ├── Header/
│   │   │   │   ├── Header.tsx
│   │   │   │   └── Header.module.css
│   │   │   ├── Sidebar/
│   │   │   │   ├── Sidebar.tsx
│   │   │   │   ├── Sidebar.module.css
│   │   │   │   └── MenuItem.tsx
│   │   │   ├── MainLayout/
│   │   │   │   └── MainLayout.tsx
│   │   │   ├── AuthLayout/
│   │   │   │   └── AuthLayout.tsx
│   │   │   └── Footer/
│   │   │       └── Footer.tsx
│   │   ├── forms/
│   │   │   ├── FormField/
│   │   │   │   ├── FormField.tsx
│   │   │   │   ├── FormField.test.tsx
│   │   │   │   └── FormField.module.css
│   │   │   ├── LoginForm/
│   │   │   │   ├── LoginForm.tsx
│   │   │   │   └── LoginForm.test.tsx
│   │   │   ├── TransferForm/
│   │   │   ├── LoanApplicationForm/
│   │   │   └── KYCForm/
│   │   ├── auth/
│   │   │   ├── LoginPage/
│   │   │   ├── MFAVerification/
│   │   │   ├── RegisterPage/
│   │   │   └── ProtectedPage/
│   │   ├── dashboard/
│   │   │   ├── DashboardPage/
│   │   │   ├── AccountsOverview/
│   │   │   ├── TransactionsWidget/
│   │   │   ├── AlertsPanel/
│   │   │   ├── QuickActionsPanel/
│   │   │   └── ChartsSection/
│   │   ├── accounts/
│   │   │   ├── AccountsList/
│   │   │   ├── AccountDetails/
│   │   │   ├── OpenAccountWizard/
│   │   │   ├── StatementDownloader/
│   │   │   └── LinkedAccountsManager/
│   │   ├── transfers/
│   │   │   ├── TransferWizard/
│   │   │   ├── BeneficiaryManager/
│   │   │   ├── TransferHistory/
│   │   │   └── TransferTracker/
│   │   ├── loans/
│   │   │   ├── LoansList/
│   │   │   ├── LoanDetails/
│   │   │   ├── EMICalculator/
│   │   │   └── LoanApplicationWizard/
│   │   └── (other modules)
│   ├── hooks/
│   │   ├── useApi.ts
│   │   ├── useAuth.ts
│   │   ├── useForm.ts
│   │   ├── useLocalStorage.ts
│   │   ├── useWebSocket.ts
│   │   ├── usePagination.ts
│   │   ├── useDebounce.ts
│   │   ├── useWindowSize.ts
│   │   └── useToggle.ts
│   ├── services/
│   │   ├── api/
│   │   │   ├── apiClient.ts
│   │   │   ├── accountService.ts
│   │   │   ├── transferService.ts
│   │   │   ├── loanService.ts
│   │   │   ├── authService.ts
│   │   │   ├── kycService.ts
│   │   │   └── reportService.ts
│   │   ├── auth/
│   │   │   ├── authService.ts
│   │   │   ├── tokenService.ts
│   │   │   └── mfaService.ts
│   │   ├── real-time/
│   │   │   ├── webSocketService.ts
│   │   │   └── notificationService.ts
│   │   └── storage/
│   │       ├── localStorageService.ts
│   │       └── sessionStorageService.ts
│   ├── store/
│   │   ├── index.ts
│   │   ├── authStore.ts
│   │   ├── accountStore.ts
│   │   ├── transferStore.ts
│   │   ├── loanStore.ts
│   │   ├── notificationStore.ts
│   │   ├── uiStore.ts
│   │   └── offlineStore.ts
│   ├── utils/
│   │   ├── formatters.ts
│   │   ├── validators.ts
│   │   ├── errorHandler.ts
│   │   ├── logger.ts
│   │   ├── sanitizer.ts
│   │   ├── masking.ts
│   │   ├── constants.ts
│   │   └── helpers.ts
│   ├── types/
│   │   ├── index.ts
│   │   ├── entities.ts
│   │   ├── api.ts
│   │   ├── forms.ts
│   │   ├── common.ts
│   │   └── store.ts
│   ├── middleware/
│   │   ├── auth.ts
│   │   ├── errorHandler.ts
│   │   └── requestInterceptor.ts
│   ├── config/
│   │   ├── constants.ts
│   │   ├── environment.ts
│   │   ├── theme.ts
│   │   ├── api.ts
│   │   └── features.ts
│   └── styles/
│       ├── globals.css
│       ├── variables.css
│       ├── components.css
│       └── animations.css
├── __tests__/
│   ├── unit/
│   │   ├── utils/
│   │   ├── hooks/
│   │   └── services/
│   ├── integration/
│   │   └── components/
│   ├── e2e/
│   │   ├── auth.spec.ts
│   │   ├── accounts.spec.ts
│   │   └── transfers.spec.ts
│   └── fixtures/
│       ├── mockData.ts
│       └── mockServer.ts
└── docs/
    ├── API.md
    ├── COMPONENTS.md
    ├── HOOKS.md
    └── DEPLOYMENT.md
```

---

## DEPENDENCIES & INSTALLATION

### 1. Core Dependencies

```bash
npm install next@latest react@latest react-dom@latest typescript@latest
```

### 2. UI & Styling

```bash
# TailwindCSS (already included with create-next-app)
npm install -D tailwindcss postcss autoprefixer

# Material-UI Components (optional alternative)
npm install @mui/material @emotion/react @emotion/styled

# Icons
npm install react-icons lucide-react
```

### 3. Form Handling

```bash
npm install react-hook-form zod @hookform/resolvers
```

### 4. State Management

```bash
npm install zustand
npm install @reduxjs/toolkit react-redux # Alternative to Zustand
```

### 5. HTTP Client & API

```bash
npm install axios
```

### 6. Real-time Communication

```bash
npm install socket.io-client # WebSocket
```

### 7. Utilities

```bash
npm install lodash-es
npm install date-fns
npm install classnames
npm install dompurify
npm install js-cookie
npm install jspdf html2canvas # PDF generation
```

### 8. Authentication

```bash
npm install next-auth # NextAuth.js (optional)
npx auth secret # Generate secret for NextAuth
```

### 9. Development Tools

```bash
npm install -D eslint eslint-config-next
npm install -D prettier
npm install -D husky lint-staged
npm install -D jest @testing-library/react @testing-library/jest-dom
npm install -D cypress
npm install -D storybook @storybook/react @storybook/nextjs
npm install -D dotenv-cli
```

### 10. Monitoring & Analytics

```bash
npm install @sentry/nextjs
npm install @amplitude/analytics-browser
```

### Complete package.json

```json
{
  "name": "cbs-banking-app",
  "version": "1.0.0",
  "description": "Tier-1 Grade Core Banking System",
  "private": true,
  "scripts": {
    "dev": "next dev",
    "build": "next build",
    "start": "next start",
    "lint": "next lint",
    "lint:fix": "next lint --fix",
    "format": "prettier --write \"src/**/*.{ts,tsx}\"",
    "test": "jest",
    "test:watch": "jest --watch",
    "test:coverage": "jest --coverage",
    "e2e": "cypress open",
    "storybook": "storybook dev -p 6006",
    "build-storybook": "storybook build",
    "prepare": "husky install"
  },
  "dependencies": {
    "react": "^18.2.0",
    "react-dom": "^18.2.0",
    "next": "^14.0.0",
    "react-hook-form": "^7.48.0",
    "zod": "^3.22.0",
    "@hookform/resolvers": "^3.3.0",
    "zustand": "^4.4.0",
    "axios": "^1.6.0",
    "socket.io-client": "^4.7.0",
    "date-fns": "^2.30.0",
    "lodash-es": "^4.17.21",
    "classnames": "^2.3.2",
    "dompurify": "^3.0.0",
    "js-cookie": "^3.0.5",
    "react-icons": "^4.12.0",
    "lucide-react": "^0.292.0",
    "jspdf": "^2.5.1",
    "html2canvas": "^1.4.1",
    "@sentry/nextjs": "^7.84.0"
  },
  "devDependencies": {
    "typescript": "^5.2.0",
    "@types/node": "^20.0.0",
    "@types/react": "^18.2.0",
    "@types/react-dom": "^18.2.0",
    "eslint": "^8.50.0",
    "eslint-config-next": "^14.0.0",
    "@typescript-eslint/eslint-plugin": "^6.7.0",
    "@typescript-eslint/parser": "^6.7.0",
    "prettier": "^3.0.0",
    "tailwindcss": "^3.3.0",
    "postcss": "^8.4.0",
    "autoprefixer": "^10.4.0",
    "jest": "^29.7.0",
    "@testing-library/react": "^14.0.0",
    "@testing-library/jest-dom": "^6.1.0",
    "cypress": "^13.3.0",
    "storybook": "^7.5.0",
    "@storybook/react": "^7.5.0",
    "@storybook/nextjs": "^7.5.0",
    "husky": "^8.0.0",
    "lint-staged": "^15.0.0",
    "dotenv-cli": "^7.3.0"
  }
}
```

---

## CONFIGURATION FILES

### 1. TypeScript Configuration (tsconfig.json)

```json
{
  "compilerOptions": {
    "target": "ES2020",
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "jsx": "preserve",
    "module": "ESNext",
    "moduleResolution": "node",
    "resolveJsonModule": true,
    "allowJs": true,
    "baseUrl": ".",
    "paths": {
      "@/*": ["./src/*"]
    },
    "strict": true,
    "strictNullChecks": true,
    "strictFunctionTypes": true,
    "strictBindCallApply": true,
    "strictPropertyInitialization": true,
    "noImplicitAny": true,
    "noImplicitThis": true,
    "alwaysStrict": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "noImplicitReturns": true,
    "noFallthroughCasesInSwitch": true,
    "declaration": true,
    "declarationMap": true,
    "sourceMap": true,
    "outDir": "./dist",
    "removeComments": true,
    "esModuleInterop": true,
    "forceConsistentCasingInFileNames": true,
    "skipLibCheck": true
  },
  "include": ["next-env.d.ts", "**/*.ts", "**/*.tsx"],
  "exclude": ["node_modules", "dist", "build", ".next"]
}
```

### 2. Next.js Configuration (next.config.js)

```javascript
/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  swcMinify: true,
  
  // Image optimization
  images: {
    remotePatterns: [
      {
        protocol: 'https',
        hostname: 'api.example.com',
      },
    ],
    formats: ['image/webp', 'image/avif'],
  },

  // Security headers
  headers: async () => {
    return [
      {
        source: '/:path*',
        headers: [
          {
            key: 'X-Content-Type-Options',
            value: 'nosniff',
          },
          {
            key: 'X-Frame-Options',
            value: 'SAMEORIGIN',
          },
          {
            key: 'X-XSS-Protection',
            value: '1; mode=block',
          },
          {
            key: 'Strict-Transport-Security',
            value: 'max-age=63072000; includeSubDomains; preload',
          },
          {
            key: 'Content-Security-Policy',
            value: "default-src 'self'; script-src 'self' 'unsafe-inline' 'unsafe-eval';",
          },
        ],
      },
    ];
  },

  // Environment variables
  env: {
    NEXT_PUBLIC_API_URL: process.env.NEXT_PUBLIC_API_URL,
  },

  // API endpoint rewrites
  rewrites: async () => {
    return {
      beforeFiles: [
        {
          source: '/api/:path*',
          destination: `${process.env.NEXT_PUBLIC_API_URL}/:path*`,
        },
      ],
    };
  },
};

module.exports = nextConfig;
```

### 3. Tailwind Configuration (tailwind.config.js)

```javascript
/** @type {import('tailwindcss').Config} */
module.exports = {
  content: [
    './src/pages/**/*.{js,ts,jsx,tsx,mdx}',
    './src/components/**/*.{js,ts,jsx,tsx,mdx}',
    './src/app/**/*.{js,ts,jsx,tsx,mdx}',
  ],
  theme: {
    extend: {
      colors: {
        primary: {
          50: '#f0f7ff',
          100: '#e0efff',
          500: '#0066cc',
          600: '#0052a3',
          700: '#003d7a',
          900: '#001f4d',
        },
        success: '#10b981',
        warning: '#f59e0b',
        error: '#ef4444',
        gray: {
          50: '#f9fafb',
          100: '#f3f4f6',
          200: '#e5e7eb',
          500: '#6b7280',
          900: '#111827',
        },
      },
      spacing: {
        '128': '32rem',
      },
      fontSize: {
        xs: '0.75rem',
        sm: '0.875rem',
        base: '1rem',
        lg: '1.125rem',
        xl: '1.25rem',
      },
      borderRadius: {
        sm: '0.25rem',
        md: '0.375rem',
        lg: '0.5rem',
      },
    },
  },
  plugins: [
    require('@tailwindcss/forms'),
    require('@tailwindcss/typography'),
  ],
};
```

### 4. Jest Configuration (jest.config.js)

```javascript
const nextJest = require('next/jest')

const createJestConfig = nextJest({
  dir: './',
})

const customJestConfig = {
  setupFilesAfterEnv: ['<rootDir>/jest.setup.js'],
  testEnvironment: 'jest-environment-jsdom',
  moduleNameMapper: {
    '^@/(.*)$': '<rootDir>/src/$1',
  },
  collectCoverageFrom: [
    'src/**/*.{js,jsx,ts,tsx}',
    '!src/**/*.d.ts',
    '!src/**/*.stories.{js,jsx,ts,tsx}',
    '!src/**/__tests__/**',
  ],
  coverageThresholds: {
    global: {
      branches: 80,
      functions: 80,
      lines: 80,
      statements: 80,
    },
  },
}

module.exports = createJestConfig(customJestConfig)
```

### 5. ESLint Configuration (.eslintrc.json)

```json
{
  "extends": ["next/core-web-vitals", "plugin:@typescript-eslint/recommended"],
  "parser": "@typescript-eslint/parser",
  "plugins": ["@typescript-eslint", "react-hooks"],
  "rules": {
    "no-console": ["warn", { "allow": ["warn", "error"] }],
    "no-debugger": "error",
    "no-var": "error",
    "prefer-const": "error",
    "prefer-arrow-callback": "warn",
    "@typescript-eslint/no-explicit-any": "error",
    "@typescript-eslint/explicit-function-return-types": "warn",
    "@typescript-eslint/no-unused-vars": ["error", { "argsIgnorePattern": "^_" }],
    "react/react-in-jsx-scope": "off",
    "react-hooks/rules-of-hooks": "error",
    "react-hooks/exhaustive-deps": "warn"
  },
  "overrides": [
    {
      "files": ["**/*.test.ts", "**/*.spec.ts"],
      "rules": {
        "@typescript-eslint/no-explicit-any": "off"
      }
    }
  ]
}
```

### 6. Prettier Configuration (.prettierrc)

```json
{
  "semi": true,
  "singleQuote": true,
  "trailing Comma": "es5",
  "printWidth": 100,
  "tabWidth": 2,
  "useTabs": false,
  "arrow ParenthesesAlways": "always",
  "jsxBracketSameLine": false
}
```

### 7. Git Hooks (husky & lint-staged)

```bash
# Initialize husky
npx husky install

# Create pre-commit hook
npx husky add .husky/pre-commit "npx lint-staged"
```

```json
// .lint-stagedrc.json
{
  "src/**/*.{ts,tsx}": [
    "eslint --fix",
    "prettier --write",
    "jest --bail" 
  ]
}
```

---

## BASE COMPONENT LIBRARY

### 1. Button Component

```typescript
// src/components/common/Button/Button.tsx
import React from 'react';
import classNames from 'classnames';
import styles from './Button.module.css';

interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'danger' | 'success';
  size?: 'sm' | 'md' | 'lg';
  isLoading?: boolean;
  icon?: React.ReactNode;
  fullWidth?: boolean;
}

export const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({
    variant = 'primary',
    size = 'md',
    isLoading = false,
    icon,
    fullWidth = false,
    className,
    children,
    disabled,
    ...props
  }, ref) => {
    return (
      <button
        ref={ref}
        className={classNames(
          styles.button,
          styles[`variant-${variant}`],
          styles[`size-${size}`],
          {
            [styles.fullWidth]: fullWidth,
            [styles.loading]: isLoading,
          },
          className
        )}
        disabled={disabled || isLoading}
        {...props}
      >
        {icon && <span className={styles.icon}>{icon}</span>}
        {isLoading ? 'Loading...' : children}
      </button>
    );
  }
);

Button.displayName = 'Button';
```

### 2. Card Component

```typescript
// src/components/common/Card/Card.tsx
import React from 'react';
import classNames from 'classnames';
import styles from './Card.module.css';

interface CardProps extends React.HTMLAttributes<HTMLDivElement> {
  variant?: 'default' | 'outlined' | 'elevated';
  padding?: 'sm' | 'md' | 'lg';
}

export const Card = React.forwardRef<HTMLDivElement, CardProps>(
  ({ variant = 'default', padding = 'md', className, children, ...props }, ref) => {
    return (
      <div
        ref={ref}
        className={classNames(
          styles.card,
          styles[`variant-${variant}`],
          styles[`padding-${padding}`],
          className
        )}
        {...props}
      >
        {children}
      </div>
    );
  }
);

Card.displayName = 'Card';

// Sub-components
export const CardHeader: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <div className={styles.header}>{children}</div>
);

export const CardBody: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <div className={styles.body}>{children}</div>
);

export const CardFooter: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <div className={styles.footer}>{children}</div>
);
```

### 3. Modal Component

```typescript
// src/components/common/Modal/Modal.tsx
import React, { useCallback, useEffect } from 'react';
import classNames from 'classnames';
import styles from './Modal.module.css';

interface ModalProps {
  isOpen: boolean;
  onClose: () => void;
  title?: string;
  children: React.ReactNode;
  size?: 'sm' | 'md' | 'lg';
  closeOnBackdropClick?: boolean;
}

export const Modal: React.FC<ModalProps> = ({
  isOpen,
  onClose,
  title,
  children,
  size = 'md',
  closeOnBackdropClick = true,
}) => {
  useEffect(() => {
    const handleEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose();
      }
    };

    if (isOpen) {
      document.addEventListener('keydown', handleEscape);
      document.body.style.overflow = 'hidden';
    }

    return () => {
      document.removeEventListener('keydown', handleEscape);
      document.body.style.overflow = 'unset';
    };
  }, [isOpen, onClose]);

  const handleBackdropClick = useCallback(() => {
    if (closeOnBackdropClick) {
      onClose();
    }
  }, [closeOnBackdropClick, onClose]);

  if (!isOpen) return null;

  return (
    <div className={styles.overlay} onClick={handleBackdropClick}>
      <div
        className={classNames(styles.modal, styles[`size-${size}`])}
        onClick={(e) => e.stopPropagation()}
      >
        {title && (
          <div className={styles.header}>
            <h2>{title}</h2>
            <button 
              className={styles.closeButton}
              onClick={onClose}
              aria-label="Close modal"
            >
              ×
            </button>
          </div>
        )}
        <div className={styles.content}>{children}</div>
      </div>
    </div>
  );
};
```

### 4. Spinner Component

```typescript
// src/components/common/Spinner/Spinner.tsx
import React from 'react';
import classNames from 'classnames';
import styles from './Spinner.module.css';

interface SpinnerProps {
  size?: 'sm' | 'md' | 'lg';
  fullScreen?: boolean;
  message?: string;
}

export const Spinner: React.FC<SpinnerProps> = ({
  size = 'md',
  fullScreen = false,
  message,
}) => {
  const spinner = (
    <div className={classNames(styles.spinner, styles[`size-${size}`])}>
      <div className={styles.spin} />
      {message && <p className={styles.message}>{message}</p>}
    </div>
  );

  if (fullScreen) {
    return <div className={styles.fullScreen}>{spinner}</div>;
  }

  return spinner;
};
```

### 5. Alert Component

```typescript
// src/components/common/Alert/Alert.tsx
import React from 'react';
import classNames from 'classnames';
import { X } from 'lucide-react';
import styles from './Alert.module.css';

interface AlertProps {
  type: 'success' | 'warning' | 'error' | 'info';
  title?: string;
  message: string;
  onClose?: () => void;
  dismissible?: boolean;
}

export const Alert: React.FC<AlertProps> = ({
  type,
  title,
  message,
  onClose,
  dismissible = true,
}) => {
  return (
    <div className={classNames(styles.alert, styles[`type-${type}`])}>
      <div className={styles.content}>
        {title && <h3 className={styles.title}>{title}</h3>}
        <p className={styles.message}>{message}</p>
      </div>
      {dismissible && (
        <button
          className={styles.closeButton}
          onClick={onClose}
          aria-label="Close alert"
        >
          <X size={18} />
        </button>
      )}
    </div>
  );
};
```

---

## COMMON COMPONENTS

(Due to length constraints, showing pattern examples)

### FormField Component

```typescript
// src/components/forms/FormField/FormField.tsx
import React from 'react';
import { FieldValues, UseFormRegisterReturn, FieldError } from 'react-hook-form';
import styles from './FormField.module.css';

interface FormFieldProps extends React.InputHTMLAttributes<HTMLInputElement> {
  label: string;
  error?: FieldError;
  register?: UseFormRegisterReturn;
  helperText?: string;
  required?: boolean;
}

export const FormField = React.forwardRef<HTMLInputElement, FormFieldProps>(
  ({
    label,
    error,
    register,
    helperText,
    required,
    className,
    ...props
  }, ref) => {
    return (
      <div className={styles.field}>
        <label className={styles.label}>
          {label}
          {required && <span className={styles.required}>*</span>}
        </label>
        <input
          ref={ref}
          className={`${styles.input} ${error ? styles.error : ''}`}
          {...register}
          {...props}
        />
        {error && <span className={styles.errorMessage}>{error.message}</span>}
        {helperText && <span className={styles.helperText}>{helperText}</span>}
      </div>
    );
  }
);

FormField.displayName = 'FormField';
```

---

This provides a solid foundation for a Tier-1 React + Next.js banking application!

Continue with the remaining 5 documents for complete implementation guidance...

