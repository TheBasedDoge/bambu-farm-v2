package com.tfyre.bambu.security;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * "Is this browser logged in to the app?" - asked by nginx ({@code auth_request}) before it passes a camera request
 * through to mediamtx.
 * <p>
 * 2026-11-18 security audit: {@code /_hlsstream/} and {@code /_camerastream/} went straight from the public port to
 * mediamtx, which lets anyone read (and publish) any path - the H2D's live camera played for an anonymous client
 * on the internet. The browser that shows the camera is the app's own page, so it already carries the app's
 * session cookie; nginx forwards that cookie here and lets the stream through only on a 204.
 * <p>
 * 204 for a logged-in session, 401 otherwise. No body, no redirect, nothing cached. nginx marks the location
 * {@code internal}, so it cannot be called from outside except as that sub-request.
 */
@WebServlet(urlPatterns = StreamAuthServlet.PATH)
public class StreamAuthServlet extends HttpServlet {

    public static final String PATH = "/_auth/stream";

    /** Any method: nginx sends the sub-request as GET (proxy_method), but a WHEP POST must not turn into a 405. */
    @Override
    protected void service(final HttpServletRequest req, final HttpServletResponse resp) {
        resp.setHeader("Cache-Control", "no-store");
        resp.setStatus(req.getUserPrincipal() != null ? HttpServletResponse.SC_NO_CONTENT : HttpServletResponse.SC_UNAUTHORIZED);
    }
}
