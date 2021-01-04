package com.github.JnCrMx.discordlobbies.network;

import net.minecraft.network.IPacket;
import net.minecraft.network.play.client.CKeepAlivePacket;
import net.minecraft.network.play.client.CPlayerPacket;
import net.minecraft.network.play.server.*;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

public class NetworkConstants
{
	public static class ChannelConfig
	{
		private boolean reliable = true;
		private boolean buffered = false;
		private int bufferSize;

		private final List<Class<? extends IPacket<?>>> packets = new ArrayList<>();
		private boolean defaultChannel = false;

		private ChannelConfig()
		{
		}

		private ChannelConfig unreliable()
		{
			reliable = false;
			return this;
		}

		private ChannelConfig buffered(int size)
		{
			buffered = true;
			bufferSize = size;
			return this;
		}

		private ChannelConfig packet(Class<? extends IPacket<?>> clazz)
		{
			packets.add(clazz);
			return this;
		}

		private ChannelConfig defaultChannel()
		{
			defaultChannel = true;
			return this;
		}

		public boolean isReliable()
		{
			return reliable;
		}

		public boolean isBuffered()
		{
			return buffered;
		}

		public int getBufferSize()
		{
			return bufferSize;
		}

		public boolean isDefaultChannel()
		{
			return defaultChannel;
		}
	}

	public static final ChannelConfig[] CHANNELS = new ChannelConfig[]{
			new ChannelConfig()
					.defaultChannel(),

			new ChannelConfig().unreliable().buffered(512)
					.packet(SEntityPacket.LookPacket.class)
					.packet(SEntityHeadLookPacket.class),

			new ChannelConfig()
					.packet(SEntityPacket.RelativeMovePacket.class),
			new ChannelConfig()
					.packet(SEntityPacket.RelativeMovePacket.class),
			new ChannelConfig()
					.packet(SEntityPacket.RelativeMovePacket.class),

			new ChannelConfig()
					.packet(SEntityPacket.MovePacket.class),
			new ChannelConfig()
					.packet(SEntityPacket.MovePacket.class),
			new ChannelConfig()
					.packet(SEntityPacket.MovePacket.class),

			new ChannelConfig().unreliable()
					.packet(SEntityVelocityPacket.class),

			new ChannelConfig()
					.packet(SEntityPropertiesPacket.class)
					.packet(SEntityMetadataPacket.class)
					.packet(SSpawnMobPacket.class)
					.packet(SDestroyEntitiesPacket.class),

			new ChannelConfig().unreliable()
					.packet(SEntityStatusPacket.class),

			new ChannelConfig()
					.packet(SChunkDataPacket.class),
			new ChannelConfig()
					.packet(SChunkDataPacket.class),
			new ChannelConfig()
					.packet(SChunkDataPacket.class),

			new ChannelConfig().unreliable()
					.packet(SUpdateLightPacket.class)
					.packet(SUnloadChunkPacket.class)
					.packet(SUpdateTimePacket.class),

			new ChannelConfig()
					.packet(SMultiBlockChangePacket.class)
					.packet(SChangeBlockPacket.class),

			new ChannelConfig()
					.packet(SKeepAlivePacket.class)
					.packet(CKeepAlivePacket.class),

			new ChannelConfig().unreliable()
					.packet(CPlayerPacket.RotationPacket.class),

			new ChannelConfig()
					.packet(CPlayerPacket.PositionPacket.class)
					.packet(CPlayerPacket.PositionRotationPacket.class),
			};
	public static final byte DEFAULT_CHANNEL = (byte) (int)
			IntStream.range(0, CHANNELS.length)
			         .mapToObj(i->new ImmutablePair<>(i, CHANNELS[i]))
			         .filter(e->e.getRight().isDefaultChannel())
			         .findFirst()
			         .map(Pair::getLeft)
			         .orElse(0);

	public static int[] getChannelsForPacket(IPacket<?> packet)
	{
		return IntStream.range(0, CHANNELS.length)
		         .mapToObj(i->new ImmutablePair<>(i, CHANNELS[i]))
		         .filter(p->p.getRight().packets.contains(packet.getClass()))
		         .mapToInt(ImmutablePair::getLeft).toArray();
	}

	public static final byte[] READY_MESSAGE = {0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77};
}
