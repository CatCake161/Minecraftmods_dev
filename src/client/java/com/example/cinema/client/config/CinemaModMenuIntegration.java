package com.example.cinema.client.config;

import com.example.cinema.client.gui.CinemaConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Mod Menu entrypoint for the client-local Cinema settings screen. */
public final class CinemaModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return CinemaConfigScreen::new;
	}
}
