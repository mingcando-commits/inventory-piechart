"""Tests for /api/stock/global-history.

Includes a regression test for a real bug found during development: the
date-serialization safeguard checked misspelled dict keys ("tranaction_date"
and "tran_time") that never matched what the query actually returned
("transaction_date"/"transaction_time"), so the guard silently never ran.
FastAPI's default JSON encoder happened to still serialize date/time objects
correctly on its own, which is why this didn't cause a visible failure --
but the fix only matters if the code actually uses the right key names, so
that's what this test locks in.
"""


def test_global_history_uses_correctly_spelled_keys(client, admin_headers, make_item):
    item_id = make_item("Global History Item")
    client.post(
        "/api/transactions",
        json={"item_id": item_id, "io_type": "IN", "transaction_qty": 7, "remark": "regression test"},
        headers=admin_headers,
    )

    response = client.get("/api/stock/global-history", headers=admin_headers)
    assert response.status_code == 200
    logs = response.json()
    assert len(logs) == 1

    entry = logs[0]
    # These are the exact keys the original typo'd code was silently missing.
    assert "transaction_date" in entry
    assert "transaction_time" in entry
    assert isinstance(entry["transaction_date"], str)
    assert isinstance(entry["transaction_time"], str)
    assert entry["item_name"] == "Global History Item"
    assert entry["transaction_qty"] == 7


def test_global_history_ordered_newest_first(client, admin_headers, make_item):
    item_a = make_item("History Item A")
    item_b = make_item("History Item B")
    client.post("/api/transactions", json={"item_id": item_a, "io_type": "IN", "transaction_qty": 1, "remark": "first"}, headers=admin_headers)
    client.post("/api/transactions", json={"item_id": item_b, "io_type": "IN", "transaction_qty": 1, "remark": "second"}, headers=admin_headers)

    logs = client.get("/api/stock/global-history", headers=admin_headers).json()
    assert logs[0]["remark"] == "second"
    assert logs[1]["remark"] == "first"


def test_global_history_shows_operator_name(client, admin_headers, make_item):
    item_id = make_item("Operator Name Item")
    client.post(
        "/api/transactions",
        json={"item_id": item_id, "io_type": "IN", "transaction_qty": 3, "remark": "test"},
        headers=admin_headers,
    )
    logs = client.get("/api/stock/global-history", headers=admin_headers).json()
    assert logs[0]["operator_name"] == "Admin"
