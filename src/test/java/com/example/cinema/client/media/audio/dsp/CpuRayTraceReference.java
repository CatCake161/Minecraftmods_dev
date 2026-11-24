package com.example.cinema.client.media.audio.dsp;

import com.example.cinema.client.media.audio.acoustics.geometry.AcousticVoxelGrid;

import java.util.List;

/**
 * Plain-Java reference implementation of the CUDA late-field kernel in
 * {@code docs/examples/cuda_audio/rt_trace.cu}.
 *
 * <p>It mirrors the kernel instruction for instruction: the same Fibonacci
 * ray set, the same one-block DDA, the same {@code reflect * sqrt(scatter)}
 * energy update, the same integer hash for the diffuse continuation and the
 * same last-bounce next-event estimation. All arithmetic stays in
 * {@code float}, in the same order as the kernel, so the result is a numeric
 * oracle the GPU output can be diffed against instead of trusting the ear.
 *
 * <p>The class is test-only on purpose: it never enters the client source set,
 * so it cannot drift into production code by accident.
 */
final class CpuRayTraceReference {
	private static final float PI = 3.14159265358979324f;
	private static final float AIR_EPS = 1.0e-3f;
	private static final float SPEED_OF_SOUND = 343.0f;
	private static final int CHANNELS = 6;

	private final byte[] solid;
	private final float[] reflect;
	private final float[] scatter;
	private final float[] high;
	private final int[] materialId;
	private final int sx;
	private final int sy;
	private final int sz;
	private final float ox;
	private final float oy;
	private final float oz;

	/** Reused DDA result, so the inner loops allocate nothing. */
	private final March march = new March();

	private CpuRayTraceReference(AcousticVoxelGrid grid) {
		this.solid = grid.solid;
		this.reflect = grid.reflect;
		this.scatter = grid.scatter;
		this.high = grid.high;
		this.materialId = grid.materialId;
		this.sx = grid.sizeX;
		this.sy = grid.sizeY;
		this.sz = grid.sizeZ;
		this.ox = grid.originX;
		this.oy = grid.originY;
		this.oz = grid.originZ;
	}

	/**
	 * Traces the same ray set as {@link CudaRayTracer#trace} and returns the
	 * same {@code rays * bounces} records in the same order.
	 */
	static CudaRayTracer.Hit[] trace(AcousticVoxelGrid grid,
									 float listenerX, float listenerY,
									 float listenerZ,
									 int rays, int bounces,
									 float maxDistanceMeters,
									 float metersPerBlock,
									 List<CudaRayTracer.Speaker> speakers) {
		return trace(grid, listenerX, listenerY, listenerZ, rays, bounces,
			maxDistanceMeters, metersPerBlock, speakers, 0.0f);
	}

	/**
	 * Same as {@link #trace(AcousticVoxelGrid, float, float, float, int, int,
	 * float, float, List)} but with the kernel's per-pass {@code rayOffset}, so
	 * a multi-pass accumulation can be validated without a GPU.
	 */
	static CudaRayTracer.Hit[] trace(AcousticVoxelGrid grid,
									 float listenerX, float listenerY,
									 float listenerZ,
									 int rays, int bounces,
									 float maxDistanceMeters,
									 float metersPerBlock,
									 List<CudaRayTracer.Speaker> speakers,
									 float rayOffset) {
		if (grid == null || rays <= 0 || bounces <= 0) {
			return new CudaRayTracer.Hit[0];
		}
		return new CpuRayTraceReference(grid).run(listenerX, listenerY,
			listenerZ, rays, bounces, maxDistanceMeters, metersPerBlock,
			speakers, rayOffset);
	}

	private CudaRayTracer.Hit[] run(float lx, float ly, float lz,
									int rays, int bounces,
									float maxDistanceMeters,
									float metersPerBlock,
									List<CudaRayTracer.Speaker> speakers,
									float rayOffset) {
		if (maxDistanceMeters <= 0.0f || !Float.isFinite(maxDistanceMeters)) {
			maxDistanceMeters = 64.0f;
		}
		if (!Float.isFinite(rayOffset)) {
			rayOffset = 0.0f;
		}
		rayOffset -= (float) Math.floor(rayOffset);
		float maxDistance = maxDistanceMeters
			/ Math.max(1.0e-4f, metersPerBlock);
		int spCount = speakers == null ? 0 : speakers.size();
		float[] spX = new float[spCount];
		float[] spY = new float[spCount];
		float[] spZ = new float[spCount];
		int[] spChan = new int[spCount];
		float[] spGain = new float[spCount];
		for (int s = 0; s < spCount; s++) {
			CudaRayTracer.Speaker speaker = speakers.get(s);
			spX[s] = speaker.x();
			spY[s] = speaker.y();
			spZ[s] = speaker.z();
			spChan[s] = speaker.channel();
			spGain[s] = speaker.gain();
		}

		int slots = rays * bounces;
		CudaRayTracer.Hit[] hits = new CudaRayTracer.Hit[slots];
		for (int slot = 0; slot < slots; slot++) {
			hits[slot] = new CudaRayTracer.Hit(0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
				-1, 0.0f, (slot % bounces) + 1, 0.0f, -1);
		}

		for (int i = 0; i < rays; i++) {
			// Deterministic Fibonacci sphere: full-sphere coverage without RNG,
			// rotated by rayOffset exactly like the kernel.
			float zShift = rayOffset - (float) Math.floor(rayOffset);
			if (zShift >= 0.5f) {
				zShift -= 1.0f;
			}
			float z0 = 1.0f - 2.0f
				* (((float) i + 0.5f + zShift) / (float) rays);
			z0 = Math.min(1.0f, Math.max(-1.0f, z0));
			float r0 = (float) Math.sqrt(Math.max(0.0f, 1.0f - z0 * z0));
			float phi0 = (float) i * 2.39996322972865332f
				+ rayOffset * 6.2831853071795864f;
			float dx = (float) Math.cos(phi0) * r0;
			float dy = (float) Math.sin(phi0) * r0;
			float dz = z0;

			float px = lx - ox;
			float py = ly - oy;
			float pz = lz - oz;
			float travelled = 0.0f;
			float energy = 1.0f;
			float highSum = 0.0f;

			for (int b = 0; b < bounces; b++) {
				if (!marchSolid(px, py, pz, dx, dy, dz, maxDistance, march)) {
					break;
				}
				float hitT = march.t;
				int cell = march.cell;
				float nx = march.nx;
				float ny = march.ny;
				float nz = march.nz;
				travelled += hitT;
				// Diffuse lobe only, exactly like the kernel.
				energy *= reflect[cell] * (float) Math.sqrt(scatter[cell]);
				if (energy < 1.0e-5f) {
					break;
				}

				float hx = px + dx * hitT;
				float hy = py + dy * hitT;
				float hz = pz + dz * hitT;
				int out = i * bounces + b;
				float delay = travelled * metersPerBlock / SPEED_OF_SOUND;
				float wx = hx + ox - lx;
				float wy = hy + oy - ly;
				float wz = hz + oz - lz;
				float length = (float) Math.sqrt(wx * wx + wy * wy + wz * wz);
				if (length < 1.0e-6f) {
					length = 1.0e-6f;
				}
				float dirX = wx / length;
				float dirY = wy / length;
				float dirZ = wz / length;
				// Average the high-band coefficient over the whole path.
				highSum += high[cell];
				float highOut = highSum / (float) (b + 1);
				int materialOut = materialId[cell];

				float best = 0.0f;
				int bestChannel = -1;
				// Next-event estimation: only the diffuse lobe connects to a
				// speaker, and only at the deepest bounce.
				if (b == bounces - 1) {
					float[] chan = new float[CHANNELS];
					float worldX = hx + ox;
					float worldY = hy + oy;
					float worldZ = hz + oz;
					float startX = worldX + nx * AIR_EPS;
					float startY = worldY + ny * AIR_EPS;
					float startZ = worldZ + nz * AIR_EPS;
					for (int s = 0; s < spCount; s++) {
						float vx = spX[s] - worldX;
						float vy = spY[s] - worldY;
						float vz = spZ[s] - worldZ;
						float dist2 = vx * vx + vy * vy + vz * vz;
						if (dist2 < 1.0e-6f) {
							continue;
						}
						float dist = (float) Math.sqrt(dist2);
						float inv = 1.0f / dist;
						float cosIn = (vx * nx + vy * ny + vz * nz) * inv;
						if (cosIn <= 0.0f) {
							continue;
						}
						if (!segmentClear(startX, startY, startZ,
							spX[s], spY[s], spZ[s])) {
							continue;
						}
						int channel = spChan[s];
						if (channel < 0 || channel >= CHANNELS) {
							continue;
						}
						float pathMeters = (travelled + dist) * metersPerBlock;
						float weight = energy * spGain[s] * cosIn
							/ (PI * Math.max(1.0f, pathMeters));
						chan[channel] += weight;
					}
					for (int c = 0; c < CHANNELS; c++) {
						if (chan[c] > best) {
							best = chan[c];
							bestChannel = c;
						}
					}
				}
				float gain = best > 1.0e-9f ? best : 0.0f;
				hits[out] = new CudaRayTracer.Hit(delay, dirX, dirY, dirZ,
					energy, gain > 0.0f ? bestChannel : -1, gain,
					b + 1, highOut, materialOut);

				// Diffuse continuation: mirror direction + cosine-weighted
				// offset, with the kernel's integer hash as the sample source.
				float dot = dx * nx + dy * ny + dz * nz;
				dx -= 2.0f * dot * nx;
				dy -= 2.0f * dot * ny;
				dz -= 2.0f * dot * nz;

				float tx;
				float ty;
				float tz;
				float ax = Math.abs(nx);
				float ay = Math.abs(ny);
				float az = Math.abs(nz);
				if (ax <= ay && ax <= az) {
					tx = 0.0f;
					ty = -nz;
					tz = ny;
				} else if (ay <= az) {
					tx = nz;
					ty = 0.0f;
					tz = -nx;
				} else {
					tx = -ny;
					ty = nx;
					tz = 0.0f;
				}
				float tlen = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
				if (tlen < 1.0e-6f) {
					tx = 1.0f;
					ty = 0.0f;
					tz = 0.0f;
					tlen = 1.0f;
				}
				tx /= tlen;
				ty /= tlen;
				tz /= tlen;
				float bx = ny * tz - nz * ty;
				float by = nz * tx - nx * tz;
				float bz = nx * ty - ny * tx;

				int h = i * 747796405 + b * (int) 2891336453L + 0x9E3779B9;
				h ^= h >>> 16;
				h *= 0x7feb352d;
				h ^= h >>> 15;
				h *= 0x846ca68b;
				h ^= h >>> 16;
				int h2 = h * (int) 2246822519L;
				h2 ^= h2 >>> 13;
				h2 *= (int) 3266489917L;
				h2 ^= h2 >>> 16;
				float u1 = (float) (h & 0x00ffffff) / 16777215.0f;
				float u2 = (float) (h2 & 0x00ffffff) / 16777215.0f;
				float rr = (float) Math.sqrt(u1);
				float pp = 6.2831853071795864f * u2;
				float ox2 = rr * (float) Math.cos(pp);
				float oy2 = rr * (float) Math.sin(pp);
				float oz2 = (float) Math.sqrt(Math.max(0.0f, 1.0f - u1));

				dx += tx * ox2 + bx * oy2 + nx * oz2;
				dy += ty * ox2 + by * oy2 + ny * oz2;
				dz += tz * ox2 + bz * oy2 + nz * oz2;
				float nlen = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
				if (nlen < 1.0e-6f || nlen != nlen) {
					break;
				}
				dx /= nlen;
				dy /= nlen;
				dz /= nlen;

				px = hx + nx * AIR_EPS;
				py = hy + ny * AIR_EPS;
				pz = hz + nz * AIR_EPS;
			}
		}
		return hits;
	}

	/** One-block DDA, same tie-breaking and epsilon rules as the kernel. */
	private boolean marchSolid(float px, float py, float pz,
							   float dx, float dy, float dz,
							   float maxDistance, March out) {
		int ix = (int) Math.floor(px);
		int iy = (int) Math.floor(py);
		int iz = (int) Math.floor(pz);
		if (ix < 0 || iy < 0 || iz < 0 || ix >= sx || iy >= sy || iz >= sz) {
			return false;
		}
		int stepX = dx > 0.0f ? 1 : -1;
		int stepY = dy > 0.0f ? 1 : -1;
		int stepZ = dz > 0.0f ? 1 : -1;
		float tDeltaX = Math.abs(dx) < 1.0e-9f ? 1.0e30f
			: Math.abs(1.0f / dx);
		float tDeltaY = Math.abs(dy) < 1.0e-9f ? 1.0e30f
			: Math.abs(1.0f / dy);
		float tDeltaZ = Math.abs(dz) < 1.0e-9f ? 1.0e30f
			: Math.abs(1.0f / dz);
		float tMaxX = Math.abs(dx) < 1.0e-9f ? 1.0e30f
			: ((dx > 0.0f ? (ix + 1.0f - px) : (px - ix)) / Math.abs(dx));
		float tMaxY = Math.abs(dy) < 1.0e-9f ? 1.0e30f
			: ((dy > 0.0f ? (iy + 1.0f - py) : (py - iy)) / Math.abs(dy));
		float tMaxZ = Math.abs(dz) < 1.0e-9f ? 1.0e30f
			: ((dz > 0.0f ? (iz + 1.0f - pz) : (pz - iz)) / Math.abs(dz));
		for (int guard = 0; guard < 4096; guard++) {
			float t;
			if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
				ix += stepX;
				t = tMaxX;
				tMaxX += tDeltaX;
				out.nx = -(float) stepX;
				out.ny = 0.0f;
				out.nz = 0.0f;
			} else if (tMaxY <= tMaxZ) {
				iy += stepY;
				t = tMaxY;
				tMaxY += tDeltaY;
				out.nx = 0.0f;
				out.ny = -(float) stepY;
				out.nz = 0.0f;
			} else {
				iz += stepZ;
				t = tMaxZ;
				tMaxZ += tDeltaZ;
				out.nx = 0.0f;
				out.ny = 0.0f;
				out.nz = -(float) stepZ;
			}
			if (t > maxDistance) {
				return false;
			}
			if (ix < 0 || iy < 0 || iz < 0 || ix >= sx || iy >= sy
				|| iz >= sz) {
				return false;
			}
			int index = (iz * sy + iy) * sx + ix;
			if (solid[index] != 0) {
				out.t = t;
				out.cell = index;
				return true;
			}
		}
		return false;
	}

	/** True when the straight segment hits no solid cell. */
	private boolean segmentClear(float x0, float y0, float z0,
								 float x1, float y1, float z1) {
		float vx = x1 - x0;
		float vy = y1 - y0;
		float vz = z1 - z0;
		float distance = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
		if (distance < 1.0e-4f) {
			return true;
		}
		return !marchSolid(x0, y0, z0, vx / distance, vy / distance,
			vz / distance, distance - 1.0e-4f, march);
	}

	private static final class March {
		private float t;
		private int cell;
		private float nx;
		private float ny;
		private float nz;
	}
}
