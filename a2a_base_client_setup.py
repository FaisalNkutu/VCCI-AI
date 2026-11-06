"""A2A SDK 1.3.x shared client adapter. Preserves the legacy project entry points."""
import json
import logging
from uuid import uuid4

import httpx
from a2a.client import A2ACardResolver, ClientFactory, ClientConfig
from a2a.types import Message, Part, TextPart
from a2a.utils.constants import EXTENDED_AGENT_CARD_PATH

logger = logging.getLogger(__name__)


def _base_url(url, port, path=''):
    return f"{str(url).rstrip('/')}:{port}/{str(path).strip('/')}".rstrip('/')


def _message(payload):
    return Message(role='user', parts=[Part(root=TextPart(text=json.dumps(payload)))], message_id=uuid4().hex)


def _json(value):
    return value.model_dump(mode='json', exclude_none=True) if hasattr(value, 'model_dump') else value


async def _make_client(url, port, path='', httpx_client=None):
    if httpx_client is None:
        raise ValueError('An open httpx.AsyncClient is required')
    resolver = A2ACardResolver(httpx_client=httpx_client, base_url=_base_url(url, port, path))
    card = await resolver.get_agent_card()
    if card.supports_authenticated_extended_card:
        logger.info('Extended card advertised; using public card unless authenticated credentials are configured')
    factory = ClientFactory(config=ClientConfig(httpx_client=httpx_client))
    return factory.create(card)


async def CreateA2AClient(url: str, port: int, path: str = ''):
    """Legacy entry point. Caller must close client and underlying HTTP resources."""
    http = httpx.AsyncClient(timeout=400)
    try:
        client = await _make_client(url, port, path, http)
        # Keep transport reachable for callers that manage persistent sessions.
        client._project_httpx_client = http
        return client
    except Exception:
        await http.aclose()
        raise


async def _send(client, payload, streaming=False, callback=None):
    message = _message(payload)
    if streaming:
        async for event in client.send_message(message):
            if callback is not None:
                callback(_json(event))
        if callback is not None:
            callback('DONE')
        return None
    async for event in client.send_message(message):
        response = _json(event)
        if callback is not None:
            callback(response)
        return event
    raise RuntimeError('A2A agent returned no response')


async def A2AClientSend(url: str, port: int, payload: dict, streaming=False, callback=None, **kwargs):
    async with httpx.AsyncClient(timeout=400) as http:
        client = await _make_client(url, port, kwargs.get('path', ''), http)
        try:
            return await _send(client, payload, streaming, callback)
        finally:
            await client.close()


class A2AFragCoreClient:
    def __init__(self, url: str, port: int):
        self.url, self.port = url, port
        self.client = None

    async def create_client(self):
        self.client = await CreateA2AClient(self.url, self.port)

    async def send(self, payload: dict, streaming=False, callback=None):
        if self.client is None:
            await self.create_client()
        return await _send(self.client, payload, streaming, callback)

    async def close(self):
        if self.client is not None:
            await self.client.close()
            await self.client._project_httpx_client.aclose()
            self.client = None
