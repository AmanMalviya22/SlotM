# SlotMind

SlotMind is a Spring Boot + React front-desk application for AI-assisted appointment rescheduling. It creates human-reviewable recommendations when a provider goes offline or an appointment slot is cancelled.

## Requirements

- Java 21
- Maven 3.9+
- Node.js 20.19+ and npm

The backend and frontend run as separate processes. A Maven Wrapper is not currently included.

## Run locally (Windows PowerShell)

From the repository root, open two terminals.

**Terminal 1 — backend**

```powershell
cd backend
# Optional: select the AI strategy. Default is rule-based.
$env:SLOTMIND_SCHEDULING_STRATEGY = "ai"
mvn spring-boot:run
```

**Terminal 2 — frontend**

```powershell
cd frontend
npm ci
npm run dev
```

Open `http://localhost:5173`. The frontend reads `VITE_API_BASE_URL` from `frontend/.env`; the default backend URL is `http://localhost:8080`.

The backend uses an in-memory H2 database and seeds demo records at startup. Set provider `PRV-001` to `OFFLINE`; the workflow should create suggestions for both affected appointments. Accepting a suggestion books the replacement slot.

## Architecture

- **REST API:** endpoints for providers, appointments, slots, and rescheduling suggestions.
- **Service and repository layers:** business operations are separated from HTTP request handling and persistence.
- **Runtime strategy selection:** `SLOTMIND_SCHEDULING_STRATEGY=rule-based` (default) selects deterministic rules; `SLOTMIND_SCHEDULING_STRATEGY=ai` selects the AI strategy.
- **AI safeguards:** AI output is validated against eligible candidates and current business rules. AI mode falls back to deterministic rules when credentials are absent, the gateway fails, or output is invalid.
- **Workflow events:** provider and slot changes dispatch post-commit events to an asynchronous workflow listener.
- **Concurrency protection:** appointment and slot updates use database locks; a slot is rechecked before a pending suggestion is saved and before a suggestion is accepted.
- **Front-desk UI:** shows providers, appointments, slot availability, recommendation source and trigger, original/proposed times, AI confidence when available, specialty-change warnings, reasoning, accept/reject controls, an explicit patient-confirmation checkbox required by both UI and API, a live SSE suggestion flow, date-filtered full-day schedule, appointment-type colors, provider-load indicators, and a browser-local waitlist that books through the real appointment API.

For a source-code map, class responsibilities, and end-to-end implementation flows, see [CODE_DOCUMENTATION.md](CODE_DOCUMENTATION.md).

## API

- `GET /api/providers`
- `GET /api/appointments`
- `GET /api/slots`
- `GET /api/suggestions`
- `POST /api/appointments`
- `PATCH /api/providers/{id}/status`
- `PATCH /api/slots/{id}/status`
- `POST /api/appointments/{id}/suggest`
- `POST /api/appointments/{id}/suggest/stream` (SSE progress + validated suggestion)
- `PATCH /api/suggestions/{id}`

Optional list filters: `GET /api/appointments?status=CONFIRMED` and `GET /api/suggestions?status=PENDING`.

## Optional LLM gateway

To use AI scheduling, select `SLOTMIND_SCHEDULING_STRATEGY=ai` and configure a gateway token in the same PowerShell session used to start the backend:

```powershell
$env:SLOTMIND_SCHEDULING_STRATEGY = "ai"
$env:SLOTMIND_LLM_API_KEY = "YOUR_ROTATED_TOKEN"
$env:SLOTMIND_LLM_PRODUCT = "PC1"
$env:SLOTMIND_LLM_BASE_URL = "https://litellm-qc.zycus.net/v1"
$env:SLOTMIND_LLM_MODEL = "qwen-cursor"
# Only if the gateway owner confirms it is required:
# $env:SLOTMIND_LLM_COOKIE = "ROTATED_COOKIE_VALUE"

cd backend
mvn spring-boot:run
```

Never place credentials in Java source, application properties, frontend variables, Git, or logs. Any credential previously shared in chat or committed to Git must be rotated. A real gateway request has not been verified unless a valid rotated credential is configured and the gateway can be reached.

## Hackathon demo

Follow [`DEMO.md`](DEMO.md) for the five-minute rubric-aligned walkthrough, including the human-confirmation checkpoint, atomic acceptance verification, SSE bonus, UI ceiling features, and troubleshooting.

## Verification

Run backend tests:

```powershell
cd backend
mvn test
```

Build the frontend:

```powershell
cd frontend
npm ci
npm test
npm run build
```

GitHub Actions runs backend tests, frontend tests, and a production frontend build on pushes to `main` or a `quality/**` branch and on pull requests to `main`.

## Known limitations

- The H2 database is in-memory and resets when the backend restarts.
- Gateway tests use a fake gateway; no live LLM request is made during automated tests.
- Workflow events are handled asynchronously in-process, not through a durable message broker; a process crash can lose an in-flight event.
- Production use still requires persistent storage, authentication and authorization, monitoring, and operational retry/reconciliation.


## Front-desk UI extensions

- Use **Full-day schedule** to inspect slots and linked appointments by date. Previous/next controls and the date picker change the displayed day.
- **Provider load** summarizes booked slots and active appointments for the chosen day.
- Appointment types use separate visual markers for routine, urgent, and follow-up visits.
- **Waitlist** entries are stored in the current browser's local storage, not the backend database. "Book next open slot" creates a real appointment through `POST /api/appointments`; the waitlist entry is removed only after that booking succeeds.
- Manual **Suggest** actions use the SSE endpoint to stream progress and the validated recommendation. The final recommendation remains pending until a staff member confirms they have checked the proposed time with the patient and accepts it; the API rejects acceptance without that confirmation flag.
- If an automatic workflow cannot find an eligible slot, the appointment remains visible in the recovery queue as **No replacement yet** so staff can add/reopen slots and retry rather than losing the exception in the UI.
