using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using Newtonsoft.Json;
using Oxide.Core;
using Oxide.Core.Plugins;
using Oxide.Game.Rust.Cui;
using UnityEngine;

namespace Oxide.Plugins
{
    [Info("AccountSystem", "ICE RUST", "1.2.0")]
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
            RegisterMenuTab();

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
            UnregisterMenuTab();
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

        #region UiStyle

        // Визуальный язык ICE RUST. Повторяет палитру и шрифты ServerMenu,
        // чтобы встроенная вкладка не отличалась от остальных.
        private static class UiStyle
        {
            public const string Bold = "robotocondensed-bold.ttf";
            public const string Reg = "robotocondensed-regular.ttf";
            public const string MatBlur = "assets/content/ui/uibackgroundblur.mat";

            public const string Card = "1 1 1 0.045";
            public const string CardDark = "0 0 0 0.16";
            public const string Line = "1 1 1 0.07";
            public const string Text = "#CEC5BB";
            public const string Muted = "#7F7D7D";
            public const string SubText = "#A39C96";
            public const string Accent = "#65A30DBF";
            public const string AccentSoft = "#65A30D59";
            public const string Danger = "#E0947A";
            public const string Positive = "#8FBF3F";
            public const string Chip = "0 0 0 0.16";
            public const string ChipActive = "#B2A9A3E6";
            public const string ChipActiveText = "#45403B";

            public static string Col(string hex)
            {
                if (string.IsNullOrEmpty(hex)) return "1 1 1 1";
                if (!hex.StartsWith("#", StringComparison.Ordinal)) return hex;

                Color color;
                return ColorUtility.TryParseHtmlString(hex, out color)
                    ? F(color.r) + " " + F(color.g) + " " + F(color.b) + " " + F(color.a)
                    : "1 1 1 1";
            }

            public static bool Blur(string color)
            {
                return color == Card || color == Line;
            }

            public static string F(float value)
            {
                return value.ToString("0.###", CultureInfo.InvariantCulture);
            }

            public static string Safe(string text, int max)
            {
                if (string.IsNullOrEmpty(text)) return string.Empty;
                text = text.Replace("\n", " ").Replace("\r", " ");
                return text.Length <= max ? text : text.Substring(0, Math.Max(0, max - 1)) + "…";
            }

            public static string Num(long value)
            {
                return value.ToString("N0", CultureInfo.InvariantCulture).Replace(",", " ");
            }

            public static string Num(double value)
            {
                return Math.Round(value).ToString("N0", CultureInfo.InvariantCulture).Replace(",", " ");
            }

            public static string Percent(double fraction)
            {
                return Math.Round(fraction * 100d, 1).ToString("0.#", CultureInfo.InvariantCulture) + "%";
            }

            public static string Time(long seconds)
            {
                if (seconds <= 0) return "0м";
                long days = seconds / 86400;
                long hours = (seconds % 86400) / 3600;
                long minutes = (seconds % 3600) / 60;
                if (days > 0) return days + "д " + hours + "ч";
                if (hours > 0) return hours + "ч " + minutes + "м";
                return Math.Max(1, minutes) + "м";
            }

            public static string Date(long unix)
            {
                if (unix <= 0) return "—";
                return DateTimeOffset.FromUnixTimeSeconds(unix).UtcDateTime
                    .ToString("dd.MM.yyyy HH:mm", CultureInfo.InvariantCulture);
            }

            public static string Rich(string color, string value)
            {
                return "<color=" + color + ">" + value + "</color>";
            }
        }

        private void Panel(CuiElementContainer c, string parent, string name,
            string amin, string amax, string omin, string omax, string color)
        {
            var image = new CuiImageComponent { Color = UiStyle.Col(color) };
            if (UiStyle.Blur(color)) image.Material = UiStyle.MatBlur;

            c.Add(new CuiPanel
            {
                RectTransform = { AnchorMin = amin, AnchorMax = amax, OffsetMin = omin, OffsetMax = omax },
                Image = image
            }, parent, name, name);
        }

        private void Label(CuiElementContainer c, string parent,
            string amin, string amax, string omin, string omax, string text,
            int size = 12, string color = UiStyle.Text,
            TextAnchor align = TextAnchor.MiddleLeft, string font = UiStyle.Bold)
        {
            c.Add(new CuiLabel
            {
                RectTransform = { AnchorMin = amin, AnchorMax = amax, OffsetMin = omin, OffsetMax = omax },
                Text = { Text = text, Font = font, FontSize = size, Align = align, Color = UiStyle.Col(color) }
            }, parent);
        }

        private void Button(CuiElementContainer c, string parent,
            string amin, string amax, string omin, string omax,
            string command, string text, string color, int size = 11, string textColor = UiStyle.Text)
        {
            var button = new CuiButton
            {
                RectTransform = { AnchorMin = amin, AnchorMax = amax, OffsetMin = omin, OffsetMax = omax },
                Button = { Color = UiStyle.Col(color), Command = command ?? string.Empty },
                Text =
                {
                    Text = text, Font = UiStyle.Bold, FontSize = size,
                    Align = TextAnchor.MiddleCenter, Color = UiStyle.Col(textColor)
                }
            };

            if (color != "0 0 0 0") button.Button.Material = UiStyle.MatBlur;
            c.Add(button, parent);
        }

        // Горизонтальная полоса заполнения: используется и для прогресса уровня,
        // и как столбик в графиках активности.
        private void Progress(CuiElementContainer c, string parent, string name,
            string amin, string amax, string omin, string omax, double fraction, string color)
        {
            Panel(c, parent, name, amin, amax, omin, omax, UiStyle.CardDark);

            double value = fraction < 0d ? 0d : (fraction > 1d ? 1d : fraction);
            if (value <= 0d) return;

            Panel(c, name, name + ".Fill", "0 0", UiStyle.F((float)value) + " 1", "0 0", "0 0", color);
        }

        // Вертикальный столбик снизу вверх — для графиков по дням и часам.
        private void Bar(CuiElementContainer c, string parent, string name,
            string amin, string amax, string omin, string omax, double fraction, string color)
        {
            Panel(c, parent, name, amin, amax, omin, omax, UiStyle.CardDark);

            double value = fraction < 0d ? 0d : (fraction > 1d ? 1d : fraction);
            if (value <= 0d) return;

            Panel(c, name, name + ".Fill", "0 0", "1 " + UiStyle.F((float)value), "0 0", "0 0", color);
        }

        private void Chip(CuiElementContainer c, string parent, string name,
            float left, float width, float top, float height,
            string command, string text, bool active)
        {
            Button(c, parent, "0 1", "0 1",
                UiStyle.F(left) + " " + UiStyle.F(top - height),
                UiStyle.F(left + width) + " " + UiStyle.F(top),
                active ? string.Empty : command, text,
                active ? UiStyle.ChipActive : UiStyle.Chip, 11,
                active ? UiStyle.ChipActiveText : UiStyle.SubText);
        }

        private void Card(CuiElementContainer c, string parent, string name,
            float left, float right, float top, float bottom, string title)
        {
            Panel(c, parent, name, "0 1", "0 1",
                UiStyle.F(left) + " " + UiStyle.F(bottom),
                UiStyle.F(right) + " " + UiStyle.F(top), UiStyle.Card);

            if (string.IsNullOrEmpty(title)) return;

            Label(c, name, "0 1", "1 1", "14 -26", "-14 -8", title, 11, UiStyle.Muted);
            Panel(c, name, name + ".Line", "0 1", "1 1", "14 -30", "-14 -29", UiStyle.Line);
        }

        // Строка «подпись — значение» внутри карточки.
        private void Row(CuiElementContainer c, string parent, string name, int index,
            string title, string value, string valueColor)
        {
            float top = -34f - index * 22f;

            Label(c, parent, "0 1", "0.62 1",
                "14 " + UiStyle.F(top - 20f), "0 " + UiStyle.F(top),
                title, 11, UiStyle.SubText, TextAnchor.MiddleLeft, UiStyle.Reg);

            Label(c, parent, "0.38 1", "1 1",
                "0 " + UiStyle.F(top - 20f), "-14 " + UiStyle.F(top),
                value, 12, valueColor, TextAnchor.MiddleRight);
        }

        private void Row(CuiElementContainer c, string parent, string name, int index, string title, string value)
        {
            Row(c, parent, name, index, title, value, UiStyle.Text);
        }

        private void Empty(CuiElementContainer c, string parent, string name, string title, string description)
        {
            Label(c, parent, "0 0", "1 1", "0 18", "0 0", title, 15, UiStyle.Muted, TextAnchor.MiddleCenter);
            Label(c, parent, "0 0", "1 1", "0 -14", "0 0", description, 11, UiStyle.SubText,
                TextAnchor.MiddleCenter, UiStyle.Reg);
        }

        #endregion


        #region ServerMenu tab

        private const string MenuTabKey = "account";
        private const string MenuHost = "ServerMenu.UI.Main";
        private const string UiRoot = "AccountSystem.Profile";
        private const string UiHead = UiRoot + ".Head";
        private const string UiSwitch = UiRoot + ".Switch";
        private const string UiBody = UiRoot + ".Body";

        // Геометрия вкладки. Ширина совпадает с контейнером ServerMenu (StripW).
        private const float PaneWidth = 1120f;
        private const float Pad = 16f;
        private const float HeadTop = -8f;
        private const float HeadHeight = 96f;
        private const float SwitchTop = HeadTop - HeadHeight - 10f;
        private const float SwitchHeight = 62f;
        private const float BodyTop = SwitchTop - SwitchHeight - 10f;
        private const float BodyBottom = -572f;

        private sealed class ViewState
        {
            public ulong Target;
            public string Section = "summary";
            public string Period = "wipe";
            public bool FromTop;
        }

        private readonly Dictionary<ulong, ViewState> _views = new Dictionary<ulong, ViewState>();

        private static readonly string[] SectionKeys =
        {
            "summary", "pvp", "weapons", "gather", "build", "pve", "activity"
        };

        private static readonly string[] PeriodKeys = { "wipe", "previous", "lifetime" };

        private ViewState View(BasePlayer player)
        {
            ViewState state;
            if (!_views.TryGetValue(player.userID, out state) || state == null)
            {
                _views[player.userID] = state = new ViewState();
                state.Target = player.userID;
            }
            return state;
        }

        private void RegisterMenuTab()
        {
            if (!ServerMenuReady()) return;

            object result = ServerMenu.Call("API_RegisterTab", this, MenuTabKey, "ПРОФИЛЬ", "", 12);
            if (result is bool && (bool)result) return;

            PrintWarning("ServerMenu отклонил регистрацию вкладки \"" + MenuTabKey +
                         "\". Обнови ServerMenu: ключ должен быть убран из IsBuiltInTab.");
        }

        private void UnregisterMenuTab()
        {
            if (ServerMenuReady()) ServerMenu.Call("API_UnregisterTab", MenuTabKey);
            _views.Clear();
        }

        private void OnPluginLoaded(Plugin plugin)
        {
            if (plugin != null && plugin.Name == "ServerMenu")
                RegisterMenuTab();
        }

        [HookMethod("API_TabTitle")]
        public string API_TabTitle(BasePlayer player)
        {
            return "ПРОФИЛЬ";
        }

        // Вызывается ServerMenu перед отрисовкой. mode — payload из API_OpenTab:
        // SteamID64 открывает чужой профиль, пустая строка — свой.
        [HookMethod("API_PrepareServerMenu")]
        public void API_PrepareServerMenu(BasePlayer player, string mode)
        {
            if (!Human(player)) return;

            ViewState state = View(player);
            ulong target;

            if (!string.IsNullOrEmpty(mode) && ulong.TryParse(mode, out target) && target.IsSteamId())
            {
                state.Target = target;
                state.FromTop = target != player.userID;
            }
            else
            {
                state.Target = player.userID;
                state.FromTop = false;
            }

            state.Section = "summary";
            state.Period = "wipe";
        }

        [HookMethod("API_RenderServerMenu")]
        public void API_RenderServerMenu(BasePlayer player)
        {
            if (!Human(player)) return;
            DrawProfile(player, View(player), true);
        }

        [HookMethod("API_OnTabHidden")]
        public void API_OnTabHidden(BasePlayer player)
        {
            if (player == null) return;
            CuiHelper.DestroyUi(player, UiRoot);
            _views.Remove(player.userID);
        }

        // Команды приходят как "servermenu.ui ext account <payload>".
        [HookMethod("API_OnTabCommand")]
        public void API_OnTabCommand(BasePlayer player, string payload)
        {
            if (!Human(player) || string.IsNullOrEmpty(payload)) return;

            ViewState state = View(player);

            int space = payload.IndexOf(' ');
            string verb = space < 0 ? payload : payload.Substring(0, space);
            string rest = space < 0 ? string.Empty : payload.Substring(space + 1).Trim();

            if (verb == "sec" && IsKnown(SectionKeys, rest))
            {
                if (state.Section == rest) return;
                state.Section = rest;
                // Перерисовываем только переключатели и тело, шапка остаётся.
                DrawProfile(player, state, false);
                return;
            }

            if (verb == "per" && IsKnown(PeriodKeys, rest))
            {
                if (state.Period == rest) return;
                state.Period = rest;
                DrawProfile(player, state, false);
            }
        }

        private static bool IsKnown(string[] keys, string value)
        {
            if (string.IsNullOrEmpty(value)) return false;
            for (int i = 0; i < keys.Length; i++)
                if (keys[i] == value) return true;
            return false;
        }

        private static string Cmd(string verb, string argument)
        {
            return "servermenu.ui ext " + MenuTabKey + " " + verb + " " + argument;
        }

        #endregion


        #region Profile rendering

        // Колонки сетки: 16 + 352 + 16 + 352 + 16 + 352 + 16 = 1120.
        private const float Col3 = 352f;
        private const float Col2 = 536f;
        private const float Col4 = 260f;

        // Внутри UiBody отступы уже сняты, рабочая ширина 1088.
        private static float ColX(int index, float width)
        {
            return index * (width + Pad);
        }

        private const float RowTop = 0f;
        private const float RowMid = -168f;
        private const float BodyHeight = 340f;

        private void DrawProfile(BasePlayer player, ViewState state, bool full)
        {
            AccountData a = Resolve(state.Target);
            var c = new CuiElementContainer();

            if (full)
            {
                CuiHelper.DestroyUi(player, UiRoot);

                Panel(c, MenuHost, UiRoot, "0 0", "1 1", "0 0", "0 0", "0 0 0 0");

                if (a == null)
                {
                    Label(c, UiRoot, "0 1", "1 1", "22 -46", "-22 -12",
                        state.FromTop ? Lang(player, "TitleOther") : Lang(player, "TitleSelf"),
                        22, UiStyle.Text);
                    Empty(c, UiRoot, UiRoot + ".Empty",
                        Lang(player, "NoProfile"), Lang(player, "NoProfileHint"));
                    CuiHelper.AddUi(player, c);
                    return;
                }

                Normalize(a);
                DrawHead(c, player, state, a);
            }
            else
            {
                if (a == null) return;
                Normalize(a);
                CuiHelper.DestroyUi(player, UiSwitch);
                CuiHelper.DestroyUi(player, UiBody);
            }

            DrawSwitch(c, player, state);
            DrawBody(c, player, state, a);

            CuiHelper.AddUi(player, c);
        }

        private void DrawHead(CuiElementContainer c, BasePlayer player, ViewState state, AccountData a)
        {
            Label(c, UiRoot, "0 1", "0.6 1", "22 -40", "0 -10",
                state.FromTop ? Lang(player, "TitleOther") : Lang(player, "TitleSelf"),
                22, UiStyle.Text);

            if (state.FromTop)
            {
                Button(c, UiRoot, "1 1", "1 1", "-186 -42", "-22 -12",
                    "servermenu.ui topback", Lang(player, "BackToTop"),
                    UiStyle.ChipActive, 11, UiStyle.ChipActiveText);

                // Сброс статистики раньше жил в ServerMenu внутри DrawAccountProfile.
                // Вместе с профилем он переехал сюда; саму команду по-прежнему
                // исполняет ServerMenu, она приходит обратно в API_ResetAccountStats.
                if (API_CanAdminReset(player))
                {
                    Button(c, UiRoot, "1 1", "1 1", "-368 -42", "-194 -12",
                        "servermenu.ui accountreset " +
                        a.UserId.ToString(CultureInfo.InvariantCulture),
                        Lang(player, "ResetStats"), "#E0947ABF", 11, "#FFFFFF");
                }
            }

            Panel(c, UiRoot, UiHead, "0 1", "1 1",
                UiStyle.F(Pad) + " " + UiStyle.F(HeadTop - HeadHeight - 46f),
                UiStyle.F(-Pad) + " " + UiStyle.F(HeadTop - 46f), UiStyle.Card);
            Panel(c, UiHead, UiHead + ".Accent", "0 0", "0 1", "0 0", "4 0", UiStyle.Accent);

            Panel(c, UiHead, UiHead + ".Avatar", "0 0.5", "0 0.5", "16 -30", "76 30", UiStyle.CardDark);
            c.Add(new CuiElement
            {
                Parent = UiHead + ".Avatar",
                Components =
                {
                    new CuiRawImageComponent
                    {
                        SteamId = a.UserId.ToString(CultureInfo.InvariantCulture),
                        Color = "1 1 1 1"
                    },
                    new CuiRectTransformComponent
                    {
                        AnchorMin = "0 0", AnchorMax = "1 1", OffsetMin = "3 3", OffsetMax = "-3 -3"
                    }
                }
            });

            string identity =
                "<size=17>" + UiStyle.Rich(UiStyle.Text, UiStyle.Safe(a.Name, 28)) + "</size>\n" +
                "<size=10>" + UiStyle.Rich(UiStyle.Muted,
                    "SteamID  " + a.UserId.ToString(CultureInfo.InvariantCulture)) + "</size>";
            Label(c, UiHead, "0 0", "0 1", "90 0", "390 0", identity, 17, UiStyle.Text);

            Panel(c, UiHead, UiHead + ".Sep", "0 0", "0 1", "404 14", "405 -14", UiStyle.Line);

            long required = Math.Max(1, RequiredExp(a.Level));
            double progress = Math.Min(1d, a.Exp / (double)required);

            Label(c, UiHead, "0 1", "0 1", "424 -34", "700 -12",
                UiStyle.Rich(UiStyle.Muted, "<size=10>" + Lang(player, "Level") + "</size>") + "   " +
                "<size=17>" + UiStyle.Rich(UiStyle.Text, a.Level.ToString(CultureInfo.InvariantCulture)) + "</size>" +
                "   " + UiStyle.Rich(UiStyle.SubText, "<size=10>" +
                    UiStyle.Num(a.Exp) + " / " + UiStyle.Num(required) + " EXP</size>"),
                12, UiStyle.Text);

            Progress(c, UiHead, UiHead + ".Exp", "0 1", "0 1", "424 -46", "700 -38",
                progress, UiStyle.Accent);

            Label(c, UiHead, "0 1", "0 1", "424 -68", "700 -50",
                UiStyle.Rich(UiStyle.Muted, "<size=10>" + Lang(player, "InGame") + "</size>") + "   " +
                UiStyle.Rich(UiStyle.SubText, UiStyle.Time(EffectivePlaySeconds(a.UserId, a))) +
                UiStyle.Rich(UiStyle.Muted, "   <size=10>" + Lang(player, "Wipes") + " " +
                    a.WipesPlayed.ToString(CultureInfo.InvariantCulture) + "</size>"),
                11, UiStyle.SubText, TextAnchor.MiddleLeft, UiStyle.Reg);

            DrawHeadRanks(c, a);
        }

        private void DrawHeadRanks(CuiElementContainer c, AccountData a)
        {
            EnsureRankCache();

            string total = _rankTotal > 0 ? " / " + _rankTotal.ToString(CultureInfo.InvariantCulture) : "";
            DrawRankChip(c, 0, "УБИЙСТВА", RankOf(_rankKills, a.UserId), total);
            DrawRankChip(c, 1, "K/D", RankOf(_rankKd, a.UserId), total);
            DrawRankChip(c, 2, "ДОБЫЧА", RankOf(_rankGathered, a.UserId), total);
            DrawRankChip(c, 3, "ВРЕМЯ", RankOf(_rankTime, a.UserId), total);
            DrawRankChip(c, 4, "УРОВЕНЬ", RankOf(_rankLevel, a.UserId), total);
        }

        private void DrawRankChip(CuiElementContainer c, int index, string title, int rank, string total)
        {
            float right = -16f - (4 - index) * 108f;
            string name = UiHead + ".Rank" + index.ToString(CultureInfo.InvariantCulture);

            Panel(c, UiHead, name, "1 0.5", "1 0.5",
                UiStyle.F(right) + " -30", UiStyle.F(right + 100f) + " 30", UiStyle.CardDark);

            Label(c, name, "0 0.5", "1 1", "0 -4", "0 -8", title, 9, UiStyle.Muted, TextAnchor.UpperCenter);
            Label(c, name, "0 0", "1 0.5", "0 6", "0 8",
                rank > 0 ? "#" + rank.ToString(CultureInfo.InvariantCulture) + UiStyle.Rich(UiStyle.Muted,
                    "<size=9>" + total + "</size>") : "—",
                15, rank > 0 && rank <= 3 ? UiStyle.Positive : UiStyle.Text, TextAnchor.LowerCenter);
        }

        private void DrawSwitch(CuiElementContainer c, BasePlayer player, ViewState state)
        {
            Panel(c, UiRoot, UiSwitch, "0 1", "1 1",
                UiStyle.F(Pad) + " " + UiStyle.F(SwitchTop - SwitchHeight - 46f),
                UiStyle.F(-Pad) + " " + UiStyle.F(SwitchTop - 46f), "0 0 0 0");

            // Период: вайп / прошлый вайп / всё время.
            float x = 0f;
            for (int i = 0; i < PeriodKeys.Length; i++)
            {
                string key = PeriodKeys[i];
                float width = i == 0 ? 96f : (i == 1 ? 150f : 116f);
                Chip(c, UiSwitch, UiSwitch + ".P" + i, x, width, 0f, 26f,
                    Cmd("per", key), Lang(player, "Period_" + key), state.Period == key);
                x += width + 6f;
            }

            Panel(c, UiSwitch, UiSwitch + ".Line", "0 1", "1 1", "0 -32", "0 -31", UiStyle.Line);

            // Подвкладки.
            x = 0f;
            for (int i = 0; i < SectionKeys.Length; i++)
            {
                string key = SectionKeys[i];
                string text = Lang(player, "Section_" + key);
                float width = 46f + text.Length * 7.4f;
                Chip(c, UiSwitch, UiSwitch + ".S" + i, x, width, -36f, 26f,
                    Cmd("sec", key), text, state.Section == key);
                x += width + 6f;
            }
        }

        private void DrawBody(CuiElementContainer c, BasePlayer player, ViewState state, AccountData a)
        {
            Panel(c, UiRoot, UiBody, "0 1", "1 1",
                UiStyle.F(Pad) + " " + UiStyle.F(BodyBottom),
                UiStyle.F(-Pad) + " " + UiStyle.F(BodyTop - 46f), "0 0 0 0");

            StatLayer layer = LayerOf(a, state.Period);

            if (state.Period == "previous" && IsLayerEmpty(layer))
            {
                Empty(c, UiBody, UiBody + ".Empty",
                    Lang(player, "NoPrevious"), Lang(player, "NoPreviousHint"));
                return;
            }

            switch (state.Section)
            {
                case "pvp": DrawSectionPvp(c, player, a, layer); break;
                case "weapons": DrawSectionWeapons(c, player, a, layer); break;
                case "gather": DrawSectionGather(c, player, a, layer); break;
                case "build": DrawSectionBuild(c, player, a, layer); break;
                case "pve": DrawSectionPve(c, player, a, layer); break;
                case "activity": DrawSectionActivity(c, player, a, layer); break;
                default: DrawSectionSummary(c, player, a, layer); break;
            }
        }

        private static bool IsLayerEmpty(StatLayer layer)
        {
            return layer.PlayerKills == 0 && layer.Deaths == 0 && layer.PlaySeconds == 0 &&
                   layer.GatheredTotal == 0 && layer.LootContainers == 0 &&
                   layer.BuildingPiecesPlaced == 0 && layer.NpcKills == 0;
        }

        #endregion


        #region Profile sections

        private void DrawSectionSummary(CuiElementContainer c, BasePlayer player, AccountData a, StatLayer layer)
        {
            string n1 = UiBody + ".Sum1";
            Card(c, UiBody, n1, ColX(0, Col3), ColX(0, Col3) + Col3, RowTop, RowMid + 8f, Lang(player, "CardCombat"));
            Row(c, n1, n1, 0, Lang(player, "Kills"), UiStyle.Num(layer.PlayerKills));
            Row(c, n1, n1, 1, Lang(player, "Deaths"), UiStyle.Num(layer.Deaths));
            Row(c, n1, n1, 2, "K/D", Math.Round(KdOf(layer), 2).ToString("0.##", CultureInfo.InvariantCulture),
                KdOf(layer) >= 1d ? UiStyle.Positive : UiStyle.Danger);
            Row(c, n1, n1, 3, Lang(player, "Assists"), UiStyle.Num(layer.Assists));
            Row(c, n1, n1, 4, Lang(player, "BestStreak"), UiStyle.Num(layer.BestKillStreak));

            string n2 = UiBody + ".Sum2";
            Card(c, UiBody, n2, ColX(1, Col3), ColX(1, Col3) + Col3, RowTop, RowMid + 8f, Lang(player, "CardShooting"));
            Row(c, n2, n2, 0, Lang(player, "Shots"), UiStyle.Num(layer.ShotsFired));
            Row(c, n2, n2, 1, Lang(player, "Hits"), UiStyle.Num(layer.HitsLanded));
            Row(c, n2, n2, 2, Lang(player, "Accuracy"),
                UiStyle.Percent(layer.ShotsFired <= 0 ? 0d : layer.HitsLanded / (double)layer.ShotsFired));
            Row(c, n2, n2, 3, Lang(player, "Headshots"), UiStyle.Num(layer.Headshots));
            Row(c, n2, n2, 4, Lang(player, "DamageDealt"), UiStyle.Num(layer.DamageDealt));

            string n3 = UiBody + ".Sum3";
            Card(c, UiBody, n3, ColX(2, Col3), ColX(2, Col3) + Col3, RowTop, RowMid + 8f, Lang(player, "CardEconomy"));
            Row(c, n3, n3, 0, Lang(player, "Gathered"), UiStyle.Num(layer.GatheredTotal));
            Row(c, n3, n3, 1, Lang(player, "Containers"), UiStyle.Num(layer.LootContainers));
            Row(c, n3, n3, 2, Lang(player, "Crafted"), UiStyle.Num(layer.CraftedItemsTotal));
            Row(c, n3, n3, 3, Lang(player, "Built"), UiStyle.Num(layer.BuildingPiecesPlaced));
            Row(c, n3, n3, 4, Lang(player, "InGame"), UiStyle.Time(LayerPlaySeconds(a.UserId, a, layer)));

            int favourite = FavouriteWeapon(layer);
            string n4 = UiBody + ".Sum4";
            Card(c, UiBody, n4, ColX(0, Col2), ColX(0, Col2) + Col2, RowMid, -BodyHeight, Lang(player, "CardFavourite"));

            if (favourite == 0)
            {
                Label(c, n4, "0 0", "1 1", "14 0", "-14 -34",
                    Lang(player, "NoWeaponYet"), 11, UiStyle.Muted, TextAnchor.MiddleCenter, UiStyle.Reg);
            }
            else
            {
                WeaponStat stat = layer.Weapons[favourite];
                DrawItemIcon(c, n4, n4 + ".Icon", favourite, 14f, -44f, 48f);

                Label(c, n4, "0 1", "1 1", "74 -66", "-14 -40",
                    ItemLabel(favourite), 14, UiStyle.Text);
                Label(c, n4, "0 1", "1 1", "74 -86", "-14 -66",
                    UiStyle.Num(stat.Kills) + " " + Lang(player, "KillsShort") + "   " +
                    UiStyle.Rich(UiStyle.SubText, UiStyle.Num(stat.Damage) + " " + Lang(player, "DamageShort")),
                    11, UiStyle.SubText, TextAnchor.MiddleLeft, UiStyle.Reg);

                Row(c, n4, n4, 3, Lang(player, "Accuracy"),
                    UiStyle.Percent(stat.Shots <= 0 ? 0d : stat.Hits / (double)stat.Shots));
                Row(c, n4, n4, 4, Lang(player, "HeadshotRate"),
                    UiStyle.Percent(stat.Hits <= 0 ? 0d : stat.Headshots / (double)stat.Hits));
            }

            string n5 = UiBody + ".Sum5";
            Card(c, UiBody, n5, ColX(1, Col2), ColX(1, Col2) + Col2, RowMid, -BodyHeight, Lang(player, "CardRecords"));
            Row(c, n5, n5, 0, Lang(player, "LongestShot"),
                layer.LongestKillDistance <= 0f
                    ? "—"
                    : Math.Round(layer.LongestKillDistance, 1).ToString("0.#", CultureInfo.InvariantCulture) + " м" +
                      (layer.LongestKillWeaponId == 0
                          ? ""
                          : UiStyle.Rich(UiStyle.SubText, "   " + ItemLabel(layer.LongestKillWeaponId))));
            Row(c, n5, n5, 1, Lang(player, "TopResource"), ResourceLabel(TopKey(layer.Resources)));
            Row(c, n5, n5, 2, Lang(player, "Wounded"), UiStyle.Num(layer.TimesWounded));
            Row(c, n5, n5, 3, Lang(player, "RevivedOthers"), UiStyle.Num(layer.RevivedOthers));
            Row(c, n5, n5, 4, Lang(player, "Sessions"), UiStyle.Num(layer.Sessions));
        }

        private void DrawSectionPvp(CuiElementContainer c, BasePlayer player, AccountData a, StatLayer layer)
        {
            string n1 = UiBody + ".Pvp1";
            Card(c, UiBody, n1, ColX(0, Col3), ColX(0, Col3) + Col3, RowTop, RowMid + 8f, Lang(player, "CardDuels"));
            Row(c, n1, n1, 0, Lang(player, "Kills"), UiStyle.Num(layer.PlayerKills));
            Row(c, n1, n1, 1, Lang(player, "Deaths"), UiStyle.Num(layer.Deaths));
            Row(c, n1, n1, 2, "K/D", Math.Round(KdOf(layer), 2).ToString("0.##", CultureInfo.InvariantCulture),
                KdOf(layer) >= 1d ? UiStyle.Positive : UiStyle.Danger);
            Row(c, n1, n1, 3, Lang(player, "CurrentStreak"), UiStyle.Num(layer.CurrentKillStreak));
            Row(c, n1, n1, 4, Lang(player, "BestStreak"), UiStyle.Num(layer.BestKillStreak));

            string n2 = UiBody + ".Pvp2";
            Card(c, UiBody, n2, ColX(1, Col3), ColX(1, Col3) + Col3, RowTop, RowMid + 8f, Lang(player, "CardBodyParts"));
            long partsTotal = Math.Max(1, layer.KillsHead + layer.KillsTorso + layer.KillsLimbs);
            DrawShare(c, n2, n2 + ".H", 0, Lang(player, "PartHead"), layer.KillsHead, partsTotal, UiStyle.Positive);
            DrawShare(c, n2, n2 + ".T", 1, Lang(player, "PartTorso"), layer.KillsTorso, partsTotal, UiStyle.Accent);
            DrawShare(c, n2, n2 + ".L", 2, Lang(player, "PartLimbs"), layer.KillsLimbs, partsTotal, UiStyle.AccentSoft);
            Row(c, n2, n2, 4, Lang(player, "HeadshotRate"),
                UiStyle.Percent(layer.HitsLanded <= 0 ? 0d : layer.Headshots / (double)layer.HitsLanded));

            string n3 = UiBody + ".Pvp3";
            Card(c, UiBody, n3, ColX(2, Col3), ColX(2, Col3) + Col3, RowTop, RowMid + 8f, Lang(player, "CardSupport"));
            Row(c, n3, n3, 0, Lang(player, "Assists"), UiStyle.Num(layer.Assists));
            Row(c, n3, n3, 1, Lang(player, "Wounded"), UiStyle.Num(layer.TimesWounded));
            Row(c, n3, n3, 2, Lang(player, "RevivedByOthers"), UiStyle.Num(layer.RevivedByOthers));
            Row(c, n3, n3, 3, Lang(player, "RevivedOthers"), UiStyle.Num(layer.RevivedOthers));
            Row(c, n3, n3, 4, Lang(player, "DamageReceived"), UiStyle.Num(layer.DamageReceived));

            DrawDuelList(c, player, UiBody + ".Victims", ColX(0, Col3), Col3,
                Lang(player, "CardTopVictims"), layer.KilledPlayers);
            DrawDuelList(c, player, UiBody + ".Killers", ColX(1, Col3), Col3,
                Lang(player, "CardTopKillers"), layer.KilledByPlayers);
            DrawCauses(c, player, layer);
        }

        // Полоса доли с подписью и числом.
        private void DrawShare(CuiElementContainer c, string parent, string name, int index,
            string title, long value, long total, string color)
        {
            float top = -34f - index * 26f;

            Label(c, parent, "0 1", "0.55 1",
                "14 " + UiStyle.F(top - 16f), "0 " + UiStyle.F(top),
                title, 11, UiStyle.SubText, TextAnchor.MiddleLeft, UiStyle.Reg);
            Label(c, parent, "0.45 1", "1 1",
                "0 " + UiStyle.F(top - 16f), "-14 " + UiStyle.F(top),
                UiStyle.Num(value) + UiStyle.Rich(UiStyle.Muted,
                    "  <size=9>" + UiStyle.Percent(value / (double)total) + "</size>"),
                11, UiStyle.Text, TextAnchor.MiddleRight);
            Progress(c, parent, name, "0 1", "1 1",
                "14 " + UiStyle.F(top - 21f), "-14 " + UiStyle.F(top - 17f),
                value / (double)total, color);
        }

        private void DrawDuelList(CuiElementContainer c, BasePlayer player, string name,
            float left, float width, string title, Dictionary<ulong, DuelStat> map)
        {
            Card(c, UiBody, name, left, left + width, RowMid, -BodyHeight, title);

            if (map.Count == 0)
            {
                Label(c, name, "0 0", "1 1", "14 0", "-14 -34",
                    Lang(player, "NoData"), 11, UiStyle.Muted, TextAnchor.MiddleCenter, UiStyle.Reg);
                return;
            }

            List<Dictionary<string, object>> rows = DuelRows(map, 10);
            int limit = rows.Count > 6 ? 6 : rows.Count;

            for (int i = 0; i < limit; i++)
            {
                float top = -34f - i * 21f;
                var row = rows[i];

                Label(c, name, "0 1", "0.72 1",
                    "14 " + UiStyle.F(top - 18f), "0 " + UiStyle.F(top),
                    UiStyle.Rich(UiStyle.Muted, (i + 1).ToString(CultureInfo.InvariantCulture) + ".") + " " +
                    UiStyle.Safe(row["Name"] as string, 22),
                    11, UiStyle.Text, TextAnchor.MiddleLeft, UiStyle.Reg);
                Label(c, name, "0.6 1", "1 1",
                    "0 " + UiStyle.F(top - 18f), "-14 " + UiStyle.F(top),
                    UiStyle.Num((long)row["Count"]), 11, UiStyle.Text, TextAnchor.MiddleRight);
            }
        }

        private void DrawCauses(CuiElementContainer c, BasePlayer player, StatLayer layer)
        {
            string name = UiBody + ".Causes";
            Card(c, UiBody, name, ColX(2, Col3), ColX(2, Col3) + Col3, RowMid, -BodyHeight,
                Lang(player, "CardDeathCauses"));

            long[] values = layer.DeathsByCause;
            int drawn = 0;

            for (int i = 0; i < values.Length && drawn < 6; i++)
            {
                if (values[i] <= 0) continue;
                Row(c, name, name, drawn, Lang(player, "Cause_" + ((DeathCause)i)), UiStyle.Num(values[i]));
                drawn++;
            }

            if (drawn == 0)
            {
                Label(c, name, "0 0", "1 1", "14 0", "-14 -34",
                    Lang(player, "NoDeaths"), 11, UiStyle.Muted, TextAnchor.MiddleCenter, UiStyle.Reg);
            }
        }

        private void DrawSectionWeapons(CuiElementContainer c, BasePlayer player, AccountData a, StatLayer layer)
        {
            string name = UiBody + ".Weapons";
            Card(c, UiBody, name, 0f, 1088f, RowTop, -BodyHeight, Lang(player, "CardWeapons"));

            if (layer.Weapons.Count == 0)
            {
                Label(c, name, "0 0", "1 1", "14 0", "-14 -34",
                    Lang(player, "NoWeaponYet"), 11, UiStyle.Muted, TextAnchor.MiddleCenter, UiStyle.Reg);
                return;
            }

            // Заголовок таблицы.
            WeaponHeaderCell(c, name, 74f, 300f, Lang(player, "ColWeapon"), TextAnchor.MiddleLeft);
            WeaponHeaderCell(c, name, 380f, 110f, Lang(player, "ColKills"), TextAnchor.MiddleRight);
            WeaponHeaderCell(c, name, 496f, 110f, Lang(player, "ColShots"), TextAnchor.MiddleRight);
            WeaponHeaderCell(c, name, 612f, 110f, Lang(player, "ColHits"), TextAnchor.MiddleRight);
            WeaponHeaderCell(c, name, 728f, 110f, Lang(player, "ColAccuracy"), TextAnchor.MiddleRight);
            WeaponHeaderCell(c, name, 844f, 110f, Lang(player, "ColHeadshots"), TextAnchor.MiddleRight);
            WeaponHeaderCell(c, name, 960f, 114f, Lang(player, "ColDamage"), TextAnchor.MiddleRight);

            List<Dictionary<string, object>> rows = WeaponRows(layer);
            int limit = rows.Count > 10 ? 10 : rows.Count;

            for (int i = 0; i < limit; i++)
            {
                var row = rows[i];
                float top = -58f - i * 26f;
                int itemId = (int)row["ItemId"];

                if (i % 2 == 1)
                {
                    Panel(c, name, name + ".Z" + i, "0 1", "1 1",
                        "8 " + UiStyle.F(top - 24f), "-8 " + UiStyle.F(top), UiStyle.CardDark);
                }

                DrawItemIcon(c, name, name + ".I" + i, itemId, 44f, top - 2f, 20f);

                WeaponCell(c, name, 74f, 300f, top, ItemLabel(itemId), TextAnchor.MiddleLeft, UiStyle.Text);
                WeaponCell(c, name, 380f, 110f, top, UiStyle.Num((long)row["Kills"]), TextAnchor.MiddleRight, UiStyle.Text);
                WeaponCell(c, name, 496f, 110f, top, UiStyle.Num((long)row["Shots"]), TextAnchor.MiddleRight, UiStyle.SubText);
                WeaponCell(c, name, 612f, 110f, top, UiStyle.Num((long)row["Hits"]), TextAnchor.MiddleRight, UiStyle.SubText);
                WeaponCell(c, name, 728f, 110f, top, UiStyle.Percent((double)row["Accuracy"]), TextAnchor.MiddleRight, UiStyle.SubText);
                WeaponCell(c, name, 844f, 110f, top,
                    UiStyle.Num((long)row["Headshots"]) + UiStyle.Rich(UiStyle.Muted,
                        "  <size=9>" + UiStyle.Percent((double)row["HeadshotRate"]) + "</size>"),
                    TextAnchor.MiddleRight, UiStyle.SubText);
                WeaponCell(c, name, 960f, 114f, top, UiStyle.Num((double)row["Damage"]), TextAnchor.MiddleRight, UiStyle.Text);
            }

            if (rows.Count > limit)
            {
                Label(c, name, "0 0", "1 0", "14 8", "-14 24",
                    Lang(player, "MoreWeapons") + " " +
                    (rows.Count - limit).ToString(CultureInfo.InvariantCulture),
                    10, UiStyle.Muted, TextAnchor.MiddleRight, UiStyle.Reg);
            }
        }

        private void WeaponHeaderCell(CuiElementContainer c, string parent, float left, float width,
            string text, TextAnchor align)
        {
            Label(c, parent, "0 1", "0 1",
                UiStyle.F(left) + " -52", UiStyle.F(left + width) + " -34",
                text, 10, UiStyle.Muted, align, UiStyle.Reg);
        }

        private void WeaponCell(CuiElementContainer c, string parent, float left, float width,
            float top, string text, TextAnchor align, string color)
        {
            Label(c, parent, "0 1", "0 1",
                UiStyle.F(left) + " " + UiStyle.F(top - 22f),
                UiStyle.F(left + width) + " " + UiStyle.F(top),
                text, 11, color, align, UiStyle.Reg);
        }

        private void DrawSectionGather(CuiElementContainer c, BasePlayer player, AccountData a, StatLayer layer)
        {
            string n1 = UiBody + ".Gath1";
            Card(c, UiBody, n1, ColX(0, Col3), ColX(0, Col3) + Col3, RowTop, -BodyHeight, Lang(player, "CardGather"));
            Row(c, n1, n1, 0, Lang(player, "Gathered"), UiStyle.Num(layer.GatheredTotal));
            Row(c, n1, n1, 1, Lang(player, "GatherEvents"), UiStyle.Num(layer.GatherEvents));
            Row(c, n1, n1, 2, Lang(player, "Collectibles"), UiStyle.Num(layer.CollectiblesPicked));
            Row(c, n1, n1, 3, Lang(player, "Containers"), UiStyle.Num(layer.LootContainers));
            Row(c, n1, n1, 4, Lang(player, "CraftOps"), UiStyle.Num(layer.CraftOperations));
            Row(c, n1, n1, 5, Lang(player, "Crafted"), UiStyle.Num(layer.CraftedItemsTotal));
            Row(c, n1, n1, 6, Lang(player, "TopLoot"), ResourceLabel(TopKey(layer.LootedContainers)));
            Row(c, n1, n1, 7, Lang(player, "TopCraft"), ResourceLabel(TopKey(layer.CraftedItems)));

            DrawTopMap(c, player, UiBody + ".Res", ColX(1, Col3), Col3,
                Lang(player, "CardResources"), layer.Resources, 10);
            DrawTopMap(c, player, UiBody + ".Loot", ColX(2, Col3), Col3,
                Lang(player, "CardContainers"), layer.LootedContainers, 10);
        }

        private void DrawSectionBuild(CuiElementContainer c, BasePlayer player, AccountData a, StatLayer layer)
        {
            string n1 = UiBody + ".Bld1";
            Card(c, UiBody, n1, ColX(0, Col3), ColX(0, Col3) + Col3, RowTop, RowMid + 8f, Lang(player, "CardBuilding"));
            Row(c, n1, n1, 0, Lang(player, "Built"), UiStyle.Num(layer.BuildingPiecesPlaced));
            Row(c, n1, n1, 1, Lang(player, "Deployables"), UiStyle.Num(layer.DeployablesPlaced));
            Row(c, n1, n1, 2, Lang(player, "Upgraded"), UiStyle.Num(layer.StructuresUpgraded));
            Row(c, n1, n1, 3, Lang(player, "Destroyed"), UiStyle.Num(layer.StructuresDestroyed));
            Row(c, n1, n1, 4, Lang(player, "OwnBlocksLost"), UiStyle.Num(layer.OwnBlocksLost), UiStyle.Danger);

            string n2 = UiBody + ".Bld2";
            Card(c, UiBody, n2, ColX(1, Col3), ColX(1, Col3) + Col3, RowTop, RowMid + 8f, Lang(player, "CardRaid"));
            Row(c, n2, n2, 0, Lang(player, "Explosives"), UiStyle.Num(layer.ExplosivesUsed));
            Row(c, n2, n2, 1, Lang(player, "Rockets"), UiStyle.Num(layer.RocketsFired));
            Row(c, n2, n2, 2, Lang(player, "DoorsDestroyed"), UiStyle.Num(layer.DoorsDestroyed));
            Row(c, n2, n2, 3, Lang(player, "Cupboards"), UiStyle.Num(layer.CupboardsDestroyed));
            Row(c, n2, n2, 4, Lang(player, "Barrels"), UiStyle.Num(layer.BarrelsDestroyed));

            string n3 = UiBody + ".Bld3";
            Card(c, UiBody, n3, ColX(2, Col3), ColX(2, Col3) + Col3, RowTop, RowMid + 8f, Lang(player, "CardByGrade"));
            long gradeTotal = 1;
            for (int i = 0; i < layer.BlocksByGrade.Length; i++) gradeTotal += layer.BlocksByGrade[i];
            DrawShare(c, n3, n3 + ".G1", 0, Lang(player, "GradeWood"),
                layer.BlocksByGrade[(int)BuildingGrade.Enum.Wood], gradeTotal, "#B08A5A");
            DrawShare(c, n3, n3 + ".G2", 1, Lang(player, "GradeStone"),
                layer.BlocksByGrade[(int)BuildingGrade.Enum.Stone], gradeTotal, "#9AA0A6");
            DrawShare(c, n3, n3 + ".G3", 2, Lang(player, "GradeMetal"),
                layer.BlocksByGrade[(int)BuildingGrade.Enum.Metal], gradeTotal, "#C2C7CC");
            DrawShare(c, n3, n3 + ".G4", 3, Lang(player, "GradeTop"),
                layer.BlocksByGrade[(int)BuildingGrade.Enum.TopTier], gradeTotal, "#E0D6C2");

            DrawTopMap(c, player, UiBody + ".Expl", ColX(0, Col2), Col2,
                Lang(player, "CardExplosives"), layer.ExplosivesByPrefab, 6);
            DrawTopMap(c, player, UiBody + ".Deploy", ColX(1, Col2), Col2,
                Lang(player, "CardBuilt"), layer.BuiltEntities, 6);
        }

        private void DrawSectionPve(CuiElementContainer c, BasePlayer player, AccountData a, StatLayer layer)
        {
            string n1 = UiBody + ".Pve1";
            Card(c, UiBody, n1, ColX(0, Col3), ColX(0, Col3) + Col3, RowTop, RowMid + 8f, Lang(player, "CardNpc"));
            Row(c, n1, n1, 0, Lang(player, "NpcKills"), UiStyle.Num(layer.NpcKills));
            Row(c, n1, n1, 1, Lang(player, "AnimalKills"), UiStyle.Num(layer.AnimalKills));
            Row(c, n1, n1, 2, Lang(player, "Barrels"), UiStyle.Num(layer.BarrelsDestroyed));

            string n2 = UiBody + ".Pve2";
            Card(c, UiBody, n2, ColX(1, Col3), ColX(1, Col3) + Col3, RowTop, RowMid + 8f, Lang(player, "CardBosses"));
            Row(c, n2, n2, 0, Lang(player, "BradleyDamage"), UiStyle.Num(layer.BradleyDamage));
            Row(c, n2, n2, 1, Lang(player, "BradleyKills"), UiStyle.Num(layer.BradleyKills));
            Row(c, n2, n2, 2, Lang(player, "HeliDamage"), UiStyle.Num(layer.HelicopterDamage));
            Row(c, n2, n2, 3, Lang(player, "HeliKills"), UiStyle.Num(layer.HelicopterKills));

            string n3 = UiBody + ".Pve3";
            Card(c, UiBody, n3, ColX(2, Col3), ColX(2, Col3) + Col3, RowTop, RowMid + 8f, Lang(player, "CardEvents"));
            Row(c, n3, n3, 0, Lang(player, "HackableCrates"), UiStyle.Num(layer.HackableCratesOpened));
            Row(c, n3, n3, 1, Lang(player, "Airdrops"), UiStyle.Num(layer.AirdropsLooted));
            Row(c, n3, n3, 2, Lang(player, "Signals"), UiStyle.Num(layer.SignalsCalled));

            DrawTopMap(c, player, UiBody + ".Sci", ColX(0, Col2), Col2,
                Lang(player, "CardScientists"), layer.NpcKillsByPrefab, 6);
            DrawTopMap(c, player, UiBody + ".Anim", ColX(1, Col2), Col2,
                Lang(player, "CardAnimals"), layer.AnimalKillsByPrefab, 6);
        }

        private void DrawSectionActivity(CuiElementContainer c, BasePlayer player, AccountData a, StatLayer layer)
        {
            long seconds = LayerPlaySeconds(a.UserId, a, layer);

            string n1 = UiBody + ".Act1";
            Card(c, UiBody, n1, ColX(0, Col3), ColX(0, Col3) + Col3, RowTop, RowMid + 8f, Lang(player, "CardTime"));
            Row(c, n1, n1, 0, Lang(player, "InGame"), UiStyle.Time(seconds));
            Row(c, n1, n1, 1, Lang(player, "Sessions"), UiStyle.Num(layer.Sessions));
            Row(c, n1, n1, 2, Lang(player, "AverageSession"),
                UiStyle.Time(layer.Sessions <= 0 ? 0 : seconds / layer.Sessions));
            Row(c, n1, n1, 3, Lang(player, "FirstSeen"), UiStyle.Date(a.FirstSeenUtc));
            Row(c, n1, n1, 4, Lang(player, "LastSeen"), UiStyle.Date(a.LastSeenUtc));

            // Последние 10 сессий.
            string n2 = UiBody + ".Act2";
            Card(c, UiBody, n2, ColX(1, Col2), ColX(1, Col2) + Col2, RowTop, RowMid + 8f,
                Lang(player, "CardSessions"));

            if (a.RecentSessions.Count == 0)
            {
                Label(c, n2, "0 0", "1 1", "14 0", "-14 -34",
                    Lang(player, "NoSessions"), 11, UiStyle.Muted, TextAnchor.MiddleCenter, UiStyle.Reg);
            }
            else
            {
                int limit = a.RecentSessions.Count > 5 ? 5 : a.RecentSessions.Count;
                for (int i = 0; i < limit; i++)
                {
                    SessionRecord record = a.RecentSessions[i];
                    float top = -34f - i * 21f;

                    Label(c, n2, "0 1", "0.5 1",
                        "14 " + UiStyle.F(top - 18f), "0 " + UiStyle.F(top),
                        UiStyle.Date(record.StartedUtc), 11, UiStyle.SubText,
                        TextAnchor.MiddleLeft, UiStyle.Reg);
                    Label(c, n2, "0.4 1", "0.78 1",
                        "0 " + UiStyle.F(top - 18f), "0 " + UiStyle.F(top),
                        UiStyle.Date(record.EndedUtc), 11, UiStyle.Muted,
                        TextAnchor.MiddleRight, UiStyle.Reg);
                    Label(c, n2, "0.78 1", "1 1",
                        "0 " + UiStyle.F(top - 18f), "-14 " + UiStyle.F(top),
                        UiStyle.Time(record.Seconds), 11, UiStyle.Text, TextAnchor.MiddleRight);
                }
            }

            DrawDays(c, player, a);
            DrawHours(c, player, a);
        }

        // 30 столбиков: слева самый старый день, справа сегодня.
        private void DrawDays(CuiElementContainer c, BasePlayer player, AccountData a)
        {
            string name = UiBody + ".Days";
            Card(c, UiBody, name, ColX(0, Col2), ColX(0, Col2) + Col2, RowMid, -BodyHeight,
                Lang(player, "CardDays"));

            long[] days = a.DayPlaySeconds;
            long peak = 1;
            for (int i = 0; i < days.Length; i++) if (days[i] > peak) peak = days[i];

            const float chartLeft = 14f;
            const float chartWidth = Col2 - 28f;
            float step = chartWidth / days.Length;
            float barWidth = step - 3f;

            for (int i = 0; i < days.Length; i++)
            {
                float x = chartLeft + i * step;
                Bar(c, name, name + ".B" + i, "0 0", "0 0",
                    UiStyle.F(x) + " 26", UiStyle.F(x + barWidth) + " 106",
                    days[i] / (double)peak,
                    i == days.Length - 1 ? UiStyle.Positive : UiStyle.Accent);
            }

            Label(c, name, "0 0", "0.5 0", "14 6", "0 24",
                Lang(player, "Days30"), 10, UiStyle.Muted, TextAnchor.MiddleLeft, UiStyle.Reg);
            Label(c, name, "0.5 0", "1 0", "0 6", "-14 24",
                Lang(player, "PeakDay") + " " + UiStyle.Time(peak), 10, UiStyle.Muted,
                TextAnchor.MiddleRight, UiStyle.Reg);
        }

        // 24 узких столбика — распределение по часам суток UTC за вайп.
        private void DrawHours(CuiElementContainer c, BasePlayer player, AccountData a)
        {
            string name = UiBody + ".Hours";
            Card(c, UiBody, name, ColX(1, Col2), ColX(1, Col2) + Col2, RowMid, -BodyHeight,
                Lang(player, "CardHours"));

            long[] hours = a.Current.HourMinutes;
            long peak = 1;
            int best = 0;
            for (int i = 0; i < hours.Length; i++)
            {
                if (hours[i] <= peak) continue;
                peak = hours[i];
                best = i;
            }

            const float chartLeft = 14f;
            const float chartWidth = Col2 - 28f;
            float step = chartWidth / 24f;
            float barWidth = step - 4f;

            for (int i = 0; i < 24; i++)
            {
                float x = chartLeft + i * step;
                Bar(c, name, name + ".B" + i, "0 0", "0 0",
                    UiStyle.F(x) + " 38", UiStyle.F(x + barWidth) + " 106",
                    hours[i] / (double)peak,
                    i == best && peak > 1 ? UiStyle.Positive : UiStyle.Accent);

                if (i % 6 != 0) continue;
                Label(c, name, "0 0", "0 0",
                    UiStyle.F(x - 4f) + " 22", UiStyle.F(x + step + 4f) + " 36",
                    i.ToString("00", CultureInfo.InvariantCulture), 9, UiStyle.Muted,
                    TextAnchor.MiddleCenter, UiStyle.Reg);
            }

            Label(c, name, "0 0", "1 0", "14 4", "-14 20",
                peak > 1
                    ? Lang(player, "PeakHour") + " " + best.ToString("00", CultureInfo.InvariantCulture) + ":00 UTC"
                    : Lang(player, "NoHours"),
                10, UiStyle.Muted, TextAnchor.MiddleLeft, UiStyle.Reg);
        }

        private void DrawTopMap(CuiElementContainer c, BasePlayer player, string name,
            float left, float width, string title, Dictionary<string, long> map, int rows)
        {
            bool tall = width > Col3;
            Card(c, UiBody, name, left, left + width,
                tall ? RowMid : RowTop, tall ? -BodyHeight : -BodyHeight, title);

            if (map.Count == 0)
            {
                Label(c, name, "0 0", "1 1", "14 0", "-14 -34",
                    Lang(player, "NoData"), 11, UiStyle.Muted, TextAnchor.MiddleCenter, UiStyle.Reg);
                return;
            }

            _mapScratch.Clear();
            foreach (var pair in map) _mapScratch.Add(pair);
            _mapScratch.Sort((x, y) => y.Value.CompareTo(x.Value));

            long peak = Math.Max(1, _mapScratch[0].Value);
            int limit = _mapScratch.Count > rows ? rows : _mapScratch.Count;

            for (int i = 0; i < limit; i++)
            {
                var pair = _mapScratch[i];
                float top = -34f - i * 24f;

                Label(c, name, "0 1", "0.62 1",
                    "14 " + UiStyle.F(top - 16f), "0 " + UiStyle.F(top),
                    ResourceLabel(pair.Key), 11, UiStyle.SubText, TextAnchor.MiddleLeft, UiStyle.Reg);
                Label(c, name, "0.5 1", "1 1",
                    "0 " + UiStyle.F(top - 16f), "-14 " + UiStyle.F(top),
                    UiStyle.Num(pair.Value), 11, UiStyle.Text, TextAnchor.MiddleRight);
                Progress(c, name, name + ".P" + i, "0 1", "1 1",
                    "14 " + UiStyle.F(top - 21f), "-14 " + UiStyle.F(top - 18f),
                    pair.Value / (double)peak, UiStyle.AccentSoft);
            }
        }

        private readonly List<KeyValuePair<string, long>> _mapScratch = new List<KeyValuePair<string, long>>();

        private void DrawItemIcon(CuiElementContainer c, string parent, string name,
            int itemId, float left, float top, float size)
        {
            if (itemId == 0) return;

            c.Add(new CuiElement
            {
                Parent = parent,
                Name = name,
                Components =
                {
                    new CuiImageComponent { ItemId = itemId, SkinId = 0UL, Color = "1 1 1 1" },
                    new CuiRectTransformComponent
                    {
                        AnchorMin = "0 1", AnchorMax = "0 1",
                        OffsetMin = UiStyle.F(left) + " " + UiStyle.F(top - size),
                        OffsetMax = UiStyle.F(left + size) + " " + UiStyle.F(top)
                    }
                }
            });
        }

        // Человекочитаемое имя предмета. Строки трогаем только на отрисовке.
        private static string ItemLabel(int itemId)
        {
            if (itemId == 0) return "—";

            ItemDefinition definition = ItemManager.FindItemDefinition(itemId);
            if (definition == null) return itemId.ToString(CultureInfo.InvariantCulture);

            return string.IsNullOrEmpty(definition.displayName_english)
                ? definition.shortname
                : definition.displayName_english;
        }

        private static string ResourceLabel(string shortname)
        {
            if (string.IsNullOrEmpty(shortname)) return "—";

            ItemDefinition definition = ItemManager.FindItemDefinition(shortname);
            if (definition != null && !string.IsNullOrEmpty(definition.displayName_english))
                return definition.displayName_english;

            return shortname;
        }

        #endregion


        #region Lang

        private string Lang(BasePlayer player, string key)
        {
            return lang.GetMessage(key, this, player == null ? null : player.UserIDString);
        }

        protected override void LoadDefaultMessages()
        {
            lang.RegisterMessages(new Dictionary<string, string>
            {
                ["TitleSelf"] = "МОЙ ПРОФИЛЬ",
                ["TitleOther"] = "ПРОФИЛЬ ИГРОКА",
                ["BackToTop"] = "НАЗАД К ТОПУ",
                ["ResetStats"] = "СБРОСИТЬ СТАТИСТИКУ",
                ["Level"] = "УРОВЕНЬ",
                ["Wipes"] = "вайпов:",

                ["Period_wipe"] = "ВАЙП",
                ["Period_previous"] = "ПРОШЛЫЙ ВАЙП",
                ["Period_lifetime"] = "ВСЁ ВРЕМЯ",

                ["Section_summary"] = "СВОДКА",
                ["Section_pvp"] = "PVP",
                ["Section_weapons"] = "ОРУЖИЕ",
                ["Section_gather"] = "ДОБЫЧА",
                ["Section_build"] = "СТРОЙКА И РЕЙДЫ",
                ["Section_pve"] = "PVE",
                ["Section_activity"] = "АКТИВНОСТЬ",

                ["CardCombat"] = "БОЙ",
                ["CardShooting"] = "СТРЕЛЬБА",
                ["CardEconomy"] = "ЭКОНОМИКА",
                ["CardFavourite"] = "ЛЮБИМОЕ ОРУЖИЕ",
                ["CardRecords"] = "РЕКОРДЫ",
                ["CardDuels"] = "ПЕРЕСТРЕЛКИ",
                ["CardBodyParts"] = "КУДА ПОПАДАЛ",
                ["CardSupport"] = "ПОДДЕРЖКА",
                ["CardTopVictims"] = "ЧАЩЕ ВСЕГО УБИВАЛ",
                ["CardTopKillers"] = "ЧАЩЕ ВСЕГО УБИВАЛИ МЕНЯ",
                ["CardDeathCauses"] = "ОТЧЕГО ПОГИБАЛ",
                ["CardWeapons"] = "СТАТИСТИКА ПО ОРУЖИЮ",
                ["CardGather"] = "ДОБЫЧА И КРАФТ",
                ["CardResources"] = "РЕСУРСЫ",
                ["CardContainers"] = "КОНТЕЙНЕРЫ",
                ["CardBuilding"] = "СТРОИТЕЛЬСТВО",
                ["CardRaid"] = "РЕЙДЫ",
                ["CardByGrade"] = "СЛОМАНО ПО МАТЕРИАЛУ",
                ["CardExplosives"] = "ВЗРЫВЧАТКА",
                ["CardBuilt"] = "ЧТО СТРОИЛ",
                ["CardNpc"] = "NPC И ЖИВОТНЫЕ",
                ["CardBosses"] = "ТАНК И ВЕРТОЛЁТ",
                ["CardEvents"] = "СОБЫТИЯ",
                ["CardScientists"] = "УЧЁНЫЕ ПО ТИПАМ",
                ["CardAnimals"] = "ЖИВОТНЫЕ ПО ТИПАМ",
                ["CardTime"] = "ВРЕМЯ НА СЕРВЕРЕ",
                ["CardSessions"] = "ПОСЛЕДНИЕ СЕССИИ",
                ["CardDays"] = "АКТИВНОСТЬ ПО ДНЯМ",
                ["CardHours"] = "АКТИВНОСТЬ ПО ЧАСАМ",

                ["Kills"] = "Убийств игроков",
                ["KillsShort"] = "убийств",
                ["Deaths"] = "Смертей",
                ["Assists"] = "Ассистов",
                ["CurrentStreak"] = "Текущая серия",
                ["BestStreak"] = "Лучшая серия",
                ["Shots"] = "Выстрелов",
                ["Hits"] = "Попаданий",
                ["Accuracy"] = "Точность",
                ["Headshots"] = "Хедшотов",
                ["HeadshotRate"] = "Доля хедшотов",
                ["DamageDealt"] = "Урон нанесён",
                ["DamageReceived"] = "Урон получен",
                ["DamageShort"] = "урона",
                ["LongestShot"] = "Дальний выстрел",
                ["Wounded"] = "Был ранен",
                ["RevivedByOthers"] = "Подняли меня",
                ["RevivedOthers"] = "Поднял других",
                ["PartHead"] = "Голова",
                ["PartTorso"] = "Тело",
                ["PartLimbs"] = "Конечности",

                ["Gathered"] = "Добыто ресурсов",
                ["GatherEvents"] = "Заходов на добычу",
                ["Collectibles"] = "Подобрано с земли",
                ["Containers"] = "Вскрыто контейнеров",
                ["CraftOps"] = "Операций крафта",
                ["Crafted"] = "Создано предметов",
                ["TopResource"] = "Главный ресурс",
                ["TopLoot"] = "Главный контейнер",
                ["TopCraft"] = "Главный крафт",

                ["Built"] = "Построено блоков",
                ["Deployables"] = "Установлено предметов",
                ["Upgraded"] = "Улучшено блоков",
                ["Destroyed"] = "Сломано блоков",
                ["OwnBlocksLost"] = "Потеряно своих блоков",
                ["Explosives"] = "Использовано взрывчатки",
                ["Rockets"] = "Запущено ракет",
                ["DoorsDestroyed"] = "Выбито чужих дверей",
                ["Cupboards"] = "Выбито чужих шкафов",
                ["Barrels"] = "Разбито бочек",
                ["GradeWood"] = "Дерево",
                ["GradeStone"] = "Камень",
                ["GradeMetal"] = "Металл",
                ["GradeTop"] = "Высокое качество",

                ["NpcKills"] = "Убито NPC",
                ["AnimalKills"] = "Убито животных",
                ["BradleyDamage"] = "Урон по танку",
                ["BradleyKills"] = "Добито танков",
                ["HeliDamage"] = "Урон по вертолёту",
                ["HeliKills"] = "Добито вертолётов",
                ["HackableCrates"] = "Взломано ящиков",
                ["Airdrops"] = "Поднято аирдропов",
                ["Signals"] = "Вызвано сигналов",

                ["InGame"] = "В игре",
                ["Sessions"] = "Сессий",
                ["AverageSession"] = "Средняя сессия",
                ["FirstSeen"] = "Первый вход",
                ["LastSeen"] = "Последний вход",
                ["Days30"] = "последние 30 дней",
                ["PeakDay"] = "лучший день:",
                ["PeakHour"] = "чаще всего играет в",
                ["NoHours"] = "данных по часам пока нет",

                ["ColWeapon"] = "ОРУЖИЕ",
                ["ColKills"] = "УБИЙСТВ",
                ["ColShots"] = "ВЫСТРЕЛОВ",
                ["ColHits"] = "ПОПАДАНИЙ",
                ["ColAccuracy"] = "ТОЧНОСТЬ",
                ["ColHeadshots"] = "ХЕДШОТЫ",
                ["ColDamage"] = "УРОН",
                ["MoreWeapons"] = "ещё оружия:",

                ["NoData"] = "Пока пусто",
                ["NoDeaths"] = "Ни одной смерти",
                ["NoSessions"] = "Сессии ещё не записаны",
                ["NoWeaponYet"] = "Оружие ещё не использовалось",
                ["NoPrevious"] = "ПРОШЛОГО ВАЙПА НЕТ",
                ["NoPreviousHint"] = "Статистика появится здесь после первого вайпа.",
                ["NoProfile"] = "ПРОФИЛЬ НЕ НАЙДЕН",
                ["NoProfileHint"] = "Аккаунт этого игрока ещё не создан.",

                ["Cause_Player"] = "От игрока",
                ["Cause_Npc"] = "От NPC",
                ["Cause_Animal"] = "От животного",
                ["Cause_Fall"] = "Падение",
                ["Cause_Starvation"] = "Голод и жажда",
                ["Cause_Radiation"] = "Радиация",
                ["Cause_Drowned"] = "Утопление",
                ["Cause_Explosion"] = "Взрыв",
                ["Cause_Turret"] = "Турель",
                ["Cause_Bradley"] = "Танк",
                ["Cause_Helicopter"] = "Вертолёт",
                ["Cause_Suicide"] = "Самоубийство",
                ["Cause_Other"] = "Прочее"
            }, this, "ru");
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
