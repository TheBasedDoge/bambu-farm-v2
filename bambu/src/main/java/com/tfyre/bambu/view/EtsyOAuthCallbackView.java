package com.tfyre.bambu.view;

import com.tfyre.bambu.SystemRoles;
import com.tfyre.bambu.printer.EtsyOAuthService;
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
import java.util.Optional;

/**
 * Landing page for Etsy's OAuth redirect. Registered as {@code bambu.etsy.redirect-uri}, e.g.
 * {@code https://your-domain/etsy-oauth-callback}. Exchanges the authorization code for tokens and bounces the user
 * back to the Etsy Sales Orders page.
 */
@Route(EtsyOAuthCallbackView.ROUTE)
@PageTitle("Connecting to Etsy…")
@RolesAllowed(SystemRoles.ROLE_ADMIN)
public class EtsyOAuthCallbackView extends VerticalLayout implements BeforeEnterObserver {

    public static final String ROUTE = "etsy-oauth-callback";

    @Inject
    EtsyOAuthService oauth;

    @Override
    public void beforeEnter(final BeforeEnterEvent event) {
        setPadding(true);
        setSpacing(true);
        final var params = event.getLocation().getQueryParameters().getParameters();
        final Optional<String> error = firstOf(params, "error");
        if (error.isPresent()) {
            add(new H3("Etsy connection failed"), new Span(error.get()));
            return;
        }
        final Optional<String> state = firstOf(params, "state");
        final Optional<String> code = firstOf(params, "code");
        if (state.isEmpty() || code.isEmpty()) {
            add(new H3("Etsy connection failed"), new Span("Missing state/code in callback."));
            return;
        }
        final Optional<String> failure = oauth.handleCallback(state.get(), code.get());
        if (failure.isPresent()) {
            add(new H3("Etsy connection failed"), new Span(failure.get()));
            return;
        }
        add(new H3("Connected to Etsy"), new Span("You can close this or head back to Etsy Sales Orders."));
        event.forwardTo("etsy-orders");
    }

    private static Optional<String> firstOf(final java.util.Map<String, List<String>> params, final String key) {
        final List<String> values = params.get(key);
        return (values == null || values.isEmpty()) ? Optional.empty() : Optional.ofNullable(values.get(0));
    }

}
