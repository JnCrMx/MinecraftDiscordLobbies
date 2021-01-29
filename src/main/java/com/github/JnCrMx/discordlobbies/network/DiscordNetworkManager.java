package com.github.JnCrMx.discordlobbies.network;

import com.github.JnCrMx.discordlobbies.DiscordLobbiesMod;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import net.minecraft.client.network.play.ClientPlayNetHandler;
import net.minecraft.network.*;
import net.minecraft.network.login.client.CCustomPayloadLoginPacket;
import net.minecraft.network.login.server.SCustomPayloadLoginPacket;
import net.minecraft.network.play.client.CKeepAlivePacket;
import net.minecraft.network.play.server.SJoinGamePacket;
import net.minecraft.network.play.server.SKeepAlivePacket;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.github.JnCrMx.discordlobbies.network.NetworkConstants.CHANNELS;
import static com.github.JnCrMx.discordlobbies.network.NetworkConstants.DEFAULT_CHANNEL;

public class DiscordNetworkManager extends NetworkManager
{
	private static final Logger LOGGER = LogManager.getLogger();
	private static final Marker INCOMING = MarkerManager.getMarker("INCOMING");
	private static final Marker OUTGOING = MarkerManager.getMarker("OUTGOING");

	private final LobbyCommunicator communicator;
	private final long userId;

	// fake channel to e.g. hold attributes
	private final Channel fakeChannel = new EmbeddedChannel();

	// for SCustomPayloadLoginPacket and CCustomPayloadLoginPacket
	private final AtomicInteger pingCount = new AtomicInteger(0);
	private final AtomicInteger pongCount = new AtomicInteger(0);
	private final Queue<Pair<IPacket<?>, GenericFutureListener<? extends Future<? super Void>>>> pingPongQueue = new ConcurrentLinkedQueue<>();

	private ProtocolType protocolType;

	private boolean open;
	private ITextComponent terminationReason;
	private boolean disconnected;

	// Server stuff
	private SKeepAlivePacket resendKeepAlive;
	private boolean resentKeepAlive;
	private boolean keepAliveReceived;

	// Client stuff
	private final ArrayList<Pair<Long, IPacket<?>>> inboundQueue = new ArrayList<>();

	// Statistics
	private final List<Map<Class<?>, Integer>> incomingStats = IntStream.range(0, CHANNELS.length)
	                                                                    .mapToObj(i->new HashMap<Class<?>, Integer>())
	                                                                    .collect(Collectors.toCollection(ArrayList::new));
	private final int[] channelLoads = new int[CHANNELS.length];

	public DiscordNetworkManager(PacketDirection packetDirection, LobbyCommunicator communicator, long userId)
	{
		super(packetDirection);
		this.communicator = communicator;
		this.userId = userId;
		this.open = true;
		this.protocolType = ProtocolType.HANDSHAKING;
	}

	@Override
	public @NotNull Channel channel()
	{
		return fakeChannel;
	}

	@Override
	public void sendPacket(@NotNull IPacket<?> packetIn, @Nullable GenericFutureListener<? extends Future<? super Void>> listener)
	{
		if(getDirection() == PacketDirection.SERVERBOUND &&
				packetIn instanceof SCustomPayloadLoginPacket)
		{
			/*
			SCustomPayloadLoginPacket and CCustomPayloadLoginPacket operate in
			a closed request/response scheme (see https://wiki.vg/Protocol#Login_Plugin_Request).
			In order to prevent issues with timing, we place all SCustomPayloadLoginPackets
			in a queue and only send the next request after received the response to the
			previous one.
			 */
			pingPongQueue.add(new ImmutablePair<>(packetIn, listener));
		}
		else
		{
			dispatchPacket(packetIn, listener);
		}
	}

	private void dispatchPacket(@NotNull IPacket<?> packetIn, @Nullable GenericFutureListener<? extends Future<? super Void>> listener)
	{
		if(!open)
		{
			LOGGER.warn("Trying to send packet {} to closed channel", packetIn.getClass());
			return;
		}

		ProtocolType type = ProtocolType.getFromPacket(packetIn);
		if(type != protocolType)
		{
			LOGGER.warn(OUTGOING,"ProtocolType changed from {} to {}.", protocolType, type);
			setConnectionState(type);
		}
		Integer packetId = type.getPacketId(getDirection() == PacketDirection.SERVERBOUND ?
				                                    PacketDirection.CLIENTBOUND :
				                                    PacketDirection.SERVERBOUND, packetIn);
		int[] channels = NetworkConstants.getChannelsForPacket(packetIn);
		byte channel = IntStream.of(channels)
		                        .mapToObj(i->new ImmutablePair<>(i, channelLoads[i]))
		                        .min(Comparator.comparing(Pair::getRight))
		                        .map(Pair::getLeft)
		                        .orElse((int)DEFAULT_CHANNEL).byteValue();

		//LOGGER.debug(OUTGOING, "["+channel+"] "+packetId+" + "+type+" -> "+packetIn.getClass());
		if(packetId == null)
		{
			throw new RuntimeException(new IOException("Can't serialize unregistered packet"));
		}
		ByteBuf buf = Unpooled.directBuffer();
		PacketBuffer packetBuffer = new PacketBuffer(buf);
		packetBuffer.writeLong(System.currentTimeMillis());
		packetBuffer.writeVarInt(packetId);
		try
		{
			packetIn.writePacketData(packetBuffer);
		}
		catch(IOException e)
		{
			throw new RuntimeException(e);
		}

		communicator.sendData(buf, userId, channel, listener);
		channelLoads[channel]++;

		if(getDirection() == PacketDirection.SERVERBOUND)
		{
			// store the very first SKeepAlivePacket to resend it
			if(!resentKeepAlive && resendKeepAlive == null && packetIn instanceof SKeepAlivePacket)
				resendKeepAlive = (SKeepAlivePacket) packetIn;
		}

		if(packetIn instanceof SKeepAlivePacket)
			LOGGER.debug("Server keep alive: {}", ((SKeepAlivePacket)packetIn).getId());
		if(packetIn instanceof CKeepAlivePacket)
			LOGGER.debug("Client keep alive: {}", ((CKeepAlivePacket)packetIn).getKey());
	}

	public void readPacket(ByteBuf buf, byte channel) throws IOException
	{
		if(!open)
		{
			LOGGER.warn("Received packet for closed channel {}.", channel);
			return;
		}

		PacketBuffer packetBuffer = new PacketBuffer(buf);

		if(getDirection() == PacketDirection.CLIENTBOUND)
		{
			/*
			Due to multiple channels (with different loads) the client might
			receive packets ahead of time. This might lead to some PLAY state
			packets coming in before the SLoginSuccessPacket (-> parsing errors)
			or the SJoinGamePacket (-> netHandler.world = null).
			In order to prevent errors caused by that, we put all packets that
			are not on the default channel (and therefore of the PLAY state)
			into a queue that we process in clientTick() after reaching PLAY
			state.
			 */
			if(
				(channel != DEFAULT_CHANNEL && protocolType != ProtocolType.PLAY) ||
				(protocolType == ProtocolType.PLAY && !inboundQueue.isEmpty()) ||
				(
					channel != DEFAULT_CHANNEL &&
					(
						!(getNetHandler() instanceof ClientPlayNetHandler) ||
						((ClientPlayNetHandler)getNetHandler()).getWorld() == null
					)
				)
			)
			{
				readAheadOfTimePacket(packetBuffer, channel);
				return;
			}
		}

		long time = packetBuffer.readLong();
		int i = packetBuffer.readVarInt();
		IPacket<?> packet = protocolType.getPacket(getDirection(), i);
		if(packet == null)
		{
			throw new IOException("Bad packet id "+i);
		}

		preprocessPacket(packet, time, channel);

		//LOGGER.debug(INCOMING, "["+channel+"] "+i+" + " + protocolType + " -> "+packet.getClass()+" @ "+buf.readableBytes());
		packet.readPacketData(packetBuffer);

		processPacket(packet);
	}

	private void readAheadOfTimePacket(PacketBuffer packetBuffer, byte channel) throws IOException
	{
		if(protocolType == ProtocolType.PLAY)
		{
			if(!(getNetHandler() instanceof ClientPlayNetHandler))
			{
				LOGGER.warn("Reading packet as ahead-of-time because ClientPlayNetHandler isn't there.");
			}
			else if(((ClientPlayNetHandler)getNetHandler()).getWorld() == null)
			{
				LOGGER.warn("Reading packet as ahead-of-time because client world is null.");
			}
			else if(!inboundQueue.isEmpty())
			{
				LOGGER.warn("Reading packet as ahead-of-time because queue is not empty: {} packets waiting for processing",
				            inboundQueue.size());
			}
			else
			{
				LOGGER.error("Reading packet as ahead-of-time for unknown reason.");
			}
		}

		long time = packetBuffer.readLong();
		int i = packetBuffer.readVarInt();
		IPacket<?> packet = ProtocolType.PLAY.getPacket(getDirection(), i);
		if(packet == null)
		{
			throw new IOException("Bad packet id "+i);
		}

		preprocessPacket(packet, time, channel);

		packet.readPacketData(packetBuffer);

		if(packet instanceof SJoinGamePacket)
			// if we don't process the SJoinGamePacket, netHandler.world stays null
			processPacket(packet);
		else
			inboundQueue.add(new ImmutablePair<>(time, packet));
	}

	private void preprocessPacket(IPacket<?> packet, long time, byte channel)
	{
		if(DiscordLobbiesMod.DEV)
		{
			long ping = System.currentTimeMillis() - time;
			if(ping > 1000)
			{
				Comparator<? super Map.Entry<Class<?>, Integer>> comparator = Map.Entry.comparingByValue();
				LOGGER.warn("Ping of {} ms on channel {} for packet {}:\n{}",
				            ping, channel, packet.getClass(),
				            incomingStats.get(channel).entrySet().stream()
				                         .sorted(comparator.reversed())
				                         .map(e -> e.getKey() + " -> " + e.getValue())
				                         .collect(Collectors.joining("\n")));
			}
		}

		int[] bestChannels = NetworkConstants.getChannelsForPacket(packet);
		if(ArrayUtils.contains(bestChannels, channel) ||
				(channel == DEFAULT_CHANNEL && bestChannels.length == 0))
		{
			if(DiscordLobbiesMod.DEV)
			{
				// only count packets that should be on this channel
				incomingStats.get(channel).merge(packet.getClass(), 1, Integer::sum);
			}
		}
		else
		{
			LOGGER.warn("Packet on wrong channel: {} should be on one of {} but is on {}.",
			            packet.getClass(), Arrays.toString(bestChannels), channel);
		}
	}

	private void processPacket(IPacket<?> packet)
	{
		if(packet instanceof SKeepAlivePacket)
			LOGGER.debug("Server keep alive: {}", ((SKeepAlivePacket)packet).getId());
		if(packet instanceof CKeepAlivePacket)
			LOGGER.debug("Client keep alive: {}", ((CKeepAlivePacket)packet).getKey());

		if(getDirection() == PacketDirection.SERVERBOUND) // server-only stuff
		{
			if(packet instanceof CCustomPayloadLoginPacket)
			{
				// We received an answer to our last SCustomPayloadLoginPacket.
				LOGGER.debug(INCOMING, "Pong #" + pongCount.incrementAndGet());
			}

			if(!resentKeepAlive && resendKeepAlive != null &&
					ProtocolType.getFromPacket(packet) == ProtocolType.PLAY)
			{
				// resend our SKeepAlivePacket once we reach PLAY state
				dispatchPacket(resendKeepAlive, null);
				resentKeepAlive = true;
			}
			if(packet instanceof CKeepAlivePacket)
			{
				// ignore duplicate CKeepAlivePacket in case both SKeepAlivePacket get an answer
				CKeepAlivePacket keepAlivePacket = (CKeepAlivePacket) packet;
				if(keepAlivePacket.getKey() == resendKeepAlive.getId())
				{
					if(keepAliveReceived)
					{
						return; // do not process duplicate keep alive
					}
					keepAliveReceived = true;
				}
			}
		}

		processPacket(packet, getNetHandler());
	}

	@Override
	public void closeChannel(@NotNull ITextComponent message)
	{
		open = false;
		communicator.closeConnection(userId);
		terminationReason = message;
	}

	@Nullable
	@Override
	public ITextComponent getExitMessage()
	{
		return terminationReason;
	}

	@Override
	public void handleDisconnection()
	{
		if(!isChannelOpen())
		{
			if(disconnected)
			{
				LOGGER.warn("handleDisconnection() called twice");
			}
			else
			{
				disconnected = true;
				ITextComponent exitMessage = getExitMessage();
				if(exitMessage != null)
					getNetHandler().onDisconnect(exitMessage);
				else
					getNetHandler().onDisconnect(new TranslationTextComponent("multiplayer.disconnect.generic"));
			}
		}
	}

	@Override
	public void setConnectionState(@NotNull ProtocolType newState)
	{
		this.protocolType = newState;
	}

	@Override
	public boolean isLocalChannel()
	{
		return false;
	}

	@Override
	public boolean hasNoChannel()
	{
		return false;
	}

	@Override
	public boolean isChannelOpen()
	{
		return open;
	}

	@Override
	public @NotNull SocketAddress getRemoteAddress()
	{
		try
		{
			// interpret userId as an IPv6 address
			ByteBuffer buffer = ByteBuffer.allocate(16);
			buffer.putLong(userId);
			buffer.putLong(userId);

			return new InetSocketAddress(Inet6Address.getByAddress(buffer.array()), 0);
		}
		catch(UnknownHostException e)
		{
			throw new RuntimeException(e);
		}
	}

	@Override
	public void setCompressionThreshold(int threshold)
	{
		communicator.setCompressionThreshold(threshold);
	}

	@Override
	public void disableAutoRead()
	{
		LOGGER.warn("auto read is not supported");
	}

	@Override
	public void tick()
	{
		super.tick();

		switch(getDirection())
		{
			case SERVERBOUND:
				serverTick();
				break;
			case CLIENTBOUND:
				clientTick();
				break;
		}
	}

	private void clientTick()
	{
		// process and clear incoming queue after reaching PLAY state
		if(protocolType == ProtocolType.PLAY &&
				getNetHandler() instanceof ClientPlayNetHandler &&
				((ClientPlayNetHandler)getNetHandler()).getWorld() != null &&
				!inboundQueue.isEmpty())
		{
			LOGGER.debug("Processing ahead-of-time packets: "+inboundQueue.size());
			inboundQueue.sort(Comparator.comparing(Pair::getLeft));
			Iterator<Pair<Long, IPacket<?>>> it = inboundQueue.iterator();
			while(it.hasNext())
			{
				Pair<Long, IPacket<?>> pair = it.next();
				processPacket(pair.getRight());
				it.remove();
			}
		}
	}

	private void serverTick()
	{
		// send next SCustomPayloadLoginPacket once we received answers to all previous ones
		if(pongCount.get() == pingCount.get())
		{
			Pair<IPacket<?>, GenericFutureListener<? extends Future<? super Void>>> pair = pingPongQueue.poll();
			if(pair != null)
			{
				dispatchPacket(pair.getLeft(), pair.getRight());
				LOGGER.debug(OUTGOING, "Ping #"+pingCount.incrementAndGet());
			}
		}
	}
}
