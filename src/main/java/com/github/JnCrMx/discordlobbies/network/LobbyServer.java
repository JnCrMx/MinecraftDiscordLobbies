package com.github.JnCrMx.discordlobbies.network;

import com.github.JnCrMx.discordlobbies.DiscordLobbiesMod;
import de.jcm.discordgamesdk.Core;
import de.jcm.discordgamesdk.DiscordEventAdapter;
import de.jcm.discordgamesdk.DiscordUtils;
import de.jcm.discordgamesdk.activity.Activity;
import de.jcm.discordgamesdk.lobby.Lobby;
import de.jcm.discordgamesdk.lobby.LobbyMemberTransaction;
import de.jcm.discordgamesdk.lobby.LobbyTransaction;
import de.jcm.discordgamesdk.lobby.LobbyType;
import de.jcm.discordgamesdk.user.DiscordUser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.SucceededFuture;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.NetworkSystem;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.PacketDirection;
import net.minecraft.network.handshake.ServerHandshakeNetHandler;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.Util;
import net.minecraft.util.text.ChatType;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static com.github.JnCrMx.discordlobbies.network.NetworkConstants.*;

public class LobbyServer extends DiscordEventAdapter implements LobbyCommunicator
{
	private static final Logger LOGGER = LogManager.getLogger();

	private final IntegratedServer server;
	private final Core core;

	private final long userId;
	private Lobby lobby;
	private boolean setAsActivity;

	private final Map<Long, DiscordUser> userCache = new HashMap<>();

	private final Map<Long, Long> peerIds = new HashMap<>();
	private final Map<Long, DiscordNetworkManager> networkManagers = new HashMap<>();

	private int compressionThreshold;
	private final PacketBuffer[] channelBuffers = Stream.of(CHANNELS)
	                                                    .mapToInt(ChannelConfig::getBufferSize)
	                                                    .mapToObj(Unpooled::directBuffer)
	                                                    .map(PacketBuffer::new)
	                                                    .toArray(PacketBuffer[]::new);

	private final ArrayList<GenericFutureListener<? extends Future<? super Void>>> listeners = new ArrayList<>();

	public LobbyServer(IntegratedServer server, Core core)
	{
		this.server = server;
		this.core = core;

		this.userId = DiscordLobbiesMod.theUser.getUserId();

		server.setOnlineMode(false);
		server.serverPort = 0;
	}

	public CompletableFuture<Void> createLobby(LobbyType type, boolean locked, boolean shareable)
	{
		LobbyTransaction txn = core.lobbyManager().getLobbyCreateTransaction();
		txn.setType(type);
		txn.setCapacity(server.getPlayerList().getMaxPlayers());
		txn.setLocked(locked);

		txn.setMetadata("minecraft.world", server.getServerConfiguration().getWorldName());
		txn.setMetadata("minecraft.owner", server.getServerOwner());
		txn.setMetadata("minecraft.motd", server.getMOTD());
		txn.setMetadata("minecraft.version", DiscordLobbiesMod.MINECRAFT_VERSION.toString());

		txn.setMetadata("lobby.shareable", Boolean.toString(shareable));
		txn.setMetadata("lobby.version", DiscordLobbiesMod.MY_VERSION.toString());

		txn.setMetadata("forge.version", DiscordLobbiesMod.FORGE_VERSION.toString());

		CompletableFuture<Lobby> future = new CompletableFuture<>();
		core.lobbyManager().createLobby(txn, DiscordUtils.returningCompleter(future));

		return future.thenAccept(lobby->this.lobby = lobby);
	}

	public CompletableFuture<Void> updateLobby(LobbyType type, boolean locked, boolean shareable)
	{
		LobbyTransaction txn = core.lobbyManager().getLobbyUpdateTransaction(lobby);
		txn.setType(type);
		txn.setLocked(locked);

		txn.setMetadata("lobby.shareable", Boolean.toString(shareable));

		CompletableFuture<Void> future = new CompletableFuture<>();
		core.lobbyManager().updateLobby(lobby, txn, DiscordUtils.completer(future));

		return future.thenAccept(ignored->this.lobby = core.lobbyManager().getLobby(lobby.getId()));
	}

	public CompletableFuture<Void> selfJoin(ClientPlayerEntity player)
	{
		LobbyMemberTransaction mTxn = core.lobbyManager().getMemberUpdateTransaction(lobby, userId);
		mTxn.setMetadata("minecraft.username", player.getGameProfile().getName());
		mTxn.setMetadata("minecraft.uuid", player.getGameProfile().getId().toString());
		mTxn.setMetadata("minecraft.version", DiscordLobbiesMod.MINECRAFT_VERSION.toString());

		mTxn.setMetadata("lobby.version", DiscordLobbiesMod.MY_VERSION.toString());

		mTxn.setMetadata("forge.version", DiscordLobbiesMod.FORGE_VERSION.toString());

		mTxn.setMetadata("network.peer_id", Long.toUnsignedString(core.networkManager().getPeerId()));
		if(DiscordLobbiesMod.myRoute != null)
			mTxn.setMetadata("network.route", DiscordLobbiesMod.myRoute);

		CompletableFuture<Void> future = new CompletableFuture<>();
		core.lobbyManager().updateMember(lobby, userId, mTxn, DiscordUtils.completer(future));

		return future;
	}

	public String getActivitySecret()
	{
		return core.lobbyManager().getLobbyActivitySecret(lobby);
	}

	public CompletableFuture<Void> setActivity()
	{
		try(Activity activity = new Activity())
		{
			activity.setState(server.getServerOwner());
			activity.setDetails(server.getServerConfiguration().getWorldName());
			activity.setInstance(true);
			activity.party().setID(String.valueOf(lobby.getId()));
			activity.party().size().setCurrentSize(core.lobbyManager().memberCount(lobby));
			activity.party().size().setMaxSize(lobby.getCapacity());
			activity.secrets().setJoinSecret(getActivitySecret());

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

	public CompletableFuture<Void> deleteLobby()
	{
		CompletableFuture<Void> future = new CompletableFuture<>();
		core.lobbyManager().deleteLobby(lobby, DiscordUtils.completer(future));

		return future.thenCompose(v->clearActivity());
	}

	@Override
	public void onMemberConnect(long lobbyId, long userId)
	{
		if(lobbyId != lobby.getId())
			return;

		DiscordUser user = core.lobbyManager().getMemberUser(lobbyId, userId);
		userCache.put(userId, user);
		String name = user.getUsername()+"#"+user.getDiscriminator();

		TranslationTextComponent c = new TranslationTextComponent("lobby.player.joined", name);
		server.getPlayerList().func_232641_a_(c.mergeStyle(TextFormatting.YELLOW), ChatType.SYSTEM, Util.DUMMY_UUID);

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

		DiscordUser user = userCache.remove(userId);
		String name = user.getUsername()+"#"+user.getDiscriminator();

		TranslationTextComponent c = new TranslationTextComponent("lobby.player.left", name);
		server.getPlayerList().func_232641_a_(c.mergeStyle(TextFormatting.YELLOW), ChatType.SYSTEM, Util.DUMMY_UUID);

		Long peerId = peerIds.get(userId);
		if(peerId != null)
		{
			NetworkManager networkManager = networkManagers.get(peerId);
			if(networkManager != null)
			{
				networkManager.closeChannel(new TranslationTextComponent("disconnect.quitting"));
			}
		}

		if(setAsActivity)
		{
			// update current Lobby size
			setActivity();
		}
	}

	@Override
	public void onMemberUpdate(long lobbyId, long userId)
	{
		if(userId == this.userId)
			return;
		if(lobbyId != lobby.getId())
			return;

		Map<String, String> metadata = core.lobbyManager().getMemberMetadata(lobby, userId);
		if(!metadata.containsKey("network.peer_id") || !metadata.containsKey("network.route"))
			return;

		DiscordUser user = userCache.get(userId);
		String username = user.getUsername()+"#"+user.getDiscriminator();

		long peerId = Long.parseUnsignedLong(metadata.get("network.peer_id"));
		String route = metadata.get("network.route");

		if(peerIds.containsKey(userId))
		{
			long oldPeerId = peerIds.get(userId);
			if(peerId != oldPeerId)
			{
				LOGGER.warn("Peer id of user {} ({}) changed from {} to {}.",
				            username, userId,
				            Long.toUnsignedString(oldPeerId),
				            Long.toUnsignedString(peerId));

				for(int i=0; i<CHANNELS.length; i++)
					core.networkManager().closeChannel(peerId, (byte) i);
				core.networkManager().closePeer(peerId);

				peerIds.replace(userId, peerId);
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
		else
		{
			LOGGER.debug("Received initial route for user {} ({}) aka peer {}: {}",
			             username, userId,
			             Long.toUnsignedString(peerId),
			             route);
			peerIds.put(userId, peerId);

			core.networkManager().openPeer(peerId, route);
			for(int i=0; i<CHANNELS.length; i++)
				core.networkManager().openChannel(peerId, (byte) i, CHANNELS[i].isReliable());
			core.networkManager().sendMessage(peerId, DEFAULT_CHANNEL, READY_MESSAGE);

			DiscordNetworkManager manager = new DiscordNetworkManager(PacketDirection.SERVERBOUND, this, userId);
			manager.setNetHandler(new ServerHandshakeNetHandler(server, manager));
			this.networkManagers.put(peerId, manager);

			NetworkSystem networkSystem = server.getNetworkSystem();
			assert networkSystem != null;
			synchronized(networkSystem.networkManagers)
			{
				networkSystem.networkManagers.add(manager);
			}
			LOGGER.info("Connection to {} ready.", username);
		}
	}

	@Override
	public void sendData(ByteBuf buf, long userId, byte channel,
	                     @Nullable GenericFutureListener<? extends Future<? super Void>> listener)
	{
		if(!peerIds.containsKey(userId))
			throw new IllegalArgumentException("no peer ID known for user "+userId);
		long peerId = peerIds.get(userId);

		if(CHANNELS[channel].isBuffered())
		{
			int bufSize = buf.readableBytes();
			int totalSize = bufSize + PacketBuffer.getVarIntSize(bufSize);
			if(totalSize > channelBuffers[channel].writableBytes())
			{
				LOGGER.debug("Sending buffer of size {}/{} on channel {}.",
				             channelBuffers[channel].readableBytes(), CHANNELS[channel].getBufferSize(),
				             channel);
				compressAndSend(channelBuffers[channel], peerId, channel);
				channelBuffers[channel].clear();
				if(totalSize > channelBuffers[channel].writableBytes())
				{
					LOGGER.warn("Packet is to big for buffer on channel {}: {} > {}", channel,
					            totalSize, channelBuffers[channel].writableBytes());

					ByteBuf totalBuf = Unpooled.buffer(totalSize);
					PacketBuffer packetBuffer = new PacketBuffer(totalBuf);
					packetBuffer.writeVarInt(bufSize);
					packetBuffer.writeBytes(buf);
					compressAndSend(totalBuf, peerId, channel);

					if(listener != null)
						listeners.add(listener);

					return;
				}
			}
			channelBuffers[channel].writeVarInt(bufSize);
			channelBuffers[channel].writeBytes(buf);
		}
		else
		{
			compressAndSend(buf, peerId, channel);
			if(listener != null)
				listeners.add(listener);
		}
	}

	@Override
	public void closeConnection(long userId)
	{
		if(!peerIds.containsKey(userId))
			throw new IllegalArgumentException("no peer ID known for user "+userId);

		long peerId = peerIds.get(userId);

		for(int i=0; i<CHANNELS.length; i++)
			core.networkManager().closeChannel(peerId, (byte) i);
		core.networkManager().closePeer(peerId);

		peerIds.remove(userId);
	}

	private void compressAndSend(ByteBuf buf, long peerId, byte channel)
	{
		ByteBuf buf2 = NetworkUtils.compress(buf, Unpooled.buffer(), compressionThreshold);

		int len = buf2.readableBytes();
		byte[] array = new byte[len];
		buf2.readBytes(array);

		core.networkManager().sendMessage(peerId, channel, array);
	}

	@Override
	public void setCompressionThreshold(int threshold)
	{
		this.compressionThreshold = threshold;
	}

	@Override
	public void onMessage(long peerId, byte channelId, byte[] data)
	{
		if(channelId >= CHANNELS.length)
			return;
		if(!networkManagers.containsKey(peerId))
		{
			LOGGER.warn("Message from unknown peer {}", peerId);
			return;
		}
		DiscordNetworkManager networkManager = networkManagers.get(peerId);

		if(data.length == 0)
		{
			LOGGER.warn("Received empty message!");
			return;
		}

		ByteBuf buf = NetworkUtils.decompress(Unpooled.wrappedBuffer(data), Unpooled.directBuffer());
		try
		{
			networkManager.readPacket(buf, channelId);
		}
		catch(IOException e)
		{
			LOGGER.error("Failed to read packet from peer "+peerId, e);
		}
	}

	public void onFlush()
	{
		Future<? extends Void> future = new SucceededFuture<>(GlobalEventExecutor.INSTANCE, null);
		listeners.forEach(future::addListener);
		listeners.clear();
	}

	public Lobby getLobby()
	{
		return lobby;
	}

	public boolean isSetAsActivity()
	{
		return setAsActivity;
	}
}
