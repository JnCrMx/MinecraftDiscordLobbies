package com.github.JnCrMx.discordlobbies.client;

import com.github.JnCrMx.discordlobbies.client.gui.screen.DiscordConnectingScreen;
import de.jcm.discordgamesdk.DiscordEventAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentTranslation;

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
		if(this.minecraft.theWorld != null)
		{
			this.minecraft.ingameGUI.getChatGUI().printChatMessage(new ChatComponentTranslation("activityJoin.alreadyInWorld"));
			return;
		}

		minecraft.displayGuiScreen(new DiscordConnectingScreen(minecraft.currentScreen, secret));
	}
}
