#!/usr/bin/env python3
"""Prepare three real ZH-F03 actors without persisting credentials or tokens."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import secrets
import sys
from typing import Any, Callable
from urllib import error, request


class DriverFailure(RuntimeError):
    pass


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest().upper()


class _HttpResponse:
    def __init__(self, status_code: int, payload: dict[str, Any]):
        self.status_code = status_code
        self._payload = payload

    def json(self) -> dict[str, Any]:
        return self._payload


def _request_json(method: str, url: str, **kwargs: Any) -> _HttpResponse:
    payload = kwargs.get("json")
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    headers = {"Content-Type": "application/json", **kwargs.get("headers", {})}
    http_request = request.Request(url, data=data, headers=headers, method=method)
    try:
        with request.urlopen(http_request, timeout=15) as response:
            raw = response.read()
            return _HttpResponse(response.status, json.loads(raw.decode("utf-8")) if raw else {})
    except error.HTTPError as exc:
        raw = exc.read()
        try:
            body = json.loads(raw.decode("utf-8")) if raw else {}
        except (UnicodeDecodeError, json.JSONDecodeError):
            body = {"message": "non-JSON HTTP error"}
        return _HttpResponse(exc.code, body)
    except error.URLError as exc:
        raise DriverFailure(f"authentication endpoint is unavailable: {exc.reason}") from exc


def _body(response: Any, operation: str) -> dict[str, Any]:
    try:
        body = response.json()
    except Exception as exc:
        raise DriverFailure(f"{operation} returned invalid JSON") from exc
    if not isinstance(body, dict):
        raise DriverFailure(f"{operation} returned a non-object body")
    return body


def _login(
    request_fn: Callable[..., Any], app_url: str, username: str, password: str,
) -> str:
    response = request_fn(
        "POST",
        app_url.rstrip("/") + "/api/v1/users/login",
        json={"username": username, "password": password},
    )
    body = _body(response, "login")
    token = (body.get("data") or {}).get("token") if isinstance(body.get("data"), dict) else None
    if response.status_code // 100 != 2 or body.get("code") != 200 or not isinstance(token, str) or not token:
        raise DriverFailure(f"login failed for actor hash {sha256_text(username)}")
    return token


def _register_and_login(
    request_fn: Callable[..., Any], app_url: str, username: str, password: str,
) -> str:
    response = request_fn(
        "POST",
        app_url.rstrip("/") + "/api/v1/users/register",
        json={"username": username, "password": password, "inviteCode": None},
    )
    body = _body(response, "registration")
    message = str(body.get("message") or "")
    if response.status_code // 100 != 2 or body.get("code") != 200:
        if "already exists" in message.lower():
            try:
                return _login(request_fn, app_url, username, password)
            except DriverFailure as exc:
                raise DriverFailure("cannot authenticate generated experimental account") from exc
        if response.status_code == 403 or "invite" in message.lower() or "registration_closed" in message.lower():
            raise DriverFailure(f"registration policy rejected experimental actor: {message}")
        raise DriverFailure(f"experimental actor registration failed: HTTP {response.status_code}")
    return _login(request_fn, app_url, username, password)


def _profile(request_fn: Callable[..., Any], app_url: str, token: str) -> dict[str, Any]:
    response = request_fn(
        "GET",
        app_url.rstrip("/") + "/api/v1/users/me",
        headers={"Authorization": f"Bearer {token}"},
    )
    body = _body(response, "actor profile")
    data = body.get("data")
    if response.status_code // 100 != 2 or body.get("code") != 200 or not isinstance(data, dict):
        raise DriverFailure("cannot validate experimental actor profile")
    if data.get("id") is None or not isinstance(data.get("username"), str) or not isinstance(data.get("role"), str):
        raise DriverFailure("experimental actor profile is incomplete")
    return data


def _password() -> str:
    # The application requires 6-18 characters with at least one letter and digit.
    return "Aa1" + secrets.token_urlsafe(9)


def prepare_auth(
    request_fn: Callable[..., Any],
    app_url: str,
    admin_user: str,
    admin_password: str,
    session_id: str,
) -> dict[str, Any]:
    if not all(isinstance(value, str) and value for value in (app_url, admin_user, admin_password, session_id)):
        raise DriverFailure("authentication preparation inputs must be non-empty")
    suffix = sha256_text(session_id).lower()[:12]
    owner_name = f"zhf03_owner_{suffix}"
    other_name = f"zhf03_other_{suffix}"

    owner_token = _register_and_login(request_fn, app_url, owner_name, _password())
    other_token = _register_and_login(request_fn, app_url, other_name, _password())
    admin_token = _login(request_fn, app_url, admin_user, admin_password)
    owner_profile = _profile(request_fn, app_url, owner_token)
    admin_profile = _profile(request_fn, app_url, admin_token)
    other_profile = _profile(request_fn, app_url, other_token)

    if admin_profile["role"] != "ADMIN":
        raise DriverFailure("admin actor does not have ADMIN role")
    if owner_profile["role"] != "USER" or other_profile["role"] != "USER":
        raise DriverFailure("generated experimental actors must have USER role")
    tokens = {owner_token, admin_token, other_token}
    if len(tokens) != 3:
        raise DriverFailure("experimental actor tokens are not distinct")

    def evidence(profile: dict[str, Any], token: str) -> dict[str, Any]:
        return {
            "usernameHash": sha256_text(profile["username"]),
            "tokenHash": sha256_text(token),
            "userId": profile["id"],
            "role": profile["role"],
        }

    return {
        "ownerToken": owner_token,
        "adminToken": admin_token,
        "otherToken": other_token,
        "ownerUserId": owner_profile["id"],
        "adminUserId": admin_profile["id"],
        "otherUserId": other_profile["id"],
        "evidence": {
            "owner": evidence(owner_profile, owner_token),
            "admin": evidence(admin_profile, admin_token),
            "other": evidence(other_profile, other_token),
            "distinctTokens": True,
        },
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Prepare real ZH-F03 authentication actors")
    parser.add_argument("--app-url", required=True)
    parser.add_argument("--session-id", required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    admin_user = os.environ.get("ZH_F03_ADMIN_USERNAME") or os.environ.get("ADMIN_BOOTSTRAP_USERNAME")
    admin_password = os.environ.get("ZH_F03_ADMIN_PASSWORD") or os.environ.get("ADMIN_BOOTSTRAP_PASSWORD")
    if not admin_user or not admin_password:
        print("required admin credential environment variables are missing", file=sys.stderr)
        return 2
    try:
        result = prepare_auth(_request_json, args.app_url, admin_user, admin_password, args.session_id)
    except DriverFailure as exc:
        print(str(exc), file=sys.stderr)
        return 3
    # This stdout is a private parent-process pipe. Callers must never redirect it to evidence files.
    print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
