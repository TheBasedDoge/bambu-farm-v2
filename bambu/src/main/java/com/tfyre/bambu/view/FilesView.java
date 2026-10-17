package com.tfyre.bambu.view;

import com.tfyre.bambu.SystemRoles;
import com.tfyre.bambu.YesNoCancelDialog;
import com.tfyre.bambu.printer.BambuConst;
import com.tfyre.bambu.printer.LibraryFileInfo;
import com.tfyre.bambu.printer.LibraryService;
import com.tfyre.bambu.printer.LibraryService.Part;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.component.upload.receivers.MultiFileBuffer;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.StreamResource;
import io.quarkus.runtime.configuration.MemorySize;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Files - the print-file library, one row per part.
 * <p>
 * The P1 slice and the H2D slice of a part sit side by side, each labelled with the printer it was sliced for
 * (read from the file), its material, weight and print time. Beside them: which listings map to the part and
 * how it has been printing. Open a row for the files themselves and what can be done with them.
 * <p>
 * This is where files are added and replaced. Replacing keeps the file's name, so mappings and stock stay
 * attached, and keeps the outgoing version so it can be put back.
 */
@Route(value = "files", layout = com.tfyre.bambu.MainLayout.class)
@PageTitle("Files")
@RolesAllowed(SystemRoles.ROLE_ADMIN)
public class FilesView extends VerticalLayout implements NotificationHelper {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);
    private static final String DIM = "var(--lumo-secondary-text-color)";
    private static final String WARN = "var(--lumo-warning-color, #e8a33d)";
    private static final String BAD = "var(--lumo-error-color)";
    private static final String ROW = "56px minmax(0, 2.2fr) minmax(0, 1.5fr) minmax(0, 1.5fr) minmax(110px, auto)";
    private static final List<String> FILTERS = List.of("All files", "Not mapped", "No H2D file", "Needs attention");

    @Inject
    LibraryService library;
    @Inject
    LibraryFileInfo fileInfo;
    @ConfigProperty(name = "quarkus.http.limits.max-body-size")
    MemorySize maxBodySize;

    private final TextField search = new TextField();
    private final Select<String> filter = new Select<>();
    private final ComboBox<String> uploadProject = new ComboBox<>("Save into project");
    private final Span summary = new Span();
    private final Div list = new Div();
    private final Set<String> open = new HashSet<>();
    /** Groups folded shut on this page. */
    private final Set<String> folded = new HashSet<>();
    private java.util.Map<String, String> groupOf = java.util.Map.of();
    /** path|modified → picture. Kept across renders so a click does not make the browser reload every thumbnail. */
    private final java.util.Map<String, StreamResource> thumbnails = new java.util.HashMap<>();
    private boolean built;

    @Override
    protected void onAttach(final AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        if (!built) {
            built = true;
            build();
        }
        render();
    }

    private void build() {
        setPadding(true);
        setSpacing(true);
        setMaxWidth("1200px");

        search.setPlaceholder("Search files, listings, materials");
        search.setClearButtonVisible(true);
        search.setWidth("300px");
        search.setValueChangeMode(ValueChangeMode.TIMEOUT);
        search.addValueChangeListener(e -> render());
        filter.setItems(FILTERS);
        filter.setValue(FILTERS.get(0));
        filter.addValueChangeListener(e -> render());
        summary.getStyle().setColor(DIM);
        final HorizontalLayout top = new HorizontalLayout(new H3("Files"), search, filter, summary);
        top.setAlignItems(FlexComponent.Alignment.BASELINE);
        top.getStyle().set("flex-wrap", "wrap");

        final MultiFileBuffer buffer = new MultiFileBuffer();
        final Upload upload = new Upload(buffer);
        upload.setAcceptedFileTypes(BambuConst.FILE_3MF);
        upload.setMaxFileSize((int) maxBodySize.asLongValue());
        upload.setDropLabel(new Span("Drop sliced .3mf files here (several at once, max %dM each)"
                .formatted(maxBodySize.asLongValue() / 1_000_000)));
        upload.addFileRejectedListener(e -> showError(e.getErrorMessage()));
        upload.addSucceededListener(e -> {
            final String bare = java.nio.file.Path.of(e.getFileName()).getFileName().toString();
            final String project = sanitize(uploadProject.getValue());
            report(library.save(project.isBlank() ? bare : project + "/" + bare, buffer.getFileData(e.getFileName()).getFile().toPath()));
            render();
        });
        uploadProject.setPlaceholder("Library root");
        uploadProject.setAllowCustomValue(true);
        uploadProject.setClearButtonVisible(true);
        uploadProject.setWidth("240px");
        uploadProject.setHelperText("Type a new name to start a project");
        uploadProject.addCustomValueSetListener(e -> {
            final String name = sanitize(e.getDetail());
            if (!name.isBlank()) {
                final List<String> items = new ArrayList<>(library.projects());
                if (!items.contains(name)) {
                    items.add(name);
                }
                uploadProject.setItems(items);
                uploadProject.setValue(name);
            }
        });
        final Span hint = new Span("A file with the same name as one already here replaces it and the old one is kept. "
                + "Name an H2D slice like the P1 file plus _H2D and it joins that part by itself.");
        hint.getStyle().setColor(DIM).set("font-size", "var(--lumo-font-size-s)");
        final HorizontalLayout uploadRow = new HorizontalLayout(uploadProject, upload);
        uploadRow.setAlignItems(FlexComponent.Alignment.END);
        uploadRow.getStyle().set("flex-wrap", "wrap");

        list.setWidthFull();
        list.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "6px");
        // Folded away: it took the top third of the page for something done a few times a week
        final com.vaadin.flow.component.details.Details uploadBox =
                new com.vaadin.flow.component.details.Details("Upload sliced files", new Div(uploadRow, hint));
        uploadBox.setOpened(false);
        add(top, uploadBox, list);
    }

    /** Folder names become paths on disk - the same flat character set Batch Print allows. */
    private static String sanitize(final String raw) {
        return raw == null ? "" : raw.trim().replaceAll("[^a-zA-Z0-9 _-]", "").trim();
    }

    private void report(final LibraryService.Result result) {
        if (result.ok()) {
            showNotification(result.message(), java.time.Duration.ofSeconds(6));
        } else {
            showError(result.message());
        }
    }

    // -------------------------------------------------------------------------
    // the list
    // -------------------------------------------------------------------------
    private boolean needsAttention(final Part p) {
        return attentionReason(p) != null;
    }

    /** Why a row carries the amber bar, in words - or null when it does not. Shown on the row, not left to guess. */
    private String attentionReason(final Part p) {
        if (p.p1() != null && !fileInfo.details(p.p1()).sliced()) {
            return "Not sliced - re-export it from Bambu Studio as a sliced file";
        }
        if (p.h2d() != null && !p.h2dAttached() && p.p1() != null && p.mapped()) {
            return "Has an H2D slice that is not attached yet - open the row to attach it";
        }
        if (p.failed() >= 2 && p.failPct() >= 20) {
            return "Fails often: %d of %d prints".formatted(p.failed(), p.finished() + p.failed());
        }
        return null;
    }

    private boolean matches(final Part p, final String needle) {
        if (needle.isEmpty()) {
            return true;
        }
        final StringBuilder hay = new StringBuilder(p.title()).append(' ').append(p.project()).append(' ')
                .append(p.p1()).append(' ').append(p.h2d()).append(' ');
        if (p.p1() != null) {
            hay.append(fileInfo.details(p.p1()).material()).append(' ');
        }
        p.uses().forEach(u -> hay.append(u.market()).append(' ').append(u.title()).append(' ').append(u.variation()).append(' '));
        return hay.toString().toLowerCase(Locale.ENGLISH).contains(needle);
    }

    private void render() {
        // Keep what is typed in "Save into project": several files dropped together must all land in it, and a
        // project that has no file yet is not in the list the library reports.
        final String chosenProject = uploadProject.getValue();
        final List<String> projectItems = new ArrayList<>(library.projects());
        if (chosenProject != null && !chosenProject.isBlank() && !projectItems.contains(chosenProject)) {
            projectItems.add(chosenProject);
        }
        uploadProject.setItems(projectItems);
        uploadProject.setValue(chosenProject);
        final List<Part> all = library.parts();
        final List<LibraryService.Loose> loose = library.loose();
        final String needle = search.getValue() == null ? "" : search.getValue().strip().toLowerCase(Locale.ENGLISH);
        final int mode = Math.max(0, FILTERS.indexOf(filter.getValue()));

        final long withH2d = all.stream().filter(p -> p.h2d() != null && p.h2dAttached()).count();
        final long unmapped = all.stream().filter(p -> !p.mapped()).count();
        summary.setText("%d part%s · %d with an H2D file · %d not mapped".formatted(all.size(), all.size() == 1 ? "" : "s",
                withH2d, unmapped));

        groupOf = library.groups(all);
        // Group, then project inside the group (loose parts first), then name.
        final List<Part> ordered = new ArrayList<>(all);
        ordered.sort(java.util.Comparator
                .comparing((Part p) -> LibraryService.OTHER_GROUP.equals(groupOf.get(p.id())) ? 1 : 0)
                .thenComparing(p -> groupOf.getOrDefault(p.id(), ""), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Part::project, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Part::title, String.CASE_INSENSITIVE_ORDER));

        list.removeAll();
        String project = null;
        String group = null;
        Div body = null;
        int shown = 0;
        for (final Part p : ordered) {
            final boolean keep = switch (mode) {
                case 1 -> !p.mapped();
                case 2 -> p.h2d() == null;
                case 3 -> needsAttention(p);
                default -> true;
            };
            if (!keep || !matches(p, needle)) {
                continue;
            }
            final String g = groupOf.getOrDefault(p.id(), LibraryService.OTHER_GROUP);
            if (!g.equals(group)) {
                group = g;
                project = null;
                final long inGroup = ordered.stream().filter(o -> g.equals(groupOf.get(o.id()))).count();
                // Each group's rows live in their own box, so folding a group is one show/hide - not a rebuild.
                body = new Div();
                body.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "6px");
                body.setVisible(!folded.contains(g));
                final Component groupHead = groupHeading(g, inGroup, body);
                // Drop a part on a group's name: it leaves its project (if it was in one) and joins the group.
                dropTarget(groupHead, dropped -> dropOnGroup(dropped, g));
                list.add(groupHead, body);
            }
            shown++;
            if (!p.project().equals(project)) {
                project = p.project();
                if (!project.isBlank()) {
                    final Component h = heading(project);
                    h.getElement().getStyle().set("margin-left", "var(--lumo-space-m)");
                    // Drop a part on a project's name to move it into that project.
                    final String intoProject = project;
                    dropTarget(h, dropped -> moveTo(dropped, intoProject));
                    body.add(h);
                }
            }
            final Component row = row(p);
            // One step in under the group, two under a project inside it.
            row.getElement().getStyle().set("margin-left", p.project().isBlank() ? "var(--lumo-space-m)" : "var(--lumo-space-xl)");
            body.add(row);
        }
        if (shown == 0) {
            final Span none = new Span(all.isEmpty() ? "The library is empty. Open Upload sliced files above to start."
                    : "No files match.");
            none.getStyle().setColor(DIM);
            list.add(none);
        }
        if (!loose.isEmpty() && (mode == 0 || mode == 3) && needle.isEmpty()) {
            list.add(heading("Mapped, but not in the library"));
            loose.forEach(l -> list.add(looseRow(l)));
        }
    }

    /** A group's name, how much is in it, and a click to fold it away. */
    private Component groupHeading(final String group, final long parts, final Div body) {
        final Span name = new Span((folded.contains(group) ? "▸ " : "▾ ") + group);
        name.getStyle().set("font-size", "var(--lumo-font-size-l)").set("font-weight", "600");
        final Span count = new Span("%d part%s".formatted(parts, parts == 1 ? "" : "s"));
        count.getStyle().setColor(DIM).set("font-size", "var(--lumo-font-size-s)");
        final Div head = new Div(name, count);
        head.getStyle().set("display", "flex").set("align-items", "baseline").set("gap", "10px").set("cursor", "pointer")
                .set("margin-top", "var(--lumo-space-m)").set("padding-bottom", "2px")
                .set("border-bottom", "1px solid var(--lumo-contrast-10pct)");
        head.addClickListener(e -> {
            if (!folded.remove(group)) {
                folded.add(group);
            }
            body.setVisible(!folded.contains(group));
            name.setText((folded.contains(group) ? "▸ " : "▾ ") + group);
        });
        return head;
    }

    private static Component heading(final String text) {
        final Span head = new Span(text);
        head.getStyle().setColor(DIM).set("font-size", "var(--lumo-font-size-xs)").set("text-transform", "uppercase")
                .set("letter-spacing", "0.09em").set("font-weight", "600").set("margin-top", "var(--lumo-space-s)");
        return head;
    }

    private static Span badge(final String text, final String color, final boolean dashed) {
        final Span b = new Span(text);
        b.getStyle().setColor(color).set("border", "1px %s currentColor".formatted(dashed ? "dashed" : "solid"))
                .set("border-radius", "4px").set("padding", "0 6px").set("font-size", "var(--lumo-font-size-xs)")
                .set("font-weight", "700").set("margin-right", "6px");
        return b;
    }

    private static String duration(final long seconds) {
        return seconds <= 0 ? "" : "%dh %02dm".formatted(seconds / 3600, seconds % 3600 / 60);
    }

    /** One printer type's file for a part: what it is, and what the slice says. */
    private Component slot(final String kind, final String path, final int makes, final boolean attached) {
        final Div slot = new Div();
        slot.getStyle().set("min-width", "0").set("font-size", "var(--lumo-font-size-s)");
        final boolean h2d = "H2D".equals(kind);
        if (path == null) {
            slot.add(new Div(badge(kind, DIM, true)));
            // A dash, not a sentence: most parts have no H2D file, and 35 rows saying so buried the rows that matter
            final Span none = new Span("—");
            none.getElement().setAttribute("title", h2d ? "No H2D file - the H2D won't take this part"
                    : "No P1 file - the P1s won't take this part");
            none.getStyle().setColor(DIM);
            slot.add(new Div(none));
            return slot;
        }
        final LibraryFileInfo.Info info = fileInfo.of(path);
        final LibraryFileInfo.Details d = fileInfo.details(path);
        final boolean wrongKind = !info.family().isEmpty() && !info.family().equalsIgnoreCase(kind);
        final String color = wrongKind || info.family().isEmpty() ? WARN
                : h2d ? "var(--lumo-primary-text-color)" : "var(--lumo-success-text-color)";
        final Span head = new Span(badge(info.family().isEmpty() ? info.badge() : kind, color, false),
                new Span(!d.sliced() ? "not sliced" : makes <= 0 ? "H2D only · one plate per unit" : "makes %d".formatted(makes)));
        slot.add(new Div(head));
        final List<String> facts = new ArrayList<>();
        if (wrongKind) {
            facts.add("sliced for " + info.badge());
        }
        if (h2d && !attached) {
            facts.add("found by name, not attached yet");
        }
        if (!d.material().isBlank()) {
            facts.add(d.material());
        }
        if (d.grams() > 0) {
            facts.add("%.0f g".formatted(d.grams()));
        }
        if (d.seconds() > 0) {
            facts.add(duration(d.seconds()));
        }
        if (!d.sliced()) {
            facts.add("no slice information, so it cannot be checked against a printer");
        }
        final Span line = new Span(String.join(" · ", facts));
        line.getStyle().setColor(wrongKind || (h2d && !attached) ? WARN : DIM).set("overflow-wrap", "anywhere");
        slot.add(new Div(line));
        return slot;
    }

    private Component thumbnail(final String path) {
        final Image img = new Image();
        img.setAlt("");
        img.getStyle().set("width", "56px").set("height", "56px").set("object-fit", "contain").set("border-radius", "6px")
                .set("background", "var(--lumo-contrast-10pct)");
        if (path != null) {
            // Read from the file only when the browser asks for the picture, not for every row on every render.
            img.setSrc(thumbnails.computeIfAbsent(path + "|" + fileInfo.details(path).modifiedMillis(),
                    k -> new StreamResource("plate.png", () -> {
                        final byte[] bytes = fileInfo.thumbnail(path);
                        return new ByteArrayInputStream(bytes == null ? new byte[0] : bytes);
                    })));
        }
        return img;
    }

    private Component row(final Part p) {
        final Div card = new Div();
        card.getStyle().set("background", "var(--lumo-contrast-5pct)").set("border-radius", "10px")
                .set("padding", "10px 12px").set("min-width", "0");
        if (needsAttention(p)) {
            card.getStyle().set("box-shadow", "inset 3px 0 0 " + WARN);
        }

        final LibraryFileInfo.Details d = fileInfo.details(p.id());
        final Span title = new Span(p.title());
        title.getStyle().set("font-weight", "600").set("overflow-wrap", "anywhere");
        final Span sub = new Span(d.sliced()
                ? "%d object%s · %d plate%s".formatted(d.objects(), d.objects() == 1 ? "" : "s", d.plates(), d.plates() == 1 ? "" : "s")
                : "not sliced");
        sub.getStyle().setColor(DIM).set("font-size", "var(--lumo-font-size-s)");
        final Div name = new Div(new Div(title), new Div(sub));
        name.getStyle().set("min-width", "0");
        final String attention = attentionReason(p);
        if (attention != null) {
            final Span why = new Span(attention);
            why.getStyle().setColor(WARN).set("font-size", "var(--lumo-font-size-xs)");
            name.add(new Div(why));
        }

        final Div use = new Div();
        use.getStyle().set("text-align", "right").set("font-size", "var(--lumo-font-size-s)").setColor(DIM);
        final Span listings = new Span(p.mapped()
                ? "%d listing%s".formatted(p.uses().size(), p.uses().size() == 1 ? "" : "s") : "not mapped");
        if (!p.mapped()) {
            listings.getStyle().setColor(WARN);
        }
        final int ran = p.finished() + p.failed();
        final Span record = new Span(ran == 0 ? "never printed" : "%d print%s · %d%% failed".formatted(ran, ran == 1 ? "" : "s", p.failPct()));
        if (p.failed() >= 2 && p.failPct() >= 20) {
            record.getStyle().setColor("var(--lumo-error-text-color)");
        }
        use.add(new Div(listings), new Div(record));

        final Div head = new Div(thumbnail(p.id()), name, slot("P1", p.p1(), 1, true),
                // "Makes N" compares an H2D print with a P1 print of the same part. With no P1 file there is nothing
                // to compare with - the plate is the part - so the row says that instead (0 = no comparison).
                slot("H2D", p.h2d(), p.p1() == null ? 0 : p.h2dMakes(), p.h2dAttached()), use);
        head.getStyle().set("display", "grid").set("grid-template-columns", ROW).set("gap", "6px 14px")
                .set("align-items", "center").set("cursor", "pointer");
        // Opening a row fills in just that row. Rebuilding the whole list for it - reading every file's
        // details and redrawing forty rows - is what made every click feel like a page reload.
        // Drag the row by its top line onto a project or group name. Mouse only: browsers do not send drag
        // events for touch, so on a phone the Project and Group boxes in the opened row do the same job.
        final com.vaadin.flow.component.dnd.DragSource<Div> drag = com.vaadin.flow.component.dnd.DragSource.create(head);
        drag.setDragData(p);
        final Div detailSlot = new Div();
        head.addClickListener(e -> {
            if (open.remove(p.id())) {
                detailSlot.removeAll();
            } else {
                open.add(p.id());
                detailSlot.add(detail(p));
            }
        });
        card.add(head, detailSlot);
        if (open.contains(p.id())) {
            detailSlot.add(detail(p));
        }
        return card;
    }

    // -------------------------------------------------------------------------
    // an opened row
    // -------------------------------------------------------------------------
    private static Div block(final String heading) {
        final Div block = new Div();
        block.getStyle().set("min-width", "0").set("font-size", "var(--lumo-font-size-s)");
        final Span h = new Span(heading);
        h.getStyle().setColor(DIM).set("font-size", "var(--lumo-font-size-xs)").set("text-transform", "uppercase")
                .set("letter-spacing", "0.09em").set("font-weight", "600");
        block.add(new Div(h));
        return block;
    }

    private static Div pair(final String left, final String right) {
        final Span l = new Span(left);
        l.getStyle().set("min-width", "0").set("overflow-wrap", "anywhere");
        final Span r = new Span(right);
        r.getStyle().setColor(DIM).set("white-space", "nowrap");
        final Div row = new Div(l, r);
        row.getStyle().set("display", "flex").set("justify-content", "space-between").set("gap", "10px").set("padding", "2px 0");
        return row;
    }

    private String fileFacts(final String path) {
        final LibraryFileInfo.Details d = fileInfo.details(path);
        return d.bytes() == 0 ? "missing" : "%s · %.1f MB".formatted(
                DAY.format(Instant.ofEpochMilli(d.modifiedMillis()).atZone(ZoneId.systemDefault())), d.bytes() / 1_000_000.0);
    }

    private Component detail(final Part p) {
        final Div files = block("Files");
        if (p.p1() != null) {
            files.add(pair(p.p1(), fileFacts(p.p1())));
            if (library.hasPrevious(p.p1())) {
                files.add(pair("Previous P1 version", "kept"));
            }
        }
        if (p.h2d() != null) {
            files.add(pair(p.h2d(), fileFacts(p.h2d())));
            if (library.hasPrevious(p.h2d())) {
                files.add(pair("Previous H2D version", "kept"));
            }
        }

        final Div uses = block("Used by");
        if (p.uses().isEmpty()) {
            uses.add(pair("No listing maps to this part", ""));
        }
        p.uses().stream().limit(8).forEach(u -> uses.add(pair(
                "%s · %s%s".formatted(u.market(), u.title(), u.variation().isBlank() ? "" : " (" + u.variation() + ")"),
                "%d per unit".formatted(u.piecesPerUnit()))));
        if (p.uses().size() > 8) {
            uses.add(pair("and %d more".formatted(p.uses().size() - 8), ""));
        }

        final Div record = block("Printing record");
        record.add(pair("Finished", String.valueOf(p.finished())), pair("Failed", String.valueOf(p.failed())));
        if (p.lastPrinted() != null) {
            record.add(pair("Last printed", "%s · %s".formatted(DAY.format(p.lastPrinted()), p.lastPrinter())));
        }

        final Div blocks = new Div(files, uses, record);
        blocks.getStyle().set("display", "grid").set("grid-template-columns", "repeat(auto-fit, minmax(min(100%, 260px), 1fr))")
                .set("gap", "14px");

        final HorizontalLayout actions = new HorizontalLayout();
        actions.getStyle().set("flex-wrap", "wrap");
        final String printable = p.p1() != null ? p.p1() : p.h2d();
        if (printable != null) {
            final Button batch = new Button("Batch print", new Icon(VaadinIcon.PRINT), e -> getUI().ifPresent(ui ->
                    ui.navigate("batchprint", com.vaadin.flow.router.QueryParameters.of("file", printable))));
            batch.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_PRIMARY);
            actions.add(batch);
        }
        if (p.p1() != null) {
            actions.add(new Button("Replace P1 file", e -> uploadTo(p.p1(), "Replace " + p.p1())));
        }
        if (p.h2d() != null) {
            actions.add(new Button("Replace H2D file", e -> uploadTo(p.h2d(), "Replace " + p.h2d())));
            if (!p.h2dAttached() && p.p1() != null && p.mapped()) {
                final Button attach = new Button("Attach as the H2D file", e -> {
                    report(library.attachH2d(p.p1(), p.h2d()));
                    render();
                });
                attach.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
                actions.add(attach);
            }
        } else if (p.p1() != null) {
            // Saved under the conventional name whatever the uploaded file is called, so it lands on this part.
            final String folder = p.p1().contains("/") ? p.p1().substring(0, p.p1().lastIndexOf('/') + 1) : "";
            final String target = folder + LibraryFileInfo.stem(p.p1()) + "_H2D.gcode.3mf";
            actions.add(new Button("Add H2D file", e -> uploadTo(target, "H2D slice for " + p.title())));
        }
        if (p.p1() != null && p.h2d() != null && p.h2dAttached()) {
            // A fact about the H2D slice, so it is set here once and applied to every listing that uses the part.
            final com.vaadin.flow.component.textfield.IntegerField makes
                    = new com.vaadin.flow.component.textfield.IntegerField("One H2D print makes");
            makes.setValue(p.h2dMakes());
            makes.setMin(1);
            makes.setMax(12);
            makes.setStepButtonsVisible(true);
            makes.setWidth("170px");
            makes.setTooltipText("2 for a plate that holds a pair. The H2D then takes two of this part from an order "
                    + "in one print, and only when two are waiting.");
            makes.addValueChangeListener(e -> {
                if (e.isFromClient() && e.getValue() != null && e.getValue() != p.h2dMakes()) {
                    report(library.setH2dMakes(p.p1(), e.getValue()));
                    render();
                }
            });
            actions.add(makes);
        }
        for (final String path : new String[]{p.p1(), p.h2d()}) {
            if (path != null && library.hasPrevious(path)) {
                final Button restore = new Button("Restore previous " + (path.equals(p.p1()) ? "P1" : "H2D") + " version", e -> {
                    report(library.restorePrevious(path));
                    render();
                });
                restore.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
                actions.add(restore);
            }
        }
        // Which project the part lives in. Moving re-points the mappings and the stock, so it is safe to do here
        // (unlike moving the file in Explorer, or Batch Print's own "move", which leave the mappings behind).
        final ComboBox<String> projectBox = new ComboBox<>("Project");
        final List<String> projectNames = new ArrayList<>(library.projects());
        if (!p.project().isBlank() && !projectNames.contains(p.project())) {
            projectNames.add(p.project());
        }
        projectBox.setItems(projectNames);
        projectBox.setValue(p.project().isBlank() ? null : p.project());
        projectBox.setPlaceholder("None (library root)");
        projectBox.setClearButtonVisible(true);
        projectBox.setAllowCustomValue(true);
        projectBox.setWidth("230px");
        projectBox.setHelperText("Pick one, or type a new name");
        projectBox.addCustomValueSetListener(e -> moveTo(p, sanitize(e.getDetail())));
        projectBox.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                moveTo(p, e.getValue() == null ? "" : e.getValue());
            }
        });
        actions.add(projectBox);
        // The level above projects. For a part inside a project this moves the whole project.
        final ComboBox<String> groupBox = new ComboBox<>("Group");
        final List<String> groupNames = new ArrayList<>(new java.util.TreeSet<>(groupOf.values()));
        groupNames.remove(LibraryService.OTHER_GROUP);
        groupBox.setItems(groupNames);
        final String current = groupOf.getOrDefault(p.id(), LibraryService.OTHER_GROUP);
        groupBox.setValue(groupNames.contains(current) ? current : null);
        groupBox.setPlaceholder("Automatic");
        groupBox.setClearButtonVisible(true);
        groupBox.setAllowCustomValue(true);
        groupBox.setWidth("200px");
        groupBox.setHelperText(p.project().isBlank() ? "Pick one, or type a new name" : "Applies to the whole project");
        groupBox.addCustomValueSetListener(e -> {
            library.setGroup(p, sanitize(e.getDetail()));
            render();
        });
        groupBox.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                library.setGroup(p, e.getValue());
                render();
            }
        });
        actions.add(groupBox);
        for (final String path : new String[]{p.p1(), p.h2d()}) {
            if (path == null) {
                continue;
            }
            final Button delete = new Button("Delete " + (path.equals(p.p1()) ? "P1" : "H2D") + " file", new Icon(VaadinIcon.TRASH),
                    e -> YesNoCancelDialog.show("Delete %s from the library?\n\nA copy is kept with the previous versions.".formatted(path), ync -> {
                        if (ync.isConfirmed()) {
                            report(library.delete(path));
                            render();
                        }
                    }));
            delete.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR);
            actions.add(delete);
        }
        actions.setAlignItems(FlexComponent.Alignment.END);

        final Div detail = new Div(blocks, actions);
        detail.getStyle().set("border-top", "1px solid var(--lumo-contrast-10pct)").set("margin-top", "10px")
                .set("padding-top", "10px").set("display", "flex").set("flex-direction", "column").set("gap", "10px");
        return detail;
    }

    /**
     * Makes {@code target} somewhere a dragged part can be dropped, and outlines it while one is held over it
     * (inline, so no stylesheet change is needed).
     */
    private void dropTarget(final Component target, final java.util.function.Consumer<Part> onDrop) {
        final com.vaadin.flow.component.dnd.DropTarget<Component> drop = com.vaadin.flow.component.dnd.DropTarget.create(target);
        drop.addDropListener(e -> e.getDragData().filter(Part.class::isInstance).map(Part.class::cast).ifPresent(onDrop));
        target.getElement().executeJs("""
                const el = this;
                const on = () => { el.style.outline = '2px dashed var(--lumo-primary-color)'; el.style.outlineOffset = '3px'; };
                const off = () => { el.style.outline = ''; };
                el.addEventListener('dragenter', on);
                el.addEventListener('dragover', on);
                el.addEventListener('dragleave', off);
                el.addEventListener('drop', off);""");
    }

    /** A part dropped on a group's name: out of its project if it is in one, then filed under that group. */
    private void dropOnGroup(final Part p, final String group) {
        final String label = LibraryService.OTHER_GROUP.equals(group) ? "" : group;
        if (p.project().isBlank()) {
            library.setGroup(p, label);
            render();
            return;
        }
        final List<String> problems = new ArrayList<>();
        for (final String path : new String[]{p.p1(), p.h2d()}) {
            if (path == null) {
                continue;
            }
            final LibraryService.Result r = library.move(path, "");
            if (!r.ok()) {
                problems.add(r.message());
            }
        }
        if (problems.isEmpty()) {
            // Its path is now just the file name; that is what the group is filed under.
            final String id = p.id();
            library.setFileGroup(id.substring(id.lastIndexOf('/') + 1), label);
            showNotification("%s moved out of %s%s".formatted(p.title(), p.project(), label.isBlank() ? "" : " into " + label));
        } else {
            showError(String.join("; ", problems));
        }
        open.clear();
        render();
    }

    /** Moves the part - its P1 file and its H2D file together - into {@code project} (blank = the library root). */
    private void moveTo(final Part p, final String project) {
        if (project.equals(p.project())) {
            return;
        }
        final List<String> problems = new ArrayList<>();
        String done = null;
        for (final String path : new String[]{p.p1(), p.h2d()}) {
            if (path == null) {
                continue;
            }
            final LibraryService.Result r = library.move(path, project);
            if (r.ok()) {
                done = r.message();
            } else {
                problems.add(r.message());
            }
        }
        open.clear();
        if (!problems.isEmpty()) {
            showError(String.join("; ", problems));
        } else if (done != null) {
            showNotification(done, java.time.Duration.ofSeconds(6));
        }
        render();
    }

    /** One file, saved at exactly {@code targetPath} whatever it is called on the computer it comes from. */
    private void uploadTo(final String targetPath, final String title) {
        final Dialog dialog = new Dialog();
        dialog.setHeaderTitle(title);
        final MultiFileBuffer buffer = new MultiFileBuffer();
        final Upload upload = new Upload(buffer);
        upload.setAcceptedFileTypes(BambuConst.FILE_3MF);
        upload.setMaxFiles(1);
        upload.setMaxFileSize((int) maxBodySize.asLongValue());
        upload.addFileRejectedListener(e -> showError(e.getErrorMessage()));
        upload.addSucceededListener(e -> {
            report(library.save(targetPath, buffer.getFileData(e.getFileName()).getFile().toPath()));
            dialog.close();
            render();
        });
        final Span note = new Span("Saved as %s. If a file is there already it is kept as the previous version.".formatted(targetPath));
        note.getStyle().setColor(DIM).set("font-size", "var(--lumo-font-size-s)");
        final VerticalLayout body = new VerticalLayout(note, upload);
        body.setPadding(false);
        dialog.add(body);
        dialog.getFooter().add(new Button("Cancel", e -> dialog.close()));
        dialog.open();
    }

    private Component looseRow(final LibraryService.Loose l) {
        final Div card = new Div();
        card.getStyle().set("background", "var(--lumo-contrast-5pct)").set("border-radius", "10px").set("padding", "10px 12px")
                .set("box-shadow", "inset 3px 0 0 " + (l.sdCard() ? WARN : BAD)).set("display", "flex")
                .set("flex-wrap", "wrap").set("gap", "8px 14px").set("align-items", "center");
        final Span path = new Span(l.path());
        path.getStyle().set("font-weight", "600").set("overflow-wrap", "anywhere");
        final Span why = new Span(l.sdCard()
                ? "On the printers' SD cards only - the app cannot read it, so there is nothing to show or check"
                : "Not in the library - an order for it would fail to queue");
        why.getStyle().setColor(DIM).set("font-size", "var(--lumo-font-size-s)");
        final Span who = new Span("%d listing%s: %s".formatted(l.uses().size(), l.uses().size() == 1 ? "" : "s",
                l.uses().get(0).market() + " · " + l.uses().get(0).title()));
        who.getStyle().setColor(DIM).set("font-size", "var(--lumo-font-size-s)");
        final Div text = new Div(new Div(l.sdCard() ? badge("SD card", WARN, false) : badge("Missing", BAD, false), path),
                new Div(why), new Div(who));
        text.getStyle().set("flex", "1 1 320px").set("min-width", "0");
        card.add(text);
        if (!l.sdCard()) {
            card.add(new Button("Upload it", e -> uploadTo(l.path(), "Upload " + l.path())));
        }
        return card;
    }
}
