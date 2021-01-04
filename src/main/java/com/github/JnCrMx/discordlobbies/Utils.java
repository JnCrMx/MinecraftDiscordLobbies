package com.github.JnCrMx.discordlobbies;

import de.jcm.discordgamesdk.GameSDKException;
import de.jcm.discordgamesdk.Result;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class Utils
{
	public static Consumer<Result> completer(CompletableFuture<Void> future)
	{
		return result -> {
			if(result == Result.OK)
			{
				future.complete(null);
			}
			else
			{
				future.completeExceptionally(new GameSDKException(result));
			}
		};
	}

	public static <T> BiConsumer<Result, T> returningCompleter(CompletableFuture<T> future)
	{
		return (result, t) -> {
			if(result == Result.OK)
			{
				future.complete(t);
			}
			else
			{
				future.completeExceptionally(new GameSDKException(result));
			}
		};
	}
}
