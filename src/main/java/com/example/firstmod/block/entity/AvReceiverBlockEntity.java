package com.example.firstmod.block.entity;

import com.example.firstmod.FirstMod;
import net.minecraft.core.BlockPos;
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

/**
 * AV receiver state: media identity, playback position, volume and video output.
 *
 * <p>The server owns the playback intent and the media fingerprint. Decoding is
 * performed by each client's receiver playback agent because the server must
 * not read client files. Projectors and speakers only consume the receiver's
 * video/audio outputs.
 */
public class AvReceiverBlockEntity extends BlockEntity {
	public enum State {
		STOPPED,
		PLAYING,
		PAUSED
	}

	public static final double MIN_VOLUME = 0.0;
	public static final double MAX_VOLUME = 1.0;

	private String mediaKey = "";
	private long mediaSize;
	private String mediaSha256 = "";
	private double durationSeconds;
	private State state = State.STOPPED;
	private double positionSeconds;
	private float volume = 0.8f;
	private boolean muted;
	private BlockPos videoTarget;
	private long stateGameTime;
	/** Server-only: reset the playback clock on the first tick after loading. */
	private transient boolean resyncClock;

	public AvReceiverBlockEntity(BlockPos pos, BlockState blockState) {
		super(FirstMod.AV_RECEIVER_BLOCK_ENTITY, pos, blockState);
	}

	public static void tick(Level level, BlockPos pos, BlockState blockState, AvReceiverBlockEntity receiver) {
		if (level.isClientSide) {
			return;
		}
		if (receiver.resyncClock) {
			receiver.resyncClock = false;
			receiver.stateGameTime = level.getGameTime();
			receiver.markUpdated();
		}
		if (receiver.state == State.PLAYING) {
			double previous = receiver.positionSeconds;
			receiver.positionSeconds += 1.0 / 20.0;
			if (receiver.durationSeconds > 0.0) {
				receiver.positionSeconds = Math.min(receiver.positionSeconds, receiver.durationSeconds);
			}
			if (receiver.positionSeconds != previous && level.getGameTime() % 20L == 0L) {
				receiver.markUpdated();
			}
		}
		if (level.getGameTime() % 20L == 0L) {
			receiver.refreshProjectorLink(level);
		}
	}

	public String mediaKey() {
		return mediaKey;
	}

	public long mediaSize() {
		return mediaSize;
	}

	public String mediaSha256() {
		return mediaSha256;
	}

	public double durationSeconds() {
		return durationSeconds;
	}

	public State state() {
		return state;
	}

	public double positionSeconds() {
		return positionSeconds;
	}

	public float volume() {
		return volume;
	}

	public boolean isMuted() {
		return muted;
	}

	public BlockPos videoTarget() {
		return videoTarget;
	}

	public long stateGameTime() {
		return stateGameTime;
	}

	public boolean selectMedia(String key, long size, String sha256, double duration) {
		if (level == null || key == null || key.isEmpty() || isAnotherReceiverActive(level, worldPosition)) {
			return false;
		}
		mediaKey = key;
		mediaSize = Math.max(0L, size);
		mediaSha256 = sha256 == null ? "" : sha256;
		durationSeconds = Math.max(0.0, duration);
		positionSeconds = 0.0;
		state = State.PLAYING;
		stateGameTime = level.getGameTime();
		pairNearestProjector(level);
		markUpdated();
		return true;
	}

	public boolean play() {
		if (mediaKey.isEmpty() || level == null || isAnotherReceiverActive(level, worldPosition)) {
			return false;
		}
		if (videoTarget == null) {
			pairNearestProjector(level);
		}
		state = State.PLAYING;
		stateGameTime = level.getGameTime();
		markUpdated();
		return true;
	}

	public void pause() {
		if (state != State.PLAYING) {
			return;
		}
		state = State.PAUSED;
		markUpdated();
	}

	public void stop() {
		state = State.STOPPED;
		positionSeconds = 0.0;
		markUpdated();
	}

	public void seek(double seconds) {
		double max = durationSeconds > 0.0 ? durationSeconds : Double.MAX_VALUE;
		positionSeconds = Math.max(0.0, Math.min(seconds, max));
		markUpdated();
	}

	public void setVolume(double value) {
		volume = (float) Math.max(MIN_VOLUME, Math.min(MAX_VOLUME, value));
		markUpdated();
	}

	public void setMuted(boolean value) {
		muted = value;
		markUpdated();
	}

	public void setVideoTarget(BlockPos target) {
		if (Objects.equals(videoTarget, target)) {
			return;
		}
		clearProjectorLink();
		videoTarget = target;
		if (target != null && level != null && !level.isClientSide
			&& level.getBlockEntity(target) instanceof ProjectorBlockEntity projector) {
			projector.setReceiverPos(worldPosition);
		}
		markUpdated();
	}

	/**
	 * Chooses the nearest loaded projector within 48 blocks and links both block
	 * entities. Returns the chosen position, or {@code null} when none exists.
	 */
	public BlockPos pairNearestProjector(Level level) {
		if (level == null || level.isClientSide) {
			return videoTarget;
		}
		ProjectorBlockEntity best = null;
		double bestDistance = 48.0 * 48.0;
		ChunkPos center = new ChunkPos(worldPosition);
		int radiusChunks = (int) Math.ceil(48.0 / 16.0);
		Vec3 origin = Vec3.atCenterOf(worldPosition);
		for (int chunkX = center.x - radiusChunks; chunkX <= center.x + radiusChunks; chunkX++) {
			for (int chunkZ = center.z - radiusChunks; chunkZ <= center.z + radiusChunks; chunkZ++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
				if (chunk == null) {
					continue;
				}
				for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
					if (!(blockEntity instanceof ProjectorBlockEntity projector)) {
						continue;
					}
					double distance = Vec3.atCenterOf(projector.getBlockPos()).distanceToSqr(origin);
					if (distance < bestDistance) {
						bestDistance = distance;
						best = projector;
					}
				}
			}
		}
		BlockPos target = best == null ? null : best.getBlockPos();
		if (!Objects.equals(videoTarget, target)) {
			clearProjectorLink();
			videoTarget = target;
			if (best != null) {
				best.setReceiverPos(worldPosition);
			}
			markUpdated();
		} else if (best != null && !worldPosition.equals(best.receiverPos())) {
			best.setReceiverPos(worldPosition);
		}
		return target;
	}

	private void refreshProjectorLink(Level level) {
		if (videoTarget != null) {
			BlockEntity blockEntity = level.getBlockEntity(videoTarget);
			if (blockEntity instanceof ProjectorBlockEntity projector
				&& worldPosition.equals(projector.receiverPos())) {
				return;
			}
		}
		pairNearestProjector(level);
	}

	private void clearProjectorLink() {
		if (videoTarget == null || level == null || level.isClientSide) {
			return;
		}
		if (level.getBlockEntity(videoTarget) instanceof ProjectorBlockEntity projector) {
			projector.clearReceiver(worldPosition);
		}
	}

	/**
	 * M1 allows one audio engine, so only one nearby receiver may be active
	 * (playing or paused with an open client player).
	 */
	public static boolean isAnotherReceiverActive(Level level, BlockPos self) {
		if (level == null || level.isClientSide) {
			return false;
		}
		ChunkPos center = new ChunkPos(self);
		int radiusChunks = 4;
		for (int chunkX = center.x - radiusChunks; chunkX <= center.x + radiusChunks; chunkX++) {
			for (int chunkZ = center.z - radiusChunks; chunkZ <= center.z + radiusChunks; chunkZ++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
				if (chunk == null) {
					continue;
				}
				for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
					if (blockEntity instanceof AvReceiverBlockEntity other
						&& !other.worldPosition.equals(self)
						&& other.state != State.STOPPED) {
						return true;
					}
				}
			}
		}
		return false;
	}

	@Override
	public void setRemoved() {
		clearProjectorLink();
		super.setRemoved();
	}

	public Component status() {
		String media = mediaKey.isEmpty() ? "none" : mediaKey;
		String target = videoTarget == null ? "none" : videoTarget.toShortString();
		return Component.literal(String.format(Locale.ROOT,
			"AV receiver %s: media=%s state=%s %.1f/%.1fs volume=%.2f%s projector=%s",
			worldPosition.toShortString(), media, state.name().toLowerCase(Locale.ROOT),
			positionSeconds, durationSeconds, volume, muted ? " muted" : "", target));
	}

	private void markUpdated() {
		if (level != null) {
			stateGameTime = level.getGameTime();
		}
		setChanged();
		if (level != null && !level.isClientSide) {
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
		}
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		tag.putString("mediaKey", mediaKey);
		tag.putLong("mediaSize", mediaSize);
		tag.putString("mediaSha256", mediaSha256);
		tag.putDouble("durationSeconds", durationSeconds);
		tag.putString("state", state.name());
		tag.putDouble("positionSeconds", positionSeconds);
		tag.putFloat("volume", volume);
		tag.putBoolean("muted", muted);
		if (videoTarget != null) {
			tag.putLong("videoTarget", videoTarget.asLong());
		}
		tag.putLong("stateGameTime", stateGameTime);
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		mediaKey = tag.getString("mediaKey");
		mediaSize = tag.getLong("mediaSize");
		mediaSha256 = tag.getString("mediaSha256");
		durationSeconds = Math.max(0.0, tag.getDouble("durationSeconds"));
		state = parseState(tag.getString("state"));
		positionSeconds = Math.max(0.0, tag.getDouble("positionSeconds"));
		volume = (float) Math.max(MIN_VOLUME, Math.min(MAX_VOLUME,
			tag.contains("volume") ? tag.getDouble("volume") : 0.8));
		muted = tag.getBoolean("muted");
		videoTarget = tag.contains("videoTarget") ? BlockPos.of(tag.getLong("videoTarget")) : null;
		stateGameTime = tag.getLong("stateGameTime");
		resyncClock = state == State.PLAYING;
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return saveWithoutMetadata(registries);
	}

	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	private static State parseState(String name) {
		for (State value : State.values()) {
			if (value.name().equals(name)) {
				return value;
			}
		}
		return State.STOPPED;
	}
}
