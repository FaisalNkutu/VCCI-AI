from typing import Optional
from pydantic import BaseModel, Field, ConfigDict

class RouterSkill(BaseModel):
    model_config = ConfigDict(extra='ignore')
    id: Optional[str] = None
    name: Optional[str] = None
    description: str = ''
    tags: list[str] = Field(default_factory=list)
    examples: list[str] = Field(default_factory=list)

class RouterAgent(BaseModel):
    model_config = ConfigDict(extra='ignore')
    id: str = Field(min_length=1)
    name: str = Field(min_length=1)
    description: str = ''
    skills: list[RouterSkill] = Field(default_factory=list)

class RouterRequest(BaseModel):
    message: str = Field(min_length=1, max_length=20000)
    contextId: Optional[str] = Field(default=None, max_length=256)
    previousAgentId: Optional[str] = Field(default=None, max_length=256)
    agents: list[RouterAgent] = Field(default_factory=list, max_length=250)

class RouterResponse(BaseModel):
    agentId: Optional[str] = None
    agentName: Optional[str] = None
    confidence: float = Field(default=0.0, ge=0, le=1)
    reason: str = ''
    method: str = 'none'
