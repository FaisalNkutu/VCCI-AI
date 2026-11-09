
from __future__ import annotations

# A2A SDK 1.3.0 / Google ADK 1.25.0: do not monkey-patch SDK modules.
import logging
logger = logging.getLogger("A2AAgentBaseSetup")

import asyncio
import logging
import os
import threading
import time


import httpx
import nest_asyncio
import uvicorn

import json

from keyword import kwlist
from typing import Any
from typing import AsyncGenerator
from typing import ClassVar
from typing import Dict
from typing import Type
from typing import Callable

from a2a.client import ClientConfig, ClientFactory
from a2a.server.apps import A2AStarletteApplication
from a2a.server.request_handlers import DefaultRequestHandler
from a2a.server.tasks import InMemoryTaskStore
from a2a.types import (
    AgentCapabilities,
    AgentCard,
    AgentSkill,
    TransportProtocol,
)
from a2a.utils.constants import AGENT_CARD_WELL_KNOWN_PATH
from dotenv import load_dotenv
from google.adk.a2a.executor.a2a_agent_executor import (
    A2aAgentExecutor,
    A2aAgentExecutorConfig,
)
from google.adk.agents import BaseAgent, Agent, SequentialAgent
from google.adk.agents.base_agent_config import BaseAgentConfig
from google.adk.agents.invocation_context import InvocationContext
from google.adk.events.event import Event
from google.genai import types
from pydantic import ConfigDict
from typing_extensions import override
from google.adk.agents.remote_a2a_agent import RemoteA2aAgent
from google.adk.artifacts import InMemoryArtifactService
from google.adk.memory.in_memory_memory_service import InMemoryMemoryService
from google.adk.runners import Runner
from google.adk.sessions import InMemorySessionService
from google.adk.tools import google_search

from google.adk.a2a.utils.agent_to_a2a import to_a2a

from fastapi import FastAPI, Request
import traceback




#
#   init_asyncio()   run only once at init
#
def init_asyncio(self=None):
    # Apply nest_asyncio
    nest_asyncio.apply()
    # Store server tasks
    server_tasks: list[asyncio.Task] = []



#
#   A2AAgentBase
#       base class to encapsulate the whole setup for an agent to be used inside FragCore      
#       Currently used for 2 system parts: [AgentFragCore , AgentMap]
#       they both use this base setup
#
class A2AAgentBaseSetup():

    def __init__(self, agent, agent_card, port,  tool_callback_dict ):
        self.agent = agent            
        self.agent.tool_invoke_callback = tool_callback_dict["default"]
        self.agent.tool_callback_dict = tool_callback_dict
        self.agent_card = agent_card
        self.port = port
        


    #
    #  create_agent_a2a_server
    #   based on A2A samples
    #
    def create_agent_a2a_server(self ):
        """Create an A2A server for any ADK agent.

        Args:
            agent: The ADK agent instance
            agent_card: The ADK agent card

        Returns:
            A2AStarletteApplication instance
        """
        runner = Runner(
            app_name=self.agent.name,
            agent=self.agent,
            artifact_service=InMemoryArtifactService(),
            session_service=InMemorySessionService(),
            memory_service=InMemoryMemoryService(),
        )

        config = A2aAgentExecutorConfig()
        executor = A2aAgentExecutor(runner=runner, config=config)

        request_handler = DefaultRequestHandler(
            agent_executor=executor,
            task_store=InMemoryTaskStore(),
        )

        # Create A2A application
        app = A2AStarletteApplication(
            agent_card=self.agent_card, http_handler=request_handler
        )
        
        # Keep SDK-provided routes unchanged; the agent card controls the endpoint.

        return app

    def to_a2a_app(self):
        return to_a2a(  self.agent,
                       host = '0.0.0.0', 
                       port = self.port )

    async def run_agent_server(self) -> None:
        """Run a single agent server."""
        app = self.create_agent_a2a_server()

        config = uvicorn.Config(
            app.build(),
            host='0.0.0.0',
            port=self.port,
            log_level='warning',
            loop='auto',
        )

        self.server = uvicorn.Server(config)
        await self.server.serve()


    async def start_all_servers(self) -> None:
        """Start all servers in the same event loop."""
        # Create tasks for all servers
        tasks = [
            asyncio.create_task(
                self.run_agent_server()
            )       
        ]

        # Give servers time to start
        await asyncio.sleep(2)
                   
        # Keep servers running
        try:
            await asyncio.gather(*tasks)
        except KeyboardInterrupt:
            logger.info('Shutting down servers...')


    # Run in a background thread
    def run_servers_in_background(self) -> None:
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        loop.run_until_complete(self.start_all_servers())


    #
    #
    #       Main: Test script for running this agent.
    #
    def start_daemon(self):           
        # Start the thread
        self.server_thread = threading.Thread(target=self.run_servers_in_background, daemon=True)
        self.server_thread.start()
        # Wait for servers to be ready
        time.sleep(3)

