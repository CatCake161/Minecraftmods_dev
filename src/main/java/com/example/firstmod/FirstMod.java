package com.example.firstmod;

import com.example.firstmod.block.ProjectorBlock;
import com.example.firstmod.block.ScreenBlock;
import com.example.firstmod.block.entity.ScreenBlockEntity;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FirstMod implements ModInitializer {
	public static final String MOD_ID = "firstmod";

	// Use the mod id as the logger name so log lines are easy to trace back.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final ScreenBlock SCREEN_BLOCK = new ScreenBlock(
		BlockBehaviour.Properties.of().strength(1.5f).noOcclusion());
	public static final ProjectorBlock PROJECTOR_BLOCK = new ProjectorBlock(
		BlockBehaviour.Properties.of().strength(2.0f));
	public static final Item SCREEN_ITEM = new BlockItem(SCREEN_BLOCK, new Item.Properties());
	public static final Item PROJECTOR_ITEM = new BlockItem(PROJECTOR_BLOCK, new Item.Properties());
	public static BlockEntityType<ScreenBlockEntity> SCREEN_BLOCK_ENTITY;

	@Override
	public void onInitialize() {
		Registry.register(BuiltInRegistries.BLOCK, id("screen"), SCREEN_BLOCK);
		Registry.register(BuiltInRegistries.BLOCK, id("projector"), PROJECTOR_BLOCK);
		Registry.register(BuiltInRegistries.ITEM, id("screen"), SCREEN_ITEM);
		Registry.register(BuiltInRegistries.ITEM, id("projector"), PROJECTOR_ITEM);
		SCREEN_BLOCK_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id("screen"),
			BlockEntityType.Builder.of(ScreenBlockEntity::new, SCREEN_BLOCK).build(null));

		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(entries -> {
			entries.accept(SCREEN_ITEM);
			entries.accept(PROJECTOR_ITEM);
		});

		LOGGER.info("First Mod is loading!");
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
}
