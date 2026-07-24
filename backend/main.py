"""
Backend Warehouse & Stock Management System.

A FastAPI service backing the Android inventory app. Handles:
- Operator authentication (JWT) and account management
- Item master data (products) maintenance
- Stock transactions (stock in / stock out) and history
- Stock valuation reporting

Database: PostgreSQL (local via DB_CONFIG, or cloud via DATABASE_URL env var,
e.g. when deployed on Render + Supabase).
"""

import csv
import io
import logging
import os
from datetime import date, datetime, timedelta
from typing import List, Optional
from zoneinfo import ZoneInfo

import jwt
import psycopg2
from fastapi import Depends, FastAPI, HTTPException, Request, status
from fastapi.security import OAuth2PasswordBearer, OAuth2PasswordRequestForm
from jwt import ExpiredSignatureError
from passlib.context import CryptContext
from psycopg2.extras import RealDictCursor, execute_values
from pydantic import BaseModel, Field

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("inventory_app")

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
# Local/dev defaults are kept so the app still runs out-of-the-box on a
# developer machine. In any shared or production environment, set these via
# environment variables instead of relying on the defaults below.
SECRET_KEY = os.environ.get("JWT_SECRET_KEY")
if not SECRET_KEY:
    logger.warning(
        "JWT_SECRET_KEY is not set. Falling back to an insecure development "
        "key. Set JWT_SECRET_KEY in the environment before deploying."
    )
    SECRET_KEY = "dev-only-insecure-secret-change-me"

ALGORITHM = "HS256"
ACCESS_TOKEN_EXPIRE_MINUTES = 480  # Token validity window

DB_CONFIG = {
    "host": os.environ.get("DB_HOST", "localhost"),
    "database": os.environ.get("DB_NAME", "inventory_db"),
    "user": os.environ.get("DB_USER", "postgres"),
    "password": os.environ.get("DB_PASSWORD", ""),
    "port": os.environ.get("DB_PORT", "5432"),
}
if not DB_CONFIG["password"]:
    logger.warning(
        "DB_PASSWORD is not set. Set DB_HOST/DB_NAME/DB_USER/DB_PASSWORD/DB_PORT "
        "in the environment, or DATABASE_URL for a hosted database."
    )

DEFAULT_ADMIN_PASSWORD = os.environ.get("DEFAULT_ADMIN_PASSWORD", "admin123")

app = FastAPI(title="Backend Warehouse & Stock Management System")
pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")
oauth2_scheme = OAuth2PasswordBearer(tokenUrl="/api/login")


# ---------------------------------------------------------------------------
# Database
# ---------------------------------------------------------------------------
def _connect():
    """Open a new PostgreSQL connection.

    Uses the DATABASE_URL environment variable when present (cloud/hosted
    deployments), otherwise falls back to the local DB_CONFIG settings.
    """
    db_url = os.environ.get("DATABASE_URL")
    if db_url:
        return psycopg2.connect(db_url, cursor_factory=RealDictCursor)
    return psycopg2.connect(**DB_CONFIG, cursor_factory=RealDictCursor)


def get_db_connection():
    """FastAPI dependency that yields a live DB connection per request.

    Hosted Postgres instances (e.g. Supabase via Render) can silently drop
    idle connections. Before handing the connection to the endpoint, we
    probe it with a cheap query; if the probe fails, we transparently
    reconnect once. The connection is always closed at the end of the
    request.
    """
    conn = _connect()

    try:
        with conn.cursor() as tester:
            tester.execute("SET TIME ZONE 'Asia/Taipei'")
    except (psycopg2.OperationalError, psycopg2.InterfaceError) as e:
        logger.warning("Stale DB connection detected (%s); reconnecting.", e)
        conn = _connect()

    try:
        yield conn
    finally:
        conn.close()


# ---------------------------------------------------------------------------
# Password & JWT helpers
# ---------------------------------------------------------------------------
def get_password_hash(password: str) -> str:
    """Hash a plaintext password using bcrypt."""
    return pwd_context.hash(password)


def verify_password(plain_password: str, hashed_password: str) -> bool:
    """Check a plaintext password against a bcrypt hash."""
    return pwd_context.verify(plain_password, hashed_password)


def create_access_token(data: dict) -> str:
    """Create a signed JWT access token that expires after ACCESS_TOKEN_EXPIRE_MINUTES."""
    to_encode = data.copy()
    taiwan_tz = ZoneInfo("Asia/Taipei")
    expire = datetime.now(taiwan_tz) + timedelta(minutes=ACCESS_TOKEN_EXPIRE_MINUTES)
    to_encode.update({"exp": expire})
    return jwt.encode(to_encode, SECRET_KEY, algorithm=ALGORITHM)


def get_current_user(
    request: Request,
    token: str = Depends(oauth2_scheme),
    conn=Depends(get_db_connection),
):
    """Resolve the currently authenticated operator from the bearer token."""
    credentials_exception = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Could not validate credentials or token expired",
        headers={"WWW-Authenticate": "Bearer"},
    )

    try:
        payload = jwt.decode(token, SECRET_KEY, algorithms=[ALGORITHM])
        username = payload.get("sub")
        if username is None:
            raise credentials_exception
    except ExpiredSignatureError:
        raise credentials_exception
    except jwt.PyJWTError as e:
        logger.info("JWT validation failed: %s", e)
        raise credentials_exception

    with conn.cursor() as cur:
        cur.execute(
            "SELECT operator_id, operator_name, is_admin "
            "FROM operator_master WHERE operator_name = %s",
            (username,),
        )
        user = cur.fetchone()
        if user is None:
            raise credentials_exception
        return user


def verify_admin(current_user=Depends(get_current_user)):
    """Dependency that restricts an endpoint to admin operators only."""
    if not current_user["is_admin"]:
        raise HTTPException(status_code=403, detail="權限不足：只有 'Admin' 身分可以執行此操作。")
    return current_user


# ---------------------------------------------------------------------------
# Request models
# ---------------------------------------------------------------------------
class LoginModel(BaseModel):
    username: str
    password: str


class OperatorCreate(BaseModel):
    operator_name: str
    password: str
    is_admin: bool = False


class OperatorUpdatePassword(BaseModel):
    old_password: str
    new_password: str


class ItemCreateUpdate(BaseModel):
    item_name: str
    category: str = Field(..., description="Main or Accessories")
    usd_price: float
    exchange_rate: Optional[float] = None    # None = fall back to the global exchange rate at valuation time
    tax_coefficient: Optional[float] = None  # None = fall back to the global adjustment factor at valuation time


class GlobalSettingsUpdate(BaseModel):
    global_exchange_rate: float
    global_tax_coefficient: float


class BulkImportRow(BaseModel):
    """One parsed/validated row from a stock-in CSV import (存貨開帳 style)."""
    row_number: int
    item_name: str
    category: str
    usd_price: Optional[float] = None
    quantity: int
    action: str  # "create" | "update" | "error"
    matched_item_id: Optional[int] = None  # set when action == "update"
    error_message: Optional[str] = None    # set when action == "error"


class BulkImportPreviewRequest(BaseModel):
    csv_content: str


class BulkImportPreviewResponse(BaseModel):
    rows: List[BulkImportRow]
    new_count: int
    update_count: int
    error_count: int


class BulkImportCommitRequest(BaseModel):
    # The exact row list returned by the preview endpoint -- committing what
    # was previewed, rather than re-parsing, guarantees no drift between
    # what the admin reviewed and what actually gets written.
    rows: List[BulkImportRow]


class BulkImportCommitResponse(BaseModel):
    created_count: int
    updated_count: int
    message: str


class TransactionCreate(BaseModel):
    item_id: int
    io_type: str = Field(..., description="IN or OUT")
    transaction_qty: int
    remark: Optional[str] = ""


class TransactionRemarkUpdate(BaseModel):
    remark: str


# ---------------------------------------------------------------------------
# Health check
# ---------------------------------------------------------------------------
@app.get("/api/ping")
def keep_alive_ping():
    """Lightweight endpoint used to keep a free-tier host (e.g. Render) warm."""
    return {"status": "pong", "message": "System is alive"}


# ---------------------------------------------------------------------------
# Operators: listing, login, account management
# ---------------------------------------------------------------------------
@app.get("/api/operators", dependencies=[Depends(verify_admin)])
def get_all_operators(conn=Depends(get_db_connection)):
    """List all operators. Admin only."""
    with conn.cursor() as cur:
        cur.execute("SELECT operator_id, operator_name, is_admin FROM operator_master ORDER BY operator_id ASC")
        return cur.fetchall()


@app.post("/api/operators/{operator_id}/password")
def change_operator_password(
    operator_id: int,
    payload: OperatorUpdatePassword,
    current_user=Depends(get_current_user),
    conn=Depends(get_db_connection),
):
    """Change an operator's password.

    Admins may change any operator's password without providing the old
    password. Non-admins may only change their own password, and must
    supply the correct current password first.
    """
    current_uid = str(current_user.get("operator_id") or current_user.get("id") or "").strip()
    target_uid = str(operator_id).strip()
    is_self_change = current_uid == target_uid and current_uid != ""

    if not current_user.get("is_admin") and not is_self_change:
        raise HTTPException(status_code=403, detail="權限不足：您無法修改他人的密碼！")

    if is_self_change:
        if not payload.old_password:
            raise HTTPException(status_code=400, detail="修改自身密碼必須輸入舊密碼！")

        with conn.cursor() as cur:
            cur.execute("SELECT password_hash FROM operator_master WHERE operator_id = %s", (operator_id,))
            user_data = cur.fetchone()
            if not user_data:
                raise HTTPException(status_code=404, detail="找不到該 Operator")

            db_password_hash = user_data["password_hash"] if isinstance(user_data, dict) else user_data[0]
            if not verify_password(payload.old_password, db_password_hash):
                raise HTTPException(status_code=400, detail="舊密碼驗證錯誤，拒絕變更！")

    hashed = get_password_hash(payload.new_password)
    with conn.cursor() as cur:
        cur.execute(
            "UPDATE operator_master SET password_hash = %s WHERE operator_id = %s",
            (hashed, operator_id),
        )
        if cur.rowcount == 0:
            raise HTTPException(status_code=404, detail="找不到該 Operator")
        conn.commit()

    return {"message": "密碼已成功更新！"}


@app.post("/api/login")
def login(data: OAuth2PasswordRequestForm = Depends(), conn=Depends(get_db_connection)):
    """Authenticate an operator and issue a JWT access token."""
    with conn.cursor() as cur:
        cur.execute("SELECT * FROM operator_master WHERE operator_name = %s", (data.username,))
        user = cur.fetchone()

        if not user or not verify_password(data.password, user["password_hash"]):
            raise HTTPException(status_code=400, detail="帳號或密碼錯誤")

        token = create_access_token(data={"sub": user["operator_name"]})
        return {
            "access_token": token,
            "token_type": "bearer",
            "is_admin": user["is_admin"],
            "operator_id": user["operator_id"],
        }


@app.post("/api/operators", dependencies=[Depends(verify_admin)])
def add_operator(op: OperatorCreate, conn=Depends(get_db_connection)):
    """Create a new operator account. Admin only."""
    hashed = get_password_hash(op.password)
    try:
        with conn.cursor() as cur:
            cur.execute(
                "INSERT INTO operator_master (operator_name, password_hash, is_admin) VALUES (%s, %s, %s)",
                (op.operator_name, hashed, op.is_admin),
            )
            conn.commit()
        return {"message": f"Operator {op.operator_name} created successfully"}
    except psycopg2.IntegrityError:
        conn.rollback()
        raise HTTPException(status_code=400, detail="Operator Name 已存在")


@app.delete("/api/operators/{operator_id}", dependencies=[Depends(verify_admin)])
def delete_operator(operator_id: int, conn=Depends(get_db_connection)):
    """Delete an operator account. Admin only."""
    with conn.cursor() as cur:
        cur.execute("DELETE FROM operator_master WHERE operator_id = %s", (operator_id,))
        if cur.rowcount == 0:
            raise HTTPException(status_code=404, detail="找不到該 Operator")
        conn.commit()
    return {"message": "Operator deleted successfully"}


# ---------------------------------------------------------------------------
# Items: product master data maintenance (admin only)
# ---------------------------------------------------------------------------
@app.post("/api/items", dependencies=[Depends(verify_admin)])
def add_item(item: ItemCreateUpdate, current_user=Depends(get_current_user), conn=Depends(get_db_connection)):
    """Create a new item and its zero-quantity stock entry.

    exchange_rate/tax_coefficient are optional -- leave them unset (None) to
    have this item fall back to the global exchange rate / adjustment factor
    (see GET/PUT /api/settings/global) at valuation time.
    """
    if item.category not in ["Main", "Accessories"]:
        raise HTTPException(status_code=400, detail="類別必須是 'Main' 或 'Accessories'")
    try:
        with conn.cursor() as cur:
            cur.execute(
                """INSERT INTO item_master (item_name, category, usd_price, exchange_rate, tax_coefficient, last_update_date, last_update_operator_id)
                   VALUES (%s, %s, %s, %s, %s, CURRENT_DATE, %s) RETURNING item_id""",
                (item.item_name, item.category, item.usd_price, item.exchange_rate, item.tax_coefficient, current_user["operator_id"]),
            )
            new_item_id = cur.fetchone()["item_id"]

            cur.execute(
                "INSERT INTO stock_master (item_id, current_qty, last_update_date, last_update_time) VALUES (%s, 0, CURRENT_DATE, CURRENT_TIME)",
                (new_item_id,),
            )
            conn.commit()
        return {"message": "Item and Stock entry created successfully", "item_id": new_item_id}
    except psycopg2.IntegrityError:
        conn.rollback()
        raise HTTPException(status_code=400, detail="Item Name 已存在")


@app.put("/api/items/{item_id}", dependencies=[Depends(verify_admin)])
def update_item(item_id: int, item: ItemCreateUpdate, current_user=Depends(get_current_user), conn=Depends(get_db_connection)):
    """Update an existing item's master data (name, category, price, rate, factor).

    Does not touch current_qty -- stock levels only change via /api/transactions.
    exchange_rate/tax_coefficient are optional; leave unset (None) to use the
    global fallback values instead of an item-specific override.
    """
    with conn.cursor() as cur:
        cur.execute(
            """UPDATE item_master
               SET item_name=%s, category=%s, usd_price=%s, exchange_rate=%s, tax_coefficient=%s,
                   last_update_date=CURRENT_DATE, last_update_operator_id=%s
               WHERE item_id = %s""",
            (item.item_name, item.category, item.usd_price, item.exchange_rate, item.tax_coefficient, current_user["operator_id"], item_id),
        )
        if cur.rowcount == 0:
            raise HTTPException(status_code=404, detail="找不到該 Item")
        conn.commit()
    return {"message": "Item updated successfully"}


@app.delete("/api/items/{item_id}", dependencies=[Depends(verify_admin)])
def delete_item(item_id: int, conn=Depends(get_db_connection)):
    """Delete an item. Fails if the item has existing transaction history."""
    try:
        with conn.cursor() as cur:
            cur.execute("DELETE FROM item_master WHERE item_id = %s", (item_id,))
            if cur.rowcount == 0:
                raise HTTPException(status_code=404, detail="找不到該 Item")
            conn.commit()
        return {"message": "Item deleted successfully"}
    except psycopg2.IntegrityError:
        conn.rollback()
        raise HTTPException(status_code=400, detail="此項目已有歷史交易紀錄，無法刪除！")


@app.get("/api/items/filter/{category}")
def get_items_by_category(category: str, conn=Depends(get_db_connection)):
    """List items belonging to a given category."""
    with conn.cursor() as cur:
        cur.execute("SELECT item_id, item_name FROM item_master WHERE category = %s", (category,))
        return cur.fetchall()


@app.get("/api/items/{item_id}")
def get_item(item_id: int, current_user=Depends(get_current_user), conn=Depends(get_db_connection)):
    """Return a single item's current master data.

    Lightweight compared to /api/stock/valuation (which returns every item) --
    used to refresh the edit-item form with up-to-date values without paying
    for the full list. exchange_rate/tax_coefficient are the item's raw
    per-item override (null if it uses the global fallback).
    """
    with conn.cursor() as cur:
        cur.execute(
            "SELECT item_id, item_name, category, usd_price, exchange_rate, tax_coefficient FROM item_master WHERE item_id = %s",
            (item_id,),
        )
        item = cur.fetchone()
        if not item:
            raise HTTPException(status_code=404, detail="找不到該 Item")
        return item


# ---------------------------------------------------------------------------
# Bulk stock-in import (CSV): 存貨開帳-style batch load
# ---------------------------------------------------------------------------
def _normalize_item_name(name: str) -> str:
    """Strips ALL whitespace and lowercases, for case/space-insensitive matching."""
    return "".join(name.split()).lower()


def _parse_csv_price(raw: str) -> Optional[float]:
    """Parses a price cell like 'US$297.00' -> 297.0. Returns None if blank/unparseable."""
    if not raw or not raw.strip():
        return None
    cleaned = raw.strip().replace("US$", "").replace("$", "").replace(",", "")
    try:
        return float(cleaned)
    except ValueError:
        return None


def _parse_bulk_import_csv(csv_content: str, existing_items: list) -> List[BulkImportRow]:
    """Parses and validates a stock-in CSV (no header row; columns: item_name,
    category, price, quantity) against the current item list, classifying
    each row as create/update/error without touching the database.

    A blank price on a brand-new item defaults to 0 rather than erroring the
    row (existing items are unaffected either way, since their price is
    never touched by this import).
    """
    existing_map = {_normalize_item_name(item["item_name"]): item["item_id"] for item in existing_items}
    seen_in_batch: dict[str, int] = {}  # normalized name -> first row_number seen

    rows: List[BulkImportRow] = []
    reader = csv.reader(io.StringIO(csv_content))

    for i, raw_row in enumerate(reader, start=1):
        if not raw_row or all(not cell.strip() for cell in raw_row):
            continue  # blank row (e.g. trailing empty lines) -- not an error, just skip

        if len(raw_row) < 4:
            rows.append(BulkImportRow(
                row_number=i, item_name=",".join(raw_row), category="", quantity=0,
                action="error", error_message="欄位數量不足（需要4欄：品名、類別、單價、數量）",
            ))
            continue

        name = raw_row[0].strip()
        category = raw_row[1].strip()
        price = _parse_csv_price(raw_row[2])
        try:
            quantity = int(raw_row[3].strip())
        except (ValueError, AttributeError):
            quantity = 0

        if not name:
            continue  # blank name -- treat like a blank row

        normalized = _normalize_item_name(name)

        if normalized in seen_in_batch:
            rows.append(BulkImportRow(
                row_number=i, item_name=name, category=category, usd_price=price, quantity=quantity,
                action="error", error_message=f"與第 {seen_in_batch[normalized]} 列重複（同一批次內品名重複）",
            ))
            continue
        seen_in_batch[normalized] = i

        if category not in ("Main", "Accessories"):
            rows.append(BulkImportRow(
                row_number=i, item_name=name, category=category, usd_price=price, quantity=quantity,
                action="error", error_message="類別必須是 Main 或 Accessories",
            ))
            continue

        if quantity <= 0:
            rows.append(BulkImportRow(
                row_number=i, item_name=name, category=category, usd_price=price, quantity=quantity,
                action="error", error_message="數量無效或為 0",
            ))
            continue

        matched_item_id = existing_map.get(normalized)

        if matched_item_id is not None:
            rows.append(BulkImportRow(
                row_number=i, item_name=name, category=category, usd_price=price, quantity=quantity,
                action="update", matched_item_id=matched_item_id,
            ))
        else:
            # A blank price on a brand-new item defaults to 0 (rather than
            # being rejected) -- the admin can edit the real price in later
            # via 更新商品資料 once known.
            rows.append(BulkImportRow(
                row_number=i, item_name=name, category=category, usd_price=(price if price is not None else 0.0),
                quantity=quantity, action="create",
            ))

    return rows


@app.post("/api/items/bulk-import/preview", dependencies=[Depends(verify_admin)])
def preview_bulk_import(payload: BulkImportPreviewRequest, conn=Depends(get_db_connection)):
    """Parses a stock-in CSV and classifies each row as create/update/error,
    WITHOUT writing anything to the database. Admin only.

    Matching against existing items ignores spacing and case. Existing items
    are never renamed/re-priced/re-categorized by this import -- only their
    stock quantity changes; item_name/category/usd_price here are only used
    when the row is classified as "create" (a brand-new item).
    """
    with conn.cursor() as cur:
        cur.execute("SELECT item_id, item_name FROM item_master")
        existing_items = cur.fetchall()

    rows = _parse_bulk_import_csv(payload.csv_content, existing_items)

    return BulkImportPreviewResponse(
        rows=rows,
        new_count=sum(1 for r in rows if r.action == "create"),
        update_count=sum(1 for r in rows if r.action == "update"),
        error_count=sum(1 for r in rows if r.action == "error"),
    )


@app.post("/api/items/bulk-import/commit", dependencies=[Depends(verify_admin)])
def commit_bulk_import(payload: BulkImportCommitRequest, conn=Depends(get_db_connection)):
    """Executes a previously-previewed stock-in batch: creates new items
    (starting at 0 stock) and/or adds a stock-in transaction to existing
    items, for every row marked "create"/"update" (rows marked "error" are
    ignored). All-or-nothing: if anything fails partway through, the whole
    batch rolls back, since a half-applied opening-balance import would be
    worse than none at all.

    Batches all writes via execute_values -- a handful of round-trips total,
    regardless of how many rows are in the batch, rather than ~4-5 sequential
    round-trips per row. A large CSV doing hundreds of one-row-at-a-time
    round-trips to a remote DB can take long enough to hit an HTTP timeout
    (the client's, or the hosting platform's) even though the work itself
    would eventually succeed -- which is exactly what "the app said it
    failed, but the data shows up anyway" looks like from the outside.

    Per the batch's requirements: operator is always the "Admin" account
    (regardless of which admin triggered the import) and remark is "開帳",
    matching an opening-balance stock load rather than a routine transaction.
    Admin only.
    """
    with conn.cursor() as cur:
        cur.execute("SELECT operator_id FROM operator_master WHERE operator_name = 'Admin'")
        admin_row = cur.fetchone()
        if not admin_row:
            raise HTTPException(status_code=500, detail="系統錯誤：找不到 Admin 帳號")
        admin_id = admin_row["operator_id"]

    create_rows = [r for r in payload.rows if r.action == "create"]
    update_rows = [r for r in payload.rows if r.action == "update"]

    try:
        with conn.cursor() as cur:
            # 1. Bulk-create every new item in one round-trip. A single
            #    multi-row INSERT...VALUES...RETURNING preserves input order,
            #    so new_item_ids[i] corresponds to create_rows[i].
            new_item_ids = []
            if create_rows:
                create_values = [(r.item_name, r.category, r.usd_price, admin_id) for r in create_rows]
                results = execute_values(
                    cur,
                    """INSERT INTO item_master (item_name, category, usd_price, last_update_date, last_update_operator_id)
                       VALUES %s RETURNING item_id""",
                    create_values,
                    template="(%s, %s, %s, CURRENT_DATE, %s)",
                    page_size=len(create_values),
                    fetch=True,
                )
                new_item_ids = [row["item_id"] for row in results]

                # 2. Bulk-create their zero-quantity stock_master rows, one round-trip.
                execute_values(
                    cur,
                    "INSERT INTO stock_master (item_id, current_qty, last_update_date, last_update_time) VALUES %s",
                    [(item_id,) for item_id in new_item_ids],
                    template="(%s, 0, CURRENT_DATE, CURRENT_TIME)",
                    page_size=len(new_item_ids),
                )

            # Every item in this batch (new or existing) appears exactly once
            # -- duplicates within one CSV were already rejected at preview time.
            target_pairs = list(zip(new_item_ids, (r.quantity for r in create_rows)))
            target_pairs += [(r.matched_item_id, r.quantity) for r in update_rows]

            if target_pairs:
                target_ids = [item_id for item_id, _ in target_pairs]

                # 3. Lock + fetch current balances for every touched item, one round-trip.
                cur.execute("SELECT item_id, current_qty FROM stock_master WHERE item_id = ANY(%s) FOR UPDATE", (target_ids,))
                current_balances = {row["item_id"]: row["current_qty"] for row in cur.fetchall()}

                update_values = []
                transaction_values = []
                for item_id, qty in target_pairs:
                    new_qty = current_balances.get(item_id, 0) + qty
                    update_values.append((item_id, new_qty))
                    transaction_values.append((item_id, qty, new_qty, admin_id))

                # 4. Bulk-update stock_master balances, one round-trip.
                execute_values(
                    cur,
                    """UPDATE stock_master AS s SET current_qty = v.new_qty,
                       last_update_date = CURRENT_DATE, last_update_time = CURRENT_TIME
                       FROM (VALUES %s) AS v(item_id, new_qty)
                       WHERE s.item_id = v.item_id""",
                    update_values,
                    template="(%s, %s)",
                    page_size=len(update_values),
                )

                # 5. Bulk-insert every stock-in transaction, one round-trip.
                execute_values(
                    cur,
                    """INSERT INTO stock_transactions
                       (transaction_date, transaction_time, item_id, io_type, transaction_qty, post_balance_qty, remark, operator_id)
                       VALUES %s""",
                    transaction_values,
                    template="(CURRENT_DATE, CURRENT_TIME, %s, 'IN', %s, %s, '開帳', %s)",
                    page_size=len(transaction_values),
                )

            conn.commit()
    except Exception as e:
        conn.rollback()
        logger.exception("Bulk import commit failed and was rolled back")
        raise HTTPException(status_code=500, detail=f"批次匯入失敗，已回滾，未寫入任何資料：{str(e)}")

    return BulkImportCommitResponse(
        created_count=len(create_rows),
        updated_count=len(update_rows),
        message=f"匯入完成：新增 {len(create_rows)} 項商品，更新 {len(update_rows)} 項庫存",
    )


# ---------------------------------------------------------------------------
# Global settings: fallback exchange rate / adjustment factor
# ---------------------------------------------------------------------------
@app.get("/api/settings/global", dependencies=[Depends(verify_admin)])
def get_global_settings(conn=Depends(get_db_connection)):
    """Return the global exchange rate / adjustment factor used by items that don't set their own. Admin only."""
    with conn.cursor() as cur:
        cur.execute("SELECT global_exchange_rate, global_tax_coefficient FROM system_settings WHERE id = 1")
        settings = cur.fetchone()
        if not settings:
            raise HTTPException(status_code=500, detail="系統參數尚未初始化，請先執行資料庫 migration。")
        return {
            "global_exchange_rate": float(settings["global_exchange_rate"]),
            "global_tax_coefficient": float(settings["global_tax_coefficient"]),
        }


@app.put("/api/settings/global", dependencies=[Depends(verify_admin)])
def update_global_settings(payload: GlobalSettingsUpdate, current_user=Depends(get_current_user), conn=Depends(get_db_connection)):
    """Update the global exchange rate / adjustment factor. Admin only."""
    with conn.cursor() as cur:
        cur.execute(
            """UPDATE system_settings
               SET global_exchange_rate = %s, global_tax_coefficient = %s,
                   last_update_date = CURRENT_DATE, last_update_operator_id = %s
               WHERE id = 1""",
            (payload.global_exchange_rate, payload.global_tax_coefficient, current_user["operator_id"]),
        )
        if cur.rowcount == 0:
            raise HTTPException(status_code=500, detail="系統參數尚未初始化，請先執行資料庫 migration。")
        conn.commit()
    return {"message": "全域參數已更新"}


# ---------------------------------------------------------------------------
# Transactions: stock in / stock out
# ---------------------------------------------------------------------------
@app.post("/api/transactions")
def create_transaction(tx: TransactionCreate, current_user=Depends(get_current_user), conn=Depends(get_db_connection)):
    """Record a stock-in or stock-out transaction and update the running balance.

    Locks the stock row (FOR UPDATE) for the duration of the transaction to
    prevent lost updates from concurrent requests on the same item.
    """
    if tx.io_type not in ["IN", "OUT"]:
        raise HTTPException(status_code=400, detail="交易類型必須是 'IN' 或 'OUT'")
    if tx.transaction_qty <= 0:
        raise HTTPException(status_code=400, detail="交易數量必須大於 0")

    try:
        with conn.cursor() as cur:
            cur.execute("SELECT current_qty FROM stock_master WHERE item_id = %s FOR UPDATE", (tx.item_id,))
            stock = cur.fetchone()
            if not stock:
                raise HTTPException(status_code=404, detail="該品項無庫存主檔資料")

            current_qty = stock["current_qty"]
            qty_change = tx.transaction_qty if tx.io_type == "IN" else -tx.transaction_qty
            new_qty = current_qty + qty_change

            if new_qty < 0:
                raise HTTPException(status_code=400, detail=f"庫存不足！當前庫存: {current_qty}, 扣除失敗。")

            cur.execute(
                """UPDATE stock_master
                   SET current_qty = %s, last_update_date = CURRENT_DATE, last_update_time = CURRENT_TIME
                   WHERE item_id = %s""",
                (new_qty, tx.item_id),
            )

            cur.execute(
                """INSERT INTO stock_transactions
                   (transaction_date, transaction_time, item_id, io_type, transaction_qty, post_balance_qty, remark, operator_id)
                   VALUES (CURRENT_DATE, CURRENT_TIME, %s, %s, %s, %s, %s, %s)""",
                (tx.item_id, tx.io_type, tx.transaction_qty, new_qty, tx.remark, current_user["operator_id"]),
            )

            conn.commit()
        return {"message": "交易成功", "current_stock": new_qty}
    except Exception as e:
        conn.rollback()
        if isinstance(e, HTTPException):
            raise e
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/transactions/item/{item_id}")
def get_item_history(item_id: int, current_user=Depends(get_current_user), conn=Depends(get_db_connection)):
    """Return the transaction history for a single item, newest first."""
    with conn.cursor() as cur:
        cur.execute(
            """SELECT t.transaction_date, t.transaction_time, t."io_type",
                   CASE WHEN t.io_type = 'IN' THEN t.transaction_qty ELSE -t.transaction_qty END as qty,
                   t.post_balance_qty, t.remark, COALESCE(o.operator_name, '已刪除人員') AS operator_name
            FROM stock_transactions t
            LEFT JOIN operator_master o ON t.operator_id = o.operator_id
            WHERE t.item_id = %s
            ORDER BY t.transaction_date DESC, t.transaction_time DESC, t.tran_id DESC""",
            (item_id,),
        )
        return cur.fetchall()


@app.put("/api/transactions/{tran_id}/remark")
def update_transaction_remark(tran_id: int, data: TransactionRemarkUpdate, conn=Depends(get_db_connection)):
    """Update the remark/note on an existing transaction."""
    with conn.cursor() as cur:
        cur.execute("UPDATE stock_transactions SET remark = %s WHERE tran_id = %s", (data.remark, tran_id))
        if cur.rowcount == 0:
            raise HTTPException(status_code=404, detail="找不到該筆交易紀錄")
        conn.commit()
    return {"message": "備註更新成功"}


@app.get("/api/transactions/search")
def search_transactions_by_date(start_date: date, end_date: date, conn=Depends(get_db_connection)):
    """Search transactions across all items within a date range."""
    with conn.cursor() as cur:
        cur.execute(
            """SELECT t.transaction_date, t.transaction_time, i.item_name, t.io_type,
                      CASE WHEN t.io_type = 'IN' THEN t.transaction_qty ELSE -t.transaction_qty END as qty,
                      t.remark, o.operator_name
               FROM stock_transactions t
               JOIN item_master i ON t.item_id = i.item_id
               JOIN operator_master o ON t.operator_id = o.operator_id
               WHERE t.transaction_date BETWEEN %s AND %s
               ORDER BY t.transaction_date ASC, t.transaction_time ASC, i.item_name ASC""",
            (start_date, end_date),
        )
        return cur.fetchall()


# ---------------------------------------------------------------------------
# Reporting
# ---------------------------------------------------------------------------
@app.get("/api/stock/valuation")
def get_stock_valuation(conn=Depends(get_db_connection)):
    """Return current stock valuation per item plus the grand total (TWD).

    Per-item TWD amount = usd_price * exchange_rate_used * (1 + tax_coefficient_used) * current_qty

    exchange_rate/tax_coefficient in the response are the item's *raw*
    per-item override (null if the item uses the global fallback) -- used
    to correctly prefill the edit-item form as blank vs. filled.
    exchange_rate_used/tax_coefficient_used are the *resolved* values
    actually applied to this valuation (item override, or the global
    default) -- used for display, e.g. in the valuation report.
    is_global_rate/is_global_tax indicate which of the two was used.
    """
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT i.item_id, i.item_name, i.category, s.current_qty, i.usd_price,
                   i.exchange_rate, i.tax_coefficient,
                   COALESCE(i.exchange_rate, ss.global_exchange_rate) AS exchange_rate_used,
                   COALESCE(i.tax_coefficient, ss.global_tax_coefficient) AS tax_coefficient_used,
                   (i.exchange_rate IS NULL) AS is_global_rate,
                   (i.tax_coefficient IS NULL) AS is_global_tax,
                   s.last_update_date, s.last_update_time,
                   ROUND(CAST(i.usd_price * COALESCE(i.exchange_rate, ss.global_exchange_rate)
                       * (1 + COALESCE(i.tax_coefficient, ss.global_tax_coefficient)) AS NUMERIC), 4) AS unit_price_twd,
                   ROUND(CAST(i.usd_price * COALESCE(i.exchange_rate, ss.global_exchange_rate)
                       * (1 + COALESCE(i.tax_coefficient, ss.global_tax_coefficient)) * s.current_qty AS NUMERIC), 2) AS twd_amount
            FROM stock_master s
            JOIN item_master i ON s.item_id = i.item_id
            CROSS JOIN system_settings ss
            ORDER BY i.category, i.item_name
            """
        )
        details = cur.fetchall()

        # Date/time objects aren't JSON-serializable as-is; stringify before returning.
        for item in details:
            if item.get("last_update_date"):
                item["last_update_date"] = str(item["last_update_date"])
            if item.get("last_update_time"):
                item["last_update_time"] = str(item["last_update_time"])

        total_twd_val = sum(item["twd_amount"] for item in details)

        return {"details": details, "total_twd_amount": total_twd_val}


@app.get("/api/stock/global-history")
def get_global_transaction_history(conn=Depends(get_db_connection)):
    """Return the full stock transaction log across all items, newest first."""
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT t.tran_id, t.item_id, i.item_name, t.io_type, t.transaction_qty,
                   t.transaction_date, t.transaction_time, t.remark,
                   t.post_balance_qty, o.operator_name
            FROM stock_transactions t
            JOIN item_master i ON t.item_id = i.item_id
            LEFT JOIN operator_master o ON t.operator_id = o.operator_id
            ORDER BY t.transaction_date DESC, t.transaction_time DESC, t.tran_id DESC
            """
        )
        logs = cur.fetchall()

        # Date/time objects aren't JSON-serializable as-is; stringify before returning.
        # NOTE: previously these keys were misspelled ("tranaction_date"/"tran_time"),
        # which meant this stringification never actually ran. Fixed to match the
        # real column names returned by the query above.
        for log in logs:
            if log.get("transaction_date"):
                log["transaction_date"] = str(log["transaction_date"])
            if log.get("transaction_time"):
                log["transaction_time"] = str(log["transaction_time"])

        return logs


@app.get("/api/stock/monthly-summary", dependencies=[Depends(verify_admin)])
def get_monthly_stock_summary(conn=Depends(get_db_connection)):
    """Aggregate stock activity by calendar month, per item, for the trend chart screen.

    For each month (from the earliest transaction through the current month)
    and each item, returns:
      - incoming_qty / incoming_value: total stock-in that month
      - outgoing_qty / outgoing_value: total stock-out that month
      - ending_qty / ending_value: stock level at the end of that month
        (carried forward from the prior month if there was no activity)

    Valuation uses each item's *current* usd_price, and its own exchange_rate/
    tax_coefficient if set, otherwise the global fallback values (see
    GET /api/settings/global). There's no historical price table, so past
    months are valued at today's prices/rates, not what was actually in
    effect back then. Admin only.
    """
    with conn.cursor() as cur:
        cur.execute(
            """SELECT item_id, item_name, category, usd_price, exchange_rate, tax_coefficient
               FROM item_master ORDER BY category, item_name"""
        )
        items = cur.fetchall()

        cur.execute(
            """SELECT item_id, transaction_date, io_type, transaction_qty, post_balance_qty
               FROM stock_transactions
               ORDER BY item_id, transaction_date ASC, transaction_time ASC, tran_id ASC"""
        )
        transactions = cur.fetchall()

        cur.execute("SELECT global_exchange_rate, global_tax_coefficient FROM system_settings WHERE id = 1")
        settings = cur.fetchone()
        global_rate = float(settings["global_exchange_rate"]) if settings else 1.0
        global_tax = float(settings["global_tax_coefficient"]) if settings else 0.0

    if not items:
        return {"months": [], "items": [], "data": []}

    # Build the full month range to report: earliest transaction through the
    # current month (or just the current month, if there's no history yet).
    today = date.today()
    if transactions:
        earliest = min(t["transaction_date"] for t in transactions)
        year, month = earliest.year, earliest.month
    else:
        year, month = today.year, today.month

    months = []
    while (year, month) <= (today.year, today.month):
        months.append(f"{year:04d}-{month:02d}")
        month += 1
        if month > 12:
            month = 1
            year += 1

    # Bucket each item's transactions by month: total IN, total OUT, and the
    # balance left by the last transaction in that month (used for the
    # month-end "current inventory" figure).
    tx_by_item_month = {}
    for t in transactions:
        key_month = f"{t['transaction_date'].year:04d}-{t['transaction_date'].month:02d}"
        bucket = tx_by_item_month.setdefault((t["item_id"], key_month), {"in": 0, "out": 0, "last_balance": None})
        if t["io_type"] == "IN":
            bucket["in"] += t["transaction_qty"]
        else:
            bucket["out"] += t["transaction_qty"]
        bucket["last_balance"] = t["post_balance_qty"]  # transactions are in chronological order

    running_balance = {}  # item_id -> most recent known ending balance, carried across months
    result_months = []

    for month_label in months:
        month_entry = {"month": month_label, "items": []}

        for item in items:
            item_rate = float(item["exchange_rate"]) if item["exchange_rate"] is not None else global_rate
            item_tax = float(item["tax_coefficient"]) if item["tax_coefficient"] is not None else global_tax
            unit_value = float(item["usd_price"]) * item_rate * (1 + item_tax)
            bucket = tx_by_item_month.get((item["item_id"], month_label))

            incoming_qty = bucket["in"] if bucket else 0
            outgoing_qty = bucket["out"] if bucket else 0

            if bucket and bucket["last_balance"] is not None:
                ending_qty = bucket["last_balance"]
                running_balance[item["item_id"]] = ending_qty
            else:
                ending_qty = running_balance.get(item["item_id"], 0)

            month_entry["items"].append({
                "item_id": item["item_id"],
                "item_name": item["item_name"],
                "category": item["category"],
                "incoming_qty": incoming_qty,
                "incoming_value": round(incoming_qty * unit_value, 2),
                "outgoing_qty": outgoing_qty,
                "outgoing_value": round(outgoing_qty * unit_value, 2),
                "ending_qty": ending_qty,
                "ending_value": round(ending_qty * unit_value, 2),
            })

        result_months.append(month_entry)

    return {
        "months": months,
        "items": [{"item_id": i["item_id"], "item_name": i["item_name"], "category": i["category"]} for i in items],
        "data": result_months,
    }


# ---------------------------------------------------------------------------
# Startup: ensure a default Admin account always exists
# ---------------------------------------------------------------------------
def init_admin_account():
    """Ensure an Admin operator exists with a known-good password hash.

    Runs once at import time. If no Admin exists, one is created with
    DEFAULT_ADMIN_PASSWORD; if one exists, its password hash is refreshed
    (useful after a hashing library upgrade). Change the default password
    immediately after first login in any shared environment.
    """
    try:
        conn = _connect()
        with conn.cursor() as cur:
            correct_hash = get_password_hash(DEFAULT_ADMIN_PASSWORD)
            cur.execute("SELECT operator_id FROM operator_master WHERE operator_name = 'Admin';")
            user = cur.fetchone()

            if user:
                cur.execute(
                    "UPDATE operator_master SET password_hash = %s, is_admin = TRUE WHERE operator_name = 'Admin';",
                    (correct_hash,),
                )
                conn.commit()
                logger.info("Existing Admin account found; password hash refreshed.")
            else:
                cur.execute(
                    "INSERT INTO operator_master (operator_name, password_hash, is_admin) VALUES (%s, %s, %s);",
                    ("Admin", correct_hash, True),
                )
                conn.commit()
                logger.info("No Admin account found; created a new one.")
        conn.close()
    except Exception as e:
        logger.error("Failed to initialize Admin account: %s", e)


init_admin_account()


def init_system_settings():
    """Ensure the system_settings singleton row exists (default rate 32.0 / factor 0.05).

    Defensive only -- the migration script (migration_global_rate_settings.sql)
    already seeds this row. If that migration hasn't been run yet, this
    fails harmlessly and is logged rather than crashing the app; the
    global-settings and valuation endpoints will error until the migration
    is applied.
    """
    try:
        conn = _connect()
        with conn.cursor() as cur:
            cur.execute(
                """INSERT INTO system_settings (id, global_exchange_rate, global_tax_coefficient)
                   VALUES (1, 32.0000, 0.0500) ON CONFLICT (id) DO NOTHING"""
            )
            conn.commit()
        conn.close()
    except Exception as e:
        logger.warning(
            "Could not verify/seed system_settings (has migration_global_rate_settings.sql been run?): %s", e
        )


init_system_settings()

if __name__ == "__main__":
    import uvicorn

    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)
