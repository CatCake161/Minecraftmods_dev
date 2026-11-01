package com.example.firstmod.block.entity;

import com.example.firstmod.FirstMod;
import com.example.firstmod.block.ProjectorBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Cinema projector optics and lamp state.
 *
 * <p>The block entity only stores installation parameters. All projection
 * geometry (lens rays, throw cone, keystone, focus) is derived on the client
 * from these values plus the paired screen, so the server can stay authoritative
 * for the configuration while the client keeps rendering cheap.
 */
public class ProjectorBlockEntity extends BlockEntity {
	public static final double MIN_THROW_RATIO = 0.6;
	public static final double MAX_THROW_RATIO = 4.0;
	public static final double MIN_FOCUS_DISTANCE = 0.25;
	public static final double MAX_FOCUS_DISTANCE = 64.0;
	public static final double MIN_KEYSTONE_DEGREES = -30.0;
	public static final double MAX_KEYSTONE_DEGREES = 30.0;
	public static final double MIN_BRIGHTNESS = 0.01;
	public static final double MAX_BRIGHTNESS = 1.0;
	public static final double MIN_AMBIENT_WASH = 0.0;
	public static final double MAX_AMBIENT_WASH = 1.0;

	private static final double DEFAULT_THROW_RATIO = 1.75;
	private static final float WARMUP_PER_TICK = 1.0f / 80.0f; // 4 s to full lamp
	private static final float COOLDOWN_PER_TICK = 1.0f / 40.0f; // 2 s to dark

	private double throwRatio = DEFAULT_THROW_RATIO;
	/** Negative means auto-focus on the paired screen. */
	private double focusDistance = -1.0;
	private double keystoneVerticalDegrees;
	private double keystoneHorizontalDegrees;
	private double brightness = 1.0;
	private double ambientWash = 0.18;
	private boolean powered = true;

	/** Client-local lamp output, 0..1. */
	private float lampLevel;

	public ProjectorBlockEntity(BlockPos pos, BlockState state) {
		super(FirstMod.PROJECTOR_BLOCK_ENTITY, pos, state);
	}

	public static void tick(Level level, BlockPos pos, BlockState state, ProjectorBlockEntity projector) {
		projector.tickLamp();
	}

	private void tickLamp() {
		float target = powered ? 1.0f : 0.0f;
		float step = powered ? WARMUP_PER_TICK : COOLDOWN_PER_TICK;
		if (lampLevel < target) {
			lampLevel = Math.min(target, lampLevel + step);
		} else if (lampLevel > target) {
			lampLevel = Math.max(target, lampLevel - step);
		}
	}

	/** World-space lens center. The lens sits just in front of the block face. */
	public Vec3 lensPosition() {
		Direction facing = getBlockState().getValue(ProjectorBlock.FACING);
		Vec3 front = new Vec3(facing.getStepX(), 0.0, facing.getStepZ());
		return Vec3.atLowerCornerOf(worldPosition)
			.add(0.5, 0.42, 0.5)
			.add(front.scale(0.52));
	}

	public double throwRatio() {
		return throwRatio;
	}

	public double focusDistance() {
		return focusDistance;
	}

	public double keystoneVerticalDegrees() {
		return keystoneVerticalDegrees;
	}

	public double keystoneHorizontalDegrees() {
		return keystoneHorizontalDegrees;
	}

	public double brightness() {
		return brightness;
	}

	public double ambientWash() {
		return ambientWash;
	}

	public boolean isPowered() {
		return powered;
	}

	public float lampLevel() {
		return lampLevel;
	}

	/** Effective lamp output after the warm-up ramp and the brightness trim. */
	public float opticalBrightness() {
		return (float) Math.max(0.0, Math.min(MAX_BRIGHTNESS, lampLevel * brightness));
	}

	public void setThrowRatio(double value) {
		throwRatio = clamp(value, MIN_THROW_RATIO, MAX_THROW_RATIO);
		markUpdated();
	}

	public void setFocusDistance(double value) {
		focusDistance = value < 0.0 ? -1.0 : clamp(value, MIN_FOCUS_DISTANCE, MAX_FOCUS_DISTANCE);
		markUpdated();
	}

	public void setKeystone(double verticalDegrees, double horizontalDegrees) {
		keystoneVerticalDegrees = clamp(verticalDegrees, MIN_KEYSTONE_DEGREES, MAX_KEYSTONE_DEGREES);
		keystoneHorizontalDegrees = clamp(horizontalDegrees, MIN_KEYSTONE_DEGREES, MAX_KEYSTONE_DEGREES);
		markUpdated();
	}

	public void setBrightness(double value) {
		brightness = clamp(value, MIN_BRIGHTNESS, MAX_BRIGHTNESS);
		markUpdated();
	}

	public void setAmbientWash(double value) {
		ambientWash = clamp(value, MIN_AMBIENT_WASH, MAX_AMBIENT_WASH);
		markUpdated();
	}

	public void setPowered(boolean value) {
		powered = value;
		markUpdated();
	}

	/** Debug/test hook: jump the lamp to a value without waiting for the ramp. */
	public void setLampLevel(float value) {
		lampLevel = Math.max(0.0f, Math.min(1.0f, value));
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
		tag.putDouble("throwRatio", throwRatio);
		tag.putDouble("focusDistance", focusDistance);
		tag.putDouble("keystoneVertical", keystoneVerticalDegrees);
		tag.putDouble("keystoneHorizontal", keystoneHorizontalDegrees);
		tag.putDouble("brightness", brightness);
		tag.putDouble("ambientWash", ambientWash);
		tag.putBoolean("powered", powered);
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		throwRatio = clamp(tag.getDouble("throwRatio") == 0.0 ? DEFAULT_THROW_RATIO : tag.getDouble("throwRatio"),
			MIN_THROW_RATIO, MAX_THROW_RATIO);
		focusDistance = tag.contains("focusDistance")
			? (tag.getDouble("focusDistance") < 0.0
				? -1.0
				: clamp(tag.getDouble("focusDistance"), MIN_FOCUS_DISTANCE, MAX_FOCUS_DISTANCE))
			: -1.0;
		keystoneVerticalDegrees = clamp(tag.getDouble("keystoneVertical"),
			MIN_KEYSTONE_DEGREES, MAX_KEYSTONE_DEGREES);
		keystoneHorizontalDegrees = clamp(tag.getDouble("keystoneHorizontal"),
			MIN_KEYSTONE_DEGREES, MAX_KEYSTONE_DEGREES);
		brightness = clamp(tag.contains("brightness") ? tag.getDouble("brightness") : 1.0,
			MIN_BRIGHTNESS, MAX_BRIGHTNESS);
		ambientWash = clamp(tag.contains("ambientWash") ? tag.getDouble("ambientWash") : 0.18,
			MIN_AMBIENT_WASH, MAX_AMBIENT_WASH);
		powered = !tag.contains("powered") || tag.getBoolean("powered");
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return saveWithoutMetadata(registries);
	}

	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}
}
