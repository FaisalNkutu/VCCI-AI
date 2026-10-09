import logging
import os
from typing import Any

import uvicorn
from dotenv import load_dotenv
from fastapi import FastAPI
from fastapi.responses import JSONResponse

from north import AsyncNorthClient, NorthClient

from a2a.server.request_handlers import DefaultRequestHandler
from a2a.server.routes import (
    add_a2a_routes_to_fastapi,
    create_agent_card_routes,
    create_jsonrpc_routes,
)
from a2a.server.tasks import InMemoryTaskStore
from a2a.types import (
    AgentCapabilities,
    AgentCard,
    AgentInterface,
    AgentSkill,
)
from a2a.utils import DEFAULT_RPC_URL, TransportProtocol

from agent_executor import NorthAgentExecutor


# ============================================================
# ENVIRONMENT
# ============================================================

load_dotenv()

HOST = os.getenv("HOST", "127.0.0.1")
PORT = int(os.getenv("PORT", "9999"))

NORTH_BASE_URL = os.getenv(
    "NORTH_BASE_URL",
    "https://north.canadacentral.cloudapp.azure.com/api",
)

NORTH_AUTH_TOKEN = os.environ["NORTH_AUTH_TOKEN"]
NORTH_AGENT_ID = os.environ["NORTH_AGENT_ID"]


# ============================================================
# LOGGING
# ============================================================

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s - %(message)s",
)

logger = logging.getLogger("main")


# ============================================================
# NORTH CLIENTS
# ============================================================

#
# Async client:
# Used by NorthAgentExecutor when VCCI sends an AI request.
#
north_client = AsyncNorthClient(
    auth_token=NORTH_AUTH_TOKEN,
    base_url=NORTH_BASE_URL,
)

#
# Sync client:
# Used for startup information and agent discovery.
#
north_sync_client = NorthClient(
    auth_token=NORTH_AUTH_TOKEN,
    base_url=NORTH_BASE_URL,
)


# ============================================================
# LOAD DEFAULT NORTH AGENT
# ============================================================

logger.info(
    "Loading default North agent: %s",
    NORTH_AGENT_ID,
)

north_agent = north_sync_client.agents.get(
    NORTH_AGENT_ID
)

logger.info(
    "Default North agent loaded: %s",
    getattr(
        north_agent,
        "name",
        NORTH_AGENT_ID,
    ),
)


# ============================================================
# HELPER FUNCTIONS
# ============================================================

def get_value(
    obj: Any,
    *names: str,
    default: Any = "",
) -> Any:

    if obj is None:
        return default

    for name in names:

        if isinstance(obj, dict):
            value = obj.get(name)
        else:
            value = getattr(
                obj,
                name,
                None,
            )

        if value is not None:
            return value

    return default


def normalize_agent(
    agent: Any,
) -> dict[str, Any]:

    agent_id = str(
        get_value(
            agent,
            "id",
            "agent_id",
            default="",
        )
    ).strip()

    name = str(
        get_value(
            agent,
            "name",
            default=agent_id,
        )
    ).strip()

    description = str(
        get_value(
            agent,
            "description",
            default="",
        )
    ).strip()

    endpoint = str(
        get_value(
            agent,
            "endpoint",
            "url",
            default="",
        )
    ).strip()

    if not agent_id:
        agent_id = name

    if not name:
        name = agent_id

    return {
        "id": agent_id,
        "name": name,
        "description": description,
        "endpoint": endpoint,
        "streaming": True,
        "type": "north",
        "configured": True,
        "active": True,
    }


# ============================================================
# NORTH AGENT DISCOVERY
# ============================================================

def discover_agents() -> list[dict[str, Any]]:

    discovered: list[dict[str, Any]] = []

    logger.info(
        "Discovering available North agents..."
    )

    try:

        result = north_sync_client.agents.list()

        logger.info(
            "North agents.list() returned type: %s",
            type(result),
        )

        logger.info(
            "North agents.list() returned: %s",
            result,
        )

        # ----------------------------------------------------
        # North SDK currently appears to return a normal list
        # in your environment.
        #
        # But support several possible SDK return structures.
        # ----------------------------------------------------

        if result is None:

            raw_agents = []

        elif isinstance(
            result,
            (list, tuple),
        ):

            raw_agents = list(result)

        elif isinstance(result, dict):

            raw_agents = (
                result.get("agents")
                or result.get("items")
                or result.get("data")
                or []
            )

        else:

            raw_agents = (
                getattr(
                    result,
                    "agents",
                    None,
                )
                or getattr(
                    result,
                    "items",
                    None,
                )
                or getattr(
                    result,
                    "data",
                    None,
                )
                or []
            )

        for item in raw_agents:

            normalized = normalize_agent(
                item
            )

            if (
                normalized["id"]
                or normalized["name"]
            ):

                discovered.append(
                    normalized
                )

    except Exception as exc:

        logger.exception(
            "North agent discovery failed: %s",
            exc,
        )


    # ========================================================
    # IMPORTANT FALLBACK
    #
    # On your machine:
    #
    #     north_sync_client.agents.list()
    #
    # is returning:
    #
    #     []
    #
    # BUT:
    #
    #     north_sync_client.agents.get(NORTH_AGENT_ID)
    #
    # successfully returns Faisal's agent.
    #
    # Therefore NEVER return an empty registry when the
    # configured/default agent was successfully loaded.
    # ========================================================

    default_agent = normalize_agent(
        north_agent
    )

    if not default_agent["id"]:
        default_agent["id"] = NORTH_AGENT_ID

    if not default_agent["name"]:
        default_agent["name"] = (
            NORTH_AGENT_ID
        )

    already_present = any(
        str(
            item.get(
                "id",
                "",
            )
        ).strip()
        == str(
            default_agent["id"]
        ).strip()
        for item in discovered
    )

    if not already_present:

        logger.info(
            "Adding configured default North "
            "agent to registry: %s",
            default_agent["name"],
        )

        discovered.append(
            default_agent
        )


    # Remove duplicates

    unique_agents = {}

    for agent in discovered:

        key = (
            agent.get("id")
            or agent.get("name")
        )

        if key:
            unique_agents[
                str(key)
            ] = agent


    agents = list(
        unique_agents.values()
    )

    agents.sort(
        key=lambda item: str(
            item.get(
                "name",
                "",
            )
        ).lower()
    )

    logger.info(
        "Returning %d agent(s).",
        len(agents),
    )

    for agent in agents:

        logger.info(
            "Agent: %s | id=%s",
            agent.get("name"),
            agent.get("id"),
        )

    return agents


# ============================================================
# A2A SKILL
# ============================================================

skill = AgentSkill(
    id=NORTH_AGENT_ID,
    name=north_agent.name,
    description=north_agent.description,
    tags=[
        "north",
    ],
    input_modes=[
        "text/plain",
    ],
    output_modes=[
        "text/plain",
    ],
)


# ============================================================
# A2A AGENT CARD
#
# KEEP THIS IN THE SAME FORM AS YOUR ORIGINAL WORKING FILE.
# ============================================================

agent_card = AgentCard(
    name=north_agent.name,
    description=north_agent.description,
    version="0.1.0",

    default_input_modes=[
        "text/plain",
    ],

    default_output_modes=[
        "text/plain",
    ],

    capabilities=AgentCapabilities(
        streaming=True
    ),

    supported_interfaces=[
        AgentInterface(
            url=f"http://{HOST}:{PORT}",
            protocol_binding=(
                TransportProtocol.JSONRPC
            ),
            protocol_version="1.0",
        )
    ],

    skills=[
        skill
    ],
)


# ============================================================
# A2A REQUEST HANDLER
#
# THIS IS THE SAME CONSTRUCTION AS YOUR WORKING VERSION.
# ============================================================

request_handler = DefaultRequestHandler(
    agent_executor=NorthAgentExecutor(
        north_client,
        NORTH_AGENT_ID,
    ),
    task_store=InMemoryTaskStore(),
    agent_card=agent_card,
)


# ============================================================
# FASTAPI
# ============================================================

app = FastAPI(
    title="VCCI AI Service",
    version="1.0.0",
)


# ============================================================
# HEALTH ENDPOINT
# ============================================================

@app.get("/health")
async def health():

    return {
        "status": "ok",
        "service": "VCCI AI Service",
        "host": HOST,
        "port": PORT,
        "defaultAgentId": (
            NORTH_AGENT_ID
        ),
        "defaultAgentName": (
            north_agent.name
        ),
    }


# ============================================================
# AGENT REGISTRY
#
# Java AgentRegistryClient calls:
#
#     GET http://127.0.0.1:9999/agents
#
# ============================================================

@app.get("/agents")
async def get_agents():

    logger.info(
        "VCCI requested agent discovery."
    )

    agents = discover_agents()

    return {
        "agents": agents,
        "count": len(agents),
        "discovery": "north",
    }


# ============================================================
# SIMPLE AUTO ROUTER
# ============================================================

def choose_agent(
    message: str,
    agents: list[dict[str, Any]],
):

    if not agents:
        return None

    text = message.lower().strip()

    best_agent = None
    best_score = 0
    best_matches = []

    # --------------------------------------------------------
    # Intent synonyms.
    #
    # This bridges normal user language to agent names.
    #
    # Example:
    #   "Translate hello into French"
    #           ->
    #   translation-agent
    #
    # NOT hard-coding an agent ID here.
    # just expanding words so "translate" can match
    # "translation".
    # --------------------------------------------------------

    synonym_groups = [
        # Translation intent
        {
            "translate",
            "translation",
            "translator",
            "language",
            "french",
            "spanish",
            "german",
            "italian",
            "russian",
        },
    
        # Dice intent
        {
            "dice",
            "die",
            "roll",
            "random",
        },
    
        # Structured-text intent
        {
            "structured",
            "structuredtext",
            "structure",
            "structurize",
            "json",
            "fields",
            "schema",
            "format",
        },
    
        # Database intent
        {
            "db",
            "database",
            "sql",
            "query",
            "records",
            "table",
            "tables",
        },
    
        # Speech / transcription intent
        {
            "speech",
            "transcribe",
            "transcription",
            "audio",
            "voice",
        },
    
        # Social-media intent
        {
            "social",
            "media",
            "post",
            "posts",
            "linkedin",
        },
    ]

    expanded_terms = set(
        text
        .replace("-", " ")
        .replace("_", " ")
        .split()
    )

    # Expand request terms with related intent words.

    for group in synonym_groups:

        if expanded_terms.intersection(group):
            expanded_terms.update(group)

    # --------------------------------------------------------
    # Score each supplied agent.
    # --------------------------------------------------------

    for agent in agents:

        score = 0
        matches = []

        name = str(
            agent.get(
                "name",
                "",
            )
        ).lower()

        description = str(
            agent.get(
                "description",
                "",
            )
        ).lower()

        # ----------------------------------------------------
        # Build searchable agent text.
        # ----------------------------------------------------

        searchable_parts = [
            name,
            description,
        ]

        # Skills may be strings or dictionaries.

        skills = agent.get(
            "skills",
            [],
        )

        if isinstance(skills, list):

            for skill in skills:

                if isinstance(skill, dict):

                    searchable_parts.extend([
                        str(
                            skill.get(
                                "id",
                                "",
                            )
                        ),
                        str(
                            skill.get(
                                "name",
                                "",
                            )
                        ),
                        str(
                            skill.get(
                                "description",
                                "",
                            )
                        ),
                    ])

                    tags = skill.get(
                        "tags",
                        [],
                    )

                    if isinstance(tags, list):
                        searchable_parts.extend(
                            str(tag)
                            for tag in tags
                        )

                    examples = skill.get(
                        "examples",
                        [],
                    )

                    if isinstance(examples, list):
                        searchable_parts.extend(
                            str(example)
                            for example in examples
                        )

                else:

                    searchable_parts.append(
                        str(skill)
                    )

        searchable_text = " ".join(
            searchable_parts
        ).lower()

        searchable_words = set(
            searchable_text
            .replace("-", " ")
            .replace("_", " ")
            .replace(",", " ")
            .replace(".", " ")
            .replace(":", " ")
            .split()
        )

        # ----------------------------------------------------
        # Direct request-word matches.
        # ----------------------------------------------------

        for term in expanded_terms:

            if len(term) < 3:
                continue

            if term in searchable_words:

                score += 4
                matches.append(term)

                continue

            # ------------------------------------------------
            # Prefix matching handles:
            #
            # translate   <-> translation
            # translating <-> translator
            #
            # without requiring exact equality.
            # ------------------------------------------------

            for agent_word in searchable_words:

                if len(agent_word) < 4:
                    continue

                common_length = min(
                    len(term),
                    len(agent_word),
                    6,
                )

                if common_length >= 4:

                    if (
                        term[:common_length]
                        == agent_word[:common_length]
                    ):

                        score += 2
                        matches.append(
                            f"{term}~{agent_word}"
                        )
                        break

        # ----------------------------------------------------
        # Give agent-name matches extra weight.
        # ----------------------------------------------------

        name_words = (
            name
            .replace("-", " ")
            .replace("_", " ")
            .split()
        )

        for name_word in name_words:

            if (
                len(name_word) >= 3
                and name_word in expanded_terms
            ):

                score += 5

        logger.info(
            "Router candidate: %s | score=%s | matches=%s",
            agent.get(
                "name",
                agent.get(
                    "id",
                    "",
                ),
            ),
            score,
            matches,
        )

        if score > best_score:

            best_score = score
            best_agent = agent
            best_matches = matches

    # --------------------------------------------------------
    # Existing one-agent fallback.
    # --------------------------------------------------------

    if (
        best_agent is None
        and len(agents) == 1
    ):

        best_agent = agents[0]

        return {
            "agent": best_agent,
            "confidence": 0.50,
            "reason": (
                "Only one available agent "
                "is currently registered."
            ),
        }

    # --------------------------------------------------------
    # More than one agent and nothing matched.
    # Do NOT guess.
    # --------------------------------------------------------

    if best_agent is None:

        return None

    confidence = min(
        0.95,
        0.55 + best_score * 0.03,
    )

    return {
        "agent": best_agent,
        "confidence": confidence,
        "reason": (
            "Agent selected by matching request "
            "intent against agent name, description "
            "and skills. "
            f"Matches: {best_matches}"
        ),
    }

# ============================================================
# AUTO ROUTER ENDPOINT
# ============================================================

@app.post("/router/select")
async def router_select(
    payload: dict[str, Any],
):

    message = str(
        payload.get(
            "message",
            "",
        )
    ).strip()

    if not message:

        return JSONResponse(
            status_code=400,
            content={
                "agent": None,
                "confidence": 0.0,
                "reason": (
                    "Message cannot be empty."
                ),
            },
        )


    available_agents = payload.get(
        "agents",
        [],
    )

    if not isinstance(
        available_agents,
        list,
    ):

        available_agents = []


    # If Java did not send the list,
    # discover it directly.

    if not available_agents:

        available_agents = (
            discover_agents()
        )


    result = choose_agent(
        message,
        available_agents,
    )


    if result is None:

        return {
            "agent": None,
            "confidence": 0.0,
            "reason": (
                "No suitable agent "
                "could be selected."
            ),
        }


    logger.info(
        "Router selected agent: %s",
        result["agent"].get(
            "name"
        ),
    )

    return result


# ============================================================
# A2A ROUTES
#
# THIS IS THE EXACT REGISTRATION STYLE FROM YOUR
# ORIGINAL WORKING main.py.
# ============================================================

add_a2a_routes_to_fastapi(
    app,

    agent_card_routes=(
        create_agent_card_routes(
            agent_card
        )
    ),

    jsonrpc_routes=(
        create_jsonrpc_routes(
            request_handler,
            DEFAULT_RPC_URL,
        )
    ),
)


# ============================================================
# START
# ============================================================

if __name__ == "__main__":

    uvicorn.run(
        app,
        host=HOST,
        port=PORT,
    )