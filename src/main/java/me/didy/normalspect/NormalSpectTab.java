package me.didy.normalspect;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.wrappers.EnumWrappers.NativeGameMode;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class NormalSpectTab extends JavaPlugin {

    private boolean warned = false;

    @Override
    public void onEnable() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
            new PacketAdapter(this, ListenerPriority.HIGH, PacketType.Play.Server.PLAYER_INFO) {
                @Override
                public void onPacketSending(PacketEvent event) {
                    try {
                        handle(event);
                    } catch (Throwable t) {
                        if (!warned) {
                            warned = true;
                            getLogger().warning("Could not process a player info packet: " + t);
                        }
                    }
                }
            });
    }

    private void handle(PacketEvent event) {
        Player receiver = event.getPlayer();
        UUID receiverId = null;
        try {
            // spectators keep seeing the real state
            if (receiver.getGameMode() == GameMode.SPECTATOR) return;
            receiverId = receiver.getUniqueId();
        } catch (Exception ignored) {
            // player still joining, treat as normal player
        }

        PacketContainer packet = event.getPacket();
        StructureModifier<List<PlayerInfoData>> lists = packet.getPlayerInfoDataLists();

        int index = -1;
        List<PlayerInfoData> entries = null;
        for (int i = 0; i < lists.size(); i++) {
            try {
                List<PlayerInfoData> l = lists.read(i);
                if (l != null && !l.isEmpty()) {
                    index = i;
                    entries = l;
                    break;
                }
            } catch (Exception ignored) {
            }
        }
        if (entries == null) return;

        boolean changed = false;
        List<PlayerInfoData> out = new ArrayList<>(entries.size());
        for (PlayerInfoData d : entries) {
            if (d.getGameMode() == NativeGameMode.SPECTATOR
                    && !Objects.equals(d.getProfileId(), receiverId)) {
                out.add(new PlayerInfoData(
                        d.getProfileId(),
                        d.getLatency(),
                        d.isListed(),
                        NativeGameMode.SURVIVAL,
                        d.getProfile(),
                        d.getDisplayName(),
                        d.getRemoteChatSessionData()));
                changed = true;
            } else {
                out.add(d);
            }
        }
        if (!changed) return;

        PacketContainer copy = packet.deepClone();
        copy.getPlayerInfoDataLists().write(index, out);
        event.setPacket(copy);
    }
}
