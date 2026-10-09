from pydantic import BaseModel, Field


class OrchestrationStep(BaseModel):

    agentId: str

    instruction: str

    dependsOn: list[int] = Field(
        default_factory=list
    )


class OrchestrationPlan(BaseModel):

    multiAgent: bool = False

    steps: list[OrchestrationStep] = Field(
        default_factory=list
    )

    reason: str = ""