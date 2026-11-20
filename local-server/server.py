#!/usr/bin/env python3
"""
خادم محلي دائم لاستقبال وأرشفة رسائل WhatsAppAutoDelete v2.2 عبر الشبكة المحلية (LAN).
يعمل بمكتبات بايثون القياسية فقط بدون الحاجة لتثبيت أي حزم خارجية.
"""

import argparse
import csv
import html
import io
import json
import os
import socket
import sqlite3
import threading
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, quote, urlparse

DB_PATH = "messages.db"
JSONL_PATH = "messages.jsonl"
API_TOKEN = ""
WRITE_LOCK = threading.Lock()
MAX_BODY_BYTES = 2 * 1024 * 1024


def init_db(db_path: str) -> None:
    conn = sqlite3.connect(db_path, timeout=15.0)
    conn.execute("PRAGMA journal_mode=WAL;")
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS messages (
            id TEXT PRIMARY KEY,
            contact TEXT NOT NULL,
            phone TEXT DEFAULT '',
            sender TEXT,
            message TEXT NOT NULL,
            app TEXT,
            is_media INTEGER DEFAULT 0,
            device TEXT,
            timestamp_ms INTEGER,
            received_at TEXT NOT NULL
        )
        """
    )
    # ترقية تلقائية لقواعد البيانات القديمة إذا لم يكن عمود phone موجوداً
    cols = [row[1] for row in conn.execute("PRAGMA table_info(messages)").fetchall()]
    if "phone" not in cols:
        conn.execute("ALTER TABLE messages ADD COLUMN phone TEXT DEFAULT ''")
    conn.commit()
    conn.close()


def get_lan_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def format_ts(ts_ms: int) -> str:
    try:
        return datetime.fromtimestamp(ts_ms / 1000.0).strftime("%Y-%m-%d %H:%M:%S")
    except Exception:
        return str(ts_ms)


def parse_timestamp_ms(val) -> int:
    fallback = int(datetime.now().timestamp() * 1000)
    if val is None or val == "":
        return fallback
    try:
        parsed = int(val)
        return parsed if parsed > 0 else fallback
    except (ValueError, TypeError):
        return fallback


class ArchiveHandler(BaseHTTPRequestHandler):
    def _is_authorized(self, params: dict) -> bool:
        if not API_TOKEN:
            return True
        header_token = self.headers.get("X-API-Key", "")
        query_token = params.get("token", [""])[0]
        return header_token == API_TOKEN or query_token == API_TOKEN

    def _check_api_auth(self, params: dict) -> bool:
        if not self._is_authorized(params):
            self._send_json(401, {"error": "Unauthorized"})
            return False
        return True

    def _send_json(self, code: int, payload: dict) -> None:
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _send_html(self, code: int, body: str) -> None:
        raw = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        params = parse_qs(parsed.query)

        if parsed.path == "/health":
            if not self._check_api_auth(params):
                return
            conn = sqlite3.connect(DB_PATH, timeout=15.0)
            count = conn.execute("SELECT COUNT(*) FROM messages").fetchone()[0]
            conn.close()
            self._send_json(200, {"status": "ok", "count": count})
            return

        if parsed.path == "/api/messages":
            if not self._check_api_auth(params):
                return
            q = params.get("q", [""])[0].strip()
            contact = params.get("contact", [""])[0].strip()
            rows = self._query_messages(q, contact)
            self._send_json(200, {"count": len(rows), "messages": rows})
            return

        if parsed.path == "/export.csv":
            if not self._check_api_auth(params):
                return
            rows = self._query_messages("", "")
            buf = io.StringIO()
            writer = csv.writer(buf)
            writer.writerow(["ID", "Contact", "Phone", "Sender", "Message", "App", "IsMedia", "Device", "Timestamp", "ReceivedAt"])
            for r in rows:
                writer.writerow([
                    r["id"], r["contact"], r.get("phone", ""), r["sender"], r["message"],
                    r["app"], r["is_media"], r["device"], format_ts(r["timestamp_ms"]), r["received_at"]
                ])
            raw = ("\ufeff" + buf.getvalue()).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/csv; charset=utf-8")
            self.send_header("Content-Disposition", 'attachment; filename="whatsapp_archive.csv"')
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)
            return

        if parsed.path == "/":
            if not self._is_authorized(params):
                self._send_html(401, self._render_token_prompt())
                return
            q = params.get("q", [""])[0].strip()
            contact = params.get("contact", [""])[0].strip()
            token_param = params.get("token", [""])[0]
            rows = self._query_messages(q, contact)
            self._send_html(200, self._render_dashboard(rows, q, contact, token_param))
            return

        self._send_json(404, {"error": "Not found"})

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        params = parse_qs(parsed.query)
        if parsed.path != "/api/messages":
            self._send_json(404, {"error": "Not found"})
            return

        if not self._check_api_auth(params):
            return

        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self._send_json(400, {"error": "Invalid Content-Length"})
            return

        if length <= 0 or length > MAX_BODY_BYTES:
            self._send_json(400, {"error": "Invalid request body size"})
            return

        try:
            body = self.rfile.read(length).decode("utf-8")
            data = json.loads(body)
        except Exception as exc:
            self._send_json(400, {"error": f"Invalid JSON: {exc}"})
            return

        if not isinstance(data, dict):
            self._send_json(400, {"error": "JSON payload must be an object"})
            return

        msg_id = str(data.get("id") or f"msg-{int(datetime.now().timestamp() * 1000)}").strip()
        contact = str(data.get("contact") or "").strip()
        phone = str(data.get("phone") or "").strip()
        sender = str(data.get("sender") or contact).strip()
        message = str(data.get("message") or "").strip()
        app = str(data.get("app") or "com.whatsapp").strip()
        is_media = 1 if data.get("is_media") else 0
        device = str(data.get("device") or "").strip()
        timestamp_ms = parse_timestamp_ms(data.get("timestamp_ms"))
        received_at = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

        if not message:
            self._send_json(400, {"error": "Empty message"})
            return

        with WRITE_LOCK:
            conn = sqlite3.connect(DB_PATH, timeout=15.0)
            cur = conn.execute(
                """
                INSERT OR IGNORE INTO messages
                (id, contact, phone, sender, message, app, is_media, device, timestamp_ms, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (msg_id, contact, phone, sender, message, app, is_media, device, timestamp_ms, received_at),
            )
            inserted = cur.rowcount > 0
            conn.commit()
            conn.close()

            if inserted:
                record = {
                    "id": msg_id,
                    "contact": contact,
                    "phone": phone,
                    "sender": sender,
                    "message": message,
                    "app": app,
                    "is_media": bool(is_media),
                    "device": device,
                    "timestamp_ms": timestamp_ms,
                    "received_at": received_at,
                }
                with open(JSONL_PATH, "a", encoding="utf-8") as f:
                    f.write(json.dumps(record, ensure_ascii=False) + "\n")

        self._send_json(200, {"status": "saved", "id": msg_id, "duplicate": not inserted})

    def _query_messages(self, q: str, contact: str):
        conn = sqlite3.connect(DB_PATH, timeout=15.0)
        conn.row_factory = sqlite3.Row
        sql = "SELECT * FROM messages WHERE 1=1"
        args = []
        if q:
            sql += " AND (message LIKE ? OR contact LIKE ? OR sender LIKE ? OR phone LIKE ?)"
            like = f"%{q}%"
            args.extend([like, like, like, like])
        if contact:
            sql += " AND (contact = ? OR phone LIKE ?)"
            args.extend([contact, f"%{contact}%"])
        sql += " ORDER BY timestamp_ms DESC LIMIT 300"
        rows = [dict(r) for r in conn.execute(sql, args).fetchall()]
        conn.close()
        return rows

    def _render_token_prompt(self) -> str:
        return """<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>تسجيل الدخول - أرشيف واتساب المحلي</title>
  <style>
    body { font-family: Tahoma, Arial, sans-serif; background: #f4f6f8; display: flex; justify-content: center; align-items: center; min-height: 100vh; margin: 0; }
    .box { background: #fff; padding: 24px; border-radius: 10px; box-shadow: 0 2px 10px rgba(0,0,0,0.08); width: 320px; }
    h2 { margin-top: 0; color: #128c7e; font-size: 18px; }
    input { width: 100%; box-sizing: border-box; padding: 10px; border: 1px solid #ccc; border-radius: 6px; margin-bottom: 12px; }
    button { width: 100%; padding: 10px; border: none; border-radius: 6px; background: #128c7e; color: #fff; cursor: pointer; font-size: 15px; }
  </style>
</head>
<body>
  <form class="box" method="get" action="/">
    <h2>الأرشيف محمي بمفتاح أمان</h2>
    <input type="password" name="token" placeholder="أدخل مفتاح الأمان (X-API-Key)" required />
    <button type="submit">دخول</button>
  </form>
</body>
</html>"""

    def _render_dashboard(self, rows, q: str, contact: str, token_param: str) -> str:
        token_qs = f"?token={quote(token_param)}" if token_param else ""
        token_hidden = (
            f'<input type="hidden" name="token" value="{html.escape(token_param)}" />'
            if token_param else ""
        )
        items_html = []
        for r in rows:
            app_label = "واتساب للأعمال" if r["app"] == "com.whatsapp.w4b" else "واتساب"
            items_html.append(
                f"<tr>"
                f"<td>{html.escape(format_ts(r['timestamp_ms']))}</td>"
                f"<td><strong>{html.escape(r['contact'])}</strong></td>"
                f"<td dir=\"ltr\">{html.escape(r.get('phone') or '')}</td>"
                f"<td>{html.escape(r['message'])}</td>"
                f"<td>{app_label}</td>"
                f"<td>{html.escape(r['device'] or '')}</td>"
                f"</tr>"
            )
        tbody = "".join(items_html) or '<tr><td colspan="6" style="text-align:center;">لا توجد رسائل محفوظة بعد.</td></tr>'
        return f"""<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>أرشيف رسائل واتساب - الشبكة المحلية</title>
  <style>
    body {{ font-family: Tahoma, Arial, sans-serif; background: #f4f6f8; margin: 0; padding: 20px; color: #222; }}
    .card {{ max-width: 1150px; margin: 0 auto; background: #fff; border-radius: 10px; padding: 20px; box-shadow: 0 2px 10px rgba(0,0,0,0.08); }}
    h1 {{ margin-top: 0; font-size: 22px; color: #128c7e; }}
    form {{ display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 16px; }}
    input {{ padding: 8px 12px; border: 1px solid #ccc; border-radius: 6px; flex: 1; min-width: 180px; }}
    button, a.btn {{ padding: 8px 14px; border: none; border-radius: 6px; background: #128c7e; color: #fff; text-decoration: none; cursor: pointer; font-size: 14px; }}
    a.btn.secondary {{ background: #455a64; }}
    table {{ width: 100%; border-collapse: collapse; font-size: 14px; }}
    th, td {{ border-bottom: 1px solid #e5e7eb; padding: 10px; text-align: right; vertical-align: top; }}
    th {{ background: #f0f9f7; color: #075e54; }}
    tr:hover {{ background: #fafafa; }}
  </style>
</head>
<body>
  <div class="card">
    <h1>أرشيف رسائل واتساب المحلي ({len(rows)} رسالة)</h1>
    <form method="get" action="/">
      {token_hidden}
      <input type="text" name="q" value="{html.escape(q)}" placeholder="بحث في نص الرسالة أو الاسم أو الرقم..." />
      <input type="text" name="contact" value="{html.escape(contact)}" placeholder="تصفية بالاسم أو الرقم..." />
      <button type="submit">بحث</button>
      <a class="btn secondary" href="/{token_qs}">تحديث</a>
      <a class="btn secondary" href="/export.csv{token_qs}">تصدير CSV</a>
    </form>
    <table>
      <thead>
        <tr>
          <th>الوقت</th>
          <th>جهة الاتصال</th>
          <th>الرقم</th>
          <th>الرسالة</th>
          <th>التطبيق</th>
          <th>الجهاز</th>
        </tr>
      </thead>
      <tbody>{tbody}</tbody>
    </table>
  </div>
</body>
</html>"""


def main() -> None:
    global DB_PATH, JSONL_PATH, API_TOKEN
    parser = argparse.ArgumentParser(description="خادم أرشفة محلي دائم لرسائل WhatsAppAutoDelete")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=int(os.environ.get("PORT", "8765")))
    parser.add_argument("--token", default=os.environ.get("API_KEY", ""))
    parser.add_argument("--db", default="messages.db")
    args = parser.parse_args()

    DB_PATH = args.db
    JSONL_PATH = os.path.splitext(args.db)[0] + ".jsonl"
    API_TOKEN = args.token
    init_db(DB_PATH)

    lan_ip = get_lan_ip()
    server = ThreadingHTTPServer((args.host, args.port), ArchiveHandler)
    print("============================================================")
    print("خادم الأرشفة المحلي يعمل الآن!")
    print(f"لوحة العرض بالمتصفح : http://{lan_ip}:{args.port}/")
    print(f"عنوان الربط بالتطبيق : http://{lan_ip}:{args.port}/api/messages")
    print(f"قاعدة البيانات       : {os.path.abspath(DB_PATH)}")
    if API_TOKEN:
        print("الحماية              : مفعّلة (X-API-Key)")
    print("============================================================")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nتم إيقاف الخادم.")


if __name__ == "__main__":
    main()
