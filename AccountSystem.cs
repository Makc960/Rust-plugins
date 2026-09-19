using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using Newtonsoft.Json;
using Oxide.Core;
using Oxide.Core.Plugins;
using UnityEngine;

namespace Oxide.Plugins
{
    [Info("AccountSystem", "ICE RUST", "1.1.0")]
    [Description("Persistent ICE RUST account progression with per-wipe, previous-wipe and lifetime statistics.")]
    public class AccountSystem : RustPlugin
    {
        [PluginReference] private Plugin ServerMenu;

        private const string DataFileName = "AccountSystem/accounts";
        private const string TempFileName = "AccountSystem/accounts.writing";
        private const string AdminPermission = "accountsystem.admin";
        private const int DataVersion = 2;
        private const int ActivityDays = 30;
        private const int DeathCauseCount = 13;
        private const int GradeCount = 6;
        private const int MaxDuelEntries = 50;
        private const int MaxWeaponEntries = 120;
        private const int MaxRecentSessions = 10;
        private const int AssistWindowSeconds = 10;
        private const int RecentAttackerSlots = 8;
        private const int RankCacheSeconds = 60;

        private ConfigData _config;
        private StoredData _data;
        private bool _dirty;

        private readonly Dictionary<ulong, long> _sessionCheckpoint = new Dictionary<ulong, long>();
        private readonly Dictionary<ulong, long> _sessionStart = new Dictionary<ulong, long>();
        private readonly Dictionary<ulong, RecentAttackers> _recentAttackers = new Dictionary<ulong, RecentAttackers>();
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


        // Индексы фиксированы: они хранятся в StatLayer.DeathsByCause.
        private enum DeathCause
        {
            Player = 0,
            Npc = 1,
            Animal = 2,
            Fall = 3,
            Starvation = 4,
            Radiation = 5,
            Drowned = 6,
            Explosion = 7,
            Turret = 8,
            Bradley = 9,
            Helicopter = 10,
            Suicide = 11,
            Other = 12
        }

        private class StoredData
        {
            public int Version = DataVersion;
            public string CurrentWipeId = "initial";
            public string PreviousWipeId = "";
            public Dictionary<ulong, AccountData> Accounts = new Dictionary<ulong, AccountData>();
        }

        // Слой статистики. Один и тот же набор счётчиков ведётся трижды:
        // текущий вайп, прошлый вайп и всё время.
        private class StatLayer
        {
            public long PlayerKills;
            public long NpcKills;
            public long AnimalKills;
            public long Deaths;
            public long Headshots;
            public long HitsLanded;
            public long ShotsFired;
            public double DamageDealt;
            public double DamageReceived;

            public long CurrentKillStreak;
            public long BestKillStreak;
            public float LongestKillDistance;
            public int LongestKillWeaponId;

            public long KillsHead;
            public long KillsTorso;
            public long KillsLimbs;

            public long Assists;
            public long TimesWounded;
            public long RevivedByOthers;
            public long RevivedOthers;

            public long[] DeathsByCause = new long[DeathCauseCount];

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

            public double BradleyDamage;
            public double HelicopterDamage;
            public long BradleyKills;
            public long HelicopterKills;
            public long HackableCratesOpened;
            public long AirdropsLooted;
            public long SignalsCalled;

            public long DoorsDestroyed;
            public long CupboardsDestroyed;
            public long OwnBlocksLost;
            public long[] BlocksByGrade = new long[GradeCount];
            public long[] DoorsByGrade = new long[GradeCount];

            public long PlaySeconds;
            public int Sessions;
            public long[] HourMinutes = new long[24];

            public Dictionary<int, WeaponStat> Weapons = new Dictionary<int, WeaponStat>();
            public Dictionary<ulong, DuelStat> KilledPlayers = new Dictionary<ulong, DuelStat>();
            public Dictionary<ulong, DuelStat> KilledByPlayers = new Dictionary<ulong, DuelStat>();

            public Dictionary<string, long> Resources = NewMap();
            public Dictionary<string, long> LootedContainers = NewMap();
            public Dictionary<string, long> CraftedItems = NewMap();
            public Dictionary<string, long> NpcKillsByPrefab = NewMap();
            public Dictionary<string, long> AnimalKillsByPrefab = NewMap();
            public Dictionary<string, long> ExplosivesByPrefab = NewMap();
            public Dictionary<string, long> BuiltEntities = NewMap();
            public Dictionary<string, long> CollectiblesByPrefab = NewMap();

            public static Dictionary<string, long> NewMap()
            {
                return new Dictionary<string, long>(StringComparer.OrdinalIgnoreCase);
            }

            public void Normalize()
            {
                if (DeathsByCause == null || DeathsByCause.Length != DeathCauseCount)
                    DeathsByCause = Resize(DeathsByCause, DeathCauseCount);
                if (BlocksByGrade == null || BlocksByGrade.Length != GradeCount)
                    BlocksByGrade = Resize(BlocksByGrade, GradeCount);
                if (DoorsByGrade == null || DoorsByGrade.Length != GradeCount)
                    DoorsByGrade = Resize(DoorsByGrade, GradeCount);
                if (HourMinutes == null || HourMinutes.Length != 24)
                    HourMinutes = Resize(HourMinutes, 24);

                if (Weapons == null) Weapons = new Dictionary<int, WeaponStat>();
                if (KilledPlayers == null) KilledPlayers = new Dictionary<ulong, DuelStat>();
                if (KilledByPlayers == null) KilledByPlayers = new Dictionary<ulong, DuelStat>();

                if (Resources == null) Resources = NewMap();
                if (LootedContainers == null) LootedContainers = NewMap();
                if (CraftedItems == null) CraftedItems = NewMap();
                if (NpcKillsByPrefab == null) NpcKillsByPrefab = NewMap();
                if (AnimalKillsByPrefab == null) AnimalKillsByPrefab = NewMap();
                if (ExplosivesByPrefab == null) ExplosivesByPrefab = NewMap();
                if (BuiltEntities == null) BuiltEntities = NewMap();
                if (CollectiblesByPrefab == null) CollectiblesByPrefab = NewMap();

                if (CurrentKillStreak < 0) CurrentKillStreak = 0;
                if (BestKillStreak < CurrentKillStreak) BestKillStreak = CurrentKillStreak;
            }

            private static long[] Resize(long[] source, int size)
            {
                var result = new long[size];
                if (source != null)
                {
                    int count = source.Length < size ? source.Length : size;
                    for (int i = 0; i < count; i++) result[i] = source[i];
                }
                return result;
            }
        }

        private class WeaponStat
        {
            public long Kills;
            public long Shots;
            public long Hits;
            public long Headshots;
            public double Damage;
        }

        private class DuelStat
        {
            public long Count;
            public string Name = "";
        }

        private class SessionRecord
        {
            public long StartedUtc;
            public long EndedUtc;
            public long Seconds;
        }

        private class AccountData
        {
            public ulong UserId;
            public string Name = "";

            // Уровень и EXP не разрезаются по вайпам: прогресс аккаунта сквозной.
            public int Level;
            public long Exp;
            public long TotalExp;

            public long FirstSeenUtc;
            public long LastSeenUtc;
            public int WipesPlayed;
            public string LastWipeId = "";

            public StatLayer Current = new StatLayer();
            public StatLayer Previous = new StatLayer();
            public StatLayer Lifetime = new StatLayer();

            public long[] DayPlaySeconds = new long[ActivityDays];
            public long DayAnchorUtc;
            public List<SessionRecord> RecentSessions = new List<SessionRecord>();

            // Данные версии 1 хранили счётчики плоско в корне аккаунта.
            // Свойства ниже доступны только на запись: Newtonsoft читает их из
            // старого файла и складывает во «всё время», но обратно не пишет,
            // поэтому формат сам собой переходит на версию 2.
            [JsonProperty("PlaySeconds")]
            public long LegacyPlaySeconds { set { Lifetime.PlaySeconds = value; } }
            [JsonProperty("Sessions")]
            public int LegacySessions { set { Lifetime.Sessions = value; } }
            [JsonProperty("PlayerKills")]
            public long LegacyPlayerKills { set { Lifetime.PlayerKills = value; } }
            [JsonProperty("NpcKills")]
            public long LegacyNpcKills { set { Lifetime.NpcKills = value; } }
            [JsonProperty("AnimalKills")]
            public long LegacyAnimalKills { set { Lifetime.AnimalKills = value; } }
            [JsonProperty("Deaths")]
            public long LegacyDeaths { set { Lifetime.Deaths = value; } }
            [JsonProperty("Headshots")]
            public long LegacyHeadshots { set { Lifetime.Headshots = value; } }
            [JsonProperty("HitsLanded")]
            public long LegacyHitsLanded { set { Lifetime.HitsLanded = value; } }
            [JsonProperty("ShotsFired")]
            public long LegacyShotsFired { set { Lifetime.ShotsFired = value; } }
            [JsonProperty("DamageDealt")]
            public double LegacyDamageDealt { set { Lifetime.DamageDealt = value; } }
            [JsonProperty("DamageReceived")]
            public double LegacyDamageReceived { set { Lifetime.DamageReceived = value; } }
            [JsonProperty("GatheredTotal")]
            public long LegacyGatheredTotal { set { Lifetime.GatheredTotal = value; } }
            [JsonProperty("GatherEvents")]
            public long LegacyGatherEvents { set { Lifetime.GatherEvents = value; } }
            [JsonProperty("CollectiblesPicked")]
            public long LegacyCollectiblesPicked { set { Lifetime.CollectiblesPicked = value; } }
            [JsonProperty("LootContainers")]
            public long LegacyLootContainers { set { Lifetime.LootContainers = value; } }
            [JsonProperty("CraftOperations")]
            public long LegacyCraftOperations { set { Lifetime.CraftOperations = value; } }
            [JsonProperty("CraftedItemsTotal")]
            public long LegacyCraftedItemsTotal { set { Lifetime.CraftedItemsTotal = value; } }
            [JsonProperty("BuildingPiecesPlaced")]
            public long LegacyBuildingPiecesPlaced { set { Lifetime.BuildingPiecesPlaced = value; } }
            [JsonProperty("DeployablesPlaced")]
            public long LegacyDeployablesPlaced { set { Lifetime.DeployablesPlaced = value; } }
            [JsonProperty("StructuresUpgraded")]
            public long LegacyStructuresUpgraded { set { Lifetime.StructuresUpgraded = value; } }
            [JsonProperty("StructuresDestroyed")]
            public long LegacyStructuresDestroyed { set { Lifetime.StructuresDestroyed = value; } }
            [JsonProperty("BarrelsDestroyed")]
            public long LegacyBarrelsDestroyed { set { Lifetime.BarrelsDestroyed = value; } }
            [JsonProperty("ExplosivesUsed")]
            public long LegacyExplosivesUsed { set { Lifetime.ExplosivesUsed = value; } }
            [JsonProperty("RocketsFired")]
            public long LegacyRocketsFired { set { Lifetime.RocketsFired = value; } }
            [JsonProperty("Resources")]
            public Dictionary<string, long> LegacyResources { set { CopyLegacy(value, Lifetime.Resources); } }
            [JsonProperty("LootedContainers")]
            public Dictionary<string, long> LegacyLootedContainers { set { CopyLegacy(value, Lifetime.LootedContainers); } }
            [JsonProperty("CraftedItems")]
            public Dictionary<string, long> LegacyCraftedItems { set { CopyLegacy(value, Lifetime.CraftedItems); } }
            [JsonProperty("NpcKillsByPrefab")]
            public Dictionary<string, long> LegacyNpcKillsByPrefab { set { CopyLegacy(value, Lifetime.NpcKillsByPrefab); } }
            [JsonProperty("AnimalKillsByPrefab")]
            public Dictionary<string, long> LegacyAnimalKillsByPrefab { set { CopyLegacy(value, Lifetime.AnimalKillsByPrefab); } }
            [JsonProperty("ExplosivesByPrefab")]
            public Dictionary<string, long> LegacyExplosivesByPrefab { set { CopyLegacy(value, Lifetime.ExplosivesByPrefab); } }
            [JsonProperty("BuiltEntities")]
            public Dictionary<string, long> LegacyBuiltEntities { set { CopyLegacy(value, Lifetime.BuiltEntities); } }
            [JsonProperty("CollectiblesByPrefab")]
            public Dictionary<string, long> LegacyCollectiblesByPrefab { set { CopyLegacy(value, Lifetime.CollectiblesByPrefab); } }

            private static void CopyLegacy(Dictionary<string, long> source, Dictionary<string, long> target)
            {
                if (source == null || target == null) return;
                foreach (var pair in source)
                {
                    if (string.IsNullOrEmpty(pair.Key)) continue;
                    target[pair.Key] = pair.Value;
                }
            }
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
            if (_data.PreviousWipeId == null) _data.PreviousWipeId = "";

            // Файл версии 1 хранил счётчики плоско. Newtonsoft уже разложил их
            // во «всё время» через write-only свойства AccountData, здесь остаётся
            // только зафиксировать факт перехода.
            bool migrated = _data.Version < DataVersion;

            foreach (AccountData account in _data.Accounts.Values)
                Normalize(account);

            if (migrated)
            {
                _data.Version = DataVersion;
                _dirty = true;
                Puts("Данные переведены на версию " + DataVersion.ToString(CultureInfo.InvariantCulture) +
                     ": перенесено аккаунтов во «всё время» — " +
                     _data.Accounts.Count.ToString(CultureInfo.InvariantCulture) +
                     ". Текущий вайп начат с нуля.");
            }
        }

        private void SaveData(bool force = false)
        {
            if (!force && !_dirty) return;
            try
            {
                // Сначала полностью пишем временный файл и только потом подменяем
                // им основной. Обрыв записи не оставит половину JSON вместо данных.
                Interface.Oxide.DataFileSystem.WriteObject(TempFileName, _data);
                if (!SwapDataFile())
                    Interface.Oxide.DataFileSystem.WriteObject(DataFileName, _data);

                _dirty = false;
            }
            catch (Exception ex)
            {
                PrintError("Не удалось сохранить AccountSystem: " + ex.Message);
            }
        }

        private bool SwapDataFile()
        {
            try
            {
                string root = Interface.Oxide.DataDirectory;
                if (string.IsNullOrEmpty(root)) return false;

                string temp = Path.Combine(root, TempFileName + ".json");
                string target = Path.Combine(root, DataFileName + ".json");
                if (!File.Exists(temp)) return false;

                if (File.Exists(target)) File.Replace(temp, target, null);
                else File.Move(temp, target);

                return true;
            }
            catch (Exception ex)
            {
                PrintWarning("Атомарная замена файла данных не удалась (" + ex.Message +
                             "), запись выполнена напрямую.");
                return false;
            }
        }

        private void Normalize(AccountData a)
        {
            if (a == null) return;

            if (a.Current == null) a.Current = new StatLayer();
            if (a.Previous == null) a.Previous = new StatLayer();
            if (a.Lifetime == null) a.Lifetime = new StatLayer();

            a.Current.Normalize();
            a.Previous.Normalize();
            a.Lifetime.Normalize();

            if (a.DayPlaySeconds == null || a.DayPlaySeconds.Length != ActivityDays)
            {
                var days = new long[ActivityDays];
                if (a.DayPlaySeconds != null)
                {
                    int count = Math.Min(a.DayPlaySeconds.Length, ActivityDays);
                    for (int i = 0; i < count; i++) days[i] = a.DayPlaySeconds[i];
                }
                a.DayPlaySeconds = days;
            }

            if (a.RecentSessions == null) a.RecentSessions = new List<SessionRecord>();
            while (a.RecentSessions.Count > MaxRecentSessions)
                a.RecentSessions.RemoveAt(a.RecentSessions.Count - 1);

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
            _sessionStart.Clear();
            _lootEntityPlayers.Clear();
            _countedExplosives.Clear();
            _recentAttackers.Clear();
            _expTopCache.Clear();
            _expTopRowsCache.Clear();
            _expTopRankCache.Clear();
            _expTopCacheUntil = 0;
            _rankCacheUntil = 0;
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
            // Фиксируем всё, что накопилось до вайпа, иначе текущая сессия
            // попадёт уже в обнулённый слой.
            FlushSessions();

            _data.PreviousWipeId = _data.CurrentWipeId ?? "initial";
            _data.CurrentWipeId = string.IsNullOrEmpty(filename)
                ? "wipe-" + Now().ToString(CultureInfo.InvariantCulture)
                : filename;

            foreach (AccountData account in _data.Accounts.Values)
            {
                if (account == null) continue;
                Normalize(account);

                // Текущий вайп становится прошлым, текущий начинается с нуля.
                // Слой «всё время», уровень и EXP не трогаются.
                account.Previous = account.Current;
                account.Current = new StatLayer();
            }

            _lootEntityPlayers.Clear();
            _countedExplosives.Clear();
            _recentAttackers.Clear();
            _expTopCacheUntil = 0;
            _rankCacheUntil = 0;
            _dirty = true;
            SaveData(true);

            Puts("Вайп: статистика текущего вайпа перенесена в «прошлый вайп» для " +
                 _data.Accounts.Count.ToString(CultureInfo.InvariantCulture) + " аккаунтов.");
        }

        private void OnPlayerConnected(BasePlayer player)
        {
            StartSession(player, true);
            _expTopCacheUntil = 0;
            _rankCacheUntil = 0;
        }

        private void OnPlayerDisconnected(BasePlayer player, string reason)
        {
            if (!Human(player)) return;
            FlushSession(player.userID, true);
            AccountData a = Ensure(player);
            a.LastSeenUtc = Now();
            _recentAttackers.Remove(player.userID);
            _dirty = true;
            _expTopCacheUntil = 0;
            _rankCacheUntil = 0;
        }

        private void StartSession(BasePlayer player, bool countSession)
        {
            if (!Human(player)) return;

            AccountData a = Ensure(player);
            long now = Now();
            a.LastSeenUtc = now;

            if (countSession || a.Lifetime.Sessions <= 0)
            {
                a.Current.Sessions++;
                a.Lifetime.Sessions++;
            }

            if (a.LastWipeId != _data.CurrentWipeId)
            {
                a.LastWipeId = _data.CurrentWipeId;
                a.WipesPlayed++;
            }

            _sessionCheckpoint[player.userID] = now;
            if (!_sessionStart.ContainsKey(player.userID))
                _sessionStart[player.userID] = now;

            _dirty = true;
        }

        private void FlushSessions()
        {
            foreach (BasePlayer player in BasePlayer.activePlayerList)
                if (Human(player))
                    FlushSession(player.userID, false);
        }

        private void FlushSession(ulong userId, bool closing)
        {
            long started;
            if (!_sessionCheckpoint.TryGetValue(userId, out started)) return;

            long now = Now();
            long delta = Math.Max(0, now - started);
            if (delta > 0)
            {
                AccountData a = Ensure(userId);
                a.Current.PlaySeconds += delta;
                a.Lifetime.PlaySeconds += delta;
                a.LastSeenUtc = now;

                AddActivity(a, started, now, delta);
                _dirty = true;
            }

            BasePlayer online = BasePlayer.FindByID(userId);
            bool stillOnline = !closing && online != null && online.IsConnected;

            if (stillOnline)
            {
                _sessionCheckpoint[userId] = now;
                return;
            }

            long sessionStart;
            if (_sessionStart.TryGetValue(userId, out sessionStart))
            {
                AccountData a = Ensure(userId);
                PushSession(a, sessionStart, now);
                _sessionStart.Remove(userId);
            }

            _sessionCheckpoint.Remove(userId);
        }

        // Раскладывает отыгранный отрезок по суткам и по часам суток UTC.
        private void AddActivity(AccountData a, long fromUtc, long toUtc, long seconds)
        {
            ShiftActivityDays(a, toUtc);

            int today = a.DayPlaySeconds.Length - 1;
            if (today >= 0) a.DayPlaySeconds[today] = SafeAdd(a.DayPlaySeconds[today], seconds);

            // Часы считаем в минутах: отрезок может пересекать границу часа.
            long cursor = fromUtc;
            int guard = 0;
            while (cursor < toUtc && guard++ < 512)
            {
                DateTime moment = DateTimeOffset.FromUnixTimeSeconds(cursor).UtcDateTime;
                long nextHour = cursor + (3600 - (moment.Minute * 60 + moment.Second));
                long end = nextHour < toUtc ? nextHour : toUtc;
                long chunk = end - cursor;
                if (chunk <= 0) break;

                int hour = moment.Hour;
                if (hour >= 0 && hour < 24)
                    a.Current.HourMinutes[hour] = SafeAdd(a.Current.HourMinutes[hour], chunk / 60);

                cursor = end;
            }
        }

        // Массив всегда заканчивается сегодняшним днём: при смене суток сдвигаем влево.
        private void ShiftActivityDays(AccountData a, long nowUtc)
        {
            long today = nowUtc / 86400L;
            if (a.DayAnchorUtc <= 0)
            {
                a.DayAnchorUtc = today;
                return;
            }

            long gap = today - a.DayAnchorUtc;
            if (gap <= 0) return;

            int length = a.DayPlaySeconds.Length;
            if (gap >= length)
            {
                Array.Clear(a.DayPlaySeconds, 0, length);
            }
            else
            {
                int shift = (int)gap;
                Array.Copy(a.DayPlaySeconds, shift, a.DayPlaySeconds, 0, length - shift);
                Array.Clear(a.DayPlaySeconds, length - shift, shift);
            }

            a.DayAnchorUtc = today;
        }

        private void PushSession(AccountData a, long startedUtc, long endedUtc)
        {
            long seconds = Math.Max(0, endedUtc - startedUtc);
            if (seconds <= 0) return;

            a.RecentSessions.Insert(0, new SessionRecord
            {
                StartedUtc = startedUtc,
                EndedUtc = endedUtc,
                Seconds = seconds
            });

            while (a.RecentSessions.Count > MaxRecentSessions)
                a.RecentSessions.RemoveAt(a.RecentSessions.Count - 1);
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

        #region Tracking - Shared
        // Кольцевой буфер последних атакующих на жертве. Массивы выделяются один
        // раз на игрока, поэтому хук урона не аллоцирует.
        private class RecentAttackers
        {
            public readonly ulong[] Ids = new ulong[RecentAttackerSlots];
            public readonly long[] Times = new long[RecentAttackerSlots];
            public readonly double[] Damage = new double[RecentAttackerSlots];
            public int Next;

            public void Record(ulong attacker, long now, float amount)
            {
                for (int i = 0; i < RecentAttackerSlots; i++)
                {
                    if (Ids[i] != attacker) continue;
                    Times[i] = now;
                    Damage[i] += amount;
                    return;
                }

                int slot = Next;
                Ids[slot] = attacker;
                Times[slot] = now;
                Damage[slot] = amount;
                Next = slot + 1 >= RecentAttackerSlots ? 0 : slot + 1;
            }

            public void Clear()
            {
                for (int i = 0; i < RecentAttackerSlots; i++)
                {
                    Ids[i] = 0UL;
                    Times[i] = 0L;
                    Damage[i] = 0d;
                }
                Next = 0;
            }
        }

        private RecentAttackers AttackersOf(ulong victimId)
        {
            RecentAttackers list;
            if (!_recentAttackers.TryGetValue(victimId, out list))
                _recentAttackers[victimId] = list = new RecentAttackers();
            return list;
        }

        // Оружие адресуется itemid: в горячих хуках строк быть не должно.
        private static WeaponStat WeaponSlot(StatLayer layer, int itemId)
        {
            WeaponStat stat;
            if (layer.Weapons.TryGetValue(itemId, out stat) && stat != null)
                return stat;

            if (layer.Weapons.Count >= MaxWeaponEntries)
                EvictWeakestWeapon(layer.Weapons);

            stat = new WeaponStat();
            layer.Weapons[itemId] = stat;
            return stat;
        }

        private static void EvictWeakestWeapon(Dictionary<int, WeaponStat> map)
        {
            int worstKey = 0;
            long worstScore = long.MaxValue;
            bool found = false;

            foreach (var pair in map)
            {
                WeaponStat value = pair.Value;
                long score = value == null ? 0 : value.Kills * 1000 + value.Hits;
                if (found && score >= worstScore) continue;
                worstScore = score;
                worstKey = pair.Key;
                found = true;
            }

            if (found) map.Remove(worstKey);
        }

        private static void BumpDuel(Dictionary<ulong, DuelStat> map, ulong otherId, string otherName)
        {
            DuelStat entry;
            if (!map.TryGetValue(otherId, out entry) || entry == null)
            {
                if (map.Count >= MaxDuelEntries)
                {
                    ulong worstKey = 0UL;
                    long worstCount = long.MaxValue;
                    bool found = false;

                    foreach (var pair in map)
                    {
                        long count = pair.Value == null ? 0 : pair.Value.Count;
                        if (found && count >= worstCount) continue;
                        worstCount = count;
                        worstKey = pair.Key;
                        found = true;
                    }

                    if (found) map.Remove(worstKey);
                }

                map[otherId] = entry = new DuelStat();
            }

            entry.Count = SafeAdd(entry.Count, 1);
            // Ник запоминается на момент последней встречи.
            if (!string.IsNullOrEmpty(otherName)) entry.Name = otherName;
        }

        private static int WeaponIdOf(HitInfo info)
        {
            if (info == null) return 0;

            AttackEntity weapon = info.Weapon;
            if (weapon != null)
            {
                ItemDefinition definition = weapon.GetOwnerItemDefinition();
                if (definition != null) return definition.itemid;
            }

            return 0;
        }

        private static float KillDistance(HitInfo info)
        {
            if (info == null) return 0f;
            // Так же дистанцию считает и сама игра, Assembly-CSharp.cs:313724.
            return info.IsProjectile()
                ? info.ProjectileDistance
                : Vector3.Distance(info.PointStart, info.HitPositionWorld);
        }

        private static DeathCause CauseOf(BaseCombatEntity victim, HitInfo info)
        {
            if (info == null) return DeathCause.Other;

            DamageTypeList types = info.damageTypes;
            if (types != null)
            {
                if (types.Has(DamageType.Suicide)) return DeathCause.Suicide;
                if (types.Has(DamageType.Fall)) return DeathCause.Fall;
                if (types.Has(DamageType.Drowned)) return DeathCause.Drowned;
                if (types.Has(DamageType.Radiation)) return DeathCause.Radiation;
                if (types.Has(DamageType.Hunger) || types.Has(DamageType.Thirst)) return DeathCause.Starvation;
            }

            BaseEntity initiator = info.Initiator;
            if (initiator != null)
            {
                if (initiator is AutoTurret) return DeathCause.Turret;
                if (initiator is BradleyAPC) return DeathCause.Bradley;
                if (initiator is BaseHelicopter) return DeathCause.Helicopter;
            }

            BasePlayer attacker = info.InitiatorPlayer;
            if (attacker != null)
                return Human(attacker) ? DeathCause.Player : DeathCause.Npc;

            if (initiator is BaseAnimalNPC || initiator is BaseNpc) return DeathCause.Animal;

            if (types != null && types.Has(DamageType.Explosion)) return DeathCause.Explosion;

            return DeathCause.Other;
        }

        private static void CountDeathCause(StatLayer layer, DeathCause cause)
        {
            int index = (int)cause;
            if (index < 0 || index >= layer.DeathsByCause.Length) index = (int)DeathCause.Other;
            layer.DeathsByCause[index] = SafeAdd(layer.DeathsByCause[index], 1);
        }

        private static void CountBodyPart(StatLayer layer, HitInfo info)
        {
            HitArea area = info == null ? (HitArea)0 : info.boneArea;

            if (area == HitArea.Head)
            {
                layer.KillsHead = SafeAdd(layer.KillsHead, 1);
                return;
            }

            if (area == HitArea.Chest || area == HitArea.Stomach)
            {
                layer.KillsTorso = SafeAdd(layer.KillsTorso, 1);
                return;
            }

            if (area == HitArea.Arm || area == HitArea.Hand || area == HitArea.Leg || area == HitArea.Foot)
            {
                layer.KillsLimbs = SafeAdd(layer.KillsLimbs, 1);
                return;
            }

            // Ближний бой и взрывы часто не заполняют boneArea.
            if (info != null && IsHeadshot(info)) layer.KillsHead = SafeAdd(layer.KillsHead, 1);
            else layer.KillsTorso = SafeAdd(layer.KillsTorso, 1);
        }

        private static int GradeIndex(BuildingGrade.Enum grade)
        {
            int index = (int)grade;
            return index < 0 || index >= GradeCount ? 0 : index;
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

            Add(a.Current.Resources, shortname, item.amount);
            Add(a.Lifetime.Resources, shortname, item.amount);
            a.Current.GatheredTotal += item.amount;
            a.Lifetime.GatheredTotal += item.amount;
            a.Current.GatherEvents++;
            a.Lifetime.GatherEvents++;
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
            a.Current.CollectiblesPicked++;
            a.Lifetime.CollectiblesPicked++;
            Add(a.Current.CollectiblesByPrefab, prefab, 1);
            Add(a.Lifetime.CollectiblesByPrefab, prefab, 1);

            if (item != null && item.info != null && item.amount > 0)
            {
                Add(a.Current.Resources, item.info.shortname, item.amount);
                Add(a.Lifetime.Resources, item.info.shortname, item.amount);
                a.Current.GatheredTotal += item.amount;
                a.Lifetime.GatheredTotal += item.amount;
            }

            _dirty = true;
            AddExpInternal(a, player, Math.Max(0, _config.Exp.Collectible), "collectible:" + prefab, true);
        }

        private void OnLootEntity(BasePlayer player, BaseEntity targetEntity)
        {
            if (!Human(player) || targetEntity == null) return;

            LootContainer loot = targetEntity as LootContainer;
            if (loot == null)
            {
                TrackSpecialLoot(player, targetEntity);
                return;
            }

            ulong entityId = NetId(targetEntity);
            if (entityId == 0) return;

            HashSet<ulong> players;
            if (!_lootEntityPlayers.TryGetValue(entityId, out players))
                _lootEntityPlayers[entityId] = players = new HashSet<ulong>();

            if (!players.Add(player.userID))
                return;

            AccountData a = Ensure(player);
            string prefab = ShortName(targetEntity);
            a.Current.LootContainers++;
            a.Lifetime.LootContainers++;
            Add(a.Current.LootedContainers, prefab, 1);
            Add(a.Lifetime.LootedContainers, prefab, 1);

            if (targetEntity is SupplyDrop)
            {
                a.Current.AirdropsLooted++;
                a.Lifetime.AirdropsLooted++;
            }

            _dirty = true;

            AddExpInternal(a, player, LootExp(prefab), "loot:" + prefab, true);
        }

        // Взломанные ящики и аирдропы не наследуют LootContainer, поэтому считаются отдельно.
        private void TrackSpecialLoot(BasePlayer player, BaseEntity entity)
        {
            bool hackable = entity is HackableLockedCrate;
            bool airdrop = entity is SupplyDrop;
            if (!hackable && !airdrop) return;

            ulong entityId = NetId(entity);
            if (entityId == 0) return;

            HashSet<ulong> players;
            if (!_lootEntityPlayers.TryGetValue(entityId, out players))
                _lootEntityPlayers[entityId] = players = new HashSet<ulong>();

            if (!players.Add(player.userID)) return;

            AccountData a = Ensure(player);
            string prefab = ShortName(entity);

            a.Current.LootContainers++;
            a.Lifetime.LootContainers++;
            Add(a.Current.LootedContainers, prefab, 1);
            Add(a.Lifetime.LootedContainers, prefab, 1);

            if (hackable)
            {
                a.Current.HackableCratesOpened++;
                a.Lifetime.HackableCratesOpened++;
            }
            else
            {
                a.Current.AirdropsLooted++;
                a.Lifetime.AirdropsLooted++;
            }

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
            a.Current.CraftOperations++;
            a.Lifetime.CraftOperations++;
            a.Current.CraftedItemsTotal += amount;
            a.Lifetime.CraftedItemsTotal += amount;
            Add(a.Current.CraftedItems, item.info.shortname, amount);
            Add(a.Lifetime.CraftedItems, item.info.shortname, amount);
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
            a.Current.ShotsFired++;
            a.Lifetime.ShotsFired++;

            int weaponId = 0;
            if (projectile != null)
            {
                ItemDefinition definition = projectile.GetOwnerItemDefinition();
                if (definition != null) weaponId = definition.itemid;
            }

            if (weaponId != 0)
            {
                WeaponSlot(a.Current, weaponId).Shots++;
                WeaponSlot(a.Lifetime, weaponId).Shots++;
            }

            _dirty = true;
        }

        private void OnEntityTakeDamage(BaseCombatEntity entity, HitInfo info)
        {
            if (entity == null || info == null) return;

            float amount = info.damageTypes != null ? info.damageTypes.Total() : 0f;
            if (amount <= 0f) return;

            BasePlayer attacker = info.InitiatorPlayer;
            bool attackerIsHuman = Human(attacker) && attacker != entity;

            if (attackerIsHuman)
            {
                AccountData a = Ensure(attacker);
                bool headshot = IsHeadshot(info);

                a.Current.DamageDealt += amount;
                a.Lifetime.DamageDealt += amount;
                a.Current.HitsLanded++;
                a.Lifetime.HitsLanded++;

                if (headshot)
                {
                    a.Current.Headshots++;
                    a.Lifetime.Headshots++;
                }

                int weaponId = WeaponIdOf(info);
                if (weaponId != 0)
                {
                    WeaponStat current = WeaponSlot(a.Current, weaponId);
                    WeaponStat lifetime = WeaponSlot(a.Lifetime, weaponId);
                    current.Hits++;
                    lifetime.Hits++;
                    current.Damage += amount;
                    lifetime.Damage += amount;
                    if (headshot)
                    {
                        current.Headshots++;
                        lifetime.Headshots++;
                    }
                }

                if (entity is BradleyAPC)
                {
                    a.Current.BradleyDamage += amount;
                    a.Lifetime.BradleyDamage += amount;
                }
                else if (entity is BaseHelicopter)
                {
                    a.Current.HelicopterDamage += amount;
                    a.Lifetime.HelicopterDamage += amount;
                }

                _dirty = true;
            }

            BasePlayer victim = entity as BasePlayer;
            if (Human(victim))
            {
                AccountData v = Ensure(victim);
                v.Current.DamageReceived += amount;
                v.Lifetime.DamageReceived += amount;
                _dirty = true;

                // Список последних атакующих нужен, чтобы при смерти раздать ассисты.
                if (attackerIsHuman)
                    AttackersOf(victim.userID).Record(attacker.userID, Now(), amount);
            }
        }

        private void OnEntityDeath(BaseCombatEntity entity, HitInfo info)
        {
            if (entity == null) return;

            BasePlayer attacker = info != null ? info.InitiatorPlayer : null;
            BasePlayer victim = entity as BasePlayer;

            if (Human(victim))
            {
                HandlePlayerDeath(victim, attacker, info);
                return;
            }

            if (entity is BradleyAPC || entity is BaseHelicopter)
            {
                TrackVehicleKill(entity, attacker);
                return;
            }

            if (!Human(attacker))
            {
                TrackStructureLoss(entity, info);
                return;
            }

            AccountData a = Ensure(attacker);
            string prefab = ShortName(entity);

            if (victim != null && victim.IsNpc)
            {
                a.Current.NpcKills++;
                a.Lifetime.NpcKills++;
                Add(a.Current.NpcKillsByPrefab, prefab, 1);
                Add(a.Lifetime.NpcKillsByPrefab, prefab, 1);
                _dirty = true;
                AddExpInternal(a, attacker, Math.Max(0, _config.Exp.NpcKill), "kill:npc:" + prefab, true);
                return;
            }

            if (entity is BaseAnimalNPC)
            {
                a.Current.AnimalKills++;
                a.Lifetime.AnimalKills++;
                Add(a.Current.AnimalKillsByPrefab, prefab, 1);
                Add(a.Lifetime.AnimalKillsByPrefab, prefab, 1);
                _dirty = true;
                AddExpInternal(a, attacker, Math.Max(0, _config.Exp.AnimalKill), "kill:animal:" + prefab, true);
                return;
            }

            if (prefab.IndexOf("barrel", StringComparison.OrdinalIgnoreCase) >= 0)
            {
                a.Current.BarrelsDestroyed++;
                a.Lifetime.BarrelsDestroyed++;
                _dirty = true;
                AddExpInternal(a, attacker, Math.Max(0, _config.Exp.BarrelDestroyed), "destroy:barrel", true);
                return;
            }

            TrackRaidKill(a, attacker, entity);
            TrackStructureLoss(entity, info);
        }

        private void HandlePlayerDeath(BasePlayer victim, BasePlayer attacker, HitInfo info)
        {
            AccountData victimAccount = Ensure(victim);
            long now = Now();

            victimAccount.Current.Deaths++;
            victimAccount.Lifetime.Deaths++;

            DeathCause cause = CauseOf(victim, info);
            CountDeathCause(victimAccount.Current, cause);
            CountDeathCause(victimAccount.Lifetime, cause);

            // Серия убийств обрывается смертью.
            victimAccount.Current.CurrentKillStreak = 0;
            victimAccount.Lifetime.CurrentKillStreak = 0;
            _dirty = true;

            bool killerIsHuman = Human(attacker) && attacker != victim;

            if (killerIsHuman)
            {
                AccountData killer = Ensure(attacker);

                killer.Current.PlayerKills++;
                killer.Lifetime.PlayerKills++;

                killer.Current.CurrentKillStreak = SafeAdd(killer.Current.CurrentKillStreak, 1);
                killer.Lifetime.CurrentKillStreak = SafeAdd(killer.Lifetime.CurrentKillStreak, 1);
                if (killer.Current.CurrentKillStreak > killer.Current.BestKillStreak)
                    killer.Current.BestKillStreak = killer.Current.CurrentKillStreak;
                if (killer.Lifetime.CurrentKillStreak > killer.Lifetime.BestKillStreak)
                    killer.Lifetime.BestKillStreak = killer.Lifetime.CurrentKillStreak;

                CountBodyPart(killer.Current, info);
                CountBodyPart(killer.Lifetime, info);

                int weaponId = WeaponIdOf(info);
                if (weaponId != 0)
                {
                    WeaponSlot(killer.Current, weaponId).Kills++;
                    WeaponSlot(killer.Lifetime, weaponId).Kills++;
                }

                float distance = KillDistance(info);
                if (distance > killer.Current.LongestKillDistance)
                {
                    killer.Current.LongestKillDistance = distance;
                    killer.Current.LongestKillWeaponId = weaponId;
                }
                if (distance > killer.Lifetime.LongestKillDistance)
                {
                    killer.Lifetime.LongestKillDistance = distance;
                    killer.Lifetime.LongestKillWeaponId = weaponId;
                }

                BumpDuel(killer.Current.KilledPlayers, victim.userID, victim.displayName);
                BumpDuel(killer.Lifetime.KilledPlayers, victim.userID, victim.displayName);
                BumpDuel(victimAccount.Current.KilledByPlayers, attacker.userID, attacker.displayName);
                BumpDuel(victimAccount.Lifetime.KilledByPlayers, attacker.userID, attacker.displayName);

                AddExpInternal(killer, attacker, Math.Max(0, _config.Exp.PlayerKill), "kill:player", true);
            }

            AwardAssists(victim, killerIsHuman ? attacker.userID : 0UL, now);
        }

        // Ассист получает каждый, кто бил жертву в последние AssistWindowSeconds
        // секунд и не оказался убийцей.
        private void AwardAssists(BasePlayer victim, ulong killerId, long now)
        {
            RecentAttackers list;
            if (!_recentAttackers.TryGetValue(victim.userID, out list)) return;

            for (int i = 0; i < RecentAttackerSlots; i++)
            {
                ulong attackerId = list.Ids[i];
                if (attackerId == 0UL || attackerId == killerId) continue;
                if (now - list.Times[i] > AssistWindowSeconds) continue;
                if (list.Damage[i] <= 0d) continue;

                AccountData helper;
                if (!_data.Accounts.TryGetValue(attackerId, out helper) || helper == null) continue;

                helper.Current.Assists = SafeAdd(helper.Current.Assists, 1);
                helper.Lifetime.Assists = SafeAdd(helper.Lifetime.Assists, 1);
                _dirty = true;
            }

            list.Clear();
        }

        private void TrackVehicleKill(BaseCombatEntity entity, BasePlayer attacker)
        {
            if (!Human(attacker)) return;

            AccountData a = Ensure(attacker);
            if (entity is BradleyAPC)
            {
                a.Current.BradleyKills++;
                a.Lifetime.BradleyKills++;
            }
            else
            {
                a.Current.HelicopterKills++;
                a.Lifetime.HelicopterKills++;
            }

            _dirty = true;
        }

        private void TrackRaidKill(AccountData a, BasePlayer attacker, BaseCombatEntity entity)
        {
            BuildingBlock block = entity as BuildingBlock;
            if (block != null)
            {
                a.Current.StructuresDestroyed++;
                a.Lifetime.StructuresDestroyed++;

                // Чужая постройка считается рейдом, своя — нет.
                if (IsForeign(entity, attacker))
                {
                    int grade = GradeIndex(block.grade);
                    a.Current.BlocksByGrade[grade] = SafeAdd(a.Current.BlocksByGrade[grade], 1);
                    a.Lifetime.BlocksByGrade[grade] = SafeAdd(a.Lifetime.BlocksByGrade[grade], 1);
                }

                _dirty = true;
                AddExpInternal(a, attacker, Math.Max(0, _config.Exp.StructureDestroyed), "destroy:structure", true);
                return;
            }

            if (entity is Door)
            {
                if (IsForeign(entity, attacker))
                {
                    a.Current.DoorsDestroyed++;
                    a.Lifetime.DoorsDestroyed++;

                    int grade = DoorGradeIndex(entity);
                    a.Current.DoorsByGrade[grade] = SafeAdd(a.Current.DoorsByGrade[grade], 1);
                    a.Lifetime.DoorsByGrade[grade] = SafeAdd(a.Lifetime.DoorsByGrade[grade], 1);
                    _dirty = true;
                }
                return;
            }

            if (entity is BuildingPrivlidge && IsForeign(entity, attacker))
            {
                a.Current.CupboardsDestroyed++;
                a.Lifetime.CupboardsDestroyed++;
                _dirty = true;
            }
        }

        // Потери владельца постройки: считаем по OwnerID жертвы.
        private void TrackStructureLoss(BaseCombatEntity entity, HitInfo info)
        {
            if (!(entity is BuildingBlock)) return;

            ulong ownerId = entity.OwnerID;
            if (!ownerId.IsSteamId()) return;

            BasePlayer attacker = info != null ? info.InitiatorPlayer : null;
            if (attacker != null && attacker.userID == ownerId) return;

            AccountData owner;
            if (!_data.Accounts.TryGetValue(ownerId, out owner) || owner == null) return;

            owner.Current.OwnBlocksLost = SafeAdd(owner.Current.OwnBlocksLost, 1);
            owner.Lifetime.OwnBlocksLost = SafeAdd(owner.Lifetime.OwnBlocksLost, 1);
            _dirty = true;
        }

        private void OnPlayerWound(BasePlayer player, HitInfo info)
        {
            if (!Human(player)) return;

            AccountData a = Ensure(player);
            a.Current.TimesWounded = SafeAdd(a.Current.TimesWounded, 1);
            a.Lifetime.TimesWounded = SafeAdd(a.Lifetime.TimesWounded, 1);
            _dirty = true;
        }

        // Подъём руками, Assembly-CSharp.cs:74215.
        private void OnPlayerAssist(BasePlayer target, BasePlayer helper)
        {
            TrackRevive(helper, target);
        }

        // Подъём медикаментами, Assembly-CSharp.cs:86693.
        private void OnPlayerRevive(BasePlayer reviver, BasePlayer target)
        {
            TrackRevive(reviver, target);
        }

        private void TrackRevive(BasePlayer reviver, BasePlayer target)
        {
            if (!Human(reviver) || !Human(target) || reviver == target) return;

            AccountData helper = Ensure(reviver);
            helper.Current.RevivedOthers = SafeAdd(helper.Current.RevivedOthers, 1);
            helper.Lifetime.RevivedOthers = SafeAdd(helper.Lifetime.RevivedOthers, 1);

            AccountData saved = Ensure(target);
            saved.Current.RevivedByOthers = SafeAdd(saved.Current.RevivedByOthers, 1);
            saved.Lifetime.RevivedByOthers = SafeAdd(saved.Lifetime.RevivedByOthers, 1);

            _dirty = true;
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
            Add(a.Current.BuiltEntities, prefab, 1);
            Add(a.Lifetime.BuiltEntities, prefab, 1);

            if (entity is BuildingBlock)
            {
                a.Current.BuildingPiecesPlaced++;
                a.Lifetime.BuildingPiecesPlaced++;
                _dirty = true;
                AddExpInternal(a, player, Math.Max(0, _config.Exp.BuildingPlaced), "build:block", true);
            }
            else
            {
                a.Current.DeployablesPlaced++;
                a.Lifetime.DeployablesPlaced++;
                _dirty = true;
                AddExpInternal(a, player, Math.Max(0, _config.Exp.DeployablePlaced), "build:deployable", true);
            }
        }

        private void OnStructureUpgraded(BuildingBlock block, BasePlayer player, BuildingGrade.Enum type, ulong skin)
        {
            if (block == null || !Human(player)) return;

            AccountData a = Ensure(player);
            a.Current.StructuresUpgraded++;
            a.Lifetime.StructuresUpgraded++;
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

            // Сигнал — не взрывчатка, он вызывает аирдроп.
            if (entity is SupplySignal)
            {
                a.Current.SignalsCalled++;
                a.Lifetime.SignalsCalled++;
                _dirty = true;
                return;
            }

            a.Current.ExplosivesUsed++;
            a.Lifetime.ExplosivesUsed++;
            Add(a.Current.ExplosivesByPrefab, prefab, 1);
            Add(a.Lifetime.ExplosivesByPrefab, prefab, 1);

            if (rocket)
            {
                a.Current.RocketsFired++;
                a.Lifetime.RocketsFired++;
            }

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
            AccountData a = Resolve(userId);
            return a == null ? null : Summary(a);
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
            AccountData a = Resolve(userId);
            return a == null ? null : BuildProfile(a);
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
            FlushSession(userId, true);
            Normalize(a);

            a.Level = 0;
            a.Exp = 0;
            a.TotalExp = 0;

            a.WipesPlayed = 0;
            a.LastWipeId = _data.CurrentWipeId ?? "initial";

            // Сброс затрагивает все три слоя: это полная очистка аккаунта.
            a.Current = new StatLayer();
            a.Previous = new StatLayer();
            a.Lifetime = new StatLayer();

            Array.Clear(a.DayPlaySeconds, 0, a.DayPlaySeconds.Length);
            a.DayAnchorUtc = 0;
            a.RecentSessions.Clear();

            long now = Now();
            a.LastSeenUtc = now;

            BasePlayer target = BasePlayer.FindByID(userId);
            if (target != null && target.IsConnected)
            {
                _sessionCheckpoint[userId] = now;
                _sessionStart[userId] = now;
            }

            // После очистки игрок может заново получить статистику с уже открываемых контейнеров.
            foreach (HashSet<ulong> viewers in _lootEntityPlayers.Values)
                if (viewers != null) viewers.Remove(userId);

            _recentAttackers.Remove(userId);

            _expTopCache.Clear();
            _expTopRowsCache.Clear();
            _expTopRankCache.Clear();
            _expTopCacheUntil = 0;
            _rankCacheUntil = 0;
            _dirty = true;
            SaveData(true);

            if (target != null && target.IsConnected)
                RefreshServerMenu(target);

            Interface.CallHook("OnAccountStatsReset", admin, userId);
            admin.ChatMessage("<color=#65A30D>[ACCOUNT]</color> Статистика игрока <color=#CEC5BB>" +
                (a.Name ?? userId.ToString(CultureInfo.InvariantCulture)) + "</color> полностью очищена.");
            return true;
        }

        // Позиция игрока среди всех аккаунтов за текущий вайп. Считается только
        // при открытии профиля и живёт RankCacheSeconds секунд, как и кеш топа.
        private readonly Dictionary<ulong, int> _rankKills = new Dictionary<ulong, int>();
        private readonly Dictionary<ulong, int> _rankKd = new Dictionary<ulong, int>();
        private readonly Dictionary<ulong, int> _rankGathered = new Dictionary<ulong, int>();
        private readonly Dictionary<ulong, int> _rankTime = new Dictionary<ulong, int>();
        private readonly Dictionary<ulong, int> _rankLevel = new Dictionary<ulong, int>();
        private readonly List<AccountData> _rankScratch = new List<AccountData>();
        private long _rankCacheUntil;
        private int _rankTotal;

        private static double KdOf(StatLayer layer)
        {
            if (layer == null) return 0d;
            return layer.Deaths <= 0 ? layer.PlayerKills : layer.PlayerKills / (double)layer.Deaths;
        }

        private void EnsureRankCache()
        {
            long now = Now();
            if (_rankCacheUntil > now && _rankTotal > 0) return;

            _rankScratch.Clear();
            foreach (AccountData account in _data.Accounts.Values)
            {
                if (account == null || !account.UserId.IsSteamId()) continue;
                Normalize(account);
                _rankScratch.Add(account);
            }

            _rankTotal = _rankScratch.Count;

            FillRank(_rankKills, (x, y) => y.Current.PlayerKills.CompareTo(x.Current.PlayerKills));
            FillRank(_rankKd, (x, y) => KdOf(y.Current).CompareTo(KdOf(x.Current)));
            FillRank(_rankGathered, (x, y) => y.Current.GatheredTotal.CompareTo(x.Current.GatheredTotal));
            FillRank(_rankTime, (x, y) => y.Current.PlaySeconds.CompareTo(x.Current.PlaySeconds));
            FillRank(_rankLevel, (x, y) =>
            {
                int byLevel = y.Level.CompareTo(x.Level);
                return byLevel != 0 ? byLevel : y.TotalExp.CompareTo(x.TotalExp);
            });

            _rankCacheUntil = now + RankCacheSeconds;
        }

        private void FillRank(Dictionary<ulong, int> target, Comparison<AccountData> comparison)
        {
            target.Clear();
            _rankScratch.Sort(comparison);
            for (int i = 0; i < _rankScratch.Count; i++)
                target[_rankScratch[i].UserId] = i + 1;
        }

        private static int RankOf(Dictionary<ulong, int> map, ulong userId)
        {
            int rank;
            return map.TryGetValue(userId, out rank) ? rank : 0;
        }

        private Dictionary<string, object> RanksOf(ulong userId)
        {
            EnsureRankCache();
            return new Dictionary<string, object>
            {
                ["Total"] = _rankTotal,
                ["Kills"] = RankOf(_rankKills, userId),
                ["Kd"] = RankOf(_rankKd, userId),
                ["Gathered"] = RankOf(_rankGathered, userId),
                ["PlayTime"] = RankOf(_rankTime, userId),
                ["Level"] = RankOf(_rankLevel, userId)
            };
        }

        private static List<Dictionary<string, object>> WeaponRows(StatLayer layer)
        {
            var rows = new List<Dictionary<string, object>>(layer.Weapons.Count);

            foreach (var pair in layer.Weapons)
            {
                WeaponStat stat = pair.Value;
                if (stat == null) continue;

                rows.Add(new Dictionary<string, object>
                {
                    ["ItemId"] = pair.Key,
                    // Перевод в shortname только на выдаче: в хуках ходит itemid.
                    ["Shortname"] = ItemName(pair.Key),
                    ["Kills"] = stat.Kills,
                    ["Shots"] = stat.Shots,
                    ["Hits"] = stat.Hits,
                    ["Headshots"] = stat.Headshots,
                    ["Damage"] = Math.Round(stat.Damage, 1),
                    // Точность и доля хедшотов не хранятся, они выводятся здесь.
                    ["Accuracy"] = stat.Shots <= 0 ? 0d : Math.Round(stat.Hits / (double)stat.Shots, 4),
                    ["HeadshotRate"] = stat.Hits <= 0 ? 0d : Math.Round(stat.Headshots / (double)stat.Hits, 4)
                });
            }

            rows.Sort((x, y) =>
            {
                int byKills = ((long)y["Kills"]).CompareTo((long)x["Kills"]);
                if (byKills != 0) return byKills;
                return ((double)y["Damage"]).CompareTo((double)x["Damage"]);
            });

            return rows;
        }

        private static List<Dictionary<string, object>> DuelRows(Dictionary<ulong, DuelStat> map, int limit)
        {
            var rows = new List<Dictionary<string, object>>(map.Count);

            foreach (var pair in map)
            {
                DuelStat stat = pair.Value;
                if (stat == null) continue;
                rows.Add(new Dictionary<string, object>
                {
                    ["SteamId"] = pair.Key,
                    ["Name"] = string.IsNullOrEmpty(stat.Name)
                        ? pair.Key.ToString(CultureInfo.InvariantCulture)
                        : stat.Name,
                    ["Count"] = stat.Count
                });
            }

            rows.Sort((x, y) => ((long)y["Count"]).CompareTo((long)x["Count"]));
            while (rows.Count > limit) rows.RemoveAt(rows.Count - 1);
            return rows;
        }

        private static Dictionary<string, long> CopyMap(Dictionary<string, long> source)
        {
            return new Dictionary<string, long>(source, StringComparer.OrdinalIgnoreCase);
        }

        private static long[] CopyArray(long[] source)
        {
            var result = new long[source.Length];
            Array.Copy(source, result, source.Length);
            return result;
        }

        private static Dictionary<string, object> DeathCauses(StatLayer layer)
        {
            long[] values = layer.DeathsByCause;
            return new Dictionary<string, object>
            {
                ["Player"] = values[(int)DeathCause.Player],
                ["Npc"] = values[(int)DeathCause.Npc],
                ["Animal"] = values[(int)DeathCause.Animal],
                ["Fall"] = values[(int)DeathCause.Fall],
                ["Starvation"] = values[(int)DeathCause.Starvation],
                ["Radiation"] = values[(int)DeathCause.Radiation],
                ["Drowned"] = values[(int)DeathCause.Drowned],
                ["Explosion"] = values[(int)DeathCause.Explosion],
                ["Turret"] = values[(int)DeathCause.Turret],
                ["Bradley"] = values[(int)DeathCause.Bradley],
                ["Helicopter"] = values[(int)DeathCause.Helicopter],
                ["Suicide"] = values[(int)DeathCause.Suicide],
                ["Other"] = values[(int)DeathCause.Other]
            };
        }

        private static Dictionary<string, object> GradeMap(long[] values)
        {
            return new Dictionary<string, object>
            {
                ["Twigs"] = values[(int)BuildingGrade.Enum.Twigs],
                ["Wood"] = values[(int)BuildingGrade.Enum.Wood],
                ["Stone"] = values[(int)BuildingGrade.Enum.Stone],
                ["Metal"] = values[(int)BuildingGrade.Enum.Metal],
                ["TopTier"] = values[(int)BuildingGrade.Enum.TopTier]
            };
        }

        private Dictionary<string, object> SectionSummary(AccountData a, StatLayer layer)
        {
            int favourite = FavouriteWeapon(layer);
            return new Dictionary<string, object>
            {
                ["PlayerKills"] = layer.PlayerKills,
                ["Deaths"] = layer.Deaths,
                ["Kd"] = Math.Round(KdOf(layer), 2),
                ["NpcKills"] = layer.NpcKills,
                ["AnimalKills"] = layer.AnimalKills,
                ["Assists"] = layer.Assists,
                ["PlaySeconds"] = LayerPlaySeconds(a.UserId, a, layer),
                ["Sessions"] = layer.Sessions,
                ["GatheredTotal"] = layer.GatheredTotal,
                ["LootContainers"] = layer.LootContainers,
                ["BuildingPiecesPlaced"] = layer.BuildingPiecesPlaced,
                ["FavouriteWeaponId"] = favourite,
                ["FavouriteWeapon"] = ItemName(favourite),
                ["TopResource"] = TopKey(layer.Resources),
                ["DamageDealt"] = Math.Round(layer.DamageDealt, 1),
                ["DamageReceived"] = Math.Round(layer.DamageReceived, 1)
            };
        }

        private Dictionary<string, object> SectionPvp(AccountData a, StatLayer layer)
        {
            return new Dictionary<string, object>
            {
                ["PlayerKills"] = layer.PlayerKills,
                ["Deaths"] = layer.Deaths,
                ["Kd"] = Math.Round(KdOf(layer), 2),
                ["CurrentKillStreak"] = layer.CurrentKillStreak,
                ["BestKillStreak"] = layer.BestKillStreak,
                ["LongestKillDistance"] = Math.Round(layer.LongestKillDistance, 1),
                ["LongestKillWeaponId"] = layer.LongestKillWeaponId,
                ["LongestKillWeapon"] = ItemName(layer.LongestKillWeaponId),
                ["KillsHead"] = layer.KillsHead,
                ["KillsTorso"] = layer.KillsTorso,
                ["KillsLimbs"] = layer.KillsLimbs,
                ["Assists"] = layer.Assists,
                ["TimesWounded"] = layer.TimesWounded,
                ["RevivedByOthers"] = layer.RevivedByOthers,
                ["RevivedOthers"] = layer.RevivedOthers,
                ["Headshots"] = layer.Headshots,
                ["HitsLanded"] = layer.HitsLanded,
                ["ShotsFired"] = layer.ShotsFired,
                ["Accuracy"] = layer.ShotsFired <= 0 ? 0d : Math.Round(layer.HitsLanded / (double)layer.ShotsFired, 4),
                ["HeadshotRate"] = layer.HitsLanded <= 0 ? 0d : Math.Round(layer.Headshots / (double)layer.HitsLanded, 4),
                ["DamageDealt"] = Math.Round(layer.DamageDealt, 1),
                ["DamageReceived"] = Math.Round(layer.DamageReceived, 1),
                ["DeathsByCause"] = DeathCauses(layer),
                ["TopVictims"] = DuelRows(layer.KilledPlayers, 10),
                ["TopKillers"] = DuelRows(layer.KilledByPlayers, 10)
            };
        }

        private Dictionary<string, object> SectionWeapons(AccountData a, StatLayer layer)
        {
            int favourite = FavouriteWeapon(layer);
            return new Dictionary<string, object>
            {
                ["FavouriteWeaponId"] = favourite,
                ["FavouriteWeapon"] = ItemName(favourite),
                ["Weapons"] = WeaponRows(layer)
            };
        }

        private Dictionary<string, object> SectionGather(AccountData a, StatLayer layer)
        {
            return new Dictionary<string, object>
            {
                ["GatheredTotal"] = layer.GatheredTotal,
                ["GatherEvents"] = layer.GatherEvents,
                ["CollectiblesPicked"] = layer.CollectiblesPicked,
                ["LootContainers"] = layer.LootContainers,
                ["CraftOperations"] = layer.CraftOperations,
                ["CraftedItemsTotal"] = layer.CraftedItemsTotal,
                ["Resources"] = CopyMap(layer.Resources),
                ["LootedContainers"] = CopyMap(layer.LootedContainers),
                ["CraftedItems"] = CopyMap(layer.CraftedItems),
                ["CollectiblesByPrefab"] = CopyMap(layer.CollectiblesByPrefab),
                ["TopResource"] = TopKey(layer.Resources),
                ["TopLootContainer"] = TopKey(layer.LootedContainers),
                ["TopCraftedItem"] = TopKey(layer.CraftedItems)
            };
        }

        private Dictionary<string, object> SectionBuild(AccountData a, StatLayer layer)
        {
            return new Dictionary<string, object>
            {
                ["BuildingPiecesPlaced"] = layer.BuildingPiecesPlaced,
                ["DeployablesPlaced"] = layer.DeployablesPlaced,
                ["StructuresUpgraded"] = layer.StructuresUpgraded,
                ["StructuresDestroyed"] = layer.StructuresDestroyed,
                ["BarrelsDestroyed"] = layer.BarrelsDestroyed,
                ["ExplosivesUsed"] = layer.ExplosivesUsed,
                ["RocketsFired"] = layer.RocketsFired,
                ["DoorsDestroyed"] = layer.DoorsDestroyed,
                ["CupboardsDestroyed"] = layer.CupboardsDestroyed,
                ["OwnBlocksLost"] = layer.OwnBlocksLost,
                ["BlocksByGrade"] = GradeMap(layer.BlocksByGrade),
                ["DoorsByGrade"] = GradeMap(layer.DoorsByGrade),
                ["ExplosivesByPrefab"] = CopyMap(layer.ExplosivesByPrefab),
                ["BuiltEntities"] = CopyMap(layer.BuiltEntities)
            };
        }

        private Dictionary<string, object> SectionPve(AccountData a, StatLayer layer)
        {
            return new Dictionary<string, object>
            {
                ["NpcKills"] = layer.NpcKills,
                ["AnimalKills"] = layer.AnimalKills,
                ["BradleyDamage"] = Math.Round(layer.BradleyDamage, 1),
                ["HelicopterDamage"] = Math.Round(layer.HelicopterDamage, 1),
                ["BradleyKills"] = layer.BradleyKills,
                ["HelicopterKills"] = layer.HelicopterKills,
                ["HackableCratesOpened"] = layer.HackableCratesOpened,
                ["AirdropsLooted"] = layer.AirdropsLooted,
                ["SignalsCalled"] = layer.SignalsCalled,
                ["NpcKillsByPrefab"] = CopyMap(layer.NpcKillsByPrefab),
                ["AnimalKillsByPrefab"] = CopyMap(layer.AnimalKillsByPrefab)
            };
        }

        private Dictionary<string, object> SectionActivity(AccountData a, StatLayer layer)
        {
            long seconds = LayerPlaySeconds(a.UserId, a, layer);
            var sessions = new List<Dictionary<string, object>>(a.RecentSessions.Count);

            foreach (SessionRecord record in a.RecentSessions)
            {
                sessions.Add(new Dictionary<string, object>
                {
                    ["StartedUtc"] = record.StartedUtc,
                    ["EndedUtc"] = record.EndedUtc,
                    ["Seconds"] = record.Seconds
                });
            }

            return new Dictionary<string, object>
            {
                ["PlaySeconds"] = seconds,
                ["Sessions"] = layer.Sessions,
                ["AverageSessionSeconds"] = layer.Sessions <= 0 ? 0L : seconds / layer.Sessions,
                ["Days"] = CopyArray(a.DayPlaySeconds),
                ["DayAnchorUtc"] = a.DayAnchorUtc,
                // Часы суток ведутся только за текущий вайп.
                ["Hours"] = CopyArray(a.Current.HourMinutes),
                ["RecentSessions"] = sessions,
                ["FirstSeenUtc"] = a.FirstSeenUtc,
                ["LastSeenUtc"] = a.LastSeenUtc,
                ["WipesPlayed"] = a.WipesPlayed
            };
        }

        private Dictionary<string, object> BuildSection(AccountData a, string section, StatLayer layer)
        {
            if (string.IsNullOrEmpty(section)) return SectionSummary(a, layer);

            if (section.Equals("pvp", StringComparison.OrdinalIgnoreCase)) return SectionPvp(a, layer);
            if (section.Equals("weapons", StringComparison.OrdinalIgnoreCase)) return SectionWeapons(a, layer);
            if (section.Equals("gather", StringComparison.OrdinalIgnoreCase)) return SectionGather(a, layer);
            if (section.Equals("build", StringComparison.OrdinalIgnoreCase)) return SectionBuild(a, layer);
            if (section.Equals("pve", StringComparison.OrdinalIgnoreCase)) return SectionPve(a, layer);
            if (section.Equals("activity", StringComparison.OrdinalIgnoreCase)) return SectionActivity(a, layer);

            return SectionSummary(a, layer);
        }

        private Dictionary<string, object> PeriodBundle(AccountData a, StatLayer layer)
        {
            return new Dictionary<string, object>
            {
                ["Summary"] = SectionSummary(a, layer),
                ["Pvp"] = SectionPvp(a, layer),
                ["Weapons"] = SectionWeapons(a, layer),
                ["Gather"] = SectionGather(a, layer),
                ["Build"] = SectionBuild(a, layer),
                ["Pve"] = SectionPve(a, layer)
            };
        }

        [HookMethod("API_GetProfileSection")]
        public object API_GetProfileSection(ulong userId, string section, string period)
        {
            AccountData a = Resolve(userId);
            if (a == null) return null;

            Normalize(a);
            StatLayer layer = LayerOf(a, period);
            var result = BuildSection(a, section, layer);
            result["Period"] = PeriodKey(period);
            result["Section"] = string.IsNullOrEmpty(section) ? "summary" : section.ToLowerInvariant();
            result["SteamId"] = a.UserId;
            result["Name"] = a.Name;
            return result;
        }

        private AccountData Resolve(ulong userId)
        {
            AccountData a;
            if (_data.Accounts.TryGetValue(userId, out a) && a != null) return a;

            BasePlayer player = BasePlayer.FindByID(userId);
            return player == null ? null : Ensure(player);
        }

        private Dictionary<string, object> BuildProfile(AccountData a)
        {
            if (a == null) return null;
            Normalize(a);

            // Ключи ниже существовали в 1.0.7 и читаются сайтом и ServerMenu.
            // Переименовывать их нельзя: значения берутся из слоя «всё время»,
            // ровно как и раньше, когда слой был один.
            var result = Summary(a);
            result["FirstSeenUtc"] = a.FirstSeenUtc;
            result["LastSeenUtc"] = a.LastSeenUtc;
            result["PlaySeconds"] = EffectivePlaySeconds(a.UserId, a);
            result["Sessions"] = a.Lifetime.Sessions;
            result["WipesPlayed"] = a.WipesPlayed;

            result["PlayerKills"] = a.Lifetime.PlayerKills;
            result["NpcKills"] = a.Lifetime.NpcKills;
            result["AnimalKills"] = a.Lifetime.AnimalKills;
            result["Deaths"] = a.Lifetime.Deaths;
            result["Headshots"] = a.Lifetime.Headshots;
            result["HitsLanded"] = a.Lifetime.HitsLanded;
            result["ShotsFired"] = a.Lifetime.ShotsFired;
            result["DamageDealt"] = Math.Round(a.Lifetime.DamageDealt, 1);
            result["DamageReceived"] = Math.Round(a.Lifetime.DamageReceived, 1);

            result["GatheredTotal"] = a.Lifetime.GatheredTotal;
            result["GatherEvents"] = a.Lifetime.GatherEvents;
            result["CollectiblesPicked"] = a.Lifetime.CollectiblesPicked;
            result["LootContainers"] = a.Lifetime.LootContainers;
            result["CraftOperations"] = a.Lifetime.CraftOperations;
            result["CraftedItemsTotal"] = a.Lifetime.CraftedItemsTotal;
            result["BuildingPiecesPlaced"] = a.Lifetime.BuildingPiecesPlaced;
            result["DeployablesPlaced"] = a.Lifetime.DeployablesPlaced;
            result["StructuresUpgraded"] = a.Lifetime.StructuresUpgraded;
            result["StructuresDestroyed"] = a.Lifetime.StructuresDestroyed;
            result["BarrelsDestroyed"] = a.Lifetime.BarrelsDestroyed;
            result["ExplosivesUsed"] = a.Lifetime.ExplosivesUsed;
            result["RocketsFired"] = a.Lifetime.RocketsFired;

            result["Resources"] = CopyMap(a.Lifetime.Resources);
            result["LootedContainers"] = CopyMap(a.Lifetime.LootedContainers);
            result["CraftedItems"] = CopyMap(a.Lifetime.CraftedItems);
            result["NpcKillsByPrefab"] = CopyMap(a.Lifetime.NpcKillsByPrefab);
            result["AnimalKillsByPrefab"] = CopyMap(a.Lifetime.AnimalKillsByPrefab);
            result["ExplosivesByPrefab"] = CopyMap(a.Lifetime.ExplosivesByPrefab);
            result["BuiltEntities"] = CopyMap(a.Lifetime.BuiltEntities);
            result["CollectiblesByPrefab"] = CopyMap(a.Lifetime.CollectiblesByPrefab);

            result["TopResource"] = TopKey(a.Lifetime.Resources);
            result["TopLootContainer"] = TopKey(a.Lifetime.LootedContainers);
            result["TopCraftedItem"] = TopKey(a.Lifetime.CraftedItems);

            // Новое в 1.1.0: разрезы по времени, ранги и активность.
            result["WipeId"] = _data.CurrentWipeId;
            result["PreviousWipeId"] = _data.PreviousWipeId;
            result["Periods"] = new Dictionary<string, object>
            {
                ["wipe"] = PeriodBundle(a, a.Current),
                ["previous"] = PeriodBundle(a, a.Previous),
                ["lifetime"] = PeriodBundle(a, a.Lifetime)
            };
            result["Activity"] = SectionActivity(a, a.Current);
            result["Ranks"] = RanksOf(a.UserId);

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
            long value = a != null ? a.Lifetime.PlaySeconds : 0;
            long started;
            if (_sessionCheckpoint.TryGetValue(userId, out started))
                value += Math.Max(0, Now() - started);
            return value;
        }

        private long LayerPlaySeconds(ulong userId, AccountData a, StatLayer layer)
        {
            long value = layer != null ? layer.PlaySeconds : 0;
            // Незафиксированный кусок текущей сессии виден только в живых слоях.
            if (a != null && (layer == a.Current || layer == a.Lifetime))
            {
                long started;
                if (_sessionCheckpoint.TryGetValue(userId, out started))
                    value += Math.Max(0, Now() - started);
            }
            return value;
        }

        // Постройка считается чужой, если владелец известен и это не сам игрок.
        private static bool IsForeign(BaseEntity entity, BasePlayer player)
        {
            if (entity == null || player == null) return false;
            ulong ownerId = entity.OwnerID;
            if (!ownerId.IsSteamId()) return false;
            return ownerId != player.userID;
        }

        // У двери нет BuildingGrade, поэтому класс берём из имени префаба.
        private static int DoorGradeIndex(BaseEntity entity)
        {
            string prefab = ShortName(entity);
            if (prefab.IndexOf("wood", StringComparison.OrdinalIgnoreCase) >= 0) return (int)BuildingGrade.Enum.Wood;
            if (prefab.IndexOf("garage", StringComparison.OrdinalIgnoreCase) >= 0) return (int)BuildingGrade.Enum.TopTier;
            if (prefab.IndexOf("hqm", StringComparison.OrdinalIgnoreCase) >= 0) return (int)BuildingGrade.Enum.TopTier;
            if (prefab.IndexOf("metal", StringComparison.OrdinalIgnoreCase) >= 0) return (int)BuildingGrade.Enum.Metal;
            if (prefab.IndexOf("stone", StringComparison.OrdinalIgnoreCase) >= 0) return (int)BuildingGrade.Enum.Stone;
            return (int)BuildingGrade.Enum.Wood;
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
            string bestKey = "";
            long bestValue = long.MinValue;
            foreach (var pair in map)
            {
                if (pair.Value <= bestValue) continue;
                bestValue = pair.Value;
                bestKey = pair.Key;
            }
            return bestKey;
        }

        // Любимое оружие: сперва по убийствам, при равенстве — по нанесённому урону.
        private static int FavouriteWeapon(StatLayer layer)
        {
            int bestId = 0;
            long bestKills = -1;
            double bestDamage = -1d;

            foreach (var pair in layer.Weapons)
            {
                WeaponStat stat = pair.Value;
                if (stat == null) continue;
                if (stat.Kills < bestKills) continue;
                if (stat.Kills == bestKills && stat.Damage <= bestDamage) continue;
                bestKills = stat.Kills;
                bestDamage = stat.Damage;
                bestId = pair.Key;
            }

            return bestKills <= 0 ? 0 : bestId;
        }

        private static string ItemName(int itemId)
        {
            if (itemId == 0) return "";
            ItemDefinition definition = ItemManager.FindItemDefinition(itemId);
            return definition == null
                ? itemId.ToString(CultureInfo.InvariantCulture)
                : definition.shortname;
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

        private StatLayer LayerOf(AccountData a, string period)
        {
            if (a == null) return null;
            if (string.IsNullOrEmpty(period)) return a.Current;

            if (period.Equals("previous", StringComparison.OrdinalIgnoreCase) ||
                period.Equals("lastwipe", StringComparison.OrdinalIgnoreCase))
                return a.Previous;

            if (period.Equals("lifetime", StringComparison.OrdinalIgnoreCase) ||
                period.Equals("total", StringComparison.OrdinalIgnoreCase) ||
                period.Equals("all", StringComparison.OrdinalIgnoreCase))
                return a.Lifetime;

            return a.Current;
        }

        private static string PeriodKey(string period)
        {
            if (string.IsNullOrEmpty(period)) return "wipe";
            if (period.Equals("previous", StringComparison.OrdinalIgnoreCase) ||
                period.Equals("lastwipe", StringComparison.OrdinalIgnoreCase)) return "previous";
            if (period.Equals("lifetime", StringComparison.OrdinalIgnoreCase) ||
                period.Equals("total", StringComparison.OrdinalIgnoreCase) ||
                period.Equals("all", StringComparison.OrdinalIgnoreCase)) return "lifetime";
            return "wipe";
        }

        #endregion
    }
}
