package com.github.JnCrMx.discordlobbies.client;

import com.github.JnCrMx.discordlobbies.client.gui.screen.DiscordConnectingScreen;
import de.jcm.discordgamesdk.DiscordEventAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.TranslationTextComponent;

public class ActivityJoinListener extends DiscordEventAdapter
{
	private final Minecraft minecraft;

	public ActivityJoinListener(Minecraft minecraft)
	{
		this.minecraft = minecraft;
	}

	@Override
	public void onActivityJoin(String secret)
	{
		if(this.minecraft.world != null)
		{
			this.minecraft.ingameGUI.getChatGUI().printChatMessage(new TranslationTextComponent("activityJoin.alreadyInWorld"));
			return;
		}

		minecraft.execute(()->minecraft.displayGuiScreen(new DiscordConnectingScreen(minecraft.currentScreen, secret)));
	}
}
