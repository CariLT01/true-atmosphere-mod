package com.carilt01.trueatmosphere;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.DoubleValue DISPLAY_EXPOSURE = BUILDER
            .comment("Display trim on the baked sky colour. 1.0 is the colour the bake produced (physical sky), "
                    + "lower is darker, higher is brighter.")
            .defineInRange("displayExposure", 1.0, 0.25, 2.5);

    public static final ModConfigSpec.DoubleValue MOON_GAIN = BUILDER
            .comment("Aesthetic option to increase the moon brightness")
            .defineInRange("moonGain", 0.060, 0, 10);

    public static final ModConfigSpec.DoubleValue MOON_GLARE_KNEE = BUILDER
            .comment("Aesthetic option to fix the double-sunset bug")
            .defineInRange("moonGlareKnee", 8.0, 0, 32);

    public static final ModConfigSpec.DoubleValue MOON_SATURATION = BUILDER
            .comment("Saturation of the light emitted by the moon")
            .defineInRange("moonSaturation", 0.5, 0.0, 1.0);


    static final ModConfigSpec SPEC = BUILDER.build();

}
