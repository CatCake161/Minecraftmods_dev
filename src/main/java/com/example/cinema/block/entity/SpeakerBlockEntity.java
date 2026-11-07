package com.example.cinema.block.entity;

import com.example.cinema.Cinema;
import com.example.cinema.block.SpeakerBlock;
import com.example.cinema.util.CinemaNames;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Objects;

/** One physical speaker; the receiver assigns its channel role and level. */
public class SpeakerBlockEntity extends BlockEntity {
	/** Receiver channel trim range. The receiver output is capped at +6 dB. */
	public static final double MIN_LEVEL_DB = -60.0;
	public static final double MAX_LEVEL_DB = 6.0;
	public static final int MAX_NAME_LENGTH = CinemaNames.MAX_LENGTH;
	public static final int ACOUSTIC_YAW_STEPS = 36;
	public static final float ACOUSTIC_YAW_STEP_DEGREES = 10.0f;
	/** Acoustic centre is this far behind the front baffle reference. */
	public static final double ACOUSTIC_FRONT_OFFSET = 0.45;

	private SpeakerRole role = SpeakerRole.NONE;
	private double levelDb;
	private String name = "";
	private BlockPos receiverPos;
	/** -1 = derive from the block's four-way FACING; 0..35 = 10 degree steps. */
	private int acousticYawStep = -1;

	public SpeakerBlockEntity(BlockPos pos, BlockState blockState) {
		super(Cinema.speakerBlockEntity, pos, blockState);
	}

	public SpeakerType type() {
		if (getBlockState().getBlock() instanceof SpeakerBlock speakerBlock) {
			return speakerBlock.type();
		}
		return SpeakerType.FLOORSTANDING;
	}

	public SpeakerRole role() {
		return role;
	}

	/**
	 * Acoustic aim in 10 degree steps. The block state keeps the four-way
	 * placement facing; this field allows the player to aim the acoustic model
	 * and its rendered model with the alignment tool.
	 */
	public int acousticYawStep() {
		return acousticYawStep >= 0 ? acousticYawStep : yawStepFor(blockFacing());
	}

	public float acousticYawDegrees() {
		return acousticYawStep() * ACOUSTIC_YAW_STEP_DEGREES;
	}

	public Vec3 acousticForward() {
		// Positive yaw is counter-clockwise from above: when the player faces
		// the speaker, increasing yaw moves the aim toward the player's right.
		double radians = Math.toRadians(acousticYawDegrees());
		return new Vec3(-Math.sin(radians), 0.0, -Math.cos(radians));
	}

	/** Tweeter/driver centre above the block's lower Y. */
	public Vec3 acousticCenter() {
		double offset = switch (type()) {
			case FLOORSTANDING -> 1.31;
			case BOOKSHELF -> 0.77;
			case CENTER -> 0.33;
			case SUBWOOFER -> 0.48;
		};
		BlockPos pos = getBlockPos();
		return new Vec3(pos.getX() + 0.5, pos.getY() + offset, pos.getZ() + 0.5);
	}

	/** Acoustic centre shifted forward to the baffle reference. */
	public Vec3 acousticFrontPosition() {
		return acousticCenter().add(acousticForward().scale(ACOUSTIC_FRONT_OFFSET));
	}

	public int cycleAcousticYaw(boolean reverse) {
		acousticYawStep = Math.floorMod(acousticYawStep() + (reverse ? -1 : 1),
			ACOUSTIC_YAW_STEPS);
		markUpdated();
		return acousticYawStep;
	}

	public void setAcousticYawStep(int step) {
		acousticYawStep = Math.floorMod(step, ACOUSTIC_YAW_STEPS);
		markUpdated();
	}

	public double levelDb() {
		return levelDb;
	}

	/** User-assigned name, or empty for the role/type fallback label. */
	public String name() {
		return name;
	}

	public void setName(String value) {
		name = sanitizeName(value);
		markUpdated();
	}

	public static String sanitizeName(String value) {
		return CinemaNames.sanitize(value);
	}

	/** Receiver this speaker is explicitly wired to, or {@code null}. */
	public BlockPos receiverPos() {
		return receiverPos;
	}

	/** Server-only: drop a link whose receiver was removed or no longer lists us. */
	public void serverTick(Level level) {
		if (receiverPos == null || level.getGameTime() % 20L != 0L) {
			return;
		}
		if (!level.isLoaded(receiverPos)) {
			return;
		}
		if (!(level.getBlockEntity(receiverPos) instanceof AvReceiverBlockEntity receiver)
			|| !receiver.speakerTargets().contains(worldPosition)) {
			setReceiverPos(null);
		}
	}

	public void setReceiverPos(BlockPos pos) {
		if (Objects.equals(receiverPos, pos)) {
			return;
		}
		receiverPos = pos;
		markUpdated();
	}

	public void clearReceiver(BlockPos expected) {
		if (receiverPos != null && receiverPos.equals(expected)) {
			setReceiverPos(null);
		}
	}

	public SpeakerRole cycleRole() {
		role = type().nextRole(role);
		markUpdated();
		return role;
	}

	public void setRole(SpeakerRole value) {
		role = type().sanitize(value);
		markUpdated();
	}

	public void setLevelDb(double value) {
		levelDb = clampLevelDb(value);
		markUpdated();
	}

	public static double clampLevelDb(double value) {
		if (!Double.isFinite(value)) {
			return 0.0;
		}
		return Math.max(MIN_LEVEL_DB, Math.min(MAX_LEVEL_DB, value));
	}

	private Direction blockFacing() {
		if (getBlockState().getBlock() instanceof SpeakerBlock) {
			return getBlockState().getValue(SpeakerBlock.FACING);
		}
		return Direction.NORTH;
	}

	private static int yawStepFor(Direction direction) {
		return switch (direction) {
			case NORTH -> 0;
			case WEST -> 9;
			case SOUTH -> 18;
			case EAST -> 27;
			default -> 0;
		};
	}

	public Component status() {
		return Component.literal(String.format(Locale.ROOT,
			"Speaker %s (%s): name=%s role=%s level=%+.1f dB yaw=%.1f° receiver=%s",
			worldPosition.toShortString(), type().id(), name.isEmpty() ? "unnamed" : name,
			role.label(), levelDb, acousticYawDegrees(),
			receiverPos == null ? "none" : receiverPos.toShortString()));
	}

	@Override
	public void setRemoved() {
		// Chunk unload also calls setRemoved(); only tell the receiver when the
		// speaker block itself was removed.
		if (wasActuallyRemoved() && receiverPos != null && level.isLoaded(receiverPos)
			&& level.getBlockEntity(receiverPos) instanceof AvReceiverBlockEntity receiver) {
			receiver.removeSpeaker(worldPosition);
		}
		super.setRemoved();
	}

	private boolean wasActuallyRemoved() {
		if (level == null || level.isClientSide) {
			return false;
		}
		ChunkPos chunkPos = new ChunkPos(worldPosition);
		LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
		if (chunk == null) {
			// The chunk is already gone; this is an unload, not a block break.
			return false;
		}
		return !(chunk.getBlockState(worldPosition).getBlock() instanceof SpeakerBlock);
	}

	private void markUpdated() {
		setChanged();
		if (level != null && !level.isClientSide) {
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
		}
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		tag.putString("role", role.name());
		tag.putDouble("levelDb", levelDb);
		tag.putString("name", name);
		if (acousticYawStep >= 0) {
			tag.putInt("acousticYawStep", acousticYawStep);
		}
		if (receiverPos != null) {
			tag.putLong("receiverPos", receiverPos.asLong());
		}
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		role = type().sanitize(SpeakerRole.byName(tag.getString("role")));
		if (tag.contains("levelDb")) {
			levelDb = clampLevelDb(tag.getDouble("levelDb"));
		} else if (tag.contains("gain")) {
			// Alpha 0.0.1 saved a linear 0..2 gain. Migrate it to dB once.
			double legacyGain = Math.max(0.0, tag.getFloat("gain"));
			levelDb = legacyGain <= 0.0
				? MIN_LEVEL_DB
				: clampLevelDb(20.0 * Math.log10(legacyGain));
		} else {
			levelDb = 0.0;
		}
		name = sanitizeName(tag.getString("name"));
		acousticYawStep = tag.contains("acousticYawStep")
			? Math.floorMod(tag.getInt("acousticYawStep"), ACOUSTIC_YAW_STEPS) : -1;
		receiverPos = tag.contains("receiverPos") ? BlockPos.of(tag.getLong("receiverPos")) : null;
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return saveWithoutMetadata(registries);
	}

	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}
}
