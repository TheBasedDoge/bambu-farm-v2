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
import com.tfyre.bambu.view.OverviewView;
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
            Map.entry(OverviewView.class, VaadinIcon.DESKTOP),
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
        // no-anim on the first paint: restoring the rail from localStorage must not animate, or every page load
        // starts with the sidebar visibly sliding shut. It's removed on the next frame, so the class is only
        // ever absent for a real user toggle.
        getElement().executeJs("""
                const al = this;
                if (al.hasAttribute('overlay')) {
                    al.drawerOpened = false;
                } else if (localStorage.getItem('bambufarm-rail') === 'on') {
                    al.classList.add('no-anim', 'drawer-rail');
                    window.dispatchEvent(new Event('resize'));
                    requestAnimationFrame(() => requestAnimationFrame(() => al.classList.remove('no-anim')));
                }""");
        drawerToggle.addClickListener(l -> getElement().executeJs("""
                const al = this;
                if (al.hasAttribute('overlay')) {
                    al.drawerOpened = !al.drawerOpened;
                } else {
                    al.classList.toggle('drawer-rail');
                    localStorage.setItem('bambufarm-rail', al.classList.contains('drawer-rail') ? 'on' : 'off');
                    // Twice on purpose. The first lets content start reflowing with the animation so it doesn't
                    // jump at the end; the second re-measures at the final width, which is what anything sizing
                    // itself from the container - grids, camera tiles - actually needs.
                    window.dispatchEvent(new Event('resize'));
                    setTimeout(() => window.dispatchEvent(new Event('resize')), 260);
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

        // The same mark as the browser tab and the installed PWA icon. Relative, not root-absolute: Flow emits
        // a <base href> per route depth, so "favicon.svg" resolves to the app root from /automation too - the
        // convention the dashboard's own images already follow. Deliberately kept on narrow screens, where the
        // title is hidden: it's the only thing left identifying the app.
        final com.vaadin.flow.component.html.Image mark
                = new com.vaadin.flow.component.html.Image("favicon.svg", "Bambu Farm");
        mark.addClassName("header-mark");

        headerContent.addClassName("header-content");
        header.add(drawerToggle, mark, logo, headerContent);

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
        // Identity for the saved menu order. The class name rather than the route path or the label: paths and
        // page titles get reworded, and a rename would silently scramble everyone's saved order instead of
        // simply dropping the one entry that no longer exists.
        result.getElement().setAttribute("data-nav-key", clazz.getSimpleName());
        return result;
    }

    /**
     * Makes the drawer's navigation links drag-to-reorder, remembered per device.
     * <p>
     * Done entirely client-side, like the grid column order and the dashboard card layout: which order you like
     * your menu in is a property of the browser you're sitting at, not of the account, and routing this through
     * the server would buy nothing but latency. The key is the view's class name, so a saved order survives a
     * page-title reword and degrades to "that one item goes last" if a view is ever renamed.
     * <p>
     * The order is re-applied on every attach because Vaadin rebuilds the drawer each time; without that, a
     * navigation would quietly reset the menu you just arranged.
     * <p>
     * HTML5 drag-and-drop is mouse-only - touch fires no drag events at all - so on a phone the menu keeps
     * whatever order you set on a desktop. That's the right way round: the phone drawer is an overlay you open
     * and dismiss, and a long-press reorder there would fight the scroll gesture.
     * <p>
     * <b>One constraint this relies on:</b> the browser moves these nodes without telling Flow, so Flow's
     * server-side child order for {@code nav} no longer matches the DOM. That is safe only because this layout
     * is built once per attach and never mutated afterwards. If anything ever starts adding or removing links
     * after creation, Flow will insert at server-side indices and land them in the wrong place - move the
     * reordering to {@code Component} level at that point rather than patching around it.
     */
    private void setupNavReorder(final VerticalLayout nav) {
        nav.addClassName("drawer-nav");
        nav.getElement().executeJs("""
                const nav = this;
                const KEY = 'bambufarm-nav-order';
                const links = () => Array.from(nav.querySelectorAll('a.drawer-link'));
                const save = () => localStorage.setItem(KEY,
                        links().map(a => a.getAttribute('data-nav-key')).join(','));

                // Apply the saved order. Appending the known keys in order moves them past anything unknown,
                // then the unknown ones are appended after - so a view added by a new release shows up at the
                // bottom rather than being lost or silently reordering everything around it.
                const saved = (localStorage.getItem(KEY) || '').split(',').filter(Boolean);
                if (saved.length) {
                    const byKey = new Map(links().map(a => [a.getAttribute('data-nav-key'), a]));
                    saved.forEach(k => { const a = byKey.get(k); if (a) { nav.appendChild(a); } });
                    links().filter(a => !saved.includes(a.getAttribute('data-nav-key')))
                           .forEach(a => nav.appendChild(a));
                }

                if (nav.__navReorderWired) { return; }
                nav.__navReorderWired = true;

                let dragged = null;
                const clearMarks = () => links().forEach(a =>
                        a.classList.remove('nav-drop-before', 'nav-drop-after'));
                // Which half of the hovered row the pointer is in decides whether the drop lands above or
                // below it. Without that, dragging an item downwards can never place it last.
                const isAfter = (a, e) => {
                    const r = a.getBoundingClientRect();
                    return (e.clientY - r.top) > r.height / 2;
                };

                links().forEach(a => {
                    a.setAttribute('draggable', 'true');
                    a.addEventListener('dragstart', e => {
                        dragged = a;
                        a.classList.add('nav-dragging');
                        e.dataTransfer.effectAllowed = 'move';
                        // Firefox refuses to start a drag unless some data is set.
                        e.dataTransfer.setData('text/plain', a.getAttribute('data-nav-key') || '');
                    });
                    a.addEventListener('dragend', () => {
                        a.classList.remove('nav-dragging');
                        clearMarks();
                        dragged = null;
                    });
                    a.addEventListener('dragover', e => {
                        if (!dragged || dragged === a) { return; }
                        // preventDefault on BOTH dragover and drop. These are anchors: without it the browser
                        // treats the drop as "open this link" and navigates instead of reordering.
                        e.preventDefault();
                        e.dataTransfer.dropEffect = 'move';
                        const after = isAfter(a, e);
                        a.classList.toggle('nav-drop-before', !after);
                        a.classList.toggle('nav-drop-after', after);
                    });
                    a.addEventListener('dragleave', () =>
                            a.classList.remove('nav-drop-before', 'nav-drop-after'));
                    a.addEventListener('drop', e => {
                        e.preventDefault();
                        e.stopPropagation();
                        if (!dragged || dragged === a) { clearMarks(); return; }
                        nav.insertBefore(dragged, isAfter(a, e) ? a.nextSibling : a);
                        clearMarks();
                        save();
                    });
                });""");
    }

    /** Puts the menu back in the order the app ships with, for when a drag session gets away from you. */
    private Button newResetOrderButton() {
        final Button reset = new Button("Reset menu order", new Icon(VaadinIcon.REFRESH), e
                -> getElement().executeJs("localStorage.removeItem('bambufarm-nav-order'); location.reload();"));
        reset.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_TERTIARY,
                com.vaadin.flow.component.button.ButtonVariant.LUMO_SMALL);
        reset.setTooltipText("Drag the menu items to reorder them; this undoes it");
        reset.addClassName("drawer-reset-order");
        return reset;
    }

    private void createDrawer() {
        clearDrawerItems();
        final RouterLink listLink = newDrawerLink("Dashboard", Dashboard.class);

        listLink.setHighlightCondition(HighlightConditions.sameLocation());

        // Overview and Dashboard are pinned at the top and deliberately left OUT of the drag-to-reorder list
        // below. They are the two "where am I" entries - Overview is what a wall display is left on, Dashboard is
        // where you land - and a menu whose first item moves is a menu you have to read before using.
        final RouterLink wallLink = newDrawerLink("Overview", OverviewView.class);
        wallLink.getElement().setAttribute("title", "Overview - the at-a-glance screen, for leaving up on a monitor");
        addToDrawerVL(new VerticalLayout(wallLink, listLink));

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
                .ifPresent(nav -> {
                    addToDrawerVL(nav);
                    setupNavReorder(nav);
                });

        final VerticalLayout controls = new VerticalLayout(darkMode, notifications, newResetOrderButton());
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
