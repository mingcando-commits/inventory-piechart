"""Tests for /api/operators and /api/operators/{id}/password."""


def test_create_operator(client, admin_headers):
    response = client.post(
        "/api/operators", json={"operator_name": "newop", "password": "pw1234", "is_admin": False},
        headers=admin_headers,
    )
    assert response.status_code == 200

    listing = client.get("/api/operators", headers=admin_headers).json()
    names = [o["operator_name"] for o in listing]
    assert "newop" in names


def test_create_operator_duplicate_name_rejected(client, admin_headers, make_operator):
    make_operator("dupe", "pw1234")
    response = client.post(
        "/api/operators", json={"operator_name": "dupe", "password": "another", "is_admin": False},
        headers=admin_headers,
    )
    assert response.status_code == 400


def test_non_admin_cannot_create_operator(client, make_operator):
    make_operator("plain_op", "pw1234", is_admin=False)
    login = client.post("/api/login", data={"username": "plain_op", "password": "pw1234"})
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}

    response = client.post(
        "/api/operators", json={"operator_name": "sneaky", "password": "pw1234", "is_admin": False},
        headers=headers,
    )
    assert response.status_code == 403


def test_delete_operator(client, admin_headers, make_operator):
    operator_id = make_operator("to_delete", "pw1234")
    response = client.delete(f"/api/operators/{operator_id}", headers=admin_headers)
    assert response.status_code == 200

    listing = client.get("/api/operators", headers=admin_headers).json()
    assert operator_id not in [o["operator_id"] for o in listing]


def test_self_password_change_requires_correct_old_password(client, make_operator):
    make_operator("selfchanger", "correct-old-pw", is_admin=False)
    login = client.post("/api/login", data={"username": "selfchanger", "password": "correct-old-pw"})
    operator_id = login.json()["operator_id"]
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}

    # Wrong old password -> rejected.
    bad = client.post(
        f"/api/operators/{operator_id}/password",
        json={"old_password": "wrong-old-pw", "new_password": "newpw1234"},
        headers=headers,
    )
    assert bad.status_code == 400

    # Correct old password -> succeeds.
    good = client.post(
        f"/api/operators/{operator_id}/password",
        json={"old_password": "correct-old-pw", "new_password": "newpw1234"},
        headers=headers,
    )
    assert good.status_code == 200

    # New password actually works for a fresh login.
    relogin = client.post("/api/login", data={"username": "selfchanger", "password": "newpw1234"})
    assert relogin.status_code == 200


def test_admin_can_change_others_password_without_old_password(client, admin_headers, make_operator):
    """Admins changing someone ELSE's password don't need to know that person's old password."""
    operator_id = make_operator("victim", "original-pw", is_admin=False)

    response = client.post(
        f"/api/operators/{operator_id}/password",
        json={"old_password": "", "new_password": "admin-set-this"},
        headers=admin_headers,
    )
    assert response.status_code == 200

    relogin = client.post("/api/login", data={"username": "victim", "password": "admin-set-this"})
    assert relogin.status_code == 200


def test_non_admin_cannot_change_someone_elses_password(client, make_operator):
    make_operator("victim2", "pw1234", is_admin=False)
    make_operator("attacker", "pw1234", is_admin=False)
    login = client.post("/api/login", data={"username": "attacker", "password": "pw1234"})
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}

    # Find victim2's operator_id via a fresh admin login (regular operators can't list operators).
    admin_login = client.post("/api/login", data={"username": "Admin", "password": "admin123"})
    admin_headers = {"Authorization": f"Bearer {admin_login.json()['access_token']}"}
    victim_id = next(
        o["operator_id"] for o in client.get("/api/operators", headers=admin_headers).json()
        if o["operator_name"] == "victim2"
    )

    response = client.post(
        f"/api/operators/{victim_id}/password",
        json={"old_password": "", "new_password": "hacked"},
        headers=headers,
    )
    assert response.status_code == 403
