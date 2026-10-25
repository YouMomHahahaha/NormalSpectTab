package me.didy.normalspect;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

public class NormalSpectTab extends JavaPlugin {

    private int warnings = 0;
    private Field actionsField;
    private Field entriesField;
    private Constructor<?> packetCtor;

    @Override
    public void onEnable() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
            new PacketAdapter(this, ListenerPriority.HIGH, PacketType.Play.Server.PLAYER_INFO) {
                @Override
                public void onPacketSending(PacketEvent event) {
                    try {
                        handle(event);
                    } catch (Throwable t) {
                        if (warnings++ < 3) {
                            getLogger().log(Level.WARNING, "Could not process a player info packet", t);
                        }
                    }
                }
            });
    }

    private void init(Class<?> packetClass) throws Exception {
        if (packetCtor != null) return;
        Field actions = null;
        Field entries = null;
        for (Field f : packetClass.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (actions == null && EnumSet.class.isAssignableFrom(f.getType())) actions = f;
            else if (entries == null && List.class.isAssignableFrom(f.getType())) entries = f;
        }
        if (actions == null || entries == null) {
            throw new IllegalStateException("Unexpected packet layout: " + packetClass.getName());
        }
        Constructor<?> ctor = null;
        for (Constructor<?> c : packetClass.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 2 && p[0] == EnumSet.class && p[1] == List.class) {
                ctor = c;
                break;
            }
        }
        if (ctor == null) {
            throw new IllegalStateException("No (EnumSet, List) constructor in " + packetClass.getName());
        }
        actions.setAccessible(true);
        entries.setAccessible(true);
        ctor.setAccessible(true);
        actionsField = actions;
        entriesField = entries;
        packetCtor = ctor;
    }

    private void handle(PacketEvent event) throws Exception {
        Player receiver = event.getPlayer();
        UUID receiverId = null;
        try {
            // spectators keep seeing the real state
            if (receiver.getGameMode() == GameMode.SPECTATOR) return;
            receiverId = receiver.getUniqueId();
        } catch (Exception ignored) {
            // player still joining, treat as normal player
        }

        Object packet = event.getPacket().getHandle();
        init(packet.getClass());

        List<?> entries = (List<?>) entriesField.get(packet);
        if (entries == null || entries.isEmpty()) return;

        List<Object> out = new ArrayList<>(entries.size());
        boolean changed = false;
        for (Object entry : entries) {
            Object replaced = replaceIfSpectator(entry, receiverId);
            if (replaced != entry) changed = true;
            out.add(replaced);
        }
        if (!changed) return;

        Object newPacket = packetCtor.newInstance(actionsField.get(packet), out);
        event.setPacket(PacketContainer.fromPacket(newPacket));
    }

    private Object replaceIfSpectator(Object entry, UUID receiverId) throws Exception {
        if (entry == null) return null;
        Class<?> c = entry.getClass();
        RecordComponent[] comps = c.getRecordComponents();
        if (comps == null) throw new IllegalStateException("Entry is not a record: " + c.getName());

        Object[] values = new Object[comps.length];
        Class<?>[] types = new Class<?>[comps.length];
        int modeIdx = -1;
        int idIdx = -1;
        for (int i = 0; i < comps.length; i++) {
            types[i] = comps[i].getType();
            Method m = comps[i].getAccessor();
            m.setAccessible(true);
            values[i] = m.invoke(entry);
            if (modeIdx < 0 && isGameModeEnum(types[i])) modeIdx = i;
            if (idIdx < 0 && types[i] == UUID.class) idIdx = i;
        }
        if (modeIdx < 0) return entry;

        Object mode = values[modeIdx];
        if (mode == null || !((Enum<?>) mode).name().equals("SPECTATOR")) return entry;
        if (idIdx >= 0 && Objects.equals(values[idIdx], receiverId)) return entry;

        values[modeIdx] = constant(types[modeIdx], "SURVIVAL");
        Constructor<?> k = c.getDeclaredConstructor(types);
        k.setAccessible(true);
        return k.newInstance(values);
    }

    private boolean isGameModeEnum(Class<?> type) {
        return type.isEnum() && constant(type, "SPECTATOR") != null && constant(type, "SURVIVAL") != null;
    }

    private Object constant(Class<?> enumType, String name) {
        for (Object o : enumType.getEnumConstants()) {
            if (((Enum<?>) o).name().equals(name)) return o;
        }
        return null;
    }
}
