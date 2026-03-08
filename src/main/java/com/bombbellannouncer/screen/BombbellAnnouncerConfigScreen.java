package com.bombbellannouncer.screen;

import com.bombbellannouncer.BombbellAnnouncerClientMod;
import com.bombbellannouncer.config.BombbellAnnouncerConfig;
import com.bombbellannouncer.protocol.ReporterRole;
import com.bombbellannouncer.relayclient.RelayEnrollmentService;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public final class BombbellAnnouncerConfigScreen extends Screen {
	private final Screen parent;
	private final BombbellAnnouncerConfig config;
	private TextFieldWidget setupBundleField;
	private ButtonWidget enabledButton;
	private ButtonWidget connectButton;
	private ButtonWidget disconnectButton;
	private ButtonWidget saveButton;
	private boolean enabled;
	private String statusMessage;
	private boolean busy;

	public BombbellAnnouncerConfigScreen(Screen parent, BombbellAnnouncerConfig config) {
		super(Text.literal("WynnData"));
		this.parent = parent;
		this.config = config;
		this.enabled = config.enabled();
		this.statusMessage = buildConnectionSummary();
	}

	@Override
	protected void init() {
		int centerX = this.width / 2;
		int left = centerX - 155;

		enabledButton = this.addDrawableChild(ButtonWidget.builder(getEnabledLabel(), button -> {
			enabled = !enabled;
			button.setMessage(getEnabledLabel());
		}).dimensions(left, 70, 310, 20).build());

		setupBundleField = new TextFieldWidget(this.textRenderer, left, 125, 310, 20, Text.literal("Enrollment Bundle"));
		setupBundleField.setMaxLength(4096);
		this.addDrawableChild(setupBundleField);

		connectButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Connect"), button -> connectWithBundle())
			.dimensions(left, 160, 150, 20)
			.build());

		disconnectButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Disconnect"), button -> disconnectSession())
			.dimensions(left + 160, 160, 150, 20)
			.build());

		saveButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Save"), button -> saveAndClose())
			.dimensions(centerX - 155, this.height - 30, 150, 20)
			.build());

		this.addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), button -> close())
			.dimensions(centerX + 5, this.height - 30, 150, 20)
			.build());

		setInitialFocus(setupBundleField);
		refreshButtonStates();
	}

	@Override
	public void close() {
		if (client != null) {
			client.setScreen(parent);
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderBackground(context, mouseX, mouseY, delta);
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 20, 0xFFFFFF);
		context.drawTextWithShadow(this.textRenderer, Text.literal("Enabled"), this.width / 2 - 155, 58, 0xA0A0A0);
		context.drawTextWithShadow(this.textRenderer, Text.literal("Paste setup bundle from /bombbell enroll"), this.width / 2 - 155, 113, 0xA0A0A0);
		context.drawTextWithShadow(
			this.textRenderer,
			Text.literal(statusMessage),
			this.width / 2 - 155,
			195,
			0x808080
		);
		context.drawTextWithShadow(
			this.textRenderer,
			Text.literal(buildConnectionSummary()),
			this.width / 2 - 155,
			207,
			0x808080
		);
		context.drawTextWithShadow(
			this.textRenderer,
			Text.literal(buildRoleSummary()),
			this.width / 2 - 155,
			219,
			0x808080
		);
		context.drawTextWithShadow(
			this.textRenderer,
			Text.literal("Changes save to config/wynndata.json"),
			this.width / 2 - 155,
			231,
			0x808080
		);
	}

	private Text getEnabledLabel() {
		return Text.literal("Relay Publishing: " + (enabled ? "On" : "Off"));
	}

	private void saveAndClose() {
		config.setEnabled(enabled);
		config.save();
		close();
	}

	private void connectWithBundle() {
		if (busy) {
			return;
		}

		busy = true;
		statusMessage = "Connecting to relay...";
		refreshButtonStates();

		CompletableFuture.supplyAsync(() -> BombbellAnnouncerClientMod.redeemSetupBundle(setupBundleField.getText()))
			.whenComplete((result, error) -> {
				if (client == null) {
					return;
				}

				client.execute(() -> {
					busy = false;
					if (error != null) {
						statusMessage = "Failed to connect: " + Objects.toString(error.getMessage(), "unknown error");
					} else {
						RelayEnrollmentService.ActionResult actionResult = result;
						statusMessage = actionResult.message();
						if (actionResult.success()) {
							setupBundleField.setText("");
						}
					}
					refreshButtonStates();
				});
			});
	}

	private void disconnectSession() {
		if (busy) {
			return;
		}

		busy = true;
		statusMessage = "Disconnecting session...";
		refreshButtonStates();

		CompletableFuture.supplyAsync(BombbellAnnouncerClientMod::disconnectRelaySession)
			.whenComplete((result, error) -> {
				if (client == null) {
					return;
				}

				client.execute(() -> {
					busy = false;
					if (error != null) {
						statusMessage = "Failed to disconnect: " + Objects.toString(error.getMessage(), "unknown error");
					} else {
						statusMessage = result.message();
					}
					refreshButtonStates();
				});
			});
	}

	private void refreshButtonStates() {
		if (enabledButton != null) {
			enabledButton.active = !busy;
		}
		if (connectButton != null) {
			connectButton.active = !busy;
		}
		if (disconnectButton != null) {
			disconnectButton.active = !busy && config.hasRelaySession();
		}
		if (saveButton != null) {
			saveButton.active = !busy;
		}
	}

	private String buildConnectionSummary() {
		if (!config.hasRelaySession()) {
			return "Not connected to a relay dashboard.";
		}

		String dashboard = config.dashboardName().isBlank() ? config.projectId() : config.dashboardName();
		String user = config.linkedDiscordUser().isBlank() ? "unknown user" : config.linkedDiscordUser();
		return "Connected as " + user + " to " + dashboard + ".";
	}

	private String buildRoleSummary() {
		if (!config.hasRelaySession()) {
			return "Reporter slot: not connected.";
		}

		ReporterRole reporterRole = config.reporterRole();
		return "Reporter slot: " + reporterRole.displayName() + ".";
	}
}
