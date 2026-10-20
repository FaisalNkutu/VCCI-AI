from pydantic import BaseModel, Field

class OrchestrationStep(BaseModel):
    agentId: str = Field(min_length=1)
    instruction: str = Field(min_length=1, max_length=20000)
    dependsOn: list[int] = Field(default_factory=list)

class OrchestrationPlan(BaseModel):
    multiAgent: bool = False
    steps: list[OrchestrationStep] = Field(min_length=1, max_length=20)
    reason: str = ''
