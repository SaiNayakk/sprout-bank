-- The key a customer's app sent with a UPI payment, so the same payment sent again (replayed after a failover) is
-- recognised and happens once. Kept on the customer's own debit row, written in the same transaction as the money
-- moving. Nullable with no default, so existing rows are untouched; rows without a key never collide.
ALTER TABLE transactions ADD COLUMN idempotency_key text;
CREATE UNIQUE INDEX transactions_idempotency_key ON transactions (vpa, idempotency_key);
