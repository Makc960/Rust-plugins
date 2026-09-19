using System;
using System.Collections.Generic;
using System.Globalization;
using Oxide.Core;
using Oxide.Core.Plugins;
using Oxide.Game.Rust.Cui;
using UnityEngine;

namespace Oxide.Plugins
{
    [Info("ServerMenu", "ICE RUST", "2.9.0")]
    [Description("Unified ICE RUST UI host: main menu, AccountSystem and Kits in one interface.")]
    public class ServerMenu : RustPlugin
    {
        [PluginReference] private Plugin ChatPrefixes;
        [PluginReference] private Plugin AdminMenu;
        [PluginReference] private Plugin Kits;
        [PluginReference] private Plugin AccountSystem;
        [PluginReference] private Plugin AutoMessages;
        [PluginReference] private Plugin ClanSystem;

        private const string Root = "ServerMenu.UI";
        private const string Main = Root + ".Main";
        private const string Header = Root + ".Header";
        private const string Tabs = Root + ".Tabs";

        private const string Bold = "robotocondensed-bold.ttf";
        private const string Reg = "robotocondensed-regular.ttf";

        private const string MatBlur = "assets/content/ui/uibackgroundblur.mat";
        private const string SpriteRadial = "assets/content/ui/ui.background.transparent.radial.psd";
        private const string ColOverlay = "0 0 0 0.97";
        private const string ColHeader = "1 1 1 0.045";
        private const string ColMain = "1 1 1 0.025";
        private const string ColCard = "1 1 1 0.045";
        private const string ColCardDark = "0 0 0 0.16";
        private const string ColInput = "0 0 0 0.16";
        private const string ColGreenBtn = "#65A30DBF";
        private const string ColGreenTxt = "#FFFFFF";
        private const string ColRedBtn = "#E0947A73";
        private const string ColRedText = "#E0947A";
        private const string ColText = "#CEC5BB";
        private const string ColMuted = "#7F7D7D";
        private const string ColSubText = "#A39C96";
        private const string ColBlue = "#B2A9A3";
        private const string ColGold = "#B2A9A3";
        private const string ColSecondaryBtn = "#B2A9A3E6";
        private const string ColSecondaryBtnDisabled = "#B2A9A373";
        private const string ColSecondaryText = "#45403B";
        private const string ColRedStrong = "#E0947ABF";
        private const string ColPremium = "#CEC5BB";
        private const string ColPremiumSoft = "1 1 1 0.045";
        private const string ColLine = "1 1 1 0.045";
        private const string ColSoft = "0 0 0 0.16";
        private const string ColNav = "1 1 1 0.025";

        private sealed class MenuState
        {
            public string Tab = "home";
            public int TopPage;
        }

        private readonly Dictionary<ulong, MenuState> states = new Dictionary<ulong, MenuState>();

        [ChatCommand("menu")]
        private void CmdMenu(BasePlayer player, string command, string[] args)
        {
            if (!ValidPlayer(player)) return;
            string tab = args != null && args.Length > 0 ? (args[0] ?? string.Empty).Trim().ToLowerInvariant() : "home";
            tab = IsAllowedTab(player, tab) ? tab : "home";
            OpenIntegrated(player, tab, "");
        }

        [ChatCommand("m")]
        private void CmdMenuShort(BasePlayer player, string command, string[] args)
        {
            CmdMenu(player, command, args);
        }

        [ChatCommand("top")]
        private void CmdTop(BasePlayer player, string command, string[] args)
        {
            if (!ValidPlayer(player)) return;
            OpenIntegrated(player, "top", "");
        }

        [ConsoleCommand("servermenu.ui")]
        private void UiCommand(ConsoleSystem.Arg arg)
        {
            BasePlayer player = arg?.Player();
            if (!ValidPlayer(player) || arg.Args == null || arg.Args.Length == 0) return;

            string action = arg.Args[0].ToString();
            if (action == "close")
            {
                states.Remove(player.userID);
                CuiHelper.DestroyUi(player, Root);
                return;
            }

            if (action == "chat" && arg.Args.Length > 1)
            {
                string message = string.Empty;

                for (int i = 1; i < arg.Args.Length; i++)
                    message += (message.Length == 0 ? string.Empty : " ") + arg.Args[i].ToString();

                message = message.Trim();
                if (message.Length == 0) return;

                // Передаём всю строку как один аргумент chat.say.
                player.SendConsoleCommand("chat.say", message);
                return;
            }

            if (action == "topplayer" && arg.Args.Length > 1)
            {
                ulong targetId;
                if (!ulong.TryParse(arg.Args[1].ToString(), out targetId) || targetId == 0UL)
                    return;

                // SteamID64 уходит во вкладку профиля как payload.
                OpenIntegrated(player, "account", targetId.ToString(CultureInfo.InvariantCulture));
                return;
            }

            if (action == "topback")
            {
                MenuState current;
                if (!states.TryGetValue(player.userID, out current) || current == null)
                    states[player.userID] = current = new MenuState();

                Open(player, "top");
                return;
            }

            if (action == "accountreset" && arg.Args.Length > 1)
            {
                ulong targetId;
                if (!ulong.TryParse(arg.Args[1].ToString(), out targetId) || targetId == 0UL)
                    return;

                Plugin account = GetAccountSystemPlugin();
                if (account == null) return;

                object allowed = account.Call("API_CanAdminReset", player);
                if (!(allowed is bool) || !(bool)allowed) return;

                object reset = account.Call("API_ResetAccountStats", player, targetId);
                if (!(reset is bool) || !(bool)reset) return;

                OpenIntegrated(player, "account", targetId.ToString(CultureInfo.InvariantCulture));
                return;
            }

            if (action == "toppage" && arg.Args.Length > 1)
            {
                int page;
                if (!int.TryParse(arg.Args[1].ToString(), out page))
                    return;

                MenuState current;
                if (!states.TryGetValue(player.userID, out current) || current == null)
                    states[player.userID] = current = new MenuState();

                current.Tab = "top";
                current.TopPage = Math.Max(0, page);
                Draw(player, current);
                return;
            }

            if (action == "ext" && arg.Args.Length > 1)
            {
                string key = arg.Args[1].ToString();

                ExternalTab entry;
                if (!extTabs.TryGetValue(key, out entry)) return;

                Plugin owner = TabPlugin(entry);
                if (owner == null) return;

                string payload = string.Empty;
                for (int i = 2; i < arg.Args.Length; i++)
                    payload += (payload.Length == 0 ? string.Empty : " ") + arg.Args[i].ToString();

                owner.Call("API_OnTabCommand", player, payload);
                return;
            }

            if (action == "tab" && arg.Args.Length > 1)
            {
                string tab = arg.Args[1].ToString();
                if (!IsAllowedTab(player, tab)) return;

                MenuState current;
                if (states.TryGetValue(player.userID, out current) && current != null &&
                    string.Equals(current.Tab, tab, StringComparison.OrdinalIgnoreCase))
                    return;

                if (current != null && string.Equals(tab, "top", StringComparison.OrdinalIgnoreCase))
                    current.TopPage = 0;

                OpenIntegrated(player, tab, "");
            }
        }

        private sealed class ExternalTab
        {
            public string Key;
            public string Title;
            public string Icon;
            public int Order;
            public string Owner;
        }

        private readonly Dictionary<string, ExternalTab> extTabs =
            new Dictionary<string, ExternalTab>(StringComparer.OrdinalIgnoreCase);

        private const float StripW = 1120f;
        private const float StripTop = 304f;
        private const float StripRowH = 40f;
        private const float StripGap = 6f;
        private const float TabMaxW = 148f;
        private const float TabMinW = 92f;
        private const float MainGap = 8f;
        private const float MainBottomY = -328f;

        // Рендерим только одну страницу рейтинга. Это резко уменьшает размер CUI-пакета.
        private const int ExpTopPageSize = 10;

        private static int TabsPerRow()
        {
            return Mathf.Max(1, Mathf.FloorToInt((StripW + StripGap) / (TabMinW + StripGap)));
        }

        private static int StripRows(int count)
        {
            int perRow = TabsPerRow();
            return Mathf.Max(1, (count + perRow - 1) / perRow);
        }

        private float StripBottom(int count)
        {
            return StripTop - StripRows(count) * StripRowH;
        }

        private float MainTopFor(int count)
        {
            return StripBottom(count) - MainGap;
        }

        [HookMethod("API_ContentHeight")]
        public float API_ContentHeight()
        {
            return MainTopFor(TabStrip(null).Count) - MainBottomY;
        }

        private const int OrderHome = 0;
        // ClanSystem = 10, TOP = 15, Kits = 20.
        // Поэтому новая вкладка автоматически располагается между КЛАНЫ и КИТЫ.
        private const int OrderTop = 15;
        private const int OrderServices = 800;
        private const int OrderCommands = 900;

        private static bool IsBuiltInTab(string tab)
        {
            // "account" сюда не входит: вкладку профиля регистрирует AccountSystem
            // через API_RegisterTab, и он же её рисует.
            return tab == "home" || tab == "top" ||
                   tab == "services" || tab == "commands";
        }

        private Plugin TabPlugin(ExternalTab tab)
        {
            if (tab == null || string.IsNullOrEmpty(tab.Owner)) return null;
            Plugin plugin = Interface.Oxide.RootPluginManager.GetPlugin(tab.Owner);
            return plugin != null && plugin.IsLoaded ? plugin : null;
        }

        private string TabTitle(ExternalTab tab, BasePlayer player)
        {
            if (player == null) return tab.Title;

            Plugin owner = TabPlugin(tab);
            if (owner == null) return tab.Title;

            string custom = owner.Call("API_TabTitle", player) as string;
            return string.IsNullOrEmpty(custom) ? tab.Title : custom;
        }

        private List<KeyValuePair<string, string>> TabStrip(BasePlayer player)
        {
            var ordered = new List<KeyValuePair<int, KeyValuePair<string, string>>>();

            ordered.Add(new KeyValuePair<int, KeyValuePair<string, string>>(
                OrderHome, Pair("home", "ГЛАВНАЯ")));

            ordered.Add(new KeyValuePair<int, KeyValuePair<string, string>>(
                OrderTop, Pair("top", "ТОП")));

            foreach (KeyValuePair<string, ExternalTab> entry in extTabs)
                ordered.Add(new KeyValuePair<int, KeyValuePair<string, string>>(
                    entry.Value.Order, Pair(entry.Value.Key, TabTitle(entry.Value, player))));

            ordered.Add(new KeyValuePair<int, KeyValuePair<string, string>>(
                OrderServices, Pair("services", "СЕРВИСЫ")));
            ordered.Add(new KeyValuePair<int, KeyValuePair<string, string>>(
                OrderCommands, Pair("commands", "КОМАНДЫ")));

            ordered.Sort((a, b) => a.Key.CompareTo(b.Key));

            var result = new List<KeyValuePair<string, string>>();
            for (int i = 0; i < ordered.Count; i++) result.Add(ordered[i].Value);
            return result;
        }

        private void EvictTab(string key)
        {
            if (states.Count == 0) return;

            var ids = new List<ulong>(states.Keys);
            for (int i = 0; i < ids.Count; i++)
            {
                MenuState state;
                if (!states.TryGetValue(ids[i], out state) || state == null) continue;
                if (!string.Equals(state.Tab, key, StringComparison.OrdinalIgnoreCase)) continue;

                BasePlayer player = BasePlayer.FindByID(ids[i]);
                if (!ValidPlayer(player)) continue;

                state.Tab = "home";
                Draw(player, state);
            }
        }

        private void HideTab(BasePlayer player, string tab)
        {
            ExternalTab entry;
            if (string.IsNullOrEmpty(tab) || !extTabs.TryGetValue(tab, out entry)) return;

            Plugin plugin = TabPlugin(entry);
            if (plugin != null) plugin.Call("API_OnTabHidden", player);
        }

        [HookMethod("API_RegisterTab")]
        public bool API_RegisterTab(Plugin plugin, string key, string title, string icon, int order)
        {
            if (plugin == null || string.IsNullOrWhiteSpace(key) || string.IsNullOrWhiteSpace(title))
                return false;

            key = key.Trim().ToLowerInvariant();
            if (IsBuiltInTab(key)) return false;

            extTabs[key] = new ExternalTab
            {
                Key = key,
                Title = title,
                Icon = icon ?? string.Empty,
                Order = order,
                Owner = plugin.Name
            };

            RedrawAll();
            return true;
        }

        [HookMethod("API_UnregisterTab")]
        public bool API_UnregisterTab(string key)
        {
            if (string.IsNullOrWhiteSpace(key)) return false;

            key = key.Trim().ToLowerInvariant();
            if (!extTabs.Remove(key)) return false;

            EvictTab(key);
            RedrawAll();
            return true;
        }

        private void RedrawAll()
        {
            if (states.Count == 0) return;

            var ids = new List<ulong>(states.Keys);
            for (int i = 0; i < ids.Count; i++)
            {
                MenuState state;
                if (!states.TryGetValue(ids[i], out state) || state == null) continue;

                BasePlayer player = BasePlayer.FindByID(ids[i]);
                if (!ValidPlayer(player)) continue;

                if (!IsAllowedTab(player, state.Tab)) state.Tab = "home";
                Draw(player, state);
            }
        }

        private void OnServerInitialized()
        {
            AdoptLegacyTabs();
        }

        private void OnPluginLoaded(Plugin plugin)
        {
            if (plugin != null) AdoptLegacyTabs();
        }

        private void AdoptLegacyTabs()
        {
            AdoptLegacyTab("kits", "КИТЫ", 20, "Kits");
        }

        private void AdoptLegacyTab(string key, string title, int order, string owner)
        {
            if (extTabs.ContainsKey(key)) return;

            extTabs[key] = new ExternalTab
            {
                Key = key,
                Title = title,
                Icon = string.Empty,
                Order = order,
                Owner = owner
            };
        }

        private bool IsLegacyTab(string key)
        {
            return key == "kits";
        }

        private void DrawLegacyFallback(CuiElementContainer c, BasePlayer player, string tab)
        {
            if (tab == "kits") DrawKits(c, player);
        }

        private void OnPluginUnloaded(Plugin plugin)
        {
            if (plugin == null || extTabs.Count == 0) return;

            List<string> gone = null;

            foreach (KeyValuePair<string, ExternalTab> entry in extTabs)
                if (string.Equals(entry.Value.Owner, plugin.Name, StringComparison.Ordinal) &&
                    !IsLegacyTab(entry.Key))
                {
                    if (gone == null) gone = new List<string>();
                    gone.Add(entry.Key);
                }

            if (gone == null) return;

            for (int i = 0; i < gone.Count; i++)
            {
                extTabs.Remove(gone[i]);
                EvictTab(gone[i]);
            }

            RedrawAll();
        }

        private void OnPlayerDisconnected(BasePlayer player, string reason)
        {
            if (player == null) return;
            states.Remove(player.userID);
            CuiHelper.DestroyUi(player, Root);
        }

        private void Unload()
        {
            foreach (BasePlayer player in BasePlayer.activePlayerList)
                if (player != null) CuiHelper.DestroyUi(player, Root);
            states.Clear();
        }

        private void OnAutoMessagesVisualOnlineChanged(int virtualCount)
        {
            if (states.Count == 0) return;

            var ids = new List<ulong>(states.Keys);
            for (int i = 0; i < ids.Count; i++)
            {
                ulong id = ids[i];
                BasePlayer player = BasePlayer.FindByID(id);
                MenuState state;
                if (!ValidPlayer(player) || !states.TryGetValue(id, out state) || state == null)
                    continue;

                Draw(player, state);
            }
        }

        private void Open(BasePlayer player, string tab)
        {
            if (!ValidPlayer(player)) return;

            MenuState state;
            if (!states.TryGetValue(player.userID, out state))
                states[player.userID] = state = new MenuState();

            string next = IsAllowedTab(player, tab) ? tab : "home";
            bool changed = !string.Equals(state.Tab, next, StringComparison.OrdinalIgnoreCase);
            if (changed)
                HideTab(player, state.Tab);

            if (changed && string.Equals(next, "top", StringComparison.OrdinalIgnoreCase))
                state.TopPage = 0;

            state.Tab = next;
            Draw(player, state);
        }

        private void OpenIntegrated(BasePlayer player, string tab, string mode)
        {
            if (!ValidPlayer(player)) return;
            tab = IsAllowedTab(player, tab) ? tab : "home";

            ExternalTab prepare;
            if (extTabs.TryGetValue(tab, out prepare))
            {
                Plugin owner = TabPlugin(prepare);
                if (owner != null) owner.Call("API_PrepareServerMenu", player, mode ?? "");
            }

            Open(player, tab);
        }

        private bool RefreshIntegrated(BasePlayer player, string tab)
        {
            if (!ValidPlayer(player)) return false;
            MenuState state;
            if (!states.TryGetValue(player.userID, out state) || state == null || state.Tab != tab) return false;
            Draw(player, state);
            return true;
        }

        [HookMethod("API_OpenTab")]
        public bool API_OpenTab(BasePlayer player, string tab, string mode = "")
        {
            if (!ValidPlayer(player) || !IsAllowedTab(player, tab)) return false;
            OpenIntegrated(player, tab, mode ?? "");
            return true;
        }

        [HookMethod("API_RefreshIntegrated")]
        public bool API_RefreshIntegrated(BasePlayer player, string tab)
        {
            return RefreshIntegrated(player, (tab ?? "").Trim().ToLowerInvariant());
        }

        [HookMethod("API_IsOpenOn")]
        public bool API_IsOpenOn(BasePlayer player, string tab)
        {
            if (!ValidPlayer(player)) return false;
            MenuState state;
            return states.TryGetValue(player.userID, out state) && state != null &&
                   string.Equals(state.Tab, tab ?? "", StringComparison.OrdinalIgnoreCase);
        }

        private bool IsAllowedTab(BasePlayer player, string tab)
        {
            return IsBuiltInTab(tab) || (!string.IsNullOrEmpty(tab) && extTabs.ContainsKey(tab));
        }

        private void Draw(BasePlayer player, MenuState state)
        {
            var c = new CuiElementContainer();
            c.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = "0 0", AnchorMax = "1 1" },
                Image = { Color = "0 0 0 0" },
                CursorEnabled = true
            }, "Overlay", Root, Root);
            AddAtlanticBackdrop(c, Root, Root + ".Backdrop");

            Panel(c, Root, Header, "0.5 0.5", "0.5 0.5", "-560 312", "560 360", ColHeader);
            Panel(c, Header, Header + ".Accent", "0 0", "0 1", "0 0", "3 0", ColText);

            Label(c, Header, "0 0", "0.46 1", "20 0", "0 0",
                "ICE RUST", 23, ColText, TextAnchor.MiddleLeft, Bold);

            Label(c, Header, "0.46 0", "1 1", "0 0", "-60 0",
                Safe(player.displayName, 22) +
                "   <color=#7F7D7D>•</color>   " + CountOnline() + " ONLINE" +
                "   <color=#7F7D7D>•</color>   " + ServerTime(),
                11, ColMuted, TextAnchor.MiddleRight, Reg);

            Button(c, Header, "1 0", "1 1", "-48 0", "0 0",
                "servermenu.ui close", "×", ColRedBtn, 20, ColRedText);

            var strip = TabStrip(player);
            int rows = StripRows(strip.Count);

            Panel(c, Root, Tabs, "0.5 0.5", "0.5 0.5",
                F(-StripW / 2f) + " " + F(StripBottom(strip.Count)),
                F(StripW / 2f) + " " + F(StripTop), ColHeader);

            DrawTabs(c, player, state, strip, rows);

            Panel(c, Root, Main, "0.5 0.5", "0.5 0.5",
                F(-StripW / 2f) + " " + F(MainBottomY),
                F(StripW / 2f) + " " + F(MainTopFor(strip.Count)), ColMain);

            bool hosted = false;
            ExternalTab active;

            if (extTabs.TryGetValue(state.Tab, out active))
            {
                hosted = TabPlugin(active) != null;

                if (!hosted)
                {
                    if (IsLegacyTab(state.Tab)) DrawLegacyFallback(c, player, state.Tab);
                    else Empty(c, "ВКЛАДКА НЕДОСТУПНА", "Плагин " + active.Owner + " не загружен.");
                }
            }
            else if (state.Tab == "top") DrawExpTop(c, player, state);
            else if (state.Tab == "services") DrawServices(c, player);
            else if (state.Tab == "commands") DrawCommands(c, player);
            else DrawHome(c, player);

            CuiHelper.AddUi(player, c);

            if (hosted)
            {
                Plugin owner = TabPlugin(active);
                if (owner != null) owner.Call("API_RenderServerMenu", player);
            }
        }

        private void DrawTabs(CuiElementContainer c, BasePlayer player, MenuState state,
            List<KeyValuePair<string, string>> tabs, int rows)
        {

            int perRow = Mathf.CeilToInt(tabs.Count / (float)Mathf.Max(1, rows));

            for (int index = 0; index < tabs.Count; index++)
            {
                int row = index / perRow;
                int column = index % perRow;
                int inRow = Mathf.Min(perRow, tabs.Count - row * perRow);

                float width = Mathf.Min(TabMaxW, (StripW - StripGap * (inRow - 1)) / inRow);
                float total = inRow * width + (inRow - 1) * StripGap;
                float start = (StripW - total) * 0.5f;

                int i = index;
                // Пока AccountSystem не зарегистрировал свою вкладку, профиль
                // не показан в полосе и подсвечивается "Главная" - как раньше.
                bool active = state.Tab == tabs[i].Key ||
                              (state.Tab == "account" && tabs[i].Key == "home" &&
                               !extTabs.ContainsKey("account"));
                float x1 = start + column * (width + StripGap);
                float x2 = x1 + width;
                float yTop = -row * StripRowH;
                string tab = Tabs + ".Tab." + tabs[i].Key;

                Panel(c, Tabs, tab, "0 1", "0 1",
                    F(x1) + " " + F(yTop - StripRowH), F(x2) + " " + F(yTop), "0 0 0 0");

                Label(c, tab, "0 0", "1 1", "0 0", "0 0",
                    tabs[i].Value, 13,
                    active ? "#FFFFFF" : ColMuted,
                    TextAnchor.MiddleCenter, Bold);

                if (active)
                    Panel(c, tab, tab + ".Active", "0 0", "1 0", "12 0", "-12 3", ColGreenBtn);

                if (!active)
                    Button(c, tab, "0 0", "1 1", "0 0", "0 0",
                        "servermenu.ui tab " + tabs[i].Key, "", "0 0 0 0");
            }
        }

        private void DrawHome(CuiElementContainer c, BasePlayer player)
        {
            Heading(c, "ГЛАВНАЯ", "Аккаунт, клан и быстрые действия");

            Plugin accountPlugin = GetAccountSystemPlugin();
            Dictionary<string, object> account = GetAccountSummary(accountPlugin, player);
            bool accountLoaded = accountPlugin != null && account != null;

            string accountCard = Main + ".AccountSummary";
            Panel(c, Main, accountCard, "0 1", "0 1", "22 -188", "1098 -84", ColCard);
            Panel(c, accountCard, accountCard + ".Accent", "0 0", "0 1", "0 0", "4 0",
                accountLoaded ? ColGreenBtn : ColMuted);

            AddSteamAvatar(c, accountCard, accountCard + ".Avatar", player.userID,
                "0 0.5", "0 0.5", "16 -38", "92 38");

            string identityLine =
                "<size=18><color=" + ColText + ">" + Safe(player.displayName, 28) + "</color></size>\n" +
                "<size=10><color=" + ColSubText + ">SteamID  " + player.UserIDString + "</color></size>";
            Label(c, accountCard, "0 0", "0 1", "108 0", "360 0",
                identityLine, 18, ColText, TextAnchor.MiddleLeft, Bold);

            Panel(c, accountCard, accountCard + ".Sep1", "0 0", "0 1",
                "374 16", "375 -16", "1 1 1 0.07");

            if (accountLoaded)
            {
                int level = GetInt(account, "Level");
                long exp = GetLong(account, "Exp");
                long required = Math.Max(1, GetLong(account, "RequiredExp"));
                double progress = Math.Max(0d, Math.Min(1d, GetDouble(account, "Progress")));

                string levelLine =
                    "<size=10><color=" + ColMuted + ">УРОВЕНЬ</color></size>\n" +
                    "<size=23><color=" + ColText + ">" + level + "</color></size>";
                Label(c, accountCard, "0 0", "0 1", "402 0", "500 0",
                    levelLine, 23, ColText, TextAnchor.MiddleLeft, Bold);

                Panel(c, accountCard, accountCard + ".Sep2", "0 0", "0 1",
                    "526 12", "527 -12", "1 1 1 0.07");

                string expLine =
                    "<size=10><color=" + ColMuted + ">EXP ДО СЛЕДУЮЩЕГО УРОВНЯ</color></size>\n" +
                    "<size=14><color=" + ColText + ">" +
                    FormatNumber(exp) + " / " + FormatNumber(required) + "</color></size>";
                Label(c, accountCard, "0 0", "0 1", "556 14", "842 22",
                    expLine, 14, ColText, TextAnchor.MiddleLeft, Bold);

                string frame = accountCard + ".ExpFrame";
                Panel(c, accountCard, frame, "0 0", "0 0",
                    "556 24", "842 38", "#B2A9A366");
                Panel(c, frame, frame + ".Track", "0 0", "1 1",
                    "2 2", "-2 -2", "0 0 0 0.32");
                if (progress > 0d)
                    Panel(c, frame, frame + ".Fill", "0 0", F((float)progress) + " 1",
                        "2 2", "-2 -2", ColGreenBtn);
            }
            else
            {
                Label(c, accountCard, "0.38 0", "0.79 1", "0 0", "0 0",
                    "AccountSystem недоступен", 12, ColMuted, TextAnchor.MiddleLeft, Bold);
            }

            SecondaryActionButton(c, accountCard, "1 0.5", "1 0.5", "-170 -18", "-18 18",
                accountLoaded ? "servermenu.ui tab account" : "",
                accountLoaded ? "МОЙ ПРОФИЛЬ" : "НЕДОСТУПНО", accountLoaded);

            string hero = Main + ".Hero";

            Plugin clanPlugin = ClanSystem ?? Interface.Oxide.RootPluginManager.GetPlugin("ClanSystem");
            Dictionary<string, object> clan = clanPlugin != null && clanPlugin.IsLoaded
                ? clanPlugin.Call("API_GetHomeSummary", player) as Dictionary<string, object>
                : null;

            object clanValue;
            bool hasClan = clan != null && clan.TryGetValue("HasClan", out clanValue) && ToBool(clanValue);
            string clanTag = clan != null && clan.TryGetValue("Tag", out clanValue) && clanValue != null
                ? clanValue.ToString()
                : string.Empty;

            Panel(c, Main, hero, "0 1", "0 1", "22 -292", "1098 -200", ColCard);
            Panel(c, hero, hero + ".Accent", "0 0", "0 1", "0 0", "4 0", hasClan ? ColGreenBtn : ColMuted);

            string clanLine =
                "<size=10><color=" + ColMuted + ">ВАШ КЛАН</color></size>\n" +
                "<size=22><color=" + (hasClan ? ColText : ColMuted) + ">" +
                (clan == null ? "НЕДОСТУПНО" : hasClan ? Safe(clanTag, 16) : "ВЫ НЕ В КЛАНЕ") +
                "</color></size>";
            Label(c, hero, "0 0", "0 1", "18 0", "374 0",
                clanLine, 22, ColText, TextAnchor.MiddleLeft, Bold);

            Panel(c, hero, hero + ".Sep1", "0 0", "0 1", "388 16", "389 -16", "1 1 1 0.07");

            if (hasClan)
            {
                Label(c, hero, "0 0", "0 1", "412 0", "530 0",
                    "<size=10><color=" + ColMuted + ">МЕСТО В ТОПЕ</color></size>\n" +
                    "<size=23><color=" + ColText + ">#" + GetInt(clan, "Rank") + "</color></size>",
                    23, ColText, TextAnchor.MiddleLeft, Bold);

                Panel(c, hero, hero + ".Sep2", "0 0", "0 1", "552 12", "553 -12", "1 1 1 0.07");

                Label(c, hero, "0 0", "0 1", "578 0", "716 0",
                    "<size=10><color=" + ColMuted + ">ОЧКИ КЛАНА</color></size>\n" +
                    "<size=14><color=" + ColText + ">" + FormatNumber(GetInt(clan, "Score")) + "</color></size>",
                    14, ColText, TextAnchor.MiddleLeft, Bold);

                Label(c, hero, "0 0", "0 1", "740 0", "900 0",
                    "<size=10><color=" + ColMuted + ">УЧАСТНИКИ</color></size>\n" +
                    "<size=14><color=" + ColText + ">" +
                    GetInt(clan, "Online") + " из " + GetInt(clan, "Members") + " в сети</color></size>",
                    14, ColText, TextAnchor.MiddleLeft, Bold);
            }
            else
            {
                int clanInvites = GetInt(clan, "Invites");

                Label(c, hero, "0 0", "0 1", "412 0", "900 0",
                    clan == null
                        ? "Плагин кланов не загружен"
                        : clanInvites > 0
                            ? "Вас приглашают в клан: " + clanInvites
                            : "Создайте свой клан или примите приглашение",
                    12, ColSubText, TextAnchor.MiddleLeft, Reg);
            }

            SecondaryActionButton(c, hero, "1 0.5", "1 0.5", "-170 -18", "-18 18",
                clan != null ? "servermenu.ui tab clans" : "",
                hasClan ? "ОТКРЫТЬ КЛАН" : "СОЗДАТЬ КЛАН", clan != null);

            Label(c, Main, "0 1", "1 1", "22 -326", "-22 -302",
                "БЫСТРЫЙ ДОСТУП", 12, ColMuted, TextAnchor.MiddleLeft, Bold);

            string prefix = CurrentPrefix(player);
            if (string.IsNullOrEmpty(prefix)) prefix = "НЕ ВЫБРАН";

            HomeActionRow(c, Main + ".QuickKits", -336,
                "КИТЫ", "Наборы сервера, кулдауны и содержимое",
                PluginLoaded("Kits") ? "servermenu.ui tab kits" : "", "ОТКРЫТЬ", PluginLoaded("Kits"), false);

            HomeActionRow(c, Main + ".QuickServices", -388,
                "СЕРВИСЫ", "Миникоптер, автокод, BlueprintShare и BuildTools",
                "servermenu.ui tab services", "ОТКРЫТЬ", true, false);

            HomeActionRow(c, Main + ".QuickPrefix", -440,
                "ЧАТ-ПРЕФИКС", "Текущий: " + prefix,
                PluginLoaded("ChatPrefixes") ? "chat.say /prefix" : "", "НАСТРОИТЬ", PluginLoaded("ChatPrefixes"), false);

            HomeActionRow(c, Main + ".QuickReport", -492,
                "РЕПОРТ", "Связаться с администрацией сервера",
                PluginLoaded("AdminMenu") ? "chat.say /report" : "", "СОЗДАТЬ", PluginLoaded("AdminMenu"), true);
        }

        private void DrawExpTop(CuiElementContainer c, BasePlayer player, MenuState state)
        {
            Plugin plugin = GetAccountSystemPlugin();

            if (plugin == null)
            {
                Heading(c, "ТОП ИГРОКОВ", "Рейтинг аккаунтов по общему EXP");
                Empty(c, "ACCOUNTSYSTEM НЕ ЗАГРУЖЕН", "Плагин AccountSystem сейчас отсутствует на сервере.");
                return;
            }

            // Используем старый/стабильный API_GetExpTop.
            // В клиентский CUI всё равно попадут только 10 строк текущей страницы.
            object raw = null;
            try
            {
                raw = plugin.Call("API_GetExpTop");
            }
            catch (Exception ex)
            {
                PrintWarning("AccountSystem.API_GetExpTop: " + ex.Message);
            }

            List<Dictionary<string, object>> all = raw as List<Dictionary<string, object>>;
            if (all == null)
            {
                var enumerable = raw as IEnumerable<Dictionary<string, object>>;
                if (enumerable != null)
                    all = new List<Dictionary<string, object>>(enumerable);
            }

            if (all == null)
            {
                Heading(c, "ТОП ИГРОКОВ", "Рейтинг аккаунтов по общему EXP");
                string version = plugin.Version != null ? plugin.Version.ToString() : "?";
                Empty(c, "ТОП НЕДОСТУПЕН",
                    "AccountSystem " + version + " не вернул API_GetExpTop.");
                return;
            }

            int total = all.Count;
            int pageCount = Math.Max(1, (total + ExpTopPageSize - 1) / ExpTopPageSize);
            int page = state == null ? 0 : Math.Max(0, Math.Min(pageCount - 1, state.TopPage));

            if (state != null)
                state.TopPage = page;

            int myRank = 0;
            int myPage = 0;

            for (int i = 0; i < all.Count; i++)
            {
                Dictionary<string, object> entry = all[i];
                if (entry == null) continue;

                if ((ulong)Math.Max(0L, GetLong(entry, "SteamId")) == player.userID)
                {
                    myRank = GetInt(entry, "Rank");
                    if (myRank <= 0) myRank = i + 1;
                    myPage = i / ExpTopPageSize;
                    break;
                }
            }

            int startIndex = page * ExpTopPageSize;
            int endIndex = Math.Min(total, startIndex + ExpTopPageSize);

            var rows = new List<Dictionary<string, object>>(Math.Max(0, endIndex - startIndex));
            for (int i = startIndex; i < endIndex; i++)
                rows.Add(all[i]);

            string subtitle = "Рейтинг всех аккаунтов по общему EXP  ·  " +
                              FormatNumber(total) + " игроков";

            if (myRank > 0)
                subtitle += "  ·  Ваша позиция  #" + myRank.ToString(CultureInfo.InvariantCulture);

            Heading(c, "ТОП ИГРОКОВ", subtitle);

            const float headTop = -84f;
            const float headBottom = -112f;

            string head = Main + ".ExpTop.Head";
            Panel(c, Main, head, "0 1", "1 1", "22 " + F(headBottom), "-22 " + F(headTop), ColCardDark);

            Label(c, head, "0 0", "0.09 1", "12 0", "0 0",
                "МЕСТО", 10, ColMuted, TextAnchor.MiddleLeft, Bold);
            Label(c, head, "0.09 0", "0.47 1", "8 0", "0 0",
                "ИГРОК", 10, ColMuted, TextAnchor.MiddleLeft, Bold);
            Label(c, head, "0.47 0", "0.59 1", "0 0", "-8 0",
                "УРОВЕНЬ", 10, ColMuted, TextAnchor.MiddleRight, Bold);
            Label(c, head, "0.59 0", "0.76 1", "0 0", "-8 0",
                "EXP", 10, ColMuted, TextAnchor.MiddleRight, Bold);
            Label(c, head, "0.76 0", "0.91 1", "0 0", "-8 0",
                "ВРЕМЯ", 10, ColMuted, TextAnchor.MiddleRight, Bold);
            Label(c, head, "0.91 0", "1 1", "0 0", "-12 0",
                "СТАТУС", 10, ColMuted, TextAnchor.MiddleRight, Bold);

            if (total == 0)
            {
                Panel(c, Main, Main + ".ExpTop.Empty", "0 1", "1 1",
                    "22 -240", "-22 -118", ColCardDark);
                Label(c, Main + ".ExpTop.Empty", "0 0", "1 1", "18 0", "-18 0",
                    "В AccountSystem пока нет игроков.", 12, ColMuted, TextAnchor.MiddleCenter, Reg);
                return;
            }

            const float rowH = 40f;
            const float rowGap = 2f;
            const float firstRowTop = -118f;

            // Никакого ScrollView на сотни игроков: клиент получает только 10 строк текущей страницы.
            for (int i = 0; i < rows.Count; i++)
            {
                Dictionary<string, object> entry = rows[i];
                if (entry == null) continue;

                ulong steamId = (ulong)Math.Max(0L, GetLong(entry, "SteamId"));
                int rank = GetInt(entry, "Rank");
                int level = GetInt(entry, "Level");
                long totalExp = GetLong(entry, "TotalExp");
                long playSeconds = GetLong(entry, "PlaySeconds");

                object value;
                string name = entry.TryGetValue("Name", out value) && value != null
                    ? value.ToString()
                    : steamId.ToString(CultureInfo.InvariantCulture);
                bool online = entry.TryGetValue("Online", out value) && ToBool(value);
                bool mine = steamId == player.userID;

                float rowTop = firstRowTop - i * (rowH + rowGap);
                float rowBottom = rowTop - rowH;
                string row = Main + ".ExpTop.Row." + i.ToString(CultureInfo.InvariantCulture);

                Panel(c, Main, row, "0 1", "1 1",
                    "22 " + F(rowBottom), "-22 " + F(rowTop),
                    mine ? "#65A30D24" : (i % 2 == 0 ? ColCard : ColCardDark));

                if (mine)
                    Panel(c, row, row + ".Mine", "0 0", "0 1", "0 0", "3 0", ColGreenBtn);

                Label(c, row, "0 0", "0.09 1", "12 0", "0 0",
                    "#" + rank.ToString(CultureInfo.InvariantCulture), 11,
                    mine || rank == 1 ? ColGreenTxt : ColMuted,
                    TextAnchor.MiddleLeft, Bold);

                Label(c, row, "0.09 0", "0.47 1", "8 0", "0 0",
                    Safe(name, 28), 14,
                    mine ? ColGreenTxt : ColText,
                    TextAnchor.MiddleLeft, Bold);

                Label(c, row, "0.47 0", "0.59 1", "0 0", "-8 0",
                    level.ToString(CultureInfo.InvariantCulture), 11,
                    ColSubText, TextAnchor.MiddleRight, Bold);

                Label(c, row, "0.59 0", "0.76 1", "0 0", "-8 0",
                    FormatNumber(totalExp), 13,
                    mine ? ColGreenTxt : ColText,
                    TextAnchor.MiddleRight, Bold);

                Label(c, row, "0.76 0", "0.91 1", "0 0", "-8 0",
                    FormatLifetimeTime(playSeconds), 11,
                    ColSubText, TextAnchor.MiddleRight, Reg);

                Label(c, row, "0.91 0", "1 1", "0 0", "-12 0",
                    online ? "<color=#65A30D>●</color> В СЕТИ" : "ОФЛАЙН", 10,
                    online ? ColGreenTxt : ColMuted,
                    TextAnchor.MiddleRight, Bold);

                // Вся строка кликабельная.
                Button(c, row, "0 0", "1 1", "0 0", "0 0",
                    "servermenu.ui topplayer " + steamId.ToString(CultureInfo.InvariantCulture),
                    "", "0 0 0 0");
            }

            const float navY1 = -568f;
            const float navY2 = -536f;

            SecondaryActionButton(c, Main, "0 1", "0 1",
                "22 " + F(navY1), "154 " + F(navY2),
                page > 0 ? "servermenu.ui toppage " + (page - 1).ToString(CultureInfo.InvariantCulture) : "",
                "НАЗАД", page > 0);

            Label(c, Main, "0 1", "1 1",
                "392 " + F(navY1), "-392 " + F(navY2),
                "СТРАНИЦА " + (page + 1).ToString(CultureInfo.InvariantCulture) +
                " / " + pageCount.ToString(CultureInfo.InvariantCulture),
                11, ColMuted, TextAnchor.MiddleCenter, Bold);

            SecondaryActionButton(c, Main, "1 1", "1 1",
                "-154 " + F(navY1), "-22 " + F(navY2),
                page + 1 < pageCount ? "servermenu.ui toppage " + (page + 1).ToString(CultureInfo.InvariantCulture) : "",
                "ВПЕРЁД", page + 1 < pageCount);

            if (myRank > 0 && myPage != page)
            {
                SecondaryActionButton(c, Main, "0.5 1", "0.5 1",
                    "-92 " + F(navY1), "92 " + F(navY2),
                    "servermenu.ui toppage " + myPage.ToString(CultureInfo.InvariantCulture),
                    "МОЁ МЕСТО  #" + myRank.ToString(CultureInfo.InvariantCulture), true);
            }
        }

        private void DrawKits(CuiElementContainer c, BasePlayer player)
        {
            Heading(c, "КИТЫ", "Обложки, содержимое и получение наборов");

            Plugin kitsPlugin = Kits ?? Interface.Oxide.RootPluginManager.GetPlugin("Kits");
            bool loaded = kitsPlugin != null && kitsPlugin.IsLoaded;
            bool canEdit = false;

            if (loaded)
            {
                object editObj = kitsPlugin.Call("API_CanEdit", player);
                canEdit = editObj is bool && (bool)editObj;
            }

            if (canEdit)
                SecondaryActionButton(c, Main, "1 1", "1 1", "-168 -58", "-22 -22",
                    "servermenu.ui chat /kits admin", "УПРАВЛЕНИЕ", true);

            if (!loaded)
            {
                Empty(c, "KITS НЕДОСТУПЕН", "Плагин Kits не загружен.");
                return;
            }

            object rawKits = kitsPlugin.Call("API_GetMenuKits", player);
            List<Dictionary<string, object>> list = rawKits as List<Dictionary<string, object>>;
            if (list == null)
            {
                var enumerable = rawKits as IEnumerable<Dictionary<string, object>>;
                if (enumerable != null) list = new List<Dictionary<string, object>>(enumerable);
            }

            if (list == null)
            {
                Empty(c, "KITS API НЕДОСТУПЕН", "Перезагрузите Kits, затем ServerMenu.");
                return;
            }

            if (list.Count == 0)
            {
                Empty(c, "НАБОРЫ ПОКА НЕ НАСТРОЕНЫ",
                    canEdit ? "Откройте управление и создайте первый набор." : "Администратор ещё не добавил наборы.");
                return;
            }

            int shown = Math.Min(4, list.Count);
            for (int i = 0; i < shown; i++)
            {
                Dictionary<string, object> kit = list[i];
                if (kit == null) continue;

                float top = -84 - i * 100;
                float bottom = top - 90;
                DrawServerKitCard(c, kit, i, 22, 1098, bottom, top);
            }

            if (list.Count > 4)
            {
                Label(c, Main, "0 0", "0.70 0", "22 14", "0 42",
                    "Показаны первые 4 набора из " + list.Count + ".", 11, ColMuted, TextAnchor.MiddleLeft, Reg);
                SecondaryActionButton(c, Main, "1 0", "1 0", "-154 10", "-22 44",
                    "chat.say /kits", "ВСЕ КИТЫ", true);
            }
        }

        private void DrawServerKitCard(CuiElementContainer c, Dictionary<string, object> kit, int index, float x1, float x2, float y1, float y2)
        {
            object o;
            string id = kit.TryGetValue("Id", out o) && o != null ? o.ToString() : string.Empty;
            string name = kit.TryGetValue("Name", out o) && o != null ? Safe(o.ToString(), 28) : "KIT";
            string description = kit.TryGetValue("Description", out o) && o != null ? Safe(o.ToString(), 52) : string.Empty;
            int cooldown = kit.TryGetValue("Cooldown", out o) ? ToInt(o) : 0;
            int remaining = kit.TryGetValue("Remaining", out o) ? ToInt(o) : 0;
            int items = kit.TryGetValue("Items", out o) ? ToInt(o) : 0;
            bool access = kit.TryGetValue("HasPermission", out o) && ToBool(o);
            bool canClaim = kit.TryGetValue("CanClaim", out o) && ToBool(o);
            string png = kit.TryGetValue("ImagePng", out o) && o != null ? o.ToString() : string.Empty;

            string card = Main + ".Kit." + index;
            Panel(c, Main, card, "0 1", "0 1", F(x1) + " " + F(y1), F(x2) + " " + F(y2),
                index % 2 == 0 ? ColCard : ColCardDark);
            Panel(c, card, card + ".Accent", "0 0", "0 1", "0 0", "3 0",
                access ? (canClaim ? ColGreenBtn : ColText) : ColRedText);

            string cover = card + ".Cover";
            Panel(c, card, cover, "0 0.5", "0 0.5", "14 -36", "86 36", ColCardDark);
            if (!string.IsNullOrEmpty(png)) RawImage(c, cover, png);
            else Label(c, cover, "0 0", "1 1", "0 0", "0 0", "KIT", 16, ColMuted, TextAnchor.MiddleCenter, Bold);

            Label(c, card, "0 0.5", "0.40 1", "102 0", "0 -6",
                name, 15, ColText, TextAnchor.LowerLeft, Bold);
            Label(c, card, "0 0", "0.40 0.5", "102 6", "0 0",
                description, 11, ColSubText, TextAnchor.UpperLeft, Reg);

            Panel(c, card, card + ".SepInfo", "0.41 0", "0.41 1", "0 12", "1 -12", "1 1 1 0.06");
            Panel(c, card, card + ".SepAction", "0.69 0", "0.69 1", "0 12", "1 -12", "1 1 1 0.06");

            string status;
            string statusColor;
            if (!access) { status = "НЕТ ДОСТУПА"; statusColor = ColRedText; }
            else if (remaining > 0) { status = "КД · " + HumanKitTime(remaining); statusColor = ColText; }
            else if (items <= 0) { status = "ПУСТО"; statusColor = ColMuted; }
            else { status = "ДОСТУПЕН"; statusColor = "#FFFFFF"; }

            Label(c, card, "0.42 0.5", "0.68 1", "12 0", "-8 -6",
                status, 13, statusColor, TextAnchor.LowerLeft, Bold);
            Label(c, card, "0.42 0", "0.68 0.5", "12 6", "-8 0",
                items + " ПРЕДМ.   •   " + (cooldown <= 0 ? "БЕЗ КД" : HumanKitTime(cooldown)),
                11, ColSubText, TextAnchor.UpperLeft, Reg);

            Button(c, card, "0.705 0.5", "0.825 0.5", "0 -16", "-6 16",
                items > 0 ? "servermenu.ui kitpreview " + id : "",
                "СОСТАВ",
                items > 0 ? ColSecondaryBtn : ColSecondaryBtnDisabled,
                12, items > 0 ? ColSecondaryText : ColMuted);

            string actionColor = canClaim ? ColGreenBtn : (!access ? ColRedStrong : ColSecondaryBtn);
            string actionTextColor = canClaim || !access ? "#FFFFFF" : ColSecondaryText;
            Button(c, card, "0.825 0.5", "1 0.5", "6 -16", "-14 16",
                canClaim ? "servermenu.ui kitclaim " + id : "",
                canClaim ? "ПОЛУЧИТЬ" : status,
                actionColor, 12, actionTextColor);
        }

        private static string HumanKitTime(int seconds)
        {
            if (seconds <= 0) return "без КД";
            int d = seconds / 86400;
            int h = (seconds % 86400) / 3600;
            int m = (seconds % 3600) / 60;
            int sec = seconds % 60;
            if (d > 0) return d + "д" + (h > 0 ? " " + h + "ч" : "");
            if (h > 0) return h + "ч" + (m > 0 ? " " + m + "м" : "");
            if (m > 0) return m + "м" + (sec > 0 ? " " + sec + "с" : "");
            return sec + "с";
        }

        private void DrawServices(CuiElementContainer c, BasePlayer player)
        {
            Heading(c, "СЕРВИСЫ", "Актуальные игровые функции и быстрые действия");

            // Левая колонка: транспорт, телепортация и строительство.
            GroupTitle(c, 22, 548, -88, "ТРАНСПОРТ И ТЕЛЕПОРТАЦИЯ");

            bool spawnMini = PluginLoaded("SpawnMini");
            CompactActionCard(c, Main + ".SvcMini", 22, 548, -116, -170,
                "ЛИЧНЫЙ МИНИКОПТЕР", "Создать или убрать свой миникоптер",
                spawnMini ? "chat.say /mymini" : "", "ПЕРЕКЛЮЧИТЬ", spawnMini);

            bool teleport = PluginLoaded("IQTeleportation");
            CompactActionCard(c, Main + ".SvcHomes", 22, 548, -178, -232,
                "МОИ ДОМА", "Показать сохранённые точки дома",
                teleport ? "chat.say /homelist" : "", "ПОКАЗАТЬ", teleport);

            CompactActionCard(c, Main + ".SvcTpr", 22, 548, -240, -294,
                "ТЕЛЕПОРТ К ИГРОКУ", "Показать команду отправки TPR-запроса",
                teleport ? "chat.say /tpr" : "", "КАК ИСПОЛЬЗОВАТЬ", teleport);

            GroupTitle(c, 22, 548, -326, "СТРОИТЕЛЬСТВО");

            bool buildTools = PluginLoaded("BuildTools");
            CompactActionCard(c, Main + ".SvcUp", 22, 548, -354, -408,
                "УЛУЧШЕНИЕ", "Включить режим улучшения построек",
                buildTools ? "chat.say /up" : "", "ВКЛЮЧИТЬ", buildTools);

            CompactActionCard(c, Main + ".SvcRemove", 22, 548, -416, -470,
                "УДАЛЕНИЕ", "Включить режим удаления построек",
                buildTools ? "chat.say /remove" : "", "ВКЛЮЧИТЬ", buildTools);

            CompactActionCard(c, Main + ".SvcBuild", 22, 548, -478, -532,
                "BUILD TOOLS", "Настройки режимов строительства и скинов",
                buildTools ? "chat.say /bskin" : "", "НАСТРОЙКИ", buildTools);

            // Правая колонка: системные и социальные сервисы.
            GroupTitle(c, 562, 1098, -88, "СИСТЕМЫ");

            bool autoCode = PluginLoaded("AutoCodeLock");
            CompactActionCard(c, Main + ".SvcCode", 562, 1098, -116, -170,
                "АВТОКОД", "Посмотреть или изменить код автоматических замков",
                autoCode ? "chat.say /code" : "", "ОТКРЫТЬ", autoCode);

            bool blueprintShare = PluginLoaded("BlueprintShare");
            CompactActionCard(c, Main + ".SvcBpToggle", 562, 1098, -178, -232,
                "ОБЩИЕ ЧЕРТЕЖИ", "Включить или выключить обмен чертежами",
                blueprintShare ? "servermenu.ui chat /bs toggle" : "", "ПЕРЕКЛЮЧИТЬ", blueprintShare);

            GroupTitle(c, 562, 1098, -264, "ОБЩЕНИЕ И КОМАНДА");

            bool trade = PluginLoaded("Trade");
            CompactActionCard(c, Main + ".SvcTrade", 562, 1098, -292, -346,
                "ОБМЕН", "Обмен предметами с другим игроком",
                trade ? "chat.say /trade" : "", "КАК ИСПОЛЬЗОВАТЬ", trade);

            bool teamAuth = PluginLoaded("ServerTeamAuth");
            CompactActionCard(c, Main + ".SvcFriendlyFire", 562, 1098, -354, -408,
                "УРОН ПО КЛАНУ", "Лидеру клана: включить или выключить Friendly Fire",
                teamAuth ? "chat.say /ff" : "", "ПЕРЕКЛЮЧИТЬ", teamAuth);

            bool adminMenu = PluginLoaded("AdminMenu");
            CompactActionCard(c, Main + ".SvcReport", 562, 1098, -416, -470,
                "РЕПОРТ", "Связаться с администрацией сервера",
                adminMenu ? "chat.say /report" : "", "СОЗДАТЬ", adminMenu);
        }

        private void DrawFeatures(CuiElementContainer c, BasePlayer player)
        {
            Heading(c, "ВОЗМОЖНОСТИ", "Системы, которые работают автоматически во время игры");

            FeatureVisualCard(c, 0, 0, "МГНОВЕННЫЙ КРАФТ", "Без ожидания", "workbench1", "InstantCraft");
            FeatureVisualCard(c, 1, 0, "КРАФТ ВЕЗДЕ", "Без привязки к месту", "building.planner", "CraftAnywhere");
            FeatureVisualCard(c, 2, 0, "РЕЙТЫ X1000", "Повышенная добыча", "stones", "Rate");
            FeatureVisualCard(c, 3, 0, "БЫСТРЫЙ РЕЦИКЛЕР", "Ускоренная переработка", "scrap", "RecyclerSpeed");

            FeatureVisualCard(c, 0, 1, "УВЕЛИЧЕННЫЕ СТАКИ", "Больше предметов в стаках", "wood", "Stacks");
            FeatureVisualCard(c, 1, 1, "АВТОПОДБОР", "Быстрый сбор бочек", "metal.fragments", "AutoPickupBarrel");
            FeatureVisualCard(c, 2, 1, "РЮКЗАК", "Дополнительное хранение", "largebackpack", "BackpackStorage");
            FeatureVisualCard(c, 3, 1, "TEAM AUTH", "Авторизация команды", "code.lock", "TeamAuth");

            FeatureVisualCard(c, 0, 2, "RESPAWN KIT", "Набор после возрождения", "bandage", "RespawnKit");
            FeatureVisualCard(c, 1, 2, "БЫСТРЫЙ AIRDROP", "Ускоренный самолёт", "supply.signal", "FastAirdropPlane");
            FeatureVisualCard(c, 2, 2, "HIT MARKER", "Индикация попаданий", "rifle.ak", "HitMarker");
            FeatureVisualCard(c, 3, 2, "ОБЩИЕ ЧЕРТЕЖИ", "Синхронизация изученного", "paper", "BlueprintShare");
        }

        private void DrawCommands(CuiElementContainer c, BasePlayer player)
        {
            Heading(c, "КОМАНДЫ", "Памятка по доступным игрокам командам из текущей сборки сервера");

            const int topStart = -116;
            const int rowHeight = 42;
            const int rowStep = 48;

            // 3 компактные колонки позволяют показать все основные пользовательские
            // команды без скролла и без смешивания их со служебными/admin-командами.
            int leftA = 22, rightA = 370;
            int leftB = 382, rightB = 728;
            int leftC = 740, rightC = 1098;

            GroupTitle(c, leftA, rightA, -88, "ОСНОВНЫЕ И ОБЩЕНИЕ");
            CommandRow(c, Main + ".CmdMenu", leftA, rightA, topStart, rowHeight, rowStep, 0, "/menu  /m  /daily", "Меню сервера; /daily — награды");
            CommandRow(c, Main + ".CmdTop", leftA, rightA, topStart, rowHeight, rowStep, 1, "/top", "Топ игроков по EXP");
            CommandRow(c, Main + ".CmdKits", leftA, rightA, topStart, rowHeight, rowStep, 2, "/kit  /kits", "Меню серверных наборов");
            CommandRow(c, Main + ".CmdAccount", leftA, rightA, topStart, rowHeight, rowStep, 3, "/profile  /account", "Профиль и статистика аккаунта");
            CommandRow(c, Main + ".CmdPrefix", leftA, rightA, topStart, rowHeight, rowStep, 4, "/prefix", "Выбрать чат-префикс");
            CommandRow(c, Main + ".CmdTrade", leftA, rightA, topStart, rowHeight, rowStep, 5, "/trade НИК", "Предложить игроку обмен");
            CommandRow(c, Main + ".CmdReport", leftA, rightA, topStart, rowHeight, rowStep, 6, "/report", "Связаться с администрацией");
            CommandRow(c, Main + ".CmdPm", leftA, rightA, topStart, rowHeight, rowStep, 7, "/pm НИК ТЕКСТ", "Личное сообщение игроку");

            GroupTitle(c, leftB, rightB, -88, "ТЕЛЕПОРТАЦИЯ И ДОМ");
            CommandRow(c, Main + ".CmdMini", leftB, rightB, topStart, rowHeight, rowStep, 0, "/mymini", "Создать / убрать миникоптер");
            CommandRow(c, Main + ".CmdTpr", leftB, rightB, topStart, rowHeight, rowStep, 1, "/tpr НИК", "Запрос телепорта к игроку");
            CommandRow(c, Main + ".CmdTpa", leftB, rightB, topStart, rowHeight, rowStep, 2, "/tpa  /tpc", "Принять / отменить телепорт");
            CommandRow(c, Main + ".CmdSetHome", leftB, rightB, topStart, rowHeight, rowStep, 3, "/sethome ИМЯ", "Сохранить дом; кратко /sh");
            CommandRow(c, Main + ".CmdHome", leftB, rightB, topStart, rowHeight, rowStep, 4, "/home ИМЯ", "Телепортироваться домой");
            CommandRow(c, Main + ".CmdRemoveHome", leftB, rightB, topStart, rowHeight, rowStep, 5, "/removehome ИМЯ", "Удалить дом; кратко /rh");
            CommandRow(c, Main + ".CmdHomeList", leftB, rightB, topStart, rowHeight, rowStep, 6, "/homelist", "Показать сохранённые дома");
            CommandRow(c, Main + ".CmdAtp", leftB, rightB, topStart, rowHeight, rowStep, 7, "/atp", "Автопринятие TPR от друзей");

            GroupTitle(c, leftC, rightC, -88, "СИСТЕМЫ И НАСТРОЙКИ");
            CommandRow(c, Main + ".CmdBuildModes", leftC, rightC, topStart, rowHeight, rowStep, 0, "/up /remove /down", "Режимы BuildTools");
            CommandRow(c, Main + ".CmdBuildSkin", leftC, rightC, topStart, rowHeight, rowStep, 1, "/bskin", "Настройки строительства / скинов");
            CommandRow(c, Main + ".CmdSkin", leftC, rightC, topStart, rowHeight, rowStep, 2, "/skin", "Меню скинов предметов");
            CommandRow(c, Main + ".CmdCode", leftC, rightC, topStart, rowHeight, rowStep, 3, "/code [1234]", "Посмотреть или сменить автокод");
            CommandRow(c, Main + ".CmdBlueprintShare", leftC, rightC, topStart, rowHeight, rowStep, 4, "/bs", "Общие чертежи: help/toggle/share/show");
            CommandRow(c, Main + ".CmdFriendlyFire", leftC, rightC, topStart, rowHeight, rowStep, 5, "/ff", "Лидеру клана: Friendly Fire");
            CommandRow(c, Main + ".CmdDm", leftC, rightC, topStart, rowHeight, rowStep, 6, "/dm on  /dm off", "Включить / выключить киллфид");
            CommandRow(c, Main + ".CmdNight", leftC, rightC, topStart, rowHeight, rowStep, 7, "/night yes|no", "Голос за ночь; /skipnight = да");

            // Дополнительные контекстные команды показываем отдельной короткой строкой
            // под основным списком: они нужны игрокам только при активном ЛС/проверке/репорте.
            GroupTitle(c, 22, 1098, -512, "ДОПОЛНИТЕЛЬНО");
            CompactCommand(c, Main + ".CmdExtraPm", 22, 370, -540, -582,
                "/pmoff  /pmon", "Запретить / разрешить входящие ЛС");
            CompactCommand(c, Main + ".CmdExtraCheck", 382, 728, -540, -582,
                "/насвязи  /ls", "Связь во время проверки");
            CompactCommand(c, Main + ".CmdExtraReport", 740, 1098, -540, -582,
                "/rls ТЕКСТ", "Личный чат активного репорта");
        }

        private void CommandRow(CuiElementContainer c, string name, int left, int right,
            int topStart, int rowHeight, int rowStep, int row, string command, string desc)
        {
            int top = topStart - row * rowStep;
            CompactCommand(c, name, left, right, top, top - rowHeight, command, desc);
        }

        private void DrawAdmin(CuiElementContainer c, BasePlayer player)
        {
            Heading(c, "АДМИНИСТРИРОВАНИЕ", "Служебные инструменты отображаются только персоналу");
            if (!HasStaffAccess(player))
            {
                Empty(c, "НЕТ ДОСТУПА", "У вас нет административной роли.");
                return;
            }

            bool lootAdmin = player.IsAdmin || permission.UserHasPermission(player.UserIDString, "zecolootui.edit");

            AdminVisualCard(c, 0, 0, "АДМИН-МЕНЮ", "Игроки, роли, наказания и репорты", "computerstation", ColRedBtn,
                PluginLoaded("AdminMenu") ? "chat.say /am" : "", "ОТКРЫТЬ", PluginLoaded("AdminMenu"));
            AdminVisualCard(c, 1, 0, "ADMIN ESP", "Настройка служебного ESP", "binoculars", ColBlue,
                PluginLoaded("AdminESP") ? "adminEsp_mainmenu" : "", "ОТКРЫТЬ", PluginLoaded("AdminESP"));
            AdminVisualCard(c, 2, 0, "VANISH", "Режим невидимости администратора", "hazmatsuit", ColGreenTxt,
                PluginLoaded("Vanish") ? "chat.say /vanish" : "", "ПЕРЕКЛЮЧИТЬ", PluginLoaded("Vanish"));

            AdminVisualCard(c, 0, 1, "ПРЕФИКСЫ", "Управление чат-префиксами", "note", ColPremium,
                PluginLoaded("ChatPrefixes") ? "chat.say /prefixadmin" : "", "УПРАВЛЕНИЕ", PluginLoaded("ChatPrefixes"));
            AdminVisualCard(c, 1, 1, "РЕДАКТОР ЛУТА", "Наведитесь на контейнер перед открытием", "box.wooden.large", ColText,
                lootAdmin && PluginLoaded("zEcoLootUI") ? "zecoloot.open" : "", "ОТКРЫТЬ", lootAdmin && PluginLoaded("zEcoLootUI"));
        }

        private void Heading(CuiElementContainer c, string title)
        {
            Heading(c, title, string.Empty);
        }

        private void Heading(CuiElementContainer c, string title, string subtitle)
        {
            Label(c, Main, "0 1", "1 1", "22 -40", "-22 -8", title, 24, ColText, TextAnchor.MiddleLeft, Bold);
            if (!string.IsNullOrEmpty(subtitle))
                Label(c, Main, "0 1", "1 1", "22 -64", "-22 -38", subtitle, 12, ColSubText, TextAnchor.MiddleLeft, Reg);
            Panel(c, Main, Main + ".HeadingLine", "0 1", "1 1", "22 -72", "-22 -71", "1 1 1 0.07");
        }

        private void NativeSection(CuiElementContainer c, string title, int y)
        {
            NativeSection(c, Main, title, y);
        }

        private void NativeSection(CuiElementContainer c, string parent, string title, int y)
        {

            string left = parent == Main ? "36 " : "14 ";
            Label(c, parent, "0 1", "1 1", left + (y - 24), "-8 " + y, title, 13, ColMuted, TextAnchor.MiddleLeft, Bold);
        }

        private void NativeActionRow(CuiElementContainer c, string name, int top, string title, string desc, string command, string buttonText, bool available)
        {
            NativeActionRow(c, Main, name, top, title, desc, command, buttonText, available);
        }

        private void NativeActionRow(CuiElementContainer c, string parent, string name, int top, string title, string desc, string command, string buttonText, bool available)
        {
            int bottom = top - 46;
            Panel(c, parent, name, "0 1", "1 1", "0 " + bottom, "-8 " + top, ColCard);
            Label(c, name, "0 0", "0.36 1", "14 0", "0 0", title, 14, available ? ColText : ColMuted, TextAnchor.MiddleLeft, Bold);
            Label(c, name, "0.36 0", "0.73 1", "0 0", "-12 0", desc, 12, ColMuted, TextAnchor.MiddleLeft, Reg);
            ActionButton(c, name, "0.77 0.5", "1 0.5", "0 -16", "-12 16", available ? command : "", available ? buttonText : "НЕДОСТУПНО", available);
        }

        private void NativeCommandRow(CuiElementContainer c, int top, string command, string desc)
        {
            int bottom = top - 38;
            string name = Main + ".Cmd." + Math.Abs(top);
            Panel(c, Main, name, "0 1", "1 1", "22 " + bottom, "-22 " + top, ColCardDark);
            Label(c, name, "0 0", "0.34 1", "14 0", "0 0", command, 14, ColText, TextAnchor.MiddleLeft, Bold);
            Label(c, name, "0.34 0", "1 1", "0 0", "-14 0", desc, 12, ColMuted, TextAnchor.MiddleLeft, Reg);
        }

        private void NativeRow(CuiElementContainer c, string name, int top, int bottom, string title, string sub, string value, string valueColor, string command, string buttonText, bool available)
        {
            Panel(c, Main, name, "0 1", "1 1", "22 " + bottom, "-22 " + top, ColCard);
            Label(c, name, "0 0.48", "0.58 1", "14 0", "0 0", title, 14, available ? ColText : ColMuted, TextAnchor.MiddleLeft, Bold);
            Label(c, name, "0 0", "0.58 0.52", "14 0", "0 0", sub, 12, ColMuted, TextAnchor.MiddleLeft, Reg);
            if (!string.IsNullOrEmpty(value))
                Label(c, name, "0.58 0", "0.78 1", "0 0", "-8 0", value, 13, valueColor, TextAnchor.MiddleRight, Bold);
            ActionButton(c, name, "0.80 0.5", "1 0.5", "0 -16", "-12 16", available ? command : "", available ? buttonText : "НЕДОСТУПНО", available);
        }

        private void NativeInfoRow(CuiElementContainer c, string name, int top, int bottom, string title, string sub, string value, string valueColor)
        {
            Panel(c, Main, name, "0 1", "1 1", "22 " + bottom, "-22 " + top, ColCardDark);
            Label(c, name, "0 0.48", "0.60 1", "14 0", "0 0", title, 13, ColMuted, TextAnchor.MiddleLeft, Bold);
            Label(c, name, "0 0", "0.60 0.52", "14 0", "0 0", sub, 12, ColMuted, TextAnchor.MiddleLeft, Reg);
            Label(c, name, "0.60 0", "1 1", "0 0", "-14 0", value, 16, valueColor, TextAnchor.MiddleRight, Bold);
        }

        private void NativeStat(CuiElementContainer c, string parent, int index, string title, string value)
        {
            float w = 1f / 3f;
            float xMin = index * w;
            float xMax = (index + 1) * w;
            if (index > 0) Panel(c, parent, parent + ".V" + index, F(xMin) + " 0", F(xMin) + " 1", "0 10", "1 -10", ColLine);
            Label(c, parent, F(xMin) + " 0", F(xMax) + " 1", "14 0", "-14 0", title, 12, ColMuted, TextAnchor.MiddleLeft, Bold);
            Label(c, parent, F(xMin) + " 0", F(xMax) + " 1", "14 0", "-14 0", value, 15, ColText, TextAnchor.MiddleRight, Bold);
        }

        private void HomeCard(CuiElementContainer c, string name, int left, int right, int top, int bottom, string title, string value, string sub, string accent, bool available, string command, string button)
        {
            Panel(c, Main, name, "0 1", "0 1", left + " " + bottom, right + " " + top, ColCard);
            Panel(c, name, name + ".Accent", "0 0", "0 1", "0 0", "5 0", available ? accent : ColMuted);

            Label(c, name, "0 1", "1 1", "18 -27", "-156 -7", Safe(title, 34), 10, ColMuted);
            Label(c, name, "0 1", "1 1", "18 -61", "-156 -29", SafeRich(value, 28), 20, available ? accent : ColMuted);
            Label(c, name, "0 0", "1 0", "18 12", "-156 34", Safe(sub, 52), 10, ColMuted, TextAnchor.MiddleLeft, Reg);
            ActionButton(c, name, "1 0.5", "1 0.5", "-140 -19", "-14 19", available ? command : "", available ? button : "НЕДОСТУПНО", available);
        }

        private void ActionCard(CuiElementContainer c, string name, int left, int right, int top, int bottom, string title, string desc, string command, string button, bool available, string accent)
        {
            Panel(c, Main, name, "0 1", "0 1", left + " " + bottom, right + " " + top, ColCard);
            Panel(c, name, name + ".Accent", "0 0", "0 1", "0 0", "5 0", available ? accent : ColMuted);

            Label(c, name, "0 1", "1 1", "16 -31", "-16 -8", Safe(title, 28), 14, available ? ColText : ColMuted);
            Label(c, name, "0 1", "1 1", "16 -65", "-16 -35", Safe(desc, 52), 10, ColMuted, TextAnchor.MiddleLeft, Reg);

            ActionButton(c, name, "0 0", "1 0", "16 12", "-16 44", available ? command : "", available ? button : "НЕДОСТУПНО", available);
        }

        private void Feature(CuiElementContainer c, int col, int row, string title, string desc, string pluginName)
        {
            FeatureVisualCard(c, col, row, title, desc, "gears", pluginName);
        }

        private void CommandGroup(CuiElementContainer c, string name, int left, int right, int top, int bottom, string title, string text)
        {
            Panel(c, Main, name, "0 1", "0 1", left + " " + bottom, right + " " + top, ColCardDark);
            Label(c, name, "0 1", "1 1", "16 -30", "-16 -8", title, 13, ColText);
            Label(c, name, "0 0", "1 1", "16 12", "-16 -36", text, 12, ColMuted, TextAnchor.UpperLeft, Reg);
        }

        private void DrawNavItem(CuiElementContainer c, MenuState state, string key, string title, string iconShortname, int index)
        {

        }

        private void MiniStat(CuiElementContainer c, string parent, int index, string title, string value, string accent)
        {
            float h = 1f / 3f;
            float yMin = 1f - (index + 1) * h;
            float yMax = 1f - index * h;
            string amin = "0 " + F(yMin);
            string amax = "1 " + F(yMax);
            if (index > 0) Panel(c, parent, parent + ".Sep." + index, "0 " + F(yMax), "1 " + F(yMax), "10 -1", "-10 0", ColLine);
            Label(c, parent, amin, amax, "10 0", "-10 0", title, 12, ColMuted, TextAnchor.MiddleLeft, Bold);
            Label(c, parent, amin, amax, "10 0", "-10 0", value, 11, accent, TextAnchor.MiddleRight, Bold);
        }

        private void HomeVisualCard(CuiElementContainer c, string name, int left, int right, int top, int bottom,
            string title, string subtitle, string iconShortname, string accent, bool available, string command, string buttonText)
        {
            Panel(c, Main, name, "0 1", "0 1", left + " " + bottom, right + " " + top, ColCard);
            Panel(c, name, name + ".Top", "0 1", "1 1", "0 -2", "0 0", available ? accent : ColMuted);
            Label(c, name, "0 1", "1 1", "18 -38", "-18 -10", title, 15, available ? ColText : ColMuted, TextAnchor.MiddleLeft, Bold);
            Label(c, name, "0 1", "1 1", "18 -64", "-18 -40", Safe(subtitle, 46), 9, ColMuted, TextAnchor.MiddleLeft, Reg);
            ActionButton(c, name, "0 0", "0 0", "18 14", "130 46", available ? command : "", available ? buttonText : "НЕДОСТУПНО", available);
        }

        private void ServiceVisualCard(CuiElementContainer c, int col, int row, string title, string desc, string iconShortname,
            string accent, string command, string buttonText, bool available)
        {
            int[] xs = { 20, 380, 740, 1100 };
            int top = -78 - row * 154;
            int bottom = top - 142;
            string name = Main + ".Service." + col + "." + row;
            Panel(c, Main, name, "0 1", "0 1", xs[col] + " " + bottom, (xs[col + 1] - 20) + " " + top, ColCard);
            Panel(c, name, name + ".Accent", "0 1", "1 1", "0 -2", "0 0", available ? accent : ColMuted);
            Label(c, name, "0 1", "1 1", "16 -36", "-16 -10", title, 15, available ? ColText : ColMuted, TextAnchor.MiddleLeft, Bold);
            Label(c, name, "0 1", "1 1", "16 -62", "-16 -36", Safe(desc, 48), 9, ColMuted, TextAnchor.MiddleLeft, Reg);
            ActionButton(c, name, "0 0", "1 0", "16 12", "-16 43", available ? command : "", available ? buttonText : "НЕДОСТУПНО", available);
        }

        private void FeatureVisualCard(CuiElementContainer c, int col, int row, string title, string desc, string iconShortname, string pluginName)
        {
            int[] xs = { 20, 290, 560, 830, 1100 };
            int top = -82 - row * 154;
            int bottom = top - 140;
            string name = Main + ".FeaturePremium." + col + "." + row;
            bool loaded = PluginLoaded(pluginName);
            Panel(c, Main, name, "0 1", "0 1", xs[col] + " " + bottom, (xs[col + 1] - 12) + " " + top, ColCardDark);
            Panel(c, name, name + ".Top", "0 1", "1 1", "0 -2", "0 0", loaded ? ColGreenBtn : ColMuted);
            Label(c, name, "0 1", "1 1", "14 -34", "-14 -10", title, 10, loaded ? ColText : ColMuted, TextAnchor.MiddleLeft, Bold);
            Label(c, name, "0 1", "1 1", "14 -58", "-14 -34", desc, 8, ColMuted, TextAnchor.MiddleLeft, Reg);
            Panel(c, name, name + ".Status", "0 0", "1 0", "14 12", "-14 38", ColInput);
            Panel(c, name + ".Status", name + ".Status.Dot", "0 0.5", "0 0.5", "10 -3", "16 3", loaded ? ColGreenBtn : ColRedBtn);
            Label(c, name + ".Status", "0 0", "1 1", "24 0", "-8 0", loaded ? "АКТИВНО" : "ВЫКЛЮЧЕНО", 8, loaded ? ColText : ColMuted, TextAnchor.MiddleLeft, Bold);
        }

        private void CommandVisualCard(CuiElementContainer c, int col, int row, string title, string iconShortname, string body)
        {
            int left = col == 0 ? 20 : 570;
            int right = col == 0 ? 550 : 1100;
            int top = -82 - row * 238;
            int bottom = top - 224;
            string name = Main + ".Command." + col + "." + row;
            Panel(c, Main, name, "0 1", "0 1", left + " " + bottom, right + " " + top, ColCard);
            Panel(c, name, name + ".Top", "0 1", "1 1", "0 -2", "0 0", ColPremium);
            Label(c, name, "0 1", "1 1", "18 -38", "-18 -10", title, 14, ColText, TextAnchor.MiddleLeft, Bold);
            Label(c, name, "0 0", "1 1", "18 16", "-18 -50", body, 11, ColMuted, TextAnchor.UpperLeft, Reg);
        }

        private void AdminVisualCard(CuiElementContainer c, int col, int row, string title, string desc, string iconShortname,
            string accent, string command, string buttonText, bool available)
        {
            int[] xs = { 20, 380, 740, 1100 };
            int top = -82 - row * 226;
            int bottom = top - 210;
            string name = Main + ".AdminPremium." + col + "." + row;
            Panel(c, Main, name, "0 1", "0 1", xs[col] + " " + bottom, (xs[col + 1] - 20) + " " + top, ColCard);
            Panel(c, name, name + ".Accent", "0 1", "1 1", "0 -2", "0 0", available ? accent : ColMuted);
            Label(c, name, "0 1", "1 1", "16 -38", "-16 -10", title, 15, available ? ColText : ColMuted, TextAnchor.MiddleLeft, Bold);
            Label(c, name, "0 1", "1 1", "16 -72", "-16 -40", Safe(desc, 52), 11, ColMuted, TextAnchor.UpperLeft, Reg);
            Panel(c, name, name + ".Status", "0 0", "1 0", "16 56", "-16 84", ColInput);
            Label(c, name + ".Status", "0 0", "1 1", "10 0", "-10 0", available ? "ДОСТУПНО" : "НЕДОСТУПНО", 8, available ? ColText : ColMuted, TextAnchor.MiddleLeft, Bold);
            ActionButton(c, name, "0 0", "1 0", "16 14", "-16 48", available ? command : "", available ? buttonText : "НЕТ ДОСТУПА", available);
        }

        private void Empty(CuiElementContainer c, string text)
        {
            Panel(c, Main, Main + ".Empty", "0.15 0.36", "0.85 0.68", "0 0", "0 0", ColCardDark);
            Label(c, Main + ".Empty", "0 0", "1 1", "24 0", "-24 0", text, 17, ColMuted, TextAnchor.MiddleCenter, Reg);
        }

        private void Empty(CuiElementContainer c, string title, string description)
        {
            string name = Main + ".Empty";
            Panel(c, Main, name, "0.15 0.36", "0.85 0.68", "0 0", "0 0", ColCardDark);
            Label(c, name, "0 0.50", "1 1", "24 0", "-24 -8", title ?? "", 18, ColText, TextAnchor.LowerCenter, Bold);
            Label(c, name, "0 0", "1 0.50", "24 8", "-24 0", description ?? "", 14, ColSubText, TextAnchor.UpperCenter, Reg);
        }

        private void ActionButton(CuiElementContainer c, string parent, string amin, string amax, string omin, string omax, string command, string text, bool enabled)
        {
            SecondaryActionButton(c, parent, amin, amax, omin, omax, command, text, enabled);
        }

        private void SecondaryActionButton(CuiElementContainer c, string parent, string amin, string amax, string omin, string omax, string command, string text, bool enabled)
        {
            bool external = enabled && !string.IsNullOrEmpty(command) &&
                (command.StartsWith("chat.say ", StringComparison.OrdinalIgnoreCase) ||
                 command.Equals("zecoloot.open", StringComparison.OrdinalIgnoreCase) ||
                 command.Equals("adminEsp_mainmenu", StringComparison.OrdinalIgnoreCase));
            string close = external ? Root : null;
            Button(c, parent, amin, amax, omin, omax, command, text,
                enabled ? ColSecondaryBtn : ColSecondaryBtnDisabled,
                12, enabled ? ColSecondaryText : ColMuted, close);
        }

        private void DangerActionButton(CuiElementContainer c, string parent, string amin, string amax, string omin, string omax, string command, string text, bool enabled)
        {
            bool external = enabled && !string.IsNullOrEmpty(command) && command.StartsWith("chat.say ", StringComparison.OrdinalIgnoreCase);
            Button(c, parent, amin, amax, omin, omax, command, text,
                enabled ? ColRedStrong : ColSecondaryBtnDisabled,
                12, enabled ? "#FFFFFF" : ColMuted, external ? Root : null);
        }

        private void HomeStat(CuiElementContainer c, string parent, int index, string title, string value)
        {
            float x1 = index / 3f;
            float x2 = (index + 1) / 3f;
            if (index > 0)
                Panel(c, parent, parent + ".Sep." + index,
                    F(x1) + " 0", F(x1) + " 1", "0 10", "1 -10", "1 1 1 0.07");

            Label(c, parent, F(x1) + " 0", F(x2) + " 1", "16 0", "-16 0",
                title + "   <size=16><color=#CEC5BB>" + value + "</color></size>",
                11, ColMuted, TextAnchor.MiddleCenter, Bold);
        }

        private void HomeActionRow(CuiElementContainer c, string name, int top,
            string title, string desc, string command, string buttonText, bool available, bool danger)
        {
            int bottom = top - 48;
            Panel(c, Main, name, "0 1", "1 1", "22 " + bottom, "-22 " + top, ColCardDark);
            Panel(c, name, name + ".Line", "0 0", "1 0", "0 0", "0 1", "1 1 1 0.06");

            Label(c, name, "0 0", "0.24 1", "14 0", "0 0",
                title, 15, available ? ColText : ColMuted, TextAnchor.MiddleLeft, Bold);
            Label(c, name, "0.24 0", "0.81 1", "0 0", "-12 0",
                Safe(desc, 70), 11, ColSubText, TextAnchor.MiddleLeft, Reg);

            if (danger)
                DangerActionButton(c, name, "0.835 0.5", "1 0.5", "0 -15", "-12 15",
                    available ? command : "", available ? buttonText : "НЕДОСТУПНО", available);
            else
                SecondaryActionButton(c, name, "0.835 0.5", "1 0.5", "0 -15", "-12 15",
                    available ? command : "", available ? buttonText : "НЕДОСТУПНО", available);
        }

        private void CompactStat(CuiElementContainer c, string parent, int index, string title, string value)
        {
            float yTop = 0.70f - index * 0.22f;
            float yBottom = yTop - 0.18f;

            if (index > 0)
                Panel(c, parent, parent + ".Sep." + index, "0 " + F(yTop + 0.02f), "1 " + F(yTop + 0.02f), "16 0", "-16 1", "1 1 1 0.06");

            Label(c, parent, "0 " + F(yBottom), "0.58 " + F(yTop), "16 0", "0 0",
                title, 11, ColMuted, TextAnchor.MiddleLeft, Bold);
            Label(c, parent, "0.58 " + F(yBottom), "1 " + F(yTop), "0 0", "-16 0",
                value, 14, ColText, TextAnchor.MiddleRight, Bold);
        }

        private void QuickCard(CuiElementContainer c, string name, int left, int right, int top, int bottom,
            string title, string description, string command, string buttonText, bool available, bool danger)
        {
            Panel(c, Main, name, "0 1", "0 1", left + " " + bottom, right + " " + top, ColCard);
            Panel(c, name, name + ".Accent", "0 0", "0 1", "0 0", "3 0", available ? (danger ? ColRedText : ColText) : ColMuted);

            Label(c, name, "0 1", "1 1", "16 -30", "-156 -8",
                title, 15, available ? ColText : ColMuted, TextAnchor.MiddleLeft, Bold);
            Label(c, name, "0 0", "1 1", "16 12", "-156 -34",
                Safe(description, 58), 12, ColSubText, TextAnchor.MiddleLeft, Reg);

            if (danger)
                DangerActionButton(c, name, "1 0.5", "1 0.5", "-140 -17", "-14 17",
                    available ? command : "", available ? buttonText : "НЕДОСТУПНО", available);
            else
                SecondaryActionButton(c, name, "1 0.5", "1 0.5", "-140 -17", "-14 17",
                    available ? command : "", available ? buttonText : "НЕДОСТУПНО", available);
        }

        private void SummaryCard(CuiElementContainer c, string name, int left, int right, int top, int bottom,
            string caption, string value, string subtitle, string accent,
            string command, string buttonText, bool available)
        {
            Panel(c, Main, name, "0 1", "0 1", left + " " + bottom, right + " " + top, ColCard);
            Panel(c, name, name + ".Accent", "0 0", "0 1", "0 0", "4 0", accent);

            Label(c, name, "0 1", "1 1", "18 -28", "-18 -8", caption, 12, ColMuted, TextAnchor.MiddleLeft, Bold);
            Label(c, name, "0 1", "1 1", "18 -62", string.IsNullOrEmpty(command) ? "-18 -30" : "-150 -30",
                Safe(value, 34), 22, accent, TextAnchor.MiddleLeft, Bold);
            Label(c, name, "0 0", "1 0", "18 12", string.IsNullOrEmpty(command) ? "-18 34" : "-150 34",
                Safe(subtitle, 52), 12, ColSubText, TextAnchor.MiddleLeft, Reg);

            if (!string.IsNullOrEmpty(command))
                SecondaryActionButton(c, name, "1 0.5", "1 0.5", "-138 -17", "-16 17",
                    available ? command : "", available ? buttonText : "НЕДОСТУПНО", available);
        }

        private void GroupTitle(CuiElementContainer c, int left, int right, int y, string title)
        {
            int lineStart = left + Math.Min(250, 28 + (title == null ? 0 : title.Length * 7));
            Label(c, Main, "0 1", "0 1", left + " " + (y - 24), lineStart + " " + y,
                title, 12, ColMuted, TextAnchor.MiddleLeft, Bold);
            if (lineStart + 12 < right)
                Panel(c, Main, Main + ".GroupLine." + left + "." + Math.Abs(y),
                    "0 1", "0 1", (lineStart + 10) + " " + (y - 13), right + " " + (y - 12),
                    "1 1 1 0.06");
        }

        private void CompactActionCard(CuiElementContainer c, string name, int left, int right, int top, int bottom,
            string title, string desc, string command, string buttonText, bool available)
        {
            Panel(c, Main, name, "0 1", "0 1", left + " " + bottom, right + " " + top, ColCardDark);
            Panel(c, name, name + ".Line", "0 0", "1 0", "0 0", "0 1", "1 1 1 0.06");

            Label(c, name, "0 0.50", "1 1", "14 0", "-132 0",
                title, 15, available ? ColText : ColMuted, TextAnchor.MiddleLeft, Bold);
            Label(c, name, "0 0", "1 0.54", "14 0", "-132 0",
                Safe(desc, 52), 11, ColSubText, TextAnchor.MiddleLeft, Reg);

            SecondaryActionButton(c, name, "1 0.5", "1 0.5", "-120 -15", "-12 15",
                available ? command : "", available ? buttonText : "НЕДОСТУПНО", available);
        }

        private void CompactCommand(CuiElementContainer c, string name, int left, int right, int top, int bottom,
            string command, string desc)
        {
            Panel(c, Main, name, "0 1", "0 1", left + " " + bottom, right + " " + top, ColCardDark);
            Panel(c, name, name + ".Line", "0 0", "1 0", "0 0", "0 1", "1 1 1 0.06");
            Label(c, name, "0 0", "0.34 1", "14 0", "0 0",
                command, 13, ColText, TextAnchor.MiddleLeft, Bold);
            Label(c, name, "0.34 0", "1 1", "4 0", "-14 0",
                desc, 11, ColSubText, TextAnchor.MiddleLeft, Reg);
        }

        private void RawImage(CuiElementContainer c, string parent, string png)
        {
            if (string.IsNullOrEmpty(png)) return;
            c.Add(new CuiElement
            {
                Parent = parent,
                Components =
                {
                    new CuiRawImageComponent { Png = png, Color = "1 1 1 1" },
                    new CuiRectTransformComponent { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMin = "0 0", OffsetMax = "0 0" }
                }
            });
        }

        private void AddVerticalScroll(CuiElementContainer c, string parent, string name, string offsetMin, string offsetMax, float contentHeight)
        {
            c.Add(new CuiElement
            {
                Name = name,
                Parent = parent,
                Components =
                {
                    new CuiImageComponent { Color = "0 0 0 0" },
                    new CuiScrollViewComponent
                    {
                        Horizontal = false,
                        Vertical = true,
                        MovementType = UnityEngine.UI.ScrollRect.MovementType.Clamped,
                        Inertia = true,
                        DecelerationRate = 0.2f,
                        ScrollSensitivity = 28f,
                        ContentTransform = new CuiRectTransform
                        {
                            AnchorMin = "0 1",
                            AnchorMax = "1 1",
                            OffsetMin = "0 -" + F(contentHeight),
                            OffsetMax = "0 0"
                        }
                    },
                    new CuiRectTransformComponent
                    {
                        AnchorMin = "0 0",
                        AnchorMax = "1 1",
                        OffsetMin = offsetMin,
                        OffsetMax = offsetMax
                    }
                }
            });
        }

        private Plugin GetAccountSystemPlugin()
        {
            return GetPlugin(ref AccountSystem, "AccountSystem");
        }

        private Dictionary<string, object> GetAccountSummary(Plugin plugin, BasePlayer player)
        {
            if (plugin == null || player == null) return null;
            return plugin.Call("API_GetPlayerSummary", player) as Dictionary<string, object>
                ?? plugin.Call("API_GetSummary", player.userID) as Dictionary<string, object>;
        }

        private void AddSteamAvatar(CuiElementContainer c, string parent, string name, ulong steamId,
            string amin, string amax, string omin, string omax)
        {
            Panel(c, parent, name, amin, amax, omin, omax, ColCardDark);
            c.Add(new CuiElement
            {
                Parent = name,
                Components =
                {
                    new CuiRawImageComponent { SteamId = steamId.ToString(CultureInfo.InvariantCulture), Color = "1 1 1 1" },
                    new CuiRectTransformComponent { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMin = "3 3", OffsetMax = "-3 -3" }
                }
            });
        }

        private static int GetInt(Dictionary<string, object> data, string key)
        {
            object value;
            return data != null && data.TryGetValue(key, out value) && value != null ? ToInt(value) : 0;
        }

        private static long GetLong(Dictionary<string, object> data, string key)
        {
            object value;
            if (data == null || !data.TryGetValue(key, out value) || value == null) return 0L;
            try { return Convert.ToInt64(value, CultureInfo.InvariantCulture); }
            catch { return 0L; }
        }

        private static double GetDouble(Dictionary<string, object> data, string key)
        {
            object value;
            if (data == null || !data.TryGetValue(key, out value) || value == null) return 0d;
            try { return Convert.ToDouble(value, CultureInfo.InvariantCulture); }
            catch { return 0d; }
        }

        private static string FormatNumber(long value)
        {
            return value.ToString("N0", CultureInfo.InvariantCulture).Replace(",", " ");
        }

        private static string FormatLifetimeTime(long seconds)
        {
            if (seconds <= 0) return "0м";
            long days = seconds / 86400;
            long hours = (seconds % 86400) / 3600;
            long minutes = (seconds % 3600) / 60;
            if (days > 0) return days + "д " + hours + "ч";
            if (hours > 0) return hours + "ч " + minutes + "м";
            return Math.Max(1, minutes) + "м";
        }

        private void AddAtlanticBackdrop(CuiElementContainer c, string parent, string name)
        {
            c.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = "0 0", AnchorMax = "1 1" },
                Image = { Color = ColOverlay }
            }, parent, name, name);
            c.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = "0 0", AnchorMax = "1 1" },
                Image = { Color = "0 0 0 0.23", Material = MatBlur }
            }, name, name + ".Blur");
            c.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = "0 0", AnchorMax = "1 1" },
                Image = { Sprite = SpriteRadial, Color = Col("#36363699") }
            }, name, name + ".Radial");
        }

        private static bool AtlanticBlur(string color)
        {
            return color == ColHeader || color == ColMain || color == ColCard || color == ColNav ||
                   color == ColPremiumSoft || color == ColLine;
        }

        private void Panel(CuiElementContainer c, string parent, string name, string amin, string amax, string omin, string omax, string color)
        {
            var image = new CuiImageComponent { Color = Col(color) };
            if (AtlanticBlur(color)) image.Material = MatBlur;
            c.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = amin, AnchorMax = amax, OffsetMin = omin, OffsetMax = omax },
                Image = image
            }, parent, name, name);
        }

        private void Label(CuiElementContainer c, string parent, string amin, string amax, string omin, string omax, string text, int size = 13, string color = ColText, TextAnchor align = TextAnchor.MiddleLeft, string font = Bold)
        {
            c.Add(new CuiLabel
            {
                RectTransform = { AnchorMin = amin, AnchorMax = amax, OffsetMin = omin, OffsetMax = omax },
                Text = { Text = text, Font = font, FontSize = size, Align = align, Color = Col(color) }
            }, parent);
        }

        private void Button(CuiElementContainer c, string parent, string amin, string amax, string omin, string omax, string command, string text, string color, int size = 13, string textColor = ColText, string close = null)
        {
            string renderColor = color;
            string renderText = textColor;
            bool hasText = !string.IsNullOrEmpty(text);
            bool hasCommand = !string.IsNullOrEmpty(command);
            if (hasText && (color == ColInput || color == ColHeader))
            {
                renderColor = hasCommand ? ColSecondaryBtn : ColSecondaryBtnDisabled;
                if (textColor == ColText) renderText = hasCommand ? ColSecondaryText : ColMuted;
            }
            if (hasText && text != "×" && color == ColRedBtn)
            {
                renderColor = hasCommand ? ColRedStrong : ColSecondaryBtnDisabled;
                if (textColor == ColRedText) renderText = hasCommand ? "#FFFFFF" : ColMuted;
            }
            var button = new CuiButton
            {
                RectTransform = { AnchorMin = amin, AnchorMax = amax, OffsetMin = omin, OffsetMax = omax },
                Button = { Color = Col(renderColor), Command = command ?? string.Empty },
                Text = { Text = text, Font = Bold, FontSize = size, Align = TextAnchor.MiddleCenter, Color = Col(renderText) }
            };
            if (renderColor != "0 0 0 0") button.Button.Material = MatBlur;
            if (!string.IsNullOrEmpty(close)) button.Button.Close = close;
            c.Add(button, parent);
        }

        private bool HasStaffAccess(BasePlayer player)
        {
            if (player == null) return false;
            if (player.IsAdmin || player?.net?.connection?.authLevel >= 2) return true;
            if (AdminMenu == null || !AdminMenu.IsLoaded) return false;
            object prefix = AdminMenu.Call("API_GetRolePrefix", player);
            return prefix != null && !string.IsNullOrEmpty(prefix.ToString());
        }

        private string CurrentPrefix(BasePlayer player)
        {
            if (player == null || ChatPrefixes == null || !ChatPrefixes.IsLoaded) return string.Empty;
            object result = ChatPrefixes.Call("API_GetPlayerPrefix", player);
            return result == null ? string.Empty : Safe(result.ToString(), 50);
        }

        private Plugin GetPlugin(ref Plugin cached, string name)
        {
            if (cached != null && cached.IsLoaded) return cached;
            cached = Interface.Oxide.RootPluginManager.GetPlugin(name);
            return cached != null && cached.IsLoaded ? cached : null;
        }

        private bool PluginLoaded(string name)
        {
            Plugin plugin = Interface.Oxide.RootPluginManager.GetPlugin(name);
            return plugin != null && plugin.IsLoaded;
        }

        private int CountOnline()
        {
            int count = 0;
            foreach (BasePlayer p in BasePlayer.activePlayerList)
                if (ValidPlayer(p)) count++;

            Plugin autoMessages = GetPlugin(ref AutoMessages, "AutoMessages");
            if (autoMessages != null)
            {
                object result = autoMessages.Call("API_GetVirtualOnlineCount");
                int virtualCount;
                if (result != null && int.TryParse(result.ToString(), out virtualCount) && virtualCount > 0)
                    count += virtualCount;
            }

            return count;
        }

        private int CountSleepers()
        {
            int count = 0;
            foreach (BasePlayer p in BasePlayer.sleepingPlayerList)
                if (p != null && !p.IsDestroyed && !p.IsNpc) count++;
            return count;
        }

        private string ServerTime()
        {
            if (TOD_Sky.Instance == null) return "--:--";
            float raw = TOD_Sky.Instance.Cycle.Hour;
            int hour = Mathf.FloorToInt(raw) % 24;
            if (hour < 0) hour += 24;
            int minute = Mathf.Clamp(Mathf.FloorToInt((raw - Mathf.Floor(raw)) * 60f), 0, 59);
            return hour.ToString("00") + ":" + minute.ToString("00");
        }

        private static bool ValidPlayer(BasePlayer player)
        {
            return player != null && !player.IsDestroyed && player.IsConnected && !player.IsNpc;
        }

        private static int ToInt(object value)
        {
            if (value == null) return 0;
            try { return Convert.ToInt32(value, CultureInfo.InvariantCulture); }
            catch { int parsed; return int.TryParse(value.ToString(), out parsed) ? parsed : 0; }
        }

        private static bool ToBool(object value)
        {
            if (value == null) return false;
            try { return Convert.ToBoolean(value, CultureInfo.InvariantCulture); }
            catch { bool parsed; return bool.TryParse(value.ToString(), out parsed) && parsed; }
        }

        private static string Safe(string text, int max)
        {
            if (string.IsNullOrEmpty(text)) return string.Empty;
            text = text.Replace("\n", " ").Replace("\r", " ");
            return text.Length <= max ? text : text.Substring(0, Math.Max(0, max - 1)) + "…";
        }

        private static string SafeRich(string text, int maxVisible)
        {
            if (string.IsNullOrEmpty(text)) return string.Empty;
            if (text.StartsWith("<color=", StringComparison.OrdinalIgnoreCase))
            {
                int openEnd = text.IndexOf('>');
                int close = text.LastIndexOf("</color>", StringComparison.OrdinalIgnoreCase);
                if (openEnd > 0 && close > openEnd)
                {
                    string inner = text.Substring(openEnd + 1, close - openEnd - 1);
                    return text.Substring(0, openEnd + 1) + Safe(inner, maxVisible) + "</color>";
                }
            }
            return Safe(text, maxVisible);
        }

        private static string DivisionColor(string division)
        {
            switch ((division ?? string.Empty).Trim().ToLowerInvariant())
            {
                case "bronze": return "#C7834C";
                case "silver": return "#D2D8DE";
                case "gold": return "#FFD34D";
                case "platinum": return "#65D8C8";
                case "diamond": return "#58C8FF";
                case "master": return "#FF69C7";
                case "legend": return "#D94CFF";
                default: return ColGreenTxt;
            }
        }

        private static bool IsLegend(string division)
        {
            return string.Equals((division ?? string.Empty).Trim(), "Legend", StringComparison.OrdinalIgnoreCase);
        }

        private static KeyValuePair<string, string> Pair(string key, string value)
        {
            return new KeyValuePair<string, string>(key, value);
        }

        private static string F(float value)
        {
            return value.ToString("0.###", CultureInfo.InvariantCulture);
        }

        private static string Col(string hex)
        {
            if (string.IsNullOrEmpty(hex)) return "1 1 1 1";
            if (!hex.StartsWith("#", StringComparison.Ordinal)) return hex;
            Color color;
            return ColorUtility.TryParseHtmlString(hex, out color)
                ? F(color.r) + " " + F(color.g) + " " + F(color.b) + " " + F(color.a)
                : "1 1 1 1";
        }
    }
}
