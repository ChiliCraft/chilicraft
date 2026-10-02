package com.chilicraft.adventure;

import com.chilicraft.core.gui.GuiHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 队伍面板（54 格）：状态头 + 成员管理（队长踢人/邀请）+ 待接受邀请列表。
 *
 * <p>无队伍时显示「创建队伍」与收到的邀请（点击接受）；有队伍时显示成员头，
 * 队长可点非队长成员头踢人、可打开选人面板邀请在线玩家。</p>
 */
final class PartyGui {

    /** 邀请列表槽位（无队伍时） */
    private static final int[] INVITE_SLOTS = {29, 30, 31, 32, 33};
    /** 成员头槽位（有队伍时） */
    private static final int[] MEMBER_SLOTS = {19, 20, 21, 22, 23, 24, 25};

    private final JavaPlugin plugin;
    private final AdventureSettings settings;
    private final PartyService parties;
    private final AdventureGui parent;

    PartyGui(JavaPlugin plugin, AdventureSettings settings, PartyService parties, AdventureGui parent) {
        this.plugin = plugin;
        this.settings = settings;
        this.parties = parties;
        this.parent = parent;
    }

    void open(Player player) {
        UUID id = player.getUniqueId();
        GuiHolder holder = new GuiHolder(54,
                GuiItems.text(settings, "gui.title-party", "<dark_gray>冒险 · 组队"));
        PartyService.Party party = parties.partyOf(id);
        if (party == null) {
            buildNoParty(holder, player, id);
        } else {
            buildInParty(holder, player, id, party);
        }
        holder.set(49, GuiItems.button(Material.ARROW,
                        GuiItems.text(settings, "gui.back", "<yellow>返回冒险菜单"), List.of()),
                (p, type) -> parent.openMain(p));
        holder.open(player);
    }

    // ---------- 无队伍 ----------

    private void buildNoParty(GuiHolder holder, Player player, UUID id) {
        holder.set(4, GuiItems.head(id,
                GuiItems.text(settings, "gui.party-status-none-name", "<gold>尚未加入队伍"),
                List.of(GuiItems.text(settings, "gui.party-status-none-lore",
                        "<gray>创建队伍或接受邀请后，可组队进入地城与远征"))));

        holder.set(20, GuiItems.button(Material.GREEN_WOOL,
                        GuiItems.text(settings, "gui.party-create-name", "<green>创建队伍"),
                        List.of(GuiItems.text(settings, "gui.party-create-lore",
                                "<gray>你将成为队长，队伍上限 <max> 人",
                                Placeholder.unparsed("max", String.valueOf(settings.partyMaxSize))))),
                (p, type) -> {
                    boolean ok = parties.create(p.getUniqueId());
                    if (ok) {
                        Msgs.sendOr(plugin, settings, p, "gui.party-result.created",
                                "<gray>[冒险] </gray><green>队伍已创建。");
                    } else {
                        Msgs.sendOr(plugin, settings, p, "gui.party-result.create-fail",
                                "<gray>[冒险] </gray><red>你已经在队伍中。");
                    }
                    open(p);
                });

        List<PartyService.Party> invites = parties.invitationsOf(id);
        for (int i = 0; i < invites.size() && i < INVITE_SLOTS.length; i++) {
            PartyService.Party inv = invites.get(i);
            holder.set(INVITE_SLOTS[i], GuiItems.head(inv.leader,
                            GuiItems.text(settings, "gui.party-invited-name", "<aqua><player> 的队伍",
                                    Placeholder.unparsed("player", GuiItems.playerName(inv.leader))),
                            List.of(GuiItems.text(settings, "gui.party-invited-lore",
                                    "<yellow>点击接受邀请（<count>/<max> 人）",
                                    Placeholder.unparsed("count", String.valueOf(inv.members.size())),
                                    Placeholder.unparsed("max", String.valueOf(settings.partyMaxSize))))),
                    (p, type) -> {
                        feedbackAccept(p, parties.accept(p.getUniqueId(), inv.leader));
                        open(p);
                    });
        }
    }

    // ---------- 有队伍 ----------

    private void buildInParty(GuiHolder holder, Player player, UUID id, PartyService.Party party) {
        boolean leader = party.leader.equals(id);

        List<Component> statusLore = new ArrayList<>();
        statusLore.add(GuiItems.text(settings, "gui.party-status-leader-line", "<gray>队长：<aqua><leader>",
                Placeholder.unparsed("leader", GuiItems.playerName(party.leader))));
        statusLore.add(leader
                ? GuiItems.text(settings, "gui.party-status-role-leader", "<yellow>你是队长")
                : GuiItems.text(settings, "gui.party-status-role-member", "<gray>你是队员"));
        holder.set(4, GuiItems.head(id,
                GuiItems.text(settings, "gui.party-status-name", "<gold>队伍（<count>/<max> 人）",
                        Placeholder.unparsed("count", String.valueOf(party.members.size())),
                        Placeholder.unparsed("max", String.valueOf(settings.partyMaxSize))),
                statusLore));

        int slot = 0;
        for (UUID member : party.members) {
            if (slot >= MEMBER_SLOTS.length) {
                break;
            }
            boolean memberIsLeader = member.equals(party.leader);
            List<Component> lore = new ArrayList<>();
            if (memberIsLeader) {
                lore.add(GuiItems.text(settings, "gui.party-member-leader-tag", "<gold>★ 队长"));
            } else if (leader) {
                lore.add(GuiItems.text(settings, "gui.party-member-kick-hint", "<red>点击踢出队伍"));
            }
            ItemStack head = GuiItems.head(member,
                    Component.text(GuiItems.playerName(member)), lore);
            if (leader && !memberIsLeader) {
                holder.set(MEMBER_SLOTS[slot], head, (p, type) -> {
                    feedbackKick(p, parties.kick(p.getUniqueId(), member), GuiItems.playerName(member));
                    open(p);
                });
            } else {
                holder.set(MEMBER_SLOTS[slot], head);
            }
            slot++;
        }

        if (leader) {
            holder.set(40, GuiItems.button(Material.NAME_TAG,
                            GuiItems.text(settings, "gui.party-invite-name", "<green>邀请玩家"),
                            List.of(GuiItems.text(settings, "gui.party-invite-lore",
                                    "<gray>仅队长可用；邀请 60 秒内有效"))),
                    (p, type) -> openInvitePicker(p));
        }
        holder.set(39, GuiItems.button(Material.RED_WOOL,
                        GuiItems.text(settings, "gui.party-leave-name", "<red>离开队伍"),
                        List.of(GuiItems.text(settings, "gui.party-leave-lore",
                                "<gray>队长离开将解散队伍"))),
                (p, type) -> {
                    parties.leave(p.getUniqueId(), false);
                    Msgs.sendOr(plugin, settings, p, "gui.party-result.left",
                            "<gray>[冒险] </gray><gray>你已离开队伍。");
                    open(p);
                });
    }

    // ---------- 邀请选人 ----------

    /** 选人面板：在线且无队伍的玩家头（0-44 槽），槽 49 返回队伍面板 */
    private void openInvitePicker(Player player) {
        GuiHolder holder = new GuiHolder(54,
                GuiItems.text(settings, "gui.title-party-invite", "<dark_gray>冒险 · 邀请玩家"));
        int slot = 0;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (slot >= 45) {
                break;
            }
            if (online.getUniqueId().equals(player.getUniqueId())) {
                continue;
            }
            // 已有队伍的玩家不可邀请（与 invite 的 TARGET_BUSY 口径一致，提前过滤）
            if (parties.partyOf(online.getUniqueId()) != null) {
                continue;
            }
            holder.set(slot++, GuiItems.head(online.getUniqueId(),
                            Component.text(online.getName()),
                            List.of(GuiItems.text(settings, "gui.party-pick-hint", "<yellow>点击邀请"))),
                    (p, type) -> {
                        PartyService.InviteResult result =
                                parties.invite(p.getUniqueId(), online.getUniqueId());
                        feedbackInvite(p, result, online.getName());
                        if (result == PartyService.InviteResult.OK) {
                            open(p);
                        } else {
                            openInvitePicker(p);
                        }
                    });
        }
        holder.set(49, GuiItems.button(Material.ARROW,
                        GuiItems.text(settings, "gui.back", "<yellow>返回冒险菜单"), List.of()),
                (p, type) -> open(p));
        holder.open(player);
    }

    // ---------- 结果反馈 ----------

    private void feedbackInvite(Player player, PartyService.InviteResult result, String targetName) {
        switch (result) {
            case OK -> Msgs.sendOr(plugin, settings, player, "gui.party-result.invite-ok",
                    "<gray>[冒险] </gray><green>已邀请 <player>。",
                    Placeholder.unparsed("player", targetName));
            case INVALID -> Msgs.sendOr(plugin, settings, player, "gui.party-result.invite-invalid",
                    "<gray>[冒险] </gray><red>目标玩家不在线。");
            case ALREADY_MEMBER -> Msgs.sendOr(plugin, settings, player, "gui.party-result.invite-already-member",
                    "<gray>[冒险] </gray><red>对方已在队伍中。");
            case FULL -> Msgs.sendOr(plugin, settings, player, "gui.party-result.invite-full",
                    "<gray>[冒险] </gray><red>队伍已满员。");
            case TARGET_BUSY -> Msgs.sendOr(plugin, settings, player, "gui.party-result.invite-target-busy",
                    "<gray>[冒险] </gray><red>对方已有其他队伍。");
            case NO_PARTY -> Msgs.sendOr(plugin, settings, player, "gui.result.no-party",
                    "<gray>[冒险] </gray><red>你还没有队伍。");
            case NOT_LEADER -> Msgs.sendOr(plugin, settings, player, "gui.result.not-leader",
                    "<gray>[冒险] </gray><red>只有队长可以操作。");
        }
    }

    private void feedbackAccept(Player player, PartyService.AcceptResult result) {
        switch (result) {
            case OK -> Msgs.sendOr(plugin, settings, player, "gui.party-result.accept-ok",
                    "<gray>[冒险] </gray><green>已加入队伍。");
            case INVITER_GONE -> Msgs.sendOr(plugin, settings, player, "gui.party-result.accept-inviter-gone",
                    "<gray>[冒险] </gray><red>邀请已失效（队伍不存在）。");
            case NO_INVITE -> Msgs.sendOr(plugin, settings, player, "gui.party-result.accept-no-invite",
                    "<gray>[冒险] </gray><red>没有待处理的邀请。");
            case INVITE_EXPIRED -> Msgs.sendOr(plugin, settings, player, "gui.party-result.accept-invite-expired",
                    "<gray>[冒险] </gray><red>邀请已过期。");
            case ALREADY_IN_PARTY -> Msgs.sendOr(plugin, settings, player, "gui.party-result.accept-already-in-party",
                    "<gray>[冒险] </gray><red>你已在队伍中。");
            case FULL -> Msgs.sendOr(plugin, settings, player, "gui.party-result.accept-full",
                    "<gray>[冒险] </gray><red>队伍已满员。");
        }
    }

    private void feedbackKick(Player player, PartyService.KickResult result, String targetName) {
        switch (result) {
            case OK -> Msgs.sendOr(plugin, settings, player, "gui.party-result.kick-ok",
                    "<gray>[冒险] </gray><gray>已移出 <player>。",
                    Placeholder.unparsed("player", targetName));
            case NOT_MEMBER -> Msgs.sendOr(plugin, settings, player, "gui.party-result.kick-not-member",
                    "<gray>[冒险] </gray><red>对方不在队伍中。");
            case NO_PARTY -> Msgs.sendOr(plugin, settings, player, "gui.result.no-party",
                    "<gray>[冒险] </gray><red>你还没有队伍。");
            case NOT_LEADER -> Msgs.sendOr(plugin, settings, player, "gui.result.not-leader",
                    "<gray>[冒险] </gray><red>只有队长可以操作。");
        }
    }
}
