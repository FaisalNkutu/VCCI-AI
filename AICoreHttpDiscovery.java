package com.softknf.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/** Optional bridge for AI Core deployments exposing a JSON agent registry. */
public final class AICoreHttpDiscovery {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final URI uri;

    public AICoreHttpDiscovery(String registryUrl) {
        uri = URI.create(Objects.requireNonNull(registryUrl));
        if (!List.of("http", "https").contains(uri.getScheme()))
            throw new IllegalArgumentException("AI Core registry URL must use http or https");
    }

    public record Result(Map<String, List<String>> commands, Map<String, String> urls) {}

    public Result discover() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json").GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2)
            throw new IllegalStateException("AI Core registry HTTP " + resp.statusCode() + ": " + resp.body());
        JsonNode root = mapper.readTree(resp.body());
        Map<String, List<String>> commands = new LinkedHashMap<>();
        Map<String, String> urls = new LinkedHashMap<>();
		if (agents.isObject()) {
			agents.fields().forEachRemaining(entry -> {
				String name = entry.getKey();
				String endpoint = entry.getValue().asText("");

				if (!name.isBlank()) {
					commands.put(name, List.of(name));
					urls.put(name, endpoint);
				}
			});

		} else if (agents.isArray()) {
            for (JsonNode agent : agents) {
                String name = agent.path("name").asText(agent.path("id").asText(""));
                if (name.isBlank()) continue;
                String endpoint = agent.path("endpoint").asText(agent.path("url").asText(""));
                urls.put(name, endpoint);
                List<String> cmds = new ArrayList<>();
                for (JsonNode command : agent.path("commands")) cmds.add(command.asText());
                commands.put(name, cmds);
            }
        } else {
            JsonNode commandMap = root.path("commandList");
            if (commandMap.isMissingNode()) commandMap = root.path("commandData");
            if (commandMap.isObject()) commandMap.fields().forEachRemaining(e -> {
                List<String> list = new ArrayList<>();
                if (e.getValue().isArray()) e.getValue().forEach(v -> list.add(v.asText()));
                commands.put(e.getKey(), list);
            });
            JsonNode urlMap = root.path("agentURLs");
            if (urlMap.isObject()) urlMap.fields().forEachRemaining(e -> urls.put(e.getKey(), e.getValue().asText()));
        }
        if (commands.isEmpty() && urls.isEmpty())
            throw new IllegalStateException("No AI Core agents in registry response");
        return new Result(commands, urls);
    }
}
