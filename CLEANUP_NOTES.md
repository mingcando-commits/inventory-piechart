# Cleanup Notes

This documents what changed during the code cleanup pass, so you can review
it against your original project before committing.

## Scope

Per your instructions: remove dead code, reorganize structure, add
docstrings/comments (English). You also asked me to fix a couple of
out-of-scope issues I found along the way (see "Bugs fixed" and
"Security" below), and to keep `HomeActivity.kt` as a single file but
reorganized internally rather than split into multiple files.

## Files changed

- `backend/main.py` — full cleanup
- `app/src/main/java/com/mingliu/inventoryapp/ApiService.kt`
- `app/src/main/java/com/mingliu/inventoryapp/AuthExpiredInterceptor.kt`
- `app/src/main/java/com/mingliu/inventoryapp/TokenInterceptor.kt`
- `app/src/main/java/com/mingliu/inventoryapp/PingWorker.kt`
- `app/src/main/java/com/mingliu/inventoryapp/ProductAdapter.kt`
- `app/src/main/java/com/mingliu/inventoryapp/MainActivity.kt`
- `app/src/main/java/com/mingliu/inventoryapp/HomeActivity.kt`

## Files removed

- **`AuthInterceptor.kt`** — a full, unused class. `RetrofitClient` only
  ever registers `TokenInterceptor` and `AuthExpiredInterceptor`; this file
  was never referenced anywhere in the project.

## Files untouched (already clean / not code)

- XML layouts, `AndroidManifest.xml`, `strings.xml`/`colors.xml`/`themes.xml`
- `inventory_db.sql` (a `pg_dump` output — not hand-authored code)
- Gradle build files, test stubs (`ExampleUnitTest.kt`, `ExampleInstrumentedTest.kt`)

## Dead code removed

- **`main.py`**: duplicate `datetime` import; ~15 lines of a commented-out
  older version of `get_db_connection`; a commented-out older SQL query
  block in `get_global_transaction_history`.
- **`HomeActivity.kt`**: a `val formattedToken = ...` line was recomputed
  in **12 places** but the value was actually used in only 2 of them — the
  rest were leftover from before `TokenInterceptor` was added to auto-attach
  the auth header to every request. Removed the other 10. Also removed the
  now-unused `token` parameter from `executeChangePasswordApi` (never read
  in the function body), duplicate/redundant KDoc comment blocks above a
  few functions, and two commented-out "Optional: return to LoginActivity"
  dead-code lines.
- **`ApiService.kt`**: ~15 lines of commented-out `@Headers(...)` annotations
  and a duplicate, fully commented-out `GlobalLogResponse` data class.

## Structure

- `HomeActivity.kt` (1,243 → 1,115 lines) is grouped into labeled sections
  in the order a user encounters them: Lifecycle & Setup → Data Loading &
  Sorting → Admin Menu & Settings → Password Management → Operator
  Management → Item Management → Transactions & History → Global History.
  `onCreate` was also broken into smaller helper methods (`bindViews`,
  `setUpTopBarInsets`, `setUpSearchBar`) instead of doing everything inline.
- `main.py` is grouped into labeled sections: Config → Database → Password/JWT
  helpers → Request models → Operators → Items → Transactions → Reporting →
  Admin bootstrap.

## Docstrings / comments

- Every function in the changed files now has an English KDoc (Kotlin) or
  docstring (Python) summarizing what it does.
- User-facing strings (Toast messages, dialog text) were **left in
  Traditional Chinese**, since that's what your users see in the app.
- Verbose, emoji-heavy inline debug commentary was trimmed; the underlying
  logic was not changed. `Log.d`/`Log.e`/`print()` calls were kept (they're
  useful) but reworded in plain English without emoji.

## Bugs fixed (you asked me to fix these while I was in the file)

- **`get_global_transaction_history`** in `main.py`: the date/time
  JSON-serialization guard checked `log.get("tranaction_date")` and
  `log.get("tran_time")` (typos), but the query actually returns
  `transaction_date` / `transaction_time`. Because the keys never matched,
  the guard silently never ran. Fixed the key names. In practice
  psycopg2/FastAPI likely serialize `date`/`time` objects to JSON without
  the manual `str()` conversion in most cases, but this is safer and now
  actually does what it was written to do.

## Security items fixed

- **Hardcoded secrets**: `SECRET_KEY` (JWT signing key) and the DB password
  are now read from environment variables (`JWT_SECRET_KEY`, `DB_PASSWORD`,
  etc.), with a warning logged if they're missing, falling back to your
  original local-dev values so `python main.py` still works unchanged on
  your machine. **Before deploying anywhere shared/public, set these env
  vars** — on Render, add `JWT_SECRET_KEY` under Environment Variables.
- **JWT logged in plaintext**: `TokenInterceptor.kt` previously logged the
  full JWT to Android's logcat on every request (`Log.d("TokenInterceptor",
  "JWT = $token")`). Removed — logcat is readable by other apps/tools on a
  rooted or debug device, so this was leaking session tokens. `MainActivity`
  also logged the token right after login; removed that too.

## New feature: monthly stock trend chart (added after initial cleanup)

Added a new Admin-only screen showing monthly stock movement as a grouped +
stacked bar chart, reachable from the gear-icon menu ("庫存趨勢圖表").

- **Chart**: each month is a group of 3 bars — current (end-of-month)
  inventory, incoming stock, outgoing stock. Each bar is stacked by product,
  one consistent color per product across all 3 bars/months. A `Switch`
  toggles between item counts and TWD value. Drag left to see earlier
  months; the view opens on the most recent months by default.
- **Tap-to-inspect**: tapping any bar (or a specific colored segment within
  a stacked bar) shows a numeric breakdown at the top of the screen — full
  per-product breakdown if you tap the bar generally, or just that one
  product if you tap its specific segment.
- **New files**: `StockChartActivity.kt`, `activity_stock_chart.xml`
- **Changed files**: `ApiService.kt` (new endpoint + response models),
  `HomeActivity.kt` (new admin menu entry), `AndroidManifest.xml` (new
  activity), `backend/main.py` (new `/api/stock/monthly-summary` endpoint,
  admin only), `settings.gradle.kts` (added JitPack repo),
  `app/build.gradle.kts` (added MPAndroidChart dependency)

**Two things worth knowing:**

1. **"Current inventory" for a past month** = the stock level at the *end*
   of that month (last known balance), carried forward from the prior month
   if there was no activity that month — per your answer to my earlier
   question.
2. **Valuation uses today's prices, not historical ones.** There's no
   price-history table in the schema, so `usd_price`/`exchange_rate`/
   `tax_coefficient` from `item_master` (today's values) are applied
   retroactively to all past months. If an item's price changed over time,
   past months' "value" figures won't reflect what the value actually was
   back then — only what it would be at current pricing. If you want
   historically-accurate valuation, that would require a price-history
   table and is a separate, larger change — let me know if you want that.

I could not compile this against the Android SDK in this environment (no
SDK/network access here), so **please do a full Gradle sync + build in
Android Studio** as the first real check. I did verify: matching brace
counts, the API surface against MPAndroidChart's public docs, and that the
new endpoint doesn't collide with or change any existing route.

## What I did *not* change

- No changes to SQL schema, request/response shapes, or business logic —
  every endpoint, every dialog flow, every validation rule behaves the same.
  `HomeActivity.kt`'s deep inline UI comments were mostly left in Chinese
  where they were describing *why* a specific UI tweak was made; only
  dead/duplicate comments were removed.
- I did not add Kotlin unit tests, error-handling improvements beyond the
  one bug above, or ViewBinding/data-binding — none of that was in scope.
