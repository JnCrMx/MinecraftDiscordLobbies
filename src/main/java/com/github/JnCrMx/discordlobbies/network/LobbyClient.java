package com.github.JnCrMx.discordlobbies.network;

import com.github.JnCrMx.discordlobbies.DiscordLobbiesMod;
import de.jcm.discordgamesdk.Core;
import de.jcm.discordgamesdk.DiscordEventAdapter;
import de.jcm.discordgamesdk.DiscordUtils;
import de.jcm.discordgamesdk.activity.Activity;
import de.jcm.discordgamesdk.lobby.Lobby;
import de.jcm.discordgamesdk.lobby.LobbyMemberTransaction;
import de.jcm.discordgamesdk.user.DiscordUser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.SucceededFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.PacketDirection;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.Session;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraftforge.fml.network.NetworkHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.github.JnCrMx.discordlobbies.network.NetworkConstants.CHANNELS;
import static com.github.JnCrMx.discordlobbies.network.NetworkConstants.READY_MESSAGE;

public class LobbyClient extends DiscordEventAdapter implements LobbyCommunicator
{
	private static final Logger LOGGER = LogManager.getLogger();

	private final Core core;

	private final long userId;
	private Lobby lobby;
	private boolean lobbyShareable;
	private boolean setAsActivity;

	private DiscordUser serverUser;
	private long serverPeerId;

	private CompletableFuture<Void> awaitServerReady;

	private DiscordNetworkManager networkManager;

	private int compressionThreshold;
	private final ArrayList<GenericFutureListener<? extends Future<? super Void>>> listeners = new ArrayList<>();

	public LobbyClient(Core core)
	{
		this.core = core;

		this.userId = DiscordLobbiesMod.theUser.getUserId();
	}

	public CompletableFuture<Void> connectToLobby(long lobbyId, String secret)
	{
		CompletableFuture<Lobby> future = new CompletableFuture<>();
		core.lobbyManager().connectLobby(lobbyId, secret, DiscordUtils.returningCompleter(future));

		return future.thenAccept(lobby->{
			this.lobby = lobby;
			this.lobbyShareable = Boolean.parseBoolean(
					core.lobbyManager().getLobbyMetadataValue(lobby, "lobby.shareable"));
		});
	}

	public CompletableFuture<Void> connectToLobby(String activitySecret)
	{
		CompletableFuture<Lobby> future = new CompletableFuture<>();
		core.lobbyManager().connectLobbyWithActivitySecret(activitySecret, DiscordUtils.returningCompleter(future));

		return future.thenAccept(lobby->{
			this.lobby = lobby;
			this.lobbyShareable = Boolean.parseBoolean(
					core.lobbyManager().getLobbyMetadataValue(lobby, "lobby.shareable"));
		});
	}

	public CompletableFuture<Void> prepareNetworking(Session session)
	{
		Map<String, String> metadata = core.lobbyManager().getMemberMetadata(lobby, lobby.getOwnerId());

		if(!metadata.containsKey("network.peer_id") || !metadata.containsKey("network.route"))
			throw new RuntimeException("cannot reach server member");

		this.serverUser = core.lobbyManager().getMemberUser(lobby, lobby.getOwnerId());

		this.serverPeerId = Long.parseUnsignedLong(metadata.get("network.peer_id"));
		String serverRoute = metadata.get("network.route");
		core.networkManager().openPeer(serverPeerId, serverRoute);
		for(int i=0; i<CHANNELS.length; i++)
			core.networkManager().openChannel(serverPeerId, (byte) i, CHANNELS[i].isReliable());

		LobbyMemberTransaction mTxn = core.lobbyManager().getMemberUpdateTransaction(lobby, userId);
		mTxn.setMetadata("minecraft.username", session.getUsername());
		mTxn.setMetadata("minecraft.uuid", session.getPlayerID());
		mTxn.setMetadata("minecraft.version", DiscordLobbiesMod.MINECRAFT_VERSION.toString());

		mTxn.setMetadata("lobby.version", DiscordLobbiesMod.MY_VERSION.toString());

		mTxn.setMetadata("forge.version", DiscordLobbiesMod.FORGE_VERSION.toString());

		mTxn.setMetadata("network.peer_id", Long.toUnsignedString(core.networkManager().getPeerId()));
		if(DiscordLobbiesMod.myRoute != null)
			mTxn.setMetadata("network.route", DiscordLobbiesMod.myRoute);

		awaitServerReady = new CompletableFuture<>();

		CompletableFuture<Void> future = new CompletableFuture<>();
		core.lobbyManager().updateMember(lobby, userId, mTxn, DiscordUtils.completer(future));

		return future.thenCombine(awaitServerReady, (a,b)->null);
	}

	public CompletableFuture<Void> setActivity()
	{
		try(Activity activity = new Activity())
		{
			activity.setState(core.lobbyManager().getLobbyMetadataValue(lobby, "minecraft.owner"));
			activity.setDetails(core.lobbyManager().getLobbyMetadataValue(lobby, "minecraft.world"));
			activity.setInstance(true);
			activity.party().setID(String.valueOf(lobby.getId()));
			activity.party().size().setCurrentSize(core.lobbyManager().memberCount(lobby));
			activity.party().size().setMaxSize(lobby.getCapacity());
			activity.secrets().setJoinSecret(core.lobbyManager().getLobbyActivitySecret(lobby));

			CompletableFuture<Void> future = new CompletableFuture<>();
			core.activityManager().updateActivity(activity, DiscordUtils.completer(future));

			return future.thenAccept(v->this.setAsActivity = true);
		}
	}

	public CompletableFuture<Void> clearActivity()
	{
		CompletableFuture<Void> future = new CompletableFuture<>();
		core.activityManager().clearActivity(DiscordUtils.completer(future));

		return future.thenAccept(v->this.setAsActivity = false);
	}

	@Override
	public void onMemberUpdate(long lobbyId, long userId)
	{
		if(userId == this.userId)
			return;
		if(lobbyId != lobby.getId())
			return;
		if(userId != lobby.getOwnerId())
			return;

		Map<String, String> metadata = core.lobbyManager().getMemberMetadata(lobby, userId);
		if(!metadata.containsKey("network.peer_id") || !metadata.containsKey("network.route"))
			return;

		String username = serverUser.getUsername()+"#"+serverUser.getDiscriminator();

		long peerId = Long.parseUnsignedLong(metadata.get("network.peer_id"));
		String route = metadata.get("network.route");

		if(peerId != serverPeerId)
		{
			LOGGER.warn("Peer id of user {} ({}) changed from {} to {}.",
			            username, userId,
			            Long.toUnsignedString(serverPeerId),
			            Long.toUnsignedString(peerId));

			for(int i=0; i<CHANNELS.length; i++)
				core.networkManager().closeChannel(serverPeerId, (byte) i);
			core.networkManager().closePeer(serverPeerId);

			serverPeerId = peerId;
			core.networkManager().openPeer(peerId, route);
			for(int i=0; i<CHANNELS.length; i++)
				core.networkManager().openChannel(peerId, (byte) i, CHANNELS[i].isReliable());
		}
		else
		{
			LOGGER.debug("Received new route for user {} ({}) aka peer {}: {}",
			             username, userId,
			             Long.toUnsignedString(peerId),
			             route);
			core.networkManager().updatePeer(peerId, route);
		}
	}

	@Override
	public void sendData(ByteBuf buf, long userId, byte channel,
	                     @Nullable GenericFutureListener<? extends Future<? super Void>> listener)
	{
		if(userId != lobby.getOwnerId())
			throw new IllegalArgumentException("user is not the server");
		if(serverPeerId == -1)
			throw new IllegalStateException("connection is closed");

		ByteBuf buf2 = NetworkUtils.compress(buf, Unpooled.directBuffer(), compressionThreshold);

		int len = buf2.readableBytes();
		byte[] array = new byte[len];
		buf2.readBytes(array);

		core.networkManager().sendMessage(serverPeerId, channel, array);

		if(listener != null)
		{
			listeners.add(listener);
		}
	}

	@Override
	public void onMemberConnect(long lobbyId, long userId)
	{
		if(setAsActivity)
		{
			// update current Lobby size
			setActivity();
		}
	}

	@Override
	public void onMemberDisconnect(long lobbyId, long userId)
	{
		if(lobbyId != lobby.getId())
			return;

		if(userId == this.userId)
			onLobbyDelete(lobbyId, 0);

		if(userId == serverUser.getUserId())
		{
			Lobby lobby = core.lobbyManager().getLobby(lobbyId);
			if(lobby.getOwnerId() == this.userId)
			{
				core.lobbyManager().deleteLobby(lobbyId);
			}
			else
			{
				onLobbyDelete(lobbyId, 0);
			}
		}

		if(setAsActivity)
		{
			// update current Lobby size
			setActivity();
		}
	}

	@Override
	public void onLobbyDelete(long lobbyId, int reason)
	{
		if(lobbyId != lobby.getId())
			return;

		if(networkManager.isChannelOpen())
		{
			networkManager.closeChannel(new ChatComponentTranslation("disconnect.lobbyClosed"));
		}
	}

	@Override
	public void closeConnection(long userId)
	{
		if(userId != lobby.getOwnerId())
			throw new IllegalArgumentException("user is not the server");

		for(int i=0; i<CHANNELS.length; i++)
			core.networkManager().closeChannel(serverPeerId, (byte) i);
		core.networkManager().closePeer(serverPeerId);

		serverPeerId = -1;

		core.lobbyManager().disconnectLobby(lobby, Core.DEFAULT_CALLBACK
				.andThen(r->LOGGER.info("Left the lobby due to closed connection.")));
		if(setAsActivity)
		{
			clearActivity();
		}

		DiscordLobbiesMod.eventHandler.removeListener(this);
	}

	@Override
	public void setCompressionThreshold(int threshold)
	{
		this.compressionThreshold = threshold;
	}

	@Override
	public void onMessage(long peerId, byte channelId, byte[] data)
	{
		if(Arrays.equals(data, READY_MESSAGE))
		{
			LOGGER.info("Connection ready.");

			networkManager = new DiscordNetworkManager(PacketDirection.CLIENTBOUND, this, serverUser.getUserId());
			NetworkHooks.registerClientLoginChannel(networkManager);

			awaitServerReady.complete(null);
		}
		else
		{
			if(channelId >= CHANNELS.length)
				return;
			if(peerId != serverPeerId)
			{
				LOGGER.warn("Message from peer {} which is not the server ({})", peerId, serverPeerId);
				return;
			}

			if(data.length == 0)
			{
				LOGGER.warn("Received empty message!");
				return;
			}

			ByteBuf cBuf = Unpooled.wrappedBuffer(data);
			ByteBuf buf = NetworkUtils.decompress(cBuf, Unpooled.directBuffer());

			if(CHANNELS[channelId].isBuffered())
			{
				PacketBuffer packetBuffer = new PacketBuffer(buf);
				while(packetBuffer.readableBytes() > 0)
				{
					int size = packetBuffer.readVarInt();
					ByteBuf partBuf = Unpooled.buffer(size);
					packetBuffer.readBytes(partBuf);
					processMessage(partBuf, peerId, channelId);
				}
			}
			else
			{
				processMessage(buf, peerId, channelId);
			}
		}
	}

	public void onFlush()
	{
		Future<? extends Void> future = new SucceededFuture<>(GlobalEventExecutor.INSTANCE, null);
		listeners.forEach(future::addListener);
		listeners.clear();
	}

	private void processMessage(ByteBuf buf, long peerId, byte channel)
	{
		try
		{
			networkManager.readPacket(buf, channel);
		}
		catch(IOException e)
		{
			LOGGER.error("Failed to read packet from peer "+peerId, e);
		}
	}

	public DiscordNetworkManager networkManager()
	{
		return networkManager;
	}

	@Override
	public void onLobbyUpdate(long lobbyId)
	{
		if(lobbyId != lobby.getId())
			return;

		this.lobbyShareable = Boolean.parseBoolean(
				core.lobbyManager().getLobbyMetadataValue(lobby, "lobby.shareable"));

		if(!lobbyShareable && setAsActivity)
		{
			clearActivity().thenRun(
					()->Minecraft.getInstance().ingameGUI.getChatGUI().printChatMessage(
							new TranslationTextComponent("clientShare.forbidden"))).whenComplete((r, t)->{
				if(t == null) return;
				Minecraft.getInstance().ingameGUI.getChatGUI().printChatMessage(
						new TranslationTextComponent("clientShare.failed", t.getMessage()));
			});
			setAsActivity = false;
		}
	}

	public boolean isShareable()
	{
		return lobbyShareable;
	}

	public boolean isSetAsActivity()
	{
		return setAsActivity;
	}
}
