package com.tfyre.bambu.view;

import com.tfyre.bambu.SystemRoles;
import com.tfyre.bambu.printer.EbayOAuthService;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Landing page for eBay's OAuth redirect. Set this app's real HTTPS URL to this route as the "Auth Accepted URL"
 * for your RuName in Your Account &gt; Application Keys &gt; User Tokens on the eBay developer site - eBay itself
 * only ever sees the opaque RuName, not this URL directly.
 */
@Route(EbayOAuthCallbackView.ROUTE)
@PageTitle("Connecting to eBay…")
@RolesAllowed(SystemRoles.ROLE_ADMIN)
public class EbayOAuthCallbackView extends VerticalLayout implements BeforeEnterObserver {

    public static final String ROUTE = "ebay-oauth-callback";

    @Inject
    EbayOAuthService oauth;

    @Override
    public void beforeEnter(final BeforeEnterEvent event) {
        setPadding(true);
        setSpacing(true);
        final Map<String, List<String>> params = event.getLocation().getQueryParameters().getParameters();
        final Optional<String> error = firstOf(params, "error");
        if (error.isPresent()) {
            add(new H3("eBay connection failed"), new Span(error.get()));
            return;
        }
        final Optional<String> state = firstOf(params, "state");
        final Optional<String> code = firstOf(params, "code");
        if (state.isEmpty() || code.isEmpty()) {
            add(new H3("eBay connection failed"), new Span("Missing state/code in callback."));
            return;
        }
        final Optional<String> failure = oauth.handleCallback(state.get(), code.get());
        if (failure.isPresent()) {
            add(new H3("eBay connection failed"), new Span(failure.get()));
            return;
        }
        add(new H3("Connected to eBay"), new Span("You can close this or head back to eBay Sales Orders."));
        event.forwardTo("ebay-orders");
    }

    private static Optional<String> firstOf(final Map<String, List<String>> params, final String key) {
        final List<String> values = params.get(key);
        return (values == null || values.isEmpty()) ? Optional.empty() : Optional.ofNullable(values.get(0));
    }

}
