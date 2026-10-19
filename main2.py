"""VCCI AI service. Requires NORTH_AUTH_TOKEN, NORTH_AGENT_ID, VCCI_API_KEY."""
import logging
import os
import secrets
from typing import Any
from dotenv import load_dotenv
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from north import AsyncNorthClient, NorthClient
from a2a.server.request_handlers import DefaultRequestHandler
from a2a.server.routes import add_a2a_routes_to_fastapi, create_agent_card_routes, create_jsonrpc_routes
from a2a.server.tasks import InMemoryTaskStore
from a2a.types import AgentCapabilities, AgentCard, AgentInterface, AgentSkill
from a2a.utils import DEFAULT_RPC_URL, TransportProtocol
from agent_executor import NorthAgentExecutor
from agent_router import AgentRouter
from router_api import create_router_api
from router_models import RouterRequest, RouterAgent

load_dotenv()
logging.basicConfig(level=os.getenv('LOG_LEVEL','INFO'))
logger = logging.getLogger('vcci')
HOST = os.getenv('HOST','127.0.0.1')
PORT = int(os.getenv('PORT','9999'))
NORTH_BASE_URL = os.getenv('NORTH_BASE_URL','https://north.canadacentral.cloudapp.azure.com/api')
NORTH_AUTH_TOKEN = os.environ['NORTH_AUTH_TOKEN']
NORTH_AGENT_ID = os.environ['NORTH_AGENT_ID']
VCCI_API_KEY = os.environ['VCCI_API_KEY']
if len(VCCI_API_KEY) < 24:
    raise RuntimeError('VCCI_API_KEY must have at least 24 characters')

north_client = AsyncNorthClient(auth_token=NORTH_AUTH_TOKEN, base_url=NORTH_BASE_URL)
north_sync_client = NorthClient(auth_token=NORTH_AUTH_TOKEN, base_url=NORTH_BASE_URL)
agent_router = AgentRouter()

# Keep existing A2A construction compatible with the uploaded working version.
north_agent = north_sync_client.agents.get(NORTH_AGENT_ID)

def value(obj: Any, key: str, default: Any='') -> Any:
    return obj.get(key, default) if isinstance(obj,dict) else getattr(obj,key,default)

def normalize(agent: Any) -> dict:
    aid = str(value(agent,'id') or value(agent,'agent_id') or '').strip()
    name = str(value(agent,'name') or aid).strip()
    return {'id':aid, 'name':name, 'description':str(value(agent,'description') or ''),
            'endpoint':str(value(agent,'endpoint') or value(agent,'url') or ''),
            'streaming':True, 'type':'north','configured':True,'active':True,
            'skills':[]}

def discover_agents() -> list[dict]:
    agents = {}
    try:
        result = north_sync_client.agents.list()
        if isinstance(result, (list,tuple)):
            raw = result
        elif isinstance(result,dict):
            raw = result.get('agents') or result.get('items') or result.get('data') or []
        else:
            raw = value(result,'agents') or value(result,'items') or value(result,'data') or []
        for item in raw:
            normalized = normalize(item)
            if normalized['id']:
                agents[normalized['id']] = normalized
    except Exception:
        logger.exception('North discovery failed; retaining configured default agent')
    fallback = normalize(north_agent)
    fallback['id'] = fallback['id'] or NORTH_AGENT_ID
    fallback['name'] = fallback['name'] or NORTH_AGENT_ID
    agents.setdefault(fallback['id'], fallback)
    return sorted(agents.values(), key=lambda a:a['name'].lower())

skill = AgentSkill(id=NORTH_AGENT_ID, name=north_agent.name,
                   description=north_agent.description or '',tags=['north'],
                   input_modes=['text/plain'], output_modes=['text/plain'])
agent_card = AgentCard(name=north_agent.name, description=north_agent.description or '',
                       version='1.1.0', default_input_modes=['text/plain'],
                       default_output_modes=['text/plain'], capabilities=AgentCapabilities(streaming=True),
                       supported_interfaces=[AgentInterface(url=os.getenv('PUBLIC_A2A_URL',f'http://{HOST}:{PORT}'),
                       protocol_binding=TransportProtocol.JSONRPC,protocol_version='1.0')], skills=[skill])
request_handler = DefaultRequestHandler(agent_executor=NorthAgentExecutor(north_client,NORTH_AGENT_ID),
                                        task_store=InMemoryTaskStore(),agent_card=agent_card)
app = FastAPI(title='VCCI AI Service',version='1.1.0')

@app.middleware('http')
async def auth(request:Request, call_next):
    if request.url.path in ('/health','/router/health'):
        return await call_next(request)
    supplied = request.headers.get('X-API-Key','')
    if not secrets.compare_digest(supplied,VCCI_API_KEY):
        return JSONResponse(status_code=401,content={'detail':'Unauthorized'})
    return await call_next(request)

@app.get('/health')
async def health():
    return {'status':'ok','service':'VCCI AI Service'}

@app.get('/agents')
async def agents():
    discovered=discover_agents()
    return {'agents':discovered,'count':len(discovered),'discovery':'north'}

# Preserve legacy Java UI response contract: {agent, confidence, reason}.
@app.post('/router/select')
async def select_legacy(payload:dict[str,Any]):
    message=str(payload.get('message') or '').strip()
    if not message:
        return JSONResponse(status_code=400,content={'agent':None,'confidence':0,'reason':'Message cannot be empty'})
    supplied=payload.get('agents')
    candidates=supplied if isinstance(supplied,list) and supplied else discover_agents()
    # Preserve the original agent dictionary including endpoint for Java manual routing.
    valid=[]
    for item in candidates[:250]:
        if not isinstance(item,dict):
            continue
        normalized=dict(item)
        normalized['id']=str(item.get('id') or item.get('agentId') or '').strip()
        normalized['name']=str(item.get('name') or normalized['id']).strip()
        if normalized['id'] and normalized['name']:
            valid.append(normalized)
    request=RouterRequest(message=message, contextId=payload.get('contextId'),
                          previousAgentId=payload.get('previousAgentId'),
                          agents=[RouterAgent.model_validate(a) for a in valid])
    decision=await agent_router.route(request)
    selected=next((a for a in valid if a['id']==decision.agentId),None)
    return {'agent':selected,'confidence':decision.confidence,'reason':decision.reason,
            'agentId':decision.agentId,'agentName':decision.agentName,'method':decision.method}

app.include_router(create_router_api(agent_router))
add_a2a_routes_to_fastapi(app,agent_card_routes=create_agent_card_routes(agent_card),
                          jsonrpc_routes=create_jsonrpc_routes(request_handler,DEFAULT_RPC_URL))

if __name__=='__main__':
    import uvicorn
    uvicorn.run(app,host=HOST,port=PORT)
