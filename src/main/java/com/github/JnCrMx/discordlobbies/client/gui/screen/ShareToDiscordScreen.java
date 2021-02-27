package com.github.JnCrMx.discordlobbies.client.gui.screen;

import com.github.JnCrMx.discordlobbies.DiscordLobbiesMod;
import com.github.JnCrMx.discordlobbies.network.LobbyServer;
import com.mojang.blaze3d.matrix.MatrixStack;
import de.jcm.discordgamesdk.lobby.LobbyType;
import net.minecraft.client.gui.DialogTexts;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.client.gui.widget.button.CheckboxButton;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@OnlyIn(Dist.CLIENT)
public class ShareToDiscordScreen extends Screen
{
	private static final Logger LOGGER = LogManager.getLogger();
	private static final ITextComponent TYPE_TEXT = new TranslationTextComponent("discordLobby.type");

	private final Screen previousScreen;
	private final boolean update;
	private final LobbyServer server;
	private final boolean locked;
	private final boolean setAsActivity;

	private LobbyType type;

	private Button typeButton;
	private CheckboxButton lockButton;
	private CheckboxButton activityButton;

	public ShareToDiscordScreen(Screen previousScreen)
	{
		super(new TranslationTextComponent("discordLobby.title"));
		this.previousScreen = previousScreen;

		this.update = false;
		this.server = null;

		this.type = LobbyType.PUBLIC;
		this.locked = false;
		this.setAsActivity = true;
	}

	public ShareToDiscordScreen(Screen previousScreen, LobbyServer server)
	{
		super(new TranslationTextComponent("discordLobby.title"));
		this.previousScreen = previousScreen;

		this.update = true;
		this.server = server;

		this.type = server.getLobby().getType();
		this.locked = server.getLobby().isLocked();
		this.setAsActivity = server.isSetAsActivity();
	}

	@Override
	protected void init()
	{
		super.init();

		assert minecraft != null;
		assert minecraft.player != null;

		ITextComponent finishButtonText;
		Button.IPressable finishAction;
		if(update)
		{
			finishButtonText = new TranslationTextComponent("shareToDiscord.update");
			finishAction = e->{
				this.minecraft.displayGuiScreen(null);

				server.updateLobby(type, lockButton.isChecked(), activityButton.isChecked())
				      .thenCompose(v -> activityButton.isChecked() ? server.setActivity() : server.clearActivity())
				      .thenRun(() -> this.minecraft.ingameGUI.getChatGUI().printChatMessage(
				      		new TranslationTextComponent("shareToDiscord.updated", server.getActivitySecret())))
				      .whenComplete((r,t)->{
					      if(t == null) return;
					      LOGGER.error("Failed to update lobby", t);
					      this.minecraft.ingameGUI.getChatGUI().printChatMessage(
					      		new TranslationTextComponent("shareToDiscord.failed", t.getMessage()));
				      });
			};
		}
		else
		{
			finishButtonText = new TranslationTextComponent("shareToDiscord.start");
			finishAction = e->{
				this.minecraft.displayGuiScreen(null);

				assert minecraft.getIntegratedServer() != null;
				LobbyServer server = new LobbyServer(minecraft.getIntegratedServer(), DiscordLobbiesMod.core);
				DiscordLobbiesMod.theServer = server;
				DiscordLobbiesMod.eventHandler.addListener(server);
				server.createLobby(type, lockButton.isChecked(), activityButton.isChecked())
				      .thenCompose(v -> server.selfJoin(minecraft.player))
				      .thenCompose(v -> activityButton.isChecked() ? server.setActivity() : server.clearActivity())
				      .thenRun(() -> this.minecraft.ingameGUI.getChatGUI().printChatMessage(
				      		new TranslationTextComponent("shareToDiscord.started", server.getActivitySecret())))
				      .whenComplete((r,t)->{
					      if(t == null) return;
					      LOGGER.error("Failed to create lobby", t);
					      this.minecraft.ingameGUI.getChatGUI().printChatMessage(
					      		new TranslationTextComponent("shareToDiscord.failed", t.getMessage()));

					      DiscordLobbiesMod.eventHandler.removeListener(server);
				      });
			};
		}
		this.addButton(new Button(this.width / 2 - 155, this.height - 28, 150, 20, finishButtonText, finishAction));
		this.addButton(new Button(this.width / 2 + 5, this.height - 28, 150, 20, DialogTexts.GUI_CANCEL, (e) -> this.minecraft.displayGuiScreen(this.previousScreen)));

		this.typeButton = this.addButton(new Button(this.width / 2 - 155, 100, 150, 20, StringTextComponent.EMPTY, (e) -> {
			int index = ArrayUtils.indexOf(LobbyType.values(), type);
			index = (index + 1) % LobbyType.values().length;
			type = LobbyType.values()[index];
			updateDisplayNames();
		}));
		this.lockButton = this.addButton(
				new CheckboxButton(this.width / 2 + 50, 100, 150, 20,
				                   new TranslationTextComponent("discordLobby.locked"),
				                   locked));

		ITextComponent activityText = new TranslationTextComponent("shareToDiscord.setAsActivity");
		int w = minecraft.fontRenderer.getStringPropertyWidth(activityText) + 24;
		this.activityButton = this.addButton(
				new CheckboxButton(this.width / 2 - w/2, 150, 150, 20,
				                   activityText, setAsActivity));

		updateDisplayNames();
	}

	private void updateDisplayNames()
	{
		this.typeButton.setMessage(new TranslationTextComponent("options.generic_value",
		                                                        TYPE_TEXT,
		                                                        new TranslationTextComponent("discordLobby.type." + type.name())));
	}

	@Override
	public void render(MatrixStack matrixStack, int mouseX, int mouseY, float partialTicks)
	{
		this.renderBackground(matrixStack);
		drawCenteredString(matrixStack, this.font, this.title, this.width / 2, 50, 16777215);
		super.render(matrixStack, mouseX, mouseY, partialTicks);
	}

	@Override
	public void closeScreen()
	{
		assert minecraft != null;
		minecraft.displayGuiScreen(previousScreen);
	}
}
