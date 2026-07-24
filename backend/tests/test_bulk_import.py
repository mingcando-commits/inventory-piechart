"""Tests for the CSV bulk-import feature: the parser (_parse_bulk_import_csv)
directly, and the preview/commit endpoints end to end.
"""
import main


def test_parser_blank_price_on_new_item_defaults_to_zero():
    """Regression: a blank price on a brand-new item used to be rejected as
    an error; it now defaults to 0 so the whole batch doesn't get blocked
    by a handful of not-yet-priced rows."""
    csv_content = "New Unpriced Item,Main,,10\n"
    rows = main._parse_bulk_import_csv(csv_content, existing_items=[])

    assert len(rows) == 1
    assert rows[0].action == "create"
    assert rows[0].usd_price == 0.0


def test_parser_matches_existing_items_ignoring_case_and_spaces():
    """Regression: matching must ignore whitespace and case differences."""
    csv_content = "  ClassIC  ,Main,US$10.00,5\n"
    existing_items = [{"item_id": 42, "item_name": "Classic "}]
    rows = main._parse_bulk_import_csv(csv_content, existing_items)

    assert len(rows) == 1
    assert rows[0].action == "update"
    assert rows[0].matched_item_id == 42


def test_parser_flags_duplicate_name_within_same_batch():
    csv_content = "Widget,Main,US$1.00,5\nWidget,Main,US$1.00,3\n"
    rows = main._parse_bulk_import_csv(csv_content, existing_items=[])

    assert len(rows) == 2
    assert rows[0].action == "create"
    assert rows[1].action == "error"
    assert "重複" in rows[1].error_message


def test_parser_flags_invalid_category():
    csv_content = "Bad Category Widget,NotACategory,US$1.00,5\n"
    rows = main._parse_bulk_import_csv(csv_content, existing_items=[])
    assert rows[0].action == "error"


def test_parser_flags_zero_or_missing_quantity():
    csv_content = "No Qty Widget,Main,US$1.00,0\n"
    rows = main._parse_bulk_import_csv(csv_content, existing_items=[])
    assert rows[0].action == "error"


def test_parser_ignores_blank_trailing_rows():
    csv_content = "Real Row,Main,US$1.00,5\n\n\n,,,\n"
    rows = main._parse_bulk_import_csv(csv_content, existing_items=[])
    assert len(rows) == 1


def test_parser_strips_us_dollar_prefix_and_commas():
    csv_content = "Priced Widget,Main,\"US$1,234.50\",1\n"
    rows = main._parse_bulk_import_csv(csv_content, existing_items=[])
    assert rows[0].usd_price == 1234.50


def test_preview_endpoint_requires_admin(client, make_operator):
    make_operator("plain_importer", "pw1234", is_admin=False)
    login = client.post("/api/login", data={"username": "plain_importer", "password": "pw1234"})
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}

    response = client.post("/api/items/bulk-import/preview", json={"csv_content": "X,Main,1.0,1\n"}, headers=headers)
    assert response.status_code == 403


def test_commit_creates_items_with_admin_operator_and_kaizhang_remark(client, admin_headers, make_operator):
    """Regression: the batch's operator/remark must always be 'Admin'/'開帳',
    regardless of which admin account actually triggered the import."""
    # Log in as a SECOND admin account to trigger the import, to make sure
    # attribution still goes to "Admin" specifically, not "whoever is logged in".
    make_operator("other_admin", "pw1234", is_admin=True)
    other_login = client.post("/api/login", data={"username": "other_admin", "password": "pw1234"})
    other_headers = {"Authorization": f"Bearer {other_login.json()['access_token']}"}

    preview = client.post(
        "/api/items/bulk-import/preview",
        json={"csv_content": "Imported Widget,Main,US$5.00,20\n"},
        headers=other_headers,
    ).json()
    assert preview["new_count"] == 1

    commit = client.post(
        "/api/items/bulk-import/commit", json={"rows": preview["rows"]}, headers=other_headers,
    )
    assert commit.status_code == 200
    assert commit.json()["created_count"] == 1

    valuation = client.get("/api/stock/valuation", headers=admin_headers).json()
    item = next(d for d in valuation["details"] if d["item_name"] == "Imported Widget")
    assert item["current_qty"] == 20

    history = client.get(f"/api/transactions/item/{item['item_id']}", headers=admin_headers).json()
    assert len(history) == 1
    assert history[0]["remark"] == "開帳"
    assert history[0]["operator_name"] == "Admin"  # NOT "other_admin", even though other_admin triggered it


def test_commit_update_only_changes_quantity_not_price(client, admin_headers, make_item, set_stock):
    """Existing items keep their name/category/price; the import only adds stock."""
    item_id = make_item("Pre-existing Item", usd_price=99.99, category="Accessories")
    set_stock(item_id, 5)

    preview = client.post(
        "/api/items/bulk-import/preview",
        json={"csv_content": "Pre-existing Item,Main,US$1.00,10\n"},  # different category/price on purpose
        headers=admin_headers,
    ).json()
    assert preview["update_count"] == 1

    client.post("/api/items/bulk-import/commit", json={"rows": preview["rows"]}, headers=admin_headers)

    valuation = client.get("/api/stock/valuation", headers=admin_headers).json()
    item = next(d for d in valuation["details"] if d["item_id"] == item_id)
    assert item["current_qty"] == 15          # 5 + 10
    assert item["category"] == "Accessories"  # unchanged
    assert float(item["usd_price"]) == 99.99  # unchanged
