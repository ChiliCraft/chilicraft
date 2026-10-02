package com.chilicraft.adventure;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 组队服务（内存态，不落库）：地城与远征共用同一支队伍。
 *
 * <p>队长制：create / invite / accept / kick 仅队长可操作；队长离开即解散。
 * 邀请 60 秒过期；离线自动离队（空队解散）。</p>
 */
final class PartyService {

    /** 一支队伍：队长 + 成员集合 + 待处理邀请（被邀请者 -> 过期毫秒） */
    final class Party {
        final UUID leader;
        final Set<UUID> members = new LinkedHashSet<>();
        final Map<UUID, Long> invites = new HashMap<>();

        Party(UUID leader) {
            this.leader = leader;
            this.members.add(leader);
        }

        boolean contains(UUID player) {
            return members.contains(player);
        }
    }

    private final JavaPlugin plugin;
    private final AdventureSettings settings;
    private final Map<UUID, Party> byMember = new HashMap<>();

    private static final long INVITE_TTL_MS = 60_000L;

    PartyService(JavaPlugin plugin, AdventureSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    Party partyOf(UUID player) {
        return byMember.get(player);
    }

    /** 玩家所属队伍成员（含自己）；无队伍时视为单人队 */
    List<UUID> membersOf(UUID player) {
        Party t = byMember.get(player);
        return t == null ? List.of(player) : List.copyOf(t.members);
    }

    boolean isLeader(UUID player) {
        Party t = byMember.get(player);
        return t != null && t.leader.equals(player);
    }

    /** 创建队伍；已在队返回 false */
    boolean create(UUID leader) {
        if (byMember.containsKey(leader)) {
            return false;
        }
        Party t = new Party(leader);
        for (UUID m : t.members) {
            byMember.put(m, t);
        }
        return true;
    }

    /** 队长邀请目标；结果枚举供命令层翻译文案 */
    InviteResult invite(UUID leader, UUID target) {
        Party t = byMember.get(leader);
        if (t == null) {
            return InviteResult.NO_PARTY;
        }
        if (!t.leader.equals(leader)) {
            return InviteResult.NOT_LEADER;
        }
        Player tp = Bukkit.getPlayer(target);
        if (tp == null || !tp.isOnline()) {
            return InviteResult.INVALID;
        }
        if (t.contains(target)) {
            return InviteResult.ALREADY_MEMBER;
        }
        if (t.members.size() >= settings.partyMaxSize) {
            return InviteResult.FULL;
        }
        if (byMember.containsKey(target)) {
            return InviteResult.TARGET_BUSY;
        }
        t.invites.put(target, System.currentTimeMillis() + INVITE_TTL_MS);
        Player lp = Bukkit.getPlayer(leader);
        if (lp != null && tp != null) {
            Msgs.send(plugin, settings, tp, "party.invited",
                    Placeholder.unparsed("player", lp.getName()));
        }
        return InviteResult.OK;
    }

    enum InviteResult { OK, NO_PARTY, NOT_LEADER, INVALID, ALREADY_MEMBER, FULL, TARGET_BUSY }

    /** 接受邀请（被邀请者按 inviter 找队伍）；结果枚举供命令层翻译文案 */
    AcceptResult accept(UUID player, UUID inviter) {
        Party t = byMember.get(inviter);
        if (t == null) {
            return AcceptResult.INVITER_GONE;
        }
        Long expire = t.invites.remove(player);
        if (expire == null) {
            return AcceptResult.NO_INVITE;
        }
        if (System.currentTimeMillis() > expire) {
            return AcceptResult.INVITE_EXPIRED;
        }
        if (byMember.containsKey(player)) {
            return AcceptResult.ALREADY_IN_PARTY;
        }
        if (t.members.size() >= settings.partyMaxSize) {
            return AcceptResult.FULL;
        }
        t.members.add(player);
        byMember.put(player, t);
        broadcast(t, "party.joined", Placeholder.unparsed("player", name(player)));
        return AcceptResult.OK;
    }

    enum AcceptResult { OK, INVITER_GONE, NO_INVITE, INVITE_EXPIRED, ALREADY_IN_PARTY, FULL }

    /** 离开队伍；队长离开即解散并通知全员 */
    void leave(UUID player, boolean silent) {
        Party t = byMember.remove(player);
        if (t == null) {
            return;
        }
        t.members.remove(player);
        if (t.leader.equals(player) || t.members.isEmpty()) {
            for (UUID m : t.members) {
                byMember.remove(m);
            }
            broadcast(t, "party.disbanded", Placeholder.unparsed("player", name(player)));
        } else if (!silent) {
            broadcast(t, "party.left", Placeholder.unparsed("player", name(player)));
        }
    }

    /** 队长踢人 */
    KickResult kick(UUID leader, UUID target) {
        Party t = byMember.get(leader);
        if (t == null) {
            return KickResult.NO_PARTY;
        }
        if (!t.leader.equals(leader)) {
            return KickResult.NOT_LEADER;
        }
        if (!t.contains(target) || target.equals(leader)) {
            return KickResult.NOT_MEMBER;
        }
        byMember.remove(target);
        t.members.remove(target);
        Player tp = Bukkit.getPlayer(target);
        if (tp != null) {
            Msgs.send(plugin, settings, tp, "party.kicked-you");
        }
        broadcast(t, "party.kicked", Placeholder.unparsed("player", name(target)));
        return KickResult.OK;
    }

    enum KickResult { OK, NO_PARTY, NOT_LEADER, NOT_MEMBER }

    /** 玩家离线：自动离队（不打扰在线成员以外的目标） */
    void handleQuit(UUID player) {
        leave(player, false);
    }

    /** 我收到的未过期邀请所在队伍（GUI 接受入口）；过期邀请顺带清理 */
    List<Party> invitationsOf(UUID player) {
        List<Party> result = new ArrayList<>();
        Set<Party> seen = new HashSet<>();
        long now = System.currentTimeMillis();
        for (Party t : byMember.values()) {
            if (!seen.add(t)) {
                continue;
            }
            Long expire = t.invites.get(player);
            if (expire == null) {
                continue;
            }
            if (now > expire) {
                t.invites.remove(player);
                continue;
            }
            result.add(t);
        }
        return result;
    }

    private void broadcast(Party t, String key, TagResolver... resolvers) {
        for (UUID m : t.members) {
            Player p = Bukkit.getPlayer(m);
            if (p != null) {
                Msgs.send(plugin, settings, p, key, resolvers);
            }
        }
    }

    private String name(UUID id) {
        Player p = Bukkit.getPlayer(id);
        return p != null ? p.getName() : id.toString().substring(0, 8);
    }
}
