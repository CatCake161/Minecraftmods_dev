package com.example.cinema.client.media.audio.dsp;

import com.example.cinema.CinemaLog;
import com.example.cinema.client.media.audio.acoustics.geometry.AcousticVoxelGrid;
import com.sun.jna.Memory;
import com.sun.jna.Native;

import java.util.List;

/**
 * CUDA port of the listener-centered voxel ray tracer used for the order-3/4
 * late field.
 *
 * <p>Each pass launches one kernel over a Fibonacci sphere; the per-pass
 * {@code rayOffset} rotates that sphere, so several passes can be accumulated
 * without an RNG. The kernel writes one record per (ray, bounce). Only the
 * diffuse lobe is evaluated:
 * at every wall hit the kernel performs next-event estimation against the
 * speakers, so a record carries the channel power of that hit. The exact image
 * solver keeps owning the specular part, so the two never double count.
 *
 * <p>The geometry stays on the device and is re-uploaded only when the room
 * version changes. Results are plain data, so the caller can accumulate them
 * over several passes ({@code RtLateFieldBins}) without touching the GPU again.
 */
public final class CudaRayTracer implements AutoCloseable {
	/** One speaker target for next-event estimation. */
	public record Speaker(float x, float y, float z, int channel, float gain) {
	}

	/**
	 * One hit. {@code gain} is the strongest channel's amplitude, already in
	 * the same domain as {@code LateReflectionBus.Tap.gain()} (0 when the hit
	 * fed no speaker); {@code channel} is that channel or -1.
	 */
	public record Hit(float delaySeconds, float dirX, float dirY, float dirZ,
					  float energy, int channel, float gain, int order,
					  float high, int material) {
	}

	private static final int THREADS = 128;
	private static final float SPEED_OF_SOUND = 343.0f;

	private long dSolid;
	private long dReflect;
	private long dScatter;
	private long dHigh;
	private long dMaterialGrid;
	private long dMaterialOut;
	private long dDelay;
	private long dDir;
	private long dEnergy;
	private long dChan;
	private long dHighOut;
	private long dSpeakers;
	private long dSpeakerChannels;
	private long dSpeakerGains;
	private int gridCells;
	private int speakerCapacity;
	private int outSlots;
	private int sx;
	private int sy;
	private int sz;
	private float ox;
	private float oy;
	private float oz;
	private boolean warned;

	/** True when the driver, context and RT module are all usable. */
	public static boolean available() {
		try {
			return CudaContext.ensureRtInitialized();
		} catch (Throwable t) {
			return false;
		}
	}

	/** Uploads the voxel occupancy, reflectivity and scattering grids. */
	public void upload(AcousticVoxelGrid grid) {
		if (grid == null || !available()) {
			return;
		}
		int cells = grid.cellCount();
		CudaContext.lock();
		try {
			CudaContext.setCurrent();
			CudaContext.CudaDriver driver = CudaContext.driver();
			if (dSolid == 0 || cells > gridCells) {
				freeGrid(driver);
				dSolid = alloc(driver, cells);
				dReflect = alloc(driver, (long) cells * Float.BYTES);
				dScatter = alloc(driver, (long) cells * Float.BYTES);
				dHigh = alloc(driver, (long) cells * Float.BYTES);
				dMaterialGrid = alloc(driver, (long) cells * Integer.BYTES);
				gridCells = cells;
			}
			Memory hSolid = new Memory(cells);
			hSolid.write(0, grid.solid, 0, cells);
			CudaContext.check(driver.cuMemcpyHtoDV2(dSolid, hSolid, cells),
				"cuMemcpyHtoD(solid)");
			Memory hReflect = new Memory((long) cells * Float.BYTES);
			hReflect.write(0, grid.reflect, 0, cells);
			CudaContext.check(driver.cuMemcpyHtoDV2(dReflect, hReflect,
				(long) cells * Float.BYTES), "cuMemcpyHtoD(reflect)");
			Memory hScatter = new Memory((long) cells * Float.BYTES);
			hScatter.write(0, grid.scatter, 0, cells);
			CudaContext.check(driver.cuMemcpyHtoDV2(dScatter, hScatter,
				(long) cells * Float.BYTES), "cuMemcpyHtoD(scatter)");
			Memory hHigh = new Memory((long) cells * Float.BYTES);
			hHigh.write(0, grid.high, 0, cells);
			CudaContext.check(driver.cuMemcpyHtoDV2(dHigh, hHigh,
				(long) cells * Float.BYTES), "cuMemcpyHtoD(high)");
			Memory hMaterial = new Memory((long) cells * Integer.BYTES);
			hMaterial.write(0, grid.materialId, 0, cells);
			CudaContext.check(driver.cuMemcpyHtoDV2(dMaterialGrid, hMaterial,
				(long) cells * Integer.BYTES), "cuMemcpyHtoD(material)");
			this.sx = grid.sizeX;
			this.sy = grid.sizeY;
			this.sz = grid.sizeZ;
			this.ox = grid.originX;
			this.oy = grid.originY;
			this.oz = grid.originZ;
		} finally {
			CudaContext.releaseCurrent();
			CudaContext.unlock();
		}
	}

	/** True after a grid has been uploaded. */
	public boolean hasGrid() {
		return dSolid != 0;
	}

	/**
	 * Traces {@code rays} directions from the listener and returns
	 * {@code rays * bounces} records. Hits that reached no speaker have
	 * {@code channel == -1} and {@code gain == 0}.
	 *
	 * @param maxDistanceMeters longest segment per bounce, in real metres
	 */
	public Hit[] trace(float listenerX, float listenerY, float listenerZ,
					   int rays, int bounces, float maxDistanceMeters,
					   float metersPerBlock, List<Speaker> speakers) {
		return trace(listenerX, listenerY, listenerZ, rays, bounces,
			maxDistanceMeters, metersPerBlock, speakers, 0.0f);
	}

	/**
	 * Same as {@link #trace(float, float, float, int, int, float, float, List)}
	 * but with a per-pass rotation of the Fibonacci sphere.
	 *
	 * <p>{@code rayOffset} is a phase in {@code [0,1)} that rotates the spiral
	 * and shifts the z lattice by less than half a step. Offset {@code 0}
	 * reproduces the base set exactly; advancing it by the golden ratio every
	 * pass yields a low-discrepancy sequence of ray sets, so accumulating
	 * several passes averages the same field instead of the same noise.
	 */
	public Hit[] trace(float listenerX, float listenerY, float listenerZ,
					   int rays, int bounces, float maxDistanceMeters,
					   float metersPerBlock, List<Speaker> speakers,
					   float rayOffset) {
		if (!hasGrid() || !available() || rays <= 0 || bounces <= 0) {
			return new Hit[0];
		}
		int slots = rays * bounces;
		if (maxDistanceMeters <= 0.0f || !Float.isFinite(maxDistanceMeters)) {
			maxDistanceMeters = 64.0f;
		}
		if (!Float.isFinite(rayOffset)) {
			rayOffset = 0.0f;
		}
		rayOffset -= (float) Math.floor(rayOffset);
		float maxDistanceBlocks = maxDistanceMeters
			/ Math.max(1.0e-4f, metersPerBlock);
		int count = speakers == null ? 0 : speakers.size();
		Hit[] hits = new Hit[slots];
		CudaContext.lock();
		try {
			CudaContext.setCurrent();
			CudaContext.CudaDriver driver = CudaContext.driver();
			ensureOutput(driver, slots);
			uploadSpeakers(driver, speakers, count);

			Memory argSolid = argLong(dSolid);
			Memory argReflect = argLong(dReflect);
			Memory argScatter = argLong(dScatter);
			Memory argHighGrid = argLong(dHigh);
			Memory argMaterialGrid = argLong(dMaterialGrid);
			Memory argSx = argInt(sx);
			Memory argSy = argInt(sy);
			Memory argSz = argInt(sz);
			Memory argOx = argFloat(ox);
			Memory argOy = argFloat(oy);
			Memory argOz = argFloat(oz);
			Memory argLx = argFloat(listenerX);
			Memory argLy = argFloat(listenerY);
			Memory argLz = argFloat(listenerZ);
			Memory argSpPos = argLong(dSpeakers);
			Memory argSpChan = argLong(dSpeakerChannels);
			Memory argSpGain = argLong(dSpeakerGains);
			Memory argSpCount = argInt(count);
			Memory argRays = argInt(rays);
			Memory argBounces = argInt(bounces);
			Memory argMax = argFloat(maxDistanceBlocks);
			Memory argOffset = argFloat(rayOffset);
			Memory argMeters = argFloat(metersPerBlock);
			Memory argSpeed = argFloat(SPEED_OF_SOUND);
			Memory argDelay = argLong(dDelay);
			Memory argDir = argLong(dDir);
			Memory argEnergy = argLong(dEnergy);
			Memory argChan = argLong(dChan);
			Memory argHighOut = argLong(dHighOut);
			Memory argMaterialOut = argLong(dMaterialOut);
			Memory[] args = {argSolid, argReflect, argScatter, argHighGrid,
				argMaterialGrid,
				argSx, argSy,
				argSz, argOx, argOy, argOz, argLx, argLy, argLz, argSpPos,
				argSpChan, argSpGain, argSpCount, argRays, argBounces, argMax,
				argOffset, argMeters, argSpeed, argDelay, argDir, argEnergy,
				argChan, argHighOut, argMaterialOut};
			Memory pointers = pointerTable(args);
			int grid = (rays + THREADS - 1) / THREADS;
			CudaContext.check(driver.cuLaunchKernel(CudaContext.rtFunction(),
				grid, 1, 1, THREADS, 1, 1, 0, null, pointers, null),
				"cuLaunchKernel(rt_trace)");
			CudaContext.check(driver.cuCtxSynchronize(), "cuCtxSynchronize(rt)");

			Memory hDelay = new Memory((long) slots * Float.BYTES);
			Memory hDir = new Memory((long) slots * 3L * Float.BYTES);
			Memory hEnergy = new Memory((long) slots * Float.BYTES);
			Memory hChan = new Memory((long) slots * 6L * Float.BYTES);
			Memory hHigh = new Memory((long) slots * Float.BYTES);
			Memory hMaterial = new Memory((long) slots * Integer.BYTES);
			CudaContext.check(driver.cuMemcpyDtoHV2(hDelay, dDelay,
				(long) slots * Float.BYTES), "cuMemcpyDtoH(delay)");
			CudaContext.check(driver.cuMemcpyDtoHV2(hDir, dDir,
				(long) slots * 3L * Float.BYTES), "cuMemcpyDtoH(dir)");
			CudaContext.check(driver.cuMemcpyDtoHV2(hEnergy, dEnergy,
				(long) slots * Float.BYTES), "cuMemcpyDtoH(energy)");
			CudaContext.check(driver.cuMemcpyDtoHV2(hChan, dChan,
				(long) slots * 6L * Float.BYTES), "cuMemcpyDtoH(chan)");
			CudaContext.check(driver.cuMemcpyDtoHV2(hHigh, dHighOut,
				(long) slots * Float.BYTES), "cuMemcpyDtoH(high)");
			CudaContext.check(driver.cuMemcpyDtoHV2(hMaterial, dMaterialOut,
				(long) slots * Integer.BYTES), "cuMemcpyDtoH(material)");
			float[] delays = hDelay.getFloatArray(0, slots);
			float[] dirs = hDir.getFloatArray(0, slots * 3);
			float[] energies = hEnergy.getFloatArray(0, slots);
			float[] channels = hChan.getFloatArray(0, slots * 6);
			float[] highs = hHigh.getFloatArray(0, slots);
			int[] materials = hMaterial.getIntArray(0, slots);
			for (int i = 0; i < slots; i++) {
				float best = 0.0f;
				int bestChannel = -1;
				for (int c = 0; c < 6; c++) {
					float power = channels[i * 6 + c];
					if (power > best) {
						best = power;
						bestChannel = c;
					}
				}
				float gain = best > 1.0e-9f ? best : 0.0f;
				hits[i] = new Hit(delays[i], dirs[i * 3], dirs[i * 3 + 1],
					dirs[i * 3 + 2], energies[i],
					gain > 0.0f ? bestChannel : -1, gain,
					(i % bounces) + 1, highs[i], materials[i]);
			}
			return hits;
		} catch (Throwable t) {
			if (!warned) {
				warned = true;
				CinemaLog.warn(CinemaLog.AUDIO,
					"CUDA ray tracing failed: {}", t.toString());
			}
			return new Hit[0];
		} finally {
			CudaContext.releaseCurrent();
			CudaContext.unlock();
		}
	}

	private void uploadSpeakers(CudaContext.CudaDriver driver,
								List<Speaker> speakers, int count) {
		if (count <= 0) {
			return;
		}
		if (dSpeakers == 0 || count > speakerCapacity) {
			freeSpeakers(driver);
			speakerCapacity = Math.max(32, count);
			dSpeakers = alloc(driver, (long) speakerCapacity * 3L * Float.BYTES);
			dSpeakerChannels = alloc(driver,
				(long) speakerCapacity * Integer.BYTES);
			dSpeakerGains = alloc(driver,
				(long) speakerCapacity * Float.BYTES);
		}
		Memory hPos = new Memory((long) count * 3L * Float.BYTES);
		Memory hChan = new Memory((long) count * Integer.BYTES);
		Memory hGain = new Memory((long) count * Float.BYTES);
		for (int i = 0; i < count; i++) {
			Speaker speaker = speakers.get(i);
			hPos.setFloat((long) i * 3L * Float.BYTES, speaker.x());
			hPos.setFloat(((long) i * 3L + 1L) * Float.BYTES, speaker.y());
			hPos.setFloat(((long) i * 3L + 2L) * Float.BYTES, speaker.z());
			hChan.setInt((long) i * Integer.BYTES, speaker.channel());
			hGain.setFloat((long) i * Float.BYTES, speaker.gain());
		}
		CudaContext.check(driver.cuMemcpyHtoDV2(dSpeakers, hPos,
			(long) count * 3L * Float.BYTES), "cuMemcpyHtoD(spPos)");
		CudaContext.check(driver.cuMemcpyHtoDV2(dSpeakerChannels, hChan,
			(long) count * Integer.BYTES), "cuMemcpyHtoD(spChan)");
		CudaContext.check(driver.cuMemcpyHtoDV2(dSpeakerGains, hGain,
			(long) count * Float.BYTES), "cuMemcpyHtoD(spGain)");
	}

	private void ensureOutput(CudaContext.CudaDriver driver, int slots) {
		if (dDelay != 0 && slots <= outSlots) {
			return;
		}
		freeOutput(driver);
		outSlots = slots;
		dDelay = alloc(driver, (long) slots * Float.BYTES);
		dDir = alloc(driver, (long) slots * 3L * Float.BYTES);
		dEnergy = alloc(driver, (long) slots * Float.BYTES);
		dChan = alloc(driver, (long) slots * 6L * Float.BYTES);
		dHighOut = alloc(driver, (long) slots * Float.BYTES);
		dMaterialOut = alloc(driver, (long) slots * Integer.BYTES);
	}

	private static long alloc(CudaContext.CudaDriver driver, long bytes) {
		com.sun.jna.ptr.LongByReference reference =
			new com.sun.jna.ptr.LongByReference();
		CudaContext.check(driver.cuMemAllocV2(reference, bytes),
			"cuMemAlloc(" + bytes + ")");
		return reference.getValue();
	}

	private static Memory argLong(long value) {
		Memory memory = new Memory(Long.BYTES);
		memory.setLong(0, value);
		return memory;
	}

	private static Memory argInt(int value) {
		Memory memory = new Memory(Integer.BYTES);
		memory.setInt(0, value);
		return memory;
	}

	private static Memory argFloat(float value) {
		Memory memory = new Memory(Float.BYTES);
		memory.setFloat(0, value);
		return memory;
	}

	private static Memory pointerTable(Memory[] args) {
		int pointerSize = Native.POINTER_SIZE;
		Memory pointers = new Memory((long) pointerSize * args.length);
		for (int i = 0; i < args.length; i++) {
			pointers.setPointer((long) pointerSize * i, args[i]);
		}
		return pointers;
	}

	private void freeGrid(CudaContext.CudaDriver driver) {
		if (dSolid != 0) {
			driver.cuMemFreeV2(dSolid);
			dSolid = 0;
		}
		if (dReflect != 0) {
			driver.cuMemFreeV2(dReflect);
			dReflect = 0;
		}
		if (dScatter != 0) {
			driver.cuMemFreeV2(dScatter);
			dScatter = 0;
		}
		if (dHigh != 0) {
			driver.cuMemFreeV2(dHigh);
			dHigh = 0;
		}
		if (dMaterialGrid != 0) {
			driver.cuMemFreeV2(dMaterialGrid);
			dMaterialGrid = 0;
		}
		gridCells = 0;
	}

	private void freeOutput(CudaContext.CudaDriver driver) {
		if (dDelay != 0) {
			driver.cuMemFreeV2(dDelay);
			dDelay = 0;
		}
		if (dDir != 0) {
			driver.cuMemFreeV2(dDir);
			dDir = 0;
		}
		if (dEnergy != 0) {
			driver.cuMemFreeV2(dEnergy);
			dEnergy = 0;
		}
		if (dChan != 0) {
			driver.cuMemFreeV2(dChan);
			dChan = 0;
		}
		if (dHighOut != 0) {
			driver.cuMemFreeV2(dHighOut);
			dHighOut = 0;
		}
		if (dMaterialOut != 0) {
			driver.cuMemFreeV2(dMaterialOut);
			dMaterialOut = 0;
		}
		outSlots = 0;
	}

	private void freeSpeakers(CudaContext.CudaDriver driver) {
		if (dSpeakers != 0) {
			driver.cuMemFreeV2(dSpeakers);
			dSpeakers = 0;
		}
		if (dSpeakerChannels != 0) {
			driver.cuMemFreeV2(dSpeakerChannels);
			dSpeakerChannels = 0;
		}
		if (dSpeakerGains != 0) {
			driver.cuMemFreeV2(dSpeakerGains);
			dSpeakerGains = 0;
		}
		speakerCapacity = 0;
	}

	@Override
	public void close() {
		if (!available()) {
			return;
		}
		CudaContext.lock();
		try {
			CudaContext.setCurrent();
			CudaContext.CudaDriver driver = CudaContext.driver();
			freeGrid(driver);
			freeOutput(driver);
			freeSpeakers(driver);
		} catch (Throwable t) {
			CinemaLog.warn(CinemaLog.AUDIO, "CUDA ray tracer close failed: {}",
				t.toString());
		} finally {
			CudaContext.releaseCurrent();
			CudaContext.unlock();
		}
	}
}
