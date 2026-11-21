package com.example.cinema.client.config;

import com.example.cinema.client.media.audio.acoustics.room.AcousticMaterial;
import com.example.cinema.client.media.audio.acoustics.room.AcousticMaterials;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcousticMaterialConfigTest {
	@TempDir
	Path tempDir;

	@AfterEach
	void restoreDefaults() {
		AcousticMaterials.resetToDefaults();
	}

	@Test
	void jsonOverridesSelectedMaterialAndKeepsOtherDefaults() throws Exception {
		Path file = tempDir.resolve("materials.json");
		Files.writeString(file, """
			{
			  "version": 1,
			  "materials": {
			    "stone": {
			      "low": 0.11,
			      "mid": 0.22,
			      "high": 0.33,
			      "scatteringLow": 0.01,
			      "scatteringMid": 0.02,
			      "scatteringHigh": 0.03
			    }
			  }
			}
			""");

		assertTrue(AcousticMaterialConfig.load(file, false));

		AcousticMaterial stone = AcousticMaterials.get("stone");
		assertEquals(0.11, stone.low(), 1.0e-9);
		assertEquals(0.22, stone.mid(), 1.0e-9);
		assertEquals(0.33, stone.high(), 1.0e-9);
		assertEquals(0.01, stone.scatteringLow(), 1.0e-9);
		assertEquals(0.02, stone.scatteringMid(), 1.0e-9);
		assertEquals(0.03, stone.scatteringHigh(), 1.0e-9);
		assertEquals(AcousticMaterials.defaultsSnapshot().get("glass").low(),
			AcousticMaterials.get("glass").low(), 1.0e-9);
	}

	@Test
	void missingFileWritesDefaults() {
		Path file = tempDir.resolve("new-materials.json");

		assertTrue(AcousticMaterialConfig.load(file, false));
		assertTrue(Files.isRegularFile(file));
		assertEquals(AcousticMaterials.defaultsSnapshot().get("default").mid(),
			AcousticMaterials.defaultMaterial().mid(), 1.0e-9);
	}

	@Test
	void invalidJsonResetsDefaults() throws Exception {
		Path file = tempDir.resolve("bad-materials.json");
		Files.writeString(file, "{ not json");

		assertFalse(AcousticMaterialConfig.load(file, false));
		assertEquals(AcousticMaterials.defaultsSnapshot().get("stone").mid(),
			AcousticMaterials.get("stone").mid(), 1.0e-9);
	}
}
