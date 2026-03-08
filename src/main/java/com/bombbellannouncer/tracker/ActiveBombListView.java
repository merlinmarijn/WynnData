package com.bombbellannouncer.tracker;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public record ActiveBombListView(
	List<Section> bombSections,
	List<Section> comboSections
) {
	public ActiveBombListView {
		bombSections = List.copyOf(Objects.requireNonNull(bombSections, "bombSections"));
		comboSections = List.copyOf(Objects.requireNonNull(comboSections, "comboSections"));
	}

	public boolean isEmpty() {
		return bombSections.isEmpty() && comboSections.isEmpty();
	}

	public record Section(
		String title,
		List<Entry> entries
	) {
		public Section {
			title = Objects.requireNonNull(title, "title");
			entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
		}
	}

	public record Entry(
		String world,
		long remainingMillis
	) {
		public Entry {
			world = Objects.requireNonNull(world, "world");
		}

		public String lobbyCode() {
			return world.toLowerCase(Locale.ROOT);
		}
	}
}
