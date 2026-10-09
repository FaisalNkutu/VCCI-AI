from typing import List, Optional

from pydantic import BaseModel, Field


class RouterSkill(BaseModel):

    id: Optional[str] = None

    name: Optional[str] = None

    description: str = ""

    tags: List[str] = Field(
        default_factory=list
    )

    examples: List[str] = Field(
        default_factory=list
    )


class RouterAgent(BaseModel):

    id: str

    name: str

    description: str = ""

    skills: List[RouterSkill] = Field(
        default_factory=list
    )


class RouterRequest(BaseModel):

    message: str

    contextId: Optional[str] = None

    previousAgentId: Optional[str] = None

    agents: List[RouterAgent] = Field(
        default_factory=list
    )


class RouterResponse(BaseModel):

    agentId: Optional[str] = None

    agentName: Optional[str] = None

    confidence: float = 0.0

    reason: str = ""

    method: str = "none"