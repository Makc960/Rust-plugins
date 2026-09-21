using System;
using System.Collections.Generic;
using Newtonsoft.Json;
using Oxide.Core;
using Oxide.Core.Plugins;

namespace Oxide.Plugins
{
    [Info("Stacks", "Flux", "1.2.1")]
    public class Stacks : RustPlugin
    {
        #region Fields
        private readonly Dictionary<string, int> _originalStacks = new();
        #endregion

        #region Oxide-Api
        private void OnServerInitialized()
        {
            foreach (ItemDefinition item in ItemManager.itemList)
                _config.Categories.TryAdd(item.category.ToString(), 0);

            SaveConfig();
            ApplyStacks();
        }

        private void Unload()
        {
            foreach (ItemDefinition item in ItemManager.itemList)
                if (_originalStacks.TryGetValue(item.shortname, out int original))
                    item.stackable = original;
        }

        private object OnMaxStackable(Item item) => item?.info?.stackable;
        #endregion

        #region Core
        private void ApplyStacks()
        {
            foreach (ItemDefinition item in ItemManager.itemList)
            {
                if (!_originalStacks.ContainsKey(item.shortname))
                    _originalStacks[item.shortname] = item.stackable;

                int vanilla = _originalStacks[item.shortname];

                if (Excluded(item))
                {
                    item.stackable = vanilla;
                    continue;
                }

                if (_config.Stacks.TryGetValue(item.shortname, out int exact))
                {
                    item.stackable = Math.Max(1, exact);
                    continue;
                }

                int mult = 0;
                if (_config.Categories.TryGetValue(item.category.ToString(), out int cm) && cm > 0)
                    mult = cm;
                else if (_config.Multiplier > 0)
                    mult = _config.Multiplier;

                item.stackable = mult > 0
                    ? (int)Math.Min(int.MaxValue, Math.Max(1, (long)vanilla * mult))
                    : vanilla;
            }
        }

        private static bool Excluded(ItemDefinition item)
            => (item.condition.enabled && item.condition.max > 0f)
               || item.spawnAsBlueprint
               || item.GetComponent<ItemModContainer>() != null;
        #endregion

        #region API

        [HookMethod("GetOriginalStack")]
        private object GetOriginalStack(string shortname)
        {
            if (string.IsNullOrEmpty(shortname))
                return null;

            int original;
            return _originalStacks.TryGetValue(shortname, out original) ? (object)original : null;
        }
        #endregion

        #region Config
        private Configuration _config;

        private class Configuration
        {
            [JsonProperty(PropertyName = "Глобальный множитель стаков (0 = выкл)")]
            public int Multiplier = 0;

            [JsonProperty(PropertyName = "Множитель по категориям (0 = использовать глобальный)")]
            public Dictionary<string, int> Categories = new();

            [JsonProperty(PropertyName = "Точечные стаки по предмету (shortname - значение), приоритет над категориями")]
            public Dictionary<string, int> Stacks = new();

            public VersionNumber Version = new VersionNumber();
        }

        protected override void LoadConfig()
        {
            base.LoadConfig();
            try
            {
                _config = Config.ReadObject<Configuration>();
                if (_config == null) throw new Exception();

                if (_config.Version < Version)
                    UpdateConfigValues();

                SaveConfig();
            }
            catch (Exception ex)
            {
                PrintError($"Ваш файл конфигурации содержит ошибку. Использование значений конфигурации по умолчанию.\n{ex}");

                LoadDefaultConfig();
            }
        }

        private void UpdateConfigValues()
        {
            PrintWarning("Обнаружено обновление конфигурации! Обновление значений конфигурации...");

            if (_config.Version < new VersionNumber(1, 2, 0) && _config.Stacks.Count > 0)
            {
                _config.Stacks.Clear();
                PrintWarning("Список точечных стаков очищен — теперь стаки задаются по категориям (раздел \"Множитель по категориям\").");
            }

            _config.Version = Version;
            PrintWarning("Обновление конфигурации завершено!");
        }

        protected override void SaveConfig() => Config.WriteObject(_config);

        protected override void LoadDefaultConfig() => _config = new Configuration();
        #endregion
    }
}
