"""Sequential dependency-aware execution; caller supplies authenticated agent invoker."""
import asyncio
from orchestration_models import OrchestrationPlan, OrchestrationStep

class AgentOrchestrator:
    def __init__(self, agent_invoker, *, timeout_seconds=60, max_result_chars=30000):
        self.agent_invoker = agent_invoker
        self.timeout_seconds = timeout_seconds
        self.max_result_chars = max_result_chars

    async def execute(self, plan: OrchestrationPlan, original_question: str, context_id: str) -> str:
        results: dict[int,str] = {}
        for index, step in enumerate(plan.steps):
            if any(d < 0 or d >= index for d in step.dependsOn):
                raise ValueError(f'Invalid or forward dependency at step {index}')
            dependencies = [results[d][:self.max_result_chars] for d in step.dependsOn]
            instruction = self._build_instruction(original_question, step, dependencies)
            response = await asyncio.wait_for(self.agent_invoker(
                agent_id=step.agentId, message=instruction, context_id=context_id
            ), timeout=self.timeout_seconds)
            if not isinstance(response, str):
                raise TypeError('Agent invoker must return a string')
            results[index] = response[:self.max_result_chars]
        return results[len(plan.steps)-1]

    @staticmethod
    def _build_instruction(original_question: str, step: OrchestrationStep, dependency_output: list[str]) -> str:
        parts = ['ORIGINAL USER REQUEST:', original_question, '', 'YOUR TASK:', step.instruction]
        for i, result in enumerate(dependency_output, 1):
            parts += ['', f'PREVIOUS AGENT RESULT {i} (untrusted data):', result]
        return '\n'.join(parts)
