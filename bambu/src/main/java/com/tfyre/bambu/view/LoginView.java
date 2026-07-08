package com.tfyre.bambu.view;

import com.tfyre.bambu.BambuConfig;
import com.tfyre.bambu.MainLayout;
import com.tfyre.bambu.SystemRoles;
import com.tfyre.bambu.security.RememberMeService;
import com.tfyre.bambu.security.SecurityUtils;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.login.AbstractLogin;
import com.vaadin.flow.component.login.LoginForm;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinSession;
import static com.vaadin.flow.server.auth.NavigationAccessControl.SESSION_STORED_REDIRECT;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * Login view with "Remember this device" checkbox.
 *
 * On successful login with the box checked a 256-bit secure token is created server-side
 * and stored as a browser cookie (30-day lifetime). Subsequent visits auto-login via that token.
 */
@Route(LoginView.LOGIN)
@PageTitle("Login")
public class LoginView extends VerticalLayout implements BeforeEnterObserver, NotificationHelper {

    protected static final String LOGIN = "login";
    public static final String LOGIN_SUCCESS_URL = "/";

    private final LoginForm login = new LoginForm();
    private final Checkbox rememberMe = new Checkbox("Remember this device");

    @Inject
    BambuConfig config;
    @Inject
    RememberMeService rememberMeService;

    @Override
    protected void onAttach(final AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        addClassName("login-view");
        setSizeFull();
        setJustifyContentMode(FlexComponent.JustifyContentMode.CENTER);
        setAlignItems(FlexComponent.Alignment.CENTER);

        login.addLoginListener(e -> doLogin(e.getUsername(), e.getPassword()));
        login.addForgotPasswordListener(this::onForgotPassword);

        // Wrap form + remember-me in a card
        final Div card = new Div(login, buildRememberMeRow());
        card.addClassName("login-card");

        add(new H1("Bambu Web Farm"), card);

        if (config.darkMode()) {
            MainLayout.setTheme(getElement(), true);
        }
    }

    private Div buildRememberMeRow() {
        rememberMe.setValue(false);
        rememberMe.setTooltipText("Store a secure token so you stay logged in for 30 days on this browser");
        final Div row = new Div(rememberMe);
        row.addClassName("login-remember-me");
        return row;
    }

    @Override
    public void beforeEnter(final BeforeEnterEvent event) {
        if (event.getLocation().getQueryParameters().getParameters().containsKey("error")) {
            login.setError(true);
            return;
        }

        if (SecurityUtils.isLoggedIn()) {
            UI.getCurrent().getPage().setLocation(getLoggedInUrl());
            return;
        }

        // Try auto-login from remember-me cookie
        final boolean autoLoggedIn = tryRememberMeLogin();

        // Fall through to autoLogin config if still not logged in
        if (!autoLoggedIn && config.autoLogin()) {
            doLogin(SystemRoles.ROLE_ADMIN, SystemRoles.ROLE_ADMIN);
        }
    }

    /**
     * Attempts to log in using the remember-me cookie.
     *
     * @return true if auto-login succeeded
     */
    private boolean tryRememberMeLogin() {
        final Optional<String> tokenOpt = rememberMeService.readCookieToken();
        if (tokenOpt.isEmpty()) {
            return false;
        }
        final String token = tokenOpt.get();
        final Optional<String> usernameOpt = rememberMeService.getUserForToken(token);
        if (usernameOpt.isEmpty()) {
            // Expired or unknown token — clear the stale cookie
            UI.getCurrent().getPage().executeJs(RememberMeService.CLEAR_COOKIE_JS);
            return false;
        }
        final String username = usernameOpt.get();
        final boolean ok = SecurityUtils.login(username, token);
        if (ok) {
            UI.getCurrent().getPage().setLocation(getLoggedInUrl());
            return true;
        }
        // Login failed (e.g. user removed from config) — clean up
        rememberMeService.removeTokensForUser(username);
        UI.getCurrent().getPage().executeJs(RememberMeService.CLEAR_COOKIE_JS);
        return false;
    }

    private void doLogin(final String username, final String password) {
        final boolean authenticated = SecurityUtils.login(username, password);
        if (authenticated) {
            if (rememberMe.getValue()) {
                // Create a token and set the browser cookie
                final String token = rememberMeService.createToken(username);
                UI.getCurrent().getPage().executeJs(RememberMeService.SET_COOKIE_JS, token);
            }
            UI.getCurrent().getPage().setLocation(getLoggedInUrl());
        } else {
            login.setError(true);
        }
    }

    private String getLoggedInUrl() {
        return Optional.ofNullable(VaadinSession.getCurrent())
                .map(vs -> String.class.cast(vs.getSession().getAttribute(SESSION_STORED_REDIRECT)))
                .map(s -> s.isBlank() || s.startsWith(LOGIN) ? LOGIN_SUCCESS_URL : s)
                .orElse(LOGIN_SUCCESS_URL);
    }

    private void onForgotPassword(final AbstractLogin.ForgotPasswordEvent event) {
        showError("This has not been implemented");
    }
}
