package com.github.JnCrMx.discordlobbies.client.gui.screen;

import com.github.JnCrMx.discordlobbies.DiscordLobbiesMod;
import com.mojang.blaze3d.matrix.MatrixStack;
import de.jcm.discordgamesdk.Core;
import de.jcm.discordgamesdk.Result;
import de.jcm.discordgamesdk.image.ImageDimensions;
import de.jcm.discordgamesdk.image.ImageHandle;
import de.jcm.discordgamesdk.image.ImageType;
import de.jcm.discordgamesdk.lobby.Lobby;
import de.jcm.discordgamesdk.lobby.LobbySearchQuery;
import de.jcm.discordgamesdk.user.DiscordUser;
import net.minecraft.client.gui.DialogTexts;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class DiscordMultiplayerScreen extends Screen
{
	public class LobbyData
	{
		private final Lobby lobby;
		private final DiscordUser owner;
		private final int playerCount;

		private final String mcWorld;
		private final String mcOwner;
		private final String mcMotd;

		private final ArtifactVersion minecraftVersion;
		private final ArtifactVersion forgeVersion;
		private final ArtifactVersion modVersion;

		public LobbyData(Lobby lobby)
		{
			this.lobby = lobby;
			this.owner = core.lobbyManager().getMemberUser(lobby, lobby.getOwnerId());
			this.playerCount = core.lobbyManager().memberCount(lobby);

			Map<String, String> metadata = core.lobbyManager().getLobbyMetadata(lobby);
			this.mcWorld = metadata.getOrDefault("minecraft.world", "Unknown world");
			this.mcOwner = metadata.getOrDefault("minecraft.owner", "Unknown owner");
			this.mcMotd = metadata.getOrDefault("minecraft.motd", "No MotD");

			this.minecraftVersion =
					Optional.ofNullable(metadata.getOrDefault("minecraft.version", null))
					        .map(DefaultArtifactVersion::new)
					        .orElse(null);
			this.forgeVersion =
					Optional.ofNullable(metadata.getOrDefault("forge.version", null))
					        .map(DefaultArtifactVersion::new)
					        .orElse(null);
			this.modVersion =
					Optional.ofNullable(metadata.getOrDefault("lobby.version", null))
					        .map(DefaultArtifactVersion::new)
					        .orElse(null);
		}

		public long getId()
		{
			return lobby.getId();
		}

		public Lobby getLobby()
		{
			return lobby;
		}

		public DiscordUser getOwner()
		{
			return owner;
		}

		public int getPlayerCount()
		{
			return playerCount;
		}

		public String getMinecraftWorld()
		{
			return mcWorld;
		}

		public String getMinecraftOwner()
		{
			return mcOwner;
		}

		public String getMinecraftMotd()
		{
			return mcMotd;
		}

		public ITextComponent getVersionMessage()
		{
			if(modVersion == null)
				return new TranslationTextComponent("versionConflict.mod.unknown")
						.mergeStyle(TextFormatting.RED);
			if(forgeVersion == null)
				return new TranslationTextComponent("versionConflict.forge.unknown")
						.mergeStyle(TextFormatting.RED);
			if(minecraftVersion == null)
				return new TranslationTextComponent("versionConflict.minecraft.unknown")
						.mergeStyle(TextFormatting.RED);

			if(modVersion.getMajorVersion() != DiscordLobbiesMod.MY_VERSION.getMajorVersion())
				return new TranslationTextComponent("versionConflict.mod.incompatible", modVersion.toString())
						.mergeStyle(TextFormatting.RED);
			if(modVersion.getMinorVersion() != DiscordLobbiesMod.MY_VERSION.getMinorVersion())
				return new TranslationTextComponent("versionConflict.mod.different", modVersion.toString())
						.mergeStyle(TextFormatting.YELLOW);

			if(forgeVersion.getMajorVersion() != DiscordLobbiesMod.FORGE_VERSION.getMajorVersion())
				return new TranslationTextComponent("versionConflict.forge.incompatible", forgeVersion.toString())
						.mergeStyle(TextFormatting.RED);
			if(forgeVersion.getMinorVersion() != DiscordLobbiesMod.FORGE_VERSION.getMinorVersion())
				return new TranslationTextComponent("versionConflict.forge.different", forgeVersion.toString())
						.mergeStyle(TextFormatting.YELLOW);

			if(minecraftVersion.getMinorVersion() != DiscordLobbiesMod.MINECRAFT_VERSION.getMinorVersion())
				return new TranslationTextComponent("versionConflict.minecraft.incompatible", minecraftVersion.toString())
						.mergeStyle(TextFormatting.RED);
			if(minecraftVersion.getIncrementalVersion() != DiscordLobbiesMod.MINECRAFT_VERSION.getIncrementalVersion())
				return new TranslationTextComponent("versionConflict.minecraft.different", minecraftVersion.toString())
						.mergeStyle(TextFormatting.YELLOW);

			return null;
		}
	}

	private final Screen previousScreen;
	private final Core core;

	private boolean initialized;

	private final Map<Long, LobbyData> searchResults = Collections.synchronizedMap(new HashMap<>());

	private ScheduledExecutorService executor;
	private ScheduledFuture<?> searchFuture;
	private Consumer<Result> listUpdater;

	protected LobbySelectionList lobbySelectionList;

	private Button connectButton;

	public DiscordMultiplayerScreen(Screen previousScreen, Core core)
	{
		super(new TranslationTextComponent("discordMultiplayer.title"));
		this.previousScreen = previousScreen;
		this.core = core;
	}

	@Override
	protected void init()
	{
		super.init();
		assert this.minecraft != null;

		if(initialized)
		{
			lobbySelectionList.updateSize(this.width, this.height, 32, this.height - 64);
		}
		else
		{
			initialized = true;

			this.lobbySelectionList = new LobbySelectionList(this, this.minecraft, this.width, this.height,
			                                                 32, this.height - 64, 36,
			                                                 this::onSelected);
			this.lobbySelectionList.updateLobbies(searchResults);

			listUpdater = result -> {
				synchronized(this.searchResults)
				{
					List<Lobby> lobbies = core.lobbyManager().getLobbies();
					searchResults.clear();

					lobbies.stream()
					       .map(LobbyData::new)
					       .forEach(l->searchResults.put(l.getId(), l));

					this.lobbySelectionList.updateLobbies(searchResults);
				}
			};

			executor = Executors.newSingleThreadScheduledExecutor();
			searchFuture = executor.scheduleAtFixedRate(()->{
				LobbySearchQuery query = core.lobbyManager().getSearchQuery();
				core.lobbyManager().search(query, listUpdater);
			}, 250, 2500, TimeUnit.MILLISECONDS);
		}
		this.children.add(lobbySelectionList);

		this.connectButton = this.addButton(new Button(this.width / 2 - 60 - 4 - 100, this.height - 52,
		                                               100, 20,
		                                               new TranslationTextComponent("discordMultiplayer.connect"),
		                                               e -> {
			LobbySelectionList.LobbyEntry selection = lobbySelectionList.getSelected();
			if(selection != null)
			{
				connectToLobby(selection);
			}
		}));
		this.addButton(new Button(this.width / 2 - 60, this.height - 52,
		                          120, 20,
		                          new TranslationTextComponent("discordMultiplayer.directConnect"),
		                          e -> this.minecraft.displayGuiScreen(
		                          		new LobbyDirectConnectScreen(this))));
		this.addButton(new Button(this.width / 2 + 4 + 60, this.height - 52,
		                          100, 20,
		                          DialogTexts.GUI_CANCEL,
		                          e -> this.minecraft.displayGuiScreen(this.previousScreen)));
		onSelected(null);
	}

	private void onSelected(LobbySelectionList.LobbyEntry entry)
	{
		connectButton.active = entry != null;
	}

	public void connectToLobby(@NotNull LobbySelectionList.LobbyEntry entry)
	{
		assert this.minecraft != null;
		this.minecraft.displayGuiScreen(new DiscordConnectingScreen(this.previousScreen, entry.getLobby()));
	}

	@Override
	public void onClose()
	{
		if(searchFuture != null)
		{
			searchFuture.cancel(false);
		}
		executor.shutdown();
	}

	@Override
	public void closeScreen()
	{
		assert this.minecraft != null;
		this.minecraft.displayGuiScreen(previousScreen);
	}

	@Override
	public void render(@NotNull MatrixStack matrixStack, int mouseX, int mouseY, float partialTicks)
	{
		this.renderBackground(matrixStack);
		drawCenteredString(matrixStack, this.font, this.title, this.width / 2, 20, 16777215);
		this.lobbySelectionList.render(matrixStack, mouseX, mouseY, partialTicks);
		super.render(matrixStack, mouseX, mouseY, partialTicks);
	}

	void fetchProfilePicture(long userId, BiConsumer<ImageDimensions, byte[]> consumer)
	{
		core.imageManager().fetch(new ImageHandle(ImageType.USER, userId, 256), false, (result, handle) -> {
			if(result == Result.OK)
			{
				ImageDimensions dimensions = core.imageManager().getDimensions(handle);
				byte[] data = core.imageManager().getData(handle, dimensions);
				consumer.accept(dimensions, data);
			}
		});
	}
}
