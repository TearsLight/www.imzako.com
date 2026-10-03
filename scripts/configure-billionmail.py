#!/usr/bin/env python3
"""Configure BillionMail: create/update the verification-code email template and
the dedicated send API (noreply@imzako.com), then print the send API key.

Runs on the BillionMail host against the local core (http://localhost:8080).
Usage:
    BILLIONMAIL_API_TOKEN=... HTML_FILE=/path/to/verification-code.html \
        python3 configure-billionmail.py [base_url]
"""
import json
import os
import sys
import urllib.request
import urllib.parse

TEMPLATE_NAME = "imzako-verification-code"
API_NAME = "imzako-verification-code-api"
SENDER = "noreply@imzako.com"
SENDER_NAME = "imzako"
SUBJECT = "{{.API.code}} 是你的 imzako 验证码"

BASE = (sys.argv[1] if len(sys.argv) > 1 else os.environ.get("BILLIONMAIL_API_URL", "http://localhost:8080")).rstrip("/")
TOKEN = os.environ.get("BILLIONMAIL_API_TOKEN", "")
HTML_FILE = os.environ.get("HTML_FILE", "/tmp/verification-code.html")

if not TOKEN:
    print("missing BILLIONMAIL_API_TOKEN", file=sys.stderr)
    sys.exit(1)

with open(HTML_FILE, "r", encoding="utf-8") as fh:
    HTML = fh.read()


def call(method, path, payload=None, headers=None):
    url = BASE + path
    data = None
    hdrs = {"Authorization": "Bearer " + TOKEN}
    if headers:
        hdrs.update(headers)
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        hdrs["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    with urllib.request.urlopen(req, timeout=30) as resp:
        body = resp.read().decode("utf-8")
    return json.loads(body)


def list_items(path, keyword, extra=None):
    params = {"page": 1, "page_size": 100, "keyword": keyword}
    if extra:
        params.update(extra)
    qs = urllib.parse.urlencode(params)
    r = call("GET", path + "?" + qs)
    data = r.get("data") or {}
    return r, (data.get("list") or [])


# 1. Template
r, items = list_items("/api/email_template/list", TEMPLATE_NAME, {"add_type": -1})
template = next((t for t in items if t.get("temp_name") == TEMPLATE_NAME), None)
if template:
    call("POST", "/api/email_template/update", {
        "id": int(template["id"]),
        "temp_name": TEMPLATE_NAME,
        "html_content": HTML,
        "drag_data": "",
    })
    template_id = int(template["id"])
    template_action = "updated"
else:
    created = call("POST", "/api/email_template/create", {
        "temp_name": TEMPLATE_NAME,
        "add_type": 0,
        "html_content": HTML,
        "drag_data": "",
        "chat_id": "",
    })
    template_id = int(created["data"]["id"])
    template_action = "created"

# 2. Send API (active=-1 returns both active and inactive)
r, items = list_items("/api/batch_mail/api/list", API_NAME, {"active": -1})
api = next((a for a in items if a.get("api_name") == API_NAME), None)
api_payload = {
    "api_name": API_NAME,
    "template_id": template_id,
    "group_id": 0,
    "subject": SUBJECT,
    "addresser": SENDER,
    "full_name": SENDER_NAME,
    "unsubscribe": 0,
    "track_open": 0,
    "track_click": 0,
    "active": 1,
    "expire_time": 0,
    "ip_whitelist": [],
}
if api:
    api_payload["id"] = int(api["id"])
    api_payload["reset_key"] = False
    call("POST", "/api/batch_mail/api/update", api_payload)
    api_action = "updated"
else:
    call("POST", "/api/batch_mail/api/create", api_payload)
    api_action = "created"

# 3. Re-fetch to obtain the send API key
r, items = list_items("/api/batch_mail/api/list", API_NAME, {"active": -1})
api = next((a for a in items if a.get("api_name") == API_NAME), None)
if not api or not api.get("api_key"):
    print("BillionMail did not return the send API key", file=sys.stderr)
    sys.exit(1)

print(json.dumps({
    "template": template_action,
    "template_id": template_id,
    "send_api": api_action,
    "api_id": int(api["id"]),
    "sender": SENDER,
    "api_key": api["api_key"],
}, ensure_ascii=False, indent=2))
