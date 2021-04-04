package com.github.JnCrMx.discordlobbies;

import com.github.JnCrMx.discordlobbies.client.ActivityJoinListener;
import com.github.JnCrMx.discordlobbies.network.LobbyClient;
import com.github.JnCrMx.discordlobbies.network.LobbyServer;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import de.jcm.discordgamesdk.*;
import de.jcm.discordgamesdk.user.DiscordUser;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Mod(modid = DiscordLobbiesMod.MOD_ID, name = DiscordLobbiesMod.MOD_ID)
public class DiscordLobbiesMod
{
	public static File downloadDiscordLibrary() throws IOException
	{
		// Find out which name Discord's library has (.dll for Windows, .so for Linux)
		String name = "discord_game_sdk";
		String suffix;
		if(System.getProperty("os.name").toLowerCase().contains("windows"))
		{
			suffix = ".dll";
		}
		else
		{
			suffix = ".so";
		}

		// Path of Discord's library inside the ZIP
		String zipPath = "lib/x86_64/"+name+suffix;

		// Open the URL as a ZipInputStream
		URL downloadUrl = new URL("https://dl-game-sdk.discordapp.net/2.5.6/discord_game_sdk.zip");

		URLConnection connection = downloadUrl.openConnection();
		connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Macintosh; U; Intel Mac OS X 10.4; en-US; rv:1.9.2.2) Gecko/20100316 Firefox/3.6.2");

		ZipInputStream zin = new ZipInputStream(connection.getInputStream());

		// Search for the right file inside the ZIP
		ZipEntry entry;
		while((entry = zin.getNextEntry())!=null)
		{
			if(entry.getName().equals(zipPath))
			{
				// Create a new temporary directory
				// We need to do this, because we may not change the filename on Windows
				File tempDir = new File(System.getProperty("java.io.tmpdir"), "java-"+name+System.nanoTime());
				if(!tempDir.mkdir())
					throw new IOException("Cannot create temporary directory");
				tempDir.deleteOnExit();

				// Create a temporary file inside our directory (with a "normal" name)
				File temp = new File(tempDir, name+suffix);
				temp.deleteOnExit();

				// Copy the file from the ZIP to our temporary file
				Files.copy(zin, temp.toPath());

				// We are done, so close the input stream
				zin.close();

				// Return our temporary file
				return temp;
			}
			// next entry
			zin.closeEntry();
		}
		zin.close();
		// We couldn't find the library inside the ZIP
		return null;
	}

	public static final String MOD_ID = "discordlobbies";
	// Directly reference a log4j logger.
	private static final Logger LOGGER = LogManager.getLogger();
	private static final Logger DISCORD_LOGGER = LogManager.getLogger("DiscordGameSDK");
	public static final boolean DEV = !FMLLoader.isProduction();

	public static ArtifactVersion MINECRAFT_VERSION;
	public static ArtifactVersion FORGE_VERSION;
	public static ArtifactVersion MY_VERSION;

	private static CreateParams createParams;
	public static Core core;
	public static boolean discordPresent;
	public static DiscordEventHandler eventHandler;

	public static DiscordUser theUser;
	public static String myRoute;

	public static LobbyServer theServer;
	public static LobbyClient theClient;

	public DiscordLobbiesMod()
	{
		MinecraftForge.EVENT_BUS.register(this);

		MY_VERSION = ModLoadingContext.get().getActiveContainer().getModInfo().getVersion();
		MINECRAFT_VERSION = ModList
				.get().getModContainerById("minecraft")
				.map(ModContainer::getModInfo)
				.map(IModInfo::getVersion)
				.orElse(new DefaultArtifactVersion("MISSING"));
		FORGE_VERSION = ModList
				.get().getModContainerById("forge")
				.map(ModContainer::getModInfo)
				.map(IModInfo::getVersion)
				.orElse(new DefaultArtifactVersion("MISSING"));
	}

	@Mod.EventHandler
	public void init(FMLInitializationEvent event)
	{
		try
		{
			File discordLibrary = downloadDiscordLibrary();
			if(discordLibrary == null)
			{
				LOGGER.fatal("Cannot download Discord Game SDK library!");
				discordPresent = false;
				return;
			}
			Core.init(discordLibrary);
		}
		catch(IOException e)
		{
			LOGGER.fatal("Cannot download Discord Game SDK library!", e);
			discordPresent = false;
			return;
		}

		eventHandler = new DiscordEventHandler();
		eventHandler.addListener(new DiscordEventAdapter()
		{
			@Override
			public void onRouteUpdate(String routeData)
			{
				myRoute = routeData;
			}

			@Override
			public void onCurrentUserUpdate()
			{
				theUser = core.userManager().getCurrentUser();
			}
		});

		createParams = new CreateParams();
		createParams.setFlags(1);
		createParams.setClientID(792838852557537310L);
		createParams.registerEventHandler(eventHandler);

		try
		{
			core = new Core(createParams);
			core.runCallbacks();
			core.setLogHook(LogLevel.DEBUG, (level, message)->{
				Level level1 = Level.INFO;
				switch(level)
				{
					case ERROR:
						level1 = Level.ERROR;
						break;
					case WARN:
						level1 = Level.WARN;
						break;
					case INFO:
						level1 = Level.INFO;
						break;
					case DEBUG:
						level1 = Level.DEBUG;
						break;
				}

				DISCORD_LOGGER.log(level1, message);
			});
			LOGGER.info("Initialized Discord Game SDK!");
			discordPresent = true;
		}
		catch(GameSDKException ex)
		{
			LOGGER.error("Cannot initialize Discord Game SDK!", ex);
			discordPresent = false;
		}
	}

	@Mod.EventHandler
	public void clientSetup(FMLPostInitializationEvent event)
	{
		if(discordPresent)
			eventHandler.addListener(new ActivityJoinListener(Minecraft.getMinecraft()));
	}

	@SubscribeEvent
	public void onClientTick(TickEvent.ClientTickEvent event)
	{
		if(!discordPresent)
			return;

		if(core != null && theServer == null)
		{
			if(event.phase == TickEvent.Phase.START)
			{
				long t1 = System.currentTimeMillis();
				core.runCallbacks();
				long time = System.currentTimeMillis() - t1;
				if(time > 10)
					LOGGER.warn("Client runCallbacks() took {} ms", time);
			}
			else if(event.phase == TickEvent.Phase.END)
			{
				long t1 = System.currentTimeMillis();
				core.networkManager().flush();
				long time = System.currentTimeMillis() - t1;
				if(time > 10)
					LOGGER.warn("Client flush() took {} ms", time);
				if(theClient != null)
					theClient.onFlush();
			}
		}
	}

	@SubscribeEvent
	public void onServerTick(TickEvent.ServerTickEvent event)
	{
		if(!discordPresent)
			return;

		if(core != null && theServer != null)
		{
			if(event.phase == TickEvent.Phase.START)
			{
				long t1 = System.currentTimeMillis();
				core.runCallbacks();
				long time = System.currentTimeMillis() - t1;
				if(time > 10)
					LOGGER.warn("Server runCallbacks() took {} ms", time);
			}
			else if(event.phase == TickEvent.Phase.END)
			{
				long t1 = System.currentTimeMillis();
				core.networkManager().flush();
				long time = System.currentTimeMillis() - t1;
				if(time > 10)
					LOGGER.warn("Server flush() took {} ms", time);
				theServer.onFlush();
			}
		}
	}

	@SubscribeEvent
	public void onServerStopping(FMLServerStoppingEvent event)
	{
		if(!discordPresent)
			return;

		if(theServer != null)
		{
			LOGGER.info("Closing lobby server...");
			eventHandler.removeListener(theServer);
			theServer.deleteLobby().thenRun(()-> LOGGER.info("Deleted lobby."));
			theServer = null;
		}
	}
}
