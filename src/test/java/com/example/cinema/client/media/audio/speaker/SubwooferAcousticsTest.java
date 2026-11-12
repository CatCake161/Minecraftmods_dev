package com.example.cinema.client.media.audio.speaker;

import com.example.cinema.block.entity.SpeakerType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubwooferAcousticsTest {
	@Test
	void sealedAndPortedHaveDifferentInfrasonicRollOff() {
		SpeakerDataProfile sealed = subwoofer(
			SpeakerDataProfile.Enclosure.SEALED, 30.0);
		SpeakerDataProfile ported = subwoofer(
			SpeakerDataProfile.Enclosure.PORTED, 20.0);

		double sealedAt20 = SubwooferAcoustics.enclosureGainDb(sealed, 20.0);
		double portedAt20 = SubwooferAcoustics.enclosureGainDb(ported, 20.0);
		double sealedAt15 = SubwooferAcoustics.enclosureGainDb(sealed, 15.0);
		double portedAt15 = SubwooferAcoustics.enclosureGainDb(ported, 15.0);

		assertTrue(portedAt20 > sealedAt20 + 3.0,
			"ported should extend lower: sealed " + sealedAt20
				+ " dB vs ported " + portedAt20 + " dB");
		assertTrue(portedAt15 > sealedAt15,
			"ported roll-off should stay above sealed below the band edge");
	}

	@Test
	void measuredSubwooferDoesNotDoubleApplyEnclosureModel() {
		SpeakerDataProfile measured = new SpeakerDataProfile(
			"measured-sub", "Measured Sub", SpeakerType.SUBWOOFER,
			SpeakerDataProfile.Enclosure.PORTED,
			SpeakerDataProfile.Pattern.MEASURED, "", "", 20.0, 200.0,
			new double[]{20.0, 80.0, 200.0}, new double[]{70.0, 80.0, 70.0},
			new SpeakerDataProfile.Row[]{
				new SpeakerDataProfile.Row(0.0, new double[]{70.0, 80.0, 70.0})
			}, new SpeakerDataProfile.Row[0]);

		assertEquals(0.0, SubwooferAcoustics.enclosureGainDb(measured, 20.0),
			1.0e-9);
	}

	@Test
	void manualBandUsesButterworthEdgesAndGeometricReference() {
		SubwooferBand band = new SubwooferBand(20.0, 200.0);

		assertEquals(-3.0, band.gainDb(20.0), 0.1);
		assertEquals(-3.0, band.gainDb(200.0), 0.1);
		assertTrue(band.gainDb(400.0) < -10.0,
			"above the manual high edge should roll off: "
				+ band.gainDb(400.0));
		assertEquals(Math.sqrt(20.0 * 200.0), band.referenceHz(), 1.0e-9);
	}

	@Test
	void manualBandClampsToRegistryBounds() {
		SubwooferBand band = new SubwooferBand(1.0, 5_000.0);

		assertEquals(SpeakerDataRegistry.SUBWOOFER_MIN_HZ, band.lowHz(), 1.0e-9);
		assertEquals(SpeakerDataRegistry.SUBWOOFER_MAX_HZ, band.highHz(), 1.0e-9);
	}

	private static SpeakerDataProfile subwoofer(
		SpeakerDataProfile.Enclosure enclosure, double lowHz) {
		return new SpeakerDataProfile("test-sub", "Test Sub",
			SpeakerType.SUBWOOFER, enclosure, SpeakerDataProfile.Pattern.OMNI,
			"", "", lowHz, 200.0, null, null, null, null);
	}
}
