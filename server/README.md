# Voice/SIP backend — v0.5

This backend lets the Android app place an AI phone call through **Twilio Programmable Voice -> SIP -> OpenAI Live (`gpt-live-1`)**. The OpenAI and Twilio secrets stay on the server; never put them inside the APK.

## What works

- `POST /phone/call` starts an outbound PSTN call from your Twilio number and bridges the answered call to OpenAI Live over SIP.
- `POST /openai/webhook` verifies the OpenAI webhook and accepts the pending Live SIP session.
- The call instructions always tell the model to disclose that it is an AI assistant acting on the owner's behalf.
- `POST /twilio/incoming` can answer calls made to the Twilio number with the same AI agent when `AI_INBOUND_CALLS_ENABLED=true`.
- `GET /phone/jobs/{job_id}` returns current call state.
- `POST /phone/jobs/{job_id}/hangup` ends the AI/Twilio call.
- Optional Live recording can be enabled server-side. It is **off by default**.

## Setup

1. Create an OpenAI API project. Copy its `proj_...` project ID and create a project-scoped API key.
2. In the same OpenAI project, create a webhook pointing to `https://YOUR_DOMAIN/openai/webhook` and subscribe it to `live.transport.incoming`. Copy the `whsec_...` signing secret.
3. Create a Twilio account and buy a voice-capable number. Trial accounts can only call verified destinations.
4. Copy `.env.example` to `.env` on your server and fill in the OpenAI/Twilio values. Use a long random `AGENT_API_TOKEN`.
5. Deploy this folder to a public HTTPS server (Render/Railway/Fly.io/VPS are all fine) and run `uvicorn main:app --host 0.0.0.0 --port 8765 --proxy-headers`.
6. For inbound AI calls, set the Twilio phone number's Voice webhook to `POST https://YOUR_DOMAIN/twilio/incoming`, then set `AI_INBOUND_CALLS_ENABLED=true`.
7. In the Android app set **URL backend** to `https://YOUR_DOMAIN` and **Токен** to the same `AGENT_API_TOKEN`.

## Android commands

- `ИИ позвони +37060000000: скажи, что я задержусь на 20 минут`
- `ИИ позвони Мама: узнай, сможет ли она созвониться вечером`

The phone resolves contact names locally, then sends only the resolved number and the call task to your backend.

## Recording

`AI_CALL_RECORDING_ENABLED=false` by default. If you explicitly enable it and request recording from the Android app, the Live session is stored and `GET /phone/jobs/{job_id}/recording` can download the stored session content. Recording/consent rules differ by jurisdiction; configure this only when you have a lawful policy and tell participants that recording may occur.
