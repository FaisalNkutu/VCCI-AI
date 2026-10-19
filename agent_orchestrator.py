import logging

from orchestration_models import (
    OrchestrationPlan,
    OrchestrationStep,
)

from router_models import (
    RouterAgent,
)


logger = logging.getLogger(__name__)


class AgentOrchestrator:

    def __init__(
        self,
        agent_invoker,
    ):

        self.agent_invoker =
                agent_invoker

    async def execute(
        self,
        plan: OrchestrationPlan,
        original_question: str,
        context_id: str,
    ) -> str:

        if not plan.steps:

            raise ValueError(
                "Orchestration plan "
                "contains no steps."
            )

        results: dict[int, str] = {}

        for index, step in enumerate(
            plan.steps
        ):

            dependency_output = []

            for dependency_index in (
                step.dependsOn
            ):

                if dependency_index not in (
                    results
                ):

                    raise ValueError(
                        "Missing orchestration "
                        f"dependency: "
                        f"{dependency_index}"
                    )

                dependency_output.append(
                    results[
                        dependency_index
                    ]
                )

            instruction = (
                self._build_instruction(
                    original_question=(
                        original_question
                    ),
                    step=step,
                    dependency_output=(
                        dependency_output
                    ),
                )
            )

            logger.info(
                "Executing orchestration "
                "step %d using agent %s",
                index,
                step.agentId,
            )

            response = (
                await self.agent_invoker(
                    agent_id=step.agentId,
                    message=instruction,
                    context_id=context_id,
                )
            )

            results[index] = response

        #
        # Last step is the final answer.
        #
        return results[
            len(plan.steps) - 1
        ]

    def _build_instruction(
        self,
        original_question: str,
        step: OrchestrationStep,
        dependency_output: list[str],
    ) -> str:

        parts = [

            "ORIGINAL USER REQUEST:",
            original_question,

            "",
            "YOUR TASK:",
            step.instruction,
        ]

        if dependency_output:

            parts.extend(
                [
                    "",
                    "RESULTS FROM PREVIOUS "
                    "AGENTS:",
                ]
            )

            for index, result in enumerate(
                dependency_output,
                start=1,
            ):

                parts.extend(
                    [
                        "",
                        (
                            f"PREVIOUS RESULT "
                            f"{index}:"
                        ),
                        result,
                    ]
                )

        return "\n".join(
            parts
        )