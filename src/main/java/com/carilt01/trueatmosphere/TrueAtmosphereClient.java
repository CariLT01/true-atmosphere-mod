package com.carilt01.trueatmosphere;

import com.carilt01.trueatmosphere.client.AtmosphereSkyClient;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(value = TrueAtmosphere.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = TrueAtmosphere.MODID, value = Dist.CLIENT)
public class TrueAtmosphereClient {
    public TrueAtmosphereClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        AtmosphereSkyClient.init(container);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {

    }
}
