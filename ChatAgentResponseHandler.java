
package com.softknf.chat;

import com.calian.paradigm.core.bto.model.FeatureRequestResponse;
import com.calian.paradigm.ui.framework.framework.IFRREnabled;

import javax.swing.SwingUtilities;
import com.calian.paradigm.service.aiservice.objs.AISGetCommandsRequest;
import java.util.function.Consumer;

public class ChatAgentResponseHandler implements IFRREnabled {

    private final Consumer<FeatureRequestResponse> onSuccess;
    private final Consumer<FeatureRequestResponse> onFailure;

    public ChatAgentResponseHandler(
            Consumer<FeatureRequestResponse> onSuccess,
            Consumer<FeatureRequestResponse> onFailure) {

        this.onSuccess = onSuccess;
        this.onFailure = onFailure;
    }

    @Override
    public void processResponse(FeatureRequestResponse response) {
        SwingUtilities.invokeLater(() -> onSuccess.accept(response));
    }

    /** Connect an existing live Paradigm response callback to the Swing registry. */
    public static ChatAgentResponseHandler forAgentDiscovery(ChatApp app,
            Consumer<FeatureRequestResponse> onFailure) {
        return new ChatAgentResponseHandler(response -> {
            if (response.getFeatureParameters() instanceof AISGetCommandsRequest result) {
                app.acceptParadigmAgents(result.getCommandList(), result.getAgentURLs());
            }
        }, onFailure);
    }

    @Override
    public void processRequestFailed(FeatureRequestResponse response) {
        SwingUtilities.invokeLater(() -> onFailure.accept(response));
    }
}