package com.tfyre.bambu.view;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.RouterLink;

/**
 * The strip across the top of the settings pages that makes them read as one "Settings" page with tabs. Each
 * tab is still its own route, so every old link and bookmark keeps working and nothing about the pages
 * themselves had to change.
 */
public final class SettingsTabs {

    private SettingsTabs() {
    }

    public static Component create() {
        final Div strip = new Div(
                new RouterLink("Notifications", NotificationSettingsView.class),
                new RouterLink("Smart plugs", TasmotaSettingsView.class),
                new RouterLink("Printer connections", PrinterConnectionsView.class));
        strip.addClassName("settings-tabs");
        return strip;
    }
}
