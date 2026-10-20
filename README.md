# Standalone Java Swing AI Chatbot

A separate Java 21 Swing application for the uploaded Python FastAPI VCCI AI Service. No VCCI Java libraries are required.

## Run

1. Start the Python FastAPI service (with the actual Python files named `main.py`, `router_models.py`, `agent_router.py`, `router_api.py`, `conversation_context.py`, `agent_executor.py`, etc.). Configure `NORTH_AUTH_TOKEN` and `NORTH_AGENT_ID` in your existing `.env`.
2. Confirm `http://127.0.0.1:9999/health` responds.
3. From this project folder run `mvn clean compile exec:java` (Java 21 and Maven required).
4. Press **Refresh Agents**, choose an agent or **Auto Select**, enter optional shared context and send a message.

## Integration contracts

- `GET /agents`: returns `{agents:[...]}`.
- `POST /router/select`: takes `message`, `agents`, `contextId`, `previousAgentId`, `context` and returns `agent` and `reason`.
- `POST /`: A2A JSON-RPC `message/send` with `message.metadata.agent_id` and `message.metadata.context`.
- The Java UI holds up to 12 recent conversation turns in request context; it does not persist them across restarts.

## Current Python limitations

- The uploaded Python `agent_router.py` is keyword/metadata based, not semantic LLM routing. It returns no match when agent metadata does not match the prompt.
- `/agents` currently discovers North agents only. External AI Core agents need a trusted registry and endpoint-specific adapters before this standalone app can invoke them.
- `agent_executor.py` uses in-memory conversation mappings. Restarting the Python service resets those mappings; Java resends recent history as context.
- A2A `message/send` support depends on the installed A2A SDK version. The code is designed for the JSON-RPC route registered by `main.py`; test with your actual Python environment.
- The existing Python service may have initialization/import errors independently of this Java project.
- For now, use only loopback/local access; do not expose the Python service publicly without authentication and authorization.
