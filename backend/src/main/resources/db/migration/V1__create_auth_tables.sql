-- V1__create_auth_tables.sql

CREATE TABLE users (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  email VARCHAR(255) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  nickname VARCHAR(30) NOT NULL,
  role ENUM('USER','MASTER') NOT NULL DEFAULT 'USER',
  status ENUM('ACTIVE','SUSPENDED','DELETED') NOT NULL DEFAULT 'ACTIVE',
  last_login_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_users_email (email),
  UNIQUE KEY uq_users_nickname (nickname)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE email_otp (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  email VARCHAR(255) NOT NULL,
  code_hash VARCHAR(100) NOT NULL,
  purpose ENUM('SIGNUP') NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  verified_at DATETIME(6) NULL,

  failed_attempts INT UNSIGNED NOT NULL DEFAULT 0,

  -- resend cooldown (60s) / daily limit(10/day)을 앱에서 판단하기 위한 상태값
  last_sent_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  resend_available_at DATETIME(6) NOT NULL,
  send_count_date DATE NOT NULL,
  send_count INT UNSIGNED NOT NULL DEFAULT 1,

  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_email_otp_email_purpose (email, purpose),
  KEY idx_email_otp_expires_at (expires_at),
  KEY idx_email_otp_verified_at (verified_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE refresh_tokens (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,

  -- refresh token 원문은 저장 금지: SHA-256(hex) 같은 해시만 저장
  token_hash CHAR(64) NOT NULL,

  remember_me TINYINT(1) NOT NULL DEFAULT 0,

  expires_at DATETIME(6) NOT NULL,
  last_used_at DATETIME(6) NULL,
  revoked_at DATETIME(6) NULL,
  revoke_reason VARCHAR(50) NULL,
  
  user_agent VARCHAR(255) NULL,
  ip_address VARCHAR(45) NULL,

  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

  PRIMARY KEY (id),
  UNIQUE KEY uq_refresh_tokens_token_hash (token_hash),
  KEY idx_refresh_tokens_user_id (user_id),
  KEY idx_refresh_tokens_expires_at (expires_at),
  CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
