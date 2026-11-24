package com.example.cinema.client.media.audio.reflection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Dynamic wet output layout: physical speakers plus reflection ghost speakers.
 *
 * <p>The geometric late bus used to render six logical 5.1 channels and play
 * each one from a single speaker anchor. That made VBAP incomplete: a
 * reflection arriving from a direction outside the physical speaker hull fell
 * back to horizontal/nearest/diffuse, and a channel with several speakers only
 * produced wet from its anchor.
 *
 * <p>This layout adds virtual ghost speakers at the energy-weighted arrival
 * directions of the active taps for orders 1-4. VBAP pans onto the union of
 * physical speakers and ghosts, so every active reflection direction is inside
 * the target hull. Each point is played by one wet OpenAL source.
 *
 * <p>Ghost points are clustered per order, capped, smoothed and held briefly
 * so their count and positions do not flicker at the 5 Hz reflection cadence.
 * Clustering and smoothing run in world space, so a static reflection stays
 * fixed when the listener turns. {@link Layout#positions()} is listener space
 * for OpenAL/VBAP; {@link Layout#worldPositions()} is for debug rendering.
 * A held but currently silent ghost is kept as an inactive point; it does not
 * receive VBAP energy but its source slot stays stable.
 */
public final class WetLayout {
	public static final int MAX_POINTS = 24;
	public static final int MAX_GHOSTS_PER_ORDER = 6;
	public static final int MAX_ORDER = 4;
	private static final double MIN_SEPARATION_RADIANS = Math.toRadians(20.0);
	private static final double MATCH_SEPARATION_RADIANS = Math.toRadians(30.0);
	private static final double MIN_ENERGY_RATIO = 1.0e-3;
	private static final double SMOOTHING_SECONDS = 0.25;
	private static final long HOLD_MILLIS = 800L;
	private static final float DEFAULT_GHOST_RADIUS = 2.0f;

	public enum Kind {
		SPEAKER,
		LFE,
		GHOST
	}

	/**
	 * Listener frame used to convert between world space (fixed room) and
	 * listener space (what OpenAL and VBAP consume). Ghost clustering and
	 * smoothing happen in world space so a static reflection stays fixed when
	 * the player turns.
	 */
	public record Frame(float[] origin, float[] right, float[] up,
						float[] forward) {
		public Frame {
			origin = origin == null ? new float[3] : origin.clone();
			right = right == null ? new float[]{1.0f, 0.0f, 0.0f}
				: right.clone();
			up = up == null ? new float[]{0.0f, 1.0f, 0.0f} : up.clone();
			forward = forward == null ? new float[]{0.0f, 0.0f, -1.0f}
				: forward.clone();
		}

		public static Frame identity() {
			return new Frame(new float[3], new float[]{1.0f, 0.0f, 0.0f},
				new float[]{0.0f, 1.0f, 0.0f},
				new float[]{0.0f, 0.0f, -1.0f});
		}

		public float[] toWorld(float[] local) {
			float[] direction = toWorldDirection(local);
			return new float[]{
				origin[0] + direction[0],
				origin[1] + direction[1],
				origin[2] + direction[2]};
		}

		public float[] toWorldDirection(float[] local) {
			float x = local == null || local.length < 3 ? 0.0f : local[0];
			float y = local == null || local.length < 3 ? 0.0f : local[1];
			float z = local == null || local.length < 3 ? 0.0f : local[2];
			return new float[]{
				right[0] * x + up[0] * y - forward[0] * z,
				right[1] * x + up[1] * y - forward[1] * z,
				right[2] * x + up[2] * y - forward[2] * z};
		}

		public float[] toLocal(float[] world) {
			float rx = world[0] - origin[0];
			float ry = world[1] - origin[1];
			float rz = world[2] - origin[2];
			return new float[]{
				rx * right[0] + ry * right[1] + rz * right[2],
				ry,
				-(rx * forward[0] + ry * forward[1] + rz * forward[2])};
		}

		public float[] toLocalDirection(float[] world) {
			if (world == null || world.length < 3) {
				return new float[3];
			}
			return new float[]{
				world[0] * right[0] + world[1] * right[1]
					+ world[2] * right[2],
				world[1],
				-(world[0] * forward[0] + world[1] * forward[1]
					+ world[2] * forward[2])};
		}
	}

	public record Point(int index, float[] position, Kind kind, int order,
						boolean active) {
	}

	private record GhostSource(int order, float[] direction, double energy) {
	}

	private record Cluster(int order, float[] direction, double energy) {
	}

	private static final class GhostTrack {
		private final int order;
		private final int slot;
		private final float[] direction = new float[3];
		private double energy;
		private long lastSeenMillis;

		private GhostTrack(int order, int slot, float[] direction,
						   long nowMillis) {
			this.order = order;
			this.slot = slot;
			Builder.normalizeInto(direction, this.direction);
			this.lastSeenMillis = nowMillis;
		}
	}

	/** Immutable result published to the audio engine. */
	public static final class Layout {
		private final float[][] worldPositions;
		private final float[][] positions;
		private final boolean[] active;
		private final int[] orders;
		private final int physicalCount;
		private final int[] lfeIndices;
		private final int[] activeIndices;
		private final Vbap.Panner allPanner;
		private final Vbap.Panner physicalPanner;
		private final int[] physicalIndices;

		private Layout(float[][] worldPositions, float[][] positions,
					   boolean[] active, int[] orders, int physicalCount,
					   int[] lfeIndices, int[] activeIndices,
					   Vbap.Panner allPanner, Vbap.Panner physicalPanner,
					   int[] physicalIndices) {
			this.worldPositions = worldPositions;
			this.positions = positions;
			this.active = active;
			this.orders = orders;
			this.physicalCount = physicalCount;
			this.lfeIndices = lfeIndices;
			this.activeIndices = activeIndices;
			this.allPanner = allPanner;
			this.physicalPanner = physicalPanner;
			this.physicalIndices = physicalIndices;
		}

		private static Layout empty() {
			return new Layout(new float[0][], new float[0][], new boolean[0],
				new int[0], 0, new int[0], new int[0], null, null, new int[0]);
		}

		public int pointCount() {
			return positions.length;
		}

		public int physicalCount() {
			return physicalCount;
		}

		/** First dedicated LFE wet index for compatibility, or -1. */
		public int lfeIndex() {
			return lfeIndices.length == 0 ? -1 : lfeIndices[0];
		}

		public int[] lfeIndices() {
			return lfeIndices.clone();
		}

		public boolean isLfeIndex(int index) {
			for (int lfe : lfeIndices) {
				if (lfe == index) {
					return true;
				}
			}
			return false;
		}

		public int activeCount() {
			return activeIndices.length;
		}

		public float[][] positions() {
			return positions;
		}

		/** Fixed room/world positions for debug rendering. */
		public float[][] worldPositions() {
			return worldPositions;
		}

		/**
		 * Re-derives listener-space positions for a new listener frame. The
		 * point set is rigid, so VBAP weights stay valid; only the OpenAL
		 * source positions have to move.
		 */
		public float[][] localPositions(Frame frame) {
			Frame basis = frame == null ? Frame.identity() : frame;
			float[][] local = new float[worldPositions.length][];
			for (int index = 0; index < worldPositions.length; index++) {
				float[] world = worldPositions[index];
				local[index] = world == null
					? new float[3] : basis.toLocal(world);
			}
			return local;
		}

		public boolean isActive(int index) {
			return index >= 0 && index < active.length && active[index];
		}

		public List<Point> points() {
			List<Point> points = new ArrayList<>(worldPositions.length);
			for (int index = 0; index < worldPositions.length; index++) {
				Kind kind = isLfeIndex(index) ? Kind.LFE
					: index < physicalCount ? Kind.SPEAKER : Kind.GHOST;
				points.add(new Point(index, worldPositions[index].clone(), kind,
					index < orders.length ? orders[index] : 0, active[index]));
			}
			return List.copyOf(points);
		}

		public int order(int index) {
			return index >= 0 && index < orders.length ? orders[index] : 0;
		}

		/**
		 * Pans one direction. Diffuse (zero-direction) taps should request the
		 * physical-only set so scattered energy does not fill ghost speakers.
		 */
		public Vbap.Weights pan(float[] direction, boolean includeGhosts) {
			Vbap.Panner panner = includeGhosts ? allPanner : physicalPanner;
			int[] mapping = includeGhosts ? activeIndices : physicalIndices;
			if (panner == null || mapping.length == 0) {
				return new Vbap.Weights(new int[0], new float[0]);
			}
			Vbap.Weights weights = panner.pan(direction);
			if (weights.isEmpty()) {
				return weights;
			}
			int[] channels = new int[weights.channels().length];
			for (int i = 0; i < channels.length; i++) {
				int compact = weights.channels()[i];
				channels[i] = compact >= 0 && compact < mapping.length
					? mapping[compact] : -1;
			}
			return new Vbap.Weights(channels, weights.weights());
		}
	}

	/** Stateful builder; call from the client thread only. */
	public static final class Builder {
		private final List<GhostTrack> tracks = new ArrayList<>();
		private boolean initialized;
		private long lastBuildMillis;

		public Layout build(List<LateReflectionBus.Tap> taps,
							float[][] speakerWorldPositions,
							int[] speakerChannels, long nowMillis) {
			return build(taps, speakerWorldPositions, speakerChannels,
				Frame.identity(), nowMillis);
		}

		/**
		 * Builds a layout from world-space speaker positions and active taps.
		 * Tap directions are listener-relative and are converted through
		 * {@code frame}; ghost clustering/smoothing stays in world space so a
		 * fixed reflection does not rotate with the player's head.
		 */
		public Layout build(List<LateReflectionBus.Tap> taps,
							float[][] speakerWorldPositions,
							int[] speakerChannels, Frame frame,
							long nowMillis) {
			if (taps == null || taps.isEmpty()) {
				clear();
				return Layout.empty();
			}
			Frame basis = frame == null ? Frame.identity() : frame;
			List<float[]> physicalWorld = new ArrayList<>();
			List<float[]> lfeWorlds = new ArrayList<>();
			int speakerCount = speakerWorldPositions == null ? 0
				: speakerWorldPositions.length;
			for (int index = 0; index < speakerCount; index++) {
				int channel = speakerChannels != null
					&& index < speakerChannels.length
					? speakerChannels[index] : index;
				if (channel < 0) {
					continue;
				}
				float[] position = speakerWorldPositions[index];
				if (!validPosition(position)) {
					continue;
				}
				if (channel == Vbap.LFE_CHANNEL) {
					lfeWorlds.add(position.clone());
					continue;
				}
				physicalWorld.add(position.clone());
				if (physicalWorld.size() >= MAX_POINTS) {
					break;
				}
			}
			int nonLfeCapacity = MAX_POINTS - Math.min(lfeWorlds.size(),
				MAX_POINTS);
			int physicalCount = Math.min(physicalWorld.size(), nonLfeCapacity);
			int lfeCount = Math.min(lfeWorlds.size(),
				MAX_POINTS - physicalCount);
			int ghostCapacity = Math.max(0,
				MAX_POINTS - physicalCount - lfeCount);
			int[] lfeIndices = new int[lfeCount];
			float radius = ghostRadius(physicalWorld, basis.origin());
			List<GhostSource> sources = ghostSources(taps);
			List<Cluster> clusters = cluster(sources, ghostCapacity);
			updateTracks(clusters, ghostCapacity, nowMillis);
			boolean[] selected = selectGhosts(ghostCapacity);
			float[][] worldPositions =
				new float[physicalCount + lfeCount + ghostCapacity][];
			float[][] localPositions =
				new float[physicalCount + lfeCount + ghostCapacity][];
			boolean[] active = new boolean[worldPositions.length];
			int[] orders = new int[worldPositions.length];
			for (int index = 0; index < physicalCount; index++) {
				worldPositions[index] = physicalWorld.get(index);
				localPositions[index] = basis.toLocal(worldPositions[index]);
				active[index] = true;
			}
			for (int index = 0; index < lfeCount; index++) {
				int target = physicalCount + index;
				lfeIndices[index] = target;
				worldPositions[target] = lfeWorlds.get(index);
				localPositions[target] = basis.toLocal(worldPositions[target]);
				active[target] = true;
			}
			int ghostBase = physicalCount + lfeCount;
			for (GhostTrack track : tracks) {
				int index = ghostBase + track.slot;
				if (index >= worldPositions.length) {
					continue;
				}
				float[] world = new float[]{
					basis.origin()[0] + track.direction[0] * radius,
					basis.origin()[1] + track.direction[1] * radius,
					basis.origin()[2] + track.direction[2] * radius};
				worldPositions[index] = world;
				localPositions[index] = basis.toLocal(world);
				active[index] = selected[track.slot];
				orders[index] = track.order;
			}
			float[] defaultWorld = basis.toWorld(new float[]{0.0f, 0.0f,
				-radius});
			float[] defaultLocal = basis.toLocal(defaultWorld);
			for (int index = 0; index < worldPositions.length; index++) {
				if (worldPositions[index] == null) {
					worldPositions[index] = defaultWorld.clone();
					localPositions[index] = defaultLocal.clone();
				}
			}
			return createLayout(worldPositions, localPositions, active, orders,
				physicalCount, lfeIndices);
		}

		public void clear() {
			tracks.clear();
			initialized = false;
			lastBuildMillis = 0L;
		}

		private Layout createLayout(float[][] worldPositions,
									float[][] positions, boolean[] active,
									int[] orders, int physicalCount,
									int[] lfeIndices) {
			boolean[] lfeMask = new boolean[positions.length];
			for (int index : lfeIndices) {
				if (index >= 0 && index < lfeMask.length) {
					lfeMask[index] = true;
				}
			}
			int activeCount = 0;
			for (int index = 0; index < active.length; index++) {
				if (active[index] && !lfeMask[index]) {
					activeCount++;
				}
			}
			int[] activeIndices = new int[activeCount];
			float[][] activePositions = new float[activeCount][];
			int cursor = 0;
			for (int index = 0; index < positions.length; index++) {
				if (!active[index] || lfeMask[index]) {
					continue;
				}
				activeIndices[cursor] = index;
				activePositions[cursor] = positions[index];
				cursor++;
			}
			int physicalActive = Math.min(physicalCount, activeCount);
			int[] physicalIndices = new int[physicalActive];
			float[][] physicalActivePositions = new float[physicalActive][];
			for (int index = 0; index < physicalActive; index++) {
				physicalIndices[index] = index;
				physicalActivePositions[index] = positions[index];
			}
			Vbap.Panner allPanner = activePositions.length == 0 ? null
				: Vbap.pannerPoints(activePositions);
			Vbap.Panner physicalPanner = physicalActivePositions.length == 0
				? null : Vbap.pannerPoints(physicalActivePositions);
			return new Layout(worldPositions, positions, active, orders,
				physicalCount, lfeIndices, activeIndices, allPanner,
				physicalPanner, physicalIndices);
		}

		private List<GhostSource> ghostSources(
			List<LateReflectionBus.Tap> taps) {
			List<GhostSource> sources = new ArrayList<>();
			for (LateReflectionBus.Tap tap : taps) {
				if (tap == null || tap.order() <= 0 || tap.order() > MAX_ORDER
					|| tap.gain() <= 0.0) {
					continue;
				}
				// Tap directions are world space, so clustering and smoothing
				// stay fixed to the room when the player turns their head.
				float[] direction = tap.direction();
				if (direction == null || direction.length < 3) {
					continue;
				}
				double length = Math.sqrt(direction[0] * direction[0]
					+ direction[1] * direction[1]
					+ direction[2] * direction[2]);
				if (!Double.isFinite(length) || length < 1.0e-6) {
					continue;
				}
				float[] world = {(float) (direction[0] / length),
					(float) (direction[1] / length),
					(float) (direction[2] / length)};
				sources.add(new GhostSource(tap.order(), world,
					tap.gain() * tap.gain()));
			}
			return sources;
		}

		private List<Cluster> cluster(List<GhostSource> sources, int capacity) {
			List<Cluster> clusters = new ArrayList<>();
			if (capacity <= 0) {
				return clusters;
			}
			sources.sort(Comparator.comparingDouble(
				(GhostSource source) -> source.energy()).reversed());
			for (int order = 1; order <= MAX_ORDER; order++) {
				List<Cluster> byOrder = new ArrayList<>();
				for (GhostSource source : sources) {
					if (source.order() != order) {
						continue;
					}
					int nearest = nearestCluster(byOrder, source.direction());
					double distance = nearest < 0 ? Double.MAX_VALUE
						: angularDistance(byOrder.get(nearest).direction(),
							source.direction());
					if (nearest >= 0 && (distance < MIN_SEPARATION_RADIANS
						|| byOrder.size() >= MAX_GHOSTS_PER_ORDER)) {
						byOrder.set(nearest, merge(byOrder.get(nearest),
							source));
					} else if (byOrder.size() < MAX_GHOSTS_PER_ORDER) {
						byOrder.add(new Cluster(order,
							source.direction().clone(), source.energy()));
					} else if (nearest >= 0) {
						byOrder.set(nearest, merge(byOrder.get(nearest),
							source));
					}
				}
				clusters.addAll(byOrder);
			}
			return clusters;
		}

		private void updateTracks(List<Cluster> clusters, int capacity,
								  long nowMillis) {
			boolean[] used = new boolean[tracks.size()];
			boolean first = !initialized;
			double dt = first ? 0.0
				: Math.max(0.0, (nowMillis - lastBuildMillis) / 1_000.0);
			double alpha = first ? 1.0
				: Math.max(0.15, Math.min(0.8,
					1.0 - Math.exp(-dt / SMOOTHING_SECONDS)));
			initialized = true;
			lastBuildMillis = nowMillis;
			for (GhostTrack track : tracks) {
				track.energy = 0.0;
			}
			for (Cluster cluster : clusters) {
				int nearest = -1;
				double best = MATCH_SEPARATION_RADIANS;
				for (int index = 0; index < tracks.size(); index++) {
					if (used[index] || tracks.get(index).order
						!= cluster.order()) {
						continue;
					}
					double distance = angularDistance(
						tracks.get(index).direction, cluster.direction());
					if (distance < best) {
						best = distance;
						nearest = index;
					}
				}
				if (nearest >= 0) {
					GhostTrack track = tracks.get(nearest);
					used[nearest] = true;
					smoothInto(track.direction, cluster.direction(),
						alpha);
					track.energy = cluster.energy();
					track.lastSeenMillis = nowMillis;
				} else if (capacity > 0) {
					int slot = freeSlot(capacity);
					if (slot >= 0) {
						tracks.add(new GhostTrack(cluster.order(),
							slot, cluster.direction(), nowMillis));
						used = Arrays.copyOf(used, tracks.size());
						used[used.length - 1] = true;
					}
				}
			}
			for (int index = tracks.size() - 1; index >= 0; index--) {
				GhostTrack track = tracks.get(index);
				if (!used[index]
					&& nowMillis - track.lastSeenMillis > HOLD_MILLIS) {
					tracks.remove(index);
				}
			}
		}

		private boolean[] selectGhosts(int capacity) {
			boolean[] selected = new boolean[Math.max(0, capacity)];
			List<GhostTrack> audible = new ArrayList<>();
			List<GhostTrack> weak = new ArrayList<>();
			double maxEnergy = 0.0;
			for (GhostTrack track : tracks) {
				maxEnergy = Math.max(maxEnergy, track.energy);
			}
			double floor = maxEnergy * MIN_ENERGY_RATIO;
			for (GhostTrack track : tracks) {
				if (track.slot < 0 || track.slot >= capacity) {
					continue;
				}
				boolean hasEnergy = track.energy > 0.0;
				boolean held = lastBuildMillis - track.lastSeenMillis
					<= HOLD_MILLIS;
				if (!hasEnergy && !held) {
					continue;
				}
				if (hasEnergy && track.energy >= floor) {
					audible.add(track);
				} else {
					weak.add(track);
				}
			}
			Comparator<GhostTrack> byEnergy = Comparator.comparingDouble(
				(GhostTrack track) -> track.energy).reversed();
			audible.sort(byEnergy);
			weak.sort(byEnergy);
			List<GhostTrack> candidates = new ArrayList<>(
				audible.size() + weak.size());
			candidates.addAll(audible);
			candidates.addAll(weak);
			if (candidates.isEmpty()) {
				return selected;
			}
			// Guarantee one direction per active order before filling the rest
			// by raw energy, so weak-but-distinct orders still get a ghost.
			boolean[] orderSeen = new boolean[MAX_ORDER + 1];
			for (GhostTrack track : candidates) {
				if (!orderSeen[track.order]) {
					selected[track.slot] = true;
					orderSeen[track.order] = true;
				}
			}
			for (GhostTrack track : candidates) {
				if (countSelected(selected) >= capacity) {
					break;
				}
				selected[track.slot] = true;
			}
			return selected;
		}

		private static int countSelected(boolean[] selected) {
			int count = 0;
			for (boolean value : selected) {
				if (value) {
					count++;
				}
			}
			return count;
		}

		private int freeSlot(int capacity) {
			for (int slot = 0; slot < capacity; slot++) {
				boolean used = false;
				for (GhostTrack track : tracks) {
					if (track.slot == slot) {
						used = true;
						break;
					}
				}
				if (!used) {
					return slot;
				}
			}
			return -1;
		}

		private static int nearestCluster(List<Cluster> clusters,
										  float[] direction) {
			int nearest = -1;
			double best = Double.MAX_VALUE;
			for (int index = 0; index < clusters.size(); index++) {
				double distance = angularDistance(clusters.get(index).direction(),
					direction);
				if (distance < best) {
					best = distance;
					nearest = index;
				}
			}
			return nearest;
		}

		private static Cluster merge(Cluster cluster, GhostSource source) {
			double energy = cluster.energy() + source.energy();
			float[] direction = new float[3];
			for (int axis = 0; axis < 3; axis++) {
				direction[axis] = (float) ((cluster.direction()[axis]
					* cluster.energy() + source.direction()[axis]
					* source.energy()) / Math.max(1.0e-12, energy));
			}
			normalizeInto(direction, direction);
			return new Cluster(cluster.order(), direction, energy);
		}

		private static void smoothInto(float[] target, float[] value,
									   double alpha) {
			for (int axis = 0; axis < 3; axis++) {
				target[axis] = (float) (target[axis]
					+ (value[axis] - target[axis]) * alpha);
			}
			normalizeInto(target, target);
		}

		private static float ghostRadius(List<float[]> physical,
										 float[] origin) {
			if (physical.isEmpty()) {
				return DEFAULT_GHOST_RADIUS;
			}
			double sum = 0.0;
			for (float[] position : physical) {
				double dx = position[0] - origin[0];
				double dy = position[1] - origin[1];
				double dz = position[2] - origin[2];
				sum += Math.sqrt(dx * dx + dy * dy + dz * dz);
			}
			double mean = sum / physical.size();
			return (float) Math.max(1.0, Math.min(8.0, mean));
		}

		private static boolean validPosition(float[] position) {
			if (position == null || position.length < 3) {
				return false;
			}
			double length = Math.sqrt(position[0] * position[0]
				+ position[1] * position[1]
				+ position[2] * position[2]);
			return Double.isFinite(length) && length > 1.0e-6;
		}

		private static void normalizeInto(float[] source, float[] target) {
			double length = Math.sqrt(source[0] * source[0]
				+ source[1] * source[1]
				+ source[2] * source[2]);
			if (!Double.isFinite(length) || length < 1.0e-6) {
				target[0] = 0.0f;
				target[1] = 0.0f;
				target[2] = -1.0f;
				return;
			}
			target[0] = (float) (source[0] / length);
			target[1] = (float) (source[1] / length);
			target[2] = (float) (source[2] / length);
		}
	}

	private static double angularDistance(float[] first, float[] second) {
		double dot = first[0] * second[0] + first[1] * second[1]
			+ first[2] * second[2];
		dot = Math.max(-1.0, Math.min(1.0, dot));
		return Math.acos(dot);
	}

	private WetLayout() {
	}
}
