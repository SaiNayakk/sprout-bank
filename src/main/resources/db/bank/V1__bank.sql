-- Sprout Bank: a simulated bank. Customers and partner businesses have accounts; money only moves
-- between accounts here, so the bank's total never changes except when a customer opens an account.

CREATE TABLE accounts (
    vpa              text PRIMARY KEY,
    user_id          uuid UNIQUE,                 -- null for partner businesses
    partner          text UNIQUE,                 -- null for customers
    holder_name      text NOT NULL,
    balance_paise    bigint NOT NULL CHECK (balance_paise >= 0),
    pin_hash         text,
    pin_failures     int NOT NULL DEFAULT 0,
    pin_locked_until timestamptz,
    opened_at        timestamptz NOT NULL
);

CREATE TABLE collect_requests (
    id           uuid PRIMARY KEY,
    partner      text NOT NULL,
    reference    text NOT NULL,
    payer_vpa    text NOT NULL REFERENCES accounts (vpa),
    payee_vpa    text NOT NULL REFERENCES accounts (vpa),
    amount_paise bigint NOT NULL CHECK (amount_paise > 0),
    note         text,
    callback_url text NOT NULL,
    status       text NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'DECLINED', 'EXPIRED')),
    created_at   timestamptz NOT NULL,
    expires_at   timestamptz NOT NULL,
    decided_at   timestamptz,
    UNIQUE (partner, reference)
);
CREATE INDEX collect_requests_payer ON collect_requests (payer_vpa, created_at DESC);
CREATE INDEX collect_requests_pending ON collect_requests (expires_at) WHERE status = 'PENDING';

CREATE TABLE payouts (
    id           uuid PRIMARY KEY,
    partner      text NOT NULL,
    reference    text NOT NULL,
    payee_vpa    text NOT NULL REFERENCES accounts (vpa),
    amount_paise bigint NOT NULL CHECK (amount_paise > 0),
    status       text NOT NULL CHECK (status IN ('COMPLETED')),
    created_at   timestamptz NOT NULL,
    UNIQUE (partner, reference)
);

CREATE TABLE transactions (
    id                  uuid PRIMARY KEY,
    vpa                 text NOT NULL REFERENCES accounts (vpa),
    direction           text NOT NULL CHECK (direction IN ('IN', 'OUT')),
    amount_paise        bigint NOT NULL CHECK (amount_paise > 0),
    description         text NOT NULL,
    counterparty        text,
    balance_after_paise bigint NOT NULL,
    at                  timestamptz NOT NULL
);
CREATE INDEX transactions_by_vpa ON transactions (vpa, at DESC);

-- Callbacks to partners, written in the same transaction as the change they report, and delivered
-- until acknowledged (the transactional outbox).
CREATE TABLE outbox (
    id              uuid PRIMARY KEY,
    partner         text NOT NULL,
    callback_url    text NOT NULL,
    body            text NOT NULL,
    created_at      timestamptz NOT NULL,
    attempts        int NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    delivered_at    timestamptz,
    last_error      text
);
CREATE INDEX outbox_due ON outbox (next_attempt_at) WHERE delivered_at IS NULL;
