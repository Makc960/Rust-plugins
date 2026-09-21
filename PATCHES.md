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

## XSkinMenu 1.8.6 (Monster) — оптимизация без изменения интерфейса

Каждый коммит — отдельный раздел с диффом, чтобы переносить на обновления
автора. Все правки проверены двумя способами: `tools/plugincheck/golden.sh`
сравнивает CUI-транскрипт 324 экранов с авторской версией побайтно, а
`XSkinHooks.cs` гоняет горячие хуки на одинаковых входах.

### Коммит 1 — горячие хуки: O(n) → O(1), ранний выход первой строкой

**Было.** `OnItemAddedToContainer` срабатывает на каждое перемещение любого
предмета и первым делом делал два линейных скана `List<ulong>.Contains` по
admin/vip-скинам, затем до шести словарных поисков по строковому ключу и
`UserHasPermission` — и только потом выяснял, что у игрока выключен
`ChangeSG` и делать нечего. `config.Setting.Blacklist` — `List<ulong>`,
`Contains` по нему стоял в `SetSkinCraftGive`, `SetSkinItem`, `OnEntityReskin`
и `OnItemSkinChange`.

**Стало.**
* `_adminSkins`, `_vipSkins`, `_adminAndVipSkins`, `_adminUiFD` —
  `HashSet<ulong>`: все 40 мест использования это `Contains`/`Add`/`Remove`/
  `AddRange`, порядок элементов нигде не читается, поэтому замена не меняет
  поведения. `AddRange` → `UnionWith`.
* Чёрный список: конфиг по-прежнему хранит `List<ulong>` (формат и
  `API_GetBlacklist` не тронуты), чтение идёт через `Blacklisted()` —
  `HashSet`-зеркало, которое пересобирается при расхождении размера с
  конфигом, так что даже внешняя правка списка через API не рассинхронизирует.
* `OnItemAddedToContainer`, `OnItemPickup`, `OnItemCraftFinished`,
  `OnPlayerInput`, `SetSkinCraftGive`: один `TryGetValue` по данным игрока
  вместо трёх индексаторов, проверки переставлены от дешёвых к дорогим
  (`bool` в данных → `Skins.ContainsKey` → `_items` → `UserHasPermission`).
  Все проверки — чистые предикаты, набор условий для действия тот же.
  Побочно: игрок без загруженных данных теперь даёт тихий выход, а не
  `KeyNotFoundException` в хуке крафта.

Проверка: golden — 324 экрана совпадают; XSkinHooks — 15 сценариев.

```diff
diff --git a/XSkinMenu.cs b/XSkinMenu.cs
index bbac83d..6f86277 100644
--- a/XSkinMenu.cs
+++ b/XSkinMenu.cs
@@ -18,7 +18,7 @@ namespace Oxide.Plugins
     class XSkinMenu : RustPlugin
     {
 		private const bool LanguageEnglish = false;
-		private List<ulong> _adminAndVipSkins = new List<ulong>();
+		private HashSet<ulong> _adminAndVipSkins = new HashSet<ulong>();
 		private Dictionary<string, List<ulong>> StoredDataSkins = new Dictionary<string, List<ulong>>();
 		
 		private void SaveData(BasePlayer player)
@@ -33,18 +33,18 @@ namespace Oxide.Plugins
 		{
 			if(item == null || player == null || player.IsNpc) return;
 			
+			if(!StoredData.TryGetValue(player.userID, out Data data) || !data.ChangeSP) return;
+
 			string shortname = item.info.shortname;
-			
+
+			if(!data.Skins.TryGetValue(shortname, out ulong skin)) return;
 			if(config.Setting.ReskinConfig && !_items.ContainsKey(shortname)) return;
-			if(!permission.UserHasPermission(player.UserIDString, permPickup) || !StoredData.ContainsKey(player.userID) || !StoredData[player.userID].Skins.ContainsKey(shortname)) return;
-			
-			if(StoredData[player.userID].ChangeSP)
-			{
-				if(ersK.ContainsKey(shortname) && ersK[shortname].ContainsKey(StoredData[player.userID].Skins[shortname]))
-					NextTick(() => SetSkinCraftGive(player, item, true));
-				else
-					SetSkinCraftGive(player, item);
-			}
+			if(!permission.UserHasPermission(player.UserIDString, permPickup)) return;
+
+			if(ersK.TryGetValue(shortname, out Dictionary<ulong, string> redirects) && redirects.ContainsKey(skin))
+				NextTick(() => SetSkinCraftGive(player, item, true));
+			else
+				SetSkinCraftGive(player, item);
 		}
 		   		 		  						  	   		   		 		  		 			   					  	 	 
         private class SkinConfig
@@ -325,11 +325,15 @@ namespace Oxide.Plugins
 					}
 				
 				if(_removeATC.Contains(player.userID)) return;
-				if(config.Setting.ReskinConfig && !_items.ContainsKey(item.info.shortname)) return;
-				if(!permission.UserHasPermission(player.UserIDString, permGive) || !StoredData.ContainsKey(player.userID) || !StoredData[player.userID].Skins.ContainsKey(item.info.shortname)) return;
-				
-				if(StoredData[player.userID].ChangeSG)
-					SetSkinCraftGive(player, item, true);
+				if(!StoredData.TryGetValue(player.userID, out Data data) || !data.ChangeSG) return;
+
+				string shortname = item.info.shortname;
+
+				if(!data.Skins.ContainsKey(shortname)) return;
+				if(config.Setting.ReskinConfig && !_items.ContainsKey(shortname)) return;
+				if(!permission.UserHasPermission(player.UserIDString, permGive)) return;
+
+				SetSkinCraftGive(player, item, true);
 			}
 		}
 		
@@ -384,17 +388,18 @@ namespace Oxide.Plugins
 			if(task.skinID == 0)
 			{
 				BasePlayer player = crafter.owner;
-				
+
+				if(player == null || !StoredData.TryGetValue(player.userID, out Data data) || data.ChangeSG || !data.ChangeSC) return;
+
 				string shortname = item.info.shortname;
-				
-				if(!StoredData[player.userID].Skins.ContainsKey(shortname) || !permission.UserHasPermission(player.UserIDString, permCraft)) return;
-				if(!StoredData[player.userID].ChangeSG && StoredData[player.userID].ChangeSC)
-				{
-					if(ersK.ContainsKey(shortname) && ersK[shortname].ContainsKey(StoredData[player.userID].Skins[shortname]))
-						NextTick(() => SetSkinCraftGive(player, item, true));
-					else
-						SetSkinCraftGive(player, item);
-				}
+
+				if(!data.Skins.TryGetValue(shortname, out ulong skin)) return;
+				if(!permission.UserHasPermission(player.UserIDString, permCraft)) return;
+
+				if(ersK.TryGetValue(shortname, out Dictionary<ulong, string> redirects) && redirects.ContainsKey(skin))
+					NextTick(() => SetSkinCraftGive(player, item, true));
+				else
+					SetSkinCraftGive(player, item);
 			}
 		}
 		
@@ -417,7 +422,7 @@ namespace Oxide.Plugins
 		
 				
 		private Dictionary<string, ulong> API_GetSkinsPlayer(ulong userID) => StoredData[userID].Skins;
-		private List<ulong> _vipSkins = new List<ulong>();
+		private HashSet<ulong> _vipSkins = new HashSet<ulong>();
 		private void CanSetAutoKit(BasePlayer player) => _removeATC.Add(player.userID);
 		
 		private void KitInfoGUI(BasePlayer player, string category, string kitname)
@@ -766,7 +771,7 @@ namespace Oxide.Plugins
 						
 						foreach(var i in pooledList)
 						{
-							if(config.Setting.Blacklist.Contains(i.skin)) continue;
+							if(Blacklisted(i.skin)) continue;
 							
 							SSI(player, i, skin, item);
 						}
@@ -777,7 +782,7 @@ namespace Oxide.Plugins
 					
 					foreach(var i in pooledList)
 					{
-						if(i.skin == skin || config.Setting.Blacklist.Contains(i.skin)) continue;
+						if(i.skin == skin || Blacklisted(i.skin)) continue;
 						
 						SSI(player, i, skin, item);
 					}
@@ -788,7 +793,7 @@ namespace Oxide.Plugins
 					
 					foreach(var i in pooledList)
 					{
-						if(i.skin == skin || config.Setting.Blacklist.Contains(i.skin)) continue;
+						if(i.skin == skin || Blacklisted(i.skin)) continue;
 						
 						SSI(player, i, skin);
 					}
@@ -798,7 +803,7 @@ namespace Oxide.Plugins
 		
 		private object OnEntityReskin(BaseEntity entity, ItemSkinDirectory.Skin skin, BasePlayer player)
 		{
-			if(config.Setting.Blacklist.Contains(entity.skinID))
+			if(Blacklisted(entity.skinID))
 			{
 				EffectNetwork.Send(new Effect("assets/bundled/prefabs/fx/invite_notice.prefab", player, 0, new Vector3(), new Vector3()), player.Connection);
 				return false;
@@ -817,9 +822,10 @@ namespace Oxide.Plugins
 				
 				foreach(ulong skinID in skinIDs)
 				{
-					if(skinID != 0 && !config.Setting.Blacklist.Contains(skinID))
+					if(skinID != 0 && !Blacklisted(skinID))
 					{
 						config.Setting.Blacklist.Add(skinID);
+						_blacklist.Add(skinID);
 						msg += $" {skinID}";
 						
 						x++;
@@ -872,7 +878,7 @@ namespace Oxide.Plugins
 		
 		private void OnPlayerInput(BasePlayer player, InputState input)
 		{
-			if(!input.WasJustPressed(BUTTON.FIRE_SECONDARY) || !StoredData[player.userID].UseSprayC) return;
+			if(!input.WasJustPressed(BUTTON.FIRE_SECONDARY) || !StoredData.TryGetValue(player.userID, out Data data) || !data.UseSprayC) return;
 			
 			if(permission.UserHasPermission(player.UserIDString, permSprayC))
 			{
@@ -917,7 +923,7 @@ namespace Oxide.Plugins
             }
         }
 		
-		private List<ulong> _adminUiFD = new List<ulong>();
+		private HashSet<ulong> _adminUiFD = new HashSet<ulong>();
 		
 		private ICuiComponent GetImageComponent(int itemid, ulong skin)
 		{
@@ -2454,7 +2460,7 @@ namespace Oxide.Plugins
 							if(StoredDataSkins.TryGetValue(item.Key, out List<ulong> skins))
 							{
 								foreach(ulong skin in item.Value)
-									if(!skins.Contains(skin) && !_adminAndVipSkins.Contains(skin) && !config.Setting.Blacklist.Contains(skin))
+									if(!skins.Contains(skin) && !_adminAndVipSkins.Contains(skin) && !Blacklisted(skin))
 									{
 										skins.Add(skin);
 										count++;
@@ -2464,7 +2470,7 @@ namespace Oxide.Plugins
 							{
 								skins = new List<ulong>(item.Value);
 								skins.RemoveAll(skin => _adminAndVipSkins.Contains(skin));
-								skins.RemoveAll(skin => config.Setting.Blacklist.Contains(skin));
+								skins.RemoveAll(skin => Blacklisted(skin));
 								
 								StoredDataSkins.Add(item.Key, skins);
 								count += skins.Count;
@@ -2921,7 +2927,7 @@ namespace Oxide.Plugins
 								}
 								else if(_shortnamesEntity.ContainsKey(entity.ShortPrefabName))
 								{
-									if(config.Setting.Blacklist.Contains(entity.skinID))
+									if(Blacklisted(entity.skinID))
 									{
 										EffectNetwork.Send(new Effect("assets/bundled/prefabs/fx/invite_notice.prefab", player, 0, new Vector3(), new Vector3()), player.Connection);
 										return;
@@ -2949,7 +2955,7 @@ namespace Oxide.Plugins
 						string shortname = _redirectSkins.ContainsKey(item.info.shortname) ? _redirectSkins[item.info.shortname] : item.info.shortname, sitem = args.GetString(1);
 						ulong skin = args.GetULong(2);
 						
-						if(config.Setting.Blacklist.Contains(item.skin))
+						if(Blacklisted(item.skin))
 						{
 							EffectNetwork.Send(new Effect("assets/bundled/prefabs/fx/invite_notice.prefab", player, 0, new Vector3(), new Vector3()), player.Connection);
 							Cooldowns[player] = DateTime.Now.AddSeconds(0.5f);
@@ -3185,12 +3191,12 @@ namespace Oxide.Plugins
 			if(player == null || item == null) return;
 			
 			string shortname = item.info.shortname;
-			ulong skin = StoredData[player.userID].Skins[shortname];
-			
+
+			if(!StoredData.TryGetValue(player.userID, out Data data) || !data.Skins.TryGetValue(shortname, out ulong skin)) return;
 			if(config.Setting.ReskinConfig && !_items.ContainsKey(shortname)) return;
-			if(item.skin == skin || config.Setting.Blacklist.Contains(item.skin)) return;
-			
-			if(isgive && skin == 0 && StoredData[player.userID].ChangeSGN) return;
+			if(item.skin == skin || Blacklisted(item.skin)) return;
+
+			if(isgive && skin == 0 && data.ChangeSGN) return;
 			
 			SSI(player, item, skin, ersK.ContainsKey(shortname) ? shortname : "", isgive);
 		}
@@ -3415,7 +3421,21 @@ namespace Oxide.Plugins
 			CuiHelper.AddUi(player, container);
 		}
 		
-		private List<ulong> _adminSkins = new List<ulong>();
+		private HashSet<ulong> _adminSkins = new HashSet<ulong>();
+		private HashSet<ulong> _blacklist = new HashSet<ulong>();
+		
+		private bool Blacklisted(ulong skinID)
+		{
+			List<ulong> source = config.Setting.Blacklist;
+			
+			if(_blacklist.Count != source.Count)
+			{
+				_blacklist.Clear();
+				_blacklist.UnionWith(source);
+			}
+			
+			return _blacklist.Contains(skinID);
+		}
 		
 		private void cmdSetSkinEntity(BasePlayer player, string command, string[] args)
 		{
@@ -3688,11 +3708,12 @@ namespace Oxide.Plugins
 				
 		private void AddToBlacklist(ulong skinID, string pluginName = "Unknown")
 		{
-			if(skinID != 0 && !config.Setting.Blacklist.Contains(skinID))
+			if(skinID != 0 && !Blacklisted(skinID))
 			{
 				PrintWarning(LanguageEnglish ? $"The [ {pluginName} ] plugin has blacklisted the skin - {skinID}" : $"Плагин [ {pluginName} ] добавил в черный список скин - {skinID}");
 				
 				config.Setting.Blacklist.Add(skinID);
+				_blacklist.Add(skinID);
 				SaveConfig();
 			}
 		}
@@ -4058,7 +4079,7 @@ namespace Oxide.Plugins
 			{
 				string shortname = _redirectSkins.ContainsKey(item.info.shortname) ? _redirectSkins[item.info.shortname] : item.info.shortname;
 				
-				if(config.Setting.Blacklist.Contains(item.skin))
+				if(Blacklisted(item.skin))
 				{
 					EffectNetwork.Send(new Effect("assets/bundled/prefabs/fx/invite_notice.prefab", player, 0, new Vector3(), new Vector3()), player.Connection);
 					return;
@@ -4162,7 +4183,7 @@ namespace Oxide.Plugins
 		   		 		  						  	   		   		 		  		 			   					  	 	 
 			foreach(var item in config.Setting.AdminSkins)
 			{
-				_adminSkins.AddRange(item.Value);
+				_adminSkins.UnionWith(item.Value);
 				
 				if(StoredDataSkins.ContainsKey(item.Key))
 						foreach(var skins in item.Value)
@@ -4172,7 +4193,7 @@ namespace Oxide.Plugins
 				
 			foreach(var item in config.Setting.VipSkins)
 			{
-				_vipSkins.AddRange(item.Value);
+				_vipSkins.UnionWith(item.Value);
 				
 				if(StoredDataSkins.ContainsKey(item.Key))
 					foreach(var skins in item.Value)
@@ -4180,8 +4201,8 @@ namespace Oxide.Plugins
 							StoredDataSkins[item.Key].Remove(skins);
 			}
 						
-			_adminAndVipSkins.AddRange(_adminSkins);
-			_adminAndVipSkins.AddRange(_vipSkins);
+			_adminAndVipSkins.UnionWith(_adminSkins);
+			_adminAndVipSkins.UnionWith(_vipSkins);
 			
 			StoredDataSkinsName[0] = "Default";
 			
@@ -4244,7 +4265,7 @@ namespace Oxide.Plugins
 		
 		private object OnItemSkinChange(int skinID, Item item, StorageContainer container, BasePlayer player)
 		{
-			if(config.Setting.Blacklist.Contains(item.skin))
+			if(Blacklisted(item.skin))
 			{
 				EffectNetwork.Send(new Effect("assets/bundled/prefabs/fx/invite_notice.prefab", player, 0, new Vector3(), new Vector3()), player.Connection);
 				
@@ -4559,9 +4580,10 @@ namespace Oxide.Plugins
 			
 			foreach(var category in config.Category)
 				category.Value.Remove("wallpaper");
-			
+
 			///DEL
 			
+			_blacklist = new HashSet<ulong>(config.Setting.Blacklist);
 			SaveConfig();
         }
 		private Dictionary<ulong, string> StoredDataSkinsName = new Dictionary<ulong, string>();
@@ -4600,7 +4622,7 @@ namespace Oxide.Plugins
 					if(entity.OwnerID == player.userID || player.currentTeam != 0 && player.Team.members.Contains(entity.OwnerID) && StoredDataFriends.ContainsKey(entity.OwnerID) && StoredDataFriends[entity.OwnerID])
 						if(_shortnamesEntity.ContainsKey(entity.ShortPrefabName))
 						{
-							if(config.Setting.Blacklist.Contains(entity.skinID))
+							if(Blacklisted(entity.skinID))
 							{
 								EffectNetwork.Send(new Effect("assets/bundled/prefabs/fx/invite_notice.prefab", player, 0, new Vector3(), new Vector3()), player.Connection);
 								return;
```

### Коммит 2 — данные: запись на диск только того, что изменилось

**Было.** Два таймера в `OnServerInitialized`: каждые 180 с
`BasePlayer.activePlayerList.ToList().ForEach(SaveData)` сериализовал в JSON
и писал на диск файл **каждого** онлайн-игрока, менялось там что-то или нет;
каждые 200 с так же безусловно писался `Friends`. Файл игрока — словарь
`Skins` со всеми предметами из `StoredDataSkins` (сотни ключей, почти все
`0`), то есть 10–20 КБ JSON на игрока. При 100 онлайн это ~1,5 МБ
сериализации и 100 обращений к диску каждые 3 минуты — O(игроков) на тик
таймера, независимо от активности. Именно эта работа даёт основной вклад в
842 МБ аллокаций за сессию: генерация JSON через `Newtonsoft` и
`File.WriteAllText` целиком на управляемой куче.

**Стало.** O(изменённых игроков), при отсутствии изменений — ноль.
* `_dirty` (`HashSet<ulong>`) и `_friendsDirty`: флаг ставится в каждой точке,
  где меняются данные игрока — 13 переключателей в `ccmdSetting`,
  `setskin`/`clear`/`clearall`/`removekit`/`addkit`/`setkit` в
  `ccmdCategoryS`, обнуление недоступных скинов в `ResetPlayerSkins`, и
  `LoadData`, если файла не было или нормализация (новые предметы, FP_TOS,
  удаление `wallpaper` из китов) что-то поменяла. Временные переключения
  `ChangeSG` внутри `skin`/`setkit` (выключили → вызвали SSI → включили)
  флаг не ставят: итоговое состояние равно исходному.
* `MarkDirty` при первом изменении заводит один `timer.Once(60 с,
  FlushDirty)`; последующие изменения в это окно только пополняют множество.
  Раньше изменение попадало на диск в пределах 180 с, теперь — 60 с.
* `OnServerSave` (вызывается игрой из `SaveRestore.Save`,
  Assembly-CSharp.cs:352785) сбрасывает всё грязное вместе с сохранением мира.
* Выход игрока и `Unload` пишут файл безусловно, как раньше — страховка на
  случай, если какая-то точка записи осталась незамеченной; при выходе
  игрок удаляется и из `_dirty`.
* Оба периодических таймера удалены.

Формат файлов не тронут: на диск уходит тот же объект `Data` и тот же
`StoredDataFriends`, старые файлы читаются как есть. Что можно было бы не
хранить: `Skins` содержит запись `"предмет": 0` для каждого предмета
коллекции — ужать нельзя без смены формата, поэтому оставлено.

Проверка: `XSkinHooks` — 10 сценариев на запись (нет мгновенной записи после
клика, один `OnServerSave` пишет только изменённого, повторный — ничего,
`friends` пишет только `Friends`, выход пишет как раньше, повторный вход с
полным файлом не помечает грязным); golden — 324 экрана побайтно совпадают.
Заглушки: `DataFileSystem` стал мини-диском (`Store`/`Writes`),
`PluginTimers.Once` складывает коллбэки в `Timer.Scheduled`, тест вызывает
`Timer.Fire()`.

```diff
diff --git a/XSkinMenu.cs b/XSkinMenu.cs
index 6f86277..a8ea801 100644
--- a/XSkinMenu.cs
+++ b/XSkinMenu.cs
@@ -29,6 +29,47 @@ namespace Oxide.Plugins
 				Interface.Oxide.DataFileSystem.WriteObject($"XDataSystem/XSkinMenu/UserSettings/{userID}", StoredData[userID]);
 		}
 		
+		private readonly HashSet<ulong> _dirty = new HashSet<ulong>();
+		private bool _friendsDirty, _flushPending;
+		
+		private void MarkDirty(ulong userID)
+		{
+			_dirty.Add(userID);
+			ScheduleFlush();
+		}
+		
+		private void ScheduleFlush()
+		{
+			if(_flushPending) return;
+			
+			_flushPending = true;
+			timer.Once(60f, FlushDirty);
+		}
+		
+		private void FlushDirty()
+		{
+			_flushPending = false;
+			
+			if(_dirty.Count != 0)
+			{
+				foreach(ulong userID in _dirty)
+					if(StoredData.TryGetValue(userID, out Data data))
+						Interface.Oxide.DataFileSystem.WriteObject($"XDataSystem/XSkinMenu/UserSettings/{userID}", data);
+				
+				_dirty.Clear();
+			}
+			
+			if(_friendsDirty)
+			{
+				_friendsDirty = false;
+				
+				if(StoredDataFriends != null && StoredDataFriends.Count != 0)
+					Interface.Oxide.DataFileSystem.WriteObject("XDataSystem/XSkinMenu/Friends", StoredDataFriends);
+			}
+		}
+		
+		private void OnServerSave() => FlushDirty();
+		
 		private void OnItemPickup(Item item, BasePlayer player)
 		{
 			if(item == null || player == null || player.IsNpc) return;
@@ -1244,6 +1285,7 @@ namespace Oxide.Plugins
 			{   
 				SaveData(player);
 				StoredData.Remove(player.userID);
+				_dirty.Remove(player.userID);
 			}			
 			  
 			if(Cooldowns.ContainsKey(player))
@@ -1495,12 +1537,6 @@ namespace Oxide.Plugins
 			GenerateItems();
 				
 			BasePlayer.activePlayerList.ToList().ForEach(OnPlayerConnected);
-			timer.Every(180, () => BasePlayer.activePlayerList.ToList().ForEach(SaveData));
-			timer.Every(200, () =>
-			{
-				if(StoredDataFriends != null && StoredDataFriends.Count != 0)
-					Interface.Oxide.DataFileSystem.WriteObject("XDataSystem/XSkinMenu/Friends", StoredDataFriends);
-			});
 			
 			if(config.Setting.UseImageLibrary && !ImageLibrary)
 			{
@@ -2381,18 +2417,31 @@ namespace Oxide.Plugins
 		private void LoadData(BasePlayer player)
 		{
 			ulong userID = player.userID;
+			bool dirty = false;
 			
 			if(Interface.Oxide.DataFileSystem.ExistsDatafile($"XDataSystem/XSkinMenu/UserSettings/{userID}"))
 			{
 				var Data = Interface.Oxide.DataFileSystem.ReadObject<Data>($"XDataSystem/XSkinMenu/UserSettings/{userID}");
 				
-				StoredData[userID] = Data ?? DATA();
+				if(Data == null)
+				{
+					Data = DATA();
+					dirty = true;
+				}
+				
+				StoredData[userID] = Data;
 			}
 			else
+			{
 				StoredData[userID] = DATA();
+				dirty = true;
+			}
 			
 			if(!StoredDataFriends.ContainsKey(userID))
-                StoredDataFriends.Add(userID, config.PSetting.ChangeF);
+			{
+				StoredDataFriends.Add(userID, config.PSetting.ChangeF);
+				_friendsDirty = true;
+			}
 			
 			var list = StoredData[userID].Skins;
 			
@@ -2401,7 +2450,10 @@ namespace Oxide.Plugins
 				string key = skin.Key;
 				
 				if(!list.ContainsKey(key))
+				{
 					list.Add(key, _items.ContainsKey(key) ? _items[key] : 0);
+					dirty = true;
+				}
 			}
 			
 			//FP_TOS
@@ -2413,20 +2465,30 @@ namespace Oxide.Plugins
 					
 					foreach(var item in items.Keys.ToList())
 						if(_allFPSkins.Contains(items[item]))
+						{
 							items[item] = 0;
+							dirty = true;
+						}
 				}
 				
 				foreach(var item in list.Keys.ToList())
 					if(_allFPSkins.Contains(list[item]))
+					{
 						list[item] = 0;
+						dirty = true;
+					}
 			}
 			
 			///DEL
 			
 			foreach(var kits in StoredData[userID].Kits)
-				kits.Value.Remove("wallpaper");
+				if(kits.Value.Remove("wallpaper"))
+					dirty = true;
 			
 			///DEL
+			
+			if(dirty)
+				MarkDirty(userID);
 		}
 		
 		[ConsoleCommand("xskin_import_file")]
@@ -2605,24 +2667,29 @@ namespace Oxide.Plugins
 				case "inventory":
 				{
 					StoredData[player.userID].ChangeSI = !StoredData[player.userID].ChangeSI;
+					MarkDirty(player.userID);
 					SettingGUI(player);
 					break;
 				}
 				case "clear":
 				{
 					StoredData[player.userID].ChangeSCL = !StoredData[player.userID].ChangeSCL;
+					MarkDirty(player.userID);
 					SettingGUI(player);
 					break;
 				}
 				case "entity":
 				{
 					StoredData[player.userID].ChangeSE = !StoredData[player.userID].ChangeSE;
+					MarkDirty(player.userID);
 					SettingGUI(player);
 					break;
 				}
 				case "friends":
 				{
 					StoredDataFriends[player.userID] = !StoredDataFriends[player.userID];
+					_friendsDirty = true;
+					ScheduleFlush();
 					SettingGUI(player);
 					break;
 				}
@@ -2630,6 +2697,7 @@ namespace Oxide.Plugins
 				{
 					StoredData[player.userID].ChangeSG = !StoredData[player.userID].ChangeSG;
 					StoredData[player.userID].ChangeSP = false;
+					MarkDirty(player.userID);
 					SettingGUI(player);
 					break;
 				}				
@@ -2637,30 +2705,35 @@ namespace Oxide.Plugins
 				{
 					StoredData[player.userID].ChangeSP = !StoredData[player.userID].ChangeSP;
 					StoredData[player.userID].ChangeSG = false;
+					MarkDirty(player.userID);
 					SettingGUI(player);
 					break;
 				}				
 				case "giveno":
 				{
 					StoredData[player.userID].ChangeSGN = !StoredData[player.userID].ChangeSGN;
+					MarkDirty(player.userID);
 					SettingGUI(player);
 					break;
 				}
 				case "craft":
 				{
 					StoredData[player.userID].ChangeSC = !StoredData[player.userID].ChangeSC;
+					MarkDirty(player.userID);
 					SettingGUI(player);
 					break;
 				}
 				case "spraycan":
 				{
 					StoredData[player.userID].UseSprayC = !StoredData[player.userID].UseSprayC;
+					MarkDirty(player.userID);
 					SettingGUI(player);
 					break;
 				}
 				case "sound":
 				{
 					StoredData[player.userID].UseSoundE = !StoredData[player.userID].UseSoundE;
+					MarkDirty(player.userID);
 					SettingGUI(player);
 					break;
 				}
@@ -2668,12 +2741,14 @@ namespace Oxide.Plugins
 				{
 					StoredData[player.userID].Comfort = false;
 					StoredData[player.userID].ComfortP = false;
+					MarkDirty(player.userID);
 					SettingGUI(player);
 					break;
 				}				
 				case "comfortmenu":
 				{
 					StoredData[player.userID].Comfort = true;
+					MarkDirty(player.userID);
 					SettingGUI(player);
 					break;
 				}				
@@ -2689,6 +2764,7 @@ namespace Oxide.Plugins
 							StoredData[player.userID].ComfortP = true;
 						}
 						
+						MarkDirty(player.userID);
 						SettingGUI(player);
 					}
 					break;
@@ -2995,6 +3071,7 @@ namespace Oxide.Plugins
 					if(!(StoredDataSkins[item].Contains(skin) || _adminAndVipSkins.Contains(skin))) return;
 					
 					StoredData[player.userID].Skins[item] = skin;
+					MarkDirty(player.userID);
 					
 					if(!permission.UserHasPermission(player.UserIDString, permInv))
 						SendReply(player, lang.GetMessage("NOPERM", this, player.UserIDString));
@@ -3036,6 +3113,7 @@ namespace Oxide.Plugins
 					
 					string item = args.GetString(1);
 					StoredData[player.userID].Skins[item] = 0;
+					MarkDirty(player.userID);
 					
 					CuiHelper.DestroyUi(player, $".I + {args.GetString(2)}");
 					if(StoredData[player.userID].ChangeSCL) SetSkinItem(player, item, 0);
@@ -3055,6 +3133,8 @@ namespace Oxide.Plugins
 					foreach(var skin in StoredDataSkins) 
 						StoredData[player.userID].Skins.Add(skin.Key, 0);
 					
+					MarkDirty(player.userID);
+					
 					GUI(player);
 					EffectNetwork.Send(z, player.Connection);
 					
@@ -3095,6 +3175,7 @@ namespace Oxide.Plugins
 					if(StoredData[player.userID].Kits.ContainsKey(key))
 					{
 						StoredData[player.userID].Kits.Remove(key);
+						MarkDirty(player.userID);
 							
 						CustomKitsGUI(player, Page);
 						EffectNetwork.Send(z, player.Connection);
@@ -3139,6 +3220,7 @@ namespace Oxide.Plugins
 						if(newkit.Count != 0)
 						{
 							StoredData[player.userID].Kits.Add(kitname, newkit);
+							MarkDirty(player.userID);
 							CustomKitsGUI(player);
 						}
 						else
@@ -3169,7 +3251,10 @@ namespace Oxide.Plugins
 						//if(!(StoredDataSkins[skin.Key].Contains(skin.Value) || _adminAndVipSkins.Contains(skin.Value))) continue;
 						
 						if(setK && StoredData[player.userID].Skins.ContainsKey(skin.Key))
+						{
 							StoredData[player.userID].Skins[skin.Key] = skin.Value;
+							MarkDirty(player.userID);
+						}
 						
 						if(invK)
 							SetSkinItem(player, skin.Key, skin.Value);
@@ -4290,17 +4375,29 @@ namespace Oxide.Plugins
 		
 		private void ResetPlayerSkins(BasePlayer player)
 		{
-			if(StoredData.ContainsKey(player.userID))
+			if(StoredData.TryGetValue(player.userID, out Data data))
 			{
+				Dictionary<string, ulong> skins = data.Skins;
+				bool dirty = false;
+				
 				if(!permission.UserHasPermission(player.UserIDString, permAdminS))
 					foreach(var item in config.Setting.AdminSkins)
-						if(StoredData[player.userID].Skins.ContainsKey(item.Key) && item.Value.Contains(StoredData[player.userID].Skins[item.Key]))
-							StoredData[player.userID].Skins[item.Key] = 0;
-					
+						if(skins.TryGetValue(item.Key, out ulong skin) && item.Value.Contains(skin))
+						{
+							skins[item.Key] = 0;
+							dirty = true;
+						}
+				
 				if(!permission.UserHasPermission(player.UserIDString, permVipS))
 					foreach(var item in config.Setting.VipSkins)
-						if(StoredData[player.userID].Skins.ContainsKey(item.Key) && item.Value.Contains(StoredData[player.userID].Skins[item.Key]))
-							StoredData[player.userID].Skins[item.Key] = 0;
+						if(skins.TryGetValue(item.Key, out ulong skin) && item.Value.Contains(skin))
+						{
+							skins[item.Key] = 0;
+							dirty = true;
+						}
+				
+				if(dirty)
+					MarkDirty(player.userID);
 			}
 		}
 		
```

### Коммит 3 — SkinGUI/ItemGUI: O(плиток) лишней работы → O(1) на экран

**Было.** На каждую отрисовку списка скинов (`SkinGUI`, до 40 плиток):
`permission.UserHasPermission(permAdmin)` + `_adminUiFD.Contains` вызывались
внутри цикла для каждой плитки (40 обращений к библиотеке прав вместо
одного); `list_skins` — новый `List<ulong>` с `AddRange` всего списка
скинов предмета (сотни элементов) даже когда admin/vip-списков у предмета
нет и результат — точная копия `StoredDataSkins[item]`; `Skip(Page*count)`
через LINQ; `StoredDataSkinsName.ContainsKey` + индексатор — два поиска на
плитку; 80 интерполяций `$"{-497.5 + (x * 100)} …"` — форматирование
`double` в строку для смещений, одинаковых для всех игроков и экранов. В
`ItemGUI` то же: `Skip().Take()` через LINQ, `ContainsKey`+индексатор по
трём словарям на предмет, 80 интерполяций смещений, три индексатора
`StoredData[player.userID]`.

**Стало.**
* Таблицы смещений плиток (`_tileMinD/_tileMaxD` — 40 позиций обычного
  режима, общие для SkinGUI и ItemGUI; `_skinTileMinC/MaxC` — comfort-режим
  SkinGUI; `_itemTile…C/P` — 14 позиций comfort / comfort+) строятся один
  раз при первой отрисовке теми же выражениями, что были в интерполяциях,
  поэтому строки побайтно те же.
* `SkinGUI`: права admin-UI считаются один раз до цикла; список скинов —
  ссылка на `StoredDataSkins[item]` без копии, объединённый список создаётся
  только если у предмета есть admin/vip-скины и у игрока есть право;
  `for` по индексу вместо `Skip`; `TryGetValue` для имени скина; одна
  выборка данных игрока.
* `ItemGUI`: пропуск страницы счётчиком вместо `Skip().Take()`;
  `TryGetValue` вместо `ContainsKey`+индексатор для скина игрока и трёх
  списков доступности; одна выборка данных игрока.

Порядок, состав и содержимое элементов не изменились — golden совпадает
побайтно на всех 324 экранах (в матрице есть admin/vip-списки у `rifle.ak`,
поиск, страницы, comfort и comfort+).

```diff
diff --git a/XSkinMenu.cs b/XSkinMenu.cs
index a8ea801..f483508 100644
--- a/XSkinMenu.cs
+++ b/XSkinMenu.cs
@@ -3288,7 +3288,8 @@ namespace Oxide.Plugins
 		
 		private void ItemGUI(BasePlayer player, string category, int Page = 0, string itemname = "null")
 		{
-			bool comfort = StoredData[player.userID].Comfort, comfortp = StoredData[player.userID].ComfortP;
+			Data data = StoredData[player.userID];
+			bool comfort = data.Comfort, comfortp = data.ComfortP;
 			
 			CuiHelper.DestroyUi(player, ".SettingGUI");
 			if(!comfort) CuiHelper.DestroyUi(player, ".SkinGUI");
@@ -3318,22 +3319,34 @@ namespace Oxide.Plugins
 			
 						
 			bool permadmins = permission.UserHasPermission(player.UserIDString, permAdminS), permvips = permission.UserHasPermission(player.UserIDString, permVipS);
-			var player_data = StoredData[player.userID].Skins;
+			var player_data = data.Skins;
 			
 			int x = 0, y = 0, z = 0, count = comfortp ? 14 : 11;
+			int skip = Page * (comfort ? count : 40), taken = 0;
 			
-			foreach(var item in comfort ? config.Category[category].Skip(Page * count).Take(count) : config.Category[category].Skip(Page * 40))
+			if(_tileMinD == null) BuildTileOffsets();
+			string[] tileMin = comfort ? (comfortp ? _itemTileMinP : _itemTileMinC) : _tileMinD, tileMax = comfort ? (comfortp ? _itemTileMaxP : _itemTileMaxC) : _tileMaxD;
+			
+			foreach(var item in config.Category[category])
 			{
+				if(skip > 0)
+				{
+					skip--;
+					continue;
+				}
+				
+				if(comfort && taken++ == count)
+					break;
+				
 				string key = item.Key;
 				
-				bool c = player_data.ContainsKey(key);
-				ulong skinID = c ? player_data[key] : 0;
-				bool s = skinID != 0, available = c && (StoredDataSkins.ContainsKey(key) && StoredDataSkins[key].Count != 0 || permvips && config.Setting.VipSkins.ContainsKey(key) && config.Setting.VipSkins[key].Count != 0 || permadmins && config.Setting.AdminSkins.ContainsKey(key) && config.Setting.AdminSkins[key].Count != 0);
+				bool c = player_data.TryGetValue(key, out ulong skinID);
+				bool s = skinID != 0, available = c && (StoredDataSkins.TryGetValue(key, out List<ulong> stored) && stored.Count != 0 || permvips && config.Setting.VipSkins.TryGetValue(key, out List<ulong> vip) && vip.Count != 0 || permadmins && config.Setting.AdminSkins.TryGetValue(key, out List<ulong> adm) && adm.Count != 0);
 				int itemid = _itemsId[key];
 				
 			    container.Add(new CuiPanel
                 {
-                    RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = comfort ? $"{(comfortp ? -611.25 : -480) + (x * 87.5)} {-42.5 - (y * 90)}" : $"{-497.5 + (x * 100)} {102.375 - (y * 100)}", OffsetMax = comfort ? $"{(comfortp ? -526.25 : -395) + (x * 87.5)} {42.5 - (y * 90)}" : $"{-402.5 + (x * 100)} {197.375 - (y * 100)}" },
+                    RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = tileMin[y * 10 + x], OffsetMax = tileMax[y * 10 + x] },
                     Image = { Color = config.GUI.BlockColor, Material = "assets/icons/greyout.mat" }
                 }, ".ItemGUI", ".Item");
 				
@@ -3956,9 +3969,45 @@ namespace Oxide.Plugins
 			});
 		}
 		
+		// Смещения плиток не зависят от игрока и состояния: те же строки, что давали интерполяции
+		// в SkinGUI/ItemGUI, но посчитанные один раз вместо 80 форматирований double на каждый экран.
+		private string[] _tileMinD, _tileMaxD, _skinTileMinC, _skinTileMaxC, _itemTileMinC, _itemTileMaxC, _itemTileMinP, _itemTileMaxP;
+		
+		private void BuildTileOffsets()
+		{
+			_tileMinD = new string[40];
+			_tileMaxD = new string[40];
+			_skinTileMinC = new string[40];
+			_skinTileMaxC = new string[40];
+			
+			for(int i = 0; i < 40; i++)
+			{
+				int x = i % 10, y = i / 10;
+				
+				_tileMinD[i] = $"{-497.5 + (x * 100)} {102.375 - (y * 100)}";
+				_tileMaxD[i] = $"{-402.5 + (x * 100)} {197.375 - (y * 100)}";
+				_skinTileMinC[i] = $"{-497.5 + (x * 100)} {52.375 - (y * 100)}";
+				_skinTileMaxC[i] = $"{-402.5 + (x * 100)} {147.375 - (y * 100)}";
+			}
+			
+			_itemTileMinC = new string[14];
+			_itemTileMaxC = new string[14];
+			_itemTileMinP = new string[14];
+			_itemTileMaxP = new string[14];
+			
+			for(int x = 0, y = 0; x < 14; x++)
+			{
+				_itemTileMinC[x] = $"{-480 + (x * 87.5)} {-42.5 - (y * 90)}";
+				_itemTileMaxC[x] = $"{-395 + (x * 87.5)} {42.5 - (y * 90)}";
+				_itemTileMinP[x] = $"{-611.25 + (x * 87.5)} {-42.5 - (y * 90)}";
+				_itemTileMaxP[x] = $"{-526.25 + (x * 87.5)} {42.5 - (y * 90)}";
+			}
+		}
+		
 		private void SkinGUI(BasePlayer player, string item, int Page = 0, string category = "null", int PageC = 0, string search = "")
 		{
-			bool comfort = StoredData[player.userID].Comfort;
+			Data data = StoredData[player.userID];
+			bool comfort = data.Comfort;
 			
             CuiElementContainer container = new CuiElementContainer();
 			
@@ -3969,17 +4018,28 @@ namespace Oxide.Plugins
             }, ".SGUI", ".SkinGUI", ".SkinGUI");
 			
 			int x = 0, y = 0, count = comfort ? 30 : 40, yN = comfort ? 3 : 4;
-			ulong s = StoredData[player.userID].Skins[item];
+			ulong s = data.Skins[item];
 			int itemid = _itemsId[item];
+			bool adminUi = permission.UserHasPermission(player.UserIDString, permAdmin) && !_adminUiFD.Contains(player.userID);
 			
-			List<ulong> list_skins = new List<ulong>();
+			if(_tileMinD == null) BuildTileOffsets();
+			string[] tileMin = comfort ? _skinTileMinC : _tileMinD, tileMax = comfort ? _skinTileMaxC : _tileMaxD;
 			
-			if(config.Setting.AdminSkins.ContainsKey(item) && permission.UserHasPermission(player.UserIDString, permAdminS))
-				list_skins.AddRange(config.Setting.AdminSkins[item]);
-			if(config.Setting.VipSkins.ContainsKey(item) && permission.UserHasPermission(player.UserIDString, permVipS))
-				list_skins.AddRange(config.Setting.VipSkins[item]);
+			List<ulong> list_skins = StoredDataSkins[item], adminList, vipList;
 			
-			list_skins.AddRange(StoredDataSkins[item]);
+			bool hasAdmin = config.Setting.AdminSkins.TryGetValue(item, out adminList) && permission.UserHasPermission(player.UserIDString, permAdminS);
+			bool hasVip = config.Setting.VipSkins.TryGetValue(item, out vipList) && permission.UserHasPermission(player.UserIDString, permVipS);
+			
+			if(hasAdmin || hasVip)
+			{
+				List<ulong> merged = new List<ulong>((hasAdmin ? adminList.Count : 0) + (hasVip ? vipList.Count : 0) + list_skins.Count);
+				
+				if(hasAdmin) merged.AddRange(adminList);
+				if(hasVip) merged.AddRange(vipList);
+				
+				merged.AddRange(list_skins);
+				list_skins = merged;
+			}
 			
 			if(!string.IsNullOrEmpty(search))
 			{
@@ -3989,13 +4049,14 @@ namespace Oxide.Plugins
 					list_skins = list_skins.Where(skinID => StoredDataSkinsName.TryGetValue(skinID, out string name) && name.Contains(search, StringComparison.OrdinalIgnoreCase)).ToList();
 			}
 			
-			foreach(ulong skin in list_skins.Skip(Page * count))
+			for(int i = Page * count; i < list_skins.Count; i++)
 			{
+				ulong skin = list_skins[i];
 				bool isAdminSkin = _adminSkins.Contains(skin), isVipSkin = _vipSkins.Contains(skin);
 				
 			    container.Add(new CuiPanel
                 {
-                    RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = comfort ? $"{-497.5 + (x * 100)} {52.375 - (y * 100)}" : $"{-497.5 + (x * 100)} {102.375 - (y * 100)}", OffsetMax = comfort ? $"{-402.5 + (x * 100)} {147.375 - (y * 100)}" : $"{-402.5 + (x * 100)} {197.375 - (y * 100)}" },
+                    RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = tileMin[y * 10 + x], OffsetMax = tileMax[y * 10 + x] },
                     Image = { Color = s == skin ? config.GUI.ActiveBlockColor : config.GUI.BlockColor, Material = "assets/icons/greyout.mat" }
                 }, ".SkinGUI", ".Skin");
 				
@@ -4009,11 +4070,11 @@ namespace Oxide.Plugins
 						}
 					});		
 					
-				if(StoredDataSkinsName.ContainsKey(skin))
+				if(StoredDataSkinsName.TryGetValue(skin, out string skinName))
 					container.Add(new CuiLabel
 					{
 						RectTransform = { AnchorMin = "0 1", AnchorMax = "1 1", OffsetMin = "2.5 -12.5", OffsetMax = "-2.5 -2.5" },
-						Text = { Text = StoredDataSkinsName[skin], Align = TextAnchor.MiddleCenter, Font = "robotocondensed-regular.ttf", FontSize = 8, Color = "0.85 0.85 0.85 1" }
+						Text = { Text = skinName, Align = TextAnchor.MiddleCenter, Font = "robotocondensed-regular.ttf", FontSize = 8, Color = "0.85 0.85 0.85 1" }
 					}, ".Skin");
 				
 				container.Add(new CuiButton
@@ -4023,7 +4084,7 @@ namespace Oxide.Plugins
                     Text = { Text = "" }
                 }, ".Skin");
 		   		 		  						  	   		   		 		  		 			   					  	 	 
-				if(permission.UserHasPermission(player.UserIDString, permAdmin) && !_adminUiFD.Contains(player.userID))
+				if(adminUi)
 				{
 					container.Add(new CuiLabel
 					{
```

### Коммит 4 — эффекты по веткам, респаун без ToArray()

**Было.** `ccmdCategoryS` (обработчик всех кнопок меню `skin_c`) создавал три
объекта `Effect` в самом начале — на каждое нажатие любой кнопки, хотя в
любой ветке используется максимум один, а в `openkit`/`adminf`/`createkit`
ни одного. `ccmdSetting` и `page.xskinmenu` создавали по одному `Effect`
заранее, даже если у игрока выключен звук (`UseSoundE`) и эффект никуда не
уйдёт. `OnPlayerRespawned` делал `itemList.ToArray()` для трёх контейнеров
на каждый респаун — три массива на игрока.

**Стало.** O(3) объектов на нажатие → O(0..1).
* `SendEffect(player, prefab)` создаёт и отправляет эффект в месте вызова;
  три префаба — константы `FxClick`/`FxStick`/`FxSpray`. Пять строк
  `Effect x/y/z = new Effect(...)` удалены, пятнадцать `EffectNetwork.Send(x|y|z, …)`
  заменены на `SendEffect(player, …)` с тем же префабом в той же ветке —
  условия (`UseSoundE`) не тронуты.
* `ReskinContainer`: снимок содержимого контейнера в один переиспользуемый
  `List<Item>` (`_respawnItems`) вместо `ToArray()`; снимок нужен, потому
  что `SetSkinCraftGive` может заменить предмет в контейнере во время обхода.
  Порядок обхода (wear → main → belt, предметы по порядку) тот же.

Проверка: `XSkinHooks` — клик в настройках со звуком даёт ровно один эффект
клика, без звука — ни одного, `clear` — один `survey_charge_stick`; респаун
перекрашивает предмет из инвентаря, не трогает после выдачи кита и не падает
на `null`-контейнере. Golden без изменений (эффекты в транскрипт не входят,
CUI совпадает побайтно).

```diff
diff --git a/XSkinMenu.cs b/XSkinMenu.cs
index f483508..cbc6958 100644
--- a/XSkinMenu.cs
+++ b/XSkinMenu.cs
@@ -2654,7 +2654,6 @@ namespace Oxide.Plugins
 			if(Cooldowns.ContainsKey(player))
                 if(Cooldowns[player].Subtract(DateTime.Now).TotalSeconds >= 0) return;
 			
-			Effect x = new Effect("assets/bundled/prefabs/fx/notice/loot.drag.grab.fx.prefab", player, 0, new Vector3(), new Vector3());
 			
 			switch(args.GetString(0))
 			{
@@ -2771,7 +2770,7 @@ namespace Oxide.Plugins
 				}
 			}
 			
-			if(StoredData[player.userID].UseSoundE) EffectNetwork.Send(x, player.Connection);
+			if(StoredData[player.userID].UseSoundE) SendEffect(player, FxClick);
 			Cooldowns[player] = DateTime.Now.AddSeconds(0.5f);
 		}
 		private readonly Dictionary<string, Dictionary<ulong, string>> ersK = new Dictionary<string, Dictionary<ulong, string>>
@@ -2879,9 +2878,6 @@ namespace Oxide.Plugins
 			if(Cooldowns.ContainsKey(player))
                 if(Cooldowns[player].Subtract(DateTime.Now).TotalSeconds >= 0) return;
 			
-			Effect x = new Effect("assets/bundled/prefabs/fx/notice/loot.drag.grab.fx.prefab", player, 0, new Vector3(), new Vector3());
-			Effect z = new Effect("assets/bundled/prefabs/fx/weapons/survey_charge/survey_charge_stick.prefab", player, 0, new Vector3(), new Vector3());
-			Effect y = new Effect("assets/prefabs/deployable/repair bench/effects/skinchange_spraypaint.prefab", player, 0, new Vector3(), new Vector3());
 			
 			switch(args.GetString(0))
 			{
@@ -2889,7 +2885,7 @@ namespace Oxide.Plugins
 				{
 					CategoryGUI(player, args.GetInt(2));
 					ItemGUI(player, args.GetString(1));
-					if(StoredData[player.userID].UseSoundE) EffectNetwork.Send(x, player.Connection);
+					if(StoredData[player.userID].UseSoundE) SendEffect(player, FxClick);
 					
 					Cooldowns[player] = DateTime.Now.AddSeconds(0.5f);
 					break;
@@ -2907,7 +2903,7 @@ namespace Oxide.Plugins
 					else
 						SkinGUI(player, args.GetString(1));
 					
-					if(StoredData[player.userID].UseSoundE) EffectNetwork.Send(x, player.Connection);
+					if(StoredData[player.userID].UseSoundE) SendEffect(player, FxClick);
 					
 					if(!StoredData[player.userID].Comfort) CuiHelper.DestroyUi(player, ".ItemGUI");
 					
@@ -2931,7 +2927,7 @@ namespace Oxide.Plugins
 							SkinGUI(player, args.GetString(1), 0, args.GetString(2), 0);
 					}
 					
-					if(StoredData[player.userID].UseSoundE) EffectNetwork.Send(x, player.Connection);
+					if(StoredData[player.userID].UseSoundE) SendEffect(player, FxClick);
 					
 					Cooldowns[player] = DateTime.Now.AddSeconds(0.5f);
 					
@@ -2944,7 +2940,7 @@ namespace Oxide.Plugins
 					else if(args.Args.Length >= 3)
 						SetItemGUI(player, args.GetString(1), 0, args.GetBool(2));
 					
-					if(StoredData[player.userID].UseSoundE) EffectNetwork.Send(x, player.Connection);
+					if(StoredData[player.userID].UseSoundE) SendEffect(player, FxClick);
 					
 					Cooldowns[player] = DateTime.Now.AddSeconds(0.5f);
 					
@@ -3052,7 +3048,7 @@ namespace Oxide.Plugins
 							else
 								SSI(player, item, skin, shortname, false, config.Setting.ReissueActiveItem);
 							
-							EffectNetwork.Send(y, player.Connection);
+							SendEffect(player, FxSpray);
 						}
 					}
 					
@@ -3102,7 +3098,7 @@ namespace Oxide.Plugins
 						}
 					}
 					
-					EffectNetwork.Send(y, player.Connection);
+					SendEffect(player, FxSpray);
 					
 					Cooldowns[player] = DateTime.Now.AddSeconds(1.5f); // Don't touch here!!!   |   Здесь не трогать!!! =)
 					break;
@@ -3119,7 +3115,7 @@ namespace Oxide.Plugins
 					if(StoredData[player.userID].ChangeSCL) SetSkinItem(player, item, 0);
 					if(config.GUI.MainSkin) ItemGUI(player, args.GetString(3), args.GetInt(4), item);
 					
-					EffectNetwork.Send(z, player.Connection);
+					SendEffect(player, FxStick);
 					
 					Cooldowns[player] = DateTime.Now.AddSeconds(0.5f);
 					break;
@@ -3136,7 +3132,7 @@ namespace Oxide.Plugins
 					MarkDirty(player.userID);
 					
 					GUI(player);
-					EffectNetwork.Send(z, player.Connection);
+					SendEffect(player, FxStick);
 					
 					Cooldowns[player] = DateTime.Now.AddSeconds(2.5f);
 					break;
@@ -3160,7 +3156,7 @@ namespace Oxide.Plugins
 				{
 					ZoomGUI(player, args.GetInt(1), args.GetULong(2), args.GetBool(3));
 					
-					if(StoredData[player.userID].UseSoundE) EffectNetwork.Send(x, player.Connection);
+					if(StoredData[player.userID].UseSoundE) SendEffect(player, FxClick);
 					
 					Cooldowns[player] = DateTime.Now.AddSeconds(0.5f);
 					break;
@@ -3178,7 +3174,7 @@ namespace Oxide.Plugins
 						MarkDirty(player.userID);
 							
 						CustomKitsGUI(player, Page);
-						EffectNetwork.Send(z, player.Connection);
+						SendEffect(player, FxStick);
 					}
 					
 					Cooldowns[player] = DateTime.Now.AddSeconds(0.5f);
@@ -3189,14 +3185,14 @@ namespace Oxide.Plugins
 					if(config.Setting.EnableDefaultKits || config.Setting.EnableCustomKits)
 						KitInfoGUI(player, args.GetString(1), args.GetString(2).Replace("'", ""));
 					
-					if(StoredData[player.userID].UseSoundE) EffectNetwork.Send(x, player.Connection);
+					if(StoredData[player.userID].UseSoundE) SendEffect(player, FxClick);
 					break;
 				}
 				case "createkitui":
 				{
 					CreateKitGUI(player);
 					
-					if(StoredData[player.userID].UseSoundE) EffectNetwork.Send(x, player.Connection);
+					if(StoredData[player.userID].UseSoundE) SendEffect(player, FxClick);
 					break;
 				}
 				case "createkit":
@@ -3263,7 +3259,7 @@ namespace Oxide.Plugins
 					if(offChangeSG)
 						StoredData[player.userID].ChangeSG = true;
 					
-					EffectNetwork.Send(y, player.Connection);
+					SendEffect(player, FxSpray);
 					
 					Cooldowns[player] = DateTime.Now.AddSeconds(2.5f); // Don't touch here!!!   |   Здесь не трогать!!! =)
 					break;
@@ -4361,7 +4357,6 @@ namespace Oxide.Plugins
 		private void ccmdPage(ConsoleSystem.Arg args)
 		{
 			BasePlayer player = args.Player();
-			Effect x = new Effect("assets/bundled/prefabs/fx/notice/loot.drag.grab.fx.prefab", player, 0, new Vector3(), new Vector3());
 			
 			string item = args.GetString(1);
 			int Page = args.GetInt(2);
@@ -4405,7 +4400,7 @@ namespace Oxide.Plugins
 				}
 			}
 			
-			if(StoredData[player.userID].UseSoundE) EffectNetwork.Send(x, player.Connection);
+			if(StoredData[player.userID].UseSoundE) SendEffect(player, FxClick);
 		}
 		protected override void LoadDefaultConfig() => config = SkinConfig.GetNewConfiguration();
 		
@@ -4553,24 +4548,39 @@ namespace Oxide.Plugins
 		}
 		private const string permPlayerAdd = "xskinmenu.playeradd";
 		
+		private const string FxClick = "assets/bundled/prefabs/fx/notice/loot.drag.grab.fx.prefab";
+		private const string FxStick = "assets/bundled/prefabs/fx/weapons/survey_charge/survey_charge_stick.prefab";
+		private const string FxSpray = "assets/prefabs/deployable/repair bench/effects/skinchange_spraypaint.prefab";
+		
+		// Эффект создаётся только в той ветке команды, где он действительно отправляется,
+		// а не три штуки на каждый вызов skin_c.
+		private void SendEffect(BasePlayer player, string prefab) => EffectNetwork.Send(new Effect(prefab, player, 0, new Vector3(), new Vector3()), player.Connection);
+		
+		private readonly List<Item> _respawnItems = new List<Item>();
+		
+		// Снимок содержимого нужен: SetSkinCraftGive может заменить предмет в контейнере.
+		// Один переиспользуемый список вместо ToArray() на каждый из трёх контейнеров.
+		private void ReskinContainer(BasePlayer player, Data data, ItemContainer container)
+		{
+			if(container == null) return;
+			
+			_respawnItems.Clear();
+			_respawnItems.AddRange(container.itemList);
+			
+			foreach(Item item in _respawnItems)
+				if(data.Skins.ContainsKey(item.info.shortname)) 
+					SetSkinCraftGive(player, item, true);
+			
+			_respawnItems.Clear();
+		}
+		
 		private void OnPlayerRespawned(BasePlayer player)
 		{
 			if(StoredData.TryGetValue(player.userID, out Data data) && !_removeATC.Contains(player.userID))
 			{
-				if(player.inventory.containerWear != null)
-					foreach(Item item in player.inventory.containerWear.itemList.ToArray())
-						if(data.Skins.ContainsKey(item.info.shortname)) 
-							SetSkinCraftGive(player, item, true);
-				
-				if(player.inventory.containerMain != null)
-					foreach(Item item in player.inventory.containerMain.itemList.ToArray())
-						if(data.Skins.ContainsKey(item.info.shortname)) 
-							SetSkinCraftGive(player, item, true);
-				
-				if(player.inventory.containerBelt != null)
-					foreach(Item item in player.inventory.containerBelt.itemList.ToArray())
-						if(data.Skins.ContainsKey(item.info.shortname)) 
-							SetSkinCraftGive(player, item, true);
+				ReskinContainer(player, data, player.inventory.containerWear);
+				ReskinContainer(player, data, player.inventory.containerMain);
+				ReskinContainer(player, data, player.inventory.containerBelt);
 			}
 			
 			RemoveATC(player.userID);
```

### Коммит 5 — выбор скина: перерисовка двух плиток вместо всей сетки

**Было.** Нажатие на скин (`skin_c setskin`) заново строило и отправляло весь
экран списка скинов: до 40 плиток по 5–9 элементов (в admin-режиме больше),
плюс в comfort-режиме ещё и `ItemGUI`. Из всего этого меняются ровно две
плитки: у старого выбора цвет фона `ActiveBlockColor → BlockColor`, у нового
— наоборот. Все плитки назывались одинаково `.Skin`, поэтому заменить одну
было нельзя — только всё поддерево `.SkinGUI`.

**Стало.** O(плиток на странице) → O(2) элементов-плиток на клик.
* Плитка выделена в `AddSkinTile` (один код для полной отрисовки и для
  замены), список скинов — в `SkinList` (без копии, как в коммите 3).
* Имя плитки — `.Skin{slot}` по позиции на странице (0..39, таблица
  `_tileNames` строится один раз): уникально в пределах сетки. Игроку имена
  не видны; никакой другой код и плагины по `.Skin` не обращаются.
* `SkinGUISelect(player, item, oldSkin, newSkin, …)`: та же выборка списка,
  что у полной отрисовки; на текущей странице находятся плитки старого и
  нового выбора и заменяются через `destroyUi` того же имени в одном `AddUi`.
  Если старый выбор на другой странице или отфильтрован поиском — заменяется
  только новая плитка; повторный клик по выбранному — одна плитка. В
  comfort-режиме `ItemGUI` по-прежнему перерисовывается целиком (11–14
  плиток по 3–4 элемента — там меняется иконка предмета и кнопка очистки).
* Ссылка на старый выбор берётся до записи `Skins[item] = skin`.

Что не совпадает с оригиналом намеренно: в обычном (не comfort) режиме
авторский `setskin` рисовал сетку с категорией `"null"`, а `skin`/`page`/
`searchskin` — с той, что пришла в команде; в кликах обычного режима это
всегда тоже `"null"`, так что состояние экрана после клика совпадает. В
comfort-режиме категория и страница берутся из аргументов той же кнопки.

Цифры из golden (сумма по 12 состояниям, JSON `AddUi`): `click setskin`
1248 КБ / 4882 элементов → 217 КБ / 832 (в comfort-половине это в основном
`ItemGUI`); в обычном режиме клик стоит две плитки; `setskin` на другой
странице/в поиске 1072 КБ → 185 КБ; выбор admin-скина 799 КБ → 118 КБ.

Проверка. Golden расширен: право `xskinmenu.skinchange` и реальные id скинов
(раньше `click setskin` и `click clear` были пустыми — команда выходила по
праву), семь вариантов выбора (обычный, назад, тот же, на другой странице,
в поиске, admin-скин и обратно) в каждом из 12 состояний. Эти 84 экрана
сравниваются по итоговому состоянию экрана (`XSkinScreen.cs`, виртуальный
клиент), остальные 324 — побайтно. Две намеренные поломки (неверный цвет
только в частичном пути; пропуск старой плитки) компаратор ловит — 78 и 54
расхождения соответственно.

```diff
diff --git a/XSkinMenu.cs b/XSkinMenu.cs
index cbc6958..5bbdb9e 100644
--- a/XSkinMenu.cs
+++ b/XSkinMenu.cs
@@ -3066,6 +3066,8 @@ namespace Oxide.Plugins
 					if(_vipSkins.Contains(skin) && !permission.UserHasPermission(player.UserIDString, permVipS)) return;
 					if(!(StoredDataSkins[item].Contains(skin) || _adminAndVipSkins.Contains(skin))) return;
 					
+					ulong oldSkin = StoredData[player.userID].Skins[item];
+					
 					StoredData[player.userID].Skins[item] = skin;
 					MarkDirty(player.userID);
 					
@@ -3084,16 +3086,16 @@ namespace Oxide.Plugins
 								ItemGUI(player, category, page, item);
 								
 								if(args.Args.Length >= 7)
-									SkinGUI(player, item, args.GetInt(3), category, page, string.Join(" ", args.Args.Skip(6)).ToLower());
+									SkinGUISelect(player, item, oldSkin, skin, args.GetInt(3), category, page, string.Join(" ", args.Args.Skip(6)).ToLower());
 								else
-									SkinGUI(player, item, args.GetInt(3), category, page);
+									SkinGUISelect(player, item, oldSkin, skin, args.GetInt(3), category, page);
 							}
 							else
 							{
 								if(args.Args.Length >= 5)
-									SkinGUI(player, item, args.GetInt(3), "null", 0, string.Join(" ", args.Args.Skip(4)).ToLower());
+									SkinGUISelect(player, item, oldSkin, skin, args.GetInt(3), "null", 0, string.Join(" ", args.Args.Skip(4)).ToLower());
 								else
-									SkinGUI(player, item, args.GetInt(3));
+									SkinGUISelect(player, item, oldSkin, skin, args.GetInt(3));
 							}
 						}
 					}
@@ -3967,7 +3969,7 @@ namespace Oxide.Plugins
 		
 		// Смещения плиток не зависят от игрока и состояния: те же строки, что давали интерполяции
 		// в SkinGUI/ItemGUI, но посчитанные один раз вместо 80 форматирований double на каждый экран.
-		private string[] _tileMinD, _tileMaxD, _skinTileMinC, _skinTileMaxC, _itemTileMinC, _itemTileMaxC, _itemTileMinP, _itemTileMaxP;
+		private string[] _tileMinD, _tileMaxD, _skinTileMinC, _skinTileMaxC, _itemTileMinC, _itemTileMaxC, _itemTileMinP, _itemTileMaxP, _tileNames;
 		
 		private void BuildTileOffsets()
 		{
@@ -3986,6 +3988,11 @@ namespace Oxide.Plugins
 				_skinTileMaxC[i] = $"{-402.5 + (x * 100)} {147.375 - (y * 100)}";
 			}
 			
+			_tileNames = new string[40];
+			
+			for(int i = 0; i < 40; i++)
+				_tileNames[i] = ".Skin" + i;
+			
 			_itemTileMinC = new string[14];
 			_itemTileMaxC = new string[14];
 			_itemTileMinP = new string[14];
@@ -4000,27 +4007,10 @@ namespace Oxide.Plugins
 			}
 		}
 		
-		private void SkinGUI(BasePlayer player, string item, int Page = 0, string category = "null", int PageC = 0, string search = "")
+				// Список скинов предмета для сетки: ссылка на StoredDataSkins[item] без копии; объединённый
+		// список создаётся только когда у предмета есть admin/vip-скины и у игрока есть право на них.
+		private List<ulong> SkinList(BasePlayer player, string item, string search)
 		{
-			Data data = StoredData[player.userID];
-			bool comfort = data.Comfort;
-			
-            CuiElementContainer container = new CuiElementContainer();
-			
-			container.Add(new CuiPanel
-            {
-                RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = "-502.5 -228.25", OffsetMax = comfort ? "502.5 77.5" : "502.5 177.5" },
-                Image = { Color = "0 0 0 0" }
-            }, ".SGUI", ".SkinGUI", ".SkinGUI");
-			
-			int x = 0, y = 0, count = comfort ? 30 : 40, yN = comfort ? 3 : 4;
-			ulong s = data.Skins[item];
-			int itemid = _itemsId[item];
-			bool adminUi = permission.UserHasPermission(player.UserIDString, permAdmin) && !_adminUiFD.Contains(player.userID);
-			
-			if(_tileMinD == null) BuildTileOffsets();
-			string[] tileMin = comfort ? _skinTileMinC : _tileMinD, tileMax = comfort ? _skinTileMaxC : _tileMaxD;
-			
 			List<ulong> list_skins = StoredDataSkins[item], adminList, vipList;
 			
 			bool hasAdmin = config.Setting.AdminSkins.TryGetValue(item, out adminList) && permission.UserHasPermission(player.UserIDString, permAdminS);
@@ -4045,113 +4035,167 @@ namespace Oxide.Plugins
 					list_skins = list_skins.Where(skinID => StoredDataSkinsName.TryGetValue(skinID, out string name) && name.Contains(search, StringComparison.OrdinalIgnoreCase)).ToList();
 			}
 			
-			for(int i = Page * count; i < list_skins.Count; i++)
-			{
-				ulong skin = list_skins[i];
-				bool isAdminSkin = _adminSkins.Contains(skin), isVipSkin = _vipSkins.Contains(skin);
+			return list_skins;
+		}
+		
+		// Одна плитка сетки скинов. slot - позиция на странице (0..39), имя плитки .Skin{slot}:
+		// уникально в пределах сетки, чтобы при выборе скина можно было заменить только её.
+		private void AddSkinTile(CuiElementContainer container, BasePlayer player, string item, int itemid, ulong skin, ulong s, int slot, bool comfort, string[] tileMin, string[] tileMax, bool adminUi, int Page, string category, int PageC, string search, bool replace)
+		{
+			bool isAdminSkin = _adminSkins.Contains(skin), isVipSkin = _vipSkins.Contains(skin);
+			string tile = _tileNames[slot];
+			
+		    container.Add(new CuiPanel
+            {
+                RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = tileMin[slot], OffsetMax = tileMax[slot] },
+                Image = { Color = s == skin ? config.GUI.ActiveBlockColor : config.GUI.BlockColor, Material = "assets/icons/greyout.mat" }
+            }, ".SkinGUI", tile, replace ? tile : null);
+			
+				container.Add(new CuiElement
+				{
+					Parent = tile,
+					Components =
+					{
+						GetImageComponent(itemid, skin),
+						new CuiRectTransformComponent { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMin = "10 5", OffsetMax = "-10 -15" }
+					}
+				});		
 				
-			    container.Add(new CuiPanel
-                {
-                    RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = tileMin[y * 10 + x], OffsetMax = tileMax[y * 10 + x] },
-                    Image = { Color = s == skin ? config.GUI.ActiveBlockColor : config.GUI.BlockColor, Material = "assets/icons/greyout.mat" }
-                }, ".SkinGUI", ".Skin");
+			if(StoredDataSkinsName.TryGetValue(skin, out string skinName))
+				container.Add(new CuiLabel
+				{
+					RectTransform = { AnchorMin = "0 1", AnchorMax = "1 1", OffsetMin = "2.5 -12.5", OffsetMax = "-2.5 -2.5" },
+					Text = { Text = skinName, Align = TextAnchor.MiddleCenter, Font = "robotocondensed-regular.ttf", FontSize = 8, Color = "0.85 0.85 0.85 1" }
+				}, tile);
+			
+			container.Add(new CuiButton
+            {
+                RectTransform = { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMax = "0 0" },
+                Button = { Color = "0 0 0 0", Command = comfort ? $"skin_c setskin {item} {skin} {Page} {category} {PageC} {search}" : $"skin_c setskin {item} {skin} {Page} {search}" },
+                Text = { Text = "" }
+            }, tile);
+			
+			if(adminUi)
+			{
+				container.Add(new CuiLabel
+				{
+					RectTransform = { AnchorMin = "0 0", AnchorMax = "1 0", OffsetMin = "2.5 0.5", OffsetMax = "-2.5 10.5" },
+					Text = { Text = $"{skin}", Align = TextAnchor.MiddleCenter, Font = "robotocondensed-regular.ttf", FontSize = 8, Color = "0.85 0.85 0.85 1" }
+				}, tile);
 				
-					container.Add(new CuiElement
-					{
-						Parent = ".Skin",
-						Components =
-						{
-							GetImageComponent(itemid, skin),
-							new CuiRectTransformComponent { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMin = "10 5", OffsetMax = "-10 -15" }
-						}
-					});		
-					
-				if(StoredDataSkinsName.TryGetValue(skin, out string skinName))
-					container.Add(new CuiLabel
+				if(_adminAndVipSkins.Contains(skin))
+					container.Add(new CuiButton
 					{
-						RectTransform = { AnchorMin = "0 1", AnchorMax = "1 1", OffsetMin = "2.5 -12.5", OffsetMax = "-2.5 -2.5" },
-						Text = { Text = skinName, Align = TextAnchor.MiddleCenter, Font = "robotocondensed-regular.ttf", FontSize = 8, Color = "0.85 0.85 0.85 1" }
-					}, ".Skin");
-				
-				container.Add(new CuiButton
-                {
-                    RectTransform = { AnchorMin = "0 0", AnchorMax = "1 1", OffsetMax = "0 0" },
-                    Button = { Color = "0 0 0 0", Command = comfort ? $"skin_c setskin {item} {skin} {Page} {category} {PageC} {search}" : $"skin_c setskin {item} {skin} {Page} {search}" },
-                    Text = { Text = "" }
-                }, ".Skin");
-		   		 		  						  	   		   		 		  		 			   					  	 	 
-				if(adminUi)
+						RectTransform = { AnchorMin = "1 1", AnchorMax = "1 1", OffsetMin = "-17 -27", OffsetMax = "-5 -15" },
+						Button = { Color = "1 1 1 0.75060739", Sprite = "assets/icons/rotate.png", Command = $"xskin refresh_ui {item} {skin} {Page} {(isAdminSkin ? "admin_to_default" : isVipSkin ? "vip_to_default" : "")} {category}" },
+						Text = { Text = "" }
+					}, tile);
+				else
 				{
-					container.Add(new CuiLabel
+					container.Add(new CuiButton
 					{
-						RectTransform = { AnchorMin = "0 0", AnchorMax = "1 0", OffsetMin = "2.5 0.5", OffsetMax = "-2.5 10.5" },
-						Text = { Text = $"{skin}", Align = TextAnchor.MiddleCenter, Font = "robotocondensed-regular.ttf", FontSize = 8, Color = "0.85 0.85 0.85 1" }
-					}, ".Skin");
+						RectTransform = { AnchorMin = "1 1", AnchorMax = "1 1", OffsetMin = "-17 -27", OffsetMax = "-5 -15" },
+						Button = { Color = "0.9 0 0 1", Sprite = "assets/icons/rotate.png", Command = $"xskin refresh_ui {item} {skin} {Page} default_to_admin {category}" },
+						Text = { Text = "" }
+					}, tile);						
 					
-					if(_adminAndVipSkins.Contains(skin))
-						container.Add(new CuiButton
-						{
-							RectTransform = { AnchorMin = "1 1", AnchorMax = "1 1", OffsetMin = "-17 -27", OffsetMax = "-5 -15" },
-							Button = { Color = "1 1 1 0.75060739", Sprite = "assets/icons/rotate.png", Command = $"xskin refresh_ui {item} {skin} {Page} {(isAdminSkin ? "admin_to_default" : isVipSkin ? "vip_to_default" : "")} {category}" },
-							Text = { Text = "" }
-						}, ".Skin");
-					else
+					container.Add(new CuiButton
 					{
-						container.Add(new CuiButton
-						{
-							RectTransform = { AnchorMin = "1 1", AnchorMax = "1 1", OffsetMin = "-17 -27", OffsetMax = "-5 -15" },
-							Button = { Color = "0.9 0 0 1", Sprite = "assets/icons/rotate.png", Command = $"xskin refresh_ui {item} {skin} {Page} default_to_admin {category}" },
-							Text = { Text = "" }
-						}, ".Skin");						
-						
-						container.Add(new CuiButton
-						{
-							RectTransform = { AnchorMin = "1 1", AnchorMax = "1 1", OffsetMin = "-17 -42", OffsetMax = "-5 -30" },
-							Button = { Color = "0.9 0.9 0 1", Sprite = "assets/icons/rotate.png", Command = $"xskin refresh_ui {item} {skin} {Page} default_to_vip {category}" },
-							Text = { Text = "" }
-						}, ".Skin");
-					}
-					
-				    container.Add(new CuiButton
-                    {
-                        RectTransform = { AnchorMin = "1 0", AnchorMax = "1 0", OffsetMin = "-20 5", OffsetMax = "-5 20" },
-                        Button = { Color = "1 1 1 0.75060739", Sprite = "assets/icons/clear.png", Command = $"xskin remove_ui {item} {skin} {Page} {(isAdminSkin ? "admin" : isVipSkin ? "vip" : "default")} {category}" },
-                        Text = { Text = "" }
-                    }, ".Skin");
+						RectTransform = { AnchorMin = "1 1", AnchorMax = "1 1", OffsetMin = "-17 -42", OffsetMax = "-5 -30" },
+						Button = { Color = "0.9 0.9 0 1", Sprite = "assets/icons/rotate.png", Command = $"xskin refresh_ui {item} {skin} {Page} default_to_vip {category}" },
+						Text = { Text = "" }
+					}, tile);
 				}
 				
-				container.Add(new CuiButton
+			    container.Add(new CuiButton
                 {
-                    RectTransform = { AnchorMin = "0 0", AnchorMax = "0 0", OffsetMin = "5 5", OffsetMax = "20 20" },
-                    Button = { Color = "1 1 1 0.75060739", Sprite = config.GUI.IconZoom, Command = $"skin_c zoomskin {itemid} {skin} false" },
+                    RectTransform = { AnchorMin = "1 0", AnchorMax = "1 0", OffsetMin = "-20 5", OffsetMax = "-5 20" },
+                    Button = { Color = "1 1 1 0.75060739", Sprite = "assets/icons/clear.png", Command = $"xskin remove_ui {item} {skin} {Page} {(isAdminSkin ? "admin" : isVipSkin ? "vip" : "default")} {category}" },
                     Text = { Text = "" }
-                }, ".Skin");
-				
-				if(isAdminSkin)
-				    container.Add(new CuiPanel
-                    {
-                        RectTransform = { AnchorMin = "0 1", AnchorMax = "0 1", OffsetMin = "2.5 -25", OffsetMax = "12.5 -15" },
-                        Image = { Color = "0.9 0 0 1", Sprite = "assets/icons/circle_closed.png" },
-                    }, ".Skin");
-				else if(isVipSkin)
-				    container.Add(new CuiPanel
-                    {
-                        RectTransform = { AnchorMin = "0 1", AnchorMax = "0 1", OffsetMin = "2.5 -25", OffsetMax = "12.5 -15" },
-                        Image = { Color = "0.9 0.9 0 1", Sprite = "assets/icons/circle_closed.png" },
-                    }, ".Skin");
+                }, tile);
+			}
+			
+			container.Add(new CuiButton
+            {
+                RectTransform = { AnchorMin = "0 0", AnchorMax = "0 0", OffsetMin = "5 5", OffsetMax = "20 20" },
+                Button = { Color = "1 1 1 0.75060739", Sprite = config.GUI.IconZoom, Command = $"skin_c zoomskin {itemid} {skin} false" },
+                Text = { Text = "" }
+            }, tile);
+			
+			if(isAdminSkin)
+			    container.Add(new CuiPanel
+                {
+                    RectTransform = { AnchorMin = "0 1", AnchorMax = "0 1", OffsetMin = "2.5 -25", OffsetMax = "12.5 -15" },
+                    Image = { Color = "0.9 0 0 1", Sprite = "assets/icons/circle_closed.png" },
+                }, tile);
+			else if(isVipSkin)
+			    container.Add(new CuiPanel
+                {
+                    RectTransform = { AnchorMin = "0 1", AnchorMax = "0 1", OffsetMin = "2.5 -25", OffsetMax = "12.5 -15" },
+                    Image = { Color = "0.9 0.9 0 1", Sprite = "assets/icons/circle_closed.png" },
+                }, tile);
+		}
+		
+		// Выбор скина: вместо перерисовки всей сетки (до 40 плиток) заменяются только плитки старого и
+		// нового выбора - у остальных ничего не изменилось. Результат на экране тот же.
+		private void SkinGUISelect(BasePlayer player, string item, ulong oldSkin, ulong newSkin, int Page, string category = "null", int PageC = 0, string search = "")
+		{
+			Data data = StoredData[player.userID];
+			bool comfort = data.Comfort;
+			
+			int count = comfort ? 30 : 40;
+			ulong s = data.Skins[item];
+			int itemid = _itemsId[item];
+			bool adminUi = permission.UserHasPermission(player.UserIDString, permAdmin) && !_adminUiFD.Contains(player.userID);
+			
+			if(_tileMinD == null) BuildTileOffsets();
+			string[] tileMin = comfort ? _skinTileMinC : _tileMinD, tileMax = comfort ? _skinTileMaxC : _tileMaxD;
+			
+			List<ulong> list_skins = SkinList(player, item, search);
+			int end = Math.Min(list_skins.Count, (Page + 1) * count);
+			CuiElementContainer container = null;
+			
+			for(int i = Page * count; i < end; i++)
+			{
+				ulong skin = list_skins[i];
 				
-				x++;
+				if(skin != oldSkin && skin != newSkin) continue;
+				if(container == null) container = new CuiElementContainer();
 				
-				if(x == 10)
-				{
-					x = 0;
-					y++;
-					
-					if(y == yN)
-						break;
-				}
+				AddSkinTile(container, player, item, itemid, skin, s, i - Page * count, comfort, tileMin, tileMax, adminUi, Page, category, PageC, search, true);
 			}
 			
+			if(container != null)
+				CuiHelper.AddUi(player, container);
+		}
+		
+		private void SkinGUI(BasePlayer player, string item, int Page = 0, string category = "null", int PageC = 0, string search = "")
+		{
+			Data data = StoredData[player.userID];
+			bool comfort = data.Comfort;
+			
+            CuiElementContainer container = new CuiElementContainer();
+			
+			container.Add(new CuiPanel
+            {
+                RectTransform = { AnchorMin = "0.5 0.5", AnchorMax = "0.5 0.5", OffsetMin = "-502.5 -228.25", OffsetMax = comfort ? "502.5 77.5" : "502.5 177.5" },
+                Image = { Color = "0 0 0 0" }
+            }, ".SGUI", ".SkinGUI", ".SkinGUI");
+			
+			int count = comfort ? 30 : 40;
+			ulong s = data.Skins[item];
+			int itemid = _itemsId[item];
+			bool adminUi = permission.UserHasPermission(player.UserIDString, permAdmin) && !_adminUiFD.Contains(player.userID);
+			
+			if(_tileMinD == null) BuildTileOffsets();
+			string[] tileMin = comfort ? _skinTileMinC : _tileMinD, tileMax = comfort ? _skinTileMaxC : _tileMaxD;
+			
+			List<ulong> list_skins = SkinList(player, item, search);
+			int end = Math.Min(list_skins.Count, (Page + 1) * count);
+			
+			for(int i = Page * count; i < end; i++)
+				AddSkinTile(container, player, item, itemid, list_skins[i], s, i - Page * count, comfort, tileMin, tileMax, adminUi, Page, category, PageC, search, false);
+			
 			bool back = Page != 0;
 			bool next = list_skins.Count > ((Page + 1) * count);
 			
```

## Конфиги: списки удваивались при каждой загрузке (ChatSystem, AccountSystem)

**Причина.** Поле конфига инициализируется дефолтами в объявлении класса
(`News = DefaultNews()`, `LootRules = DefaultLootRules()`), а
`Config.ReadObject<T>()` у Oxide — это `JsonConvert.DeserializeObject<T>` с
настройками по умолчанию: `ObjectCreationHandling.Auto` не заменяет уже
существующий список, а дописывает в него элементы из файла. Каждая
загрузка: дефолты + содержимое файла → 2 → 4 → 6 …, и `SaveConfig()` в конце
`LoadConfig()` закрепляет результат в файле.

**Исправление.**
* `[JsonProperty(..., ObjectCreationHandling = ObjectCreationHandling.Replace)]`
  на всех коллекциях конфигов: `ChatSystem.News`, `AccountSystem.LootRules` и
  `AccountSystem.Exp.GatherPer100` (словарь — дубли ключей невозможны, но
  без Replace удалённый админом ключ возвращался бы из дефолтов),
  `Stacks.Categories` и `Stacks.Stacks` (инициализаторы пустые, ошибки не
  было — атрибут на будущее). `Rates` конфига не имеет; в `ServerMenu` коллекций
  в конфиге нет; у `XSkinMenu` (авторский) инициализаторы пустые, дефолты
  задаются в `GetNewConfiguration()` — не подвержен.
* Одноразовая чистка уже раздутых файлов: при загрузке из `News` и `LootRules`
  удаляются точные повторы (все поля равны), первое вхождение остаётся, в
  консоль пишется количество удалённых; порядок правил не меняется.

**Тест** (`tools/plugincheck/harness/ConfigCheck.cs`): заглушка конфига
стала настоящим Newtonsoft round-trip; три загрузки подряд — размеры всех
коллекций конфига не меняются и равны дефолтам; файл с продублированным ×4
списком после загрузки содержит только уникальные записи. До исправления
тест давал `ChatSystem.News` 2/4/6 и `AccountSystem.LootRules` 20/40/60.

```diff
diff --git a/AccountSystem.cs b/AccountSystem.cs
index 4de39cb..d851890 100644
--- a/AccountSystem.cs
+++ b/AccountSystem.cs
@@ -70,7 +70,8 @@ namespace Oxide.Plugins
             [JsonProperty("EXP за действия")]
             public ExpConfig Exp = new ExpConfig();
 
-            [JsonProperty("Правила EXP за контейнеры (первое совпадение сверху вниз)")]
+            // Replace: иначе Newtonsoft дописывает правила из файла к дефолтным из инициализатора
+            [JsonProperty("Правила EXP за контейнеры (первое совпадение сверху вниз)", ObjectCreationHandling = ObjectCreationHandling.Replace)]
             public List<LootRule> LootRules = DefaultLootRules();
 
             [JsonProperty("Сообщение при повышении уровня")]
@@ -169,7 +170,7 @@ namespace Oxide.Plugins
             [JsonProperty("EXP за неизвестный мировой loot-контейнер")]
             public int DefaultLootContainer = 4;
 
-            [JsonProperty("EXP за каждые 100 добытых единиц ресурса")]
+            [JsonProperty("EXP за каждые 100 добытых единиц ресурса", ObjectCreationHandling = ObjectCreationHandling.Replace)]
             public Dictionary<string, int> GatherPer100 = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase)
             {
                 ["wood"] = 5,
@@ -226,6 +227,11 @@ namespace Oxide.Plugins
             if (_config.LootRules == null || _config.LootRules.Count == 0)
                 _config.LootRules = ConfigData.DefaultLootRules();
 
+            // Файлы, уже раздутые старой ошибкой (правила дописывались к дефолтным при каждой загрузке):
+            // убираем точные повторы, первое вхождение остаётся - порядок «первое совпадение сверху вниз» не меняется.
+            int duplicates = RemoveDuplicateLootRules(_config.LootRules);
+            if (duplicates > 0) PrintWarning($"AccountSystem: из конфига удалено {duplicates} повторяющихся правил EXP за контейнеры.");
+
             _config.SaveIntervalSeconds = Mathf.Clamp(_config.SaveIntervalSeconds, 30f, 1800f);
             _config.SessionFlushSeconds = Mathf.Clamp(_config.SessionFlushSeconds, 60f, 3600f);
             // Миграция старого баланса 1.0.2 -> x1000-safe.
@@ -258,6 +264,31 @@ namespace Oxide.Plugins
             Config.WriteObject(_config, true);
         }
 
+        private static int RemoveDuplicateLootRules(List<LootRule> rules)
+        {
+            int removed = 0;
+
+            for (int i = 1; i < rules.Count; i++)
+            {
+                LootRule a = rules[i];
+                bool duplicate = false;
+
+                for (int j = 0; j < i && !duplicate; j++)
+                {
+                    LootRule b = rules[j];
+                    duplicate = a.Contains == b.Contains && a.Exp == b.Exp;
+                }
+
+                if (duplicate)
+                {
+                    rules.RemoveAt(i--);
+                    removed++;
+                }
+            }
+
+            return removed;
+        }
+
         #endregion
 
         #region Data
diff --git a/ChatSystem.cs b/ChatSystem.cs
index 29f6259..07a1802 100644
--- a/ChatSystem.cs
+++ b/ChatSystem.cs
@@ -37,7 +37,8 @@ namespace Oxide.Plugins
             [JsonProperty("Новости в случайном порядке")]
             public bool NewsRandomOrder;
 
-            [JsonProperty("Новости")]
+            // Replace: иначе Newtonsoft дописывает новости из файла к дефолтным (2 -> 4 -> 8 при каждой загрузке)
+            [JsonProperty("Новости", ObjectCreationHandling = ObjectCreationHandling.Replace)]
             public List<NewsEntry> News = DefaultNews();
 
             [JsonProperty("Звук личного сообщения")]
@@ -98,6 +99,11 @@ namespace Oxide.Plugins
             }
 
             if (_config.News == null) _config.News = ConfigData.DefaultNews();
+
+            // Файлы, уже раздутые старой ошибкой (новости дописывались к дефолтным при каждой загрузке):
+            // убираем точные повторы, первое вхождение остаётся.
+            int duplicates = RemoveDuplicateNews(_config.News);
+            if (duplicates > 0) PrintWarning($"ChatSystem: из конфига удалено {duplicates} повторяющихся новостей.");
             if (string.IsNullOrEmpty(_config.Prefix)) _config.Prefix = "[СЕРВЕР]";
             if (string.IsNullOrEmpty(_config.PrefixHex)) _config.PrefixHex = "#65A30D";
             if (string.IsNullOrEmpty(_config.PmSound)) _config.PmSound = "assets/bundled/prefabs/fx/invite_notice.prefab";
@@ -114,6 +120,31 @@ namespace Oxide.Plugins
             Config.WriteObject(_config, true);
         }
 
+        private static int RemoveDuplicateNews(List<NewsEntry> news)
+        {
+            int removed = 0;
+
+            for (int i = 1; i < news.Count; i++)
+            {
+                NewsEntry a = news[i];
+                bool duplicate = false;
+
+                for (int j = 0; j < i && !duplicate; j++)
+                {
+                    NewsEntry b = news[j];
+                    duplicate = a.Text == b.Text && a.Prefix == b.Prefix && a.Hex == b.Hex;
+                }
+
+                if (duplicate)
+                {
+                    news.RemoveAt(i--);
+                    removed++;
+                }
+            }
+
+            return removed;
+        }
+
         private void OnServerInitialized()
         {
             RestartNewsTimer();
diff --git a/Stacks.cs b/Stacks.cs
index 19d3875..38cbd6d 100644
--- a/Stacks.cs
+++ b/Stacks.cs
@@ -84,10 +84,11 @@ namespace Oxide.Plugins
             [JsonProperty(PropertyName = "Глобальный множитель стаков (0 = выкл)")]
             public int Multiplier = 0;
 
-            [JsonProperty(PropertyName = "Множитель по категориям (0 = использовать глобальный)")]
+            // Replace: словарь из файла должен заменять инициализатор поля, а не дописываться к нему
+            [JsonProperty(PropertyName = "Множитель по категориям (0 = использовать глобальный)", ObjectCreationHandling = ObjectCreationHandling.Replace)]
             public Dictionary<string, int> Categories = new();
 
-            [JsonProperty(PropertyName = "Точечные стаки по предмету (shortname - значение), приоритет над категориями")]
+            [JsonProperty(PropertyName = "Точечные стаки по предмету (shortname - значение), приоритет над категориями", ObjectCreationHandling = ObjectCreationHandling.Replace)]
             public Dictionary<string, int> Stacks = new();
 
             public VersionNumber Version = new VersionNumber(0, 0, 1);
```

## InstantCraft 0.0.1 — крафт целиком в момент постановки

**Почему старая версия выдавала по одному предмету за цикл** (Assembly-CSharp.cs):
* `BasePlayer.InventoryUpdate` :81845 → `inventory.ServerUpdate(0.1f)`; запускается
  `InvokeRepeating(InventoryUpdate, 1f, 0.1f * Random(0.99..1.01))` :81728 — раз в ~0,1 с.
* `PlayerInventory.ServerUpdate` :169523 → `crafting.ServerUpdate(delta)` :169528
  (только если не спит и не переносится).
* `ItemCrafter.ServerUpdate` :365569–365617 за один вызов делает ровно один шаг
  первой задачи очереди: `endTime == 0` → старт (`endTime = now + GetScaledDuration`,
  `note.craft_start`) и выход :365585–365601; иначе, если `endTime <= now` →
  **один** `FinishCrafting` :365605, `amount--`, и либо `RemoveFirst`, либо
  `endTime = 0` :365606–365614. При `blueprint.time = 0` и `endTime = 0` это
  старт на одном тике и один предмет на следующем: ~0,2 с на предмет,
  100 патронов ≈ 20 с.

**Что делает 0.0.1.** Хук `OnItemCraft(task, owner, fromTempBlueprint)` вызывается
из `ItemCrafter.CraftItem` :365683 — после `CollectIngredients` :365662 (ингредиенты
уже сняты в `task.takenItems` :365631–365647), но до `queue.AddLast` :365692 и
`note.craft_add` :365695. `FinishCrafting` :365700–365761 очередь не трогает
(только `task`, `owner`, `containers`), поэтому плагин:
1. ставит `task.workbenchEntity = owner.GetCachedCraftLevelWorkbench()` — как
   `ServerUpdate` :365588, чтобы `Workbench.ApplyUpgradesToCraftedItem/GiveBonusItems`
   :365719–365722 работали как в ванили;
2. шлёт клиенту `note.craft_add` (то, что ваниль шлёт после хука :365695);
3. вызывает ванильный `FinishCrafting(task)` пока `task.amount > 0` — игра сама
   создаёт предмет со skin/condition/instanceData :365704–365718, списывает
   ингредиенты из `takenItems` :365723–365741, шлёт `note.craft_done` :365745,
   зовёт `OnItemCraftFinished` :365747, кладёт в инвентарь или дропает :365755–365761;
4. возвращает `true` → `CraftItem` возвращает `true` без постановки в очередь
   :365684–365691.

Границы: `fromTempBlueprint != null` (крафт из чертежа-образца) и `task.cancelled`
→ `null`, ваниль. `blueprint.time` не трогается. Уровень верстака проверяется до
`CraftItem` в `PlayerBlueprints.CanCraft` :312720 (`currentCraftLevel <
GetWorkbenchLevel` :369900) — без изменений; лимит очереди `ItemCrafter.CanCraft`
:365874 (`> 8`) — очередь пуста, не мешает.

**Дюп-проверка.**
* Отмена в момент постановки: `craft.cancel` → `CancelTask` :365762 ищет задачу в
  `queue` :365771 — нашей там нет никогда, `false`; ингредиенты уже уничтожены
  `UseItem` :365735, возвращать нечего; клиент получил `craft_done … 0` и убрал
  запись.
* Выход с сервера/смерть: очередь пуста (наши задачи не ставятся), предметы уже
  в инвентаре игрока; `OnDied` :82076 и `OnStartBeingLooted` :87061 зовут
  `CancelAll` :365830 только для ванильных задач (чертёж-образец).
* Полный инвентарь: на каждый `FinishCrafting` один `CreateByItemID` :365704 и
  при неудаче `GiveItem` :365755 один `Drop` :365761 — N предметов под ноги, ни
  потерь, ни удвоения (то же, что ваниль за N тиков).
* Два крафта быстрее тика: оба `craft.add` обрабатываются синхронно в главном
  потоке; второй `CanCraft` :365858 → `DoesHaveUsableItem` :365850 считает уже
  реальный остаток (первый снял ингредиенты `container.Take` :365625 и потратил
  их) — либо крафт, либо `false`. Окна между снятием и завершением нет.
* Остаток в `takenItems` (в ванили невозможен: `Take` снимает ровно
  `(int)amount * amount` :365643, `FinishCrafting` тратит `(int)amount` на крафт):
  если появится из-за стороннего плагина — `ReturnLeftovers` возвращает в
  `containerMain` или дропает с `note.inv`, как `CancelTask` :365780–365792; не
  уничтожает и не дублирует (те же экземпляры).
* `oxide.reload` во время крафта: состояния у плагина нет, `Unload` пустой,
  очередь ванильная.

Конфликт хуков: если другой плагин вернёт из `OnItemCraft` `false` (запрет крафта),
Oxide залогирует конфликт (Oxide.Core.cs:6149) — такие плагины должны грузиться
раньше или блокировать через `CanCraft` :365884, который срабатывает до снятия
ингредиентов.

**Тест** (`tools/plugincheck/harness/InstantCraftCheck.cs`, заглушка `FinishCrafting`
повторяет :365700 в наблюдаемой части): 100 крафтов → 100 `FinishCrafting`, 100
предметов, `takenItems` пуст, `note.craft_add` + `craft_done`×100 с остатком до 0;
10 со скином; полный инвентарь → 5 дропов, 0 в инвентаре; чертёж-образец и
`cancelled` → `null`; лишний ингредиент возвращён; owner null/destroyed → `null`.
