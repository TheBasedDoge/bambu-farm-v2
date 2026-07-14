package com.tfyre.bambu.view.dashboard;

import com.tfyre.bambu.BambuConfig;
import com.tfyre.bambu.printer.BambuConst;
import com.tfyre.bambu.printer.BambuPrinter;
import com.tfyre.bambu.printer.EbayOrderPollingService;
import com.tfyre.bambu.printer.EtsyOrderPollingService;
import com.tfyre.bambu.MainLayout;
import com.tfyre.bambu.SystemRoles;
import com.tfyre.bambu.security.SecurityUtils;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasComponents;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dnd.DragSource;
import com.vaadin.flow.component.dnd.DropTarget;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.theme.lumo.LumoUtility;
import jakarta.inject.Inject;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import com.tfyre.bambu.printer.BambuPrinters;
import com.tfyre.bambu.view.EbayOrdersView;
import com.tfyre.bambu.view.EtsyOrdersView;
import com.tfyre.bambu.view.PrinterView;
import com.tfyre.bambu.view.PushDiv;
import com.tfyre.bambu.view.UpdateHeader;
import com.tfyre.bambu.view.ViewHelper;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.inject.Instance;

/**
 *
 * @author Francois Steyn - (fsteyn@tfyre.co.za)
 */
@Route(value = "", layout = MainLayout.class)
@PageTitle("Dashboard")
@RolesAllowed({ SystemRoles.ROLE_ADMIN, SystemRoles.ROLE_NORMAL })
public class Dashboard extends PushDiv implements UpdateHeader, ViewHelper {

    private static final String JS_ORDER_KEY = "bambufarm-dashboard-order";
    private static final String JS_SIZE_KEY = "bambufarm-card-sizes";
    private static final String JS_SORT_KEY = "bambufarm-dashboard-sort";
    private static final String JS_VIEW_KEY = "bambufarm-dashboard-mode";
    private static final String JS_COLS_KEY = "bambufarm-dashboard-cols";

    private static final List<String> COL_OPTIONS = List.of("Auto", "1", "2", "3", "4", "5", "6");
    private static final String ATTR_PRINTER = "data-printer";

    private static final String SORT_CUSTOM = "Custom";
    private static final String SORT_NAME = "Name";
    private static final String SORT_STATUS = "Status";
    private static final String SORT_NEXT = "Next Available";
    private static final List<String> SORTS = List.of(SORT_CUSTOM, SORT_NAME, SORT_STATUS, SORT_NEXT);

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    @Inject
    BambuPrinters printers;
    @Inject
    Instance<DashboardPrinter> cardInstance;
    @Inject
    BambuConfig config;
    @Inject
    EtsyOrderPollingService etsyPolling;
    @Inject
    EbayOrderPollingService ebayPolling;

    private final Div overview = new Div();
    private final Select<String> sortSelect = new Select<>();
    private final Select<String> colSelect = new Select<>();
    private final Map<Component, BambuPrinter> cardMap = new LinkedHashMap<>();
    private List<BambuPrinter> overviewPrinters = List.of();
    private String overviewText = "";
    /**
     * Cached on attach (request thread). MUST NOT be checked per-tick via SecurityUtils inside ui.access():
     * VaadinServletRequest.getCurrent() is null on the scheduler thread, so the check silently returns false
     * there - which made the Etsy/eBay order chips vanish on the first background refresh.
     */
    private boolean isAdmin;
    /** The currently active sort mode — re-applied every tick for live sorts (Status / Next Available). */
    private String currentSort = SORT_CUSTOM;

    @Override
    protected void onAttach(final AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        final UI ui = attachEvent.getUI();
        isAdmin = SecurityUtils.userHasAccess(SystemRoles.ROLE_ADMIN);
        overviewPrinters = printers.getPrinters().stream()
                .sorted(Comparator.comparing(BambuPrinter::getName))
                .toList();
        getElement().executeJs("return localStorage.getItem($0) || 'cards'", JS_VIEW_KEY)
                .then(String.class, mode -> {
                    if ("compact".equals(mode)) {
                        buildCompact(ui);
                    } else {
                        buildCards(ui);
                    }
                });
    }

    private void buildCards(final UI ui) {
        final List<Runnable> runnables = new ArrayList<>();
        addClassName("dashboard-view");

        overview.addClassName("dashboard-overview");
        add(overview);

        cardMap.clear();
        overviewPrinters.stream()
                .map(printer -> handlePrinter(printer, runnables::add))
                .forEach(this::add);
        getElement().executeJs("return localStorage.getItem($0) || ''", JS_SORT_KEY)
                .then(String.class, saved -> {
                    final String mode = SORTS.contains(saved) ? saved : SORT_CUSTOM;
                    sortSelect.setValue(mode);
                    if (SORT_CUSTOM.equals(mode)) {
                        applySavedOrder();
                    } else {
                        applySort(mode);
                    }
                });
        applySavedSizes();
        updateOverview();
        createFuture(() -> ui.access(() -> {
            runnables.forEach(Runnable::run);
            updateOverview();
            reapplyLiveSort();
        }), config.refreshInterval());
    }

    private String currentFile(final BambuPrinter printer) {
        return printer.getStatus()
                .filter(m -> m.message().hasPrint() && m.message().getPrint().hasSubtaskName())
                .map(m -> m.message().getPrint().getSubtaskName())
                .orElse("");
    }

    private void buildCompact(final UI ui) {
        addClassName("dashboard-compact");
        overview.addClassName("dashboard-overview");
        add(overview);

        final Grid<BambuPrinter> grid = new Grid<>();
        grid.addClassName("compact-grid");
        grid.addComponentColumn(p -> {
            final RouterLink link = new RouterLink();
            link.setRoute(PrinterView.class, p.getName());
            link.add(new Span(p.getName()));
            return link;
        }).setHeader("Name").setFlexGrow(2);
        grid.addComponentColumn(p -> {
            final BambuConst.GCodeState state = p.getGCodeState();
            final Span result = new Span(state.getDescription());
            if (hasError(p)) {
                result.addClassName(LumoUtility.TextColor.ERROR);
            } else if (state.isPrinting()) {
                result.addClassName(LumoUtility.TextColor.PRIMARY);
            } else if (state.isReady()) {
                result.addClassName(LumoUtility.TextColor.SUCCESS);
            } else {
                result.addClassName(LumoUtility.TextColor.SECONDARY);
            }
            return result;
        }).setHeader("Status").setFlexGrow(1);
        grid.addColumn(this::currentFile).setHeader("File").setFlexGrow(3);
        grid.addComponentColumn(p -> {
            final int pct = percent(p).orElse(0);
            final boolean printing = p.getGCodeState().isPrinting() || p.getGCodeState() == BambuConst.GCodeState.PAUSE;
            if (!printing) {
                return new Span("--");
            }
            final ProgressBar bar = new ProgressBar(0, 100, Math.min(pct, 100));
            bar.setWidth("120px");
            final HorizontalLayout result = new HorizontalLayout(bar, new Span("%d%%".formatted(pct)));
            result.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.CENTER);
            return result;
        }).setHeader("Progress").setFlexGrow(2);
        grid.addColumn(p -> {
            final boolean printing = p.getGCodeState().isPrinting() || p.getGCodeState() == BambuConst.GCodeState.PAUSE;
            if (!printing) {
                return "--";
            }
            return remainingMinutes(p)
                    .map(m -> "%s (~%s)".formatted(compactTime(m), HHMM.format(LocalTime.now().plusMinutes(m))))
                    .orElse("--");
        }).setHeader("Remaining / ETA").setFlexGrow(2);
        grid.getColumns().forEach(c -> c.setResizable(true));
        grid.setAllRowsVisible(true);
        grid.setItems(overviewPrinters);
        add(grid);

        updateOverview();
        createFuture(() -> ui.access(() -> {
            grid.getDataProvider().refreshAll();
            updateOverview();
        }), config.refreshInterval());
    }

    private void toggleView() {
        getElement().executeJs("""
                const v = localStorage.getItem($0) === 'compact' ? 'cards' : 'compact';
                localStorage.setItem($0, v);
                location.reload();""", JS_VIEW_KEY);
    }

    private Component handlePrinter(final BambuPrinter printer, final Consumer<Runnable> consumer) {
        final DashboardPrinter card = cardInstance.get();
        consumer.accept(card::update);
        final Component result = card.build(printer, true);
        result.getElement().setAttribute(ATTR_PRINTER, printer.getName());
        setupDragDrop(card, result);
        cardMap.put(result, printer);
        return result;
    }

    private void setupDragDrop(final DashboardPrinter card, final Component component) {
        final DragSource<? extends Component> drag = DragSource.create(card.getDragHandle());
        drag.setDragData(component);
        final DropTarget<Component> drop = DropTarget.create(component);
        drop.addDropListener(e -> e.getDragData().ifPresent(data -> {
            if (data instanceof Component src && src != component) {
                moveCard(src, component);
            }
        }));
    }

    private List<Component> getCards() {
        return getChildren().filter(cardMap::containsKey).toList();
    }

    private void moveCard(final Component src, final Component target) {
        final List<Component> cards = new ArrayList<>(getCards());
        if (!cards.contains(src) || !cards.contains(target)) {
            return;
        }
        cards.remove(src);
        cards.add(cards.indexOf(target), src);
        cards.forEach(c -> getElement().appendChild(c.getElement()));
        // manual ordering switches sorting back to custom — stops live re-sort
        currentSort = SORT_CUSTOM;
        sortSelect.setValue(SORT_CUSTOM);
        getElement().executeJs("localStorage.setItem($0, $1)", JS_SORT_KEY, SORT_CUSTOM);
        saveOrder();
    }

    private void saveOrder() {
        final String order = getCards().stream()
                .map(c -> c.getElement().getAttribute(ATTR_PRINTER))
                .filter(Objects::nonNull)
                .collect(Collectors.joining(","));
        getElement().executeJs("localStorage.setItem($0, $1)", JS_ORDER_KEY, order);
    }

    private void applySavedOrder() {
        getElement().executeJs("return localStorage.getItem($0) || ''", JS_ORDER_KEY)
                .then(String.class, saved -> {
                    if (saved == null || saved.isBlank()) {
                        return;
                    }
                    final List<String> order = List.of(saved.split(","));
                    final List<Component> cards = new ArrayList<>(getCards());
                    cards.sort(Comparator.comparingInt(c -> {
                        final int i = order.indexOf(c.getElement().getAttribute(ATTR_PRINTER));
                        return i == -1 ? Integer.MAX_VALUE : i;
                    }));
                    cards.forEach(c -> getElement().appendChild(c.getElement()));
                });
    }

    private boolean hasError(final BambuPrinter printer) {
        // FAILED alone is NOT an error condition: it just means the last print was cancelled/failed, and the
        // state only clears when the next print starts - the printer is fully usable (and auto-start treats it
        // as ready). Only an active non-zero print-error code counts as a real error.
        return printer.getPrintError() != 0;
    }

    private int statusRank(final BambuPrinter printer) {
        final BambuConst.GCodeState state = printer.getGCodeState();
        if (hasError(printer)) {
            return 3;
        }
        if (state.isPrinting()) {
            return 0;
        }
        if (state == BambuConst.GCodeState.PAUSE) {
            return 1;
        }
        if (state.isReady()) {
            return 2;
        }
        return 4;
    }

    private Optional<Integer> remainingMinutes(final BambuPrinter printer) {
        return printer.getStatus()
                .filter(m -> m.message().hasPrint() && m.message().getPrint().hasMcRemainingTime())
                .map(m -> m.message().getPrint().getMcRemainingTime());
    }

    private int nextAvailableRank(final BambuPrinter printer) {
        final BambuConst.GCodeState state = printer.getGCodeState();
        if (hasError(printer)) {
            return Integer.MAX_VALUE;
        }
        if (state.isReady()) {
            return 0;
        }
        if (state.isPrinting() || state == BambuConst.GCodeState.PAUSE) {
            return 1 + remainingMinutes(printer).orElse(Integer.MAX_VALUE - 2);
        }
        return Integer.MAX_VALUE;
    }

    private void applySort(final String mode) {
        final Comparator<Component> comparator = switch (mode) {
            case SORT_NAME ->
                Comparator.comparing(c -> cardMap.get(c).getName(), String.CASE_INSENSITIVE_ORDER);
            case SORT_STATUS ->
                Comparator.<Component>comparingInt(c -> statusRank(cardMap.get(c)))
                        .thenComparingInt(c -> nextAvailableRank(cardMap.get(c)))
                        .thenComparing(c -> cardMap.get(c).getName(), String.CASE_INSENSITIVE_ORDER);
            case SORT_NEXT ->
                Comparator.<Component>comparingInt(c -> nextAvailableRank(cardMap.get(c)))
                        .thenComparing(c -> cardMap.get(c).getName(), String.CASE_INSENSITIVE_ORDER);
            default ->
                null;
        };
        if (comparator == null) {
            return;
        }
        currentSort = mode;
        final List<Component> before = new ArrayList<>(getCards());
        final List<Component> cards = new ArrayList<>(before);
        cards.sort(comparator);
        // Only touch the DOM if the order actually changed — re-appending elements
        // that are already in the right place resets the page's scroll position.
        if (!cards.equals(before)) {
            cards.forEach(c -> getElement().appendChild(c.getElement()));
        }
    }

    /** Re-applies live sorts (Status / Next Available) so card order stays current as printer states change. */
    private void reapplyLiveSort() {
        if (!SORT_CUSTOM.equals(currentSort) && !SORT_NAME.equals(currentSort)) {
            applySort(currentSort);
        }
    }

    private String compactTime(final int minutes) {
        final int h = minutes / 60;
        final int m = minutes % 60;
        return h > 0 ? "%dh %dm".formatted(h, m) : "%dm".formatted(m);
    }

    private Optional<Integer> percent(final BambuPrinter printer) {
        return printer.getStatus()
                .filter(m -> m.message().hasPrint() && m.message().getPrint().hasMcPercent())
                .map(m -> m.message().getPrint().getMcPercent());
    }

    private Span statusItem(final String dotClass, final String text) {
        final Span dot = new Span();
        dot.addClassName("dot");
        dot.addClassName(dotClass);
        final Span result = new Span(dot, new Span(text));
        result.addClassName("ov-item");
        return result;
    }

    private void updateOverview() {
        int printing = 0;
        int available = 0;
        int offline = 0;
        final List<String> errors = new ArrayList<>();
        BambuPrinter next = null;
        int nextRank = Integer.MAX_VALUE;
        for (final BambuPrinter printer : overviewPrinters) {
            final BambuConst.GCodeState state = printer.getGCodeState();
            if (state == BambuConst.GCodeState.OFFLINE || state == BambuConst.GCodeState.UNKNOWN) {
                offline++;
            } else if (hasError(printer)) {
                errors.add(printer.getName());
            } else if (state.isPrinting() || state == BambuConst.GCodeState.PAUSE) {
                printing++;
            } else if (state.isReady()) {
                available++;
            } else {
                offline++;
            }
            final int rank = nextAvailableRank(printer);
            if (rank < nextRank) {
                nextRank = rank;
                next = printer;
            }
        }
        final String nextText;
        if (next == null || nextRank == Integer.MAX_VALUE) {
            nextText = "No printers available";
        } else if (nextRank == 0) {
            nextText = "Next available: %s (now)".formatted(next.getName());
        } else if (nextRank >= Integer.MAX_VALUE - 1) {
            nextText = "Next available: %s".formatted(next.getName());
        } else {
            final int minutes = nextRank - 1;
            nextText = "Next available: %s %s %s (~%s)".formatted(next.getName(),
                    percent(next).map("%d%% •"::formatted).orElse("in"),
                    compactTime(minutes), HHMM.format(LocalTime.now().plusMinutes(minutes)));
        }
        // Open marketplace orders (already excludes dismissed ones) - a quick "anything outstanding?" glance.
        // Admin-only, matching the Sales Orders pages the chips link to. isAdmin is cached on attach - see field doc.
        final int etsyOrders = isAdmin ? etsyPolling.getReceipts().size() : 0;
        final int ebayOrders = isAdmin ? ebayPolling.getOrders().size() : 0;
        final String key = "%d|%d|%d|%s|%s|%d|%d".formatted(printing, available, offline, errors, nextText, etsyOrders, ebayOrders);
        if (overviewText.equals(key)) {
            return;
        }
        overviewText = key;
        overview.removeAll();
        overview.add(statusItem("printing", "%d printing".formatted(printing)));
        overview.add(statusItem("available", "%d available".formatted(available)));
        if (offline > 0) {
            overview.add(statusItem("offline", "%d offline".formatted(offline)));
        }
        if (!errors.isEmpty()) {
            final Span error = statusItem("error", "%d error%s: %s".formatted(
                    errors.size(), errors.size() == 1 ? "" : "s", String.join(", ", errors)));
            error.addClassName("ov-error");
            overview.add(error);
        }
        final Span nextSpan = new Span(nextText);
        nextSpan.addClassName("next");
        overview.add(nextSpan);
        if (etsyOrders > 0) {
            overview.add(orderItem("Etsy %d".formatted(etsyOrders),
                    "%d open Etsy order%s - click to view".formatted(etsyOrders, etsyOrders == 1 ? "" : "s"),
                    EtsyOrdersView.class));
        }
        if (ebayOrders > 0) {
            overview.add(orderItem("eBay %d".formatted(ebayOrders),
                    "%d open eBay order%s - click to view".formatted(ebayOrders, ebayOrders == 1 ? "" : "s"),
                    EbayOrdersView.class));
        }
    }

    /** Overview chip for open marketplace orders - clickable, jumps to the matching Sales Orders page. */
    private Span orderItem(final String text, final String tooltip, final Class<? extends Component> target) {
        final Span item = statusItem("orders", text);
        item.setTitle(tooltip);
        item.getStyle().setCursor("pointer");
        item.addClickListener(e -> UI.getCurrent().navigate(target));
        return item;
    }

    private void applySavedSizes() {
        getElement().executeJs("""
                const KEY = $0;
                const root = this;
                const saved = JSON.parse(localStorage.getItem(KEY) || '{}');
                const setSpan = (c, n) => {
                    c.classList.remove('bspan-2', 'bspan-3', 'bspan-4', 'bspan-5', 'bspan-6');
                    if (n >= 2) { c.classList.add('bspan-' + n); }
                };
                root.querySelectorAll('.dashboard-printer[data-printer]').forEach(c => {
                    const name = c.getAttribute('data-printer');
                    const n = saved[name];
                    if (typeof n === 'number') { setSpan(c, Math.max(1, Math.min(6, n))); }
                    c.addEventListener('mouseup', () => {
                        if (!c.style.width) { return; }
                        const styles = getComputedStyle(root);
                        const cols = styles.gridTemplateColumns.split(' ').length;
                        const gap = parseFloat(styles.columnGap) || 0;
                        const pad = (parseFloat(styles.paddingLeft) || 0) + (parseFloat(styles.paddingRight) || 0);
                        const cell = (root.clientWidth - pad - gap * (cols - 1)) / cols;
                        const span = Math.max(1, Math.min(6, Math.min(cols,
                                Math.round((parseFloat(c.style.width) + gap) / (cell + gap)))));
                        c.style.width = '';
                        c.style.height = '';
                        setSpan(c, span);
                        const data = JSON.parse(localStorage.getItem(KEY) || '{}');
                        data[name] = span;
                        localStorage.setItem(KEY, JSON.stringify(data));
                    });
                });""", JS_SIZE_KEY);
    }

    private void applyDashboardCols(final String cols) {
        if (cols == null || "Auto".equals(cols)) {
            getElement().executeJs("""
                    this.classList.remove('dashboard-view--fixed-cols');
                    this.style.removeProperty('--dash-cols');
                    """);
        } else {
            try {
                final int n = Integer.parseInt(cols.trim());
                getElement().executeJs("""
                        this.style.setProperty('--dash-cols', $0);
                        this.classList.add('dashboard-view--fixed-cols');
                        """, String.valueOf(n));
            } catch (NumberFormatException ex) {
                // ignore
            }
        }
    }

    private void resetLayout() {
        getElement().executeJs("""
                localStorage.removeItem($0);
                localStorage.removeItem($1);
                localStorage.removeItem($2);
                localStorage.removeItem($3);
                location.reload();""",
                JS_ORDER_KEY, JS_SIZE_KEY, JS_SORT_KEY, JS_COLS_KEY);
    }

    private void commandLights(final BambuConst.LightMode mode) {
        printers.getPrinters().forEach(p -> p.commandLight(mode));
    }

    private Button headerButton(final String text, final VaadinIcon icon, final Runnable action) {
        final Button result = new Button(new Icon(icon), l -> action.run());
        final Span label = new Span(text);
        label.addClassName("btn-label");
        result.getElement().appendChild(label.getElement());
        result.setTooltipText(text);
        return result;
    }

    @Override
    public void updateHeader(final HasComponents component) {
        sortSelect.setItems(SORTS);
        sortSelect.setValue(SORT_CUSTOM);
        sortSelect.setWidth("150px");
        sortSelect.setTooltipText("Sort printer cards");
        sortSelect.addValueChangeListener(l -> {
            if (!l.isFromClient()) {
                return;
            }
            getElement().executeJs("localStorage.setItem($0, $1)", JS_SORT_KEY, l.getValue());
            if (SORT_CUSTOM.equals(l.getValue())) {
                applySavedOrder();
            } else {
                applySort(l.getValue());
            }
        });

        colSelect.setItems(COL_OPTIONS);
        colSelect.setValue("Auto");
        colSelect.setTooltipText("Number of columns");
        colSelect.getStyle().setWidth("80px");
        getElement().executeJs("return localStorage.getItem($0) || 'Auto';", JS_COLS_KEY)
                .then(String.class, saved -> {
                    final String v = COL_OPTIONS.contains(saved) ? saved : "Auto";
                    colSelect.setValue(v);
                    applyDashboardCols(v);
                });
        colSelect.addValueChangeListener(e -> {
            if (!e.isFromClient()) {
                return;
            }
            final String v = e.getValue() != null ? e.getValue() : "Auto";
            applyDashboardCols(v);
            getElement().executeJs("localStorage.setItem($0, $1);", JS_COLS_KEY, v);
        });

        component.add(colSelect, sortSelect);
        if (!SecurityUtils.userHasAccess(SystemRoles.ROLE_ADMIN)) {
            return;
        }
        component.add(
                headerButton("Lights On", VaadinIcon.LIGHTBULB, () -> commandLights(BambuConst.LightMode.ON)),
                headerButton("Lights Off", VaadinIcon.MOON_O, () -> commandLights(BambuConst.LightMode.OFF)),
                headerButton("Toggle View", VaadinIcon.TABLE, this::toggleView),
                headerButton("Reset Layout", VaadinIcon.GRID_SMALL, this::resetLayout));
    }

}
