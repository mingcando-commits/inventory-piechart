# Backend test suite

Tests against a **real local Postgres** (not SQLite) since the schema relies
on Postgres-specific features (`FOR UPDATE` row locking, `ARRAY`/`ANY` CHECK
constraints, `GENERATED ALWAYS AS IDENTITY`) that SQLite doesn't support
correctly. Each test run gets a completely fresh database.

## One-time setup

1. Make sure Docker Desktop is installed and running.
2. From the `backend/` directory:
   ```bash
   pip install -r requirements.txt --break-system-packages
   pip install -r requirements-dev.txt --break-system-packages
   ```

## Running the tests

```bash
# 1. Start the disposable test database (leave this running)
docker compose -f docker-compose.test.yml up -d

# 2. Run the tests
pytest

# 3. When you're done (optional -- tmpfs means it's gone on stop anyway)
docker compose -f docker-compose.test.yml down
```

Every run starts from a completely clean schema (`conftest.py` drops and
recreates the `public` schema at the start of the test session), and every
individual test gets its tables truncated + reseeded before it runs (see the
`db_conn` fixture) -- tests never see leftover data from each other.

## What's covered right now

| File | Covers |
|---|---|
| `test_auth.py` | Login, JWT expiry/validity, admin-only endpoint gating |
| `test_operators.py` | Operator CRUD, password-change rules (self vs. admin-on-behalf-of) |
| `test_items.py` | Item CRUD, nullable rate/factor, delete-blocked-with-history |
| `test_transactions.py` | Stock in/out, balance checks, **the concurrent stock-out race condition** |
| `test_global_history.py` | The `transaction_date`/`tranaction_date` typo regression |
| `test_valuation.py` | Global rate/factor fallback logic |
| `test_bulk_import.py` | CSV parser (blank price, duplicates, matching) + the preview/commit endpoints |

## The one test worth double-checking first

`test_concurrent_stock_out_never_goes_negative` in `test_transactions.py` is
the most structurally complex test here (real threading, real separate DB
connections, deliberately bypassing the test client's usual connection
override so the `FOR UPDATE` lock is actually exercised). I couldn't execute
any of this suite in the sandbox I wrote it in (no Docker/package access
there), so this is the one I'd run first and look at closely -- threading +
DB tests are exactly the kind of thing that can be subtly flaky if a detail
is off.

## Natural next steps (not built yet)

- **CI**: a GitHub Actions workflow that spins up the same Postgres service
  container and runs `pytest` on every push/PR
- **Coverage**: `pytest-cov` to see what's still untested (e.g.
  `/api/transactions/search`, `/api/transactions/{id}/remark` aren't covered yet)
- **Android/Flutter test layers**: JUnit/Robolectric and `flutter test`,
  covering the client-side logic (sort functions, form validation, the
  `formatMoneyK`/`anyToDoubleOrNull` helpers) that the backend suite can't reach
