from fastapi import APIRouter, HTTPException
from router_models import RouterRequest, RouterResponse
from agent_router import AgentRouter

def create_router_api(agent_router: AgentRouter, allowed_agent_ids: set[str] | None = None) -> APIRouter:
    router = APIRouter(prefix='/router', tags=['Agent Router'])
    @router.post('/select/v2', response_model=RouterResponse)
    async def select_agent(request: RouterRequest) -> RouterResponse:
        if allowed_agent_ids is None:
            raise HTTPException(status_code=503, detail='Typed routing requires a trusted agent registry')
        request.agents = [agent for agent in request.agents if agent.id in allowed_agent_ids]
        return await agent_router.route(request)
    @router.get('/health')
    async def health():
        return {'status':'UP', 'router':'AgentRouter', 'llmRouterConfigured':False}
    return router
