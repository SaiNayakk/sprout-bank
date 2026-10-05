-- What a movement was for, as its sender named it (a payout's reference), so the receiving business can
-- match money that arrived to what it was expecting. Movements from before this have none.
ALTER TABLE transactions ADD COLUMN reference text;
CREATE INDEX transactions_by_reference ON transactions (vpa, reference) WHERE reference IS NOT NULL;
