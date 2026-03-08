package com.bombbellannouncer.bomb;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BombMessageParser {
	public static final UUID WORLD_NAME_UUID = UUID.fromString("16ff7452-714f-2752-b3cd-c3cb2068f6af");

	private static final Pattern WHITESPACE = Pattern.compile("\\s+");
	private static final Pattern LEADING_GLYPHS = Pattern.compile("^[\\p{Co}\\s]+");
	private static final Pattern BOMB_BELL_PATTERN = Pattern.compile(
		"^(?<user>.+?) has thrown an? (?<bomb>.+?) Bomb on (?<server>.+)$"
	);
	private static final Pattern LOCAL_BOMB_THROWN_PATTERN = Pattern.compile(
		"^(?<bomb>.+?) Bomb$"
	);
	private static final Pattern EXPIRATION_PATTERN = Pattern.compile(
		"^(?<bomb>.+?) Bomb has expired!.*$"
	);
	private static final Pattern BOSS_BAR_PATTERN = Pattern.compile(
		"^(?:Double )?(?<bomb>.+?) from (?<user>.+?) \\[(?<length>\\d+)(?<unit>[ms])\\]$"
	);
	private static final Pattern WORLD_NAME_PATTERN = Pattern.compile(
		"^Global \\[(?<server>[^\\]]+)]$"
	);

	public String normalizePlainText(String rawText) {
		if (rawText == null) {
			return "";
		}

		String normalized = rawText.replace('\u00A0', ' ');
		normalized = WHITESPACE.matcher(normalized).replaceAll(" ").trim();
		normalized = LEADING_GLYPHS.matcher(normalized).replaceFirst("").trim();
		return WHITESPACE.matcher(normalized).replaceAll(" ").trim();
	}

	public Optional<BombBellMatch> parseBombBell(String rawText) {
		String normalized = normalizePlainText(rawText);
		Matcher matcher = BOMB_BELL_PATTERN.matcher(normalized);

		if (!matcher.matches()) {
			return Optional.empty();
		}

		return BombType.fromString(matcher.group("bomb")).map(bombType ->
			new BombBellMatch(
				matcher.group("user").trim(),
				bombType,
				BombKey.normalizeServer(matcher.group("server"))
			)
		);
	}

	public Optional<BombType> parseBombExpiration(String rawText) {
		String normalized = normalizePlainText(rawText);
		Matcher matcher = EXPIRATION_PATTERN.matcher(normalized);

		if (!matcher.matches()) {
			return Optional.empty();
		}

		return BombType.fromString(matcher.group("bomb"));
	}

	public Optional<BombType> parseLocalBombThrown(String rawText) {
		String normalized = normalizePlainText(rawText);
		Matcher matcher = LOCAL_BOMB_THROWN_PATTERN.matcher(normalized);

		if (!matcher.matches()) {
			return Optional.empty();
		}

		return BombType.fromString(matcher.group("bomb"));
	}

	public Optional<BossBarMatch> parseBossBar(String rawText) {
		String normalized = normalizePlainText(rawText);
		Matcher matcher = BOSS_BAR_PATTERN.matcher(normalized);

		if (!matcher.matches()) {
			return Optional.empty();
		}

		return BombType.fromString(matcher.group("bomb")).map(bombType -> {
			float lengthValue = Float.parseFloat(matcher.group("length"));
			float lengthMinutes = matcher.group("unit").equals("m")
				? lengthValue + 0.5f
				: lengthValue / 60.0f;

			return new BossBarMatch(matcher.group("user").trim(), bombType, lengthMinutes);
		});
	}

	public Optional<String> parseCurrentWorldName(String rawText) {
		String normalized = normalizePlainText(rawText);
		Matcher matcher = WORLD_NAME_PATTERN.matcher(normalized);

		if (!matcher.matches()) {
			return Optional.empty();
		}

		return Optional.of(BombKey.normalizeServer(matcher.group("server")));
	}

	public record BombBellMatch(String user, BombType bombType, String server) {
		public String notificationKey() {
			return bombType.name() + "|" + server + "|" + user.toLowerCase(Locale.ROOT);
		}
	}

	public record BossBarMatch(String user, BombType bombType, float lengthMinutes) {
	}
}
