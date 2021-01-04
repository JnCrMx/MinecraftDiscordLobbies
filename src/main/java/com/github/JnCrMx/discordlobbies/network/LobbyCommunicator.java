package com.github.JnCrMx.discordlobbies.network;

import io.netty.buffer.ByteBuf;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import javax.annotation.Nullable;

public interface LobbyCommunicator
{
	void sendData(ByteBuf buf, long userId, byte channel,
	              @Nullable GenericFutureListener<? extends Future<? super Void>> listener);
	void closeConnection(long userId);

	void setCompressionThreshold(int threshold);
}
