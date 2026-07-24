"""
Shared pytest fixtures for the backend test suite.

Sets environment variables and applies the test schema BEFORE `main` is
imported anywhere, since importing main.py has side effects: it connects to
whatever database DB_HOST/etc. point at and seeds the Admin account /
system_settings row. If we imported main.py before pointing those env vars
at the test database, it would try to do that against your real database.
"""
import os

os.environ.setdefault("DB_HOST", "localhost")
os.environ.setdefault("DB_PORT", "5433")
os.environ.setdefault("DB_NAME", "inventory_test")
os.environ.setdefault("DB_USER", "test")
os.environ.setdefault("DB_PASSWORD", "test")
os.environ.setdefault("JWT_SECRET_KEY", "test-only-secret-key-not-for-production")
os.environ.pop("DATABASE_URL", None)  # make sure a real DATABASE_URL isn't picked up by accident

import pathlib
import threading

import psycopg2
import psycopg2.extras
import pytest
from fastapi.testclient import TestClient


def _apply_schema():
    """(Re)applies tests/schema.sql to the test database, dropping and
    recreating the public schema first so each test *session* starts from
    a clean slate regardless of what a previous run left behind.
    """
    conn = psycopg2.connect(
        host=os.environ["DB_HOST"], port=os.environ["DB_PORT"], dbname=os.environ["DB_NAME"],
        user=os.environ["DB_USER"], password=os.environ["DB_PASSWORD"],
    )
    conn.autocommit = True
    try:
        with conn.cursor() as cur:
            cur.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;")
            schema_sql = (pathlib.Path(__file__).parent / "schema.sql").read_text()
            cur.execute(schema_sql)
    finally:
        conn.close()


_apply_schema()

# Only safe to import main.py now that the schema exists -- its module-level
# init_admin_account()/init_system_settings() calls need the tables to be there.
from main import app, get_db_connection, get_password_hash  # noqa: E402


def _connect_test_db():
    return psycopg2.connect(
        host=os.environ["DB_HOST"], port=os.environ["DB_PORT"], dbname=os.environ["DB_NAME"],
        user=os.environ["DB_USER"], password=os.environ["DB_PASSWORD"],
        cursor_factory=psycopg2.extras.RealDictCursor,
    )


@pytest.fixture()
def db_conn():
    """A fresh connection per test. Truncates every table and reseeds
    system_settings + a default Admin operator (password: admin123) before
    the test runs, so tests never see leftover data from a previous test.
    """
    conn = _connect_test_db()
    conn.rollback()  # clear any leftover transaction state from a previous failed test

    with conn.cursor() as cur:
        cur.execute(
            "TRUNCATE stock_transactions, stock_master, item_master, system_settings, operator_master RESTART IDENTITY CASCADE"
        )
        cur.execute(
            "INSERT INTO system_settings (id, global_exchange_rate, global_tax_coefficient) VALUES (1, 32.0, 0.05)"
        )
        cur.execute(
            "INSERT INTO operator_master (operator_name, password_hash, is_admin) VALUES (%s, %s, %s)",
            ("Admin", get_password_hash("admin123"), True),
        )
        conn.commit()

    yield conn
    conn.close()


@pytest.fixture()
def client(db_conn):
    """FastAPI TestClient wired to use db_conn for every request in this test."""
    def override_get_db_connection():
        yield db_conn

    app.dependency_overrides[get_db_connection] = override_get_db_connection
    yield TestClient(app)
    app.dependency_overrides.clear()


@pytest.fixture()
def admin_token(client):
    """Logs in as the seeded Admin account via the real endpoint, returns a bearer token string."""
    response = client.post("/api/login", data={"username": "Admin", "password": "admin123"})
    assert response.status_code == 200, response.text
    return response.json()["access_token"]


@pytest.fixture()
def admin_headers(admin_token):
    return {"Authorization": f"Bearer {admin_token}"}


@pytest.fixture()
def make_operator(db_conn):
    """Factory fixture: make_operator("bob", "pw1234") -> operator_id. Returns the new operator's ID."""
    def _make(operator_name: str, password: str, is_admin: bool = False) -> int:
        with db_conn.cursor() as cur:
            cur.execute(
                "INSERT INTO operator_master (operator_name, password_hash, is_admin) VALUES (%s, %s, %s) RETURNING operator_id",
                (operator_name, get_password_hash(password), is_admin),
            )
            operator_id = cur.fetchone()["operator_id"]
            db_conn.commit()
        return operator_id
    return _make


@pytest.fixture()
def make_item(db_conn):
    """Factory fixture: make_item("Widget") -> item_id, with a zero-quantity stock_master row already created."""
    def _make(item_name: str, category: str = "Main", usd_price: float = 10.0,
              exchange_rate=None, tax_coefficient=None) -> int:
        with db_conn.cursor() as cur:
            cur.execute(
                """INSERT INTO item_master (item_name, category, usd_price, exchange_rate, tax_coefficient, last_update_date)
                   VALUES (%s, %s, %s, %s, %s, CURRENT_DATE) RETURNING item_id""",
                (item_name, category, usd_price, exchange_rate, tax_coefficient),
            )
            item_id = cur.fetchone()["item_id"]
            cur.execute(
                "INSERT INTO stock_master (item_id, current_qty, last_update_date, last_update_time) VALUES (%s, 0, CURRENT_DATE, CURRENT_TIME)",
                (item_id,),
            )
            db_conn.commit()
        return item_id
    return _make


@pytest.fixture()
def set_stock(db_conn):
    """Factory fixture: set_stock(item_id, 50) -- directly sets an item's current_qty, bypassing transactions."""
    def _set(item_id: int, qty: int):
        with db_conn.cursor() as cur:
            cur.execute("UPDATE stock_master SET current_qty = %s WHERE item_id = %s", (qty, item_id))
            db_conn.commit()
    return _set


def run_concurrently(*fns):
    """Runs each of the given zero-arg callables in its own thread, waits for
    all of them to finish. Used for the stock-locking regression test, where
    each thread needs a genuinely separate DB connection to exercise the
    real FOR UPDATE lock -- not the shared, overridden test connection.
    """
    threads = [threading.Thread(target=fn) for fn in fns]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
