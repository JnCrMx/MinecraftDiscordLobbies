package com.github.JnCrMx.discordlobbies;

import de.jcm.discordgamesdk.DiscordEventAdapter;
import de.jcm.discordgamesdk.user.DiscordUser;
import de.jcm.discordgamesdk.user.Relationship;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class DiscordEventHandler extends DiscordEventAdapter
{
	private final List<DiscordEventAdapter> listeners = new CopyOnWriteArrayList<>();

	public void addListener(DiscordEventAdapter listener)
	{
		this.listeners.add(listener);
	}

	public void removeListener(DiscordEventAdapter listener)
	{
		this.listeners.remove(listener);
	}

	public void removeAllListeners()
	{
		this.listeners.clear();
	}

	@Override
	public void onActivityJoin(String secret)
	{
		listeners.forEach(l->l.onActivityJoin(secret));
	}

	@Override
	public void onActivitySpectate(String secret)
	{
		listeners.forEach(l->l.onActivitySpectate(secret));
	}

	@Override
	public void onActivityJoinRequest(DiscordUser user)
	{
		listeners.forEach(l->l.onActivityJoinRequest(user));
	}

	@Override
	public void onCurrentUserUpdate()
	{
		listeners.forEach(DiscordEventAdapter::onCurrentUserUpdate);
	}

	@Override
	public void onOverlayToggle(boolean locked)
	{
		listeners.forEach(l->l.onOverlayToggle(locked));
	}

	@Override
	public void onRelationshipRefresh()
	{
		listeners.forEach(DiscordEventAdapter::onRelationshipRefresh);
	}

	@Override
	public void onRelationshipUpdate(Relationship relationship)
	{
		listeners.forEach(l->l.onRelationshipUpdate(relationship));
	}

	@Override
	public void onLobbyUpdate(long lobbyId)
	{
		listeners.forEach(l->l.onLobbyUpdate(lobbyId));
	}

	@Override
	public void onLobbyDelete(long lobbyId, int reason)
	{
		listeners.forEach(l->l.onLobbyDelete(lobbyId, reason));
	}

	@Override
	public void onMemberConnect(long lobbyId, long userId)
	{
		listeners.forEach(l->l.onMemberConnect(lobbyId, userId));
	}

	@Override
	public void onMemberUpdate(long lobbyId, long userId)
	{
		listeners.forEach(l->l.onMemberUpdate(lobbyId, userId));
	}

	@Override
	public void onMemberDisconnect(long lobbyId, long userId)
	{
		listeners.forEach(l->l.onMemberDisconnect(lobbyId, userId));
	}

	@Override
	public void onLobbyMessage(long lobbyId, long userId, byte[] data)
	{
		listeners.forEach(l->l.onLobbyMessage(lobbyId, userId, data));
	}

	@Override
	public void onSpeaking(long lobbyId, long userId, boolean speaking)
	{
		listeners.forEach(l->onSpeaking(lobbyId, userId, speaking));
	}

	@Override
	public void onNetworkMessage(long lobbyId, long userId, byte channelId, byte[] data)
	{
		listeners.forEach(l->onNetworkMessage(lobbyId, userId, channelId, data));
	}

	@Override
	public void onMessage(long peerId, byte channelId, byte[] data)
	{
		listeners.forEach(l->l.onMessage(peerId, channelId, data));
	}

	@Override
	public void onRouteUpdate(String routeData)
	{
		listeners.forEach(l->l.onRouteUpdate(routeData));
	}
}
