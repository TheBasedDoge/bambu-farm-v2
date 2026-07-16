package com.tfyre.bambu;

import com.tfyre.bambu.security.SecurityUtils;
import com.tfyre.bambu.view.batchprint.BatchPrintView;
import com.tfyre.bambu.view.CameraView;
import com.tfyre.bambu.view.HistoryView;
import com.tfyre.bambu.view.LogsView;
import com.tfyre.bambu.view.MaintenanceView;
import com.tfyre.bambu.view.PrinterView;
import com.tfyre.bambu.view.SdCardView;
import com.tfyre.bambu.security.RememberMeService;
import com.tfyre.bambu.view.AutomationView;
import com.tfyre.bambu.view.EbayOrdersView;
import com.tfyre.bambu.view.EtsyOrdersView;
import com.tfyre.bambu.view.NotificationSettingsView;
import com.tfyre.bambu.view.SpoolsView;
import com.tfyre.bambu.view.TasmotaSettingsView;
import com.tfyre.bambu.view.UpdateHeader;
import com.tfyre.bambu.view.dashboard.Dashboard;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.router.HighlightConditions;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.theme.lumo.Lumo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;

/**
 * The main view contains a button and a click listener.
 *
 * @author Francois Steyn - (fsteyn@tfyre.co.za)
 */
public class MainLayout extends AppLayout {

    private static final Map<Class<? extends Component>, AccessRoute> MAP = makeEntries(Stream.of(
            CameraView.class,
            SdCardView.class,
            PrinterView.class,
            BatchPrintView.class,
            AutomationView.class,
            HistoryView.class,
            LogsView.class,
            MaintenanceView.class,
            SpoolsView.class,
            NotificationSettingsView.class,
            TasmotaSettingsView.class,
            EtsyOrdersView.class,
            EbayOrdersView.class
    ));

    private static final Map<Class<? extends Component>, VaadinIcon> ICONS = Map.ofEntries(
            Map.entry(Dashboard.class, VaadinIcon.DASHBOARD),
            Map.entry(CameraView.class, VaadinIcon.CAMERA),
            Map.entry(PrinterView.class, VaadinIcon.PRINT),
            Map.entry(BatchPrintView.class, VaadinIcon.COPY),
            Map.entry(AutomationView.class, VaadinIcon.AUTOMATION),
            Map.entry(SdCardView.class, VaadinIcon.ARCHIVE),
            Map.entry(HistoryView.class, VaadinIcon.CLOCK),
            Map.entry(LogsView.class, VaadinIcon.CLIPBOARD_TEXT),
            Map.entry(MaintenanceView.class, VaadinIcon.WRENCH),
            Map.entry(SpoolsView.class, VaadinIcon.CIRCLE_THIN),
            Map.entry(NotificationSettingsView.class, VaadinIcon.BELL),
            Map.entry(TasmotaSettingsView.class, VaadinIcon.PLUG),
            Map.entry(EtsyOrdersView.class, VaadinIcon.SHOP),
            Map.entry(EbayOrdersView.class, VaadinIcon.CART)
    );

    private final HorizontalLayout header = new HorizontalLayout();
    private final Div headerContent = new Div();
    private final List<VerticalLayout> drawerItems = new ArrayList<>();
    private final Checkbox darkMode = new Checkbox("Dark Theme");
    private final Checkbox notifications = new Checkbox("Notifications");
    private final Button drawerToggle = new Button(new Icon(VaadinIcon.MENU));

    @Inject
    BambuConfig config;
    @Inject
    RememberMeService rememberMeService;

    public MainLayout() {
    }

    public static void setTheme(final Element element, final boolean darkMode) {
        final String js = "document.documentElement.setAttribute('theme', $0)";
        element.executeJs(js, darkMode ? Lumo.DARK : Lumo.LIGHT);
    }

    private void setTheme() {
        //FIXME: use security context
        SecurityUtils.getPrincipal()
                .flatMap(p -> Optional.ofNullable(config.users().get(p.getName().toLowerCase())))
                .ifPresent(u -> {
                    if (u.darkMode().orElseGet(config::darkMode)) {
                        darkMode.setValue(true);
                    }
                });
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        darkMode.addValueChangeListener(l -> setTheme(getElement(), l.getValue()));
        setupNotifications();
        setDrawerOpened(true);
        createHeader();
        createDrawer();
        addToNavbar(header);
        setTheme();
        setupDrawerToggle();
    }

    /**
     * Desktop: the toggle switches between the full drawer and an icon-only rail. Mobile (overlay drawer): normal open/close.
     */
    private void setupDrawerToggle() {
        drawerToggle.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_TERTIARY);
        drawerToggle.setTooltipText("Toggle sidebar");
        getElement().executeJs("""
                const al = this;
                if (al.hasAttribute('overlay')) {
                    al.drawerOpened = false;
                } else if (localStorage.getItem('bambufarm-rail') === 'on') {
                    al.classList.add('drawer-rail');
                    window.dispatchEvent(new Event('resize'));
                }""");
        drawerToggle.addClickListener(l -> getElement().executeJs("""
                const al = this;
                if (al.hasAttribute('overlay')) {
                    al.drawerOpened = !al.drawerOpened;
                } else {
                    al.classList.toggle('drawer-rail');
                    localStorage.setItem('bambufarm-rail', al.classList.contains('drawer-rail') ? 'on' : 'off');
                    window.dispatchEvent(new Event('resize'));
                }"""));
    }

    private void setupNotifications() {
        notifications.setTooltipText("Browser notifications when a print finishes or fails");
        getElement().executeJs("return localStorage.getItem('bambufarm-notifications') === 'on'")
                .then(Boolean.class, value -> notifications.setValue(Boolean.TRUE.equals(value)));
        notifications.addValueChangeListener(l -> {
            if (!l.isFromClient()) {
                return;
            }
            getElement().executeJs("""
                    if ($0) {
                        localStorage.setItem('bambufarm-notifications', 'on');
                        if (window.Notification && Notification.permission === 'default') {
                            Notification.requestPermission();
                        }
                    } else {
                        localStorage.setItem('bambufarm-notifications', 'off');
                    }""", l.getValue());
        });
    }

    private String getUsername() {
        return SecurityUtils.getPrincipal()
                .map(p -> p.getName())
                .orElse("Unknown");
    }

    private void createHeader() {
        header.removeAll();
        final H1 logo = new H1("Bambu Web Interface: %s".formatted(getUsername()));
        logo.addClassNames("text-l", "m-m", "header-title");
        logo.getStyle()
                .set("font-size", "var(--lumo-font-size-l)")
                .set("margin", "0");

        headerContent.addClassName("header-content");
        header.add(drawerToggle, logo, headerContent);

        header.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.CENTER);

        header.setWidth("100%");
        header.addClassNames("py-0", "px-m", "main-header");
        // Inline style wins over Vaadin's own flex defaults — prevents the outer
        // header row from ever wrapping the drawer toggle onto its own line.
        header.getStyle().set("flex-wrap", "nowrap").set("overflow-x", "auto");
    }

    private void clearDrawerItems() {
        drawerItems.forEach(vl -> {
            vl.getChildren().forEach(c -> c.setVisible(false));
            vl.removeAll();
            vl.setVisible(false);
        });
        drawerItems.clear();
    }

    private void addToDrawerVL(final VerticalLayout layout) {
        drawerItems.add(layout);
        addToDrawer(layout);
    }

    private RouterLink newDrawerLink(final String name, final Class<? extends Component> clazz) {
        final RouterLink result = new RouterLink();
        result.setRoute(clazz);
        result.add(new Icon(ICONS.getOrDefault(clazz, VaadinIcon.CIRCLE_THIN)), new Span(name));
        result.addClassName("drawer-link");
        result.getElement().setAttribute("title", name);
        return result;
    }

    private void createDrawer() {
        clearDrawerItems();
        final RouterLink listLink = newDrawerLink("Dashboard", Dashboard.class);

        listLink.setHighlightCondition(HighlightConditions.sameLocation());

        addToDrawerVL(new VerticalLayout(listLink));

        final Predicate<String> roleChecker = VaadinRequest.getCurrent()::isUserInRole;
        getVerticalLayout(roleChecker, Stream.of(
                CameraView.class,
                PrinterView.class,
                BatchPrintView.class,
                AutomationView.class,
                SdCardView.class,
                HistoryView.class,
                LogsView.class,
                MaintenanceView.class,
                SpoolsView.class,
                NotificationSettingsView.class,
                TasmotaSettingsView.class,
                EtsyOrdersView.class,
                EbayOrdersView.class))
                .ifPresent(this::addToDrawerVL);

        final VerticalLayout controls = new VerticalLayout(darkMode, notifications);
        if (SecurityUtils.isLoggedIn()) {
            controls.add(new Button("Logout", new Icon(VaadinIcon.SIGN_OUT), e -> {
                // Invalidate remember-me token and clear browser cookie before ending session
                SecurityUtils.getPrincipal().ifPresent(p -> rememberMeService.removeTokensForUser(p.getName()));
                getUI().ifPresent(ui -> ui.getPage().executeJs(RememberMeService.CLEAR_COOKIE_JS));
                SecurityUtils.logout();
            }));
        }
        controls.addClassName("drawer-controls");
        addToDrawerVL(controls);
    }

    private Optional<VerticalLayout> getVerticalLayout(final Predicate<String> roleChecker, Stream<Class<? extends Component>> stream) {
        final List<RouterLink> list = stream
                .filter(clazz -> MAP.get(clazz).roles.stream().anyMatch(roleChecker))
                .map(clazz -> newDrawerLink(MAP.get(clazz).name(), clazz))
                .collect(Collectors.toList());

        if (list.isEmpty()) {
            return Optional.empty();
        }

        final VerticalLayout result = new VerticalLayout();
        list.forEach(result::add);
        return Optional.of(result);
    }

    private static Map<Class<? extends Component>, AccessRoute> makeEntries(Stream<Class<? extends Component>> stream) {
        return stream
                .collect(Collectors.toMap(Function.identity(), clazz -> {
                    final String name = clazz.getAnnotation(PageTitle.class).value();
                    final Set<String> roles = Arrays.stream(clazz.getAnnotation(RolesAllowed.class).value())
                            .collect(Collectors.toSet());
                    return new AccessRoute(clazz, name, roles);
                }));
    }

    @Override
    public void showRouterLayoutContent(final HasElement content) {
        super.showRouterLayoutContent(content);

        if (content instanceof Dashboard) {
        } else {
            headerContent.add(new RouterLink("Back to Dashboard", Dashboard.class));
        }

        if (content instanceof UpdateHeader updateHeader) {
            updateHeader.updateHeader(headerContent);
        }
    }

    @Override
    public void removeRouterLayoutContent(final HasElement oldContent) {
        super.removeRouterLayoutContent(oldContent);
        headerContent.removeAll();
    }

    private record AccessRoute(Class<? extends Component> component, String name, Set<String> roles) {

    }

}
