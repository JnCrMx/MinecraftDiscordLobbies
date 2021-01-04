package com.github.JnCrMx.discordlobbies.client.gui.screen;

import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.gui.DialogTexts;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import org.jetbrains.annotations.NotNull;

public class LobbyDirectConnectScreen extends Screen
{
	private static final ITextComponent SECRET_TEXT = new TranslationTextComponent("discordMultiplayer.directConnect.enterSecret");

	private final Screen previousScreen;

	private TextFieldWidget secretEdit;
	private Button connectButton;

	public LobbyDirectConnectScreen(Screen previousScreen)
	{
		super(new TranslationTextComponent("discordMultiplayer.directConnect"));
		this.previousScreen = previousScreen;
	}

	@Override
	protected void init()
	{
		super.init();

		assert this.minecraft != null;

		this.connectButton = this.addButton(
				new Button(this.width / 2 - 100, this.height / 4 + 96 + 12,
				           200, 20,
				           new TranslationTextComponent("discordMultiplayer.connect"),
				           e -> this.minecraft.displayGuiScreen(
				           		new DiscordConnectingScreen(previousScreen, secretEdit.getText()))));
		this.addButton(new Button(this.width / 2 - 100, this.height / 4 + 120 + 12,
		                          200, 20, DialogTexts.GUI_CANCEL,
		                          e -> this.minecraft.displayGuiScreen(previousScreen)));

		this.secretEdit = new TextFieldWidget(this.font, this.width / 2 - 100, 116, 200, 20, new TranslationTextComponent("discordMultiplayer.connect"));
		this.secretEdit.setMaxStringLength(128);
		this.secretEdit.setFocused2(true);
		this.secretEdit.setResponder(e -> this.checkSecret());
		this.children.add(this.secretEdit);
		this.setFocusedDefault(this.secretEdit);

		checkSecret();
	}

	private void checkSecret()
	{
		String s = secretEdit.getText();
		connectButton.active = !s.isEmpty() && s.contains(":") && !s.startsWith(" ") && !s.endsWith(" ");
	}

	public void render(@NotNull MatrixStack matrixStack, int mouseX, int mouseY, float partialTicks)
	{
		this.renderBackground(matrixStack);
		drawCenteredString(matrixStack, this.font, this.title, this.width / 2, 20, 16777215);
		drawString(matrixStack, this.font, SECRET_TEXT, this.width / 2 - 100, 100, 10526880);
		this.secretEdit.render(matrixStack, mouseX, mouseY, partialTicks);
		super.render(matrixStack, mouseX, mouseY, partialTicks);
	}
}
