"""Tests for /api/transactions -- stock in/out, balance tracking, and the
concurrency-safety guarantee (the FOR UPDATE lock in create_transaction)."""
from conftest import run_concurrently, _connect_test_db


def test_stock_in_increases_balance(client, admin_headers, make_item):
    item_id = make_item("Stock In Item")
    response = client.post(
        "/api/transactions",
        json={"item_id": item_id, "io_type": "IN", "transaction_qty": 15, "remark": "test"},
        headers=admin_headers,
    )
    assert response.status_code == 200
    assert response.json()["current_stock"] == 15


def test_stock_out_decreases_balance(client, admin_headers, make_item, set_stock):
    item_id = make_item("Stock Out Item")
    set_stock(item_id, 20)

    response = client.post(
        "/api/transactions",
        json={"item_id": item_id, "io_type": "OUT", "transaction_qty": 8, "remark": "test"},
        headers=admin_headers,
    )
    assert response.status_code == 200
    assert response.json()["current_stock"] == 12


def test_stock_out_exceeding_balance_rejected(client, admin_headers, make_item, set_stock):
    item_id = make_item("Insufficient Stock Item")
    set_stock(item_id, 5)

    response = client.post(
        "/api/transactions",
        json={"item_id": item_id, "io_type": "OUT", "transaction_qty": 6, "remark": "test"},
        headers=admin_headers,
    )
    assert response.status_code == 400
    # current_qty must be unchanged after a rejected transaction.
    valuation = client.get("/api/stock/valuation", headers=admin_headers).json()
    unchanged = next(d for d in valuation["details"] if d["item_id"] == item_id)
    assert unchanged["current_qty"] == 5


def test_zero_or_negative_quantity_rejected(client, admin_headers, make_item):
    item_id = make_item("Zero Qty Item")
    response = client.post(
        "/api/transactions",
        json={"item_id": item_id, "io_type": "IN", "transaction_qty": 0, "remark": "test"},
        headers=admin_headers,
    )
    assert response.status_code == 400


def test_concurrent_stock_out_never_goes_negative(client, admin_headers, make_item, set_stock):
    """Regression test: two operators stocking OUT the same item at (almost)
    the same time must never be allowed to jointly overdraw it, even though
    each individual request looks valid when checked in isolation. This is
    exactly what the FOR UPDATE lock in create_transaction exists to prevent.

    Deliberately does NOT use the shared/overridden `client` connection for
    the concurrent requests themselves -- each request needs its own real
    connection (exactly like production) for the row lock to have any
    effect. Only item setup and the final balance check use the shared
    `client`/db fixtures.
    """
    from fastapi.testclient import TestClient
    import main

    item_id = make_item("Concurrency Test Item")
    set_stock(item_id, 10)

    # Make sure no dependency override is active for the concurrent requests below.
    main.app.dependency_overrides.pop(main.get_db_connection, None)
    raw_client = TestClient(main.app)

    results = []

    def do_stock_out():
        resp = raw_client.post(
            "/api/transactions",
            json={"item_id": item_id, "io_type": "OUT", "transaction_qty": 6, "remark": "concurrency test"},
            headers=admin_headers,
        )
        results.append(resp.status_code)

    run_concurrently(do_stock_out, do_stock_out)

    # Both requests tried to take 6 from a stock of 10 -- exactly one must
    # succeed and one must be rejected; they must NOT both succeed (which
    # would leave current_qty at -2, violating the current_qty >= 0 check).
    assert sorted(results) == [200, 400], f"expected exactly one success and one rejection, got {results}"

    verify_conn = _connect_test_db()
    with verify_conn.cursor() as cur:
        cur.execute("SELECT current_qty FROM stock_master WHERE item_id = %s", (item_id,))
        assert cur.fetchone()["current_qty"] == 4  # 10 - 6 = 4; the second OUT must not have applied
    verify_conn.close()


def test_item_history_returns_transactions_newest_first(client, admin_headers, make_item):
    item_id = make_item("History Order Item")
    client.post("/api/transactions", json={"item_id": item_id, "io_type": "IN", "transaction_qty": 10, "remark": "first"}, headers=admin_headers)
    client.post("/api/transactions", json={"item_id": item_id, "io_type": "IN", "transaction_qty": 5, "remark": "second"}, headers=admin_headers)

    response = client.get(f"/api/transactions/item/{item_id}", headers=admin_headers)
    assert response.status_code == 200
    history = response.json()
    assert len(history) == 2
    assert history[0]["remark"] == "second"  # newest first
    assert history[1]["remark"] == "first"
