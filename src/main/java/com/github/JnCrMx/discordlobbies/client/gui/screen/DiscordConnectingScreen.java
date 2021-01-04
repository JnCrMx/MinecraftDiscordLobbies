package com.github.JnCrMx.discordlobbies.client.gui.screen;

import com.github.JnCrMx.discordlobbies.DiscordLobbiesMod;
import com.github.JnCrMx.discordlobbies.network.LobbyClient;
import com.mojang.blaze3d.matrix.MatrixStack;
import de.jcm.discordgamesdk.lobby.Lobby;
import net.minecraft.client.gui.DialogTexts;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.login.ClientLoginNetHandler;
import net.minecraft.network.ProtocolType;
import net.minecraft.network.handshake.client.CHandshakePacket;
import net.minecraft.network.login.client.CLoginStartPacket;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

public class DiscordConnectingScreen extends Screen
{
	private static final Logger LOGGER = LogManager.getLogger();

	private final Screen previousScreen;
	private LobbyClient client;

	private final long lobbyId;
	private final String secret;

	private final String activitySecret;

	private ITextComponent message = new TranslationTextComponent("connect.connecting");

	public DiscordConnectingScreen(Screen previousScreen, Lobby lobby)
	{
		this(previousScreen, lobby.getId(), lobby.getSecret());
	}

	public DiscordConnectingScreen(Screen previousScreen, long lobbyId, String secret)
	{
		super(StringTextComponent.EMPTY);
		this.previousScreen = previousScreen;
		this.lobbyId = lobbyId;
		this.secret = secret;
		this.activitySecret = null;

		connect();
	}

	public DiscordConnectingScreen(Screen previousScreen, String activitySecret)
	{
		super(StringTextComponent.EMPTY);
		this.previousScreen = previousScreen;
		this.lobbyId = -1;
		this.secret = null;
		this.activitySecret = activitySecret;

		connect();
	}

	public void connect()
	{
		assert minecraft != null;

		client = new LobbyClient(DiscordLobbiesMod.core);
		DiscordLobbiesMod.theClient = client;
		DiscordLobbiesMod.eventHandler.addListener(client);

		CompletableFuture<Void> future;
		if(lobbyId != -1 && secret != null)
			future = client.connectToLobby(lobbyId, secret);
		else
			future = client.connectToLobby(activitySecret);

		// Welcome to Lambda hell!
		future
				.whenComplete((r,t)->{
					if(t == null) return;
					LOGGER.error("Could not connect to lobby", t);
					minecraft.execute(()-> minecraft.displayGuiScreen(
							new DisconnectedScreen(previousScreen, DialogTexts.CONNECTION_FAILED,
							                       new TranslationTextComponent(
							                       		"disconnect.genericReason",
								                        "Could not connect to lobby: "+t.getMessage()))));

					DiscordLobbiesMod.eventHandler.removeListener(client);
				})
				.thenCompose(v->client
						.prepareNetworking(minecraft.getSession())
						.whenComplete((r,t)->{ // only add this handler, after the first stage succeeded
							if(t == null) return;
							LOGGER.error("Could not setup networking", t);
							minecraft.execute(()-> minecraft.displayGuiScreen(
									new DisconnectedScreen(previousScreen, DialogTexts.CONNECTION_FAILED,
									                       new TranslationTextComponent(
									                       		"disconnect.genericReason",
										                        "Could not setup networking: "+t.getMessage()))));

							DiscordLobbiesMod.eventHandler.removeListener(client);
						})
						.thenRun(()->{ // this will only execute if second stage succeeds
							try
							{
								client.networkManager().setNetHandler(
										new ClientLoginNetHandler(
												client.networkManager(), minecraft,
												previousScreen, this::handleMessage));
								client.networkManager().sendPacket(
										new CHandshakePacket("discord", 1, ProtocolType.LOGIN));
								client.networkManager().setConnectionState(ProtocolType.LOGIN);
								client.networkManager().sendPacket(
										new CLoginStartPacket(minecraft.getSession().getProfile()));
							}
							catch(Throwable t) // final "handler", we luckily need no whenComplete(...) for this
							{
								LOGGER.error("Could not join game", t);
								minecraft.execute(()-> minecraft.displayGuiScreen(
										new DisconnectedScreen(previousScreen, DialogTexts.CONNECTION_FAILED,
										                       new TranslationTextComponent(
										                       		"disconnect.genericReason",
											                        "Could not join game: "+t.getMessage()))));

								DiscordLobbiesMod.eventHandler.removeListener(client);
							}
						}));
	}

	private void handleMessage(ITextComponent message)
	{
		this.message = message;
	}

	@Override
	public void render(@NotNull MatrixStack matrixStack, int mouseX, int mouseY, float partialTicks)
	{
		this.renderBackground(matrixStack);

		drawCenteredString(matrixStack, this.font, this.message, this.width / 2, this.height / 2 - 50, 16777215);
		super.render(matrixStack, mouseX, mouseY, partialTicks);
	}

	@Override
	public boolean shouldCloseOnEsc()
	{
		return false;
	}

	@Override
	public void tick()
	{
		if(client != null && client.networkManager() != null)
		{
			if(client.networkManager().isChannelOpen())
			{
				client.networkManager().tick();
			}
			else
			{
				client.networkManager().handleDisconnection();
			}
		}
	}
}
