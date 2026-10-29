# A2A Server for North

Wraps a North platform agent as an [A2A](https://a2a-protocol.org) server. `NorthAgentExecutor` streams the agent's `chat_stream()` responses as A2A task artifacts; any tools configured on the North agent (e.g. MCP-based tools) run server-side, so the executor just relays the resulting text.

## Prerequisites

- [uv](https://docs.astral.sh/uv/)
- A North instance URL and an `auth_token` for it
- A North agent ID (create one via the North UI or API, e.g. with an MCP tool attached)

## Setup

Install dependencies -

```bash
uv sync
```

Create `.env` -

```bash
NORTH_AUTH_TOKEN=<your-north-auth-token>
NORTH_AGENT_ID=<your-north-agent-id>
```

> [!NOTE]
> `main.py` hardcodes the North base URL (`NORTH_BASE_URL`) - update it if pointing at a different instance.

Run the server -

```bash
uv run uvicorn main:app --host 127.0.0.1 --port 9999
```

The agent card is served at `http://127.0.0.1:9999/.well-known/agent-card.json`.
