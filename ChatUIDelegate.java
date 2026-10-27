package com.softknf.chat;

import com.calian.paradigm.ui.framework.framework.UIDelegate;
import com.calian.paradigm.ui.framework.framework.IApplicationUIProvider;

import java.util.LinkedHashMap;

public class ChatUIDelegate extends UIDelegate {

    public ChatUIDelegate(ChatUIComponent component) {
        super(component);
    }

    @Override
    public IApplicationUIProvider createUIProvider(
            LinkedHashMap<String, Object> parameters) {

        throw new UnsupportedOperationException(
                "Standalone ChatUIProvider is not yet configured");
    }
}