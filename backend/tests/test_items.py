"""Tests for /api/items -- create/update/delete, and the nullable rate/factor fallback."""


def test_create_item_with_nullable_rate_and_tax(client, admin_headers):
    """exchange_rate/tax_coefficient are optional -- omitting them means 'use the global fallback'."""
    response = client.post(
        "/api/items",
        json={"item_name": "No Override Item", "category": "Main", "usd_price": 9.99,
              "exchange_rate": None, "tax_coefficient": None},
        headers=admin_headers,
    )
    assert response.status_code == 200
    item_id = response.json()["item_id"]

    detail = client.get(f"/api/items/{item_id}", headers=admin_headers).json()
    assert detail["exchange_rate"] is None
    assert detail["tax_coefficient"] is None


def test_create_item_invalid_category_rejected(client, admin_headers):
    response = client.post(
        "/api/items",
        json={"item_name": "Bad Category Item", "category": "NotARealCategory", "usd_price": 1.0},
        headers=admin_headers,
    )
    assert response.status_code == 400


def test_create_item_duplicate_name_rejected(client, admin_headers, make_item):
    make_item("Existing Item")
    response = client.post(
        "/api/items",
        json={"item_name": "Existing Item", "category": "Main", "usd_price": 1.0},
        headers=admin_headers,
    )
    assert response.status_code == 400


def test_update_item_never_touches_current_qty(client, admin_headers, make_item, set_stock):
    """update_item only changes master data (name/category/price/rate/factor);
    stock quantity must only ever change via /api/transactions."""
    item_id = make_item("Item To Rename", usd_price=5.0)
    set_stock(item_id, 42)

    response = client.put(
        f"/api/items/{item_id}",
        json={"item_name": "Renamed Item", "category": "Accessories", "usd_price": 7.5},
        headers=admin_headers,
    )
    assert response.status_code == 200

    valuation = client.get("/api/stock/valuation", headers=admin_headers).json()
    updated = next(d for d in valuation["details"] if d["item_id"] == item_id)
    assert updated["item_name"] == "Renamed Item"
    assert updated["category"] == "Accessories"
    assert updated["current_qty"] == 42  # unchanged


def test_delete_item_with_no_transactions_succeeds(client, admin_headers, make_item):
    item_id = make_item("Deletable Item")
    response = client.delete(f"/api/items/{item_id}", headers=admin_headers)
    assert response.status_code == 200


def test_delete_item_with_transactions_blocked(client, admin_headers, make_item):
    """Regression: an item with any transaction history must not be deletable
    (the FK is ON DELETE RESTRICT specifically to prevent this)."""
    item_id = make_item("Item With History")
    client.post(
        "/api/transactions",
        json={"item_id": item_id, "io_type": "IN", "transaction_qty": 10, "remark": "test stock-in"},
        headers=admin_headers,
    )

    response = client.delete(f"/api/items/{item_id}", headers=admin_headers)
    assert response.status_code == 400


def test_non_admin_cannot_create_item(client, make_operator):
    make_operator("plain_op2", "pw1234", is_admin=False)
    login = client.post("/api/login", data={"username": "plain_op2", "password": "pw1234"})
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}

    response = client.post(
        "/api/items", json={"item_name": "Sneaky Item", "category": "Main", "usd_price": 1.0}, headers=headers,
    )
    assert response.status_code == 403
