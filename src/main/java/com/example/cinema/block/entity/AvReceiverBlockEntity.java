package com.example.cinema.block.entity;

import com.example.cinema.Cinema;
import com.example.cinema.block.SpeakerBlock;
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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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
	/** Explicitly linked speakers. Speakers store the matching receiver position. */
	private final List<BlockPos> speakerTargets = new ArrayList<>();
	private long stateChangedGameTime;
	/** Server-only: reset the playback clock on the first tick after loading. */
	private transient boolean resyncClock;

	public AvReceiverBlockEntity(BlockPos pos, BlockState blockState) {
		super(Cinema.avReceiverBlockEntity, pos, blockState);
	}

	public static void tick(Level level, BlockPos pos, BlockState blockState, AvReceiverBlockEntity receiver) {
		if (level.isClientSide) {
			return;
		}
		if (receiver.resyncClock) {
			receiver.resyncClock = false;
			receiver.stateChangedGameTime = level.getGameTime();
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
			receiver.refreshSpeakerLinks(level);
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

	/** Immutable snapshot of the speakers explicitly linked to this receiver. */
	public List<BlockPos> speakerTargets() {
		return List.copyOf(speakerTargets);
	}

	public void addSpeaker(BlockPos pos) {
		if (pos == null || speakerTargets.contains(pos)) {
			return;
		}
		speakerTargets.add(pos);
		markUpdated();
	}

	public void removeSpeaker(BlockPos pos) {
		if (speakerTargets.remove(pos)) {
			markUpdated();
		}
	}

	public void clearSpeakers() {
		if (speakerTargets.isEmpty()) {
			return;
		}
		speakerTargets.clear();
		markUpdated();
	}

	/** Clears {@code videoTarget} only when it still points at {@code expected}. */
	public void clearProjectorTarget(BlockPos expected) {
		if (expected != null && expected.equals(videoTarget)) {
			videoTarget = null;
			markUpdated();
		}
	}

	public long stateChangedGameTime() {
		return stateChangedGameTime;
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
		stateChangedGameTime = level.getGameTime();
		markUpdated();
		return true;
	}

	public boolean play() {
		if (mediaKey.isEmpty() || level == null || isAnotherReceiverActive(level, worldPosition)) {
			return false;
		}
		state = State.PLAYING;
		stateChangedGameTime = level.getGameTime();
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
	 * Keeps the explicit projector link healthy. There is no automatic
	 * nearest-projector search: an unlinked or broken link stays empty until
	 * the player pairs a projector with the Cinema Cable.
	 */
	private void refreshProjectorLink(Level level) {
		if (videoTarget == null) {
			return;
		}
		if (!level.isLoaded(videoTarget)) {
			// Keep the link while the projector's chunk is unloaded; otherwise a
			// chunk unload would silently unlink the devices.
			return;
		}
		BlockEntity blockEntity = level.getBlockEntity(videoTarget);
		if (blockEntity instanceof ProjectorBlockEntity projector
			&& worldPosition.equals(projector.receiverPos())) {
			return;
		}
		clearProjectorLink();
		videoTarget = null;
		markUpdated();
	}

	/** Drops speakers that were broken or re-linked to another receiver. */
	private void refreshSpeakerLinks(Level level) {
		if (speakerTargets.isEmpty()) {
			return;
		}
		boolean changed = false;
		for (Iterator<BlockPos> iterator = speakerTargets.iterator(); iterator.hasNext(); ) {
			BlockPos pos = iterator.next();
			if (!level.isLoaded(pos)) {
				continue;
			}
			if (!(level.getBlockState(pos).getBlock() instanceof SpeakerBlock)
				|| !(level.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker)
				|| !worldPosition.equals(speaker.receiverPos())) {
				iterator.remove();
				changed = true;
			}
		}
		if (changed) {
			markUpdated();
		}
	}

	private void clearProjectorLink() {
		if (videoTarget == null || level == null || level.isClientSide
			|| !level.isLoaded(videoTarget)) {
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
		// Chunk unload also calls setRemoved(). Only clear links when the block
		// was actually removed, otherwise leaving the area would unpair devices.
		if (wasActuallyRemoved()) {
			for (BlockPos pos : List.copyOf(speakerTargets)) {
				if (level.isLoaded(pos)
					&& level.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker) {
					speaker.clearReceiver(worldPosition);
				}
			}
			speakerTargets.clear();
			clearProjectorLink();
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
		return !chunk.getBlockState(worldPosition).is(Cinema.AV_RECEIVER_BLOCK);
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
			stateChangedGameTime = level.getGameTime();
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
			tag.putBoolean("videoTargetExplicit", true);
		}
		tag.putLongArray("speakerTargets",
			speakerTargets.stream().mapToLong(BlockPos::asLong).toArray());
		tag.putLong("stateGameTime", stateChangedGameTime);
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
		videoTarget = tag.getBoolean("videoTargetExplicit") && tag.contains("videoTarget")
			? BlockPos.of(tag.getLong("videoTarget")) : null;
		speakerTargets.clear();
		for (long packed : tag.getLongArray("speakerTargets")) {
			speakerTargets.add(BlockPos.of(packed));
		}
		stateChangedGameTime = tag.getLong("stateGameTime");
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
