# Paper test result — 2026-08-28

## Scope

End-to-end validation through Horizon Blackline and Alpaca Paper only. No live
endpoint or capital was used.

## Final accepted test

- Instrument: AAPL equity; buy 1 share at a US$250.00 limit.
- Observed quote: approximately US$316.74 / US$316.90, so the limit was placed
  below market to avoid an immediate fill.
- Alpaca order ID: `16ba80b5-b5a1-4fd2-9622-7bf81609e302`.
- Client order ID: `paper-test-retry-509829f0-dd7d-4d90-bdbb-9f781e07afe9`.
- Broker result: accepted as `new`, then canceled through the governed gateway.
- Final broker state: `canceled`, `filled_qty=0`, `filled_at=null`.

## Decision evidence

- BDR: `08008add-f74e-4be7-94cd-3d817ec3a66a`.
- Final state: `POST_MORTEM_COMPLETE`.
- Replay verification: valid hash chain, 9 events, ending in
  `POST_MORTEM_RECORDED`.
- Exercised gates: evidence, three critics, deterministic evaluation,
  authorization TTL, Datomic outbox/idempotency, account allowlist, explicit
  dispatch/cancel confirmations, reconciliation, and post-mortem.

## Negative result preserved

The first submission was rejected because the gateway omitted `limit_price`
after intent reload from Datomic. The regression was corrected and tested; the
failed attempt remains recorded rather than rewritten or deleted.

## Limitations

This validates governed paper trading only. It makes no claim about live-market
execution, profitability, financial advice, or production compliance.
