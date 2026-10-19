#!/usr/bin/env python3
"""
Talks to the RuStore API from CI: checks the key, uploads a build as a draft version
and, if asked, sends it to moderation.

    rustore.py check                  sign in and list the versions; changes nothing
    rustore.py upload APK [--submit]  the APK as a draft of a new version, then maybe to moderation

The key comes from RuStore Console -> API RuStore, in RUSTORE_KEY_ID and RUSTORE_PRIVATE_KEY.
Only the standard library and the openssl command are used. The key and the token are never printed.
"""
import base64
import datetime
import json
import os
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import uuid

API = os.environ.get("RUSTORE_API", "https://public-api.rustore.ru")
PACKAGE = "com.makc.basedefense"
# Android 8.0, the game's minSdk 26.
MIN_ANDROID = 8
LISTING = os.path.join(os.path.dirname(os.path.abspath(__file__)), "listing-rustore.md")


class ApiError(Exception):
    pass


def pem_key(secret: str, workdir: str) -> str:
    """The private key as a PEM file, whether it came as PEM, or as Base64 of PKCS#8 or PKCS#1 or of a PEM."""
    text = secret.strip()
    path = os.path.join(workdir, "key.pem")
    if "-----BEGIN" in text:
        with open(path, "w") as f:
            f.write(text + "\n")
        return path
    raw = base64.b64decode("".join(text.split()))
    if raw.lstrip().startswith(b"-----BEGIN"):
        with open(path, "wb") as f:
            f.write(raw)
        return path
    der = os.path.join(workdir, "key.der")
    with open(der, "wb") as f:
        f.write(raw)
    for tool in (["openssl", "pkey"], ["openssl", "rsa"]):
        done = subprocess.run(tool + ["-inform", "DER", "-in", der, "-out", path], capture_output=True)
        if done.returncode == 0:
            return path
    raise ApiError("the private key is neither PEM nor Base64 of a DER key")


def sign(message: str, key_file: str) -> str:
    """SHA512withRSA of [message], in Base64."""
    done = subprocess.run(["openssl", "dgst", "-sha512", "-sign", key_file], input=message.encode(), capture_output=True)
    if done.returncode != 0:
        raise ApiError("openssl could not sign: " + done.stderr.decode(errors="replace").strip())
    return base64.b64encode(done.stdout).decode()


def call(method: str, path: str, token: str = None, body=None, data: bytes = None, content_type: str = None):
    """One request; gives the answer's body, or raises with RuStore's own message."""
    headers = {"Accept": "application/json"}
    if token:
        headers["Public-Token"] = token
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode()
        content_type = "application/json"
    if content_type:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(API + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            text = r.read().decode(errors="replace")
    except urllib.error.HTTPError as e:
        text = e.read().decode(errors="replace")
        raise ApiError(f"{method} {path.split('?')[0]}: HTTP {e.code}: {message(text)}")
    answer = json.loads(text) if text.strip() else {}
    if str(answer.get("code", "OK")).upper() != "OK":
        raise ApiError(f"{method} {path.split('?')[0]}: {message(text)}")
    return answer.get("body")


def message(text: str) -> str:
    try:
        answer = json.loads(text)
        return str(answer.get("message") or answer.get("code") or text)
    except ValueError:
        return text.strip()[:500]


def sign_in(workdir: str) -> str:
    key_id = os.environ.get("RUSTORE_KEY_ID", "").strip()
    secret = os.environ.get("RUSTORE_PRIVATE_KEY", "")
    if not key_id or not secret.strip():
        raise ApiError("RUSTORE_KEY_ID and RUSTORE_PRIVATE_KEY are not set")
    stamp = datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="milliseconds")
    signature = sign(key_id + stamp, pem_key(secret, workdir))
    body = call("POST", "/public/auth/", body={"keyId": key_id, "timestamp": stamp, "signature": signature})
    token = (body or {}).get("jwe")
    if not token:
        raise ApiError("RuStore gave no token")
    return token


def versions(token: str) -> list:
    body = call("GET", f"/public/v1/application/{PACKAGE}/version?pageSize=20", token)
    return (body or {}).get("content", []) if isinstance(body, dict) else []


def whats_new() -> str:
    """The "What's new" section of the RuStore listing."""
    out = []
    taking = False
    with open(LISTING, encoding="utf-8") as f:
        for line in f:
            if line.startswith("## "):
                taking = line.startswith("## Что нового")
                continue
            if taking:
                out.append(line)
    return "".join(out).strip()


def multipart(field: str, filename: str, payload: bytes, kind: str):
    boundary = uuid.uuid4().hex
    head = (
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"{field}\"; filename=\"{filename}\"\r\n"
        f"Content-Type: {kind}\r\n\r\n"
    ).encode()
    return head + payload + f"\r\n--{boundary}--\r\n".encode(), f"multipart/form-data; boundary={boundary}"


def draft(token: str) -> int:
    """A new draft version, or the draft that is already there: RuStore keeps one at a time."""
    try:
        body = call("POST", f"/public/v1/application/{PACKAGE}/version", token, body={
            "whatsNew": whats_new(),
            "minAndroidVersion": MIN_ANDROID,
        })
        return int(body["versionId"] if isinstance(body, dict) else body)
    except ApiError as first:
        for v in versions(token):
            if "DRAFT" in str(v.get("versionStatus", v.get("status", ""))).upper():
                print(f"A draft is already there, version {v.get('versionId')}: the APK goes into it.")
                return int(v["versionId"])
        raise first


def summary(lines: list):
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    text = "\n".join(lines) + "\n"
    print(text)
    if path:
        with open(path, "a", encoding="utf-8") as f:
            f.write(text)


def table(token: str) -> list:
    rows = ["| ID | Версия | Код | Статус |", "|---|---|---|---|"]
    for v in versions(token):
        rows.append(f"| {v.get('versionId')} | {v.get('versionName', '')} | {v.get('versionCode', '')} | {v.get('versionStatus', v.get('status', ''))} |")
    return rows


def main(args: list) -> int:
    if not args or args[0] not in ("check", "upload") or (args[0] == "upload" and len(args) < 2):
        print(__doc__)
        return 2
    with tempfile.TemporaryDirectory() as workdir:
        try:
            token = sign_in(workdir)
            if args[0] == "check":
                summary(["### RuStore: ключ работает", "", *table(token)])
                return 0
            apk = args[1]
            version = draft(token)
            with open(apk, "rb") as f:
                data, kind = multipart("file", os.path.basename(apk), f.read(), "application/vnd.android.package-archive")
            call("POST", f"/public/v1/application/{PACKAGE}/version/{version}/apk?isMainApk=true&servicesType=Unknown", token, data=data, content_type=kind)
            lines = [f"### RuStore: APK загружен в черновик версии {version}"]
            if "--submit" in args:
                call("POST", f"/public/v1/application/{PACKAGE}/version/{version}/commit", token)
                lines.append("Версия отправлена на модерацию.")
            else:
                lines.append("Проверь черновик в RuStore Консоли и отправь его на модерацию.")
            summary(lines + ["", *table(token)])
            return 0
        except (ApiError, OSError, ValueError, KeyError) as e:
            summary([f"### RuStore: не получилось", "", f"`{e}`"])
            return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
