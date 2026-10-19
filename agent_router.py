import logging
import re
from typing import Optional

from router_models import (
    RouterAgent,
    RouterRequest,
    RouterResponse,
)


logger = logging.getLogger(
    __name__
)


class AgentRouter:

    def __init__(
        self,
        north_client=None,
        router_agent_id: Optional[str] = None,
    ):

        self.north_client = north_client

        self.router_agent_id = (
            router_agent_id.strip()
            if router_agent_id
            else None
        )


    # ========================================================
    # PUBLIC API
    # ========================================================

    async def route(
        self,
        request: RouterRequest,
    ) -> RouterResponse:

        message = (
            request.message or ""
        ).strip()


        if not message:

            return RouterResponse(
                reason="The request is empty.",
                method="none",
            )


        agents = request.agents or []


        # ----------------------------------------------------
        # No agents
        # ----------------------------------------------------

        if not agents:

            return RouterResponse(
                reason=(
                    "No A2A agents are currently "
                    "available."
                ),
                method="none",
            )


        # ----------------------------------------------------
        # Exactly one agent
        # ----------------------------------------------------

        if len(agents) == 1:

            agent = agents[0]

            return RouterResponse(
                agentId=agent.id,
                agentName=agent.name,
                confidence=1.0,
                reason=(
                    "Only one A2A agent is currently "
                    "available."
                ),
                method="single-agent",
            )


        # ----------------------------------------------------
        # Previous agent continuity
        # ----------------------------------------------------

        previous = self._find_agent(
            agents,
            request.previousAgentId,
        )


        if previous is not None:

            if self._looks_like_followup(
                message
            ):

                return RouterResponse(
                    agentId=previous.id,
                    agentName=previous.name,
                    confidence=0.95,
                    reason=(
                        "The request appears to continue "
                        "the previous conversation."
                    ),
                    method="conversation-continuity",
                )


        # ----------------------------------------------------
        # Strong metadata match
        # ----------------------------------------------------

        metadata_result = (
            self._metadata_route(
                message,
                agents,
            )
        )


        if (
            metadata_result is not None
            and metadata_result.confidence >= 0.75
        ):

            return metadata_result


        # ----------------------------------------------------
        # LLM semantic routing
        # ----------------------------------------------------

        if self.has_llm_router():

            try:

                llm_result = (
                    await self._llm_route(
                        message,
                        agents,
                    )
                )

                if (
                    llm_result is not None
                    and llm_result.agentId
                ):

                    return llm_result

            except Exception:

                logger.exception(
                    "LLM routing failed."
                )


        # ----------------------------------------------------
        # Lower-confidence metadata fallback
        # ----------------------------------------------------

        if (
            metadata_result is not None
            and metadata_result.agentId
        ):

            return metadata_result


        return RouterResponse(
            confidence=0.0,
            reason=(
                "No available agent clearly "
                "matches the request."
            ),
            method="none",
        )


    # ========================================================
    # ROUTER STATUS
    # ========================================================

    def has_llm_router(self) -> bool:

        return bool(
            self.north_client
            and self.router_agent_id
        )


    # ========================================================
    # METADATA ROUTING
    # ========================================================

    def _metadata_route(
        self,
        message: str,
        agents: list[RouterAgent],
    ) -> Optional[RouterResponse]:

        message_tokens = self._tokens(
            message
        )


        if not message_tokens:

            return None


        best_agent = None

        best_score = 0.0


        for agent in agents:

            score = self._score_agent(
                message_tokens,
                agent,
            )


            logger.debug(
                "Agent routing candidate "
                "%s score=%.3f",
                agent.name,
                score,
            )


            if score > best_score:

                best_score = score

                best_agent = agent


        if best_agent is None:
            return None


        #
        # Normalize into a 0..1 confidence.
        #

        confidence = min(
            1.0,
            best_score,
        )


        if confidence <= 0.0:
            return None


        return RouterResponse(
            agentId=best_agent.id,
            agentName=best_agent.name,
            confidence=confidence,
            reason=(
                "The request matched the "
                "agent's A2A AgentCard metadata."
            ),
            method="metadata",
        )


    def _score_agent(
        self,
        message_tokens: set[str],
        agent: RouterAgent,
    ) -> float:

        score = 0.0


        # ----------------------------------------------------
        # Agent name
        # ----------------------------------------------------

        name_tokens = self._tokens(
            agent.name
        )


        name_matches = (
            message_tokens
            & name_tokens
        )


        score += (
            len(name_matches)
            * 0.35
        )


        # ----------------------------------------------------
        # Description
        # ----------------------------------------------------

        description_tokens = (
            self._tokens(
                agent.description
            )
        )


        description_matches = (
            message_tokens
            & description_tokens
        )


        score += (
            len(description_matches)
            * 0.20
        )


        # ----------------------------------------------------
        # Skills
        # ----------------------------------------------------

        for skill in agent.skills:

            skill_name_tokens = (
                self._tokens(
                    skill.name or ""
                )
            )


            score += (
                len(
                    message_tokens
                    & skill_name_tokens
                )
                * 0.40
            )


            skill_description_tokens = (
                self._tokens(
                    skill.description
                )
            )


            score += (
                len(
                    message_tokens
                    & skill_description_tokens
                )
                * 0.25
            )


            for tag in skill.tags:

                tag_tokens = (
                    self._tokens(
                        tag
                    )
                )

                score += (
                    len(
                        message_tokens
                        & tag_tokens
                    )
                    * 0.30
                )


            for example in skill.examples:

                example_tokens = (
                    self._tokens(
                        example
                    )
                )

                score += (
                    len(
                        message_tokens
                        & example_tokens
                    )
                    * 0.20
                )


        return score


    # ========================================================
    # LLM ROUTING
    # ========================================================

    async def _llm_route(
        self,
        message: str,
        agents: list[RouterAgent],
    ) -> Optional[RouterResponse]:

        if not self.has_llm_router():
            return None


        #
        # Keep this method isolated because the exact
        # North SDK invocation depends upon the version
        # installed in your AIService.
        #
        # Until NORTH_ROUTER_AGENT_ID is configured,
        # metadata routing continues to work.
        #

        logger.info(
            "Dedicated North routing agent configured: %s",
            self.router_agent_id,
        )


        return None


    # ========================================================
    # HELPERS
    # ========================================================

    @staticmethod
    def _tokens(
        value: str,
    ) -> set[str]:

        if not value:
            return set()


        words = re.findall(
            r"[a-zA-Z0-9]+",
            value.lower(),
        )


        stop_words = {
            "a",
            "an",
            "and",
            "are",
            "for",
            "in",
            "is",
            "of",
            "on",
            "or",
            "the",
            "to",
            "what",
            "which",
            "who",
            "with",
        }


        return {
            word
            for word in words
            if (
                len(word) > 1
                and word not in stop_words
            )
        }


    @staticmethod
    def _find_agent(
        agents: list[RouterAgent],
        agent_id: Optional[str],
    ) -> Optional[RouterAgent]:

        if not agent_id:
            return None


        for agent in agents:

            if (
                agent.id == agent_id
                or agent.name == agent_id
            ):
                return agent


        return None


    @staticmethod
    def _looks_like_followup(
        message: str,
    ) -> bool:

        value = message.lower().strip()


        followups = (
            "and ",
            "also ",
            "what about",
            "how about",
            "why ",
            "explain that",
            "explain this",
            "tell me more",
            "continue",
            "more detail",
            "give me more",
        )


        return any(
            value.startswith(prefix)
            for prefix in followups
        )