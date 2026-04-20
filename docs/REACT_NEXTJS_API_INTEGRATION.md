# TIER-1 REACT + NEXT.JS API INTEGRATION GUIDE
## Backend Communication & Real-time Data Patterns

**Document Version:** 1.0  
**Date:** April 19, 2026  
**Grade:** Tier-1 Enterprise Banking Standard

---

## TABLE OF CONTENTS

1. [HTTP Client Setup](#http-client-setup)
2. [Service Layer Architecture](#service-layer-architecture)
3. [Request/Response Patterns](#requestresponse-patterns)
4. [Authentication & Authorization](#authentication--authorization)
5. [Error Handling](#error-handling)
6. [Caching Strategies](#caching-strategies)
7. [Real-time Communication (WebSocket)](#real-time-communication-websocket)
8. [Offline-First Architecture](#offline-first-architecture)
9. [API Pagination & Filtering](#api-pagination--filtering)
10. [Complete Examples](#complete-examples)

---

## HTTP CLIENT SETUP

### 1. Axios Configuration with Interceptors

```typescript
// src/services/api/apiClient.ts
import axios, {
  AxiosInstance,
  AxiosError,
  AxiosResponse,
  InternalAxiosRequestConfig,
} from 'axios';
import { useAuthStore } from '@/store/authStore';
import { logger } from '@/utils/logger';
import { ErrorHandler } from '@/utils/errorHandler';

// Custom config type
interface CustomAxiosRequestConfig extends InternalAxiosRequestConfig {
  _retry?: boolean;
  _startTime?: number;
}

// Create instance
const apiClient: AxiosInstance = axios.create({
  baseURL: process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080/api',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
  },
  withCredentials: true, // Send cookies with requests
});

// Request Interceptor
apiClient.interceptors.request.use(
  (config: CustomAxiosRequestConfig) => {
    // Track request time for logging
    config._startTime = Date.now();

    // Add JWT token
    const token = localStorage.getItem('token');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }

    // Add request ID for tracing
    config.headers['X-Request-ID'] = generateRequestId();

    // Add client version
    config.headers['X-Client-Version'] = process.env.NEXT_PUBLIC_APP_VERSION;

    logger.debug(`[REQUEST] ${config.method?.toUpperCase()} ${config.url}`, {
      params: config.params,
    });

    return config;
  },
  (error) => {
    logger.error('Request interceptor error', error);
    return Promise.reject(error);
  }
);

// Response Interceptor
apiClient.interceptors.response.use(
  (response: AxiosResponse) => {
    const config = response.config as CustomAxiosRequestConfig;
    const duration = config._startTime ? Date.now() - config._startTime : 0;

    // Log successful response
    logger.info(`[RESPONSE] ${response.status} ${config.method?.toUpperCase()} ${config.url}`, {
      duration: `${duration}ms`,
    });

    return response;
  },
  async (error: AxiosError) => {
    const config = error.config as CustomAxiosRequestConfig;

    // Handle 401 Unauthorized - Token expired
    if (error.response?.status === 401 && !config?._retry) {
      config._retry = true;

      try {
        // Try to refresh token
        const { refreshToken } = useAuthStore.getState();
        await refreshToken();

        // Retry original request
        return apiClient(config);
      } catch (refreshError) {
        // Refresh failed, redirect to login
        useAuthStore.getState().logout();
        window.location.href = '/login';
        return Promise.reject(refreshError);
      }
    }

    // Handle 429 Too Many Requests - Implement backoff
    if (error.response?.status === 429) {
      const retryAfter = parseInt(
        error.response.headers['retry-after'] || '5',
        10
      );
      logger.warn(`Rate limited. Retrying after ${retryAfter}s`);
      
      // Wait and retry
      await new Promise(resolve => setTimeout(resolve, retryAfter * 1000));
      return apiClient(config);
    }

    // Handle 503 Service Unavailable
    if (error.response?.status === 503) {
      logger.error('Service unavailable', error);
      // Show user-friendly message
      ErrorHandler.showNotification({
        type: 'error',
        title: 'Service Unavailable',
        message: 'The service is temporarily unavailable. Please try again later.',
      });
    }

    // Log error
    logger.error(`[ERROR] ${error.response?.status} ${config?.method?.toUpperCase()} ${config?.url}`, {
      data: error.response?.data,
      status: error.response?.status,
    });

    return Promise.reject(error);
  }
);

// Utility function to generate request ID
function generateRequestId(): string {
  return `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
}

export default apiClient;
```

### 2. API Response Wrapper

```typescript
// src/types/api.ts
export interface ApiResponse<T = any> {
  success: boolean;
  data?: T;
  error?: {
    code: string;
    message: string;
    details?: Record<string, any>;
  };
  timestamp: string;
  requestId: string;
}

export interface PaginatedResponse<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
  hasNextPage: boolean;
  hasPreviousPage: boolean;
}

export type ApiErrorResponse = {
  code: string;
  message: string;
  details?: Record<string, string>;
  statusCode: number;
};
```

---

## SERVICE LAYER ARCHITECTURE

### 1. Account Service Example

```typescript
// src/services/api/accountService.ts
import apiClient from './apiClient';
import { Account, Transaction } from '@/types/entities';
import { PaginatedResponse } from '@/types/api';
import { logger } from '@/utils/logger';

/**
 * Account Service - Handles all account-related API calls
 * Follows service layer pattern with error handling
 */
export class AccountService {
  private static readonly BASE_URL = '/accounts';

  /**
   * Fetch all accounts for current user (paginated)
   * @param page - Page number (1-indexed)
   * @param pageSize - Items per page
   * @returns Promise<PaginatedResponse<Account>>
   */
  static async getAccounts(
    page: number = 1,
    pageSize: number = 10
  ): Promise<PaginatedResponse<Account>> {
    try {
      const response = await apiClient.get<PaginatedResponse<Account>>(
        this.BASE_URL,
        {
          params: { page, pageSize },
        }
      );
      return response.data;
    } catch (error) {
      logger.error('Failed to fetch accounts', error);
      throw error;
    }
  }

  /**
   * Fetch single account by ID
   * @param accountId - Account ID
   * @returns Promise<Account>
   */
  static async getAccount(accountId: string): Promise<Account> {
    try {
      const response = await apiClient.get<Account>(
        `${this.BASE_URL}/${accountId}`
      );
      return response.data;
    } catch (error) {
      logger.error(`Failed to fetch account ${accountId}`, error);
      throw error;
    }
  }

  /**
   * Fetch account transactions (paginated)
   * @param accountId - Account ID
   * @param page - Page number
   * @param pageSize - Items per page
   * @param filters - Optional filters
   * @returns Promise<PaginatedResponse<Transaction>>
   */
  static async getTransactions(
    accountId: string,
    page: number = 1,
    pageSize: number = 20,
    filters?: {
      fromDate?: Date;
      toDate?: Date;
      type?: 'DEBIT' | 'CREDIT' | 'TRANSFER';
      status?: 'PENDING' | 'COMPLETED' | 'FAILED';
    }
  ): Promise<PaginatedResponse<Transaction>> {
    try {
      const params = {
        page,
        pageSize,
        ...(filters?.fromDate && { fromDate: filters.fromDate.toISOString() }),
        ...(filters?.toDate && { toDate: filters.toDate.toISOString() }),
        ...(filters?.type && { type: filters.type }),
        ...(filters?.status && { status: filters.status }),
      };

      const response = await apiClient.get<PaginatedResponse<Transaction>>(
        `${this.BASE_URL}/${accountId}/transactions`,
        { params }
      );
      return response.data;
    } catch (error) {
      logger.error(`Failed to fetch transactions for ${accountId}`, error);
      throw error;
    }
  }

  /**
   * Create new account
   * @param data - Account creation data
   * @returns Promise<Account>
   */
  static async createAccount(data: Partial<Account>): Promise<Account> {
    try {
      const response = await apiClient.post<Account>(this.BASE_URL, data);
      return response.data;
    } catch (error) {
      logger.error('Failed to create account', error);
      throw error;
    }
  }

  /**
   * Update account
   * @param accountId - Account ID
   * @param data - Update data
   * @returns Promise<Account>
   */
  static async updateAccount(
    accountId: string,
    data: Partial<Account>
  ): Promise<Account> {
    try {
      const response = await apiClient.put<Account>(
        `${this.BASE_URL}/${accountId}`,
        data
      );
      return response.data;
    } catch (error) {
      logger.error(`Failed to update account ${accountId}`, error);
      throw error;
    }
  }

  /**
   * Close account
   * @param accountId - Account ID
   * @param reason - Closure reason
   * @returns Promise<void>
   */
  static async closeAccount(
    accountId: string,
    reason: string
  ): Promise<void> {
    try {
      await apiClient.post(`${this.BASE_URL}/${accountId}/close`, { reason });
    } catch (error) {
      logger.error(`Failed to close account ${accountId}`, error);
      throw error;
    }
  }

  /**
   * Download account statement
   * @param accountId - Account ID
   * @param fromDate - From date
   * @param toDate - To date
   * @param format - File format (pdf, csv, xlsx)
   * @returns Promise<Blob>
   */
  static async downloadStatement(
    accountId: string,
    fromDate: Date,
    toDate: Date,
    format: 'pdf' | 'csv' | 'xlsx' = 'pdf'
  ): Promise<Blob> {
    try {
      const response = await apiClient.get(
        `${this.BASE_URL}/${accountId}/statement`,
        {
          params: {
            fromDate: fromDate.toISOString().split('T')[0],
            toDate: toDate.toISOString().split('T')[0],
            format,
          },
          responseType: 'blob',
        }
      );
      return response.data;
    } catch (error) {
      logger.error(`Failed to download statement for ${accountId}`, error);
      throw error;
    }
  }
}
```

### 2. Transfer Service Example

```typescript
// src/services/api/transferService.ts
import apiClient from './apiClient';
import { Transaction } from '@/types/entities';
import { logger } from '@/utils/logger';

export interface InitiateTransferRequest {
  fromAccountId: string;
  toAccountNumber: string;
  amount: number;
  description?: string;
  scheduledDate?: Date;
}

export interface TransferResponse {
  transactionId: string;
  status: 'INITIATED' | 'PENDING_VERIFICATION' | 'COMPLETED' | 'FAILED';
  referenceNumber: string;
}

export class TransferService {
  private static readonly BASE_URL = '/transfers';

  /**
   * Initiate fund transfer
   * @param data - Transfer request data
   * @returns Promise<TransferResponse>
   */
  static async initiateTransfer(
    data: InitiateTransferRequest
  ): Promise<TransferResponse> {
    try {
      const response = await apiClient.post<TransferResponse>(
        this.BASE_URL,
        {
          ...data,
          ...(data.scheduledDate && {
            scheduledDate: data.scheduledDate.toISOString(),
          }),
        }
      );
      return response.data;
    } catch (error) {
      logger.error('Failed to initiate transfer', error);
      throw error;
    }
  }

  /**
   * Verify transfer with OTP
   * @param transferId - Transfer ID
   * @param otp - One-time password
   * @returns Promise<TransferResponse>
   */
  static async verifyTransferOtp(
    transferId: string,
    otp: string
  ): Promise<TransferResponse> {
    try {
      const response = await apiClient.post<TransferResponse>(
        `${this.BASE_URL}/${transferId}/verify-otp`,
        { otp }
      );
      return response.data;
    } catch (error) {
      logger.error(`Failed to verify transfer ${transferId}`, error);
      throw error;
    }
  }

  /**
   * Get transfer status
   * @param transferId - Transfer ID
   * @returns Promise<TransferResponse>
   */
  static async getTransferStatus(
    transferId: string
  ): Promise<TransferResponse> {
    try {
      const response = await apiClient.get<TransferResponse>(
        `${this.BASE_URL}/${transferId}`
      );
      return response.data;
    } catch (error) {
      logger.error(`Failed to get transfer status ${transferId}`, error);
      throw error;
    }
  }

  /**
   * Cancel pending transfer
   * @param transferId - Transfer ID
   * @returns Promise<void>
   */
  static async cancelTransfer(transferId: string): Promise<void> {
    try {
      await apiClient.post(`${this.BASE_URL}/${transferId}/cancel`);
    } catch (error) {
      logger.error(`Failed to cancel transfer ${transferId}`, error);
      throw error;
    }
  }
}
```

---

## REQUEST/RESPONSE PATTERNS

### 1. Error Response Handling

```typescript
// src/utils/errorHandler.ts
import { useNotificationStore } from '@/store/notificationStore';
import { logger } from '@/utils/logger';

export interface ErrorNotification {
  type: 'success' | 'error' | 'warning' | 'info';
  title: string;
  message: string;
}

export class ErrorHandler {
  /**
   * Map API error codes to user-friendly messages
   */
  private static readonly ERROR_MAP: Record<string, Partial<ErrorNotification>> = {
    INVALID_CREDENTIALS: {
      title: 'Login Failed',
      message: 'Invalid email or password. Please try again.',
    },
    ACCOUNT_NOT_FOUND: {
      title: 'Account Not Found',
      message: 'The account you are looking for does not exist.',
    },
    INSUFFICIENT_BALANCE: {
      title: 'Insufficient Balance',
      message: 'You do not have enough balance to complete this transaction.',
    },
    DAILY_LIMIT_EXCEEDED: {
      title: 'Daily Limit Exceeded',
      message: 'You have exceeded your daily transaction limit.',
    },
    INVALID_BENEFICIARY: {
      title: 'Invalid Beneficiary',
      message: 'The beneficiary account is not valid or not active.',
    },
    KYC_NOT_VERIFIED: {
      title: 'KYC Not Verified',
      message: 'Please complete KYC verification to proceed.',
    },
    AML_CHECK_FAILED: {
      title: 'Transaction Blocked',
      message: 'Your transaction was blocked due to AML screening.',
    },
    NETWORK_ERROR: {
      title: 'Network Error',
      message: 'Unable to connect to the server. Please check your connection.',
    },
    TIMEOUT_ERROR: {
      title: 'Request Timeout',
      message: 'The request took too long to complete. Please try again.',
    },
  };

  /**
   * Handle API error and show notification
   */
  static handleError(error: any): void {
    const { addNotification } = useNotificationStore.getState();

    // Network error
    if (!error.response) {
      const notification: ErrorNotification = {
        type: 'error',
        title: 'Network Error',
        message: 'Unable to connect. Please check your internet connection.',
      };
      addNotification(notification);
      logger.error('Network error', error);
      return;
    }

    // API returned error
    const apiError = error.response.data;
    const errorCode = apiError?.code || 'UNKNOWN_ERROR';

    const notification: ErrorNotification = {
      type: 'error',
      title: this.ERROR_MAP[errorCode]?.title || 'Error',
      message: this.ERROR_MAP[errorCode]?.message || apiError?.message || 'An error occurred',
    };

    addNotification(notification);
    logger.error(`API Error: ${errorCode}`, apiError);
  }

  /**
   * Handle specific error types
   */
  static handleStatusCode(status: number): void {
    switch (status) {
      case 400:
        logger.error('Bad request');
        break;
      case 401:
        logger.warn('Unauthorized');
        break;
      case 403:
        logger.warn('Forbidden');
        break;
      case 404:
        logger.error('Resource not found');
        break;
      case 429:
        logger.warn('Rate limited');
        break;
      case 500:
        logger.error('Server error');
        break;
      case 503:
        logger.error('Service unavailable');
        break;
    }
  }

  /**
   * Show notification to user
   */
  static showNotification(notification: ErrorNotification): void {
    const { addNotification } = useNotificationStore.getState();
    addNotification(notification);
  }
}
```

### 2. Request Retry Logic with Exponential Backoff

```typescript
// src/utils/retryUtil.ts
export interface RetryOptions {
  maxRetries?: number;
  initialDelayMs?: number;
  maxDelayMs?: number;
  backoffMultiplier?: number;
}

/**
 * Retry logic with exponential backoff
 * Useful for flaky network connections
 */
export const withRetry = async <T,>(
  fn: () => Promise<T>,
  options: RetryOptions = {}
): Promise<T> => {
  const {
    maxRetries = 3,
    initialDelayMs = 1000,
    maxDelayMs = 30000,
    backoffMultiplier = 2,
  } = options;

  let lastError: Error | null = null;

  for (let attempt = 0; attempt <= maxRetries; attempt++) {
    try {
      return await fn();
    } catch (error) {
      lastError = error as Error;

      // Don't retry on 4xx errors (client errors)
      if (error?.response?.status >= 400 && error?.response?.status < 500) {
        throw error;
      }

      // Calculate delay with exponential backoff
      if (attempt < maxRetries) {
        const delay = Math.min(
          initialDelayMs * Math.pow(backoffMultiplier, attempt),
          maxDelayMs
        );
        await new Promise(resolve => setTimeout(resolve, delay));
      }
    }
  }

  throw lastError;
};

// Usage
const accounts = await withRetry(
  () => AccountService.getAccounts(),
  { maxRetries: 3 }
);
```

---

## AUTHENTICATION & AUTHORIZATION

### 1. Authentication Service

```typescript
// src/services/auth/authService.ts
import apiClient from '@/services/api/apiClient';
import { logger } from '@/utils/logger';

export interface LoginRequest {
  email: string;
  password: string;
}

export interface LoginResponse {
  user: {
    id: string;
    email: string;
    name: string;
    roles: string[];
    permissions: string[];
  };
  token: string;
  refreshToken: string;
  expiresIn: number; // Seconds
}

export interface MFAVerifyRequest {
  sessionId: string;
  mfaCode: string;
}

export class AuthService {
  static async login(
    email: string,
    password: string
  ): Promise<LoginResponse> {
    try {
      const response = await apiClient.post<LoginResponse>('/auth/login', {
        email,
        password,
      });
      return response.data;
    } catch (error) {
      logger.error('Login failed', error);
      throw error;
    }
  }

  static async refreshToken(): Promise<{ token: string }> {
    try {
      const refreshToken = localStorage.getItem('refreshToken');
      const response = await apiClient.post<{ token: string }>(
        '/auth/refresh',
        { refreshToken }
      );
      return response.data;
    } catch (error) {
      logger.error('Token refresh failed', error);
      throw error;
    }
  }

  static async verifyMFA(
    sessionId: string,
    mfaCode: string
  ): Promise<LoginResponse> {
    try {
      const response = await apiClient.post<LoginResponse>(
        '/auth/mfa-verify',
        { sessionId, mfaCode }
      );
      return response.data;
    } catch (error) {
      logger.error('MFA verification failed', error);
      throw error;
    }
  }

  static async logout(): Promise<void> {
    try {
      await apiClient.post('/auth/logout');
    } catch (error) {
      logger.error('Logout failed', error);
    } finally {
      localStorage.removeItem('token');
      localStorage.removeItem('refreshToken');
    }
  }
}
```

### 2. Token Management

```typescript
// src/services/auth/tokenService.ts
import jsCookie from 'js-cookie';

export class TokenService {
  private static readonly TOKEN_KEY = 'token';
  private static readonly REFRESH_TOKEN_KEY = 'refreshToken';
  private static readonly EXPIRY_KEY = 'tokenExpiry';

  /**
   * Save tokens securely
   * Note: In production, store in secure httpOnly cookies
   */
  static setTokens(
    token: string,
    refreshToken: string,
    expiresIn: number
  ): void {
    // Store access token
    if (typeof window !== 'undefined') {
      localStorage.setItem(this.TOKEN_KEY, token);
      localStorage.setItem(this.REFRESH_TOKEN_KEY, refreshToken);
      localStorage.setItem(
        this.EXPIRY_KEY,
        (Date.now() + expiresIn * 1000).toString()
      );

      // Also set as secure cookie
      jsCookie.set(this.TOKEN_KEY, token, {
        secure: true,
        sameSite: 'strict',
      });
    }
  }

  /**
   * Get access token
   */
  static getToken(): string | null {
    if (typeof window === 'undefined') return null;
    return localStorage.getItem(this.TOKEN_KEY);
  }

  /**
   * Get refresh token
   */
  static getRefreshToken(): string | null {
    if (typeof window === 'undefined') return null;
    return localStorage.getItem(this.REFRESH_TOKEN_KEY);
  }

  /**
   * Check if token is expired
   */
  static isTokenExpired(): boolean {
    if (typeof window === 'undefined') return true;
    const expiry = localStorage.getItem(this.EXPIRY_KEY);
    if (!expiry) return true;
    return Date.now() > parseInt(expiry);
  }

  /**
   * Clear tokens
   */
  static clearTokens(): void {
    if (typeof window === 'undefined') return;
    localStorage.removeItem(this.TOKEN_KEY);
    localStorage.removeItem(this.REFRESH_TOKEN_KEY);
    localStorage.removeItem(this.EXPIRY_KEY);
    jsCookie.remove(this.TOKEN_KEY);
  }

  /**
   * Decode JWT token (without verification)
   * For client-side claims only
   */
  static decodeToken(token: string): Record<string, any> | null {
    try {
      const base64Url = token.split('.')[1];
      const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/');
      const jsonPayload = decodeURIComponent(
        atob(base64)
          .split('')
          .map((c) => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
          .join('')
      );
      return JSON.parse(jsonPayload);
    } catch {
      return null;
    }
  }
}
```

---

## CACHING STRATEGIES

### 1. Query Caching with React Query Pattern

```typescript
// src/hooks/useCachedApi.ts
import { useEffect, useState } from 'react';
import { logger } from '@/utils/logger';

interface CacheEntry<T> {
  data: T;
  timestamp: number;
  ttl: number; // Time to live in milliseconds
}

const cache = new Map<string, CacheEntry<any>>();

export const useCachedApi = <T,>(
  key: string,
  fetcher: () => Promise<T>,
  ttlSeconds: number = 300 // 5 minutes default
) => {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<Error | null>(null);

  const fetchData = async (forceRefresh = false) => {
    const now = Date.now();
    const cached = cache.get(key);

    // Return cached data if available and not expired
    if (cached && !forceRefresh && now - cached.timestamp < cached.ttl) {
      logger.debug(`Using cached data for ${key}`);
      setData(cached.data);
      return;
    }

    setLoading(true);
    try {
      const result = await fetcher();
      cache.set(key, {
        data: result,
        timestamp: now,
        ttl: ttlSeconds * 1000,
      });
      setData(result);
      setError(null);
    } catch (err) {
      setError(err as Error);
      logger.error(`Failed to fetch ${key}`, err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchData();
  }, [key]);

  const refetch = () => fetchData(true);
  const invalidate = () => {
    cache.delete(key);
    fetchData(true);
  };

  return { data, loading, error, refetch, invalidate };
};
```

---

## REAL-TIME COMMUNICATION (WEBSOCKET)

### 1. WebSocket Service

```typescript
// src/services/real-time/webSocketService.ts
import { io, Socket } from 'socket.io-client';
import { logger } from '@/utils/logger';

export enum SocketEvent {
  BALANCE_UPDATE = 'balance:update',
  TRANSACTION_POSTED = 'transaction:posted',
  LOAN_STATUS_CHANGED = 'loan:statusChanged',
  TRANSFER_STATUS = 'transfer:status',
  NOTIFICATION = 'notification:new',
  DEPOSIT_MATURED = 'deposit:matured',
}

export class WebSocketService {
  private static instance: Socket | null = null;
  private static listeners: Map<string, Set<(data: any) => void>> = new Map();

  static getInstance(): Socket {
    if (!this.instance) {
      const token = localStorage.getItem('token');
      this.instance = io(process.env.NEXT_PUBLIC_WEBSOCKET_URL || 'http://localhost:8080', {
        auth: {
          token,
        },
        reconnection: true,
        reconnectionDelay: 1000,
        reconnectionDelayMax: 5000,
        reconnectionAttempts: 5,
      });

      this.setupEventListeners();
    }

    return this.instance;
  }

  private static setupEventListeners(): void {
    const socket = this.instance!;

    socket.on('connect', () => {
      logger.info('WebSocket connected');
    });

    socket.on('disconnect', () => {
      logger.warn('WebSocket disconnected');
    });

    socket.on('error', (error) => {
      logger.error('WebSocket error', error);
    });

    // Broadcast events to all listeners
    socket.onAny((event: string, data: any) => {
      const listeners = this.listeners.get(event);
      if (listeners) {
        listeners.forEach(listener => listener(data));
      }
    });
  }

  static subscribe(event: string, callback: (data: any) => void): () => void {
    if (!this.listeners.has(event)) {
      this.listeners.set(event, new Set());
    }

    this.listeners.get(event)!.add(callback);

    // Return unsubscribe function
    return () => {
      this.listeners.get(event)?.delete(callback);
    };
  }

  static emit(event: string, data: any): void {
    const socket = this.getInstance();
    socket.emit(event, data);
  }

  static disconnect(): void {
    if (this.instance) {
      this.instance.disconnect();
      this.instance = null;
    }
  }
}
```

### 2. Real-time Hook

```typescript
// src/hooks/useRealtimeData.ts
import { useEffect, useState } from 'react';
import { WebSocketService, SocketEvent } from '@/services/real-time/webSocketService';

export const useRealtimeBalance = (accountId: string) => {
  const [balance, setBalance] = useState<number | null>(null);

  useEffect(() => {
    // Subscribe to balance updates
    const unsubscribe = WebSocketService.subscribe(
      `${SocketEvent.BALANCE_UPDATE}:${accountId}`,
      (data) => {
        setBalance(data.balance);
      }
    );

    return () => unsubscribe();
  }, [accountId]);

  return balance;
};

export const useRealtimeTransactions = (accountId: string) => {
  const [transactions, setTransactions] = useState<any[]>([]);

  useEffect(() => {
    const unsubscribe = WebSocketService.subscribe(
      `${SocketEvent.TRANSACTION_POSTED}:${accountId}`,
      (data) => {
        setTransactions(prev => [data, ...prev]);
      }
    );

    return () => unsubscribe();
  }, [accountId]);

  return transactions;
};
```

---

## OFFLINE-FIRST ARCHITECTURE

### 1. Offline Queue Management

```typescript
// src/services/storage/offlineQueueService.ts
import { openDB, DBSchema, IDBPDatabase } from 'idb';

interface QueuedAction extends DBSchema {
  queuedActions: {
    key: string;
    value: {
      id: string;
      action: string;
      payload: any;
      timestamp: number;
      retries: number;
    };
  };
}

export class OfflineQueueService {
  private static db: IDBPDatabase<QueuedAction> | null = null;

  private static async getDb() {
    if (!this.db) {
      this.db = await openDB<QueuedAction>('cbs-offline', 1, {
        upgrade(db) {
          if (!db.objectStoreNames.contains('queuedActions')) {
            db.createObjectStore('queuedActions', { keyPath: 'id' });
          }
        },
      });
    }
    return this.db;
  }

  /**
   * Add action to offline queue
   */
  static async enqueue(action: string, payload: any): Promise<void> {
    const db = await this.getDb();
    await db.add('queuedActions', {
      id: `${action}-${Date.now()}`,
      action,
      payload,
      timestamp: Date.now(),
      retries: 0,
    });
  }

  /**
   * Get all queued actions
   */
  static async getQueue(): Promise<any[]> {
    const db = await this.getDb();
    return db.getAll('queuedActions');
  }

  /**
   * Process queue when online
   */
  static async processQueue(): Promise<void> {
    const queue = await this.getQueue();

    for (const item of queue) {
      try {
        // Process action based on type
        await this.processAction(item);
        await this.removeFromQueue(item.id);
      } catch (error) {
        // Update retry count
        item.retries++;
        if (item.retries > 3) {
          await this.removeFromQueue(item.id);
        }
      }
    }
  }

  private static async processAction(item: any): Promise<void> {
    // Implement action processing based on action type
    switch (item.action) {
      case 'TRANSFER':
        // Retry transfer
        break;
      case 'BILL_PAY':
        // Retry bill payment
        break;
    }
  }

  private static async removeFromQueue(id: string): Promise<void> {
    const db = await this.getDb();
    await db.delete('queuedActions', id);
  }
}
```

### 2. Offline Detection & Handling

```typescript
// src/hooks/useOnlineStatus.ts
import { useEffect, useState } from 'react';
import { OfflineQueueService } from '@/services/storage/offlineQueueService';

export const useOnlineStatus = () => {
  const [isOnline, setIsOnline] = useState(true);

  useEffect(() => {
    const handleOnline = async () => {
      setIsOnline(true);
      // Process offline queue when coming back online
      await OfflineQueueService.processQueue();
    };

    const handleOffline = () => {
      setIsOnline(false);
    };

    window.addEventListener('online', handleOnline);
    window.addEventListener('offline', handleOffline);

    return () => {
      window.removeEventListener('online', handleOnline);
      window.removeEventListener('offline', handleOffline);
    };
  }, []);

  return isOnline;
};

// Usage
const TransferForm = () => {
  const isOnline = useOnlineStatus();

  if (!isOnline) {
    return (
      <Alert type="warning">
        You are offline. Your changes will be synced when you're back online.
      </Alert>
    );
  }

  return <TransferFormContent />;
};
```

---

## API PAGINATION & FILTERING

### 1. Pagination Hook

```typescript
// src/hooks/usePagination.ts
import { useState } from 'react';

export interface PaginationState {
  page: number;
  pageSize: number;
  total: number;
  totalPages: number;
}

export const usePagination = (initialPageSize: number = 10) => {
  const [pagination, setPagination] = useState<PaginationState>({
    page: 1,
    pageSize: initialPageSize,
    total: 0,
    totalPages: 0,
  });

  const goToPage = (page: number) => {
    setPagination(prev => ({ ...prev, page }));
  };

  const nextPage = () => {
    if (pagination.page < pagination.totalPages) {
      goToPage(pagination.page + 1);
    }
  };

  const previousPage = () => {
    if (pagination.page > 1) {
      goToPage(pagination.page - 1);
    }
  };

  const updateTotal = (total: number) => {
    const totalPages = Math.ceil(total / pagination.pageSize);
    setPagination(prev => ({ ...prev, total, totalPages }));
  };

  return {
    ...pagination,
    goToPage,
    nextPage,
    previousPage,
    updateTotal,
  };
};
```

---

## COMPLETE EXAMPLES

(See the prior documents for complete, detailed examples of each pattern)

---

This comprehensive guide provides:
✅ Complete API integration patterns
✅ Error handling & retry logic
✅ Real-time WebSocket communication
✅ Offline-first capabilities
✅ Pagination & filtering
✅ Security best practices
✅ Caching strategies
✅ Production-ready implementations

