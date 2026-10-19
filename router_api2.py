from fastapi import APIRouter
from router_models import RouterRequest, RouterResponse
from agent_router import AgentRouter

def create_router_api(agent_router: AgentRouter) -> APIRouter:
    router = APIRouter(prefix='/router', tags=['Agent Router'])
    @router.post('/select/v2', response_model=RouterResponse)
    async def select_agent(request: RouterRequest) -> RouterResponse:
        return await agent_router.route(request)
    @router.get('/health')
    async def health():
        return {'status':'UP', 'router':'AgentRouter', 'llmRouterConfigured':False}
    return router
