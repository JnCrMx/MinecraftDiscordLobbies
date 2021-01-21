package com.github.JnCrMx.discordlobbies.client.gui.screen;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import de.jcm.discordgamesdk.lobby.Lobby;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.gui.widget.list.ExtendedList;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.Util;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Consumer;

import static org.lwjgl.opengl.GL11.*;

public class LobbySelectionList extends ExtendedList<LobbySelectionList.LobbyEntry>
{
	private final DiscordMultiplayerScreen owner;
	private final Consumer<LobbyEntry> selectionListener;

	public LobbySelectionList(DiscordMultiplayerScreen owner, Minecraft mcIn, int widthIn, int heightIn,
	                          int topIn, int bottomIn, int slotHeightIn,
	                          Consumer<LobbyEntry> selectionListener)
	{
		super(mcIn, widthIn, heightIn, topIn, bottomIn, slotHeightIn);
		this.owner = owner;
		this.selectionListener = selectionListener;
	}

	public void updateLobbies(Map<Long, DiscordMultiplayerScreen.LobbyData> lobbies)
	{
		Map<Long, DiscordMultiplayerScreen.LobbyData> copy = new HashMap<>(lobbies);

		List<LobbyEntry> toRemove = new ArrayList<>();
		for(int i=0; i<getItemCount(); i++)
		{
			LobbyEntry entry = getEntry(i);
			long id = entry.lobby.getId();
			if(copy.containsKey(id))
			{
				entry.lobby = lobbies.get(id);
				copy.remove(id);
			}
			else
			{
				toRemove.add(entry);
			}
		}

		toRemove.forEach(this::removeEntry);
		copy.values().stream().map(LobbyEntry::new).forEach(this::addEntry);
	}

	@Override
	public void setSelected(@Nullable LobbySelectionList.LobbyEntry entry)
	{
		super.setSelected(entry);
		selectionListener.accept(entry);
	}

	protected int getScrollbarPosition()
	{
		return super.getScrollbarPosition() + 30;
	}

	public int getRowWidth()
	{
		return super.getRowWidth() + 85;
	}

	protected boolean isFocused()
	{
		return this.owner.getListener() == this;
	}

	public class LobbyEntry extends ExtendedList.AbstractListEntry<LobbyEntry>
	{
		private DiscordMultiplayerScreen.LobbyData lobby;
		private final Minecraft mc;
		private final DynamicTexture icon;

		private long lastClickTime;

		public LobbyEntry(DiscordMultiplayerScreen.LobbyData lobby)
		{
			this.lobby = lobby;
			this.mc = Minecraft.getInstance();
			this.icon = new DynamicTexture(256, 256, false);

			owner.fetchProfilePicture(lobby.getOwner().getUserId(), (dimensions, data) -> {
				// Bind the texture we will be uploading our data to
				icon.bindTexture();
				/*
				Do this manually, because I don't want to encode the image as a PNG
				just so Minecraft decodes it again.
				 */
				GlStateManager.texParameter(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
				GlStateManager.texParameter(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
				GlStateManager.texParameter(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
				GlStateManager.texParameter(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
				/*
				Minecraft might have set some of these pixel storage modes,
				so we need to reset them in order to everything to work fine.
				This is also done in net.minecraft.client.renderer.texture.NativeImage#uploadTextureSubRaw.
				 */
				GlStateManager.pixelStore(GL_UNPACK_ROW_LENGTH, 0);
				GlStateManager.pixelStore(GL_UNPACK_SKIP_PIXELS, 0);
				GlStateManager.pixelStore(GL_UNPACK_SKIP_ROWS, 0);

				// We need a direct buffer, not just a wrapping one
				ByteBuffer buf = BufferUtils.createByteBuffer(data.length);
				buf.put(data);
				buf.flip();

				// UPLOOOOAD the image!
				GL11.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8,
				                  dimensions.getWidth(), dimensions.getHeight(), 0,
				                  GL_RGBA, GL_UNSIGNED_BYTE, buf);
			});
		}

		@Override
		public void render(@NotNull MatrixStack stack, int index, int rowTop, int rowLeft, int rowWidth, int rowHeight, int mouseX, int mouseY, boolean mouseOver, float partialTicks)
		{
			this.mc.fontRenderer.drawString(stack, this.lobby.getMinecraftWorld(), rowLeft + 32 + 3, rowTop + 1,
			                                Objects.requireNonNull(TextFormatting.WHITE.getColor()));

			ITextComponent versionMessage = lobby.getVersionMessage();
			if(versionMessage == null)
			{
				versionMessage = new StringTextComponent(Long.toString(lobby.getId()))
						.mergeStyle(TextFormatting.DARK_GRAY);
			}
			this.mc.fontRenderer.func_243248_b(stack, versionMessage, rowLeft + 32 + 3, rowTop + 1 + 11, 3158064);

			String discordOwner = lobby.getOwner().getUsername()+"#"+lobby.getOwner().getDiscriminator();
			ITextComponent owner = (new StringTextComponent(lobby.getMinecraftOwner()).mergeStyle(TextFormatting.GRAY))
					.append(new StringTextComponent(" aka ").mergeStyle(TextFormatting.DARK_GRAY))
					.append(new StringTextComponent(discordOwner).mergeStyle(TextFormatting.GRAY));

			this.mc.fontRenderer.func_243248_b(stack, owner, rowLeft + 32 + 3, rowTop + 1 + 11 + 11, 3158064);

			ITextComponent population = formatPopulation(lobby.getPlayerCount(), lobby.getLobby().getCapacity());

			int j = this.mc.fontRenderer.getStringPropertyWidth(population);
			this.mc.fontRenderer.func_243248_b(stack, population, rowLeft + rowWidth - j - 5, rowTop + 1, 8421504);

			icon.bindTexture();
			RenderSystem.enableBlend();
			AbstractGui.blit(stack, rowLeft, rowTop, 0.0F, 0.0F,
			                 32, 32, 32, 32);
			RenderSystem.disableBlend();
		}

		private ITextComponent formatPopulation(int playerCount, int capacity)
		{
			return (new StringTextComponent(Integer.toString(playerCount)))
					.append((new StringTextComponent("/")).mergeStyle(TextFormatting.DARK_GRAY))
					.appendString(Integer.toString(capacity)).mergeStyle(TextFormatting.GRAY);
		}

		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button)
		{
			setSelected(this);

			if (Util.milliTime() - this.lastClickTime < 250L) {
				owner.connectToLobby(this);
			}

			this.lastClickTime = Util.milliTime();

			return false;
		}

		public Lobby getLobby()
		{
			return lobby.getLobby();
		}
	}
}
