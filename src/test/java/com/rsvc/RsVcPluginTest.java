package com.rsvc;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Development launcher only. Never included in the Plugin Hub JAR. */
public final class RsVcPluginTest
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(RsVcPlugin.class);
        RuneLite.main(args);
    }
}
