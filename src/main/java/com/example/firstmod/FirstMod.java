package com.example.firstmod;

import com.example.firstmod.block.AvReceiverBlock;
import com.example.firstmod.block.ProjectorBlock;
import com.example.firstmod.block.ScreenBlock;
import com.example.firstmod.block.SpeakerBlock;
import com.example.firstmod.block.entity.AvReceiverBlockEntity;
import com.example.firstmod.block.entity.ProjectorBlockEntity;
import com.example.firstmod.block.entity.ScreenBlockEntity;
import com.example.firstmod.block.entity.SpeakerBlockEntity;
import com.example.firstmod.command.ProjectorCommand;
import com.example.firstmod.item.CableItem;
import com.example.firstmod.item.RemoteControllerItem;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
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
		BlockBehaviour.Properties.of().strength(2.0f).noOcclusion());
	public static final AvReceiverBlock AV_RECEIVER_BLOCK = new AvReceiverBlock(
		BlockBehaviour.Properties.of().strength(2.0f).noOcclusion());
	public static final SpeakerBlock SPEAKER_BLOCK = new SpeakerBlock(
		BlockBehaviour.Properties.of().strength(1.5f).noOcclusion());
	public static final Item SCREEN_ITEM = new BlockItem(SCREEN_BLOCK, new Item.Properties());
	public static final Item PROJECTOR_ITEM = new BlockItem(PROJECTOR_BLOCK, new Item.Properties());
	public static final Item AV_RECEIVER_ITEM = new BlockItem(AV_RECEIVER_BLOCK, new Item.Properties());
	public static final Item SPEAKER_ITEM = new BlockItem(SPEAKER_BLOCK, new Item.Properties());
	public static final Item CABLE_ITEM = new CableItem(new Item.Properties());
	public static final Item REMOTE_CONTROLLER_ITEM = new RemoteControllerItem(new Item.Properties());
	public static BlockEntityType<ScreenBlockEntity> SCREEN_BLOCK_ENTITY;
	public static BlockEntityType<ProjectorBlockEntity> PROJECTOR_BLOCK_ENTITY;
	public static BlockEntityType<AvReceiverBlockEntity> AV_RECEIVER_BLOCK_ENTITY;
	public static BlockEntityType<SpeakerBlockEntity> SPEAKER_BLOCK_ENTITY;

	@Override
	public void onInitialize() {
		Registry.register(BuiltInRegistries.BLOCK, id("screen"), SCREEN_BLOCK);
		Registry.register(BuiltInRegistries.BLOCK, id("projector"), PROJECTOR_BLOCK);
		Registry.register(BuiltInRegistries.BLOCK, id("av_receiver"), AV_RECEIVER_BLOCK);
		Registry.register(BuiltInRegistries.BLOCK, id("speaker"), SPEAKER_BLOCK);
		Registry.register(BuiltInRegistries.ITEM, id("screen"), SCREEN_ITEM);
		Registry.register(BuiltInRegistries.ITEM, id("projector"), PROJECTOR_ITEM);
		Registry.register(BuiltInRegistries.ITEM, id("av_receiver"), AV_RECEIVER_ITEM);
		Registry.register(BuiltInRegistries.ITEM, id("speaker"), SPEAKER_ITEM);
		Registry.register(BuiltInRegistries.ITEM, id("cable"), CABLE_ITEM);
		Registry.register(BuiltInRegistries.ITEM, id("remote_controller"), REMOTE_CONTROLLER_ITEM);
		SCREEN_BLOCK_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id("screen"),
			BlockEntityType.Builder.of(ScreenBlockEntity::new, SCREEN_BLOCK).build(null));
		PROJECTOR_BLOCK_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id("projector"),
			BlockEntityType.Builder.of(ProjectorBlockEntity::new, PROJECTOR_BLOCK).build(null));
		AV_RECEIVER_BLOCK_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id("av_receiver"),
			BlockEntityType.Builder.of(AvReceiverBlockEntity::new, AV_RECEIVER_BLOCK).build(null));
		SPEAKER_BLOCK_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id("speaker"),
			BlockEntityType.Builder.of(SpeakerBlockEntity::new, SPEAKER_BLOCK).build(null));

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			ProjectorCommand.register(dispatcher));

		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(entries -> {
			entries.accept(SCREEN_ITEM);
			entries.accept(PROJECTOR_ITEM);
			entries.accept(AV_RECEIVER_ITEM);
			entries.accept(SPEAKER_ITEM);
		});
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> {
			entries.accept(CABLE_ITEM);
			entries.accept(REMOTE_CONTROLLER_ITEM);
		});

		LOGGER.info("First Mod is loading!");
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
}
