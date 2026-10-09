import logging

from fastapi import APIRouter

from agent_router import AgentRouter

from router_models import (
    RouterRequest,
    RouterResponse,
)


logger = logging.getLogger(
    __name__
)


def create_router_api(
    agent_router: AgentRouter,
) -> APIRouter:

    router = APIRouter(
        prefix="/router",
        tags=["Agent Router"],
    )


    @router.post(
        "/select",
        response_model=RouterResponse,
    )
    async def select_agent(
        request: RouterRequest,
    ) -> RouterResponse:

        logger.info(
            "Agent routing request received. "
            "contextId=%s previousAgentId=%s "
            "availableAgents=%d",
            request.contextId,
            request.previousAgentId,
            len(request.agents),
        )


        result = await agent_router.route(
            request
        )


        if result.agentId:

            logger.info(
                "Selected agent=%s "
                "confidence=%.2f "
                "method=%s reason=%s",
                result.agentId,
                result.confidence,
                result.method,
                result.reason,
            )

        else:

            logger.warning(
                "No suitable agent selected. "
                "confidence=%.2f "
                "reason=%s",
                result.confidence,
                result.reason,
            )


        return result


    @router.get(
        "/health"
    )
    async def router_health():

        return {
            "status": "UP",
            "router": "AgentRouter",
            "llmRouterConfigured":
                agent_router.has_llm_router(),
        }


    return router