package com.tfyre.bambu.printer;

import com.tfyre.bambu.printer.OrderTrackingService.OrderProgress;
import com.tfyre.bambu.printer.OrderTrackingService.ProgressView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Order print-progress accounting - the "n/m printed" counters and the one-shot ready-to-ship signal.
 * <p>
 * Getting this wrong is not a UI bug. Too eager and it says an order is ready while a part is still missing, so
 * a package ships short; too reluctant and a finished order sits unshipped because the counter never reached its
 * target. Both have happened here - a single-item order showed 0/2, and a cancelled job left an order stuck at
 * n-1/n forever.
 * <p>
 * Plain JUnit against the counter object, no CDI: the arithmetic is where the bugs live, and file persistence
 * isn't worth booting Quarkus to exercise.
 */
@DisplayName("order progress accounting")
class OrderProgressTest {

    private static OrderProgress order(final int expected, final int printed) {
        final OrderProgress p = new OrderProgress();
        p.addExpected(expected);
        for (int i = 0; i < printed; i++) {
            p.recordPrinted();
        }
        return p;
    }

    @Nested
    @DisplayName("finishing an order")
    class Completion {

        @Test
        @DisplayName("the completing print returns true, and only that one")
        void firesExactlyOnce() {
            final OrderProgress p = order(2, 0);
            assertFalse(p.recordPrinted(), "1 of 2 is not ready to ship");
            assertTrue(p.recordPrinted(), "2 of 2 completes the order");
            // A duplicate finish event - a retry, a restart replaying history - must not re-notify.
            assertFalse(p.recordPrinted(), "already notified; a second 'ready to ship' is noise");
        }

        @Test
        @DisplayName("a single-item order is 1/1, not 0/2")
        void singleItemOrder() {
            // The reported bug: a one-item order displayed 0/2. Whatever counted the jobs, the counter itself
            // must hold exactly what it was given.
            final OrderProgress p = order(1, 0);
            assertEquals(1, p.expected);
            assertEquals(0, p.printed);
            assertTrue(p.recordPrinted());
        }

        @Test
        @DisplayName("nothing expected never completes")
        void zeroExpectedNeverCompletes() {
            // An order with no registered jobs printing something is a bookkeeping error, not a shipment.
            final OrderProgress p = new OrderProgress();
            assertFalse(p.recordPrinted());
        }
    }

    @Nested
    @DisplayName("cancelling a queued job")
    class Cancellation {

        @Test
        @DisplayName("removing the last outstanding job leaves the order complete-able")
        void cancelUnsticksTheOrder() {
            // Without removeExpected this order sits at 1/2 forever and never fires ready-to-ship.
            final OrderProgress p = order(2, 1);
            p.removeExpected(1, false);
            assertEquals(1, p.expected);
            assertTrue(p.allPartsPrinted(), "1 printed of 1 still expected");
        }

        @Test
        @DisplayName("expected can never fall below what has already printed")
        void clampedToPrinted() {
            final OrderProgress p = order(3, 2);
            p.removeExpected(99, false);
            assertEquals(2, p.expected, "you cannot un-print a part");
            assertTrue(p.printed <= p.expected);
        }
    }

    @Nested
    @DisplayName("a part that was given up on")
    class Abandonment {

        @Test
        @DisplayName("blocks ready-to-ship however many other parts finish")
        void abandonedBlocksCompletion() {
            final OrderProgress p = order(3, 0);
            p.removeExpected(1, true);       // one part failed, nothing will reprint it
            assertFalse(p.recordPrinted());
            assertFalse(p.recordPrinted(), "2 of 2 printed, but the order is still short a part");
            assertEquals(1, p.abandoned);
            assertFalse(new ProgressView("1", p.expected, p.printed, null, p.abandoned).complete());
            assertTrue(new ProgressView("1", p.expected, p.printed, null, p.abandoned).needsAttention());
        }

        @Test
        @DisplayName("re-queueing the part clears the block and does not double-count")
        void requeueClearsIt() {
            final OrderProgress p = order(3, 0);
            p.removeExpected(1, true);
            p.recordPrinted();
            p.recordPrinted();
            assertEquals(2, p.expected);

            p.addExpected(1);                 // human re-queues the failed part
            assertEquals(0, p.abandoned, "the re-queued job covers the abandoned one");
            assertEquals(3, p.expected, "and it is expected again - 2 printed of 3");
            assertTrue(p.recordPrinted(), "now it really is ready to ship");
        }

        @Test
        @DisplayName("re-queueing after completion re-opens the order")
        void requeueResetsTheNotifiedFlag() {
            // Reprinting a part of an already-shipped-ready order must be able to notify again, or the second
            // completion is silent.
            final OrderProgress p = order(1, 0);
            assertTrue(p.recordPrinted());
            p.addExpected(1);
            assertTrue(p.recordPrinted());
        }

        @Test
        @DisplayName("abandonment never goes negative")
        void abandonedFloorsAtZero() {
            final OrderProgress p = order(1, 0);
            p.addExpected(5);
            assertEquals(0, p.abandoned);
        }
    }

    @Nested
    @DisplayName("pruning closed orders")
    class Pruning {

        @Test
        @DisplayName("a finished order is kept, an unfinished one is droppable")
        void onlyFinishedSurvives() {
            // allPartsPrinted is what stops a completed order's entry being pruned and then re-notifying if the
            // marketplace ever lists it again. Deliberately ignores abandoned: the entry is kept either way,
            // and completion is judged by ProgressView.
            assertTrue(order(2, 2).allPartsPrinted());
            assertFalse(order(2, 1).allPartsPrinted());
            assertFalse(new OrderProgress().allPartsPrinted(), "nothing expected is not 'finished'");
        }
    }

    @Nested
    @DisplayName("what the UI is told")
    class View {

        @Test
        @DisplayName("complete() needs a target, all of it printed, and nothing abandoned")
        void completeRules() {
            assertTrue(new ProgressView("1", 2, 2, "t", 0).complete());
            assertFalse(new ProgressView("1", 2, 1, "t", 0).complete());
            assertFalse(new ProgressView("1", 0, 0, "t", 0).complete(), "0/0 is not a shipped order");
            assertFalse(new ProgressView("1", 2, 2, "t", 1).complete(), "short a part");
        }
    }
}
