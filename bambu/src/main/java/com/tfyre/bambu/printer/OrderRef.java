package com.tfyre.bambu.printer;

/**
 * Reference from a print job back to the marketplace order it fulfills - carried on queue entries, through
 * print history, and used by {@link OrderTrackingService} to fire the "order fully printed - ready to ship"
 * notification when the last part finishes.
 *
 * @param market  "etsy" or "ebay"
 * @param orderId Etsy receipt id (stringified) or eBay order id
 * @param label   human-readable order label for notifications, e.g. "Etsy order #123 (Jane)"
 */
public record OrderRef(String market, String orderId, String label) {
}
