"""Tests for /api/stock/valuation and the global exchange-rate/tax-factor fallback."""


def test_item_without_own_rate_uses_global_fallback(client, admin_headers, make_item, set_stock):
    item_id = make_item("No Override Item", usd_price=10.0, exchange_rate=None, tax_coefficient=None)
    set_stock(item_id, 2)
    # Global settings default to 32.0 / 0.05 (seeded by the db_conn fixture).

    valuation = client.get("/api/stock/valuation", headers=admin_headers).json()
    detail = next(d for d in valuation["details"] if d["item_id"] == item_id)

    assert detail["is_global_rate"] is True
    assert detail["is_global_tax"] is True
    assert float(detail["exchange_rate_used"]) == 32.0
    assert float(detail["tax_coefficient_used"]) == 0.05
    # unit_price_twd = 10.0 * 32.0 * 1.05 = 336.0; twd_amount = 336.0 * 2 = 672.0
    assert abs(float(detail["twd_amount"]) - 672.0) < 0.01


def test_item_with_own_rate_ignores_global(client, admin_headers, make_item, set_stock):
    item_id = make_item("Custom Rate Item", usd_price=10.0, exchange_rate=30.0, tax_coefficient=0.10)
    set_stock(item_id, 1)

    valuation = client.get("/api/stock/valuation", headers=admin_headers).json()
    detail = next(d for d in valuation["details"] if d["item_id"] == item_id)

    assert detail["is_global_rate"] is False
    assert detail["is_global_tax"] is False
    assert float(detail["exchange_rate_used"]) == 30.0
    assert float(detail["tax_coefficient_used"]) == 0.10
    # unit_price_twd = 10.0 * 30.0 * 1.10 = 330.0
    assert abs(float(detail["twd_amount"]) - 330.0) < 0.01


def test_updating_global_settings_changes_fallback_valuation(client, admin_headers, make_item, set_stock):
    item_id = make_item("Reacts To Global Item", usd_price=1.0, exchange_rate=None, tax_coefficient=None)
    set_stock(item_id, 1)

    update = client.put(
        "/api/settings/global",
        json={"global_exchange_rate": 40.0, "global_tax_coefficient": 0.0},
        headers=admin_headers,
    )
    assert update.status_code == 200

    valuation = client.get("/api/stock/valuation", headers=admin_headers).json()
    detail = next(d for d in valuation["details"] if d["item_id"] == item_id)
    assert abs(float(detail["twd_amount"]) - 40.0) < 0.01  # 1.0 * 40.0 * 1.0


def test_valuation_total_matches_sum_of_items(client, admin_headers, make_item, set_stock):
    item_a = make_item("Total Test A", usd_price=1.0, exchange_rate=10.0, tax_coefficient=0.0)
    item_b = make_item("Total Test B", usd_price=2.0, exchange_rate=10.0, tax_coefficient=0.0)
    set_stock(item_a, 3)   # 1.0 * 10.0 * 1.0 * 3 = 30.0
    set_stock(item_b, 5)   # 2.0 * 10.0 * 1.0 * 5 = 100.0

    valuation = client.get("/api/stock/valuation", headers=admin_headers).json()
    relevant = [d for d in valuation["details"] if d["item_id"] in (item_a, item_b)]
    assert abs(sum(float(d["twd_amount"]) for d in relevant) - 130.0) < 0.01
