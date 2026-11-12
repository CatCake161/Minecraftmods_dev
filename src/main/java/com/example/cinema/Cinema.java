package com.example.cinema;

import com.example.cinema.block.AvReceiverBlock;
import com.example.cinema.block.entity.AvReceiverBlockEntity;
import com.example.cinema.block.entity.ProjectorBlockEntity;
import com.example.cinema.block.entity.ScreenBlockEntity;
import com.example.cinema.block.entity.SeatBlockEntity;
import com.example.cinema.block.entity.SpeakerBlockEntity;
import com.example.cinema.block.entity.SpeakerType;
import com.example.cinema.block.FloorstandingSpeakerBlock;
import com.example.cinema.block.ProjectorBlock;
import com.example.cinema.block.ScreenBlock;
import com.example.cinema.block.SeatBlock;
import com.example.cinema.block.SpeakerBlock;
import com.example.cinema.command.ProjectorCommand;
import com.example.cinema.entity.SeatEntity;
import com.example.cinema.item.CableItem;
import com.example.cinema.item.RemoteControllerItem;
import com.example.cinema.item.SpeakerAlignmentToolItem;
import com.example.cinema.network.AvReceiverControlHandler;
import com.example.cinema.network.AvReceiverControlPayload;
import com.example.cinema.network.DeviceNameHandler;
import com.example.cinema.network.DeviceNamePayload;
import com.example.cinema.network.ProjectorSettingsHandler;
import com.example.cinema.network.ProjectorSettingsPayload;
import com.example.cinema.network.RemoteBindHandler;
import com.example.cinema.network.RemoteBindPayload;
import com.example.cinema.network.RemotePairingActionPayload;
import com.example.cinema.network.RemotePairingHandler;
import com.example.cinema.network.RemotePairingListPayload;
import com.example.cinema.network.RemotePairingRequestPayload;
import com.example.cinema.network.RemoteScanHandler;
import com.example.cinema.network.RemoteScanListPayload;
import com.example.cinema.network.RemoteScanRequestPayload;
import com.example.cinema.network.SpeakerLevelHandler;
import com.example.cinema.network.SpeakerLevelPayload;
import com.example.cinema.network.SpeakerNameHandler;
import com.example.cinema.network.SpeakerNamePayload;
import com.example.cinema.network.SpeakerRoleHandler;
import com.example.cinema.network.SpeakerRolePayload;
import com.example.cinema.pairing.CinemaPairing;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Cinema implements ModInitializer {
	public static final String MOD_ID = "cinema";

	// Single underlying logger. Application code should use CinemaLog so every
	// line keeps the "cinema <area>: <message>" shape.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final ScreenBlock SCREEN_BLOCK = new ScreenBlock(
		BlockBehaviour.Properties.of().strength(1.5f).noOcclusion());
	public static final ProjectorBlock PROJECTOR_BLOCK = new ProjectorBlock(
		BlockBehaviour.Properties.of().strength(2.0f).noOcclusion());
	public static final AvReceiverBlock AV_RECEIVER_BLOCK = new AvReceiverBlock(
		BlockBehaviour.Properties.of().strength(2.0f).noOcclusion());
	public static final SeatBlock SEAT_BLOCK = new SeatBlock(
		BlockBehaviour.Properties.of().strength(1.0f).noOcclusion());
	public static final FloorstandingSpeakerBlock FLOORSTANDING_SPEAKER_BLOCK = new FloorstandingSpeakerBlock(
		BlockBehaviour.Properties.of().strength(1.5f).noOcclusion());
	public static final SpeakerBlock BOOKSHELF_SPEAKER_BLOCK = new SpeakerBlock(SpeakerType.BOOKSHELF,
		BlockBehaviour.Properties.of().strength(1.5f).noOcclusion());
	public static final SpeakerBlock CENTER_SPEAKER_BLOCK = new SpeakerBlock(SpeakerType.CENTER,
		BlockBehaviour.Properties.of().strength(1.5f).noOcclusion());
	public static final SpeakerBlock SUBWOOFER_BLOCK = new SpeakerBlock(SpeakerType.SUBWOOFER,
		BlockBehaviour.Properties.of().strength(2.0f).noOcclusion());
	public static final Item SCREEN_ITEM = new BlockItem(SCREEN_BLOCK, new Item.Properties());
	public static final Item PROJECTOR_ITEM = new BlockItem(PROJECTOR_BLOCK, new Item.Properties());
	public static final Item AV_RECEIVER_ITEM = new BlockItem(AV_RECEIVER_BLOCK, new Item.Properties());
	public static final Item SEAT_ITEM = new BlockItem(SEAT_BLOCK, new Item.Properties());
	public static final Item FLOORSTANDING_SPEAKER_ITEM = new BlockItem(FLOORSTANDING_SPEAKER_BLOCK, new Item.Properties());
	public static final Item BOOKSHELF_SPEAKER_ITEM = new BlockItem(BOOKSHELF_SPEAKER_BLOCK, new Item.Properties());
	public static final Item CENTER_SPEAKER_ITEM = new BlockItem(CENTER_SPEAKER_BLOCK, new Item.Properties());
	public static final Item SUBWOOFER_ITEM = new BlockItem(SUBWOOFER_BLOCK, new Item.Properties());
	public static final Item CABLE_ITEM = new CableItem(new Item.Properties());
	public static final Item REMOTE_CONTROLLER_ITEM = new RemoteControllerItem(new Item.Properties());
	public static final Item SPEAKER_ALIGNMENT_TOOL_ITEM =
		new SpeakerAlignmentToolItem(new Item.Properties().stacksTo(1));
	public static BlockEntityType<ScreenBlockEntity> screenBlockEntity;
	public static BlockEntityType<ProjectorBlockEntity> projectorBlockEntity;
	public static BlockEntityType<AvReceiverBlockEntity> avReceiverBlockEntity;
	public static BlockEntityType<SeatBlockEntity> seatBlockEntity;
	public static BlockEntityType<SpeakerBlockEntity> speakerBlockEntity;
	public static EntityType<SeatEntity> seatEntityType;

	@Override
	public void onInitialize() {
		Registry.register(BuiltInRegistries.BLOCK, id("screen"), SCREEN_BLOCK);
		Registry.register(BuiltInRegistries.BLOCK, id("projector"), PROJECTOR_BLOCK);
		Registry.register(BuiltInRegistries.BLOCK, id("av_receiver"), AV_RECEIVER_BLOCK);
		Registry.register(BuiltInRegistries.BLOCK, id("seat"), SEAT_BLOCK);
		Registry.register(BuiltInRegistries.BLOCK, id("floorstanding_speaker"), FLOORSTANDING_SPEAKER_BLOCK);
		Registry.register(BuiltInRegistries.BLOCK, id("bookshelf_speaker"), BOOKSHELF_SPEAKER_BLOCK);
		Registry.register(BuiltInRegistries.BLOCK, id("center_speaker"), CENTER_SPEAKER_BLOCK);
		Registry.register(BuiltInRegistries.BLOCK, id("subwoofer"), SUBWOOFER_BLOCK);
		Registry.register(BuiltInRegistries.ITEM, id("screen"), SCREEN_ITEM);
		Registry.register(BuiltInRegistries.ITEM, id("projector"), PROJECTOR_ITEM);
		Registry.register(BuiltInRegistries.ITEM, id("av_receiver"), AV_RECEIVER_ITEM);
		Registry.register(BuiltInRegistries.ITEM, id("seat"), SEAT_ITEM);
		Registry.register(BuiltInRegistries.ITEM, id("floorstanding_speaker"), FLOORSTANDING_SPEAKER_ITEM);
		Registry.register(BuiltInRegistries.ITEM, id("bookshelf_speaker"), BOOKSHELF_SPEAKER_ITEM);
		Registry.register(BuiltInRegistries.ITEM, id("center_speaker"), CENTER_SPEAKER_ITEM);
		Registry.register(BuiltInRegistries.ITEM, id("subwoofer"), SUBWOOFER_ITEM);
		Registry.register(BuiltInRegistries.ITEM, id("cable"), CABLE_ITEM);
		Registry.register(BuiltInRegistries.ITEM, id("remote_controller"), REMOTE_CONTROLLER_ITEM);
		Registry.register(BuiltInRegistries.ITEM, id("speaker_alignment_tool"),
			SPEAKER_ALIGNMENT_TOOL_ITEM);
		screenBlockEntity = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id("screen"),
			BlockEntityType.Builder.of(ScreenBlockEntity::new, SCREEN_BLOCK).build(null));
		projectorBlockEntity = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id("projector"),
			BlockEntityType.Builder.of(ProjectorBlockEntity::new, PROJECTOR_BLOCK).build(null));
		avReceiverBlockEntity = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id("av_receiver"),
			BlockEntityType.Builder.of(AvReceiverBlockEntity::new, AV_RECEIVER_BLOCK).build(null));
		seatBlockEntity = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id("seat"),
			BlockEntityType.Builder.of(SeatBlockEntity::new, SEAT_BLOCK).build(null));
		speakerBlockEntity = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id("speaker"),
			BlockEntityType.Builder.of(SpeakerBlockEntity::new,
				FLOORSTANDING_SPEAKER_BLOCK, BOOKSHELF_SPEAKER_BLOCK, CENTER_SPEAKER_BLOCK, SUBWOOFER_BLOCK).build(null));
		seatEntityType = Registry.register(BuiltInRegistries.ENTITY_TYPE, id("seat"),
			EntityType.Builder.of(SeatEntity::new, MobCategory.MISC)
				.sized(0.25f, 0.25f)
				.clientTrackingRange(4)
				.updateInterval(20)
				.noSummon()
				.noSave()
				.build("cinema_seat"));

		PayloadTypeRegistry.playC2S().register(AvReceiverControlPayload.TYPE, AvReceiverControlPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(AvReceiverControlPayload.TYPE, AvReceiverControlHandler::handle);
		PayloadTypeRegistry.playC2S().register(SpeakerRolePayload.TYPE, SpeakerRolePayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(SpeakerRolePayload.TYPE, SpeakerRoleHandler::handle);
		PayloadTypeRegistry.playC2S().register(SpeakerLevelPayload.TYPE, SpeakerLevelPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(SpeakerLevelPayload.TYPE, SpeakerLevelHandler::handle);
		PayloadTypeRegistry.playC2S().register(SpeakerNamePayload.TYPE, SpeakerNamePayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(SpeakerNamePayload.TYPE, SpeakerNameHandler::handle);
		PayloadTypeRegistry.playC2S().register(DeviceNamePayload.TYPE, DeviceNamePayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(DeviceNamePayload.TYPE, DeviceNameHandler::handle);
		PayloadTypeRegistry.playC2S().register(ProjectorSettingsPayload.TYPE, ProjectorSettingsPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(ProjectorSettingsPayload.TYPE, ProjectorSettingsHandler::handle);
		PayloadTypeRegistry.playC2S().register(RemoteBindPayload.TYPE, RemoteBindPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(RemoteBindPayload.TYPE, RemoteBindHandler::handle);
		PayloadTypeRegistry.playC2S().register(RemotePairingRequestPayload.TYPE,
			RemotePairingRequestPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(RemotePairingListPayload.TYPE,
			RemotePairingListPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(RemotePairingActionPayload.TYPE,
			RemotePairingActionPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(RemotePairingRequestPayload.TYPE,
			RemotePairingHandler::handleRequest);
		ServerPlayNetworking.registerGlobalReceiver(RemotePairingActionPayload.TYPE,
			RemotePairingHandler::handleAction);
		PayloadTypeRegistry.playC2S().register(RemoteScanRequestPayload.TYPE,
			RemoteScanRequestPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(RemoteScanListPayload.TYPE,
			RemoteScanListPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(RemoteScanRequestPayload.TYPE,
			RemoteScanHandler::handle);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			ProjectorCommand.register(dispatcher));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
			CinemaPairing.clear(handler.getPlayer().getUUID()));

		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(entries -> {
			entries.accept(SCREEN_ITEM);
			entries.accept(PROJECTOR_ITEM);
			entries.accept(AV_RECEIVER_ITEM);
			entries.accept(SEAT_ITEM);
			entries.accept(FLOORSTANDING_SPEAKER_ITEM);
			entries.accept(BOOKSHELF_SPEAKER_ITEM);
			entries.accept(CENTER_SPEAKER_ITEM);
			entries.accept(SUBWOOFER_ITEM);
		});
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> {
			entries.accept(CABLE_ITEM);
			entries.accept(REMOTE_CONTROLLER_ITEM);
			entries.accept(SPEAKER_ALIGNMENT_TOOL_ITEM);
		});

		CinemaLog.info(CinemaLog.LIFECYCLE, "loading");
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
}
