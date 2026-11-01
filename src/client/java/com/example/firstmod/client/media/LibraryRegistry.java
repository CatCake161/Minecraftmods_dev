package com.example.firstmod.client.media;

import com.example.firstmod.FirstMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Client-local registry of media library roots.
 *
 * <p>The default library is {@code <game>/cinema/media/}. Extra folders are
 * stored in {@code <game>/cinema/libraries.json} and exposed to the receiver as
 * {@code lib/<id>/<relative>} keys, so absolute paths never enter block entity
 * state or network payloads.
 */
public final class LibraryRegistry {
	public static final String DEFAULT_ID = "default";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final List<StoredLibrary> EXTERNAL = new ArrayList<>();
	private static boolean loaded;

	private LibraryRegistry() {
	}

	public static Path defaultRoot() {
		Path root = Minecraft.getInstance().gameDirectory.toPath()
			.resolve("cinema").resolve("media").toAbsolutePath().normalize();
		try {
			Files.createDirectories(root);
		} catch (IOException e) {
			FirstMod.LOGGER.warn("cinema media: could not create default library directory", e);
		}
		return root;
	}

	public static Path configFile() {
		return Minecraft.getInstance().gameDirectory.toPath()
			.resolve("cinema").resolve("libraries.json").toAbsolutePath().normalize();
	}

	public static synchronized List<Library> libraries() {
		load();
		List<Library> result = new ArrayList<>();
		result.add(new Library(DEFAULT_ID, "Cinema Media", defaultRoot()));
		for (StoredLibrary stored : EXTERNAL) {
			try {
				result.add(new Library(stored.id, stored.name,
					Path.of(stored.path).toAbsolutePath().normalize()));
			} catch (Exception e) {
				FirstMod.LOGGER.warn("cinema media: ignoring invalid library path for {}", stored.id);
			}
		}
		return result;
	}

	public static synchronized Library findById(String id) {
		if (id == null) {
			return null;
		}
		for (Library library : libraries()) {
			if (library.id().equals(id)) {
				return library;
			}
		}
		return null;
	}

	public static synchronized Library findByPath(Path file) {
		Path absolute = file.toAbsolutePath().normalize();
		Library best = null;
		int bestDepth = -1;
		for (Library library : libraries()) {
			if (absolute.startsWith(library.root()) && library.root().getNameCount() > bestDepth) {
				best = library;
				bestDepth = library.root().getNameCount();
			}
		}
		return best;
	}

	public static synchronized Library add(Path folder) throws IOException {
		load();
		Path root = folder.toAbsolutePath().normalize();
		if (!Files.isDirectory(root)) {
			throw new IOException("Not a folder: " + root);
		}
		for (Library library : libraries()) {
			if (library.root().equals(root)) {
				return library;
			}
		}
		String name = root.getFileName() == null ? "Library" : root.getFileName().toString();
		String baseId = slugify(name);
		String id = baseId;
		int suffix = 2;
		while (findByIdUnchecked(id) != null) {
			id = baseId + "-" + suffix++;
		}
		StoredLibrary stored = new StoredLibrary();
		stored.id = id;
		stored.name = name;
		stored.path = root.toString();
		EXTERNAL.add(stored);
		save();
		FirstMod.LOGGER.info("cinema media: added external library {} ({})", name, id);
		return new Library(id, name, root);
	}

	public static synchronized boolean remove(String id) {
		load();
		if (DEFAULT_ID.equals(id)) {
			return false;
		}
		boolean removed = EXTERNAL.removeIf(stored -> stored.id.equals(id));
		if (removed) {
			save();
			FirstMod.LOGGER.info("cinema media: removed external library {}", id);
		}
		return removed;
	}

	/** Resolves a full media key to its library and relative path. */
	public static Key parseKey(String rawKey) {
		String key = rawKey == null ? "" : rawKey;
		if (key.startsWith("lib/")) {
			int slash = key.indexOf('/', 4);
			String id = slash < 0 ? key.substring(4) : key.substring(4, slash);
			String relative = slash < 0 ? "" : key.substring(slash + 1);
			Library library = findById(id);
			if (library == null) {
				return null;
			}
			return new Key(library, relative);
		}
		return new Key(findById(DEFAULT_ID), key);
	}

	private static void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		Path file = configFile();
		if (!Files.isRegularFile(file)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			StoredLibraries data = GSON.fromJson(reader, StoredLibraries.class);
			if (data != null && data.libraries != null) {
				for (StoredLibrary stored : data.libraries) {
					if (stored.id != null && stored.name != null && stored.path != null) {
						EXTERNAL.add(stored);
					}
				}
			}
		} catch (Exception e) {
			FirstMod.LOGGER.warn("cinema media: could not read libraries.json", e);
		}
	}

	private static void save() {
		Path file = configFile();
		StoredLibraries data = new StoredLibraries();
		data.libraries = new ArrayList<>(EXTERNAL);
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(data, writer);
			}
		} catch (IOException e) {
			FirstMod.LOGGER.warn("cinema media: could not save libraries.json", e);
		}
	}

	private static Library findByIdUnchecked(String id) {
		for (Library library : libraries()) {
			if (library.id().equals(id)) {
				return library;
			}
		}
		return null;
	}

	private static String slugify(String name) {
		String slug = name.toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9]+", "-")
			.replaceAll("^-+|-+$", "");
		return slug.isEmpty() ? "library" : slug;
	}

	public record Library(String id, String name, Path root) {
	}

	public record Key(Library library, String relative) {
	}

	private static final class StoredLibraries {
		List<StoredLibrary> libraries = new ArrayList<>();
	}

	private static final class StoredLibrary {
		String id;
		String name;
		String path;
	}
}
