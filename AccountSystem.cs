using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using Newtonsoft.Json;
using Oxide.Core;
using Oxide.Core.Plugins;
using UnityEngine;

namespace Oxide.Plugins
{
    [Info("AccountSystem", "ICE RUST", "1.0.7")]
    [Description("Persistent ICE RUST account progression and lifetime statistics. UI is rendered by ServerMenu.")]
    public class AccountSystem : RustPlugin
    {
        [PluginReference] private Plugin ServerMenu;

        private const string DataFileName = "AccountSystem/accounts";
        private const string AdminPermission = "accountsystem.admin";
        private const int DataVersion = 1;

        private ConfigData _config;
        private StoredData _data;
        private bool _dirty;

        private readonly Dictionary<ulong, long> _sessionCheckpoint = new Dictionary<ulong, long>();
        private readonly Dictionary<ulong, HashSet<ulong>> _lootEntityPlayers = new Dictionary<ulong, HashSet<ulong>>();
        private readonly HashSet<ulong> _countedExplosives = new HashSet<ulong>();

        // TOP кешируется: сортировать все аккаунты при каждом открытии меню дорого,
        // а визуально рейтингу достаточно обновляться раз в несколько секунд.
        private readonly List<AccountData> _expTopCache = new List<AccountData>();
        private readonly List<Dictionary<string, object>> _expTopRowsCache =
            new List<Dictionary<string, object>>();
        private readonly Dictionary<ulong, int> _expTopRankCache = new Dictionary<ulong, int>();
        private long _expTopCacheUntil;
        private const int ExpTopCacheSeconds = 10;

        #region Configuration

        private class ConfigData
        {
            [JsonProperty("Версия баланса")]
            public int? BalanceVersion;

            [JsonProperty("Сохранение данных каждые N секунд")]
            public float SaveIntervalSeconds = 120f;

            [JsonProperty("Фиксация игрового времени каждые N секунд")]
            public float SessionFlushSeconds = 300f;

            [JsonProperty("Прогрессия уровней")]
            public LevelConfig Leveling = new LevelConfig();

            [JsonProperty("EXP за действия")]
            public ExpConfig Exp = new ExpConfig();

            [JsonProperty("Правила EXP за контейнеры (первое совпадение сверху вниз)")]
            public List<LootRule> LootRules = DefaultLootRules();

            [JsonProperty("Сообщение при повышении уровня")]
            public string LevelUpMessage = "<color=#65A30D>УРОВЕНЬ ПОВЫШЕН</color>  Ваш уровень: <color=#CEC5BB>{level}</color>";

            public static List<LootRule> DefaultLootRules()
            {
                return new List<LootRule>
                {
                    new LootRule("codelockedhackablecrate", 40),
                    new LootRule("hackablelockedcrate", 40),
                    new LootRule("lockedcrate", 40),
                    new LootRule("heli_crate", 35),
                    new LootRule("bradley_crate", 35),
                    new LootRule("crate_elite", 30),
                    new LootRule("elite", 30),
                    new LootRule("crate_military", 18),
                    new LootRule("military", 18),
                    new LootRule("underwater", 12),
                    new LootRule("crate_tools", 8),
                    new LootRule("toolbox", 8),
                    new LootRule("crate_medical", 7),
                    new LootRule("medical", 7),
                    new LootRule("crate_food", 6),
                    new LootRule("foodbox", 6),
                    new LootRule("crate_normal", 9),
                    new LootRule("crate_basic", 6),
                    new LootRule("crate", 6),
                    new LootRule("barrel", 3)
                };
            }
        }

        private class LevelConfig
        {
            [JsonProperty("EXP для перехода с 0 на 1 уровень")]
            public long BaseExp = 500;

            [JsonProperty("Степень прогрессии. Формула: BaseExp * (Level + 1)^Exponent")]
            public double Exponent = 1.35d;

            [JsonProperty("Максимальный EXP за один внешний API-вызов")]
            public long MaxApiExp = 1000000;
        }

        private class ExpConfig
        {
            [JsonProperty("EXP за убийство игрока")]
            public int PlayerKill = 75;

            [JsonProperty("EXP за убийство NPC")]
            public int NpcKill = 20;

            [JsonProperty("EXP за убийство животного")]
            public int AnimalKill = 12;

            [JsonProperty("EXP за уничтожение бочки")]
            public int BarrelDestroyed = 3;

            [JsonProperty("EXP за уничтожение строительного блока")]
            public int StructureDestroyed = 2;

            [JsonProperty("EXP за установку строительного блока")]
            public int BuildingPlaced = 2;

            [JsonProperty("EXP за установку deployable-предмета")]
            public int DeployablePlaced = 1;

            [JsonProperty("EXP за успешное улучшение строительного блока")]
            public int StructureUpgraded = 3;

            [JsonProperty("EXP за использование взрывчатки")]
            public int ExplosiveUsed = 4;

            [JsonProperty("EXP за запуск ракеты")]
            public int RocketFired = 6;

            [JsonProperty("Базовый EXP за завершённый крафт")]
            public int CraftBase = 1;

            [JsonProperty("Дополнительный EXP за каждые N созданных предметов")]
            public int CraftItemsPerExtraExp = 50;

            [JsonProperty("Максимальный EXP за одну операцию крафта")]
            public int MaxCraftExpPerOperation = 4;

            [JsonProperty("Делитель количества ресурсов для EXP. Для x1000 = 1000")]
            public double GatherAmountDivisor = 1000d;

            [JsonProperty("Максимальный EXP за одно событие добычи")]
            public int MaxGatherExpPerEvent = 15;

            [JsonProperty("EXP за подбор collectible")]
            public int Collectible = 2;

            [JsonProperty("EXP за неизвестный мировой loot-контейнер")]
            public int DefaultLootContainer = 4;

            [JsonProperty("EXP за каждые 100 добытых единиц ресурса")]
            public Dictionary<string, int> GatherPer100 = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase)
            {
                ["wood"] = 5,
                ["stones"] = 5,
                ["metal.ore"] = 8,
                ["sulfur.ore"] = 10,
                ["hq.metal.ore"] = 15,
                ["cloth"] = 8,
                ["leather"] = 8,
                ["fat.animal"] = 8,
                ["bone.fragments"] = 5
            };
        }

        private class LootRule
        {
            [JsonProperty("Содержит в prefab")]
            public string Contains;

            [JsonProperty("EXP")]
            public int Exp;

            public LootRule() { }
            public LootRule(string contains, int exp)
            {
                Contains = contains;
                Exp = exp;
            }
        }

        protected override void LoadDefaultConfig()
        {
            _config = new ConfigData();
        }

        protected override void LoadConfig()
        {
            base.LoadConfig();
            try
            {
                _config = Config.ReadObject<ConfigData>();
                if (_config == null) throw new Exception("Config is null");
            }
            catch
            {
                PrintWarning("Конфиг AccountSystem повреждён. Создан новый.");
                _config = new ConfigData();
            }

            if (_config.Leveling == null) _config.Leveling = new LevelConfig();
            if (_config.Exp == null) _config.Exp = new ExpConfig();
            if (_config.Exp.GatherPer100 == null)
                _config.Exp.GatherPer100 = new ExpConfig().GatherPer100;
            if (_config.LootRules == null || _config.LootRules.Count == 0)
                _config.LootRules = ConfigData.DefaultLootRules();

            _config.SaveIntervalSeconds = Mathf.Clamp(_config.SaveIntervalSeconds, 30f, 1800f);
            _config.SessionFlushSeconds = Mathf.Clamp(_config.SessionFlushSeconds, 60f, 3600f);
            // Миграция старого баланса 1.0.2 -> x1000-safe.
            // Данные аккаунтов и уже полученные уровни не сбрасываются.
            if (!_config.BalanceVersion.HasValue || _config.BalanceVersion.Value < 2)
            {
                _config.Leveling.BaseExp = 500;
                _config.Leveling.Exponent = 1.35d;
                _config.Exp.CraftItemsPerExtraExp = 50;
                _config.Exp.MaxCraftExpPerOperation = 4;
                _config.Exp.GatherAmountDivisor = 1000d;
                _config.Exp.MaxGatherExpPerEvent = 15;
                _config.BalanceVersion = 2;
                PrintWarning("AccountSystem: применён баланс прогрессии для x1000. Уровни и статистика игроков сохранены.");
            }

            _config.Leveling.BaseExp = Math.Max(1, _config.Leveling.BaseExp);
            _config.Leveling.Exponent = Math.Max(1.01d, Math.Min(3d, _config.Leveling.Exponent));
            _config.Leveling.MaxApiExp = Math.Max(1, _config.Leveling.MaxApiExp);
            _config.Exp.CraftItemsPerExtraExp = Math.Max(1, _config.Exp.CraftItemsPerExtraExp);
            _config.Exp.MaxCraftExpPerOperation = Math.Max(0, _config.Exp.MaxCraftExpPerOperation);
            _config.Exp.GatherAmountDivisor = Math.Max(1d, _config.Exp.GatherAmountDivisor);
            _config.Exp.MaxGatherExpPerEvent = Math.Max(0, _config.Exp.MaxGatherExpPerEvent);

            SaveConfig();
        }

        protected override void SaveConfig()
        {
            Config.WriteObject(_config, true);
        }

        #endregion

        #region Data

        private class StoredData
        {
            public int Version = DataVersion;
            public string CurrentWipeId = "initial";
            public Dictionary<ulong, AccountData> Accounts = new Dictionary<ulong, AccountData>();
        }

        private class AccountData
        {
            public ulong UserId;
            public string Name = "";

            public int Level;
            public long Exp;
            public long TotalExp;

            public long FirstSeenUtc;
            public long LastSeenUtc;
            public long PlaySeconds;
            public int Sessions;
            public int WipesPlayed;
            public string LastWipeId = "";

            public long PlayerKills;
            public long NpcKills;
            public long AnimalKills;
            public long Deaths;
            public long Headshots;
            public long HitsLanded;
            public long ShotsFired;
            public double DamageDealt;
            public double DamageReceived;

            public long GatheredTotal;
            public long GatherEvents;
            public long CollectiblesPicked;

            public long LootContainers;
            public long CraftOperations;
            public long CraftedItemsTotal;

            public long BuildingPiecesPlaced;
            public long DeployablesPlaced;
            public long StructuresUpgraded;
            public long StructuresDestroyed;
            public long BarrelsDestroyed;

            public long ExplosivesUsed;
            public long RocketsFired;

            public Dictionary<string, long> Resources = new Dictionary<string, long>(StringComparer.OrdinalIgnoreCase);
            public Dictionary<string, long> LootedContainers = new Dictionary<string, long>(StringComparer.OrdinalIgnoreCase);
            public Dictionary<string, long> CraftedItems = new Dictionary<string, long>(StringComparer.OrdinalIgnoreCase);
            public Dictionary<string, long> NpcKillsByPrefab = new Dictionary<string, long>(StringComparer.OrdinalIgnoreCase);
            public Dictionary<string, long> AnimalKillsByPrefab = new Dictionary<string, long>(StringComparer.OrdinalIgnoreCase);
            public Dictionary<string, long> ExplosivesByPrefab = new Dictionary<string, long>(StringComparer.OrdinalIgnoreCase);
            public Dictionary<string, long> BuiltEntities = new Dictionary<string, long>(StringComparer.OrdinalIgnoreCase);
            public Dictionary<string, long> CollectiblesByPrefab = new Dictionary<string, long>(StringComparer.OrdinalIgnoreCase);
        }

        private void LoadData()
        {
            try
            {
                _data = Interface.Oxide.DataFileSystem.ReadObject<StoredData>(DataFileName);
            }
            catch (Exception ex)
            {
                PrintError("Не удалось прочитать данные AccountSystem: " + ex.Message);
                _data = new StoredData();
            }

            if (_data == null) _data = new StoredData();
            if (_data.Accounts == null) _data.Accounts = new Dictionary<ulong, AccountData>();
            if (string.IsNullOrEmpty(_data.CurrentWipeId)) _data.CurrentWipeId = "initial";

            foreach (AccountData account in _data.Accounts.Values)
                Normalize(account);
        }

        private void SaveData(bool force = false)
        {
            if (!force && !_dirty) return;
            try
            {
                Interface.Oxide.DataFileSystem.WriteObject(DataFileName, _data);
                _dirty = false;
            }
            catch (Exception ex)
            {
                PrintError("Не удалось сохранить AccountSystem: " + ex.Message);
            }
        }

        private void Normalize(AccountData a)
        {
            if (a == null) return;
            if (a.Resources == null) a.Resources = new Dictionary<string, long>(StringComparer.OrdinalIgnoreCase);
            if (a.LootedContainers == null) a.LootedContainers = new Dictionary<string, long>(StringComparer.OrdinalIgnoreCase);
            if (a.CraftedItems == null) a.CraftedItems = new Dictionary<string, long>(StringComparer.OrdinalIgnoreCase);
            if (a.NpcKillsByPrefab == null) a.NpcKillsByPrefab = new Dictionary<string, long>(StringComparer.OrdinalIgnoreCase);
            if (a.AnimalKillsByPrefab == null) a.AnimalKillsByPrefab = new Dictionary<string, long>(StringComparer.OrdinalIgnoreCase);
            if (a.ExplosivesByPrefab == null) a.ExplosivesByPrefab = new Dictionary<string, long>(StringComparer.OrdinalIgnoreCase);
            if (a.BuiltEntities == null) a.BuiltEntities = new Dictionary<string, long>(StringComparer.OrdinalIgnoreCase);
            if (a.CollectiblesByPrefab == null) a.CollectiblesByPrefab = new Dictionary<string, long>(StringComparer.OrdinalIgnoreCase);
            if (a.Level < 0) a.Level = 0;
            if (a.Exp < 0) a.Exp = 0;
            if (a.TotalExp < 0) a.TotalExp = 0;
        }

        private AccountData Ensure(ulong userId, string name = null)
        {
            AccountData a;
            if (!_data.Accounts.TryGetValue(userId, out a) || a == null)
            {
                long now = Now();
                a = new AccountData
                {
                    UserId = userId,
                    Name = name ?? userId.ToString(CultureInfo.InvariantCulture),
                    FirstSeenUtc = now,
                    LastSeenUtc = now
                };
                _data.Accounts[userId] = a;
                _dirty = true;
            }

            Normalize(a);
            if (!string.IsNullOrEmpty(name) && a.Name != name)
            {
                a.Name = name;
                _dirty = true;
            }

            return a;
        }

        private AccountData Ensure(BasePlayer player)
        {
            return Ensure(player.userID, player.displayName);
        }

        #endregion

        #region Lifecycle

        private void Init()
        {
            permission.RegisterPermission(AdminPermission, this);
            LoadData();
        }

        private void OnServerInitialized()
        {
            foreach (BasePlayer player in BasePlayer.activePlayerList)
                StartSession(player, false);

            timer.Every(_config.SaveIntervalSeconds, () => SaveData());
            timer.Every(_config.SessionFlushSeconds, FlushSessions);
        }

        private void Unload()
        {
            FlushSessions();
            SaveData(true);
            _sessionCheckpoint.Clear();
            _lootEntityPlayers.Clear();
            _countedExplosives.Clear();
            _expTopCache.Clear();
            _expTopRowsCache.Clear();
            _expTopRankCache.Clear();
            _expTopCacheUntil = 0;
        }

        private void OnServerSave()
        {
            FlushSessions();
            SaveData();
        }

        private void OnServerShutdown()
        {
            FlushSessions();
            SaveData(true);
        }

        private void OnNewSave(string filename)
        {

            _data.CurrentWipeId = string.IsNullOrEmpty(filename)
                ? "wipe-" + Now().ToString(CultureInfo.InvariantCulture)
                : filename;

            _lootEntityPlayers.Clear();
            _countedExplosives.Clear();
            _expTopCacheUntil = 0;
            _dirty = true;
            SaveData(true);
        }

        private void OnPlayerConnected(BasePlayer player)
        {
            StartSession(player, true);
            _expTopCacheUntil = 0;
        }

        private void OnPlayerDisconnected(BasePlayer player, string reason)
        {
            if (!Human(player)) return;
            FlushSession(player.userID);
            AccountData a = Ensure(player);
            a.LastSeenUtc = Now();
            _dirty = true;
            _expTopCacheUntil = 0;
            SaveData();
        }

        private void StartSession(BasePlayer player, bool countSession)
        {
            if (!Human(player)) return;

            AccountData a = Ensure(player);
            long now = Now();
            a.LastSeenUtc = now;

            if (countSession || a.Sessions <= 0)
                a.Sessions++;

            if (a.LastWipeId != _data.CurrentWipeId)
            {
                a.LastWipeId = _data.CurrentWipeId;
                a.WipesPlayed++;
            }

            _sessionCheckpoint[player.userID] = now;
            _dirty = true;
        }

        private void FlushSessions()
        {
            foreach (BasePlayer player in BasePlayer.activePlayerList)
                if (Human(player))
                    FlushSession(player.userID);
        }

        private void FlushSession(ulong userId)
        {
            long started;
            if (!_sessionCheckpoint.TryGetValue(userId, out started)) return;

            long now = Now();
            long delta = Math.Max(0, now - started);
            if (delta > 0)
            {
                AccountData a = Ensure(userId);
                a.PlaySeconds += delta;
                a.LastSeenUtc = now;
                _dirty = true;
            }

            BasePlayer online = BasePlayer.FindByID(userId);
            if (online != null && online.IsConnected)
                _sessionCheckpoint[userId] = now;
            else
                _sessionCheckpoint.Remove(userId);
        }

        #endregion

        #region Leveling

        private long RequiredExp(int level)
        {
            if (level < 0) level = 0;
            double raw = _config.Leveling.BaseExp * Math.Pow(level + 1d, _config.Leveling.Exponent);
            if (double.IsNaN(raw) || double.IsInfinity(raw) || raw >= long.MaxValue)
                return long.MaxValue;
            return Math.Max(1L, (long)Math.Ceiling(raw));
        }

        private bool AddExp(BasePlayer player, long amount, string source, bool notifyLevelUp = true)
        {
            if (!Human(player) || amount <= 0) return false;
            return AddExpInternal(Ensure(player), player, amount, source, notifyLevelUp);
        }

        private bool AddExp(ulong userId, long amount, string source, bool notifyLevelUp = true)
        {
            if (!userId.IsSteamId() || amount <= 0) return false;

            BasePlayer player = BasePlayer.FindByID(userId);
            AccountData a;
            if (_data.Accounts.TryGetValue(userId, out a) && a != null)
                Normalize(a);
            else if (player != null)
                a = Ensure(player);
            else
                return false;

            return AddExpInternal(a, player, amount, source, notifyLevelUp);
        }

        private bool AddExpInternal(AccountData a, BasePlayer player, long amount, string source, bool notifyLevelUp)
        {
            if (a == null || amount <= 0) return false;

            int oldLevel = a.Level;
            a.TotalExp = SafeAdd(a.TotalExp, amount);

            long remaining = amount;
            int safety = 0;
            while (remaining > 0 && safety++ < 100000)
            {
                long required = RequiredExp(a.Level);
                long need = Math.Max(1, required - a.Exp);

                if (remaining < need)
                {
                    a.Exp += remaining;
                    remaining = 0;
                }
                else
                {
                    remaining -= need;
                    a.Level++;
                    a.Exp = 0;
                }
            }

            _dirty = true;

            if (a.Level > oldLevel)
            {
                Interface.CallHook("OnAccountLevelUp", player, a.UserId, oldLevel, a.Level);

                if (notifyLevelUp && player != null && player.IsConnected)
                {
                    string message = (_config.LevelUpMessage ?? "")
                        .Replace("{level}", a.Level.ToString(CultureInfo.InvariantCulture))
                        .Replace("{oldlevel}", oldLevel.ToString(CultureInfo.InvariantCulture));
                    if (!string.IsNullOrWhiteSpace(message))
                        player.ChatMessage(message);
                }

                RefreshServerMenu(player);
            }

            return true;
        }

        private static long SafeAdd(long value, long amount)
        {
            if (amount > 0 && value > long.MaxValue - amount) return long.MaxValue;
            if (amount < 0 && value < long.MinValue - amount) return long.MinValue;
            return value + amount;
        }

        #endregion

        #region Tracking - Resources / Loot / Craft

        private void OnDispenserGathered(ResourceDispenser dispenser, BasePlayer player, Item item)
        {
            TrackGather(player, item);
        }

        private void OnDispenserBonusReceived(ResourceDispenser dispenser, BasePlayer player, Item item)
        {
            TrackGather(player, item);
        }

        private void TrackGather(BasePlayer player, Item item)
        {
            if (!Human(player) || item == null || item.info == null || item.amount <= 0) return;

            AccountData a = Ensure(player);
            string shortname = item.info.shortname ?? "unknown";
            Add(a.Resources, shortname, item.amount);
            a.GatheredTotal += item.amount;
            a.GatherEvents++;
            _dirty = true;

            int per100;
            if (!_config.Exp.GatherPer100.TryGetValue(shortname, out per100))
                per100 = 3;

            // Статистика хранит реальное количество ресурсов с x1000, но EXP
            // считается от нормализованного количества, чтобы рейты сервера не
            // превращали один удар по ноде в несколько уровней.
            double normalizedAmount = item.amount / Math.Max(1d, _config.Exp.GatherAmountDivisor);
            long exp = Math.Max(0L, (long)Math.Floor(normalizedAmount * (per100 / 100d)));

            int gatherCap = Math.Max(0, _config.Exp.MaxGatherExpPerEvent);
            if (gatherCap > 0 && exp > gatherCap)
                exp = gatherCap;

            if (exp > 0) AddExpInternal(a, player, exp, "gather:" + shortname, true);
        }

        private void OnCollectiblePickedup(CollectibleEntity collectible, BasePlayer player, Item item)
        {
            if (!Human(player)) return;

            AccountData a = Ensure(player);
            string prefab = ShortName(collectible);
            a.CollectiblesPicked++;
            Add(a.CollectiblesByPrefab, prefab, 1);

            if (item != null && item.info != null && item.amount > 0)
            {
                Add(a.Resources, item.info.shortname, item.amount);
                a.GatheredTotal += item.amount;
            }

            _dirty = true;
            AddExpInternal(a, player, Math.Max(0, _config.Exp.Collectible), "collectible:" + prefab, true);
        }

        private void OnLootEntity(BasePlayer player, BaseEntity targetEntity)
        {
            if (!Human(player) || targetEntity == null) return;

            LootContainer loot = targetEntity as LootContainer;
            if (loot == null) return;

            ulong entityId = NetId(targetEntity);
            if (entityId == 0) return;

            HashSet<ulong> players;
            if (!_lootEntityPlayers.TryGetValue(entityId, out players))
                _lootEntityPlayers[entityId] = players = new HashSet<ulong>();

            if (!players.Add(player.userID))
                return;

            AccountData a = Ensure(player);
            string prefab = ShortName(targetEntity);
            a.LootContainers++;
            Add(a.LootedContainers, prefab, 1);
            _dirty = true;

            AddExpInternal(a, player, LootExp(prefab), "loot:" + prefab, true);
        }

        private int LootExp(string prefab)
        {
            string value = prefab ?? "";
            foreach (LootRule rule in _config.LootRules)
            {
                if (rule == null || string.IsNullOrWhiteSpace(rule.Contains)) continue;
                if (value.IndexOf(rule.Contains, StringComparison.OrdinalIgnoreCase) >= 0)
                    return Math.Max(0, rule.Exp);
            }
            return Math.Max(0, _config.Exp.DefaultLootContainer);
        }

        private void OnItemCraftFinished(ItemCraftTask task, Item item, ItemCrafter crafter)
        {
            BasePlayer player = crafter != null ? crafter.owner : null;
            if (!Human(player) || item == null || item.info == null) return;

            int amount = Math.Max(1, item.amount);
            AccountData a = Ensure(player);
            a.CraftOperations++;
            a.CraftedItemsTotal += amount;
            Add(a.CraftedItems, item.info.shortname, amount);
            _dirty = true;

            int extraDivisor = Math.Max(1, _config.Exp.CraftItemsPerExtraExp);
            long exp = Math.Max(0, _config.Exp.CraftBase) + amount / extraDivisor;

            int craftCap = Math.Max(0, _config.Exp.MaxCraftExpPerOperation);
            if (craftCap > 0 && exp > craftCap)
                exp = craftCap;

            if (exp > 0) AddExpInternal(a, player, exp, "craft:" + item.info.shortname, true);
        }

        #endregion

        #region Tracking - Combat

        private void OnWeaponFired(BaseProjectile projectile, BasePlayer player, ItemModProjectile mod, ProtoBuf.ProjectileShoot projectiles)
        {
            if (!Human(player)) return;
            AccountData a = Ensure(player);
            a.ShotsFired++;
            _dirty = true;
        }

        private void OnEntityTakeDamage(BaseCombatEntity entity, HitInfo info)
        {
            if (entity == null || info == null) return;

            float amount = info.damageTypes != null ? info.damageTypes.Total() : 0f;
            if (amount <= 0f) return;

            BasePlayer attacker = info.InitiatorPlayer;
            if (Human(attacker) && attacker != entity)
            {
                AccountData a = Ensure(attacker);
                a.DamageDealt += amount;
                a.HitsLanded++;
                if (IsHeadshot(info)) a.Headshots++;
                _dirty = true;
            }

            BasePlayer victim = entity as BasePlayer;
            if (Human(victim))
            {
                AccountData v = Ensure(victim);
                v.DamageReceived += amount;
                _dirty = true;
            }
        }

        private void OnEntityDeath(BaseCombatEntity entity, HitInfo info)
        {
            if (entity == null) return;

            BasePlayer attacker = info != null ? info.InitiatorPlayer : null;
            BasePlayer victim = entity as BasePlayer;

            if (Human(victim))
            {
                AccountData victimAccount = Ensure(victim);
                victimAccount.Deaths++;
                _dirty = true;

                if (Human(attacker) && attacker != victim)
                {
                    AccountData attackerAccount = Ensure(attacker);
                    attackerAccount.PlayerKills++;
                    _dirty = true;
                    AddExpInternal(attackerAccount, attacker, Math.Max(0, _config.Exp.PlayerKill), "kill:player", true);
                }
                return;
            }

            if (!Human(attacker)) return;

            AccountData a = Ensure(attacker);
            string prefab = ShortName(entity);

            if (victim != null && victim.IsNpc)
            {
                a.NpcKills++;
                Add(a.NpcKillsByPrefab, prefab, 1);
                _dirty = true;
                AddExpInternal(a, attacker, Math.Max(0, _config.Exp.NpcKill), "kill:npc:" + prefab, true);
                return;
            }

            if (entity is BaseAnimalNPC)
            {
                a.AnimalKills++;
                Add(a.AnimalKillsByPrefab, prefab, 1);
                _dirty = true;
                AddExpInternal(a, attacker, Math.Max(0, _config.Exp.AnimalKill), "kill:animal:" + prefab, true);
                return;
            }

            if (prefab.IndexOf("barrel", StringComparison.OrdinalIgnoreCase) >= 0)
            {
                a.BarrelsDestroyed++;
                _dirty = true;
                AddExpInternal(a, attacker, Math.Max(0, _config.Exp.BarrelDestroyed), "destroy:barrel", true);
                return;
            }

            if (entity is BuildingBlock)
            {
                a.StructuresDestroyed++;
                _dirty = true;
                AddExpInternal(a, attacker, Math.Max(0, _config.Exp.StructureDestroyed), "destroy:structure", true);
            }
        }

        #endregion

        #region Tracking - Building / Explosives

        private void OnEntityBuilt(Planner plan, GameObject go)
        {
            if (plan == null || go == null) return;
            BasePlayer player = plan.GetOwnerPlayer();
            if (!Human(player)) return;

            BaseEntity entity = go.ToBaseEntity();
            if (entity == null) return;

            AccountData a = Ensure(player);
            string prefab = ShortName(entity);
            Add(a.BuiltEntities, prefab, 1);

            if (entity is BuildingBlock)
            {
                a.BuildingPiecesPlaced++;
                _dirty = true;
                AddExpInternal(a, player, Math.Max(0, _config.Exp.BuildingPlaced), "build:block", true);
            }
            else
            {
                a.DeployablesPlaced++;
                _dirty = true;
                AddExpInternal(a, player, Math.Max(0, _config.Exp.DeployablePlaced), "build:deployable", true);
            }
        }

        private void OnStructureUpgraded(BuildingBlock block, BasePlayer player, BuildingGrade.Enum type, ulong skin)
        {
            if (block == null || !Human(player)) return;

            AccountData a = Ensure(player);
            a.StructuresUpgraded++;
            _dirty = true;
            AddExpInternal(a, player, Math.Max(0, _config.Exp.StructureUpgraded), "build:upgrade:" + type, true);
        }

        private void OnExplosiveThrown(BasePlayer player, BaseEntity thrownEntity, ThrownWeapon instance)
        {
            TrackExplosive(player, thrownEntity, false);
        }

        private void OnExplosiveDropped(BasePlayer player, BaseEntity droppedEntity, ThrownWeapon instance)
        {
            TrackExplosive(player, droppedEntity, false);
        }

        private void OnRocketLaunched(BasePlayer player, BaseEntity entity)
        {
            TrackExplosive(player, entity, true);
        }

        private void TrackExplosive(BasePlayer player, BaseEntity entity, bool rocket)
        {
            if (!Human(player) || entity == null) return;

            ulong id = NetId(entity);
            if (id != 0 && !_countedExplosives.Add(id)) return;

            AccountData a = Ensure(player);
            string prefab = ShortName(entity);
            a.ExplosivesUsed++;
            Add(a.ExplosivesByPrefab, prefab, 1);

            if (rocket) a.RocketsFired++;
            _dirty = true;

            int exp = rocket ? _config.Exp.RocketFired : _config.Exp.ExplosiveUsed;
            AddExpInternal(a, player, Math.Max(0, exp), rocket ? "explosive:rocket" : "explosive:" + prefab, true);
        }

        private void OnEntityKill(BaseNetworkable entity)
        {
            if (entity == null) return;
            ulong id = NetId(entity);
            if (id == 0) return;

            _lootEntityPlayers.Remove(id);
            _countedExplosives.Remove(id);
        }

        #endregion

        #region Commands / ServerMenu

        [ChatCommand("profile")]
        private void CmdProfile(BasePlayer player, string command, string[] args)
        {
            OpenProfile(player);
        }

        [ChatCommand("account")]
        private void CmdAccount(BasePlayer player, string command, string[] args)
        {
            OpenProfile(player);
        }

        private void OpenProfile(BasePlayer player)
        {
            if (!Human(player)) return;
            Ensure(player);

            if (ServerMenuReady())
                ServerMenu.Call("API_OpenTab", player, "account", "");
        }

        private bool ServerMenuReady()
        {
            if (ServerMenu != null && ServerMenu.IsLoaded) return true;
            ServerMenu = Interface.Oxide.RootPluginManager.GetPlugin("ServerMenu");
            return ServerMenu != null && ServerMenu.IsLoaded;
        }

        private void RefreshServerMenu(BasePlayer player)
        {
            if (player == null || !player.IsConnected || !ServerMenuReady()) return;
            ServerMenu.Call("API_RefreshIntegrated", player, "account");
            ServerMenu.Call("API_RefreshIntegrated", player, "home");
        }

        #endregion

        #region Public API

        [HookMethod("API_GetLevel")]
        public int API_GetLevel(ulong userId)
        {
            AccountData a;
            return _data.Accounts.TryGetValue(userId, out a) && a != null ? Math.Max(0, a.Level) : 0;
        }

        [HookMethod("API_HasLevel")]
        public bool API_HasLevel(ulong userId, int requiredLevel)
        {
            return API_GetLevel(userId) >= Math.Max(0, requiredLevel);
        }

        [HookMethod("API_GetRequiredExp")]
        public long API_GetRequiredExp(int level)
        {
            return RequiredExp(level);
        }

        [HookMethod("API_AddExp")]
        public bool API_AddExp(ulong userId, long amount, string source = "external")
        {
            if (amount <= 0 || amount > _config.Leveling.MaxApiExp) return false;
            return AddExp(userId, amount, source ?? "external", true);
        }

        [HookMethod("API_GetSummary")]
        public object API_GetSummary(ulong userId)
        {
            AccountData a;
            if (!_data.Accounts.TryGetValue(userId, out a) || a == null)
            {
                BasePlayer player = BasePlayer.FindByID(userId);
                if (player == null) return null;
                a = Ensure(player);
            }

            return Summary(a);
        }

        [HookMethod("API_GetPlayerSummary")]
        public object API_GetPlayerSummary(BasePlayer player)
        {
            return Human(player) ? Summary(Ensure(player)) : null;
        }

        private void EnsureExpTopCache()
        {
            long now = Now();
            if (_expTopCacheUntil > now && _expTopCache.Count > 0)
                return;

            // Имена онлайн-игроков синхронизируем только при обновлении кеша,
            // а не при каждом открытии вкладки.
            foreach (BasePlayer online in BasePlayer.activePlayerList)
            {
                if (Human(online))
                    Ensure(online);
            }

            _expTopCache.Clear();
            _expTopRowsCache.Clear();
            _expTopRankCache.Clear();

            foreach (AccountData account in _data.Accounts.Values)
            {
                if (account == null || !account.UserId.IsSteamId())
                    continue;

                Normalize(account);
                _expTopCache.Add(account);
            }

            _expTopCache.Sort((a, b) =>
            {
                int byTotalExp = b.TotalExp.CompareTo(a.TotalExp);
                if (byTotalExp != 0) return byTotalExp;

                int byLevel = b.Level.CompareTo(a.Level);
                if (byLevel != 0) return byLevel;

                int byCurrentExp = b.Exp.CompareTo(a.Exp);
                if (byCurrentExp != 0) return byCurrentExp;

                int byPlaytime = EffectivePlaySeconds(b.UserId, b).CompareTo(EffectivePlaySeconds(a.UserId, a));
                if (byPlaytime != 0) return byPlaytime;

                return string.Compare(a.Name ?? string.Empty, b.Name ?? string.Empty, StringComparison.OrdinalIgnoreCase);
            });

            for (int i = 0; i < _expTopCache.Count; i++)
            {
                AccountData account = _expTopCache[i];
                int rank = i + 1;
                _expTopRankCache[account.UserId] = rank;
                _expTopRowsCache.Add(ExpTopRow(account, rank));
            }

            _expTopCacheUntil = now + ExpTopCacheSeconds;
        }

        private Dictionary<string, object> ExpTopRow(AccountData a, int rank)
        {
            BasePlayer online = a == null ? null : BasePlayer.FindByID(a.UserId);

            return new Dictionary<string, object>
            {
                ["Rank"] = rank,
                ["SteamId"] = a.UserId,
                ["Name"] = a.Name ?? a.UserId.ToString(CultureInfo.InvariantCulture),
                ["Level"] = a.Level,
                ["Exp"] = a.Exp,
                ["RequiredExp"] = RequiredExp(a.Level),
                ["TotalExp"] = a.TotalExp,
                ["PlaySeconds"] = EffectivePlaySeconds(a.UserId, a),
                ["Online"] = online != null && online.IsConnected && !online.IsNpc
            };
        }

        [HookMethod("API_GetExpTopPage")]
        public object API_GetExpTopPage(int page, int pageSize, ulong viewerUserId = 0UL)
        {
            EnsureExpTopCache();

            pageSize = Math.Max(5, Math.Min(20, pageSize));
            int total = _expTopCache.Count;
            int pageCount = Math.Max(1, (total + pageSize - 1) / pageSize);
            page = Math.Max(0, Math.Min(pageCount - 1, page));

            int myRank = 0;
            if (viewerUserId != 0UL)
                _expTopRankCache.TryGetValue(viewerUserId, out myRank);

            int myPage = myRank > 0 ? (myRank - 1) / pageSize : 0;
            int start = page * pageSize;
            int end = Math.Min(total, start + pageSize);

            var rows = new List<Dictionary<string, object>>(Math.Max(0, end - start));
            for (int i = start; i < end; i++)
                rows.Add(ExpTopRow(_expTopCache[i], i + 1));

            return new Dictionary<string, object>
            {
                ["Rows"] = rows,
                ["Total"] = total,
                ["Page"] = page,
                ["PageCount"] = pageCount,
                ["MyRank"] = myRank,
                ["MyPage"] = myPage,
                ["CacheSeconds"] = ExpTopCacheSeconds
            };
        }

        // Старый API сохраняем для совместимости сторонних плагинов.
        // ServerMenu больше его не использует, чтобы не передавать сотни строк CUI.
        [HookMethod("API_GetExpTop")]
        public object API_GetExpTop()
        {
            EnsureExpTopCache();

            // Возвращаем уже готовый кеш рейтинга. ServerMenu сам показывает
            // только нужные 10 строк, поэтому большой CUI на клиент не отправляется.
            return _expTopRowsCache;
        }

        [HookMethod("API_GetPlayerProfile")]
        public object API_GetPlayerProfile(BasePlayer player)
        {
            if (!Human(player)) return null;
            return BuildProfile(Ensure(player));
        }

        [HookMethod("API_GetProfile")]
        public object API_GetProfile(ulong userId)
        {
            AccountData a;
            if (!_data.Accounts.TryGetValue(userId, out a) || a == null)
            {
                BasePlayer player = BasePlayer.FindByID(userId);
                if (player == null) return null;
                a = Ensure(player);
            }

            return BuildProfile(a);
        }

        [HookMethod("API_CanAdminReset")]
        public bool API_CanAdminReset(BasePlayer admin)
        {
            return Human(admin) && permission.UserHasPermission(admin.UserIDString, AdminPermission);
        }

        [HookMethod("API_ResetAccountStats")]
        public bool API_ResetAccountStats(BasePlayer admin, ulong userId)
        {
            if (!API_CanAdminReset(admin) || !userId.IsSteamId()) return false;

            AccountData a;
            if (!_data.Accounts.TryGetValue(userId, out a) || a == null) return false;

            // Сначала фиксируем текущую онлайн-сессию, затем обнуляем накопленный прогресс.
            FlushSession(userId);
            Normalize(a);

            a.Level = 0;
            a.Exp = 0;
            a.TotalExp = 0;

            a.PlaySeconds = 0;
            a.Sessions = 0;
            a.WipesPlayed = 0;
            a.LastWipeId = _data.CurrentWipeId ?? "initial";

            a.PlayerKills = 0;
            a.NpcKills = 0;
            a.AnimalKills = 0;
            a.Deaths = 0;
            a.Headshots = 0;
            a.HitsLanded = 0;
            a.ShotsFired = 0;
            a.DamageDealt = 0d;
            a.DamageReceived = 0d;

            a.GatheredTotal = 0;
            a.GatherEvents = 0;
            a.CollectiblesPicked = 0;
            a.LootContainers = 0;
            a.CraftOperations = 0;
            a.CraftedItemsTotal = 0;

            a.BuildingPiecesPlaced = 0;
            a.DeployablesPlaced = 0;
            a.StructuresUpgraded = 0;
            a.StructuresDestroyed = 0;
            a.BarrelsDestroyed = 0;
            a.ExplosivesUsed = 0;
            a.RocketsFired = 0;

            a.Resources.Clear();
            a.LootedContainers.Clear();
            a.CraftedItems.Clear();
            a.NpcKillsByPrefab.Clear();
            a.AnimalKillsByPrefab.Clear();
            a.ExplosivesByPrefab.Clear();
            a.BuiltEntities.Clear();
            a.CollectiblesByPrefab.Clear();

            long now = Now();
            a.LastSeenUtc = now;

            BasePlayer target = BasePlayer.FindByID(userId);
            if (target != null && target.IsConnected)
                _sessionCheckpoint[userId] = now;

            // После очистки игрок может заново получить статистику с уже открываемых контейнеров.
            foreach (HashSet<ulong> viewers in _lootEntityPlayers.Values)
                if (viewers != null) viewers.Remove(userId);

            _expTopCache.Clear();
            _expTopRowsCache.Clear();
            _expTopRankCache.Clear();
            _expTopCacheUntil = 0;
            _dirty = true;
            SaveData(true);

            if (target != null && target.IsConnected)
                RefreshServerMenu(target);

            Interface.CallHook("OnAccountStatsReset", admin, userId);
            admin.ChatMessage("<color=#65A30D>[ACCOUNT]</color> Статистика игрока <color=#CEC5BB>" +
                (a.Name ?? userId.ToString(CultureInfo.InvariantCulture)) + "</color> полностью очищена.");
            return true;
        }

        private Dictionary<string, object> BuildProfile(AccountData a)
        {
            if (a == null) return null;
            Normalize(a);

            var result = Summary(a);
            result["FirstSeenUtc"] = a.FirstSeenUtc;
            result["LastSeenUtc"] = a.LastSeenUtc;
            result["PlaySeconds"] = EffectivePlaySeconds(a.UserId, a);
            result["Sessions"] = a.Sessions;
            result["WipesPlayed"] = a.WipesPlayed;

            result["PlayerKills"] = a.PlayerKills;
            result["NpcKills"] = a.NpcKills;
            result["AnimalKills"] = a.AnimalKills;
            result["Deaths"] = a.Deaths;
            result["Headshots"] = a.Headshots;
            result["HitsLanded"] = a.HitsLanded;
            result["ShotsFired"] = a.ShotsFired;
            result["DamageDealt"] = Math.Round(a.DamageDealt, 1);
            result["DamageReceived"] = Math.Round(a.DamageReceived, 1);

            result["GatheredTotal"] = a.GatheredTotal;
            result["GatherEvents"] = a.GatherEvents;
            result["CollectiblesPicked"] = a.CollectiblesPicked;
            result["LootContainers"] = a.LootContainers;
            result["CraftOperations"] = a.CraftOperations;
            result["CraftedItemsTotal"] = a.CraftedItemsTotal;
            result["BuildingPiecesPlaced"] = a.BuildingPiecesPlaced;
            result["DeployablesPlaced"] = a.DeployablesPlaced;
            result["StructuresUpgraded"] = a.StructuresUpgraded;
            result["StructuresDestroyed"] = a.StructuresDestroyed;
            result["BarrelsDestroyed"] = a.BarrelsDestroyed;
            result["ExplosivesUsed"] = a.ExplosivesUsed;
            result["RocketsFired"] = a.RocketsFired;

            result["Resources"] = new Dictionary<string, long>(a.Resources, StringComparer.OrdinalIgnoreCase);
            result["LootedContainers"] = new Dictionary<string, long>(a.LootedContainers, StringComparer.OrdinalIgnoreCase);
            result["CraftedItems"] = new Dictionary<string, long>(a.CraftedItems, StringComparer.OrdinalIgnoreCase);
            result["NpcKillsByPrefab"] = new Dictionary<string, long>(a.NpcKillsByPrefab, StringComparer.OrdinalIgnoreCase);
            result["AnimalKillsByPrefab"] = new Dictionary<string, long>(a.AnimalKillsByPrefab, StringComparer.OrdinalIgnoreCase);
            result["ExplosivesByPrefab"] = new Dictionary<string, long>(a.ExplosivesByPrefab, StringComparer.OrdinalIgnoreCase);
            result["BuiltEntities"] = new Dictionary<string, long>(a.BuiltEntities, StringComparer.OrdinalIgnoreCase);
            result["CollectiblesByPrefab"] = new Dictionary<string, long>(a.CollectiblesByPrefab, StringComparer.OrdinalIgnoreCase);

            result["TopResource"] = TopKey(a.Resources);
            result["TopLootContainer"] = TopKey(a.LootedContainers);
            result["TopCraftedItem"] = TopKey(a.CraftedItems);

            return result;
        }

        private Dictionary<string, object> Summary(AccountData a)
        {
            return new Dictionary<string, object>
            {
                ["SteamId"] = a.UserId,
                ["Name"] = a.Name,
                ["Level"] = a.Level,
                ["Exp"] = a.Exp,
                ["RequiredExp"] = RequiredExp(a.Level),
                ["TotalExp"] = a.TotalExp,
                ["Progress"] = RequiredExp(a.Level) <= 0 ? 0d : Math.Min(1d, a.Exp / (double)RequiredExp(a.Level)),
                ["PlaySeconds"] = EffectivePlaySeconds(a.UserId, a)
            };
        }

        #endregion

        #region Helpers

        private long EffectivePlaySeconds(ulong userId, AccountData a)
        {
            long value = a != null ? a.PlaySeconds : 0;
            long started;
            if (_sessionCheckpoint.TryGetValue(userId, out started))
                value += Math.Max(0, Now() - started);
            return value;
        }

        private static void Add(Dictionary<string, long> map, string key, long amount)
        {
            if (map == null || string.IsNullOrEmpty(key) || amount == 0) return;
            long current;
            map.TryGetValue(key, out current);
            map[key] = SafeAdd(current, amount);
        }

        private static string TopKey(Dictionary<string, long> map)
        {
            if (map == null || map.Count == 0) return "";
            KeyValuePair<string, long> best = map.First();
            foreach (var pair in map)
                if (pair.Value > best.Value)
                    best = pair;
            return best.Key;
        }

        private static bool Human(BasePlayer player)
        {
            return player != null && player.userID.IsSteamId() && !player.IsNpc;
        }

        private static bool IsHeadshot(HitInfo info)
        {
            if (info == null) return false;
            if (info.HitBone == 698017942) return true;
            return info.boneName != null && info.boneName.IndexOf("head", StringComparison.OrdinalIgnoreCase) >= 0;
        }

        private static string ShortName(BaseNetworkable entity)
        {
            if (entity == null) return "unknown";
            string value = entity.ShortPrefabName;
            if (!string.IsNullOrEmpty(value)) return value;
            return string.IsNullOrEmpty(entity.PrefabName) ? "unknown" : entity.PrefabName;
        }

        private static ulong NetId(BaseNetworkable entity)
        {
            return entity != null && entity.net != null ? entity.net.ID.Value : 0UL;
        }

        private static long Now()
        {
            return DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        }

        #endregion
    }
}
