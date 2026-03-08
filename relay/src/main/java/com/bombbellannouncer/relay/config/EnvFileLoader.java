package com.bombbellannouncer.relay.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class EnvFileLoader {
	private EnvFileLoader() {
	}

	static Map<String, String> load(Path path) {
		Map<String, String> values = new LinkedHashMap<>();
		if (Files.notExists(path)) {
			return values;
		}

		try {
			List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
			for (String line : lines) {
				String trimmed = line.trim();
				if (trimmed.isEmpty() || trimmed.startsWith("#")) {
					continue;
				}

				int separatorIndex = trimmed.indexOf('=');
				if (separatorIndex <= 0) {
					continue;
				}

				String key = trimmed.substring(0, separatorIndex).trim();
				String value = trimmed.substring(separatorIndex + 1).trim();
				if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
					value = value.substring(1, value.length() - 1);
				}
				values.put(key, value);
			}
		} catch (IOException ignored) {
		}

		return values;
	}

	static Path firstExisting(Path... candidates) {
		for (Path candidate : candidates) {
			if (candidate != null && Files.exists(candidate)) {
				return candidate;
			}
		}

		return null;
	}
}
