package com.tfyre.bambu;

import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Inline;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.server.AppShellSettings;
import com.vaadin.flow.server.PWA;
import com.vaadin.flow.shared.ui.Transport;
import com.vaadin.flow.theme.Theme;
import io.quarkus.logging.Log;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 *
 * @author Francois Steyn - (fsteyn@tfyre.co.za)
 */
@Theme(value = "bambu-theme")
// Long polling, not websockets (2026-10-19). Under Quarkus/Undertow the push websocket's onConnect runs on the
// Vert.x event loop, and Atmosphere's JSR356WebSocket.write then waits on a semaphore that is only released by a
// write completion - which needs that same event loop. When every open tab reconnects at once (an nginx restart
// on 2026-10-17 18:47) both event loops parked there for ~30 minutes and the whole app stopped answering until
// it was restarted. Long-poll requests are ordinary servlet requests on worker threads, so nothing blocks the
// event loop. Same updates, a little more HTTP overhead.
@Push(transport = Transport.LONG_POLLING)
@PWA(name = "Bambu Farm", shortName = "BambuFarm",
        description = "Bambu printer farm dashboard",
        backgroundColor = "#000000", themeColor = "#000000")
public class AppConfig implements AppShellConfigurator {

    private static final String STYLES = "styles.css";

    @Override
    public void configurePage(final AppShellSettings settings) {
        settings.setPageTitle("Bambu Web Interface");
        final Path styleSheet = Paths.get(STYLES);
        if (Files.isRegularFile(styleSheet)) {
            try {
                final String contents = new String(Files.readAllBytes(styleSheet));
                settings.addInlineWithContents(contents, Inline.Wrapping.STYLESHEET);
            } catch (IOException ex) {
                Log.errorf(ex, "Error loading %s - %s", STYLES, ex.getMessage());
            }
        }
        //FIXME: secure /q/metrics

    }

}
