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
from psycopg2.extras import RealDictCursor
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
    exchange_rate: float
    tax_coefficient: float


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
    """Create a new item and its zero-quantity stock entry."""
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
    """Update an existing item's master data."""
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

    Per-item TWD amount = usd_price * exchange_rate * (1 + tax_coefficient) * current_qty
    """
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT i.item_id, i.item_name, i.category, s.current_qty, i.usd_price, i.exchange_rate, i.tax_coefficient,
                   s.last_update_date, s.last_update_time,
                   ROUND(CAST(i.usd_price * i.exchange_rate * (1 + i.tax_coefficient) * s.current_qty AS NUMERIC), 2) as twd_amount
            FROM stock_master s
            JOIN item_master i ON s.item_id = i.item_id
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

    Valuation uses each item's *current* usd_price/exchange_rate/tax_coefficient
    from item_master. There's no historical price table, so past months are
    valued at today's prices, not the prices that were actually in effect then.
    Admin only.
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
            unit_value = float(item["usd_price"]) * float(item["exchange_rate"]) * (1 + float(item["tax_coefficient"]))
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

if __name__ == "__main__":
    import uvicorn

    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)
