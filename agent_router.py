"""Deterministic, auditable metadata router. Confidence is heuristic, not calibrated."""
import re
from router_models import RouterRequest, RouterResponse, RouterAgent

STOP = {'the','and','with','this','that','please','would','could','from','into','about','have','what','when','your','for','are','you','can','how'}
SYNONYMS = (
    {'translate','translation','translator','french','spanish','german','language'},
    {'dice','roll','random'},
    {'database','sql','query','records','tables','table','db'},
    {'speech','transcribe','transcription','audio','voice'},
    {'social','media','linkedin','post','posts'},
    {'json','structured','schema','format','fields'},
)

def tokens(text: str) -> set[str]:
    return {w for w in re.findall(r'[a-z0-9]+', text.lower()) if len(w)>2 and w not in STOP}

def expanded(text: str) -> set[str]:
    words = tokens(text)
    for group in SYNONYMS:
        if words & group:
            words |= group
    return words

class AgentRouter:
    def __init__(self, north_client=None, router_agent_id=None):
        # Reserved for a future explicitly tested semantic router integration.
        self.north_client = north_client
        self.router_agent_id = router_agent_id

    def has_llm_router(self) -> bool:
        return False

    async def route(self, request: RouterRequest) -> RouterResponse:
        agents = request.agents
        if not agents:
            return RouterResponse(reason='No available agents')
        message = request.message.strip()
        if not message:
            return RouterResponse(reason='Empty message')
        # Use current prompt first; contextual task/background can disambiguate short follow-ups.
        query = expanded(message)
        if len(query) <= 2:
            query |= expanded(request.context.taskContext)
            query |= expanded(request.context.userContext[-1000:])
        scores = []
        for agent in agents:
            name = tokens(agent.name)
            desc = tokens(agent.description)
            skills = set()
            for s in agent.skills:
                skills |= tokens(' '.join([s.id or '', s.name or '', s.description, *s.tags, *s.examples]))
            score = 5 * len(query & name) + 2 * len(query & skills) + len(query & desc)
            scores.append((score, agent))
        scores.sort(key=lambda item: (-item[0], item[1].id))
        best_score, best = scores[0]
        if best_score == 0:
            return RouterResponse(reason='No agent capability matched the request')
        if len(scores)>1 and scores[1][0] == best_score:
            return RouterResponse(reason='Ambiguous match between multiple agents')
        return RouterResponse(agentId=best.id, agentName=best.name,
                              confidence=min(.95, .55 + best_score*.04),
                              reason='Selected using agent name, description and skill metadata', method='metadata')
