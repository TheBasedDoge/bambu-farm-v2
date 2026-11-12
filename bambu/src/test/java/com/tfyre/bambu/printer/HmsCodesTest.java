package com.tfyre.bambu.printer;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-12: the H2D failed bed leveling twice and the app showed only "HMS 0x03002600" - half the code, looked up in
 * a table with no H2D entries. These pin the full code, Bambu's per-model text and the wiki link.
 */
@DisplayName("HMS and print-error text")
class HmsCodesTest {

    private static final BambuConst.PrinterModel H2D = BambuConst.PrinterModel.H2D;

    @Test
    void fullCodeFromAttrAndCode() {
        assertEquals("0300-2600-0001-0008", HmsCodes.hmsCode(0x03002600L, 0x00010008L));
        assertEquals("0300-4002", HmsCodes.errorCode(0x03004002));
        assertEquals("0700-8006", HmsCodes.errorCode(0x07008006));
    }

    @Test
    void h2dEddyCurrentAlertHasBambusText() {
        final HmsCodes.Hms h = HmsCodes.hms(H2D, 0x03002600L, 0x00010008L);
        assertEquals("0300-2600-0001-0008", h.code());
        assertEquals(1, h.severity());
        assertTrue(h.text().contains("left nozzle"), h.text());
        assertTrue(h.describe().startsWith("HMS 0300-2600-0001-0008: "), h.describe());
        assertEquals("https://e.bambulab.com/index.php?e=0300260000010008&d=094&s=device_hms&lang=en", h.url());
    }

    @Test
    void unknownCodeStillShowsTheFullCode() {
        final HmsCodes.Hms h = HmsCodes.hms(H2D, 0x0FFF0000L, 0x00020001L);
        assertEquals("HMS 0FFF-0000-0002-0001", h.describe());
        final HmsCodes.Hms noModel = HmsCodes.hms(BambuConst.PrinterModel.UNKNOWN, 0x03002600L, 0x00010008L);
        assertEquals("HMS 0300-2600-0001-0008", noModel.describe());
        assertEquals("", noModel.url());
    }

    @Test
    void printErrorsUseTheModelsDatabaseThenTheOldTable() {
        assertTrue(HmsCodes.printError(H2D, 0x03004002).orElseThrow().contains("Bed Leveling"));
        assertTrue(HmsCodes.printError(BambuConst.PrinterModel.P1S, 0x07008011).orElseThrow().contains("ran out"));
        // no database for this model: the built-in xtouch table still answers
        assertTrue(HmsCodes.printError(BambuConst.PrinterModel.UNKNOWN, 0x03008014).isPresent());
        assertFalse(HmsCodes.printError(H2D, 0x0FFF0FFF).isPresent());
        assertEquals("0FFF-0FFF: unknown error", HmsCodes.describeError(H2D, 0x0FFF0FFF));
    }

    @Test
    void parsesBambusRawAnswerToo() throws Exception {
        final String raw = """
                {"result":0,"data":{"device_hms":{"ver":202611121903,"en":[{"ecode":"0300260000010008","intro":"Nozzle hit"},
                {"ecode":"0300260000000000","intro":""}]},"device_error":{"ver":1,"en":[{"ecode":"03004002","intro":"ABL failed"}]}}}""";
        final HmsCodes.Db db = HmsCodes.parse(HmsCodes.mapper().readTree(raw));
        assertEquals("202611121903", db.ver());
        assertEquals(1, db.hms().size());
        assertEquals("ABL failed", db.errors().get("03004002"));
    }

    @Test
    void notificationListsEachAlertWithItsLink() {
        final String lines = NotificationService.hmsLines(List.of(HmsCodes.hms(H2D, 0x03002600L, 0x00010008L)));
        assertTrue(lines.startsWith("\nHMS 0300-2600-0001-0008: "), lines);
        assertTrue(lines.endsWith("\nhttps://e.bambulab.com/index.php?e=0300260000010008&d=094&s=device_hms&lang=en"), lines);
    }
}
