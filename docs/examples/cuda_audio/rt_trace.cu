// Listener-centered voxel ray tracer for the late-reflection field.
//
// Compile offline with NVRTC (`GenRtPtx.java`) and embed the resulting PTX as
// `/assets/cinema/audio/rt_trace.ptx`. No CUDA Toolkit is required at runtime.
//
// Grid: one byte per block (0 = air, 1 = solid), one float reflection
// amplitude and one float scattering coefficient per block.
//
// Each pass traces a deterministic Fibonacci sphere from the listener. The
// affine `rayOffset` argument (advanced by the golden ratio on the host)
// rotates that sphere per pass so several passes can be accumulated without
// introducing an RNG. At every
// wall hit it performs next-event estimation: for each speaker that is on the
// front side of the wall and unoccluded, the diffuse lobe of that hit feeds the
// speaker's channel. Only the diffuse lobe is evaluated (the exact image solver
// owns the specular part), so the two never double count.
//
// Output per (ray, bounce):
//   delay  = travelled distance * metersPerBlock / speedOfSound
//   dir    = world-space listener -> hit unit vector (VBAP input)
//   energy = accumulated diffuse amplitude at the hit (diagnostics)
//   chan   = 6 channel powers from next-event estimation

#define PI_F 3.14159265358979324f
#define AIR_EPS 1.0e-3f

__device__ __forceinline__ bool marchSolid(
	const unsigned char* __restrict__ solid,
	int sx, int sy, int sz,
	float px, float py, float pz,
	float dx, float dy, float dz,
	float maxDistance,
	float* outT, int* outCell, float* outNx, float* outNy, float* outNz)
{
	int ix = (int)floorf(px);
	int iy = (int)floorf(py);
	int iz = (int)floorf(pz);
	if (ix < 0 || iy < 0 || iz < 0 || ix >= sx || iy >= sy || iz >= sz) {
		return false;
	}
	int stepX = dx > 0.0f ? 1 : -1;
	int stepY = dy > 0.0f ? 1 : -1;
	int stepZ = dz > 0.0f ? 1 : -1;
	float tDeltaX = fabsf(dx) < 1.0e-9f ? 1.0e30f : fabsf(1.0f / dx);
	float tDeltaY = fabsf(dy) < 1.0e-9f ? 1.0e30f : fabsf(1.0f / dy);
	float tDeltaZ = fabsf(dz) < 1.0e-9f ? 1.0e30f : fabsf(1.0f / dz);
	float tMaxX = fabsf(dx) < 1.0e-9f ? 1.0e30f
		: ((dx > 0.0f ? (ix + 1.0f - px) : (px - ix)) / fabsf(dx));
	float tMaxY = fabsf(dy) < 1.0e-9f ? 1.0e30f
		: ((dy > 0.0f ? (iy + 1.0f - py) : (py - iy)) / fabsf(dy));
	float tMaxZ = fabsf(dz) < 1.0e-9f ? 1.0e30f
		: ((dz > 0.0f ? (iz + 1.0f - pz) : (pz - iz)) / fabsf(dz));
	for (int guard = 0; guard < 4096; guard++) {
		float t;
		if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
			ix += stepX;
			t = tMaxX;
			tMaxX += tDeltaX;
			*outNx = -(float)stepX;
			*outNy = 0.0f;
			*outNz = 0.0f;
		} else if (tMaxY <= tMaxZ) {
			iy += stepY;
			t = tMaxY;
			tMaxY += tDeltaY;
			*outNx = 0.0f;
			*outNy = -(float)stepY;
			*outNz = 0.0f;
		} else {
			iz += stepZ;
			t = tMaxZ;
			tMaxZ += tDeltaZ;
			*outNx = 0.0f;
			*outNy = 0.0f;
			*outNz = -(float)stepZ;
		}
		if (t > maxDistance) {
			return false;
		}
		if (ix < 0 || iy < 0 || iz < 0 || ix >= sx || iy >= sy || iz >= sz) {
			return false;
		}
		int index = (iz * sy + iy) * sx + ix;
		if (solid[index] != 0) {
			*outT = t;
			*outCell = index;
			return true;
		}
	}
	return false;
}

__device__ __forceinline__ bool segmentClear(
	const unsigned char* __restrict__ solid,
	int sx, int sy, int sz,
	float x0, float y0, float z0,
	float x1, float y1, float z1)
{
	float vx = x1 - x0;
	float vy = y1 - y0;
	float vz = z1 - z0;
	float distance = sqrtf(vx * vx + vy * vy + vz * vz);
	if (distance < 1.0e-4f) {
		return true;
	}
	float t;
	int cell;
	float nx;
	float ny;
	float nz;
	return !marchSolid(solid, sx, sy, sz, x0, y0, z0,
		vx / distance, vy / distance, vz / distance, distance - 1.0e-4f,
		&t, &cell, &nx, &ny, &nz);
}

extern "C" __global__ void rt_trace(
	const unsigned char* __restrict__ solid,
	const float* __restrict__ reflect,
	const float* __restrict__ scatter,
	const float* __restrict__ highBand,
	const int* __restrict__ materialId,
	int sx, int sy, int sz,
	float ox, float oy, float oz,
	float lx, float ly, float lz,
	const float* __restrict__ spPos,
	const int* __restrict__ spChan,
	const float* __restrict__ spGain,
	int spCount,
	int rayCount, int bounces, float maxDistance,
	// Per-pass decorrelation of the Fibonacci lattice (Cranley-Patterson):
	// the host advances it by the golden ratio every pass, so K accumulated
	// passes cover K times the directions without any RNG. 0 = base set.
	float rayOffset,
	float metersPerBlock, float speedOfSound,
	float* __restrict__ outDelay,
	float* __restrict__ outDir,
	float* __restrict__ outEnergy,
	float* __restrict__ outChan,
	float* __restrict__ outHigh,
	int* __restrict__ outMaterial)
{
	int i = blockIdx.x * blockDim.x + threadIdx.x;
	if (i >= rayCount) {
		return;
	}
	for (int b = 0; b < bounces; b++) {
		int out = i * bounces + b;
		outDelay[out] = 0.0f;
		outDir[out * 3 + 0] = 0.0f;
		outDir[out * 3 + 1] = 0.0f;
		outDir[out * 3 + 2] = 0.0f;
		outEnergy[out] = 0.0f;
		outHigh[out] = 0.0f;
		outMaterial[out] = -1;
		for (int c = 0; c < 6; c++) {
			outChan[out * 6 + c] = 0.0f;
		}
	}

	// Deterministic Fibonacci sphere: full-sphere coverage without RNG.
	// rayOffset rotates the spiral and shifts the z lattice by less than half
	// a step, so a single pass stays stratified and offset 0 reproduces the
	// original fixed set exactly. The phase is built with explicit rn
	// intrinsics so the compiler cannot contract it into an FMA: the CPU
	// reference in the tests must stay bit-comparable through the chaotic
	// diffuse bounces.
	float zShift = rayOffset - floorf(rayOffset);
	if (zShift >= 0.5f) {
		zShift -= 1.0f;
	}
	float z0 = 1.0f - 2.0f * (((float)i + 0.5f + zShift) / (float)rayCount);
	z0 = fminf(1.0f, fmaxf(-1.0f, z0));
	float r0 = sqrtf(fmaxf(0.0f, 1.0f - z0 * z0));
	float phi0 = __fadd_rn(
		__fmul_rn((float)i, 2.39996322972865332f),
		__fmul_rn(rayOffset, 6.2831853071795864f));
	float dx = cosf(phi0) * r0;
	float dy = sinf(phi0) * r0;
	float dz = z0;

	float px = lx - ox;
	float py = ly - oy;
	float pz = lz - oz;
	float travelled = 0.0f;
	float energy = 1.0f;
	float highSum = 0.0f;

	for (int b = 0; b < bounces; b++) {
		float hitT;
		int cell;
		float nx;
		float ny;
		float nz;
		if (!marchSolid(solid, sx, sy, sz, px, py, pz, dx, dy, dz,
			maxDistance, &hitT, &cell, &nx, &ny, &nz)) {
			break;
		}
		travelled += hitT;
		// Diffuse lobe only. sqrt(1 - alpha) * sqrt(scattering) is the same
		// scattered-pressure coefficient the exact solver uses; multiplying by
		// the raw scattering value would add an extra sqrt per bounce.
		energy *= reflect[cell] * sqrtf(scatter[cell]);
		if (energy < 1.0e-5f) {
			break;
		}

		float hx = px + dx * hitT;
		float hy = py + dy * hitT;
		float hz = pz + dz * hitT;
		int out = i * bounces + b;
		outDelay[out] = travelled * metersPerBlock / speedOfSound;
		float wx = hx + ox - lx;
		float wy = hy + oy - ly;
		float wz = hz + oz - lz;
		float length = sqrtf(wx * wx + wy * wy + wz * wz);
		if (length < 1.0e-6f) {
			length = 1.0e-6f;
		}
		outDir[out * 3 + 0] = wx / length;
		outDir[out * 3 + 1] = wy / length;
		outDir[out * 3 + 2] = wz / length;
		outEnergy[out] = energy;
		// Average the high-band coefficient over the whole path: the exact
		// solver averages Tap.fromPath materials, not just the last hit.
		highSum += highBand[cell];
		outHigh[out] = highSum / (float)(b + 1);
		outMaterial[out] = materialId[cell];

		// Next-event estimation: only the diffuse lobe connects to a speaker.
		// The image solver owns the specular part, so this runs once at the
		// deepest bounce that participates in the late field.
		if (b == bounces - 1) {
			float worldX = hx + ox;
			float worldY = hy + oy;
			float worldZ = hz + oz;
			float startX = worldX + nx * AIR_EPS;
			float startY = worldY + ny * AIR_EPS;
			float startZ = worldZ + nz * AIR_EPS;
			for (int s = 0; s < spCount; s++) {
				float sxw = spPos[s * 3 + 0];
				float syw = spPos[s * 3 + 1];
				float szw = spPos[s * 3 + 2];
				float vx = sxw - worldX;
				float vy = syw - worldY;
				float vz = szw - worldZ;
				float dist2 = vx * vx + vy * vy + vz * vz;
				if (dist2 < 1.0e-6f) {
					continue;
				}
				float dist = sqrtf(dist2);
				float inv = 1.0f / dist;
				float cosIn = (vx * nx + vy * ny + vz * nz) * inv;
				if (cosIn <= 0.0f) {
					continue;
				}
				if (!segmentClear(solid, sx, sy, sz, startX, startY, startZ,
					sxw, syw, szw)) {
					continue;
				}
				int channel = spChan[s];
				if (channel < 0 || channel >= 6) {
					continue;
				}
				// Lambertian BRDF (1/pi) * cos, and the same 1/max(1, m)
				// distance normalization the exact late taps use. Using 1/r^2
				// here made the tail tens of dB hotter than the image solver.
				float pathMeters = (travelled + dist) * metersPerBlock;
				float weight = energy * spGain[s] * cosIn
					/ (PI_F * fmaxf(1.0f, pathMeters));
				outChan[out * 6 + channel] += weight;
			}
		}

		// Diffuse continuation: mirror direction + cosine-weighted offset.
		float dot = dx * nx + dy * ny + dz * nz;
		dx -= 2.0f * dot * nx;
		dy -= 2.0f * dot * ny;
		dz -= 2.0f * dot * nz;

		float tx;
		float ty;
		float tz;
		float ax = fabsf(nx);
		float ay = fabsf(ny);
		float az = fabsf(nz);
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
		float tlen = sqrtf(tx * tx + ty * ty + tz * tz);
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

		unsigned int h = (unsigned int)i * 747796405u
			+ (unsigned int)b * 2891336453u + 0x9E3779B9u;
		h ^= h >> 16; h *= 0x7feb352dU; h ^= h >> 15; h *= 0x846ca68bU;
		h ^= h >> 16;
		unsigned int h2 = h * 2246822519u;
		h2 ^= h2 >> 13; h2 *= 3266489917u; h2 ^= h2 >> 16;
		float u1 = (float)(h & 0x00ffffffu) / 16777215.0f;
		float u2 = (float)(h2 & 0x00ffffffu) / 16777215.0f;
		float rr = sqrtf(u1);
		float pp = 6.2831853071795864f * u2;
		float ox2 = rr * cosf(pp);
		float oy2 = rr * sinf(pp);
		float oz2 = sqrtf(fmaxf(0.0f, 1.0f - u1));

		dx += tx * ox2 + bx * oy2 + nx * oz2;
		dy += ty * ox2 + by * oy2 + ny * oz2;
		dz += tz * ox2 + bz * oy2 + nz * oz2;
		float nlen = sqrtf(dx * dx + dy * dy + dz * dz);
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
