package com.tfyre.bambu.view;

import com.tfyre.bambu.printer.BambuConst;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared list/label helpers for the "force this print onto one physical AMS tray" ComboBox&lt;Integer&gt; used on
 * both the SD Card page's print dialog and the Etsy/eBay mapping rows ({@link MappingPartsPanel}), so the two
 * places offer identical slot choices and naming.
 */
final class AmsSlotSupport {

    /** 0-15 covers up to 4 AMS units (A1..D4), plus the external spool tray. */
    static final List<Integer> ITEMS = buildItems();

    private AmsSlotSupport() {
    }

    private static List<Integer> buildItems() {
        final List<Integer> items = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            items.add(i);
        }
        items.add(BambuConst.AMS_TRAY_VIRTUAL);
        return items;
    }

    /** Where a print's 2nd filament can come from: the AMS trays, an AMS HT (one spool each), or the external spool. */
    static final List<Integer> EXTRA_ITEMS = buildExtraItems();

    private static List<Integer> buildExtraItems() {
        final List<Integer> items = new ArrayList<>();
        for (int i = 128; i < 132; i++) {
            items.add(i);
        }
        for (int i = 0; i < 16; i++) {
            items.add(i);
        }
        items.add(BambuConst.AMS_TRAY_VIRTUAL);
        return items;
    }

    static String label(final Integer slot) {
        if (slot == null) {
            return "";
        }
        if (slot >= 128 && slot < 254) {
            return "AMS HT " + (slot - 127);
        }
        if (slot == BambuConst.AMS_TRAY_VIRTUAL) {
            return "External Spool";
        }
        final char unit = (char) ('A' + slot / 4);
        final int tray = slot % 4 + 1;
        return "" + unit + tray;
    }

}
