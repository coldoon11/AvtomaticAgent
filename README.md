# AvtomaticAgent

Android AI assistant with voice mode, hand-gesture control, app launching, notification replies, ordinary calls and **AI phone calls over SIP**.

## v0.5 AI phone calls

The Android app can send a command such as:

`ИИ позвони Мама: узнай, сможет ли она созвониться вечером`

The phone resolves the contact locally and asks the backend to place the call. The backend uses a Twilio voice number, bridges the call over SIP to OpenAI Live (`gpt-live-1`), and the agent clearly identifies itself as an AI assistant acting on the owner's behalf.

The required server is in [`server/`](server/README.md). OpenAI and Twilio secrets belong on the server, not in the APK.

Recording is disabled by default. Enable it only when you have an appropriate consent/legal policy for the participants and jurisdictions involved.
