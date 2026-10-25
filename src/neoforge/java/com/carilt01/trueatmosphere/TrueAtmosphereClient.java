package com.carilt01.trueatmosphere;

import com.carilt01.trueatmosphere.client.AtmosphereSkyClient;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(value = TrueAtmosphere.MODID, dist = Dist.CLIENT)
public class TrueAtmosphereClient {
    public TrueAtmosphereClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        AtmosphereSkyClient.init(container);
    }
}
