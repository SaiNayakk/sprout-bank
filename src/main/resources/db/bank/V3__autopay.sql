-- Paying merchants, and UPI AutoPay mandates.

-- The demo merchants customers can pay: fictional shops, with accounts like anyone else's.
ALTER TABLE accounts ADD COLUMN merchant_category text;
INSERT INTO accounts (vpa, holder_name, balance_paise, merchant_category, opened_at) VALUES
    ('monsoonchai@sproutbank', 'Monsoon Chai', 0, 'Food and drink', now()),
    ('tiffinbox@sproutbank', 'Tiffin Box Kitchen', 0, 'Food and drink', now()),
    ('kiranacorner@sproutbank', 'Kirana Corner', 0, 'Groceries', now()),
    ('citymetro@sproutbank', 'City Metro Card', 0, 'Travel', now()),
    ('bookworm@sproutbank', 'Bookworm Books', 0, 'Books', now()),
    ('rechargehub@sproutbank', 'Recharge Hub', 0, 'Bills and recharges', now())
ON CONFLICT (vpa) DO NOTHING;

-- Standing permission a customer gives a partner to debit up to max_amount each time, until revoked.
CREATE TABLE mandates (
    id               uuid PRIMARY KEY,
    partner          text NOT NULL,
    reference        text NOT NULL,
    payer_vpa        text NOT NULL REFERENCES accounts (vpa),
    payee_vpa        text NOT NULL REFERENCES accounts (vpa),
    max_amount_paise bigint NOT NULL CHECK (max_amount_paise > 0),
    purpose          text NOT NULL,
    share_spends     boolean NOT NULL,
    callback_url     text NOT NULL,
    status           text NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'DECLINED', 'EXPIRED', 'REVOKED')),
    created_at       timestamptz NOT NULL,
    expires_at       timestamptz NOT NULL,
    decided_at       timestamptz,
    revoked_at       timestamptz,
    UNIQUE (partner, reference)
);
CREATE INDEX mandates_by_payer ON mandates (payer_vpa, created_at DESC);
CREATE INDEX mandates_pending ON mandates (expires_at) WHERE status = 'PENDING';
CREATE INDEX mandates_sharing ON mandates (payer_vpa) WHERE status = 'ACTIVE' AND share_spends;

CREATE TABLE mandate_debits (
    id           uuid PRIMARY KEY,
    mandate_id   uuid NOT NULL REFERENCES mandates (id),
    partner      text NOT NULL,
    reference    text NOT NULL,
    amount_paise bigint NOT NULL CHECK (amount_paise > 0),
    status       text NOT NULL CHECK (status IN ('COMPLETED')),
    created_at   timestamptz NOT NULL,
    UNIQUE (partner, reference)
);
