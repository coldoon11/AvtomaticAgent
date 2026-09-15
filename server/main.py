from __future__ import annotations

import html
import hmac
import os
import re
import sqlite3
import threading
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from fastapi import FastAPI, Header, HTTPException, Request, Response
from openai import OpenAI
from pydantic import BaseModel, Field
from twilio.request_validator import RequestValidator
from twilio.rest import Client as TwilioClient

E164_RE = re.compile(r"^\+[1-9]\d{6,14}$")


@dataclass(frozen=True)
class Settings:
    openai_api_key: str = os.getenv("OPENAI_API_KEY", "").strip()
    openai_project_id: str = os.getenv("OPENAI_PROJECT_ID", "").strip()
    openai_webhook_secret: str = os.getenv("OPENAI_WEBHOOK_SECRET", "").strip()
    openai_live_model: str = os.getenv("OPENAI_LIVE_MODEL", "gpt-live-1").strip()
    openai_live_voice: str = os.getenv("OPENAI_LIVE_VOICE", "marin").strip()
    openai_sip_host: str = os.getenv("OPENAI_SIP_HOST", "sip.api.openai.com").strip()

    twilio_account_sid: str = os.getenv("TWILIO_ACCOUNT_SID", "").strip()
    twilio_auth_token: str = os.getenv("TWILIO_AUTH_TOKEN", "").strip()
    twilio_from_number: str = os.getenv("TWILIO_FROM_NUMBER", "").strip()
    twilio_validate_webhooks: bool = os.getenv("TWILIO_VALIDATE_WEBHOOKS", "true").lower() in {"1", "true", "yes", "on"}

    public_base_url: str = os.getenv("PUBLIC_BASE_URL", "").strip().rstrip("/")
    agent_api_token: str = os.getenv("AGENT_API_TOKEN", "").strip()
    owner_name: str = os.getenv("AI_OWNER_NAME", "владельца телефона").strip()
    inbound_calls_enabled: bool = os.getenv("AI_INBOUND_CALLS_ENABLED", "false").lower() in {"1", "true", "yes", "on"}
    recording_enabled: bool = os.getenv("AI_CALL_RECORDING_ENABLED", "false").lower() in {"1", "true", "yes", "on"}
    max_call_seconds: int = int(os.getenv("AI_CALL_MAX_SECONDS", "600"))
    db_path: Path = Path(os.getenv("AI_CALL_DB", "phone_jobs.sqlite3"))


SETTINGS = Settings()
app = FastAPI(title="AvtomaticAgent Voice Gateway", version="0.5.0")


class PhoneCallRequest(BaseModel):
    number: str = Field(min_length=7, max_length=32)
    task: str = Field(default="Поздоровайся и спроси, удобно ли сейчас разговаривать.", min_length=1, max_length=4000)
    record: bool = False


class PhoneCallResponse(BaseModel):
    job_id: str
    twilio_call_sid: str
    status: str
    recording: bool


class JobStore:
    def __init__(self, path: Path):
        self.path = path
        self._lock = threading.RLock()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as db:
            db.execute(
                """
                CREATE TABLE IF NOT EXISTS phone_jobs (
                    job_id TEXT PRIMARY KEY,
                    direction TEXT NOT NULL,
                    remote_number TEXT NOT NULL,
                    task TEXT NOT NULL,
                    record_enabled INTEGER NOT NULL DEFAULT 0,
                    twilio_call_sid TEXT,
                    live_session_id TEXT,
                    status TEXT NOT NULL,
                    created_at REAL NOT NULL,
                    updated_at REAL NOT NULL
                )
                """
            )

    def _connect(self) -> sqlite3.Connection:
        db = sqlite3.connect(self.path)
        db.row_factory = sqlite3.Row
        return db

    def create(self, *, direction: str, remote_number: str, task: str, record_enabled: bool) -> dict[str, Any]:
        now = time.time()
        job_id = uuid.uuid4().hex
        with self._lock, self._connect() as db:
            db.execute(
                """
                INSERT INTO phone_jobs (
                    job_id, direction, remote_number, task, record_enabled,
                    twilio_call_sid, live_session_id, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, NULL, NULL, ?, ?, ?)
                """,
                (job_id, direction, remote_number, task, int(record_enabled), "created", now, now),
            )
        return self.get(job_id) or {}

    def get(self, job_id: str) -> dict[str, Any] | None:
        with self._lock, self._connect() as db:
            row = db.execute("SELECT * FROM phone_jobs WHERE job_id = ?", (job_id,)).fetchone()
        return dict(row) if row else None

    def update(self, job_id: str, **values: Any) -> dict[str, Any] | None:
        allowed = {"twilio_call_sid", "live_session_id", "status"}
        clean = {k: v for k, v in values.items() if k in allowed}
        if not clean:
            return self.get(job_id)
        clean["updated_at"] = time.time()
        assignments = ", ".join(f"{key} = ?" for key in clean)
        params = list(clean.values()) + [job_id]
        with self._lock, self._connect() as db:
            db.execute(f"UPDATE phone_jobs SET {assignments} WHERE job_id = ?", params)
        return self.get(job_id)


STORE = JobStore(SETTINGS.db_path)


def openai_client() -> OpenAI:
    if not SETTINGS.openai_api_key or not SETTINGS.openai_project_id:
        raise HTTPException(status_code=503, detail="OpenAI API не настроен на сервере")
    return OpenAI(
        api_key=SETTINGS.openai_api_key,
        project=SETTINGS.openai_project_id,
        webhook_secret=SETTINGS.openai_webhook_secret or None,
    )


def twilio_client() -> TwilioClient:
    if not SETTINGS.twilio_account_sid or not SETTINGS.twilio_auth_token or not SETTINGS.twilio_from_number:
        raise HTTPException(status_code=503, detail="Twilio не настроен на сервере")
    return TwilioClient(SETTINGS.twilio_account_sid, SETTINGS.twilio_auth_token)


def require_agent_token(authorization: str | None = Header(default=None)) -> None:
    expected = SETTINGS.agent_api_token
    if not expected:
        return
    provided = ""
    if authorization and authorization.lower().startswith("bearer "):
        provided = authorization[7:].strip()
    if not hmac.compare_digest(provided, expected):
        raise HTTPException(status_code=401, detail="Неверный токен приложения")


def normalize_number(raw: str) -> str:
    value = raw.strip().replace(" ", "").replace("-", "").replace("(", "").replace(")", "")
    if not E164_RE.fullmatch(value):
        raise HTTPException(
            status_code=400,
            detail="Номер должен быть в международном формате E.164, например +37060000000",
        )
    return value


def sip_uri(job_id: str) -> str:
    project = SETTINGS.openai_project_id
    if not project.startswith("proj_"):
        raise HTTPException(status_code=503, detail="OPENAI_PROJECT_ID должен начинаться с proj_")
    return f"sip:{project}@{SETTINGS.openai_sip_host};transport=tls?X-Agent-Job={job_id}"


def dial_twiml(job_id: str) -> str:
    target = html.escape(sip_uri(job_id), quote=True)
    limit = max(30, min(7200, SETTINGS.max_call_seconds))
    return (
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        "<Response>"
        f"<Dial answerOnBridge=\"true\" timeLimit=\"{limit}\">"
        f"<Sip>{target}</Sip>"
        "</Dial>"
        "</Response>"
    )


def reject_twiml() -> str:
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response><Reject reason=\"busy\"/></Response>"


def build_instructions(job: dict[str, Any]) -> str:
    owner = SETTINGS.owner_name or "владельца телефона"
    direction = job["direction"]
    task = job["task"]
    recording = bool(job["record_enabled"])

    if direction == "outbound":
        purpose = f"Ты совершаешь исходящий звонок по поручению {owner}. Цель звонка: {task}"
    else:
        purpose = (
            f"Ты отвечаешь на входящий звонок как ИИ-помощник {owner}. "
            "Узнай имя звонящего, причину звонка и спокойно собери сообщение для владельца."
        )

    recording_rule = (
        "В первой реплике также сообщи, что разговор может быть записан и сохранён. "
        "Если собеседник возражает против записи, не проси и не обсуждай чувствительные данные и предложи завершить разговор."
        if recording
        else "Запись разговора со стороны этого сервиса выключена."
    )

    return f"""
Ты голосовой ИИ-помощник. Разговаривай естественно, кратко и по-русски, если собеседник не перешёл на другой язык.
{purpose}

Обязательные правила:
1. В самом начале честно скажи, что ты ИИ-помощник {owner}; не выдавай себя за человека и не имитируй голос владельца.
2. {recording_rule}
3. Не раскрывай пароли, токены, банковские данные, адреса, медицинские или другие чувствительные сведения владельца.
4. Не подтверждай покупки, переводы денег, кредиты, договоры или иные юридически/финансово значимые обязательства без отдельного подтверждения владельца.
5. Если тебе не хватает полномочий или информации, скажи, что передашь вопрос владельцу.
6. Не придумывай факты о владельце. Если не знаешь — прямо скажи, что не знаешь.
7. Когда цель разговора достигнута, кратко подведи итог собеседнику и вежливо попрощайся.
""".strip()


async def twilio_form(request: Request) -> dict[str, str]:
    form = await request.form()
    values = {str(k): str(v) for k, v in form.items()}
    if SETTINGS.twilio_validate_webhooks:
        if not SETTINGS.twilio_auth_token:
            raise HTTPException(status_code=503, detail="TWILIO_AUTH_TOKEN не настроен")
        signature = request.headers.get("X-Twilio-Signature", "")
        base_url = SETTINGS.public_base_url
        signed_url = f"{base_url}{request.url.path}" if base_url else str(request.url)
        if request.url.query:
            signed_url += f"?{request.url.query}"
        validator = RequestValidator(SETTINGS.twilio_auth_token)
        if not validator.validate(signed_url, values, signature):
            raise HTTPException(status_code=403, detail="Неверная подпись Twilio")
    return values


@app.get("/health")
def health() -> dict[str, Any]:
    return {
        "status": "ok",
        "version": "0.5.0",
        "openai_configured": bool(SETTINGS.openai_api_key and SETTINGS.openai_project_id),
        "twilio_configured": bool(
            SETTINGS.twilio_account_sid and SETTINGS.twilio_auth_token and SETTINGS.twilio_from_number
        ),
        "inbound_calls_enabled": SETTINGS.inbound_calls_enabled,
        "recording_allowed": SETTINGS.recording_enabled,
    }


@app.post("/phone/call", response_model=PhoneCallResponse)
def start_phone_call(payload: PhoneCallRequest, authorization: str | None = Header(default=None)) -> PhoneCallResponse:
    require_agent_token(authorization)
    number = normalize_number(payload.number)
    record = bool(payload.record and SETTINGS.recording_enabled)
    job = STORE.create(direction="outbound", remote_number=number, task=payload.task.strip(), record_enabled=record)

    callback = f"{SETTINGS.public_base_url}/twilio/status?job_id={job['job_id']}" if SETTINGS.public_base_url else None
    kwargs: dict[str, Any] = {
        "to": number,
        "from_": SETTINGS.twilio_from_number,
        "twiml": dial_twiml(job["job_id"]),
    }
    if callback:
        kwargs.update(
            status_callback=callback,
            status_callback_event=["initiated", "ringing", "answered", "completed"],
            status_callback_method="POST",
        )

    try:
        call = twilio_client().calls.create(**kwargs)
    except Exception as exc:
        STORE.update(job["job_id"], status="failed_to_start")
        raise HTTPException(status_code=502, detail=f"Twilio не смог начать звонок: {exc}") from exc

    STORE.update(job["job_id"], twilio_call_sid=call.sid, status=getattr(call, "status", None) or "queued")
    return PhoneCallResponse(
        job_id=job["job_id"],
        twilio_call_sid=call.sid,
        status=getattr(call, "status", None) or "queued",
        recording=record,
    )


@app.get("/phone/jobs/{job_id}")
def phone_job(job_id: str, authorization: str | None = Header(default=None)) -> dict[str, Any]:
    require_agent_token(authorization)
    job = STORE.get(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Звонок не найден")
    return {
        "job_id": job["job_id"],
        "direction": job["direction"],
        "remote_number": job["remote_number"],
        "task": job["task"],
        "recording": bool(job["record_enabled"]),
        "twilio_call_sid": job["twilio_call_sid"],
        "live_session_id": job["live_session_id"],
        "status": job["status"],
        "created_at": job["created_at"],
        "updated_at": job["updated_at"],
    }


@app.post("/phone/jobs/{job_id}/hangup")
def hangup_phone_job(job_id: str, authorization: str | None = Header(default=None)) -> dict[str, str]:
    require_agent_token(authorization)
    job = STORE.get(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Звонок не найден")

    errors: list[str] = []
    if job.get("live_session_id"):
        try:
            openai_client().live.sessions.hangup(job["live_session_id"])
        except Exception as exc:
            errors.append(f"OpenAI: {exc}")
    if job.get("twilio_call_sid"):
        try:
            twilio_client().calls(job["twilio_call_sid"]).update(status="completed")
        except Exception as exc:
            errors.append(f"Twilio: {exc}")

    STORE.update(job_id, status="completed")
    return {"status": "completed" if not errors else "completed_with_errors", "detail": "; ".join(errors)}


@app.get("/phone/jobs/{job_id}/recording")
def download_recording(job_id: str, authorization: str | None = Header(default=None)) -> Response:
    require_agent_token(authorization)
    job = STORE.get(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Звонок не найден")
    if not bool(job["record_enabled"]):
        raise HTTPException(status_code=409, detail="Запись для этого звонка не была включена")
    session_id = job.get("live_session_id")
    if not session_id:
        raise HTTPException(status_code=409, detail="Live-сессия ещё не создана")
    try:
        data = openai_client().live.sessions.download_recording(session_id).read()
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Не удалось получить запись: {exc}") from exc
    return Response(content=data, media_type="application/octet-stream")


@app.post("/twilio/status")
async def twilio_status(request: Request) -> dict[str, bool]:
    values = await twilio_form(request)
    job_id = request.query_params.get("job_id", "")
    if job_id:
        status = values.get("CallStatus") or "unknown"
        sid = values.get("CallSid")
        STORE.update(job_id, twilio_call_sid=sid, status=status)
    return {"ok": True}


@app.post("/twilio/incoming")
async def twilio_incoming(request: Request) -> Response:
    values = await twilio_form(request)
    if not SETTINGS.inbound_calls_enabled:
        return Response(content=reject_twiml(), media_type="application/xml")

    remote = values.get("From", "unknown")
    record = SETTINGS.recording_enabled
    job = STORE.create(
        direction="inbound",
        remote_number=remote,
        task="Прими звонок, выясни имя, причину и что нужно передать владельцу.",
        record_enabled=record,
    )
    sid = values.get("CallSid")
    if sid:
        STORE.update(job["job_id"], twilio_call_sid=sid, status="ringing")
    return Response(content=dial_twiml(job["job_id"]), media_type="application/xml")


@app.post("/openai/webhook")
async def openai_webhook(request: Request) -> dict[str, bool]:
    if not SETTINGS.openai_webhook_secret:
        raise HTTPException(status_code=503, detail="OPENAI_WEBHOOK_SECRET не настроен")

    raw_body = (await request.body()).decode("utf-8")
    client = openai_client()
    try:
        event = client.webhooks.unwrap(raw_body, request.headers)
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"Неверный webhook OpenAI: {exc}") from exc

    if event.type not in {"live.transport.incoming", "live.call.incoming"}:
        return {"ok": True}

    session_id = event.data.session_id
    headers = {item.name.lower(): item.value for item in event.data.sip_headers}
    job_id = headers.get("x-agent-job", "").strip()
    job = STORE.get(job_id) if job_id else None

    if not job:
        try:
            client.live.sessions.reject(session_id, status_code=403)
        finally:
            return {"ok": True}

    if job.get("live_session_id") == session_id:
        return {"ok": True}

    session = {
        "model": SETTINGS.openai_live_model,
        "type": "live",
        "audio": {"output": {"voice": SETTINGS.openai_live_voice}},
        "instructions": build_instructions(job),
        "store": bool(job["record_enabled"]),
    }

    try:
        client.live.sessions.accept(session_id, session=session)
    except Exception as exc:
        STORE.update(job_id, status="openai_accept_failed")
        raise HTTPException(status_code=502, detail=f"OpenAI не принял SIP-звонок: {exc}") from exc

    STORE.update(job_id, live_session_id=session_id, status="ai_connected")
    return {"ok": True}
