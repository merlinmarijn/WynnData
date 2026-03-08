package com.bombbellannouncer.relay.persistence;

import com.bombbellannouncer.protocol.BombSnapshotItem;
import com.bombbellannouncer.protocol.BombSource;
import com.bombbellannouncer.protocol.BombType;
import com.bombbellannouncer.protocol.ReporterRole;
import com.bombbellannouncer.relay.discord.DashboardComboSortMode;
import com.bombbellannouncer.relay.text.DisplayTextSanitizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RelayStore {
	private static final long MAX_ACCEPTED_DURATION_MILLIS = 45L * 60_000L;

	private final String jdbcUrl;

	public RelayStore(String jdbcUrl) {
		this.jdbcUrl = jdbcUrl;
	}

	public synchronized void initialize() {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("PRAGMA foreign_keys = ON");
			statement.execute("PRAGMA journal_mode = WAL");
			statement.execute("""
				CREATE TABLE IF NOT EXISTS projects (
					project_id TEXT PRIMARY KEY,
					guild_id TEXT NOT NULL UNIQUE,
					channel_id TEXT NOT NULL,
					dashboard_name TEXT NOT NULL,
					dashboard_layout_version INTEGER NOT NULL DEFAULT 0,
					submit_window_sequence INTEGER NOT NULL DEFAULT 0,
					created_at INTEGER NOT NULL,
					updated_at INTEGER NOT NULL
				)
				""");
			statement.execute("""
				CREATE TABLE IF NOT EXISTS dashboard_bomb_type_settings (
					project_id TEXT NOT NULL,
					bomb_type TEXT NOT NULL,
					enabled INTEGER NOT NULL,
					display_order INTEGER NOT NULL,
					updated_at INTEGER NOT NULL,
					PRIMARY KEY (project_id, bomb_type),
					FOREIGN KEY (project_id) REFERENCES projects(project_id) ON DELETE CASCADE
				)
				""");
			statement.execute("""
				CREATE TABLE IF NOT EXISTS dashboard_combos (
					project_id TEXT NOT NULL,
					combo_name_normalized TEXT NOT NULL,
					combo_name_display TEXT NOT NULL,
					bomb_types_csv TEXT NOT NULL,
					sort_mode TEXT NOT NULL,
					display_order INTEGER NOT NULL,
					created_at INTEGER NOT NULL,
					updated_at INTEGER NOT NULL,
					PRIMARY KEY (project_id, combo_name_normalized),
					FOREIGN KEY (project_id) REFERENCES projects(project_id) ON DELETE CASCADE
				)
				""");
			statement.execute("""
				CREATE TABLE IF NOT EXISTS dashboard_messages (
					project_id TEXT NOT NULL,
					bomb_type TEXT NOT NULL,
					message_id TEXT NOT NULL,
					payload_hash TEXT NOT NULL,
					updated_at INTEGER NOT NULL,
					PRIMARY KEY (project_id, bomb_type),
					FOREIGN KEY (project_id) REFERENCES projects(project_id) ON DELETE CASCADE
				)
				""");
			statement.execute("""
				CREATE TABLE IF NOT EXISTS dashboard_message_slots (
					project_id TEXT NOT NULL,
					message_slot TEXT NOT NULL,
					message_id TEXT NOT NULL,
					payload_hash TEXT NOT NULL,
					updated_at INTEGER NOT NULL,
					PRIMARY KEY (project_id, message_slot),
					FOREIGN KEY (project_id) REFERENCES projects(project_id) ON DELETE CASCADE
				)
				""");
			statement.execute("""
				CREATE TABLE IF NOT EXISTS contributors (
					contributor_id INTEGER PRIMARY KEY AUTOINCREMENT,
					project_id TEXT NOT NULL,
					discord_user_id TEXT NOT NULL,
					discord_username TEXT NOT NULL,
					created_at INTEGER NOT NULL,
					revoked_at INTEGER,
					UNIQUE (project_id, discord_user_id),
					FOREIGN KEY (project_id) REFERENCES projects(project_id) ON DELETE CASCADE
				)
				""");
			statement.execute("""
				CREATE TABLE IF NOT EXISTS device_credentials (
					credential_id INTEGER PRIMARY KEY AUTOINCREMENT,
					contributor_id INTEGER NOT NULL,
					token_hash TEXT NOT NULL UNIQUE,
					token_prefix TEXT NOT NULL,
					created_at INTEGER NOT NULL,
					last_seen_at INTEGER NOT NULL,
					revoked_at INTEGER,
					FOREIGN KEY (contributor_id) REFERENCES contributors(contributor_id) ON DELETE CASCADE
				)
				""");
			statement.execute("""
				CREATE TABLE IF NOT EXISTS reporter_chain_slots (
					project_id TEXT NOT NULL,
					reporter_role TEXT NOT NULL,
					credential_id INTEGER NOT NULL,
					assigned_at INTEGER NOT NULL,
					updated_at INTEGER NOT NULL,
					miss_count INTEGER NOT NULL DEFAULT 0,
					PRIMARY KEY (project_id, reporter_role),
					FOREIGN KEY (project_id) REFERENCES projects(project_id) ON DELETE CASCADE,
					FOREIGN KEY (credential_id) REFERENCES device_credentials(credential_id) ON DELETE CASCADE
				)
				""");
			statement.execute("""
				CREATE TABLE IF NOT EXISTS reporter_leases (
					project_id TEXT PRIMARY KEY,
					credential_id INTEGER NOT NULL,
					acquired_at INTEGER NOT NULL,
					updated_at INTEGER NOT NULL,
					FOREIGN KEY (project_id) REFERENCES projects(project_id) ON DELETE CASCADE,
					FOREIGN KEY (credential_id) REFERENCES device_credentials(credential_id) ON DELETE CASCADE
				)
				""");
			statement.execute("""
				CREATE TABLE IF NOT EXISTS submit_windows (
					project_id TEXT PRIMARY KEY,
					snapshot_hash TEXT NOT NULL,
					sequence INTEGER NOT NULL,
					allowed_credential_id INTEGER NOT NULL,
					attempted_credential_ids TEXT NOT NULL,
					created_at INTEGER NOT NULL,
					window_started_at INTEGER NOT NULL,
					updated_at INTEGER NOT NULL,
					FOREIGN KEY (project_id) REFERENCES projects(project_id) ON DELETE CASCADE,
					FOREIGN KEY (allowed_credential_id) REFERENCES device_credentials(credential_id) ON DELETE CASCADE
				)
				""");
			statement.execute("""
				CREATE TABLE IF NOT EXISTS enrollment_codes (
					enrollment_id INTEGER PRIMARY KEY AUTOINCREMENT,
					project_id TEXT NOT NULL,
					discord_user_id TEXT NOT NULL,
					discord_username TEXT NOT NULL,
					code_hash TEXT NOT NULL UNIQUE,
					created_at INTEGER NOT NULL,
					expires_at INTEGER NOT NULL,
					consumed_at INTEGER,
					FOREIGN KEY (project_id) REFERENCES projects(project_id) ON DELETE CASCADE
				)
				""");
			statement.execute("""
				CREATE TABLE IF NOT EXISTS active_bombs (
					project_id TEXT NOT NULL,
					server TEXT NOT NULL,
					bomb_type TEXT NOT NULL,
					user_name TEXT NOT NULL,
					start_time_millis INTEGER NOT NULL,
					expires_at_millis INTEGER NOT NULL,
					source TEXT NOT NULL,
					updated_at INTEGER NOT NULL,
					PRIMARY KEY (project_id, server, bomb_type),
					FOREIGN KEY (project_id) REFERENCES projects(project_id) ON DELETE CASCADE
				)
				""");

			ensureColumn(connection, "projects", "dashboard_layout_version", "INTEGER NOT NULL DEFAULT 0");
			ensureColumn(connection, "projects", "submit_window_sequence", "INTEGER NOT NULL DEFAULT 0");
			ensureColumn(connection, "device_credentials", "eligible_at", "INTEGER");
			ensureColumn(connection, "device_credentials", "last_proof_at", "INTEGER");
			ensureColumn(connection, "device_credentials", "last_status_at", "INTEGER");
			ensureColumn(connection, "device_credentials", "last_snapshot_at", "INTEGER");
			ensureColumn(connection, "device_credentials", "role_updated_at", "INTEGER");
			migrateDashboardMessages(connection);
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to initialize relay database", exception);
		}
	}

	public synchronized ProjectRecord upsertProjectBinding(String guildId, String channelId, String dashboardName, long nowMillis) {
		ProjectRecord existing = findProjectByGuildId(guildId);
		if (existing != null) {
			boolean channelChanged = !existing.channelId().equals(channelId);
			try (Connection connection = connection();
			     PreparedStatement statement = connection.prepareStatement(
				      "UPDATE projects SET channel_id = ?, dashboard_name = ?, dashboard_layout_version = ?, updated_at = ? WHERE guild_id = ?"
			     )) {
				statement.setString(1, channelId);
				statement.setString(2, dashboardName);
				statement.setLong(3, channelChanged ? 0L : findProjectDashboardLayoutVersion(existing.projectId()));
				statement.setLong(4, nowMillis);
				statement.setString(5, guildId);
				statement.executeUpdate();
			} catch (Exception exception) {
				throw new IllegalStateException("Failed to update project binding", exception);
			}

			if (channelChanged) {
				clearDashboardMessages(existing.projectId());
			}
			ensureDefaultDashboardBombTypeSettings(existing.projectId(), nowMillis);
			return new ProjectRecord(existing.projectId(), guildId, channelId, dashboardName);
		}

		String projectId = UUID.randomUUID().toString();
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement(
			      "INSERT INTO projects (project_id, guild_id, channel_id, dashboard_name, dashboard_layout_version, submit_window_sequence, created_at, updated_at) VALUES (?, ?, ?, ?, 0, 0, ?, ?)"
		     )) {
			statement.setString(1, projectId);
			statement.setString(2, guildId);
			statement.setString(3, channelId);
			statement.setString(4, dashboardName);
			statement.setLong(5, nowMillis);
			statement.setLong(6, nowMillis);
			statement.executeUpdate();
			ensureDefaultDashboardBombTypeSettings(projectId, nowMillis);
			return new ProjectRecord(projectId, guildId, channelId, dashboardName);
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to create project binding", exception);
		}
	}

	public synchronized ProjectRecord findProjectByGuildId(String guildId) {
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement(
			      "SELECT project_id, guild_id, channel_id, dashboard_name FROM projects WHERE guild_id = ?"
		     )) {
			statement.setString(1, guildId);
			try (ResultSet resultSet = statement.executeQuery()) {
				if (!resultSet.next()) {
					return null;
				}
				return new ProjectRecord(
					resultSet.getString("project_id"),
					resultSet.getString("guild_id"),
					resultSet.getString("channel_id"),
					resultSet.getString("dashboard_name")
				);
			}
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to look up project by guild", exception);
		}
	}

	public synchronized ProjectRecord findProjectById(String projectId) {
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement(
			      "SELECT project_id, guild_id, channel_id, dashboard_name FROM projects WHERE project_id = ?"
		     )) {
			statement.setString(1, projectId);
			try (ResultSet resultSet = statement.executeQuery()) {
				if (!resultSet.next()) {
					return null;
				}
				return new ProjectRecord(
					resultSet.getString("project_id"),
					resultSet.getString("guild_id"),
					resultSet.getString("channel_id"),
					resultSet.getString("dashboard_name")
				);
			}
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to look up project by id", exception);
		}
	}

	public synchronized List<ProjectRecord> findAllProjects() {
		List<ProjectRecord> projects = new ArrayList<>();
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement(
			      "SELECT project_id, guild_id, channel_id, dashboard_name FROM projects ORDER BY created_at ASC"
		     );
		     ResultSet resultSet = statement.executeQuery()) {
			while (resultSet.next()) {
				projects.add(new ProjectRecord(
					resultSet.getString("project_id"),
					resultSet.getString("guild_id"),
					resultSet.getString("channel_id"),
					resultSet.getString("dashboard_name")
				));
			}
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to list projects", exception);
		}

		return projects;
	}

	public synchronized long findProjectDashboardLayoutVersion(String projectId) {
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement(
			      "SELECT dashboard_layout_version FROM projects WHERE project_id = ?"
		     )) {
			statement.setString(1, projectId);
			try (ResultSet resultSet = statement.executeQuery()) {
				if (!resultSet.next()) {
					return 0L;
				}
				return resultSet.getLong("dashboard_layout_version");
			}
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to load project dashboard layout version", exception);
		}
	}

	public synchronized void setProjectDashboardLayoutVersion(String projectId, long version) {
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement(
			      "UPDATE projects SET dashboard_layout_version = ?, updated_at = ? WHERE project_id = ?"
		     )) {
			statement.setLong(1, Math.max(0L, version));
			statement.setLong(2, System.currentTimeMillis());
			statement.setString(3, projectId);
			statement.executeUpdate();
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to update project dashboard layout version", exception);
		}
	}

	public synchronized List<DashboardBombTypeSettingRecord> findDashboardBombTypeSettings(String projectId) {
		ensureDefaultDashboardBombTypeSettings(projectId, System.currentTimeMillis());
		List<DashboardBombTypeSettingRecord> settings = new ArrayList<>();
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement("""
			      SELECT project_id, bomb_type, enabled, display_order
			      FROM dashboard_bomb_type_settings
			      WHERE project_id = ?
			      ORDER BY display_order ASC, bomb_type ASC
			      """)) {
			statement.setString(1, projectId);
			try (ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					settings.add(new DashboardBombTypeSettingRecord(
						resultSet.getString("project_id"),
						BombType.valueOf(resultSet.getString("bomb_type")),
						resultSet.getInt("enabled") != 0,
						resultSet.getInt("display_order")
					));
				}
			}
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to load dashboard bomb type settings", exception);
		}

		return settings;
	}

	public synchronized void setDashboardBombTypeEnabled(String projectId, BombType bombType, boolean enabled, long nowMillis) {
		ensureDefaultDashboardBombTypeSettings(projectId, nowMillis);
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement("""
			      UPDATE dashboard_bomb_type_settings
			      SET enabled = ?, updated_at = ?
			      WHERE project_id = ? AND bomb_type = ?
			      """)) {
			statement.setInt(1, enabled ? 1 : 0);
			statement.setLong(2, nowMillis);
			statement.setString(3, projectId);
			statement.setString(4, bombType.name());
			statement.executeUpdate();
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to update bomb type enabled state", exception);
		}
	}

	public synchronized void moveDashboardBombType(String projectId, BombType bombType, int requestedPosition, long nowMillis) {
		List<DashboardBombTypeSettingRecord> settings = new ArrayList<>(findDashboardBombTypeSettings(projectId));
		int sourceIndex = -1;
		for (int index = 0; index < settings.size(); index++) {
			if (settings.get(index).bombType() == bombType) {
				sourceIndex = index;
				break;
			}
		}
		if (sourceIndex < 0) {
			return;
		}

		DashboardBombTypeSettingRecord moved = settings.remove(sourceIndex);
		int targetIndex = Math.max(0, Math.min(settings.size(), requestedPosition - 1));
		settings.add(targetIndex, moved);
		persistBombTypeOrder(projectId, settings, nowMillis);
	}

	public synchronized List<DashboardComboRecord> findDashboardCombos(String projectId) {
		List<DashboardComboRecord> combos = new ArrayList<>();
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement("""
			      SELECT project_id, combo_name_normalized, combo_name_display, bomb_types_csv, sort_mode, display_order, created_at, updated_at
			      FROM dashboard_combos
			      WHERE project_id = ?
			      ORDER BY display_order ASC, combo_name_normalized ASC
			      """)) {
			statement.setString(1, projectId);
			try (ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					combos.add(new DashboardComboRecord(
						resultSet.getString("project_id"),
						resultSet.getString("combo_name_normalized"),
						resultSet.getString("combo_name_display"),
						parseBombTypes(resultSet.getString("bomb_types_csv")),
						DashboardComboSortMode.valueOf(resultSet.getString("sort_mode")),
						resultSet.getInt("display_order"),
						resultSet.getLong("created_at"),
						resultSet.getLong("updated_at")
					));
				}
			}
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to load dashboard combos", exception);
		}

		return combos;
	}

	public synchronized DashboardComboRecord findDashboardCombo(String projectId, String normalizedName) {
		return findDashboardCombos(projectId).stream()
			.filter(combo -> combo.normalizedName().equals(normalizedName))
			.findFirst()
			.orElse(null);
	}

	public synchronized void addDashboardCombo(
		String projectId,
		String normalizedName,
		String displayName,
		List<BombType> bombTypes,
		DashboardComboSortMode sortMode,
		long nowMillis
	) {
		int displayOrder = findDashboardCombos(projectId).size();
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement("""
			      INSERT INTO dashboard_combos (project_id, combo_name_normalized, combo_name_display, bomb_types_csv, sort_mode, display_order, created_at, updated_at)
			      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
			      """)) {
			statement.setString(1, projectId);
			statement.setString(2, normalizedName);
			statement.setString(3, displayName);
			statement.setString(4, joinBombTypes(bombTypes));
			statement.setString(5, sortMode.name());
			statement.setInt(6, displayOrder);
			statement.setLong(7, nowMillis);
			statement.setLong(8, nowMillis);
			statement.executeUpdate();
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to add dashboard combo", exception);
		}
	}

	public synchronized void updateDashboardCombo(
		String projectId,
		String normalizedName,
		List<BombType> bombTypes,
		DashboardComboSortMode sortMode,
		long nowMillis
	) {
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement("""
			      UPDATE dashboard_combos
			      SET bomb_types_csv = ?, sort_mode = ?, updated_at = ?
			      WHERE project_id = ? AND combo_name_normalized = ?
			      """)) {
			statement.setString(1, joinBombTypes(bombTypes));
			statement.setString(2, sortMode.name());
			statement.setLong(3, nowMillis);
			statement.setString(4, projectId);
			statement.setString(5, normalizedName);
			statement.executeUpdate();
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to update dashboard combo", exception);
		}
	}

	public synchronized void removeDashboardCombo(String projectId, String normalizedName, long nowMillis) {
		List<DashboardComboRecord> combos = new ArrayList<>(findDashboardCombos(projectId));
		combos.removeIf(combo -> combo.normalizedName().equals(normalizedName));
		persistDashboardCombos(projectId, combos, nowMillis);
	}

	public synchronized void moveDashboardCombo(String projectId, String normalizedName, int requestedPosition, long nowMillis) {
		List<DashboardComboRecord> combos = new ArrayList<>(findDashboardCombos(projectId));
		int sourceIndex = -1;
		for (int index = 0; index < combos.size(); index++) {
			if (combos.get(index).normalizedName().equals(normalizedName)) {
				sourceIndex = index;
				break;
			}
		}
		if (sourceIndex < 0) {
			return;
		}

		DashboardComboRecord moved = combos.remove(sourceIndex);
		int targetIndex = Math.max(0, Math.min(combos.size(), requestedPosition - 1));
		combos.add(targetIndex, moved);
		persistDashboardCombos(projectId, combos, nowMillis);
	}

	public synchronized long nextSubmitWindowSequence(String projectId) {
		try (Connection connection = connection()) {
			long nextValue;
			try (PreparedStatement select = connection.prepareStatement(
				"SELECT submit_window_sequence FROM projects WHERE project_id = ?"
			)) {
				select.setString(1, projectId);
				try (ResultSet resultSet = select.executeQuery()) {
					nextValue = resultSet.next() ? resultSet.getLong("submit_window_sequence") + 1L : 1L;
				}
			}

			try (PreparedStatement update = connection.prepareStatement(
				"UPDATE projects SET submit_window_sequence = ?, updated_at = ? WHERE project_id = ?"
			)) {
				update.setLong(1, nextValue);
				update.setLong(2, System.currentTimeMillis());
				update.setString(3, projectId);
				update.executeUpdate();
			}

			return nextValue;
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to increment submit window sequence", exception);
		}
	}

	public synchronized void storeEnrollmentCode(String projectId, String discordUserId, String discordUsername, String codeHash, long expiresAtMillis, long nowMillis) {
		String sanitizedDiscordUsername = normalizeUser(discordUsername);
		try (Connection connection = connection()) {
			try (PreparedStatement revokeOld = connection.prepareStatement(
				"UPDATE enrollment_codes SET consumed_at = ? WHERE project_id = ? AND discord_user_id = ? AND consumed_at IS NULL"
			)) {
				revokeOld.setLong(1, nowMillis);
				revokeOld.setString(2, projectId);
				revokeOld.setString(3, discordUserId);
				revokeOld.executeUpdate();
			}

			try (PreparedStatement insert = connection.prepareStatement(
				"INSERT INTO enrollment_codes (project_id, discord_user_id, discord_username, code_hash, created_at, expires_at, consumed_at) VALUES (?, ?, ?, ?, ?, ?, NULL)"
			)) {
				insert.setString(1, projectId);
				insert.setString(2, discordUserId);
				insert.setString(3, sanitizedDiscordUsername);
				insert.setString(4, codeHash);
				insert.setLong(5, nowMillis);
				insert.setLong(6, expiresAtMillis);
				insert.executeUpdate();
			}
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to store enrollment code", exception);
		}
	}

	public synchronized EnrollmentGrant consumeEnrollmentCode(String projectId, String codeHash, long nowMillis) {
		try (Connection connection = connection();
		     PreparedStatement select = connection.prepareStatement(
			      "SELECT enrollment_id, discord_user_id, discord_username FROM enrollment_codes WHERE project_id = ? AND code_hash = ? AND consumed_at IS NULL AND expires_at >= ?"
		     )) {
			select.setString(1, projectId);
			select.setString(2, codeHash);
			select.setLong(3, nowMillis);
			try (ResultSet resultSet = select.executeQuery()) {
				if (!resultSet.next()) {
					return null;
				}

				long enrollmentId = resultSet.getLong("enrollment_id");
				String discordUserId = resultSet.getString("discord_user_id");
				String discordUsername = resultSet.getString("discord_username");

				try (PreparedStatement update = connection.prepareStatement(
					"UPDATE enrollment_codes SET consumed_at = ? WHERE enrollment_id = ?"
				)) {
					update.setLong(1, nowMillis);
					update.setLong(2, enrollmentId);
					update.executeUpdate();
				}

				return new EnrollmentGrant(projectId, discordUserId, discordUsername);
			}
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to consume enrollment code", exception);
		}
	}

	public synchronized ContributorRecord upsertContributor(String projectId, String discordUserId, String discordUsername, long nowMillis) {
		String sanitizedDiscordUsername = normalizeUser(discordUsername);
		try (Connection connection = connection();
		     PreparedStatement select = connection.prepareStatement(
			      "SELECT contributor_id FROM contributors WHERE project_id = ? AND discord_user_id = ?"
		     )) {
			select.setString(1, projectId);
			select.setString(2, discordUserId);
			try (ResultSet resultSet = select.executeQuery()) {
				if (resultSet.next()) {
					long contributorId = resultSet.getLong("contributor_id");
					try (PreparedStatement update = connection.prepareStatement(
						"UPDATE contributors SET discord_username = ?, revoked_at = NULL WHERE contributor_id = ?"
					)) {
						update.setString(1, sanitizedDiscordUsername);
						update.setLong(2, contributorId);
						update.executeUpdate();
					}
					return new ContributorRecord(contributorId, projectId, discordUserId, sanitizedDiscordUsername);
				}
			}

			try (PreparedStatement insert = connection.prepareStatement(
				"INSERT INTO contributors (project_id, discord_user_id, discord_username, created_at, revoked_at) VALUES (?, ?, ?, ?, NULL)",
				Statement.RETURN_GENERATED_KEYS
			)) {
				insert.setString(1, projectId);
				insert.setString(2, discordUserId);
				insert.setString(3, sanitizedDiscordUsername);
				insert.setLong(4, nowMillis);
				insert.executeUpdate();

				try (ResultSet keys = insert.getGeneratedKeys()) {
					keys.next();
					return new ContributorRecord(keys.getLong(1), projectId, discordUserId, sanitizedDiscordUsername);
				}
			}
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to upsert contributor", exception);
		}
	}

	public synchronized void createDeviceCredential(long contributorId, String tokenHash, String tokenPrefix, long nowMillis) {
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement(
			      "INSERT INTO device_credentials (contributor_id, token_hash, token_prefix, created_at, last_seen_at, last_status_at, revoked_at) VALUES (?, ?, ?, ?, ?, 0, NULL)"
		     )) {
			statement.setLong(1, contributorId);
			statement.setString(2, tokenHash);
			statement.setString(3, tokenPrefix);
			statement.setLong(4, nowMillis);
			statement.setLong(5, nowMillis);
			statement.executeUpdate();
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to create device credential", exception);
		}
	}

	public synchronized AuthenticatedDevice authenticateDevice(String projectId, String tokenHash, long nowMillis) {
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement("""
			      SELECT dc.credential_id, dc.token_prefix, c.contributor_id, c.discord_user_id, c.discord_username
			      FROM device_credentials dc
			      JOIN contributors c ON c.contributor_id = dc.contributor_id
			      WHERE c.project_id = ? AND dc.token_hash = ? AND dc.revoked_at IS NULL AND c.revoked_at IS NULL
			      """)) {
			statement.setString(1, projectId);
			statement.setString(2, tokenHash);
			try (ResultSet resultSet = statement.executeQuery()) {
				if (!resultSet.next()) {
					return null;
				}

				long credentialId = resultSet.getLong("credential_id");
				long contributorId = resultSet.getLong("contributor_id");
				String discordUserId = resultSet.getString("discord_user_id");
				String discordUsername = resultSet.getString("discord_username");
				String tokenPrefix = resultSet.getString("token_prefix");

				try (PreparedStatement update = connection.prepareStatement(
					"UPDATE device_credentials SET last_seen_at = ? WHERE credential_id = ?"
				)) {
					update.setLong(1, nowMillis);
					update.setLong(2, credentialId);
					update.executeUpdate();
				}

				return new AuthenticatedDevice(projectId, contributorId, credentialId, discordUserId, discordUsername, tokenPrefix);
			}
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to authenticate device credential", exception);
		}
	}

	public synchronized void markDeviceStatus(String projectId, long credentialId, long nowMillis) {
		updateDeviceReporterFields(projectId, credentialId, nowMillis, null, null, null);
	}

	public synchronized boolean markBombBellProof(String projectId, long credentialId, long nowMillis) {
		ReporterDeviceRecord existing = findReporterDevice(projectId, credentialId);
		boolean newlyEligible = existing != null && !existing.eligible();
		updateDeviceReporterFields(projectId, credentialId, nowMillis, nowMillis, nowMillis, newlyEligible ? nowMillis : null);
		return newlyEligible;
	}

	public synchronized void markSnapshotAccepted(String projectId, long credentialId, long nowMillis) {
		updateDeviceReporterFields(projectId, credentialId, null, null, nowMillis, null);
	}

	public synchronized List<ReporterDeviceRecord> findConnectedReporterDevices(String projectId, long minLastStatusAtMillis) {
		List<ReporterDeviceRecord> devices = new ArrayList<>();
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement("""
			      SELECT c.project_id, c.contributor_id, dc.credential_id, c.discord_user_id, c.discord_username, dc.token_prefix,
			             dc.last_seen_at, COALESCE(dc.last_status_at, 0) AS last_status_at, COALESCE(dc.last_proof_at, 0) AS last_proof_at,
			             COALESCE(dc.last_snapshot_at, 0) AS last_snapshot_at, dc.eligible_at, dc.role_updated_at
			      FROM device_credentials dc
			      JOIN contributors c ON c.contributor_id = dc.contributor_id
			      WHERE c.project_id = ? AND c.revoked_at IS NULL AND dc.revoked_at IS NULL AND COALESCE(dc.last_status_at, 0) >= ?
			      ORDER BY CASE WHEN dc.eligible_at IS NULL THEN 1 ELSE 0 END ASC, dc.eligible_at ASC, dc.credential_id ASC
			      """)) {
			statement.setString(1, projectId);
			statement.setLong(2, minLastStatusAtMillis);
			try (ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					devices.add(mapReporterDevice(resultSet));
				}
			}
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to list connected reporter devices", exception);
		}

		return devices;
	}

	public synchronized List<ReporterDeviceRecord> findEligibleReporterDevices(String projectId, long minLastStatusAtMillis) {
		List<ReporterDeviceRecord> devices = new ArrayList<>();
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement("""
			      SELECT c.project_id, c.contributor_id, dc.credential_id, c.discord_user_id, c.discord_username, dc.token_prefix,
			             dc.last_seen_at, COALESCE(dc.last_status_at, 0) AS last_status_at, COALESCE(dc.last_proof_at, 0) AS last_proof_at,
			             COALESCE(dc.last_snapshot_at, 0) AS last_snapshot_at, dc.eligible_at, dc.role_updated_at
			      FROM device_credentials dc
			      JOIN contributors c ON c.contributor_id = dc.contributor_id
			      WHERE c.project_id = ? AND c.revoked_at IS NULL AND dc.revoked_at IS NULL
			        AND dc.eligible_at IS NOT NULL AND COALESCE(dc.last_status_at, 0) >= ?
			      ORDER BY dc.eligible_at ASC, dc.credential_id ASC
			      """)) {
			statement.setString(1, projectId);
			statement.setLong(2, minLastStatusAtMillis);
			try (ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					devices.add(mapReporterDevice(resultSet));
				}
			}
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to list eligible reporter devices", exception);
		}

		return devices;
	}

	public synchronized ReporterDeviceRecord findReporterDevice(String projectId, long credentialId) {
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement("""
			      SELECT c.project_id, c.contributor_id, dc.credential_id, c.discord_user_id, c.discord_username, dc.token_prefix,
			             dc.last_seen_at, COALESCE(dc.last_status_at, 0) AS last_status_at, COALESCE(dc.last_proof_at, 0) AS last_proof_at,
			             COALESCE(dc.last_snapshot_at, 0) AS last_snapshot_at, dc.eligible_at, dc.role_updated_at
			      FROM device_credentials dc
			      JOIN contributors c ON c.contributor_id = dc.contributor_id
			      WHERE c.project_id = ? AND dc.credential_id = ? AND c.revoked_at IS NULL AND dc.revoked_at IS NULL
			      """)) {
			statement.setString(1, projectId);
			statement.setLong(2, credentialId);
			try (ResultSet resultSet = statement.executeQuery()) {
				return resultSet.next() ? mapReporterDevice(resultSet) : null;
			}
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to load reporter device", exception);
		}
	}

	public synchronized LeaderLeaseRecord findLeaderLease(String projectId) {
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement(
			      "SELECT project_id, credential_id, acquired_at, updated_at FROM reporter_leases WHERE project_id = ?"
		     )) {
			statement.setString(1, projectId);
			try (ResultSet resultSet = statement.executeQuery()) {
				if (!resultSet.next()) {
					return null;
				}

				return new LeaderLeaseRecord(
					resultSet.getString("project_id"),
					resultSet.getLong("credential_id"),
					resultSet.getLong("acquired_at"),
					resultSet.getLong("updated_at")
				);
			}
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to load reporter lease", exception);
		}
	}

	public synchronized void upsertLeaderLease(String projectId, long credentialId, long nowMillis, Long previousCredentialId) {
		LeaderLeaseRecord existing = findLeaderLease(projectId);
		long acquiredAt = existing != null && existing.credentialId() == credentialId ? existing.acquiredAt() : nowMillis;
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement("""
			      INSERT INTO reporter_leases (project_id, credential_id, acquired_at, updated_at)
			      VALUES (?, ?, ?, ?)
			      ON CONFLICT(project_id)
			      DO UPDATE SET credential_id = excluded.credential_id, acquired_at = excluded.acquired_at, updated_at = excluded.updated_at
			      """)) {
			statement.setString(1, projectId);
			statement.setLong(2, credentialId);
			statement.setLong(3, acquiredAt);
			statement.setLong(4, nowMillis);
			statement.executeUpdate();
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to save reporter lease", exception);
		}

		touchRoleUpdatedAt(credentialId, nowMillis);
		if (previousCredentialId != null && previousCredentialId.longValue() != credentialId) {
			touchRoleUpdatedAt(previousCredentialId, nowMillis);
		}
	}

	public synchronized void clearLeaderLease(String projectId, Long previousCredentialId, long nowMillis) {
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement(
			      "DELETE FROM reporter_leases WHERE project_id = ?"
		     )) {
			statement.setString(1, projectId);
			statement.executeUpdate();
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to clear reporter lease", exception);
		}

		if (previousCredentialId != null) {
			touchRoleUpdatedAt(previousCredentialId, nowMillis);
		}
	}

	public synchronized boolean mergeBombObservation(String projectId, BombSnapshotItem item, long nowMillis) {
		BombSnapshotItem normalized = normalize(item);
		if (normalized == null) {
			return false;
		}

		ActiveBombRecord existing = findActiveBomb(projectId, normalized.server(), normalized.bombType());
		if (existing == null) {
			insertActiveBomb(projectId, normalized, nowMillis);
			return true;
		}

		if (!shouldReplace(existing, normalized) && !shouldPatchUser(existing, normalized)) {
			return false;
		}

		updateActiveBomb(projectId, normalized, nowMillis);
		return true;
	}

	public synchronized void deleteExpiredBombs(long nowMillis) {
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement(
			      "DELETE FROM active_bombs WHERE expires_at_millis <= ?"
		     )) {
			statement.setLong(1, nowMillis);
			statement.executeUpdate();
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to delete expired bombs", exception);
		}
	}

	public synchronized List<ActiveBombRecord> findActiveBombs(String projectId, long nowMillis) {
		List<ActiveBombRecord> bombs = new ArrayList<>();
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement("""
			      SELECT project_id, server, bomb_type, user_name, start_time_millis, expires_at_millis, source
			      FROM active_bombs
			      WHERE project_id = ? AND expires_at_millis > ?
			      ORDER BY bomb_type ASC, expires_at_millis ASC, server ASC
			      """)) {
			statement.setString(1, projectId);
			statement.setLong(2, nowMillis);
			try (ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					bombs.add(new ActiveBombRecord(
						resultSet.getString("project_id"),
						normalizeServer(resultSet.getString("server")),
						BombType.valueOf(resultSet.getString("bomb_type")),
						resultSet.getString("user_name"),
						resultSet.getLong("start_time_millis"),
						resultSet.getLong("expires_at_millis"),
						BombSource.valueOf(resultSet.getString("source"))
					));
				}
			}
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to list active bombs", exception);
		}

		return bombs;
	}

	public synchronized Map<String, DashboardMessageRecord> findDashboardMessages(String projectId) {
		Map<String, DashboardMessageRecord> messages = new java.util.LinkedHashMap<>();
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement("""
			      SELECT project_id, message_slot, message_id, payload_hash
			      FROM dashboard_message_slots
			      WHERE project_id = ?
			      """)) {
			statement.setString(1, projectId);
			try (ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					String slotId = resultSet.getString("message_slot");
					messages.put(slotId, new DashboardMessageRecord(
						resultSet.getString("project_id"),
						slotId,
						resultSet.getString("message_id"),
						resultSet.getString("payload_hash")
					));
				}
			}
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to load dashboard messages", exception);
		}

		return messages;
	}

	public synchronized void upsertDashboardMessage(String projectId, String slotId, String messageId, String payloadHash, long nowMillis) {
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement("""
			      INSERT INTO dashboard_message_slots (project_id, message_slot, message_id, payload_hash, updated_at)
			      VALUES (?, ?, ?, ?, ?)
			      ON CONFLICT(project_id, message_slot)
			      DO UPDATE SET message_id = excluded.message_id, payload_hash = excluded.payload_hash, updated_at = excluded.updated_at
			      """)) {
			statement.setString(1, projectId);
			statement.setString(2, slotId);
			statement.setString(3, messageId);
			statement.setString(4, payloadHash);
			statement.setLong(5, nowMillis);
			statement.executeUpdate();
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to save dashboard message", exception);
		}
	}

	public synchronized void clearDashboardMessages(String projectId) {
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement(
			      "DELETE FROM dashboard_message_slots WHERE project_id = ?"
		     )) {
			statement.setString(1, projectId);
			statement.executeUpdate();
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to clear dashboard messages", exception);
		}
	}

	private void ensureDefaultDashboardBombTypeSettings(String projectId, long nowMillis) {
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement("""
			      INSERT OR IGNORE INTO dashboard_bomb_type_settings (project_id, bomb_type, enabled, display_order, updated_at)
			      VALUES (?, ?, 1, ?, ?)
			      """)) {
			int index = 0;
			for (BombType bombType : BombType.values()) {
				statement.setString(1, projectId);
				statement.setString(2, bombType.name());
				statement.setInt(3, index++);
				statement.setLong(4, nowMillis);
				statement.addBatch();
			}
			statement.executeBatch();
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to initialize dashboard bomb type settings", exception);
		}
	}

	private void persistBombTypeOrder(String projectId, List<DashboardBombTypeSettingRecord> settings, long nowMillis) {
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement("""
			      UPDATE dashboard_bomb_type_settings
			      SET display_order = ?, enabled = ?, updated_at = ?
			      WHERE project_id = ? AND bomb_type = ?
			      """)) {
			for (int index = 0; index < settings.size(); index++) {
				DashboardBombTypeSettingRecord setting = settings.get(index);
				statement.setInt(1, index);
				statement.setInt(2, setting.enabled() ? 1 : 0);
				statement.setLong(3, nowMillis);
				statement.setString(4, projectId);
				statement.setString(5, setting.bombType().name());
				statement.addBatch();
			}
			statement.executeBatch();
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to persist dashboard bomb type order", exception);
		}
	}

	private void persistDashboardCombos(String projectId, List<DashboardComboRecord> combos, long nowMillis) {
		try (Connection connection = connection()) {
			try (PreparedStatement delete = connection.prepareStatement(
				"DELETE FROM dashboard_combos WHERE project_id = ?"
			)) {
				delete.setString(1, projectId);
				delete.executeUpdate();
			}

			try (PreparedStatement insert = connection.prepareStatement("""
				INSERT INTO dashboard_combos (project_id, combo_name_normalized, combo_name_display, bomb_types_csv, sort_mode, display_order, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)
				""")) {
				for (int index = 0; index < combos.size(); index++) {
					DashboardComboRecord combo = combos.get(index);
					insert.setString(1, projectId);
					insert.setString(2, combo.normalizedName());
					insert.setString(3, combo.displayName());
					insert.setString(4, joinBombTypes(combo.bombTypes()));
					insert.setString(5, combo.sortMode().name());
					insert.setInt(6, index);
					insert.setLong(7, combo.createdAt());
					insert.setLong(8, nowMillis);
					insert.addBatch();
				}
				insert.executeBatch();
			}
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to persist dashboard combos", exception);
		}
	}

	public synchronized List<ReporterChainSlotRecord> findReporterChain(String projectId) {
		List<ReporterChainSlotRecord> slots = new ArrayList<>();
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement("""
			      SELECT project_id, reporter_role, credential_id, assigned_at, updated_at, miss_count
			      FROM reporter_chain_slots
			      WHERE project_id = ?
			      ORDER BY CASE reporter_role
			      	WHEN 'PRIMARY' THEN 1
			      	WHEN 'SECONDARY' THEN 2
			      	WHEN 'TERTIARY' THEN 3
			      	ELSE 99
			      END ASC
			      """)) {
			statement.setString(1, projectId);
			try (ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					slots.add(new ReporterChainSlotRecord(
						resultSet.getString("project_id"),
						ReporterRole.valueOf(resultSet.getString("reporter_role")),
						resultSet.getLong("credential_id"),
						resultSet.getLong("assigned_at"),
						resultSet.getLong("updated_at"),
						resultSet.getInt("miss_count")
					));
				}
			}
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to load reporter chain", exception);
		}

		return slots;
	}

	public synchronized void saveReporterChain(String projectId, List<ReporterChainSlotRecord> slots, long nowMillis) {
		List<ReporterChainSlotRecord> existing = findReporterChain(projectId);
		try (Connection connection = connection()) {
			try (PreparedStatement delete = connection.prepareStatement(
				"DELETE FROM reporter_chain_slots WHERE project_id = ?"
			)) {
				delete.setString(1, projectId);
				delete.executeUpdate();
			}

			try (PreparedStatement insert = connection.prepareStatement("""
				INSERT INTO reporter_chain_slots (project_id, reporter_role, credential_id, assigned_at, updated_at, miss_count)
				VALUES (?, ?, ?, ?, ?, ?)
				""")) {
				for (ReporterChainSlotRecord slot : slots) {
					insert.setString(1, projectId);
					insert.setString(2, slot.role().name());
					insert.setLong(3, slot.credentialId());
					insert.setLong(4, slot.assignedAt());
					insert.setLong(5, slot.updatedAt());
					insert.setInt(6, slot.missCount());
					insert.addBatch();
				}
				insert.executeBatch();
			}
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to save reporter chain", exception);
		}

		for (ReporterChainSlotRecord slot : existing) {
			touchRoleUpdatedAt(slot.credentialId(), nowMillis);
		}
		for (ReporterChainSlotRecord slot : slots) {
			touchRoleUpdatedAt(slot.credentialId(), nowMillis);
		}
	}

	public synchronized SubmitWindowRecord findSubmitWindow(String projectId) {
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement("""
			      SELECT project_id, snapshot_hash, sequence, allowed_credential_id, attempted_credential_ids, created_at, window_started_at, updated_at
			      FROM submit_windows
			      WHERE project_id = ?
			      """)) {
			statement.setString(1, projectId);
			try (ResultSet resultSet = statement.executeQuery()) {
				if (!resultSet.next()) {
					return null;
				}
				return new SubmitWindowRecord(
					resultSet.getString("project_id"),
					resultSet.getString("snapshot_hash"),
					resultSet.getLong("sequence"),
					resultSet.getLong("allowed_credential_id"),
					parseCredentialIds(resultSet.getString("attempted_credential_ids")),
					resultSet.getLong("created_at"),
					resultSet.getLong("window_started_at"),
					resultSet.getLong("updated_at")
				);
			}
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to load submit window", exception);
		}
	}

	public synchronized void upsertSubmitWindow(SubmitWindowRecord record) {
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement("""
			      INSERT INTO submit_windows (project_id, snapshot_hash, sequence, allowed_credential_id, attempted_credential_ids, created_at, window_started_at, updated_at)
			      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
			      ON CONFLICT(project_id)
			      DO UPDATE SET snapshot_hash = excluded.snapshot_hash,
			      	sequence = excluded.sequence,
			      	allowed_credential_id = excluded.allowed_credential_id,
			      	attempted_credential_ids = excluded.attempted_credential_ids,
			      	created_at = excluded.created_at,
			      	window_started_at = excluded.window_started_at,
			      	updated_at = excluded.updated_at
			      """)) {
			statement.setString(1, record.projectId());
			statement.setString(2, record.snapshotHash());
			statement.setLong(3, record.sequence());
			statement.setLong(4, record.allowedCredentialId());
			statement.setString(5, joinCredentialIds(record.attemptedCredentialIds()));
			statement.setLong(6, record.createdAt());
			statement.setLong(7, record.windowStartedAt());
			statement.setLong(8, record.updatedAt());
			statement.executeUpdate();
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to save submit window", exception);
		}
	}

	public synchronized void clearSubmitWindow(String projectId) {
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement(
			      "DELETE FROM submit_windows WHERE project_id = ?"
		     )) {
			statement.setString(1, projectId);
			statement.executeUpdate();
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to clear submit window", exception);
		}
	}

	public synchronized void revokeCredentialsForDiscordUser(String projectId, String discordUserId, long nowMillis) {
		try (Connection connection = connection()) {
			try (PreparedStatement revokeContributor = connection.prepareStatement(
				"UPDATE contributors SET revoked_at = ? WHERE project_id = ? AND discord_user_id = ?"
			)) {
				revokeContributor.setLong(1, nowMillis);
				revokeContributor.setString(2, projectId);
				revokeContributor.setString(3, discordUserId);
				revokeContributor.executeUpdate();
			}

			try (PreparedStatement revokeCredentials = connection.prepareStatement("""
				UPDATE device_credentials
				SET revoked_at = ?
				WHERE credential_id IN (
					SELECT dc.credential_id
					FROM device_credentials dc
					JOIN contributors c ON c.contributor_id = dc.contributor_id
					WHERE c.project_id = ? AND c.discord_user_id = ?
				)
				""")) {
				revokeCredentials.setLong(1, nowMillis);
				revokeCredentials.setString(2, projectId);
				revokeCredentials.setString(3, discordUserId);
				revokeCredentials.executeUpdate();
			}

			try (PreparedStatement clearLeases = connection.prepareStatement("""
				DELETE FROM reporter_leases
				WHERE project_id = ? AND credential_id IN (
					SELECT dc.credential_id
					FROM device_credentials dc
					JOIN contributors c ON c.contributor_id = dc.contributor_id
					WHERE c.project_id = ? AND c.discord_user_id = ?
				)
				""")) {
				clearLeases.setString(1, projectId);
				clearLeases.setString(2, projectId);
				clearLeases.setString(3, discordUserId);
				clearLeases.executeUpdate();
			}

			try (PreparedStatement clearChain = connection.prepareStatement("""
				DELETE FROM reporter_chain_slots
				WHERE project_id = ? AND credential_id IN (
					SELECT dc.credential_id
					FROM device_credentials dc
					JOIN contributors c ON c.contributor_id = dc.contributor_id
					WHERE c.project_id = ? AND c.discord_user_id = ?
				)
				""")) {
				clearChain.setString(1, projectId);
				clearChain.setString(2, projectId);
				clearChain.setString(3, discordUserId);
				clearChain.executeUpdate();
			}

			try (PreparedStatement clearSubmitWindow = connection.prepareStatement("""
				DELETE FROM submit_windows
				WHERE project_id = ? AND allowed_credential_id IN (
					SELECT dc.credential_id
					FROM device_credentials dc
					JOIN contributors c ON c.contributor_id = dc.contributor_id
					WHERE c.project_id = ? AND c.discord_user_id = ?
				)
				""")) {
				clearSubmitWindow.setString(1, projectId);
				clearSubmitWindow.setString(2, projectId);
				clearSubmitWindow.setString(3, discordUserId);
				clearSubmitWindow.executeUpdate();
			}
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to revoke contributor credentials", exception);
		}
	}

	public synchronized void revokeCredential(String projectId, String tokenHash, long nowMillis) {
		try (Connection connection = connection()) {
			try (PreparedStatement statement = connection.prepareStatement("""
			      UPDATE device_credentials
			      SET revoked_at = ?
			      WHERE token_hash = ? AND credential_id IN (
			      	SELECT dc.credential_id
			      	FROM device_credentials dc
			      	JOIN contributors c ON c.contributor_id = dc.contributor_id
			      	WHERE c.project_id = ?
			      )
			      """)) {
				statement.setLong(1, nowMillis);
				statement.setString(2, tokenHash);
				statement.setString(3, projectId);
				statement.executeUpdate();
			}

			try (PreparedStatement clearLease = connection.prepareStatement("""
				DELETE FROM reporter_leases
				WHERE project_id = ? AND credential_id IN (
					SELECT dc.credential_id
					FROM device_credentials dc
					JOIN contributors c ON c.contributor_id = dc.contributor_id
					WHERE dc.token_hash = ? AND c.project_id = ?
				)
				""")) {
				clearLease.setString(1, projectId);
				clearLease.setString(2, tokenHash);
				clearLease.setString(3, projectId);
				clearLease.executeUpdate();
			}

			try (PreparedStatement clearChain = connection.prepareStatement("""
				DELETE FROM reporter_chain_slots
				WHERE project_id = ? AND credential_id IN (
					SELECT dc.credential_id
					FROM device_credentials dc
					JOIN contributors c ON c.contributor_id = dc.contributor_id
					WHERE dc.token_hash = ? AND c.project_id = ?
				)
				""")) {
				clearChain.setString(1, projectId);
				clearChain.setString(2, tokenHash);
				clearChain.setString(3, projectId);
				clearChain.executeUpdate();
			}

			try (PreparedStatement clearSubmitWindow = connection.prepareStatement("""
				DELETE FROM submit_windows
				WHERE project_id = ? AND allowed_credential_id IN (
					SELECT dc.credential_id
					FROM device_credentials dc
					JOIN contributors c ON c.contributor_id = dc.contributor_id
					WHERE dc.token_hash = ? AND c.project_id = ?
				)
				""")) {
				clearSubmitWindow.setString(1, projectId);
				clearSubmitWindow.setString(2, tokenHash);
				clearSubmitWindow.setString(3, projectId);
				clearSubmitWindow.executeUpdate();
			}
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to revoke device credential", exception);
		}
	}

	private void updateDeviceReporterFields(
		String projectId,
		long credentialId,
		Long lastStatusAt,
		Long lastProofAt,
		Long lastSnapshotAt,
		Long eligibleAt
	) {
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement("""
			      UPDATE device_credentials
			      SET last_status_at = COALESCE(?, last_status_at),
			          last_proof_at = COALESCE(?, last_proof_at),
			          last_snapshot_at = COALESCE(?, last_snapshot_at),
			          eligible_at = COALESCE(eligible_at, ?),
			          role_updated_at = CASE WHEN eligible_at IS NULL AND ? IS NOT NULL THEN ? ELSE role_updated_at END
			      WHERE credential_id = ? AND contributor_id IN (
			      	SELECT contributor_id FROM contributors WHERE project_id = ?
			      )
			      """)) {
			bindNullableLong(statement, 1, lastStatusAt);
			bindNullableLong(statement, 2, lastProofAt);
			bindNullableLong(statement, 3, lastSnapshotAt);
			bindNullableLong(statement, 4, eligibleAt);
			bindNullableLong(statement, 5, eligibleAt);
			bindNullableLong(statement, 6, eligibleAt);
			statement.setLong(7, credentialId);
			statement.setString(8, projectId);
			statement.executeUpdate();
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to update reporter device fields", exception);
		}
	}

	private void touchRoleUpdatedAt(long credentialId, long nowMillis) {
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement(
			      "UPDATE device_credentials SET role_updated_at = ? WHERE credential_id = ?"
		     )) {
			statement.setLong(1, nowMillis);
			statement.setLong(2, credentialId);
			statement.executeUpdate();
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to touch reporter role timestamp", exception);
		}
	}

	private ActiveBombRecord findActiveBomb(String projectId, String server, BombType bombType) {
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement("""
			      SELECT project_id, server, bomb_type, user_name, start_time_millis, expires_at_millis, source
			      FROM active_bombs
			      WHERE project_id = ? AND server = ? AND bomb_type = ?
			      """)) {
			statement.setString(1, projectId);
			statement.setString(2, normalizeServer(server));
			statement.setString(3, bombType.name());
			try (ResultSet resultSet = statement.executeQuery()) {
				if (!resultSet.next()) {
					return null;
				}

				return new ActiveBombRecord(
					resultSet.getString("project_id"),
					normalizeServer(resultSet.getString("server")),
					BombType.valueOf(resultSet.getString("bomb_type")),
					resultSet.getString("user_name"),
					resultSet.getLong("start_time_millis"),
					resultSet.getLong("expires_at_millis"),
					BombSource.valueOf(resultSet.getString("source"))
				);
			}
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to look up active bomb", exception);
		}
	}

	private void insertActiveBomb(String projectId, BombSnapshotItem item, long nowMillis) {
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement("""
			      INSERT INTO active_bombs (project_id, server, bomb_type, user_name, start_time_millis, expires_at_millis, source, updated_at)
			      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
			      """)) {
			statement.setString(1, projectId);
			statement.setString(2, item.server());
			statement.setString(3, item.bombType().name());
			statement.setString(4, normalizeUser(item.user()));
			statement.setLong(5, item.startTimeMillis());
			statement.setLong(6, item.expiresAtMillis());
			statement.setString(7, item.source().name());
			statement.setLong(8, nowMillis);
			statement.executeUpdate();
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to insert active bomb", exception);
		}
	}

	private void updateActiveBomb(String projectId, BombSnapshotItem item, long nowMillis) {
		try (Connection connection = connection();
		     PreparedStatement statement = connection.prepareStatement("""
			      UPDATE active_bombs
			      SET user_name = ?, start_time_millis = ?, expires_at_millis = ?, source = ?, updated_at = ?
			      WHERE project_id = ? AND server = ? AND bomb_type = ?
			      """)) {
			statement.setString(1, normalizeUser(item.user()));
			statement.setLong(2, item.startTimeMillis());
			statement.setLong(3, item.expiresAtMillis());
			statement.setString(4, item.source().name());
			statement.setLong(5, nowMillis);
			statement.setString(6, projectId);
			statement.setString(7, item.server());
			statement.setString(8, item.bombType().name());
			statement.executeUpdate();
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to update active bomb", exception);
		}
	}

	private ReporterDeviceRecord mapReporterDevice(ResultSet resultSet) throws Exception {
		return new ReporterDeviceRecord(
			resultSet.getString("project_id"),
			resultSet.getLong("contributor_id"),
			resultSet.getLong("credential_id"),
			resultSet.getString("discord_user_id"),
			resultSet.getString("discord_username"),
			resultSet.getString("token_prefix"),
			resultSet.getLong("last_seen_at"),
			resultSet.getLong("last_status_at"),
			resultSet.getLong("last_proof_at"),
			resultSet.getLong("last_snapshot_at"),
			nullableLong(resultSet, "eligible_at"),
			nullableLong(resultSet, "role_updated_at")
		);
	}

	private void migrateDashboardMessages(Connection connection) throws Exception {
		try (PreparedStatement statement = connection.prepareStatement("""
			      INSERT OR IGNORE INTO dashboard_message_slots (project_id, message_slot, message_id, payload_hash, updated_at)
			      SELECT project_id, 'BOMB_' || bomb_type, message_id, payload_hash, updated_at
			      FROM dashboard_messages
			      """)) {
			statement.executeUpdate();
		}
	}

	private static void ensureColumn(Connection connection, String tableName, String columnName, String columnDefinition) throws Exception {
		try (PreparedStatement statement = connection.prepareStatement("PRAGMA table_info(" + tableName + ")");
		     ResultSet resultSet = statement.executeQuery()) {
			while (resultSet.next()) {
				if (columnName.equalsIgnoreCase(resultSet.getString("name"))) {
					return;
				}
			}
		}

		try (Statement alter = connection.createStatement()) {
			alter.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnDefinition);
		}
	}

	private static BombSnapshotItem normalize(BombSnapshotItem item) {
		if (item == null || item.bombType() == null || item.source() == null) {
			return null;
		}

		String server = normalizeServer(item.server());
		if (server.isBlank()) {
			return null;
		}

		long nowMillis = System.currentTimeMillis();
		long expiresAtMillis = item.expiresAtMillis();
		if (expiresAtMillis <= nowMillis || expiresAtMillis - nowMillis > MAX_ACCEPTED_DURATION_MILLIS) {
			return null;
		}

		long startTimeMillis = item.startTimeMillis();
		if (startTimeMillis <= 0L || startTimeMillis > expiresAtMillis) {
			startTimeMillis = expiresAtMillis - (item.bombType().activeMinutes() * 60_000L);
		}

		return new BombSnapshotItem(
			item.bombType(),
			server,
			normalizeUser(item.user()),
			startTimeMillis,
			expiresAtMillis,
			item.source()
		);
	}

	private static boolean shouldReplace(ActiveBombRecord existing, BombSnapshotItem incoming) {
		int existingPriority = sourcePriority(existing.source());
		int incomingPriority = sourcePriority(incoming.source());
		if (incomingPriority > existingPriority) {
			return true;
		}
		if (incomingPriority < existingPriority) {
			return false;
		}
		if (incoming.expiresAtMillis() > existing.expiresAtMillis()) {
			return true;
		}
		if (incoming.expiresAtMillis() < existing.expiresAtMillis()) {
			return false;
		}
		return !normalizeUser(incoming.user()).equals(existing.user()) && "Unknown".equals(existing.user());
	}

	private static boolean shouldPatchUser(ActiveBombRecord existing, BombSnapshotItem incoming) {
		return existing.expiresAtMillis() == incoming.expiresAtMillis()
			&& sourcePriority(existing.source()) == sourcePriority(incoming.source())
			&& "Unknown".equals(existing.user())
			&& !"Unknown".equals(normalizeUser(incoming.user()));
	}

	private static int sourcePriority(BombSource source) {
		return switch (source) {
			case CHAT_BELL -> 2;
			case LOCAL_BOSSBAR -> 1;
		};
	}

	private static String normalizeServer(String value) {
		String sanitized = DisplayTextSanitizer.sanitizeInline(value);
		if (sanitized.isBlank()) {
			return "";
		}
		return sanitized.replaceAll("\\s+", "").toUpperCase();
	}

	private static String normalizeUser(String value) {
		return DisplayTextSanitizer.sanitizeName(value, "Unknown");
	}

	private Connection connection() throws Exception {
		ensureSqliteParentDirectory();
		Connection connection = DriverManager.getConnection(jdbcUrl);
		try (Statement statement = connection.createStatement()) {
			statement.execute("PRAGMA foreign_keys = ON");
		}
		return connection;
	}

	private void ensureSqliteParentDirectory() throws Exception {
		if (!jdbcUrl.startsWith("jdbc:sqlite:")) {
			return;
		}

		String sqliteTarget = jdbcUrl.substring("jdbc:sqlite:".length());
		if (sqliteTarget.isBlank() || ":memory:".equalsIgnoreCase(sqliteTarget) || sqliteTarget.startsWith("file:")) {
			return;
		}

		int queryIndex = sqliteTarget.indexOf('?');
		if (queryIndex >= 0) {
			sqliteTarget = sqliteTarget.substring(0, queryIndex);
		}

		Path databasePath = Path.of(sqliteTarget);
		Path parent = databasePath.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
	}

	private static void bindNullableLong(PreparedStatement statement, int parameterIndex, Long value) throws Exception {
		if (value == null) {
			statement.setObject(parameterIndex, null);
		} else {
			statement.setLong(parameterIndex, value);
		}
	}

	private static Long nullableLong(ResultSet resultSet, String columnName) throws Exception {
		Object value = resultSet.getObject(columnName);
		if (value == null) {
			return null;
		}
		return ((Number) value).longValue();
	}

	private static String joinCredentialIds(List<Long> credentialIds) {
		if (credentialIds == null || credentialIds.isEmpty()) {
			return "";
		}

		StringBuilder builder = new StringBuilder();
		for (Long credentialId : credentialIds) {
			if (credentialId == null || credentialId <= 0L) {
				continue;
			}
			if (!builder.isEmpty()) {
				builder.append(',');
			}
			builder.append(credentialId.longValue());
		}
		return builder.toString();
	}

	private static List<Long> parseCredentialIds(String rawValue) {
		List<Long> values = new ArrayList<>();
		if (rawValue == null || rawValue.isBlank()) {
			return values;
		}

		for (String part : rawValue.split(",")) {
			String trimmed = part.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			try {
				values.add(Long.parseLong(trimmed));
			} catch (NumberFormatException ignored) {
			}
		}
		return values;
	}

	private static String joinBombTypes(List<BombType> bombTypes) {
		if (bombTypes == null || bombTypes.isEmpty()) {
			return "";
		}

		StringBuilder builder = new StringBuilder();
		for (BombType bombType : bombTypes) {
			if (bombType == null) {
				continue;
			}
			if (!builder.isEmpty()) {
				builder.append(',');
			}
			builder.append(bombType.name());
		}
		return builder.toString();
	}

	private static List<BombType> parseBombTypes(String rawValue) {
		List<BombType> bombTypes = new ArrayList<>();
		if (rawValue == null || rawValue.isBlank()) {
			return bombTypes;
		}

		for (String part : rawValue.split(",")) {
			String trimmed = part.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			try {
				bombTypes.add(BombType.valueOf(trimmed));
			} catch (IllegalArgumentException ignored) {
			}
		}

		return bombTypes;
	}

	public record ProjectRecord(String projectId, String guildId, String channelId, String dashboardName) {
	}

	public record DashboardMessageRecord(String projectId, String slotId, String messageId, String payloadHash) {
	}

	public record DashboardBombTypeSettingRecord(String projectId, BombType bombType, boolean enabled, int displayOrder) {
	}

	public record DashboardComboRecord(
		String projectId,
		String normalizedName,
		String displayName,
		List<BombType> bombTypes,
		DashboardComboSortMode sortMode,
		int displayOrder,
		long createdAt,
		long updatedAt
	) {
	}

	public record ContributorRecord(long contributorId, String projectId, String discordUserId, String discordUsername) {
	}

	public record EnrollmentGrant(String projectId, String discordUserId, String discordUsername) {
	}

	public record AuthenticatedDevice(
		String projectId,
		long contributorId,
		long credentialId,
		String discordUserId,
		String discordUsername,
		String tokenPrefix
	) {
	}

	public record LeaderLeaseRecord(String projectId, long credentialId, long acquiredAt, long updatedAt) {
	}

	public record ReporterChainSlotRecord(
		String projectId,
		ReporterRole role,
		long credentialId,
		long assignedAt,
		long updatedAt,
		int missCount
	) {
	}

	public record SubmitWindowRecord(
		String projectId,
		String snapshotHash,
		long sequence,
		long allowedCredentialId,
		List<Long> attemptedCredentialIds,
		long createdAt,
		long windowStartedAt,
		long updatedAt
	) {
	}

	public record ReporterDeviceRecord(
		String projectId,
		long contributorId,
		long credentialId,
		String discordUserId,
		String discordUsername,
		String tokenPrefix,
		long lastSeenAt,
		long lastStatusAt,
		long lastProofAt,
		long lastSnapshotAt,
		Long eligibleAt,
		Long roleUpdatedAt
	) {
		public boolean eligible() {
			return eligibleAt != null && eligibleAt > 0L;
		}

		public long lastActiveAt() {
			return Math.max(Math.max(lastSeenAt, lastStatusAt), Math.max(lastProofAt, lastSnapshotAt));
		}
	}

	public record ActiveBombRecord(
		String projectId,
		String server,
		BombType bombType,
		String user,
		long startTimeMillis,
		long expiresAtMillis,
		BombSource source
	) {
	}
}
