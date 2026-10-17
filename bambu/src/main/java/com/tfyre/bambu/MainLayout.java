package com.tfyre.bambu;

import com.tfyre.bambu.security.SecurityUtils;
import com.tfyre.bambu.view.batchprint.BatchPrintView;
import com.tfyre.bambu.view.CameraView;
import com.tfyre.bambu.view.HistoryView;
import com.tfyre.bambu.view.InventoryView;
import com.tfyre.bambu.view.LogsView;
import com.tfyre.bambu.view.MaintenanceView;
import com.tfyre.bambu.view.PrinterView;
import com.tfyre.bambu.view.SdCardView;
import com.tfyre.bambu.security.RememberMeService;
import com.tfyre.bambu.view.AutomationView;
import com.tfyre.bambu.view.EbayOrdersView;
import com.tfyre.bambu.view.EtsyOrdersView;
import com.tfyre.bambu.view.NotificationSettingsView;
import com.tfyre.bambu.view.NotificationsView;
import com.tfyre.bambu.view.OverviewView;
import com.tfyre.bambu.view.SpoolsView;
import com.tfyre.bambu.view.TasmotaSettingsView;
import com.tfyre.bambu.view.UpdateHeader;
import com.tfyre.bambu.view.FarmView;
import com.tfyre.bambu.view.FilesView;
import com.tfyre.bambu.view.OrdersView;
import com.tfyre.bambu.view.ShipView;
import com.tfyre.bambu.view.ProfitView;
import com.tfyre.bambu.view.UspsPickupView;
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
            InventoryView.class,
            HistoryView.class,
            ProfitView.class,
            LogsView.class,
            MaintenanceView.class,
            SpoolsView.class,
            NotificationsView.class,
            NotificationSettingsView.class,
            TasmotaSettingsView.class,
            com.tfyre.bambu.view.PrinterConnectionsView.class,
            EtsyOrdersView.class,
            EbayOrdersView.class,
            OrdersView.class,
            FilesView.class,
            UspsPickupView.class
    ));

    /** Sidebar labels that differ from the page's own title. */
    private static final Map<Class<? extends Component>, String> NAV_NAMES = Map.of(
            NotificationSettingsView.class, "Settings");

    private static final Map<Class<? extends Component>, VaadinIcon> ICONS = Map.ofEntries(
            Map.entry(OverviewView.class, VaadinIcon.DESKTOP),
            Map.entry(Dashboard.class, VaadinIcon.DASHBOARD),
            Map.entry(CameraView.class, VaadinIcon.CAMERA),
            Map.entry(PrinterView.class, VaadinIcon.PRINT),
            Map.entry(BatchPrintView.class, VaadinIcon.COPY),
            Map.entry(AutomationView.class, VaadinIcon.AUTOMATION),
            Map.entry(InventoryView.class, VaadinIcon.PACKAGE),
            Map.entry(SdCardView.class, VaadinIcon.ARCHIVE),
            Map.entry(HistoryView.class, VaadinIcon.CLOCK),
            Map.entry(ProfitView.class, VaadinIcon.DOLLAR),
            Map.entry(LogsView.class, VaadinIcon.CLIPBOARD_TEXT),
            Map.entry(MaintenanceView.class, VaadinIcon.WRENCH),
            Map.entry(SpoolsView.class, VaadinIcon.CIRCLE_THIN),
            Map.entry(NotificationsView.class, VaadinIcon.ENVELOPE_O),
            Map.entry(NotificationSettingsView.class, VaadinIcon.COG),
            Map.entry(OrdersView.class, VaadinIcon.CART),
            Map.entry(FilesView.class, VaadinIcon.FOLDER_O),
            Map.entry(TasmotaSettingsView.class, VaadinIcon.PLUG),
            Map.entry(com.tfyre.bambu.view.PrinterConnectionsView.class, VaadinIcon.CONNECT),
            Map.entry(EtsyOrdersView.class, VaadinIcon.SHOP),
            Map.entry(EbayOrdersView.class, VaadinIcon.CART),
            Map.entry(UspsPickupView.class, VaadinIcon.TRUCK)
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
    @Inject
    com.tfyre.bambu.printer.OrderBoardService orderBoard;
    /** Set while the drawer is being built; read by the Orders entry and its group heading. */
    private int ordersNeedingYou;
    @Inject
    com.tfyre.bambu.printer.FarmStatusService farmStatus;
    @Inject
    com.tfyre.bambu.printer.BedClearService bedClear;
    @Inject
    com.tfyre.bambu.printer.DispatchQueueService dispatchQueue;
    /** The one-sentence farm status in the top bar of every page except the Overview, which says it already. */
    private final Div statusChip = new Div();
    private final Span statusChipText = new Span();
    private boolean chromeBuilt;

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
        if (!chromeBuilt) {
            chromeBuilt = true;
            attachEvent.getUI().addBeforeEnterListener(e -> navStarted = System.nanoTime());
            // "Touch-optimized" navbar content is what AppLayout moves to the BOTTOM of the screen on a phone-
            // sized viewport. On a desktop it would sit in the top bar, where the stylesheet hides it.
            addToNavbar(true, buildBottomTabs());
            // The chip is refreshed on every navigation and, between navigations, by this timer - a page left
            // open should not go on saying "next free in 24m" for an hour.
            getElement().executeJs("""
                    const al = this;
                    if (!al.__chipTimer) {
                        al.__chipTimer = setInterval(() => { if (al.$server) { al.$server.refreshStatusChip(); } }, 30000);
                    }""");
        }
        refreshStatusChip();
    }

    /** Farm, Orders, Ship and More along the bottom of a phone-sized screen. */
    private Component buildBottomTabs() {
        final Div tabs = new Div();
        tabs.addClassName("bottom-tabs");
        final Predicate<String> roleChecker = VaadinRequest.getCurrent()::isUserInRole;
        tabs.add(bottomTab("Farm", VaadinIcon.PRINT, FarmView.class));
        if (roleChecker.test(SystemRoles.ROLE_ADMIN)) {
            tabs.add(bottomTab("Orders", VaadinIcon.CART, OrdersView.class));
            tabs.add(bottomTab("Ship", VaadinIcon.PACKAGE, ShipView.class));
        }
        final Button more = new Button("More", new Icon(VaadinIcon.MENU),
                e -> getElement().executeJs("this.drawerOpened = !this.drawerOpened"));
        more.addClassName("bottom-tab");
        more.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_TERTIARY);
        tabs.add(more);
        return tabs;
    }

    private static RouterLink bottomTab(final String label, final VaadinIcon icon, final Class<? extends Component> view) {
        final RouterLink link = new RouterLink();
        link.setRoute(view);
        link.add(new Icon(icon), new Span(label));
        link.addClassName("bottom-tab");
        return link;
    }

    /** Called on every navigation, and by the browser every 30 seconds in between. */
    @com.vaadin.flow.component.ClientCallable
    public void refreshStatusChip() {
        try {
            final com.tfyre.bambu.printer.FarmStatusService.Status status = farmStatus.status();
            statusChipText.setText(status.headline());
            statusChip.getElement().setAttribute("data-tone", status.tone().name().toLowerCase());
            statusChip.getElement().setAttribute("title", status.detail().isBlank() ? "Click for each printer" : status.detail());
        } catch (RuntimeException ex) {
            // The chip is a convenience; a printer in a strange state must not take the top bar down with it.
            statusChipText.setText("");
        }
    }

    private void showStatusDialog() {
        final com.tfyre.bambu.printer.FarmStatusService.Status status = farmStatus.status();
        final com.vaadin.flow.component.dialog.Dialog dialog = new com.vaadin.flow.component.dialog.Dialog();
        dialog.setHeaderTitle(status.headline());
        final VerticalLayout body = new VerticalLayout();
        body.setPadding(false);
        body.setSpacing(false);
        body.getStyle().set("gap", "var(--lumo-space-s)").set("min-width", "min(420px, 80vw)");
        if (!status.detail().isBlank()) {
            final Span detail = new Span(status.detail());
            detail.getStyle().setColor("var(--lumo-secondary-text-color)").set("font-size", "var(--lumo-font-size-s)");
            body.add(detail);
        }
        final boolean admin = VaadinRequest.getCurrent() != null && VaadinRequest.getCurrent().isUserInRole(SystemRoles.ROLE_ADMIN);
        status.printers().forEach(line -> {
            final Span name = new Span(line.name());
            name.getStyle().set("font-weight", "600").set("min-width", "64px");
            final Span text = new Span(line.text());
            text.getStyle().set("flex", "1").set("min-width", "0").set("overflow-wrap", "anywhere").setColor(switch (line.tone()) {
                case BAD -> "var(--lumo-error-text-color)";
                case WARN -> "var(--lumo-warning-text-color, #e8a33d)";
                case OK -> "var(--lumo-body-text-color)";
                case QUIET -> "var(--lumo-secondary-text-color)";
            });
            final HorizontalLayout row = new HorizontalLayout(name, text);
            row.setWidthFull();
            row.setAlignItems(FlexComponent.Alignment.BASELINE);
            if (line.canClear() && admin) {
                final Button clear = new Button("Bed cleared", e -> {
                    if (bedClear.markCleared(line.name(), "Header")) {
                        dispatchQueue.passNow();
                    }
                    dialog.close();
                    refreshStatusChip();
                });
                clear.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_SMALL,
                        com.vaadin.flow.component.button.ButtonVariant.LUMO_PRIMARY);
                row.add(clear);
            }
            body.add(row);
        });
        dialog.add(body);
        dialog.getFooter().add(new Button("Close", e -> dialog.close()));
        dialog.open();
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
        final Span dot = new Span();
        dot.addClassName("header-chip-dot");
        statusChip.removeAll();
        statusChip.add(dot, statusChipText);
        statusChip.addClassName("header-chip");
        if (!chromeBuilt) {
            statusChip.addClickListener(e -> showStatusDialog());
        }
        header.add(drawerToggle, mark, logo, statusChip, headerContent);

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

    /**
     * Optional image files that replace the stock icon for a sidebar entry - the marketplace pages, so they can
     * carry the marketplace's own mark. The files are NOT shipped with the project (they are those companies'
     * trademarks, to be taken from their brand pages): drop them at
     * {@code bambu/src/main/resources/META-INF/resources/icons/} under these names and rebuild. An entry whose
     * file is absent keeps its stock icon, so a missing file is never a broken image.
     */
    private static final Map<Class<? extends Component>, String> ICON_FILES = Map.of(
            EtsyOrdersView.class, "icons/nav-etsy.png",
            EbayOrdersView.class, "icons/nav-ebay.png");

    private static Component drawerIcon(final Class<? extends Component> clazz) {
        final String file = ICON_FILES.get(clazz);
        if (file != null && MainLayout.class.getResource("/META-INF/resources/" + file) != null) {
            final com.vaadin.flow.component.html.Image img = new com.vaadin.flow.component.html.Image("/" + file, "");
            // Same box as the vaadin-icon rule for .drawer-link, inline so no theme rebuild is needed.
            img.getStyle().set("width", "20px").set("height", "20px").set("object-fit", "contain")
                    .set("flex", "none");
            return img;
        }
        return new Icon(ICONS.getOrDefault(clazz, VaadinIcon.CIRCLE_THIN));
    }

    private RouterLink newDrawerLink(final String name, final Class<? extends Component> clazz) {
        final RouterLink result = new RouterLink();
        result.setRoute(clazz);
        result.add(drawerIcon(clazz), new Span(name));
        if (clazz == OrdersView.class && ordersNeedingYou > 0) {
            final Span count = new Span(String.valueOf(ordersNeedingYou));
            count.addClassName("drawer-count");
            count.getElement().setAttribute("title", "Orders that need you: a failed part, or nothing queued");
            result.add(count);
        }
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
    private void setupNavReorder(final VerticalLayout nav, final String group) {
        nav.addClassName("drawer-nav");
        nav.getElement().executeJs("""
                const nav = this;
                // One saved order per group: each group saves only its own links, so a shared key would have
                // every group overwrite the others.
                const KEY = 'bambufarm-nav-order-' + $0;
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
                });""", group);
    }

    /**
     * One titled, foldable section of the sidebar. Which groups are folded is remembered per device, like the
     * order within them. A group is never left folded over the page you are on: the entry for the current page
     * should always be visible.
     */
    private void addGroup(final Predicate<String> roleChecker, final String title, final int count,
            final Stream<Class<? extends Component>> views) {
        getVerticalLayout(roleChecker, views).ifPresent(nav -> {
            final Div head = new Div(new Span(title));
            head.addClassName("drawer-group-head");
            head.getElement().setAttribute("title", "Click to fold or unfold");
            if (count > 0) {
                final Span badge = new Span(String.valueOf(count));
                badge.addClassName("drawer-count");
                head.add(badge);
            }
            final VerticalLayout group = new VerticalLayout(head, nav);
            group.addClassName("drawer-group");
            addToDrawerVL(group);
            setupNavReorder(nav, title);
            group.getElement().executeJs("""
                    const g = this;
                    const KEY = 'bambufarm-nav-folded';
                    const name = $0;
                    const read = () => (localStorage.getItem(KEY) || '').split(',').filter(Boolean);
                    const here = Array.from(g.querySelectorAll('a.drawer-link')).some(a => {
                        try { return a.hasAttribute('highlight') || new URL(a.href).pathname === location.pathname; }
                        catch (e) { return false; }
                    });
                    g.classList.toggle('collapsed', read().includes(name) && !here);
                    if (g.__foldWired) { return; }
                    g.__foldWired = true;
                    g.querySelector('.drawer-group-head').addEventListener('click', () => {
                        const folded = g.classList.toggle('collapsed');
                        const set = new Set(read());
                        if (folded) { set.add(name); } else { set.delete(name); }
                        localStorage.setItem(KEY, Array.from(set).join(','));
                    });""", title);
        });
    }

    /** Puts the menu back in the order the app ships with, for when a drag session gets away from you. */
    private Button newResetOrderButton() {
        final Button reset = new Button("Reset menu order", new Icon(VaadinIcon.REFRESH), e
                -> getElement().executeJs("Object.keys(localStorage).filter(k => k.startsWith('bambufarm-nav-order'))"
                        + ".forEach(k => localStorage.removeItem(k)); location.reload();"));
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
        // Counted once per build of the drawer, for admins only (they are the ones who can see Orders). A
        // problem counting must never cost anyone their menu.
        ordersNeedingYou = 0;
        if (roleChecker.test(SystemRoles.ROLE_ADMIN)) {
            try {
                ordersNeedingYou = orderBoard.needsYou();
            } catch (RuntimeException ex) {
                ordersNeedingYou = 0;
            }
        }
        // The Etsy / eBay order pages and the smart-plug settings are still routes, just no longer menu
        // entries: Orders links to the first two, Settings has a tab for the third.
        addGroup(roleChecker, "Farm", 0, Stream.of(CameraView.class, PrinterView.class, BatchPrintView.class,
                FilesView.class, AutomationView.class, SdCardView.class));
        addGroup(roleChecker, "Orders", ordersNeedingYou, Stream.of(OrdersView.class, InventoryView.class,
                UspsPickupView.class, ProfitView.class));
        addGroup(roleChecker, "Upkeep", 0, Stream.of(MaintenanceView.class, SpoolsView.class, HistoryView.class));
        addGroup(roleChecker, "System", 0, Stream.of(NotificationsView.class, LogsView.class,
                NotificationSettingsView.class));

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
                .map(clazz -> newDrawerLink(NAV_NAMES.getOrDefault(clazz, MAP.get(clazz).name()), clazz))
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
        // The Overview's own top bar already says all of this, larger.
        statusChip.setVisible(!(content instanceof OverviewView));
        refreshStatusChip();

        // No "Back to Dashboard" link any more: the sidebar has Dashboard, and on narrow headers the link wrapped.
        // Pages with no title of their own (their controls live up here) get their name at the start of the row.
        if (content instanceof PrinterView || content instanceof LogsView || content instanceof SdCardView
                || content instanceof HistoryView) {
            final Span pageTitle = new Span(pageTitleOf(content.getClass()));
            pageTitle.addClassName("header-page-title");
            headerContent.add(pageTitle);
        }

        if (content instanceof UpdateHeader updateHeader) {
            updateHeader.updateHeader(headerContent);
        }
        // The view is built and attached by now; time it from the start of the navigation (see RefreshTicker)
        if (navStarted != 0) {
            com.tfyre.bambu.view.RefreshTicker.report(com.tfyre.bambu.view.RefreshTicker.pageName(content), "build",
                    (System.nanoTime() - navStarted) / 1_000_000);
            navStarted = 0;
        }
    }

    /** When the current navigation started, for the slow-page log; 0 = not measuring. */
    private long navStarted;

    /** The @PageTitle of a view class - looked up the hierarchy, because CDI hands out subclasses. */
    private static String pageTitleOf(final Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            final com.vaadin.flow.router.PageTitle t = c.getAnnotation(com.vaadin.flow.router.PageTitle.class);
            if (t != null) {
                return t.value();
            }
        }
        return "";
    }

    @Override
    public void removeRouterLayoutContent(final HasElement oldContent) {
        super.removeRouterLayoutContent(oldContent);
        headerContent.removeAll();
    }

    private record AccessRoute(Class<? extends Component> component, String name, Set<String> roles) {

    }

}
