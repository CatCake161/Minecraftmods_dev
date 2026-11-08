package com.example.cinema.client.audio.acoustics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Pure higher-order image-source geometry for the A3 rework spike.
 *
 * <p>The existing {@link ImageSourceSolver} handles one bounce. This class
 * recursively mirrors the current image source through a sequence of
 * {@link MirrorPlane}s, then backtracks the reflection points and validates
 * every point through {@link ImageSourceSolver.World}. It deliberately stops
 * at the geometry/material/occlusion layer and does not allocate OpenAL
 * sources or FIRs; the caller can use the resulting path count and timing to
 * decide between a per-tap source pool and a late-reflection bus.
 *
 * <p>Only specular paths are generated. The path key contains the full plane
 * sequence, e.g. {@code 0:Z-8000>X-6000}, so two different bounce orders are
 * never collapsed by the existing first-order {@code channel:plane} key.
 */
public final class ImageSourcePathSolver {
	public static final double SPEED_OF_SOUND = 343.0;
	public static final double MIN_DELAY_SECONDS = 0.001;
	/** The spike allows late paths up to 300 ms; the audio engine still clamps at 80 ms. */
	public static final double MAX_DELAY_SECONDS = 0.300;
	public static final int DEFAULT_MAX_ORDER = 3;
	public static final int MAX_PATHS = 512;
	/** Hard bound for deep plane sequences in a patch-heavy room. */
	public static final int MAX_VISITS = 200_000;

	public record SolveStats(List<ReflectionPathTap> paths, int visits,
							 boolean truncated) {
		public SolveStats {
			paths = paths == null ? List.of() : List.copyOf(paths);
		}
	}

	private static final double EPSILON = 1.0e-6;
	private static final double BOUNDS_MARGIN = 0.10;

	private ImageSourcePathSolver() {
	}

	/** Solves all paths with order {@code 2..maxOrder}. */
	public static List<ReflectionPathTap> solve(List<MirrorPlane> planes, int channel,
												Point3 source, Point3 listener,
												boolean lfe,
												ImageSourceSolver.World world,
												int maxOrder) {
		return solve(planes, channel, source, listener, lfe, world, maxOrder,
			MAX_PATHS);
	}

	public static List<ReflectionPathTap> solve(List<MirrorPlane> planes, int channel,
												Point3 source, Point3 listener,
												boolean lfe,
												ImageSourceSolver.World world,
												int maxOrder, int maxPaths) {
		return solveWithStats(planes, channel, source, listener, lfe, world,
			maxOrder, maxPaths).paths();
	}

	public static SolveStats solveWithStats(List<MirrorPlane> planes, int channel,
											Point3 source, Point3 listener,
											boolean lfe,
											ImageSourceSolver.World world,
											int maxOrder, int maxPaths) {
		return solveWithStats(planes, channel, source, listener, lfe, world,
			maxOrder, maxPaths, 2, MAX_VISITS);
	}

	public static SolveStats solveWithStats(List<MirrorPlane> planes, int channel,
											Point3 source, Point3 listener,
											boolean lfe,
											ImageSourceSolver.World world,
											int maxOrder, int maxPaths,
											int minOrder, int maxVisits) {
		int firstOrder = Math.max(2, minOrder);
		if (lfe || planes == null || planes.isEmpty() || source == null
			|| listener == null || world == null || maxOrder < firstOrder
			|| maxPaths <= 0 || maxVisits <= 0) {
			return new SolveStats(List.of(), 0, false);
		}
		List<ReflectionPathTap> taps = new ArrayList<>();
		List<MirrorPlane> pathPlanes = new ArrayList<>(maxOrder);
		List<Point3> imageChain = new ArrayList<>(maxOrder + 1);
		imageChain.add(source);
		int[] visits = {0};
		boolean[] truncated = {false};
		search(planes, channel, listener, world, maxOrder, maxPaths, firstOrder,
			maxVisits, source, pathPlanes, imageChain, taps, visits, truncated);
		return new SolveStats(taps, visits[0], truncated[0]);
	}

	private static void search(List<MirrorPlane> planes, int channel,
							   Point3 listener, ImageSourceSolver.World world,
							   int maxOrder, int maxPaths, int minOrder,
							   int maxVisits, Point3 currentImage,
							   List<MirrorPlane> pathPlanes,
							   List<Point3> imageChain,
							   List<ReflectionPathTap> taps,
							   int[] visits, boolean[] truncated) {
		if (pathPlanes.size() >= maxOrder) {
			return;
		}
		if (taps.size() >= maxPaths || visits[0] >= maxVisits) {
			truncated[0] = true;
			return;
		}
		MirrorPlane last = pathPlanes.isEmpty()
			? null : pathPlanes.get(pathPlanes.size() - 1);
		for (MirrorPlane plane : planes) {
			if (taps.size() >= maxPaths || visits[0] >= maxVisits) {
				truncated[0] = true;
				return;
			}
			visits[0]++;
			if (plane == null || (last != null && last.key().equals(plane.key()))) {
				continue;
			}
			// The current image and the listener must both lie on the
			// interior side of the next plane. This rejects sequences whose
			// image source is already behind the wall before the bounce.
			if (plane.signedDistance(currentImage) <= EPSILON
				|| plane.signedDistance(listener) <= EPSILON) {
				continue;
			}
			Point3 nextImage = mirror(currentImage, plane);
			pathPlanes.add(plane);
			imageChain.add(nextImage);
			if (pathPlanes.size() >= minOrder) {
				ReflectionPathTap tap = buildTap(channel, pathPlanes, imageChain,
					listener, world);
				if (tap != null) {
					taps.add(tap);
					if (taps.size() >= maxPaths) {
						truncated[0] = true;
						imageChain.remove(imageChain.size() - 1);
						pathPlanes.remove(pathPlanes.size() - 1);
						return;
					}
				}
			}
			search(planes, channel, listener, world, maxOrder, maxPaths, minOrder,
				maxVisits, nextImage, pathPlanes, imageChain, taps, visits,
				truncated);
			imageChain.remove(imageChain.size() - 1);
			pathPlanes.remove(pathPlanes.size() - 1);
		}
	}

	private static final class Frame {
		private final int depth;
		private int nextPlane;
		private final Point3 image;

		private Frame(int depth, int nextPlane, Point3 image) {
			this.depth = depth;
			this.nextPlane = nextPlane;
			this.image = image;
		}
	}

	/**
	 * Resumable order-N path search. Each {@link #step(long)} processes a small
	 * time slice and returns true when the search is complete. It is meant for
	 * the client thread, where the world probe is safe but a full speaker solve
	 * would create a frame spike.
	 */
	public static final class Session {
		private final List<MirrorPlane> planes;
		private final int channel;
		private final Point3 listener;
		private final ImageSourceSolver.World world;
		private final int maxOrder;
		private final int maxPaths;
		private final int minOrder;
		private final int maxVisits;
		private final List<ReflectionPathTap> taps = new ArrayList<>();
		private final ArrayDeque<Frame> stack = new ArrayDeque<>();
		private final MirrorPlane[] pathPlanes;
		private final Point3[] imageChain;
		private int visits;
		private boolean truncated;
		private boolean done;

		public Session(List<MirrorPlane> planes, int channel, Point3 source,
					   Point3 listener, ImageSourceSolver.World world,
					   int maxOrder, int maxPaths, int minOrder, int maxVisits) {
			this.planes = planes == null ? List.of() : planes;
			this.channel = channel;
			this.listener = listener;
			this.world = world;
			this.maxOrder = maxOrder;
			this.maxPaths = Math.max(1, maxPaths);
			this.minOrder = Math.max(2, minOrder);
			this.maxVisits = Math.max(1, maxVisits);
			this.pathPlanes = new MirrorPlane[Math.max(2, maxOrder)];
			this.imageChain = new Point3[Math.max(2, maxOrder) + 1];
			if (source == null || listener == null || world == null
				|| this.planes.isEmpty() || maxOrder < this.minOrder) {
				done = true;
				return;
			}
			imageChain[0] = source;
			stack.push(new Frame(0, 0, source));
		}

		public boolean step(long budgetNanos) {
			if (done) {
				return true;
			}
			long deadline = System.nanoTime() + Math.max(1L, budgetNanos);
			while (!stack.isEmpty()) {
				if (taps.size() >= maxPaths || visits >= maxVisits) {
					truncated = true;
					done = true;
					break;
				}
				if ((visits & 63) == 0 && System.nanoTime() >= deadline) {
					break;
				}
				Frame frame = stack.peek();
				if (frame.nextPlane >= planes.size()) {
					stack.pop();
					continue;
				}
				MirrorPlane plane = planes.get(frame.nextPlane++);
				visits++;
				if (plane == null || (frame.depth > 0
					&& pathPlanes[frame.depth - 1].key().equals(plane.key()))) {
					continue;
				}
				if (plane.signedDistance(frame.image) <= EPSILON
					|| plane.signedDistance(listener) <= EPSILON) {
					continue;
				}
				Point3 nextImage = mirror(frame.image, plane);
				pathPlanes[frame.depth] = plane;
				imageChain[frame.depth] = frame.image;
				imageChain[frame.depth + 1] = nextImage;
				if (frame.depth + 1 >= minOrder) {
					List<MirrorPlane> path = Arrays.asList(pathPlanes)
						.subList(0, frame.depth + 1);
					List<Point3> images = Arrays.asList(imageChain)
						.subList(0, frame.depth + 2);
					ReflectionPathTap tap = buildTap(channel, path, images,
						listener, world);
					if (tap != null) {
						taps.add(tap);
					}
				}
				if (frame.depth + 1 < maxOrder) {
					stack.push(new Frame(frame.depth + 1, 0, nextImage));
				}
			}
			if (stack.isEmpty()) {
				done = true;
			}
			return done;
		}

		public SolveStats stats() {
			return new SolveStats(taps, visits, truncated);
		}
	}

	private static ReflectionPathTap buildTap(int channel,
											  List<MirrorPlane> planes,
											  List<Point3> imageChain,
											  Point3 listener,
											  ImageSourceSolver.World world) {
		int order = planes.size();
		Point3 finalImage = imageChain.get(order);
		Point3 source = imageChain.get(0);
		double pathLength = finalImage.distanceTo(listener);
		double directDistance = source.distanceTo(listener);
		if (!Double.isFinite(pathLength) || !Double.isFinite(directDistance)
			|| pathLength <= directDistance + EPSILON) {
			return null;
		}
		double delaySeconds = (pathLength - directDistance) / SPEED_OF_SOUND;
		if (delaySeconds < MIN_DELAY_SECONDS
			|| delaySeconds > MAX_DELAY_SECONDS) {
			return null;
		}

		List<Point3> points = new ArrayList<>(order);
		List<AcousticMaterial> materials = new ArrayList<>(order);
		Point3 next = listener;
		for (int i = order - 1; i >= 0; i--) {
			MirrorPlane plane = planes.get(i);
			// For the last bounce the straight line starts at the final image
			// source; after that, backtrack from the previous image source to
			// the reflection point just found.
			Point3 from = imageChain.get(i + 1);
			Optional<Point3> hit = intersection(plane, from, next);
			if (hit.isEmpty()) {
				return null;
			}
			Point3 point = hit.get();
			if (!plane.contains(point, BOUNDS_MARGIN)) {
				return null;
			}
			AcousticMaterial material = world.wallAt(plane, point);
			if (material == null) {
				return null;
			}
			points.add(0, point);
			materials.add(0, material);
			next = point;
		}

		OcclusionResult occlusion = OcclusionResult.NONE;
		Point3 previous = source;
		for (Point3 point : points) {
			occlusion = OcclusionResult.combine(occlusion,
				world.occlusion(previous, point));
			previous = point;
		}
		occlusion = OcclusionResult.combine(occlusion,
			world.occlusion(previous, listener));

		double gain = occlusion.linearGain() / Math.max(1.0, pathLength);
		for (AcousticMaterial material : materials) {
			gain *= Math.sqrt(Math.max(0.0, 1.0 - material.mid()));
		}
		return new ReflectionPathTap(channel, pathKey(channel, planes), order,
			finalImage, points, materials, pathLength, delaySeconds, gain,
			occlusion);
	}

	private static Point3 mirror(Point3 point, MirrorPlane plane) {
		double distance = plane.signedDistance(point);
		return point.subtract(plane.normalVector().scale(2.0 * distance));
	}

	/**
	 * Intersects the segment from an image source (behind the plane) to the
	 * next point (in front of it). Both sides are required so a degenerate
	 * grazing path cannot produce a reflection point.
	 */
	private static Optional<Point3> intersection(MirrorPlane plane, Point3 from,
												 Point3 to) {
		double fromDistance = plane.signedDistance(from);
		double toDistance = plane.signedDistance(to);
		if (fromDistance >= -EPSILON || toDistance <= EPSILON) {
			return Optional.empty();
		}
		double denominator = fromDistance - toDistance;
		if (Math.abs(denominator) <= EPSILON) {
			return Optional.empty();
		}
		double t = fromDistance / denominator;
		if (!(t > 0.0 && t < 1.0)) {
			return Optional.empty();
		}
		Point3 point = from.add(to.subtract(from).scale(t));
		if (!Double.isFinite(point.x()) || !Double.isFinite(point.y())
			|| !Double.isFinite(point.z())) {
			return Optional.empty();
		}
		return Optional.of(point);
	}

	private static String pathKey(int channel, List<MirrorPlane> planes) {
		StringBuilder builder = new StringBuilder();
		builder.append(channel).append(':');
		for (int i = 0; i < planes.size(); i++) {
			if (i > 0) {
				builder.append('>');
			}
			builder.append(planes.get(i).key());
		}
		return builder.toString();
	}
}
