export interface ApiEnvelope<T> {
  code: 'OK';
  msg: string;
  data: T;
  traceId: string;
}

export interface FieldError {
  field: string;
  message: string;
}

export interface ProblemDetail {
  type: string;
  title: string;
  status: number;
  detail: string;
  instance: string;
  code: string;
  traceId: string;
  errors?: FieldError[];
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface RegisterRequest {
  username: string;
  password: string;
}

export interface LoginData {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
}

export type UserRole = 'USER' | 'ADMIN';

export const USER_ROLES: readonly UserRole[] = ['USER', 'ADMIN'];

export type UserStatus = 'ACTIVE' | 'DISABLED';

export const USER_STATUSES: readonly UserStatus[] = ['ACTIVE', 'DISABLED'];

export interface CurrentUserData {
  userId: string;
  username: string;
  status: UserStatus;
  roles: UserRole[];
  accessTokenExpiresIn: number;
}

/** 登录表单沿用契约 `LoginRequest.username` 的 1–100 字符宽松口径，注册才收紧到 32 字符。 */
export const LOGIN_USERNAME_MAX_LENGTH = 100;

export const REGISTER_USERNAME_MIN_LENGTH = 3;
export const REGISTER_USERNAME_MAX_LENGTH = 32;
export const REGISTER_USERNAME_PATTERN = /^[A-Za-z0-9_.-]{3,32}$/;

export const PASSWORD_MIN_LENGTH = 10;
export const PASSWORD_MAX_LENGTH = 72;
export const PASSWORD_PATTERN = /^(?=.*[A-Za-z])(?=.*\d).{10,72}$/;
