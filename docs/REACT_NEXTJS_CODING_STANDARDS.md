# TIER-1 REACT + NEXT.JS CODING STANDARDS
## Enterprise Banking Grade Implementation

**Document Version:** 1.0  
**Date:** April 19, 2026  
**Grade:** Tier-1 Enterprise Banking Standard

---

## TABLE OF CONTENTS

1. [TypeScript Standards](#typescript-standards)
2. [Component Coding Standards](#component-coding-standards)
3. [Hooks Standards](#hooks-standards)
4. [API Integration Standards](#api-integration-standards)
5. [State Management Standards](#state-management-standards)
6. [Form Handling Standards](#form-handling-standards)
7. [Error Handling & Logging](#error-handling--logging)
8. [Performance Standards](#performance-standards)
9. [Security Standards](#security-standards)
10. [Testing Standards](#testing-standards)
11. [Code Quality Metrics](#code-quality-metrics)

---

## TYPESCRIPT STANDARDS

### 1. Strict TypeScript Configuration

```json
// tsconfig.json
{
  "compilerOptions": {
    "strict": true,
    "noImplicitAny": true,
    "strictNullChecks": true,
    "strictFunctionTypes": true,
    "strictBindCallApply": true,
    "strictPropertyInitialization": true,
    "noImplicitThis": true,
    "alwaysStrict": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "noImplicitReturns": true,
    "noFallthroughCasesInSwitch": true,
    "esModuleInterop": true,
    "target": "ES2020",
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "jsx": "react-jsx",
    "declaration": true,
    "declarationMap": true,
    "sourceMap": true
  },
  "include": ["src/**/*"],
  "exclude": ["node_modules", "dist", "build"]
}
```

### 2. Type Definitions

```typescript
// types/entities.ts

/**
 * Core entity types
 */

export interface User {
  id: string;
  email: string;
  firstName: string;
  lastName: string;
  phoneNumber: string;
  dateOfBirth: Date;
  gender: 'MALE' | 'FEMALE' | 'OTHER';
  address: Address;
  kycStatus: KYCStatus;
  amlStatus: AMLStatus;
  createdAt: Date;
  updatedAt: Date;
  lastLogin: Date | null;
}

export interface Account {
  id: string;
  accountNumber: string;
  customerId: string;
  accountType: 'SAVINGS' | 'CURRENT' | 'SALARY';
  currency: string;
  balance: number;
  availableBalance: number;
  status: 'ACTIVE' | 'INACTIVE' | 'FROZEN' | 'CLOSED';
  openedDate: Date;
  closedDate: Date | null;
  linkedAccounts: string[];
  createdAt: Date;
  updatedAt: Date;
}

export interface Transaction {
  id: string;
  transactionId: string;
  accountId: string;
  fromAccount?: string;
  toAccount?: string;
  amount: number;
  currency: string;
  transactionType: 'DEBIT' | 'CREDIT' | 'TRANSFER';
  status: 'PENDING' | 'COMPLETED' | 'FAILED' | 'REVERSED';
  description: string;
  valueDate: Date;
  postingDate: Date;
  referenceNumber: string;
  beneficiaryName?: string;
  createdAt: Date;
  updatedAt: Date;
}

export interface Address {
  street: string;
  city: string;
  state: string;
  country: string;
  postalCode: string;
  type: 'RESIDENTIAL' | 'OFFICE' | 'MAILING';
}

export type KYCStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED';
export type AMLStatus = 'PENDING' | 'APPROVED' | 'FLAGGED' | 'REJECTED';

// types/api.ts
export interface ApiResponse<T> {
  success: boolean;
  data?: T;
  error?: ApiError;
  timestamp: string;
  requestId: string;
}

export interface ApiError {
  code: string;
  message: string;
  details?: Record<string, string>;
  statusCode: number;
}

export interface PaginatedResponse<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
  hasNextPage: boolean;
}

export interface FormData {
  [key: string]: any;
}

export interface ValidationError {
  field: string;
  message: string;
  code: string;
}
```

### 3. No Implicit Any

```typescript
// ❌ BAD: Implicit any
function processData(data) {
  return data.map(item => item.id);
}

// ✅ GOOD: Explicit types
function processData(data: User[]): string[] {
  return data.map((item: User) => item.id);
}

// ✅ GOOD: Generic types
function processData<T extends { id: string }>(data: T[]): string[] {
  return data.map(item => item.id);
}
```

### 4. Const Assertions for Literal Types

```typescript
// ✅ GOOD: Literal types for enums
export const ACCOUNT_TYPES = {
  SAVINGS: 'SAVINGS',
  CURRENT: 'CURRENT',
  SALARY: 'SALARY',
} as const;

export type AccountType = typeof ACCOUNT_TYPES[keyof typeof ACCOUNT_TYPES];

// ✅ GOOD: Union types
export type TransactionStatus = 
  | 'PENDING' 
  | 'COMPLETED' 
  | 'FAILED' 
  | 'REVERSED';
```

---

## COMPONENT CODING STANDARDS

### 1. Functional Components with TypeScript

```typescript
// ✅ GOOD: Typed functional component
import React, { useState, useCallback } from 'react';

interface LoginFormProps {
  onSubmit: (credentials: LoginCredentials) => Promise<void>;
  isLoading?: boolean;
}

interface LoginCredentials {
  email: string;
  password: string;
}

export const LoginForm: React.FC<LoginFormProps> = ({
  onSubmit,
  isLoading = false,
}) => {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});

  const handleSubmit = useCallback(async (e: React.FormEvent) => {
    e.preventDefault();
    
    try {
      await onSubmit({ email, password });
    } catch (error) {
      setErrors({ submit: 'Login failed' });
    }
  }, [email, password, onSubmit]);

  return (
    <form onSubmit={handleSubmit}>
      <input
        type="email"
        value={email}
        onChange={(e) => setEmail(e.target.value)}
        placeholder="Email"
        aria-label="Email address"
      />
      <input
        type="password"
        value={password}
        onChange={(e) => setPassword(e.target.value)}
        placeholder="Password"
        aria-label="Password"
      />
      {errors.submit && <span className="error">{errors.submit}</span>}
      <button type="submit" disabled={isLoading}>
        {isLoading ? 'Logging in...' : 'Login'}
      </button>
    </form>
  );
};
```

### 2. Component Naming & Organization

```typescript
// File: components/auth/LoginForm.tsx

// ✅ GOOD: Descriptive names
export const LoginForm = () => {}
export const LoginFormContainer = () => {}
export const LoginFormField = () => {}

// ❌ BAD: Generic names
export const Form = () => {}
export const Input = () => {}
export const Container = () => {}

// ✅ GOOD: One component per file (unless very related)
// LoginForm.tsx - Main component
// LoginForm.test.tsx - Tests
// LoginForm.module.css - Styles
// LoginForm.types.ts - Types (if large)
```

### 3. Props Destructuring

```typescript
// ✅ GOOD: Destructured props with defaults
interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'danger';
  size?: 'sm' | 'md' | 'lg';
  isLoading?: boolean;
}

export const Button: React.FC<ButtonProps> = ({
  variant = 'primary',
  size = 'md',
  isLoading = false,
  children,
  ...rest
}) => {
  return (
    <button
      className={`btn btn-${variant} btn-${size}`}
      disabled={isLoading}
      {...rest}
    >
      {isLoading ? <Spinner /> : children}
    </button>
  );
};

// Usage
<Button variant="primary" size="lg" onClick={handleClick}>
  Click Me
</Button>
```

### 4. Component Composition

```typescript
// ✅ GOOD: Composable components
export const Card: React.FC<{ children: React.ReactNode }> = ({
  children,
}) => <div className="card">{children}</div>;

export const CardHeader: React.FC<{ title: string }> = ({ title }) => (
  <div className="card-header">
    <h2>{title}</h2>
  </div>
);

export const CardBody: React.FC<{ children: React.ReactNode }> = ({
  children,
}) => <div className="card-body">{children}</div>;

// Usage
<Card>
  <CardHeader title="Account Details" />
  <CardBody>
    <AccountInfo />
  </CardBody>
</Card>
```

### 5. Conditional Rendering

```typescript
// ✅ GOOD: Clear conditional logic
export const TransactionList: React.FC<TransactionListProps> = ({
  transactions,
  isLoading,
  error,
}) => {
  // Loading state
  if (isLoading) {
    return <LoadingSpinner />;
  }

  // Error state
  if (error) {
    return <ErrorMessage error={error} />;
  }

  // Empty state
  if (transactions.length === 0) {
    return <EmptyState message="No transactions found" />;
  }

  // Render data
  return (
    <div className="transaction-list">
      {transactions.map(transaction => (
        <TransactionItem key={transaction.id} transaction={transaction} />
      ))}
    </div>
  );
};

// ❌ BAD: Multiple renders, unclear logic
{isLoading ? <Spinner /> : (
  error ? <Error /> : (
    transactions.length > 0 ? (
      <List>{transactions}</List>
    ) : (
      <Empty />
    )
  )
)}
```

---

## HOOKS STANDARDS

### 1. Custom Hook Patterns

```typescript
// hooks/useApi.ts
import { useEffect, useState } from 'react';

interface UseApiOptions {
  skip?: boolean;
  cacheTime?: number;
}

/**
 * Custom hook for API data fetching with caching
 * 
 * @template T - Response data type
 * @param url - API endpoint URL
 * @param options - Fetch options
 * @returns Object with data, loading, error states
 */
export const useApi = <T,>(
  url: string,
  options: UseApiOptions = {}
): {
  data: T | null;
  loading: boolean;
  error: Error | null;
  refetch: () => Promise<void>;
} => {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(!options.skip);
  const [error, setError] = useState<Error | null>(null);

  const fetchData = async () => {
    if (options.skip) return;

    setLoading(true);
    try {
      const response = await apiClient.get<T>(url);
      setData(response.data);
      setError(null);
    } catch (err) {
      setError(err as Error);
      logger.error(`Failed to fetch from ${url}:`, err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchData();
  }, [url]); // Only refetch on URL change

  return { data, loading, error, refetch: fetchData };
};

// Usage
const AccountsPage = () => {
  const { data: accounts, loading, error } = useApi<Account[]>('/api/accounts');
  
  if (loading) return <LoadingSpinner />;
  if (error) return <ErrorMessage error={error} />;
  
  return <AccountsList accounts={accounts || []} />;
};
```

### 2. Form Hook

```typescript
// hooks/useForm.ts
import { useState, useCallback } from 'react';

interface UseFormOptions<T> {
  initialValues: T;
  onSubmit: (values: T) => Promise<void>;
  validate?: (values: T) => Record<string, string>;
}

export const useForm = <T extends Record<string, any>>({
  initialValues,
  onSubmit,
  validate,
}: UseFormOptions<T>) => {
  const [values, setValues] = useState<T>(initialValues);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [touched, setTouched] = useState<Record<string, boolean>>({});
  const [isSubmitting, setIsSubmitting] = useState(false);

  const handleChange = useCallback(
    (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => {
      const { name, value, type } = e.target;
      const newValue = type === 'checkbox' ? (e.target as any).checked : value;
      
      setValues(prev => ({ ...prev, [name]: newValue }));
    },
    []
  );

  const handleBlur = useCallback((e: React.FocusEvent<HTMLInputElement>) => {
    const { name } = e.target;
    setTouched(prev => ({ ...prev, [name]: true }));
  }, []);

  const handleSubmit = useCallback(
    async (e: React.FormEvent) => {
      e.preventDefault();

      // Validate
      if (validate) {
        const newErrors = validate(values);
        setErrors(newErrors);
        if (Object.keys(newErrors).length > 0) return;
      }

      setIsSubmitting(true);
      try {
        await onSubmit(values);
      } catch (error) {
        setErrors({ submit: (error as Error).message });
      } finally {
        setIsSubmitting(false);
      }
    },
    [values, onSubmit, validate]
  );

  const reset = useCallback(() => {
    setValues(initialValues);
    setErrors({});
    setTouched({});
  }, [initialValues]);

  return {
    values,
    errors,
    touched,
    isSubmitting,
    handleChange,
    handleBlur,
    handleSubmit,
    reset,
    setValues,
  };
};

// Usage
const LoginForm = () => {
  const form = useForm({
    initialValues: { email: '', password: '' },
    onSubmit: async (values) => {
      await authService.login(values);
    },
    validate: (values) => {
      const errors: Record<string, string> = {};
      if (!validateEmail(values.email)) {
        errors.email = 'Invalid email';
      }
      if (values.password.length < 8) {
        errors.password = 'Password too short';
      }
      return errors;
    },
  });

  return (
    <form onSubmit={form.handleSubmit}>
      <input
        name="email"
        value={form.values.email}
        onChange={form.handleChange}
        onBlur={form.handleBlur}
      />
      {form.touched.email && form.errors.email && (
        <span>{form.errors.email}</span>
      )}
      <button type="submit" disabled={form.isSubmitting}>
        Login
      </button>
    </form>
  );
};
```

### 3. Local Storage Hook

```typescript
// hooks/useLocalStorage.ts
import { useState, useCallback } from 'react';

export const useLocalStorage = <T,>(
  key: string,
  initialValue: T
): [T, (value: T) => void] => {
  const [storedValue, setStoredValue] = useState<T>(() => {
    try {
      const item = window.localStorage.getItem(key);
      return item ? JSON.parse(item) : initialValue;
    } catch (error) {
      logger.error(`Failed to read from localStorage:`, error);
      return initialValue;
    }
  });

  const setValue = useCallback(
    (value: T) => {
      try {
        setStoredValue(value);
        window.localStorage.setItem(key, JSON.stringify(value));
      } catch (error) {
        logger.error(`Failed to write to localStorage:`, error);
      }
    },
    [key]
  );

  return [storedValue, setValue];
};

// Usage
const UserPreferences = () => {
  const [theme, setTheme] = useLocalStorage<'light' | 'dark'>('theme', 'light');
  
  return (
    <button onClick={() => setTheme(theme === 'light' ? 'dark' : 'light')}>
      Toggle {theme} theme
    </button>
  );
};
```

---

## API INTEGRATION STANDARDS

### 1. API Client Setup

```typescript
// services/api/apiClient.ts
import axios, {
  AxiosInstance,
  AxiosError,
  InternalAxiosRequestConfig,
} from 'axios';

const apiClient: AxiosInstance = axios.create({
  baseURL: process.env.NEXT_PUBLIC_API_URL,
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
  },
});

// Request interceptor
apiClient.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    // Add JWT token to headers
    const token = localStorage.getItem('token');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }

    // Add request ID for tracing
    config.headers['X-Request-ID'] = generateRequestId();

    return config;
  },
  (error) => {
    logger.error('Request error:', error);
    return Promise.reject(error);
  }
);

// Response interceptor
apiClient.interceptors.response.use(
  (response) => {
    // Log successful API calls
    logger.info(`API ${response.config.method?.toUpperCase()} ${response.config.url}: ${response.status}`);
    return response;
  },
  async (error: AxiosError) => {
    const originalRequest = error.config as InternalAxiosRequestConfig;

    // Handle token expiration
    if (error.response?.status === 401 && !originalRequest._retry) {
      originalRequest._retry = true;

      try {
        const newToken = await refreshToken();
        localStorage.setItem('token', newToken);
        originalRequest.headers.Authorization = `Bearer ${newToken}`;
        return apiClient(originalRequest);
      } catch (refreshError) {
        // Redirect to login
        window.location.href = '/login';
        return Promise.reject(refreshError);
      }
    }

    // Handle other errors
    logger.error(
      `API Error: ${error.config?.url} - ${error.response?.status}`,
      error.response?.data
    );

    return Promise.reject(error);
  }
);

export default apiClient;
```

### 2. Service Layer

```typescript
// services/api/accountService.ts
import apiClient from './apiClient';
import { Account, PaginatedResponse } from '@/types/entities';

export class AccountService {
  /**
   * Fetch paginated accounts for the current user
   */
  static async getAccounts(
    page: number = 1,
    pageSize: number = 20
  ): Promise<PaginatedResponse<Account>> {
    const response = await apiClient.get<PaginatedResponse<Account>>(
      '/api/accounts',
      {
        params: { page, pageSize },
      }
    );
    return response.data;
  }

  /**
   * Fetch account by ID
   */
  static async getAccount(id: string): Promise<Account> {
    const response = await apiClient.get<Account>(`/api/accounts/${id}`);
    return response.data;
  }

  /**
   * Create new account
   */
  static async createAccount(data: Partial<Account>): Promise<Account> {
    const response = await apiClient.post<Account>('/api/accounts', data);
    return response.data;
  }

  /**
   * Update account
   */
  static async updateAccount(
    id: string,
    data: Partial<Account>
  ): Promise<Account> {
    const response = await apiClient.put<Account>(
      `/api/accounts/${id}`,
      data
    );
    return response.data;
  }

  /**
   * Close account
   */
  static async closeAccount(id: string): Promise<void> {
    await apiClient.post(`/api/accounts/${id}/close`);
  }

  /**
   * Get account statements
   */
  static async getStatements(
    accountId: string,
    fromDate: Date,
    toDate: Date
  ): Promise<any> {
    const response = await apiClient.get(`/api/accounts/${accountId}/statements`, {
      params: {
        fromDate: format(fromDate, 'yyyy-MM-dd'),
        toDate: format(toDate, 'yyyy-MM-dd'),
      },
    });
    return response.data;
  }
}
```

### 3. Error Handling

```typescript
// utils/errorHandler.ts
import { ApiError } from '@/types/api';

export class ApiErrorHandler {
  static handle(error: any): {
    message: string;
    code: string;
    details?: Record<string, string>;
  } {
    // Network error
    if (!error.response) {
      return {
        message: 'Network error. Please check your connection.',
        code: 'NETWORK_ERROR',
      };
    }

    // API returned error
    const apiError = error.response.data as ApiError;

    const errorMap: Record<string, string> = {
      INVALID_CREDENTIALS: 'Invalid email or password',
      ACCOUNT_NOT_FOUND: 'Account not found',
      INSUFFICIENT_BALANCE: 'Insufficient balance for this transaction',
      DAILY_LIMIT_EXCEEDED: 'Daily transaction limit exceeded',
      INVALID_BENEFICIARY: 'Invalid beneficiary account',
      KYC_NOT_VERIFIED: 'Please complete KYC verification',
      AML_CHECK_FAILED: 'Transaction blocked due to AML screening',
    };

    return {
      message: errorMap[apiError.code] || apiError.message,
      code: apiError.code,
      details: apiError.details,
    };
  }
}

// Usage in components
try {
  await transferService.initiateTransfer(data);
} catch (error) {
  const { message, code } = ApiErrorHandler.handle(error);
  setError(message);
  logger.error(`Transfer failed: ${code}`, error);
}
```

---

## STATE MANAGEMENT STANDARDS

### 1. Zustand Store Pattern

```typescript
// store/authStore.ts
import { create } from 'zustand';
import { devtools, persist, subscribeWithSelector } from 'zustand/middleware';
import { User } from '@/types/entities';

interface AuthState {
  // State
  user: User | null;
  isAuthenticated: boolean;
  token: string | null;
  isLoading: boolean;
  error: string | null;

  // Actions
  login: (email: string, password: string) => Promise<void>;
  logout: () => void;
  setUser: (user: User) => void;
  refreshToken: () => Promise<void>;
  clearError: () => void;
}

export const useAuthStore = create<AuthState>()(
  devtools(
    persist(
      subscribeWithSelector((set, get) => ({
        // Initial state
        user: null,
        isAuthenticated: false,
        token: null,
        isLoading: false,
        error: null,

        // Actions
        login: async (email: string, password: string) => {
          set({ isLoading: true, error: null });
          try {
            const response = await authService.login(email, password);
            set({
              user: response.user,
              token: response.token,
              isAuthenticated: true,
              isLoading: false,
            });
          } catch (error) {
            set({
              error: (error as Error).message,
              isLoading: false,
            });
            throw error;
          }
        },

        logout: () => {
          set({
            user: null,
            token: null,
            isAuthenticated: false,
          });
          localStorage.removeItem('token');
        },

        setUser: (user: User) => {
          set({ user, isAuthenticated: true });
        },

        refreshToken: async () => {
          try {
            const newToken = await authService.refreshToken();
            set({ token: newToken });
          } catch (error) {
            get().logout();
            throw error;
          }
        },

        clearError: () => set({ error: null }),
      })),
      {
        name: 'auth-store', // localStorage key
        partialize: (state) => ({
          user: state.user,
          isAuthenticated: state.isAuthenticated,
          token: state.token,
        }),
      }
    )
  )
);

// Usage
const LoginPage = () => {
  const { login, isLoading, error } = useAuthStore();

  const handleLogin = async (email: string, password: string) => {
    await login(email, password);
  };

  return (
    <LoginForm
      onSubmit={handleLogin}
      isLoading={isLoading}
      error={error}
    />
  );
};
```

### 2. Multiple Stores

```typescript
// store/index.ts
export { useAuthStore } from './authStore';
export { useAccountStore } from './accountStore';
export { useTransferStore } from './transferStore';
export { useNotificationStore } from './notificationStore';
export { useUIStore } from './uiStore';

// store/accountStore.ts
interface AccountState {
  accounts: Account[];
  selectedAccount: Account | null;
  isLoading: boolean;
  error: string | null;

  fetchAccounts: () => Promise<void>;
  selectAccount: (account: Account) => void;
  addAccount: (account: Account) => void;
}

export const useAccountStore = create<AccountState>()((set) => ({
  accounts: [],
  selectedAccount: null,
  isLoading: false,
  error: null,

  fetchAccounts: async () => {
    set({ isLoading: true });
    try {
      const accounts = await accountService.getAccounts();
      set({ accounts, isLoading: false });
    } catch (error) {
      set({ error: (error as Error).message, isLoading: false });
    }
  },

  selectAccount: (account: Account) => {
    set({ selectedAccount: account });
  },

  addAccount: (account: Account) => {
    set((state) => ({
      accounts: [...state.accounts, account],
    }));
  },
}));
```

---

## FORM HANDLING STANDARDS

### 1. React Hook Form Integration

```typescript
// components/forms/TransferForm.tsx
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';

const transferSchema = z.object({
  fromAccountId: z.string().min(1, 'Select source account'),
  toAccountNumber: z.string().regex(/^\d{16}$/, 'Invalid account number'),
  amount: z.number()
    .min(1, 'Amount must be at least 1')
    .max(1000000, 'Amount cannot exceed limit'),
  description: z.string().max(100, 'Description too long').optional(),
  mfaCode: z.string().length(6, 'MFA code must be 6 digits'),
});

type TransferFormData = z.infer<typeof transferSchema>;

interface TransferFormProps {
  onSubmit: (data: TransferFormData) => Promise<void>;
}

export const TransferForm: React.FC<TransferFormProps> = ({ onSubmit }) => {
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
    reset,
  } = useForm<TransferFormData>({
    resolver: zodResolver(transferSchema),
  });

  const onSubmitHandler = async (data: TransferFormData) => {
    try {
      await onSubmit(data);
      reset();
    } catch (error) {
      logger.error('Transfer failed:', error);
    }
  };

  return (
    <form onSubmit={handleSubmit(onSubmitHandler)}>
      <div className="form-field">
        <label htmlFor="fromAccountId">From Account</label>
        <select id="fromAccountId" {...register('fromAccountId')}>
          <option value="">Select account</option>
          {/* Account options */}
        </select>
        {errors.fromAccountId && (
          <span className="error">{errors.fromAccountId.message}</span>
        )}
      </div>

      <div className="form-field">
        <label htmlFor="toAccountNumber">To Account Number</label>
        <input
          id="toAccountNumber"
          type="text"
          placeholder="1234567890123456"
          {...register('toAccountNumber')}
        />
        {errors.toAccountNumber && (
          <span className="error">{errors.toAccountNumber.message}</span>
        )}
      </div>

      <div className="form-field">
        <label htmlFor="amount">Amount</label>
        <input
          id="amount"
          type="number"
          step="0.01"
          {...register('amount', { valueAsNumber: true })}
        />
        {errors.amount && (
          <span className="error">{errors.amount.message}</span>
        )}
      </div>

      <button type="submit" disabled={isSubmitting}>
        {isSubmitting ? 'Processing...' : 'Transfer'}
      </button>
    </form>
  );
};
```

---

## ERROR HANDLING & LOGGING

### 1. Global Error Handler

```typescript
// utils/errorHandler.ts
export class ErrorHandler {
  static handle(error: any): void {
    // Log to console in development
    if (process.env.NODE_ENV === 'development') {
      console.error('Error:', error);
    }

    // Log to Sentry in production
    if (process.env.NODE_ENV === 'production') {
      Sentry.captureException(error);
    }

    // Specific error handling
    if (error instanceof NetworkError) {
      this.handleNetworkError(error);
    } else if (error instanceof ValidationError) {
      this.handleValidationError(error);
    } else if (error instanceof ApiError) {
      this.handleApiError(error);
    } else {
      this.handleGenericError(error);
    }
  }

  private static handleNetworkError(error: NetworkError): void {
    logger.error('Network error', { url: error.url, status: error.status });
  }

  private static handleValidationError(error: ValidationError): void {
    logger.warn('Validation error', { field: error.field, message: error.message });
  }

  private static handleApiError(error: ApiError): void {
    logger.error('API error', { code: error.code, message: error.message });
  }

  private static handleGenericError(error: Error): void {
    logger.error('Generic error', { message: error.message, stack: error.stack });
  }
}
```

### 2. Logger Configuration

```typescript
// utils/logger.ts
export class Logger {
  static info(message: string, context?: any): void {
    console.log(`[INFO] ${message}`, context);
  }

  static warn(message: string, context?: any): void {
    console.warn(`[WARN] ${message}`, context);
  }

  static error(message: string, context?: any): void {
    console.error(`[ERROR] ${message}`, context);
    // Send to Sentry
    Sentry.captureMessage(message, 'error');
  }

  static debug(message: string, context?: any): void {
    if (process.env.NODE_ENV === 'development') {
      console.debug(`[DEBUG] ${message}`, context);
    }
  }
}

export const logger = Logger;
```

---

## PERFORMANCE STANDARDS

### 1. Lazy Loading Components

```typescript
// pages/accounts/index.tsx
import dynamic from 'next/dynamic';

const AccountsList = dynamic(
  () => import('@/components/accounts/AccountsList'),
  {
    loading: () => <LoadingSpinner />,
    ssr: true,
  }
);

export default function AccountsPage() {
  return <AccountsList />;
}
```

### 2. Code Splitting

```typescript
// routes/index.tsx
import { lazy, Suspense } from 'react';

const Dashboard = lazy(() => import('./Dashboard'));
const Accounts = lazy(() => import('./Accounts'));
const Transfers = lazy(() => import('./Transfers'));

export const Routes = () => (
  <Suspense fallback={<LoadingSpinner />}>
    {/* Routes */}
  </Suspense>
);
```

### 3. Image Optimization

```typescript
import Image from 'next/image';

// ✅ GOOD: Using Next.js Image component
<Image
  src="/bank-logo.png"
  alt="Bank Logo"
  width={200}
  height={100}
  priority
  loading="eager"
/>

// ❌ BAD: Using HTML img tag (no optimization)
<img src="/bank-logo.png" alt="Bank Logo" />
```

---

## SECURITY STANDARDS

### 1. CSRF Token

```typescript
// middleware/csrf.ts
export const getCsrfToken = (): string => {
  const meta = document.querySelector('meta[name="csrf-token"]');
  return meta?.getAttribute('content') || '';
};

// Add CSRF token to all requests
apiClient.interceptors.request.use((config) => {
  if (config.method !== 'get') {
    config.headers['X-CSRF-Token'] = getCsrfToken();
  }
  return config;
});
```

### 2. Input Sanitization

```typescript
// utils/sanitizer.ts
import DOMPurify from 'dompurify';

export const sanitizeHtml = (dirty: string): string => {
  return DOMPurify.sanitize(dirty);
};

export const sanitizeInput = (input: string): string => {
  return input.trim().replace(/[<>]/g, '');
};
```

### 3. PII Masking

```typescript
// utils/masking.ts
export const maskAccountNumber = (accountNumber: string): string => {
  return accountNumber.replace(/(\d)(?=\d{4})/g, '*');
  // 1234567890123456 -> ***************456
};

export const maskPan = (pan: string): string => {
  return pan.replace(/(\w)(?=\w{4})/g, '*');
  // AAAPA1234B -> ****PA1234B
};

export const maskEmail = (email: string): string => {
  const [local, domain] = email.split('@');
  const maskedLocal = local[0] + '*'.repeat(local.length - 2) + local[local.length - 1];
  return `${maskedLocal}@${domain}`;
  // user@example.com -> u***@example.com
};
```

---

## TESTING STANDARDS

### 1. Component Testing

```typescript
// components/LoginForm.test.tsx
import { render, screen, userEvent } from '@testing-library/react';
import { LoginForm } from './LoginForm';

describe('LoginForm', () => {
  it('should render login form', () => {
    const onSubmit = jest.fn();
    render(<LoginForm onSubmit={onSubmit} />);

    expect(screen.getByLabelText('Email address')).toBeInTheDocument();
    expect(screen.getByLabelText('Password')).toBeInTheDocument();
  });

  it('should call onSubmit with credentials', async () => {
    const onSubmit = jest.fn().mockResolvedValue(undefined);
    render(<LoginForm onSubmit={onSubmit} />);

    const emailInput = screen.getByLabelText('Email address') as HTMLInputElement;
    const passwordInput = screen.getByLabelText('Password') as HTMLInputElement;
    const submitButton = screen.getByText('Login');

    await userEvent.type(emailInput, 'user@example.com');
    await userEvent.type(passwordInput, 'password123');
    await userEvent.click(submitButton);

    expect(onSubmit).toHaveBeenCalledWith({
      email: 'user@example.com',
      password: 'password123',
    });
  });

  it('should display error message on failed submission', async () => {
    const onSubmit = jest.fn().mockRejectedValue(new Error('Invalid credentials'));
    render(<LoginForm onSubmit={onSubmit} />);

    await userEvent.click(screen.getByText('Login'));

    expect(await screen.findByText('Login failed')).toBeInTheDocument();
  });
});
```

### 2. Hook Testing

```typescript
// hooks/useApi.test.ts
import { renderHook, waitFor } from '@testing-library/react';
import { useApi } from './useApi';

describe('useApi', () => {
  it('should fetch data', async () => {
    const { result } = renderHook(() => useApi<Account[]>('/api/accounts'));

    expect(result.current.loading).toBe(true);

    await waitFor(() => {
      expect(result.current.loading).toBe(false);
    });

    expect(result.current.data).toEqual(mockAccounts);
  });

  it('should handle errors', async () => {
    const { result } = renderHook(() => useApi<Account[]>('/api/invalid'));

    await waitFor(() => {
      expect(result.current.error).toBeDefined();
    });
  });
});
```

---

## CODE QUALITY METRICS

### Target Metrics

| Metric | Target | Enforcement |
|--------|--------|-------------|
| Code Coverage | 80%+ | Pre-commit |
| Cyclomatic Complexity | <10 per function | ESLint |
| TypeScript Strict | 100% | tsconfig |
| Type Coverage | 95%+ | Type Coverage Tool |
| Bundle Size | <500KB (gzipped) | Bundlesize |
| Lighthouse Score | 90+ | CI/CD |
| Security Audit | 0 vulnerabilities | npm audit |

### ESLint Configuration

```json
// .eslintrc.json
{
  "extends": ["next/core-web-vitals", "plugin:@typescript-eslint/recommended"],
  "rules": {
    "no-console": ["warn", { "allow": ["warn", "error"] }],
    "no-debugger": "error",
    "no-var": "error",
    "prefer-const": "error",
    "@typescript-eslint/no-explicit-any": "error",
    "@typescript-eslint/explicit-function-return-types": "warn",
    "react/react-in-jsx-scope": "off",
    "react-hooks/rules-of-hooks": "error",
    "react-hooks/exhaustive-deps": "warn"
  }
}
```

---

## SUMMARY TABLE: Common Patterns

| Task | Pattern | File Location |
|------|---------|----------------|
| **API Fetching** | useApi hook | hooks/useApi.ts |
| **Form Handling** | React Hook Form + Zod | components/forms/*.tsx |
| **Authentication** | Zustand store | store/authStore.ts |
| **Component Composition** | Presentational + Container | components/*/ |
| **Type Safety** | TypeScript interfaces | types/*.ts |
| **Error Handling** | ErrorHandler class | utils/errorHandler.ts |
| **Logging** | Logger class | utils/logger.ts |
| **State Management** | Zustand store | store/*.ts |
| **API Integration** | Service classes | services/api/*.ts |
| **Security** | CSRF tokens, sanitization | utils/*, middleware/* |

---

This comprehensive guide ensures:
✅ Type safety (100% TypeScript strict mode)
✅ Best practices (React 18, Next.js 14)
✅ Security (CSRF, sanitization, PII masking)
✅ Performance (code splitting, lazy loading)
✅ Maintainability (consistent patterns, clear structure)
✅ Testing (80%+ coverage)
✅ Scalability (enterprise-grade architecture)

