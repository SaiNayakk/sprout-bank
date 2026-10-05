# sprout-bank

**Sprout Bank is not part of Sprout.** It simulates a customer's own bank and its UPI app, so money can
move end to end without real money. It lives in the "street" host with the other outside parties (the
exchange, clearing, the depository) as they arrive.

- **Accounts** open with ₹1,00,000 of pretend money and a readable UPI address (`asha.rao@sproutbank`).
- **UPI PIN** set when opening: 4 or 6 digits, no repeats or runs (`1111`, `1234`), stored as a bcrypt hash. Three wrong PINs lock approvals for 15 minutes.
- **Collect requests** from partners (Sprout) wait for the customer to approve with the PIN or decline, and expire after 5 minutes. A request can't be approved by anyone but its payer.
- **Payouts** to customers, idempotent by reference, limited to what the partner holds.
- **Callbacks** to partners are written to an outbox in the same transaction as the change, signed with HMAC-SHA256, and retried with backoff (1 s up to a minute) until acknowledged.
- **The web page** at `/app` (through the gateway: `/api/bank/app`): sign in with your Sprout login, open the account, approve payments with your PIN.

Partners authenticate with `X-Partner-Key`; customers come through the gateway, which supplies their identity.

**Businesses pay each other too.** Partners (Sprout, the clearing corporation) can pay any account
here, and read their own statement filtered by the reference a payment carried, which is how the
clearing corporation and Sprout see settlement money arrive.

## Part of Sprout

[Sprout](https://sainayakk.github.io/sprout-platform/) is a simulated brokerage built from scratch as
separate services, each with its own repository and contract. Architecture, environments and test
evidence live in [sprout-platform](https://github.com/SaiNayakk/sprout-platform); this service's API is
[`bank-v1.yaml`](https://github.com/SaiNayakk/sprout-contracts/blob/main/src/main/resources/sprout/contracts/openapi/bank-v1.yaml)
in sprout-contracts. It runs inside the **street** host.

`./mvnw verify` runs the tests on a real Postgres (Docker needed), every JSON response checked against
the contract.

## License

MIT
