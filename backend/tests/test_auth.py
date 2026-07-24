"""Tests for /api/login and JWT-based auth (get_current_user / verify_admin)."""
import time

import jwt


def test_login_success_returns_token_and_admin_flag(client):
    response = client.post("/api/login", data={"username": "Admin", "password": "admin123"})
    assert response.status_code == 200
    body = response.json()
    assert body["access_token"]
    assert body["token_type"] == "bearer"
    assert body["is_admin"] is True
    assert body["operator_id"] == 1


def test_login_wrong_password_rejected(client):
    response = client.post("/api/login", data={"username": "Admin", "password": "wrong-password"})
    assert response.status_code == 400


def test_login_nonexistent_user_rejected(client):
    response = client.post("/api/login", data={"username": "NoSuchUser", "password": "whatever"})
    assert response.status_code == 400


def test_request_without_token_rejected(client):
    response = client.get("/api/operators")
    assert response.status_code == 401


def test_request_with_garbage_token_rejected(client):
    response = client.get("/api/operators", headers={"Authorization": "Bearer not-a-real-token"})
    assert response.status_code == 401


def test_expired_token_rejected(client):
    """Regression: a token whose exp claim is in the past must be rejected,
    not silently accepted."""
    import main

    expired_payload = {"sub": "Admin", "exp": int(time.time()) - 60}
    expired_token = jwt.encode(expired_payload, main.SECRET_KEY, algorithm=main.ALGORITHM)

    response = client.get("/api/operators", headers={"Authorization": f"Bearer {expired_token}"})
    assert response.status_code == 401


def test_non_admin_blocked_from_admin_only_endpoint(client, make_operator):
    make_operator("regular_joe", "pw1234", is_admin=False)
    login = client.post("/api/login", data={"username": "regular_joe", "password": "pw1234"})
    assert login.status_code == 200
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}

    response = client.get("/api/operators", headers=headers)
    assert response.status_code == 403


def test_admin_can_access_admin_only_endpoint(client, admin_headers):
    response = client.get("/api/operators", headers=admin_headers)
    assert response.status_code == 200
