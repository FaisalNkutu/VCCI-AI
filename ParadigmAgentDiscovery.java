package com.softknf.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Thread-safe boundary between asynchronous Paradigm agent discovery
 * and the standalone Swing chatbot.
 */
public final class ParadigmAgentDiscovery {

    public record Agent(String commandId, String name, String endpoint,
                        List<String> commands) {
        public Agent {
            Objects.requireNonNull(commandId, "commandId");
            Objects.requireNonNull(name, "name");
            endpoint = endpoint == null ? "" : endpoint;
            commands = List.copyOf(commands == null ? List.of() : commands);
        }
    }

    private final Consumer<List<Agent>> onUpdate;
    private List<Agent> snapshot = List.of();

    public ParadigmAgentDiscovery(Consumer<List<Agent>> onUpdate) {
        this.onUpdate = Objects.requireNonNull(onUpdate, "onUpdate");
    }

    public void acceptResponse(Map<String, ? extends List<String>> commandList,
                               Map<String, String> agentURLs) {
        Map<String, ? extends List<String>> commands =
                commandList == null ? Collections.emptyMap() : commandList;
        Map<String, String> urls =
                agentURLs == null ? Collections.emptyMap() : agentURLs;

        System.out.println("===== PARADIGM acceptResponse CALLED =====");
        commands.forEach((id, values) -> System.out.println(
                "COMMAND = " + id + " | FRIENDLY DATA = " + values));

        Map<String, Agent> discovered = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends List<String>> entry : commands.entrySet()) {
            String commandId = entry.getKey();
            if (commandId == null || commandId.isBlank()) {
                continue;
            }

            List<String> values = entry.getValue();
            String friendlyName = commandId;
            if (values != null && !values.isEmpty()
                    && values.get(0) != null && !values.get(0).isBlank()) {
                friendlyName = values.get(0);
            }
            List<String> safeValues = values == null ? List.of() :
                    values.stream().filter(Objects::nonNull).toList();
            discovered.put(commandId, new Agent(commandId, friendlyName,
                    urls.getOrDefault(commandId, ""), safeValues));
        }

        for (Map.Entry<String, String> entry : urls.entrySet()) {
            String commandId = entry.getKey();
            if (commandId == null || commandId.isBlank()) {
                continue;
            }
            discovered.putIfAbsent(commandId,
                    new Agent(commandId, commandId, entry.getValue(), List.of()));
        }

        List<Agent> fresh = List.copyOf(new ArrayList<>(discovered.values()));
        synchronized (this) {
            snapshot = fresh;
        }
        onUpdate.accept(fresh);
    }

    public synchronized List<Agent> snapshot() {
        return snapshot;
    }
}