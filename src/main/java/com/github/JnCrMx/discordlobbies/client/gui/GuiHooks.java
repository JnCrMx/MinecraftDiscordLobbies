package com.github.JnCrMx.discordlobbies.client.gui;

import com.github.JnCrMx.discordlobbies.DiscordLobbiesMod;
import com.github.JnCrMx.discordlobbies.client.gui.screen.DiscordMultiplayerScreen;
import com.github.JnCrMx.discordlobbies.client.gui.screen.ShareToDiscordScreen;
import de.jcm.discordgamesdk.user.DiscordUser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.gui.screen.IngameMenuScreen;
import net.minecraft.client.gui.screen.MainMenuScreen;
import net.minecraft.client.gui.screen.MultiplayerScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE)
public class GuiHooks
{
	private static final List<Pair<Class<? extends Screen>, Consumer<GuiScreenEvent.InitGuiEvent>>> HOOKS = new ArrayList<>();
	static
	{
		HOOKS.add(new ImmutablePair<>(IngameMenuScreen.class, GuiHooks::inGameMenuHook));
		HOOKS.add(new ImmutablePair<>(MultiplayerScreen.class, GuiHooks::multiplayerHook));
	}

	@SubscribeEvent
	public static void onDrawScreen(GuiScreenEvent.DrawScreenEvent event)
	{
		if(event.getGui() instanceof MainMenuScreen)
		{
			if(DiscordLobbiesMod.discordPresent)
			{
				DiscordUser user = DiscordLobbiesMod.theUser;
				String username = user.getUsername() + "#" + user.getDiscriminator();

				AbstractGui.drawString(event.getMatrixStack(),
				                       Minecraft.getInstance().fontRenderer,
				                       username,
				                       2, 2, 16777215);
			}
			else
			{
				AbstractGui.drawString(event.getMatrixStack(),
				                       Minecraft.getInstance().fontRenderer,
				                       new TranslationTextComponent("mainMenu.noDiscord"),
				                       2, 2, 16777215);
			}
		}
	}

	@SubscribeEvent
	public static void onInitGui(GuiScreenEvent.InitGuiEvent event)
	{
		if(!DiscordLobbiesMod.discordPresent)
			return;

		Class<? extends Screen> guiClass = event.getGui().getClass();
		HOOKS.stream()
		     .filter(e->e.getKey().isAssignableFrom(guiClass))
		     .forEach(e->e.getValue().accept(event));
	}

	private static void inGameMenuHook(GuiScreenEvent.InitGuiEvent event)
	{
		if(!Minecraft.getInstance().isSingleplayer())
			return;

		Screen s = event.getGui();

		ITextComponent buttonText;
		Button.IPressable buttonAction;

		if(DiscordLobbiesMod.theServer == null)
		{
			buttonText = new TranslationTextComponent("menu.shareToDiscord");
			buttonAction = e->s.getMinecraft().displayGuiScreen(new ShareToDiscordScreen(s));
		}
		else
		{
			buttonText = new TranslationTextComponent("shareToDiscord.update");
			buttonAction = e->s.getMinecraft().displayGuiScreen(new ShareToDiscordScreen(s, DiscordLobbiesMod.theServer));
		}

		Button button = new Button(s.width / 2 + 4 + 98, s.height / 4 + 96 + -16,
		                           98, 20, buttonText, buttonAction);
		event.addWidget(button);
	}

	private static void multiplayerHook(GuiScreenEvent.InitGuiEvent event)
	{
		Screen s = event.getGui();
		Button button = new Button(s.width - 100 - 5, 5,
		                           100, 20,
		                           new TranslationTextComponent("discordMultiplayer.title"),
		                           e -> s.getMinecraft().displayGuiScreen(new DiscordMultiplayerScreen(s, DiscordLobbiesMod.core)));
		event.addWidget(button);
	}
}
