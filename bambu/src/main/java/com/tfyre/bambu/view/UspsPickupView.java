package com.tfyre.bambu.view;

import com.tfyre.bambu.SystemRoles;
import com.tfyre.bambu.printer.EbayOrderPollingService;
import com.tfyre.bambu.printer.EtsyOrderPollingService;
import com.tfyre.bambu.printer.OrderTrackingService;
import com.tfyre.bambu.printer.UspsPickupService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * USPS Pickup - book the carrier to collect packages, see what is booked, change or cancel it.
 * <p>
 * The form is short on purpose: a date, how many packages and roughly how heavy. Who and where is entered
 * once under "Pickup address" and re-sent every time. The package count starts at the number of open orders
 * that are fully printed - a starting figure, because the app knows what has been printed, not what has been
 * packed and labelled.
 */
@Route(value = "usps-pickup", layout = com.tfyre.bambu.MainLayout.class)
@PageTitle("USPS Pickup")
@RolesAllowed(SystemRoles.ROLE_ADMIN)
public class UspsPickupView extends VerticalLayout {

    private static final String USPS_FORM = "https://tools.usps.com/schedule-pickup-steps.htm";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.ENGLISH);

    @Inject
    UspsPickupService usps;
    @Inject
    OrderTrackingService tracking;
    @Inject
    EtsyOrderPollingService etsyPolling;
    @Inject
    EbayOrderPollingService ebayPolling;

    private final Div statusBox = new Div();
    private final Div bookedBox = new Div();
    private final DatePicker date = new DatePicker("Pickup date");
    private final IntegerField count = new IntegerField("Packages");
    private final IntegerField weight = new IntegerField("Total weight (lb, estimate)");
    private final Button submit = new Button();
    private final Button cancel = new Button("Cancel pickup", new Icon(VaadinIcon.CLOSE));
    private final Span result = new Span();
    private final Span webHint = new Span();

    private final TextField firstName = new TextField("First name");
    private final TextField lastName = new TextField("Last name");
    private final TextField firm = new TextField("Business name (optional)");
    private final TextField street = new TextField("Street address");
    private final TextField secondary = new TextField("Apt / suite (optional)");
    private final TextField city = new TextField("City");
    private final TextField state = new TextField("State");
    private final TextField zip = new TextField("ZIP code");
    private final TextField phone = new TextField("Phone");
    private final TextField email = new TextField("Email");
    private final ComboBox<String> location = new ComboBox<>("Where the packages will be");
    private final ComboBox<String> packageType = new ComboBox<>("Mail class");
    private final TextField instructions = new TextField("Note for the carrier (optional)");
    private final Span profileResult = new Span();
    private Details profileSection;
    private boolean built;

    @Override
    protected void onAttach(final AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        if (!built) {
            built = true;
            build();
        }
        loadProfile();
        refresh();
    }

    private void build() {
        setMaxWidth("860px");
        add(new H3("USPS Pickup"), statusBox, bookedBox);

        date.setMin(LocalDate.now());
        date.setMax(LocalDate.now().plusDays(30));
        count.setMin(1);
        count.setStepButtonsVisible(true);
        weight.setMin(1);
        weight.setStepButtonsVisible(true);
        submit.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        submit.addClickListener(e -> submit());
        cancel.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
        cancel.addClickListener(e -> com.tfyre.bambu.YesNoCancelDialog.show(
                "Cancel the USPS pickup? USPS cannot restore a cancelled pickup - you would book a new one.", ync -> {
                    if (ync.isConfirmed()) {
                        show(result, usps.cancel());
                        refresh();
                    }
                }));
        final FormLayout form = new FormLayout(date, count, weight);
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1), new FormLayout.ResponsiveStep("520px", 3));
        // The free form on usps.com books the same pickup with an ordinary USPS login - the way through while
        // the app has no access to the Carrier Pickup API, or whenever USPS is refusing requests.
        final Anchor web = new Anchor(USPS_FORM, "");
        web.setTarget("_blank");
        web.add(new Button("Schedule on usps.com", new Icon(VaadinIcon.EXTERNAL_LINK)));
        final HorizontalLayout buttons = new HorizontalLayout(submit, cancel, web);
        buttons.getStyle().set("flex-wrap", "wrap");
        webHint.getStyle().set("font-size", "var(--lumo-font-size-s)").set("color", "var(--lumo-secondary-text-color)");
        add(form, buttons, webHint, result);

        location.setItems(UspsPickupService.LOCATIONS);
        location.setItemLabelGenerator(UspsPickupView::pretty);
        packageType.setItems(UspsPickupService.PACKAGE_TYPES);
        packageType.setItemLabelGenerator(UspsPickupView::pretty);
        packageType.setHelperText("What most of the packages are. Marketplace labels are usually the first one.");
        state.setMaxLength(2);
        state.setHelperText("Two letters");
        final FormLayout profileForm = new FormLayout(firstName, lastName, firm, street, secondary, city, state, zip,
                phone, email, location, packageType, instructions);
        profileForm.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1), new FormLayout.ResponsiveStep("520px", 2));
        final Button save = new Button("Save", e -> {
            saveProfile();
            profileResult.setText("Saved.");
            profileResult.getStyle().setColor("var(--lumo-success-text-color)");
            refresh();
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        final Button check = new Button("Save and check with USPS", e -> {
            saveProfile();
            show(profileResult, usps.checkEligibility());
            refresh();
        });
        final HorizontalLayout profileButtons = new HorizontalLayout(save, check);
        profileButtons.getStyle().set("flex-wrap", "wrap");
        profileSection = new Details("Pickup address", new VerticalLayout(profileForm, profileButtons, profileResult));
        profileSection.setWidthFull();
        add(profileSection);
    }

    private static String pretty(final String apiValue) {
        if (apiValue == null) {
            return "";
        }
        if ("FIRST-CLASS_PACKAGE_SERVICE".equals(apiValue)) {
            return "Ground Advantage / First-Class Package";
        }
        final String s = apiValue.replace('_', ' ').replace('-', ' ').toLowerCase(Locale.ENGLISH);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void loadProfile() {
        final UspsPickupService.Profile p = usps.getProfile();
        firstName.setValue(nz(p.firstName()));
        lastName.setValue(nz(p.lastName()));
        firm.setValue(nz(p.firm()));
        street.setValue(nz(p.streetAddress()));
        secondary.setValue(nz(p.secondaryAddress()));
        city.setValue(nz(p.city()));
        state.setValue(nz(p.state()));
        zip.setValue(nz(p.zip()));
        phone.setValue(nz(p.phone()));
        email.setValue(nz(p.email()));
        location.setValue(UspsPickupService.LOCATIONS.contains(p.packageLocation()) ? p.packageLocation() : "FRONT_DOOR");
        packageType.setValue(UspsPickupService.PACKAGE_TYPES.contains(p.packageType()) ? p.packageType()
                : UspsPickupService.PACKAGE_TYPES.get(0));
        instructions.setValue(nz(p.specialInstructions()));
    }

    private static String nz(final String s) {
        return s == null ? "" : s;
    }

    private void saveProfile() {
        usps.setProfile(new UspsPickupService.Profile(firstName.getValue().strip(), lastName.getValue().strip(),
                firm.getValue().strip(), street.getValue().strip(), secondary.getValue().strip(), city.getValue().strip(),
                state.getValue().strip().toUpperCase(Locale.ENGLISH), zip.getValue().strip(), phone.getValue().strip(),
                email.getValue().strip(), location.getValue(), instructions.getValue().strip(), packageType.getValue()));
    }

    /** Open orders whose every part has been printed - the number of boxes you are probably about to pack. */
    private int readyToShip() {
        final Set<String> etsyOpen = etsyPolling.getReceipts().stream().map(r -> String.valueOf(r.receiptId())).collect(Collectors.toSet());
        final Set<String> ebayOpen = ebayPolling.getOrders().stream().map(o -> o.orderId()).collect(Collectors.toSet());
        return (int) (tracking.progress("etsy").stream().filter(p -> p.complete() && etsyOpen.contains(p.orderId())).count()
                + tracking.progress("ebay").stream().filter(p -> p.complete() && ebayOpen.contains(p.orderId())).count());
    }

    /** Tomorrow, or Monday when tomorrow is a Sunday - today's cutoff has usually passed by the time you pack. */
    private static LocalDate nextPickupDay() {
        LocalDate d = LocalTime.now().isBefore(LocalTime.of(2, 0)) ? LocalDate.now() : LocalDate.now().plusDays(1);
        if (d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.plusDays(1);
        }
        return d;
    }

    private void refresh() {
        statusBox.removeAll();
        final boolean configured = usps.isConfigured();
        final boolean profileOk = usps.getProfile().complete();
        if (!configured) {
            statusBox.add(note("USPS is not connected yet. Create an app at developers.usps.com (Add App gives you a "
                    + "Consumer Key and Secret), add these two lines to data/.env, and restart: "
                    + "bambu.usps.client-id=<Consumer Key>   bambu.usps.client-secret=<Consumer Secret>", true));
        } else if (usps.isTestEnvironment()) {
            statusBox.add(note("Connected to the USPS TEST environment - bookings here are not real and no carrier will come.", true));
        }
        if (configured && !profileOk) {
            statusBox.add(note("Enter the pickup address below before booking.", true));
        }
        if (profileSection != null && !profileOk) {
            profileSection.setOpened(true);
        }

        bookedBox.removeAll();
        final var booked = usps.getBooked();
        if (booked.isPresent()) {
            final UspsPickupService.Booked b = booked.get();
            final Div card = new Div();
            card.getStyle().set("background", "var(--lumo-success-color-10pct)").set("border-radius", "10px")
                    .set("padding", "var(--lumo-space-m)");
            card.add(new H4("Pickup booked for " + day(b.pickupDate())));
            card.add(new Div(new Span("%d package(s), about %d lb · %s · confirmation %s".formatted(b.packageCount(),
                    b.estimatedWeight(), pretty(b.packageType()), b.confirmationNumber()))));
            final Button forget = new Button("I cancelled it on usps.com", e -> {
                usps.forgetBooking();
                refresh();
            });
            forget.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
            card.add(forget);
            bookedBox.add(card);
            date.setValue(safeDate(b.pickupDate()));
            count.setValue(b.packageCount());
            weight.setValue(b.estimatedWeight());
            submit.setText("Change pickup");
            cancel.setVisible(true);
        } else {
            final int ready = readyToShip();
            if (date.getValue() == null || date.getValue().isBefore(LocalDate.now())) {
                date.setValue(nextPickupDay());
            }
            if (count.getValue() == null) {
                count.setValue(Math.max(1, ready));
            }
            if (weight.getValue() == null) {
                // About a pound a box for this shop's parts; it is an estimate USPS uses to plan the route.
                weight.setValue(Math.max(1, count.getValue()));
            }
            count.setHelperText(ready > 0 ? "%d order(s) are fully printed and ready to ship".formatted(ready)
                    : "No orders are marked ready to ship");
            submit.setText("Book pickup");
            cancel.setVisible(false);
        }
        submit.setEnabled(configured && profileOk);
        webHint.setText(booked.isPresent() ? ""
                : "Booking on usps.com instead? Enter %d package(s), about %d lb. A pickup booked there is not shown here."
                        .formatted(count.getValue(), weight.getValue()));
    }

    private void submit() {
        if (date.getValue() == null || count.getValue() == null || weight.getValue() == null) {
            show(result, new UspsPickupService.Result(false, "Pick a date and enter the package count and weight."));
            return;
        }
        final UspsPickupService.Result r = usps.getBooked().isPresent()
                ? usps.update(date.getValue(), count.getValue(), weight.getValue())
                : usps.schedule(date.getValue(), count.getValue(), weight.getValue());
        show(result, r);
        refresh();
    }

    private static void show(final Span target, final UspsPickupService.Result r) {
        target.setText(r.message());
        target.getStyle().setColor(r.ok() ? "var(--lumo-success-text-color)" : "var(--lumo-error-text-color)");
    }

    private static Div note(final String text, final boolean warn) {
        final Div d = new Div(new Span(text));
        d.getStyle().set("padding", "var(--lumo-space-s) var(--lumo-space-m)").set("border-radius", "8px")
                .set("margin-bottom", "var(--lumo-space-s)")
                .set("background", warn ? "var(--lumo-warning-color-10pct, rgba(232,163,61,0.12))" : "var(--lumo-contrast-5pct)");
        return d;
    }

    private static LocalDate safeDate(final String iso) {
        try {
            return LocalDate.parse(iso);
        } catch (RuntimeException ex) {
            return LocalDate.now();
        }
    }

    private static String day(final String iso) {
        try {
            return DAY.format(LocalDate.parse(iso));
        } catch (RuntimeException ex) {
            return iso;
        }
    }
}
