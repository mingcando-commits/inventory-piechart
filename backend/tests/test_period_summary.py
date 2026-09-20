"""Tests for /api/stock/period-summary (庫存期間出庫量統計查詢).

For each item: the stock balance as of `end_date`, plus the total OUT and
total IN quantity for transactions dated between `start_date` and `end_date`
inclusive.
"""


def _insert_transaction(db_conn, item_id, io_type, qty, post_balance_qty, tx_date, tx_time="12:00:00"):
    """Inserts a stock_transactions row with an explicit date/time, bypassing
    the /api/transactions endpoint (which always uses "now"). Needed because
    this feature is inherently about historical date ranges.
    """
    with db_conn.cursor() as cur:
        cur.execute(
            """INSERT INTO stock_transactions
                   (transaction_date, transaction_time, item_id, io_type, transaction_qty, post_balance_qty, operator_id)
               VALUES (%s, %s, %s, %s, %s, %s, 1)""",
            (tx_date, tx_time, item_id, io_type, qty, post_balance_qty),
        )
        db_conn.commit()


def test_end_date_qty_uses_balance_as_of_end_date_not_current(client, admin_headers, db_conn, make_item):
    item_id = make_item("Period Item A")
    # Balance climbs to 10 by 2026-01-05, then more happens after the window.
    _insert_transaction(db_conn, item_id, "IN", 10, 10, "2026-01-05")
    _insert_transaction(db_conn, item_id, "OUT", 4, 6, "2026-01-20")  # after end_date below

    response = client.get(
        "/api/stock/period-summary",
        params={"start_date": "2026-01-01", "end_date": "2026-01-10"},
        headers=admin_headers,
    )
    assert response.status_code == 200
    row = next(r for r in response.json()["rows"] if r["item_id"] == item_id)
    assert row["end_date_qty"] == 10  # the 2026-01-20 OUT is after end_date and must not count


def test_item_with_no_transactions_before_end_date_reports_zero(client, admin_headers, make_item):
    item_id = make_item("Period Item Never Moved")

    response = client.get(
        "/api/stock/period-summary",
        params={"start_date": "2026-01-01", "end_date": "2026-01-31"},
        headers=admin_headers,
    )
    row = next(r for r in response.json()["rows"] if r["item_id"] == item_id)
    assert row["end_date_qty"] == 0
    assert row["period_out_qty"] == 0
    assert row["period_in_qty"] == 0


def test_period_sums_only_include_transactions_within_range(client, admin_headers, db_conn, make_item):
    item_id = make_item("Period Item B")
    _insert_transaction(db_conn, item_id, "IN", 20, 20, "2025-12-31")   # before range: excluded from sums
    _insert_transaction(db_conn, item_id, "OUT", 3, 17, "2026-01-01")   # in range (start boundary)
    _insert_transaction(db_conn, item_id, "IN", 5, 22, "2026-01-15")    # in range
    _insert_transaction(db_conn, item_id, "OUT", 2, 20, "2026-01-31")   # in range (end boundary)
    _insert_transaction(db_conn, item_id, "OUT", 1, 19, "2026-02-01")   # after range: excluded from sums

    response = client.get(
        "/api/stock/period-summary",
        params={"start_date": "2026-01-01", "end_date": "2026-01-31"},
        headers=admin_headers,
    )
    row = next(r for r in response.json()["rows"] if r["item_id"] == item_id)
    assert row["period_out_qty"] == 5   # 3 + 2, NOT the 2026-02-01 OUT
    assert row["period_in_qty"] == 5    # only the 2026-01-15 IN, NOT the 2025-12-31 IN
    assert row["end_date_qty"] == 20    # balance as of 2026-01-31


def test_end_date_defaults_to_today(client, admin_headers, make_item):
    make_item("Period Item C")

    response = client.get(
        "/api/stock/period-summary",
        params={"start_date": "2020-01-01"},
        headers=admin_headers,
    )
    assert response.status_code == 200
    body = response.json()
    assert body["end_date"]  # defaulted to something, not left null/omitted


def test_end_date_before_start_date_rejected(client, admin_headers, make_item):
    make_item("Period Item D")

    response = client.get(
        "/api/stock/period-summary",
        params={"start_date": "2026-02-01", "end_date": "2026-01-01"},
        headers=admin_headers,
    )
    assert response.status_code == 400