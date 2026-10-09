import uuid

from a2a.helpers import new_task_from_user_message
from a2a.server.agent_execution import (
    AgentExecutor,
    RequestContext,
)
from a2a.server.events import EventQueue
from a2a.server.tasks import TaskUpdater
from a2a.types import Part

from north import AsyncNorthClient
from north.types import NorthChatResponseStream


ARTIFACT_NAME = "response"


def _text_delta(
    event: NorthChatResponseStream,
) -> str | None:

    if event.type != "content-delta":
        return None

    content = event.delta.message.content

    if isinstance(content, str):
        return content

    if getattr(content, "type", None) == "text":
        return content.text

    return None


def _stream_error(
    event: NorthChatResponseStream,
) -> str | None:

    if event.type not in (
        "message-end",
        "stream-end",
    ):
        return None

    error = (
        event.delta.error
        if event.delta
        else None
    )

    if error is None:
        return None

    return error.message or error.error_code


def _conversation_id(
    event: NorthChatResponseStream,
) -> str | None:

    if event.type != "stream-start":
        return None

    return event.conversation_id


class NorthAgentExecutor(AgentExecutor):

    def __init__(
        self,
        client: AsyncNorthClient,
        default_agent_id: str,
    ) -> None:

        self._client = client

        self._default_agent_id = (
            default_agent_id
        )

        # context_id -> selected agent
        self._context_agents: dict[
            str,
            str
        ] = {}

        # (context_id, agent_id)
        #       -> North conversation_id
        self._conversation_ids: dict[
            tuple[str, str],
            str
        ] = {}

    # -----------------------------------------------------
    # Agent selection
    # -----------------------------------------------------

    def _get_selected_agent(
        self,
        context: RequestContext,
    ) -> str:

        selected_agent = None

        message = context.message

        #
        # Read metadata sent by VCCI.
        #
        # Exact A2A SDK model versions may expose metadata
        # slightly differently, so use getattr safely.
        #

        metadata = getattr(
            message,
            "metadata",
            None,
        )

        if metadata:

            if isinstance(metadata, dict):

                selected_agent = metadata.get(
                    "agent_id"
                )

        if not selected_agent:

            selected_agent = (
                self._context_agents.get(
                    context.context_id
                )
            )

        if not selected_agent:

            selected_agent = (
                self._default_agent_id
            )

        self._context_agents[
            context.context_id
        ] = selected_agent

        return selected_agent

    # -----------------------------------------------------
    # Execute
    # -----------------------------------------------------

    async def execute(
        self,
        context: RequestContext,
        event_queue: EventQueue,
    ) -> None:

        task = (
            context.current_task
            or new_task_from_user_message(
                context.message
            )
        )

        #
        # First event MUST be Task.
        #

        await event_queue.enqueue_event(task)

        updater = TaskUpdater(
            event_queue,
            task.id,
            task.context_id,
        )

        selected_agent_id = (
            self._get_selected_agent(context)
        )

        await updater.start_work(
            updater.new_agent_message(
                [
                    Part(
                        text=(
                            "Processing request "
                            f"using agent "
                            f"{selected_agent_id}..."
                        )
                    )
                ]
            )
        )

        artifact_id = str(
            uuid.uuid4()
        )

        pending_text = None

        is_first_chunk = True

        error_message = None

        #
        # VERY IMPORTANT:
        #
        # Conversation belongs to:
        #
        # context + agent
        #
        # not just context.
        #

        conversation_key = (
            task.context_id,
            selected_agent_id,
        )

        conversation_id = (
            self._conversation_ids.get(
                conversation_key
            )
        )

        stream_arguments = {
            "agent": {
                "id": selected_agent_id
            },
            "messages": [
                {
                    "role": "user",
                    "content": (
                        context.get_user_input()
                    ),
                }
            ],
        }

        if conversation_id:

            stream_arguments[
                "conversation"
            ] = {
                "id": conversation_id
            }

        stream = self._client.chat_stream(
            **stream_arguments
        )

        async for event in stream:

            new_conversation_id = (
                _conversation_id(event)
            )

            if new_conversation_id:

                self._conversation_ids[
                    conversation_key
                ] = new_conversation_id

            error_message = (
                _stream_error(event)
            )

            if error_message:
                break

            text = _text_delta(event)

            if text is None:
                continue

            if pending_text is not None:

                await updater.add_artifact(
                    parts=[
                        Part(
                            text=pending_text
                        )
                    ],
                    artifact_id=artifact_id,
                    name=ARTIFACT_NAME,
                    append=(
                        not is_first_chunk
                    ),
                    last_chunk=False,
                )

                is_first_chunk = False

            pending_text = text

        #
        # Error
        #

        if error_message:

            await updater.failed(
                updater.new_agent_message(
                    [
                        Part(
                            text=error_message
                        )
                    ]
                )
            )

            return

        #
        # Last response chunk
        #

        if pending_text is not None:

            await updater.add_artifact(
                parts=[
                    Part(
                        text=pending_text
                    )
                ],
                artifact_id=artifact_id,
                name=ARTIFACT_NAME,
                append=(
                    not is_first_chunk
                ),
                last_chunk=True,
            )

        await updater.complete()

    # -----------------------------------------------------
    # Cancel
    # -----------------------------------------------------

    async def cancel(
        self,
        context: RequestContext,
        event_queue: EventQueue,
    ) -> None:

        updater = TaskUpdater(
            event_queue,
            context.task_id,
            context.context_id,
        )

        await updater.cancel()