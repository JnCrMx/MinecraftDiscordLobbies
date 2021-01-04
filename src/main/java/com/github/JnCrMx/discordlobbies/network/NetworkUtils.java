package com.github.JnCrMx.discordlobbies.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.PacketBuffer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public class NetworkUtils
{
	private static final Logger LOGGER = LogManager.getLogger();

	private static final Deflater deflater = new Deflater();
	private static final byte[] deflateBuffer = new byte[8192];
	private static final ReentrantLock deflaterLock = new ReentrantLock();

	private static final Inflater inflater = new Inflater();
	private static final ReentrantLock inflaterLock = new ReentrantLock();

	public static ByteBuf compress(ByteBuf input, ByteBuf output, int threshold)
	{
		PacketBuffer compressed = new PacketBuffer(output);
		int len = input.readableBytes();
		if(threshold != -1 && len > threshold)
		{
			compressed.writeVarInt(len);
			byte[] bytes = new byte[len];
			input.readBytes(bytes);

			deflaterLock.lock();
			try
			{
				deflater.setInput(bytes, 0, len);
				deflater.finish();
				while(!deflater.finished())
				{
					int j = deflater.deflate(deflateBuffer);
					compressed.writeBytes(deflateBuffer, 0, j);
				}
				deflater.reset();
			}
			finally
			{
				deflaterLock.unlock();
			}
		}
		else
		{
			compressed.writeVarInt(0);
			compressed.writeBytes(input);
		}
		return output;
	}

	public static ByteBuf decompress(ByteBuf input, ByteBuf output)
	{
		PacketBuffer packetBuffer = new PacketBuffer(input);
		int compressionLen = packetBuffer.readVarInt();
		if(compressionLen == 0)
		{
			output.writeBytes(input);
		}
		else
		{
			byte[] abyte = new byte[input.readableBytes()];
			input.readBytes(abyte);

			inflaterLock.lock();
			try
			{
				inflater.setInput(abyte);
				byte[] abyte1 = new byte[compressionLen];
				try
				{
					inflater.inflate(abyte1);
				}
				catch(DataFormatException e)
				{
					LOGGER.error("Error decompressing packet", e);
				}
				output.writeBytes(abyte1);
				inflater.reset();
			}
			finally
			{
				inflaterLock.unlock();
			}
		}
		return output;
	}
}
