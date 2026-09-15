# AvtomaticAgent / Astra AI

Android AI assistant with background wake phrase, voice mode, hand-gesture control, app launching, notification replies, ordinary calls and **AI phone calls over SIP**.

## v0.6 — background Astra

The new background mode starts a user-visible Android `microphone` foreground service. After you enable it while the app is open, it keeps listening when the app is minimized.

Default wake name: **Астра**. It can be changed under **Настройки → Ключевое имя**.

Examples:

- `Астра` → beep → `открой Telegram`
- `Астра, что сейчас на экране?`
- `Астра, ИИ позвони Мама: узнай, сможет ли она созвониться вечером`

After one request is completed and the spoken answer finishes, the assistant returns to wake-word-only waiting mode. The ongoing Android notification always shows when the background microphone is active and contains a **Выключить** action.

Wake-phrase recognition is local on the phone using **Vosk Android 0.3.75** and the Apache-2.0 `vosk-model-small-ru-0.22` model. The model is bundled into the APK by GitHub Actions. It is about 45 MB and typically needs roughly 300 MB RAM while loaded. The user's speech is only sent to the configured backend after the wake phrase has activated a command turn.

Android 14+ does not allow an app to silently start a microphone foreground service from the background. The user must start Astra from the visible app; once started it can continue while the app is minimized. Some vendor battery-saving modes may still stop long-running services, in which case set Astra AI battery usage to unrestricted.

## AI phone calls

The Android app can send a command such as:

`ИИ позвони Мама: узнай, сможет ли она созвониться вечером`

The phone resolves the contact locally and asks the backend to place the call. The backend uses a Twilio voice number, bridges the call over SIP to OpenAI Live (`gpt-live-1`), and the agent clearly identifies itself as an AI assistant acting on the owner's behalf.

The required server is in [`server/`](server/README.md). OpenAI and Twilio secrets belong on the server, not in the APK.

Recording is disabled by default. Enable it only when you have an appropriate consent/legal policy for the participants and jurisdictions involved.
