# PATCHES

Изменения в сторонних плагинах ICE RUST, сделанные ради переноса профиля
игрока в AccountSystem.

## ServerMenu 2.8.9 → 2.9.0

Профиль игрока переехал в AccountSystem: там лежат данные, там же теперь и
отрисовка. ServerMenu остаётся хостом меню и больше не знает, как выглядит
профиль.

Обе части правки — снятие `"account"` со списка встроенных вкладок и удаление
`DrawAccountProfile` — уехали **одним коммитом**. По отдельности их применять
нельзя: `API_RegisterTab` отказывает, пока ключ числится встроенным
(`IsBuiltInTab`), поэтому между двумя коммитами профиль остался бы
недоступным вовсе.

### Что изменено

| Место | Было | Стало |
|---|---|---|
| `IsBuiltInTab` | `home`, `account`, `top`, `services`, `commands` | `account` убран — вкладку регистрирует AccountSystem |
| `Draw` | две ветки рисовали `DrawAccountProfile` | ветка `top` рисует только рейтинг |
| `DrawAccountProfile` | 201 строка отрисовки профиля | удалён |
| `MenuState.SelectedAccountId` | хранил, чей профиль открыт внутри вкладки «Топ» | удалён, профиль живёт в своей вкладке |
| `servermenu.ui topplayer <id>` | переключал «Топ» в режим профиля | `OpenIntegrated(player, "account", <id>)` — SteamID64 уходит payload'ом |
| `servermenu.ui topback` | сбрасывал `SelectedAccountId` | `Open(player, "top")` |
| `servermenu.ui accountreset <id>` | после сброса перерисовывал профиль внутри «Топа» | открывает вкладку профиля того же игрока |
| Подсветка вкладки | `state.Tab == "account"` всегда подсвечивал «ГЛАВНАЯ» | то же, но только пока вкладки профиля нет в полосе |

### Удалён мёртвый код

Эти методы вызывались только из `DrawAccountProfile` и после его удаления
стали недостижимы:

`DrawResourceLifetimeCardInside` (32) · `ResourceLabel` (17) ·
`AccountStatRow` (15) · `TopPositionColor` (8) · `GetLongDictionary` (21) ·
`Rich` (5) · `GetAccountProfile` обе перегрузки (22)

Итого 2383 → 2031 строки.

### Что намеренно не тронуто

* **Карточка уровня на Главной.** Работает через `GetAccountSummary` →
  `API_GetPlayerSummary` с откатом на `API_GetSummary`. Здесь стоит отметить
  расхождение с постановкой: `API_GetPlayerProfile` Главная никогда не
  вызывала — этот вызов жил только внутри `GetAccountProfile`, то есть в
  удалённом пути профиля. Карточка не затронута.
* **Вкладка «Топ»** и её постраничный вывод.
* **Команда `accountreset`** — осталась в ServerMenu, её по-прежнему
  исполняет он, вызывая `API_CanAdminReset` и `API_ResetAccountStats`.
  Переехала только кнопка: теперь её рисует AccountSystem в шапке чужого
  профиля, иначе с удалением `DrawAccountProfile` админский сброс пропал бы
  из интерфейса.
* Кнопка «НАЗАД К ТОПУ» по-прежнему шлёт `servermenu.ui topback`.

### Проверка

`tools/plugincheck` поднимает оба плагина и связывает их через реестр, как
это делает Oxide: регистрация вкладки, её появление в полосе `/menu`,
передача SteamID64 payload'ом, отрисовка чужого и своего профиля, возврат в
«Топ» с очисткой состояния и сохранность карточки уровня на Главной.

### Полный дифф

```diff
--- /tmp/ServerMenu.before.cs	2026-09-19 19:05:09.178729257 +0000
+++ ServerMenu.cs	2026-09-19 19:06:37.301908140 +0000
@@ -8,7 +8,7 @@
 
 namespace Oxide.Plugins
 {
-    [Info("ServerMenu", "ICE RUST", "2.8.9")]
+    [Info("ServerMenu", "ICE RUST", "2.9.0")]
     [Description("Unified ICE RUST UI host: main menu, AccountSystem and Kits in one interface.")]
     public class ServerMenu : RustPlugin
     {
@@ -57,7 +57,6 @@
         private sealed class MenuState
         {
             public string Tab = "home";
-            public ulong SelectedAccountId;
             public int TopPage;
         }
 
@@ -120,13 +119,8 @@
                 if (!ulong.TryParse(arg.Args[1].ToString(), out targetId) || targetId == 0UL)
                     return;
 
-                MenuState current;
-                if (!states.TryGetValue(player.userID, out current) || current == null)
-                    states[player.userID] = current = new MenuState();
-
-                current.Tab = "top";
-                current.SelectedAccountId = targetId;
-                Draw(player, current);
+                // SteamID64 уходит во вкладку профиля как payload.
+                OpenIntegrated(player, "account", targetId.ToString(CultureInfo.InvariantCulture));
                 return;
             }
 
@@ -136,9 +130,7 @@
                 if (!states.TryGetValue(player.userID, out current) || current == null)
                     states[player.userID] = current = new MenuState();
 
-                current.Tab = "top";
-                current.SelectedAccountId = 0UL;
-                Draw(player, current);
+                Open(player, "top");
                 return;
             }
 
@@ -157,13 +149,7 @@
                 object reset = account.Call("API_ResetAccountStats", player, targetId);
                 if (!(reset is bool) || !(bool)reset) return;
 
-                MenuState current;
-                if (!states.TryGetValue(player.userID, out current) || current == null)
-                    states[player.userID] = current = new MenuState();
-
-                current.Tab = "top";
-                current.SelectedAccountId = targetId;
-                Draw(player, current);
+                OpenIntegrated(player, "account", targetId.ToString(CultureInfo.InvariantCulture));
                 return;
             }
 
@@ -178,7 +164,6 @@
                     states[player.userID] = current = new MenuState();
 
                 current.Tab = "top";
-                current.SelectedAccountId = 0UL;
                 current.TopPage = Math.Max(0, page);
                 Draw(player, current);
                 return;
@@ -210,22 +195,10 @@
                 MenuState current;
                 if (states.TryGetValue(player.userID, out current) && current != null &&
                     string.Equals(current.Tab, tab, StringComparison.OrdinalIgnoreCase))
-                {
-                    if (string.Equals(tab, "top", StringComparison.OrdinalIgnoreCase) &&
-                        current.SelectedAccountId != 0UL)
-                    {
-                        current.SelectedAccountId = 0UL;
-                        Draw(player, current);
-                    }
                     return;
-                }
 
-                if (current != null)
-                {
-                    current.SelectedAccountId = 0UL;
-                    if (string.Equals(tab, "top", StringComparison.OrdinalIgnoreCase))
-                        current.TopPage = 0;
-                }
+                if (current != null && string.Equals(tab, "top", StringComparison.OrdinalIgnoreCase))
+                    current.TopPage = 0;
 
                 OpenIntegrated(player, tab, "");
             }
@@ -291,7 +264,9 @@
 
         private static bool IsBuiltInTab(string tab)
         {
-            return tab == "home" || tab == "account" || tab == "top" ||
+            // "account" сюда не входит: вкладку профиля регистрирует AccountSystem
+            // через API_RegisterTab, и он же её рисует.
+            return tab == "home" || tab == "top" ||
                    tab == "services" || tab == "commands";
         }
 
@@ -528,9 +503,6 @@
             if (changed)
                 HideTab(player, state.Tab);
 
-            if (changed || !string.Equals(next, "top", StringComparison.OrdinalIgnoreCase))
-                state.SelectedAccountId = 0UL;
-
             if (changed && string.Equals(next, "top", StringComparison.OrdinalIgnoreCase))
                 state.TopPage = 0;
 
@@ -642,14 +614,7 @@
                     else Empty(c, "ВКЛАДКА НЕДОСТУПНА", "Плагин " + active.Owner + " не загружен.");
                 }
             }
-            else if (state.Tab == "account") DrawAccountProfile(c, player);
-            else if (state.Tab == "top")
-            {
-                if (state.SelectedAccountId != 0UL)
-                    DrawAccountProfile(c, player, state.SelectedAccountId, true);
-                else
-                    DrawExpTop(c, player, state);
-            }
+            else if (state.Tab == "top") DrawExpTop(c, player, state);
             else if (state.Tab == "services") DrawServices(c, player);
             else if (state.Tab == "commands") DrawCommands(c, player);
             else DrawHome(c, player);
@@ -680,7 +645,11 @@
                 float start = (StripW - total) * 0.5f;
 
                 int i = index;
-                bool active = state.Tab == tabs[i].Key || (state.Tab == "account" && tabs[i].Key == "home");
+                // Пока AccountSystem не зарегистрировал свою вкладку, профиль
+                // не показан в полосе и подсвечивается "Главная" - как раньше.
+                bool active = state.Tab == tabs[i].Key ||
+                              (state.Tab == "account" && tabs[i].Key == "home" &&
+                               !extTabs.ContainsKey("account"));
                 float x1 = start + column * (width + StripGap);
                 float x2 = x1 + width;
                 float yTop = -row * StripRowH;
@@ -1059,207 +1028,6 @@
             }
         }
 
-        private void DrawAccountProfile(CuiElementContainer c, BasePlayer player)
-        {
-            DrawAccountProfile(c, player, player.userID, false);
-        }
-
-        private void DrawAccountProfile(CuiElementContainer c, BasePlayer viewer, ulong targetUserId, bool fromTop)
-        {
-            Plugin plugin = GetAccountSystemPlugin();
-            Dictionary<string, object> profile = GetAccountProfile(plugin, targetUserId);
-
-            string title = fromTop ? "ПРОФИЛЬ ИГРОКА" : "МОЙ ПРОФИЛЬ";
-
-            if (plugin == null)
-            {
-                Heading(c, title, "Постоянный аккаунт ICE RUST");
-                Empty(c, "ACCOUNTSYSTEM НЕ ЗАГРУЖЕН", "Плагин AccountSystem сейчас отсутствует на сервере.");
-                return;
-            }
-
-            if (profile == null)
-            {
-                Heading(c, title, "Постоянный аккаунт ICE RUST");
-                Empty(c, "ПРОФИЛЬ НЕ ПОЛУЧЕН", "AccountSystem не вернул данные выбранного аккаунта.");
-                return;
-            }
-
-            object profileValue;
-            ulong profileSteamId = targetUserId;
-            if (profile.TryGetValue("SteamId", out profileValue) && profileValue != null)
-                ulong.TryParse(profileValue.ToString(), out profileSteamId);
-
-            string profileName = profile.TryGetValue("Name", out profileValue) && profileValue != null
-                ? profileValue.ToString()
-                : profileSteamId.ToString(CultureInfo.InvariantCulture);
-
-            bool canResetStats = false;
-            if (fromTop)
-            {
-                object canResetRaw = plugin.Call("API_CanAdminReset", viewer);
-                canResetStats = canResetRaw is bool && (bool)canResetRaw;
-            }
-
-            Label(c, Main, "0 1", "0.60 1", "22 -42", "0 -8",
-                title, 24, ColText, TextAnchor.MiddleLeft, Bold);
-            Label(c, Main, "0 1", "0.74 1", "22 -66", "0 -40",
-                "Постоянная статистика аккаунта — не сбрасывается с вайпами",
-                11, ColSubText, TextAnchor.MiddleLeft, Reg);
-
-            SecondaryActionButton(c, Main, "1 1", "1 1", "-174 -58", "-22 -22",
-                fromTop ? "servermenu.ui topback" : "servermenu.ui tab home",
-                fromTop ? "НАЗАД К ТОПУ" : "НАЗАД", true);
-            Panel(c, Main, Main + ".AccountHeadingLine", "0 1", "1 1",
-                "22 -76", "-22 -75", "1 1 1 0.07");
-
-            int level = GetInt(profile, "Level");
-            long exp = GetLong(profile, "Exp");
-            long required = Math.Max(1, GetLong(profile, "RequiredExp"));
-            double progress = Math.Max(0d, Math.Min(1d, GetDouble(profile, "Progress")));
-            long playSeconds = GetLong(profile, "PlaySeconds");
-
-            string top = Main + ".AccountTop";
-            Panel(c, Main, top, "0 1", "1 1", "22 -176", "-22 -88", ColCard);
-            Panel(c, top, top + ".Accent", "0 0", "0 1", "0 0", "4 0", ColGreenBtn);
-
-            AddSteamAvatar(c, top, top + ".Avatar", profileSteamId,
-                "0 0.5", "0 0.5", "16 -32", "80 32");
-
-            string identityLine =
-                "<size=18><color=" + ColText + ">" + Safe(profileName, 30) + "</color></size>\n" +
-                "<size=10><color=" + ColSubText + ">SteamID  " +
-                profileSteamId.ToString(CultureInfo.InvariantCulture) + "</color></size>";
-            Label(c, top, "0 0", "0 1", "96 0", "356 0",
-                identityLine, 18, ColText, TextAnchor.MiddleLeft, Bold);
-
-            Panel(c, top, top + ".Sep1", "0 0", "0 1",
-                "372 12", "373 -12", "1 1 1 0.07");
-
-            string levelLine =
-                "<size=10><color=" + ColMuted + ">УРОВЕНЬ</color></size>\n" +
-                "<size=22><color=" + ColText + ">" + level + "</color></size>";
-            Label(c, top, "0 0", "0 1", "402 0", "498 0",
-                levelLine, 22, ColText, TextAnchor.MiddleLeft, Bold);
-
-            Panel(c, top, top + ".Sep2", "0 0", "0 1",
-                "526 16", "527 -16", "1 1 1 0.07");
-
-            string expLine =
-                "<size=10><color=" + ColMuted + ">EXP ДО СЛЕДУЮЩЕГО УРОВНЯ</color></size>\n" +
-                "<size=15><color=" + ColText + ">" +
-                FormatNumber(exp) + " / " + FormatNumber(required) + "</color></size>";
-            Label(c, top, "0 0", "0 1", "556 10", "812 16",
-                expLine, 15, ColText, TextAnchor.MiddleLeft, Bold);
-
-            string frame = top + ".ExpFrame";
-            Panel(c, top, frame, "0 0", "0 0",
-                "556 18", "812 32", "#B2A9A366");
-            Panel(c, frame, frame + ".Track", "0 0", "1 1",
-                "2 2", "-2 -2", "0 0 0 0.32");
-            if (progress > 0d)
-                Panel(c, frame, frame + ".Fill", "0 0", F((float)progress) + " 1",
-                    "2 2", "-2 -2", ColGreenBtn);
-
-            Panel(c, top, top + ".Sep3", "0 0", "0 1",
-                "840 12", "841 -12", "1 1 1 0.07");
-
-            string timeLine =
-                "<size=10><color=" + ColMuted + ">ВСЕГО ЧАСОВ НА СЕРВЕРЕ</color></size>\n" +
-                "<size=16><color=" + ColText + ">" + FormatLifetimeTime(playSeconds) + "</color></size>";
-
-            if (canResetStats)
-            {
-                Label(c, top, "0 0.43", "1 1", "870 0", "-18 -2",
-                    timeLine, 16, ColText, TextAnchor.MiddleRight, Bold);
-                Button(c, top, "0 0", "1 0", "870 10", "-18 38",
-                    "servermenu.ui accountreset " + profileSteamId.ToString(CultureInfo.InvariantCulture),
-                    "ОЧИСТИТЬ СТАТИСТИКУ", ColRedStrong, 10, "#FFFFFF");
-            }
-            else
-            {
-                Label(c, top, "0 0", "1 1", "870 0", "-18 0",
-                    timeLine, 16, ColText, TextAnchor.MiddleRight, Bold);
-            }
-
-            string resourcesPanel = Main + ".ResourcesPanel";
-            Panel(c, Main, resourcesPanel, "0 1", "1 1", "22 -328", "-22 -190", ColCard);
-            Label(c, resourcesPanel, "0 1", "1 1", "14 -28", "-14 -6",
-                "РЕСУРСЫ ЗА ВСЁ ВРЕМЯ", 11, ColMuted, TextAnchor.MiddleLeft, Bold);
-
-            Dictionary<string, long> resources = GetLongDictionary(profile, "Resources");
-            string[] resourceOrder =
-            {
-                "wood", "stones", "metal.ore", "sulfur.ore", "hq.metal.ore",
-                "cloth", "leather", "fat.animal", "bone.fragments"
-            };
-
-            float resourceGap = 5f;
-            float usable = 1048f;
-            float resourceWidth = (usable - resourceGap * (resourceOrder.Length - 1)) / resourceOrder.Length;
-
-            for (int i = 0; i < resourceOrder.Length; i++)
-            {
-                string shortname = resourceOrder[i];
-                long amount;
-                resources.TryGetValue(shortname, out amount);
-
-                float x1 = 14f + i * (resourceWidth + resourceGap);
-                float x2 = x1 + resourceWidth;
-                DrawResourceLifetimeCardInside(c, resourcesPanel, resourcesPanel + ".Res." + i,
-                    shortname, amount, x1, x2);
-            }
-
-            int leftX1 = 22, leftX2 = 548, rightX1 = 562, rightX2 = 1098;
-
-            Label(c, Main, "0 1", "0 1", leftX1 + " -356", leftX2 + " -334",
-                "БОЙ И ВЫЖИВАНИЕ", 11, ColMuted, TextAnchor.MiddleLeft, Bold);
-            Label(c, Main, "0 1", "0 1", rightX1 + " -356", rightX2 + " -334",
-                "АКТИВНОСТЬ АККАУНТА", 11, ColMuted, TextAnchor.MiddleLeft, Bold);
-
-            int y = -364;
-            AccountStatRow(c, "PlayerKills", leftX1, leftX2, y,
-                "Убийств игроков", FormatNumber(GetLong(profile, "PlayerKills")));
-            AccountStatRow(c, "NpcKills", leftX1, leftX2, y - 27,
-                "Убийств NPC", FormatNumber(GetLong(profile, "NpcKills")));
-            AccountStatRow(c, "AnimalKills", leftX1, leftX2, y - 54,
-                "Убийств животных", FormatNumber(GetLong(profile, "AnimalKills")));
-            AccountStatRow(c, "Deaths", leftX1, leftX2, y - 81,
-                "Смертей", FormatNumber(GetLong(profile, "Deaths")));
-            AccountStatRow(c, "Headshots", leftX1, leftX2, y - 108,
-                "Попаданий в голову", FormatNumber(GetLong(profile, "Headshots")));
-            AccountStatRow(c, "Shots", leftX1, leftX2, y - 135,
-                "Выстрелов", FormatNumber(GetLong(profile, "ShotsFired")));
-            AccountStatRow(c, "Damage", leftX1, leftX2, y - 162,
-                "Нанесено / получено урона",
-                FormatNumber((long)Math.Round(GetDouble(profile, "DamageDealt"))) + " / " +
-                FormatNumber((long)Math.Round(GetDouble(profile, "DamageReceived"))));
-
-            AccountStatRow(c, "Loot", rightX1, rightX2, y,
-                "Открыто мировых ящиков", FormatNumber(GetLong(profile, "LootContainers")));
-            AccountStatRow(c, "Craft", rightX1, rightX2, y - 27,
-                "Скрафчено предметов", FormatNumber(GetLong(profile, "CraftedItemsTotal")));
-            AccountStatRow(c, "Build", rightX1, rightX2, y - 54,
-                "Построено блоков / объектов",
-                FormatNumber(GetLong(profile, "BuildingPiecesPlaced")) + " / " +
-                FormatNumber(GetLong(profile, "DeployablesPlaced")));
-            AccountStatRow(c, "Upgrade", rightX1, rightX2, y - 81,
-                "Улучшено / уничтожено построек",
-                FormatNumber(GetLong(profile, "StructuresUpgraded")) + " / " +
-                FormatNumber(GetLong(profile, "StructuresDestroyed")));
-            AccountStatRow(c, "Explosives", rightX1, rightX2, y - 108,
-                "Взрывчатки / ракет использовано",
-                FormatNumber(GetLong(profile, "ExplosivesUsed")) + " / " +
-                FormatNumber(GetLong(profile, "RocketsFired")));
-            AccountStatRow(c, "Sessions", rightX1, rightX2, y - 135,
-                "Сессий / вайпов сыграно",
-                FormatNumber(GetLong(profile, "Sessions")) + " / " +
-                FormatNumber(GetLong(profile, "WipesPlayed")));
-            AccountStatRow(c, "Gather", rightX1, rightX2, y - 162,
-                "Всего добыто ресурсов",
-                FormatNumber(GetLong(profile, "GatheredTotal")));
-        }
-
         private void DrawKits(CuiElementContainer c, BasePlayer player)
         {
             Heading(c, "КИТЫ", "Обложки, содержимое и получение наборов");
@@ -1988,28 +1756,6 @@
                 ?? plugin.Call("API_GetSummary", player.userID) as Dictionary<string, object>;
         }
 
-        private Dictionary<string, object> GetAccountProfile(Plugin plugin, BasePlayer player)
-        {
-            if (plugin == null || player == null) return null;
-            return GetAccountProfile(plugin, player.userID);
-        }
-
-        private Dictionary<string, object> GetAccountProfile(Plugin plugin, ulong userId)
-        {
-            if (plugin == null || userId == 0UL) return null;
-
-            Dictionary<string, object> profile =
-                plugin.Call("API_GetProfile", userId) as Dictionary<string, object>;
-            if (profile != null) return profile;
-
-            BasePlayer online = BasePlayer.FindByID(userId);
-            if (online != null)
-                return plugin.Call("API_GetPlayerProfile", online) as Dictionary<string, object>
-                    ?? plugin.Call("API_GetPlayerSummary", online) as Dictionary<string, object>;
-
-            return plugin.Call("API_GetSummary", userId) as Dictionary<string, object>;
-        }
-
         private void AddSteamAvatar(CuiElementContainer c, string parent, string name, ulong steamId,
             string amin, string amax, string omin, string omax)
         {
@@ -2025,70 +1771,6 @@
             });
         }
 
-        private void DrawResourceLifetimeCardInside(CuiElementContainer c, string parent, string name,
-            string shortname, long amount, float x1, float x2)
-        {
-            Panel(c, parent, name, "0 1", "0 1",
-                F(x1) + " -124", F(x2) + " -34", ColCardDark);
-
-            ItemDefinition def = ItemManager.FindItemDefinition(shortname);
-            if (def != null)
-            {
-                c.Add(new CuiElement
-                {
-                    Parent = name,
-                    Components =
-                    {
-                        new CuiImageComponent { ItemId = def.itemid, SkinId = 0, Color = "1 1 1 1" },
-                        new CuiRectTransformComponent
-                        {
-                            AnchorMin = "0.5 1",
-                            AnchorMax = "0.5 1",
-                            OffsetMin = "-25 -55",
-                            OffsetMax = "25 -5"
-                        }
-                    }
-                });
-            }
-
-            Label(c, name, "0 0", "1 0", "4 20", "-4 40",
-                FormatNumber(amount), 13, ColText, TextAnchor.MiddleCenter, Bold);
-            Label(c, name, "0 0", "1 0", "3 3", "-3 22",
-                ResourceLabel(shortname), 9, ColMuted, TextAnchor.MiddleCenter, Reg);
-        }
-
-        private void AccountStatRow(CuiElementContainer c, string suffix, int left, int right,
-            int top, string title, string value)
-        {
-            int bottom = top - 24;
-            string row = Main + ".AccountStat." + suffix;
-
-            Panel(c, Main, row, "0 1", "0 1",
-                left + " " + bottom, right + " " + top, ColCardDark);
-
-            Label(c, row, "0 0", "0.72 1", "12 0", "0 0",
-                title, 11, ColSubText, TextAnchor.MiddleLeft, Reg);
-            Label(c, row, "0.72 0", "1 1", "0 0", "-12 0",
-                value, 12, ColText, TextAnchor.MiddleRight, Bold);
-        }
-
-        private static string ResourceLabel(string shortname)
-        {
-            switch (shortname)
-            {
-                case "wood": return "ДЕРЕВО";
-                case "stones": return "КАМЕНЬ";
-                case "metal.ore": return "МЕТАЛЛ";
-                case "sulfur.ore": return "СЕРА";
-                case "hq.metal.ore": return "МВК";
-                case "cloth": return "ТКАНЬ";
-                case "leather": return "КОЖА";
-                case "fat.animal": return "ЖИР";
-                case "bone.fragments": return "КОСТИ";
-                default: return Safe(shortname, 14).ToUpperInvariant();
-            }
-        }
-
         private static int GetInt(Dictionary<string, object> data, string key)
         {
             object value;
@@ -2111,27 +1793,6 @@
             catch { return 0d; }
         }
 
-        private static Dictionary<string, long> GetLongDictionary(Dictionary<string, object> data, string key)
-        {
-            object value;
-            if (data == null || !data.TryGetValue(key, out value) || value == null)
-                return new Dictionary<string, long>(StringComparer.OrdinalIgnoreCase);
-
-            Dictionary<string, long> direct = value as Dictionary<string, long>;
-            if (direct != null)
-                return new Dictionary<string, long>(direct, StringComparer.OrdinalIgnoreCase);
-
-            var result = new Dictionary<string, long>(StringComparer.OrdinalIgnoreCase);
-            IDictionary<string, object> objects = value as IDictionary<string, object>;
-            if (objects != null)
-            {
-                foreach (var pair in objects)
-                try { result[pair.Key] = Convert.ToInt64(pair.Value, CultureInfo.InvariantCulture); }
-                catch { }
-            }
-            return result;
-        }
-
         private static string FormatNumber(long value)
         {
             return value.ToString("N0", CultureInfo.InvariantCulture).Replace(",", " ");
@@ -2148,19 +1809,6 @@
             return Math.Max(1, minutes) + "м";
         }
 
-        private static string Rich(string color, string value)
-        {
-            return "<color=" + color + ">" + value + "</color>";
-        }
-
-        private static string TopPositionColor(int position)
-        {
-            if (position == 1) return "#FFD34D";
-            if (position == 2) return "#D2D8DE";
-            if (position == 3) return "#C7834C";
-            return ColText;
-        }
-
         private void AddAtlanticBackdrop(CuiElementContainer c, string parent, string name)
         {
             c.Add(new CuiPanel
```

## ChatSystem вместо IQChat

`ChatSystem` реализует `API_ALERT_PLAYER` с сигнатурой IQChat, но одного API
мало. Oxide связывает `[PluginReference]` **по имени поля**
(`Oxide.CSharp.cs:2351` — `pluginReferenceMembers[attribute.Name ?? member.Name]`),
поэтому поле `Plugin IQChat` ищет плагин с именем `IQChat` и при его отсутствии
остаётся `null`: проверки `if (IQChat)` не проходят и сообщения молча не
доходят.

Лечится одной строкой в каждом плагине — атрибуту передаётся имя, само поле и
весь остальной код не меняются.

### XRaidProtection.cs:20

```diff
-		[PluginReference] private Plugin IQChat, RaidableBases, AbandonedBases, Convoy;
+		[PluginReference("ChatSystem")] private Plugin IQChat;
+		[PluginReference] private Plugin RaidableBases, AbandonedBases, Convoy;
```

### IQSimpleVote.cs:1051

```diff
-        [PluginReference] Plugin IQChat, SimpleStatus;
+        [PluginReference("ChatSystem")] Plugin IQChat;
+        [PluginReference] Plugin SimpleStatus;
```

### IQWipeBlock.cs:2225

```diff
-        [PluginReference] Plugin IQChat, nanoModalMenu, nanoSettingModule, nanoChat, Battles, Duel, Duelist, ArenaTournament, AimTraining, XFarmRoom, OneVSOne, EventHelper;
+        [PluginReference("ChatSystem")] Plugin IQChat;
+        [PluginReference] Plugin nanoModalMenu, nanoSettingModule, nanoChat, Battles, Duel, Duelist, ArenaTournament, AimTraining, XFarmRoom, OneVSOne, EventHelper;
```

### IQRates.cs:1618

```diff
-        [PluginReference] Plugin IQChat;
+        [PluginReference("ChatSystem")] Plugin IQChat;
```

`IQRates` в постановке не упоминался, но зовёт тот же API на строке 1866 —
без этой правки уведомления о бонусных рейтах перестали бы приходить.

### Что показал grep по сборке

| Обращение | Где | Статус |
|---|---|---|
| `API_ALERT_PLAYER` (2 аргумента) | `XRaidProtection.cs:609` | реализован |
| `API_ALERT_PLAYER` (4 аргумента) | `IQSimpleVote.cs:927` | реализован |
| `API_ALERT_PLAYER` (4 аргумента) | `IQWipeBlock.cs:1099` | реализован |
| `API_ALERT_PLAYER` (4 аргумента) | `IQRates.cs:1866` | реализован |

Других вызовов к IQChat в сборке нет: ни `API_ALERT`, ни
`API_ALERT_PLAYER_UI` (`IQChat.cs:7115`) никто не зовёт. `API_ALERT` реализован
по постановке, UI-версия — нет, её незачем.

Остальные упоминания IQChat в этих плагинах — поля конфигов с префиксом и
аватаром (`useIQChat`, `iqchatPreset`, `presetReferenceChat`); они передаются в
`API_ALERT_PLAYER` как `customPrefix` и `customAvatar` и работают без правок.
