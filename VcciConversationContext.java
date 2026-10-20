// Java 21; Jackson databind dependency required. Client is authoritative owner of context.
// Add this class to your VCCI Java package, adjusting the package declaration.
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;

public final class VcciConversationContext {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String contextId = UUID.randomUUID().toString();
    private final ObjectNode context = JSON.createObjectNode();
    private final ArrayNode history = JSON.createArrayNode();
    private final ArrayNode results = JSON.createArrayNode();

    public VcciConversationContext(String systemInstructions, String userContext, String taskContext) {
        context.put("systemInstructions", systemInstructions);
        context.put("userContext", userContext);
        context.put("taskContext", taskContext);
        context.set("conversationHistory", history);
        context.set("agentResults", results);
    }
    public String contextId() { return contextId; }
    public void addMessage(String role, String content, String agentId) {
        ObjectNode msg = JSON.createObjectNode();
        msg.put("role", role); msg.put("content", content);
        if (agentId != null) msg.put("agentId", agentId);
        history.add(msg);
        while (history.size() > 30) history.remove(0);
    }
    public void addAgentResult(String agentId, String content) {
        ObjectNode msg = JSON.createObjectNode();
        msg.put("role", "agent"); msg.put("agentId", agentId); msg.put("content", content);
        results.add(msg);
        while (results.size() > 15) results.remove(0);
    }
    public ObjectNode routingRequest(String prompt, ArrayNode agents, String previousAgentId) {
        ObjectNode req = JSON.createObjectNode();
        req.put("message", prompt); req.put("contextId", contextId);
        if (previousAgentId != null) req.put("previousAgentId", previousAgentId);
        req.set("agents", agents.deepCopy()); req.set("context", context.deepCopy());
        return req;
    }
    public ObjectNode a2aMetadata(String selectedAgentId) {
        ObjectNode metadata = JSON.createObjectNode();
        metadata.put("agent_id", selectedAgentId);
        metadata.set("context", context.deepCopy());
        return metadata;
    }
    public ObjectNode snapshot() { return context.deepCopy(); }
}
