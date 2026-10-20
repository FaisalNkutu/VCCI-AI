# VCCI client-managed shared conversation context

No PostgreSQL or `DATABASE_URL` is required. Java VCCI owns conversation history, instructions, task context, and prior agent results. Python is stateless with respect to shared context. The existing North SDK may independently keep a North conversation ID in the executor memory; this does not replace Java-owned history.

## Installation
Copy Python files to `coyote-azure\src`, overwriting matching files. The files are named for your existing imports (not `*3.py`). Keep `NORTH_AUTH_TOKEN` and `NORTH_AGENT_ID` in `.env`. `VCCI_API_KEY` is not required. Keep Uvicorn bound to `127.0.0.1` because the API has no authentication.

`uv run uvicorn main:app --host 127.0.0.1 --port 9999 --log-level debug --env-file .env`

## Java client integration
Add `VcciConversationContext.java` to your VCCI project with Jackson databind. Keep one instance per chat session. Use `routingRequest(prompt, agents, previousAgentId)` as JSON body for `POST /router/select`. The response retains `agent`, `agentId`, `agentName`, `confidence`, `reason`, and `method`. After routing, check for null `agent`; do not fall back to Faisal's Agent. For North A2A requests, put the output of `a2aMetadata(selectedAgentId)` into the A2A **message.metadata** field (not the outer JSON-RPC envelope). For Java AI Core agents, pass `snapshot()` as context using that agent's existing request contract; each AI Core endpoint may need its own adapter. After an agent returns, call `addMessage("user", prompt, null)`, `addMessage("assistant", answer, agentId)`, and `addAgentResult(agentId, answer)` in Java. Avoid duplicating history entries across retries.

## Example routing request
```json
{"message":"Translate the previous answer into French","contextId":"chat-123","context":{"systemInstructions":"Be concise","userContext":"Engineering student","taskContext":"Translation","conversationHistory":[{"role":"assistant","content":"Hello world","agentId":"north-1"}],"agentResults":[]},"agents":[{"id":"translation-agent","name":"Translation Agent","description":"Translate between languages"}]}
```

## Caveats
* Router remains deterministic metadata matching, **not** LLM semantic routing. A follow-up without routing clues can remain unmatched.
* Client-supplied agent lists are trusted for **selection**, not authorization. Never use returned endpoints for arbitrary server-side HTTP requests.
* Existing A2A executor retains its legacy default North agent when A2A messages omit `agent_id`. Java should always supply explicit `agent_id`; a stricter removal of this fallback requires an A2A behavior change.
* Java-side integration points are reference code, not a tested patch of your Java Swing repository (not supplied).
* The North SDK, A2A SDK and live Java VCCI integration have not been tested in this environment.
