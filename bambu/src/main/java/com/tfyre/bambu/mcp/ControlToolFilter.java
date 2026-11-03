package com.tfyre.bambu.mcp;

import io.quarkiverse.mcp.server.FilterContext;
import io.quarkiverse.mcp.server.ToolFilter;
import io.quarkiverse.mcp.server.ToolManager;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Hides the control tools from connections that use the read token, so a read-only agent isn't offered tools it
 * can't use. When the caller's access can't be told, the tools stay listed; {@link ControlTools} refuses them anyway.
 */
@Singleton
public class ControlToolFilter implements ToolFilter {

    @Inject
    McpAudit audit;

    @Override
    public boolean test(final ToolManager.ToolInfo tool, final FilterContext context) {
        if (!ControlTools.NAMES.contains(tool.name())) {
            return true;
        }
        return audit.caller().map(a -> a == McpAccessFilter.Access.CONTROL).orElse(true);
    }
}
